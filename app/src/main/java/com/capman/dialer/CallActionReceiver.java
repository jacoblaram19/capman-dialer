package com.capman.dialer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Call buttons inside the notification shade (answer / reject / hang up). */
public class CallActionReceiver extends BroadcastReceiver {

    public static final String ACTION_ANSWER = "com.capman.dialer.ANSWER_CALL";
    public static final String ACTION_REJECT = "com.capman.dialer.REJECT_CALL";
    public static final String ACTION_HANGUP = "com.capman.dialer.HANGUP_CALL";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        switch (intent.getAction()) {
            case ACTION_ANSWER:
                CallManager.answer();
                break;
            case ACTION_REJECT:
            case ACTION_HANGUP:
                CallManager.hangupOrReject();
                break;
            default:
                break;
        }
    }
}
