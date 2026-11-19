#!/usr/bin/env python3
"""Runs Wave's boot watchdog through pretend boots of a phone with Magisk 24.0.

What is real:
- the shell and applets: Magisk 24.0's own busybox, taken from its APK. The
  x86_64 build runs here; its applet table is byte-identical to the
  armeabi-v7a build the XP3 uses, which this checks;
- how magiskd runs things (boot.sh, copied step by step from v24.0's
  bootstages.cpp, scripting.cpp and module.cpp): the environment (env -i,
  ASH_STANDALONE=1, PATH), which files run, the 35 s post-fs-data budget, the
  order (post-fs-data.d before modules are collected), safe mode;
- the file layout: /data/adb, /sbin/.magisk/busybox (MAGISKTMP on a
  non-system-as-root Android 8.1), /data/adb/magisk/util_functions.sh;
- module installs: Wave's own installModule script, then `magisk
  --install-module` runs the APK's util_functions.sh install_module;
- Wave's scripts, exactly as the app builds them (Gen.java), and the root-tests
  modules.
What is pretend: getprop, setprop, /system/bin/reboot, zygote and
system_server, the passing of time in Android, and mounting partitions.

  app/root-tests/magisk/sim.py [--apk ../rphone/Magisk-v24.0.apk] [--fast] [SCENARIO...]

--fast shortens the watchdog's waits through its WAVE_* settings (which
Magisk never sets) so the whole run takes about two minutes instead of ten.
Needs bwrap and a built :app:core (classes for Gen.java).
"""
import argparse
import concurrent.futures
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
JAVA = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk") + "/bin/java"
CLASSES = REPO / "app/core/build/classes/java/main"
RESOURCES = REPO / "app/core/build/resources/main"
# init's PATH on the XP3 (Android 8.1, as `adb shell echo $PATH` shows), which
# magiskd and `su` pass on.
INITPATH = "/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin"
# The XP3's /system/bin commands that Wave's root scripts use (toybox, and grep
# on its own). Here busybox stands in for them; the phone run checks the real ones.
SYSTEM_BIN = ("cat", "chmod", "chown", "cut", "date", "grep", "head", "ls", "mkdir", "mv", "pidof", "printf",
              "readlink", "rm", "sed", "sleep", "sort", "stat", "sync", "tail", "touch", "tr", "wc")


def unquote(text):
    """Shell text with the insides of quoted strings blanked, keeping $(...) in them."""
    out, i, quote = [], 0, None
    while i < len(text):
        ch = text[i]
        if quote is None:
            if ch in "'\"":
                quote = ch
            out.append(ch)
        elif ch == quote:
            quote = None
            out.append(ch)
        elif quote == '"' and text.startswith("$(", i):
            j = text.index(")", i)
            out.append(text[i:j + 1])
            i = j
        elif ch == "\n":
            out.append(ch)
        i += 1
    return "".join(out)


