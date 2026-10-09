package com.particlesdevs.photoncamera.processing;

import com.particlesdevs.photoncamera.util.Allocator;
import com.particlesdevs.photoncamera.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.List;

/**
 * P27, last resort only: the Hybrid merges any RAW stream at its own resolution. A stream beyond the hard limits (the sensor-grid
 * RGB float result above one 2 GB Java buffer, i.e. above ~178 MP, or a side above the GPU's texture / render-target / viewport
 * limit or the merge transport's 65534) cannot be merged or developed at all; it is binned 2x2 here until it fits, instead of
 * failing the shot. Same-colour samples are averaged, so the black and white levels stay; the framing stays (only the columns /
 * rows past the last whole colour period go, at most 2b - 1).
 * - Plain Bayer: each output site is the mean of the four same-colour sites of its 4x4 cell (the CFA order is kept).
 * - Colour-block mosaic (Quad 2x2, Tetra 4x4, measured): each 2x2 box inside one colour block (Quad becomes plain Bayer of
 *   the same colour order, Tetra becomes Quad).
 * The noise of a binned frame is the sensor's divided by the samples averaged ({@link ImageFrame#binnedSamples}).
 */
public final class RawBin {
    /** The sensor-grid RGB float32 result (12 B per pixel) must fit one Java direct buffer: 178,956,970 pixels. */
    public static final long MAX_RGB_BYTES = Integer.MAX_VALUE;
    /** Largest frame side the merge transport (NCH header) and the worker take. */
    public static final int MAX_SIDE = 65534;

    private RawBin() {}

    /**
     * True when the Hybrid can merge and develop a w x h stream at its own resolution: the sensor-grid RGB float result fits one
     * Java buffer and no side is above the GPU's {@code gpuMaxSide} (GLLimits.maxSide(), Integer.MAX_VALUE when unknown) or
     * {@link #MAX_SIDE}. There is no megapixel cap.
     */
    public static boolean fits(int w, int h, int gpuMaxSide) {
        return limitExceeded(w, h, gpuMaxSide) == null;
    }

    /** Which hard limit a w x h stream is beyond (for the log), or null when it {@link #fits}. */
    public static String limitExceeded(int w, int h, int gpuMaxSide) {
        if (w <= 0 || h <= 0) return "no size";
        final long rgb = 12L * w * h;
        if (rgb > MAX_RGB_BYTES) return "sensor-grid RGB " + (rgb >> 20) + " MB above 2 GB";
        final int side = Math.max(w, h);
        if (side > gpuMaxSide) return "side " + side + " above the GPU limit " + gpuMaxSide;
        if (side > MAX_SIDE) return "side " + side + " above the merge transport's " + MAX_SIDE;
        return null;
    }

    /**
     * Bins the frames of the first frame's size until they {@link #fits}; frames of another size are left alone (the burst drops
     * them). Returns the linear factor (1 = untouched, 2 = half width and height, ...). A stream that fits is never touched.
     */
    public static int binOversized(List<ImageFrame> frames, float black, float white, int gpuMaxSide) throws IOException {
        if (frames.isEmpty() || frames.get(0).buffer == null) return 1;
        int w = frames.get(0).width, h = frames.get(0).height;
        final String reason = limitExceeded(w, h, gpuMaxSide);
        if (reason == null) return 1;
        final int w0 = w, h0 = h;
        final MosaicBlockDetector.Result mosaic = MosaicBlockDetector.detect(frames.get(0).buffer, w, h, w * 2, black, white, 8);
        int block = mosaic == null || !mosaic.confident ? 1 : mosaic.block;
        final int block0 = block;
        int factor = 1;
        final long started = System.nanoTime();
        while (!fits(w, h, gpuMaxSide)) {
            final int[] out = outputSize(w, h, block);
            if (out[0] < 64 || out[1] < 64) throw new IOException("RAW " + w0 + "x" + h0 + ": too small to bin");
            final long inBytes = (long) w * h * 2, outBytes = (long) out[0] * out[1] * 2;
            for (ImageFrame f : frames) {
                if (f == null || f.buffer == null || f.width != w || f.height != h || f.buffer.capacity() < inBytes) continue;
                ByteBuffer binned = Allocator.allocate((int) outBytes);
                if (binned == null) throw new IOException("RAW binning: out of memory");
                binned.order(ByteOrder.LITTLE_ENDIAN);
                bin(f.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(), w, h, block,
                        binned.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(), out[0], out[1]);
                binned.position(0);
                ByteBuffer old = f.buffer;
                f.buffer = binned;
                f.width = out[0];
                f.height = out[1];
                f.binnedSamples *= 4;
                Allocator.free(old);
            }
            w = out[0]; h = out[1];
            factor *= 2;
            block = Math.max(1, block / 2);
        }
        Log.i("SCAM_HDR", "RAW " + w0 + "x" + h0 + " beyond the hard limits (" + reason + "): last resort, binned x" + factor
                + " to " + w + "x" + h + " (colour block " + block0 + (mosaic == null ? ", not measured" : mosaic.confident ? "" : ", not confident")
                + ") in " + (System.nanoTime() - started) / 1_000_000 + " ms");
        return factor;
    }

    /** Output size of one 2x2 binning step: whole colour periods only (2 x 2 sites for Bayer, 2b x 2b for a block-b mosaic). */
    static int[] outputSize(int w, int h, int block) {
        final int period = block <= 1 ? 4 : 2 * block;
        return new int[]{w / period * period / 2, h / period * period / 2};
    }

    /** One 2x2 binning step of a packed uint16 plane (rows of w samples) into {@code out} (rows of ow samples). */
    static void bin(ShortBuffer in, int w, int h, int block, ShortBuffer out, int ow, int oh) {
        final int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        final Thread[] pool = new Thread[threads - 1];
        for (int t = 1; t < threads; t++) {
            final int y0 = (int) ((long) oh * t / threads), y1 = (int) ((long) oh * (t + 1) / threads);
            pool[t - 1] = new Thread(() -> rows(in, w, block, out, ow, y0, y1), "RawBin-" + t);
            pool[t - 1].start();
        }
        rows(in, w, block, out, ow, 0, (int) ((long) oh / threads));
        for (Thread t : pool) {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static void rows(ShortBuffer src, int w, int block, ShortBuffer dst, int ow, int y0, int y1) {
        final ShortBuffer in = src.duplicate(), out = dst.duplicate();
        final short[] r0 = new short[w], r1 = new short[w], o = new short[ow];
        for (int y = y0; y < y1; y++) {
            // Bayer: rows 4Y+py and 4Y+py+2 (same colour); block mosaic: rows 2y and 2y+1 (one colour block)
            final int a = block <= 1 ? 2 * (y & ~1) + (y & 1) : 2 * y;
            final int b = block <= 1 ? a + 2 : a + 1;
            in.position(a * w); in.get(r0);
            in.position(b * w); in.get(r1);
            for (int x = 0; x < ow; x++) {
                final int c = block <= 1 ? 2 * (x & ~1) + (x & 1) : 2 * x;
                final int d = block <= 1 ? c + 2 : c + 1;
                final int sum = (r0[c] & 0xFFFF) + (r0[d] & 0xFFFF) + (r1[c] & 0xFFFF) + (r1[d] & 0xFFFF);
                o[x] = (short) ((sum + 2) >> 2);
            }
            out.position(y * ow); out.put(o);
        }
    }
}
