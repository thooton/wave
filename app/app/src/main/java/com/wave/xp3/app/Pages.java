package com.wave.xp3.app;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Detail;
import com.wave.xp3.data.WaveData.Pkg;
import com.wave.xp3.data.WaveData.Snapshot;
import com.wave.xp3.data.WaveData.Src;
import com.wave.xp3.data.WaveData.State;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * What the pages say, as plain values: the text of a row, which note a details
 * page shows, which message stands in for an empty list. The screens turn a
 * {@link Tone} into a colour and a {@link Line} into a TextView; nothing here
 * touches a View, so the wording and the precedence between messages are
 * checked on a desktop JVM (AppSelfTest).
 */
final class Pages {
    private Pages() {}

    enum Tone { TEXT, MUTED, RED, BLUE, GREEN, ORANGE }

    enum Icon { WARN, INFO, REBOOT }

    /** A line of text and the colour it is drawn in. */
    static final class Line {
        final String text;
        final Tone tone;

        Line(String text, Tone tone) {
            this.text = text;
            this.tone = tone;
        }

        @Override
        public String toString() {
            return tone + ":" + text;
        }
    }

    /** What stands in for a list that has nothing to show. */
    static final class Message {
        /** Null for a spinner. */
        final String title, body, button;
        final boolean spinner;

        Message(String title, String body, String button, boolean spinner) {
            this.title = title;
            this.body = body;
            this.button = button;
            this.spinner = spinner;
        }
    }

    static Message error(String body) {
        return new Message("Cannot Load Sources", body, "Try Again", false);
    }

    // ------------------------------------------------------------------ Installed

    /** The line under a row of Installed. */
    static Line installedLine(Pkg p, boolean bootFailed) {
        switch (p.state) {
            case OFF:
                return new Line(bootFailed ? "Turned off: boot failed" : "Turned off", Tone.RED);
            case PENDING_INSTALL:
                return new Line("Starts after a reboot", Tone.BLUE);
            case PENDING_REMOVAL:
                return new Line("Removed after a reboot", Tone.BLUE);
            default:
                return new Line((p.isModule ? "System mod, " : "Version ") + (p.installedVersion != null ? p.installedVersion : p.version), Tone.MUTED);
        }
    }

    /** A pending or turned-off package has no capsule; the rest follow {@link Actions#capsule}. */
    static String installedCapsule(Pkg p, Predicate<Pkg> canOpen) {
        if (p.state == State.OFF || p.state == State.PENDING_INSTALL || p.state == State.PENDING_REMOVAL) return null;
        return Actions.capsule(p, canOpen);
    }

    /** The second line of an update's row: when it came out, else its version. */
    static String updateLine(Pkg p) {
        String when = p.newestChange != null && p.newestChange.date != null ? Format.date(p.newestChange.date) : null;
        return when != null ? when : "Version " + p.version;
    }

    static boolean hasNotes(Pkg p) {
        return p.newestChange != null && p.newestChange.text != null && !p.newestChange.text.isEmpty();
    }

    /** True when no source has been read yet (nothing refreshed, nothing listed). */
    static boolean nothingFetched(Snapshot s) {
        for (Src src : s.sources) if (src.refreshedAt > 0 || src.packageCount > 0) return false;
        return true;
    }

    /** The Installed page with no rows: what failed, what is loading, or that nothing is installed. */
    static Message installedEmpty(Snapshot s) {
        boolean unread = nothingFetched(s);
        if (s.firstLoadError != null && unread) return error(s.firstLoadError);
        if (unread) return new Message(null, null, null, true);
        return new Message("Nothing Installed", "What Wave installs is listed here.", null, false);
    }

    static String rebootText(List<String> names) {
        if (names.size() == 1) return names.get(0) + " starts after a reboot";
        return names.size() + " changes start after a reboot";
    }

    // ------------------------------------------------------------------ Sources

    static boolean anyRefreshing(Snapshot s) {
        for (Src src : s.sources) if (src.refreshing) return true;
        return false;
    }

