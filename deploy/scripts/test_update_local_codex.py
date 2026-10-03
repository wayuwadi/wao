import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('updater', Path(__file__).with_name('update-local-codex.py'))
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)


class UpdateLocalCodexTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def binary(self, version):
        path = self.root / 'versions' / version / 'codex.exe'
        path.parent.mkdir(parents=True)
        path.write_text(version)
        return path

    def test_switch_and_rollback_preserve_both_versions_and_pause_updates(self):
        first, second = self.binary('1.0.0'), self.binary('1.1.0')
        updater.activate(self.root, first, '1.0.0', {})
        updater.activate(self.root, second, '1.1.0', {})
        updater.rollback(self.root)
        self.assertEqual('1.0.0', updater.read(self.root / 'current.json')['active']['version'])
        self.assertTrue(first.exists() and second.exists())
        with patch.object(updater.urllib.request, 'urlopen') as remote:
            self.assertEqual('paused', updater.update(self.root, Path('npm-cli.js'), True))
            remote.assert_not_called()

    def test_corrupt_previous_never_changes_active_pointer(self):
        first, second = self.binary('1.0.0'), self.binary('1.1.0')
        updater.activate(self.root, first, '1.0.0', {})
        updater.activate(self.root, second, '1.1.0', {})
        old = (self.root / 'current.json').read_bytes()
        first.write_text('corrupt')
        with self.assertRaisesRegex(RuntimeError, 'previous_version_invalid'):
            updater.rollback(self.root)
        self.assertEqual(old, (self.root / 'current.json').read_bytes())

    def test_failed_candidate_keeps_working_version(self):
        first = self.binary('1.0.0')
        updater.activate(self.root, first, '1.0.0', {})
        old = (self.root / 'current.json').read_bytes()
        candidate = self.root / 'versions/1.1.0/node_modules/@openai/codex-win32-x64/vendor/x64/bin/codex.exe'
        candidate.parent.mkdir(parents=True); candidate.write_text('candidate')
        with patch.object(updater.urllib.request, 'urlopen') as remote, \
             patch.object(updater, 'command', return_value='codex-cli 1.1.0'), \
             patch.object(updater, 'probe', side_effect=RuntimeError('image_probe_failed')):
            remote.return_value.__enter__.return_value.read.return_value = json.dumps({'version': '1.1.0'})
            with self.assertRaisesRegex(RuntimeError, 'image_probe_failed'):
                updater.update(self.root, Path('npm-cli.js'), True)
        self.assertEqual(old, (self.root / 'current.json').read_bytes())

    def test_regular_checks_back_off_without_network(self):
        updater.save(self.root / 'status.json', {'checkedAt': updater.now(), 'status': 'failed_previous_preserved'})
        with patch.object(updater.urllib.request, 'urlopen') as remote:
            self.assertEqual('not_due', updater.update(self.root, Path('npm-cli.js')))
            remote.assert_not_called()

    def test_rejects_unmanaged_binary(self):
        outside = self.root / 'outside.exe'; outside.write_text('outside')
        with self.assertRaisesRegex(RuntimeError, 'unmanaged_executable'):
            updater.activate(self.root, outside, '1.0.0', {})
        self.assertFalse((self.root / 'current.json').exists())


if __name__ == '__main__':
    unittest.main()
