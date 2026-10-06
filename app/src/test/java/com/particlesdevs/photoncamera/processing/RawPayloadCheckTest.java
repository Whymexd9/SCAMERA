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
}
