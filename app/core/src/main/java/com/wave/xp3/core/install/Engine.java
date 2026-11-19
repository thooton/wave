package com.wave.xp3.core.install;

import com.wave.xp3.core.root.Facts;
import com.wave.xp3.core.root.PmResult;
import com.wave.xp3.core.root.Scripts;
import com.wave.xp3.core.root.Shell;
import com.wave.xp3.core.root.Watchdog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Carries out a {@link Plan} through root, one step at a time, and says how
 * it is going (design section 4, "Doing things"). Plain Java: the phone
 * comes in through {@link Shell} (the `su` shell) and {@link Platform}
 * (what only Android can read), so the whole state machine runs on a
 * desktop JVM against a fake `su`.
 *
 * For each step: download to the cache, check size and SHA-256, check the
 * file is what the manifest says, install or remove through root, record
 * the receipt, delete the download. A module also goes on the boot
 * watchdog's pending list, and before the first module the watchdog's own
 * scripts are put in place if the phone's are missing or older. Wave's own
 * update is always the last step and runs detached (Android stops Wave to
 * replace it). The first failure stops the run: what is left is reported as
 * not done, since later steps may depend on the failed one.
 */
public final class Engine {

    /** What only Android knows. */
    public interface Platform {
        /** Where downloads go (cache/downloads); emptied after every run. */
        File downloads();

        /** What the platform reads from an APK, against the phone's copy. */
        Apk read(File apk, String packageName);

        /** Wave's own launcher activity, to start again after updating itself. */
        String selfActivity();
    }

    /**
     * How apps are installed when not through root: on a phone without it,
     * the app hands them to Android's (or the Updater's) installer and waits
     * for its answer. Modules always need root.
     */
    public interface Apps {
        /**
         * Installs `apk` and returns the answer; blocks until there is one.
         * For Wave itself (`self`) it may never return, as Android stops
         * Wave to replace it.
         */
        PmResult install(File apk, String packageName, boolean self) throws IOException;

        PmResult uninstall(String packageName) throws IOException;
    }

    /** An APK as the platform read it. */
    public static final class Apk {
        /** Null when the file couldn't be read as an app. */
        public final String packageName;
        public final String versionName;
        public final long versionCode;
        /** The phone's copy's versionCode, -1 when not installed. */
        public final long phoneCode;
        public final String phoneVersion;
        /** Signed with the same key as the phone's copy. */
        public final boolean sameKey;

