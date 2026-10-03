package com.particlesdevs.photoncamera.processing.opengl;

import android.app.Application;
import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * The tile-to-bitmap readback must give exactly the bitmap of the old path
 * (full-frame buffer + Bitmap.copyPixelsFromBuffer), for any tile height and
 * a last tile shorter than the others, with stale bytes past each tile.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TileBlitterTest {

    private static byte[] frame(int width, int height, long seed, boolean opaque) {
        byte[] data = new byte[width * height * 4];
        Random random = new Random(seed);
        for (int i = 0; i < data.length; i += 4) {
            int a = opaque ? 255 : random.nextInt(256);
            data[i] = (byte) random.nextInt(a + 1);
            data[i + 1] = (byte) random.nextInt(a + 1);
            data[i + 2] = (byte) random.nextInt(a + 1);
            data[i + 3] = (byte) a;
        }
        return data;
    }

    private static byte[] pixels(Bitmap bitmap) {
        ByteBuffer out = ByteBuffer.allocate(bitmap.getByteCount());
        bitmap.copyPixelsToBuffer(out);
        return out.array();
    }

    private static void check(int width, int height, int tileRows, int stripRows, boolean opaque) {
        byte[] source = frame(width, height, 31L * width + height, opaque);
        Bitmap reference = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        reference.copyPixelsFromBuffer(ByteBuffer.wrap(source));

        Bitmap dst = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        ByteBuffer tile = ByteBuffer.allocateDirect(width * 4 * tileRows);
        Random stale = new Random(5);
        try (GLCoreBlockProcessing.TileBlitter blitter = new GLCoreBlockProcessing.TileBlitter(dst, stripRows)) {
            GLBlockDivider divider = new GLBlockDivider(height, tileRows);
            int[] row = new int[2];
            while (divider.nextBlock(row)) {
                tile.clear();
                byte[] junk = new byte[tile.capacity()];
                stale.nextBytes(junk);
                tile.put(junk);
                tile.clear();
                tile.put(source, row[0] * width * 4, row[1] * width * 4);
                tile.position(17); // the blitter must not depend on the caller's position
                blitter.blit(tile, row[0], row[1]);
                assertEquals(0, tile.position());
                assertEquals(tile.capacity(), tile.limit());
            }
        }
        byte[] expected = pixels(reference);
        assertArrayEquals("reference must hold the source bytes", source, expected);
        assertArrayEquals("w=" + width + " h=" + height + " tile=" + tileRows + " strip=" + stripRows,
                expected, pixels(dst));
    }

    @Test
    public void tilesGiveTheSameBitmapAsTheFullBuffer() {
        check(37, 300, 256, 16, true);   // last tile 44 rows: partial strips
        check(64, 256, 256, 16, true);   // exactly one tile
        check(29, 21, 8, 8, true);       // energy-saving tile size, short last tile
        check(40, 10, 256, 16, true);    // image shorter than one strip
        check(33, 77, 100, 100, true);   // tile size that is not a multiple of the strip
    }

    @Test
    public void rawPremultipliedBytesAreCopiedUnchanged() {
        check(31, 50, 16, 16, false);
    }
}
