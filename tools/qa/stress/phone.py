#!/usr/bin/env python3
"""One data-layer stress run on the phone (see README.md). Run it under tools/qa/phone-run.sh, with
`sources.py serve WORK` already running on the host.

    phone.py --apk APK --work WORK --sources N --out DIR [--search] [--timeout SECONDS]

Installs the QA APK (the one tools/qa/rooted/apk.py --default-url https://localhost:4484/s000.git builds: a debug
build that trusts the throwaway certificate and has s000 as its built-in source), writes the other N-1 sources
into Wave's own sources.json the way Add Source records them (url, name, no commit yet), starts Wave, and lets it
refresh them all. While it works it samples, and afterwards it removes the temporary install:

  progress   sources with a saved tree, every 2 s (so each source's refresh finishes somewhere on this curve)
  memory     `dumpsys meminfo`: Java (Dalvik) and native heap, allocated and PSS, and total PSS
  responsive a d-pad key about every 1.5 s, then `dumpsys gfxinfo framestats`: how long the oldest input event
             waited for the main thread, and the longest frame; focus moved or not at three checkpoints
  health     logcat for ANR, crash and FATAL EXCEPTION
  search     (--search) the Search tab with one letter typed: time to the last frame drawn, then keys down to the
             end of the list to count its rows

Writes DIR/result.json and DIR/samples.csv, and screenshots. The phone must not have Wave installed.
"""
import argparse
import csv
import json
import re
import signal
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

SERIAL = '8516067e'
START_AT_LEAST = 50   # battery percent: a run starts at or above this, so a long one can't drain the phone dead
STOP_BELOW = 30       # and stops, cleaning up, if it falls below this
PACKAGE = 'com.wave.xp3'
ADB = ['adb', '-s', SERIAL]
MAIN = PACKAGE + '/com.wave.xp3.app.WaveActivity'
NONE = 9223372036854775807


def adb(*args, timeout=40, check=True, data=None):
    try:
        done = subprocess.run(ADB + list(args), capture_output=True, timeout=timeout, input=data)
    except subprocess.TimeoutExpired:
        # A phone that has gone away must not hang the cleanup: report the timeout like a failed command.
        done = subprocess.CompletedProcess(args, 124, b'', b'timed out')
    if check and done.returncode:
        raise SystemExit(f'adb {" ".join(args)} failed: {done.stderr.decode(errors="replace")[:300]}')
    return done


def sh(command, timeout=40, check=True):
    return adb('shell', command, timeout=timeout, check=check).stdout.decode(errors='replace')


def run_as(script, data=None, timeout=60):
    quoted = "'" + script.replace("'", "'\\''") + "'"
    done = adb('shell', f'run-as {PACKAGE} sh -c {quoted}', timeout=timeout, data=data, check=False)
    return done.returncode, done.stdout.decode(errors='replace'), done.stderr.decode(errors='replace')


def battery():
    """(percent, charging, temperature in tenths of a degree) from dumpsys battery; percent is None if it can't be read."""
    return parse_battery(sh('dumpsys battery', check=False))


def parse_battery(text):
    level = re.search(r'^\s*level: (\d+)', text, re.M)
    status = re.search(r'^\s*status: (\d+)', text, re.M)
    temp = re.search(r'^\s*temperature: (\d+)', text, re.M)
    return (int(level[1]) if level else None, bool(status) and status[1] == '2', int(temp[1]) if temp else None)


def too_low():
    level, _, _ = battery()
    return level is None or level < STOP_BELOW


def present():
    return sh(f'pm path {PACKAGE}', check=False).strip().startswith('package:')


def sources_json(count):
    """The record Add Source leaves behind for each source that isn't built in: url, name, commit, lastRefresh."""
    entries = [{'url': f'https://localhost:4484/s{n:03d}.git', 'name': f'Stress Source {n:03d}', 'commit': '', 'lastRefresh': 0}
               for n in range(1, count)]
    return json.dumps(entries).encode()


