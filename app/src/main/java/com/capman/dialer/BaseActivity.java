package com.capman.dialer;

import android.app.Activity;
import android.content.Context;

/**
 * Common base that applies the theme preference to every screen.
 *
 * When the theme is changed from settings, screens waiting in the back stack
 * would keep the old one, so on resume each screen checks whether the
 * preference moved and recreates itself if so.
 */
public class BaseActivity extends Activity {

    private String appliedTheme;

    @Override
    protected void attachBaseContext(Context newBase) {
        appliedTheme = Prefs.theme(newBase);
        super.attachBaseContext(Theming.wrap(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedTheme != null && !appliedTheme.equals(Prefs.theme(this))) {
            appliedTheme = Prefs.theme(this);
            recreate();
        }
    }
}
