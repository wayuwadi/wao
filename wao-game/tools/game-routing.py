#!/usr/bin/env python3
"""Render same-origin immutable game locations into an existing HTTPS server."""
import argparse
from pathlib import Path
import re

BEGIN = '    # BEGIN WAO GAME ASSETS v1'
END = '    # END WAO GAME ASSETS v1'
BLOCK = r"""    # BEGIN WAO GAME ASSETS v1
    location = /play-assets/catalog.json {
        alias /var/www/wao/play-assets/catalog.json;
        add_header Cache-Control "no-cache, no-store, must-revalidate" always;
        add_header X-Content-Type-Options nosniff always;
    }
    location ~ "^/play-assets/(dino|rift)/([0-9a-f]{40})/(index\.html|game-manifest\.json|assets/[A-Za-z0-9_.-]+\.(?:js|css|png|jpg|webp|svg|woff2|ogg|mp3))$" {
        alias /var/www/wao/play-assets/$1/$2/$3;
        add_header Cache-Control "public, max-age=31536000, immutable";
        add_header X-Content-Type-Options nosniff always;
        add_header X-Frame-Options SAMEORIGIN always;
    }
    # Explicit fallback prevents unknown paths from receiving the main SPA shell.
    location /play-assets/ { return 404; }
    # END WAO GAME ASSETS v1
"""


def render(text):
    if BEGIN in text:
        if text.count(BEGIN) != 1 or text.count(END) != 1:
            raise ValueError('Ambiguous game routing markers')
        return text[:text.index(BEGIN)] + BLOCK.rstrip() + text[text.index(END) + len(END):]
    # Existing deployment configs use a conventional server block. Refuse ambiguous input.
    matches = list(re.finditer(r'server\s*\{\s*listen\s+443\b[^;]*;', text))
    if len(matches) != 1:
        raise ValueError('Expected exactly one HTTPS server starting with listen 443')
    point = matches[0].end()
    return text[:point] + '\n\n' + BLOCK + text[point:]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source'); parser.add_argument('destination')
    args = parser.parse_args()
    result = render(Path(args.source).read_text(encoding='utf-8'))
    Path(args.destination).write_text(result, encoding='utf-8')

if __name__ == '__main__': main()
