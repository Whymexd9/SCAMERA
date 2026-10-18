package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.util.Rational;

import com.particlesdevs.photoncamera.api.CameraReflectionApi;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** P192: the OPPO Find X7 Ultra's native FULLRES modes, measured on PHY110 (2026-10-17) and nowhere else. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class OppoNativeFullResTest {
    @Test public void anyOppoAndThePhy110() {
        assertTrue(OppoNativeFullRes.model("OPPO", "PHY110"));
        assertTrue(OppoNativeFullRes.model("oppo", "PHY110"));
        assertFalse("Find X8 Ultra is not the measured model", OppoNativeFullRes.model("OPPO", "PKJ110"));
        assertTrue("but an OPPO phone", OppoNativeFullRes.brand("OPPO"));
        assertFalse(OppoNativeFullRes.brand("vivo"));
        assertFalse(OppoNativeFullRes.brand("OnePlus"));
        assertFalse(OppoNativeFullRes.brand(null));
    }

    @Test public void theMeasuredModesOfThePhy110() {
        assertEquals(35, OppoNativeFullRes.mode("2"));
        assertEquals(16, OppoNativeFullRes.mode("3"));
        assertEquals(19, OppoNativeFullRes.mode("4"));
        assertEquals(17, OppoNativeFullRes.mode("5"));
        assertEquals(-1, OppoNativeFullRes.mode("0"));
        assertEquals(-1, OppoNativeFullRes.mode("1"));
    }

    /** A table of {count, 3, then w, h, fps of each row} with the given full-size rows (8192x6144) among 4096x3072 ones. */
    private static int[] table(int count, int fullFps, int... fullRows) {
        int[] t = new int[2 + count * 3];
        t[0] = count;
        t[1] = 3;
        for (int i = 0; i < count; i++) { t[2 + i * 3] = 4096; t[3 + i * 3] = 3072; t[4 + i * 3] = 30; }
        for (int r : fullRows) { t[2 + r * 3] = 8192; t[3 + r * 3] = 6144; t[4 + r * 3] = fullFps; }
        return t;
    }

    /** The PHY110's tables (SCAM TEST 2026-10-11): the measured modes are the full-size rows after row 0, first in order. */
    @Test public void theCandidatesAreTheFullSizeRowsAfterRowZero() {
        assertArrayEquals("main", new int[]{8192, 6144, 24, 35, 37}, OppoNativeFullRes.fullModes(table(38, 24, 0, 35, 37)));
        assertArrayEquals("ultra-wide", new int[]{8192, 6144, 15, 16}, OppoNativeFullRes.fullModes(table(18, 15, 0, 16)));
        assertArrayEquals("3x", new int[]{8192, 6144, 24, 19}, OppoNativeFullRes.fullModes(table(21, 24, 0, 19)));
        assertArrayEquals("6x", new int[]{8192, 6144, 15, 17, 20}, OppoNativeFullRes.fullModes(table(21, 15, 0, 17, 20)));
        assertNull("only the hardware-remosaic row (the front camera)", OppoNativeFullRes.fullModes(table(9, 24, 0)));
        assertNull(OppoNativeFullRes.fullModes(null));
        assertNull("short", OppoNativeFullRes.fullModes(new int[]{38, 3, 8192}));
    }

    @Test public void anIdentityTransformIsNotTheIspColour() {
        assertTrue(OppoNativeFullRes.identity(new ColorSpaceTransform(new int[]{1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1})));
        assertFalse(OppoNativeFullRes.identity(new ColorSpaceTransform(new int[]{18, 10, -5, 10, -3, 10, -2, 10, 15, 10, -3, 10, 0, 1, -6, 10, 16, 10})));
    }

    @Test public void theOwnNeutralIsSmoothedInTheLogDomain() {
        OppoNativeFullRes.forgetNeutral("t");
        OppoNativeFullRes.measuredNeutral("t", 0.5f, 0.6f);
        assertEquals(0.5f, OppoNativeFullRes.neutral("t")[0], 1e-6f);
        OppoNativeFullRes.measuredNeutral("t", 1.0f, 0.6f);
        assertEquals((float) Math.exp(0.7 * Math.log(0.5) + 0.3 * Math.log(1.0)), OppoNativeFullRes.neutral("t")[0], 1e-5f);
        OppoNativeFullRes.measuredNeutral("t", Float.NaN, 0.6f); // ignored
        assertTrue(OppoNativeFullRes.neutral("t")[0] < 1f);
        OppoNativeFullRes.forgetNeutral("t");
    }

    /** The framework's map (its constructor is hidden in the SDK stubs: rows, then columns). */
    private static LensShadingMap lsm(float[] gains, int cols, int rows) throws Exception {
        final java.lang.reflect.Constructor<LensShadingMap> c = LensShadingMap.class.getDeclaredConstructor(float[].class, int.class, int.class);
        c.setAccessible(true);
        return c.newInstance(gains, rows, cols);
    }

    private static SharedPreferences store(String name, long wallMs) {
        final SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences(name, 0);
        OppoPlainState.useStoreForTest(prefs, "fw", () -> wallMs);
        return prefs;
    }

    /**
     * P212e (W1, M2 VERIFY): a plain session of an earlier process leaves the camera's neutral and shading map in the store, so a
     * process opened straight into FULLRES has a neutral (neutral() non-null) and the plain map (17 x 13, copyGainFactors order).
     */
    @Test public void aPlainSessionOfAnEarlierProcessReachesAColdStart() throws Exception {
        final SharedPreferences prefs = store("oppo_plain_state_fr_test", 1_800_000_000_000L);
        prefs.edit().clear().commit();
        OppoNativeFullRes.resetForTest();
        OppoNativeWb.resetForTest();
        try {
            OppoNativeFullRes.offerForTest("2", new int[]{8192, 6144, 24, 35});
            final CaptureResult plain = mock(CaptureResult.class);
            when(plain.get(CaptureResult.CONTROL_AWB_STATE)).thenReturn(CaptureResult.CONTROL_AWB_STATE_CONVERGED);
            when(plain.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new Rational[]{new Rational(408, 1000), new Rational(1, 1),
                    new Rational(614, 1000)});
            final float[] g = OppoPlainStateTest.map(17, 13, 4.7f, 0.03f);
            when(plain.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)).thenReturn(lsm(g, 17, 13));
            OppoNativeFullRes.rememberPlain("2", plain);
            // a new process, straight into FULLRES
            OppoNativeFullRes.resetForTest();
            store("oppo_plain_state_fr_test", 1_800_000_060_000L);
            assertNull("the camera's native size unknown yet", OppoNativeFullRes.neutral("2"));
            OppoNativeFullRes.offerForTest("2", new int[]{8192, 6144, 24, 35});
            assertArrayEquals(new float[]{0.408f, 1f, 0.614f}, OppoNativeFullRes.neutral("2"), 1e-4f);
            final OppoPlainState.Shading s = OppoNativeFullRes.storedShading("2", null);
            assertEquals(17, s.cols);
            assertEquals(13, s.rows);
            assertArrayEquals(g, s.gains, 0f);
            // the session's white balance starts from it
            OppoNativeFullRes.begin("2", null);
            assertArrayEquals(new float[]{0.408f, 1f, 0.614f}, OppoNativeWb.neutral("2"), 1e-4f);
            OppoNativeFullRes.end();
        } finally {
            OppoNativeFullRes.resetForTest();
            OppoNativeWb.resetForTest();
            OppoPlainState.resetForTest();
        }
    }

    /**
     * P212e (W1, M2 step 5): complete() writes a neutral only when it is a finite white point - nothing for a session with no
     * neutral at all and the 3745 K init AWB signature (the HAL's NaN stays, the photo keeps its own); the held gains of a plain
     * session just before are taken.
     */
    @Test public void completeNeverWritesNan() {
        store("oppo_plain_state_nan_test", 1_800_000_000_000L).edit().clear().commit();
        OppoNativeFullRes.resetForTest();
        OppoNativeWb.resetForTest();
        try (MockedStatic<CameraReflectionApi> api = mockStatic(CameraReflectionApi.class)) {
            api.when(() -> CameraReflectionApi.setCached(any(), any(), any())).thenReturn(true);
            final CaptureResult r = mock(CaptureResult.class);
            when(r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new Rational[]{Rational.NaN, Rational.NaN, Rational.NaN});
            final CaptureResult.Key<float[]> rk = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlRGain", float[].class);
            final CaptureResult.Key<float[]> bk = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlBGain", float[].class);
            final CaptureResult.Key<int[]> ck = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlCCT", int[].class);
            when(r.get(rk)).thenReturn(new float[]{2.225807f});
            when(r.get(bk)).thenReturn(new float[]{2.369458f});
            when(r.get(ck)).thenReturn(new int[]{3745});
            OppoNativeWb.begin("2", 0L, null, -1, null, null);
            assertEquals("", OppoNativeFullRes.complete("2", r));
            api.verify(() -> CameraReflectionApi.setCached(eq(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT), any(), any()), never());
            // the gains a plain session left (v10-camera2-engineer35-rawonly-ae: 2.4490006 / 1.6287329, 5546 K)
            when(r.get(rk)).thenReturn(new float[]{2.4490006f});
            when(r.get(bk)).thenReturn(new float[]{1.6287329f});
            when(r.get(ck)).thenReturn(new int[]{5546});
            OppoNativeWb.begin("2", 0L, null, -1, null, null);
            final String filled = OppoNativeFullRes.complete("2", r);
            assertTrue(filled, filled.contains("neutral 0.408 / 0.614"));
            final ArgumentCaptor<Rational[]> written = ArgumentCaptor.forClass(Rational[].class);
            api.verify(() -> CameraReflectionApi.setCached(eq(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT), written.capture(), eq(r)));
            for (Rational q : written.getValue()) assertTrue(q + " finite", q.floatValue() > 0f && !Float.isInfinite(q.floatValue()));
        } finally {
            OppoNativeFullRes.resetForTest();
            OppoNativeWb.resetForTest();
            OppoPlainState.resetForTest();
        }
    }

    /**
     * P212f (W2 = M3 VERIFY): a hand-set white balance is the own neutral as it is (setNeutral, not the k 0.3 average of
     * measuredNeutral), and complete() writes exactly its inverse gains into the next result - what the photo's AWB-off branch reads.
     */
    @Test public void aHandSetNeutralIsFilledExactly() {
        OppoNativeFullRes.resetForTest();
        OppoNativeWb.resetForTest();
        try (MockedStatic<CameraReflectionApi> api = mockStatic(CameraReflectionApi.class)) {
            api.when(() -> CameraReflectionApi.setCached(any(), any(), any())).thenReturn(true);
            OppoNativeFullRes.measuredNeutral("2", 0.33f, 0.70f); // the session's own estimate so far
            OppoNativeFullRes.setNeutral("2", 0.50f, 0.6667f);    // 3200 K by hand
            assertArrayEquals(new float[]{0.50f, 1f, 0.6667f}, OppoNativeFullRes.neutral("2"), 0f);
            final CaptureResult r = mock(CaptureResult.class);
            when(r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new Rational[]{Rational.NaN, Rational.NaN, Rational.NaN});
            final String filled = OppoNativeFullRes.complete("2", r);
            assertTrue(filled, filled.contains("neutral 0.500 / 0.667"));
            final ArgumentCaptor<android.hardware.camera2.params.RggbChannelVector> gains =
                    ArgumentCaptor.forClass(android.hardware.camera2.params.RggbChannelVector.class);
            api.verify(() -> CameraReflectionApi.setCached(eq(CaptureResult.COLOR_CORRECTION_GAINS), gains.capture(), eq(r)));
            assertEquals(2.0f, gains.getValue().getRed(), 1e-6f);
            assertEquals(1f / 0.6667f, gains.getValue().getBlue(), 1e-5f);
            // not a white point: ignored
            OppoNativeFullRes.setNeutral("2", Float.NaN, 0.6f);
            assertEquals(0.50f, OppoNativeFullRes.neutral("2")[0], 0f);
        } finally {
            OppoNativeFullRes.resetForTest();
            OppoNativeWb.resetForTest();
        }
    }

    /**
     * P212e: a map goes into a result through the raw keys and must read back in its own orientation (the synthesized key's
     * setter gave 13 x 17 for the 17 x 13 map); the size is tried both ways, and a map that never reads back right is taken out.
     */
    @Test public void aMapReadsBackInItsOwnOrientationOrNotAtAll() throws Exception {
        final float[] g = OppoPlainStateTest.map(17, 13, 4.7f, 0f);
        try (MockedStatic<CameraReflectionApi> api = mockStatic(CameraReflectionApi.class)) {
            api.when(() -> CameraReflectionApi.setCached(any(), any(), any())).thenReturn(true);
            final CaptureResult ok = mock(CaptureResult.class);
            when(ok.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)).thenReturn(lsm(g, 13, 17), lsm(g, 17, 13));
            assertTrue(OppoNativeFullRes.putShading(ok, g, 17, 13));
            api.verify(() -> CameraReflectionApi.setCached(any(), eq(new android.util.Size(17, 13)), eq(ok)));
            api.verify(() -> CameraReflectionApi.setCached(any(), eq(new android.util.Size(13, 17)), eq(ok)));
            api.verify(() -> CameraReflectionApi.setCached(any(), isNull(), eq(ok)), never());
            final CaptureResult transposed = mock(CaptureResult.class);
            when(transposed.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)).thenReturn(lsm(g, 13, 17));
            assertFalse(OppoNativeFullRes.putShading(transposed, g, 17, 13));
            api.verify(() -> CameraReflectionApi.setCached(any(), isNull(), eq(transposed)));
            assertFalse("a short map", OppoNativeFullRes.putShading(ok, new float[8], 17, 13));
        }
    }
}
