package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import java.util.Locale;

/**
 * P236 (speed-ux F / D2): the phone's thermal state as the platform reports it - PowerManager.getCurrentThermalStatus (API 29:
 * NONE 0, LIGHT 1, MODERATE 2, SEVERE 3, CRITICAL 4, EMERGENCY 5, SHUTDOWN 6) and getThermalHeadroom (API 30: 1.0 = SEVERE is
 * reached in that many seconds' forecast; NaN when the HAL has none or it is asked more than once a second). The app never read it
 * before (the roadmap's grep found only a comment), so the shot logs could not tell GPU throttling from a slow merge (P200).
 */
public final class ThermalState {
    /** One sample: {@link #status} -1 when unknown, {@link #headroom} NaN when unknown. */
    public static final class Sample {
        public final int status;
        public final float headroom;

        public Sample(int status, float headroom) {
            this.status = status;
            this.headroom = headroom;
        }

        /** SEVERE or worse (the platform's own throttling level). */
        public boolean severe() { return status >= 3; }

        @Override public String toString() {
            return name(status) + (Float.isNaN(headroom) ? "" : String.format(Locale.ROOT, "/%.2f", headroom));
        }
    }

    public static final Sample UNKNOWN = new Sample(-1, Float.NaN);
    /** The last headroom read and when (the platform answers NaN to a second call within a second). */
    private static volatile float lastHeadroom = Float.NaN;
    private static volatile long lastHeadroomMs = -10_000;

    private ThermalState() {}

    /** The thermal status name ({@code status} of PowerManager), "unknown" for -1. */
    public static String name(int status) {
        switch (status) {
            case 0: return "none";
            case 1: return "light";
            case 2: return "moderate";
            case 3: return "severe";
            case 4: return "critical";
            case 5: return "emergency";
            case 6: return "shutdown";
            default: return "unknown";
        }
    }

    /** The state now; {@link #UNKNOWN} without a context or before Android 10. Never throws. */
    public static Sample now(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 29) return UNKNOWN;
        try {
            final PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return UNKNOWN;
            final int status = pm.getCurrentThermalStatus();
            float headroom = Float.NaN;
            if (Build.VERSION.SDK_INT >= 30) {
                final long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastHeadroomMs >= 1000) {
                    lastHeadroom = pm.getThermalHeadroom(0);
                    lastHeadroomMs = now;
                }
                headroom = lastHeadroom;
            }
            return new Sample(status, headroom);
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    /** The state now from the app's context. */
    public static Sample now() {
        Context context = null;
        try { context = com.particlesdevs.photoncamera.app.ScameraApp.getAppContext(); } catch (RuntimeException | LinkageError ignored) { }
        return now(context);
    }
}
