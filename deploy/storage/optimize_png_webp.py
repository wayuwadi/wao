#!/usr/bin/env python3
"""Replace active PNG objects with pixel-exact lossless WebP after original archival.

The original PNG stays in its independently encrypted WebDAV archive and the
old S3 object remains untouched until a separate approved garbage collection.
Dry-run is the default. Conversion is skipped when WebP is not smaller or the
decoded pixels, dimensions or ICC profile differ.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from archive_original_png import archive_key, verify_remote
from migrate_assets import (database, digest, ensure_object, lossless_webp,
                            object_client, pixel_equivalent_png_webp, preview, required)
from webdav_client import build_client


def optimize_one(db, s3, bucket: str, asset_id: str, archive_verifier=None) -> str:
    with db.cursor() as cursor:
        cursor.execute("SELECT id, content_type, byte_size, content_hash, content, object_key, "
                       "object_content_type, original_archive_key, preview_key, lossless_webp_checked "
                       "FROM image_blob WHERE id=%s", (asset_id,))
        row = cursor.fetchone()
    if (not row or row["content_type"] != "image/png" or row["lossless_webp_checked"]
            or not row["object_key"] or not row["original_archive_key"]):
        return "skipped"
    if (row["object_content_type"] or row["content_type"]) != "image/png":
        raise RuntimeError("Only an original PNG object may be optimized")
    original = bytes(row["content"]) if row["content"] is not None else s3.get_object(
        Bucket=bucket, Key=row["object_key"])["Body"].read()
    original_hash = digest(original)
    if (len(original) != row["byte_size"] or original_hash != row["content_hash"]
            or not row["object_key"].endswith("/" + original_hash)
            or not row["original_archive_key"].endswith("/" + original_hash + ".png.gpg")):
        raise RuntimeError("Original PNG or archive marker differs from metadata")
    if archive_verifier is None:
        raise RuntimeError("Original PNG archive must be verified before WebP activation")
    archive_verifier(row["original_archive_key"], original)
    stored_png = s3.get_object(Bucket=bucket, Key=row["object_key"])["Body"].read()
    if stored_png != original:
        raise RuntimeError("Original S3 PNG differs from MySQL source")
    preview_key = row["preview_key"]
    if len(original) > 220_000 and not preview_key:
        thumbnail = preview(original, "image/png")
        if thumbnail is not None:
            thumbnail_hash = digest(thumbnail)
            preview_key = f"previews/{asset_id}/{thumbnail_hash}"
            ensure_object(s3, bucket, preview_key, thumbnail, "image/jpeg", thumbnail_hash)
    optimized = lossless_webp(original)
    if optimized is None:
        with db.cursor() as cursor:
            cursor.execute("UPDATE image_blob SET lossless_webp_checked=TRUE, preview_key=%s "
                           "WHERE id=%s AND object_key=%s AND original_archive_key=%s "
                           "AND preview_key <=> %s AND byte_size=%s AND content_hash=%s "
                           "AND lossless_webp_checked=FALSE",
                           (preview_key, asset_id, row["object_key"], row["original_archive_key"],
                            row["preview_key"], len(original), original_hash))
            return "not-smaller" if cursor.rowcount == 1 else "concurrent-change"
    optimized_hash = digest(optimized)
    key = f"images/{asset_id}/{optimized_hash}"
    ensure_object(s3, bucket, key, optimized, "image/webp", optimized_hash)
    downloaded = s3.get_object(Bucket=bucket, Key=key)["Body"].read()
    if not pixel_equivalent_png_webp(original, downloaded):
        raise RuntimeError("Uploaded WebP differs from original PNG pixels")
    with db.cursor() as cursor:
        cursor.execute("UPDATE image_blob SET object_key=%s, object_content_type='image/webp', "
                       "object_byte_size=%s, object_hash=%s, preview_key=%s, "
                       "lossless_webp_checked=TRUE, webp_activated_at=UTC_TIMESTAMP(3) "
                       "WHERE id=%s AND object_key=%s AND original_archive_key=%s "
                       "AND preview_key <=> %s AND byte_size=%s AND content_hash=%s "
                       "AND lossless_webp_checked=FALSE",
                       (key, len(optimized), optimized_hash, preview_key, asset_id, row["object_key"],
                        row["original_archive_key"], row["preview_key"], len(original), original_hash))
        return "optimized" if cursor.rowcount == 1 else "concurrent-change"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="upload WebP and CAS-switch image rows")
    parser.add_argument("--limit", type=int, default=100, help="maximum PNGs per run; 1 to 500")
    args = parser.parse_args()
    if not 1 <= args.limit <= 500:
        parser.error("--limit must be between 1 and 500")
    db = database()
    try:
        with db.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) AS pending, COALESCE(SUM(byte_size),0) AS bytes "
                           "FROM image_blob WHERE content_type='image/png' "
                           "AND object_key IS NOT NULL AND original_archive_key IS NOT NULL "
                           "AND lossless_webp_checked=FALSE")
            totals = cursor.fetchone()
            cursor.execute("SELECT id FROM image_blob WHERE content_type='image/png' "
                           "AND object_key IS NOT NULL AND original_archive_key IS NOT NULL "
                           "AND lossless_webp_checked=FALSE ORDER BY id LIMIT %s", (args.limit,))
            ids = [row["id"] for row in cursor.fetchall()]
        print(f"lossless_webp: pending={totals['pending']} source_bytes={totals['bytes']}")
        if not args.apply:
            return 0
        with db.cursor() as cursor:
            cursor.execute("SELECT @@read_only AS read_only, @@super_read_only AS super_read_only")
            role = cursor.fetchone()
        if role["read_only"] or role["super_read_only"]:
            raise RuntimeError("Refusing to update a read-only MySQL replica")
        s3 = object_client()
        bucket = required("WAO_STORAGE_BUCKET")
        config_path = Path(os.getenv("WAO_ORIGINAL_WEBDAV_CONFIG", "/etc/wao-backup/webdav.env"))
        passphrase = Path(os.getenv("WAO_ORIGINAL_PASSPHRASE_FILE",
                                 "/etc/wao-backup/encryption-passphrase"))
        if not passphrase.is_file():
            raise RuntimeError("Original archive passphrase is missing")
        webdav, config = build_client(config_path, allow_http=False)
        remote_root = config.get("WAO_BACKUP_REMOTE_ROOT", "WAO-backups")

        def verify_archive(key, original):
            if key != archive_key(remote_root, digest(original)):
                raise RuntimeError("Original archive key differs from PNG checksum")
            verify_remote(webdav, key, original, passphrase)

        counts = {"optimized": 0, "not-smaller": 0, "skipped": 0,
                  "concurrent-change": 0}
        failed = 0
        for asset_id in ids:
            try:
                counts[optimize_one(db, s3, bucket, asset_id, verify_archive)] += 1
            except Exception as exc:
                failed += 1
                print(f"FAIL {asset_id}: {type(exc).__name__}: {str(exc)[:120]}", file=sys.stderr)
        print(f"lossless_webp: {counts}, failures={failed}")
        return 1 if failed else 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
