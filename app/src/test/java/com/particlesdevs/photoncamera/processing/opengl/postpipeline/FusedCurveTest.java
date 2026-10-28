package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P314: the RAW viewfinder's per-pixel fusion curve (FusedCurve, read by why/combine.glsl POINT_FUSED 1) against the shader's own
 * formula - why/gf.glsl fusedAt and MODE 3's P291 monotonic envelope, here in float as the GPU evaluates them - and the lookup
 * pointFused() does between its entries.
 */
public class FusedCurveTest {
    /** why/gf.glsl acescg of one channel (float). */
    private static float acescg(float v, float toe) {
        float x = Math.max(v, 0f) * 1.5f;
        float b = Math.max(toe * 0.75f, 0.005f);
        float e = Math.max(0.14f - (toe - 0.04f) * 0.8f, 0.03f);
        return (x * (2.51f * x + b)) / (x * (2.43f * x + 0.59f) + e);
    }

    /** why/gf.glsl fusedAt(I, 0) (float, the shader's defaults for unset values), deepFloorU 0. */
    private static float shaderFusedAt(float I, float[] em, float[] lw, float center, float smooth, float gammaInv, float toe) {
        return shaderFusedAt(I, em, lw, center, smooth, gammaInv, toe, 0f);
    }

    /** The same with import why_fusion's deepFloorU (the bright layer's weight floor in deep shadows). */
    private static float shaderFusedAt(float I, float[] em, float[] lw, float center, float smooth, float gammaInv, float toe, float floor) {
        center = center > 0f ? center : 0.6f;
        smooth = smooth > 0f ? smooth : 0.25f;
        gammaInv = gammaInv > 0f ? gammaInv : 1f / 2.2f;
        toe = toe > 0f ? toe : 0.05f;
        float sw = 0f, swl = 0f;
        for (int k = 0; k < 4; k++) {
            float l = (float) Math.pow(Math.max(acescg(I * em[k], toe), 0.000001f), gammaInv);
            float d = l - center;
            float w = (float) Math.exp(-0.5f * (d * d) / Math.max(smooth * smooth, 0.001f)) * lw[k] + 0.000001f;
            if (k == 3 && floor > 0f && d < 0f) w = Math.max(w, (float) Math.exp(-0.5f * floor * floor) * lw[k] + 0.000001f);
            sw += w;
            swl += w * l;
        }
        return swl / sw;
    }

    /** why/gf.glsl MODE 3's envelope of a neutral flat area: the guided filter's value there (float). */
    private static float shaderEnvelope(float I, float[] em, float[] lw, float center, float smooth, float gammaInv, float toe, float mono) {
        float f = shaderFusedAt(I, em, lw, center, smooth, gammaInv, toe), env = f;
        if (mono > 0f && I > 0f) {
            for (int j = 1; j <= 36; j++) {
                float s = j * (0.25f * 0.6931472f);
                float fj = shaderFusedAt(I * (float) Math.exp(-s), em, lw, center, smooth, gammaInv, toe);
                if (fj >= 0.5f) env = Math.max(env, fj + mono * s);
            }
        }
        return env;
    }

    /** why/combine.glsl pointFused() of a guide I on the table (linear between entries, clamped at its ends). */
    private static float lookup(float[] table, float I) {
        int n = table.length;
        float t = Math.max(0f, Math.min(n - 1f, ((float) (Math.log(Math.max(I, 1.0e-12f)) / Math.log(2.0)) - FusedCurve.LOG2_MIN) * FusedCurve.PER_EV));
        int i0 = Math.min((int) t, n - 1), i1 = Math.min(i0 + 1, n - 1);
        return Math.max(table[i0] + (table[i1] - table[i0]) * (t - i0), 0f);
    }

    private static float[] exposures(float effS, float effH) {
        WhyAe.Result r = new WhyAe.Result();
        r.effS = effS;
        r.effH = effH;
        return WhyAe.exposures(r);
    }

    private static final float[] LW = {0.8f, 1f, 0.7f, 0.5f};

