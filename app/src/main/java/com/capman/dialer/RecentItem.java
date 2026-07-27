package com.capman.dialer;

import java.util.ArrayList;
import java.util.List;

public class RecentItem {
    public long id;
    public String number;
    public String cachedName;
    public int type;        // CallLog.Calls.INCOMING_TYPE / OUTGOING_TYPE / MISSED_TYPE ...
    public long date;
    public long duration;   // seconds

    /** How many consecutive calls to the same number this row stands for (including itself). */
    public int count = 1;

    /**
     * The individual calls in the group, newest first. The first element is this
     * entry itself; expanding the row reveals the rest (the "+2").
     */
    public final List<RecentItem> group = new ArrayList<>();
}
