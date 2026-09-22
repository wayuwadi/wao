import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import io

spec = importlib.util.spec_from_file_location('endpoint', Path(__file__).with_name('refresh-mesh-endpoint.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class EndpointTest(unittest.TestCase):
    def test_public_resolver_precedes_stale_router_cache(self):
        with patch.object(module.urllib.request, 'urlopen', return_value=io.BytesIO(
                b'{"Answer":[{"type":28,"data":"2606:4700::1"}]}')), \
             patch.object(module.socket, 'getaddrinfo') as local:
            self.assertEqual(module.resolve_ipv6('linux1.wayuwa.cn'), ['2606:4700::1'])
            local.assert_not_called()

    def test_resolver_outage_falls_back_only_to_global_local_answer(self):
        with patch.object(module.urllib.request, 'urlopen', side_effect=OSError('offline')), \
             patch.object(module.socket, 'getaddrinfo', return_value=[(None,None,None,None,('fd00::1',0))]):
            with self.assertRaises(ValueError):
                module.resolve_ipv6('linux1.wayuwa.cn')

    def test_keeps_valid_current_address_and_rejects_nonpublic_answers(self):
        good = '240e:36d::1'
        self.assertEqual(module.select_ipv6(['240e:36d::2', good, 'fd00::1'], good), good)
        with self.assertRaises(ValueError):
            module.select_ipv6(['fd00::1', '::1', '198.18.0.1'], good)

    def test_updates_one_peer_without_changing_private_key_or_other_peer(self):
        original = '[Interface]\nPrivateKey = fixture-only\n\n[Peer]\nPublicKey = first\nEndpoint = original:1\n\n[Peer]\nPublicKey = second\nEndpoint = other:2\n'
        changed = module.replace_endpoint(original, 'first', '[240e:36d::1]:3')
        self.assertIn('PrivateKey = fixture-only', changed)
        self.assertIn('Endpoint = [240e:36d::1]:3', changed)
        self.assertIn('Endpoint = other:2', changed)
        with self.assertRaises(ValueError):
            module.replace_endpoint(original, 'missing', 'unused:1')


if __name__ == '__main__':
    unittest.main()
