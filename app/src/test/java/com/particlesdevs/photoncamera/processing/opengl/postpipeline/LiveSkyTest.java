package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P378 review (P301: the viewfinder matches the photo; P383: the shown tone eases per frame in the scene's terms, no steps): the
 * viewfinder's sky term between its meter runs - LiveSky eases the decision's cells, levels and backlit share per drawn frame, and
 * puts the cells into the tone's orientation as rawdevelop_vs draws the frame there.
 */
public class LiveSkyTest {
    static final long FRAME = 33_333_333L;
    static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    /** A mask of nx x ny cells: the top {@code rows} sky (weight 1, clear 1, level {@code level}), the rest ground. */
    static WhySkyMask.Mask mask(int nx, int ny, int rows, float level, boolean backlit) {
        final float[] c = new float[4 * nx * ny];
        for (int y = 0; y < ny; y++) {
            for (int x = 0; x < nx; x++) {
                final int i = 4 * (y * nx + x);
                c[i] = y < rows ? 1f : 0f;
                c[i + 1] = y < rows ? 1f : 0f;
                c[i + 2] = level;
            }
        }
        return new WhySkyMask.Mask(nx, ny, c, backlit ? 0f : 1f, rows / (float) ny, level, 15f, 10f, backlit ? "test" : "");
    }

    /** The shown sky weight at the frame's cell (cx, cy) with the identity rotation (texture x = the frame's y). */
    static float weight(LiveSky s, int cx, int cy, double logP) {
        final int[] size = new int[2];
        final float[] t = s.texture(IDENTITY, false, null, logP, size);
        // identity: the target's x runs along the frame's y (tw = ny), its y along the frame's x
        return t[4 * (cx * size[0] + cy)];
    }

    @Test
    public void theSkyEasesInPerFrameWithoutASteps() {
        final LiveSky s = new LiveSky();
        assertFalse(s.shown());
        s.decided(mask(8, 6, 3, -1f, false), 1f, 0.0);
        assertTrue(s.shown());
        float last = weight(s, 4, 1, 0.0);
        assertEquals("a sky coming in starts at 0", 0f, last, 0f);
        float biggest = 0f;
        for (int k = 1; k <= 15; k++) {
            s.ease(FRAME);
            final float w = weight(s, 4, 1, 0.0);
            assertTrue("never back", w >= last - 1e-6f);
            biggest = Math.max(biggest, w - last);
            last = w;
        }
        assertTrue("about 0.9 there after 0.5 s: " + last, last > 0.85f && last < 1.0001f);
        assertTrue("no step: at most ~11 % a frame (" + biggest + ")", biggest < 0.12f);
        // the ground stays without
        assertEquals(0f, weight(s, 4, 5, 0.0), 1e-6f);
    }

    @Test
    public void aBacklitSceneEasesTheLevelShareOutAndANoneTheSky() {
        final LiveSky s = new LiveSky();
        s.decided(mask(8, 6, 3, -1f, false), 1f, 0.0);
        for (int k = 0; k < 30; k++) s.ease(FRAME);
        assertEquals(1f, s.level(), 1e-3f);
        s.decided(mask(8, 6, 3, -1f, true), 1f, 0.0);
        s.ease(FRAME);
        assertTrue("eases, no jump: " + s.level(), s.level() > 0.9f);
        for (int k = 0; k < 30; k++) s.ease(FRAME);
        assertEquals(0f, s.level(), 0.02f);
        // no sky any more: the weights ease out, then the define goes
        s.decided(null, 1f, 0.0);
        s.ease(FRAME);
        assertTrue(s.shown());
        assertTrue(weight(s, 4, 1, 0.0) > 0.9f);
        for (int k = 0; k < 90; k++) s.ease(FRAME);
        assertFalse("gone", s.shown());
    }

    @Test
    public void theLevelFollowsTheFramesOwnExposure() {
        // the scene's terms: the metered frame at 2^-5, the cells' level -1 there; a frame drawn at 2^-4 (one stop more) sees -0
        final LiveSky s = new LiveSky();
        s.decided(mask(8, 6, 3, -1f, false), 1f, -5.0);
        for (int k = 0; k < 30; k++) s.ease(FRAME);
        final int[] size = new int[2];
        final float[] at5 = s.texture(IDENTITY, false, null, -5.0, size), at4 = s.texture(IDENTITY, false, null, -4.0, size);
        assertEquals(-1f, at5[2], 1e-4f);
        assertEquals(0f, at4[2], 1e-4f);
        assertEquals(-1f + WhySkyMask.TOP_FROM + 1f, s.tops(-4.0)[0], 1e-4f);
        // a new decision at the same scene from a frame one stop brighter changes nothing shown
        s.decided(mask(8, 6, 3, 0f, false), 1f, -4.0);
        s.ease(FRAME);
        assertEquals(-1f, s.texture(IDENTITY, false, null, -5.0, size)[2], 1e-4f);
    }

    @Test
    public void theCellsTakeTheTonesOrientation() {
        // rawdevelop_vs: texCoord = ((1 + vertex.y) / 2, (1 + vertex.x) / 2) - the frame's crop position (u, v); NDC = rotation x
        // vertex. A sky along the frame's first rows (small v) ...
        final LiveSky s = new LiveSky();
        final int nx = 8, ny = 6;
        s.decided(mask(nx, ny, 2, -1f, false), 1f, 0.0);
        for (int k = 0; k < 60; k++) s.ease(FRAME);
        final int[] size = new int[2];
        // identity: v = (1 + NDC x) / 2 - the sky (small v) on the target's left columns; the target is ny x nx
        float[] t = s.texture(IDENTITY, false, null, 0.0, size);
        assertEquals(ny, size[0]);
        assertEquals(nx, size[1]);
        assertTrue(t[4 * (3 * size[0])] > 0.99f);
        assertEquals(0f, t[4 * (3 * size[0] + size[0] - 1)], 1e-6f);
        // the front mirror flips v: the sky on the right
        t = s.texture(IDENTITY, true, null, 0.0, size);
        assertEquals(0f, t[4 * (3 * size[0])], 1e-6f);
        assertTrue(t[4 * (3 * size[0] + size[0] - 1)] > 0.99f);
        // a quarter turn (setRotateM 90: NDC = (-vertex.y, vertex.x)): v = (1 + NDC y) / 2 - the sky along the target's bottom
        // rows, the target nx x ny
        final float[] r90 = {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        t = s.texture(r90, false, null, 0.0, size);
        assertEquals(nx, size[0]);
        assertEquals(ny, size[1]);
        assertTrue(t[4 * 3] > 0.99f);
        assertEquals(0f, t[4 * ((ny - 1) * nx + 3)], 1e-6f);
        // a degenerate rotation draws nothing
        assertNull(s.texture(new float[16], false, null, 0.0, size));
    }

    @Test
    public void aNewSessionStartsOver() {
        final LiveSky s = new LiveSky();
        s.decided(mask(8, 6, 3, -1f, false), 1f, 0.0);
        s.reset();
        assertFalse(s.shown());
        assertNull(s.texture(IDENTITY, false, null, 0.0, new int[2]));
        // from a reset: from 0
        s.decided(mask(8, 6, 3, -1f, false), 1f, 0.0);
        assertEquals(0f, weight(s, 4, 1, 0.0), 1e-6f);
    }

    @Test
    public void anotherGridTakesWhatIsShown() {
        // P378 review: the live crop follows a pinch zoom, the meter's grid changes nx or ny with nearly every step - the shown
        // sky resampled onto the new grid (it was set back to none: a +15 % step of the sky in one frame)
        final LiveSky s = new LiveSky();
        s.decided(mask(8, 6, 3, -1f, false), 1f, 0.0);
        for (int k = 0; k < 30; k++) s.ease(FRAME);
        final float before = weight(s, 4, 1, 0.0);
        assertTrue(before > 0.95f);
        float last = before, biggest = 0f;
        for (int step = 0; step < 12; step++) {
            final int nx = 8 + step % 3, ny = 6 + step % 2;
            s.decided(mask(nx, ny, ny / 2, -1f, false), 1f, 0.0);
            s.ease(FRAME);
            final int[] size = new int[2];
            assertNotNull(s.texture(IDENTITY, false, null, 0.0, size));
            assertEquals(nx, size[1]);
            final float w = weight(s, nx / 2, 1, 0.0);
            biggest = Math.max(biggest, Math.abs(w - last));
            last = w;
        }
        assertTrue("no frame changes the sky by more than ~0.12: " + biggest, biggest < 0.12f);
        assertTrue("still shown: " + last, last > 0.9f);
        // the ground stays ground on the new grids
        assertEquals(0f, weight(s, 4, 5, 0.0), 0.06f);
    }
}
