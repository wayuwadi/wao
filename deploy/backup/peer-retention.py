#!/usr/bin/env python3
"""Prune only completed snapshots older than seven days; retain at least seven."""
import argparse
import datetime as dt
from pathlib import Path
import re
import shutil


def expired_snapshots(root: Path, now: dt.datetime) -> list[Path]:
    if not root.is_absolute() or root.is_symlink() or root.resolve() == Path(root.anchor):
        raise ValueError('Expected a dedicated absolute backup directory')
    valid = []
    for path in root.iterdir():
        if path.is_symlink() or not path.is_dir() or not re.fullmatch(r'\d{8}T\d{6}Z', path.name):
            continue
        marker = path / 'COMPLETE'
        if marker.is_symlink() or not marker.is_file() or marker.read_text().strip() != path.name:
            continue
        if not (path / 'recovery.tar.gpg').is_file() or not (path / 'SHA256SUMS').is_file():
            continue
        try:
            created = dt.datetime.strptime(path.name, '%Y%m%dT%H%M%SZ').replace(tzinfo=dt.timezone.utc)
        except ValueError:
            continue
        valid.append((created, path))
    valid.sort(reverse=True)
    return [path for created, path in valid[7:] if now - created > dt.timedelta(days=7)]


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    for path in expired_snapshots(args.root, dt.datetime.now(dt.timezone.utc)):
        print(('Prune ' if args.apply else 'Would prune ') + path.name)
        if args.apply:
            shutil.rmtree(path)
