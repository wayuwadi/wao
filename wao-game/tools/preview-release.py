#!/usr/bin/env python3
"""Local-only production artifact preview; separate main/game roots, no directory listing."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import mimetypes
from pathlib import Path
from urllib.parse import urlsplit, unquote
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


def handler(web, games):
    class Preview(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_GET(self):
            path = unquote(urlsplit(self.path).path)
            if path.startswith('/api/'):
                try:
                    with urlopen(Request('http://127.0.0.1:9000' + self.path), timeout=10) as response:
                        data = response.read(); self.send_response(response.status); self.send_header('Content-Type', response.headers.get('Content-Type', 'application/json')); self.end_headers(); self.wfile.write(data)
                except (HTTPError, URLError): self.send_error(502, 'Local API unavailable')
                return
            root = games if path.startswith('/play-assets/') else web
            relative = path[len('/play-assets/'):] if root == games else path.lstrip('/')
            target = (root / relative).resolve()
            if not target.is_relative_to(root) or any(part.startswith('.') for part in Path(relative).parts): self.send_error(404); return
            if target.is_dir(): target = target / 'index.html'
            if not target.is_file() and root == web and '.' not in Path(path).name: target = web / 'index.html'
            if not target.is_file(): self.send_error(404); return
            data = target.read_bytes(); self.send_response(200)
            self.send_header('Content-Type', mimetypes.guess_type(target.name)[0] or 'application/octet-stream')
            self.send_header('Content-Length', str(len(data))); self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Frame-Options', 'SAMEORIGIN'); self.end_headers(); self.wfile.write(data)
    return Preview


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--web', type=Path, required=True); p.add_argument('--games', type=Path, required=True); p.add_argument('--port', type=int, default=5280)
    args=p.parse_args(); web=args.web.resolve(); games=args.games.resolve()
    if not (web/'index.html').is_file() or not (games/'catalog.json').is_file(): raise ValueError('Build/install both artifact sets first')
    server=ThreadingHTTPServer(('127.0.0.1',args.port),handler(web,games))
    print(f'Local production preview: http://127.0.0.1:{args.port}/breathe',flush=True);server.serve_forever()

if __name__=='__main__': main()
