package com.particlesdevs.photoncamera.capture;

/**
 * Preview stall watchdog decisions (owner, 2026-10-08, vivo X200 Ultra 5x tele): the session stays configured but the camera
 * stops delivering preview frames (black viewfinder) until the module is switched or the app restarted. Seen on a cold start
 * with the tele's sensor mode 5 and right after a format change in the viewfinder; the HAL aborted after 12 s without frames
 * twice. The watchdog restarts the camera itself, as the module switch did, and logs the state first.
 */
final class PreviewStall {
    /** No preview result for this long (or three frame durations plus a second, for long manual exposures) is a stall. */
    static final long MIN_LIMIT_MS = 2500;
    /** Restarts in a row without a preview frame in between; then the watchdog only logs. */
    static final int MAX_RESTARTS = 3;

    private PreviewStall() {}

    /** The longest gap between preview results that is still normal for frames of this duration (ms, 0 = unknown). */
    static long limitMs(long frameMs) {
        return Math.max(MIN_LIMIT_MS, 3 * Math.max(0, frameMs) + 1000);
    }

    /**
     * Whether the preview stalled: {@code lastFrameMs} is the last preview result (0 = none in this session),
     * {@code sessionStartMs} when the session was configured (all on the elapsedRealtime clock).
     */
    static boolean stalled(long nowMs, long lastFrameMs, long sessionStartMs, long frameMs) {
        if (sessionStartMs <= 0) return false;
        long since = Math.max(lastFrameMs, sessionStartMs);
        return nowMs - since >= limitMs(frameMs);
    }

    /** A restart is allowed while fewer than {@link #MAX_RESTARTS} restarts in a row brought no frame. */
    static boolean mayRestart(int restartsInARow) {
        return restartsInARow < MAX_RESTARTS;
    }
}
