package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

/** P356: the ETTR boost (capture/Ettr): its steps, its limits and the RAW meter. */
public class EttrTest {
    private static ByteBuffer frame(int w, int h, int value, int brightCount, int bright) {
        final ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < w * h; i++) b.putShort((short) value);
        // bright sites on the metered quads (every 4th quad each way: rows / columns multiple of 8)
        int placed = 0;
        b.rewind();
        for (int y = 0; y < h && placed < brightCount; y += 8)
            for (int x = 0; x < w && placed < brightCount; x += 8, placed++) b.putShort((y * w + x) * 2, (short) bright);
        return b;
    }

    @Test
    public void meterReadsTheBrightTail() {
        final int w = 256, h = 256;
        // 1024 metered quads, 0.1 % = 2 skipped: 10 bright quads stay inside the 99.9th percentile
        final float p = Ettr.p999(frame(w, h, 64 + 96, 10, 64 + 480), w, h, w * 2, 64f, 1023f, 4);
        assertEquals(480f / 959f, p, 0.002f);
        // one bright quad is outside it: the frame's level
        final float q = Ettr.p999(frame(w, h, 64 + 96, 1, 1023), w, h, w * 2, 64f, 1023f, 4);
        assertEquals(96f / 959f, q, 0.002f);
    }

    @Test
    public void boostRisesToTheTargetCappedAndSettles() {
        final Ettr e = new Ettr();
        assertTrue(e.update(0.425f, true)); // one stop below 0.85
        assertEquals(1f, e.ev(), 1e-3f);
        for (int i = 0; i < Ettr.SETTLE_FRAMES; i++) assertFalse(e.due());
        assertFalse(e.update(0.80f, true)); // inside the minimal step
        assertTrue(e.update(0.05f, true));
        assertEquals(Ettr.MAX_EV, e.ev(), 1e-6f);
    }

    @Test
    public void clippedOrNoRoomComesDown() {
        final Ettr e = new Ettr();
        e.update(0.2f, true);
        assertEquals(Ettr.MAX_EV, e.ev(), 1e-6f);
        assertTrue(e.update(1f, true));
        assertEquals(Ettr.MAX_EV - 1f, e.ev(), 1e-6f);
        assertTrue(e.update(0.5f, false));
        assertEquals(0f, e.ev(), 0f);
        assertFalse(e.reset());
    }

    @Test
    public void indexAddsTheBoostInsideTheRange() {
        assertEquals(6, Ettr.index(0, 1f, 1f / 6f, -24, 24));
        assertEquals(24, Ettr.index(20, 2f, 1f / 6f, -24, 24));
        assertEquals(-3, Ettr.index(-3, 0f, 1f / 3f, -12, 12));
    }
}
