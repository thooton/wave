package com.wave.xp3.app;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Pkg;
import com.wave.xp3.data.WaveData.State;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * What a package offers and what pressing it does, as plain decisions. The
 * screens turn the answers into rows, menu entries and calls; nothing here
 * touches a View, so each rule is checked on a desktop JVM (AppSelfTest).
 * {@code canOpen} asks the phone whether the app has a launcher, so it is only
 * asked where the answer matters.
 */
final class Actions {
    private Actions() {}

    /** Entries of a package's menu, in the order shown. */
    enum Item {
        OPEN("Open"), GET("Get"), UPDATE("Update"), UPDATE_WAVE("Update Wave"), DETAILS("Details"),
        HISTORY("See Version History"), TURN_ON("Turn On"), REINSTALL("Reinstall"), REMOVE("!Remove");

        /** The menu's text; a leading "!" paints it red. */
        final String title;

        Item(String title) {
            this.title = title;
        }
    }

    /** What pressing a row's capsule (or the row) does. */
    enum Press { NOTHING, GET, UPDATE, OPEN, DETAILS }

    /** A package this phone can't take: it needs a newer Wave or doesn't fit. */
    static boolean unfit(Pkg p) {
        return p.unfitReason != null || p.needsNewerWave;
    }

    /** 2 for an update, 1 for anything on the phone, 0 for not installed: the badge on a browse row's icon. */
    static int badge(Pkg p) {
        switch (p.state) {
            case UPDATE:
                return 2;
            case INSTALLED:
            case NEWER:
            case OFF:
            case PENDING_INSTALL:
                return 1;
            default:
                return 0;
        }
    }

    /** What the capsule says on a row of Installed, or null for none (a mod, Wave itself, nothing to open). */
    static String capsule(Pkg p, Predicate<Pkg> canOpen) {
        if (unfit(p) && p.state == State.NONE) return null;
        switch (p.state) {
            case NONE:
                return "GET";
            case UPDATE:
                // An update this phone can't take: the row is as up to date as it can be.
                if (unfit(p)) return canOpen.test(p) ? "OPEN" : null;
                return "UPDATE";
            case INSTALLED:
            case NEWER:
                return canOpen.test(p) ? "OPEN" : null;
            default:
                return null;
        }
    }

    /** What pressing a package's capsule does anywhere in the app. */
    static Press press(Pkg p, Predicate<Pkg> canOpen) {
        if (unfit(p) && p.state == State.NONE) return Press.NOTHING;
        switch (p.state) {
            case NONE:
                return Press.GET;
            case UPDATE:
                if (unfit(p)) return canOpen.test(p) ? Press.OPEN : Press.DETAILS;
                return Press.UPDATE;
            case INSTALLED:
            case NEWER:
                return canOpen.test(p) ? Press.OPEN : Press.DETAILS;
            default:
                return Press.DETAILS;
        }
    }

    /** A press on a row of Installed: its capsule, and the details for a row without one. */
    static Press pressInstalledRow(Pkg p, Predicate<Pkg> canOpen) {
        String word = capsule(p, canOpen);
        if ("OPEN".equals(word)) return Press.OPEN;
        if (word != null && p.state != State.OFF) return press(p, canOpen);
        return Press.DETAILS;
    }

    /**
     * The package menu: the capsule's action, Wave's update when the package needs
     * a newer Wave and a source offers one, details, Turn On, Reinstall, Remove.
     * {@code isSelf} is Wave itself, which is never removed from here.
     */
    static List<Item> menu(Pkg p, boolean withDetails, Predicate<Pkg> canOpen, boolean isSelf, boolean waveUpdateOffered) {
        return menu(p, withDetails, canOpen, isSelf, waveUpdateOffered, false);
    }

    /** {@link #menu}, with See Version History after the details when the source lists any versions ({@code hasHistory}). */
    static List<Item> menu(Pkg p, boolean withDetails, Predicate<Pkg> canOpen, boolean isSelf, boolean waveUpdateOffered,
                           boolean hasHistory) {
        List<Item> items = new ArrayList<>();
        String word = capsule(p, canOpen);
        if (word != null) {
            if (word.equals("OPEN")) items.add(Item.OPEN);
            else if (!unfit(p)) items.add(word.equals("GET") ? Item.GET : Item.UPDATE);
        }
        if (p.needsNewerWave && waveUpdateOffered) items.add(Item.UPDATE_WAVE);
        if (withDetails) items.add(Item.DETAILS);
        if (hasHistory) items.add(Item.HISTORY);
        if (p.state == State.OFF) items.add(Item.TURN_ON);
        boolean installed = p.state != State.NONE && p.state != State.PENDING_REMOVAL;
        // Reinstalling downloads from a source; one kept only by its receipt has none.
        if (installed && p.sourceId != null && p.state != State.PENDING_INSTALL && p.state != State.UPDATE && !unfit(p)) {
            items.add(Item.REINSTALL);
        }
        if (installed && !isSelf) items.add(Item.REMOVE);
        return Collections.unmodifiableList(items);
    }

    /** Wave's own update, when a source offers one this phone can take; else null. */
    static Pkg waveUpdate(WaveData.Snapshot snapshot, String selfId) {
        if (snapshot == null) return null;
        for (Pkg u : snapshot.updates) if (u.id.equals(selfId)) return u;
        return null;
    }

    /** Whether a package has an app to open: not a mod, not Wave itself, and the phone has a launcher for it (asked last). */
    static boolean openable(Pkg p, String selfId, java.util.function.BooleanSupplier hasLauncher) {
        return !p.isModule && !p.id.equals(selfId) && hasLauncher.getAsBoolean();
    }

    /** Ids Update All takes: the updates this phone can install. */
    static List<String> updatable(List<Pkg> updates) {
        List<String> ids = new ArrayList<>();
        for (Pkg p : updates) if (!unfit(p)) ids.add(p.id);
        return ids;
    }

    /** "What's New" goes right after "Details" in a menu that has it, else first. */
    static int whatsNewAt(List<String> titles) {
        return titles.indexOf("Details") + 1;
    }

    // The details page.

    /** The capsule of a details page: GET, UPDATE or OPEN; none for a mod or Wave once installed. */
    static String detailsCapsule(Pkg p, Predicate<Pkg> canOpen) {
        switch (p.state) {
            case NONE:
                return "GET";
            case UPDATE:
                return "UPDATE";
            case INSTALLED:
            case NEWER:
                return canOpen.test(p) ? "OPEN" : null;
            default:
                return null;
        }
    }

    /** A GET or UPDATE this phone can't take: drawn grey, and OK does nothing. */
    static boolean greyed(Pkg p) {
        return unfit(p) && (p.state == State.NONE || p.state == State.UPDATE);
    }

    /** What OK does on a details page; MENU for an installed mod, which has nothing to press. */
    enum Ok { NOTHING, OPEN, PRESS, MENU }

    static Ok detailsOk(Pkg p, Predicate<Pkg> canOpen) {
        if (greyed(p)) return Ok.NOTHING;
        String word = detailsCapsule(p, canOpen);
        if ("OPEN".equals(word)) return Ok.OPEN;
        return word != null ? Ok.PRESS : Ok.MENU;
    }
}
