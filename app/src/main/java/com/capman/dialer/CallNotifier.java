package com.capman.dialer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The call notification.
 *
 * The goal: once the call screen is already up, no heads-up banner should drop
 * from the top - but pulling the shade down must still show the call as a card,
 * with "Answer / Reject" while ringing and a duration counter plus "Hang up"
 * during the call. Tapping the card brings the full call screen back.
 *
 * Two channels make that work:
 *   - {@link #CH_SHADE}  LOW importance: visible in the shade, no banner. The
 *     normal path.
 *   - {@link #CH_FULL}   HIGH importance, carrying a full-screen intent. It only
 *     kicks in when the call screen could not come forward by itself (lock
 *     screen, background restrictions) and is withdrawn the moment the screen
 *     becomes visible.
 *
 * The notification doubles as the service's foreground notification; Android 14+
 * only accepts CallStyle notifications from a foreground service or with a
 * full-screen intent.
 */
public final class CallNotifier {

    private static final String TAG = "CallNotifier";

    private static final String CH_SHADE = "call_shade";
    private static final String CH_FULL = "call_full_screen";
    /** Banner mode: high importance so a banner drops, but no full-screen intent. */
    private static final String CH_BANNER = "call_banner";

    private static final int ID_SHADE = 4201;
    private static final int ID_FULL = 4202;

    /** How long the call screen is given to come forward on its own. */
    private static final long FULLSCREEN_FALLBACK_MS = 900;

    private static CallNotifier current;

    private final Service service;
    private final NotificationManager nm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, String> names = new HashMap<>();

    private boolean foreground;
    private boolean fullScreenShown;
    /**
     * The channel is picked once per call: Android does not allow changing the
     * channel of a notification that has already been posted.
     */
    private String callChannel;
    /** The full-screen fallback is evaluated once per call. */
    private boolean fullScreenEvaluated;
    /**
     * Android 14+ only accepts a CallStyle notification from a foreground
     * service or with a full-screen intent; if it is rejected we fall back to a
     * plain layout and try again.
     */
    private boolean callStyleOk = true;

    CallNotifier(Service service) {
        this.service = service;
        this.nm = service.getSystemService(NotificationManager.class);
    }

    // ------------------------------------------------------------------ lifecycle

    void attach() {
        current = this;
        createChannels();
    }

    void detach() {
        clear();
        io.shutdownNow();
        if (current == this) current = null;
    }

    /** The call screen came forward, so the full-screen notification is no longer needed. */
    public static void onCallScreenVisible() {
        CallNotifier c = current;
        if (c == null) return;
        c.ui.post(c::hideFullScreen);
    }

    /** The call screen closed but the call goes on: the card has to stay in the shade. */
    public static void refresh() {
        CallNotifier c = current;
        if (c != null) c.ui.post(c::update);
    }

    private void createChannels() {
        if (nm == null) return;

        // The single channel of an older version: unused now, don't leave it in settings
        try {
            nm.deleteNotificationChannel("incoming_call");
        } catch (Exception ignored) {
        }

        NotificationChannel shade = new NotificationChannel(CH_SHADE,
                "Call card", NotificationManager.IMPORTANCE_LOW);
        shade.setDescription("The call card in the notification shade; never drops a banner");
        shade.setSound(null, null);
        shade.enableVibration(false);
        shade.setShowBadge(false);
        nm.createNotificationChannel(shade);

        NotificationChannel banner = new NotificationChannel(CH_BANNER,
                "Incoming call (banner)", NotificationManager.IMPORTANCE_HIGH);
        banner.setDescription("Shows an incoming call as a strip at the top of the screen");
        banner.setSound(null, null);        // Telecom plays the ringtone
        banner.enableVibration(false);
        nm.createNotificationChannel(banner);

        NotificationChannel full = new NotificationChannel(CH_FULL,
                "Incoming call screen", NotificationManager.IMPORTANCE_HIGH);
        full.setDescription("Steps in when the call screen cannot open by itself");
        full.setSound(null, null);          // Telecom plays the ringtone; don't double it up
        full.enableVibration(false);
        nm.createNotificationChannel(full);
    }

    // ------------------------------------------------------------------ updating

    /** Builds or refreshes the notification for the current call state. */
    void update() {
        Call call = CallManager.primary();
        if (call == null) {
            clear();
            return;
        }
        int state = CallManager.stateOf(call);
        if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
            clear();
            return;
        }
        String number = CallManager.numberOf(call);
        resolveName(number);

        // The service is brought to the foreground once, but the notification is
        // re-posted EVERY time: the card has to update when the name arrives
        // from the address book and on the "ringing -> in call" transition. It
        // used to be posted only the first time, which is why even a saved
        // contact stayed shown as a bare number.
        if (callChannel == null) callChannel = chooseChannel(state);
        if (!foreground) foreground = startForeground(call);
        post(ID_SHADE, call, callChannel, false);

        // In banner mode we never open the call screen ourselves; the banner is enough
        if (state == Call.STATE_RINGING && CH_SHADE.equals(callChannel)) {
            scheduleFullScreenFallback();
        } else {
            hideFullScreen();
        }
    }

    /**
     * Brings the service to the foreground.
     *
     * It uses {@link #callChannel} as the channel: this is where the
     * notification is posted for the first time, and Android will not change
     * the channel of a posted notification afterwards. Hard-coding CH_SHADE
     * meant banner mode could never drop a banner at all.
     */
    private boolean startForeground(Call call) {
        String channel = callChannel != null ? callChannel : CH_SHADE;
        Notification n = build(call, channel, false, callStyleOk);
        if (n == null) return false;
        try {
            attachToService(n);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not start the foreground service", e);
        }
        // It may have been rejected because of CallStyle: try once more, plain
        if (callStyleOk) {
            callStyleOk = false;
            Notification plain = build(call, channel, false, false);
            if (plain != null) {
                try {
                    attachToService(plain);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "the plain notification would not start it either", e);
                }
            }
        }
        return false;
    }

    private void attachToService(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.startForeground(ID_SHADE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
        } else {
            service.startForeground(ID_SHADE, n);
        }
    }

    /** Posts the notification, falling back to a plain layout if CallStyle is rejected. */
    private void post(int id, Call call, String channel, boolean fullScreen) {
        if (nm == null) return;
        Notification n = build(call, channel, fullScreen, callStyleOk);
        if (n == null) return;
        try {
            nm.notify(id, n);
        } catch (Exception e) {
            Log.w(TAG, "CallStyle notification was not accepted, falling back to plain", e);
            callStyleOk = false;
            Notification plain = build(call, channel, fullScreen, false);
            if (plain == null) return;
            try {
                nm.notify(id, plain);
            } catch (Exception e2) {
                Log.e(TAG, "could not post the notification", e2);
            }
        }
    }

    /**
     * How should an incoming call be shown?
     *
     * If the user asked for a banner we switch to banner mode - but only while
     * the screen is on and unlocked. With the screen off or locked a banner
     * would not be seen, so full screen is still the right answer there.
     */
    private String chooseChannel(int state) {
        if (state != Call.STATE_RINGING) return CH_SHADE;
        if (Prefs.incomingFullScreen(service)) return CH_SHADE;
        return screenUsable() ? CH_BANNER : CH_SHADE;
    }

    private boolean screenUsable() {
        try {
            android.os.PowerManager pm = service.getSystemService(android.os.PowerManager.class);
            android.app.KeyguardManager km =
                    service.getSystemService(android.app.KeyguardManager.class);
            boolean on = pm == null || pm.isInteractive();
            boolean locked = km != null && km.isKeyguardLocked();
            return on && !locked;
        } catch (Exception e) {
            return false;
        }
    }

    /** Should the call screen open by itself? Not in banner mode. */
    static boolean shouldOpenScreen(Context ctx) {
        if (Prefs.incomingFullScreen(ctx)) return true;
        try {
            android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
            android.app.KeyguardManager km = ctx.getSystemService(android.app.KeyguardManager.class);
            boolean on = pm == null || pm.isInteractive();
            boolean locked = km != null && km.isKeyguardLocked();
            return !(on && !locked);          // screen off or locked: full screen after all
        } catch (Exception e) {
            return true;
        }
    }

    void clear() {
        ui.removeCallbacksAndMessages(null);
        hideFullScreen();
        fullScreenEvaluated = false;
        callChannel = null;
        if (foreground) {
            try {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } catch (Exception ignored) {
            }
            foreground = false;
        }
        if (nm != null) nm.cancel(ID_SHADE);
    }

    // ------------------------------------------------------------------ full-screen fallback

    private void scheduleFullScreenFallback() {
        if (fullScreenEvaluated) return;
        fullScreenEvaluated = true;
        ui.postDelayed(() -> {
            // If the user deliberately left the call screen (to glance at
            // WhatsApp, say) we don't force it back: this check runs only once
            // per call.
            if (InCallActivity.isVisible()) return;
            Call call = CallManager.primary();
            if (call == null || CallManager.stateOf(call) != Call.STATE_RINGING) return;

            fullScreenShown = true;
            post(ID_FULL, call, CH_FULL, true);
            Log.d(TAG, "the call screen did not come forward, posted a full-screen notification");
        }, FULLSCREEN_FALLBACK_MS);
    }

    private void hideFullScreen() {
        if (!fullScreenShown) return;
        fullScreenShown = false;
        if (nm != null) nm.cancel(ID_FULL);
    }

    // ------------------------------------------------------------------ building

    private Notification build(Call call, String channel, boolean withFullScreen,
                               boolean useCallStyle) {
        Context ctx = service;
        int state = CallManager.stateOf(call);
        boolean ringing = state == Call.STATE_RINGING;
        String number = CallManager.numberOf(call);
        String who = displayName(number);

        PendingIntent open = activityIntent(ctx, 10);
        PendingIntent answer = broadcast(ctx, 11, CallActionReceiver.ACTION_ANSWER);
        PendingIntent reject = broadcast(ctx, 12, CallActionReceiver.ACTION_REJECT);
        PendingIntent hangup = broadcast(ctx, 13, CallActionReceiver.ACTION_HANGUP);

        Notification.Builder b = new Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_phone)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open);

        if (withFullScreen) b.setFullScreenIntent(open, true);

        long connect = call.getDetails().getConnectTimeMillis();
        if (!ringing && connect > 0) {
            b.setWhen(connect).setUsesChronometer(true).setShowWhen(true);
        }

        if (useCallStyle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Person person = new Person.Builder().setName(who).setImportant(true).build();
            b.setStyle(ringing
                    ? Notification.CallStyle.forIncomingCall(person, reject, answer)
                    : Notification.CallStyle.forOngoingCall(person, hangup));
        } else {
            b.setContentTitle(who).setContentText(stateText(ctx, state));
            if (ringing) {
                b.addAction(action(ctx, R.drawable.ic_call_end, "Reject", reject));
                b.addAction(action(ctx, R.drawable.ic_phone, "Answer", answer));
            } else {
                b.addAction(action(ctx, R.drawable.ic_call_end, "Hang up", hangup));
            }
        }
        try {
            return b.build();
        } catch (Exception e) {
            Log.e(TAG, "could not build the notification", e);
            return null;
        }
    }

    private static String stateText(Context ctx, int state) {
        switch (state) {
            case Call.STATE_RINGING: return ctx.getString(R.string.state_incoming);
            case Call.STATE_DIALING: return ctx.getString(R.string.state_dialing);
            case Call.STATE_CONNECTING: return ctx.getString(R.string.state_connecting);
            case Call.STATE_HOLDING: return ctx.getString(R.string.state_holding);
            default: return ctx.getString(R.string.state_active);
        }
    }

    private static Notification.Action action(Context ctx, int iconRes, String label,
                                              PendingIntent pi) {
        return new Notification.Action.Builder(
                Icon.createWithResource(ctx, iconRes), label, pi).build();
    }

    private static PendingIntent activityIntent(Context ctx, int req) {
        Intent i = new Intent(ctx, InCallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent broadcast(Context ctx, int req, String action) {
        Intent i = new Intent(ctx, CallActionReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ------------------------------------------------------------------ names

    private String displayName(String number) {
        if (number == null || number.isEmpty()) {
            return service.getString(R.string.unknown_number);
        }
        String cached = names.get(PhoneUtil.key(number));
        if (cached != null) return cached;
        return PhoneUtil.pretty(number);
    }

    /** Resolves the name from the address book in the background and re-posts. */
    private void resolveName(String number) {
        if (number == null || number.isEmpty()) return;
        final String key = PhoneUtil.key(number);
        if (key.isEmpty() || names.containsKey(key)) return;
        names.put(key, PhoneUtil.pretty(number));   // so the same number is not looked up twice
        io.execute(() -> {
            Contact c = ContactsRepo.lookupByNumber(service.getContentResolver(), number);
            if (c == null || c.displayName().isEmpty()) return;
            names.put(key, c.displayName());
            ui.post(this::update);
        });
    }
}
