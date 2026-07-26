import importlib.util
import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


RUNNER_FILE = Path(__file__).with_name("wao-ai-runner.py")
SPEC = importlib.util.spec_from_file_location("wao_ai_runner", RUNNER_FILE)
assert SPEC and SPEC.loader
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class RunnerInvocationTest(unittest.TestCase):

    @patch.object(RUNNER.subprocess, "Popen")
    def test_prompt_is_sent_through_stdin(self, popen: Mock) -> None:
        process = popen.return_value
        process.stdin = Mock()
        process.stdout = iter([])
        process.returncode = 0
        prompt = "第一行\nhttps://wao.example/task.md\n最后一行"

        exit_code, _ = RUNNER.invoke_cli(["codex.cmd", "exec", "-"], Path("C:/work"), prompt)

        self.assertEqual(0, exit_code)
        process.stdin.write.assert_called_once_with(prompt)
        process.stdin.close.assert_called_once()
        process.wait.assert_called_once()
        self.assertEqual(RUNNER.subprocess.PIPE, popen.call_args.kwargs["stdin"])

    @patch.object(RUNNER, "find_github_cli", return_value="C:/Program Files/GitHub CLI/gh.exe")
    @patch.object(RUNNER.subprocess, "run")
    def test_git_credential_is_only_injected_into_child_environment(self, run: Mock, _find: Mock) -> None:
        run.return_value = Mock(returncode=0, stdout="username=wao\npassword=secret-token\n")

        environment, ready, cli = RUNNER.build_cli_environment("git.exe")

        self.assertTrue(ready)
        self.assertEqual("secret-token", environment["GH_TOKEN"])
        self.assertEqual("C:/Program Files/GitHub CLI/gh.exe", cli)
        # 断言不写死分隔符：Linux 主节点也要能跑这套用例。
        cli_dir = str(Path("C:/Program Files/GitHub CLI/gh.exe").parent)
        self.assertTrue(environment["PATH"].startswith(cli_dir + os.pathsep))
        run.assert_called_once()

    def test_success_requires_explicit_completion_marker(self) -> None:
        code, summary = RUNNER.normalize_result(0, "请把任务链接发来")

        self.assertEqual(3, code)
        self.assertIn("没有确认任务完成", summary)

    def test_blocked_marker_is_reported_as_failure(self) -> None:
        code, summary = RUNNER.normalize_result(0, "WAO_RUN_STATUS: BLOCKED\n缺少部署权限")

        self.assertEqual(2, code)
        self.assertIn("缺少部署权限", summary)

    def test_success_marker_returns_clean_summary(self) -> None:
        code, summary = RUNNER.normalize_result(0, "WAO_RUN_STATUS: SUCCESS\n已提交并部署")

        self.assertEqual(0, code)
        self.assertEqual("已提交并部署", summary)

    def test_extracts_agent_message_but_filters_command(self) -> None:
        message = RUNNER.extract_progress({
            "type": "item.completed",
            "item": {"type": "agent_message", "text": "已定位登录失败原因，正在修复回调校验"},
        })
        command = RUNNER.extract_progress({
            "type": "item.completed",
            "item": {"type": "command_execution", "command": "git status"},
        })

        self.assertEqual(("message", "已定位登录失败原因，正在修复回调校验"), message)
        self.assertIsNone(command)

    def test_reasoning_only_uses_public_summary(self) -> None:
        without_summary = RUNNER.extract_progress({
            "type": "item.completed", "item": {"type": "reasoning", "text": "内部推理"},
        })
        with_summary = RUNNER.extract_progress({
            "type": "item.completed", "item": {"type": "reasoning", "summary": "已确认问题边界"},
        })

        self.assertIsNone(without_summary)
        self.assertEqual(("message", "已确认问题边界"), with_summary)


