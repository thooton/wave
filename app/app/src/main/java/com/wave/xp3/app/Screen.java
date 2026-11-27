package com.wave.xp3.app;

import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.wave.xp3.data.WaveData;

import java.util.ArrayList;
import java.util.List;

/**
 * One screen of Wave: what the bar says, the page under it, and what the keys
 * do there beyond the defaults (Up/Down move between focus stops, OK presses
 * the focused one, Left/Right switch tabs, Back goes back a screen).
 *
 * Each tab keeps a stack of these. A screen is built once, then told about
 * every new snapshot while it is the one showing ({@link #update}).
 */
abstract class Screen {

    final WaveActivity app;
    View view;
    /** The focus stop that had focus when this screen was last left, by key. */
    String focusKey;
    /** The name the bar shows for Back, set when the screen is pushed. */
    String backLabel;

    Screen(WaveActivity app) {
        this.app = app;
    }

    abstract String title();

    /** Builds the page; called once. */
    abstract View build();

    /** Shows `snapshot`; called after build and on every change while showing. */
    void update(WaveData.Snapshot snapshot) {}

    /** Puts what this screen has on the bar beyond title and Back (field or spinner). */
    void bar(Bar bar) {}

    /** Modal screens hide the tab bar and keep Left/Right. */
    boolean modal() {
        return false;
    }

    /** No bar at all (the no-root screen). */
    boolean bare() {
        return false;
    }

    /** Whether Left/Right belong to this screen (a text cursor, two buttons) rather than the tabs. */
    boolean ownsLeftRight(View focus) {
        return false;
    }

    /** First look at a key; true when it was handled. */
    boolean onKey(KeyEvent event) {
        return false;
    }

    /** Back on this screen; true when it was handled here (the screen stays). */
    boolean onBack() {
        return false;
    }

    /** What the bar's Back says over `below`: its title, or nothing on a modal or bare screen. */
    String backTo(Screen below) {
        return modal() || bare() ? null : below.title();
    }

    /** The menu's items for where the focus is. */
    void menu(Menu menu) {}

    /** The screen's recycled list, when its page is one (set by build). */
    Feed feed;

    /** The focus stop to start on; with a list, null means its first row. */
    View firstFocus() {
        return feed != null ? null : firstFocusable(view);
    }

    /** Puts the focus back on the stop with {@link #focusKey}, or the first; false when the page has none. */
    boolean placeFocus() {
        if (feed != null && focusKey != null && feed.focus(focusKey, false)) return true;
        if (feed == null && focusKey != null) {
            View v = findKey(view, focusKey);
            if (v != null && v.isFocusable()) {
                v.requestFocus();
                return true;
            }
        }
        View first = firstFocus();
        if (first != null) {
            first.requestFocus();
            return true;
        }
        return feed != null && feed.focus(null, true);
    }

    static View findKey(View root, String key) {
        if (key.equals(root.getTag(R_KEY))) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findKey(g.getChildAt(i), key);
                if (found != null) return found;
            }
        }
        return null;
    }

    void shown() {}

    void hidden() {}

    static View firstFocusable(View root) {
        if (root == null || root.getVisibility() != View.VISIBLE) return null;
        if (root.isFocusable() && !(root instanceof ScrollView)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = firstFocusable(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return root.isFocusable() ? root : null;
    }

    /** The items of a menu, each with what it does. A title starting "!" is drawn red. */
    static final class Menu {
        final List<String> titles = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        Menu add(String title, Runnable action) {
            titles.add(title);
            actions.add(action);
            return this;
        }

        boolean isEmpty() {
            return titles.isEmpty();
        }
    }

    /**
     * A page that is a column of rows in a ScrollView, refilled whole on each
     * snapshot with the focus put back on the row with the same key.
     */
    static final class Page {
        final ScrollView scroll;
        final LinearLayout column;

        Page(WaveActivity app) {
            scroll = new ScrollView(app);
            scroll.setFillViewport(true);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setFocusable(false);
            scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            column = new LinearLayout(app);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setBackgroundColor(0xFFFFFFFF);
            scroll.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        /** The key of the focused row in this page, or null. */
        String focusedKey() {
            View focus = scroll.findFocus();
            while (focus != null && focus != scroll) {
                if (focus.getTag(R_KEY) != null) return (String) focus.getTag(R_KEY);
                focus = focus.getParent() instanceof View ? (View) focus.getParent() : null;
            }
            return null;
        }

        View find(String key) {
            if (key == null) return null;
            return findKey(column, key);
        }

        private static View findKey(View root, String key) {
            if (key.equals(root.getTag(R_KEY))) return root;
            if (root instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) root;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View found = findKey(group.getChildAt(i), key);
                    if (found != null) return found;
                }
            }
            return null;
        }

        /** Empties the column, keeping where the focus and the scroll were to put back with {@link #restore}. */
        Mark clear() {
            Mark mark = new Mark(focusedKey(), scroll.getScrollY(), scroll.hasFocus());
            column.removeAllViews();
            return mark;
        }

        void restore(Mark mark, String fallbackKey) {
            final int y = mark.scrollY;
            scroll.scrollTo(0, y);
            if (!mark.hadFocus) return;
            View target = find(mark.key);
            if (target == null) target = find(fallbackKey);
            if (target == null) target = firstFocusable(scroll);
            if (target != null) target.requestFocus();
        }

        void add(View view) {
            column.addView(view);
        }
    }

    static final class Mark {
        final String key;
        final int scrollY;
        final boolean hadFocus;

        Mark(String key, int scrollY, boolean hadFocus) {
            this.key = key;
            this.scrollY = scrollY;
            this.hadFocus = hadFocus;
        }
    }

    static final int R_KEY = 0x7f0a7101;

    static <V extends View> V keyed(V view, String key) {
        view.setTag(R_KEY, key);
        return view;
    }

    static String keyOf(View view) {
        while (view != null) {
            Object key = view.getTag(R_KEY);
            if (key != null) return (String) key;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return null;
    }

    static TextView paragraph(WaveActivity app, CharSequence text) {
        TextView p = Look.text(app, text, 12.5f, Look.TEXT, Look.REGULAR);
        p.setLineSpacing(Look.dp(app, 1.5f), 1f);
        p.setPadding(Look.dp(app, 10), 0, Look.dp(app, 10), Look.dp(app, 6));
        return p;
    }
}
