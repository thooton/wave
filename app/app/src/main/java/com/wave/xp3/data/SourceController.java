package com.wave.xp3.data;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.format.Formatter;
import com.wave.xp3.EngineService;
import com.wave.xp3.Phone;
import com.wave.xp3.RootShell;
import com.wave.xp3.core.install.Engine;
import com.wave.xp3.core.install.Packages;
import com.wave.xp3.core.install.Plan;
import com.wave.xp3.core.install.Receipts;
import com.wave.xp3.core.root.Modules;
import com.wave.xp3.core.root.Scripts;
import com.wave.xp3.core.root.Shell;
import com.wave.xp3.core.root.Watchdog;
import com.wave.xp3.core.sources.Compat;
import com.wave.xp3.core.sources.InFlight;
import com.wave.xp3.core.sources.Index;
import com.wave.xp3.core.sources.PackageManifest;
import com.wave.xp3.core.sources.SourceManifest;
import com.wave.xp3.core.sources.SourceStore;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Loads storage and phone state off the UI thread; publishes immutable snapshots on it. */
public final class SourceController implements WaveData {
    private final Context context;
    private final Phone phone;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "wave-data"));
    private final ExecutorService network = Executors.newFixedThreadPool(2, r -> new Thread(r, "wave-sources"));
    private final ExecutorService searches = Executors.newSingleThreadExecutor(r -> new Thread(r, "wave-search"));
    private final List<Runnable> listeners = new ArrayList<>();
    private final Map<String, String> sourceErrors = new LinkedHashMap<>();
    private final Set<String> refreshing = new HashSet<>();
    private final Set<String> acknowledged = new HashSet<>();
    private final EngineService.Listener engineListener = this::engineChanged;
    private final Compat.Phone compatibility = new Compat.Phone(Build.DEVICE, Build.MODEL, Build.VERSION.SDK_INT,
        Build.FINGERPRINT, SourceManifest.FORMAT);
    private volatile SourceStore store;
    private volatile boolean closed;
    // State below is published and consumed on the main thread unless noted otherwise.
    private Root root = Root.CHECKING;
    private String firstLoadError, actionError, rootLabel = "Checking…";
    private Inventory inventory = new Inventory();
    private Index shown;
    // Main thread: the source page's lines per index source, by identity.
    private final java.util.Map<Index.Source, List<List<String>>> pageLines = new java.util.IdentityHashMap<>();
    private CatalogModel catalog;
    private QueueView queue;
    private Snapshot snapshot;
    private volatile long searchEpoch;
    private boolean startedRefresh;
    private String notice;
    // One tracked phone read at a time; a request made meanwhile becomes a single follow-up read.
    private final InFlight reading = new InFlight();
    private final java.util.concurrent.atomic.AtomicBoolean rereadQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private static final int EXPLICIT = 1, NEW_INDEX = 2;
    private Pending proposed;
    // Accessed only by the data worker.
    private Index workerIndex;

    public SourceController(Context context) {
        this.context = context.getApplicationContext();
        phone = new Phone(this.context);
        render();
        EngineService.listen(engineListener);
        Engine active = EngineService.current();
        if (active != null && !active.finished()) engineChanged(active);
        reading.begin(NEW_INDEX); // The first read is in flight from here; a resume or root request meanwhile joins it.
        worker.execute(() -> {
            try {
                store = new SourceStore(this.context.getFilesDir());
                String saved = store.notice();
                if (saved != null) post(() -> { notice = saved; actionError = saved; render(); });
            } catch (IOException e) { post(() -> { firstLoadError = message(e); render(); }); }
            readTracked(NEW_INDEX);
        });
    }

    @Override public Snapshot snapshot() { return snapshot; }
    @Override public void listen(Runnable listener) { listeners.add(listener); }
    @Override public void unlisten(Runnable listener) { listeners.remove(listener); }

    private void render() {
        List<Src> rows = new ArrayList<>();
        // Each index source's page lines are built once; only the shown index's are kept.
        java.util.Set<Index.Source> live = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (shown != null) live.addAll(shown.sources());
        pageLines.keySet().retainAll(live);
        if (shown != null) for (Index.Source s : shown.sources()) {
            String error = sourceErrors.get(s.info.id);
            // The reader's words for the source's own details, never the raw issue.
            if (error == null && s.issues.containsKey("wave.json")) {
                error = s.headerProblem != null ? s.headerProblem : com.wave.xp3.core.git.SourceFailure.Kind.UNREADABLE.says;
            }
            List<List<String>> lines = pageLines.get(s);
            if (lines == null) pageLines.put(s, lines = Index.pageLines(s, 20));
            rows.add(new Src(s.info.id, s.info.name, s.info.url, s.icon, s.info.builtIn,
                s.manifest == null ? "" : s.manifest.maintainer, s.info.lastRefresh, s.packages.size(),
                refreshing.contains(s.info.id), error, s.manifest != null && s.manifest.needsNewerWave(),
                s.unreadable(), lines.get(0), lines.get(1)));
        }
        List<TurnedOff> alerts = new ArrayList<>();
        for (TurnedOff alert : inventory.turnedOff) if (!acknowledged.contains(alert.id)) alerts.add(alert);
        snapshot = new Snapshot(root, firstLoadError, actionError, rows, catalog == null ? Shelf.empty() : catalog.all,
            catalog == null ? Collections.emptyMap() : catalog.shelves,
            catalog == null ? Collections.emptyList() : catalog.updates, catalog == null ? Collections.emptyList() : catalog.upToDate,
            catalog == null ? Collections.emptyList() : catalog.rebootPending, alerts,
            new Facts(Build.MODEL, Build.VERSION.RELEASE, Build.DISPLAY, rootLabel, inventory.free), queue);
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }

    @Override public void checkRoot() {
        root = Root.CHECKING;
        render();
        schedulePhone(EXPLICIT);
    }

    @Override public void recheckPhone() { schedulePhone(0); }

    /** Main thread. Starts a read unless one is in flight; then the request is merged into its single follow-up. */
    private void schedulePhone(int flags) {
        if (closed || !reading.begin(flags)) return;
        submit(worker, () -> readTracked(flags));
    }

    /** A read that holds the gate. However it ends, the gate must be let go or no read would ever run again. */
    private void readTracked(int flags) {
        try { readPhone((flags & EXPLICIT) != 0, (flags & NEW_INDEX) != 0, true); }
        catch (RuntimeException e) { post(this::readEnded); }
    }

    private void readEnded() {
        int again = reading.finish();
        if (again != 0) submit(worker, () -> readTracked(again));
    }

    /** Untracked reads (after a change the worker itself made) always run and see the latest store. */
    private void readPhone(boolean explicit, boolean newIndex, boolean tracked) {
        if (store != null && (newIndex || workerIndex == null)) workerIndex = store.index();
        Inventory state = new Inventory();
        state.free = Formatter.formatFileSize(context, new StatFs(context.getFilesDir().getPath()).getAvailableBytes());
        boolean granted = false;
        // Before Wave has asked, it can't say whether the phone has root.
        String label = "Not checked";
        if (explicit || RootShell.known(context)) {
            label = "Not granted";
            try {
                // This only checks permission. Obtaining root remains the user's choice in their root manager.
                Shell.Result result = root(Scripts.whoami());
                granted = result.ok() && Scripts.isRoot(result.output);
                if (granted) {
                    com.wave.xp3.core.root.Facts facts = com.wave.xp3.core.root.Facts.parse(root(Scripts.facts()).output);
                    label = !facts.magisk.isEmpty() ? "Magisk " + facts.magisk.split(":", 2)[0] :
                        facts.hasMagisk() ? "Magisk" : "Available";
                    for (Modules.Module module : phone.modules()) state.modules.put(module.id, module);
                    for (Watchdog.Disabled item : Watchdog.parse(root(Watchdog.read()).output).disabled) {
                        Modules.Module m = state.modules.get(item.id);
                        state.turnedOff.add(new TurnedOff(item.id, m == null ? item.id : m.name,
                            m == null ? "" : m.version, item.why()));
                    }
                }
            } catch (Shell.NoRoot e) { granted = false; }
            catch (IOException ignored) { /* A device query can fail while a root action is still settling. */ }
        }
        Index currentIndex = workerIndex;
        Receipts receipts = Receipts.load(new File(context.getFilesDir(), "receipts.json"));
        Set<String> ids = new HashSet<>();
        ids.add(context.getPackageName());
        for (Receipts.Receipt r : receipts.all()) if (r.kind == Plan.Kind.APK) ids.add(r.id);
        if (currentIndex != null) for (Index.Source source : currentIndex.sources()) {
            for (Index.Entry e : source.packages) {
                PackageManifest p = e.manifest;
                // Include referred-to APKs even when the source no longer offers them; removal/conflicts need real inventory.
                ids.add(p.id); ids.addAll(p.conflicts); ids.addAll(p.replaces);
                for (PackageManifest.Dependency dep : p.depends) ids.add(dep.id);
            }
        }
        state.apps.putAll(phone.apps(ids));
        Map<String, String> labels = new LinkedHashMap<>();
        for (String id : state.apps.keySet()) if (id.equals(context.getPackageName()) || receipts.get(id) != null) {
            PackageManager pm = context.getPackageManager();
            try { labels.put(id, pm.getApplicationLabel(pm.getApplicationInfo(id, 0)).toString()); }
            catch (PackageManager.NameNotFoundException ignored) { /* Package removed since reading its version. */ }
        }
        CatalogModel model = currentIndex == null ? null : new CatalogModel(currentIndex, compatibility, state.apps, state.modules, receipts, labels);
        final boolean haveRoot = granted;
        final String rootText = label;
        post(() -> {
            // While the user's root request waits behind this read, the screen keeps saying it is checking.
            if (!(tracked && reading.waiting(EXPLICIT))) {
                root = haveRoot ? Root.OK : Root.NONE;
                rootLabel = rootText;
            }
            inventory = state; catalog = model; shown = currentIndex;
            if (shown != null && shown.sources().stream().anyMatch(s -> s.manifest != null)) firstLoadError = null;
            render();
            if (store != null && !startedRefresh) {
                startedRefresh = true;
                refreshAll();
            }
            if (tracked) readEnded();
        });
    }

    private static final class Inventory {
        final Map<String, Plan.Installed> apps = new LinkedHashMap<>();
        final Map<String, Modules.Module> modules = new LinkedHashMap<>();
        final List<TurnedOff> turnedOff = new ArrayList<>();
        String free = "";
    }

    @Override public void refreshAll() {
        if (closed) return;
        if (store == null) { firstLoadError = "Wave couldn't read its list of sources."; render(); return; }
        if (shown != null) for (Index.Source source : shown.sources()) refresh(source.info.id);
    }

    @Override public void refresh(String sourceId) {
        SourceStore sources = store;
        if (closed || sources == null || !refreshing.add(sourceId)) return;
        sourceErrors.remove(sourceId);
        if (actionError != null && !actionError.equals(notice)) actionError = null;
        render();
        submit(network, () -> {
            try {
                sources.refresh(sourceId);
                submit(worker, () -> {
                    post(() -> refreshing.remove(sourceId));
                    readAfterRefresh();
                });
            } catch (IOException | RuntimeException e) {
                post(() -> {
                    refreshing.remove(sourceId);
                    sourceErrors.put(sourceId, message(e));
                    if (refreshing.isEmpty() && shown != null && shown.sources().stream().noneMatch(s -> s.manifest != null)) {
                        firstLoadError = message(e);
                    }
                    render();
                });
            }
        });
    }

    /**
     * Worker. Reads the sources and the phone again after a source refreshed, once for however many sources finished
     * while the last read ran: with 100 sources refreshing together, a read for each would be 100 reads, each slower
     * than the refresh it follows. A source that finishes during a read is picked up by the one that follows.
     */
    private void readAfterRefresh() {
        if (!rereadQueued.compareAndSet(false, true)) return;
        submit(worker, () -> {
            rereadQueued.set(false);
            readPhone(false, true, false);
        });
    }

    @Override public void preview(String url, Result<Preview> callback) {
        request(network, () -> {
            SourceStore.Preview result = sources().preview(url);
            return new Preview(result.manifest.name, result.manifest.maintainer, result.packageCount, result.unreadable,
                result.icon(), result.url, result);
        }, callback);
    }

    @Override public void add(Preview preview, Result<Void> callback) {
        request(worker, () -> {
            if (!(preview.token instanceof SourceStore.Preview)) throw new IOException("Fetch the source again. Then add it.");
            sources().add((SourceStore.Preview) preview.token);
            readPhone(false, true, false);
            return null;
        }, callback);
    }

    @Override public void remove(String sourceId, Result<Void> callback) {
        request(worker, () -> {
            sources().remove(sourceId);
            readPhone(false, true, false);
            return null;
        }, callback);
    }

    @Override public void search(String query, Result<List<Pkg>> callback) {
        long epoch = ++searchEpoch;
        final CatalogModel model = catalog;
        request(searches, () -> epoch == searchEpoch && model != null ? model.search(query) : Collections.emptyList(), new Result<List<Pkg>>() {
            @Override public void ok(List<Pkg> value) { if (epoch == searchEpoch) callback.ok(value); }
            @Override public void failed(String reason) { if (epoch == searchEpoch) callback.failed(reason); }
        });
    }

    @Override public void plan(List<Request> requests, Result<PlanView> callback) {
        Index source = shown;
        CatalogModel model = catalog;
        Inventory here = inventory;
        List<Request> wanted = new ArrayList<>(requests);
        // A finished run's result belongs to that run: a new plan starts from no queue, so its screen can't show the old outcome.
        if (queue != null && queue.finished) { queue = null; render(); }
        if (source == null || model == null) { callback.failed("Wave is still loading the sources. Try again in a moment."); return; }
        boolean rootGranted = root == Root.OK;
        request(worker, () -> makePlan(wanted, source, model, here, rootGranted), new Result<PlanView>() {
            @Override public void ok(PlanView value) { proposed = (Pending) value.token; callback.ok(value); }
            @Override public void failed(String reason) { callback.failed(reason); }
        });
    }

    private PlanView makePlan(List<Request> asked, Index index, CatalogModel model, Inventory here,
                              boolean rootGranted) throws Plan.Refused {
        Map<String, Plan.Package> offerings = new LinkedHashMap<>();
        for (Index.Entry entry : index.all()) {
            if (Compat.of(entry, compatibility).fits) {
                Plan.Package candidate = Packages.of(entry.manifest);
                candidate.source = entry.source.url;
                // A private source's sign-in, for its downloads only (never shown or kept).
                SourceStore sources = store;
                candidate.credential = sources == null ? null : sources.credential(entry.source.id);
                offerings.put(candidate.id, candidate);
            }
        }
        List<Plan.Request> requests = new ArrayList<>();
        for (Request ask : asked) {
            String id = ask.id;
            Action action = ask.action;
            Index.Entry entry = index.get(id);
            if (action != Action.REMOVE && entry != null) {
                Compat c = Compat.of(entry, compatibility);
                if (!c.fits) throw new Plan.Refused(entry.manifest.name + ": " + c.reason);
            }
            requests.add(new Plan.Request(action == Action.REMOVE ? Plan.Op.REMOVE :
                action == Action.REINSTALL ? Plan.Op.REINSTALL : Plan.Op.INSTALL, id));
        }
        Map<String, Plan.Installed> installed = new LinkedHashMap<>(here.apps);
        for (Modules.Module mod : here.modules.values()) {
            Index.Entry chosen = index.get(mod.id);
            if (installed.containsKey(mod.id) && chosen != null && "apk".equals(chosen.manifest.type)) continue;
            installed.put(mod.id, new Plan.Installed(mod.id, Plan.Kind.MODULE, mod.version, mod.state == Modules.State.PENDING_REMOVAL));
        }
        Plan plan = Plan.make(requests, offerings, installed, Phone.device(), context.getPackageName());
        if (plan.needsReboot() && !rootGranted) throw new Plan.Refused("System mods need root access in Wave.");
        List<PlanItem> items = new ArrayList<>();
        long bytes = 0;
        for (Plan.Step step : plan.steps) {
            if (step.pkg != null) {
                bytes = step.pkg.size > Long.MAX_VALUE - bytes ? Long.MAX_VALUE : bytes + step.pkg.size;
            }
            items.add(new PlanItem(packageFor(model, step), step.why, action(step.op)));
        }
        return new PlanView(items, bytes, plan.needsReboot(), null, new Pending(plan, index));
    }

    @Override public void confirm(PlanView preview) {
        Pending pending = proposed;
        proposed = null;
        if (closed || preview == null || pending == null || pending != preview.token || shown != pending.index) {
            actionError = "The package list changed. Review the plan again.";
        } else if (pending.plan.needsReboot() && root != Root.OK) {
            actionError = "System mods need root access in Wave.";
        } else if (!EngineService.start(context, pending.plan, null)) {
            actionError = "Wave is already installing other changes.";
        } else {
            actionError = null;
            List<QueueItem> waiting = new ArrayList<>();
            for (PlanItem item : preview.items) waiting.add(new QueueItem(item.pkg, Step.WAITING, -1, 0, 0, null, null));
            queue = new QueueView(waiting, false, false);
        }
        render();
    }

    private static final class Pending {
        final Plan plan;
        final Index index;
        Pending(Plan plan, Index index) { this.plan = plan; this.index = index; }
    }

    @Override public void cancel() { EngineService.stop(); }
    @Override public void reboot() { rootAction(Scripts.reboot()); }
    @Override public void turnOn(String moduleId) {
        if (inventory.modules.containsKey(moduleId)) rootAction(Scripts.enableModule(moduleId));
    }
    @Override public void ackTurnedOff(String moduleId) {
        if (!inventory.turnedOff.stream().anyMatch(item -> item.id.equals(moduleId)) || !acknowledged.add(moduleId)) return;
        if (inventory.turnedOff.stream().allMatch(item -> acknowledged.contains(item.id))) rootAction(Watchdog.acknowledge());
        render();
    }

    private void rootAction(String script) {
        request(worker, () -> {
            if (!root(script).ok()) throw new IOException("That didn't work");
            readPhone(false, false, false);
            return null;
        }, new Result<Void>() {
            @Override public void ok(Void value) { actionError = null; render(); }
            @Override public void failed(String reason) { actionError = reason; render(); }
        });
    }

    private void engineChanged(Engine engine) {
        if (closed) return;
        List<QueueItem> rows = new ArrayList<>();
        for (Engine.Item item : engine.items()) {
            Engine.Phase phase = item.phase;
            Step step = phase == Engine.Phase.PREPARING ? Step.WAITING : Step.valueOf(phase.name());
            long size = item.step.pkg == null ? 0 : item.step.pkg.size;
            long bytes = item.percent < 0 ? 0 : (long) (size * (item.percent / 100.0));
            rows.add(new QueueItem(packageFor(catalog, item.step), step, item.percent, bytes, size, item.message,
                step == Step.FAILED ? item.message : null));
        }
        queue = new QueueView(rows, engine.finished(), engine.rebootNeeded());
        render();
        if (engine.finished()) recheckPhone();
    }

    private static Pkg packageFor(CatalogModel model, Plan.Step step) {
        Pkg pkg = model == null ? null : model.get(step.id);
        if (pkg != null) return pkg;
        String version = step.pkg == null ? null : step.pkg.version;
        return new Pkg(step.id, step.name, "", null, version, null, State.NONE, null, false,
            step.kind == Plan.Kind.MODULE, null, null, "", "");
    }

    private static Action action(Plan.Op op) {
        return op == Plan.Op.INSTALL ? Action.GET : Action.valueOf(op.name());
    }

    private SourceStore sources() throws IOException {
        if (store == null) throw new IOException("Wave couldn't read its list of sources.");
        return store;
    }

    private Shell.Result root(String script) throws IOException { return RootShell.run(context, script, Engine.QUICK_MS); }
    private interface Work<T> { T run() throws IOException, Plan.Refused; }
    private <T> void request(ExecutorService executor, Work<T> work, Result<T> callback) {
        submit(executor, () -> {
            try { T value = work.run(); post(() -> callback.ok(value)); }
            catch (IOException | Plan.Refused e) { post(() -> callback.failed(message(e))); }
            catch (RuntimeException e) { post(() -> callback.failed("An unknown error occurred.")); }
        });
    }
    private void submit(ExecutorService executor, Runnable task) {
        if (closed) return;
        try { executor.execute(task); }
        catch (RejectedExecutionException e) { if (!closed) throw e; }
    }
    /** Core's source failures are written for the screen (SourceFailure, Plan.Refused): shown as they are. */
    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isEmpty() ? "An unknown error occurred." : e.getMessage();
    }
    private void post(Runnable update) { main.post(() -> { if (!closed) update.run(); }); }

    @Override public void close() {
        closed = true;
        EngineService.unlisten(engineListener);
        listeners.clear();
        worker.shutdownNow(); network.shutdownNow(); searches.shutdownNow();
    }
}
