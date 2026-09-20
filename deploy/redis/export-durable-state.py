#!/usr/bin/env python3
"""Write a sensitive gzip tar to stdout ONLY for the encrypted peer-backup pipe."""
import argparse
import io
import json
import subprocess
import sys
import tarfile


def read(container, *args):
    result = subprocess.run(["docker", "exec", container, *args],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError("Redis state export failed; no archive should be published")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=["master", "slave"])
    args = parser.parse_args()
    if sys.stdout.isatty():
        raise RuntimeError("Refusing to print sensitive archive to terminal")
    redis = f"wao-{args.role}-redis-{args.role}-1"
    sentinel = f"wao-{args.role}-sentinel-{args.role}-1"
    flushed = read(sentinel, "sh", "-ec",
                   'REDISCLI_AUTH="$REDIS_PASS" redis-cli --no-auth-warning -p 26379 SENTINEL FLUSHCONFIG')
    if flushed.strip() != b"OK":
        raise RuntimeError("Sentinel state flush failed")
    files = {
        "redis.conf": read(redis, "cat", "/data/wao-runtime/redis.conf"),
        "sentinel.conf": read(sentinel, "cat", "/data/sentinel.conf"),
        "role.json": read(redis, "sh", "-ec",
                          'REDISCLI_AUTH="$REDIS_PASS" redis-cli --no-auth-warning --json ROLE'),
    }
    json.loads(files["role.json"])
    with tarfile.open(fileobj=sys.stdout.buffer, mode="w|gz") as archive:
        for name, content in files.items():
            item = tarfile.TarInfo(name)
            item.size = len(content)
            item.mode = 0o600
            archive.addfile(item, io.BytesIO(content))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError):
        print("Redis durable state export failed; do not publish the backup", file=sys.stderr)
        raise SystemExit(2)
