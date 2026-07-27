package com.capman.dialer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Short per-contact notes ("gave the address", "calling Tuesday at 3", "painter").
 *
 * A note is attached to the NUMBER rather than the contact, so numbers that are
 * not in the address book can carry notes too, and deleting a contact does not
 * lose the note. The key is the last 9 digits of the number (see PhoneUtil.key),
 * which makes the +90 / 0 prefix difference irrelevant.
 */
public final class Notes {

    private static final String FILE = "dialer_notes";

    private Notes() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String keyOf(String number) {
        String k = PhoneUtil.key(number);
        return k.isEmpty() ? null : "n_" + k;
    }

    /** The note for a number, or an empty string. */
    public static String get(Context ctx, String number) {
        String k = keyOf(number);
        if (k == null) return "";
        return prefs(ctx).getString(k, "");
    }

    public static boolean has(Context ctx, String number) {
        return !get(ctx, number).trim().isEmpty();
    }

    /** Passing empty text deletes the note. */
    public static void set(Context ctx, String number, String note) {
        String k = keyOf(number);
        if (k == null) return;
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (note == null || note.trim().isEmpty()) e.remove(k);
        else e.putString(k, note.trim());
        e.apply();
    }
}
