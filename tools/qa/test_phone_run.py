import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest

SCRIPT = Path(__file__).resolve().parent / 'phone-run.sh'


class PhoneRun(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.dir = Path(self.temp.name)
        self.lock = self.dir / 'phone.lock'
        self.env = {**os.environ, 'PHONE_LOCK_DIR': str(self.lock)}
        self.env.pop('WAVE_PHONE_LOCK_TOKEN', None)

    def tearDown(self):
        self.temp.cleanup()

    def start(self, *args, wait='5'):
        return subprocess.Popen([str(SCRIPT), '--who', 'agent9 test', '--wait', wait, '--', *args],
                                env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def wait_for(self, path, seconds=10):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if path.exists():
                return
            time.sleep(0.05)
        self.fail(f'{path} never appeared')

    def test_normal_path_holds_the_lock_only_while_the_command_runs(self):
        probe = self.dir / 'owner-seen'
        done = subprocess.run(
            [str(SCRIPT), '--who', 'agent9 test', '--', 'bash', '-c',
             f'cat "{self.lock}/owner" > "{probe}"; echo "$WAVE_PHONE_LOCK_TOKEN" > "{self.dir}/token"; exit 0'],
            env=self.env, capture_output=True, text=True, timeout=20)
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertTrue(probe.read_text().startswith('agent9 test [phone-run pid='))
        self.assertEqual(probe.read_text(), (self.dir / 'token').read_text())
        self.assertFalse(self.lock.exists())

    def test_command_failure_passes_through_and_releases(self):
        done = subprocess.run([str(SCRIPT), '--who', 'agent9 test', '--', 'bash', '-c', 'exit 7'],
                              env=self.env, capture_output=True, text=True, timeout=20)
        self.assertEqual(done.returncode, 7)
        self.assertFalse(self.lock.exists())
        done = subprocess.run([str(SCRIPT), '--who', 'agent9 test', '--', 'no-such-command-wave'],
                              env=self.env, capture_output=True, text=True, timeout=20)
        self.assertEqual(done.returncode, 127)
        self.assertFalse(self.lock.exists())

    def test_interrupt_and_terminate_stop_the_command_and_release(self):
        for sig, code in ((signal.SIGINT, 130), (signal.SIGTERM, 143), (signal.SIGHUP, 129)):
            with self.subTest(signal=sig.name):
                pidfile = self.dir / f'child-{sig.name}'
                proc = self.start('bash', '-c', f'echo $$ > "{pidfile}"; exec sleep 60')
                self.wait_for(pidfile)
                child = int(pidfile.read_text())
                self.assertTrue(self.lock.is_dir())
                proc.send_signal(sig)
                proc.communicate(timeout=20)
                self.assertEqual(proc.returncode, code)
                self.assertFalse(self.lock.exists())
                with self.assertRaises(ProcessLookupError):
                    os.kill(child, 0)

    def test_busy_phone_times_out_without_running_and_leaves_the_holder_alone(self):
        self.lock.mkdir()
        (self.lock / 'owner').write_text('agent1 first install\n')
        ran = self.dir / 'ran'
        start = time.monotonic()
        proc = self.start('touch', str(ran), wait='3')
        out, err = proc.communicate(timeout=20)
        self.assertEqual(proc.returncode, 75)
        self.assertGreaterEqual(time.monotonic() - start, 3)
        self.assertIn('agent1 first install', err)
        self.assertFalse(ran.exists())
        self.assertEqual((self.lock / 'owner').read_text(), 'agent1 first install\n')

    def test_waits_for_a_release_then_runs(self):
        self.lock.mkdir()
        (self.lock / 'owner').write_text('agent1 short job\n')
        ran = self.dir / 'ran'
        proc = self.start('touch', str(ran), wait='30')
        time.sleep(3)
        self.assertFalse(ran.exists())
        (self.lock / 'owner').unlink()
        self.lock.rmdir()
        out, err = proc.communicate(timeout=30)
        self.assertEqual(proc.returncode, 0, err)
        self.assertTrue(ran.exists())
        self.assertFalse(self.lock.exists())

    def test_does_not_remove_a_lock_someone_else_took_over(self):
        gate = self.dir / 'gate'
        proc = self.start('bash', '-c', f'while [ ! -e "{gate}" ]; do sleep 0.1; done')
        self.wait_for(self.lock / 'owner')
        (self.lock / 'owner').write_text('agent1 took it\n')
        gate.touch()
        proc.communicate(timeout=20)
        self.assertEqual((self.lock / 'owner').read_text(), 'agent1 took it\n')

    def test_usage_errors_run_nothing(self):
        for args in ([], ['--who', 'x'], ['--', 'true'], ['--who', 'x', '--wait', 'soon', '--', 'true']):
            done = subprocess.run([str(SCRIPT), *args], env=self.env, capture_output=True, text=True, timeout=20)
            self.assertEqual(done.returncode, 64, args)
        self.assertFalse(self.lock.exists())

    def test_run_py_accepts_only_the_matching_token(self):
        import sys
        sys.path.insert(0, str(SCRIPT.parent))
        import run
        from unittest.mock import patch
        self.lock.mkdir()
        (self.lock / 'owner').write_text('agent9 test [phone-run pid=1 2]\n')
        with patch.object(run, 'LOCK', self.lock):
            with patch.dict(os.environ, {'WAVE_PHONE_LOCK_TOKEN': 'agent9 test [phone-run pid=1 2]'}):
                with run.phone_lock():
                    pass
            self.assertTrue(self.lock.is_dir(), 'run.py must not release a lock it did not take')
            with patch.dict(os.environ, {'WAVE_PHONE_LOCK_TOKEN': 'someone else'}):
                with self.assertRaises(run.HarnessError):
                    with run.phone_lock():
                        pass


if __name__ == '__main__':
    unittest.main()
