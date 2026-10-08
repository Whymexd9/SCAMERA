package com.particlesdevs.photoncamera.processing.heif;

import android.app.Application;
import android.media.MediaFormat;

import com.particlesdevs.photoncamera.processing.color.IccProfiles;
import com.particlesdevs.photoncamera.processing.color.OutputColour;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.particlesdevs.photoncamera.processing.heif.HeifContainerWriterTest.boxes;
import static com.particlesdevs.photoncamera.processing.heif.HeifContainerWriterTest.only;
import static com.particlesdevs.photoncamera.processing.heif.HeifContainerWriterTest.u16;
import static com.particlesdevs.photoncamera.processing.heif.HeifContainerWriterTest.u32;
import static org.junit.Assert.*;

/**
 * P46 in the HEIC files: the nclx / ICC colr boxes of the 10-bit writer (Display P3, HLG), the ICC added to a finished HEIF
 * (HeifWriter's 8-bit HEIC), the BT.2020 P010 matrix and the HEVC VUI of the HDR encode.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class HeifColourTest {
    /** A parsed file: ipco properties (type + payload), ipma (item -> property indices), items' iloc data. */
    static final class Parsed {
        final List<String> propTypes = new ArrayList<>();
        final List<byte[]> props = new ArrayList<>();
        final Map<Integer, List<Integer>> assoc = new HashMap<>();
        final Map<Integer, byte[]> data = new HashMap<>();
        int primary;
    }

    static Parsed parse(byte[] f) {
        final Parsed p = new Parsed();
        HeifContainerWriterTest.Box meta = null;
        for (HeifContainerWriterTest.Box b : boxes(f, 0, f.length)) if (b.type.equals("meta")) meta = b;
        assertNotNull(meta);
        final List<HeifContainerWriterTest.Box> m = boxes(f, meta.start + 4, meta.end);
        p.primary = u16(f, only(m, "pitm").start + 4);
        final HeifContainerWriterTest.Box iprp = only(m, "iprp");
        final List<HeifContainerWriterTest.Box> pr = boxes(f, iprp.start, iprp.end);
        final HeifContainerWriterTest.Box ipco = only(pr, "ipco"), ipma = only(pr, "ipma");
        for (HeifContainerWriterTest.Box b : boxes(f, ipco.start, ipco.end)) {
            p.propTypes.add(b.type);
            p.props.add(Arrays.copyOfRange(f, b.start, b.end));
        }
        assertEquals(0, f[ipma.start]); // version 0
        final boolean wide = (f[ipma.start + 3] & 1) != 0;
        int q = ipma.start + 8;
        for (long e = 0, n = u32(f, ipma.start + 4); e < n; e++) {
            final int item = u16(f, q);
            final int count = f[q + 2] & 0xFF;
            q += 3;
            final List<Integer> list = new ArrayList<>();
            for (int a = 0; a < count; a++) {
                list.add(wide ? u16(f, q) & 0x7FFF : f[q] & 0x7F);
                q += wide ? 2 : 1;
            }
            p.assoc.put(item, list);
        }
        final HeifContainerWriterTest.Box iloc = only(m, "iloc");
        assertEquals(1, f[iloc.start]);
        assertEquals(0x44, f[iloc.start + 4] & 0xFF);
        q = iloc.start + 6;
        final int items = u16(f, q);
        q += 2;
        for (int i = 0; i < items; i++) {
            final int id = u16(f, q);
            q += 6; // id, construction method, data reference
            final int extents = u16(f, q);
            q += 2;
            final ByteArrayOutputStream d = new ByteArrayOutputStream();
            for (int e = 0; e < extents; e++) {
                final int off = u32(f, q), len = u32(f, q + 4);
                d.write(f, off, len);
                q += 8;
            }
            p.data.put(id, d.toByteArray());
        }
        return p;
    }

    /** The colr payloads (type 4CC + rest) associated with {@code item}. */
    static List<byte[]> colr(Parsed p, int item) {
        final List<byte[]> out = new ArrayList<>();
        for (int index : p.assoc.get(item)) if (p.propTypes.get(index - 1).equals("colr")) out.add(p.props.get(index - 1));
        return out;
    }

    static byte[] file(OutputColour.Signal colour) throws Exception {
        final TileGrid grid = new TileGrid(150, 70, 64);
        final HeifContainerWriter w = new HeifContainerWriter(grid, HeifContainerWriterTest.hvcC(), 10);
        for (int i = 0; i < grid.count(); i++) w.addTile(HeifContainerWriterTest.tile(i));
        w.setExif(HeifContainerWriterTest.EXIF);
        w.setColour(colour);
        return w.toByteArray();
    }

    private static void nclx(byte[] colr, int primaries, int transfer, int matrix) {
        assertEquals("nclx", new String(colr, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(primaries, u16(colr, 4));
        assertEquals(transfer, u16(colr, 6));
        assertEquals(matrix, u16(colr, 8));
        assertEquals(0x80, colr[10] & 0xFF);
    }

    @Test
    public void defaultColourIsTheSrgbNclxOnly() throws Exception {
        final Parsed p = parse(file(OutputColour.Signal.SRGB));
        assertEquals(Arrays.asList("hvcC", "ispe", "ispe", "colr", "pixi"), p.propTypes);
        assertEquals(1, colr(p, p.primary).size());
        nclx(colr(p, p.primary).get(0), 1, 13, 1);
        assertArrayEquals("setColour(SRGB) writes the default bytes", HeifContainerWriterTestAccess.defaultSampleFile(), file(OutputColour.Signal.SRGB));
    }

    @Test
    public void displayP3HasNclx12AndTheProfileOnGridAndTiles() throws Exception {
        final Parsed p = parse(file(OutputColour.Signal.displayP3()));
        assertEquals(Arrays.asList("hvcC", "ispe", "ispe", "colr", "pixi", "colr"), p.propTypes);
        for (int item = 1; item <= 7; item++) {
            final List<byte[]> c = colr(p, item);
            assertEquals("item " + item, 2, c.size());
            nclx(c.get(0), 12, 13, 1);
            assertEquals("prof", new String(c.get(1), 0, 4, StandardCharsets.US_ASCII));
            assertArrayEquals(IccProfiles.displayP3(), Arrays.copyOfRange(c.get(1), 4, c.get(1).length));
        }
        assertEquals(7, p.primary);
        // the hvcC stays the first, essential property of every tile
        assertEquals(Integer.valueOf(1), p.assoc.get(1).get(0));
        // the item data did not move relative to their iloc entries
        final Parsed d = parse(file(OutputColour.Signal.SRGB));
        for (int item = 1; item <= 8; item++) assertArrayEquals("item " + item, d.data.get(item), p.data.get(item));
    }

    @Test
    public void hlgHasNclx9189AndNoProfile() throws Exception {
        final Parsed p = parse(file(OutputColour.Signal.HLG));
        assertEquals(Arrays.asList("hvcC", "ispe", "ispe", "colr", "pixi"), p.propTypes);
        nclx(colr(p, p.primary).get(0), 9, 18, 9);
        nclx(colr(p, 1).get(0), 9, 18, 9);
    }

    @Test
    public void patchAddsTheProfileAndMovesTheOffsets() throws Exception {
        final byte[] original = file(OutputColour.Signal.SRGB);
        final byte[] icc = IccProfiles.displayP3();
        final byte[] patched = HeifColourPatch.patch(original, icc, OutputColour.PRIMARIES_P3);
        assertNotNull(patched);
        assertTrue(patched.length > original.length + icc.length);
        final Parsed before = parse(original), after = parse(patched);
        assertEquals(Arrays.asList("hvcC", "ispe", "ispe", "colr", "pixi", "colr"), after.propTypes);
        for (int item = 1; item <= 7; item++) {
            final List<byte[]> c = colr(after, item);
            assertEquals(2, c.size());
            // the nclx the file had now names the P3 primaries too
            nclx(c.get(0), 12, 13, 1);
            assertArrayEquals(icc, Arrays.copyOfRange(c.get(1), 4, c.get(1).length));
        }
        assertEquals("the Exif item has no properties", null, after.assoc.get(8));
        for (int item = 1; item <= 8; item++) assertArrayEquals("item " + item + " data", before.data.get(item), after.data.get(item));
        // a profile already there (the encoder's own) is replaced, not doubled
        final byte[] other = Arrays.copyOf(icc, icc.length + 4);
        final Parsed again = parse(HeifColourPatch.patch(patched, other, OutputColour.PRIMARIES_P3));
        assertEquals(after.propTypes, again.propTypes);
        assertEquals(after.assoc, again.assoc);
        assertArrayEquals(other, Arrays.copyOfRange(colr(again, 7).get(1), 4, colr(again, 7).get(1).length));
        for (int item = 1; item <= 8; item++) assertArrayEquals("item " + item + " data", before.data.get(item), again.data.get(item));
    }

    /** A minimal single-image HEIF with the mdat BEFORE the meta box (offsets must not move) and 15-bit ipma indices. */
    private static byte[] mdatFirst(byte[] payload) {
        final Box b = new Box();
        b.start("ftyp").fourcc("heic").u32(0).fourcc("mif1").fourcc("heic").end();
        final int mdatPayload = b.size() + 8;
        b.start("mdat").raw(payload).end();
        b.startFull("meta", 0, 0);
        b.startFull("hdlr", 0, 0).u32(0).fourcc("pict").u32(0).u32(0).u32(0).u8(0).end();
        b.startFull("pitm", 0, 0).u16(1).end();
        b.startFull("iinf", 0, 0).u16(1);
        b.startFull("infe", 2, 0).u16(1).u16(0).fourcc("hvc1").u8(0).end();
        b.end();
        b.start("iprp");
        b.start("ipco");
        b.start("hvcC").raw(new byte[23]).end();
        b.startFull("ispe", 0, 0).u32(16).u32(16).end();
        b.end();
        b.startFull("ipma", 0, 1).u32(1).u16(1).u8(2).u16(0x8001).u16(2).end();
        b.end();
        b.startFull("iloc", 1, 0).u8(0x44).u8(0).u16(1).u16(1).u16(0).u16(0).u16(1).u32(mdatPayload).u32(payload.length).end();
        b.end();
        return b.bytes();
    }

    @Test
    public void patchLeavesDataBeforeMetaAndKeepsWideIndices() throws Exception {
        final byte[] payload = new byte[333];
        new Random(5).nextBytes(payload);
        final byte[] in = mdatFirst(payload);
        final byte[] out = HeifColourPatch.patch(in, IccProfiles.displayP3(), 12);
        assertNotNull(out);
        // the mdat (and its payload) is where it was
        assertArrayEquals(Arrays.copyOfRange(in, 0, 32 + payload.length), Arrays.copyOfRange(out, 0, 32 + payload.length));
        final int meta = indexOf(out, "ipma");
        assertEquals(1, out[meta + 7] & 1); // flags: 15-bit indices kept
        assertEquals(1, u32(out, meta + 8)); // one entry
        assertEquals(1, u16(out, meta + 12)); // item 1
        assertEquals(3, out[meta + 14] & 0xFF); // hvcC, ispe, + the profile
        assertEquals(0x8001, u16(out, meta + 15));
        assertEquals(2, u16(out, meta + 17));
        assertEquals(3, u16(out, meta + 19));
        final int iloc = indexOf(out, "iloc");
        assertEquals(24 + 8, u32(out, iloc + 4 + 4 + 2 + 2 + 2 + 2 + 2 + 2)); // offset unchanged: the mdat payload after ftyp (24) and its header
    }

    private static int indexOf(byte[] d, String fourcc) {
        final byte[] t = fourcc.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + 4 <= d.length; i++) {
            for (int j = 0; j < 4; j++) if (d[i + j] != t[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Tiny big-endian box builder for synthetic files. */
    static final class Box {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final int[] starts = new int[16];
        private int depth;
        private byte[] buf;

        Box u8(int v) { out.write(v); return this; }
        Box u16(int v) { out.write(v >>> 8); out.write(v); return this; }
        Box u32(long v) { u16((int) (v >>> 16)); u16((int) v); return this; }
        Box fourcc(String s) { out.write(s.getBytes(StandardCharsets.US_ASCII), 0, 4); return this; }
        Box raw(byte[] b) { out.write(b, 0, b.length); return this; }
        Box start(String type) { starts[depth++] = out.size(); u32(0); return fourcc(type); }
        Box startFull(String type, int version, int flags) { start(type); u8(version); u8(flags >>> 16); u8(flags >>> 8); return u8(flags); }
        int size() { return out.size(); }
        Box end() {
            final int s = starts[--depth];
            buf = out.toByteArray();
            final int n = buf.length - s;
            buf[s] = (byte) (n >>> 24); buf[s + 1] = (byte) (n >>> 16); buf[s + 2] = (byte) (n >>> 8); buf[s + 3] = (byte) n;
            out.reset();
            out.write(buf, 0, buf.length);
            return this;
        }
        byte[] bytes() { assertEquals(0, depth); return out.toByteArray(); }
    }

    // ------------------------------------------------------------------------------------------------ P010 / VUI

    @Test
    public void bt2020MatrixCoefficients() {
        final P010.Matrix m = P010.Matrix.BT2020;
        assertEquals(9, m.code);
        assertEquals(65536, m.kr + m.kg + m.kb);
        assertEquals(0, m.cbR + m.cbG + m.cbB);
        assertEquals(0, m.crR + m.crG + m.crB);
        assertEquals(0.2627, m.kr / 65536.0, 1e-4);
        assertEquals(0.0593, m.kb / 65536.0, 1e-4);
        // BT.709 rebuilt by the same rule gives the constants of the default path
        final P010.Matrix b = P010.Matrix.of(1, 0.2126, 0.0722);
        assertArrayEquals(new int[]{P010.KR, P010.KG, P010.KB, P010.CB_R, P010.CB_G, P010.CB_B, P010.CR_R, P010.CR_G, P010.CR_B},
                new int[]{b.kr, b.kg, b.kb, b.cbR, b.cbG, b.cbB, b.crR, b.crG, b.crB});
        assertSame(P010.Matrix.BT2020, P010.Matrix.forCode(9));
        assertSame(P010.Matrix.BT709, P010.Matrix.forCode(1));
    }

    @Test
    public void bt2020TileMatchesTheFormula() {
        final Random rnd = new Random(2020);
        final int w = 8, h = 8;
        final int[] band = new int[w * h];
        for (int i = 0; i < band.length; i++) band[i] = P010.pack(rnd.nextInt(1024), rnd.nextInt(1024), rnd.nextInt(1024));
        final byte[] out = new byte[P010.tileBytes(w, h)];
        P010.convertTile(band, w, h, 0, w, w, h, out, P010.Matrix.BT2020);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                final int p = band[y * w + x];
                final double r = p & 0x3FF, g = (p >>> 10) & 0x3FF, b = (p >>> 20) & 0x3FF;
                assertEquals(0.2627 * r + 0.6780 * g + 0.0593 * b, P010.sample(out, 2 * (y * w + x)), 1.01);
            }
        // white and black: neutral chroma
        final int[] white = new int[4];
        Arrays.fill(white, P010.pack(1023, 1023, 1023));
        final byte[] t = new byte[P010.tileBytes(2, 2)];
        P010.convertTile(white, 2, 2, 0, 2, 2, 2, t, P010.Matrix.BT2020);
        assertEquals(1023, P010.sample(t, 0));
        assertEquals(512, P010.sample(t, 8));
        assertEquals(512, P010.sample(t, 10));
    }

    @Test
    public void hdrVuiIsBt2020Hlg() {
        final MediaFormat f = Heic10Encoder.format(512, true, 80, 0, OutputColour.Signal.HLG);
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, f.getInteger(MediaFormat.KEY_COLOR_STANDARD));
        assertEquals(MediaFormat.COLOR_TRANSFER_HLG, f.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
        assertEquals(MediaFormat.COLOR_RANGE_FULL, f.getInteger(MediaFormat.KEY_COLOR_RANGE));
        assertEquals(Heic10Support.HEVC_PROFILE_MAIN10, f.getInteger(MediaFormat.KEY_PROFILE));
        // Display P3 is SDR: the VUI of the default (MediaFormat has no P3 standard), the container says P3
        final MediaFormat p3 = Heic10Encoder.format(512, true, 80, 0, OutputColour.Signal.displayP3());
        final MediaFormat def = Heic10Encoder.format(512, true, 80, 0);
        for (String key : new String[]{MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_RANGE})
            assertEquals(key, def.getInteger(key), p3.getInteger(key));
    }
}
