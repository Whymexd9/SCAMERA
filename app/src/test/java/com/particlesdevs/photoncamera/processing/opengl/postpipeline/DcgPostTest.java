package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.GLInterface;
import com.particlesdevs.photoncamera.processing.render.NoiseModeler;
import com.particlesdevs.photoncamera.processing.render.Parameters;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P320: the post of a DCG shot (one exposure read at high and low conversion gain, fused) never runs the plain RAW rules
 * without an explicit check - the denoise reads two noise zones (high gain below the knee, low gain above it) instead of one
 * curve, the WHY AE's night factor reads the ISO over the mode's range scale and keeps a bright scene's captured exposure,
 * the gain clamp takes the offset of mid grey's zone, bracket_denoise leaves the frame's own highlights alone - while every
 * other shot keeps its numbers (and the scamdn shaders their tokens without the DCG define).
 */
@RunWith(RobolectricTestRunner.class)
public class DcgPostTest {
    /** {slope, high-gain offset, low-gain offset, knee}, normalised units: the low-gain read noise ~6 x the high-gain one. */
    private static final float[] ZONES = {2.0e-4f, 1.0e-6f, 4.0e-5f, 0.125f};
    private static final double W2 = 0.2126 * 0.2126 + 0.7152 * 0.7152 + 0.0721996 * 0.0721996;

    @Test public void zonesAreUsableOnlyWhenTheyAreAModel() {
        assertNull(NoiseModeler.dcgZones(null));
        assertNull(NoiseModeler.dcgZones(new float[]{2e-4f, 1e-6f, 4e-5f}));                     // short
        assertNull(NoiseModeler.dcgZones(new float[]{Float.NaN, 1e-6f, 4e-5f, 0.125f}));
        assertNull(NoiseModeler.dcgZones(new float[]{2e-4f, 1e-6f, Float.POSITIVE_INFINITY, 0.125f}));
        assertNull(NoiseModeler.dcgZones(new float[]{0f, 1e-6f, 4e-5f, 0.125f}));                // no slope
        assertNull(NoiseModeler.dcgZones(new float[]{2e-4f, -1e-7f, 4e-5f, 0.125f}));            // negative offset
        assertNull(NoiseModeler.dcgZones(new float[]{2e-4f, 1e-6f, 4e-5f, 0f}));                 // no knee
        assertNull(NoiseModeler.dcgZones(new float[]{2e-4f, 1e-6f, 4e-5f, 1.2f}));               // knee above white
        assertNull(NoiseModeler.dcgZones(new float[]{2e-4f, 1e-6f, 1e-6f, -0.1f}));              // negative knee
        assertNull(NoiseModeler.dcgZones(new float[]{2e-4f, 1e-6f, 4e-5f, 1e-40f}));             // a denormal knee
        // the worker's one measured zone (lcg = hcg, knee 0) is a model: the burst's own single curve
        assertArrayEquals(new float[]{2e-4f, 3e-6f, 3e-6f, 0f}, NoiseModeler.dcgZones(new float[]{2e-4f, 3e-6f, 3e-6f, 0f}), 0f);
        final float[] z = NoiseModeler.dcgZones(ZONES);
        assertArrayEquals(ZONES, z, 0f);
        assertNotSame(ZONES, z);
        assertArrayEquals(ZONES, NoiseModeler.dcgZones(new float[]{2.0e-4f, 1.0e-6f, 4.0e-5f, 0.125f, 9f}), 0f);
        assertArrayEquals(new float[]{2e-4f, 0f, 0f, 1f}, NoiseModeler.dcgZones(new float[]{2e-4f, 0f, 0f, 1f}), 0f);
    }

