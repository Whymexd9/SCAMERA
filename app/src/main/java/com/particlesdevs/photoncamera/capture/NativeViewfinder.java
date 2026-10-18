package com.particlesdevs.photoncamera.capture;

import androidx.annotation.Nullable;

/**
 * P212h (A6 = M11): the native FULLRES viewfinder predicts the photo. Pure rules, CaptureController.publishLiveRawFrame applies them.
 * <ul>
 * <li>Display gain: the OPPO native results say AE OFF (OppoNativeFullRes.apply), so LiveRawMeter showed the night frame's median of
 * 0.001-0.004 at gain 1 while ArkAe lifts the photo 3-4x. The gain now covers the exposure the mode cannot deliver - the own AE's
 * deficit (P212a NativeRawExposure.deficitEv: the meter's wish above the ISO cap at the frame time) - when it exceeds
 * {@link #DEFICIT_GATE_EV}: LiveRawMeter's gain held to [1, 2^deficit]; below it [1, 1], today's viewfinder (the daylight view
 * keeps its brightness). scam_dev.txt "vf_native_gain": 1 that (default), 2 the verifier's variant (the meter's own law whenever
 * the exposure is not hand-set, as the plain RAW viewfinder of the camera's AE), 0 none.</li>
 * <li>Colour: the X7 Ultra's native result transform is the identity (and the plain one too, so OppoNativeFullRes.complete fills
 * none) while the photo renders with OppoTunedColor: its {@code previewMatrix} at the frame's neutral; another OPPO phone or a vivo
 * identity transform takes the camera's plain-session matrix at that light (W7 PlainColourMemory, as the mosaic photo), else the
 * frame's com.oplus.custom.ccm (OPPO). Recomputed only when the neutral moves by more than {@link #MOVE_LN} ln.</li>
 * </ul>
 */
final class NativeViewfinder {
    private NativeViewfinder() {}

    /** The own AE's deficit (EV) above which the viewfinder shows it. */
    static final double DEFICIT_GATE_EV = 0.3;
    /** The neutral's move (ln r, ln b) that recomputes the viewfinder matrix. */
    static final float MOVE_LN = 0.01f;

    /**
     * The range {lo, hi} LiveRawMeter holds the display gain of an OPPO native frame to, null for none (gain 1, as before):
     * {@code mode} 0 none, 1 the deficit, 2 the meter's own law (no range); a hand-set exposure (ISO and shutter) never.
     */
    @Nullable
    static float[] displayGain(int mode, boolean manual, double deficitEv) {
        if (mode <= 0 || manual) return null;
        if (mode >= 2) return new float[]{0f, Float.POSITIVE_INFINITY};
        final float hi = deficitEv > DEFICIT_GATE_EV ? (float) Math.pow(2.0, deficitEv) : 1f;
        return new float[]{1f, Math.max(1f, hi)};
    }

    /** A row-major 3x3 as the viewfinder's colour_transform takes it (column-major, CaptureController's getElement(col, row) fill). */
    static float[] columnMajor(float[] rowMajor) {
        final float[] out = new float[9];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) out[c * 3 + r] = rowMajor[r * 3 + c];
        return out;
    }

    /** The neutral (ln r, ln b) moved from the last matrix's by more than MOVE_LN (any NaN: moved). */
    static boolean moved(float lnR0, float lnB0, float lnR, float lnB) {
        return !(Math.abs(lnR - lnR0) <= MOVE_LN) || !(Math.abs(lnB - lnB0) <= MOVE_LN);
    }
}
