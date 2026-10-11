package com.particlesdevs.photoncamera.manual;

/**
 * P82: the viewfinder's mean brightness (display-encoded luma, 0..1) for the software shutter / ISO priority. Written by
 * the viewfinder's meter on the GL thread (a 64 × 64 readback every eighth frame), read on the camera thread.
 */
public final class SceneLuma {
    private static volatile double mean = Double.NaN;
    private static volatile long atNs;
    private static volatile boolean wanted;

    private SceneLuma() {}

    /** The meter runs while the manual panel may need it (the panel is open), not otherwise. */
    public static boolean wanted() { return wanted; }

    static void setWanted(boolean on) {
        wanted = on;
        if (!on) mean = Double.NaN;
    }

    public static void publish(double luma) {
        mean = luma;
        atNs = System.nanoTime();
    }

    /** The latest mean, NaN when none was measured. */
    static double mean() { return mean; }

    /** When the latest mean was measured (System.nanoTime). */
    static long atNs() { return atNs; }
}
