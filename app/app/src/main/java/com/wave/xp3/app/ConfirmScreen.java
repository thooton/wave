package com.wave.xp3.app;

import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.PlanItem;

/**
 * Confirm: the plan, before anything happens. What will be installed (and,
 * for one pulled in, what needs it), what will be removed (and what replaces
 * it), the download and whether a reboot follows. Cancel | Confirm in the
 * foot, focus on Confirm. Modal: no tab bar.
 */
final class ConfirmScreen extends Screen {

    private final WaveData.PlanView plan;
    private ScrollView scroll;
    private TextView[] buttons;

    ConfirmScreen(WaveActivity app, WaveData.PlanView plan) {
        super(app);
        this.plan = plan;
    }

    @Override
    String title() {
        return "Confirm";
    }

    @Override
    boolean modal() {
        return true;
    }

    @Override
    View build() {
        LinearLayout outer = new LinearLayout(app);
        outer.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(app);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        LinearLayout column = new LinearLayout(app);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column);
        group(column, "Install", plan.install, false);
        group(column, "Remove", plan.remove, true);
        outer.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Kit.Foot foot = new Kit.Foot(app);
        foot.setSummary(Pages.confirmSummary(plan));
        buttons = foot.setButtons("Cancel", "Confirm");
        buttons[0].setOnClickListener(v -> app.pop());
        buttons[1].setOnClickListener(v -> confirm());
        outer.addView(foot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    private void group(LinearLayout column, String title, java.util.List<PlanItem> items, boolean removing) {
        if (items.isEmpty()) return;
        column.addView(Kit.heading(app, Pages.confirmHeading(items, removing), true));
        for (int i = 0; i < items.size(); i++) {
            PlanItem item = items.get(i);
            Pages.Line line = Pages.confirmLine(item, removing);
            column.addView(Rows.queue(app, item.pkg, line.text, Look.color(line.tone), null, true, i == items.size() - 1, false));
        }
    }

    private void confirm() {
        app.data.confirm(plan);
        app.replaceOverlay(new QueueScreen(app));
    }

    @Override
    View firstFocus() {
        return buttons[1];
    }

    @Override
    boolean onKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) scroll.smoothScrollBy(0, Kit.dp(app, 42) * (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1));
            return true;
        }
        return false;
    }
}
