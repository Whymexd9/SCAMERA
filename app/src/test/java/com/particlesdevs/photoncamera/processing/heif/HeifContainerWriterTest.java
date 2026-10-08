package com.particlesdevs.photoncamera.processing.heif;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** The HEIF file of the 10-bit HEIC: box tree, items, references, properties, iloc offsets, grid and Exif data. */
public class HeifContainerWriterTest {
    /** One parsed box: type and the payload range of the whole file. */
    static final class Box {
        final String type;
        final int start, end; // payload

        Box(String type, int start, int end) {
            this.type = type;
            this.start = start;
            this.end = end;
        }
    }

    static List<Box> boxes(byte[] d, int start, int end) {
        final List<Box> out = new ArrayList<>();
        int p = start;
        while (p < end) {
            final int size = u32(d, p);
            assertTrue("box size " + size + " at " + p, size >= 8 && p + size <= end);
            out.add(new Box(new String(d, p + 4, 4, StandardCharsets.US_ASCII), p + 8, p + size));
            p += size;
        }
        assertEquals("boxes fill their parent exactly", end, p);
        return out;
    }

    static Box only(List<Box> list, String type) {
        Box found = null;
        for (Box b : list) if (b.type.equals(type)) {
            assertNull("two " + type + " boxes", found);
            found = b;
        }
        assertNotNull("no " + type, found);
        return found;
    }

    static int u16(byte[] d, int p) {
        return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
    }

    static int u32(byte[] d, int p) {
        return ByteBuffer.wrap(d, p, 4).getInt();
    }

    static byte[] hvcC() {
        final byte[] sps = HevcNalTest.sps(0, 2, 93, 1, 64, 64, null, 10, 10);
        return HevcNal.hvcC(Collections.singletonList(new byte[]{0x40, 0x01, 0x0C, 0x01}), Collections.singletonList(sps),
                Collections.singletonList(new byte[]{0x44, 0x01, (byte) 0xC1, 0x72}));
    }

    /** A 150 x 70 image in 64 x 64 tiles (3 x 2, padded right and bottom), each sample tagged with its index. */
    static byte[] sampleFile(byte[] exif) throws Exception {
        final TileGrid grid = new TileGrid(150, 70, 64);
        final HeifContainerWriter w = new HeifContainerWriter(grid, hvcC(), 10);
        for (int i = 0; i < grid.count(); i++) w.addTile(tile(i));
        w.setExif(exif);
        return w.toByteArray();
    }

    static byte[] tile(int i) {
        final byte[] slice = {0x26, 0x01, (byte) (0x10 + i), 0x55, (byte) i, 0x7F};
        final byte[] out = new byte[4 + slice.length + i]; // different lengths per tile
        out[3] = (byte) (slice.length + i);
        System.arraycopy(slice, 0, out, 4, slice.length);
        return out;
    }

    static final byte[] EXIF = ("Exif\0\0II*\0" + "\u0008\0\0\0" + "\0\0\0\0\0\0").getBytes(StandardCharsets.ISO_8859_1);

    @Test
    public void ftypListsHeicAndMif1() throws Exception {
        final byte[] f = sampleFile(EXIF);
        final List<Box> top = boxes(f, 0, f.length);
        assertEquals(Arrays.asList("ftyp", "meta", "mdat"), Arrays.asList(top.get(0).type, top.get(1).type, top.get(2).type));
        final Box ftyp = top.get(0);
        assertEquals("heic", new String(f, ftyp.start, 4, StandardCharsets.US_ASCII));
        assertEquals(0, u32(f, ftyp.start + 4));
        final List<String> brands = new ArrayList<>();
        for (int p = ftyp.start + 8; p < ftyp.end; p += 4) brands.add(new String(f, p, 4, StandardCharsets.US_ASCII));
        assertEquals(Arrays.asList("mif1", "heic", "heix", "miaf"), brands);
    }

