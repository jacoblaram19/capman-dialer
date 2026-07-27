package com.capman.dialer;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The first-run tour.
 *
 * Rather than explaining in prose it shows the screens themselves: sample lists
 * filled with made-up people, and at the end two goes on the real chomper strip
 * - reject first, then answer. Doing the gesture is what teaches it.
 *
 * No permission is needed: every bit of the content is fake.
 */
public class OnboardingActivity extends BaseActivity {

    /** Made-up callers; familiar names hold attention. */
    private static final String[][] FAKE_RECENTS = {
            {"Thor", "Today 14:20 · 3 min 12 s", "in"},
            {"Frank Castle", "Today 12:05", "missed"},
            {"Dominic Toretto", "Yesterday 21:40 · 8 min 4 s", "out"},
            {"Ellen Ripley", "Yesterday 19:12 · 45 s", "in"},
    };
    private static final String[] FAKE_FAVORITES = {"Thor", "Ripley", "Dom", "Sarah"};

    private static final int WELCOME = 0, RECENTS = 1, FAVORITES = 2, NOTE = 3,
            GESTURES = 4, TRY_REJECT = 5, TRY_ANSWER = 6;
    private static final int STEP_COUNT = 7;

    private int index = 0;
    private TextView title, body, next;
    private LinearLayout dots;
    private FrameLayout stage;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public static Intent intent(Context ctx) {
        return new Intent(ctx, OnboardingActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        title = findViewById(R.id.obTitle);
        body = findViewById(R.id.obBody);
        next = findViewById(R.id.obNext);
        dots = findViewById(R.id.obDots);
        stage = findViewById(R.id.obStage);

        findViewById(R.id.obSkip).setOnClickListener(v -> done());
        next.setOnClickListener(v -> advance());

        buildDots();
        show();
    }

    @Override
    public void onBackPressed() {
        if (index > 0) {
            index--;
            show();
            return;
        }
        super.onBackPressed();
    }

    private void advance() {
        if (index >= STEP_COUNT - 1) {
            done();
            return;
        }
        index++;
        show();
    }

    // ------------------------------------------------------------------ steps

    private void show() {
        stage.removeAllViews();
        next.setVisibility(View.VISIBLE);
        next.setText(index == STEP_COUNT - 1 ? "Done" : "Next");

        switch (index) {
            case WELCOME:
                stage.addView(bigEmoji("👋"));
                title.setText("Welcome");
                body.setText("A one-minute tour. I'll show you rather than tell you.");
                break;

            case RECENTS:
                stage.addView(mockRecents());
                title.setText("Call history");
                body.setText("Every row has a WhatsApp and an info button. "
                        + "Tapping the row places the call.");
                break;

            case FAVORITES:
                stage.addView(mockFavorites());
                title.setText("Favorites and missed calls");
                body.setText("The pencil under the star reorders your favorites. "
                        + "«Missed» shows only the ones you have not returned.");
                break;

            case NOTE:
                stage.addView(mockNote());
                title.setText("Contact notes");
                body.setText("Leave a short note on someone; you see it on screen next time they call.");
                break;

            case GESTURES:
                stage.addView(mockGestures());
                title.setText("Hands free");
                body.setText("These work while the phone is ringing. You can turn them off in settings.");
                break;

            case TRY_REJECT:
                stage.addView(callDemo(false, true));
                title.setText("Your turn: reject it");
                body.setText("Grab the skull and drag it onto the chomper.");
                next.setVisibility(View.INVISIBLE);
                break;

            case TRY_ANSWER:
                stage.addView(callDemo(true, false));
                title.setText("Last step: answer it");
                body.setText("Grab the chomper and drag it onto the green handset.");
                next.setVisibility(View.INVISIBLE);
                break;

            default:
                break;
        }

        for (int i = 0; i < dots.getChildCount(); i++) {
            dots.getChildAt(i).setAlpha(i == index ? 1f : 0.28f);
        }
        for (View v : new View[]{stage, title, body}) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(220).start();
        }
    }

    // ------------------------------------------------------------------ stages

    private View bigEmoji(String e) {
        TextView tv = new TextView(this);
        tv.setText(e);
        tv.setTextSize(72);
        tv.setGravity(Gravity.CENTER);
        tv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return tv;
    }

    /** The real row layout, filled with the made-up people. */
    private View mockRecents() {
        LinearLayout box = column();
        LayoutInflater inf = LayoutInflater.from(this);
        for (String[] row : FAKE_RECENTS) {
            View v = inf.inflate(R.layout.item_recent, box, false);
            ((TextView) v.findViewById(R.id.name)).setText(row[0]);
            ((TextView) v.findViewById(R.id.avatar)).setText(PhoneUtil.initials(row[0]));
            ((TextView) v.findViewById(R.id.sub)).setText(row[1]);

            ImageView icon = v.findViewById(R.id.typeIcon);
            boolean missed = "missed".equals(row[2]);
            icon.setImageResource(missed ? R.drawable.ic_call_missed
                    : "out".equals(row[2]) ? R.drawable.ic_call_made : R.drawable.ic_call_received);
            icon.setColorFilter(getColor(missed ? R.color.red
                    : "out".equals(row[2]) ? R.color.blue : R.color.green));
            if (missed) {
                ((TextView) v.findViewById(R.id.name)).setTextColor(getColor(R.color.red));
            }
            v.findViewById(R.id.expand).setVisibility(View.GONE);
            box.addView(v);
        }
        return box;
    }

    private View mockFavorites() {
        LinearLayout box = column();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.bg_favbar);
        bar.setPadding(dp(16), dp(4), dp(10), dp(4));

