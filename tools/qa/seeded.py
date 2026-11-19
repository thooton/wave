#!/usr/bin/env python3
"""Run a d-pad scenario against a debug build whose private files were seeded first.

Some of what Wave must survive lives on its own disk: a damaged sources.json, a cached source
whose icons are not images, a source that asks for a sign-in. `run.py` cannot arrange that, so this
wrapper does what `run.py` does (same lock, same checks, same report, temporary install only) and
in between writes files with `run-as`, which only a debuggable build allows. Nothing outside
com.wave.xp3's own files is touched, and no root is used.

    python3 tools/qa/seeded.py --apk app/app/build/outputs/apk/debug/app-debug.apk \
        tools/qa/seeded/damaged-list.json

A seeded scenario is JSON: {"name", "seed": {relative path: text}, "builder": optional name from
BUILDERS below that adds generated files, "expect": [relative paths that must exist after the run],
"steps": [...]} where steps are exactly those of `run.py` scenarios.
"""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run  # noqa: E402

PATH = re.compile(r'files/(sources\.json|sources(/[A-Za-z0-9._-]{1,64}){1,6})')
MAX_FILE = 70 * 1024


def source_id(url):
    return hashlib.sha256(url.encode()).hexdigest()


def png(side, pixels=b'\x00'):
    """A well-formed PNG that declares side x side pixels in under a hundred bytes."""
    import struct
    import zlib

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', side, side, 1, 0, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b''))


def cached_and_signin():
    """A cached source whose icons are a GIF and a pixel bomb and that cannot be reached, and one that asks to sign in."""
    commit = 'a' * 40
    cached = 'https://qa-cache.invalid/source'
    private = 'https://github.com/thooton/wave-qa-no-such-private-source.git'
    root = run.ROOT / 'spec/example-source'
    package = json.loads((root / 'applications/list/package.json').read_text())
    package.update(id='com.qa.cached', name='QA Cached App', summary='Cached while its source is unreachable')
    header = json.loads((root / 'wave.json').read_text())
    header.update(name='QA Cached Source', sections={'applications': 'Applications'}, featured=['com.qa.cached'])
    tree = f'files/sources/{source_id(cached)}/tree/{commit}'
    return {
        'files/sources.json': json.dumps([
            {'url': cached, 'name': 'QA Cached Source', 'commit': commit, 'lastRefresh': 1700000000000},
            {'url': private, 'name': '', 'commit': '', 'lastRefresh': 0}]),
        f'{tree}/wave.json': json.dumps(header),
        f'{tree}/icon.png': b'GIF89a not a png',
        f'{tree}/applications/qa-app/package.json': json.dumps(package),
        f'{tree}/applications/qa-app/icon.png': png(65535),
    }


BUILDERS = {'cached-and-signin': cached_and_signin}


def read(path):
    doc = json.loads(path.read_text(encoding='utf-8'))
    allowed = {'name', 'seed', 'builder', 'expect', 'steps'}
    if not isinstance(doc, dict) or {'name', 'steps'} - set(doc) or set(doc) - allowed:
        raise run.HarnessError('A seeded scenario needs name and steps, and may add seed, builder and expect.')
    if doc.get('builder', 'cached-and-signin') not in BUILDERS:
        raise run.HarnessError('Unknown builder; known: ' + ', '.join(BUILDERS))
    files = dict(doc.get('seed', {}))
    if 'builder' in doc:
        files.update(BUILDERS[doc['builder']]())
    for rel, content in list(files.items()):
        data = content.encode() if isinstance(content, str) else content
        if not PATH.fullmatch(rel) or '..' in rel.split('/') or len(data) > MAX_FILE:
            raise run.HarnessError(f'Seed path or size not allowed: {rel}')
        files[rel] = data
    expect = doc.get('expect', [])
    if not all(isinstance(e, str) and PATH.fullmatch(e) for e in expect):
        raise run.HarnessError('expect must list files/sources.json or files/sources/... paths.')
    return doc, files, expect


def run_as(device, script, data=None, timeout=15):
    result = subprocess.run(device.prefix + ['shell', 'run-as', run.PACKAGE, 'sh', '-c', script],
                            input=data, capture_output=True, timeout=timeout)
    return result


