package com.wave.xp3.app;

import android.view.View;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Pkg;

import java.util.List;

/** A section's packages (or All Packages, with a null folder), as browse rows: OK opens the details. */
final class SectionScreen extends Screen {

    private final String sourceId;
    private final String folder;
    private final String name;

    SectionScreen(WaveActivity app, String sourceId, String folder, String name) {
        super(app);
        this.sourceId = sourceId;
        this.folder = folder;
        this.name = name;
    }

    @Override
    String title() {
        return name;
    }

    @Override
    View build() {
        feed = new Feed(app);
        return feed.root;
    }

    @Override
    void update(WaveData.Snapshot s) {
        Mark mark = feed.clear();
        List<Pkg> packages = s.packages(sourceId, folder);
        if (packages.isEmpty()) {
            feed.message(Kit.message(app, null, "No Packages", null, null, null));
            return;
        }
        for (int i = 0; i < packages.size(); i++) {
            final Pkg p = packages.get(i);
            feed.add(Rows.browseLine(app, p, v -> app.details(p, sourceId), i == packages.size() - 1));
        }
        feed.restore(mark, null);
    }

    @Override
    void menu(Menu menu) {
        View focus = app.getCurrentFocus();
        if (focus instanceof Kit.Row && ((Kit.Row) focus).about instanceof Pkg) {
            app.packageMenu(menu, (Pkg) ((Kit.Row) focus).about, true, sourceId);
        }
        menu.add("Refresh Sources", () -> app.data.refreshAll());
    }
}
