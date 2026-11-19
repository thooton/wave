package com.wave.xp3.app;

import android.view.View;
import android.widget.TextView;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Pkg;

import java.util.ArrayList;
import java.util.List;

/**
 * Installed: the Updater's screen. Updates first (with what's new) and Update
 * All on the bar, then everything else under Up to Date. A Reboot row heads it
 * while a change waits for one. OK presses the row's capsule (UPDATE, OPEN);
 * a row without one (a mod, Wave itself) opens its details.
 */
final class InstalledScreen extends Screen {


    InstalledScreen(WaveActivity app) {
        super(app);
    }

    @Override
    String title() {
        return "Installed";
    }

    @Override
    View build() {
        feed = new Feed(app);
        return feed.root;
    }

    @Override
    void bar(Bar bar) {
        if (!app.snapshot.updates.isEmpty()) bar.setLink(1, "Update All", true).setOnClickListener(v -> updateAll());
    }

    private void updateAll() {
        List<String> ids = new ArrayList<>();
        for (Pkg p : app.snapshot.updates) if (!Rows.unfit(p)) ids.add(p.id);
        if (!ids.isEmpty()) app.plan(ids, WaveData.Action.UPDATE);
    }

    private View.OnClickListener press(final Pkg p) {
        return v -> {
            String word = Rows.capsuleWord(app, p);
            if ("OPEN".equals(word)) app.open(p);
            else if (word != null && p.state != WaveData.State.OFF) app.press(p);
            else app.details(p, null);
        };
    }

    @Override
    void update(WaveData.Snapshot s) {
        Mark mark = feed.clear();
        if (!s.rebootPending.isEmpty()) feed.add(Rows.reboot(app, Rows.rebootText(s.rebootPending)));
        if (!s.updates.isEmpty()) {
            feed.add(Kit.heading(app, "Updates", false));
            for (int i = 0; i < s.updates.size(); i++) {
                Pkg p = s.updates.get(i);
                feed.add(Rows.updateLine(app, p, press(p), i == s.updates.size() - 1));
            }
        }
        if (!s.upToDate.isEmpty()) {
            feed.add(Kit.heading(app, "Up to Date", false));
            for (int i = 0; i < s.upToDate.size(); i++) {
                Pkg p = s.upToDate.get(i);
                feed.add(Rows.installedLine(app, p, press(p), i == s.upToDate.size() - 1));
            }
        }
        if (s.updates.isEmpty() && s.upToDate.isEmpty()) {
            boolean unread = SourcesScreen.nothingFetched(s);
            View message = s.firstLoadError != null && unread
                ? Kit.message(app, null, "Cannot Load Sources", s.firstLoadError, null, "Try Again")
                : unread ? Kit.message(app, Ring.spinnerBox(app), null, null, null, null)
                : Kit.message(app, null, "Nothing Installed", "What Wave installs is listed here.", null, null);
            TextView retry = Kit.messageButton(message);
            if (retry != null) retry.setOnClickListener(v -> app.data.refreshAll());
            if (s.rebootPending.isEmpty()) {
                feed.message(message);
                return;
            }
            message.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1, Kit.dp(app, 180)));
            feed.add(message);
        }
        feed.add(Kit.gap(app, 6));
        feed.restore(mark, null);
    }

    @Override
    void menu(Menu menu) {
        View focus = app.getCurrentFocus();
        if (focus instanceof Kit.Row && ((Kit.Row) focus).about instanceof Pkg) {
            app.packageMenu(menu, (Pkg) ((Kit.Row) focus).about, true, null);
            Pkg p = (Pkg) ((Kit.Row) focus).about;
            if (p.newestChange != null) {
                // After Details, as the mockup's menu has it.
                int at = menu.titles.indexOf("Details") + 1;
                menu.titles.add(at, "What's New");
                menu.actions.add(at, () -> app.details(p, null));
            }
        }
        int updates = app.snapshot.updates.size();
        if (updates > 1) menu.add("Update All (" + updates + ")", this::updateAll);
        if (!app.snapshot.rebootPending.isEmpty()) menu.add("Reboot", app::askReboot);
        menu.add("Refresh", () -> {
            app.data.recheckPhone();
            app.data.refreshAll();
        });
    }
}
