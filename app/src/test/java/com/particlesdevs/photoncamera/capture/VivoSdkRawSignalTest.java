package com.particlesdevs.photoncamera.capture;

import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class VivoSdkRawSignalTest {
    @Test public void rejectsAllZeroPixelsEvenWhenPaddingIsNonZero() {
        ByteBuffer data = ByteBuffer.allocate(16);
        data.putLong(4, -1L);
        data.putShort(12, (short) 0);
        data.putShort(14, (short) 0);
        assertFalse(VivoSdkFullResCapture.hasNonZeroPixels(data, 2, 2, 12));
        data.putShort(14, (short) 64);
        assertTrue(VivoSdkFullResCapture.hasNonZeroPixels(data, 2, 2, 12));
    }

    @Test public void preservesBufferPositionAndChecksTheLastPixel() {
        ByteBuffer data = ByteBuffer.allocateDirect(35);
        data.position(3);
        data.put(34, (byte) 1);
        assertTrue(VivoSdkFullResCapture.hasNonZeroPixels(data, 4, 4, 8));
        assertEquals(3, data.position());
        data.put(34, (byte) 0);
        assertFalse(VivoSdkFullResCapture.hasNonZeroPixels(data, 4, 4, 8));
    }

    @Test public void rejectsTruncatedAndImpossibleLayouts() {
        ByteBuffer data = ByteBuffer.allocate(15);
        data.put(0, (byte) 1);
        assertFalse(VivoSdkFullResCapture.hasNonZeroPixels(data, 4, 2, 8));
        assertFalse(VivoSdkFullResCapture.hasNonZeroPixels(data, 4, 1, 7));
        assertFalse(VivoSdkFullResCapture.hasNonZeroPixels(data, 0, 1, 8));
        assertFalse(VivoSdkFullResCapture.hasNonZeroPixels(data, Integer.MAX_VALUE, 1, Integer.MAX_VALUE));
    }
}
