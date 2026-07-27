package com.capman.dialer;

import java.util.ArrayList;
import java.util.List;

public class Contact {
    public long id;
    public String lookupKey;
    public String name;
    /** Small image, used in lists. */
    public String photoThumbUri;
    /** Large image for the detail and call screens; falls back to the thumbnail. */
    public String photoUri;
    /** The contact provider's own "starred" flag, shared with the system. */
    public boolean starred;

    public String bestPhoto(boolean large) {
        if (large) return photoUri != null ? photoUri : photoThumbUri;
        return photoThumbUri != null ? photoThumbUri : photoUri;
    }
    public final List<String> numbers = new ArrayList<>();
    public final List<String> labels = new ArrayList<>();

    public String primaryNumber() {
        return numbers.isEmpty() ? null : numbers.get(0);
    }

    public String displayName() {
        if (name != null && !name.trim().isEmpty()) return name;
        String n = primaryNumber();
        return n != null ? PhoneUtil.pretty(n) : "";
    }
}
