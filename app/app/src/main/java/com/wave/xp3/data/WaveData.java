package com.wave.xp3.data;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Main-thread contract between the screens and the worker-owned sources, phone and queue. */
public interface WaveData extends AutoCloseable {
    enum Root { CHECKING, OK, NONE }
    enum State { NONE, INSTALLED, UPDATE, NEWER, PENDING_INSTALL, PENDING_REMOVAL, OFF }
    enum Action { GET, UPDATE, REINSTALL, REMOVE }
    enum Step { WAITING, DOWNLOADING, CHECKING, INSTALLING, REMOVING, DONE, FAILED, SKIPPED }

    interface Result<T> { void ok(T value); void failed(String reason); }

    Snapshot snapshot();
    void listen(Runnable onChanged);
    void unlisten(Runnable onChanged);
    void checkRoot();
    void recheckPhone();
    void refreshAll();
    void refresh(String sourceId);
    void preview(String url, Result<Preview> callback);
    void add(Preview preview, Result<Void> callback);
    void remove(String sourceId, Result<Void> callback);
    void search(String query, Result<List<Pkg>> callback);
    /** Works out what doing `action` to each of `ids` takes. */
    default void plan(List<String> ids, Action action, Result<PlanView> callback) {
        List<Request> requests = new ArrayList<>(ids.size());
        for (String id : ids) requests.add(new Request(id, action));
        plan(requests, callback);
    }
    /** Works out what the requests take together, each with its own action (a queue: Get one, Remove another). */
    void plan(List<Request> requests, Result<PlanView> callback);
    void confirm(PlanView plan);
    void cancel();
    void reboot();
    void turnOn(String moduleId);
    void ackTurnedOff(String moduleId);
    @Override void close();

    final class Preview {
        public final String name, maintainer, redactedUrl;
        public final int packageCount;
        /** Packages in the source Wave can't read; packageCount leaves them out. */
        public final int unreadable;
        private final byte[] icon;
        final Object token;
        public Preview(String name, String maintainer, int packageCount, byte[] icon, String redactedUrl) {
            this(name, maintainer, packageCount, 0, icon, redactedUrl, null);
        }
        Preview(String name, String maintainer, int packageCount, int unreadable, byte[] icon, String redactedUrl, Object token) {
            this.name = name; this.maintainer = maintainer; this.packageCount = packageCount; this.unreadable = unreadable;
            this.icon = icon == null ? null : icon.clone(); this.redactedUrl = redactedUrl; this.token = token;
        }
        public byte[] icon() { return icon == null ? null : icon.clone(); }
    }

    final class Src {
        public final String id, name, url, maintainer, error;
        public final File iconFile;
        public final boolean isDefault, refreshing, needsNewerWave;
        public final long refreshedAt;
        public final int packageCount;
        /**
         * What the source's page lists as not read, as "folder: reason" lines:
         * packages left out, then icons ignored (their packages are listed).
         */
        public final List<String> unreadable, iconsIgnored;
        /** How many packages can't be read (the lines stop at 20, then "and N more"). */
        public final int unreadableCount;
        public Src(String id, String name, String url, File iconFile, boolean isDefault, String maintainer,
                   long refreshedAt, int packageCount, boolean refreshing, String error, boolean needsNewerWave) {
            this(id, name, url, iconFile, isDefault, maintainer, refreshedAt, packageCount, refreshing, error, needsNewerWave,
                0, Collections.emptyList(), Collections.emptyList());
        }
        public Src(String id, String name, String url, File iconFile, boolean isDefault, String maintainer,
                   long refreshedAt, int packageCount, boolean refreshing, String error, boolean needsNewerWave,
                   int unreadableCount, List<String> unreadable, List<String> iconsIgnored) {
            this.unreadableCount = unreadableCount;
            this.id = id; this.name = name; this.url = url; this.iconFile = iconFile; this.isDefault = isDefault;
            this.maintainer = maintainer; this.refreshedAt = refreshedAt; this.packageCount = packageCount;
            this.refreshing = refreshing; this.error = error; this.needsNewerWave = needsNewerWave;
            this.unreadable = Collections.unmodifiableList(new ArrayList<>(unreadable));
            this.iconsIgnored = Collections.unmodifiableList(new ArrayList<>(iconsIgnored));
        }
    }

