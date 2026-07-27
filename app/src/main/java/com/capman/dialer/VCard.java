package com.capman.dialer;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Exporting and importing the address book as .vcf (vCard).
 *
 * On export we use the contacts provider's own vCard generator
 * ({@code Contacts.CONTENT_VCARD_URI}), so fields we never read ourselves -
 * photos, e-mail, addresses - end up in the backup too. It is a full backup.
 *
 * On import our own parser runs, handling folded lines, quoted-printable
 * encoding and vCard 2.1/3.0/4.0 fields.
 */
public final class VCard {

    private static final String TAG = "VCard";

    public interface Progress {
        void onProgress(int done, int total);
    }

    private VCard() {
    }

    // ------------------------------------------------------------------ export

    /**
     * Writes every contact into a single .vcf stream.
     *
     * @return how many contacts were written
     */
    public static int export(ContentResolver cr, List<Contact> contacts,
                             OutputStream out, Progress progress) throws Exception {
        int written = 0;
        for (int i = 0; i < contacts.size(); i++) {
            Contact c = contacts.get(i);
            if (c.lookupKey == null) continue;
            byte[] card = readVCard(cr, c.lookupKey);
            if (card != null && card.length > 0) {
                out.write(card);
                if (card[card.length - 1] != '\n') out.write('\n');
                written++;
            }
            if (progress != null && (i % 10 == 0 || i == contacts.size() - 1)) {
                progress.onProgress(i + 1, contacts.size());
            }
        }
        out.flush();
        return written;
    }

    private static byte[] readVCard(ContentResolver cr, String lookupKey) {
        Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey);
        AssetFileDescriptor fd = null;
        try {
            fd = cr.openAssetFileDescriptor(uri, "r");
            if (fd == null) return null;
            try (InputStream in = fd.createInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read the vCard: " + lookupKey, e);
            return null;
        } finally {
            try {
                if (fd != null) fd.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ import

    /** A single parsed card. */
    public static class Entry {
        public String name;
        public final List<String> numbers = new ArrayList<>();
    }

    /** Parses .vcf content. Only names and phone numbers are taken. */
    public static List<Entry> parse(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);

        List<Entry> out = new ArrayList<>();
        Entry current = null;
        String structuredName = null;

        for (String line : unfold(text)) {
            String upper = line.toUpperCase();
            if (upper.startsWith("BEGIN:VCARD")) {
                current = new Entry();
                structuredName = null;
                continue;
            }
            if (upper.startsWith("END:VCARD")) {
                if (current != null) {
                    if (current.name == null) current.name = structuredName;
                    if (current.name != null || !current.numbers.isEmpty()) out.add(current);
                }
                current = null;
                continue;
            }
            if (current == null) continue;

            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String rawKey = line.substring(0, colon);
            String value = line.substring(colon + 1);
            String key = rawKey.toUpperCase();

            value = decodeValue(rawKey, value);

            if (key.startsWith("FN")) {
                if (!value.trim().isEmpty()) current.name = value.trim();
            } else if (key.startsWith("N;") || key.equals("N")) {
                // N: Family;Given;Middle;Prefix;Suffix
                String[] parts = value.split(";", -1);
                StringBuilder sb = new StringBuilder();
                if (parts.length > 1 && !parts[1].trim().isEmpty()) sb.append(parts[1].trim());
                if (parts.length > 0 && !parts[0].trim().isEmpty()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(parts[0].trim());
                }
                if (sb.length() > 0) structuredName = sb.toString();
            } else if (key.startsWith("TEL")) {
                String num = value.trim();
                if (!num.isEmpty() && !containsNumber(current, num)) current.numbers.add(num);
            }
        }
        return out;
    }

    private static boolean containsNumber(Entry e, String num) {
        for (String n : e.numbers) {
            if (PhoneUtil.sameNumber(n, num)) return true;
        }
        return false;
    }

    /** Joins folded lines, the ones continued with a space or tab. */
    private static List<String> unfold(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean qpContinues = false;

        for (String raw : text.split("\r\n|\n|\r", -1)) {
            if (qpContinues) {
                cur.append(raw);
                qpContinues = cur.toString().endsWith("=");
                if (qpContinues) cur.setLength(cur.length() - 1);
                continue;
            }
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
                cur.append(raw.substring(1));   // katlanmis devam satiri
                continue;
            }
            if (cur.length() > 0) out.add(cur.toString());
            cur.setLength(0);
            cur.append(raw);
            // a quoted-printable line continues with a trailing "="
            if (cur.toString().toUpperCase().contains("QUOTED-PRINTABLE")
                    && cur.toString().endsWith("=")) {
                cur.setLength(cur.length() - 1);
                qpContinues = true;
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Decodes QUOTED-PRINTABLE and charset parameters. */
    private static String decodeValue(String key, String value) {
        String k = key.toUpperCase();
        if (!k.contains("QUOTED-PRINTABLE")) return value;

        String charset = "UTF-8";
        int csIdx = k.indexOf("CHARSET=");
        if (csIdx >= 0) {
            String tail = key.substring(csIdx + 8);
            int semi = tail.indexOf(';');
            charset = semi >= 0 ? tail.substring(0, semi) : tail;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '=' && i + 2 < value.length()) {
                    bos.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                    i += 2;
                } else {
                    bos.write(c);
                }
            }
            return new String(bos.toByteArray(), charset);
        } catch (Exception e) {
            return value;
        }
    }

    /** Writes the parsed cards into the address book. */
    public static int importEntries(ContentResolver cr, List<Entry> entries,
                                    ContactAccount account, Progress progress) {
        int added = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.numbers.isEmpty() && (e.name == null || e.name.trim().isEmpty())) continue;
            if (ContactsRepo.insert(cr, e.name, e.numbers, account) > 0) added++;
            if (progress != null && (i % 5 == 0 || i == entries.size() - 1)) {
                progress.onProgress(i + 1, entries.size());
            }
        }
        return added;
    }
}