class Phone:
    """One pretend phone: its /data and the pretend Android state, kept between boots."""

    def __init__(self, work, magisk, scripts, fast):
        self.root = Path(tempfile.mkdtemp(prefix="phone-", dir=work))
        self.data = self.root / "data"
        self.fake = self.root / "fake"
        self.magisk = magisk
        self.scripts = scripts
        self.fast = fast
        adb = self.data / "adb"
        # magisk_env(): /data/adb/magisk holds busybox and util_functions.sh,
        # and magiskd makes modules, post-fs-data.d and service.d.
        for d in ("magisk", "modules", "post-fs-data.d", "service.d"):
            (adb / d).mkdir(parents=True)
        shutil.copy(magisk / "busybox", adb / "magisk/busybox")
        shutil.copy(magisk / "util_functions.sh", adb / "magisk/util_functions.sh")
        (self.data / "local/tmp").mkdir(parents=True)
        (self.fake / "props").mkdir(parents=True)
        for f in ("pfs.out", "service.out", "calls", "events"):
            (self.fake / f).write_text("")
        self.prop("ro.build.version.sdk", "27")
        self.prop("ro.product.cpu.abi", "armeabi-v7a")
        self.boots = 0

    def prop(self, name, value):
        (self.fake / "props" / name).write_text(value + "\n")

    def sandbox(self, *command):
        bb = self.magisk / "busybox"
        args = [
            "bwrap", "--unshare-user", "--uid", "0", "--gid", "0", "--unshare-pid",
            "--die-with-parent", "--new-session",
            "--ro-bind", "/usr", "/usr", "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib", "/lib64",
            "--dir", "/sbin/.magisk/busybox", "--ro-bind", str(bb), "/sbin/.magisk/busybox/busybox",
            "--ro-bind", str(HERE / "stubs/magisk"), "/sbin/magisk",
            "--dir", "/system/bin", "--ro-bind", str(bb), "/system/bin/sh",
        ]
        for c in SYSTEM_BIN:
            args += ["--ro-bind", str(bb), "/system/bin/" + c]
        for stub in ("getprop", "setprop", "reboot"):
            args += ["--ro-bind", str(HERE / "stubs" / stub), "/system/bin/" + stub]
        args += [
            "--bind", str(self.data), "/data", "--bind", str(self.fake), "/fake",
            "--ro-bind", str(HERE), "/sim", "--ro-bind", str(self.scripts), "/wave",
            "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp",
        ]
        return subprocess.run(args + list(command), capture_output=True, text=True)

    def su(self, script, **subst):
        """A Wave root script, with the phone up (a zygote running, as install_module checks)."""
        text = (self.scripts / script).read_text()
        for k, v in subst.items():
            text = text.replace(k, v)
        (self.fake / "cmd.sh").write_text(text)
        r = self.sandbox(
            "/sbin/.magisk/busybox/busybox", "sh", "-c",
            "/usr/bin/bash -c 'exec -a zygote /usr/bin/sleep 1000' & z=$!; "
            "/sbin/.magisk/busybox/busybox env -i PATH=" + INITPATH + " /sbin/.magisk/busybox/busybox sh /fake/cmd.sh; "
            "rc=$?; kill $z; exit $rc")
        return r

    def install_module(self, zip_path):
        mod_id = zipfile.ZipFile(zip_path).read("module.prop").decode().split("id=")[1].split("\n")[0]
        shutil.copy(zip_path, self.data / "local/tmp/module.zip")
        r = self.su("install-module.sh", wave_module_id=mod_id)
        (self.data / "local/tmp/module.zip").unlink()
        return r

    def boot(self, **scenario):
        self.boots += 1
        for f in ("zygote.stopped", "rebooted"):
            (self.fake / f).unlink(missing_ok=True)
        for p in ("sys.boot_completed", "sys.powerctl", "sys.shutdown.requested"):
            (self.fake / "props" / p).unlink(missing_ok=True)
        lines = [f"{k}={v}" for k, v in scenario.items()]
        (self.fake / "scenario").write_text("\n".join(lines) + "\n")
        with open(self.fake / "events", "a") as e:
            e.write(f"--- boot {self.boots} {scenario}\n")
        if self.fast:
            # Only the waits change: 300 s -> 40 s, 60 s -> 10 s.
            for f in (self.data / "adb/service.d").glob("*.sh"):
                text = f.read_text()
                if "LIMIT=${WAVE_BOOT_LIMIT:-300}" in text:
                    text = text.replace("${WAVE_BOOT_LIMIT:-300}", "${WAVE_BOOT_LIMIT:-40}")
                    text = text.replace("${WAVE_SETTLE:-60}", "${WAVE_SETTLE:-10}")
                    f.write_text(text)
        r = self.sandbox("/sbin/.magisk/busybox/busybox", "sh", "/sim/boot.sh")
        if r.returncode or r.stderr.strip():
            raise AssertionError(f"boot.sh failed: {r.returncode} {r.stderr}")
        return self.events_of(self.boots)

    def events_of(self, n):
        text = (self.fake / "events").read_text()
        part = text.split(f"--- boot {n} ")[1]
        return part.split("--- boot ")[0]

    def adb(self, rel):
        return self.data / "adb" / rel

    def read(self, rel):
        p = self.adb(rel)
        return p.read_text() if p.exists() else None


class Checks:
    def __init__(self, name):
        self.name = name
        self.lines = []
        self.failed = 0

    def ok(self, what, cond, detail=""):
        self.lines.append(("PASS " if cond else "FAIL ") + what + ("" if cond else f"  [{detail}]"))
        if not cond:
            self.failed += 1


def common(phone, c):
    """The watchdog and a module, put in place by Wave's own scripts."""
    r = phone.su("install-watchdog.sh")
    c.ok("Wave's watchdog install exits 0 and says so", r.returncode == 0 and re.search(r"installed \d+", r.stdout), r.stdout + r.stderr)
    for d in ("post-fs-data.d", "service.d"):
        f = phone.adb(d + "/wave-watchdog.sh")
        c.ok(f"{d}/wave-watchdog.sh is a regular file with mode 755", f.is_file() and not f.is_symlink()
             and oct(f.stat().st_mode & 0o777) == "0o755", str(f))


