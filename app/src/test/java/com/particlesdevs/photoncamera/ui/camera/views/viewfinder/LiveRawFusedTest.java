package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import com.particlesdevs.photoncamera.processing.LiveRawFrame;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * P365 (owner, 2026-10-27: «В арк камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»): the RAW
 * viewfinder's fused pass - the two RAW textures taken in turn, the lens-shading map uploaded on change, when the tone goes straight
 * to the screen (one pass) and how long the last RAW frame is held while a series is taken.
 */
public class LiveRawFusedTest {
    /** Frames of these versions drawn in a row (a repeat: a redraw of the same frame) through slotFor, as upload() keeps them. */
    private static int[] slots(int[] frames, boolean twin) {
        int[] versions = {-1, -1};
        int cur = 0;
        int[] out = new int[frames.length];
        for (int i = 0; i < frames.length; i++) {
            int slot = LiveRawRenderer.slotFor(frames[i], cur, versions, twin);
            versions[slot] = frames[i];
            cur = slot;
            out[i] = slot;
        }
        return out;
    }

    @Test
    public void aNewFrameGoesIntoTheTextureNotDrawnLast() {
        int[] frames = {1, 1, 2, 3, 3, 3, 4, 5, 6, 6};
        int[] s = slots(frames, true);
        for (int i = 1; i < frames.length; i++) {
            if (frames[i] == frames[i - 1]) assertEquals("a redraw keeps its texture (no upload) at " + i, s[i - 1], s[i]);
            else assertNotEquals("a new frame never waits for the texture the GPU drew last, at " + i, s[i - 1], s[i]);
        }
        // vf_fused 0: one texture, as before
        for (int slot : slots(frames, false)) assertEquals(0, slot);
    }

    @Test
    public void switchingTheSecondTextureOffGoesBackToTheFirst() {
        int[] versions = {7, 8};
        assertEquals("the frame drawn last stays where it is", 1, LiveRawRenderer.slotFor(8, 1, versions, false));
        assertEquals("a new frame into texture 0 with one texture", 0, LiveRawRenderer.slotFor(9, 1, versions, false));
        assertEquals("a frame the other texture holds is drawn from it (no upload: it holds that version)", 0,
                LiveRawRenderer.slotFor(7, 1, versions, true));
    }

    @Test
    public void theShadingMapUploadsOnlyWhenItChanges() {
        float[] map = new float[17 * 13 * 3];
        for (int i = 0; i < map.length; i++) map[i] = 1f + i * 1e-3f;
        float[] copy = map.clone();   // LiveRawFrame hands every frame its own array
        assertTrue(LiveRawRenderer.sameShading(map, 17, 13, copy, 17, 13));
        copy[100] += 1e-4f;
        assertFalse("a changed gain", LiveRawRenderer.sameShading(map, 17, 13, copy, 17, 13));
        assertFalse("another grid", LiveRawRenderer.sameShading(map, 17, 13, map.clone(), 13, 17));
        assertFalse("nothing uploaded yet", LiveRawRenderer.sameShading(null, 0, 0, map, 17, 13));
    }

    private static LiveRawFrame.Frame frame(int w, int h, int block) {
        LiveRawFrame.Frame f = new LiveRawFrame.Frame();
        f.width = w;
        f.height = h;
        f.cfaBlock = block;
        f.crop = new float[]{0, 0, 1, 1};
        return f;
    }

    private static boolean direct(LiveRawFrame.Frame f, int vpW, int vpH, boolean filter, int peaking) {
        int[] crop = LiveRawRenderer.visibleCrop(f, vpW, vpH, false);
        int[] tone = LiveRawRenderer.toneSize(vpW, vpH, crop[2], crop[3], f.cfaBlock, false);
        return MainRenderer.directPass(tone[0], tone[1], vpW, vpH, filter, peaking);
    }

    @Test
    public void theToneGoesStraightToTheScreenOnlyAtTheViewportsSizeWithNothingAfterIt() {
        // the OPPO X7 Ultra 1x: 4096 x 3072 Bayer on 1440 x 1920 - one pass
        assertTrue(direct(frame(4096, 3072, 1), 1440, 1920, false, 0));
        assertFalse("focus peaking reads the tone target", direct(frame(4096, 3072, 1), 1440, 1920, false, 1));
        assertFalse("a lens filter reads the tone target", direct(frame(4096, 3072, 1), 1440, 1920, true, 0));
        // a 2x ISZ Quad's cell means 2048 x 1536: the tone at 810 x 1080, the filter pass scales it up - two passes
        assertFalse(direct(frame(2048, 1536, 1), 1440, 1920, false, 0));
        // a 1:1 crop of the 1x frame (the 3072 x 3072 square, 1536 colours a side) on 1440 x 1440 - still the viewport
        LiveRawFrame.Frame square = frame(4096, 3072, 1);
        square.crop = new float[]{0.125f, 0, 0.75f, 1};
        assertTrue(direct(square, 1440, 1440, false, 0));
    }

    @Test
    public void aSeriesHoldsTheLastRawFrameForAtMostTwoSeconds() {
        assertTrue(MainRenderer.holdsSeries(true, true, 0));
        assertTrue(MainRenderer.holdsSeries(true, true, 400_000_000L));   // the OPPO's series: 0.38 - 0.40 s
        assertTrue(MainRenderer.holdsSeries(true, true, MainRenderer.SERIES_HOLD_NS - 1));
        assertFalse("after 2 s the ISP picture comes back", MainRenderer.holdsSeries(true, true, MainRenderer.SERIES_HOLD_NS));
        assertFalse("vf_series_hold 0: as before", MainRenderer.holdsSeries(false, true, 0));
        assertFalse("no series", MainRenderer.holdsSeries(true, false, 0));
        assertFalse("no RAW frame drawn", MainRenderer.holdsSeries(true, true, -1));
        assertTrue("longer than the 300 ms the ISP frames wait otherwise", MainRenderer.SERIES_HOLD_NS > MainRenderer.RAW_SHOWN_NS);
    }
}
