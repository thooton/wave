#!/usr/bin/env python3
"""Regenerate offline Git fixtures, or serve a git http-backend loopback test."""
import argparse
import http.server
import json
import os
from pathlib import Path
import random
import shutil
import subprocess
import tempfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'app/core/src/test/resources/git'
ENV = dict(os.environ, GIT_AUTHOR_NAME='Wave', GIT_AUTHOR_EMAIL='wave@example.org',
           GIT_COMMITTER_NAME='Wave', GIT_COMMITTER_EMAIL='wave@example.org',
           GIT_AUTHOR_DATE='2025-01-01T00:00:00Z', GIT_COMMITTER_DATE='2025-01-01T00:00:00Z')


def git(repo, *args, data=None):
    return subprocess.check_output(['git', '-C', str(repo), *args], input=data, env=ENV)


def pkt(text):
    data = text.encode('ascii')
    return f'{len(data) + 4:04x}'.encode('ascii') + data


def init(repo, packages):
    shutil.copytree(ROOT / 'spec/example-source', repo)
    (repo / 'README').write_text('Ignored by Wave.\n')
    (repo / 'ignored').write_bytes(bytes(300_000))
    if packages:
        rng = random.Random(29)
        sample = json.loads((repo / 'applications/list/package.json').read_text())
        for i in range(packages):
            folder = repo / 'applications' / f'package-{i}'
            folder.mkdir()
            pkg = dict(sample, name=f'Package {i}', id=f'com.example.package{i}',
                       description=bytes(rng.getrandbits(8) for _ in range(800)).hex())
            (folder / 'package.json').write_text(json.dumps(pkg) + '\n')
    else:
        # Similar blobs make Git produce both OFS and REF delta fixtures.
        rng = random.Random(27)
        common = bytes(rng.randrange(33, 127) for _ in range(4000))
        for i in range(5):
            (repo / f'delta-{i}').write_bytes(common + str(i).encode('ascii'))
    git(repo, 'init', '-q', '-b', 'main', '--object-format=sha1')
    git(repo, 'config', 'uploadpack.allowFilter', 'true')
    git(repo, 'config', 'core.autocrlf', 'false')
    git(repo, 'add', '.')
    git(repo, 'commit', '-qm', 'Wave test source')


def cgi(repo, method, target, headers, body):
    url = urlsplit(target)
    env = dict(os.environ, GIT_PROJECT_ROOT=str(repo.parent), GIT_HTTP_EXPORT_ALL='1',
               REQUEST_METHOD=method, PATH_INFO=url.path, QUERY_STRING=url.query,
               CONTENT_TYPE=headers.get('Content-Type', ''), CONTENT_LENGTH=str(len(body)),
               GIT_PROTOCOL=headers.get('Git-Protocol', ''))
    data = subprocess.check_output(['git', 'http-backend'], env=env, input=body)
    head, payload = data.split(b'\r\n\r\n', 1)
    return dict(line.split(': ', 1) for line in head.decode('ascii').split('\r\n')), payload


def record(repo):
    head = git(repo, 'rev-parse', 'HEAD').strip()
    objects = head + b'\n'
    for name, args in [('plain', ['--window=0']), ('ofs-delta', ['--delta-base-offset']), ('ref-delta', []),
                       ('filtered', ['--delta-base-offset', '--filter=blob:limit=262144'])]:
        (OUT / (name + '.pack')).write_bytes(git(repo, 'pack-objects', '--stdout', '--revs', '--threads=1',
                                               *args, data=objects))
    (OUT / 'head.txt').write_bytes(head + b'\n')
    headers = {'Git-Protocol': 'version=2', 'Content-Type': 'application/x-git-upload-pack-request'}
    fetch = ['want ' + head.decode('ascii') + '\n', 'deepen 1\n', 'filter blob:limit=262144\n',
             'no-progress\n', 'ofs-delta\n', 'done\n']
    for name, command, args in [('advertisement', None, []), ('ls-refs', 'ls-refs', ['ref-prefix HEAD\n']),
                                ('fetch', 'fetch', fetch)]:
        if command is None:
            _, response = cgi(repo, 'GET', '/fixture/info/refs?service=git-upload-pack', headers, b'')
        else:
            body = pkt('command=' + command + '\n') + b'0001' + b''.join(map(pkt, args)) + b'0000'
            _, response = cgi(repo, 'POST', '/fixture/git-upload-pack', headers, body)
        (OUT / (name + '.pkt')).write_bytes(response)


def serve(repo, port):
    class Handler(http.server.BaseHTTPRequestHandler):
        def handle_git(self):
            body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            headers, payload = cgi(repo, self.command, self.path, self.headers, body)
            self.send_response(int(headers.pop('Status', '200').split()[0]))
            for name, value in headers.items():
                self.send_header(name, value)
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        do_GET = do_POST = handle_git

    with http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler) as server:
        print(f'http://127.0.0.1:{server.server_port}/fixture', flush=True)
        server.serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serve', type=int, help='serve a test repository on this loopback port')
    parser.add_argument('--packages', type=int, default=0, help='generate this many extra packages when serving')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='git-fixture-', dir=ROOT / '.oak/tmp' if (ROOT / '.oak/tmp').exists() else None) as work:
        repo = Path(work) / 'fixture'
        init(repo, args.packages)
        if args.serve is None:
            if args.packages:
                parser.error('--packages is for manual serving, not recording')
            record(repo)
        else:
            serve(repo, args.serve)


if __name__ == '__main__':
    main()
