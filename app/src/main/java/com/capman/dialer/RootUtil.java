package com.capman.dialer;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.File;

/**
 * Works out whether call recording can actually do anything on this device.
 *
 * An ordinary app records nothing but silence from the microphone during a call
 * (measured, not assumed). The only exception is a privileged system app holding
 * {@code CAPTURE_AUDIO_OUTPUT}, which needs root. Rather than leave a button
 * that cannot work sitting on the call screen, the record button is hidden
 * accordingly.
 *
 * The root check is deliberately quiet: we never execute {@code su}, because on
 * Magisk that pops a grant dialog - the last thing anyone wants while the phone
 * is ringing.
 */
public final class RootUtil {

    private static final String PERM_CAPTURE = "android.permission.CAPTURE_AUDIO_OUTPUT";

    private static final String[] SU_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
    };

    private static Boolean cached;

    private RootUtil() {
    }

    /** Does call recording make any sense on this device? */
    public static boolean callRecordingPossible(Context ctx) {
        if (cached != null) return cached;
        cached = hasCapturePermission(ctx) || hasSuBinary();
        return cached;
    }

    /** If the permission really is granted, recording is guaranteed to work. */
    private static boolean hasCapturePermission(Context ctx) {
        try {
            return ctx.checkSelfPermission(PERM_CAPTURE) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasSuBinary() {
        for (String p : SU_PATHS) {
            try {
                if (new File(p).exists()) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