    /** The Sources tab's message instead of its list, or null when the list shows. */
    static Message sourcesEmpty(Snapshot s) {
        return s.firstLoadError != null && nothingFetched(s) && !anyRefreshing(s) ? error(s.firstLoadError) : null;
    }

    /** The line under a source on the Sources tab. */
    static Line sourceRow(Src src) {
        if (src.refreshing) return new Line("Refreshing…", Tone.MUTED);
        if (src.error != null) return new Line(src.error, Tone.RED);
        if (src.needsNewerWave) return new Line("Needs a newer Wave", Tone.RED);
        return new Line(Format.shortUrl(src.url), Tone.MUTED);
    }

    /** A source page's header line. */
    static Line sourceHeader(Src src) {
        String by = src.maintainer != null ? src.maintainer : Format.shortUrl(src.url);
        String when = Format.refreshed(src.refreshedAt);
        String text = src.refreshing ? "Refreshing…" : src.error != null ? "Couldn't refresh: " + src.error
            : when != null ? by + ", " + when : by;
        return new Line(text, src.error != null && !src.refreshing ? Tone.RED : Tone.MUTED);
    }

    static String sourceTitle(String sourceId, Src src) {
        return sourceId == null ? "All Sources" : src != null ? src.name : "Source";
    }

    static String unreadableLabel(int n) {
        return n == 1 ? "1 package can't be read" : n + " packages can't be read";
    }

    static String unreadableText(List<String> lines, List<String> icons) {
        StringBuilder text = new StringBuilder(String.join("\n", lines));
        if (!icons.isEmpty()) text.append("\n\nIcons ignored:\n").append(String.join("\n", icons));
        return text.toString();
    }

    // ------------------------------------------------------------------ Wave

    /** Root access status at the top of the Wave tab. The tone applies to the icon. */
    static Line rootStatus(WaveData.Root root) {
        switch (root) {
            case OK: return new Line("Root access granted", Tone.GREEN);
            case NONE: return new Line("Root access not granted", Tone.ORANGE);
            default: return new Line("Checking root access…", Tone.MUTED);
        }
    }

    static String rootText(WaveData.Root root) {
        return root == WaveData.Root.OK ? "Yes" : root == WaveData.Root.NONE ? "No" : "Checking…";
    }

    /** The Device facts; the phone's own model and release stand in for what the snapshot lacks. */
    static List<String[]> phoneFacts(Snapshot s, String ownModel, String ownRelease) {
        WaveData.Facts f = s.facts;
        String model = f != null && f.model != null ? f.model : ownModel;
        String android = f != null && f.android != null ? f.android : ownRelease;
        if (f != null && f.build != null) android += ", build " + f.build;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Model", model});
        rows.add(new String[]{"Android", android});
        rows.add(new String[]{"Root", f != null && f.root != null ? f.root : rootText(s.root)});
        rows.add(new String[]{"Storage", f != null && f.storageFree != null ? f.storageFree : ""});
        return rows;
    }

    // ------------------------------------------------------------------ Search

    enum Results { BLANK, NONE_FOUND, FOUND }

    /** Null results are a blank query (or a failed one); an empty list is "No Results". */
    static Results results(List<Pkg> results) {
        return results == null ? Results.BLANK : results.isEmpty() ? Results.NONE_FOUND : Results.FOUND;
    }

    // ------------------------------------------------------------------ Details

    static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    /** A line of "what to know" on a details page. */
    static final class Note {
        final Icon icon;
        final Tone iconTone, textTone;
        final String text;

        Note(Icon icon, Tone iconTone, String text, Tone textTone) {
            this.icon = icon;
            this.iconTone = iconTone;
            this.text = text;
            this.textTone = textTone;
        }

        @Override
        public String toString() {
            return icon + ":" + text;
        }
    }

