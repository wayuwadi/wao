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

    def test_skipped_unsupported_feature_is_not_reported_as_failure(self) -> None:
        code, summary = RUNNER.normalize_result(
            0,
            "WAO_RUN_STATUS: SKIPPED\n已完成并上线有效改动；额度功能缺少真实接口，按需求不予实现。",
        )

        self.assertEqual(0, code)
        self.assertIn("按需求不予实现", summary)

    def test_prompt_distinguishes_unsupported_feature_from_real_blocker(self) -> None:
        prompt = RUNNER.build_prompt({
            "defaultBranch": "main",
            "promptUrl": "https://wao.example/task.md",
            "branchName": "fix/example",
        })

        self.assertIn("WAO_RUN_STATUS: SKIPPED", prompt)
        self.assertIn("不要伪造", prompt)
        self.assertIn("不算失败", prompt)

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

        exit_code, result_text, session_id, usage = RUNNER.run_claude(
            "claude", Path("/work"), "提示", "danger-full-access", reporter)

        self.assertEqual(0, exit_code)
        self.assertEqual("sess-1", session_id)
        self.assertEqual((0, "已合并 PR"), RUNNER.normalize_result(exit_code, result_text, "claude"))
        self.assertIn("--dangerously-skip-permissions", invoke.call_args.args[0])
        self.assertIn("stream-json", invoke.call_args.args[0])

    @patch.object(RUNNER, "invoke_cli")
    def test_claude_error_result_is_a_failure_even_with_exit_code_zero(self, invoke: Mock) -> None:
        invoke.return_value = (0, '{"type":"result","is_error":true,"result":"上下文超限"}')

        exit_code, result_text, _, _ = RUNNER.run_claude(
            "claude", Path("/work"), "提示", "workspace-write", Mock())

        self.assertEqual(1, exit_code)
        code, summary = RUNNER.normalize_result(exit_code, result_text, "claude")
        self.assertEqual(1, code)
        self.assertIn("Claude Code 退出码：1", summary)


class QoderEngineTest(unittest.TestCase):
    """Qoder 的 stream-json 与 Claude Code 同构，重点验命令行参数和标签口径。"""

    def test_progress_uses_qoder_label(self) -> None:
        init = RUNNER.extract_progress({"type": "system", "subtype": "init"}, "qoder")
        text = RUNNER.extract_progress({
            "type": "assistant",
            "message": {"content": [{"type": "text", "text": "已定位问题"}]},
        }, "qoder")

        self.assertEqual(("phase", "Qoder 会话已建立，正在分析任务"), init)
        self.assertEqual(("message", "已定位问题"), text)

    @patch.object(RUNNER, "invoke_cli")
    def test_qoder_run_reads_result_event_and_session(self, invoke: Mock) -> None:
        invoke.return_value = (0, "\n".join([
            '{"type":"system","subtype":"init","session_id":"qs-1"}',
            '{"type":"result","subtype":"success","is_error":false,'
            '"result":"WAO_RUN_STATUS: SUCCESS\\n已合并 PR","session_id":"qs-1"}',
        ]))
        reporter = Mock()

        exit_code, result_text, session_id, usage = RUNNER.run_qoder(
            "qodercli", Path("/work"), "提示", "danger-full-access", reporter)

        self.assertEqual(0, exit_code)
        self.assertEqual("qs-1", session_id)
        self.assertEqual((0, "已合并 PR"), RUNNER.normalize_result(exit_code, result_text, "qoder"))
        self.assertIn("-p", invoke.call_args.args[0])
        self.assertIn("stream-json", invoke.call_args.args[0])
        self.assertIn("--dangerously-skip-permissions", invoke.call_args.args[0])

    @patch.object(RUNNER, "invoke_cli")
    def test_qoder_restricted_sandbox_and_resume_arguments(self, invoke: Mock) -> None:
        invoke.return_value = (0, '{"type":"result","is_error":false,"result":"ok"}')

        RUNNER.run_qoder("qodercli", Path("/work"), "提示", "workspace-write", Mock(),
                         repo_dir=Path("/repo"), session_id="qs-9")

        command = invoke.call_args.args[0]
        self.assertIn("accept_edits", command)
        self.assertIn("--add-dir", command)
        self.assertIn("qs-9", command[command.index("-r") + 1])

    def test_qoder_result_without_usage_still_keeps_the_model(self) -> None:
        output = "\n".join([
            '{"type":"system","subtype":"init","session_id":"qs-1","model":"qoder-pro"}',
            '{"type":"result","subtype":"success","is_error":false,"result":"完成"}',
        ])

        usage = RUNNER.collect_usage(output, "qoder")

        self.assertEqual("qoder-pro", usage["model"])
        self.assertEqual(0, usage["totalTokens"], "Qoder 没回传按次用量时只记模型不记 token")
        self.assertIsNone(usage["costUsd"], "Credits 计费拿不到按次金额，不能编一个 0 出来")

    @patch.object(RUNNER, "invoke_cli")
    def test_qoder_read_only_falls_back_to_default_mode(self, invoke: Mock) -> None:
        """qodercli 1.1.5 没有 plan 档，read-only 必须映射成 default 而不是直接报参数错。"""
        invoke.return_value = (0, '{"type":"result","is_error":false,"result":"ok"}')

        RUNNER.run_qoder("qodercli", Path("/work"), "提示", "read-only", Mock())

        command = invoke.call_args.args[0]
        self.assertIn("default", command)
        self.assertNotIn("plan", command)

    @patch.object(RUNNER.shutil, "which")
    def test_qoder_cn_command_name_is_probed_as_fallback(self, which: Mock) -> None:
        """国内版命令名是 qoderclicn，自动探测必须把它也认出来。"""
        which.side_effect = lambda name: r"C:\qoder\qoderclicn.exe" if name == "qoderclicn" else None

        self.assertEqual(r"C:\qoder\qoderclicn.exe", RUNNER.find_engine_executable("qoder"))
        with self.assertRaises(RuntimeError):
            RUNNER.find_engine_executable("codex")


