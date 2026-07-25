import importlib.util
import os
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


if __name__ == "__main__":
    unittest.main()
