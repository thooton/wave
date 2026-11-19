package com.wave.xp3.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The bar under the status bar, as the Updater's: near-white, a grey hairline
 * under it, the title centred. On the left, Back's target named in blue with
 * a chevron (never a focus stop: Back does it), or a link, or the spinner of
 * a refresh in progress; on the right, a link. A link (Refresh, Add, Update
 * All) is blue text, and fills blue with white text while the d-pad is on it.
 *
 * The title is centred on the screen and as wide as the sides leave it, as
 * iOS lays out a navigation bar. A Back name over 9 characters reads "Back".
 */
final class Bar extends FrameLayout {

    private final float d;
    private final TextView title;
    private final TextView back;
    private final TextView left;
    private final TextView right;
    private final Ring spinner;
    private final Paint rule = new Paint();
    private View field;

    Bar(Context context) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        setBackgroundColor(Look.BAR);
        setWillNotDraw(false);
        rule.setColor(Look.BAR_RULE);

        title = Look.text(context, "", 15, Look.TEXT, Look.MEDIUM);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        back = Look.text(context, "", 13, Look.BLUE, Look.REGULAR);
        back.setSingleLine(true);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setCompoundDrawablePadding(0);
        back.setPadding(Math.round(13 * d), 0, 0, 0);
        LayoutParams bl = new LayoutParams(LayoutParams.WRAP_CONTENT, Math.round(20 * d), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        bl.leftMargin = Math.round(4 * d);
        addView(back, bl);

        left = link(context);
        LayoutParams ll = new LayoutParams(LayoutParams.WRAP_CONTENT, Math.round(20 * d), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        ll.leftMargin = Math.round(4 * d);
        addView(left, ll);

        right = link(context);
        LayoutParams rl = new LayoutParams(LayoutParams.WRAP_CONTENT, Math.round(20 * d), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rl.rightMargin = Math.round(4 * d);
        addView(right, rl);

        spinner = Ring.spinner(context);
        LayoutParams sl = new LayoutParams(Math.round(14 * d), Math.round(14 * d), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        sl.leftMargin = Math.round(10 * d);
        addView(spinner, sl);
        clear();
    }

    private TextView link(Context context) {
        TextView view = Look.text(context, "", 13, Look.BLUE, Look.REGULAR);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(Math.round(6 * d), 0, Math.round(6 * d), 0);
        // Filled blue with white text while the d-pad is on it.
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(Kit.FOCUSED, Look.rounded(Look.BLUE, 9 * d));
        bg.addState(new int[0], new android.graphics.drawable.ColorDrawable(0));
        view.setBackground(bg);
        view.setClickable(true);
        view.setFocusable(false);
        return view;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(Math.round(Look.BAR_HEIGHT * d) + Math.max(1, Math.round(d)), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // Centre the title on the screen, clear of whatever is beside it.
        int width = r - l;
        int side = Math.round(10 * d);
        int gap = Math.round(6 * d);
        if (back.getVisibility() == VISIBLE) side = Math.max(side, back.getRight() + gap);
        if (left.getVisibility() == VISIBLE) side = Math.max(side, left.getRight() + gap);
        if (spinner.getVisibility() == VISIBLE) side = Math.max(side, spinner.getRight() + gap);
        if (right.getVisibility() == VISIBLE) side = Math.max(side, width - right.getLeft() + gap);
        int barH = Math.round(Look.BAR_HEIGHT * d);
        title.layout(side, 0, Math.max(side, width - side), barH);
        if (field != null) field.layout(Math.round(6 * d), Math.round(5 * d), width - Math.round(6 * d), Math.round(29 * d));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        int barH = Math.round(Look.BAR_HEIGHT * d);
        canvas.drawRect(0, barH, getWidth(), barH + Math.max(1, Math.round(d)), rule);
        if (back.getVisibility() == VISIBLE) {
            // The chevron before Back's name.
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Look.BLUE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            float x = back.getLeft() + 4.5f * d, y = barH / 2f;
            Path path = new Path();
            path.moveTo(x + 5 * d, y - 5.5f * d);
            path.lineTo(x, y);
            path.lineTo(x + 5 * d, y + 5.5f * d);
            canvas.drawPath(path, p);
        }
    }

    /** Back to an empty bar. */
    void clear() {
        title.setText("");
        back.setVisibility(GONE);
        left.setVisibility(GONE);
        right.setVisibility(GONE);
        left.setFocusable(false);
        right.setFocusable(false);
        left.setOnClickListener(null);
        right.setOnClickListener(null);
        spinner.setVisibility(GONE);
        if (field != null) {
            removeView(field);
            field = null;
        }
        title.setVisibility(VISIBLE);
    }

    void setTitle(String text) {
        title.setText(text == null ? "" : text);
    }

    void setBack(String target) {
        if (target == null) {
            back.setVisibility(GONE);
            return;
        }
        back.setText(target.length() > 9 ? "Back" : target);
        back.setVisibility(VISIBLE);
    }

    /** Puts a link on the left (0) or right (1); null removes it. A disabled one is grey and no focus stop. */
    TextView setLink(int side, String text, boolean enabled) {
        TextView view = side == 0 ? left : right;
        if (text == null) {
            view.setVisibility(GONE);
            view.setFocusable(false);
            return view;
        }
        view.setText(text);
        view.setVisibility(VISIBLE);
        view.setFocusable(enabled);
        view.setTextColor(enabled
            ? new android.content.res.ColorStateList(new int[][]{Kit.FOCUSED, new int[0]}, new int[]{0xFFFFFFFF, Look.BLUE})
            : android.content.res.ColorStateList.valueOf(0xFFC4C4C9));
        return view;
    }

    TextView link(int side) {
        return side == 0 ? left : right;
    }

    void setSpinning(boolean on) {
        spinner.setVisibility(on ? VISIBLE : GONE);
        if (on) {
            left.setVisibility(GONE);
            left.setFocusable(false);
        }
    }

    /** Puts `view` (the search field) in the title's place. */
    void setField(View view) {
        if (field != null) removeView(field);
        field = view;
        title.setVisibility(view == null ? VISIBLE : GONE);
        if (view != null) addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, Math.round(24 * d)));
    }
}
