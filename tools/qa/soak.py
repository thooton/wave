#!/usr/bin/env python3
"""Soak Wave on the reserved phone and watch its memory: opt-in, never part of suite.py.

    tools/qa/phone-run.sh --who "you, Wave soak (~90 min)" --wait 1800 -- \\
        python3 tools/qa/soak.py [--commit REV | --apk APK] [--walks 300] [--churn 200] [--flips 300]

Three phases, each a loop driven with the keypad (sendevent on /dev/input/event1, the
same path a real key takes), against a local HTTPS Git source this script serves:

  walk   every screen opened and closed: Home's featured package and back, Sources, the
         source, a section, a package's details, back out, Installed, Search (typed
         query), a result's details, back, Home again.
  churn  the same walk while the source changes under it: each cycle asks for Refresh
         Sources, and the server answers slowly (3 s) with a new commit each time
         (versions, summaries, icons and one package added and one removed). Every tenth
         cycle starts a download of a 1.5 MB package that the server sends slowly, cuts
         once half way (Wave resumes it) and that then fails its SHA-256, so it ends as a
         download stopped part way does: the alert, Done, back to the screens. Back in
         the middle of it puts Wave in the background, and it is brought back.
  flips  Home key and relaunch; every fifth cycle Back from the root instead, which
         finishes the Activity, so the next launch makes a new one in the same process.

Every --every cycles it forces a garbage collection (SIGUSR1 through run-as) and records
`dumpsys meminfo com.wave.xp3` (PSS, Java and native heap allocated, Views, ViewRootImpl,
Activities, AppContexts, binders), the thread count and the open file descriptors. The
phone has no `procrank`; PSS and USS come from meminfo (USS: private dirty + clean).
It writes samples.csv and report.md under .oak/tmp/wave-qa/soak-<time>/.

The APK is the debug build from tools/qa/rooted/apk.py (built for --commit, HEAD by
default): its built-in source is https://localhost:4483/mods.git, reached through adb
reverse, and it trusts that work folder's throwaway certificate only. No root is used,
nothing is installed but that temporary Wave, which is removed at the end, and no
package action is ever completed (the only download fails its hash by design).
"""
import argparse
import csv
from datetime import datetime, timezone
import hashlib
import http.server
import importlib.util
import json
import os
from pathlib import Path
import random
import re
import shutil
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
import zlib

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / 'rooted'))
import run  # noqa: E402
import source as rooted_source  # noqa: E402

PORT = 4483
SERIAL = run.SERIAL
PACKAGE = run.PACKAGE
ACTIVITY = 'com.wave.xp3/.app.WaveActivity'
# Linux key codes of the XP3's keypad (soc:matrix_keypad, /dev/input/event1).
KEYS = {'UP': 103, 'DOWN': 108, 'LEFT': 105, 'RIGHT': 106, 'OK': 352, 'BACK': 158, 'MENU': 139, 'HOME': 102}
BIG = 1536 * 1024
BIG_ID = 'com.wave.soak.download'
ENV = dict(rooted_source.ENV, GIT_AUTHOR_NAME='Wave Soak', GIT_COMMITTER_NAME='Wave Soak')
METRICS = ('pss_kb', 'uss_kb', 'java_alloc_kb', 'java_size_kb', 'native_alloc_kb', 'native_size_kb', 'graphics_kb',
           'views', 'viewroots', 'activities', 'appcontexts', 'assets', 'local_binders', 'proxy_binders',
           'death_recipients', 'threads', 'fds', 'rss_kb')


class SoakError(RuntimeError):
    pass


# ----------------------------------------------------------------------------
# The source: a Git repository that changes on demand, and a slow download
# ----------------------------------------------------------------------------

def png(side, rgb):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    row = b'\x00' + bytes(rgb) * side
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', side, side, 8, 2, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress(row * side)) + chunk(b'IEND', b''))


def big_bytes():
    return random.Random(41).randbytes(BIG)


