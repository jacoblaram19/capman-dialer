package com.capman.dialer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.util.Log;

import java.io.InputStream;

/**
 * The call screen background: a ready-made gradient or the user's own photo.
 *
 * Flat colours are deliberately absent. So that text and buttons stay readable,
 * every option is the SAME HUE darkening downwards - a little lighter at the
 * top, nearly black at the bottom. A photo follows the same logic: a scrim that
 * darkens downwards is drawn over it, otherwise white text disappears on a
 * bright picture.
 */
public final class CallBackground {

    private static final String TAG = "CallBackground";

    /** Upper bound of the blur slider. */
    public static final int MAX_BLUR = 25;

    private CallBackground() {
    }

    /** A ready-made gradient. */
    public static class Preset {
        public final String id;
        public final String label;
        /** The top (lighter) and bottom (darker) colour. */
        public final int top, bottom;

        Preset(String id, String label, int top, int bottom) {
            this.id = id;
            this.label = label;
            this.top = top;
            this.bottom = bottom;
        }
    }

    /** The dark default leads, the light one follows right after. */
    public static final Preset[] PRESETS = {
            new Preset("midnight", "Midnight blue", 0xFF12202E, 0xFF070A0E),
            new Preset("white", "White", 0xFFFFFFFF, 0xFFD3DCE8),
            new Preset("purple", "Purple", 0xFF2A1B3D, 0xFF0C0713),
            new Preset("green", "Green", 0xFF102A20, 0xFF050C09),
            new Preset("burgundy", "Burgundy", 0xFF33141C, 0xFF100507),
            new Preset("amber", "Amber", 0xFF33240F, 0xFF100A03),
            new Preset("graphite", "Graphite", 0xFF262A2E, 0xFF0A0B0C),
    };

    /**
     * White when the app is in the light theme, midnight blue otherwise.
     *
     * The call screen's text follows the theme, so the background has to be on
     * the same side: pick a dark background in the light theme and dark text
     * disappears on a dark surface.
     */
    public static Preset defaultPreset(Context ctx) {
        return preset(isLightTheme(ctx) ? "white" : "midnight");
    }

