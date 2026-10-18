package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * P230: DCG 14 bit on the Xiaomi 15 Ultra without root (MotionCam community): session operation mode 0x9203, current_mode 24,
 * 60 fps, the 512 empty rows of the 4096 x 3072 buffer cut, 14-bit levels. Tables from the owner's SCAM TEST dump 2026-10-11.
 */
public class XiaomiDcgTest {
    /** Camera 0 / 2 (main) org.quic.camera2.sensormode.info.SensorModeTable, as dumped. */
    static final int[] MAIN = {41, 3, 8192, 6144, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30,
            4096, 3072, 30, 4096, 3072, 30, 8192, 6144, 30, 4096, 2304, 30, 4096, 2304, 30, 4096, 2304, 30, 4096, 2304, 30, 4096, 2304,
            30, 4096, 2304, 30, 4096, 2304, 30, 8192, 4608, 30, 4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 30, 4096,
            2560, 30, 4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 30, 4096, 1840, 30, 4096, 1840, 30, 4096, 1840, 30, 4096, 2560, 60,
            4096, 2560, 60, 4096, 2560, 60, 4096, 2560, 60, 4096, 2560, 60, 7680, 4320, 30, 1920, 1080, 480, 1280, 720, 480, 4080, 2304,
            120, 4096, 3072, 30, 4096, 3072, 30, 4096, 2304, 30, 4096, 2304, 30};
    /** Camera 3 (ultra-wide): 15 modes. */
    static final int[] WIDE = {15, 3, 8192, 6144, 15, 4096, 3072, 30, 4096, 3072, 24, 4096, 3072, 25, 4096, 2304, 30, 4096, 2304, 30,
            4096, 2304, 30, 4096, 2560, 30, 4096, 2560, 30, 4096, 2560, 60, 1920, 1080, 240, 1280, 720, 240, 1440, 1080, 10, 1920, 1080,
            120, 1280, 720, 120};
    /** Camera 4 (3x tele, never opened alone): 14 modes. */
    static final int[] TELE = {14, 3, 8192, 6144, 12, 4096, 3072, 30, 4096, 3072, 12, 4096, 3072, 27, 4096, 3072, 30, 4096, 2304, 30,
            4096, 3072, 12, 4096, 2304, 30, 4096, 2304, 30, 4096, 2560, 30, 4096, 2560, 60, 1920, 1080, 240, 1280, 720, 240, 1920, 1080, 120};
    /** Camera 1 (front): 9 modes. */
    static final int[] FRONT = {9, 3, 6528, 4896, 15, 3264, 2448, 30, 3264, 2448, 30, 3264, 1836, 30, 3264, 1836, 30, 3264, 1836, 60,
            1632, 918, 120, 1632, 1224, 10, 1440, 1080, 20};

    @Test public void theFifteenUltraOfAnyRegion() {
        assertTrue(XiaomiDcg.ultra15("Xiaomi", "25010PN30G", "xuanyuan"));
        assertTrue(XiaomiDcg.ultra15("Xiaomi", "25010PN30C", "xuanyuan"));
        assertTrue("by the device name", XiaomiDcg.ultra15("Xiaomi", "Xiaomi 15 Ultra", "xuanyuan"));
        assertFalse("the 17 Ultra has its own route (P41b)", XiaomiDcg.ultra15("Xiaomi", "25128PNA1C", "nezha"));
        assertFalse(XiaomiDcg.ultra15("vivo", "25010PN30G", "PD2454"));
        assertFalse(XiaomiDcg.ultra15(null, null, null));
    }

    @Test public void theValuesOfThePost() {
        assertEquals(37379, XiaomiDcg.OPERATION_MODE);
        assertEquals("a vendor operation mode Android takes as it is", 0x9203,
                CaptureController.sessionOperationMode(XiaomiDcg.OPERATION_MODE, false));
        assertEquals("never in a FULLRES session", 0, CaptureController.sessionOperationMode(XiaomiDcg.OPERATION_MODE, true));
        assertEquals(24, XiaomiDcg.DCG_MODE);
        assertEquals(23, XiaomiDcg.OP_MODE_ALONE);
        assertEquals(60, XiaomiDcg.FPS);
        assertEquals(14, XiaomiDcg.BITS);
        // without a scam_dev line the defaults are the post's values
        assertEquals(0x9203, XiaomiDcg.operationMode());
        assertEquals(24, XiaomiDcg.dcgMode());
        assertEquals(60, XiaomiDcg.fps());
        assertEquals(SensorModeBand.CROP_EMPTY, SensorModeBand.cropSetting());
    }

    /** The switch only on the main sensor: never on the ultra-wide, the tele 4 (alone it kills the provider) or the front. */
    @Test public void onlyTheMainHasTheDcgMode() {
        assertTrue(XiaomiDcg.mainTable(MAIN, 24));
        assertFalse(XiaomiDcg.mainTable(WIDE, 24));
        assertFalse(XiaomiDcg.mainTable(TELE, 24));
        assertFalse(XiaomiDcg.mainTable(FRONT, 24));
        assertFalse(XiaomiDcg.mainTable(null, 24));
        assertFalse("a 480 fps mode is no DCG mode", XiaomiDcg.mainTable(MAIN, 34));
    }

    @Test public void sixtyFramesASecond() {
        // camera 0's aeAvailableTargetFpsRanges
        int[][] ranges = {{10, 10}, {15, 15}, {24, 24}, {10, 30}, {30, 30}, {10, 60}, {60, 60}};
        assertArrayEquals(new int[]{60, 60}, XiaomiDcg.pickFps(ranges, 60));
        assertArrayEquals("the highest floor ending at 60", new int[]{30, 60},
                XiaomiDcg.pickFps(new int[][]{{10, 30}, {10, 60}, {30, 60}}, 60));
        assertArrayEquals("not listed: asked as it is", new int[]{60, 60}, XiaomiDcg.pickFps(new int[][]{{10, 30}}, 60));
        assertArrayEquals(new int[]{60, 60}, XiaomiDcg.pickFps(null, 60));
        assertNull("fps 0: the viewfinder's own range", XiaomiDcg.pickFps(ranges, 0));
    }

    @Test public void theReportedModeDecidesTheDepth() {
        assertEquals(14, XiaomiDcg.bitsOfReport(24, 24));
        assertEquals("the operation mode's own 14-bit mode", 14, XiaomiDcg.bitsOfReport(23, 24));
        assertEquals("the HAL kept its normal mode: declared levels", 0, XiaomiDcg.bitsOfReport(1, 24));
        assertEquals("a 60 fps mode that is not the DCG one", 0, XiaomiDcg.bitsOfReport(28, 24));
        assertEquals("no report: the session's assumption stays", -1, XiaomiDcg.bitsOfReport(null, 24));
        assertEquals("another mode asked for by scam_dev", 14, XiaomiDcg.bitsOfReport(30, 30));
    }
}