    /** The host's zone blend is the shaders' mix(hcg, lcg, smoothstep(0.9 t, 1.1 t, Y)). */
    @Test public void zoneOffsetSwitchesOverTenPercentAroundTheKnee() {
        final float hcg = ZONES[1], lcg = ZONES[2], k = ZONES[3];
        assertEquals(hcg, NoiseModeler.zoneOffset(0f, hcg, lcg, k), 0f);
        assertEquals(hcg, NoiseModeler.zoneOffset(0.9f * k, hcg, lcg, k), 0f);
        assertEquals(lcg, NoiseModeler.zoneOffset(1.1f * k, hcg, lcg, k), 1e-11f);
        assertEquals(lcg, NoiseModeler.zoneOffset(1.11f * k, hcg, lcg, k), 0f);
        assertEquals(lcg, NoiseModeler.zoneOffset(0.9f, hcg, lcg, k), 0f);
        assertEquals(0.5f * (hcg + lcg), NoiseModeler.zoneOffset(k, hcg, lcg, k), 1e-10f);
        float last = -1f;
        for (int i = 0; i <= 200; i++) {
            final float y = 0.2f * k + i * (1.6f * k / 200f);
            final float w = NoiseModeler.zoneWeight(y, k);
            // GLSL smoothstep(e0, e1, x): t = clamp((x - e0) / (e1 - e0), 0, 1), t * t * (3 - 2 t)
            final double t = Math.max(0, Math.min(1, (y - 0.9 * k) / (1.1 * k - 0.9 * k)));
            assertEquals("y=" + y, t * t * (3 - 2 * t), w, 1e-5);
            assertTrue("monotone at y=" + y, w >= last);
            last = w;
        }
        assertEquals("no knee: the high-gain zone everywhere", hcg, NoiseModeler.zoneOffset(0.9f, hcg, lcg, 0f), 0f);
        assertEquals(0f, NoiseModeler.zoneWeight(0.5f, Float.NaN), 0f);
        assertEquals(hcg, NoiseModeler.zoneOffset(Float.NaN, hcg, lcg, k), 0f);
        assertEquals(lcg, NoiseModeler.zoneOffset(Float.POSITIVE_INFINITY, hcg, lcg, k), 0f);
    }

    @Test public void denoiseTermsScaleTheMeasuredGreenByTheCameraRatios() {
        // neutral camera channels, white point 1: the luma weights of the single curve on the measured green
        float[] t = ScamDenoiseTables.dcgTerms(ZONES, new float[]{3e-4f, 3e-4f, 3e-4f}, new float[]{5e-6f, 5e-6f, 5e-6f},
                new float[]{1f, 1f, 1f});
        assertEquals(ZONES[0] * W2, t[0], 1e-6 * t[0]);
        assertEquals(ZONES[1] * W2, t[1], 1e-6 * t[1]);
        assertEquals(ZONES[2] * W2, t[2], 1e-6 * t[2]);
        assertEquals(ZONES[0], t[3], 0f);
        assertEquals(ZONES[1], t[4], 0f);
        assertEquals(ZONES[2], t[5], 0f);
        assertEquals(ZONES[3], t[6], 0f);
        // no camera model, no white point: ratios and gains 1 (the same numbers)
        assertArrayEquals(t, ScamDenoiseTables.dcgTerms(ZONES, null, null, null), 0f);
        // channel ratios of the camera model (R 2x, B 1.5x the green slope; R 2x the green offset) and white-balance gains
        final float[] wp = {0.5f, 0.8f, 0.4f};
        t = ScamDenoiseTables.dcgTerms(ZONES, new float[]{4e-4f, 2e-4f, 3e-4f}, new float[]{2e-6f, 1e-6f, 1e-6f}, wp);
        final double gR = 1 / 0.5, gG = 1 / 0.8, gB = 1 / 0.4;
        final double wR = 0.2126, wG = 0.7152, wB = 0.0721996;
        final double s = ZONES[0], oh = ZONES[1], ol = ZONES[2];
        assertEquals(wR * wR * gR * s * 2 + wG * wG * gG * s + wB * wB * gB * s * 1.5, t[0], 1e-6 * t[0]);
        assertEquals(wR * wR * gR * gR * oh * 2 + wG * wG * gG * gG * oh + wB * wB * gB * gB * oh, t[1], 1e-6 * t[1]);
        assertEquals(wR * wR * gR * gR * ol * 2 + wG * wG * gG * gG * ol + wB * wB * gB * gB * ol, t[2], 1e-6 * t[2]);
        assertEquals(gG * s, t[3], 1e-6 * t[3]);
        assertEquals(gG * gG * oh, t[4], 1e-6 * t[4]);
        assertEquals(gG * gG * ol, t[5], 1e-6 * t[5]);
        assertEquals("the knee at a neutral grey's luminance", gG * ZONES[3], t[6], 1e-7);
    }

