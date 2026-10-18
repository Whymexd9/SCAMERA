package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * P240 (speed-ux C4): a capture route built but not yet run on a phone (the P208 200 MP stock route of the X100 / X300 Ultra) is
 * marked experimental on its first use - once per install, then the route runs silently. The flag lives in its own preferences
 * file ("scam_experimental"), so a settings reset does not repeat it.
 */
public final class ExperimentalRoutes {
    static final String FILE = "scam_experimental";

    private ExperimentalRoutes() {}

    /** {@code text} the first time {@code route} is used on this install, null after (or without a context). Never throws. */
    public static String firstUse(Context context, String route, String text) {
        if (context == null || route == null) return null;
        try {
            final SharedPreferences p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            final String key = "shown_" + route;
            if (p.getBoolean(key, false)) return null;
            p.edit().putBoolean(key, true).apply();
            com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", "experimental route " + route + ": first use, said on the card");
            return text;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