def seed(count):
    if count <= 1:
        return
    data = sources_json(count)
    code, _, err = run_as('mkdir -p files && cat > files/sources.json', data)
    if code or err:
        raise SystemExit('could not seed sources.json: ' + err[:200])
    code, back, _ = run_as('cat files/sources.json')
    if back.encode() != data:
        raise SystemExit('sources.json did not read back identically')


def meminfo():
    return parse_meminfo(sh(f'dumpsys meminfo {PACKAGE}', check=False))


def parse_meminfo(text):
    out = {}

    def row(label):
        m = re.search(rf'^\s*{label}\s+(\d+)\s+\d+\s+\d+\s+\d+\s+(\d+)\s+(\d+)\s+(\d+)', text, re.M)
        return m
    for key, label in (('dalvik', 'Dalvik Heap'), ('native', 'Native Heap')):
        m = row(label)
        if m:
            out[key + '_pss'], out[key + '_size'], out[key + '_alloc'] = int(m[1]), int(m[2]), int(m[3])
    for key, label in (('java_pss', 'Java Heap:'), ('native_pss', 'Native Heap:'), ('total_pss', 'TOTAL:')):
        m = re.search(rf'{label}\s+(\d+)', text)
        if m:
            out[key] = int(m[1])
    return out


def trees():
    code, out, _ = run_as('ls -d files/sources/*/tree/*/.sum 2>/dev/null | wc -l', timeout=20)
    return int(out.strip() or 0) if not code else -1


def focused(tree):
    return [n.get('bounds') + ' ' + (n.get('text') or n.get('content-desc') or '') for n in tree.iter('node') if n.get('focused') == 'true']


def hierarchy():
    path = '/data/local/tmp/wave-stress.xml'
    for _ in range(4):
        try:
            adb('shell', f'uiautomator dump {path}', timeout=25)
            xml = adb('exec-out', f'cat {path}', timeout=20).stdout
            return ET.fromstring(xml)
        except (ET.ParseError, subprocess.TimeoutExpired, SystemExit):
            time.sleep(.6)
    return None


def texts(tree):
    return [(n.get('text') or '') for n in tree.iter('node')] if tree is not None else []


def frames():
    return parse_frames(sh(f'dumpsys gfxinfo {PACKAGE} framestats', check=False))


def parse_frames(text):
    """The frames gfxinfo still holds, as dicts keyed by column name."""
    rows, names = [], None
    for line in text.splitlines():
        if line.startswith('Flags,'):
            names = line.rstrip(',').split(',')
        elif names and re.match(r'^\d+,\d+,', line):
            cells = line.rstrip(',').split(',')
            if len(cells) >= len(names):
                rows.append({n: int(c) for n, c in zip(names, cells)})
        elif names and line.startswith('---PROFILEDATA---'):
            names = None
    return rows


class Sampler(threading.Thread):
    def __init__(self, count, started):
        super().__init__(daemon=True)
        self.count, self.started, self.stop_flag = count, started, threading.Event()
        self.samples = []
        self.battery_low = threading.Event()
        self.n = 0

    def run(self):
        while not self.stop_flag.is_set():
            try:
                sample = {'t': round(time.time() - self.started, 1), 'trees': trees(), **meminfo()}
                self.n += 1
                if self.n % 10 == 1:
                    level, charging, temp = battery()
                    sample.update(battery=level, charging=int(charging), temp_tenths=temp)
                    if level is None or level < STOP_BELOW:
                        self.battery_low.set()
                self.samples.append(sample)
            except Exception as e:  # a sample is lost; the run goes on
                self.samples.append({'t': round(time.time() - self.started, 1), 'error': str(e)[:80]})
            self.stop_flag.wait(1.5)


