import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('pitr', Path(__file__).with_name('prepare-peer-pitr.py'))
pitr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pitr)


class ChainTests(unittest.TestCase):
    def point(self, name='mysql-bin.000020', position=150):
        return dict(source_uuid='a', file=name, position=position)

    def test_multi_file_and_single_file_boundaries(self):
        self.assertEqual(pitr.chain_names(self.point(), self.point('mysql-bin.000022')), ['mysql-bin.000020', 'mysql-bin.000021', 'mysql-bin.000022'])
        self.assertEqual(pitr.chain_names(self.point(), self.point()), ['mysql-bin.000020'])

    def test_rejects_other_source_reverse_positions_and_invalid_names(self):
        invalid = [dict(self.point(), source_uuid='b'), self.point(position=149), self.point('mysql-bin.000019'), self.point('../x.000021'), self.point('other.000021'), self.point(position=0)]
        for target in invalid:
            with self.subTest(target=target), self.assertRaises(ValueError):
                pitr.chain_names(self.point(), target)


if __name__ == '__main__':
    unittest.main()
