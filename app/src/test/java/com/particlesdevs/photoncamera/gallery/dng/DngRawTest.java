package com.particlesdevs.photoncamera.gallery.dng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * P351: the editor's RAW decode of DNGs (DngRaw) on small synthetic files (DngPreviewTest's TIFF writer and lossless JPEG
 * encoder): levels, CFA phase, binning, DefaultCrop, orientation, a Quad CFA, a GainMap, LJ92 = uncompressed, and the colour
 * (DngColor): the as-shot neutral comes out grey, CCT / Duv round trips.
 */
public class DngRawTest {
    static final int W = DngPreviewTest.W, H = DngPreviewTest.H;
    static final double[] N = {0.5, 1.0, 0.7};

    static byte[] shorts16(int[] raw) {
        ByteBuffer d = ByteBuffer.allocate(2 * raw.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : raw) d.putShort((short) v);
        return d.array();
    }

    static DngPreviewTest.Tiff bayer(int[] raw, int black, int white) {
        DngPreviewTest.Tiff t = DngPreviewTest.baseTags(16, 1, N, black, white);
        byte[] data = shorts16(raw);
        t.blob(0x111, 4, 1, data);
        t.tag(0x117, 4, 1, data.length);
        return t;
    }

    static float at(DngRaw.Image img, int x, int y) {
        return (img.data.get(y * img.width + x) & 0xFFFF) / DngRaw.FIXED_ONE;
    }

    @Test
    public void levelsAndPhase() throws IOException {
        int[] raw = DngPreviewTest.scene(N, 64, 1023);
        DngRaw.Image img = DngRaw.decode(ByteBuffer.wrap(bayer(raw, 64, 1023).build()), 1);
        assertEquals(W, img.width);
        assertEquals(H, img.height);
        assertEquals(DngRaw.RGGB, img.phase);
        assertEquals(DngRaw.KIND_BAYER, img.info.kind);
        for (int[] p : new int[][]{{3, 5}, {10, 10}, {40, 7}, {50, 30}}) {
            double expect = (raw[p[1] * W + p[0]] - 64) / (1023.0 - 64);
            assertEquals(expect, at(img, p[0], p[1]), 1.0 / DngRaw.FIXED_ONE + 1e-4);
        }
        // the grey half: green 0.3 of white, red / blue the neutral's share
        assertEquals(0.3, at(img, 1, 0), 2e-3);
        assertEquals(0.3 * N[0], at(img, 0, 0), 2e-3);
        assertEquals(0.3 * N[2], at(img, 1, 1), 2e-3);
    }

    @Test
    public void binningAveragesSameColours() throws IOException {
        int[] raw = DngPreviewTest.scene(N, 64, 1023);
        ByteBuffer file = ByteBuffer.wrap(bayer(raw, 64, 1023).build());
        DngRaw.Image full = DngRaw.decode(file, 1), half = DngRaw.decode(file, 2);
        assertEquals(W / 2, half.width);
        assertEquals(H / 2, half.height);
        assertEquals(DngRaw.RGGB, half.phase);
        // output (0,0) = mean of the reds at (0,0) (2,0) (0,2) (2,2)
        double mean = (at(full, 0, 0) + at(full, 2, 0) + at(full, 0, 2) + at(full, 2, 2)) / 4;
        assertEquals(mean, at(half, 0, 0), 3e-4);
        assertEquals((at(full, 1, 0) + at(full, 3, 0) + at(full, 1, 2) + at(full, 3, 2)) / 4, at(half, 1, 0), 3e-4);
    }

    @Test
    public void defaultCropMovesThePhase() throws IOException {
        int[] raw = DngPreviewTest.scene(N, 64, 1023);
        DngPreviewTest.Tiff t = bayer(raw, 64, 1023);
        t.blob(0xC61F, 5, 2, DngPreviewTest.rationals(1, 2));   // origin x 1, y 2
        t.blob(0xC620, 5, 2, DngPreviewTest.rationals(W - 4, H - 4));
        DngRaw.Image img = DngRaw.decode(ByteBuffer.wrap(t.build()), 1);
        assertEquals(DngRaw.GRBG, img.phase);
        assertEquals(W - 4, img.width);
        assertEquals(H - 4, img.height);
        assertEquals(at(DngRaw.decode(ByteBuffer.wrap(bayer(raw, 64, 1023).build()), 1), 1, 2), at(img, 0, 0), 1e-6);
    }

