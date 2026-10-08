package com.particlesdevs.photoncamera.gallery.dng;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * P59 / P53: the gallery's own picture of a DNG, for every DNG the camera writes (and camera DNGs in general), so a DNG is
 * never black or a placeholder (Pixel 7 main-camera DNGs were black through the platform decoder).
 *
 * <p>Either an embedded preview (a JPEG of the reduced-resolution IFDs, large enough for the request) or a render of the
 * raw data: uncompressed 8..16-bit (strips or tiles, rows byte-padded), or lossless JPEG (compression 7, {@link Lj92Decoder});
 * the linearisation table, black / white levels, any CFA repeat pattern (2x2 Bayer, 4x4 Quad), each output pixel the box
 * mean of its block per colour; white balance from AsShotNeutral, camera to sRGB through ForwardMatrix1 (else the inverse of
 * ColorMatrix1), BaselineExposure, an automatic lift for dark raw data, a soft highlight shoulder and the sRGB curve.
 * Pure Java on a {@link ByteBuffer} (a mapped file): no Android classes, testable on the JVM.
 */
public final class DngPreview {
    /** An embedded JPEG (offset / length in the buffer) or pixels; {@code orientation} is the TIFF orientation (1..8). */
    public static final class Result {
        public int jpegOffset = -1, jpegLength;
        public int[] argb;
        public int width, height;
        public int orientation = 1;

        public boolean isJpeg() { return jpegOffset >= 0; }
    }

    static final class Ifd {
        long subType;
        int width, height, compression = 1, photometric = -1, spp = 1, planar = 1, rowsPerStrip = Integer.MAX_VALUE;
        int[] bps = {1};
        long[] stripOffsets, stripCounts, tileOffsets, tileCounts;
        int tileW, tileH;
        int cfaRows = 2, cfaCols = 2;
        byte[] cfa;
        int[] linearization;
        double[] black;
        int blackRows = 1, blackCols = 1;
        double white = -1;
        long jpegOffset = -1, jpegLength;
    }

    private final ByteBuffer b;
    final List<Ifd> ifds = new ArrayList<>();
    int orientation = 1;
    double[] colorMatrix1, forwardMatrix1, asShotNeutral;
    double baselineExposure;
    boolean dng;

    private DngPreview(ByteBuffer buffer) throws IOException {
        b = buffer.duplicate();
        if (b.limit() < 8) throw new IOException("DNG: too short");
        int bo = b.get(0) & 0xFF;
        if (bo == 'I' && (b.get(1) & 0xFF) == 'I') b.order(ByteOrder.LITTLE_ENDIAN);
        else if (bo == 'M' && (b.get(1) & 0xFF) == 'M') b.order(ByteOrder.BIG_ENDIAN);
        else throw new IOException("DNG: not a TIFF");
        if ((b.getShort(2) & 0xFFFF) != 42) throw new IOException("DNG: not a TIFF");
        long off = b.getInt(4) & 0xFFFFFFFFL;
        int guard = 0;
        while (off != 0 && off < b.limit() && guard++ < 16) off = readIfd(off, 0, true);
    }

    /** True when the first bytes are a TIFF header (the caller then reads the whole file with {@link #read}). */
    public static boolean isTiff(byte[] head, int n) {
        return n >= 4 && ((head[0] == 'I' && head[1] == 'I' && head[2] == 42 && head[3] == 0)
                || (head[0] == 'M' && head[1] == 'M' && head[2] == 0 && head[3] == 42));
    }

