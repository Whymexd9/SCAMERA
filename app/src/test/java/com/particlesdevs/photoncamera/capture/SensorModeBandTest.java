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
 * P230: a DCG sensor mode shorter than its RAW buffer (Xiaomi 15 Ultra 4096 x 2560, vivo X200 Ultra 4096 x 2632, both in
 * 4096 x 3072): the mode from the SensorModeTable, the empty band cut when it is one, the 16:9 rows taken from the mode's.
 * Tables from the owner's dumps (15U SCAM TEST 2026-10-11, PD2454 dumpsys 2026-10-13).
 */
public class SensorModeBandTest {
    static final int[] MAIN_15U = XiaomiDcgTest.MAIN;
    /** PD2454 camera 3 (main IMX06C): 25 modes; 8-12 are 4096 x 2632 (10 / 11 at 60 fps), 12 the 14-bit DCG LOG mode. */
    static final int[] MAIN_X200U = {25, 3, 8192, 6144, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 8192, 6144, 30, 4096, 2304,
            60, 4096, 3072, 30, 4096, 3072, 30, 4096, 2632, 30, 4096, 2632, 30, 4096, 2632, 60, 4096, 2632, 60, 4096, 2632, 30, 8192, 6144,
            30, 3456, 1944, 120, 1920, 1080, 240, 3840, 2160, 120, 2048, 1536, 30, 2432, 1824, 30, 4096, 3072, 30, 4096, 3072, 60, 2048,
            1536, 120, 3072, 3072, 60, 1280, 768, 481, 4096, 3072, 30};
    /** PD2454 camera 5 (tele HP9), first 13 of its 39 modes: 4080 x 2616 only at 30 fps, 4080 x 2296 at 60. */
    static final int[] TELE_X200U = {13, 3, 16320, 12288, 7, 8160, 6144, 24, 4080, 3072, 30, 4048, 3040, 30, 4080, 3072, 30, 4080, 3072,
            30, 4080, 3072, 30, 4080, 3072, 30, 4080, 3072, 60, 4080, 2616, 30, 4080, 2616, 30, 3840, 2160, 120, 4080, 2296, 60};

    @Test public void theModeTable() {
        assertArrayEquals(new int[]{4096, 3072, 30}, SensorModeBand.modeEntry(MAIN_15U, 1));
        assertArrayEquals(new int[]{4096, 2560, 30}, SensorModeBand.modeEntry(MAIN_15U, 23));
        assertArrayEquals(new int[]{4096, 2560, 30}, SensorModeBand.modeEntry(MAIN_15U, 24));
        assertArrayEquals(new int[]{4096, 2560, 60}, SensorModeBand.modeEntry(MAIN_15U, 28));
        assertArrayEquals(new int[]{4096, 2304, 30}, SensorModeBand.modeEntry(MAIN_15U, 40));
        assertNull(SensorModeBand.modeEntry(MAIN_15U, 41));
        assertNull(SensorModeBand.modeEntry(MAIN_15U, -1));
        assertNull(SensorModeBand.modeEntry(null, 24));
        assertNull("a cut table", SensorModeBand.modeEntry(new int[]{41, 3, 4096, 3072, 30}, 24));
        assertNull(SensorModeBand.modeEntry(new int[]{41, 2, 4096, 3072}, 0));
        // the X200 Ultra's main: the DCG modes of the stock selector's log
        assertArrayEquals("DCGHDR60_1_4", new int[]{4096, 2632, 60}, SensorModeBand.modeEntry(MAIN_X200U, 11));
        assertArrayEquals("DCGHDR30_LOG (RAW14)", new int[]{4096, 2632, 30}, SensorModeBand.modeEntry(MAIN_X200U, 12));
        assertArrayEquals("PHOTO", new int[]{4096, 3072, 30}, SensorModeBand.modeEntry(MAIN_X200U, 1));
    }

