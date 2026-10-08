package com.particlesdevs.photoncamera.processing.ml;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/**
 * The 10-bit port of the native Lanczos (tools/check_lanczos_downscale.py checks the native one the same way): a dense
 * float64 reference in linear light, flat levels kept exactly, the alias suppression of a checkerboard, and the same
 * output for any number of threads.
 */
public class Lanczos1010102Test {
    private static int pack(int r, int g, int b) {
        return (3 << 30) | (b << 20) | (g << 10) | r;
    }

    private static double decode(int v) {
        final double x = v / 1023.0;
        return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
    }

    private static int encode(double l) {
        l = Math.max(0, Math.min(1, l));
        return (int) Math.round(1023 * (l <= 0.0031308 ? 12.92 * l : 1.055 * Math.pow(l, 1 / 2.4) - 0.055));
    }

    /** Dense weights of the native code's formula, normalised per output sample (float64). */
    private static double[][] matrix(int n, int m, int a) {
        final double[][] w = new double[m][n];
        for (int i = 0; i < m; i++) {
            double sum = 0;
            for (int j = 0; j < n; j++) {
                final double x = j - ((i + 0.5) * n / m - 0.5);
                final double z = x * m / n;
                final double v = Math.abs(z) < a ? sinc(z) * sinc(z / a) : 0;
                w[i][j] = v;
                sum += v;
            }
            for (int j = 0; j < n; j++) w[i][j] /= sum;
        }
        return w;
    }

    private static double sinc(double x) {
        return Math.abs(x) < 1e-12 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
    }

    private static int[] reference(int[] src, int sw, int sh, int dw, int dh, int a) {
        final double[][] wx = matrix(sw, dw, a), wy = matrix(sh, dh, a);
        final int[] out = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                final double[] acc = new double[3];
                for (int sy = 0; sy < sh; sy++) {
                    if (wy[y][sy] == 0) continue;
                    for (int sx = 0; sx < sw; sx++) {
                        final double w = wy[y][sy] * wx[x][sx];
                        if (w == 0) continue;
                        final int p = src[sy * sw + sx];
                        acc[0] += w * decode(p & 0x3FF);
                        acc[1] += w * decode((p >>> 10) & 0x3FF);
                        acc[2] += w * decode((p >>> 20) & 0x3FF);
                    }
                }
                out[y * dw + x] = pack(encode(acc[0]), encode(acc[1]), encode(acc[2]));
            }
        }
        return out;
    }

    private static int[] resize(int[] src, int sw, int sh, int dw, int dh, int a, int threads) throws Exception {
        final int[] out = new int[dw * dh];
        java.util.Arrays.fill(out, 0x5A5A5A5A);
        Lanczos1010102.resize(() -> (y, rows, o, offset) -> System.arraycopy(src, y * sw, o, offset, rows * sw), sw, sh,
                () -> (y, rows, in, offset) -> System.arraycopy(in, offset, out, y * dw, rows * dw), dw, dh, a, threads);
        return out;
    }

    private static int maxError(int[] a, int[] b) {
        int max = 0;
        for (int i = 0; i < a.length; i++)
            for (int s = 0; s < 30; s += 10) max = Math.max(max, Math.abs(((a[i] >>> s) & 0x3FF) - ((b[i] >>> s) & 0x3FF)));
        return max;
    }

    @Test
    public void matchesTheDenseReference() throws Exception {
        final Random rnd = new Random(731);
        final int[][] sizes = {{37, 29, 19, 11}, {44, 32, 11, 8}, {1, 11, 1, 4}, {19, 1, 7, 1}, {8, 8, 2, 2}, {61, 45, 40, 30}};
        for (int a = 2; a <= 5; a++) {
            for (int[] s : sizes) {
                final int[] src = new int[s[0] * s[1]];
                for (int i = 0; i < src.length; i++) src[i] = pack(rnd.nextInt(1024), rnd.nextInt(1024), rnd.nextInt(1024));
                final int[] got = resize(src, s[0], s[1], s[2], s[3], a, 1);
                final int[] expected = reference(src, s[0], s[1], s[2], s[3], a);
                assertTrue("a=" + a + " " + s[0] + "x" + s[1] + " -> " + s[2] + "x" + s[3], maxError(got, expected) <= 1);
                for (int p : got) assertEquals("alpha 3", 3, p >>> 30);
            }
        }
    }

    @Test
    public void flatLevelsStayExact() throws Exception {
        for (int a = 2; a <= 5; a++) {
            for (int level : new int[]{0, 1, 2, 37, 128, 511, 512, 700, 1022, 1023}) {
                final int[] src = new int[47 * 31];
                java.util.Arrays.fill(src, pack(level, level, level));
                for (int p : resize(src, 47, 31, 19, 13, a, 1)) assertEquals("a=" + a + " level " + level, pack(level, level, level), p);
            }
        }
    }

    @Test
    public void checkerboardIsAveragedNotAliased() throws Exception {
        final int[] src = new int[64 * 64];
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++) src[y * 64 + x] = ((x + y) & 1) == 0 ? pack(0, 0, 0) : pack(1023, 1023, 1023);
        final int expected = encode(0.5); // half the linear light: 10-bit sRGB code of 0.5 (the 8-bit check expects 188)
        for (int a = 2; a <= 5; a++) {
            final int[] out = resize(src, 64, 64, 16, 16, a, 1);
            for (int y = 3; y < 13; y++)
                for (int x = 3; x < 13; x++) assertEquals("a=" + a, expected, out[y * 16 + x] & 0x3FF, 2);
        }
        assertEquals(188, Math.round(encode(0.5) * 255 / 1023.0), 1);
    }

    @Test
    public void threadsGiveTheSameImage() throws Exception {
        final Random rnd = new Random(5);
        final int sw = 203, sh = 157, dw = 101, dh = 78;
        final int[] src = new int[sw * sh];
        for (int i = 0; i < src.length; i++) src[i] = pack(rnd.nextInt(1024), rnd.nextInt(1024), rnd.nextInt(1024));
        final int[] one = resize(src, sw, sh, dw, dh, 3, 1);
        assertArrayEquals(one, resize(src, sw, sh, dw, dh, 3, 4));
        assertArrayEquals(one, resize(src, sw, sh, dw, dh, 3, 7));
    }

    @Test
    public void geometryIsValidated() {
        for (int[] g : new int[][]{{8, 8, 1, 1, 3}, {8, 8, 9, 8, 3}, {8, 8, 2, 2, 6}, {8, 8, 2, 2, 1}}) {
            try {
                resize(new int[64], g[0], g[1], g[2], g[3], g[4], 1);
                fail("accepted " + java.util.Arrays.toString(g));
            } catch (IllegalArgumentException expected) {
                // as the native resize: <= 4:1, downscale only, 2..5 lobes
            } catch (Exception e) {
                fail(e.toString());
            }
        }
    }
}
