#!/usr/bin/env python3
"""WAO 本地 AI 执行节点：领取任务，在独立 Git worktree 中调用 Codex 或 Claude Code CLI。"""

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
# 运行结束后保留 worktree 供人工复查，超过这个天数才回收，避免磁盘被历史任务占满。
RUN_WORKTREE_TTL_DAYS = 7
ENGINE_LABELS = {"codex": "Codex", "claude": "Claude Code"}
# 沙箱强度到各 CLI 参数的映射，保证两种工具在同一档位下权限一致。
CLAUDE_PERMISSION = {
    "read-only": ["--permission-mode", "plan"],
    "workspace-write": ["--permission-mode", "acceptEdits"],
    "danger-full-access": ["--dangerously-skip-permissions"],
}


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


def pair(api: WaoApi, args: argparse.Namespace, engines: list[str]) -> dict[str, str]:
    if not args.pair_code:
        raise RuntimeError("本机尚未配对，请在 AI 控制台生成配对码，再传入 --pair-code")
    result = api.post(
        "/api/ai/runner/pair",
        {
            "code": args.pair_code,
            "name": args.name,
            "platform": f"{platform.system()} {platform.release()}",
            "engines": engines,
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


def resolve_repo_url(repo_url: str, protocol: str, https_ready: bool) -> str:
    """按本机的 Git 认证方式选择克隆地址。

    项目配置里只填一个 HTTPS 地址，但每台电脑的认证方式可能不同：Windows 用凭据管理器里的
    HTTPS 凭据，Linux 主节点用 SSH key。auto 表示本机没有可用的 HTTPS 凭据时自动改用 SSH，
    这样同一个项目配置在两种机器上都能克隆。
    """
    if not repo_url.startswith("https://"):
        return repo_url
    if protocol == "https" or (protocol == "auto" and https_ready):
        return repo_url
    host, _, path = repo_url[len("https://"):].partition("/")
    if not path:
        return repo_url
    return f"git@{host.rpartition('@')[2]}:{path}"


def detach_primary_clone(project_dir: Path, git_command: str) -> None:
    """把主克隆切成游离 HEAD，让默认分支永远不被任何 worktree 占用。

    主克隆只当对象仓库用，任务全在 `.wao-runs/<run-id>` 的独立 worktree 里跑。如果主克隆
    一直检出 `main`，AI 在 worktree 里执行 `git switch main` 校对基线时就会撞上
    `fatal: 'main' is already checked out at ...`，白白浪费轮次甚至放弃任务。

    这里用 `update-ref --no-deref` 而不是 `checkout --detach`：后者会顺带把工作副本铺出来，
    既抵消了 `--no-checkout`，也可能覆盖旧克隆里的本地改动。前者只重写 HEAD 引用，索引和
    工作区一个字节都不碰。
    """
    head = run([git_command, "symbolic-ref", "--quiet", "HEAD"], cwd=project_dir, check=False)
    if head.returncode != 0:
        return
    branch = head.stdout.strip().rpartition("/")[2]
    commit = run([git_command, "rev-parse", "HEAD"], cwd=project_dir, check=False)
    if commit.returncode != 0:
        return  # 还没有任何提交，HEAD 未出生，Git 不会认为分支被占用。
    result = run([git_command, "update-ref", "--no-deref", "HEAD", commit.stdout.strip()],
                 cwd=project_dir, check=False)
    if result.returncode == 0:
        log(f"主克隆 {project_dir} 已切到游离 HEAD，分支 {branch} 现在可以被任务 worktree 使用")
    else:
        log(f"主克隆 {project_dir} 无法切到游离 HEAD，AI 可能会遇到分支被占用：{result.stdout[-300:]}")


def owning_repo(worktree: Path) -> Path | None:
    """从 worktree 的 .git 指针反查它属于哪个主克隆；`.wao-runs` 下混放着多个项目的工作区。"""
    pointer = worktree / ".git"
    if not pointer.is_file():
        return None
    text = pointer.read_text(encoding="utf-8", errors="replace").strip()
    if not text.startswith("gitdir:"):
        return None
    git_dir = Path(text[len("gitdir:"):].strip())
    # gitdir 形如 <repo>/.git/worktrees/<name>，向上两级即主克隆的 .git 目录。
    return git_dir.parent.parent.parent if git_dir.parent.parent.name == ".git" else None


def drop_worktree(worktree: Path, git_command: str, fallback_repo: Path | None = None) -> None:
    repo = owning_repo(worktree) or fallback_repo
    if repo and repo.exists():
        run([git_command, "worktree", "remove", "--force", str(worktree)], cwd=repo, check=False)
    if worktree.exists():
        shutil.rmtree(worktree, ignore_errors=True)
    if repo and repo.exists():
        run([git_command, "worktree", "prune"], cwd=repo, check=False)


def prune_stale_worktrees(runs_root: Path, git_command: str, keep: set[str]) -> None:
    """回收超过保留期的历史运行工作区；失败不影响本次任务。"""
    if not runs_root.is_dir():
        return
    deadline = time.time() - RUN_WORKTREE_TTL_DAYS * 86400
    for entry in runs_root.iterdir():
        if not entry.is_dir() or entry.name in keep:
            continue
        try:
            if entry.stat().st_mtime >= deadline:
                continue
            drop_worktree(entry, git_command)
            log(f"已回收 {RUN_WORKTREE_TTL_DAYS} 天前的历史工作区 {entry}")
        except OSError as exc:
            log(f"回收历史工作区 {entry} 失败，忽略：{exc}")


def prepare_worktree(claim: dict[str, Any], root: Path, git_command: str, repo_url: str) -> Path:
    project_dir = root / claim["workspaceDir"]
    if not (project_dir / ".git").exists():
        project_dir.parent.mkdir(parents=True, exist_ok=True)
        log(f"[{claim['runId']}] 首次克隆 {repo_url}")
        if project_dir.exists():
            # 上一次克隆失败可能留下半个目录，git clone 会因为目录非空直接报错。
            shutil.rmtree(project_dir)
        # 主克隆不需要工作副本，只提供对象库和 worktree 元数据。
        run([git_command, "clone", "--no-checkout", repo_url, str(project_dir)])
    # 本机认证方式变了（比如从 HTTPS 换成 SSH）时，把已有仓库的 origin 一并纠正。
    run([git_command, "remote", "set-url", "origin", repo_url], cwd=project_dir, check=False)
    run([git_command, "fetch", "--prune", "origin"], cwd=project_dir)
    run([git_command, "worktree", "prune"], cwd=project_dir)
    detach_primary_clone(project_dir, git_command)
    runs_root = root / ".wao-runs"
    runs_root.mkdir(parents=True, exist_ok=True)
    prune_stale_worktrees(runs_root, git_command, {claim["runId"]})
    worktree = runs_root / claim["runId"]
    if worktree.exists():
        drop_worktree(worktree, git_command, project_dir)
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


def find_github_cli() -> str | None:
    """查找 GitHub CLI；Windows 计划任务通常拿不到系统安装目录中的 PATH。"""
    executable = shutil.which("gh")
    if executable:
        return executable
    candidates = []
    for root_name in ("ProgramFiles", "LOCALAPPDATA"):
        root = os.getenv(root_name)
        if root:
            candidates.append(Path(root) / "GitHub CLI" / "gh.exe")
    return next((str(path) for path in candidates if path.is_file()), None)


def build_cli_environment(git_command: str) -> tuple[dict[str, str], bool, str | None]:
    """把本机 Git 凭据临时交给 AI CLI 子进程，不写配置文件或日志。"""
    environment = os.environ.copy()
    github_cli = find_github_cli()
    if github_cli:
        cli_dir = str(Path(github_cli).parent)
        environment["PATH"] = cli_dir + os.pathsep + environment.get("PATH", "")
    credential = subprocess.run(
        [git_command, "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
        text=True, encoding="utf-8", errors="replace", stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, check=False,
    )
    fields: dict[str, str] = {}
    if credential.returncode == 0:
        for line in credential.stdout.splitlines():
            key, separator, value = line.partition("=")
            if separator:
                fields[key] = value
    token = fields.get("password", "").strip()
    if token:
        environment["GH_TOKEN"] = token
    return environment, bool(token), github_cli


def invoke_cli(command: list[str], worktree: Path, prompt: str,
        on_event: Callable[[dict[str, Any]], None] | None = None,
        environment: dict[str, str] | None = None) -> tuple[int, str]:
    """通过标准输入传递提示，并逐行消费 AI CLI 的 JSONL 事件。"""
    process = subprocess.Popen(
        command, cwd=str(worktree), text=True, encoding="utf-8", errors="replace",
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=environment,
    )
    if process.stdin is None or process.stdout is None:
        raise RuntimeError("无法建立 AI CLI 的标准输入输出管道")
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


def extract_progress(event: dict[str, Any], engine: str = "codex") -> tuple[str, str] | None:
    """按 AI 工具选择事件解析方式；两种工具都只回传用户可读内容。"""
    return extract_claude_progress(event) if engine == "claude" else extract_codex_progress(event)


def _claude_plan_text(todos: Any) -> str:
    if not isinstance(todos, list):
        return ""
    marks = {"completed": "已完成", "in_progress": "进行中", "pending": "待办"}
    lines = []
    for index, todo in enumerate(todos, start=1):
        if not isinstance(todo, dict):
            continue
        text = _readable_text(todo.get("content"))
        if text:
            lines.append(f"{index}. {text}（{marks.get(str(todo.get('status')), '待办')}）")
    return "\n".join(lines)


def extract_claude_progress(event: dict[str, Any]) -> tuple[str, str] | None:
    """解析 Claude Code 的 stream-json 事件；thinking 正文与工具调用细节不外传。"""
    event_type = str(event.get("type") or "")
    if event_type == "system" and event.get("subtype") == "init":
        return "phase", "Claude Code 会话已建立，正在分析任务"
    if event_type == "result":
        if event.get("is_error"):
            return "result", "Claude Code 本轮执行失败，正在整理错误原因"
        return None
    if event_type != "assistant":
        return None
    blocks = (event.get("message") or {}).get("content")
    if not isinstance(blocks, list):
        return None
    texts = []
    for block in blocks:
        if not isinstance(block, dict):
            continue
        if block.get("type") == "text":
            text = _readable_text(block.get("text"))
            if text.startswith("WAO_RUN_STATUS:"):
                text = "\n".join(text.splitlines()[1:]).strip()
            if text:
                texts.append(text)
        elif block.get("type") == "tool_use" and block.get("name") == "TodoWrite":
            plan = _claude_plan_text((block.get("input") or {}).get("todos"))
            if plan:
                return "plan", plan
    return ("message", "\n".join(texts)) if texts else None


def extract_codex_progress(event: dict[str, Any]) -> tuple[str, str] | None:
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

    def __init__(self, api: WaoApi, run_id: str, engine: str = "codex", interval: float = 2.0) -> None:
        self.api = api
        self.run_id = run_id
        self.engine = engine
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
        progress = extract_progress(event, self.engine)
        if progress:
            self.send(*progress)

    def flush(self) -> None:
        if self.pending:
            self.send(*self.pending, force=True)


def normalize_result(exit_code: int, result_text: str, engine: str = "codex") -> tuple[int, str]:
    """同时校验 CLI 退出码和业务完成标记，避免把反问或阻塞误报为成功。"""
    name = ENGINE_LABELS.get(engine, engine)
    if exit_code != 0:
        return exit_code, f"{name} 退出码：{exit_code}\n{result_text or f'{name} 未返回文字输出'}"
    lines = result_text.strip().splitlines()
    marker = lines[0].strip() if lines else ""
    detail = "\n".join(lines[1:]).strip()
    if marker == "WAO_RUN_STATUS: SUCCESS":
        return 0, detail or f"{name} 已完成任务，但没有返回结果摘要。"
    if marker == "WAO_RUN_STATUS: BLOCKED":
        return 2, f"{name} 未完成任务：{detail or '未说明阻塞原因'}"
    return 3, (
        f"{name} 进程已结束，但没有确认任务完成，已按失败处理。\n"
        f"原始答复：\n{result_text or f'{name} 未返回文字输出'}"
    )


def build_prompt(claim: dict[str, Any]) -> str:
    base = claim["defaultBranch"]
    prompt = (
        "这是 WAO 自动派发的开发任务。请先读取下面的公开 Markdown 地址，然后在当前独立 "
        "Git worktree 中完整实现需求：\n"
        f"{claim['promptUrl']}\n\n"
        f"工作区已经准备好：当前目录是专属 Git worktree，分支 `{claim['branchName']}` 刚从最新的 "
        f"`origin/{base}` 创建，不需要再对基线做任何准备。主克隆目录是共享的游离 HEAD 仓库，"
        f"**任何目录下都不要 checkout / switch 到 `{base}`，也不要新建其它 worktree**；"
        f"需要对比基线时用 `git fetch origin {base}` 之后读 `origin/{base}`（例如 "
        f"`git diff origin/{base}...HEAD`）。请始终留在 `{claim['branchName']}` 上提交。\n\n"
        "必须遵守仓库 AGENTS.md 与相关规格。完成与风险相称的"
        "测试后，直接写详细中文提交，创建 PR、合并 main。"
        "Runner 已把本机 Git 凭据通过 GH_TOKEN 临时交给当前进程，并把 GitHub CLI 加入 PATH；"
        "GitHub 操作优先使用 gh，不要因为 AI 工具内置的 GitHub 连接器不可用而停止。"
        "不要停下来等待人工确认。若外部权限确实阻塞，请在最终结果中准确说明。\n\n"
        "合并只是做完了一半：合并后必须用 gh（如 `gh run watch` / `gh run view --log-failed`）"
        "盯住 main 上的这次部署，直到它跑完为止，再验证线上结果与飞书通知。"
        "部署失败时先读报错、修好、重新走一次提交与合并，直到部署成功；"
        "只有部署确实成功并且线上验证通过，才可以回复 SUCCESS —— "
        "部署还没结束、或者最后一次部署是失败的，一律回复 BLOCKED 并把完整报错写进摘要，"
        "由用户在 WAO 里点「基于报错重跑」让下一次运行接着修。\n\n"
        "最终答复必须以状态行开头：全部完成并验收通过时第一行写 `WAO_RUN_STATUS: SUCCESS`；"
        "尚未完成或需要补充信息时第一行写 `WAO_RUN_STATUS: BLOCKED`。状态行后再写中文结果摘要。"
    )
    previous = _readable_text(claim.get("previousFailure")).strip()
    if previous:
        prompt += (
            "\n\n这是一次「基于报错重跑」：同一个任务上一次没有跑通，报错如下。"
            "请先判断上一次改到哪一步（相关提交可能已经合进 main），从这个报错继续往下修，"
            "不要把已经做完的部分推倒重来。\n"
            "----- 上一次的失败详情 -----\n"
            f"{previous[-8000:]}\n"
            "----- 失败详情结束 -----"
        )
    return prompt


def run_codex(executable: str, worktree: Path, prompt: str, sandbox: str,
        reporter: "ProgressReporter",
        environment: dict[str, str] | None = None,
        session_id: str | None = None) -> tuple[int, str, str | None, dict[str, Any]]:
    """跑一次 `codex exec --json`，返回退出码、最终答复、会话 id 和用量。"""
    result_file = worktree / ".wao-ai-result.txt"
    command = [
        executable, "exec", *( ["resume", session_id] if session_id else []), "--json", "--color", "never", "--sandbox", sandbox,
        "-c", 'approval_policy="never"', "-C", str(worktree),
        "--output-last-message", str(result_file), "-",
    ]
    exit_code, output = invoke_cli(command, worktree, prompt, reporter.event, environment)
    reporter.flush()
    session_id = None
    for event in iter_events(output):
        session_id = event.get("thread_id") or event.get("session_id") or session_id
    result_text = (result_file.read_text(encoding="utf-8", errors="replace")
                   if result_file.exists() else output[-12000:])
    return exit_code, result_text, session_id, collect_usage(output, "codex")


def run_claude(executable: str, worktree: Path, prompt: str, sandbox: str,
        reporter: "ProgressReporter", environment: dict[str, str] | None = None,
        model: str | None = None, repo_dir: Path | None = None,
        session_id: str | None = None) -> tuple[int, str, str | None, dict[str, Any]]:
    """跑一次 `claude -p --output-format stream-json`，返回退出码、最终答复、会话 id 和用量。"""
    command = [
        executable, "-p", "--output-format", "stream-json", "--verbose",
        "--add-dir", str(worktree),
        *CLAUDE_PERMISSION.get(sandbox, CLAUDE_PERMISSION["workspace-write"]),
    ]
    # worktree 的 .git 指向主仓库目录，受限权限档位下必须一并放行，否则 git 操作会卡在询问。
    if repo_dir:
        command += ["--add-dir", str(repo_dir)]
    if model:
        command += ["--model", model]
    if session_id:
        command += ["--resume", session_id]
    exit_code, output = invoke_cli(command, worktree, prompt, reporter.event, environment)
    reporter.flush()
    session_id, result_text = None, ""
    for event in iter_events(output):
        session_id = event.get("session_id") or session_id
        if event.get("type") == "result":
            result_text = _readable_text(event.get("result")) or result_text
            if event.get("is_error") and exit_code == 0:
                exit_code = 1
    return exit_code, result_text or output[-12000:], session_id, collect_usage(output, "claude")


def iter_events(output: str):
    for line in output.splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(event, dict):
            yield event


def _count(value: Any) -> int:
    """只接受真正的整数计数；布尔和字符串一律当 0，避免把脏字段算进用量。"""
    return value if isinstance(value, int) and not isinstance(value, bool) else 0


def _amount(value: Any) -> float:
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else 0.0


def _blank_usage() -> dict[str, Any]:
    return {"model": None, "models": None, "inputTokens": 0, "outputTokens": 0,
            "cachedInputTokens": 0, "totalTokens": 0, "costUsd": None}


def _finish_usage(usage: dict[str, Any], by_model: dict[str, int], cost: float,
        counted_cost: bool) -> dict[str, Any]:
    """按输出 token 挑出主模型，并把合计与花费补齐。"""
    if by_model:
        usage["model"] = max(by_model, key=lambda name: by_model[name])
        # 一次运行可能中途换模型（自动选模型时尤其常见），全部记下来便于事后核账。
        usage["models"] = ",".join(sorted(by_model))
    usage["totalTokens"] = usage["inputTokens"] + usage["outputTokens"] + usage["cachedInputTokens"]
    usage["costUsd"] = round(cost, 6) if counted_cost else None
    return usage


def collect_claude_usage(events: list[dict[str, Any]]) -> dict[str, Any]:
    """读取 Claude Code `result` 事件里的 usage / total_cost_usd / modelUsage。"""
    usage, by_model, cost, counted_cost = _blank_usage(), {}, 0.0, False
    fallback_model = None
    for event in events:
        if event.get("type") == "system" and event.get("subtype") == "init":
            fallback_model = event.get("model") or fallback_model
        if event.get("type") != "result":
            continue
        block = event.get("usage")
        if isinstance(block, dict):
            usage["inputTokens"] += _count(block.get("input_tokens"))
            usage["outputTokens"] += _count(block.get("output_tokens"))
            usage["cachedInputTokens"] += (_count(block.get("cache_read_input_tokens"))
                                           + _count(block.get("cache_creation_input_tokens")))
        if "total_cost_usd" in event:
            cost += _amount(event.get("total_cost_usd"))
            counted_cost = True
        per_model = event.get("modelUsage")
        if isinstance(per_model, dict):
            for name, detail in per_model.items():
                if isinstance(detail, dict):
                    by_model[str(name)] = by_model.get(str(name), 0) + _count(detail.get("outputTokens"))
    if not by_model and fallback_model:
        by_model[str(fallback_model)] = 0
    return _finish_usage(usage, by_model, cost, counted_cost)


def collect_codex_usage(events: list[dict[str, Any]]) -> dict[str, Any]:
    """累加 Codex `turn.completed` 事件里的 usage；Codex 不回传花费，只记 token。"""
    usage, by_model = _blank_usage(), {}
    for event in events:
        model = event.get("model")
        if isinstance(model, str) and model:
            by_model.setdefault(model, 0)
        block = event.get("usage")
        if not isinstance(block, dict):
            continue
        output_tokens = _count(block.get("output_tokens"))
        usage["inputTokens"] += _count(block.get("input_tokens"))
        usage["outputTokens"] += output_tokens
        usage["cachedInputTokens"] += _count(block.get("cached_input_tokens"))
        if isinstance(model, str) and model:
            by_model[model] += output_tokens
    return _finish_usage(usage, by_model, 0.0, False)


def collect_usage(output: str, engine: str) -> dict[str, Any]:
    """汇总一次运行的 token 用量、花费和实际使用的模型。

    解析失败不能影响任务结果回传，因此任何异常都回落成空用量：宁可少一条账，也不能
    让一次已经跑完的任务因为统计问题变成失败。
    """
    try:
        events = list(iter_events(output))
        return collect_claude_usage(events) if engine == "claude" else collect_codex_usage(events)
    except Exception as exc:  # noqa: BLE001 - 统计失败不影响任务结果
        log(f"用量统计失败，本次不记账：{exc}")
        return _blank_usage()


def execute_claim(api: WaoApi, claim: dict[str, Any], root: Path, sandbox: str, git_command: str,
        engine_commands: dict[str, str], cli_environment: dict[str, str] | None = None,
        claude_model: str | None = None, git_protocol: str = "auto",
        https_ready: bool = False) -> None:
    run_id = claim["runId"]
    engine = str(claim.get("engine") or "codex")
    name = ENGINE_LABELS.get(engine, engine)
    worktree: Path | None = None
    exit_code = -1
    session_id: str | None = None
    usage = _blank_usage()
    summary = ""
    stage = "准备 Git 工作区"
    reporter = ProgressReporter(api, run_id, engine)
    try:
        log(f"[{run_id}] 开始（{name}）：{claim['taskTitle']}")
        executable = engine_commands.get(engine)
        if not executable:
            raise RuntimeError(f"本机没有可用的 {name} 命令，请检查 --engines 与 CLI 安装情况")
        reporter.send("phase", "正在准备独立工作区", force=True)
        continuation = _readable_text(claim.get("continuationInput")).strip()
        session_id = _readable_text(claim.get("sessionId")).strip() or None
        if continuation and session_id:
            worktree = root / ".wao-runs" / run_id
            if not worktree.exists():
                raise RuntimeError("原 AI 工作区不存在，无法安全恢复会话；请使用基于报错重跑")
            reporter.send("phase", "正在恢复原 AI 会话", force=True)
            prompt = "用户补充了以下信息，请在当前任务和已有工作区基础上继续执行：\n\n" + continuation
        else:
            worktree = prepare_worktree(
                claim, root, git_command, resolve_repo_url(claim["repoUrl"], git_protocol, https_ready))
            prompt = build_prompt(claim)
        stage = f"启动 {name}"
        reporter.send("phase", f"工作区已准备完成，正在启动 {name}", force=True)
        stage = f"等待 {name} 完成"
        if engine == "claude":
            exit_code, result_text, session_id, usage = run_claude(
                executable, worktree, prompt, sandbox, reporter, cli_environment,
                claude_model, root / claim["workspaceDir"], session_id)
        else:
            exit_code, result_text, session_id, usage = run_codex(
                executable, worktree, prompt, sandbox, reporter, cli_environment, session_id)
        cost_text = "未回传" if usage["costUsd"] is None else "$%.4f" % usage["costUsd"]
        log(f"[{run_id}] 用量：模型 {usage['model'] or '未知'}，"
            f"token {usage['totalTokens']}（输入 {usage['inputTokens']} / 输出 "
            f"{usage['outputTokens']} / 缓存 {usage['cachedInputTokens']}），折算额度 {cost_text}")
        exit_code, result_text = normalize_result(exit_code, result_text, engine)
        reporter.send("result", f"{name} 已执行完成，正在整理结果" if exit_code == 0
                      else f"{name} 执行未完成，正在整理失败原因", force=True)
        summary = f"执行工具：{name}\n工作目录：{worktree}\n\n{result_text}"[-30000:]
    except Exception as exc:  # noqa: BLE001 - 必须把节点错误回传给 WAO
        summary = (f"失败阶段：{stage}\n错误详情：{exc}\n执行工具：{name}\n"
                   f"工作目录：{worktree or '尚未创建'}\n本机日志：{LOG_FILE}")[-30000:]
        log(f"[{run_id}] {stage}失败：{exc}")
    finally:
        try:
            api.post(
                f"/api/ai/runner/runs/{run_id}/complete",
                {"exitCode": exit_code, "resultSummary": summary, "sessionId": session_id, **usage},
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
    parser.add_argument("--claude-command", default=os.getenv("WAO_CLAUDE_COMMAND"))
    parser.add_argument("--claude-model", default=os.getenv("WAO_CLAUDE_MODEL"),
                        help="传给 Claude Code 的模型别名；留空使用本机默认模型")
    parser.add_argument(
        "--git-protocol",
        choices=["auto", "https", "ssh"],
        default=os.getenv("WAO_GIT_PROTOCOL", "auto"),
        help="克隆项目仓库使用的协议；auto 表示本机没有 HTTPS 凭据时自动改用 SSH",
    )
    parser.add_argument("--engines", default=os.getenv("WAO_RUNNER_ENGINES"),
                        help="本机可用的 AI 工具，逗号分隔（codex,claude）；留空按已安装的 CLI 自动探测")
    parser.add_argument("--max-concurrency", type=int, default=int(os.getenv("WAO_RUNNER_CONCURRENCY", "2")))
    parser.add_argument(
        "--sandbox",
        choices=["read-only", "workspace-write", "danger-full-access"],
        default=os.getenv("WAO_CODEX_SANDBOX", "danger-full-access"),
        help="开发阶段默认完整权限；建议在专用系统账号或隔离电脑运行",
    )
    return parser.parse_args()


def resolve_engines(args: argparse.Namespace) -> dict[str, str]:
    """解析本机可用的 AI 工具；显式指定时找不到命令直接报错，自动探测时静默跳过。"""
    configured = {"codex": args.codex_command, "claude": args.claude_command}
    wanted = [name.strip().lower() for name in (args.engines or "").split(",") if name.strip()]
    for name in wanted:
        if name not in ENGINE_LABELS:
            raise RuntimeError(f"不支持的 AI 工具：{name}（可选 {'、'.join(ENGINE_LABELS)}）")
    commands: dict[str, str] = {}
    for name in wanted or list(ENGINE_LABELS):
        try:
            commands[name] = find_executable(name, configured[name])
        except RuntimeError:
            if wanted:
                raise
    if not commands:
        raise RuntimeError("本机没有找到 codex 或 claude 命令，请先安装并登录至少一种 AI CLI")
    return commands


def main() -> int:
    args = parse_args()
    args.max_concurrency = max(1, min(8, args.max_concurrency))
    git_command = find_executable("git", args.git_command)
    engine_commands = resolve_engines(args)
    engines = list(engine_commands)
    state = load_state()
    if state.get("server") != args.server.rstrip("/") or not state.get("token"):
        state = pair(WaoApi(args.server), args, engines)
    api = WaoApi(args.server, state["token"])
    root = Path(args.root).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    cli_environment, github_token_ready, github_cli = build_cli_environment(git_command)
    log(f"WAO AI Runner 已启动：工作区 {root}，并发 {args.max_concurrency}，日志 {LOG_FILE}")
    log("运行环境：Git=" + git_command
        + "".join(f"，{ENGINE_LABELS[name]}={path}" for name, path in engine_commands.items()))
    log(f"GitHub 自动授权：凭据={'可用' if github_token_ready else '不可用'}，"
        f"GitHub CLI={github_cli or '未找到'}，"
        f"克隆协议={args.git_protocol}"
        f"{'（无 HTTPS 凭据，改用 SSH）' if args.git_protocol == 'auto' and not github_token_ready else ''}")
    futures: set[Future[None]] = set()
    with ThreadPoolExecutor(max_workers=args.max_concurrency, thread_name_prefix="wao-ai") as executor:
        while True:
            futures = {future for future in futures if not future.done()}
            try:
                api.post(f"/api/ai/runner/heartbeat?maxConcurrency={args.max_concurrency}"
                         f"&engines={','.join(engines)}")
                while len(futures) < args.max_concurrency:
                    claim = api.post("/api/ai/runner/claim")
                    if not claim:
                        break
                    futures.add(executor.submit(
                        execute_claim, api, claim, root, args.sandbox, git_command,
                        engine_commands, cli_environment, args.claude_model,
                        args.git_protocol, github_token_ready))
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
