package com.wave.xp3.core.install;

import com.wave.xp3.core.sources.PackageManifest;
import com.xp3.common.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Packages for the planner, from the two places they come from: a source's
 * checked `package.json` ({@link #of(PackageManifest)}, what the screens
 * use), and a hand-written test plan ({@link #planFile}), which the debug
 * build reads to drive the engine on a phone over adb (app/ROOT-TESTING.md).
 */
public final class Packages {

    private Packages() {}

    public static Plan.Package of(PackageManifest m) {
        Plan.Package p = new Plan.Package(m.id, Plan.Kind.of(m.type), m.name, m.version, m.download.url,
            m.download.size, m.download.sha256);
        for (PackageManifest.Dependency d : m.depends) {
            p.depends.add(new Plan.Dep(d.id, d.min == null || d.min.isEmpty() ? null : d.min));
        }
        p.conflicts.addAll(m.conflicts);
        p.replaces.addAll(m.replaces);
        p.minSdk = (int) m.requires.sdk;
        p.devices.addAll(m.requires.device);
        return p;
    }

    /** A test plan: what to ask for, and the packages it may use. */
    public static final class PlanFile {
        public final String source;
        public final List<Plan.Request> requests = new ArrayList<>();
        public final Map<String, Plan.Package> packages = new LinkedHashMap<>();

        PlanFile(String source) {
            this.source = source;
        }
    }

    /**
     * Reads a test plan:
     *
     * <pre>{"source": "debug",
     *  "requests": [{"op": "install", "id": "wave_test_broken"}],
     *  "packages": [{"id": "wave_test_broken", "type": "module", "version": "1.0",
     *                "download": {"url": "...", "size": 123, "sha256": "..."},
     *                "depends": ["x", {"id": "y", "min": "1.0"}], "conflicts": [], "replaces": []}]}</pre>
     *
     * Looser than the source format (any URL Download accepts, including
     * plain http to this phone's loopback through `adb reverse`); every
     * other check is the engine's own. Throws IllegalArgumentException.
     */
    public static PlanFile planFile(String json) {
        Map<String, Object> root = Json.object(Json.parse(json));
        PlanFile out = new PlanFile(Json.text(root, "source"));
        for (Object item : Json.children(root, "packages")) {
            Map<String, Object> m = Json.object(item);
            Map<String, Object> d = Json.child(m, "download");
            Double size = Json.number(d, "size");
            String id = Json.string(m, "id");
            Plan.Package p = new Plan.Package(id, Plan.Kind.of(Json.string(m, "type")), Json.text(m, "name"),
                Json.string(m, "version"), Json.text(d, "url"), size == null ? -1 : size.longValue(),
                Json.text(d, "sha256"));
            for (Object dep : Json.children(m, "depends")) {
                if (dep instanceof String) p.depends.add(new Plan.Dep((String) dep, null));
                else p.depends.add(new Plan.Dep(Json.string(Json.object(dep), "id"), Json.text(Json.object(dep), "min")));
            }
            for (Object c : Json.children(m, "conflicts")) p.conflicts.add(String.valueOf(c));
            for (Object r : Json.children(m, "replaces")) p.replaces.add(String.valueOf(r));
            out.packages.put(id, p);
        }
        for (Object item : Json.children(root, "requests")) {
            Map<String, Object> r = Json.object(item);
            String op = Json.string(r, "op");
            Plan.Op o;
            if (op.equals("install") || op.equals("update")) o = Plan.Op.INSTALL;
            else if (op.equals("reinstall")) o = Plan.Op.REINSTALL;
            else if (op.equals("remove")) o = Plan.Op.REMOVE;
            else throw new IllegalArgumentException("Unknown op " + op);
            out.requests.add(new Plan.Request(o, Json.string(r, "id")));
        }
        return out;
    }
}