    @Test
    public void itemsReferencesPropertiesAndLocations() throws Exception {
        final byte[] f = sampleFile(EXIF);
        final List<Box> top = boxes(f, 0, f.length);
        final Box meta = top.get(1), mdat = top.get(2);
        final List<Box> m = boxes(f, meta.start + 4, meta.end);
        assertEquals("hdlr", m.get(0).type);
        assertEquals("pict", new String(f, m.get(0).start + 8, 4, StandardCharsets.US_ASCII));
        final int n = 6, gridId = 7, exifId = 8;
        assertEquals(gridId, u16(f, only(m, "pitm").start + 4));

        // iinf: 6 hidden hvc1 tiles, the grid, the Exif item
        final Box iinf = only(m, "iinf");
        assertEquals(n + 2, u16(f, iinf.start + 4));
        final Map<Integer, String> types = new LinkedHashMap<>();
        final Map<Integer, Boolean> hidden = new HashMap<>();
        for (Box infe : boxes(f, iinf.start + 6, iinf.end)) {
            assertEquals("infe", infe.type);
            assertEquals(2, f[infe.start]);
            final int id = u16(f, infe.start + 4);
            types.put(id, new String(f, infe.start + 8, 4, StandardCharsets.US_ASCII));
            hidden.put(id, (f[infe.start + 3] & 1) != 0);
        }
        for (int id = 1; id <= n; id++) {
            assertEquals("hvc1", types.get(id));
            assertTrue(hidden.get(id));
        }
        assertEquals("grid", types.get(gridId));
        assertFalse(hidden.get(gridId));
        assertEquals("Exif", types.get(exifId));

        // iref: dimg grid -> tiles 1..6 in raster order, cdsc Exif -> grid
        final Box iref = only(m, "iref");
        final List<Box> refs = boxes(f, iref.start + 4, iref.end);
        final Box dimg = only(refs, "dimg");
        assertEquals(gridId, u16(f, dimg.start));
        assertEquals(n, u16(f, dimg.start + 2));
        for (int i = 0; i < n; i++) assertEquals(i + 1, u16(f, dimg.start + 4 + 2 * i));
        final Box cdsc = only(refs, "cdsc");
        assertEquals(exifId, u16(f, cdsc.start));
        assertEquals(1, u16(f, cdsc.start + 2));
        assertEquals(gridId, u16(f, cdsc.start + 4));

        // ipco: hvcC, ispe tile, ispe image, colr nclx, pixi
        final Box iprp = only(m, "iprp");
        final List<Box> pr = boxes(f, iprp.start, iprp.end);
        final List<Box> props = boxes(f, only(pr, "ipco").start, only(pr, "ipco").end);
        assertEquals(Arrays.asList("hvcC", "ispe", "ispe", "colr", "pixi"),
                Arrays.asList(props.get(0).type, props.get(1).type, props.get(2).type, props.get(3).type, props.get(4).type));
        assertArrayEquals(hvcC(), Arrays.copyOfRange(f, props.get(0).start, props.get(0).end));
        assertEquals(64, u32(f, props.get(1).start + 4));
        assertEquals(64, u32(f, props.get(1).start + 8));
        assertEquals(150, u32(f, props.get(2).start + 4));
        assertEquals(70, u32(f, props.get(2).start + 8));
        final Box colr = props.get(3);
        assertEquals("nclx", new String(f, colr.start, 4, StandardCharsets.US_ASCII));
        assertEquals(1, u16(f, colr.start + 4)); // BT.709 primaries
        assertEquals(13, u16(f, colr.start + 6)); // sRGB transfer
        assertEquals(1, u16(f, colr.start + 8)); // BT.709 matrix
        assertEquals(0x80, f[colr.start + 10] & 0xFF); // full range
        final Box pixi = props.get(4);
        assertArrayEquals(new byte[]{3, 10, 10, 10}, Arrays.copyOfRange(f, pixi.start + 4, pixi.end));

        // ipma: tiles -> hvcC (essential), ispe tile, colr, pixi; grid -> ispe image, colr, pixi
        final Box ipma = only(pr, "ipma");
        assertEquals(n + 1, u32(f, ipma.start + 4));
        int p = ipma.start + 8;
        for (int id = 1; id <= n + 1; id++) {
            assertEquals("entries sorted by item id", id, u16(f, p));
            final int count = f[p + 2];
            final byte[] assoc = Arrays.copyOfRange(f, p + 3, p + 3 + count);
            if (id <= n) assertArrayEquals(new byte[]{(byte) 0x81, 2, 4, 5}, assoc);
            else assertArrayEquals(new byte[]{3, 4, 5}, assoc);
            p += 3 + count;
        }
        assertEquals(ipma.end, p);

        // iloc v1: every item's single extent inside mdat; the data there is what was given
        final Box iloc = only(m, "iloc");
        assertEquals(1, f[iloc.start]);
        assertEquals(0x44, f[iloc.start + 4] & 0xFF);
        assertEquals(0x00, f[iloc.start + 5] & 0xFF);
        final int count = u16(f, iloc.start + 6);
        assertEquals(n + 2, count);
        final Map<Integer, byte[]> data = new HashMap<>();
        p = iloc.start + 8;
        for (int i = 0; i < count; i++) {
            final int id = u16(f, p);
            assertEquals("construction_method 0", 0, u16(f, p + 2));
            assertEquals(0, u16(f, p + 4));
            assertEquals(1, u16(f, p + 6));
            final int off = u32(f, p + 8), len = u32(f, p + 12);
            assertTrue("extent of item " + id + " inside mdat", off >= mdat.start && off + len <= mdat.end);
            data.put(id, Arrays.copyOfRange(f, off, off + len));
            p += 16;
        }
        assertEquals(iloc.end, p);
        for (int id = 1; id <= n; id++) assertArrayEquals(tile(id - 1), data.get(id));
        // ImageGrid: version 0, flags 0, rows-1 = 1, columns-1 = 2, 16-bit output size
        assertArrayEquals(new byte[]{0, 0, 1, 2, 0, (byte) 150, 0, 70}, data.get(gridId));
        // Exif: tiff header offset 6 (big-endian) then "Exif\0\0" and the TIFF header
        final byte[] exif = data.get(exifId);
        assertArrayEquals(new byte[]{0, 0, 0, 6}, Arrays.copyOf(exif, 4));
        assertArrayEquals(EXIF, Arrays.copyOfRange(exif, 4, exif.length));
        // mdat holds exactly the items
        int total = 0;
        for (byte[] b : data.values()) total += b.length;
        assertEquals(mdat.end - mdat.start, total);
    }

