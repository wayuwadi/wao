"""Run manually on a Linux host with Docker and MySQL 9.7; never use a live DB."""
import gzip
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent
UUID = '11111111-2222-3333-4444-555555555555'


@unittest.skipUnless(shutil.which('docker') and shutil.which('gpg'), 'requires Docker and GPG')
class AuthoritativeRestoreIntegrationTest(unittest.TestCase):
    def test_encrypted_dump_restores_into_isolated_mysql(self):
        base = Path(os.environ.get('WAO_PEER_RESTORE_STAGE_ROOT', '/var/backups/wao-peer/.restore-work'))
        base.mkdir(mode=0o700, parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='wao-snapshot-qa-', dir=base) as temp:
            root = Path(temp)
            snapshot = root / 'snapshot'
            snapshot.mkdir(mode=0o700)
            passphrase = root / 'passphrase'
            passphrase.write_bytes(os.urandom(32).hex().encode())
            passphrase.chmod(0o600)
            gnupg = root / 'gnupg'
            gnupg.mkdir(mode=0o700)
            env = dict(os.environ, GNUPGHOME=str(gnupg),
                       WAO_PEER_RESTORE_STAGE_ROOT=str(root / 'restore-work'))
            dump = root / 'wao.sql.gz'
            sql = f'''-- WAO_SOURCE_UUID={UUID}
-- WAO_SOURCE_TABLES=2
-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='binlog.000001', SOURCE_LOG_POS=123;
-- SET @@GLOBAL.GTID_PURGED='{UUID}:1-2';
DROP DATABASE IF EXISTS `wao`;
CREATE DATABASE `wao`;
USE `wao`;
CREATE TABLE `alpha` (`id` int PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO `alpha` VALUES (1);
CREATE TABLE `beta` (`id` int PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO `beta` VALUES (2);
'''
            dump.write_bytes(gzip.compress(sql.encode()))
            metadata = subprocess.check_output(
                ['python3', str(ROOT / 'inspect-authoritative-wao-snapshot.py'), str(dump)])
            (snapshot / 'recovery-point.json').write_bytes(metadata)
            subprocess.run([
                'gpg', '--batch', '--yes', '--pinentry-mode', 'loopback',
                '--passphrase-file', str(passphrase), '--symmetric', '--cipher-algo', 'AES256',
                '--compress-algo', 'none', '--output', str(snapshot / 'wao.sql.gz.gpg'), str(dump),
            ], env=env, check=True, capture_output=True)
            (snapshot / 'COMPLETE').write_text('wao-only source=linux1 created_utc=20260927T000000Z\n')
            sums = subprocess.check_output([
                'sha256sum', 'wao.sql.gz.gpg', 'recovery-point.json'], cwd=snapshot)
            (snapshot / 'SHA256SUMS').write_bytes(sums)
            result = subprocess.run([
                'bash', str(ROOT / 'verify-authoritative-wao-restore.sh'),
                str(snapshot), str(passphrase),
            ], env=env, capture_output=True, text=True, timeout=360)
            self.assertEqual(result.returncode, 0, result.stderr[-2000:])
            self.assertIn('2 tables imported and checked', result.stdout)


if __name__ == '__main__':
    unittest.main()