class Source:
    """The soak source in WORK/soak/git/mods.git; `churn()` commits a new round."""

    SECTIONS = {'applications': ('Applications', 40), 'games': ('Games', 20), 'system': ('System', 8)}

    def __init__(self, work):
        self.repo = work / 'soak' / 'git' / 'mods.git'
        self.round = 0
        self.lock = threading.Lock()
        self.rng = random.Random(7)

    def package(self, section, i):
        kind = 'module' if section == 'system' else 'apk'
        pid = f'soak_mod_{i}' if kind == 'module' else f'com.wave.soak.{section}{i}'
        return pid, kind

    def write_package(self, section, i, name, version, summary, colour, download=None):
        pid, kind = self.package(section, i)
        folder = self.repo / section / f'{section}-{i}'
        folder.mkdir(parents=True, exist_ok=True)
        data = f'{pid} {version}'.encode()
        pkg = {'id': pid, 'type': kind, 'name': name, 'summary': summary,
               'description': f'{name} exists to be opened and closed many times by tools/qa/soak.py.\n\n'
                              'Nothing here is real and nothing should ever be installed.',
               'author': 'Wave Soak', 'version': version,
               'download': download or {'url': f'https://localhost:{PORT}/files/{pid}.zip', 'size': len(data),
                                        'sha256': hashlib.sha256(data).hexdigest()},
               'requires': {'sdk': 27},
               'changes': [{'version': version, 'date': '2026-11-20', 'text': f'Soak round {self.round}.'}]}
        if kind == 'module':
            pkg['requires']['device'] = ['XP3800']
        (folder / 'package.json').write_text(json.dumps(pkg, indent=2) + '\n')
        (folder / 'icon.png').write_bytes(png(48, colour))

    def build(self):
        shutil.rmtree(self.repo.parent, ignore_errors=True)
        self.repo.mkdir(parents=True)
        (self.repo / 'wave.json').write_text(json.dumps({
            'wave': 1, 'name': 'Soak', 'description': 'tools/qa/soak.py: a source that keeps changing.',
            'maintainer': 'Wave Soak', 'sections': {k: v[0] for k, v in self.SECTIONS.items()},
            'featured': [BIG_ID, 'com.wave.soak.applications0', 'com.wave.soak.games0']}, indent=2) + '\n')
        (self.repo / 'icon.png').write_bytes(png(48, (40, 120, 220)))
        for section, (title, count) in self.SECTIONS.items():
            for i in range(count):
                self.write_package(section, i, f'Soak {title[:-1] if title.endswith("s") else title} {i}', '1.0',
                                   f'Package {i} of {count}, round 0', self.colour())
        data = big_bytes()
        folder = self.repo / 'applications' / 'download'
        folder.mkdir(parents=True)
        (folder / 'package.json').write_text(json.dumps({
            'id': BIG_ID, 'type': 'apk', 'name': 'Soak Download', 'summary': 'Downloads slowly, then fails its hash',
            'description': 'A 1.5 MB download the soak server sends slowly, cuts once and then gets wrong on purpose, '
                           'so that it never installs.',
            'author': 'Wave Soak', 'version': '1.0',
            # The hash of other bytes: the download always ends as "doesn't match the source".
            'download': {'url': f'https://localhost:{PORT}/files/download.apk', 'size': len(data),
                         'sha256': hashlib.sha256(data + b'x').hexdigest()},
            'requires': {'sdk': 27},
            'changes': [{'version': '1.0', 'date': '2026-11-20', 'text': 'Never installs.'}]}, indent=2) + '\n')
        (folder / 'icon.png').write_bytes(png(48, (255, 149, 0)))
        for args in (['init', '-q', '-b', 'main', '--object-format=sha1'], ['config', 'uploadpack.allowFilter', 'true'],
                     ['add', '.'], ['commit', '-qm', 'Soak round 0']):
            subprocess.run(['git', '-C', str(self.repo), *args], check=True, env=ENV, capture_output=True)

    def colour(self):
        return tuple(self.rng.randrange(256) for _ in range(3))

    def churn(self):
        """A new round: five versions and summaries, three icons, one package in and one out."""
        with self.lock:
            self.round += 1
            r = self.round
            for k in range(5):
                i = (r * 5 + k) % 40
                self.write_package('applications', i, f'Soak Application {i}', f'1.{r}',
                                   f'Changed in round {r}', self.colour())
            for k in range(3):
                i = (r * 3 + k) % 20
                self.write_package('games', i, f'Soak Game {i}', '1.0', f'Package {i} of 20, round {r}', self.colour())
            self.write_package('games', 100 + r, f'Soak Extra {r}', '1.0', f'Added in round {r}', self.colour())
            shutil.rmtree(self.repo / 'games' / f'games-{100 + r - 3}', ignore_errors=True)
            subprocess.run(['git', '-C', str(self.repo), 'add', '-A'], check=True, env=ENV, capture_output=True)
            subprocess.run(['git', '-C', str(self.repo), 'commit', '-qm', f'Soak round {r}'], check=True, env=ENV,
                           capture_output=True)


