package com.wave.xp3.core.sources;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The source header. An unfamiliar format version can still advertise its name. */
public final class SourceManifest {
    public static final int FORMAT = 1;
    public final long wave;
    public final String name, description, maintainer, homepage;
    public final Map<String, String> sections;
    public final List<String> featured;

    private SourceManifest(Fields fields) {
        wave = fields.number("wave", true);
        name = fields.string("name", 1, 40, true);
        description = fields.string("description", 0, 200, false);
        maintainer = fields.string("maintainer", 0, 60, false);
        homepage = fields.https("homepage", false);
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : fields.child("sections", false).entries().entrySet()) {
            if (!Fields.FOLDER.matcher(entry.getKey()).matches()) throw Fields.bad("sections", "contains an invalid folder name");
            labels.put(entry.getKey(), Fields.string(entry.getValue(), "sections." + entry.getKey(), 1, 30));
        }
        sections = Collections.unmodifiableMap(labels);
        featured = fields.strings("featured", 0, 10, true);
    }

    public static SourceManifest parse(byte[] utf8) { return new SourceManifest(Fields.parse(utf8, "wave.json")); }
    public boolean needsNewerWave() { return wave > FORMAT; }
}
