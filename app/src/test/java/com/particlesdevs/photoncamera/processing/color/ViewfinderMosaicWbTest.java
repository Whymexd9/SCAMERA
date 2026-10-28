package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Rational;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * P370 (B6): the X200 Ultra's ISZ viewfinder (a Quad stream under the vivo SDK lease, the ISP running no 3A: the identity transform,
 * gains frozen at R 2.434 / B 1.569) shows the photo's own neutral - the teacher of the same sensor blended with the locus, measured
 * on the frame (MosaicNeutral.measure2) - not the frozen gains. Results whose gains move (a running ISP) or a plain Bayer stream keep
 * the HAL's gains; the neutral shown moves in ln space past a 0.01 ln dead band.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ViewfinderMosaicWbTest {
    private MockedStatic<PreferenceKeys> keys;
    private static final ColorSpaceTransform IDENTITY = new ColorSpaceTransform(new Rational[]{
            new Rational(1, 1), new Rational(0, 1), new Rational(0, 1), new Rational(0, 1), new Rational(1, 1), new Rational(0, 1),
            new Rational(0, 1), new Rational(0, 1), new Rational(1, 1)});
    private static final int W = 512, H = 384;
    private static final float[] BLACK = {64, 64, 64, 64};

    @Before public void setUp() {
        MosaicNeutral.resetForTest();
        MosaicTeacher.resetForTest();
        keys = mockStatic(PreferenceKeys.class, invocation -> invocation.callRealMethod());
    }

    @After public void tearDown() {
        keys.close();
        MosaicNeutral.resetForTest();
        MosaicTeacher.resetForTest();
    }

    private static CaptureResult result(long timestamp, float red, float blue) {
        final CaptureResult r = mock(CaptureResult.class);
        when(r.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(timestamp);
        when(r.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(CaptureResult.CONTROL_AWB_MODE_AUTO);
        when(r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)).thenReturn(IDENTITY);
        when(r.get(CaptureResult.COLOR_CORRECTION_GAINS)).thenReturn(new RggbChannelVector(red, 1f, 1f, blue));
        return r;
    }

    /** A Quad (block 2) BGGR frame of a grey scene seen with camera neutral r / b (green 1), lit 20 - 70 % of white, noisy. */
    private static ByteBuffer quadFrame(float r, float b) {
        final ByteBuffer buf = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random rnd = new Random(7);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                final int qx = (x >> 1) & 1, qy = (y >> 1) & 1; // the 2x2 colour blocks of BGGR
                final float level = 0.2f + 0.5f * ((x / 64 + y / 64) % 4) / 3f;
                final float gain = qx == 0 && qy == 0 ? b : qx == 1 && qy == 1 ? r : 1f;
                final float v = 64 + (1023 - 64) * level * gain + (float) rnd.nextGaussian() * 2f;
                buf.putShort((y * W + x) * 2, (short) Math.round(Math.max(0, Math.min(1023, v))));
            }
        return buf;
    }

    private static MosaicNeutral.Prior prior() {
        return new MosaicNeutral.Prior(Math.log(0.996), Math.log(0.249), Math.log(0.390), Math.log(0.671), false);
    }

    /** Camera 3 (the same sensor) taught at the scene: statistics 0.36 / 0.68 -> ISP 0.384 / 0.647. */
    private static void teachCamera3(MosaicNeutral.Stat at) {
        for (int i = 0; i < 24; i++) MosaicTeacher.observe("3", at.r, at.b, 0.384f, 0.647f);
    }

    private static ViewfinderMosaicWb.Measure measure(ByteBuffer frame) {
        return new ViewfinderMosaicWb.Measure() {
            @Override public MosaicNeutral.Stat stat() {
                return MosaicNeutral.measure2(frame, W, H, W * 2, 3, BLACK, 1023f, 2, null);
            }
            @Override public MosaicNeutral.Result own() {
                return MosaicNeutral.estimate("4", frame, W, H, W * 2, 3, BLACK, 1023f, 2, prior());
            }
            @Override public float[] hal() {
                return new float[]{0.410f, 1f, 0.638f};
            }
        };
    }

    private static double off(float[] a, float[] b) {
        return Math.hypot(Math.log(a[0] / b[0]), Math.log(a[2] / b[2]));
    }

    /**
     * P370 (review): the viewfinder keeps a sample per result, not the results: the idle test on the samples is ispIdle on the
     * results (frozen, moving gains, a duplicate timestamp, AWB off, another transform, a null result).
     */
    @Test public void theSamplesTellWhatTheResultsTell() {
        final java.util.List<java.util.List<CaptureResult>> cases = new java.util.ArrayList<>();
        final java.util.List<CaptureResult> frozen = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) frozen.add(result(1_000_000_000L + 50_000_000L * i, 2.434f, 1.569f));
        cases.add(frozen);
        final java.util.List<CaptureResult> moving = new java.util.ArrayList<>(frozen);
        moving.add(result(1_500_000_000L, 2.1f, 1.7f));
        cases.add(moving);
        final java.util.List<CaptureResult> dup = new java.util.ArrayList<>(frozen);
        final CaptureResult awbOff = result(1_000_000_000L, 2.434f, 1.569f); // a duplicate timestamp: skipped before its AWB
        when(awbOff.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(CaptureResult.CONTROL_AWB_MODE_OFF);
        dup.add(awbOff);
        dup.add(null);
        cases.add(dup);
        final java.util.List<CaptureResult> off = new java.util.ArrayList<>(frozen);
        final CaptureResult manual = result(1_600_000_000L, 2.434f, 1.569f);
        when(manual.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(CaptureResult.CONTROL_AWB_MODE_OFF);
        off.add(manual);
        cases.add(off);
        final java.util.List<CaptureResult> other = new java.util.ArrayList<>(frozen);
        final CaptureResult matrix = result(1_700_000_000L, 2.434f, 1.569f);
        when(matrix.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)).thenReturn(new ColorSpaceTransform(new int[]{
                200, 128, -62, 128, -10, 128, -17, 128, 172, 128, -27, 128, 8, 128, -70, 128, 191, 128}));
        other.add(matrix);
        cases.add(other);
        for (java.util.List<CaptureResult> c : cases) {
            final java.util.List<MosaicNeutralResolver.IdleSample> samples = new java.util.ArrayList<>();
            for (CaptureResult r : c) samples.add(r == null ? null : MosaicNeutralResolver.IdleSample.of(r));
            assertEquals(MosaicNeutralResolver.ispIdle(c), MosaicNeutralResolver.ispIdleOf(samples));
        }
        assertNotNull(MosaicNeutralResolver.ispIdle(frozen));
        assertNotNull("the duplicate timestamp is skipped", MosaicNeutralResolver.ispIdle(dup));
        assertNull(MosaicNeutralResolver.ispIdle(moving));
        assertNull(MosaicNeutralResolver.ispIdle(off));
        assertNull(MosaicNeutralResolver.ispIdle(other));
    }

    @Test public void anIdleIspShowsThePhotosNeutral() {
        final ByteBuffer frame = quadFrame(0.36f, 0.68f);
        final MosaicNeutral.Stat stat = measure(frame).stat();
        assertNotNull(stat);
        teachCamera3(stat);
        final List<String> same = Collections.singletonList("3");
        // the photo: ScamProcessor.applyMosaicNeutral's idle branch on the same statistic
        final MosaicTeacher.Use use = MosaicTeacher.use("4", stat.r, stat.b, same);
        assertTrue(String.valueOf(use), use != null && use.usable());
        final float[] photo = MosaicNeutral.taught("4", stat, prior(), use).neutral;
        final ViewfinderMosaicWb vf = new ViewfinderMosaicWb();
        float[] shown = null;
        for (int i = 0; i < 40; i++)
            shown = vf.frame("4", 1, result(1_000_000_000L + 33_000_000L * i, 2.434f, 1.569f), true, measure(frame), prior(), same, i * 33L);
        assertNotNull(shown);
        assertTrue("viewfinder " + shown[0] + " / " + shown[2] + " against the photo's " + photo[0] + " / " + photo[2], off(shown, photo) < 0.02);
        // the gains the viewfinder takes
        final float[] g = ViewfinderMosaicWb.gains(shown);
        assertEquals(1f / shown[0], g[0], 1e-6f);
        assertEquals(1f, g[1], 0f);
        // the frozen HAL neutral (1 / gains) is far from it: the viewfinder showed another colour before
        assertTrue(off(shown, new float[]{1f / 2.434f, 1f, 1f / 1.569f}) > 0.05);
    }

    @Test public void aRunningIspOrAPlainStreamKeepsTheHalsGains() {
        final ByteBuffer frame = quadFrame(0.36f, 0.68f);
        teachCamera3(measure(frame).stat());
        final List<String> same = Collections.singletonList("3");
        final ViewfinderMosaicWb vf = new ViewfinderMosaicWb();
        for (int i = 0; i < 40; i++)
            assertNull("moving gains", vf.frame("4", 1, result(1_000_000_000L + 33_000_000L * i, 2.30f + 0.01f * i, 1.569f), true,
                    measure(frame), prior(), same, i * 33L));
        float[] shown = null;
        for (int i = 0; i < 40; i++)
            shown = vf.frame("4", 1, result(5_000_000_000L + 33_000_000L * i, 2.434f, 1.569f), true, measure(frame), prior(), same, i * 33L);
        assertNotNull(shown);
        assertNull("a plain Bayer stream", vf.frame("4", 1, result(9_000_000_000L, 2.434f, 1.569f), false, measure(frame), prior(), same, 0L));
        // and its state is gone: a mosaic stream starts over (no stale neutral at the first frame)
        assertNull(vf.frame("4", 1, result(9_100_000_000L, 2.434f, 1.569f), true, measure(frame), prior(), same, 0L));
    }

    @Test public void theShownNeutralMovesPastADeadBand() {
        final float[] a = {0.40f, 1f, 0.60f};
        assertArrayEquals(a, ViewfinderMosaicWb.smooth(null, a), 1e-6f);
        final float[] near = {(float) (0.40 * Math.exp(0.005)), 1f, 0.60f};
        assertArrayEquals("within 0.01 ln", a, ViewfinderMosaicWb.smooth(a, near), 0f);
        final float[] far = {(float) (0.40 * Math.exp(0.1)), 1f, 0.60f};
        final float[] moved = ViewfinderMosaicWb.smooth(a, far);
        assertEquals(0.40 * Math.exp(0.05), moved[0], 1e-5);
        assertEquals(0.60, moved[2], 1e-6);
    }
}
