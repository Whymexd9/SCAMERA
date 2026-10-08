package com.particlesdevs.photoncamera.gallery.dng;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * P59: lossless JPEG (ITU T.81 process 14, SOF3) as DNG compression 7 stores raw data: our DNG writer (tinydng's lj92, one
 * component 2W x H/2 = the Bayer rows in pairs) and camera DNGs (one to four components per tile). The decoded samples come
 * out in raster order of the JPEG frame (X * Nc samples per row), which is the raster order of the strip / tile they encode.
 */
public final class Lj92Decoder {
    private final ByteBuffer d;
    private int pos;
    private final int end;
    // Huffman tables (DC class, up to 4)
    private final int[][] maxCode = new int[4][], valPtr = new int[4][], minCode = new int[4][];
    private final int[][] vals = new int[4][];
    private int precision, width, height, comps, predictor, pointTransform, restartInterval;
    private final int[] compTable = new int[4];

    private Lj92Decoder(ByteBuffer data, int offset, int length) {
        d = data;
        pos = offset;
        end = offset + length;
    }

    /** Samples of the frame (X * Nc per row, Y rows; absolute offsets into {@code data}); dims[0] = X * Nc, dims[1] = Y. */
    public static char[] decode(ByteBuffer data, int offset, int length, int[] dims) throws IOException {
        Lj92Decoder dec = new Lj92Decoder(data, offset, length);
        return dec.run(dims);
    }

    /** The same, row by row into {@code sink} (two rows of memory instead of the whole frame). */
    public static void decode(ByteBuffer data, int offset, int length, int[] dims, RowSink sink) throws IOException {
        Lj92Decoder dec = new Lj92Decoder(data, offset, length);
        dec.sink = sink;
        dec.run(dims);
    }

    private int u8() throws IOException {
        if (pos >= end) throw new IOException("LJ92: truncated");
        return d.get(pos++) & 0xFF;
    }

    private int u16() throws IOException {
        return (u8() << 8) | u8();
    }

