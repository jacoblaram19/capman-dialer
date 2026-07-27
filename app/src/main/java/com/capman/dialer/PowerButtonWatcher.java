package com.capman.dialer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * Detects two consecutive presses of the power key.
 *
 * Android never hands the power key to apps (the system consumes it first), so
 * we go the indirect way: during a call, the screen going OFF and immediately
 * back ON means the user pressed power twice.
 *
 * A screen blanked by the proximity sensor does not emit these broadcasts, so
 * holding the phone to your ear cannot trigger it by accident.
 */
public class PowerButtonWatcher extends BroadcastReceiver {

    private static final String TAG = "PowerButtonWatcher";

    /** Off-then-on within this window counts as a double press. */
    private static final long WINDOW_MS = 1500;

    public interface Callback {
        void onDoublePress();

        /**
         * The screen went off - while ringing that means a SINGLE power press
         * ("silence it and pocket it"). A double press fires this first as
         * well, but silencing the ringer never blocks a later reject, so the
         * two do not clash.
         */
        void onScreenOff();
    }

    private final Callback callback;
    private long screenOffAt = 0;
    private boolean registered = false;

    public PowerButtonWatcher(Callback callback) {
        this.callback = callback;
    }

    public void register(Context ctx) {
        if (registered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        ctx.registerReceiver(this, f);
        registered = true;
    }

    public void unregister(Context ctx) {
        if (!registered) return;
        try {
            ctx.unregisterReceiver(this);
        } catch (Exception e) {
            Log.w(TAG, "the receiver was not registered", e);
        }
        registered = false;
        screenOffAt = 0;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            screenOffAt = System.currentTimeMillis();
            callback.onScreenOff();
        } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
            long gap = System.currentTimeMillis() - screenOffAt;
            if (screenOffAt > 0 && gap <= WINDOW_MS) {
                screenOffAt = 0;
                Log.d(TAG, "power key double press (" + gap + " ms)");
                callback.onDoublePress();
            }
            screenOffAt = 0;
        }
    }
}
