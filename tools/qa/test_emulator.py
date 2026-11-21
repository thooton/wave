"""Local transport and adb-routing tests; a real boot is checked in Actions."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / ".github/actions/emulator"))
from mailbox import GitError, Mailbox, MissingMailbox, ref  # noqa: E402
from runner import Device, selected  # noqa: E402

SESSION = "app-0123456789abcdef"


class MailboxTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.remote = self.root / "remote.git"
        subprocess.run(["git", "init", "-q", "--bare", self.remote], check=True)

    def peer(self, name):
        path = self.root / name
        path.mkdir()
        box = Mailbox(path, str(self.remote), SESSION)
        box.git("init", "-q", "--bare")
        return box

    def test_independent_shallow_clients_and_binary_result(self):
        client, runner = self.peer("starter"), self.peer("runner")
        client.push(client.commit({"kind": "starting"}))
        head, frame = runner.read()
        self.assertEqual(frame, {"kind": "starting"})
        runner.push(runner.commit({"kind": "ready"}, parent=head))

        driver = self.peer("driver")
        head, frame = driver.read()
        self.assertEqual(frame, {"kind": "ready"})
        request = driver.commit({"kind": "request", "op": "shot", "args": []}, parent=head)
        driver.push(request)
        self.assertEqual(runner.read(), (request, {"kind": "request", "op": "shot", "args": []}))
        output = b"\x89PNG\r\n\x1a\n\0\xff"
        result = {"kind": "result", "request": request, "ok": True, "op": "shot"}
        runner.push(runner.commit(result, parent=request, output=output))
        head, frame = driver.read()
        self.assertEqual(frame, result)
        self.assertEqual(driver.output(head), output)
        driver.delete(SESSION)
        with self.assertRaises(MissingMailbox):
            runner.read()

    def test_two_drivers_cannot_overwrite_a_pending_command(self):
        a, b = self.peer("a"), self.peer("b")
        ready = a.commit({"kind": "ready"})
        a.push(ready)
        parent, _ = b.read()
        a.push(a.commit({"kind": "request", "op": "key"}, parent=ready))
        with self.assertRaises(GitError):
            b.push(b.commit({"kind": "request", "op": "text"}, parent=parent))

    def test_invalid_session(self):
        for value in ("master", "app-0123456789abcdef/start", "app-01234567"):
            with self.assertRaises(ValueError):
                ref(value, "io")


class DeviceTest(unittest.TestCase):
    def setUp(self):
        self.device = Device(Path("/fake-sdk"), "org.example.app")
        self.adb = self.device.adb = Mock(return_value=b"")

    @patch("runner.time.sleep")
    def test_key_maps_digits_names_and_numeric_codes(self, _sleep):
        self.device.execute("key", ["dpad_down", "5", "66"])
        self.assertEqual([call.args[-1] for call in self.adb.call_args_list],
                         ["KEYCODE_DPAD_DOWN", "KEYCODE_5", "66"])
        with self.assertRaises(ValueError):
            self.device.execute("key", ["DPAD_UP; ls"])

    def test_shell_routes_to_device_not_host_and_text_stays_quoted(self):
        self.device.execute("shell", ["printf hi; pwd"])
        self.adb.assert_called_once_with("shell", "printf hi; pwd")
        self.device.execute("text", ["a b; c"])
        self.adb.assert_called_with("shell", "input text 'a%sb;%sc'")
        with self.assertRaises(ValueError):
            self.device.execute("text", ["a%sb"])

    def test_requires_a_png_and_accepts_binary_output(self):
        with self.assertRaises(RuntimeError):
            self.device.execute("shot", [])
        png = b"\x89PNG\r\n\x1a\n\0\xff"
        self.adb.return_value = png
        self.assertEqual(self.device.execute("shot", []), png)
        self.adb.assert_called_with("exec-out", "screencap", "-p")

    def test_rejects_non_wave_runner_before_sdk_use(self):
        env = {"GITHUB_REPOSITORY": "some/other", "GITHUB_REF": ref(SESSION, "start")}
        with patch.dict("runner.os.environ", env), self.assertRaises(ValueError):
            selected()


if __name__ == "__main__":
    unittest.main()
