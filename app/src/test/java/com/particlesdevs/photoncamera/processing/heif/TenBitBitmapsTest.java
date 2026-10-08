package com.particlesdevs.photoncamera.processing.heif;

import android.app.Application;
import android.graphics.Bitmap;

import com.particlesdevs.photoncamera.control.ZoomController;
import com.particlesdevs.photoncamera.processing.ml.Lanczos1010102;
import com.particlesdevs.photoncamera.processing.ml.VivoPostDownscale;
import com.particlesdevs.photoncamera.processing.opengl.GLImage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * The RGBA_1010102 final image of the 10-bit HEIC through everything after the post pipeline: strip reads / writes, the
 * zoom crop, the hybrid resize kernels, the 8-bit copies (encoders, gain-map texture) - real Skia (native graphics).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TenBitBitmapsTest {
    static int pack(int r, int g, int b) {
        return P010.pack(r, g, b);
    }

    /** A 10-bit bitmap holding exactly {@code px} (row-major packed pixels). */
    static Bitmap tenBit(int w, int h, int[] px) {
        final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_1010102);
        final ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        buf.asIntBuffer().put(px);
        b.copyPixelsFromBuffer(buf);
        return b;
    }

    static int[] pixels(Bitmap b) {
        final ByteBuffer buf = ByteBuffer.allocateDirect(b.getByteCount()).order(ByteOrder.nativeOrder());
        b.copyPixelsToBuffer(buf);
        buf.rewind();
        final int[] out = new int[b.getWidth() * b.getHeight()];
        buf.asIntBuffer().get(out);
        return out;
    }

    static int[] random(int n, long seed) {
        final Random rnd = new Random(seed);
        final int[] px = new int[n];
        for (int i = 0; i < n; i++) px[i] = pack(rnd.nextInt(1024), rnd.nextInt(1024), rnd.nextInt(1024));
        return px;
    }

    @Test
    public void rowsGoInAndOutUnchanged() {
        final int w = 37, h = 150;
        final int[] px = random(w * h, 3);
        final Bitmap dst = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_1010102);
        assertTrue(TenBitBitmaps.isTenBit(dst));
        try (TenBitBitmaps.RowWriter writer = new TenBitBitmaps.RowWriter(dst, 16)) {
            for (int y = 0; y < h; y += 23) writer.write(y, Math.min(23, h - y), px, y * w);
        }
        assertArrayEquals("raw 2_10_10_10 words kept by the strip blit", px, pixels(dst));
        final int[] back = new int[w * h];
        try (TenBitBitmaps.RowReader reader = new TenBitBitmaps.RowReader(dst, 64)) {
            for (int y = 0; y < h; y += 41) reader.read(y, Math.min(41, h - y), back, y * w);
        }
        assertArrayEquals(px, back);
    }

    @Test
    public void cropKeepsTenBits() {
        final int[] px = random(40 * 30, 11);
        final Bitmap src = tenBit(40, 30, px);
        final Bitmap c = TenBitBitmaps.crop(src, 6, 4, 20, 16);
        assertEquals(Bitmap.Config.RGBA_1010102, c.getConfig());
        final int[] got = pixels(c);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 20; x++) assertEquals(px[(y + 4) * 40 + x + 6], got[y * 20 + x]);
        // ZoomController's centre crop keeps the config too (Bitmap.createBitmap would make it ARGB_8888)
        final Bitmap zoomed = ZoomController.crop(tenBit(40, 30, px), 2f);
        assertEquals(Bitmap.Config.RGBA_1010102, zoomed.getConfig());
        assertEquals(20, zoomed.getWidth());
        assertEquals(14, zoomed.getHeight());
        // an 8-bit image takes the platform crop as before
        final Bitmap eight = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        assertEquals(Bitmap.Config.ARGB_8888, TenBitBitmaps.crop(eight, 0, 0, 10, 10).getConfig());
    }

    @Test
    public void eightBitCopiesRoundTenBitValues() {
        final int[] px = new int[64];
        for (int i = 0; i < 64; i++) px[i] = pack(i * 16, 1023 - i * 16, (i * 37) % 1024);
        final Bitmap src = tenBit(8, 8, px);
        final Bitmap eight = TenBitBitmaps.toArgb8888(src);
        assertEquals(Bitmap.Config.ARGB_8888, eight.getConfig());
        final ByteBuffer rgba = TenBitBitmaps.rgba8(src);
        final ByteBuffer copy = ByteBuffer.allocate(eight.getByteCount());
        eight.copyPixelsToBuffer(copy);
        for (int i = 0; i < 64; i++) {
            final int r = i * 16, g = 1023 - i * 16, b = (i * 37) % 1024;
            // round(v * 255 / 1023); getPixel reads ARGB whatever the byte order of ARGB_8888 is (RGBA on a phone)
            final int color = eight.getPixel(i % 8, i / 8);
            assertEquals("copy px " + i + " red", (r * 255 + 511) / 1023, android.graphics.Color.red(color), 1);
            assertEquals("copy px " + i + " green", (g * 255 + 511) / 1023, android.graphics.Color.green(color), 1);
            assertEquals("copy px " + i + " blue", (b * 255 + 511) / 1023, android.graphics.Color.blue(color), 1);
            for (int c = 0; c < 4; c++)
                assertEquals("strip conversion px " + i + " c" + c, copy.get(i * 4 + c) & 0xFF, rgba.get(i * 4 + c) & 0xFF, 1);
        }
    }

    @Test
    public void glImageOfATenBitBitmapIsRgba8() {
        final int[] px = random(33 * 70, 5);
        final Bitmap src = tenBit(33, 70, px); // more rows than one conversion strip
        final GLImage image = new GLImage(src);
        final ByteBuffer expected = ByteBuffer.allocate(33 * 70 * 4);
        TenBitBitmaps.toArgb8888(src).copyPixelsToBuffer(expected);
        assertEquals(33 * 70 * 4, image.byteBuffer.capacity());
        for (int i = 0; i < 33 * 70 * 4; i++)
            assertEquals(i + "", expected.get(i) & 0xFF, image.byteBuffer.get(i) & 0xFF, 1);
        // an ARGB_8888 bitmap still uploads its own bytes
        final Bitmap eight = TenBitBitmaps.toArgb8888(src);
        final GLImage raw = new GLImage(eight);
        final ByteBuffer own = ByteBuffer.allocate(eight.getByteCount());
        eight.copyPixelsToBuffer(own);
        own.rewind();
        assertEquals(own, raw.byteBuffer);
    }

    @Test
    public void resizeKernelsKeepTenBits() {
        final int sw = 64, sh = 48, dw = 40, dh = 30;
        final int[] px = random(sw * sh, 9);
        for (String kernel : new String[]{"lanczos", "bicubic", "area", "bilinear"}) {
            final Bitmap out = VivoPostDownscale.resizeTo(tenBit(sw, sh, px), dw, dh, kernel);
            assertEquals(kernel, Bitmap.Config.RGBA_1010102, out.getConfig());
            assertEquals(dw, out.getWidth());
            assertEquals(dh, out.getHeight());
        }
        // the 10-bit Lanczos through the bitmap rows equals the pure port on arrays
        final int[] expected = new int[dw * dh];
        try {
            Lanczos1010102.resize(() -> (y, rows, o, offset) -> System.arraycopy(px, y * sw, o, offset, rows * sw), sw, sh,
                    () -> (y, rows, in, offset) -> System.arraycopy(in, offset, expected, y * dw, rows * dw), dw, dh, 3, 1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertArrayEquals(expected, pixels(VivoPostDownscale.resizeTo(tenBit(sw, sh, px), dw, dh, "lanczos")));
        // flat 10-bit levels survive the bilinear / area canvas draws (no 8-bit quantisation)
        final int[] flat = new int[sw * sh];
        java.util.Arrays.fill(flat, pack(513, 257, 901));
        for (String kernel : new String[]{"area", "bilinear"})
            for (int p : pixels(VivoPostDownscale.resizeTo(tenBit(sw, sh, flat), 20, 15, kernel)))
                assertEquals(kernel, pack(513, 257, 901) & 0x3FFFFFFF, p & 0x3FFFFFFF);
    }
}
