import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import io

spec = importlib.util.spec_from_file_location('ddns', Path(__file__).with_name('linux1-ddns.py'))
ddns = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ddns)


class DdnsTests(unittest.TestCase):
    def test_ipv4_uses_physical_interface_and_requires_matching_sources(self):
        with patch.object(ddns.urllib.request, 'urlopen', side_effect=lambda *a, **k: io.BytesIO(
                b'{"Answer":[{"type":1,"data":"8.8.4.4"}]}')), \
             patch.object(ddns.subprocess, 'run') as run:
            run.return_value = SimpleNamespace(returncode=0, stdout='8.8.8.8\n')
            self.assertEqual(ddns.public_ipv4(), '8.8.8.8')
            for call in run.call_args_list:
                command = call.args[0]
                self.assertIn('--interface', command)
                self.assertIn('--resolve', command)
                self.assertNotIn('-k', command)
                self.assertLessEqual(call.kwargs['timeout'], 10)
            run.side_effect = [SimpleNamespace(returncode=0, stdout='8.8.8.8'),
                               SimpleNamespace(returncode=0, stdout='1.1.1.1')]
            with self.assertRaises(RuntimeError):
                ddns.public_ipv4()

    def test_ipv4_outage_does_not_block_ipv6_update(self):
        with patch.object(ddns, 'load_credentials', return_value=('key', 'secret', None)), \
             patch.object(ddns, 'preferred_ipv6', return_value='2606:4700::1'), \
             patch.object(ddns, 'public_ipv4', side_effect=RuntimeError('offline')), \
             patch.object(ddns, 'sync_record', return_value=True) as sync, \
             patch.object(ddns, 'notify_feishu'):
            with self.assertRaises(RuntimeError):
                ddns.main()
            sync.assert_called_once_with('AAAA', '2606:4700::1', 'key', 'secret')

    def test_ipv6_failure_does_not_block_ipv4_update(self):
        with patch.object(ddns, 'load_credentials', return_value=('key', 'secret', None)), \
             patch.object(ddns, 'preferred_ipv6', side_effect=RuntimeError('offline')), \
             patch.object(ddns, 'public_ipv4', return_value='8.8.8.8'), \
             patch.object(ddns, 'sync_record', return_value=False) as sync:
            with self.assertRaises(RuntimeError):
                ddns.main()
            sync.assert_called_once_with('A', '8.8.8.8', 'key', 'secret')

    def test_uses_preferred_physical_address_not_tun_or_temporary(self):
        rows = [{'addr_info': [
            {'local': 'fdfe:dcba::1', 'scope': 'global', 'prefixlen': 128},
            {'local': '2606:4700::3', 'scope': 'global', 'prefixlen': 64, 'temporary': True},
            {'local': '2606:4700::4', 'scope': 'global', 'prefixlen': 128, 'preferred_life_time': 0},
            {'local': '2606:4700::2', 'scope': 'global', 'prefixlen': 64},
            {'local': '2606:4700::1', 'scope': 'global', 'prefixlen': 128}]}]
        self.assertEqual(ddns.select_ipv6(rows), '2606:4700::1')
        with self.assertRaises(RuntimeError):
            ddns.select_ipv6([{'addr_info': rows[0]['addr_info'][:1]}])


if __name__ == '__main__':
    unittest.main()