def scenario_good(phone, c, fast):
    common(phone, c)
    r = phone.install_module(HERE.parent / "build/wave_test_ok-1.0.zip")
    c.ok("magisk --install-module (util_functions.sh) and Wave's checks exit 0", r.returncode == 0, r.stdout + r.stderr)
    c.ok("the module waits in modules_update", phone.adb("modules_update/wave_test_ok/module.prop").is_file())
    c.ok("Magisk's app marker modules/<id>/update is there", phone.adb("modules/wave_test_ok/update").exists())
    c.ok("pending lists it", phone.read("wave/pending") == "wave_test_ok\n", phone.read("wave/pending"))
    ev = phone.boot(BOOT_AT=15, MAX=60 if fast else 120)
    c.ok("post-fs-data.d script ran and exited 0", "post-fs-data.d wave-watchdog.sh exit 0" in ev, ev)
    c.ok("the module was mounted", "mounted: wave_test_ok" in ev, ev)
    c.ok("its post-fs-data.sh ran (marker)", (phone.read("wave_test_ok.mounted") or "").endswith("version=1.0\n"))
    log = phone.read("wave/log") or ""
    c.ok("the early half put it on trial", "post-fs-data: trying wave_test_ok" in log, log)
    c.ok("the late half said the boot settled", "service: boot settled after" in log, log)
    c.ok("service.d script exited 0", "service.d wave-watchdog.sh exit 0" in ev, ev)
    c.ok("trying and pending are gone", phone.read("wave/trying") is None and phone.read("wave/pending") is None)
    c.ok("no reboot asked for", "reboot asked for" not in ev, ev)
    c.ok("nothing on stderr from either script", not (phone.fake / "pfs.out").read_text().strip()
         and not (phone.fake / "service.out").read_text().strip(),
         (phone.fake / "pfs.out").read_text() + (phone.fake / "service.out").read_text())
    # Removal through Wave's script: Magisk runs uninstall.sh and deletes it.
    r = phone.su("remove-module.sh", wave_module_id="wave_test_ok")
    c.ok("Wave's removal script exits 0 and flags it", r.returncode == 0 and phone.adb("modules/wave_test_ok/remove").exists())
    ev = phone.boot(BOOT_AT=5, MAX=10)
    c.ok("Magisk removed it at boot, running its uninstall.sh",
         "removed wave_test_ok" in ev and not phone.adb("modules/wave_test_ok").exists()
         and phone.read("wave_test_ok.mounted") is None, ev)


def scenario_broken(phone, c, fast):
    common(phone, c)
    r = phone.install_module(HERE.parent / "build/wave_test_broken-1.0.zip")
    c.ok("broken module installed", r.returncode == 0, r.stdout + r.stderr)
    (phone.data / "adb/wave_test_broken.armed").write_text("")
    ev = phone.boot(BOOT_AT=15, MAX=90 if fast else 400)
    log = phone.read("wave/log") or ""
    c.ok("the hung boot ended in one reboot from the watchdog", "reboot asked for: reboot" in ev, ev)
    c.ok("logged why", "boot not completed after" in log and "rebooting once" in log, log)
    c.ok("the module had held the framework down", "setprop ctl.stop zygote" in (phone.fake / "calls").read_text())
    ev = phone.boot(BOOT_AT=15, MAX=40)
    disabled = phone.read("wave/disabled") or ""
    c.ok("the next boot's early half turned it off (boot-timeout)", "\twave_test_broken\tboot-timeout\n" in disabled, disabled)
    c.ok("Magisk left it out of that boot", "left out (disabled) wave_test_broken" in ev, ev)
    c.ok("that boot completed with no second reboot", "boot completed" in ev and "reboot asked for" not in ev, ev)
    c.ok("forced.last cleared by the late half", phone.read("wave/forced.last") is None and phone.read("wave/forced") is None)
    r = phone.su("read-watchdog.sh")
    c.ok("Wave reads it back", "@disabled" in r.stdout and "wave_test_broken\tboot-timeout" in r.stdout, r.stdout)


