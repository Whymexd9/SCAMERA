package com.particlesdevs.photoncamera.processing.color;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Puts an ICC profile into a JPEG or a WebP file (P46, «Цветовое пространство» Display P3). The encoders (jpegli, Android's
 * JPEG / WebP encoders through Bitmap.compress) see an sRGB-tagged bitmap and write no profile, so the profile is added
 * here: JPEG as APP2 "ICC_PROFILE" segment(s) (ICC.1 Annex B.4) right after SOI (after a JFIF APP0 when there is one),
 * WebP as an 'ICCP' chunk right after 'VP8X' with the ICC flag set (a simple VP8 / VP8L file gets a VP8X first). Nothing
 * here runs for an sRGB photo. Pure Java.
 */
public final class IccEmbed {
    private IccEmbed() {}

    static final byte[] ICC_ID = "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII);
    /** Largest profile piece of one APP2 segment: 65535 - 2 (length) - 12 (id) - 2 (sequence, count). */
    static final int MAX_CHUNK = 65519;

    /** The APP2 segments (FF E2 ...) that carry {@code icc}, in order. */
    public static byte[] jpegSegments(byte[] icc) {
        if (icc == null || icc.length == 0) throw new IllegalArgumentException("empty ICC profile");
        final int count = (icc.length + MAX_CHUNK - 1) / MAX_CHUNK;
        if (count > 255) throw new IllegalArgumentException("ICC profile too large for JPEG: " + icc.length);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(icc.length + count * 18);
        for (int i = 0; i < count; i++) {
            final int from = i * MAX_CHUNK, n = Math.min(MAX_CHUNK, icc.length - from);
            final int length = 2 + ICC_ID.length + 2 + n;
            out.write(0xFF);
            out.write(0xE2);
            out.write(length >>> 8);
            out.write(length & 0xFF);
            out.write(ICC_ID, 0, ICC_ID.length);
            out.write(i + 1);
            out.write(count);
            out.write(icc, from, n);
        }
        return out.toByteArray();
    }

    /**
     * A stream that writes a JPEG into {@code out} with the ICC segments inserted after SOI (and after a JFIF APP0 that
     * directly follows SOI); ICC_PROFILE segments the encoder wrote itself (Android's encoder tags its output sRGB) are
     * dropped, so the file has exactly one profile. The leading metadata segments (APPn / COM, at most 1 MB) are held back
     * until the first other marker; the rest passes straight through. {@link #close()} completes a header that ends early
     * and does not close {@code out}.
     */
    public static OutputStream jpegInserting(OutputStream out, byte[] icc) {
        return new JpegIccStream(out, jpegSegments(icc));
    }

    private static final class JpegIccStream extends OutputStream {
        private static final int MAX_HEAD = 1 << 20;
        private final OutputStream out;
        private final byte[] segments;
        private byte[] head = new byte[1024];
        private int headLength;
        private boolean streaming;

        JpegIccStream(OutputStream out, byte[] segments) {
            this.out = out;
            this.segments = segments;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0 && !streaming) {
                if (headLength == head.length) {
                    if (head.length >= MAX_HEAD) throw new IOException("JPEG metadata header larger than 1 MB");
                    head = Arrays.copyOf(head, head.length * 2);
                }
                final int n = Math.min(len, head.length - headLength);
                System.arraycopy(b, off, head, headLength, n);
                headLength += n;
                off += n;
                len -= n;
                final int end = headerEnd(false);
                if (end >= 0) emit(end);
            }
            if (len > 0) out.write(b, off, len);
        }

