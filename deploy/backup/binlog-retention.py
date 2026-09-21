#!/usr/bin/env python3
"""Keep every log needed by any retained format-3 full snapshot; never purge source MySQL logs."""
import argparse
import json
from pathlib import Path
import re
import shutil


def expired_logs(root):
    if not root.is_absolute() or root.is_symlink() or root.resolve() == Path(root.anchor):
        raise ValueError('Expected a dedicated absolute backup root')
    floors = {}
    for snapshot in root.iterdir():
        if snapshot.is_symlink() or not re.fullmatch(r'\d{8}T\d{6}Z', snapshot.name):
            continue
        if not (snapshot / 'COMPLETE').is_file() or not (snapshot / 'recovery-point.json').is_file():
            continue
        point = json.loads((snapshot / 'recovery-point.json').read_text())
        name = re.fullmatch(r'([A-Za-z0-9_-]+)\.(\d+)', point['file'])
        if not name or not re.fullmatch(r'[0-9a-f-]{36}', point['source_uuid']):
            raise ValueError('Invalid retained recovery point; refuse pruning')
        key = point['source_uuid'], name[1], len(name[2])
        floors[key] = min(floors.get(key, int(name[2])), int(name[2]))
    expired = []
    for (uuid, prefix, width), floor in floors.items():
        directory = root / 'binlogs' / uuid
        if not directory.is_dir() or directory.is_symlink():
            continue
        for archive in directory.iterdir():
            match = re.fullmatch(re.escape(prefix) + r'\.(\d{' + str(width) + r'})', archive.name)
            if archive.is_symlink() or not archive.is_dir() or not match or int(match[1]) >= floor:
                continue
            if (archive / 'COMPLETE').is_file() and (archive / 'COMPLETE').read_text().strip() == archive.name:
                expired.append(archive)
    return expired


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    for path in expired_logs(args.root):
        print(('Prune ' if args.apply else 'Would prune ') + path.name)
        if args.apply:
            shutil.rmtree(path)
