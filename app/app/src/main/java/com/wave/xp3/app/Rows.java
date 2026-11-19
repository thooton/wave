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
        return new BrowseRow(app).bind(p, open, false);
    }

    /** {@link #browse} as a line of a {@link Feed}, recycled. */
    static Feed.Entry browseLine(final WaveActivity app, final Pkg p, final View.OnClickListener open, final boolean last) {
        return new Feed.Entry("pkg:" + p.id, Feed.BROWSE, true) {
            @Override
            View view(View recycled) {
                BrowseRow row = recycled instanceof BrowseRow ? (BrowseRow) recycled : new BrowseRow(app);
                return row.bind(p, open, last);
            }

            @Override
            Object look() {
                return new PkgLook(p, last);
            }
        };
    }

    /**
     * A browse line's look: everything a Pkg holds, and whether it is the last
     * line. A row kept because its look is equal still holds the old Pkg (and
     * the open action made with it); with every field equal, that Pkg is as
     * good as the new one.
     */
    static final class PkgLook {
        private final Pkg p;
        private final boolean last;

        PkgLook(Pkg p, boolean last) {
            this.p = p;
            this.last = last;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PkgLook)) return false;
            PkgLook l = (PkgLook) o;
            Pkg q = l.p;
            return last == l.last && p.state == q.state && p.needsNewerWave == q.needsNewerWave && p.isModule == q.isModule
                && eq(p.id, q.id) && eq(p.name, q.name) && eq(p.summary, q.summary) && eq(p.version, q.version)
                && eq(p.installedVersion, q.installedVersion) && eq(p.unfitReason, q.unfitReason)
                && eq(p.sourceId, q.sourceId) && eq(p.sourceName, q.sourceName) && eq(p.sectionName, q.sectionName)
                && eq(p.iconFile, q.iconFile) && sameChange(p.newestChange, q.newestChange);
        }

        @Override
        public int hashCode() {
            return p.id.hashCode();
        }

        private static boolean sameChange(WaveData.Change a, WaveData.Change b) {
            if (a == null || b == null) return a == b;
            return eq(a.version, b.version) && eq(a.date, b.date) && eq(a.text, b.text);
        }

        private static boolean eq(Object a, Object b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    static final class BrowseRow extends Kit.Row {
        private final WaveActivity app;
        private final Kit.BadgedIcon icon;
        private final TextView name;
        private final TextView sub;

        BrowseRow(WaveActivity app) {
            super(app);
            this.app = app;
            setLayoutParams(Kit.fill(Kit.dp(app, 47)));
            // Centred at a fixed height, so baselines place nothing; with them
            // on, LinearLayout measures the texts an extra time per bind.
            setBaselineAligned(false);
            icon = new Kit.BadgedIcon(app, 32);
            addView(icon);
            LinearLayout texts = Kit.texts(app, "", "-", Look.MUTED, 8);
            addView(texts);
            name = Kit.name(texts);
            sub = (TextView) texts.getChildAt(1);
            addView(Kit.chevron(app));
        }

        BrowseRow bind(Pkg p, View.OnClickListener open, boolean last) {
            about = p;
            app.icons.into(icon.image, icon(app, p), 32);
            icon.setBadge(badge(p));
            name.setText(p.name);
            sub.setText(p.summary);
            sub.setVisibility(p.summary == null || p.summary.isEmpty() ? GONE : VISIBLE);
            boolean dim = unfit(p);
            name.setTextColor(dim ? 0x73000000 : Look.TEXT);
            icon.setAlpha(dim ? 0.45f : 1f);
            setHairline(!last);
            setOnClickListener(open);
            Screen.keyed(this, "pkg:" + p.id);
            setContentDescription(p.name + ", " + p.summary);
            return this;
        }
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
                // An update this phone can't take: the row is as up to date as it can be.
                if (unfit(p)) return app.canOpen(p) ? "OPEN" : null;
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
        return new UpdateRow(app).bind(p, press, false);
    }

    static Feed.Entry updateLine(final WaveActivity app, final Pkg p, final View.OnClickListener press, final boolean last) {
        return new Feed.Entry("pkg:" + p.id, Feed.UPDATE, true) {
            @Override
            View view(View recycled) {
                UpdateRow row = recycled instanceof UpdateRow ? (UpdateRow) recycled : new UpdateRow(app);
                return row.bind(p, press, last);
            }
        };
    }

    static final class UpdateRow extends Kit.Row {
        private final WaveActivity app;
        private final ImageView icon;
        private final TextView name;
        private final TextView sub;
        private final TextView capsule;
        private final View chevron;
        private final TextView notes;

        UpdateRow(WaveActivity app) {
            super(app);
            this.app = app;
            setOrientation(LinearLayout.VERTICAL);
            setGravity(Gravity.NO_GRAVITY);
            setPadding(Kit.dp(app, 10), Kit.dp(app, 8), Kit.dp(app, 10), Kit.dp(app, 8));
            setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout top = new LinearLayout(app);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setBaselineAligned(false); // only the capsule has a baseline: it places nothing
            top.setDuplicateParentStateEnabled(true);
            icon = Kit.iconView(app, 40);
            top.addView(icon);
            LinearLayout texts = Kit.texts(app, "", "-", Look.MUTED, 8);
            top.addView(texts);
            name = Kit.name(texts);
            sub = (TextView) texts.getChildAt(1);
            capsule = Kit.capsule(app, "UPDATE", false, false);
            top.addView(capsule);
            chevron = Kit.chevron(app);
            top.addView(chevron);
            addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            notes = Look.text(app, "", 12, Look.TEXT, Look.REGULAR);
            notes.setMaxLines(2);
            notes.setEllipsize(TextUtils.TruncateAt.END);
            notes.setLineSpacing(Kit.dp(app, 1), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Kit.dp(app, 7);
            addView(notes, lp);
        }

        UpdateRow bind(Pkg p, View.OnClickListener press, boolean last) {
            about = p;
            app.icons.into(icon, icon(app, p), 40);
            String when = p.newestChange != null && p.newestChange.date != null ? Format.date(p.newestChange.date) : null;
            String line = when != null ? when : "Version " + p.version;
            name.setText(p.name);
            sub.setText(line);
            String word = capsuleWord(app, p);
            capsule.setText(word != null ? word : "");
            capsule.setVisibility(word != null ? VISIBLE : GONE);
            chevron.setVisibility(word != null ? GONE : VISIBLE);
            boolean hasNotes = p.newestChange != null && p.newestChange.text != null && !p.newestChange.text.isEmpty();
            notes.setText(hasNotes ? p.newestChange.text : "");
            notes.setVisibility(hasNotes ? VISIBLE : GONE);
            setHairline(!last);
            setOnClickListener(press);
            Screen.keyed(this, "pkg:" + p.id);
            setContentDescription(p.name + ", " + (word != null ? word : line));
            return this;
        }
    }

    /** An installed package with nothing new: the Updater's row without notes. */
    static Kit.Row installed(final WaveActivity app, final Pkg p, View.OnClickListener press) {
        return new InstalledRow(app).bind(p, press, false);
    }

    static Feed.Entry installedLine(final WaveActivity app, final Pkg p, final View.OnClickListener press, final boolean last) {
        return new Feed.Entry("pkg:" + p.id, Feed.INSTALLED, true) {
            @Override
            View view(View recycled) {
                InstalledRow row = recycled instanceof InstalledRow ? (InstalledRow) recycled : new InstalledRow(app);
                return row.bind(p, press, last);
            }
        };
    }

    static final class InstalledRow extends Kit.Row {
        private final WaveActivity app;
        private final ImageView icon;
        private final TextView name;
        private final TextView sub;
        private final TextView capsule;
        private final View chevron;

        InstalledRow(WaveActivity app) {
            super(app);
            this.app = app;
            setLayoutParams(Kit.fill(Kit.dp(app, 47)));
            setBaselineAligned(false); // centred at a fixed height (see BrowseRow)
            icon = Kit.iconView(app, 32);
            addView(icon);
            LinearLayout texts = Kit.texts(app, "", "-", Look.MUTED, 8);
            addView(texts);
            name = Kit.name(texts);
            sub = (TextView) texts.getChildAt(1);
            capsule = Kit.capsule(app, "OPEN", false, false);
            addView(capsule);
            chevron = Kit.chevron(app);
            addView(chevron);
        }

        InstalledRow bind(Pkg p, View.OnClickListener press, boolean last) {
            about = p;
            app.icons.into(icon, icon(app, p), 32);
            String line;
            int color = Look.MUTED;
            switch (p.state) {
                case OFF:
                    line = bootFailed(app, p.id) ? "Turned off: boot failed" : "Turned off";
                    color = Look.RED;
                    break;
                case PENDING_INSTALL:
                    line = "Starts after a reboot";
                    color = Look.BLUE;
                    break;
                case PENDING_REMOVAL:
                    line = "Removed after a reboot";
                    color = Look.BLUE;
                    break;
                default:
                    line = (p.isModule ? "System mod, " : "Version ") + (p.installedVersion != null ? p.installedVersion : p.version);
            }
            name.setText(p.name);
            sub.setText(line);
            sub.setTextColor(color);
            String word = p.state == State.OFF || p.state == State.PENDING_INSTALL || p.state == State.PENDING_REMOVAL ? null : capsuleWord(app, p);
            capsule.setText(word != null ? word : "");
            capsule.setVisibility(word != null ? VISIBLE : GONE);
            chevron.setVisibility(word != null ? GONE : VISIBLE);
            setHairline(!last);
            setOnClickListener(press);
            Screen.keyed(this, "pkg:" + p.id);
            setContentDescription(p.name + ", " + line);
            return this;
        }
    }

    /** Whether the watchdog turned `id` off (rather than the user or Magisk's safe mode). */
    static boolean bootFailed(WaveActivity app, String id) {
        for (WaveData.TurnedOff off : app.snapshot.turnedOff) if (off.id.equals(id)) return true;
        return false;
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
