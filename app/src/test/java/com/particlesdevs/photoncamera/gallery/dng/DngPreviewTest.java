package com.particlesdevs.photoncamera.gallery.dng;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class DngPreviewTest {
    static final int W = 64, H = 48;

    /** A little-endian TIFF with one IFD and the data blobs after it. */
    static final class Tiff {
        final List<int[]> entries = new ArrayList<>(); // tag, type, count, value, blob index (-1: inline value)
        final List<byte[]> blobs = new ArrayList<>();

        void tag(int tag, int type, int count, int value) {
            entries.add(new int[]{tag, type, count, value, -1});
        }

        void blob(int tag, int type, int count, byte[] data) {
            blobs.add(data);
            entries.add(new int[]{tag, type, count, 0, blobs.size() - 1});
        }

        byte[] build() {
            entries.sort((a, b) -> Integer.compare(a[0], b[0]));
            int ifdSize = 2 + 12 * entries.size() + 4;
            int[] blobOff = new int[blobs.size()];
            int o = 8 + ifdSize;
            for (int i = 0; i < blobs.size(); ++i) {
                blobOff[i] = o;
                o += blobs.get(i).length + (blobs.get(i).length & 1);
            }
            ByteBuffer bb = ByteBuffer.allocate(o).order(ByteOrder.LITTLE_ENDIAN);
            bb.put((byte) 0x49).put((byte) 0x49).putShort((short) 42).putInt(8);
            bb.putShort((short) entries.size());
            for (int[] e : entries) {
                bb.putShort((short) e[0]).putShort((short) e[1]).putInt(e[2]);
                if (e[4] >= 0) {
                    byte[] d = blobs.get(e[4]);
                    if (d.length <= 4) {
                        byte[] p = new byte[4];
                        System.arraycopy(d, 0, p, 0, d.length);
                        bb.put(p);
                    } else bb.putInt(blobOff[e[4]]);
                } else if (e[1] == 3) {
                    bb.putShort((short) e[3]).putShort((short) 0);
                } else bb.putInt(e[3]);
            }
            bb.putInt(0);
            for (int i = 0; i < blobs.size(); ++i) {
                bb.position(blobOff[i]);
                bb.put(blobs.get(i));
            }
            return bb.array();
        }
    }

    static byte[] shorts(int... v) {
        ByteBuffer b = ByteBuffer.allocate(2 * v.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int x : v) b.putShort((short) x);
        return b.array();
    }

    static byte[] rationals(double... v) {
        ByteBuffer b = ByteBuffer.allocate(8 * v.length).order(ByteOrder.LITTLE_ENDIAN);
        for (double x : v) b.putInt((int) Math.round(x * 10000)).putInt(10000);
        return b.array();
    }

    /** Bayer RGGB raw of a scene: left half grey (camera RGB = neutral x level), right half red. */
    static int[] scene(double[] neutral, int black, int white) {
        int[] raw = new int[W * H];
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                int c = (y & 1) == 0 ? ((x & 1) == 0 ? 0 : 1) : ((x & 1) == 0 ? 1 : 2);
                double cam = x < W / 2 ? 0.3 * neutral[c] / neutral[1] : (c == 0 ? 0.5 : 0.05);
                raw[y * W + x] = (int) Math.round(black + cam * (white - black));
            }
        return raw;
    }

    static Tiff baseTags(int bps, int compression, double[] neutral, int black, int white) {
        Tiff t = new Tiff();
        t.tag(0xFE, 4, 1, 0);
        t.tag(0x100, 4, 1, W);
        t.tag(0x101, 4, 1, H);
        t.tag(0x102, 3, 1, bps);
        t.tag(0x103, 3, 1, compression);
        t.tag(0x106, 3, 1, 32803);
        t.tag(0x115, 3, 1, 1);
        t.tag(0x116, 4, 1, H);
        t.blob(0x828D, 3, 2, shorts(2, 2));
        t.blob(0x828E, 1, 4, new byte[]{0, 1, 1, 2});
        t.blob(0xC612, 1, 4, new byte[]{1, 4, 0, 0});
        t.blob(0xC61A, 5, 1, rationals(black));
        t.tag(0xC61D, 4, 1, white);
        t.blob(0xC628, 5, 3, rationals(neutral));
        return t;
    }

    static int[] pixel(DngPreview.Result r, int x, int y) {
        int v = r.argb[y * r.width + x];
        return new int[]{(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF};
    }

    static void assertGreyAndRed(DngPreview.Result r) {
        int[] g = pixel(r, r.width / 4, r.height / 2), red = pixel(r, 3 * r.width / 4, r.height / 2);
        assertTrue("grey " + g[0] + "," + g[1] + "," + g[2], Math.abs(g[0] - g[1]) <= 3 && Math.abs(g[2] - g[1]) <= 3 && g[1] > 40);
        assertTrue("red " + red[0] + "," + red[1] + "," + red[2], red[0] > red[1] + 40 && red[0] > red[2] + 40);
    }

    @Test
    public void uncompressed16BitBayer() throws Exception {
        double[] n = {0.5, 1.0, 0.7};
        int[] raw = scene(n, 64, 1023);
        Tiff t = baseTags(16, 1, n, 64, 1023);
        ByteBuffer d = ByteBuffer.allocate(2 * W * H).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : raw) d.putShort((short) v);
        t.blob(0x111, 4, 1, d.array());
        t.tag(0x117, 4, 1, 2 * W * H);
        DngPreview.Result r = DngPreview.read(ByteBuffer.wrap(t.build()), W / 2, H / 2);
        assertEquals(W / 2, r.width);
        assertGreyAndRed(r);
    }

    @Test
    public void packed10BitWithLinearisation() throws Exception {
        double[] n = {0.6, 1.0, 0.8};
        int[] linear = scene(n, 4096, 65535);
        int[] table = new int[1024];
        for (int i = 0; i < 1024; ++i) table[i] = (int) Math.round(65535 * Math.pow(i / 1023.0, 2.2));
        int[] enc = new int[linear.length];
        for (int i = 0; i < linear.length; ++i) enc[i] = (int) Math.round(1023 * Math.pow(linear[i] / 65535.0, 1 / 2.2));
        Tiff t = baseTags(10, 1, n, 4096, 65535);
        int rowBytes = (W * 10 + 7) / 8;
        byte[] packed = new byte[rowBytes * H];
        for (int y = 0; y < H; ++y) {
            long bit = 0;
            for (int x = 0; x < W; ++x)
                for (int k = 9; k >= 0; --k, ++bit)
                    if (((enc[y * W + x] >> k) & 1) != 0) packed[y * rowBytes + (int) (bit >> 3)] |= (byte) (0x80 >> (bit & 7));
        }
        t.blob(0x111, 4, 1, packed);
        t.tag(0x117, 4, 1, packed.length);
        t.blob(0xC618, 3, 1024, shorts(table));
        DngPreview.Result r = DngPreview.read(ByteBuffer.wrap(t.build()), W / 2, H / 2);
        assertGreyAndRed(r);
    }

    @Test
    public void losslessJpegInTheWritersLayout() throws Exception {
        double[] n = {0.55, 1.0, 0.75};
        int[] raw = scene(n, 64, 1023);
        // our writer: one component, 2W x H/2 (the Bayer rows in pairs)
        byte[] lj = Lj92TestEncoder.encode(raw, 2 * W, H / 2, 16);
        int[] dims = new int[2];
        char[] back = Lj92Decoder.decode(ByteBuffer.wrap(lj), 0, lj.length, dims);
        assertEquals(2 * W, dims[0]);
        int[] round = new int[back.length];
        for (int i = 0; i < back.length; ++i) round[i] = back[i];
        assertArrayEquals(raw, round);
        Tiff t = baseTags(16, 7, n, 64, 1023);
        t.blob(0x111, 4, 1, lj);
        t.tag(0x117, 4, 1, lj.length);
        DngPreview.Result r = DngPreview.read(ByteBuffer.wrap(t.build()), W / 2, H / 2);
        assertGreyAndRed(r);
    }

    @Test
    public void embeddedJpegPreviewWhenLargeEnough() throws Exception {
        Tiff t = new Tiff();
        t.tag(0xFE, 4, 1, 1);
        t.tag(0x100, 4, 1, 1600);
        t.tag(0x101, 4, 1, 1200);
        t.tag(0x103, 3, 1, 7);
        t.tag(0x106, 3, 1, 6);
        byte[] fake = new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4, (byte) 0xFF, (byte) 0xD9};
        t.blob(0x111, 4, 1, fake);
        t.tag(0x117, 4, 1, fake.length);
        t.tag(0x112, 3, 1, 6);
        DngPreview.Result r = DngPreview.read(ByteBuffer.wrap(t.build()), 400, 300);
        assertTrue(r.isJpeg());
        assertEquals(fake.length, r.jpegLength);
        assertEquals(6, r.orientation);
    }

    /**
     * A big-endian OpcodeList2 of four GainMaps (R, the two greens, B of an RGGB mosaic: pitch 2), 3 x 3 points: 1 at the
     * centre, {@code corner[c]} at the corners, the mean of the two at the edge midpoints.
     */
    static byte[] cornerMaps(double[] corner) {
        final int[][] sites = {{0, 0, 0}, {0, 1, 1}, {1, 0, 1}, {1, 1, 2}}; // top, left, colour
        ByteBuffer b = ByteBuffer.allocate(4 + sites.length * (16 + 76 + 9 * 4)).order(ByteOrder.BIG_ENDIAN);
        b.putInt(sites.length);
        for (int[] s : sites) {
            b.putInt(9).putInt(0x01030000).putInt(0).putInt(76 + 9 * 4);
            b.putInt(s[0]).putInt(s[1]).putInt(H).putInt(W).putInt(0).putInt(1).putInt(2).putInt(2).putInt(3).putInt(3);
            b.putDouble(0.5).putDouble(0.5).putDouble(0).putDouble(0).putInt(1);
            final double k = corner[s[2]], e = (1 + k) / 2;
            final double[] g = {k, e, k, e, 1, e, k, e, k};
            for (double v : g) b.putFloat((float) v);
        }
        return b.array();
    }

    /**
     * P370 (A10): a flat grey field shaded by the lens (corner gains R 2.6, G 2.4, B 2.2) renders flat through the file's GainMap:
     * a corner equals the centre within 2 % (without the map the corner was about 1.25 EV darker and tinted).
     */
    @Test
    public void theGainMapLiftsTheCorners() throws Exception {
        final double[] n = {0.5, 1.0, 0.7}, corner = {2.6, 2.4, 2.2};
        final byte[] ops = cornerMaps(corner);
        final List<DngRaw.GainMap> maps = DngRaw.parseGainMaps(ops);
        assertEquals(4, maps.size());
        final int black = 64, white = 4095;
        int[] raw = new int[W * H];
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                final int c = (y & 1) == 0 ? ((x & 1) == 0 ? 0 : 1) : ((x & 1) == 0 ? 1 : 2);
                final int m = ((y & 1) << 1) | (x & 1);
                final double gain = maps.get(m).at((y + 0.5) / H, (x + 0.5) / W, 0);
                raw[y * W + x] = (int) Math.round(black + 0.3 * n[c] / gain * (white - black));
            }
        final Tiff shaded = baseTags(16, 1, n, black, white), plain = baseTags(16, 1, n, black, white);
        ByteBuffer d = ByteBuffer.allocate(2 * W * H).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : raw) d.putShort((short) v);
        for (Tiff t : new Tiff[]{shaded, plain}) {
            t.blob(0x111, 4, 1, d.array());
            t.tag(0x117, 4, 1, 2 * W * H);
        }
        shaded.blob(0xC740, 7, ops.length, ops);
        final DngPreview.Result r = DngPreview.read(ByteBuffer.wrap(shaded.build()), W / 2, H / 2);
        final int[] centre = pixel(r, r.width / 2, r.height / 2);
        for (int[] at : new int[][]{{0, 0}, {r.width - 1, 0}, {0, r.height - 1}, {r.width - 1, r.height - 1}}) {
            final int[] p = pixel(r, at[0], at[1]);
            for (int c = 0; c < 3; c++)
                assertEquals("corner " + at[0] + "," + at[1] + " channel " + c, centre[c], p[c], Math.max(1.0, 0.02 * centre[c]));
        }
        // the former preview (no map): the corner well below the centre
        final DngPreview.Result before = DngPreview.read(ByteBuffer.wrap(plain.build()), W / 2, H / 2);
        final int[] c0 = pixel(before, before.width / 2, before.height / 2), k0 = pixel(before, 0, 0);
        assertTrue("corner " + k0[1] + " centre " + c0[1], k0[1] < 0.8 * c0[1]);
    }

    /**
     * P370 (review): the maps applied per output block (each CFA site's gain at its samples' mean position) render what shading
     * every sample renders, within 0.5 % (1 code) - a textured scene, the green maps of the two sites apart, at four request sizes.
     */
    @Test
    public void theBlockShadingIsTheSampleShading() throws Exception {
        final double[] n = {0.5, 1.0, 0.7};
        final byte[] ops = cornerMaps(new double[]{2.6, 2.4, 2.2});
        // the second green site's map 3 % stronger at the corners than the first's
        final ByteBuffer o = ByteBuffer.wrap(ops).order(ByteOrder.BIG_ENDIAN);
        final int second = 4 + 2 * (16 + 76 + 9 * 4) + 16 + 76;
        for (int i : new int[]{0, 2, 6, 8}) o.putFloat(second + 4 * i, o.getFloat(second + 4 * i) * 1.03f);
        final java.util.Random rnd = new java.util.Random(370);
        final int black = 64, white = 4095;
        int[] raw = new int[W * H];
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                final int c = (y & 1) == 0 ? ((x & 1) == 0 ? 0 : 1) : ((x & 1) == 0 ? 1 : 2);
                final double tex = 0.25 + 0.15 * Math.sin(x * 0.7) * Math.cos(y * 0.45) + 0.05 * rnd.nextGaussian();
                final double r2 = ((x - W / 2.0) * (x - W / 2.0) + (y - H / 2.0) * (y - H / 2.0)) / (W * W / 4.0);
                raw[y * W + x] = (int) Math.round(black + Math.max(0, tex * n[c] / (1 + 1.5 * r2)) * (white - black));
            }
        final Tiff t = baseTags(16, 1, n, black, white);
        ByteBuffer d = ByteBuffer.allocate(2 * W * H).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : raw) d.putShort((short) v);
        t.blob(0x111, 4, 1, d.array());
        t.tag(0x117, 4, 1, 2 * W * H);
        t.blob(0xC740, 7, ops.length, ops);
        final byte[] file = t.build();
        for (int div : new int[]{1, 2, 4, 8}) {
            final DngPreview.Result fast = DngPreview.read(ByteBuffer.wrap(file), W / div, H / div);
            DngPreview.Result ref;
            DngPreview.perSampleShadingForTest = true;
            try {
                ref = DngPreview.read(ByteBuffer.wrap(file), W / div, H / div);
            } finally {
                DngPreview.perSampleShadingForTest = false;
            }
            assertEquals(ref.width, fast.width);
            int worst = 0;
            for (int y = 0; y < ref.height; y++)
                for (int x = 0; x < ref.width; x++) {
                    final int[] a = pixel(ref, x, y), b = pixel(fast, x, y);
                    for (int c = 0; c < 3; c++) {
                        assertTrue("request 1/" + div + " pixel " + x + "," + y + " channel " + c + ": " + a[c] + " vs " + b[c],
                                Math.abs(a[c] - b[c]) <= Math.max(1.0, 0.005 * a[c]));
                        worst = Math.max(worst, Math.abs(a[c] - b[c]));
                    }
                }
            assertTrue(worst <= 2);
        }
    }

    /** P370 (A10): a dual-illuminant DNG at a daylight neutral renders with DngColor's matrix, not ForwardMatrix1 alone. */
    @Test
    public void aDualIlluminantDngTakesTheEditorsMatrix() throws Exception {
        final double[] n = {0.47, 1.0, 0.62};
        final Tiff t = baseTags(16, 1, n, 64, 1023);
        final double[] cm1 = {0.6892, 0.0196, -0.3223, -0.7131, 1.8376, -0.1520, -0.0884, 0.2258, 0.6427}; // A
        final double[] cm2 = {0.6178, -0.1002, -0.0970, -0.4729, 1.4118, 0.0345, -0.0861, 0.2593, 0.4441}; // D65
        final double[] fm1 = {0.7000, 0.1500, 0.1142, 0.2800, 0.8000, -0.0800, 0.0300, -0.2000, 0.9949};
        final double[] fm2 = {0.6200, 0.2500, 0.0942, 0.3000, 0.6600, 0.0400, 0.0300, 0.1000, 0.6949};
        t.blob(0xC621, 10, 9, DngRawTest.srationals(cm1));
        t.blob(0xC622, 10, 9, DngRawTest.srationals(cm2));
        t.blob(0xC714, 10, 9, DngRawTest.srationals(fm1));
        t.blob(0xC715, 10, 9, DngRawTest.srationals(fm2));
        t.blob(0xC623, 10, 9, DngRawTest.srationals(1.02, 0, 0, 0, 1, 0, 0, 0, 0.97));
        t.blob(0xC624, 10, 9, DngRawTest.srationals(0.99, 0, 0, 0, 1, 0, 0, 0, 1.03));
        t.tag(0xC65A, 3, 1, 17);
        t.tag(0xC65B, 3, 1, 21);
        final int[] raw = scene(n, 64, 1023);
        ByteBuffer d = ByteBuffer.allocate(2 * W * H).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : raw) d.putShort((short) v);
        t.blob(0x111, 4, 1, d.array());
        t.tag(0x117, 4, 1, 2 * W * H);
        final byte[] file = t.build();
        final DngPreview p = DngPreview.parse(ByteBuffer.wrap(file));
        final double[] m = p.previewMatrix(p.asShotNeutral);
        final DngColor colour = DngColor.of(p);
        final double[] want = colour.cameraToSrgb(colour.asShotNeutral);
        double change = 0;
        final double[] former = p.cameraToSrgb(p.asShotNeutral);
        for (int i = 0; i < 9; i++) {
            assertEquals("entry " + i, want[i], m[i], 1e-6);
            change = Math.max(change, Math.abs(former[i] - m[i]));
        }
        assertTrue("the ForwardMatrix1 matrix differs by " + change, change > 0.02);
        // the neutral stays white, the render grey
        final double[] w = DngColor.apply(m, n);
        for (int c = 0; c < 3; c++) assertEquals(1, w[c], 1e-3);
        assertGreyAndRed(DngPreview.read(ByteBuffer.wrap(file), W / 2, H / 2));
    }

    /** P370 (A10): a single-profile DNG (P243: CM1 + FM1, one illuminant twice) keeps its former matrix within 1e-6. */
    @Test
    public void aSingleProfileDngKeepsItsMatrix() throws Exception {
        final double[] n = {0.52, 1.0, 0.66};
        final Tiff t = baseTags(16, 1, n, 64, 1023);
        t.blob(0xC621, 10, 9, DngRawTest.srationals(0.6178, -0.1002, -0.0970, -0.4729, 1.4118, 0.0345, -0.0861, 0.2593, 0.4441));
        t.blob(0xC714, 10, 9, DngRawTest.srationals(0.6200, 0.2500, 0.0942, 0.3000, 0.6600, 0.0400, 0.0300, 0.1000, 0.6949));
        t.tag(0xC65A, 3, 1, 21);
        t.tag(0xC65B, 3, 1, 21);
        final int[] raw = scene(n, 64, 1023);
        ByteBuffer d = ByteBuffer.allocate(2 * W * H).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : raw) d.putShort((short) v);
        t.blob(0x111, 4, 1, d.array());
        t.tag(0x117, 4, 1, 2 * W * H);
        final DngPreview p = DngPreview.parse(ByteBuffer.wrap(t.build()));
        final double[] m = p.previewMatrix(p.asShotNeutral), former = p.cameraToSrgb(p.asShotNeutral);
        for (int i = 0; i < 9; i++) assertEquals("entry " + i, former[i], m[i], 1e-6);
    }

    @Test
    public void tiffMagic() {
        assertFalse(DngPreview.isTiff(new byte[]{(byte) 0xFF, (byte) 0xD8, 0, 0}, 4));
        assertTrue(DngPreview.isTiff(new byte[]{0x49, 0x49, 42, 0}, 4));
        assertTrue(DngPreview.isTiff(new byte[]{0x4D, 0x4D, 0, 42}, 4));
    }

    /** A minimal lossless JPEG encoder (predictor 1, one table: every category 0..16 has a 5-bit code). */
    static final class Lj92TestEncoder {
        static byte[] encode(int[] samples, int width, int height, int precision) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(0xFF); o.write(0xD8);
            o.write(0xFF); o.write(0xC4);
            int len = 2 + 1 + 16 + 17;
            o.write(len >> 8); o.write(len & 0xFF); o.write(0x00);
            for (int l = 1; l <= 16; ++l) o.write(l == 5 ? 17 : 0);
            for (int s = 0; s <= 16; ++s) o.write(s);
            o.write(0xFF); o.write(0xC3); o.write(0); o.write(11); o.write(precision);
            o.write(height >> 8); o.write(height & 0xFF); o.write(width >> 8); o.write(width & 0xFF); o.write(1);
            o.write(1); o.write(0x11); o.write(0);
            o.write(0xFF); o.write(0xDA); o.write(0); o.write(8); o.write(1); o.write(1); o.write(0x00); o.write(1); o.write(0); o.write(0);
            long acc = 0;
            int n = 0;
            List<Integer> bytes = new ArrayList<>();
            for (int y = 0; y < height; ++y)
                for (int x = 0; x < width; ++x) {
                    int v = samples[y * width + x];
                    int pred = y == 0 ? (x == 0 ? 1 << (precision - 1) : samples[y * width + x - 1])
                            : (x == 0 ? samples[(y - 1) * width] : samples[y * width + x - 1]);
                    int diff = (v - pred) & 0xFFFF;
                    if (diff >= 32768) diff -= 65536;
                    int s = diff == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(diff));
                    if (diff == -32768) s = 16;
                    acc = (acc << 5) | s;
                    n += 5;
                    if (s > 0 && s < 16) {
                        int bits = diff < 0 ? diff + (1 << s) - 1 : diff;
                        acc = (acc << s) | (bits & ((1 << s) - 1));
                        n += s;
                    }
                    while (n >= 8) {
                        int b = (int) ((acc >> (n - 8)) & 0xFF);
                        bytes.add(b);
                        if (b == 0xFF) bytes.add(0);
                        n -= 8;
                    }
                }
            if (n > 0) {
                int b = (int) ((acc << (8 - n)) & 0xFF) | ((1 << (8 - n)) - 1);
                bytes.add(b);
                if (b == 0xFF) bytes.add(0);
            }
            for (int b : bytes) o.write(b);
            o.write(0xFF); o.write(0xD9);
            return o.toByteArray();
        }
    }
}
