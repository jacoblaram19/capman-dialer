package com.capman.dialer;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Receives the "remind me about this call later" alarm and posts the reminder.
 */
public class ReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE = "com.capman.dialer.REMIND";
    public static final String ACTION_CALL = "com.capman.dialer.REMINDER_CALL";
    public static final String ACTION_SNOOZE = "com.capman.dialer.REMINDER_SNOOZE";

    public static final String EX_ID = "id";
    public static final String EX_NUMBER = "number";
    public static final String EX_NAME = "name";

    private static final int SNOOZE_MINUTES = 15;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        int id = intent.getIntExtra(EX_ID, 0);
        String number = intent.getStringExtra(EX_NUMBER);
        String name = intent.getStringExtra(EX_NAME);

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);

        switch (intent.getAction()) {
            case ACTION_FIRE:
                Reminders.ensureChannel(ctx);
                if (nm != null) nm.notify(id, Reminders.build(ctx, id, number, name));
                Reminders.forget(ctx, id);
                break;

            case ACTION_CALL:
                if (nm != null) nm.cancel(id);
                closeShade(ctx);
                Dial.call(ctx, number);
                break;

            case ACTION_SNOOZE:
                if (nm != null) nm.cancel(id);
                long at = Reminders.schedule(ctx, number, name, SNOOZE_MINUTES);
                Toast.makeText(ctx, at > 0
                                ? SNOOZE_MINUTES + " minutes and I'll remind you again"
                                : "Could not set the reminder",
                        Toast.LENGTH_SHORT).show();
                break;

            default:
                break;
        }
    }

    /** Collapse the shade when a call is started from the notification. */
    private void closeShade(Context ctx) {
        try {
            ctx.sendBroadcast(new Intent("android.intent.action.CLOSE_SYSTEM_DIALOGS"));
        } catch (Exception ignored) {
        }
    }
}
