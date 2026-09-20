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
                self.assertIsNone(module.health(root, now))
                self.assertIsNotNone(module.health(root, now + dt.timedelta(hours=7)))
                disk.return_value.free = 1024
                self.assertIsNotNone(module.health(root, now))


if __name__ == '__main__':
    unittest.main()
