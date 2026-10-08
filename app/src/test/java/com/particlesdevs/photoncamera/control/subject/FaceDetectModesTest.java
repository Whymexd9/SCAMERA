package com.particlesdevs.photoncamera.control.subject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P42: face detection mode per camera module — only advertised HAL modes are ever written. */
public class FaceDetectModesTest {
    /** OPPO Find X8 Ultra and vivo X200 Ultra: every module lists OFF + SIMPLE (dumpsys media.camera). */
    private static final int[] SIMPLE_ONLY = {0, 1};
    private static final int[] FULL = {0, 1, 2};
    private static final int[] VENDOR = {0, 1, 0x80};
    private static final int[] NONE = {0};

    @Test
    public void autoTakesTheBestAdvertisedMode() {
        assertEquals(FaceDetectModes.HAL_SIMPLE, FaceDetectModes.resolve("auto", SIMPLE_ONLY).halMode);
        assertEquals(FaceDetectModes.HAL_FULL, FaceDetectModes.resolve("auto", FULL).halMode);
        // The vendor value is never chosen on its own.
        assertEquals(FaceDetectModes.HAL_SIMPLE, FaceDetectModes.resolve("auto", VENDOR).halMode);
        assertFalse(FaceDetectModes.resolve("auto", FULL).software);
    }

    @Test
    public void unsupportedChoiceFallsBackToTheBestSupported() {
        assertEquals(FaceDetectModes.HAL_SIMPLE, FaceDetectModes.resolve("full", SIMPLE_ONLY).halMode);
        assertEquals(FaceDetectModes.HAL_SIMPLE, FaceDetectModes.resolve("extended", SIMPLE_ONLY).halMode);
        assertEquals(FaceDetectModes.HAL_FULL, FaceDetectModes.resolve("extended", FULL).halMode);
        assertEquals(FaceDetectModes.HAL_SIMPLE, FaceDetectModes.resolve("simple", FULL).halMode);
    }

    @Test
    public void vendorModeOnlyWhereAdvertised() {
        assertEquals(FaceDetectModes.HAL_EXTENDED, FaceDetectModes.resolve("extended", VENDOR).halMode);
    }

    @Test
    public void modulesWithoutHalFacesUseTheSoftwareDetector() {
        for (String pref : new String[]{"auto", "simple", "full", "extended"}) {
            FaceDetectModes.Resolved r = FaceDetectModes.resolve(pref, NONE);
            assertEquals(pref, FaceDetectModes.HAL_OFF, r.halMode);
            assertTrue(pref, r.software);
            assertTrue(FaceDetectModes.resolve(pref, null).software);
        }
    }

    @Test
    public void offAndSoftware() {
        FaceDetectModes.Resolved off = FaceDetectModes.resolve("off", FULL);
        assertEquals(FaceDetectModes.HAL_OFF, off.halMode);
        assertFalse(off.software);
        assertFalse(off.enabled());
        FaceDetectModes.Resolved soft = FaceDetectModes.resolve("software", FULL);
        assertEquals(FaceDetectModes.HAL_OFF, soft.halMode);
        assertTrue(soft.software);
        assertTrue(soft.enabled());
    }

    @Test
    public void unknownPreferenceIsTheDefault() {
        assertEquals(FaceDetectModes.DEFAULT, FaceDetectModes.normalize(null));
        assertEquals(FaceDetectModes.DEFAULT, FaceDetectModes.normalize("1"));
        assertEquals(FaceDetectModes.HAL_SIMPLE, FaceDetectModes.resolve("garbage", SIMPLE_ONLY).halMode);
        assertEquals(FaceDetectModes.HAL_FULL, FaceDetectModes.bestHal(FULL));
        assertEquals(FaceDetectModes.HAL_OFF, FaceDetectModes.bestHal(NONE));
    }
}
