#!/usr/bin/env python3
"""WAO 本地 AI 执行节点：领取任务，在独立 Git worktree 中调用 Codex 或 Claude Code CLI。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import queue
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import Future, ThreadPoolExecutor
from pathlib import Path
from typing import Any, Callable


STATE_FILE = Path.home() / ".wao" / "ai-runner.json"
LOG_FILE = Path.home() / ".wao" / "ai-runner.log"
# 本脚本自身的路径；后台一键更新时从服务端拉取新版本原地替换并重启。
SCRIPT_PATH = Path(__file__).resolve()
PRINT_LOCK = threading.Lock()
MAX_LOG_BYTES = 2 * 1024 * 1024
REMOTE_LOG_LINES = 200
REMOTE_LOG_CHARS = 30_000
# 运行结束后保留 worktree 供人工复查，超过这个天数才回收，避免磁盘被历史任务占满。
RUN_WORKTREE_TTL_DAYS = 7
RUN_REPORT_DIR = ".wao-run-reports"
RUN_REPORT_FILE = "report.html"
MAX_RUN_REPORT_BYTES = 10 * 1024 * 1024
# 服务端和后台配置允许的单机并发上限。线程池按这个能力上限创建，但线程会按需启动；
# 实际领取数仍由心跳返回的 maxConcurrency 动态控制。
MAX_RUNNER_CONCURRENCY = 8
# 被 WAO 解除接入后的退出码（EX_CONFIG）：不是崩溃，重启也没用，systemd 用
# RestartPreventExitStatus=78 认这个码，避免每十秒重启一次刷屏。
EXIT_PAIRING_REVOKED = 78
ENGINE_LABELS = {"codex": "Codex", "claude": "Claude Code", "qoder": "Qoder"}
# 引擎 id 到本机候选命令名的映射，按顺序探测第一个存在的。Qoder 的命令名与引擎 id
# 不同，且国际版叫 qodercli、国内版叫 qoderclicn，两个名字都要认。
ENGINE_COMMANDS = {"codex": ("codex",), "claude": ("claude",), "qoder": ("qodercli", "qoderclicn")}
# Qoder 的 stream-json 事件与 Claude Code 同构（system/init、assistant、result），
# 进展过滤、结果解析和用量统计直接复用 Claude 的口径。
CLAUDE_STYLE_ENGINES = {"claude", "qoder"}
# 沙箱强度到各 CLI 参数的映射，保证两种工具在同一档位下权限一致。
CLAUDE_PERMISSION = {
    "read-only": ["--permission-mode", "plan"],
    "workspace-write": ["--permission-mode", "acceptEdits"],
    "danger-full-access": ["--dangerously-skip-permissions"],
}
QODER_PERMISSION = {
    # qodercli 1.1.5 的 --permission-mode 没有 plan 档（可选 default/accept_edits/
    # bypass_permissions/dont_ask/auto）；read-only 用 default：非交互模式下无人批准，
    # 写操作一律被拒，效果等同只读。
    "read-only": ["--permission-mode", "default"],
    "workspace-write": ["--permission-mode", "accept_edits"],
    "danger-full-access": ["--dangerously-skip-permissions"],
}


def log(message: str) -> None:
    with PRINT_LOCK:
        line = f"{time.strftime('%Y-%m-%d %H:%M:%S')} {message}"
        # pythonw.exe 没有控制台，也没有 stdout；后台计划任务仍要正常写文件日志。
        if sys.stdout is not None:
            try:
                print(line, flush=True)
            except OSError:
                pass
        LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
        if LOG_FILE.exists() and LOG_FILE.stat().st_size >= MAX_LOG_BYTES:
            LOG_FILE.replace(LOG_FILE.with_suffix(".log.1"))
        with LOG_FILE.open("a", encoding="utf-8") as output:
            output.write(line + "\n")


def remote_log_tail(path: Path | None = None) -> str:
    """读取最近诊断并做二次脱敏；只上传有限尾部，不上传完整本机日志。"""
    source = path or LOG_FILE
    if not source.exists():
        return ""
    lines = source.read_text(encoding="utf-8", errors="replace").splitlines()[-REMOTE_LOG_LINES:]
    content = "\n".join(lines)[-REMOTE_LOG_CHARS:]
    # 防御外部命令异常把常见凭据格式带进日志；节点令牌本身从不参与日志拼接。
    content = re.sub(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+", "Bearer [已脱敏]", content)
    content = re.sub(
        r"(?i)\b(api[_-]?key|token|secret|password|authorization|cookie)(\s*[=:]\s*)[^\s,;]+",
        r"\1\2[已脱敏]",
        content,
    )
    content = re.sub(
        r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,})\b",
        "[已脱敏]",
        content,
    )
    return content


class PairingRevokedError(RuntimeError):
    """本机的节点令牌已经不被 WAO 认可：多半是后台解除了这台电脑的接入。"""


class WaoApi:
    def __init__(self, server: str, token: str | None = None) -> None:
        self.server = server.rstrip("/")
        self.token = token

    def post(self, path: str, body: dict[str, Any] | None = None) -> Any:
        data = json.dumps(body or {}, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.token:
            headers["X-WAO-Runner-Token"] = self.token
        return self._send(urllib.request.Request(self.server + path, data=data, headers=headers, method="POST"))

    def post_file(self, path: str, field_name: str, file_path: Path, content_type: str) -> Any:
        """用标准库上传一个 multipart 文件，避免 Runner 额外依赖 requests。"""
        boundary = "----wao-runner-" + uuid.uuid4().hex
        filename = file_path.name.replace('"', "")
        content = file_path.read_bytes()
        body = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{field_name}"; filename="{filename}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n"
        ).encode("utf-8") + content + f"\r\n--{boundary}--\r\n".encode("ascii")
        headers = {
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Accept": "application/json",
        }
        if self.token:
            headers["X-WAO-Runner-Token"] = self.token
        request = urllib.request.Request(self.server + path, data=body, headers=headers, method="POST")
        return self._send(request)

    def absolute_url(self, value: str) -> str:
        return urllib.parse.urljoin(self.server + "/", value)

    def get(self, path: str) -> Any:
        headers = {"Accept": "application/json"}
        if self.token:
            headers["X-WAO-Runner-Token"] = self.token
        return self._send(urllib.request.Request(self.server + path, headers=headers, method="GET"))

    def _send(self, request: urllib.request.Request) -> Any:
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                envelope = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            if exc.code == 401 and self.token:
                raise PairingRevokedError(f"WAO 不再认可本机令牌: {detail[:300]}") from exc
            raise RuntimeError(f"WAO 返回 HTTP {exc.code}: {detail[:300]}") from exc
        if envelope.get("code") != 0:
            raise RuntimeError(envelope.get("message") or "WAO 请求失败")
        return envelope.get("data")


def load_state() -> dict[str, str]:
    if not STATE_FILE.exists():
        return {}
    return json.loads(STATE_FILE.read_text(encoding="utf-8"))


def forget_pairing() -> None:
    """删掉本机保存的节点令牌，让这台电脑彻底回到未接入状态。"""
    STATE_FILE.unlink(missing_ok=True)


def disable_windows_task(task_name: str | None) -> bool:
    """解除接入后禁用 Windows 守护任务，避免它把退出码 78 反复拉起。"""
    if os.name != "nt" or not task_name or not task_name.strip():
        return False
    try:
        result = subprocess.run(
            ["schtasks.exe", "/Change", "/TN", task_name, "/Disable"],
            capture_output=True, text=True, timeout=15, check=False,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        log(f"WAO 已解除本机接入，但未能自动禁用 Windows 计划任务“{task_name}”：{exc}")
        return False
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "未知错误").strip().replace("\n", " ")
        log(f"WAO 已解除本机接入，但未能自动禁用 Windows 计划任务“{task_name}”：{detail[:300]}")
        return False
    log(f"WAO 已解除本机接入，已禁用 Windows 计划任务“{task_name}”，不会再自动重试")
    return True


def save_state(state: dict[str, str]) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    if os.name != "nt":
        STATE_FILE.chmod(0o600)


def detect_wake_mac() -> str:
    """Return the physical MAC selected by the OS; ignore uuid.getnode() random fallbacks."""
    node = uuid.getnode()
    first_octet = (node >> 40) & 0xFF
    if first_octet & 0x01:
        return ""
    return ":".join(f"{(node >> shift) & 0xFF:02X}" for shift in range(40, -1, -8))


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
            shutil.rmtree(runs_root.parent / ".wao-codex-homes" / entry.name, ignore_errors=True)
            shutil.rmtree(runs_root.parent / RUN_REPORT_DIR / entry.name, ignore_errors=True)
            log(f"已回收 {RUN_WORKTREE_TTL_DAYS} 天前的历史工作区 {entry}")
        except OSError as exc:
            log(f"回收历史工作区 {entry} 失败，忽略：{exc}")


def has_remote_default_branch(project_dir: Path, git_command: str, default_branch: str) -> bool:
    """判断远端默认分支是否已有可检出的提交。

    新建的 Git 仓库可以还没有任何提交；这时 ``origin/main`` 只是一个不存在的引用，不能作为
    ``git worktree add`` 的起点。
    """
    result = run(
        [git_command, "rev-parse", "--verify", "--quiet",
         f"refs/remotes/origin/{default_branch}^{{commit}}"],
        cwd=project_dir,
        check=False,
    )
    return result.returncode == 0


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
    if has_remote_default_branch(project_dir, git_command, claim["defaultBranch"]):
        run(
            [git_command, "worktree", "add", "-b", claim["branchName"], str(worktree),
             f"origin/{claim['defaultBranch']}"],
            cwd=project_dir,
        )
    else:
        # 空仓库没有 origin/<defaultBranch> 可供检出。直接创建孤儿分支，让 AI 在这个独立
        # worktree 写入该仓库的第一版内容；首个提交会自然成为远端默认分支的候选 PR。
        log(f"[{claim['runId']}] 远端 {claim['defaultBranch']} 尚无提交，创建首个任务工作区")
        run(
            [git_command, "worktree", "add", "--orphan", "-b", claim["branchName"], str(worktree)],
            cwd=project_dir,
        )
    return worktree


def find_engine_executable(engine: str, configured: str | None = None) -> str:
    """按引擎的候选命令名逐个探测；显式指定路径时不再探测。"""
    names = ENGINE_COMMANDS[engine]
    if configured:
        return find_executable(names[0], configured)
    for name in names:
        executable = shutil.which(name)
        if executable:
            return executable
    raise RuntimeError(f"找不到 {' / '.join(names)}；可通过命令行参数或环境变量指定完整路径")


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
        environment: dict[str, str] | None = None,
        should_stop: Callable[[], bool] | None = None) -> tuple[int, str]:
    """通过标准输入传递提示，并逐行消费 AI CLI 的 JSONL 事件。"""
    process = subprocess.Popen(
        command, cwd=str(worktree), text=True, encoding="utf-8", errors="replace",
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=environment,
        start_new_session=os.name != "nt",
        creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0) if os.name == "nt" else 0,
    )
    if process.stdin is None or process.stdout is None:
        raise RuntimeError("无法建立 AI CLI 的标准输入输出管道")
    process.stdin.write(prompt)
    process.stdin.close()
    lines: list[str] = []
    output: queue.Queue[str | None] = queue.Queue()

    def read_output() -> None:
        for line in process.stdout:
            output.put(line)
        output.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    stopping = False
    while True:
        try:
            line = output.get(timeout=1)
        except queue.Empty:
            if should_stop and should_stop() and not stopping:
                log("收到暂停请求，正在终止本地 AI CLI")
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], check=False,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                else:
                    os.killpg(process.pid, signal.SIGTERM)
                stopping = True
            continue
        if line is None:
            break
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
    """按 AI 工具选择事件解析方式；所有工具都只回传用户可读内容。"""
    if engine in CLAUDE_STYLE_ENGINES:
        return extract_claude_progress(event, ENGINE_LABELS[engine])
    return extract_codex_progress(event)


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


def extract_claude_progress(event: dict[str, Any], name: str = "Claude Code") -> tuple[str, str] | None:
    """解析 Claude Code 同构的 stream-json 事件；thinking 正文与工具调用细节不外传。"""
    event_type = str(event.get("type") or "")
    if event_type == "system" and event.get("subtype") == "init":
        return "phase", f"{name} 会话已建立，正在分析任务"
    if event_type == "result":
        if event.get("is_error"):
            return "result", f"{name} 本轮执行失败，正在整理错误原因"
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


class NoopProgressReporter:
    """页面问答只回传最终答复，不保存 Codex 的中间分析或工具事件。"""

    def event(self, _event: dict[str, Any]) -> None:
        return

    def flush(self) -> None:
        return


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
    if marker == "WAO_RUN_STATUS: SKIPPED":
        return 0, detail or f"{name} 已确认该功能缺少真实实现条件，按需求不予实现。"
    if marker == "WAO_RUN_STATUS: BLOCKED":
        return 2, f"{name} 未完成任务：{detail or '未说明阻塞原因'}"
    # 退出码 3 专指「CLI 正常结束但没有报告任务状态」；服务端依赖这个约定给出准确提示。
    return 3, (
        f"{name} 已结束会话，但最终答复没有按约定报告任务状态（SUCCESS / SKIPPED / BLOCKED），"
        "系统无法确认任务是否真的完成，先按未完成处理。\n"
        "请根据下面的原始答复判断实际进展：如果工作其实已经完成或只差收尾，"
        "可以在下方补充信息让 AI 在原会话中继续，并要求它最后明确汇报任务状态。\n"
        f"原始答复：\n{result_text or f'{name} 未返回文字输出'}"
    )


def _report_prompt(report_path: Path | None) -> str:
    if report_path is None:
        return ""
    return (
        "\n\n如果本次工作有值得长期查阅或分享的分析、调研、方案、决策依据或验收证据，"
        "请额外生成一份自包含的 UTF-8 单文件 HTML 报告（资源内联或使用公网绝对地址，最大 10MB），"
        f"写到 `{report_path}`。这是运行产物路径，不要把它加入 Git 提交。"
        "Runner 会在会话结束后自动持久化并关联到 AI Run 结果。"
        "普通小改动没有额外报告价值时不要为了凑数生成占位报告。"
    )


def build_prompt(claim: dict[str, Any], report_path: Path | None = None) -> str:
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
        "如果某项功能没有真实数据源、官方接口或可靠实现条件，继续实现只会产生伪造数据或演示逻辑，"
        "不要伪造，也不要把它留成占位功能：直接不实现或删除该功能，并在结果摘要中说明跳过原因。"
        "只要其余有效改动已经完成、部署和验收，这种主动放弃不可真实实现的非必要功能不算失败。\n\n"
        "最终答复必须以状态行开头：全部完成并验收通过时第一行写 `WAO_RUN_STATUS: SUCCESS`；"
        "因缺少真实实现条件而取消功能、且其余工作已完成时第一行写 `WAO_RUN_STATUS: SKIPPED`；"
        "只有阻塞导致用户需要的有效结果没有交付，或确实需要用户补充必要信息时，第一行才写 "
        "`WAO_RUN_STATUS: BLOCKED`。状态行后再写中文结果摘要。"
        "注意：你一旦停止回复，本次会话就立即结束，不存在「稍后再汇报」；"
        "等待部署这类长任务必须持续轮询直到有结论，然后在同一次答复里给出状态行。"
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
    return prompt + _report_prompt(report_path)


def build_continuation_prompt(
        claim: dict[str, Any], continuation: str, report_path: Path | None = None) -> str:
    """
    续跑的提示词。

    上一次可能失败（补充信息接着修），也可能成功但不符合预期（基于这一版继续优化）。
    两种情况都不该从零重来：会话和 worktree 都还在，AI 记得自己改过什么。但成功那次
    已经提交合并过了，所以必须重申一遍「改完还要提交、合并、盯部署」，否则 AI 很容易
    以为只要在本地改好就算完成。
    """
    base = claim["defaultBranch"]
    return (
        "用户对这次运行给出了新的输入，请在当前任务、已有工作区和你自己的记忆基础上继续，"
        "不要把已经做对的部分推倒重来：\n\n"
        f"{continuation}\n\n"
        f"仍然留在分支 `{claim['branchName']}` 上工作；需要对比基线时用 "
        f"`git fetch origin {base}` 之后读 `origin/{base}`。"
        "如果上一次的改动已经合并进 main，这次就在它的基础上继续改。\n\n"
        "改完同样要跑与风险相称的测试、写详细中文提交、创建 PR 并合并 main，"
        "然后用 gh 盯住这次部署直到成功，再验证线上结果。\n\n"
        "最终答复仍以状态行开头：完成并验收通过写 `WAO_RUN_STATUS: SUCCESS`；"
        "因缺少真实实现条件而取消功能、其余已完成写 `WAO_RUN_STATUS: SKIPPED`；"
        "确实阻塞才写 `WAO_RUN_STATUS: BLOCKED`。"
        "你一旦停止回复会话就立即结束，不存在「稍后再汇报」；"
        "等待部署这类长任务要持续轮询到有结论，再在同一次答复里给出状态行。"
    ) + _report_prompt(report_path)


def publish_run_report(api: WaoApi, run_id: str, report_path: Path) -> str | None:
    """校验并上传可选报告；返回可直接分享的绝对地址。"""
    if not report_path.is_file():
        return None
    size = report_path.stat().st_size
    if size <= 0:
        raise RuntimeError("AI 生成的持久化报告是空文件")
    if size > MAX_RUN_REPORT_BYTES:
        raise RuntimeError("AI 生成的持久化报告超过 10MB")
    try:
        report_path.read_text(encoding="utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise RuntimeError("AI 生成的持久化报告不是 UTF-8 编码") from exc
    result = api.post_file(
        f"/api/ai/runner/runs/{run_id}/report", "html", report_path, "text/html; charset=UTF-8")
    url = _readable_text((result or {}).get("url")).strip()
    if not url:
        raise RuntimeError("WAO 已接收持久化报告，但没有返回公开地址")
    return api.absolute_url(url)


def prepare_codex_home(codex_home: Path, account: dict[str, Any]) -> dict[str, str]:
    """为一次运行准备隔离的 Codex 登录/供应商配置，避免并发任务覆盖全局账号。"""
    # 这里只返回本账号需要覆盖的变量。调用方可能已经注入 GH_TOKEN、gh 所在 PATH 等
    # 运行级环境；若复制整个进程环境再 update，会把这些更精确的值悄悄覆盖掉。
    environment: dict[str, str] = {}
    codex_home.mkdir(parents=True, exist_ok=True)
    if os.name != "nt":
        codex_home.chmod(0o700)
    auth_method = str(account.get("authMethod") or "")
    if auth_method == "api_key":
        api_key = str(account.get("apiKey") or "").strip()
        base_url = str(account.get("baseUrl") or "").strip().rstrip("/")
        model = str(account.get("model") or "").strip()
        if not api_key or not base_url or not model:
            raise RuntimeError("自定义 API 缺少 API Key、Base URL 或模型")
        environment["WAO_CODEX_API_KEY"] = api_key
        config = (
            f"model = {json.dumps(model, ensure_ascii=False)}\n"
            'model_provider = "wao_custom"\n\n'
            '[model_providers.wao_custom]\n'
            f"name = {json.dumps(str(account.get('label') or 'WAO 自定义 API'), ensure_ascii=False)}\n"
            f"base_url = {json.dumps(base_url, ensure_ascii=False)}\n"
            'env_key = "WAO_CODEX_API_KEY"\n'
            'wire_api = "responses"\n'
        )
        config_path = codex_home / "config.toml"
        config_path.write_text(config, encoding="utf-8")
        if os.name != "nt":
            config_path.chmod(0o600)
    else:
        credentials = str(account.get("credentials") or "").strip()
        if not credentials:
            raise RuntimeError("选择的 Codex 账号没有可用凭据")
        auth = json.loads(credentials)
        auth_path = codex_home / "auth.json"
        auth_path.write_text(json.dumps(auth, ensure_ascii=False), encoding="utf-8")
        if os.name != "nt":
            auth_path.chmod(0o600)
    environment["CODEX_HOME"] = str(codex_home)
    return environment


def run_codex(executable: str, worktree: Path, prompt: str, sandbox: str,
        reporter: "ProgressReporter",
        environment: dict[str, str] | None = None,
        session_id: str | None = None,
        should_stop: Callable[[], bool] | None = None,
        account: dict[str, Any] | None = None,
        codex_home: Path | None = None) -> tuple[int, str, str | None, dict[str, Any]]:
    """跑一次 `codex exec --json`，返回退出码、最终答复、会话 id 和用量。"""
    result_file = worktree / ".wao-ai-result.txt"
    command = [
        executable, "exec", *( ["resume", session_id] if session_id else []), "--json", "--color", "never", "--sandbox", sandbox,
        "-c", 'approval_policy="never"', "-C", str(worktree),
        "--output-last-message", str(result_file), "-",
    ]
    run_environment = dict(environment or os.environ)
    if account:
        if codex_home is None:
            raise RuntimeError("选择集中账号时缺少隔离的 CODEX_HOME")
        run_environment.update(prepare_codex_home(codex_home, account))
    exit_code, output = invoke_cli(command, worktree, prompt, reporter.event, run_environment, should_stop)
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
        session_id: str | None = None,
        should_stop: Callable[[], bool] | None = None) -> tuple[int, str, str | None, dict[str, Any]]:
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
    exit_code, output = invoke_cli(command, worktree, prompt, reporter.event, environment, should_stop)
    reporter.flush()
    return _claude_style_outcome(exit_code, output, "claude")


def run_qoder(executable: str, worktree: Path, prompt: str, sandbox: str,
        reporter: "ProgressReporter", environment: dict[str, str] | None = None,
        repo_dir: Path | None = None,
        session_id: str | None = None,
        should_stop: Callable[[], bool] | None = None) -> tuple[int, str, str | None, dict[str, Any]]:
    """跑一次 `qodercli -p --output-format stream-json`，返回退出码、最终答复、会话 id 和用量。"""
    command = [
        executable, "-p", "--output-format", "stream-json",
        *QODER_PERMISSION.get(sandbox, QODER_PERMISSION["workspace-write"]),
    ]
    # qodercli 只信任启动目录；worktree 的 .git 指向主仓库目录，必须用 --add-dir 一并信任，
    # 否则受限档位下 git 操作会退回询问。
    if repo_dir:
        command += ["--add-dir", str(repo_dir)]
    if session_id:
        command += ["-r", session_id]
    exit_code, output = invoke_cli(command, worktree, prompt, reporter.event, environment, should_stop)
    reporter.flush()
    return _claude_style_outcome(exit_code, output, "qoder")


def _claude_style_outcome(exit_code: int, output: str,
        engine: str) -> tuple[int, str, str | None, dict[str, Any]]:
    """从 Claude 同构的 stream-json 输出里取最终答复、会话 id 和用量。"""
    session_id, result_text = None, ""
    for event in iter_events(output):
        session_id = event.get("session_id") or session_id
        if event.get("type") == "result":
            result_text = _readable_text(event.get("result")) or result_text
            if event.get("is_error") and exit_code == 0:
                exit_code = 1
    return exit_code, result_text or output[-12000:], session_id, collect_usage(output, engine)


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
    让一次已经跑完的任务因为统计问题变成失败。Qoder 按 Credits 配额计费，result
    事件里没有按次用量字段时同样回落成空账。
    """
    try:
        events = list(iter_events(output))
        return collect_claude_usage(events) if engine in CLAUDE_STYLE_ENGINES else collect_codex_usage(events)
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
    report_path = root / RUN_REPORT_DIR / run_id / RUN_REPORT_FILE
    stage = "准备 Git 工作区"
    reporter = ProgressReporter(api, run_id, engine)
    last_cancel_check = 0.0

    def cancellation_requested() -> bool:
        nonlocal last_cancel_check
        now = time.monotonic()
        if now - last_cancel_check < 3:
            return False
        last_cancel_check = now
        try:
            return bool((api.get(f"/api/ai/runner/runs/{run_id}/control") or {}).get("cancelled"))
        except Exception as exc:  # noqa: BLE001 - 短暂网络错误不应中断正常执行
            log(f"[{run_id}] 暂停状态检查失败：{exc}")
            return False
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
            report_path.parent.mkdir(parents=True, exist_ok=True)
            prompt = build_continuation_prompt(claim, continuation, report_path)
        else:
            worktree = prepare_worktree(
                claim, root, git_command, resolve_repo_url(claim["repoUrl"], git_protocol, https_ready))
            report_path.parent.mkdir(parents=True, exist_ok=True)
            prompt = build_prompt(claim, report_path)
        stage = f"启动 {name}"
        reporter.send("phase", f"工作区已准备完成，正在启动 {name}", force=True)
        stage = f"等待 {name} 完成"
        if engine == "claude":
            exit_code, result_text, session_id, usage = run_claude(
                executable, worktree, prompt, sandbox, reporter, cli_environment,
                claude_model, root / claim["workspaceDir"], session_id, cancellation_requested)
        elif engine == "qoder":
            exit_code, result_text, session_id, usage = run_qoder(
                executable, worktree, prompt, sandbox, reporter, cli_environment,
                root / claim["workspaceDir"], session_id, cancellation_requested)
        else:
            exit_code, result_text, session_id, usage = run_codex(
                executable, worktree, prompt, sandbox, reporter, cli_environment, session_id,
                cancellation_requested, claim.get("account"), root / ".wao-codex-homes" / run_id)
        cost_text = "未回传" if usage["costUsd"] is None else "$%.4f" % usage["costUsd"]
        log(f"[{run_id}] 用量：模型 {usage['model'] or '未知'}，"
            f"token {usage['totalTokens']}（输入 {usage['inputTokens']} / 输出 "
            f"{usage['outputTokens']} / 缓存 {usage['cachedInputTokens']}），折算额度 {cost_text}")
        exit_code, result_text = normalize_result(exit_code, result_text, engine)
        try:
            report_url = publish_run_report(api, run_id, report_path)
            if report_url:
                reporter.send("message", "持久化报告已发布，可在运行结果中随时查看和分享", force=True)
                result_text = f"{result_text}\n\n持久化报告：{report_url}".strip()
                shutil.rmtree(report_path.parent, ignore_errors=True)
        except Exception as exc:  # noqa: BLE001 - 报告既然已生成，就不能静默丢失
            report_error = f"持久化报告上传失败：{exc}"
            log(f"[{run_id}] {report_error}")
            result_text = f"{result_text}\n\n{report_error}".strip()
            if exit_code == 0:
                exit_code = 2
        if exit_code == 0:
            closing = f"{name} 已执行完成，正在整理结果"
        elif exit_code == 3:
            closing = f"{name} 已结束会话但未报告任务状态，需要人工确认结果"
        else:
            closing = f"{name} 执行未完成，正在整理失败原因"
        reporter.send("result", closing, force=True)
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


