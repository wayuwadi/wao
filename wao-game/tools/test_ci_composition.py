"""Exercise selective build/deploy orchestration with recorded external commands."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
BASH=shutil.which('bash') or 'C:/Users/wage/AppData/Local/Programs/Git/bin/bash.exe'

class CompositionTest(unittest.TestCase):
    def run_shell(self, root, source, env=None):
        result=subprocess.run([BASH,'-c',source],cwd=root,env={**os.environ,**(env or {})},capture_output=True,text=True,encoding='utf-8')
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        return result.stdout

    def test_build_plan_executes_only_actual_consumers(self):
        for changed, expected in [
            ('wao-game/games/dino/engine.ts',['game:install','game:test:dino','game:dino']),
            ('wao-game/games/rift/engine.ts',['game:install','game:test:rift','game:rift']),
            ('wao-web/src/view.vue',['game:install','web']),
            ('wao-game/host/GameHost.vue',['game:install','game:test:dino','game:test:rift','web']),
            ('docs/release.md',['game:install','web']),
            ('wao-game/games/rift/engine.test.ts',['game:install','game:test:rift','web']),
        ]:
            with self.subTest(changed=changed),tempfile.TemporaryDirectory() as folder:
                root=Path(folder)
                for directory in ['deploy/jenkins','wao-game/tools','wao-game/dist/dino/assets','wao-game/dist/rift/assets','bin','run']:
                    (root/directory).mkdir(parents=True,exist_ok=True)
                for file in ['ci-build-frontends.sh']:
                    shutil.copy(ROOT/'deploy/jenkins'/file,root/'deploy/jenkins'/file)
                for file in ['change-plan.py','game-release.py']:
                    shutil.copy(ROOT/'wao-game/tools'/file,root/'wao-game/tools'/file)
                (root/'deploy/scripts').mkdir()
                shutil.copy(ROOT/'deploy/scripts/static-release.py',root/'deploy/scripts/static-release.py')
                (root/'deploy/jenkins/ci-game-build.sh').write_text('echo "game:$1" >>calls\n')
                (root/'deploy/jenkins/ci-frontend-build.sh').write_text('echo web >>calls\n')
                (root/'wao-game/dist/dino/assets/fixture.js').write_text('/* fixture */')
                (root/'wao-game/dist/dino/index.html').write_text('<!doctype html><p>fixture</p>')
                (root/'wao-game/dist/rift/assets/fixture.js').write_text('/* rift fixture */')
                (root/'wao-game/dist/rift/index.html').write_text('<!doctype html><p>rift fixture</p>')
                (root/'bin/python3').write_text('#!/bin/sh\nexec "'+Path(sys.executable).as_posix()+'" -X utf8 "$@"\n')
                self.run_shell(root,'git init -q; git config user.email qa@example.invalid; git config user.name QA; git add .; git commit -qm base')
                base=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
                path=root/changed; path.parent.mkdir(parents=True,exist_ok=True); path.write_text('fixture change')
                self.run_shell(root,'git add .; git commit -qm changed')
                head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
                self.run_shell(root,'export PATH="$PWD/bin:$PATH"; export WAO_RUN_TEMP="$PWD/run"; sh deploy/jenkins/ci-build-frontends.sh',{'WAO_CHANGE_BASE':base,'WAO_GIT_SHA':head,'WAO_WEB_ONLY':'true'})
                self.assertEqual((root/'calls').read_text().splitlines(),expected)
                plan=json.loads((root/'run/game-plan.json').read_text())
                for game in ['dino','rift']:
                    self.assertEqual((root/f'run/game-{game}.tgz').exists(),game in plan['buildGames'])

    def test_game_only_deploy_never_activates_main(self):
        source=(ROOT/'deploy/jenkins/ci-deploy.sh').read_text(encoding='utf-8')
        functions=source.split('stage_games() {',1)[1].split('gateway_ready_from_cloud() {',1)[0]
        functions='stage_games() {'+functions
        for web,games in [('false','dino'),('false','rift'),('true','')]:
            with self.subTest(web=web),tempfile.TemporaryDirectory() as folder:
                root=Path(folder)
                preamble='''set -eu
retry_remote() { printf '%s\\n' "$*" >>calls; }
ssh() { printf '%s\\n' "$*" >>calls; }
python3() { printf '%s\\n' "$*" >>calls; }
'''
                self.run_shell(root,preamble+functions+'\nstage_cloud\nactivate_cloud\n',{'build_web':web,'build_games':games,'game_remote':'/tmp/game-fixture','WAO_GIT_SHA':'a'*40,'WAO_RUN_TEMP':'/tmp/fixture'})
                calls=(root/'calls').read_text()
                if games:
                    self.assertIn('game-release.py',calls)
                    self.assertNotIn('static-release.py pack',calls)
                    self.assertNotIn('publish-static-routing.py',calls)
                    self.assertNotIn('systemctl',calls)
                else:
                    self.assertNotIn('game-release.py',calls)
                    self.assertIn('static-release.py pack',calls)
                    self.assertIn('publish-static-routing.py',calls)

if __name__=='__main__': unittest.main()
