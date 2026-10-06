package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.app.Application;

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
 * P27 any resolution: RawBin is the last resort only, for a stream beyond the hard limits (sensor-grid RGB above 2 GB, a side above
 * the GPU's limit). Below them nothing is touched; above them same-colour sites are averaged and the colour order is kept.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = RawBinTest.HeapAllocator.class,
        instrumentedPackages = "com.particlesdevs.photoncamera.util")
public class RawBinTest {
    /** Native memory as Java direct buffers (the binning itself is plain Java). */
    @Implements(Allocator.class)
    public static class HeapAllocator {
        @Implementation protected static void __staticInitializer__() { }
        @Implementation protected static ByteBuffer allocate(int capacity) { return ByteBuffer.allocateDirect(capacity); }
        @Implementation protected static void free(ByteBuffer buffer) { }
    }

    /** Colour phase of site (x, y) of a block-b colour mosaic (1 = Bayer, 2 = Quad, 4 = Tetra): ((y / b) & 1) << 1 | (x / b) & 1. */
    private static int phase(int x, int y, int block) {
        return (((y / block) & 1) << 1) | ((x / block) & 1);
    }

    /** A plane whose value names the colour phase (1000 x phase) plus a position term below 1000, so means are checked exactly. */
    private static ShortBuffer plane(int w, int h, int block) {
        ShortBuffer s = ShortBuffer.allocate(w * h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            s.put(y * w + x, (short) (1000 * phase(x, y, block) + 64 + (x * 7 + y * 13) % 97));
        return s;
    }

    private static int at(ShortBuffer s, int w, int x, int y) { return s.get(y * w + x) & 0xFFFF; }

    @Test public void bayerKeepsTheColourOrderAndAveragesTheFourSameColourSitesOfItsCell() {
        final int w = 16, h = 12;
        int[] out = RawBin.outputSize(w, h, 1);
        assertArrayEquals(new int[]{8, 6}, out);
        ShortBuffer in = plane(w, h, 1), binned = ShortBuffer.allocate(out[0] * out[1]);
        RawBin.bin(in, w, h, 1, binned, out[0], out[1]);
        for (int y = 0; y < out[1]; y++) for (int x = 0; x < out[0]; x++) {
            final int px = x & 1, py = y & 1, x0 = 4 * (x >> 1) + px, y0 = 4 * (y >> 1) + py;
            final int sum = at(in, w, x0, y0) + at(in, w, x0 + 2, y0) + at(in, w, x0, y0 + 2) + at(in, w, x0 + 2, y0 + 2);
            for (int[] s : new int[][]{{x0, y0}, {x0 + 2, y0}, {x0, y0 + 2}, {x0 + 2, y0 + 2}})
                assertEquals("source sites share one colour", phase(x, y, 1), phase(s[0], s[1], 1));
            final int v = at(binned, out[0], x, y);
            assertEquals("site " + x + "," + y, (sum + 2) >> 2, v);
            assertEquals("colour order kept at " + x + "," + y, phase(x, y, 1), v / 1000);
        }
    }

    @Test public void quadBecomesPlainBayerOfTheSameColourOrder() {
        final int w = 16, h = 12;
        int[] out = RawBin.outputSize(w, h, 2);
        assertArrayEquals(new int[]{8, 6}, out);
        ShortBuffer in = plane(w, h, 2), binned = ShortBuffer.allocate(out[0] * out[1]);
        RawBin.bin(in, w, h, 2, binned, out[0], out[1]);
        for (int y = 0; y < out[1]; y++) for (int x = 0; x < out[0]; x++) {
            final int sum = at(in, w, 2 * x, 2 * y) + at(in, w, 2 * x + 1, 2 * y) + at(in, w, 2 * x, 2 * y + 1) + at(in, w, 2 * x + 1, 2 * y + 1);
            final int v = at(binned, out[0], x, y);
            assertEquals((sum + 2) >> 2, v);
            assertEquals("Quad block colour becomes the Bayer site colour", phase(x, y, 1), v / 1000);
        }
    }

    @Test public void tetraBecomesQuad() {
        final int w = 32, h = 24;
        int[] out = RawBin.outputSize(w, h, 4);
        assertArrayEquals(new int[]{16, 12}, out);
        ShortBuffer in = plane(w, h, 4), binned = ShortBuffer.allocate(out[0] * out[1]);
        RawBin.bin(in, w, h, 4, binned, out[0], out[1]);
        for (int y = 0; y < out[1]; y++) for (int x = 0; x < out[0]; x++) {
            final int sum = at(in, w, 2 * x, 2 * y) + at(in, w, 2 * x + 1, 2 * y) + at(in, w, 2 * x, 2 * y + 1) + at(in, w, 2 * x + 1, 2 * y + 1);
            final int v = at(binned, out[0], x, y);
            assertEquals((sum + 2) >> 2, v);
            assertEquals("Tetra 4x4 blocks become Quad 2x2 blocks", phase(x, y, 2), v / 1000);
        }
    }

    @Test public void outputSizesKeepWholeColourPeriodsOnly() {
        assertArrayEquals(new int[]{2040, 1530}, RawBin.outputSize(4080, 3060, 1));
        assertArrayEquals(new int[]{2040, 1530}, RawBin.outputSize(4082, 3062, 1));   // a partial 4x4 cell goes
        assertArrayEquals(new int[]{4080, 3060}, RawBin.outputSize(8160, 6120, 2));
        assertArrayEquals(new int[]{4082, 3062}, RawBin.outputSize(8166, 6126, 2));
        assertArrayEquals(new int[]{8160, 6120}, RawBin.outputSize(16320, 12240, 4));
        assertArrayEquals(new int[]{8160, 6120}, RawBin.outputSize(16326, 12246, 4));
    }

    @Test public void onlyTheHardLimitsCountNoMegapixelCap() {
        final int adreno = 16384, unknown = Integer.MAX_VALUE;
        for (int side : new int[]{adreno, unknown}) {
            assertTrue(RawBin.fits(4080, 3060, side));      // 12.5 MP
            assertTrue(RawBin.fits(4624, 3472, side));      // 16.05 MP, refused by the old 16 MP guard
            assertTrue(RawBin.fits(8160, 6120, side));      // 50 MP
            assertTrue(RawBin.fits(12000, 9000, side));     // 108 MP
            assertTrue(RawBin.fits(15000, 11900, side));    // 178.5 MP: RGB 2142 MB, still one buffer
            assertFalse(RawBin.fits(15000, 12000, side));   // 180 MP: RGB above 2 GB
            assertFalse(RawBin.fits(16320, 12240, side));   // 200 MP
        }
        assertNull(RawBin.limitExceeded(8160, 6120, adreno));
        assertTrue(RawBin.limitExceeded(16320, 12240, adreno).contains("above 2 GB"));
        assertFalse(RawBin.fits(9000, 6000, 8192));         // a side above the GPU's limit
        assertTrue(RawBin.limitExceeded(9000, 6000, 8192).contains("GPU"));
        assertFalse(RawBin.fits(70000, 2000, unknown));     // the merge transport's 65534
        assertTrue(RawBin.fits(65534, 2000, unknown));
    }

    private static ImageFrame frame(int w, int h, ByteBuffer buffer) {
        ImageFrame f = mock(ImageFrame.class);
        f.width = w; f.height = h; f.buffer = buffer; f.binnedSamples = 1;
        return f;
    }

    @Test public void framesWithinTheLimitsAreNeverTouched() throws Exception {
        for (int[] size : new int[][]{{4080, 3060}, {4624, 3472}, {8160, 6120}, {12000, 9000}}) {
            ByteBuffer buffer = ByteBuffer.allocate(16);
            ImageFrame f = frame(size[0], size[1], buffer);
            List<ImageFrame> frames = new ArrayList<>(List.of(f));
            assertEquals(1, RawBin.binOversized(frames, 64f, 1023f, 16384));
            assertSame(buffer, f.buffer);
            assertEquals(size[0], f.width);
            assertEquals(size[1], f.height);
            assertEquals(1, f.binnedSamples);
        }
    }

    /** A scene (gradient + texture) through a colour-block CFA, with shot-like noise (as MosaicBlockDetectorTest). */
    private static ByteBuffer mosaic(int w, int h, int block) {
        Random r = new Random(block);
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        double[] colour = {0.45, 1.0, 0.7};
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = phase(x, y, block), c = p == 0 ? 0 : p == 3 ? 2 : 1;
            double scene = 300 + 200.0 * x / w + 80 * Math.sin(x * 0.07) * Math.cos(y * 0.05);
            double v = 64 + scene * colour[c] + r.nextGaussian() * Math.sqrt(scene * colour[c] * 0.5 + 4);
            b.putShort((y * w + x) * 2, (short) Math.max(0, Math.min(1023, Math.round(v))));
        }
        return b;
    }

