package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P212h (A5 = M5): ArkAe's noise gain clamp from the merged image's noise - N_eff of the merge's effective-frame map (the worker's
 * codes, 64 = its median coverage), the model divided by N_eff^0.8 for the clamp only.
 */
public class ArkNoiseClampTest {
    /** The ISO 6400 model of the owner's log-2026-10-17 (unit B, PHY110): ae 1.1545 = its one-frame clamp at 10 / 11 N. */
    private static final double NOISE_S = 0.0036539782, NOISE_O = 4.8773218E-5;

    private static ByteBuffer map(int w, int h, int code) {
        ByteBuffer b = ByteBuffer.allocateDirect(w * h);
        for (int i = 0; i < w * h; i++) b.put(i, (byte) code);
        return b;
    }

    @Test
    public void aModelDividedByNRaisesTheClampBySqrtN() {
        final double one = ArkAe.gainClamp(NOISE_S, NOISE_O, 0.05);
        assertEquals("the log's clamp", 1.154, one, 1e-3);
        for (int n : new int[]{2, 4, 9, 16}) {
            final double merged = ArkAe.gainClamp(NOISE_S / n, NOISE_O / n, 0.05);
            assertEquals("x sqrt(" + n + ")", one * Math.sqrt(n), merged, 1e-9 * merged);
        }
        // the clamp never goes below 1 (a noisy model only stops the boost)
        assertEquals(1.0, ArkAe.gainClamp(1.0, 1.0, 0.05), 0.0);
        // N_eff^0.8 on the model: N_eff^0.4 on the clamp
        assertEquals(Math.pow(8, -0.8), ArkNoiseClamp.modelScale(8f), 1e-12);
        assertEquals(1.0, ArkNoiseClamp.modelScale(1f), 0.0);
        assertEquals(1.0, ArkNoiseClamp.modelScale(0.5f), 0.0);
    }

    @Test
    public void nEffIsTheLowQuartileOfTheMapClampedToTheMergedN() {
        final int w = 640, h = 480;
        // a still scene: every pixel at the median coverage; the coverage counts in frames (6.86 for 6 N) or more (22 for 8 N)
        assertEquals(6f, ArkNoiseClamp.effectiveFrames(map(w, h, 64), w, h, 6.857f, 6), 1e-6f);
        assertEquals(8f, ArkNoiseClamp.effectiveFrames(map(w, h, 64), w, h, 22.05f, 8), 1e-6f);
        // a median pixel that did not get all N: the coverage, not N
        assertEquals(3f, ArkNoiseClamp.effectiveFrames(map(w, h, 64), w, h, 3f, 10), 1e-6f);
        // motion: a third of the frame at a quarter of the median coverage - the 25th percentile is that part
        ByteBuffer moving = map(w, h, 64);
        for (int y = 0; y < h / 3; y++) for (int x = 0; x < w; x++) moving.put(y * w + x, (byte) 16);
        assertEquals("8 N x 16 / 64", 2f, ArkNoiseClamp.effectiveFrames(moving, w, h, 22.05f, 8), 1e-6f);
        // a sliver that is rejected (10 %) stays above the quartile: the whole N
        ByteBuffer sliver = map(w, h, 64);
        for (int y = 0; y < h / 10; y++) for (int x = 0; x < w; x++) sliver.put(y * w + x, (byte) 8);
        assertEquals(8f, ArkNoiseClamp.effectiveFrames(sliver, w, h, 22.05f, 8), 1e-6f);
        // zeros (unknown) are left out; at most the merged N, at least 1
        ByteBuffer holes = map(w, h, 0);
        for (int i = 0; i < w * h; i += 3) holes.put(i, (byte) 64);
        assertEquals(8f, ArkNoiseClamp.effectiveFrames(holes, w, h, 22.05f, 8), 1e-6f);
        assertEquals(1f, ArkNoiseClamp.effectiveFrames(map(w, h, 1), w, h, 6f, 6), 1e-6f);
    }

    @Test
    public void aMissingOrMismatchedMapKeepsTodaysOneFrameClamp() {
        final int w = 640, h = 480;
        assertEquals(1f, ArkNoiseClamp.effectiveFrames(null, w, h, 22f, 8), 0f);
        assertEquals("another size (a shot's map against another output)", 1f,
                ArkNoiseClamp.effectiveFrames(map(w, h, 64), w * 2, h * 2, 22f, 8), 0f);
        assertEquals("no median in the report", 1f, ArkNoiseClamp.effectiveFrames(map(w, h, 64), w, h, Float.NaN, 8), 0f);
        assertEquals("one N", 1f, ArkNoiseClamp.effectiveFrames(map(w, h, 64), w, h, 22f, 1), 0f);
        assertEquals("all unknown", 1f, ArkNoiseClamp.effectiveFrames(map(w, h, 0), w, h, 22f, 8), 0f);
        ByteBuffer partial = map(w, h, 64);
        partial.limit(w * h / 2);
        assertEquals("a map read in part", 1f, ArkNoiseClamp.effectiveFrames(partial, w, h, 22f, 8), 0f);

        ArkAe.Settings s = new ArkAe.Settings();
        s.noiseS = NOISE_S;
        s.noiseO = NOISE_O;
        assertNull("the old model of post_ab keeps the one-frame clamp", ArkNoiseClamp.apply(s, true, true, map(w, h, 64), w, h, 22f, 10));
        assertEquals(NOISE_S, s.noiseS, 0.0);
        final String kept = ArkNoiseClamp.apply(s, true, false, null, w, h, 22f, 10);
        assertNotNull(kept);
        assertTrue(kept, kept.contains("Neff=1") && kept.contains("no frame map") && kept.contains("1.154 kept"));
        assertEquals(NOISE_S, s.noiseS, 0.0);
        assertEquals(NOISE_O, s.noiseO, 0.0);
        assertNull("not FULLRES: 12 MP keeps it by default", ArkNoiseClamp.apply(s, false, false, map(w, h, 64), w, h, 22f, 10));
        ArkAe.Settings none = new ArkAe.Settings();
        assertNull("no model, no clamp", ArkNoiseClamp.apply(none, true, false, map(w, h, 64), w, h, 22f, 10));
    }

