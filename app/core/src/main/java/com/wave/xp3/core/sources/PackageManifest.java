package com.wave.xp3.core.sources;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** A package's format-v1 metadata. Unknown types are parsed but never installable. */
public final class PackageManifest {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern MULTILINE = Pattern.compile("[\r\n]");
    public final String id, type, name, summary, description, author, homepage, license, version;
    public final Download download;
    public final Requires requires;
    public final List<Dependency> depends;
    public final List<String> conflicts, replaces;
    public final List<Change> changes;

    public static final class Download {
        public final String url, sha256;
        public final long size;
        private Download(Fields fields) {
            url = fields.https("url", true);
            size = fields.number("size", true);
            sha256 = fields.matching("sha256", SHA256, "64 lowercase hexadecimal characters", true);
        }
    }

    public static final class Requires {
        public final List<String> device, firmware;
        /** Zero means no minimum. */
        public final long sdk, wave;
        private Requires(Fields fields) {
            device = fields.strings("device", 1, Integer.MAX_VALUE, false);
            firmware = fields.strings("firmware", 0, Integer.MAX_VALUE, false);
            sdk = fields.number("sdk", false);
            wave = fields.number("wave", false);
        }
    }

    public static final class Dependency {
        public final String id, min;
        private Dependency(String id, String min) { this.id = id; this.min = min; }
    }

    public static final class Change {
        public final String version, date, text;
        private Change(Fields fields) {
            version = fields.matching("version", Fields.VERSION, "a dot-separated numeric version", true);
            date = fields.matching("date", DATE, "YYYY-MM-DD", false);
            if (!date.isEmpty()) {
                try {
                    if (LocalDate.parse(date).getYear() == 0) throw new DateTimeException("year zero");
                } catch (DateTimeException e) { throw Fields.bad(fields.at("date"), "must be a calendar date"); }
            }
            text = fields.string("text", 1, 500, true);
        }
    }

    private PackageManifest(Fields fields) {
        id = fields.matching("id", Fields.ID, "a package id", true);
        type = fields.string("type", 1, Integer.MAX_VALUE, true);
        name = fields.string("name", 1, 30, true);
        summary = fields.string("summary", 1, 80, true);
        if (MULTILINE.matcher(summary).find()) throw Fields.bad("summary", "must fit on one line");
        description = fields.string("description", 0, 4000, false);
        author = fields.string("author", 0, 60, false);
        homepage = fields.https("homepage", false);
        license = fields.string("license", 0, 40, false);
        version = fields.matching("version", Fields.VERSION, "a dot-separated numeric version", true);
        download = new Download(fields.child("download", true));
        requires = new Requires(fields.child("requires", false));
        List<Dependency> deps = new ArrayList<>();
        for (Object item : fields.array("depends", 0, Integer.MAX_VALUE)) {
            String where = "depends[" + deps.size() + ']';
            if (item instanceof String) {
                deps.add(new Dependency(Fields.matching(item, where, Fields.ID, "a package id"), ""));
            } else {
                Fields dep = Fields.object(item, where);
                deps.add(new Dependency(dep.matching("id", Fields.ID, "a package id", true),
                    dep.matching("min", Fields.VERSION, "a dot-separated numeric version", false)));
            }
        }
        depends = Collections.unmodifiableList(deps);
        conflicts = fields.strings("conflicts", 0, Integer.MAX_VALUE, true);
        replaces = fields.strings("replaces", 0, Integer.MAX_VALUE, true);
        List<Change> notes = new ArrayList<>();
        for (Object item : fields.array("changes", 0, 10)) notes.add(new Change(Fields.object(item, "changes[" + notes.size() + ']')));
        changes = Collections.unmodifiableList(notes);
    }

    public static PackageManifest parse(byte[] utf8) { return new PackageManifest(Fields.parse(utf8, "package.json")); }
    public boolean supportedType() { return "apk".equals(type) || "module".equals(type); }
}
