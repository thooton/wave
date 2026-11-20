"""Host tests for the rooted suite's refusals and validation. They never touch a phone: adb is a fake on PATH."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / 'rooted'))
import run  # noqa: E402
import rooted  # noqa: E402
import suite  # noqa: E402

SCENARIOS = sorted((HERE / 'rooted/scenarios').glob('R*.json'))


def read(path, **kwargs):
    return run.read_scenario(path, rooted=True, check_do=rooted.check_do, **kwargs)


class RootedScenarios(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.dir = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, doc):
        path = self.dir / 's.json'
        path.write_text(json.dumps(doc))
        return path

    def steps(self, *more):
        return [{'name': 'Launch', 'action': 'launch', 'wait_ms': 100}, *more]

    def test_shipped_scenarios_are_valid_and_marked_rooted(self):
        self.assertEqual([p.name[:3] for p in SCENARIOS], ['R01', 'R02', 'R03', 'R04'])
        for path in SCENARIOS:
            with self.subTest(path.name):
                self.assertIs(json.loads(path.read_text())['rooted'], True)
                self.assertTrue(read(path)['steps'])

    def test_rooted_scenarios_cannot_run_through_the_root_free_reader(self):
        for path in SCENARIOS:
            with self.subTest(path.name), self.assertRaises(run.HarnessError):
                run.read_scenario(path)

    def test_a_scenario_must_say_rooted(self):
        with self.assertRaises(run.HarnessError):
            read(self.write({'name': 'x', 'steps': self.steps()}))
        with self.assertRaises(run.HarnessError):
            read(self.write({'name': 'x', 'rooted': False, 'steps': self.steps()}))

    def test_do_steps_are_checked(self):
        for bad in ({'name': 'a', 'do': 'format'}, {'name': 'a', 'do': 'su'}, {'name': 'a', 'do': 'su', 'cmd': ''},
                    {'name': 'a', 'do': 'reboot', 'how': 'adb'}, {'name': 'a', 'do': 'pregrant', 'cmd': 'id'},
                    {'name': 'a', 'do': 'wait_su', 'cmd': 'id', 'expect': '('},
                    {'name': 'a', 'do': 'wait_boot', 'timeout_s': 5000}, {'name': 'a', 'do': 'su', 'cmd': 'id', 'key': 'ENTER'}):
            with self.subTest(bad), self.assertRaises(run.HarnessError):
                read(self.write({'name': 'x', 'rooted': True, 'steps': self.steps(bad)}))
        good = {'name': 'a', 'do': 'su', 'cmd': 'id', 'expect': 'uid=0'}
        self.assertEqual(len(read(self.write({'name': 'x', 'rooted': True, 'steps': self.steps(good)}))['steps']), 2)

    def test_do_and_rooted_confirm_are_refused_in_the_root_free_suite(self):
        with self.assertRaises(run.HarnessError):
            run.read_scenario(self.write({'name': 'x', 'steps': self.steps({'name': 'a', 'do': 'su', 'cmd': 'id'})}))
        with self.assertRaises(run.HarnessError):
            run.read_scenario(self.write({'name': 'x', 'steps': self.steps(
                {'name': 'a', 'key': 'ENTER', 'before_text': ['Confirm'], 'rooted_confirm': True})}))

    def test_rooted_confirm_only_on_enter(self):
        step = {'name': 'a', 'key': 'DPAD_DOWN', 'rooted_confirm': True}
        with self.assertRaises(run.HarnessError):
            read(self.write({'name': 'x', 'rooted': True, 'steps': self.steps(step)}))

    def test_the_default_suite_never_picks_up_rooted_scenarios(self):
        names = [p.name for p in sorted(HERE.glob('scenarios/07-*.json'))]
        self.assertTrue(names and not any(n.startswith('R') for n in names))
        self.assertFalse(list(HERE.glob('scenarios/R*.json')))

    def test_only_safe_keys_still_hold(self):
        with self.assertRaises(run.HarnessError):
            read(self.write({'name': 'x', 'rooted': True, 'steps': self.steps({'name': 'a', 'key': 'POWER'})}))


class RootedPreflight(unittest.TestCase):
    def test_refuses_unrooted_and_unexpected_grants(self):
        self.assertIn('REFUSED', suite.rooted_preflight(lambda c: (1, 'su: not found')))
        self.assertIn('REFUSED', suite.rooted_preflight(lambda c: (0, 'uid=2000(shell)')))
        self.assertIn('REFUSED', suite.rooted_preflight(lambda c: (0, 'uid=0(root)') if c == 'id' else (0, 'uid=2000\nuid=10123')))
        self.assertIn('REFUSED', suite.rooted_preflight(lambda c: (0, 'uid=0(root)') if c == 'id' else (1, '')))

    def test_accepts_the_clean_rooted_phone(self):
        self.assertIsNone(suite.rooted_preflight(lambda c: (0, 'uid=0(root) gid=0') if c == 'id' else (0, 'policy=2|uid=2000')))

    def test_suite_cli_refuses_an_unrooted_phone_before_anything_runs(self):
        with tempfile.TemporaryDirectory() as temp:
            fake = Path(temp) / 'adb'
            fake.write_text('#!/bin/sh\necho "$@" >> "$FAKE_ADB_LOG"\necho "su: inaccessible or not found"\nexit 1\n')
            fake.chmod(0o755)
            log, out = Path(temp) / 'adb.log', Path(temp) / 'out'
            env = {**os.environ, 'PATH': f'{temp}:{os.environ["PATH"]}', 'FAKE_ADB_LOG': str(log),
                   'WAVE_PHONE_LOCK_TOKEN': 'test'}
            apk = Path(temp) / 'x.apk'
            apk.write_bytes(b'')
            done = subprocess.run([sys.executable, str(HERE / 'suite.py'), '--rooted', '--apk', str(apk), '--out', str(out)],
                                  env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(done.returncode, 2, done.stdout + done.stderr)
            self.assertIn('REFUSED', done.stdout)
            self.assertFalse(out.exists())
            self.assertNotRegex(log.read_text(), r'install|reboot|uninstall')

    def test_suite_cli_refuses_a_non_rooted_scenario_file(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / 'x.apk'
            apk.write_bytes(b'')
            done = subprocess.run([sys.executable, str(HERE / 'suite.py'), '--rooted', '--apk', str(apk),
                                   str(HERE / 'scenarios/07-browse.json')], capture_output=True, text=True, timeout=30)
            self.assertEqual(done.returncode, 2)
            self.assertIn('not a rooted scenario', done.stderr)


if __name__ == '__main__':
    unittest.main()
