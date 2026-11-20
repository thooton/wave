#!/usr/bin/env python3
"""A community's worth of sources, generated and served locally over HTTPS, for the data-layer stress run.

    sources.py build WORK [--big N] [--medium M --medium-count K] [--small S] [--count TOTAL]
    sources.py serve WORK [--port 4484]

`build` writes TOTAL sources (default 128, the most Wave holds, the built-in one included) as plain
trees under WORK/trees/sNNN and as Git repositories under WORK/git/sNNN.git:

    s000          BIG packages        (default 3000: the one huge source)
    s001..s00K    MEDIUM packages     (default 5 x 300: "a few hundred each")
    the rest      SMALL packages      (default 20; use --small 120 to go past the 16 MiB manifest budget)

Every package has a realistic manifest (about 1.5 KB: description, three changes, download block) and a
96x96 icon. Names share common words so that a one-letter search matches far more than the 200-result cap.
`serve` answers Git smart HTTP for /sNNN.git with `git http-backend` on https://127.0.0.1:PORT and writes one
JSON line per request to WORK/requests.jsonl (path, start and end in seconds, bytes), which is where the
per-source refresh times come from. The certificate is the throwaway one tools/qa/rooted/source.py makes.
"""
import argparse
import hashlib
import http.server
import importlib.util
import json
import os
from pathlib import Path
import random
import shutil
import ssl
import struct
import subprocess
import sys
import threading
import time
import zlib

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ENV = dict(os.environ, GIT_AUTHOR_NAME='Wave QA', GIT_AUTHOR_EMAIL='qa@example.org',
           GIT_COMMITTER_NAME='Wave QA', GIT_COMMITTER_EMAIL='qa@example.org',
           GIT_AUTHOR_DATE='2026-11-20T00:00:00Z', GIT_COMMITTER_DATE='2026-11-20T00:00:00Z')

WORDS = ('note list clock mail music photo radio chess maps books news video weather timer scan dial wallet notes draw '
         'paint sketch tasks habit fuel train bus tide moon sun star garden recipe fitness sleep phone camera gallery '
         'calendar contacts files cloud backup keyboard launcher theme sound volume torch compass ruler level ping '
         'trace queue vault safe pass code token bridge print forms reader pocket feed quote verse poem story comic').split()
SECTIONS = {'applications': 'Applications', 'games': 'Games', 'tools': 'Tools', 'media': 'Media'}


