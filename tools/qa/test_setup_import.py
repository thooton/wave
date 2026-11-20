"""Host tests for tools/qa/setup: the app table against the site's descriptors, and the driver's refusals (no phone)."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE / 'setup'))
from apps import APPS, FAKE, SECRET_MARKERS  # noqa: E402


def descriptors():
    text = (ROOT / 'site/setup/descriptors.mjs').read_text()
    return {d['app']: d for d in json.loads(text[text.index('export const descriptors = ') + len('export const descriptors = '):].rstrip().rstrip(';'))}


class AppTable(unittest.TestCase):
    def test_covers_exactly_the_apps_the_site_describes(self):
        self.assertEqual(set(APPS), set(descriptors()))

    def test_ids_and_backends_match_the_descriptors(self):
        for app, info in APPS.items():
            with self.subTest(app):
                d = descriptors()[app]
                self.assertEqual(info['package'], d['package'])
                self.assertEqual(info['name'], d['name'])
                self.assertEqual(info['managed'], app in ('authenticator', 'flashcards'),
                                 'the two apps the guide says can use a command center only')
                self.assertTrue(d.get('delta', {}).get('supported') or not info['managed'])

    def test_direct_values_satisfy_the_descriptor_patterns_and_required_settings(self):
        for app, info in APPS.items():
            if info['managed']:
                continue
            with self.subTest(app):
                settings = {s['name']: s for s in descriptors()[app]['settings']}
                self.assertTrue({n for n, s in settings.items() if s.get('required')} <= set(info['values']))
                for name, value in info['values'].items():
                    self.assertIn(name, settings)
                    if settings[name].get('pattern'):
                        self.assertRegex(value, settings[name]['pattern'])

    def test_secret_values_carry_a_marker_the_screens_are_checked_for(self):
        for app, info in APPS.items():
            for name, value in info['values'].items():
                if name in ('server',) or name in ('baseUrl', 'email', 'clientId'):
                    continue
                with self.subTest(f"{app} {name}"):
                    self.assertTrue(any(m in value for m in SECRET_MARKERS), value)
        self.assertTrue(FAKE.startswith('QAfake'))

    def test_the_launcher_activities_are_the_apps_own_packages_or_namespaces(self):
        for app, info in APPS.items():
            self.assertTrue(info['activity'].endswith('.MainActivity'), app)


class Driver(unittest.TestCase):
    def run_import(self, *args):
        with tempfile.TemporaryDirectory() as temp:
            fake = Path(temp) / 'adb'
            log = Path(temp) / 'adb.log'
            fake.write_text('#!/bin/sh\necho "$@" >> "$FAKE_ADB_LOG"\nexit 1\n')
            fake.chmod(0o755)
            env = {**os.environ, 'PATH': f'{temp}:{os.environ["PATH"]}', 'FAKE_ADB_LOG': str(log)}
            done = subprocess.run([sys.executable, str(HERE / 'setup/import_setup.py'), *args], env=env,
                                  capture_output=True, text=True, timeout=30)
            return done, log.read_text() if log.exists() else ''

    def test_refuses_missing_inputs_and_unknown_apps_before_touching_adb(self):
        with tempfile.TemporaryDirectory() as temp:
            for args in (['--apks', temp, '--files', temp, '--out', temp + '/o'],
                         ['--apks', temp, '--files', temp, '--out', temp + '/o', 'print']):
                with self.subTest(args):
                    done, adb = self.run_import(*args)
                    self.assertEqual(done.returncode, 2, done.stderr)
                    self.assertEqual(adb, '')

    def test_refuses_when_the_phone_is_not_the_reserved_one(self):
        with tempfile.TemporaryDirectory() as temp:
            for app in APPS:
                (Path(temp) / f'{app}.apk').write_bytes(b'')
                (Path(temp) / f'wave-setup-{app}.json').write_text('{}')
            done, adb = self.run_import('--apks', temp, '--files', temp, '--out', temp + '/o')
            self.assertNotEqual(done.returncode, 0)
            self.assertIn('Not the reserved XP3800', done.stderr + done.stdout)
            self.assertNotRegex(adb, r'install|push|reboot')

    def test_the_scripts_hold_no_machine_specific_paths(self):
        for path in (HERE / 'setup').glob('*.py'):
            self.assertNotIn('/home/sam', path.read_text(), path.name)


if __name__ == '__main__':
    unittest.main()
