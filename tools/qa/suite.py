#!/usr/bin/env python3
"""Run every fixture scenario in order against one debug APK and index the reports.

    suite.py --apk APK [scenario.json ...]            the default, root-free fixture suite
    suite.py --rooted [--apk APK] [R0x.json ...]      the ROOTED suite (tools/qa/rooted/): changes the phone

--rooted needs a rooted phone, reboots it several times (one scenario deliberately breaks a boot) and takes
about 25 minutes. It checks `su -c id` first and refuses on an unrooted phone, holds the phone lock for the
whole run, builds the rooted-QA APK from HEAD when --apk is not given, and restores the phone after every
scenario whatever happens. Read the "Rooted suite" section of tools/qa/README.md before the first run.
"""
import argparse
import os
from datetime import datetime, timezone
from html import escape
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

SERIAL = '8516067e'

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def adb_su(command, timeout=30):
    """Run `su -c command` on the reserved phone; return (exit code, output)."""
    done = subprocess.run(['adb', '-s', SERIAL, 'shell', 'su -c ' + command], capture_output=True, text=True,
                          timeout=timeout, check=False)
    return done.returncode, (done.stdout + done.stderr).strip()


def rooted_preflight(su=adb_su):
    """Return None when the phone is rooted for adb and has no Magisk grant but the shell's, else the refusal."""
    try:
        code, out = su("id")
    except (OSError, subprocess.TimeoutExpired) as e:
        return f'REFUSED: could not run `su -c id` on {SERIAL}: {e}. Nothing was changed.'
    if code or 'uid=0(root)' not in out:
        return (f'REFUSED: `su -c id` on {SERIAL} did not give uid=0 (got: {out[:120] or "nothing"}). The rooted suite '
                'needs a rooted phone; nothing was changed.')
    try:
        code, out = su("'magisk --sqlite \"SELECT uid FROM policies\"'")
    except (OSError, subprocess.TimeoutExpired) as e:
        return f'REFUSED: could not read the Magisk grant table: {e}. Nothing was changed.'
    uids = sorted({int(n) for n in re.findall(r'uid=(\d+)', out)})
    if code or uids != [2000]:
        return (f'REFUSED: the Magisk grant table is {uids or out[:100]}; the rooted suite starts from uid 2000 (shell) only '
                'and restores to it. Nothing was changed.')
    return None


