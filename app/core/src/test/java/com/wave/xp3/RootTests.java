package com.wave.xp3;

import com.wave.xp3.core.root.Facts;
import com.wave.xp3.core.root.Frame;
import com.wave.xp3.core.root.Modules;
import com.wave.xp3.core.root.PmResult;
import com.wave.xp3.core.root.ProcessShell;
import com.wave.xp3.core.root.Prop;
import com.wave.xp3.core.root.Scripts;
import com.wave.xp3.core.root.Shell;
import com.wave.xp3.core.root.Watchdog;

import com.xp3.common.Check;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The root layer: script text, the parsers for what comes back, the shell
 * framing through a real process (a fake `su`), and the boot watchdog's two
 * scripts run through whole pretend boots.
 */
final class RootTests {

    private RootTests() {}

    static void run(Check check) {
        check.run("Root: frame", () -> frame(check));
        check.run("Root: scripts", () -> scripts(check));
        check.run("Root: pm results", () -> pm(check));
        check.run("Root: modules", () -> modules(check));
        check.run("Root: facts and props", () -> facts(check));
        check.run("Root: shell", () -> shell(check));
        check.run("Root: module scripts on a pretend phone", () -> moduleScripts(check));
        check.run("Watchdog: state", () -> watchdogState(check));
        check.run("Watchdog: boots", () -> watchdogBoots(check));
    }

    private static void frame(Check check) {
        String w = Frame.wrap("echo hi", "abcdefgh12");
        check.ok("subshell", w.startsWith("(\necho hi\n) </dev/null 2>&1\n"));
        check.ok("marker", w.endsWith(":wave-end:abcdefgh12:$?\"\n"));
        check.fails("bad nonce", () -> Frame.wrap("x", "a;b"));
        check.same("end", 3, Frame.end(":wave-end:abcdefgh12:3", "abcdefgh12"));
        check.same("other nonce", null, Frame.end(":wave-end:zzzzzzzz:3", "abcdefgh12"));
        check.same("not a marker", null, Frame.end("hello", "abcdefgh12"));
        check.same("trailing blank dropped", "a\nb", Frame.output(Arrays.asList("a", "b", "")));
        check.same("no trailing blank", "a", Frame.output(Arrays.asList("a")));
        check.same("empty", "", Frame.output(Arrays.asList("")));
    }

    private static void scripts(Check check) {
        check.same("quote", "'it'\\''s'", Scripts.quote("it's"));
        check.same("quote plain", "'/a b/c'", Scripts.quote("/a b/c"));
        check.ok("package ok", Scripts.isPackage("com.wave.xp3"));
        check.ok("package no dot", !Scripts.isPackage("wave"));
        check.ok("package shell", !Scripts.isPackage("com.x;rm -rf"));
        check.ok("module ok", Scripts.isModuleId("messages_improved"));
        check.ok("module dots", !Scripts.isModuleId(".."));
        check.ok("module slash", !Scripts.isModuleId("a/b"));
        check.ok("module space", !Scripts.isModuleId("a b"));
        check.fails("uninstall refuses junk", () -> Scripts.uninstallApk("x; reboot"));
        check.fails("remove refuses junk", () -> Scripts.removeModule("../../system"));
        check.fails("install refuses no size", () -> Scripts.installApk("/x.apk", 0));
        check.same("install streams", "cat '/data/user/0/com.wave.xp3/cache/downloads/a.apk' | pm install -r -S 42",
            Scripts.installApk("/data/user/0/com.wave.xp3/cache/downloads/a.apk", 42));
        check.same("uninstall", "pm uninstall com.list.xp3", Scripts.uninstallApk("com.list.xp3"));
        check.same("compile", "cmd package compile -m speed com.list.xp3", Scripts.compile("com.list.xp3"));
        check.ok("module install marks pending", Scripts.installModule("/z.zip", "m1").contains("/pending"));
        check.ok("root id", Scripts.isRoot("uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0"));
        check.ok("not root", !Scripts.isRoot("uid=2000(shell) gid=2000(shell)"));
        check.ok("nothing", !Scripts.isRoot(""));
        check.ok("reboot", Scripts.reboot().startsWith("svc power reboot"));
    }