class UsageAccountingTest(unittest.TestCase):
    """用量统计；Claude 的样例取自真实 `claude -p --output-format stream-json` 输出。"""

    CLAUDE_OUTPUT = "\n".join([
        '{"type":"system","subtype":"init","session_id":"s1","model":"claude-opus-5"}',
        '{"type":"assistant","message":{"content":[{"type":"text","text":"收到"}]}}',
        '{"type":"result","subtype":"success","is_error":false,"session_id":"s1",'
        '"total_cost_usd":0.08705349999999999,'
        '"usage":{"input_tokens":2,"cache_creation_input_tokens":8142,'
        '"cache_read_input_tokens":11047,"output_tokens":4},'
        '"modelUsage":{"claude-opus-5":{"inputTokens":2,"outputTokens":4,'
        '"cacheReadInputTokens":11047,"cacheCreationInputTokens":8142,"costUSD":0.087}},'
        '"result":"WAO_RUN_STATUS: SUCCESS\\n已合并"}',
    ])

    def test_claude_usage_is_read_from_the_result_event(self) -> None:
        usage = RUNNER.collect_usage(self.CLAUDE_OUTPUT, "claude")

        self.assertEqual("claude-opus-5", usage["model"])
        self.assertEqual(2, usage["inputTokens"])
        self.assertEqual(4, usage["outputTokens"])
        self.assertEqual(8142 + 11047, usage["cachedInputTokens"])
        self.assertEqual(2 + 4 + 8142 + 11047, usage["totalTokens"])
        self.assertAlmostEqual(0.087053, usage["costUsd"], places=6)

    def test_claude_multi_model_run_keeps_every_model_and_picks_the_busiest(self) -> None:
        output = ('{"type":"result","total_cost_usd":0.5,'
                  '"usage":{"input_tokens":10,"output_tokens":20},'
                  '"modelUsage":{"claude-haiku-4-5-20251001":{"outputTokens":5},'
                  '"claude-opus-5":{"outputTokens":15}}}')

        usage = RUNNER.collect_usage(output, "claude")

        self.assertEqual("claude-opus-5", usage["model"])
        self.assertEqual("claude-haiku-4-5-20251001,claude-opus-5", usage["models"])

    def test_codex_usage_accumulates_turns_and_reports_no_cost(self) -> None:
        output = "\n".join([
            '{"type":"thread.started","thread_id":"t1","model":"gpt-5-codex"}',
            '{"type":"turn.completed","usage":{"input_tokens":100,'
            '"cached_input_tokens":900,"output_tokens":50}}',
            '{"type":"turn.completed","usage":{"input_tokens":10,'
            '"cached_input_tokens":5,"output_tokens":7}}',
        ])

        usage = RUNNER.collect_usage(output, "codex")

        self.assertEqual(110, usage["inputTokens"])
        self.assertEqual(57, usage["outputTokens"])
        self.assertEqual(905, usage["cachedInputTokens"])
        self.assertEqual(1072, usage["totalTokens"])
        self.assertIsNone(usage["costUsd"], "Codex 不回传花费，不能编一个 0 出来")

    def test_unparseable_output_falls_back_to_empty_usage(self) -> None:
        usage = RUNNER.collect_usage("这不是 JSON\n{坏掉的行", "claude")

        self.assertEqual(0, usage["totalTokens"])
        self.assertIsNone(usage["model"])
        self.assertIsNone(usage["costUsd"])

    def test_dirty_token_fields_are_ignored_instead_of_crashing(self) -> None:
        output = ('{"type":"result","usage":{"input_tokens":"很多",'
                  '"output_tokens":true,"cache_read_input_tokens":null}}')

        usage = RUNNER.collect_usage(output, "claude")

        self.assertEqual(0, usage["totalTokens"])


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
