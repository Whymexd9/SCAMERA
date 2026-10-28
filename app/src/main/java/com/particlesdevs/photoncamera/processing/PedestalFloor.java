package com.particlesdevs.photoncamera.processing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * P315: a RAW stream that comes without its black pedestal (OPPO Find X8 Ultra, the module sensor mode 8 through the agingtest
 * key: data floor 0.48 / 0.33 DN for a declared 64, owner's log 2026-10-21 13:33:28 / 13:36:59). The photo measures it per shot
 * (P114, Parameters.refineBlackLevel); the RAW viewfinder subtracted the declared 64 and lost every sample under it - R and B
 * (0.45 / 0.58 of G on grey) first: pure green mid-tones, half the frame black. The viewfinder's own frame is checked here:
 * non-zero samples under a quarter of the black (no drift reaches that, zero padding is exactly 0) in at least SHARE of the
 * blocks prove the pedestal gone; the black is then the frame's floor, the lowest of the camera session (an upper bound of the
 * true black, never raised again). A bright frame without dark samples proves nothing and keeps the session's state.
 * P386: the photo (Parameters.refineBlackLevel) takes the same proof ({@link #SHARE}); a measurement that switches the viewfinder
 * also gives the session's stream its floor (PedestalMemory, MosaicStream's key): a photo of that stream without deep shade takes it.
 */
public final class PedestalFloor {
    static final int BLOCK = 16, STEP = 4, VOTES = 2;
    /** Share of the blocks whose non-zero samples under a quarter of the black prove the pedestal lost (P315; the photo: P386). */
    public static final float SHARE = 0.02f;
    static final long INTERVAL_NS = 250_000_000L;

    private int session = Integer.MIN_VALUE, votes;
    private long atNs = Long.MIN_VALUE;
    private float floor = Float.NaN;          // NaN: the pedestal is there (or not proven lost yet)
    private boolean logged;

    /**
     * The frame's floor when its samples prove the pedestal lost, NaN otherwise. {@code block}: the frame's colour block (1 for a
     * plain or a cell-means frame); the four channels are the sites ((x / block) & 1, (y / block) & 1), one site per macro pixel.
     * P386: when the darkest block means themselves sit below half the black (the read noise of a 16-sample mean reaches that only
     * from ~0.5 x the black, ~34 DN of a 10-bit stream), the floor is the third-lowest smallest non-zero sample of the blocks when
     * lower: a daylight frame's darkest blocks still hold light (vivo X200 Ultra camera 5, owner's zip 2026-10-28, its Quad frame
     * by cell means: the darkest block means ~4-5 DN, the samples ~1-2 DN, the true black ~0). Only when that floor is near zero
     * (PedestalMemory.nearZero of {@code white}) and dark blocks hold it - at least 3 blocks whose mean is within 3 x nearZero with
     * 2 of their samples at or below it - as in the photo (Parameters.refineBlackLevel): the noise tail of a black left partly
     * above zero reaches ~1 DN as well, and the samples' floor then lifted a high-ISO frame's shadows magenta (P386 review).
     */
    static float measure(ShortBuffer d, int w, int h, int rowPx, int block, float black) {
        return measure(d, w, h, rowPx, block, black, 1023f);
    }

    static float measure(ShortBuffer d, int w, int h, int rowPx, int block, float black, float white) {
        final float below = black * 0.25f, nearZero = PedestalMemory.nearZero(white);
        if (!(below >= 1f)) return Float.NaN;
        final int b = Math.max(1, block), period = 2 * b, sx = w / period, sy = h / period;   // sites per channel
        final int bw = sx / BLOCK, bh = sy / BLOCK;
        if (bw < 2 || bh < 2) return Float.NaN;
        int blocks = 0, hits = 0, nearBlocks = 0;
        float low1 = Float.MAX_VALUE, low2 = Float.MAX_VALUE, low3 = Float.MAX_VALUE;
        int m1 = Integer.MAX_VALUE, m2 = Integer.MAX_VALUE, m3 = Integer.MAX_VALUE;
        for (int c = 0; c < 4; c++) {
            final int ox = (c & 1) * b, oy = (c >> 1) * b;
            for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
                long sum = 0; int n = 0, least = Integer.MAX_VALUE, atZero = 0; boolean hit = false, allZero = true;
                for (int j = 0; j < BLOCK; j += STEP) {
                    final int row = ((by * BLOCK + j) * period + oy) * rowPx;
                    for (int i = 0; i < BLOCK; i += STEP) {
                        final int v = d.get(row + (bx * BLOCK + i) * period + ox) & 0xFFFF;
                        sum += v; n++;
                        hit |= v > 0 && v < below;
                        allZero &= v == 0;
                        if (v > 0 && v < least) least = v;
                        if (v <= nearZero) atZero++;
                    }
                }
                blocks++;
                if (hit) hits++;
                if (allZero) continue;
                final float mean = sum / (float) n;
                if (atZero >= 2 && mean <= 3f * nearZero) nearBlocks++;
                if (mean < low1) { low3 = low2; low2 = low1; low1 = mean; }
                else if (mean < low2) { low3 = low2; low2 = mean; }
                else if (mean < low3) low3 = mean;
                if (least < m1) { m3 = m2; m2 = m1; m1 = least; }
                else if (least < m2) { m3 = m2; m2 = least; }
                else if (least < m3) m3 = least;
            }
        }
        if (!(hits >= SHARE * blocks && low3 < black)) return Float.NaN;
        return low3 < 0.5f * black && m3 < low3 && m3 <= nearZero && nearBlocks >= 3 ? m3 : low3;
    }

    /** The black the viewfinder subtracts for this frame: {@code black} unless this camera session's frames proved it lost. */
    synchronized float[] black(int frameSession, ByteBuffer frame, int w, int h, int rowStride, int block, float[] black) {
        return black(frameSession, frame, w, h, rowStride, block, black, 1023f);
    }

    /** {@link #black(int, ByteBuffer, int, int, int, int, float[])} of a stream of white level {@code white}. */
    synchronized float[] black(int frameSession, ByteBuffer frame, int w, int h, int rowStride, int block, float[] black, float white) {
        if (frameSession != session) { session = frameSession; votes = 0; floor = Float.NaN; atNs = Long.MIN_VALUE; logged = false; }
        float declared = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) declared = Math.min(declared, black[i]);
        final long now = System.nanoTime();
        if (atNs == Long.MIN_VALUE || now - atNs >= INTERVAL_NS) { // the first frame of a session is measured at once
            atNs = now;
            final ShortBuffer d = frame.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
            final float f = measure(d, w, h, rowStride / 2, block, declared, white);
            if (Float.isNaN(f)) { if (Float.isNaN(floor)) votes = 0; }
            else if (++votes >= VOTES) {
                floor = Float.isNaN(floor) ? f : Math.min(floor, f);
                // P386: the session's stream proved without its pedestal - this measurement's floor for its photos
                PedestalMemory.remember(MosaicStream.key(), f, white, "viewfinder");
            }
        }
        if (Float.isNaN(floor)) return black;
        if (!logged) {
            logged = true;
            com.particlesdevs.photoncamera.util.Log.i("LiveRawFrame", "RAW viewfinder: the stream came without its black pedestal: black "
                    + declared + " -> " + floor + " (the frame's floor, P315)");
        }
        final float[] out = new float[4];
        for (int i = 0; i < 4; i++) out[i] = Math.max(0f, Math.min(black[i], floor));
        return out;
    }
}
