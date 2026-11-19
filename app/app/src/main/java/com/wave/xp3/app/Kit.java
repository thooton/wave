package com.wave.xp3.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The pieces every screen is built from, each as the mockups draw it
 * (site/mockups/mockups.css): rows that are a focus stop with their own
 * hairline, the capsule, headings, the bar-coloured foot with buttons, the
 * message screen. Sizes are the mockups' pixels as dp.
 */
final class Kit {

    private Kit() {}

    static final int[] FOCUSED = {android.R.attr.state_focused};
    static final int[] PRESSED = {android.R.attr.state_pressed};

    /**
     * A row: white, the iOS selection grey while the d-pad is on it, a
     * hairline under it inset 10dp each side unless it is the last of its
     * group. Its children that duplicate its state (the capsule) see its
     * focus too.
     */
    static class Row extends LinearLayout {
        private final Paint rule = new Paint();
        private boolean hairline = true;
        /** What the row is about, for the menu: a package, a source, whatever the screen put there. */
        Object about;

        Row(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);
            setClickable(true);
            setBackground(selector(0xFFFFFFFF, Look.FOCUS));
            setWillNotDraw(false);
            rule.setColor(Look.SEPARATOR);
            int pad = Look.dp(context, Look.GUTTER);
            setPadding(pad, 0, pad, 0);
        }

        Row last() {
            setHairline(false);
            return this;
        }

