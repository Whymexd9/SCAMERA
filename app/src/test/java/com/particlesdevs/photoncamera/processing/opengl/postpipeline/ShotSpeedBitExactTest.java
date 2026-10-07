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

    /** Block statistics as lmcdn/stats returns them, with ties (quantised values), signed zeros and invalid blocks. */
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
                        LmcDenoiseTables.reduceLegacy(b, count, 0.8f), LmcDenoiseTables.reduce(b, count, 0.8f));
            }
        }
        // Fewer than 16 valid blocks: null in both.
        float[] few = blocks(new Random(1), 10, 0);
        assertNull(LmcDenoiseTables.reduce(few, 10, 0.8f));
        assertNull(LmcDenoiseTables.reduceLegacy(few, 10, 0.8f));
    }

    @Test public void sortableBitsKeepTheFloatCompareOrder() {
        float[] v = {Float.NEGATIVE_INFINITY, -Float.MAX_VALUE, -1f, -Float.MIN_NORMAL, -Float.MIN_VALUE, -0.0f, 0.0f,
                Float.MIN_VALUE, Float.MIN_NORMAL, 1f, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NaN};
        for (float a : v) for (float b : v)
            assertEquals(a + " vs " + b, Integer.signum(Float.compare(a, b)),
                    Integer.signum(Integer.compare(LmcDenoiseTables.sortableBits(a), LmcDenoiseTables.sortableBits(b))));
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

    private static void assertSameResult(ArkAe.Result expected, ArkAe.Result actual) throws IllegalAccessException {
        assertEquals(expected.describe(), actual.describe());
        for (Field f : ArkAe.Result.class.getDeclaredFields()) {
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
            ArkAe.Settings s = new ArkAe.Settings();
            ArkAe.Result fromFloats = ArkAe.smartHdr(fs, 4, w, h, s, 200, 6400);
            ArkAe.Result fromHalves = ArkAe.smartHdrHalf(hs, 4, w, h, s, 200, 6400);
            assertSameResult(fromFloats, fromHalves);
        }
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
            assertEquals("size " + size, expectedCount, LmcDenoise.effHistogram(eff, got));
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
}