class Prober(threading.Thread):
    """A d-pad key, then how long the app took to take it."""

    def __init__(self, started):
        super().__init__(daemon=True)
        self.started, self.stop_flag, self.seen = started, threading.Event(), set()
        self.waits, self.frame_ms, self.keys = [], [], 0
        self.pause = threading.Event()

    def run(self):
        down = True
        while not self.stop_flag.is_set():
            if self.pause.is_set():
                self.stop_flag.wait(.3)
                continue
            sh(f'input keyevent {"KEYCODE_DPAD_DOWN" if down else "KEYCODE_DPAD_UP"}', check=False)
            down = not down
            self.keys += 1
            time.sleep(.5)
            try:
                for f in frames():
                    key = f.get('IntendedVsync')
                    if key in self.seen:
                        continue
                    self.seen.add(key)
                    oldest = f.get('OldestInputEvent', NONE)
                    if oldest not in (NONE, 0) and f.get('HandleInputStart', 0) >= oldest:
                        self.waits.append((round(time.time() - self.started, 1), (f['HandleInputStart'] - oldest) / 1e6))
                    if f.get('FrameCompleted', 0) > f.get('IntendedVsync', 0) > 0:
                        self.frame_ms.append((f['FrameCompleted'] - f['IntendedVsync']) / 1e6)
            except Exception:
                pass
            self.stop_flag.wait(1.0)


