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
    def test_only_public_and_linux2_targets_are_monitored(self):
        self.assertEqual(set(watch.TARGETS), {'main', 'linux2'})

    def test_transient_failure_does_not_alert_and_recovery_resets_count(self):
        state, _, message = watch.update({}, {'linux2': False}, 100)
        self.assertIsNone(message)
        state, problems, message = watch.update(state, {'linux2': True}, 101)
        self.assertFalse(problems)
        self.assertIsNone(message)
        self.assertEqual(state['failures']['linux2'], 0)

    def test_persistent_failure_deduplicates_and_recovers(self):
        state, _, _ = watch.update({}, {'linux2': False}, 100)
        state, problems, message = watch.update(state, {'linux2': False}, 160)
        self.assertIn('linux2', message)
        state.update(notified=problems, sent=160)
        state, _, message = watch.update(state, {'linux2': False}, 220)
        self.assertIsNone(message)
        _, _, message = watch.update(state, {'linux2': False}, 160 + 4 * 3600)
        self.assertIsNotNone(message)
        for now in range(300, 300 + 60 * (watch.RECOVERY_SUCCESS_CHECKS - 1), 60):
            state, _, message = watch.update(state, {'linux2': True}, now)
            self.assertIsNone(message)
        _, problems, message = watch.update(
            state, {'linux2': True}, 300 + 60 * (watch.RECOVERY_SUCCESS_CHECKS - 1))
        self.assertFalse(problems)
        self.assertIn('恢复', message)

    def test_recent_release_window_silences_targets_until_it_expires(self):
        with tempfile.TemporaryDirectory() as folder:
            marker = Path(folder) / 'deploying'
            marker.touch()
            os.utime(marker, (100, 100))
            self.assertEqual(watch.planned_drains(100 + watch.DEPLOY_WINDOW_SECONDS, Path(folder)),
                             {'main', 'linux2'})
            self.assertEqual(watch.planned_drains(101 + watch.DEPLOY_WINDOW_SECONDS, Path(folder)), set())

    def test_main_flapping_does_not_repeat_incident_notifications(self):
        state = {'notified': ['linux2'], 'sent': 100, 'failures': {'linux2': 2}}
        sent = []
        for i, main in enumerate([False, False, True, False, False, True, False, False]):
            state, problems, message = watch.update(
                state, {'linux2': False, 'main': main}, 160 + 60 * i)
            if message:
                sent.append(message)
                state.update(notified=problems, sent=state['checked'])
        self.assertEqual(len(sent), 1)
        self.assertEqual(state['notified'], ['linux2', 'main'])

    def test_failed_delivery_is_retried_without_claiming_notified(self):
        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder) / 'state.json'
            state.write_text(json.dumps({'failures': {'linux2': 2}}))
            with patch('sys.argv', ['watch', '--state', str(state)]), \
                 patch.object(watch, 'probe', return_value=False), \
                 patch.object(watch, 'notify', side_effect=ValueError('private detail')) as send:
                self.assertEqual(watch.main(), 1)
                self.assertNotIn('sent', json.loads(state.read_text()))
                self.assertEqual(watch.main(), 1)
                self.assertEqual(send.call_count, 2)

    def test_probe_rejects_wrong_contract_and_network_failure(self):
        with patch.object(watch.subprocess, 'run') as run:
            run.return_value = SimpleNamespace(stdout='{"code":0,"data":{"protocol":1}}')
            self.assertTrue(watch.probe(watch.TARGETS['main']))
            run.return_value = SimpleNamespace(stdout='{"code":50300,"data":null}')
            self.assertFalse(watch.probe(watch.TARGETS['main']))
            run.side_effect = OSError('private connection information')
            self.assertFalse(watch.probe(watch.TARGETS['main']))

    def test_linux2_public_probe_uses_public_dns_and_hostname_tls(self):
        with patch.object(watch.subprocess, 'run') as run:
            run.side_effect = [SimpleNamespace(stdout='127.0.0.1\n8.8.8.8\n'),
                               SimpleNamespace(stdout='{"code":0,"data":{"protocol":1}}')]
            self.assertTrue(watch.probe(watch.TARGETS['linux2']))
            command = run.call_args.args[0]
            self.assertIn('linux2.wayuwa.cn:9443:8.8.8.8', command)
            self.assertNotIn('--insecure', command)
            self.assertNotIn('--location', command)


if __name__ == '__main__':
    unittest.main()
