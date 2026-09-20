#!/usr/bin/env python3
"""Failure injection: interrupted snapshots must restart the original Nacos."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class RecoveryFailureTest(unittest.TestCase):
    def run_failure(self, mode):
        with tempfile.TemporaryDirectory(prefix='nacos-test-') as d:
            root = Path(d)
            commands = root / 'bin'
            commands.mkdir()
            shutil.copy(Path(__file__).with_name('create-nacos-recovery.sh'), root)
            (root / 'nacos-recovery-inventory.py').write_text('print("{}")\n')
            (root / 'peer.env').write_text('\n'.join([
                f'BACKUP_ROOT={root}/backup', 'PEER_ROOT=/home/test/backup', 'PEER_HOST=test',
                'SSH_KEY=/unused', 'KNOWN_HOSTS=/unused', 'PASSPHRASE_FILE=/unused']))
            stubs = {
                'df': '#!/bin/sh\necho "Filesystem 1024-blocks Used Available"\necho "mock 99000000 0 99000000"\n',
                'ssh': '#!/bin/sh\necho 99000000\n',
                'systemd-run': '#!/bin/sh\necho watchdog >> "$TEST_ROOT/actions"\n',
                'systemctl': '#!/bin/sh\nexit 0\n',
                'docker': '''#!/bin/sh
echo "$1 $2" >> "$TEST_ROOT/actions"
case "$1" in
 inspect)
  case "$3" in
   *Mounts*) echo 'true 0';;
   *ExitCode*) if [ "$FAIL_MODE" = stop ]; then echo 137; else echo 143; fi;;
   *Running*) cat "$TEST_ROOT/running";;
   *) echo '[{"Config":{"Env":["MODE=standalone"]}}]';;
  esac;;
 stop) echo false > "$TEST_ROOT/running";;
 start) echo true > "$TEST_ROOT/running";;
 commit) exit 23;;
esac
''',
            }
            for name, body in stubs.items():
                p = commands / name
                p.write_text(body)
                p.chmod(0o700)
            (root / 'running').write_text('true\n')
            env = dict(os.environ, PATH=str(commands) + ':' + os.environ['PATH'],
                       TEST_ROOT=str(root), FAIL_MODE=mode, WAO_PEER_BACKUP_CONFIG=str(root / 'peer.env'),
                       WAO_INFRA_LOCK_FILE=str(root / 'maintenance.lock'))
            result = subprocess.run(['bash', str(root / 'create-nacos-recovery.sh')], env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
            self.assertNotEqual(result.returncode, 0)
            actions = (root / 'actions').read_text()
            self.assertIn('watchdog', actions)
            self.assertIn('start nacos', actions)
            self.assertEqual((root / 'running').read_text().strip(), 'true')
            self.assertEqual('commit --pause=false' in actions, mode == 'commit')
            self.assertFalse(list(root.glob('backup-nacos/*/COMPLETE')))

    def test_unclean_stop_restarts_without_publishing(self):
        self.run_failure('stop')

    def test_commit_failure_restarts_without_publishing(self):
        self.run_failure('commit')


if __name__ == '__main__':
    unittest.main()
