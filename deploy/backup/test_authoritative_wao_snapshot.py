import gzip
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent
WRITER = ROOT / 'export-authoritative-wao-on-writer.sh'
INSPECT = ROOT / 'inspect-authoritative-wao-snapshot.py'
UUID = '11111111-2222-3333-4444-555555555555'


class AuthoritativeSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        (self.path / 'writer.env').write_text('WAO_MYSQL_PRIMARY=linux1\nDB_ROOT_PASS=fixture\n')
        docker = self.path / 'docker'
        docker.write_text('''#!/bin/sh
case " $* " in
  *" mysqldump "*)
    printf '%s\\n' "-- MySQL dump" \
      "-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='binlog.000001', SOURCE_LOG_POS=123;" \
      "-- SET @@GLOBAL.GTID_PURGED='11111111-2222-3333-4444-555555555555:1-2';" \
      'DROP DATABASE IF EXISTS `wao`;' \
      'CREATE DATABASE `wao`;'
    ;;
  *) printf '%s\\n' "${MOCK_UUID:-11111111-2222-3333-4444-555555555555} ${MOCK_READ_ONLY:-0} 0" ;;
esac
''')
        docker.chmod(0o755)

    def run_writer(self, **extra):
        env = os.environ.copy()
        env.update(PATH=f'{self.path}:{env["PATH"]}',
                   WAO_WRITER_ENV_FILE=str(self.path / 'writer.env'),
                   WAO_SNAPSHOT_RATE_BPS='65536')
        env.update(extra)
        return subprocess.run(['bash', str(WRITER)], env=env, capture_output=True)

    def test_writer_dump_has_verified_source_and_coordinates(self):
        result = self.run_writer()
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        archive = self.path / 'wao.sql.gz'
        archive.write_bytes(result.stdout)
        self.assertTrue(gzip.decompress(result.stdout).startswith(b'-- WAO_SOURCE_UUID='))
        inspected = subprocess.run(['python3', str(INSPECT), str(archive)],
                                   capture_output=True, text=True, check=True)
        self.assertEqual(json.loads(inspected.stdout)['source_uuid'], UUID)
        self.assertEqual(json.loads(inspected.stdout)['source_log_position'], 123)

    def test_wrong_role_and_read_only_source_fail_closed(self):
        (self.path / 'writer.env').write_text('WAO_MYSQL_PRIMARY=linux2\nDB_ROOT_PASS=fixture\n')
        self.assertNotEqual(self.run_writer().returncode, 0)
        (self.path / 'writer.env').write_text('WAO_MYSQL_PRIMARY=linux1\nDB_ROOT_PASS=fixture\n')
        self.assertNotEqual(self.run_writer(MOCK_READ_ONLY='1').returncode, 0)

    def test_missing_recovery_metadata_is_rejected(self):
        archive = self.path / 'invalid.gz'
        archive.write_bytes(gzip.compress(b'CREATE DATABASE `wao`;'))
        result = subprocess.run(['python3', str(INSPECT), str(archive)], capture_output=True)
        self.assertNotEqual(result.returncode, 0)


if __name__ == '__main__':
    unittest.main()
