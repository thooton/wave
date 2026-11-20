#!/usr/bin/env python3
"""Run one ROOTED scenario on the reserved, rooted Sonim XP3800.

    tools/qa/phone-run.sh --who "agentN rooted QA" -- python3 tools/qa/rooted/rooted.py \\
        --apk <rooted-QA APK from rooted/apk.py> --scenario tools/qa/rooted/scenarios/R02-install-mod.json

THIS CHANGES THE PHONE. It needs `su` (Magisk), installs Wave temporarily, answers or pre-sets Magisk's
grant for Wave, installs and removes the two test modules from rooted/source.py, and some scenarios
reboot the phone, one of them into a boot that the watchdog has to rescue (up to about 8 minutes).
It refuses to start on an unrooted phone, with an existing Wave, with a Magisk grant for any app but
the shell (uid 2000), or with leftovers from an earlier run. Whatever happens in between, it then
restores the phone to what it recorded first: the test modules, their markers, Wave's own state
files, the watchdog (if it wasn't there), Wave, and every Magisk grant that wasn't there. If the
phone does not come back, see "If the phone does not boot" in tools/qa/README.md.
Exit codes as run.py: 0 pass, 1 fail, 2 incomplete (including a restore that could not be verified).
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import uuid
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import run  # noqa: E402
from run import CheckFailed, Device, HarnessError, PACKAGE, ROOT, SERIAL  # noqa: E402

PORT = 4483
WORK = ROOT / '.oak/tmp/wave-qa/rooted-work'
DO_KINDS = {
    'su': {'cmd'}, 'wait_su': {'cmd', 'expect', 'timeout_s'}, 'pregrant': set(), 'answer_prompt': set(),
    'reverse': set(), 'mark_log': set(), 'health': set(), 'reboot': {'how'}, 'wait_boot': set(),
}
OPTIONAL = {'expect', 'expect_not', 'timeout_s', 'wait_ms'}
TEST_MODULES = ('wave_test_ok', 'wave_test_broken')
SHELL_UID = 2000
GRANT_MOVES = ['DOWN', 'DOWN', 'RIGHT', 'DOWN', 'RIGHT']


def check_do(step, number):
    kind = step['do']
    if kind not in DO_KINDS:
        raise HarnessError(f'Step {number}: unknown do {kind!r}. Known: ' + ', '.join(sorted(DO_KINDS)) + '.')
    given = set(step) - {'name', 'do'}
    if not DO_KINDS[kind] <= given or given - DO_KINDS[kind] - OPTIONAL:
        raise HarnessError(f'Step {number}: do {kind} takes ' + (', '.join(sorted(DO_KINDS[kind])) or 'no fields')
                           + ' and optionally ' + ', '.join(sorted(OPTIONAL)) + '.')
    for field in ('expect', 'expect_not'):
        if field in step:
            try:
                re.compile(step[field])
            except (re.error, TypeError) as e:
                raise HarnessError(f'Step {number}: {field} is not a regular expression.') from e
    if 'timeout_s' in step and (type(step['timeout_s']) is not int or not 1 <= step['timeout_s'] <= 1800):
        raise HarnessError(f'Step {number}: timeout_s is 1 to 1800.')
    if kind == 'reboot' and step['how'] != 'su':
        raise HarnessError(f'Step {number}: reboot how must be "su" (a clean restart); Wave\'s own Reboot Now is a UI step.')
    if kind in ('su', 'wait_su') and (not isinstance(step['cmd'], str) or not step['cmd'].strip()):
        raise HarnessError(f'Step {number}: cmd must be a nonempty string.')


class Rooted:
    """The rooted state: what the phone looked like first, the 'do' steps, and the restore."""

    def __init__(self, device, work, apk):
        self.device, self.work, self.apk = device, work, apk
        self.base = {}
        self.boot_id = ''
        self.logmark = 0
        self.marker = ''
        self.notes = []
        self.reboots = 0
        self.wave_uid = None
        self.prompted = False
        self.logs = None
        self.stream = None

    # adb helpers -----------------------------------------------------------------------------
    def su(self, cmd, timeout=40, check=True):
        result = self.device.call('shell', 'su -c ' + shlex.quote(cmd), timeout=timeout, check=False)
        out = (result.stdout + result.stderr).decode(errors='replace').strip()
        if check and result.returncode:
            raise CheckFailed(f'su -c {cmd!r} exited {result.returncode}: {out[:300]}')
        return out

    def prop(self, name):
        try:
            return self.device.text('shell', 'getprop', name, timeout=8)
        except HarnessError:
            return ''

    def reverse(self):
        self.device.call('reverse', f'tcp:{PORT}', f'tcp:{PORT}', timeout=10)

    def uids(self):
        out = self.su('magisk --sqlite "SELECT uid FROM policies ORDER BY uid"')
        return sorted(int(n) for n in re.findall(r'uid=(\d+)', out))

    def leftovers(self):
        return self.su('ls -d /data/adb/modules/wave_test_* /data/adb/modules_update/wave_test_* /data/adb/wave_test_* '
                       '/data/adb/wave/pending /data/adb/wave/trying 2>/dev/null; true')

    def wave_files(self):
        return set(self.su('ls -A /data/adb/wave 2>/dev/null; true').split())

    def watchdog_present(self):
        return self.su('ls /data/adb/post-fs-data.d/wave-watchdog.sh /data/adb/service.d/wave-watchdog.sh 2>/dev/null; true').count('wave-watchdog.sh') == 2

    # before ----------------------------------------------------------------------------------
    def baseline(self):
        id_out = self.su('id', check=False)
        if 'uid=0(root)' not in id_out:
            raise HarnessError('REFUSED: this phone is not rooted for adb (`su -c id` gave: ' + (id_out[:120] or 'nothing')
                               + '). The rooted suite only runs on a rooted phone; nothing was changed.')
        uids = self.uids()
        if set(uids) - {SHELL_UID}:
            raise HarnessError(f'REFUSED: Magisk already has grants for uid(s) {sorted(set(uids) - {SHELL_UID})}. The suite '
                               'restores the grant table to what it found and will not guess whose those are.')
        left = self.leftovers()
        if left:
            raise HarnessError('REFUSED: leftovers from an earlier run on the phone:\n' + left)
        level = re.search(r'level:\s*(\d+)', self.device.text('shell', 'dumpsys', 'battery'))
        if not level or int(level[1]) < 30:
            raise HarnessError('REFUSED: the battery is under 30%; a rooted run reboots the phone several times.')
        self.base = {'uids': uids, 'wave_files': self.wave_files(), 'watchdog': self.watchdog_present(),
                     'modules': sorted(self.su('ls /data/adb/modules 2>/dev/null; true').split())}
        self.boot_id = self.su('cat /proc/sys/kernel/random/boot_id')
        self.notes.append('Rooted run. Baseline: Magisk grants for uid ' + ', '.join(map(str, uids))
                          + f"; watchdog {'installed' if self.base['watchdog'] else 'absent'}; Magisk modules: "
                          + (', '.join(self.base['modules']) or 'none') + f'; battery {level[1]}%.')

    # steps -----------------------------------------------------------------------------------
    def start_epoch(self):
        """Stream the health tags from now on; the small log buffers wrap, and a reboot empties them."""
        self.stop_stream()
        self.marker = 'qa_' + uuid.uuid4().hex
        self.stream = tempfile.TemporaryFile()
        args = self.device.prefix + ['logcat', '-b', 'main', '-b', 'system', '-b', 'events', '-T', '1', '-v', 'brief', 'WaveQa:I',
                                     'AndroidRuntime:E', 'ActivityManager:E', 'am_anr:I', 'am_crash:I', '*:S']
        self.logs = subprocess.Popen(args, stdout=self.stream, stderr=subprocess.DEVNULL)
        time.sleep(1)
        self.device.call('shell', 'log', '-p', 'i', '-t', 'WaveQa', self.marker)

    def stop_stream(self):
        if self.logs and self.logs.poll() is None:
            self.logs.terminate()
            try:
                self.logs.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.logs.kill()
        self.logs = None

    def health(self):
        if not self.marker:
            return
        time.sleep(2)
        if self.logs is None or self.logs.poll() is not None:
            raise HarnessError('Logcat stream stopped; Wave crash/ANR monitoring is inconclusive.')
        self.stream.seek(0)
        events = run.health_events(self.stream.read().decode(errors='replace'), self.marker)
        self.stop_stream()
        self.stream.close()
        self.marker = ''
        if events:
            raise CheckFailed('Wave crashed or hung: ' + ', '.join(events))

    def doer(self, step, frame, output, index):
        kind = step['do']
        pause = step.get('wait_ms', 0) / 1000
        if kind == 'su':
            out = self.su(step['cmd'].replace('$LOGMARK', str(self.logmark)), check=False)
            self.expect(step, out, frame)
        elif kind == 'wait_su':
            deadline = time.monotonic() + step.get('timeout_s', 60)
            while True:
                out = self.su(step['cmd'].replace('$LOGMARK', str(self.logmark)), check=False)
                if re.search(step['expect'], out):
                    break
                if time.monotonic() > deadline:
                    raise CheckFailed(f'Timed out after {step.get("timeout_s", 60)} s waiting for /{step["expect"]}/; last output: {out[-300:]}')
                time.sleep(3)
            self.expect(step, out, frame)
        elif kind == 'pregrant':
            self.pregrant(frame)
        elif kind == 'answer_prompt':
            self.answer_prompt(frame, output, index)
        elif kind == 'reverse':
            self.reverse()
            frame['checks'].append(f'adb reverse tcp:{PORT} set.')
        elif kind == 'mark_log':
            self.logmark = int(self.su('wc -l < /data/adb/wave/log 2>/dev/null || echo 0').split()[0]) + 1
            frame['checks'].append(f'Watchdog log is {self.logmark - 1} lines so far.')
        elif kind == 'health':
            self.health()
            frame['checks'].append('No Wave crash or ANR since the last boot started.')
        elif kind == 'reboot':
            self.health()
            self.device.call('shell', 'su -c ' + shlex.quote('svc power reboot'), timeout=20, check=False)
            frame['checks'].append('Clean restart asked for (svc power reboot).')
        elif kind == 'wait_boot':
            self.wait_boot(step.get('timeout_s', 300), frame)
        time.sleep(pause)

    def expect(self, step, out, frame):
        if 'expect' in step:
            if not re.search(step['expect'], out):
                raise CheckFailed(f'Expected /{step["expect"]}/ in: {out[-400:] or "(no output)"}')
            frame['checks'].append(f'matched /{step["expect"]}/')
        if 'expect_not' in step:
            if re.search(step['expect_not'], out):
                raise CheckFailed(f'Did not expect /{step["expect_not"]}/ in: {out[-400:]}')
            frame['checks'].append(f'absent /{step["expect_not"]}/')
        if out:
            frame['checks'].append('output: ' + ' | '.join(out.splitlines()[-6:])[:300])

    def uid_of_wave(self):
        out = self.device.text('shell', 'dumpsys', 'package', PACKAGE, timeout=20)
        found = re.search(r'userId=(\d+)', out)
        if not found:
            raise HarnessError('Could not read the Wave uid.')
        return int(found[1])

    def pregrant(self, frame):
        uid = self.uid_of_wave()
        self.su(f'magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES ({uid},2,0,1,1)"')
        if uid not in self.uids():
            raise CheckFailed('Magisk did not take the grant.')
        frame['checks'].append(f'Magisk grant set for Wave (uid {uid}) through its sqlite; the d-pad prompt is R01\'s job.')

    def answer_prompt(self, frame, output, index):
        # Magisk's request window is not in uiautomator's tree, so find it by window focus, take its
        self.prompted = True
        # picture, and answer with the keys that reach Grant on this phone (Down, Right, OK, one call).
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            focus = re.search(r'(?m)^\s*mCurrentFocus=.*$', self.device.text('shell', 'dumpsys', 'window', 'windows', timeout=15))
            if focus and 'SuRequestActivity' in focus[0]:
                png = self.device.call('exec-out', 'screencap', '-p').stdout
                (output / f'{index:02d}.png').write_bytes(png)
                frame['image'] = f'{index:02d}.png'
                self.device.call('shell', 'input', 'keyevent', 'KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_RIGHT', 'KEYCODE_ENTER')
                frame['checks'].append("Magisk's Superuser Request window had focus; Down, Right, OK pressed (Magisk waits 10 s).")
                time.sleep(.4)
                return
            time.sleep(.3)
        png = self.device.call('exec-out', 'screencap', '-p').stdout
        (output / f'{index:02d}.png').write_bytes(png)
        frame['image'] = f'{index:02d}.png'
        raise CheckFailed("Magisk's Superuser Request window did not take focus within 8 s of Check Again.")

    def wait_boot(self, timeout, frame):
        start = time.monotonic()
        while True:
            if time.monotonic() - start > timeout:
                raise HarnessError(f'The phone did not finish booting within {timeout} s. See "If the phone does not boot" '
                                   'in tools/qa/README.md. (The restore step will keep waiting for it.)')
            try:
                if (self.device.text('get-state', timeout=8) == 'device' and self.prop('sys.boot_completed') == '1'):
                    boot = self.device.text('shell', 'cat /proc/sys/kernel/random/boot_id', timeout=8)
                    if boot and boot != self.boot_id:
                        break
            except HarnessError:
                pass
            time.sleep(4)
        took = time.monotonic() - start
        # adbd restarts once more about 20 s after boot completes and drops adb reverse (ROOT-TESTING.md).
        time.sleep(25)
        self.settle_adb()
        self.boot_id = self.device.text('shell', 'cat /proc/sys/kernel/random/boot_id', timeout=8)
        self.reboots += 1
        self.reverse()
        self.start_epoch()
        frame['checks'].append(f'Phone back after {took:.0f} s (plus 25 s for adbd), boot {self.reboots} of this run.')

    def settle_adb(self):
        for _ in range(20):
            try:
                if self.device.text('get-state', timeout=8) == 'device' and self.prop('sys.boot_completed') == '1':
                    return
            except HarnessError:
                pass
            time.sleep(3)
        raise HarnessError('adb did not come back after the boot.')

    def wait_online(self, minutes=12):
        deadline = time.monotonic() + minutes * 60
        while time.monotonic() < deadline:
            try:
                if self.device.text('get-state', timeout=8) == 'device' and self.prop('sys.boot_completed') == '1':
                    self.settle_adb()
                    return True
            except HarnessError:
                pass
            time.sleep(5)
        return False

    # after, always ---------------------------------------------------------------------------
    def restore(self):
        problems = []
        if not self.wait_online():
            raise HarnessError('RESTORE NOT DONE: the phone did not come back within 12 minutes. Follow "If the phone does '
                               'not boot" in tools/qa/README.md; the test modules and Wave are still on it.')
        steps = []

        def attempt(label, action):
            try:
                action()
                steps.append(label)
            except (HarnessError, CheckFailed) as e:
                problems.append(f'{label}: {e}')

        def drop_modules():
            self.su('for m in ' + ' '.join(TEST_MODULES) + '; do rm -rf /data/adb/modules/$m /data/adb/modules_update/$m; done; '
                    'rm -f /data/adb/wave_test_*')

        def drop_wave_state():
            if not self.base['watchdog']:
                self.su('rm -f /data/adb/post-fs-data.d/wave-watchdog.sh /data/adb/service.d/wave-watchdog.sh')
            if not self.base['wave_files']:
                self.su('rm -rf /data/adb/wave')
            else:
                for name in sorted(self.wave_files() - self.base['wave_files']):
                    if re.fullmatch(r'[A-Za-z0-9._-]+', name):
                        self.su(f'rm -rf /data/adb/wave/{name}')

        def drop_wave():
            if self.device.present():
                self.device.text('uninstall', PACKAGE, timeout=30)
            if self.device.present():
                raise HarnessError('Wave is still installed.')

        def drop_grants():
            keep = ','.join(str(u) for u in self.base['uids']) or '-1'
            self.su(f'magisk --sqlite "DELETE FROM policies WHERE uid NOT IN ({keep})"')

        def drop_plumbing():
            self.device.call('reverse', '--remove', f'tcp:{PORT}', check=False, timeout=10)
            self.su('rm -f /sdcard/Android/data/com.wave.xp3/files/plan.json', check=False)

        attempt('test modules and markers removed', drop_modules)
        attempt("Wave's own state files back to the first listing", drop_wave_state)
        attempt('Wave uninstalled', drop_wave)
        attempt('Magisk grants back to uid ' + ', '.join(map(str, self.base['uids'])), drop_grants)
        attempt('adb reverse removed', drop_plumbing)
        if self.prompted:
            time.sleep(12)   # an unanswered Magisk prompt is written as Deny up to 10 s after Wave goes
        attempt('Magisk grants back to uid ' + ', '.join(map(str, self.base['uids'])) + ' (second pass)', drop_grants)
        try:
            left, uids, present = self.leftovers(), self.uids(), self.device.present()
            if left or present or uids != self.base['uids']:
                problems.append(f'verification: leftovers={left!r} wave_installed={present} grants={uids}')
            else:
                steps.append('verified: no test module, no Wave, grants as before')
            if self.base['watchdog'] != self.watchdog_present():
                problems.append('verification: the watchdog scripts differ from the first listing')
        except (HarnessError, CheckFailed) as e:
            problems.append(f'verification: {e}')
        self.notes.append('Restored: ' + '; '.join(steps) + '.')
        if problems:
            raise HarnessError('RESTORE INCOMPLETE: ' + ' | '.join(problems))


def start_server(work):
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1', PORT)) == 0:
            raise HarnessError(f'Something already listens on 127.0.0.1:{PORT}.')
    if not (work / 'git' / 'mods.git' / '.git').is_dir():
        subprocess.run([sys.executable, str(HERE / 'source.py'), 'build', str(work)], check=True, capture_output=True)
    server = subprocess.Popen([sys.executable, str(HERE / 'source.py'), 'serve', str(work)],
                              stdout=open(work / 'server.log', 'w'), stderr=subprocess.STDOUT)
    for _ in range(50):
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', PORT)) == 0:
                return server
        time.sleep(.2)
    server.terminate()
    raise HarnessError('The local test source did not start; see ' + str(work / 'server.log'))


def check_build(apk, work):
    cert = (work / 'cert.pem').read_bytes() if (work / 'cert.pem').is_file() else b''
    with ZipFile(apk) as z:
        names = [n for n in z.namelist() if n.endswith('rooted_qa_cert.pem')]
        if not names or z.read(names[0]) != cert:
            raise HarnessError('This APK is not a rooted-QA build for this work folder; build it with tools/qa/rooted/apk.py.')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--scenario', type=Path, required=True)
    parser.add_argument('--work', type=Path, default=WORK)
    parser.add_argument('--wait-lock', type=int, default=0, metavar='SECONDS')
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    output = (args.out or ROOT / '.oak/tmp/wave-qa' / f'rooted-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}_{uuid.uuid4().hex[:8]}').resolve()
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    work = args.work.resolve()
    status, metadata, title = 'PASS', 'APK not inspected', args.scenario.name
    frames, notes = [], []
    signal.signal(signal.SIGTERM, run.interrupt)
    started = time.monotonic()
    server = state = None
    try:
        adb = shutil.which('adb')
        if not adb:
            raise HarnessError('adb not found on PATH.')
        scenario = run.read_scenario(args.scenario.resolve(), rooted=True, check_do=check_do)
        title = '[ROOTED] ' + scenario['name']
        check_build(args.apk, work)
        metadata, activity = run.inspect_apk(args.apk, None)
        device = Device(adb, activity, None, None)
        device.rooted = True
        state = Rooted(device, work, args.apk)
        device.doer = state.doer
        device.before_launch = state.reverse
        with run.phone_lock(args.wait_lock):
            device.check_device()
            if device.present():
                raise HarnessError('Wave is already installed. The harness never replaces an existing install.')
            state.baseline()
            server = start_server(work)
            try:
                if 'Success' not in device.text('install', str(args.apk), timeout=60):
                    raise HarnessError('Wave install did not report Success.')
                state.start_epoch()
                run.run_steps(device, scenario, output, frames)
                state.health()
            finally:
                try:
                    if server:
                        server.terminate()
                        server.wait(timeout=5)
                finally:
                    try:
                        state.stop_stream()
                        state.restore()
                    finally:
                        notes.extend(state.notes)
    except CheckFailed as e:
        status = 'FAIL'
        notes.append(str(e))
    except (HarnessError, ValueError, OSError, TypeError, KeyboardInterrupt, subprocess.CalledProcessError) as e:
        status = 'INCOMPLETE'
        notes.append(str(e) or 'Interrupted.')
    if state and state.notes and not any(n in notes for n in state.notes):
        notes[:0] = state.notes
    notes.insert(0, f'Took {time.monotonic() - started:.0f} s.')
    if frames and frames[-1]['status'] == 'incomplete' and status == 'FAIL':
        frames[-1]['status'] = 'fail'
    if status == 'PASS' and any('RESTORE' in n for n in notes):
        status = 'INCOMPLETE'
    run.report(output, title, metadata, frames, notes, status)
    print(f'{status}: {output / "index.html"}')
    for line in notes:
        print(line)
    return {'PASS': 0, 'FAIL': 1, 'INCOMPLETE': 2}[status]


if __name__ == '__main__':
    sys.exit(main())
