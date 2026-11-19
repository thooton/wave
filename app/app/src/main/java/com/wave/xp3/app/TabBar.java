package com.wave.xp3.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The tab bar, as Cydia's: four tabs, each an icon over its name, the open one
 * in blue with the filled form of its icon, as iOS draws them. It never takes
 * focus: Left and Right move between tabs from the rows. Installed carries a
 * red badge with the number of updates.
 *
 * The icons are drawn here from the mockups' SVG paths (24 units across for
 * Wave's waves, 18 for the others), so they stay sharp at any density.
 */
final class TabBar extends View {

    static final String[] NAMES = {"Wave", "Sources", "Installed", "Search"};

    private final Paint rule = new Paint();
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private int open;
    private int badge;

    TabBar(Context context) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        setBackgroundColor(Look.BAR);
        rule.setColor(Look.BAR_RULE);
        label.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
        label.setTypeface(Look.REGULAR);
        label.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        badgePaint.setColor(Look.BADGE);
        badgeText.setColor(0xFFFFFFFF);
        badgeText.setTypeface(Look.MEDIUM);
        badgeText.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
        badgeText.setTextAlign(Paint.Align.CENTER);
        setFocusable(false);
        setContentDescription("Tabs");
    }

    void setOpen(int tab) {
        if (open == tab) return;
        open = tab;
        invalidate();
    }

    void setBadge(int count) {
        if (badge == count) return;
        badge = count;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(Look.TAB_HEIGHT * d));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        canvas.drawRect(0, 0, w, Math.max(1, Math.round(d)), rule);
        float tabW = w / 4f;
        for (int i = 0; i < 4; i++) {
            boolean on = i == open;
            int color = on ? Look.BLUE : Look.MUTED;
            float cx = tabW * i + tabW / 2f;
            canvas.save();
            // An 18-unit icon box whose top is 5dp down.
            canvas.translate(cx - 9 * d, 5 * d);
            canvas.scale(d, d);
            icon(canvas, i, on, color);
            canvas.restore();
            label.setColor(color);
            canvas.drawText(NAMES[i], cx, 33 * d - label.descent(), label);
            if (i == 2 && badge > 0) {
                String text = badge > 99 ? "99+" : String.valueOf(badge);
                float bw = Math.max(14 * d, badgeText.measureText(text) + 6 * d);
                float left = cx + 4 * d;
                RectF r = new RectF(left, 2 * d, left + bw, 16 * d);
                canvas.drawRoundRect(r, 7 * d, 7 * d, badgePaint);
                Paint.FontMetrics m = badgeText.getFontMetrics();
                canvas.drawText(text, r.centerX(), r.centerY() - (m.ascent + m.descent) / 2f, badgeText);
            }
        }
    }

    /** Tab `i`'s icon in an 18x18 box, in `color`. */
    private void icon(Canvas c, int i, boolean on, int color) {
        stroke.setColor(color);
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        switch (i) {
            case 0: { // two waves
                stroke.setStrokeWidth(on ? 2.4f : 1.6f);
                c.translate(-2, 0);
                for (int k = 0; k < 2; k++) {
                    float y = k == 0 ? 7 : 13;
                    Path p = new Path();
                    p.moveTo(2, y);
                    p.rCubicTo(2.5f, -3.5f, 5f, -3.5f, 7.5f, 0);
                    p.rCubicTo(2.5f, 3.5f, 5f, 3.5f, 7.5f, 0);
                    p.rCubicTo(1.75f, -2.5f, 3.5f, -2.5f, 3.5f, -2.5f);
                    c.drawPath(p, stroke);
                }
                break;
            }
            case 1: { // a stack of trays
                if (on) {
                    fill.setAlpha(140);
                    c.drawRoundRect(3, 1.5f, 15, 4.5f, 1, 1, fill);
                    fill.setAlpha(204);
                    c.drawRoundRect(2, 5.5f, 16, 8.5f, 1, 1, fill);
                    fill.setAlpha(255);
                    c.drawRoundRect(1, 9.5f, 17, 17, 1.5f, 1.5f, fill);
                } else {
                    stroke.setStrokeWidth(1.2f);
                    c.drawRoundRect(3.5f, 2, 14.5f, 4, .8f, .8f, stroke);
                    c.drawRoundRect(2.5f, 6, 15.5f, 8, .8f, .8f, stroke);
                    stroke.setStrokeWidth(1.3f);
                    c.drawRoundRect(1.5f, 10, 16.5f, 16.5f, 1.3f, 1.3f, stroke);
                }
                break;
            }
            case 2: { // an arrow coming down in a circle
                Path arrow = new Path();
                arrow.moveTo(9, 4.5f);
                arrow.lineTo(9, 12.5f);
                arrow.moveTo(5.5f, 9.2f);
                arrow.lineTo(9, 12.7f);
                arrow.lineTo(12.5f, 9.2f);
                if (on) {
                    c.drawCircle(9, 9, 8.2f, fill);
                    stroke.setColor(Look.BAR);
                    stroke.setStrokeWidth(1.8f);
                } else {
                    stroke.setStrokeWidth(1.3f);
                    c.drawCircle(9, 9, 7.8f, stroke);
                    stroke.setStrokeWidth(1.4f);
                }
                c.drawPath(arrow, stroke);
                break;
            }
            default: { // a magnifier
                stroke.setStrokeWidth(on ? 2.3f : 1.5f);
                c.drawCircle(7.6f, 7.6f, 5.6f, stroke);
                stroke.setStrokeWidth(on ? 2.6f : 1.7f);
                c.drawLine(11.8f, 11.8f, 16.2f, 16.2f, stroke);
            }
        }
    }
}
