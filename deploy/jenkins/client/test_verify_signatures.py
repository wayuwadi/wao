import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('signatures', Path(__file__).with_name('verify-signatures.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SignatureTests(unittest.TestCase):
    def test_existing_public_key_accepts_both_artifacts_and_rejects_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / 'wao-client/src-tauri/tauri.conf.json'
            config.parent.mkdir(parents=True)
            public, secret = root / 'test.pub', root / 'test.key'
            subprocess.run(['minisign', '-G', '-W', '-p', str(public), '-s', str(secret)], check=True, capture_output=True)
            config.write_text(json.dumps({'plugins': {'updater': {
                'pubkey': base64.b64encode(public.read_bytes()).decode(),
            }}}))
            raw = root / 'raw'
            raw.mkdir()
            for name in ['WAO-test-setup.exe', 'WAO.app.tar.gz']:
                artifact = raw / name
                artifact.write_bytes(b'only a test fixture')
                signature = Path(str(artifact) + '.sig')
                subprocess.run(['minisign', '-S', '-s', str(secret), '-m', str(artifact), '-x', str(signature)], check=True, capture_output=True)
                signature.write_bytes(base64.b64encode(signature.read_bytes()))
            module.verify(root, raw)
            (raw / 'WAO.app.tar.gz').write_bytes(b'tampered bytes')
            with self.assertRaises(subprocess.CalledProcessError):
                module.verify(root, raw)

    def test_missing_platform_never_reaches_signing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / 'wao-client/src-tauri/tauri.conf.json'
            config.parent.mkdir(parents=True)
            config.write_text('{"plugins":{"updater":{"pubkey":""}}}')
            raw = root / 'raw'
            raw.mkdir()
            (raw / 'WAO-test-setup.exe').write_bytes(b'fixture')
            with self.assertRaisesRegex(ValueError, '必须包含'):
                module.verify(root, raw)
