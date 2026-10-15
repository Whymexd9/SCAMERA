package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.nio.FloatBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * ArkAe (Java port of the ArkCam Smart-HDR AE) against the Python reference research/hybrid5/ref/tone/ark_ae.py, which
 * matches libfc_suppressor.so under emulation within 5e-5. Expected values: scratchpad h5_arktone/gen_ae_cases.py on the
 * same synthetic image (rational formula, identical in float64 in both languages, then cast to float32).
 */
public class ArkAeTest {
    private static final int W = 96, H = 64;

    private static FloatBuffer synth(double scale, boolean bento) {
        float[] out = new float[W * H * 3];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double t = x / (double) (W - 1), u = y / (double) (H - 1);
                double base = 0.002 + 1.5 * t * t * t;
                double r = base * (0.7 + 0.6 * u), g = base * (1.0 - 0.3 * u * t), b = base * (0.5 + 0.8 * ((x * 7 + y * 3) % 11) / 10.0);
                double k = bento && x >= 80 && y < 12 ? 7.0 : 1.0;
                int o = (y * W + x) * 3;
                out[o] = (float) (r * scale * k);
                out[o + 1] = (float) (g * scale * k);
                out[o + 2] = (float) (b * scale * k);
            }
        }
        return FloatBuffer.wrap(out);
    }

    // name, scale, bento, metering, iso, max iso, look, overrides,
    // {ae, clip, p10, p50, p98, p99.5, geo, nf, deficit, effS, effH, slope, minEv, ovArea}
    private static final Object[][] CASES = {
            {"day", 1.0, false, 0, 0, 0, 4, "", new double[]{1.20914799, 1.9526, 0.00286025391, 0.177812451, 1.65656372, 1.83199263, 0.103620653, 1, 1.19719661, 0.566959553, 0.93236604, 2.7, -8.5, 0.26578776}},
            {"dark", 0.05, false, 0, 0, 0, 4, "", new double[]{5, 1, 0, 0.0087890625, 0.0827636719, 0.0915527344, 0.0051851803, 1, 1, 0.457088279, 0, 2.7, -8.5, 0}},
            {"bento", 1.0, true, 0, 0, 0, 4, "", new double[]{0.784602368, 4, 0.0029296875, 0.177734375, 3.99902344, 3.99902344, 0.110117321, 1, 1.73614505, 0.841465513, 1.31440628, 2.7, -8.5, 0.158040365}},
            {"iso400c", 1.0, false, 1, 400, 6400, 4, "", new double[]{1.1336874, 1.9526, 0.00858076173, 0.181149414, 1.50878394, 1.77335742, 0.124550977, 0.888888889, 1.01053609, 0.373311703, 0.811725578, 2.7, -8.5, 0.164631429}},
            {"iso6400s", 0.3, true, 2, 6400, 6400, 1, "", new double[]{0.807773814, 4, 0.0283203125, 0.0546875, 0.149414062, 0.170898438, 0.0530558731, 0, 1, 0, 0, 3.12951265, -7.5, 0}},
            {"flat", 0.02, false, 0, 0, 0, 4, "dark_pixel=40", new double[]{5, 1, 0, 0.00341796875, 0.0329589844, 0.0366210938, 0.00225962501, 1, 1, 0.309606331, 0, 3.1465642, -8.5, 0}},
            {"dim", 1.0, false, 0, 800, 10000, 0, "night_dim=0.8", new double[]{1.14236049, 1.9526, 0.00286025391, 0.177812451, 1.65656372, 1.83199263, 0.103620653, 0.796107119, 1.09002501, 0.38988261, 0.877717478, 2.7, -10, 0.252115885}},
    };

    @Test
    public void smartHdrMatchesThePythonReference() {
        for (Object[] c : CASES) {
            String name = (String) c[0];
            ArkAe.Settings s = new ArkAe.Settings();
            s.metering = (Integer) c[3];
            s.look = (Integer) c[6];
            if (c[7].equals("dark_pixel=40")) s.darkPixelThresh = 40f;
            if (c[7].equals("night_dim=0.8")) s.nightDim = 0.8f;
            ArkAe.Result r = ArkAe.smartHdr(synth((Double) c[1], (Boolean) c[2]), 3, W, H, s, (Integer) c[4], (Integer) c[5]);
            double[] e = (double[]) c[8];
            double[] got = {r.ae, r.clip, r.p10, r.p50, r.p98, r.p995, r.geo, r.nf, r.deficit, r.effS, r.effH, r.slope, r.minEv, r.ovArea};
            String[] what = {"ae", "clip", "p10", "p50", "p98", "p995", "geo", "nf", "deficit", "effS", "effH", "slope", "minEv", "ovArea"};
            for (int i = 0; i < e.length; i++)
                assertEquals(name + " " + what[i], e[i], got[i], 1e-4 * Math.max(1.0, Math.abs(e[i])));
        }
    }

    @Test
    public void exposuresFollowTheFusionMultipliers() {
        ArkAe.Result r = new ArkAe.Result();
        r.effS = 1.5f;
        r.effH = 2f;
        float[] em = ArkAe.exposures(r);
        assertEquals(0.125f, em[0], 1e-6f);
        assertEquals(1f, em[1], 0f);
        assertEquals((float) Math.pow(2, 1.5), em[2], 1e-5f);
        assertEquals(8f, em[3], 1e-5f);
        r.effS = 3f;
        em = ArkAe.exposures(r);
        assertEquals(8f, em[2], 0f);
        assertEquals(16f, em[3], 0f);
    }

    /**
     * P145: the GPU statistics path (ArkAeGpu: the max key, two histograms of counts, per-group float sums) decodes into the
     * same exposure as the CPU count, for every metering and with a highlight; the max key orders negatives and positives.
     */
    @Test
    public void gpuStatisticsGiveTheSameExposure() {
        for (int mode = 0; mode <= 2; mode++) {
            for (boolean bento : new boolean[]{false, true}) {
                FloatBuffer px = synth(1.0, bento);
                ArkAe.Settings s = new ArkAe.Settings();
                s.metering = mode;
                ArkAe.Result cpu = ArkAe.smartHdr(px, 3, W, H, s, 400, 6400);
                ArkAe.Stats st = ArkAe.stats((y, row) -> {
                    FloatBuffer d = px.duplicate();
                    d.position(y * W * 3);
                    d.get(row, 0, W * 3);
                }, 3, W, H, mode);
                // as the shader leaves them: counts, and the sums split over a few groups in float
                int[] hy = new int[ArkAe.BINS], hm = new int[ArkAe.BINS];
                for (int i = 0; i < ArkAe.BINS; i++) {
                    hy[i] = (int) st.hy[i];
                    hm[i] = (int) st.hm[i];
                }
                int[] sums = {Float.floatToRawIntBits((float) (st.sumY * 0.25)), Float.floatToRawIntBits((float) (st.sumLog * 0.25)),
                        Float.floatToRawIntBits((float) (st.sumY * 0.75)), Float.floatToRawIntBits((float) (st.sumLog * 0.75))};
                ArkAe.Result gpu = ArkAe.finish(ArkAe.fromGpu(ArkAe.maxKey(st.gmax), hy, hm, sums), s, 400, 6400);
                String c = "metering " + mode + (bento ? " bento" : "");
                assertEquals(c, cpu.gmax, gpu.gmax, 0f);
                assertEquals(c, cpu.clip, gpu.clip, 0f);
                assertEquals(c, cpu.p10, gpu.p10, 0f);
                assertEquals(c, cpu.p50, gpu.p50, 0f);
                assertEquals(c, cpu.p98, gpu.p98, 0f);
                assertEquals(c, cpu.p995, gpu.p995, 0f);
                assertEquals(c, cpu.ovArea, gpu.ovArea, 0f);
                assertEquals(c, cpu.ae, gpu.ae, cpu.ae * 1e-6f);
                assertEquals(c, cpu.geo, gpu.geo, cpu.geo * 1e-6f);
                assertEquals(c, cpu.effS, gpu.effS, 1e-5f);
                assertEquals(c, cpu.effH, gpu.effH, 1e-5f);
            }
        }
        // the key keeps the order of floats across the sign
        float[] v = {-3.5f, -0.01f, -0f, 0f, 1e-6f, 0.5f, 1.05f, 3.99f, 65504f};
        for (int i = 1; i < v.length; i++)
            assertTrue(v[i] + " above " + v[i - 1], Integer.compareUnsigned(ArkAe.maxKey(v[i]), ArkAe.maxKey(v[i - 1])) >= 0);
        for (float f : v) assertEquals(Math.max(f, -1000f), ArkAe.fromGpu(ArkAe.maxKey(f), new int[ArkAe.BINS], new int[ArkAe.BINS], new int[0]).gmax, 0f);
        assertEquals("no finite sample", -1000f, ArkAe.fromGpu(0, new int[ArkAe.BINS], new int[ArkAe.BINS], new int[0]).gmax, 0f);
        // the metering weight the GPU result is checked against
        assertEquals(96.0 * 64, ArkAeGpu.totalWeight(96, 64, 0), 0);
        assertEquals(ArkAe.stats((y, row) -> java.util.Arrays.fill(row, 0.2f), 3, 96, 64, 1).tot, ArkAeGpu.totalWeight(96, 64, 1), 0);
        assertEquals(ArkAe.stats((y, row) -> java.util.Arrays.fill(row, 0.2f), 3, 96, 64, 2).tot, ArkAeGpu.totalWeight(96, 64, 2), 0);
    }
}
