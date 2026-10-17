package com.particlesdevs.photoncamera.gallery.helper;

import androidx.annotation.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * P196: the pixel size a photo file really stores, from its own headers (owner: «нам нужно чтобы в информации о фото писало
 * фактическое разрешение»): the JPEG frame header (SOFn; an Ultra HDR JPEG's primary image comes first), the WebP bitstream
 * header (VP8 / VP8L / the VP8X canvas), and for HEIC / AVIF the 'ispe' property of the primary item (pitm, through
 * iprp / ipma; the grid of a tiled HEIC carries the full size). Not the EXIF ImageWidth / ImageLength tags, which only say
 * what the writer claimed. Reads the stream forward only, skipping what it does not need (a HEIF 'mdat' in front of its
 * 'meta' too). The size is as stored, before any EXIF / irot rotation. Plain Java (JVM tests).
 */
public final class ImageHeaderSize {
    /** A HEIF 'meta' box above this is not read (a 200 MP grid HEIC's meta is ~100 KB). */
    static final int MAX_META_BYTES = 8 << 20;

    private ImageHeaderSize() {}

    /**
     * {width, height} from the headers of the image in {@code in}, or null when it is not a JPEG / WebP / HEIF / AVIF or
     * the file ends before the size.
     */
    @Nullable
    public static int[] read(InputStream in) throws IOException {
        if (in == null) return null;
        try {
            return parse(in);
        } catch (EOFException e) {
            return null;
        }
    }

    private static int[] parse(InputStream in) throws IOException {
        Reader r = new Reader(in);
        byte[] head = new byte[12];
        int n = r.readUpTo(head);
        if (n >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF)
            return jpeg(r, head, n);
        if (n == 12 && ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP")) return webp(r);
        if (n >= 8 && ascii(head, 4, "ftyp")) return isobmff(r, head, n);
        return null;
    }

    // ------------------------------------------------------------------ JPEG

    private static int[] jpeg(Reader r, byte[] head, int n) throws IOException {
        // head[0..1] = SOI; the rest of the 12 bytes read are pushed back by parsing them first
        Reader s = r.prepend(head, 2, n - 2);
        while (true) {
            int b = s.u8();
            if (b != 0xFF) return null; // not at a marker: damaged
            int marker;
            do marker = s.u8(); while (marker == 0xFF); // fill bytes
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue; // no length
            if (marker == 0xD9 || marker == 0xDA) return null; // EOI / SOS before any frame header
            int len = s.u16();
            if (len < 2) return null;
            if (isSof(marker)) {
                s.u8(); // precision
                int h = s.u16(), w = s.u16();
                return w > 0 && h > 0 ? new int[]{w, h} : null;
            }
            s.skip(len - 2);
        }
    }

    /** SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC). */
    static boolean isSof(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    // ------------------------------------------------------------------ WebP

    private static int[] webp(Reader r) throws IOException {
        // RIFF header read; chunks follow: the first one tells the size (VP8X: canvas; VP8 / VP8L: the only image)
        byte[] fourcc = new byte[4];
        r.fully(fourcc);
        r.u32le(); // chunk size
        if (ascii(fourcc, 0, "VP8X")) {
            r.skip(4); // flags + reserved
            int w = r.u24le() + 1, h = r.u24le() + 1;
            return new int[]{w, h};
        }
        if (ascii(fourcc, 0, "VP8 ")) {
            r.skip(3); // frame tag
            if (r.u8() != 0x9D || r.u8() != 0x01 || r.u8() != 0x2A) return null;
            int w = r.u16le() & 0x3FFF, h = r.u16le() & 0x3FFF;
            return w > 0 && h > 0 ? new int[]{w, h} : null;
        }
        if (ascii(fourcc, 0, "VP8L")) {
            if (r.u8() != 0x2F) return null;
            long bits = r.u32le();
            int w = (int) (bits & 0x3FFF) + 1, h = (int) ((bits >> 14) & 0x3FFF) + 1;
            return new int[]{w, h};
        }
        return null;
    }

    // ------------------------------------------------------------------ HEIF / AVIF

    private static int[] isobmff(Reader r, byte[] head, int n) throws IOException {
        Reader s = r.prepend(head, 0, n);
        while (true) {
            long size;
            try {
                size = s.u32();
            } catch (EOFException e) {
                return null;
            }
            String type = s.fourcc();
            long header = 8;
            if (size == 1) {
                size = s.u64();
                header = 16;
            } else if (size == 0) {
                if (!"meta".equals(type)) return null; // the rest of the file, and no meta seen
                size = Long.MAX_VALUE;
            }
            if (size < header) return null;
            long payload = size - header;
            if ("meta".equals(type)) {
                if (payload > MAX_META_BYTES && size != Long.MAX_VALUE) return null;
                byte[] meta = size == Long.MAX_VALUE ? s.rest(MAX_META_BYTES) : new byte[(int) payload];
                if (size != Long.MAX_VALUE) s.fully(meta);
                return primarySize(meta);
            }
            s.skip(payload);
        }
    }

    /** The 'ispe' of the primary item in the payload of a 'meta' box (FullBox), or null. */
    @Nullable
    static int[] primarySize(byte[] meta) {
        if (meta.length < 4) return null;
        int end = meta.length;
        long primary = -1;
        int iprp = -1, iprpEnd = -1;
        for (int p = 4; p + 8 <= end; ) { // after version / flags
            long size = u32(meta, p);
            String type = fourcc(meta, p + 4);
            if (size < 8 || p + size > end) return null;
            if ("pitm".equals(type) && size >= 14) {
                int version = meta[p + 8] & 0xFF;
                primary = version == 0 ? u16(meta, p + 12) : u32(meta, p + 12);
            } else if ("iprp".equals(type)) {
                iprp = p + 8;
                iprpEnd = (int) (p + size);
            }
            p += (int) size;
        }
        if (primary < 0 || iprp < 0) return null;
        int ipco = -1, ipcoEnd = -1;
        java.util.List<int[]> ipmas = new java.util.ArrayList<>();
        for (int p = iprp; p + 8 <= iprpEnd; ) {
            long size = u32(meta, p);
            String type = fourcc(meta, p + 4);
            if (size < 8 || p + size > iprpEnd) return null;
            if ("ipco".equals(type)) {
                ipco = p + 8;
                ipcoEnd = (int) (p + size);
            } else if ("ipma".equals(type)) {
                ipmas.add(new int[]{p + 8, (int) (p + size)});
            }
            p += (int) size;
        }
        if (ipco < 0) return null;
        for (int[] ipma : ipmas) {
            int[] props = associations(meta, ipma[0], ipma[1], primary);
            if (props == null) continue;
            for (int index : props) {
                int[] box = property(meta, ipco, ipcoEnd, index);
                if (box != null && "ispe".equals(fourcc(meta, box[0] + 4)) && box[1] - box[0] >= 20) {
                    long w = u32(meta, box[0] + 12), h = u32(meta, box[0] + 16);
                    if (w > 0 && h > 0 && w <= Integer.MAX_VALUE && h <= Integer.MAX_VALUE) return new int[]{(int) w, (int) h};
                }
            }
        }
        return null;
    }

    /** The 1-based property indices of {@code item} in an 'ipma' payload, or null. */
    private static int[] associations(byte[] d, int p, int end, long item) {
        if (p + 8 > end) return null;
        int version = d[p] & 0xFF, flags = (int) (u32(d, p) & 0xFFFFFF);
        p += 4;
        long count = u32(d, p);
        p += 4;
        for (long i = 0; i < count; i++) {
            long id;
            if (version < 1) {
                if (p + 2 > end) return null;
                id = u16(d, p);
                p += 2;
            } else {
                if (p + 4 > end) return null;
                id = u32(d, p);
                p += 4;
            }
            if (p + 1 > end) return null;
            int n = d[p++] & 0xFF;
            int[] out = new int[n];
            for (int k = 0; k < n; k++) {
                if ((flags & 1) != 0) {
                    if (p + 2 > end) return null;
                    out[k] = u16(d, p) & 0x7FFF;
                    p += 2;
                } else {
                    if (p + 1 > end) return null;
                    out[k] = d[p++] & 0x7F;
                }
            }
            if (id == item) return out;
        }
        return null;
    }

    /** {start, end} of the {@code index}-th (1-based) box in an 'ipco' payload, or null. */
    private static int[] property(byte[] d, int p, int end, int index) {
        if (index <= 0) return null;
        for (int i = 1; p + 8 <= end; i++) {
            long size = u32(d, p);
            if (size < 8 || p + size > end) return null;
            if (i == index) return new int[]{p, (int) (p + size)};
            p += (int) size;
        }
        return null;
    }

    // ------------------------------------------------------------------ bytes

    private static boolean ascii(byte[] b, int at, String s) {
        if (at + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) if (b[at + i] != (byte) s.charAt(i)) return false;
        return true;
    }

    private static String fourcc(byte[] b, int at) {
        return new String(b, at, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int u16(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    private static long u32(byte[] b, int at) {
        return ((long) (b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    /** A forward reader over a stream, with bytes already read put in front. */
    private static final class Reader {
        private final InputStream in;
        private byte[] front = new byte[0];
        private int frontAt;

        Reader(InputStream in) {
            this.in = in;
        }

        Reader prepend(byte[] b, int off, int len) {
            byte[] f = new byte[len + (front.length - frontAt)];
            System.arraycopy(b, off, f, 0, len);
            System.arraycopy(front, frontAt, f, len, front.length - frontAt);
            front = f;
            frontAt = 0;
            return this;
        }

        int readUpTo(byte[] b) throws IOException {
            int n = 0;
            while (n < b.length) {
                int k = in.read(b, n, b.length - n);
                if (k < 0) break;
                n += k;
            }
            return n;
        }

        int u8() throws IOException {
            if (frontAt < front.length) return front[frontAt++] & 0xFF;
            int b = in.read();
            if (b < 0) throw new EOFException();
            return b;
        }

        int u16() throws IOException {
            return (u8() << 8) | u8();
        }

        int u16le() throws IOException {
            return u8() | (u8() << 8);
        }

        int u24le() throws IOException {
            return u8() | (u8() << 8) | (u8() << 16);
        }

        long u32() throws IOException {
            return ((long) u16() << 16) | u16();
        }

        long u32le() throws IOException {
            return (long) u16le() | ((long) u16le() << 16);
        }

        long u64() throws IOException {
            return (u32() << 32) | u32();
        }

        String fourcc() throws IOException {
            byte[] b = new byte[4];
            fully(b);
            return new String(b, java.nio.charset.StandardCharsets.US_ASCII);
        }

        void fully(byte[] b) throws IOException {
            for (int i = 0; i < b.length; i++) b[i] = (byte) u8();
        }

        /** Up to {@code max} bytes to the end of the stream. */
        byte[] rest(int max) throws IOException {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            while (frontAt < front.length && out.size() < max) out.write(front[frontAt++]);
            byte[] buf = new byte[8192];
            int k;
            while (out.size() < max && (k = in.read(buf, 0, Math.min(buf.length, max - out.size()))) > 0) out.write(buf, 0, k);
            return out.toByteArray();
        }

        void skip(long n) throws IOException {
            while (n > 0 && frontAt < front.length) {
                frontAt++;
                n--;
            }
            while (n > 0) {
                long k = in.skip(n);
                if (k <= 0) {
                    if (in.read() < 0) throw new EOFException();
                    k = 1;
                }
                n -= k;
            }
        }
    }
}
