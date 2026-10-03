import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location('game_plan', Path(__file__).with_name('change-plan.py'))
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class PlanTest(unittest.TestCase):
    def test_only_game(self):
        value = module.plan(['wao-game/games/dino/engine.ts'])
        self.assertFalse(value['buildWeb']); self.assertEqual(value['buildGames'], ['dino'])
    def test_web_reuses_all_games(self):
        self.assertEqual(module.plan(['wao-web/src/views/LoginView.vue'])['buildGames'], [])
    def test_shared_real_consumers(self):
        self.assertEqual(module.plan(['wao-game/host/GameHost.vue'])['buildGames'], [])
        self.assertTrue(module.plan(['wao-game/games/dino/save.ts'])['buildWeb'])
        self.assertEqual(module.plan(['wao-game/protocol/dino.ts'])['buildGames'], ['dino'])
    def test_tests_and_demo_do_not_publish_games(self):
        value = module.plan(['wao-game/games/dino/engine.test.ts', 'wao-game/demos/overall.html'])
        self.assertEqual(value['testGames'], ['dino']); self.assertEqual(value['buildGames'], [])
        self.assertFalse(value['buildWeb'])
    def test_unknown_game_fails_closed(self):
        with self.assertRaises(ValueError): module.plan(['wao-game/games/unknown/main.ts'])
    def test_second_game_reuses_first_artifact(self):
        value = module.plan(['wao-game/games/rift/engine.ts','wao-game/protocol/rift.ts','wao-game/host/registry.ts'])
        self.assertEqual(value['buildGames'], ['rift'])
        self.assertEqual(value['testGames'], ['dino', 'rift', 'starfield'])
        self.assertTrue(value['buildWeb'])
        before={'dependencies':{'vue':'3.5.40'},'scripts':{'build:dino':'old'}}
        after={'dependencies':{'vue':'3.5.40'},'scripts':{'build:dino':'old','build:rift':'new','test:rift':'test'}}
        value=module.plan(['wao-game/package.json','wao-game/tools/game-routing.py'],package_before=before,package_after=after)
        self.assertEqual(value['buildGames'], ['rift'])
        self.assertEqual(module.plan(['wao-game/protocol/host.ts'])['buildGames'], [])

if __name__ == '__main__': unittest.main()
