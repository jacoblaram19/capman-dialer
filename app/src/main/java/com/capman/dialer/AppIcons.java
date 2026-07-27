package com.capman.dialer;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Changing the launcher icon.
 *
 * Android will not let an app swap its icon at runtime; instead the manifest
 * declares one activity-alias per icon and exactly one of them stays enabled.
 * The order below matters: the new alias is enabled first and the others are
 * disabled afterwards - do it the other way round and the app vanishes from the
 * launcher for a moment.
 */
public final class AppIcons {

    private static final String TAG = "AppIcons";
    private static final String PKG = "com.capman.dialer";

    public static class Icon {
        public final String alias;
        public final String label;
        public final int preview;

        Icon(String alias, String label, int preview) {
            this.alias = alias;
            this.label = label;
            this.preview = preview;
        }
    }

    /** The selectable icons. The first one is the default. */
    public static final Icon[] ALL = {
            new Icon(PKG + ".IconClassic", "Classic (yellow chomper)", R.mipmap.ic_launcher),
            new Icon(PKG + ".IconGhost", "Hayalet", R.mipmap.ic_launcher_ghost),
            new Icon(PKG + ".IconGreen", "Green field", R.mipmap.ic_launcher_green),
            new Icon(PKG + ".IconHandset", "Plain handset", R.mipmap.ic_launcher_handset),
            new Icon(PKG + ".IconDots", "Pellet trail", R.mipmap.ic_launcher_dots),
    };

    private AppIcons() {
    }

    /** The icon currently enabled, or the default if none is found. */
    public static Icon current(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (Icon i : ALL) {
            try {
                int state = pm.getComponentEnabledSetting(new ComponentName(ctx, i.alias));
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i;
            } catch (Exception ignored) {
            }
        }
        return ALL[0];
    }

    /** @return whether the icon could be changed */
    public static boolean apply(Context ctx, Icon chosen) {
        PackageManager pm = ctx.getPackageManager();
        try {
            // Enable the new one first so the launcher never has a gap
            pm.setComponentEnabledSetting(new ComponentName(ctx, chosen.alias),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            for (Icon i : ALL) {
                if (i.alias.equals(chosen.alias)) continue;
                pm.setComponentEnabledSetting(new ComponentName(ctx, i.alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "could not change the icon", e);
            return false;
        }
    }
}
