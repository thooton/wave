package com.wave.xp3.app;

import android.view.View;

import com.wave.xp3.data.WaveData;

/** Asks the root manager for permission only when the user presses Grant access. */
final class NoRootScreen extends Screen {

    private static final String GRANT_ACCESS = "Grant access";
    private View page;

    NoRootScreen(WaveActivity app) {
        super(app);
    }

    @Override
    String title() {
        return "Root";
    }

    @Override
    boolean bare() {
        return true;
    }

    @Override
    View build() {
        page = Kit.message(app, null, "Wave requires root access", null, null, GRANT_ACCESS);
        Kit.messageButton(page).setOnClickListener(v -> app.data.checkRoot());
        return page;
    }

    @Override
    void update(WaveData.Snapshot s) {
        if (s.root == WaveData.Root.OK && app.isShowing(this)) {
            app.toast("Wave has root");
            page.post(app::pop);
        }
        Kit.messageButton(page).setText(s.root == WaveData.Root.CHECKING ? "Checking…" : GRANT_ACCESS);
    }

    @Override
    View firstFocus() {
        return Kit.messageButton(page);
    }
}
