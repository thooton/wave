package com.wave.xp3.core.root;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Magisk modules on the phone, from {@link Scripts#listModules}.
 *
 * A module can be in two places. `/data/adb/modules/<id>` is what Magisk
 * mounted at boot (or, right after an install, a placeholder holding the new
 * `module.prop` and an `update` flag); `/data/adb/modules_update/<id>` is a
 * module installed since and waiting for the next boot. Flags in a module's
 * folder: `disable` (left out at boot), `remove` (deleted at the next boot),
 * `update` (an install is waiting). So "installed", "pending install" and
 * "pending removal" are separate states, and disabled is a fourth.
 */
public final class Modules {

    private Modules() {}

    public enum State {
        INSTALLED, DISABLED, PENDING_INSTALL, PENDING_REMOVAL;

        /** Takes effect only after a reboot. */
        public boolean pending() {
            return this == PENDING_INSTALL || this == PENDING_REMOVAL;
        }
    }

    public static final class Module {
        public final String id;
        public final String name;
        public final String version;
        public final long versionCode;
        public final String author;
        public final State state;

        Module(String id, Map<String, String> prop, State state) {
            this.id = id;
            this.name = prop.containsKey("name") ? prop.get("name") : id;
            this.version = prop.get("version");
            this.versionCode = number(prop.get("versionCode"));
            this.author = prop.get("author");
            this.state = state;
        }

        @Override
        public String toString() {
            return id + " " + version + " " + state;
        }
    }

    private static final class Folder {
        final Map<String, String> prop = new LinkedHashMap<>();
        boolean disable;
        boolean remove;
        boolean update;
    }

    /** The modules, sorted by id. Lines it doesn't understand are skipped. */
    public static List<Module> parse(String output) {
        Map<String, Folder> mounted = new LinkedHashMap<>();
        Map<String, Folder> waiting = new LinkedHashMap<>();
        Folder current = null;
        for (String raw : (output == null ? "" : output).split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith("@modules ") || line.startsWith("@modules_update ")) {
                String[] parts = line.split(" ", 3);
                if (parts.length < 2 || !Scripts.isModuleId(parts[1])) {
                    current = null;
                    continue;
                }
                current = new Folder();
                String flags = parts.length > 2 ? parts[2] : "";
                for (String flag : flags.split(",")) {
                    if (flag.equals("disable")) current.disable = true;
                    else if (flag.equals("remove")) current.remove = true;
                    else if (flag.equals("update")) current.update = true;
                }
                (parts[0].equals("@modules") ? mounted : waiting).put(parts[1], current);
            } else if (line.equals("@end")) {
                current = null;
            } else if (current != null) {
                Prop.line(line, current.prop);
            }
        }
        List<Module> out = new ArrayList<>();
        for (Map.Entry<String, Folder> e : waiting.entrySet()) {
            String id = e.getKey();
            Folder w = e.getValue();
            Folder m = mounted.get(id);
            boolean disabled = w.disable || (m != null && m.disable);
            State state = m != null && m.remove ? State.PENDING_REMOVAL
                : disabled ? State.DISABLED : State.PENDING_INSTALL;
            out.add(new Module(id, w.prop.isEmpty() && m != null ? m.prop : w.prop, state));
        }
        for (Map.Entry<String, Folder> e : mounted.entrySet()) {
            if (waiting.containsKey(e.getKey())) continue;
            Folder m = e.getValue();
            State state = m.remove ? State.PENDING_REMOVAL : m.disable ? State.DISABLED : State.INSTALLED;
            out.add(new Module(e.getKey(), m.prop, state));
        }
        Collections.sort(out, (a, b) -> a.id.compareTo(b.id));
        return out;
    }

    /** True when every module is disabled, as Magisk's safe mode leaves them. */
    public static boolean allDisabled(List<Module> modules) {
        if (modules.isEmpty()) return false;
        for (Module m : modules) {
            if (m.state != State.DISABLED && m.state != State.PENDING_REMOVAL) return false;
        }
        return true;
    }

    static long number(String text) {
        if (text == null) return -1;
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
