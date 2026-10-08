package com.particlesdevs.photoncamera.control.subject;

/**
 * Face detection modes (P42, after ArkCamera v50's per-lens «Face Detection Mode»: Off / Simple / Full /
 * Extended 0x80) and their resolution against what the current camera module advertises.
 * <p>
 * ArkCam writes its choice into {@code STATISTICS_FACE_DETECT_MODE} unchecked, including the raw vendor value
 * 0x80. Here the value written to the HAL is always one the module lists in
 * {@code STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES}: an unsupported choice falls back to the best supported one,
 * so a request can never be rejected for it. Without any HAL mode, "auto" and "software" run the platform
 * {@code android.media.FaceDetector} on the downscaled viewfinder frames instead (no ML dependency).
 * <p>
 * Pure Java (JVM-testable): the Camera2 constants are repeated here (OFF 0, SIMPLE 1, FULL 2).
 */
public final class FaceDetectModes {
    private FaceDetectModes() {}

    /** Preference key and values (pref_face_detect_mode). */
    public static final String KEY = "pref_face_detect_mode";
    public static final String OFF = "off", AUTO = "auto", SIMPLE = "simple", FULL = "full", EXTENDED = "extended",
            SOFTWARE = "software";
    public static final String DEFAULT = AUTO;

    /** Camera2 STATISTICS_FACE_DETECT_MODE values; EXTENDED is the vendor value ArkCam offers (Pixel / Qualcomm). */
    public static final int HAL_OFF = 0, HAL_SIMPLE = 1, HAL_FULL = 2, HAL_EXTENDED = 0x80;

    /** How faces are produced for one camera module. */
    public static final class Resolved {
        /** Value for STATISTICS_FACE_DETECT_MODE (HAL_OFF when the HAL is not asked for faces). */
        public final int halMode;
        /** Faces come from android.media.FaceDetector on viewfinder frames. */
        public final boolean software;

        Resolved(int halMode, boolean software) {
            this.halMode = halMode;
            this.software = software;
        }

        public boolean enabled() {
            return halMode != HAL_OFF || software;
        }

        @Override
        public String toString() {
            return software ? "software" : "hal=" + halMode;
        }
    }

    private static final Resolved NONE = new Resolved(HAL_OFF, false);
    private static final Resolved SOFT = new Resolved(HAL_OFF, true);

    static boolean has(int[] available, int mode) {
        if (available == null) return false;
        for (int m : available) if (m == mode) return true;
        return false;
    }

    /** Best HAL mode the module lists: FULL over SIMPLE (the vendor value is never picked on its own). */
    public static int bestHal(int[] available) {
        if (has(available, HAL_FULL)) return HAL_FULL;
        if (has(available, HAL_SIMPLE)) return HAL_SIMPLE;
        return HAL_OFF;
    }

    /**
     * The effective mode of a preference value on a module.
     *
     * @param pref      preference value (unknown values count as {@link #DEFAULT})
     * @param available STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES of the module (null = unknown = none)
     */
    public static Resolved resolve(String pref, int[] available) {
        String p = normalize(pref);
        if (OFF.equals(p)) return NONE;
        if (SOFTWARE.equals(p)) return SOFT;
        int best = bestHal(available);
        int wanted;
        switch (p) {
            case SIMPLE: wanted = HAL_SIMPLE; break;
            case FULL: wanted = HAL_FULL; break;
            case EXTENDED: wanted = HAL_EXTENDED; break;
            default: wanted = best; break; // AUTO
        }
        if (wanted != HAL_OFF && has(available, wanted)) return new Resolved(wanted, false);
        if (best != HAL_OFF) return new Resolved(best, false);
        // The module has no HAL face detection at all: the software detector stands in.
        return SOFT;
    }

    /** Known preference value, else the default. */
    public static String normalize(String pref) {
        if (pref == null) return DEFAULT;
        switch (pref) {
            case OFF: case AUTO: case SIMPLE: case FULL: case EXTENDED: case SOFTWARE:
                return pref;
            default:
                return DEFAULT;
        }
    }
}
