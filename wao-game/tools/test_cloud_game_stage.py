"""Execute the cloud activation shell with temporary files and mocked Nginx commands."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('stage', Path(__file__).with_name('cloud-game-stage.py'))
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)
BASH = shutil.which('bash') or 'C:/Users/wage/AppData/Local/Programs/Git/bin/bash.exe'


class ActivationTest(unittest.TestCase):
    def script(self, root):
        version, previous = 'b' * 40, 'a' * 40
        (root / 'public-release-coordinator.json').write_text(json.dumps({
            'state': 'publishing', 'sha': version, 'previousSha': previous, 'authorizedByUser': True
        }))
        scripts = []
        class Transport:
            def cloud_run(self, script, *args, **kwargs):
                scripts.append(script)
                return 'fixture-invocation'
            def cloud_result(self, *_):
                return [{'status': 'Success', 'exitCode': 0, 'output': 'fixture', 'dropped': False}]
        argv = ['stage', 'activate', '--runtime', str(root), '--version', version,
                '--game', 'rift', '--archive', str(root / 'unused.tgz'), '--expected-catalog', '1']
        with patch.object(sys, 'argv', argv), patch.object(stage, 'load', return_value=Transport()), contextlib.redirect_stdout(io.StringIO()):
            stage.main()
        self.assertEqual(len(scripts), 1)
        return scripts[0], version, previous

    def test_reload_or_catalog_failure_keeps_old_pointer_and_restores_configuration(self):
        for scenario in ['success', 'config-failure', 'reload-failure', 'catalog-conflict']:
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                source, version, previous = self.script(root)
                remote = root / 'remote'; remote.mkdir()
                assets = root / 'assets'; assets.mkdir()
                catalog = assets / 'catalog.json'
                catalog.write_text('{"revision":1,"games":{"dino":{"version":"preserved"}}}')
                original_catalog = catalog.read_bytes()
                conf = root / 'nginx.conf'; conf.write_text('old-route')
                source = source.replace('/root/wao-game-releases/' + version, remote.as_posix())
                source = source.replace('/var/www/wao/play-assets', assets.as_posix())
                source = source.replace('/etc/nginx/conf.d/wao.wayuwa.cn.conf', conf.as_posix())
                preamble = r'''
readlink() { echo /var/www/wao/releases/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; }
nginx() { echo check >>calls; test "$SCENARIO" != config-failure; }
reloads=0
systemctl() {
  echo reload >>calls
  reloads=$((reloads+1))
  test "$SCENARIO" != reload-failure || test "$reloads" -gt 1
}
python3() {
  case "$1" in
    */game-release.py)
      if test "$2" = activate; then
        echo activate >>calls
        test "$SCENARIO" != catalog-conflict || return 1
        printf '{"revision":2}' >"$3/catalog.json"
      fi;;
    */game-routing.py) printf new-route >"$3";;
    *) "$PYTHON_EXE" -X utf8 "$@";;
  esac
}
'''
                result = subprocess.run([BASH, '-c', preamble + source], cwd=root,
                    env={**os.environ, 'SCENARIO': scenario, 'PYTHON_EXE': Path(sys.executable).as_posix()},
                    capture_output=True, text=True, encoding='utf-8')
                calls = (root / 'calls').read_text().splitlines()
                if scenario == 'success':
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(calls, ['check', 'reload', 'activate'])
                    self.assertEqual(json.loads(catalog.read_text())['revision'], 2)
                    self.assertEqual(conf.read_text(), 'new-route')
                else:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(catalog.read_bytes(), original_catalog)
                    self.assertEqual(conf.read_text(), 'old-route')
                    if scenario in ['config-failure', 'reload-failure']:
                        self.assertNotIn('activate', calls)
                    if scenario == 'catalog-conflict':
                        self.assertEqual(calls[-2:], ['check', 'reload'])


if __name__ == '__main__':
    unittest.main()
