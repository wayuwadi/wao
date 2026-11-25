#!/usr/bin/env python3
"""读取本机 Antigravity CLI (/usage) 额度并安全上报到 WAO 服务端。"""

from __future__ import annotations

import argparse
import json
import math
import os
import platform
import re
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from typing import Any

MAX_EPOCH_SECONDS = 253_402_300_799
SOURCE_RE = re.compile(r"^[a-zA-Z0-9_-]{1,40}$")


def parse_epoch_seconds(value: Any) -> int | None:
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(text)
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    epoch = int(dt.timestamp())
    return epoch if 0 < epoch <= MAX_EPOCH_SECONDS else None


def parse_agy_usage(raw_json: str, source: str, account_label: str) -> dict[str, Any]:
    if not SOURCE_RE.match(source):
        raise ValueError("source 须为 1 到 40 位字母、数字、下划线或短横线")
    payload = json.loads(raw_json)
    if not isinstance(payload, dict) or payload.get("status") != "SUCCESS":
        raise ValueError("agy /usage 返回状态不是 SUCCESS")
    command = payload.get("command") or {}
    if command.get("name") != "usage":
        raise ValueError("agy 返回内容不是 usage 数据")
    raw_groups = ((command.get("data") or {}).get("groups")) or []
    groups: list[dict[str, Any]] = []
    for raw_group in raw_groups[:10]:
        if not isinstance(raw_group, dict):
            continue
        group_name = str(raw_group.get("name") or "").strip()[:100]
        if not group_name:
            continue
        buckets: list[dict[str, Any]] = []
        for raw_bucket in (raw_group.get("buckets") or [])[:6]:
            if not isinstance(raw_bucket, dict):
                continue
            fraction = raw_bucket.get("remaining_fraction")
            if not isinstance(fraction, (int, float)) or isinstance(fraction, bool) or not math.isfinite(fraction):
                continue
            bucket_id = str(raw_bucket.get("id") or "").strip()[:64] or None
            bucket_name = str(raw_bucket.get("name") or "").strip()[:100] or None
            window = str(raw_bucket.get("window") or "").strip()[:32] or None
            if not bucket_id and not window:
                continue
            remaining_percent = max(0, min(100, round(float(fraction) * 100.0)))
            buckets.append({
                "id": bucket_id,
                "name": bucket_name,
                "window": window,
                "remainingPercent": remaining_percent,
                "resetsAt": parse_epoch_seconds(raw_bucket.get("reset_time")),
            })
        if buckets:
            groups.append({"name": group_name, "buckets": buckets})
    if not groups:
        raise ValueError("未解析到任何有效 Agy 额度窗口")
    label = (account_label or "").strip()[:120] or "Agy"
    return {
        "source": source,
        "accountLabel": label,
        "groups": groups,
    }


def run_agy_usage(command: str, timeout_seconds: int) -> str:
    completed = subprocess.run(
        [command, "--print", "/usage", "--output-format", "json"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=timeout_seconds,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"执行 {command} 失败（退出码 {completed.returncode}）")
    return completed.stdout


def post_report(server: str, token: str, report: dict[str, Any], timeout_seconds: int) -> dict[str, Any]:
    url = server.rstrip("/") + "/api/agy-quota/report"
    body = json.dumps(report, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Authorization": f"Bearer {token}",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        raise RuntimeError(f"上报失败：HTTP {err.code}") from None
    except urllib.error.URLError as err:
        raise RuntimeError(f"连接 WAO 服务失败：{err.reason}") from None


def default_source() -> str:
    raw = re.sub(r"[^a-zA-Z0-9_-]+", "-", platform.node() or "local").strip("-")
    return (raw or "local")[:40]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="同步本机 Agy 额度到 WAO")
    parser.add_argument("--server", default=os.getenv("WAO_SERVER_URL", "https://wao.wayuwa.cn"))
    parser.add_argument("--source", default=os.getenv("WAO_AGY_QUOTA_SOURCE", default_source()))
    parser.add_argument("--account-label", default=os.getenv("WAO_AGY_QUOTA_ACCOUNT_LABEL", "Agy"))
    parser.add_argument("--agy-command", default=os.getenv("WAO_AGY_QUOTA_EXECUTABLE", "agy"))
    parser.add_argument("--timeout", type=int, default=25)
    parser.add_argument("--token-stdin", action="store_true", help="从标准输入读取 Bearer 令牌")
    parser.add_argument("--dry-run", action="store_true", help="仅输出解析后的脱敏快照，不发送到服务端")
    args = parser.parse_args(argv)

    raw_json = run_agy_usage(args.agy_command, args.timeout)
    report = parse_agy_usage(raw_json, args.source, args.account_label)
    if args.dry_run:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0

    token = sys.stdin.readline().strip() if args.token_stdin else os.getenv("WAO_API_TOKEN", "").strip()
    if not token:
        print("缺少 WAO 工作区令牌：请设置环境变量 WAO_API_TOKEN 或使用 --token-stdin", file=sys.stderr)
        return 2

    result = post_report(args.server, token, report, args.timeout)
    if result.get("code") != 0:
        print(f"服务端拒绝上报：{result.get('message') or '未知错误'}", file=sys.stderr)
        return 1
    data = result.get("data") or {}
    print(f"已同步 Agy 额度：{data.get('accountLabel')}（来源 {args.source}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
