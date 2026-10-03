#!/usr/bin/env python3
"""Plan only installed games and actual shared consumers. No backend scope here."""
import argparse
import json
import subprocess

GAMES = ('dino', 'rift', 'starfield', 'beasts', 'camp')


def plan(paths, full=False, package_before=None, package_after=None):
    build, test = set(), set()
    web = full
    reasons = []
    if full:
        build.update(GAMES); test.update(GAMES)
    for path in paths:
        if path.startswith(('docs/', 'wao-game/demos/')) or path.endswith('.md'):
            continue
        if path.startswith('wao-web/'):
            web = True
        elif path.startswith('wao-game/host/'):
            web = True; test.update(GAMES)
        elif path.startswith('wao-game/games/'):
            game = path.split('/')[2]
            if game not in GAMES:
                raise ValueError(f'Unregistered game: {game}; register its build before delivery')
            test.add(game)
            if not path.endswith('.test.ts'):
                build.add(game)
            # The host validates this schema; importing it does not load rendering/runtime.
            if path.endswith('/save.ts') or path in ('wao-game/games/rift/catalog.ts', 'wao-game/games/starfield/catalog.ts', 'wao-game/games/starfield/model.ts', 'wao-game/games/beasts/catalog.ts', 'wao-game/games/beasts/model.ts', 'wao-game/games/camp/catalog.ts', 'wao-game/games/camp/model.ts'):
                web = True
        elif path.startswith('wao-game/protocol/'):
            web = True
            game = path.rsplit('/', 1)[-1].removesuffix('.ts')
            if game in GAMES:
                build.add(game); test.add(game)
            elif game == 'host':
                test.update(GAMES)
            else:
                build.update(GAMES); test.update(GAMES)
        elif path == 'wao-game/package.json':
            if package_before is not None and package_after is not None and {k:v for k,v in package_before.items() if k != 'scripts'} == {k:v for k,v in package_after.items() if k != 'scripts'}:
                before, after = package_before.get('scripts', {}), package_after.get('scripts', {})
                for script in set(before) | set(after):
                    if before.get(script) == after.get(script): continue
                    target = script.split(':')[-1]
                    if target not in GAMES:
                        web = True; build.update(GAMES); test.update(GAMES)
                    else:
                        test.add(target)
                        if script.startswith(('build:', 'dev:')): build.add(target)
            else:
                web = True; build.update(GAMES); test.update(GAMES)
        elif path == 'wao-game/package-lock.json':
            web = True; build.update(GAMES); test.update(GAMES)
        elif path.startswith('wao-game/tools/'):
            # Packaging/routing changes do not change game source bytes.
            # All artifact tests run independently; a host release applies routing.
            web = True; test.update(GAMES)
        elif path == 'wao-game/.gitignore':
            continue
        elif path.startswith('wao-game/'):
            raise ValueError(f'Unregistered shared game input: {path}')
        elif path.startswith(('deploy/', 'Jenkinsfile')):
            web = True
        else:
            web = True
        reasons.append(path)
    return {'formatVersion': 1, 'buildWeb': web, 'buildGames': sorted(build), 'testGames': sorted(test), 'inputs': reasons}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base'); parser.add_argument('head')
    parser.add_argument('--field', choices=['buildWeb', 'buildGames', 'testGames'])
    parser.add_argument('--full-web', action='store_true')
    parser.add_argument('--release-batch', action='store_true')
    args = parser.parse_args()
    try:
        paths = subprocess.check_output(['git', 'diff', '--name-only', args.base, args.head], text=True).splitlines()
        before = after = None
        if 'wao-game/package.json' in paths:
            try:
                before = json.loads(subprocess.check_output(['git', 'show', args.base + ':wao-game/package.json'], text=True))
                after = json.loads(subprocess.check_output(['git', 'show', args.head + ':wao-game/package.json'], text=True))
            except (subprocess.CalledProcessError, json.JSONDecodeError): pass
        result = plan(paths, package_before=before, package_after=after)
    except subprocess.CalledProcessError:
        result = plan([], full=True)
    if args.full_web:
        result['buildWeb'] = True
    if args.release_batch and not result['buildGames']:
        # Preserve ADR-311 for docs/tests/web batches. An actual game-only
        # artifact update keeps the web SHA/history paired with its old bytes.
        result['buildWeb'] = True
    value = result.get(args.field) if args.field else result
    print(' '.join(value) if isinstance(value, list) else str(value).lower() if isinstance(value, bool) else json.dumps(value))


if __name__ == '__main__':
    main()
