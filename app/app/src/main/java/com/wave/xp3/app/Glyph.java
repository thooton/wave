package com.wave.xp3.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * The small drawn signs: the chevron that says OK opens a row, a tick, the
 * warning triangle, the info dot, the reboot arrow and a plus. Drawn from the
 * mockups' paths in a 14-unit box (the chevron in its own 13x14 box).
 */
final class Glyph extends View {

    static final int CHEVRON = 0, TICK = 1, WARN = 2, INFO = 3, REBOOT = 4, PLUS = 5;

    /*
     * The shapes, in the 14-unit box onDraw scales to the screen. One Path
     * each, made once: hwui caches a path's drawing by the Path, so a new one
     * per draw would be drawn and uploaded again for every row shown.
     */
    private static final Path CHEVRON_PATH = path(false, 3, 2.5f, 7.5f, 7, 3, 11.5f);
    private static final Path TICK_PATH = path(false, 2.8f, 7.3f, 5.6f, 10.1f, 11.3f, 4.0f);
    private static final Path WARN_PATH = path(true, 7, 1.2f, 13.2f, 12.2f, 0.8f, 12.2f);
    private static final Path REBOOT_PATH = path(false, 8.2f, 1.6f, 10.8f, 3.3f, 9.1f, 5.9f);

    private static Path path(boolean closed, float... xy) {
        Path p = new Path();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        if (closed) p.close();
        return p;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private int kind;
    private int color;

    Glyph(Context context, int kind, int color) {
        super(context);
        d = getResources().getDisplayMetrics().density;
        this.kind = kind;
        this.color = color;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void set(int kind, int color) {
        this.kind = kind;
        this.color = color;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round((kind == CHEVRON ? 13 : 14) * d), Math.round(14 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        c.scale(d, d);
        paint.setColor(color);
        switch (kind) {
            case CHEVRON: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2);
                paint.setStrokeCap(Paint.Cap.SQUARE);
                paint.setStrokeJoin(Paint.Join.MITER);
                c.drawPath(CHEVRON_PATH, paint);
                break;
            }
            case TICK: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.8f);
                c.drawPath(TICK_PATH, paint);
                break;
            }
            case WARN: {
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setStrokeWidth(1);
                c.drawPath(WARN_PATH, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                c.drawRoundRect(6.25f, 5, 7.75f, 9, .7f, .7f, paint);
                c.drawCircle(7, 10.4f, .85f, paint);
                break;
            }
            case INFO: {
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(7, 7, 6.2f, paint);
                paint.setColor(0xFFFFFFFF);
                c.drawRoundRect(6.2f, 6, 7.8f, 10.6f, .8f, .8f, paint);
                c.drawCircle(7, 3.9f, 1, paint);
                break;
            }
            case REBOOT: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.6f);
                c.drawArc(2.6f, 2.6f, 11.4f, 11.4f, -50, 300, false, paint);
                c.drawPath(REBOOT_PATH, paint);
                break;
            }
            default: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2);
                c.drawLine(7, 2, 7, 12, paint);
                c.drawLine(2, 7, 12, 7, paint);
            }
        }
        c.restore();
    }
}
