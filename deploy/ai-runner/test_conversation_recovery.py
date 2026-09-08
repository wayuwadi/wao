"""会话持久化必须跨异常与 Runner 重启，不能只依赖最终回包。"""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("conversation_runner", Path(__file__).with_name("wao-ai-runner.py"))
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class ConversationRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.api = Mock()
        self.api.post_file.return_value = {"url": "/api/pages/report"}
        self.api.absolute_url.return_value = "https://wao.example/api/pages/report"
        self.claim = {"runId": "ar-recovery", "engine": "codex", "taskTitle": "续接验收",
                      "repoUrl": "https://example.com/repo", "workspaceDir": "repo",
                      "defaultBranch": "main", "branchName": "ai/recovery", "promptUrl": "https://wao.example/task",
                      "failureRetryCount": 1}
        self.worktree = self.root / ".wao-runs" / self.claim["runId"]
        self.worktree.mkdir(parents=True)

    def test_checkpoint_survives_network_failure_and_retries_binding(self):
        reporter = RUNNER.ProgressReporter(self.api, "ar-recovery")
        reporter.session_path = self.root / "session"
        self.api.post.side_effect = [OSError("offline"), None]
        reporter.event({"type": "thread.started", "thread_id": "thread-1"})
        self.assertEqual("thread-1", reporter.session_path.read_text())
        self.assertFalse(reporter.session_reported)
        reporter.flush()
        self.assertTrue(reporter.session_reported)
        with self.assertRaisesRegex(RuntimeError, "不同"):
            reporter.bind_session("thread-other")
        self.assertEqual("thread-1", reporter.session_path.read_text())

    def test_finished_messages_keep_multiline_content_and_do_not_overwrite_each_other(self):
        reporter = RUNNER.ProgressReporter(self.api, "ar", interval=1000)
        texts = ["计划\n- 第一项\n- 第二项", "进展" * 1500, "后续结论"]
        for text in texts:
            reporter.event({"type": "item.completed", "item": {"type": "agent_message", "text": text}})
        reporter.flush()
        self.assertEqual(texts, [call.args[1]["content"] for call in self.api.post.call_args_list
                                 if call.args[1]["kind"] == "message"])

    def test_restart_without_new_input_resumes_local_checkpoint_and_preserves_worktree(self):
        checkpoint = self.root / ".wao-run-sessions" / "ar-recovery"
        checkpoint.parent.mkdir()
        checkpoint.write_text("thread-kept")
        with patch.object(RUNNER, "prepare_worktree") as prepare, \
                patch.object(RUNNER, "run_codex", return_value=(0, "WAO_RUN_STATUS: SUCCESS\n已验收", "thread-kept", RUNNER._blank_usage())) as codex:
            RUNNER.execute_claim(self.api, self.claim, self.root, "workspace-write", "git", {"codex": "codex"})
        prepare.assert_not_called()
        self.assertEqual("thread-kept", codex.call_args.args[6])
        self.assertIn("中断处继续", codex.call_args.args[2])

    def test_exception_after_thread_start_retries_same_session(self):
        calls = []
        def turn(*args, **kwargs):
            calls.append(args[6])
            if len(calls) == 1:
                args[4].event({"type": "thread.started", "thread_id": "thread-started"})
                raise OSError("stream disconnected")
            return 0, "WAO_RUN_STATUS: SUCCESS\n已验收", "thread-started", RUNNER._blank_usage()
        with patch.object(RUNNER, "prepare_worktree", return_value=self.worktree), \
                patch.object(RUNNER, "run_codex", side_effect=turn):
            RUNNER.execute_claim(self.api, self.claim, self.root, "workspace-write", "git", {"codex": "codex"})
        self.assertEqual([None, "thread-started"], calls)

    def test_bound_worktree_is_not_removed_by_age(self):
        checkpoint = self.root / ".wao-run-sessions" / "ar-recovery"
        checkpoint.parent.mkdir()
        checkpoint.write_text("thread-kept")
        os.utime(self.worktree, (1, 1))
        with patch.object(RUNNER, "drop_worktree") as drop:
            RUNNER.prune_stale_worktrees(self.worktree.parent, "git", set())
        drop.assert_not_called()

    def test_continuation_uses_incremental_context_and_conditional_delivery(self):
        self.claim["projectMemories"] = [{"content": "不应重复注入的旧背景"}]
        prompt = RUNNER.build_continuation_prompt(self.claim, "解释一下上次的设计")
        self.assertNotIn("不应重复注入的旧背景", prompt)
        self.assertIn("纯问答", prompt)
        self.assertIn("不手动重复 Build", prompt)
        self.assertIn("同一代码和环境已通过", prompt)

    def test_no_deployment_marker_is_only_read_from_assistant_messages(self):
        reporter = RUNNER.ProgressReporter(self.api, "ar")
        reporter.event({"type": "item.completed", "item": {"type": "command_execution",
                        "command": "WAO_DELIVERY_STAGE: NOT_REQUIRED"}})
        self.api.post.assert_not_called()
        reporter.event({"type": "item.completed", "item": {"type": "agent_message",
                        "text": "WAO_DELIVERY_STAGE: NOT_REQUIRED"}})
        self.assertIn("delivery_not_required", [call.args[1]["kind"] for call in self.api.post.call_args_list])
