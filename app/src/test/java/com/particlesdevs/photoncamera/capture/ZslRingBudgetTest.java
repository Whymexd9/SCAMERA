package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** P91: the ring in the app's memfd is bounded by the memory available (MotionCam-style budget). */
public class ZslRingBudgetTest {
    private static final long SLOT_12MP = 25L << 20; // a 4080 x 3072 frame unpacked to 16 bit

    @Test
    public void budgetIsFortyPercentOfTheAvailableMemory() {
        // 8 GB available (X200 Ultra after start): 3.2 GB / 25 MB -> 128 slots at most
        assertEquals(128, ZslRing.budgetSlots(8L << 30, SLOT_12MP));
        // 2.5 GB available (Redmi Note 11 Pro): 1 GB / 25 MB = 40
        assertEquals(40, ZslRing.budgetSlots(2560L << 20, SLOT_12MP));
        // nearly nothing: still 12 (the ring falls back to fewer frames, never to none)
        assertEquals(12, ZslRing.budgetSlots(100L << 20, SLOT_12MP));
        // unknown memory: 64
        assertEquals(64, ZslRing.budgetSlots(0, SLOT_12MP));
    }
}
