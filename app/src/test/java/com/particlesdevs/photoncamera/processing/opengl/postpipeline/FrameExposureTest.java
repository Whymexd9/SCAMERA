package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.LiveRawFrame;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P383: the RAW viewfinder draws a frame against its own exposure (FrameExposure + ToneEase): a move of the camera's exposure is
 * cancelled in its frame, a reported move the frame does not show is refused, a hand-set exposure is not followed.
 */
public class FrameExposureTest {
    private static final long FRAME_NS = 33_333_333L;

    /** A 64 x 48 RGGB 16-bit frame, black 64, white 1023: the level {@code v} (a fraction of white) with {@code clipped} rows at white. */
    static LiveRawFrame.Frame frame(float v, int clippedRows) {
        final int w = 64, h = 48;
        final ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                b.putShort((short) (y < clippedRows ? 1023 : Math.round(64 + v * (1023 - 64))));
        b.position(0);
        final LiveRawFrame.Frame f = new LiveRawFrame.Frame();
        f.buffer = b;
        f.width = w;
        f.height = h;
        f.rowStride = w * 2;
        f.whiteLevel = 1023;
        f.blackLevel = new float[]{64, 64, 64, 64};
        f.crop = new float[]{0, 0, 1, 1};
        return f;
    }

    @Test
    public void theExposureIsTimeTimesTheAnalogIso() {
        assertEquals(Math.log(0.008333333 * 1900) / Math.log(2), FrameExposure.log2Product(8_333_333L, 1900, 6400), 1e-6);
        // a gain past the analog ceiling is applied after the RAW
        assertEquals(FrameExposure.log2Product(10_000_000L, 1600, 1600), FrameExposure.log2Product(10_000_000L, 6400, 1600), 1e-9);
        assertTrue(Double.isNaN(FrameExposure.log2Product(0, 100, 6400)));
        assertTrue(Double.isNaN(FrameExposure.log2Product(10_000_000L, 0, 6400)));
    }

    @Test
    public void theLevelFollowsTheExposureAndIgnoresAClippedLamp() {
        final double l1 = FrameExposure.log2Level(frame(0.05f, 0)), l2 = FrameExposure.log2Level(frame(0.10f, 0));
        assertEquals(1.0, l2 - l1, 0.01);
        // a third of the frame clipped: the median still reads the rest
        assertEquals(l1, FrameExposure.log2Level(frame(0.05f, 16)), 0.01);
        assertTrue(Double.isNaN(FrameExposure.log2Level(null)));
    }

    @Test
    public void smallMovesAreTakenLargeOnesOnlyWhenTheFrameShowsThem() {
        final FrameExposure.Track t = new FrameExposure.Track();
        assertEquals(3.0, t.next(3.0, -4, true), 0);
        assertEquals(3.3, t.next(3.3, -3.9, true), 1e-9);              // the AE's step: taken as reported
        assertEquals(4.8, t.next(4.8, -2.4, true), 1e-9);              // +1.5 EV and the frame 1.5 EV brighter: taken
        // the vivo V2502A's report: ISO 8019 -> 22698 -> 8328 for frames whose RAW did not change
        assertEquals(4.8, t.next(4.8 + 1.5, -2.4, true), 1e-9);
        assertEquals(4.8, t.next(4.8 + 0.05, -2.4, true), 1e-9);       // and back: refused (from the glitch's report)
        assertEquals("the glitch and the way back (-1.45 EV against an unchanged frame)", 2, t.takeRefused());
        assertEquals(4.8, t.next(Double.NaN, -2.4, true), 1e-9);       // unknown: held
        assertEquals(4.8, t.next(5.8, -1.4, false), 1e-9);             // vf_ae_scene 0: not followed, drawn as before
        assertEquals("a hand-set move is followed (the photo's AE meters it away too): -0.5 from the last frame", 4.3, t.next(5.3, -1.9, true), 1e-9);
        t.reset();
        assertEquals(1.0, t.next(1.0, -4, true), 0);
        assertEquals(0.0, new FrameExposure.Track().next(Double.NaN, -4, true), 0);
    }

