import datetime as dt
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('retention', Path(__file__).with_name('peer-retention.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RetentionTest(unittest.TestCase):
    def test_history_partial_snapshots_and_minimum(self):
        now = dt.datetime(2026, 9, 20, tzinfo=dt.timezone.utc)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            for age in range(12):
                snapshot = root / (now - dt.timedelta(days=age)).strftime('%Y%m%dT%H%M%SZ')
                snapshot.mkdir()
                for name in ('recovery.tar.gpg', 'SHA256SUMS'):
                    (snapshot / name).write_text('test')
                (snapshot / 'COMPLETE').write_text(snapshot.name)
            partial = root / '20200101T000000Z'
            partial.mkdir()
            (partial / 'COMPLETE').write_text('wrong marker')
            expired = module.expired_snapshots(root, now)
            self.assertEqual(len(expired), 4)
            self.assertNotIn(partial, expired)
            for path in expired:
                self.assertLess(path.name, '20260913T000000Z')
            # Old snapshots must survive if fewer than seven are complete.
            for snapshot in sorted(root.iterdir(), reverse=True)[:7]:
                (snapshot / 'COMPLETE').unlink()
            self.assertEqual(module.expired_snapshots(root, now), [])


if __name__ == '__main__':
    unittest.main()
