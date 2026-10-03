import functools
import importlib.util
import json
from pathlib import Path
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import tempfile
import threading
import unittest

def load(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module
release = load('release_public_fixture', 'game-release.py')
public = load('public_verifier', 'verify-public.py')

class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *_): pass

class PublicTest(unittest.TestCase):
    def test_http_catalog_manifest_assets_and_wrong_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); source = root / 'source'; (source / 'assets').mkdir(parents=True)
            (source / 'index.html').write_text('<script src="./assets/main.js"></script>')
            (source / 'assets/main.js').write_text('console.log("game fixture")')
            version = 'a' * 40; archive = root / 'game.tgz'; assets = root / 'play-assets'
            release.pack(source, archive, 'dino', version); release.install(archive, assets, 'dino', version); release.activate(assets, 'dino', version, 0)
            server = ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(QuietHandler, directory=root))
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            origin = f'http://127.0.0.1:{server.server_port}'
            try:
                self.assertEqual(public.verify(origin, ['dino'], version)[0]['resources'], 2)
                with self.assertRaises(ValueError): public.verify(origin, ['dino'], 'b' * 40)
                (assets / 'dino' / version / 'assets/main.js').write_text('corrupted')
                with self.assertRaises(ValueError): public.verify(origin, ['dino'], version)
            finally:
                server.shutdown(); thread.join(); server.server_close()

if __name__ == '__main__': unittest.main()
