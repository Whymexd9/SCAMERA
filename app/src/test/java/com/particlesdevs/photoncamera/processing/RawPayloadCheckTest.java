package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/** RawPayloadCheck against the vivo X100 Ultra case: packed MIPI RAW10 behind RAW_SENSOR with RAW16 metadata. */
public class RawPayloadCheckTest {
    private static final int W = 512, H = 384, STRIDE = W * 2, BLACK = 64, WHITE = 1023;

    /** A 10-bit Bayer scene (black 64, white 1023): gradient, texture and noise, scaled to {@code gain}. */
    private static int[][] scene(double gain, long seed) {
        Random r = new Random(seed);
        int[][] v = new int[H][W];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double s = gain * (200 + 500.0 * x / W + 150 * Math.sin(x * 0.09) * Math.cos(y * 0.07)) + r.nextGaussian() * 3;
            v[y][x] = (int) Math.max(0, Math.min(WHITE, Math.round(BLACK + s)));
        }
        return v;
    }

    private static ByteBuffer plain(int[][] v, int scale) {
        ByteBuffer b = ByteBuffer.allocateDirect(STRIDE * H).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) b.putShort(y * STRIDE + 2 * x, (short) (v[y][x] * scale));
        return b;
    }

    /** MIPI RAW10 rows (5 bytes per 4 px) written one after another from offset 0 into a RAW16-sized buffer. */
    private static ByteBuffer packedMipi10(int[][] v, ByteBuffer into) {
        int at = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x += 4) {
            int p0 = v[y][x], p1 = v[y][x + 1], p2 = v[y][x + 2], p3 = v[y][x + 3];
            into.put(at++, (byte) (p0 >> 2)); into.put(at++, (byte) (p1 >> 2));
            into.put(at++, (byte) (p2 >> 2)); into.put(at++, (byte) (p3 >> 2));
            into.put(at++, (byte) ((p3 & 3) << 6 | (p2 & 3) << 4 | (p1 & 3) << 2 | (p0 & 3)));
        }
        return into;
    }

    /** MIPI RAW10 rows {@code stride} bytes apart, the padding after each row filled with {@code pad}. */
    private static ByteBuffer packedMipi10Strided(int[][] v, int stride, int pad) {
        ByteBuffer into = zeroed();
        for (int y = 0; y < H; y++) {
            int at = y * stride;
            for (int x = 0; x < W; x += 4) {
                int p0 = v[y][x], p1 = v[y][x + 1], p2 = v[y][x + 2], p3 = v[y][x + 3];
                into.put(at++, (byte) (p0 >> 2)); into.put(at++, (byte) (p1 >> 2));
                into.put(at++, (byte) (p2 >> 2)); into.put(at++, (byte) (p3 >> 2));
                into.put(at++, (byte) ((p3 & 3) << 6 | (p2 & 3) << 4 | (p1 & 3) << 2 | (p0 & 3)));
            }
            for (int i = W * 5 / 4; i < stride; i++) into.put(y * stride + i, (byte) pad);
        }
        return into;
    }

    private static ByteBuffer zeroed() {
        return ByteBuffer.allocateDirect(STRIDE * H).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    public void plainTenBitFrameIsPlain() {
        RawPayloadCheck.Result r = RawPayloadCheck.check(plain(scene(1, 1), 1), W, H, STRIDE, WHITE);
        assertTrue(r.error, r.plain());
        assertEquals(0f, r.impossibleShare, 0f);
    }

    @Test
    public void packedMipi10WithZeroTailIsRejected() {
        RawPayloadCheck.Result r = RawPayloadCheck.check(packedMipi10(scene(1, 2), zeroed()), W, H, STRIDE, WHITE);
        assertFalse(r.plain());
        assertNotNull(r.error);
        // As on the X100 Ultra: 5/8 of the rows carry packed bytes, 3/8 are never written.
        assertEquals(0.625f, r.impossibleShare, 0.03f);
        assertEquals(0.375f, r.zeroRowShare, 0.04f);
    }

    @Test
    public void packedDarkSceneIsRejected() {
        RawPayloadCheck.Result r = RawPayloadCheck.check(packedMipi10(scene(0.02, 3), zeroed()), W, H, STRIDE, WHITE);
        assertFalse(r.error, r.plain());
    }

    @Test
    public void packedFrameOverAStaleTailIsRejected() {
        // A recycled reader buffer: the rows past the packed payload still hold an older plain frame.
        RawPayloadCheck.Result r = RawPayloadCheck.check(packedMipi10(scene(1, 4), plain(scene(1, 5), 1)), W, H, STRIDE, WHITE);
        assertFalse(r.plain());
        assertEquals(0f, r.zeroRowShare, 0f);
    }

    @Test
    public void twelveBitDataUnderATenBitWhiteStaysPlain() {
        // A HAL that reports 1023 for 12-bit data: wrong white, but a plain payload the existing white fixes handle.
        assertTrue(RawPayloadCheck.check(plain(scene(1, 6), 4), W, H, STRIDE, WHITE).plain());
    }

    @Test
    public void sixteenBitRangesAreNotChecked() {
        assertTrue(RawPayloadCheck.check(packedMipi10(scene(1, 7), zeroed()), W, H, STRIDE, 16383).plain());
        assertTrue(RawPayloadCheck.check(packedMipi10(scene(1, 7), zeroed()), W, H, STRIDE, 0).plain());
    }

    @Test
    public void blackSubtractedFrameWithZeroRowsStaysPlain() {
        int[][] v = scene(1, 8);
        for (int y = 0; y < H * 2 / 5; y++) java.util.Arrays.fill(v[y], 0); // night sky clamped at black
        RawPayloadCheck.Result r = RawPayloadCheck.check(plain(v, 1), W, H, STRIDE, WHITE);
        assertTrue(r.error, r.plain());
        assertTrue(r.zeroRowShare > 0.3f);
    }

    @Test
    public void geometryThatCannotHoldItsRowsIsRejected() {
        assertFalse(RawPayloadCheck.check(zeroed(), W, H, W, WHITE).plain()); // rowStride < 2 * width
        ByteBuffer small = ByteBuffer.allocateDirect(STRIDE * (H - 1)).order(ByteOrder.LITTLE_ENDIAN);
        assertFalse(RawPayloadCheck.check(small, W, H, STRIDE, WHITE).plain());
    }

    @Test
    public void packedStrideOfContiguousRowsIsTheirLength() {
        // Xiaomi 17 Ultra tele (logical camera 0): rows of width*10/8 bytes one after another, the rest of the buffer zero.
        ByteBuffer b = packedMipi10(scene(1, 9), zeroed());
        assertTrue(RawPayloadCheck.check(b, W, H, STRIDE, WHITE).isPacked10());
        assertEquals(W * 5 / 4, RawPayloadCheck.packedStride(b, W, H));
        assertEquals(W * 5 / 4, RawPayloadCheck.packedStride(packedMipi10(scene(0.02, 10), zeroed()), W, H));
    }

    @Test
    public void packedStrideFindsAlignedRows() {
        assertEquals(656, RawPayloadCheck.packedStride(packedMipi10Strided(scene(1, 11), 656, 0), W, H));
        assertEquals(704, RawPayloadCheck.packedStride(packedMipi10Strided(scene(1, 12), 704, 0x5a), W, H));
    }

    @Test
    public void packedStrideNeedsAZeroTail() {
        assertEquals(0, RawPayloadCheck.packedStride(plain(scene(1, 13), 1), W, H));
        assertEquals(0, RawPayloadCheck.packedStride(packedMipi10(scene(1, 14), plain(scene(1, 15), 1)), W, H));
        assertEquals(0, RawPayloadCheck.packedStride(zeroed(), W, H));
    }

    @Test
    public void packedFrameUnpacksToItsSamples() {
        int[][] v = scene(1, 16);
        ByteBuffer b = packedMipi10Strided(v, 656, 0);
        int stride = RawPayloadCheck.packedStride(b, W, H);
        ByteBuffer out = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.nativeOrder());
        assertTrue(com.particlesdevs.photoncamera.util.RawUnpack.unpack(b.duplicate(), android.graphics.ImageFormat.RAW10, W, H, stride, out));
        for (int y = 0; y < H; y += 37) for (int x = 0; x < W; x += 13)
            assertEquals(v[y][x], out.getShort((y * W + x) * 2) & 0xffff);
    }

    /** MIPI RAW14 rows (7 bytes per 4 px, P72) of the 14-bit values v x scale, {@code stride} apart, into a RAW16-sized buffer. */
    private static ByteBuffer packedMipi14(int[][] v, int scale, int stride) {
        ByteBuffer into = zeroed();
        for (int y = 0; y < H; y++) for (int x = 0, at = y * stride; x < W; x += 4, at += 7) {
            int p0 = v[y][x] * scale, p1 = v[y][x + 1] * scale, p2 = v[y][x + 2] * scale, p3 = v[y][x + 3] * scale;
            into.put(at, (byte) (p0 >> 6)); into.put(at + 1, (byte) (p1 >> 6)); into.put(at + 2, (byte) (p2 >> 6)); into.put(at + 3, (byte) (p3 >> 6));
            into.put(at + 4, (byte) ((p0 & 0x3f) | ((p1 & 3) << 6)));
            into.put(at + 5, (byte) (((p1 >> 2) & 0x0f) | ((p2 & 0x0f) << 4)));
            into.put(at + 6, (byte) (((p2 >> 4) & 3) | ((p3 & 0x3f) << 2)));
        }
        return into;
    }

    @Test
    public void raw14With14BitValuesIsReadShiftedTo10Bit() {
        // Xiaomi 17 Ultra in-sensor zoom (mode 9): 7140 of 8160 bytes a row; here 896 of 1024
        int[][] v = scene(1, 21);
        ByteBuffer b = packedMipi14(v, 16, W * 14 / 8);
        assertFalse(RawPayloadCheck.check(b, W, H, STRIDE, WHITE).plain());
        assertEquals(0, RawPayloadCheck.packedStride(b, W, H)); // not RAW10
        RawPayloadCheck.Layout l = RawPayloadCheck.packedLayout(b, W, H, BLACK, WHITE);
        assertNotNull(l);
        assertEquals(com.particlesdevs.photoncamera.util.RawUnpack.RAW14_TO_10, l.format);
        assertEquals(W * 14 / 8, l.stride);
        ByteBuffer out = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.nativeOrder());
        assertTrue(com.particlesdevs.photoncamera.util.RawUnpack.unpack(b.duplicate(), l.format, W, H, l.stride, out));
        for (int y = 0; y < H; y += 37) for (int x = 0; x < W; x += 13)
            assertEquals(v[y][x], out.getShort((y * W + x) * 2) & 0xffff);
        assertTrue(RawPayloadCheck.fits(l, RawPayloadCheck.dataEnd(b), W, H));
    }

    @Test
    public void raw14With10BitValuesIsReadAsIs() {
        int[][] v = scene(1, 22);
        RawPayloadCheck.Layout l = RawPayloadCheck.packedLayout(packedMipi14(v, 1, 912), W, H, BLACK, WHITE);
        assertNotNull(l);
        assertEquals(com.particlesdevs.photoncamera.util.RawUnpack.RAW14, l.format);
        assertEquals(912, l.stride);
    }

    @Test
    public void raw10IsStillFoundFirstAndGarbageIsRefused() {
        RawPayloadCheck.Layout l = RawPayloadCheck.packedLayout(packedMipi10(scene(1, 23), zeroed()), W, H, BLACK, WHITE);
        assertNotNull(l);
        assertEquals(android.graphics.ImageFormat.RAW10, l.format);
        assertFalse(RawPayloadCheck.fits(l, W * 14 / 8 * H, W, H));
        // random bytes in the RAW14 geometry: no plausible image
        ByteBuffer junk = zeroed();
        Random r = new Random(24);
        for (int i = 0; i < W * 14 / 8 * H; i++) junk.put(i, (byte) (1 + r.nextInt(255)));
        assertEquals(null, RawPayloadCheck.packedLayout(junk, W, H, BLACK, WHITE));
        assertEquals(null, RawPayloadCheck.packedLayout(plain(scene(1, 25), 1), W, H, BLACK, WHITE));
    }
}
