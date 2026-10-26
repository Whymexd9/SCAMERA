package com.particlesdevs.photoncamera.gallery.editor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P351: the editor's geometry - orientation, turns, flip, straighten inset, ratio presets, drag limits, export tiles. */
public class CropMathTest {
    static final double W = 4000, H = 3000;

    static void near(double[] a, double[] b) {
        assertEquals(a[0], b[0], 1e-6);
        assertEquals(a[1], b[1], 1e-6);
    }

    @Test
    public void exifOrientations() {
        near(new double[]{1, 0}, CropMath.orientationToDisplay(6).apply(0, 0)); // 90 cw: the top-left goes to the top-right
        near(new double[]{0, 1}, CropMath.orientationToDisplay(8).apply(0, 0));
        near(new double[]{1, 1}, CropMath.orientationToDisplay(3).apply(0, 0));
        near(new double[]{1, 0}, CropMath.orientationToDisplay(2).apply(0, 0));
        near(new double[]{0, 0}, CropMath.orientationToDisplay(5).apply(0, 0));
        near(new double[]{1, 1}, CropMath.orientationToDisplay(7).apply(0, 0));
        for (int o = 1; o <= 8; o++) {
            CropMath.Affine m = CropMath.orientationToDisplay(o).then(CropMath.orientationToDisplay(o).inverse());
            near(new double[]{0.3, 0.7}, m.apply(0.3, 0.7));
            assertEquals(o >= 5, CropMath.swapsSides(o));
        }
    }

    @Test
    public void identityGeometryMapsOutputToThePhoto() {
        EditState s = new EditState();
        CropMath.Affine m = CropMath.outputToOriented(s, W, H);
        near(new double[]{0, 0}, m.apply(0, 0));
        near(new double[]{1, 1}, m.apply(1, 1));
        near(new double[]{0.25, 0.75}, m.apply(0.25, 0.75));
    }