    /** A w x h frame of 14-bit samples around black 1024, the first {@code emptyTop} / last {@code emptyBottom} rows {@code fill}. */
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
        assertArrayEquals(new int[]{0, 0, 0, 64}, SensorModeBand.padding(frame(w, h, 0, 64, 0, 1), w, h, w, modeH, SensorModeBand.CROP_EMPTY));
        assertArrayEquals("a constant fill other than zero", new int[]{0, 0, 0, 64},
                SensorModeBand.padding(frame(w, h, 0, 64, 1024, 2), w, h, w, modeH, SensorModeBand.CROP_EMPTY));
        assertArrayEquals("the band on top instead", new int[]{0, 64, 0, 0},
                SensorModeBand.padding(frame(w, h, 64, 0, 0, 3), w, h, w, modeH, SensorModeBand.CROP_EMPTY));
        assertArrayEquals("image in every row: nothing cut", new int[4],
                SensorModeBand.padding(frame(w, h, 0, 0, 0, 4), w, h, w, modeH, SensorModeBand.CROP_EMPTY));
        assertArrayEquals("forced: the bottom band unchecked", new int[]{0, 0, 0, 64},
                SensorModeBand.padding(frame(w, h, 0, 0, 0, 5), w, h, w, modeH, SensorModeBand.CROP_ALWAYS));
        assertArrayEquals("off", new int[4], SensorModeBand.padding(frame(w, h, 0, 64, 0, 6), w, h, w, modeH, SensorModeBand.CROP_OFF));
        assertArrayEquals("the mode fills the frame (mode 1)", new int[4],
                SensorModeBand.padding(frame(w, h, 0, 64, 0, 7), w, h, w, h, SensorModeBand.CROP_EMPTY));
        assertArrayEquals("another width: not this buffer's mode", new int[4],
                SensorModeBand.padding(frame(w, h, 0, 64, 0, 8), w, h, w - 16, modeH, SensorModeBand.CROP_EMPTY));
        assertArrayEquals("less than half would stay", new int[4],
                SensorModeBand.padding(frame(w, h, 0, 64, 0, 9), w, h, w, h / 2 - 2, SensorModeBand.CROP_EMPTY));
        assertArrayEquals("no buffer", new int[4], SensorModeBand.padding(null, w, h, w, modeH, SensorModeBand.CROP_EMPTY));
    }

    @Test public void theFullSizeFramesOfBothPhones() {
        final int w = 4096, h = 3072;
        final int[] mode15 = SensorModeBand.modeEntry(MAIN_15U, 24);
        assertArrayEquals(new int[]{0, 0, 0, 512},
                SensorModeBand.padding(frame(w, h, 0, 512, 0, 11), w, h, mode15[0], mode15[1], SensorModeBand.CROP_EMPTY));
        assertArrayEquals("the cut keeps the mode's 4096 x 2560 on whole colour periods",
                new int[]{0, 0, 4096, 2560}, area(com.particlesdevs.photoncamera.processing.RawCrop.keep(w, h, new int[]{0, 0, 0, 512})));
        final int[] modeX200 = SensorModeBand.modeEntry(MAIN_X200U, 11);
        assertArrayEquals("X200 Ultra: 440 rows under 4096 x 2632", new int[]{0, 0, 0, 440},
                SensorModeBand.padding(frame(w, h, 0, 440, 0, 12), w, h, modeX200[0], modeX200[1], SensorModeBand.CROP_EMPTY));
        assertArrayEquals(new int[]{0, 0, 4096, 2632}, area(com.particlesdevs.photoncamera.processing.RawCrop.keep(w, h, new int[]{0, 0, 0, 440})));
    }

    private static int[] area(com.particlesdevs.photoncamera.processing.RawCrop.Area a) {
        return new int[]{a.x, a.y, a.width, a.height};
    }

    /** «16:9» (Wide169) takes its centred rows out of the mode's 2560, not out of the empty band. */
    @Test public void theImageRowsOfTheBuffer() {
        assertEquals(2560, SensorModeBand.imageRows(4096, 3072, 4096, 2560, SensorModeBand.CROP_EMPTY));
        assertEquals(2304, com.particlesdevs.photoncamera.processing.Wide169.rows(4096, 2560));
        assertEquals(128, com.particlesdevs.photoncamera.processing.Wide169.firstRow(4096, 2560));
        assertEquals("no DCG session", 3072, SensorModeBand.imageRows(4096, 3072, 0, 0, SensorModeBand.CROP_EMPTY));
        assertEquals("crop off", 3072, SensorModeBand.imageRows(4096, 3072, 4096, 2560, SensorModeBand.CROP_OFF));
        assertEquals("another stream", 2304, SensorModeBand.imageRows(4096, 2304, 4096, 2560, SensorModeBand.CROP_EMPTY));
        SensorModeBand.sessionEnd();
        assertFalse(SensorModeBand.sessionActive());
        assertEquals("no DCG session running", 3072, SensorModeBand.imageRows(4096, 3072));
    }

    @Test public void constantRowsOnly() {
        ShortBuffer s = frame(64, 8, 0, 2, 0, 12).asShortBuffer();
        assertTrue(SensorModeBand.constantRows(s, 64, 6, 2));
        assertFalse(SensorModeBand.constantRows(s, 64, 5, 2));
        s.put(7 * 64 + 63, (short) 1);
        assertFalse("one sample off the fill", SensorModeBand.constantRows(s, 64, 6, 2));
    }

    /**
     * P230 X200 Ultra Telegram route: offered where the table has a 16:10 4096-wide mode at 60 fps - the main (10 / 11) and the
     * ultra-wide - not the tele (4080 x 2616 at 30 only) nor the 15U's ultra-wide table's 4:3 / 16:9 modes.
     */
    @Test public void theX200UltraTelegramRouteNeedsA60FpsDcgWindow() {
        assertTrue(VivoQcomModes.dcg60Table(MAIN_X200U));
        assertFalse(VivoQcomModes.dcg60Table(TELE_X200U));
        assertFalse(VivoQcomModes.dcg60Table(null));
        assertFalse("4:3 at 60 is no DCG window", VivoQcomModes.dcg60Table(new int[]{2, 3, 4096, 3072, 60, 3072, 3072, 60}));
        assertEquals("org.telegram.messenger", VivoQcomModes.TELEGRAM);
        assertEquals(60, VivoQcomModes.DCG_FPS);
    }
}
