package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * P366 (owner, 2026-10-27: «вот такие фото получаю с исз, исправь»): the vivo X200 Ultra's camera 4 in an ISZ module (forceSensorMode 3,
 * colour block 2, a RAW-only session under the vivo SDK lease) at noon by the sea, build 31130 (log-2026-10-27, shots 12:57:50 and
 * 12:57:57): its learned curve (12 points, two clusters) fitted a line of slope -3.08 against its DNG prior's -1.06, the statistic
 * 0.357 / 0.708 went onto it at 0.429 / 0.752 and the photo was green; the HAL's neutral was frozen (0.410 / 0.638 on every result,
 * the identity transform), camera 3 (the same sensor) chose 0.384 / 0.647 for the same scene a moment before. Fixtures from that log.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class MosaicIszNoonTest {
    private MockedStatic<PreferenceKeys> keys;
    /** scam_dev.txt lines of a test (null: no line, the default). */
    private static Boolean slopeGuard, coolKeep;

    @Before public void setUp() {
        MosaicNeutral.resetForTest();
        MosaicTeacher.resetForTest();
        keys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("scamDevSwitch")) {
                final Object key = invocation.getArgument(0);
                if ("mosaic_awb_slope_guard".equals(key) && slopeGuard != null) return slopeGuard;
                if ("mosaic_awb_cool_keep".equals(key) && coolKeep != null) return coolKeep;
            }
            return invocation.callRealMethod();
        });
    }

    @After public void tearDown() {
        slopeGuard = null;
        coolKeep = null;
        keys.close();
        MosaicNeutral.resetForTest();
        MosaicTeacher.resetForTest();
    }

    /** "mosaic white balance curve of camera 4" (L13750): its 12 points {ln r, ln b, count}. */
    private static final float[][] CAMERA_4 = {
            {-0.8244f, -0.4302f, 1}, {-0.7169f, -0.4716f, 1}, {-0.6643f, -0.4961f, 2}, {-0.7819f, -0.4739f, 2},
            {-0.8143f, -0.4666f, 5}, {-0.7289f, -0.5082f, 1}, {-0.7347f, -0.5656f, 10}, {-0.7675f, -0.5165f, 3},
            {-0.6235f, -0.9531f, 5}, {-0.6452f, -0.9422f, 7}, {-0.6410f, -1.0031f, 7}, {-0.6757f, -0.9730f, 1}};

    /** Its prior as logged: "prior 0.996 / 0.249 - 0.390 / 0.671" (the DNG shape anchored at camera 4's PD2454 daylight neutral). */
    private static MosaicNeutral.Prior prior() {
        return new MosaicNeutral.Prior(Math.log(0.996), Math.log(0.249), Math.log(0.390), Math.log(0.671), false);
    }

    private static void curve4() {
        final ArrayList<float[]> pts = MosaicNeutral.curve("4");
        for (float[] p : CAMERA_4) pts.add(new float[]{p[0], p[1], p[2], 0f});
    }

    /** The photo's robust statistic (L13751 "0.359 / 0.676 (sd 0.0001 / 0.0005, conf 1.00, 103236 cells ...)"). */
    private static MosaicNeutral.Stat stat(float r, float b) {
        return new MosaicNeutral.Stat(r, b, 0.0001f, 0.0005f, 103236f, 0.214f, 0.5736f, 103236, 3, true);
    }

    /** Camera 3's logged teacher pairs {m r, m b, ISP r, ISP b} of the day, in order, without the last one (12:57:13). */
    private static final float[][] CAMERA_3 = {
            {0.370f, 0.589f, 0.282f, 0.806f}, {0.389f, 0.570f, 0.396f, 0.579f}, {0.390f, 0.564f, 0.399f, 0.576f},
            {0.380f, 0.572f, 0.391f, 0.583f}, {0.440f, 0.520f, 0.411f, 0.557f}, {0.413f, 0.458f, 0.349f, 0.632f},
            {0.419f, 0.456f, 0.391f, 0.521f}, {0.420f, 0.460f, 0.389f, 0.521f}, {0.415f, 0.653f, 0.432f, 0.616f},
            {0.423f, 0.641f, 0.436f, 0.617f}, {0.417f, 0.651f, 0.436f, 0.616f}, {0.388f, 0.693f, 0.418f, 0.650f},
            {0.956f, 0.441f, 0.444f, 0.669f}, {0.415f, 0.555f, 0.415f, 0.583f}, {0.364f, 0.543f, 0.366f, 0.558f},
            {0.365f, 0.529f, 0.355f, 0.563f}, {0.366f, 0.525f, 0.356f, 0.560f}, {0.344f, 0.507f, 0.360f, 0.528f}};

    /**
     * Camera 3's grid as the log implies it at the ISZ shot: its logged pairs; the unlogged pairs of its session at the same scene
     * (12:56:31-12:57:12, about 30; the log prints every 30th) as 17 pairs at the 12:57:13 statistic 0.354 / 0.713 with the
     * neutral the grid then predicted for it ("predicted before 0.381 / 0.669 (support 16.9 ...)", L13115); then that pair itself
     * (ISP 0.384 / 0.647). Returns the prediction made before it (the logged forward check).
     */
    private static MosaicTeacher.Prediction teachCamera3() {
        for (float[] p : CAMERA_3) MosaicTeacher.observe("3", p[0], p[1], p[2], p[3]);
        for (int i = 0; i < 17; i++) MosaicTeacher.observe("3", 0.354f, 0.713f, 0.381f, 0.669f);
        return MosaicTeacher.observe("3", 0.354f, 0.713f, 0.384f, 0.647f);
    }

    private static double ln(double a, double b) {
        return Math.hypot(Math.log(a), Math.log(b));
    }

    private static double off(float[] n, double r, double b) {
        return ln(n[0] / n[1] / r, n[2] / n[1] / b);
    }

    /** The bug as logged (both switches off: P212f): the line of slope -3.08 and 0.357 / 0.708 -> 0.429 / 0.752 (0.357 / 0.718 -> 0.428 / 0.762). */
    @Test public void theLoggedCurveMadeThePhotoGreen() {
        slopeGuard = false;
        coolKeep = false;
        curve4();
        final MosaicNeutral.Locus l = MosaicNeutral.fit(MosaicNeutral.curve("4"), prior());
        assertEquals("line", l.kind);
        assertEquals(-3.0830, l.a1, 1e-3);
        final MosaicNeutral.Result first = MosaicNeutral.resolve("4", 0.357f, 0.708f, 0, 0f, prior(), "measured");
        assertEquals(first.how, 0.429, first.neutral[0], 0.002);
        assertEquals(first.how, 0.752, first.neutral[2], 0.002);
        final MosaicNeutral.Result second = MosaicNeutral.resolve("4", 0.357f, 0.718f, 0, 0f, prior(), "measured");
        assertEquals(second.how, 0.428, second.neutral[0], 0.002);
        assertEquals(second.how, 0.762, second.neutral[2], 0.002);
    }

    /**
     * P366 (a): the points cover 0.54 ln along the prior (of its 1.37), the line's -3.08 is 2.9 x the prior's: the prior's slope
     * through their weighted centre - the cool statistic keeps its colour temperature (R/G not raised), 0.13 ln from the green.
     */
    @Test public void theSlopeIsHeldToThePrior() {
        curve4();
        final MosaicNeutral.Prior prior = prior();
        assertEquals(0.54, MosaicNeutral.spanAlong(MosaicNeutral.curve("4"), prior), 0.01);
        assertEquals(1.365, prior.span(), 0.005);
        final MosaicNeutral.Locus l = MosaicNeutral.fit(MosaicNeutral.curve("4"), prior);
        assertTrue(l.kind, l.kind.startsWith("DNG slope through the points"));
        assertEquals(prior.slope(), l.a1, 1e-9);
        final MosaicNeutral.Result r = MosaicNeutral.resolve("4", 0.357f, 0.708f, 0, 0f, prior, "measured");
        assertTrue(r.how, r.how.contains("DNG slope through the points"));
        assertTrue("R/G not raised: " + r.how, r.neutral[0] <= 0.357f + 1e-3f);
        assertTrue(r.how, off(r.neutral, 0.357, 0.708) < 0.01);
        assertTrue(r.how, off(r.neutral, 0.429, 0.752) > 0.1);
        // a shallower learned slope stands (OPPO camera 2's daylight -0.62 against its prior's -1.14; the X8U's -0.66 against an sRGB
        // placeholder's -1.58), a steeper one up to 2 x
        assertTrue(MosaicNeutral.inBand(-0.62, new MosaicNeutral.Prior(Math.log(0.657), Math.log(0.357), Math.log(0.378), Math.log(0.671), false)));
        assertTrue(MosaicNeutral.inBand(-0.66, new MosaicNeutral.Prior(0.803, -1.265, 0, 0, false)));
        assertTrue(MosaicNeutral.inBand(-2.0, prior));
        assertFalse(MosaicNeutral.inBand(-3.083, prior));
        // the points of a camera that cover half the prior's span keep their own steep slope (a real locus seen over its range)
        final java.util.ArrayList<float[]> wide = new java.util.ArrayList<>(MosaicNeutral.curve("4"));
        wide.add(new float[]{(float) Math.log(0.80), (float) Math.log(0.20), 3f, 0f});
        assertTrue(MosaicNeutral.spanAlong(wide, prior) >= MosaicNeutral.PRIOR_SPAN_TRUST * prior.span());
        assertFalse(MosaicNeutral.fit(wide, prior).kind, MosaicNeutral.fit(wide, prior).kind.startsWith("DNG slope"));
    }

    /**
     * P366 (review): the slope guard is for a photo's own statistic. A neutral checked against camera 4's curve - a converged HAL
     * neutral on the vivo native route (CURVE_DISTANCE), P315's raw-grey point and estimate distance - is checked against the line its
     * points fit, as before: the lamp neutral 0.504 / 0.377 its ISP chose (09:00) stays on it (the guarded line runs between the
     * daylight and the lamp clusters, 0.18 from it), and the photo's projection carries that distance beside its own.
     */
    @Test public void aNeutralIsCheckedAgainstTheLearnedCurve() {
        curve4();
        final float[] lamp = {0.504f, 1f, 0.377f};
        final float checked = MosaicNeutralResolver.curveDistance("4", lamp, prior());
        final MosaicNeutral.Result photo = MosaicNeutral.resolve("4", 0.504f, 0.377f, 0, 0f, prior(), "measured");
        final MosaicNeutral.Result grey = MosaicNeutral.onLearnedCurve("4", 1f, 1f, prior(), "raw grey");
        assertTrue(photo.how, photo.how.contains("DNG slope through the points"));
        assertTrue("guarded " + photo.distance, photo.distance > MosaicNeutralResolver.CURVE_DISTANCE);
        assertTrue("checked " + checked, checked <= MosaicNeutralResolver.CURVE_DISTANCE);
        assertEquals(checked, photo.learnedDistance, 1e-5f);
        // the same as without the guard (P212f)
        slopeGuard = false;
        assertEquals(checked, MosaicNeutralResolver.curveDistance("4", lamp, prior()), 1e-6f);
        final MosaicNeutral.Result plain = MosaicNeutral.resolve("4", 0.504f, 0.377f, 0, 0f, prior(), "measured");
        assertEquals(plain.distance, plain.learnedDistance, 0f);
        assertEquals(checked, plain.distance, 1e-5f);
        final MosaicNeutral.Result plainGrey = MosaicNeutral.resolve("4", 1f, 1f, 0, 0f, prior(), "raw grey");
        assertEquals(plainGrey.neutral[0], grey.neutral[0], 1e-6f);
        assertEquals(plainGrey.neutral[2], grey.neutral[2], 1e-6f);
    }

    /**
     * P366 (c): camera 4's own grid has nothing near (L13752 "nothing learned near it (14 cells)"); camera 3's - the same sensor -
     * gives 0.386 / 0.634 at weight 0.88 and the photo's neutral lands within 0.03 ln of daylight 0.39 / 0.645 (the frozen HAL's
     * 0.410 / 0.638, camera 3's ISP 0.384 / 0.647), not at the green 0.429 / 0.752; the second shot too.
     */
    @Test public void theTeacherOfTheSameSensorGivesDaylight() {
        curve4();
        final MosaicTeacher.Prediction before = teachCamera3();
        assertNotNull(before);
        assertEquals("the logged forward prediction", 0.381, before.r, 0.002);
        assertEquals(0.669, before.b, 0.002);
        assertEquals(16.9, before.support, 0.5);
        assertNull("camera 4's own grid: nothing", MosaicTeacher.predict("4", 0.359f, 0.676f));
        final MosaicTeacher.Use use = MosaicTeacher.use("4", 0.359f, 0.676f, Collections.singletonList("3"));
        assertNotNull(use);
        assertTrue(use.toString(), use.sibling && use.usable() && "3".equals(use.camera));
        assertEquals(use.toString(), MosaicTeacher.W_SIBLING, use.weight, 1e-9);
        final MosaicNeutral.Result shot = MosaicNeutral.taught("4", stat(0.359f, 0.676f), prior(), use);
        assertTrue(shot.how, off(shot.neutral, 0.39, 0.645) <= 0.03);
        assertTrue(shot.how, off(shot.neutral, 0.429, 0.752) > 0.1);
        assertTrue(shot.how, shot.how.contains("camera 3's (same sensor)"));
        // the second shot (L14465: 0.356 / 0.688)
        final MosaicTeacher.Use next = MosaicTeacher.use("4", 0.356f, 0.688f, Collections.singletonList("3"));
        final MosaicNeutral.Result again = MosaicNeutral.taught("4", stat(0.356f, 0.688f), prior(), next);
        assertTrue(again.how, off(again.neutral, 0.39, 0.645) <= 0.03);
    }

    /** P366: the camera's own grid first (W_OWN); a same-sensor grid only when the own one has too little; none without either. */
    @Test public void theOwnGridComesFirst() {
        assertNull(MosaicTeacher.use("4", 0.359f, 0.676f, Collections.emptyList()));
        teachCamera3();
        for (int i = 0; i < 12; i++) MosaicTeacher.observe("4", 0.359f, 0.676f, 0.39f, 0.64f);
        final MosaicTeacher.Use own = MosaicTeacher.use("4", 0.359f, 0.676f, Collections.singletonList("3"));
        assertFalse(own.toString(), own.sibling);
        assertEquals(MosaicTeacher.W_OWN, own.weight, 1e-9);
        assertEquals(0.39f, own.prediction.r, 1e-3f);
        // a statistic camera 3 has never seen near: whatever there is, without the support a photo takes
        final MosaicTeacher.Use far = MosaicTeacher.use("4", 0.60f, 0.40f, Collections.singletonList("3"));
        assertTrue(far == null || !far.usable());
        // a few pairs: support 2, not usable
        MosaicTeacher.observe("5", 0.50f, 0.50f, 0.52f, 0.48f);
        MosaicTeacher.observe("5", 0.50f, 0.50f, 0.52f, 0.48f);
        assertFalse(MosaicTeacher.use("5", 0.50f, 0.50f, null).usable());
    }

    /**
     * P366: the burst of a RAW-only session whose ISP runs no 3A (the identity, gains R 2.4346 / 2.4365 / 2.4336, B 1.5693 on
     * every result over 0.84 s) is told apart from a running ISP (camera 3's CCM), gains that move, too few results or too short a
     * span, AWB off.
     */
    @Test public void aFrozenIspIsDetected() {
        final ColorSpaceTransform identity = cst(1, 1, 0, 0, 0, 1, 0, 0, 0, 1);
        final ColorSpaceTransform camera3 = cst(128, 153, -19, -6, -17, 175, -30, 0, -55, 184);
        final float[] red = {2.434570f, 2.436523f, 2.433594f};
        final List<CaptureResult> frozen = new ArrayList<>();
        for (int i = 0; i < 14; i++)
            frozen.add(result(13390145104652L + 64_000_000L * i, CaptureResult.CONTROL_AWB_MODE_AUTO, identity, red[i % 3], 1.569336f));
        final String idle = MosaicNeutralResolver.ispIdle(frozen);
        assertNotNull(idle);
        assertTrue(idle, idle.contains("14 results") && idle.contains("2.43"));
        final List<CaptureResult> running = new ArrayList<>(frozen);
        running.set(5, result(13390145104652L + 64_000_000L * 5, CaptureResult.CONTROL_AWB_MODE_AUTO, camera3, 2.5869f, 1.5547f));
        assertNull("a result with the ISP's matrix", MosaicNeutralResolver.ispIdle(running));
        final List<CaptureResult> moving = new ArrayList<>();
        for (int i = 0; i < 14; i++)
            moving.add(result(1_000_000_000L + 64_000_000L * i, CaptureResult.CONTROL_AWB_MODE_AUTO, identity, 2.40f + 0.01f * i, 1.569f));
        assertNull("gains that move", MosaicNeutralResolver.ispIdle(moving));
        assertNull("too few", MosaicNeutralResolver.ispIdle(frozen.subList(0, 3)));
        final List<CaptureResult> quick = new ArrayList<>();
        for (int i = 0; i < 6; i++) quick.add(result(1_000_000_000L + 33_000_000L * i, CaptureResult.CONTROL_AWB_MODE_AUTO, identity, 2.4346f, 1.5693f));
        assertNull("0.17 s", MosaicNeutralResolver.ispIdle(quick));
        final List<CaptureResult> manual = new ArrayList<>(frozen);
        manual.set(0, result(13390145104652L, CaptureResult.CONTROL_AWB_MODE_OFF, identity, 2.4346f, 1.5693f));
        assertNull("AWB off", MosaicNeutralResolver.ispIdle(manual));
        assertNull(MosaicNeutralResolver.ispIdle(null));
    }

    /**
     * P366: X200U cameras 3 and 4 are the same sensor for the teacher (pixel array 4096x3072, 8192x6144 at full resolution, a 9.994 mm
     * wide array - 36 mm x 10.09 / 36.345 and 36 mm x 4.03 / 14.516, the log's 35 mm equivalents - white 1023, Bayer BGGR / RGGB);
     * camera 5 (4080x3072, 9.139 mm) is not.
     */
    @Test public void camerasThreeAndFourAreOneSensor() {
        final CameraCharacteristics c3 = characteristics(4096, 3072, 8192, 6144, 9.9942f, 1023, 3);
        final CameraCharacteristics c4 = characteristics(4096, 3072, 8192, 6144, 9.9942f, 1023, 0);
        final CameraCharacteristics c5 = characteristics(4080, 3072, 16320, 12288, 9.1393f, 1023, 3);
        assertNotNull(MosaicTeacher.sensorKey(c4));
        assertEquals(MosaicTeacher.sensorKey(c3), MosaicTeacher.sensorKey(c4));
        assertFalse(MosaicTeacher.sensorKey(c4).equals(MosaicTeacher.sensorKey(c5)));
        assertNull(MosaicTeacher.sensorKey(mock(CameraCharacteristics.class)));
        // the cameras with a grid whose sensor is camera 4's
        MosaicTeacher.observe("3", 0.35f, 0.70f, 0.38f, 0.65f);
        MosaicTeacher.observe("5", 0.45f, 0.70f, 0.47f, 0.69f);
        final List<String> same = MosaicTeacher.sameSensor("4", c4, id -> "3".equals(id) ? c3 : "5".equals(id) ? c5 : null);
        assertEquals(Collections.singletonList("3"), same);
        assertTrue(MosaicTeacher.sameSensor("4", null, id -> c3).isEmpty());
    }

    /**
     * P366 (b): a light far beyond the extended cool end (0.20 / 1.20 against a locus that starts at 0.40 / 0.70) was held at the
     * end (0.306 / 0.943: its R/G raised by half); now it moves along the locus no more than towards it, and (review) stays within
     * WARM_MAX beyond the end, as the warm end does (0.268 / 1.095; uncapped 0.250 / 1.185). A measurement lying 1 ln beyond the end
     * along the curve (0.157 / 1.989: a sky- or sea-filled frame's statistic, biased cool) is not followed there: WARM_MAX beyond
     * the end at most. A light whose move there is mostly towards the locus is unchanged.
     */
    @Test public void aLightBeyondTheCoolEndKeepsItsColourTemperature() {
        final ArrayList<float[]> pts = MosaicNeutral.curve("cool");
        for (int i = 0; i < 10; i++) {
            final double t = i / 9.0;
            pts.add(new float[]{(float) (Math.log(0.40) + t * (Math.log(0.785) - Math.log(0.40))),
                    (float) (Math.log(0.70) + t * (Math.log(0.329) - Math.log(0.70))), 8f, 0f});
        }
        final MosaicNeutral.Result kept = MosaicNeutral.resolve("cool", 0.20f, 1.20f, 0, 0f, null, "measured");
        assertTrue(kept.how, kept.how.contains("colour temperature kept"));
        assertEquals(kept.how, 0.268, kept.neutral[0], 0.003);
        assertEquals(kept.how, 1.095, kept.neutral[2], 0.005);
        assertEquals(kept.how, MosaicNeutral.WARM_MAX, off(kept.neutral, 0.306, 0.943), 0.005);
        final MosaicNeutral.Result far = MosaicNeutral.resolve("cool", 0.1574f, 1.989f, 0, 0f, null, "measured");
        assertTrue(far.how, far.how.contains("colour temperature kept"));
        assertTrue(far.how, off(far.neutral, 0.306, 0.943) <= MosaicNeutral.WARM_MAX + 0.005);
        assertTrue(far.how, off(far.neutral, 0.1574, 1.989) > 0.7);
        final MosaicNeutral.Result towards = MosaicNeutral.resolve("cool", 0.22f, 0.85f, 0, 0f, null, "measured");
        assertFalse(towards.how, towards.how.contains("colour temperature kept"));
        coolKeep = false;
        final MosaicNeutral.Result clamped = MosaicNeutral.resolve("cool", 0.20f, 1.20f, 0, 0f, null, "measured");
        assertEquals(clamped.how, 0.306, clamped.neutral[0], 0.005);
        assertEquals(clamped.how, 0.943, clamped.neutral[2], 0.005);
        assertEquals(towards.neutral[0], MosaicNeutral.resolve("cool", 0.22f, 0.85f, 0, 0f, null, "measured").neutral[0], 1e-6f);
    }

    private static ColorSpaceTransform cst(int den, int... v) {
        final Rational[] r = new Rational[9];
        for (int i = 0; i < 9; i++) r[i] = new Rational(v[i], den);
        return new ColorSpaceTransform(r);
    }

    private static CaptureResult result(long timestamp, int awbMode, ColorSpaceTransform transform, float red, float blue) {
        final CaptureResult r = mock(CaptureResult.class);
        when(r.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(timestamp);
        when(r.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(awbMode);
        when(r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)).thenReturn(transform);
        when(r.get(CaptureResult.COLOR_CORRECTION_GAINS)).thenReturn(new RggbChannelVector(red, 1f, 1f, blue));
        return r;
    }

    private static CameraCharacteristics characteristics(int w, int h, int maxW, int maxH, float width, int white, int cfa) {
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)).thenReturn(new Size(w, h));
        when(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)).thenReturn(new Size(maxW, maxH));
        when(c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)).thenReturn(new SizeF(width, width * 0.75f));
        when(c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)).thenReturn(white);
        when(c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)).thenReturn(cfa);
        return c;
    }
}
