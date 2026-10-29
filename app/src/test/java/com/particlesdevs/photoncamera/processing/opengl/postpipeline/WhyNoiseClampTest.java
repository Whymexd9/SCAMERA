package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P212h (A5 = M5): WhyAe's noise gain clamp from the merged image's noise - N_eff of the merge's effective-frame map (the worker's
 * codes, 64 = its median coverage), the model divided by N_eff^0.8 for the clamp only.
 */
public class WhyNoiseClampTest {
    /** The ISO 6400 model of the owner's log-2026-10-17 (unit B, PHY110): ae 1.1545 = its one-frame clamp at 10 / 11 N. */
    private static final double NOISE_S = 0.0036539782, NOISE_O = 4.8773218E-5;

    private static ByteBuffer map(int w, int h, int code) {
        ByteBuffer b = ByteBuffer.allocateDirect(w * h);
        for (int i = 0; i < w * h; i++) b.put(i, (byte) code);
        return b;
    }

    @Test
    public void aModelDividedByNRaisesTheClampBySqrtN() {
        final double one = WhyAe.gainClamp(NOISE_S, NOISE_O, 0.05);
        assertEquals("the log's clamp", 1.154, one, 1e-3);
        for (int n : new int[]{2, 4, 9, 16}) {
            final double merged = WhyAe.gainClamp(NOISE_S / n, NOISE_O / n, 0.05);
            assertEquals("x sqrt(" + n + ")", one * Math.sqrt(n), merged, 1e-9 * merged);
        }
        // the clamp never goes below 1 (a noisy model only stops the boost)
        assertEquals(1.0, WhyAe.gainClamp(1.0, 1.0, 0.05), 0.0);
        // N_eff^0.8 on the model: N_eff^0.4 on the clamp
        assertEquals(Math.pow(8, -0.8), WhyNoiseClamp.modelScale(8f), 1e-12);
        assertEquals(1.0, WhyNoiseClamp.modelScale(1f), 0.0);
        assertEquals(1.0, WhyNoiseClamp.modelScale(0.5f), 0.0);
    }

    /**
     * P391 (review): the WHY fusion's N_eff - the map's where the map is the output's size and the worker reported its median, else
     * (200 MP: no map above 100 MP) 0.55 of the merged N as the RAW viewfinder expects, not 1; a one-N shot 1.
     */
    @Test
    public void theFusionsNEffFallsBackToTheViewfindersExpectationWithoutAMap() {
        final int w = 640, h = 480;
        assertEquals(8f, WhyNoiseClamp.fusionFrames(map(w, h, 64), w, h, 22.05f, 8), 1e-6f);
        assertEquals("the map at one frame stays one", 1f, WhyNoiseClamp.fusionFrames(map(w, h, 1), w, h, 22.05f, 8), 1e-6f);
        assertEquals("no map: 0.55 x 22 N", 0.55f * 22, WhyNoiseClamp.fusionFrames(null, w, h, 16.4f, 22), 1e-5f);
        assertEquals("a map of another size", 0.55f * 22, WhyNoiseClamp.fusionFrames(map(w / 2, h, 64), w, h, 16.4f, 22), 1e-5f);
        assertEquals("no median in the report", 0.55f * 22, WhyNoiseClamp.fusionFrames(map(w, h, 64), w, h, Float.NaN, 22), 1e-5f);
        assertEquals("two N: at least 1", 1.1f, WhyNoiseClamp.fusionFrames(null, w, h, Float.NaN, 2), 1e-6f);
        assertEquals("one N", 1f, WhyNoiseClamp.fusionFrames(null, w, h, Float.NaN, 1), 0f);
        // the clamp's own N_eff keeps 1 without a map (P212h)
        assertEquals(1f, WhyNoiseClamp.effectiveFrames(null, w, h, 16.4f, 22), 0f);
    }

    @Test
    public void nEffIsTheLowQuartileOfTheMapClampedToTheMergedN() {
        final int w = 640, h = 480;
        // a still scene: every pixel at the median coverage; the coverage counts in frames (6.86 for 6 N) or more (22 for 8 N)
        assertEquals(6f, WhyNoiseClamp.effectiveFrames(map(w, h, 64), w, h, 6.857f, 6), 1e-6f);
        assertEquals(8f, WhyNoiseClamp.effectiveFrames(map(w, h, 64), w, h, 22.05f, 8), 1e-6f);
        // a median pixel that did not get all N: the coverage, not N
        assertEquals(3f, WhyNoiseClamp.effectiveFrames(map(w, h, 64), w, h, 3f, 10), 1e-6f);
        // motion: a third of the frame at a quarter of the median coverage - the 25th percentile is that part
        ByteBuffer moving = map(w, h, 64);
        for (int y = 0; y < h / 3; y++) for (int x = 0; x < w; x++) moving.put(y * w + x, (byte) 16);
        assertEquals("8 N x 16 / 64", 2f, WhyNoiseClamp.effectiveFrames(moving, w, h, 22.05f, 8), 1e-6f);
        // a sliver that is rejected (10 %) stays above the quartile: the whole N
        ByteBuffer sliver = map(w, h, 64);
        for (int y = 0; y < h / 10; y++) for (int x = 0; x < w; x++) sliver.put(y * w + x, (byte) 8);
        assertEquals(8f, WhyNoiseClamp.effectiveFrames(sliver, w, h, 22.05f, 8), 1e-6f);
        // zeros (unknown) are left out; at most the merged N, at least 1
        ByteBuffer holes = map(w, h, 0);
        for (int i = 0; i < w * h; i += 3) holes.put(i, (byte) 64);
        assertEquals(8f, WhyNoiseClamp.effectiveFrames(holes, w, h, 22.05f, 8), 1e-6f);
        assertEquals(1f, WhyNoiseClamp.effectiveFrames(map(w, h, 1), w, h, 6f, 6), 1e-6f);
    }

