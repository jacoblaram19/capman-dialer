package com.capman.dialer;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads contact photos in the background and crops them to a circle.
 *
 * When there is no photo (or it fails to load) it falls back to the initial
 * badge, so the list never looks half empty.
 */
public final class PhotoLoader {

    private static final int CACHE_BYTES = 6 * 1024 * 1024;
    private static final int SIZE_SMALL = 144;
    private static final int SIZE_LARGE = 420;

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final ExecutorService io = Executors.newFixedThreadPool(3);
    private static final Handler main = new Handler(Looper.getMainLooper());

    private PhotoLoader() {
    }

    /**
     * @param photo    the photo uri, may be null
     * @param image    the view the photo is drawn into
     * @param initials the initial badge shown when there is no photo
     */
    public static void load(String photo, ImageView image, TextView initials, boolean large) {
        final String key = photo == null ? null : photo + (large ? "#L" : "#S");
        image.setTag(key);

        if (photo == null) {
            showInitials(image, initials);
            return;
        }
        Bitmap cached = cache.get(key);
        if (cached != null) {
            showPhoto(image, initials, cached);
            return;
        }
        showInitials(image, initials);

        final Context ctx = image.getContext().getApplicationContext();
        io.execute(() -> {
            final Bitmap bmp = decodeCircle(ctx.getContentResolver(), photo,
                    large ? SIZE_LARGE : SIZE_SMALL);
            if (bmp != null) cache.put(key, bmp);
            main.post(() -> {
                if (bmp != null && key.equals(image.getTag())) {
                    showPhoto(image, initials, bmp);
                }
            });
        });
    }

    private static void showPhoto(ImageView image, TextView initials, Bitmap bmp) {
        image.setImageBitmap(bmp);
        image.setVisibility(View.VISIBLE);
        if (initials != null) initials.setVisibility(View.GONE);
    }

    private static void showInitials(ImageView image, TextView initials) {
        image.setImageDrawable(null);
        image.setVisibility(View.GONE);
        if (initials != null) initials.setVisibility(View.VISIBLE);
    }

    /**
     * A circular image from in-memory JPEG bytes, for pictures that do not come
     * from the contacts provider (a WhatsApp avatar, say). Call it off the main
     * thread.
     */
    public static Bitmap circleFromBytes(byte[] jpeg, int size) {
        if (jpeg == null || jpeg.length == 0) return null;
        try {
            Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (src == null) return null;
            return circleCrop(src, size);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap decodeCircle(ContentResolver cr, String uriString, int size) {
        InputStream in = null;
        try {
            Uri uri = Uri.parse(uriString);

            // measure first, so we never decode a needlessly large bitmap
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = cr.openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();

            int sample = 1;
            int w = bounds.outWidth, h = bounds.outHeight;
            while (w / (sample * 2) >= size && h / (sample * 2) >= size) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            in = cr.openInputStream(uri);
            if (in == null) return null;
            Bitmap src = BitmapFactory.decodeStream(in, null, opts);
            if (src == null) return null;

            return circleCrop(src, size);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Crops to a square and fits it inside a circle. */
    private static Bitmap circleCrop(Bitmap src, int size) {
        int side = Math.min(src.getWidth(), src.getHeight());
        int left = (src.getWidth() - side) / 2;
        int top = (src.getHeight() - side) / 2;
        Bitmap square = Bitmap.createBitmap(src, left, top, side, side);
        if (square != src) src.recycle();

        int out = Math.min(size, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, out, out, true);
        if (scaled != square) square.recycle();

        Bitmap result = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = out / 2f;
        canvas.drawCircle(r, r, r, paint);
        scaled.recycle();
        return result;
    }
}