    @Test
    public void straightenInsetHasTheClosedForm() {
        double deg = 10, t = Math.toRadians(deg), c = Math.cos(t), si = Math.sin(t);
        double k = Math.min((W / 2) / (W / 2 * c + H / 2 * si), (H / 2) / (W / 2 * si + H / 2 * c));
        float[] r = CropMath.inset(0, 0, 1, 1, W, H, deg);
        assertEquals(k, r[2] - r[0], 1e-5);
        assertEquals(k, r[3] - r[1], 1e-5);
        assertEquals(0.5, (r[0] + r[2]) / 2, 1e-6);
        assertTrue(CropMath.fits(r[0], r[1], r[2], r[3], W, H, deg));
        // no straighten: the crop stays
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.7f, 0.9f}, CropMath.inset(0.1f, 0.2f, 0.7f, 0.9f, W, H, 0), 0f);
    }

    @Test
    public void insetCornersLieInsideThePhoto() {
        for (double deg : new double[]{-45, -20, -3, 3, 17, 45}) {
            EditState s = new EditState();
            s.straighten = (float) deg;
            s.cropL = 0.6f;
            s.cropT = 0.05f;
            s.cropR = 1f;
            s.cropB = 0.5f;
            CropMath.insetState(s, W, H);
            CropMath.Affine m = CropMath.outputToOriented(s, W, H);
            for (double[] p : new double[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}}) {
                double[] q = m.apply(p[0], p[1]);
                assertTrue(deg + ": " + q[0] + "," + q[1], q[0] > -1e-4 && q[0] < 1 + 1e-4 && q[1] > -1e-4 && q[1] < 1 + 1e-4);
            }
            assertTrue(s.cropR - s.cropL > 0.05f);
        }
    }

    @Test
    public void rotatingTurnsTheOutput() {
        for (boolean flip : new boolean[]{false, true}) {
            EditState s = new EditState();
            s.flipH = flip;
            s.straighten = 6;
            s.cropL = 0.2f;
            s.cropT = 0.15f;
            s.cropR = 0.8f;
            s.cropB = 0.7f;
            CropMath.Affine before = CropMath.outputToOriented(s, W, H);
            CropMath.rotateClockwise(s);
            CropMath.Affine after = CropMath.outputToOriented(s, W, H);
            // turned clockwise: the new top-left is the old bottom-left, the new top-right the old top-left
            near(before.apply(0, 1), after.apply(0, 0));
            near(before.apply(0, 0), after.apply(1, 0));
            near(before.apply(1, 1), after.apply(0, 1));
        }
    }

    @Test
    public void flippingMirrorsTheOutput() {
        EditState s = new EditState();
        s.quarterTurns = 1;
        s.straighten = -9;
        s.cropL = 0.1f;
        s.cropT = 0.2f;
        s.cropR = 0.6f;
        s.cropB = 0.9f;
        CropMath.Affine before = CropMath.outputToOriented(s, W, H);
        CropMath.flipHorizontal(s);
        CropMath.Affine after = CropMath.outputToOriented(s, W, H);
        near(before.apply(1, 0), after.apply(0, 0));
        near(before.apply(0, 1), after.apply(1, 1));
        assertEquals(9f, s.straighten, 0f);
    }

    @Test
    public void quarterTurnSwapsTheFrameAndARatio() {
        EditState s = new EditState();
        s.aspect = 16f / 9f;
        CropMath.rotateClockwise(s);
        assertEquals(1, s.quarterTurns);
        assertEquals(9f / 16f, s.aspect, 1e-6f);
        assertArrayEquals(new double[]{H, W}, CropMath.baseSize(W, H, s.quarterTurns), 0);
        double[] px = CropMath.cropPixels(s, W, H);
        assertEquals(H, px[0], 1e-6);
        assertEquals(W, px[1], 1e-6);
    }

    @Test
    public void ratioPresets() {
        for (int i = 2; i < CropMath.PRESETS.length; i++) {
            EditState s = new EditState();
            s.aspect = CropMath.PRESETS[i];
            CropMath.applyAspect(s, W, H);
            double[] px = CropMath.cropPixels(s, W, H);
            assertEquals(CropMath.PRESET_RATIOS[i], CropMath.PRESETS[i], px[0] / px[1], 1e-3);
            assertTrue(CropMath.fits(s.cropL, s.cropT, s.cropR, s.cropB, W, H, 0));
            // the largest of that ratio: it touches two opposite sides of the photo
            assertTrue(s.cropR - s.cropL > 0.999f || s.cropB - s.cropT > 0.999f);
            assertEquals(i, CropMath.presetIndex(CropMath.PRESETS[i]));
        }
        EditState o = new EditState();
        o.aspect = EditState.ASPECT_ORIGINAL;
        o.straighten = 12;
        CropMath.applyAspect(o, W, H);
        double[] px = CropMath.cropPixels(o, W, H);
        assertEquals(W / H, px[0] / px[1], 1e-3);
        assertTrue(CropMath.fits(o.cropL, o.cropT, o.cropR, o.cropB, W, H, 12));
        assertEquals(-1, CropMath.presetIndex(5f / 4f));
    }

    @Test
    public void dragsStopAtTheEdge() {
        float[] from = {0.2f, 0.2f, 0.6f, 0.6f};
        float[] to = {0.7f, 0.2f, 1.1f, 0.6f};
        float[] r = CropMath.constrain(from, to, W, H, 0);
        assertEquals(1f, r[2], 1e-4f);
        assertEquals(0.4f, r[2] - r[0], 1e-4f);
        assertFalse(CropMath.fits(to[0], to[1], to[2], to[3], W, H, 0));
        float[] tilted = CropMath.constrain(new float[]{0.4f, 0.4f, 0.6f, 0.6f}, new float[]{0.4f, 0.4f, 1f, 1f}, W, H, 20);
        assertTrue(CropMath.fits(tilted[0], tilted[1], tilted[2], tilted[3], W, H, 20));
        assertTrue(tilted[2] > 0.6f);
    }

    @Test
    public void exportTileRegionCoversItsFootprint() {
        EditState s = new EditState();
        s.straighten = 15;
        CropMath.insetState(s, W, H);
        int outW = 2000, outH = 1500;
        CropMath.Affine outToSrc = CropMath.Affine.scale(1.0 / outW, 1.0 / outH).then(CropMath.outputToOriented(s, W, H))
                .then(CropMath.Affine.scale(W, H));
        int overlap = EditMath.overlapPx((float) W);
        int[] reg = EditExporter.region(outToSrc, 1024, 1024, 976, 476, overlap, (int) W, (int) H, true);
        assertEquals(0, reg[0] & 1);
        assertEquals(0, reg[1] & 1);
        for (int[] p : new int[][]{{1024, 1024}, {2000, 1024}, {1024, 1500}, {2000, 1500}, {1500, 1300}}) {
            double[] q = outToSrc.apply(p[0], p[1]);
            assertTrue(q[0] - overlap >= reg[0] - 1e-6 || reg[0] == 0);
            assertTrue(q[0] + overlap <= reg[0] + reg[2] + 1e-6 || reg[0] + reg[2] == (int) W);
            assertTrue(q[1] - overlap >= reg[1] - 1e-6 || reg[1] == 0);
            assertTrue(q[1] + overlap <= reg[1] + reg[3] + 1e-6 || reg[1] + reg[3] == (int) H);
        }
        assertTrue(reg[0] + reg[2] <= W && reg[1] + reg[3] <= H);
    }

    @Test
    public void affineAlgebra() {
        CropMath.Affine a = new CropMath.Affine(2, 1, 3, -1, 4, 5);
        CropMath.Affine id = a.then(a.inverse());
        near(new double[]{7, -2}, id.apply(7, -2));
        float[] m = a.mat3();
        assertEquals(2f, m[0], 0f);
        assertEquals(-1f, m[1], 0f);
        assertEquals(3f, m[6], 0f);
        assertEquals(5f, m[7], 0f);
    }
}
