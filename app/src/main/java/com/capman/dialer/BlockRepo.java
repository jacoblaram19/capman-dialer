package com.capman.dialer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BlockedNumberContract;
import android.provider.BlockedNumberContract.BlockedNumbers;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Blocked numbers.
 *
 * We keep no list of our own and use Android's system-wide blocked number list
 * instead. That way the system drops a blocked call before it ever reaches us
 * (the phone stays quiet, no notification) and the list is shared with other
 * apps.
 *
 * Only the default phone/SMS app may write to it, which is fine while we are
 * the default dialer.
 */
public final class BlockRepo {

    private static final String TAG = "BlockRepo";

    private BlockRepo() {
    }

    public static class Entry {
        public final long id;
        public final String number;

        Entry(long id, String number) {
            this.id = id;
            this.number = number;
        }
    }

    /** May this user block numbers at all? A work profile, for instance, may not. */
    public static boolean canBlock(Context ctx) {
        try {
            return BlockedNumberContract.canCurrentUserBlockNumbers(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isBlocked(Context ctx, String number) {
        if (number == null || number.trim().isEmpty()) return false;
        try {
            return BlockedNumberContract.isBlocked(ctx, number.trim());
        } catch (Exception e) {
            // isBlocked may only be called from the default phone/SMS app
            Log.w(TAG, "could not read the blocked state", e);
            return false;
        }
    }

    /** @return whether the operation succeeded */
    public static boolean block(Context ctx, String number) {
        if (number == null || number.trim().isEmpty()) return false;
        try {
            ContentValues v = new ContentValues();
            v.put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number.trim());
            Uri uri = ctx.getContentResolver().insert(BlockedNumbers.CONTENT_URI, v);
            return uri != null;
        } catch (Exception e) {
            Log.e(TAG, "could not block the number", e);
            return false;
        }
    }

    public static boolean unblock(Context ctx, String number) {
        if (number == null || number.trim().isEmpty()) return false;
        try {
            return BlockedNumberContract.unblock(ctx, number.trim()) > 0;
        } catch (Exception e) {
            Log.e(TAG, "could not unblock the number", e);
            return false;
        }
    }

    public static List<Entry> list(Context ctx) {
        List<Entry> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(BlockedNumbers.CONTENT_URI,
                    new String[]{BlockedNumbers.COLUMN_ID, BlockedNumbers.COLUMN_ORIGINAL_NUMBER},
                    null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String n = c.getString(1);
                    out.add(new Entry(c.getLong(0), n == null ? "" : n));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "could not read the blocked list", e);
        } finally {
            if (c != null) c.close();
        }
        return out;
    }
}
