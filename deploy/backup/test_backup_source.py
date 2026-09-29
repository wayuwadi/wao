import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('source', Path(__file__).with_name('verify-backup-source.py'))
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)


class SourceTest(unittest.TestCase):
    def test_linux2_is_the_only_current_writer(self):
        values = iter(['0\t0\t1', 'N'])
        self.assertEqual(
            source.check(lambda _: next(values), 'linux2'),
            'linux2-four-database-writer',
        )

    def test_other_writer_or_locked_account_is_rejected(self):
        for writer, locked in [('linux1', 'N'), ('linux2', 'Y')]:
            values = iter(['0\t0\t1', locked])
            with self.assertRaises(ValueError):
                source.check(lambda _: next(values), writer)


if __name__ == '__main__':
    unittest.main()