def seed(device, files):
    for rel, data in files.items():
        folder = rel.rsplit('/', 1)[0]
        done = run_as(device, f"mkdir -p '{folder}' && cat > '{rel}'", data)
        if done.returncode or done.stderr:
            raise run.HarnessError(f'Could not seed {rel}: {done.stderr.decode(errors="replace")[:200]}')
        back = run_as(device, f"cat '{rel}'").stdout
        if back != data:
            raise run.HarnessError(f'Seeded file {rel} did not read back identically.')


def seeded_run(device, apk, doc, files, expect, output, frames, notes, wait):
    with run.phone_lock(wait):
        device.check_device()
        if device.present():
            raise run.HarnessError('Wave is already installed. The harness never replaces an existing install.')
        with device.monitor_health() as collect_health:
            try:
                if 'Success' not in device.text('install', str(apk), timeout=30):
                    raise run.HarnessError('Wave install did not report Success.')
                seed(device, files)
                notes.append(f'Seeded {len(files)} private file(s) with run-as: ' + ', '.join(sorted(files)))
                run.run_steps(device, doc, output, frames)
                for rel in expect:
                    found = run_as(device, f"test -e '{rel}' && echo yes").stdout.strip() == b'yes'
                    notes.append(f'{rel} {"exists" if found else "is missing"} after the run.')
                    if not found:
                        raise run.CheckFailed(f'Expected {rel} to exist after the run.')
            finally:
                try:
                    if frames:
                        time.sleep(6)  # An input ANR is logged five seconds after the key.
                    evidence = collect_health()
                    notes.extend(evidence or ['No Wave FATAL EXCEPTION, recorded crash or ANR after the start marker.'])
                except run.HarnessError as e:
                    evidence = None
                    notes.append(str(e))
                finally:
                    if device.present():
                        removed = device.text('uninstall', run.PACKAGE, timeout=20)
                        if removed != 'Success' or device.present():
                            raise run.HarnessError('Temporary Wave install did not uninstall cleanly.')
                        notes.append('Temporary Wave install removed.')
                if evidence is None:
                    raise run.HarnessError('Health check inconclusive.')
                if evidence:
                    raise run.CheckFailed('Crash or ANR detected; see health checks.')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('scenario', type=Path)
    parser.add_argument('--apk', type=Path, default=run.ROOT / 'app/app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--wait-lock', type=int, default=0, metavar='SECONDS')
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    output = (args.out or run.ROOT / '.oak/tmp/wave-qa' /
              f'{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}_{uuid.uuid4().hex[:8]}').resolve()
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    status, metadata, title = 'PASS', 'APK not inspected', args.scenario.name
    frames, notes = [], []
    try:
        adb = shutil.which('adb')
        if not adb:
            raise run.HarnessError('adb not found on PATH.')
        doc, files, expect = read(args.scenario)
        title = doc['name']
        # The runner validates steps, so give it the steps as a scenario file of its own kind.
        plain = output / 'steps.json'
        plain.write_text(json.dumps({'name': doc['name'], 'steps': doc['steps']}), encoding='utf-8')
        scenario = run.read_scenario(plain)
        with tempfile.TemporaryDirectory(prefix='apk-', dir=output) as temp:
            apk = Path(temp) / 'wave.apk'
            shutil.copyfile(args.apk.resolve(strict=True), apk)
            metadata, activity = run.inspect_apk(apk, None)
            badging = run.command([shutil.which('aapt') or sorted(Path('/opt/android-sdk').glob('build-tools/*/aapt'))[-1],
                                   'dump', 'badging', str(apk)]).stdout.decode(errors='replace')
            if 'application-debuggable' not in badging.splitlines():
                raise run.HarnessError('Seeding needs a debuggable build (run-as); nothing sent to the phone.')
            seeded_run(run.Device(adb, activity, None, None), apk, scenario, files, expect, output, frames, notes,
                       args.wait_lock)
    except run.CheckFailed as e:
        status = 'FAIL'
        notes.append(str(e))
    except (run.HarnessError, ValueError, OSError, TypeError, KeyboardInterrupt, subprocess.TimeoutExpired) as e:
        status = 'INCOMPLETE'
        notes.append(str(e) or 'Interrupted.')
    if frames and frames[-1]['status'] == 'incomplete' and status == 'FAIL':
        frames[-1]['status'] = 'fail'
    run.report(output, title, metadata, frames, notes, status)
    print(f'{status}: {output / "index.html"}')
    for line in notes:
        print(line)
    return {'PASS': 0, 'FAIL': 1, 'INCOMPLETE': 2}[status]


if __name__ == '__main__':
    sys.exit(main())
