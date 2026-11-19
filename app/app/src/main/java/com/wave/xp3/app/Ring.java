package com.wave.xp3.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The App Store's download ring: a grey track, a blue arc for how far it has
 * got and a stop square in the middle; with no percentage, a quarter arc
 * that turns (waiting its turn, checking, installing). Also the small grey
 * spinner of the bar and of a refreshing source ({@link #spinner}).
 */
final class Ring extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stop = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private int percent = -1;
    private boolean small;

    Ring(Context context) {
        super(context);
        float stroke = Look.dp(context, 2);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(Look.RING_TRACK);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setColor(Look.BLUE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        stop.setColor(Look.BLUE);
    }

    /** The grey spinner: no stop square, a grey arc. */
    static Ring spinner(Context context) {
        Ring ring = new Ring(context);
        ring.small = true;
        ring.arc.setColor(Look.MUTED);
        ring.track.setStrokeWidth(Look.dp(context, 1.8f));
        ring.arc.setStrokeWidth(Look.dp(context, 1.8f));
        return ring;
    }

    /** 0 to 100, or -1 to spin. */
    void setPercent(int value) {
        percent = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float inset = track.getStrokeWidth() / 2f + 0.5f;
        box.set(inset, inset, w - inset, h - inset);
        canvas.drawOval(box, track);
        if (percent >= 0 && !small) {
            canvas.drawArc(box, -90, 360f * percent / 100f, false, arc);
            float s = Math.min(w, h) * 0.29f;
            canvas.drawRoundRect(w / 2 - s / 2, h / 2 - s / 2, w / 2 + s / 2, h / 2 + s / 2, s / 7, s / 7, stop);
        } else {
            long turn = (System.currentTimeMillis() / 3) % 360;
            canvas.drawArc(box, turn, small ? 90 : 108, false, arc);
            if (isShown()) postInvalidateDelayed(40);
        }
    }
}
