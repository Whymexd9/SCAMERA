package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.util.Allocator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * P162: the RAW crop. The vivo X200 Pro / Pro Mini RAW (4096x3072, the image in 4000x3000, exact zeros on the sensor's right
 * and bottom: the left and the bottom of an upright photo) loses its padding; own margins of the upright photo turn to the
 * sensor's sides; the kept area starts on whole colour periods; the lens shading map and the hot pixels follow it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = RawCropTest.HeapAllocator.class,
        instrumentedPackages = "com.particlesdevs.photoncamera.util")
public class RawCropTest {
    /** Native memory as Java direct buffers; no shot arena (the crop falls back to plain buffers). */
    @Implements(Allocator.class)
    public static class HeapAllocator {
        @Implementation protected static void __staticInitializer__() { }
        @Implementation protected static ByteBuffer allocate(int capacity) {
            return ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN);
        }
        @Implementation protected static void free(ByteBuffer buffer) { }
        @Implementation protected static int arenaCreate(long bytes) { return 0; }
    }

    /** A w x h frame around black 64 with noise, the last {@code right} columns and {@code bottom} rows exact zeros. */
    private static ByteBuffer padded(int w, int h, int right, int bottom, long seed) {
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer s = b.asShortBuffer();
        Random r = new Random(seed);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                s.put(y * w + x, (short) (x >= w - right || y >= h - bottom ? 0 : 60 + r.nextInt(9) + (x * 3 + y * 5) % 400));
        return b;
    }

    @Test public void theX200ProPaddingIsFoundOnTheSensorsRightAndBottom() {
        final ByteBuffer raw = padded(4096, 3072, 96, 72, 1);
        assertArrayEquals(new int[]{0, 0, 96, 72}, RawCrop.detectPadding(raw, 4096, 3072, 64f));
        // as on the upright photo (mounted at 90 degrees): the left and the bottom, as in the owner's photo
        assertArrayEquals(new int[]{72, 0, 0, 96}, RawCrop.sensorToPhoto(new int[]{0, 0, 96, 72}, 90));
        final RawCrop.Area area = RawCrop.keep(4096, 3072, new int[]{0, 0, 96, 72});
        assertEquals(new RawCrop.Area(0, 0, 4000, 3000), area);
    }

    @Test public void zerosAreNoPaddingWithoutABlackPedestalOrOverTheWholeFrame() {
        final ByteBuffer raw = padded(512, 384, 16, 8, 2);
        assertArrayEquals("no pedestal: zeros can be signal", new int[4], RawCrop.detectPadding(raw, 512, 384, 0f));
        assertArrayEquals(new int[]{0, 0, 16, 8}, RawCrop.detectPadding(raw, 512, 384, 64f));
        final ByteBuffer dark = ByteBuffer.allocateDirect(512 * 384 * 2);
        assertArrayEquals("a frame of zeros is not padding", new int[4], RawCrop.detectPadding(dark, 512, 384, 64f));
        assertArrayEquals("a band over 1/8 of the side is not padding", new int[4],
                RawCrop.detectPadding(padded(512, 384, 100, 0, 3), 512, 384, 64f));
        // one real sample in the edge column: it stays
        final ByteBuffer one = padded(512, 384, 16, 0, 4);
        one.asShortBuffer().put(100 * 512 + 511, (short) 70);
        assertArrayEquals(new int[4], RawCrop.detectPadding(one, 512, 384, 64f));
    }

    @Test public void photoSidesTurnToTheSensorsByItsMounting() {
        final int[] photo = {1, 2, 3, 4};
        assertArrayEquals(new int[]{1, 2, 3, 4}, RawCrop.photoToSensor(photo, 0));
        // 90: the sensor's left becomes the photo's top, its top the right, its right the bottom, its bottom the left
        assertArrayEquals(new int[]{2, 3, 4, 1}, RawCrop.photoToSensor(photo, 90));
        assertArrayEquals(new int[]{3, 4, 1, 2}, RawCrop.photoToSensor(photo, 180));
        assertArrayEquals(new int[]{4, 1, 2, 3}, RawCrop.photoToSensor(photo, 270));
        for (int o : new int[]{0, 90, 180, 270, -90, 450})
            assertArrayEquals("round trip at " + o, photo, RawCrop.sensorToPhoto(RawCrop.photoToSensor(photo, o), o));
    }

    @Test public void theKeptAreaStartsOnWholeColourPeriodsWithEvenSides() {
        assertNull("nothing to cut", RawCrop.keep(4096, 3072, new int[4]));
        assertEquals(new RawCrop.Area(8, 16, 4096 - 8 - 6, 3072 - 16), RawCrop.keep(4096, 3072, new int[]{5, 9, 5, 0}));
        assertEquals("the larger cut of each side", new RawCrop.Area(40, 0, 4000 - 40, 3000),
                RawCrop.keep(4096, 3072, RawCrop.larger(new int[]{0, 0, 96, 72}, new int[]{40, 0, 0, 10})));
        assertNull("less than half would stay", RawCrop.keep(4096, 3072, new int[]{1100, 0, 1100, 0}));
    }

    @Test public void aShotsFramesAreCutToTheImageAndKeepTheirSamples() throws Exception {
        final int w = 256, h = 192;
        List<ImageFrame> frames = new ArrayList<>();
        List<ShortBuffer> originals = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ImageFrame f = ImageFrame.deferred((RingFrame) null, 0x20, w, w * 2, 0, w * h * 2);
            f.buffer = padded(w, h, 8, 6, 10 + i);
            f.width = w;
            f.height = h;
            originals.add(f.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer());
            frames.add(f);
        }
        // the upright photo's top 16 (mounted at 90: the sensor's left) on top of the padding
        RawCrop.Shot shot = RawCrop.cutShot(frames, 64f, true, new int[]{0, 16, 0, 0}, 90);
        assertArrayEquals(new int[]{0, 0, 8, 6}, shot.padding);
        assertEquals(new RawCrop.Area(16, 0, w - 16 - 8, h - 6), shot.area);
        assertEquals(w, shot.fullWidth);
        for (int i = 0; i < frames.size(); i++) {
            ImageFrame f = frames.get(i);
            assertEquals(w - 24, f.width);
            assertEquals(h - 6, f.height);
            assertEquals((long) f.width * f.height * 2, f.buffer.capacity());
            ShortBuffer out = f.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            for (int y = 0; y < f.height; y += 7)
                for (int x = 0; x < f.width; x += 5)
                    assertEquals(originals.get(i).get(y * w + x + 16), out.get(y * f.width + x));
            for (int y = 0; y < f.height; y++) assertTrue("no padding left", out.get(y * f.width + f.width - 1) != 0);
        }
        // automatic crop off and no margins: the frames stay as they are
        List<ImageFrame> whole = new ArrayList<>();
        ImageFrame f = ImageFrame.deferred((RingFrame) null, 0x20, w, w * 2, 0, w * h * 2);
        f.buffer = padded(w, h, 8, 6, 20);
        f.width = w;
        f.height = h;
        whole.add(f);
        RawCrop.Shot none = RawCrop.cutShot(whole, 64f, false, new int[4], 90);
        assertNull(none.area);
        assertEquals(w, f.width);
    }

    @Test public void theLensShadingMapAndTheHotPixelsFollowTheKeptArea() {
        final int cols = 5, rows = 4, fw = 401, fh = 301;
        float[] map = new float[cols * rows * 4];
        for (int j = 0; j < rows; j++)
            for (int i = 0; i < cols; i++)
                for (int c = 0; c < 4; c++) map[(j * cols + i) * 4 + c] = 1f + i * 0.25f + j * 0.5f + c;   // linear in x and y
        assertArrayEquals("the whole frame keeps the map", map,
                RawCrop.cropGainMap(map, cols, rows, fw, fh, new RawCrop.Area(0, 0, fw, fh)), 1e-6f);
        // the right half of the frame: the map of the right half (linear, so exact)
        RawCrop.Area right = new RawCrop.Area(200, 0, 201, 301);
        float[] out = RawCrop.cropGainMap(map, cols, rows, fw, fh, right);
        for (int j = 0; j < rows; j++)
            for (int i = 0; i < cols; i++) {
                final double x = (200 + (double) i / (cols - 1) * 200) / 400 * (cols - 1);
                assertEquals(1f + x * 0.25f + j * 0.5f, out[(j * cols + i) * 4], 1e-4);
            }
        Point[] hot = {new Point(10, 10), new Point(250, 20), new Point(399, 300)};
        Point[] kept = Parameters.keptHotPixels(hot, new RawCrop.Area(200, 0, 150, 301));
        assertEquals(1, kept.length);
        assertEquals(new Point(50, 20), kept[0]);
    }

    @Test public void theDetectionTextRoundTrips() {
        String text = RawCrop.describe(4096, 3072, new int[]{0, 0, 96, 72}, 90);
        assertArrayEquals(new int[]{4096, 3072, 0, 0, 96, 72, 90}, RawCrop.parse(text));
        assertNull(RawCrop.parse(""));
        assertNull(RawCrop.parse("null"));
        assertNull(RawCrop.parse("1,2,3"));
        assertNotNull(RawCrop.parse("4096,3072,0,0,0,0,270"));
        assertEquals(270, RawCrop.parse("4096,3072,0,0,0,0,270")[6]);
    }

    /**
     * P230 Xiaomi 15 Ultra DCG: the mode's 2560 rows in the 4096 x 3072 buffer (here 320 of 384), the band below cut with the
     * automatic crop off too; the shot's detection carries it for the settings' picture.
     */
    @Test public void aSensorModesEmptyBandIsCut() throws Exception {
        final int w = 512, h = 384;
        List<ImageFrame> frames = new ArrayList<>();
        ImageFrame f = ImageFrame.deferred((RingFrame) null, 0x20, w, w * 2, 0, w * h * 2);
        f.buffer = padded(w, h, 0, 64, 31);
        f.width = w;
        f.height = h;
        frames.add(f);
        RawCrop.Shot shot = RawCrop.cutShot(frames, 64f, false, new int[4], 90, new int[]{0, 0, 0, 64});
        assertEquals(new RawCrop.Area(0, 0, w, h - 64), shot.area);
        assertArrayEquals(new int[]{0, 0, 0, 64}, shot.padding);
        assertEquals("the mode's window is centred on the sensor: the buffer's rows sit half the band lower", 32, shot.sensorShiftY);
        assertEquals(-32, RawCrop.modeShiftY(new int[]{0, 64, 0, 0}));
        assertEquals(0, RawCrop.modeShiftY(new int[4]));
        assertEquals(0, RawCrop.modeShiftY(null));
        assertEquals(h - 64, f.height);
        assertEquals((long) w * (h - 64) * 2, f.buffer.capacity());
        RawCrop.Shot none = RawCrop.cutShot(frames, 64f, false, new int[4], 90, null);
        assertNull("no band: nothing more cut", none.area);
    }
}
