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
     * Bug capture-ettr-oscillation: the ceiling was judged on the boosted exposure - at a scene between ceiling / 2^boost and the
     * ceiling the boost flipped 0 / +1.1 EV every 16 frames (19 changes in 300 frames). P377: the ceiling is one ring frame of
     * the panel's free shutter (the ISO set by hand); judged on the un-boosted shutter and capped to the headroom it settles once,
     * the shutter taking all, half or none of the boost.
     */
    @Test
    public void boostHoldsStillAtTheShutterCeiling() {
        final long cap = 33_333_333L;
        for (long scene : new long[]{4_000_000L, 8_000_000L, 12_000_000L, 16_000_000L, 22_000_000L, 30_000_000L, 40_000_000L})
            for (double share : new double[]{1.0, 0.5, 0.0}) {
                final Ettr e = new Ettr();
                int changes = 0;
                double shutter = scene;
                for (int f = 0; f < 300; f++) {
                    shutter = scene * Math.pow(2, e.ev() * share);
                    final float p = (float) Math.min(1.0, 0.4 * Math.pow(2, e.ev()));
                    if (!e.due()) continue;
                    if (e.update(p, Ettr.shutterHeadroomEv(Math.round(shutter), cap, e.ev()))) changes++;
                }
                final String what = "scene shutter " + scene + ", share " + share + ": boost " + e.ev();
                assertTrue(what + ", " + changes + " changes", changes <= 2);
                assertTrue(what + ", shutter " + shutter, shutter <= Math.max(cap * 1.001, scene)); // past the ceiling: none
            }
        // a short shutter keeps the full boost, one at the ceiling none
        final Ettr bright = new Ettr();
        assertTrue(bright.update(0.2f, Ettr.shutterHeadroomEv(4_000_000L, cap, 0f)));
        assertEquals(Ettr.MAX_EV, bright.ev(), 1e-6f);
        final Ettr dim = new Ettr();
        assertFalse(dim.update(0.2f, Ettr.shutterHeadroomEv(33_333_333L, cap, 0f)));
        assertEquals(0f, dim.ev(), 0f);
    }

    @Test
    public void headroomIsJudgedWithoutTheBoost() {
        final long cap = 32_000_000L;
        assertEquals(1f, Ettr.shutterHeadroomEv(32_000_000L, cap, 1f), 1e-5f); // 32 ms with +1 EV: 16 ms without it
        assertEquals(0f, Ettr.shutterHeadroomEv(32_000_000L, cap, 0f), 1e-5f);
        assertEquals(-1f, Ettr.shutterHeadroomEv(64_000_000L, cap, 0f), 1e-5f);
        assertEquals("no shutter known: no room", 0f, Ettr.shutterHeadroomEv(null, cap, 0f), 0f);
        assertEquals(0f, Ettr.shutterHeadroomEv(8_000_000L, 0, 0f), 0f);
        // a boost above the headroom comes down to it at the next meter (no stop below the ceiling left: 0)
        final Ettr e = new Ettr();
        e.update(0.2f, true);
        assertEquals(Ettr.MAX_EV, e.ev(), 1e-6f);
        assertTrue(e.update(0.8f, 0.5f));
        assertEquals(0.5f, e.ev(), 1e-6f);
        assertTrue(e.update(0.8f, 0f));
        assertEquals(0f, e.ev(), 0f);
    }

    /**
     * P377: the owner's X200 Ultra indoors (log 2026-10-28 13:29): the AE at 8.33 ms (a 60 Hz half period) and ISO 1883, the
     * brightest quads at 0.35 - the boost is light now: 16.67 ms, two whole half periods (+1 EV), at the same ISO; the next meter
     * at it sees 0.70 and stays. The shutter stays inside one ring frame, never below the AE's own.
     */
    @Test
    public void theBoostIsTheShutterInWholeHalfPeriods() {
        final long ref = 8_333_326L, frame = 33_333_333L;
        final Ettr e = new Ettr();
        e.learnRef(ref, 1883);
        assertEquals(ref, e.refNs());
        final float headroom = (float) (Math.log((double) frame / ref) / Math.log(2));
        final float want = e.wanted(0.35f, headroom);
        assertEquals(Math.log(0.85 / 0.35) / Math.log(2), want, 1e-4);
        final long s = Ettr.shutterNs(ref, want, frame);
        assertEquals(16_666_666L, s);
        assertEquals(1f, Ettr.boostOf(s, ref), 1e-3f);
        assertTrue(e.deliver(Ettr.boostOf(s, ref)));
        e.learnRef(s, 1883); // a boosted frame does not move the reference
        assertEquals(ref, e.refNs());
        assertTrue("+1 EV doubled the tail: inside the step", Math.abs(e.wanted(0.70f, headroom) - e.ev()) < Ettr.MIN_STEP_EV);
        // a darker scene: 25 ms; the most a ring frame allows: 4 half periods (33.3 ms), not 3 for 8333326 ns x 4 = 33333304
        assertEquals(24_999_999L, Ettr.shutterNs(ref, 1.7f, frame));
        assertEquals(33_333_332L, Ettr.shutterNs(ref, 2f, frame));
        // 50 Hz: whole 10 ms
        assertEquals(30_000_000L, Ettr.shutterNs(10_000_000L, 2f, frame));
        // no lamp half period: the exact boost
        assertEquals(8_000_000L, Ettr.shutterNs(4_000_000L, 1f, frame));
        // a ceiling under the reference or no boost: the AE's shutter
        assertEquals(ref, Ettr.shutterNs(ref, 1f, 8_000_000L));
        assertEquals(ref, Ettr.shutterNs(ref, 0f, frame));
        assertEquals(0f, Ettr.boostOf(ref, ref), 0f);
    }

    /**
     * P377 (review): a settled frame is metered against the boost in force - doubled tail at +1: inside the step; a scene a stop
     * darker that the AE answered with its ISO (the tail held): no change (the boost stays the light over the AE's, not +2 with the
     * compensation on top, clipped); the AE's exposure for the viewfinder loop's target (ISO x s).
     */
    @Test
    public void settledFramesKeepTheBoostThroughTheAesAnswer() {
        final long ref = 8_333_326L, frame = 33_333_333L;
        final float headroom = (float) (Math.log((double) frame / ref) / Math.log(2));
        final Ettr e = new Ettr();
        e.learnRef(ref, 1883);
        assertEquals(1883 * (ref / 1e9), e.refProduct(), 1e-9);
        assertTrue(e.deliver(Ettr.boostOf(Ettr.shutterNs(ref, e.wanted(0.35f, headroom), frame), ref)));
        assertEquals(1f, e.ev(), 1e-3f);
        assertTrue("+1 EV doubled the tail", Math.abs(e.wanted(0.70f, headroom) - e.ev()) < Ettr.MIN_STEP_EV);
        assertTrue("a darker scene, the AE's ISO up, the tail held", Math.abs(e.wanted(0.70f, headroom, e.ev()) - e.ev()) < Ettr.MIN_STEP_EV);
        // back to 0: the reference (and its exposure) goes with it
        assertTrue(e.deliver(0f));
        assertEquals(0L, e.refNs());
        assertEquals(0, e.refProduct(), 0);
    }

    /** P377: a clipped tail starts over from the AE (a new scene: its own reference), no room no boost. */
    @Test
    public void theShutterPathStartsOverWhenClipped() {
        final Ettr e = new Ettr();
        e.learnRef(4_000_000L, 100);
        assertTrue(e.deliver(e.wanted(0.2f, 3f)));
        assertEquals(Ettr.MAX_EV, e.ev(), 1e-6f);
        assertEquals(0f, e.wanted(1f, 3f), 0f);
        assertEquals(0f, e.wanted(0.3f, 0f), 0f);
        assertEquals(0.5f, e.wanted(0.1f, 0.5f), 1e-6f);
        assertTrue(e.reset());
        assertEquals("the reference goes with it", 0L, e.refNs());
    }

    /** P377: with the ultrashort frames the brightest 0.5 % may clip (a lamp no longer holds the boost at 0), without them 0.1 %. */
    @Test
    public void aLampClipsUnderTheUltrashortFrames() {
        final int w = 256, h = 256;
        // 1024 metered quads at 0.3, 4 lamp quads clipped: the 99.9th percentile is the lamp, the 99.5th the scene
        final ByteBuffer lamp = frame(w, h, 64 + Math.round(0.3f * 959), 4, 1023);
        assertEquals(1f, Ettr.tail(lamp, w, h, w * 2, 64f, 1023f, 4, Ettr.tail(false)), 0.002f);
        assertEquals(0.3f, Ettr.tail(lamp, w, h, w * 2, 64f, 1023f, 4, Ettr.tail(true)), 0.002f);
        assertEquals(Ettr.p999(lamp, w, h, w * 2, 64f, 1023f, 4), Ettr.tail(lamp, w, h, w * 2, 64f, 1023f, 4, 0.001f), 0f);
    }

    /** Bug capture-ettr-motion-ceiling, P377: what holds the boost - the ISO alone set by hand no longer does. */
    @Test
    public void heldUnderTheMotionCeiling() {
        assertEquals("off", Ettr.hold(false, true, true, true, true, true));
        assertEquals("the motion mode (short shutters)", Ettr.hold(true, false, false, true, false, false));
        assertEquals("hand-set shutter", Ettr.hold(true, false, false, false, true, false));
        assertEquals("own native AE", Ettr.hold(true, true, false, true, true, false));
        assertEquals("vivo SDK", Ettr.hold(true, false, true, true, false, false));
        assertEquals("forced stabilisation", Ettr.hold(true, false, false, false, false, true));
        assertEquals(null, Ettr.hold(true, false, false, false, false, false));
        assertEquals("man", Ettr.holdCode("hand-set shutter"));
        assertEquals("mm", Ettr.holdCode("the motion mode (short shutters)"));
        assertEquals("off", Ettr.holdCode("off"));
    }

    @Test
    public void indexAddsTheBoostInsideTheRange() {
        assertEquals(6, Ettr.index(0, 1f, 1f / 6f, -24, 24));
        assertEquals(24, Ettr.index(20, 2f, 1f / 6f, -24, 24));
        assertEquals(-3, Ettr.index(-3, 0f, 1f / 3f, -12, 12));
    }
}
