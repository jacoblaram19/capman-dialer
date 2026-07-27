package com.capman.dialer;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * The chomper strip for incoming calls.
 *
 * There are two separate drags, each with exactly ONE destination:
 *   - Grab the CHOMPER and take it to the green handset to answer (it eats the
 *     pellets on the way).
 *   - Grab the SKULL and take it to the chomper to reject (the chomper dies).
 *
 * A single object used to be dragged in two directions; moving quickly could
 * lock onto the wrong axis and reject the call by accident. Now the object you
 * grab decides the action, so there is nothing left to confuse.
 */
public class ChompSwipeView extends View {

    public interface OnActionListener {
        void onAnswer();

        void onReject();
    }

    private static final int DRAG_NONE = 0;
    private static final int DRAG_CHOMP = 1;
    private static final int DRAG_SKULL = 2;

    /** Length of the death animation, exposed so the screen can close right as it ends. */
    public static final long DEATH_MS = 420;

    /** How much of the path answering takes. */
    private static final float TRIGGER_ANSWER = 0.72f;
    /** Rejecting cannot be undone, so it asks for a little more. */
    private static final float TRIGGER_REJECT = 0.80f;

    /**
     * Three hints that explain the gesture WITHOUT any words:
     *  1. The pellets flow along the path toward where you are meant to go.
     *  2. A ring pulses around the two draggable objects (the chomper and the
     *     skull) and not around the destination, which reads as "these two you
     *     grab, that one you don't".
     *  3. Every now and then each object nudges toward its destination and
     *     comes back.
     */
    private static final float FLOW_DP_PER_SEC = 14f;
    private static final long NUDGE_PERIOD_MS = 2800;
    private static final long NUDGE_DUR_MS = 460;
    private static final float NUDGE_DP = 11f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private OnActionListener listener;
    private android.graphics.drawable.Drawable handset;

    private final float density;
    private final float pad, chompR, targetR, pelletR, pelletGap;

    /** Fixed positions: chomper bottom-left, skull top-left, handset top-right. */
    private float chompHomeX, chompHomeY, skullHomeX, skullHomeY, answerX, answerY;
    /** The axes: chomper -> handset, skull -> chomper. */
    private float chompDx, chompDy, chompLen, skullDx, skullDy, skullLen;

    private float chompX, chompY, skullX, skullY;
    private int drag = DRAG_NONE;
    private boolean fired = false;
    private float downX, downY;

    private final long startTime;
    private ValueAnimator returnAnim;

    /** The eat-the-handset animation. */
    private boolean eating = false;
    private float eatT = 0f;
    /** The chomper's death animation: the mouth closes and it disappears. */
    private boolean dying = false;
    private float deathT = 0f;

    public ChompSwipeView(Context c) {
        this(c, null);
    }

