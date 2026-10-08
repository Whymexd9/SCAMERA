package com.particlesdevs.photoncamera.processing.heif;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/** RGBA_1010102 -> P010 (BT.709 full range, 2x2 chroma average, LE words with the value in the high bits, edge padding). */
public class P010Test {
    private static int y(byte[] p, int tile, int x, int yy) {
        return P010.sample(p, 2 * (yy * tile + x));
    }

    private static int cb(byte[] p, int tile, int cx, int cy) {
        return P010.sample(p, tile * tile * 2 + (cy * (tile / 2) + cx) * 4);
    }

    private static int cr(byte[] p, int tile, int cx, int cy) {
        return P010.sample(p, tile * tile * 2 + (cy * (tile / 2) + cx) * 4 + 2);
    }

    private static byte[] convertFlat(int r, int g, int b) {
        final int[] band = new int[4 * 4];
        java.util.Arrays.fill(band, P010.pack(r, g, b));
        final byte[] out = new byte[P010.tileBytes(4, 4)];
        P010.convertTile(band, 4, 4, 0, 4, 4, 4, out);
        return out;
    }

    @Test
    public void knownColours() {
        // {R, G, B, Y, Cb, Cr}: BT.709 full range at 10 bits
        final int[][] cases = {
                {0, 0, 0, 0, 512, 512},
                {1023, 1023, 1023, 1023, 512, 512},
                {512, 512, 512, 512, 512, 512},
                {1023, 0, 0, 217, 395, 1023},
                {0, 1023, 0, 732, 118, 47},
                {0, 0, 1023, 74, 1023, 465},
        };
        for (int[] c : cases) {
            final byte[] p = convertFlat(c[0], c[1], c[2]);
            final String what = c[0] + "," + c[1] + "," + c[2];
            assertEquals(what + " Y", c[3], y(p, 4, 1, 2));
            assertEquals(what + " Cb", c[4], cb(p, 4, 1, 1));
            assertEquals(what + " Cr", c[5], cr(p, 4, 1, 1));
        }
    }

    @Test
    public void matchesTheBt709FormulaEverywhere() {
        final Random rnd = new Random(7);
        for (int i = 0; i < 20000; i++) {
            final int r = rnd.nextInt(1024), g = rnd.nextInt(1024), b = rnd.nextInt(1024);
            final double yy = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            assertEquals(yy, P010.luma(r, g, b), 0.51);
            final double cbv = (b - yy) / 1.8556 + 512, crv = (r - yy) / 1.5748 + 512;
            assertEquals(Math.max(0, Math.min(1023, cbv)), P010.cb4(4 * r, 4 * g, 4 * b), 0.51);
            assertEquals(Math.max(0, Math.min(1023, crv)), P010.cr4(4 * r, 4 * g, 4 * b), 0.51);
        }
    }

    @Test
    public void wordsAreLittleEndianWithTheValueInTheHighBits() {
        final byte[] p = convertFlat(1023, 1023, 1023);
        assertEquals((byte) 0xC0, p[0]); // 1023 << 6 = 0xFFC0
        assertEquals((byte) 0xFF, p[1]);
        final byte[] q = convertFlat(1, 1, 1);
        assertEquals((byte) 0x40, q[0]); // 1 << 6
        assertEquals(0, q[1]);
        assertEquals(P010.tileBytes(4, 4), p.length);
    }

    @Test
    public void chromaIsTheAverageOfTheTwoByTwoBlock() {
        // left block: red, green / blue, white; right block: all black
        final int[] band = new int[4 * 2];
        band[0] = P010.pack(1023, 0, 0);
        band[1] = P010.pack(0, 1023, 0);
        band[4] = P010.pack(0, 0, 1023);
        band[5] = P010.pack(1023, 1023, 1023);
        for (int i : new int[]{2, 3, 6, 7}) band[i] = P010.pack(0, 0, 0);
        final byte[] out = new byte[P010.tileBytes(4, 2)];
        P010.convertTile(band, 4, 2, 0, 4, 4, 2, out);
        final int sr = 2046, sg = 2046, sb = 2046; // the four pixels sum to 2046 per channel: grey 511.5
        assertEquals(P010.cb4(sr, sg, sb), P010.sample(out, 16));
        assertEquals(512, P010.sample(out, 16));
        assertEquals(512, P010.sample(out, 18));
        assertEquals(512, P010.sample(out, 20)); // black block
        assertEquals(217, P010.sample(out, 0)); // Y of red
        assertEquals(732, P010.sample(out, 2)); // Y of green
        assertEquals(74, P010.sample(out, 8)); // Y of blue (second row)
        assertEquals(1023, P010.sample(out, 10)); // Y of white
        // a red / black block: Cr is half way between red's and neutral
        final int[] half = {P010.pack(1023, 0, 0), P010.pack(0, 0, 0), P010.pack(1023, 0, 0), P010.pack(0, 0, 0)};
        final byte[] o2 = new byte[P010.tileBytes(2, 2)];
        P010.convertTile(half, 2, 2, 0, 2, 2, 2, o2);
        assertEquals(Math.round(512 + (1023 - 0.2126 * 1023) / 1.5748 / 2), P010.sample(o2, 10), 1);
    }

    @Test
    public void paddingRepeatsTheLastColumnAndRow() {
        // a 3 x 3 image in a 4 x 4 tile at column offset 2 of a 5-wide band
        final int[] band = new int[5 * 4];
        for (int yy = 0; yy < 3; yy++)
            for (int x = 0; x < 5; x++) band[yy * 5 + x] = P010.pack(100 * x + yy, 0, 0);
        for (int x = 0; x < 5; x++) band[3 * 5 + x] = P010.pack(999, 999, 999); // stale row past the image
        final byte[] out = new byte[P010.tileBytes(4, 4)];
        P010.convertTile(band, 5, 3, 2, 3, 4, 4, out);
        for (int yy = 0; yy < 4; yy++) {
            for (int x = 0; x < 4; x++) {
                final int sx = 2 + Math.min(x, 2), sy = Math.min(yy, 2);
                assertEquals("(" + x + "," + yy + ")", P010.luma(100 * sx + sy, 0, 0), y(out, 4, x, yy));
            }
        }
        // the bottom-right chroma block is made of the last pixel only
        final int last = 100 * 4 + 2;
        assertEquals(P010.cr4(4 * last, 0, 0), cr(out, 4, 1, 1));
    }

    @Test
    public void rangeIsClamped() {
        for (int r = 0; r < 1024; r += 31)
            for (int b = 0; b < 1024; b += 29) {
                final int[] v = {P010.luma(r, 0, b), P010.cb4(4 * r, 0, 4 * b), P010.cr4(4 * r, 0, 4 * b)};
                for (int s : v) assertTrue(s >= 0 && s <= 1023);
            }
    }

    @Test(expected = IllegalArgumentException.class)
    public void oddTileRefused() {
        P010.convertTile(new int[9], 3, 3, 0, 3, 3, 3, new byte[P010.tileBytes(3, 3)]);
    }
}
