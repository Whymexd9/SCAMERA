package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P228: the lens filters' CPU model (the settings' preview; the GPU node follows the same formulas). */
public class LensFiltersTest {
    static final int W = 96, H = 64;

    /** The scene 4 x as large (each pixel a 4 x 4 block): the diffusion's sizes are shares of the frame, sub-pixel at 96 x 64. */
    static final int BW = W * 4, BH = H * 4;

    static float[] big() {
        float[] small = scene(), out = new float[BW * BH * 3];
        for (int y = 0; y < BH; y++)
            for (int x = 0; x < BW; x++)
                System.arraycopy(small, ((y / 4) * W + x / 4) * 3, out, (y * BW + x) * 3, 3);
        return out;
    }

    /** A dark street with a few point lights (4 x white) and a sky gradient on top. */
    static float[] scene() {
        float[] rgb = new float[W * H * 3];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 3;
                float sky = y < H / 3 ? 0.25f * (1f - y / (H / 3f)) : 0.02f;
                rgb[i] = sky * 0.6f + 0.01f * (x % 7);
                rgb[i + 1] = sky * 0.8f + 0.02f * ((x + y) % 5 == 0 ? 1 : 0);
                rgb[i + 2] = sky;
            }
        }
        int[][] lights = {{20, 40}, {48, 36}, {75, 44}};
        for (int[] l : lights) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int i = ((l[1] + dy) * W + l[0] + dx) * 3;
                    rgb[i] = 4f;
                    rgb[i + 1] = 3.2f;
                    rgb[i + 2] = 1.6f;
                }
            }
        }
        return rgb;
    }

    static double sum(float[] rgb, int ch) {
        double s = 0;
        for (int i = ch; i < rgb.length; i += 3) s += rgb[i];
        return s;
    }

    static double meanLevel(float[] psf) {
        double m = 0;
        for (int k = 0; k < psf.length; k++) m += k * psf[k];
        return m;
    }

    static double diff(float[] a, float[] b) {
        double d = 0;
        for (int i = 0; i < a.length; i++) d += Math.abs(a[i] - b[i]);
        return d / a.length;
    }

    @Test public void noFilterOrZeroStrengthLeavesTheImage() {
        float[] base = scene();
        float[] a = base.clone();
        LensFilters.apply(a, W, H, "", 1f, 0.5f);
        assertArrayEquals(base, a, 0f);
        for (String id : LensFilters.IDS) {
            float[] b = base.clone();
            LensFilters.apply(b, W, H, id, 0f, 0.5f);
            assertArrayEquals(id, base, b, 0f);
        }
        float[] c = base.clone();
        LensFilters.apply(c, W, H, "unknown", 1f, 0.5f);
        assertArrayEquals("an unknown id is no filter", base, c, 0f);
    }

    @Test public void everyFilterChangesTheImageAndStaysFinite() {
        float[] base = scene();
        for (String id : LensFilters.IDS) {
            float[] a = base.clone();
            LensFilters.apply(a, W, H, id, 1f, LensFilters.defaultParam(id));
            assertTrue(id + " changes the frame", diff(a, base) > 1e-4);
            for (float v : a) assertTrue(id + " finite", Float.isFinite(v));
        }
    }

    @Test public void strengthScalesTheEffect() {
        float[] base = scene();
        for (String id : LensFilters.IDS) {
            float[] half = base.clone(), full = base.clone();
            LensFilters.apply(half, W, H, id, 0.5f, LensFilters.defaultParam(id));
            LensFilters.apply(full, W, H, id, 1f, LensFilters.defaultParam(id));
            assertTrue(id + ": more strength, more change", diff(full, base) > diff(half, base));
        }
    }

    /** The diffusion glow moves light from the sources to their surroundings: the energy stays (bar the veil / lift). */
    @Test public void theGlowKeepsTheEnergyAndSpreadsTheLight() {
        // owner, 2026-10-18: DiffusionModel - a share of all the light spread by the filter's PSF, energy kept exactly
        float[] base = big();
        for (String id : new String[]{LensFilters.BLACK_PRO_MIST, LensFilters.CINEBLOOM, LensFilters.DOUBLE_FOG, LensFilters.FOG}) {
            float[] a = base.clone();
            LensFilters.apply(a, BW, BH, id, 1f, 0f);
            for (int ch = 0; ch < 3; ch++)
                assertEquals(id + " energy ch" + ch, 1.0, sum(a, ch) / sum(base, ch), 0.01);
            int core = (162 * BW + 82) * 3, near = (162 * BW + 92) * 3, far = (162 * BW + 136) * 3;
            assertTrue(id + ": the source dims", a[core] < base[core]);
            assertTrue(id + ": its surroundings glow more than the far street",
                    (a[near] - base[near]) > (a[far] - base[far]) + 1e-4f);
        }
        // a frame with no light above the knee gets no glow
        float[] dark = new float[W * H * 3];
        java.util.Arrays.fill(dark, 0.1f);
        float[] d = dark.clone();
        LensFilters.apply(d, W, H, LensFilters.BLACK_PRO_MIST, 1f, 0f);
        assertArrayEquals("Black Pro-Mist keeps the black (no lift)", dark, d, 1e-4f);
    }

    @Test public void theStreakIsHorizontal() {
        float[] a = scene();
        float[] base = a.clone();
        LensFilters.apply(a, W, H, LensFilters.STREAK, 1f, 0.5f);
        int side = (40 * W + 30) * 3, above = (30 * W + 20) * 3;
        assertTrue("light along the row", a[side + 2] - base[side + 2] > 0.01f);
        assertEquals("nothing above the light", base[above + 2], a[above + 2], 1e-3f);
    }

    @Test public void gndDarkensTheTopOnly() {
        float[] a = new float[W * H * 3];
        java.util.Arrays.fill(a, 0.5f);
        LensFilters.apply(a, W, H, LensFilters.GND, 1f, 0.45f);
        assertEquals("the top by 3 stops", 0.5f / 8f, a[0], 1e-3f);
        assertEquals("the bottom as it was", 0.5f, a[a.length - 1], 1e-4f);
    }

    @Test public void lightPollutionCutsSodiumNotBlue() {
        float[] c = {1f, 0.6f, 0.1f, 1f, 0.6f, 0.1f, 0.1f, 0.2f, 0.6f, 0.1f, 0.2f, 0.6f};
        LensFilters.apply(c, 2, 2, LensFilters.LIGHT_POLLUTION, 1f, 0f);
        float sodiumChroma = c[0] - c[2], blueChroma = c[8] - c[6];
        assertTrue("the orange loses most of its chroma", sodiumChroma < 0.3f * 0.9f);
        assertTrue("the blue keeps its chroma", blueChroma > 0.45f);
    }

    @Test public void kaleidoscopeAndInfraredVariants() {
        assertEquals(3, LensFilters.segments(0f));
        assertEquals(12, LensFilters.segments(1f));
        float[] base = scene();
        float[] mono = base.clone(), swap = base.clone();
        LensFilters.apply(mono, W, H, LensFilters.INFRARED, 1f, 0f);
        for (int i = 0; i < mono.length; i += 3) {
            assertEquals(mono[i], mono[i + 1], 1e-5f);
            assertEquals(mono[i], mono[i + 2], 1e-5f);
        }
        LensFilters.apply(swap, W, H, LensFilters.INFRARED, 1f, 1f);
        assertEquals("R / B swap", base[2], swap[0], 1e-6f);
        assertEquals(base[0], swap[2], 1e-6f);
    }

    /** The fine controls: stored per filter, 0.5 = the filter as designed, each one changes the picture its own way. */
    @Test public void fineControlsTuneTheFilter() {
        LensFilters.Tune d = LensFilters.Tune.defaults(LensFilters.STREAK);
        assertEquals(0.5f, d.param, 0f);
        assertTrue(d.fineDefault());
        assertEquals("50,50,50,50,50", d.serialize());
        LensFilters.Tune t = LensFilters.Tune.parse(LensFilters.BLACK_PRO_MIST, "30,10,90,x,200");
        assertEquals(0.3f, t.param, 1e-6f);
        assertEquals(0.1f, t.knee, 1e-6f);
        assertEquals(0.9f, t.radius, 1e-6f);
        assertEquals("unreadable: the default", 0.5f, t.haze, 0f);
        assertEquals("clamped", 1f, t.warmth, 0f);
        assertEquals(t.serialize(), LensFilters.Tune.parse(LensFilters.BLACK_PRO_MIST, t.serialize()).serialize());
        assertEquals("pref_lens_filter_tune_bpm", LensFilters.tuneKey(LensFilters.BLACK_PRO_MIST));

        float[] base = big();
        for (String id : LensFilters.IDS) {
            float[] a = base.clone(), b = base.clone();
            LensFilters.apply(a, BW, BH, id, 0.8f, LensFilters.defaultParam(id));
            LensFilters.apply(b, BW, BH, id, 0.8f, LensFilters.Tune.defaults(id));
            assertArrayEquals(id + ": the defaults are the filter as designed", a, b, 0f);
            int fine = LensFilters.tunable(id);
            for (int control : LensFilters.CONTROLS) {
                if ((fine & control) == 0) continue;
                LensFilters.Tune lo = LensFilters.Tune.defaults(id), hi = LensFilters.Tune.defaults(id);
                lo.set(control, 0f);
                hi.set(control, 1f);
                float[] x = base.clone(), y = base.clone();
                LensFilters.apply(x, BW, BH, id, 0.8f, lo);
                LensFilters.apply(y, BW, BH, id, 0.8f, hi);
                assertTrue(id + " control " + control + " changes the picture", diff(x, y) > 1e-4);
                assertNotNull(LensFilters.controlName(null, id, control));
                assertNotNull(LensFilters.controlHint(null, id, control));
            }
        }
        assertEquals("GND has no fine controls (its own parameter only)", 0, LensFilters.tunable(LensFilters.GND));
        for (String id : LensFilters.IDS) {
            float[] psf = LensFilters.psf(id, LensFilters.Tune.parse(id, "50,50,100,50,50"));
            if (!LensFilters.glows(id)) continue;
            float s = 0;
            for (float v : psf) s += v;
            assertEquals(id + ": a wider PSF still sums to 1", 1f, s, 1e-4f);
            assertTrue(id + ": wider", meanLevel(psf) > meanLevel(LensFilters.psf(id)));
        }
        // «Ореол» (the diffusion's halo share): right, a stronger halo round the lights; the star / streak's knee: lower, more glows
        float[] weakHalo = base.clone(), strongHalo = base.clone();
        LensFilters.apply(weakHalo, BW, BH, LensFilters.BLACK_PRO_MIST, 1f, LensFilters.Tune.parse(LensFilters.BLACK_PRO_MIST, "0,0,50,50,50"));
        LensFilters.apply(strongHalo, BW, BH, LensFilters.BLACK_PRO_MIST, 1f, LensFilters.Tune.parse(LensFilters.BLACK_PRO_MIST, "0,100,50,50,50"));
        int ring = (162 * BW + 90) * 3;
        assertTrue("a stronger halo next to the light", strongHalo[ring + 1] > weakHalo[ring + 1]);
        float[] lowKnee = scene(), highKnee = scene();
        LensFilters.apply(lowKnee, W, H, LensFilters.STREAK, 1f, LensFilters.Tune.parse(LensFilters.STREAK, "50,0,50,50,50"));
        LensFilters.apply(highKnee, W, H, LensFilters.STREAK, 1f, LensFilters.Tune.parse(LensFilters.STREAK, "50,100,50,50,50"));
        assertTrue(diff(lowKnee, scene()) > diff(highKnee, scene()));
        // the tone moves warm light to the halo's rim without adding any (energy kept per channel)
        float[] warm = base.clone(), cool = base.clone();
        LensFilters.apply(warm, BW, BH, LensFilters.CINEBLOOM, 1f, LensFilters.Tune.parse(LensFilters.CINEBLOOM, "0,50,50,50,100"));
        LensFilters.apply(cool, BW, BH, LensFilters.CINEBLOOM, 1f, LensFilters.Tune.parse(LensFilters.CINEBLOOM, "0,50,50,50,0"));
        int rim = (162 * BW + 96) * 3;
        assertTrue("warmer: a redder rim", warm[rim] / warm[rim + 2] > cool[rim] / cool[rim + 2]);
        assertEquals("the same red light in all", sum(cool, 0), sum(warm, 0), 0.002 * sum(cool, 0));
    }

    @Test public void idsNamesAndParameters() {
        assertEquals(12, LensFilters.IDS.length);
        assertEquals(0, LensFilters.index(""));
        assertEquals(-1, LensFilters.index("x"));
        for (int i = 0; i < LensFilters.IDS.length; i++) {
            String id = LensFilters.IDS[i];
            assertEquals(i + 1, LensFilters.index(id));
            assertNotNull(LensFilters.name(null, id));
            assertNotNull(LensFilters.hint(null, id));
            assertNotNull(LensFilters.description(null, id));
            float[] psf = LensFilters.psf(id);
            assertEquals(LensFilters.LEVELS, psf.length);
            if (LensFilters.glows(id)) {
                float s = 0;
                for (float v : psf) s += v;
                assertEquals(id + " PSF sums to 1", 1f, s, 1e-4f);
            }
            float p = LensFilters.defaultParam(id);
            assertTrue(p >= 0f && p <= 1f);
        }
        assertNull(LensFilters.paramName(null, LensFilters.BLACK_PRO_MIST));
        assertNotNull(LensFilters.paramName(null, LensFilters.STREAK));
    }

    /**
     * Owner, 2026-10-18 (Pro-Mist had square highlights): a point light's glow from the coarse levels is round - the same at a
     * radius along the axis and along the diagonal - and falls off smoothly (the bilinear stretch drew a diamond).
     */
    @Test public void aPointLightsGlowIsRound() {
        int w = 256, h = 256;
        float[] img = new float[w * h * 3];
        int c = (128 * w + 128) * 3;
        img[c] = img[c + 1] = img[c + 2] = 10000f;
        float[] g = LensFilters.pyramidSum(img, w, h, new float[]{0f, 0f, 0f, 0f, 0.5f, 0.5f});
        for (int r : new int[]{24, 40, 56}) {
            int d = Math.round(r / (float) Math.sqrt(2));
            float axis = 0.25f * (g[(128 * w + 128 + r) * 3] + g[(128 * w + 128 - r) * 3] + g[((128 + r) * w + 128) * 3] + g[((128 - r) * w + 128) * 3]);
            float diag = 0.25f * (g[((128 + d) * w + 128 + d) * 3] + g[((128 - d) * w + 128 - d) * 3] + g[((128 + d) * w + 128 - d) * 3] + g[((128 - d) * w + 128 + d) * 3]);
            assertEquals("radius " + r + ": axis " + axis + " diagonal " + diag, 1f, diag / axis, 0.12f);
        }
        // smooth: no crease along a row (the second difference never jumps)
        float maxJump = 0f;
        for (int x = 70; x < 186; x++) {
            float d2a = g[(128 * w + x - 1) * 3] - 2 * g[(128 * w + x) * 3] + g[(128 * w + x + 1) * 3];
            float d2b = g[(128 * w + x) * 3] - 2 * g[(128 * w + x + 1) * 3] + g[(128 * w + x + 2) * 3];
            maxJump = Math.max(maxJump, Math.abs(d2b - d2a));
        }
        assertTrue("no creases: " + maxJump + " of a peak " + g[c], maxJump < 1e-3f * g[c]);
    }
}
