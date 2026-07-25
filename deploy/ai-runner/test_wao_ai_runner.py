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

        exit_code, _ = RUNNER.invoke_codex(["codex.cmd", "exec", "-"], Path("C:/work"), prompt)

        self.assertEqual(0, exit_code)
        process.stdin.write.assert_called_once_with(prompt)
        process.stdin.close.assert_called_once()
        process.wait.assert_called_once()
        self.assertEqual(RUNNER.subprocess.PIPE, popen.call_args.kwargs["stdin"])

    @patch.object(RUNNER, "find_github_cli", return_value="C:/Program Files/GitHub CLI/gh.exe")
    @patch.object(RUNNER.subprocess, "run")
    def test_git_credential_is_only_injected_into_child_environment(self, run: Mock, _find: Mock) -> None:
        run.return_value = Mock(returncode=0, stdout="username=wao\npassword=secret-token\n")

        environment, ready, cli = RUNNER.build_codex_environment("git.exe")

        self.assertTrue(ready)
        self.assertEqual("secret-token", environment["GH_TOKEN"])
        self.assertEqual("C:/Program Files/GitHub CLI/gh.exe", cli)
        self.assertTrue(environment["PATH"].startswith("C:\\Program Files\\GitHub CLI" + os.pathsep))
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


if __name__ == "__main__":
    unittest.main()
