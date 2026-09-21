import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('accounts', Path(__file__).with_name('prepare-mysql-recovery-accounts.py'))
accounts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(accounts)


class AccountSafetyTest(unittest.TestCase):
    def test_partial_grant_failure_restores_super_read_only(self):
        envs = [dict(DB_NAME='wao', DB_USER='wao_app', DB_PASS='test-only'),
                dict(DB_NAME='wao', MYSQL_REPLICATION_USER='wao_repl', MYSQL_REPLICATION_PASSWORD='test-only')]
        commands = []

        def fake_sql(statement):
            commands.append(statement)
            if statement.startswith('SELECT @@read_only'):
                return '1\t1\t2'
            if statement.startswith('SELECT COUNT'):
                return '0'
            if 'CREATE USER' in statement:
                raise RuntimeError('simulated grant failure')
            return ''

        with patch.object(accounts, 'environment', side_effect=envs), patch.object(accounts, 'sql', side_effect=fake_sql):
            with self.assertRaises(RuntimeError):
                accounts.prepare()
        self.assertEqual(commands[-1], 'SET GLOBAL super_read_only=ON;')
        self.assertFalse(any('SET GLOBAL read_only=OFF' in c for c in commands))

    def test_existing_account_is_not_reset(self):
        envs = [dict(DB_NAME='wao', DB_USER='wao_app', DB_PASS='test-only'),
                dict(DB_NAME='wao', MYSQL_REPLICATION_USER='wao_repl', MYSQL_REPLICATION_PASSWORD='test-only')]
        with patch.object(accounts, 'environment', side_effect=envs), patch.object(accounts, 'sql', side_effect=['1\t1\t2', '1']) as sql:
            with self.assertRaisesRegex(RuntimeError, 'already exists'):
                accounts.prepare()
        self.assertEqual(sql.call_count, 2)


if __name__ == '__main__':
    unittest.main()