    /** The finish SNR (the tiers, par.finishSnr) at mu reads mu's zone: the shadows are as clean as the high-gain read. */
    @Test public void finishSnrTakesTheZoneOfMu() {
        final float[] t = ScamDenoiseTables.dcgTerms(ZONES, null, null, null);
        final float g01 = 0.6f, low = 0.05f, high = 0.5f;
        final float snrLow = ScamDenoiseTables.snr(low, g01, t[3], NoiseModeler.zoneOffset(low, t[4], t[5], t[6]), 1.2f, 6f);
        assertEquals(ScamDenoiseTables.snr(low, g01, t[3], t[4], 1.2f, 6f), snrLow, 0f);
        assertEquals(ScamDenoiseTables.snr(high, g01, t[3], t[5], 1.2f, 6f),
                ScamDenoiseTables.snr(high, g01, t[3], NoiseModeler.zoneOffset(high, t[4], t[5], t[6]), 1.2f, 6f), 0f);
        assertTrue("one low-gain curve would understate the shadows' SNR",
                snrLow > ScamDenoiseTables.snr(low, g01, t[3], t[5], 1.2f, 6f) * 1.05f);
    }

    /**
     * The worker's report lines as ScamNeuralClient parses them (Parameters.dcgNoise) reach the post: two zones turn on the
     * DCG_ZONES shaders, one measured zone (its factor line says x1) is the burst's own single curve on the plain shaders.
     */
    @Test public void workerLinesReachThePostAsZones() {
        final float[] two = NoiseModeler.dcgZones(ScamNeuralClient.dcgNoise(
                "HYBRID NOISE CHECK: base vs frame 1, 812 flat of 1024 blocks, measured / model 2.1 -> model x1 (DCG: the measured zones"
                        + " replace the model)\nHYBRID NOISE CHECK DCG: slope=0.0002 hcg=1e-06 lcg=4e-05 knee=0.125 blocks=790/1024 zones=2\n"));
        assertArrayEquals(ZONES, two, 1e-12f);
        float[] t = ScamDenoiseTables.dcgTerms(two, null, null, null);
        assertArrayEquals(new float[]{t[2] / t[1], t[6]}, ScamDenoise.dcgShaderPass(t[1], t[2], t[6]), 0f);
        final float[] one = NoiseModeler.dcgZones(ScamNeuralClient.dcgNoise(
                "HYBRID NOISE CHECK DCG: slope=0.000213 hcg=3.1e-06 lcg=3.1e-06 knee=0 blocks=801/1024 zones=1\n"));
        assertArrayEquals(new float[]{2.13e-4f, 3.1e-6f, 3.1e-6f, 0f}, one, 0f);
        t = ScamDenoiseTables.dcgTerms(one, null, null, null);
        assertEquals("one zone: one read term", t[1], t[2], 0f);
        assertEquals(0f, t[6], 0f);
        assertNull("one zone: the plain shaders (smoothstep(0, 0, Y) is undefined)", ScamDenoise.dcgShaderPass(t[1], t[2], t[6]));
        assertEquals(t[4], NoiseModeler.zoneOffset(0.18f, t[4], t[5], t[6]), 0f);
        final float[] clamp = WhyAe.dcgClampModel(one, 6e-4f, 1.5e-5f, Double.NaN, Double.NaN);
        assertEquals(one[0], clamp[0], 0f);
        assertEquals(one[1], clamp[1], 0f);
        assertNull(ScamDenoise.dcgShaderPass(1e-6f, 4e-5f, Float.NaN));
        assertNull(ScamDenoise.dcgShaderPass(0f, 4e-5f, 0.125f));
    }

    @Test public void denoiseZonesOnlyForADcgShotWithUsableZones() {
        assertNull(ScamDenoise.dcgZones(null));
        final Parameters p = new Parameters();
        assertNull("a plain shot", ScamDenoise.dcgZones(p));
        p.dcgNoise = ZONES.clone();
        assertNull("zones without the DCG flag", ScamDenoise.dcgZones(p));
        p.dcgShot = true;
        assertArrayEquals(ZONES, ScamDenoise.dcgZones(p), 0f);
        p.dcgNoise = new float[]{2e-4f, 1e-6f, 4e-5f, 0f};
        assertNull("unusable zones: the camera's curve", ScamDenoise.dcgZones(p));
        p.dcgNoise = null;
        assertNull(ScamDenoise.dcgZones(p));
    }

