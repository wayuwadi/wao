import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('hook', Path(__file__).with_name('node-dns-acme-hook.py'))
hook = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hook)


class HookTest(unittest.TestCase):
    def test_domain_allowlist(self):
        with patch.dict(os.environ, CERTBOT_DOMAIN='other.wayuwa.cn', CERTBOT_VALIDATION='test'):
            with self.assertRaises(ValueError):
                hook.main()

    def test_cleanup_never_removes_modified_record(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.dict(os.environ, CERTBOT_DOMAIN='linux2.wayuwa.cn', CERTBOT_VALIDATION='test'), \
                 patch.object(hook.Path, 'expanduser', return_value=root), \
                 patch.object(hook, 'api', return_value={'RecordId': 'owned-record'}) as api, \
                 patch.object(hook.time, 'sleep'), patch.object(hook.sys, 'argv', ['hook', 'auth']):
                hook.main()
                api.assert_called_once()
                api.reset_mock()
                api.return_value = {'RR': '_acme-challenge.linux2', 'Type': 'TXT', 'Value': 'changed'}
                with patch.object(hook.sys, 'argv', ['hook', 'cleanup']):
                    with self.assertRaises(ValueError):
                        hook.main()
                self.assertEqual(api.call_args.args[0], 'DescribeDomainRecordInfo')
                self.assertEqual(api.call_count, 1)
                api.reset_mock()
                api.return_value = {'RR': '_acme-challenge.linux2', 'Type': 'TXT', 'Value': 'test'}
                with patch.object(hook.sys, 'argv', ['hook', 'cleanup']):
                    hook.main()
                self.assertEqual(api.call_args.args[0], 'DeleteDomainRecord')
                self.assertEqual(list(root.glob('*.json')), [])


if __name__ == '__main__':
    unittest.main()