def scenario_crashing(phone, c, fast):
    common(phone, c)
    phone.install_module(HERE.parent / "build/wave_test_ok-1.0.zip")
    ev = phone.boot(BOOT_AT=5, CRASH_EVERY=4, MAX=90 if fast else 400)
    log = phone.read("wave/log") or ""
    c.ok("a framework that keeps restarting forces one reboot", "system_server kept restarting; rebooting once" in log
         and "reboot asked for" in ev, log + ev)
    ev = phone.boot(BOOT_AT=5, MAX=30)
    c.ok("and the module is off the next boot", "left out (disabled) wave_test_ok" in ev
         and "boot-timeout" in (phone.read("wave/disabled") or ""), ev)


def scenario_incomplete(phone, c, fast):
    common(phone, c)
    phone.install_module(HERE.parent / "build/wave_test_ok-1.0.zip")
    ev = phone.boot(BOOT_AT=15, CUT_AT=8)
    c.ok("power cut before the boot completed", "power cut" in ev, ev)
    ev = phone.boot(BOOT_AT=5, MAX=30)
    c.ok("next boot: turned off as boot-incomplete", "\twave_test_ok\tboot-incomplete\n" in (phone.read("wave/disabled") or ""),
         phone.read("wave/disabled"))
    c.ok("and left out of that boot", "left out (disabled) wave_test_ok" in ev, ev)
    r = phone.su("enable-module.sh", wave_module_id="wave_test_ok")
    c.ok("Wave's Turn On exits 0, removes disable, puts it on pending",
         r.returncode == 0 and not phone.adb("modules/wave_test_ok/disable").exists()
         and phone.read("wave/pending") == "wave_test_ok\n", r.stdout + r.stderr)
    ev = phone.boot(BOOT_AT=5, MAX=40 if fast else 90)
    c.ok("then it mounts and settles", "mounted: wave_test_ok" in ev and "boot settled" in (phone.read("wave/log") or ""), ev)


def scenario_restart(phone, c, fast):
    common(phone, c)
    phone.install_module(HERE.parent / "build/wave_test_ok-1.0.zip")
    ev = phone.boot(BOOT_AT=5, RESTART_AT=12)
    log = phone.read("wave/log") or ""
    c.ok("a user restart right after boot keeps the module",
         "shutdown asked for after boot completed; modules kept" in log, log + ev)
    ev = phone.boot(BOOT_AT=5, MAX=10)
    c.ok("next boot mounts it", "mounted: wave_test_ok" in ev and phone.read("wave/disabled") is None, ev)


def scenario_safe(phone, c, fast):
    common(phone, c)
    phone.install_module(HERE.parent / "build/wave_test_ok-1.0.zip")
    phone.boot(BOOT_AT=5, MAX=40 if fast else 90)
    log_before = phone.read("wave/log")
    ev = phone.boot(VOLUME_DOWN=1, BOOT_AT=5, MAX=10)
    c.ok("safe mode: Magisk disables every module and runs no scripts",
         "safe mode" in ev and phone.adb("modules/wave_test_ok/disable").exists()
         and phone.read("wave/log") == log_before and "wave-watchdog" not in ev, ev)
    c.ok("the watchdog records nothing", phone.read("wave/disabled") is None)
    r = phone.su("list-modules.sh")
    c.ok("Wave sees it disabled", "@modules wave_test_ok disable" in r.stdout, r.stdout)
    phone.su("enable-module.sh", wave_module_id="wave_test_ok")
    ev = phone.boot(BOOT_AT=5, MAX=40 if fast else 90)
    c.ok("Turn On brings it back next boot", "mounted: wave_test_ok" in ev, ev)


def scenario_slow_neighbour(phone, c, fast):
    """Another post-fs-data.d script that blocks first uses up Magisk's 35 s."""
    common(phone, c)
    phone.install_module(HERE.parent / "build/wave_test_ok-1.0.zip")
    slow = phone.adb("post-fs-data.d/00-slow.sh")
    slow.write_text("sleep 40\n")
    slow.chmod(0o755)
    phone.boot(BOOT_AT=5, CUT_AT=8)
    ev = phone.boot(BOOT_AT=5, MAX=45)
    disabled = phone.read("wave/disabled") or ""
    c.ok("magiskd stops waiting at 35 s but still starts the watchdog, which exits 0",
         "still running at 35 s" in ev and "wave-watchdog.sh exit 0 (not waited for)" in ev, ev)
    c.ok("it still records boot-incomplete", "\twave_test_ok\tboot-incomplete\n" in disabled, disabled)
    mounted = "mounted: wave_test_ok" in ev
    c.lines.append("INFO race: the module was " + ("mounted anyway (disable came after collect_modules)"
                                                      if mounted else "left out (disable won the race)"))


