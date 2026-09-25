#!/usr/bin/env python3
"""Remove superseded online PNG objects after the WebP and archive safety gates.

Dry-run is the default. Actual deletion requires an explicit approval flag,
a restored S3 backup manifest, a seven-day switch grace period, and a fresh
readback of both the permanent original PNG archive and online WebP.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import os
import subprocess
import sys

from botocore.exceptions import ClientError

from archive_original_png import archive_key, download_original
from migrate_assets import (database, digest, object_client,
                            pixel_equivalent_png_webp, required)
from reclaim_mysql_blobs import acquire_asset_lock, load_manifest
from webdav_client import build_client


def missing_object(error: ClientError) -> bool:
    return error.response.get("ResponseMetadata", {}).get("HTTPStatusCode") == 404


def gc_one(db, s3, bucket: str, backup: dict, webdav, passphrase: Path,
           remote_root: str, asset_id: str, min_age_days: int) -> str:
    with db.cursor() as cursor:
        cursor.execute("SELECT id, byte_size, content_hash, content, object_key, "
                       "object_content_type, object_byte_size, object_hash, "
                       "original_archive_key FROM image_blob WHERE id=%s "
                       "AND content_type='image/png' AND object_content_type='image/webp' "
                       "AND webp_activated_at <= UTC_TIMESTAMP(3) - INTERVAL %s DAY",
                       (asset_id, min_age_days))
        row = cursor.fetchone()
    if not row:
        return "skipped"
    original_hash = row["content_hash"]
    webp_hash = row["object_hash"]
    original_key = f"images/{asset_id}/{original_hash}"
    if (not original_hash or not webp_hash or not row["object_key"]
            or row["object_key"] == original_key
            or row["object_key"] != f"images/{asset_id}/{webp_hash}"
            or row["original_archive_key"] != archive_key(remote_root, original_hash)):
        raise RuntimeError("PNG and WebP metadata are incomplete or inconsistent")
    original = download_original(webdav, row["original_archive_key"], passphrase)
    if len(original) != row["byte_size"] or digest(original) != original_hash:
        raise RuntimeError("Original PNG archive differs from MySQL metadata")
    if row["content"] is not None and bytes(row["content"]) != original:
        raise RuntimeError("Retained MySQL PNG differs from permanent archive")
    saved = backup.get(row["object_key"])
    if (not saved or saved.get("bytes") != row["object_byte_size"]
            or saved.get("sha256") != webp_hash):
        raise RuntimeError("Online WebP is absent from restored S3 backup")
    webp = s3.get_object(Bucket=bucket, Key=row["object_key"])["Body"].read()
    if (len(webp) != row["object_byte_size"] or digest(webp) != webp_hash
            or not pixel_equivalent_png_webp(original, webp)):
        raise RuntimeError("Online WebP differs from original PNG pixels")
    with db.cursor() as cursor:
        cursor.execute("SELECT (SELECT COUNT(*) FROM image_blob WHERE object_key=%s "
                       "OR preview_key=%s) + (SELECT COUNT(*) FROM file_blob "
                       "WHERE object_key=%s) AS references",
                       (original_key, original_key, original_key))
        if cursor.fetchone()["references"]:
            raise RuntimeError("Old PNG object is still referenced")
    try:
        old = s3.get_object(Bucket=bucket, Key=original_key)["Body"].read()
    except ClientError as exc:
        if missing_object(exc):
            return "already-removed"
        raise
    if old != original:
        raise RuntimeError("Old online PNG differs from permanent archive")

    db.begin()
    try:
        with db.cursor() as cursor:
            cursor.execute("SELECT object_key, object_hash, original_archive_key FROM image_blob "
                           "WHERE id=%s AND webp_activated_at <= UTC_TIMESTAMP(3) - INTERVAL %s DAY "
                           "FOR UPDATE", (asset_id, min_age_days))
            locked = cursor.fetchone()
            if (not locked or locked["object_key"] != row["object_key"]
                    or locked["object_hash"] != webp_hash
                    or locked["original_archive_key"] != row["original_archive_key"]):
                db.rollback()
                return "concurrent-change"
            cursor.execute("SELECT (SELECT COUNT(*) FROM image_blob WHERE object_key=%s "
                           "OR preview_key=%s) + (SELECT COUNT(*) FROM file_blob "
                           "WHERE object_key=%s) AS references",
                           (original_key, original_key, original_key))
            if cursor.fetchone()["references"]:
                raise RuntimeError("Old PNG object is still referenced")
            s3.delete_object(Bucket=bucket, Key=original_key)
            try:
                s3.head_object(Bucket=bucket, Key=original_key)
            except ClientError as exc:
                if not missing_object(exc):
                    raise
            else:
                raise RuntimeError("Old PNG object still exists after delete")
    except Exception:
        db.rollback()
        raise
    else:
        db.commit()
        return "deleted"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="delete verified old online PNG objects")
    parser.add_argument("--confirm-approved-online-delete", action="store_true",
                        help="acknowledge explicit approval for production object deletion")
    parser.add_argument("--backup-manifest", type=Path,
                        help="manifest.json from an independently restored S3 backup")
    parser.add_argument("--limit", type=int, default=25, help="maximum images per run; 1 to 100")
    parser.add_argument("--min-age-days", type=int, default=7,
                        help="minimum time since WebP activation; at least seven days")
    args = parser.parse_args()
    if not 1 <= args.limit <= 100 or args.min_age_days < 7:
        parser.error("--limit must be 1..100 and --min-age-days must be at least 7")
    if args.apply and (not args.confirm_approved_online_delete or not args.backup_manifest):
        parser.error("--apply requires explicit approval flag and restored --backup-manifest")
    lock = None
    if args.apply:
        if os.geteuid() != 0:
            raise RuntimeError("Approved online object deletion must run as root")
        lock = acquire_asset_lock(Path(os.getenv(
            "WAO_IMAGE_BACKUP_LOCK_FILE", "/run/lock/wao-image-backup.lock")))
    try:
        db = database()
        try:
            return run(db, args)
        finally:
            db.close()
    finally:
        if lock is not None:
            lock.close()


def run(db, args) -> int:
    with db.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) AS eligible FROM image_blob "
                       "WHERE content_type='image/png' AND object_content_type='image/webp' "
                       "AND webp_activated_at <= UTC_TIMESTAMP(3) - INTERVAL %s DAY",
                       (args.min_age_days,))
        eligible = cursor.fetchone()["eligible"]
        cursor.execute("SELECT id FROM image_blob WHERE content_type='image/png' "
                       "AND object_content_type='image/webp' "
                       "AND webp_activated_at <= UTC_TIMESTAMP(3) - INTERVAL %s DAY "
                       "ORDER BY webp_activated_at, id LIMIT %s",
                       (args.min_age_days, args.limit))
        ids = [row["id"] for row in cursor.fetchall()]
    print(f"original_png_gc: eligible={eligible}")
    if not args.apply:
        return 0
    if os.getenv("WAO_STORAGE_MODE") != "s3":
        raise RuntimeError("Refusing online object deletion outside S3 mode")
    provenance = subprocess.run(
        ["python3", "/usr/local/lib/wao-backup/verify-backup-source.py"],
        capture_output=True, check=False, timeout=45)
    if provenance.returncode:
        raise RuntimeError("WAO writer and return replica are not verified")
    with db.cursor() as cursor:
        cursor.execute("SELECT @@read_only AS read_only, @@super_read_only AS super_read_only")
        role = cursor.fetchone()
    if role["read_only"] or role["super_read_only"]:
        raise RuntimeError("Refusing online object deletion from a replica")
    bucket = required("WAO_STORAGE_BUCKET")
    backup = load_manifest(args.backup_manifest, bucket)
    passphrase = Path(os.getenv("WAO_ORIGINAL_PASSPHRASE_FILE",
                             "/etc/wao-backup/encryption-passphrase"))
    if not passphrase.is_file():
        raise RuntimeError("Original archive passphrase is missing")
    webdav, config = build_client(
        Path(os.getenv("WAO_ORIGINAL_WEBDAV_CONFIG", "/etc/wao-backup/webdav.env")),
        allow_http=False)
    s3 = object_client()
    remote_root = config.get("WAO_BACKUP_REMOTE_ROOT", "WAO-backups")
    counts = {"deleted": 0, "already-removed": 0, "skipped": 0, "concurrent-change": 0}
    failures = 0
    for asset_id in ids:
        try:
            counts[gc_one(db, s3, bucket, backup, webdav, passphrase,
                          remote_root, asset_id, args.min_age_days)] += 1
        except Exception as exc:
            failures += 1
            print(f"FAIL {asset_id}: {type(exc).__name__}: {str(exc)[:120]}", file=sys.stderr)
    print(f"original_png_gc: {counts}, failures={failures}")
    return 1 if failures else 0

if __name__ == "__main__":
    raise SystemExit(main())
