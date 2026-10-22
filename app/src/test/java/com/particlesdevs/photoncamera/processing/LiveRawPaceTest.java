package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** P319: a Quad / Tetra viewfinder frame's cell means come every frame; only a slow bin and the 50 / 200 MP bins are paced. */
public class LiveRawPaceTest {
    @Test public void cheapCellMeansAreNotPaced() {
        final long isz2x = 4096L * 3072L, ms = 1_000_000L;
        assertEquals(0, LiveRawFrame.paceNs(true, isz2x, 5 * ms));
        assertEquals(0, LiveRawFrame.paceNs(true, isz2x, 29 * ms));
        // a bin as long as a frame keeps P188's pacing
        assertEquals(LiveRawFrame.binIntervalNs(isz2x, 40 * ms), LiveRawFrame.paceNs(true, isz2x, 40 * ms));
        // the large bins (not cell means) as before
        final long fullres = 8192L * 6144L;
        assertEquals(LiveRawFrame.binIntervalNs(fullres, 5 * ms), LiveRawFrame.paceNs(false, fullres, 5 * ms));
    }
}
