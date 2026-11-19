#!/usr/bin/env python3
"""Check that internal URLs resolve inside the staged site and local anchors exist."""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit
import sys


class Page(HTMLParser):
    def __init__(self, file):
        super().__init__()
        self.ids = set()
        self.urls = []
        self.feed(file.read_text(encoding='utf-8'))
        self.close()

    def handle_starttag(self, tag, attrs):
        for key, value in attrs:
            if key == 'id':
                self.ids.add(value)
            if key in ('src', 'href'):
                self.urls.append(value)


def check(root):
    pages = {p: Page(p) for p in root.rglob('*.html')}
    errors = []
    for path, page in pages.items():
        for url in page.urls:
            parts = urlsplit(url)
            if parts.scheme == 'https' and parts.hostname:
                continue
            dest = (path.parent / unquote(parts.path)).resolve() if parts.path else path
            if dest.is_dir():
                dest = dest / 'index.html'
            if (parts.scheme or parts.netloc or parts.path.startswith('/') or not dest.is_relative_to(root)
                    or not dest.is_file() or (parts.fragment and dest in pages and
                                               unquote(parts.fragment) not in pages[dest].ids)):
                errors.append(f'{path.relative_to(root)}: invalid local URL: {url}')
    return errors


if __name__ == '__main__':
    problems = check(Path(sys.argv[1]).resolve(strict=True))
    if problems:
        sys.exit('\n'.join(problems))
