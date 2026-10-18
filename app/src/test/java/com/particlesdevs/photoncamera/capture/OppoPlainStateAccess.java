package com.particlesdevs.photoncamera.capture;

import android.content.SharedPreferences;

/** Tests of other packages: OppoPlainState's test store and its writes (package-private there). */
public final class OppoPlainStateAccess {
    private OppoPlainStateAccess() {}

    public static void useStore(SharedPreferences prefs, String firmware) {
        OppoPlainState.useStoreForTest(prefs, firmware, () -> 1_800_000_000_000L);
    }

    public static void remember(String camera, int width, int height, float r, float b, float[] gains, int cols, int rows) {
        OppoPlainState.remember(camera, width, height, r, b, null, gains, cols, rows, 1_000L);
    }

    public static void reset() {
        OppoPlainState.resetForTest();
    }
}