    /**
     * What to know about a package, in order. A newer format is said once (its
     * reason is the same words) and points at Wave's own update when a source
     * offers one.
     */
    static List<Note> notes(Pkg p, Detail d, boolean waveUpdateOffered) {
        List<Note> notes = new ArrayList<>();
        if (p.unfitReason != null && !p.needsNewerWave) notes.add(new Note(Icon.WARN, Tone.ORANGE, p.unfitReason, Tone.RED));
        if (p.needsNewerWave) {
            notes.add(new Note(Icon.INFO, Tone.MUTED, waveUpdateOffered ? "Update Wave to install this" : "Needs a newer Wave", Tone.TEXT));
        }
        if (p.state == State.UPDATE && Actions.unfit(p)) {
            notes.add(new Note(Icon.INFO, Tone.MUTED, "Version " + p.version + " can't be installed here", Tone.TEXT));
        }
        if (p.isModule) notes.add(new Note(Icon.REBOOT, Tone.BLUE, "System mod, needs a reboot", Tone.TEXT));
        if (p.state == State.OFF) {
            notes.add(new Note(Icon.WARN, Tone.ORANGE, "Turned off: the phone didn't finish starting with it", Tone.RED));
        }
        if (p.state == State.PENDING_INSTALL) notes.add(new Note(Icon.REBOOT, Tone.BLUE, "Starts after a reboot", Tone.TEXT));
        if (p.state == State.PENDING_REMOVAL) notes.add(new Note(Icon.REBOOT, Tone.BLUE, "Removed after a reboot", Tone.TEXT));
        for (String r : d.replaces) notes.add(new Note(Icon.INFO, Tone.MUTED, "Replaces " + r, Tone.TEXT));
        if (d.firmwareNote) notes.add(new Note(Icon.INFO, Tone.MUTED, "Not tested on this phone's firmware", Tone.TEXT));
        return notes;
    }

    /** The line under the name: the author, else the source; none for a package kept by a receipt. */
    static String byline(Pkg p, Detail d) {
        return !empty(d.author) ? d.author : !empty(p.sourceName) ? p.sourceName : null;
    }

    /** The word beside the capsule when there is none ("INSTALLED", "TURNED OFF"). */
    static String stateWord(Pkg p) {
        switch (p.state) {
            case OFF:
                return "TURNED OFF";
            case PENDING_INSTALL:
            case PENDING_REMOVAL:
                return "AFTER REBOOT";
            default:
                return "INSTALLED";
        }
    }

    /** The grey text after the capsule: the change of version, the size to get, or the version it is. */
    static String afterText(Pkg p, Detail d) {
        if (p.state == State.UPDATE && p.installedVersion != null) return p.installedVersion + " → " + p.version;
        if (p.state == State.NONE) {
            String size = Format.size(d.size);
            return size != null ? size : "Version " + p.version;
        }
        return "Version " + (p.installedVersion != null ? p.installedVersion : p.version);
    }

    /** The paragraph under the notes: the description, else the summary, else none. */
    static String description(Detail d) {
        if (!empty(d.description)) return d.description;
        return !empty(d.pkg.summary) ? d.pkg.summary : null;
    }

    /** What's New shows at most this many versions. */
    static final int MAX_CHANGES = 4;

    /** An update puts What's New before the description; everything else after. */
    static boolean changesFirst(Pkg p) {
        return p.state == State.UPDATE;
    }

    /** The Information table. */
    static List<String[]> facts(Pkg p, Detail d) {
        String size = Format.size(d.size);
        String version = p.installedVersion != null && p.state == State.UPDATE ? p.installedVersion + " → " + p.version : p.version;
        List<String[]> facts = new ArrayList<>();
        facts.add(new String[]{"Version", version});
        if (size != null) facts.add(new String[]{"Size", size});
        // Kept by a receipt after its source went, a package has no section (and Wave itself may have no source).
        if (!empty(p.sourceName)) facts.add(new String[]{"Source", p.sourceName});
        if (!empty(p.sectionName)) facts.add(new String[]{"Section", p.sectionName});
        if (!empty(d.author)) facts.add(new String[]{"Author", d.author});
        if (!d.depends.isEmpty()) facts.add(new String[]{"Needs", String.join(", ", d.depends)});
        if (!d.conflicts.isEmpty()) facts.add(new String[]{"Conflicts", String.join(", ", d.conflicts)});
        facts.add(new String[]{"Id", p.id});
        return facts;
    }

