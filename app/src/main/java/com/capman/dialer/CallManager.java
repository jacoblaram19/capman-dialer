package com.capman.dialer;

import android.content.Context;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.telecom.VideoProfile;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single point of control for the active calls: the bridge between the
 * InCallService and the UI (InCallActivity).
 */
public final class CallManager {

    private static final String TAG = "CallManager";

    public interface Listener {
        void onCallsChanged();

        void onAudioChanged();
    }

    private static final List<Call> calls = new CopyOnWriteArrayList<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static InCallService service;

    private CallManager() {
    }

    private static final Call.Callback callback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            notifyCalls();
        }

        @Override
        public void onDetailsChanged(Call call, Call.Details details) {
            notifyCalls();
        }
    };

    // ------------------------------------------------------------------ service side

    static void attachService(InCallService s) {
        service = s;
    }

    static void detachService() {
        service = null;
    }

    static void addCall(Call call) {
        if (!calls.contains(call)) {
            call.registerCallback(callback);
            calls.add(call);
        }
        notifyCalls();
    }

    static void removeCall(Call call) {
        call.unregisterCallback(callback);
        calls.remove(call);
        notifyCalls();
    }

    static void notifyAudio() {
        for (Listener l : listeners) l.onAudioChanged();
    }

    private static void notifyCalls() {
        for (Listener l : listeners) l.onCallsChanged();
    }

    // ------------------------------------------------------------------ UI side

    public static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    public static List<Call> all() {
        return new ArrayList<>(calls);
    }

    public static boolean hasCalls() {
        return !calls.isEmpty();
    }

    /**
     * The call to show on screen: ringing first, then active, then holding.
     *
     * Disconnected calls are pushed to the back, so when a second call comes in
     * during a conversation and one of them ends, the screen moves to the call
     * that is still alive instead of getting stuck on the dead one.
     */
    public static Call primary() {
        Call ringing = null, active = null, holding = null, other = null, dead = null;
        for (Call c : calls) {
            int s = stateOf(c);
            if (s == Call.STATE_DISCONNECTED || s == Call.STATE_DISCONNECTING) {
                if (dead == null) dead = c;
            } else if (s == Call.STATE_RINGING) {
                if (ringing == null) ringing = c;
            } else if (s == Call.STATE_ACTIVE) {
                if (active == null) active = c;
            } else if (s == Call.STATE_HOLDING) {
                if (holding == null) holding = c;
            } else if (other == null) {
                other = c;
            }
        }
        if (ringing != null) return ringing;
        if (active != null) return active;
        if (other != null) return other;
        if (holding != null) return holding;
        return dead;
    }

    /** The second call, the one not on screen (waiting or running in the background). */
    public static Call secondary() {
        Call p = primary();
        for (Call c : calls) {
            if (c == p) continue;
            int s = stateOf(c);
            if (s == Call.STATE_DISCONNECTED || s == Call.STATE_DISCONNECTING) continue;
            return c;
        }
        return null;
    }

    /** How many calls have not been disconnected. */
    public static int liveCount() {
        int n = 0;
        for (Call c : calls) {
            int s = stateOf(c);
            if (s != Call.STATE_DISCONNECTED && s != Call.STATE_DISCONNECTING) n++;
        }
        return n;
    }

    /** Switches between two calls: holds one and resumes the other. */
    public static void swap() {
        Call p = primary();
        Call s = secondary();
        if (p == null || s == null) return;
        if (stateOf(p) == Call.STATE_ACTIVE) {
            p.hold();
            s.unhold();
        } else if (stateOf(s) == Call.STATE_ACTIVE) {
            s.hold();
            p.unhold();
        } else {
            p.unhold();
        }
    }

    public static int stateOf(Call c) {
        if (c == null) return Call.STATE_DISCONNECTED;
        return c.getDetails().getState();
    }

    public static String numberOf(Call c) {
        if (c == null) return null;
        android.net.Uri handle = c.getDetails().getHandle();
        if (handle == null) return null;
        return handle.getSchemeSpecificPart();
    }

    // ------------------------------------------------------------------ actions

    /**
     * Answers the ringing call. Any other running call is put on hold first -
     * without that, some devices simply drop the call.
     */
    public static void answer() {
        Call c = primary();
        if (c == null || stateOf(c) != Call.STATE_RINGING) return;
        for (Call other : calls) {
            if (other == c) continue;
            if (stateOf(other) == Call.STATE_ACTIVE
                    && other.getDetails().can(Call.Details.CAPABILITY_HOLD)) {
                other.hold();
            }
        }
        c.answer(VideoProfile.STATE_AUDIO_ONLY);
    }

    /** Ends the running call and answers the ringing one. */
    public static void endCurrentAndAnswer() {
        Call ringing = primary();
        if (ringing == null || stateOf(ringing) != Call.STATE_RINGING) return;
        for (Call other : calls) {
            if (other == ringing) continue;
            int s = stateOf(other);
            if (s != Call.STATE_DISCONNECTED && s != Call.STATE_DISCONNECTING) {
                other.disconnect();
            }
        }
        ringing.answer(VideoProfile.STATE_AUDIO_ONLY);
    }

    /** Rejects a ringing call, hangs up an active one. */
    public static void hangupOrReject() {
        Call c = primary();
        if (c == null) return;
        if (stateOf(c) == Call.STATE_RINGING) c.reject(false, null);
        else c.disconnect();
    }

    /** Reject and send the canned reply as an SMS. */
    public static void rejectWithMessage(Context ctx, String message) {
        Call c = primary();
        if (c == null) return;
        String number = numberOf(c);
        c.reject(false, null);
        if (number == null || number.isEmpty() || message == null) return;
        try {
            SmsManager sms = ctx.getSystemService(SmsManager.class);
            if (sms == null) sms = SmsManager.getDefault();
            sms.sendTextMessage(number, null, message, null, null);
        } catch (Exception e) {
            Log.e(TAG, "could not send the quick reply", e);
        }
    }

    /** Silence the ringer without rejecting the call (volume key). */
    public static void silenceRinger() {
        try {
            if (service != null) {
                android.telecom.TelecomManager tm =
                        service.getSystemService(android.telecom.TelecomManager.class);
                if (tm != null) tm.silenceRinger();
            }
        } catch (Exception e) {
            Log.e(TAG, "could not silence the ringer", e);
        }
    }

    public static void setMuted(boolean muted) {
        if (service != null) service.setMuted(muted);
    }

    public static boolean isMuted() {
        CallAudioState s = audioState();
        return s != null && s.isMuted();
    }

    public static void setSpeaker(boolean on) {
        if (service == null) return;
        service.setAudioRoute(on ? CallAudioState.ROUTE_SPEAKER
                : CallAudioState.ROUTE_WIRED_OR_EARPIECE);
    }

    public static boolean isSpeakerOn() {
        CallAudioState s = audioState();
        return s != null && s.getRoute() == CallAudioState.ROUTE_SPEAKER;
    }

    private static CallAudioState audioState() {
        try {
            return service != null ? service.getCallAudioState() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void setHold(boolean hold) {
        Call c = primary();
        if (c == null) return;
        if (hold) c.hold();
        else c.unhold();
    }

    public static boolean isOnHold() {
        return stateOf(primary()) == Call.STATE_HOLDING;
    }

    /** Cutting the tone immediately made some IVR menus miss the keypress. */
    private static final long DTMF_MS = 160;
    private static final android.os.Handler dtmfHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    public static void playDtmf(char digit) {
        Call c = primary();
        if (c == null) return;
        c.playDtmfTone(digit);
        dtmfHandler.removeCallbacksAndMessages(null);
        dtmfHandler.postDelayed(() -> {
            try {
                c.stopDtmfTone();
            } catch (Exception ignored) {
            }
        }, DTMF_MS);
    }
}
