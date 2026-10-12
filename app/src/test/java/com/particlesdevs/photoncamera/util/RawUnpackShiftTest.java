package com.particlesdevs.photoncamera.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RawUnpackShiftTest {
    private static final int RAW10 = 0x25, RAW12 = 0x26;

    @Test
    public void shiftPutsPackedSamplesOnTheDeclaredWhite() {
        assertEquals(0, RawUnpack.shiftFor(RAW10, 1023)); // 10-bit sensors (X8U, Pixel tele)
        assertEquals(2, RawUnpack.shiftFor(RAW10, 4095)); // Pixel 10 Pro XL main: 12-bit levels over RAW10
        assertEquals(0, RawUnpack.shiftFor(RAW12, 4095));
        assertEquals(2, RawUnpack.shiftFor(RAW12, 16383));
        assertEquals(4, RawUnpack.shiftFor(RAW10, 16383));
        assertEquals(0, RawUnpack.shiftFor(0x20, 4095)); // RAW_SENSOR is not packed
        assertEquals(0, RawUnpack.shiftFor(RAW10, 0));
    }

    @Test
    public void javaUnpackShiftsRaw10() {
        // one group: samples 64, 65, 1023, 512 -> MSB bytes and the low-bit byte
        int[] v = {64, 65, 1023, 512};
        ByteBuffer src = ByteBuffer.allocate(5);
        int low = 0;
        for (int i = 0; i < 4; i++) { src.put(i, (byte) (v[i] >> 2)); low |= (v[i] & 3) << (2 * i); }
        src.put(4, (byte) low);
        ByteBuffer dst = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        try {
            RawUnpack.setPackedShift(2, 0);
            RawUnpack.unpack(src, RAW10, 4, 1, 5, dst);
            for (int i = 0; i < 4; i++) assertEquals(v[i] << 2, dst.getShort(2 * i) & 0xFFFF);
        } finally {
            RawUnpack.setPackedShift(0, 0);
        }
    }
}
