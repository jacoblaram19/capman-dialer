package com.capman.dialer;

import android.telephony.PhoneNumberUtils;

import java.text.Collator;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;

/** Helpers for formatting and comparing phone numbers. */
public final class PhoneUtil {

    private PhoneUtil() {
    }

    /** Keeps the digits only, dropping everything including the "+". */
    public static String digitsOnly(String number) {
        if (number == null) return "";
        StringBuilder sb = new StringBuilder(number.length());
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Comparison key: the last 9 digits, so 0532..., +90532... and 532... all
     * map to the same person. Anything shorter than 9 digits (short codes) is
     * left as it is.
     */
    public static String key(String number) {
        String d = digitsOnly(number);
        return d.length() > 9 ? d.substring(d.length() - 9) : d;
    }

    public static boolean sameNumber(String a, String b) {
        String ka = key(a), kb = key(b);
        return !ka.isEmpty() && ka.equals(kb);
    }

    /**
     * Converts a number to international form: 0532 397 63 97 -> +905323976397.
     *
     * The country code comes from the SIM first and from the device locale
     * otherwise. Anything that cannot be converted (service short codes, 4-5
     * digit numbers, anything already starting with "+") is left alone -
     * forcing a conversion would only break it.
     */
    public static String toE164(android.content.Context ctx, String number) {
        if (number == null) return null;
        String trimmed = number.trim();
        if (trimmed.isEmpty()) return trimmed;

        String country = simCountry(ctx);
        if (country == null || country.isEmpty()) return trimmed;
        try {
            String e164 = PhoneNumberUtils.formatNumberToE164(trimmed, country.toUpperCase(Locale.US));
            return e164 != null ? e164 : trimmed;
        } catch (Exception e) {
            return trimmed;
        }
    }

    private static String simCountry(android.content.Context ctx) {
        try {
            android.telephony.TelephonyManager tm =
                    ctx.getSystemService(android.telephony.TelephonyManager.class);
            if (tm != null) {
                String iso = tm.getSimCountryIso();
                if (iso != null && !iso.isEmpty()) return iso;
                iso = tm.getNetworkCountryIso();
                if (iso != null && !iso.isEmpty()) return iso;
            }
        } catch (Exception ignored) {
        }
        return Locale.getDefault().getCountry();
    }

    /** Display form, e.g. 0532 111 22 33. */
    public static String pretty(String number) {
        if (number == null || number.isEmpty()) return "";
        String formatted = PhoneNumberUtils.formatNumber(number, Locale.getDefault().getCountry());
        return formatted != null ? formatted : number;
    }

    /** The device locale drives casing and collation, so the index follows it. */
    public static final Locale LOCALE = Locale.getDefault();

    /**
     * The section a contact belongs to (its initial). Letters of the alphabet
     * return themselves, accented Latin letters return their plain equivalent.
     * Anything starting with a digit, a symbol or a character outside the
     * alphabet lands in the "#" section, which collects at the bottom of the
     * list.
     */
    public static String section(String name) {
        if (name == null) return "#";
        String t = name.trim();
        if (t.isEmpty()) return "#";

        String up = String.valueOf(t.charAt(0)).toUpperCase(LOCALE);
        char c = up.charAt(0);
        if (c >= 'A' && c <= 'Z') return String.valueOf(c);

        // É -> E, for accented letters outside the alphabet
        String norm = Normalizer.normalize(up, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        if (!norm.isEmpty()) {
            char n = norm.charAt(0);
            if (n >= 'A' && n <= 'Z') return String.valueOf(n);
        }
        return "#";
    }

    /** Is this the "#" section? Those go to the end of the list. */
    public static boolean isOther(String name) {
        return "#".equals(section(name));
    }

    /** The initial taken from a name, for the avatar. */
    public static String initials(String name) {
        return section(name);
    }

    /** Locale-aware lowercase, to sidestep the dotted/dotless I trap. */
    public static String lowerCase(String s) {
        return s == null ? "" : s.toLowerCase(LOCALE);
    }

    /**
     * Contact ordering: A-Z first, in alphabet order, then the "#" section.
     */
    public static Comparator<Contact> contactOrder() {
        final Collator collator = Collator.getInstance(LOCALE);
        collator.setStrength(Collator.SECONDARY);
        return (a, b) -> {
            String na = a.displayName(), nb = b.displayName();
            int ga = isOther(na) ? 1 : 0;
            int gb = isOther(nb) ? 1 : 0;
            if (ga != gb) return ga - gb;
            return collator.compare(na, nb);
        };
    }
}
