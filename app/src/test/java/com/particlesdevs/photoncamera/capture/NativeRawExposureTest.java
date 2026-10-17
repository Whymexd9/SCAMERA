package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.Range;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** P192: the own AE of the OPPO Find X7 Ultra's native FULLRES mode (its HAL AE has no statistics there). */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class NativeRawExposureTest {
    private static NativeRawExposure ae(long startNs, int startIso) {
        return new NativeRawExposure(new Range<>(10_000L, 1_000_000_000L), new Range<>(100, 6400),
                CaptureController.NATIVE_FRAME_NS, startNs, startIso);
    }

    @Test public void aMedianAtTheSetpointNeedsNoChange() {
        assertEquals(1.0, NativeRawExposure.multiplier(NativeRawExposure.SETPOINT, 0.5f, 0f), 1e-6);
        assertEquals(2.0, NativeRawExposure.multiplier(NativeRawExposure.SETPOINT / 2, 0.3f, 0f), 1e-6);
        assertEquals("ev +1", 2.0, NativeRawExposure.multiplier(NativeRawExposure.SETPOINT, 0.3f, 1f), 1e-6);
    }

    @Test public void highlightsPullTheExposureDownAtMostTwoStopsUnderTheSetpoint() {
        // the median at the setpoint, the highlights clipped: down to keep them, ...
        double m = NativeRawExposure.multiplier(NativeRawExposure.SETPOINT, 1.0f, 0f);
        assertEquals(NativeRawExposure.HIGHLIGHT, m, 1e-6);
        // ... but never more than two stops under the setpoint
        assertEquals(0.25, NativeRawExposure.multiplier(NativeRawExposure.SETPOINT, 20f, 0f), 1e-6);
        assertEquals("a black frame is capped", 16.0, NativeRawExposure.multiplier(0f, 0f, 0f), 1e-6);
    }

    @Test public void theShutterComesFirstThenTheSensitivityThenTheFrameTime() {
        NativeRawExposure a = ae(1_000_000L, 100);
        assertEquals(1_000_000L, a.exposureNs());
        assertEquals(100, a.iso());
        // 30 ms at ISO 100 -> 30 ms (a multiple of 10 ms)
        a.split(30_000_000.0);
        assertEquals(30_000_000L, a.exposureNs());
        assertEquals(100, a.iso());
        // 25 ms -> 20 ms ISO 125 (no 50 Hz banding)
        a.split(25_000_000.0);
        assertEquals(20_000_000L, a.exposureNs());
        assertEquals(125, a.iso());
        // dark: the shutter stops at 1/30 s (30 ms after the 10 ms step), the sensitivity rises
        a.split(300_000_000.0);
        assertEquals(30_000_000L, a.exposureNs());
        assertEquals(1000, a.iso());
        // darker than the highest ISO allows: the frame time, never longer
        a.split(1e12);
        assertTrue(a.exposureNs() <= CaptureController.NATIVE_FRAME_NS);
        assertEquals(6400, a.iso());
        assertEquals(CaptureController.NATIVE_FRAME_NS, a.frameDurationNs());
    }

    /**
     * P209 (owner's X7 Ultra, 2026-10-17: FULLRES stripes under 60 Hz mains): the shutter in whole periods of the place's flicker -
     * 120 Hz (8.33 ms), 100 Hz (10 ms) or none; the change itself re-splits the exposure.
     */
    @Test public void theShutterTakesWholePeriodsOfTheFlickerOfThePlace() {
        NativeRawExposure a = ae(30_000_000L, 1600);
        assertTrue("30 ms is no whole 120 Hz period", a.flicker(120));
        assertEquals(0, a.exposureNs() % 8_333_333L, 4);
        a.split(300_000_000.0); // dark: 1/30 s -> four 8.33 ms periods
        assertEquals(33_333_332L, a.exposureNs());
        assertEquals(900, a.iso());
        a.split(25_000_000.0);
        assertEquals(16_666_666L, a.exposureNs());
        assertFalse("the same flicker changes nothing", a.flicker(120));
        a.flicker(100);
        a.split(25_000_000.0);
        assertEquals(20_000_000L, a.exposureNs());
        a.flicker(0); // no snap
        a.split(25_000_000.0);
        assertEquals(25_000_000L, a.exposureNs());
    }

    /** A scene whose median is proportional to the exposure: the loop settles inside the dead band within a few steps. */
    @Test public void theLoopSettlesOnTheSetpointFromFarAway() {
        for (double scene : new double[]{2e-10, 5e-9, 1e-7}) { // median per ISO-100 ns
            NativeRawExposure a = ae(1_000_000L, 100);
            int steps = 0;
            for (int frame = 0; frame < 200; frame++) {
                float p50 = (float) Math.min(1.0, scene * a.total());
                a.update(p50, Math.min(1f, p50 * 3), a.exposureNs(), a.iso(), 0f);
                steps = frame;
                if (Math.abs(Math.log(p50 / NativeRawExposure.SETPOINT) / Math.log(2)) < NativeRawExposure.DEAD_BAND_EV + 0.05) break;
            }
            float p50 = (float) Math.min(1.0, scene * a.total());
            assertTrue("scene " + scene + " median " + p50 + " after " + steps,
                    Math.abs(Math.log(p50 / NativeRawExposure.SETPOINT) / Math.log(2)) < 0.25 || a.iso() == 6400 || a.iso() == 100);
            assertTrue("steps " + steps, steps < 40);
        }
    }

    @Test public void aChangeWaitsUntilTheFramesShowIt() {
        NativeRawExposure a = ae(10_000_000L, 100);
        assertTrue(a.update(NativeRawExposure.SETPOINT / 4, 0.1f, 10_000_000L, 100, 0f));
        long asked = a.exposureNs();
        int iso = a.iso();
        // the next frames still carry the old exposure: no new change until they show it or SETTLE_FRAMES passed
        for (int i = 0; i < NativeRawExposure.SETTLE_FRAMES - 1; i++)
            assertFalse(a.update(NativeRawExposure.SETPOINT / 4, 0.1f, 10_000_000L, 100, 0f));
        assertEquals(asked, a.exposureNs());
        assertEquals(iso, a.iso());
    }

    @Test public void theMeterReadsQuadCellsInTheirColourOrder() {
        // BGGR Quad (cfa 3, block 2): every cell flat; R 300, G 500, B 200 over black 64, white 1023
        int w = 64, h = 48, stride = w * 2;
        ByteBuffer raw = ByteBuffer.allocateDirect(stride * h).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int ph = ((y / 2) & 1) * 2 + ((x / 2) & 1); // 0 B, 1 G, 2 G, 3 R
            raw.putShort((y * w + x) * 2, (short) (ph == 0 ? 200 : ph == 3 ? 300 : 500));
        }
        float[] black = {64, 64, 64, 64};
        float[] m = NativeRawExposure.meter(raw, w, h, stride, 3, 2, black, 1023f);
        assertNotNull(m);
        float r = (300 - 64) / 959f, g = (500 - 64) / 959f, b = (200 - 64) / 959f;
        float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        assertEquals(l, m[0], 1.5f / 1024);
        assertEquals(g, m[1], 1.5f / 1024);
        assertNull("no frame", NativeRawExposure.meter(null, w, h, stride, 3, 2, black, 1023f));
    }
    /**
     * P201 (owner: «при работе фулреза работал полностью ручной режим»): ISO and shutter by hand are the exposure, the frames move
     * nothing; one by hand stays while the metering moves the other; back to automatic, the metering runs again.
     */
    @Test public void aHandSetIsoAndShutterHold() {
        NativeRawExposure a = ae(10_000_000L, 100);
        assertTrue(a.setManual(800, 20_000_000L));
        assertEquals(20_000_000L, a.exposureNs());
        assertEquals(800, a.iso());
        assertFalse("the same values: nothing to rebuild", a.setManual(800, 20_000_000L));
        assertFalse("no metering", a.update(NativeRawExposure.SETPOINT / 8, 0.1f, 20_000_000L, 800, 0f));
        assertEquals(800, a.iso());

        assertTrue(a.setManual(400, 0)); // ISO by hand: the shutter follows the metering
        assertEquals(400, a.iso());
        a.update(NativeRawExposure.SETPOINT / 4, 0.1f, a.exposureNs(), 400, 0f);
        assertEquals("the hand-set ISO stays", 400, a.iso());

        NativeRawExposure b = ae(10_000_000L, 100);
        assertTrue(b.setManual(0, 5_000_000L)); // shutter by hand: the ISO follows
        assertEquals(5_000_000L, b.exposureNs());
        b.update(NativeRawExposure.SETPOINT / 4, 0.1f, 5_000_000L, b.iso(), 0f);
        assertEquals("the hand-set shutter stays", 5_000_000L, b.exposureNs());
        assertTrue("brighter by the ISO", b.iso() > 100);

        b.setManual(0, 0);
        assertFalse(b.manual());
    }
}
