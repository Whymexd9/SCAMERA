package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

/**
 * P241 (speed-ux C6): diagnostics make every shot slower (the X300U's 12 MP shot 5.6 s against 3.0-3.6 s), and timing complaints
 * came from diagnostics sessions. The switch («Диагностика» / pref_scam_hybrid_diagnostics and the legacy
 * pref_scamhdr_diagnostics) turns itself off 24 h after the camera first saw it on; the viewfinder shows «DIAG» while dumps are
 * written (CameraFragment). The time it was first seen on lives in its own preferences file ("scam_diag"); a scam_dev
 * hybrid_diagnostics line is the owner's own and is not touched (the badge still shows it).
 */
public final class DiagnosticsAutoOff {
    public static final long LIMIT_MS = 24L * 60 * 60 * 1000;
    static final String FILE = "scam_diag", SINCE = "on_since";
    static final String HYBRID_KEY = "pref_scam_hybrid_diagnostics", LEGACY_KEY = "pref_scamhdr_diagnostics";

    private DiagnosticsAutoOff() {}

    /**
     * {new "on since" (0: off), turn off now (1) or not (0)} for the stored switch {@code on} first seen on at {@code since}
     * (0: not seen yet) at {@code now}. A wall clock set back (now before since) starts the 24 h again.
     */
    static long[] decide(boolean on, long since, long now) {
        if (!on) return new long[]{0, 0};
        if (since <= 0 || now < since) return new long[]{now, 0};
        return now - since >= LIMIT_MS ? new long[]{0, 1} : new long[]{since, 0};
    }

    /** The stored diagnostics switch (either key). */
    static boolean storedOn() {
        final SettingsManager s = PreferenceKeys.settingsStore();
        if (s == null) return false;
        try {
            return s.getBoolean("default_scope", HYBRID_KEY, false) || s.getBoolean("default_scope", LEGACY_KEY, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Diagnostics dumps are on now (the stored switch or the scam_dev line): the viewfinder's «DIAG» badge. */
    public static boolean dumpsOn() {
        try {
            return PreferenceKeys.hybridSwitch("diagnostics", false) || storedOn();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Checks the 24 h at the camera's start; turns the stored switch off when they passed and returns the message to show
     * (null otherwise). Never throws.
     */
    public static String check(Context context, long nowMs) {
        if (context == null) return null;
        try {
            final SharedPreferences p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            final long[] d = decide(storedOn(), p.getLong(SINCE, 0), nowMs);
            p.edit().putLong(SINCE, d[0]).apply();
            if (d[1] == 0) return null;
            final SettingsManager s = PreferenceKeys.settingsStore();
            if (s == null) return null;
            s.set("default_scope", HYBRID_KEY, false);
            s.set("default_scope", LEGACY_KEY, false);
            Log.i("SCAM_DIAG", "diagnostics switched off: on for 24 h (it slows every shot)");
            return Lang.t("Диагностика выключена: прошло 24 часа (она замедляет каждый снимок)",
                    "Diagnostics switched off: 24 hours passed (it slows every shot)");
        } catch (RuntimeException e) {
            return null;
        }
    }
}
