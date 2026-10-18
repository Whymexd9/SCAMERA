package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
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
        a.split(30_000_000.0); // 30 ms -> three periods, 25 ms
        assertEquals(24_999_999L, a.exposureNs());
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

    /**
     * P212a: with the ISO the mode delivers (the PHY110's native modes: 1600 of the declared 6400) the split never asks above it, and
     * a dark scene takes the frame time instead: 40 ms at 100 Hz, 41.67 ms at 120 Hz (whole flicker periods).
     */
    @Test public void theSplitStopsAtTheDeliveredIsoAndTakesTheFrameTime() {
        NativeRawExposure a = ae(10_000_000L, 100);
        assertFalse("no cap, nothing changes", a.isoCap(Integer.MAX_VALUE));
        a.isoCap(1600);
        assertEquals(1600, a.effMaxIso());
        for (double t : new double[]{1e6, 3e7, 1e8, 6e8, 1e12}) {
            a.split(t);
            assertTrue("ISO " + a.iso() + " for " + t, a.iso() <= 1600);
        }
        a.split(1e12);
        assertEquals(40_000_000L, a.exposureNs());
        assertEquals(1600, a.iso());
        a.flicker(120);
        a.split(1e12);
        assertEquals(41_666_665L, a.exposureNs(), 2);
        assertEquals(1600, a.iso());
        assertEquals(CaptureController.NATIVE_FRAME_NS, a.frameDurationNs());
        // the cap cleared (a frame delivered above it): the declared range again
        assertTrue(a.isoCap(Integer.MAX_VALUE));
        a.split(1e12);
        assertEquals(6400, a.iso());
    }

    /** P212a: a hand-set ISO above what the mode delivers is the cap (ISO 3200 asked: 1600 in the frames anyway). */
    @Test public void aHandSetIsoStopsAtTheCap() {
        NativeRawExposure a = ae(10_000_000L, 100);
        a.isoCap(1600);
        assertTrue(a.setManual(3200, 0));
        assertEquals(1600, a.iso());
        assertFalse("the same hand-set value again: nothing to rebuild", a.setManual(3200, 0));
        NativeRawExposure b = ae(10_000_000L, 100);
        b.setManual(3200, 20_000_000L);
        assertEquals(3200, b.iso());
        assertTrue("a cap learned later stops the hand-set ISO too", b.isoCap(1600));
        assertEquals(1600, b.iso());
    }

    /**
     * P212a: a camera that delivers at most ISO 1600 (OPPO Find X7 Ultra native mode, 3 frames of latency, the frames noisy): with
     * the learned cap in the AE the requests are values it delivers - they are shown and the AE holds at 40 ms ISO 1600 (at most
     * two rebuilds after the cap is learned); without it the request is never shown and rebuilt every few frames (log22: 139
     * rebuilds in 323 s).
     */
    @Test public void aCameraClampingTheIsoNoLongerRebuildsEverySixFrames() {
        final int unwired = clampedCameraRebuilds(false, null);
        final NativeRawExposure[] out = new NativeRawExposure[1];
        final int wired = clampedCameraRebuilds(true, out);
        assertTrue("without the cap: " + unwired, unwired > 40);
        assertTrue("with the cap: " + wired, wired <= 2);
        assertEquals(40_000_000L, out[0].exposureNs());
        assertEquals(1600, out[0].iso());
        assertEquals("the scene needs twice what the mode gives", 1.0, out[0].deficitEv(), 0.25);
        assertTrue("at the limit counts as settled", out[0].settled());
    }

    private static int clampedCameraRebuilds(boolean wired, NativeRawExposure[] out) {
        ExposureLimits.resetForTest();
        final ExposureLimits limits = ExposureLimits.of("2", 8192, 6144, false);
        final NativeRawExposure a = ae(30_000_000L, 100);
        final long[] q = new long[2];
        final CaptureRequest request = mock(CaptureRequest.class);
        when(request.get(CaptureRequest.CONTROL_AE_MODE)).thenReturn(CaptureRequest.CONTROL_AE_MODE_OFF);
        when(request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)).thenAnswer(i -> q[0]);
        when(request.get(CaptureRequest.SENSOR_SENSITIVITY)).thenAnswer(i -> (int) q[1]);
        final long[] r = new long[2];
        final CaptureResult result = mock(CaptureResult.class);
        when(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenAnswer(i -> r[0]);
        when(result.get(CaptureResult.SENSOR_SENSITIVITY)).thenAnswer(i -> (int) r[1]);
        // the scene needs twice the most the mode gives (the frame time at ISO 1600)
        final double scene = NativeRawExposure.SETPOINT / (2.0 * CaptureController.NATIVE_FRAME_NS * 1600 / 100.0);
        final long[] askedNs = new long[600];
        final int[] askedIso = new int[600];
        int rebuilds = 0;
        java.util.Random noise = new java.util.Random(7);
        for (int f = 0; f < 600; f++) {
            askedNs[f] = a.exposureNs();
            askedIso[f] = a.iso();
            final int shown = Math.max(0, f - 3);
            q[0] = askedNs[shown];
            q[1] = askedIso[shown];
            r[0] = askedNs[shown];
            r[1] = Math.min(1600, askedIso[shown]);
            limits.observeManual(request, result);
            boolean changed = wired && a.isoCap(limits.isoCap());
            final float p50 = (float) (scene * r[0] * r[1] / 100.0 * (1 + 0.1 * noise.nextGaussian()));
            changed |= a.update(p50, Math.min(1f, 3 * p50), r[0], (int) r[1], 0f);
            if (changed && f >= 100) rebuilds++;
        }
        if (out != null) out[0] = a;
        return rebuilds;
    }

    /**
     * P212a: the session's one probe above the cap: only after PROBE_AFTER_MS, with the cap known, the exposure short by more than
     * PROBE_DEFICIT_EV and nothing pending; 1.5 times the cap, once.
     */
    @Test public void theProbeAsksOnceAboveTheCapWhenTheExposureIsShort() {
        NativeRawExposure a = ae(10_000_000L, 100);
        assertEquals("no cap known", 0, a.probeIso(60_000L));
        a.isoCap(1600);
        for (int i = 0; i < 30; i++) a.update(1e-4f, 1e-3f, a.exposureNs(), a.iso(), 0f); // dark: up to the cap
        assertEquals(1600, a.iso());
        assertTrue(a.deficitEv() > NativeRawExposure.PROBE_DEFICIT_EV);
        assertEquals("too early in the session", 0, a.probeIso(5_000L));
        assertEquals(2400, a.probeIso(NativeRawExposure.PROBE_AFTER_MS));
        assertEquals("once a session", 0, a.probeIso(60_000L));
        NativeRawExposure b = ae(10_000_000L, 100);
        b.isoCap(1600);
        for (int i = 0; i < 30; i++) b.update(NativeRawExposure.SETPOINT, 0.3f, b.exposureNs(), b.iso(), 0f);
        assertEquals("not short of the meter: no probe", 0, b.probeIso(60_000L));
    }

    /** P212b: settled = nothing pending and SETTLE_FRAMES metered frames that asked for no change - inside the band or at a limit. */
    @Test public void settledCountsQuietFramesInsideTheBandOrAtTheLimit() {
        NativeRawExposure a = ae(10_000_000L, 100);
        assertFalse(a.settled());
        for (int i = 0; i < NativeRawExposure.SETTLE_FRAMES; i++) a.update(NativeRawExposure.SETPOINT, 0.3f, 10_000_000L, 100, 0f);
        assertTrue(a.settled());
        assertTrue(a.update(NativeRawExposure.SETPOINT / 4, 0.1f, 10_000_000L, 100, 0f));
        assertFalse("a change in flight", a.settled());
        NativeRawExposure b = ae(10_000_000L, 100);
        b.isoCap(1600);
        for (int i = 0; i < 40; i++) b.update(1e-4f, 1e-3f, b.exposureNs(), b.iso(), 0f);
        assertEquals(40_000_000L, b.exposureNs());
        assertEquals(1600, b.iso());
        assertTrue("the cap at the frame time, still too dark: as settled as it gets", b.settled());
        NativeRawExposure c = ae(10_000_000L, 100);
        c.setManual(400, 10_000_000L);
        assertTrue("ISO and shutter by hand", c.settled());
    }

    /** P212b: a seed is split as any request (the cap, the flicker) and leaves nothing pending. */
    @Test public void aSeedIsSplitAsARequest() {
        NativeRawExposure a = ae(0L, 0);
        assertEquals("the default start", 30_000_000L, a.exposureNs());
        a.isoCap(1600);
        a.flicker(100);
        a.seed(1e12);
        assertEquals(40_000_000L, a.exposureNs());
        assertEquals(1600, a.iso());
        assertFalse(a.pending());
        a.seed(3_500_000.0); // 3.5 ms ISO 100
        assertEquals(3_500_000L, a.exposureNs());
        assertEquals(100, a.iso());
        a.seed(0);
        assertEquals("none: nothing", 3_500_000L, a.exposureNs());
    }

    /**
     * P212c: a lamp at the highlight threshold (p98 0.75 <-> 0.95 from frame to frame at one exposure, log21 L1295-1406): the P192
     * law flips its direction over and over, the EMA and the hysteresis hold the exposure.
     */
    @Test public void aLampAtTheHighlightThresholdNoLongerHunts() {
        final int legacy = lampFlips(false), smooth = lampFlips(true);
        assertTrue("P192 law: " + legacy + " flips", legacy >= 10);
        assertTrue("smoothed: " + smooth + " flips", smooth <= 1);
    }

    private static int lampFlips(boolean smooth) {
        final NativeRawExposure a = ae(10_000_000L, 100);
        a.smoothing(smooth);
        final double e0 = a.total();
        final long[] askedNs = new long[600];
        final int[] askedIso = new int[600];
        int flips = 0, last = 0;
        for (int f = 0; f < 600; f++) {
            askedNs[f] = a.exposureNs();
            askedIso[f] = a.iso();
            final int shown = Math.max(0, f - 3);
            final double delivered = (double) askedNs[shown] * askedIso[shown] / 100.0, k = delivered / e0;
            final float p50 = (float) Math.min(1.0, 0.10 * k), p98 = (float) Math.min(1.0, ((f & 1) == 0 ? 0.75 : 0.95) * k);
            final double before = a.total();
            if (a.update(p50, p98, askedNs[shown], askedIso[shown], 0f)) {
                final int dir = a.total() > before ? 1 : -1;
                if (last != 0 && dir != last) flips++;
                last = dir;
            }
        }
        return flips;
    }

    /** P212c: far off (3 EV) the smoothed law steps a whole stop-error at once and settles in a few updates. */
    @Test public void theSmoothedLawSettlesFromFarAwayInAFewUpdates() {
        for (double over : new double[]{8.0, 1 / 8.0, 3.0}) {
            final NativeRawExposure a = ae(10_000_000L, 100);
            a.smoothing(true);
            final double scene = NativeRawExposure.SETPOINT * over / a.total(); // median per ISO-100 ns
            int rebuilds = 0;
            final long[] askedNs = new long[200];
            final int[] askedIso = new int[200];
            for (int f = 0; f < 200; f++) {
                askedNs[f] = a.exposureNs();
                askedIso[f] = a.iso();
                final int shown = Math.max(0, f - 3);
                final float p50 = (float) Math.min(1.0, scene * askedNs[shown] * askedIso[shown] / 100.0);
                if (a.update(p50, Math.min(1f, 3 * p50), askedNs[shown], askedIso[shown], 0f)) rebuilds++;
            }
            final double p50 = scene * a.total();
            assertTrue("x" + over + ": " + rebuilds + " updates", rebuilds <= 4);
            assertEquals("x" + over + " ends at the setpoint", 0.0, Math.log(p50 / NativeRawExposure.SETPOINT) / Math.log(2), 0.1);
            assertTrue(a.settled());
        }
    }

    private static ByteBuffer quad(int w, int h, java.util.function.IntBinaryOperator value) {
        ByteBuffer raw = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) raw.putShort((y * w + x) * 2, (short) value.applyAsInt(x, y));
        return raw;
    }

    private static final float[] BLACK = {64, 64, 64, 64};

    /** P212c: a flat frame meters as with the P192 meter (log bins: within 1/64 EV); its peaks are the brightest channel's. */
    @Test public void theNewMeterReadsAFlatFrameAsTheOldOne() {
        int w = 128, h = 96;
        // RGGB Quad (cfa 0, block 2): R 300, G 500, B 200
        ByteBuffer raw = quad(w, h, (x, y) -> {
            int ph = ((y / 2) & 1) * 2 + ((x / 2) & 1);
            return ph == 0 ? 300 : ph == 3 ? 200 : 500;
        });
        float[] old = NativeRawExposure.meter(raw, w, h, w * 2, 0, 2, BLACK, 1023f);
        NativeRawExposure.Metered m = NativeRawExposure.meter2(raw, w, h, w * 2, 0, 2, BLACK, 1023f, new NativeRawExposure.MeterSetup());
        assertNotNull(m);
        assertEquals(old[0], m.p50, old[0] * 0.025f);
        assertEquals(old[1], m.p98, 1.5f / 1024);
        assertEquals(0f, m.clip, 0f);
        assertEquals("flat: the log-mean is the median", m.p50, m.logMean, m.p50 * 0.025f);
        assertFalse(m.shaded);
        // a white point balances the luminance at today's scale: a grey of that white point keeps its luminance
        NativeRawExposure.MeterSetup wb = new NativeRawExposure.MeterSetup();
        float r = (300 - 64) / 959f, g = (500 - 64) / 959f, b = (200 - 64) / 959f;
        wb.neutralR = r / g;
        wb.neutralB = b / g;
        assertEquals(old[0], NativeRawExposure.meter2(raw, w, h, w * 2, 0, 2, BLACK, 1023f, wb).p50, old[0] * 0.025f);
    }

    /** P212c: a Quad block whose G sites alone clip counts as clipped (the block mean hid it: 'p98 0.45' with sites at white). */
    @Test public void aSiteClippedInsideAQuadBlockCountsAsClipped() {
        int w = 128, h = 96; // 32 x 24 cells
        // RGGB Quad; in the cells of the two left columns one G site at white, the block mean still 500
        ByteBuffer raw = quad(w, h, (x, y) -> {
            int ph = ((y / 2) & 1) * 2 + ((x / 2) & 1);
            if (ph == 0) return 300;
            if (ph == 3) return 200;
            if (x / 4 < 2 && ph == 1) return (x & 1) == 0 && (y & 1) == 0 ? 1023 : 326; // (1023 + 3 x 326) / 4 = 500
            return 500;
        });
        float[] old = NativeRawExposure.meter(raw, w, h, w * 2, 0, 2, BLACK, 1023f);
        assertEquals("the P192 meter sees no clipping", (500 - 64) / 959f, old[1], 1.5f / 1024);
        NativeRawExposure.Metered m = NativeRawExposure.meter2(raw, w, h, w * 2, 0, 2, BLACK, 1023f, null);
        assertEquals(2 / 32f, m.clip, 1e-4f);
        assertEquals("clipped cells are peak 1", 1f, m.p98, 1f / 1024);
        assertEquals("the unclipped ones' peak", (500 - 64) / 959f, m.peak, 1.5f / 1024);
        assertTrue(m.clipAfter(1.0) >= m.clip);
        assertEquals(1f, m.clipAfter(2.5), 1e-6f);
    }

    /** P212c: what lies outside the output crop counts nothing; a tap region counts four times. */
    @Test public void theCropAndATapRegionWeighTheCells() {
        int w = 256, h = 192;
        // Bayer RGGB (block 1), flat 0.1 in the centre half, clipped outside it
        ByteBuffer raw = quad(w, h, (x, y) -> x < w / 4 || x >= 3 * w / 4 || y < h / 4 || y >= 3 * h / 4 ? 1023 : 64 + 96);
        NativeRawExposure.Metered whole = NativeRawExposure.meter2(raw, w, h, w * 2, 0, 1, BLACK, 1023f, null);
        assertTrue(whole.clip > 0.5f);
        NativeRawExposure.MeterSetup crop = new NativeRawExposure.MeterSetup();
        crop.left = 0.25f;
        crop.top = 0.25f;
        crop.right = 0.75f;
        crop.bottom = 0.75f;
        NativeRawExposure.Metered cropped = NativeRawExposure.meter2(raw, w, h, w * 2, 0, 1, BLACK, 1023f, crop);
        assertEquals(0f, cropped.clip, 0f);
        assertEquals(96 / 959f, cropped.p50, 96 / 959f * 0.025f);
        // a quarter of the frame bright: the median is the dark rest's; tapped there, the bright part is the median
        ByteBuffer half = quad(w, h, (x, y) -> x < w / 2 && y < h / 2 ? 64 + 400 : 64 + 40);
        NativeRawExposure.MeterSetup plain = new NativeRawExposure.MeterSetup();
        plain.centre = false;
        assertEquals(40 / 959f, NativeRawExposure.meter2(half, w, h, w * 2, 0, 1, BLACK, 1023f, plain).p50, 40 / 959f * 0.025f);
        plain.regions = new float[][]{{0f, 0f, 0.5f, 0.5f}};
        assertEquals(400 / 959f, NativeRawExposure.meter2(half, w, h, w * 2, 0, 1, BLACK, 1023f, plain).p50, 400 / 959f * 0.025f);
        // a bright centre weighs more with the centre weight (its log-mean rises)
        ByteBuffer centre = quad(w, h, (x, y) -> Math.abs(x - w / 2) < w / 6 && Math.abs(y - h / 2) < h / 6 ? 64 + 400 : 64 + 40);
        NativeRawExposure.MeterSetup flat = new NativeRawExposure.MeterSetup();
        flat.centre = false;
        assertTrue(NativeRawExposure.meter2(centre, w, h, w * 2, 0, 1, BLACK, 1023f, new NativeRawExposure.MeterSetup()).logMean
                > NativeRawExposure.meter2(centre, w, h, w * 2, 0, 1, BLACK, 1023f, flat).logMean * 1.1f);
    }

    /**
     * P212c: the shading gains, normalised by their median over the frame, de-vignette the luminance while a flat field keeps
     * today's median (the setpoint stays); the peaks stay raw (the sensor clips, not the corrected image).
     */
    @Test public void theShadingMapDevignettesAtTodaysScale() {
        int w = 256, h = 192, cols = 9, rows = 7;
        float[] gains = new float[4 * cols * rows];
        for (int j = 0; j < rows; j++) for (int i = 0; i < cols; i++) {
            float u = i / (cols - 1f) - 0.5f, v = j / (rows - 1f) - 0.5f;
            float g = 1f + 3f * (u * u + v * v) / 0.5f; // 1 in the centre, 4 in the corners
            for (int ch = 0; ch < 4; ch++) gains[4 * (j * cols + i) + ch] = g;
        }
        // a flat field of 0.2 behind that vignetting (the map's own bilinear gain at each site)
        ByteBuffer raw = quad(w, h, (x, y) -> {
            float fx = (x + 0.5f) / w * (cols - 1), fy = (y + 0.5f) / h * (rows - 1);
            int ix = Math.min(cols - 2, (int) fx), iy = Math.min(rows - 2, (int) fy);
            float ax = fx - ix, ay = fy - iy;
            float g00 = gains[4 * (iy * cols + ix)], g10 = gains[4 * (iy * cols + ix + 1)], g01 = gains[4 * ((iy + 1) * cols + ix)],
                    g11 = gains[4 * ((iy + 1) * cols + ix + 1)];
            float g = (g00 + (g10 - g00) * ax) * (1 - ay) + (g01 + (g11 - g01) * ax) * ay;
            return Math.round(64 + 0.2f * 959f / g);
        });
        float[] old = NativeRawExposure.meter(raw, w, h, w * 2, 0, 1, BLACK, 1023f);
        NativeRawExposure.MeterSetup lsc = new NativeRawExposure.MeterSetup();
        lsc.centre = false;
        lsc.gains = gains;
        lsc.gainCols = cols;
        lsc.gainRows = rows;
        NativeRawExposure.Metered m = NativeRawExposure.meter2(raw, w, h, w * 2, 0, 1, BLACK, 1023f, lsc);
        assertTrue(m.shaded);
        assertEquals("a flat field keeps today's median", old[0], m.p50, old[0] * 0.06f);
        assertEquals("de-vignetted: flat (log-mean = median)", m.p50, m.logMean, m.p50 * 0.06f);
        assertEquals("the peaks are raw", old[1], m.p98, 1.5f / 1024);
        // a corner of the frame meters as its centre once de-vignetted, a quarter as bright without the map
        NativeRawExposure.MeterSetup none = new NativeRawExposure.MeterSetup();
        none.centre = false;
        for (NativeRawExposure.MeterSetup s : new NativeRawExposure.MeterSetup[]{lsc, none}) {
            s.left = 0f; s.top = 0f; s.right = 0.2f; s.bottom = 0.2f;
            final float corner = NativeRawExposure.meter2(raw, w, h, w * 2, 0, 1, BLACK, 1023f, s).p50;
            s.left = 0.4f; s.top = 0.4f; s.right = 0.6f; s.bottom = 0.6f;
            final float centre = NativeRawExposure.meter2(raw, w, h, w * 2, 0, 1, BLACK, 1023f, s).p50;
            if (s == lsc) assertEquals("corrected: corner = centre", centre, corner, centre * 0.08f);
            else assertTrue("raw: the corner " + corner + " is dark against " + centre, corner < centre * 0.6f);
        }
    }

    /** P212c (log only): the clip-budget law steps down 0.3-2 EV on clipped cells, never under today's two-stop floor. */
    @Test public void theClipBudgetLawStepsDownOnClippedCellsAboveTheFloor() {
        int w = 256, h = 192; // 128 x 96 cells of Bayer, 1 % of the metered ones clipped, the median at the setpoint
        final int level = Math.round(64 + NativeRawExposure.SETPOINT * 959f);
        ByteBuffer raw = quad(w, h, (x, y) -> (x / 2) < 3 && (y / 2) < 64 ? 1023 : level);
        NativeRawExposure.Metered m = NativeRawExposure.meter2(raw, w, h, w * 2, 0, 1, BLACK, 1023f, null);
        assertTrue("clip " + m.clip, m.clip > 2 * NativeRawExposure.CLIP_BUDGET);
        double k = NativeRawExposure.clipBudget(m, 0f);
        assertTrue("k " + k, k <= Math.pow(2, -0.3) + 1e-6);
        assertTrue("floor: k " + k, k >= 0.25 - 1e-6);
        assertEquals("the P192 law does not see 1 % of clipped cells", 1.0, NativeRawExposure.multiplier(m.p50, m.p98, 0f), 0.03);
    }
}
