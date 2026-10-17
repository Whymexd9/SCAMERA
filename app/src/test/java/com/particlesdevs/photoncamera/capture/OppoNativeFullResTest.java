package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.hardware.camera2.params.ColorSpaceTransform;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P192: the OPPO Find X7 Ultra's native FULLRES modes, measured on PHY110 (2026-10-17) and nowhere else. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class OppoNativeFullResTest {
    @Test public void onlyThePhy110() {
        assertTrue(OppoNativeFullRes.model("OPPO", "PHY110"));
        assertTrue(OppoNativeFullRes.model("oppo", "PHY110"));
        assertFalse("Find X8 Ultra", OppoNativeFullRes.model("OPPO", "PKJ110"));
        assertFalse("Find X9 Ultra", OppoNativeFullRes.model("OPPO", "CPH2841"));
        assertFalse(OppoNativeFullRes.model("vivo", "PHY110"));
        assertFalse(OppoNativeFullRes.model(null, null));
    }

    @Test public void theMeasuredModesOfItsFourRearCameras() {
        assertEquals(35, OppoNativeFullRes.mode("2"));
        assertEquals(16, OppoNativeFullRes.mode("3"));
        assertEquals(19, OppoNativeFullRes.mode("4"));
        assertEquals(17, OppoNativeFullRes.mode("5"));
        assertEquals(-1, OppoNativeFullRes.mode("0"));
        assertEquals(-1, OppoNativeFullRes.mode("1"));
        assertNull(OppoNativeFullRes.profile("1"));
        VivoSdkFullRes.Profile main = OppoNativeFullRes.profile("2");
        assertEquals(8192, main.size.getWidth());
        assertEquals(6144, main.size.getHeight());
        assertEquals("Quad: the own remosaic", 2, main.nativeBlock);
        assertEquals(35, main.sensorMode);
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
}
