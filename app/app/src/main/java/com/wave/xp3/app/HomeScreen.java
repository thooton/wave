package com.wave.xp3.app;

import android.os.Build;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Pkg;

import java.util.List;

/**
 * The Wave tab, Cydia's home: a Reboot row when one is pending, the welcome
 * header, Featured (the default source's), What is Wave and Add a Source, then
 * the phone's facts and the community links as text below the last focus
 * stop (Down past it scrolls them in).
 */
final class HomeScreen extends Screen {

    private Page page;

    HomeScreen(WaveActivity app) {
        super(app);
    }

    @Override
    String title() {
        return "Home";
    }

    @Override
    View build() {
        page = new Page(app);
        return page.scroll;
    }

    @Override
    void update(WaveData.Snapshot s) {
        Mark mark = page.clear();
        if (!s.rebootPending.isEmpty()) page.add(Rows.reboot(app, Rows.rebootText(s.rebootPending)));
        page.add(welcome());

        List<Pkg> featured = s.featured(null);
        page.add(Kit.heading(app, "Featured", false));
        if (featured.isEmpty()) {
            String why = s.firstLoadError != null ? "Cannot load sources. " + s.firstLoadError
                : refreshing(s) ? "Loading…" : "Nothing featured yet.";
            TextView t = Look.text(app, why, 13, Look.MUTED, Look.REGULAR);
            t.setPadding(Kit.dp(app, 10), Kit.dp(app, 4), Kit.dp(app, 10), Kit.dp(app, 8));
            page.add(t);
        }
        for (int i = 0; i < featured.size(); i++) {
            final Pkg p = featured.get(i);
            Kit.Row row = Rows.browse(app, p, v -> app.details(p, null));
            if (i == featured.size() - 1) row.last();
            page.add(row);
        }
        page.add(Kit.gap(app, 10));

        ImageView wave = new ImageView(app);
        app.icons.into(wave, new AppIcons.Ref(app.getPackageName(), "Wave", null, null, app.getPackageName()), 26);
        Kit.Row about = Rows.link(app, "What is Wave", null, 0, wave, 26, null, false);
        about.setOnClickListener(v -> app.push(new AboutScreen(app)));
        page.add(keyed(about, "about"));

        TextView plus = Look.text(app, "+", 20, 0xFFFFFFFF, Look.REGULAR);
        plus.setGravity(android.view.Gravity.CENTER);
        plus.setBackground(Look.rounded(0xFF34C759, Kit.dp(app, 26) * 0.225f));
        Kit.Row add = Rows.link(app, "Add a Source", null, 0, plus, 26, null, false);
        add.last();
        add.setOnClickListener(v -> app.pushOnTab(1, new AddSourceScreen(app)));
        page.add(keyed(add, "add"));

        if (s.root != WaveData.Root.OK) {
            Kit.Row root = Rows.link(app, "Root for System Mods", s.root == WaveData.Root.CHECKING ? "Checking…" : "Not granted", Look.MUTED,
                null, 0, null, false);
            root.setOnClickListener(v -> app.push(new NoRootScreen(app)));
            page.add(Kit.gap(app, 10));
            page.add(keyed(root.last(), "root"));
        }
        page.add(Kit.heading(app, "This Phone", false));
        WaveData.Facts f = s.facts;
        String model = f != null && f.model != null ? f.model : Build.MODEL;
        String android = f != null && f.android != null ? f.android : Build.VERSION.RELEASE;
        if (f != null && f.build != null) android += ", build " + f.build;
        page.add(Rows.fact(app, "Model", model, false));
        page.add(Rows.fact(app, "Android", android, false));
        page.add(Rows.fact(app, "Root", f != null && f.root != null ? f.root : rootText(s.root), false));
        page.add(Rows.fact(app, "Storage", f != null && f.storageFree != null ? f.storageFree : "", true));
        page.add(Kit.heading(app, "Community", false));
        page.add(Rows.fact(app, "Code", "github.com/thooton/wave", false));
        page.add(Rows.fact(app, "Website", "thooton.github.io/wave", true));
        page.add(Kit.gap(app, 10));
        page.restore(mark, featured.isEmpty() ? "about" : null);
    }

    private static boolean refreshing(WaveData.Snapshot s) {
        for (WaveData.Src src : s.sources) if (src.refreshing) return true;
        return s.sources.isEmpty();
    }

    private static String rootText(WaveData.Root root) {
        return root == WaveData.Root.OK ? "Yes" : root == WaveData.Root.NONE ? "No" : "Checking…";
    }

    private View welcome() {
        LinearLayout row = new LinearLayout(app);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(Kit.dp(app, 10), 0, Kit.dp(app, 10), 0);
        row.setLayoutParams(Kit.fill(Kit.dp(app, 58)));
        ImageView icon = Kit.iconView(app, 40);
        app.icons.into(icon, new AppIcons.Ref(app.getPackageName(), "Wave", null, null, app.getPackageName()), 40);
        row.addView(icon);
        LinearLayout texts = new LinearLayout(app);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Look.text(app, "Welcome to Wave", 15, Look.TEXT, Look.MEDIUM));
        TextView sub = Kit.line(app, "for the Sonim " + Build.MODEL, 12, Look.MUTED);
        LinearLayout.LayoutParams sl = Kit.wrap();
        sl.topMargin = Kit.dp(app, 3);
        texts.addView(sub, sl);
        LinearLayout.LayoutParams tl = Kit.weight();
        tl.leftMargin = Kit.dp(app, 10);
        row.addView(texts, tl);
        return row;
    }

    @Override
    void menu(Menu menu) {
        View focus = app.getCurrentFocus();
        if (focus instanceof Kit.Row && ((Kit.Row) focus).about instanceof Pkg) {
            final Pkg p = (Pkg) ((Kit.Row) focus).about;
            app.packageMenu(menu, p, true, null);
        }
        if (!app.snapshot.rebootPending.isEmpty()) menu.add("Reboot", app::askReboot);
        menu.add("Refresh Sources", () -> app.data.refreshAll());
    }
}
