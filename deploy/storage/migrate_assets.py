#!/usr/bin/env python3
"""Copy immutable MySQL image/file bytes to a private S3 bucket, then CAS-mark rows.

Dry-run is the default. This command never clears MySQL content. It can run while
WAO serves traffic: concurrent file replacements fail the compare-and-set and are
picked up on the next pass. Credentials come only from the process environment.
"""

from __future__ import annotations

import argparse
from hashlib import sha256
from io import BytesIO
import os
import sys

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError
from PIL import Image, ImageOps, UnidentifiedImageError
import pymysql

TABLES = ("image_blob", "file_blob")
Image.MAX_IMAGE_PIXELS = 16_000_000


def required(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise RuntimeError(f"Missing {name}")
    return value


def database():
    # On Linux2 MYSQL_HOST addresses the native return replica; migration and
    # the object-backup inventory must follow the authoritative WAO writer.
    host = os.getenv("MYSQL_WRITER_HOST") or required("MYSQL_HOST")
    if host == "host.docker.internal":
        host = "127.0.0.1"
    port = (os.getenv("MYSQL_WRITER_PORT", "3306") if os.getenv("MYSQL_WRITER_HOST")
            else os.getenv("MYSQL_PORT", "3306"))
    return pymysql.connect(host=host, port=int(port),
                           user=required("DB_USER"), password=required("DB_PASS"),
                           database=required("DB_NAME"), autocommit=True,
                           cursorclass=pymysql.cursors.DictCursor, read_timeout=60, write_timeout=60)


def object_client():
    return boto3.client("s3", endpoint_url=required("WAO_STORAGE_ENDPOINT"),
                        region_name=os.getenv("WAO_STORAGE_REGION", "garage"),
                        aws_access_key_id=required("WAO_STORAGE_ACCESS_KEY"),
                        aws_secret_access_key=required("WAO_STORAGE_SECRET_KEY"),
                        config=Config(signature_version="s3v4", s3={"addressing_style": "path"},
                                      retries={"max_attempts": 3, "mode": "standard"}))


def digest(data: bytes) -> str:
    return sha256(data).hexdigest()


def preview(data: bytes, mime: str) -> bytes | None:
    if len(data) <= 220_000 or mime not in ("image/png", "image/jpeg"):
        return None
    try:
        with Image.open(BytesIO(data)) as image:
            if image.width * image.height > Image.MAX_IMAGE_PIXELS:
                return None
            image = ImageOps.exif_transpose(image)
            image.thumbnail((768, 768), Image.Resampling.LANCZOS)
            if image.mode != "RGB":
                white = Image.new("RGB", image.size, "white")
                if "A" in image.getbands():
                    white.paste(image, mask=image.getchannel("A"))
                else:
                    white.paste(image)
                image = white
            output = BytesIO()
            image.save(output, format="JPEG", quality=84, optimize=True)
            result = output.getvalue()
            return result if len(result) < len(data) else None
    except (OSError, ValueError, UnidentifiedImageError, Image.DecompressionBombError):
        return None


def ensure_object(client, bucket: str, key: str, data: bytes, mime: str, expected: str) -> None:
    try:
        head = client.head_object(Bucket=bucket, Key=key)
    except ClientError as error:
        if error.response.get("ResponseMetadata", {}).get("HTTPStatusCode") != 404:
            raise
        client.put_object(Bucket=bucket, Key=key, Body=data, ContentType=mime,
                          Metadata={"sha256": expected})
    else:
        if head["ContentLength"] != len(data) or head.get("Metadata", {}).get("sha256") != expected:
            raise RuntimeError("Object key collision or incomplete existing upload")
    downloaded = client.get_object(Bucket=bucket, Key=key)["Body"].read()
    if len(downloaded) != len(data) or digest(downloaded) != expected:
        raise RuntimeError("Object readback differs from MySQL bytes")


def migrate_row(db, client, bucket: str, table: str, asset_id: str) -> str:
    with db.cursor() as cursor:
        cursor.execute(f"SELECT id, content_type, byte_size, content_hash, content, object_key "
                       f"FROM {table} WHERE id=%s", (asset_id,))
        row = cursor.fetchone()
    if row is None or row["object_key"] is not None:
        return "skipped"
    if row["content"] is None:
        raise RuntimeError("Neither MySQL bytes nor object key exists")
    data = bytes(row["content"])
    content_hash = digest(data)
    if len(data) != row["byte_size"] or (row["content_hash"] and row["content_hash"] != content_hash):
        raise RuntimeError("MySQL byte length or stored checksum mismatch")
    prefix = "images" if table == "image_blob" else "files"
    key = f"{prefix}/{asset_id}/{content_hash}"
    ensure_object(client, bucket, key, data, row["content_type"], content_hash)
    preview_key = None
    if table == "image_blob":
        smaller = preview(data, row["content_type"])
        if smaller is not None:
            preview_hash = digest(smaller)
            preview_key = f"previews/{asset_id}/{preview_hash}"
            ensure_object(client, bucket, preview_key, smaller, "image/jpeg", preview_hash)
    with db.cursor() as cursor:
        if table == "image_blob":
            cursor.execute("UPDATE image_blob SET object_key=%s, preview_key=%s, content_hash=%s "
                           "WHERE id=%s AND object_key IS NULL AND byte_size=%s AND SHA2(content,256)=%s",
                           (key, preview_key, content_hash, asset_id, len(data), content_hash))
        else:
            cursor.execute("UPDATE file_blob SET object_key=%s, content_hash=%s "
                           "WHERE id=%s AND object_key IS NULL AND byte_size=%s AND SHA2(content,256)=%s",
                           (key, content_hash, asset_id, len(data), content_hash))
        return "copied" if cursor.rowcount == 1 else "concurrent-change"


def verify_row(db, client, bucket: str, table: str, asset_id: str) -> None:
    with db.cursor() as cursor:
        cursor.execute(f"SELECT object_key, byte_size, content_hash, content FROM {table} WHERE id=%s",
                       (asset_id,))
        row = cursor.fetchone()
    if not row or not row["object_key"] or not row["content_hash"]:
        raise RuntimeError("Missing key or checksum")
    downloaded = client.get_object(Bucket=bucket, Key=row["object_key"])["Body"].read()
    if len(downloaded) != row["byte_size"] or digest(downloaded) != row["content_hash"]:
        raise RuntimeError("Stored object does not match metadata")
    if row["content"] is not None and downloaded != bytes(row["content"]):
        raise RuntimeError("Object differs from retained MySQL bytes")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="copy bytes and update object keys")
    parser.add_argument("--verify-only", action="store_true", help="read back all keyed objects")
    parser.add_argument("--limit", type=int, default=0, help="maximum rows per table; zero is all")
    args = parser.parse_args()
    if args.apply and args.verify_only:
        parser.error("choose --apply or --verify-only")
    db = database()
    if args.apply:
        with db.cursor() as cursor:
            cursor.execute("SELECT @@read_only AS read_only, @@super_read_only AS super_read_only")
            role = cursor.fetchone()
        if role["read_only"] or role["super_read_only"]:
            db.close()
            raise RuntimeError("Refusing to update a read-only MySQL replica")
    client = object_client() if args.apply or args.verify_only else None
    bucket = required("WAO_STORAGE_BUCKET") if client else ""
    failed = []
    try:
        for table in TABLES:
            predicate = "object_key IS NOT NULL" if args.verify_only else "object_key IS NULL"
            with db.cursor() as cursor:
                cursor.execute(f"SELECT id FROM {table} WHERE {predicate} ORDER BY id")
                ids = [item["id"] for item in cursor.fetchall()]
            if args.limit:
                ids = ids[:args.limit]
            print(f"{table}: {len(ids)} {'keyed' if args.verify_only else 'pending'} rows", flush=True)
            if not (args.apply or args.verify_only):
                continue
            counts = {"copied": 0, "skipped": 0, "concurrent-change": 0}
            for asset_id in ids:
                try:
                    if args.verify_only:
                        verify_row(db, client, bucket, table, asset_id)
                        counts["copied"] += 1
                    else:
                        counts[migrate_row(db, client, bucket, table, asset_id)] += 1
                except Exception as error:  # one bad object must not hide the rest of the audit
                    failed.append((table, asset_id, type(error).__name__, str(error)[:160]))
            print(f"{table}: {counts}", flush=True)
    finally:
        db.close()
    for table, asset_id, error_type, reason in failed[:30]:
        print(f"FAIL {table} {asset_id}: {error_type}: {reason}", file=sys.stderr)
    print(f"failures={len(failed)}", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