    @Test
    public void theCurveIsTheShadersFusedAt() {
        Random random = new Random(3141);
        for (int n = 0; n < 2000; n++) {
            float[] em = exposures(3f * random.nextFloat(), 3.5f * random.nextFloat());
            float[] lw = {random.nextFloat(), 0.2f + random.nextFloat(), random.nextFloat(), random.nextFloat()};
            float center = n % 5 == 0 ? 0f : 0.4f + 0.4f * random.nextFloat(), smooth = n % 7 == 0 ? 0f : 0.1f + 0.3f * random.nextFloat();
            float gammaInv = n % 11 == 0 ? 0f : 1f / (1.8f + random.nextFloat()), toe = n % 13 == 0 ? 0f : 0.01f + 0.1f * random.nextFloat();
            float I = (float) Math.pow(2.0, -14 + 19 * random.nextDouble());
            double want = shaderFusedAt(I, em, lw, center, smooth, gammaInv, toe);
            assertEquals("case " + n, want, FusedCurve.fusedAt(I, em, lw, center, smooth, gammaInv, toe), 2e-5);
        }
    }

    /**
     * At its entries the table is the shader's envelope (the P291 lift of a falling curve too: em.x small with Bento-like
     * strengths), and the lookup between them stays within 1e-3 of the shader everywhere on the light's range.
     */
    @Test
    public void theTableAndItsLookupAreTheShadersEnvelope() {
        float[][] strengths = {{0.5f, 0.5f}, {1.5f, 1.0f}, {2f, 2f}, {2f, 3.5f}, {0.2f, 0f}};
        float[] table = new float[FusedCurve.SIZE];
        // monoU up to 0.1 (WhyTone.fusionMono: 0.04); a steeper rise makes the shader's own gate (fj >= 0.5) jump between entries
        for (float mono : new float[]{0f, 0.04f, 0.1f}) {
            for (float[] s : strengths) {
                float[] em = exposures(s[0], s[1]);
                FusedCurve.fill(table, em, LW, 0.6f, 0.25f, 1f / 2.2f, 0.05f, mono);
                float gridErr = 0f, lookErr = 0f;
                for (int i = 0; i < FusedCurve.SIZE; i++) {
                    float I = (float) Math.pow(2.0, FusedCurve.LOG2_MIN + i / (double) FusedCurve.PER_EV);
                    gridErr = Math.max(gridErr, Math.abs(table[i] - shaderEnvelope(I, em, LW, 0.6f, 0.25f, 1f / 2.2f, 0.05f, mono)));
                }
                for (int k = 0; k <= 4000; k++) {
                    float I = (float) Math.pow(2.0, -14 + 19 * k / 4000.0);
                    lookErr = Math.max(lookErr, Math.abs(lookup(table, I) - shaderEnvelope(I, em, LW, 0.6f, 0.25f, 1f / 2.2f, 0.05f, mono)));
                }
                String c = "effS " + s[0] + " effH " + s[1] + " mono " + mono;
                assertTrue(c + ": entries " + gridErr, gridErr < 2e-5f);
                assertTrue(c + ": lookup " + lookErr, lookErr < 1e-3f);
            }
        }
    }

    /**
     * The host model of the design (effS 1.5, effH 1, mono 0.04) at three entries. P348: the exposures two dark and one bright
     * (0.354, 1, 0.595, 4.76); an independent numpy fusedAt with the envelope gives 0.43456 / 0.88904 / 0.98738 (the lift only near the
     * top, where the curve flattens: 0.98625 without it).
     */
    @Test
    public void theTableMatchesTheHostModel() {
        float[] table = new float[FusedCurve.SIZE];
        FusedCurve.fill(table, exposures(1.5f, 1.0f), LW, 0.6f, 0.25f, 1f / 2.2f, 0.05f, 0.04f);
        assertEquals(353, FusedCurve.SIZE);
        assertEquals(0.43456, table[(int) ((-4 - FusedCurve.LOG2_MIN) * FusedCurve.PER_EV)], 2e-5);
        assertEquals(0.88904, table[(int) ((0 - FusedCurve.LOG2_MIN) * FusedCurve.PER_EV)], 2e-5);
        assertEquals(0.98738, table[(int) ((2 - FusedCurve.LOG2_MIN) * FusedCurve.PER_EV)], 2e-5);
    }

