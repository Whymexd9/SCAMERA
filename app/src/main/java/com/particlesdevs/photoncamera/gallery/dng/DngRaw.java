package com.particlesdevs.photoncamera.gallery.dng;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * P351 (owner, 2026-10-26: «наша галерея полноценно принимала dng»): the raw data of a DNG for the editor - not the embedded
 * preview, not DngPreview's 8-bit box render - as 16-bit linear samples the GPU demosaics (EditRenderer, demosaic.glsl).
 *
 * <p>Read: the largest raw IFD (CFA 32803 or LinearRaw 34892), uncompressed 8..16-bit strips / tiles or lossless JPEG
 * (compression 7, {@link Lj92Decoder}); the linearisation table, black level (BlackLevelRepeatDim pattern, DeltaH / DeltaV),
 * white level, OpcodeList2 GainMap (lens shading, active-area relative), ActiveArea and DefaultCrop; each sample becomes
 * {@code (value - black) / (white - black) x gain}, stored as fixed point ({@link #FIXED_ONE} = the white level, up to 4x for
 * shading gains) in a direct {@link ShortBuffer} - 2 bytes a sample, 100 MB for 50 MP.
 *
 * <p>CFA: a 2x2 Bayer (any phase) stays a Bayer mosaic; a 4x4 Quad (2x2 same-colour cells) becomes a half-size Bayer, each
 * cell averaged (the editor has no Quad demosaic: a quarter of the pixels, said in the UI); {@code bin} averages bin x bin
 * same-colour samples more (previews; RAWs over the export budget). LinearRaw (3 samples) stays RGB. Other patterns (X-Trans,
 * 3x3...) are refused: the caller falls back to DngPreview. Pure Java, no Android classes (JVM tests).
 */
public final class DngRaw {
    /** 1.0 (the white level) in the fixed-point samples; 65535 is 4.0. */
    public static final float FIXED_ONE = 16384f;
    public static final int KIND_BAYER = 0, KIND_RGB = 1;
    /** Bayer phase codes: the colours at (0,0) (1,0) (0,1) (1,1). */
    public static final int RGGB = 0, GRBG = 1, GBRG = 2, BGGR = 3;

    /** What a decode would give, read from the tags only (cheap). */
    public static final class Info {
        public int kind;
        /** Raw samples inside the crop (before the Quad halving and binning). */
        public int cropWidth, cropHeight;
        /** 1 for Bayer / RGB, 2 for a Quad CFA (2x2 cells). */
        public int cell = 1;
        public int orientation = 1;
        public double baselineExposure;
        public DngColor colour;
        public boolean gainMap;
        public int bits;
        public int compression;

        /** The size of a decode with {@code bin}: {width, height}. */
        public int[] size(int bin) {
            if (kind == KIND_RGB) return new int[]{Math.max(1, cropWidth / bin), Math.max(1, cropHeight / bin)};
            int step = 2 * cell * bin;
            return new int[]{Math.max(2, cropWidth / step * 2), Math.max(2, cropHeight / step * 2)};
        }
    }

    /** A decode: fixed-point samples, row-major ({@code width x height}, x3 for RGB), stored orientation. */
    public static final class Image {
        public Info info;
        public int width, height, bin;
        public int phase;
        public ShortBuffer data;
        /** Automatic exposure lift in stops (a dark phone RAW, as DngPreview): from the green median and the 99th percentile. */
        public float autoLiftStops;
        /** P352: what one sample unit is (1 / FIXED_ONE here; LibRaw's developed RGB: 1 / 65535). */
        public float scale = 1f / FIXED_ONE;
        /** P352: what holds {@code data} when it is not this object's own buffer (a LibRaw handle), closed with the image. */
        public java.io.Closeable owner;
        /** P352: a line for the editor («Quad: половина разрешения»...), null for none. */
        public String note;

        public void close() {
            if (owner != null) {
                try {
                    owner.close();
                } catch (java.io.IOException ignored) {
                }
                owner = null;
            }
            data = null;
        }
    }

    private final DngPreview p;
    private final DngPreview.Ifd f;
    private final Info info = new Info();
    // geometry: raw coordinates of the crop, the active area origin
    private int x0, y0, aaLeft, aaTop, aaW, aaH;
    private int rr = 2, rc = 2;
    private byte[] cfa;
    private int[] lin;
    private double[] black;
    private int bRows = 1, bCols = 1;
    private double[] deltaH, deltaV;
    private double white;
    private final List<GainMap> gainMaps = new ArrayList<>();
    // output
    private int outW, outH, bin, spp = 1, step;
    private ShortBuffer out;
    private float inv;

    private DngRaw(DngPreview p, DngPreview.Ifd f) {
        this.p = p;
        this.f = f;
    }

    /** True for a TIFF / DNG header (see {@link DngPreview#isTiff}). */
    public static boolean isDng(byte[] head, int n) {
        return DngPreview.isTiff(head, n);
    }

    /** The tags of the raw, or an IOException when this DNG has nothing the editor can decode. */
    public static Info probe(ByteBuffer buffer) throws IOException {
        return open(buffer).info;
    }

    /** Decodes the raw with {@code bin} (1, 2, 4...: same-colour samples averaged in bin x bin). */
    public static Image decode(ByteBuffer buffer, int bin) throws IOException {
        DngRaw r = open(buffer);
        return r.run(Math.max(1, bin));
    }

    private static DngRaw open(ByteBuffer buffer) throws IOException {
        DngPreview p = DngPreview.parse(buffer);
        DngPreview.Ifd raw = null;
        for (DngPreview.Ifd f : p.ifds) {
            if ((f.photometric == 32803 || f.photometric == 34892) && (f.subType & 1) == 0
                    && (raw == null || (long) f.width * f.height > (long) raw.width * raw.height)) raw = f;
        }
        if (raw == null) throw new IOException("DNG: no raw image");
        DngRaw r = new DngRaw(p, raw);
        r.setup();
        return r;
    }

    private void setup() throws IOException {
        info.orientation = p.orientation;
        info.baselineExposure = p.baselineExposure;
        info.colour = DngColor.of(p);
        info.compression = f.compression;
        info.bits = f.bps.length > 0 ? f.bps[0] : 16;
        if (f.compression != 1 && f.compression != 7) throw new IOException("DNG: raw compression " + f.compression);
        if (info.bits < 8 || info.bits > 16) throw new IOException("DNG: raw of " + info.bits + " bits");
        if (f.planar != 1) throw new IOException("DNG: planar raw");
        if (f.photometric == 34892) {
            if (f.spp != 3) throw new IOException("DNG: LinearRaw with " + f.spp + " samples");
            info.kind = KIND_RGB;
            spp = 3;
        } else {
            if (f.spp != 1) throw new IOException("DNG: CFA with " + f.spp + " samples");
            info.kind = KIND_BAYER;
            rr = Math.max(1, f.cfaRows);
            rc = Math.max(1, f.cfaCols);
            cfa = f.cfa != null && f.cfa.length >= rr * rc ? f.cfa : null;
            if (cfa == null) {
                rr = rc = 2;
                cfa = new byte[]{0, 1, 1, 2};
            }
            if (rr == 2 && rc == 2) info.cell = 1;
            else if (rr == 4 && rc == 4 && quadCells()) info.cell = 2;
            else throw new IOException("DNG: CFA " + rc + "x" + rr + " is not Bayer or Quad");
        }
        // active area (top, left, bottom, right) and the default crop inside it
        int[] aa = f.activeArea != null && f.activeArea.length >= 4 ? f.activeArea : new int[]{0, 0, f.height, f.width};
        aaTop = clampI(aa[0], 0, f.height - 1);
        aaLeft = clampI(aa[1], 0, f.width - 1);
        aaH = clampI(aa[2], aaTop + 1, f.height) - aaTop;
        aaW = clampI(aa[3], aaLeft + 1, f.width) - aaLeft;
        int cx = 0, cy = 0, cw = aaW, ch = aaH;
        if (f.cropOrigin != null && f.cropOrigin.length >= 2 && f.cropSize != null && f.cropSize.length >= 2) {
            cx = clampI((int) Math.round(f.cropOrigin[0]), 0, aaW - 1);
            cy = clampI((int) Math.round(f.cropOrigin[1]), 0, aaH - 1);
            cw = clampI((int) Math.round(f.cropSize[0]), 1, aaW - cx);
            ch = clampI((int) Math.round(f.cropSize[1]), 1, aaH - cy);
        }
        if (info.cell == 2) { // whole 2x2 cells
            int ax = cx & 1, ay = cy & 1;
            cx += ax;
            cy += ay;
            cw = Math.max(2, cw - ax);
            ch = Math.max(2, ch - ay);
        }
        x0 = aaLeft + cx;
        y0 = aaTop + cy;
        info.cropWidth = cw;
        info.cropHeight = ch;
        lin = f.linearization;
        black = f.black != null && f.black.length > 0 ? f.black : new double[]{0};
        bRows = Math.max(1, f.blackRows);
        bCols = Math.max(1, f.blackCols);
        if (black.length < bRows * bCols * spp) {
            bRows = bCols = 1;
            if (black.length < spp) black = new double[]{black[0], black[0], black[0]};
        }
        deltaH = f.blackDeltaH;
        deltaV = f.blackDeltaV;
        white = f.white > 0 ? f.white : (lin != null ? 65535 : (1 << info.bits) - 1);
        if (f.opcodeList2 != null) parseOpcodes(f.opcodeList2);
        info.gainMap = !gainMaps.isEmpty();
    }

    /** A 4x4 pattern of 2x2 single-colour cells that reduces to a Bayer. */
    private boolean quadCells() {
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++)
                if (cfa[y * 4 + x] != cfa[(y & ~1) * 4 + (x & ~1)]) return false;
        int r = 0, g = 0, b = 0;
        for (int y = 0; y < 4; y += 2)
            for (int x = 0; x < 4; x += 2) {
                int c = cfa[y * 4 + x];
                if (c == 0) r++;
                else if (c == 1) g++;
                else if (c == 2) b++;
            }
        return r == 1 && g == 2 && b == 1;
    }

    /** The colour of the CFA at raw (x, y): the pattern starts at the active area's corner. */
    private int cfaAt(int x, int y) {
        return cfa[Math.floorMod(y - aaTop, rr) * rc + Math.floorMod(x - aaLeft, rc)];
    }

    /** The Bayer phase of the decoded mosaic (its output pixel (px, py) comes from the crop corner + q * (px, py)). */
    int phase() {
        int q = info.cell;
        int c00 = cfaAt(x0, y0), c10 = cfaAt(x0 + q, y0), c01 = cfaAt(x0, y0 + q), c11 = cfaAt(x0 + q, y0 + q);
        return phaseOf(c00, c10, c01, c11);
    }

    static int phaseOf(int c00, int c10, int c01, int c11) {
        if (c00 == 0 && c10 == 1 && c01 == 1 && c11 == 2) return RGGB;
        if (c00 == 1 && c10 == 0 && c01 == 2 && c11 == 1) return GRBG;
        if (c00 == 1 && c10 == 2 && c01 == 0 && c11 == 1) return GBRG;
        if (c00 == 2 && c10 == 1 && c01 == 1 && c11 == 0) return BGGR;
        return -1;
    }

    // ---------------------------------------------------------------- gain maps (DNG 1.3 opcode 9)

    static final class GainMap {
        int top, left, bottom, right, plane, planes, rowPitch, colPitch, pointsV, pointsH, mapPlanes;
        double spacingV, spacingH, originV, originH;
        float[] gains;

        float at(double rv, double rh, int plane) {
            double mv = (rv - originV) / Math.max(spacingV, 1e-12), mh = (rh - originH) / Math.max(spacingH, 1e-12);
            mv = Math.max(0, Math.min(pointsV - 1, mv));
            mh = Math.max(0, Math.min(pointsH - 1, mh));
            int v0 = (int) mv, h0 = (int) mh, v1 = Math.min(pointsV - 1, v0 + 1), h1 = Math.min(pointsH - 1, h0 + 1);
            double tv = mv - v0, th = mh - h0;
            int p = Math.min(plane, mapPlanes - 1);
            double a = gains[(v0 * pointsH + h0) * mapPlanes + p], b = gains[(v0 * pointsH + h1) * mapPlanes + p];
            double c = gains[(v1 * pointsH + h0) * mapPlanes + p], d = gains[(v1 * pointsH + h1) * mapPlanes + p];
            return (float) ((a + (b - a) * th) * (1 - tv) + (c + (d - c) * th) * tv);
        }
    }

    private void parseOpcodes(byte[] list) {
        gainMaps.addAll(parseGainMaps(list));
    }

    /**
     * OpcodeList2: big-endian {count; {id, version, flags, bytes, parameters}...}; GainMap (9) kept, the rest skipped. P370 (A10):
     * the gallery's DngPreview reads the same maps.
     */
    static List<GainMap> parseGainMaps(byte[] list) {
        final List<GainMap> gainMaps = new ArrayList<>();
        try {
            ByteBuffer o = ByteBuffer.wrap(list).order(ByteOrder.BIG_ENDIAN);
            int n = o.getInt();
            for (int i = 0; i < n && o.remaining() >= 16; i++) {
                int id = o.getInt();
                o.getInt();
                o.getInt();
                int bytes = o.getInt();
                int start = o.position();
                if (bytes < 0 || start + bytes > list.length) break;
                if (id == 9 && bytes >= 76) {
                    GainMap g = new GainMap();
                    g.top = o.getInt();
                    g.left = o.getInt();
                    g.bottom = o.getInt();
                    g.right = o.getInt();
                    g.plane = o.getInt();
                    g.planes = o.getInt();
                    g.rowPitch = Math.max(1, o.getInt());
                    g.colPitch = Math.max(1, o.getInt());
                    g.pointsV = o.getInt();
                    g.pointsH = o.getInt();
                    g.spacingV = o.getDouble();
                    g.spacingH = o.getDouble();
                    g.originV = o.getDouble();
                    g.originH = o.getDouble();
                    g.mapPlanes = Math.max(1, o.getInt());
                    // P370 (review): the count in long - 5 x 214748365 points wrapped to a 4-byte count and an OutOfMemoryError
                    final long count = (long) g.pointsV * g.pointsH * g.mapPlanes;
                    if (g.pointsV > 0 && g.pointsH > 0 && count > 0 && count * 4 <= bytes - 76) {
                        g.gains = new float[(int) count];
                        for (int k = 0; k < count; k++) g.gains[k] = o.getFloat();
                        gainMaps.add(g);
                    }
                }
                o.position(start + bytes);
            }
        } catch (RuntimeException ignored) {
            // a damaged list: no shading correction
        }
        return gainMaps;
    }

    // ---------------------------------------------------------------- decode

    private Image run(int bin) throws IOException {
        this.bin = bin;
        int[] size = info.size(bin);
        outW = size[0];
        outH = size[1];
        step = info.kind == KIND_RGB ? bin : 2 * info.cell * bin;
        int channels = info.kind == KIND_RGB ? 3 : 1;
        long n = (long) outW * outH * channels;
        if (n > Integer.MAX_VALUE / 2) throw new IOException("DNG: raw too large to decode");
        out = ByteBuffer.allocateDirect((int) n * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        int perOut = info.kind == KIND_RGB ? bin * bin : info.cell * info.cell * bin * bin;
        inv = FIXED_ONE / perOut;
        if (f.compression == 1) uncompressed();
        else lossless();
        Image img = new Image();
        img.info = info;
        img.width = outW;
        img.height = outH;
        img.bin = bin;
        img.phase = info.kind == KIND_BAYER ? phase() : 0;
        if (info.kind == KIND_BAYER && img.phase < 0) throw new IOException("DNG: CFA phase is not a Bayer");
        img.data = out;
        img.autoLiftStops = autoLift(img);
        return img;
    }

    /** One sample of the raw at (x, y), channel {@code ch} (RGB) - normalised, shaded, accumulated into its output cell. */
    private void add(int x, int y, int ch, int v) {
        int dx = x - x0, dy = y - y0;
        if (dx < 0 || dy < 0) return;
        int ox, oy;
        if (info.kind == KIND_RGB) {
            ox = dx / bin;
            oy = dy / bin;
        } else {
            int q = info.cell;
            ox = 2 * (dx / step) + (dx % (2 * q)) / q;
            oy = 2 * (dy / step) + (dy % (2 * q)) / q;
        }
        if (ox >= outW || oy >= outH) return;
        int value = lin != null ? lin[Math.min(v, lin.length - 1)] : v;
        int ax = x - aaLeft, ay = y - aaTop;
        double bl = black[(Math.floorMod(ay, bRows) * bCols + Math.floorMod(ax, bCols)) * spp + (spp == 3 ? ch : 0)];
        if (deltaH != null && ax >= 0 && ax < deltaH.length) bl += deltaH[ax];
        if (deltaV != null && ay >= 0 && ay < deltaV.length) bl += deltaV[ay];
        double s = (value - bl) / Math.max(1, white - bl);
        if (!gainMaps.isEmpty()) {
            int plane = spp == 3 ? ch : 0;
            for (GainMap g : gainMaps) {
                if (ay < g.top || ay >= g.bottom || ax < g.left || ax >= g.right) continue;
                if ((ay - g.top) % g.rowPitch != 0 || (ax - g.left) % g.colPitch != 0) continue;
                if (plane < g.plane || plane >= g.plane + g.planes) continue;
                s *= g.at((ay + 0.5) / aaH, (ax + 0.5) / aaW, plane - g.plane);
            }
        }
        if (s <= 0) return;
        int i = (oy * outW + ox) * (info.kind == KIND_RGB ? 3 : 1) + (info.kind == KIND_RGB ? ch : 0);
        int acc = (out.get(i) & 0xFFFF) + (int) Math.round(s * inv);
        out.put(i, (short) Math.min(65535, acc));
    }

    private void uncompressed() throws IOException {
        final int bits = info.bits;
        final int W = f.width, H = f.height;
        if (f.tileOffsets != null && f.tileW > 0 && f.tileH > 0) {
            final int across = (W + f.tileW - 1) / f.tileW;
            final int tRowBytes = (f.tileW * spp * bits + 7) / 8;
            for (int t = 0; t < f.tileOffsets.length; ++t) {
                int tx = (t % across) * f.tileW, ty = (t / across) * f.tileH;
                if (ty + f.tileH <= y0 || tx + f.tileW <= x0) continue;
                for (int y = 0; y < f.tileH && ty + y < H; ++y)
                    readRow(f.tileOffsets[t] + (long) y * tRowBytes, bits, Math.min(f.tileW, W - tx), tx, ty + y);
            }
            return;
        }
        if (f.stripOffsets == null) throw new IOException("DNG: raw without strips");
        final int rowBytes = (W * spp * bits + 7) / 8;
        final int rps = Math.max(1, Math.min(f.rowsPerStrip, H));
        int yEnd = Math.min(H, y0 + info.cropHeight);
        for (int y = Math.max(0, y0); y < yEnd; ++y) {
            int s = y / rps;
            if (s >= f.stripOffsets.length) break;
            readRow(f.stripOffsets[s] + (long) (y - s * rps) * rowBytes, bits, W, 0, y);
        }
    }

    private void readRow(long off, int bits, int n, int xStart, int y) {
        ByteBuffer b = p.buffer();
        int o = (int) off;
        int samples = n * spp;
        if (bits == 16) {
            if (o + 2L * samples > b.limit()) return;
            for (int i = 0; i < samples; ++i) add(xStart + i / spp, y, i % spp, b.getShort(o + 2 * i) & 0xFFFF);
        } else if (bits == 8) {
            if (o + (long) samples > b.limit()) return;
            for (int i = 0; i < samples; ++i) add(xStart + i / spp, y, i % spp, b.get(o + i) & 0xFF);
        } else { // packed MSB first, rows byte-aligned
            long bitPos = 0;
            for (int i = 0; i < samples; ++i) {
                int v = 0;
                for (int k = 0; k < bits; ++k, ++bitPos) {
                    int at = o + (int) (bitPos >> 3);
                    if (at >= b.limit()) return;
                    v = (v << 1) | ((b.get(at) >> (7 - (int) (bitPos & 7))) & 1);
                }
                add(xStart + i / spp, y, i % spp, v);
            }
        }
    }

    private void lossless() throws IOException {
        final int W = f.width, H = f.height;
        final boolean tiled = f.tileOffsets != null && f.tileW > 0 && f.tileH > 0;
        final long[] offs = tiled ? f.tileOffsets : f.stripOffsets, counts = tiled ? f.tileCounts : f.stripCounts;
        if (offs == null || counts == null) throw new IOException("DNG: LJ92 raw without data");
        final int tw = tiled ? f.tileW : W, th = tiled ? f.tileH : Math.min(f.rowsPerStrip, H);
        final int across = tiled ? (W + tw - 1) / tw : 1;
        ByteBuffer b = p.buffer();
        for (int t = 0; t < offs.length; ++t) {
            final int tx = (t % across) * tw, ty = (t / across) * th;
            if (ty >= y0 + info.cropHeight || tx >= x0 + info.cropWidth || ty + th <= y0 || tx + tw <= x0) continue;
            final long[] cursor = {0};
            Lj92Decoder.decode(b, (int) offs[t], (int) counts[t], new int[2], (row, data) -> {
                // samples in raster order of the tile / strip (spp per pixel), whatever the JPEG frame's row length
                long c = cursor[0];
                for (int i = 0; i < data.length; ++i, ++c) {
                    long px = c / spp;
                    int x = tx + (int) (px % tw), y = ty + (int) (px / tw);
                    if (x < W && y < H) add(x, y, (int) (c % spp), data[i]);
                }
                cursor[0] = c;
            });
        }
    }

    /**
     * P351: a phone RAW is exposed for its highlights; like DngPreview the editor lifts it so the green median reaches ~0.18,
     * by at most 3 stops and only while the 99th percentile stays under 4x white (the filmic shoulder's white).
     */
    public static float autoLift(Image img) {
        int w = img.width, h = img.height;
        int stride = Math.max(1, (int) Math.sqrt((double) w * h / 40000.0));
        List<Float> g = new ArrayList<>();
        for (int y = 0; y < h; y += stride)
            for (int x = 0; x < w; x += stride) {
                float v;
                if (img.info.kind == KIND_RGB) v = (img.data.get((y * w + x) * 3 + 1) & 0xFFFF) * img.scale;
                else {
                    int gx = x & ~1, gy = y & ~1;
                    // the green of this 2x2 block
                    int px = (img.phase == RGGB || img.phase == BGGR) ? 1 : 0;
                    v = (img.data.get(gy * w + gx + px) & 0xFFFF) * img.scale;
                }
                g.add(v);
            }
        if (g.isEmpty()) return 0f;
        g.sort(null);
        double median = g.get(g.size() / 2), high = g.get(Math.min(g.size() - 1, (int) (g.size() * 0.99)));
        if (median <= 1e-6) return 0f;
        double lift = Math.max(1, Math.min(8, 0.18 / median));
        if (high * lift > 4) lift = Math.max(1, 4 / Math.max(high, 1e-6));
        return (float) (Math.log(lift) / Math.log(2));
    }

    private static int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