    @Test public void gainClampTakesTheOffsetOfMidGreysZone() {
        // knee below 0.5: the low-gain offset, carried by the averaged model's scale over the camera's green (x2, x3)
        float[] m = WhyAe.dcgClampModel(ZONES, 6e-4f, 1.5e-5f, 3e-4, 5e-6);
        assertEquals(ZONES[0] * 2.0, m[0], 1e-6 * m[0]);
        assertEquals(ZONES[2] * 3.0, m[1], 1e-6 * m[1]);
        // knee above 0.5: the high-gain offset
        m = WhyAe.dcgClampModel(new float[]{2e-4f, 1e-6f, 4e-5f, 0.8f}, 6e-4f, 1.5e-5f, 3e-4, 5e-6);
        assertEquals(1e-6 * 3.0, m[1], 1e-6 * m[1]);
        // no camera term: the zones as measured
        m = WhyAe.dcgClampModel(ZONES, 6e-4f, 1.5e-5f, Double.NaN, 0);
        assertEquals(ZONES[0], m[0], 0f);
        assertEquals(ZONES[2], m[1], 0f);
        // the clamp is sigma at 0.5 of that model
        assertEquals(0.05 / Math.sqrt(0.5 * ZONES[0] + ZONES[2]), WhyAe.gainClamp(m[0], m[1], 0.05), 1e-4);
    }

    /**
     * P391 (review): the WHY fusion's noise caps act on the deep shadows, under the knee - the high-gain zone's offset, whatever the
     * knee, carried by the averaged model's scale as the clamp's; the clamp keeps mid grey's zone.
     */
    @Test public void theFusionsNoiseTakesTheDeepShadowsHighGainZone() {
        float[] m = WhyAe.dcgShadowModel(ZONES, 6e-4f, 1.5e-5f, 3e-4, 5e-6);
        assertEquals(ZONES[0] * 2.0, m[0], 1e-6 * m[0]);
        assertEquals(ZONES[1] * 3.0, m[1], 1e-6 * m[1]);
        assertEquals("the clamp: the low-gain offset above the knee", ZONES[2] * 3.0, WhyAe.dcgClampModel(ZONES, 6e-4f, 1.5e-5f, 3e-4, 5e-6)[1], 1e-6 * ZONES[2] * 3.0);
        m = WhyAe.dcgShadowModel(new float[]{2e-4f, 1e-6f, 4e-5f, 0.8f}, 6e-4f, 1.5e-5f, Double.NaN, Double.NaN);
        assertEquals(1e-6f, m[1], 0f);
        // one measured zone (knee 0, equal offsets): that offset
        m = WhyAe.dcgShadowModel(new float[]{2.13e-4f, 3.1e-6f, 3.1e-6f, 0f}, 6e-4f, 1.5e-5f, Double.NaN, Double.NaN);
        assertEquals(3.1e-6f, m[1], 0f);
    }

    /**
     * P391 (review): PostPipeline.computeNoise of a DCG shot with measured zones gives the clamp mid grey's (low-gain) pair and the
     * WHY fusion's caps the deep shadows' (high-gain) one, both carried by the averaged model's scale; a plain shot gives both the
     * averaged pair.
     */
    @Test public void thePipelineHandsTheFusionTheShadowsZone() {
        @SuppressWarnings("unchecked")
        final android.util.Pair<Double, Double>[] model = new android.util.Pair[]{new android.util.Pair<>(3e-4, 5e-6),
                new android.util.Pair<>(3e-4, 5e-6), new android.util.Pair<>(3e-4, 5e-6)};
        final Parameters p = new Parameters();
        p.noiseModeler = new NoiseModeler(model, 100, 100, 0);
        p.noiseModeler.computeModel = new android.util.Pair[]{new android.util.Pair<>(6e-4, 1.5e-5), new android.util.Pair<>(6e-4, 1.5e-5),
                new android.util.Pair<>(6e-4, 1.5e-5)};
        p.noiseModeler.baseModel[1] = new android.util.Pair<>(3e-4, 5e-6);
        final PostPipeline pipeline = new PostPipeline();
        pipeline.computeNoise(p);
        assertEquals("a plain shot: the averaged pair", 6e-4f, pipeline.fusionNoiseS, 1e-10f);
        assertEquals(1.5e-5f, pipeline.fusionNoiseO, 1e-12f);
        assertEquals(pipeline.noiseO, pipeline.fusionNoiseO, 0f);
        p.dcgShot = true;
        p.dcgNoise = ZONES.clone();
        pipeline.computeNoise(p);
        assertEquals("the clamp: the low-gain offset", ZONES[2] * 3.0, pipeline.noiseO, 1e-6 * ZONES[2] * 3.0);
        assertEquals("the fusion: the high-gain offset", ZONES[1] * 3.0, pipeline.fusionNoiseO, 1e-6 * ZONES[1] * 3.0);
        assertEquals(ZONES[0] * 2.0, pipeline.fusionNoiseS, 1e-6 * ZONES[0] * 2.0);
        p.dcgNoise = null;
        pipeline.computeNoise(p);
        assertEquals("no zones: the camera's curve for both", pipeline.noiseO, pipeline.fusionNoiseO, 0f);
    }

