package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureResult;

import androidx.annotation.Nullable;

/**
 * P252 (owner, 2026-10-18/19: the OPPO Find X7 Ultra «не фокусируется» at night, on every camera): in a dark, far scene the
 * HAL's continuous AF left the lens at its rest stop, 7.14 dpt, beyond the 5 dpt the camera declares as its nearest focus,
 * STATIONARY, the AF state PASSIVE_UNFOCUSED (OPPO, 2026-10-19 03:20, 60 ms ISO 3842) - the AF never drove the lens. A lens the
 * AF drives cannot stand beyond the declared range, so that is the sign: after {@link #PARK_NS} of it the viewfinder and the
 * shot take the hyperfocal distance (sharp from half of it to infinity: a night scene is far) until the scene gets clearly
 * brighter (the HAL's AF again) or the session ends. A hand-set focus and a touch focus (AF mode not continuous) are left alone.
 */
final class ParkedLens {
    static final long PARK_NS = 1_500_000_000L;
    /** The scene must get this much brighter (exposure x ISO) before the HAL's AF gets the lens back. */
    static final double BRIGHTER = 8.0;
    /** After giving the lens back, the fallback waits this long before it takes it again. */
    static final long COOLDOWN_NS = 5_000_000_000L;

    /** RELEASE: the fallback gives the lens back to the HAL's AF; YIELD: a touch focus / hand-set mode took it (nothing to restore). */
    static final int NOTHING = 0, TAKE = 1, RELEASE = -1, YIELD = -2;

    private long parkedSince = -1, releasedAt = Long.MIN_VALUE / 2;
    private boolean active, seenOff;
    private double exposureAtTake;

    boolean active() {
        return active;
    }

    void reset() {
        parkedSince = -1;
        active = false;
        seenOff = false;
        releasedAt = Long.MIN_VALUE / 2;
    }

    /** The lens stands beyond the camera's declared nearest focus (with a margin) and does not move. */
    static boolean parked(@Nullable Float focus, float minFocus, @Nullable Integer lensState) {
        return minFocus > 0f && focus != null && focus > minFocus * 1.1f + 0.1f
                && (lensState == null || lensState == CaptureResult.LENS_STATE_STATIONARY);
    }

    static boolean continuous(@Nullable Integer afMode) {
        return afMode != null && (afMode == CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                || afMode == CaptureResult.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
    }

    /**
     * One preview result: {@link #TAKE} when the fallback takes the lens now, {@link #RELEASE} when it gives it back, else
     * {@link #NOTHING}. {@code exposure}: exposure time (s) x ISO of the frame.
     */
    int observe(long timeNs, @Nullable Integer afMode, @Nullable Float focus, @Nullable Integer lensState, float minFocus, double exposure) {
        if (active && afMode != null && afMode == CaptureResult.CONTROL_AF_MODE_OFF) seenOff = true;
        if (active && seenOff && afMode != null && afMode != CaptureResult.CONTROL_AF_MODE_OFF) {
            // our AF OFF reached the frames, now another mode: a touch focus or a hand-set mode took the lens - it is theirs
            active = false;
            parkedSince = -1;
            releasedAt = timeNs;
            return YIELD;
        }
        if (active) {
            if (exposure > 0 && exposureAtTake > 0 && exposure * BRIGHTER < exposureAtTake) {
                active = false;
                parkedSince = -1;
                releasedAt = timeNs;
                return RELEASE;
            }
            return NOTHING;
        }
        if (!continuous(afMode) || !parked(focus, minFocus, lensState)) {
            parkedSince = -1;
            return NOTHING;
        }
        if (parkedSince < 0) parkedSince = timeNs;
        if (timeNs - parkedSince < PARK_NS || timeNs - releasedAt < COOLDOWN_NS) return NOTHING;
        active = true;
        seenOff = false;
        exposureAtTake = exposure;
        return TAKE;
    }

    /**
     * The hyperfocal distance in diopters for a lens of {@code focalMm} at f/{@code aperture} on pixels {@code pitchMm} wide
     * (circle of confusion: two pixels), within [0, {@code minFocus}].
     */
    static float hyperfocalDiopters(float focalMm, float aperture, float pitchMm, float minFocus) {
        if (!(focalMm > 0f) || !(aperture > 0f) || !(pitchMm > 0f)) return 0f;
        final double coc = 2.0 * pitchMm;
        final double hMm = focalMm * (double) focalMm / (aperture * coc) + focalMm;
        final float d = (float) (1000.0 / hMm);
        return Math.max(0f, minFocus > 0f ? Math.min(minFocus, d) : d);
    }
}
