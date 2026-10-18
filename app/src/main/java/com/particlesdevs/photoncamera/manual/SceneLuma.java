package com.particlesdevs.photoncamera.manual;

/**
 * P82: the viewfinder's mean brightness (display-encoded luma, 0..1) for the software shutter / ISO priority. Written by
 * the viewfinder's meter on the GL thread (a 64 × 64 readback every eighth frame), read on the camera thread.
 * <p>
 * P212h (A6 = M11): or by the RAW viewfinder's meter (LiveRawRenderer: the developed frame's median before any gain, gamma
 * 1/2.2), which MainRenderer draws instead of the ISP texture - its luma is not on the ISP texture's scale, so each value carries
 * its source and the priority loop learns its target again when the source changes (ExposurePriority.Loop.relearn).
 */
public final class SceneLuma {
    /** The source of a value: the ISP texture's mean (LiveSceneMeter), the RAW viewfinder's median (LiveRawRenderer). */
    public static final int ISP = 0, RAW = 1;

    private static volatile double mean = Double.NaN;
    private static volatile long atNs;
    private static volatile int source = ISP;
    private static volatile boolean wanted;

    private SceneLuma() {}

    /** The meter runs while the manual panel may need it (the panel is open), not otherwise. */
    public static boolean wanted() { return wanted; }

    static void setWanted(boolean on) {
        wanted = on;
        if (!on) mean = Double.NaN;
    }

    /** A value of the ISP texture's meter. */
    public static void publish(double luma) {
        publish(luma, ISP);
    }

    /** P212h: a value of the RAW viewfinder's meter. */
    public static void publishRaw(double luma) {
        publish(luma, RAW);
    }

    private static synchronized void publish(double luma, int from) {
        source = from;
        mean = luma;
        atNs = System.nanoTime();
    }

    /** The latest mean, NaN when none was measured. */
    static double mean() { return mean; }

    /** When the latest mean was measured (System.nanoTime). */
    static long atNs() { return atNs; }

    /** P212h: the source of the latest mean (ISP or RAW). */
    static int source() { return source; }
}
