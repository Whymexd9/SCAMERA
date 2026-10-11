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
}
