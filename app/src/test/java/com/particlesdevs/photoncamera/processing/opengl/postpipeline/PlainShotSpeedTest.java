package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * P48 (research/speed/PLAIN_SHOT_SPEED.md): the faster input stage of VivoNiceRgb gives bit for bit what it replaces.
 * channelClip in parts on the shared pool against the single walk it replaced (copied below as it was), and the RGB to RGBA
 * repack of the banded upload against the RGB floats (raw bits, alpha 1.0).
 */
public class PlainShotSpeedTest {
    /** The clip statistics before P48: one walk over every 17th pixel (VivoNiceRgb.channelClip as it was). */
    private static VivoNiceRgb.ChannelClip legacyChannelClip(ByteBuffer rgb, boolean bento, float k, float usClipped, boolean flags) {
        VivoNiceRgb.ChannelClip cc = new VivoNiceRgb.ChannelClip();
        FloatBuffer f = rgb.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
        final int n = f.limit() / 3;
        final float inv = bento && k > 1f ? 1f / k : 0f;
        final int bins = VivoNiceRgb.HIST_BINS;
        final int[][] hLo = new int[3][bins], hHi = new int[3][bins];
        final double[][] sLo = new double[3][bins], sHi = new double[3][bins];
        long samples = 0;
        for (int i = 0; i < n; i += 17) {
            samples++;
            for (int c = 0; c < 3; c++) {
                final float v = f.get(i * 3 + c);
                if (v > cc.max[c]) cc.max[c] = v;
                histogram(hLo[c], sLo[c], v);
                if (inv > 0f) histogram(hHi[c], sHi[c], v * inv);
            }
        }
        final boolean useLo = !bento || flags;
        final boolean useHi = bento && k > 1f;
        StringBuilder logLo = new StringBuilder(), logHi = new StringBuilder();
        for (int c = 0; c < 3; c++) {
            float lo = 0f, hi = 0f;
            if (useLo) {
                final float s = VivoNiceRgb.plateau(hLo[c], sLo[c], samples, 0.92f, 1.03f);
                lo = Float.isNaN(s) ? 1f : s;
                logLo.append(c == 0 ? "lo=" : ",").append(lo).append(Float.isNaN(s) ? "(nominal)" : "(plateau)");
            }
            if (useHi) {
                final float s = VivoNiceRgb.plateau(hHi[c], sHi[c], samples, 0.90f, 1.08f);
                hi = !Float.isNaN(s) ? s * k : (usClipped > 0f ? k : 0f);
                logHi.append(c == 0 ? "hi=" : ",").append(hi)
                        .append(!Float.isNaN(s) ? "(plateau)" : hi > 0f ? "(nominal)" : "(off)");
            }
            cc.lo[c] = lo;
            cc.hi[c] = hi;
        }
        final String log = (logLo.length() > 0 && logHi.length() > 0 ? logLo + " " + logHi : logLo.toString() + logHi);
        if (cc.hi[1] <= 0f) java.util.Arrays.fill(cc.hi, 0f);
        else for (int c = 0; c < 3; c++) if (cc.hi[c] <= 0f) cc.hi[c] = k;
        for (int c = 0; c < 3; c++)
            if ((cc.lo[1] > 0f && cc.max[c] >= 0.85f * cc.lo[c]) || (cc.hi[1] > 0f && cc.max[c] >= 0.85f * cc.hi[c])) cc.nearClip = true;
        cc.log = log + " max=" + cc.max[0] + "," + cc.max[1] + "," + cc.max[2] + " samples=" + samples;
        return cc;
    }

    private static void histogram(int[] h, double[] sum, float t) {
        final int b = (int) Math.floor((t - VivoNiceRgb.HIST_T0) / VivoNiceRgb.HIST_BIN);
        if (b >= 0 && b < VivoNiceRgb.HIST_BINS) { h[b]++; sum[b] += t; }
    }