def rooted_main(args, parser):
    paths = args.scenarios or sorted((HERE / 'rooted/scenarios').glob('R*.json'))
    if not paths:
        parser.error('no rooted scenarios')
    for path in paths:
        if not path.is_file() or not json.loads(path.read_text(encoding='utf-8')).get('rooted') is True:
            parser.error(f'{path} is not a rooted scenario ("rooted": true)')
    apk = args.apk
    if apk is None:
        print('Building the rooted-QA APK from HEAD (scratch export) ...', flush=True)
        built = subprocess.run([sys.executable, str(HERE / 'rooted/apk.py'), '--commit', 'HEAD'], capture_output=True, text=True)
        if built.returncode:
            print('APK build failed:\n' + built.stderr[-1500:])
            return 2
        apk = Path(built.stdout.strip().splitlines()[-1])
    if not os.environ.get('WAVE_PHONE_LOCK_TOKEN'):
        # One lock for the whole rooted run, so nobody uses the phone between scenarios or during a reboot.
        cmd = [str(HERE / 'phone-run.sh'), '--who', args.who or 'rooted QA suite (changes root state, reboots; about 25 min)',
               '--wait', str(args.wait_lock), '--', sys.executable, str(Path(__file__).resolve()), '--rooted',
               '--apk', str(apk), '--wait-lock', '0']
        if args.out:
            cmd += ['--out', str(args.out.resolve())]
        os.execv(cmd[0], cmd + [str(p.resolve()) for p in paths])
    refusal = rooted_preflight()
    if refusal:
        print(refusal)
        return 2
    stamp = f'{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}_{uuid.uuid4().hex[:8]}'
    out = (args.out or ROOT / '.oak/tmp/wave-qa' / f'rooted-suite-{stamp}').resolve()
    out.mkdir(parents=True, mode=0o700, exist_ok=False)
    rows, worst, began = [], 0, time.monotonic()
    for path in paths:
        started = time.monotonic()
        done = subprocess.run([sys.executable, str(HERE / 'rooted/rooted.py'), '--apk', str(apk), '--scenario', str(path),
                               '--out', str(out / path.stem)], capture_output=True, text=True, check=False)
        status = re.search(r'^(PASS|FAIL|INCOMPLETE):', done.stdout, re.M)
        notes = [line for line in done.stdout.splitlines() if line and not line[:2].isdigit() and not re.match(r'^(PASS|FAIL|INCOMPLETE):', line)]
        rows.append((path.stem, status[1] if status else 'INCOMPLETE', f'{time.monotonic() - started:.0f} s',
                     ' '.join(notes)[:600] or done.stderr.strip()[:300]))
        worst = max(worst, done.returncode or (0 if status else 2))
        print(f'{rows[-1][1]:10} {path.stem} {rows[-1][2]}', flush=True)
        if rows[-1][1] == 'INCOMPLETE' and 'RESTORE' in rows[-1][3]:
            print('Stopping: the restore did not finish, so the phone is not known to be clean.', flush=True)
            break
    final = rooted_preflight()
    left = adb_su('ls -d /data/adb/modules/wave_test_* /data/adb/wave_test_* 2>/dev/null; true')[1]
    present = subprocess.run(['adb', '-s', SERIAL, 'shell', 'pm', 'path', 'com.wave.xp3'], capture_output=True, text=True).stdout.strip()
    ending = ('Final check: phone rooted, grants uid 2000 only, no test module, Wave removed.' if not (final or left or present)
              else f'FINAL CHECK FAILED: {final or ""} {left} {present}'.strip())
    if final or left or present:
        worst = 2
    print(ending, flush=True)
    items = ''.join(f'<tr><td><a href="{escape(n)}/index.html">{escape(n)}</a></td><td>{escape(st)}</td><td>{escape(t)}</td>'
                    f'<td>{escape(d)}</td></tr>' for n, st, t, d in rows)
    (out / 'index.html').write_text(
        '<!doctype html><html lang="en"><meta charset="utf-8"><title>Wave rooted QA suite</title>'
        '<body style="font:16px sans-serif;margin:2rem"><h1>Wave ROOTED QA suite</h1>'
        f'<p>Rooted phone, reboots included. APK {escape(apk.name)}. Total {time.monotonic() - began:.0f} s. {escape(ending)}</p>'
        '<table border="1" cellpadding="6"><thead><tr><th>Scenario</th><th>Status</th><th>Time</th><th>Notes</th></tr></thead>'
        f'<tbody>{items}</tbody></table></body></html>\n', encoding='utf-8')
    print(out / 'index.html')
    return worst


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=None, help='debug APK (default app/app/build/outputs/apk/debug/app-debug.apk; '
                        'with --rooted, a rooted-QA APK, default: built from HEAD)')
    parser.add_argument('--rooted', action='store_true', help='run tools/qa/rooted/scenarios/R*.json on the rooted phone instead')
    parser.add_argument('--who', help='with --rooted: the lock label')
    parser.add_argument('--wait-lock', type=int, default=600, metavar='SECONDS')
    parser.add_argument('--out', type=Path, help='new suite folder (default .oak/tmp/wave-qa/suite-<UTC>_<id>)')
    parser.add_argument('scenarios', nargs='*', type=Path, help='default: scenarios/07-*.json with a fixture')
    args = parser.parse_args()
    if args.rooted:
        return rooted_main(args, parser)
    args.apk = args.apk or ROOT / 'app/app/build/outputs/apk/debug/app-debug.apk'
    paths = args.scenarios or sorted(HERE.glob('scenarios/07-*.json'))
    paths = [p for p in paths if 'fixture' in json.loads(p.read_text(encoding='utf-8'))]
    if not paths:
        parser.error('no fixture scenarios')
    stamp = f'{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}_{uuid.uuid4().hex[:8]}'
    out = (args.out or ROOT / '.oak/tmp/wave-qa' / f'suite-{stamp}').resolve()
    out.mkdir(parents=True, mode=0o700, exist_ok=False)
    rows, worst = [], 0
    for path in paths:
        report = out / path.stem
        done = subprocess.run([sys.executable, str(HERE / 'run.py'), '--apk', str(args.apk), '--scenario', str(path),
                               '--wait-lock', str(args.wait_lock), '--out', str(report)],
                              capture_output=True, text=True, check=False)
        status = re.search(r'^(PASS|FAIL|INCOMPLETE):', done.stdout, re.M)
        detail = '; '.join(line for line in done.stdout.splitlines()[1:] if line and not line[:2].isdigit()
                           and ': ' not in line[:12])[:300] if done.returncode else ''
        rows.append((path.stem, status[1] if status else 'INCOMPLETE', detail or done.stderr.strip()[:300]))
        worst = max(worst, done.returncode)
        print(f'{rows[-1][1]:10} {path.stem}', flush=True)
    items = ''.join(f'<tr><td><a href="{escape(name)}/index.html">{escape(name)}</a></td>'
                    f'<td>{escape(status)}</td><td>{escape(detail)}</td></tr>' for name, status, detail in rows)
    (out / 'index.html').write_text(
        '<!doctype html><html lang="en"><meta charset="utf-8"><title>Wave QA suite</title>'
        '<body style="font:16px sans-serif;margin:2rem"><h1>Wave QA suite</h1>'
        '<table border="1" cellpadding="6"><thead><tr><th>Scenario</th><th>Status</th><th>Notes</th></tr></thead>'
        f'<tbody>{items}</tbody></table></body></html>\n', encoding='utf-8')
    print(out / 'index.html')
    return worst


if __name__ == '__main__':
    sys.exit(main())
