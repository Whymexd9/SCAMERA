package com.particlesdevs.photoncamera.processing;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** P386: the per-stream floor of a RAW stream proven without its black pedestal (the app's lifetime only). */
public class PedestalMemoryTest {
    private static final String A = "5|4096x3072|keys|5", B = "5|4096x3072|keys|7";

    @After public void cleanup() { PedestalMemory.reset(); }

    @Test public void aFloorNearZeroIsTheBlackAndReplacesTheRememberedOne() {
        PedestalMemory.remember(A, 1f, 1023f, "photo");
        assertEquals(1f, PedestalMemory.floor(A, 1023f), 0f);
        assertTrue("per stream", Float.isNaN(PedestalMemory.floor(B, 1023f)));
        PedestalMemory.remember(A, 2.5f, 1023f, "viewfinder");
        assertEquals("the latest near-zero floor", 2.5f, PedestalMemory.floor(A, 1023f), 0f);
        assertEquals("viewfinder", PedestalMemory.source(A));
    }

    @Test public void aHigherFloorIsOnlyAnUpperBound() {
        PedestalMemory.remember(A, 10f, 1023f, "photo");
        assertEquals("nothing better known: the bound", 10f, PedestalMemory.floor(A, 1023f), 0f);
        PedestalMemory.remember(A, 1f, 1023f, "photo");
        PedestalMemory.remember(A, 12f, 1023f, "photo");
        assertEquals("a bound never raises it", 1f, PedestalMemory.floor(A, 1023f), 0f);
        PedestalMemory.remember(A, 0.5f, 1023f, "photo");
        assertEquals(0.5f, PedestalMemory.floor(A, 1023f), 0f);
    }

    @Test public void theFloorFollowsTheWhiteLevel() {
        PedestalMemory.remember(A, 2f, 1023f, "viewfinder");
        assertEquals("a 12-bit photo of the 10-bit viewfinder's stream", 8f, PedestalMemory.floor(A, 4095f), 0.01f);
        assertEquals(12f, PedestalMemory.nearZero(4095f), 0.05f);
        assertEquals(3f, PedestalMemory.nearZero(1023f), 0f);
    }

    @Test public void clearedWhenThePedestalIsBackAndNeverForUnmeasuredStreams() {
        PedestalMemory.remember(A, 1f, 1023f, "photo");
        PedestalMemory.clear(A, "test");
        assertTrue(Float.isNaN(PedestalMemory.floor(A, 1023f)));
        for (String key : new String[]{null, "", "off"}) {
            PedestalMemory.remember(key, 1f, 1023f, "photo");
            assertTrue(Float.isNaN(PedestalMemory.floor(key, 1023f)));
        }
    }
}
