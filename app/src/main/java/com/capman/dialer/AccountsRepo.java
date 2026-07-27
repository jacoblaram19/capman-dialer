package com.capman.dialer;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.provider.ContactsContract;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a contact gets saved: device storage, the SIM card or a Google account.
 *
 * Accounts come from two sources: those that already hold contacts in the
 * provider, and AccountManager. For the SIM we use
 * {@code SimContacts.getSimAccounts()} on Android 12+, falling back to spotting
 * SIM-like account types in the provider.
 */
public final class AccountsRepo {

    private static final String TAG = "AccountsRepo";
    private static final String PREFS = "dialer_prefs";
    private static final String KEY_LAST_ACCOUNT = "son_kayit_hesabi";

    /** Read-only sync adapters: no contact can be written to these. */
    private static final String[] READ_ONLY = {
            "com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms",
            "com.facebook", "com.instagram", "com.viber",
    };

    private AccountsRepo() {
    }

    public static ContactAccount deviceStorage() {
        return new ContactAccount(null, null, "Device storage", false);
    }

    /** The places a contact can be saved to. Device storage comes first. */
    public static List<ContactAccount> writableAccounts(Context ctx) {
        Map<String, ContactAccount> out = new LinkedHashMap<>();
        ContactAccount device = deviceStorage();
        out.put(device.key(), device);

        for (ContactAccount a : simAccounts(ctx)) out.put(a.key(), a);

        // Accounts that already hold contacts
        for (ContactAccount a : accountsFromProvider(ctx.getContentResolver())) {
            if (!out.containsKey(a.key())) out.put(a.key(), a);
        }
        // Accounts known to AccountManager that hold none yet
        for (ContactAccount a : accountsFromManager(ctx)) {
            if (!out.containsKey(a.key())) out.put(a.key(), a);
        }
        return new ArrayList<>(out.values());
    }

    private static List<ContactAccount> simAccounts(Context ctx) {
        List<ContactAccount> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                List<ContactsContract.SimAccount> sims =
                        ContactsContract.SimContacts.getSimAccounts(ctx.getContentResolver());
                if (sims != null) {
                    for (ContactsContract.SimAccount s : sims) {
                        out.add(new ContactAccount(s.getAccountType(), s.getAccountName(),
                                "SIM kart" + (s.getAccountName() == null ? ""
                                        : " · " + s.getAccountName()), true));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "could not read the SIM accounts", e);
            }
        }
        if (out.isEmpty()) {
            // Spot it from SIM-like account types in the provider (e.g. "USIM Account ADN")
            for (ContactAccount a : accountsFromProvider(ctx.getContentResolver())) {
                if (a.isSim) out.add(a);
            }
        }
        return out;
    }

    private static List<ContactAccount> accountsFromProvider(ContentResolver cr) {
        Map<String, ContactAccount> out = new LinkedHashMap<>();
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
                    if (isReadOnly(type)) continue;
                    ContactAccount a = new ContactAccount(type, name, label(type, name), isSim(type));
                    out.put(a.key(), a);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "could not read the accounts", e);
        } finally {
            if (c != null) c.close();
        }
        return new ArrayList<>(out.values());
    }

    private static List<ContactAccount> accountsFromManager(Context ctx) {
        List<ContactAccount> out = new ArrayList<>();
        try {
            AccountManager am = AccountManager.get(ctx);
            for (Account a : am.getAccounts()) {
                if (a.type == null || isReadOnly(a.type)) continue;
                // Only account types that can hold contacts are of interest
                if (!a.type.startsWith("com.google") && !isSim(a.type)) continue;
                out.add(new ContactAccount(a.type, a.name, label(a.type, a.name), isSim(a.type)));
            }
        } catch (Exception e) {
            Log.w(TAG, "could not read AccountManager", e);
        }
        return out;
    }

    private static boolean isReadOnly(String type) {
        String t = type.toLowerCase();
        for (String r : READ_ONLY) {
            if (t.startsWith(r)) return true;
        }
        return false;
    }

    private static boolean isSim(String type) {
        String t = type.toLowerCase();
        return t.contains("sim") || t.contains("adn") || t.contains("icc");
    }

    private static String label(String type, String name) {
        if (type == null) return "Device storage";
        if (isSim(type)) return "SIM kart" + (name == null ? "" : " · " + name);
        if (type.startsWith("com.google")) return "Google · " + (name == null ? "" : name);
        return (name == null ? type : name);
    }

    // ------------------------------------------------------------------ preference

    /** The last chosen destination, or the account holding most contacts. */
    public static ContactAccount lastUsed(Context ctx, List<ContactAccount> available) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = p.getString(KEY_LAST_ACCOUNT, null);
        if (key != null) {
            for (ContactAccount a : available) {
                if (a.key().equals(key)) return a;
            }
        }
        ContactAccount busiest = busiest(ctx.getContentResolver(), available);
        return busiest != null ? busiest : deviceStorage();
    }

    public static void remember(Context ctx, ContactAccount account) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_ACCOUNT, account.key()).apply();
    }

    /** The writable account with the most contacts in it. */
    private static ContactAccount busiest(ContentResolver cr, List<ContactAccount> available) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Cursor c = null;
        try {
            c = cr.query(RawContacts.CONTENT_URI,
                    new String[]{RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME},
                    RawContacts.DELETED + "=0", null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String type = c.getString(0);
                    String name = c.getString(1);
                    if (type == null || isReadOnly(type) || isSim(type)) continue;
                    String k = new ContactAccount(type, name, "", false).key();
                    counts.put(k, counts.getOrDefault(k, 0) + 1);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "could not count the accounts", e);
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
        if (best == null) return null;
        for (ContactAccount a : available) {
            if (a.key().equals(best)) return a;
        }
        return null;
    }
}
