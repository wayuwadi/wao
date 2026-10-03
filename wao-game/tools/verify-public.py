#!/usr/bin/env python3
"""Verify live catalog, immutable manifest and every game resource by content hash."""
import argparse
import hashlib
import json
import re
from urllib.request import Request, urlopen


def fetch(url, limit=32 * 1024 * 1024):
    with urlopen(Request(url, headers={'Cache-Control': 'no-cache'}), timeout=15) as response:
        data = response.read(limit + 1)
        if len(data) > limit: raise ValueError('Public resource exceeds size limit')
        return data


def verify(origin, games, version=None):
    catalog = json.loads(fetch(origin + '/play-assets/catalog.json', 1024 * 1024))
    if catalog.get('protocol') != 1 or not isinstance(catalog.get('games'), dict):
        raise ValueError('Public game catalog not ready')
    evidence = []
    for game in games or sorted(catalog['games']):
        if not re.fullmatch(r'[a-z][a-z0-9-]{0,31}', game): raise ValueError('Invalid game ID')
        pointer = catalog['games'][game]
        sha = pointer['version']
        if not re.fullmatch(r'[a-f0-9]{40}', sha) or (version and sha != version):
            raise ValueError('Public game version mismatch')
        base = f'{origin}/play-assets/{game}/{sha}/'
        raw = fetch(base + 'game-manifest.json', 1024 * 1024)
        if hashlib.sha256(raw).hexdigest() != pointer['manifestSha256']:
            raise ValueError('Public manifest checksum mismatch')
        manifest = json.loads(raw)
        if manifest['gitSha'] != sha or manifest['gameId'] != game or manifest['hostProtocol'] != 1 or manifest['readSaveRules'] != [1]:
            raise ValueError('Public game compatibility mismatch')
        files = manifest['files']
        if 'index.html' not in files: raise ValueError('Missing game entry')
        for path, expected in files.items():
            if not re.fullmatch(r'(index\.html|assets/[A-Za-z0-9_.-]+)', path): raise ValueError('Invalid public resource path')
            data = fetch(base + path)
            if len(data) != expected['size'] or hashlib.sha256(data).hexdigest() != expected['sha256']:
                raise ValueError('Public game asset mismatch: ' + path)
        evidence.append({'game': game, 'version': sha, 'resources': len(files), 'verified': True})
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', default='https://wao.wayuwa.cn')
    parser.add_argument('--games', nargs='*', default=[])
    parser.add_argument('--version')
    args = parser.parse_args()
    print(json.dumps(verify(args.origin.rstrip('/'), args.games, args.version)))

if __name__ == '__main__': main()