    /** P291's envelope: a curve that falls (em.x far below the base: a light past the base layer) is lifted, never lowered. */
    @Test
    public void theEnvelopeLiftsAFallingCurveOnly() {
        float[] plain = new float[FusedCurve.SIZE], lifted = new float[FusedCurve.SIZE];
        float[] em = {0.03f, 1f, 4f, 16f};
        FusedCurve.fill(plain, em, LW, 0.75f, 0.25f, 1f / 2.2f, 0.05f, 0f);
        FusedCurve.fill(lifted, em, LW, 0.75f, 0.25f, 1f / 2.2f, 0.05f, 0.05f);
        float plainPeak = 0f, liftedPeak = 0f, plainDrop = 0f, liftedDrop = 0f;
        for (int i = 0; i < FusedCurve.SIZE; i++) {
            assertTrue("never below the kernel's curve at " + i, lifted[i] >= plain[i] - 1e-7f);
            plainPeak = Math.max(plainPeak, plain[i]);
            liftedPeak = Math.max(liftedPeak, lifted[i]);
            plainDrop = Math.max(plainDrop, plainPeak - plain[i]);
            liftedDrop = Math.max(liftedDrop, liftedPeak - lifted[i]);
        }
        // the dark ring round a lamp (P291): the kernel's curve falls by a visible step, the envelope (quarter-EV steps, as the
        // shader's) by a small fraction of it
        assertTrue("this curve falls without the envelope: " + plainDrop, plainDrop > 0.02f);
        assertTrue("the envelope keeps it from falling: " + liftedDrop + " of " + plainDrop, liftedDrop < 0.25f * plainDrop);
    }

    /**
     * Window burst (2026-10-28: a room 7 EV under the window rendered black): with the deep-shadow floor the viewfinder's curve is
     * the shader's fusedAt with deepFloorU, rises with the light everywhere, a deep shadow takes at most the bright layer's own
     * exposure, and wherever the bright layer is within the floor's sigma of the centre nothing changes.
     */
    @Test
    public void theDeepShadowFloorIsTheShadersAndRises() {
        final float[] em = {0.0626f, 1f, 0.2502f, 2.6148f}, lw = {0.4f, 1f, 0.1f, 1f};
        final float center = 0.75f, smooth = 0.25f, gammaInv = 1f / 2.2f, toe = 0.05f, floor = 2f;
        try {
            FusedCurve.DEEP_FLOOR[0] = floor;
            float last = -1f;
            for (int i = 0; i <= 2000; i++) {
                final float I = (float) Math.pow(2.0, -16 + 18 * i / 2000.0);
                final double want = shaderFusedAt(I, em, lw, center, smooth, gammaInv, toe, floor);
                final float got = (float) FusedCurve.fusedAt(I, em, lw, center, smooth, gammaInv, toe);
                assertEquals("I " + I, want, got, 2e-5);
                assertTrue("rises at I " + I + ": " + got + " after " + last, got >= last - 1e-6f);
                last = got;
                // the bright layer's own luma bounds the lift
                final float bright = (float) Math.pow(Math.max(acescg(I * em[3], toe), 0.000001f), gammaInv);
                assertTrue("at most the bright layer at I " + I, got <= bright + 1e-5f);
                if (bright >= center - floor * smooth)
                    assertEquals("unchanged at I " + I, shaderFusedAt(I, em, lw, center, smooth, gammaInv, toe), got, 2e-5);
            }
            // the room: 7.2 EV under the window median at ae 2.42 - the bright layer's light, not the average's
            final float room = 0.000495f * 2.42f;
            final float before = shaderFusedAt(room, em, lw, center, smooth, gammaInv, toe), after = (float) FusedCurve.fusedAt(room, em, lw, center, smooth, gammaInv, toe);
            assertTrue("the room lifted: " + before + " -> " + after, after > 1.2f * before);
        } finally {
            FusedCurve.DEEP_FLOOR[0] = 0f;
        }
    }
}
