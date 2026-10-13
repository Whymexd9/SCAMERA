package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ShortBuffer;

public class RawLevelTest {
    private static ShortBuffer flat(int w, int h, int stride, int value) {
        short[] a = new short[stride * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) a[y * stride + x] = (short) value;
        return ShortBuffer.wrap(a);
    }

    @Test
    public void levelAboveBlackLinear() {
        // 10-bit RAW in 16 bits (X500 Pro Max: black 64, white 1023), row padding beyond the width
        assertEquals(0.5, RawLevel.mean(flat(4096, 3072, 4160, 64 + 479), 4096, 3072, 4160, 64, 1023), 0.002);
        assertEquals(0.0, RawLevel.mean(flat(400, 300, 400, 30), 400, 300, 400, 64, 1023), 1e-9); // below black
        assertEquals(1.0, RawLevel.mean(flat(400, 300, 400, 4000), 400, 300, 400, 64, 1023), 1e-9); // clipped at white
    }

    @Test
    public void wideRawAtItsOwnRange() {
        // P104 DCG 14 bit on the X500 Pro Max: white 1023 declared, samples to 16383, black scaled alike
        float[] wide = RawLevel.range(64, 1023, 16383);
        assertEquals(1024, wide[0], 0.5);
        assertEquals(16383, wide[1], 0);
        float[] plain = RawLevel.range(64, 1023, 0);
        assertEquals(64, plain[0], 0);
        assertEquals(1023, plain[1], 0);
    }

    @Test
    public void badInputsGiveNoLevel() {
        assertTrue(Double.isNaN(RawLevel.mean(flat(400, 300, 400, 500), 400, 300, 399, 64, 1023)));
        assertTrue(Double.isNaN(RawLevel.mean(flat(400, 300, 400, 500), 400, 300, 400, 64, 64)));
        assertTrue(Double.isNaN(RawLevel.mean(ShortBuffer.allocate(10), 400, 300, 400, 64, 1023)));
    }
}
