package com.wave.xp3.core.install;

import com.wave.xp3.core.Version;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the queue will do, in order, before anything is done (design section
 * 4, "Doing things"): the asked-for installs, updates and removals, the
 * dependencies they pull in, the packages they replace, and Wave's own
 * update last. Or a refusal, with the reason in words.
 *
 * Inputs are plain values so this stays testable and free of how sources
 * and the phone are read: {@link Package} is a source's manifest cut down to
 * what planning needs, {@link Installed} what the phone has.
 */
public final class Plan {

    public enum Kind {
        APK, MODULE;

        /** "apk" or "module", as manifests say; null for anything else. */
        public static Kind of(String type) {
            if ("apk".equals(type)) return APK;
            if ("module".equals(type)) return MODULE;
            return null;
        }
    }

    public enum Op { INSTALL, UPDATE, REINSTALL, REMOVE }

    public static final class Dep {
        public final String id;
        /** Lowest version that will do, or null for any. */
        public final String min;

        public Dep(String id, String min) {
            this.id = id;
            this.min = min;
        }
    }

    /** A package a source offers. */
    public static final class Package {
        public final String id;
        public final Kind kind;
        public final String name;
        public final String version;
        public final String url;
        public final long size;
        public final String sha256;
        public final List<Dep> depends = new ArrayList<>();
        public final List<String> conflicts = new ArrayList<>();
        public final List<String> replaces = new ArrayList<>();
        /** Minimum SDK, 0 for any. */
        public int minSdk;
        /** Devices it runs on (ro.product.device or model); empty for any. */
        public final List<String> devices = new ArrayList<>();
        /** The source it comes from, for its receipt; null to use the engine's. */
        public String source;
        /**
         * Its source's sign-in, for a private source's download; null for none.
         * In memory only: never written down, shown or put in a receipt.
         */
        public Credential credential;

