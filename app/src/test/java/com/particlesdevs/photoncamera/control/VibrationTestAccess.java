package com.particlesdevs.photoncamera.control;

/** The package-private pieces of {@link Vibration} for tests in other packages. */
public final class VibrationTestAccess {
    private VibrationTestAccess() {
    }

    public static android.os.VibrationEffect soft(boolean amplitudeControl) {
        return Vibration.softEffect(amplitudeControl);
    }
}
