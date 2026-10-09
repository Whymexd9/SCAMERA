package com.particlesdevs.photoncamera.gallery.glide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import com.bumptech.glide.load.Options;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AvifGlideDecoderTest {

    private static byte[] buildFtypBox(String majorBrand, int minorVersion, String... compatibleBrands) {
        int size = 8 + 4 + 4 + (compatibleBrands.length * 4);
        ByteBuffer bb = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(size);
        bb.put(new byte[]{'f', 't', 'y', 'p'});
        byte[] mb = majorBrand.getBytes();
        bb.put(mb, 0, Math.min(mb.length, 4));
        bb.putInt(minorVersion);
        for (String cb : compatibleBrands) {
            byte[] cbb = cb.getBytes();
            bb.put(cbb, 0, Math.min(cbb.length, 4));
        }
        return bb.array();
    }

    @Test
    public void isAvif_majorBrandAvif_returnsTrue() {
        byte[] data = buildFtypBox("avif", 0, "mif1");
        assertTrue(AvifGlideDecoder.isAvif(data, data.length));
    }

    @Test
    public void isAvif_majorBrandAvis_returnsTrue() {
        byte[] data = buildFtypBox("avis", 0, "msf1");
        assertTrue(AvifGlideDecoder.isAvif(data, data.length));
    }

    @Test
    public void isAvif_compatibleBrandAvif_returnsTrue() {
        byte[] data = buildFtypBox("mif1", 0, "miaf", "avif");
        assertTrue(AvifGlideDecoder.isAvif(data, data.length));
    }

    @Test
    public void isAvif_compatibleBrandAvis_returnsTrue() {
        byte[] data = buildFtypBox("msf1", 0, "miaf", "avis");
        assertTrue(AvifGlideDecoder.isAvif(data, data.length));
    }

    @Test
    public void isAvif_heicFormat_returnsFalse() {
        byte[] data = buildFtypBox("heic", 0, "mif1", "msf1");
        assertFalse(AvifGlideDecoder.isAvif(data, data.length));
    }

    @Test
    public void isAvif_jpegHeader_returnsFalse() {
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};
        assertFalse(AvifGlideDecoder.isAvif(jpeg, jpeg.length));
    }

    @Test
    public void isAvif_pngHeader_returnsFalse() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
        assertFalse(AvifGlideDecoder.isAvif(png, png.length));
    }

    @Test
    public void isAvif_webpHeader_returnsFalse() {
        byte[] webp = new byte[]{'R', 'I', 'F', 'F', 32, 0, 0, 0, 'W', 'E', 'B', 'P'};
        assertFalse(AvifGlideDecoder.isAvif(webp, webp.length));
    }

    @Test
    public void isAvif_dngHeader_returnsFalse() {
        byte[] dng = new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 0, 0};
        assertFalse(AvifGlideDecoder.isAvif(dng, dng.length));
    }

    @Test
    public void isAvif_shortOrNull_returnsFalse() {
        assertFalse(AvifGlideDecoder.isAvif(null, 0));
        assertFalse(AvifGlideDecoder.isAvif(new byte[]{0, 0, 0, 16, 'f', 't'}, 6));
        assertFalse(AvifGlideDecoder.isAvif(new byte[11], 11));
    }

    @Test
    public void handles_resetsStreamPosition() throws IOException {
        byte[] data = buildFtypBox("avif", 0, "mif1");
        ByteArrayInputStream is = new ByteArrayInputStream(data);
        AvifGlideDecoder decoder = new AvifGlideDecoder(null, null);

        // When SDK >= 31, handles returns true; on SDK < 31, handles returns false.
        boolean handled = decoder.handles(is, new Options());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(handled);
        } else {
            assertFalse(handled);
        }
        // Stream must be rewound to offset 0
        assertEquals(0, data.length - is.available());
    }

    @Test
    public void byteBufferDecoder_handlesAvif() {
        byte[] data = buildFtypBox("avif", 0, "mif1");
        ByteBuffer buf = ByteBuffer.wrap(data);
        AvifGlideDecoder.ByteBufferDecoder decoder = new AvifGlideDecoder.ByteBufferDecoder(null);

        boolean handled = decoder.handles(buf, new Options());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(handled);
        } else {
            assertFalse(handled);
        }
        // Buffer position must be preserved
        assertEquals(0, buf.position());
    }
}
