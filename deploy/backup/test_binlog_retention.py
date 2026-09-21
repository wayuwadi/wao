import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('retention', Path(__file__).with_name('binlog-retention.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RetentionTests(unittest.TestCase):
    def test_keeps_the_oldest_retained_snapshot_chain(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            uuid = 'a' * 36
            for i in range(18, 24):
                archive = root / 'binlogs' / uuid / f'mysql-bin.{i:06d}'
                archive.mkdir(parents=True)
                (archive / 'COMPLETE').write_text(archive.name)
            self.assertEqual(module.expired_logs(root), [])
            for day, first in ((20, 20), (21, 22)):
                snapshot = root / f'202609{day}T000000Z'
                snapshot.mkdir()
                (snapshot / 'COMPLETE').write_text(snapshot.name)
                (snapshot / 'recovery-point.json').write_text(json.dumps(dict(source_uuid=uuid, file=f'mysql-bin.{first:06d}', position=123)))
            self.assertEqual(sorted(p.name for p in module.expired_logs(root)), ['mysql-bin.000018', 'mysql-bin.000019'])


if __name__ == '__main__':
    unittest.main()
