package com.capman.dialer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

/**
 * Confetti that bursts when a call is answered.
 *
 * Why this is its own view: the strip ({@link ChompSwipeView}) used to draw the
 * confetti, but answering hides the incoming-call panel immediately, so it was
 * never visible on a real call - only in the theme picker. It is now an
 * independent layer added on top of the call screen that removes itself once
 * its time is up.
 */
public class CelebrationView extends View {

    private static final long DURATION_MS = 900;
    private static final int PIECES = 40;
    /** Past this point the pieces stop scattering and gather at the button. */
    private static final float GATHER_FROM = 0.45f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final float originX, originY, unit;
    /** Where the confetti gathers: the centre of the hang-up button. */
    private final float sinkX, sinkY;
    private final int[] colors;
    private final long startedAt = System.currentTimeMillis();

    /**
     * @param originX,originY centre of the burst, in this view's coordinates
     * @param unit            scales the piece size; the target's radius
     */
    public CelebrationView(Context ctx, float originX, float originY,
                           float sinkX, float sinkY, float unit, int[] colors) {
        super(ctx);
        this.originX = originX;
        this.originY = originY;
        this.sinkX = sinkX;
        this.sinkY = sinkY;
        this.unit = unit;
        this.colors = colors;
        setWillNotDraw(false);
    }

    /** Does this theme celebrate? If not, the layer is never added. */
    public static int[] colorsFor(int theme) {
        if (theme == CallThemes.FOOTBALL) {
            return new int[]{0xFF2EE59D, 0xFFFFFFFF, 0xFFE8C46A};
        }
        return null;
    }

    /**
     * Adds itself to the call screen and removes itself when finished.
     *
     * @param sinkX,sinkY where the pieces end up gathering (the hang-up button)
     */
    public static void launch(ViewGroup root, int theme, float x, float y,
                              float sinkX, float sinkY, float unit) {
        int[] colors = colorsFor(theme);
        if (colors == null || root == null) return;
        CelebrationView v = new CelebrationView(root.getContext(), x, y, sinkX, sinkY, unit, colors);
        root.addView(v, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        v.bringToFront();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float t = (System.currentTimeMillis() - startedAt) / (float) DURATION_MS;
        if (t >= 1f) {
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) parent.post(() -> parent.removeView(this));
            return;
        }

        // Fast at first, then slowing: that reads as an explosion
        float ease = 1f - (1f - t) * (1f - t);
        float reach = unit * (0.5f + 7.5f * ease);

        // In the second half the pieces flow into the hang-up button and vanish
        // there, telling the "celebration over, call started" story in one move.
        float gather = t < GATHER_FROM ? 0f : (t - GATHER_FROM) / (1f - GATHER_FROM);
        gather = gather * gather * (3f - 2f * gather);          // ease in and out

        for (int i = 0; i < PIECES; i++) {
            double ang = Math.PI * 2 * i / PIECES + i * 0.31;
            float spin = (float) Math.sin(t * 9 + i);
            float d = reach * (0.35f + 0.65f * ((i % 7) / 6f));
            float bx = originX + (float) Math.cos(ang) * d;
            // gravity: the pieces fall faster and faster
            float by = originY + (float) Math.sin(ang) * d + unit * 5.5f * t * t;

            float x = bx + (sinkX - bx) * gather;
            float y = by + (sinkY - by) * gather;
            float shrink = 1f - 0.85f * gather;

            fill.setColor(colors[i % colors.length]);
            fill.setAlpha((int) (240 * (1f - gather * gather)));
            canvas.save();
            canvas.rotate(spin * 120f, x, y);
            float w = unit * 0.16f * shrink, h = unit * 0.075f * shrink;
            rect.set(x - w, y - h, x + w, y + h);
            canvas.drawRoundRect(rect, h, h, fill);
            canvas.restore();
        }
        postInvalidateOnAnimation();
    }
}
