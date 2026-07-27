package com.capman.dialer;

import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.CallLog;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * READ-ONLY access to the call history.
 *
 * Important: this class never deletes or modifies an entry. The call log lives
 * in Android's system-wide CallLog provider and we only read from it - the app
 * never even asks for WRITE_CALL_LOG.
 *
 * The default view is limited to the last 6 months; older entries are NOT
 * deleted, only hidden, and "Show more" loads the lot.
 */
public final class CallLogRepo {

    private static final String TAG = "CallLogRepo";

    /** 6 months ~ 182 days. */
    public static final long WINDOW_MS = 182L * 24 * 60 * 60 * 1000;

    /** A performance cap. Hitting it also brings up "show more". */
    private static final int LIMIT_RECENT = 2000;
    private static final int LIMIT_ALL = 10000;

    private CallLogRepo() {
    }

    public static class Page {
        public final List<RecentItem> items;
        /** Are there older entries not being shown? */
        public final boolean hasMore;

        Page(List<RecentItem> items, boolean hasMore) {
            this.items = items;
            this.hasMore = hasMore;
        }
    }

    /**
     * @param windowDays the history window in days; 0 or negative means no limit.
     */
    public static Page load(ContentResolver cr, boolean all, int windowDays) {
        if (windowDays <= 0) all = true;
        return loadInternal(cr, all, windowDays);
    }