    public ChompSwipeView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        pad = dp(34);
        chompR = dp(33);
        targetR = dp(33);
        pelletR = dp(5);
        pelletGap = dp(30);
        stroke.setStyle(Paint.Style.STROKE);
        startTime = System.currentTimeMillis();
    }

    public void setOnActionListener(OnActionListener l) {
        this.listener = l;
    }

    /** Exposed because the tour reuses the same strip twice. */
    public void reset() {
        fired = false;
        resetAll();
    }

    /** Accept only the given gesture, so the tour can teach them one at a time. */
    public void setAllowed(boolean answer, boolean reject) {
        allowAnswer = answer;
        allowReject = reject;
        invalidate();
    }

    private boolean allowAnswer = true, allowReject = true;

    private float dp(float v) {
        return v * density;
    }

    /** Centre of the green handset, used by the transition into the in-call screen. */
    public float answerCenterX() {
        return answerX;
    }

    public float answerCenterY() {
        return answerY;
    }

    public float answerRadius() {
        return targetR;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        chompHomeX = pad + chompR;
        chompHomeY = h - pad - chompR;
        skullHomeX = chompHomeX;
        skullHomeY = pad + targetR;
        answerX = w - pad - targetR;
        answerY = pad + targetR;

        float ax = answerX - chompHomeX, ay = answerY - chompHomeY;
        chompLen = (float) Math.hypot(ax, ay);
        chompDx = chompLen == 0 ? 1 : ax / chompLen;
        chompDy = chompLen == 0 ? 0 : ay / chompLen;

        float sx = chompHomeX - skullHomeX, sy = chompHomeY - skullHomeY;
        skullLen = (float) Math.hypot(sx, sy);
        skullDx = skullLen == 0 ? 0 : sx / skullLen;
        skullDy = skullLen == 0 ? 1 : sy / skullLen;

        resetAll();
    }

    private void resetAll() {
        chompX = chompHomeX;
        chompY = chompHomeY;
        skullX = skullHomeX;
        skullY = skullHomeY;
        drag = DRAG_NONE;
        eating = false;
        dying = false;
        eatT = 0f;
        deathT = 0f;
        invalidate();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0) return;

        // --- the paths: faint bands. The part already covered takes on colour.
        if (allowAnswer) {
            drawTrack(canvas, chompHomeX, chompHomeY, answerX, answerY, R.color.green,
                    drag == DRAG_CHOMP, answerProgress(), chompR);
        }
        if (allowReject) {
            drawTrack(canvas, skullHomeX, skullHomeY, chompHomeX, chompHomeY, R.color.red,
                    drag == DRAG_SKULL, rejectProgress(), targetR);
        }

        if (allowAnswer) drawAnswerPellets(canvas);
        if (allowReject) drawSkullPellets(canvas);

        if ((!eating || eatT < 1f) && allowAnswer) drawAnswerTarget(canvas);
        drawEatBurst(canvas);

        // While idle, the objects nudge toward their destinations
        boolean idle = drag == DRAG_NONE && !fired;
        float nP = idle ? nudge(0) * dp(NUDGE_DP) : 0;
        float nS = idle ? nudge(NUDGE_PERIOD_MS / 2) * dp(NUDGE_DP) : 0;
        float px = chompX + chompDx * nP, py = chompY + chompDy * nP;
        float sx = skullX + skullDx * nS, sy = skullY + skullDy * nS;

        // While dying, the chomper is drawn ON TOP of the skull, otherwise the
        // death is hidden underneath it. The skull fades a little and leaves the
        // stage to the chomper.
        if (dying) {
            if (allowReject) drawSkull(canvas, sx, sy, targetR, (int) (255 * (1f - 0.55f * deathT)));
            if (deathT < 1f) drawChomper(canvas, px, py);
        } else {
            drawChomper(canvas, px, py);
            if (allowReject) drawSkull(canvas, sx, sy, targetR, 255);
        }

        // The "grab me" ring, only on the two draggable objects
        if (idle) {
            if (allowAnswer) drawGrabRing(canvas, px, py, chompR, R.color.chomp_yellow);
            if (allowReject) drawGrabRing(canvas, sx, sy, targetR, R.color.red);
        }

        postInvalidateOnAnimation();
    }

    /** Produces a short 0 -> 1 -> 0 nudge pulse. */
    private float nudge(long offsetMs) {
        long t = (System.currentTimeMillis() - startTime + offsetMs) % NUDGE_PERIOD_MS;
        if (t > NUDGE_DUR_MS) return 0f;
        return (float) Math.sin(t / (double) NUDGE_DUR_MS * Math.PI);
    }

    /**
     * The path band: very faint normally, slightly stronger while that path is
     * being dragged, and the covered part fills with the object's colour.
     */
    private void drawTrack(Canvas canvas, float x1, float y1, float x2, float y2,
                           int colorRes, boolean active, float progress, float halfWidth) {
        stroke.setStrokeCap(Paint.Cap.ROUND);

        stroke.setStrokeWidth(halfWidth * 1.15f);
        stroke.setColor(getContext().getColor(colorRes));
        stroke.setAlpha(active ? 26 : 11);
        canvas.drawLine(x1, y1, x2, y2, stroke);

        if (active && progress > 0.02f) {
            stroke.setAlpha(58);
            canvas.drawLine(x1, y1, x1 + (x2 - x1) * progress, y1 + (y2 - y1) * progress, stroke);
        }
        stroke.setAlpha(255);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    /** The ring pulsing around the objects you can grab. */
    private void drawGrabRing(Canvas canvas, float cx, float cy, float r, int colorRes) {
        float t = (System.currentTimeMillis() - startTime) / 1400f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * Math.PI * 2);
        stroke.setColor(getContext().getColor(colorRes));
        stroke.setStrokeWidth(dp(2));
        stroke.setAlpha((int) (30 + 55 * pulse));
        canvas.drawCircle(cx, cy, r * (1.22f + 0.10f * pulse), stroke);
        stroke.setAlpha(255);
    }

    /**
     * The pellets flow toward the destination. This is the main wordless cue:
     * green pellets drift to the handset, red ones to the chomper.
     */
    private float flowOffset() {
        float perSec = dp(FLOW_DP_PER_SEC);
        float travelled = (System.currentTimeMillis() - startTime) / 1000f * perSec;
        return travelled % pelletGap;
    }

    /** The green pellets eaten along the chomper's path. */
    private void drawAnswerPellets(Canvas canvas) {
        float travelled = (float) Math.hypot(chompX - chompHomeX, chompY - chompHomeY);
        float from = chompR + pelletGap * 0.6f;
        float to = chompLen - targetR - pelletGap * 0.4f;
        float flow = flowOffset();
        for (float d = from + flow; d <= to; d += pelletGap) {
            if (d <= travelled + chompR * 0.35f) continue;
            drawPellet(canvas, chompHomeX + chompDx * d, chompHomeY + chompDy * d, R.color.green);
        }
    }

    /** The red pellets crushed as the skull passes over them. */
    private void drawSkullPellets(Canvas canvas) {
        float travelled = (float) Math.hypot(skullX - skullHomeX, skullY - skullHomeY);
        float from = targetR + pelletGap * 0.6f;
        float to = skullLen - chompR - pelletGap * 0.4f;
        float flow = flowOffset();
        for (float d = from + flow; d <= to; d += pelletGap) {
            if (d <= travelled + targetR * 0.35f) continue;
            drawPellet(canvas, skullHomeX + skullDx * d, skullHomeY + skullDy * d, R.color.red);
        }
    }

    private void drawPellet(Canvas canvas, float x, float y, int colorRes) {
        float phase = (System.currentTimeMillis() - startTime) / 600f + (x + y) / 120f;
        float pulse = 1f + 0.12f * (float) Math.sin(phase);
        fill.setColor(getContext().getColor(colorRes));
        fill.setAlpha(150);
        canvas.drawCircle(x, y, pelletR * pulse, fill);
        fill.setAlpha(255);
    }

    /** The green handset. It glows as the chomper closes in and shrinks as it is eaten. */
    private void drawAnswerTarget(Canvas canvas) {
        float progress = answerProgress();
        float shrink = eating ? 1f - eatT : 1f;
        if (shrink <= 0.02f) return;
        float r = targetR * (1f + 0.10f * progress) * shrink;

        int glow = (int) (40 + 150 * progress);
        fill.setColor(getContext().getColor(R.color.green));
        fill.setAlpha((int) (Math.min(255, glow) * shrink));
        canvas.drawCircle(answerX, answerY, r, fill);
        fill.setAlpha(255);

        stroke.setColor(getContext().getColor(R.color.green));
        stroke.setStrokeWidth(dp(2));
        stroke.setAlpha((int) (220 * shrink));
        canvas.drawCircle(answerX, answerY, r, stroke);
        stroke.setAlpha(255);

        drawHandset(canvas, answerX, answerY, targetR * 0.56f * shrink);
    }

    private void drawHandset(Canvas canvas, float cx, float cy, float s) {
        if (handset == null) {
            handset = getContext().getDrawable(R.drawable.ic_phone);
            if (handset != null) handset.setTint(Color.WHITE);
        }
        if (handset == null) return;
        handset.setBounds((int) (cx - s), (int) (cy - s), (int) (cx + s), (int) (cy + s));
        handset.draw(canvas);
    }

    private void drawEatBurst(Canvas canvas) {
        if (!eating) return;
        stroke.setColor(getContext().getColor(R.color.green));
        stroke.setAlpha((int) (200 * (1f - eatT)));
        stroke.setStrokeWidth(dp(3) * (1f - eatT) + 1f);
        canvas.drawCircle(answerX, answerY, targetR * (1f + 1.5f * eatT), stroke);
        stroke.setAlpha(255);
    }

    /** The skull. It reddens as it nears the chomper. */
    private void drawSkull(Canvas canvas, float cx, float cy, float baseR, int alpha) {
        float progress = rejectProgress();
        float r = baseR * (1f + 0.10f * progress);
        float a = alpha / 255f;

        int glow = (int) (40 + 150 * progress);
        fill.setColor(getContext().getColor(R.color.red));
        fill.setAlpha((int) (Math.min(255, glow) * a));
        canvas.drawCircle(cx, cy, r, fill);
        fill.setAlpha(255);

        stroke.setColor(getContext().getColor(R.color.red));
        stroke.setStrokeWidth(dp(2));
        stroke.setAlpha((int) (220 * a));
        canvas.drawCircle(cx, cy, r, stroke);
        stroke.setAlpha(255);

        drawSkullFace(canvas, cx, cy, baseR * 0.62f, alpha);
    }

    private void drawSkullFace(Canvas canvas, float cx, float cy, float r, int alpha) {
        fill.setColor(Color.WHITE);
        fill.setAlpha(alpha);
        canvas.drawCircle(cx, cy - r * 0.12f, r * 0.78f, fill);
        rect.set(cx - r * 0.42f, cy + r * 0.35f, cx + r * 0.42f, cy + r * 0.92f);
        canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, fill);

        fill.setColor(getContext().getColor(R.color.red_deep));
        fill.setAlpha(alpha);
        canvas.drawCircle(cx - r * 0.32f, cy - r * 0.18f, r * 0.24f, fill);
        canvas.drawCircle(cx + r * 0.32f, cy - r * 0.18f, r * 0.24f, fill);
        path.reset();
        path.moveTo(cx, cy + r * 0.06f);
        path.lineTo(cx - r * 0.13f, cy + r * 0.30f);
        path.lineTo(cx + r * 0.13f, cy + r * 0.30f);
        path.close();
        canvas.drawPath(path, fill);
        stroke.setColor(getContext().getColor(R.color.red_deep));
        stroke.setAlpha(alpha);
        stroke.setStrokeWidth(Math.max(1f, r * 0.09f));
        canvas.drawLine(cx - r * 0.14f, cy + r * 0.38f, cx - r * 0.14f, cy + r * 0.90f, stroke);
        canvas.drawLine(cx + r * 0.14f, cy + r * 0.38f, cx + r * 0.14f, cy + r * 0.90f, stroke);
        fill.setAlpha(255);
        stroke.setAlpha(255);
    }

    private void drawChomper(Canvas canvas, float cx, float cy) {
        float mouth;
        float scale = 1f;
        int alpha = 255;

        if (dying) {
            // The classic death: the mouth opens all the way and the body vanishes
            mouth = 6 + 174 * deathT;
            scale = 1f - 0.25f * deathT;
            alpha = (int) (255 * (1f - deathT * 0.9f));
        } else if (eating) {
            mouth = 4 + 36 * Math.abs((float) Math.sin(eatT * Math.PI * 2f));
            scale = 1f + 0.24f * (float) Math.sin(eatT * Math.PI);
        } else {
            float speed = drag == DRAG_CHOMP ? 260f : 420f;
            float t = (System.currentTimeMillis() - startTime) / speed;
            mouth = 6 + 30 * Math.abs((float) Math.sin(t));
        }

        float rotation = (float) Math.toDegrees(Math.atan2(chompDy, chompDx));

        fill.setColor(getContext().getColor(R.color.chomp_yellow));
        fill.setAlpha(eating ? (int) (38 + 60 * Math.sin(eatT * Math.PI)) : (int) (38 * alpha / 255f));
        canvas.drawCircle(cx, cy, chompR * 1.35f * scale, fill);

        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(rotation);
        canvas.scale(scale, scale);
        rect.set(-chompR, -chompR, chompR, chompR);
        path.reset();
        path.moveTo(0, 0);
        path.arcTo(rect, mouth, Math.max(0, 360 - 2 * mouth));
        path.close();
        fill.setColor(getContext().getColor(R.color.chomp_yellow));
        fill.setAlpha(alpha);
        canvas.drawPath(path, fill);

        if (!dying) {
            fill.setColor(0xFF2A2000);
            fill.setAlpha(alpha);
            canvas.drawCircle(chompR * 0.12f, -chompR * 0.42f, chompR * 0.11f, fill);
        }
        canvas.restore();
        fill.setAlpha(255);
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (fired) return false;
                float x = e.getX(), y = e.getY();
                if (allowAnswer && Math.hypot(x - chompX, y - chompY) <= chompR * 1.9f) {
                    drag = DRAG_CHOMP;
                } else if (allowReject && Math.hypot(x - skullX, y - skullY) <= targetR * 1.9f) {
                    drag = DRAG_SKULL;
                } else {
                    return false;
                }
                if (returnAnim != null) returnAnim.cancel();
                downX = x;
                downY = y;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (drag == DRAG_NONE) return false;
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (drag == DRAG_CHOMP) {
                    float along = clamp(dx * chompDx + dy * chompDy, 0, chompLen);
                    chompX = chompHomeX + chompDx * along;
                    chompY = chompHomeY + chompDy * along;
                } else {
                    float along = clamp(dx * skullDx + dy * skullDy, 0, skullLen);
                    skullX = skullHomeX + skullDx * along;
                    skullY = skullHomeY + skullDy * along;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (drag == DRAG_NONE) return false;
                int which = drag;
                drag = DRAG_NONE;
                if (which == DRAG_CHOMP && answerProgress() >= TRIGGER_ANSWER) {
                    fireAnswer();
                } else if (which == DRAG_SKULL && rejectProgress() >= TRIGGER_REJECT) {
                    fireReject();
                } else {
                    animateHome(which);
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private float answerProgress() {
        if (chompLen <= 0) return 0;
        return clamp((float) Math.hypot(chompX - chompHomeX, chompY - chompHomeY) / chompLen, 0, 1);
    }

    private float rejectProgress() {
        if (skullLen <= 0) return 0;
        return clamp((float) Math.hypot(skullX - skullHomeX, skullY - skullHomeY) / skullLen, 0, 1);
    }

    // ------------------------------------------------------------------ actions

    private void fireAnswer() {
        if (fired) return;
        fired = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        slide(chompX, chompY, answerX, answerY, true, this::startEat);
    }

    private void fireReject() {
        if (fired) return;
        fired = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        slide(skullX, skullY, chompX, chompY, false, this::startDeath);
    }

    /** Snaps to the destination and then runs the given work. */
    private void slide(float fromX, float fromY, float toX, float toY,
                       boolean moveChomp, Runnable then) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(110);
        a.setInterpolator(new android.view.animation.AccelerateInterpolator(1.6f));
        a.addUpdateListener(v -> {
            float f = (float) v.getAnimatedValue();
            if (moveChomp) {
                chompX = fromX + (toX - fromX) * f;
                chompY = fromY + (toY - fromY) * f;
            } else {
                skullX = fromX + (toX - fromX) * f;
                skullY = fromY + (toY - fromY) * f;
            }
            invalidate();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                then.run();
            }
        });
        a.start();
    }

    /** Eating the handset: the action fires at the START of the animation, so nothing feels laggy. */
    private void startEat() {
        eating = true;
        eatT = 0f;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (listener != null) listener.onAnswer();

        ValueAnimator eat = ValueAnimator.ofFloat(0f, 1f);
        eat.setDuration(240);
        eat.addUpdateListener(v -> {
            eatT = (float) v.getAnimatedValue();
            invalidate();
        });
        eat.start();
    }

    /** The skull reached the chomper: the chomper dies. */
    private void startDeath() {
        dying = true;
        deathT = 0f;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (listener != null) listener.onReject();

        ValueAnimator death = ValueAnimator.ofFloat(0f, 1f);
        death.setDuration(DEATH_MS);
        death.addUpdateListener(v -> {
            deathT = (float) v.getAnimatedValue();
            invalidate();
        });
        death.start();
    }

    private void animateHome(final int which) {
        final float fromX = which == DRAG_CHOMP ? chompX : skullX;
        final float fromY = which == DRAG_CHOMP ? chompY : skullY;
        final float toX = which == DRAG_CHOMP ? chompHomeX : skullHomeX;
        final float toY = which == DRAG_CHOMP ? chompHomeY : skullHomeY;

        returnAnim = ValueAnimator.ofFloat(0f, 1f);
        returnAnim.setDuration(220);
        returnAnim.addUpdateListener(v -> {
            float f = (float) v.getAnimatedValue();
            if (which == DRAG_CHOMP) {
                chompX = fromX + (toX - fromX) * f;
                chompY = fromY + (toY - fromY) * f;
            } else {
                skullX = fromX + (toX - fromX) * f;
                skullY = fromY + (toY - fromY) * f;
            }
            invalidate();
        });
        returnAnim.start();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
