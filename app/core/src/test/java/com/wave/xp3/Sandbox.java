package com.wave.xp3;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A pretend phone for the root scripts: a temp folder standing in for
 * `/data/adb` (WAVE_ADB) and `/data/local/tmp` (WAVE_TMP), and a `bin`
 * of fakes put first on PATH: `su` (a plain `sh` that says it is root),
 * `id`, `pm`, `cmd`, `magisk`, `svc`, `am`, and for the boot watchdog
 * `getprop`, `setprop`, `pidof` and `sleep`, which read and write files
 * under `props/` and count ticks so a test can script a boot. Every fake
 * appends what it was asked to `calls`.
 *
 * Runs the scripts with the host's /bin/sh (dash on CI: POSIX, not bash), or
 * with WAVE_TEST_SH when set, such as "/path/to/busybox ash", the shell
 * Magisk runs boot scripts with. A `sh` in `bin` stands for it, so scripts
 * that start `sh` themselves get it too.
 */
final class Sandbox implements AutoCloseable {

    final File root;
    final File adb;
    final File tmp;
    final File bin;
    final File props;
    final File calls;

    Sandbox() throws IOException {
        root = Files.createTempDirectory("wave-sandbox").toFile();
        adb = dir("adb");
        tmp = dir("tmp");
        bin = dir("bin");
        props = dir("props");
        calls = new File(root, "calls");
        fakes();
    }

