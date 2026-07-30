package com.capman.dialer;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.os.SystemClock;
import android.view.animation.OvershootInterpolator;
import android.telecom.Call;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The call screen.
 *
 * An incoming call brings up the chomper strip: drag the CHOMPER onto the green
 * handset to answer, drag the SKULL onto the chomper to reject. During a call
 * the mute, speaker, keypad, note, hold and contact buttons appear.
 *
 * Once a call starts the proximity sensor takes over: the screen blanks when
 * the phone is held to an ear, so a cheek cannot press anything.
 */
public class InCallActivity extends BaseActivity implements CallManager.Listener {

    private static final String TAG = "InCallActivity";

    /** For trying the screen without a real call, from "Try the incoming call screen". */
    public static final String EX_PREVIEW = "preview";

    /** Is the call screen currently in the foreground? The notification side reads this. */
    private static volatile boolean visible;

    public static boolean isVisible() {
        return visible;
    }

    public static Intent previewIntent(android.content.Context ctx) {
        Intent i = new Intent(ctx, InCallActivity.class);
        i.putExtra(EX_PREVIEW, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private boolean preview = false;
    private long previewStart = 0;
    private boolean previewAnswered = false;

    private TextView stateText, callerName, callerNumber, timer, avatar, recordingBadge, ikDisplay;
    private TextView quickReply, otherCallInfo, callerHint, callerNote;
    private View recordRow, ikSave;
    private ImageView avatarPhoto;
    private View incomingPanel, activePanel, keypadPanel, waBadge;
    private ImageView waPhoto, waIcon;
    private ChompSwipeView chomper;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final CallRecorder recorder = new CallRecorder();

    private PowerManager.WakeLock proximity;
    private MotionGestures gestures;

    private String number;
    private String peerName;
    private boolean finishing = false;
    private boolean speakerWasOnBeforeRecording = false;
    private boolean waChecked = false;
    private boolean answerAnimDone = false;
    private boolean answerAnimRunning = false;
    private final StringBuilder dtmf = new StringBuilder();
    /** Number to display name, so the person on the second line can be named. */
    private final Map<String, String> nameCache = new HashMap<>();

    /**
     * "Optimistic" button states. The screen is repainted the instant you
     * touch; the system's real state (CallAudioState) arrives a few hundred
     * milliseconds later. Our value wins for that window and then hands over to
     * reality.
     */
    private static final long OPTIMISTIC_MS = 1500;
    private Boolean optMute, optSpeaker, optHold;
    private long optMuteAt, optSpeakerAt, optHoldAt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTimer();
            ui.postDelayed(this, 500);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preview = getIntent() != null && getIntent().getBooleanExtra(EX_PREVIEW, false);
        showOverLockScreen();
        setContentView(R.layout.activity_incall);
        bindViews();
        applyLookAndFeel();
        setupProximity();
        setupGestures();
        setupRecorder();

        if (!preview) CallManager.addListener(this);
        update();
        ui.post(tick);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        // The screen came forward, so the full-screen fallback is no longer needed
        CallNotifier.onCallScreenVisible();
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CallManager.removeListener(this);
        ui.removeCallbacks(tick);
        if (recorder.isRecording()) recorder.stop(this);
        recorder.shutdown();
        releaseProximity(true);
        if (gestures != null) gestures.stop();
        io.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (preview) {
            finish();
            return;
        }
        // The back key must not escape a real call screen, or it closes by accident.
        moveTaskToBack(true);
    }

    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /**
     * The background and theme the user picked. The background is built for the
     * screen size; with a photo, cropping and blurring happen here.
     */
    private void applyLookAndFeel() {
        View root = findViewById(R.id.incallRoot);
        if (root != null) {
            root.setBackground(CallBackground.current(this,
                    getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels));
        }
        if (chomper != null) chomper.setTheme(Prefs.callTheme(this));
    }

    private void bindViews() {
        stateText = findViewById(R.id.stateText);
        callerName = findViewById(R.id.callerName);
        callerNumber = findViewById(R.id.callerNumber);
        timer = findViewById(R.id.timer);
        avatar = findViewById(R.id.avatar);
        avatarPhoto = findViewById(R.id.avatarPhoto);
        recordingBadge = findViewById(R.id.recordingBadge);
        incomingPanel = findViewById(R.id.incomingPanel);
        activePanel = findViewById(R.id.activePanel);
        keypadPanel = findViewById(R.id.incallKeypad);
        ikDisplay = findViewById(R.id.ikDisplay);
        chomper = findViewById(R.id.chomper);
        quickReply = findViewById(R.id.quickReply);
        otherCallInfo = findViewById(R.id.otherCallInfo);
        waBadge = findViewById(R.id.waBadge);
        waPhoto = findViewById(R.id.waPhoto);
        waIcon = findViewById(R.id.waIcon);
        callerHint = findViewById(R.id.callerHint);
        callerNote = findViewById(R.id.callerNote);
        recordRow = findViewById(R.id.recordRow);
        ikSave = findViewById(R.id.ikSave);

        chomper.setOnActionListener(new ChompSwipeView.OnActionListener() {
            @Override
            public void onAnswer() {
                playAnswerTransition();
                if (preview) {
                    previewAnswered = true;
                    previewStart = System.currentTimeMillis();
                    update();
                    return;
                }
                CallManager.answer();
            }

            @Override
            public void onReject() {
                // The screen closes the moment the death animation ends. The
                // disconnect is already under way in the background and the
                // screen has no reason to wait for it, which keeps it snappy.
                long closeAt = ChompSwipeView.DEATH_MS + 60;
                if (preview) {
                    ui.postDelayed(InCallActivity.this::finish, closeAt);
                    return;
                }
                CallManager.hangupOrReject();
                finishSoon(closeAt);
            }
        });

        // With a second call waiting, the first button becomes "End and answer"
        quickReply.setOnClickListener(v -> {
            if (!preview && CallManager.secondary() != null) endCurrentAndAnswer();
            else sendQuickReply();
        });
        waBadge.setOnClickListener(v -> openWhatsApp());
        findViewById(R.id.rejectRemind).setOnClickListener(v -> askRemind(true));
        findViewById(R.id.silenceRemind).setOnClickListener(v -> askRemind(false));
        findViewById(R.id.hangup).setOnClickListener(v -> endCall());
        findViewById(R.id.ikHangup).setOnClickListener(v -> endCall());

        // Buttons repaint first and the real work follows, so nothing feels laggy
        findViewById(R.id.actMute).setOnClickListener(v -> {
            pressPulse(R.id.actMuteIcon);
            if (preview) return;
            boolean next = !muteOn();
            optMute = next;
            optMuteAt = SystemClock.uptimeMillis();
            update();
            CallManager.setMuted(next);
        });
        findViewById(R.id.actSpeaker).setOnClickListener(v -> {
            pressPulse(R.id.actSpeakerIcon);
            if (preview) return;
            // With a Bluetooth device around, toggling is not enough: ask where to
            if (AudioRoutes.needsPicker()) {
                showAudioRoutePicker();
                return;
            }
            boolean next = !speakerOn();
            optSpeaker = next;
            optSpeakerAt = SystemClock.uptimeMillis();
            update();
            CallManager.setSpeaker(next);
        });
        findViewById(R.id.actHold).setOnClickListener(v -> {
            pressPulse(R.id.actHoldIcon);
            if (preview) return;
            // With two calls this button means "swap", not "hold"
            if (CallManager.secondary() != null) {
                CallManager.swap();
            } else {
                boolean next = !holdOn();
                optHold = next;
                optHoldAt = SystemClock.uptimeMillis();
                update();
                CallManager.setHold(next);
            }
            update();
        });

        // The record row only stays if it can actually do something (root / priv-app)
        recordRow.setVisibility(
                RootUtil.callRecordingPossible(this) ? View.VISIBLE : View.GONE);

        findViewById(R.id.actNote).setOnClickListener(v -> openNote());
        ikSave.setOnClickListener(v -> saveTypedNumber());
        findViewById(R.id.actRecord).setOnClickListener(v -> {
            if (preview) {
                Toast.makeText(this, "Recording only works during a real call",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            toggleRecording();
        });
        findViewById(R.id.actKeypad).setOnClickListener(v -> showKeypad(true));
        findViewById(R.id.ikHide).setOnClickListener(v -> showKeypad(false));
        findViewById(R.id.actInfo).setOnClickListener(v -> openContactInfo());

        setupKeypadKeys();
    }

    private void setupKeypadKeys() {
        int[] ids = {R.id.ikey0, R.id.ikey1, R.id.ikey2, R.id.ikey3, R.id.ikey4,
                R.id.ikey5, R.id.ikey6, R.id.ikey7, R.id.ikey8, R.id.ikey9,
                R.id.ikeyStar, R.id.ikeyHash};
        char[] chars = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '#'};
        for (int i = 0; i < ids.length; i++) {
            final char c = chars[i];
            findViewById(ids[i]).setOnClickListener(v -> {
                CallManager.playDtmf(c);
                dtmf.append(c);
                ikDisplay.setText(dtmf.toString());
                // After 3 digits the "save to contacts" button appears
                ikSave.setVisibility(dtmf.length() >= 3 ? View.VISIBLE : View.INVISIBLE);
            });
        }
    }

    // ------------------------------------------------------------------ proximity sensor

    private void setupProximity() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm == null) return;
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                Log.w(TAG, "this device has no proximity wake lock");
                return;
            }
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "capman:proximity");
            proximity.setReferenceCounted(false);
        } catch (Exception e) {
            Log.w(TAG, "could not create the proximity lock", e);
        }
    }

    // ------------------------------------------------------------------ motion shortcuts

    private void setupGestures() {
        gestures = new MotionGestures(this, new MotionGestures.Listener() {
            @Override
            public void onRaisedToEar() {
                ui.post(() -> {
                    if (preview) {
                        if (previewAnswered) return;
                        previewAnswered = true;
                        previewStart = System.currentTimeMillis();
                        tick();
                        update();
                        return;
                    }
                    if (CallManager.stateOf(CallManager.primary()) != Call.STATE_RINGING) return;
                    tick();
                    CallManager.answer();
                });
            }

            @Override
            public void onFlippedFaceDown() {
                ui.post(() -> {
                    if (preview) {
                        tick();                 // denemede de hissedilsin
                        return;
                    }
                    if (CallManager.stateOf(CallManager.primary()) != Call.STATE_RINGING) return;
                    tick();
                    CallManager.silenceRinger();
                });
            }
        });
    }

    // ------------------------------------------------------------------ answer animation

    /**
     * On answering, the green handset the chomper just swallowed turns red,
     * travels to where the "hang up" button lives and becomes it. The other
     * buttons pop into place like bubbles.
     */
    private void playAnswerTransition() {
        if (answerAnimDone) return;
        answerAnimDone = true;
        answerAnimRunning = true;

        final View root = findViewById(R.id.incallRoot);
        final View morph = findViewById(R.id.morphBubble);
        final View hangup = findViewById(R.id.hangup);
        if (root == null || morph == null || hangup == null) return;

        final int[] r = new int[2], p = new int[2];
        root.getLocationInWindow(r);
        chomper.getLocationInWindow(p);
        final float startCx = p[0] - r[0] + chomper.answerCenterX();
        final float startCy = p[1] - r[1] + chomper.answerCenterY();
        final float startScale = (2f * chomper.answerRadius()) / morph.getLayoutParams().width;

        // Confetti lives in its own layer so hiding the incoming panel cannot take
        // it away. The hang-up button may not be measured yet, so wait for layout.
        final android.view.ViewGroup rootGroup = (android.view.ViewGroup) root;
        final int themeNow = Prefs.callTheme(this);
        hangup.post(() -> {
            int[] h = new int[2], rr = new int[2];
            hangup.getLocationInWindow(h);
            root.getLocationInWindow(rr);
            float sinkX = h[0] - rr[0] + hangup.getWidth() / 2f;
            float sinkY = h[1] - rr[1] + hangup.getHeight() / 2f;
            CelebrationView.launch(rootGroup, themeNow, startCx, startCy,
                    sinkX, sinkY, chomper.answerRadius());
        });

        incomingPanel.setVisibility(View.GONE);
        activePanel.setVisibility(View.VISIBLE);
        hangup.setAlpha(0f);

        final android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(CallThemes.answerColor(Prefs.callTheme(this)));
        morph.setBackground(circle);
        morph.setVisibility(View.VISIBLE);
        morph.setX(startCx - morph.getLayoutParams().width / 2f);
        morph.setY(startCy - morph.getLayoutParams().height / 2f);
        morph.setScaleX(startScale);
        morph.setScaleY(startScale);

        // Let the buttons pop into place
        int[] cells = {R.id.actMute, R.id.actKeypad, R.id.actSpeaker,
                R.id.actNote, R.id.actHold, R.id.actInfo};
        for (int i = 0; i < cells.length; i++) {
            popIn(findViewById(cells[i]), 90 + i * 45);
        }

        // The hang-up button's position is only known once layout is done
        hangup.post(() -> {
            int[] h = new int[2];
            hangup.getLocationInWindow(h);
            float endCx = h[0] - r[0] + hangup.getWidth() / 2f;
            float endCy = h[1] - r[1] + hangup.getHeight() / 2f;

            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(430);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
            final android.animation.ArgbEvaluator argb = new android.animation.ArgbEvaluator();
            final int from = getColor(R.color.green), to = getColor(R.color.red);
            a.addUpdateListener(v -> {
                float f = (float) v.getAnimatedValue();
                float cx = startCx + (endCx - startCx) * f;
                float cy = startCy + (endCy - startCy) * f;
                morph.setX(cx - morph.getWidth() / 2f);
                morph.setY(cy - morph.getHeight() / 2f);
                float s = startScale + (1f - startScale) * f;
                morph.setScaleX(s);
                morph.setScaleY(s);
                circle.setColor((int) argb.evaluate(f, from, to));
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    hangup.setAlpha(1f);
                    morph.setVisibility(View.GONE);
                    answerAnimRunning = false;
                }
            });
            a.start();
        });
    }

    /** A bubble-like pop-in. */
    private void popIn(View v, long delayMs) {
        if (v == null) return;
        v.setScaleX(0f);
        v.setScaleY(0f);
        v.animate().cancel();
        v.animate().scaleX(1f).scaleY(1f).setStartDelay(delayMs).setDuration(300)
                .setInterpolator(new OvershootInterpolator(2.0f)).start();
    }

    /**
     * A short buzz. Motion shortcuts show no text on screen - with the phone at
     * your ear you cannot see it anyway - so this is the only feedback.
     */
    private void tick() {
        try {
            android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
            if (v != null && v.hasVibrator()) {
                v.vibrate(android.os.VibrationEffect.createOneShot(
                        30, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) {
        }
    }

    /** Motion shortcuts are only listened for while the phone is RINGING. */
    private void applyGestures(boolean ringing) {
        if (gestures == null) return;
        if (ringing) gestures.start(Prefs.earAnswer(this), Prefs.flipSilence(this));
        else gestures.stop();
    }

    /**
     * Hands the screen over to the proximity sensor during a call: it blanks as
     * the phone nears an ear and comes back as it moves away.
     */
    private void applyProximity(boolean shouldHold) {
        if (proximity == null) return;
        boolean wanted = shouldHold && Prefs.proximityOff(this) && !preview;
        try {
            if (wanted && !proximity.isHeld()) {
                proximity.acquire();
                Log.d(TAG, "proximity lock acquired");
            } else if (!wanted && proximity.isHeld()) {
                releaseProximity(false);
                Log.d(TAG, "proximity lock released");
            }
        } catch (Exception e) {
            Log.w(TAG, "could not change the proximity lock", e);
        }
    }

    private void releaseProximity(boolean waitForNoProximity) {
        if (proximity == null || !proximity.isHeld()) return;
        try {
            // "Wait for no proximity" on release, so the screen is never left blank
            proximity.release(waitForNoProximity
                    ? PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY : 0);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ state

    @Override
    public void onCallsChanged() {
        ui.post(this::update);
    }

    @Override
    public void onAudioChanged() {
        ui.post(this::update);
    }

    private void endCall() {
        // Visual reaction first: the button collapses and fades, the text says "ended"
        View h = findViewById(R.id.hangup);
        if (h != null && h.getVisibility() == View.VISIBLE) {
            h.animate().cancel();
            h.animate().scaleX(0.78f).scaleY(0.78f).alpha(0.45f).setDuration(130).start();
        }
        stateText.setText(R.string.state_ended);

        if (preview) {
            ui.postDelayed(this::finish, 130);
            return;
        }
        CallManager.hangupOrReject();
    }

    // ------------------------------------------------------------------ button feedback

    /** A scale animation that pops the moment you touch. */
    private void pressPulse(int viewId) {
        View v = findViewById(viewId);
        if (v == null) return;
        v.animate().cancel();
        v.setScaleX(0.84f);
        v.setScaleY(0.84f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(240)
                .setInterpolator(new OvershootInterpolator(2.4f)).start();
    }

    private boolean fresh(long at) {
        return SystemClock.uptimeMillis() - at < OPTIMISTIC_MS;
    }

    private boolean muteOn() {
        return (optMute != null && fresh(optMuteAt)) ? optMute : CallManager.isMuted();
    }

    private boolean speakerOn() {
        return (optSpeaker != null && fresh(optSpeakerAt)) ? optSpeaker : CallManager.isSpeakerOn();
    }

    private boolean holdOn() {
        return (optHold != null && fresh(optHoldAt)) ? optHold : CallManager.isOnHold();
    }

    /**
     * Should the button look lit? In picker mode the question is not "is the
     * speaker on" but "is the audio going somewhere other than the earpiece".
     */
    private boolean speakerHighlight() {
        if (preview || !AudioRoutes.needsPicker()) return speakerOn();
        return CallManager.currentRoute() != android.telecom.CallAudioState.ROUTE_EARPIECE;
    }

    // ------------------------------------------------------------------ audio output

    /**
     * The "where should the sound come out?" sheet. It slides up from the bottom;
     * each row carries the device's icon, its name and a tick when it is active.
     */
    private void showAudioRoutePicker() {
        List<AudioRoutes.Option> options = AudioRoutes.list(this);
        if (options.size() < 2) return;

        View sheet = getLayoutInflater().inflate(R.layout.dialog_audio_route, null);
        LinearLayout list = sheet.findViewById(R.id.routeList);

        Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.setContentView(sheet);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setBackgroundDrawable(new ColorDrawable(0x99000000));
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
        }

        for (AudioRoutes.Option o : options) {
            View row = getLayoutInflater().inflate(R.layout.item_audio_route, list, false);
            ImageView icon = row.findViewById(R.id.routeIcon);
            TextView label = row.findViewById(R.id.routeLabel);
            icon.setImageResource(o.icon);
            icon.setColorFilter(getColor(o.active ? R.color.gold : R.color.text_dim));
            label.setText(o.label);
            label.setTextColor(getColor(o.active ? R.color.gold : R.color.text));
            row.findViewById(R.id.routeCheck)
                    .setVisibility(o.active ? View.VISIBLE : View.INVISIBLE);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                tick();
                // Repaint at once and let the real switch happen behind it
                optSpeaker = o.route == android.telecom.CallAudioState.ROUTE_SPEAKER;
                optSpeakerAt = SystemClock.uptimeMillis();
                update();
                AudioRoutes.apply(o);
            });
            list.addView(row);
        }
        dialog.show();
    }

    /** For trying the screen without a real call. */
    private void updatePreview() {
        callerName.setText("John Wick");
        callerNumber.setText(PhoneUtil.pretty("05551234567"));
        avatar.setText("J");
        setupWhatsAppShortcut("05551234567");

        boolean ringing = !previewAnswered;
        applyGestures(ringing);          // the motion shortcuts work in the preview too
        incomingPanel.setVisibility(ringing ? View.VISIBLE : View.GONE);
        activePanel.setVisibility(ringing ? View.GONE : View.VISIBLE);
        stateText.setText(ringing ? R.string.state_incoming : R.string.state_active);
        timer.setVisibility(ringing ? View.GONE : View.VISIBLE);
        recordingBadge.setVisibility(View.GONE);

        paintToggle(R.id.actMuteIcon, false);
        paintToggle(R.id.actSpeakerIcon, false);
        paintToggle(R.id.actHoldIcon, false);
        paintToggle(R.id.actRecordIcon, false);
        updateTimer();
    }

    private void update() {
        if (preview) {
            updatePreview();
            return;
        }
        Call call = CallManager.primary();
        if (call == null) {
            finishSoon();
            return;
        }
        int state = CallManager.stateOf(call);

        String n = CallManager.numberOf(call);
        if (n != null && !n.equals(number)) {
            number = n;
            waChecked = false;
            resolvePeer(n);
            loadCallerContext(n);
        }
        if (peerName == null) {
            callerName.setText(number == null || number.isEmpty()
                    ? getString(R.string.unknown_number) : PhoneUtil.pretty(number));
            callerNumber.setText("");
            avatar.setText(PhoneUtil.initials(number));
        }

        boolean ringing = state == Call.STATE_RINGING;
        incomingPanel.setVisibility(ringing ? View.VISIBLE : View.GONE);
        activePanel.setVisibility(ringing ? View.GONE : View.VISIBLE);
        if (ringing && keypadPanel.getVisibility() == View.VISIBLE) showKeypad(false);
        bindSecondCall(ringing);
        applyGestures(ringing);

        // Blank the screen when it goes to an ear. Not active while a call is
        // RINGING (you may be looking at the phone), but active the moment you
        // dial someone - you can put the phone to your ear before they pick
        // up.
        applyProximity(state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING
                || state == Call.STATE_DIALING || state == Call.STATE_CONNECTING);

        switch (state) {
            case Call.STATE_RINGING:
                stateText.setText(R.string.state_incoming);
                timer.setVisibility(View.GONE);
                break;
            case Call.STATE_DIALING:
                stateText.setText(R.string.state_dialing);
                timer.setVisibility(View.GONE);
                break;
            case Call.STATE_CONNECTING:
                stateText.setText(R.string.state_connecting);
                timer.setVisibility(View.GONE);
                break;
            case Call.STATE_HOLDING:
                stateText.setText(R.string.state_holding);
                timer.setVisibility(View.VISIBLE);
                break;
            case Call.STATE_ACTIVE:
                stateText.setText(R.string.state_active);
                timer.setVisibility(View.VISIBLE);
                break;
            case Call.STATE_DISCONNECTED:
            case Call.STATE_DISCONNECTING:
                stateText.setText(R.string.state_ended);
                // If a second call is still running, switch to it instead of closing
                if (CallManager.liveCount() == 0) {
                    finishSoon();
                    return;
                }
                break;
            default:
                break;
        }

        // Even if the answer animation is cut short, the hang-up button stays visible
        View hangupBtn = findViewById(R.id.hangup);
        if (!answerAnimRunning && hangupBtn != null && hangupBtn.getAlpha() < 1f
                && findViewById(R.id.morphBubble).getVisibility() != View.VISIBLE) {
            hangupBtn.setAlpha(1f);
        }

        boolean busy = recorder.isRecording() || recorder.isStarting();
        paintToggle(R.id.actMuteIcon, muteOn());
        paintToggle(R.id.actSpeakerIcon, speakerHighlight());
        paintToggle(R.id.actHoldIcon, holdOn());
        paintToggle(R.id.actRecordIcon, busy);

        boolean twoCalls = CallManager.secondary() != null;
        ((TextView) findViewById(R.id.actMuteLabel))
                .setText(muteOn() ? "Muted" : "Mute");
        ((TextView) findViewById(R.id.actHoldLabel))
                .setText(twoCalls ? "Swap"
                        : holdOn() ? "Resume" : "Hold");
        ((ImageView) findViewById(R.id.actHoldIcon)).setImageResource(
                twoCalls ? R.drawable.ic_swap_calls : R.drawable.ic_pause);
        paintSpeakerButton();
        ((TextView) findViewById(R.id.actRecordLabel))
                .setText(recorder.isStarting() ? "Starting…"
                        : recorder.isRecording() ? "Stop recording" : "Record");
        ((ImageView) findViewById(R.id.actRecordIcon))
                .setImageResource(busy ? R.drawable.ic_stop : R.drawable.ic_record);
        updateRecordingBadge();

        updateTimer();
    }

    private void updateRecordingBadge() {
        if (!recorder.isRecording()) {
            recordingBadge.setVisibility(View.GONE);
            return;
        }
        recordingBadge.setVisibility(View.VISIBLE);
        recordingBadge.setText(recorder.isSilent()
                ? "● RECORDING · NO AUDIO" : "● RECORDING");
    }

    /**
     * The speaker button.
     *
     * With no Bluetooth device it stays the plain "Speaker" on/off button it
     * always was. With one connected it becomes a PICKER: it shows the name and
     * icon of whatever the sound is currently coming out of, and opens the list
     * on tap.
     */
    private void paintSpeakerButton() {
        ImageView icon = findViewById(R.id.actSpeakerIcon);
        TextView label = findViewById(R.id.actSpeakerLabel);
        if (icon == null || label == null) return;

        if (preview || !AudioRoutes.needsPicker()) {
            icon.setImageResource(R.drawable.ic_speaker);
            label.setText("Speaker");
            return;
        }
        int route = CallManager.currentRoute();
        icon.setImageResource(
                route == android.telecom.CallAudioState.ROUTE_BLUETOOTH ? R.drawable.ic_bluetooth
                        : route == android.telecom.CallAudioState.ROUTE_WIRED_HEADSET
                        ? R.drawable.ic_headset
                        : route == android.telecom.CallAudioState.ROUTE_SPEAKER
                        ? R.drawable.ic_speaker : R.drawable.ic_phone);
        label.setText(AudioRoutes.currentLabel(this));
    }

    /** Show the on/off state through the button's colour. */
    private void paintToggle(int iconId, boolean on) {
        ImageView v = findViewById(iconId);
        v.setBackgroundResource(on ? R.drawable.bg_incall_action_on : R.drawable.bg_incall_action);
        v.setColorFilter(getColor(on ? R.color.bg : R.color.text));
    }

    private void updateTimer() {
        long start;
        if (preview) {
            start = previewStart;
        } else {
            Call call = CallManager.primary();
            if (call == null) return;
            start = call.getDetails().getConnectTimeMillis();
        }
        if (start <= 0) {
            timer.setText("");
            return;
        }
        long sec = Math.max(0, (System.currentTimeMillis() - start) / 1000);
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        timer.setText(h > 0
                ? String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
                : String.format(Locale.getDefault(), "%02d:%02d", m, s));
    }

    private void resolvePeer(String n) {
        io.execute(() -> {
            final Contact c = ContactsRepo.lookupByNumber(getContentResolver(), n);
            ui.post(() -> {
                if (c == null) {
                    peerName = null;
                    setupWhatsAppShortcut(n);
                    return;
                }
                peerName = c.displayName();
                callerName.setText(peerName);
                callerNumber.setText(PhoneUtil.pretty(n));
                avatar.setText(PhoneUtil.initials(peerName));
                PhotoLoader.load(c.bestPhoto(true), avatarPhoto, avatar, true);
                setupWhatsAppShortcut(n);
            });
        });
    }

    /**
     * The WhatsApp badge in the corner.
     *
     * A LIMIT, verified on a device: WhatsApp never writes its profile photo to
     * the contacts provider - its rows carry the name, a profile entry and call
     * entries, but not a single "photo" row, and there is no exported avatar
     * provider either. So we cannot put the picture on screen ourselves.
     *
     * The badge is a shortcut instead: tapping it opens the caller's WhatsApp
     * chat, which is often enough to recognise an unknown number. If a photo
     * does happen to exist in the contacts provider (another app may have
     * written one) it is drawn inside the badge.
     */
    private void setupWhatsAppShortcut(String n) {
        if (waChecked || n == null || n.isEmpty()) return;
        waChecked = true;
        if (!WhatsAppUtil.isInstalled(this)) return;

        waBadge.setVisibility(View.VISIBLE);

        io.execute(() -> {
            byte[] jpeg = WhatsAppUtil.photoBytes(getContentResolver(), n);
            final Bitmap bmp = jpeg == null ? null : PhotoLoader.circleFromBytes(jpeg, 220);
            ui.post(() -> {
                if (bmp == null) return;
                waPhoto.setImageBitmap(bmp);
                waPhoto.setVisibility(View.VISIBLE);
                waIcon.setVisibility(View.GONE);
            });
        });
    }

    private void openWhatsApp() {
        String n = preview ? "05551234567" : number;
        WhatsAppUtil.openChat(this, n);
    }

    /** With two calls at once, shows who is on the other line. */
    private void bindSecondCall(boolean ringing) {
        Call other = CallManager.secondary();
        if (preview || other == null) {
            otherCallInfo.setVisibility(View.GONE);
            quickReply.setText("Mesajla\nreddet");
            return;
        }
        otherCallInfo.setVisibility(View.VISIBLE);
        otherCallInfo.setText((ringing ? "On the call: " : "On hold: ") + labelFor(other));
        // With a second call ringing, offer something more useful than "reject with message"
        quickReply.setText(ringing ? "End and\nanswer" : "Mesajla\nreddet");
    }

    /** Turns a number into a name; the lookup runs in the background and is cached. */
    private String labelFor(Call c) {
        String n = CallManager.numberOf(c);
        if (n == null || n.isEmpty()) return getString(R.string.unknown_number);
        final String key = PhoneUtil.key(n);
        String cached = nameCache.get(key);
        if (cached != null) return cached;

        nameCache.put(key, PhoneUtil.pretty(n));   // ayni numara tekrar sorulmasin
        io.execute(() -> {
            Contact found = ContactsRepo.lookupByNumber(getContentResolver(), n);
            if (found == null || found.displayName().isEmpty()) return;
            nameCache.put(key, found.displayName());
            ui.post(this::update);
        });
        return PhoneUtil.pretty(n);
    }

    private void endCurrentAndAnswer() {
        CallManager.endCurrentAndAnswer();
        Toast.makeText(this, "Previous call ended", Toast.LENGTH_SHORT).show();
    }

    private void finishSoon() {
        // On a normal hang-up, let "Call ended" show for a moment
        finishSoon(900);
    }

    private void finishSoon(long delayMs) {
        if (finishing) return;
        finishing = true;
        if (recorder.isRecording()) stopRecording();
        releaseProximity(true);
        ui.removeCallbacks(tick);
        ui.postDelayed(this::finishAndRemoveTask, delayMs);
    }

    // ------------------------------------------------------------------ actions

    private void sendQuickReply() {
        if (preview) {
            Toast.makeText(this, "The quick reply is sent during a real call",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = Prefs.quickReply(this);
        CallManager.rejectWithMessage(this, msg);
        Toast.makeText(this, "Rejected, message sent", Toast.LENGTH_SHORT).show();
    }

    /**
     * "Remind me about this call later".
     *
     * @param alsoReject true to reject the call, false to only silence the
     *                   ringer (it stays a missed call once the caller gives up).
     */
    private void askRemind(boolean alsoReject) {
        final String n = preview ? "05551234567" : number;
        if (n == null || n.isEmpty()) {
            Toast.makeText(this, "The number is withheld, no reminder can be set",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final String who = preview ? "Test call" : peerName;
        new AlertDialog.Builder(this)
                .setTitle(alsoReject ? "Reject and remind me" : "Silence and remind me")
                .setItems(Reminders.labels(), (d, which) -> {
                    int minutes = Reminders.MINUTES[which];
                    long at = Reminders.schedule(this, n, who, minutes);
                    if (at == 0) {
                        Toast.makeText(this, "Could not set the reminder", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (preview) {
                        Toast.makeText(this, "Reminder set " + Reminders.label(minutes),
                                Toast.LENGTH_LONG).show();
                        finish();
                        return;
                    }
                    if (alsoReject) {
                        CallManager.hangupOrReject();
                        Toast.makeText(this, "Rejected · reminding you "
                                + Reminders.label(minutes), Toast.LENGTH_LONG).show();
                    } else {
                        CallManager.silenceRinger();
                        Toast.makeText(this, "Ringer silenced · reminding you "
                                + Reminders.label(minutes), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showKeypad(boolean show) {
        keypadPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            dtmf.setLength(0);
            ikDisplay.setText("");
            ikSave.setVisibility(View.INVISIBLE);
        }
    }

    /** Saves the number typed on the in-call keypad to the address book. */
    private void saveTypedNumber() {
        String typed = dtmf.toString().trim();
        if (typed.length() < 3) return;
        Intent i = EditContactActivity.intentForNew(this, typed);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void openContactInfo() {
        if (number == null) return;
        Intent i = ContactDetailActivity.intentFor(this, -1, number);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /** "Add note": opens the contact screen with the note field focused and the keyboard up. */
    private void openNote() {
        String n = preview ? "05551234567" : number;
        if (n == null || n.isEmpty()) {
            Toast.makeText(this, "The number is withheld, no note can be kept", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = ContactDetailActivity.intentForNote(this, -1, n);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /** Puts the caller summary and the saved note on screen; both are read in the background. */
    private void loadCallerContext(String n) {
        callerNote.setVisibility(View.GONE);
        callerHint.setVisibility(View.GONE);
        if (n == null || n.isEmpty()) return;
        io.execute(() -> {
            final String summary = CallLogRepo.callerSummary(getContentResolver(), n);
            final String note = Notes.get(this, n);
            ui.post(() -> {
                if (summary != null && !summary.isEmpty()) {
                    callerHint.setText(summary);
                    callerHint.setVisibility(View.VISIBLE);
                }
                if (!note.trim().isEmpty()) {
                    callerNote.setText("„" + note.trim() + "”");
                    callerNote.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    // ------------------------------------------------------------------ recording

    private void setupRecorder() {
        recorder.setListener(new CallRecorder.Listener() {
            @Override
            public void onRecordingStarted(boolean silent) {
                if (silent) {
                    Toast.makeText(InCallActivity.this,
                            "Recording started but nothing is reaching the microphone. Android "
                                    + "may be restricting it during the call; keep the speaker on.",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(InCallActivity.this, "Recording started",
                            Toast.LENGTH_SHORT).show();
                }
                update();
            }

            @Override
            public void onRecordingFailed(String reason) {
                Toast.makeText(InCallActivity.this, "Could not start recording: " + reason,
                        Toast.LENGTH_LONG).show();
                restoreSpeaker();
                update();
            }

            @Override
            public void onSilenceChanged(boolean silent) {
                updateRecordingBadge();
                if (silent) {
                    Toast.makeText(InCallActivity.this,
                            "No audio is reaching the recording — turn the speaker on", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void toggleRecording() {
        if (recorder.isStarting()) return;
        if (recorder.isRecording()) {
            stopRecording();
        } else {
            startRecording();
        }
        update();
    }

    private void startRecording() {
        // The other party only reaches the microphone through the air, via the speaker.
        speakerWasOnBeforeRecording = CallManager.isSpeakerOn();
        if (Prefs.recordSpeaker(this) && !speakerWasOnBeforeRecording) {
            CallManager.setSpeaker(true);
        }
        String peer = peerName != null ? peerName : number;
        Toast.makeText(this, "Preparing to record…", Toast.LENGTH_SHORT).show();
        recorder.start(this, peer);
    }

    private void stopRecording() {
        CallRecorder.Result r = recorder.stop(this);
        restoreSpeaker();
        if (r == null) return;

        if (r.location == null) {
            Toast.makeText(this, "No recording file was produced", Toast.LENGTH_LONG).show();
        } else if (r.silent) {
            Toast.makeText(this,
                    "The recording came out silent: " + r.location + "\nAndroid does not "
                            + "give apps the call audio stream; with the speaker off, "
                            + "nothing gets in at all.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Recording: " + r.location, Toast.LENGTH_LONG).show();
        }
    }

    /** Restore the speaker if it was off before recording. */
    private void restoreSpeaker() {
        if (Prefs.recordSpeaker(this) && !speakerWasOnBeforeRecording) {
            CallManager.setSpeaker(false);
        }
    }

    // ------------------------------------------------------------------ keys

    /**
     * While ringing, a volume key silences the ringer without rejecting the
     * call, so the phone can go back in a pocket. (Rejecting is the power key,
     * pressed twice.)
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                && Prefs.volumeSilence(this)
                && CallManager.stateOf(CallManager.primary()) == Call.STATE_RINGING) {
            CallManager.silenceRinger();
            Toast.makeText(this, "Ringer silenced", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
