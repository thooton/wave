package com.wave.xp3.app;

import android.view.View;

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

    private Page page;

    InstalledScreen(WaveActivity app) {
        super(app);
    }

    @Override
    String title() {
        return "Installed";
    }

    @Override
    View build() {
        page = new Page(app);
        return page.scroll;
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
            if (Rows.capsuleWord(app, p) != null && p.state != WaveData.State.OFF) app.press(p);
            else app.details(p, null);
        };
    }

    @Override
    void update(WaveData.Snapshot s) {
        Mark mark = page.clear();
        if (!s.rebootPending.isEmpty()) page.add(Rows.reboot(app, Rows.rebootText(s.rebootPending)));
        if (!s.updates.isEmpty()) {
            page.add(Kit.heading(app, "Updates", false));
            for (int i = 0; i < s.updates.size(); i++) {
                Pkg p = s.updates.get(i);
                Kit.Row row = Rows.update(app, p, press(p));
                if (i == s.updates.size() - 1) row.last();
                page.add(row);
            }
        }
        if (!s.upToDate.isEmpty()) {
            page.add(Kit.heading(app, "Up to Date", false));
            for (int i = 0; i < s.upToDate.size(); i++) {
                Pkg p = s.upToDate.get(i);
                Kit.Row row = Rows.installed(app, p, press(p));
                if (i == s.upToDate.size() - 1) row.last();
                page.add(row);
            }
        }
        if (s.updates.isEmpty() && s.upToDate.isEmpty()) {
            page.add(Kit.message(app, null, "Nothing Installed", "What Wave installs is listed here.", null, null));
        }
        page.add(Kit.gap(app, 6));
        page.restore(mark, null);
    }

    @Override
    void menu(Menu menu) {
        View focus = app.getCurrentFocus();
        if (focus instanceof Kit.Row && ((Kit.Row) focus).about instanceof Pkg) {
            app.packageMenu(menu, (Pkg) ((Kit.Row) focus).about, true, null);
            Pkg p = (Pkg) ((Kit.Row) focus).about;
            if (p.newestChange != null) {
                menu.add("What's New", () -> app.details(p, null));
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
