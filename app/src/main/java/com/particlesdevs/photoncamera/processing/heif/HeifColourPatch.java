package com.particlesdevs.photoncamera.processing.heif;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adds an ICC profile ('colr' of type 'prof') to a HEIF file written by somebody else - the 8-bit HEIC of androidx
 * HeifWriter / MediaMuxer, which cannot be told a colour (P46, «Цветовое пространство» Display P3). The property is appended
 * to 'ipco' and associated (not essential) with the primary item and the tiles it is derived from ('dimg'); an 'nclx' colr
 * already associated with those items gets the same primaries ({@code nclxPrimaries}), so the two never disagree. The
 * YCbCr matrix stays whatever the encoder wrote. When the 'meta' box sits before the coded data, every 'iloc' file offset
 * behind it moves by the growth of 'meta'. An ICC profile the encoder already put on those items is replaced instead.
 * Files this cannot patch safely (a 'moov' track behind a growing 'meta', 32-bit offset overflow, unknown box versions)
 * throw an IOException and stay untouched.
 */
public final class HeifColourPatch {
    private HeifColourPatch() {}

    /** Largest 'meta' box handled in memory (HeifWriter's is a few KB). */
    static final int MAX_META = 16 << 20;

    /** Patches {@code file} in place (through a temporary file). False when nothing was changed. */
    public static boolean patch(Path file, byte[] icc, int nclxPrimaries) throws IOException {
        final Path tmp = file.resolveSibling(file.getFileName() + ".colr.tmp");
        boolean written = false;
        try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ)) {
            final long size = in.size();
            long metaStart = -1, metaSize = 0;
            boolean moov = false;
            for (long p = 0; p + 8 <= size; ) {
                final ByteBuffer h = read(in, p, (int) Math.min(16, size - p));
                long n = h.getInt(0) & 0xFFFFFFFFL;
                final String type = fourcc(h, 4);
                if (n == 1) n = h.getLong(8);
                else if (n == 0) n = size - p;
                if (n < 8 || p + n > size) throw new IOException("box '" + type + "' at " + p + " overruns the file");
                if (type.equals("meta")) {
                    if (metaStart >= 0) throw new IOException("two meta boxes");
                    metaStart = p;
                    metaSize = n;
                }
                if (type.equals("moov")) moov = true;
                p += n;
            }
            if (metaStart < 0) throw new IOException("no meta box");
            if (metaSize > MAX_META) throw new IOException("meta box of " + metaSize + " bytes");
            final byte[] meta = new byte[(int) metaSize];
            final ByteBuffer mb = ByteBuffer.wrap(meta);
            while (mb.hasRemaining()) if (in.read(mb, metaStart + mb.position()) < 0) throw new IOException("truncated meta");
            final byte[] patched = patchMeta(meta, metaStart, icc, nclxPrimaries, moov && metaStart + metaSize < size);
            if (patched == null) return false;
            try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (long p = 0; p < metaStart; ) p += in.transferTo(p, metaStart - p, out);
                final ByteBuffer m = ByteBuffer.wrap(patched);
                while (m.hasRemaining()) out.write(m);
                for (long p = metaStart + metaSize; p < size; ) p += in.transferTo(p, size - p, out);
                out.force(false);
            }
            written = true;
        } finally {
            if (!written) Files.deleteIfExists(tmp);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /** {@link #patch} on bytes (tests, checks); null when the file is not changed. */
    public static byte[] patch(byte[] heif, byte[] icc, int nclxPrimaries) throws IOException {
        final Path dir = Files.createTempDirectory("heifcolr");
        final Path f = dir.resolve("a.heic");
        try {
            Files.write(f, heif);
            return patch(f, icc, nclxPrimaries) ? Files.readAllBytes(f) : null;
        } finally {
            Files.deleteIfExists(f);
            Files.deleteIfExists(dir);
        }
    }

    // ------------------------------------------------------------------------------------------------ meta rewrite

    /** One child box of a parsed range: type and its absolute byte range (header included) in the meta array. */
    static final class Child {
        final String type;
        final int start, header, end;

        Child(String type, int start, int header, int end) {
            this.type = type;
            this.start = start;
            this.header = header;
            this.end = end;
        }
    }

    static List<Child> children(byte[] d, int start, int end) throws IOException {
        final List<Child> out = new ArrayList<>();
        for (int p = start; p < end; ) {
            if (p + 8 > end) throw new IOException("truncated box at " + p);
            long n = u32(d, p);
            int header = 8;
            if (n == 1) {
                if (p + 16 > end) throw new IOException("truncated large box at " + p);
                n = ByteBuffer.wrap(d, p + 8, 8).getLong();
                header = 16;
            } else if (n == 0) {
                n = end - p;
            }
            if (n < header || p + n > end) throw new IOException("box at " + p + " overruns its parent");
            out.add(new Child(new String(d, p + 4, 4, StandardCharsets.US_ASCII), p, header, (int) (p + n)));
            p += (int) n;
        }
        return out;
    }

    static Child find(List<Child> list, String type) {
        for (Child c : list) if (c.type.equals(type)) return c;
        return null;
    }

    /**
     * The new 'meta' box, or null when there is nothing to do. {@code metaStart}: its file offset; {@code trackBehind}: a
     * 'moov' exists behind the meta box (its chunk offsets could not follow a growing meta).
     */
    static byte[] patchMeta(byte[] meta, long metaStart, byte[] icc, int nclxPrimaries, boolean trackBehind) throws IOException {
        if (icc == null || icc.length == 0) return null;
        if (meta.length < 12 || !"meta".equals(new String(meta, 4, 4, StandardCharsets.US_ASCII)))
            throw new IOException("not a meta box");
        final List<Child> top = children(meta, 12, meta.length);
        final Child pitm = find(top, "pitm"), iprp = find(top, "iprp"), iloc = find(top, "iloc"), iref = find(top, "iref");
        if (pitm == null || iprp == null || iloc == null) throw new IOException("meta without pitm / iprp / iloc");
        final int pitmPayload = pitm.start + pitm.header;
        final long primary = meta[pitmPayload] == 0 ? u16(meta, pitmPayload + 4) : u32(meta, pitmPayload + 4);
        final Set<Long> targets = new HashSet<>();
        targets.add(primary);
        if (iref != null) {
            final int p0 = iref.start + iref.header;
            final int idSize = meta[p0] == 0 ? 2 : 4;
            for (Child r : children(meta, p0 + 4, iref.end)) {
                if (!r.type.equals("dimg")) continue;
                int p = r.start + r.header;
                final long from = idSize == 2 ? u16(meta, p) : u32(meta, p);
                if (from != primary) continue;
                final int count = u16(meta, p + idSize);
                p += idSize + 2;
                for (int i = 0; i < count; i++, p += idSize) targets.add(idSize == 2 ? (long) u16(meta, p) : u32(meta, p));
            }
        }
        final List<Child> props = children(meta, iprp.start + iprp.header, iprp.end);
        final Child ipco = find(props, "ipco");
        Child ipma = null;
        for (Child c : props) if (c.type.equals("ipma")) {
            if (ipma != null) throw new IOException("two ipma boxes");
            ipma = c;
        }
        if (ipco == null || ipma == null) throw new IOException("iprp without ipco / ipma");
        final List<Child> properties = children(meta, ipco.start + ipco.header, ipco.end);
        final int newIndex = properties.size() + 1;

        // ipma: version 0 / 1 (16 / 32-bit item IDs), flags bit 0 = 15-bit property indices.
        final int mp = ipma.start + ipma.header;
        final int version = meta[mp] & 0xFF, flags = (int) (u32(meta, mp) & 0xFFFFFF);
        if (version > 1) throw new IOException("ipma version " + version);
        final boolean wide = (flags & 1) != 0;
        if (!wide && newIndex > 127) throw new IOException("ipma cannot index property " + newIndex);
        final long entries = u32(meta, mp + 4);
        final byte[] patchedMeta = meta.clone(); // nclx primaries are patched in place
        // Pass 1: the colr boxes of the target items - an nclx takes the new primaries, an ICC already there is replaced
        // in place (the encoder's own sRGB profile) instead of adding a second one.
        int iccIndex = -1;
        int p = mp + 8;
        for (long e = 0; e < entries; e++) {
            final long item = version == 0 ? u16(meta, p) : u32(meta, p);
            p += version == 0 ? 2 : 4;
            final int count = meta[p] & 0xFF;
            p += 1;
            for (int a = 0; a < count; a++) {
                final int index = wide ? (u16(meta, p) & 0x7FFF) : (meta[p] & 0x7F);
                p += wide ? 2 : 1;
                if (index < 1 || index > properties.size() || !targets.contains(item)) continue;
                final Child prop = properties.get(index - 1);
                if (!prop.type.equals("colr")) continue;
                final int cp = prop.start + prop.header;
                final String colourType = new String(meta, cp, 4, StandardCharsets.US_ASCII);
                if (colourType.equals("prof") || colourType.equals("rICC")) {
                    if (iccIndex >= 0 && iccIndex != index) throw new IOException("two ICC profiles on the image items");
                    iccIndex = index;
                }
                if (colourType.equals("nclx") && cp + 6 <= prop.end) {
                    patchedMeta[cp + 4] = (byte) (nclxPrimaries >>> 8);
                    patchedMeta[cp + 5] = (byte) nclxPrimaries;
                }
            }
        }
        final boolean replace = iccIndex > 0;
        // Pass 2 (adding): every target item gets the new property; items without an entry get one.
        final ByteArrayOutputStream ipmaBody = new ByteArrayOutputStream();
        final Set<Long> seen = new HashSet<>();
        p = mp + 8;
        for (long e = 0; e < entries; e++) {
            final int entryStart = p;
            final long item = version == 0 ? u16(meta, p) : u32(meta, p);
            p += version == 0 ? 2 : 4;
            final int count = meta[p] & 0xFF;
            p += 1;
            final int assocStart = p;
            p += count * (wide ? 2 : 1);
            final boolean add = !replace && targets.contains(item);
            if (add && count >= 255) throw new IOException("item " + item + " has 255 properties");
            if (!add) {
                ipmaBody.write(meta, entryStart, p - entryStart);
            } else {
                writeId(ipmaBody, item, version);
                ipmaBody.write(count + 1);
                ipmaBody.write(meta, assocStart, p - assocStart);
                writeIndex(ipmaBody, newIndex, wide);
            }
            seen.add(item);
        }
        long newEntries = entries;
        if (!replace) {
            for (Long item : targets) {
                if (seen.contains(item)) continue;
                writeId(ipmaBody, item, version);
                ipmaBody.write(1);
                writeIndex(ipmaBody, newIndex, wide);
                newEntries++;
            }
        }
        final ByteArrayOutputStream newIpma = new ByteArrayOutputStream();
        final byte[] body = ipmaBody.toByteArray();
        writeBoxHeader(newIpma, "ipma", 8 + 4 + 4 + body.length);
        newIpma.write(meta, mp, 4); // version, flags
        write32(newIpma, newEntries);
        newIpma.write(body, 0, body.length);

        final ByteArrayOutputStream colr = new ByteArrayOutputStream();
        writeBoxHeader(colr, "colr", 8 + 4 + icc.length);
        colr.write("prof".getBytes(StandardCharsets.US_ASCII), 0, 4);
        colr.write(icc, 0, icc.length);
        final ByteArrayOutputStream ipcoBody = new ByteArrayOutputStream();
        for (int i = 0; i < properties.size(); i++) {
            final Child c = properties.get(i);
            if (replace && i == iccIndex - 1) ipcoBody.write(colr.toByteArray(), 0, colr.size());
            else ipcoBody.write(patchedMeta, c.start, c.end - c.start);
        }
        if (!replace) ipcoBody.write(colr.toByteArray(), 0, colr.size());
        final ByteArrayOutputStream newIpco = new ByteArrayOutputStream();
        writeBoxHeader(newIpco, "ipco", 8 + ipcoBody.size());
        newIpco.write(ipcoBody.toByteArray(), 0, ipcoBody.size());

        final ByteArrayOutputStream iprpBody = new ByteArrayOutputStream();
        for (Child c : props) {
            if (c == ipco) iprpBody.write(newIpco.toByteArray(), 0, newIpco.size());
            else if (c == ipma) iprpBody.write(newIpma.toByteArray(), 0, newIpma.size());
            else iprpBody.write(patchedMeta, c.start, c.end - c.start);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(meta.length + colr.size() + 64);
        final int newSize = 12 + (meta.length - 12) - (iprp.end - iprp.start) + 8 + iprpBody.size();
        writeBoxHeader(out, "meta", newSize);
        out.write(meta, 8, 4); // version, flags
        int ilocAt = -1;
        for (Child c : top) {
            if (c == iprp) {
                writeBoxHeader(out, "iprp", 8 + iprpBody.size());
                out.write(iprpBody.toByteArray(), 0, iprpBody.size());
            } else {
                if (c == iloc) ilocAt = out.size();
                out.write(patchedMeta, c.start, c.end - c.start);
            }
        }
        final byte[] result = out.toByteArray();
        if (result.length != newSize) throw new IllegalStateException("meta size " + result.length + " != " + newSize);
        final long delta = result.length - meta.length;
        final long oldEnd = metaStart + meta.length;
        if (shiftIloc(result, ilocAt, oldEnd, delta) && trackBehind)
            throw new IOException("a moov track follows the growing meta box");
        return result;
    }

    /**
     * Moves the file offsets (construction method 0, this file) at or behind {@code oldEnd} by {@code delta}, in place.
     * Returns whether any offset moved.
     */
    static boolean shiftIloc(byte[] d, int at, long oldEnd, long delta) throws IOException {
        final int header = u32(d, at) == 1 ? 16 : 8;
        int p = at + header;
        final int version = d[p] & 0xFF;
        if (version > 2) throw new IOException("iloc version " + version);
        p += 4;
        final int offSize = (d[p] >> 4) & 15, lenSize = d[p] & 15, baseSize = (d[p + 1] >> 4) & 15;
        final int indexSize = version >= 1 ? d[p + 1] & 15 : 0;
        p += 2;
        final long items = version < 2 ? u16(d, p) : u32(d, p);
        p += version < 2 ? 2 : 4;
        boolean moved = false;
        for (long i = 0; i < items; i++) {
            p += version < 2 ? 2 : 4; // item_ID
            int method = 0;
            if (version >= 1) {
                method = u16(d, p) & 15;
                p += 2;
            }
            final int dataRef = u16(d, p);
            p += 2;
            final int baseAt = p;
            final long base = read(d, p, baseSize);
            p += baseSize;
            final int extents = u16(d, p);
            p += 2;
            final boolean local = method == 0 && dataRef == 0;
            boolean baseMoved = false;
            if (local && baseSize > 0 && base >= oldEnd) {
                write(d, baseAt, baseSize, base + delta);
                baseMoved = moved = true;
            }
            for (int e = 0; e < extents; e++) {
                p += indexSize;
                final long off = read(d, p, offSize);
                if (local && !baseMoved && base + off >= oldEnd && (offSize > 0 || base + off > 0)) {
                    if (offSize == 0) throw new IOException("iloc extent without an offset field behind meta");
                    write(d, p, offSize, off + delta);
                    moved = true;
                }
                p += offSize + lenSize;
            }
        }
        return moved;
    }

    // ------------------------------------------------------------------------------------------------ bytes

    private static ByteBuffer read(FileChannel in, long at, int n) throws IOException {
        final ByteBuffer b = ByteBuffer.allocate(n);
        while (b.hasRemaining()) if (in.read(b, at + b.position()) < 0) throw new IOException("truncated at " + at);
        b.flip();
        return b;
    }

    private static String fourcc(ByteBuffer b, int at) {
        return new String(new byte[]{b.get(at), b.get(at + 1), b.get(at + 2), b.get(at + 3)}, StandardCharsets.US_ASCII);
    }

    static int u16(byte[] d, int p) {
        return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
    }

    static long u32(byte[] d, int p) {
        return ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    private static long read(byte[] d, int p, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (d[p + i] & 0xFF);
        return v;
    }

    private static void write(byte[] d, int p, int n, long v) throws IOException {
        if (n < 8 && (v < 0 || v >= (1L << (8 * n)))) throw new IOException("offset " + v + " does not fit " + n + " bytes");
        for (int i = n - 1; i >= 0; i--, v >>>= 8) d[p + i] = (byte) v;
    }

    private static void writeBoxHeader(ByteArrayOutputStream out, String type, long size) throws IOException {
        if (size > 0xFFFFFFFFL) throw new IOException("box above 4 GB");
        write32(out, size);
        out.write(type.getBytes(StandardCharsets.US_ASCII), 0, 4);
    }

    private static void write32(ByteArrayOutputStream out, long v) {
        out.write((int) (v >>> 24));
        out.write((int) (v >>> 16));
        out.write((int) (v >>> 8));
        out.write((int) v);
    }

    private static void writeId(ByteArrayOutputStream out, long item, int version) {
        if (version == 0) {
            out.write((int) (item >>> 8));
            out.write((int) item);
        } else {
            write32(out, item);
        }
    }

    private static void writeIndex(ByteArrayOutputStream out, int index, boolean wide) {
        if (wide) {
            out.write(index >>> 8);
            out.write(index);
        } else {
            out.write(index);
        }
    }
}
