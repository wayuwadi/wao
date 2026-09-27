#!/usr/bin/env python3
"""Check the recovery coordinates and destructive scope of a WAO-only dump."""
import gzip
import json
from pathlib import Path
import re
import sys


def inspect(path: Path) -> dict:
    with gzip.open(path, 'rb') as dump:
        prefix = dump.read(2 * 1024 * 1024)
    coordinate = re.search(
        rb"(?m)^-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='([A-Za-z0-9_.-]+)', SOURCE_LOG_POS=([0-9]+);",
        prefix,
    )
    source_uuid = re.search(rb'(?m)^-- WAO_SOURCE_UUID=([0-9a-f-]{36})$', prefix)
    checks = {
        'source_uuid': source_uuid is not None,
        'source_coordinate': coordinate is not None,
        'commented_gtid': b'GTID_PURGED' in prefix,
        'wao_database_drop': b'DROP DATABASE IF EXISTS `wao`' in prefix,
        'wao_database_create': b'CREATE DATABASE' in prefix and b'`wao`' in prefix,
    }
    if not all(checks.values()):
        raise ValueError(f'Incomplete authoritative WAO dump header: {checks}')
    return {
        'database': 'wao',
        'source_uuid': source_uuid.group(1).decode('ascii'),
        'source_log_file': coordinate.group(1).decode('ascii'),
        'source_log_position': int(coordinate.group(2)),
        'gtid_comment_present': True,
    }


if __name__ == '__main__':
    try:
        print(json.dumps(inspect(Path(sys.argv[1]))))
    except (IndexError, OSError, ValueError) as exc:
        raise SystemExit(str(exc))