    private File dir(String name) {
        File f = new File(root, name);
        f.mkdirs();
        return f;
    }

    Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", bin.getAbsolutePath() + ":/usr/bin:/bin");
        env.put("WAVE_ADB", adb.getAbsolutePath());
        env.put("WAVE_TMP", tmp.getAbsolutePath());
        env.put("FAKE", root.getAbsolutePath());
        env.put("FAKE_LOG", calls.getAbsolutePath());
        return env;
    }

    /** The command for a fake `su` process. */
    List<String> su() {
        return Arrays.asList(new File(bin, "su").getAbsolutePath());
    }

    private void fakes() throws IOException {
        String shell = System.getenv("WAVE_TEST_SH");
        fake("sh", "exec " + (shell == null || shell.isEmpty() ? "/bin/sh" : shell) + " \"$@\"");
        fake("su", "exec \"$(dirname \"$0\")/sh\"");
        fake("id", "echo \"id $*\" >> \"$FAKE_LOG\"\n"
            + "if [ -f \"$FAKE/deny\" ]; then echo 'uid=2000(shell) gid=2000(shell)'; else echo 'uid=0(root) gid=0(root) context=u:r:magisk:s0'; fi");
        fake("pm", "echo \"pm $*\" >> \"$FAKE_LOG\"\n"
            + "case \"$1\" in\n"
            + "install)\n"
            + "  size=\n"
            + "  while [ $# -gt 0 ]; do [ \"$1\" = -S ] && size=$2; shift; done\n"
            + "  n=$(cat | wc -c | tr -d ' ')\n"
            + "  echo \"pm got $n bytes\" >> \"$FAKE_LOG\"\n"
            + "  if [ \"$n\" != \"$size\" ]; then echo 'Failure [INSTALL_FAILED_INVALID_APK: Size mismatch]'; exit 1; fi\n"
            + "  if [ -f \"$FAKE/pm-fail\" ]; then echo \"Failure [$(cat \"$FAKE/pm-fail\"): fake]\"; exit 1; fi\n"
            + "  echo Success ;;\n"
            + "uninstall)\n"
            + "  if [ -f \"$FAKE/pm-missing\" ]; then echo 'Failure [DELETE_FAILED_INTERNAL_ERROR]'; exit 1; fi\n"
            + "  echo Success ;;\n"
            + "*) echo \"Error: unknown command '$1'\"; exit 1 ;;\n"
            + "esac");
        // Not root here: ownership is recorded, not changed.
        fake("chown", "echo \"chown $*\" >> \"$FAKE_LOG\"");
        // Logs the receipts as they are when it runs (compiling comes after an install).
        fake("cmd", "echo \"cmd $*\" >> \"$FAKE_LOG\"\n"
            + "echo \"receipts then: $(cat \"$FAKE/files/receipts.json\" 2>/dev/null | tr -d '\\n')\" >> \"$FAKE_LOG\"\n"
            + "echo Success");
        fake("svc", "echo \"svc $*\" >> \"$FAKE_LOG\"");
        fake("am", "echo \"am $*\" >> \"$FAKE_LOG\"");
        // Magisk 24.0's --install-module in boot mode, in miniature: the
        // module goes to modules_update, a placeholder with the new
        // module.prop and an `update` flag to modules (util_functions.sh).
        fake("magisk", "echo \"magisk $*\" >> \"$FAKE_LOG\"\n"
            + "case \"$1\" in\n"
            + "-v) [ -f \"$FAKE/no-magisk\" ] || echo 24.0:MAGISK ;;\n"
            + "-V) [ -f \"$FAKE/no-magisk\" ] || echo 24000 ;;\n"
            + "--install-module)\n"
            + "  [ -f \"$FAKE/no-magisk\" ] && { echo 'magisk: not found'; exit 127; }\n"
            + "  t=$FAKE/unzip; rm -rf \"$t\"; mkdir -p \"$t\"\n"
            + "  (cd \"$t\" && unzip -q -o \"$2\") || { echo '! Unable to extract zip file!'; exit 1; }\n"
            + "  [ -f \"$t/module.prop\" ] || { echo '! Unable to extract zip file!'; exit 1; }\n"
            + "  id=$(sed -n 's/^id=//p' \"$t/module.prop\" | head -n 1)\n"
            + "  echo \"- Installing $id\"\n"
            + "  A=$WAVE_ADB; mkdir -p \"$A/modules_update\" \"$A/modules/$id\"\n"
            + "  rm -rf \"$A/modules_update/$id\"; mv \"$t\" \"$A/modules_update/$id\"\n"
            + "  touch \"$A/modules/$id/update\"; rm -f \"$A/modules/$id/remove\" \"$A/modules/$id/disable\"\n"
            + "  cp \"$A/modules_update/$id/module.prop\" \"$A/modules/$id/module.prop\"\n"
            + "  echo '- Done' ;;\n"
            + "esac");
        fake("getprop", "cat \"$FAKE/props/$1\" 2>/dev/null");
        fake("setprop", "echo \"setprop $*\" >> \"$FAKE_LOG\"\necho \"$2\" > \"$FAKE/props/$1\"");
        fake("pidof", "cat \"$FAKE/props/pid.$1\" 2>/dev/null");
        // What Wave's state folder held when it was synced.
        fake("sync", "echo \"sync $(ls \"$WAVE_ADB/wave\" 2>/dev/null | tr '\\n' ' ')\" >> \"$FAKE_LOG\"");
        // Each sleep is a tick; at tick N, files in at/N/ become props.
        fake("sleep", "n=$(cat \"$FAKE/ticks\" 2>/dev/null || echo 0); n=$((n + 1)); echo $n > \"$FAKE/ticks\"\n"
            + "if [ -d \"$FAKE/at/$n\" ]; then cp \"$FAKE/at/$n\"/* \"$FAKE/props/\"; fi");
    }

    private void fake(String name, String body) throws IOException {
        File f = new File(bin, name);
        write(f, "#!/bin/sh\n" + body + "\n");
        f.setExecutable(true);
    }

    /** Sets a property now. */
    void prop(String name, String value) throws IOException {
        write(new File(props, name), value + "\n");
    }

    /** Sets a property when the `tick`th sleep happens. */
    void propAt(int tick, String name, String value) throws IOException {
        File at = new File(root, "at/" + tick);
        at.mkdirs();
        write(new File(at, name), value + "\n");
    }

    void flag(String name) throws IOException {
        write(new File(root, name), "");
    }

    void flag(String name, String value) throws IOException {
        write(new File(root, name), value);
    }

    File file(String relative) {
        return new File(adb, relative);
    }

    String read(String relative) throws IOException {
        File f = file(relative);
        return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
    }

    String calls() throws IOException {
        return calls.isFile() ? new String(Files.readAllBytes(calls.toPath()), StandardCharsets.UTF_8) : "";
    }

    void clearCalls() {
        calls.delete();
    }

    /** Runs `script` with sh in the sandbox; returns its exit status and output. */
    Ran sh(String script, Map<String, String> extra) throws IOException, InterruptedException {
        File f = new File(root, "script-" + System.nanoTime() + ".sh");
        write(f, script);
        ProcessBuilder b = new ProcessBuilder(new File(bin, "sh").getAbsolutePath(), f.getAbsolutePath()).redirectErrorStream(true);
        b.environment().putAll(env());
        if (extra != null) b.environment().putAll(extra);
        Process p = b.start();
        p.getOutputStream().close();
        InputStream in = p.getInputStream();
        byte[] out = readAll(in);
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroy();
            throw new IOException("script hung");
        }
        f.delete();
        return new Ran(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    static final class Ran {
        final int exit;
        final String output;

        Ran(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }
    }

    static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    @Override
    public void close() throws IOException {
        try (Stream<java.nio.file.Path> walk = Files.walk(root.toPath())) {
            List<java.nio.file.Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            paths.sort(Comparator.reverseOrder());
            for (java.nio.file.Path p : paths) p.toFile().delete();
        }
    }
}