    final class Section {
        public final String folder, name;
        public final int count;
        public Section(String folder, String name, int count) { this.folder = folder; this.name = name; this.count = count; }
    }

    final class Change {
        public final String version, date, text;
        public Change(String version, String date, String text) { this.version = version; this.date = date; this.text = text; }
    }

    final class Pkg {
        public final String id, name, summary, version, installedVersion, unfitReason, sourceId, sourceName, sectionName;
        public final State state;
        public final File iconFile;
        public final boolean needsNewerWave, isModule;
        public final Change newestChange;
        public Pkg(String id, String name, String summary, File iconFile, String version, String installedVersion,
                   State state, String unfitReason, boolean needsNewerWave, boolean isModule, Change newestChange,
                   String sourceId, String sourceName, String sectionName) {
            this.id = id; this.name = name; this.summary = summary; this.iconFile = iconFile; this.version = version;
            this.installedVersion = installedVersion; this.state = state; this.unfitReason = unfitReason;
            this.needsNewerWave = needsNewerWave; this.isModule = isModule; this.newestChange = newestChange;
            this.sourceId = sourceId; this.sourceName = sourceName; this.sectionName = sectionName;
        }
    }

    final class Detail {
        public final Pkg pkg;
        public final String description, author;
        public final long size;
        public final List<Change> changes;
        public final List<String> depends, conflicts, replaces;
        public final boolean firmwareNote;
        public Detail(Pkg pkg, String description, String author, long size, List<Change> changes,
                      List<String> depends, List<String> conflicts, List<String> replaces, boolean firmwareNote) {
            this.pkg = pkg; this.description = description; this.author = author; this.size = size;
            this.changes = frozen(changes); this.depends = frozen(depends); this.conflicts = frozen(conflicts);
            this.replaces = frozen(replaces); this.firmwareNote = firmwareNote;
        }
    }

    final class Facts {
        public final String model, android, build, root, storageFree;
        public Facts(String model, String android, String build, String root, String storageFree) {
            this.model = model; this.android = android; this.build = build; this.root = root; this.storageFree = storageFree;
        }
    }

    final class TurnedOff {
        public final String id, name, version, reason;
        public TurnedOff(String id, String name, String version, String reason) {
            this.id = id; this.name = name; this.version = version; this.reason = reason;
        }
    }

    /** One thing asked of a package: Get, Update, Reinstall or Remove it. */
    final class Request {
        public final String id;
        public final Action action;
        public Request(String id, Action action) { this.id = id; this.action = action; }
        @Override public boolean equals(Object o) {
            return o instanceof Request && ((Request) o).id.equals(id) && ((Request) o).action == action;
        }
        @Override public int hashCode() { return id.hashCode() * 31 + action.hashCode(); }
        @Override public String toString() { return action + " " + id; }
    }

    final class PlanItem {
        public final Pkg pkg;
        public final String reason;
        public final Action action;
        public PlanItem(Pkg pkg, String reason, Action action) { this.pkg = pkg; this.reason = reason; this.action = action; }
    }

    final class PlanView {
        public final List<PlanItem> items, install, remove;
        public final long downloadBytes;
        public final boolean needsReboot;
        public final String refusal;
        final Object token;
        public PlanView(List<PlanItem> items, long downloadBytes, boolean needsReboot, String refusal) {
            this(items, downloadBytes, needsReboot, refusal, null);
        }
        PlanView(List<PlanItem> items, long downloadBytes, boolean needsReboot, String refusal, Object token) {
            this.items = frozen(items);
            List<PlanItem> installs = new ArrayList<>(), removals = new ArrayList<>();
            for (PlanItem item : items) (item.action == Action.REMOVE ? removals : installs).add(item);
            this.install = frozen(installs); this.remove = frozen(removals); this.downloadBytes = downloadBytes;
            this.needsReboot = needsReboot; this.refusal = refusal; this.token = token;
        }
    }