        LinearLayout starCol = new LinearLayout(this);
        starCol.setOrientation(LinearLayout.VERTICAL);
        starCol.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView star = new TextView(this);
        star.setText("⭐");
        star.setTextSize(16);
        starCol.addView(star);
        ImageView pencil = new ImageView(this);
        pencil.setImageResource(R.drawable.ic_edit);
        pencil.setColorFilter(getColor(R.color.gold));
        pencil.setPadding(dp(5), dp(5), dp(5), dp(5));
        starCol.addView(pencil, new LinearLayout.LayoutParams(dp(26), dp(26)));
        bar.addView(starCol);

        LayoutInflater inf = LayoutInflater.from(this);
        for (String name : FAKE_FAVORITES) {
            View v = inf.inflate(R.layout.item_favorite, bar, false);
            ((TextView) v.findViewById(R.id.name)).setText(name);
            ((TextView) v.findViewById(R.id.avatar)).setText(PhoneUtil.initials(name));
            bar.addView(v);
        }
        box.addView(bar);

        // Filter chips
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(14), dp(10), dp(14), 0);
        chips.addView(chip("All", true));
        chips.addView(chip("Missed 2", false));
        box.addView(chips);
        return box;
    }

    private View chip(String text, boolean active) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), 0, dp(16), 0);
        t.setBackgroundResource(active ? R.drawable.bg_pill : R.drawable.bg_tab_active);
        t.setTextColor(getColor(active ? R.color.gold : R.color.text_faint));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMarginEnd(dp(8));
        t.setLayoutParams(lp);
        return t;
    }

    /** Three gestures, three rows: a big glyph and one line each. */
    private View mockGestures() {
        LinearLayout box = column();
        box.setPadding(dp(24), 0, dp(24), 0);
        box.addView(gestureRow("👂", "Raise it to your ear", "the call is answered"));
        box.addView(gestureRow("🙃", "Turn it face down", "the ringer stops"));
        box.addView(gestureRow("🔒", "Press the power key", "it silences and the screen goes off"));
        return box;
    }

    private View gestureRow(String emoji, String what, String result) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        row.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        row.setLayoutParams(lp);

        TextView e = new TextView(this);
        e.setText(emoji);
        e.setTextSize(30);
        e.setPadding(0, 0, dp(18), 0);
        row.addView(e);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText(what);
        t1.setTextSize(16);
        t1.setTextColor(getColor(R.color.text));
        texts.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(result);
        t2.setTextSize(13);
        t2.setTextColor(getColor(R.color.gold));
        texts.addView(t2);
        row.addView(texts);
        return row;
    }

    private View mockNote() {
        LinearLayout box = column();
        box.setPadding(dp(22), dp(10), dp(22), 0);

        TextView head = new TextView(this);
        head.setText("NOTE");
        head.setTextSize(11);
        head.setLetterSpacing(0.2f);
        head.setTextColor(getColor(R.color.gold));
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setPadding(dp(4), 0, 0, dp(8));
        box.addView(head);

        TextView card = new TextView(this);
        card.setText("Calling Tuesday at 3, gave the address");
        card.setTextSize(15);
        card.setTextColor(getColor(R.color.text));
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(18), dp(16), dp(18));
        box.addView(card);

        TextView hint = new TextView(this);
        hint.setText("\n« This note appears on screen next time they call »");
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(getColor(R.color.text_faint));
        box.addView(hint);
        return box;
    }

    /** A go on the real chomper strip, with only the gesture being taught enabled. */
    private View callDemo(boolean allowAnswer, boolean allowReject) {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView state = new TextView(this);
        state.setText("Incoming call");
        state.setTextSize(12);
        state.setLetterSpacing(0.25f);
        state.setTextColor(getColor(R.color.gold));
        state.setGravity(Gravity.CENTER);
        box.addView(state);

        TextView who = new TextView(this);
        who.setText("John Wick");
        who.setTextSize(23);
        who.setTextColor(getColor(R.color.text));
        who.setGravity(Gravity.CENTER);
        who.setPadding(0, dp(8), 0, dp(2));
        box.addView(who);

        TextView num = new TextView(this);
        num.setText("0555 111 22 33");
        num.setTextSize(13);
        num.setTextColor(getColor(R.color.text_dim));
        num.setGravity(Gravity.CENTER);
        box.addView(num);

        ChompSwipeView strip = new ChompSwipeView(this);
        strip.setAllowed(allowAnswer, allowReject);
        strip.setOnActionListener(new ChompSwipeView.OnActionListener() {
            @Override
            public void onAnswer() {
                celebrate("Answered! \uD83C\uDF89");
            }

            @Override
            public void onReject() {
                celebrate("Rejected \uD83D\uDC80");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        strip.setLayoutParams(lp);
        box.addView(strip);
        return box;
    }

    /** The gesture landed: a short confirmation, then the next step. */
    private void celebrate(String message) {
        ui.post(() -> {
            title.setText(message);
            body.setText("");
        });
        ui.postDelayed(this::advance, 850);
    }

    // ------------------------------------------------------------------ helpers

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void buildDots() {
        dots.removeAllViews();
        int size = dp(7), gap = dp(5);
        for (int i = 0; i < STEP_COUNT; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(gap, 0, gap, 0);
            lp.gravity = Gravity.CENTER_VERTICAL;
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.bg_dot);
            dots.addView(dot);
        }
    }

    private void done() {
        Prefs.setOnboardingSeen(this, true);
        finish();
    }
}