    private static void pm(Check check) {
        PmResult ok = PmResult.parse("Success", 0);
        check.ok("success", ok.ok);
        PmResult ok2 = PmResult.parse("Transferred 1 bytes\nSuccess\n", 0);
        check.ok("success after chatter", ok2.ok);
        PmResult key = PmResult.parse("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.x signatures do not "
            + "match the previously installed version; ignoring!]", 1);
        check.ok("failed", !key.ok);
        check.same("code", "INSTALL_FAILED_UPDATE_INCOMPATIBLE", key.code);
        check.same("words", "The phone's copy is signed with a different key.", key.message);
        check.same("space", "The phone is out of space.",
            PmResult.parse("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]", 1).message);
        check.same("downgrade", "The phone has a newer version.",
            PmResult.parse("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]", 1).message);
        check.same("parse", "The file isn't a valid app.",
            PmResult.parse("Failure [INSTALL_PARSE_FAILED_NO_CERTIFICATES: Package has no certificates]", 1).message);
        check.same("not installed", "It isn't installed.",
            PmResult.parse("Failure [DELETE_FAILED_INTERNAL_ERROR]", 1).message);
        check.ok("unknown code", PmResult.parse("Failure [INSTALL_FAILED_WEIRD: x]", 1).message.contains("WEIRD"));
        PmResult error = PmResult.parse("Error: java.lang.IllegalArgumentException: bad", 255);
        check.ok("error", !error.ok && error.message.contains("bad"));
        check.ok("silence", PmResult.parse("", 1).message.contains("no answer"));
        PmResult thrown = PmResult.parse("Exception occurred while executing:\njava.lang.IllegalArgumentException: "
            + "Unknown package: com.x\n\tat com.android.server.pm.PackageManagerShellCommand.run(X.java:1)\n", 255);
        check.ok("cmd exception", !thrown.ok);
        check.same("cmd exception words", "Android refused it: Unknown package: com.x", thrown.message);
        check.ok("exception beats a stray Success", !PmResult.parse("Success\nException occurred while executing:\n"
            + "java.lang.RuntimeException: x\n", 1).ok);
        check.ok("su chatter", PmResult.parse("/system/bin/sh: pm: not found", 127).message.contains("not found"));
    }

    private static void modules(Check check) {
        String out = "@modules a disable\n|id=a\n|name=Alpha\n|version=1.0\n|versionCode=1\n|\n@end\n"
            + "@modules b remove\n|id=b\n|name=Bravo\n|version=2.0\n@end\n"
            + "@modules c \n|id=c\n|version=3.1\n|versionCode=31\n@end\n"
            + "@modules d update\n|id=d\n|name=Delta\n|version=1.1\n@end\n"
            + "@modules_update d \n|id=d\n|name=Delta\n|version=1.1\n|versionCode=11\n@end\n"
            + "@modules_update e \n|id=e\n|name=Echo\n|version=0.1\n@end\n"
            + "@modules ../x \n|id=evil\n@end\n";
        List<Modules.Module> list = Modules.parse(out);
        check.same("count", 5, list.size());
        Map<String, Modules.Module> by = new HashMap<>();
        for (Modules.Module m : list) by.put(m.id, m);
        check.same("disabled", Modules.State.DISABLED, by.get("a").state);
        check.same("name", "Alpha", by.get("a").name);
        check.same("pending removal", Modules.State.PENDING_REMOVAL, by.get("b").state);
        check.same("installed", Modules.State.INSTALLED, by.get("c").state);
        check.same("code", 31L, by.get("c").versionCode);
        check.same("no name is id", "c", by.get("c").name);
        check.same("update waiting", Modules.State.PENDING_INSTALL, by.get("d").state);
        check.same("update version", 11L, by.get("d").versionCode);
        check.same("new waiting", Modules.State.PENDING_INSTALL, by.get("e").state);
        check.ok("pending", by.get("e").state.pending());
        check.ok("sorted", list.get(0).id.equals("a") && list.get(4).id.equals("e"));
        check.ok("not all disabled", !Modules.allDisabled(list));
        check.ok("all disabled", Modules.allDisabled(Modules.parse(
            "@modules a disable\n|id=a\n@end\n@modules b disable,remove\n@end\n")));
        check.ok("none is not all disabled", !Modules.allDisabled(Modules.parse("")));
        check.same("disabled waiting update", Modules.State.DISABLED, Modules.parse(
            "@modules_update f disable\n|id=f\n@end\n").get(0).state);
    }

