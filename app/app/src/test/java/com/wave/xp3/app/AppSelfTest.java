package com.wave.xp3.app;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Change;
import com.wave.xp3.data.WaveData.Detail;
import com.wave.xp3.data.WaveData.PlanItem;
import com.wave.xp3.data.WaveData.PlanView;
import com.wave.xp3.data.WaveData.Pkg;
import com.wave.xp3.data.WaveData.QueueItem;
import com.wave.xp3.data.WaveData.QueueView;
import com.wave.xp3.data.WaveData.Snapshot;
import com.wave.xp3.data.WaveData.Src;
import com.wave.xp3.data.WaveData.State;
import com.xp3.common.Check;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Everything in :app:app that decides something without needing a View, on a
 * desktop JVM: what the rows and pages say (Pages, Format), what a package
 * offers and what pressing it does (Actions), how the phone and the sources
 * become rows (CatalogModel) and the snapshot's lookups (WaveData). Run with
 * {@code ./gradlew :app:app:selfTest}.
 */
public final class AppSelfTest {
    public static void main(String[] args) throws Exception {
        Check check = new Check();
        check.run("Sizes, dates and addresses", () -> formats(check));
        check.run("Capsule words and presses", () -> capsules(check));
        check.run("A package's menu", () -> menus(check));
        check.run("Installed rows and empty states", () -> installed(check));
        check.run("Sources and Wave tab text", () -> sourcesAndHome(check));
        check.run("Details page notes, byline and facts", () -> details(check));
        check.run("Confirm and queue text", () -> planAndQueue(check));
        check.run("Search states", () -> search(check));
        check.run("Snapshot lookups", () -> snapshots(check));
        com.wave.xp3.data.DataTests.run(check);
        if (!check.report()) System.exit(1);
    }

    // ---------------------------------------------------------------- builders

    static final Predicate<Pkg> OPENABLE = p -> true, NOT_OPENABLE = p -> false;

    static Pkg pkg(String id, State state) {
        return new Pkg(id, "Name " + id, "Summary of " + id, null, "2.0", state == State.NONE ? null : "1.0", state,
            null, false, false, null, "src", "Source", "Section");
    }

    /** `p` with some fields replaced; the rest is kept. */
    static class Edit {
        String id, name, summary, version, installed, unfit, source, sourceName, section;
        State state;
        boolean newer, module;
        Change change;

        Edit(Pkg p) {
            id = p.id; name = p.name; summary = p.summary; version = p.version; installed = p.installedVersion; unfit = p.unfitReason;
            source = p.sourceId; sourceName = p.sourceName; section = p.sectionName; state = p.state; newer = p.needsNewerWave;
            module = p.isModule; change = p.newestChange;
        }

        Pkg build() {
            return new Pkg(id, name, summary, null, version, installed, state, unfit, newer, module, change, source, sourceName, section);
        }
    }

    static Pkg unfit(Pkg p) {
        Edit e = new Edit(p);
        e.unfit = "Not compatible with this device";
        return e.build();
    }

    static Pkg needsNewerWave(Pkg p) {
        Edit e = new Edit(p);
        e.unfit = "Needs a newer Wave";
        e.newer = true;
        return e.build();
    }

    static Pkg module(Pkg p) {
        Edit e = new Edit(p);
        e.module = true;
        return e.build();
    }

    static Pkg kept(Pkg p) {
        Edit e = new Edit(p);
        e.source = null; e.sourceName = ""; e.section = ""; e.summary = "";
        return e.build();
    }

