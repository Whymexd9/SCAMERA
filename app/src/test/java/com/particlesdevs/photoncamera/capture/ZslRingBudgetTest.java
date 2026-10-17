package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P91: the ring in the app's memfd is bounded by the memory available (MotionCam-style budget). */
public class ZslRingBudgetTest {
    private static final long SLOT_12MP = 25L << 20; // a 4080 x 3072 frame unpacked to 16 bit
    private static final long SLOT_50MP = 8192L * 6144 * 2; // P198: 96 MB, a native 50 MP frame (already 4 KB aligned)

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

    /**
     * P198: the 50 MP ring {ring frames, shot slots}: a quarter of the memory available, at most 16 slots (the 12 MP ring's bytes),
     * 6 of them for the shot's Hybrid plan, the ring the shot's N + 2 within the ring setting (50).
     */
    @Test
    public void the50MpRingIsAQuarterOfTheMemoryAtMostSixteenSlots() {
        // 6 GB available: 1.5 GB / 96 MB = 16 slots: ring 10 (N 20 + 2 asked) + shot 6
        assertArrayEquals(new int[]{10, 6}, ZslRing.fullResSlots(6L << 30, SLOT_50MP, 50, 20));
        // 12 GB: still 16 (1.5 GB)
        assertArrayEquals(new int[]{10, 6}, ZslRing.fullResSlots(12L << 30, SLOT_50MP, 50, 20));
        // 4 GB: 10 slots: ring 5 + shot 5
        assertArrayEquals(new int[]{5, 5}, ZslRing.fullResSlots(4L << 30, SLOT_50MP, 50, 20));
        // 2 GB: 5 slots: ring 3 + shot 2
        assertArrayEquals(new int[]{3, 2}, ZslRing.fullResSlots(2L << 30, SLOT_50MP, 50, 20));
        // nearly nothing: 4 slots, ring 2 + shot 2
        assertArrayEquals(new int[]{2, 2}, ZslRing.fullResSlots(100L << 20, SLOT_50MP, 50, 20));
        // unknown memory: the maximum
        assertArrayEquals(new int[]{10, 6}, ZslRing.fullResSlots(0, SLOT_50MP, 50, 20));
        // «Кадров в снимке 50 Мп» 4: N 4 + 2; the ring setting 3: 3
        assertArrayEquals(new int[]{6, 6}, ZslRing.fullResSlots(6L << 30, SLOT_50MP, 50, 4));
        assertArrayEquals(new int[]{3, 6}, ZslRing.fullResSlots(6L << 30, SLOT_50MP, 3, 20));
        for (long avail = 0; avail <= (16L << 30); avail += 256L << 20) {
            int[] s = ZslRing.fullResSlots(avail, SLOT_50MP, 100, 64);
            long bytes = (s[0] + s[1]) * SLOT_50MP;
            assertTrue("never above the 12 MP ring's 66 x 25 MB", bytes <= 66 * SLOT_12MP);
            assertTrue("a quarter of the memory, or the 4-slot floor", avail == 0 || bytes <= Math.max(avail / 4, ZslRing.FULLRES_SLOTS_MIN * SLOT_50MP));
            assertTrue(s[0] >= 1 && s[1] >= 1);
        }
    }
}