        public Package(String id, Kind kind, String name, String version, String url, long size, String sha256) {
            this.id = id;
            this.kind = kind;
            this.name = name == null || name.isEmpty() ? id : name;
            this.version = version;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /** A package on the phone. */
    public static final class Installed {
        public final String id;
        public final Kind kind;
        public final String version;
        /** A module whose removal waits for a reboot: as good as gone. */
        public final boolean removing;

        public Installed(String id, Kind kind, String version, boolean removing) {
            this.id = id;
            this.kind = kind;
            this.version = version;
            this.removing = removing;
        }
    }

    /** The phone, for `requires`; null skips those checks. */
    public static final class Device {
        public final int sdk;
        public final Set<String> names;

        public Device(int sdk, String... names) {
            this.sdk = sdk;
            this.names = new HashSet<>();
            for (String name : names) if (name != null) this.names.add(name);
        }
    }

    public static final class Request {
        /** INSTALL also means "update" for an installed package; REINSTALL; REMOVE. */
        public final Op op;
        public final String id;

        public Request(Op op, String id) {
            if (op == Op.UPDATE) op = Op.INSTALL;
            this.op = op;
            this.id = id;
        }
    }

    public static final class Step {
        public final Op op;
        public final String id;
        public final Kind kind;
        /** What to fetch; null for a removal. */
        public final Package pkg;
        /** Its name, for the screen. */
        public final String name;
        /** Null when asked for; else "Needed by X" or "Replaced by X". */
        public final String why;
        /** Wave updating itself. */
        public final boolean self;

        Step(Op op, String id, Kind kind, Package pkg, String name, String why, boolean self) {
            this.op = op;
            this.id = id;
            this.kind = kind;
            this.pkg = pkg;
            this.name = name;
            this.why = why;
            this.self = self;
        }

        @Override
        public String toString() {
            return op + " " + id + (pkg != null ? " " + pkg.version : "") + (why != null ? " (" + why + ")" : "");
        }
    }

    /** Planning stopped: {@link #getMessage()} says why, in words. */
    public static final class Refused extends Exception {
        public Refused(String message) {
            super(message);
        }
    }

    public final List<Step> steps;

    private Plan(List<Step> steps) {
        this.steps = Collections.unmodifiableList(steps);
    }

    /** True when a step only takes effect after a reboot (any module). */
    public boolean needsReboot() {
        for (Step step : steps) if (step.kind == Kind.MODULE) return true;
        return false;
    }

    public boolean installsModule() {
        for (Step step : steps) if (step.kind == Kind.MODULE && step.op != Op.REMOVE) return true;
        return false;
    }

    // ------------------------------------------------------------------

    public static Plan make(List<Request> requests, Map<String, Package> available,
            Map<String, Installed> installed, Device device, String self) throws Refused {
        return new Planner(available, installed, device, self).plan(requests);
    }

    private static final class Planner {
        final Map<String, Package> available;
        final Map<String, Installed> installed;
        final Device device;
        final String self;
        final Map<String, Step> installs = new LinkedHashMap<>();
        final Map<String, Step> removals = new LinkedHashMap<>();
        /** Replaced packages' removals, each placed right after what replaces it. */
        final Map<String, List<Step>> replacedBy = new LinkedHashMap<>();
        final Set<String> visiting = new LinkedHashSet<>();

        Planner(Map<String, Package> available, Map<String, Installed> installed, Device device, String self) {
            this.available = available;
            this.installed = installed;
            this.device = device;
            this.self = self;
        }

        Plan plan(List<Request> requests) throws Refused {
            if (requests.isEmpty()) throw new Refused("Nothing to do.");
            for (Request r : requests) {
                if (r.op == Op.REMOVE) remove(r.id, null);
            }
            for (Request r : requests) {
                if (r.op != Op.REMOVE) install(r.id, null, r.op == Op.REINSTALL);
            }
            for (String id : removals.keySet()) {
                if (installs.containsKey(id)) {
                    throw new Refused(name(id) + " can't be both removed and installed.");
                }
            }
            checkConflicts();
            checkDependents();

            List<Step> steps = new ArrayList<>();
            Step last = null;
            for (Step s : removals.values()) {
                if (s.why == null) steps.add(s);
            }
            for (Step s : installs.values()) {
                if (s.self) {
                    last = s;
                    continue;
                }
                steps.add(s);
                List<Step> replaced = replacedBy.get(s.id);
                if (replaced != null) steps.addAll(replaced);
            }
            if (last != null) {
                steps.add(last);
                List<Step> replaced = replacedBy.get(last.id);
                if (replaced != null) steps.addAll(replaced);
            }
            if (steps.isEmpty()) throw new Refused("Nothing to do.");
            return new Plan(steps);
        }

        void remove(String id, String why) throws Refused {
            if (removals.containsKey(id)) return;
            Installed here = installed.get(id);
            if (here == null || here.removing) {
                if (why != null) return;
                throw new Refused(name(id) + " isn't installed.");
            }
            if (id.equals(self)) throw new Refused("Wave can't remove itself.");
            Step step = new Step(Op.REMOVE, id, here.kind, null, name(id), why, false);
            removals.put(id, step);
        }

        /** Adds `id` and what it depends on (first), unless already satisfied. */
        void install(String id, String why, boolean reinstall) throws Refused {
            if (installs.containsKey(id)) return;
            if (visiting.contains(id)) {
                throw new Refused(name(id) + " depends on itself through " + String.join(", ", visiting) + ".");
            }
            Package pkg = available.get(id);
            if (pkg == null) {
                throw new Refused(why == null ? "No source offers " + id + "."
                    : why + " needs " + id + ", which no source offers.");
            }
            if (pkg.kind == null) throw new Refused(pkg.name + " is a kind of package Wave can't install.");
            compatible(pkg);
            Installed here = installed.get(id);
            Op op;
            if (here == null || here.removing) {
                op = Op.INSTALL;
            } else {
                if (here.kind != pkg.kind) {
                    throw new Refused(pkg.name + " is installed as a different kind of package.");
                }
                int c = Version.compare(pkg.version, here.version);
                if (c > 0) op = Op.UPDATE;
                else if (c == 0 && why == null) op = Op.REINSTALL;
                else if (c == 0) return;
                else throw new Refused("The phone has a newer " + pkg.name + " (" + here.version
                    + ") than the source's " + pkg.version + ".");
                if (reinstall && op != Op.REINSTALL) op = Op.UPDATE;
            }
            visiting.add(id);
            for (Dep dep : pkg.depends) {
                if (dep.id.equals(id)) continue;
                Installed has = installed.get(dep.id);
                boolean kept = has != null && !has.removing && !removals.containsKey(dep.id);
                if (kept && (dep.min == null || Version.compare(has.version, dep.min) >= 0)) continue;
                Package offered = available.get(dep.id);
                if (offered == null || (dep.min != null && Version.compare(offered.version, dep.min) < 0)) {
                    String wanted = dep.id + (dep.min != null ? " " + dep.min + " or newer" : "");
                    throw new Refused(pkg.name + " needs " + wanted + ", which no source offers.");
                }
                install(dep.id, pkg.name, false);
            }
            visiting.remove(id);
            installs.put(id, new Step(op, id, pkg.kind, pkg, pkg.name,
                why == null ? null : "Needed by " + why, id.equals(self)));
            for (String old : pkg.replaces) {
                if (old.equals(id)) continue;
                Installed has = installed.get(old);
                if (has == null || has.removing || removals.containsKey(old)) continue;
                if (old.equals(self)) throw new Refused(pkg.name + " would replace Wave.");
                Step step = new Step(Op.REMOVE, old, has.kind, null, name(old), "Replaced by " + pkg.name, false);
                removals.put(old, step);
                List<Step> list = replacedBy.get(id);
                if (list == null) replacedBy.put(id, list = new ArrayList<>());
                list.add(step);
            }
        }

        void compatible(Package pkg) throws Refused {
            if (device == null) return;
            if (pkg.minSdk > 0 && device.sdk < pkg.minSdk) {
                throw new Refused(pkg.name + " needs a newer Android (API " + pkg.minSdk + ").");
            }
            if (!pkg.devices.isEmpty()) {
                for (String d : pkg.devices) if (device.names.contains(d)) return;
                throw new Refused(pkg.name + " isn't made for this phone.");
            }
        }

        /** What will be on the phone afterwards. */
        Set<String> after() {
            Set<String> out = new LinkedHashSet<>();
            for (Installed i : installed.values()) {
                if (!i.removing && !removals.containsKey(i.id)) out.add(i.id);
            }
            out.addAll(installs.keySet());
            return out;
        }

        /** The manifest that will describe `id` afterwards, if known. */
        Package manifest(String id) {
            Step step = installs.get(id);
            return step != null ? step.pkg : available.get(id);
        }

        void checkConflicts() throws Refused {
            Set<String> after = after();
            for (Step s : installs.values()) {
                for (String c : s.pkg.conflicts) {
                    if (!c.equals(s.id) && after.contains(c)) {
                        throw new Refused(s.name + " can't be installed alongside " + name(c) + ".");
                    }
                }
            }
            for (String id : after) {
                if (installs.containsKey(id)) continue;
                Package p = available.get(id);
                if (p == null) continue;
                for (String c : p.conflicts) {
                    if (installs.containsKey(c)) {
                        throw new Refused(name(c) + " can't be installed alongside " + p.name + ".");
                    }
                }
            }
        }

        void checkDependents() throws Refused {
            Set<String> after = after();
            for (String gone : removals.keySet()) {
                for (String id : after) {
                    Package p = manifest(id);
                    if (p == null) continue;
                    for (Dep d : p.depends) {
                        if (d.id.equals(gone)) {
                            throw new Refused(p.name + " needs " + name(gone) + ", so it can't be removed.");
                        }
                    }
                }
            }
        }

        String name(String id) {
            Package p = available.get(id);
            return p != null ? p.name : id;
        }
    }
}
