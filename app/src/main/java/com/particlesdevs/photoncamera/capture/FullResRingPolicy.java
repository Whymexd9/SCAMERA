package com.particlesdevs.photoncamera.capture;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/**
 * P239 (speed-ux D1): how often the native 50 MP ring copies a frame, and when it rests. ringCopyDue only waited twice the last
 * copy's time, so with 7-10 ms copies every frame went in: 150 copies in 6.35 s, ~23.6 a second x 96 MB = 2.3 GB/s of memory
 * traffic for a ring of 3-10 slots, while ColorOS had already killed the app for heat (P200).
 * <ul>
 *   <li>Rate: at most {@link #DEFAULT_FPS} copies a second (scam_dev fullres_ring_fps, 1-30; 0 = every frame the copy time
 *       allows, as before P239).</li>
 *   <li>Rest: no touch for {@link #DEFAULT_IDLE_S} s and the gyro still for as long (the filtered shake below the tripod
 *       threshold, Gyro.getTripod's 25) - the phone lies or stands, nobody is about to shoot: no copies, the ring's frames go;
 *       a touch-down anywhere on the camera screen (CameraActivity.onUserInteraction) or a moving gyro starts it again
 *       (scam_dev fullres_ring_idle_s, seconds; 0 = never rests).</li>
 * </ul>
 * The merge's frame selection keeps its candidates: frames 83 ms apart stay far inside the ring's stale gap (500 ms,
 * CaptureController.ZSL_RING_MAX_GAP_NS), the ring keeps its slots (the same frame count once it has run for slots / 12 s), and a
 * press while the ring is short or resting takes the missing N after the shutter (P232's split) - the merge gets as many N as
 * before; only those frames come later. Hence the new behaviour is the default.
 */
public final class FullResRingPolicy {
    public static final int DEFAULT_FPS = 12;
    public static final int DEFAULT_IDLE_S = 10;
    /** Gyro.getFilteredShakiness below this is still (Gyro.getTripod's threshold). */
    static final int STILL_SHAKE = 25;

    private static volatile long lastTouchMs = Long.MIN_VALUE / 2;
    private long stillSinceMs = -1;

    /** A touch-down or key on the camera screen (any thread). */
    public static void touched(long nowMs) {
        lastTouchMs = nowMs;
    }

    static long lastTouchMs() { return lastTouchMs; }

    /** The copies a second (0: no cap). */
    static int fps() {
        final float v = PreferenceKeys.scamDevNumber("fullres_ring_fps", DEFAULT_FPS);
        return v <= 0 ? 0 : (int) Math.min(30, Math.max(1, Math.round(v)));
    }

    /** The idle seconds before the ring rests (0: never). */
    static int idleSeconds() {
        final float v = PreferenceKeys.scamDevNumber("fullres_ring_idle_s", DEFAULT_IDLE_S);
        return v <= 0 ? 0 : Math.round(v);
    }

    /** A copy at {@code nowNs} keeps the rate of {@code fps} copies a second after the last one began at {@code lastStartNs}. */
    static boolean rateDue(long nowNs, long lastStartNs, int fps) {
        if (fps <= 0 || lastStartNs <= 0) return true;
        final long period = 1_000_000_000L / fps;
        return nowNs - lastStartNs >= period - period / 10; // a little slack for the frame timing (24 fps -> every 2nd at 12)
    }

    /**
     * The ring rests at {@code nowMs}: no touch for {@code idleS} s and the gyro still ({@code shake} below {@link #STILL_SHAKE})
     * for as long; a moving gyro restarts the still time.
     */
    boolean idle(long nowMs, int shake, long touchMs, int idleS) {
        if (shake >= STILL_SHAKE || shake < 0) { stillSinceMs = -1; return false; }
        if (stillSinceMs < 0) stillSinceMs = nowMs;
        if (idleS <= 0) return false;
        final long n = idleS * 1000L;
        return nowMs - touchMs >= n && nowMs - stillSinceMs >= n;
    }
}