def png(side=96):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    raw = b''.join(b'\x00' + bytes(v for x in range(side) for v in ((x * 255) // side, (y * 255) // side, 160))
                   for y in range(side))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', side, side, 8, 2, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def manifest(rng, source, i):
    a, b, c = rng.choice(WORDS), rng.choice(WORDS), rng.choice(WORDS)
    name = f'{a.title()} {b.title()} {i:04d}'
    text = ' '.join(rng.choice(WORDS) for _ in range(rng.randrange(90, 140)))
    pkg = {
        'id': f'com.stress.s{source:03d}.p{i:04d}', 'type': 'apk', 'name': name,
        'summary': f'{a.title()} and {b} for a small screen, with {c}'[:80],
        'description': f'{text.capitalize()}.\n\nNothing leaves the phone.',
        'author': 'Stress Maker', 'homepage': f'https://example.invalid/s{source:03d}/p{i:04d}',
        'version': f'{1 + i % 4}.{i % 10}.0',
        'download': {'url': f'https://example.invalid/s{source:03d}/p{i:04d}.apk', 'size': 40000 + i * 7,
                     'sha256': hashlib.sha256(f'{source}/{i}'.encode()).hexdigest()},
        'requires': {'sdk': 27},
        'changes': [{'version': f'{v}.{i % 10}.0', 'date': '2026-11-1%d' % v, 'text': ' '.join(rng.choice(WORDS) for _ in range(12)).capitalize() + '.'}
                    for v in (3, 2, 1)]}
    return pkg


def write_tree(out, source, count, icon):
    rng = random.Random(source * 7919 + 1)
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    sections = list(SECTIONS)
    (out / 'wave.json').write_text(json.dumps({
        'wave': 1, 'name': 'Stress Source %03d' % source, 'description': 'Generated for the data-layer stress run.',
        'maintainer': 'Stress Maker', 'sections': SECTIONS,
        'featured': [f'com.stress.s{source:03d}.p0000', f'com.stress.s{source:03d}.p0001']}, indent=2) + '\n')
    (out / 'icon.png').write_bytes(icon)
    for i in range(count):
        folder = out / sections[i % len(sections)] / f'p{i:04d}'
        folder.mkdir(parents=True)
        (folder / 'package.json').write_text(json.dumps(manifest(rng, source, i), indent=2) + '\n')
        (folder / 'icon.png').write_bytes(icon)


def git_init(tree, repo):
    shutil.rmtree(repo, ignore_errors=True)
    shutil.copytree(tree, repo)
    for args in (['init', '-q', '-b', 'main', '--object-format=sha1'], ['config', 'uploadpack.allowFilter', 'true'],
                 ['add', '.'], ['commit', '-qm', 'Stress source']):
        subprocess.run(['git', '-C', str(repo), *args], check=True, env=ENV, capture_output=True)


def plan(args):
    counts = [args.big] + [args.medium] * args.medium_count
    counts += [args.small] * (args.count - len(counts))
    return counts[:args.count]


def build(args):
    work = Path(args.work).resolve()
    icon = png()
    counts = plan(args)
    done = 0
    for n, count in enumerate(counts):
        write_tree(work / 'trees' / f's{n:03d}', n, count, icon)
        git_init(work / 'trees' / f's{n:03d}', work / 'git' / f's{n:03d}.git')
        done += count
        if n % 16 == 0:
            print(f's{n:03d}: {count} packages ({done} so far)', flush=True)
    (work / 'plan.json').write_text(json.dumps({'counts': counts, 'packages': sum(counts)}))
    size = sum(f.stat().st_size for f in (work / 'trees').rglob('package.json'))
    print(f'{len(counts)} sources, {sum(counts)} packages, {size / 2**20:.1f} MiB of package.json')


def certificate(work):
    spec = importlib.util.spec_from_file_location('rooted_source', ROOT / 'tools/qa/rooted/source.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.certificate(work)


def serve(args):
    work = Path(args.work).resolve()
    spec = importlib.util.spec_from_file_location('git_fixture', ROOT / 'app/core/test-tools/git_fixture.py')
    git = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(git)
    log = (work / 'requests.jsonl').open('a', buffering=1)
    lock = threading.Lock()

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, fmt, *a):
            pass

        def do_GET(self):
            started = time.time()
            body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            name = self.path.split('/')[1].split('?')[0]
            repo = work / 'git' / name
            if not name.endswith('.git') or not repo.is_dir():
                self.send_error(404)
                return
            headers, payload = git.cgi(repo, self.command, self.path, self.headers, body)
            self.send_response(int(headers.pop('Status', '200').split()[0]))
            for key, value in headers.items():
                self.send_header(key, value)
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            with lock:
                log.write(json.dumps({'source': name[:-4], 'method': self.command, 'start': started, 'end': time.time(),
                                      'bytes': len(payload)}) + '\n')

        do_POST = do_GET

    cert, key = certificate(work)
    with http.server.ThreadingHTTPServer(('127.0.0.1', args.port), Handler) as server:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        print(f'Serving {work}/git on https://127.0.0.1:{args.port}', flush=True)
        server.serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    b = sub.add_parser('build')
    b.add_argument('work')
    b.add_argument('--big', type=int, default=3000)
    b.add_argument('--medium', type=int, default=300)
    b.add_argument('--medium-count', type=int, default=5)
    b.add_argument('--small', type=int, default=20)
    b.add_argument('--count', type=int, default=128)
    s = sub.add_parser('serve')
    s.add_argument('work')
    s.add_argument('--port', type=int, default=4484)
    args = parser.parse_args()
    {'build': build, 'serve': serve}[args.command](args)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        pass
