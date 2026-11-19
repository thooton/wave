package com.wave.xp3.core.root;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The boot watchdog (design section 5): two scripts Wave puts into Magisk's
 * `post-fs-data.d` and `service.d`, shipped in this jar beside this class,
 * and the state they keep in `/data/adb/wave`. The engine adds a module to
 * the pending list when it installs one ({@link Scripts#installModule}); the
 * scripts do the rest at boot; the app reads back what they turned off.
 */
public final class Watchdog {

    private Watchdog() {}

    /** The scripts' name in both folders. */
    public static final String FILE = "wave-watchdog.sh";
    public static final String POST_FS_DATA = Scripts.ADB + "/post-fs-data.d/" + FILE;
    public static final String SERVICE = Scripts.ADB + "/service.d/" + FILE;

    private static final String DELIMITER = "WAVE_WATCHDOG_EOF";

    public static String postFsData() {
        return resource("watchdog-post-fs-data.sh");
    }

    public static String service() {
        return resource("watchdog-service.sh");
    }

    /** The version this app ships: the `# wave-watchdog: N` line. */
    public static int version() {
        return Math.min(version(postFsData()), version(service()));
    }

    /** The version in a script's text, or 0 when it has none (or isn't there). */
    public static int version(String script) {
        if (script == null) return 0;
        for (String line : script.split("\n")) {
            if (line.startsWith("# wave-watchdog: ")) {
                try {
                    return Integer.parseInt(line.substring(17).trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /** True when the phone's copy (from {@link Facts}) is older than this app's. */
    public static boolean needsInstall(Facts facts) {
        return facts.watchdogPostFsData < version() || facts.watchdogService < version();
    }

    /**
     * Writes both scripts (beside, then renamed into place, so a half-written
     * one is never run), root-owned and executable, and makes the state
     * folder. Magisk only runs regular files it can execute.
     */
    public static String install() {
        return "set -e\n"
            + "mkdir -p " + Scripts.ADB + "/post-fs-data.d " + Scripts.ADB + "/service.d " + Scripts.STATE + "\n"
            + "chmod 700 " + Scripts.STATE + "\n"
            + write(POST_FS_DATA, postFsData())
            + write(SERVICE, service())
            + "echo installed " + version();
    }

    private static String write(String path, String text) {
        if (text.contains("\n" + DELIMITER + "\n") || text.startsWith(DELIMITER + "\n")) {
            throw new IllegalStateException("script contains the heredoc delimiter");
        }
        String body = text.endsWith("\n") ? text : text + "\n";
        return "cat > " + path + ".new <<'" + DELIMITER + "'\n" + body + DELIMITER + "\n"
            + "chown 0:0 " + path + ".new\n"
            + "chmod 755 " + path + ".new\n"
            + "mv -f " + path + ".new " + path + "\n";
    }

    // ------------------------------------------------------------------
    // What it did
    // ------------------------------------------------------------------

    public static final class Disabled {
        /** Seconds since the epoch, when the early half turned it off. */
        public final long time;
        public final String id;
        /** `boot-incomplete` or `boot-timeout`. */
        public final String reason;

        Disabled(long time, String id, String reason) {
            this.time = time;
            this.id = id;
            this.reason = reason;
        }

        /** Why, in words, for the screen. */
        public String why() {
            if (reason.equals("boot-timeout")) {
                return "The phone didn't finish starting within five minutes after it was installed.";
            }
            return "The phone restarted before it finished starting, the first time with it installed.";
        }
    }

    public static final class State {
        public final List<Disabled> disabled;
        public final List<String> pending;
        public final List<String> trying;

        State(List<Disabled> disabled, List<String> pending, List<String> trying) {
            this.disabled = disabled;
            this.pending = pending;
            this.trying = trying;
        }
    }

    /** Prints the state, sections led by `@disabled`, `@pending`, `@trying`. */
    public static String read() {
        return "for f in disabled pending trying; do echo \"@$f\"; cat " + Scripts.STATE
            + "/$f 2>/dev/null; echo; done";
    }

    public static State parse(String output) {
        List<Disabled> disabled = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<String> trying = new ArrayList<>();
        String section = "";
        for (String raw : (output == null ? "" : output).split("\n")) {
            String line = raw.trim();
            if (line.startsWith("@")) {
                section = line.substring(1);
                continue;
            }
            if (line.isEmpty()) continue;
            if (section.equals("disabled")) {
                String[] parts = raw.split("\t");
                if (parts.length < 3 || !Scripts.isModuleId(parts[1].trim())) continue;
                disabled.add(new Disabled(Modules.number(parts[0]), parts[1].trim(), parts[2].trim()));
            } else if (section.equals("pending") || section.equals("trying")) {
                if (!Scripts.isModuleId(line)) continue;
                (section.equals("pending") ? pending : trying).add(line);
            }
        }
        return new State(disabled, pending, trying);
    }

    /** The user has seen what was turned off: keep it in `disabled.seen` instead. */
    public static String acknowledge() {
        String d = Scripts.STATE + "/disabled";
        return "if [ -f " + d + " ]; then cat " + d + " >> " + d + ".seen; rm -f " + d + "; fi";
    }

    private static String resource(String name) {
        InputStream in = Watchdog.class.getResourceAsStream(name);
        if (in == null) throw new IllegalStateException("missing " + name);
        try {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                return new String(out.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("unreadable " + name, e);
        }
    }
}