    /** Is the app currently light? Follows the system when set to do so. */
    public static boolean isLightTheme(Context ctx) {
        String t = Prefs.theme(ctx);
        if (Prefs.THEME_LIGHT.equals(t)) return true;
        if (Prefs.THEME_DARK.equals(t)) return false;
        int mode = ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode != android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public static Preset preset(String id) {
        for (Preset p : PRESETS) {
            if (p.id.equals(id)) return p;
        }
        return PRESETS[0];
    }

    // ------------------------------------------------------------------ building

    /** A background from the chosen gradient. */
    public static Drawable gradient(Preset p) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{p.top, p.bottom});
        d.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return d;
    }

    /**
     * Builds a background from whatever is selected in settings.
     *
     * @param w,h target size the photo is cropped to; 0 falls back to the
     *            screen size.
     * @return never null - an unreadable photo falls back to the gradient
     */
    public static Drawable current(Context ctx, int w, int h) {
        if (Prefs.callBgImage(ctx) != null) {
            Drawable d = image(ctx, Prefs.callBgImage(ctx), Prefs.callBgBlur(ctx), w, h);
            if (d != null) return d;
        }
        return gradient(preset(Prefs.callBgPreset(ctx)));
    }

    /**
     * A background from a photo: crop it, blur it, and draw the downward
     * darkening scrim over it.
     *
     * @return null if the photo cannot be read
     */
    public static Drawable image(Context ctx, String uri, int blur, int w, int h) {
        Bitmap bmp = load(ctx, uri, w, h);
        if (bmp == null) return null;
        if (blur > 0) bmp = blur(bmp, blur);

        BitmapDrawable photo = new BitmapDrawable(ctx.getResources(), bmp);
        // Scrim: light on top, dark at the bottom. A blurred photo needs less.
        // Light theme means dark text, so the photo has to be washed out, not darkened
        boolean light = isLightTheme(ctx);
        int topA = blur > 12 ? 0x55 : 0x66;
        GradientDrawable scrim = light
                ? new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66FFFFFF, 0xAAFFFFFF, 0xE0FFFFFF})
                : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(topA << 24), 0xAA000000, 0xD8000000});
        return new LayerDrawable(new Drawable[]{photo, scrim});
    }

    // ------------------------------------------------------------------ photo

    /** Reads the photo, cropping and downscaling it to the target size. */
    private static Bitmap load(Context ctx, String uriString, int w, int h) {
        if (uriString == null || uriString.isEmpty()) return null;
        if (w <= 0 || h <= 0) {
            w = ctx.getResources().getDisplayMetrics().widthPixels;
            h = ctx.getResources().getDisplayMetrics().heightPixels;
        }
        // Blurring needs no detail anyway, so never decode a large bitmap
        int maxW = Math.min(w, 720);
        int maxH = Math.max(1, Math.round(maxW * (h / (float) w)));

        Uri uri = Uri.parse(uriString);
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, probe);
            }
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(probe.outWidth, probe.outHeight, maxW, maxH);
            Bitmap full;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                full = BitmapFactory.decodeStream(in, null, opts);
            }
            if (full == null) return null;
            return centerCrop(full, maxW, maxH);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "could not read the background photo", e);
            return null;
        }
    }

    private static int sampleSize(int srcW, int srcH, int reqW, int reqH) {
        int s = 1;
        while (srcW / (s * 2) >= reqW && srcH / (s * 2) >= reqH) s *= 2;
        return s;
    }

    /** Centre-crops to the screen aspect; stretching would distort the photo. */
    private static Bitmap centerCrop(Bitmap src, int w, int h) {
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int sw = Math.max(1, Math.round(w / scale));
        int sh = Math.max(1, Math.round(h / scale));
        int x = Math.max(0, (src.getWidth() - sw) / 2);
        int y = Math.max(0, (src.getHeight() - sh) / 2);
        sw = Math.min(sw, src.getWidth() - x);
        sh = Math.min(sh, src.getHeight() - y);
        Bitmap cut = Bitmap.createBitmap(src, x, y, sw, sh);
        Bitmap out = Bitmap.createScaledBitmap(cut, w, h, true);
        if (cut != src) cut.recycle();
        if (src != out && !src.isRecycled()) src.recycle();
        return out;
    }

    /**
     * Box blur, one horizontal and one vertical pass.
     *
     * RenderEffect exists only on Android 12+ and only works on a View; both
     * the preview and the real call screen need a Bitmap, so we run our own
     * pass. The photo is already down to 720 px, so it takes a few
     * milliseconds.
     */
    private static Bitmap blur(Bitmap src, int radius) {
        int r = Math.max(1, Math.min(MAX_BLUR, radius));
        int w = src.getWidth(), h = src.getHeight();
        int[] pix = new int[w * h];
        src.getPixels(pix, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        boxBlur(pix, tmp, w, h, r, true);
        boxBlur(tmp, pix, w, h, r, false);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pix, 0, w, 0, 0, w, h);
        if (!src.isRecycled()) src.recycle();
        return out;
    }

    /** A running sum along one axis: O(1) per pixel. */
    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int outer = horizontal ? h : w;
        int inner = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        for (int o = 0; o < outer; o++) {
            int base = horizontal ? o * w : o;
            int rs = 0, gs = 0, bs = 0, n = 0;
            for (int i = -r; i <= r; i++) {
                int idx = base + Math.max(0, Math.min(inner - 1, i)) * step;
                int c = in[idx];
                rs += Color.red(c);
                gs += Color.green(c);
                bs += Color.blue(c);
                n++;
            }
            for (int i = 0; i < inner; i++) {
                out[base + i * step] = Color.rgb(rs / n, gs / n, bs / n);
                int add = base + Math.max(0, Math.min(inner - 1, i + r + 1)) * step;
                int sub = base + Math.max(0, Math.min(inner - 1, i - r)) * step;
                int ca = in[add], cb = in[sub];
                rs += Color.red(ca) - Color.red(cb);
                gs += Color.green(ca) - Color.green(cb);
                bs += Color.blue(ca) - Color.blue(cb);
            }
        }
    }
}
