package com.wave.xp3.app;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Pkg;
import com.wave.xp3.data.WaveData.State;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** The rows of the mockups, filled from WaveData's values. */
final class Rows {

    private Rows() {}

    static AppIcons.Ref icon(WaveActivity app, Pkg p) {
        boolean onPhone = !p.isModule && p.state != State.NONE;
        return new AppIcons.Ref(p.id, p.name, p.iconFile, null, onPhone ? p.id : null);
    }

    static boolean unfit(Pkg p) {
        return p.unfitReason != null || p.needsNewerWave;
    }

    /**
     * A package as it is browsed (a section, All Packages, Featured, search):
     * OK opens its details, so it ends in a chevron; its state is a badge on
     * the icon; one that can't be installed here is dimmed.
     */
    static Kit.Row browse(final WaveActivity app, final Pkg p, View.OnClickListener open) {
        Kit.Row row = new Kit.Row(app);
        row.about = p;
        row.setLayoutParams(Kit.fill(Kit.dp(app, 47)));
        Kit.BadgedIcon icon = new Kit.BadgedIcon(app, 32);
        app.icons.into(icon.image, icon(app, p), 32);
        icon.setBadge(badge(p));
        row.addView(icon);
        LinearLayout texts = Kit.texts(app, p.name, p.summary, Look.MUTED, 8);
        row.addView(texts);
        if (unfit(p)) {
            Kit.name(texts).setTextColor(0x73000000);
            icon.setAlpha(0.45f);
        }
        row.addView(Kit.chevron(app));
        row.setOnClickListener(open);
        Screen.keyed(row, "pkg:" + p.id);
        row.setContentDescription(p.name + ", " + p.summary);
        return row;
    }

    static int badge(Pkg p) {
        switch (p.state) {
            case UPDATE:
                return 2;
            case INSTALLED:
            case NEWER:
            case OFF:
            case PENDING_INSTALL:
                return 1;
            default:
                return 0;
        }
    }

    /** What the capsule says for `p` on Installed, or null for none (a mod, Wave itself, nothing to open). */
    static String capsuleWord(WaveActivity app, Pkg p) {
        if (unfit(p) && p.state == State.NONE) return null;
        switch (p.state) {
            case NONE:
                return "GET";
            case UPDATE:
                return "UPDATE";
            case INSTALLED:
            case NEWER:
                return app.canOpen(p) ? "OPEN" : null;
            default:
                return null;
        }
    }

    /**
     * The Updater's row, for an update on Installed: a 40dp icon, the name,
     * the date of the newest change, the capsule, and two lines of what's new.
     */
    static Kit.Row update(final WaveActivity app, final Pkg p, View.OnClickListener press) {
        Kit.Row row = new Kit.Row(app);
        row.about = p;
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.NO_GRAVITY);
        row.setPadding(Kit.dp(app, 10), Kit.dp(app, 8), Kit.dp(app, 10), Kit.dp(app, 8));
        LinearLayout top = new LinearLayout(app);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setDuplicateParentStateEnabled(true);
        ImageView icon = Kit.iconView(app, 40);
        app.icons.into(icon, icon(app, p), 40);
        top.addView(icon);
        String when = p.newestChange != null && p.newestChange.date != null ? Format.date(p.newestChange.date) : null;
        String sub = when != null ? when : "Version " + p.version;
        top.addView(Kit.texts(app, p.name, sub, Look.MUTED, 8));
        String word = capsuleWord(app, p);
        if (word != null) top.addView(Kit.capsule(app, word, false, false));
        else top.addView(Kit.chevron(app));
        row.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (p.newestChange != null && p.newestChange.text != null && !p.newestChange.text.isEmpty()) {
            TextView notes = Look.text(app, p.newestChange.text, 12, Look.TEXT, Look.REGULAR);
            notes.setMaxLines(2);
            notes.setEllipsize(TextUtils.TruncateAt.END);
            notes.setLineSpacing(Kit.dp(app, 1), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Kit.dp(app, 7);
            row.addView(notes, lp);
        }
        row.setOnClickListener(press);
        Screen.keyed(row, "pkg:" + p.id);
        row.setContentDescription(p.name + ", " + (word != null ? word : sub));
        return row;
    }

