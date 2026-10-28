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
 * the linearisation table, black / white levels, the OpcodeList2 GainMap (lens shading, P370), any CFA repeat pattern (2x2
 * Bayer, 4x4 Quad), each output pixel the box mean of its block per colour; white balance and camera to sRGB as the editor's
 * {@link DngColor} (P370; ForwardMatrix1 / the inverse of ColorMatrix1 before), BaselineExposure, an automatic lift for dark raw
 * data, a soft highlight shoulder and the sRGB curve.
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
        // P351 (the editor's RAW decode, DngRaw): the raw IFD's area, crop, black deltas and stage-2 opcodes
        int[] activeArea;
        double[] cropOrigin, cropSize, blackDeltaH, blackDeltaV;
        byte[] opcodeList2;
    }

    private final ByteBuffer b;
    final List<Ifd> ifds = new ArrayList<>();
    int orientation = 1;
    double[] colorMatrix1, forwardMatrix1, asShotNeutral;
    double baselineExposure;
    // P351: the second calibration and the rest of the DNG colour tags (DngColor)
    double[] colorMatrix2, forwardMatrix2, cameraCalibration1, cameraCalibration2, analogBalance, asShotWhiteXY;
    int illuminant1, illuminant2;
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

    /** P351: the parsed file (IFDs and DNG tags) for DngRaw. */
    static DngPreview parse(ByteBuffer buffer) throws IOException {
        return new DngPreview(buffer);
    }

    ByteBuffer buffer() {
        return b;
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
                case 0xC715: forwardMatrix2 = doubles(type, vo, count); break;
                case 0xC622: colorMatrix2 = doubles(type, vo, count); break;
                case 0xC623: cameraCalibration1 = doubles(type, vo, count); break;
                case 0xC624: cameraCalibration2 = doubles(type, vo, count); break;
                case 0xC627: analogBalance = doubles(type, vo, count); break;
                case 0xC629: asShotWhiteXY = doubles(type, vo, count); break;
                case 0xC65A: illuminant1 = (int) num(type, vo, 0); break;
                case 0xC65B: illuminant2 = (int) num(type, vo, 0); break;
                case 0xC68D: f.activeArea = ints(type, vo, count); break;
                case 0xC61F: f.cropOrigin = doubles(type, vo, count); break;
                case 0xC620: f.cropSize = doubles(type, vo, count); break;
                case 0xC61B: f.blackDeltaH = doubles(type, vo, count); break;
                case 0xC61C: f.blackDeltaV = doubles(type, vo, count); break;
                case 0xC740: { f.opcodeList2 = new byte[(int) count]; ByteBuffer d = b.duplicate(); d.position(vo); d.get(f.opcodeList2); break; }
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
    // P370 (A10): the OpcodeList2 lens shading (DngRaw's GainMap parser; null: none) and the active area its maps are relative to
    private Shade[] shading;
    private int aaTop, aaLeft, aaW, aaH;

    /**
     * P370 (A10): one GainMap of a CFA plane (DngRaw.GainMap.at, the editor's shading) with the row of the map interpolated once a
     * raw row: the preview reads every sample, a thumbnail too.
     */
    private static final class Shade {
        final DngRaw.GainMap g;
        final double[] row;
        int rowY = Integer.MIN_VALUE;

        Shade(DngRaw.GainMap g) {
            this.g = g;
            row = new double[g.pointsH];
        }

        double at(int ax, int ay, int aaW, int aaH) {
            if (ay < g.top || ay >= g.bottom || ax < g.left || ax >= g.right) return 1;
            if ((ay - g.top) % g.rowPitch != 0 || (ax - g.left) % g.colPitch != 0) return 1;
            if (0 < g.plane || 0 >= g.plane + g.planes) return 1;
            if (ay != rowY) {
                rowY = ay;
                for (int h = 0; h < g.pointsH; h++) row[h] = g.at((ay + 0.5) / aaH, g.originH + h * g.spacingH, 0);
            }
            double mh = ((ax + 0.5) / aaW - g.originH) / Math.max(g.spacingH, 1e-12);
            mh = Math.max(0, Math.min(g.pointsH - 1, mh));
            final int h0 = (int) mh, h1 = Math.min(g.pointsH - 1, h0 + 1);
            return row[h0] + (row[h1] - row[h0]) * (mh - h0);
        }

        /**
         * P370 (review): the gain of a CFA site's samples in an output block - at their mean position (mx, my), active-area
         * relative; (sx, sy) one of the site's positions (its pitch phase): 1 where the map does not apply to the site.
         */
        double atSite(double mx, double my, int sx, int sy, int aaW, int aaH) {
            if (my < g.top || my >= g.bottom || mx < g.left || mx >= g.right) return 1;
            if (Math.floorMod(sy - g.top, g.rowPitch) != 0 || Math.floorMod(sx - g.left, g.colPitch) != 0) return 1;
            if (0 < g.plane || 0 >= g.plane + g.planes) return 1;
            return g.at((my + 0.5) / aaH, (mx + 0.5) / aaW, 0);
        }
    }

    /**
     * P370 (review): tests only - shade every sample (the reference) instead of each output block's CFA sites (the per-sample path
     * cost a 12 MP DNG's thumbnail 3.9 x the time: four maps looked up for each of 50 M samples).
     */
    static boolean perSampleShadingForTest;

    /** Each map's pitch divides the CFA repeat: a site's samples in a block share the maps that apply to them. */
    private static boolean sitesShadable(Shade[] maps, int rows, int cols) {
        for (Shade s : maps) if (rows % s.g.rowPitch != 0 || cols % s.g.colPitch != 0) return false;
        return true;
    }

    /**
     * P370 (review): the lens shading of the unshaded block sums - each colour's sum times the mean gain of that colour's CFA
     * sites, each site's gain at its samples' mean position in the block (a smooth map: within 0.5 % of shading every sample).
     */
    private void shadeBlocks(Shade[] maps) {
        final int k = blockX / rc;
        final double[] g = new double[3];
        final int[] n = new int[3];
        for (int oy = 0; oy < outH; oy++) {
            for (int ox = 0; ox < outW; ox++) {
                g[0] = g[1] = g[2] = 0;
                n[0] = n[1] = n[2] = 0;
                for (int py = 0; py < rr; py++) {
                    for (int px = 0; px < rc; px++) {
                        final int c = cfa[py * rc + px];
                        if (c < 0 || c > 2) continue;
                        final double mx = ox * blockX + px + rc * (k - 1) / 2.0 - aaLeft, my = oy * blockY + py + rr * (k - 1) / 2.0 - aaTop;
                        double s = 1;
                        for (Shade m : maps) s *= m.atSite(mx, my, px - aaLeft, py - aaTop, aaW, aaH);
                        g[c] += s;
                        n[c]++;
                    }
                }
                final int i = (oy * outW + ox) * 3;
                for (int c = 0; c < 3; c++) if (n[c] > 0) sum[i + c] = (float) (sum[i + c] * (g[c] / n[c]));
            }
        }
    }

    private void render(Ifd f, int reqW, int reqH, Result r) throws IOException {
        if (f.spp != 1 || f.planar != 1) throw new IOException("DNG: raw with " + f.spp + " samples per pixel");
        final int bits = f.bps.length > 0 ? f.bps[0] : 16;
        if (bits < 8 || bits > 16) throw new IOException("DNG: raw of " + bits + " bits");
        W = f.width; H = f.height;
        // P370 (A10): our DNGs carry the lens shading as OpcodeList2 GainMaps and embed no preview: without them every thumbnail's
        // corners came out about 1.25 EV dark and tinted (corner gain 2.386 in the owner's log)
        final List<DngRaw.GainMap> maps = f.opcodeList2 != null ? DngRaw.parseGainMaps(f.opcodeList2) : new ArrayList<>();
        shading = null;
        if (!maps.isEmpty()) {
            shading = new Shade[maps.size()];
            for (int i = 0; i < shading.length; i++) shading[i] = new Shade(maps.get(i));
        }
        final int[] aa = f.activeArea != null && f.activeArea.length >= 4 ? f.activeArea : new int[]{0, 0, H, W};
        aaTop = Math.max(0, Math.min(H - 1, aa[0]));
        aaLeft = Math.max(0, Math.min(W - 1, aa[1]));
        aaH = Math.max(aaTop + 1, Math.min(H, aa[2])) - aaTop;
        aaW = Math.max(aaLeft + 1, Math.min(W, aa[3])) - aaLeft;
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
        // P370 (review): the maps per output block where each map's pitch divides the CFA repeat (our DNGs: one map per Bayer site),
        // else per sample
        Shade[] blockShading = null;
        if (shading != null && !perSampleShadingForTest && sitesShadable(shading, rr, rc)) {
            blockShading = shading;
            shading = null;
        }
        if (f.compression == 1) uncompressed(f, bits);
        else if (f.compression == 7) lossless(f);
        else throw new IOException("DNG: raw compression " + f.compression);
        if (blockShading != null) shadeBlocks(blockShading);
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
        if (shading == null) sum[i] += (float) (value - bl);
        else {
            double s = value - bl;
            for (Shade g : shading) s *= g.at(x - aaLeft, y - aaTop, aaW, aaH);
            sum[i] += (float) s;
        }
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
        double[] m = previewMatrix(n);
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

    /**
     * P370 (A10): camera RGB -> linear sRGB of the preview: the editor's colour (DngColor at its as-shot neutral - AsShotNeutral,
     * else AsShotWhiteXY, else 1, 1, 1 -: both calibrations interpolated at the white, CameraCalibration and AnalogBalance, the
     * ColorMatrix route adapted to D50), the neutral to white. ForwardMatrix1 alone (else the unadapted inverse ColorMatrix1)
     * before; a single-profile DNG (P243: CM1 + FM1) gives the same matrix. Without a ColorMatrix, or when the matrices give no
     * finite result, the former matrix at {@code n} (the AsShotNeutral, else 1, 1, 1).
     */
    double[] previewMatrix(double[] n) {
        final DngColor colour = DngColor.of(this);
        if (colour.hasMatrices()) {
            final double[] m = colour.cameraToSrgb(colour.asShotNeutral);
            boolean finite = true;
            for (double v : m) finite &= Double.isFinite(v);
            if (finite) return m;
        }
        return cameraToSrgb(n);
    }

    /** The former preview matrix (ForwardMatrix1, else the inverse ColorMatrix1): {@link #previewMatrix}'s fallback. */
    double[] cameraToSrgb(double[] neutral) {
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
