package com.wave.xp3.app;

import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Detail;
import com.wave.xp3.data.WaveData.Pkg;
import com.wave.xp3.data.WaveData.State;

import java.util.Collections;

/**
 * A package's details: the header with its icon, name, author and the
 * capsule; lines for what to know (a mod needs a reboot, what it replaces,
 * why it can't be installed); the description; What's New; Information.
 *
 * The capsule is the page's one focus stop, so it is drawn pressed and OK
 * presses it wherever the page is scrolled; Up/Down scroll the page. Remove
 * and Reinstall are in the menu only.
 */
final class DetailsScreen extends Screen {

    private final String id;
    private final String sourceId;
    private ScrollView scroll;
    private LinearLayout column;
    private Pkg pkg;

    DetailsScreen(WaveActivity app, String id, String sourceId) {
        super(app);
        this.id = id;
        this.sourceId = sourceId;
    }

    @Override
    String title() {
        return "Details";
    }

    @Override
    View build() {
        scroll = new ScrollView(app);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        column = new LinearLayout(app);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column);
        return scroll;
    }

    @Override
    View firstFocus() {
        return scroll;
    }

    private Detail detail(WaveData.Snapshot s) {
        Detail d = sourceId != null ? s.detail(sourceId, id) : null;
        return d != null ? d : s.detail(id);
    }

    @Override
    void update(WaveData.Snapshot s) {
        int y = scroll.getScrollY();
        column.removeAllViews();
        Detail d = detail(s);
        if (d == null) {
            pkg = null;
            column.addView(Kit.message(app, null, "Not Available", "No source lists this package now.", null, null),
                Kit.fill(Kit.dp(app, 200)));
            return;
        }
        pkg = d.pkg;
        Pkg p = d.pkg;

        // The header.
        LinearLayout head = new LinearLayout(app);
        head.setPadding(Kit.dp(app, 10), Kit.dp(app, 10), Kit.dp(app, 10), Kit.dp(app, 8));
        ImageView icon = Kit.iconView(app, 56);
        app.icons.into(icon, Rows.icon(app, p), 56);
        head.addView(icon);
        LinearLayout texts = new LinearLayout(app);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = Kit.line(app, p.name, 16, Look.TEXT);
        name.setTypeface(Look.MEDIUM);
        texts.addView(name);
        TextView by = Kit.line(app, d.author != null ? d.author : p.sourceName != null ? p.sourceName : "", 12, Look.MUTED);
        LinearLayout.LayoutParams bl = Kit.wrap();
        bl.topMargin = Kit.dp(app, 1);
        texts.addView(by, bl);
        LinearLayout capLine = new LinearLayout(app);
        capLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
        String word = word(p);
        boolean greyed = greyed(p);
        if (word != null) {
            capLine.addView(Kit.capsule(app, word, !greyed, greyed));
        } else if (p.state != State.NONE) {
            TextView done = Look.text(app, stateWord(p), 11.5f, Look.MUTED, Look.MEDIUM);
            capLine.addView(done);
        }
        TextView after = Kit.line(app, afterText(p, d), 11.5f, Look.MUTED);
        LinearLayout.LayoutParams al = Kit.wrap();
        al.leftMargin = Kit.dp(app, word != null ? 6 : 0);
        if (word == null && p.state != State.NONE) al.leftMargin = Kit.dp(app, 6);
        capLine.addView(after, al);
        LinearLayout.LayoutParams cl = Kit.wrap();
        cl.topMargin = Kit.dp(app, 7);
        texts.addView(capLine, cl);
        LinearLayout.LayoutParams tl = Kit.weight();
        tl.leftMargin = Kit.dp(app, 10);
        head.addView(texts, tl);
        column.addView(head);
        column.addView(Kit.hair(app));

        // What to know.
        if (p.unfitReason != null) column.addView(line(Glyph.WARN, Look.ORANGE, p.unfitReason, Look.RED));
        if (p.needsNewerWave) column.addView(line(Glyph.INFO, Look.MUTED, "Needs a newer Wave", Look.TEXT));
        if (p.state == State.UPDATE && Rows.unfit(p)) column.addView(line(Glyph.INFO, Look.MUTED, "Version " + p.version + " can't be installed here", Look.TEXT));
        if (p.isModule) column.addView(line(Glyph.REBOOT, Look.BLUE, "System mod, needs a reboot", Look.TEXT));
        if (p.state == State.OFF) column.addView(line(Glyph.WARN, Look.ORANGE, "Turned off: the phone didn't finish starting with it", Look.RED));
        if (p.state == State.PENDING_INSTALL) column.addView(line(Glyph.REBOOT, Look.BLUE, "Starts after a reboot", Look.TEXT));
        if (p.state == State.PENDING_REMOVAL) column.addView(line(Glyph.REBOOT, Look.BLUE, "Removed after a reboot", Look.TEXT));
        for (String r : d.replaces) column.addView(line(Glyph.INFO, Look.MUTED, "Replaces " + r, Look.TEXT));
        if (d.firmwareNote) column.addView(line(Glyph.INFO, Look.MUTED, "Not tested on this phone's firmware", Look.TEXT));

        boolean updateFirst = p.state == State.UPDATE;
        if (!updateFirst) description(d);
        if (!d.changes.isEmpty()) {
            column.addView(Kit.heading(app, "What's New", true));
            int shown = 0;
            for (WaveData.Change c : d.changes) {
                if (shown++ == 4) break;
                LinearLayout sub = new LinearLayout(app);
                sub.setPadding(Kit.dp(app, 10), 0, Kit.dp(app, 10), Kit.dp(app, 3));
                sub.addView(Kit.line(app, c.version != null ? "Version " + c.version : "", 12, Look.MUTED), Kit.weight());
                if (c.date != null) sub.addView(Kit.line(app, Rows.Format.date(c.date), 12, Look.MUTED));
                column.addView(sub);
                if (c.text != null) column.addView(paragraph(app, c.text));
            }
        }
        if (updateFirst) description(d);

        column.addView(Kit.heading(app, "Information", true));
        String size = Rows.Format.size(d.size);
        String version = p.installedVersion != null && p.state == State.UPDATE ? p.installedVersion + " → " + p.version : p.version;
        java.util.List<String[]> facts = new java.util.ArrayList<>();
        facts.add(new String[]{"Version", version});
        if (size != null) facts.add(new String[]{"Size", size});
        if (p.sourceName != null) facts.add(new String[]{"Source", p.sourceName});
        if (p.sectionName != null) facts.add(new String[]{"Section", p.sectionName});
        if (d.author != null) facts.add(new String[]{"Author", d.author});
        if (!d.depends.isEmpty()) facts.add(new String[]{"Needs", join(d.depends)});
        if (!d.conflicts.isEmpty()) facts.add(new String[]{"Conflicts", join(d.conflicts)});
        facts.add(new String[]{"Id", p.id});
        for (int i = 0; i < facts.size(); i++) {
            column.addView(Rows.fact(app, facts.get(i)[0], facts.get(i)[1], i == facts.size() - 1));
        }
        column.addView(Kit.gap(app, 8));
        scroll.post(() -> scroll.scrollTo(0, y));
        app.redressBar();
    }

    private void description(Detail d) {
        if (d.description == null || d.description.isEmpty()) {
            if (d.pkg.summary != null) {
                TextView p = paragraph(app, d.pkg.summary);
                p.setPadding(p.getPaddingLeft(), Kit.dp(app, 4), p.getPaddingRight(), p.getPaddingBottom());
                column.addView(p);
            }
            return;
        }
        TextView p = paragraph(app, d.description);
        p.setPadding(p.getPaddingLeft(), Kit.dp(app, 4), p.getPaddingRight(), p.getPaddingBottom());
        column.addView(p);
    }

    private static String join(java.util.List<String> items) {
        StringBuilder b = new StringBuilder();
        for (String s : items) {
            if (b.length() > 0) b.append(", ");
            b.append(s);
        }
        return b.toString();
    }

    /** The capsule on a details page: GET, UPDATE or OPEN; none for a mod or Wave once installed. */
    private String word(Pkg p) {
        switch (p.state) {
            case NONE:
                return "GET";
            case UPDATE:
                return "UPDATE";
            case INSTALLED:
            case NEWER:
                return app.canOpen(p) ? "OPEN" : null;
            default:
                return null;
        }
    }

    private static String stateWord(Pkg p) {
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

    private static String afterText(Pkg p, Detail d) {
        if (p.state == State.UPDATE && p.installedVersion != null) return p.installedVersion + " → " + p.version;
        if (p.state == State.NONE) {
            String size = Rows.Format.size(d.size);
            return size != null ? size : "Version " + p.version;
        }
        return "Version " + (p.installedVersion != null ? p.installedVersion : p.version);
    }

    private View line(int glyph, int glyphColor, String text, int textColor) {
        LinearLayout row = new LinearLayout(app);
        row.setPadding(Kit.dp(app, 10), Kit.dp(app, 6), Kit.dp(app, 10), Kit.dp(app, 6));
        row.addView(new Glyph(app, glyph, glyphColor));
        TextView t = Look.text(app, text, 12, textColor, Look.REGULAR);
        t.setLineSpacing(Kit.dp(app, 1), 1f);
        LinearLayout.LayoutParams lp = Kit.weight();
        lp.leftMargin = Kit.dp(app, 6);
        row.addView(t, lp);
        return row;
    }

    @Override
    boolean onKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int step = Kit.dp(app, 34) * (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                scroll.smoothScrollBy(0, step);
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_UP && pkg != null) ok();
            return true;
        }
        return false;
    }

    /** A GET or UPDATE this phone can't take: drawn grey, and OK does nothing. */
    private static boolean greyed(Pkg p) {
        return Rows.unfit(p) && (p.state == State.NONE || p.state == State.UPDATE);
    }

    private void ok() {
        Pkg p = pkg;
        if (greyed(p)) return;
        String word = word(p);
        if ("OPEN".equals(word)) {
            app.open(p);
        } else if (word != null) {
            app.press(p);
        } else {
            // An installed mod: nothing to press, so OK opens the menu.
            app.openMenu();
        }
    }

    @Override
    void menu(Menu menu) {
        if (pkg == null) return;
        final Pkg p = pkg;
        app.packageMenu(menu, p, false, sourceId);
    }

    static Pkg none() {
        return null;
    }

    static java.util.List<String> one(String id) {
        return Collections.singletonList(id);
    }
}
