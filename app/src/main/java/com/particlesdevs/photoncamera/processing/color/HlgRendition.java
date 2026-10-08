package com.particlesdevs.photoncamera.processing.color;

import android.graphics.Bitmap;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.particlesdevs.photoncamera.processing.heif.TenBitBitmaps;
import com.particlesdevs.photoncamera.processing.ultrahdr.GainMapComputer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The HDR picture of «HDR в HEIC / AVIF» (P46): the Ultra HDR rendition of the shot (the SDR base and the gain map that
 * PostPipeline.RunHDRGainMap / {@link GainMapComputer} compute for Ultra HDR JPEG) as BT.2100 HLG, BT.2020 primaries,
 * 10 bits, full range, for the 10-bit HEIC (nclx 9/18/9, HEVC VUI BT.2020 / HLG) and the 10 / 12-bit AVIF (CICP 9/18/9).
 *
 * <p>Per pixel:
 * <ol>
 *   <li>the base (sRGB or Display P3 primaries, sRGB transfer as every viewer decodes the SDR file) to linear light;</li>
 *   <li>the gain map applied as an ISO 21496-1 viewer applies it on a display with the HLG headroom (1000 / 203 nits =
 *       2.3 stops): {@code (sdr + 1/64) * 2^(boost * w) - 1/64}, boost = GainMapMin..Max of the 8-bit map value,
 *       {@code w = min(1, log2(headroom) / HDRCapacityMax)} - the SDR picture itself (boost 0) is unchanged;</li>
 *   <li>the primaries converted to BT.2020;</li>
 *   <li>a soft knee keeps the brightest channel below the headroom (only above 80 % of it, hue kept);</li>
 *   <li>SDR white (1.0) = 203 cd/m² on the 1000 cd/m² HLG reference display (ITU-R BT.2408): the inverse HLG OOTF
 *       (system gamma 1.2, on the BT.2020 luminance) and the HLG OETF (ARIB STD-B67) give the signal - SDR white comes
 *       out at 75 % HLG.</li>
 * </ol>
 * Pure arithmetic in {@link Renderer} (tests); {@link #render} runs it over a bitmap in bands on a few threads.
 */
public final class HlgRendition {
    private HlgRendition() {}

    /** BT.2408 HDR reference white and the nominal peak of the HLG reference display (cd/m²). */
    public static final double REFERENCE_WHITE = 203.0, PEAK = 1000.0;
    /** HLG system gamma at a 1000 cd/m² display (BT.2100). */
    public static final double SYSTEM_GAMMA = 1.2;
    /** ARIB STD-B67 constants. */
    static final double A = 0.17883277, B = 1.0 - 4.0 * A, C = 0.5 - A * Math.log(4.0 * A);
    /** BT.2020 luminance weights. */
    static final double KR = 0.2627, KG = 0.6780, KB = 0.0593;
    /** Where the highlight knee starts, as a fraction of the headroom. */
    static final double KNEE = 0.8;

    /** Headroom of the HLG reference display over SDR white: 1000 / 203. */
    public static double headroom() {
        return PEAK / REFERENCE_WHITE;
    }

    /** HLG OETF: scene light 0..1 -> signal 0..1. */
    public static double oetf(double e) {
        if (e <= 0.0) return 0.0;
        if (e <= 1.0 / 12.0) return Math.sqrt(3.0 * e);
        return A * Math.log(12.0 * e - B) + C;
    }

    /** Inverse HLG OETF: signal 0..1 -> scene light 0..1. */
    public static double inverseOetf(double v) {
        if (v <= 0.0) return 0.0;
        if (v <= 0.5) return v * v / 3.0;
        return (Math.exp((v - C) / A) + B) / 12.0;
    }

    /** Soft knee of the brightest channel: unchanged up to KNEE x headroom, then towards the headroom (slope 1 at the knee). */
    public static double knee(double m) {
        final double p = headroom(), k = KNEE * p;
        if (m <= k) return m;
        return k + (p - k) * (1.0 - Math.exp(-(m - k) / (p - k)));
    }

    /**
     * HLG signal of display light relative to SDR white (BT.2020 linear, 1.0 = 203 cd/m²): {@code out[0..2]} in 0..1.
     */
    public static void encode(double r, double g, double b, double[] out) {
        r = Math.max(r, 0.0);
        g = Math.max(g, 0.0);
        b = Math.max(b, 0.0);
        final double m = Math.max(r, Math.max(g, b));
        if (m > KNEE * headroom()) {
            final double s = knee(m) / m;
            r *= s;
            g *= s;
            b *= s;
        }
        final double k = REFERENCE_WHITE / PEAK;
        r *= k;
        g *= k;
        b *= k;
        // Inverse OOTF (alpha = peak, black 0): E = F_D * Y_D^((1 - gamma) / gamma), on the normalised display light.
        final double yd = KR * r + KG * g + KB * b;
        final double scale = yd > 0.0 ? Math.pow(yd, (1.0 - SYSTEM_GAMMA) / SYSTEM_GAMMA) : 0.0;
        out[0] = oetf(Math.min(1.0, r * scale));
        out[1] = oetf(Math.min(1.0, g * scale));
        out[2] = oetf(Math.min(1.0, b * scale));
    }

    /** Display light (BT.2020 linear, relative to SDR white) of an HLG signal: the inverse of {@link #encode} below the knee. */
    public static void decode(double r, double g, double b, double[] out) {
        final double er = inverseOetf(r), eg = inverseOetf(g), eb = inverseOetf(b);
        final double ys = KR * er + KG * eg + KB * eb;
        final double scale = ys > 0.0 ? Math.pow(ys, SYSTEM_GAMMA - 1.0) : 0.0;
        final double k = PEAK / REFERENCE_WHITE;
        out[0] = er * scale * k;
        out[1] = eg * scale * k;
        out[2] = eb * scale * k;
    }

    /** The per-pixel arithmetic: base pixel + gain-map value -> packed RGBA_1010102 HLG pixel. */
    public static final class Renderer {
        private final float[] linear;
        private final float[] gainOf = new float[256];
        private final double[] toBt2020;
        private final boolean tenBit;
        private final double[] px = new double[3];

        /**
         * @param tenBit        base pixels are RGBA_1010102 (else ARGB_8888 memory order: R in the low byte)
         * @param baseSpace     primaries of the base (the «Цветовое пространство» the pipeline rendered in)
         * @param gainMin       GainMapMin (log2)
         * @param gainMax       GainMapMax (log2)
         * @param capacityMax   HDRCapacityMax (log2) of the map
         */
        public Renderer(boolean tenBit, OutputColour.Space baseSpace, float gainMin, float gainMax, float capacityMax) {
            this.tenBit = tenBit;
            final int levels = tenBit ? 1024 : 256;
            linear = new float[levels];
            for (int i = 0; i < levels; i++) linear[i] = (float) OutputColour.srgbToLinear(i / (double) (levels - 1));
            final double w = weight(capacityMax);
            for (int i = 0; i < 256; i++) {
                final double boost = gainMin + (gainMax - gainMin) * (i / 255.0);
                gainOf[i] = (float) Math.pow(2.0, boost * w);
            }
            toBt2020 = baseSpace == OutputColour.Space.DISPLAY_P3 ? OutputColour.p3ToBt2020() : OutputColour.srgbToBt2020();
        }

        /** Headroom weight of the gain map (ISO 21496-1) for the HLG headroom: min(1, log2(1000 / 203) / capacity). */
        public static double weight(float capacityMax) {
            final double cap = Math.max(capacityMax, 1e-3);
            return Math.min(1.0, Math.log(headroom()) / Math.log(2.0) / cap);
        }

        /** Linear multiplier of a gain-map value 0..255 (interpolated between the 256 levels). */
        public double gain(float value) {
            if (value <= 0f) return gainOf[0];
            if (value >= 255f) return gainOf[255];
            final int i = (int) value;
            final float f = value - i;
            return gainOf[i] + (gainOf[Math.min(255, i + 1)] - gainOf[i]) * f;
        }

        /** The HLG pixel (packed RGBA_1010102, alpha 3) of base pixel {@code base} with gain-map value {@code gainValue}. */
        public int pixel(int base, float gainValue) {
            final float r, g, b;
            if (tenBit) {
                r = linear[base & 0x3FF];
                g = linear[(base >>> 10) & 0x3FF];
                b = linear[(base >>> 20) & 0x3FF];
            } else {
                r = linear[base & 0xFF];
                g = linear[(base >>> 8) & 0xFF];
                b = linear[(base >>> 16) & 0xFF];
            }
            final double k = gain(gainValue), off = GainMapComputer.DECODE_OFFSET;
            final double hr = (r + off) * k - off, hg = (g + off) * k - off, hb = (b + off) * k - off;
            final double[] m = toBt2020;
            encode(m[0] * hr + m[1] * hg + m[2] * hb, m[3] * hr + m[4] * hg + m[5] * hb, m[6] * hr + m[7] * hg + m[8] * hb, px);
            return pack(px[0], px[1], px[2]);
        }

        static int pack(double r, double g, double b) {
            final int ri = (int) Math.round(Math.max(0.0, Math.min(1.0, r)) * 1023.0);
            final int gi = (int) Math.round(Math.max(0.0, Math.min(1.0, g)) * 1023.0);
            final int bi = (int) Math.round(Math.max(0.0, Math.min(1.0, b)) * 1023.0);
            return (3 << 30) | (bi << 20) | (gi << 10) | ri;
        }
    }

    /** Rows per band of {@link #render}. */
    static final int BAND = 64;

    /**
     * The HLG image (RGBA_1010102, same size) of {@code base} (ARGB_8888 or RGBA_1010102, keeps it) with the gain map of
     * the shot ({@code gain.gainMap}: R = G = B = the 8-bit value, any size - sampled bilinearly in its own grid).
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public static Bitmap render(Bitmap base, GainMapComputer.Result gain, OutputColour.Space baseSpace) throws Exception {
        final int w = base.getWidth(), h = base.getHeight();
        final boolean tenBit = TenBitBitmaps.isTenBit(base);
        if (!tenBit && base.getConfig() != Bitmap.Config.ARGB_8888) throw new IllegalArgumentException("base " + base.getConfig());
        final Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_1010102);
        final Bitmap map = gain.gainMap;
        final int gw = map.getWidth(), gh = map.getHeight();
        final int threads = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors()));
        final ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            final Thread t = new Thread(r, "HlgRendition");
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
        boolean ok = false;
        try {
            final List<Future<?>> jobs = new ArrayList<>();
            final int bands = (h + BAND - 1) / BAND;
            final int perThread = (bands + threads - 1) / threads;
            for (int t = 0; t < threads; t++) {
                final int b0 = t * perThread, b1 = Math.min(bands, b0 + perThread);
                if (b0 >= b1) break;
                jobs.add(pool.submit(() -> {
                    final Renderer renderer = new Renderer(tenBit, baseSpace, gain.gainMapMin, gain.gainMapMax, gain.hdrCapacityMax);
                    final int[] rows = new int[w * BAND];
                    final int[] g0 = new int[gw], g1 = new int[gw];
                    final float[] gainRow = new float[w];
                    try (TenBitBitmaps.RowReader reader = new TenBitBitmaps.RowReader(base, BAND);
                         TenBitBitmaps.RowWriter writer = new TenBitBitmaps.RowWriter(out, BAND)) {
                        for (int band = b0; band < b1; band++) {
                            final int y0 = band * BAND, n = Math.min(BAND, h - y0);
                            reader.read(y0, n, rows, 0);
                            for (int r = 0; r < n; r++) {
                                gainRow(map, gw, gh, w, h, y0 + r, g0, g1, gainRow);
                                final int at = r * w;
                                for (int x = 0; x < w; x++) rows[at + x] = renderer.pixel(rows[at + x], gainRow[x]);
                            }
                            writer.write(y0, n, rows, 0);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : jobs) f.get();
            ok = true;
            return out;
        } finally {
            pool.shutdownNow();
            if (!ok) out.recycle();
        }
    }

    /** Gain-map values (0..255) of image row {@code y}: bilinear in the map's grid (pixel centres aligned). */
    static void gainRow(Bitmap map, int gw, int gh, int w, int h, int y, int[] g0, int[] g1, float[] out) {
        final double fy = Math.max(0.0, Math.min(gh - 1.0, (y + 0.5) * gh / (double) h - 0.5));
        final int y0 = (int) fy, y1 = Math.min(gh - 1, y0 + 1);
        final float wy = (float) (fy - y0);
        synchronized (map) {
            map.getPixels(g0, 0, gw, 0, y0, gw, 1);
            if (y1 != y0) map.getPixels(g1, 0, gw, 0, y1, gw, 1);
        }
        final int[] lower = y1 != y0 ? g1 : g0;
        sampleRow(g0, lower, wy, gw, w, out);
    }

    /** Bilinear row of the map values (R channel of ARGB ints) for an image {@code w} pixels wide. */
    static void sampleRow(int[] upper, int[] lower, float wy, int gw, int w, float[] out) {
        for (int x = 0; x < w; x++) {
            final double fx = Math.max(0.0, Math.min(gw - 1.0, (x + 0.5) * gw / (double) w - 0.5));
            final int x0 = (int) fx, x1 = Math.min(gw - 1, x0 + 1);
            final float wx = (float) (fx - x0);
            final float a = (upper[x0] >> 16) & 0xFF, b = (upper[x1] >> 16) & 0xFF;
            final float c = (lower[x0] >> 16) & 0xFF, d = (lower[x1] >> 16) & 0xFF;
            final float top = a + (b - a) * wx, bottom = c + (d - c) * wx;
            out[x] = top + (bottom - top) * wy;
        }
    }
}