        public Apk(String packageName, String versionName, long versionCode, long phoneCode, String phoneVersion,
                boolean sameKey) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.phoneCode = phoneCode;
            this.phoneVersion = phoneVersion;
            this.sameKey = sameKey;
        }
    }

    public enum Phase { WAITING, PREPARING, DOWNLOADING, CHECKING, INSTALLING, REMOVING, DONE, FAILED, SKIPPED }

    /** Where one step is. */
    public static final class Item {
        public final Plan.Step step;
        public volatile Phase phase = Phase.WAITING;
        /** 0-100 while downloading, else -1. */
        public volatile int percent = -1;
        /** Why it failed or was skipped; for a step done, a note or null. */
        public volatile String message;

        Item(Plan.Step step) {
            this.step = step;
        }

        @Override
        public String toString() {
            return step.op + " " + step.id + ": " + phase + (message != null ? " (" + message + ")" : "");
        }
    }

    public interface Listener {
        /** Something changed; called on the engine's thread. */
        void changed(Engine engine);
    }

    public static final long QUICK_MS = 30000;
    public static final long INSTALL_MS = 5 * 60000;

    private final Shell shell;
    private final Platform platform;
    private final Receipts receipts;
    private final Listener listener;
    private final String source;
    private final String self;
    private final List<Item> items = new ArrayList<>();
    private Apps apps;
    private volatile boolean stop;
    private volatile boolean finished;
    private volatile boolean rebootNeeded;
    private volatile boolean selfUpdating;
    private volatile String watchdogNote;

    /**
     * `source` is recorded in the receipts of packages whose own
     * {@link Plan.Package#source} is null (null when unknown); `self` is
     * Wave's own package name.
     */
    public Engine(Plan plan, Shell shell, Platform platform, Receipts receipts, Listener listener, String source,
            String self) {
        this.shell = shell;
        this.platform = platform;
        this.receipts = receipts;
        this.listener = listener;
        this.source = source;
        this.self = self;
        for (Plan.Step step : plan.steps) items.add(new Item(step));
    }

    /**
     * Installs and removes apps through `apps` instead of root; null (the
     * default) uses root. Set before {@link #run}.
     */
    public Engine apps(Apps apps) {
        this.apps = apps;
        return this;
    }

    public List<Item> items() {
        return Collections.unmodifiableList(items);
    }

    /** Stops after the current download or script. */
    public void stop() {
        stop = true;
    }

    public boolean finished() {
        return finished;
    }

    /** A module changed: it takes effect at the next boot. */
    public boolean rebootNeeded() {
        return rebootNeeded;
    }

    /** Wave handed itself to a detached shell and is about to be stopped. */
    public boolean selfUpdating() {
        return selfUpdating;
    }

    public boolean succeeded() {
        for (Item i : items) if (i.phase != Phase.DONE) return false;
        return true;
    }

    /** Runs the whole plan; returns when it is done or failed. Call on a worker. */
    public void run() {
        try {
            Item failed = prepareModules();
            if (failed != null) {
                skipRest("Not done, because " + failed.step.name + " failed.");
            } else {
                for (Item item : items) {
                    if (stop) {
                        skipRest("Stopped.");
                        break;
                    }
                    if (!step(item)) {
                        skipRest("Not done, because " + item.step.name + " failed.");
                        break;
                    }
                }
            }
        } finally {
            clean();
            finished = true;
            changed();
        }
    }

    /**
     * Before any module: Magisk must be there, and the boot watchdog in place
     * (installed or brought up to this app's version). Without the watchdog
     * no module is installed.
     */
    private Item prepareModules() {
        boolean modules = false;
        for (Item i : items) {
            if (i.step.kind == Plan.Kind.MODULE && i.step.op != Plan.Op.REMOVE) modules = true;
        }
        if (!modules) return null;
        Item first = null;
        for (Item i : items) {
            if (i.step.kind == Plan.Kind.MODULE) {
                first = i;
                break;
            }
        }
        first.phase = Phase.PREPARING;
        changed();
        try {
            Facts facts = Facts.parse(shell.run(Scripts.facts(), QUICK_MS).output);
            if (!facts.hasMagisk()) {
                fail(first, "Magisk isn't installed, and modules need it.");
                return first;
            }
            if (Watchdog.needsInstall(facts)) {
                Shell.Result r = shell.run(Watchdog.install(), QUICK_MS);
                if (!r.ok()) {
                    fail(first, "Couldn't set up the boot watchdog, which keeps a mod that stops the phone starting from staying on.");
                    return first;
                }
                watchdogNote = "Boot watchdog " + Watchdog.version() + " installed.";
            }
            first.phase = Phase.WAITING;
            return null;
        } catch (IOException e) {
            fail(first, message(e));
            return first;
        }
    }

    private boolean fail(Item item, String why) {
        item.phase = Phase.FAILED;
        item.message = why;
        item.percent = -1;
        changed();
        return false;
    }

    private void skipRest(String why) {
        for (Item i : items) {
            if (i.phase == Phase.WAITING || i.phase == Phase.PREPARING) {
                i.phase = Phase.SKIPPED;
                i.message = why;
            }
        }
        changed();
    }

    private boolean step(Item item) {
        Plan.Step s = item.step;
        try {
            if (s.op == Plan.Op.REMOVE) return remove(item);
            return install(item);
        } catch (Download.Stopped e) {
            stop = true;
            return fail(item, "Stopped.");
        } catch (IOException e) {
            return fail(item, message(e));
        }
    }

    private boolean remove(Item item) throws IOException {
        Plan.Step s = item.step;
        item.phase = Phase.REMOVING;
        changed();
        if (s.kind == Plan.Kind.APK) {
            PmResult pm;
            if (apps != null) {
                pm = apps.uninstall(s.id);
            } else {
                Shell.Result r = shell.run(Scripts.uninstallApk(s.id), INSTALL_MS);
                pm = PmResult.parse(r.output, r.exit);
            }
            if (!pm.ok) return fail(item, pm.message);
        } else {
            Shell.Result r = shell.run(Scripts.removeModule(s.id), QUICK_MS);
            if (!r.ok()) return fail(item, "Couldn't mark it for removal.");
            rebootNeeded = true;
            item.message = "Removed when the phone restarts.";
        }
        try {
            receipts.forget(s.id);
        } catch (IOException e) {
            item.message = "Removed, but Wave couldn't update its list: " + message(e);
        }
        item.phase = Phase.DONE;
        changed();
        return true;
    }

    private boolean install(final Item item) throws IOException {
        Plan.Step s = item.step;
        Plan.Package p = s.pkg;
        String extension = s.kind == Plan.Kind.APK ? ".apk" : ".zip";
        File file = new File(platform.downloads(), safe(s.id) + "-" + safe(p.version) + extension);
        try {
            item.phase = Phase.DOWNLOADING;
            item.percent = 0;
            changed();
            Download.fetch(p.url, p.size, p.sha256, file, (done, total) -> {
                int percent = total > 0 ? (int) (done * 100 / total) : -1;
                if (percent != item.percent) {
                    item.percent = percent;
                    changed();
                }
            }, () -> stop, p.credential);
            item.phase = Phase.CHECKING;
            item.percent = -1;
            changed();
            String problem;
            if (s.kind == Plan.Kind.APK) {
                Apk apk = platform.read(file, s.id);
                problem = Verify.apk(s.id, p.version, apk.packageName, apk.versionName, apk.versionCode,
                    apk.phoneCode, apk.phoneVersion, apk.sameKey);
            } else {
                problem = Verify.module(file, s.id, p.version);
            }
            if (problem != null) return fail(item, problem);
            if (stop) throw new Download.Stopped();

            item.phase = Phase.INSTALLING;
            changed();
            Receipts.Receipt receipt = new Receipts.Receipt(s.id, s.kind, p.version,
                p.source != null ? p.source : source, p.sha256, System.currentTimeMillis());
            if (s.kind == Plan.Kind.APK && s.id.equals(self)) {
                // Android stops Wave part way through, before it could note
                // the update afterwards; so it is noted first, and put back
                // if the update can't even start.
                Receipts.Receipt before = receipts.get(s.id);
                note(item, receipt);
                selfUpdating = true;
                item.message = "Wave closes to update itself.";
                changed();
                String failed;
                if (apps != null) {
                    PmResult pm = apps.install(file, s.id, true);
                    failed = pm.ok ? null : pm.message;
                } else {
                    Shell.Result r = shell.run(Scripts.selfUpdate(file.getAbsolutePath(), file.length(), self,
                        platform.selfActivity()), QUICK_MS);
                    failed = r.ok() ? null : "Couldn't start updating Wave.";
                }
                if (failed != null) {
                    selfUpdating = false;
                    unnote(s.id, before);
                    return fail(item, failed);
                }
            } else if (s.kind == Plan.Kind.APK) {
                PmResult pm;
                if (apps != null) {
                    pm = apps.install(file, s.id, false);
                } else {
                    Shell.Result r = shell.run(Scripts.installApk(file.getAbsolutePath(), file.length()), INSTALL_MS);
                    pm = PmResult.parse(r.output, r.exit);
                }
                if (!pm.ok) return fail(item, pm.message);
                // Noted before compiling, which can take minutes: a run cut
                // off then still knows where the app came from.
                note(item, receipt);
                if (apps == null) {
                    // Ahead-of-time compiling only makes it start faster; a failure is not the install's.
                    try {
                        shell.run(Scripts.compile(s.id), INSTALL_MS);
                    } catch (IOException ignored) {
                        // Left to Android's own background compile.
                    }
                }
            } else {
                Shell.Result r = shell.run(Scripts.installModule(file.getAbsolutePath(), s.id), INSTALL_MS);
                if (!r.ok()) return fail(item, moduleFailure(r.output));
                rebootNeeded = true;
                item.message = "Takes effect when the phone restarts.";
                note(item, receipt);
            }
            item.phase = Phase.DONE;
            changed();
            return true;
        } finally {
            // Wave's own update copied it for the detached shell already.
            file.delete();
        }
    }

    /**
     * Records what was installed. The install itself has happened by now, so
     * a full disk here doesn't fail the step; it says so instead.
     */
    private void note(Item item, Receipts.Receipt receipt) {
        try {
            receipts.record(receipt);
        } catch (IOException e) {
            item.message = "Installed, but Wave couldn't note it: " + message(e);
        }
    }

    private void unnote(String id, Receipts.Receipt before) {
        try {
            if (before != null) receipts.record(before);
            else receipts.forget(id);
        } catch (IOException ignored) {
            // The list is only wrong about where Wave came from.
        }
    }

    /** Magisk's installer prints its errors as lines starting with "! ". */
    static String moduleFailure(String output) {
        String found = null;
        for (String line : output.split("\n")) {
            if (line.startsWith("! ")) {
                found = line.substring(2).trim();
                break;
            }
        }
        // Anything else it printed is for a shell, not a person.
        return found == null || found.isEmpty() ? "Magisk couldn't install it." : "Magisk couldn't install it: " + found;
    }

    /** Leaves no download behind (Wave's own update has its own copy by now). */
    private void clean() {
        File[] files = platform.downloads().listFiles();
        if (files == null) return;
        for (File f : files) f.delete();
    }

    public String watchdogNote() {
        return watchdogNote;
    }

    private void changed() {
        if (listener != null) listener.changed(this);
    }

    private static String safe(String text) {
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String message(IOException e) {
        return e.getMessage() != null && !e.getMessage().trim().isEmpty() ? e.getMessage() : "Something went wrong.";
    }
}
