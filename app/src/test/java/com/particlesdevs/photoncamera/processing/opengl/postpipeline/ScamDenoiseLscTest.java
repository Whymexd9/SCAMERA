package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * P214d: the lens-shading term of the scamdn strength map (ScamDenoise.lscTerm / lscFactor, scamdn/strmap.glsl): f times
 * min(g^2, cap) of the luminance gain the WHY tone applies after the denoise, g over the map's smallest gain; no term
 * without a real map; one-sided (the optical centre keeps f = 1 exactly, also through the fp16 texture); the uniforms the
 * host sets are the ones the shader declares.
 */
public class ScamDenoiseLscTest {
    private static final int MW = 17, MH = 13;

    /** A radial shading map like the phones': gain 1 + (corner - 1) r^2 per site, r = 1 at the corners, all four sites alike. */
    private static float[] radial(float centre, float corner) {
        float[] m = new float[4 * MW * MH];
        for (int y = 0; y < MH; y++) {
            for (int x = 0; x < MW; x++) {
                double dx = (x - (MW - 1) / 2.0) / ((MW - 1) / 2.0), dy = (y - (MH - 1) / 2.0) / ((MH - 1) / 2.0);
                float g = (float) (centre + (corner - centre) * (dx * dx + dy * dy) / 2.0);
                for (int c = 0; c < 4; c++) m[4 * (y * MW + x) + c] = g;
            }
        }
        return m;
    }

    private static float[] flat(int w, int h, float v) {
        float[] m = new float[4 * w * h];
        java.util.Arrays.fill(m, v);
        return m;
    }

    /** The value an RGBA16F texel holds for v (round to nearest of 11 significant bits; normal range). */
    private static float half(float v) {
        float u = Math.ulp(v) * 8192f;
        return Math.round(v / u) * u;
    }

    @Test public void noRealMapNoTerm() {
        assertNull(ScamDenoise.lscTerm(null, MW, MH, 4f));
        assertNull(ScamDenoise.lscTerm(new float[4 * MW * MH - 1], MW, MH, 4f));      // short
        assertNull(ScamDenoise.lscTerm(flat(1, 1, 1f), 1, 1, 4f));                     // the identity of no HAL map
        assertNull(ScamDenoise.lscTerm(flat(1, 1, 3f), 1, 1, 4f));                     // 1 x 1, whatever its value
        assertNull(ScamDenoise.lscTerm(flat(MW, MH, 1f), MW, MH, 4f));                 // flat
        assertNull(ScamDenoise.lscTerm(flat(MW, MH, 1.6f), MW, MH, 4f));               // flat with a global gain
        assertNull(ScamDenoise.lscTerm(flat(MW, MH, Float.NaN), MW, MH, 4f));          // no valid site
        assertNull(ScamDenoise.lscTerm(radial(1f, 3.8f), 0, MH, 4f));
        // cap 1 (or below / not a number): the factor could never leave 1
        assertNull(ScamDenoise.lscTerm(radial(1f, 3.8f), MW, MH, 1f));
        assertNull(ScamDenoise.lscTerm(radial(1f, 3.8f), MW, MH, 0.5f));
        assertNull(ScamDenoise.lscTerm(radial(1f, 3.8f), MW, MH, Float.NaN));
        assertNull(ScamDenoise.lscTerm(radial(1f, 3.8f), MW, MH, Float.POSITIVE_INFINITY));
        // a shading within the fp16 margin is flat
        assertNull(ScamDenoise.lscTerm(radial(1f, 1f + 1f / 1024f), MW, MH, 4f));
    }

    @Test public void uniformIsTheReferenceAndTheCap() {
        // the log line of the survey: centre 1.0005, corner 3.8325
        float[] term = ScamDenoise.lscTerm(radial(1.0005f, 3.8325f), MW, MH, ScamDenoise.LSC_CAP);
        assertNotNull(term);
        assertEquals(3, term.length);
        assertEquals(1f / (1.0005f * ScamDenoise.LSC_MARGIN), term[0], 1e-6f);        // lscU.x
        assertEquals(4f, term[1], 0f);                                                // lscU.y
        assertEquals(4f, term[2], 0f);                                                // the corner hits the cap
        // a cap above the corner's g^2: the peak is the corner's own (3.8325 / 1.0005 / margin)^2
        term = ScamDenoise.lscTerm(radial(1.0005f, 3.8325f), MW, MH, 16f);
        float g = 3.8325f / (1.0005f * ScamDenoise.LSC_MARGIN);
        assertEquals(g * g, term[2], 1e-4f);
        assertTrue(term[2] < 16f);
    }

    @Test public void factorIsOneSidedAndCapped() {
        float[] term = ScamDenoise.lscTerm(radial(1f, 3.8f), MW, MH, 4f);
        float inv = term[0], cap = term[1];
        // the optical centre and anything below the reference: exactly 1
        assertEquals(1f, ScamDenoise.lscFactor(1f, inv, cap), 0f);
        assertEquals(1f, ScamDenoise.lscFactor(ScamDenoise.LSC_MARGIN * 0.9999f, inv, cap), 0f);
        assertEquals(1f, ScamDenoise.lscFactor(0.5f, inv, cap), 0f);
        // a site at 1.5 x the reference: 2.25; the corner: the cap
        assertEquals(2.25f, ScamDenoise.lscFactor(1.5f * ScamDenoise.LSC_MARGIN, inv, cap), 1e-5f);
        assertEquals(4f, ScamDenoise.lscFactor(3.8f, inv, cap), 0f);
        // monotonic in the gain, never below 1, never above the cap
        float last = 1f;
        for (float gain = 0.9f; gain < 5f; gain += 0.01f) {
            float f = ScamDenoise.lscFactor(gain, inv, cap);
            assertTrue(f >= last && f >= 1f && f <= cap);
            last = f;
        }
        // a broken (NaN) interpolated site: 1, the filter neither weakened nor blown up
        assertEquals(1f, ScamDenoise.lscFactor(Float.NaN, inv, cap), 0f);
        assertEquals(1f, ScamDenoise.lscFactor(-2f, inv, cap), 0f);
    }

