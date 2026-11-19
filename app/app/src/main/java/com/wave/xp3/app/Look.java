package com.wave.xp3.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Wave's look, in one place: the Updater's colours and sizes (the App Store's
 * light chrome, iOS blue, grey hairlines), as site/mockups/mockups.css draws
 * them. At the XP3's 160 dpi one dp is one pixel, so these are the mockups'
 * pixels.
 */
final class Look {

    static final int BAR = 0xFFF9F9F9;
    static final int BAR_RULE = 0xFFB2B2B2;
    static final int BLUE = 0xFF007AFF;
    static final int PILL = 0xFFEFEFF4;
    static final int SEPARATOR = 0xFFC8C7CC;
    static final int FOCUS = 0xFFE9E9EE;
    static final int TEXT = 0xFF000000;
    static final int MUTED = 0xFF8E8E93;
    static final int CHEVRON = 0xFFC7C7CC;
    /** iOS's accessible red and green, for text that says something failed or is done. */
    static final int RED = 0xFFD70015;
    static final int GREEN = 0xFF248A3D;
    static final int ORANGE = 0xFFFF9500;
    static final int BADGE = 0xFFFF3B30;
    static final int OFF_TEXT = 0xFFB4B4BB;
    static final int FIELD = 0xFFE3E3E8;
    /** The theme's highlight, for the item under the d-pad in a menu or dialog. */
    static final int WASH = 0x22007AFF;
    static final int RING_TRACK = 0xFFD1D1D6;

    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    static final int BAR_HEIGHT = 34;
    static final int TAB_HEIGHT = 36;
    static final int GUTTER = 10;

    private Look() {}

    static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            context.getResources().getDisplayMetrics()));
    }

    static TextView text(Context context, CharSequence value, float sp, int color, Typeface face) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(face);
        view.setIncludeFontPadding(false);
        return view;
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radiusPx);
        return shape;
    }
}
