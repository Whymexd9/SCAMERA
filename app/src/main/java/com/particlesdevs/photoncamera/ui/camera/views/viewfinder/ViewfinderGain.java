package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

/**
 * P90 Series frames on the viewfinder (owner, 2026-10-11: «видоискатель зависает во время съёмки»). As in ArkCam the
 * post-shutter series also targets the viewfinder surface, so the viewfinder keeps moving through the series instead of
 * holding its last frame for 0.7-0.8 s (and the vivo HAL no longer switches between preview+RAW and RAW-only requests). The
 * series frames are exposed from x0.06 (Bento) to x2 (Shasta) of the viewfinder's exposure: the renderer scales each one back
 * by the viewfinder's exposure over the frame's (sensor timestamp -> ISO x shutter, recorded when the frame starts).
 */
public final class ViewfinderGain {
    private static final int SIZE = 64;
    private static final long[] TS = new long[SIZE];
    private static final double[] PRODUCT = new double[SIZE];
    private static int next;
    private static volatile double reference;

    private ViewfinderGain() {}

    /** The viewfinder's exposure (ISO x seconds) the series frames are brought back to; 0 = none (no scaling). */
    public static void setReference(double isoSeconds) {
        reference = isoSeconds > 0 ? isoSeconds : 0;
    }

    /** A series frame starting at sensor {@code timestamp} with ISO x seconds {@code isoSeconds}. */
    public static synchronized void record(long timestamp, double isoSeconds) {
        if (timestamp == 0 || !(isoSeconds > 0)) return;
        TS[next] = timestamp;
        PRODUCT[next] = isoSeconds;
        next = (next + 1) % SIZE;
    }

    /** The gain for the viewfinder frame with sensor {@code timestamp}: 1 for a viewfinder frame, clamped to x1/16..x16. */
    public static synchronized float gainFor(long timestamp) {
        final double ref = reference;
        if (ref <= 0 || timestamp == 0) return 1f;
        for (int i = 0; i < SIZE; i++)
            if (TS[i] == timestamp) return gain(ref, PRODUCT[i]);
        return 1f;
    }

    static float gain(double reference, double product) {
        if (!(reference > 0) || !(product > 0)) return 1f;
        return (float) Math.max(1.0 / 16, Math.min(16, reference / product));
    }

    /** Tests. */
    static synchronized void resetForTest() {
        java.util.Arrays.fill(TS, 0);
        next = 0;
        reference = 0;
    }
}