    /**
     * The picture of a DNG for a request of {@code reqW x reqH} (<= 0: 2048 on the long side): the smallest embedded JPEG
     * that is large enough, else a raw render of about the request's size, else the largest embedded preview.
     */
    public static Result read(ByteBuffer buffer, int reqW, int reqH) throws IOException {
        DngPreview p = new DngPreview(buffer);
        int need = Math.max(reqW, reqH);
        if (need <= 0) need = 2048;
        need = Math.min(need, 4096);
        Ifd bestJpeg = null, largestJpeg = null, raw = null;
        for (Ifd f : p.ifds) {
            if (f.photometric == 32803 && (f.subType & 1) == 0) {
                if (raw == null || (long) f.width * f.height > (long) raw.width * raw.height) raw = f;
            } else if (f.jpegOffset >= 0 || (f.compression == 6 || f.compression == 7) && f.photometric != 32803 && f.stripOffsets != null) {
                long[] jr = jpegRange(f);
                if (jr == null) continue;
                int side = Math.max(f.width, f.height);
                if (largestJpeg == null || side > Math.max(largestJpeg.width, largestJpeg.height)) largestJpeg = f;
                if (side >= 0.9 * Math.min(need, 1024) && (bestJpeg == null || side < Math.max(bestJpeg.width, bestJpeg.height))) bestJpeg = f;
            }
        }
        Result r = new Result();
        r.orientation = p.orientation;
        if (bestJpeg != null) return jpeg(r, bestJpeg);
        if (raw != null) {
            try {
                p.render(raw, reqW > 0 ? reqW : need, reqH > 0 ? reqH : need, r);
                return r;
            } catch (IOException | RuntimeException e) {
                if (largestJpeg == null) throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
        if (largestJpeg != null) return jpeg(r, largestJpeg);
        throw new IOException("DNG: no raw data or preview that can be shown");
    }

    private static long[] jpegRange(Ifd f) {
        if (f.jpegOffset >= 0 && f.jpegLength > 0) return new long[]{f.jpegOffset, f.jpegLength};
        if (f.stripOffsets != null && f.stripOffsets.length == 1 && f.stripCounts != null) return new long[]{f.stripOffsets[0], f.stripCounts[0]};
        return null;
    }

    private static Result jpeg(Result r, Ifd f) {
        long[] jr = jpegRange(f);
        r.jpegOffset = (int) jr[0];
        r.jpegLength = (int) jr[1];
        r.width = f.width;
        r.height = f.height;
        return r;
    }

    // ---------------------------------------------------------------- TIFF

    private long readIfd(long off, int depth, boolean top) throws IOException {
        if (off + 2 > b.limit()) return 0;
        int n = b.getShort((int) off) & 0xFFFF;
        if (n == 0 || off + 2 + 12L * n + 4 > b.limit()) return 0;
        Ifd f = new Ifd();
        List<Long> subs = new ArrayList<>();
        for (int i = 0; i < n; ++i) {
            int e = (int) (off + 2 + 12L * i);
            int tag = b.getShort(e) & 0xFFFF, type = b.getShort(e + 2) & 0xFFFF;
            long count = b.getInt(e + 4) & 0xFFFFFFFFL;
            int size = typeSize(type);
            if (size == 0 || count > 1 << 26) continue;
            long bytes = size * count;
            int vo = bytes <= 4 ? e + 8 : b.getInt(e + 8);
            if (vo < 0 || vo + bytes > b.limit()) continue;
            switch (tag) {
                case 0xFE: f.subType = (long) num(type, vo, 0); break;
                case 0x100: f.width = (int) num(type, vo, 0); break;
                case 0x101: f.height = (int) num(type, vo, 0); break;
                case 0x102: f.bps = ints(type, vo, count); break;
                case 0x103: f.compression = (int) num(type, vo, 0); break;
                case 0x106: f.photometric = (int) num(type, vo, 0); break;
                case 0x111: f.stripOffsets = longs(type, vo, count); break;
                case 0x112: if (top && depth == 0) orientation = (int) num(type, vo, 0); break;
                case 0x115: f.spp = (int) num(type, vo, 0); break;
                case 0x116: f.rowsPerStrip = (int) num(type, vo, 0); break;
                case 0x117: f.stripCounts = longs(type, vo, count); break;
                case 0x11C: f.planar = (int) num(type, vo, 0); break;
                case 0x142: f.tileW = (int) num(type, vo, 0); break;
                case 0x143: f.tileH = (int) num(type, vo, 0); break;
                case 0x144: f.tileOffsets = longs(type, vo, count); break;
                case 0x145: f.tileCounts = longs(type, vo, count); break;
                case 0x14A: for (long s : longs(type, vo, count)) subs.add(s); break;
                case 0x201: f.jpegOffset = (long) num(type, vo, 0); break;
                case 0x202: f.jpegLength = (long) num(type, vo, 0); break;
                case 0x828D: { int[] d = ints(type, vo, count); if (d.length >= 2) { f.cfaRows = d[0]; f.cfaCols = d[1]; } break; }
                case 0x828E: { f.cfa = new byte[(int) count]; for (int k = 0; k < count; ++k) f.cfa[k] = b.get(vo + k); break; }
                case 0xC612: dng = true; break;
                case 0xC618: f.linearization = ints(type, vo, count); break;
                case 0xC619: { int[] d = ints(type, vo, count); if (d.length >= 2) { f.blackRows = d[0]; f.blackCols = d[1]; } break; }
                case 0xC61A: f.black = doubles(type, vo, count); break;
                case 0xC61D: f.white = num(type, vo, 0); break;
                case 0xC621: colorMatrix1 = doubles(type, vo, count); break;
                case 0xC628: asShotNeutral = doubles(type, vo, count); break;
                case 0xC62A: baselineExposure = num(type, vo, 0); break;
                case 0xC714: forwardMatrix1 = doubles(type, vo, count); break;
                default: break;
            }
        }
        ifds.add(f);
        if (depth < 3) for (long s : subs) if (s > 0 && s < b.limit()) readIfd(s, depth + 1, false);
        return b.getInt((int) (off + 2 + 12L * n)) & 0xFFFFFFFFL;
    }

    private static int typeSize(int type) {
        switch (type) {
            case 1: case 2: case 6: case 7: return 1;
            case 3: case 8: return 2;
            case 4: case 9: case 11: case 13: return 4;
            case 5: case 10: case 12: return 8;
            default: return 0;
        }
    }

    private double num(int type, int vo, int i) {
        switch (type) {
            case 1: case 7: return b.get(vo + i) & 0xFF;
            case 6: return b.get(vo + i);
            case 3: return b.getShort(vo + 2 * i) & 0xFFFF;
            case 8: return b.getShort(vo + 2 * i);
            case 4: case 13: return b.getInt(vo + 4 * i) & 0xFFFFFFFFL;
            case 9: return b.getInt(vo + 4 * i);
            case 5: { long n = b.getInt(vo + 8 * i) & 0xFFFFFFFFL, d = b.getInt(vo + 8 * i + 4) & 0xFFFFFFFFL; return d == 0 ? 0 : (double) n / d; }
            case 10: { int n = b.getInt(vo + 8 * i), d = b.getInt(vo + 8 * i + 4); return d == 0 ? 0 : (double) n / d; }
            case 11: return b.getFloat(vo + 4 * i);
            case 12: return b.getDouble(vo + 8 * i);
            default: return 0;
        }
    }

    private double[] doubles(int type, int vo, long count) {
        double[] v = new double[(int) count];
        for (int i = 0; i < count; ++i) v[i] = num(type, vo, i);
        return v;
    }

    private int[] ints(int type, int vo, long count) {
        int[] v = new int[(int) count];
        for (int i = 0; i < count; ++i) v[i] = (int) num(type, vo, i);
        return v;
    }

    private long[] longs(int type, int vo, long count) {
        long[] v = new long[(int) count];
        for (int i = 0; i < count; ++i) v[i] = (long) num(type, vo, i);
        return v;
    }

    // ---------------------------------------------------------------- raw render

    private int W, H, outW, outH, blockX, blockY, rr, rc;
    private byte[] cfa;
    private int[] lin;
    private double[] black;
    private int bRows, bCols;
    private float[] sum;
    private int[] cnt;

    private void render(Ifd f, int reqW, int reqH, Result r) throws IOException {
        if (f.spp != 1 || f.planar != 1) throw new IOException("DNG: raw with " + f.spp + " samples per pixel");
        final int bits = f.bps.length > 0 ? f.bps[0] : 16;
        if (bits < 8 || bits > 16) throw new IOException("DNG: raw of " + bits + " bits");
        W = f.width; H = f.height;
        rr = Math.max(1, f.cfaRows); rc = Math.max(1, f.cfaCols);
        cfa = f.cfa != null && f.cfa.length >= rr * rc ? f.cfa : new byte[]{0, 1, 1, 2};
        if (f.cfa == null) { rr = 2; rc = 2; }
        lin = f.linearization;
        black = f.black != null && f.black.length > 0 ? f.black : new double[]{0};
        bRows = Math.max(1, f.blackRows); bCols = Math.max(1, f.blackCols);
        if (black.length < bRows * bCols) { bRows = 1; bCols = 1; }
        // output: one pixel per block of k x k repeat tiles, about the request (at least the request when the raw allows)
        int k = (int) Math.max(1, Math.floor(Math.min((double) W / rc / Math.max(1, reqW), (double) H / rr / Math.max(1, reqH))));
        if (Math.max(reqW, reqH) <= 0) k = 1;
        blockX = rc * k; blockY = rr * k;
        outW = Math.max(1, W / blockX); outH = Math.max(1, H / blockY);
        sum = new float[outW * outH * 3];
        cnt = new int[outW * outH * 3];
        if (f.compression == 1) uncompressed(f, bits);
        else if (f.compression == 7) lossless(f);
        else throw new IOException("DNG: raw compression " + f.compression);
        double white = f.white > 0 ? f.white : (lin != null ? 65535 : (1 << bits) - 1);
        finish(white, r);
    }

    private void add(int x, int y, int v) {
        int ox = x / blockX, oy = y / blockY;
        if (ox >= outW || oy >= outH) return;
        int value = lin != null ? lin[Math.min(v, lin.length - 1)] : v;
        int c = cfa[(y % rr) * rc + (x % rc)];
        if (c < 0 || c > 2) return;
        double bl = black[(y % bRows) * bCols + (x % bCols)];
        int i = (oy * outW + ox) * 3 + c;
        sum[i] += (float) (value - bl);
        cnt[i]++;
    }

    private void uncompressed(Ifd f, int bits) throws IOException {
        final int rowBytes = (W * bits + 7) / 8;
        if (f.tileOffsets != null && f.tileW > 0 && f.tileH > 0) {
            final int tilesAcross = (W + f.tileW - 1) / f.tileW;
            final int tRowBytes = (f.tileW * bits + 7) / 8;
            for (int t = 0; t < f.tileOffsets.length; ++t) {
                int x0 = (t % tilesAcross) * f.tileW, y0 = (t / tilesAcross) * f.tileH;
                for (int y = 0; y < f.tileH && y0 + y < H; ++y)
                    readRow(f.tileOffsets[t] + (long) y * tRowBytes, bits, Math.min(f.tileW, W - x0), x0, y0 + y);
            }
            return;
        }
        if (f.stripOffsets == null) throw new IOException("DNG: raw without strips");
        final int rps = Math.min(f.rowsPerStrip, H);
        for (int y = 0; y < H; ++y) {
            int s = y / rps;
            if (s >= f.stripOffsets.length) break;
            readRow(f.stripOffsets[s] + (long) (y - s * rps) * rowBytes, bits, W, 0, y);
        }
    }

    private void readRow(long off, int bits, int n, int x0, int y) {
        int o = (int) off;
        if (bits == 16) {
            if (o + 2L * n > b.limit()) return;
            for (int x = 0; x < n; ++x) add(x0 + x, y, b.getShort(o + 2 * x) & 0xFFFF);
        } else if (bits == 8) {
            if (o + n > b.limit()) return;
            for (int x = 0; x < n; ++x) add(x0 + x, y, b.get(o + x) & 0xFF);
        } else { // packed MSB first, rows byte-aligned
            long bitPos = 0;
            for (int x = 0; x < n; ++x) {
                int v = 0;
                for (int k = 0; k < bits; ++k, ++bitPos) {
                    int at = o + (int) (bitPos >> 3);
                    if (at >= b.limit()) return;
                    v = (v << 1) | ((b.get(at) >> (7 - (int) (bitPos & 7))) & 1);
                }
                add(x0 + x, y, v);
            }
        }
    }

    private void lossless(Ifd f) throws IOException {
        final boolean tiled = f.tileOffsets != null && f.tileW > 0 && f.tileH > 0;
        final long[] offs = tiled ? f.tileOffsets : f.stripOffsets, counts = tiled ? f.tileCounts : f.stripCounts;
        if (offs == null || counts == null) throw new IOException("DNG: LJ92 raw without data");
        final int tw = tiled ? f.tileW : W, th = tiled ? f.tileH : Math.min(f.rowsPerStrip, H);
        final int tilesAcross = tiled ? (W + tw - 1) / tw : 1;
        for (int t = 0; t < offs.length; ++t) {
            final int x0 = (t % tilesAcross) * tw, y0 = (t / tilesAcross) * th;
            final int[] dims = new int[2];
            final long[] cursor = {0};
            Lj92Decoder.decode(b, (int) offs[t], (int) counts[t], dims, (row, data) -> {
                // samples in raster order of the tile / strip, whatever the JPEG frame's row length
                long c = cursor[0];
                for (int i = 0; i < data.length; ++i, ++c) {
                    int x = x0 + (int) (c % tw), y = y0 + (int) (c / tw);
                    if (x < W && y < H && x - x0 < tw) add(x, y, data[i]);
                }
                cursor[0] = c;
            });
        }
    }

    private void finish(double white, Result r) {
        final double bl = mean(black);
        final double range = Math.max(1, white - bl);
        // white balance and camera -> linear sRGB
        double[] n = asShotNeutral != null && asShotNeutral.length >= 3 ? asShotNeutral : new double[]{1, 1, 1};
        double[] m = cameraToSrgb(n);
        double gain = Math.pow(2, baselineExposure);
        float[] rgb = new float[outW * outH * 3];
        float[] luma = new float[outW * outH];
        for (int i = 0; i < outW * outH; ++i) {
            double[] cam = new double[3];
            for (int c = 0; c < 3; ++c) {
                int k = i * 3 + c;
                cam[c] = cnt[k] > 0 ? sum[k] / cnt[k] / range : 0;
            }
            for (int c = 0; c < 3; ++c) {
                double v = (m[c * 3] * cam[0] + m[c * 3 + 1] * cam[1] + m[c * 3 + 2] * cam[2]) * gain;
                rgb[i * 3 + c] = (float) v;
            }
            luma[i] = 0.2126f * rgb[i * 3] + 0.7152f * rgb[i * 3 + 1] + 0.0722f * rgb[i * 3 + 2];
        }
        // automatic lift: raw data of a phone is often exposed for the highlights (the camera's tone curve lifts it later)
        float[] sorted = luma.clone();
        java.util.Arrays.sort(sorted);
        double median = sorted[sorted.length / 2], high = sorted[(int) (sorted.length * 0.99)];
        double lift = median > 1e-6 ? Math.max(1, Math.min(8, 0.18 / median)) : 1;
        if (high * lift > 3) lift = Math.max(1, 3 / Math.max(high, 1e-6));
        int[] out = new int[outW * outH];
        for (int i = 0; i < outW * outH; ++i) {
            int argb = 0xFF000000;
            for (int c = 0; c < 3; ++c) {
                double v = Math.max(0, rgb[i * 3 + c] * lift);
                v = v * (1 + v / 9.0) / (1 + v); // soft shoulder, white at 3
                argb |= srgb8(Math.min(1, v)) << (16 - 8 * c);
            }
            out[i] = argb;
        }
        r.argb = out;
        r.width = outW;
        r.height = outH;
    }

    private double[] cameraToSrgb(double[] neutral) {
        // XYZ (D50) -> linear sRGB (Bradford-adapted)
        final double[] xyzToSrgb = {3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540, 0.0719453, -0.2289914, 1.4052427};
        double[] wb = {1 / Math.max(neutral[0], 1e-6), 1 / Math.max(neutral[1], 1e-6), 1 / Math.max(neutral[2], 1e-6)};
        double[] m;
        if (forwardMatrix1 != null && forwardMatrix1.length >= 9) {
            m = mul(xyzToSrgb, mul(forwardMatrix1, diag(wb)));
        } else if (colorMatrix1 != null && colorMatrix1.length >= 9 && invertible(colorMatrix1)) {
            m = mul(xyzToSrgb, inverse(colorMatrix1));
        } else {
            m = diag(wb);
            return m;
        }
        // the neutral maps to white
        double[] w = {m[0] * neutral[0] + m[1] * neutral[1] + m[2] * neutral[2], m[3] * neutral[0] + m[4] * neutral[1] + m[5] * neutral[2],
                m[6] * neutral[0] + m[7] * neutral[1] + m[8] * neutral[2]};
        for (int r = 0; r < 3; ++r) {
            double s = w[r] > 1e-9 ? 1 / w[r] : 1;
            for (int c = 0; c < 3; ++c) m[r * 3 + c] *= s;
        }
        return m;
    }

    static int srgb8(double v) {
        double s = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.max(0, Math.min(1, s)) * 255);
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return v.length > 0 ? s / v.length : 0;
    }

    private static double[] diag(double[] d) {
        return new double[]{d[0], 0, 0, 0, d[1], 0, 0, 0, d[2]};
    }

    static double[] mul(double[] a, double[] b) {
        double[] r = new double[9];
        for (int i = 0; i < 3; ++i) for (int j = 0; j < 3; ++j) r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return r;
    }

    private static double det(double[] m) {
        return m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
    }

    private static boolean invertible(double[] m) {
        return Math.abs(det(m)) > 1e-12;
    }

    static double[] inverse(double[] m) {
        double d = det(m);
        return new double[]{
                (m[4] * m[8] - m[5] * m[7]) / d, (m[2] * m[7] - m[1] * m[8]) / d, (m[1] * m[5] - m[2] * m[4]) / d,
                (m[5] * m[6] - m[3] * m[8]) / d, (m[0] * m[8] - m[2] * m[6]) / d, (m[2] * m[3] - m[0] * m[5]) / d,
                (m[3] * m[7] - m[4] * m[6]) / d, (m[1] * m[6] - m[0] * m[7]) / d, (m[0] * m[4] - m[1] * m[3]) / d};
    }
}
