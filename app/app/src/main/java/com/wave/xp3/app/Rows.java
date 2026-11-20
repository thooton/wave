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


/** The rows of the mockups, filled from WaveData's values. */
final class Rows {

    private Rows() {}

    static AppIcons.Ref icon(WaveActivity app, Pkg p) {
        boolean onPhone = !p.isModule && p.state != State.NONE;
        return new AppIcons.Ref(p.id, p.name, p.iconFile, null, onPhone ? p.id : null);
    }

    static boolean unfit(Pkg p) {
        return Actions.unfit(p);
    }

    /**
     * A package as it is browsed (a section, All Packages, Featured, search):
     * OK opens its details, so it ends in a chevron; its state is a badge on
     * the icon; one that can't be installed here is dimmed.
     */
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
            // As wide as the name (it is one ellipsized line either way): a new
            // text then lays itself out at that width, and the row is the same
            // size whatever it says, so a rebind needn't measure it again.
            sub.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
            addView(Kit.chevron(app));
        }

        /*
         * Every browse row is the same size by construction (fixed height,
         * fixed-size icon and chevron, texts as wide as what's left, one line
         * each), so a measurement is kept and reused for the same constraints
         * until the detail line comes or goes.
         */
        private int specW = -1, specH = -1, sizeW, sizeH;
        private boolean subShown = true;

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            if (widthSpec == specW && heightSpec == specH) {
                setMeasuredDimension(sizeW, sizeH);
                return;
            }
            super.onMeasure(widthSpec, heightSpec);
            specW = widthSpec;
            specH = heightSpec;
            sizeW = getMeasuredWidth();
            sizeH = getMeasuredHeight();
        }

        BrowseRow bind(Pkg p, View.OnClickListener open, boolean last) {
            about = p;
            app.icons.into(icon.image, icon(app, p), 32);
            icon.setBadge(badge(p));
            name.setText(p.name);
            sub.setText(p.summary);
            boolean shown = p.summary != null && !p.summary.isEmpty();
            if (shown != subShown) {
                subShown = shown;
                specW = -1; // the texts move: measure them again
            }
            sub.setVisibility(shown ? VISIBLE : GONE);
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
        return Actions.badge(p);
    }

    /** What the capsule says for `p` on Installed, or null for none (a mod, Wave itself, nothing to open). */
    static String capsuleWord(WaveActivity app, Pkg p) {
        return Actions.capsule(p, app::canOpen);
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
        private final Kit.Link capsule;
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
            String line = Pages.updateLine(p);
            name.setText(p.name);
            sub.setText(line);
            String word = capsuleWord(app, p);
            capsule.setWord(word != null ? word : "");
            capsule.setVisibility(word != null ? VISIBLE : GONE);
            chevron.setVisibility(word != null ? GONE : VISIBLE);
            boolean hasNotes = Pages.hasNotes(p);
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
        private final Kit.Link capsule;
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
            Pages.Line shown = Pages.installedLine(p, p.state == State.OFF && bootFailed(app, p.id));
            String line = shown.text;
            int color = Look.color(shown.tone);
            name.setText(p.name);
            sub.setText(line);
            sub.setTextColor(color);
            String word = Pages.installedCapsule(p, app::canOpen);
            capsule.setWord(word != null ? word : "");
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

    /** The root status is an action only when Wave does not have root. */
    static View rootStatus(WaveActivity app, WaveData.Root root) {
        Pages.Line status = Pages.rootStatus(root);
        boolean needsRoot = root == WaveData.Root.NONE;
        int glyph = needsRoot ? Glyph.WARN : root == WaveData.Root.OK ? Glyph.TICK : Glyph.INFO;
        View icon = new Glyph(app, glyph, Look.color(status.tone));
        if (needsRoot) {
            Kit.Row row = link(app, status.text, null, 0, icon, 14, null, false);
            row.setOnClickListener(v -> app.push(new NoRootScreen(app)));
            return Screen.keyed(row, "root");
        }
        Kit.Row row = new Kit.Row(app);
        row.setLayoutParams(Kit.fill(Kit.dp(app, 40)));
        row.setFocusable(false);
        row.setClickable(false);
        row.addView(icon);
        row.addView(Kit.texts(app, status.text, null, 0, 9));
        return row;
    }

    /** The Reboot row of Wave and Installed: a focus stop whose action is Reboot. */
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
        return Pages.rebootText(names);
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

    static WaveData.Pkg none() {
        return null;
    }
}
