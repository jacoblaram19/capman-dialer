package com.capman.dialer;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Motion shortcuts for incoming calls.
 *
 * - RAISE TO EAR: lifting the ringing phone to your ear answers it. Three
 *   conditions must hold together so it never fires by accident:
 *     1. Proximity must read FAR first and NEAR afterwards. A phone ringing in
 *        a pocket has the sensor covered from the start, so the condition is
 *        never met - it cannot answer itself in your pocket.
 *     2. The phone must be upright, as if held to an ear, not lying flat on a
 *        table.
 *     3. Proximity must stay NEAR for a short while - a hand passing over it is
 *        not enough.
 *
 * - FLIP FACE DOWN: turning the phone screen-down silences the ringer. If the
 *   phone was ALREADY face down when the call arrived it does nothing: we first
 *   confirm we have seen it face up, and only then start counting a flip.
 */
public class MotionGestures implements SensorEventListener {

    private static final String TAG = "MotionGestures";

    /** Z-axis thresholds for counting the screen as facing up or down. */
    private static final float FACE_UP_Z = 6.5f;
    private static final float FACE_DOWN_Z = -6.5f;

    /**
     * How upright the phone must be to count as held to an ear. Kept loose,
     * because people hold a phone at surprisingly shallow angles.
     */
    private static final float UPRIGHT_Y = 2.5f;
    /** Only there to rule out a phone lying perfectly flat on a table. */
    private static final float FLAT_Z = 8.5f;

    /** Proximity has to stay covered this long, so a passing hand does nothing. */
    private static final long EAR_HOLD_MS = 250;

    /** Low-pass filter on the accelerometer, to strip the shake out of the motion. */
    private static final float SMOOTH = 0.2f;

    public interface Listener {
        void onRaisedToEar();

        void onFlippedFaceDown();
    }

    private final SensorManager sensors;
    private final Sensor accelerometer;
    private final Sensor proximity;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean running = false;
    private boolean earEnabled = true;
    private boolean flipEnabled = true;

    private boolean sawFaceUp = false;
    private boolean flipFired = false;

    private boolean sawFar = false;
    private boolean earFired = false;
    private volatile boolean covered = false;

    /** Filtered gravity component, used to judge uprightness. */
    private volatile float gY = 0, gZ = 0;
    private volatile boolean haveGravity = false;

    private final Runnable earCheck = new Runnable() {
        @Override
        public void run() {
            if (!running || earFired) return;
            // Time is up: is it still covered, and is the phone upright?
            // Uprightness is checked HERE because at the moment of covering the
            // phone is still moving, so the accelerometer does not report
            // gravity correctly yet.
            if (!covered) {
                Log.w(TAG, "ear: proximity cleared, giving up");
                return;
            }
            if (!upright()) {
                Log.w(TAG, "ear: not upright (gY=" + gY + " gZ=" + gZ + ")");
                return;
            }
            earFired = true;
            Log.d(TAG, "raised to ear -> answering");
            listener.onRaisedToEar();
        }
    };

    public MotionGestures(Context ctx, Listener listener) {
        this.listener = listener;
        this.sensors = ctx.getSystemService(SensorManager.class);
        this.accelerometer = sensors == null ? null
                : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.proximity = sensors == null ? null
                : sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
    }

    /** Only ever enabled while the phone is RINGING. */
    public void start(boolean ear, boolean flip) {
        this.earEnabled = ear;
        this.flipEnabled = flip;
        if (running || sensors == null || accelerometer == null) return;
        if (!ear && !flip) return;

        sawFaceUp = false;
        flipFired = false;
        sawFar = false;
        earFired = false;
        covered = false;
        haveGravity = false;    // don't reuse the gravity reading left over from the previous call

        sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        if (proximity != null) {
            sensors.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
        }
        running = true;
    }

    public void stop() {
        handler.removeCallbacks(earCheck);
        if (!running || sensors == null) return;
        try {
            sensors.unregisterListener(this);
        } catch (Exception ignored) {
        }
        running = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();

        if (type == Sensor.TYPE_PROXIMITY) {
            // Some sensors are binary (0 / max), others report centimetres; handle both
            float v = event.values[0];
            float max = event.sensor.getMaximumRange();
            boolean near = v < Math.min(5f, Math.max(1f, max * 0.5f));
            if (near != covered) Log.d(TAG, "proximity=" + v + " (max " + max + ") near=" + near);
            handleProximity(near);
            return;
        }
        if (type != Sensor.TYPE_ACCELEROMETER) return;

        float y = event.values[1], z = event.values[2];
        if (!haveGravity) {
            gY = y;
            gZ = z;
            haveGravity = true;
        } else {
            gY += (y - gY) * SMOOTH;
            gZ += (z - gZ) * SMOOTH;
        }
        if (flipEnabled) checkFlip(z);
    }

    private void handleProximity(boolean near) {
        covered = near;
        if (!earEnabled || earFired) return;

        if (!near) {
            // The phone is in the open: now we can count a later "out of the pocket"
            sawFar = true;
            handler.removeCallbacks(earCheck);
            return;
        }
        // Covered: if it was covered from the start (pocket) sawFar was never set
        if (!sawFar) {
            Log.w(TAG, "ear: it was covered from the start (pocket?), skipped");
            return;
        }
        handler.removeCallbacks(earCheck);
        handler.postDelayed(earCheck, EAR_HOLD_MS);
    }

    /** Is the phone upright, as if held to an ear? Only a flat one is rejected. */
    private boolean upright() {
        if (!haveGravity) return true;      // olcum yoksa engel olma
        return gY > UPRIGHT_Y && gZ < FLAT_Z;
    }

    /** Face up must be seen first, and only then a flip. */
    private void checkFlip(float z) {
        if (flipFired) return;
        if (z > FACE_UP_Z) {
            sawFaceUp = true;
            return;
        }
        if (sawFaceUp && z < FACE_DOWN_Z) {
            flipFired = true;
            Log.d(TAG, "phone turned face down");
            listener.onFlippedFaceDown();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
