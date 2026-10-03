package com.particlesdevs.photoncamera.processing.ultrahdr;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The streaming Ultra HDR writer must produce exactly the bytes of the legacy
 * in-memory assembly (EXIF APP1 after SOI, then {@link UltraHdrContainer#encode}),
 * whatever chunking the JPEG encoder uses.
 */
public class UltraHdrContainerStreamTest {

    private static byte[] segment(int marker, byte[] payload) {
        int len = 2 + payload.length;
        byte[] seg = new byte[2 + len];
        seg[0] = (byte) 0xFF;
        seg[1] = (byte) marker;
        seg[2] = (byte) (len >> 8);
        seg[3] = (byte) len;
        System.arraycopy(payload, 0, seg, 4, payload.length);
        return seg;
    }

    /** Baseline like Skia's: SOI, JFIF APP0, optional ICC APP2, DQT, SOF0, SOS + entropy data, EOI. */
    private static byte[] baseline(int entropyBytes, boolean icc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        out.write(segment(0xE0, new byte[]{'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0}));
        if (icc) {
            byte[] profile = new byte[3000];
            byte[] tag = "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(tag, 0, profile, 0, tag.length);
            out.write(segment(0xE2, profile));
        }
        byte[] dqt = new byte[65];
        Arrays.fill(dqt, (byte) 3);
        out.write(segment(0xDB, dqt));
        out.write(segment(0xC0, new byte[]{8, 0x18, 0, 0x20, 0, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1}));
        out.write(segment(0xDA, new byte[]{3, 1, 0, 2, 0x11, 3, 0x11, 0, 0x3F, 0}));
        byte[] entropy = new byte[entropyBytes];
        new Random(7).nextBytes(entropy);
        for (int i = 0; i < entropy.length; i++) if (entropy[i] == (byte) 0xFF) entropy[i] = 0x7F;
        out.write(entropy);
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    private static byte[] exifApp1() {
        byte[] tiff = new byte[120];
        byte[] head = {'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 0x2A, 0, 8, 0, 0, 0};
        System.arraycopy(head, 0, tiff, 0, head.length);
        for (int i = head.length; i < tiff.length; i++) tiff[i] = (byte) i;
        return segment(0xE1, tiff);
    }

    private static byte[] withExifAfterSoi(byte[] jpeg, byte[] exif) {
        byte[] out = new byte[jpeg.length + exif.length];
        System.arraycopy(jpeg, 0, out, 0, 2);
        System.arraycopy(exif, 0, out, 2, exif.length);
        System.arraycopy(jpeg, 2, out, 2 + exif.length, jpeg.length - 2);
        return out;
    }

    private static final class Patchable extends ByteArrayOutputStream {
        void patch(long offset, byte[] bytes) {
            System.arraycopy(bytes, 0, buf, (int) offset, bytes.length);
        }
    }

    /** Writes {@code data} in chunks of {@code chunk} bytes, like Bitmap.compress through its 4 KB buffer. */
    private static UltraHdrContainer.PrimaryEncoder chunked(byte[] data, int chunk) {
        return out -> {
            for (int off = 0; off < data.length; off += chunk) out.write(data, off, Math.min(chunk, data.length - off));
            out.flush();
        };
    }

    private static byte[] streamed(byte[] sdr, byte[] exif, byte[] gain, int chunk) throws IOException {
        Patchable out = new Patchable();
        long total = UltraHdrContainer.write(out, out::patch, chunked(sdr, chunk), exif, gain, -0.25f, 3.5f, 3.5f);
        assertEquals(out.size(), total);
        return out.toByteArray();
    }

    @Test
    public void streamingMatchesLegacyAssembly() throws IOException {
        byte[] gain = baseline(900, false);
        byte[] exif = exifApp1();
        for (boolean icc : new boolean[]{false, true}) {
            byte[] sdr = baseline(50_000, icc);
            byte[] legacy = UltraHdrContainer.encode(withExifAfterSoi(sdr, exif), gain, -0.25f, 3.5f, 3.5f);
            for (int chunk : new int[]{1, 3, 7, 64, 4096, 1 << 20}) {
                assertArrayEquals("icc=" + icc + " chunk=" + chunk, legacy, streamed(sdr, exif, gain, chunk));
            }
        }
    }

    /** A whole JPEG larger than the metadata bound, handed over in one write: only its header is buffered. */
    @Test
    public void singleLargeWriteMatchesLegacyAssembly() throws IOException {
        byte[] gain = baseline(900, false);
        byte[] exif = exifApp1();
        byte[] sdr = baseline(3_000_000, true);
        byte[] legacy = UltraHdrContainer.encode(withExifAfterSoi(sdr, exif), gain, -0.25f, 3.5f, 3.5f);
        assertArrayEquals(legacy, streamed(sdr, exif, gain, sdr.length));
    }

    @Test
    public void streamingWithoutExifMatchesLegacyAssembly() throws IOException {
        byte[] gain = baseline(500, false);
        byte[] sdr = baseline(10_000, true);
        byte[] legacy = UltraHdrContainer.encode(sdr, gain, 0f, 2f, 2f);
        Patchable out = new Patchable();
        UltraHdrContainer.write(out, out::patch, chunked(sdr, 4096), null, gain, 0f, 2f, 2f);
        assertArrayEquals(legacy, out.toByteArray());
    }

    @Test
    public void mpfPointsAtTheGainMapImage() throws IOException {
        byte[] gain = baseline(700, false);
        byte[] sdr = baseline(20_000, false);
        byte[] file = streamed(sdr, exifApp1(), gain, 4096);
        int mpf = -1;
        for (int i = 0; i + 8 < file.length; i++) {
            if ((file[i] & 0xFF) == 0xFF && (file[i + 1] & 0xFF) == 0xE2 && file[i + 4] == 'M' && file[i + 5] == 'P'
                    && file[i + 6] == 'F' && file[i + 7] == 0) { mpf = i; break; }
        }
        assertTrue(mpf > 0);
        ByteBuffer entries = ByteBuffer.wrap(file, mpf + 8 + 50, 32).order(ByteOrder.LITTLE_ENDIAN);
        entries.getInt();
        int primaryLen = entries.getInt();
        entries.getInt();
        entries.getInt();
        entries.getInt();
        int gainLen = entries.getInt();
        int gainOffset = entries.getInt();
        assertEquals(file.length, primaryLen + gainLen);
        int gainStart = mpf + 8 + gainOffset;
        assertEquals(primaryLen, gainStart);
        assertEquals(0xFF, file[gainStart] & 0xFF);
        assertEquals(0xD8, file[gainStart + 1] & 0xFF);
        assertEquals(0xD9, file[primaryLen - 1] & 0xFF);
    }

    @Test
    public void findsExifSegment() throws IOException {
        byte[] exif = exifApp1();
        byte[] jpeg = withExifAfterSoi(baseline(100, false), exif);
        assertArrayEquals(exif, UltraHdrContainer.findExifSegment(jpeg));
        assertNull(UltraHdrContainer.findExifSegment(baseline(100, true)));
        assertNull(UltraHdrContainer.findExifSegment(new byte[]{1, 2, 3}));
    }

    @Test(expected = IOException.class)
    public void rejectsNonJpegPrimary() throws IOException {
        Patchable out = new Patchable();
        UltraHdrContainer.write(out, out::patch, o -> o.write(new byte[]{1, 2, 3, 4, 5, 6}), null,
                baseline(10, false), 0f, 1f, 1f);
    }

    @Test(expected = IOException.class)
    public void rejectsTruncatedHeader() throws IOException {
        byte[] sdr = baseline(10, true);
        byte[] cut = Arrays.copyOf(sdr, 40); // inside the ICC segment
        Patchable out = new Patchable();
        UltraHdrContainer.write(out, out::patch, o -> o.write(cut), null, baseline(10, false), 0f, 1f, 1f);
    }
}
