package com.particlesdevs.photoncamera.processing.ml;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * The hybrid's final Lanczos downscale (app/src/main/cpp/lanczos-downscale.h: separable, scale-aware, in linear light,
 * horizontal rows cached only as long as the vertical filter needs them) for the RGBA_1010102 image of the 10-bit HEIC:
 * packed pixels (R in bits 0-9, G 10-19, B 20-29), 10-bit sRGB in, 10-bit sRGB out, alpha 3. The weights are computed
 * exactly as the native code computes them; the output is encoded through a 65536-step linear table as there, into 1023
 * levels instead of 255. The native kernel keeps the ARGB_8888 images (it only takes RGBA8). Bands of output rows run in
 * parallel, each with its own row reader / writer. Pure Java.
 */
public final class Lanczos1010102 {
    /** Source rows: rows {@code y .. y+rows-1} into {@code out} (row-major, source width ints per row) from {@code offset}. */
    public interface Rows extends AutoCloseable {
        void read(int y, int rows, int[] out, int offset);

        @Override
        default void close() {}
    }

    /** Destination rows from {@code in} (row-major, destination width ints per row). */
    public interface Sink extends AutoCloseable {
        void write(int y, int rows, int[] in, int offset);

        @Override
        default void close() {}
    }

    /** Source rows read at once by a band. */
    static final int READ_ROWS = 32;
    /** Destination rows written at once by a band. */
    static final int WRITE_ROWS = 16;

    private static final float[] DECODE = new float[1024];
    private static final short[] ENCODE = new short[65536];