def execute_assistant_claim(api: WaoApi, claim: dict[str, Any], root: Path, executable: str,
        git_command: str, cli_environment: dict[str, str] | None = None) -> None:
    """在临时空仓库和只读沙箱中执行页面问答，结束后不保留用户上下文。"""
    run_id = claim["runId"]
    worktree = root / ".wao-assistant" / run_id
    codex_home = root / ".wao-assistant-codex-homes" / run_id
    exit_code = -1
    response = ""
    error = ""
    try:
        worktree.mkdir(parents=True, exist_ok=True)
        run([git_command, "init", "-q"], worktree)
        log(f"[{run_id}] 开始页面只读问答")
        exit_code, response, _, _ = run_codex(
            executable, worktree, str(claim.get("prompt") or ""), "read-only",
            NoopProgressReporter(), cli_environment, account=claim.get("account"), codex_home=codex_home)
        response = response.strip()
        if exit_code != 0:
            error = f"Codex 退出码 {exit_code}：{response or '没有返回错误详情'}"
        elif not response:
            exit_code = 1
            error = "Codex 没有返回文字答复"
    except Exception as exc:  # noqa: BLE001 - 必须把节点错误回传给页面
        error = str(exc)
        log(f"[{run_id}] 页面问答失败：{exc}")
    finally:
        try:
            api.post(
                f"/api/ai/runner/assistant/{run_id}/complete",
                {"exitCode": exit_code, "response": response[-30000:], "error": error[-2000:]},
            )
        except Exception as exc:  # noqa: BLE001
            log(f"[{run_id}] 页面问答结果回传失败：{exc}")
        shutil.rmtree(worktree, ignore_errors=True)
        shutil.rmtree(codex_home, ignore_errors=True)


