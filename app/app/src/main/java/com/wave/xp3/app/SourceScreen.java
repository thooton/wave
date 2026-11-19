package com.wave.xp3.app;

import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Pkg;
import com.wave.xp3.data.WaveData.Section;
import com.wave.xp3.data.WaveData.Src;

import java.util.List;

/**
 * A source opened: its header, All Packages, each section with its count, and
 * the source's own Featured. With a null source, All Sources: every section
 * of every source.
 */
final class SourceScreen extends Screen {

    private final String sourceId;

    SourceScreen(WaveActivity app, String sourceId) {
        super(app);
        this.sourceId = sourceId;
    }

    private Src source() {
        if (sourceId == null) return null;
        for (Src s : app.snapshot.sources) if (s.id.equals(sourceId)) return s;
        return null;
    }

    @Override
    String title() {
        Src s = source();
        return Pages.sourceTitle(sourceId, s);
    }

    @Override
    View build() {
        feed = new Feed(app);
        return feed.root;
    }

    @Override
    void update(WaveData.Snapshot snap) {
        Mark mark = feed.clear();
        Src src = source();
        if (sourceId != null && src == null) {
            // Removed (from its menu): back to the list it was in.
            feed.message(Kit.message(app, null, "Source Removed", null, null, null));
            if (app.isShowing(this)) feed.root.post(() -> {
                if (app.isShowing(this)) app.pop();
            });
            return;
        }
        if (src != null) {
            LinearLayout head = new LinearLayout(app);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            head.setPadding(Kit.dp(app, 10), 0, Kit.dp(app, 10), 0);
            head.setLayoutParams(Kit.fill(Kit.dp(app, 52)));
            ImageView icon = Kit.iconView(app, 36);
            app.icons.into(icon, new AppIcons.Ref("source:" + src.id, src.name, src.iconFile, null, null), 36);
            head.addView(icon);
            Pages.Line header = Pages.sourceHeader(src);
            LinearLayout texts = new LinearLayout(app);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(Look.text(app, src.name, 14, Look.TEXT, Look.MEDIUM));
            TextView s = Look.text(app, header.text, 12, Look.color(header.tone), Look.REGULAR);
            s.setMaxLines(2);
            LinearLayout.LayoutParams sl = Kit.wrap();
            sl.topMargin = Kit.dp(app, 3);
            texts.addView(s, sl);
            LinearLayout.LayoutParams tl = Kit.weight();
            tl.leftMargin = Kit.dp(app, 9);
            head.addView(texts, tl);
            feed.add(head);
            feed.add(Kit.hair(app));
        }
        List<Section> sections = snap.sections(sourceId);
        int total = snap.packages(sourceId, null).size();
        Kit.Row all = Rows.link(app, "All Packages", null, 0, null, 0, String.valueOf(total), false);
        all.setOnClickListener(v -> app.push(new SectionScreen(app, sourceId, null, "All Packages")));
        boolean notRead = src != null && src.unreadableCount > 0;
        if (sections.isEmpty() && !notRead) all.last();
        feed.add(keyed(all, "all"));
        for (int i = 0; i < sections.size(); i++) {
            final Section section = sections.get(i);
            Kit.Row row = Rows.link(app, section.name, null, 0, null, 0, String.valueOf(section.count), false);
            if (i == sections.size() - 1 && !notRead) row.last();
            row.setOnClickListener(v -> app.push(new SectionScreen(app, sourceId, section.folder, section.name)));
            feed.add(keyed(row, "section:" + section.folder));
        }
        if (notRead) {
            // For whoever publishes the source: which packages Wave left out, and why.
            int n = src.unreadableCount;
            Kit.Row row = Rows.link(app, Pages.unreadableLabel(n),
                null, 0, null, 0, null, false);
            row.last();
            final List<String> lines = src.unreadable, icons = src.iconsIgnored;
            row.setOnClickListener(v -> {
                app.alert("Can't Be Read", Pages.unreadableText(lines, icons));
            });
            feed.add(keyed(row, "unreadable"));
        }
        if (sourceId != null) {
            List<Pkg> featured = snap.featured(sourceId);
            if (!featured.isEmpty()) {
                feed.add(Kit.heading(app, "Featured", false));
                for (int i = 0; i < featured.size(); i++) {
                    final Pkg p = featured.get(i);
                    feed.add(Rows.browseLine(app, p, v -> app.details(p, sourceId), i == featured.size() - 1));
                }
            }
        }
        feed.add(Kit.gap(app, 6));
        feed.restore(mark, sections.isEmpty() ? "all" : "section:" + sections.get(0).folder);
    }

    @Override
    void menu(Menu menu) {
        View focus = app.getCurrentFocus();
        if (focus instanceof Kit.Row && ((Kit.Row) focus).about instanceof Pkg) {
            app.packageMenu(menu, (Pkg) ((Kit.Row) focus).about, true, sourceId);
        }
        Src src = source();
        if (src != null) SourcesScreen.sourceMenu(app, menu, src);
        else menu.add("Refresh Sources", () -> app.data.refreshAll());
    }
}
