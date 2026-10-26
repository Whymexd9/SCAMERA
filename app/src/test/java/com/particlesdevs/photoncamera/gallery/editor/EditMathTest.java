package com.particlesdevs.photoncamera.gallery.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.particlesdevs.photoncamera.gallery.editor.EditState.Param;

import org.junit.Test;

/**
 * P351: the CPU reference of the editor's per-pixel math (develop.glsl / main.glsl mirror it): untouched sliders give the photo
 * back, each tool moves the way its name says, the tables stay monotone, the radii and the tile overlap agree.
 */
public class EditMathTest {
    static final EditMath.SourceColour SRGB = EditMath.display(false, false);

    /** develop + main with flat local bases (no neighbourhood: every base equals the pixel's own log2 Y). */
    static float[] run(float[] enc, EditState s, EditMath.SourceColour src) {
        EditMath.Uniforms u = EditMath.Uniforms.of(s, src);
        float[] d = EditMath.develop(enc, u);
        return EditMath.main(d, d[3], d[3], d[3], d[3], 0f, u);
    }

    @Test
    public void defaultsGiveThePhotoBack() {
        EditState s = new EditState();
        for (float r = 0; r <= 1.0001f; r += 0.125f)
            for (float g = 0; g <= 1.0001f; g += 0.25f)
                for (float b = 0; b <= 1.0001f; b += 0.25f) {
                    float[] e = {r, g, b};
                    float[] out = run(e, s, SRGB);
                    for (int i = 0; i < 3; i++) assertEquals(r + "," + g + "," + b, e[i], out[i], 1.5e-3f);
                }
    }

    @Test
    public void exposureIsLinearGain() {
        EditState s = new EditState();
        s.set(Param.EXPOSURE, 1f);
        EditMath.Uniforms u = EditMath.Uniforms.of(s, SRGB);
        float[] d = EditMath.develop(new float[]{0.5f, 0.25f, 0.1f}, u);
        assertEquals(2 * EditMath.srgbToLin(0.5f), d[0], 1e-5f);
        assertEquals(2 * EditMath.srgbToLin(0.1f), d[2], 1e-5f);
        // a mid grey one stop up comes out brighter but stays a grey
        float[] out = run(new float[]{0.4f, 0.4f, 0.4f}, s, SRGB);
        assertTrue(out[0] > 0.5f);
        assertEquals(out[0], out[2], 1e-4f);
    }

    @Test
    public void whiteBalanceKeepsLuminance() {
        float[] n = EditMath.wbGains(0, 0);
        assertEquals(1f, n[0], 1e-6f);
        assertEquals(1f, n[2], 1e-6f);
        for (float t = -1; t <= 1; t += 0.5f)
            for (float g = -1; g <= 1; g += 0.5f) {
                float[] w = EditMath.wbGains(t, g);
                assertEquals(1f, EditMath.LUMA[0] * w[0] + EditMath.LUMA[1] * w[1] + EditMath.LUMA[2] * w[2], 1e-5f);
            }
        float[] warm = EditMath.wbGains(1, 0);
        assertTrue(warm[0] > warm[2]);
        float[] magenta = EditMath.wbGains(0, 1);
        assertTrue(magenta[1] < magenta[0] && magenta[1] < magenta[2]);
    }

    @Test
    public void toneTableIsTheIdentityByDefaultAndMonotone() {
        float[] lut = EditMath.toneLut(new EditState(), false);
        for (int i = 0; i <= 511; i++) assertEquals(i / 511f, lut[i], 1e-6f);
        java.util.Random rnd = new java.util.Random(7);
        for (int k = 0; k < 40; k++) {
            EditState s = new EditState();
            for (Param p : new Param[]{Param.CONTRAST, Param.WHITES, Param.BLACKS, Param.CURVE_SHADOWS, Param.CURVE_DARKS,
                    Param.CURVE_LIGHTS, Param.CURVE_HIGHLIGHTS})
                s.set(p, rnd.nextInt(201) - 100);
            s.set(Param.EXPOSURE, rnd.nextFloat() * 4 - 1);
            for (boolean scene : new boolean[]{false, true}) {
                float[] t = EditMath.toneLut(s, scene);
                for (int i = 1; i < t.length; i++) assertTrue("table " + k + " at " + i, t[i] >= t[i - 1] - 1e-6f);
                for (float v : t) assertTrue(v >= 0f && v <= 2.01f);
            }
        }
    }