    /** A dark scene (the night factor decides the shadow strength, no exposure floor involved). */
    private static WhyAe.Stats dark() {
        return WhyAe.stats((y, row) -> {
            for (int x = 0; x < 200; x++) {
                float v = 0.001f + 0.08f * x / 200f;
                row[3 * x] = v; row[3 * x + 1] = v; row[3 * x + 2] = v;
            }
        }, 3, 200, 100, 0);
    }

    @Test public void nightFactorReadsTheIsoOverTheDcgScale() {
        final WhyAe.Settings s = new WhyAe.Settings();
        assertEquals("a plain shot: the ISO as reported", 1336.0, WhyAe.nightIso(1336, s), 0);
        s.dcgIsoScale = 8f;
        assertEquals("a scale without the DCG flag is not read", 1336.0, WhyAe.nightIso(1336, s), 0);
        s.dcg = true;
        assertEquals(167.0, WhyAe.nightIso(1336, s), 0);
        assertEquals("at least ISO 1", 1.0, WhyAe.nightIso(4, s), 0);
        s.dcgIsoScale = 1f;
        assertEquals("scale unknown: as reported", 1336.0, WhyAe.nightIso(1336, s), 0);
        s.dcgIsoScale = Float.NaN;
        assertEquals(1336.0, WhyAe.nightIso(1336, s), 0);
        assertEquals(1.0, WhyAe.nightIso(0, new WhyAe.Settings()), 0);
        // the whole AE (the photo's ISO threshold 1, WhyTone.settings): the X300 Ultra's DCG shot at ISO 1336 (x8) gets the
        // night factor and shadow strength of ISO 167, not of 1336
        final WhyAe.Stats st = dark();
        final WhyAe.Settings plain = new WhyAe.Settings(), dcg = new WhyAe.Settings();
        plain.nightThresh = dcg.nightThresh = 1f;
        dcg.dcg = true;
        dcg.dcgIsoScale = 8f;
        final WhyAe.Result at167 = WhyAe.finish(st, plain, 167, 6400), at1336 = WhyAe.finish(st, plain, 1336, 6400);
        final WhyAe.Result dcgShot = WhyAe.finish(st, dcg, 1336, 6400);
        assertEquals(at167.nf, dcgShot.nf, 0f);
        assertEquals(at167.effS, dcgShot.effS, 0f);
        assertEquals(at167.ae, dcgShot.ae, 0f);
        assertTrue("the reported ISO would cut the shadows: nf " + at1336.nf + " < " + dcgShot.nf, at1336.nf < dcgShot.nf - 0.1f);
        // DCG without a known scale: the night factor of the reported ISO
        dcg.dcgIsoScale = 1f;
        assertEquals(at1336.nf, WhyAe.finish(st, dcg, 1336, 6400).nf, 0f);
    }

