#!/usr/bin/env python3
"""Alert on stale Linux2-only cloud snapshots, binlogs, or remote capacity."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.request

STAMP = re.compile(r'\d{8}T\d{6}Z')
UUID = re.compile(r'[0-9a-f-]{36}')
BINLOG = re.compile(r'[A-Za-z0-9_-]+\.\d+')


def configuration(path):
    result = {}
    for line in path.read_text().splitlines():
        if line and not line.startswith('#'):
            key, value = line.split('=', 1)
            result[key] = value
    for key in ('BACKUP_ROOT', 'CLOUD_ROOT', 'CLOUD_HOST', 'SSH_KEY', 'KNOWN_HOSTS'):
        if not result.get(key):
            raise ValueError(f'Missing backup configuration: {key}')
    if not re.fullmatch(r'[A-Za-z0-9_.@-]+', result['CLOUD_HOST']):
        raise ValueError('Invalid cloud host')
    return result


def check_local(root, now):
    problems = []
    try:
        stamp = (root / 'last-success').read_text().strip()
        if not STAMP.fullmatch(stamp):
            raise ValueError('Invalid snapshot marker')
        date = dt.datetime.strptime(stamp, '%Y%m%dT%H%M%SZ').replace(tzinfo=dt.timezone.utc)
        if not 0 <= (now - date).total_seconds() <= 8 * 86400:
            problems.append('四库全量快照已超过 8 天')
        point = root / stamp
        if ((point / 'COMPLETE').read_text().strip() != stamp
                or not (point / 'recovery.tar.gpg').is_file()
                or not (point / 'SHA256SUMS').is_file()):
            problems.append('本机全量快照不完整')
    except (OSError, ValueError):
        stamp = None
        problems.append('没有可用的四库全量快照')
    try:
        status = json.loads((root / 'binlogs/last-success.json').read_text())
        captured = dt.datetime.fromisoformat(status['captured_utc'].replace('Z', '+00:00'))
        if not 0 <= (now - captured).total_seconds() <= 75 * 60:
            problems.append('binlog 异地归档超过 75 分钟')
        if not UUID.fullmatch(status['source_uuid']) or not BINLOG.fullmatch(status['file']):
            raise ValueError('Invalid binlog marker')
        if not (root / 'binlogs' / status['source_uuid'] / status['file'] / 'COMPLETE').is_file():
            raise ValueError('Incomplete binlog')
    except (OSError, KeyError, TypeError, ValueError):
        problems.append('本机 binlog 归档清单不完整')
    disk = shutil.disk_usage(root)
    if disk.free < max(6 * 1024**3, disk.total // 5):
        problems.append('Linux2 备份空间低于预留门槛')
    return stamp, problems


def check_cloud(config, stamp):
    if not stamp:
        return ['云端恢复点无法核对']
    remote = config['CLOUD_ROOT']
    if not re.fullmatch(r'/[A-Za-z0-9_/-]+', remote):
        return ['云端备份路径无效']
    command = ['ssh', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
               '-o', 'ConnectTimeout=10', '-i', config['SSH_KEY'],
               '-o', 'UserKnownHostsFile=' + config['KNOWN_HOSTS'],
               config['CLOUD_HOST'],
               f"test -f '{remote}/{stamp}/COMPLETE' && "
               f"test -f '{remote}/binlogs/last-success.json' && "
               f"df -Pk '{remote}' | awk 'NR==2 {{print $2,$4}}'"]
    try:
        output = subprocess.run(command, capture_output=True, text=True,
                                timeout=20, check=True).stdout.split()
        total, free = map(int, output)
        if free < max(6 * 1024 * 1024, total // 5):
            return ['云端备份空间低于预留门槛']
        return []
    except (OSError, ValueError, subprocess.SubprocessError):
        return ['云端恢复点或 binlog 清单无法验证']


def notify(problem):
    webhook = os.environ.get('FEISHU_COMMENT_WEBHOOK_URL', '')
    if not webhook.startswith('https://open.feishu.cn/open-apis/bot/v2/hook/'):
        raise ValueError('Backup alarm webhook unavailable')
    payload = json.dumps({'msg_type': 'text', 'content': {
        'text': '[WAO Linux2 异地备份告警] ' + problem}}).encode()
    request = urllib.request.Request(webhook, data=payload,
                                     headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=15) as response:
        result = json.load(response)
    if result.get('code', result.get('StatusCode', -1)) != 0:
        raise ValueError('Backup alarm was rejected')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=Path('/etc/wao-backup/cloud.env'))
    parser.add_argument('--state', type=Path, default=Path('/var/lib/wao-cloud-backup/alarm.json'))
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    config = configuration(args.config)
    stamp, problems = check_local(Path(config['BACKUP_ROOT']), dt.datetime.now(dt.timezone.utc))
    problems += check_cloud(config, stamp)
    if not problems:
        if not args.check_only:
            args.state.unlink(missing_ok=True)
        print('Linux2/cloud full snapshot, binlog chain, and capacity are current.')
        return 0
    problem = '；'.join(problems)
    print(problem)
    if args.check_only:
        return 1
    try:
        previous = json.loads(args.state.read_text())
    except (OSError, ValueError):
        previous = {}
    if previous.get('problem') == problem and time.time() - previous.get('sent', 0) < 4 * 3600:
        return 1
    try:
        notify(problem)
    except Exception:
        raise SystemExit('Cloud backup alarm delivery failed; inspect private configuration locally.') from None
    args.state.parent.mkdir(parents=True, exist_ok=True)
    args.state.write_text(json.dumps({'problem': problem, 'sent': time.time()}))
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
