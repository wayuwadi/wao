#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location('health', Path(__file__).with_name('controller-health.py'))
health = importlib.util.module_from_spec(spec)
spec.loader.exec_module(health)


class RecoveryPolicyTest(unittest.TestCase):
    def probe(self, **changes):
        return dict(dict(spawn='ok', quietingDown=False, running=[], queue=[]), **changes)

    def failed(self, **changes):
        return self.probe(spawn='spawn_helper_failed', **changes)

    def test_healthy_never_restarts(self):
        result = health.evaluate(self.probe(), {}, 10000, True)
        self.assertEqual(result['issues'], [])
        self.assertEqual(result['action'], 'none')

    def test_two_recent_failures_required(self):
        first = health.evaluate(self.failed(), {}, 10000, True)
        self.assertEqual(first['action'], 'none')
        self.assertEqual(health.evaluate(self.failed(), first, 10120, True)['action'], 'safe_restart')
        self.assertEqual(health.evaluate(self.failed(), first, 10400, True)['action'], 'none')

    def test_read_only_probe_does_not_recover(self):
        first = health.evaluate(self.failed(), {}, 10000, False)
        self.assertEqual(health.evaluate(self.failed(), first, 10120, False)['action'], 'none')

    def test_busy_or_quieting_controller_never_restarts(self):
        first = health.evaluate(self.failed(), {}, 10000, True)
        for probe in (self.failed(quietingDown=True),
                      self.failed(running=[{'job': 'client', 'number': 1, 'since': 10000000}])):
            self.assertEqual(health.evaluate(probe, first, 10120, True)['action'], 'deferred_busy')

    def test_cooldown_survives_probe_errors(self):
        client = Mock()
        client.probe.side_effect = TimeoutError('private response must not appear')
        result = health.check(client, {'lastRestartAt': 9900}, 10000, True)
        self.assertEqual(result['lastRestartAt'], 9900)
        self.assertEqual(result['issues'], ['probe_unavailable:TimeoutError'])
        self.assertEqual(result['consecutiveSpawnFailures'], 0)
        first = health.evaluate(self.failed(), result, 10120, True)
        self.assertEqual(health.evaluate(self.failed(), first, 10240, True)['action'], 'deferred_cooldown')
        self.assertEqual(health.evaluate(self.failed(), dict(first, checkedAt=12000), 12020, True)['action'], 'safe_restart')

    def test_unrelated_process_failures_do_not_restart(self):
        for issue in ('process_start_failed', 'timeout', 'nonzero'):
            first = health.evaluate(self.probe(spawn=issue), {}, 10000, True)
            self.assertEqual(health.evaluate(self.probe(spawn=issue), first, 10120, True)['action'], 'none')

    def test_queue_and_long_build_are_reported_not_killed(self):
        probe = self.probe(queue=[{'job': 'main', 'since': 0}],
                           running=[{'job': 'client', 'since': 0}])
        result = health.evaluate(probe, {}, 10000, True)
        self.assertEqual(result['issues'], ['queue_over_15m:main', 'build_over_60m:client'])
        self.assertEqual(result['action'], 'none')

    def test_polling_error_is_visible_without_restart(self):
        result = health.evaluate(self.probe(pollingFailures=['github-polling.log']), {}, 10000, True)
        self.assertEqual(result['issues'], ['scm_polling_failed:github-polling.log'])
        self.assertEqual(result['action'], 'none')

    def test_health_resets_consecutive_failure(self):
        first = health.evaluate(self.failed(), {}, 10000, True)
        healthy = health.evaluate(self.probe(), first, 10120, True)
        self.assertEqual(health.evaluate(self.failed(), healthy, 10240, True)['action'], 'none')

    def test_restart_attempt_state_precedes_network(self):
        client = Mock()
        client.probe.return_value = self.failed()
        first = health.evaluate(self.failed(), {}, 10000, True)
        result = health.check(client, first, 10120, True)
        self.assertEqual(result['lastRestartAt'], 10120)
        client.restart.assert_not_called()
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'status.json'
            health.save(path, result)
            self.assertIn('10120', path.read_text())
            self.assertFalse(path.with_suffix('.tmp').exists())


if __name__ == '__main__':
    unittest.main()
