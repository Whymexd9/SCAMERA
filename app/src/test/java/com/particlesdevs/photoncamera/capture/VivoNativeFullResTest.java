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
        profile(X100, "2", true, 8192, 6144, 11, 2); // P207: row 0 is the hardware remosaic
        profile(X100, "0", true, 8192, 6144, 11, 2); // the listed main: the same sensor's other profile
        profile(X100, "3", true, 8192, 6144, 9, 2); // P210: row 0 is the hardware remosaic
        profile(X100, "4", true, 16320, 12288, 0, 4);
        profile(X100, "4", false, 8160, 6144, 1, 2);
        assertNull("the X200 Ultra's tele ID is no camera of it", VivoNativeFullRes.profile(X100, "5", true, false));
        assertNull("mono", VivoNativeFullRes.profile(X100, "6", true, false));
        assertTrue(VivoNativeFullRes.twoSizes(X100, "4"));
        assertFalse(VivoNativeFullRes.twoSizes(X100, "2"));
    }

    /**
     * P208: the X100 / X300 Ultra's 200 MP sizes keep the viewfinder in the normal mode and take the full-size stream for the
     * stills only (the stock camera's session); their 50 MP sizes and the X200 Ultra's stay held in their mode.
     */
    @Test
    public void the200MpSizesOfTheX100AndX300UltraAreStillOnly() {
        assertTrue(VivoNativeFullRes.profile(X100, "4", true, false).stillOnly);
        assertTrue(VivoNativeFullRes.profile(X300, "3", true, false).stillOnly);
        assertTrue(VivoNativeFullRes.profile(X300, "5", true, false).stillOnly);
        assertFalse(VivoNativeFullRes.profile(X100, "4", false, false).stillOnly);
        assertFalse(VivoNativeFullRes.profile(X100, "2", true, false).stillOnly);
        assertFalse(VivoNativeFullRes.profile(X300, "3", false, false).stillOnly);
        assertFalse(VivoNativeFullRes.profile(X300, "5", false, false).stillOnly);
        assertFalse("the X200 Ultra's tele 200 MP runs in its mode", VivoNativeFullRes.profile(X200, "5", true, false).stillOnly);
        assertFalse("a hardware-remosaic row", VivoNativeFullRes.hardwareProfile(X300, "3", false).stillOnly);
    }

    @Test
    public void theX300UltraTakesItsOwnCamerasAndTableRows() {
        profile(X300, "3", true, 16320, 12256, 0, 4);
        profile(X300, "3", false, 8160, 6128, 3, 2);
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
            for (String camera : new String[]{"0", "2", "3", "4", "5"})
                for (boolean large : new boolean[]{true, false}) {
                    VivoSdkFullRes.Profile p = VivoNativeFullRes.profile(phone, camera, large, false);
                    if (p != null && p.nativeBlock == 2 && p.size.getWidth() == 8192) assertNotEquals(phone + " " + camera, 4, p.sensorMode);
                }
    }

    /**
     * P186 (owner, X300 Ultra: the main camera's 50 / 200 MP and the tele's 200 MP gave no frame or killed the camera, the tele's
     * 50 MP and the ultra-wide worked): each size tries its own table's rows in order, a failed row is passed, a size whose rows
     * all failed is off; a test row of scam_dev.txt always wins.
     */
    @Test
    public void eachSizeTriesItsOwnRowsInTurn() {
        assertArrayEquals("P200: the working row 3 first", new int[]{3, 1}, VivoNativeFullRes.candidates(X300, "3", false));
        assertArrayEquals(new int[]{0}, VivoNativeFullRes.candidates(X300, "3", true));
        assertArrayEquals(new int[]{0, 4}, VivoNativeFullRes.candidates(X300, "4", true));
        assertArrayEquals("P229: row 14 is the tele's hardware-remosaic row (rawr)", new int[]{1, 27}, VivoNativeFullRes.candidates(X300, "5", false));
        assertArrayEquals("P207: not the hardware-remosaic row 0", new int[]{11, 31}, VivoNativeFullRes.candidates(X100, "0", false));
        assertArrayEquals(new int[]{11, 31}, VivoNativeFullRes.candidates(X100, "2", false));
        assertArrayEquals(new int[]{1, 14, 18, 20}, VivoNativeFullRes.candidates(X100, "4", false));
        assertEquals(0, VivoNativeFullRes.candidates(X200, "3", false).length);
        int[] rows = {1, 2, 3};
        assertEquals("first", 1, VivoNativeFullRes.rowFor(rows, 0, -1));
        int tried = VivoNativeFullRes.next(rows, 0, 1);
        assertEquals("row 1 failed: row 2 next", 2, VivoNativeFullRes.rowFor(rows, tried, -1));
        assertEquals("a row not tried now changes nothing", tried, VivoNativeFullRes.next(rows, tried, 14));
        tried = VivoNativeFullRes.next(rows, VivoNativeFullRes.next(rows, tried, 2), 3);
        assertEquals("all failed: the size is off", -1, VivoNativeFullRes.rowFor(rows, tried, -1));
        assertEquals("a test row wins", 7, VivoNativeFullRes.rowFor(rows, tried, 7));
        assertEquals("main50", VivoNativeFullRes.role(X300, "3", false));
        assertEquals("main", VivoNativeFullRes.role(X300, "3", true));
        assertEquals("tele", VivoNativeFullRes.role(X300, "5", true));
        assertEquals("wide", VivoNativeFullRes.role(X100, "3", true));
        assertArrayEquals(new int[]{16320, 12256, 4}, VivoNativeFullRes.size(X300, "3", true));
        assertArrayEquals(new int[]{8160, 6128, 2}, VivoNativeFullRes.size(X300, "3", false));
    }

    /**
     * P194 (owner, 2026-10-17: «FULLRES: аппаратный ремозаик» on the X100 / X300 Ultra): the rows their HAL gave remosaicked in
     * the P186 trials (block 1) are the hardware-remosaic profiles, plain Bayer (nativeBlock 1) at the 50 MP size of that row;
     * the cameras whose rows gave the own Quad have none; nothing on the X200 Ultra (its SDK route) or an unknown phone.
     */
    @Test
    public void theHardwareRemosaicRowsAreTheMainCamerasBayerRows() {
        assertEquals(2, VivoNativeFullRes.hardwareRow(X300, "3"));
        assertEquals(0, VivoNativeFullRes.hardwareRow(X100, "2"));
        assertEquals("the listed main: the same sensor", 0, VivoNativeFullRes.hardwareRow(X100, "0"));
        assertEquals("P229: the tele's Bayer row (rawr)", 14, VivoNativeFullRes.hardwareRow(X300, "5"));
        for (String camera : new String[]{"4", "6"}) assertEquals("X300 camera " + camera, -1, VivoNativeFullRes.hardwareRow(X300, camera));
        assertEquals("P210: the X100 ultra-wide's Bayer row", 0, VivoNativeFullRes.hardwareRow(X100, "3"));
        for (String camera : new String[]{"4", "5"}) assertEquals("X100 camera " + camera, -1, VivoNativeFullRes.hardwareRow(X100, camera));
        for (String camera : new String[]{"2", "3", "4", "5"}) {
            assertEquals("X200 camera " + camera, -1, VivoNativeFullRes.hardwareRow(X200, camera));
            assertEquals("unknown phone", -1, VivoNativeFullRes.hardwareRow(null, camera));
            assertNull(VivoNativeFullRes.hardwareProfile(X200, camera, false));
            assertNull(VivoNativeFullRes.hardwareProfile(null, camera, true));
        }
        VivoSdkFullRes.Profile x300 = VivoNativeFullRes.hardwareProfile(X300, "3", false);
        assertEquals(8160, x300.size.getWidth());
        assertEquals(6128, x300.size.getHeight());
        assertEquals(2, x300.sensorMode);
        assertEquals("plain Bayer", 1, x300.nativeBlock);
        assertTrue(x300.nativeRoute());
        assertTrue(x300.nativeBayer());
        VivoSdkFullRes.Profile x100 = VivoNativeFullRes.hardwareProfile(X100, "2", false);
        assertEquals(8192, x100.size.getWidth());
        assertEquals(6144, x100.size.getHeight());
        assertEquals(0, x100.sensorMode);
        assertEquals(1, x100.nativeBlock);
        // P229: the X300 telephoto's row 14 gives the HAL's remosaic (rawr's colour-block test)
        VivoSdkFullRes.Profile x300tele = VivoNativeFullRes.hardwareProfile(X300, "5", false);
        assertEquals(14, x300tele.sensorMode);
        assertEquals(1, x300tele.nativeBlock);
        assertNull("the X100 telephoto", VivoNativeFullRes.hardwareProfile(X100, "4", false));
        // the viewfinder's Bayer bin keeps one 2 x 2 quad of each 4 x 4: whole 4 x 4 cells
        for (VivoSdkFullRes.Profile p : new VivoSdkFullRes.Profile[]{x300, x100}) {
            assertEquals(0, p.size.getWidth() % 4);
            assertEquals(0, p.size.getHeight() % 4);
        }
        assertFalse("a Quad row is no Bayer", VivoNativeFullRes.profile(X300, "3", false, false).nativeBayer());
        assertTrue(VivoNativeFullRes.profile(X300, "3", false, false).nativeRoute());
        assertFalse("the SDK route", new VivoSdkFullRes.Profile(8192, 6144, 13, 0).nativeRoute());
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
