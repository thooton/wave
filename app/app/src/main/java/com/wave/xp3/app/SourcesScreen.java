package com.wave.xp3.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Src;

/**
 * The Sources tab: All Sources, then each source with its icon, name and
 * address; Refresh and Add on the bar (Up from the top row reaches them).
 * A refreshing source says so; one that won't refresh says why in red. With
 * nothing fetched and no network, the Updater's message screen instead.
 */
final class SourcesScreen extends Screen {


    SourcesScreen(WaveActivity app) {
        super(app);
    }

    @Override
    String title() {
        return "Sources";
    }

    @Override
    View build() {
        feed = new Feed(app);
        return feed.root;
    }

    private boolean refreshing() {
        return Pages.anyRefreshing(app.snapshot);
    }

    @Override
    void bar(Bar bar) {
        // With nothing fetched and a failure, the page's Try Again is the refresh.
        Pages.BarState state = Pages.sourcesBar(app.snapshot);
        if (state == Pages.BarState.SPINNING) bar.setSpinning(true);
        else if (state == Pages.BarState.REFRESH) bar.setLink(0, "Refresh", true).setOnClickListener(v -> app.data.refreshAll());
        bar.setLink(1, "Add", true).setOnClickListener(v -> app.push(new AddSourceScreen(app)));
    }

    static boolean nothingFetched(WaveData.Snapshot s) {
        return Pages.nothingFetched(s);
    }

    @Override
    void update(WaveData.Snapshot s) {
        boolean messageHadFocus = feed.showingMessage() && feed.root.hasFocus();
        Pages.Message failed = Pages.sourcesEmpty(s);
        if (failed != null) {
            View message = Kit.message(app, null, failed.title, failed.body, null, failed.button);
            Kit.messageButton(message).setOnClickListener(v -> app.data.refreshAll());
            feed.message(message);
            if (app.isShowing(this)) Kit.messageButton(message).requestFocus();
            return;
        }
        Mark mark = feed.clear();
        FolderIcon all = new FolderIcon(app);
        Kit.Row allRow = Rows.link(app, "All Sources", "Every package below", Look.MUTED, all, 30, null, true);
        allRow.setOnClickListener(v -> app.push(new SourceScreen(app, null)));
        feed.add(keyed(allRow, "all"));
        for (int i = 0; i < s.sources.size(); i++) {
            final Src src = s.sources.get(i);
            ImageView icon = new ImageView(app);
            app.icons.into(icon, new AppIcons.Ref("source:" + src.id, src.name, src.iconFile, null, null), 30);
            Pages.Line line = Pages.sourceRow(src);
            Kit.Row row = Rows.link(app, src.name, line.text, Look.color(line.tone), icon, 30, null, true);
            if (src.refreshing || src.error != null) {
                // The spinner or the warning sits before the chevron.
                View sign = src.refreshing ? Ring.spinner(app) : new Glyph(app, Glyph.WARN, Look.ORANGE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Kit.dp(app, 14), Kit.dp(app, 14));
                lp.rightMargin = Kit.dp(app, 6);
                row.addView(sign, row.getChildCount() - 1, lp);
            }
            row.about = src;
            if (i == s.sources.size() - 1) row.last();
            row.setOnClickListener(v -> app.push(new SourceScreen(app, src.id)));
            feed.add(keyed(row, "src:" + src.id));
        }
        feed.restore(mark, null);
        if (messageHadFocus && app.isShowing(this)) feed.focus(null, true);
    }

    @Override
    void menu(Menu menu) {
        View focus = app.getCurrentFocus();
        if (focus instanceof Kit.Row && ((Kit.Row) focus).about instanceof Src) {
            final Src src = (Src) ((Kit.Row) focus).about;
            sourceMenu(app, menu, src);
        }
        if (!refreshing()) menu.add("Refresh Sources", () -> app.data.refreshAll());
        menu.add("Add a Source", () -> app.push(new AddSourceScreen(app)));
    }

    /** Refresh, Copy Address and (not for Wave's own) Remove Source. */
    static void sourceMenu(final WaveActivity app, Menu menu, final Src src) {
        if (!src.refreshing) menu.add("Refresh", () -> app.data.refresh(src.id));
        menu.add("Copy Address", () -> {
            ClipboardManager clip = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clip != null) clip.setPrimaryClip(ClipData.newPlainText(src.name, src.url));
            app.toast("Copied");
        });
        if (!src.isDefault) {
            menu.add("!Remove Source", () -> new android.app.AlertDialog.Builder(app)
                .setTitle("Remove " + src.name + "?")
                .setMessage("Its packages stay installed, but won't get updates from it.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(WaveActivity.red("Remove"), (d, w) -> app.data.remove(src.id, new WaveData.Result<Void>() {
                    @Override
                    public void ok(Void value) {
                        app.toast("Removed " + src.name);
                    }

                    @Override
                    public void failed(String reason) {
                        app.alert("Can't Remove " + src.name, reason);
                    }
                }))
                .show()
                .getButton(android.app.AlertDialog.BUTTON_NEGATIVE).requestFocus());
        }
    }

    /** The grey folder tile of All Sources and the sections. */
    static final class FolderIcon extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        FolderIcon(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float s = getWidth() / 30f;
            paint.setColor(Look.MUTED);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), getWidth() * 0.225f, getWidth() * 0.225f, paint);
            paint.setColor(0xFFFFFFFF);
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(6 * s, 10.5f * s);
            p.lineTo(6 * s, 20.5f * s);
            p.quadTo(6 * s, 22 * s, 7.5f * s, 22 * s);
            p.lineTo(22.5f * s, 22 * s);
            p.quadTo(24 * s, 22 * s, 24 * s, 20.5f * s);
            p.lineTo(24 * s, 12.5f * s);
            p.quadTo(24 * s, 11 * s, 22.5f * s, 11 * s);
            p.lineTo(14.5f * s, 11 * s);
            p.lineTo(12.5f * s, 9 * s);
            p.lineTo(7.5f * s, 9 * s);
            p.quadTo(6 * s, 9 * s, 6 * s, 10.5f * s);
            p.close();
            canvas.drawPath(p, paint);
        }
    }
}
