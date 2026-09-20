import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('endpoint', Path(__file__).with_name('refresh-mesh-endpoint.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class EndpointTest(unittest.TestCase):
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
