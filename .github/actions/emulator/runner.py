#!/usr/bin/env python3
"""Actions side of a Wave XP3 session. Remote commands run only inside Android."""
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time

from mailbox import GitError, Mailbox, MAX_OUTPUT, MissingMailbox, POLL, SESSION, repository, runner_env

IMAGE = "system-images;android-27;default;x86_64"
SERIAL = "emulator-5554"
CONFIG = {
    "hw.lcd.width": 240, "hw.lcd.height": 320, "hw.lcd.density": 160,
    "hw.keyboard": "yes", "hw.dPad": "yes", "hw.mainKeys": "yes",
    "hw.ramSize": 1536, "vm.heapSize": 128,
    "hw.camera.back": "none", "hw.camera.front": "none", "hw.audioInput": "no",
    "skin.path": "_no_skin", "skin.name": "240x320", "showDeviceFrame": "no",
}


def checked(cmd, *, stdin=None, timeout=45):
    p = subprocess.run(cmd, input=stdin, timeout=timeout, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if p.returncode:
        raise RuntimeError(f"{Path(cmd[0]).name} exited {p.returncode}: {p.stdout[-3000:].decode(errors='replace')}")
    return p.stdout


def selected():
    repo = repository(os.environ["GITHUB_REPOSITORY"])
    match = re.fullmatch(r"refs/heads/emulator/([^/]+)/start", os.environ["GITHUB_REF"])
    parsed = SESSION.fullmatch(match[1]) if match else None
    if not parsed:
        raise ValueError("invalid session launch branch; use tools/qa/emulator start")
    app = parsed[1]
    if repo == "thooton/wave" and app != "app":
        raise ValueError("the Wave checkout's app directory is 'app'")
    roots = [Path(app) / "android/app", Path(app) / "app"]
    module = next((p for p in roots if (p / "build.gradle").is_file()), None)
    if module is None:
        raise ValueError(f"{app!r} is not a Gradle Android app in this checkout")
    return repo, match[1], app, module / "build/outputs/apk/debug/app-debug.apk"


class Device:
    def __init__(self, sdk, package):
        self.sdk = sdk
        self.package = package

    def adb(self, *args, **kwargs):
        return checked([str(self.sdk / "platform-tools/adb"), "-s", SERIAL, *args], **kwargs)

    def boot(self, log, apk):
        avd = Path.home() / ".android/avd"
        os.environ["ANDROID_AVD_HOME"] = str(avd)
        avd.mkdir(parents=True, exist_ok=True)
        checked(["avdmanager", "create", "avd", "-n", "wave_xp3", "-k", IMAGE, "-d", "pixel", "--force"],
                stdin=b"no\n", timeout=120)
        ini = avd / "wave_xp3.avd/config.ini"
        lines = [line for line in ini.read_text().splitlines() if line.split("=", 1)[0].strip() not in CONFIG]
        ini.write_text("\n".join(lines + [f"{k}={v}" for k, v in CONFIG.items()]) + "\n")
        process = subprocess.Popen([str(self.sdk / "emulator/emulator"), "-avd", "wave_xp3", "-port", "5554",
                                    "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-accel", "on",
                                    "-gpu", "swiftshader_indirect"], stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 600
            while process.poll() is None and time.monotonic() < deadline:
                try:
                    if self.adb("shell", "getprop", "sys.boot_completed", timeout=10).strip() == b"1":
                        break
                except (RuntimeError, subprocess.TimeoutExpired):
                    pass
                time.sleep(3)
            else:
                raise RuntimeError("emulator exited or did not finish booting")
            props = [self.adb("shell", "getprop", name).decode().strip()
                     for name in ("ro.kernel.qemu", "ro.build.version.sdk", "ro.build.version.release")]
            size = self.adb("shell", "wm size").decode().strip()
            density = self.adb("shell", "wm density").decode().strip()
            if props[:2] != ["1", "27"] or size != "Physical size: 240x320" or density != "Physical density: 160":
                raise RuntimeError(f"unexpected device: {props}, {size}, {density}")
            for command in ("settings put global window_animation_scale 0",
                            "settings put global transition_animation_scale 0",
                            "settings put global animator_duration_scale 0",
                            "settings put global stay_on_while_plugged_in 7",
                            "settings put secure show_ime_with_hard_keyboard 0",
                            "wm dismiss-keyguard", "input keyevent KEYCODE_WAKEUP"):
                self.adb("shell", command)
            install = self.adb("install", "-r", str(apk), timeout=120)
            if not install.rstrip().endswith(b"Success"):
                raise RuntimeError(f"APK installation failed: {install!r}")
            return process, f"Android {props[2]} (API 27), 240x320 mdpi; installed {self.package}"
        except BaseException:
            stop_emulator(process)
            raise

    def execute(self, op, args):
        if (not isinstance(args, list) or any(not isinstance(x, str) for x in args)
                or len(args) > 40 or sum(len(x.encode()) for x in args) > 2048):
            raise ValueError("invalid command arguments")
        no_args = {"launch", "shot", "ui", "stop", "reset", "close"}
        if op in no_args and args:
            raise ValueError(f"{op} takes no arguments")
        if op == "launch":
            result = self.adb("shell", "monkey", "-p", self.package, "-c", "android.intent.category.LAUNCHER", "1")
            if b"Events injected: 1" not in result:
                raise RuntimeError(result.decode(errors="replace"))
            return result
        if op == "key":
            if not args or any(not re.fullmatch(r"[A-Z0-9_]{1,48}", key.upper()) for key in args):
                raise ValueError("supply Android key names, for example DPAD_DOWN DPAD_CENTER BACK 5")
            for key in args:
                key = key.upper()
                code = key if key.isdigit() and len(key) > 1 else "KEYCODE_" + key.removeprefix("KEYCODE_")
                self.adb("shell", "input", "keyevent", code, timeout=5)
                time.sleep(0.15)
            return b""
        if op == "text":
            if len(args) != 1 or not re.fullmatch(r"[ -~]+", args[0]) or "%s" in args[0]:
                raise ValueError("pass one quoted printable ASCII string (no literal %s)")
            return self.adb("shell", "input text " + shlex.quote(args[0].replace(" ", "%s")))
        if op == "shot":
            data = self.adb("exec-out", "screencap", "-p")
            if not data.startswith(b"\x89PNG\r\n\x1a\n"):
                raise RuntimeError("adb did not return a PNG screenshot")
            return data
        if op == "ui":
            self.adb("shell", "uiautomator", "dump", "/sdcard/wave-ui.xml")
            data = self.adb("exec-out", "cat", "/sdcard/wave-ui.xml")
            if not data.startswith(b"<?xml"):
                raise RuntimeError("adb did not return an XML hierarchy")
            return data
        if op == "log" and args in ([], ["--clear"]):
            return self.adb("logcat", "-c") if args else self.adb("logcat", "-d", "-t", "300")
        if op == "stop":
            return self.adb("shell", "am", "force-stop", self.package)
        if op == "reset":
            return self.adb("shell", "pm", "clear", self.package)
        if op == "shell" and len(args) == 1:
            return self.adb("shell", args[0])
        if op == "close":
            return b""
        raise ValueError(f"invalid arguments or operation: {op!r}")


def stop_emulator(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def serve(box, device, apk, tmp):
    head, frame = box.read()
    if frame["kind"] != "starting":
        raise RuntimeError("this session was already used; start a new session instead of rerunning")
    log_path = tmp / "emulator.log"
    with log_path.open("wb") as log:
        try:
            process, summary = device.boot(log, apk)
        except Exception:
            with log_path.open("rb") as contents:
                contents.seek(max(0, log_path.stat().st_size - 6000))
                print(contents.read().decode(errors="replace"), file=sys.stderr)
            raise
    try:
        box.push(box.commit({"kind": "ready", "summary": summary}, parent=head))
        print(summary, flush=True)
        last = None
        failures = 0
        while process.poll() is None:
            try:
                head, frame = box.read()
                failures = 0
            except MissingMailbox:
                print("Mailbox removed; stopping", flush=True)
                return
            except (GitError, subprocess.TimeoutExpired):
                failures += 1
                if failures >= 5:
                    raise
                time.sleep(POLL)
                continue
            if head != last and frame["kind"] == "request":
                last = head
                op = frame.get("op")
                reply = {"kind": "result", "request": head, "op": op, "ok": True}
                output = None
                try:
                    output = device.execute(op, frame.get("args"))
                    if len(output) > MAX_OUTPUT:
                        raise ValueError("command output exceeded 2 MiB")
                    if op == "close":
                        reply["kind"] = "closed"
                        output = None
                except (RuntimeError, ValueError, subprocess.TimeoutExpired, TypeError) as exc:
                    reply.update(ok=False, error=str(exc)[:3000])
                    output = None
                box.push(box.commit(reply, parent=head, output=output))
                if reply["kind"] == "closed":
                    return
            time.sleep(POLL)
        raise RuntimeError("emulator stopped unexpectedly")
    finally:
        stop_emulator(process)


def main():
    repo, session, app, apk = selected()
    if sys.argv[1:] == ["build"]:
        subprocess.run(["./gradlew", "--no-daemon", f":{app}:app:assembleDebug"], check=True)
        if not apk.is_file():
            raise RuntimeError(f"debug APK not found: {apk}")
    elif sys.argv[1:] == ["serve"]:
        sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
        url = f"https://github.com/{repo}"
        env = runner_env(url)
        package = checked([str(sdk / "build-tools/35.0.1/aapt2"), "dump", "packagename", str(apk)]).decode().strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.]*", package):
            raise ValueError("invalid APK package name")
        with tempfile.TemporaryDirectory(prefix="wave-emu-") as tmp:
            box = Mailbox(tmp, url, session, env=env)
            box.git("init", "-q", "--bare")
            serve(box, Device(sdk, package), apk, Path(tmp))
    else:
        raise ValueError("expected build or serve")


if __name__ == "__main__":
    main()
