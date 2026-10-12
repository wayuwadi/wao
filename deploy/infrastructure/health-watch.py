#!/usr/bin/env python3
"""Independent cloud readiness checks with local records only; no notifications."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

TARGETS = {
    'main': 'https://wao.wayuwa.cn/api/edge/ready',
}


PLANNED_DRAIN_DIR = Path('/run/wao-nginx-upstreams')
# A recent marker identifies an explicitly planned application replacement.
DEPLOY_WINDOW_SECONDS = 10 * 60


def probe(url):
    try:
        command = ['curl', '--silent', '--fail', '--max-time', '4', '--connect-timeout', '2',
                   '--max-filesize', '4096', '--proto', '=https',
                   '-H', 'Origin: https://wao.wayuwa.cn']
        response = subprocess.run(command + [url], capture_output=True, text=True, timeout=5, check=True)
        value = json.loads(response.stdout)
        return (isinstance(value, dict) and value.get('code') == 0
                and isinstance(value.get('data'), dict) and value['data'].get('protocol') == 1)
    except (OSError, ValueError, subprocess.SubprocessError):
        return False


def planned_drains(now, runtime_dir=PLANNED_DRAIN_DIR):
    """Ignore all probes only during a recent, explicit single-node release."""
    try:
        age = now - (runtime_dir / 'deploying').stat().st_mtime
        if 0 <= age <= DEPLOY_WINDOW_SECONDS:
            return set(TARGETS)
    except OSError:
        pass
    return set()


def update(previous, results, now, planned=()):
    """Record real readiness without sending failure or recovery messages."""
    failures = {name: (previous.get('failures', {}).get(name, 0) + 1 if not good and name not in planned else 0)
                for name, good in results.items()}
    problems = sorted(name for name, count in failures.items() if count >= 2)
    successes = previous.get('successes', 0) + 1 if all(results.values()) else 0
    retained = {key: value for key, value in previous.items() if key not in ('notified', 'sent')}
    state = {**retained, 'checked': now, 'failures': failures, 'healthy': results,
             'successes': successes, 'notificationsEnabled': False}
    return state, problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, default=Path('/var/lib/wao-health-watch/state.json'))
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    targets = TARGETS
    results = {name: probe(url) for name, url in targets.items()}
    print(json.dumps(results))
    if args.check_only:
        return 0 if all(results.values()) else 1
    try:
        previous = json.loads(args.state.read_text())
    except (OSError, ValueError):
        previous = {}
    now = int(time.time())
    state, problems = update(previous, results, now, planned_drains(now))
    args.state.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.state.with_suffix('.tmp')
    temporary.write_text(json.dumps(state))
    os.replace(temporary, args.state)
    return 1 if problems else 0


if __name__ == '__main__':
    raise SystemExit(main())
