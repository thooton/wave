package com.wave.xp3.app;

import android.os.Handler;
import android.os.Looper;

import com.wave.xp3.data.WaveData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Debug builds only: the mockups' sample data (site/mockups/frames.js) behind
 * WaveData, so every screen can be walked on a phone without root, network,
 * or changing anything on it. Started with
 * `am start -n com.wave.xp3/.app.WaveActivity --es fixture <name>`:
 *
 * - browse: sources, sections, Featured, two updates, no reboot pending.
 * - reboot: as browse, with Messages Improved waiting for a reboot.
 * - errors: a source that won't refresh, and Launcher Improved turned off.
 * - offline: nothing fetched and no network.
 * - uptodate: as browse, with no updates.
 * - big: as browse, plus Big Source with 500 packages (long lists).
 * - noroot: as browse, without root.
 * Getting Mail always fails its check, to show the queue's alert.
 *
 * Plans come back as the mockups' (a dependency, a replaced package); Confirm
 * runs a pretend queue that changes nothing; Reboot only says so.
 */
public final class FakeWaveData implements WaveData {

    private final String fixture;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();
    private final Map<String, Pkg> pkgs = new LinkedHashMap<>();
    private final Map<String, String> descriptions = new HashMap<>();
    private Root root = Root.OK;
    private QueueView queue;
    private String actionError;
    private boolean refreshing;
    private final List<TurnedOff> turnedOff = new ArrayList<>();
    private final List<String> rebootPending = new ArrayList<>();

    public FakeWaveData(String fixture) {
        this.fixture = fixture;
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
        app("com.authenticator.xp3", "Authenticator", "Two-factor codes, as Authy shows them", "1.6.0", null, State.INSTALLED, "applications");
        app("com.books.xp3", "Books", "EPUBs and audiobooks from your library", "3.2.1", null, State.INSTALLED, "applications");
        app("com.canvas.xp3", "Canvas", "Your school week, due dates and grades", "2.0.3", null, State.NONE, "applications");
        boolean current = "uptodate".equals(fixture);
        put(new Pkg("com.teslacharge.xp3", "Charge", "Your Tesla's charge, climate and location", null, "2.4.0", current ? "2.4.0" : "2.3.1",
            current ? State.INSTALLED : State.UPDATE,
            null, false, false, new Change("2.4.0", today, "Set the charge limit from the climate page; show the time to full while charging."),
            "wave", "Wave", "Applications"));
        app("com.dictionary.xp3", "Dictionary", "Definitions, thesaurus and shorthand, offline", "1.9.2", null, State.INSTALLED, "applications");
        app("com.flashcards.xp3", "Flashcards", "Study your Anki decks", "1.3.0", null, State.INSTALLED, "applications");
        app("com.list.xp3", "List", "Lists and reminders that sync", "2.2.0", null, State.INSTALLED, "applications");
        app("com.mail.xp3", "Mail", "Gmail on the keypad, with threads", "1.8.0", null, State.NONE, "applications");
        app("com.musica.xp3", "Música", "Find, download and play music", "1.5.4", null, State.NONE, "applications");
        app("com.newyorker.xp3", "New Yorker", "Today's stories and the magazine", "1.1.0", null, State.INSTALLED, "applications");
        app("com.places.xp3", "Places", "Nearby places, hours and phone numbers", "1.4.1", null, State.INSTALLED, "applications");
        app("com.wave.xp3", "Wave", "Apps and system mods for this phone", "0.5.0", null, State.INSTALLED, "applications");
        boolean reboot = "reboot".equals(fixture);
        put(new Pkg("messages_improved", "Messages Improved", "Group MMS in the right thread, faster receiving", null, "1.2.0",
            reboot ? "1.2.0" : null, reboot ? State.PENDING_INSTALL : State.NONE, null, false, true,
            new Change("1.2.0", "2026-11-18", "Prefetch recent messages when the inbox is opened."), "wave", "Wave", "System"));
        boolean errors = "errors".equals(fixture);
        put(new Pkg("launcher_improved", "Launcher Improved", "The home screen with an app drawer and a tray", null, "2.0.1", "2.0.1",
            errors ? State.OFF : State.INSTALLED, null, false, true, null, "wave", "Wave", "System"));
        put(new Pkg("music_improved", "Music Improved", "The stock Music app, without its crashes", null, "1.0.4", current ? "1.0.4" : "1.0.2",
            current ? State.INSTALLED : State.UPDATE,
            null, false, true, new Change("1.0.4", "2026-11-16", "Keep the queue when the phone restarts."), "wave", "Wave", "System"));
        put(new Pkg("com.mkline.snake", "Snake 97", "The snake game, as the old Nokias had it", null, "1.1.0", null, State.NONE,
            null, false, false, null, "games", "XP3 Games", "Games"));
        put(new Pkg("com.mkline.tetrim", "Tetrim", "Falling blocks for the d-pad", null, "0.4.0", null, State.NONE,
            "Needs Android 9; this phone has 8.1.", false, false, null, "games", "XP3 Games", "Games"));
        put(new Pkg("night_theme", "Night Theme", "Dark system colours", null, "0.2.0", null, State.NONE,
            null, true, true, null, "tweaks", "Sonim Tweaks", "Themes"));
        descriptions.put("com.mail.xp3", "Gmail on the keypad: threads, search, replies and attachments you can open. Bring your own Gmail token with a setup code.");
        descriptions.put("messages_improved", "The stock Messages app and its provider, fixed: group MMS land in the right thread.");
        if (reboot) rebootPending.add("Messages Improved");
        if (errors) turnedOff.add(new TurnedOff("launcher_improved", "Launcher Improved", "2.0.1", null));
        if ("noroot".equals(fixture)) root = Root.NONE;
        if ("big".equals(fixture)) bulk();
    }