def script_hash(path: Path) -> str:
    """计算脚本文件字节的 SHA-256，与服务端对打包资源的口径一致。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def apply_script_update(api: WaoApi, script_path: Path) -> None:
    """从服务端拉取最新脚本，哈希校验通过后原地替换自身并重启进程。

    校验失败时抛异常、不动本地文件，主循环会在下次心跳重试；
    成功时直接重启，不会返回。
    """
    data = api.get("/api/ai/runner/script") or {}
    content = data.get("content") or ""
    expected = data.get("hash") or ""
    body = content.encode("utf-8")
    if not content or hashlib.sha256(body).hexdigest() != expected:
        raise RuntimeError("下载的 Runner 脚本哈希校验失败，本次不更新")
    if script_path.exists() and script_hash(script_path) == expected:
        log(f"Runner 脚本已是最新版本 {expected[:12]}，跳过重复重启")
        return
    # 先写临时文件再原子替换，避免写到一半断电把脚本写坏。
    temp = script_path.with_name(script_path.name + ".new")
    temp.write_bytes(body)
    temp.replace(script_path)
    log(f"Runner 脚本已更新到 {expected[:12]}，正在重启进程")
    restart_self(script_path)


def restart_self(script_path: Path) -> None:
    """用同样的启动参数重启自身；配对令牌存在 STATE_FILE 里，重启后自动恢复身份。"""
    arguments = [sys.executable, str(script_path)] + sys.argv[1:]
    if os.name == "nt":
        # Windows 的 execv 不会给参数加引号，带空格的 --name 会被拆散；
        # 改为拉起新进程后退出，新进程独立于当前控制台存活。
        subprocess.Popen(arguments, close_fds=True,
                         creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0))
        os._exit(0)
    os.execv(sys.executable, arguments)


def _ai_credentials_path(provider: str, config_root: Path) -> Path | None:
    """返回本机 CLI 凭据文件路径；未知供应商可通过环境变量覆盖。"""
    known = {
        "claude": config_root / ".claude" / ".credentials.json",
        "codex": config_root / ".codex" / "auth.json",
    }
    path = known.get(provider)
    if path:
        return path
    env = os.getenv(f"WAO_AI_CREDENTIALS_PATH_{provider.upper()}")
    return Path(env) if env else None


def sync_ai_credentials(api: WaoApi, config_root: Path) -> dict[str, bool]:
    """从服务端拉取启用的 AI 账号凭据并写入本机 CLI 配置文件。

    只有明确开启凭据同步的 Runner 才会执行；写入前会检查文件路径是否已知，
    避免把凭据写到错误位置。非 Unix 系统跳过权限设置。
    """
    results: dict[str, bool] = {}
    try:
        accounts = api.get("/api/ai/runner/accounts") or []
    except Exception as exc:  # noqa: BLE001 - 凭据同步失败不能中断主循环
        log(f"AI 账号凭据拉取失败，跳过同步：{exc}")
        return results
    if not isinstance(accounts, list):
        log("AI 账号凭据接口返回格式异常，跳过同步")
        return results
    for item in accounts:
        if not isinstance(item, dict):
            continue
        provider = str(item.get("provider") or "").lower()
        credentials = item.get("credentials")
        if not provider or not isinstance(credentials, str) or not credentials.strip():
            continue
        path = _ai_credentials_path(provider, config_root)
        if not path:
            log(f"AI 账号同步：未知 {provider} 的本地凭据路径，跳过（可设 WAO_AI_CREDENTIALS_PATH_{provider.upper()}）")
            results[provider] = False
            continue
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(credentials, encoding="utf-8")
            if os.name != "nt":
                path.chmod(0o600)
            log(f"AI 账号同步：已更新 {provider} 凭据 {path}")
            results[provider] = True
        except OSError as exc:
            log(f"AI 账号同步：写入 {provider} 凭据失败：{exc}")
            results[provider] = False
    return results


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="WAO AI Runner")
    parser.add_argument("--server", default=os.getenv("WAO_SERVER_URL", "https://wao.wayuwa.cn"))
    parser.add_argument("--name", default=os.getenv("COMPUTERNAME") or platform.node() or "Codex 电脑")
    parser.add_argument("--root", default=os.getenv("WAO_WORKSPACE_ROOT", str(Path.home() / "CodeSpace")))
    parser.add_argument("--pair-code", default=os.getenv("WAO_PAIR_CODE"))
    parser.add_argument("--git-command", default=os.getenv("WAO_GIT_COMMAND"))
    parser.add_argument("--codex-command", default=os.getenv("WAO_CODEX_COMMAND"))
    parser.add_argument("--claude-command", default=os.getenv("WAO_CLAUDE_COMMAND"))
    parser.add_argument("--qoder-command", default=os.getenv("WAO_QODER_COMMAND"))
    parser.add_argument("--windows-task-name", default=os.getenv("WAO_WINDOWS_TASK_NAME"),
                        help="Windows 守护计划任务名称；解除接入后会自动禁用该任务")
    parser.add_argument("--claude-model", default=os.getenv("WAO_CLAUDE_MODEL"),
                        help="传给 Claude Code 的模型别名；留空使用本机默认模型")
    parser.add_argument(
        "--git-protocol",
        choices=["auto", "https", "ssh"],
        default=os.getenv("WAO_GIT_PROTOCOL", "auto"),
        help="克隆项目仓库使用的协议；auto 表示本机没有 HTTPS 凭据时自动改用 SSH",
    )
    parser.add_argument("--engines", default=os.getenv("WAO_RUNNER_ENGINES"),
                        help="本机可用的 AI 工具，逗号分隔（codex,claude,qoder）；留空按已安装的 CLI 自动探测")
    parser.add_argument("--extra-engines", default=os.getenv("WAO_RUNNER_EXTRA_ENGINES"),
                        help="在 --engines 之外追加能力；供主节点部署在不改原 service 的情况下接入 Codex")
    parser.add_argument("--max-concurrency", type=int, default=int(os.getenv("WAO_RUNNER_CONCURRENCY", "2")))
    parser.add_argument(
        "--sandbox",
        choices=["read-only", "workspace-write", "danger-full-access"],
        default=os.getenv("WAO_CODEX_SANDBOX", "danger-full-access"),
        help="开发阶段默认完整权限；建议在专用系统账号或隔离电脑运行",
    )
    parser.add_argument(
        "--sync-ai-credentials",
        action="store_true",
        default=os.getenv("WAO_SYNC_AI_CREDENTIALS", "").lower() in ("1", "true", "yes"),
        help="从 WAO 服务端拉取启用的 AI 账号凭据并写入本机 CLI 配置文件",
    )
    return parser.parse_args()


def resolve_engines(args: argparse.Namespace) -> dict[str, str]:
    """解析本机可用的 AI 工具；显式指定时找不到命令直接报错，自动探测时静默跳过。"""
    configured = {"codex": args.codex_command, "claude": args.claude_command, "qoder": args.qoder_command}
    wanted = [name.strip().lower() for name in (args.engines or "").split(",") if name.strip()]
    wanted += [name.strip().lower() for name in (args.extra_engines or "").split(",") if name.strip()]
    wanted = list(dict.fromkeys(wanted))
    for name in wanted:
        if name not in ENGINE_LABELS:
            raise RuntimeError(f"不支持的 AI 工具：{name}（可选 {'、'.join(ENGINE_LABELS)}）")
    commands: dict[str, str] = {}
    for name in wanted or list(ENGINE_LABELS):
        try:
            commands[name] = find_engine_executable(name, configured[name])
        except RuntimeError:
            if wanted:
                raise
    if not commands:
        raise RuntimeError("本机没有找到 codex、claude 或 qodercli/qoderclicn 命令，请先安装并登录至少一种 AI CLI")
    return commands


def resolve_runtime_concurrency(status: Any, current: int) -> int:
    """读取服务端心跳返回的实际并发数；旧服务端或异常响应沿用当前值。"""
    if not isinstance(status, dict) or status.get("maxConcurrency") is None:
        return current
    try:
        configured = int(status["maxConcurrency"])
    except (TypeError, ValueError):
        return current
    return max(1, min(MAX_RUNNER_CONCURRENCY, configured))


def main() -> int:
    args = parse_args()
    args.max_concurrency = max(1, min(MAX_RUNNER_CONCURRENCY, args.max_concurrency))
    runtime_concurrency = args.max_concurrency
    git_command = find_executable("git", args.git_command)
    engine_commands = resolve_engines(args)
    engines = list(engine_commands)
    wake_mac = detect_wake_mac()
    state = load_state()
    if state.get("server") != args.server.rstrip("/") or not state.get("token"):
        state = pair(WaoApi(args.server), args, engines)
    api = WaoApi(args.server, state["token"])
    root = Path(args.root).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    cli_environment, github_token_ready, github_cli = build_cli_environment(git_command)
    log(f"WAO AI Runner 已启动：工作区 {root}，启动并发 {args.max_concurrency}，"
        f"后台配置将在心跳后动态生效，日志 {LOG_FILE}")
    log("运行环境：Git=" + git_command
        + "".join(f"，{ENGINE_LABELS[name]}={path}" for name, path in engine_commands.items()))
    log(f"GitHub 自动授权：凭据={'可用' if github_token_ready else '不可用'}，"
        f"GitHub CLI={github_cli or '未找到'}，"
        f"克隆协议={args.git_protocol}"
        f"{'（无 HTTPS 凭据，改用 SSH）' if args.git_protocol == 'auto' and not github_token_ready else ''}")
    if args.sync_ai_credentials:
        log("AI 账号凭据同步已启用")
        sync_ai_credentials(api, Path.home())
    futures: set[Future[None]] = set()
    last_credentials_sync = time.monotonic()
    last_reported_log_hash: str | None = None
    CREDENTIALS_SYNC_INTERVAL_SECONDS = 300
    with ThreadPoolExecutor(max_workers=MAX_RUNNER_CONCURRENCY, thread_name_prefix="wao-ai") as executor:
        while True:
            futures = {future for future in futures if not future.done()}
            try:
                heartbeat_query = urllib.parse.urlencode({
                    "maxConcurrency": args.max_concurrency,
                    "engines": ",".join(engines),
                    "scriptHash": script_hash(SCRIPT_PATH),
                    "wakeMac": wake_mac,
                })
                log_tail = remote_log_tail()
                log_hash = hashlib.sha256(log_tail.encode("utf-8")).hexdigest()
                heartbeat_body = {"logTail": log_tail} if log_hash != last_reported_log_hash else {}
                status = api.post(f"/api/ai/runner/heartbeat?{heartbeat_query}", heartbeat_body)
                last_reported_log_hash = log_hash
                next_concurrency = resolve_runtime_concurrency(status, runtime_concurrency)
                if next_concurrency != runtime_concurrency:
                    log(f"后台并发配置已生效：{runtime_concurrency} → {next_concurrency}")
                    runtime_concurrency = next_concurrency
                if isinstance(status, dict) and status.get("updateRequested"):
                    # 后台下发了脚本更新：不再领新任务，等在跑的任务收尾后原地升级重启。
                    if futures:
                        log("已收到 Runner 脚本更新指令，等正在执行的任务结束后自动更新重启")
                    else:
                        apply_script_update(api, SCRIPT_PATH)
                else:
                    if args.sync_ai_credentials and time.monotonic() - last_credentials_sync >= CREDENTIALS_SYNC_INTERVAL_SECONDS:
                        sync_ai_credentials(api, Path.home())
                        last_credentials_sync = time.monotonic()
                    while len(futures) < runtime_concurrency:
                        claim = api.post("/api/ai/runner/claim")
                        if not claim and "codex" in engine_commands:
                            assistant_claim = api.post("/api/ai/runner/assistant/claim")
                            if assistant_claim:
                                futures.add(executor.submit(
                                    execute_assistant_claim, api, assistant_claim, root,
                                    engine_commands["codex"], git_command, cli_environment))
                                continue
                        if not claim:
                            break
                        futures.add(executor.submit(
                            execute_claim, api, claim, root, args.sandbox, git_command,
                            engine_commands, cli_environment, args.claude_model,
                            args.git_protocol, github_token_ready))
            except PairingRevokedError as exc:
                # WAO 后台解除了这台电脑的接入：令牌已经作废，重试没有意义，
                # 清掉本地令牌后退出，需要重新接入时用新的配对码启动即可。
                log(f"WAO 已解除本机的接入（{exc}）；已删除本地令牌 {STATE_FILE}，Runner 退出。"
                    "如需重新接入，请在 AI 控制台生成新的配对码后带 --pair-code 启动一次")
                forget_pairing()
                disable_windows_task(args.windows_task_name)
                return EXIT_PAIRING_REVOKED
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