class RepoUrlTest(unittest.TestCase):

    HTTPS = "https://github.com/wayuwacn/wao.git"

    def test_auto_keeps_https_when_machine_has_https_credential(self) -> None:
        self.assertEqual(self.HTTPS, RUNNER.resolve_repo_url(self.HTTPS, "auto", True))

    def test_auto_falls_back_to_ssh_when_machine_only_has_ssh_key(self) -> None:
        self.assertEqual(
            "git@github.com:wayuwacn/wao.git", RUNNER.resolve_repo_url(self.HTTPS, "auto", False))

    def test_explicit_protocol_wins_over_detection(self) -> None:
        self.assertEqual(
            "git@github.com:wayuwacn/wao.git", RUNNER.resolve_repo_url(self.HTTPS, "ssh", True))
        self.assertEqual(self.HTTPS, RUNNER.resolve_repo_url(self.HTTPS, "https", False))

    def test_non_https_urls_are_left_alone(self) -> None:
        for url in ("git@github.com:wayuwacn/wao.git", "ssh://git@github.com/wayuwacn/wao.git"):
            self.assertEqual(url, RUNNER.resolve_repo_url(url, "ssh", False))


class ClaudeEngineTest(unittest.TestCase):

    def test_assistant_text_is_reported_but_thinking_is_not(self) -> None:
        text = RUNNER.extract_progress({
            "type": "assistant",
            "message": {"content": [{"type": "text", "text": "已读取任务，开始实现接入方案"}]},
        }, "claude")
        thinking = RUNNER.extract_progress({
            "type": "assistant",
            "message": {"content": [{"type": "thinking", "thinking": "内部推理"}]},
        }, "claude")

        self.assertEqual(("message", "已读取任务，开始实现接入方案"), text)
        self.assertIsNone(thinking)

    def test_tool_use_only_exposes_todo_plan(self) -> None:
        plan = RUNNER.extract_progress({
            "type": "assistant",
            "message": {"content": [{
                "type": "tool_use", "name": "TodoWrite",
                "input": {"todos": [
                    {"content": "实现后端字段", "status": "completed"},
                    {"content": "改前端控制台", "status": "in_progress"},
                ]},
            }]},
        }, "claude")
        bash = RUNNER.extract_progress({
            "type": "assistant",
            "message": {"content": [{"type": "tool_use", "name": "Bash", "input": {"command": "git push"}}]},
        }, "claude")

        self.assertEqual("plan", plan[0])
        self.assertIn("1. 实现后端字段（已完成）", plan[1])
        self.assertIn("2. 改前端控制台（进行中）", plan[1])
        self.assertIsNone(bash)

    @patch.object(RUNNER, "invoke_cli")
    def test_claude_run_reads_result_event_and_session(self, invoke: Mock) -> None:
        invoke.return_value = (0, "\n".join([
            '{"type":"system","subtype":"init","session_id":"sess-1"}',
            '{"type":"result","subtype":"success","is_error":false,'
            '"result":"WAO_RUN_STATUS: SUCCESS\\n已合并 PR","session_id":"sess-1"}',
        ]))
        reporter = Mock()

        exit_code, result_text, session_id = RUNNER.run_claude(
            "claude", Path("/work"), "提示", "danger-full-access", reporter)

        self.assertEqual(0, exit_code)
        self.assertEqual("sess-1", session_id)
        self.assertEqual((0, "已合并 PR"), RUNNER.normalize_result(exit_code, result_text, "claude"))
        self.assertIn("--dangerously-skip-permissions", invoke.call_args.args[0])
        self.assertIn("stream-json", invoke.call_args.args[0])

    @patch.object(RUNNER, "invoke_cli")
    def test_claude_error_result_is_a_failure_even_with_exit_code_zero(self, invoke: Mock) -> None:
        invoke.return_value = (0, '{"type":"result","is_error":true,"result":"上下文超限"}')

        exit_code, result_text, _ = RUNNER.run_claude(
            "claude", Path("/work"), "提示", "workspace-write", Mock())

        self.assertEqual(1, exit_code)
        code, summary = RUNNER.normalize_result(exit_code, result_text, "claude")
        self.assertEqual(1, code)
        self.assertIn("Claude Code 退出码：1", summary)


GIT = shutil.which("git")


