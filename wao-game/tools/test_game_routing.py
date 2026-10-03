import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location('game_routing', Path(__file__).with_name('game-routing.py'))
route = importlib.util.module_from_spec(spec); spec.loader.exec_module(route)

class RoutingTest(unittest.TestCase):
    def test_additive_idempotent_https_only(self):
        text = 'server { listen 80; location / { return 301 https://$host; } }\nserver {\n    listen 443 ssl;\n    location /api/ { proxy_pass http://backend; }\n}'
        result = route.render(text)
        self.assertEqual(result, route.render(result))
        self.assertIn('location /api/ { proxy_pass http://backend; }', result)
        self.assertLess(result.index('listen 443'), result.index(route.BEGIN))
        self.assertIn('location /play-assets/ { return 404; }', result)
        self.assertNotIn('immutable" always', result)
    def test_ambiguous_config_refused(self):
        for text in ['', 'server { listen 443; } server { listen 443; }', route.BEGIN]:
            with self.assertRaises(ValueError): route.render(text)

if __name__ == '__main__': unittest.main()
