package com.particlesdevs.photoncamera.gallery.dng;

/** A small synthetic Bayer DNG (DngRawTest's writer) for the editor's tests in other packages. */
public final class DngFixtures {
    private DngFixtures() {}

    public static byte[] smallBayer() {
        int[] raw = DngPreviewTest.scene(DngRawTest.N, 64, 1023);
        return DngRawTest.bayer(raw, 64, 1023).build();
    }
}
