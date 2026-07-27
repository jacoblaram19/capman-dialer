package com.capman.dialer;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.util.Log;

/**
 * The service that receives calls while we are the default dialer. The system
 * binds to it and we bring up the incoming/outgoing call screen.
 *
 * Notifications live in {@link CallNotifier}: once the call screen is up it does
 * not drop a banner from the top, the card only sits in the shade.
 */
public class PhoneInCallService extends InCallService {

    private static final String TAG = "PhoneInCallService";

    private CallNotifier notifier;

    /**
     * Power key:
     *  - double press → reject the ringing call / hang up the active one
     *  - single press → silence the ringer (the screen goes off anyway, ready
     *    to slip back into a pocket)
     */
    private final PowerButtonWatcher powerWatcher = new PowerButtonWatcher(
            new PowerButtonWatcher.Callback() {
                @Override
                public void onDoublePress() {
                    if (Prefs.powerDouble(PhoneInCallService.this)) CallManager.hangupOrReject();
                }

                @Override
                public void onScreenOff() {
                    if (!Prefs.powerSilence(PhoneInCallService.this)) return;
                    if (CallManager.stateOf(CallManager.primary()) == Call.STATE_RINGING) {
                        CallManager.silenceRinger();
                    }
                }
            });

    private final CallManager.Listener stateListener = new CallManager.Listener() {
        @Override
        public void onCallsChanged() {
            if (notifier != null) notifier.update();
        }

        @Override
        public void onAudioChanged() {
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        notifier = new CallNotifier(this);
        notifier.attach();
        CallManager.addListener(stateListener);
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        CallManager.attachService(this);
        CallManager.addCall(call);
        powerWatcher.register(this);

        // Try to bring the screen up first: if that works the notification stays
        // on the quiet channel and no banner drops. Otherwise CallNotifier falls
        // back to a full-screen notification. In banner mode we do not open the
        // screen at all; the notification shows up as a strip instead.
        if (CallNotifier.shouldOpenScreen(this)) startInCallScreen();
        if (notifier != null) notifier.update();
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        CallManager.removeCall(call);
        if (!CallManager.hasCalls()) {
            if (notifier != null) notifier.clear();
            powerWatcher.unregister(this);
            CallManager.detachService();
        } else if (notifier != null) {
            notifier.update();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        CallManager.removeListener(stateListener);
        powerWatcher.unregister(this);
        if (notifier != null) {
            notifier.detach();
            notifier = null;
        }
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState audioState) {
        super.onCallAudioStateChanged(audioState);
        CallManager.notifyAudio();
    }

    @Override
    public void onSilenceRinger() {
        super.onSilenceRinger();
        Log.d(TAG, "the system silenced the ringer");
    }

    private void startInCallScreen() {
        try {
            Intent i = new Intent(this, InCallActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "could not open the call screen", e);
        }
    }
}