    @Test
    public void contrastAndCurveRegions() {
        EditState s = new EditState();
        s.set(Param.CONTRAST, 60);
        float[] lut = EditMath.toneLut(s, false);
        assertTrue("darks darker", lut[100] < 100 / 511f);
        assertTrue("lights lighter", lut[400] > 400 / 511f);
        assertNull(EditMath.curveRegions(new EditState()));
        EditState c = new EditState();
        c.set(Param.CURVE_LIGHTS, 80);
        float[] r = EditMath.curveRegions(c);
        assertEquals(0f, EditMath.parametric(0f, r), 0f);
        assertEquals(1f, EditMath.parametric(1f, r), 1e-6f);
        assertTrue(EditMath.parametric(0.62f, r) > 0.62f);
        assertEquals(0.1f, EditMath.parametric(0.1f, r), 0.02f);
    }

    @Test
    public void pointCurves() {
        assertEquals(0.3f, EditMath.curveAt(null, 0.3f), 0f);
        float[] pts = {0, 0, 0.25f, 0.4f, 0.75f, 0.8f, 1, 1};
        assertEquals(0.4f, EditMath.curveAt(pts, 0.25f), 1e-5f);
        assertEquals(0.8f, EditMath.curveAt(pts, 0.75f), 1e-5f);
        float prev = -1;
        for (int i = 0; i <= 100; i++) {
            float v = EditMath.curveAt(pts, i / 100f);
            assertTrue(v >= prev - 1e-6f);
            prev = v;
        }
        // a straight curve through the corners is the identity; the end points clamp
        assertEquals(0.37f, EditMath.curveAt(new float[]{0, 0, 1, 1}, 0.37f), 1e-5f);
        assertEquals(0.2f, EditMath.curveAt(new float[]{0.1f, 0.2f, 0.9f, 0.7f}, 0.05f), 0f);
        EditState s = new EditState();
        assertNull(EditMath.pointLut(s));
        s.curves[EditState.CURVE_RED] = new float[]{0, 0.2f, 1, 1};
        float[] t = EditMath.pointLut(s);
        assertEquals(4 * EditMath.LUT_SIZE, t.length);
        float[] out = run(new float[]{0f, 0f, 0f}, s, SRGB);
        assertEquals(0.2f, out[0], 1e-3f);
        assertEquals(0f, out[1], 1e-3f);
    }

    @Test
    public void bandWeightsSumToOne() {
        for (float h = -3.2f; h < 6.4f; h += 0.05f) {
            float[] w = EditMath.bandWeights(h);
            float sum = 0;
            for (float v : w) {
                assertTrue(v >= 0);
                sum += v;
            }
            assertEquals("hue " + h, 1f, sum, 1e-4f);
        }
        for (int i = 1; i < 8; i++) assertTrue(EditMath.BAND_HUES[i] > EditMath.BAND_HUES[i - 1]);
        float[] red = EditMath.bandWeights(EditMath.BAND_HUES[0]);
        assertEquals(1f, red[0], 1e-4f);
    }

