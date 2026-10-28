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

    /**
     * Bug capture-ettr-oscillation: the ceiling was judged on the boosted ISO - at a scene ISO between 4x base / 2^boost and 4x
     * base the boost flipped 0 / +1.1 EV every 16 frames (19 changes in 300 frames at ISO 200-390, base 100). Judged on the
     * un-boosted ISO and capped to the headroom it settles once, the AE putting all, half or none of the boost into the ISO.
     */
    @Test
    public void boostHoldsStillAtTheIsoCeiling() {
        for (int scene : new int[]{100, 150, 200, 250, 300, 390, 450})
            for (double share : new double[]{1.0, 0.5, 0.0}) {
                final Ettr e = new Ettr();
                int changes = 0;
                double iso = scene;
                for (int f = 0; f < 300; f++) {
                    iso = scene * Math.pow(2, e.ev() * share);
                    final float p = (float) Math.min(1.0, 0.4 * Math.pow(2, e.ev()));
                    if (!e.due()) continue;
                    if (e.update(p, Ettr.headroomEv((int) Math.round(iso), 100, e.ev()))) changes++;
                }
                final String what = "scene ISO " + scene + ", AE share " + share + ": boost " + e.ev();
                assertTrue(what + ", " + changes + " changes", changes <= 2);
                assertTrue(what + ", ISO " + iso, iso <= Math.max(401, scene)); // a scene past the ceiling gets none
            }
        // the bright scenes keep their full boost, at the ceiling none
        final Ettr bright = new Ettr();
        assertTrue(bright.update(0.2f, Ettr.headroomEv(50, 100, 0f)));
        assertEquals(Ettr.MAX_EV, bright.ev(), 1e-6f);
        final Ettr dim = new Ettr();
        assertFalse(dim.update(0.2f, Ettr.headroomEv(390, 100, 0f)));
        assertEquals(0f, dim.ev(), 0f);
    }

    @Test
    public void headroomIsJudgedWithoutTheBoost() {
        assertEquals(1f, Ettr.headroomEv(400, 100, 1f), 1e-5f); // ISO 400 with +1 EV: 200 without it, a stop below 4x base
        assertEquals(0f, Ettr.headroomEv(400, 100, 0f), 1e-5f);
        assertEquals(-1f, Ettr.headroomEv(800, 100, 0f), 1e-5f);
        assertEquals(Float.POSITIVE_INFINITY, Ettr.headroomEv(null, 100, 0f), 0f);
        assertEquals(Float.POSITIVE_INFINITY, Ettr.headroomEv(100, null, 0f), 0f);
        // a boost above the headroom comes down to it at the next meter (no stop below the ceiling left: 0)
        final Ettr e = new Ettr();
        e.update(0.2f, true);
        assertEquals(Ettr.MAX_EV, e.ev(), 1e-6f);
        assertTrue(e.update(0.8f, 0.5f));
        assertEquals(0.5f, e.ev(), 1e-6f);
        assertTrue(e.update(0.8f, 0f));
        assertEquals(0f, e.ev(), 0f);
    }

    /** Bug capture-ettr-motion-ceiling: the «Движение» ceiling's request is AE off - the boost is held there. */
    @Test
    public void heldUnderTheMotionCeiling() {
        assertEquals("off", Ettr.hold(false, true, true, true, true));
        assertEquals("the motion shutter ceiling (AE off)", Ettr.hold(true, false, false, true, false));
        assertEquals("hand-set exposure", Ettr.hold(true, false, false, false, true));
        assertEquals("own native AE", Ettr.hold(true, true, false, true, true));
        assertEquals("vivo SDK", Ettr.hold(true, false, true, true, false));
        assertEquals(null, Ettr.hold(true, false, false, false, false));
    }

    @Test
    public void indexAddsTheBoostInsideTheRange() {
        assertEquals(6, Ettr.index(0, 1f, 1f / 6f, -24, 24));
        assertEquals(24, Ettr.index(20, 2f, 1f / 6f, -24, 24));
        assertEquals(-3, Ettr.index(-3, 0f, 1f / 3f, -12, 12));
    }
}
