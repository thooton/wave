package com.wave.xp3.core.root;

import java.util.regex.Pattern;

/**
 * The shell text for everything Wave does as root (design section 4, "Root").
 * Each method returns a script for {@link Frame#wrap}; the parsers for what
 * comes back are beside them ({@link PmResult}, {@link Modules},
 * {@link #isRoot}). Nothing here writes outside `/data/adb`, a path the
 * caller names, and what `pm` and `magisk` do themselves.
 *
 * Every value put into a script is either checked against a strict pattern
 * (ids, package names) or single-quoted ({@link #quote}), so no name from a
 * source can become shell syntax.
 */
public final class Scripts {

    private Scripts() {}

    /**
     * Where Magisk keeps its state: `/data/adb`. Written as a shell default so
     * the tests can run the same scripts against a folder of their own
     * (`WAVE_ADB`); nothing on the phone sets it.
     */
    public static final String ADB = "${WAVE_ADB:-/data/adb}";
    /** Somewhere root and the package installer can both read. */
    public static final String TMP = "${WAVE_TMP:-/data/local/tmp}";
    /** Wave's own root-owned state: the boot watchdog's lists and record. */
    public static final String STATE = ADB + "/wave";

    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    /**
     * Starts with a letter or digit, as the source format's ids do, so an id is never an option to
     * `grep` or `rm` (`-f`), a hidden folder (`.core`) or `.`/`..`.
     */
    private static final Pattern MODULE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** `text` as one single-quoted shell word. */
    public static String quote(String text) {
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in a shell word");
        return "'" + text.replace("'", "'\\''") + "'";
    }

    public static boolean isPackage(String name) {
        return name != null && name.length() <= 255 && PACKAGE.matcher(name).matches();
    }

    public static boolean isModuleId(String id) {
        return id != null && MODULE_ID.matcher(id).matches();
    }

    static String pkg(String name) {
        if (!isPackage(name)) throw new IllegalArgumentException("Not a package name: " + name);
        return name;
    }

    static String module(String id) {
        if (!isModuleId(id)) throw new IllegalArgumentException("Not a module id: " + id);
        return id;
    }

    // ------------------------------------------------------------------
    // Root itself
    // ------------------------------------------------------------------

    /** Who the shell is. */
    public static String whoami() {
        return "id";
    }

    /**
     * Ends process `pid` and everything under it, for a script Wave gave up
     * on ({@link Frame}). The tree is found by parent pid in /proc and stopped
     * (SIGSTOP) before it is killed, until a pass finds nothing new, so
     * nothing can fork past it or be orphaned away from it while it is
     * found. Prints what is left alive of it: nothing, when it worked.
     */
    public static String stop(int pid) {
        if (pid < 2) throw new IllegalArgumentException("bad pid");
        return "t=' " + pid + " '\n"
            + "[ -d /proc/" + pid + " ] || exit 0\n"
            + "while :; do\n"
            + "  kill -STOP $t 2>/dev/null\n"
            + "  more=\n"
            + "  for f in /proc/[0-9]*/stat; do\n"
            + "    read -r l 2>/dev/null < \"$f\" || continue\n"
            + "    p=${l%% *}\n"
            + "    set -- ${l##*\") \"}\n"
            + "    case \"$t\" in *\" $2 \"*) case \"$t\" in *\" $p \"*) ;; *) t=\"$t$p \"; more=1 ;; esac ;; esac\n"
            + "  done\n"
            + "  [ -n \"$more\" ] || break\n"
            + "done\n"
            + "kill -KILL $t 2>/dev/null\n"
            // Gone, or a zombie its parent hasn't collected yet, within 2 s.
            + "for p in $t; do\n"
            + "  n=0\n"
            + "  while read -r l 2>/dev/null < /proc/$p/stat; do\n"
            + "    set -- ${l##*\") \"}\n"
            + "    [ \"$1\" != Z ] || break\n"
            + "    n=$((n + 1)); [ $n -le 20 ] || { echo $p; break; }\n"
            + "    sleep 0.1\n"
            + "  done\n"
            + "done";
    }

