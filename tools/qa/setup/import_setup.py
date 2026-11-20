#!/usr/bin/env python3
"""Import each generated setup file into a side-by-side `.wavetest` copy of its app with the d-pad, and verify it.

    tools/qa/phone-run.sh --who "agentN setup import" --wait 3000 -- \\
        python3 tools/qa/setup/import_setup.py --apks APK_DIR --files FILE_DIR --out REPORT_DIR [app ...]

APK_DIR comes from build_copies.py and FILE_DIR from generate.py. Only packages ending in .wavetest are installed (one at
a time, removed afterwards, along with their Android/data folder); the owner's packages are only read, and their path,
version and update time must be identical before and after or the run fails. Keys sent: DPAD_*, ENTER, MENU.
The reserved phone (serial in apps.py) must be rooted: the check that the setup landed in the app's private storage
uses `su`; no Magisk prompt appears because the shell is already granted.

Per app it checks: a fresh copy opens on "Set up <App>" and "Not set up yet." with no login; the first Import button is
reached by Down (Authenticator and Flashcards start on "On this phone only"); "Import this setup?" offers Import, which
Right reaches; the app then opens, the file is gone from Android/data, wave-setup.json exists and is owner-only; after a
force-stop and relaunch the app opens without asking; the menu's Setup... (Settings for Canvas, whose account sits there)
shows the imported setup and no secret value; no crash. Writes <out>/<app>/NN-*.png and result.json, and summary.json.
Exit 0 only if every app passed and the owner packages are unchanged. With --picker the file goes to /sdcard/Download and is imported through "Choose a file...": Down, OK, Down to
Downloads, OK, OK on the newest file (the picker also lists the owner's files; the script refuses to press OK unless the focus
is on wave-setup-*), then the same dialog.
"""
import argparse, json, subprocess, sys, time
from pathlib import Path
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
from apps import APPS, COPY_SUFFIX, SECRET_MARKERS, SERIAL as S  # noqa: E402

APKS = FILES = OUT = None
PICKER = False
KEYS = {'DOWN': 'KEYCODE_DPAD_DOWN', 'UP': 'KEYCODE_DPAD_UP', 'LEFT': 'KEYCODE_DPAD_LEFT', 'RIGHT': 'KEYCODE_DPAD_RIGHT',
        'ENTER': 'KEYCODE_ENTER', 'BACK': 'KEYCODE_BACK', 'MENU': 'KEYCODE_MENU'}


def adb(*args, check=True, timeout=60, binary=False):
    r = subprocess.run(['adb', '-s', S, *args], capture_output=True, timeout=timeout)
    if check and r.returncode:
        raise SystemExit(f'adb {args} failed: {r.stderr.decode(errors="replace")[:300]}')
    return r.stdout if binary else r.stdout.decode(errors='replace').strip()


def shell(cmd, **kw):
    return adb('shell', cmd, **kw)