        /** Position of the first marker after SOI that is not APPn / COM; -1 while more bytes are needed. */
        private int headerEnd(boolean complete) throws IOException {
            if (headLength >= 2 && ((head[0] & 0xFF) != 0xFF || (head[1] & 0xFF) != 0xD8)) throw new IOException("not a JPEG (no SOI)");
            int pos = 2;
            while (pos + 4 <= headLength) {
                if ((head[pos] & 0xFF) != 0xFF) return pos;
                final int marker = head[pos + 1] & 0xFF;
                if ((marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE) {
                    pos += 2 + (((head[pos + 2] & 0xFF) << 8) | (head[pos + 3] & 0xFF));
                } else {
                    return pos;
                }
            }
            return complete ? Math.min(Math.max(pos, 2), headLength) : -1;
        }

        /** SOI, [JFIF APP0], the ICC segments, the other leading segments without ICC_PROFILE, then the rest. */
        private void emit(int end) throws IOException {
            if (headLength < 2) {
                out.write(head, 0, headLength); // not even a SOI: passed on as it is
            } else {
                out.write(head, 0, 2);
                int pos = 2;
                boolean first = true;
                while (pos + 4 <= end) {
                    final int marker = head[pos + 1] & 0xFF;
                    final int next = Math.min(end, pos + 2 + (((head[pos + 2] & 0xFF) << 8) | (head[pos + 3] & 0xFF)));
                    if (first && marker != 0xE0) out.write(segments);
                    if (!(marker == 0xE2 && startsWith(head, pos + 4, ICC_ID))) out.write(head, pos, next - pos);
                    if (first && marker == 0xE0) out.write(segments);
                    first = false;
                    pos = next;
                }
                if (first) out.write(segments);
                out.write(head, pos, headLength - pos);
            }
            head = null;
            headLength = 0;
            streaming = true;
        }

        @Override
        public void flush() throws IOException {
            if (streaming) out.flush();
        }

        @Override
        public void close() throws IOException {
            if (!streaming && head != null) emit(headerEnd(true));
            out.flush();
        }
    }

