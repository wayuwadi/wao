#!/usr/bin/env python3
"""Include all immutable S3 objects in WAO's encrypted offsite recovery archive."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import sys

from migrate_assets import database, digest, object_client, required

KEY = re.compile(r"(?:images|files|previews)/[A-Za-z0-9_-]+/[0-9a-f]{64}\Z")


def inventory(db) -> dict[str, tuple[int | None, str]]:
    result = {}
    with db.cursor() as cursor:
        cursor.execute("SELECT object_key, COALESCE(object_byte_size,byte_size) AS object_size, "
                       "COALESCE(object_hash,content_hash) AS object_checksum FROM image_blob "
                       "WHERE object_key IS NOT NULL")
        for row in cursor.fetchall():
            result[row["object_key"]] = (row["object_size"], row["object_checksum"])
        cursor.execute("SELECT object_key, byte_size, content_hash FROM file_blob "
                       "WHERE object_key IS NOT NULL")
        for row in cursor.fetchall():
            result[row["object_key"]] = (row["byte_size"], row["content_hash"])
        cursor.execute("SELECT preview_key FROM image_blob WHERE preview_key IS NOT NULL")
        for row in cursor.fetchall():
            key = row["preview_key"]
            result[key] = (None, key.rsplit("/", 1)[-1])
    for key, (_, content_hash) in result.items():
        if not KEY.fullmatch(key) or not content_hash or key.rsplit("/", 1)[-1] != content_hash:
            raise RuntimeError("Unsafe or inconsistent stored object key")
    return result


def export(target: Path) -> None:
    if target.exists() and any(target.iterdir()):
        raise RuntimeError("Object backup destination must be empty")
    target.mkdir(parents=True, exist_ok=True)
    db = database()
    try:
        wanted = inventory(db)
    finally:
        db.close()
    client = object_client()
    bucket = required("WAO_STORAGE_BUCKET")
    listed = {}
    for page in client.get_paginator("list_objects_v2").paginate(Bucket=bucket):
        for item in page.get("Contents", []):
            listed[item["Key"]] = item["Size"]
    if not wanted.keys() <= listed.keys():
        raise RuntimeError("Referenced objects are missing from bucket listing")
    manifest = {"formatVersion": 1, "bucket": bucket, "objects": {}}
    for key, listed_size in sorted(listed.items()):
        if not KEY.fullmatch(key):
            raise RuntimeError("Unexpected object key in private bucket")
        expected_size, expected_hash = wanted.get(key, (listed_size, key.rsplit("/", 1)[-1]))
        head = client.head_object(Bucket=bucket, Key=key)
        data = client.get_object(Bucket=bucket, Key=key)["Body"].read()
        if (expected_size is not None and len(data) != expected_size) or digest(data) != expected_hash:
            raise RuntimeError("Object differs from MySQL metadata: " + key)
        path = target / key
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        manifest["objects"][key] = {"bytes": len(data), "sha256": expected_hash,
                                     "contentType": head.get("ContentType", "application/octet-stream")}
    (target / "manifest.json").write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n")
    print(f"object-backup: verified {len(listed)} objects, including {len(wanted)} referenced", flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: export_object_backup.py EMPTY_DESTINATION")
    export(Path(sys.argv[1]))