    /** The big fixture: a source of 500 packages in five sections, 40 of them installed and 10 with updates. */
    private void bulk() {
        String[] sections = {"Games", "Media", "Office", "Tools", "Travel"};
        String[] words = {"Atlas", "Beacon", "Cinder", "Delta", "Ember", "Fable", "Garnet", "Harbor", "Iris", "Juniper"};
        for (int i = 0; i < 500; i++) {
            String name = words[i % words.length] + " " + String.format(Locale.US, "%03d", i + 1);
            String section = sections[(i / 7) % sections.length];
            State state = i % 50 == 3 ? State.UPDATE : i % 25 == 7 ? State.INSTALLED : State.NONE;
            String installed = state == State.UPDATE ? "1.0.0" : state == State.INSTALLED ? "1.1.0" : null;
            Change change = state == State.UPDATE ? new Change("1.1.0", "2026-11-17", "Faster start, and the list keeps its place when it is refilled.") : null;
            put(new Pkg("org.bulk.p" + i, name, "Sample package " + (i + 1) + " of 500, for a long list", null, "1.1.0", installed, state,
                i % 61 == 11 ? "Needs Android 9; this phone has 8.1." : null, false, false, change, "bulk", "Big Source", section));
        }
    }

    private void app(String id, String name, String summary, String version, String installed, State state, String folder) {
        put(new Pkg(id, name, summary, null, version, installed, state, null, false, false, null, "wave", "Wave", "Applications"));
    }

    private void put(Pkg p) {
        pkgs.put(p.id, p);
    }