        /** Redraws only on a change: a recycled row is rebound on every scroll step. */
        void setHairline(boolean on) {
            if (hairline == on) return;
            hairline = on;
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (!hairline) return;
            int inset = Look.dp(getContext(), Look.GUTTER);
            int h = getHeight();
            canvas.drawRect(inset, h - Math.max(1, Look.dp(getContext(), 1)), getWidth() - inset, h, rule);
        }
    }

    static StateListDrawable selector(int normal, int focused) {
        StateListDrawable states = new StateListDrawable();
        states.addState(PRESSED, new ColorDrawable(0xFFD9D9DE));
        states.addState(FOCUSED, new ColorDrawable(focused));
        states.addState(new int[0], new ColorDrawable(normal));
        return states;
    }

    static int dp(Context c, float v) {
        return Look.dp(c, v);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fill(int heightPx) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    static TextView line(Context c, CharSequence text, float sp, int color) {
        TextView view = Look.text(c, text, sp, color, Look.REGULAR);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    /** The name over a line of detail, filling the row's middle. */
    static LinearLayout texts(Context c, CharSequence name, CharSequence sub, int subColor, int leftMarginDp) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(line(c, name, 14, Look.TEXT));
        if (sub != null && sub.length() > 0) {
            TextView s = line(c, sub, 12, subColor);
            LinearLayout.LayoutParams lp = wrap();
            lp.topMargin = dp(c, 3);
            box.addView(s, lp);
        }
        LinearLayout.LayoutParams lp = weight();
        lp.leftMargin = dp(c, leftMarginDp);
        lp.rightMargin = dp(c, 6);
        box.setLayoutParams(lp);
        return box;
    }

    static TextView name(LinearLayout texts) {
        return (TextView) texts.getChildAt(0);
    }

    static ImageView iconView(Context c, int sizeDp) {
        ImageView view = new ImageView(c);
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return view;
    }

    static Glyph chevron(Context c) {
        Glyph g = new Glyph(c, Glyph.CHEVRON, Look.CHEVRON);
        LinearLayout.LayoutParams lp = wrap();
        lp.rightMargin = -dp(c, 2);
        g.setLayoutParams(lp);
        return g;
    }

    /**
     * The App Store's capsule (GET, UPDATE, OPEN): blue on grey, filled blue
     * with white text while its row is focused, which is how the screen says
     * OK presses it. `alwaysPressed` draws it filled regardless (a details
     * page, where it is the one thing OK does); `off` greys it.
     */
    static TextView capsule(Context c, String word, boolean alwaysPressed, boolean off) {
        TextView cap = Look.text(c, word, 11.5f, Look.BLUE, Look.BOLD);
        cap.setGravity(Gravity.CENTER);
        cap.setSingleLine(true);
        cap.setMinWidth(dp(c, 56));
        cap.setPadding(dp(c, 10), 0, dp(c, 10), 0);
        float r = dp(c, 11);
        if (off) {
            cap.setBackground(Look.rounded(Look.PILL, r));
            cap.setTextColor(Look.OFF_TEXT);
        } else if (alwaysPressed) {
            cap.setBackground(Look.rounded(Look.BLUE, r));
            cap.setTextColor(0xFFFFFFFF);
        } else {
            StateListDrawable bg = new StateListDrawable();
            bg.addState(FOCUSED, Look.rounded(Look.BLUE, r));
            bg.addState(new int[0], Look.rounded(Look.PILL, r));
            cap.setBackground(bg);
            cap.setTextColor(new ColorStateList(new int[][]{FOCUSED, new int[0]}, new int[]{0xFFFFFFFF, Look.BLUE}));
            cap.setDuplicateParentStateEnabled(true);
        }
        cap.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 22)));
        return cap;
    }

    /** A bold heading (18sp), or the small grey one (13sp) of a details page and the queue. */
    static TextView heading(Context c, String text, boolean small) {
        TextView h = small ? Look.text(c, text, 13, Look.MUTED, Look.MEDIUM) : Look.text(c, text, 18, Look.TEXT, Look.BOLD);
        h.setSingleLine(true);
        h.setEllipsize(TextUtils.TruncateAt.END);
        h.setPadding(dp(c, 10), dp(c, small ? 10 : 12), dp(c, 10), dp(c, 4));
        h.setBackgroundColor(0xFFFFFFFF);
        return h;
    }

    static View gap(Context c, int dpHigh) {
        View v = new View(c);
        v.setLayoutParams(fill(dp(c, dpHigh)));
        return v;
    }

    /** A grey hairline across, inset 10dp. */
    static View hair(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Look.SEPARATOR);
        LinearLayout.LayoutParams lp = fill(Math.max(1, dp(c, 1)));
        lp.leftMargin = lp.rightMargin = dp(c, 10);
        lp.topMargin = lp.bottomMargin = dp(c, 4);
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * A wide button (the foot's, a message screen's): blue on grey, filled
     * while focused; `red` for a destructive one.
     */
    static TextView button(Context c, String text, boolean red) {
        TextView b = Look.text(c, text, 14, red ? Look.RED : Look.BLUE, Look.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        float r = dp(c, 9);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(FOCUSED, Look.rounded(red ? Look.RED : Look.BLUE, r));
        bg.addState(new int[0], Look.rounded(Look.PILL, r));
        b.setBackground(bg);
        b.setTextColor(new ColorStateList(new int[][]{FOCUSED, new int[0]}, new int[]{0xFFFFFFFF, red ? Look.RED : Look.BLUE}));
        return b;
    }

    /**
     * The modal screens' foot, on the bar's colour with a hairline over it:
     * an optional grey summary line, then one button or two side by side.
     */
    static final class Foot extends LinearLayout {
        private final Paint rule = new Paint();
        final TextView summary;
        final LinearLayout buttons;

        Foot(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setBackgroundColor(Look.BAR);
            setWillNotDraw(false);
            rule.setColor(Look.BAR_RULE);
            setPadding(0, dp(c, 8), 0, dp(c, 8));
            summary = line(c, "", 12, Look.MUTED);
            summary.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.leftMargin = sl.rightMargin = dp(c, 10);
            sl.bottomMargin = dp(c, 7);
            addView(summary, sl);
            summary.setVisibility(GONE);
            buttons = new LinearLayout(c);
            buttons.setOrientation(HORIZONTAL);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 34));
            bl.leftMargin = bl.rightMargin = dp(c, 10);
            addView(buttons, bl);
        }

        void setSummary(String text) {
            summary.setText(text == null ? "" : text);
            summary.setVisibility(text == null ? GONE : VISIBLE);
        }

        /** Replaces the buttons; returns them in order. */
        TextView[] setButtons(String... labels) {
            buttons.removeAllViews();
            TextView[] out = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                boolean red = labels[i].startsWith("!");
                out[i] = button(getContext(), red ? labels[i].substring(1) : labels[i], red);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (i > 0) lp.leftMargin = dp(getContext(), 8);
                buttons.addView(out[i], lp);
            }
            return out;
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            canvas.drawRect(0, 0, getWidth(), Math.max(1, dp(getContext(), 1)), rule);
        }
    }

    /**
     * The Updater's message screen: a bold title, a grey line under it, and
     * optionally a button (its action) and a big blue line (an address).
     * Centred in the page. Returns the frame; the button, if any, is
     * findable as {@link #messageButton}.
     */
    static FrameLayout message(Context c, View top, String title, String detail, String big, String button) {
        FrameLayout frame = new FrameLayout(c);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        if (top != null) {
            box.addView(top);
        }
        if (title != null) {
            TextView t = Look.text(c, title, 17, Look.TEXT, Look.BOLD);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tl = wrap();
            if (top != null) tl.topMargin = dp(c, 12);
            box.addView(t, tl);
        }
        if (detail != null) {
            TextView d = Look.text(c, detail, 13, Look.MUTED, Look.REGULAR);
            d.setGravity(Gravity.CENTER);
            d.setLineSpacing(dp(c, 2), 1f);
            LinearLayout.LayoutParams dl = wrap();
            dl.topMargin = dp(c, 3);
            box.addView(d, dl);
        }
        if (big != null) {
            TextView b = Look.text(c, big, 15, Look.BLUE, Look.MEDIUM);
            b.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams bl = wrap();
            bl.topMargin = dp(c, 8);
            box.addView(b, bl);
        }
        if (button != null) {
            TextView b = button(c, button, false);
            b.setMinWidth(dp(c, 130));
            b.setId(R_MESSAGE_BUTTON);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 34));
            bl.topMargin = dp(c, 14);
            box.addView(b, bl);
        }
        frame.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return frame;
    }

    static final int R_MESSAGE_BUTTON = 0x7f0a7001;

    static TextView messageButton(View message) {
        return (TextView) message.findViewById(R_MESSAGE_BUTTON);
    }

    /** A package icon with an optional badge on its corner (grey tick installed, blue arrow update). */
    static final class BadgedIcon extends FrameLayout {
        final ImageView image;
        private int kind; // 0 none, 1 installed, 2 update
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BadgedIcon(Context c, int sizeDp) {
            super(c);
            image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            addView(image, new LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
            setWillNotDraw(false);
            setClipChildren(false);
            setDuplicateParentStateEnabled(true);
            setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        }

        /** The badge's mark, kept while the kind and size stay (a new Path would be drawn and uploaded again). */
        private android.graphics.Path mark;
        private int markKind, markW, markH;

        void setBadge(int kind) {
            // Always redrawn: the ring's colour follows the row's focus, and a
            // rebind is what brings it up to date.
            this.kind = kind;
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (kind == 0) return;
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() - 4 * d, cy = getHeight() - 4 * d;
            boolean focused = ((View) getParent()).isFocused();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(focused ? Look.FOCUS : 0xFFFFFFFF);
            canvas.drawCircle(cx, cy, 8.5f * d, paint);
            paint.setColor(kind == 2 ? Look.BLUE : Look.MUTED);
            canvas.drawCircle(cx, cy, 7 * d, paint);
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * d);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            if (mark == null || markKind != kind || markW != getWidth() || markH != getHeight()) {
                mark = mark(kind, cx, cy, d);
                markKind = kind;
                markW = getWidth();
                markH = getHeight();
            }
            canvas.drawPath(mark, paint);
        }

        private static android.graphics.Path mark(int kind, float cx, float cy, float d) {
            android.graphics.Path p = new android.graphics.Path();
            if (kind == 2) {
                p.moveTo(cx, cy + 3 * d);
                p.lineTo(cx, cy - 3 * d);
                p.moveTo(cx - 2.5f * d, cy - 0.5f * d);
                p.lineTo(cx, cy - 3 * d);
                p.lineTo(cx + 2.5f * d, cy - 0.5f * d);
            } else {
                p.moveTo(cx - 3.2f * d, cy + 0.3f * d);
                p.lineTo(cx - 1.1f * d, cy + 2.4f * d);
                p.lineTo(cx + 3.2f * d, cy - 2.2f * d);
            }
            return p;
        }
    }

    static Drawable none() {
        return null;
    }
}
