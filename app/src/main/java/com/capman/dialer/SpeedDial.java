package com.capman.dialer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The numbers dialled by long-pressing 1-9 on the keypad.
 *
 * Only the NUMBER is stored, not a contact id: speed dial keeps working even if
 * the contact is deleted or the address book moves to another account. The name
 * shown is looked up from the number every time, so renaming a contact updates
 * the speed dial by itself.
 *
 * 0 is not used - a long press there already types "+".
 */
public final class SpeedDial {

    private static final String PREFIX = "speed_dial_";

    /** The usable keys. */
    public static final char[] KEYS = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};

    private SpeedDial() {
    }

    public static boolean isSlot(char digit) {
        return digit >= '1' && digit <= '9';
    }

    /** @return the assigned number, or null for an empty slot */
    public static String number(Context ctx, char digit) {
        if (!isSlot(digit)) return null;
        String n = prefs(ctx).getString(PREFIX + digit, "");
        return n == null || n.isEmpty() ? null : n;
    }

    public static void set(Context ctx, char digit, String number) {
        if (!isSlot(digit)) return;
        prefs(ctx).edit().putString(PREFIX + digit, number == null ? "" : number).apply();
    }

    public static void clear(Context ctx, char digit) {
        set(ctx, digit, null);
    }

    /** How many keys are assigned; shown as a summary in settings. */
    public static int count(Context ctx) {
        int n = 0;
        for (char c : KEYS) {
            if (number(ctx, c) != null) n++;
        }
        return n;
    }

    private static SharedPreferences prefs(Context ctx) {
        return Prefs.get(ctx);
    }
}
