package com.particlesdevs.photoncamera.processing.color;

import androidx.annotation.Nullable;

/**
 * P370 (A2, B4): the colour matrix the photo renders a frame with, as the RAW viewfinder needs it - one set of rules for both, so
 * they cannot drift apart. Pure (JVM tests); matrices are row-major 3x3 (white-balanced camera RGB -> linear sRGB).
 * <ul>
 * <li>Rows: the photo divides each row of the result's transform (and of the OPPO ISP matrix) by its sum (Parameters.normalize,
 * {@link #normaliseRows}): white stays white; a row summing to about 0 is kept, never divided by.</li>
 * <li>A frame whose transform is the identity (the OPPO Find X7 Ultra; the vivo mosaic results; an X8U native mode): the tuned
 * matrix of the X7 Ultra (OppoTunedColor.previewMatrix: the photo's OPPO_TUNED route); on a plain Bayer stream the frame's own
 * com.oplus.custom.ccm (the photo's OPLUS_CCM route); on a mosaic stream (an ISZ lease, a picked sensor mode, FULLRES) the camera's
 * plain-session matrix at the frame's neutral (PlainColourMemory: the photo's PLAIN_TABLE, which Parameters takes for mosaic shots
 * only), then the OPPO matrix; else the identity.</li>
 * <li>The viewfinder's scene light for the «Цвет» look (keep warm / cool, B4): the tuned estimate, the table's AWB label, the white
 * balance against the camera's daylight neutral (SceneIlluminant, as the photo's ISP route), the DNG SDK's estimate of the
 * camera's colour matrices (Parameters' A1, |Duv| <= 0.025), the result's AWB label; 0 = unknown.</li>
 * </ul>
 */
public final class PhotoMatrix {
    private PhotoMatrix() {}

    /** What the viewfinder renders a frame with: the matrix (row-major, null = the identity), its source, its scene light. */
    public static final class Pick {
        @Nullable public final float[] rowMajor;
        public final String how;
        /** The light the matrix belongs to when its source says one (tuned estimate, table label), else NaN. */
        public final float cct;
        /** The X7 Ultra's tuned matrix (the photo's AgX saturation compensation, WhyTone.ccmSatComp). */
        public final boolean tuned;
        /** The result's own transform (not the identity). */
        public final boolean reported;

        Pick(@Nullable float[] rowMajor, String how, float cct, boolean tuned, boolean reported) {
            this.rowMajor = rowMajor;
            this.how = how;
            this.cct = cct;
            this.tuned = tuned;
            this.reported = reported;
        }
    }

    /**
     * Each row divided by its sum (white stays white). A row summing to less than 0.25 in magnitude, or not finite, is kept as it
     * is (its division gave inf) and the result is false.
     */
    public static boolean normaliseRows(float[] m) {
        boolean ok = true;
        for (int row = 0; row < 9; row += 3) {
            final float avr = m[row] + m[row + 1] + m[row + 2];
            if (!(Math.abs(avr) >= 0.25f) || !Float.isFinite(avr)) {
                ok = false;
                continue;
            }
            m[row] /= avr;
            m[row + 1] /= avr;
            m[row + 2] /= avr;
        }
        return ok;
    }

    /** {@link #normaliseRows} of a copy. */
    public static float[] rowsNormalised(float[] m) {
        final float[] out = m.clone();
        normaliseRows(out);
        return out;
    }