    private static void facts(Check check) {
        Facts f = Facts.parse("magisk=24.0:MAGISK\nmagiskCode=24000\nwatchdogPfd=1\nwatchdogService=\n");
        check.ok("magisk", f.hasMagisk());
        check.same("code", 24000L, f.magiskCode);
        check.same("pfd", 1, f.watchdogPostFsData);
        check.same("service missing", 0, f.watchdogService);
        check.ok("needs install", Watchdog.needsInstall(f));
        check.ok("no magisk", !Facts.parse("magisk=\nmagiskCode=\n").hasMagisk());
        Map<String, String> p = Prop.parse("# c\nid=a\nname= Spaced Name \nid=b\nbad line\n=x\n");
        check.same("first wins", "a", p.get("id"));
        check.same("trimmed", "Spaced Name", p.get("name"));
        check.same("size", 2, p.size());
        check.ok("shipped version", Watchdog.version() >= 1);
        Facts current = Facts.parse("magiskCode=24000\nwatchdogPfd=" + Watchdog.version()
            + "\nwatchdogService=" + Watchdog.version() + "\n");
        check.ok("current needs nothing", !Watchdog.needsInstall(current));
    }

    /** ProcessShell through a real process: framing, exit codes, timeouts, no root. */
    private static void shell(Check check) throws Exception {
        try (Sandbox box = new Sandbox()) {
            ProcessShell shell = new ProcessShell(box.su(), box.env());
            Shell.Result r = shell.run("echo one; echo two >&2", 5000);
            check.same("exit", 0, r.exit);
            check.same("output", "one\ntwo", r.output);
            Shell.Result r2 = shell.run("printf 'no newline'; exit 7", 5000);
            check.same("exit code", 7, r2.exit);
            check.same("no newline", "no newline", r2.output);
            check.same("state doesn't leak", "", shell.run("X=1; cd /", 5000).output
                + shell.run("echo $X", 5000).output);
            check.same("stdin is closed", "", shell.run("cat", 5000).output);
            check.fails("timeout", "too long", () -> shell.run("while :; do :; done", 500));
            check.same("reopens after timeout", "back", shell.run("echo back", 5000).output);
            check.fails("shell exiting", "closed", () -> shell.run("kill $$", 5000));
            check.same("reopens after exit", "again", shell.run("echo again", 5000).output);
            shell.close();

            box.flag("deny");
            ProcessShell denied = new ProcessShell(box.su(), box.env());
            check.fails("not root", "refused", () -> denied.run("echo x", 5000));
            try {
                denied.run("echo x", 5000);
            } catch (Shell.NoRoot e) {
                check.ok("is NoRoot", true);
            }
            ProcessShell none = new ProcessShell(Arrays.asList(new File(box.root, "nothing-here").getPath()), null);
            check.fails("no su", "no root", () -> none.run("echo x", 5000));
        }
    }

