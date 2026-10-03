import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
spec=importlib.util.spec_from_file_location('game_cloud_stage',Path(__file__).with_name('cloud-game-stage.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class StageTest(unittest.TestCase):
    def test_coordinator_must_match_authorized_version(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); p=root/'public-release-coordinator.json'
            for state in [{'state':'complete','sha':'a'*40,'authorizedByUser':True}, {'state':'publishing','sha':'b'*40,'authorizedByUser':True}, {'state':'publishing','sha':'a'*40,'authorizedByUser':False}]:
                p.write_text(json.dumps(state))
                with self.assertRaises(ValueError): module.coordinator(root,'a'*40)
            state={'state':'publishing','sha':'a'*40,'authorizedByUser':True}; p.write_text(json.dumps(state))
            self.assertEqual(module.coordinator(root,'a'*40),state)
    def test_chunk_retry_idempotent_and_conflicting_bytes_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/'asset'; payload=b'original bytes'
            script=module.chunk_script(str(p),payload,0,hashlib.sha256(payload).hexdigest())
            code=script.split("<<'PY'\n",1)[1].rsplit('\nPY',1)[0]
            for _ in range(2):
                subprocess.run([sys.executable,'-c',code],check=True,capture_output=True)
            self.assertEqual(p.read_bytes(),payload)
            p.write_bytes(b'different bytes')
            self.assertNotEqual(subprocess.run([sys.executable,'-c',code],capture_output=True).returncode,0)
            self.assertEqual(p.read_bytes(),b'different bytes')
    def test_invalid_version_never_becomes_shell_text(self):
        for value in ['main','a'*39,';echo secret','A'*40]:
            with self.assertRaises(ValueError): module.checked_sha(value)

if __name__=='__main__':unittest.main()