class Server:
    def __init__(self, work, source):
        self.work = work
        self.source = source
        self.churning = False
        self.refreshes = 0
        self.downloads = 0
        self.requests = []
        spec = importlib.util.spec_from_file_location('git_fixture', ROOT / 'app/core/test-tools/git_fixture.py')
        self.git = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.git)

    def start(self):
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', PORT)) == 0:
                raise SoakError(f'Something already listens on 127.0.0.1:{PORT} (the rooted suite?).')
        outer = self
        data = big_bytes()

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def log_message(self, fmt, *args):
                outer.requests.append(f'{time.strftime("%H:%M:%S")} {self.command} {self.path} {fmt % args}')
                del outer.requests[:-200]

            def do_GET(self):
                if self.path.startswith('/files/'):
                    return self.download()
                self.repository()

            def download(self):
                if self.path != '/files/download.apk':
                    self.send_error(404)
                    return
                start = 0
                ranged = re.fullmatch(r'bytes=(\d+)-', self.headers.get('Range', ''))
                if ranged:
                    start = int(ranged[1])
                else:
                    outer.downloads += 1
                body = data[start:]
                self.send_response(206 if start else 200)
                if start:
                    self.send_header('Content-Range', f'bytes {start}-{BIG - 1}/{BIG}')
                self.send_header('Content-Type', 'application/vnd.android.package-archive')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                # About 160 KB/s; a first request is cut half way, as a lost connection would.
                cut = len(body) // 2 if not start else None
                sent = 0
                try:
                    while sent < len(body):
                        if cut is not None and sent >= cut:
                            self.close_connection = True
                            self.connection.shutdown(socket.SHUT_RDWR)
                            return
                        chunk = body[sent:sent + 16384]
                        self.wfile.write(chunk)
                        sent += len(chunk)
                        time.sleep(.1)
                except OSError:
                    self.close_connection = True

            def repository(self):
                if self.path.split('/')[1] != 'mods.git':
                    self.send_error(404)
                    return
                body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                if self.command == 'GET' and 'info/refs' in self.path:
                    outer.refreshes += 1
                    if outer.churning:
                        outer.source.churn()
                        time.sleep(3)
                with outer.source.lock:
                    headers, payload = outer.git.cgi(outer.source.repo, self.command, self.path, self.headers, body)
                self.send_response(int(headers.pop('Status', '200').split()[0]))
                for name, value in headers.items():
                    self.send_header(name, value)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            do_POST = repository

        cert, key = rooted_source.certificate(self.work)
        self.httpd = http.server.ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
        self.httpd.daemon_threads = True
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        self.httpd.socket = context.wrap_socket(self.httpd.socket, server_side=True)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()


# ----------------------------------------------------------------------------
# The phone
# ----------------------------------------------------------------------------

GONE = ('not found', 'offline', 'no devices', 'device unauthorized', 'closed')


