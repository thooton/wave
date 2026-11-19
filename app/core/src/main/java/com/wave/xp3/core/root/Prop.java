package com.wave.xp3.core.root;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `key=value` files: a module's `module.prop`, and the {@link Scripts#facts}
 * answer. Blank lines and `#` comments are skipped; the first `=` splits;
 * of a key given twice the first is kept, as Magisk's `grep_prop` does.
 */
public final class Prop {

    private Prop() {}

    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : (text == null ? "" : text).split("\n")) line(line, out);
        return out;
    }

    static void line(String raw, Map<String, String> into) {
        String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return;
        int eq = line.indexOf('=');
        if (eq <= 0) return;
        String key = line.substring(0, eq).trim();
        if (key.isEmpty() || into.containsKey(key)) return;
        into.put(key, line.substring(eq + 1).trim());
    }
}