    // ------------------------------------------------------------------ Confirm and queue

    static String confirmHeading(List<WaveData.PlanItem> items, boolean removing) {
        if (removing) return "Remove";
        boolean allUpdates = true, allReinstall = true;
        for (WaveData.PlanItem item : items) {
            if (item.action != WaveData.Action.UPDATE) allUpdates = false;
            if (item.action != WaveData.Action.REINSTALL) allReinstall = false;
        }
        return allUpdates ? "Update" : allReinstall ? "Reinstall" : "Install";
    }

    static Line confirmLine(WaveData.PlanItem item, boolean removing) {
        Pkg p = item.pkg;
        if (item.reason != null) return new Line(item.reason, removing ? Tone.MUTED : Tone.BLUE);
        if (removing) return new Line("Version " + (p.installedVersion != null ? p.installedVersion : p.version), Tone.MUTED);
        if (item.action == WaveData.Action.UPDATE && p.installedVersion != null) {
            return new Line(p.installedVersion + " → " + p.version, Tone.MUTED);
        }
        return new Line(p.version, Tone.MUTED);
    }

    /**
     * An action's word as the screen shows it: "GET" is "Get", "TURNED OFF"
     * is "Turned Off". The words themselves (Actions, stateWord) stay in
     * capitals, as the tests and menus know them.
     */
    static String shownWord(String word) {
        if (word == null) return null;
        StringBuilder b = new StringBuilder(word.length());
        boolean start = true;
        for (char ch : word.toCharArray()) {
            b.append(start ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
            start = ch == ' ';
        }
        return b.toString();
    }

    /** What the user asked for in a plan: the items no other item pulled in. */
    static List<WaveData.PlanItem> confirmAsked(WaveData.PlanView plan) {
        List<WaveData.PlanItem> asked = new ArrayList<>();
        for (WaveData.PlanItem item : plan.items) if (item.reason == null) asked.add(item);
        return asked.isEmpty() ? plan.items : asked;
    }

    /** The sheet's main action: Install, Update, Reinstall or Remove. */
    static String confirmVerb(WaveData.PlanView plan) {
        List<WaveData.PlanItem> asked = confirmAsked(plan);
        for (WaveData.PlanItem item : asked) if (item.action != WaveData.Action.REMOVE) {
            List<WaveData.PlanItem> going = new ArrayList<>();
            for (WaveData.PlanItem a : asked) if (a.action != WaveData.Action.REMOVE) going.add(a);
            return confirmHeading(going, false);
        }
        return "Remove";
    }

    /** Whether the sheet's action only takes things away: then it is red and the focus starts on Cancel. */
    static boolean confirmDestroys(WaveData.PlanView plan) {
        return "Remove".equals(confirmVerb(plan));
    }

    /** "Install Canvas?", "Update 3 Packages?". */
    static String confirmTitle(WaveData.PlanView plan) {
        List<WaveData.PlanItem> asked = confirmAsked(plan);
        String what = asked.size() == 1 ? asked.get(0).pkg.name : asked.size() + " Packages";
        return confirmVerb(plan) + " " + what + "?";
    }

    /** The grey line under the title of a one-package sheet: the version, or the change of version. */
    static String confirmSub(WaveData.PlanView plan) {
        List<WaveData.PlanItem> asked = confirmAsked(plan);
        if (asked.size() != 1) return null;
        WaveData.PlanItem item = asked.get(0);
        Pkg p = item.pkg;
        if (item.action == WaveData.Action.REMOVE) {
            return "Version " + (p.installedVersion != null ? p.installedVersion : p.version);
        }
        if (item.action == WaveData.Action.UPDATE && p.installedVersion != null) return p.installedVersion + " → " + p.version;
        return "Version " + p.version;
    }

    /** One line of the sheet: plain text around a package name (or size) drawn heavier. */
    static final class Fact {
        final String before, name, after;
        final Tone tone;

        Fact(String before, String name, String after, Tone tone) {
            this.before = before;
            this.name = name;
            this.after = after;
            this.tone = tone;
        }

        @Override
        public String toString() {
            return tone + ":" + before + "[" + name + "]" + after;
        }
    }

    /**
     * What the sheet says between its title and its actions: each package
     * asked for when there are several, what comes along or goes and why,
     * the download, and whether a reboot follows.
     */
    static List<Fact> confirmFacts(WaveData.PlanView plan) {
        List<Fact> facts = new ArrayList<>();
        List<WaveData.PlanItem> asked = confirmAsked(plan);
        boolean one = asked.size() == 1;
        if (!one) {
            for (WaveData.PlanItem item : asked) {
                boolean removing = item.action == WaveData.Action.REMOVE;
                facts.add(new Fact("", item.pkg.name, " " + confirmLine(item, removing).text, Tone.TEXT));
            }
        }
        for (WaveData.PlanItem item : plan.items) {
            if (asked.contains(item)) continue;
            boolean removing = item.action == WaveData.Action.REMOVE;
            String why = item.reason == null ? "" : ", " + Character.toLowerCase(item.reason.charAt(0)) + item.reason.substring(1);
            if (removing) facts.add(one ? new Fact("Replaces ", item.pkg.name, "", Tone.TEXT) : new Fact("Removes ", item.pkg.name, why, Tone.TEXT));
            else facts.add(new Fact("Also installs ", item.pkg.name, one ? "" : why, Tone.TEXT));
        }
        String size = Format.size(plan.downloadBytes);
        if (size != null) facts.add(new Fact("", size, " to download", Tone.TEXT));
        if (plan.needsReboot) facts.add(new Fact("Needs a reboot to finish", "", "", Tone.ORANGE));
        return facts;
    }

    enum Right { NONE, TICK, SPINNER }

    /** A row of the queue: what it says, how it is drawn and what stands on its right. */
    static final class QueueLine {
        final String text;
        final Tone tone;
        final boolean dim;
        final Right right;
        /** For a spinner: 0 to 100, or -1 when unknown. */
        final int percent;

        QueueLine(String text, Tone tone, boolean dim, Right right, int percent) {
            this.text = text;
            this.tone = tone;
            this.dim = dim;
            this.right = right;
            this.percent = percent;
        }
    }

    static QueueLine queueLine(WaveData.QueueItem item, boolean finished) {
        String text = item.text;
        switch (item.step) {
            case DONE:
                return new QueueLine(text != null ? text : "Done", Tone.GREEN, false, Right.TICK, -1);
            case FAILED:
                return new QueueLine(text != null ? text : "Not installed", Tone.RED, false, Right.NONE, -1);
            case SKIPPED:
                return new QueueLine(text != null ? text : "Skipped", Tone.MUTED, false, Right.NONE, -1);
            case WAITING:
                return new QueueLine(text != null ? text : "Waiting", Tone.MUTED, true, finished ? Right.NONE : Right.SPINNER, -1);
            case DOWNLOADING: {
                String of = item.total > 0 ? ", " + Format.megabytes(item.bytes) + " of " + Format.size(item.total) : "";
                return new QueueLine("Downloading" + of, Tone.BLUE, false, Right.SPINNER, item.percent);
            }
            default:
                return new QueueLine(text != null ? text : stepWord(item.step), Tone.BLUE, false, Right.SPINNER, -1);
        }
    }

    private static String stepWord(WaveData.Step step) {
        switch (step) {
            case CHECKING:
                return "Checking";
            case INSTALLING:
                return "Installing";
            case REMOVING:
                return "Removing";
            default:
                return "";
        }
    }

    static String queueTitle(WaveData.QueueView q, boolean started) {
        return q != null && q.finished && started ? "Done" : "Installing";
    }

    /** "Weather wasn't installed" or "wasn't removed", by what the step said. */
    static String failureTitle(WaveData.QueueItem item) {
        String verb = item.text != null && item.text.toLowerCase(java.util.Locale.US).contains("remov") ? "removed" : "installed";
        return item.pkg.name + " wasn't " + verb;
    }

    static String failureMessage(WaveData.QueueItem item) {
        return item.failure != null ? item.failure : "Something went wrong.";
    }
}
