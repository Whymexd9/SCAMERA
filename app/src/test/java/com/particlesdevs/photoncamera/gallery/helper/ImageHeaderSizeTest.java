package com.particlesdevs.photoncamera.gallery.helper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import com.particlesdevs.photoncamera.processing.heif.HeifContainerWriterTestAccess;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * P196: the pixel size a file stores, read from its own headers (the gallery's «Разрешение», the compare panes). Files from
 * the real encoders (JPEG, WebP lossy / lossless) are checked in processing.PhotoMakeModelTest.
 */
public class ImageHeaderSizeTest {
    private static int[] size(byte[] file) throws IOException {
        return ImageHeaderSize.read(new ByteArrayInputStream(file));
    }

    /** A stream that skips and reads a few bytes at a time (a content resolver stream may). */
    private static int[] sizeSlowly(byte[] file) throws IOException {
        InputStream in = new FilterInputStream(new ByteArrayInputStream(file)) {
            @Override
            public long skip(long n) throws IOException {
                return super.skip(Math.min(n, 3));
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, 5));
            }
        };
        return ImageHeaderSize.read(in);
    }

    @Test
    public void jpegFrameHeaderAfterLargeAppSegmentsNotTheExifTags() throws Exception {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(new byte[]{(byte) 0xFF, (byte) 0xD8});
        segment(f, 0xE1, 60000); // EXIF (whatever size tags it claims)
        segment(f, 0xE2, 40000); // ICC / ISO 21496-1
        segment(f, 0xC4, 30); // DHT is not a frame header
        f.write(0xFF); // a fill byte
        sof(f, 0xC2, 8192, 6144); // progressive
        sos(f);
        assertArrayEquals(new int[]{8192, 6144}, size(f.toByteArray()));
        assertArrayEquals(new int[]{8192, 6144}, sizeSlowly(f.toByteArray()));
    }

    @Test
    public void ultraHdrJpegIsThePrimaryImage() throws Exception {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(new byte[]{(byte) 0xFF, (byte) 0xD8});
        segment(f, 0xE1, 200);
        sof(f, 0xC0, 4096, 3072);
        sos(f);
        f.write(new byte[]{(byte) 0xFF, (byte) 0xD9});
        f.write(new byte[]{(byte) 0xFF, (byte) 0xD8}); // the gain map JPEG after it
        sof(f, 0xC0, 1024, 768);
        assertArrayEquals(new int[]{4096, 3072}, size(f.toByteArray()));
    }

    @Test
    public void noSizeWithoutAFrameHeaderOrWhenCut() throws Exception {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(new byte[]{(byte) 0xFF, (byte) 0xD8});
        segment(f, 0xE0, 16);
        sos(f);
        assertNull(size(f.toByteArray()));
        ByteArrayOutputStream g = new ByteArrayOutputStream();
        g.write(new byte[]{(byte) 0xFF, (byte) 0xD8});
        segment(g, 0xE1, 5000);
        assertNull(size(Arrays.copyOf(g.toByteArray(), 3000))); // ends inside the EXIF
        assertNull(size("not an image at all".getBytes(StandardCharsets.US_ASCII)));
        assertNull(size(new byte[0]));
    }

    @Test
    public void webpLossyLosslessAndExtended() throws Exception {
        // VP8X canvas (24-bit minus one)
        byte[] vp8x = riff("VP8X", new byte[]{0x08, 0, 0, 0, (byte) 0xFF, 0x1F, 0x00, (byte) 0xFF, 0x17, 0x00});
        assertArrayEquals(new int[]{8192, 6144}, size(vp8x));
        // VP8: frame tag, start code, 14-bit sizes
        byte[] vp8 = riff("VP8 ", new byte[]{0x50, 0x2A, 0x01, (byte) 0x9D, 0x01, 0x2A, (byte) 0xD0, 0x07, (byte) 0xDC, 0x05});
        assertArrayEquals(new int[]{2000, 1500}, size(vp8));
        // VP8L: signature, 14-bit width-1 and height-1
        int w = 3000 - 1, h = 2000 - 1;
        long bits = w | ((long) h << 14);
        byte[] vp8l = riff("VP8L", new byte[]{0x2F, (byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24)});
        assertArrayEquals(new int[]{3000, 2000}, size(vp8l));
    }

    @Test
    public void heicFromOurWriterIsTheGridSize() throws Exception {
        byte[] heic = HeifContainerWriterTestAccess.defaultSampleFile(); // a 150 x 70 image in 64 x 64 tiles
        assertArrayEquals(new int[]{150, 70}, size(heic));
        assertArrayEquals(new int[]{150, 70}, sizeSlowly(heic));
    }

    @Test
    public void heifWithMdatFirstAndWideIpmaFields() throws Exception {
        // ftyp, mdat, then meta: pitm v1 (32-bit id 2), ipco [ispe 640x480 of item 1, ispe 8160x6120 of item 2],
        // ipma v1 flags 1 (32-bit ids, 16-bit indices with the essential bit)
        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        meta.write(new byte[4]); // version / flags
        meta.write(box("hdlr", concat(new byte[8], "pict".getBytes(StandardCharsets.US_ASCII), new byte[13])));
        meta.write(box("pitm", concat(new byte[]{1, 0, 0, 0}, u32(2))));
        byte[] ipco = box("ipco", concat(ispe(640, 480), ispe(8160, 6120)));
        byte[] ipma = box("ipma", concat(new byte[]{1, 0, 0, 1}, u32(2),
                u32(1), new byte[]{1}, new byte[]{(byte) 0x80, 1},
                u32(2), new byte[]{1}, new byte[]{(byte) 0x80, 2}));
        meta.write(box("iprp", concat(ipco, ipma)));
        byte[] file = concat(box("ftyp", "avifmif1".getBytes(StandardCharsets.US_ASCII)), box("mdat", new byte[50000]),
                box("meta", meta.toByteArray()));
        assertArrayEquals(new int[]{8160, 6120}, size(file));
        assertArrayEquals(new int[]{8160, 6120}, sizeSlowly(file));
        // without the primary item's property: nothing
        byte[] noPitm = concat(box("ftyp", "heicmif1".getBytes(StandardCharsets.US_ASCII)),
                box("meta", concat(new byte[4], box("iprp", concat(ipco, ipma)))));
        assertNull(size(noPitm));
    }

    // ------------------------------------------------------------------ builders

    private static void segment(ByteArrayOutputStream f, int marker, int payload) {
        f.write(0xFF);
        f.write(marker);
        int len = payload + 2;
        f.write(len >> 8);
        f.write(len);
        f.write(new byte[payload], 0, payload);
    }

    private static void sof(ByteArrayOutputStream f, int marker, int w, int h) {
        f.write(0xFF);
        f.write(marker);
        f.write(0);
        f.write(17);
        f.write(8);
        f.write(h >> 8);
        f.write(h);
        f.write(w >> 8);
        f.write(w);
        f.write(new byte[10], 0, 10);
    }

    private static void sos(ByteArrayOutputStream f) {
        segment(f, 0xDA, 10);
        f.write(new byte[100], 0, 100);
    }

    private static byte[] riff(String chunk, byte[] payload) {
        byte[] c = concat(chunk.getBytes(StandardCharsets.US_ASCII), le32(payload.length), payload);
        return concat("RIFF".getBytes(StandardCharsets.US_ASCII), le32(4 + c.length), "WEBP".getBytes(StandardCharsets.US_ASCII), c);
    }

    private static byte[] ispe(int w, int h) {
        return box("ispe", concat(new byte[4], u32(w), u32(h)));
    }

    private static byte[] box(String type, byte[] payload) {
        return concat(u32(payload.length + 8), type.getBytes(StandardCharsets.US_ASCII), payload);
    }

    private static byte[] u32(long v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] le32(long v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }
}
