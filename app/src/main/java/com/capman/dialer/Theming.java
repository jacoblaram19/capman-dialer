package com.capman.dialer;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Theme selection. Dark by default.
 *
 * The colours live in res/values (light) and res/values-night (dark); all we do
 * here is force the app's night mode, which makes the choice apply everywhere
 * on its own.
 */
public final class Theming {

    private Theming() {
    }

    public static Context wrap(Context base) {
        String theme = Prefs.theme(base);
        if (Prefs.THEME_SYSTEM.equals(theme)) return base;

        boolean dark = !Prefs.THEME_LIGHT.equals(theme);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(cfg);
    }
}
