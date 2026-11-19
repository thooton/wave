package com.wave.xp3.app;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Detail;
import com.wave.xp3.data.WaveData.Pkg;
import com.wave.xp3.data.WaveData.Section;
import com.wave.xp3.data.WaveData.Snapshot;
import com.wave.xp3.data.WaveData.Src;
import com.wave.xp3.data.WaveData.State;
import com.xp3.common.Check;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The debug fixtures (FakeWaveData) are what the QA scenarios and the phone frames are
 * judged against, so each must be a snapshot a real controller could produce, and every
 * package in them must get through every decision the screens make without surprises.
 */
final class FixtureTests {
    private FixtureTests() {}

    static final String[] NAMES = {"browse", "reboot", "errors", "offline", "uptodate", "big", "noroot", "newer", "kept"};

    static void run(Check check) {
        check.run("Debug fixtures are coherent snapshots", () -> {
            for (String name : NAMES) fixture(check, name);
        });
        check.run("A fixture's story", () -> stories(check));
    }

    private static Snapshot snapshot(String name) {
        return new FakeWaveData(name).snapshot();
    }

    private static void fixture(Check check, String name) {
        Snapshot s = snapshot(name);
        String at = name + ": ";
        check.ok(at + "has sources, or is offline", !s.sources.isEmpty());
        Set<String> seen = new HashSet<>();
        for (Pkg p : s.updates) {
            check.same(at + "an update is an UPDATE: " + p.id, State.UPDATE, p.state);
            check.ok(at + "an update fits this phone: " + p.id, !Actions.unfit(p));
            check.ok(at + "listed once: " + p.id, seen.add(p.id));
        }
        for (Pkg p : s.upToDate) {
            check.ok(at + "up to date isn't NONE: " + p.id, p.state != State.NONE);
            check.ok(at + "listed once: " + p.id, seen.add(p.id));
            check.ok(at + "an installed package knows its version: " + p.id, p.installedVersion != null);
        }
        List<Pkg> sorted = new ArrayList<>(s.updates);
        for (int i = 1; i < sorted.size(); i++) {
            check.ok(at + "updates by name", sorted.get(i - 1).name.compareToIgnoreCase(sorted.get(i).name) <= 0);
        }
        for (Src src : s.sources) {
            List<Pkg> inSource = s.packages(src.id, null);
            int counted = 0;
            for (Section section : s.sections(src.id)) {
                counted += section.count;
                check.same(at + src.id + "/" + section.folder + " count is what it lists", section.count, s.packages(src.id, section.folder).size());
            }
            check.same(at + src.id + ": sections add up to its packages", inSource.size(), counted);
            for (Pkg p : s.featured(src.id)) check.ok(at + "featured is in its source: " + p.id, inSource.contains(p));
            for (Pkg p : inSource) {
                Detail d = s.detail(src.id, p.id);
                check.ok(at + src.id + ": a detail for " + p.id, d != null && d.pkg == p);
                check.same(at + "package belongs to its source: " + p.id, src.id, p.sourceId);
            }
        }
        for (Pkg p : s.packages(null, null)) {
            check.ok(at + "all packages have a detail: " + p.id, s.detail(p.id) != null);
        }
        decisions(check, at, s);
        check.same(at + "root", name.equals("noroot") ? WaveData.Root.NONE : WaveData.Root.OK, s.root);
    }

    /** Runs every package of the snapshot through the decisions the screens make. */
    private static void decisions(Check check, String at, Snapshot s) {
        List<Pkg> everything = new ArrayList<>(s.updates);
        everything.addAll(s.upToDate);
        everything.addAll(s.packages(null, null));
        for (Pkg p : everything) {
            for (boolean open : new boolean[]{true, false}) {
                java.util.function.Predicate<Pkg> canOpen = q -> open;
                List<Actions.Item> menu = Actions.menu(p, true, canOpen, p.id.equals("com.wave.xp3"), false);
                check.ok(at + "menu has no repeats: " + p.id, new HashSet<>(menu).size() == menu.size());
                check.ok(at + "remove only for what is on the phone: " + p.id,
                    menu.contains(Actions.Item.REMOVE) == (p.state != State.NONE && p.state != State.PENDING_REMOVAL && !p.id.equals("com.wave.xp3")));
                check.ok(at + "reinstall needs a source: " + p.id, !menu.contains(Actions.Item.REINSTALL) || p.sourceId != null);
                check.ok(at + "get or update never for what doesn't fit: " + p.id,
                    !(Actions.unfit(p) && (menu.contains(Actions.Item.GET) || menu.contains(Actions.Item.UPDATE))));
                String word = Actions.capsule(p, canOpen);
                check.ok(at + "capsule word is a known one: " + p.id, word == null || word.equals("GET") || word.equals("UPDATE") || word.equals("OPEN"));
            }
            check.ok(at + "installed line says something: " + p.id, !Pages.installedLine(p, false).text.isEmpty());
            Detail d = s.detail(p.id);
            if (d == null) continue;
            for (String[] fact : Pages.facts(p, d)) check.ok(at + "no empty fact " + fact[0] + ": " + p.id, fact[1] != null && !fact[1].isEmpty());
            for (Pages.Note note : Pages.notes(p, d, true)) check.ok(at + "no empty note: " + p.id, !note.text.isEmpty());
            check.ok(at + "something says what it is: " + p.id, !Pages.afterText(p, d).isEmpty());
        }
    }