    @Test public void beyondTheGpuSideAQuadStreamIsBinnedToBayerOnceAndItsNoiseScaled() throws Exception {
        final int w = 640, h = 480;
        ByteBuffer source = mosaic(w, h, 2);
        ShortBuffer expected = ShortBuffer.allocate(320 * 240);
        RawBin.bin(source.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(), w, h, 2, expected, 320, 240);
        ImageFrame a = frame(w, h, source), b = frame(w, h, mosaic(w, h, 2)), other = frame(320, 200, ByteBuffer.allocate(16));
        List<ImageFrame> frames = new ArrayList<>(List.of(a, b, other));
        assertEquals(2, RawBin.binOversized(frames, 64f, 1023f, 400));   // side 640 above a GPU limit of 400
        for (ImageFrame f : new ImageFrame[]{a, b}) {
            assertEquals(320, f.width);
            assertEquals(240, f.height);
            assertEquals(4, f.binnedSamples);
            assertEquals(320 * 240 * 2, f.buffer.capacity());
        }
        ShortBuffer got = a.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        for (int i = 0; i < 320 * 240; i++) assertEquals("site " + i, expected.get(i), got.get(i));
        assertEquals("a frame of another size is left to the burst", 320, other.width);
        assertEquals(1, other.binnedSamples);
    }
}
