#!/usr/bin/env python3
"""Verify or restore a WAO object archive into a private S3 bucket; never delete."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from export_object_backup import KEY
from migrate_assets import digest, ensure_object, object_client, required


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="extracted recovery/objects directory")
    parser.add_argument("--apply", action="store_true", help="upload missing objects")
    args = parser.parse_args()
    manifest = json.loads((args.source / "manifest.json").read_text())
    if manifest.get("formatVersion") != 1 or not isinstance(manifest.get("objects"), dict):
        raise RuntimeError("Invalid object backup manifest")
    bucket = required("WAO_STORAGE_BUCKET")
    if manifest.get("bucket") != bucket:
        raise RuntimeError("Backup bucket differs from configured restore bucket")
    client = object_client() if args.apply else None
    total = 0
    for key, meta in sorted(manifest["objects"].items()):
        if not KEY.fullmatch(key) or key.rsplit("/", 1)[-1] != meta.get("sha256"):
            raise RuntimeError("Unsafe or inconsistent object key")
        path = args.source / key
        data = path.read_bytes()
        if len(data) != meta.get("bytes") or digest(data) != meta["sha256"]:
            raise RuntimeError("Backup object differs from manifest: " + key)
        if client:
            content_type = meta.get("contentType", "application/octet-stream")
            ensure_object(client, bucket, key, data, content_type, meta["sha256"])
        total += 1
    print(f"{'restored-and-verified' if args.apply else 'backup-verified'}={total}")


if __name__ == "__main__":
    main()
