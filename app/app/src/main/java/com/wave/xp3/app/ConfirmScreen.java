package com.wave.xp3.app;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.wave.xp3.data.WaveData;

/**
 * Confirm, as Cydia's: one screen whatever the plan does (get, update,
 * reinstall, remove, or a queue of them all), with Back on the bar.
 * Statistics (the download), Modifications (Install, Reinstall, Remove, each
 * with its packages on the right) and, for updates, an Update section with
 * each change of version; see {@link Pages#confirmSections}. Just over the
 * foot, a grey "Reboot required" when the plan needs one ({@link Pages#confirmNote}).
 * The foot: Add to Queue on the left and Confirm on the right, both black at
 * rest like every Wave button; selected, Add to Queue is green and Confirm
 * blue (red when the plan only removes). The focus starts
 * on Confirm, or on Add to Queue when the plan only removes. Left/Right move
 * between the two, Up/Down scroll a long plan; Back leaves, changing nothing.
 */
final class ConfirmScreen extends Screen {

    private final WaveData.PlanView plan;
    private final boolean destroys;
    private ScrollView scroll;
    private TextView queue, go;

    ConfirmScreen(WaveActivity app, WaveData.PlanView plan) {
        super(app);
        this.plan = plan;
        destroys = Pages.confirmDestroys(plan);
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
    String backTo(Screen below) {
        return "Back";
    }

    @Override
    View build() {
        LinearLayout outer = new LinearLayout(app);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Look.PILL);

        scroll = new ScrollView(app);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        LinearLayout column = new LinearLayout(app);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Kit.dp(app, 8), 0, Kit.dp(app, 8), Kit.dp(app, 10));
        for (Pages.Section section : Pages.confirmSections(plan)) {
            TextView heading = Look.text(app, section.title, 13, Look.MUTED, Look.MEDIUM);
            heading.setPadding(Kit.dp(app, 4), Kit.dp(app, 10), Kit.dp(app, 4), Kit.dp(app, 4));
            column.addView(heading);
            column.addView(card(section));
        }
        scroll.addView(column);
        outer.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // A reboot is said quietly: grey, on the page just over the foot, not a row and not in the foot.
        String note = Pages.confirmNote(plan);
        if (note != null) {
            TextView hint = Look.text(app, note, 12, Look.MUTED, Look.REGULAR);
            hint.setGravity(Gravity.CENTER_HORIZONTAL);
            hint.setPadding(Kit.dp(app, 10), Kit.dp(app, 2), Kit.dp(app, 10), Kit.dp(app, 6));
            outer.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        Kit.Foot foot = new Kit.Foot(app);
        TextView[] buttons = foot.setButtons("Add to Queue", (destroys ? "!" : "") + "Confirm");
        // Cydia's Continue Queuing: keep these changes for the next Confirm and browse on. Green, not Confirm's blue.
        queue = ((Kit.Link) buttons[0]).accent(Look.GREEN);
        go = buttons[1];
        queue.setOnClickListener(v -> {
            app.pop();
            app.addToQueue(plan);
        });
        go.setOnClickListener(v -> {
            app.pop();
            app.confirmed(plan);
        });
        outer.addView(foot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    /** A section's rows on a white card: the label on the left, its value (one name a line) on the right. */
    private View card(Pages.Section section) {
        LinearLayout card = new LinearLayout(app);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = Look.rounded(0xFFFFFFFF, Kit.dp(app, 8));
        bg.setStroke(Math.max(1, Kit.dp(app, 1)), Look.SEPARATOR);
        card.setBackground(bg);
        for (int i = 0; i < section.rows.size(); i++) {
            if (i > 0) {
                View rule = new View(app);
                rule.setBackgroundColor(Look.SEPARATOR);
                LinearLayout.LayoutParams rl = Kit.fill(Math.max(1, Kit.dp(app, 1)));
                rl.leftMargin = Kit.dp(app, 9);
                card.addView(rule, rl);
            }
            String[] row = section.rows.get(i);
            LinearLayout line = new LinearLayout(app);
            line.setPadding(Kit.dp(app, 9), Kit.dp(app, 7), Kit.dp(app, 9), Kit.dp(app, 7));
            TextView label = Look.text(app, row[0], 13.5f, Look.TEXT, Look.MEDIUM);
            // An Update row's label is a package's name: leave its version room.
            label.setMaxWidth(Kit.dp(app, 130));
            line.addView(label, Kit.wrap());
            TextView value = Look.text(app, row[1], 13.5f, Look.TEXT, Look.REGULAR);
            value.setGravity(Gravity.END);
            value.setLineSpacing(Kit.dp(app, 1.5f), 1f);
            LinearLayout.LayoutParams vl = Kit.weight();
            vl.leftMargin = Kit.dp(app, 10);
            line.addView(value, vl);
            line.setContentDescription(row[0] + ", " + row[1].replace('\n', ','));
            card.addView(line);
        }
        return card;
    }

    @Override
    View firstFocus() {
        // Removing only: the focus starts on the answer that changes nothing yet, so OK, OK never removes by accident.
        return destroys ? queue : go;
    }

    @Override
    boolean onKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            scroll.smoothScrollBy(0, Kit.dp(app, 36) * (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1));
        }
        return true;
    }
}