    /** True when `id`'s answer is root's. */
    public static boolean isRoot(String idOutput) {
        if (idOutput == null) return false;
        for (String line : idOutput.split("\n")) {
            if (line.trim().startsWith("uid=0(") || line.trim().equals("uid=0")) return true;
        }
        return false;
    }

    /**
     * Facts for the Wave tab and for checks: Magisk's version and code, and
     * whether the boot watchdog's scripts are in place (their version line).
     * One `key=value` per line; see {@link Facts}.
     */
    public static String facts() {
        return "echo \"magisk=$(magisk -v 2>/dev/null)\"\n"
            + "echo \"magiskCode=$(magisk -V 2>/dev/null)\"\n"
            + "echo \"watchdogPfd=$(sed -n 's/^# wave-watchdog: //p' " + ADB + "/post-fs-data.d/" + Watchdog.FILE
            + " 2>/dev/null | head -n 1)\"\n"
            + "echo \"watchdogService=$(sed -n 's/^# wave-watchdog: //p' " + ADB + "/service.d/" + Watchdog.FILE
            + " 2>/dev/null | head -n 1)\"\n";
    }

    // ------------------------------------------------------------------
    // APKs
    // ------------------------------------------------------------------

    /**
     * Installs or updates the APK at `path` (`size` bytes). `pm` runs as root
     * but hands the file to system_server, which can't read an app's private
     * files, so the APK is streamed through stdin (`-S`) rather than named.
     * The subshell's stdin is /dev/null (see Frame), hence the explicit `cat`.
     */
    public static String installApk(String path, long size) {
        if (size <= 0) throw new IllegalArgumentException("size");
        return "cat " + quote(path) + " | pm install -r -S " + size;
    }

    /** Compiles an installed app ahead of time, as Play does after an install. */
    public static String compile(String packageName) {
        return "cmd package compile -m speed " + pkg(packageName);
    }

    public static String uninstallApk(String packageName) {
        return "pm uninstall " + pkg(packageName);
    }

    /**
     * Wave replacing itself. Android stops the app (and with it the `su`
     * shell's pipes) part way through, so the install runs in a detached
     * shell from a copy root owns, writes how it went to
     * {@code /data/adb/wave/self-update}, and starts Wave again, which reads
     * the result back through {@link #selfUpdateResult}.
     */
    public static String selfUpdate(String path, long size, String packageName, String activity) {
        String copy = TMP + "/wave-self-update.apk";
        String result = STATE + "/self-update";
        String job = "cat " + copy + " | pm install -r -S " + size + " > " + result + ".part 2>&1; "
            + "mv " + result + ".part " + result + "; rm -f " + copy + "; "
            + "am start -n " + pkg(packageName) + "/" + activity(activity) + " >/dev/null 2>&1";
        return "set -e\n"
            + "mkdir -p " + STATE + "\n"
            + "chmod 700 " + STATE + "\n"
            + "rm -f " + result + "\n"
            // Removed first: /data/local/tmp is the shell's, and a link left at this name would be followed by root's cp.
            + "rm -f " + copy + "\n"
            + "cp " + quote(path) + " " + copy + "\n"
            + "chmod 644 " + copy + "\n"
            + "B=" + ADB + "/magisk/busybox\n"
            + "if [ -x \"$B\" ]; then S=\"$B setsid\"; elif command -v setsid >/dev/null 2>&1; then S=setsid; else S=; fi\n"
            + "$S nohup sh -c " + quote(job) + " </dev/null >/dev/null 2>&1 &\n"
            + "echo started";
    }

    private static String activity(String name) {
        if (!name.matches("\\.?[A-Za-z][A-Za-z0-9_.]*")) throw new IllegalArgumentException("activity");
        return name;
    }

    /** What the last self-update said (pm's answer), and forgets it; empty if none. */
    public static String selfUpdateResult() {
        String result = STATE + "/self-update";
        return "if [ -f " + result + " ]; then cat " + result + "; rm -f " + result + "; fi";
    }

    // ------------------------------------------------------------------
    // Modules
    // ------------------------------------------------------------------