    /** A night FULLRES frame (p50 about 0.006) whose exposure stood at the one-frame clamp brightens to N^0.4 of it, at most maxBoost. */
    @Test
    public void aNightShotAtTheOneFrameClampBrightensByNToThe04() {
        final int w = 96, h = 64;
        float[] px = new float[w * h * 3];
        for (int i = 0; i < w * h; i++) {
            final float v = 0.002f + 0.01f * ((i * 7) % 13) / 12f;
            px[3 * i] = v * 0.9f;
            px[3 * i + 1] = v;
            px[3 * i + 2] = v * 0.8f;
        }
        ArkAe.Settings s = new ArkAe.Settings();
        s.noiseS = NOISE_S;
        s.noiseO = NOISE_O;
        final ArkAe.Result single = ArkAe.smartHdr(FloatBuffer.wrap(px), 3, w, h, s, 6400, 6400);
        assertEquals("the one-frame clamp binds", single.maxGainByNoise, single.ae, 1e-4f);
        final String line = ArkNoiseClamp.apply(s, true, false, map(640, 480, 64), 640, 480, 32.6f, 10);
        assertNotNull(line);
        assertTrue(line, line.startsWith("AE noise clamp: Neff=10.00") && line.contains("1.154 -> "));
        final ArkAe.Result merged = ArkAe.smartHdr(FloatBuffer.wrap(px), 3, w, h, s, 6400, 6400);
        assertEquals("x 10^0.4", single.maxGainByNoise * Math.pow(10, 0.4), merged.maxGainByNoise, 1e-3);
        assertEquals("brighter by 0.4 log2(10) = 1.33 EV", single.ae * Math.pow(10, 0.4), merged.ae, 1e-3);
        assertTrue(merged.ae <= s.maxBoost + 1e-6f);
        // a day shot under the clamp does not move
        for (int i = 0; i < px.length; i++) px[i] *= 30f;
        final ArkAe.Settings day = new ArkAe.Settings();
        day.noiseS = NOISE_S;
        day.noiseO = NOISE_O;
        final float before = ArkAe.smartHdr(FloatBuffer.wrap(px), 3, w, h, day, 6400, 6400).ae;
        ArkNoiseClamp.apply(day, true, false, map(640, 480, 64), 640, 480, 32.6f, 10);
        assertEquals(before, ArkAe.smartHdr(FloatBuffer.wrap(px), 3, w, h, day, 6400, 6400).ae, 0f);
    }

    /** The N a merge took: the burst's normals, fewer when the worker left N frames of a series out. */
    @Test
    public void theMergedNComesFromTheWorkersSeriesLine() {
        assertEquals(10, ScamHybridBurst.seriesNormals(10, null));
        assertEquals(10, ScamHybridBurst.seriesNormals(10, "HYBRID GPU: ok\n"));
        assertEquals(7, ScamHybridBurst.seriesNormals(10, "x\nHYBRID SERIES: merged 9 of 12 sent (N frames 7 of 10)\n"));
        assertEquals("the retry's line, the last one", 5, ScamHybridBurst.seriesNormals(10,
                "HYBRID SERIES: merged 9 of 12 sent (N frames 7 of 10)\nRETRY:\nHYBRID SERIES: merged 7 of 12 sent (N frames 5 of 10)\n"));
        assertEquals("never above the burst's", 6, ScamHybridBurst.seriesNormals(6, "HYBRID SERIES: merged 12 of 12 sent (N frames 10 of 10)"));
        assertEquals(1, ScamHybridBurst.seriesNormals(0, null));
    }

    /** The worker's report line gives the frames of code 64. */
    @Test
    public void theReportLineGivesTheMedianCoverage() {
        assertEquals(22.049706f, ScamNeuralClient.reportNumber("HYBRID EFFECTIVE MAP: median frames=22.049706 code scale=2.902533",
                ScamNeuralClient.EFFECTIVE_MAP_LINE, "median frames=", Float.NaN), 1e-6f);
        assertTrue(Float.isNaN(ScamNeuralClient.reportNumber("HYBRID GPU: ok", ScamNeuralClient.EFFECTIVE_MAP_LINE, "median frames=", Float.NaN)));
    }
}
