package com.particlesdevs.photoncamera.gallery.raw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.particlesdevs.photoncamera.gallery.editor.ExternalEdit;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** P352: which files are camera RAW (names, MIME types, first bytes) and where they go (DNG reader, LibRaw, the editor). */
public class RawFormatsTest {

    @Test
    public void extensionsAndMimes() {
        for (String n : new String[]{"a.CR2", "b.cr3", "x.nef", "x.NRW", "y.arw", "z.raf", "o.orf", "p.rw2", "q.pef", "r.srw",
                "h.3fr", "i.iiq", "m.mrw", "s.x3f", "g.gpr", "d.dng", "dir.with.dots/IMG_1.Dng"})
            assertTrue(n, RawFormats.isRawName(n));
        for (String n : new String[]{"a.jpg", "b.heic", "c", "c.", ".nef/x.png", null})
            assertFalse(String.valueOf(n), RawFormats.isRawName(n));
        assertTrue(RawFormats.isDngName("x.DNG"));
        assertTrue(RawFormats.isDngName("gopro.gpr"));
        assertFalse(RawFormats.isDngName("x.nef"));
        assertEquals("image/x-nikon-nef", RawFormats.mimeForName("A.NEF"));
        assertEquals("image/x-adobe-dng", RawFormats.mimeForName("a.dng"));
        assertEquals("image/x-dcraw", RawFormats.mimeForName("a.k25"));
        assertNull(RawFormats.mimeForName("a.jpg"));
        for (String m : new String[]{"image/x-canon-cr2", "image/x-sony-arw", "image/x-fuji-raf", "image/dng", "image/x-adobe-dng",
                "image/x-dcraw", "IMAGE/X-PANASONIC-RW2", "image/x-samsung-srw"})
            assertTrue(m, RawFormats.isRawMime(m));
        for (String m : new String[]{"image/jpeg", "image/x-icon", "application/octet-stream", "video/mp4", null})
            assertFalse(String.valueOf(m), RawFormats.isRawMime(m));
        assertEquals("cr3", RawFormats.extensionOf("C:\\dcim\\IMG.CR3"));
    }

    static byte[] tiff(boolean le, boolean dngVersion) {
        ByteBuffer b = ByteBuffer.allocate(64).order(le ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        b.put((byte) (le ? 'I' : 'M')).put((byte) (le ? 'I' : 'M')).putShort((short) 42).putInt(8);
        b.putShort((short) 2);
        b.putShort((short) 0x100).putShort((short) 4).putInt(1).putInt(100);
        b.putShort((short) (dngVersion ? 0xC612 : 0x110)).putShort((short) 1).putInt(4).putInt(0x01040000);
        b.putInt(0);
        return b.array();
    }

    @Test
    public void magic() {
        assertTrue(RawFormats.looksDng(tiff(true, true), 64));
        assertTrue(RawFormats.looksDng(tiff(false, true), 64));
        assertFalse(RawFormats.looksDng(tiff(true, false), 64));
        assertTrue("a TIFF-based RAW without DNGVersion (CR2, NEF, ARW...)", RawFormats.looksCameraRaw(tiff(true, false), 64));
        assertFalse(RawFormats.looksCameraRaw(tiff(true, true), 64));
        byte[] cr3 = new byte[16];
        System.arraycopy("\0\0\0\u0018ftypcrx ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0, cr3, 0, 12);
        assertTrue(RawFormats.looksCameraRaw(cr3, 16));
        assertTrue(RawFormats.looksCameraRaw("FUJIFILMCCD-RAW 0201".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 20));
        assertTrue(RawFormats.looksCameraRaw(new byte[]{'I', 'I', 'R', 'O', 8, 0, 0, 0}, 8));
        assertTrue(RawFormats.looksCameraRaw(new byte[]{'I', 'I', 'U', 0, 8, 0, 0, 0}, 8));
        assertFalse(RawFormats.looksCameraRaw(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1}, 4));
        assertFalse(RawFormats.looksCameraRaw(new byte[0], 0));
        // a first IFD beyond the bytes read: treated as DNG (the DNG reader decides, as before)
        byte[] far = tiff(true, false);
        far[4] = (byte) 0xF0;
        far[5] = 0x10;
        assertTrue(RawFormats.looksDng(far, 64));
    }

    @Test
    public void libRawFlipToExif() {
        assertEquals(1, RawFormats.orientationOfFlip(0));
        assertEquals(3, RawFormats.orientationOfFlip(3));
        assertEquals(8, RawFormats.orientationOfFlip(5));
        assertEquals(6, RawFormats.orientationOfFlip(6));
    }

    @Test
    public void editorTakesPhotosAndRaws() {
        for (String n : new String[]{"a.jpg", "b.HEIC", "c.webp", "d.avif", "e.png", "f.gif", "g.dng", "h.cr3", "i.arw"})
            assertTrue(n, ExternalEdit.editable(n, null));
        assertTrue(ExternalEdit.editable("noext", "image/x-fuji-raf"));
        assertTrue(ExternalEdit.editable("noext", "image/jpeg"));
        assertFalse(ExternalEdit.editable("clip.mp4", "video/mp4"));
        assertEquals("image/x-sony-arw", ExternalEdit.mediaTypeOf("DSC0001.ARW"));
        assertEquals("image/jpeg", ExternalEdit.mediaTypeOf("a.jpg"));
    }
}