    /**
     * Installs or updates a module from its zip. Magisk puts it in
     * `modules_update` until the next boot. On success the module is added to
     * the boot watchdog's pending list (design section 5), so a module that
     * stops the phone booting is turned off by the boot after.
     */
    public static String installModule(String zip, String id) {
        String m = module(id);
        return "magisk --install-module " + quote(zip) + " || exit $?\n"
            + "[ -d " + ADB + "/modules_update/" + m + " ] || { echo '! The module installed under another id.'; exit 3; }\n"
            + markPending(m);
    }

    /** Adds `id` to the watchdog's pending list once. */
    public static String markPending(String id) {
        String m = module(id);
        return "mkdir -p " + STATE + " && chmod 700 " + STATE + "\n"
            + "grep -qxF " + quote(m) + " " + STATE + "/pending 2>/dev/null || echo " + quote(m) + " >> " + STATE
            + "/pending";
    }

    /**
     * Removes a module at the next boot. One installed and never booted is
     * dropped from `modules_update` at once; one Magisk already mounted gets
     * a `remove` flag, which Magisk acts on (running its `uninstall.sh`)
     * before mounting anything. Also leaves the pending list.
     */
    public static String removeModule(String id) {
        String m = module(id);
        return "rm -rf " + ADB + "/modules_update/" + m + "\n"
            + "if [ -d " + ADB + "/modules/" + m + " ]; then touch " + ADB + "/modules/" + m + "/remove; fi\n"
            + drop(m, "trying") + "\n"
            + drop(m, "pending");
    }

    /** Takes back a removal not yet carried out by a reboot. */
    public static String keepModule(String id) {
        String m = module(id);
        return "rm -f " + ADB + "/modules/" + m + "/remove";
    }

    /**
     * Turns a disabled module back on for the next boot, and puts it under
     * the watchdog again: the "try again" after the watchdog turned it off,
     * or after Magisk's safe mode turned everything off.
     */
    public static String enableModule(String id) {
        String m = module(id);
        return "[ -d " + ADB + "/modules/" + m + " ] || [ -d " + ADB + "/modules_update/" + m
            + " ] || { echo '! No such module.'; exit 3; }\n"
            + "rm -f " + ADB + "/modules/" + m + "/disable " + ADB + "/modules_update/" + m + "/disable\n"
            + drop(m, "trying") + "\n"
            + markPending(m);
    }

    /**
     * Takes `m` off one of the watchdog's lists. Off `trying` too when the
     * user turns a module on again or removes it: a `trying` left over from
     * a boot Magisk's safe mode cut short would otherwise turn it straight
     * back off at the next boot.
     */
    private static String drop(String m, String list) {
        String f = STATE + "/" + list;
        return "if [ -f " + f + " ]; then grep -vxF " + quote(m) + " " + f + " > " + f
            + ".new; mv -f " + f + ".new " + f + "; fi";
    }

    /**
     * Every module, installed or waiting for a reboot: for each folder in
     * `modules` and `modules_update`, a line `@<where> <id> <flags>`, then
     * its `module.prop` with each line led by `|` (so no line of it can pass
     * for a marker, and a last line without a newline still ends), then
     * `@end`. Parsed by {@link Modules#parse}.
     */
    public static String listModules() {
        return "for where in modules modules_update; do\n"
            + "  for d in " + ADB + "/$where/*; do\n"
            + "    [ -d \"$d\" ] || continue\n"
            + "    id=${d##*/}\n"
            + "    [ \"$id\" = .core ] && continue\n"
            + "    flags=\n"
            + "    for f in disable remove update skip_mount; do [ -e \"$d/$f\" ] && flags=\"$flags,$f\"; done\n"
            + "    echo \"@$where $id ${flags#,}\"\n"
            + "    [ -f \"$d/module.prop\" ] && sed 's/^/|/' \"$d/module.prop\" && echo\n"
            + "    echo @end\n"
            + "  done\n"
            + "done";
    }

    // ------------------------------------------------------------------
    // The phone
    // ------------------------------------------------------------------

    /**
     * Restarts the phone. `svc power reboot` is the framework's own, clean
     * restart; `reboot` is the fallback when the framework isn't answering.
     */
    public static String reboot() {
        return "svc power reboot || reboot";
    }
}
