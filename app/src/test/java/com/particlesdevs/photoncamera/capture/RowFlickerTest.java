package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * P212d (M4 phase B): the lamp flicker from the worker's ultrashort / base row profile, fitted at 100 / 120 Hz over the rolling
 * shutter skew (40.11 ms: 4.0 / 4.8 cycles a frame). The WORKER_* lines are what scam-hybrid.h reports for the synthetic frames of
 * tools/check_hybrid_us_rows.cpp (built for arm64 and run on the PHY110: a 5 % flicker, the scene shifted 1 px or not, no flicker;
 * a two-period +-45 % flicker and a linear ramp on 24 bands).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class RowFlickerTest {
    private static final double SKEW = 40_112_817; // the PHY110's native modes (ns)
    // tools/check_hybrid_us_rows.cpp, "row profile line for the flicker vote" and the 24-band cases above it
    static final String WORKER_FLAT_24 = "HYBRID MOSAIC NATIVE: ultrashort frame 1 row profile 24 bands G 1.0001 1.0001 0.9996 1.0001 0.9999 0.9999 "
            + "0.9999 0.9998 1.0000 0.9996 0.9998 1.0001 1.0001 0.9999 1.0001 1.0000 1.0002 1.0000 1.0000 0.9997 0.9999 "
            + "0.9998 0.9999 1.0001";
    static final String WORKER_RAMP_24 = "HYBRID MOSAIC NATIVE: ultrashort frame 1 row profile 24 bands G 0.9041 0.9123 0.9204 0.9294 0.9375 0.9461 "
            + "0.9540 0.9627 0.9702 0.9792 0.9872 0.9962 1.0042 1.0121 1.0205 1.0292 1.0376 1.0456 1.0545 1.0628 1.0708 "
            + "1.0794 1.0875 1.0961";
    static final String WORKER_TWO_PERIODS_24 = "HYBRID MOSAIC NATIVE: ultrashort frame 1 row profile 24 bands G 1.1650 1.3498 1.4152 1.4405 1.2770 1.1673 "
            + "0.9381 0.6517 0.5782 0.5796 0.6456 0.9386 1.1683 1.2762 1.4388 1.4160 1.3516 1.1664 0.8286 0.7189 0.5806 "
            + "0.5585 0.7272 0.9399";
    static final String WORKER_NONE = "HYBRID MOSAIC NATIVE: ultrashort frame 1 row profile 64 bands G 1.0001 0.9999 1.0001 0.9998 1.0001 1.0001 "
            + "1.0002 1.0003 1.0001 1.0003 0.9999 1.0001 0.9998 0.9999 0.9999 0.9997 0.9999 1.0000 1.0001 0.9999 1.0000 "
            + "0.9999 1.0000 0.9999 1.0004 0.9997 1.0001 1.0000 0.9996 1.0003 1.0001 1.0002 1.0003 1.0002 0.9999 0.9998 "
            + "1.0001 1.0002 0.9999 0.9999 0.9999 1.0003 0.9999 1.0001 1.0000 1.0000 1.0001 0.9996 1.0001 1.0000 1.0000 "
            + "1.0002 1.0000 1.0001 0.9991 0.9998 1.0000 1.0001 0.9997 1.0002 1.0001 1.0007 0.9998 1.0001";
    static final String WORKER_100 = "HYBRID MOSAIC NATIVE: ultrashort frame 1 row profile 64 bands G 1.0310 1.0152 0.9961 0.9773 0.9610 0.9520 "
            + "0.9502 0.9564 0.9701 0.9870 1.0077 1.0256 1.0404 1.0479 1.0495 1.0428 1.0307 1.0130 0.9922 0.9743 0.9614 "
            + "0.9523 0.9508 0.9572 0.9682 0.9891 1.0087 1.0273 1.0386 1.0480 1.0495 1.0425 1.0288 1.0125 0.9942 0.9758 "
            + "0.9582 0.9519 0.9510 0.9569 0.9722 0.9905 1.0107 1.0254 1.0406 1.0483 1.0492 1.0413 1.0298 1.0132 0.9938 "
            + "0.9715 0.9604 0.9516 0.9511 0.9584 0.9719 0.9879 1.0102 1.0286 1.0402 1.0485 1.0485 1.0419";
    static final String WORKER_120 = "HYBRID MOSAIC NATIVE: ultrashort frame 1 row profile 64 bands G 1.0283 1.0113 0.9878 0.9646 0.9526 0.9510 "
            + "0.9608 0.9768 1.0039 1.0243 1.0398 1.0491 1.0474 1.0360 1.0140 0.9898 0.9732 0.9560 0.9505 0.9566 0.9717 "
            + "0.9910 1.0172 1.0369 1.0474 1.0486 1.0379 1.0241 1.0019 0.9770 0.9590 0.9510 0.9531 0.9686 0.9897 1.0112 "
            + "1.0316 1.0461 1.0496 1.0436 1.0289 1.0059 0.9846 0.9649 0.9529 0.9514 0.9617 0.9800 1.0063 1.0266 1.0418 "
            + "1.0494 1.0464 1.0347 1.0111 0.9896 0.9700 0.9547 0.9504 0.9587 0.9745 0.9951 1.0199 1.0389";
    static final String WORKER_100_SHIFT = "HYBRID MOSAIC NATIVE: ultrashort frame 1 row profile 64 bands G 1.0307 1.0109 0.9973 0.9776 0.9617 0.9521 "
            + "0.9510 0.9565 0.9711 0.9840 1.0074 1.0271 1.0418 1.0491 1.0513 1.0418 1.0326 1.0147 0.9919 0.9738 0.9621 "
            + "0.9512 0.9524 0.9585 0.9701 0.9891 1.0092 1.0225 1.0408 1.0488 1.0482 1.0437 1.0323 1.0115 0.9921 0.9759 "
            + "0.9594 0.9518 0.9502 0.9591 0.9736 0.9917 1.0068 1.0267 1.0407 1.0489 1.0483 1.0417 1.0277 1.0142 0.9936 "
            + "0.9740 0.9571 0.9520 0.9518 0.9602 0.9698 0.9890 1.0098 1.0281 1.0396 1.0515 1.0496 1.0401";
    static final String WORKER_120_SHIFT = "HYBRID MOSAIC NATIVE: ultrashort frame 1 row profile 64 bands G 1.0297 1.0046 0.9868 0.9678 0.9531 0.9508 "
            + "0.9592 0.9788 1.0032 1.0190 1.0404 1.0478 1.0454 1.0359 1.0151 0.9908 0.9720 0.9580 0.9507 0.9579 0.9693 "
            + "0.9927 1.0173 1.0381 1.0468 1.0484 1.0392 1.0249 0.9996 0.9754 0.9573 0.9512 0.9561 0.9693 0.9875 1.0078 "
            + "1.0308 1.0465 1.0499 1.0447 1.0251 1.0012 0.9839 0.9636 0.9512 0.9518 0.9604 0.9810 1.0057 1.0220 1.0414 "
            + "1.0497 1.0438 1.0342 1.0131 0.9878 0.9697 0.9549 0.9515 0.9585 0.9711 0.9953 1.0204 1.0400";

    private Locale locale;

    @Before public void setUp() { locale = Locale.getDefault(); MainsFlicker.resetForTest(); }
    @After public void tearDown() { Locale.setDefault(locale); MainsFlicker.resetForTest(); }

    /** A synthetic profile: 64 bands, a flicker of {@code amp} at {@code hz} with phase {@code phase}, a quadratic trend, noise. */
    private static float[] profile(int hz, double amp, double phase, double noise, Random rnd) {
        final float[] g = new float[64];
        for (int k = 0; k < 64; k++) {
            final double c = (k + 0.5) / 64, u = 2 * c - 1;
            final double f = hz == 0 ? 1 : 1 + amp * Math.cos(2 * Math.PI * hz * c * SKEW * 1e-9 + phase);
            g[k] = (float) (f * (1 + 0.04 * u - 0.03 * u * u) * (1 + noise * rnd.nextGaussian()));
        }
        return g;
    }

    private static String line(float[] g) {
        final StringBuilder b = new StringBuilder("HYBRID MOSAIC NATIVE: ultrashort frame 19 row profile " + g.length + " bands G");
        for (float v : g) b.append(Float.isNaN(v) ? " -" : String.format(Locale.ROOT, " %.4f", v));
        return b.toString();
    }

    @Test public void theWorkerLineParses() {
        final float[] g = RowFlicker.parse(WORKER_100);
        assertNotNull(g);
        assertEquals(64, g.length);
        assertEquals(1.0310f, g[0], 1e-6f);
        final float[] holes = RowFlicker.parse("X: ultrashort frame 3 row profile 4 bands G 1.0100 - 0.9900 -");
        assertNotNull(holes);
        assertTrue(Float.isNaN(holes[1]) && Float.isNaN(holes[3]));
        assertNull("a band count that disagrees", RowFlicker.parse("X: ultrashort frame 3 row profile 5 bands G 1.0 1.0"));
        assertNull("the row check's no-profile line",
                RowFlicker.parse("HYBRID MOSAIC NATIVE: ultrashort frame 20 rows against the base: no row profile (178 tiles; 1 of 64 bands with agreeing tiles)"));
        assertNull(RowFlicker.parse(null));
    }

    /** The worker's own lines: 100 / 120 Hz with and without a 1 px shift vote right; no flicker, a ramp, two periods vote nothing. */
    @Test public void theWorkerProfilesOfTheHostCheckVoteRight() {
        assertEquals(100, RowFlicker.vote(Collections.singletonList(WORKER_100), SKEW).hz);
        assertEquals(120, RowFlicker.vote(Collections.singletonList(WORKER_120), SKEW).hz);
        assertEquals(100, RowFlicker.vote(Collections.singletonList(WORKER_100_SHIFT), SKEW).hz);
        assertEquals(120, RowFlicker.vote(Collections.singletonList(WORKER_120_SHIFT), SKEW).hz);
        final RowFlicker.Vote v = RowFlicker.vote(Collections.singletonList(WORKER_100), SKEW);
        assertEquals("the 5 % of the frames", 0.05, v.amp, 0.005);
        assertTrue("ratio " + v.ratio, v.ratio < 0.05);
        assertEquals("texture alone", 0, RowFlicker.vote(Collections.singletonList(WORKER_NONE), SKEW).hz);
        assertEquals("texture alone, 24 bands", 0, RowFlicker.vote(Collections.singletonList(WORKER_FLAT_24), SKEW).hz);
        assertEquals("a row ramp", 0, RowFlicker.vote(Collections.singletonList(WORKER_RAMP_24), SKEW).hz);
        assertEquals("two periods a frame: neither frequency", 0, RowFlicker.vote(Collections.singletonList(WORKER_TWO_PERIODS_24), SKEW).hz);
        assertEquals("one frame of each: no shot vote", 0, RowFlicker.vote(Arrays.asList(WORKER_100, WORKER_120), SKEW).hz);
        assertEquals("two frames alike", 120, RowFlicker.vote(Arrays.asList(WORKER_120, WORKER_120_SHIFT), SKEW).hz);
    }

    /** Synthetic 3 % / 5 % flicker at any phase over a trend and 0.5 % noise, a third of the bands missing: the right frequency. */
    @Test public void syntheticProfilesAtAnyPhaseAreClassified() {
        final Random rnd = new Random(212);
        for (int hz : new int[]{100, 120}) {
            for (double amp : new double[]{0.035, 0.05, 0.12}) {
                for (int i = 0; i < 12; i++) {
                    final float[] g = profile(hz, amp, i * Math.PI / 6, 0.005, rnd);
                    if (i % 2 == 1) for (int k = 0; k < 64; k++) if (rnd.nextInt(3) == 0) g[k] = Float.NaN;
                    final RowFlicker.Vote v = RowFlicker.vote(Collections.singletonList(line(g)), SKEW);
                    assertEquals(hz + " Hz amp " + amp + " phase " + i + ": " + v, hz, v.hz);
                }
            }
        }
    }

    /** No flicker (a trend and noise, or a flicker under 3 %) votes nothing; too few counting bands fit nothing. */
    @Test public void texturesAndFaintProfilesVoteNothing() {
        final Random rnd = new Random(7);
        for (int i = 0; i < 40; i++) {
            assertEquals(0, RowFlicker.vote(Collections.singletonList(line(profile(0, 0, 0, 0.005, rnd))), SKEW).hz);
            assertEquals("1.5 % is under the limit", 0,
                    RowFlicker.vote(Collections.singletonList(line(profile(i % 2 == 0 ? 100 : 120, 0.015, i, 0.002, rnd))), SKEW).hz);
        }
        final float[] sparse = profile(100, 0.05, 0, 0.002, rnd);
        for (int k = 0; k < 64; k++) if (k % 3 != 0) sparse[k] = Float.NaN; // 21 of 64 count: under half
        assertNull(RowFlicker.fit(sparse, SKEW));
        assertEquals(0, RowFlicker.vote(Collections.singletonList(line(sparse)), SKEW).hz);
        assertNull("no skew", RowFlicker.fit(profile(100, 0.05, 0, 0, rnd), 0));
        assertEquals(0, RowFlicker.vote(null, SKEW).hz);
    }

    /** Two shots voting alike latch over the region's guess; a shot without a profile, or without a skew, changes nothing. */
    @Test public void twoAgreeingShotsLatch() {
        Locale.setDefault(new Locale("ru", "RU"));
        assertFalse(MainsFlicker.known());
        RowFlicker.afterMerge(Collections.singletonList(WORKER_120), (long) SKEW);
        assertFalse("one shot", MainsFlicker.known());
        RowFlicker.afterMerge(Collections.<String>emptyList(), (long) SKEW);
        RowFlicker.afterMerge(Collections.singletonList(WORKER_100), null);
        RowFlicker.afterMerge(new ArrayList<>(Collections.singletonList(WORKER_120_SHIFT)), (long) SKEW);
        assertTrue(MainsFlicker.known());
        assertEquals(120, MainsFlicker.hz());
        assertTrue(MainsFlicker.source().startsWith("row profile"));
    }

    @Test public void leastSquaresSolvesAndRefusesASingularSystem() {
        final double[][] x = {{1, 0}, {1, 1}, {1, 2}};
        final double[] b = RowFlicker.leastSquares(x, new double[]{1, 3, 5}, 3);
        assertNotNull(b);
        assertEquals(1, b[0], 1e-9);
        assertEquals(2, b[1], 1e-9);
        final List<double[]> same = Arrays.asList(new double[]{1, 1}, new double[]{2, 2});
        assertNull(RowFlicker.leastSquares(same.toArray(new double[0][]), new double[]{1, 2}, 2));
    }
}
