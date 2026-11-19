package com.wave.xp3.app;

import android.app.AlertDialog;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.QueueItem;

import java.util.ArrayList;
import java.util.List;

/**
 * The queue at work, then done. Each package in the plan's order with the
 * step it is at (a ring while downloading, a spinner while waiting or
 * installing, a tick when done, a red line when it failed). When it ends:
 * Done, or Later | Reboot Now when a mod waits for a reboot. Back while it
 * works sends Wave to the back; the work carries on.
 */
final class QueueScreen extends Screen {

    private LinearLayout outer;
    private ScrollView scroll;
    private LinearLayout column;
    private Kit.Foot foot;
    private TextView[] buttons;
    private boolean finished;
    private final List<String> failuresShown = new ArrayList<>();

    QueueScreen(WaveActivity app) {
        super(app);
    }

    @Override
    String title() {
        WaveData.QueueView q = app.snapshot.queue;
        if (q != null && q.finished) return "Done";
        if (q != null) {
            boolean removing = true;
            for (QueueItem item : q.items) if (item.step != WaveData.Step.REMOVING && item.step != WaveData.Step.WAITING) removing = false;
            for (QueueItem item : q.items) if (item.step == WaveData.Step.DOWNLOADING || item.step == WaveData.Step.INSTALLING) return "Installing";
        }
        return "Installing";
    }

    @Override
    boolean modal() {
        return true;
    }

    @Override
    View build() {
        outer = new LinearLayout(app);
        outer.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(app);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        column = new LinearLayout(app);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column);
        outer.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        foot = new Kit.Foot(app);
        foot.setVisibility(View.GONE);
        outer.addView(foot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    @Override
    void update(WaveData.Snapshot s) {
        WaveData.QueueView q = s.queue;
        column.removeAllViews();
        if (q == null) {
            column.addView(Kit.message(app, null, "Nothing to Do", null, null, null), Kit.fill(Kit.dp(app, 200)));
            showFoot(true, false);
            return;
        }
        for (int i = 0; i < q.items.size(); i++) {
            QueueItem item = q.items.get(i);
            column.addView(row(item, i == q.items.size() - 1));
        }
        if (q.finished && !finished) showFoot(true, q.needsReboot);
        finished = q.finished;
        for (QueueItem item : q.items) {
            if (item.step == WaveData.Step.FAILED && !failuresShown.contains(item.pkg.id)) {
                failuresShown.add(item.pkg.id);
                String verb = item.text != null && item.text.toLowerCase().contains("remov") ? "Removed" : "Installed";
                new AlertDialog.Builder(app)
                    .setTitle(item.pkg.name + " wasn't " + verb.toLowerCase(java.util.Locale.US))
                    .setMessage(item.failure != null ? item.failure : "Something went wrong.")
                    .setPositiveButton("OK", null)
                    .show();
            }
        }
    }

    private View row(QueueItem item, boolean last) {
        String sub;
        int color = Look.MUTED;
        View right = null;
        boolean dim = false;
        switch (item.step) {
            case DONE:
                sub = item.text != null ? item.text : "Done";
                color = Look.GREEN;
                right = new Glyph(app, Glyph.TICK, Look.GREEN);
                break;
            case FAILED:
                sub = item.text != null ? item.text : "Not installed";
                color = Look.RED;
                break;
            case SKIPPED:
                sub = item.text != null ? item.text : "Skipped";
                break;
            case WAITING:
                sub = item.text != null ? item.text : "Waiting";
                dim = true;
                right = spinner(-1);
                break;
            case DOWNLOADING: {
                String of = item.total > 0 ? ", " + mb(item.bytes) + " of " + Rows.Format.size(item.total) : "";
                sub = "Downloading" + of;
                color = Look.BLUE;
                right = spinner(item.percent);
                break;
            }
            default:
                sub = item.text != null ? item.text : word(item.step);
                color = Look.BLUE;
                right = spinner(-1);
        }
        if (finished && item.step == WaveData.Step.WAITING) right = null;
        return Rows.queue(app, item.pkg, sub, color, right, false, last, dim);
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f", bytes / 1000.0 / 1000.0);
    }

    private static String word(WaveData.Step step) {
        switch (step) {
            case CHECKING:
                return "Checking";
            case INSTALLING:
                return "Installing";
            case REMOVING:
                return "Removing";
            default:
                return "";
        }
    }

    private View spinner(int percent) {
        Ring ring = new Ring(app);
        ring.setPercent(percent);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Kit.dp(app, 24), Kit.dp(app, 24));
        lp.leftMargin = lp.rightMargin = Kit.dp(app, 2);
        ring.setLayoutParams(lp);
        return ring;
    }

    private void showFoot(boolean done, boolean reboot) {
        foot.setVisibility(View.VISIBLE);
        if (reboot) {
            foot.setSummary("Changes start after a reboot");
            buttons = foot.setButtons("Later", "Reboot Now");
            buttons[0].setOnClickListener(v -> leave());
            buttons[1].setOnClickListener(v -> app.data.reboot());
            buttons[1].requestFocus();
        } else {
            foot.setSummary(null);
            buttons = foot.setButtons("Done");
            buttons[0].setOnClickListener(v -> leave());
            buttons[0].requestFocus();
        }
        app.redressBar();
    }

    private void leave() {
        app.data.cancel();
        app.pop();
    }

    @Override
    View firstFocus() {
        if (buttons != null) return buttons[buttons.length - 1];
        return scroll;
    }

    @Override
    boolean onKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) scroll.smoothScrollBy(0, Kit.dp(app, 46) * (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1));
            return true;
        }
        if (buttons == null && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) return true;
        return false;
    }

    @Override
    boolean onBack() {
        if (finished) {
            leave();
            return true;
        }
        app.toast("Working in the background");
        app.moveTaskToBack(true);
        return true;
    }
}