def stats(values):
    values = sorted(values)
    if not values:
        return {}
    return {'n': len(values), 'median': round(values[len(values) // 2], 1), 'p95': round(values[int(len(values) * .95)], 1), 'max': round(values[-1], 1)}


def screenshot(path):
    path.write_bytes(adb('exec-out', 'screencap -p', timeout=30).stdout)


def checkpoint(name, result):
    """Does the d-pad still move focus? One key, a screen dump before and after."""
    before = hierarchy()
    adb('shell', 'input keyevent KEYCODE_DPAD_DOWN', check=False)
    time.sleep(.8)
    after = hierarchy()
    result['focus_checkpoints'].append({'at': name, 'before': focused(before) if before is not None else None,
                                        'after': focused(after) if after is not None else None,
                                        'moved': before is not None and after is not None and focused(before) != focused(after)})


def search_phase(result, out):
    # Home is where Wave opens; Search is three tabs to the right.
    for _ in range(3):
        sh('input keyevent KEYCODE_DPAD_RIGHT', check=False)
        time.sleep(.4)
    time.sleep(1)
    frames()  # nothing to do with the result; the next call is the one that counts
    sh('dumpsys gfxinfo ' + PACKAGE + ' reset', check=False)
    note = {}
    for query, key in (('a', 'KEYCODE_A'),):
        sh('dumpsys gfxinfo ' + PACKAGE + ' reset', check=False)
        sent = time.time()
        sh(f'input keyevent {key}', check=False)
        # Keep reading frames until none new has been drawn for a second.
        done, last, seen = None, None, set()
        while time.time() - sent < 20:
            time.sleep(.25)
            for f in frames():
                v = f.get('IntendedVsync')
                if v in seen or f.get('OldestInputEvent', NONE) in (0,):
                    continue
                seen.add(v)
                last = (f['FrameCompleted'], f.get('OldestInputEvent', NONE), v)
                done = time.time()
            if done and time.time() - done > 1.0:
                break
        firstframe = None
        # Time from the key to the last frame: the frame that carries the key, then the one that shows results.
        stamps = sorted(seen)
        note[query] = {'wall_to_last_frame_s': round(done - sent, 2) if done else None, 'frames': len(seen)}
        screenshot(out / 'search-a.png')
        tree = hierarchy()
        note[query]['texts'] = [t for t in texts(tree) if t][:12]
    # Down to the end of the list and count the rows on the way.
    sh('input keyevent KEYCODE_DPAD_DOWN', check=False)
    rows = 0
    last_focus = None
    for i in range(260):
        keys = ' '.join(['KEYCODE_DPAD_DOWN'] * 10)
        sh(f'input keyevent {keys}', check=False)
        rows += 10
        if i % 5 == 4:
            now = focused(hierarchy())
            if now == last_focus:
                break
            last_focus = now
    note['keys_pressed_until_focus_stopped'] = rows
    note['focus_at_end'] = last_focus
    screenshot(out / 'search-end.png')
    result['search'] = note


def restart_phase(result):
    """Wave started again with every source saved: how long until Home stops saying Loading (the index read with digests checked)."""
    adb('shell', f'am force-stop {PACKAGE}', check=False, timeout=15)
    time.sleep(2)
    start = time.time()
    adb('shell', f'am start -W -n {MAIN}', timeout=40)
    launched = time.time() - start
    seen = []
    while time.time() - start < 180:
        tree = hierarchy()
        words = [t for t in texts(tree) if t]
        seen.append((round(time.time() - start, 1), 'Loading…' in words))
        if tree is not None and 'Loading…' not in words and any(w in ('Featured', 'Updates') or w for w in words):
            break
    note = {'launch_command_s': round(launched, 1), 'loading_gone_after_s': seen[-1][0] if seen and not seen[-1][1] else None,
            'polls': len(seen)}
    result['cold_start'] = note
    screenshot(Path(result['out']) / 'cold-start.png')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--apk', required=True)
    parser.add_argument('--work', required=True)
    parser.add_argument('--sources', type=int, required=True, help='sources in all, the built-in one included (1 to 128)')
    parser.add_argument('--out', required=True)
    parser.add_argument('--search', action='store_true')
    parser.add_argument('--timeout', type=int, default=1500, help='seconds to wait for every source to refresh')
    parser.add_argument('--force-battery', action='store_true', help=f'start below {START_AT_LEAST}%% (the run still stops below {STOP_BELOW}%%)')
    args = parser.parse_args()
    # A signal must still leave the phone as it was: turn it into an exception so the cleanup below runs.
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(SystemExit('terminated')))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    work = Path(args.work)
    if sh('getprop ro.product.device').strip() != 'XP3800' or adb('get-state').stdout.strip() != b'device':
        raise SystemExit(f'{SERIAL} must be the XP3800, booted and authorized')
    if present():
        raise SystemExit('Wave is already installed on the phone. The stress run never replaces an existing install.')
    level, charging, temp = battery()
    result_battery = {'start_percent': level, 'charging': charging, 'temp_tenths': temp}
    if level is None or (level < START_AT_LEAST and not args.force_battery):
        raise SystemExit(f'Battery is at {level}%: a run needs at least {START_AT_LEAST}% (a long run with the screen on drains faster than USB charges). Charge it, or pass --force-battery.')
    if temp is not None and temp > 430:
        raise SystemExit(f'Battery is at {temp / 10:.1f} C. Let it cool before a long run.')
    log_file = (out / 'logcat.txt').open('wb')
    result = {'out': str(out), 'apk': Path(args.apk).name, 'sources': args.sources, 'focus_checkpoints': [], 'battery': result_battery}
    log = None
    sampler = prober = None
    try:
        if 'Success' not in adb('install', '-t', args.apk, timeout=120).stdout.decode():
            raise SystemExit('install failed')
        adb('reverse', 'tcp:4484', 'tcp:4484')
        seed(args.sources)
        adb('logcat', '-c')
        log = subprocess.Popen(ADB + ['logcat', '-b', 'main,system,events', '-v', 'brief', 'ActivityManager:E', 'AndroidRuntime:E',
                                      'am_anr:I', 'am_crash:I', 'WindowManager:E', '*:S'], stdout=log_file, stderr=subprocess.DEVNULL)
        requests_file = work / 'requests.jsonl'
        mark = requests_file.stat().st_size if requests_file.exists() else 0
        sh('dumpsys gfxinfo ' + PACKAGE + ' reset', check=False)
        started = time.time()
        reply = sh(f'am start -W -n {MAIN}', timeout=40)
        result['launch_reply_ok'] = 'Status: ok' in reply
        result['launch_ms'] = int(m[1]) if (m := re.search(r'TotalTime: (\d+)', reply)) else None
        sampler = Sampler(args.sources, started)
        sampler.start()
        time.sleep(3)
        prober = Prober(started)
        checkpoint('start', result)
        prober.start()
        finished = None
        deadline = started + args.timeout
        mid = False
        while time.time() < deadline:
            time.sleep(2)
            if sampler.battery_low.is_set():
                result['stopped'] = f'battery below {STOP_BELOW}%'
                break
            done = [s['trees'] for s in sampler.samples if 'trees' in s]
            current = done[-1] if done else 0
            if not mid and current >= args.sources // 2:
                mid = True
                prober.pause.set()
                time.sleep(1.5)
                checkpoint('half the sources saved', result)
                prober.pause.clear()
            if current >= args.sources:
                finished = time.time() - started
                break
        result['all_trees_saved_after_s'] = round(finished, 1) if finished else None
        result['trees_saved'] = [s['trees'] for s in sampler.samples if 'trees' in s][-1:]
        time.sleep(6)   # the last read of the index and the screens
        prober.pause.set()
        time.sleep(1.5)
        checkpoint('all refreshed', result)
        prober.stop_flag.set()
        prober.join(10)
        screenshot(out / 'sources.png')
        restart_phase(result)
        if args.search:
            search_phase(result, out)
        time.sleep(3)
        # Sources screen as a person would see it: Home, then Sources tab.
        sampler.stop_flag.set()
        sampler.join(10)
        result['launch_to_first_samples_s'] = sampler.samples[0]['t'] if sampler.samples else None
        for name, key in (('prober', prober),):
            result['input_wait_ms'] = stats([w for _, w in key.waits])
            result['frame_ms'] = stats(key.frame_ms)
            result['keys_sent'] = key.keys
            result['input_wait_over_100ms'] = sum(1 for _, w in key.waits if w > 100)
            result['input_wait_over_500ms'] = sum(1 for _, w in key.waits if w > 500)
        peaks = {}
        for k in ('dalvik_alloc', 'dalvik_size', 'native_alloc', 'native_size', 'java_pss', 'native_pss', 'total_pss'):
            values = [s[k] for s in sampler.samples if k in s]
            if values:
                peaks[k] = max(values)
                peaks[k + '_last'] = values[-1]
        result['memory_kb'] = peaks
        pid = sh(f'pidof {PACKAGE}', check=False).strip()
        result['alive_at_end'] = bool(pid)
        # Requests the server saw: one source's fetch, first request to last.
        per = {}
        if requests_file.exists():
            with requests_file.open() as f:
                f.seek(mark)
                for line in f:
                    r = json.loads(line)
                    s = per.setdefault(r['source'], [r['start'], r['end'], 0])
                    s[0], s[1], s[2] = min(s[0], r['start']), max(s[1], r['end']), s[2] + r['bytes']
        fetch = sorted((v[1] - v[0], k, v[2]) for k, v in per.items())
        result['fetch_s'] = {'sources_fetched': len(per), **stats([x[0] * 1000 for x in fetch]), 'unit': 'ms per source, first request to last',
                             'slowest': [(k, round(d, 2), b) for d, k, b in fetch[-3:]]}
        if per:
            first = min(v[0] for v in per.values())
            last = max(v[1] for v in per.values())
            result['first_request_after_launch_s'] = round(first - started, 1)
            result['last_request_after_launch_s'] = round(last - started, 1)
    finally:
        if sampler:
            sampler.stop_flag.set()
        if prober:
            prober.stop_flag.set()
        time.sleep(2)
        if log:
            log.terminate()
        log_file.close()
        text = (out / 'logcat.txt').read_text(errors='replace')
        result['anr'] = len(re.findall(r'am_anr|ANR in', text))
        result['crashes'] = len(re.findall(r'am_crash|FATAL EXCEPTION', text))
        if sampler:
            keys = sorted({k for s in sampler.samples for k in s})
            with (out / 'samples.csv').open('w', newline='') as f:
                w = csv.DictWriter(f, keys)
                w.writeheader()
                w.writerows(sampler.samples)
        adb('shell', f'am force-stop {PACKAGE}', check=False, timeout=15)
        adb('reverse', '--remove', 'tcp:4484', check=False)
        adb('shell', 'input keyevent KEYCODE_HOME', check=False, timeout=15)
        if present():
            adb('uninstall', PACKAGE, timeout=60, check=False)
        try:
            result['uninstalled'] = not present()
            result['battery']['end_percent'] = battery()[0]
        except Exception as e:  # the phone may be gone: say so, and still write the report
            result['uninstalled'] = 'unknown: ' + str(e)[:80]
        (out / 'result.json').write_text(json.dumps(result, indent=2))
        print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