class Phone:
    def __init__(self, adb='adb', grace=180, max_drops=5):
        self.prefix = [adb, '-s', SERIAL]
        self.grace = grace          # seconds to wait for the phone to come back after it drops off adb
        self.drops = []             # (time, seconds away)
        self.max_drops = max_drops  # more than this, and the run stops: the link is too unreliable

    def call(self, *args, timeout=60, check=True, retry=True):
        try:
            r = subprocess.run(self.prefix + list(args), capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as e:
            raise SoakError(f'adb {" ".join(args)[:80]} timed out') from e
        said = (r.stderr + r.stdout).decode(errors='replace')
        # 255: adb's own failure (the transport went), not the command's status.
        if r.returncode and retry and self.grace and (r.returncode == 255 or any(g in said for g in GONE)):
            self.reconnect()
            return self.call(*args, timeout=timeout, check=check, retry=False)
        if check and r.returncode:
            raise SoakError(f'adb {" ".join(args)[:80]} failed: {(r.stderr or r.stdout).decode(errors="replace")[:300]}')
        return r.stdout.decode(errors='replace')

    def reconnect(self):
        """The phone dropped off adb: wait for it (booted), and put back the reverse port; noted in `drops`."""
        if len(self.drops) >= self.max_drops:
            raise SoakError(f'{SERIAL} left adb a {len(self.drops) + 1}th time (more than {self.max_drops}): stopped')
        start = time.time()
        while time.time() - start < self.grace:
            try:
                r = subprocess.run(self.prefix + ['shell', 'getprop sys.boot_completed'], capture_output=True, timeout=30)
            except subprocess.TimeoutExpired:
                continue
            if r.returncode == 0 and r.stdout.strip() == b'1':
                subprocess.run(self.prefix + ['reverse', f'tcp:{PORT}', f'tcp:{PORT}'], capture_output=True, timeout=30)
                self.drops.append((time.strftime('%H:%M:%S'), round(time.time() - start)))
                print(f'phone back on adb after {round(time.time() - start)} s', flush=True)
                return
            time.sleep(3)
        raise SoakError(f'{SERIAL} left adb and did not come back within {self.grace} s')

    def sh(self, script, timeout=120):
        return self.call('shell', script, timeout=timeout)

    def keys(self, plan):
        """`plan`: [(key, seconds to wait after it)]; one adb call for the lot."""
        parts = ['k(){ sendevent /dev/input/event1 1 $1 1; sendevent /dev/input/event1 0 0 0; '
                 'sendevent /dev/input/event1 1 $1 0; sendevent /dev/input/event1 0 0 0; }']
        for key, wait in plan:
            parts.append(f'k {KEYS[key]}')
            if wait:
                parts.append(f'sleep {wait}')
        self.sh('; '.join(parts), timeout=60 + sum(w for _, w in plan))

    def battery(self):
        """`dumpsys battery`: level (%), temperature (tenths of a degree C), whether it is plugged in."""
        out = self.sh('dumpsys battery')
        b = {}
        for key, name in (('level', 'level'), ('temperature', 'temp'), ('status', 'status')):
            m = re.search(rf'^\s*{key}:\s*(-?\d+)', out, re.M)
            if m:
                b[name] = int(m[1])
        b['plugged'] = bool(re.search(r'^\s*(AC|USB|Wireless) powered:\s*true', out, re.M))
        return b

    def screen(self, on):
        """Wakes the screen (and dismisses a keyguard without a lock), or turns it off."""
        self.sh('input keyevent 224; wm dismiss-keyguard; true' if on else 'input keyevent 223')

    def pid(self):
        return self.sh(f'pidof {PACKAGE}').strip()

    def focused(self):
        out = self.sh('dumpsys window windows | grep -E "mCurrentFocus|mFocusedApp"')
        return out

    def wave_in_front(self):
        return run.focused_on_wave(self.sh('dumpsys window windows'))

    def texts(self):
        path = '/data/local/tmp/wave-soak.xml'
        for _ in range(4):
            out = self.call('shell', f'uiautomator dump {path} >/dev/null 2>&1; cat {path}; rm -f {path}', check=False)
            try:
                import xml.etree.ElementTree as ET
                tree = ET.fromstring(out[out.index('<?xml'):] if '<?xml' in out else out)
                return [n.get('text', '') or n.get('content-desc', '') for n in tree.iter('node')
                        if n.get('package') == PACKAGE and (n.get('text') or n.get('content-desc'))]
            except (ValueError, Exception):  # noqa: BLE001 - uiautomator sometimes returns nothing while spinners turn
                time.sleep(1)
        return []

    def dump_heap(self, local):
        """`am dumpheap` of Wave (a debug build), pulled to `local`."""
        remote = '/data/local/tmp/wave-soak.hprof'
        self.sh(f'rm -f {remote}; am dumpheap {PACKAGE} {remote}')
        last, still = -1, 0
        for _ in range(60):
            time.sleep(1)
            size = self.sh(f'stat -c %s {remote} 2>/dev/null || echo -1').strip()
            size = int(size) if size.lstrip('-').isdigit() else -1
            still = still + 1 if size == last and size > 0 else 0
            last = size
            if still >= 3:
                break
        self.call('pull', remote, str(local), timeout=120)
        self.sh(f'rm -f {remote}')

    def launch(self):
        self.sh(f'am start -W -n {ACTIVITY} >/dev/null')

    def sample(self, pid):
        """Collect garbage, then read meminfo, threads and descriptors of `pid`."""
        out = self.sh(f'run-as {PACKAGE} kill -10 {pid}; sleep 2; run-as {PACKAGE} kill -10 {pid}; sleep 2; '
                      f'echo "@@status"; run-as {PACKAGE} cat /proc/{pid}/status; '
                      f'echo "@@fds $(run-as {PACKAGE} ls /proc/{pid}/fd | wc -l)"; '
                      f'echo "@@meminfo"; dumpsys meminfo {pid}', timeout=90)
        return parse_sample(out)


def parse_sample(text):
    """The numbers soak.py follows, from its sample command's output (see Phone.sample)."""
    s = {}
    status = re.search(r'@@status\n(.*?)@@fds', text, re.S)
    if status:
        for key, name in (('Threads', 'threads'), ('VmRSS', 'rss_kb')):
            m = re.search(rf'^{key}:\s+(\d+)', status[1], re.M)
            if m:
                s[name] = int(m[1])
    m = re.search(r'@@fds (\d+)', text)
    if m:
        s['fds'] = int(m[1])
    mem = text.split('@@meminfo', 1)[-1]
    for label, prefix in (('Native Heap', 'native'), ('Dalvik Heap', 'java')):
        m = re.search(rf'^\s*{label}\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)', mem, re.M)
        if m:
            s[f'{prefix}_size_kb'] = int(m[5])
            s[f'{prefix}_alloc_kb'] = int(m[6])
    m = re.search(r'^\s*TOTAL\s+(\d+)\s+(\d+)\s+(\d+)', mem, re.M)
    if m:
        s['pss_kb'] = int(m[1])
        s['uss_kb'] = int(m[2]) + int(m[3])
    m = re.search(r'^\s*Graphics:\s+(\d+)', mem, re.M)
    if m:
        s['graphics_kb'] = int(m[1])
    objects = mem.split(' Objects', 1)[-1] if ' Objects' in mem else ''
    for label, name in (('Views', 'views'), ('ViewRootImpl', 'viewroots'), ('Activities', 'activities'),
                        ('AppContexts', 'appcontexts'), ('Assets', 'assets'), ('Local Binders', 'local_binders'),
                        ('Proxy Binders', 'proxy_binders'), ('Death Recipients', 'death_recipients')):
        m = re.search(rf'\b{label}:\s+(\d+)', objects)
        if m:
            s[name] = int(m[1])
    return s


# ----------------------------------------------------------------------------
# The loops
# ----------------------------------------------------------------------------

M, S = .3, .7   # seconds after a move; after a key that opens or closes a screen


def home_keys():
    """From anywhere on Home: its top (Soak Download), the next package's details, back."""
    return [('UP', M), ('UP', M), ('UP', M), ('UP', M), ('DOWN', M), ('OK', S), ('BACK', S)]


def sources_keys(refresh):
    """Home to Sources; Refresh from the source's menu when `refresh`; the source, Applications, a package, back out.

    Sources keeps its focus between visits, and the soak source is its last row, so Down
    always lands on it. With the source refreshing, its menu starts with Copy Address
    instead, which is harmless."""
    keys = [('RIGHT', S), ('DOWN', M)]
    if refresh:
        keys += [('MENU', S), ('OK', S)]
    return keys + [('OK', S), ('DOWN', M), ('OK', S), ('DOWN', M), ('DOWN', M), ('DOWN', M), ('OK', S),
                   ('BACK', S), ('BACK', S), ('BACK', S), ('RIGHT', S), ('RIGHT', S), ('UP', M), ('UP', M), ('UP', M)]


def search_keys():
    """On Search with the query typed: the second result's details, back, then Right to Home."""
    return [('DOWN', M), ('DOWN', M), ('OK', S), ('BACK', S), ('RIGHT', S)]


class Soak:
    def __init__(self, phone, server, out, every, check_every, battery_stop=30):
        self.phone = phone
        self.battery_stop = battery_stop
        self.battery = []           # (phase, cycle, level, temp)
        self.server = server
        self.out = out
        self.every = every
        self.check_every = check_every
        self.rows = []
        self.events = []
        self.cycle = 0
        self.pid = None
        self.csv = None

    def note(self, text):
        line = f'{time.strftime("%H:%M:%S")} [{self.cycle}] {text}'
        self.events.append(line)
        print(line, flush=True)

    def record(self, phase, n):
        pid = self.phone.pid()
        if not pid:
            self.note('Wave is not running (killed, or the phone restarted): launching it')
            self.phone.launch()
            time.sleep(8)
            pid = self.phone.pid()
        if not pid:
            raise SoakError('Wave is not running.')
        if self.pid and pid != self.pid:
            self.note(f'Wave\'s process changed ({self.pid} -> {pid}): the numbers restart here')
        self.pid = pid
        sample = self.phone.sample(pid)
        row = {'phase': phase, 'cycle': n, 'time': round(time.time()), 'pid': pid,
               **{k: sample.get(k, '') for k in METRICS},
               'battery': self.battery[-1][2] if self.battery else '', 'battery_temp': self.battery[-1][3] if self.battery else '',
               'adb_drops': len(self.phone.drops)}
        self.rows.append(row)
        if self.csv is None:
            self.csv_file = open(self.out / 'samples.csv', 'w', newline='')
            self.csv = csv.DictWriter(self.csv_file, fieldnames=list(row))
            self.csv.writeheader()
        self.csv.writerow(row)
        self.csv_file.flush()
        print(f'  {phase} {n}: pss {row["pss_kb"]} KB, java {row["java_alloc_kb"]} KB, native {row["native_alloc_kb"]} '
              f'KB, views {row["views"]}, activities {row["activities"]}, threads {row["threads"]}, fds {row["fds"]}',
              flush=True)

    def heap(self, label):
        """A heap dump now, and what holds each WaveActivity in it (tools/qa/hprof.py): heap-LABEL.txt."""
        import hprof
        local = self.out / f'heap-{label}.hprof'
        try:
            self.phone.dump_heap(local)
            heap = hprof.Heap(local)
        except (SoakError, OSError, ValueError) as e:
            self.note(f'heap dump {label} failed: {e}')
            return
        counts = heap.count('com.wave.xp3')
        lines = [hprof.why(heap, cls, 10) for cls in ('com.wave.xp3.app.WaveActivity',)]
        lines += ['', 'instances:'] + [f'{n:7d}  {name}' for name, n in sorted(counts.items(), key=lambda kv: -kv[1])[:60]]
        (self.out / f'heap-{label}.txt').write_text('\n'.join(lines) + '\n')
        n = counts.get('com.wave.xp3.app.WaveActivity', 0)
        self.note(f'heap {label}: {n} WaveActivity, {sum(counts.values())} com.wave.xp3 objects (heap-{label}.txt)')

    def home(self):
        """Back to Home's top in a fresh Activity in the same process (counted)."""
        self.note('back to Home: Back until Wave closes, then launch')
        for _ in range(8):
            if not self.phone.wave_in_front():
                break
            self.phone.keys([('BACK', .7)])
        self.phone.launch()
        time.sleep(2.5)

    def at_home(self):
        texts = self.phone.texts()
        return bool(texts) and texts[0] == 'Home' and 'Details' not in texts[:3]

    def search(self):
        """On Search, in its field: clear it and type the query (the same in each walk)."""
        self.phone.sh('input keyevent 67 67 67 67 67 67 67 67; input text soak')
        time.sleep(1)

    def walk(self, churn=False):
        self.phone.keys(home_keys() + sources_keys(churn))
        self.search()
        self.phone.keys(search_keys())

    def download(self):
        """Home's first featured package is Soak Download: GET, Confirm, Back mid-way, back again, its failure."""
        self.phone.keys([('UP', M), ('UP', M), ('UP', M), ('UP', M), ('OK', 1.5)])
        texts = ' '.join(self.phone.texts())
        if 'Soak Download' not in texts or 'GET' not in texts:
            self.note('download: details not as expected, skipped: ' + texts[:200])
            self.phone.keys([('BACK', S)])
            return
        self.phone.keys([('OK', 2.5)])   # GET: the plan, then Confirm (focus on Confirm)
        if not any('Confirm' in t for t in self.phone.texts()):
            self.note('download: no Confirm, skipped')
            self.home()
            return
        self.phone.keys([('OK', 4)])     # Confirm: the queue, downloading
        self.phone.keys([('BACK', 2)])   # "Working in the background"
        self.phone.launch()
        deadline = time.time() + 60
        while time.time() < deadline:
            texts = ' '.join(self.phone.texts())
            if 'wasn\'t installed' in texts or "doesn't match" in texts:
                break
            time.sleep(3)
        else:
            self.note('download: no failure alert within 60 s')
        self.phone.keys([('OK', 1.5), ('OK', 1.5)])   # the alert's OK, then Done
        if not self.at_home():
            self.phone.keys([('BACK', S)])   # Done leaves the queue for the details

    def flip(self, n):
        if n % 5 == 4:
            self.phone.keys([('BACK', 1.5)])   # from Home's root: finish()
        else:
            self.phone.keys([('HOME', 1.5)])
        self.phone.launch()
        time.sleep(1)

    def guard(self, phase, n):
        """Every cycle: the battery. Under `battery_stop` the soak stops (and cleans up) before the phone does."""
        b = self.phone.battery()
        level = b.get('level')
        self.battery.append((phase, n, level, b.get('temp')))
        if level is not None and level < self.battery_stop:
            raise SoakError(f'battery at {level}% (under {self.battery_stop}%, '
                            f'{"plugged in" if b["plugged"] else "not plugged in"}): stopped before the phone runs out')

    def phase(self, name, cycles):
        self.note(f'phase {name}: {cycles} cycles')
        self.guard(name, 0)
        self.phone.screen(True)
        self.record(name, 0)
        for n in range(1, cycles + 1):
            self.cycle = n
            # The screen is on only while keys are pressed: off between cycles
            # and while sampling, so a long soak doesn't outrun the charger.
            self.guard(name, n)
            self.phone.screen(True)
            if name == 'flips':
                self.flip(n)
            elif name == 'churn' and n % 10 == 5:
                self.download()
            else:
                self.walk(churn=name == 'churn')
            if not self.phone.wave_in_front():
                self.note('Wave is not in front')
                self.home()
            elif self.check_every and n % self.check_every == 0 and name != 'flips' and not self.at_home():
                self.note('not on Home after the cycle: ' + ' | '.join(self.phone.texts())[:200])
                self.home()
            self.phone.screen(False)
            if n % self.every == 0 or n == cycles:
                self.record(name, n)
        self.heap(name)


# ----------------------------------------------------------------------------
# The report
# ----------------------------------------------------------------------------

def slope(points):
    """Least-squares slope of [(x, y)]."""
    if len(points) < 2:
        return 0.0
    mx = sum(x for x, _ in points) / len(points)
    my = sum(y for _, y in points) / len(points)
    var = sum((x - mx) ** 2 for x, _ in points)
    return sum((x - mx) * (y - my) for x, y in points) / var if var else 0.0


def summarise(rows):
    """Per phase and metric: first, after warm-up, last, max, and the slope per 100 cycles over the second half."""
    out = {}
    for phase in dict.fromkeys(r['phase'] for r in rows):
        mine = [r for r in rows if r['phase'] == phase]
        table = {}
        for m in METRICS:
            pts = [(int(r['cycle']), float(r[m])) for r in mine if r.get(m) not in ('', None)]
            if not pts:
                continue
            warm = pts[min(len(pts) - 1, max(1, len(pts) // 5))] if len(pts) > 2 else pts[0]
            half = [p for p in pts if p[0] >= pts[-1][0] / 2]
            table[m] = {'first': pts[0][1], 'warm': warm[1], 'last': pts[-1][1], 'max': max(y for _, y in pts),
                        'slope100': slope(half) * 100}
        out[phase] = table
    return out


def write_report(out, meta, rows, events):
    summary = summarise(rows)
    lines = [f'# Wave soak, {meta["started"]}', '']
    for k, v in meta.items():
        lines.append(f'- {k}: {v}')
    for phase, table in summary.items():
        lines += ['', f'## {phase}', '', '| metric | first | after warm-up | last | max | second half, per 100 cycles |',
                  '|---|---|---|---|---|---|']
        for m, t in table.items():
            lines.append(f'| {m} | {t["first"]:.0f} | {t["warm"]:.0f} | {t["last"]:.0f} | {t["max"]:.0f} | '
                         f'{t["slope100"]:+.1f} |')
    lines += ['', '## Events', ''] + [f'- {e}' for e in events or ['none']]
    (out / 'report.md').write_text('\n'.join(lines) + '\n')
    (out / 'summary.json').write_text(json.dumps(summary, indent=1))
    return out / 'report.md'


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--commit', default='HEAD', help='build the APK from this commit (tools/qa/rooted/apk.py)')
    parser.add_argument('--apk', type=Path, help='an APK apk.py already built for --work')
    parser.add_argument('--work', type=Path, default=ROOT / '.oak/tmp/wave-qa/soak-work')
    parser.add_argument('--out', type=Path)
    parser.add_argument('--walks', type=int, default=300)
    parser.add_argument('--churn', type=int, default=200)
    parser.add_argument('--flips', type=int, default=300)
    parser.add_argument('--every', type=int, default=20, help='cycles between samples')
    parser.add_argument('--battery-start', type=int, default=50, help='refuse to start under this battery level (%%)')
    parser.add_argument('--battery-stop', type=int, default=30, help='stop and clean up under this level (%%)')
    parser.add_argument('--check-every', type=int, default=10, help='cycles between checks that the walk is on Home')
    args = parser.parse_args()
    if not os.environ.get('WAVE_PHONE_LOCK_TOKEN'):
        sys.exit('Run it through tools/qa/phone-run.sh (it holds the phone for the whole soak).')
    work = args.work.resolve()
    apk = args.apk
    if apk is None:
        import apk as apk_builder
        commit = subprocess.run(['git', '-C', str(ROOT), 'rev-parse', args.commit], capture_output=True, text=True,
                                check=True).stdout.strip()
        apk = apk_builder.build(commit, work)
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    out = (args.out or ROOT / f'.oak/tmp/wave-qa/soak-{stamp}').resolve()
    out.mkdir(parents=True, exist_ok=True)
    phone = Phone()
    if phone.call('get-state').strip() != 'device' or phone.sh('getprop sys.boot_completed').strip() != '1':
        raise SoakError(f'{SERIAL} must be attached and booted.')
    battery = phone.battery()
    if battery.get('level') is None or battery['level'] < args.battery_start:
        raise SoakError(f'REFUSED: the battery is at {battery.get("level")}%; a soak needs {args.battery_start}% to start '
                        f'(it stops itself under {args.battery_stop}%).')
    if phone.call('shell', f'pm path {PACKAGE}', check=False).strip():
        raise SoakError('Wave is already installed; the soak only uses a temporary install.')
    source = Source(work)
    source.build()
    server = Server(work, source)
    server.start()
    meta = {'started': stamp, 'apk': f'{apk} (SHA-256 {hashlib.sha256(Path(apk).read_bytes()).hexdigest()[:16]}…)',
            'cycles': f'walk {args.walks}, churn {args.churn}, flips {args.flips}; a sample every {args.every}'}
    soak = Soak(phone, server, out, args.every, args.check_every, args.battery_stop)
    installed = False
    try:
        phone.call('reverse', f'tcp:{PORT}', f'tcp:{PORT}')
        phone.call('install', str(apk), timeout=120)
        installed = True
        phone.screen(True)
        phone.launch()
        time.sleep(8)   # the first load and its refresh
        if args.walks:
            soak.phase('walk', args.walks)
        if args.churn:
            server.churning = True
            soak.phase('churn', args.churn)
            server.churning = False
        if args.flips:
            soak.phase('flips', args.flips)
        meta['refreshes served'] = server.refreshes
        meta['downloads started'] = server.downloads
        meta['source rounds'] = source.round
    except BaseException as e:
        meta['stopped by'] = f'{type(e).__name__}: {e}'
        raise
    finally:
        meta['finished'] = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        meta['adb drops (time, seconds away)'] = phone.drops or 'none'
        if soak.battery:
            levels = [b[2] for b in soak.battery if b[2] is not None]
            temps = [b[3] for b in soak.battery if b[3] is not None]
            meta['battery'] = (f'{levels[0]}% at the start, {levels[-1]}% at the end, lowest {min(levels)}%'
                               + (f'; up to {max(temps) / 10:.1f} °C' if temps else '')) if levels else 'unknown'
        phone.grace = 180
        phone.max_drops = len(phone.drops) + 2   # cleanup may still ride out a blip
        try:
            report = write_report(out, meta, soak.rows, soak.events)
            print('report:', report)
        finally:
            (out / 'server.log').write_text('\n'.join(server.requests) + '\n')
            server.stop()
            # Each step on its own: one failing (the phone gone) mustn't hide the others or the error above.
            steps = [('uninstall', PACKAGE)] if installed else []
            steps.append(('reverse', '--remove', f'tcp:{PORT}'))
            for step in steps:
                try:
                    phone.call(*step, check=False, timeout=60)
                except SoakError as e:
                    print(f'soak: cleanup step {step[0]} not done: {e}', file=sys.stderr)


if __name__ == '__main__':
    try:
        main()
    except SoakError as e:
        sys.exit(f'soak: {e}')
