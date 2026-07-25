#!/usr/bin/env python3
"""WAO 本地 AI 执行节点：领取任务，在独立 Git worktree 中调用 Codex CLI。"""

from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path
from typing import Any, Callable


STATE_FILE = Path.home() / ".wao" / "ai-runner.json"
LOG_FILE = Path.home() / ".wao" / "ai-runner.log"
PRINT_LOCK = threading.Lock()
MAX_LOG_BYTES = 2 * 1024 * 1024


def log(message: str) -> None:
    with PRINT_LOCK:
        line = f"{time.strftime('%Y-%m-%d %H:%M:%S')} {message}"
        print(line, flush=True)
        LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
        if LOG_FILE.exists() and LOG_FILE.stat().st_size >= MAX_LOG_BYTES:
            LOG_FILE.replace(LOG_FILE.with_suffix(".log.1"))
        with LOG_FILE.open("a", encoding="utf-8") as output:
            output.write(line + "\n")


class WaoApi:
    def __init__(self, server: str, token: str | None = None) -> None:
        self.server = server.rstrip("/")
        self.token = token

    def post(self, path: str, body: dict[str, Any] | None = None) -> Any:
        data = json.dumps(body or {}, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.token:
            headers["X-WAO-Runner-Token"] = self.token
        request = urllib.request.Request(self.server + path, data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                envelope = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"WAO 返回 HTTP {exc.code}: {detail[:300]}") from exc
        if envelope.get("code") != 0:
            raise RuntimeError(envelope.get("message") or "WAO 请求失败")
        return envelope.get("data")


def load_state() -> dict[str, str]:
    if not STATE_FILE.exists():
        return {}
    return json.loads(STATE_FILE.read_text(encoding="utf-8"))


def save_state(state: dict[str, str]) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    if os.name != "nt":
        STATE_FILE.chmod(0o600)


def pair(api: WaoApi, args: argparse.Namespace) -> dict[str, str]:
    if not args.pair_code:
        raise RuntimeError("本机尚未配对，请在项目设置生成配对码，再传入 --pair-code")
    result = api.post(
        "/api/ai/runner/pair",
        {
            "code": args.pair_code,
            "name": args.name,
            "platform": f"{platform.system()} {platform.release()}",
            "maxConcurrency": args.max_concurrency,
        },
    )
    state = {"server": args.server.rstrip("/"), "runnerId": result["runnerId"], "token": result["token"]}
    save_state(state)
    log(f"配对成功：{args.name}（{state['runnerId']}），令牌仅保存在 {STATE_FILE}")
    return state


def run(command: list[str], cwd: Path | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command, cwd=str(cwd) if cwd else None, text=True, encoding="utf-8", errors="replace",
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    if check and result.returncode != 0:
        raise RuntimeError(f"命令失败（{result.returncode}）：{' '.join(command[:3])}\n{result.stdout[-2000:]}")
    return result


def prepare_worktree(claim: dict[str, Any], root: Path, git_command: str) -> Path:
    project_dir = root / claim["workspaceDir"]
    if not (project_dir / ".git").exists():
        project_dir.parent.mkdir(parents=True, exist_ok=True)
        log(f"[{claim['runId']}] 首次克隆 {claim['repoUrl']}")
        run([git_command, "clone", claim["repoUrl"], str(project_dir)])
    run([git_command, "fetch", "--prune", "origin"], cwd=project_dir)
    run([git_command, "worktree", "prune"], cwd=project_dir)
    worktree = root / ".wao-runs" / claim["runId"]
    worktree.parent.mkdir(parents=True, exist_ok=True)
    if worktree.exists():
        run([git_command, "worktree", "remove", "--force", str(worktree)], cwd=project_dir, check=False)
        if worktree.exists():
            shutil.rmtree(worktree)
    run([git_command, "branch", "-D", claim["branchName"]], cwd=project_dir, check=False)
    run(
        [git_command, "worktree", "add", "-b", claim["branchName"], str(worktree),
         f"origin/{claim['defaultBranch']}"],
        cwd=project_dir,
    )
    return worktree


def find_executable(name: str, configured: str | None = None) -> str:
    if configured:
        executable = shutil.which(configured)
        if not executable and Path(configured).is_file():
            executable = str(Path(configured).resolve())
    else:
        executable = shutil.which(name)
    if not executable:
        hint = "；可通过命令行参数或环境变量指定完整路径" if configured is None else ""
        raise RuntimeError(f"找不到 {name}{hint}")
    return executable


def invoke_codex(command: list[str], worktree: Path, prompt: str,
        on_event: Callable[[dict[str, Any]], None] | None = None) -> tuple[int, str]:
    """通过标准输入传递提示，并逐行消费 Codex JSONL 事件。"""
    process = subprocess.Popen(
        command, cwd=str(worktree), text=True, encoding="utf-8", errors="replace",
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    if process.stdin is None or process.stdout is None:
        raise RuntimeError("无法建立 Codex 标准输入输出管道")
    process.stdin.write(prompt)
    process.stdin.close()
    lines: list[str] = []
    for line in process.stdout:
        lines.append(line)
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if on_event:
            on_event(event)
    process.wait()
    return process.returncode, "".join(lines)


def _readable_text(value: Any) -> str:
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, list):
        parts = [_readable_text(item) for item in value]
        return "\n".join(part for part in parts if part)
    if isinstance(value, dict):
        for key in ("text", "summary", "content", "message"):
            text = _readable_text(value.get(key))
            if text:
                return text
    return ""


def extract_progress(event: dict[str, Any]) -> tuple[str, str] | None:
    """只提取用户可读结果；明确排除命令、文件、工具调用和网页检索事件。"""
    event_type = str(event.get("type") or "")
    if event_type == "turn.started":
        return "phase", "Codex 已开始分析任务"
    if event_type == "turn.failed":
        return "result", "Codex 本轮执行失败，正在整理错误原因"
    if not event_type.startswith("item."):
        return None
    item = event.get("item")
    if not isinstance(item, dict):
        return None
    item_type = str(item.get("type") or "")
    if item_type == "agent_message":
        text = _readable_text(item.get("text") or item.get("content"))
        if text.startswith("WAO_RUN_STATUS:"):
            text = "\n".join(text.splitlines()[1:]).strip()
        return ("message", text) if text else None
    if item_type in {"plan", "plan_update"}:
        text = _readable_text(item.get("text") or item.get("plan") or item.get("content"))
        return ("plan", text) if text else None
    if item_type == "reasoning":
        # 仅使用 Codex 主动提供的公开摘要，不读取内部推理正文。
        text = _readable_text(item.get("summary"))
        return ("message", text) if text else None
    return None


class ProgressReporter:
    """对进展去重和节流，网络失败不影响本机执行。"""

    def __init__(self, api: WaoApi, run_id: str, interval: float = 2.0) -> None:
        self.api = api
        self.run_id = run_id
        self.interval = interval
        self.last_sent_at = 0.0
        self.last_content = ""
        self.pending: tuple[str, str] | None = None

    def send(self, kind: str, content: str, force: bool = False) -> None:
        content = " ".join(content.split())[:1000]
        if not content or content == self.last_content:
            return
        now = time.monotonic()
        if not force and now - self.last_sent_at < self.interval:
            self.pending = (kind, content)
            return
        try:
            self.api.post(f"/api/ai/runner/runs/{self.run_id}/progress", {"kind": kind, "content": content})
            self.last_sent_at = now
            self.last_content = content
            self.pending = None
        except Exception as exc:  # noqa: BLE001 - 进展上报不能中断 Codex
            log(f"[{self.run_id}] 进展上报失败，继续执行：{exc}")

    def event(self, event: dict[str, Any]) -> None:
        progress = extract_progress(event)
        if progress:
            self.send(*progress)

    def flush(self) -> None:
        if self.pending:
            self.send(*self.pending, force=True)


def normalize_result(exit_code: int, result_text: str) -> tuple[int, str]:
    """同时校验 Codex 退出码和业务完成标记，避免把反问或阻塞误报为成功。"""
    if exit_code != 0:
        return exit_code, f"Codex 退出码：{exit_code}\n{result_text or 'Codex 未返回文字输出'}"
    lines = result_text.strip().splitlines()
    marker = lines[0].strip() if lines else ""
    detail = "\n".join(lines[1:]).strip()
    if marker == "WAO_RUN_STATUS: SUCCESS":
        return 0, detail or "Codex 已完成任务，但没有返回结果摘要。"
    if marker == "WAO_RUN_STATUS: BLOCKED":
        return 2, f"Codex 未完成任务：{detail or '未说明阻塞原因'}"
    return 3, (
        "Codex 进程已结束，但没有确认任务完成，已按失败处理。\n"
        f"原始答复：\n{result_text or 'Codex 未返回文字输出'}"
    )


def execute_claim(api: WaoApi, claim: dict[str, Any], root: Path, sandbox: str,
        git_command: str, codex_command: str) -> None:
    run_id = claim["runId"]
    worktree: Path | None = None
    exit_code = -1
    session_id: str | None = None
    summary = ""
    stage = "准备 Git 工作区"
    reporter = ProgressReporter(api, run_id)
    try:
        log(f"[{run_id}] 开始：{claim['taskTitle']}")
        reporter.send("phase", "正在准备独立工作区", force=True)
        worktree = prepare_worktree(claim, root, git_command)
        result_file = worktree / ".wao-ai-result.txt"
        prompt = (
            "这是 WAO 自动派发的开发任务。请先读取下面的公开 Markdown 地址，然后在当前独立 "
            "Git worktree 中完整实现需求：\n"
            f"{claim['promptUrl']}\n\n"
            "必须遵守仓库 AGENTS.md 与相关规格；先确认当前分支基于最新 main。完成与风险相称的"
            "测试后，直接写详细中文提交，创建 PR、合并 main、等待部署并验证线上结果与飞书通知。"
            "不要停下来等待人工确认。若外部权限确实阻塞，请在最终结果中准确说明。\n\n"
            "最终答复必须以状态行开头：全部完成并验收通过时第一行写 `WAO_RUN_STATUS: SUCCESS`；"
            "尚未完成或需要补充信息时第一行写 `WAO_RUN_STATUS: BLOCKED`。状态行后再写中文结果摘要。"
        )
        stage = "启动 Codex"
        reporter.send("phase", "工作区已准备完成，正在启动 Codex", force=True)
        command = [
            codex_command, "exec", "--json", "--color", "never", "--sandbox", sandbox,
            "-c", 'approval_policy="never"', "-C", str(worktree),
            "--output-last-message", str(result_file), "-",
        ]
        stage = "等待 Codex 完成"
        exit_code, output = invoke_codex(command, worktree, prompt, reporter.event)
        reporter.flush()
        for line in output.splitlines():
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            session_id = event.get("thread_id") or event.get("session_id") or session_id
        result_text = result_file.read_text(encoding="utf-8", errors="replace") if result_file.exists() else output[-12000:]
        exit_code, result_text = normalize_result(exit_code, result_text)
        reporter.send("result", "Codex 已执行完成，正在整理结果" if exit_code == 0
                      else "Codex 执行未完成，正在整理失败原因", force=True)
        summary = f"工作目录：{worktree}\n\n{result_text}"[-30000:]
    except Exception as exc:  # noqa: BLE001 - 必须把节点错误回传给 WAO
        summary = (f"失败阶段：{stage}\n错误详情：{exc}\n"
                   f"工作目录：{worktree or '尚未创建'}\n本机日志：{LOG_FILE}")[-30000:]
        log(f"[{run_id}] {stage}失败：{exc}")
    finally:
        try:
            api.post(
                f"/api/ai/runner/runs/{run_id}/complete",
                {"exitCode": exit_code, "resultSummary": summary, "sessionId": session_id},
            )
            log(f"[{run_id}] 已回传，退出码 {exit_code}")
        except Exception as exc:  # noqa: BLE001
            log(f"[{run_id}] 回传失败，下次心跳会让任务重新排队：{exc}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="WAO AI Runner")
    parser.add_argument("--server", default=os.getenv("WAO_SERVER_URL", "https://wao.wayuwa.cn"))
    parser.add_argument("--name", default=os.getenv("COMPUTERNAME") or platform.node() or "Codex 电脑")
    parser.add_argument("--root", default=os.getenv("WAO_WORKSPACE_ROOT", str(Path.home() / "CodeSpace")))
    parser.add_argument("--pair-code", default=os.getenv("WAO_PAIR_CODE"))
    parser.add_argument("--git-command", default=os.getenv("WAO_GIT_COMMAND"))
    parser.add_argument("--codex-command", default=os.getenv("WAO_CODEX_COMMAND"))
    parser.add_argument("--max-concurrency", type=int, default=int(os.getenv("WAO_RUNNER_CONCURRENCY", "2")))
    parser.add_argument(
        "--sandbox",
        choices=["read-only", "workspace-write", "danger-full-access"],
        default=os.getenv("WAO_CODEX_SANDBOX", "danger-full-access"),
        help="开发阶段默认完整权限；建议在专用系统账号或隔离电脑运行",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    args.max_concurrency = max(1, min(8, args.max_concurrency))
    state = load_state()
    if state.get("server") != args.server.rstrip("/") or not state.get("token"):
        state = pair(WaoApi(args.server), args)
    api = WaoApi(args.server, state["token"])
    root = Path(args.root).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    git_command = find_executable("git", args.git_command)
    codex_command = find_executable("codex", args.codex_command)
    log(f"WAO AI Runner 已启动：工作区 {root}，并发 {args.max_concurrency}，日志 {LOG_FILE}")
    log(f"运行环境：Git={git_command}，Codex={codex_command}")
    futures: set[Future[None]] = set()
    with ThreadPoolExecutor(max_workers=args.max_concurrency, thread_name_prefix="wao-ai") as executor:
        while True:
            futures = {future for future in futures if not future.done()}
            try:
                api.post(f"/api/ai/runner/heartbeat?maxConcurrency={args.max_concurrency}")
                while len(futures) < args.max_concurrency:
                    claim = api.post("/api/ai/runner/claim")
                    if not claim:
                        break
                    futures.add(executor.submit(
                        execute_claim, api, claim, root, args.sandbox, git_command, codex_command))
            except Exception as exc:  # noqa: BLE001
                log(f"连接 WAO 失败，10 秒后重试：{exc}")
            time.sleep(10)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        log("已停止")
        raise SystemExit(0)
    except Exception as error:  # noqa: BLE001
        log(f"启动失败：{error}")
        raise SystemExit(1)
