#!/usr/bin/env python3
"""Immutable game artifacts and compare-and-swap catalog activation; no backend actions."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

GAME_MANIFEST = 'game-manifest.json'
KNOWN_GAMES = {'dino', 'rift', 'starfield'}


def static_tool():
    path = Path(__file__).resolve().parents[2] / 'deploy/scripts/static-release.py'
    # A transferred installer may have both tools side by side.
    if not path.is_file():
        path = Path(__file__).with_name('static-release.py')
    spec = importlib.util.spec_from_file_location('wao_static_release', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def identity(game, version):
    if game not in KNOWN_GAMES or not re.fullmatch(r'[a-f0-9]{40}', version):
        raise ValueError('Unknown game or invalid Git SHA')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')


def pack(source, output, game, version):
    identity(game, version)
    source = Path(source).resolve()
    module = static_tool()
    files = {}
    for path in sorted(source.rglob('*')):
        if path.is_symlink():
            raise ValueError('Symlink in game artifact')
        if not path.is_file() or path.name == GAME_MANIFEST:
            continue
        name = module.safe_name(path.relative_to(source).as_posix())
        data = path.read_bytes()
        files[name] = {'sha256': digest(data), 'size': len(data)}
    if 'index.html' not in files or not any(n.startswith('assets/') for n in files):
        raise ValueError('Incomplete game build')
    manifest = {'formatVersion': 1, 'gameId': game, 'gitSha': version, 'hostProtocol': 1,
                'readSaveRules': [1], 'writeSaveRules': 1, 'files': files,
                'artifactHash': digest(encode(files))}
    (source / GAME_MANIFEST).write_bytes(encode(manifest))
    return module.pack(source, output, version)


def verify(root, game, version):
    identity(game, version)
    directory = Path(root) / game / version
    if directory.is_symlink() or (Path(root) / game).is_symlink():
        raise ValueError('Symlink in artifact destination')
    data = (directory / GAME_MANIFEST).read_bytes()
    manifest = json.loads(data)
    expected_keys = {'formatVersion', 'gameId', 'gitSha', 'hostProtocol', 'readSaveRules', 'writeSaveRules', 'files', 'artifactHash'}
    if set(manifest) != expected_keys or manifest['formatVersion'] != 1 or manifest['gameId'] != game or manifest['gitSha'] != version:
        raise ValueError('Game manifest identity mismatch')
    if manifest['hostProtocol'] != 1 or manifest['readSaveRules'] != [1] or manifest['writeSaveRules'] != 1:
        raise ValueError('Incompatible host or save rules')
    files = manifest['files']
    if manifest['artifactHash'] != digest(encode(files)):
        raise ValueError('Artifact inventory hash mismatch')
    release = json.loads((directory / 'release-manifest.json').read_bytes())
    if release.get('release') != version or set(release.get('files', {})) != set(files) | {GAME_MANIFEST}:
        raise ValueError('Artifact inventory mismatch')
    actual = set()
    for entry in directory.rglob('*'):
        if entry.is_symlink():
            raise ValueError('Symlink in installed artifact')
        if entry.is_file():
            actual.add(entry.relative_to(directory).as_posix())
    if actual != set(release['files']) | {'release-manifest.json'}:
        raise ValueError('Untracked file in installed artifact')
    module = static_tool()
    for name, expected in release['files'].items():
        path = directory / module.safe_name(name)
        if path.is_symlink():
            raise ValueError('Symlink in installed asset')
        content = path.read_bytes()
        if expected != {'sha256': digest(content), 'size': len(content)}:
            raise ValueError('Installed asset checksum mismatch')
        if name != GAME_MANIFEST and files[name] != expected:
            raise ValueError('Game and release manifests disagree')
    return {'version': version, 'manifestSha256': digest(data), 'hostProtocol': 1, 'rulesVersion': 1}


def install(archive, root, game, version):
    identity(game, version)
    root = Path(root).resolve()
    target = root / game
    if target.is_symlink():
        raise ValueError('Refusing a symlink game directory')
    root.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix='.game-incoming-', dir=root))
    try:
        static_tool().install(archive, stage / game, version)
        pointer = verify(stage, game, version)
        if (target / version).exists():
            if verify(root, game, version) != pointer:
                raise ValueError('Refusing to overwrite an immutable game version')
            return 'already-installed'
        target.mkdir(exist_ok=True)
        (stage / game / version).rename(target / version)
        return 'installed'
    finally:
        # Only remove this invocation's verified temporary directory under root.
        stage.resolve().relative_to(root)
        shutil.rmtree(stage)


def read_catalog(root):
    path = Path(root) / 'catalog.json'
    if not path.exists():
        return {'protocol': 1, 'revision': 0, 'previousRevision': None, 'games': {}}
    catalog = json.loads(path.read_bytes())
    if (catalog.get('protocol') != 1 or type(catalog.get('revision')) is not int
            or catalog['revision'] < 0 or not isinstance(catalog.get('games'), dict)):
        raise ValueError('Invalid existing catalog; refusing overwrite')
    for game, item in catalog['games'].items():
        # Preserve future games as opaque valid pointers; this tool changes only its target.
        if not re.fullmatch(r'[a-z][a-z0-9-]{0,31}', game) or not isinstance(item, dict):
            raise ValueError('Invalid catalog game')
        if not re.fullmatch(r'[a-f0-9]{40}', item.get('version', '')):
            raise ValueError('Invalid catalog version')
    return catalog


@contextmanager
def catalog_lock(root):
    root.mkdir(parents=True, exist_ok=True)
    path = root / '.catalog-lock'
    try:
        path.mkdir()
    except FileExistsError as exc:
        raise ValueError('Catalog is locked; inspect existing release owner before retrying') from exc
    try:
        yield
    finally:
        path.rmdir()


def atomic_write(path, data):
    handle, temporary = tempfile.mkstemp(prefix='.catalog-', dir=path.parent)
    try:
        with os.fdopen(handle, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def activate(root, game, version, expected_revision):
    identity(game, version)
    root = Path(root).resolve()
    with catalog_lock(root):
        pointer = verify(root, game, version)
        current = read_catalog(root)
        if current['revision'] != expected_revision:
            raise ValueError('Catalog revision conflict; no pointer was changed')
        if current['games'].get(game) == pointer:
            return current
        history = root / '.catalog-history'
        history.mkdir(exist_ok=True)
        old = history / f"{current['revision']:012d}.json"
        if old.exists() and old.read_bytes() != encode(current):
            raise ValueError('Catalog history mismatch')
        if not old.exists():
            atomic_write(old, encode(current))
        result = {**current, 'revision': current['revision'] + 1, 'previousRevision': current['revision'],
                  'updatedAt': datetime.now(timezone.utc).isoformat(), 'games': {**current['games'], game: pointer}}
        atomic_write(root / 'catalog.json', encode(result))
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    for action in ('pack', 'install'):
        command = commands.add_parser(action)
        command.add_argument('source'); command.add_argument('destination')
        command.add_argument('game'); command.add_argument('version')
    for action in ('verify', 'activate'):
        command = commands.add_parser(action)
        command.add_argument('root'); command.add_argument('game'); command.add_argument('version')
        if action == 'activate':
            command.add_argument('--expected-revision', type=int, required=True)
    args = parser.parse_args()
    if args.action == 'pack':
        result = pack(args.source, args.destination, args.game, args.version)
    elif args.action == 'install':
        result = install(args.source, args.destination, args.game, args.version)
    elif args.action == 'verify':
        result = verify(args.root, args.game, args.version)
    else:
        result = activate(args.root, args.game, args.version, args.expected_revision)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
