import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('source', Path(__file__).with_name('verify-backup-source.py'))
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)


class SourceTest(unittest.TestCase):
    def test_fenced_original_writer_is_not_claimed_current(self):
        values = iter(['0\t0\t1', 'Y'])
        with self.assertRaisesRegex(ValueError, 'fenced'):
            source.check(lambda _: next(values), 'linux2')

    def test_promoted_topology_requires_applied_barrier(self):
        for waited in ('0', '1'):
            def sql(statement, upstream=False):
                if statement.startswith('SELECT @@read_only'):
                    return '0\t0\t1'
                if statement.startswith('SELECT account_locked'):
                    return 'Y'
                if statement.startswith('SHOW REPLICA'):
                    return '\n'.join(['Source_Host: 10.66.0.3', 'Source_UUID: new', 'Replica_IO_Running: Yes',
                        'Replica_SQL_Running: Yes', 'Replicate_Wild_Do_Table: wao.%', 'Seconds_Behind_Source: 0'])
                if upstream:
                    return 'new\tnew:1-3'
                if statement.startswith('SELECT WAIT_FOR'):
                    return waited
                self.fail('unexpected query')
            if waited == '0':
                self.assertIn('verified-wao-replica', source.check(sql, 'linux1'))
            else:
                with self.assertRaisesRegex(ValueError, 'barrier'):
                    source.check(sql, 'linux1')


if __name__ == '__main__':
    unittest.main()