    private static Page loadInternal(ContentResolver cr, boolean all, int windowDays) {
        String[] proj = {
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
        };
        int limit = all ? LIMIT_ALL : LIMIT_RECENT;
        String selection = null;
        String[] args = null;
        if (!all) {
            selection = CallLog.Calls.DATE + ">=?";
            args = new String[]{String.valueOf(cutoff(windowDays))};
        }
        String order = CallLog.Calls.DATE + " DESC LIMIT " + limit;

        List<RecentItem> rows = new ArrayList<>();
        Cursor c = null;
        try {
            c = cr.query(CallLog.Calls.CONTENT_URI, proj, selection, args, order);
            if (c != null) {
                while (c.moveToNext()) {
                    RecentItem r = new RecentItem();
                    r.id = c.getLong(0);
                    r.number = c.getString(1);
                    r.cachedName = c.getString(2);
                    r.type = c.getInt(3);
                    r.date = c.getLong(4);
                    r.duration = c.getLong(5);
                    if (r.number == null) r.number = "";
                    rows.add(r);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "could not read the call log", e);
        } finally {
            if (c != null) c.close();
        }

        boolean hasMore = false;
        if (!all) {
            // Either we hit the cap, or there are entries older than the window.
            hasMore = rows.size() >= limit || hasOlder(cr, windowDays);
        }
        return new Page(group(rows), hasMore);
    }

    private static long cutoff(int windowDays) {
        return System.currentTimeMillis() - windowDays * 24L * 60 * 60 * 1000;
    }

    /** Is there anything older than the window? */
    private static boolean hasOlder(ContentResolver cr, int windowDays) {
        Cursor c = null;
        try {
            c = cr.query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls._ID},
                    CallLog.Calls.DATE + "<?", new String[]{String.valueOf(cutoff(windowDays))},
                    CallLog.Calls.DATE + " DESC LIMIT 1");
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /** The last dialled number, used to refill the keypad when "call" is pressed empty. */
    public static String lastOutgoing(ContentResolver cr) {
        Cursor c = null;
        try {
            c = cr.query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.NUMBER},
                    CallLog.Calls.TYPE + "=?",
                    new String[]{String.valueOf(CallLog.Calls.OUTGOING_TYPE)},
                    CallLog.Calls.DATE + " DESC LIMIT 1");
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {
            Log.e(TAG, "could not read the last call", e);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /**
     * Every call for one contact, for the detail screen. All of the contact's
     * numbers are scanned and the results merged into one list by date.
     */
    public static List<RecentItem> forNumbers(ContentResolver cr, List<String> numbers, int limit) {
        List<RecentItem> out = new ArrayList<>();
        if (numbers == null || numbers.isEmpty()) return out;

        String[] proj = {
                CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION,
        };
        StringBuilder sel = new StringBuilder();
        List<String> args = new ArrayList<>();
        for (String n : numbers) {
            if (n == null || n.trim().isEmpty()) continue;
            if (sel.length() > 0) sel.append(" OR ");
            // The provider's own number matching: +90532... equals 0532...
            sel.append("PHONE_NUMBERS_EQUAL(").append(CallLog.Calls.NUMBER).append(", ?, 0)");
            args.add(n.trim());
        }
        if (sel.length() == 0) return out;

        Cursor c = null;
        try {
            c = cr.query(CallLog.Calls.CONTENT_URI, proj, sel.toString(),
                    args.toArray(new String[0]),
                    CallLog.Calls.DATE + " DESC LIMIT " + limit);
            if (c != null) {
                while (c.moveToNext()) {
                    RecentItem r = new RecentItem();
                    r.id = c.getLong(0);
                    r.number = c.getString(1) == null ? "" : c.getString(1);
                    r.cachedName = c.getString(2);
                    r.type = c.getInt(3);
                    r.date = c.getLong(4);
                    r.duration = c.getLong(5);
                    out.add(r);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "could not read the calls for this contact", e);
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /**
     * A one-line summary about the caller, shown on the incoming call screen.
     *
     * Computed entirely from your own call log, no network involved. A number
     * you don't know calling "for the 3rd time today" is either urgent or a
     * salesperson; knowing it is the first time is useful too.
     */
    public static String callerSummary(ContentResolver cr, String number) {
        if (number == null || number.trim().isEmpty()) return null;
        List<RecentItem> past = forNumbers(cr, java.util.Collections.singletonList(number), 500);
        if (past.isEmpty()) return "Calling for the first time";

        long dayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        int todayIncoming = 0;
        int talked = 0;
        long last = 0;
        for (RecentItem r : past) {
            if (r.date > last) last = r.date;
            if (r.duration > 0) talked++;
            boolean incomingish = r.type == CallLog.Calls.INCOMING_TYPE
                    || r.type == CallLog.Calls.MISSED_TYPE
                    || r.type == CallLog.Calls.REJECTED_TYPE;
            if (incomingish && r.date >= dayAgo) todayIncoming++;
        }

        if (todayIncoming >= 1) {
            return "Call number " + (todayIncoming + 1) + " today";
        }
        if (talked > 0) {
            return "You have talked " + talked + " times · last " + shortDate(last);
        }
        return "Called " + past.size() + " times, never answered";
    }

    private static String shortDate(long ts) {
        if (ts <= 0) return "-";
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("d MMM", new java.util.Locale("tr", "TR"));
        return f.format(new java.util.Date(ts));
    }

    /**
     * Missed calls that have NOT been returned yet.
     *
     * A missed call counts as returned as soon as there is an outgoing call to
     * that number AFTER it. That way no separate list has to be kept - the
     * answer falls out of the call log itself.
     *
     * @param windowDays how many days back to look; 0 means no limit
     */
    public static List<RecentItem> missedPending(ContentResolver cr, int windowDays) {
        String[] proj = {
                CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION,
        };
        String selection = null;
        String[] args = null;
        if (windowDays > 0) {
            selection = CallLog.Calls.DATE + ">=?";
            args = new String[]{String.valueOf(cutoff(windowDays))};
        }

        List<RecentItem> missed = new ArrayList<>();
        // number key -> the time of the LAST outgoing call to that number
        java.util.Map<String, Long> lastOutgoing = new java.util.HashMap<>();

        Cursor c = null;
        try {
            c = cr.query(CallLog.Calls.CONTENT_URI, proj, selection, args,
                    CallLog.Calls.DATE + " DESC LIMIT " + LIMIT_ALL);
            if (c != null) {
                while (c.moveToNext()) {
                    String number = c.getString(1);
                    if (number == null) number = "";
                    int type = c.getInt(3);
                    long date = c.getLong(4);
                    String key = PhoneUtil.key(number);

                    if (type == CallLog.Calls.OUTGOING_TYPE) {
                        Long prev = lastOutgoing.get(key);
                        if (prev == null || date > prev) lastOutgoing.put(key, date);
                    } else if (isMissedLike(type)) {
                        RecentItem r = new RecentItem();
                        r.id = c.getLong(0);
                        r.number = number;
                        r.cachedName = c.getString(2);
                        r.type = type;
                        r.date = date;
                        r.duration = c.getLong(5);
                        missed.add(r);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "could not read the missed calls", e);
        } finally {
            if (c != null) c.close();
        }

        // Keep the unreturned ones, collapsing each number into a single row
        List<RecentItem> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (RecentItem r : missed) {
            String key = PhoneUtil.key(r.number);
            Long back = lastOutgoing.get(key);
            if (back != null && back > r.date) continue;        // geri donulmus
            if (key.isEmpty()) {
                r.group.add(r);
                out.add(r);
                continue;
            }
            if (seen.contains(key)) {
                // older missed calls from the same number bump the counter on the row above
                for (RecentItem head : out) {
                    if (PhoneUtil.key(head.number).equals(key)) {
                        head.count++;
                        head.group.add(r);
                        break;
                    }
                }
                continue;
            }
            seen.add(key);
            r.group.add(r);
            out.add(r);
        }
        return out;
    }

    /** How many calls are there with one contact? Used by the "show all" button. */
    public static int countForNumbers(ContentResolver cr, List<String> numbers) {
        return forNumbers(cr, numbers, 10000).size();
    }

    /**
     * Collapses consecutive calls with the same number and type into one row.
     * The individual calls are kept in {@link RecentItem#group} so that
     * expanding the row (the "+2" arrow) can show each one's time and
     * duration.
     */
    private static List<RecentItem> group(List<RecentItem> in) {
        List<RecentItem> out = new ArrayList<>(in.size());
        for (RecentItem r : in) {
            if (!out.isEmpty()) {
                RecentItem head = out.get(out.size() - 1);
                boolean sameKind = head.type == r.type
                        || (isMissedLike(head.type) && isMissedLike(r.type));
                if (sameKind && PhoneUtil.sameNumber(head.number, r.number)) {
                    head.count++;
                    head.group.add(r);
                    continue;
                }
            }
            // Head of the group: it joins its own group, so totals like
            // "overall duration" can be computed in one place.
            r.group.add(r);
            out.add(r);
        }
        return out;
    }

    private static boolean isMissedLike(int type) {
        return type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE;
    }
}
