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

        String key() {
            return id + "|" + (file != null ? file.getPath() + "@" + file.lastModified() : "")
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
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
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
        Bitmap hit = cache.get(key);
        if (hit != null) {
            view.setImageDrawable(new BitmapDrawable(context.getResources(), hit));
            return;
        }
        String tileKey = "tile|" + ref.id + "|" + ref.label + "@" + px;
        Bitmap tile = cache.get(tileKey);
        if (tile == null) {
            tile = round(tile(ref, px), px);
            cache.put(tileKey, tile);
        }
        view.setImageDrawable(new BitmapDrawable(context.getResources(), tile));
        if (ref.file == null && ref.bytes == null && ref.installedPackage == null) return;
        worker.execute(() -> {
            Bitmap art = load(ref, px);
            if (art == null) return;
            final Bitmap done = round(art, px);
            main.post(() -> {
                cache.put(key, done);
                if (key.equals(view.getTag())) view.setImageDrawable(new BitmapDrawable(context.getResources(), done));
            });
        });
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
        String text = ref.label.isEmpty() ? "?" : ref.label.substring(0, 1).toUpperCase();
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