    /**
     * The viewfinder's matrix of a frame. {@code reported}: the result's transform (row-major, null: none); {@code tuned}: the X7
     * Ultra's tuned matrix at the frame's neutral (null: not this phone) and its colour temperature; {@code mosaic}: the stream is a
     * colour-block mosaic; {@code table}: the camera's plain-session matrix at the frame's neutral (null: none); {@code oplus}: the
     * frame's com.oplus.custom.ccm (null: none).
     */
    public static Pick viewfinder(@Nullable float[] reported, @Nullable float[] tuned, float tunedCct, boolean mosaic,
                                  @Nullable PlainColourMemory.Lookup table, @Nullable float[] oplus) {
        if (reported != null && reported.length >= 9 && !OplusCcm.identity(reported))
            return new Pick(rowsNormalised(reported), "the result's transform", Float.NaN, false, true);
        if (tuned != null) return new Pick(tuned.clone(), "the OPPO tuned matrix", tunedCct, true, false);
        final boolean oplusOk = oplus != null && oplus.length >= 9 && !OplusCcm.identity(oplus);
        if (mosaic && table != null)
            return new Pick(table.ccm.clone(), "the plain-session matrix", table.cct > 0f ? table.cct : Float.NaN, false, false);
        if (oplusOk) return new Pick(rowsNormalised(oplus), "the frame's OPPO ISP matrix", Float.NaN, false, false);
        return new Pick(null, "the identity (no matrix for this frame)", Float.NaN, false, false);
    }

    /** |Duv| of the DNG estimate above which it is unknown (Parameters.DUV_GUARD). */
    static final double DUV_GUARD = 0.025;

    /**
     * The viewfinder's scene light (K, 0 = unknown) for {@code pick} at {@code neutral} (R/G, 1, B/G): the pick's own light, else on
     * the result's transform the white balance against the camera's daylight neutral (SceneIlluminant), else the DNG SDK's estimate
     * of {@code dng} (null: none) when within DUV_GUARD of the locus, else the result's AWB label ({@code awbLabelK}, 0: none).
     */
    public static float sceneCct(Pick pick, @Nullable String camera, float[] neutral,
                                 @Nullable com.particlesdevs.photoncamera.gallery.dng.DngColor dng, int awbLabelK) {
        if (pick != null && pick.cct > 0f) return pick.cct;
        if (pick != null && pick.reported && pick.rowMajor != null) {
            final float wb = SceneIlluminant.cct(camera, neutral, pick.rowMajor);
            if (wb > 0f) return wb;
        }
        if (dng != null && dng.usable()) {
            try {
                // the photo's estimate: no D50 stand-in for a white the matrices cannot place (P370 review)
                final double[] xy = dng.estimateXy(new double[]{neutral[0], neutral[1], neutral[2]});
                final double[] cd = xy == null ? null : com.particlesdevs.photoncamera.gallery.dng.DngColor.cctDuv(xy);
                if (cd != null && cd[0] > 0 && Double.isFinite(cd[0]) && Math.abs(cd[1]) <= DUV_GUARD) return (float) cd[0];
            } catch (RuntimeException ignored) {
            }
        }
        return awbLabelK > 0 ? awbLabelK : 0f;
    }

    /** The DNG white estimate of a camera's characteristics (DngColor.forCamera), null without colour matrices. */
    @Nullable
    public static com.particlesdevs.photoncamera.gallery.dng.DngColor dngWhite(@Nullable android.hardware.camera2.CameraCharacteristics c) {
        if (c == null) return null;
        final double[] cm1 = rows(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_COLOR_TRANSFORM1));
        double[] cm2 = rows(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_COLOR_TRANSFORM2));
        if (cm1 == null) return null;
        double[] cc1 = rows(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1));
        double[] cc2 = rows(c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2));
        final Integer ill1 = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
        final Byte ill2 = c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
        final int i1 = ill1 != null ? ill1 : 21, i2 = ill2 != null ? ill2 : i1;
        if (cm2 == null) cm2 = cm1;
        if (cc2 == null) cc2 = cc1;
        return com.particlesdevs.photoncamera.gallery.dng.DngColor.forCamera(cm1, cm2, cc1, cc2, i1, i2);
    }

    @Nullable
    private static double[] rows(@Nullable android.hardware.camera2.params.ColorSpaceTransform m) {
        if (m == null) return null;
        final double[] out = new double[9];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) out[r * 3 + c] = m.getElement(c, r).doubleValue();
        return out;
    }

    /**
     * B4: what the RAW viewfinder's look reads of the frame it shows - its scene light (K, 0 unknown) and whether the X7 Ultra's
     * tuned matrix renders it (CaptureController sets them with the matrix; LiveRawRenderer and LiveWhyTone read them).
     */
    public static volatile float viewfinderSceneCct;
    public static volatile boolean viewfinderTuned;
}