    @Test public void dcgKeepsTheCapturedExposureOfABrightScene() {
        assertFalse(WhyAe.keepsCapturedExposure(0f, false));
        assertTrue(WhyAe.keepsCapturedExposure(8f, false));
        assertTrue(WhyAe.keepsCapturedExposure(0f, true));
        // a bright scene (geometric mean 0.5 against the target 0.15): the plain AE darkens it to its limit, a DCG frame (its
        // highlights its own data) keeps the captured exposure
        final WhyAe.Stats bright = WhyAe.stats((y, row) -> java.util.Arrays.fill(row, 0.5f), 3, 200, 100, 0);
        final WhyAe.Settings plain = new WhyAe.Settings(), dcg = new WhyAe.Settings();
        dcg.dcg = true;
        assertEquals(plain.minLimit, WhyAe.finish(bright, plain, 400, 6400).ae, 1e-6f);
        assertEquals(1f, WhyAe.finish(bright, dcg, 400, 6400).ae, 1e-6f);
        // a dark scene is brightened as before (the floor only acts below 1)
        final WhyAe.Stats st = dark();
        assertEquals(WhyAe.finish(st, plain, 400, 6400).describe(), WhyAe.finish(st, dcg, 400, 6400).describe());
    }

    @Test public void bracketDenoiseLeavesADcgFramesOwnHighlights() {
        assertEquals("a plain shot: the setting", 1f, WhyFusion.bracketStrength(1f, false, false, false), 0f);
        assertEquals(0.4f, WhyFusion.bracketStrength(0.4f, false, true, false), 0f);
        assertEquals("DCG without Bento: off", 0f, WhyFusion.bracketStrength(1f, true, false, false), 0f);
        assertEquals("DCG with Bento ultrashorts: the gained range is real", 1f, WhyFusion.bracketStrength(1f, true, true, false), 0f);
        assertEquals("the old run of post_ab: the setting", 1f, WhyFusion.bracketStrength(1f, true, false, true), 0f);
    }

    private static File src(String path) {
        File f = new File(path);
        return f.exists() ? f : new File("app/" + path);
    }

    /** The lines of {@code source} a GLSL preprocessor keeps for DCG_ZONES = {@code on} (only #if DCG_ZONES / #else / #endif). */
    private static String preprocess(String source, boolean on) {
        StringBuilder out = new StringBuilder();
        boolean inBlock = false, keep = true;
        for (String line : source.split("\n")) {
            final String t = line.trim();
            if (t.equals("#if DCG_ZONES")) { assertFalse("nested", inBlock); inBlock = true; keep = on; continue; }
            if (t.equals("#else") && inBlock) { keep = !on; continue; }
            if (t.equals("#endif") && inBlock) { inBlock = false; keep = true; continue; }
            if (t.startsWith("#define DCG_ZONES")) continue;
            if (!keep) continue;
            final int comment = line.indexOf("//");
            out.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        assertFalse("unterminated #if DCG_ZONES", inBlock);
        return out.toString();
    }

    @Test public void shadersReadTheZonesOnlyUnderTheDefine() throws IOException {
        final ArrayList<String[]> one = new ArrayList<>();
        one.add(new String[]{"DCG_ZONES", "1"});
        for (String name : new String[]{"lbf", "cbf", "stats"}) {
            final String src = new String(Files.readAllBytes(src("src/main/assets/shaders/scamdn/" + name + ".glsl").toPath()),
                    StandardCharsets.UTF_8).replace("\r\n", "\n");
            int defines = 0;
            for (String line : src.split("\n")) if (line.equals("#define DCG_ZONES 0")) defines++;
            assertEquals(name + ": one #define DCG_ZONES 0", 1, defines);
            // GLProg.setDefine("DCG_ZONES", 1) before useAssetProgram: GLInterface rewrites that line; no define keeps it
            final String dcg = GLInterface.loadShader(src, one), plain = GLInterface.loadShader(src, null);
            assertTrue(name, dcg.contains("\n#define DCG_ZONES 1\n"));
            assertFalse(name, dcg.contains("#define DCG_ZONES 0"));
            assertTrue(name, plain.contains("\n#define DCG_ZONES 0\n"));
            // the DCG uniforms are declared and read only inside #if DCG_ZONES: the plain variant is the single curve
            final String p0 = preprocess(src, false), p1 = preprocess(src, true);
            for (String u : new String[]{"noiseHiU", "modelHiU", "kneeU"}) assertFalse(name + " plain reads " + u, p0.contains(u));
            assertTrue(name, p1.contains("uniform float kneeU;"));
            assertTrue(name, p1.contains("smoothstep(0.9 * kneeU, 1.1 * kneeU, "));
            assertTrue(name, p1.contains(name.equals("stats") ? "uniform float modelHiU;" : "uniform float noiseHiU;"));
        }
    }
}