    /** The module scripts against a pretend /data/adb, with Magisk's install faked. */
    private static void moduleScripts(Check check) throws Exception {
        try (Sandbox box = new Sandbox()) {
            File zip = InstallTests.moduleZip(box.root, "m1", "1.0");
            Sandbox.Ran r = box.sh(Scripts.installModule(zip.getPath(), "m1"), null);
            check.same("install exit", 0, r.exit);
            check.ok("waiting", box.file("modules_update/m1/module.prop").isFile());
            check.same("pending", "m1\n", box.read("wave/pending"));
            box.sh(Scripts.installModule(zip.getPath(), "m1"), null);
            check.same("pending once", "m1\n", box.read("wave/pending"));
            check.same("wrong id", 3, box.sh(Scripts.installModule(zip.getPath(), "other"), null).exit);

            Sandbox.Ran list = box.sh(Scripts.listModules(), null);
            List<Modules.Module> mods = Modules.parse(list.output);
            check.same("listed", 1, mods.size());
            check.same("listed pending", Modules.State.PENDING_INSTALL, mods.get(0).state);

            check.same("remove exit", 0, box.sh(Scripts.removeModule("m1"), null).exit);
            check.ok("waiting dropped", !box.file("modules_update/m1").exists());
            check.ok("placeholder flagged", box.file("modules/m1/remove").isFile());
            check.same("unpended", "", box.read("wave/pending"));
            check.same("listed removal", Modules.State.PENDING_REMOVAL,
                Modules.parse(box.sh(Scripts.listModules(), null).output).get(0).state);
            box.sh(Scripts.keepModule("m1"), null);
            check.ok("kept", !box.file("modules/m1/remove").exists());

            box.file("modules/m2").mkdirs();
            Sandbox.write(box.file("modules/m2/module.prop"), "id=m2\nversion=2\n");
            Sandbox.write(box.file("modules/m2/disable"), "");
            check.same("enable", 0, box.sh(Scripts.enableModule("m2"), null).exit);
            check.ok("enabled", !box.file("modules/m2/disable").exists());
            check.ok("watched again", box.read("wave/pending").contains("m2"));
            check.same("enable missing", 3, box.sh(Scripts.enableModule("nope"), null).exit);

            // A module.prop whose lines look like the listing's own markers,
            // and one without a final newline.
            box.file("modules/tricky").mkdirs();
            Sandbox.write(box.file("modules/tricky/module.prop"),
                "id=tricky\nname=Tricky\ndescription=x\n@end\n@modules_update evil disable\nversion=9");
            box.file("modules/after").mkdirs();
            Sandbox.write(box.file("modules/after/module.prop"), "id=after\nversion=1\n");
            Map<String, Modules.Module> listed = new HashMap<>();
            for (Modules.Module mod : Modules.parse(box.sh(Scripts.listModules(), null).output)) listed.put(mod.id, mod);
            check.ok("markers in a prop don't split it", !listed.containsKey("evil"));
            check.same("prop read past them", "9", listed.get("tricky").version);
            check.same("no final newline", Modules.State.INSTALLED, listed.get("after").state);
            check.same("next module intact", "1", listed.get("after").version);

            // Safe mode cut a boot short, leaving `trying`; the user turns a
            // module on again: the next boot must try it, not turn it off.
            Sandbox.write(box.file("wave/trying"), "m2\n");
            Sandbox.write(box.file("modules/m2/disable"), "");
            box.sh(Scripts.enableModule("m2"), null);
            check.same("off trying", "", box.read("wave/trying"));
            box.sh(Watchdog.install(), null);
            box.sh("sh " + box.file("post-fs-data.d/wave-watchdog.sh").getPath(), null);
            check.ok("tried, not turned off", !box.file("modules/m2/disable").exists()
                && box.read("wave/trying").contains("m2"));
            box.sh(Scripts.removeModule("m2"), null);
            check.ok("removal leaves trying", !box.read("wave/trying").contains("m2"));

            check.same("no magisk exit", 0, box.sh(Scripts.facts(), null).exit);
            box.flag("no-magisk");
            check.ok("facts without magisk", !Facts.parse(box.sh(Scripts.facts(), null).output).hasMagisk());
        }
    }

    private static void watchdogState(Check check) throws Exception {
        Watchdog.State s = Watchdog.parse("@disabled\n1763500000\tm1\tboot-incomplete\nbad\n\n"
            + "@pending\nm2\n../x\n\n@trying\nm3\n\n");
        check.same("disabled", 1, s.disabled.size());
        check.same("id", "m1", s.disabled.get(0).id);
        check.same("time", 1763500000L, s.disabled.get(0).time);
        check.ok("why", s.disabled.get(0).why().contains("restarted"));
        check.same("pending", Arrays.asList("m2"), s.pending);
        check.same("trying", Arrays.asList("m3"), s.trying);
        check.ok("timeout why", Watchdog.parse("@disabled\n1\tm\tboot-timeout\n").disabled.get(0).why()
            .contains("five minutes"));
        check.same("scripts versioned alike", Watchdog.version(Watchdog.postFsData()),
            Watchdog.version(Watchdog.service()));
        // A user restart is seen only in the few seconds Android takes to go
        // down, so the late half looks every second unless a test says otherwise.
        check.ok("late half looks every second", Watchdog.service().contains("STEP=${WAVE_STEP:-1}\n"));
    }

