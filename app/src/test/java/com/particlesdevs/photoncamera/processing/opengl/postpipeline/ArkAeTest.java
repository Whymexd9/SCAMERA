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
            // P159: effH 2 (the highlight strength reads the bright area beyond the ceiling of 4, ArkAe.tailBin) instead of the
            // native 1.31440628, which saw the 7x Bento corner at the ceiling; everything else as the reference
            {"bento", 1.0, true, 0, 0, 0, 4, "", new double[]{0.784602368, 4, 0.0029296875, 0.177734375, 3.99902344, 3.99902344, 0.110117321, 1, 1.73614505, 0.841465513, 2.0, 2.7, -8.5, 0.158040365}},
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
    /**
     * P159: a bright area beyond the ceiling (an LED screen at 2-3 stops over the hall's white: 7 % of the frame at 10x the
     * hall) is measured by the tail: p98x / p995x at its level, the highlight strength up to its setting; without data above
     * the ceiling nothing changes.
     */
    @Test
    public void theHighlightStrengthReadsTheBrightAreaBeyondTheCeiling() {
        final int w = 100, h = 100;
        ArkAe.Rows rows = (y, row) -> {
            for (int x = 0; x < w; x++) {
                boolean screen = y < 8 && x < 90;                       // 7.2 % of the frame
                float v = screen ? 10f : 0.05f + 0.6f * x / w;
                row[3 * x] = v;
                row[3 * x + 1] = v;
                row[3 * x + 2] = v * (screen ? 1.2f : 1f);
            }
        };
        ArkAe.Settings s = new ArkAe.Settings();
        ArkAe.Stats st = ArkAe.stats(rows, 3, w, h, 0);
        double above = 0;
        for (double v : st.tail) above += v;
        assertEquals("the screen's pixels in the tail", 720, above, 0);
        ArkAe.Result r = ArkAe.finish(st, s, 400, 6400);
        assertEquals("p98 at the ceiling", 4.0, r.p98, 0.01);
        assertTrue("p98x at the screen's level: " + r.p98x, r.p98x > 9f && r.p98x < 13f);
        assertTrue("the highlight strength near its setting: " + r.effH, r.effH > 1.9f && r.effH <= s.highlightStr);
        // the exposure does not read the tail
        ArkAe.Stats noTail = ArkAe.stats(rows, 3, w, h, 0);
        java.util.Arrays.fill(noTail.tail, 0);
        ArkAe.Result native_ = ArkAe.finish(noTail, s, 400, 6400);
        assertEquals("the same exposure", native_.ae, r.ae, 0f);
        assertTrue("the native strength was smaller: " + native_.effH, native_.effH < r.effH);
        // nothing above the ceiling: as before
        ArkAe.Result dim = ArkAe.finish(ArkAe.stats((y, row) -> java.util.Arrays.fill(row, 0.3f), 3, w, h, 0), s, 400, 6400);
        assertEquals(dim.p98, dim.p98x, 0f);
        assertEquals(dim.p995, dim.p995x, 0f);
    }

    /** A room up to 0.9, a window (2 % of the frame) at 4.5 and a crane in it (0.3 %) at 15, one site at {@code peak}. */
    private static ArkAe.Rows window(float peak) {
        return (y, row) -> {
            for (int x = 0; x < 200; x++) {
                float v = 0.01f + 0.9f * x / 200f;
                if (y < 4 && x < 100) v = 4.5f;                              // 400 of 20000
                if (y == 4 && x < 60) v = 15f;                                // 60 of 20000
                if (y == 50 && x == 50) v = peak;
                row[3 * x] = v;
                row[3 * x + 1] = v;
                row[3 * x + 2] = v;
            }
        };
    }

    /**
     * P216 (owner, X200 Ultra bridge window under the x8 ultrashort: white with an orange smear): with real Bento headroom
     * above the native cap of 4 the result carries the real ceiling (the maximum, at most 16 x the clip) and the highlight
     * peak moves hrMix of the way (in log) to p99.9, its strength capped by what brings the ceiling to bt; without Bento, or
     * without data above 4, every field stays as before.
     */
    @Test
    public void realBentoHeadroomRaisesTheHighlightStrength() {
        ArkAe.Stats st = ArkAe.stats(window(23.5f), 3, 200, 100, 0);
        ArkAe.Settings off = new ArkAe.Settings();
        ArkAe.Result before = ArkAe.finish(st, off, 400, 6400);
        assertEquals("no Bento: no real ceiling", 0f, before.hr, 0f);
        ArkAe.Settings on = new ArkAe.Settings();
        on.bentoHeadroom = true;
        ArkAe.Result r = ArkAe.finish(st, on, 400, 6400);
        assertEquals("the real ceiling is the maximum", 23.5f, r.hr, 1e-4f);
        assertEquals("the native clip stays capped", 4f, r.clip, 0f);
        assertTrue("never darker than the native exposure: " + before.ae + " -> " + r.ae, r.ae >= before.ae);
        assertTrue("a stronger highlight strength: " + before.effH + " -> " + r.effH, r.effH > before.effH + 0.1f);
        double cap = Math.log(r.hr * r.ae / on.brightThresh) / Math.log(2) / 1.5;
        assertTrue("within the ceiling's cap", r.effH <= cap + 1e-6);
        // the peak lies between P159's and p99.9's (half way in log by default; all the way with hrMix 1)
        on.hrMix = 1f;
        ArkAe.Result full = ArkAe.finish(st, on, 400, 6400);
        assertTrue("hrMix 1 goes further: " + full.effH, full.effH > r.effH);
        on.hrMix = 0f;
        ArkAe.Result none = ArkAe.finish(st, on, 400, 6400);
        assertEquals("hrMix 0: no pull", 0f, none.hrPull, 0f);
        assertTrue("hrMix 0 stays below the half pull: " + none.effH, none.effH < r.effH);
        assertEquals("the pull leaves the exposure", r.ae, none.ae, 0f);
        // a stray maximum far above: the ceiling stops at 16 x the clip
        on.hrMix = 0.5f;
        assertEquals(64f, ArkAe.finish(ArkAe.stats(window(500f), 3, 200, 100, 0), on, 400, 6400).hr, 0f);
        // Bento without data above the cap of 4: identical to the native result
        ArkAe.Rows dim = (y, row) -> { for (int x = 0; x < 200; x++) { float v = 0.01f + 3.5f * x / 200f; row[3 * x] = v; row[3 * x + 1] = v; row[3 * x + 2] = v; } };
        ArkAe.Stats ds = ArkAe.stats(dim, 3, 200, 100, 0);
        ArkAe.Result a = ArkAe.finish(ds, off, 400, 6400), b = ArkAe.finish(ds, on, 400, 6400);
        assertEquals(0f, b.hr, 0f);
        assertEquals(a.describe(), b.describe());
        // a light source (the sun over a deck: 0.1 % of the frame beyond the ceiling) keeps P159's peak - the sky does not
        // darken (owner, 2026-10-18); its ceiling still drives the shoulder and the landing
        ArkAe.Rows sun = (y, row) -> {
            for (int x = 0; x < 200; x++) {
                float v = y < 2 && x < 10 ? 15f : 0.01f + 0.9f * x / 200f;
                row[3 * x] = v; row[3 * x + 1] = v; row[3 * x + 2] = v;
            }
        };
        ArkAe.Stats ss = ArkAe.stats(sun, 3, 200, 100, 0);
        ArkAe.Result sHr = ArkAe.finish(ss, on, 400, 6400);
        on.hrMix = 0f;
        ArkAe.Result sNoPull = ArkAe.finish(ss, on, 400, 6400);
        on.hrMix = 0.5f;
        assertEquals(15f, sHr.hr, 0f);
        assertEquals(0f, sHr.hrPull, 0f);
        assertEquals("no pull towards the light's glow", sNoPull.effH, sHr.effH, 0f);
        assertEquals("the bridge window pulls fully", 0.5f, r.hrPull, 1e-6f);
        // a bright Bento scene with real headroom (a sunlit sky: geometric mean 0.5 against the target 0.15, the sun at 15) keeps
        // its captured exposure instead of being pulled down to the target grey; without Bento the native AE darkens it to its limit
        ArkAe.Stats bright = ArkAe.stats((y, row) -> { java.util.Arrays.fill(row, 0.5f); if (y == 0) for (int i = 0; i < 6; i++) row[i] = 15f; }, 3, 200, 100, 0);
        assertEquals("native: down to the minimum", off.minLimit, ArkAe.finish(bright, off, 400, 6400).ae, 1e-6f);
        assertEquals("Bento: the captured exposure", 1f, ArkAe.finish(bright, on, 400, 6400).ae, 1e-6f);
    }

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
                int[] hy = new int[ArkAe.BINS], hm = new int[ArkAe.BINS], tail = new int[ArkAe.TAIL_BINS];
                for (int i = 0; i < ArkAe.BINS; i++) {
                    hy[i] = (int) st.hy[i];
                    hm[i] = (int) st.hm[i];
                }
                for (int i = 0; i < ArkAe.TAIL_BINS; i++) tail[i] = (int) st.tail[i];
                int[] sums = {Float.floatToRawIntBits((float) (st.sumY * 0.25)), Float.floatToRawIntBits((float) (st.sumLog * 0.25)),
                        Float.floatToRawIntBits((float) (st.sumY * 0.75)), Float.floatToRawIntBits((float) (st.sumLog * 0.75))};
                ArkAe.Result gpu = ArkAe.finish(ArkAe.fromGpu(ArkAe.maxKey(st.gmax), hy, hm, sums, tail), s, 400, 6400);
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
