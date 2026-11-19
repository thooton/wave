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
    private Page page;

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
        page = new Page(app);
        return page.scroll;
    }

    @Override
    void update(WaveData.Snapshot s) {
        Mark mark = page.clear();
        List<Pkg> packages = s.packages(sourceId, folder);
        if (packages.isEmpty()) {
            page.add(Kit.message(app, null, "No Packages", null, null, null));
            page.scroll.setFocusable(true);
            return;
        }
        for (int i = 0; i < packages.size(); i++) {
            final Pkg p = packages.get(i);
            Kit.Row row = Rows.browse(app, p, v -> app.details(p, sourceId));
            if (i == packages.size() - 1) row.last();
            page.add(row);
        }
        page.restore(mark, null);
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
