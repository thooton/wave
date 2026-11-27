package com.wave.xp3.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.wave.xp3.data.WaveData;

/**
 * Add a Source: the address typed on the keypad (it starts as
 * https://github.com/ with the cursor at the end), then Add. Wave fetches the
 * source first, so the dialog that trusts it with root can say what it is:
 * its name, its maintainer, how many packages.
 *
 * Left/Right move the cursor here; Clear deletes and Back goes back (as it
 * always does, even mid-address); Down goes to Add.
 */
final class AddSourceScreen extends Screen {

    private static final String START = "https://github.com/";

    private EditText field;
    private TextView note;
    private TextView add;
    private LinearLayout working;
    private boolean fetching;
    private int attempt;
    private boolean bad;

    AddSourceScreen(WaveActivity app) {
        super(app);
    }

    @Override
    String title() {
        return "Add a Source";
    }

    @Override
    View build() {
        LinearLayout column = new LinearLayout(app);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(0xFFFFFFFF);
        TextView label = Look.text(app, "Address", 12, Look.MUTED, Look.REGULAR);
        label.setPadding(Kit.dp(app, 10), Kit.dp(app, 12), Kit.dp(app, 10), Kit.dp(app, 4));
        column.addView(label);

        field = new EditText(app);
        field.setTextSize(13.5f);
        field.setTextColor(Look.TEXT);
        field.setIncludeFontPadding(false);
        field.setLineSpacing(Kit.dp(app, 1), 1f);
        field.setMinHeight(Kit.dp(app, 30));
        field.setPadding(Kit.dp(app, 8), Kit.dp(app, 6), Kit.dp(app, 8), Kit.dp(app, 6));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setHorizontallyScrolling(false);
        field.setMaxLines(4);
        field.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setText(START);
        field.setSelection(START.length());
        field.setContentDescription("Address");
        field.setOnFocusChangeListener((v, has) -> paintField());
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (bad) {
                    bad = false;
                    setNote(null, false);
                }
            }
        });
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.leftMargin = fl.rightMargin = Kit.dp(app, 10);
        column.addView(field, fl);

        note = Look.text(app, "", 12, Look.MUTED, Look.REGULAR);
        note.setLineSpacing(Kit.dp(app, 1), 1f);
        note.setPadding(Kit.dp(app, 10), Kit.dp(app, 5), Kit.dp(app, 10), 0);
        column.addView(note);
        setNote(null, false);

        add = Kit.button(app, "Add", false);
        add.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Kit.dp(app, 34));
        al.leftMargin = al.rightMargin = Kit.dp(app, 10);
        al.topMargin = Kit.dp(app, 16);
        column.addView(add, al);

        working = new LinearLayout(app);
        working.setGravity(android.view.Gravity.CENTER);
        Ring spin = Ring.spinner(app);
        working.addView(spin, new LinearLayout.LayoutParams(Kit.dp(app, 14), Kit.dp(app, 14)));
        TextView words = Look.text(app, "Fetching", 13, Look.MUTED, Look.REGULAR);
        LinearLayout.LayoutParams wl = Kit.wrap();
        wl.leftMargin = Kit.dp(app, 7);
        working.addView(words, wl);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Kit.dp(app, 34));
        kl.topMargin = Kit.dp(app, 16);
        column.addView(working, kl);
        working.setVisibility(View.GONE);
        paintField();
        return column;
    }

    private void setNote(String error, boolean red) {
        note.setText(error != null ? error : "");
        note.setTextColor(red ? Look.RED : Look.MUTED);
        paintField();
    }

    private void paintField() {
        boolean focused = field.hasFocus();
        GradientDrawable bg = Look.rounded(focused || bad ? 0xFFFFFFFF : Look.FIELD, Kit.dp(app, 8));
        if (focused || bad) bg.setStroke(Math.max(1, Kit.dp(app, 1.5f)), bad ? Look.RED : Look.BLUE);
        field.setBackground(bg);
    }

    @Override
    View firstFocus() {
        return field;
    }

    @Override
    boolean ownsLeftRight(View focus) {
        return true;
    }

    private void submit() {
        if (fetching) return;
        final String url = field.getText().toString().trim();
        if (!url.startsWith("https://")) {
            bad = true;
            setNote("Use an https:// address.", true);
            field.requestFocus();
            return;
        }
        fetching = true;
        final int mine = ++attempt;
        add.setVisibility(View.GONE);
        working.setVisibility(View.VISIBLE);
        note.setText(" ");
        field.setFocusable(false);
        working.setFocusable(true);
        working.requestFocus();
        app.data.preview(url, new WaveData.Result<WaveData.Preview>() {
            @Override
            public void ok(WaveData.Preview preview) {
                if (mine != attempt) return;
                done();
                trust(preview);
            }

            @Override
            public void failed(String reason) {
                if (mine != attempt) return;
                done();
                bad = true;
                setNote(reason, true);
                field.requestFocus();
                field.setSelection(field.length());
            }
        });
    }

    /** Back from fetching: the fetch's answer is ignored. */
    private void done() {
        fetching = false;
        working.setFocusable(false);
        working.setVisibility(View.GONE);
        add.setVisibility(View.VISIBLE);
        field.setFocusable(true);
        field.setFocusableInTouchMode(true);
        setNote(null, false);
        add.requestFocus();
    }

    /** The question: this source's packages run with root; add it? */
    private void trust(final WaveData.Preview preview) {
        String who = preview.maintainer != null ? preview.maintainer + ", " : "";
        View head = app.dialogHead(new AppIcons.Ref("preview:" + preview.redactedUrl, preview.name, null, preview.icon(), null),
            preview.name, who + preview.packageCount + (preview.packageCount == 1 ? " package" : " packages")
                + (preview.unreadable > 0 ? " (" + preview.unreadable + " can't be read)" : ""));
        AlertDialog dialog = new AlertDialog.Builder(app)
            .setCustomTitle(head)
            // The name and maintainer are the source's own words; the address is the one thing it can't choose.
            .setMessage(preview.redactedUrl == null ? null : preview.redactedUrl.replaceFirst("^https://", ""))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add Source", (d, w) -> app.data.add(preview, new WaveData.Result<Void>() {
                @Override
                public void ok(Void value) {
                    app.toast("Added " + preview.name);
                    if (app.isShowing(AddSourceScreen.this)) app.pop();
                }

                @Override
                public void failed(String reason) {
                    bad = true;
                    setNote(reason, true);
                }
            }))
            .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    @Override
    boolean onKey(KeyEvent event) {
        int key = event.getKeyCode();
        View focus = app.getCurrentFocus();
        if (focus == field && key == KeyEvent.KEYCODE_DPAD_DOWN) {
            // Down leaves the field for Add, whatever line the cursor is on.
            if (event.getAction() == KeyEvent.ACTION_DOWN) add.requestFocus();
            return true;
        }
        if (focus == field && key == KeyEvent.KEYCODE_DPAD_UP) return true;
        if (focus == field && (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DPAD_CENTER)) {
            if (event.getAction() == KeyEvent.ACTION_UP) submit();
            return true;
        }
        if (focus == add && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) return true;
        return false;
    }

    @Override
    boolean onBack() {
        if (fetching) {
            attempt++;
            done();
            field.requestFocus();
            return true;
        }
        return false;
    }

    @Override
    void menu(Menu menu) {
        menu.add("Paste", () -> {
            ClipboardManager clip = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clip == null || !clip.hasPrimaryClip()) return;
            ClipData data = clip.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) return;
            CharSequence text = data.getItemAt(0).coerceToText(app);
            field.setText(text.toString().trim());
            field.setSelection(field.length());
            field.requestFocus();
        });
        menu.add("Clear", () -> {
            field.setText(START);
            field.setSelection(START.length());
            field.requestFocus();
        });
    }
}
