package com.wave.xp3.app;

import android.view.View;
import android.widget.ImageView;

import com.wave.xp3.data.WaveData;

/**
 * Root, explained: system mods (and installing without the Installer) need
 * Wave to have root, which the owner grants in Magisk. Check Again asks for
 * it, which is when Magisk's prompt appears. Apps still install without it.
 */
final class NoRootScreen extends Screen {

    private View page;

    NoRootScreen(WaveActivity app) {
        super(app);
    }

    @Override
    String title() {
        return "Root";
    }

    @Override
    View build() {
        ImageView icon = Kit.iconView(app, 56);
        app.icons.into(icon, new AppIcons.Ref(app.getPackageName(), "Wave", null, null, app.getPackageName()), 56);
        page = Kit.message(app, icon, "Wave Needs Root for Mods",
            "Apps install without it. For system mods, jailbreak the phone from a computer at", "thooton.github.io/wave", "Check Again");
        Kit.messageButton(page).setOnClickListener(v -> app.data.checkRoot());
        return page;
    }

    @Override
    void update(WaveData.Snapshot s) {
        if (s.root == WaveData.Root.OK && app.isShowing(this)) {
            app.toast("Wave has root");
            page.post(app::pop);
        }
        Kit.messageButton(page).setText(s.root == WaveData.Root.CHECKING ? "Checking…" : "Check Again");
    }

    @Override
    View firstFocus() {
        return Kit.messageButton(page);
    }
}
