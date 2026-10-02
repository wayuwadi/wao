import importlib.util
from pathlib import Path
from unittest import TestCase, mock

spec = importlib.util.spec_from_file_location('retired_runner', Path(__file__).with_name('wao-ai-runner.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

class RetirementTest(TestCase):
    def test_no_pairing_or_state_deletion(self):
        with mock.patch.object(runner, 'WaoApi') as api, mock.patch.object(runner, 'forget_pairing') as forget:
            self.assertEqual(runner.main(), runner.EXIT_PAIRING_REVOKED)
            api.assert_not_called()
            forget.assert_not_called()
