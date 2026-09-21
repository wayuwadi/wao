import datetime as dt
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('health', Path(__file__).with_name('check-peer-backup.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class HealthTest(unittest.TestCase):
    def test_incomplete_stale_current_and_disk(self):
        now = dt.datetime(2026, 9, 20, tzinfo=dt.timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertIsNotNone(module.health(root, now))
            snapshot = root / '20260919T000000Z'
            snapshot.mkdir()
            (snapshot / 'COMPLETE').write_text(snapshot.name)
            self.assertIsNotNone(module.health(root, now))
            for name in ('SHA256SUMS', 'recovery.tar.gpg'):
                (snapshot / name).touch()
            with patch.object(module.shutil, 'disk_usage') as disk:
                disk.return_value.free = 20 * 1024**3
                disk.return_value.total = 90 * 1024**3
                self.assertIsNone(module.health(root, now))
                self.assertIsNotNone(module.health(root, now + dt.timedelta(hours=7)))
                disk.return_value.free = 1024
                self.assertIsNotNone(module.health(root, now))

    def test_binlog_marker_cannot_hide_missing_or_stale_payload(self):
        import json
        now = dt.datetime(2026, 9, 20, tzinfo=dt.timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot = root / '20260920T000000Z'
            snapshot.mkdir()
            (snapshot / 'COMPLETE').write_text(snapshot.name)
            for name in ('SHA256SUMS', 'recovery.tar.gpg'):
                (snapshot / name).touch()
            logs = root / 'binlogs'
            logs.mkdir()
            with patch.object(module.shutil, 'disk_usage') as disk:
                disk.return_value.free = 30 * 1024**3
                disk.return_value.total = 90 * 1024**3
                self.assertIn('binlog', module.health(root, now))
                state = dict(source_uuid='a'*36, file='mysql-bin.000001', captured_utc=now.isoformat(), completed_utc=now.isoformat())
                (logs / 'last-success.json').write_text(json.dumps(state))
                self.assertIn('binlog', module.health(root, now))
                payload = logs / state['source_uuid'] / state['file']
                payload.mkdir(parents=True)
                (payload / 'COMPLETE').write_text(state['file'])
                self.assertIsNone(module.health(root, now))
                self.assertIn('binlog', module.health(root, now + dt.timedelta(minutes=11)))


if __name__ == '__main__':
    unittest.main()
