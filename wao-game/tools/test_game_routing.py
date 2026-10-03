import importlib.util
from pathlib import Path
import unittest
import re
import shlex
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
    def test_quantifier_is_quoted_for_nginx_parser(self):
        lines=[line.strip() for line in route.BLOCK.splitlines() if 'location ~ ' in line]
        self.assertEqual(len(lines),1)
        line=lines[0]
        self.assertTrue(line.startswith('location ~ "'))
        self.assertTrue(line.endswith('" {'))
        expression=shlex.split(line)[2]
        self.assertIsNotNone(re.fullmatch(expression,'/play-assets/dino/'+'a'*40+'/assets/index-one.js'))
        self.assertIsNotNone(re.fullmatch(expression,'/play-assets/rift/'+'a'*40+'/index.html'))
        self.assertIsNotNone(re.fullmatch(expression,'/play-assets/starfield/'+'a'*40+'/index.html'))
        self.assertIsNone(re.fullmatch(expression,'/play-assets/unregistered/'+'a'*40+'/index.html'))
        for path in ['/play-assets/dino/'+'a'*39+'/index.html','/play-assets/dino/'+'a'*40+'/secret.txt','/play-assets/dino/'+'a'*40+'/../index.html']:
            self.assertIsNone(re.fullmatch(expression,path))
        template=Path(__file__).resolve().parents[2]/'deploy/nginx/wao.wayuwa.cn.conf.template'
        self.assertIn(line,template.read_text(encoding='utf-8'))
    def test_ambiguous_config_refused(self):
        for text in ['', 'server { listen 443; } server { listen 443; }', route.BEGIN]:
            with self.assertRaises(ValueError): route.render(text)

if __name__ == '__main__': unittest.main()
