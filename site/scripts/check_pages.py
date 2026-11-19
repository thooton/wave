#!/usr/bin/env python3
"""Check staged HTML landmarks, local URLs, static module imports, and byte budgets."""
from html.parser import HTMLParser
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit
import sys

BUNDLE_BUDGET = 110 * 1024
LANDING_BUDGET = 32 * 1024
IMPORT = re.compile(r'^\s*(?:import|export)\s+(?:[^;\'"\n]*?\bfrom\s+)?([\'"])([^\'"\n]+)\1', re.M)


class Page(HTMLParser):
    def __init__(self, file):
        super().__init__()
        self.ids = set()
        self.urls = []
        self.refs = []
        self.problems = []
        self.doctype = self.html = self.title = self.main = False
        self.feed(file.read_text(encoding='utf-8'))
        self.close()
        if not all((self.doctype, self.html, self.title, self.main)):
            self.problems.append('missing doctype, html lang, title or main landmark')
        self.problems.extend(f'reference to missing id: {ref}' for ref in self.refs if ref not in self.ids)

    def handle_decl(self, decl):
        if decl.lower() == 'doctype html':
            self.doctype = True

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if len(attrs) != len(values):
            self.problems.append(f'duplicate attribute on <{tag}>')
        if tag == 'html':
            self.html = bool(values.get('lang'))
        elif tag == 'title':
            self.title = True
        elif tag == 'main':
            if self.main:
                self.problems.append('multiple main landmarks')
            self.main = values.get('id') == 'main' and values.get('tabindex') == '-1'
        elif tag == 'img' and 'alt' not in values:
            self.problems.append('image without an alt attribute')
        for key, value in attrs:
            if key == 'id':
                if not value or value in self.ids:
                    self.problems.append(f'empty or duplicate id: {value}')
                self.ids.add(value)
            elif key in ('src', 'href'):
                # External anchors are intentional; scripts/assets never load from other origins.
                self.urls.append((value or '', tag == 'a'))
            elif key in ('for', 'aria-describedby', 'aria-labelledby', 'aria-controls'):
                self.refs.extend((value or '').split())


def check(root):
    pages = {p: Page(p) for p in root.rglob('*.html')}
    files = {p for p in root.rglob('*') if p.is_file() and p.name != '.nojekyll'}
    edges = {p: set() for p in files}
    errors = []
    for path in sorted(files):
        page = pages.get(path)
        if page:
            errors.extend(f'{path.relative_to(root)}: {message}' for message in page.problems)
        urls = page.urls if page else []
        if path.suffix == '.mjs':
            urls = [(match[2], False) for match in IMPORT.finditer(path.read_text(encoding='utf-8'))]
            if any(not url.startswith(('./', '../')) for url, _ in urls):
                errors.append(f'{path.relative_to(root)}: non-relative module import')
        for url, external_allowed in urls:
            parts = urlsplit(url)
            if external_allowed and parts.scheme == 'https' and parts.hostname:
                continue
            dest = (path.parent / unquote(parts.path)).resolve() if parts.path else path
            if dest.is_dir():
                dest = dest / 'index.html'
            if (not url or parts.scheme or parts.netloc or parts.path.startswith('/') or dest not in files
                    or (parts.fragment and dest in pages and unquote(parts.fragment) not in pages[dest].ids)):
                errors.append(f'{path.relative_to(root)}: invalid local URL: {url}')
            else:
                edges[path].add(dest)

    entry = root / 'index.html'
    if entry not in pages:
        errors.append('missing site entry point')
    else:
        visited, todo = set(), [entry]
        while todo:
            next_file = todo.pop()
            if next_file not in visited:
                visited.add(next_file)
                todo.extend(edges[next_file] - visited)
        errors.extend(f'asset not reachable from index.html: {p.relative_to(root)}' for p in sorted(files - visited))
        landing = sum(p.stat().st_size for p in {entry} | (edges[entry] - pages.keys()))
        if landing > LANDING_BUDGET:
            errors.append(f'landing payload {landing} B exceeds {LANDING_BUDGET} B')
    total = sum(p.stat().st_size for p in files)
    if total > BUNDLE_BUDGET:
        errors.append(f'bundle {total} B exceeds {BUNDLE_BUDGET} B')
    return errors


if __name__ == '__main__':
    problems = check(Path(sys.argv[1]).resolve(strict=True))
    if problems:
        sys.exit('\n'.join(problems))
