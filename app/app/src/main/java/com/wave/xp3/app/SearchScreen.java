package com.wave.xp3.app;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.wave.xp3.data.WaveData;
import com.wave.xp3.data.WaveData.Pkg;

import java.util.List;

/**
 * Search: the field takes the bar's place, as in Cydia, and has the keys as
 * soon as the tab opens. Results follow each key, across every source. The
 * cursor stays at the end, so Left/Right still switch tabs; Back deletes;
 * Down goes into the results and Up on the first comes back.
 */
final class SearchScreen extends Screen {

    private EditText field;
    private LinearLayout fieldBox;
    private List<Pkg> results;
    private String asked = "";
    private int generation;

    SearchScreen(WaveActivity app) {
        super(app);
    }

    @Override
    String title() {
        return "Search";
    }

    @Override
    View build() {
        fieldBox = new LinearLayout(app);
        fieldBox.setGravity(Gravity.CENTER_VERTICAL);
        fieldBox.setPadding(Kit.dp(app, 8), 0, Kit.dp(app, 8), 0);
        Glyph lens = new Glyph(app, Glyph.INFO, Look.MUTED);
        fieldBox.addView(new Lens(app));
        field = new EditText(app);
        field.setBackground(null);
        field.setPadding(Kit.dp(app, 5), 0, 0, 0);
        field.setTextSize(13.5f);
        field.setTextColor(Look.TEXT);
        field.setHintTextColor(Look.MUTED);
        field.setHint("Names and descriptions");
        field.setSingleLine(true);
        field.setIncludeFontPadding(false);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setContentDescription("Search");
        fieldBox.addView(field, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        field.setOnFocusChangeListener((v, has) -> paintField(has));
        paintField(false);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (field.getSelectionEnd() != s.length()) field.setSelection(s.length());
                ask(s.toString().trim());
            }
        });
        lens.setVisibility(View.GONE);

        feed = new Feed(app);
        return feed.root;
    }

    /** Puts `query` in the field, as if typed. */
    void type(String query) {
        field.setText(query);
        field.setSelection(field.length());
        field.requestFocus();
    }

    private void paintField(boolean focused) {
        android.graphics.drawable.GradientDrawable bg = Look.rounded(focused ? 0xFFFFFFFF : Look.FIELD, Kit.dp(app, 8));
        if (focused) bg.setStroke(Math.max(1, Kit.dp(app, 1.5f)), Look.BLUE);
        fieldBox.setBackground(bg);
    }

    @Override
    void bar(Bar bar) {
        if (fieldBox.getParent() != null) ((android.view.ViewGroup) fieldBox.getParent()).removeView(fieldBox);
        bar.setField(fieldBox);
    }

    @Override
    View firstFocus() {
        return field;
    }

    @Override
    void shown() {
        field.post(() -> {
            if (app.isShowing(this) && !feed.root.hasFocus()) field.requestFocus();
        });
    }

    private void ask(final String query) {
        if (query.equals(asked)) return;
        asked = query;
        final int mine = ++generation;
        if (query.isEmpty()) {
            results = null;
            draw();
            return;
        }
        app.data.search(query, new WaveData.Result<List<Pkg>>() {
            @Override
            public void ok(List<Pkg> found) {
                if (mine != generation) return;
                results = found;
                draw();
            }

            @Override
            public void failed(String reason) {
                if (mine != generation) return;
                results = null;
                draw();
            }
        });
    }

    @Override
    void update(WaveData.Snapshot s) {
        // Installed states change under the results: ask again.
        if (!asked.isEmpty()) {
            String q = asked;
            asked = "";
            ask(q);
        }
    }

    private void draw() {
        Mark mark = feed.clear();
        Pages.Results what = Pages.results(results);
        if (what == Pages.Results.NONE_FOUND) {
            feed.message(Kit.message(app, null, "No Results", null, null, null));
            return;
        }
        if (what == Pages.Results.FOUND) {
            for (int i = 0; i < results.size(); i++) {
                final Pkg p = results.get(i);
                feed.add(Rows.browseLine(app, p, v -> app.details(p, null), i == results.size() - 1));
            }
        }
        feed.restore(mark, null);
    }

    @Override
    boolean onKey(KeyEvent event) {
        int key = event.getKeyCode();
        View focus = app.getCurrentFocus();
        if (focus == field && (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
            // Down (or OK) goes into the results: on key-down for Down, on release for OK.
            boolean act = key == KeyEvent.KEYCODE_DPAD_DOWN ? event.getAction() == KeyEvent.ACTION_DOWN : event.getAction() == KeyEvent.ACTION_UP;
            if (act) feed.focusFirst();
            return true;
        }
        if (focus != field && key == KeyEvent.KEYCODE_DPAD_UP && focus != null && feed.atTop(focus)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) field.requestFocus();
            return true;
        }
        return false;
    }

    @Override
    boolean onBack() {
        View focus = app.getCurrentFocus();
        if (focus != field) {
            field.requestFocus();
            return true;
        }
        Editable text = field.getText();
        if (text.length() == 0) return false;
        text.delete(text.length() - 1, text.length());
        return true;
    }

    @Override
    void menu(Menu menu) {
        View focus = app.getCurrentFocus();
        if (focus instanceof Kit.Row && ((Kit.Row) focus).about instanceof Pkg) {
            app.packageMenu(menu, (Pkg) ((Kit.Row) focus).about, true, null);
        }
        if (field.length() > 0) menu.add("Clear", () -> {
            field.setText("");
            field.requestFocus();
        });
    }

    /** The magnifier in the field. */
    static final class Lens extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Lens(android.content.Context c) {
            super(c);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setColor(Look.MUTED);
            setLayoutParams(new LinearLayout.LayoutParams(Kit.dp(c, 13), Kit.dp(c, 13)));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float d = getWidth() / 14f;
            paint.setStrokeWidth(1.7f * d);
            canvas.drawCircle(6 * d, 6 * d, 4.6f * d, paint);
            paint.setStrokeWidth(1.9f * d);
            canvas.drawLine(9.4f * d, 9.4f * d, 12.8f * d, 12.8f * d, paint);
        }
    }
}
