import importlib.util
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import base64

SPEC = importlib.util.spec_from_file_location('local_dev', Path(__file__).resolve().parents[2] / 'scripts/local-dev.py')
LOCAL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LOCAL)

class IndependentInstallationTest(unittest.TestCase):
    def test_each_install_gets_independent_secrets_and_existing_config_is_preserved(self):
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            contents = []
            for directory in (first, second):
                with patch.object(LOCAL, 'ROOT', Path(directory)):
                    LOCAL.initialize()
                    data = (Path(directory) / '.env').read_text()
                    with self.assertRaises(FileExistsError): LOCAL.initialize()
                    self.assertEqual(data, (Path(directory) / '.env').read_text())
                    contents.append(dict(line.split('=', 1) for line in data.splitlines()))
            for key in ('DB_PASS', 'DB_ROOT_PASS', 'WAO_VAULT_KEY', 'AGENT_CREDENTIAL_KEY'):
                self.assertNotEqual(contents[0][key], contents[1][key])
            self.assertEqual(32, len(base64.b64decode(contents[0]['WAO_VAULT_KEY'])))
            self.assertEqual('127.0.0.1', contents[0]['MYSQL_HOST'])
            self.assertEqual('3306', contents[0]['MYSQL_PORT'])
            self.assertEqual('false', contents[0]['FEISHU_LOGIN_ENABLED'])

    def test_startup_passes_private_configuration_without_putting_it_in_arguments(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(LOCAL, 'ROOT', Path(directory)):
            (Path(directory) / '.env').write_text('DB_PASS=local-private-value\nWAO_VAULT_KEY=local-private-key\n')
            with patch.object(LOCAL.shutil, 'which', return_value='mvn'), patch.object(LOCAL.subprocess, 'call', return_value=0) as call:
                with self.assertRaises(SystemExit) as result: LOCAL.run('server')
                self.assertEqual(0, result.exception.code)
                args, kwargs = call.call_args
                self.assertEqual('local-private-value', kwargs['env']['DB_PASS'])
                self.assertNotIn('local-private-value', str(args))
                self.assertIn('--server.address=127.0.0.1', ' '.join(args[0]))

if __name__ == '__main__': unittest.main()