    @Test
    public void withoutExifThereIsNoExifItemOrCdsc() throws Exception {
        final byte[] f = sampleFile(null);
        final List<Box> top = boxes(f, 0, f.length);
        final List<Box> m = boxes(f, top.get(1).start + 4, top.get(1).end);
        assertEquals(7, u16(f, only(m, "iinf").start + 4));
        final Box iref = only(m, "iref");
        final List<Box> refs = boxes(f, iref.start + 4, iref.end);
        assertEquals(1, refs.size());
        assertEquals("dimg", refs.get(0).type);
    }

    @Test
    public void bareTiffExifGetsOffsetZero() {
        final byte[] tiff = "II*\0abcd".getBytes(StandardCharsets.ISO_8859_1);
        final byte[] item = HeifContainerWriter.exifItem(tiff);
        assertArrayEquals(new byte[]{0, 0, 0, 0}, Arrays.copyOf(item, 4));
        assertArrayEquals(tiff, Arrays.copyOfRange(item, 4, item.length));
    }

    @Test
    public void largeGridUses32BitSize() {
        final HeifContainerWriter w = new HeifContainerWriter(new TileGrid(70000, 600, 512), hvcC(), 10);
        final byte[] g = w.imageGrid();
        assertEquals(12, g.length);
        assertEquals(1, g[1]);
        assertEquals(1, g[2]); // rows - 1
        assertEquals(136, g[3] & 0xFF); // columns - 1: ceil(70000 / 512) = 137
        assertEquals(70000, u32(g, 4));
        assertEquals(600, u32(g, 8));
    }

    @Test
    public void incompleteGridIsRefused() throws Exception {
        final HeifContainerWriter w = new HeifContainerWriter(new TileGrid(150, 70, 64), hvcC(), 10);
        w.addTile(tile(0));
        try {
            w.toByteArray();
            fail("a grid with missing tiles was written");
        } catch (IllegalStateException expected) {
            // the encoder gave fewer frames than tiles: no file
        }
    }
}
