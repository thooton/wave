package com.wave.xp3.app;

import android.view.View;
import android.widget.TextView;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Pkg;

import java.util.List;

/**
 * Installed: updates first (with what's new), then everything else under Up to
 * Date. Update All is in the menu when multiple updates are available.
 * A Queue row heads the list while Add to Queue holds changes (OK
 * confirms them all), then a Reboot row while a change waits for one. OK presses the
 * row's capsule (UPDATE, OPEN);
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

    private void updateAll() {
        List<String> ids = Actions.updatable(app.snapshot.updates);
        if (!ids.isEmpty()) app.plan(ids, WaveData.Action.UPDATE);
    }

    private View.OnClickListener press(final Pkg p) {
        return v -> {
            switch (Actions.pressInstalledRow(p, app::canOpen)) {
                case OPEN: app.open(p); break;
                case DETAILS: app.details(p, null); break;
                default: app.press(p);
            }
        };
    }

    @Override
    void update(WaveData.Snapshot s) {
        Mark mark = feed.clear();
        if (!app.queued.isEmpty()) feed.add(Rows.queued(app, app.queued.size()));
        if (!s.rebootPending.isEmpty()) feed.add(Rows.reboot(app));
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
            Pages.Message what = Pages.installedEmpty(s);
            View message = what.spinner ? Kit.message(app, Ring.spinnerBox(app), null, null, null, null)
                : Kit.message(app, null, what.title, what.body, null, what.button);
            TextView retry = Kit.messageButton(message);
            if (retry != null) retry.setOnClickListener(v -> app.data.refreshAll());
            if (s.rebootPending.isEmpty() && app.queued.isEmpty()) {
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
        boolean onPackage = focus instanceof Kit.Row && ((Kit.Row) focus).about instanceof Pkg;
        // A package's menu ends with the queue's items; elsewhere they come first.
        if (!onPackage) app.queueMenu(menu);
        if (onPackage) {
            app.packageMenu(menu, (Pkg) ((Kit.Row) focus).about, true, null);
            Pkg p = (Pkg) ((Kit.Row) focus).about;
            if (p.newestChange != null) {
                // After Details, as the mockup's menu has it.
                int at = Actions.whatsNewAt(menu.titles);
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