    static {
        for (int i = 0; i < 1024; i++) {
            final double v = i / 1023.0;
            DECODE[i] = (float) (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i < 65536; i++) {
            final double v = i / 65535.0;
            ENCODE[i] = (short) Math.round(1023 * (v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055));
        }
    }

    private Lanczos1010102() {}

    static final class Tap {
        int first;
        float[] weights;
    }

    /** The native coefficients(): a normalised Lanczos-{@code lobes} window per output sample, widened by the scale. */
    static Tap[] coefficients(int input, int output, int lobes) {
        final double ratio = (double) output / input;
        final double scale = Math.min(1.0, ratio), radius = lobes / scale;
        final Tap[] taps = new Tap[output];
        for (int i = 0; i < output; i++) {
            final double center = (i + 0.5) / ratio - 0.5;
            final int first = Math.max(0, (int) Math.ceil(center - radius));
            final int last = Math.min(input - 1, (int) Math.floor(center + radius));
            final double[] w = new double[Math.max(0, last - first + 1)];
            double sum = 0;
            for (int j = first; j <= last; j++) {
                final double d = (j - center) * scale;
                final double v = Math.abs(d) < lobes ? sinc(d) * sinc(d / lobes) : 0;
                w[j - first] = (float) v; // the native code stores the raw weight as float, then divides by the double sum
                sum += v;
            }
            final Tap t = new Tap();
            t.first = first;
            t.weights = new float[w.length];
            for (int k = 0; k < w.length; k++) t.weights[k] = (float) (w[k] / sum);
            taps[i] = t;
        }
        return taps;
    }

    private static double sinc(double x) {
        if (Math.abs(x) < 1e-12) return 1.0;
        final double p = Math.PI * x;
        return Math.sin(p) / p;
    }

    /**
     * Resizes {@code sw} x {@code sh} to {@code dw} x {@code dh} (downscale only, at most 4:1 per side, {@code lobes} 2..5).
     * {@code threads} bands of output rows run in parallel; each takes its own reader and sink.
     */
    public static void resize(Supplier<Rows> source, int sw, int sh, Supplier<Sink> sink, int dw, int dh, int lobes, int threads)
            throws Exception {
        if (sw < 1 || sh < 1 || dw < 1 || dh < 1 || dw > sw || dh > sh || (long) dw * 4 < sw || (long) dh * 4 < sh || lobes < 2 || lobes > 5)
            throw new IllegalArgumentException("Invalid Lanczos downscale geometry " + sw + "x" + sh + " -> " + dw + "x" + dh + " a=" + lobes);
        final Tap[] horizontal = coefficients(sw, dw, lobes);
        final Tap[] vertical = coefficients(sh, dh, lobes);
        final int bands = Math.max(1, Math.min(threads * 2, dh / 16));
        if (threads <= 1 || bands <= 1) {
            band(source, sw, sh, sink, dw, 0, dh, horizontal, vertical);
            return;
        }
        final ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, bands));
        try {
            final List<Future<?>> jobs = new ArrayList<>();
            for (int b = 0; b < bands; b++) {
                final int y0 = (int) ((long) dh * b / bands), y1 = (int) ((long) dh * (b + 1) / bands);
                jobs.add(pool.submit(() -> {
                    band(source, sw, sh, sink, dw, y0, y1, horizontal, vertical);
                    return null;
                }));
            }
            for (Future<?> f : jobs) f.get();
        } finally {
            pool.shutdownNow();
        }
    }

    /** Output rows {@code y0 .. y1-1}: the streaming filter of the native code over this band's source rows. */
    private static void band(Supplier<Rows> source, int sw, int sh, Supplier<Sink> sink, int dw, int y0, int y1,
                             Tap[] horizontal, Tap[] vertical) throws Exception {
        int capacity = 1;
        for (Tap t : vertical) capacity = Math.max(capacity, t.weights.length);
        final float[][] rows = new float[capacity][dw * 3];
        final int[] tags = new int[capacity];
        java.util.Arrays.fill(tags, -1);
        final float[] sum = new float[dw * 3];
        final int[] in = new int[READ_ROWS * sw];
        int inFirst = -1, inCount = 0;
        final int[] out = new int[WRITE_ROWS * dw];
        int outFirst = y0, outCount = 0;
        try (Rows reader = source.get(); Sink writer = sink.get()) {
            for (int y = y0; y < y1; y++) {
                java.util.Arrays.fill(sum, 0f);
                final Tap v = vertical[y];
                for (int k = 0; k < v.weights.length; k++) {
                    final int sy = v.first + k, slot = sy % capacity;
                    final float[] row = rows[slot];
                    if (tags[slot] != sy) {
                        if (sy < inFirst || sy >= inFirst + inCount) {
                            inFirst = sy;
                            inCount = Math.min(READ_ROWS, sh - sy);
                            reader.read(inFirst, inCount, in, 0);
                        }
                        final int base = (sy - inFirst) * sw;
                        for (int x = 0; x < dw; x++) {
                            float r = 0, g = 0, b = 0;
                            final Tap h = horizontal[x];
                            final float[] w = h.weights;
                            for (int j = 0, p = base + h.first; j < w.length; j++, p++) {
                                final int px = in[p];
                                final float wj = w[j];
                                r += DECODE[px & 0x3FF] * wj;
                                g += DECODE[(px >>> 10) & 0x3FF] * wj;
                                b += DECODE[(px >>> 20) & 0x3FF] * wj;
                            }
                            row[x * 3] = r;
                            row[x * 3 + 1] = g;
                            row[x * 3 + 2] = b;
                        }
                        tags[slot] = sy;
                    }
                    final float w = v.weights[k];
                    for (int i = 0; i < sum.length; i++) sum[i] += row[i] * w;
                }
                final int o = outCount * dw;
                for (int x = 0; x < dw; x++) {
                    out[o + x] = (3 << 30) | (encode(sum[x * 3 + 2]) << 20) | (encode(sum[x * 3 + 1]) << 10) | encode(sum[x * 3]);
                }
                if (++outCount == WRITE_ROWS || y == y1 - 1) {
                    writer.write(outFirst, outCount, out, 0);
                    outFirst += outCount;
                    outCount = 0;
                }
            }
        }
    }

    private static int encode(float linear) {
        final float v = Math.max(0f, Math.min(1f, linear));
        return ENCODE[(int) (v * 65535f + 0.5f)];
    }
}
