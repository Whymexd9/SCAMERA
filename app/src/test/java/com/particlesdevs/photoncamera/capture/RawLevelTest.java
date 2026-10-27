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

    /**
     * P365 (review): a packed RAW10 frame (the Find X7 Ultra's stream since DeviceDefaults v7), unpacked as
     * CaptureController.meterSamples does, gives the level of the same 16-bit frame (it was skipped before: RAW_SENSOR only).
     */
    @Test
    public void unpackedRaw10FrameGivesItsLevel() {
        final int w = 400, h = 300, stride = w * 10 / 8 + 12, value = 64 + 479;
        final java.nio.ByteBuffer packed = java.nio.ByteBuffer.allocateDirect(stride * h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x += 4) {
                final int at = y * stride + x / 4 * 5;
                for (int k = 0; k < 4; k++) packed.put(at + k, (byte) (value >> 2));
                packed.put(at + 4, (byte) ((value & 3) * 0x55)); // the low 2 bits of the four samples
            }
        final java.nio.ByteBuffer unpacked = java.nio.ByteBuffer.allocateDirect(w * h * 2).order(java.nio.ByteOrder.nativeOrder());
        assertTrue(com.particlesdevs.photoncamera.util.RawUnpack.unpack(packed, android.graphics.ImageFormat.RAW10, w, h, stride, unpacked));
        RawLevel.clear();
        RawLevel.observe(unpacked, w, h, w * 2, 1234L, 64, 1023);
        final RawLevel.Sample s = RawLevel.sample();
        RawLevel.clear();
        assertTrue(s != null);
        assertEquals(0.5, s.level, 0.002);
        assertEquals(1234L, s.timestampNs);
    }

    @Test
    public void badInputsGiveNoLevel() {
        assertTrue(Double.isNaN(RawLevel.mean(flat(400, 300, 400, 500), 400, 300, 399, 64, 1023)));
        assertTrue(Double.isNaN(RawLevel.mean(flat(400, 300, 400, 500), 400, 300, 400, 64, 64)));
        assertTrue(Double.isNaN(RawLevel.mean(ShortBuffer.allocate(10), 400, 300, 400, 64, 1023)));
    }
}