    final class QueueItem {
        public final Pkg pkg;
        public final Step step;
        public final int percent;
        public final long bytes, total;
        public final String text, failure;
        public QueueItem(Pkg pkg, Step step, int percent, long bytes, long total, String text, String failure) {
            this.pkg = pkg; this.step = step; this.percent = percent; this.bytes = bytes; this.total = total;
            this.text = text; this.failure = failure;
        }
    }

    final class QueueView {
        public final List<QueueItem> items;
        public final boolean finished, needsReboot;
        public QueueView(List<QueueItem> items, boolean finished, boolean needsReboot) {
            this.items = frozen(items); this.finished = finished; this.needsReboot = needsReboot;
        }
    }

    final class Shelf {
        public final List<Pkg> featured, packages;
        public final List<Section> sections;
        private final Map<String, List<Pkg>> bySection;
        private final Map<String, Detail> details;
        public Shelf(List<Pkg> featured, List<Pkg> packages, List<Section> sections,
                     Map<String, List<Pkg>> bySection, Map<String, Detail> details) {
            this.featured = frozen(featured); this.packages = frozen(packages); this.sections = frozen(sections);
            Map<String, List<Pkg>> grouped = new LinkedHashMap<>();
            bySection.forEach((folder, entries) -> grouped.put(folder, frozen(entries)));
            this.bySection = Collections.unmodifiableMap(grouped);
            this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }
        public List<Pkg> packages(String folder) { return folder == null ? packages : bySection.getOrDefault(folder, Collections.emptyList()); }
        public Detail detail(String id) { return details.get(id); }
        public static Shelf empty() {
            return new Shelf(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyMap(), Collections.emptyMap());
        }
    }

    final class Snapshot {
        public final Root root;
        public final String firstLoadError, actionError;
        public final List<Src> sources;
        public final List<Pkg> updates, upToDate;
        public final List<String> rebootPending;
        public final List<TurnedOff> turnedOff;
        public final Facts facts;
        public final QueueView queue;
        private final Shelf all;
        private final Map<String, Shelf> bySource;
        public Snapshot(Root root, String firstLoadError, String actionError, List<Src> sources, Shelf all,
                        Map<String, Shelf> bySource, List<Pkg> updates, List<Pkg> upToDate,
                        List<String> rebootPending, List<TurnedOff> turnedOff, Facts facts, QueueView queue) {
            this.root = root; this.firstLoadError = firstLoadError; this.actionError = actionError; this.sources = frozen(sources);
            this.all = all; this.bySource = Collections.unmodifiableMap(new LinkedHashMap<>(bySource));
            this.updates = frozen(updates); this.upToDate = frozen(upToDate); this.rebootPending = frozen(rebootPending);
            this.turnedOff = frozen(turnedOff); this.facts = facts; this.queue = queue;
        }
        private Shelf shelf(String sourceId) { return sourceId == null ? all : bySource.getOrDefault(sourceId, Shelf.empty()); }
        public List<Pkg> featured(String sourceId) {
            return shelf(sourceId).featured;
        }
        public List<Section> sections(String sourceId) { return shelf(sourceId).sections; }
        public List<Pkg> packages(String sourceId, String folder) { return shelf(sourceId).packages(folder); }
        public Detail detail(String sourceId, String id) { return shelf(sourceId).detail(id); }
        public Detail detail(String id) { return all.detail(id); }
    }

    static <T> List<T> frozen(List<T> items) { return Collections.unmodifiableList(new ArrayList<>(items)); }
}
