package com.capman.dialer;

/** A place a contact can be saved: device storage, the SIM or a Google account. */
public class ContactAccount {

    /** Key separator: a character no account type or name can contain. */
    private static final String SEP = String.valueOf((char) 0);

    public final String type;   // null means device storage
    public final String name;
    public final String label;  // the label shown to the user
    public final boolean isSim;

    public ContactAccount(String type, String name, String label, boolean isSim) {
        this.type = type;
        this.name = name;
        this.label = label;
        this.isSim = isSim;
    }

    public String key() {
        return (type == null ? "" : type) + SEP + (name == null ? "" : name);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ContactAccount && key().equals(((ContactAccount) o).key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }
}
