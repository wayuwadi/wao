#!/usr/bin/env python3
"""Validate and decrypt one continuous immutable log chain into private restore staging."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess


def chain_names(start, target):
    if start['source_uuid'] != target['source_uuid']:
        raise ValueError('Restore target belongs to a different source server')
    first = re.fullmatch(r'([A-Za-z0-9_-]+)\.(\d+)', start['file'])
    last = re.fullmatch(r'([A-Za-z0-9_-]+)\.(\d+)', target['file'])
    if not first or not last or first[1] != last[1] or len(first[2]) != len(last[2]):
        raise ValueError('Invalid log sequence')
    a, b = int(first[2]), int(last[2])
    if b < a or b - a > 100000 or int(start['position']) < 4 or int(target['position']) < 4:
        raise ValueError('Invalid restore interval')
    if a == b and int(target['position']) < int(start['position']):
        raise ValueError('Target precedes the snapshot')
    return [f'{first[1]}.{n:0{len(first[2])}d}' for n in range(a, b + 1)]


def prepare(start, target, archive_root, output, passphrase):
    names = chain_names(start, target)
    if not re.fullmatch(r'[0-9a-f-]{36}', start['source_uuid']):
        raise ValueError('Invalid source UUID')
    output.mkdir(mode=0o700)
    for name in names:
        archive = archive_root / start['source_uuid'] / name
        if (archive / 'COMPLETE').read_text().strip() != name:
            raise ValueError('Incomplete archived log')
        subprocess.run(['sha256sum', '-c', 'SHA256SUMS'], cwd=archive, check=True, stdout=subprocess.DEVNULL)
        metadata = json.loads((archive / 'metadata.json').read_text())
        if metadata['source_uuid'] != start['source_uuid'] or metadata['file'] != name:
            raise ValueError('Archive identity mismatch')
        size = int(metadata['size'])
        if name == names[0] and int(start['position']) > size:
            raise ValueError('Snapshot position exceeds archived file')
        if name == names[-1] and int(target['position']) > size:
            raise ValueError('Target is beyond the verified archive')
        if shutil.disk_usage(output).free - size * 2 < shutil.disk_usage(output).total // 5:
            raise ValueError('Restore would breach the 20 percent disk reserve')
        with (output / name).open('wb') as raw:
            process = subprocess.Popen(['gpg', '--batch', '--quiet', '--pinentry-mode', 'loopback', '--passphrase-file', str(passphrase), '--decrypt', str(archive / 'binlog.gz.gpg')], stdout=subprocess.PIPE)
            try:
                with gzip.GzipFile(fileobj=process.stdout) as decoded:
                    shutil.copyfileobj(decoded, raw)
            finally:
                process.stdout.close()
                code = process.wait()
            if code:
                raise ValueError('Could not decrypt archived log')
        with (output / name).open('rb') as raw:
            digest = hashlib.file_digest(raw, 'sha256').hexdigest()
        if (output / name).stat().st_size != size or digest != metadata['sha256']:
            raise ValueError('Raw binlog integrity check failed')
    return ['--verify-binlog-checksum', '--skip-gtids', '--disable-log-bin',
            '--start-position=' + str(int(start['position'])), '--stop-position=' + str(int(target['position'])),
            *('/tmp/wao-pitr/' + name for name in names)]


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ('point', 'target', 'archives', 'output', 'passphrase'):
        parser.add_argument(key, type=Path)
    args = parser.parse_args()
    options = prepare(json.loads(args.point.read_text()), json.loads(args.target.read_text()), args.archives, args.output, args.passphrase)
    # Paths and numeric positions only, never SQL rows or secret configuration.
    print('\n'.join(options))
