import datetime as dt
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('recovery', Path(__file__).with_name('mysql-guarded-recovery.py'))
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


class PromotionSafetyTest(unittest.TestCase):
    def test_local_bootstrap_gtid_rejects_promotion_before_channel_reset(self):
        commands = []
        def sql(statement):
            commands.append(statement)
            if statement.startswith('SELECT @@server_id'): return '2\t1\t1'
            if statement.startswith('SHOW REPLICA STATUS'):
                return '\n'.join(['Source_UUID: '+'a'*36, 'Replica_IO_Running: Yes',
                    'Replica_SQL_Running: Yes', 'Replicate_Wild_Do_Table: wao.%'])
            if statement.startswith('SELECT account_locked'): return 'N'
            if statement.startswith('SELECT WAIT_FOR'): return '0'
            if statement.startswith('SELECT @@global.gtid_executed'): return 'b'*36+':1-2,'+'a'*36+':1-4'
            if statement.startswith('SELECT GTID_SUBTRACT'): return 'b'*36+':1-2'
            self.fail('Unexpected mutation: '+statement)
        with self.assertRaisesRegex(RuntimeError, 'local GTID history'):
            recovery.promote(sql, self.receipt())
        self.assertFalse(any(c.startswith(('STOP', 'RESET', 'SET PERSIST')) for c in commands))

    def test_rejoin_refuses_missing_or_uncovered_baseline_before_change_source(self):
        for baseline in [None, 'b'*36+':1-2']:
            commands = []
            def sql(statement):
                commands.append(statement)
                if statement == 'SELECT @@server_uuid': return 'a'*36
                if statement.startswith('SELECT account_locked'): return 'Y'
                if statement == 'SHOW REPLICA STATUS': return ''
                if statement.startswith('SELECT GTID_SUBSET'): return '0'
                self.fail('Unexpected mutation: '+statement)
            promoted = dict(kind='wao-promoted', previous_source_uuid='a'*36, baseline_gtid=baseline)
            with self.subTest(baseline=baseline), self.assertRaises(RuntimeError):
                recovery.rejoin(sql, promoted, 'test')
            self.assertFalse(any(c.startswith(('CHANGE', 'START')) for c in commands))

    def test_vertical_status_preserves_empty_values(self):
        parsed = recovery.status(lambda _: ' Source_UUID: a\n Last_SQL_Error: \n Replica_IO_Running: Yes\n')
        self.assertEqual(parsed['Last_SQL_Error'], '')
        self.assertEqual(parsed['Source_UUID'], 'a')

    def receipt(self):
        return dict(kind='wao-account-fence', source_uuid='a'*36, gtid='a'*36+':1-3',
                    captured_utc=dt.datetime.now(dt.timezone.utc).isoformat())

    def test_expired_evidence_never_touches_mysql(self):
        receipt = self.receipt()
        receipt['captured_utc'] = (dt.datetime.now(dt.timezone.utc)-dt.timedelta(minutes=3)).isoformat()
        with self.assertRaisesRegex(RuntimeError, 'expired'):
            recovery.promote(lambda _: self.fail('stale receipt touched mysql'), receipt)

    def test_lag_or_wrong_source_never_promotes(self):
        for wrong_source in [False, True]:
            commands = []

            def sql(statement):
                commands.append(statement)
                if statement.startswith('SELECT @@server_id'):
                    return '2\t1\t1'
                if statement.startswith('SHOW REPLICA STATUS'):
                    return '\n'.join(['Source_UUID: '+('b'*36 if wrong_source else 'a'*36),
                        'Replica_IO_Running: Yes', 'Replica_SQL_Running: Yes', 'Replicate_Wild_Do_Table: wao.%'])
                if statement.startswith('SELECT account_locked'):
                    return 'N'
                if statement.startswith('SELECT WAIT_FOR'):
                    return '1'
                self.fail('unexpected mutating command')

            with self.subTest(wrong_source=wrong_source), self.assertRaises(RuntimeError):
                recovery.promote(sql, self.receipt())
            self.assertFalse(any(c.startswith(('STOP', 'RESET', 'SET PERSIST')) for c in commands))


if __name__ == '__main__':
    unittest.main()
