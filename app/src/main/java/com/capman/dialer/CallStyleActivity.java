package com.capman.dialer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * How the call screen looks: theme and background.
 *
 * The REAL strip sits at the top of the screen - the chosen theme and
 * background appear there at once, and it can even be dragged. That removes the
 * "save, wait for a call, dislike it, go back" loop.
 *
 * Choices are only written on Save; backing out leaves the old ones alone.
 */
public class CallStyleActivity extends BaseActivity {

    private static final int REQ_IMAGE = 401;

    private FrameLayout preview;
    private ChompSwipeView swipe;
    private LinearLayout themeRow, bgRow, blurBox;
    private TextView themeHint, blurLabel;
    private SeekBar blur;

    private int theme;
    private String presetId;
    private String imageUri;
    private int blurValue;

    public static Intent intent(Context ctx) {
        return new Intent(ctx, CallStyleActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_callstyle);

        theme = Prefs.callTheme(this);
        presetId = Prefs.callBgPreset(this);
        imageUri = Prefs.callBgImage(this);
        blurValue = Prefs.callBgBlur(this);

        preview = findViewById(R.id.preview);
        swipe = findViewById(R.id.previewSwipe);
        themeRow = findViewById(R.id.themeRow);
        bgRow = findViewById(R.id.bgRow);
        themeHint = findViewById(R.id.themeHint);
        blurBox = findViewById(R.id.blurBox);
        blurLabel = findViewById(R.id.blurLabel);
        blur = findViewById(R.id.blur);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.save).setOnClickListener(v -> save());

        // The preview strip really works: it resets after each try
        swipe.setOnActionListener(new ChompSwipeView.OnActionListener() {
            @Override
            public void onAnswer() {
                swipe.postDelayed(swipe::reset, 700);
            }

            @Override
            public void onReject() {
                swipe.postDelayed(swipe::reset, ChompSwipeView.DEATH_MS + 300);
            }
        });

        swipe.setPreviewMode(true);

        blur.setMax(CallBackground.MAX_BLUR);
        blur.setProgress(blurValue);
        blur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                blurValue = value;
                blurLabel.setText("BLUR · " + value);
                if (fromUser) paintPreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        buildThemeRow();
        buildBackgroundRow();
        paintPreview();
    }

    // ------------------------------------------------------------------ choices

    private void buildThemeRow() {
        themeRow.removeAllViews();
        for (int t : CallThemes.ALL) {
            final int id = t;
            TextView chip = new TextView(this);
            chip.setText(CallThemes.emoji(t) + "  " + CallThemes.label(t));
            chip.setTextSize(14);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), 0, dp(16), 0);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                theme = id;
                buildThemeRow();
                paintPreview();
            });
            paintChip(chip, t == theme);
            themeRow.addView(chip);
        }
        themeHint.setText(CallThemes.hint(theme));
    }

    private void paintChip(TextView chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.bg_pill : R.drawable.bg_tab_active);
        chip.setTextColor(getColor(active ? R.color.gold : R.color.text_dim));
        chip.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void buildBackgroundRow() {
        bgRow.removeAllViews();
        for (CallBackground.Preset p : CallBackground.PRESETS) {
            final String id = p.id;
            View sw = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
            lp.setMarginEnd(dp(10));
            sw.setLayoutParams(lp);
            sw.setBackground(swatch(p, imageUri == null && p.id.equals(presetId)));
            sw.setOnClickListener(v -> {
                presetId = id;
                imageUri = null;                 // picking a colour drops the photo
                buildBackgroundRow();
                paintPreview();
            });
            bgRow.addView(sw);
        }

        TextView pick = new TextView(this);
        pick.setText(imageUri == null ? "🖼  Choose photo" : "🖼  Change photo");
        pick.setTextSize(14);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        pick.setLayoutParams(lp);
        pick.setBackgroundResource(imageUri != null ? R.drawable.bg_pill : R.drawable.bg_tab_active);
        pick.setTextColor(getColor(imageUri != null ? R.color.gold : R.color.text_dim));
        pick.setOnClickListener(v -> pickImage());
        bgRow.addView(pick);
    }

    /** A small round sample of the gradient; the selected one gets a gold ring. */
    private GradientDrawable swatch(CallBackground.Preset p, boolean active) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{p.top, p.bottom});
        d.setShape(GradientDrawable.OVAL);
        d.setStroke(dp(active ? 3 : 1), getColor(active ? R.color.gold : R.color.stroke));
        return d;
    }

    private void pickImage() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open the file picker", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMAGE || res != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            // The call screen must still open the photo days later: persist the grant
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        imageUri = uri.toString();
        if (blurValue == 0) blurValue = 6;      // keep text readable over a photo
        blur.setProgress(blurValue);
        buildBackgroundRow();
        paintPreview();
    }

    // ------------------------------------------------------------------ preview

    private void paintPreview() {
        swipe.setTheme(theme);
        swipe.reset();
        blurBox.setVisibility(imageUri != null ? View.VISIBLE : View.GONE);
        blurLabel.setText("BLUR · " + blurValue);

        int w = preview.getWidth(), h = preview.getHeight();
        if (w == 0 || h == 0) {
            preview.post(this::paintPreview);   // size is not known yet
            return;
        }
        if (imageUri != null) {
            android.graphics.drawable.Drawable d =
                    CallBackground.image(this, imageUri, blurValue, w, h);
            if (d != null) {
                preview.setBackground(d);
                return;
            }
            Toast.makeText(this, "Could not read the photo", Toast.LENGTH_SHORT).show();
            imageUri = null;
            buildBackgroundRow();
        }
        preview.setBackground(CallBackground.gradient(CallBackground.preset(presetId)));
    }

    private void save() {
        Prefs.setCallTheme(this, theme);
        Prefs.setCallBackground(this, presetId, imageUri, blurValue);
        Toast.makeText(this, "Look saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
