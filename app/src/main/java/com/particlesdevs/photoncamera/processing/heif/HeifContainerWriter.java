package com.particlesdevs.photoncamera.processing.heif;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a HEIF still image (ISO/IEC 23008-12) whose primary item is a 'grid' of hidden 'hvc1' tiles, with an 'Exif' item:
 * <pre>
 * ftyp  major 'heic', compatible 'mif1' 'heic' 'heix' 'miaf'
 * meta  hdlr 'pict' | pitm = grid | iinf: tiles 1..N ('hvc1', hidden), grid N+1, Exif N+2
 *       iref: 'dimg' grid -> tiles (raster order), 'cdsc' Exif -> grid
 *       iprp: ipco [1 hvcC, 2 ispe tile, 3 ispe image, 4 colr nclx, 5 pixi]; ipma: tile -> 1 (essential), 2, 4, 5;
 *             grid -> 3, 4, 5
 *       iloc (version 1, construction_method 0): every item's data in mdat (absolute file offsets)
 * mdat  ImageGrid | Exif (4-byte tiff header offset + "Exif\0\0" + TIFF) | tile samples (4-byte length-prefixed NAL units)
 * </pre>
 * 'heic' and 'mif1' are both listed because androidx ExifInterface takes a file as HEIF only with both brands; 'heix'
 * names the Main10 profile. colr: BT.709 primaries, sRGB transfer (13), BT.709 matrix, full range - the encoder's VUI says
 * BT.709 SDR video, the container carries the real sRGB transfer. Pure Java (no Android types).
 */
public final class HeifContainerWriter {
    /** nclx colour of the image: BT.709 primaries, sRGB transfer, BT.709 matrix, full range. */
    public static final int PRIMARIES = 1, TRANSFER_SRGB = 13, MATRIX = 1;
    public static final boolean FULL_RANGE = true;
    static final String[] BRANDS = {"mif1", "heic", "heix", "miaf"};
    static final String MAJOR_BRAND = "heic";

    private final TileGrid grid;
    private final byte[] hvcC;
    private final int bitDepth;
    private final List<byte[]> tiles = new ArrayList<>();
    private byte[] exif;

    /**
     * @param grid     the tile layout of the image
     * @param hvcC     the HEVCDecoderConfigurationRecord of every tile ({@link HevcNal#hvcC})
     * @param bitDepth bits per channel of the coded image (pixi)
     */
    public HeifContainerWriter(TileGrid grid, byte[] hvcC, int bitDepth) {
        if (hvcC == null || hvcC.length < 23) throw new IllegalArgumentException("hvcC missing");
        this.grid = grid;
        this.hvcC = hvcC.clone();
        this.bitDepth = bitDepth;
    }

    /** Sample of the next tile in raster order: 4-byte length-prefixed NAL units ({@link HevcNal#toLengthPrefixed}). */
    public void addTile(byte[] sample) {
        if (sample == null || sample.length < 5) throw new IllegalArgumentException("empty tile sample");
        if (tiles.size() >= grid.count()) throw new IllegalStateException("more tiles than the grid holds");
        tiles.add(sample);
    }

    public int tileCount() {
        return tiles.size();
    }

    /**
     * EXIF of the image: the data block of a JPEG APP1 segment ("Exif\0\0" + TIFF header, as ExifBlock.exifDataBlock gives
     * it and HeifWriter.addExifData takes it) or a bare TIFF block. Null: no Exif item.
     */
    public void setExif(byte[] exifDataBlock) {
        exif = exifDataBlock == null || exifDataBlock.length == 0 ? null : exifDataBlock.clone();
    }

    /** Exif item data: exif_tiff_header_offset (bytes from the start of the payload to the TIFF header) + the payload. */
    static byte[] exifItem(byte[] block) {
        final int offset = startsWithExifId(block) ? 6 : 0;
        final byte[] out = new byte[4 + block.length];
        out[0] = (byte) (offset >>> 24);
        out[1] = (byte) (offset >>> 16);
        out[2] = (byte) (offset >>> 8);
        out[3] = (byte) offset;
        System.arraycopy(block, 0, out, 4, block.length);
        return out;
    }

    private static boolean startsWithExifId(byte[] b) {
        return b.length >= 6 && b[0] == 'E' && b[1] == 'x' && b[2] == 'i' && b[3] == 'f' && b[4] == 0 && b[5] == 0;
    }

