package com.particlesdevs.photoncamera.processing;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P315: the RAW viewfinder's black of a stream that came without its pedestal (OPPO Find X8 Ultra, sensor mode 8: data floor ~0.4
 * DN for a declared 64 - the viewfinder was green and half black). Only non-zero samples far under the declared black prove the
 * pedestal lost; zero padding, read noise around the pedestal and a slight drift never do.
 */
public class PedestalFloorTest {
    private static final int W = 256, H = 192;

    /** A plain RGGB frame: a dark left half (signal ~2 DN), a lit right half, {@code pedestal} added, noise {@code sigma}. */
    private static ByteBuffer frame(int pedestal, double sigma, int zeroRows, long seed) {
        Random random = new Random(seed);
        ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            final boolean green = ((x ^ y) & 1) == 1;
            final double signal = x < W / 2 ? 2 : (green ? 120 : 60);
            long v = Math.round(pedestal + signal + sigma * random.nextGaussian());
            if (y < zeroRows) v = 0;
            s.put(y * W + x, (short) Math.max(0, Math.min(1023, v)));
        }
        return b;
    }

    /** A frame without a pedestal and without dark samples (every site ~100 DN): it proves nothing either way. */
    private static ByteBuffer bright(long seed) {
        Random random = new Random(seed);
        ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        for (int i = 0; i < W * H; i++) s.put(i, (short) Math.round(100 + 1.5 * random.nextGaussian()));
        return b;
    }

    private static float measure(ByteBuffer b) {
        return PedestalFloor.measure(b.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer(), W, H, W, 1, 64f);
    }

    @Test
    public void aStreamWithoutItsPedestalIsFound() {
        final float floor = measure(frame(0, 1.5, 0, 1));
        assertFalse(Float.isNaN(floor));
        assertTrue("the floor is the dark blocks' level, not the declared 64: " + floor, floor < 6f);
    }

    @Test
    public void aPedestalPaddingOrNoiseIsNeverTakenForALoss() {
        assertTrue(Float.isNaN(measure(frame(64, 1.5, 0, 2))));
        assertTrue("zero padding rows prove nothing", Float.isNaN(measure(frame(64, 1.5, 10, 3))));
        assertTrue("read noise around the pedestal", Float.isNaN(measure(frame(64, 8, 0, 4))));
        assertTrue("a 64 -> 60 drift", Float.isNaN(measure(frame(60, 1.5, 0, 5))));
    }

    @Test
    public void theViewfinderTakesTheFloorAfterTwoMeasurementsOfTheSession() throws Exception {
        PedestalFloor p = new PedestalFloor();
        final float[] declared = {64, 64, 64, 64};
        final ByteBuffer lost = frame(0, 1.5, 0, 6);
        assertEquals("one measurement is not enough", 64f, p.black(1, lost, W, H, W * 2, 1, declared)[0], 0f);
        Thread.sleep(PedestalFloor.INTERVAL_NS / 1_000_000L + 20);
        final float[] black = p.black(1, lost, W, H, W * 2, 1, declared);
        assertTrue("the second agreeing measurement switches: " + black[0], black[0] < 6f);
        // a bright frame of the same session proves nothing and keeps the floor
        Thread.sleep(PedestalFloor.INTERVAL_NS / 1_000_000L + 20);
        assertTrue(Float.isNaN(measure(bright(7))));
        assertTrue(p.black(1, bright(7), W, H, W * 2, 1, declared)[0] < 6f);
        // a new camera session starts from the declared black again
        assertEquals(64f, p.black(2, frame(64, 1.5, 0, 8), W, H, W * 2, 1, declared)[0], 0f);
        assertEquals("the declared array is never changed", 64f, declared[0], 0f);
    }
}