    @Test public void centreKeepsOneThroughTheHalfTexture() {
        // every centre gain of the phones' logs: the RGBA16F value of the smallest site, and a bilinear mix of it with its
        // neighbours (at most one fp16 step above), still reads factor 1 exactly
        for (float centre : new float[]{1f, 1.0005f, 1.0137f, 1.25f, 1.6f, 2.03f}) {
            float[] map = radial(centre, centre * 3.3f);
            float[] term = ScamDenoise.lscTerm(map, MW, MH, 4f);
            assertNotNull(term);
            float stored = half(centre);
            assertEquals("centre " + centre, 1f, ScamDenoise.lscFactor(stored, term[0], term[1]), 0f);
            float up = stored + Math.ulp(stored) * 8192f;
            assertEquals("centre " + centre + " + 1 fp16 step", 1f, ScamDenoise.lscFactor(up, term[0], term[1]), 0f);
        }
    }

    @Test public void normalisedToTheSmallestGain() {
        // a HAL map with a global gain baked in (smallest 1.6): the shading over it counts, not the 1.6
        float[] term = ScamDenoise.lscTerm(radial(1.6f, 1.6f * 1.5f), MW, MH, 4f);
        assertEquals(1f, ScamDenoise.lscFactor(1.6f, term[0], term[1]), 0f);
        float g = 1.5f / ScamDenoise.LSC_MARGIN;
        assertEquals(g * g, ScamDenoise.lscFactor(1.6f * 1.5f, term[0], term[1]), 1e-5f);
        assertEquals(g * g, term[2], 1e-5f);
    }

    @Test public void luminanceGainIsTheWhyToneFactor() {
        // why/low: lsc = dot((r, (g_even + g_odd) / 2, b), 1/3)
        assertEquals((2f + 2f + 4f) / 3f, ScamDenoise.lscGain(2f, 1f, 3f, 4f), 1e-6f);
        // colour shading alone (scamrgb's part, normalised to mean 1): the luminance gain is flat - no term
        float[] m = new float[4 * MW * MH];
        for (int i = 0; i < MW * MH; i++) {
            float t = 0.3f * (i % MW) / (MW - 1);
            m[4 * i] = 1f + t; m[4 * i + 1] = 1f; m[4 * i + 2] = 1f; m[4 * i + 3] = 1f - t;
        }
        assertNull(ScamDenoise.lscTerm(m, MW, MH, 4f));
        // the reference comes from the luminance gain of the sites: one site's red alone at 3 does not set it
        m = flat(MW, MH, 1f);
        m[4 * 7] = 3f;                                         // site 7: (3, 1, 1, 1) -> luminance gain 5/3
        float[] term = ScamDenoise.lscTerm(m, MW, MH, 16f);
        float g = (5f / 3f) / ScamDenoise.LSC_MARGIN;
        assertEquals(g * g, term[2], 1e-5f);
    }

    @Test public void brokenSitesAreSkipped() {
        float[] m = radial(1f, 3f);
        m[0] = Float.NaN;                                      // a corner site
        m[4 * 5 + 1] = Float.POSITIVE_INFINITY;
        m[4 * (MW * MH - 1)] = -8f;                            // the other corner: luminance gain (-8 + 3 + 3) / 3 < 0
        float[] term = ScamDenoise.lscTerm(m, MW, MH, 16f);
        assertNotNull(term);
        assertEquals(1f / ScamDenoise.LSC_MARGIN, term[0], 1e-6f);   // smallest valid site: the centre, 1
        assertTrue(Float.isFinite(term[2]) && term[2] > 1f && term[2] <= 9f);
    }

    private static File src(String path) {
        File f = new File(path);
        return f.exists() ? f : new File("app/" + path);
    }

    /**
     * A uniform name the host sets that the shader does not declare is silently ignored by GL (location -1): the term
     * would be off without a trace. Every name of the strmap block of ScamDenoise.process is a uniform of strmap.glsl.
     */
    @Test public void hostSetsTheShadersUniforms() throws IOException {
        String glsl = new String(Files.readAllBytes(src("src/main/assets/shaders/scamdn/strmap.glsl").toPath()), StandardCharsets.UTF_8);
        String java = new String(Files.readAllBytes(src("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/ScamDenoise.java").toPath()), StandardCharsets.UTF_8);
        int start = java.indexOf("useAssetProgram(\"scamdn/strmap\"");
        int end = java.indexOf("drawBlocks(strMap)", start);
        assertTrue(start > 0 && end > start);
        Set<String> set = new TreeSet<>();
        Matcher m = Pattern.compile("set(?:Var|Texture)\\(\"([A-Za-z0-9_]+)\"").matcher(java.substring(start, end));
        while (m.find()) set.add(m.group(1));
        Set<String> declared = new TreeSet<>();
        m = Pattern.compile("(?m)^uniform\\s+\\w+\\s+([A-Za-z0-9_]+)\\s*;").matcher(glsl);
        while (m.find()) declared.add(m.group(1));
        assertEquals(declared, set);
        for (String u : new String[]{"GainMap", "lscU", "noEffU", "fullInvU", "EffMap"}) assertTrue(u, set.contains(u));
        // the lens-shading term multiplies the frame factor; with lscU.x = 0 nothing touches it (the map of before)
        assertTrue(glsl.contains("if (lscU.x > 0.0)"));
        assertTrue(glsl.contains("f = s / float(F * F);"));
    }
}
