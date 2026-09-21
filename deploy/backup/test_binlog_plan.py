import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('planner', Path(__file__).with_name('plan-peer-binlogs.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PlanTests(unittest.TestCase):
    def test_does_not_rearchive_logs_older_than_oldest_full_and_excludes_active(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot = root / '20260921T000000Z'
            snapshot.mkdir()
            (snapshot / 'COMPLETE').write_text(snapshot.name)
            (snapshot / 'recovery-point.json').write_text(json.dumps(dict(source_uuid='a', file='mysql-bin.000020')))
            rows = [[f'mysql-bin.{n:06d}', '123'] for n in range(18,23)]
            self.assertEqual([r[0] for r in module.plan(root, 'a', rows)], ['mysql-bin.000020','mysql-bin.000021'])

    def test_rejects_expired_unarchived_gap(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'binlogs').mkdir()
            (root/'binlogs/last-success.json').write_text(json.dumps(dict(source_uuid='a', file='mysql-bin.000020')))
            with self.assertRaises(ValueError):
                module.plan(root, 'a', [['mysql-bin.000023','123'],['mysql-bin.000024','100']])


if __name__ == '__main__':
    unittest.main()
