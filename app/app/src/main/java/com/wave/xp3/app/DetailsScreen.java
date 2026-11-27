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
import com.xp3.common.ui.Markdown;

import java.util.Collections;

/**
 * A package's details: the header with its icon, name, author and the
 * capsule; lines for what to know (a mod needs a reboot,
 * why it can't be installed); the description; What's New; Information.
 *
 * The action (Get, Update, Open) is the page's one focus stop, so it is drawn
 * selected and OK presses it wherever the page is scrolled; Up/Down scroll the page. Remove
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
        // Kept by a receipt, a package has no author and maybe no source name: then no line at all.
        String byline = Pages.byline(p, d);
        if (byline != null) {
            TextView by = Kit.line(app, byline, 12, Look.MUTED);
            LinearLayout.LayoutParams bl = Kit.wrap();
            bl.topMargin = Kit.dp(app, 1);
            texts.addView(by, bl);
        }
        LinearLayout capLine = new LinearLayout(app);
        capLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
        String word = word(p);
        boolean greyed = greyed(p);
        if (word != null) {
            capLine.addView(Kit.capsule(app, word, !greyed, greyed));
        } else if (p.state != State.NONE) {
            TextView done = Look.text(app, Pages.shownWord(stateWord(p)), 13, Look.MUTED, Look.MEDIUM);
            done.setPadding(0, Kit.dp(app, 7), 0, Kit.dp(app, 7));
            capLine.addView(done);
        }
        TextView after = Kit.line(app, afterText(p, d), 12, Look.MUTED);
        LinearLayout.LayoutParams al = Kit.wrap();
        al.leftMargin = Kit.dp(app, word != null ? 10 : 0);
        if (word == null && p.state != State.NONE) al.leftMargin = Kit.dp(app, 6);
        capLine.addView(after, al);
        LinearLayout.LayoutParams cl = Kit.wrap();
        cl.topMargin = Kit.dp(app, 1);
        texts.addView(capLine, cl);
        LinearLayout.LayoutParams tl = Kit.weight();
        tl.leftMargin = Kit.dp(app, 10);
        head.addView(texts, tl);
        column.addView(head);
        column.addView(Kit.hair(app));

        // What to know.
        // A newer format is said once, as a note (its reason is the same words), and points at Wave's own update when there is one.
        for (Pages.Note note : Pages.notes(p, d, app.waveUpdate() != null)) {
            column.addView(line(glyph(note.icon), Look.color(note.iconTone), note.text, Look.color(note.textTone)));
        }

        boolean updateFirst = Pages.changesFirst(p);
        if (!updateFirst) description(d);
        if (!d.changes.isEmpty()) {
            column.addView(Kit.heading(app, "What's New", true));
            int shown = 0;
            for (WaveData.Change c : d.changes) {
                if (shown++ == Pages.MAX_CHANGES) break;
                LinearLayout sub = new LinearLayout(app);
                sub.setPadding(Kit.dp(app, 10), 0, Kit.dp(app, 10), Kit.dp(app, 3));
                sub.addView(Kit.line(app, c.version != null ? "Version " + c.version : "", 12, Look.MUTED), Kit.weight());
                if (c.date != null) sub.addView(Kit.line(app, Format.date(c.date), 12, Look.MUTED));
                column.addView(sub);
                if (c.text != null) column.addView(paragraph(app, c.text));
            }
        }
        if (updateFirst) description(d);

        column.addView(Kit.heading(app, "Information", true));
        java.util.List<String[]> facts = Pages.facts(p, d);
        for (int i = 0; i < facts.size(); i++) {
            column.addView(Rows.fact(app, facts.get(i)[0], facts.get(i)[1], i == facts.size() - 1));
        }
        column.addView(Kit.gap(app, 8));
        scroll.post(() -> scroll.scrollTo(0, y));
        app.redressBar();
    }

    private void description(Detail d) {
        String text = Pages.description(d);
        if (text == null) return;
        boolean first = true;
        for (Pages.Block block : Pages.descriptionBlocks(text)) {
            if (block.table) {
                new Markdown(app, 10, 13).into(column, block.text);
            } else {
                TextView p = paragraph(app, block.text);
                p.setPadding(p.getPaddingLeft(), Kit.dp(app, first ? 4 : 2), p.getPaddingRight(), p.getPaddingBottom());
                column.addView(p);
            }
            first = false;
        }
    }

    private static int glyph(Pages.Icon icon) {
        return icon == Pages.Icon.WARN ? Glyph.WARN : icon == Pages.Icon.REBOOT ? Glyph.REBOOT : Glyph.INFO;
    }

    private String word(Pkg p) {
        return Actions.detailsCapsule(p, app::canOpen);
    }

    private static String stateWord(Pkg p) {
        return Pages.stateWord(p);
    }

    private static String afterText(Pkg p, Detail d) {
        return Pages.afterText(p, d);
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
        return Actions.greyed(p);
    }

    private void ok() {
        Pkg p = pkg;
        switch (Actions.detailsOk(p, app::canOpen)) {
            case OPEN:
                app.open(p);
                break;
            case PRESS:
                app.press(p);
                break;
            case MENU:
                // An installed mod: nothing to press, so OK opens the menu.
                app.openMenu();
                break;
            default:
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
