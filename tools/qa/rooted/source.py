#!/usr/bin/env python3
"""The local test source for tools/qa/rooted: build it, and serve it over HTTPS.

    source.py build WORK     # certificate, the two test modules, and a Git source holding them
    source.py serve WORK     # https://127.0.0.1:4483/mods.git and /files/, until interrupted

The source is what a person would publish: wave.json, a System section and one
package.json per module, each pointing at https://localhost:4483/files/<zip> with the
zip's size and SHA-256. The modules are app/root-tests/wave_test_ok and
wave_test_broken, unchanged. Wave reaches the server through `adb reverse`; the
throwaway certificate is for localhost/127.0.0.1 only and is trusted only by the
QA build that rooted/apk.py makes.
"""
import hashlib
import http.server
import importlib.util
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PORT = 4483
MODULES = {'wave_test_ok': ('Wave Test OK', 'Harmless test mod: changes nothing, leaves a marker',
                            'A harmless system mod for testing Wave\'s install engine and boot watchdog. It changes nothing '
                            'on the phone; when Magisk mounts it, it leaves a marker in /data/adb.'),
           'wave_test_broken': ('Wave Test Broken', 'Deliberately stops one boot, to test the watchdog',
                                'Stops one boot from completing, to test Wave\'s boot watchdog. It does nothing unless armed '
                                'from a computer, and disarms itself first.')}
ENV = dict(os.environ, GIT_AUTHOR_NAME='Wave QA', GIT_AUTHOR_EMAIL='qa@example.org',
           GIT_COMMITTER_NAME='Wave QA', GIT_COMMITTER_EMAIL='qa@example.org',
           GIT_AUTHOR_DATE='2026-11-19T00:00:00Z', GIT_COMMITTER_DATE='2026-11-19T00:00:00Z')


def certificate(work):
    work.mkdir(parents=True, exist_ok=True)
    cert, key = work / 'cert.pem', work / 'key.pem'
    if not cert.is_file() or not key.is_file():
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(key), '-out', str(cert),
                        '-days', '30', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1'],
                       check=True, capture_output=True)
        key.chmod(0o600)
    return cert, key


def module_zip(module, out):
    root = ROOT / 'app/root-tests'
    staging = out.parent / ('stage-' + module)
    shutil.rmtree(staging, ignore_errors=True)
    shutil.copytree(root / module, staging)
    shutil.copytree(root / 'META-INF', staging / 'META-INF')
    prop = staging / 'module.prop'
    prop.write_text(prop.read_text().replace('VERSION', '1.0').replace('CODE', '1'))
    out.unlink(missing_ok=True)
    subprocess.run(['zip', '-qr', '-X', str(out), '.'], cwd=staging, check=True)
    shutil.rmtree(staging)


def build(work):
    certificate(work)
    files, repo = work / 'files', work / 'git' / 'mods.git'
    shutil.rmtree(files, ignore_errors=True)
    shutil.rmtree(work / 'git', ignore_errors=True)
    files.mkdir()
    for module, (name, summary, description) in MODULES.items():
        zipped = files / f'{module}-1.0.zip'
        module_zip(module, zipped)
        data = zipped.read_bytes()
        folder = repo / 'system' / module.replace('_', '-')
        folder.mkdir(parents=True)
        (folder / 'package.json').write_text(json.dumps({
            'id': module, 'type': 'module', 'name': name, 'summary': summary,
            'description': description + '\n\nA system mod: it takes effect after a reboot.',
            'author': 'Wave QA', 'version': '1.0',
            'download': {'url': f'https://localhost:{PORT}/files/{zipped.name}', 'size': len(data),
                         'sha256': hashlib.sha256(data).hexdigest()},
            'requires': {'device': ['XP3800'], 'sdk': 27},
            'changes': [{'version': '1.0', 'date': '2026-11-19', 'text': 'Test release.'}]}, indent=2) + '\n')
    (repo / 'wave.json').write_text(json.dumps({
        'wave': 1, 'name': 'Wave', 'description': 'Local test source for the rooted QA suite.', 'maintainer': 'Wave QA',
        'sections': {'system': 'System'}, 'featured': ['wave_test_ok']}, indent=2) + '\n')
    for args in (['init', '-q', '-b', 'main', '--object-format=sha1'], ['config', 'uploadpack.allowFilter', 'true'],
                 ['add', '.'], ['commit', '-qm', 'Rooted QA source']):
        subprocess.run(['git', '-C', str(repo), *args], check=True, env=ENV, capture_output=True)
    return files


def serve(work):
    spec = importlib.util.spec_from_file_location('git_fixture', ROOT / 'app/core/test-tools/git_fixture.py')
    git = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(git)
    repo, files = work / 'git' / 'mods.git', work / 'files'

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            print(time.strftime('%H:%M:%S'), self.command, self.path, fmt % args, flush=True)

        def do_GET(self):
            if self.path.startswith('/files/'):
                f = files / Path(self.path[len('/files/'):]).name
                if not f.is_file():
                    self.send_error(404)
                    return
                data = f.read_bytes()
                self.send_response(200)
                self.send_header('Content-Type', 'application/zip')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            self.git()

        def git(self):
            if self.path.split('/')[1] != 'mods.git':
                self.send_error(404)
                return
            body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            headers, payload = git.cgi(repo, self.command, self.path, self.headers, body)
            self.send_response(int(headers.pop('Status', '200').split()[0]))
            for name, value in headers.items():
                self.send_header(name, value)
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        do_POST = git

    cert, key = certificate(work)
    with http.server.ThreadingHTTPServer(('127.0.0.1', PORT), Handler) as server:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        print(f'Serving on https://127.0.0.1:{PORT}', flush=True)
        server.serve_forever()


if __name__ == '__main__':
    if len(sys.argv) != 3 or sys.argv[1] not in ('build', 'serve'):
        sys.exit(__doc__)
    work = Path(sys.argv[2]).resolve()
    if sys.argv[1] == 'build':
        print('built', build(work))
    else:
        try:
            serve(work)
        except KeyboardInterrupt:
            pass
