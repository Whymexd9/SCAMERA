package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.util.ParallelWork;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Random;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Shot speed, wave 1: the faster CPU pieces of the post pipeline give bit for bit the results of the code they replace
 * (research/speed/SHOT_SPEED_PLAN.md W1.6). Compared on the host, element by element as raw float bits.
 */
public class ShotSpeedBitExactTest {
    private static void assertSameBits(String what, float[] expected, float[] actual) {
        if (expected == null || actual == null) {
            assertEquals(what, expected == null, actual == null);
            return;
        }
        assertEquals(what + " length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++)
            assertEquals(what + " [" + i + "]", Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]));
    }

    /** Block statistics as scamdn/stats returns them, with ties (quantised values), signed zeros and invalid blocks. */
    private static float[] blocks(Random r, int count, int levels) {
        float[] b = new float[8 * count];
        for (int i = 0; i < count; i++) {
            int o = 8 * i;
            b[o] = levels > 0 ? (1 + r.nextInt(levels)) / (float) levels * 0.7f : r.nextFloat() * 0.9f;   // mean Y (some above clip)
            float t = levels > 0 ? r.nextInt(levels) / (float) levels : (float) r.nextGaussian();
            if (r.nextInt(17) == 0) t = r.nextBoolean() ? -0.0f : 0.0f;
            b[o + 1] = t;                                                                   // texture, ties and signed zeros
            for (int k = 2; k < 8; k++) b[o + k] = levels > 0 ? (1 + r.nextInt(levels)) * 1e-4f : r.nextFloat() * 1e-3f;
            switch (r.nextInt(40)) {
                case 0: b[o + 3] = Float.NaN; break;
                case 1: b[o + 5] = Float.POSITIVE_INFINITY; break;
                case 2: b[o] = 0f; break;
                case 3: b[o + 2] = -1f; break;
                default: break;
            }
        }
        return b;
    }

    @Test public void primitiveSortReduceEqualsTheBoxedOne() {
        Random r = new Random(20261007);
        int[] counts = {0, 5, 15, 16, 17, 40, 63, 64, 65, 200, 1000, 49152};
        for (int count : counts) {
            for (int levels : new int[]{0, 3, 16, 256}) {
                float[] b = blocks(r, count, levels);
                assertSameBits("count " + count + " levels " + levels,
                        ScamDenoiseTables.reduceLegacy(b, count, 0.8f), ScamDenoiseTables.reduce(b, count, 0.8f));
            }
        }
        // Fewer than 16 valid blocks: null in both.
        float[] few = blocks(new Random(1), 10, 0);
        assertNull(ScamDenoiseTables.reduce(few, 10, 0.8f));
        assertNull(ScamDenoiseTables.reduceLegacy(few, 10, 0.8f));
    }

    /** P364: the selections give the sorted array's elements (medians, the n/4 noise element, the m lowest keys) bit for bit. */
    @Test public void selectionsEqualTheSorts() {
        Random r = new Random(20261027);
        float[] special = {-0.0f, 0.0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MIN_VALUE};
        for (int n = 1; n <= 300; n += (n < 40 ? 1 : 37)) {
            for (int kind = 0; kind < 4; kind++) {
                float[] a = new float[n];
                for (int i = 0; i < n; i++) {
                    a[i] = kind == 0 ? r.nextFloat() : kind == 1 ? r.nextInt(5) * 0.25f : kind == 2 ? (float) r.nextGaussian()
                            : r.nextInt(4) == 0 ? special[r.nextInt(special.length)] : r.nextInt(3) - 1f;
                }
                float[] keep = a.clone();
                assertEquals("median n " + n + " kind " + kind, Float.floatToIntBits(ScamDenoiseTables.median(a, n)),
                        Float.floatToIntBits(ScamDenoiseTables.medianSelect(a, n)));
                float[] sorted = a.clone();
                java.util.Arrays.sort(sorted);
                for (int k : new int[]{0, n / 4, n / 2, n - 1})
                    assertEquals("select n " + n + " k " + k, Float.floatToIntBits(sorted[k]),
                            Float.floatToIntBits(ScamDenoiseTables.selectFloat(a, n, k)));
                assertSameBits("input unchanged", keep, a);
                long[] keys = new long[n];
                for (int i = 0; i < n; i++) keys[i] = ((long) ScamDenoiseTables.sortableBits(a[i]) << 32) | i;
                long[] all = keys.clone();
                java.util.Arrays.sort(all);
                int m = Math.max(1, n / 4);
                ScamDenoiseTables.selectSmallest(keys, n, m);
                java.util.Arrays.sort(keys, 0, m);
                for (int i = 0; i < m; i++) assertEquals("smallest n " + n + " i " + i, all[i], keys[i]);
            }
        }
    }

    @Test public void sortableBitsKeepTheFloatCompareOrder() {
        float[] v = {Float.NEGATIVE_INFINITY, -Float.MAX_VALUE, -1f, -Float.MIN_NORMAL, -Float.MIN_VALUE, -0.0f, 0.0f,
                Float.MIN_VALUE, Float.MIN_NORMAL, 1f, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NaN};
        for (float a : v) for (float b : v)
            assertEquals(a + " vs " + b, Integer.signum(Float.compare(a, b)),
                    Integer.signum(Integer.compare(ScamDenoiseTables.sortableBits(a), ScamDenoiseTables.sortableBits(b))));
    }

    /** An independent IEEE half decoder (Math.scalb), the reference of the read-back table. */
    private static float halfReference(int h) {
        int sign = (h >> 15) & 1, exp = (h >> 10) & 31, man = h & 1023;
        float v;
        if (exp == 0) v = Math.scalb((float) man, -24);
        else if (exp == 31) v = man == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        else v = Math.scalb((float) (1024 + man), exp - 25);
        return sign != 0 ? -v : v;
    }

    @Test public void halfTableWidensEveryHalfExactly() {
        for (int h = 0; h < 65536; h++) {
            float ref = halfReference(h);
            float got = HalfFloat.TABLE[h];
            if (Float.isNaN(ref)) assertTrue("half " + h, Float.isNaN(got));
            else assertEquals("half " + h, Float.floatToRawIntBits(ref), Float.floatToRawIntBits(got));
        }
    }

    /** Float to the nearest IEEE half (round to nearest even), enough to make test data that RGBA16F can hold. */
    private static short toHalf(float f) {
        int bits = Float.floatToIntBits(f), sign = (bits >>> 16) & 0x8000;
        float a = Math.abs(f);
        if (Float.isNaN(f)) return (short) (sign | 0x7e00);
        if (a >= 65520f) return (short) (sign | 0x7c00);
        if (a < Math.scalb(1f, -25)) return (short) sign;
        int e = Math.getExponent(a);
        if (e < -14) return (short) (sign | Math.round(Math.scalb(a, 24)));
        int m = Math.round(Math.scalb(a, 10 - e)) - 1024;
        if (m == 1024) { m = 0; e++; }
        return (short) (sign | ((e + 15) << 10) | m);
    }

    private static void assertSameResult(WhyAe.Result expected, WhyAe.Result actual) throws IllegalAccessException {
        assertEquals(expected.describe(), actual.describe());
        for (Field f : WhyAe.Result.class.getDeclaredFields()) {
            if (f.getType() == float.class)
                assertEquals(f.getName(), Float.floatToRawIntBits(f.getFloat(expected)), Float.floatToRawIntBits(f.getFloat(actual)));
            else if (f.getType() == boolean.class) assertEquals(f.getName(), f.getBoolean(expected), f.getBoolean(actual));
            else if (f.getType() == int.class) assertEquals(f.getName(), f.getInt(expected), f.getInt(actual));
        }
    }

    @Test public void smartHdrOnHalvesEqualsSmartHdrOnTheirFloats() throws IllegalAccessException {
        Random r = new Random(7);
        int w = 96, h = 64;
        for (int scene = 0; scene < 4; scene++) {
            ByteBuffer halves = ByteBuffer.allocateDirect(w * h * 4 * 2).order(ByteOrder.nativeOrder());
            ByteBuffer floats = ByteBuffer.allocateDirect(w * h * 4 * 4).order(ByteOrder.nativeOrder());
            ShortBuffer hs = halves.asShortBuffer();
            FloatBuffer fs = floats.asFloatBuffer();
            for (int i = 0; i < w * h * 4; i++) {
                float v;
                switch (scene) {
                    case 0: v = r.nextFloat() * 0.5f; break;                                 // daylight
                    case 1: v = (float) Math.pow(r.nextFloat(), 6) * 0.02f; break;           // night, subnormal halves
                    case 2: v = r.nextFloat() * 3.5f; break;                                 // Bento range above 1
                    default: v = r.nextInt(50) == 0 ? Float.NaN : r.nextInt(70) == 0 ? Float.POSITIVE_INFINITY : r.nextFloat(); break;
                }
                short half = toHalf(v);
                hs.put(i, half);
                fs.put(i, HalfFloat.TABLE[half & 0xffff]);
            }
            WhyAe.Settings s = new WhyAe.Settings();
            WhyAe.Result fromFloats = WhyAe.smartHdr(fs, 4, w, h, s, 200, 6400);
            WhyAe.Result fromHalves = WhyAe.smartHdrHalf(hs, 4, w, h, s, 200, 6400);
            assertSameResult(fromFloats, fromHalves);
        }
    }

    /** Read-backs of the 65536-half probe texture as a driver might return them (exact unless changed by the caller). */
    private static ShortBuffer probeHalves() {
        ShortBuffer s = ByteBuffer.allocateDirect(65536 * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        for (int h = 0; h < 65536; h++) s.put(h, (short) h);
        return s;
    }
    private static FloatBuffer probeFloats() {
        FloatBuffer f = ByteBuffer.allocateDirect(65536 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int h = 0; h < 65536; h++) f.put(h, HalfFloat.TABLE[h]);
        return f;
    }

    @Test public void halfReadbackCheckAcceptsOnlyTheExactWidening() {
        // A driver that widens exactly; NaNs may come back with other payloads (canonical NaN) both ways.
        ShortBuffer halves = probeHalves();
        FloatBuffer floats = probeFloats();
        for (int h = 0x7c01; h < 0x7c10; h++) { halves.put(h, (short) 0x7e00); floats.put(h, Float.intBitsToFloat(0x7fc00000)); }
        assertEquals(-1, WhyStats.firstHalfMismatch(halves, floats));

        // GL_FLOAT conversion flushing denormal halves to zero (ES allows it): the first positive denormal is found.
        floats = probeFloats();
        for (int h = 1; h < 0x400; h++) { floats.put(h, 0f); floats.put(0x8000 | h, -0f); }
        assertEquals(1, WhyStats.firstHalfMismatch(probeHalves(), floats));

        // Only the negative ones flushed, to +0, and the sign of -0 lost: the first one in pattern order.
        floats = probeFloats();
        floats.put(0x8000, 0f);
        floats.put(0x8001, 0f);
        assertEquals(0x8000, WhyStats.firstHalfMismatch(probeHalves(), floats));

        // The half read-back itself changed a pattern (flushed on upload or read): not accepted either.
        halves = probeHalves();
        halves.put(0x0123, (short) 0);
        assertEquals(0x0123, WhyStats.firstHalfMismatch(halves, probeFloats()));

        // A NaN read back as a number, or a number read back as NaN.
        floats = probeFloats();
        floats.put(0x7e00, 0f);
        assertEquals(0x7e00, WhyStats.firstHalfMismatch(probeHalves(), floats));
        floats = probeFloats();
        floats.put(0x3c00, Float.NaN);
        assertEquals(0x3c00, WhyStats.firstHalfMismatch(probeHalves(), floats));

        // Infinities must stay infinities.
        floats = probeFloats();
        floats.put(0xfc00, -Float.MAX_VALUE);
        assertEquals(0xfc00, WhyStats.firstHalfMismatch(probeHalves(), floats));
    }

    @Test public void chunkedEffHistogramEqualsTheStrideLoop() {
        Random r = new Random(3);
        for (int size : new int[]{1, 6, 7, 8, 100, 65533, 65534, 65535, 65541, 200_000, 1_000_003}) {
            ByteBuffer eff = ByteBuffer.allocateDirect(size);
            for (int i = 0; i < size; i++) eff.put(i, (byte) (r.nextInt(4) == 0 ? 0 : r.nextInt(256)));
            int[] expected = new int[256];
            long expectedCount = 0;
            for (int i = 0; i < eff.capacity(); i += 7) {
                int v = eff.get(i) & 255;
                if (v > 0) { expected[v]++; expectedCount++; }
            }
            int[] got = new int[256];
            assertEquals("size " + size, expectedCount, ScamDenoise.effHistogram(eff, got));
            for (int v = 0; v < 256; v++) assertEquals("size " + size + " code " + v, expected[v], got[v]);
            assertEquals(0, eff.position());
        }
    }

    @Test public void parallelWorkRunsEveryIndexOnceAndRethrows() {
        for (int n : new int[]{0, 1, 2, 7, 8, 9, 1000}) {
            AtomicIntegerArray seen = new AtomicIntegerArray(Math.max(1, n));
            ParallelWork.forEach(n, i -> seen.incrementAndGet(i));
            for (int i = 0; i < n; i++) assertEquals("n " + n + " index " + i, 1, seen.get(i));
        }
        try {
            ParallelWork.forEach(100, i -> { if (i == 37) throw new IllegalStateException("item 37"); });
            fail("no exception");
        } catch (IllegalStateException e) {
            assertNotNull(e.getMessage());
        }
    }

    /** The level block grids of ScamDenoise.process for a level-0 size (x0, then Y[L] and XC[L] for L = 1..3, 16 px blocks). */
    private static int[][] statsGrids(int w0, int h0) {
        int[] w = new int[7], h = new int[7];
        int lw = w0, lh = h0;
        w[0] = Math.max(1, lw / 16);
        h[0] = Math.max(1, lh / 16);
        for (int L = 1; L < 4; L++) {
            lw = (lw + 1) / 2;
            lh = (lh + 1) / 2;
            w[2 * L - 1] = w[2 * L] = Math.max(1, lw / 16);
            h[2 * L - 1] = h[2 * L] = Math.max(1, lh / 16);
        }
        return new int[][]{w, h};
    }

    /** P364: the statistics atlas holds every level's rectangle inside it, none overlapping another. */
    @Test public void statsAtlasRectanglesAreDisjointAndInside() {
        int[][] sizes = {{4096, 3072}, {4000, 3000}, {4031, 3023}, {3072, 4096}, {8160, 6120}, {6144, 8192}, {100, 60}, {17, 15},
                {1, 1}, {33, 2000}, {2000, 33}};
        for (int[] sz : sizes) {
            int[][] g = statsGrids(sz[0], sz[1]);
            int[] w = g[0], h = g[1], x = new int[7], y = new int[7];
            int[] atlas = ScamDenoise.statsAtlas(w, h, x, y);
            for (int i = 0; i < 7; i++) {
                String what = sz[0] + "x" + sz[1] + " level " + i;
                assertTrue(what, x[i] >= 0 && y[i] >= 0 && x[i] + w[i] <= atlas[0] && y[i] + h[i] <= atlas[1]);
                for (int j = 0; j < i; j++)
                    assertTrue(what + " vs " + j, x[i] + w[i] <= x[j] || x[j] + w[j] <= x[i] || y[i] + h[i] <= y[j] || y[j] + h[j] <= y[i]);
            }
            assertEquals(0, x[0]);
            assertEquals(0, y[0]);
        }
        // 12 MP: 256 x 192 blocks of level 0 over two shelves of 128 x 96 and 64 x 48 / 32 x 24 rectangles
        int[] atlas = ScamDenoise.statsAtlas(statsGrids(4096, 3072)[0], statsGrids(4096, 3072)[1], new int[7], new int[7]);
        assertEquals(256, atlas[0]);
        assertEquals(336, atlas[1]);
    }

    /** P364: a level's statistics from the atlas are the floats readStats gave from a texture of its own, bit for bit. */
    @Test public void atlasStatisticsEqualTheReadStatsDecode() {
        Random r = new Random(364);
        int[][] g = statsGrids(1000, 700);
        int[] w = g[0], h = g[1], x = new int[7], y = new int[7];
        int[] atlas = ScamDenoise.statsAtlas(w, h, x, y);
        int[] texels = new int[atlas[0] * atlas[1] * 4];
        for (int i = 0; i < texels.length; i++) {
            int lo = r.nextInt(65536), hi = r.nextInt(65536);
            if (r.nextInt(9) == 0) lo = new int[]{0x7c00, 0xfc00, 0x7e00, 0x8000, 0x0001, 0x83ff, 0xbc00}[r.nextInt(7)];
            texels[i] = (hi << 16) | lo;
        }
        for (int level = 0; level < 7; level++) {
            // readStats: the level's own w x h texture, row-major from y = 0, ScamDenoiseTables.half of each 16-bit half
            float[] expected = new float[w[level] * h[level] * 8];
            for (int by = 0; by < h[level]; by++) {
                for (int bx = 0; bx < w[level]; bx++) {
                    for (int c = 0; c < 4; c++) {
                        int v = texels[((y[level] + by) * atlas[0] + x[level] + bx) * 4 + c];
                        int i = (by * w[level] + bx) * 4 + c;
                        expected[2 * i] = ScamDenoiseTables.half(v & 0xffff);
                        expected[2 * i + 1] = ScamDenoiseTables.half((v >>> 16) & 0xffff);
                    }
                }
            }
            float[] got = ScamDenoise.statsOf(texels, atlas[0], x[level], y[level], w[level], h[level]);
            assertEquals("level " + level + " length", expected.length, got.length);
            for (int i = 0; i < expected.length; i++) {
                if (Float.isNaN(expected[i])) assertTrue("level " + level + " [" + i + "]", Float.isNaN(got[i]));
                else assertEquals("level " + level + " [" + i + "]", Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(got[i]));
            }
        }
    }

    /** P364: ScamDenoise.process takes p50 / p90 of the level-0 block means by selection: the elements of the sort of before. */
    @Test public void meanPercentilesBySelectionEqualTheSort() {
        Random r = new Random(9);
        for (int n : new int[]{1, 2, 3, 9, 10, 11, 100, 1001, 49152}) {
            for (int kind = 0; kind < 3; kind++) {
                float[] ys = new float[n];
                for (int i = 0; i < n; i++)
                    ys[i] = kind == 0 ? r.nextFloat() : kind == 1 ? r.nextInt(7) * 0.125f : (r.nextInt(5) == 0 ? (r.nextBoolean() ? -0.0f : 0.0f) : r.nextFloat() * 1e-3f);
                float[] sorted = ys.clone();
                java.util.Arrays.sort(sorted, 0, n);
                int k50 = Math.min(n - 1, n / 2), k90 = Math.min(n - 1, (int) (n * 0.9f));
                assertEquals("p50 n " + n, Float.floatToRawIntBits(sorted[k50]), Float.floatToRawIntBits(ScamDenoiseTables.selectFloat(ys, n, k50)));
                assertEquals("p90 n " + n, Float.floatToRawIntBits(sorted[k90]), Float.floatToRawIntBits(ScamDenoiseTables.selectFloat(ys, n, k90)));
            }
        }
    }
}