    /** An installed package with nothing new: the Updater's row without notes. */
    static Kit.Row installed(final WaveActivity app, final Pkg p, View.OnClickListener press) {
        Kit.Row row = new Kit.Row(app);
        row.about = p;
        row.setLayoutParams(Kit.fill(Kit.dp(app, 47)));
        ImageView icon = Kit.iconView(app, 32);
        app.icons.into(icon, icon(app, p), 32);
        row.addView(icon);
        String sub;
        int color = Look.MUTED;
        switch (p.state) {
            case OFF:
                sub = "Turned off: boot failed";
                color = Look.RED;
                break;
            case PENDING_INSTALL:
                sub = "Starts after a reboot";
                color = Look.BLUE;
                break;
            case PENDING_REMOVAL:
                sub = "Removed after a reboot";
                color = Look.BLUE;
                break;
            default:
                sub = (p.isModule ? "System mod, " : "Version ") + (p.installedVersion != null ? p.installedVersion : p.version);
        }
        row.addView(Kit.texts(app, p.name, sub, color, 8));
        String word = p.state == State.OFF || p.state == State.PENDING_INSTALL || p.state == State.PENDING_REMOVAL ? null : capsuleWord(app, p);
        if (word != null) row.addView(Kit.capsule(app, word, false, false));
        else row.addView(Kit.chevron(app));
        row.setOnClickListener(press);
        Screen.keyed(row, "pkg:" + p.id);
        row.setContentDescription(p.name + ", " + sub);
        return row;
    }

    /** A row that opens something: an optional icon, a name, optional detail and count, a chevron. */
    static Kit.Row link(WaveActivity app, String name, String sub, int subColor, View icon, int iconDp, String count, boolean tall) {
        Kit.Row row = new Kit.Row(app);
        row.setLayoutParams(Kit.fill(Kit.dp(app, tall ? 46 : 40)));
        if (icon != null) {
            icon.setLayoutParams(new LinearLayout.LayoutParams(Kit.dp(app, iconDp), Kit.dp(app, iconDp)));
            row.addView(icon);
        }
        row.addView(Kit.texts(app, name, sub, subColor, icon != null ? 9 : 0));
        if (count != null) {
            TextView c = Look.text(app, count, 13, Look.MUTED, Look.REGULAR);
            LinearLayout.LayoutParams lp = Kit.wrap();
            lp.rightMargin = Kit.dp(app, 8);
            row.addView(c, lp);
        }
        row.addView(Kit.chevron(app));
        row.setContentDescription(name + (sub != null ? ", " + sub : ""));
        return row;
    }

    /** The Reboot row of Wave and Installed: a focus stop whose capsule is REBOOT. */
    static Kit.Row reboot(WaveActivity app, String what) {
        Kit.Row row = new Kit.Row(app);
        row.setPadding(Kit.dp(app, 10), Kit.dp(app, 8), Kit.dp(app, 10), Kit.dp(app, 8));
        row.addView(new Glyph(app, Glyph.REBOOT, Look.BLUE));
        LinearLayout texts = new LinearLayout(app);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = Look.text(app, "Reboot to Finish", 14, Look.TEXT, Look.MEDIUM);
        texts.addView(title);
        TextView sub = Look.text(app, what, 12.5f, Look.MUTED, Look.REGULAR);
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sl = Kit.wrap();
        sl.topMargin = Kit.dp(app, 1);
        texts.addView(sub, sl);
        LinearLayout.LayoutParams tl = Kit.weight();
        tl.leftMargin = tl.rightMargin = Kit.dp(app, 8);
        row.addView(texts, tl);
        row.addView(Kit.capsule(app, "REBOOT", false, false));
        row.setOnClickListener(v -> app.askReboot());
        Screen.keyed(row, "reboot");
        row.setContentDescription("Reboot to Finish, " + what);
        return row;
    }

