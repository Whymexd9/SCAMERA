package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

/**
 * P238 (speed-ux D2): what the camera does when the phone is hot. ColorOS force-stopped the app for heat in a 50 MP merge (P200:
 * «o-stop(22)», 53 °C) while the FULLRES session kept developing and ringing 96 MB frames at 24 fps. From the platform's thermal
 * status SEVERE (PowerManager, the level at which it throttles itself):
 * <ul>
 *   <li>the developed RAW viewfinder publishes at most {@link #SEVERE_VF_FPS} frames a second ({@link #viewfinderFrameDue}; the
 *       camera's stream, the AE and the ZSL ring see every frame as before; the ISP preview's texture draw is not touched - its
 *       buffers must keep flowing back to the camera);</li>
 *   <li>the FULLRES ring copy pauses (CaptureController: its frames go, a press takes the series after the shutter);</li>
 *   <li>a FULLRES press says «Телефон горячий — 50 Мп будет дольше» (at most once in {@link #NOTICE_EVERY_MS}).</li>
 * </ul>
 * The status comes from the platform's listener (API 29+, registered once per process) and is read again at each press.
 * scam_dev thermal_policy: 1 (default) as above, 0 = only logged.
 */
public final class ThermalPolicy {
    public static final int SEVERE_VF_FPS = 15;
    /**
     * P353 (owner, 2026-10-27: «Узнай почему у арк гораздо быстрее обработка чем у нас и ускорь нашу до его уровня»): while a shot
     * is processed (the merge worker and the post pipeline on the same GPU) the developed RAW viewfinder tones at most this many
     * frames a second. OPPO Find X7 Ultra, the same 26-frame merge: 3.06 s with the ISP viewfinder (30 draws a second, 1.3 ms
     * each), 4.38 s with the developed RAW one (29 tones and 55 draws a second, 8 - 10 ms each). scam_dev vf_busy_fps: the rate
     * (0 = every frame, as before).
     */
    static final int BUSY_VF_FPS = 15;
    /** A processing flag older than this counts as cleared (a path that never cleared it must not slow the viewfinder for good). */
    static final long BUSY_MAX_NS = 20_000_000_000L;
    private static volatile long busySinceNs;
    static final long NOTICE_EVERY_MS = 5 * 60_000L;
    private static volatile int status = -1;
    private static volatile boolean registered;
    private static long lastVfNs;
    private static volatile long lastNoticeMs = Long.MIN_VALUE / 2;

    private ThermalPolicy() {}

    /** Registers the platform's thermal listener once (API 29+); never throws. */
    public static void watch(Context context) {
        if (registered || context == null || Build.VERSION.SDK_INT < 29) return;
        try {
            final PowerManager pm = (PowerManager) context.getApplicationContext().getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            registered = true;
            status = pm.getCurrentThermalStatus();
            pm.addThermalStatusListener(context.getApplicationContext().getMainExecutor(), s -> {
                final int before = status;
                status = s;
                if (before != s) Log.i("SCAM_CAPTURE", "THERMAL status " + ThermalState.name(before) + " -> " + ThermalState.name(s)
                        + (s >= 3 && enabled() ? ": viewfinder at most " + SEVERE_VF_FPS + " fps, FULLRES ring paused"
                        : before >= 3 && s < 3 ? ": back to normal" : ""));
            });
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "thermal listener: " + e);
        }
    }

    /** The status a press read ({@link ThermalState#now}); keeps the policy current where the listener is late or missing. */
    public static void observed(ThermalState.Sample sample) {
        if (sample != null && sample.status >= 0) status = sample.status;
    }

    static boolean enabled() {
        try {
            return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("thermal_policy", true);
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    /** P353: a shot is being processed from now ({@code true}) / no longer ({@code false}). */
    public static void processing(boolean on) {
        busySinceNs = on ? Math.max(1L, android.os.SystemClock.elapsedRealtimeNanos()) : 0L;
    }

    static int busyFps() {
        try {
            return Math.max(0, Math.round(com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevNumber("vf_busy_fps", BUSY_VF_FPS)));
        } catch (RuntimeException | LinkageError e) {
            return BUSY_VF_FPS;
        }
    }

    /** P353: a shot is being processed (set less than {@link #BUSY_MAX_NS} ago). */
    static boolean busy(long nowNs) {
        final long since = busySinceNs;
        return since != 0 && nowNs - since < BUSY_MAX_NS;
    }

    /** SEVERE or worse, with the policy on. */
    public static boolean severe() {
        return status >= 3 && enabled();
    }

    /** The current status (-1 unknown). */
    public static int status() { return status; }

    /** Test hook: the status as the listener would set it. */
    static void set(int s) { status = s; }

    /**
     * The developed RAW viewfinder may show the frame at {@code nowNs}: always below SEVERE; at SEVERE when at least 1 /
     * {@link #SEVERE_VF_FPS} s passed since the last one shown (a small tolerance keeps a 30 fps stream at 15).
     */
    public static synchronized boolean viewfinderFrameDue(long nowNs) {
        if (severe()) return due(nowNs, SEVERE_VF_FPS);
        // P353: while a shot is processed (the flag's age on the monotonic clock; nowNs is the frame's sensor time)
        final int busyFps = busy(android.os.SystemClock.elapsedRealtimeNanos()) ? busyFps() : 0;
        if (busyFps > 0) return due(nowNs, busyFps);
        lastVfNs = nowNs;
        return true;
    }

    static synchronized boolean due(long nowNs) {
        return due(nowNs, SEVERE_VF_FPS);
    }

    static synchronized boolean due(long nowNs, int fps) {
        final long period = 1_000_000_000L / fps;
        if (lastVfNs != 0 && nowNs - lastVfNs < period - period / 8) return false;
        lastVfNs = nowNs;
        return true;
    }

    /** The hot-phone notice for a FULLRES press now, or null (not SEVERE, or said less than {@link #NOTICE_EVERY_MS} ago). */
    public static String pressNotice(long nowMs, int megapixels) {
        if (!severe()) return null;
        if (nowMs - lastNoticeMs < NOTICE_EVERY_MS) return null;
        lastNoticeMs = nowMs;
        final int mp = megapixels > 0 ? megapixels : 50;
        return Lang.t("Телефон горячий — " + mp + " Мп будет дольше", "The phone is hot: " + mp + " MP will take longer");
    }

    /** Test hook: forget the last notice. */
    static void resetNotice() { lastNoticeMs = Long.MIN_VALUE / 2; lastVfNs = 0; }
}
