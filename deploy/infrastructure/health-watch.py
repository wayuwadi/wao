#!/usr/bin/env python3
"""Independent cloud readiness checks; no sessions, arbitrary targets or promotion."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import urllib.parse

TARGETS = {
    'main': 'https://wao.wayuwa.cn/api/edge/ready',
    'linux2': 'https://linux2.wayuwa.cn:9443/api/edge/ready',
}


PLANNED_DRAIN_DIR = Path('/run/wao-nginx-upstreams')
PLANNED_DRAIN_MAX_SECONDS = 2 * 3600
# Jenkins touches this marker when the Linux2 application is replaced in place.
DEPLOY_WINDOW_SECONDS = 10 * 60
# The timer runs every minute. A short clean interval must not close a
# flapping incident and cause a new Feishu alert for the same failure.
RECOVERY_SUCCESS_CHECKS = 10


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def probe(url):
    try:
        host = urllib.parse.urlsplit(url).hostname
        command = ['curl', '--silent', '--fail', '--max-time', '4', '--connect-timeout', '2',
                   '--max-filesize', '4096', '--proto', '=https',
                   '-H', 'Origin: https://wao.wayuwa.cn']
        if host == 'linux2.wayuwa.cn':
            answer = subprocess.run(['dig', '+time=2', '+tries=1', '+short', 'A', host, '@223.5.5.5'],
                                    capture_output=True, text=True, timeout=3, check=True)
            addresses = []
            for line in answer.stdout.splitlines():
                try:
                    address = ipaddress.ip_address(line.strip())
                    if address.version == 4 and address.is_global:
                        addresses.append(str(address))
                except ValueError:
                    continue
            if not addresses:
                return False
            command += ['--resolve', f'{host}:9443:{addresses[0]}']
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
    """Remember notified targets for the entire incident, including flapping."""
    failures = {name: (previous.get('failures', {}).get(name, 0) + 1 if not good and name not in planned else 0)
                for name, good in results.items()}
    problems = sorted(name for name, count in failures.items() if count >= 2)
    old = previous.get('notified', [])
    successes = previous.get('successes', 0) + 1 if all(results.values()) else 0
    notification = None
    if problems and (set(problems) - set(old) or now - previous.get('sent', 0) >= 4 * 3600):
        notification = '[WAO 基础设施告警] 就绪检查连续失败：' + ', '.join(problems) + '。请检查入口、应用与权威数据库；不会自动提升数据库。'
        problems = sorted(set(problems) | set(old))
    elif not problems and old and successes >= RECOVERY_SUCCESS_CHECKS:
        notification = '[WAO 基础设施恢复] 所有在役入口的应用与权威数据库就绪检查均已恢复。'
    state = {**previous, 'checked': now, 'failures': failures, 'healthy': results, 'successes': successes}
    return state, problems, notification


def notify(message):
    webhook = os.environ.get('FEISHU_COMMENT_WEBHOOK_URL', '')
    if not webhook.startswith('https://open.feishu.cn/open-apis/bot/v2/hook/'):
        raise ValueError('Notification configuration unavailable')
    payload = json.dumps({'msg_type': 'text', 'content': {'text': message}}).encode()
    request = urllib.request.Request(webhook, data=payload, headers={'Content-Type': 'application/json'})
    with urllib.request.build_opener(NoRedirect).open(request, timeout=10) as response:
        result = json.loads(response.read(4096))
    if result.get('code', result.get('StatusCode', -1)) != 0:
        raise ValueError('Notification was not accepted')


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
    previous['notified'] = [name for name in previous.get('notified', []) if name in targets]
    now = int(time.time())
    state, problems, message = update(previous, results, now, planned_drains(now))
    failed_delivery = False
    if message:
        try:
            notify(message)
            state.update(notified=problems, sent=state['checked'])
        except Exception:
            print('Notification delivery failed; inspect private configuration locally.')
            failed_delivery = True
    args.state.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.state.with_suffix('.tmp')
    temporary.write_text(json.dumps(state))
    os.replace(temporary, args.state)
    return 1 if problems or failed_delivery else 0


if __name__ == '__main__':
    raise SystemExit(main())
