#!/usr/bin/env python3
"""Keep each source PNG in an immutable encrypted WebDAV archive outside rolling backups.

Dry-run is the default. On --apply, read the authoritative MySQL row or its
unoptimized S3 object, encrypt it with the existing recovery passphrase, upload
it, download it and verify SHA-256 before marking original_archive_key. Archive
paths are content-addressed and never deleted by this tool.
"""

from __future__ import annotations

import argparse
from hashlib import sha256
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from migrate_assets import database, object_client, required

try:
    from webdav_client import build_client
except ModuleNotFoundError:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backup"))
    from webdav_client import build_client

SHA = re.compile(r"[0-9a-f]{64}\Z")


def archive_key(remote_root: str, content_hash: str) -> str:
    if not SHA.fullmatch(content_hash):
        raise ValueError("Invalid original PNG checksum")
    return f"{remote_root.strip('/')}/originals/png/{content_hash[:2]}/{content_hash}.png.gpg"


def crypt(data: bytes, passphrase: Path, decrypt: bool = False) -> bytes:
    command = ["gpg", "--no-options", "--batch", "--yes", "--pinentry-mode", "loopback",
               "--passphrase-file", str(passphrase)]
    command += (["--decrypt", "--output", "-"] if decrypt else
                ["--symmetric", "--cipher-algo", "AES256", "--compress-algo", "none",
                 "--output", "-"])
    result = subprocess.run(command, input=data, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError("Original PNG encryption or decryption failed")
    return result.stdout


def original_bytes(row: dict, s3, bucket: str) -> bytes:
    if row["content"] is not None:
        return bytes(row["content"])
    if row["object_key"] and (row["object_content_type"] or row["content_type"]) == "image/png":
        return s3.get_object(Bucket=bucket, Key=row["object_key"])["Body"].read()
    raise RuntimeError("Original PNG bytes are no longer available online")


def download_original(client, key: str, passphrase: Path) -> bytes:
    with tempfile.TemporaryDirectory(prefix="wao-original-verify-") as directory:
        path = Path(directory) / "source.png.gpg"
        client.download(key, path)
        return crypt(path.read_bytes(), passphrase, decrypt=True)


def verify_remote(client, key: str, expected: bytes, passphrase: Path) -> None:
    recovered = download_original(client, key, passphrase)
    if len(recovered) != len(expected) or sha256(recovered).digest() != sha256(expected).digest():
        raise RuntimeError("Encrypted original archive differs from source PNG")


def archive_one(db, client, s3, bucket: str, passphrase: Path, remote_root: str,
                asset_id: str) -> str:
    with db.cursor() as cursor:
        cursor.execute("SELECT id, content_type, byte_size, content_hash, content, object_key, "
                       "object_content_type, original_archive_key FROM image_blob WHERE id=%s",
                       (asset_id,))
        row = cursor.fetchone()
    if not row or row["content_type"] != "image/png" or row["original_archive_key"]:
        return "skipped"
    original = original_bytes(row, s3, bucket)
    content_hash = sha256(original).hexdigest()
    if len(original) != row["byte_size"] or (row["content_hash"] and row["content_hash"] != content_hash):
        raise RuntimeError("Source PNG differs from MySQL metadata")
    key = archive_key(remote_root, content_hash)
    if client.stat_size(key, missing_ok=True) is None:
        encrypted = crypt(original, passphrase)
        with tempfile.TemporaryDirectory(prefix="wao-original-upload-") as directory:
            path = Path(directory) / "source.png.gpg"
            path.write_bytes(encrypted)
            client.ensure_dir(key.rsplit("/", 1)[0])
            client.upload(path, key)
    verify_remote(client, key, original, passphrase)
    with db.cursor() as cursor:
        cursor.execute("UPDATE image_blob SET original_archive_key=%s, content_hash=%s "
                       "WHERE id=%s AND original_archive_key IS NULL AND byte_size=%s "
                       "AND content_hash <=> %s AND object_key <=> %s "
                       "AND (content IS NULL OR SHA2(content,256)=%s)",
                       (key, content_hash, asset_id, len(original), row["content_hash"],
                        row["object_key"], content_hash))
        return "archived" if cursor.rowcount == 1 else "concurrent-change"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="upload and mark verified originals")
    parser.add_argument("--limit", type=int, default=100, help="maximum images per run; 1 to 500")
    args = parser.parse_args()
    if not 1 <= args.limit <= 500:
        parser.error("--limit must be between 1 and 500")
    db = database()
    try:
        with db.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) AS pending, COALESCE(SUM(byte_size),0) AS bytes "
                           "FROM image_blob WHERE content_type='image/png' AND original_archive_key IS NULL")
            totals = cursor.fetchone()
            cursor.execute("SELECT id FROM image_blob WHERE content_type='image/png' "
                           "AND original_archive_key IS NULL ORDER BY id LIMIT %s", (args.limit,))
            ids = [row["id"] for row in cursor.fetchall()]
        print(f"original_png_archive: pending={totals['pending']} bytes={totals['bytes']}")
        if not args.apply:
            return 0
        with db.cursor() as cursor:
            cursor.execute("SELECT @@read_only AS read_only, @@super_read_only AS super_read_only")
            role = cursor.fetchone()
        if role["read_only"] or role["super_read_only"]:
            raise RuntimeError("Refusing to mark archives on a read-only MySQL replica")
        config_path = Path(os.getenv("WAO_ORIGINAL_WEBDAV_CONFIG", "/etc/wao-backup/webdav.env"))
        passphrase = Path(os.getenv("WAO_ORIGINAL_PASSPHRASE_FILE",
                                 "/etc/wao-backup/encryption-passphrase"))
        if not passphrase.is_file():
            raise RuntimeError("Recovery passphrase file is missing")
        client, config = build_client(config_path, allow_http=False)
        remote_root = config.get("WAO_BACKUP_REMOTE_ROOT", "WAO-backups")
        s3 = object_client()
        bucket = required("WAO_STORAGE_BUCKET")
        counts = {"archived": 0, "skipped": 0, "concurrent-change": 0}
        failed = 0
        for asset_id in ids:
            try:
                counts[archive_one(db, client, s3, bucket, passphrase, remote_root, asset_id)] += 1
            except Exception as exc:
                failed += 1
                print(f"FAIL {asset_id}: {type(exc).__name__}: {str(exc)[:120]}", file=sys.stderr)
        print(f"original_png_archive: {counts}, failures={failed}")
        return 1 if failed else 0
    finally:
        db.close()


if __name__ == "__main__":
    raise SystemExit(main())
