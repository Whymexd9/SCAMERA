package com.particlesdevs.photoncamera.processing.ultrahdr;

import android.app.Application;
import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;

/** The Ultra HDR gain-map normalisation: range metadata, requantisation and the log-domain box filter, band by band. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GainMapComputerTest {

    /** GPU map: v = log2(boost) / SCALE in R=G=B; {@code stops(x, y)} per pixel. */
    private static Bitmap gpuMap(int w, int h, java.util.function.BiFunction<Integer, Integer, Float> stops) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int v = Math.round(stops.apply(x, y) / GainMapComputer.SCALE * 255f);
                px[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    @Test
    public void rangeAndRequantisation() {
        Bitmap src = gpuMap(64, 40, (x, y) -> y < 10 ? 3f : 0f);
        GainMapComputer.Result r = GainMapComputer.compute(src, 1, GainMapComputer.SCALE);
        assertEquals(64, r.gainW);
        assertEquals(40, r.gainH);
        assertEquals(0f, r.gainMapMin, 0f);
        float stored = Math.round(3f / GainMapComputer.SCALE * 255f) / 255f * GainMapComputer.SCALE;
        assertEquals(stored * 1.02f, r.gainMapMax, 1e-4f);
        assertEquals(r.gainMapMax, r.hdrCapacityMax, 0f);
        // the boosted rows come out at 255/1.02 of the range, the rest at identity (0)
        assertEquals(Math.round(255f / 1.02f), r.gainMap.getPixel(5, 2) & 0xFF);
        assertEquals(0, r.gainMap.getPixel(5, 30) & 0xFF);
        assertEquals(0xFF, r.gainMap.getPixel(63, 39) >>> 24);
    }

    @Test
    public void boxFilterAveragesInTheLogDomain() {
        // 2x2 blocks: one pixel at 2 stops, three at 0 -> 0.5 stop
        Bitmap src = gpuMap(8, 6, (x, y) -> x % 2 == 0 && y % 2 == 0 ? 2f : 0f);
        GainMapComputer.Result r = GainMapComputer.compute(src, 2, GainMapComputer.SCALE);
        assertEquals(4, r.gainW);
        assertEquals(3, r.gainH);
        float block = Math.round(2f / GainMapComputer.SCALE * 255f) / 255f * GainMapComputer.SCALE / 4f;
        assertEquals(block * 1.02f, r.gainMapMax, 1e-4f);
        for (int y = 0; y < 3; y++)
            for (int x = 0; x < 4; x++) assertEquals(Math.round(255f / 1.02f), r.gainMap.getPixel(x, y) & 0xFF);
    }

    @Test
    public void flatMapKeepsAPositiveCapacity() {
        GainMapComputer.Result r = GainMapComputer.compute(gpuMap(16, 16, (x, y) -> 0f), 1, GainMapComputer.SCALE);
        assertTrue(r.gainMapMax > r.gainMapMin);
        assertTrue(r.hdrCapacityMax > 0f);
    }
}
