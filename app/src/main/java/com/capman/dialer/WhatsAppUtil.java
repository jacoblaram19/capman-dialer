package com.capman.dialer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;
import android.widget.Toast;

/**
 * WhatsApp integration.
 *
 * LIMITATION (this matters for profile pictures): there is NO interface that
 * exposes WhatsApp profile photos to other apps.
 *
 * WhatsApp creates raw contacts under its own account ("com.whatsapp") for the
 * people in your address book - but those rows only carry the name, a "profile"
 * entry, voice/video call entries and the number; there is no photo row.
 * Verified on a device: not one of 655 WhatsApp raw contacts had a photo row.
 * Nor is there an exported avatar provider (only migration/media/file
 * providers, all permission-gated), and web endpoints such as wa.me will not
 * hand out the picture without a session.
 *
 * So: reading a number's WhatsApp profile picture from this app is not
 * possible. The lookup code below stays anyway - if the contacts provider ever
 * does hold such a photo it gets used, and otherwise nothing is shown.
 */
public final class WhatsAppUtil {

    private static final String TAG = "WhatsAppUtil";

    public static final String PKG = "com.whatsapp";
    /** WhatsApp Business. */
    public static final String PKG_BUSINESS = "com.whatsapp.w4b";

    private WhatsAppUtil() {
    }

    // ------------------------------------------------------------------ chat

    public static boolean isInstalled(Context ctx) {
        return installedPackage(ctx) != null;
    }

    private static String installedPackage(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String p : new String[]{PKG, PKG_BUSINESS}) {
            try {
                pm.getPackageInfo(p, 0);
                return p;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Opens the WhatsApp chat for a number. */
    public static void openChat(Context ctx, String number) {
        if (number == null || number.trim().isEmpty()) {
            Toast.makeText(ctx, "No number", Toast.LENGTH_SHORT).show();
            return;
        }
        // wa.me wants international form: the leading 0 has to become a country code
        String digits = PhoneUtil.digitsOnly(PhoneUtil.toE164(ctx, number));
        if (digits.isEmpty()) {
            Toast.makeText(ctx, "the number could not be converted for WhatsApp", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = Uri.parse("https://wa.me/" + digits);
        String pkg = installedPackage(ctx);
        if (pkg == null) {
            Toast.makeText(ctx, "WhatsApp is not installed", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "could not open WhatsApp", e);
            Toast.makeText(ctx, "Could not open WhatsApp", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ profile photo

    /**
     * A number's WhatsApp profile photo as JPEG bytes, or null if there is
     * none. Call it off the main thread - it runs a few contact queries.
     */
    public static byte[] photoBytes(ContentResolver cr, String number) {
        if (number == null || number.trim().isEmpty()) return null;
        long contactId = contactIdOf(cr, number);
        if (contactId <= 0) return null;

        for (long rawId : whatsAppRawContacts(cr, contactId)) {
            byte[] photo = photoOfRaw(cr, rawId);
            if (photo != null && photo.length > 0) return photo;
        }
        return null;
    }

    private static long contactIdOf(ContentResolver cr, String number) {
        Cursor c = null;
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number.trim()));
            c = cr.query(uri, new String[]{ContactsContract.PhoneLookup.CONTACT_ID},
                    null, null, null);
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) {
            Log.w(TAG, "contact not found", e);
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private static long[] whatsAppRawContacts(ContentResolver cr, long contactId) {
        Cursor c = null;
        try {
            c = cr.query(RawContacts.CONTENT_URI, new String[]{RawContacts._ID},
                    RawContacts.CONTACT_ID + "=? AND " + RawContacts.DELETED + "=0 AND "
                            + RawContacts.ACCOUNT_TYPE + " LIKE ?",
                    new String[]{String.valueOf(contactId), PKG + "%"}, null);
            if (c == null) return new long[0];
            long[] out = new long[c.getCount()];
            int i = 0;
            while (c.moveToNext()) out[i++] = c.getLong(0);
            return out;
        } catch (Exception e) {
            Log.w(TAG, "no WhatsApp raw contact found", e);
            return new long[0];
        } finally {
            if (c != null) c.close();
        }
    }

    private static byte[] photoOfRaw(ContentResolver cr, long rawId) {
        Cursor c = null;
        try {
            c = cr.query(Data.CONTENT_URI, new String[]{Photo.PHOTO},
                    Data.RAW_CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                    new String[]{String.valueOf(rawId), Photo.CONTENT_ITEM_TYPE}, null);
            if (c != null && c.moveToFirst()) return c.getBlob(0);
        } catch (Exception e) {
            Log.w(TAG, "could not read the WhatsApp photo", e);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }
}