    private List<Pkg> where(String sourceId, String section) {
        List<Pkg> out = new ArrayList<>();
        for (Pkg p : pkgs.values()) {
            if (sourceId != null && !sourceId.equals(p.sourceId)) continue;
            if (section != null && !section.equals(p.sectionName)) continue;
            out.add(p);
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private Shelf shelf(String sourceId, List<Pkg> featured) {
        Map<String, List<Pkg>> bySection = new LinkedHashMap<>();
        List<Section> sections = new ArrayList<>();
        for (Pkg p : where(sourceId, null)) {
            String folder = p.sectionName.toLowerCase(Locale.US);
            if (!bySection.containsKey(folder)) bySection.put(folder, new ArrayList<>());
            bySection.get(folder).add(p);
        }
        List<String> folders = new ArrayList<>(bySection.keySet());
        Collections.sort(folders);
        for (String f : folders) {
            List<Pkg> list = bySection.get(f);
            sections.add(new Section(f, list.get(0).sectionName, list.size()));
        }
        Map<String, Detail> details = new HashMap<>();
        for (Pkg p : where(sourceId, null)) details.put(p.id, detail(p));
        return new Shelf(featured, where(sourceId, null), sections, bySection, details);
    }

    private Detail detail(Pkg p) {
        List<Change> changes = new ArrayList<>();
        if (p.newestChange != null) changes.add(p.newestChange);
        changes.add(new Change(p.installedVersion != null ? p.installedVersion : "1.0.0", "2026-11-02", "Keep the token after a reboot."));
        List<String> replaces = p.id.equals("messages_improved") ? Collections.singletonList("Messages (argon)") : Collections.<String>emptyList();
        List<String> depends = p.id.equals("com.mail.xp3") ? Collections.singletonList("Print 1.2 or later") : Collections.<String>emptyList();
        String description = descriptions.containsKey(p.id) ? descriptions.get(p.id) : p.summary + ".";
        long size = p.isModule ? 4100000 : 2300000;
        return new Detail(p, description, p.sourceId.equals("wave") ? "thooton" : "mkline", size, changes, depends,
            Collections.<String>emptyList(), replaces, p.id.equals("messages_improved"));
    }

    private Snapshot cached;

    @Override
    public Snapshot snapshot() {
        if (cached == null) cached = build();
        return cached;
    }

    private Snapshot build() {
        boolean offline = "offline".equals(fixture);
        List<Src> sources = new ArrayList<>();
        long now = System.currentTimeMillis();
        sources.add(new Src("wave", "Wave", "https://github.com/thooton/wave_repository", null, true, "thooton",
            offline ? 0 : now, offline ? 0 : 17, refreshing, null, false));
        if (!offline) {
            sources.add(new Src("games", "XP3 Games", "https://github.com/mkline/xp3-games", null, false, "mkline", now, 2, refreshing, null, false));
            sources.add(new Src("tweaks", "Sonim Tweaks", "https://gitlab.com/rpeters/sonim-tweaks", null, false, "rpeters", now - 86400000L * 3, 1,
                refreshing, "errors".equals(fixture) ? "gitlab.com answered 404" : null, false));
        }
        if ("big".equals(fixture)) {
            sources.add(new Src("bulk", "Big Source", "https://github.com/example/big-source", null, false, "example", now, 500, refreshing, null, false));
        }
        Map<String, Shelf> bySource = new LinkedHashMap<>();
        List<Pkg> featured = Arrays.asList(pkgs.get("messages_improved"), pkgs.get("com.mail.xp3"));
        if (!offline) {
            bySource.put("wave", shelf("wave", featured));
            bySource.put("games", shelf("games", Collections.<Pkg>emptyList()));
            bySource.put("tweaks", shelf("tweaks", Collections.<Pkg>emptyList()));
            if ("big".equals(fixture)) bySource.put("bulk", shelf("bulk", Collections.<Pkg>emptyList()));
        }
        Shelf all = offline ? Shelf.empty() : shelf(null, Collections.<Pkg>emptyList());
        List<Pkg> updates = new ArrayList<>();
        List<Pkg> upToDate = new ArrayList<>();
        for (Pkg p : where(null, null)) {
            if (p.state == State.UPDATE) updates.add(p);
            else if (p.state != State.NONE) upToDate.add(p);
        }
        Facts facts = new Facts("XP3800", "8.1", "8A.0.5-11", root == Root.OK ? "Magisk 24.0" : "Not granted", "1.3 GB free");
        return new Snapshot(root, offline ? "No network." : null, actionError, sources, all, bySource, updates, upToDate,
            rebootPending, turnedOff, facts, queue);
    }

    private void changed() {
        cached = null;
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    @Override public void listen(Runnable onChanged) { listeners.add(onChanged); }
    @Override public void unlisten(Runnable onChanged) { listeners.remove(onChanged); }

    @Override
    public void checkRoot() {
        root = Root.CHECKING;
        changed();
        main.postDelayed(() -> {
            root = Root.OK;
            changed();
        }, 1200);
    }

    @Override public void recheckPhone() {}

    @Override
    public void refreshAll() {
        refreshing = true;
        changed();
        main.postDelayed(() -> {
            refreshing = false;
            if ("offline".equals(fixture) || "errors".equals(fixture)) actionError = "Couldn't refresh: No network.";
            changed();
            actionError = null;
            cached = null;
        }, 2000);
    }

    @Override public void refresh(String sourceId) { refreshAll(); }

    @Override
    public void preview(String url, Result<Preview> callback) {
        main.postDelayed(() -> {
            if (url.contains("games")) callback.ok(new Preview("XP3 Games", "mkline", 12, null, url));
            else callback.failed("github.com answered 404: no repository here.");
        }, 1500);
    }

    @Override public void add(Preview preview, Result<Void> callback) { main.post(() -> callback.ok(null)); }
    @Override public void remove(String sourceId, Result<Void> callback) { main.post(() -> callback.ok(null)); }

    @Override
    public void search(String query, Result<List<Pkg>> callback) {
        List<Pkg> found = new ArrayList<>();
        String q = query.toLowerCase(Locale.US);
        for (Pkg p : where(null, null)) {
            if (p.name.toLowerCase(Locale.US).contains(q) || p.summary.toLowerCase(Locale.US).contains(q)) found.add(p);
        }
        main.post(() -> callback.ok(found));
    }

    @Override
    public void plan(List<String> ids, Action action, Result<PlanView> callback) {
        List<PlanItem> items = new ArrayList<>();
        long bytes = 0;
        boolean reboot = false;
        for (String id : ids) {
            Pkg p = pkgs.get(id);
            if (p == null) continue;
            if (id.equals("messages_improved") && action == Action.GET) {
                Pkg sqlite = new Pkg("sqlite_upgrade", "SQLite Upgrade", "A newer SQLite for the system", null, "3.45.1", null, State.NONE,
                    null, false, true, null, "wave", "Wave", "System");
                items.add(new PlanItem(sqlite, "Needed by Messages Improved", Action.GET));
                items.add(new PlanItem(p, null, Action.GET));
                items.add(new PlanItem(new Pkg("messages_argon", "Messages (argon)", "", null, "0.9.3", "0.9.3", State.INSTALLED,
                    null, false, true, null, "wave", "Wave", "System"), "Replaced by Messages Improved", Action.REMOVE));
                bytes += 5000000;
            } else {
                items.add(new PlanItem(p, null, action));
                if (action != Action.REMOVE) bytes += p.isModule ? 4100000 : 2300000;
            }
            reboot |= p.isModule;
        }
        final PlanView plan = new PlanView(items, bytes, reboot, null);
        main.post(() -> callback.ok(plan));
    }

    @Override
    public void confirm(PlanView plan) {
        final List<PlanItem> items = plan.items;
        final boolean reboot = plan.needsReboot;
        final int[] tick = {0};
        Runnable step = new Runnable() {
            @Override
            public void run() {
                int t = tick[0]++;
                List<QueueItem> rows = new ArrayList<>();
                boolean finished = true;
                for (int i = 0; i < items.size(); i++) {
                    PlanItem item = items.get(i);
                    int start = i * 6;
                    Step s;
                    int pct = -1;
                    String text = null;
                    if (t < start) {
                        s = Step.WAITING;
                        text = item.action == Action.REMOVE ? "To remove" : "Waiting";
                        finished = false;
                    } else if (t < start + 4 && item.action != Action.REMOVE) {
                        s = Step.DOWNLOADING;
                        pct = (t - start) * 25 + 10;
                        finished = false;
                    } else if (t < start + 6) {
                        s = item.action == Action.REMOVE ? Step.REMOVING : Step.INSTALLING;
                        finished = false;
                    } else if (item.pkg.id.equals("com.mail.xp3")) {
                        s = Step.FAILED;
                        text = "Not installed";
                    } else {
                        s = Step.DONE;
                        text = item.action == Action.REMOVE ? "Removed" : item.action == Action.UPDATE ? "Updated to " + item.pkg.version
                            : "Installed " + item.pkg.version;
                    }
                    rows.add(new QueueItem(item.pkg, s, pct, pct < 0 ? 0 : pct * 41000L, 4100000, text,
                        s == Step.FAILED ? "The download doesn't match the source. It was deleted; nothing on the phone changed." : null));
                }
                queue = new QueueView(rows, finished, finished && reboot);
                changed();
                if (!finished) main.postDelayed(this, 700);
            }
        };
        main.post(step);
    }

    @Override
    public void cancel() {
        queue = null;
        changed();
    }

    @Override
    public void reboot() {
        actionError = "Fixture: would reboot now.";
        changed();
        actionError = null;
        cached = null;
    }

    @Override public void turnOn(String moduleId) {}

    @Override
    public void ackTurnedOff(String moduleId) {
        cached = null;
        for (int i = 0; i < turnedOff.size(); i++) if (turnedOff.get(i).id.equals(moduleId)) turnedOff.remove(i--);
    }

    @Override public void close() { main.removeCallbacksAndMessages(null); }
}
