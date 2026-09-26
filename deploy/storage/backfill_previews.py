#!/usr/bin/env python3
"""Build small JPEG previews for large images that lack a stored variant.

Dry-run is the default. On --apply, verify the authoritative object bytes,
upload and read back a preview, then compare-and-set its key in MySQL.
"""

from __future__ import annotations

import argparse
import sys

from migrate_assets import database, digest, ensure_object, object_client, preview, required


def backfill_one(db, s3, bucket: str, asset_id: str) -> str:
    with db.cursor() as cursor:
        cursor.execute("SELECT id, content_type, byte_size, content_hash, content, object_key, "
                       "object_content_type, object_byte_size, object_hash, preview_key, preview_checked "
                       "FROM image_blob WHERE id=%s", (asset_id,))
        row = cursor.fetchone()
    if not row or row["preview_key"] or row["preview_checked"]:
        return "skipped"
    key = row["object_key"]
    if key:
        body = s3.get_object(Bucket=bucket, Key=key)["Body"].read()
        mime = row["object_content_type"] or row["content_type"]
        expected_size = row["object_byte_size"] or row["byte_size"]
        expected_hash = row["object_hash"] or row["content_hash"]
        if (len(body) != expected_size or not expected_hash or digest(body) != expected_hash
                or not key.endswith("/" + expected_hash)):
            raise RuntimeError("Image object differs from metadata")
    elif row["content"] is not None:
        body = bytes(row["content"])
        mime = row["content_type"]
        if (len(body) != row["byte_size"] or (row["content_hash"]
                and digest(body) != row["content_hash"])):
            raise RuntimeError("MySQL image differs from metadata")
    else:
        raise RuntimeError("Image has no available original bytes")
    thumbnail = preview(body, mime)
    if thumbnail is None:
        with db.cursor() as cursor:
            cursor.execute("UPDATE image_blob SET preview_checked=TRUE WHERE id=%s "
                           "AND preview_key IS NULL AND preview_checked=FALSE "
                           "AND object_key <=> %s AND content_hash <=> %s",
                           (asset_id, key, row["content_hash"]))
            return "not-smaller" if cursor.rowcount == 1 else "concurrent-change"
    thumbnail_hash = digest(thumbnail)
    preview_key = f"previews/{asset_id}/{thumbnail_hash}"
    ensure_object(s3, bucket, preview_key, thumbnail, "image/jpeg", thumbnail_hash)
    with db.cursor() as cursor:
        cursor.execute("UPDATE image_blob SET preview_key=%s, preview_checked=TRUE "
                       "WHERE id=%s AND preview_key IS NULL AND preview_checked=FALSE "
                       "AND object_key <=> %s AND content_hash <=> %s",
                       (preview_key, asset_id, key, row["content_hash"]))
        return "created" if cursor.rowcount == 1 else "concurrent-change"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="upload and mark verified previews")
    parser.add_argument("--limit", type=int, default=100, help="maximum images per run; 1 to 500")
    args = parser.parse_args()
    if not 1 <= args.limit <= 500:
        parser.error("--limit must be between 1 and 500")
    db = database()
    try:
        with db.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) AS pending FROM image_blob WHERE preview_key IS NULL "
                           "AND preview_checked=FALSE "
                           "AND (object_key IS NOT NULL OR content IS NOT NULL) "
                           "AND (content_type IN ('image/png','image/jpeg','image/webp') "
                           "OR object_content_type='image/webp') "
                           "AND COALESCE(object_byte_size,byte_size)>220000")
            total = cursor.fetchone()["pending"]
            cursor.execute("SELECT id FROM image_blob WHERE preview_key IS NULL "
                           "AND preview_checked=FALSE "
                           "AND (object_key IS NOT NULL OR content IS NOT NULL) "
                           "AND (content_type IN ('image/png','image/jpeg','image/webp') "
                           "OR object_content_type='image/webp') "
                           "AND COALESCE(object_byte_size,byte_size)>220000 ORDER BY id LIMIT %s",
                           (args.limit,))
            ids = [row["id"] for row in cursor.fetchall()]
        print(f"image_previews: pending={total}")
        if not args.apply:
            return 0
        with db.cursor() as cursor:
            cursor.execute("SELECT @@read_only AS read_only, @@super_read_only AS super_read_only")
            role = cursor.fetchone()
        if role["read_only"] or role["super_read_only"]:
            raise RuntimeError("Refusing to update a read-only MySQL replica")
        s3 = object_client()
        bucket = required("WAO_STORAGE_BUCKET")
        counts = {"created": 0, "skipped": 0, "not-smaller": 0,
                  "concurrent-change": 0}
        failures = 0
        for asset_id in ids:
            try:
                counts[backfill_one(db, s3, bucket, asset_id)] += 1
            except Exception as exc:
                failures += 1
                print(f"FAIL {asset_id}: {type(exc).__name__}: {str(exc)[:120]}", file=sys.stderr)
        print(f"image_previews: {counts}, failures={failures}")
        return 1 if failures else 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
