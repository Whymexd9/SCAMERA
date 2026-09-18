package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

/** Loading is only one prerequisite, never evidence that stock processing is ready. */
public final class VivoRemosaicAvailability {
    private VivoRemosaicAvailability() {}
    private static native String nativeProbe();

    public static synchronized String probe() {
        try {
            System.loadLibrary("vivoRemosaicProbe");
            return nativeProbe();
        } catch (LinkageError | SecurityException e) {
            return "Vivo loader probe unavailable: " + e;
        }
    }
}
