package com.capman.dialer;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The "remind me about this call in X" entries.
 *
 * Reminders are armed through AlarmManager. A reboot wipes the alarms, so the
 * same entries are re-armed from SharedPreferences afterwards.
 */
public final class Reminders {

    private static final String TAG = "Reminders";

    static final String CHANNEL_ID = "call_reminder";
    private static final String PREF_KEY = "hatirlatmalar";

    /** Field separator inside a stored row: a character no name or number contains. */
    private static final String SEP = String.valueOf((char) 31);

    /** The delays offered to the user, in minutes. */
    public static final int[] MINUTES = {5, 10, 15, 30, 60, 120};

    private Reminders() {
    }

    public static String label(int minutes) {
        if (minutes < 60) return "in " + minutes + " minutes";
        int h = minutes / 60;
        return h == 1 ? "in an hour" : "in " + h + " hours";
    }

    public static CharSequence[] labels() {
        CharSequence[] out = new CharSequence[MINUTES.length];
        for (int i = 0; i < MINUTES.length; i++) out[i] = label(MINUTES[i]);
        return out;
    }

    // ------------------------------------------------------------------ arming

    /** @return when the reminder will fire, or 0 if it could not be armed */
    public static long schedule(Context ctx, String number, String name, int minutes) {
        if (number == null || number.trim().isEmpty()) return 0;
        long at = System.currentTimeMillis() + minutes * 60_000L;
        int id = (int) (at / 1000);
        if (!setAlarm(ctx, id, at, number.trim(), name)) return 0;
        remember(ctx, id + SEP + at + SEP + number.trim() + SEP + (name == null ? "" : name));
        return at;
    }

    private static boolean setAlarm(Context ctx, int id, long at, String number, String name) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return false;

        Intent i = new Intent(ctx, ReminderReceiver.class)
                .setAction(ReminderReceiver.ACTION_FIRE)
                .putExtra(ReminderReceiver.EX_ID, id)
                .putExtra(ReminderReceiver.EX_NUMBER, number)
                .putExtra(ReminderReceiver.EX_NAME, name);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            // Firing on time matters; without the permission we fall back to inexact
            if (canExact(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "could not arm the reminder", e);
            return false;
        }
    }

    private static boolean canExact(AlarmManager am) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        try {
            return am.canScheduleExactAlarms();
        } catch (Exception e) {
            return false;
        }
    }

    /** Re-arms the stored reminders after a reboot. */
    public static void rescheduleAll(Context ctx) {
        long now = System.currentTimeMillis();
        Set<String> kept = new HashSet<>();
        for (String row : stored(ctx)) {
            String[] p = row.split(SEP, -1);
            if (p.length < 4) continue;
            try {
                int id = Integer.parseInt(p[0]);
                long at = Long.parseLong(p[1]);
                if (at <= now) continue;             // already past, drop it
                setAlarm(ctx, id, at, p[2], p[3]);
                kept.add(row);
            } catch (Exception ignored) {
            }
        }
        Prefs.get(ctx).edit().putStringSet(PREF_KEY, kept).apply();
    }

    // ------------------------------------------------------------------ the ledger

    private static Set<String> stored(Context ctx) {
        return new HashSet<>(Prefs.get(ctx).getStringSet(PREF_KEY, new HashSet<>()));
    }

    private static void remember(Context ctx, String row) {
        Set<String> all = stored(ctx);
        all.add(row);
        Prefs.get(ctx).edit().putStringSet(PREF_KEY, all).apply();
    }

    static void forget(Context ctx, int id) {
        Set<String> all = stored(ctx);
        all.removeIf(row -> row.startsWith(id + SEP));
        Prefs.get(ctx).edit().putStringSet(PREF_KEY, all).apply();
    }

    /** Pending reminders, soonest first. */
    public static List<String> pendingDescriptions(Context ctx) {
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        long now = System.currentTimeMillis();
        // Keep the timestamp alongside so we can sort by time; sorting the
        // plain strings sorted them by name instead.
        List<long[]> order = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (String row : stored(ctx)) {
            String[] p = row.split(SEP, -1);
            if (p.length < 4) continue;
            try {
                long at = Long.parseLong(p[1]);
                if (at <= now) continue;
                String who = p[3].isEmpty() ? PhoneUtil.pretty(p[2]) : p[3];
                order.add(new long[]{at, texts.size()});
                texts.add(who + " · " + fmt.format(new Date(at)));
            } catch (Exception ignored) {
            }
        }
        order.sort((a, b) -> Long.compare(a[0], b[0]));
        List<String> out = new ArrayList<>(order.size());
        for (long[] o : order) out.add(texts.get((int) o[1]));
        return out;
    }

    // ------------------------------------------------------------------ notification

    static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "Call reminders", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("The calls you asked to be reminded about");
        nm.createNotificationChannel(ch);
    }

    static Notification build(Context ctx, int id, String number, String name) {
        String who = (name == null || name.isEmpty()) ? PhoneUtil.pretty(number) : name;

        PendingIntent call = PendingIntent.getBroadcast(ctx, id + 1,
                new Intent(ctx, ReminderReceiver.class)
                        .setAction(ReminderReceiver.ACTION_CALL)
                        .putExtra(ReminderReceiver.EX_ID, id)
                        .putExtra(ReminderReceiver.EX_NUMBER, number),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent snooze = PendingIntent.getBroadcast(ctx, id + 2,
                new Intent(ctx, ReminderReceiver.class)
                        .setAction(ReminderReceiver.ACTION_SNOOZE)
                        .putExtra(ReminderReceiver.EX_ID, id)
                        .putExtra(ReminderReceiver.EX_NUMBER, number)
                        .putExtra(ReminderReceiver.EX_NAME, name),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent open = PendingIntent.getActivity(ctx, id + 3,
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(who)
                .setContentText("A reminder to call back")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        icon(ctx, R.drawable.ic_phone), "Call now", call).build())
                .addAction(new Notification.Action.Builder(
                        icon(ctx, R.drawable.ic_alarm), "Snooze 15 min", snooze).build())
                .build();
    }

    private static android.graphics.drawable.Icon icon(Context ctx, int res) {
        return android.graphics.drawable.Icon.createWithResource(ctx, res);
    }
}