    @Test
    public void aTrustedMoveTheFrameDoesNotShowIsCountedAsSuspect() {
        final FrameExposure.Track t = new FrameExposure.Track();
        t.next(3.0, -4, true);
        t.next(3.3, -3.7, true);   // followed by the level: fine
        t.next(3.7, -3.7, true);   // +0.4 EV reported, the frame unchanged: taken (small), suspect
        t.next(3.75, -3.65, true); // under SUSPECT_EV: not counted
        assertEquals(1, t.takeSuspect());
        assertEquals(0, t.takeSuspect());
        assertEquals(0, t.takeRefused());
    }

    /**
     * The owner's case end to end: a still scene, the camera's AE moving the exposure; the meter as WhyAe on a free scene (ae =
     * target / the frame's level). Drawn = the frame's level x the drawn gain must hold still (before: the move showed in full, then
     * eased back).
     */
    @Test
    public void aStillSceneHoldsStillWhileTheCamerasExposureMoves() {
        final FrameExposure.Track track = new FrameExposure.Track();
        final ToneEase tone = new ToneEase();
        final double scene = -6, target = -2.5;
        // the camera's AE: 8.33 ms at ISO 1914 -> 1763 -> 2273 -> 1938 (the owner's cabin, one second apart) in single-frame hops
        final int[] isos = {1914, 1914, 1763, 1763, 1763, 2273, 2273, 2273, 2273, 1938, 1938, 1938};
        double meteredP = 0, largest = 0, first = Double.NaN;
        float ae = 1f;
        for (int i = 0; i < isos.length * 6; i++) {
            final int iso = isos[i / 6];
            final double p = FrameExposure.log2Product(8_333_333L, iso, 6400), level = scene + p;
            final double logP = track.next(p, level, true);
            if (i % 2 == 0) { // the meter every other frame (METER_NS at 30 frames a second)
                ae = (float) Math.pow(2, target - level);
                meteredP = logP;
            }
            tone.toward(ToneEaseTest.result(ae), ae, new float[]{0.5f, 1f, 0.7f, 2f}, meteredP, FRAME_NS);
            final double drawn = level + Math.log(tone.aeFor(logP)) / Math.log(2);
            if (Double.isNaN(first)) first = drawn;
            largest = Math.max(largest, Math.abs(drawn - first));
        }
        assertTrue("the drawn picture holds within 0.005 EV: " + largest, largest < 0.005);
    }

    @Test
    public void aSceneChangeEasesOnceWithoutOvershootWhileTheCameraCatchesUp() {
        // +2 EV (a window) in one frame; the camera's AE takes 1.6 EV of it back over ~0.5 s; the meter every other frame
        final FrameExposure.Track track = new FrameExposure.Track();
        final ToneEase tone = new ToneEase();
        final double target = -2.5;
        double p = FrameExposure.log2Product(8_333_333L, 1900, 6400), meteredP = 0, prev = Double.NaN, largest = 0, last = 0;
        float ae = 1f;
        for (int i = 0; i < 60; i++) {
            final double scene = i == 0 ? -6 : -4;
            if (i >= 3) p -= (p - (FrameExposure.log2Product(8_333_333L, 1900, 6400) - 1.6)) * 0.2; // the HAL's lag
            final double level = scene + p, logP = track.next(p, level, true);
            if (i % 2 == 0) {
                ae = (float) Math.max(0.5, Math.min(5, Math.pow(2, target - level)));
                meteredP = logP;
            }
            tone.toward(ToneEaseTest.result(ae), ae, new float[]{0.5f, 1f, 0.7f, 2f}, meteredP, FRAME_NS);
            final double drawn = level + Math.log(tone.aeFor(logP)) / Math.log(2);
            if (i >= 2 && !Double.isNaN(prev)) largest = Math.max(largest, Math.abs(drawn - prev));
            prev = drawn;
            last = drawn;
            assertTrue("no overshoot below the target at frame " + i + ": " + drawn, drawn >= target - 0.02);
        }
        assertEquals(target, last, 0.02);
        assertTrue("an own move of at most 0.21 EV a frame after the cut: " + largest, largest <= 0.21);
    }
}
