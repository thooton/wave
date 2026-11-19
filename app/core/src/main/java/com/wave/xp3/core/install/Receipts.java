package com.wave.xp3.core.install;

import com.xp3.common.Disk;
import com.xp3.common.Json;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Wave installed (`files/receipts.json`): for each package id its kind,
 * version, source, SHA-256 and when. The phone itself says what is installed;
 * receipts say which of that came through Wave and from where, so Wave
 * doesn't offer to manage what the owner put there some other way, and can
 * name the source of an update.
 *
 * <pre>{"schema": 1, "packages": {"&lt;id&gt;": {"type": "apk", "version": "1.2.0",
 *   "source": "https://...", "sha256": "...", "time": 1763500000000}}}</pre>
 */
public final class Receipts {

    public static final class Receipt {
        public final String id;
        public final Plan.Kind kind;
        public final String version;
        public final String source;
        public final String sha256;
        public final long time;

        public Receipt(String id, Plan.Kind kind, String version, String source, String sha256, long time) {
            this.id = id;
            this.kind = kind;
            this.version = version;
            this.source = source;
            this.sha256 = sha256;
            this.time = time;
        }
    }

    private static final long MAX = 4L * 1024 * 1024;

    private final File file;
    private final Map<String, Receipt> byId = new LinkedHashMap<>();

    private Receipts(File file) {
        this.file = file;
    }

    /** Reads the file; a missing or unreadable one is an empty list. */
    public static Receipts load(File file) {
        Receipts out = new Receipts(file);
        String text = file.isFile() ? Disk.read(file, MAX) : null;
        if (text == null) return out;
        try {
            Map<String, Object> root = Json.object(Json.parse(text));
            Map<String, Object> packages = Json.child(root, "packages");
            if (packages == null) return out;
            for (Map.Entry<String, Object> e : packages.entrySet()) {
                Map<String, Object> r;
                try {
                    r = Json.object(e.getValue());
                } catch (IllegalArgumentException bad) {
                    continue;
                }
                Plan.Kind kind = Plan.Kind.of(Json.text(r, "type"));
                String version = Json.text(r, "version");
                if (kind == null || version == null) continue;
                Double time = Json.number(r, "time");
                out.byId.put(e.getKey(), new Receipt(e.getKey(), kind, version, Json.text(r, "source"),
                    Json.text(r, "sha256"), time == null ? 0 : time.longValue()));
            }
        } catch (IllegalArgumentException e) {
            // Unreadable: start again rather than refuse to work.
        }
        return out;
    }

    public Receipt get(String id) {
        return byId.get(id);
    }

    public List<Receipt> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    public void record(Receipt receipt) throws IOException {
        byId.put(receipt.id, receipt);
        save();
    }

    public void forget(String id) throws IOException {
        if (byId.remove(id) != null) save();
    }

    private void save() throws IOException {
        Map<String, Object> packages = new LinkedHashMap<>();
        for (Receipt r : byId.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", r.kind == Plan.Kind.APK ? "apk" : "module");
            m.put("version", r.version);
            if (r.source != null) m.put("source", r.source);
            if (r.sha256 != null) m.put("sha256", r.sha256);
            m.put("time", r.time);
            packages.put(r.id, m);
        }
        Disk.write(file, Json.write(Json.map("schema", 1, "packages", packages)), false);
    }
}