SCENARIOS = {
    "good": scenario_good, "broken": scenario_broken, "crashing": scenario_crashing,
    "incomplete": scenario_incomplete, "restart": scenario_restart, "safe": scenario_safe,
    "slow-neighbour": scenario_slow_neighbour,
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default=str(REPO.parent / "rphone/Magisk-v24.0.apk"))
    ap.add_argument("--fast", action="store_true")
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("names", nargs="*")
    a = ap.parse_args()
    if not shutil.which("bwrap"):
        sys.exit("needs bwrap")
    if not (HERE.parent / "build/wave_test_ok-1.0.zip").exists():
        subprocess.run([str(HERE.parent / "build.sh")], check=True, capture_output=True)
    work = Path(tempfile.mkdtemp(prefix="wave-magisk-sim-"))
    try:
        magisk = work / "magisk"
        magisk.mkdir()
        with zipfile.ZipFile(a.apk) as z:
            tables = {abi: z.read(f"lib/{abi}/libbusybox.so") for abi in ("armeabi-v7a", "x86_64")}
            (magisk / "busybox").write_bytes(tables["x86_64"])
            (magisk / "util_functions.sh").write_bytes(z.read("assets/util_functions.sh"))
        (magisk / "busybox").chmod(0o755)
        applets = subprocess.run([str(magisk / "busybox"), "--list"], capture_output=True, text=True).stdout.split()
        table = b"\0".join(n.encode() for n in applets) + b"\0"
        same = table in tables["armeabi-v7a"]
        version = subprocess.run([str(magisk / "busybox")], capture_output=True, text=True).stdout.splitlines()[0]
        print(f"{version}; {len(applets)} applets; armeabi-v7a build has the same applet table: {same}")
        scripts = work / "wave"
        subprocess.run([JAVA, "-cp", f"{CLASSES}:{RESOURCES}", str(HERE / "Gen.java"), str(scripts)], check=True)
        used = set()
        for f in ("watchdog-post-fs-data.sh", "watchdog-service.sh"):
            text = (REPO / "app/core/src/main/resources/com/wave/xp3/core/root" / f).read_text()
            text = re.sub(r"(?m)^\s*#.*$", "", text)
            text = re.sub(r"\$\(\([^)]*\)\)", "0", unquote(text))  # and arithmetic
            for word in re.findall(r"(?m)(?:^|[|;&(]|\$\(|&&|\|\||\bthen\b|\bdo\b|\belse\b)\s*([a-z][a-z0-9_]*)\b(?!=)", text):
                used.add(word)
        shell = {"if", "then", "else", "elif", "fi", "for", "do", "done", "while", "until", "case", "esac", "in",
                 "exit", "read", "local", "return", "break", "continue", "echo", "printf", "kill", "test",
                 "true", "false", "wait", "set", "shift", "export", "trap", "command", "exec", "cd"}
        functions = set(re.findall(r"(?m)^([a-z_]+)\(\)", (REPO / "app/core/src/main/resources/com/wave/xp3/core/root/watchdog-service.sh").read_text()
                                   + (REPO / "app/core/src/main/resources/com/wave/xp3/core/root/watchdog-post-fs-data.sh").read_text()))
        external = sorted(used - shell - functions)
        missing = [w for w in external if w not in applets]
        print("commands the watchdog scripts call:", " ".join(external))
        print("not busybox applets (found on PATH instead):", " ".join(missing) or "none")
        names = a.names or list(SCENARIOS)

        def run(name):
            c = Checks(name)
            phone = Phone(work, magisk, scripts, a.fast)
            t = time.time()
            try:
                SCENARIOS[name](phone, c, a.fast)
            except Exception as e:  # noqa: BLE001
                c.ok("ran to the end", False, repr(e))
            c.lines.append(f"     ({time.time() - t:.0f} s, {phone.boots} boots)")
            return c

        failed = 0
        with concurrent.futures.ThreadPoolExecutor(len(names)) as pool:
            for c in pool.map(run, names):
                print(f"\n== {c.name}")
                print("\n".join(c.lines))
                failed += c.failed
        print(f"\n{'OK' if not failed else 'FAILED'}: {failed} failed")
        sys.exit(1 if failed else 0)
    finally:
        if not a.keep:
            shutil.rmtree(work, ignore_errors=True)
        else:
            print("kept", work)


if __name__ == "__main__":
    main()
