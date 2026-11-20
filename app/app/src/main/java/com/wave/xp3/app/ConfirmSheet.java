package com.wave.xp3.app;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.wave.xp3.data.WaveData;

import java.util.List;

/**
 * Confirm, as a sheet over the page it was asked from (dimmed behind it):
 * "Install Canvas?" with the version, then what comes along or goes and
 * why, the download and whether a reboot follows; Cancel on the left, the
 * action named on the right. The focus starts on the action, or on Cancel
 * when the action only removes. Left/Right move between the two, Up/Down
 * scroll a long plan, Back is Cancel.
 */
final class ConfirmSheet {

    private final WaveActivity app;
    private final WaveData.PlanView plan;
    private final Dialog dialog;
    private ScrollView scroll;

    ConfirmSheet(WaveActivity app, WaveData.PlanView plan) {
        this.app = app;
        this.plan = plan;
        dialog = new Dialog(app);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(build());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.42f);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        dialog.setOnKeyListener((d, key, event) -> {
            if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) scroll.smoothScrollBy(0, Kit.dp(app, 36) * (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1));
            return true;
        });
    }

    void show(Runnable onGone) {
        dialog.setOnDismissListener(d -> onGone.run());
        dialog.show();
    }

    void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    private View build() {
        LinearLayout sheet = new LinearLayout(app);
        sheet.setOrientation(LinearLayout.VERTICAL);
        float r = Kit.dp(app, 12);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setPadding(Kit.dp(app, 12), Kit.dp(app, 12), Kit.dp(app, 12), Kit.dp(app, 4));

        // The head: the package's icon when there is one package, the question, its version.
        List<WaveData.PlanItem> asked = Pages.confirmAsked(plan);
        LinearLayout head = new LinearLayout(app);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (asked.size() == 1) {
            ImageView icon = Kit.iconView(app, 34);
            app.icons.into(icon, Rows.icon(app, asked.get(0).pkg), 34);
            head.addView(icon);
        }
        LinearLayout texts = new LinearLayout(app);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = Look.text(app, Pages.confirmTitle(plan), 15, Look.TEXT, Look.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title);
        String sub = Pages.confirmSub(plan);
        if (sub != null) {
            LinearLayout.LayoutParams sl = Kit.wrap();
            sl.topMargin = Kit.dp(app, 2);
            texts.addView(Kit.line(app, sub, 12, Look.MUTED), sl);
        }
        LinearLayout.LayoutParams tl = Kit.weight();
        tl.leftMargin = asked.size() == 1 ? Kit.dp(app, 9) : 0;
        head.addView(texts, tl);
        sheet.addView(head);

        View rule = new View(app);
        rule.setBackgroundColor(0xFFE3E3E8);
        LinearLayout.LayoutParams rl = Kit.fill(Math.max(1, Kit.dp(app, 1)));
        rl.topMargin = Kit.dp(app, 9);
        sheet.addView(rule, rl);

        // The facts scroll when the plan is longer than the screen; the head and the actions stay.
        scroll = new ScrollView(app);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        LinearLayout column = new LinearLayout(app);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, Kit.dp(app, 6), 0, 0);
        for (Pages.Fact fact : Pages.confirmFacts(plan)) column.addView(fact(fact));
        scroll.addView(column);
        // Its natural height, cut down (weight) when the sheet would be taller than the window.
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean destroys = Pages.confirmDestroys(plan);
        LinearLayout actions = new LinearLayout(app);
        TextView cancel = Kit.button(app, "Cancel", true);
        cancel.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        cancel.setOnClickListener(v -> dialog.cancel());
        TextView go = Kit.button(app, Pages.confirmVerb(plan), destroys);
        go.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        go.setOnClickListener(v -> {
            dialog.dismiss();
            app.confirmed(plan);
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(0, Kit.dp(app, 36), 1f));
        actions.addView(go, new LinearLayout.LayoutParams(0, Kit.dp(app, 36), 1f));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.topMargin = Kit.dp(app, 6);
        sheet.addView(actions, al);

        // Removing only: the safe answer is where the focus starts, so OK, OK never removes by accident.
        final TextView first = destroys ? cancel : go;
        first.post(first::requestFocus);
        return sheet;
    }

    private TextView fact(Pages.Fact fact) {
        SpannableStringBuilder text = new SpannableStringBuilder(fact.before);
        if (!fact.name.isEmpty()) {
            int start = text.length();
            text.append(fact.name);
            text.setSpan(new TypefaceSpan("sans-serif-medium"), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(Look.TEXT), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.append(fact.after);
        // iOS's orange is too pale for text on white at this size: a darker one says the same.
        int color = fact.tone == Pages.Tone.TEXT ? 0xFF3C3C43 : fact.tone == Pages.Tone.ORANGE ? 0xFFB25E00 : Look.color(fact.tone);
        TextView t = Look.text(app, text, 12.5f, color, Look.REGULAR);
        t.setPadding(0, Kit.dp(app, 2), 0, Kit.dp(app, 2));
        return t;
    }
}
