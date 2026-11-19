package com.wave.xp3.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Pkg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wave: four tabs (Wave, Sources, Installed, Search) under a bar, as Cydia
 * arranges them, in the Updater's look. Each tab keeps a stack of screens;
 * the queue's screens and the no-root screen sit above the tabs.
 *
 * Keys, everywhere unless a screen says otherwise (site/mockups/CHECKLIST.md):
 * Left/Right switch tabs, Up/Down move between focus stops, OK presses the
 * focused one, Back goes back a screen (on a tab's first screen it closes
 * Wave, or sends it to the back while the queue works), Menu opens the menu
 * for where the focus is.
 */
public final class WaveActivity extends Activity {

    WaveData data;
    AppIcons icons;
    WaveData.Snapshot snapshot;

    private Bar bar;
    private FrameLayout content;
    private TabBar tabBar;
    private final List<List<Screen>> stacks = new ArrayList<>();
    /** Screens over the tabs: the queue's, the no-root screen. */
    private final List<Screen> overlay = new ArrayList<>();
    private int tab;
    private Screen showing;
    /** The data's listener: news is applied at most once a frame, and not before the first. */
    private final Runnable onChanged = this::changeLater;
    private final Runnable applyChange = () -> {
        changePending = false;
        changed();
    };
    private boolean changePending;
    private boolean firstFrameDrawn;
    private boolean resumedOnce;
    private String lastActionError;
    private final List<String> turnedOffShown = new ArrayList<>();
    private boolean dialogOpen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        icons = new AppIcons(this);
        data = Data.open(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        bar = new Bar(this);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        tabBar = new TabBar(this);
        root.addView(tabBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        registerForContextMenu(content);
        // The first frame goes up with what is at hand (the bar, the tabs,
        // Home saying "Loading…"); the sources the worker reads meanwhile
        // follow in the next one, so a slow read never holds up the first.
        root.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener() {
            @Override
            public void onDraw() {
                if (firstFrameDrawn) return;
                firstFrameDrawn = true;
                final android.view.ViewTreeObserver.OnDrawListener self = this;
                root.post(() -> {
                    root.getViewTreeObserver().removeOnDrawListener(self);
                    if (changePending) content.postOnAnimation(applyChange);
                });
            }
        });

        stacks.add(new ArrayList<>(Collections.<Screen>singletonList(new HomeScreen(this))));
        stacks.add(new ArrayList<>(Collections.<Screen>singletonList(new SourcesScreen(this))));
        stacks.add(new ArrayList<>(Collections.<Screen>singletonList(new InstalledScreen(this))));
        stacks.add(new ArrayList<>(Collections.<Screen>singletonList(new SearchScreen(this))));

        snapshot = data.snapshot();
        data.listen(onChanged);
        show();
        // A debug fixture may start on Search with a query typed (tools/qa can only press d-pad keys).
        String query = getIntent().getStringExtra("query");
        if (query != null && data.getClass().getSimpleName().equals("FakeWaveData")) {
            openTab(3);
            ((SearchScreen) top()).type(query);
        }
        turnedOff();
        com.wave.xp3.AfterBoot.selfUpdate(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Opening the data just started reading the phone; coming back to
        // Wave later reads it again (an app installed or removed meanwhile).
        if (resumedOnce) data.recheckPhone();
        resumedOnce = true;
    }

    @Override
    protected void onDestroy() {
        content.removeCallbacks(applyChange);
        data.unlisten(onChanged);
        data.close();
        icons.close();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Screens
    // ------------------------------------------------------------------

    private Screen top() {
        if (!overlay.isEmpty()) return overlay.get(overlay.size() - 1);
        List<Screen> stack = stacks.get(tab);
        return stack.get(stack.size() - 1);
    }

    /** Puts the top screen up: its bar, its page, its focus. */
    private void show() {
        final long started = android.os.SystemClock.uptimeMillis();
        Screen next = top();
        if (showing != null && showing != next) {
            showing.focusKey = Screen.keyOf(getCurrentFocus());
            if (showing.feed != null) showing.feed.remember();
            showing.hidden();
        }
        boolean fresh = next.view == null;
        if (fresh) next.view = next.build();
        content.removeAllViews();
        content.addView(next.view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        showing = next;
        dressBar(next);
        tabBar.setVisibility(next.modal() || next.bare() ? View.GONE : View.VISIBLE);
        tabBar.setOpen(tab);
        tabBar.setBadge(snapshot.updates.size());
        next.update(snapshot);
        next.shown();
        if (!next.placeFocus()) content.requestFocus();
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) timeShow(next, started);
    }

    /** Debug builds: how long a screen took to build and to reach the screen (logcat tag WaveTime). */
    private void timeShow(Screen screen, long started) {
        final long built = android.os.SystemClock.uptimeMillis() - started;
        final String name = screen.getClass().getSimpleName();
        final android.view.ViewTreeObserver tree = content.getViewTreeObserver();
        tree.addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener() {
            boolean done;

            @Override
            public void onDraw() {
                if (done) return;
                done = true;
                final android.view.ViewTreeObserver.OnDrawListener self = this;
                content.post(() -> content.getViewTreeObserver().removeOnDrawListener(self));
                android.util.Log.i("WaveTime", name + " built " + built + " ms, drawn " + (android.os.SystemClock.uptimeMillis() - started) + " ms");
            }
        });
    }

    private void dressBar(Screen screen) {
        // Redressing takes the links and the search field off the bar and puts
        // them back; whichever had the focus gets it back, so news arriving
        // while the user types or sits on Add doesn't move them.
        View focus = getCurrentFocus();
        boolean inBar = focus != null && isInside(focus, bar);
        bar.clear();
        bar.setVisibility(screen.bare() ? View.GONE : View.VISIBLE);
        bar.setTitle(screen.title());
        bar.setBack(screen.backLabel);
        screen.bar(bar);
        if (inBar && focus.isAttachedToWindow() && focus.isShown() && focus.isFocusable() && isInside(focus, bar)) {
            focus.requestFocus();
        }
    }

    /** Redraws the bar of the screen showing (its links changed). */
    void redressBar() {
        if (showing != null) dressBar(showing);
    }

    private static View findKey(View root, String key) {
        if (key.equals(root.getTag(Screen.R_KEY))) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findKey(g.getChildAt(i), key);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Opens `screen` on top of the current tab, with Back named after the screen below. */
    void push(Screen screen) {
        Screen below = top();
        screen.backLabel = screen.modal() || screen.bare() ? null : below.title();
        if (!overlay.isEmpty() || screen.modal() || screen.bare()) overlay.add(screen);
        else stacks.get(tab).add(screen);
        show();
    }

    /** Opens `screen` on the Sources tab (Add a Source from Home). */
    void pushOnTab(int which, Screen screen) {
        openTab(which);
        push(screen);
    }

    /** Closes the top screen. */
    void pop() {
        if (!overlay.isEmpty()) {
            overlay.remove(overlay.size() - 1).view = null;
        } else {
            List<Screen> stack = stacks.get(tab);
            if (stack.size() > 1) stack.remove(stack.size() - 1);
        }
        show();
    }

    /** Swaps the top overlay screen for `screen` (Confirm becomes Installing). */
    void replaceOverlay(Screen screen) {
        if (!overlay.isEmpty()) overlay.remove(overlay.size() - 1);
        overlay.add(screen);
        show();
    }

    void openTab(int which) {
        if (!overlay.isEmpty()) return;
        if (which == tab && showing == top()) return;
        if (showing != null) {
            showing.focusKey = Screen.keyOf(getCurrentFocus());
            if (showing.feed != null) showing.feed.remember();
        }
        tab = which;
        show();
    }

    boolean isShowing(Screen screen) {
        return showing == screen;
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private void changeLater() {
        if (changePending) return;
        changePending = true;
        if (firstFrameDrawn) content.postOnAnimation(applyChange);
    }

    private void changed() {
        snapshot = data.snapshot();
        tabBar.setBadge(snapshot.updates.size());

        // A queue already working (Wave reopened while it runs) gets its screen back.
        if (snapshot.queue != null && !snapshot.queue.finished && find(QueueScreen.class) == null && find(ConfirmScreen.class) == null) {
            overlay.add(new QueueScreen(this));
            show();
            return;
        }
        if (showing != null) {
            dressBar(showing);
            showing.update(snapshot);
        }
        if (snapshot.actionError != null && !snapshot.actionError.equals(lastActionError)) {
            toast(snapshot.actionError);
        }
        lastActionError = snapshot.actionError;
        turnedOff();
    }

    private Screen find(Class<? extends Screen> kind) {
        for (Screen s : overlay) if (kind.isInstance(s)) return s;
        return null;
    }

    /** The watchdog's news, once per mod: it didn't start, so Wave turned it off. */
    private void turnedOff() {
        if (dialogOpen || snapshot.root != WaveData.Root.OK) return;
        for (final WaveData.TurnedOff off : snapshot.turnedOff) {
            if (turnedOffShown.contains(off.id)) continue;
            turnedOffShown.add(off.id);
            // Which version and why: the watchdog's reason says how the boot failed.
            String why = off.reason != null && !off.reason.isEmpty() ? off.reason : "The phone didn't finish starting.";
            String body = off.version != null && !off.version.isEmpty()
                ? "With version " + off.version + ": " + Character.toLowerCase(why.charAt(0)) + why.substring(1)
                : why;
            body += " Wave turned it off, so the phone starts without it.";
            WaveData.Detail known = snapshot.detail(off.id);
            AppIcons.Ref icon = known != null ? Rows.icon(this, known.pkg) : AppIcons.Ref.tile(off.id, off.name);
            AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(dialogHead(icon, off.name, "Turned off"))
                .setMessage(body)
                .setPositiveButton("OK", (d, w) -> data.ackTurnedOff(off.id))
                .setNegativeButton(red("Remove It"), (d, w) -> {
                    data.ackTurnedOff(off.id);
                    plan(Collections.singletonList(off.id), WaveData.Action.REMOVE);
                })
                .setOnCancelListener(d -> data.ackTurnedOff(off.id))
                .create();
            dialogOpen = true;
            dialog.setOnDismissListener(d -> {
                dialogOpen = false;
                turnedOff();
            });
            dialog.show();
            // OK is the safe answer; Remove It is never where the focus starts.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
            return;
        }
    }

    // ------------------------------------------------------------------
    // What the rows do
    // ------------------------------------------------------------------

    /** Opens `p`'s details; `sourceId` keeps the source it was browsed in (null: merged). */
    void details(Pkg p, String sourceId) {
        push(new DetailsScreen(this, p.id, sourceId));
    }

    /** What pressing `p`'s capsule does: GET or UPDATE go to the confirm, OPEN starts the app. */
    void press(Pkg p) {
        if (Rows.unfit(p) && p.state == WaveData.State.NONE) return;
        switch (p.state) {
            case NONE:
                plan(Collections.singletonList(p.id), WaveData.Action.GET);
                break;
            case UPDATE:
                if (Rows.unfit(p)) {
                    // A newer version this phone can't take: as if up to date.
                    if (canOpen(p)) open(p);
                    else details(p, null);
                    break;
                }
                plan(Collections.singletonList(p.id), WaveData.Action.UPDATE);
                break;
            case INSTALLED:
            case NEWER:
                if (canOpen(p)) open(p);
                else details(p, null);
                break;
            default:
                details(p, null);
        }
    }

    boolean canOpen(Pkg p) {
        return !p.isModule && !p.id.equals(getPackageName()) && getPackageManager().getLaunchIntentForPackage(p.id) != null;
    }

    void open(Pkg p) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(p.id);
        if (launch == null) {
            toast(p.name + " can't be opened.");
            return;
        }
        try {
            startActivity(launch);
        } catch (RuntimeException e) {
            toast(p.name + " can't be opened.");
        }
    }

    /** Works out what doing `action` to `ids` takes, then shows the confirm (or why it can't). */
    void plan(List<String> ids, final WaveData.Action action) {
        if (find(ConfirmScreen.class) != null) return;
        data.plan(ids, action, new WaveData.Result<WaveData.PlanView>() {
            @Override
            public void ok(WaveData.PlanView plan) {
                if (plan.refusal != null) {
                    alert(action == WaveData.Action.REMOVE ? "Can't Remove" : "Can't Install", plan.refusal);
                    return;
                }
                if (find(ConfirmScreen.class) != null || find(QueueScreen.class) != null) return;
                push(new ConfirmScreen(WaveActivity.this, plan));
            }

            @Override
            public void failed(String reason) {
                alert(action == WaveData.Action.REMOVE ? "Can't Remove" : "Can't Install", reason);
            }
        });
    }

    void askReboot() {
        new AlertDialog.Builder(this)
            .setTitle("Reboot Now?")
            .setMessage("The phone restarts, and the changes waiting for it start.")
            .setNegativeButton("Later", null)
            .setPositiveButton("Reboot", (d, w) -> data.reboot())
            .show()
            // Later, so OK on Reboot to Finish and a second OK never reboot by accident.
            .getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    /** A dialog's title as the mockups draw it: the icon, the name, a grey line. */
    View dialogHead(AppIcons.Ref icon, String name, String sub) {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(Kit.dp(this, 14), Kit.dp(this, 14), Kit.dp(this, 14), 0);
        android.widget.ImageView image = Kit.iconView(this, 32);
        icons.into(image, icon, 32);
        head.addView(image);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = Kit.line(this, name, 16, Look.TEXT);
        title.setTypeface(Look.MEDIUM);
        texts.addView(title);
        texts.addView(Kit.line(this, sub, 12, Look.MUTED));
        LinearLayout.LayoutParams tl = Kit.weight();
        tl.leftMargin = Kit.dp(this, 8);
        head.addView(texts, tl);
        return head;
    }

    void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    void alert(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }

    static CharSequence red(String text) {
        SpannableString s = new SpannableString(text);
        s.setSpan(new ForegroundColorSpan(Look.RED), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Screen screen = top();
        if (screen.onKey(event)) return true;
        int key = event.getKeyCode();
        // Up from the top row goes to the bar's link (Add, Update All), the right one first.
        if (key == KeyEvent.KEYCODE_DPAD_UP && bar.getVisibility() == View.VISIBLE) {
            View focus = getCurrentFocus();
            TextView link = bar.upTarget();
            if (link != null && focus != null && focus.getParent() != bar && screen.atTop(focus)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) link.requestFocus();
                return true;
            }
        }
        if ((key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)
            && !screen.modal() && !screen.bare() && overlay.isEmpty()) {
            View focus = getCurrentFocus();
            boolean onBarLink = focus != null && focus.getParent() == bar;
            if (!onBarLink && !screen.ownsLeftRight(focus)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    openTab(key == KeyEvent.KEYCODE_DPAD_LEFT ? (tab + 3) % 4 : (tab + 1) % 4);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /* Menu opens on its release, as the platform's own menus do (the Updater's rule). */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getRepeatCount() == 0) event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.isTracking() && !event.isCanceled()) openMenu();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        Screen screen = top();
        if (screen.onBack()) return;
        if (!overlay.isEmpty()) {
            pop();
            return;
        }
        if (stacks.get(tab).size() > 1) {
            pop();
            return;
        }
        if (snapshot.queue != null && !snapshot.queue.finished) {
            toast("Working in the background");
            moveTaskToBack(true);
            return;
        }
        finish();
    }

    // ------------------------------------------------------------------
    // The menu: the platform's context menu, opened beside the focused row
    // ------------------------------------------------------------------

    private Screen.Menu menu;

    void openMenu() {
        Screen.Menu items = new Screen.Menu();
        top().menu(items);
        if (items.isEmpty()) return;
        menu = items;
        View focus = getCurrentFocus();
        float x = Math.min(Look.dp(this, 32), content.getWidth() / 2f);
        float y = content.getHeight() / 3f;
        if (focus != null && isInside(focus, content)) {
            int[] at = new int[2];
            int[] base = new int[2];
            focus.getLocationInWindow(at);
            content.getLocationInWindow(base);
            y = Math.max(0, Math.min(at[1] - base[1] + focus.getHeight(), content.getHeight()));
            // A whole page in focus (Details): the menu goes over its top third.
            if (focus.getHeight() > content.getHeight() / 2) y = content.getHeight() / 3f;
        }
        // Under the row when it fits, else as low as it fits whole (a menu item is about 32dp).
        float tall = Look.dp(this, 32) * items.titles.size() + Look.dp(this, 16);
        y = Math.max(0, Math.min(y, content.getHeight() - tall));
        content.showContextMenu(x, y);
    }

    private static boolean isInside(View view, View parent) {
        while (view != null) {
            if (view == parent) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    @Override
    public void onCreateContextMenu(ContextMenu contextMenu, View view, ContextMenu.ContextMenuInfo info) {
        if (menu == null) return;
        for (int i = 0; i < menu.titles.size(); i++) {
            String title = menu.titles.get(i);
            CharSequence shown = title.startsWith("!") ? red(title.substring(1)) : title;
            contextMenu.add(0, i, i, shown);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        Screen.Menu items = menu;
        menu = null;
        if (items != null && item.getItemId() < items.actions.size()) items.actions.get(item.getItemId()).run();
        return true;
    }

    @Override
    public void onContextMenuClosed(android.view.Menu closed) {
        super.onContextMenuClosed(closed);
    }

    // ------------------------------------------------------------------
    // Shared bits for screens
    // ------------------------------------------------------------------

    /** Wave's own update, when a source offers one this phone can take; else null. */
    Pkg waveUpdate() {
        if (snapshot == null) return null;
        for (Pkg u : snapshot.updates) if (u.id.equals(getPackageName())) return u;
        return null;
    }

    /** The common package menu: the capsule's action, details, what's new. */
    void packageMenu(Screen.Menu menu, final Pkg p, boolean withDetails, final String sourceId) {
        String word = Rows.capsuleWord(this, p);
        if (word != null) {
            if (word.equals("OPEN")) menu.add("Open", () -> open(p));
            else if (!Rows.unfit(p)) menu.add(word.equals("GET") ? "Get" : "Update", () -> press(p));
        }
        if (p.needsNewerWave) {
            final Pkg wave = waveUpdate();
            if (wave != null) menu.add("Update Wave", () -> press(wave));
        }
        if (withDetails) menu.add("Details", () -> details(p, sourceId));
        if (p.state == WaveData.State.OFF) menu.add("Turn On", () -> data.turnOn(p.id));
        boolean installed = p.state != WaveData.State.NONE && p.state != WaveData.State.PENDING_REMOVAL;
        if (installed && p.state != WaveData.State.PENDING_INSTALL && p.state != WaveData.State.UPDATE && !Rows.unfit(p)) {
            menu.add("Reinstall", () -> plan(Collections.singletonList(p.id), WaveData.Action.REINSTALL));
        }
        if (installed && !p.id.equals(getPackageName())) {
            menu.add("!Remove", () -> plan(Collections.singletonList(p.id), WaveData.Action.REMOVE));
        }
    }

    TextView message(String text) {
        TextView t = Look.text(this, text, 13, Look.MUTED, Look.REGULAR);
        return t;
    }
}