    private char[] run(int[] dims) throws IOException {
        if (u8() != 0xFF || u8() != 0xD8) throw new IOException("LJ92: no SOI");
        while (true) {
            int m = u8();
            if (m != 0xFF) continue;
            int marker = u8();
            while (marker == 0xFF) marker = u8();
            if (marker == 0xD9) throw new IOException("LJ92: no scan");
            int len = u16();
            int segEnd = pos + len - 2;
            switch (marker) {
                case 0xC4: // DHT
                    while (pos < segEnd) {
                        int tcth = u8(), th = tcth & 3;
                        int[] counts = new int[17];
                        int total = 0;
                        for (int i = 1; i <= 16; ++i) { counts[i] = u8(); total += counts[i]; }
                        int[] v = new int[total];
                        for (int i = 0; i < total; ++i) v[i] = u8();
                        buildTable(th, counts, v);
                    }
                    break;
                case 0xC3: // SOF3
                    precision = u8();
                    height = u16();
                    width = u16();
                    comps = u8();
                    if (comps < 1 || comps > 4) throw new IOException("LJ92: components " + comps);
                    for (int i = 0; i < comps; ++i) { u8(); u8(); u8(); }
                    break;
                case 0xDD: // DRI
                    restartInterval = u16();
                    break;
                case 0xDA: { // SOS
                    int ns = u8();
                    for (int i = 0; i < ns; ++i) { u8(); int t = u8(); compTable[i] = (t >> 4) & 3; }
                    predictor = u8();
                    u8(); // Se
                    pointTransform = u8() & 15;
                    pos = segEnd;
                    if (width <= 0 || height <= 0) throw new IOException("LJ92: no frame");
                    dims[0] = width * comps;
                    dims[1] = height;
                    return scan();
                }
                default:
                    if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC && marker != 0xC3)
                        throw new IOException("LJ92: unsupported frame 0x" + Integer.toHexString(marker));
                    break;
            }
            pos = segEnd;
        }
    }

    private void buildTable(int th, int[] counts, int[] v) {
        int[] max = new int[18], min = new int[17], ptr = new int[17];
        int code = 0, k = 0;
        for (int l = 1; l <= 16; ++l) {
            ptr[l] = k;
            min[l] = code;
            code += counts[l];
            k += counts[l];
            max[l] = counts[l] > 0 ? code - 1 : -1;
            code <<= 1;
        }
        max[17] = Integer.MAX_VALUE;
        maxCode[th] = max;
        minCode[th] = min;
        valPtr[th] = ptr;
        vals[th] = v;
    }

    // bit reader over the entropy-coded segment (byte stuffing FF 00, restart markers)
    private long bitBuf;
    private int bitCnt;
    private boolean markerHit;

    private void fill() {
        while (bitCnt <= 56) {
            int b = 0;
            if (!markerHit && pos < end) {
                b = d.get(pos) & 0xFF;
                if (b == 0xFF) {
                    int n = pos + 1 < end ? d.get(pos + 1) & 0xFF : 0;
                    if (n == 0x00) pos += 2;
                    else { markerHit = true; b = 0; }
                } else pos++;
            }
            bitBuf |= ((long) b) << (56 - bitCnt);
            bitCnt += 8;
        }
    }

    private int bits(int n) {
        if (n == 0) return 0;
        if (bitCnt < n) fill();
        int v = (int) (bitBuf >>> (64 - n));
        bitBuf <<= n;
        bitCnt -= n;
        return v;
    }

    private int huff(int t) throws IOException {
        if (bitCnt < 16) fill();
        int[] max = maxCode[t];
        if (max == null) throw new IOException("LJ92: missing Huffman table " + t);
        int code = 0;
        for (int l = 1; l <= 16; ++l) {
            code = (code << 1) | bits(1);
            if (code <= max[l]) return vals[t][valPtr[t][l] + code - minCode[t][l]];
        }
        throw new IOException("LJ92: bad Huffman code");
    }

    private void restart() {
        bitBuf = 0;
        bitCnt = 0;
        markerHit = false;
        // skip to the RSTn marker and past it
        while (pos + 1 < end && !((d.get(pos) & 0xFF) == 0xFF && (d.get(pos + 1) & 0xFF) >= 0xD0 && (d.get(pos + 1) & 0xFF) <= 0xD7)) pos++;
        if (pos + 1 < end) pos += 2;
    }

    /** Receives the decoded rows in order: {@code row} holds X * Nc samples (valid until the next call). */
    public interface RowSink {
        void row(int y, char[] row) throws IOException;
    }

    private RowSink sink;

    private char[] scan() throws IOException {
        final int rowLen = width * comps;
        final boolean keep = sink == null;
        final char[] out = keep ? new char[rowLen * height] : null;
        char[] prev = new char[rowLen], cur = new char[rowLen];
        final int mask = 0xFFFF;
        final int initial = 1 << (precision - pointTransform - 1);
        int mcus = 0;
        boolean resetRow = true;
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                if (restartInterval > 0 && mcus > 0 && mcus % restartInterval == 0) {
                    restart();
                    resetRow = true;
                }
                for (int c = 0; c < comps; ++c) {
                    int s = huff(compTable[c]);
                    int diff;
                    if (s == 0) diff = 0;
                    else if (s == 16) diff = 32768;
                    else {
                        diff = bits(s);
                        if (diff < (1 << (s - 1))) diff -= (1 << s) - 1;
                    }
                    final int i = x * comps + c;
                    int pred;
                    if (resetRow && x == 0) pred = initial;
                    else if (y == 0 || resetRow) pred = cur[i - comps];
                    else if (x == 0) pred = prev[c];
                    else {
                        final int ra = cur[i - comps], rb = prev[i], rc = prev[i - comps];
                        switch (predictor) {
                            case 1: pred = ra; break;
                            case 2: pred = rb; break;
                            case 3: pred = rc; break;
                            case 4: pred = ra + rb - rc; break;
                            case 5: pred = ra + ((rb - rc) >> 1); break;
                            case 6: pred = rb + ((ra - rc) >> 1); break;
                            case 7: pred = (ra + rb) >> 1; break;
                            default: pred = ra; break;
                        }
                    }
                    cur[i] = (char) ((pred + diff) & mask);
                }
                ++mcus;
            }
            resetRow = false; // a restart (or the scan start) predicts its first line from the left only
            char[] emit = cur;
            if (pointTransform > 0) {
                emit = new char[rowLen];
                for (int i = 0; i < rowLen; ++i) emit[i] = (char) ((cur[i] << pointTransform) & mask);
            }
            if (keep) System.arraycopy(emit, 0, out, y * rowLen, rowLen);
            else sink.row(y, emit);
            char[] t = prev; prev = cur; cur = t;
        }
        return out;
    }

}
