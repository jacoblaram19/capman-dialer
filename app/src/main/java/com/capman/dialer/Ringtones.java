package com.capman.dialer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The sound list behind the per-contact ringtone picker.
 *
 * It gathers the phone's own ringtones and the user's music into one list. The
 * detail that matters: Telecom plays the ringtone, so the SYSTEM has to be able
 * to read the chosen uri, not us. That rules out using a file picked from a
 * document picker directly (a SAF uri is only opened to this app); it is copied
 * into the MediaStore Ringtones folder first and that uri is used instead.
 */
public final class Ringtones {

    private static final String TAG = "Ringtones";

    /** Very long lists make choosing harder. */
    private static final int MUSIC_LIMIT = 300;

    public static class Item {
        public final String title;
        /** null means the default ringtone. */
        public final String uri;

        Item(String title, String uri) {
            this.title = title;
            this.uri = uri;
        }
    }

    private Ringtones() {
    }

    /**
     * The selectable sounds: the phone's ringtones first, then the user's
     * music. Call it off the main thread.
     */
    public static List<Item> list(Context ctx) {
        List<Item> out = new ArrayList<>();
        addSystemRingtones(ctx, out);
        addMusic(ctx, out);
        return out;
    }

    private static void addSystemRingtones(Context ctx, List<Item> out) {
        try {
            RingtoneManager rm = new RingtoneManager(ctx);
            rm.setType(RingtoneManager.TYPE_RINGTONE);
            Cursor c = rm.getCursor();
            if (c == null) return;
            for (int i = 0; i < c.getCount(); i++) {
                c.moveToPosition(i);
                String title = c.getString(RingtoneManager.TITLE_COLUMN_INDEX);
                Uri uri = rm.getRingtoneUri(i);
                if (uri != null) out.add(new Item("🔔  " + title, uri.toString()));
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read the ringtones", e);
        }
    }

    private static void addMusic(Context ctx, List<Item> out) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE},
                    MediaStore.Audio.Media.IS_MUSIC + "!=0", null,
                    MediaStore.Audio.Media.TITLE + " ASC LIMIT " + MUSIC_LIMIT);
            if (c == null) return;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String title = c.getString(1);
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                out.add(new Item("🎵  " + (title == null ? uri.getLastPathSegment() : title),
                        uri.toString()));
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read the music, the permission may be missing", e);
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Copies a sound file picked from elsewhere into the Ringtones folder.
     *
     * Necessary because only this app can read a uri handed over by the
     * document picker; the system that will actually play the ringtone cannot.
     * The MediaStore uri we copy to is readable by everyone.
     *
     * @return the new uri, or null on failure
     */
    public static String importAudio(Context ctx, Uri source) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            String name = displayName(cr, source);

            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Audio.Media.MIME_TYPE, mimeOf(cr, source));
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES);
            v.put(MediaStore.Audio.Media.IS_RINGTONE, 1);
            v.put(MediaStore.Audio.Media.IS_MUSIC, 0);
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);

            Uri dest = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (dest == null) return null;

            try (InputStream in = cr.openInputStream(source);
                 OutputStream os = cr.openOutputStream(dest)) {
                if (in == null || os == null) return null;
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            v.clear();
            v.put(MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(dest, v, null, null);
            return dest.toString();
        } catch (Exception e) {
            Log.e(TAG, "could not copy the sound file", e);
            return null;
        }
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.trim().isEmpty()) return n;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return "zil_sesi_" + System.currentTimeMillis();
    }

    private static String mimeOf(ContentResolver cr, Uri uri) {
        String m = cr.getType(uri);
        return (m == null || !m.startsWith("audio/")) ? "audio/mpeg" : m;
    }

    /** The display name for a stored uri. */
    public static String titleOf(Context ctx, String uriString) {
        if (uriString == null || uriString.trim().isEmpty()) return "Default ringtone";
        try {
            Uri uri = Uri.parse(uriString);
            android.media.Ringtone r = RingtoneManager.getRingtone(ctx, uri);
            if (r != null) {
                String t = r.getTitle(ctx);
                if (t != null && !t.trim().isEmpty()) return t;
            }
        } catch (Exception ignored) {
        }
        return "Custom ringtone";
    }
}
