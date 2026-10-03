import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('game_release', Path(__file__).with_name('game-release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class GameArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / 'dist'
        (self.source / 'assets').mkdir(parents=True)
        (self.source / 'index.html').write_text('<script src="./assets/game.js"></script>')
        (self.source / 'assets/game.js').write_text('/* original test artifact */')
        self.target = self.root / 'public/play-assets'
        self.a, self.b = 'a' * 40, 'b' * 40

    def tearDown(self):
        self.temporary.cleanup()

    def pack_install(self, sha):
        archive = self.root / f'{sha}.tgz'
        release.pack(self.source, archive, 'dino', sha)
        release.install(archive, self.target, 'dino', sha)
        return archive

    def test_independent_activation_rollback_and_old_resources(self):
        archive = self.pack_install(self.a)
        self.assertFalse((self.target / 'catalog.json').exists())
        self.assertEqual(release.install(archive, self.target, 'dino', self.a), 'already-installed')
        catalog = release.activate(self.target, 'dino', self.a, 0)
        self.assertEqual(catalog['revision'], 1)
        # Fixture for an independently published game: only its pointer must survive.
        catalog['games']['other'] = {'version': 'c' * 40, 'hostProtocol': 1, 'rulesVersion': 1}
        (self.target / 'catalog.json').write_bytes(release.encode(catalog))
        (self.source / 'assets/game.js').write_text('/* updated artifact */')
        self.pack_install(self.b)
        updated = release.activate(self.target, 'dino', self.b, 1)
        self.assertEqual(updated['games']['other'], catalog['games']['other'])
        self.assertEqual((self.target / 'dino' / self.a / 'assets/game.js').read_text(), '/* original test artifact */')
        rolled_back = release.activate(self.target, 'dino', self.a, 2)
        self.assertEqual(rolled_back['games']['dino']['version'], self.a)
        self.assertEqual(rolled_back['games']['other'], catalog['games']['other'])
        self.assertTrue((self.target / 'dino' / self.b / 'index.html').is_file())

    def test_conflict_and_existing_lock_never_change_catalog(self):
        self.pack_install(self.a)
        release.activate(self.target, 'dino', self.a, 0)
        original = (self.target / 'catalog.json').read_bytes()
        with self.assertRaisesRegex(ValueError, 'revision conflict'):
            release.activate(self.target, 'dino', self.a, 0)
        (self.target / '.catalog-lock').mkdir()
        with self.assertRaisesRegex(ValueError, 'locked'):
            release.activate(self.target, 'dino', self.a, 1)
        self.assertEqual((self.target / 'catalog.json').read_bytes(), original)

    def test_rift_publish_and_rollback_preserves_live_dino(self):
        self.pack_install(self.a)
        first = release.activate(self.target, 'dino', self.a, 0)
        original = (self.target / 'dino' / self.a / 'assets/game.js').read_bytes()
        for version in [self.a, self.b]:
            archive = self.root / f'rift-{version}.tgz'
            release.pack(self.source, archive, 'rift', version)
            release.install(archive, self.target, 'rift', version)
        release.activate(self.target, 'rift', self.a, 1)
        release.activate(self.target, 'rift', self.b, 2)
        result = release.activate(self.target, 'rift', self.a, 3)
        self.assertEqual(result['games']['dino'], first['games']['dino'])
        self.assertEqual((self.target / 'dino' / self.a / 'assets/game.js').read_bytes(), original)
        self.assertTrue((self.target / 'rift' / self.b / 'index.html').exists())

    def test_damaged_and_extra_assets_refuse_activation(self):
        self.pack_install(self.a)
        asset = self.target / 'dino' / self.a / 'assets/game.js'
        original = asset.read_bytes()
        asset.write_text('tampered')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            release.activate(self.target, 'dino', self.a, 0)
        asset.write_bytes(original)
        (asset.parent / 'unexpected.js').write_text('not in manifest')
        with self.assertRaisesRegex(ValueError, 'Untracked'):
            release.activate(self.target, 'dino', self.a, 0)
        self.assertFalse((self.target / 'catalog.json').exists())

    def test_invalid_game_manifest_does_not_poison_immutable_path(self):
        archive = self.root / 'bad.tgz'
        release.pack(self.source, archive, 'dino', self.a)
        manifest = json.loads((self.source / release.GAME_MANIFEST).read_bytes())
        manifest['writeSaveRules'] = 9
        (self.source / release.GAME_MANIFEST).write_bytes(release.encode(manifest))
        release.static_tool().pack(self.source, archive, self.a)
        with self.assertRaisesRegex(ValueError, 'Incompatible'):
            release.install(archive, self.target, 'dino', self.a)
        self.assertFalse((self.target / 'dino' / self.a).exists())
        self.pack_install(self.a)
        self.assertTrue((self.target / 'dino' / self.a).exists())

    def test_forbidden_paths_and_immutable_replacement(self):
        with self.assertRaises(ValueError):
            release.pack(self.source, self.root / 'x.tgz', '../escape', self.a)
        (self.source / '.env').write_text('private fixture')
        with self.assertRaises(ValueError):
            release.pack(self.source, self.root / 'x.tgz', 'dino', self.a)
        (self.source / '.env').unlink()
        self.pack_install(self.a)
        (self.source / 'assets/game.js').write_text('different bytes at same version')
        archive = self.root / 'changed.tgz'
        release.pack(self.source, archive, 'dino', self.a)
        with self.assertRaisesRegex(ValueError, 'immutable'):
            release.install(archive, self.target, 'dino', self.a)


if __name__ == '__main__':
    unittest.main()
