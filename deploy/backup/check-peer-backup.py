#!/usr/bin/env python3
"""Independent peer-side freshness alarm; webhook comes only from environment."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import time
import urllib.request


def health(root: Path, now: dt.datetime) -> str | None:
    dates = []
    if root.exists():
        for path in root.iterdir():
            if path.is_symlink() or not re.fullmatch(r'\d{8}T\d{6}Z', path.name):
                continue
            marker = path / 'COMPLETE'
            if not marker.is_file() or marker.read_text().strip() != path.name:
                continue
            if not (path / 'recovery.tar.gpg').is_file() or not (path / 'SHA256SUMS').is_file():
                continue
            try:
                dates.append(dt.datetime.strptime(path.name, '%Y%m%dT%H%M%SZ').replace(tzinfo=dt.timezone.utc))
            except ValueError:
                continue
    if not dates or not 0 <= (now - max(dates)).total_seconds() <= 30 * 3600:
        return '两台 Linux 间的 WAO 备份超过 30 小时未成功，或尚无完整备份。请检查备份服务与节点连接。'
    if shutil.disk_usage(root).free < 12 * 1024**3:
        return 'WAO 备份节点剩余磁盘不足 12 GiB，下一次备份可能无法完成。'
    if (root / 'RESTORE_FAILED').exists():
        return 'WAO 最近一次隔离恢复演练失败。备份文件存在不代表可恢复，请检查 Linux1 恢复服务日志。'
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--state', required=True, type=Path)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    problem = health(args.root, dt.datetime.now(dt.timezone.utc))
    if not problem:
        if not args.check_only:
            args.state.unlink(missing_ok=True)
        print('Peer backup freshness and free space are healthy.')
        return 0
    print(problem)
    if args.check_only:
        return 1
    if args.state.is_file():
        try:
            previous = json.loads(args.state.read_text())
        except (OSError, ValueError):
            previous = {}
        if previous.get('problem') == problem and time.time() - previous.get('sent', 0) < 4 * 3600:
            return 1
    webhook = os.environ.get('FEISHU_COMMENT_WEBHOOK_URL', '')
    if not webhook.startswith('https://open.feishu.cn/open-apis/bot/v2/hook/'):
        raise SystemExit('Backup alarm webhook is missing or has an unexpected host.')
    payload = json.dumps({'msg_type': 'text', 'content': {'text': '[WAO 基础设施备份告警] ' + problem}}).encode()
    request = urllib.request.Request(webhook, data=payload, headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            result = json.load(response)
        if result.get('code', result.get('StatusCode', -1)) != 0:
            raise ValueError('Webhook rejected notification')
    except Exception:
        # Never include request URLs or credentials in a service log.
        raise SystemExit('Backup alarm delivery failed; inspect notification configuration locally.') from None
    args.state.parent.mkdir(parents=True, exist_ok=True)
    args.state.write_text(json.dumps({'problem': problem, 'sent': time.time()}))
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
