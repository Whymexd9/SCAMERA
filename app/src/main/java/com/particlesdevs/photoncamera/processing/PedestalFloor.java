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
 */
final class PedestalFloor {
    static final int BLOCK = 16, STEP = 4, VOTES = 2;
    static final float SHARE = 0.02f;
    static final long INTERVAL_NS = 250_000_000L;

    private int session = Integer.MIN_VALUE, votes;
    private long atNs = Long.MIN_VALUE;
    private float floor = Float.NaN;          // NaN: the pedestal is there (or not proven lost yet)
    private boolean logged;

    /**
     * The frame's floor when its samples prove the pedestal lost, NaN otherwise. {@code block}: the frame's colour block (1 for a
     * plain or a cell-means frame); the four channels are the sites ((x / block) & 1, (y / block) & 1), one site per macro pixel.
     */
    static float measure(ShortBuffer d, int w, int h, int rowPx, int block, float black) {
        final float below = black * 0.25f;
        if (!(below >= 1f)) return Float.NaN;
        final int b = Math.max(1, block), period = 2 * b, sx = w / period, sy = h / period;   // sites per channel
        final int bw = sx / BLOCK, bh = sy / BLOCK;
        if (bw < 2 || bh < 2) return Float.NaN;
        int blocks = 0, hits = 0;
        float low1 = Float.MAX_VALUE, low2 = Float.MAX_VALUE, low3 = Float.MAX_VALUE;
        for (int c = 0; c < 4; c++) {
            final int ox = (c & 1) * b, oy = (c >> 1) * b;
            for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
                long sum = 0; int n = 0; boolean hit = false, allZero = true;
                for (int j = 0; j < BLOCK; j += STEP) {
                    final int row = ((by * BLOCK + j) * period + oy) * rowPx;
                    for (int i = 0; i < BLOCK; i += STEP) {
                        final int v = d.get(row + (bx * BLOCK + i) * period + ox) & 0xFFFF;
                        sum += v; n++;
                        hit |= v > 0 && v < below;
                        allZero &= v == 0;
                    }
                }
                blocks++;
                if (hit) hits++;
                if (allZero) continue;
                final float mean = sum / (float) n;
                if (mean < low1) { low3 = low2; low2 = low1; low1 = mean; }
                else if (mean < low2) { low3 = low2; low2 = mean; }
                else if (mean < low3) low3 = mean;
            }
        }
        return hits >= SHARE * blocks && low3 < black ? low3 : Float.NaN;
    }

    /** The black the viewfinder subtracts for this frame: {@code black} unless this camera session's frames proved it lost. */
    synchronized float[] black(int frameSession, ByteBuffer frame, int w, int h, int rowStride, int block, float[] black) {
        if (frameSession != session) { session = frameSession; votes = 0; floor = Float.NaN; atNs = Long.MIN_VALUE; logged = false; }
        float declared = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) declared = Math.min(declared, black[i]);
        final long now = System.nanoTime();
        if (atNs == Long.MIN_VALUE || now - atNs >= INTERVAL_NS) { // the first frame of a session is measured at once
            atNs = now;
            final ShortBuffer d = frame.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
            final float f = measure(d, w, h, rowStride / 2, block, declared);
            if (Float.isNaN(f)) { if (Float.isNaN(floor)) votes = 0; }
            else if (++votes >= VOTES) floor = Float.isNaN(floor) ? f : Math.min(floor, f);
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