    /** ImageGrid (ISO/IEC 23008-12 6.6.2.3.2): 16-bit output size unless a side needs 32 bits. */
    byte[] imageGrid() {
        final boolean large = grid.width > 0xFFFF || grid.height > 0xFFFF;
        final Box b = new Box();
        b.u8(0); // version
        b.u8(large ? 1 : 0); // flags
        b.u8(grid.rows - 1);
        b.u8(grid.columns - 1);
        if (large) {
            b.u32(grid.width);
            b.u32(grid.height);
        } else {
            b.u16(grid.width);
            b.u16(grid.height);
        }
        return b.bytes();
    }

    public int gridItemId() {
        return grid.count() + 1;
    }

    public int exifItemId() {
        return grid.count() + 2;
    }

    /** The whole file. */
    public byte[] toByteArray() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTo(out);
        return out.toByteArray();
    }

    /** Writes the file: ftyp, meta, mdat. Every tile of the grid must have been added. */
    public void writeTo(OutputStream out) throws IOException {
        if (tiles.size() != grid.count())
            throw new IllegalStateException(tiles.size() + " tiles for a grid of " + grid.count());
        final int n = grid.count();
        if (n + 2 > 0xFFFF) throw new IllegalStateException("too many items: " + (n + 2));
        final byte[] gridData = imageGrid();
        final byte[] exifData = exif == null ? null : exifItem(exif);
        long mdatPayload = gridData.length + (exifData == null ? 0 : exifData.length);
        for (byte[] t : tiles) mdatPayload += t.length;
        final boolean largeMdat = mdatPayload + 8 > 0xFFFFFFFFL;
        final byte[] ftyp = ftyp();
        // The meta box has the same size whatever the offsets are (4-byte offset fields): size it, then fill the offsets.
        final int metaSize = meta(0, gridData.length, exifData).length;
        final long dataStart = ftyp.length + metaSize + (largeMdat ? 16 : 8);
        if (dataStart + mdatPayload > 0xFFFFFFFFL) throw new IOException("HEIC above 4 GB");
        final byte[] meta = meta(dataStart, gridData.length, exifData);
        out.write(ftyp);
        out.write(meta);
        final Box header = new Box();
        if (largeMdat) {
            header.u32(1);
            header.fourcc("mdat");
            header.u64(mdatPayload + 16);
        } else {
            header.u32(mdatPayload + 8);
            header.fourcc("mdat");
        }
        out.write(header.bytes());
        out.write(gridData);
        if (exifData != null) out.write(exifData);
        for (byte[] t : tiles) out.write(t);
    }

    static byte[] ftyp() {
        final Box b = new Box();
        b.start("ftyp");
        b.fourcc(MAJOR_BRAND);
        b.u32(0); // minor_version
        for (String brand : BRANDS) b.fourcc(brand);
        b.end();
        return b.bytes();
    }

    private byte[] meta(long dataStart, int gridLength, byte[] exifData) {
        final int n = grid.count(), gridId = gridItemId(), exifId = exifItemId();
        final Box b = new Box();
        b.startFull("meta", 0, 0);

        b.startFull("hdlr", 0, 0);
        b.u32(0); // pre_defined
        b.fourcc("pict");
        b.u32(0);
        b.u32(0);
        b.u32(0);
        b.u8(0); // name: empty string
        b.end();

        b.startFull("pitm", 0, 0);
        b.u16(gridId);
        b.end();

        b.startFull("iinf", 0, 0);
        b.u16(n + (exifData != null ? 2 : 1));
        for (int id = 1; id <= n; id++) infe(b, id, "hvc1", true);
        infe(b, gridId, "grid", false);
        if (exifData != null) infe(b, exifId, "Exif", false);
        b.end();

        b.startFull("iref", 0, 0);
        b.start("dimg");
        b.u16(gridId);
        b.u16(n);
        for (int id = 1; id <= n; id++) b.u16(id);
        b.end();
        if (exifData != null) {
            b.start("cdsc");
            b.u16(exifId);
            b.u16(1);
            b.u16(gridId);
            b.end();
        }
        b.end();

        b.start("iprp");
        b.start("ipco");
        b.start("hvcC"); // 1
        b.raw(hvcC);
        b.end();
        ispe(b, grid.tile, grid.tile); // 2
        ispe(b, grid.width, grid.height); // 3
        b.start("colr"); // 4
        b.fourcc("nclx");
        b.u16(PRIMARIES);
        b.u16(TRANSFER_SRGB);
        b.u16(MATRIX);
        b.u8(FULL_RANGE ? 0x80 : 0);
        b.end();
        b.startFull("pixi", 0, 0); // 5
        b.u8(3);
        for (int c = 0; c < 3; c++) b.u8(bitDepth);
        b.end();
        b.end(); // ipco
        b.startFull("ipma", 0, 0);
        b.u32(n + 1); // entry_count: the tiles and the grid (the Exif item has no properties)
        for (int id = 1; id <= n; id++) {
            b.u16(id);
            b.u8(4);
            b.u8(0x80 | 1); // hvcC, essential
            b.u8(2);
            b.u8(4);
            b.u8(5);
        }
        b.u16(gridId);
        b.u8(3);
        b.u8(3);
        b.u8(4);
        b.u8(5);
        b.end(); // ipma
        b.end(); // iprp

        // iloc version 1: offset_size 4, length_size 4, base_offset_size 0, index_size 0; one extent per item.
        b.startFull("iloc", 1, 0);
        b.u8(0x44);
        b.u8(0x00);
        b.u16(n + (exifData != null ? 2 : 1));
        long offset = dataStart;
        final long gridOffset = offset;
        offset += gridLength;
        final long exifOffset = offset;
        if (exifData != null) offset += exifData.length;
        for (int id = 1; id <= n; id++) {
            final int length = tiles.isEmpty() ? 0 : tiles.get(id - 1).length;
            iloc(b, id, offset, length);
            offset += length;
        }
        iloc(b, gridId, gridOffset, gridLength);
        if (exifData != null) iloc(b, exifId, exifOffset, exifData.length);
        b.end();

        b.end(); // meta
        return b.bytes();
    }

    private static void infe(Box b, int id, String type, boolean hidden) {
        b.startFull("infe", 2, hidden ? 1 : 0);
        b.u16(id);
        b.u16(0); // item_protection_index
        b.fourcc(type);
        b.u8(0); // item_name: empty string
        b.end();
    }

    private static void ispe(Box b, int width, int height) {
        b.startFull("ispe", 0, 0);
        b.u32(width);
        b.u32(height);
        b.end();
    }

    private static void iloc(Box b, int id, long offset, long length) {
        b.u16(id);
        b.u16(0); // reserved + construction_method 0 (file offset)
        b.u16(0); // data_reference_index: this file
        b.u16(1); // extent_count
        b.u32(offset);
        b.u32(length);
    }

    /** Big-endian ISO BMFF box builder with nested size patching. */
    static final class Box {
        private byte[] buf = new byte[256];
        private int size;
        private final int[] starts = new int[16];
        private int depth;

        private void ensure(int n) {
            if (size + n > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, size + n));
        }

        void u8(int v) {
            ensure(1);
            buf[size++] = (byte) v;
        }

        void u16(int v) {
            if (v < 0 || v > 0xFFFF) throw new IllegalArgumentException("u16 " + v);
            u8(v >>> 8);
            u8(v);
        }

        void u32(long v) {
            if (v < 0 || v > 0xFFFFFFFFL) throw new IllegalArgumentException("u32 " + v);
            u8((int) (v >>> 24));
            u8((int) (v >>> 16));
            u8((int) (v >>> 8));
            u8((int) v);
        }

        void u64(long v) {
            u32(v >>> 32);
            u32(v & 0xFFFFFFFFL);
        }

        void fourcc(String s) {
            final byte[] b = s.getBytes(StandardCharsets.US_ASCII);
            if (b.length != 4) throw new IllegalArgumentException("fourcc " + s);
            raw(b);
        }

        void raw(byte[] b) {
            ensure(b.length);
            System.arraycopy(b, 0, buf, size, b.length);
            size += b.length;
        }

        void start(String type) {
            starts[depth++] = size;
            u32(0);
            fourcc(type);
        }

        void startFull(String type, int version, int flags) {
            start(type);
            u8(version);
            u8(flags >>> 16);
            u8(flags >>> 8);
            u8(flags);
        }

        void end() {
            final int start = starts[--depth];
            final int length = size - start;
            buf[start] = (byte) (length >>> 24);
            buf[start + 1] = (byte) (length >>> 16);
            buf[start + 2] = (byte) (length >>> 8);
            buf[start + 3] = (byte) length;
        }

        byte[] bytes() {
            if (depth != 0) throw new IllegalStateException("open box");
            return java.util.Arrays.copyOf(buf, size);
        }
    }
}