    /** "Messages Improved starts after a reboot", or the count of them. */
    static String rebootText(java.util.List<String> names) {
        if (names.size() == 1) return names.get(0) + " starts after a reboot";
        return names.size() + " changes start after a reboot";
    }

    /** A label and its value, as text: no focus stop. */
    static View fact(WaveActivity app, String label, String value, boolean last) {
        Kit.Row row = new Kit.Row(app);
        row.setFocusable(false);
        row.setClickable(false);
        row.setBackgroundColor(0xFFFFFFFF);
        if (last) row.last();
        row.setLayoutParams(Kit.fill(Kit.dp(app, 30)));
        TextView l = Kit.line(app, label, 13, Look.MUTED);
        row.addView(l, Kit.weight());
        TextView v = Kit.line(app, value == null ? "" : value, 13, Look.TEXT);
        v.setMaxWidth(Kit.dp(app, 150));
        v.setGravity(Gravity.END);
        row.addView(v);
        return row;
    }

    /** A row of the queue: icon, name, the step it is at, and what is on its right. */
    static View queue(WaveActivity app, Pkg p, String sub, int subColor, View right, boolean compact, boolean last, boolean dim) {
        Kit.Row row = new Kit.Row(app);
        row.setFocusable(false);
        row.setClickable(false);
        row.setBackgroundColor(0xFFFFFFFF);
        if (last) row.last();
        row.setLayoutParams(Kit.fill(Kit.dp(app, compact ? 42 : 46)));
        ImageView icon = Kit.iconView(app, 30);
        app.icons.into(icon, icon(app, p), 30);
        row.addView(icon);
        LinearLayout texts = Kit.texts(app, p.name, sub, subColor, 8);
        row.addView(texts);
        if (dim) {
            icon.setAlpha(0.5f);
            Kit.name(texts).setTextColor(0x80000000);
        }
        if (right != null) row.addView(right);
        return row;
    }

    static Typeface medium() {
        return Look.MEDIUM;
    }

    /** Sizes, dates and versions as the screens say them. */
    static final class Format {
        private Format() {}

        static String size(long bytes) {
            if (bytes <= 0) return null;
            if (bytes < 1000 * 1000) return Math.max(1, Math.round(bytes / 1000.0)) + " KB";
            return String.format(Locale.US, "%.1f MB", bytes / 1000.0 / 1000.0);
        }

        /** "Today", "Yesterday", or "Nov 12, 2026", from YYYY-MM-DD. Anything else is returned as it is. */
        static String date(String iso) {
            if (iso == null) return null;
            try {
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                in.setLenient(false);
                Date day = in.parse(iso.length() >= 10 ? iso.substring(0, 10) : iso);
                Calendar then = Calendar.getInstance();
                then.setTime(day);
                Calendar now = Calendar.getInstance();
                if (sameDay(then, now)) return "Today";
                now.add(Calendar.DAY_OF_YEAR, -1);
                if (sameDay(then, now)) return "Yesterday";
                return new SimpleDateFormat("MMM d, yyyy", Locale.US).format(day);
            } catch (Exception e) {
                return iso;
            }
        }

        private static boolean sameDay(Calendar a, Calendar b) {
            return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
        }

        /** "refreshed today", "refreshed Nov 2", or null before the first refresh. */
        static String refreshed(long millis) {
            if (millis <= 0) return null;
            Calendar then = Calendar.getInstance();
            then.setTimeInMillis(millis);
            Calendar now = Calendar.getInstance();
            if (sameDay(then, now)) return "refreshed today";
            now.add(Calendar.DAY_OF_YEAR, -1);
            if (sameDay(then, now)) return "refreshed yesterday";
            return "refreshed " + new SimpleDateFormat("MMM d", Locale.US).format(new Date(millis));
        }

        /** An address without its scheme, as the rows show it. */
        static String shortUrl(String url) {
            if (url == null) return "";
            String s = url.replaceFirst("^[a-zA-Z]+://", "");
            if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
            if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            return s;
        }
    }

    static WaveData.Pkg none() {
        return null;
    }
}