    private static void stories(Check check) {
        Snapshot reboot = snapshot("reboot");
        check.same("reboot: the waiting change is named", java.util.Collections.singletonList("Messages Improved"), reboot.rebootPending);
        check.ok("reboot: and it's a pending install", pkg(reboot, "messages_improved").state == State.PENDING_INSTALL);
        Snapshot errors = snapshot("errors");
        check.same("errors: one turned off", 1, errors.turnedOff.size());
        check.same("errors: and it is the one shown off", State.OFF, pkg(errors, errors.turnedOff.get(0).id).state);
        boolean failing = false;
        for (Src src : errors.sources) failing |= src.error != null;
        check.ok("errors: a source fails to refresh", failing);
        Snapshot offline = snapshot("offline");
        check.ok("offline: nothing fetched, an error", Pages.nothingFetched(offline) && offline.firstLoadError != null);
        check.same("offline: the Installed page says why", "Cannot Load Sources", Pages.installedEmpty(offline).title);
        Snapshot current = snapshot("uptodate");
        check.ok("uptodate: no updates", current.updates.isEmpty());
        Snapshot browse = snapshot("browse");
        check.ok("browse: updates to take", browse.updates.size() >= 2);
        check.ok("browse: a package that doesn't fit its device", Actions.unfit(pkg(browse, "com.mkline.tetrim")));
        Snapshot newer = snapshot("newer");
        Pkg wave = null;
        for (Pkg p : newer.updates) if (p.id.equals("com.wave.xp3")) wave = p;
        check.ok("newer: Wave's own update is offered", wave != null);
        Pkg night = pkg(newer, "night_theme");
        check.ok("newer: Night Theme needs a newer Wave", night.needsNewerWave);
        check.same("newer: with Wave's update on offer the menu leads there", Actions.Item.UPDATE_WAVE,
            Actions.menu(night, true, p -> false, false, true).get(0));
        check.same("newer: and the details page says so", "Update Wave to install this", Pages.notes(night, newer.detail(night.id), true).get(0).text);
        Snapshot kept = snapshot("kept");
        for (String id : new String[]{"com.weather.xp3", "clock_seconds", "com.wave.xp3"}) {
            Pkg p = pkg(kept, id);
            check.ok("kept: " + id + " has no source to reinstall from", p.sourceId == null
                && !Actions.menu(p, true, q -> true, id.equals("com.wave.xp3"), false).contains(Actions.Item.REINSTALL));
            String source = id.equals("com.weather.xp3") ? "Old Source" : null;
            check.same("kept: " + id + " has no byline unless its receipt names a source", source, Pages.byline(p, kept.detail(id)));
            StringBuilder labels = new StringBuilder();
            for (String[] fact : Pages.facts(p, kept.detail(id))) labels.append(labels.length() == 0 ? "" : ",").append(fact[0]);
            check.same("kept: " + id + " shows only what it has", source == null ? "Version,Id" : "Version,Source,Id", labels.toString());
        }
        Snapshot big = snapshot("big");
        check.same("big: 500 packages in Big Source", 500, big.packages("bulk", null).size());
        check.same("big: five sections", 5, big.sections("bulk").size());
        Src bulk = null;
        for (Src src : big.sources) if (src.id.equals("bulk")) bulk = src;
        check.same("big: unreadable lines match the count", bulk.unreadableCount, bulk.unreadable.size());
    }

    private static Pkg pkg(Snapshot s, String id) {
        for (Pkg p : s.updates) if (p.id.equals(id)) return p;
        for (Pkg p : s.upToDate) if (p.id.equals(id)) return p;
        Detail d = s.detail(id);
        if (d == null) throw new AssertionError("fixture lacks " + id);
        return d.pkg;
    }
}