    static Detail detail(Pkg p) {
        return new Detail(p, "Describes " + p.id, "An Author", 48_213, Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), false);
    }

    static Detail detail(Pkg p, String description, String author, long size, List<String> depends, List<String> conflicts,
                         List<String> replaces, boolean firmware) {
        return new Detail(p, description, author, size, Collections.emptyList(), depends, conflicts, replaces, firmware);
    }

    static Src src(String id, boolean refreshing, String error, boolean newer, long refreshedAt, int count) {
        return new Src(id, "Source " + id, "https://example.org/" + id + ".git", null, false, "Maker", refreshedAt, count,
            refreshing, error, newer);
    }

    static Snapshot snapshot(String firstLoadError, List<Src> sources, List<Pkg> updates, List<Pkg> upToDate) {
        return new Snapshot(WaveData.Root.OK, firstLoadError, null, sources, WaveData.Shelf.empty(), Collections.emptyMap(),
            updates, upToDate, Collections.emptyList(), Collections.emptyList(), null, null);
    }

    static Snapshot snapshot(WaveData.Root root, WaveData.Facts facts) {
        return new Snapshot(root, null, null, Collections.emptyList(), WaveData.Shelf.empty(), Collections.emptyMap(),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), facts, null);
    }

    // ---------------------------------------------------------------- Format

    private static void formats(Check check) {
        check.same("no size", null, Format.size(0));
        check.same("negative size", null, Format.size(-5));
        check.same("under a kilobyte rounds up to 1", "1 KB", Format.size(1));
        check.same("kilobytes", "48 KB", Format.size(48_213));
        check.same("just under a megabyte", "1000 KB", Format.size(999_999));
        check.same("a megabyte", "1.0 MB", Format.size(1_000_000));
        check.same("megabytes", "4.1 MB", Format.size(4_107_233));
        check.same("progress megabytes", "0.5", Format.megabytes(500_000));

        Calendar now = Calendar.getInstance();
        now.clear();
        now.set(2026, Calendar.NOVEMBER, 19, 15, 30);
        check.same("today", "Today", Format.date("2026-11-19", now));
        check.same("a time of day on the same date is still today", "Today", Format.date("2026-11-19T01:00:00", now));
        check.same("yesterday", "Yesterday", Format.date("2026-11-18", now));
        check.same("older", "Nov 12, 2026", Format.date("2026-11-12", now));
        check.same("last year", "Dec 31, 2025", Format.date("2025-12-31", now));
        check.same("tomorrow is a date, not 'today'", "Nov 20, 2026", Format.date("2026-11-20", now));
        check.same("not a date: as it is", "soon", Format.date("soon", now));
        check.same("an impossible day: as it is", "2026-02-30", Format.date("2026-02-30", now));
        check.same("no date", null, Format.date(null, now));
        Calendar newYear = Calendar.getInstance();
        newYear.clear();
        newYear.set(2026, Calendar.JANUARY, 1, 9, 0);
        check.same("yesterday across a year", "Yesterday", Format.date("2025-12-31", newYear));

        check.same("never refreshed", null, Format.refreshed(0, now));
        check.same("refreshed today", "refreshed today", Format.refreshed(millis(2026, Calendar.NOVEMBER, 19, 1), now));
        check.same("refreshed yesterday", "refreshed yesterday", Format.refreshed(millis(2026, Calendar.NOVEMBER, 18, 23), now));
        check.same("refreshed earlier", "refreshed Nov 2", Format.refreshed(millis(2026, Calendar.NOVEMBER, 2, 12), now));

        check.same("scheme and .git dropped", "github.com/thooton/wave_repository", Format.shortUrl("https://github.com/thooton/wave_repository.git"));
        check.same("trailing slash", "example.org/src", Format.shortUrl("https://example.org/src/"));
        check.same("a source is kept apart from its .git twin only by the name", "example.org/src", Format.shortUrl("https://example.org/src.git"));
        check.same("no address", "", Format.shortUrl(null));
        check.same("not a URL", "plain", Format.shortUrl("plain"));
    }

    private static long millis(int year, int month, int day, int hour) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, hour, 0);
        return c.getTimeInMillis();
    }

    // ---------------------------------------------------------------- capsules and presses

    private static void capsules(Check check) {
        Pkg none = pkg("a", State.NONE), update = pkg("a", State.UPDATE), installed = pkg("a", State.INSTALLED);
        check.same("get", "GET", Actions.capsule(none, NOT_OPENABLE));
        check.same("update", "UPDATE", Actions.capsule(update, OPENABLE));
        check.same("installed and launchable", "OPEN", Actions.capsule(installed, OPENABLE));
        check.same("installed with no launcher (a mod, Wave)", null, Actions.capsule(installed, NOT_OPENABLE));
        check.same("newer than the source offers: open", "OPEN", Actions.capsule(pkg("a", State.NEWER), OPENABLE));
        for (State s : new State[]{State.OFF, State.PENDING_INSTALL, State.PENDING_REMOVAL}) {
            check.same(s + " has no capsule", null, Actions.capsule(pkg("a", s), OPENABLE));
        }
        check.same("not installed and unfit: no capsule", null, Actions.capsule(unfit(none), OPENABLE));
        check.same("not installed and needs a newer Wave: no capsule", null, Actions.capsule(needsNewerWave(none), OPENABLE));
        check.same("an update this phone can't take opens the app", "OPEN", Actions.capsule(unfit(update), OPENABLE));
        check.same("an update this phone can't take and can't open", null, Actions.capsule(unfit(update), NOT_OPENABLE));
        check.same("an update that needs a newer Wave is no update", "OPEN", Actions.capsule(needsNewerWave(update), OPENABLE));

        int[] asked = {0};
        Predicate<Pkg> counting = p -> { asked[0]++; return true; };
        Actions.capsule(none, counting);
        Actions.capsule(update, counting);
        check.same("the phone is not asked for packages it isn't needed for", 0, asked[0]);

        check.same("badge: update", 2, Actions.badge(update));
        check.same("badge: installed", 1, Actions.badge(installed));
        check.same("badge: newer", 1, Actions.badge(pkg("a", State.NEWER)));
        check.same("badge: off", 1, Actions.badge(pkg("a", State.OFF)));
        check.same("badge: waiting to install", 1, Actions.badge(pkg("a", State.PENDING_INSTALL)));
        check.same("badge: waiting to remove", 0, Actions.badge(pkg("a", State.PENDING_REMOVAL)));
        check.same("badge: none", 0, Actions.badge(none));

        check.same("press get", Actions.Press.GET, Actions.press(none, OPENABLE));
        check.same("press update", Actions.Press.UPDATE, Actions.press(update, OPENABLE));
        check.same("press an update that doesn't fit: open", Actions.Press.OPEN, Actions.press(unfit(update), OPENABLE));
        check.same("press an update that doesn't fit, no launcher: details", Actions.Press.DETAILS, Actions.press(unfit(update), NOT_OPENABLE));
        check.same("press installed", Actions.Press.OPEN, Actions.press(installed, OPENABLE));
        check.same("press installed mod: details", Actions.Press.DETAILS, Actions.press(module(installed), NOT_OPENABLE));
        check.same("press a get that doesn't fit: nothing", Actions.Press.NOTHING, Actions.press(unfit(none), OPENABLE));
        check.same("press off: details", Actions.Press.DETAILS, Actions.press(pkg("a", State.OFF), OPENABLE));
        check.same("press waiting: details", Actions.Press.DETAILS, Actions.press(pkg("a", State.PENDING_INSTALL), OPENABLE));

        check.same("installed row, update: press", Actions.Press.UPDATE, Actions.pressInstalledRow(update, OPENABLE));
        check.same("installed row, openable", Actions.Press.OPEN, Actions.pressInstalledRow(installed, OPENABLE));
        check.same("installed row, a mod: its details", Actions.Press.DETAILS, Actions.pressInstalledRow(installed, NOT_OPENABLE));
        check.same("installed row, turned off: its details", Actions.Press.DETAILS, Actions.pressInstalledRow(pkg("a", State.OFF), OPENABLE));

        check.same("details capsule: get even if it doesn't fit (it is greyed)", "GET", Actions.detailsCapsule(unfit(none), OPENABLE));
        check.same("details capsule: update", "UPDATE", Actions.detailsCapsule(update, OPENABLE));
        check.same("details capsule: open", "OPEN", Actions.detailsCapsule(installed, OPENABLE));
        check.same("details capsule: a mod has none", null, Actions.detailsCapsule(module(installed), NOT_OPENABLE));
        check.same("details capsule: off has none", null, Actions.detailsCapsule(pkg("a", State.OFF), OPENABLE));
        check.ok("greyed: get that doesn't fit", Actions.greyed(unfit(none)));
        check.ok("greyed: update that needs a newer Wave", Actions.greyed(needsNewerWave(update)));
        check.ok("not greyed: installed that doesn't fit", !Actions.greyed(unfit(installed)));
        check.ok("not greyed: a get that fits", !Actions.greyed(none));
        check.same("OK on a greyed page does nothing", Actions.Ok.NOTHING, Actions.detailsOk(unfit(none), OPENABLE));
        check.same("OK opens", Actions.Ok.OPEN, Actions.detailsOk(installed, OPENABLE));
        check.same("OK presses get", Actions.Ok.PRESS, Actions.detailsOk(none, OPENABLE));
        check.same("OK on an installed mod opens the menu", Actions.Ok.MENU, Actions.detailsOk(module(installed), NOT_OPENABLE));
        check.same("OK on a turned off mod opens the menu", Actions.Ok.MENU, Actions.detailsOk(pkg("a", State.OFF), OPENABLE));
    }

    // ---------------------------------------------------------------- menus

    private static List<String> titles(Pkg p, boolean details, Predicate<Pkg> open, boolean self, boolean waveUpdate) {
        List<String> titles = new ArrayList<>();
        for (Actions.Item item : Actions.menu(p, details, open, self, waveUpdate)) titles.add(item.title);
        return titles;
    }

    private static void menus(Check check) {
        Pkg none = pkg("a", State.NONE), update = pkg("a", State.UPDATE), installed = pkg("a", State.INSTALLED);
        check.same("not installed", Arrays.asList("Get", "Details"), titles(none, true, OPENABLE, false, false));
        check.same("not installed, details left to the page", Collections.singletonList("Get"), titles(none, false, OPENABLE, false, false));
        check.same("update", Arrays.asList("Update", "Details", "!Remove"), titles(update, true, OPENABLE, false, false));
        check.same("installed", Arrays.asList("Open", "Details", "Reinstall", "!Remove"), titles(installed, true, OPENABLE, false, false));
        check.same("installed with no launcher", Arrays.asList("Details", "Reinstall", "!Remove"), titles(module(installed), true, NOT_OPENABLE, false, false));
        check.same("Wave itself is never removed here", Arrays.asList("Open", "Details", "Reinstall"), titles(installed, true, OPENABLE, true, false));
        check.same("newer than the source offers",
            Arrays.asList("Open", "Details", "Reinstall", "!Remove"), titles(pkg("a", State.NEWER), true, OPENABLE, false, false));
        check.same("turned off", Arrays.asList("Details", "Turn On", "Reinstall", "!Remove"), titles(module(pkg("a", State.OFF)), true, NOT_OPENABLE, false, false));
        check.same("waiting to install: remove only", Arrays.asList("Details", "!Remove"), titles(module(pkg("a", State.PENDING_INSTALL)), true, NOT_OPENABLE, false, false));
        check.same("waiting to be removed: nothing but details", Collections.singletonList("Details"), titles(module(pkg("a", State.PENDING_REMOVAL)), true, NOT_OPENABLE, false, false));

        // What a package kept by its receipt (its source was removed) can't do.
        Pkg kept = kept(installed);
        check.same("kept: no reinstall, no source to download from", Arrays.asList("Open", "Details", "!Remove"), titles(kept, true, OPENABLE, false, false));
        check.same("kept and off", Arrays.asList("Details", "Turn On", "!Remove"), titles(kept(module(pkg("a", State.OFF))), true, NOT_OPENABLE, false, false));

        // A package this phone can't take.
        check.same("doesn't fit, not installed: only details", Collections.singletonList("Details"), titles(unfit(none), true, OPENABLE, false, false));
        check.same("doesn't fit, installed: no reinstall", Arrays.asList("Open", "Details", "!Remove"), titles(unfit(installed), true, OPENABLE, false, false));
        check.same("update that doesn't fit: open it, no update, no reinstall", Arrays.asList("Open", "Details", "!Remove"),
            titles(unfit(update), true, OPENABLE, false, false));
        check.same("update that doesn't fit, no launcher", Arrays.asList("Details", "!Remove"), titles(unfit(update), true, NOT_OPENABLE, false, false));

        // "Needs a newer Wave" and Wave's own update.
        Pkg newerNone = needsNewerWave(none);
        check.same("needs a newer Wave, none offered", Collections.singletonList("Details"), titles(newerNone, true, OPENABLE, false, false));
        check.same("needs a newer Wave, Wave's update offered", Arrays.asList("Update Wave", "Details"), titles(newerNone, true, OPENABLE, false, true));
        check.same("Wave's update is only offered to what needs it", Arrays.asList("Get", "Details"), titles(none, true, OPENABLE, false, true));
        check.same("a package that doesn't fit for another reason doesn't offer it", Collections.singletonList("Details"),
            titles(unfit(none), true, OPENABLE, false, true));
        check.same("installed and needs a newer Wave, offered", Arrays.asList("Open", "Update Wave", "Details", "!Remove"),
            titles(needsNewerWave(installed), true, OPENABLE, false, true));

        check.same("updatable skips what doesn't fit", Arrays.asList("b", "d"), Actions.updatable(Arrays.asList(
            pkg("b", State.UPDATE), unfit(pkg("c", State.UPDATE)), pkg("d", State.UPDATE), needsNewerWave(pkg("e", State.UPDATE)))));
        check.same("updatable of nothing", Collections.emptyList(), Actions.updatable(Collections.emptyList()));
        Snapshot offered = snapshot(null, Collections.singletonList(src("s", false, null, false, 9, 1)),
            Arrays.asList(pkg("a", State.UPDATE), pkg("com.wave.xp3", State.UPDATE)), Collections.emptyList());
        check.same("Wave's update is the update with Wave's id", "com.wave.xp3", Actions.waveUpdate(offered, "com.wave.xp3").id);
        check.same("none when no source offers one", null, Actions.waveUpdate(snapshot(null, Collections.emptyList(), Collections.singletonList(pkg("a", State.UPDATE)),
            Collections.emptyList()), "com.wave.xp3"));
        check.same("none before the first snapshot", null, Actions.waveUpdate(null, "com.wave.xp3"));
        // An update this phone can't take is never among the updates, so it is never offered either (see CatalogModel).
        check.ok("an app with a launcher can be opened", Actions.openable(installed, "com.wave.xp3", () -> true));
        check.ok("an app without one can't", !Actions.openable(installed, "com.wave.xp3", () -> false));
        check.ok("a mod can't, whatever the phone says", !Actions.openable(module(installed), "com.wave.xp3", () -> { throw new AssertionError("asked"); }));
        check.ok("nor can Wave itself", !Actions.openable(pkg("com.wave.xp3", State.INSTALLED), "com.wave.xp3", () -> { throw new AssertionError("asked"); }));
        check.same("What's New after Details", 2, Actions.whatsNewAt(Arrays.asList("Open", "Details", "!Remove")));
        check.same("What's New first without Details", 0, Actions.whatsNewAt(Arrays.asList("Get")));
    }

    // ---------------------------------------------------------------- Installed

    private static void installed(Check check) {
        Pkg app = pkg("a", State.INSTALLED);
        check.same("an app's version", "MUTED:Version 1.0", Pages.installedLine(app, false).toString());
        check.same("a mod's version", "MUTED:System mod, 1.0", Pages.installedLine(module(app), false).toString());
        check.same("the offered version when the phone's is unknown", "MUTED:Version 2.0",
            Pages.installedLine(new Edit(app) {{ installed = null; }}.build(), false).toString());
        check.same("off, by the user or safe mode", "RED:Turned off", Pages.installedLine(pkg("a", State.OFF), false).toString());
        check.same("off by the watchdog", "RED:Turned off: boot failed", Pages.installedLine(pkg("a", State.OFF), true).toString());
        check.same("pending install", "BLUE:Starts after a reboot", Pages.installedLine(pkg("a", State.PENDING_INSTALL), false).toString());
        check.same("pending removal", "BLUE:Removed after a reboot", Pages.installedLine(pkg("a", State.PENDING_REMOVAL), false).toString());
        check.same("a pending package has no capsule", null, Pages.installedCapsule(pkg("a", State.PENDING_INSTALL), OPENABLE));
        check.same("an off package has no capsule", null, Pages.installedCapsule(pkg("a", State.OFF), OPENABLE));
        check.same("an installed one follows the rule", "OPEN", Pages.installedCapsule(app, OPENABLE));
        check.same("update row: date", "Nov 12, 2026", Pages.updateLine(new Edit(pkg("a", State.UPDATE)) {{ change = new Change("2.0", "2026-11-12", "x"); }}.build()));
        check.same("update row: no date, the version", "Version 2.0", Pages.updateLine(new Edit(pkg("a", State.UPDATE)) {{ change = new Change("2.0", null, "x"); }}.build()));
        check.same("update row: no change at all", "Version 2.0", Pages.updateLine(pkg("a", State.UPDATE)));
        check.ok("notes shown", Pages.hasNotes(new Edit(pkg("a", State.UPDATE)) {{ change = new Change("2.0", null, "Fixes."); }}.build()));
        check.ok("empty notes hidden", !Pages.hasNotes(new Edit(pkg("a", State.UPDATE)) {{ change = new Change("2.0", null, ""); }}.build()));
        check.ok("no change, no notes", !Pages.hasNotes(pkg("a", State.UPDATE)));
        check.same("one waiting change", "Messages Improved starts after a reboot", Pages.rebootText(Collections.singletonList("Messages Improved")));
        check.same("several waiting changes", "3 changes start after a reboot", Pages.rebootText(Arrays.asList("a", "b", "c")));

        // The page with nothing on it: which message, in which precedence.
        Src fetched = src("s", false, null, false, 1700000000000L, 3), never = src("n", false, null, false, 0, 0);
        List<Pkg> none = Collections.emptyList();
        Pages.Message m = Pages.installedEmpty(snapshot(null, Collections.singletonList(never), none, none));
        check.ok("nothing read yet and no error: a spinner", m.spinner && m.title == null);
        check.ok("no sources at all: a spinner", Pages.installedEmpty(snapshot(null, Collections.emptyList(), none, none)).spinner);
        m = Pages.installedEmpty(snapshot("Couldn't reach the source.", Collections.singletonList(never), none, none));
        check.same("nothing read and an error: the error", "Cannot Load Sources", m.title);
        check.same("with its text", "Couldn't reach the source.", m.body);
        check.same("and a retry", "Try Again", m.button);
        m = Pages.installedEmpty(snapshot("Couldn't reach the source.", Collections.singletonList(fetched), none, none));
        check.same("something was read, so an error doesn't hide that nothing is installed", "Nothing Installed", m.title);
        m = Pages.installedEmpty(snapshot(null, Collections.singletonList(fetched), none, none));
        check.same("read, nothing installed", "What Wave installs is listed here.", m.body);
        check.ok("no retry on that", m.button == null && !m.spinner);
        check.ok("a source with packages but no refresh time counts as fetched", !Pages.nothingFetched(
            snapshot(null, Collections.singletonList(src("p", false, null, false, 0, 4)), none, none)));
        check.ok("a refresh time without packages counts as fetched", !Pages.nothingFetched(
            snapshot(null, Collections.singletonList(src("p", false, null, false, 5, 0)), none, none)));
    }

    // ---------------------------------------------------------------- Sources and Wave

    private static void sourcesAndHome(Check check) {
        List<Pkg> none = Collections.emptyList();
        Src never = src("n", false, null, false, 0, 0);
        Snapshot loading = snapshot("Couldn't reach the source.", Collections.singletonList(never), none, none);
        check.same("Sources tab: the error replaces the list", "Cannot Load Sources", Pages.sourcesEmpty(loading).title);
        check.same("its retry", "Try Again", Pages.sourcesEmpty(loading).button);
        Snapshot refreshing = snapshot("Couldn't reach the source.", Collections.singletonList(src("n", true, null, false, 0, 0)), none, none);
        check.same("not while one is refreshing", null, Pages.sourcesEmpty(refreshing));
        check.same("not without an error", null, Pages.sourcesEmpty(snapshot(null, Collections.singletonList(never), none, none)));
        check.same("not when something was read", null, Pages.sourcesEmpty(
            snapshot("x", Collections.singletonList(src("f", false, null, false, 9, 2)), none, none)));
        check.ok("bar: show the spinner while a source refreshes", Pages.anyRefreshing(refreshing));
        check.ok("bar: no spinner when no source is refreshing", !Pages.anyRefreshing(loading));

        check.same("row: refreshing wins over an error", "MUTED:Refreshing…", Pages.sourceRow(src("a", true, "Bad.", false, 0, 0)).toString());
        check.same("row: an error wins over a newer format", "RED:Bad.", Pages.sourceRow(src("a", false, "Bad.", true, 0, 0)).toString());
        check.same("row: a newer format", "RED:Needs a newer Wave", Pages.sourceRow(src("a", false, null, true, 0, 0)).toString());
        check.same("row: the address", "MUTED:example.org/a", Pages.sourceRow(src("a", false, null, false, 0, 0)).toString());
        check.same("header: refreshing", "MUTED:Refreshing…", Pages.sourceHeader(src("a", true, "Bad.", false, 0, 0)).toString());
        check.same("header: an error says it couldn't refresh", "RED:Couldn't refresh: Bad.", Pages.sourceHeader(src("a", false, "Bad.", false, 5, 1)).toString());
        check.same("header: never refreshed", "MUTED:Maker", Pages.sourceHeader(src("a", false, null, false, 0, 0)).toString());
        check.same("header: maintainer and when", "MUTED:Maker, refreshed today",
            Pages.sourceHeader(src("a", false, null, false, System.currentTimeMillis(), 1)).toString());
        Src nameless = new Src("a", "A", "https://example.org/a.git", null, false, null, 0, 0, false, null, false);
        check.same("header: no maintainer, the address", "MUTED:example.org/a", Pages.sourceHeader(nameless).toString());
        check.same("title: all sources", "All Sources", Pages.sourceTitle(null, null));
        check.same("title: a source", "Source a", Pages.sourceTitle("a", src("a", false, null, false, 0, 0)));
        check.same("title: one that went", "Source", Pages.sourceTitle("a", null));
        check.same("one package left out", "1 package can't be read", Pages.unreadableLabel(1));
        check.same("several", "7 packages can't be read", Pages.unreadableLabel(7));
        check.same("alert text", "a: Over 64 KB\nb: summary is required", Pages.unreadableText(Arrays.asList("a: Over 64 KB", "b: summary is required"), Collections.emptyList()));
        check.same("alert text with icons", "a: x\n\nIcons ignored:\nb: y", Pages.unreadableText(Collections.singletonList("a: x"), Collections.singletonList("b: y")));

        check.same("root status: granted", "GREEN:Root access granted", Pages.rootStatus(WaveData.Root.OK).toString());
        check.same("root status: checking", "MUTED:Checking root access…", Pages.rootStatus(WaveData.Root.CHECKING).toString());
        check.same("root status: not granted", "ORANGE:Root access not granted", Pages.rootStatus(WaveData.Root.NONE).toString());
        check.same("root fact: yes", "Yes", Pages.rootText(WaveData.Root.OK));
        check.same("root fact: no", "No", Pages.rootText(WaveData.Root.NONE));
        List<String[]> facts = Pages.phoneFacts(snapshot(WaveData.Root.NONE, null), "XP3800", "8.1.0");
        check.same("facts without a snapshot's own: the phone's", "XP3800|8.1.0|No|", join(facts));
        facts = Pages.phoneFacts(snapshot(WaveData.Root.OK, new WaveData.Facts("Sonim XP3800", "8.1.0", "3A.0", "Magisk 24", "1.2 GB free")), "X", "Y");
        check.same("facts of the snapshot", "Sonim XP3800|8.1.0, build 3A.0|Magisk 24|1.2 GB free", join(facts));
        check.same("fact labels", "Model,Android,Root,Storage", labels(facts));
    }

    private static String join(List<String[]> rows) {
        StringBuilder b = new StringBuilder();
        for (String[] row : rows) b.append(b.length() == 0 ? "" : "|").append(row[1]);
        return b.toString();
    }

    private static String labels(List<String[]> rows) {
        StringBuilder b = new StringBuilder();
        for (String[] row : rows) b.append(b.length() == 0 ? "" : ",").append(row[0]);
        return b.toString();
    }

    // ---------------------------------------------------------------- Details

    private static void details(Check check) {
        Pkg none = pkg("a", State.NONE), update = pkg("a", State.UPDATE), installed = pkg("a", State.INSTALLED);
        check.same("a plain package has no notes", "[]", Pages.notes(none, detail(none), false).toString());

        // A package that needs a newer Wave says so once, and points at Wave's update when there is one.
        Pkg newer = needsNewerWave(none);
        check.same("needs a newer Wave", "[INFO:Needs a newer Wave]", Pages.notes(newer, detail(newer), false).toString());
        check.same("and Wave's update is on offer", "[INFO:Update Wave to install this]", Pages.notes(newer, detail(newer), true).toString());
        check.same("the reason isn't repeated as a second warning", 1, Pages.notes(newer, detail(newer), true).size());
        Pkg other = unfit(none);
        check.same("another reason is the warning", "[WARN:Not compatible with this device]", Pages.notes(other, detail(other), true).toString());
        check.same("the warning is red text with an orange icon", Pages.Tone.RED, Pages.notes(other, detail(other), true).get(0).textTone);
        check.same("update that doesn't fit", "[WARN:Not compatible with this device, INFO:Version 2.0 can't be installed here]",
            Pages.notes(unfit(update), detail(unfit(update)), false).toString());
        check.same("update that needs a newer Wave", "[INFO:Update Wave to install this, INFO:Version 2.0 can't be installed here]",
            Pages.notes(needsNewerWave(update), detail(needsNewerWave(update)), true).toString());
        check.same("a mod needs a reboot", "[REBOOT:System mod, needs a reboot]", Pages.notes(module(none), detail(module(none)), false).toString());
        check.same("a mod turned off", "[REBOOT:System mod, needs a reboot, WARN:Turned off: the phone didn't finish starting with it]",
            Pages.notes(module(pkg("a", State.OFF)), detail(module(pkg("a", State.OFF))), false).toString());
        check.same("waiting to install", "[REBOOT:Starts after a reboot]", Pages.notes(pkg("a", State.PENDING_INSTALL), detail(pkg("a", State.PENDING_INSTALL)), false).toString());
        check.same("waiting to be removed", "[REBOOT:Removed after a reboot]", Pages.notes(pkg("a", State.PENDING_REMOVAL), detail(pkg("a", State.PENDING_REMOVAL)), false).toString());
        Detail rich = detail(none, "Long.", "Me", 0, Collections.emptyList(), Collections.emptyList(), Arrays.asList("Old Messages", "Older"), true);
        check.same("replaces and the firmware note, last", "[INFO:Replaces Old Messages, INFO:Replaces Older, INFO:Not tested on this phone's firmware]",
            Pages.notes(none, rich, false).toString());

        check.same("byline: the author", "An Author", Pages.byline(none, detail(none)));
        check.same("byline: the source when there is no author", "Source", Pages.byline(none, detail(none, "", "", 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false)));
        Pkg kept = kept(installed);
        check.same("byline: a kept package has none", null, Pages.byline(kept, detail(kept, "", "", 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false)));
        check.same("byline: null author and source", null, Pages.byline(new Edit(none) {{ sourceName = null; }}.build(),
            detail(none, null, null, 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false)));

        check.same("state word", "INSTALLED", Pages.stateWord(installed));
        check.same("state word: newer", "INSTALLED", Pages.stateWord(pkg("a", State.NEWER)));
        check.same("state word: off", "TURNED OFF", Pages.stateWord(pkg("a", State.OFF)));
        check.same("state word: pending", "AFTER REBOOT", Pages.stateWord(pkg("a", State.PENDING_INSTALL)));
        check.same("state word: pending removal", "AFTER REBOOT", Pages.stateWord(pkg("a", State.PENDING_REMOVAL)));
        check.same("after: the size to get", "48 KB", Pages.afterText(none, detail(none)));
        check.same("after: no size, the version", "Version 2.0", Pages.afterText(none, detail(none, "", "", 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false)));
        check.same("after: an update's change", "1.0 → 2.0", Pages.afterText(update, detail(update)));
        check.same("after: an update with no installed version", "Version 2.0", Pages.afterText(new Edit(update) {{ installed = null; }}.build(), detail(update)));
        check.same("after: what is installed", "Version 1.0", Pages.afterText(installed, detail(installed)));
        check.same("after: a kept package with no installed version", "Version 2.0", Pages.afterText(new Edit(installed) {{ installed = null; }}.build(), detail(installed)));

        check.same("description", "Describes a", Pages.description(detail(none)));
        check.same("the summary stands in for a missing description", "Summary of a",
            Pages.description(detail(none, "", "", 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false)));
        check.same("a kept package has neither", null, Pages.description(detail(kept, "", "", 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false)));
        check.ok("an update puts its changes first", Pages.changesFirst(update) && !Pages.changesFirst(installed));

        check.same("facts of a package", "Version=2.0;Size=48 KB;Source=Source;Section=Section;Author=An Author;Id=a", facts(Pages.facts(none, detail(none))));
        check.same("facts of an update show the change", "Version=1.0 → 2.0;Size=48 KB;Source=Source;Section=Section;Author=An Author;Id=a", facts(Pages.facts(update, detail(update))));
        Detail linked = detail(none, "d", "", 1_500_000, Arrays.asList("Core ≥ 1.0", "Other"), Arrays.asList("Rival"), Collections.emptyList(), false);
        check.same("facts with needs and conflicts", "Version=2.0;Size=1.5 MB;Source=Source;Section=Section;Needs=Core ≥ 1.0, Other;Conflicts=Rival;Id=a", facts(Pages.facts(none, linked)));
        check.same("facts of a kept package: no source, section or author", "Version=2.0;Id=a",
            facts(Pages.facts(kept, detail(kept, "", "", 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false))));
        check.same("a kept package with null names", "Version=2.0;Id=a", facts(Pages.facts(new Edit(kept) {{ sourceName = null; section = null; }}.build(),
            detail(kept, null, null, 0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false))));
        check.same("at most four versions of What's New", 4, Pages.MAX_CHANGES);
    }

    private static String facts(List<String[]> rows) {
        StringBuilder b = new StringBuilder();
        for (String[] row : rows) b.append(b.length() == 0 ? "" : ";").append(row[0]).append('=').append(row[1]);
        return b.toString();
    }

    // ---------------------------------------------------------------- Confirm and queue

    private static PlanItem item(Pkg p, String reason, WaveData.Action action) {
        return new PlanItem(p, reason, action);
    }

    private static QueueItem step(WaveData.Step step, String text, long bytes, long total, int percent) {
        return new QueueItem(pkg("a", State.NONE), step, percent, bytes, total, text, null);
    }

    private static void planAndQueue(Check check) {
        Pkg a = pkg("a", State.NONE), b = pkg("b", State.UPDATE), c = pkg("c", State.INSTALLED);
        check.same("heading: install", "Install", Pages.confirmHeading(Arrays.asList(item(a, null, WaveData.Action.GET)), false));
        check.same("heading: update", "Update", Pages.confirmHeading(Arrays.asList(item(b, null, WaveData.Action.UPDATE), item(b, null, WaveData.Action.UPDATE)), false));
        check.same("heading: reinstall", "Reinstall", Pages.confirmHeading(Arrays.asList(item(c, null, WaveData.Action.REINSTALL)), false));
        check.same("heading: a mix is an install", "Install", Pages.confirmHeading(Arrays.asList(item(b, null, WaveData.Action.UPDATE), item(a, null, WaveData.Action.GET)), false));
        check.same("heading: remove", "Remove", Pages.confirmHeading(Arrays.asList(item(c, null, WaveData.Action.REMOVE)), true));
        check.same("line: a pulled-in package says why", "BLUE:Needed by List", Pages.confirmLine(item(a, "Needed by List", WaveData.Action.GET), false).toString());
        check.same("line: a removal's reason is not blue", "MUTED:Replaced by Messages Improved", Pages.confirmLine(item(c, "Replaced by Messages Improved", WaveData.Action.REMOVE), true).toString());
        check.same("line: a removal shows what is installed", "MUTED:Version 1.0", Pages.confirmLine(item(c, null, WaveData.Action.REMOVE), true).toString());
        check.same("line: an update shows the change", "MUTED:1.0 → 2.0", Pages.confirmLine(item(b, null, WaveData.Action.UPDATE), false).toString());
        check.same("line: an install shows the version", "MUTED:2.0", Pages.confirmLine(item(a, null, WaveData.Action.GET), false).toString());
        check.same("line: an update with no installed version", "MUTED:2.0", Pages.confirmLine(item(new Edit(b) {{ installed = null; }}.build(), null, WaveData.Action.UPDATE), false).toString());
        PlanView one = new PlanView(Arrays.asList(item(a, null, WaveData.Action.GET)), 4_107_233, false, null);
        check.same("sheet: one install's title", "Install " + a.name + "?", Pages.confirmTitle(one));
        check.same("sheet: its version under the title", "Version 2.0", Pages.confirmSub(one));
        check.same("sheet: its facts are the download", "[TEXT:[4.1 MB] to download]", Pages.confirmFacts(one).toString());
        check.ok("sheet: an install goes ahead in blue", !Pages.confirmDestroys(one));
        PlanView pulled = new PlanView(Arrays.asList(item(a, null, WaveData.Action.GET), item(b, "Needed by " + a.name, WaveData.Action.GET),
            item(c, "Replaced by " + a.name, WaveData.Action.REMOVE)), 0, true, null);
        check.same("sheet: what comes along and goes is named, the reboot said", "[TEXT:Also installs [" + b.name + "], TEXT:Replaces [" + c.name + "], ORANGE:Needs a reboot to finish[]]",
            Pages.confirmFacts(pulled).toString());
        check.same("sheet: replacing is still an install", "Install", Pages.confirmVerb(pulled));
        PlanView all = new PlanView(Arrays.asList(item(b, null, WaveData.Action.UPDATE), item(b, null, WaveData.Action.UPDATE),
            item(a, "Needed by " + b.name, WaveData.Action.GET)), 0, false, null);
        check.same("sheet: several updates", "Update 2 Packages?", Pages.confirmTitle(all));
        check.same("sheet: no single version", null, Pages.confirmSub(all));
        check.same("sheet: each listed, and what comes along says why", "[TEXT:[" + b.name + "] 1.0 → 2.0, TEXT:[" + b.name + "] 1.0 → 2.0, TEXT:Also installs [" + a.name + "], needed by " + b.name + "]",
            Pages.confirmFacts(all).toString());
        PlanView remove = new PlanView(Arrays.asList(item(c, null, WaveData.Action.REMOVE)), 0, true, null);
        check.same("sheet: a removal", "Remove " + c.name + "?", Pages.confirmTitle(remove));
        check.same("sheet: what is installed", "Version 1.0", Pages.confirmSub(remove));
        check.ok("sheet: only removing is red and starts on Cancel", Pages.confirmDestroys(remove));
        check.same("words: as shown", "Get", Pages.shownWord("GET"));
        check.same("words: each word", "Turned Off", Pages.shownWord("TURNED OFF"));
        check.same("words: Reboot Now is left alone", "Reboot Now", Pages.shownWord("Reboot Now"));
        PlanView mixed = new PlanView(Arrays.asList(item(a, null, WaveData.Action.GET), item(c, null, WaveData.Action.REMOVE)), 0, false, null);
        check.same("plan splits installs from removals", 1, mixed.install.size());
        check.same("and removals", 1, mixed.remove.size());

        Pages.QueueLine line = Pages.queueLine(step(WaveData.Step.DONE, null, 0, 0, 0), false);
        check.ok("done: green tick", line.text.equals("Done") && line.tone == Pages.Tone.GREEN && line.right == Pages.Right.TICK);
        check.same("done with a text", "Installed 1.0", Pages.queueLine(step(WaveData.Step.DONE, "Installed 1.0", 0, 0, 0), false).text);
        line = Pages.queueLine(step(WaveData.Step.FAILED, null, 0, 0, 0), true);
        check.ok("failed: red, nothing on the right", line.text.equals("Not installed") && line.tone == Pages.Tone.RED && line.right == Pages.Right.NONE);
        check.same("skipped", "Skipped", Pages.queueLine(step(WaveData.Step.SKIPPED, null, 0, 0, 0), false).text);
        line = Pages.queueLine(step(WaveData.Step.WAITING, null, 0, 0, 0), false);
        check.ok("waiting: dim, spinner", line.text.equals("Waiting") && line.dim && line.right == Pages.Right.SPINNER);
        check.same("waiting on a finished queue loses its spinner", Pages.Right.NONE, Pages.queueLine(step(WaveData.Step.WAITING, null, 0, 0, 0), true).right);
        line = Pages.queueLine(step(WaveData.Step.DOWNLOADING, null, 500_000, 4_107_233, 12), false);
        check.same("downloading", "Downloading, 0.5 of 4.1 MB", line.text);
        check.same("with its percent", 12, line.percent);
        check.same("downloading without a known size", "Downloading", Pages.queueLine(step(WaveData.Step.DOWNLOADING, null, 500_000, 0, -1), false).text);
        check.same("checking", "Checking", Pages.queueLine(step(WaveData.Step.CHECKING, null, 0, 0, 0), false).text);
        check.same("installing", "Installing", Pages.queueLine(step(WaveData.Step.INSTALLING, null, 0, 0, 0), false).text);
        check.same("removing", "Removing", Pages.queueLine(step(WaveData.Step.REMOVING, null, 0, 0, 0), false).text);
        check.same("a step's own text wins", "Starting", Pages.queueLine(step(WaveData.Step.INSTALLING, "Starting", 0, 0, 0), false).text);
        check.same("title while working", "Installing", Pages.queueTitle(new QueueView(Collections.emptyList(), false, false), true));
        check.same("title when done", "Done", Pages.queueTitle(new QueueView(Collections.emptyList(), true, false), true));
        check.same("title of a stale finished queue not yet seen", "Installing", Pages.queueTitle(new QueueView(Collections.emptyList(), true, false), false));
        check.same("title with no queue", "Installing", Pages.queueTitle(null, true));
        Pkg weather = new Edit(a) {{ name = "Weather"; }}.build();
        check.same("failure title", "Weather wasn't installed", Pages.failureTitle(new QueueItem(weather, WaveData.Step.FAILED, 0, 0, 0, "Installing", "x")));
        check.same("failure title of a removal", "Weather wasn't removed", Pages.failureTitle(new QueueItem(weather, WaveData.Step.FAILED, 0, 0, 0, "Removing the module", "x")));
        check.same("failure message", "Disk full.", Pages.failureMessage(new QueueItem(weather, WaveData.Step.FAILED, 0, 0, 0, null, "Disk full.")));
        check.same("failure without a message", "Something went wrong.", Pages.failureMessage(new QueueItem(weather, WaveData.Step.FAILED, 0, 0, 0, null, null)));
    }

    // ---------------------------------------------------------------- Search

    private static void search(Check check) {
        check.same("no query: nothing drawn", Pages.Results.BLANK, Pages.results(null));
        check.same("a query that found nothing", Pages.Results.NONE_FOUND, Pages.results(Collections.emptyList()));
        check.same("a query that found something", Pages.Results.FOUND, Pages.results(Collections.singletonList(pkg("a", State.NONE))));
    }

    // ---------------------------------------------------------------- Snapshot lookups

    private static void snapshots(Check check) {
        Pkg a = pkg("a", State.NONE), b = pkg("b", State.INSTALLED), c = pkg("c", State.UPDATE);
        WaveData.Section apps = new WaveData.Section("applications", "Applications", 2), sys = new WaveData.Section("system", "System", 1);
        java.util.Map<String, List<Pkg>> groups = new java.util.LinkedHashMap<>();
        groups.put("applications", Arrays.asList(a, b));
        groups.put("system", Collections.singletonList(c));
        java.util.Map<String, Detail> details = new java.util.LinkedHashMap<>();
        for (Pkg p : Arrays.asList(a, b, c)) details.put(p.id, detail(p));
        WaveData.Shelf mine = new WaveData.Shelf(Collections.singletonList(b), Arrays.asList(a, b, c), Arrays.asList(apps, sys), groups, details);
        WaveData.Shelf theirs = new WaveData.Shelf(Collections.singletonList(c), Collections.singletonList(c), Collections.singletonList(sys),
            Collections.singletonMap("system", Collections.singletonList(c)), Collections.singletonMap("c", detail(new Edit(c) {{ version = "9.9"; }}.build())));
        WaveData.Shelf all = new WaveData.Shelf(Collections.emptyList(), Arrays.asList(a, b, c), Arrays.asList(apps, sys), groups, details);
        java.util.Map<String, WaveData.Shelf> by = new java.util.LinkedHashMap<>();
        by.put("one", mine);
        by.put("two", theirs);
        Src one = new Src("one", "One", "https://e.org/1", null, true, "", 1, 3, false, null, false);
        Src two = new Src("two", "Two", "https://e.org/2", null, false, "", 1, 1, false, null, false);
        Snapshot s = new Snapshot(WaveData.Root.OK, null, null, Arrays.asList(one, two), all, by, Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null, null);
        check.same("no featured across all sources", Collections.emptyList(), s.featured(null));
        check.same("featured of the default source", Collections.singletonList(b), s.featured("one"));
        check.same("featured of a source", Collections.singletonList(c), s.featured("two"));
        check.same("featured of an unknown source", Collections.emptyList(), s.featured("nope"));
        check.same("all packages", Arrays.asList(a, b, c), s.packages(null, null));
        check.same("a section across all sources", Arrays.asList(a, b), s.packages(null, "applications"));
        check.same("a source's packages", Collections.singletonList(c), s.packages("two", null));
        check.same("a section the source lacks", Collections.emptyList(), s.packages("two", "applications"));
        check.same("a source that is gone", Collections.emptyList(), s.packages("nope", null));
        check.same("section counts", 2, s.sections(null).get(0).count);
        check.same("section order is the source's", "system", s.sections(null).get(1).folder);
        check.same("a source's sections", 1, s.sections("two").size());
        check.same("detail across sources", b, s.detail("b").pkg);
        check.same("detail in the source it was browsed in", "9.9", s.detail("two", "c").pkg.version);
        check.same("merged detail", "2.0", s.detail("c").pkg.version);
        check.same("detail that isn't there", null, s.detail("two", "a"));
        check.same("detail of an unknown package", null, s.detail("zzz"));
        check.fails("a snapshot's lists can't be changed", () -> s.sources.add(one));
        check.fails("nor a shelf's", () -> mine.packages.add(a));
        check.fails("nor a plan's", () -> new PlanView(Collections.emptyList(), 0, false, null).items.add(null));
        List<Pkg> source = new ArrayList<>(Arrays.asList(a, b));
        WaveData.Shelf copy = new WaveData.Shelf(source, source, Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());
        source.clear();
        check.same("a shelf keeps its own copy", 2, copy.packages.size());
        Src many = new Src("m", "M", "https://e.org/m", null, false, "", 1, 3, false, null, false, 1000,
            Arrays.asList("x: Over 64 KB"), Collections.emptyList());
        check.same("a source with many unreadable packages counts them all", 1000, many.unreadableCount);
    }
}
