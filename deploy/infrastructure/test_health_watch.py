import importlib.util
from pathlib import Path
from types import SimpleNamespace
import tempfile
import json
import os
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('watch', Path(__file__).with_name('health-watch.py'))
watch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(watch)


class WatchTests(unittest.TestCase):
    def test_only_current_public_entry_is_monitored(self):
        self.assertEqual(set(watch.TARGETS), {'main'})

    def test_transient_failure_and_recovery_record_real_status(self):
        state, problems = watch.update({}, {'main': False}, 100)
        self.assertFalse(problems)
        self.assertEqual(state['healthy'], {'main': False})
        state, problems = watch.update(state, {'main': True}, 101)
        self.assertFalse(problems)
        self.assertEqual(state['failures']['main'], 0)

    def test_persistent_failure_is_recorded_and_recovery_resets_it(self):
        state = {}
        for now in (100, 160, 220, 160 + 4 * 3600):
            state, problems = watch.update(state, {'main': False}, now)
        self.assertEqual(problems, ['main'])
        self.assertEqual(state['failures']['main'], 4)
        self.assertFalse(state['notificationsEnabled'])
        state, problems = watch.update(state, {'main': True}, now + 60)
        self.assertEqual(problems, [])
        self.assertEqual(state['healthy'], {'main': True})
        self.assertEqual(state['successes'], 1)

    def test_recent_release_window_silences_targets_until_it_expires(self):
        with tempfile.TemporaryDirectory() as folder:
            marker = Path(folder) / 'deploying'
            marker.touch()
            os.utime(marker, (100, 100))
            self.assertEqual(watch.planned_drains(100 + watch.DEPLOY_WINDOW_SECONDS, Path(folder)),
                             {'main'})
            self.assertEqual(watch.planned_drains(101 + watch.DEPLOY_WINDOW_SECONDS, Path(folder)), set())

    def test_planned_release_keeps_actual_probe_result_without_failure_count(self):
        state, problems = watch.update({}, {'main': False}, 160, {'main'})
        self.assertEqual(problems, [])
        self.assertEqual(state['failures'], {'main': 0})
        self.assertEqual(state['healthy'], {'main': False})

    def test_main_flapping_records_checks_without_notification_state(self):
        state = {}
        for i, healthy in enumerate([False, False, True, False, False, True, False, False]):
            state, problems = watch.update(state, {'main': healthy}, 160 + 60 * i)
            self.assertFalse(state['notificationsEnabled'])
            self.assertNotIn('notified', state)
            self.assertNotIn('sent', state)
        self.assertEqual(problems, ['main'])

    def test_retired_linux2_state_is_removed_without_sending_a_notification(self):
        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder) / 'state.json'
            state.write_text(json.dumps({'failures': {'linux2': 11474},
                'healthy': {'main': True, 'linux2': False}, 'notified': ['linux2'], 'sent': 1}))
            with patch('sys.argv', ['watch', '--state', str(state)]), \
                 patch.object(watch, 'probe', return_value=True) as probe:
                self.assertEqual(watch.main(), 0)
                probe.assert_called_once_with(watch.TARGETS['main'])
            saved = json.loads(state.read_text())
            self.assertEqual(saved['failures'], {'main': 0})
            self.assertEqual(saved['healthy'], {'main': True})
            self.assertNotIn('notified', saved)
            self.assertNotIn('sent', saved)
            self.assertFalse(saved['notificationsEnabled'])

    def test_failure_and_recovery_with_leftover_webhook_only_call_readiness_probe(self):
        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder) / 'state.json'
            state.write_text(json.dumps({'failures': {'main': 2}, 'notified': ['main'], 'sent': 1}))
            with patch('sys.argv', ['watch', '--state', str(state)]), \
                 patch.dict(os.environ, {'FEISHU_COMMENT_WEBHOOK_URL': 'https://example.invalid/old-webhook'}), \
                 patch.object(watch.subprocess, 'run') as run:
                run.return_value = SimpleNamespace(stdout='{"code":50300,"data":null}')
                self.assertEqual(watch.main(), 1)
                saved = json.loads(state.read_text())
                self.assertEqual(saved['failures'], {'main': 3})
                self.assertEqual(saved['healthy'], {'main': False})
                run.return_value = SimpleNamespace(stdout='{"code":0,"data":{"protocol":1}}')
                self.assertEqual(watch.main(), 0)
                saved = json.loads(state.read_text())
                self.assertEqual(saved['healthy'], {'main': True})
                self.assertFalse(saved['notificationsEnabled'])
                self.assertEqual(run.call_count, 2)
                for call in run.call_args_list:
                    self.assertEqual(call.args[0][-1], watch.TARGETS['main'])
                    self.assertNotIn('--data', call.args[0])

    def test_check_only_does_not_change_recorded_state(self):
        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder) / 'state.json'
            state.write_text('{"checked":1}')
            with patch('sys.argv', ['watch', '--check-only', '--state', str(state)]), \
                 patch.object(watch, 'probe', return_value=False):
                self.assertEqual(watch.main(), 1)
            self.assertEqual(state.read_text(), '{"checked":1}')

    def test_probe_rejects_wrong_contract_and_network_failure(self):
        with patch.object(watch.subprocess, 'run') as run:
            run.return_value = SimpleNamespace(stdout='{"code":0,"data":{"protocol":1}}')
            self.assertTrue(watch.probe(watch.TARGETS['main']))
            run.return_value = SimpleNamespace(stdout='{"code":50300,"data":null}')
            self.assertFalse(watch.probe(watch.TARGETS['main']))
            run.side_effect = OSError('private connection information')
            self.assertFalse(watch.probe(watch.TARGETS['main']))

    def test_public_probe_preserves_tls_without_redirect_or_retired_dns_lookup(self):
        with patch.object(watch.subprocess, 'run') as run:
            run.return_value = SimpleNamespace(stdout='{"code":0,"data":{"protocol":1}}')
            self.assertTrue(watch.probe(watch.TARGETS['main']))
            run.assert_called_once()
            command = run.call_args.args[0]
            self.assertEqual(command[-1], 'https://wao.wayuwa.cn/api/edge/ready')
            self.assertNotIn('--insecure', command)
            self.assertNotIn('--location', command)
            self.assertNotIn('dig', command)


if __name__ == '__main__':
    unittest.main()
