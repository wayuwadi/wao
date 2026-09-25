import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import tempfile
import json
import os

spec = importlib.util.spec_from_file_location('watch', Path(__file__).with_name('health-watch.py'))
watch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(watch)


class WatchTests(unittest.TestCase):
    def test_transient_failure_does_not_alert_and_recovery_resets_count(self):
        state, _, message = watch.update({}, {'linux1': False}, 100)
        self.assertIsNone(message)
        state, problems, message = watch.update(state, {'linux1': True}, 101)
        self.assertFalse(problems)
        self.assertIsNone(message)
        self.assertEqual(state['failures']['linux1'], 0)

    def test_persistent_failure_deduplicates_and_recovers(self):
        state, _, _ = watch.update({}, {'linux1': False}, 100)
        state, problems, message = watch.update(state, {'linux1': False}, 160)
        self.assertIn('linux1', message)
        state.update(notified=problems, sent=160)
        state, _, message = watch.update(state, {'linux1': False}, 220)
        self.assertIsNone(message)
        _, _, message = watch.update(state, {'linux1': False}, 160 + 4 * 3600)
        self.assertIsNotNone(message)
        state, _, message = watch.update(state, {'linux1': True}, 300)
        self.assertIsNone(message)
        _, problems, message = watch.update(state, {'linux1': True}, 360)
        self.assertFalse(problems)
        self.assertIn('恢复', message)

    def test_main_flapping_does_not_repeat_incident_notifications(self):
        state = {'notified': ['linux1', 'linux2'], 'sent': 100,
                 'failures': {'linux1': 2, 'linux2': 2}}
        sent = []
        for i, main in enumerate([False, False, True, False, False, True, False, False]):
            state, problems, message = watch.update(
                state, {'linux1': False, 'linux2': False, 'main': main}, 160 + 60 * i)
            if message:
                sent.append(message)
                state.update(notified=problems, sent=state['checked'])
        self.assertEqual(len(sent), 1)
        self.assertEqual(state['notified'], ['linux1', 'linux2', 'main'])

    def test_recent_release_window_silences_every_target_until_it_expires(self):
        with tempfile.TemporaryDirectory() as folder:
            marker = Path(folder) / 'deploying'
            marker.touch()
            os.utime(marker, (100, 100))
            self.assertEqual(watch.planned_drains(100 + watch.DEPLOY_WINDOW_SECONDS, Path(folder)),
                             {'main', 'linux1', 'linux2'})
            self.assertEqual(watch.planned_drains(101 + watch.DEPLOY_WINDOW_SECONDS, Path(folder)), set())
            state = {}
            for now in (160, 220, 280):
                state, problems, message = watch.update(
                    state, {'main': False, 'linux1': False, 'linux2': False}, now,
                    watch.planned_drains(now, Path(folder)))
                self.assertFalse(problems)
                self.assertIsNone(message)

    def test_recent_planned_drain_does_not_alert_but_stale_drain_does(self):
        with tempfile.TemporaryDirectory() as folder:
            marker = Path(folder) / 'slave.down'
            marker.touch()
            os.utime(marker, (100, 100))
            self.assertEqual(watch.planned_drains(120, Path(folder)), {'linux1'})
            state, _, message = watch.update({}, {'main': True, 'linux1': False, 'linux2': True},
                                             120, {'linux1'})
            self.assertIsNone(message)
            self.assertEqual(state['failures']['linux1'], 0)
            self.assertEqual(watch.planned_drains(100 + watch.PLANNED_DRAIN_MAX_SECONDS + 1,
                                                  Path(folder)), set())
            state, _, _ = watch.update(state, {'main': True, 'linux1': False, 'linux2': True},
                                       2000)
            _, problems, message = watch.update(state, {'main': True, 'linux1': False,
                                                        'linux2': True}, 2060)
            self.assertEqual(problems, ['linux1'])
            self.assertIn('linux1', message)

    def test_planned_node_does_not_hide_main_failure_or_send_false_recovery(self):
        state = {'notified': ['linux1'], 'failures': {'linux1': 2}, 'successes': 0}
        results = {'main': False, 'linux1': False, 'linux2': True}
        state, _, _ = watch.update(state, results, 100, {'linux1'})
        _, problems, message = watch.update(state, results, 160, {'linux1'})
        self.assertIn('main', problems)
        self.assertIn('main', message)

    def test_failed_delivery_is_retried_without_claiming_notified(self):
        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder) / 'state.json'
            state.write_text(json.dumps({'failures': {'linux1': 2}}))
            with patch('sys.argv', ['watch', '--state', str(state)]), \
                 patch.object(watch, 'probe', return_value=False), \
                 patch.object(watch, 'notify', side_effect=ValueError('do not log private detail')) as send:
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

    def test_public_probe_resolves_public_address_and_keeps_hostname_tls(self):
        with patch.object(watch.subprocess, 'run') as run:
            run.side_effect = [SimpleNamespace(stdout='127.0.0.1\n8.8.8.8\n'),
                               SimpleNamespace(stdout='{"code":0,"data":{"protocol":1}}')]
            self.assertTrue(watch.probe(watch.TARGETS['linux1']))
            command = run.call_args.args[0]
            self.assertIn('linux1.wayuwa.cn:9443:8.8.8.8', command)
            self.assertNotIn('--insecure', command)
            self.assertNotIn('--location', command)


if __name__ == '__main__':
    unittest.main()
