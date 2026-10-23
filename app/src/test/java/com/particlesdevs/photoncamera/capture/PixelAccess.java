package com.particlesdevs.photoncamera.capture;

/** P320 tests: the package-private lens rule of PixelPro10.dcgMain. */
public final class PixelAccess {
    private PixelAccess() {}

    public static boolean mainLens(float focal, float width) {
        return PixelPro10.mainLens(focal, width);
    }
}
