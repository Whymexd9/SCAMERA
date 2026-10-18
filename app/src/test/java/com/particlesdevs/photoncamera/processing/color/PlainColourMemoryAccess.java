package com.particlesdevs.photoncamera.processing.color;

import android.content.SharedPreferences;

/** Tests of other packages: PlainColourMemory's test store (package-private there). */
public final class PlainColourMemoryAccess {
    private PlainColourMemoryAccess() {}

    public static void useStore(SharedPreferences prefs, String firmware) {
        PlainColourMemory.useStoreForTest(prefs, firmware);
    }

    public static void reset() {
        PlainColourMemory.resetForTest();
    }
}
