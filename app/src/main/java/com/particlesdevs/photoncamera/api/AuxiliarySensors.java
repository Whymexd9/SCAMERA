package com.particlesdevs.photoncamera.api;

/** Plain checks behind CameraManager2.isAuxiliarySensor (no Android types, unit-tested). */
final class AuxiliarySensors {
    private AuxiliarySensors() {}

    /**
     * A sensor that declares no lens, no gain or no exposure is no photo lens either (SCAM TEST, 2026-10-11): the vivo X200 Pro's
     * camera 5 (focal length 0.0, ISO [0, 0], 1920x1080; the session-failure / error 4 loop of P39) and the X200 Ultra's camera 7
     * (exposure [0, 0], no preview frame). Missing keys (-1 / NaN) say nothing.
     */
    static boolean noLensGainOrExposure(float focalMm, int isoMax, long exposureMaxNs) {
        return focalMm == 0f || isoMax == 0 || exposureMaxNs == 0L;
    }

    /**
     * A vivo camera profile that is no lens of its own (com.vivo.chi.override.CameraType): the teleconverter calibrations of the
     * tele («Tele3xLongExternal» on the X300, which takes no extender; «Tele3P5xExternal» / «…External400» on the X500 Pro Max,
     * the second «3.5×» of its strip) and «DUMMY». Each shows the plain tele's picture under the extender's focal length.
     */
    static boolean vivoProfileOnly(String cameraType) {
        if (cameraType == null) return false;
        String t = cameraType.trim();
        return t.contains("External") || t.equals("DUMMY") || t.equals("MultiSpectrum") || t.equals("PreISP");
    }

    /**
     * P120: a camera without the BACKWARD_COMPATIBLE capability (0) takes no ordinary photo request: depth-only outputs (the X300
     * Ultra's camera 8). Unknown capabilities (null) say nothing.
     */
    static boolean notAPhotoCamera(int[] capabilities) {
        if (capabilities == null) return false;
        for (int c : capabilities) if (c == 0) return false;
        return true;
    }
}