    private static void assertSameBits(String what, float[] expected, float[] actual) {
        assertEquals(what + " length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++)
            assertEquals(what + " [" + i + "]", Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]));
    }

    /**
     * A merged RGB: smooth content, a clip plateau just below the white of each channel (and of k for Bento), values above it,
     * negatives (signed noise), NaN and signed zeros, so every plateau and branch is taken.
     */
    private static ByteBuffer rgb(Random r, int pixels, float k, boolean plateau) {
        ByteBuffer b = ByteBuffer.allocateDirect(pixels * 12).order(ByteOrder.nativeOrder());
        FloatBuffer f = b.asFloatBuffer();
        final float[] white = {0.985f, 1.0f, 0.97f};
        for (int i = 0; i < pixels; i++) {
            final int kind = r.nextInt(100);
            for (int c = 0; c < 3; c++) {
                float v;
                if (plateau && kind < 4) v = white[c] - 0.004f * r.nextFloat();                    // the clipped mean
                else if (plateau && k > 1f && kind < 7) v = k * (white[c] - 0.003f * r.nextFloat()); // Bento's clip
                else if (kind < 9) v = 0.8f + 0.3f * r.nextFloat();                                  // real content near white
                else if (kind == 9) v = -0.01f * r.nextFloat();
                else v = r.nextFloat() * 0.9f;
                if (r.nextInt(50000) == 0) v = Float.NaN;
                if (r.nextInt(50000) == 0) v = -0.0f;
                f.put(i * 3 + c, v);
            }
        }
        return b;
    }

    @Test public void channelClipInPartsEqualsTheSingleWalk() {
        Random r = new Random(20261008);
        int[] sizes = {1, 16, 17, 18, 1000, 65536 * 17 - 1, 65536 * 17 + 5, 2_500_000};
        int checked = 0;
        for (int pixels : sizes) {
            for (int variant = 0; variant < 4; variant++) {
                final boolean bento = variant >= 2, flags = variant % 2 == 1;
                final float k = bento ? 16f : 1f;
                final ByteBuffer b = rgb(r, pixels, k, variant != 1);
                final VivoNiceRgb.ChannelClip expected = legacyChannelClip(b, bento, k, bento ? 0.001f : 0f, flags);
                for (int parts : new int[]{1, 2, 3, 8}) {
                    final VivoNiceRgb.ChannelClip got = VivoNiceRgb.channelClip(b, bento, k, bento ? 0.001f : 0f, flags, parts);
                    final String what = pixels + " px, variant " + variant + ", parts " + parts;
                    assertSameBits(what + " lo", expected.lo, got.lo);
                    assertSameBits(what + " hi", expected.hi, got.hi);
                    assertSameBits(what + " max", expected.max, got.max);
                    assertEquals(what + " nearClip", expected.nearClip, got.nearClip);
                    assertEquals(what + " log", expected.log, got.log);
                    checked++;
                }
            }
        }
        assertEquals(sizes.length * 4 * 4, checked);
    }

    @Test public void parallelReadGivesTheFileBytes() throws Exception {
        java.io.File file = java.io.File.createTempFile("p48-read", ".bin");
        file.deleteOnExit();
        Random r = new Random(48);
        byte[] bytes = new byte[3 * 1024 * 1024 + 4321];
        r.nextBytes(bytes);
        java.nio.file.Files.write(file.toPath(), bytes);
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file.toPath())) {
            // short reads (at most 1000 bytes per call), as a pread may return
            final VivoNeuralClient.PositionalReader reader = (d, at) -> {
                ByteBuffer part = d.duplicate();
                part.limit(Math.min(d.limit(), d.position() + 1000));
                final int n = channel.read(part, at);
                if (n > 0) d.position(d.position() + n);
                return n;
            };
            for (int part : new int[]{1 << 20, 4096, 7, VivoNeuralClient.READ_PART}) {
                for (int[] range : new int[][]{{0, bytes.length}, {12345, 2_000_000}, {bytes.length - 10, 10}}) {
                    final int from = range[0], length = range[1];
                    ByteBuffer dst = ByteBuffer.allocateDirect(length + 33);
                    dst.position(33);
                    VivoNeuralClient.readParallel(reader, dst, from, part);
                    assertEquals(length + 33, dst.position());
                    byte[] got = new byte[length];
                    dst.position(33);
                    dst.get(got);
                    assertArrayEquals("part " + part + " from " + from, java.util.Arrays.copyOfRange(bytes, from, from + length), got);
                }
            }
            ByteBuffer beyond = ByteBuffer.allocateDirect(100);
            try {
                VivoNeuralClient.readParallel(reader, beyond, bytes.length - 50, 16);
                org.junit.Assert.fail("a read past the end must fail");
            } catch (java.io.EOFException expected) {
                // as the sequential read: an incomplete result
            }
        }
    }

    @Test public void rgbaRepackKeepsEveryBit() {
        Random r = new Random(7);
        for (int[] wh : new int[][]{{1, 1}, {3, 5}, {17, 9}, {8160, 4}, {1021, 37}}) {
            final int w = wh[0], h = wh[1];
            ByteBuffer src = ByteBuffer.allocateDirect(w * h * 12).order(ByteOrder.nativeOrder());
            IntBuffer s = src.asIntBuffer();
            for (int i = 0; i < w * h * 3; i++) {
                int bits = r.nextInt();
                if (i % 97 == 0) bits = 0x7fc00001;          // a NaN with a payload
                if (i % 101 == 0) bits = Float.floatToRawIntBits(-0.0f);
                s.put(i, bits);
            }
            for (int y0 = 0; y0 < h; y0 += Math.max(1, h / 3)) {
                final int rows = Math.min(Math.max(1, h / 3), h - y0);
                ByteBuffer dst = ByteBuffer.allocateDirect(rows * w * 16).order(ByteOrder.nativeOrder());
                dst.position(rows * w * 8); // the repack writes from the start whatever the position
                VivoNiceRgb.rgbToRgbaRows(src, w, y0, rows, dst);
                ByteBuffer view = dst.duplicate().order(ByteOrder.nativeOrder());
                view.position(0);
                IntBuffer d = view.asIntBuffer();
                int[] expected = new int[rows * w * 4], got = new int[rows * w * 4];
                for (int y = 0; y < rows; y++)
                    for (int x = 0; x < w; x++) {
                        final int o = (y * w + x) * 4, i = ((y0 + y) * w + x) * 3;
                        expected[o] = s.get(i); expected[o + 1] = s.get(i + 1); expected[o + 2] = s.get(i + 2);
                        expected[o + 3] = Float.floatToRawIntBits(1f);
                    }
                d.get(got);
                assertArrayEquals(w + "x" + h + " rows " + y0 + "+" + rows, expected, got);
            }
        }
    }
}
