package com.particlesdevs.photoncamera.control.subject;

/**
 * Decisions of the face / tracking focus (P42) that need no Android: which face leads, when a region is worth a new
 * repeating request, and who owns AF / AE (manual values and an active tap focus always win).
 */
public final class SubjectPolicy {
    private SubjectPolicy() {}

    /** Preference key and values of the tracking autofocus (pref_tracking_af_mode). */
    public static final String TRACKING_KEY = "pref_tracking_af_mode";
    public static final String TRACK_OFF = "off", TRACK_LONG_PRESS = "long_press", TRACK_TAP = "tap";
    public static final String TRACK_DEFAULT = TRACK_LONG_PRESS;

    public static String normalizeTracking(String pref) {
        if (TRACK_OFF.equals(pref) || TRACK_TAP.equals(pref) || TRACK_LONG_PRESS.equals(pref)) return pref;
        return TRACK_DEFAULT;
    }

    /** Camera2 CONTROL_AF_MODE_OFF / CONTROL_AE_MODE_OFF. */
    static final int MODE_OFF = 0;

    /** Faces / tracking may write AF regions: the lens is on auto focus (the manual panel sets AF_MODE_OFF) and can focus. */
    public static boolean afAllowed(Integer afMode, int maxAfRegions, boolean lensCanFocus) {
        return maxAfRegions > 0 && lensCanFocus && (afMode == null || afMode != MODE_OFF);
    }

    /** Faces / tracking may write AE regions: exposure is automatic (manual ISO or shutter sets AE_MODE_OFF). */
    public static boolean aeAllowed(Integer aeMode, int maxAeRegions) {
        return maxAeRegions > 0 && (aeMode == null || aeMode != MODE_OFF);
    }

    // ------------------------------------------------------------------ primary face

    /** Smallest face side (fraction of the view) that is drawn and metered; ArkCam drops faces under 4 %. */
    public static final float MIN_FACE = 0.03f;
    /** A new face must beat the current primary by this factor to take over (hysteresis). */
    public static final float SWITCH_MARGIN = 1.25f;
    /** Centre distance (fraction of the view) within which a face is "the same" as the previous primary. */
    public static final float SAME_FACE = 0.12f;

    /**
     * ArkCam's prominence score (CameraVisionTracker.updateFaces): confidence, size, closeness to the centre.
     *
     * @param score Camera2 face score 1..100 (software faces map their confidence onto it)
     * @param l..b  normalized view rectangle
     */
    public static float prominence(int score, float l, float t, float r, float b) {
        float w = r - l, h = b - t;
        float cx = (l + r) * 0.5f - 0.5f, cy = (t + b) * 0.5f - 0.5f;
        return score / 100f * 1.5f + w * h * 3f - (float) Math.hypot(cx, cy) * 0.4f;
    }

    /** True when a normalized view rectangle is usable (big enough, centre inside the view). */
    public static boolean usableFace(float l, float t, float r, float b) {
        float cx = (l + r) * 0.5f, cy = (t + b) * 0.5f;
        return r - l >= MIN_FACE && b - t >= MIN_FACE && cx > 0f && cx < 1f && cy > 0f && cy < 1f;
    }

    /**
     * Picks the primary face with hysteresis.
     *
     * @param rects   normalized view rectangles, 4 floats per face (l, t, r, b)
     * @param scores  Camera2 scores
     * @param n       number of faces
     * @param prevCx  previous primary centre (negative: none)
     * @return index of the primary face, or -1
     */
    public static int pickPrimary(float[] rects, int[] scores, int n, float prevCx, float prevCy) {
        int best = -1, kept = -1;
        float bestScore = -Float.MAX_VALUE, keptScore = 0, keptDist = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float l = rects[4 * i], t = rects[4 * i + 1], r = rects[4 * i + 2], b = rects[4 * i + 3];
            if (scores[i] <= 0 || !usableFace(l, t, r, b)) continue;
            float s = prominence(scores[i], l, t, r, b);
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
            if (prevCx >= 0) {
                float d = (float) Math.hypot((l + r) * 0.5f - prevCx, (t + b) * 0.5f - prevCy);
                if (d < SAME_FACE && d < keptDist) {
                    keptDist = d;
                    kept = i;
                    keptScore = s;
                }
            }
        }
        if (kept >= 0 && kept != best && bestScore < keptScore + Math.abs(keptScore) * (SWITCH_MARGIN - 1f) + 0.05f)
            return kept;
        return best;
    }

    // ------------------------------------------------------------------ region updates

    /**
     * Whether a region moved enough to send a new repeating request: the centre moved by more than {@code move}
     * (fraction of the larger window side) or the size changed by more than {@code resize}, and at least
     * {@code minIntervalMs} passed since the last write. A first write ({@code lastW <= 0}) always passes.
     * Inputs are {x, y, w, h} in Camera2 units.
     */
    public static boolean regionChanged(int[] next, int[] last, int windowSide, long nowMs, long lastWriteMs,
                                        long minIntervalMs, float move, float resize) {
        if (last[2] <= 0 || last[3] <= 0) return true;
        if (nowMs - lastWriteMs < minIntervalMs) return false;
        float dx = (next[0] + next[2] * 0.5f) - (last[0] + last[2] * 0.5f);
        float dy = (next[1] + next[3] * 0.5f) - (last[1] + last[3] * 0.5f);
        if (Math.hypot(dx, dy) > move * windowSide) return true;
        float area = (float) next[2] * next[3], lastArea = (float) last[2] * last[3];
        return Math.abs(area - lastArea) > resize * lastArea;
    }
}
