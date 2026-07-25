import importlib.util
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
        process.communicate.return_value = ("", None)
        process.returncode = 0
        prompt = "第一行\nhttps://wao.example/task.md\n最后一行"

        exit_code, _ = RUNNER.invoke_codex(["codex.cmd", "exec", "-"], Path("C:/work"), prompt)

        self.assertEqual(0, exit_code)
        process.communicate.assert_called_once_with(input=prompt)
        self.assertEqual(RUNNER.subprocess.PIPE, popen.call_args.kwargs["stdin"])

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


if __name__ == "__main__":
    unittest.main()
