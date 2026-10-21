package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P314: the RAW viewfinder's per-pixel fusion curve (FusedCurve, read by ark/combine.glsl POINT_FUSED 1) against the shader's own
 * formula - ark/gf.glsl fusedAt and MODE 3's P291 monotonic envelope, here in float as the GPU evaluates them - and the lookup
 * pointFused() does between its entries.
 */
public class FusedCurveTest {
    /** ark/gf.glsl acescg of one channel (float). */
    private static float acescg(float v, float toe) {
        float x = Math.max(v, 0f) * 1.5f;
        float b = Math.max(toe * 0.75f, 0.005f);
        float e = Math.max(0.14f - (toe - 0.04f) * 0.8f, 0.03f);
        return (x * (2.51f * x + b)) / (x * (2.43f * x + 0.59f) + e);
    }

    /** ark/gf.glsl fusedAt(I, 0) (float, the shader's defaults for unset values). */
    private static float shaderFusedAt(float I, float[] em, float[] lw, float center, float smooth, float gammaInv, float toe) {
        center = center > 0f ? center : 0.6f;
        smooth = smooth > 0f ? smooth : 0.25f;
        gammaInv = gammaInv > 0f ? gammaInv : 1f / 2.2f;
        toe = toe > 0f ? toe : 0.05f;
        float sw = 0f, swl = 0f;
        for (int k = 0; k < 4; k++) {
            float l = (float) Math.pow(Math.max(acescg(I * em[k], toe), 0.000001f), gammaInv);
            float d = l - center;
            float w = (float) Math.exp(-0.5f * (d * d) / Math.max(smooth * smooth, 0.001f)) * lw[k] + 0.000001f;
            sw += w;
            swl += w * l;
        }
        return swl / sw;
    }

    /** ark/gf.glsl MODE 3's envelope of a neutral flat area: the guided filter's value there (float). */
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

    /** ark/combine.glsl pointFused() of a guide I on the table (linear between entries, clamped at its ends). */
    private static float lookup(float[] table, float I) {
        int n = table.length;
        float t = Math.max(0f, Math.min(n - 1f, ((float) (Math.log(Math.max(I, 1.0e-12f)) / Math.log(2.0)) - FusedCurve.LOG2_MIN) * FusedCurve.PER_EV));
        int i0 = Math.min((int) t, n - 1), i1 = Math.min(i0 + 1, n - 1);
        return Math.max(table[i0] + (table[i1] - table[i0]) * (t - i0), 0f);
    }

    private static float[] exposures(float effS, float effH) {
        ArkAe.Result r = new ArkAe.Result();
        r.effS = effS;
        r.effH = effH;
        return ArkAe.exposures(r);
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
        // monoU up to 0.1 (ArkTone.fusionMono: 0.04); a steeper rise makes the shader's own gate (fj >= 0.5) jump between entries
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

    /** The host model of the design (tools/fused_curve_model.py, effS 1.5, effH 1, mono 0.04) at three entries. */
    @Test
    public void theTableMatchesTheHostModel() {
        float[] table = new float[FusedCurve.SIZE];
        FusedCurve.fill(table, exposures(1.5f, 1.0f), LW, 0.6f, 0.25f, 1f / 2.2f, 0.05f, 0.04f);
        assertEquals(353, FusedCurve.SIZE);
        assertEquals(0.53759, table[(int) ((-4 - FusedCurve.LOG2_MIN) * FusedCurve.PER_EV)], 2e-5);
        assertEquals(0.90395, table[(int) ((0 - FusedCurve.LOG2_MIN) * FusedCurve.PER_EV)], 2e-5);
        assertEquals(0.99435, table[(int) ((2 - FusedCurve.LOG2_MIN) * FusedCurve.PER_EV)], 2e-5);
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
}
