package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** P145: the once-per-process check of the GPU half RGB repack (HalfRgbUpload.firstLost) on read-backs it may get. */
public class HalfRgbUploadTest {
    private static final int W = 128, H = (65536 + 3 * W - 1) / (3 * W);

    /** The read-back of a driver that keeps every half: the patterns in RGB, zeros past them, alpha 1.0. */
    private static ByteBuffer kept(boolean halves) {
        ByteBuffer b = ByteBuffer.allocate(W * H * 4 * (halves ? 2 : 4)).order(ByteOrder.nativeOrder());
        for (int p = 0; p < W * H; p++) {
            for (int c = 0; c < 4; c++) {
                int h = c == 3 ? 0x3C00 : p * 3 + c < 65536 ? p * 3 + c : 0;
                int at = p * 4 + c;
                if (halves) b.putShort(at * 2, (short) h);
                else b.putFloat(at * 4, HalfFloat.TABLE[h]);
            }
        }
        return b;
    }

    @Test
    public void everyHalfKeptPasses() {
        assertEquals(-1, HalfRgbUpload.firstLost(kept(true), true, W * H));
        assertEquals(-1, HalfRgbUpload.firstLost(kept(false), false, W * H));
    }

    @Test
    public void aFlushedDenormalOrAnotherNaNIsFound() {
        // pattern 0x0001 (the smallest denormal) at pixel 0, green: flushed to +0
        ByteBuffer b = kept(true);
        b.putShort(1 * 2, (short) 0);
        assertEquals(0x0001, HalfRgbUpload.firstLost(b, true, W * H));
        b = kept(false);
        b.putFloat(1 * 4, 0f);
        assertEquals(0x0001, HalfRgbUpload.firstLost(b, false, W * H));
        // a NaN may come back as another NaN, not as a number
        b = kept(true);
        int nan = 0x7E01, p = nan / 3, c = nan % 3;
        b.putShort((p * 4 + c) * 2, (short) 0x7FFF);
        assertEquals(-1, HalfRgbUpload.firstLost(b, true, W * H));
        b.putShort((p * 4 + c) * 2, (short) 0x3C00);
        assertEquals(nan, HalfRgbUpload.firstLost(b, true, W * H));
        // alpha must be 1.0
        b = kept(true);
        b.putShort((5 * 4 + 3) * 2, (short) 0);
        assertEquals(0x3C00, HalfRgbUpload.firstLost(b, true, W * H));
    }
}
