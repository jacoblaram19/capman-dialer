package com.capman.dialer;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The contacts read/write layer.
 *
 * Adding and editing contacts is done directly through ContactsContract rather
 * than handed off to another app, so the dialer keeps working on devices where
 * no stock contacts app is installed.
 */
public final class ContactsRepo {

    /** Map key separator: a character no name or account type can contain. */
    private static final String SEP = String.valueOf((char) 0);

    private static final String TAG = "ContactsRepo";

    /** Accounts whose sync adapter is read-only; never try to edit those. */
    private static final String ACC_WHATSAPP = "com.whatsapp";
    private static final String ACC_TELEGRAM = "org.telegram.messenger";

    private ContactsRepo() {
    }

    // ------------------------------------------------------------------ reading

    /** Every contact that has a number, sorted by name, with their numbers merged. */
    public static List<Contact> loadAll(ContentResolver cr) {
        String[] proj = {
                Phone.CONTACT_ID,
                Phone.LOOKUP_KEY,
                Phone.DISPLAY_NAME_PRIMARY,
                Phone.NUMBER,
                Phone.TYPE,
                Phone.LABEL,
                Phone.PHOTO_THUMBNAIL_URI,
                Phone.PHOTO_URI,
                Phone.STARRED,
        };
        Map<Long, Contact> byId = new LinkedHashMap<>();
        Cursor c = null;
        try {
            c = cr.query(Phone.CONTENT_URI, proj, null, null, null);
            if (c == null) return new ArrayList<>();
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String number = c.getString(3);
                if (number == null || number.trim().isEmpty()) continue;

                Contact ct = byId.get(id);
                if (ct == null) {
                    ct = new Contact();
                    ct.id = id;
                    ct.lookupKey = c.getString(1);
                    ct.name = c.getString(2);
                    ct.photoThumbUri = c.getString(6);
                    ct.photoUri = c.getString(7);
                    ct.starred = c.getInt(8) != 0;
                    byId.put(id, ct);
                }
                // The same number may arrive from several accounts (Google +
                // WhatsApp); drop the duplicates.
                boolean dup = false;
                for (String existing : ct.numbers) {
                    if (PhoneUtil.sameNumber(existing, number)) {
                        dup = true;
                        break;
                    }
                }
                if (dup) continue;

                ct.numbers.add(number.trim());
                ct.labels.add(labelOf(c.getInt(4), c.getString(5)));
            }
        } catch (Exception e) {
            Log.e(TAG, "could not read the contacts", e);
        } finally {
            if (c != null) c.close();
        }
        // A-Z first, and anything not starting with a letter at the end
        List<Contact> out = new ArrayList<>(byId.values());
        Collections.sort(out, PhoneUtil.contactOrder());
        return out;
    }

    /** A fast number-to-name map, keyed by the last 9 digits. */
    public static Map<String, String> nameIndex(List<Contact> contacts) {
        Map<String, String> map = new HashMap<>();
        for (Contact ct : contacts) {
            if (ct.name == null || ct.name.trim().isEmpty()) continue;
            for (String n : ct.numbers) {
                String k = PhoneUtil.key(n);
                if (!k.isEmpty()) map.put(k, ct.name);
            }
        }
        return map;
    }

    /** A number-to-photo map, keyed by the last 9 digits, holding thumbnail uris. */
    public static Map<String, String> photoIndex(List<Contact> contacts) {
        Map<String, String> map = new HashMap<>();
        for (Contact ct : contacts) {
            String photo = ct.bestPhoto(false);
            if (photo == null) continue;
            for (String n : ct.numbers) {
                String k = PhoneUtil.key(n);
                if (!k.isEmpty()) map.put(k, photo);
            }
        }
        return map;
    }

    /**
     * Finds a contact from a number, for the call screen. It uses the
     * provider's own number matching, so country-code and leading-zero
     * differences do not matter.
     */
    public static Contact lookupByNumber(ContentResolver cr, String number) {
        if (number == null || number.trim().isEmpty()) return null;
        Uri uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number.trim()));
        String[] proj = {
                ContactsContract.PhoneLookup.CONTACT_ID,
                ContactsContract.PhoneLookup.LOOKUP_KEY,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
                ContactsContract.PhoneLookup.PHOTO_URI,
        };
        Cursor c = null;
        try {
            c = cr.query(uri, proj, null, null, null);
            if (c != null && c.moveToFirst()) {
                Contact ct = new Contact();
                ct.id = c.getLong(0);
                ct.lookupKey = c.getString(1);
                ct.name = c.getString(2);
                ct.photoThumbUri = c.getString(3);
                ct.photoUri = c.getString(4);
                ct.numbers.add(number);
                ct.labels.add("Phone");
                return ct;
            }
        } catch (Exception e) {
            Log.e(TAG, "no contact found for that number", e);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** A number-to-contact-id map, keyed by the last 9 digits. */
    public static Map<String, Long> idIndex(List<Contact> contacts) {
        Map<String, Long> map = new HashMap<>();
        for (Contact ct : contacts) {
            for (String n : ct.numbers) {
                String k = PhoneUtil.key(n);
                if (!k.isEmpty()) map.put(k, ct.id);
            }
        }
        return map;
    }

    /** Loads a single contact by id, or null if there is none. */
    public static Contact load(ContentResolver cr, long contactId) {
        String[] proj = {
                Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME_PRIMARY,
                Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.PHOTO_THUMBNAIL_URI,
                Phone.PHOTO_URI, Phone.STARRED,
        };
        Contact ct = null;
        Cursor c = null;
        try {
            c = cr.query(Phone.CONTENT_URI, proj, Phone.CONTACT_ID + "=?",
                    new String[]{String.valueOf(contactId)}, null);
            if (c == null) return null;
            while (c.moveToNext()) {
                if (ct == null) {
                    ct = new Contact();
                    ct.id = c.getLong(0);
                    ct.lookupKey = c.getString(1);
                    ct.name = c.getString(2);
                    ct.photoThumbUri = c.getString(6);
                    ct.photoUri = c.getString(7);
                    ct.starred = c.getInt(8) != 0;
                }
                String number = c.getString(3);
                if (number == null || number.trim().isEmpty()) continue;
                boolean dup = false;
                for (String existing : ct.numbers) {
                    if (PhoneUtil.sameNumber(existing, number)) {
                        dup = true;
                        break;
                    }
                }
                if (dup) continue;
                ct.numbers.add(number.trim());
                ct.labels.add(labelOf(c.getInt(4), c.getString(5)));
            }
        } catch (Exception e) {
            Log.e(TAG, "could not read the contact: " + contactId, e);
        } finally {
            if (c != null) c.close();
        }
        return ct;
    }

    private static String labelOf(int type, String custom) {
        switch (type) {
            case Phone.TYPE_MOBILE: return "Mobile";
            case Phone.TYPE_HOME: return "Home";
            case Phone.TYPE_WORK: return "Work";
            case Phone.TYPE_MAIN: return "Main";
            case Phone.TYPE_FAX_WORK:
            case Phone.TYPE_FAX_HOME: return "Fax";
            case Phone.TYPE_OTHER: return "Other";
            case Phone.TYPE_CUSTOM: return custom != null ? custom : "Other";
            default: return "Phone";
        }
    }

    // ------------------------------------------------------------------ writing

    /**
     * Saves a new contact, picking the destination itself: the writable
     * account that already holds most contacts, or device storage.
     *
     * @return the new contact's id, or -1 on failure
     */
    public static long insert(ContentResolver cr, String name, List<String> numbers) {
        String[] acc = preferredAccount(cr);
        return insert(cr, name, numbers, new ContactAccount(acc[0], acc[1], "", false));
    }

    public static long insert(ContentResolver cr, String name, List<String> numbers,
                              ContactAccount account) {
        return insert(cr, name, numbers, account, null);
    }

    /** Saves a new contact into the given account: device storage, SIM or Google. */
    public static long insert(ContentResolver cr, String name, List<String> numbers,
                              ContactAccount account, byte[] photoJpeg) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, account == null ? null : account.type)
                .withValue(RawContacts.ACCOUNT_NAME, account == null ? null : account.name)
                .build());
        addNameOp(ops, name);
        addNumberOps(ops, numbers);
        if (photoJpeg != null && photoJpeg.length > 0) {
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoJpeg)
                    .build());
        }
        try {
            ContentProviderResult[] res = cr.applyBatch(ContactsContract.AUTHORITY, ops);
            if (res.length == 0 || res[0].uri == null) return -1;
            long rawId = ContentUris.parseId(res[0].uri);
            return contactIdOfRaw(cr, rawId);
        } catch (Exception e) {
            Log.e(TAG, "could not add the contact", e);
            return -1;
        }
    }

    /**
     * Updates an existing contact. It picks an editable raw contact; if the
     * person only exists in read-only accounts such as WhatsApp, a new raw
     * contact is created in device storage and linked to the same person.
     */
    public static boolean update(ContentResolver cr, long contactId, String name, List<String> numbers) {
        long rawId = editableRawContactId(cr, contactId);
        try {
            if (rawId <= 0) {
                return attachNewRawContact(cr, contactId, name, numbers);
            }
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();

            // Name: update it if present, insert it otherwise
            if (hasNameRow(cr, rawId)) {
                ops.add(ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                        .withSelection(Data.RAW_CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                                new String[]{String.valueOf(rawId), StructuredName.CONTENT_ITEM_TYPE})
                        .withValue(StructuredName.DISPLAY_NAME, name == null ? "" : name.trim())
                        .build());
            } else if (name != null && !name.trim().isEmpty()) {
                ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValue(Data.RAW_CONTACT_ID, rawId)
                        .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(StructuredName.DISPLAY_NAME, name.trim())
                        .build());
            }

            // Numbers: delete the old ones and write the new ones
            ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
                    .withSelection(Data.RAW_CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                            new String[]{String.valueOf(rawId), Phone.CONTENT_ITEM_TYPE})
                    .build());
            for (String n : numbers) {
                if (n == null || n.trim().isEmpty()) continue;
                ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValue(Data.RAW_CONTACT_ID, rawId)
                        .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                        .withValue(Phone.NUMBER, n.trim())
                        .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                        .build());
            }
            cr.applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "could not update the contact: " + contactId, e);
            return false;
        }
    }

    /** Changes a contact's photo, writing to an editable raw contact. */
    public static boolean setPhoto(ContentResolver cr, long contactId, byte[] jpeg) {
        long rawId = editableRawContactId(cr, contactId);
        if (rawId <= 0) rawId = anyRawContactId(cr, contactId);
        if (rawId <= 0) return false;
        try {
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
                    .withSelection(Data.RAW_CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                            new String[]{String.valueOf(rawId),
                                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE})
                    .build());
            if (jpeg != null && jpeg.length > 0) {
                ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValue(Data.RAW_CONTACT_ID, rawId)
                        .withValue(Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, jpeg)
                        .build());
            }
            cr.applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "could not change the photo: " + contactId, e);
            return false;
        }
    }

    public static boolean delete(ContentResolver cr, long contactId, String lookupKey) {
        try {
            Uri uri = (lookupKey != null)
                    ? ContactsContract.Contacts.getLookupUri(contactId, lookupKey)
                    : ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId);
            return cr.delete(uri, null, null) > 0;
        } catch (Exception e) {
            Log.e(TAG, "could not delete the contact: " + contactId, e);
            return false;
        }
    }

    /**
     * Adds or removes a contact from the favorites.
     *
     * We keep no list of our own and use the provider's STARRED column, so the
     * favorites are shared with the system and other apps.
     */
    public static boolean setStarred(ContentResolver cr, long contactId, boolean starred) {
        if (contactId <= 0) return false;
        try {
            ContentValues v = new ContentValues();
            v.put(ContactsContract.Contacts.STARRED, starred ? 1 : 0);
            Uri uri = ContentUris.withAppendedId(
                    ContactsContract.Contacts.CONTENT_URI, contactId);
            return cr.update(uri, v, null, null) > 0;
        } catch (Exception e) {
            Log.e(TAG, "could not change the favorite state: " + contactId, e);
            return false;
        }
    }

    /**
     * The per-contact ringtone. It lives in the provider's own CUSTOM_RINGTONE
     * column and Telecom plays it, so it survives switching to another dialer.
     */
    public static String getRingtone(ContentResolver cr, long contactId) {
        if (contactId <= 0) return null;
        Cursor c = null;
        try {
            c = cr.query(ContentUris.withAppendedId(
                            ContactsContract.Contacts.CONTENT_URI, contactId),
                    new String[]{ContactsContract.Contacts.CUSTOM_RINGTONE}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {
            Log.e(TAG, "could not read the ringtone: " + contactId, e);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** @param uri null or empty restores the default ringtone. */
    public static boolean setRingtone(ContentResolver cr, long contactId, String uri) {
        if (contactId <= 0) return false;
        try {
            ContentValues v = new ContentValues();
            if (uri == null || uri.trim().isEmpty()) {
                v.putNull(ContactsContract.Contacts.CUSTOM_RINGTONE);
            } else {
                v.put(ContactsContract.Contacts.CUSTOM_RINGTONE, uri);
            }
            return cr.update(ContentUris.withAppendedId(
                    ContactsContract.Contacts.CONTENT_URI, contactId), v, null, null) > 0;
        } catch (Exception e) {
            Log.e(TAG, "could not write the ringtone: " + contactId, e);
            return false;
        }
    }

    public static boolean isStarred(ContentResolver cr, long contactId) {
        if (contactId <= 0) return false;
        Cursor c = null;
        try {
            c = cr.query(ContentUris.withAppendedId(
                            ContactsContract.Contacts.CONTENT_URI, contactId),
                    new String[]{ContactsContract.Contacts.STARRED}, null, null, null);
            return c != null && c.moveToFirst() && c.getInt(0) != 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * The favorite contacts. A user-defined order is applied first; favorites
     * added later, which are not in that order, follow at the end by name.
     *
     * @param orderCsv the saved order as comma-separated contact ids; may be empty
     */
    public static List<Contact> favorites(List<Contact> all, String orderCsv) {
        Map<Long, Contact> starred = new LinkedHashMap<>();
        for (Contact c : all) {
            if (c.starred) starred.put(c.id, c);
        }

        List<Contact> out = new ArrayList<>();
        if (orderCsv != null && !orderCsv.trim().isEmpty()) {
            for (String part : orderCsv.split(",")) {
                try {
                    Contact c = starred.remove(Long.parseLong(part.trim()));
                    if (c != null) out.add(c);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // The ones not in the order: by name, at the end
        List<Contact> rest = new ArrayList<>(starred.values());
        Collections.sort(rest, PhoneUtil.contactOrder());
        out.addAll(rest);
        return out;
    }

    /** Adds another number to an existing contact. */
    public static boolean addNumber(ContentResolver cr, long contactId, String number) {
        long rawId = editableRawContactId(cr, contactId);
        if (rawId <= 0) {
            return attachNewRawContact(cr, contactId, null, Collections.singletonList(number));
        }
        try {
            ContentValues v = new ContentValues();
            v.put(Data.RAW_CONTACT_ID, rawId);
            v.put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
            v.put(Phone.NUMBER, number.trim());
            v.put(Phone.TYPE, Phone.TYPE_MOBILE);
            return cr.insert(Data.CONTENT_URI, v) != null;
        } catch (Exception e) {
            Log.e(TAG, "could not add the number", e);
            return false;
        }
    }

    // ------------------------------------------------------------- internals

    private static void addNameOp(ArrayList<ContentProviderOperation> ops, String name) {
        if (name == null || name.trim().isEmpty()) return;
        ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, name.trim())
                .build());
    }

    private static void addNumberOps(ArrayList<ContentProviderOperation> ops, List<String> numbers) {
        for (String n : numbers) {
            if (n == null || n.trim().isEmpty()) continue;
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, n.trim())
                    .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                    .build());
        }
    }

    /**
     * To make a contact from read-only accounts editable: creates a new raw
     * contact in device storage and marks the two as the same person.
     */
    private static boolean attachNewRawContact(ContentResolver cr, long contactId,
                                               String name, List<String> numbers) {
        long anchor = anyRawContactId(cr, contactId);
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .withValue(RawContacts.ACCOUNT_NAME, null)
                .build());
        addNameOp(ops, name);
        addNumberOps(ops, numbers);
        try {
            ContentProviderResult[] res = cr.applyBatch(ContactsContract.AUTHORITY, ops);
            if (res.length == 0 || res[0].uri == null) return false;
            long newRawId = ContentUris.parseId(res[0].uri);
            if (anchor > 0) {
                ContentValues v = new ContentValues();
                v.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, Math.min(anchor, newRawId));
                v.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, Math.max(anchor, newRawId));
                v.put(ContactsContract.AggregationExceptions.TYPE,
                        ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER);
                cr.update(ContactsContract.AggregationExceptions.CONTENT_URI, v, null, null);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "could not link the new raw contact", e);
            return false;
        }
    }

    /** A contact's editable raw contact: device storage first, then Google, then the rest. */
    private static long editableRawContactId(ContentResolver cr, long contactId) {
        long local = -1, google = -1, other = -1;
        Cursor c = null;
        try {
            c = cr.query(RawContacts.CONTENT_URI,
                    new String[]{RawContacts._ID, RawContacts.ACCOUNT_TYPE},
                    RawContacts.CONTACT_ID + "=? AND " + RawContacts.DELETED + "=0",
                    new String[]{String.valueOf(contactId)}, null);
            if (c == null) return -1;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String type = c.getString(1);
                if (type == null) {
                    if (local < 0) local = id;
                } else if (type.startsWith("com.google")) {
                    if (google < 0) google = id;
                } else if (!type.startsWith(ACC_WHATSAPP) && !type.startsWith(ACC_TELEGRAM)) {
                    if (other < 0) other = id;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "no raw contact found", e);
        } finally {
            if (c != null) c.close();
        }
        if (local > 0) return local;
        if (google > 0) return google;
        return other;
    }

    private static long anyRawContactId(ContentResolver cr, long contactId) {
        Cursor c = null;
        try {
            c = cr.query(RawContacts.CONTENT_URI, new String[]{RawContacts._ID},
                    RawContacts.CONTACT_ID + "=? AND " + RawContacts.DELETED + "=0",
                    new String[]{String.valueOf(contactId)}, null);
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) {
            Log.e(TAG, "no raw contact found", e);
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private static long contactIdOfRaw(ContentResolver cr, long rawId) {
        Cursor c = null;
        try {
            c = cr.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
                    new String[]{RawContacts.CONTACT_ID}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) {
            Log.e(TAG, "no contact id found", e);
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private static boolean hasNameRow(ContentResolver cr, long rawId) {
        Cursor c = null;
        try {
            c = cr.query(Data.CONTENT_URI, new String[]{Data._ID},
                    Data.RAW_CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                    new String[]{String.valueOf(rawId), StructuredName.CONTENT_ITEM_TYPE}, null);
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * The account new contacts go to: the writable one already holding most of
     * them. With none available, {null, null} means device storage.
     */
    private static String[] preferredAccount(ContentResolver cr) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String[]> accounts = new HashMap<>();
        Cursor c = null;
        try {
            c = cr.query(RawContacts.CONTENT_URI,
                    new String[]{RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME},
                    RawContacts.DELETED + "=0", null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String type = c.getString(0);
                    String name = c.getString(1);
                    if (type == null) continue;
                    if (type.startsWith(ACC_WHATSAPP) || type.startsWith(ACC_TELEGRAM)) continue;
                    String k = type + SEP + name;
                    counts.put(k, counts.getOrDefault(k, 0) + 1);
                    accounts.put(k, new String[]{type, name});
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "could not read the accounts", e);
        } finally {
            if (c != null) c.close();
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best != null ? accounts.get(best) : new String[]{null, null};
    }
}
