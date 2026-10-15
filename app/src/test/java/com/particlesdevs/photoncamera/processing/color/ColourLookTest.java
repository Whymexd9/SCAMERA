package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P134 «Цвет»: the white-balance matrix and the uniforms of the colour look (ark/combine.glsl: tools/check_colour_look.py). */
public class ColourLookTest {
    private static float[] white(float[] m) {
        return new float[]{m[0] + m[1] + m[2], m[3] + m[4] + m[5], m[6] + m[7] + m[8]};
    }

    private static float luminance(float[] rgb) {
        return 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2];
    }

    @Test public void defaultsChangeNothing() {
        ColourLook c = new ColourLook();
        assertTrue(c.shaderNeutral());
        assertNull(c.whiteMatrix(6504f));
        assertNull("the scene light is off by default, whatever the light", c.whiteMatrix(2700f));
        assertArrayEquals(new float[]{0f, 0f, 0f}, c.monoUniform(), 0f);
        float[][] g = c.gradeUniforms();
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f}, g[0], 0f);
        assertEquals(0.5f, g[1][3], 0f);
        assertFalse(c.mixerOn());
    }

    @Test public void warmthAndTintMoveWhiteKeepingItsLuminance() {
        ColourLook c = new ColourLook();
        c.warmth = 50f;
        float[] w = white(c.whiteMatrix(6504f));
        assertEquals(1f, luminance(w), 1e-4f);
        assertTrue("warmer: red over blue " + java.util.Arrays.toString(w), w[0] > w[2] + 0.05f);
        c.warmth = -50f;
        w = white(c.whiteMatrix(6504f));
        assertTrue("cooler: blue over red", w[2] > w[0] + 0.05f);
        c.warmth = 0f;
        c.tint = 60f;
        w = white(c.whiteMatrix(6504f));
        assertEquals(1f, luminance(w), 1e-4f);
        assertTrue("magenta: green under red and blue " + java.util.Arrays.toString(w), w[1] < Math.min(w[0], w[2]));
        c.tint = -60f;
        w = white(c.whiteMatrix(6504f));
        assertTrue("green", w[1] > Math.max(w[0], w[2]));
        // a small step gives a small matrix, symmetric in sign
        c.tint = 0f;
        c.warmth = 1f;
        float[] small = c.whiteMatrix(6504f);
        assertEquals(1f, small[0] + small[1] + small[2], 0.02f);
    }

    @Test public void sceneLightIsKeptOnlyForWarmOrCoolLight() {
        ColourLook c = new ColourLook();
        c.keepWarm = 100f;
        assertNull("daylight: nothing to keep", c.whiteMatrix(5600f));
        float[] w = white(c.whiteMatrix(2700f));
        assertTrue("tungsten stays warm " + java.util.Arrays.toString(w), w[0] > w[2] * 1.3f);
        float[] half = white(c.whiteMatrix(3500f));
        assertTrue("less at 3500 K", half[0] / half[2] < w[0] / w[2]);
        c.keepWarm = 0f;
        c.keepCool = 100f;
        assertNull(c.whiteMatrix(2700f));
        w = white(c.whiteMatrix(12000f));
        assertTrue("shade stays blue " + java.util.Arrays.toString(w), w[2] > w[0] * 1.1f);
        assertNull("unknown light", c.whiteMatrix(Float.NaN));
    }

    @Test public void wheelHuesMapOntoOklab() {
        assertEquals(29.2, ColourLook.oklabHueOfWheel(0), 1.0);      // sRGB red
        assertEquals(110.0, ColourLook.oklabHueOfWheel(60), 2.0);    // yellow
        assertEquals(142.5, ColourLook.oklabHueOfWheel(120), 1.5);   // green
        assertEquals(264.1, ColourLook.oklabHueOfWheel(240), 1.0);   // blue
        assertEquals(ColourLook.oklabHueOfWheel(10), ColourLook.oklabHueOfWheel(370), 1e-9);
        ColourLook c = new ColourLook();
        c.gradeStr[2] = 100f;
        c.gradeHue[2] = 60f;
        float[][] g = c.gradeUniforms();
        assertEquals(0.06, Math.hypot(g[1][0], g[1][1]), 1e-6);
        assertTrue("yellow highlights: +b", g[1][1] > 0.05f);
        assertFalse(c.shaderNeutral());
    }

    @Test public void primariesCalibrationKeepsWhite() {
        ColourLook c = new ColourLook();
        assertNull(c.calibrationMatrix());
        c.calHue[0] = 100f;
        float[] m = c.calibrationMatrix();
        float[] w = white(m);
        assertArrayEquals("white stays white", new float[]{1f, 1f, 1f}, w, 1e-4f);
        assertTrue("red turned towards yellow: green in the red column " + java.util.Arrays.toString(m), m[3] > 0.02f);
        assertFalse(c.matrixNeutral(6504f));
        assertArrayEquals("the white matrix is the calibration alone", m, c.whiteMatrix(6504f), 1e-6f);
        c.calHue[0] = 0f;
        c.calSat[2] = 100f;
        m = c.calibrationMatrix();
        assertArrayEquals(new float[]{1f, 1f, 1f}, white(m), 1e-4f);
        // white stays white, so the richer blue shows in the column of blue: less green (and red) mixed into it
        assertTrue("richer blue: a blue input carries less green " + java.util.Arrays.toString(m), m[5] < -0.02f);
        c.warmth = 40f;
        float[] both = c.whiteMatrix(6504f);
        assertTrue("warmth still acts with the calibration", white(both)[0] > white(both)[2]);
    }

    @Test public void uniformsAreScaledAndClamped() {
        ColourLook c = new ColourLook();
        c.mix[5 * 3 + 1] = -100f;
        c.mix[0] = 250f;
        float[] u = c.mixUniform();
        assertEquals(-1f, u[16], 0f);
        assertEquals(1f, u[0], 0f);
        assertTrue(c.mixerOn());
        c.mono = 4;
        assertArrayEquals(new float[]{1f, 0.2f, 0.02f}, c.monoUniform(), 0f);
        c.skinProtect = 50f;
        c.skinHue = -100f;
        assertArrayEquals(new float[]{0.5f, -1f, 0f}, c.skinUniform(), 0f);
    }
}
