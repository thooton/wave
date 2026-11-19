"""Tests for the setup snapshot sync check (run: python3 -m unittest discover -s site/scripts)."""
import contextlib
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import sync_setup


def descriptor(app, extra=''):
    return json.dumps({'format': 1, 'app': app, 'name': app.title(), 'package': f'com.{app}.xp3',
                       'about': 'x' + extra, 'settings': [], 'limits': []})


class SyncSetupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.packages = base / 'packages'
        for app in sync_setup.APPS:
            path = self.packages / sync_setup.asset(app)
            path.parent.mkdir(parents=True)
            path.write_text(descriptor(app))
        self.git('init', '-q')
        self.commit('first')
        self.first = self.head()
        setup = base / 'setup'
        setup.mkdir()
        self.patch = (sync_setup.OUTPUT, sync_setup.UPSTREAM)
        sync_setup.OUTPUT, sync_setup.UPSTREAM = setup / 'descriptors.mjs', setup / 'UPSTREAM'
        self.addCleanup(lambda: setattr(sync_setup, 'OUTPUT', self.patch[0]) or setattr(sync_setup, 'UPSTREAM', self.patch[1]))

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.packages), '-c', 'user.name=t', '-c', 'user.email=t@t', *args],
                              check=True, capture_output=True, text=True).stdout

    def commit(self, message):
        self.git('add', '-A')
        self.git('commit', '-q', '-m', message)

    def head(self):
        return self.git('rev-parse', 'HEAD').strip()

    def run_check(self, root):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            sync_setup.check(root)
        return out.getvalue(), err.getvalue()

    def test_sync_records_the_commit_and_check_passes(self):
        with contextlib.redirect_stdout(io.StringIO()):
            sync_setup.sync(self.packages)
        self.assertEqual(sync_setup.UPSTREAM.read_text(), f'{sync_setup.REPOSITORY}\n{self.first}\n')
        self.assertIn(self.first, sync_setup.OUTPUT.read_text().splitlines()[0])
        out, err = self.run_check(self.packages)
        self.assertIn('matches', out)
        self.assertEqual(err, '')
        self.assertIn('offline', self.run_check(None)[0])

    def test_changed_descriptor_in_a_newer_commit_is_noted_but_not_a_failure(self):
        with contextlib.redirect_stdout(io.StringIO()):
            sync_setup.sync(self.packages)
        (self.packages / sync_setup.asset('mail')).write_text(descriptor('mail', 'changed'))
        self.commit('newer')
        out, err = self.run_check(self.packages)
        self.assertIn('matches', out)
        self.assertIn('newer descriptor changes', err)

    def test_uncommitted_edits_are_not_picked_up(self):
        (self.packages / sync_setup.asset('mail')).write_text(descriptor('mail', 'dirty'))
        with contextlib.redirect_stdout(io.StringIO()):
            sync_setup.sync(self.packages)
        self.assertNotIn('dirty', sync_setup.OUTPUT.read_text())

    def test_hand_edited_snapshot_fails(self):
        with contextlib.redirect_stdout(io.StringIO()):
            sync_setup.sync(self.packages)
        sync_setup.OUTPUT.write_text(sync_setup.OUTPUT.read_text().replace('"about": "x"', '"about": "y"'))
        with self.assertRaises(SystemExit) as raised:
            self.run_check(self.packages)
        self.assertIn('differ', str(raised.exception))

    def test_record_and_snapshot_must_name_the_same_commit(self):
        with contextlib.redirect_stdout(io.StringIO()):
            sync_setup.sync(self.packages)
        (self.packages / sync_setup.asset('mail')).write_text(descriptor('mail', 'changed'))
        self.commit('newer')
        sync_setup.UPSTREAM.write_text(f'{sync_setup.REPOSITORY}\n{self.head()}\n')
        with self.assertRaises(SystemExit) as raised:
            self.run_check(None)
        self.assertIn('does not say', str(raised.exception))

    def test_missing_or_malformed_record_fails(self):
        with self.assertRaises(SystemExit):
            sync_setup.recorded()
        sync_setup.UPSTREAM.write_text('nonsense\n')
        with self.assertRaises(SystemExit):
            sync_setup.recorded()

    def test_checked_in_snapshot_is_recorded_against_a_real_commit(self):
        sync_setup.OUTPUT, sync_setup.UPSTREAM = self.patch
        out, _ = self.run_check(None)
        self.assertIn('recorded against', out)


if __name__ == '__main__':
    unittest.main()
