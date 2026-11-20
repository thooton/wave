package com.wave.xp3.app;

import android.os.Build;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wave.xp3.data.WaveData;

import java.util.List;

/**
 * The Wave tab: the welcome header, root access, a pending Reboot action,
 * then phone facts and community links.
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
        // With root granted and no pending reboot, the page has no action rows.
        page.scroll.setFocusable(true);
        page.scroll.setDefaultFocusHighlightEnabled(false);
        return page.scroll;
    }

    @Override
    void update(WaveData.Snapshot s) {
        Mark mark = page.clear();
        page.add(welcome());
        page.add(Rows.rootStatus(app, s.root));
        if (!s.rebootPending.isEmpty()) page.add(Rows.reboot(app, Rows.rebootText(s.rebootPending)));
        page.add(Kit.heading(app, "This Phone", false));
        List<String[]> phone = Pages.phoneFacts(s, Build.MODEL, Build.VERSION.RELEASE);
        for (int i = 0; i < phone.size(); i++) page.add(Rows.fact(app, phone.get(i)[0], phone.get(i)[1], i == phone.size() - 1));
        page.add(Kit.heading(app, "Community", false));
        page.add(Rows.fact(app, "Code", "github.com/thooton/wave", false));
        page.add(Rows.fact(app, "Website", "thooton.github.io/wave", true));
        page.add(Kit.gap(app, 10));
        page.restore(mark, null);
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
        if (!app.snapshot.rebootPending.isEmpty()) menu.add("Reboot", app::askReboot);
        menu.add("Refresh Sources", () -> app.data.refreshAll());
    }
}