    @Test
    public void aMissingOrMismatchedMapKeepsTodaysOneFrameClamp() {
        final int w = 640, h = 480;
        assertEquals(1f, WhyNoiseClamp.effectiveFrames(null, w, h, 22f, 8), 0f);
        assertEquals("another size (a shot's map against another output)", 1f,
                WhyNoiseClamp.effectiveFrames(map(w, h, 64), w * 2, h * 2, 22f, 8), 0f);
        assertEquals("no median in the report", 1f, WhyNoiseClamp.effectiveFrames(map(w, h, 64), w, h, Float.NaN, 8), 0f);
        assertEquals("one N", 1f, WhyNoiseClamp.effectiveFrames(map(w, h, 64), w, h, 22f, 1), 0f);
        assertEquals("all unknown", 1f, WhyNoiseClamp.effectiveFrames(map(w, h, 0), w, h, 22f, 8), 0f);
        ByteBuffer partial = map(w, h, 64);
        partial.limit(w * h / 2);
        assertEquals("a map read in part", 1f, WhyNoiseClamp.effectiveFrames(partial, w, h, 22f, 8), 0f);

        WhyAe.Settings s = new WhyAe.Settings();
        s.noiseS = NOISE_S;
        s.noiseO = NOISE_O;
        assertNull("the old model of post_ab keeps the one-frame clamp", WhyNoiseClamp.apply(s, true, true, map(w, h, 64), w, h, 22f, 10));
        assertEquals(NOISE_S, s.noiseS, 0.0);
        final String kept = WhyNoiseClamp.apply(s, true, false, null, w, h, 22f, 10);
        assertNotNull(kept);
        assertTrue(kept, kept.contains("Neff=1") && kept.contains("no frame map") && kept.contains("1.154 kept"));
        assertEquals(NOISE_S, s.noiseS, 0.0);
        assertEquals(NOISE_O, s.noiseO, 0.0);
        assertNull("not FULLRES: 12 MP keeps it by default", WhyNoiseClamp.apply(s, false, false, map(w, h, 64), w, h, 22f, 10));
        WhyAe.Settings none = new WhyAe.Settings();
        assertNull("no model, no clamp", WhyNoiseClamp.apply(none, true, false, map(w, h, 64), w, h, 22f, 10));
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
        WhyAe.Settings s = new WhyAe.Settings();
        s.noiseS = NOISE_S;
        s.noiseO = NOISE_O;
        final WhyAe.Result single = WhyAe.smartHdr(FloatBuffer.wrap(px), 3, w, h, s, 6400, 6400);
        assertEquals("the one-frame clamp binds", single.maxGainByNoise, single.ae, 1e-4f);
        final String line = WhyNoiseClamp.apply(s, true, false, map(640, 480, 64), 640, 480, 32.6f, 10);
        assertNotNull(line);
        assertTrue(line, line.startsWith("AE noise clamp: Neff=10.00") && line.contains("1.154 -> "));
        final WhyAe.Result merged = WhyAe.smartHdr(FloatBuffer.wrap(px), 3, w, h, s, 6400, 6400);
        assertEquals("x 10^0.4", single.maxGainByNoise * Math.pow(10, 0.4), merged.maxGainByNoise, 1e-3);
        assertEquals("brighter by 0.4 log2(10) = 1.33 EV", single.ae * Math.pow(10, 0.4), merged.ae, 1e-3);
        assertTrue(merged.ae <= s.maxBoost + 1e-6f);
        // a day shot under the clamp does not move
        for (int i = 0; i < px.length; i++) px[i] *= 30f;
        final WhyAe.Settings day = new WhyAe.Settings();
        day.noiseS = NOISE_S;
        day.noiseO = NOISE_O;
        final float before = WhyAe.smartHdr(FloatBuffer.wrap(px), 3, w, h, day, 6400, 6400).ae;
        WhyNoiseClamp.apply(day, true, false, map(640, 480, 64), 640, 480, 32.6f, 10);
        assertEquals(before, WhyAe.smartHdr(FloatBuffer.wrap(px), 3, w, h, day, 6400, 6400).ae, 0f);
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