    @Test
    public void orientationAndBlackPattern() throws IOException {
        int[] raw = DngPreviewTest.scene(N, 0, 1023);
        // a 2x2 black pattern: 60 on the reds, 64 elsewhere
        int[] withBlack = raw.clone();
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) withBlack[y * W + x] += ((x & 1) == 0 && (y & 1) == 0) ? 60 : 64;
        DngPreviewTest.Tiff t = bayer(withBlack, 0, 1023 + 64);
        t.blob(0xC619, 3, 2, DngPreviewTest.shorts(2, 2));
        t.entries.removeIf(e -> e[0] == 0xC61A);
        t.blob(0xC61A, 5, 4, DngPreviewTest.rationals(60, 64, 64, 64));
        t.tag(0x112, 3, 1, 6);
        DngRaw.Image img = DngRaw.decode(ByteBuffer.wrap(t.build()), 1);
        assertEquals(6, img.info.orientation);
        assertEquals(raw[0] / (1023.0 + 64 - 60), at(img, 0, 0), 2e-3);
        assertEquals(raw[1] / (1023.0), at(img, 1, 0), 2e-3);
    }

    @Test
    public void losslessJpegEqualsUncompressed() throws IOException {
        int[] raw = DngPreviewTest.scene(N, 64, 1023);
        byte[] lj = DngPreviewTest.Lj92TestEncoder.encode(raw, 2 * W, H / 2, 16);
        DngPreviewTest.Tiff t = DngPreviewTest.baseTags(16, 7, N, 64, 1023);
        t.blob(0x111, 4, 1, lj);
        t.tag(0x117, 4, 1, lj.length);
        DngRaw.Image a = DngRaw.decode(ByteBuffer.wrap(t.build()), 1);
        DngRaw.Image b = DngRaw.decode(ByteBuffer.wrap(bayer(raw, 64, 1023).build()), 1);
        for (int i = 0; i < W * H; i++) assertEquals(b.data.get(i), a.data.get(i));
    }

    @Test
    public void quadCfaBecomesHalfSizeBayer() throws IOException {
        int[] raw = new int[W * H];
        byte[] cfa = new byte[16];
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) cfa[y * 4 + x] = (byte) (y < 2 ? (x < 2 ? 0 : 1) : (x < 2 ? 1 : 2));
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int c = cfa[(y % 4) * 4 + (x % 4)];
                raw[y * W + x] = 100 + c * 200 + ((x + y) & 1) * 8;
            }
        DngPreviewTest.Tiff t = bayer(raw, 0, 1023);
        t.entries.removeIf(e -> e[0] == 0x828D || e[0] == 0x828E);
        t.blob(0x828D, 3, 2, DngPreviewTest.shorts(4, 4));
        t.blob(0x828E, 1, 16, cfa);
        DngRaw.Info info = DngRaw.probe(ByteBuffer.wrap(t.build()));
        assertEquals(2, info.cell);
        DngRaw.Image img = DngRaw.decode(ByteBuffer.wrap(t.build()), 1);
        assertEquals(W / 2, img.width);
        assertEquals(DngRaw.RGGB, img.phase);
        assertEquals(104 / 1023.0, at(img, 0, 0), 1e-3);  // a 2x2 red cell: 100, 108, 108, 100
        assertEquals(304 / 1023.0, at(img, 1, 0), 1e-3);
    }

    /** A big-endian OpcodeList2 with one GainMap of constant {@code gain} over the whole image (all CFA positions). */
    static byte[] gainMapList(float gain) {
        ByteBuffer b = ByteBuffer.allocate(4 + 16 + 76 + 4 * 4).order(ByteOrder.BIG_ENDIAN);
        b.putInt(1);
        b.putInt(9).putInt(0x01030000).putInt(0).putInt(76 + 16);
        b.putInt(0).putInt(0).putInt(H).putInt(W).putInt(0).putInt(1).putInt(1).putInt(1).putInt(2).putInt(2);
        b.putDouble(1).putDouble(1).putDouble(0).putDouble(0).putInt(1);
        for (int i = 0; i < 4; i++) b.putFloat(gain);
        return b.array();
    }

    /**
     * P370 (review): a GainMap whose point count overflows an int (5 x 214748365: x 4 bytes wrapped to 4) is skipped, not an
     * OutOfMemoryError from a 1-billion-float array; the decode goes on without it.
     */
    @Test
    public void anOverflowingGainMapIsSkipped() throws IOException {
        ByteBuffer b = ByteBuffer.allocate(4 + 16 + 76 + 4 * 4).order(ByteOrder.BIG_ENDIAN);
        b.putInt(1);
        b.putInt(9).putInt(0x01030000).putInt(0).putInt(76 + 16);
        b.putInt(0).putInt(0).putInt(H).putInt(W).putInt(0).putInt(1).putInt(1).putInt(1).putInt(5).putInt(214748365);
        b.putDouble(1).putDouble(1).putDouble(0).putDouble(0).putInt(1);
        for (int i = 0; i < 4; i++) b.putFloat(2f);
        final byte[] ops = b.array();
        assertTrue(DngRaw.parseGainMaps(ops).isEmpty());
        int[] raw = DngPreviewTest.scene(N, 64, 1023);
        DngPreviewTest.Tiff t = bayer(raw, 64, 1023);
        t.blob(0xC740, 7, ops.length, ops);
        DngRaw.Image img = DngRaw.decode(ByteBuffer.wrap(t.build()), 1);
        assertTrue(!img.info.gainMap);
        assertEquals(0.3, at(img, 1, 0), 3e-3);
        assertTrue(DngPreview.read(ByteBuffer.wrap(t.build()), W / 2, H / 2).argb != null);
    }

    @Test
    public void gainMapScalesSamples() throws IOException {
        int[] raw = DngPreviewTest.scene(N, 64, 1023);
        DngPreviewTest.Tiff t = bayer(raw, 64, 1023);
        byte[] ops = gainMapList(2f);
        t.blob(0xC740, 7, ops.length, ops);
        DngRaw.Image img = DngRaw.decode(ByteBuffer.wrap(t.build()), 1);
        assertTrue(img.info.gainMap);
        assertEquals(0.6, at(img, 1, 0), 3e-3);
    }

    @Test
    public void asShotNeutralComesOutGrey() throws IOException {
        // XYZ -> camera: a plausible matrix (rows ~ R, G, B sensitivities)
        double[] cm = {0.9, -0.3, -0.1, -0.4, 1.3, 0.1, -0.05, 0.2, 0.6};
        DngColor colour = new DngColor(cm, null, null, null, null, null, null, 21, 0, N, null);
        double[] m = colour.cameraToSrgb(colour.asShotNeutral);
        double[] out = DngColor.apply(m, N);
        assertEquals(1, out[0], 1e-9);
        assertEquals(1, out[1], 1e-9);
        assertEquals(1, out[2], 1e-9);
        // the neutral of the white it implies maps back to itself
        double[] xy = colour.xyForNeutral(colour.asShotNeutral);
        double[] n = colour.neutralForXy(xy);
        for (int i = 0; i < 3; i++) assertEquals(colour.asShotNeutral[i], n[i], 1e-6);
        // and the decoded file carries the same neutral
        int[] raw = DngPreviewTest.scene(N, 64, 1023);
        DngPreviewTest.Tiff t = bayer(raw, 64, 1023);
        t.blob(0xC621, 10, 9, srationals(cm));
        DngRaw.Info info = DngRaw.probe(ByteBuffer.wrap(t.build()));
        assertTrue(info.colour.hasMatrices());
        assertEquals(N[0], info.colour.asShotNeutral[0], 1e-3);
    }

    @Test
    public void colourPatchKeepsItsHue() {
        double[] cm = {0.9, -0.3, -0.1, -0.4, 1.3, 0.1, -0.05, 0.2, 0.6};
        double[] fm = {0.6, 0.25, 0.1, 0.25, 0.7, 0.05, 0.0, 0.05, 0.78};
        DngColor colour = new DngColor(cm, null, fm, null, null, null, null, 21, 0, N, null);
        double[] m = colour.cameraToSrgb(colour.asShotNeutral);
        double[] grey = DngColor.apply(m, N);
        assertEquals(grey[0], grey[2], 1e-6);
        // more red on the sensor than the neutral -> a reddish result
        double[] red = DngColor.apply(m, new double[]{N[0] * 2, N[1], N[2]});
        assertTrue(red[0] > red[1] && red[0] > red[2]);
    }

    @Test
    public void cctDuvRoundTrip() {
        for (double t : new double[]{2850, 4000, 5000, 6504, 9000}) {
            for (double duv : new double[]{-0.01, 0, 0.012}) {
                double[] xy = DngColor.xyFromCctDuv(t, duv);
                double[] back = DngColor.cctDuv(xy);
                assertEquals(t, back[0], t * 0.003);
                assertEquals(duv, back[1], 5e-4);
            }
        }
        double[] d65 = DngColor.cctDuv(new double[]{0.3127, 0.3290});
        assertEquals(6504, d65[0], 40);
    }

    static byte[] srationals(double... v) {
        ByteBuffer b = ByteBuffer.allocate(8 * v.length).order(ByteOrder.LITTLE_ENDIAN);
        for (double x : v) b.putInt((int) Math.round(x * 10000)).putInt(10000);
        return b.array();
    }
}