class Run:
    def __init__(self, app):
        self.app = app
        self.owner, self.activity, self.name = (APPS[app][k] for k in ('package', 'activity', 'name'))
        self.pkg = self.owner + COPY_SUFFIX
        self.dir = OUT / app
        self.dir.mkdir(parents=True, exist_ok=True)
        self.n = 0
        self.log = []
        self.keys = []

    def note(self, text):
        self.log.append(text)
        print(f'[{self.app}] {text}', flush=True)

    def shot(self, label):
        self.n += 1
        name = f'{self.n:02d}-{label}.png'
        (self.dir / name).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        return name

    def tree(self):
        for _ in range(6):
            shell('uiautomator dump /sdcard/wavetest-ui.xml', check=False, timeout=30)
            xml = adb('exec-out', 'cat', '/sdcard/wavetest-ui.xml', check=False, binary=True).decode(errors='replace')
            shell('rm -f /sdcard/wavetest-ui.xml', check=False)
            if '<hierarchy' in xml:
                try:
                    return list(ET.fromstring(xml).iter('node'))
                except ET.ParseError:
                    pass
            time.sleep(.8)
        return []

    def texts(self):
        out = []
        for n in self.tree():
            for t in (n.get('text'), n.get('content-desc')):
                if t:
                    out.append(t.strip())
        return out

    def focused(self):
        for n in self.tree():
            if n.get('focused') == 'true':
                # a focused list row has no text of its own; its first labelled child names it
                for d in n.iter('node'):
                    label = (d.get('text') or d.get('content-desc') or '').strip()
                    if label:
                        return label
        return ''

    def highlighted(self):
        """The picker's roots list keeps focus on the list and marks the current row selected; its file list focuses the row."""
        for n in self.tree():
            if n.get('selected') == 'true':
                label = (n.get('text') or '').strip()
                if label:
                    return label
        return self.focused()   # a file list focuses the row itself

    def key(self, name, wait=.8):
        shell('input keyevent ' + KEYS[name])
        self.keys.append(name)
        time.sleep(wait)

    def foreground_ok(self):
        return self.pkg in shell('dumpsys window windows | grep mCurrentFocus', check=False)

    def launch(self):
        shell(f'am start -W -n {self.pkg}/{self.activity}', timeout=40)
        time.sleep(2.5)

    def expect(self, cond, what):
        if not cond:
            self.shot('FAILED')
            raise AssertionError(f'{self.app}: {what}')
        self.note('ok: ' + what)

    def run(self):
        app, pkg = self.app, self.pkg
        remote_dir = f'/sdcard/Android/data/{pkg}/files'
        file = FILES / f'wave-setup-{app}.json'
        result = {'app': app, 'package': pkg, 'file': file.name, 'placed': remote_dir + '/' + file.name}
        shell('logcat -c')
        shell('input keyevent KEYCODE_WAKEUP')
        self.expect('Success' in adb('install', '-t', str(APKS / f'{app}.apk'), timeout=120), 'installed beside the original as ' + pkg)
        try:
            self.launch()
            self.expect(self.foreground_ok(), 'the copy is in the foreground')
            self.shot('first-launch')
            texts = self.texts()
            self.expect(f'Set up {self.name}' in texts and 'Not set up yet.' in texts, f'a fresh install opens on "Set up {self.name}" / "Not set up yet."')
            self.expect(not any(t.startswith(('Sign in', 'Log in')) for t in texts), 'no login screen is shown')
            if PICKER:
                download = f'/sdcard/Download/{file.name}'
                adb('push', str(file), download)
                self.note(f'file pushed to {download}; going through "Choose a file..." (Android picker)')
                result['placed'] = download
                for _ in range(4):
                    if self.focused().startswith('Choose'):
                        break
                    self.key('DOWN')
                self.expect(self.focused().startswith('Choose'), f'focus reached "{self.focused()}"')
                self.key('ENTER', 2.5)
                self.shot('picker')
                self.expect('com.android.documentsui' in shell('dumpsys window windows | grep mCurrentFocus', check=False), "Android's file picker opened")
                for _ in range(4):
                    if self.highlighted() == 'Downloads':
                        break
                    self.key('DOWN', .6)
                self.expect(self.highlighted() == 'Downloads', 'the d-pad reaches Downloads in the picker')
                self.key('ENTER', 2)
                self.shot('picker-downloads')
                self.expect(self.highlighted().startswith('wave-setup-'), f'the newest file, "{self.highlighted()}", is highlighted (never press OK on anything else: the picker lists the owner\'s files)')
                self.key('ENTER', 2.5)
            else:
                adb('push', str(file), remote_dir + '/' + file.name)
                self.note(f'file pushed to {remote_dir}/ (shell, as the transport describes)')
                # first button has the focus; move Down until Import has it
                for _ in range(4):
                    if self.focused().startswith('Import'):
                        break
                    self.key('DOWN')
                self.expect(self.focused().startswith('Import'), f'focus reached "{self.focused()}"')
                self.shot('import-button-focused')
                self.key('ENTER', 1.5)
            self.shot('import-this-setup')
            texts = self.texts()
            self.expect('Import this setup?' in texts, 'the app offers "Import this setup?" with a summary')
            result['summary'] = [t for t in texts if t.lower() not in ('import this setup?', 'import', 'cancel')][:8]
            for _ in range(3):
                if self.focused().lower() == 'import':
                    break
                self.key('RIGHT', .5)
            self.expect(self.focused().lower() == 'import', 'the d-pad reaches Import in the dialog')
            self.key('ENTER', 2.5)
            self.shot('after-import')
            time.sleep(1)
            self.expect(self.foreground_ok(), 'the app opened after the import')
            texts = self.texts()
            self.expect(not (f'Set up {self.name}' in texts or 'Not set up yet.' in texts), 'the app no longer shows the setup screen')
            self.shot('app-opened')
            result['after_import_text'] = texts[:12]
            gone = shell(f'ls /sdcard/Download {remote_dir} 2>&1', check=False) if PICKER else shell(f'ls {remote_dir} 2>&1', check=False)
            self.expect(file.name not in gone, 'the setup file was deleted after the import (it holds secrets)')
            private = shell(f"su -c 'ls -l /data/data/{pkg}/files/wave-setup.json' 2>&1", check=False)
            self.expect('wave-setup.json' in private and 'No such' not in private, 'wave-setup.json exists in the app\'s private storage: ' + private.split()[0])
            # restart: it must not ask again
            shell(f'am force-stop {pkg}')
            time.sleep(1)
            self.launch()
            texts = self.texts()
            self.shot('relaunch')
            self.expect(self.foreground_ok() and not (f'Set up {self.name}' in texts or 'Not set up yet.' in texts),
                        'after a force-stop and a new launch the app opens configured and does not ask again')
            result['relaunch_text'] = texts[:12]
            # the setup screen from the app's own menu shows what it is set up with
            base = set(texts)
            self.key('MENU', 1)
            self.shot('menu')
            menu = [t for t in self.texts() if t not in base and not t.isdigit()]
            result['menu'] = menu[:12]
            setup_item = next((t for t in menu if t.startswith(('Setup', 'Settings'))), None)
            if setup_item:
                for _ in range(menu.index(setup_item)):
                    self.key('DOWN', .5)
                self.shot('menu-on-setup')
                self.key('ENTER', 1.5)
                self.shot('setup-screen')
                shown = self.texts()
                result['setup_screen'] = shown[:20]
                if app == 'canvas':   # Canvas keeps its account in Settings
                    self.expect('Account' in shown and 'school.example.edu' in shown, 'Settings shows Account: school.example.edu (the imported address)')
                else:
                    self.expect(f'{self.name} setup' in shown, f'the setup screen reads "{self.name} setup" (set up)')
                self.expect(not any(v in ' '.join(shown) for v in SECRET_MARKERS), 'no secret value is printed on the setup screen')
            else:
                self.note('menu has no Setup/Settings item: ' + ', '.join(menu[:10]))
            crashes = [l for l in shell('logcat -d -b crash -b main *:E', check=False).splitlines() if pkg in l and 'FATAL' in l]
            self.expect(not crashes, 'no crash recorded for the copy')
            result['status'] = 'PASS'
        except (AssertionError, SystemExit) as e:
            result['status'] = 'FAIL'
            result['error'] = str(e)
            self.note('FAIL: ' + str(e))
        finally:
            adb('shell', 'am', 'force-stop', pkg, check=False)
            adb('uninstall', pkg, check=False)
            left = shell(f'pm path {pkg}', check=False)
            shell(f'rm -rf /sdcard/Android/data/{pkg}', check=False)
            if PICKER:
                shell(f'rm -f /sdcard/Download/{file.name}', check=False)
            self.note('uninstalled' + (' (STILL INSTALLED)' if left else '') + '; Android/data folder removed')
        result['keys'] = self.keys
        result['log'] = self.log
        (self.dir / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        return result


def main():
    global APKS, FILES, OUT, PICKER
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--apks', type=Path, required=True)
    parser.add_argument('--files', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--picker', action='store_true', help='import through "Choose a file..." and Android\'s picker, from /sdcard/Download')
    parser.add_argument('apps', nargs='*')
    args = parser.parse_args()
    unknown = [a for a in args.apps if a not in APPS]
    if unknown:
        parser.error('unknown app(s): ' + ', '.join(unknown))
    PICKER = args.picker
    APKS, FILES, OUT = args.apks.resolve(), args.files.resolve(), args.out.resolve()
    OUT.mkdir(parents=True, exist_ok=True)
    for app in args.apps or APPS:
        for needed in (APKS / f'{app}.apk', FILES / f'wave-setup-{app}.json'):
            if not needed.is_file():
                parser.error(f'missing {needed}')
    if 'XP3800' not in shell('getprop ro.product.device', check=False):
        raise SystemExit('Not the reserved XP3800; refusing.')
    if 'uid=0' not in shell("su -c id", check=False):
        raise SystemExit('This phone is not rooted for adb; the private-storage check needs su. Refusing.')
    owners = {a: APPS[a]['package'] for a in APPS}
    probe = lambda p: shell(f'pm path {p}; dumpsys package {p} | grep -E "versionName|lastUpdateTime"', check=False)
    before = {a: probe(p) for a, p in owners.items()}
    for a, p in owners.items():
        if shell(f'pm path {p}{COPY_SUFFIX}', check=False):
            raise SystemExit(f'{p}{COPY_SUFFIX} is already installed; refusing.')
    print('owner packages (read only):', json.dumps({a: bool(v) for a, v in before.items()}), flush=True)
    results = [Run(a).run() for a in (args.apps or APPS)]
    untouched = before == {a: probe(p) for a, p in owners.items()}
    print('owner packages unchanged (path, version, lastUpdateTime):', untouched, flush=True)
    (OUT / 'summary.json').write_text(json.dumps({'results': results, 'owner_unchanged': untouched}, indent=2) + '\n')
    for r in results:
        print(r['status'], r['app'])
    return 0 if all(r['status'] == 'PASS' for r in results) and untouched else 1


if __name__ == '__main__':
    sys.exit(main())
