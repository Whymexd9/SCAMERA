package com.particlesdevs.photoncamera.capture;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * P180 (owner, 2026-10-16: «Изучи и реализуй, учитывай что х300у есть для разных регионов»): native FULLRES profiles of the
 * X100 Ultra and of the X300 Ultra of every region, off until a targeted test, the X200 Ultra's unchanged.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class VivoNativeFullResTest {
    private static final VivoNativeFullRes.Phone X200 = VivoNativeFullRes.Phone.X200_ULTRA,
            X100 = VivoNativeFullRes.Phone.X100_ULTRA, X300 = VivoNativeFullRes.Phone.X300_ULTRA;

    @Test
    public void everyRegionOfTheX300UltraAndTheX100UltraAreKnown() {
        assertEquals(X200, VivoNativeFullRes.phone("vivo", "PD2454", "V2454A", null));
        assertEquals(X100, VivoNativeFullRes.phone("vivo", "PD2366", "V2366GA", null));
        assertEquals(X100, VivoNativeFullRes.phone("vivo", "PD2366", "V2366A", null));
        assertEquals("by its market name", X100, VivoNativeFullRes.phone("vivo", "PDXXXX", "VXXXX", "vivo X100 Ultra"));
        assertEquals("satellite edition", X300, VivoNativeFullRes.phone("vivo", "PD2547", "V2547DA", null));
        assertEquals("China", X300, VivoNativeFullRes.phone("vivo", "PD2547", "V2547A", null));
        assertEquals("global", X300, VivoNativeFullRes.phone("vivo", "V2562", "V2562", null));
        assertEquals(X300, VivoNativeFullRes.phone("vivo", "PD2562", "V2562", null));
        assertEquals("by its market name", X300, VivoNativeFullRes.phone("vivo", "PDXXXX", "VXXXX", "vivo X300 Ultra 卫星通信版"));
        assertNull("X200 Pro", VivoNativeFullRes.phone("vivo", "PD2405", "V2405A", null));
        assertNull("the MediaTek X300", VivoNativeFullRes.phone("vivo", "PD2509", "V2509A", "vivo X300"));
        assertNull("another brand", VivoNativeFullRes.phone("OPPO", "PD2366", "V2366A", null));
        assertNull(VivoNativeFullRes.phone(null, null, null, null));
    }

    @Test
    public void onlyTheX200UltraRunsWithoutItsTestSwitch() {
        assertTrue(VivoNativeFullRes.allowed(X200, false));
        assertFalse(VivoNativeFullRes.allowed(X100, false));
        assertFalse(VivoNativeFullRes.allowed(X300, false));
        assertTrue(VivoNativeFullRes.allowed(X100, true));
        assertTrue(VivoNativeFullRes.allowed(X300, true));
    }

    private static void profile(VivoNativeFullRes.Phone phone, String camera, boolean large, int w, int h, int mode, int block) {
        VivoSdkFullRes.Profile p = VivoNativeFullRes.profile(phone, camera, large, false);
        String what = phone + " camera " + camera + (large ? " 200 MP" : "");
        assertNotNull(what, p);
        assertEquals(what, w, p.size.getWidth());
        assertEquals(what, h, p.size.getHeight());
        assertEquals(what, mode, p.sensorMode);
        assertEquals(what, block, p.nativeBlock);
        // the viewfinder bin and the worker's native merge need whole colour-block pairs
        assertEquals(what, 0, w % (2 * block));
        assertEquals(what, 0, h % (2 * block));
    }

    @Test
    public void theX100UltraTakesItsOwnCamerasAndTableRows() {
        profile(X100, "2", true, 8192, 6144, 0, 2);
        profile(X100, "3", true, 8192, 6144, 0, 2);
        profile(X100, "4", true, 16320, 12288, 0, 4);
        profile(X100, "4", false, 8160, 6144, 1, 2);
        assertNull("the X200 Ultra's tele ID is no camera of it", VivoNativeFullRes.profile(X100, "5", true, false));
        assertNull("mono", VivoNativeFullRes.profile(X100, "6", true, false));
        assertTrue(VivoNativeFullRes.twoSizes(X100, "4"));
        assertFalse(VivoNativeFullRes.twoSizes(X100, "2"));
    }

    @Test
    public void theX300UltraTakesItsOwnCamerasAndTableRows() {
        profile(X300, "3", true, 16320, 12256, 0, 4);
        profile(X300, "3", false, 8160, 6128, 1, 2);
        profile(X300, "4", true, 8192, 6144, 0, 2);
        profile(X300, "5", true, 16320, 12288, 0, 4);
        profile(X300, "5", false, 8160, 6144, 1, 2);
        assertNull("logical", VivoNativeFullRes.profile(X300, "2", true, false));
        assertNull("auxiliary", VivoNativeFullRes.profile(X300, "6", true, false));
        assertTrue(VivoNativeFullRes.twoSizes(X300, "3"));
        assertTrue(VivoNativeFullRes.twoSizes(X300, "5"));
        assertFalse(VivoNativeFullRes.twoSizes(X300, "4"));
    }

    @Test
    public void theX200UltraKeepsItsMeasuredProfiles() {
        profile(X200, "3", true, 8192, 6144, 4, 2);
        profile(X200, "4", true, 8192, 6144, 4, 2);
        assertSame(VivoSdkFullRes.TELE_200, VivoNativeFullRes.profile(X200, "5", true, false));
        assertSame(VivoSdkFullRes.TELE_50, VivoNativeFullRes.profile(X200, "5", false, false));
        assertNull(VivoNativeFullRes.profile(X200, "2", true, false));
        assertTrue(VivoNativeFullRes.twoSizes(X200, "5"));
        assertFalse(VivoNativeFullRes.twoSizes(X200, "3"));
    }

    /** The brief: the X200 Ultra's mode numbers are not copied to the other phones (its main / ultra-wide mode 4). */
    @Test
    public void noModeIsCopiedFromTheX200Ultra() {
        for (VivoNativeFullRes.Phone phone : new VivoNativeFullRes.Phone[]{X100, X300})
            for (String camera : new String[]{"2", "3", "4", "5"})
                for (boolean large : new boolean[]{true, false}) {
                    VivoSdkFullRes.Profile p = VivoNativeFullRes.profile(phone, camera, large, false);
                    if (p != null && p.nativeBlock == 2 && p.size.getWidth() == 8192) assertNotEquals(phone + " " + camera, 4, p.sensorMode);
                }
    }

    @Test
    public void aTestRowReplacesTheTablesWhenItIsAValidRow() {
        assertEquals(0, VivoNativeFullRes.rowOf(-1f, 0));
        assertEquals(11, VivoNativeFullRes.rowOf(11f, 0));
        assertEquals(31, VivoNativeFullRes.rowOf(31f, 0));
        assertEquals(1, VivoNativeFullRes.rowOf(64f, 1));
        assertEquals(1, VivoNativeFullRes.rowOf(2.5f, 1));
        assertEquals(1, VivoNativeFullRes.rowOf(Float.NaN, 1));
    }
}
