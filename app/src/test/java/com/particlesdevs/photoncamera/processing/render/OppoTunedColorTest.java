package com.particlesdevs.photoncamera.processing.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P20: the X7 Ultra's tuned colour table: CCT estimate and forward matrices. */
public class OppoTunedColorTest {
    private static final float[] FOCALS = {8.67f, 2.59f, 15.38f, 22.36f, 3.23f};
    private static final float[] D50 = {0.9642f, 1f, 0.8249f};

    private static float[] neutral(float lnRB) {
        // R/G and B/G with the given ln(R/B), G = 1
        float r = (float) Math.exp(lnRB / 2), b = (float) Math.exp(-lnRB / 2);
        return new float[]{r, 1f, b};
    }

    @Test
    public void cctFallsAsTheLightGetsWarmerAndStaysInTheTable() {
        for (float f : FOCALS) {
            float prev = Float.MAX_VALUE;
            for (float ln = -2f; ln <= 2.5f; ln += 0.05f) {
                float cct = OppoTunedColor.estimateCct(f, neutral(ln));
                assertTrue("finite " + f + " " + ln, Float.isFinite(cct));
                assertTrue("monotone " + f + " at " + ln + ": " + cct + " after " + prev, cct <= prev + 1e-3f);
                assertTrue(cct >= 2999f && cct <= 9010f);
                prev = cct;
            }
        }
    }

    @Test
    public void theMeasured6500KNeutralGivesAbout6500K() {
        assertEquals(6500f, OppoTunedColor.estimateCct(8.67f, neutral(-0.696f)), 10f); // the table puts 6500 K at 154 mired (6494 K)
        assertEquals(6500f, OppoTunedColor.estimateCct(2.59f, neutral(-0.613f)), 10f);
        assertEquals(6500f, OppoTunedColor.estimateCct(15.38f, neutral(-0.551f)), 10f);
    }

    @Test
    public void forwardMatricesKeepWhiteAreContinuousAndFinite() {
        for (float f : FOCALS) {
            float[] prev = null;
            for (float cct = 2500f; cct <= 9000f; cct += 25f) {
                float[] m = OppoTunedColor.forwardAt(f, cct);
                assertNotNull(m);
                for (int r = 0; r < 3; r++) {
                    float sum = m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2];
                    assertEquals("white row " + r + " at " + cct + " K, focal " + f, D50[r], sum, 2e-3f);
                }
                if (prev != null)
                    for (int k = 0; k < 9; k++)
                        assertTrue("continuous at " + cct + " K, focal " + f, Math.abs(m[k] - prev[k]) < 0.03f);
                prev = m;
            }
        }
        assertNull("no table: the HAL's matrix stays", OppoTunedColor.forwardAt(4.0f, 5000f));
    }

    /**
     * P212h (A6 = M11): the native viewfinder's matrix is the photo's. Parameters' OPPO tuned branch for a white point wp renders camera
     * RGB with proPhotoToSRGB x sXYZtoProPhoto x calculateCameraToXYZD50Transform(FM, FM, I, I, wp) (FM the normalised tuned forward
     * matrix at estimateCct(wp)); the viewfinder applies the gains 1 / wp, then previewMatrix: the same matrix up to the photo's
     * scale max(wp). Golden numbers: the main camera's 6500 K neutral gives (almost) its tuning CCM, every row keeps white.
     */
    @Test
    public void thePreviewMatrixIsThePhotosMatrixForTheSameNeutral() {
        final float[] identity = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        for (float f : FOCALS) {
            for (float ln = -1.4f; ln <= 1.6f; ln += 0.2f) {
                final float[] wp = neutral(ln);
                final float[] preview = OppoTunedColor.previewMatrix(f, wp, null);
                assertNotNull(preview);
                final float cct = OppoTunedColor.estimateCct(f, wp);
                final float[] fm = OppoTunedColor.forwardAt(f, cct);
                Converter.normalizeFM(fm);
                final float[] sensorToXyz = new float[9], sensorToProPhoto = new float[9], proPhotoToSrgb = new float[9], photo = new float[9];
                Converter.calculateCameraToXYZD50Transform(fm, fm.clone(), identity, identity, wp, 0.5, sensorToXyz);
                Converter.multiply(Converter.sXYZtoProPhoto, sensorToXyz, sensorToProPhoto);
                Converter.multiply(Converter.sXYZtoSRGB, Converter.sProPhotoToXYZ, proPhotoToSrgb);
                Converter.multiply(proPhotoToSrgb, sensorToProPhoto, photo);
                final float scale = Math.max(wp[0], Math.max(wp[1], wp[2]));
                for (int r = 0; r < 3; r++) {
                    float sum = 0f;
                    for (int c = 0; c < 3; c++) {
                        // the viewfinder: preview x diag(1 / wp); the photo: x max(wp)
                        assertEquals("focal " + f + " ln " + ln + " [" + r + "][" + c + "]",
                                photo[r * 3 + c] / scale, preview[r * 3 + c] / wp[c], 2e-4f * Math.max(1f, Math.abs(photo[r * 3 + c] / scale)));
                        sum += preview[r * 3 + c];
                    }
                    assertEquals("white row " + r, 1f, sum, 3e-3f);
                }
            }
        }
        // the main camera at its 6500 K neutral: the tuning's CCM between 6300 and 7500 K (sRGB -> XYZ D50 -> sRGB nearly cancels)
        final float[] main = OppoTunedColor.previewMatrix(8.67f, neutral(-0.696f), null);
        assertEquals(1.6437f, main[0], 0.01f);
        assertEquals(-0.6348f, main[1], 0.01f);
        assertEquals(1.3640f, main[4], 0.01f);
        assertEquals(1.5495f, main[8], 0.01f);
        assertNull("no table row", OppoTunedColor.previewMatrix(4.0f, neutral(0f), null));
        assertNull("no white point", OppoTunedColor.previewMatrix(8.67f, new float[]{Float.NaN, 1f, 0.5f}, null));
        assertNull(OppoTunedColor.previewMatrix(8.67f, new float[]{0f, 1f, 0.5f}, null));
    }

    /** P315: the X8U camera 2's DNG forward matrix is the sRGB -> XYZ (D50) placeholder; a real camera matrix is not. */
    @org.junit.Test public void thePlaceholderForwardMatrixIsRecognised() {
        org.junit.Assert.assertTrue(OppoTunedColor.placeholderForward(new float[]{0.43898535f, 0.38411218f, 0.14110243f, 0.21875f, 0.71875f,
                0.0625f, 0.01571238f, 0.09427429f, 0.7149133f}));
        org.junit.Assert.assertFalse(OppoTunedColor.placeholderForward(new float[]{0.490f, 0.295f, 0.179f, 0.163f, 0.814f, 0.023f,
                -0.023f, -0.319f, 1.167f}));
        org.junit.Assert.assertFalse(OppoTunedColor.placeholderForward(null));
    }
}
