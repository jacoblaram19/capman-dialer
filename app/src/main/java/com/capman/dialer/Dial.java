package com.capman.dialer;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.telecom.TelecomManager;
import android.util.Log;
import android.widget.Toast;

/** Placing calls: through Telecom when we are the default dialer, otherwise via an intent. */
public final class Dial {

    private static final String TAG = "Dial";

    private Dial() {
    }

    public static void call(Context ctx, String number) {
        if (number == null || number.trim().isEmpty()) return;
        Uri uri = Uri.fromParts("tel", number.trim(), null);

        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(ctx, "Call permission not granted", Toast.LENGTH_SHORT).show();
            openDialer(ctx, uri);
            return;
        }
        try {
            TelecomManager tm = ctx.getSystemService(TelecomManager.class);
            tm.placeCall(uri, null);
        } catch (Exception e) {
            Log.w(TAG, "placeCall failed, falling back to an intent", e);
            try {
                Intent i = new Intent(Intent.ACTION_CALL, uri);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e2) {
                Log.e(TAG, "could not start the call", e2);
                Toast.makeText(ctx, "Could not start the call", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static void openDialer(Context ctx, Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_DIAL, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
