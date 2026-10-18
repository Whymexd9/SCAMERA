package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Random;

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
        assertEquals(XiaomiDcg.CROP_EMPTY, XiaomiDcg.cropSetting());
    }

    @Test public void theModeTable() {
        assertArrayEquals(new int[]{4096, 3072, 30}, XiaomiDcg.modeEntry(MAIN, 1));
        assertArrayEquals(new int[]{4096, 2560, 30}, XiaomiDcg.modeEntry(MAIN, 23));
        assertArrayEquals(new int[]{4096, 2560, 30}, XiaomiDcg.modeEntry(MAIN, 24));
        assertArrayEquals(new int[]{4096, 2560, 60}, XiaomiDcg.modeEntry(MAIN, 28));
        assertArrayEquals(new int[]{4096, 2304, 30}, XiaomiDcg.modeEntry(MAIN, 40));
        assertNull(XiaomiDcg.modeEntry(MAIN, 41));
        assertNull(XiaomiDcg.modeEntry(MAIN, -1));
        assertNull(XiaomiDcg.modeEntry(null, 24));
        assertNull("a cut table", XiaomiDcg.modeEntry(new int[]{41, 3, 4096, 3072, 30}, 24));
        assertNull(XiaomiDcg.modeEntry(new int[]{41, 2, 4096, 3072}, 0));
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

    /** A w x h frame of 14-bit samples around black 1024, the last {@code empty} rows filled with {@code fill}. */
    static ByteBuffer frame(int w, int h, int emptyTop, int emptyBottom, int fill, long seed) {
        ByteBuffer b = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer s = b.asShortBuffer();
        Random r = new Random(seed);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                s.put(y * w + x, (short) (y < emptyTop || y >= h - emptyBottom ? fill : 1000 + r.nextInt(60) + (x * 7 + y * 3) % 9000));
        return b;
    }

    /** The post: «normal 4096 x 3072 frames have 512 empty rows at the bottom» (scaled 1:8 here: 512 x 384, the band 64). */
    @Test public void theEmptyBottomRowsAreCut() {
        final int w = 512, h = 384, modeH = 320;
        assertArrayEquals(new int[]{0, 0, 0, 64}, XiaomiDcg.padding(frame(w, h, 0, 64, 0, 1), w, h, w, modeH, XiaomiDcg.CROP_EMPTY));
        assertArrayEquals("a constant fill other than zero", new int[]{0, 0, 0, 64},
                XiaomiDcg.padding(frame(w, h, 0, 64, 1024, 2), w, h, w, modeH, XiaomiDcg.CROP_EMPTY));
        assertArrayEquals("the band on top instead", new int[]{0, 64, 0, 0},
                XiaomiDcg.padding(frame(w, h, 64, 0, 0, 3), w, h, w, modeH, XiaomiDcg.CROP_EMPTY));
        assertArrayEquals("image in every row: nothing cut", new int[4],
                XiaomiDcg.padding(frame(w, h, 0, 0, 0, 4), w, h, w, modeH, XiaomiDcg.CROP_EMPTY));
        assertArrayEquals("forced: the bottom band unchecked", new int[]{0, 0, 0, 64},
                XiaomiDcg.padding(frame(w, h, 0, 0, 0, 5), w, h, w, modeH, XiaomiDcg.CROP_ALWAYS));
        assertArrayEquals("off", new int[4], XiaomiDcg.padding(frame(w, h, 0, 64, 0, 6), w, h, w, modeH, XiaomiDcg.CROP_OFF));
        assertArrayEquals("the mode fills the frame (mode 1)", new int[4],
                XiaomiDcg.padding(frame(w, h, 0, 64, 0, 7), w, h, w, h, XiaomiDcg.CROP_EMPTY));
        assertArrayEquals("another width: not this buffer's mode", new int[4],
                XiaomiDcg.padding(frame(w, h, 0, 64, 0, 8), w, h, w - 16, modeH, XiaomiDcg.CROP_EMPTY));
        assertArrayEquals("less than half would stay", new int[4],
                XiaomiDcg.padding(frame(w, h, 0, 64, 0, 9), w, h, w, h / 2 - 2, XiaomiDcg.CROP_EMPTY));
        assertArrayEquals("no buffer", new int[4], XiaomiDcg.padding(null, w, h, w, modeH, XiaomiDcg.CROP_EMPTY));
    }

    @Test public void theFullSizeFrameOfTheFifteenUltra() {
        final int w = 4096, h = 3072;
        final int[] mode = XiaomiDcg.modeEntry(MAIN, 24);
        assertArrayEquals(new int[]{0, 0, 0, 512},
                XiaomiDcg.padding(frame(w, h, 0, 512, 0, 11), w, h, mode[0], mode[1], XiaomiDcg.CROP_EMPTY));
        assertArrayEquals("the cut keeps the mode's 4096 x 2560 on whole colour periods",
                new int[]{0, 0, 4096, 2560}, area(com.particlesdevs.photoncamera.processing.RawCrop.keep(w, h, new int[]{0, 0, 0, 512})));
    }

    private static int[] area(com.particlesdevs.photoncamera.processing.RawCrop.Area a) {
        return new int[]{a.x, a.y, a.width, a.height};
    }

    /** «16:9» (Wide169) takes its centred rows out of the mode's 2560, not out of the empty band. */
    @Test public void theImageRowsOfTheBuffer() {
        assertEquals(2560, XiaomiDcg.imageRows(4096, 3072, 4096, 2560, XiaomiDcg.CROP_EMPTY));
        assertEquals(2304, com.particlesdevs.photoncamera.processing.Wide169.rows(4096, 2560));
        assertEquals(128, com.particlesdevs.photoncamera.processing.Wide169.firstRow(4096, 2560));
        assertEquals("no DCG session", 3072, XiaomiDcg.imageRows(4096, 3072, 0, 0, XiaomiDcg.CROP_EMPTY));
        assertEquals("crop off", 3072, XiaomiDcg.imageRows(4096, 3072, 4096, 2560, XiaomiDcg.CROP_OFF));
        assertEquals("another stream", 2304, XiaomiDcg.imageRows(4096, 2304, 4096, 2560, XiaomiDcg.CROP_EMPTY));
        assertEquals("a session without DCG (the switch only)", 3072, XiaomiDcg.imageRows(4096, 3072));
    }

    @Test public void constantRowsOnly() {
        ShortBuffer s = frame(64, 8, 0, 2, 0, 12).asShortBuffer();
        assertTrue(XiaomiDcg.constantRows(s, 64, 6, 2));
        assertFalse(XiaomiDcg.constantRows(s, 64, 5, 2));
        s.put(7 * 64 + 63, (short) 1);
        assertFalse("one sample off the fill", XiaomiDcg.constantRows(s, 64, 6, 2));
    }
}
