"""Exercise the shared phone lock without connecting a device."""
import os
from pathlib import Path
import select
import signal
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().with_name('phone-run.sh')


class PhoneLockTests(unittest.TestCase):
    def setUp(self):
        temp = self.enterContext(tempfile.TemporaryDirectory())
        self.lock = Path(temp) / 'phone.lock'
        self.env = {**os.environ, 'PHONE_LOCK_DIR': str(self.lock)}
        self.prefix = [str(SCRIPT), '--who', 'test', '--wait', '0', '--']

    def run_locked(self, *args):
        return subprocess.run(self.prefix + list(args), env=self.env, capture_output=True, timeout=5)

    def test_holds_lock_during_command_and_releases_on_failure(self):
        done = self.run_locked('bash', '-c', 'test -s "$PHONE_LOCK_DIR/owner" && exit 7')
        self.assertEqual(done.returncode, 7, done.stderr)
        self.assertFalse(self.lock.exists())

    def test_busy_lock_is_left_alone(self):
        self.lock.mkdir()
        owner = self.lock / 'owner'
        owner.write_text('another user\n')
        marker = self.lock.parent / 'ran'
        self.assertEqual(self.run_locked('touch', str(marker)).returncode, 75)
        self.assertFalse(marker.exists())
        self.assertEqual(owner.read_text(), 'another user\n')

    def test_termination_stops_command_and_releases_lock(self):
        with subprocess.Popen(self.prefix + ['bash', '-c', 'echo $$; exec sleep 60'],
                              env=self.env, stdout=subprocess.PIPE, text=True) as proc:
            try:
                self.assertTrue(select.select([proc.stdout], [], [], 5)[0], 'locked command did not start')
                child = int(proc.stdout.readline())
                proc.send_signal(signal.SIGTERM)
                self.assertEqual(proc.wait(timeout=15), 143)
                with self.assertRaises(ProcessLookupError):
                    os.kill(child, 0)
                self.assertFalse(self.lock.exists())
            finally:
                if proc.poll() is None:
                    proc.kill()
