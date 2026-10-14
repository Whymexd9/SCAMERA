package com.particlesdevs.photoncamera.processing.heif;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * P125: the HEIC with an ISO 21496-1 gain map (HeifContainerWriter.setGainMap): the SDR grid stays the primary item, the map
 * is a hidden grid, the 'tmap' item derives from both (base first) with the metadata as its data, 'altr' {tmap, grid}, the
 * 'tmap' brand; without a map the file is byte for byte the old one.
 */
public class HeifGainMapTest {
    private static byte[] fake(int n, int seed) {
        final byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (seed + i);
        return b;
    }

    private static HeifContainerWriter writer(boolean gain, byte[] toneMap) {
        final HeifContainerWriter w = new HeifContainerWriter(new TileGrid(1000, 600), fake(30, 1), 10); // 2 x 2 tiles
        for (int i = 0; i < 4; i++) w.addTile(fake(40 + i, 10 * i));
        w.setExif(new byte[]{'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 42});
        if (gain) {
            w.setGainMap(new TileGrid(500, 300), fake(31, 7), 10, toneMap); // 1 tile
            w.addGainTile(fake(50, 99));
        }
        return w;
    }

    /** Top-level and meta child boxes: type -> payload (after the size / type, and the version / flags of full boxes). */
    private static Map<String, byte[]> boxes(byte[] file, int from, int to, boolean full) {
        final Map<String, byte[]> out = new HashMap<>();
        int at = from;
        while (at + 8 <= to) {
            final int size = ByteBuffer.wrap(file, at, 4).getInt();
            final String type = new String(file, at + 4, 4, StandardCharsets.US_ASCII);
            final int skip = full ? 12 : 8;
            out.put(type, Arrays.copyOfRange(file, at + skip, at + size));
            at += size;
        }
        return out;
    }

    private static Map<String, byte[]> meta(byte[] file) {
        final Map<String, byte[]> top = boxes(file, 0, file.length, false);
        final byte[] meta = top.get("meta");
        return boxes(meta, 4, meta.length, false); // meta is a full box
    }

    @Test public void withoutAMapTheFileIsTheOldOne() throws Exception {
        final byte[] plain = writer(false, null).toByteArray();
        assertFalse(new String(plain, 0, 64, StandardCharsets.US_ASCII).contains("tmap"));
        assertFalse(meta(plain).containsKey("grpl"));
    }

    @Test public void gainMapItemsAndReferences() throws Exception {
        final byte[] toneMap = fake(62, 3);
        final HeifContainerWriter w = writer(true, toneMap);
        final byte[] file = w.toByteArray();
        final Map<String, byte[]> top = boxes(file, 0, file.length, false);
        final String brands = new String(top.get("ftyp"), StandardCharsets.US_ASCII);
        assertTrue("the 'tmap' brand", brands.contains("tmap"));
        final Map<String, byte[]> meta = meta(file);
        // pitm: the SDR grid (4 tiles -> grid id 5)
        assertEquals(5, ByteBuffer.wrap(meta.get("pitm"), 4, 2).getShort());
        // ids: tiles 1-4, grid 5, Exif 6, gain tile 7, gain grid 8, tmap 9
        assertEquals(7, w.gainTileId(0));
        assertEquals(8, w.gainGridItemId());
        assertEquals(9, w.toneMapItemId());
        // iinf: item types and hidden flags
        final byte[] iinf = meta.get("iinf");
        final int count = ByteBuffer.wrap(iinf, 4, 2).getShort();
        assertEquals(9, count);
        final List<String> types = new ArrayList<>();
        final List<Integer> hidden = new ArrayList<>();
        int at = 6;
        for (int i = 0; i < count; i++) {
            final int size = ByteBuffer.wrap(iinf, at, 4).getInt();
            hidden.add(iinf[at + 11] & 1);
            types.add(new String(iinf, at + 16, 4, StandardCharsets.US_ASCII));
            at += size;
        }
        assertEquals(Arrays.asList("hvc1", "hvc1", "hvc1", "hvc1", "grid", "Exif", "hvc1", "grid", "tmap"), types);
        assertEquals("the map's tiles and grid hidden, 'tmap' shown", Arrays.asList(1, 1, 1, 1, 0, 0, 1, 1, 0), hidden);
        // iref: dimg tmap -> [grid 5, gain grid 8]
        final byte[] iref = meta.get("iref");
        boolean tmapRef = false;
        at = 4;
        while (at < iref.length) {
            final ByteBuffer b = ByteBuffer.wrap(iref, at, iref.length - at);
            final int size = b.getInt();
            final String type = new String(iref, at + 4, 4, StandardCharsets.US_ASCII);
            b.position(b.position() + 4);
            final int from = b.getShort();
            if (type.equals("dimg") && from == 9) {
                assertEquals(2, b.getShort());
                assertEquals("the base image first", 5, b.getShort());
                assertEquals("the gain map second", 8, b.getShort());
                tmapRef = true;
            }
            at += size;
        }
        assertTrue(tmapRef);
        // grpl / altr: {tmap 9, grid 5}
        final byte[] grpl = meta.get("grpl");
        final ByteBuffer altr = ByteBuffer.wrap(grpl);
        altr.getInt();
        assertEquals("altr", new String(grpl, 4, 4, StandardCharsets.US_ASCII));
        altr.position(12);
        altr.getInt(); // group_id
        assertEquals(2, altr.getInt());
        assertEquals(9, altr.getInt());
        assertEquals(5, altr.getInt());
        // iloc: the 'tmap' item's data is the metadata
        final byte[] iloc = meta.get("iloc");
        final ByteBuffer l = ByteBuffer.wrap(iloc, 6, iloc.length - 6);
        final int items = l.getShort();
        boolean found = false;
        for (int i = 0; i < items; i++) {
            final int id = l.getShort();
            l.getShort();
            l.getShort();
            l.getShort();
            final long offset = l.getInt() & 0xFFFFFFFFL, length = l.getInt() & 0xFFFFFFFFL;
            if (id == 9) {
                assertArrayEquals(toneMap, Arrays.copyOfRange(file, (int) offset, (int) (offset + length)));
                found = true;
            }
            if (id == 7) assertArrayEquals(fake(50, 99), Arrays.copyOfRange(file, (int) offset, (int) (offset + length)));
        }
        assertTrue(found);
    }

    @Test(expected = IllegalStateException.class)
    public void everyGainTileIsNeeded() throws Exception {
        final HeifContainerWriter w = new HeifContainerWriter(new TileGrid(600, 600), fake(30, 1), 10);
        for (int i = 0; i < 4; i++) w.addTile(fake(40, i));
        w.setGainMap(new TileGrid(600, 600), fake(30, 2), 10, fake(62, 0));
        w.toByteArray();
    }
}
