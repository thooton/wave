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

import java.util.List;

/**
 * Every version a package's source lists, from the menu's See Version
 * History: the package's icon and name over how many versions, then each
 * version with its date and what changed, newest first. Details shows only
 * the newest few under What's New. The version on the phone is marked
 * Installed. Like Details, the page is one focus stop: Up/Down scroll it.
 */
final class VersionHistoryScreen extends Screen {

    private final String id;
    private final String sourceId;
    private ScrollView scroll;
    private LinearLayout column;

    VersionHistoryScreen(WaveActivity app, String id, String sourceId) {
        super(app);
        this.id = id;
        this.sourceId = sourceId;
    }

    @Override
    String title() {
        return "Version History";
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
        if (d == null || d.changes.isEmpty()) {
            column.addView(Kit.message(app, null, "No Versions Listed", "The source doesn't list this package's versions.", null, null),
                Kit.fill(Kit.dp(app, 200)));
            return;
        }
        Pkg p = d.pkg;
        List<Pages.Version> versions = Pages.history(p, d);

        LinearLayout head = new LinearLayout(app);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(Kit.dp(app, 10), Kit.dp(app, 10), Kit.dp(app, 10), Kit.dp(app, 8));
        ImageView icon = Kit.iconView(app, 40);
        app.icons.into(icon, Rows.icon(app, p), 40);
        head.addView(icon);
        head.addView(Kit.texts(app, p.name, Pages.historyCount(versions.size()), Look.MUTED, 10));
        column.addView(head);
        column.addView(Kit.hair(app));

        for (int i = 0; i < versions.size(); i++) {
            Pages.Version v = versions.get(i);
            LinearLayout line = new LinearLayout(app);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            line.setPadding(Kit.dp(app, 10), Kit.dp(app, i == 0 ? 4 : 8), Kit.dp(app, 10), Kit.dp(app, 3));
            TextView title = Kit.line(app, v.title, 13, Look.TEXT);
            title.setTypeface(Look.MEDIUM);
            line.addView(title);
            if (v.installed) {
                TextView mark = Kit.line(app, "Installed", 12, Look.GREEN);
                LinearLayout.LayoutParams ml = Kit.wrap();
                ml.leftMargin = Kit.dp(app, 6);
                line.addView(mark, ml);
            }
            line.addView(new View(app), Kit.weight());
            if (v.date != null) line.addView(Kit.line(app, v.date, 12, Look.MUTED));
            column.addView(line);
            if (v.text != null) column.addView(paragraph(app, v.text));
            if (i < versions.size() - 1) column.addView(Kit.hair(app));
        }
        column.addView(Kit.gap(app, 8));
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    @Override
    boolean onKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                scroll.smoothScrollBy(0, Kit.dp(app, 34) * (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1));
            }
            return true;
        }
        // Nothing to press here: OK is claimed so it doesn't fall through to the bar.
        return Feed.isOk(key);
    }
}