    @Test
    public void colourTools() {
        float[] colour = {0.75f, 0.35f, 0.2f};
        EditState mono = new EditState();
        mono.set(Param.MONO, 1);
        float[] m = run(colour, mono, SRGB);
        assertEquals(m[0], m[1], 2e-3f);
        assertEquals(m[1], m[2], 2e-3f);
        EditState sat = new EditState();
        sat.set(Param.SATURATION, -100);
        float[] g = run(colour, sat, SRGB);
        assertEquals(g[0], g[2], 2e-3f);
        EditState more = new EditState();
        more.set(Param.SATURATION, 50);
        float[] s = run(colour, more, SRGB);
        assertTrue(s[0] - s[2] > colour[0] - colour[2]);
        EditState grade = new EditState();
        grade.set(Param.GRADE_ALL_LUM, 60);
        float[] b = run(new float[]{0.4f, 0.4f, 0.4f}, grade, SRGB);
        assertTrue(b[1] > 0.42f);
        EditState hue = new EditState();
        hue.set(Param.hue(1), 100); // orange toward yellow
        float[] h = run(colour, hue, SRGB);
        assertTrue(h[1] > colour[1]);
        EditState off = new EditState();
        assertFalse(EditMath.Uniforms.of(off, SRGB).colourOn);
    }

    @Test
    public void localToolsMoveTheRightWay() {
        EditState s = new EditState();
        s.set(Param.SHADOWS, 100);
        EditMath.Uniforms u = EditMath.Uniforms.of(s, SRGB);
        float[] dark = EditMath.develop(new float[]{0.08f, 0.08f, 0.08f}, u);
        float[] lifted = EditMath.main(dark, dark[3], dark[3], dark[3], dark[3], 0, u);
        assertTrue(lifted[0] > 0.12f);
        s = new EditState();
        s.set(Param.HIGHLIGHTS, -100);
        u = EditMath.Uniforms.of(s, SRGB);
        float[] bright = EditMath.develop(new float[]{0.95f, 0.95f, 0.95f}, u);
        assertTrue(EditMath.main(bright, bright[3], bright[3], bright[3], bright[3], 0, u)[0] < 0.9f);
        s = new EditState();
        s.set(Param.SHARPEN, 100);
        u = EditMath.Uniforms.of(s, SRGB);
        float[] d = EditMath.develop(new float[]{0.5f, 0.5f, 0.5f}, u);
        // brighter than its blur: sharpening makes it brighter still
        assertTrue(EditMath.main(d, d[3], d[3], d[3] - 0.3f, d[3], 0, u)[0] > 0.5f);
    }

    @Test
    public void radiiAndOverlap() {
        assertEquals(1f, EditMath.refScale(4000), 0f);
        float side = 8160;
        float ref = EditMath.refScale(side);
        int overlap = EditMath.overlapPx(side);
        float nrReach = 2 * EditMath.chromaStepRef(maxed()) * ref;
        float blurReach = 3 * Math.max(EditMath.TEXTURE_SIGMA_REF, 3f) * ref;
        assertTrue(overlap >= nrReach + blurReach);
        float[] t = EditMath.gaussTaps(2f);
        assertEquals(6f, t[0], 0f);
        assertEquals(1f, t[1], 0f);
        t = EditMath.gaussTaps(20f);
        assertEquals(24f, t[0], 0f);
        assertEquals(2.5f, t[1], 1e-6f);
    }

    static EditState maxed() {
        EditState s = new EditState();
        s.set(Param.COLOR_NR, 100);
        s.set(Param.LUMA_NR, 100);
        return s;
    }

    @Test
    public void gamutFitKeepsHueAndLuminance() {
        float[] c = {1.6f, 0.2f, -0.1f};
        float y = 0.6f;
        EditMath.fitGamut(c, y);
        for (float v : c) assertTrue(v >= -1e-6f && v <= 1 + 1e-6f);
        assertTrue(c[0] > c[1] && c[1] > c[2]);
    }

    @Test
    public void p3SourceGoesThroughItsMatrix() {
        EditMath.Uniforms u = EditMath.Uniforms.of(new EditState(), EditMath.display(true, false));
        float[] d = EditMath.develop(new float[]{1f, 0f, 0f}, u);
        assertTrue("P3 red is beyond sRGB red", d[0] > 1.2f && d[1] < 0f);
        float[] grey = EditMath.develop(new float[]{0.5f, 0.5f, 0.5f}, u);
        assertEquals(grey[0], grey[1], 1e-4f);
        assertEquals(grey[1], grey[2], 1e-4f);
    }
}
