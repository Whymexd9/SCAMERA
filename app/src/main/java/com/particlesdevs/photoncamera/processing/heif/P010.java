package com.particlesdevs.photoncamera.processing.heif;

/**
 * RGB (packed like Android's RGBA_1010102: R in bits 0-9, G 10-19, B 20-29, A 30-31) to YCbCr 4:2:0 P010 tiles for the
 * HEVC Main10 encoder: BT.709 matrix, full range (Y 0..1023, Cb / Cr 0..1023 around 512), each chroma sample the average of
 * its 2x2 pixel block, every sample a 16-bit little-endian word with the 10-bit value in its high bits. Layout of a tile:
 * the Y plane (tileW x tileH words) followed by the interleaved CbCr plane (tileW/2 x tileH/2 pairs, Cb first). Pixels of
 * the tile past the image (right / bottom padding of the grid) repeat the last image column / row. Pure Java.
 */
public final class P010 {
    // BT.709 luma and colour-difference coefficients in 1/65536 (each row sums to 65536 / 0).
    static final int KR = 13933, KG = 46871, KB = 4732;
    static final int CB_R = -7509, CB_G = -25259, CB_B = 32768;
    static final int CR_R = 32768, CR_G = -29763, CR_B = -3005;
    /** 512 (the chroma zero) and the rounding half for sums of 4 pixels in 1/65536 (shift 18). */
    private static final int CHROMA_BIAS = (512 << 18) + (1 << 17);

    private P010() {}

    /** Bytes of one P010 tile: 2 per luma sample + 2 x 2 per chroma pair (a quarter of the pixels). */
    public static int tileBytes(int tileW, int tileH) {
        return tileW * tileH * 3;
    }

    /** Packed RGBA_1010102 pixel (alpha 3). */
    public static int pack(int r, int g, int b) {
        return (3 << 30) | ((b & 0x3FF) << 20) | ((g & 0x3FF) << 10) | (r & 0x3FF);
    }

    public static int luma(int r, int g, int b) {
        return clamp((KR * r + KG * g + KB * b + 32768) >> 16);
    }

    /** Cb of a 2x2 block from the sums of its four R, G and B values. */
    public static int cb4(int sumR, int sumG, int sumB) {
        return clamp((CB_R * sumR + CB_G * sumG + CB_B * sumB + CHROMA_BIAS) >> 18);
    }

    /** Cr of a 2x2 block from the sums of its four R, G and B values. */
    public static int cr4(int sumR, int sumG, int sumB) {
        return clamp((CR_R * sumR + CR_G * sumG + CR_B * sumB + CHROMA_BIAS) >> 18);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 1023);
    }

    /**
     * Converts one tile of a band of packed pixels into P010 ({@link #tileBytes} bytes from {@code out[0]}).
     *
     * @param band       packed pixels of the band, row-major, {@code stride} per row, row 0 = the tile's first row
     * @param validRows  image rows in the band (1..tileH): rows below repeat the last one
     * @param x0         first image column of the tile in the band
     * @param validCols  image columns in the tile (1..tileW): columns to the right repeat the last one
     */
    public static void convertTile(int[] band, int stride, int validRows, int x0, int validCols, int tileW, int tileH, byte[] out) {
        if ((tileW & 1) != 0 || (tileH & 1) != 0) throw new IllegalArgumentException("odd tile " + tileW + "x" + tileH);
        if (validRows < 1 || validCols < 1 || validRows > tileH || validCols > tileW)
            throw new IllegalArgumentException("valid " + validCols + "x" + validRows + " in a tile of " + tileW + "x" + tileH);
        if (out.length < tileBytes(tileW, tileH)) throw new IllegalArgumentException("output of " + out.length + " bytes");
        final int lastCol = x0 + validCols - 1;
        final int uvBase = tileW * tileH * 2;
        final int halfW = tileW / 2;
        for (int cy = 0; cy < tileH / 2; cy++) {
            final int ty = 2 * cy;
            final int row0 = Math.min(ty, validRows - 1) * stride, row1 = Math.min(ty + 1, validRows - 1) * stride;
            int y0 = 2 * ty * tileW, y1 = y0 + 2 * tileW; // byte offsets of the two luma rows
            int uv = uvBase + cy * halfW * 4;
            for (int cx = 0; cx < halfW; cx++) {
                final int sx0 = Math.min(x0 + 2 * cx, lastCol), sx1 = Math.min(x0 + 2 * cx + 1, lastCol);
                final int a = band[row0 + sx0], b = band[row0 + sx1], c = band[row1 + sx0], d = band[row1 + sx1];
                final int ar = a & 0x3FF, ag = (a >>> 10) & 0x3FF, ab = (a >>> 20) & 0x3FF;
                final int br = b & 0x3FF, bg = (b >>> 10) & 0x3FF, bb = (b >>> 20) & 0x3FF;
                final int cr = c & 0x3FF, cg = (c >>> 10) & 0x3FF, cb = (c >>> 20) & 0x3FF;
                final int dr = d & 0x3FF, dg = (d >>> 10) & 0x3FF, db = (d >>> 20) & 0x3FF;
                y0 = put(out, y0, luma(ar, ag, ab));
                y0 = put(out, y0, luma(br, bg, bb));
                y1 = put(out, y1, luma(cr, cg, cb));
                y1 = put(out, y1, luma(dr, dg, db));
                final int sr = ar + br + cr + dr, sg = ag + bg + cg + dg, sb = ab + bb + cb + db;
                uv = put(out, uv, cb4(sr, sg, sb));
                uv = put(out, uv, cr4(sr, sg, sb));
            }
        }
    }

    /** One sample as a little-endian 16-bit word with the value in the high 10 bits. */
    private static int put(byte[] out, int at, int v) {
        out[at] = (byte) ((v & 3) << 6);
        out[at + 1] = (byte) (v >>> 2);
        return at + 2;
    }

    /** The 10-bit value of a P010 word at {@code at} (little-endian), for tests and checks. */
    public static int sample(byte[] p010, int at) {
        return (((p010[at + 1] & 0xFF) << 8) | (p010[at] & 0xFF)) >>> 6;
    }
}