    /** The ICC profile of a JPEG (APP2 ICC_PROFILE segments joined in sequence order), or null. */
    public static byte[] jpegProfile(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return null;
        final byte[][] pieces = new byte[256][];
        int count = 0, pos = 2;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
            final int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) break;
            final int len = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (len < 2 || pos + 2 + len > jpeg.length) break;
            if (marker == 0xE2 && len >= 2 + ICC_ID.length + 2 && startsWith(jpeg, pos + 4, ICC_ID)) {
                final int seq = jpeg[pos + 4 + ICC_ID.length] & 0xFF;
                count = jpeg[pos + 5 + ICC_ID.length] & 0xFF;
                pieces[seq] = Arrays.copyOfRange(jpeg, pos + 6 + ICC_ID.length, pos + 2 + len);
            }
            pos += 2 + len;
        }
        if (count == 0) return null;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 1; i <= count; i++) {
            if (pieces[i] == null) return null;
            out.write(pieces[i], 0, pieces[i].length);
        }
        return out.toByteArray();
    }

    private static boolean startsWith(byte[] data, int at, byte[] prefix) {
        if (at + prefix.length > data.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[at + i] != prefix[i]) return false;
        return true;
    }

    // ------------------------------------------------------------------------------------------------ WebP

    /** VP8X flags (WebP container specification). */
    static final int VP8X_ICC = 0x20, VP8X_ALPHA = 0x10;

    /**
     * Adds (or replaces) the ICC profile of the WebP {@code file} in place: through a temporary file next to it, the image
     * chunks copied without loading them. {@code width} / {@code height}: the canvas, for a simple file that needs a VP8X.
     */
    public static void addToWebp(Path file, byte[] icc, int width, int height) throws IOException {
        final Path tmp = file.resolveSibling(file.getFileName() + ".icc.tmp");
        boolean done = false;
        try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            webp(in, out, icc, width, height);
            out.force(false);
            done = true;
        } finally {
            if (!done) Files.deleteIfExists(tmp);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** {@link #addToWebp} on bytes (tests, checks). */
    public static byte[] addToWebp(byte[] webp, byte[] icc, int width, int height) throws IOException {
        final Path dir = Files.createTempDirectory("webpicc");
        final Path f = dir.resolve("a.webp");
        try {
            Files.write(f, webp);
            addToWebp(f, icc, width, height);
            return Files.readAllBytes(f);
        } finally {
            Files.deleteIfExists(f);
            Files.deleteIfExists(dir);
        }
    }

    private static ByteBuffer read(FileChannel in, long at, int n) throws IOException {
        final ByteBuffer b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            if (in.read(b, at + b.position()) < 0) throw new IOException("WebP truncated at " + (at + b.position()));
        }
        b.flip();
        return b;
    }

    private static String fourcc(ByteBuffer b, int at) {
        return new String(new byte[]{b.get(at), b.get(at + 1), b.get(at + 2), b.get(at + 3)}, StandardCharsets.US_ASCII);
    }

    private static void webp(FileChannel in, FileChannel out, byte[] icc, int width, int height) throws IOException {
        final long size = in.size();
        final ByteBuffer riff = read(in, 0, 12);
        if (!fourcc(riff, 0).equals("RIFF") || !fourcc(riff, 8).equals("WEBP")) throw new IOException("not a WebP file");
        final long riffEnd = Math.min(size, 8L + (riff.getInt(4) & 0xFFFFFFFFL));
        // Chunks: type, payload offset, payload size.
        final java.util.List<long[]> chunks = new java.util.ArrayList<>();
        final java.util.List<String> types = new java.util.ArrayList<>();
        for (long p = 12; p + 8 <= riffEnd; ) {
            final ByteBuffer h = read(in, p, 8);
            final long n = h.getInt(4) & 0xFFFFFFFFL;
            if (p + 8 + n > riffEnd) throw new IOException("WebP chunk " + fourcc(h, 0) + " overruns the file");
            types.add(fourcc(h, 0));
            chunks.add(new long[]{p + 8, n});
            p += 8 + n + (n & 1);
        }
        if (chunks.isEmpty()) throw new IOException("WebP without chunks");
        final ByteArrayOutputStream head = new ByteArrayOutputStream();
        long copyFrom;
        if (types.get(0).equals("VP8X")) {
            final long[] vp8x = chunks.get(0);
            if (vp8x[1] < 10) throw new IOException("short VP8X");
            final ByteBuffer v = read(in, vp8x[0], (int) vp8x[1]);
            final byte[] payload = new byte[(int) vp8x[1]];
            v.get(payload);
            payload[0] |= VP8X_ICC;
            chunk(head, "VP8X", payload);
            chunk(head, "ICCP", icc);
            copyFrom = vp8x[0] + vp8x[1] + (vp8x[1] & 1);
            // An ICCP already there is replaced: the chunks after VP8X are copied without it.
            if (types.size() > 1 && types.get(1).equals("ICCP")) {
                final long[] old = chunks.get(1);
                copyFrom = old[0] + old[1] + (old[1] & 1);
            }
        } else if (types.get(0).equals("VP8 ") || types.get(0).equals("VP8L")) {
            boolean alpha = false;
            if (types.get(0).equals("VP8L") && chunks.get(0)[1] >= 5) {
                final ByteBuffer h = read(in, chunks.get(0)[0], 5);
                final long bits = (h.get(1) & 0xFFL) | (h.get(2) & 0xFFL) << 8 | (h.get(3) & 0xFFL) << 16 | (h.get(4) & 0xFFL) << 24;
                alpha = ((bits >>> 28) & 1) != 0; // alpha_is_used after two 14-bit sizes
            }
            if (width < 1 || height < 1 || width > (1 << 24) || height > (1 << 24)) throw new IOException("bad canvas " + width + "x" + height);
            final byte[] payload = new byte[10];
            payload[0] = (byte) (VP8X_ICC | (alpha ? VP8X_ALPHA : 0));
            put24(payload, 4, width - 1);
            put24(payload, 7, height - 1);
            chunk(head, "VP8X", payload);
            chunk(head, "ICCP", icc);
            copyFrom = 12;
        } else {
            throw new IOException("WebP starts with a '" + types.get(0) + "' chunk");
        }
        final long tail = riffEnd - copyFrom;
        final long riffSize = 4 + head.size() + tail;
        if (riffSize > 0xFFFFFFFEL) throw new IOException("WebP above 4 GB");
        final ByteBuffer top = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        top.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) riffSize).put("WEBP".getBytes(StandardCharsets.US_ASCII));
        top.flip();
        writeAll(out, top);
        writeAll(out, ByteBuffer.wrap(head.toByteArray()));
        for (long p = copyFrom; p < riffEnd; ) p += in.transferTo(p, riffEnd - p, out);
    }

    private static void writeAll(FileChannel out, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) out.write(b);
    }

    private static void put24(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >>> 8);
        b[at + 2] = (byte) (v >>> 16);
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] payload) {
        final byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t, 0, 4);
        final int n = payload.length;
        out.write(n);
        out.write(n >>> 8);
        out.write(n >>> 16);
        out.write(n >>> 24);
        out.write(payload, 0, n);
        if ((n & 1) != 0) out.write(0);
    }

    /** The ICCP payload of a WebP, or null. */
    public static byte[] webpProfile(byte[] webp) {
        if (webp == null || webp.length < 12) return null;
        final ByteBuffer b = ByteBuffer.wrap(webp).order(ByteOrder.LITTLE_ENDIAN);
        for (int p = 12; p + 8 <= webp.length; ) {
            final String type = new String(webp, p, 4, StandardCharsets.US_ASCII);
            final int n = b.getInt(p + 4);
            if (n < 0 || p + 8 + n > webp.length) return null;
            if (type.equals("ICCP")) return Arrays.copyOfRange(webp, p + 8, p + 8 + n);
            p += 8 + n + (n & 1);
        }
        return null;
    }
}
