package com.particlesdevs.photoncamera.ui.camera;

/**
 * P345: when the frozen viewfinder (CameraFragment.showFrozenViewfinder: the last frame before the pause, shown over the
 * viewfinder after the resume) gives way. Plain rules, kept apart for the tests.
 */
final class FrozenViewfinder {
    /** The longest the frozen frame stays when no camera frame comes. */
    static final long MAX_SHOWN_MS = 4000;
    /** A frame older than this (real time: deep sleep counted) is not shown at all. */
    static final long MAX_AGE_MS = 60_000;

    private FrozenViewfinder() {}

    /**
     * A new camera frame is on the viewfinder: an ISP frame latched (MainRenderer.shownTimestamp moved) or a developed RAW frame
     * drawn (MainRenderer.rawDraws moved - a RAW-only session, ISZ / FULLRES without an ISP stream, never latches an ISP one).
     */
    static boolean fresh(long shownBefore, long shownNow, long rawBefore, long rawNow) {
        return shownNow != shownBefore || rawNow != rawBefore;
    }

    /** The frame frozen at {@code frozenAtMs} is too old to show at {@code nowMs} (SystemClock.elapsedRealtime both). */
    static boolean tooOld(long frozenAtMs, long nowMs) {
        return nowMs - frozenAtMs > MAX_AGE_MS;
    }
}
