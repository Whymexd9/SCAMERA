package com.particlesdevs.photoncamera.processing.heif;

import org.junit.Test;

import static org.junit.Assert.*;

/** The 512 x 512 tile grid: counts, positions and the padded right / bottom tiles. */
public class TileGridTest {
    @Test
    public void exactMultiple() {
        final TileGrid g = new TileGrid(4096, 3072);
        assertEquals(512, g.tile);
        assertEquals(8, g.columns);
        assertEquals(6, g.rows);
        assertEquals(48, g.count());
        assertEquals(512, g.validWidth(7));
        assertEquals(512, g.validHeight(5));
    }

    @Test
    public void paddedEdgesForSizesNotDivisibleBy512() {
        // 12.5 MP hybrid output 4080 x 3060 and a 50 MP 8160 x 6120 frame
        final TileGrid g = new TileGrid(4080, 3060);
        assertEquals(8, g.columns);
        assertEquals(6, g.rows);
        assertEquals(3584, g.x0(7));
        assertEquals(4080 - 3584, g.validWidth(7));
        assertEquals(3060 - 2560, g.validHeight(5));
        assertEquals(512, g.validWidth(6));
        final TileGrid big = new TileGrid(8160, 6120);
        assertEquals(16, big.columns);
        assertEquals(12, big.rows);
        assertEquals(192, big.count());
        assertEquals(8160 - 15 * 512, big.validWidth(15));
        assertEquals(6120 - 11 * 512, big.validHeight(11));
    }

    @Test
    public void imageSmallerThanOneTile() {
        final TileGrid g = new TileGrid(300, 1);
        assertEquals(1, g.count());
        assertEquals(300, g.validWidth(0));
        assertEquals(1, g.validHeight(0));
    }

    @Test
    public void limits() {
        assertEquals(256, new TileGrid(256 * 512, 512).columns);
        try {
            new TileGrid(256 * 512 + 1, 512);
            fail("257 columns accepted (ImageGrid stores columns - 1 in 8 bits)");
        } catch (IllegalArgumentException expected) {
            // too wide for one grid
        }
        try {
            new TileGrid(0, 10);
            fail("empty image accepted");
        } catch (IllegalArgumentException expected) {
            // nothing to code
        }
        try {
            new TileGrid(100, 100, 15);
            fail("odd tile accepted (4:2:0)");
        } catch (IllegalArgumentException expected) {
            // chroma needs even tiles
        }
    }
}
