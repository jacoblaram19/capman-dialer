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

    /** The confetti outlasts the eat animation. */
    private static final long CELEBRATE_MS = 900;
    private long celebrateAt = 0;

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
        celebrateAt = 0;
        resetAll();
    }

    /** Accept only the given gesture, so the tour can teach them one at a time. */
    public void setAllowed(boolean answer, boolean reject) {
        allowAnswer = answer;
        allowReject = reject;
        invalidate();
    }

    private boolean allowAnswer = true, allowReject = true;

    /** Visual theme. The mechanic never changes, only the shapes drawn. */
    private int theme = CallThemes.PACMAN;
    /** In the theme picker the strip draws its own confetti. */
    private boolean previewMode = false;

    public void setPreviewMode(boolean on) {
        previewMode = on;
    }

    public void setTheme(int t) {
        theme = t;
        invalidate();
    }

    private int cHandle() {
        return CallThemes.handleColor(theme);
    }

    private int cAnswer() {
        return CallThemes.answerColor(theme);
    }

    private int cReject() {
        return CallThemes.rejectColor(theme);
    }

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
            drawTrack(canvas, chompHomeX, chompHomeY, answerX, answerY, cAnswer(),
                    drag == DRAG_CHOMP, answerProgress(), chompR);
        }
        if (allowReject) {
            drawTrack(canvas, skullHomeX, skullHomeY, chompHomeX, chompHomeY, cReject(),
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
            if (allowReject) drawRejectHandle(canvas, sx, sy, targetR, (int) (255 * (1f - 0.55f * deathT)));
            if (deathT < 1f) drawAnswerHandle(canvas, px, py);
        } else {
            drawAnswerHandle(canvas, px, py);
            if (allowReject) drawRejectHandle(canvas, sx, sy, targetR, 255);
        }

        // The "grab me" ring, only on the two draggable objects
        if (idle) {
            if (allowAnswer) drawGrabRing(canvas, px, py, chompR, cHandle());
            if (allowReject) drawGrabRing(canvas, sx, sy, targetR, cReject());
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
                           int color, boolean active, float progress, float halfWidth) {
        stroke.setStrokeCap(Paint.Cap.ROUND);

        stroke.setStrokeWidth(halfWidth * 1.15f);
        stroke.setColor(color);
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
    private void drawGrabRing(Canvas canvas, float cx, float cy, float r, int color) {
        float t = (System.currentTimeMillis() - startTime) / 1400f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * Math.PI * 2);
        stroke.setColor(color);
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
            drawPellet(canvas, chompHomeX + chompDx * d, chompHomeY + chompDy * d, cAnswer());
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
            drawPellet(canvas, skullHomeX + skullDx * d, skullHomeY + skullDy * d, cReject());
        }
    }

    private void drawPellet(Canvas canvas, float x, float y, int color) {
        float phase = (System.currentTimeMillis() - startTime) / 600f + (x + y) / 120f;
        float pulse = 1f + 0.12f * (float) Math.sin(phase);
        fill.setColor(color);
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
        fill.setColor(cAnswer());
        fill.setAlpha((int) (Math.min(255, glow) * shrink));
        canvas.drawCircle(answerX, answerY, r, fill);
        fill.setAlpha(255);

        stroke.setColor(cAnswer());
        stroke.setStrokeWidth(dp(2));
        stroke.setAlpha((int) (220 * shrink));
        canvas.drawCircle(answerX, answerY, r, stroke);
        stroke.setAlpha(255);

        drawTargetIcon(canvas, answerX, answerY, targetR * 0.60f * shrink);
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
        stroke.setColor(cAnswer());
        stroke.setAlpha((int) (200 * (1f - eatT)));
        stroke.setStrokeWidth(dp(3) * (1f - eatT) + 1f);
        canvas.drawCircle(answerX, answerY, targetR * (1f + 1.5f * eatT), stroke);
        stroke.setAlpha(255);
    }

    /** The skull. It reddens as it nears the chomper. */
    private void drawSkullBadge(Canvas canvas, float cx, float cy, float baseR, int alpha) {
        float progress = rejectProgress();
        float r = baseR * (1f + 0.10f * progress);
        float a = alpha / 255f;

        int glow = (int) (40 + 150 * progress);
        fill.setColor(cReject());
        fill.setAlpha((int) (Math.min(255, glow) * a));
        canvas.drawCircle(cx, cy, r, fill);
        fill.setAlpha(255);

        stroke.setColor(cReject());
        stroke.setStrokeWidth(dp(2));
        stroke.setAlpha((int) (220 * a));
        canvas.drawCircle(cx, cy, r, stroke);
        stroke.setAlpha(255);

        drawRejectIcon(canvas, cx, cy, baseR * 0.62f, alpha);
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

    private void drawChomperShape(Canvas canvas, float cx, float cy) {
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

        fill.setColor(cHandle());
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
        fill.setColor(cHandle());
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

    // ------------------------------------------------------------------ themes
    //
    // A theme defines three shapes only: the draggable "answer" object, the
    // target, and the draggable "reject" object. Positions, distances and
    // trigger thresholds are shared, so the gesture a user has learned does not
    // change when the theme does.

    private void drawAnswerHandle(Canvas canvas, float cx, float cy) {
        if (theme == CallThemes.PACMAN) {
            drawChomperShape(canvas, cx, cy);
            return;
        }
        float scale = 1f, alpha = 1f;
        if (dying) {
            scale = 1f - 0.45f * deathT;
            alpha = Math.max(0f, 1f - deathT * 1.15f);
        } else if (eating) {
            scale = 1f + 0.24f * (float) Math.sin(eatT * Math.PI);
        }

        fill.setColor(cHandle());
        fill.setAlpha((int) (38 * alpha));
        canvas.drawCircle(cx, cy, chompR * 1.35f * scale, fill);
        fill.setAlpha(255);

        int a = (int) (255 * alpha);
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(scale, scale);
        switch (theme) {
            case CallThemes.PLAIN: drawPlainButton(canvas, a); break;
            case CallThemes.ROCKET: drawRocket(canvas, a); break;
            case CallThemes.BALLOON: drawBalloon(canvas, a); break;
            case CallThemes.FOOTBALL: drawBall(canvas, a); break;
            case CallThemes.CAT: drawCat(canvas, a); break;
            default: break;
        }
        canvas.restore();

        if (dying) drawDeathBurst(canvas, cx, cy);
        fill.setAlpha(255);
        stroke.setAlpha(255);
    }

    /** Pieces flung outward as it dies - this is what makes it feel final. */
    private void drawDeathBurst(Canvas canvas, float cx, float cy) {
        int n = 9;
        float spread = chompR * (0.6f + 2.1f * deathT);
        fill.setColor(theme == CallThemes.BALLOON ? cHandle() : cReject());
        fill.setAlpha((int) (220 * (1f - deathT)));
        for (int i = 0; i < n; i++) {
            double ang = Math.PI * 2 * i / n + deathT * 0.8;
            float x = cx + (float) Math.cos(ang) * spread;
            float y = cy + (float) Math.sin(ang) * spread;
            canvas.drawCircle(x, y, chompR * 0.14f * (1f - deathT), fill);
        }
        fill.setAlpha(255);
    }

    // --- answer objects, all centred on 0,0 with radius chompR ---

    private void drawPlainButton(Canvas canvas, int alpha) {
        fill.setColor(cHandle());
        fill.setAlpha(alpha);
        canvas.drawCircle(0, 0, chompR, fill);
        fill.setAlpha(255);
        drawHandset(canvas, 0, 0, chompR * 0.55f);
    }

    private void drawRocket(Canvas canvas, int alpha) {
        // The nose always points where it is heading
        canvas.rotate((float) Math.toDegrees(Math.atan2(chompDy, chompDx)) + 90f);
        float w = chompR * 0.62f, h = chompR;

        if (drag == DRAG_CHOMP || eating) {                 // itki alevi
            fill.setColor(0xFFFF8A3D);
            fill.setAlpha((int) (alpha * 0.9f));
            float flick = 0.75f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 45.0);
            path.reset();
            path.moveTo(-w * 0.42f, h * 0.72f);
            path.lineTo(0, h * (1.05f + 0.45f * flick));
            path.lineTo(w * 0.42f, h * 0.72f);
            path.close();
            canvas.drawPath(path, fill);
        }

        fill.setColor(cHandle());
        fill.setAlpha(alpha);
        path.reset();
        path.moveTo(0, -h);                                // nose
        path.cubicTo(w * 0.95f, -h * 0.18f, w * 0.72f, h * 0.45f, w * 0.46f, h * 0.78f);
        path.lineTo(-w * 0.46f, h * 0.78f);
        path.cubicTo(-w * 0.72f, h * 0.45f, -w * 0.95f, -h * 0.18f, 0, -h);
        path.close();
        canvas.drawPath(path, fill);

        fill.setColor(0xFFE0574F);                         // fins
        fill.setAlpha(alpha);
        path.reset();
        path.moveTo(-w * 0.46f, h * 0.30f);
        path.lineTo(-w * 1.15f, h * 0.92f);
        path.lineTo(-w * 0.46f, h * 0.80f);
        path.close();
        canvas.drawPath(path, fill);
        path.reset();
        path.moveTo(w * 0.46f, h * 0.30f);
        path.lineTo(w * 1.15f, h * 0.92f);
        path.lineTo(w * 0.46f, h * 0.80f);
        path.close();
        canvas.drawPath(path, fill);

        fill.setColor(0xFF3E6BD9);                         // window
        fill.setAlpha(alpha);
        canvas.drawCircle(0, -h * 0.20f, w * 0.34f, fill);
        fill.setAlpha(255);
    }

    private void drawBalloon(Canvas canvas, int alpha) {
        float r = chompR * 0.82f;
        fill.setColor(cHandle());
        fill.setAlpha(alpha);
        rect.set(-r, -chompR, r, r * 1.05f);
        canvas.drawOval(rect, fill);
        path.reset();                                      // knot
        path.moveTo(-r * 0.20f, r * 0.98f);
        path.lineTo(0, r * 1.34f);
        path.lineTo(r * 0.20f, r * 0.98f);
        path.close();
        canvas.drawPath(path, fill);

        stroke.setColor(cHandle());                        // string
        stroke.setAlpha((int) (alpha * 0.8f));
        stroke.setStrokeWidth(Math.max(1f, chompR * 0.055f));
        path.reset();
        path.moveTo(0, r * 1.30f);
        path.quadTo(r * 0.42f, chompR * 1.62f, 0, chompR * 1.95f);
        canvas.drawPath(path, stroke);

        fill.setColor(0x66FFFFFF);                         // highlight
        fill.setAlpha((int) (alpha * 0.4f));
        canvas.drawCircle(-r * 0.32f, -r * 0.38f, r * 0.24f, fill);
        fill.setAlpha(255);
        stroke.setAlpha(255);
    }

    private void drawBall(Canvas canvas, int alpha) {
        fill.setColor(cHandle());
        fill.setAlpha(alpha);
        canvas.drawCircle(0, 0, chompR, fill);

        fill.setColor(0xFF1A1F26);                         // pentagon patches
        fill.setAlpha(alpha);
        pentagon(canvas, 0, 0, chompR * 0.42f, 0);
        for (int i = 0; i < 5; i++) {
            double ang = Math.PI * 2 * i / 5 - Math.PI / 2;
            pentagon(canvas, (float) Math.cos(ang) * chompR * 0.78f,
                    (float) Math.sin(ang) * chompR * 0.78f, chompR * 0.26f, (float) (ang + Math.PI));
        }
        stroke.setColor(0xFF1A1F26);
        stroke.setAlpha((int) (alpha * 0.55f));
        stroke.setStrokeWidth(Math.max(1f, chompR * 0.06f));
        canvas.drawCircle(0, 0, chompR * 0.97f, stroke);
        fill.setAlpha(255);
        stroke.setAlpha(255);
    }

    private void pentagon(Canvas canvas, float cx, float cy, float r, float rot) {
        path.reset();
        for (int i = 0; i < 5; i++) {
            double a = rot + Math.PI * 2 * i / 5 - Math.PI / 2;
            float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        canvas.drawPath(path, fill);
    }

    private void drawCat(Canvas canvas, int alpha) {
        float r = chompR * 0.86f;
        fill.setColor(cHandle());
        fill.setAlpha(alpha);
        path.reset();                                      // ears
        path.moveTo(-r * 0.78f, -r * 0.42f);
        path.lineTo(-r * 0.62f, -r * 1.28f);
        path.lineTo(-r * 0.06f, -r * 0.72f);
        path.close();
        canvas.drawPath(path, fill);
        path.reset();
        path.moveTo(r * 0.78f, -r * 0.42f);
        path.lineTo(r * 0.62f, -r * 1.28f);
        path.lineTo(r * 0.06f, -r * 0.72f);
        path.close();
        canvas.drawPath(path, fill);
        canvas.drawCircle(0, 0, r, fill);                  // head

        fill.setColor(0xFF2A1B12);                         // eyes
        fill.setAlpha(alpha);
        canvas.drawCircle(-r * 0.34f, -r * 0.12f, r * 0.13f, fill);
        canvas.drawCircle(r * 0.34f, -r * 0.12f, r * 0.13f, fill);
        path.reset();                                      // nose
        path.moveTo(0, r * 0.10f);
        path.lineTo(-r * 0.13f, r * 0.26f);
        path.lineTo(r * 0.13f, r * 0.26f);
        path.close();
        canvas.drawPath(path, fill);

        stroke.setColor(0xFF2A1B12);                       // whiskers
        stroke.setAlpha((int) (alpha * 0.75f));
        stroke.setStrokeWidth(Math.max(1f, r * 0.055f));
        for (int i = -1; i <= 1; i += 2) {
            canvas.drawLine(i * r * 0.30f, r * 0.34f, i * r * 1.05f, r * 0.20f, stroke);
            canvas.drawLine(i * r * 0.30f, r * 0.44f, i * r * 1.02f, r * 0.52f, stroke);
        }
        fill.setAlpha(255);
        stroke.setAlpha(255);
    }

    // --- targets ---

    private void drawTargetIcon(Canvas canvas, float cx, float cy, float s) {
        switch (theme) {
            case CallThemes.ROCKET: drawPlanet(canvas, cx, cy, s); break;
            case CallThemes.BALLOON: drawCloud(canvas, cx, cy, s); break;
            case CallThemes.FOOTBALL: drawGoal(canvas, cx, cy, s); break;
            case CallThemes.CAT: drawFish(canvas, cx, cy, s); break;
            default: drawHandset(canvas, cx, cy, s * 0.93f); break;
        }
    }

    private void drawPlanet(Canvas canvas, float cx, float cy, float s) {
        fill.setColor(0xFFC9A6FF);
        canvas.drawCircle(cx, cy, s * 0.72f, fill);
        fill.setColor(0x55FFFFFF);
        canvas.drawCircle(cx - s * 0.26f, cy - s * 0.22f, s * 0.20f, fill);
        stroke.setColor(0xFFF0E6FF);                       // ring
        stroke.setStrokeWidth(Math.max(1.5f, s * 0.13f));
        canvas.save();
        canvas.rotate(-22f, cx, cy);
        rect.set(cx - s * 1.15f, cy - s * 0.30f, cx + s * 1.15f, cy + s * 0.30f);
        canvas.drawOval(rect, stroke);
        canvas.restore();
    }

    private void drawCloud(Canvas canvas, float cx, float cy, float s) {
        fill.setColor(0xFFF4FAFF);
        canvas.drawCircle(cx - s * 0.42f, cy + s * 0.10f, s * 0.42f, fill);
        canvas.drawCircle(cx + s * 0.40f, cy + s * 0.14f, s * 0.36f, fill);
        canvas.drawCircle(cx, cy - s * 0.18f, s * 0.55f, fill);
        rect.set(cx - s * 0.60f, cy + s * 0.10f, cx + s * 0.60f, cy + s * 0.54f);
        canvas.drawRoundRect(rect, s * 0.22f, s * 0.22f, fill);
    }

    private void drawGoal(Canvas canvas, float cx, float cy, float s) {
        stroke.setColor(0xFFF2F4F7);
        stroke.setStrokeWidth(Math.max(1.5f, s * 0.16f));
        rect.set(cx - s * 0.86f, cy - s * 0.60f, cx + s * 0.86f, cy + s * 0.62f);
        canvas.drawRect(rect, stroke);
        stroke.setStrokeWidth(Math.max(1f, s * 0.06f));    // netting
        stroke.setAlpha(150);
        for (int i = 1; i < 4; i++) {
            float x = rect.left + rect.width() * i / 4f;
            canvas.drawLine(x, rect.top, x, rect.bottom, stroke);
        }
        for (int i = 1; i < 3; i++) {
            float y = rect.top + rect.height() * i / 3f;
            canvas.drawLine(rect.left, y, rect.right, y, stroke);
        }
        stroke.setAlpha(255);
    }

    private void drawFish(Canvas canvas, float cx, float cy, float s) {
        fill.setColor(0xFFC8F5DF);
        rect.set(cx - s * 0.40f, cy - s * 0.42f, cx + s * 0.86f, cy + s * 0.42f);
        canvas.drawOval(rect, fill);
        path.reset();                                      // tail
        path.moveTo(cx - s * 0.34f, cy);
        path.lineTo(cx - s * 0.92f, cy - s * 0.46f);
        path.lineTo(cx - s * 0.92f, cy + s * 0.46f);
        path.close();
        canvas.drawPath(path, fill);
        fill.setColor(0xFF12382C);                         // eye
        canvas.drawCircle(cx + s * 0.50f, cy - s * 0.10f, s * 0.10f, fill);
    }

    // --- reject objects ---

    private void drawRejectHandle(Canvas canvas, float cx, float cy, float baseR, int alpha) {
        drawSkullBadge(canvas, cx, cy, baseR, alpha);
    }

    private void drawRejectIcon(Canvas canvas, float cx, float cy, float r, int alpha) {
        switch (theme) {
            case CallThemes.PACMAN: drawSkullFace(canvas, cx, cy, r, alpha); break;
            case CallThemes.PLAIN: drawEndCall(canvas, cx, cy, r, alpha); break;
            case CallThemes.ROCKET: drawMeteor(canvas, cx, cy, r, alpha); break;
            case CallThemes.BALLOON: drawNeedle(canvas, cx, cy, r, alpha); break;
            case CallThemes.CAT: drawDrop(canvas, cx, cy, r, alpha); break;
            default: drawRedCard(canvas, cx, cy, r, alpha); break;
        }
    }

    private void drawEndCall(Canvas canvas, float cx, float cy, float r, int alpha) {
        canvas.save();
        canvas.rotate(135f, cx, cy);
        drawHandset(canvas, cx, cy, r * 0.95f);
        canvas.restore();
    }

    private void drawMeteor(Canvas canvas, float cx, float cy, float r, int alpha) {
        fill.setColor(0xFF6B5140);
        fill.setAlpha(alpha);
        path.reset();
        for (int i = 0; i < 9; i++) {                      // rough rock
            double a = Math.PI * 2 * i / 9;
            float rr = r * (i % 2 == 0 ? 0.98f : 0.78f);
            float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        canvas.drawPath(path, fill);
        fill.setColor(0xFF3E2E24);                         // craters
        fill.setAlpha(alpha);
        canvas.drawCircle(cx - r * 0.28f, cy - r * 0.18f, r * 0.20f, fill);
        canvas.drawCircle(cx + r * 0.30f, cy + r * 0.24f, r * 0.14f, fill);
        fill.setAlpha(255);
    }

    private void drawNeedle(Canvas canvas, float cx, float cy, float r, int alpha) {
        canvas.save();
        // The point follows the travel direction. In local space the tip is at
        // +y, so subtract the 90 degrees of screen coordinates; without that the
        // needle ends up upside down.
        canvas.rotate((float) Math.toDegrees(Math.atan2(skullDy, skullDx)) - 90f, cx, cy);
        fill.setColor(0xFFE6ECF4);
        fill.setAlpha(alpha);
        path.reset();                                      // tip pointing down
        path.moveTo(cx, cy + r * 1.15f);
        path.lineTo(cx - r * 0.20f, cy + r * 0.30f);
        path.lineTo(cx + r * 0.20f, cy + r * 0.30f);
        path.close();
        canvas.drawPath(path, fill);
        rect.set(cx - r * 0.20f, cy - r * 0.85f, cx + r * 0.20f, cy + r * 0.34f);
        canvas.drawRoundRect(rect, r * 0.10f, r * 0.10f, fill);
        stroke.setColor(0xFF8A93A0);                       // eye of the needle
        stroke.setAlpha(alpha);
        stroke.setStrokeWidth(Math.max(1f, r * 0.10f));
        rect.set(cx - r * 0.10f, cy - r * 0.72f, cx + r * 0.10f, cy - r * 0.40f);
        canvas.drawOval(rect, stroke);
        canvas.restore();
        fill.setAlpha(255);
        stroke.setAlpha(255);
    }

    private void drawDrop(Canvas canvas, float cx, float cy, float r, int alpha) {
        fill.setColor(0xFFBFE9FF);
        fill.setAlpha(alpha);
        path.reset();
        path.moveTo(cx, cy - r * 1.05f);
        path.cubicTo(cx + r * 0.95f, cy + r * 0.05f, cx + r * 0.62f, cy + r * 0.98f, cx, cy + r * 0.98f);
        path.cubicTo(cx - r * 0.62f, cy + r * 0.98f, cx - r * 0.95f, cy + r * 0.05f, cx, cy - r * 1.05f);
        path.close();
        canvas.drawPath(path, fill);
        fill.setColor(0x88FFFFFF);
        fill.setAlpha((int) (alpha * 0.45f));
        canvas.drawCircle(cx - r * 0.24f, cy + r * 0.30f, r * 0.20f, fill);
        fill.setAlpha(255);
    }

    private void drawRedCard(Canvas canvas, float cx, float cy, float r, int alpha) {
        canvas.save();
        canvas.rotate(-14f, cx, cy);
        fill.setColor(0xFFE01B3C);
        fill.setAlpha(alpha);
        rect.set(cx - r * 0.55f, cy - r * 0.86f, cx + r * 0.55f, cy + r * 0.86f);
        canvas.drawRoundRect(rect, r * 0.14f, r * 0.14f, fill);
        stroke.setColor(0x66FFFFFF);
        stroke.setAlpha((int) (alpha * 0.5f));
        stroke.setStrokeWidth(Math.max(1f, r * 0.08f));
        canvas.drawRoundRect(rect, r * 0.14f, r * 0.14f, stroke);
        canvas.restore();
        fill.setAlpha(255);
        stroke.setAlpha(255);
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
