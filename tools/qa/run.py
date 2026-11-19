#!/usr/bin/env python3
"""Run a checked d-pad scenario on the reserved Sonim XP3800."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
from html import escape
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid
from xml.etree import ElementTree as ET
from zipfile import ZipFile, BadZipFile

from pixels import WIDTH, HEIGHT, decode, difference

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SERIAL = '8516067e'
PACKAGE = 'com.wave.xp3'
ACTIVITIES = {'com.wave.xp3.MainActivity', 'com.wave.xp3.app.WaveActivity'}
FIXTURES = ('browse', 'reboot', 'errors', 'offline', 'uptodate', 'big', 'noroot')
FIXTURE_DESCRIPTOR = b'Lcom/wave/xp3/app/FakeWaveData;'
LOCK = Path(os.environ.get('PHONE_LOCK_DIR') or ROOT / '.oak' / 'phone.lock')
KEYS = {'DPAD_UP', 'DPAD_DOWN', 'DPAD_LEFT', 'DPAD_RIGHT', 'ENTER', 'BACK', 'MENU'}
REGION = (0, 25, WIDTH, 270)  # Omit status/navigation bars. Override for finer focus checks.
STEP_FIELDS = {'action', 'key', 'name', 'wait_ms', 'before_text', 'text_all', 'text_any',
               'changed', 'focus_moved', 'region', 'min_diff', 'reference', 'max_diff', 'focus_on', 'text_none',
               'fixture_confirm', 'repeat'}


class HarnessError(RuntimeError):
    pass


class CheckFailed(RuntimeError):
    pass


def read_scenario(path):
    doc = json.loads(path.read_text(encoding='utf-8'))
    if (not isinstance(doc, dict) or not {'name', 'steps'} <= set(doc) <= {'name', 'steps', 'fixture', 'query'}
            or not isinstance(doc['name'], str)):
        raise HarnessError('Scenario needs a name and steps array, and may add fixture and query.')
    if 'fixture' in doc and doc['fixture'] not in FIXTURES:
        raise HarnessError('Scenario fixture must be one of: ' + ', '.join(FIXTURES) + '.')
    if 'query' in doc and (not isinstance(doc['query'], str) or 'fixture' not in doc
                           or not re.fullmatch('[a-zA-Z0-9]{1,24}', doc['query'])):
        raise HarnessError('Scenario query needs a fixture and at most 24 letters or digits.')
    steps = doc['steps']
    if not isinstance(steps, list) or not 1 <= len(steps) <= 60:
        raise HarnessError('A scenario needs 1 to 60 steps.')
    for n, step in enumerate(steps):
        if not isinstance(step, dict) or set(step) - STEP_FIELDS or not isinstance(step.get('name'), str):
            raise HarnessError(f'Step {n + 1}: invalid fields or missing name.')
        if n == 0 and step.get('action') != 'launch':
            raise HarnessError('The first step must launch Wave.')
        if 'action' in step and ('key' in step or step['action'] not in ({'launch'} if n == 0 else {'capture'})):
            raise HarnessError(f'Step {n + 1}: expected launch first, then a key or capture.')
        if 'action' not in step and step.get('key') not in KEYS:
            raise HarnessError(f'Step {n + 1} ({step["name"]}): unknown key {step.get("key")!r}. '
                               'Accepted: ' + ', '.join(sorted(KEYS)) + '.')
        for field in ('before_text', 'text_all', 'text_any', 'text_none'):
            if field in step and (not isinstance(step[field], list) or not step[field]
                                  or not all(isinstance(s, str) and s.strip() for s in step[field])):
                raise HarnessError(f'Step {n + 1}: {field} must be a nonempty array of strings.')
        if 'repeat' in step and (type(step['repeat']) is not int or not 1 <= step['repeat'] <= 600
                                 or step.get('key') not in {'DPAD_UP', 'DPAD_DOWN', 'DPAD_LEFT', 'DPAD_RIGHT'}):
            raise HarnessError(f'Step {n + 1}: repeat is 1 to 600 and only for a d-pad direction key.')
        if 'focus_on' in step and (not isinstance(step['focus_on'], str) or not step['focus_on'].strip()):
            raise HarnessError(f'Step {n + 1}: focus_on must be a nonempty string.')
        if 'fixture_confirm' in step and (step['fixture_confirm'] is not True or 'fixture' not in doc
                                          or step.get('key') != 'ENTER'):
            raise HarnessError(f'Step {n + 1}: fixture_confirm is only for an ENTER step in a debug fixture scenario.')
        if step.get('key') == 'ENTER' and 'before_text' not in step:
            raise HarnessError(f'Step {n + 1}: ENTER requires before_text.')
        if any(field in step and type(step[field]) is not bool for field in ('changed', 'focus_moved')):
            raise HarnessError(f'Step {n + 1}: changed and focus_moved must be booleans.')
        if step.get('focus_moved') is False:
            raise HarnessError(f'Step {n + 1}: omit focus_moved unless checking for movement.')
        if n == 0 and any(field in step for field in ('before_text', 'changed', 'focus_moved')):
            raise HarnessError('The launch step has no previous screen.')
        wait = step.get('wait_ms', 350)
        if type(wait) is not int or not 0 <= wait <= 15000:
            raise HarnessError(f'Step {n + 1}: wait_ms must be from 0 to 15000.')
        for key, value in (('min_diff', .003), ('max_diff', .005)):
            threshold = step.get(key, value)
            if type(threshold) not in (float, int) or not 0 < threshold <= 1:
                raise HarnessError(f'Step {n + 1}: {key} must be above 0 and at most 1.')
        region = step.get('region', REGION)
        if not isinstance(region, (list, tuple)) or len(region) != 4 or any(type(k) is not int for k in region):
            raise HarnessError(f'Step {n + 1}: region must be [x, y, width, height].')
        x, y, w, h = region
        if min(x, y) < 0 or min(w, h) < 1 or x + w > WIDTH or y + h > HEIGHT:
            raise HarnessError(f'Step {n + 1}: region outside the 240x320 screen.')
        if 'reference' in step:
            relative = Path(step['reference'])
            reference = (path.parent / relative).resolve(strict=True)
            if relative.is_absolute() or not reference.is_relative_to(path.parent) or reference.suffix != '.png':
                raise HarnessError(f'Step {n + 1}: reference must be a PNG beside or below the scenario.')
            step['_reference_rgb'] = decode(reference.read_bytes())
    return doc


def command(args, timeout=12, check=True):
    try:
        result = subprocess.run(args, capture_output=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as e:
        raise HarnessError(f'{Path(args[0]).name} timed out after {timeout}s') from e
    if check and result.returncode:
        detail = (result.stderr or result.stdout).decode(errors='replace').strip()[:500]
        raise HarnessError(f'{Path(args[0]).name} command failed: {detail or result.returncode}')
    return result


def inspect_apk(path, fixture):
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '/opt/android-sdk')
    tools = sorted(sdk.glob('build-tools/*/aapt'), reverse=True)
    aapt = shutil.which('aapt') or next((str(candidate) for candidate in tools if os.access(candidate, os.X_OK)), None)
    if not aapt or not path.is_file():
        raise HarnessError('Need Android build-tools aapt and a built Wave APK.')
    text = command([aapt, 'dump', 'badging', str(path)]).stdout.decode(errors='replace')
    metadata = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", text, re.M)
    if not metadata or metadata[1] != PACKAGE:
        raise HarnessError(f'APK must be {PACKAGE}; nothing sent to the phone.')
    launchable = re.search(r"^launchable-activity: name='([^']+)'", text, re.M)
    if not launchable or launchable[1] not in ACTIVITIES:
        raise HarnessError('APK has no recognized Wave launcher activity.')
    if fixture:
        if launchable[1] != 'com.wave.xp3.app.WaveActivity' or 'application-debuggable' not in text.splitlines():
            raise HarnessError('Fixtures require a debuggable WaveActivity build.')
        try:
            with ZipFile(path) as archive:
                has_fake = any(re.fullmatch(r'classes[0-9]*\.dex', entry.filename)
                               and entry.file_size <= 16 * 1024 * 1024
                               and FIXTURE_DESCRIPTOR in archive.read(entry)
                               for entry in archive.infolist())
        except BadZipFile as e:
            raise HarnessError('Could not inspect the fixture APK.') from e
        if not has_fake:
            raise HarnessError('Fixture provider missing from the APK. No real plans are exercised in fixture mode.')
    with path.open('rb') as apk:
        digest = hashlib.file_digest(apk, 'sha256').hexdigest()
    return f'{metadata[3]} (code {metadata[2]}), SHA-256 {digest}', PACKAGE + '/' + launchable[1]


@contextmanager
def phone_lock(wait=0):
    held = os.environ.get('WAVE_PHONE_LOCK_TOKEN')
    if held:
        # tools/qa/phone-run.sh took the lock for this process; trust it only
        # while its owner file still names that exact token.
        try:
            current = (LOCK / 'owner').read_text(encoding='utf-8').strip()
        except OSError:
            current = ''
        if current != held:
            raise HarnessError('The phone lock is no longer held by this phone-run session.')
        yield
        return
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + wait
    while True:
        try:
            LOCK.mkdir()
            break
        except FileExistsError as e:
            if time.monotonic() >= deadline:
                try:
                    holder = (LOCK / 'owner').read_text(encoding='utf-8').strip()
                except OSError:
                    holder = '(owner unavailable)'
                raise HarnessError(f'Phone reserved by {holder}. Retry when released.') from e
            time.sleep(5)
    owner = LOCK / 'owner'
    token = f'wave-qa pid={os.getpid()} {uuid.uuid4().hex}'
    try:
        owner.write_text(token + '\n', encoding='utf-8')
    except (OSError, KeyboardInterrupt):
        owner.unlink(missing_ok=True)
        LOCK.rmdir()
        raise
    try:
        yield
    finally:
        if owner.is_file() and owner.read_text(encoding='utf-8').strip() == token:
            owner.unlink()
            LOCK.rmdir()


class Device:
    def __init__(self, adb, activity, fixture, query):
        self.activity = activity
        self.fixture = fixture
        self.query = query
        self.prefix = [adb, '-s', SERIAL]
        self.marker = 'qa_' + uuid.uuid4().hex
        self.dump_path = '/data/local/tmp/wave-' + self.marker + '.xml'

    def call(self, *args, **kwargs):
        return command(self.prefix + list(args), **kwargs)

    def text(self, *args, **kwargs):
        return self.call(*args, **kwargs).stdout.decode(errors='replace').strip()

    def present(self):
        result = self.call('shell', 'pm', 'path', PACKAGE, check=False)
        if result.returncode not in (0, 1) or result.stderr or (result.stdout and not result.stdout.startswith(b'package:')):
            raise HarnessError('Could not determine whether Wave is installed.')
        return bool(result.stdout.strip())

    def check_device(self):
        if (self.text('get-state') != 'device' or self.text('shell', 'getprop', 'sys.boot_completed') != '1'
            or self.text('shell', 'getprop', 'ro.product.device') != 'XP3800'):
            raise HarnessError(f'{SERIAL} must be an authorized XP3800 with Android fully booted.')

    @contextmanager
    def monitor_health(self):
        # Stream instead of fetching at the end: the small device log buffers
        # can wrap even within one UI scenario. Only the health tags are kept.
        args = self.prefix + ['logcat', '-b', 'main', '-b', 'system', '-b', 'events', '-T', '100', '-v', 'brief',
                              'WaveQa:I', 'AndroidRuntime:E', 'ActivityManager:E', 'am_anr:I', 'am_crash:I', '*:S']
        with tempfile.TemporaryFile() as stream, subprocess.Popen(args, stdout=stream, stderr=subprocess.DEVNULL) as logs:
            def evidence():
                if logs.poll() is not None:
                    raise HarnessError('Logcat monitor stopped during the scenario; health is inconclusive.')
                stream.seek(0)
                return health_events(stream.read().decode(errors='replace'), self.marker)

            try:
                self.call('shell', 'log', '-p', 'i', '-t', 'WaveQa', self.marker)
                yield evidence
            finally:
                if logs.poll() is None:
                    logs.terminate()
                    try:
                        logs.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        logs.kill()

    def foreground(self):
        text = self.text('shell', 'dumpsys', 'window', 'windows')
        return focused_on_wave(text)

    def hierarchy(self):
        try:
            self.call('shell', 'uiautomator', 'dump', self.dump_path, timeout=10)
            xml = self.call('exec-out', 'cat', self.dump_path).stdout
            return ET.fromstring(xml)
        except ET.ParseError as e:
            raise HarnessError('uiautomator returned an unreadable hierarchy.') from e
        finally:
            self.call('shell', 'rm', '-f', self.dump_path, check=False, timeout=4)


def focused_on_wave(windows):
    current = re.search(r'(?m)^\s*mCurrentFocus\s*=\s*Window\{([a-f0-9]+)\s+[^\n]*}', windows)
    if not current:
        return False
    # PopupWindow has a generic title, e.g. PopupWindow:3a2, in mCurrentFocus.
    # Resolve that exact window entry rather than trusting mFocusedApp, which
    # may still point to Wave even while another app has shown an overlay.
    blocks = re.split(r'(?m)^\s*Window #\d+ Window\{([a-f0-9]+)\s+[^\n]*}[^\n]*\n', windows)
    for ident, details in zip(blocks[1::2], blocks[2::2]):
        if ident == current[1]:
            owner = re.search(r'\bpackage=(\S+)', details)
            if owner:
                return owner[1] == PACKAGE
    return bool(re.search(r'\bu\d+ ' + re.escape(PACKAGE) + r'/', current[0]))


def health_events(text, marker):
    start = re.search(r'(?m)^I/WaveQa\s*\(\s*\d+\): ' + re.escape(marker) + r'\s*$', text)
    if not start:
        raise HarnessError('Missing logcat start marker; crash/ANR monitoring is inconclusive.')
    pkg = re.escape(PACKAGE) + r'(?::[\w.]+)?'
    fatal_pids = set()
    evidence = set()
    for line in text[start.end():].splitlines():
        runtime = re.match(r'E/AndroidRuntime\s*\(\s*(\d+)\):\s*(.*)', line)
        if runtime:
            pid, message = runtime.groups()
            if message.startswith('FATAL EXCEPTION'):
                fatal_pids.add(pid)
            elif pid in fatal_pids and re.match(r'Process: ' + pkg + r',\s*PID: ' + pid + r'\b', message):
                evidence.add('FATAL EXCEPTION in Wave (PID ' + pid + ')')
        activity = re.match(r'E/ActivityManager\s*\(\s*\d+\):\s*ANR in ' + pkg + r'(?=\s|$)', line)
        event = re.match(r'[IWE]/(am_anr|am_crash)\s*\(\s*\d+\):\s*\[\d+,\s*(\d+),\s*' + pkg + r',', line)
        if activity or (event and event[1] == 'am_anr'):
            evidence.add('ANR in Wave')
        if event and event[1] == 'am_crash' and f'FATAL EXCEPTION in Wave (PID {event[2]})' not in evidence:
            evidence.add('Android reported a Wave crash (PID ' + event[2] + ')')
    return sorted(evidence)


def normalize(text):
    return ' '.join(text.casefold().split())


def wave_nodes(tree):
    return (node for node in tree.iter('node') if node.get('package') == PACKAGE)


def text_matches(tree, expected, every=True):
    texts = [normalize(node.get(key, '')) for node in wave_nodes(tree) for key in ('text', 'content-desc')]
    matches = (any(normalize(value) in actual for actual in texts) for value in expected)
    return all(matches) if every else any(matches)


def focused_labels(tree):
    """Normalized labels of every focused/selected Wave node and its descendants."""
    labels = []
    for node in wave_nodes(tree):
        if node.get('selected') == 'true' or node.get('focused') == 'true':
            for inner in node.iter('node'):
                labels.extend(normalize(inner.get(key, '')) for key in ('text', 'content-desc') if inner.get(key))
    return labels


def focus(tree):
    """Return selected/focused identities, excluding container-level ListView focus."""
    chosen = []
    for node in wave_nodes(tree):
        if node.get('selected') != 'true' and node.get('focused') != 'true':
            continue
        if len(node) and not any(node.get(key) for key in ('text', 'content-desc')):
            # A selected row may be a container; ListView focus itself is not a row.
            if node.get('selected') != 'true':
                continue
        chosen.append(tuple(node.get(key, '') for key in ('resource-id', 'text', 'content-desc', 'bounds')))
    return tuple(chosen)


def inspect_step(device, step, frame, previous, current, prior_focus):
    tree = None
    has_reference = '_reference_rgb' in step
    if any(field in step for field in ('text_all', 'text_any', 'text_none', 'focus_on', 'focus_moved')):
        try:
            tree = device.hierarchy()
        except HarnessError:
            if not has_reference:
                raise
            frame['checks'].append('Accessibility unavailable; checking the visual reference.')
    if 'changed' in step or step.get('focus_moved') or has_reference:
        region = step.get('region', REGION)
    if 'changed' in step or step.get('focus_moved'):
        diff = difference(previous, current, region)
        frame['checks'].append(f'{diff:.2%} of pixels changed in the comparison region.')
        changed = diff >= step.get('min_diff', .003)
        if 'changed' in step and changed != step['changed']:
            raise CheckFailed('Expected a visibly different screen.' if step['changed'] else 'Expected a stable screen.')
        if step.get('focus_moved'):
            now = focus(tree) if tree is not None else ()
            if prior_focus and now and prior_focus != now:
                frame['checks'].append('Accessible focus/selection changed.')
            elif changed:
                frame['checks'].append('Focus inferred from a visual change, not proven by accessibility.')
            else:
                raise CheckFailed('No accessible or visible focus movement.')
    if 'focus_on' in step:
        wanted = normalize(step['focus_on'])
        if tree is None or not any(wanted in label for label in focused_labels(tree)):
            raise CheckFailed(f'Expected focus on: {step["focus_on"]}')
        frame['checks'].append('focus_on: ' + step['focus_on'])
    if 'text_none' in step:
        if tree is None:
            raise CheckFailed('Cannot confirm absent text without the accessibility tree.')
        present = [text for text in step['text_none'] if text_matches(tree, [text])]
        if present:
            raise CheckFailed('Unexpected text: ' + ', '.join(present))
        frame['checks'].append('text_none: ' + ', '.join(step['text_none']))
    missing = []
    for key in ('text_all', 'text_any'):
        if key in step:
            if tree is not None and text_matches(tree, step[key], every=key == 'text_all'):
                frame['checks'].append(f'{key}: ' + ', '.join(step[key]))
            else:
                missing.extend(step[key])
    if has_reference:
        diff = difference(step['_reference_rgb'], current, region)
        if diff > step.get('max_diff', .005):
            raise CheckFailed(f'Visual reference mismatch: {diff:.2%} of cropped pixels differ.')
        frame['checks'].append(f'Visual reference matched ({diff:.2%} differ).')
        if missing:
            frame['checks'].append('Text was not exposed/matched; substitute visual reference, not OCR.')
    elif missing:
        raise CheckFailed('Expected accessible text: ' + ', '.join(missing))


def run_steps(device, scenario, output, frames):
    previous = None
    for i, step in enumerate(scenario['steps'], 1):
        action = step.get('key') or step['action']
        frame = {'name': step['name'], 'action': action, 'checks': [], 'status': 'incomplete'}
        if step.get('repeat', 1) > 1:
            frame['repeat'] = step['repeat']
        frames.append(frame)
        print(f'{i:02d} {action}: {step["name"]}', flush=True)
        if action != 'launch' and not device.foreground():
            raise CheckFailed('Wave is not foreground; no key sent.')
        before = None
        if 'before_text' in step or step.get('focus_moved'):
            try:
                before = device.hierarchy()
            except HarnessError:
                if 'before_text' in step:
                    raise
        if 'before_text' in step and not text_matches(before, step['before_text']):
            raise CheckFailed('Key guard failed; expected: ' + ', '.join(step['before_text']))
        if action == 'ENTER' and not (step.get('fixture_confirm') and device.fixture) and any(
                normalize(node.get(key, '')) in {'confirm', 'reboot now'}
                for node in wave_nodes(before) for key in ('text', 'content-desc')):
            raise CheckFailed('Test stopped before a visible Confirm or Reboot Now action.')
        if before is not None and not device.foreground():
            raise CheckFailed('Wave lost foreground while reading the hierarchy; no key sent.')
        if action == 'launch':
            extras = ('--es', 'fixture', device.fixture) if device.fixture else ()
            if device.query:
                extras += ('--es', 'query', device.query)
            reply = device.text('shell', 'am', 'start', '-W', '-n', device.activity, *extras, timeout=20)
            if not re.search(r'^Status: ok$', reply, re.M):
                raise CheckFailed('Activity did not report a successful launch.')
        elif action != 'capture':
            count = step.get('repeat', 1)
            device.call('shell', 'input', 'keyevent', *(['KEYCODE_' + action] * count), timeout=8 + count // 4)
        time.sleep(step.get('wait_ms', 350) / 1000)
        if not device.foreground():
            raise CheckFailed('Wave lost foreground. No screenshot of the owner’s other apps was taken.')
        try:
            png = device.call('exec-out', 'screencap', '-p').stdout
            current = decode(png)
        except ValueError as e:
            raise HarnessError(str(e)) from e
        frame['image'] = f'{i:02d}.png'
        (output / frame['image']).write_bytes(png)
        inspect_step(device, step, frame, previous, current, focus(before) if before is not None else ())
        frame['status'] = 'pass'
        previous = current


def device_run(device, apk, scenario, output, frames, notes, wait=0):
    with phone_lock(wait):
        device.check_device()
        if device.present():
            raise HarnessError('Wave is already installed. The harness never replaces an existing install.')
        with device.monitor_health() as collect_health:
            temporary_install(device, apk, scenario, output, frames, notes, collect_health)


def temporary_install(device, apk, scenario, output, frames, notes, collect_health):
    try:
        result = device.text('install', str(apk), timeout=30)
        if 'Success' not in result:
            raise HarnessError('Wave install did not report Success.')
        run_steps(device, scenario, output, frames)
    finally:
        try:
            if frames:
                # Logcat must have time to record a five-second input ANR.
                time.sleep(6)
            evidence = collect_health()
            notes.extend(evidence or ['No Wave FATAL EXCEPTION, recorded crash or ANR after the start marker.'])
        except HarnessError as e:
            evidence = None
            notes.append(str(e))
        finally:
            # The package was absent before this run. Never uninstall a pre-existing copy.
            if device.present():
                removed = device.text('uninstall', PACKAGE, timeout=20)
                if removed != 'Success' or device.present():
                    raise HarnessError('Temporary Wave install did not uninstall cleanly.')
                notes.append('Temporary Wave install removed.')
        if evidence is None:
            raise HarnessError('Health check inconclusive.')
        if evidence:
            raise CheckFailed('Crash or ANR detected; see health checks.')


def report(output, title, metadata, frames, notes, status):
    cards = []
    for frame in frames:
        image = (f'<img src="{escape(frame["image"])}" width="240" height="320" alt="XP3800 capture">'
                 if 'image' in frame else '<p>No Wave screen captured.</p>')
        checks = ''.join(f'<li>{escape(line)}</li>' for line in frame['checks'])
        label = 'launch' if frame['action'] == 'launch' else frame['action'] + (f' ×{frame["repeat"]}' if 'repeat' in frame else '')
        cards.append(f'<article><h2>{escape(frame["name"])}</h2><p><code>{escape(label)}</code> '
                     f'{escape(frame["status"])}</p>{image}<ul>{checks}</ul></article>')
    observations = ''.join(f'<li>{escape(note)}</li>' for note in notes)
    page = f'''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{escape(title)} | Wave device QA</title><style>
body{{font:16px/1.5 sans-serif;background:#f2f6fa;color:#182b40;margin:2rem}}h1,h2,p{{margin:.4rem 0}}
p{{overflow-wrap:anywhere}}main{{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(310px,100%),1fr));gap:1rem}}
article{{background:white;border:1px solid #acbac8;border-radius:8px;padding:1rem}}img{{max-width:100%;height:auto}}
</style><h1>{escape(title)}</h1><p><strong>{status}</strong> on {SERIAL}</p><p>{escape(metadata)}</p>
<h2>Health and run notes</h2><ul>{observations}</ul><main>{''.join(cards)}</main></html>
'''
    (output / 'index.html').write_text(page, encoding='utf-8')


def interrupt(_signal, _frame):
    raise KeyboardInterrupt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT / 'app/app/build/outputs/apk/release/app-release.apk')
    choice = parser.add_mutually_exclusive_group(required=True)
    choice.add_argument('--dry-run', action='store_true', help='run the non-installing in-app smoke path on the real handset')
    choice.add_argument('--scenario', type=Path, help='run a reviewed local JSON scenario')
    parser.add_argument('--fixture', choices=FIXTURES, help='use mock data in a debug-only WaveActivity build')
    parser.add_argument('--fixture-query', help='preload a synthetic Search query (debug fixture only)')
    parser.add_argument('--wait-lock', type=int, default=0, metavar='SECONDS',
                        help='wait this long for another agent to release the phone lock (default: fail at once)')
    parser.add_argument('--out', type=Path, help='new report folder (default .oak/tmp/wave-qa/<UTC>_<id>)')
    args = parser.parse_args()
    if args.fixture and not args.scenario:
        parser.error('--fixture requires an explicit --scenario')
    if args.fixture_query and not re.fullmatch('[a-zA-Z0-9]{1,24}', args.fixture_query):
        parser.error('--fixture-query takes at most 24 letters/digits')
    if not 0 <= args.wait_lock <= 3600:
        parser.error('--wait-lock must be 0 to 3600 seconds')
    path = args.scenario.resolve() if args.scenario else None
    run_id = f'{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}_{uuid.uuid4().hex[:8]}'
    output = (args.out or ROOT / '.oak/tmp/wave-qa' / run_id).resolve()
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    status, metadata = 'PASS', 'APK not inspected'
    frames, notes = [], []
    title = path.name if path else 'Wave device smoke'
    signal.signal(signal.SIGTERM, interrupt)
    try:
        adb = shutil.which('adb')
        if not adb:
            raise HarnessError('adb not found on PATH.')
        scenario = read_scenario(path) if path else None
        fixture = args.fixture or (scenario or {}).get('fixture')
        query = args.fixture_query or (scenario or {}).get('query')
        if query and not fixture:
            raise HarnessError('A seeded query needs a debug fixture.')
        # A sibling build can replace Gradle's artifact while a run is in flight.
        # Inspect and install the same private snapshot; never retain it in the report.
        with tempfile.TemporaryDirectory(prefix='apk-', dir=output) as temp:
            apk = Path(temp) / 'wave.apk'
            shutil.copyfile(args.apk.resolve(strict=True), apk)
            metadata, activity = inspect_apk(apk, fixture)
            if scenario is None:
                path = HERE / 'scenarios' / ('07-launch.json' if activity.endswith('.WaveActivity') else 'foundation-smoke.json')
                scenario = read_scenario(path)
            title = scenario['name']
            if fixture:
                metadata += f', debug fixture {fixture}'
            if query:
                metadata += ', seeded search query ' + query
            device_run(Device(adb, activity, fixture, query), apk, scenario, output, frames, notes, args.wait_lock)
    except CheckFailed as e:
        status = 'FAIL'
        notes.append(str(e))
    except (HarnessError, ValueError, OSError, TypeError, KeyboardInterrupt) as e:
        status = 'INCOMPLETE'
        notes.append(str(e) or 'Interrupted.')
    if frames and frames[-1]['status'] == 'incomplete' and status == 'FAIL':
        frames[-1]['status'] = 'fail'
    report(output, title, metadata, frames, notes, status)
    print(f'{status}: {output / "index.html"}')
    for line in notes:
        print(line)
    return {'PASS': 0, 'FAIL': 1, 'INCOMPLETE': 2}[status]


if __name__ == '__main__':
    sys.exit(main())
