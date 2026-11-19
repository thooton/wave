package com.wave.xp3.app;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A page that is a recycled list, for the pages that can be long (a source,
 * a section, All Packages, Installed, Search, Sources): only the rows on
 * screen exist, and a row scrolled off is rebound to the next package. It
 * is a framework ListView (Wave has no libraries), drawn and driven like
 * {@link Screen.Page}: rows are the focus stops, Up/Down move between them
 * and the list scrolls the least that shows the new one (and a heading or
 * gap next to it at either end); headings, gaps and headers are lines that
 * aren't stops.
 *
 * A screen refills it on each snapshot as before: {@link #clear} keeps
 * which row had the focus (by key) and where it sat, {@link #add} lines,
 * {@link #restore} shows them with the focus and the row back in place.
 * A page with nothing to list shows a message instead ({@link #message}).
 */
final class Feed {

    /** The kinds of recycled rows; other lines are fixed views. */
    static final int BROWSE = 0, INSTALLED = 1, UPDATE = 2, TYPES = 3;

    /** One line: a recycled row (type 0..TYPES-1) or a fixed view. */
    abstract static class Entry {
        final String key;
        final int type;
        final boolean stop;

        Entry(String key, int type, boolean stop) {
            this.key = key;
            this.type = type;
            this.stop = stop;
        }

        /** The view for this line, `recycled` being a view of the same type that showed another line, or null. */
        abstract View view(View recycled);
    }

    /** A view that is the line itself, kept whole (in a cell that keeps its margins). */
    static final class Fixed extends Entry {
        final View cell;

        Fixed(View view, FrameLayout cell) {
            super(Screen.keyOf(view), AdapterView.ITEM_VIEW_TYPE_IGNORE, Screen.firstFocusable(view) != null);
            this.cell = cell;
        }

        @Override
        View view(View recycled) {
            return cell;
        }
    }

    final FrameLayout root;
    final Lister list;
    private final WaveActivity app;
    private final Adapter adapter = new Adapter();
    private List<Entry> shown = Collections.emptyList();
    private List<Entry> staged = new ArrayList<>();
    private View message;
    /** The focus and its row's top, kept while the screen is away. */
    private String awayKey;
    private int awayTop;

    Feed(WaveActivity app) {
        this.app = app;
        root = new FrameLayout(app);
        root.setBackgroundColor(0xFFFFFFFF);
        list = new Lister(app);
        root.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setAdapter(adapter);
    }

    /** Starts a refill, keeping the focus and its place for {@link #restore}. */
    Screen.Mark clear() {
        staged = new ArrayList<>();
        View focus = list.findFocus();
        int top = 0;
        String key = null;
        if (focus != null) {
            int at = list.positionOf(focus);
            View row = list.childFor(at);
            if (row != null) top = row.getTop();
            if (at >= 0 && at < shown.size()) key = shown.get(at).key;
        }
        return new Screen.Mark(key, top, focus != null);
    }

    void add(Entry entry) {
        staged.add(entry);
    }

    /** Adds a fixed line (heading, gap, header, a link row). */
    void add(View view) {
        FrameLayout cell = new FrameLayout(view.getContext());
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        int w = ViewGroup.LayoutParams.MATCH_PARENT;
        int h = lp != null ? lp.height : ViewGroup.LayoutParams.WRAP_CONTENT;
        if (h == ViewGroup.LayoutParams.MATCH_PARENT || h == 0) h = ViewGroup.LayoutParams.WRAP_CONTENT;
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(w, h);
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            fl.setMargins(m.leftMargin, m.topMargin, m.rightMargin, m.bottomMargin);
        }
        cell.addView(view, fl);
        staged.add(new Fixed(view, cell));
    }

    /** Shows the staged lines, the focus back on the row with the mark's key (or `fallbackKey`, or the first). */
    void restore(Screen.Mark mark, String fallbackKey) {
        hideMessage();
        int first = list.getFirstVisiblePosition();
        View firstChild = list.getChildAt(0);
        int firstTop = firstChild != null ? firstChild.getTop() : 0;
        shown = staged;
        staged = new ArrayList<>();
        adapter.notifyDataSetChanged();
        if (mark.hadFocus) {
            int at = indexOf(mark.key);
            int top = mark.scrollY;
            if (at < 0) {
                at = indexOf(fallbackKey);
                top = -1;
            }
            if (at < 0) {
                at = firstStop();
                top = -1;
            }
            if (at >= 0) {
                place(at, top);
                return;
            }
        }
        if (first < shown.size()) showFrom(first, firstTop);
    }

    /**
     * Scrolls so line `pos` starts at `top`. ListView's setSelectionFromTop
     * puts the next selectable line there instead, so for a heading (or any
     * fixed line) the first stop after it goes lower by their heights.
     */
    private void showFrom(int pos, int top) {
        int at = pos;
        while (at < shown.size() && !shown.get(at).stop) {
            top += heightOf(shown.get(at));
            at++;
        }
        list.setSelectionFromTop(at < shown.size() ? at : pos, top);
    }

    private int heightOf(Entry entry) {
        if (!(entry instanceof Fixed)) return 0;
        View cell = ((Fixed) entry).cell;
        int width = list.getWidth() > 0 ? list.getWidth() : app.getResources().getDisplayMetrics().widthPixels;
        cell.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        ViewGroup.LayoutParams lp = cell.getLayoutParams();
        return lp != null && lp.height > 0 ? lp.height : cell.getMeasuredHeight();
    }

    /** Shows `view` (a message screen) in the list's place; the list empties. */
    void message(View view) {
        boolean hadFocus = root.hasFocus();
        shown = Collections.emptyList();
        staged = new ArrayList<>();
        adapter.notifyDataSetChanged();
        hideMessage();
        message = view;
        list.setVisibility(View.GONE);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (hadFocus) {
            View stop = Screen.firstFocusable(view);
            if (stop != null) stop.requestFocus();
        }
    }

    private void hideMessage() {
        if (message != null) {
            root.removeView(message);
            message = null;
        }
        list.setVisibility(View.VISIBLE);
    }

    boolean showingMessage() {
        return message != null;
    }

    /** Remembers the focused row and where it sat, for {@link #focus} when the screen comes back. */
    void remember() {
        View focus = list.findFocus();
        awayKey = null;
        if (focus == null) return;
        int at = list.positionOf(focus);
        View row = list.childFor(at);
        if (at >= 0 && at < shown.size()) {
            awayKey = shown.get(at).key;
            awayTop = row != null ? row.getTop() : 0;
        }
    }

    /**
     * Puts the focus on the row with `key` (back from another screen), or
     * with `orFirst` on the first stop; false when there is none.
     */
    boolean focus(String key, boolean orFirst) {
        if (message != null) {
            if (!orFirst) return false;
            View stop = Screen.firstFocusable(message);
            if (stop == null) return false;
            stop.requestFocus();
            return true;
        }
        int at = indexOf(key);
        int top = key != null && key.equals(awayKey) ? awayTop : -1;
        if (at < 0) {
            if (!orFirst) return false;
            at = firstStop();
            top = -1;
        }
        if (at < 0) return false;
        place(at, top);
        return true;
    }

    /** Whether `focus` is on the first stop (Up from it goes to the bar). */
    boolean atTop(View focus) {
        if (message != null) return focus == Screen.firstFocusable(message);
        int at = list.positionOf(focus);
        return at >= 0 && at == firstStop();
    }

    /** The first stop's view, if laid out (Search's Down). */
    void focusFirst() {
        int at = firstStop();
        if (at >= 0) place(at, -1);
    }

    boolean hasStops() {
        return firstStop() >= 0;
    }

    private void place(int at, int top) {
        if (top < 0) {
            // A row with no remembered place: at the top, with any heading above it.
            int head = at;
            while (head > 0 && !shown.get(head - 1).stop) head--;
            showFrom(head, 0);
        } else {
            list.setSelectionFromTop(at, top);
        }
        list.focusAfterLayout(at);
    }

    private int indexOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < shown.size(); i++) if (key.equals(shown.get(i).key)) return i;
        return -1;
    }

    private int firstStop() {
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).stop) return i;
        return -1;
    }

    private int nextStop(int from, int dir) {
        for (int i = from + dir; i >= 0 && i < shown.size(); i += dir) if (shown.get(i).stop) return i;
        return -1;
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return TYPES;
        }

        @Override
        public int getItemViewType(int position) {
            return shown.get(position).type;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return shown.get(position).stop;
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            Entry entry = shown.get(position);
            if (entry instanceof Fixed && ((Fixed) entry).cell.getParent() != null && ((Fixed) entry).cell.getParent() != parent) {
                ((ViewGroup) ((Fixed) entry).cell.getParent()).removeView(((Fixed) entry).cell);
            }
            return entry.view(entry.type == AdapterView.ITEM_VIEW_TYPE_IGNORE ? null : recycled);
        }
    }

    /**
     * The ListView: no dividers, selector, scroll bars or glow; rows take the
     * focus themselves, and Up/Down move it from stop to stop here rather
     * than through ListView's selection.
     */
    final class Lister extends ListView {
        private int focusAfterLayout = -1;

        Lister(Context context) {
            super(context);
            setDivider(null);
            setDividerHeight(0);
            setSelector(new ColorDrawable(0));
            setCacheColorHint(0);
            setItemsCanFocus(true);
            setFocusable(false);
            setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(View.OVER_SCROLL_NEVER);
            setVerticalFadingEdgeEnabled(false);
            setBackgroundColor(0xFFFFFFFF);
        }

        void focusAfterLayout(int position) {
            focusAfterLayout = position;
            requestLayout();
        }

        int positionOf(View view) {
            View v = view;
            while (v != null && v.getParent() != this) v = v.getParent() instanceof View ? (View) v.getParent() : null;
            if (v == null) return -1;
            return getFirstVisiblePosition() + indexOfChild(v);
        }

        View childFor(int position) {
            int i = position - getFirstVisiblePosition();
            return i >= 0 && i < getChildCount() ? getChildAt(i) : null;
        }

        @Override
        protected void layoutChildren() {
            // A layout detaches every row; the focused one can lose the focus to the
            // window's first stop (the bar's link) on the way. It comes back here.
            View had = findFocus();
            int hadAt = had != null ? positionOf(had) : -1;
            laying = true;
            try {
                super.layoutChildren();
            } finally {
                laying = false;
            }
            int want = focusAfterLayout >= 0 ? focusAfterLayout : hadAt;
            focusAfterLayout = -1;
            if (want >= 0) {
                View row = childFor(want);
                View stop = row != null ? Screen.firstFocusable(row) : null;
                if (stop != null && !stop.hasFocus()) stop.requestFocus();
            }
        }

        private boolean laying;

        /*
         * ListView keeps a selected position of its own, and each layout
         * anchors on it and gives it the focus: left behind on a row scrolled
         * away, it would pull the list back there. So it follows the focus.
         */
        @Override
        public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            if (laying || child == null || child.getParent() != this) return;
            int at = getFirstVisiblePosition() + indexOfChild(child);
            if (at >= 0 && at < getCount() && at != getSelectedItemPosition()) setSelectionFromTop(at, child.getTop());
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            int key = event.getKeyCode();
            if ((key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) && findFocus() != null) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) move(key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        /** Moves the focus to the next stop down (1) or up (-1), scrolling the least that shows it. */
        private void move(int dir) {
            int from = positionOf(findFocus());
            if (from < 0) return;
            int to = nextStop(from, dir);
            int h = getHeight();
            if (to < 0) {
                // No stop further: show what is left (the heading at the top, the gap at the bottom).
                if (dir < 0) {
                    View first = getChildAt(0);
                    if (getFirstVisiblePosition() > 0 || (first != null && first.getTop() < 0)) {
                        scrollListBy(Math.max(-h / 2, first != null && getFirstVisiblePosition() == 0 ? first.getTop() : -h / 2));
                    }
                } else {
                    View last = getChildAt(getChildCount() - 1);
                    if (getLastVisiblePosition() < shown.size() - 1 || (last != null && last.getBottom() > h)) {
                        scrollListBy(Math.min(h / 2, last != null && getLastVisiblePosition() == shown.size() - 1 ? last.getBottom() - h : h / 2));
                    }
                }
                return;
            }
            View row = childFor(to);
            // The next stop just past the edge: scroll it into being now (scrollListBy fills the gap
            // at once), so the focus moves before the next key; a deferred layout would drop keys.
            for (int tries = 0; row == null && tries < 8; tries++) {
                if (dir > 0) {
                    if (to <= getLastVisiblePosition()) break;
                    View last = getChildAt(getChildCount() - 1);
                    if (last == null || getLastVisiblePosition() >= shown.size() - 1) break;
                    scrollListBy(Math.max(1, last.getBottom() - h + 1));
                } else {
                    if (to >= getFirstVisiblePosition()) break;
                    View first = getChildAt(0);
                    if (first == null || getFirstVisiblePosition() <= 0) break;
                    scrollListBy(Math.min(-1, first.getTop() - 1));
                }
                row = childFor(to);
            }
            if (row == null) {
                // Not laid out (a long jump past fixed lines): bring it to the edge it comes in from.
                setSelectionFromTop(to, dir > 0 ? Math.max(0, h - Look.dp(getContext(), 47)) : 0);
                focusAfterLayout(to);
                return;
            }
            // The lines that come along with it (a heading above it going up, the end
            // gap after the last stop going down): scrolled into being too.
            for (int tries = 0; tries < 8; tries++) {
                if (dir < 0) {
                    int lo = to;
                    while (lo > 0 && !shown.get(lo - 1).stop) lo--;
                    View first = getChildAt(0);
                    if (lo >= getFirstVisiblePosition() || first == null) break;
                    scrollListBy(Math.min(-1, first.getTop() - 1));
                } else {
                    if (nextStop(to, 1) >= 0 || getLastVisiblePosition() >= shown.size() - 1) break;
                    View last = getChildAt(getChildCount() - 1);
                    if (last == null) break;
                    scrollListBy(Math.max(1, last.getBottom() - h + 1));
                }
            }
            row = childFor(to);
            if (row == null) {
                setSelectionFromTop(to, dir > 0 ? Math.max(0, h - Look.dp(getContext(), 47)) : 0);
                focusAfterLayout(to);
                return;
            }
            // The lines between it and the end it is the last stop before come along (a heading, the end gap).
            int top = row.getTop();
            int bottom = row.getBottom();
            if (dir < 0) {
                for (int i = to - 1; i >= 0 && !shown.get(i).stop; i--) {
                    View line = childFor(i);
                    if (line == null) break;
                    top = line.getTop();
                }
            } else if (nextStop(to, 1) < 0) {
                for (int i = to + 1; i < shown.size(); i++) {
                    View line = childFor(i);
                    if (line == null) break;
                    bottom = line.getBottom();
                }
            }
            if (bottom > h) scrollListBy(Math.min(bottom - h, top));
            else if (top < 0) scrollListBy(Math.max(top, bottom - h));
            View now = childFor(to);
            View stop = now != null ? Screen.firstFocusable(now) : null;
            if (stop != null) stop.requestFocus();
            else focusAfterLayout(to);
        }
    }
}