    /** Runs the early and late halves as Magisk would, through pretend boots. */
    private static void watchdogBoots(Check check) throws Exception {
        Map<String, String> fast = new HashMap<>();
        fast.put("WAVE_BOOT_LIMIT", "30");
        fast.put("WAVE_SETTLE", "10");
        fast.put("WAVE_STEP", "5");

        try (Sandbox box = new Sandbox()) {
            // Installed through the scripts Wave sends.
            Sandbox.Ran install = box.sh(Watchdog.install(), null);
            check.same("install exit", 0, install.exit);
            File pfd = box.file("post-fs-data.d/wave-watchdog.sh");
            File service = box.file("service.d/wave-watchdog.sh");
            check.ok("pfd executable", pfd.canExecute());
            check.ok("service executable", service.canExecute());
            check.same("installed text", Watchdog.postFsData(), box.read("post-fs-data.d/wave-watchdog.sh"));
            Facts facts = Facts.parse(box.sh(Scripts.facts(), null).output);
            check.same("facts see it", Watchdog.version(), facts.watchdogService);
            check.ok("no reinstall needed", !Watchdog.needsInstall(facts));
            String early = "sh " + pfd.getPath();
            String late = "sh " + service.getPath();

            // A good module: boot completes, system_server stays.
            module(box, "good");
            box.sh(Scripts.markPending("good"), null);
            check.same("pfd exit", 0, box.sh(early, fast).exit);
            check.same("moved to trying", "good\n", box.read("wave/trying"));
            check.ok("pending gone", box.read("wave/pending") == null);
            box.prop("pid.system_server", "100");
            box.propAt(2, "sys.boot_completed", "1");
            check.same("service exit", 0, box.sh(late, fast).exit);
            check.ok("trying cleared", box.read("wave/trying") == null);
            check.ok("not disabled", !box.file("modules_update/good/disable").exists());
            check.ok("no reboot", !box.calls().contains("setprop sys.powerctl"));
            box.sh(early, fast);
            check.ok("next boot leaves it", !box.file("modules_update/good/disable").exists()
                && box.read("wave/disabled") == null);
        }

        try (Sandbox box = new Sandbox()) {
            box.sh(Watchdog.install(), null);
            String early = "sh " + box.file("post-fs-data.d/wave-watchdog.sh").getPath();
            String late = "sh " + box.file("service.d/wave-watchdog.sh").getPath();
            // A bad module: boot never completes. One forced reboot, then off.
            module(box, "bad");
            module(box, "other");
            box.sh(Scripts.markPending("bad"), null);
            box.sh(early, fast);
            box.prop("sys.boot_completed", "0");
            check.same("service exit", 0, box.sh(late, fast).exit);
            check.ok("forced reboot", box.calls().contains("setprop sys.powerctl reboot"));
            check.ok("forced marked", box.read("wave/forced") != null);
            check.ok("still trying", box.read("wave/trying") != null);
            box.clearCalls();
            // The next boot.
            box.sh(early, fast);
            check.ok("disabled where waiting", box.file("modules_update/bad/disable").isFile());
            check.ok("disabled placeholder", box.file("modules/bad/disable").isFile());
            check.ok("other untouched", !box.file("modules_update/other/disable").exists());
            check.ok("trying consumed", box.read("wave/trying") == null);
            Watchdog.State state = Watchdog.parse(box.sh(Watchdog.read(), null).output);
            check.same("recorded", 1, state.disabled.size());
            check.same("recorded id", "bad", state.disabled.get(0).id);
            check.same("reason", "boot-timeout", state.disabled.get(0).reason);
            check.ok("forced.last", box.read("wave/forced.last") != null);
            // Still not booting (not the module's fault): no second forced reboot.
            box.sh(Scripts.markPending("other"), null);
            new File(box.root, "ticks").delete();
            box.sh(early, fast);
            box.sh(late, fast);
            check.ok("no second reboot", !box.calls().contains("setprop sys.powerctl"));
            check.ok("forced.last spent", box.read("wave/forced.last") == null);
            // The user saw it.
            box.sh(Watchdog.acknowledge(), null);
            check.same("acknowledged", 0, Watchdog.parse(box.sh(Watchdog.read(), null).output).disabled.size());
            check.ok("kept as seen", box.read("wave/disabled.seen").contains("bad"));
        }

        try (Sandbox box = new Sandbox()) {
            box.sh(Watchdog.install(), null);
            String early = "sh " + box.file("post-fs-data.d/wave-watchdog.sh").getPath();
            String late = "sh " + box.file("service.d/wave-watchdog.sh").getPath();
            // Restarted mid-boot (battery pulled, or a crash back to the logo).
            module(box, "crashy");
            box.sh(Scripts.markPending("crashy"), null);
            box.sh(early, fast);
            box.sh(early, fast);
            check.ok("off after an incomplete boot", box.file("modules_update/crashy/disable").isFile());
            check.same("reason", "boot-incomplete",
                Watchdog.parse(box.sh(Watchdog.read(), null).output).disabled.get(0).reason);
            box.sh(late, fast);
            check.ok("nothing to do", !box.calls().contains("setprop"));
        }

        try (Sandbox box = new Sandbox()) {
            box.sh(Watchdog.install(), null);
            String early = "sh " + box.file("post-fs-data.d/wave-watchdog.sh").getPath();
            String late = "sh " + box.file("service.d/wave-watchdog.sh").getPath();
            // system_server keeps restarting after boot completed: not settled.
            module(box, "flaky");
            box.sh(Scripts.markPending("flaky"), null);
            box.sh(early, fast);
            box.prop("sys.boot_completed", "1");
            box.prop("pid.system_server", "1");
            for (int t = 1; t <= 8; t++) box.propAt(t, "pid.system_server", String.valueOf(100 + t));
            box.sh(late, fast);
            check.ok("restarting framework forces a reboot", box.calls().contains("setprop sys.powerctl reboot"));
        }

        try (Sandbox box = new Sandbox()) {
            box.sh(Watchdog.install(), null);
            String early = "sh " + box.file("post-fs-data.d/wave-watchdog.sh").getPath();
            String late = "sh " + box.file("service.d/wave-watchdog.sh").getPath();
            // Each change reaches the disk before its script ends. The XP3's
            // f2fs lost an unsynced `trying` when the phone was cut soon after
            // (seen on 8516067e), so the mod was tried again, not turned off.
            module(box, "durable");
            box.sh(Scripts.markPending("durable"), null);
            box.sh(early, fast);
            check.ok("trial synced", box.calls().matches("(?s).*sync [^\n]*trying[^\n]*\n?$"));
            box.prop("sys.boot_completed", "1");
            box.prop("pid.system_server", "7");
            box.sh(late, fast);
            check.ok("settle synced", box.calls().matches("(?s).*sync (?![^\n]*trying)[^\n]*\n?$"));
            Sandbox.write(box.file("wave/trying"), "durable\n");
            box.sh(early, fast);
            check.ok("turned off, synced", box.file("modules_update/durable/disable").exists()
                && box.calls().matches("(?s).*sync [^\n]*disabled[^\n]*\n?$"));
            String before = box.calls();
            box.sh(early, fast);
            check.same("nothing to do, no sync", before, box.calls());
        }

        try (Sandbox box = new Sandbox()) {
            box.sh(Watchdog.install(), null);
            String early = "sh " + box.file("post-fs-data.d/wave-watchdog.sh").getPath();
            String late = "sh " + box.file("service.d/wave-watchdog.sh").getPath();
            // The user restarts right after boot completed: not the module's fault.
            module(box, "fine");
            box.sh(Scripts.markPending("fine"), null);
            box.sh(early, fast);
            box.prop("sys.boot_completed", "1");
            box.prop("pid.system_server", "5");
            box.propAt(1, "sys.shutdown.requested", "0reboot");
            box.sh(late, fast);
            check.ok("clean restart keeps it", box.read("wave/trying") == null);
            box.sh(early, fast);
            check.ok("not disabled", !box.file("modules_update/fine/disable").exists());
        }

        try (Sandbox box = new Sandbox()) {
            box.sh(Watchdog.install(), null);
            String early = "sh " + box.file("post-fs-data.d/wave-watchdog.sh").getPath();
            String late = "sh " + box.file("service.d/wave-watchdog.sh").getPath();
            // A module removed by hand while it was being tried.
            module(box, "gone");
            module(box, "kept");
            Sandbox.write(box.file("wave/trying"), "gone\nkept\n");
            Sandbox.write(box.file("wave/pending"), "next\n");
            new File(box.adb, "modules_update/gone").delete();
            for (File f : box.file("modules_update/gone").listFiles()) f.delete();
            box.file("modules_update/gone").delete();
            for (File f : box.file("modules/gone").listFiles()) f.delete();
            box.file("modules/gone").delete();
            check.same("pfd survives a vanished module", 0, box.sh(early, fast).exit);
            Watchdog.State st = Watchdog.parse(box.sh(Watchdog.read(), null).output);
            check.same("only the one still there recorded", 1, st.disabled.size());
            check.same("which", "kept", st.disabled.get(0).id);
            check.same("pending moved on", Arrays.asList("next"), st.trying);

            // Unreadable lists (not root here, so a mode does it): no crash,
            // and pending still moves on.
            File trying = box.file("wave/trying");
            trying.setReadable(false);
            Sandbox.write(box.file("wave/pending"), "later\n");
            Sandbox.Ran r = box.sh(early, fast);
            check.same("unreadable trying", 0, r.exit);
            check.ok("logged", box.read("wave/log").contains("unreadable"));
            check.same("pending still moved", "later\n", box.read("wave/trying"));

            // Lists that aren't files at all.
            box.file("wave/trying").delete();
            box.file("wave/trying").mkdirs();
            box.file("wave/pending").mkdirs();
            check.same("odd trying", 0, box.sh(early, fast).exit);
            box.file("wave/pending").delete();
            box.file("wave/trying").delete();
            check.same("service without trying", 0, box.sh(late, fast).exit);
        }

        try (Sandbox box = new Sandbox()) {
            // No /data/adb at all.
            Map<String, String> nowhere = new HashMap<>(fast);
            nowhere.put("WAVE_ADB", new File(box.root, "missing").getPath());
            Sandbox.write(new File(box.root, "pfd.sh"), Watchdog.postFsData());
            Sandbox.write(new File(box.root, "svc.sh"), Watchdog.service());
            check.same("pfd without /data/adb", 0, box.sh("sh " + box.root + "/pfd.sh", nowhere).exit);
            check.same("service without /data/adb", 0, box.sh("sh " + box.root + "/svc.sh", nowhere).exit);
            check.ok("made nothing", !new File(box.root, "missing").exists());
        }

        try (Sandbox box = new Sandbox()) {
            // Without Wave's state folder the scripts do nothing at all.
            Sandbox.write(new File(box.root, "pfd.sh"), Watchdog.postFsData());
            Sandbox.write(new File(box.root, "svc.sh"), Watchdog.service());
            check.same("pfd nothing", 0, box.sh("sh " + box.root + "/pfd.sh", fast).exit);
            check.same("service nothing", 0, box.sh("sh " + box.root + "/svc.sh", fast).exit);
            check.ok("no state made", !box.file("wave").exists());
        }
    }

    /** A module as Magisk leaves one right after installing it. */
    private static void module(Sandbox box, String id) throws Exception {
        box.file("modules_update/" + id).mkdirs();
        box.file("modules/" + id).mkdirs();
        Sandbox.write(box.file("modules_update/" + id + "/module.prop"), "id=" + id + "\nversion=1\n");
        Sandbox.write(box.file("modules/" + id + "/module.prop"), "id=" + id + "\nversion=1\n");
        Sandbox.write(box.file("modules/" + id + "/update"), "");
    }
}
