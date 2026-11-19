package com.wave.xp3.app;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** What is Wave: four short paragraphs. Up/Down scroll; nothing to press. */
final class AboutScreen extends Screen {

    private ScrollView scroll;

    AboutScreen(WaveActivity app) {
        super(app);
    }

    @Override
    String title() {
        return "What is Wave";
    }

    @Override
    View build() {
        scroll = new ScrollView(app);
        scroll.setFocusable(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(app);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Kit.dp(app, 10), Kit.dp(app, 10), Kit.dp(app, 10), Kit.dp(app, 10));
        String[] paragraphs = {
            "Wave installs apps and system mods on this phone, and keeps them up to date.",
            "They come from sources: lists of packages that anyone can publish on GitHub and the like. Wave starts with one, its own.",
            "A system mod changes the phone itself, and starts after a reboot. If the phone doesn't finish starting with one, Wave turns it off again by itself.",
            "Anything Wave installs can be removed from Installed.",
        };
        for (String text : paragraphs) {
            TextView p = Look.text(app, text, 13, Look.TEXT, Look.REGULAR);
            p.setLineSpacing(Kit.dp(app, 2), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Kit.dp(app, 8);
            column.addView(p, lp);
        }
        scroll.addView(column);
        return scroll;
    }

    @Override
    View firstFocus() {
        return scroll;
    }
}
