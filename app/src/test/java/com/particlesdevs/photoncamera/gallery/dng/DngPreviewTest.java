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
