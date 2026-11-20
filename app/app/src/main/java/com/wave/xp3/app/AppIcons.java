package com.wave.xp3.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Icons as the App Store draws them (the Updater's Icons): a rounded square,
 * corners at 22.5% of the side, a faint hairline round it. A package's icon
 * comes from its source's icon.png; failing that, an installed app's own
 * launcher icon; failing that, its initial on one of iOS's system colours,
 * picked by its id so it is the same every time.
 *
 * Decoding happens off the main thread; an ImageView shows the tile until the
 * real icon arrives, and a recycled row never gets another row's icon.
 *
 * A long list binds a row per d-pad press, so binding does no I/O and draws
 * little: a lettered tile depends only on its colour and letter, so packages
 * share one (drawn once, uploaded to the GPU once), and a placeholder has the
 * real icon's size, so the icon arriving redraws the row instead of laying
 * out the whole list again.
 */
final class AppIcons {

    /** Where an icon comes from. Any of file, bytes and installedPackage may be null. */
    static final class Ref {
        final String id;
        final String label;
        final File file;
        final byte[] bytes;
        final String installedPackage;

        Ref(String id, String label, File file, byte[] bytes, String installedPackage) {
            this.id = id;
            this.label = label == null ? "" : label;
            this.file = file;
            this.bytes = bytes;
            this.installedPackage = installedPackage;
        }

        static Ref tile(String id, String label) {
            return new Ref(id, label, null, null, null);
        }

        /**
         * What the cache knows the art by. A file's path is enough: icons live
         * in a source's tree under its commit, which is never rewritten, so
         * there is no need to stat the file on the main thread.
         */
        String key() {
            return id + "|" + (file != null ? file.getPath() : "")
                + (bytes != null ? "#" + bytes.length : "") + (installedPackage != null ? "!" + installedPackage : "");
        }
    }

    private static final int[] TILES = {
        0xFF007AFF, 0xFF34C759, 0xFFFF9500, 0xFFFF2D55,
        0xFF5856D6, 0xFF5AC8FA, 0xFFAF52DE, 0xFFFF3B30,
    };

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "icons");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /*
     * Icons are kept as drawables, and a view gets a new one on their shared
     * state (as Resources does): the bitmap and its paint aren't set up again
     * for every row bound. Nothing tints or fades an icon's drawable (rows
     * fade the view), so the shared state never changes.
     */
    private final LruCache<String, BitmapDrawable> cache = new LruCache<String, BitmapDrawable>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, BitmapDrawable value) {
            return value.getBitmap().getByteCount();
        }
    };

    /** Lettered tiles by colour, letter and size: few, shared by every package that has them. */
    private final LruCache<String, BitmapDrawable> tiles = new LruCache<String, BitmapDrawable>(2 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, BitmapDrawable value) {
            return value.getBitmap().getByteCount();
        }
    };

    AppIcons(Context context) {
        this.context = context.getApplicationContext();
    }

    void close() {
        worker.shutdownNow();
    }

    /** Puts the icon `ref` names into `view`, `sizeDp` square. */
    void into(final ImageView view, final Ref ref, final int sizeDp) {
        final int px = Look.dp(context, sizeDp) * 2;
        final String key = ref.key() + "@" + px;
        view.setTag(key);
        BitmapDrawable hit = cache.get(key);
        if (hit != null) {
            show(view, hit);
            return;
        }
        final String tileKey = tileKey(ref, px);
        BitmapDrawable tile = tiles.get(tileKey);
        boolean hasArt = ref.file != null || ref.bytes != null || ref.installedPackage != null;
        if (tile != null) {
            show(view, tile);
        } else if (!hasArt) {
            // The lettered tile is this icon: drawn now, so it never pops in.
            tile = new BitmapDrawable(context.getResources(), round(tile(ref, px), px));
            tiles.put(tileKey, tile);
            show(view, tile);
        } else {
            // The art is on its way: a plain tile of the same colour holds the
            // place, and the drawing (lettered tile if the art fails) is the
            // worker's, off the main thread. It has the icon's size, so the
            // swap doesn't lay out the list again.
            android.graphics.drawable.GradientDrawable hold = Look.rounded(tileColor(ref.id), px / 2 * 0.225f);
            hold.setSize(px, px);
            view.setImageDrawable(hold);
        }
        if (!hasArt) return;
        // The worker and its queue hold the view weakly: a stack frame of the
        // worker kept the last view it drew for, and with it a closed
        // Activity and its whole tree (tools/qa/soak.py's heap dumps).
        final WeakReference<ImageView> target = new WeakReference<>(view);
        worker.execute(() -> {
            Bitmap art = load(ref, px);
            final boolean real = art != null;
            final BitmapDrawable done = new BitmapDrawable(context.getResources(), real ? round(art, px) : round(tile(ref, px), px));
            main.post(() -> {
                if (real) cache.put(key, done);
                else tiles.put(tileKey, done);
                ImageView v = target.get();
                if (v != null && key.equals(v.getTag())) show(v, done);
            });
        });
    }

    /** Shows `icon` in `view`, keeping the drawable it has when that already shows it (a rebind). */
    private void show(ImageView view, BitmapDrawable icon) {
        Drawable now = view.getDrawable();
        if (now instanceof BitmapDrawable && ((BitmapDrawable) now).getBitmap() == icon.getBitmap()) return;
        view.setImageDrawable(icon.getConstantState().newDrawable(context.getResources()));
    }

    /** What a lettered tile depends on: its colour, its letter and its size (see {@link #tile}). */
    private static String tileKey(Ref ref, int px) {
        return tileColor(ref.id) + "|" + letter(ref) + "@" + px;
    }

    private static String letter(Ref ref) {
        return ref.label.isEmpty() ? "?" : ref.label.substring(0, 1).toUpperCase();
    }

    /** The icon's art, or null when none of its places has one. On the worker. */
    private Bitmap load(Ref ref, int px) {
        try {
            if (ref.bytes != null) {
                Bitmap b = BitmapFactory.decodeByteArray(ref.bytes, 0, ref.bytes.length);
                if (b != null) return b;
            }
            if (ref.file != null && ref.file.isFile()) {
                Bitmap b = BitmapFactory.decodeFile(ref.file.getPath());
                if (b != null) return b;
            }
            if (ref.installedPackage != null) {
                Drawable d = context.getPackageManager().getApplicationIcon(ref.installedPackage);
                return crop(render(d, px));
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
        return null;
    }

    private static Bitmap render(Drawable drawable, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        drawable.setBounds(0, 0, px, px);
        drawable.draw(canvas);
        return out;
    }

    /** The part of `art` that isn't see-through (launcher icons leave a margin round a square tile). */
    private static Bitmap crop(Bitmap art) {
        int w = art.getWidth();
        int h = art.getHeight();
        int[] pixels = new int[w * h];
        art.getPixels(pixels, 0, w, 0, 0, w, h);
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((pixels[y * w + x] >>> 24) < 32) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        if (right < 0 || (left == 0 && top == 0 && right == w - 1 && bottom == h - 1)) return art;
        return Bitmap.createBitmap(art, left, top, right - left + 1, bottom - top + 1);
    }

    static int tileColor(String id) {
        return TILES[(id.hashCode() & 0x7FFFFFFF) % TILES.length];
    }

    private static Bitmap tile(Ref ref, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(tileColor(ref.id));
        String text = letter(ref);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setTypeface(Look.BOLD);
        paint.setTextSize(px * 0.5f);
        paint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics m = paint.getFontMetrics();
        canvas.drawText(text, px / 2f, px / 2f - (m.ascent + m.descent) / 2f, paint);
        return out;
    }

    /** `art` scaled into the rounded square, with the hairline on top. */
    private static Bitmap round(Bitmap art, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        BitmapShader shader = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix scale = new Matrix();
        scale.setScale(px / (float) art.getWidth(), px / (float) art.getHeight());
        shader.setLocalMatrix(scale);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        fill.setShader(shader);
        float corner = px * 0.225f;
        RectF bounds = new RectF(0, 0, px, px);
        canvas.drawRoundRect(bounds, corner, corner, fill);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(px / 40f);
        line.setColor(0x1F000000);
        float half = line.getStrokeWidth() / 2f;
        bounds.inset(half, half);
        canvas.drawRoundRect(bounds, corner - half, corner - half, line);
        return out;
    }
}
