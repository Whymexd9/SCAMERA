package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P118 «16:9»: one crop for every frame of a shot, whole colour blocks, the lens shading map cut to it. */
public class Wide169Test {
    @Test public void oneCropWithWholeColourBlocks() {
        for (int[] s : new int[][]{{4080, 3060}, {4000, 3000}, {4096, 3072}, {4032, 3024}, {8160, 6120}}) {
            final int rows = Wide169.rows(s[0], s[1]), first = Wide169.firstRow(s[0], s[1]);
            assertEquals(s[0] + "x" + s[1], 0, rows % 8);
            assertEquals(0, first % 8);
            assertTrue(rows <= s[0] * 9 / 16 && rows > s[0] * 9 / 16 - 8);
            assertTrue("inside the frame", first + rows <= s[1]);
            assertTrue("centred within a block", Math.abs(s[1] - rows - 2 * first) < 16);
        }
        assertEquals(2288, Wide169.rows(4080, 3060)); // was 2294 for the ring and 2295 (dropped) after the press
        assertEquals(384, Wide169.firstRow(4080, 3060));
        assertEquals("portrait stays whole", 4080, Wide169.rows(3060, 4080));
        assertEquals("already 16:9 stays whole", 2160, Wide169.rows(3840, 2160));
        assertEquals(0, Wide169.firstRow(3840, 2160));
    }

    @Test public void lensShadingMapIsCutToTheCropRows() {
        // 3 x 5 points, gain = the row's position 0..1 on the full frame (all four channels)
        final int cols = 3, rows = 5;
        final float[] map = new float[cols * rows * 4];
        for (int j = 0; j < rows; j++) for (int i = 0; i < cols * 4; i++) map[j * cols * 4 + i] = j / 4f;
        final float[] cut = Wide169.cropGainMap(map, cols, rows, 3061, 384, 2288);
        assertEquals(map.length, cut.length);
        assertEquals("first row of the crop", 384f / 3060, cut[0], 1e-4);
        assertEquals("last row of the crop", (384f + 2287) / 3060, cut[(rows - 1) * cols * 4], 1e-4);
        assertEquals("middle", (384f + 2287 / 2f) / 3060, cut[2 * cols * 4 + 5], 1e-4);
        assertSame("no crop, no change", map, Wide169.cropGainMap(map, cols, rows, 2288, 0, 2288));
    }
}
