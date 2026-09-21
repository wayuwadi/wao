#!/usr/bin/env python3
"""Reject archive gaps and avoid re-copying source files older than retained full snapshots."""
import json
from pathlib import Path
import re
import sys


def number(name):
    match = re.fullmatch(r'([A-Za-z0-9_-]+)\.(\d+)', name)
    if not match:
        raise ValueError('Invalid binlog filename')
    return match[1], int(match[2]), len(match[2])


def plan(root, uuid, rows):
    if len(rows) < 2:
        raise ValueError('No closed source log after rotation')
    prefix, _, width = number(rows[0][0])
    numbers = [number(row[0]) for row in rows]
    if any(p != prefix or w != width for p, _, w in numbers) or any(b[1] != a[1]+1 for a,b in zip(numbers, numbers[1:])):
        raise ValueError('Source log list is not continuous')
    floors = []
    for snapshot in root.iterdir():
        if snapshot.is_symlink() or not re.fullmatch(r'\d{8}T\d{6}Z', snapshot.name):
            continue
        point = snapshot / 'recovery-point.json'
        if not (snapshot / 'COMPLETE').is_file() or not point.is_file():
            continue
        value = json.loads(point.read_text())
        if value['source_uuid'] == uuid:
            p,n,w = number(value['file'])
            if (p,w) != (prefix,width):
                raise ValueError('Snapshot uses another binlog prefix')
            floors.append(n)
    floor = min(floors) if floors else numbers[0][1]
    previous_file = root / 'binlogs/last-success.json'
    if previous_file.is_file():
        previous = json.loads(previous_file.read_text())
        if previous['source_uuid'] != uuid:
            raise ValueError('Archive source changed; establish and review a new base before continuing')
        _, last, _ = number(previous['file'])
        if numbers[0][1] > last+1:
            raise ValueError('Source logs expired before archiving; recovery chain is broken')
    # Missing native files must already exist in our independent archive.
    for n in range(floor, numbers[0][1]):
        name = f'{prefix}.{n:0{width}d}'
        if not (root / 'binlogs' / uuid / name / 'COMPLETE').is_file():
            raise ValueError('Retained full snapshot has an archived log gap')
    return [row for row in rows[:-1] if number(row[0])[1] >= floor]


if __name__ == '__main__':
    root, uuid, listing = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
    for row in plan(root, uuid, [line.split() for line in listing.read_text().splitlines()]):
        print('\t'.join(row))
