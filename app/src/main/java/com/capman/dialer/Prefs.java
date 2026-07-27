package com.capman.dialer;

import android.content.Context;
import android.content.SharedPreferences;

/** Application preferences. */
public final class Prefs {

    public static final String FILE = "dialer_prefs";

    public static final String THEME = "theme";                  // dark | light | system
    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_SYSTEM = "system";

    public static final String QUICK_REPLY = "quick_reply";
    public static final String POWER_DOUBLE = "power_double_press";
    public static final String VOLUME_SILENCE = "volume_key_silences";
    public static final String RECORD_SPEAKER = "speaker_while_recording";
    public static final String KEY_FEEDBACK = "key_feedback";
    public static final String PROXIMITY_OFF = "proximity_screen_off";
    public static final String EAR_ANSWER = "raise_to_ear_answers";
    public static final String FLIP_SILENCE = "flip_to_silence";
    public static final String POWER_SILENCE = "power_key_silences";
    /** Incoming call: true = full screen, false = a banner at the top of the screen. */
    public static final String INCOMING_FULLSCREEN = "incoming_full_screen";
    public static final String HISTORY_DAYS = "history_days";    // 0 = everything
    /** User-defined order of the favorites (contact ids, comma separated). */
    public static final String FAV_ORDER = "favorite_order";
    /** Has the first-run tour been shown? */
    public static final String ONBOARDING_SEEN = "onboarding_seen";

    private Prefs() {
    }

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String theme(Context ctx) {
        return get(ctx).getString(THEME, THEME_DARK);
    }

    public static String quickReply(Context ctx) {
        return get(ctx).getString(QUICK_REPLY, ctx.getString(R.string.quick_reply));
    }

    public static boolean powerDouble(Context ctx) {
        return get(ctx).getBoolean(POWER_DOUBLE, true);
    }

    public static boolean volumeSilence(Context ctx) {
        return get(ctx).getBoolean(VOLUME_SILENCE, true);
    }

    public static boolean recordSpeaker(Context ctx) {
        return get(ctx).getBoolean(RECORD_SPEAKER, true);
    }

    public static boolean keyFeedback(Context ctx) {
        return get(ctx).getBoolean(KEY_FEEDBACK, true);
    }

    /** Should the screen blank when the phone is held to the ear during a call? */
    public static boolean proximityOff(Context ctx) {
        return get(ctx).getBoolean(PROXIMITY_OFF, true);
    }

    /** Should raising the ringing phone to your ear answer the call? */
    public static boolean earAnswer(Context ctx) {
        return get(ctx).getBoolean(EAR_ANSWER, true);
    }

    /** Should turning the ringing phone face down silence it? */
    public static boolean flipSilence(Context ctx) {
        return get(ctx).getBoolean(FLIP_SILENCE, true);
    }

    /** With the screen on, should an incoming call take over the screen or show a banner? */
    public static boolean incomingFullScreen(Context ctx) {
        return get(ctx).getBoolean(INCOMING_FULLSCREEN, true);
    }

    /** Should a single press of the power key silence the ringer? */
    public static boolean powerSilence(Context ctx) {
        return get(ctx).getBoolean(POWER_SILENCE, true);
    }

    public static boolean onboardingSeen(Context ctx) {
        return get(ctx).getBoolean(ONBOARDING_SEEN, false);
    }

    public static void setOnboardingSeen(Context ctx, boolean seen) {
        get(ctx).edit().putBoolean(ONBOARDING_SEEN, seen).apply();
    }

    public static String favOrder(Context ctx) {
        return get(ctx).getString(FAV_ORDER, "");
    }

    public static void setFavOrder(Context ctx, String csv) {
        get(ctx).edit().putString(FAV_ORDER, csv == null ? "" : csv).apply();
    }

    /** Call history window, in days. 0 means no limit. */
    public static int historyDays(Context ctx) {
        return get(ctx).getInt(HISTORY_DAYS, 182);
    }
}
