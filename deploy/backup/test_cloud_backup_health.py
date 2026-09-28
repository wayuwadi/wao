import datetime as dt
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    'cloud_backup_health', Path(__file__).with_name('check-cloud-backup.py'))
health = importlib.util.module_from_spec(spec)
spec.loader.exec_module(health)


class CloudBackupHealthTests(unittest.TestCase):
    def test_completed_snapshot_and_binlog_are_current(self):
        now = dt.datetime(2026, 9, 27, 15, 0, tzinfo=dt.timezone.utc)
        stamp = '20260927T142143Z'
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            point = root / stamp
            point.mkdir()
            (root / 'last-success').write_text(stamp + '\n')
            (point / 'COMPLETE').write_text(stamp + '\n')
            (point / 'recovery.tar.gpg').write_bytes(b'encrypted')
            (point / 'SHA256SUMS').write_text('hash')
            uuid = '12345678-1234-1234-1234-123456789abc'
            archived = root / 'binlogs' / uuid / 'mysql-bin.000780'
            archived.mkdir(parents=True)
            (archived / 'COMPLETE').write_text('mysql-bin.000780')
            (root / 'binlogs/last-success.json').write_text(json.dumps({
                'captured_utc': '2026-09-27T14:45:00Z',
                'source_uuid': uuid, 'file': 'mysql-bin.000780'}))
            with patch.object(health.shutil, 'disk_usage',
                              return_value=type('Disk', (), {'total': 100 << 30, 'free': 80 << 30})()):
                self.assertEqual(health.check_local(root, now), (stamp, []))
                stale = now + dt.timedelta(hours=2)
                self.assertIn('binlog 异地归档超过 75 分钟',
                              health.check_local(root, stale)[1])


if __name__ == '__main__':
    unittest.main()
