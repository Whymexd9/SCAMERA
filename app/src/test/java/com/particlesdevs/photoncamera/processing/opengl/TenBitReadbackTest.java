package com.particlesdevs.photoncamera.processing.opengl;

import android.app.Application;
import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * The 10-bit HEIC readback: glReadPixels of the RGB10_A2 tile target gives GL_UNSIGNED_INT_2_10_10_10_REV words (R in the
 * low 10 bits), the layout of Bitmap.Config.RGBA_1010102, so the tile blitter must copy them into the RGBA_1010102 bitmap
 * unchanged - for any tile height, a short last tile and stale bytes past each tile, as the 8-bit readback (TileBlitterTest).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TenBitReadbackTest {
    private static void check(int width, int height, int tileRows, int stripRows) {
        final Random random = new Random(31L * width + height);
        final int[] words = new int[width * height];
        for (int i = 0; i < words.length; i++) words[i] = (3 << 30) | (random.nextInt(1 << 30)); // alpha 3 as the shader writes
        final ByteBuffer source = ByteBuffer.allocate(words.length * 4).order(ByteOrder.nativeOrder());
        source.asIntBuffer().put(words);

        final Bitmap dst = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_1010102);
        final ByteBuffer tile = ByteBuffer.allocateDirect(width * 4 * tileRows);
        final Random stale = new Random(5);
        try (GLCoreBlockProcessing.TileBlitter blitter = new GLCoreBlockProcessing.TileBlitter(dst, stripRows)) {
            final GLBlockDivider divider = new GLBlockDivider(height, tileRows);
            final int[] row = new int[2];
            while (divider.nextBlock(row)) {
                final byte[] junk = new byte[tile.capacity()];
                stale.nextBytes(junk);
                tile.clear();
                tile.put(junk);
                tile.clear();
                tile.put(source.array(), row[0] * width * 4, row[1] * width * 4);
                tile.position(9);
                blitter.blit(tile, row[0], row[1]);
                assertEquals(0, tile.position());
            }
        }
        assertEquals(Bitmap.Config.RGBA_1010102, dst.getConfig());
        final ByteBuffer out = ByteBuffer.allocate(dst.getByteCount()).order(ByteOrder.nativeOrder());
        dst.copyPixelsToBuffer(out);
        out.rewind();
        final int[] got = new int[words.length];
        out.asIntBuffer().get(got);
        assertArrayEquals("w=" + width + " h=" + height + " tile=" + tileRows, words, got);
    }

    @Test
    public void tenBitTilesAreCopiedUnchanged() {
        check(37, 300, 256, 16);
        check(64, 256, 256, 16);
        check(29, 21, 8, 8);
        check(33, 77, 100, 100);
    }

    @Test
    public void tenBitDecodeOfAWordMatchesTheGlLayout() {
        // R = 900, G = 600, B = 300 in a GL_UNSIGNED_INT_2_10_10_10_REV word: the bitmap reads them as R, G, B
        final Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.RGBA_1010102);
        final ByteBuffer w = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
        w.putInt((3 << 30) | (300 << 20) | (600 << 10) | 900);
        w.rewind();
        b.copyPixelsFromBuffer(w);
        final android.graphics.Color c = b.getColor(0, 0);
        // the host's getColor goes through 8 bits: the channel order is what this checks
        assertEquals(900 / 1023f, c.red(), 1 / 255f);
        assertEquals(600 / 1023f, c.green(), 1 / 255f);
        assertEquals(300 / 1023f, c.blue(), 1 / 255f);
        assertEquals(1f, c.alpha(), 1e-6);
    }
}
