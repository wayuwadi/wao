#!/usr/bin/env python3
"""Reclaim verified MySQL asset bytes after an independently restored S3 backup.

The default action is a read-only inventory. --apply requires a restored object
backup manifest and an explicit S3-only runtime setting. Process a small bounded
number of rows per invocation; each row is checked against S3 and the backup
manifest before its MySQL body is cleared with a compare-and-set update.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from migrate_assets import TABLES, database, digest, object_client, required


def load_manifest(path: Path, bucket: str) -> dict:
    manifest = json.loads(path.read_text())
    if (manifest.get("formatVersion") != 1 or manifest.get("bucket") != bucket
            or not isinstance(manifest.get("objects"), dict)):
        raise RuntimeError("Backup manifest is missing, invalid or from another bucket")
    return manifest["objects"]


def clear_one(db, client, bucket: str, backup: dict, table: str, asset_id: str) -> int:
    if table not in TABLES:
        raise ValueError("Unexpected asset table")
    with db.cursor() as cursor:
        cursor.execute(f"SELECT object_key, byte_size, content_hash, content FROM {table} "
                       "WHERE id=%s AND object_key IS NOT NULL AND content IS NOT NULL", (asset_id,))
        row = cursor.fetchone()
    if not row:
        return 0
    key, size, expected, body = (row["object_key"], row["byte_size"], row["content_hash"],
                                 bytes(row["content"]))
    if not expected or len(body) != size or digest(body) != expected or not key.endswith("/" + expected):
        raise RuntimeError("MySQL body or object key differs from stored metadata")
    backed_up = backup.get(key)
    if not backed_up or backed_up.get("bytes") != size or backed_up.get("sha256") != expected:
        raise RuntimeError("Object is absent from the independently verified backup")
    object_bytes = client.get_object(Bucket=bucket, Key=key)["Body"].read()
    if object_bytes != body:
        raise RuntimeError("S3 object differs from the MySQL original")
    with db.cursor() as cursor:
        cursor.execute(f"UPDATE {table} SET content=NULL WHERE id=%s AND object_key=%s "
                       "AND byte_size=%s AND SHA2(content,256)=%s",
                       (asset_id, key, size, expected))
        if cursor.rowcount != 1:
            raise RuntimeError("Asset changed concurrently; no MySQL body was cleared")
    return size


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="clear verified MySQL bodies")
    parser.add_argument("--backup-manifest", type=Path,
                        help="manifest.json from an independently restored encrypted backup")
    parser.add_argument("--limit", type=int, default=25, help="maximum rows per table; 1 to 100")
    args = parser.parse_args()
    if not 1 <= args.limit <= 100:
        parser.error("--limit must be between 1 and 100")
    if args.apply and not args.backup_manifest:
        parser.error("--apply requires --backup-manifest")
    if args.apply and (os.getenv("WAO_STORAGE_MODE") != "s3"
                       or os.getenv("WAO_STORAGE_RETAIN_MYSQL") != "false"):
        parser.error("--apply requires S3-only runtime settings after both WAO nodes have switched")

    db = database()
    try:
        with db.cursor() as cursor:
            cursor.execute("SELECT @@read_only AS read_only, @@super_read_only AS super_read_only")
            role = cursor.fetchone()
        if args.apply and (role["read_only"] or role["super_read_only"]):
            raise RuntimeError("Refusing to clear a read-only MySQL replica")
        client = object_client() if args.apply else None
        bucket = required("WAO_STORAGE_BUCKET") if args.apply else ""
        backup = load_manifest(args.backup_manifest, bucket) if args.apply else {}
        failed = 0
        for table in TABLES:
            with db.cursor() as cursor:
                cursor.execute(f"SELECT COUNT(*) AS pending, COALESCE(SUM(byte_size),0) AS bytes "
                               f"FROM {table} WHERE object_key IS NOT NULL AND content IS NOT NULL")
                totals = cursor.fetchone()
                cursor.execute(f"SELECT id FROM {table} WHERE object_key IS NOT NULL "
                               "AND content IS NOT NULL ORDER BY id LIMIT %s", (args.limit,))
                ids = [row["id"] for row in cursor.fetchall()]
            print(f"{table}: pending={totals['pending']} bytes={totals['bytes']}", flush=True)
            if not args.apply:
                continue
            cleared = 0
            cleared_bytes = 0
            for asset_id in ids:
                try:
                    size = clear_one(db, client, bucket, backup, table, asset_id)
                    cleared += bool(size)
                    cleared_bytes += size
                except Exception as exc:
                    failed += 1
                    print(f"FAIL {table} {asset_id}: {type(exc).__name__}: {str(exc)[:120]}", flush=True)
            print(f"{table}: cleared={cleared} bytes={cleared_bytes}", flush=True)
        print(f"failures={failed}", flush=True)
        return 1 if failed else 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