@unittest.skipUnless(GIT, "本机没有 git，跳过工作区隔离用例")
class WorkspaceIsolationTest(unittest.TestCase):
    """用真实 Git 仓库验证工作区隔离；这里的回归就是「main 被另一个 worktree 占用」。"""

    def setUp(self) -> None:
        self.root = Path(tempfile.mkdtemp(prefix="wao-runner-test-"))
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.origin = self.root / "origin.git"
        seed = self.root / "seed"
        self._git("init", "--initial-branch=main", str(seed), cwd=self.root)
        (seed / "README.md").write_text("seed", encoding="utf-8")
        self._git("add", ".", cwd=seed)
        self._git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "seed", cwd=seed)
        self._git("clone", "--bare", str(seed), str(self.origin), cwd=self.root)
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()
        self.claim = {
            "runId": "ar-current", "workspaceDir": "wao", "branchName": "ai/wao-ar-current",
            "defaultBranch": "main", "promptUrl": "https://wao.example/api/share/tasks/t1.md",
        }

    def _git(self, *args: str, cwd: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run([GIT, *args], cwd=str(cwd), text=True, check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def _prepare(self) -> Path:
        return RUNNER.prepare_worktree(self.claim, self.workspace, GIT, str(self.origin))

    def test_default_branch_stays_free_so_worktree_can_check_it_out(self) -> None:
        worktree = self._prepare()

        # 这一步在修复前会失败：fatal: 'main' is already checked out at '<主克隆>'
        self._git("switch", "main", cwd=worktree)

        self.assertEqual("main", self._git("branch", "--show-current", cwd=worktree).stdout.strip())

    def test_primary_clone_holds_no_branch_and_no_working_copy(self) -> None:
        self._prepare()
        primary = self.workspace / "wao"

        head = subprocess.run([GIT, "symbolic-ref", "--quiet", "HEAD"], cwd=str(primary),
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

        self.assertNotEqual(0, head.returncode, "主克隆必须是游离 HEAD")
        self.assertFalse((primary / "README.md").exists(), "主克隆不需要工作副本")

    def test_existing_clone_on_default_branch_is_repaired_in_place(self) -> None:
        primary = self.workspace / "wao"
        # 模拟旧版本 Runner 留下的、检出在 main 上的完整克隆。
        self._git("clone", str(self.origin), str(primary), cwd=self.workspace)
        self.assertEqual("main", self._git("branch", "--show-current", cwd=primary).stdout.strip())

        worktree = self._prepare()

        self.assertEqual("", self._git("branch", "--show-current", cwd=primary).stdout.strip())
        self._git("switch", "main", cwd=worktree)

    def test_stale_worktrees_are_recycled_but_recent_and_current_are_kept(self) -> None:
        self._prepare()
        runs_root = self.workspace / ".wao-runs"
        primary = self.workspace / "wao"
        for run_id in ("ar-old", "ar-fresh"):
            self._git("worktree", "add", "-b", f"ai/wao-{run_id}", str(runs_root / run_id),
                      "origin/main", cwd=primary)
        aged = time.time() - (RUNNER.RUN_WORKTREE_TTL_DAYS + 1) * 86400
        os.utime(runs_root / "ar-old", (aged, aged))
        os.utime(runs_root / "ar-current", (aged, aged))

        RUNNER.prune_stale_worktrees(runs_root, GIT, {"ar-current"})

        self.assertFalse((runs_root / "ar-old").exists(), "超期工作区应被回收")
        self.assertTrue((runs_root / "ar-fresh").exists(), "保留期内的工作区不能删")
        self.assertTrue((runs_root / "ar-current").exists(), "本次运行的工作区不能删")

    def test_owning_repo_is_resolved_from_worktree_pointer(self) -> None:
        worktree = self._prepare()

        self.assertEqual((self.workspace / "wao").resolve(),
                         RUNNER.owning_repo(worktree).resolve())


class PromptTest(unittest.TestCase):

    PROMPT = RUNNER.build_prompt({
        "promptUrl": "https://wao.example/api/share/tasks/t1.md",
        "branchName": "ai/wao-ar1", "defaultBranch": "main",
    })

    def test_prompt_tells_ai_the_branch_is_already_based_on_latest_main(self) -> None:
        self.assertIn("ai/wao-ar1", self.PROMPT)
        self.assertIn("origin/main", self.PROMPT)
        self.assertIn("https://wao.example/api/share/tasks/t1.md", self.PROMPT)

    def test_prompt_forbids_checking_out_the_default_branch(self) -> None:
        self.assertIn("不要 checkout / switch 到 `main`", self.PROMPT)
        self.assertIn("不要新建其它 worktree", self.PROMPT)


if __name__ == "__main__":
    unittest.main()
