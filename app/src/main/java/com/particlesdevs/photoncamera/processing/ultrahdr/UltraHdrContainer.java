package com.particlesdevs.photoncamera.processing.ultrahdr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Assembles a spec-compliant Ultra HDR (JPEG/R, ISO 21496-1) file from an SDR
 * baseline JPEG and a gain-map JPEG, both produced in Java:
 *
 * <ul>
 *   <li>APP1 XMP GContainer packet (primary image) referencing the GainMap</li>
 *   <li>APP2 MPF (Multi-Picture Format) pointing at the gain-map secondary image</li>
 *   <li>the gain-map JPEG appended after the primary, with its own XMP metadata</li>
 * </ul>
 *
 * No external dependencies - pure byte manipulation.
 *
 * <p>The MPF {@code MPEntry} offsets follow the CIPA DC-007 / libultrahdr
 * convention: they are measured relative to the MPF base (the byte just after
 * the {@code MPF\0} signature in the APP2 segment), not from the file start.
 * The gain-map entry's offset is therefore {@code gainMapSoidAbs - mpfBaseAbs};
 * measuring it from the primary SOI instead points the decoder at the wrong
 * bytes and makes the file unparseable (the gain map appears as raw garbage).
 */
public final class UltraHdrContainer {

    private static final byte[] XMP_IDENTIFIER =
            "http://ns.adobe.com/xap/1.0/\u0000".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MPF_IDENTIFIER = {'M', 'P', 'F', 0};

    private UltraHdrContainer() {}

    /**
     * @param sdrJpeg        baseline SDR JPEG bytes (no Ultra HDR metadata yet)
     * @param gainMapJpeg    baseline gain-map JPEG bytes (will get XMP prepended)
     * @param gainMapMin     gain-map min (base-2 log boost)
     * @param gainMapMax     gain-map max (base-2 log boost)
     * @param hdrCapacityMax max content boost (log2), equals gainMapMax
     * @return assembled Ultra HDR JPEG bytes
     */
    public static byte[] encode(byte[] sdrJpeg, byte[] gainMapJpeg,
                                float gainMapMin, float gainMapMax, float hdrCapacityMax) {
        // Gain-map image XMP must be assembled first so the primary GContainer can
        // reference the gain-map image's full (XMP-inclusive) length.
        final byte[] gainMapJpegXmp = gainMapImage(gainMapJpeg, gainMapMin, gainMapMax, hdrCapacityMax);
        final byte[] xmpApp1 = primaryXmpSegment(gainMapJpegXmp.length);

        // Locate insertion point: right before the first non APPn/COM marker.
        int insertPos = 2; // skip SOI
        while (insertPos + 4 <= sdrJpeg.length) {
            if ((sdrJpeg[insertPos] & 0xFF) != 0xFF) break;
            final int marker = sdrJpeg[insertPos + 1] & 0xFF;
            if ((marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE) {
                final int len = ((sdrJpeg[insertPos + 2] & 0xFF) << 8) | (sdrJpeg[insertPos + 3] & 0xFF);
                insertPos += 2 + len;
            } else {
                break;
            }
        }

        // Layout: [sdrJpeg 0..insertPos][xmpApp1][mpfApp2][sdrJpeg insertPos..end][gainMapJpegXmp]
        final int mpfApp2Len = MPF_APP2_LENGTH;
        // The gain-map SOI sits right after the primary image (which ends at its EOI).
        final int gainMapSoidAbs = xmpApp1.length + mpfApp2Len + sdrJpeg.length; // absolute gain-map SOI
        // MPF base = position right after the 'MPF\0' signature (FFE2_start + 8).
        final int mpfBaseAbs = insertPos + xmpApp1.length + 8;
        // MPF primary size must be the primary image size (up to the gain-map SOI), not the whole file.
        final int primaryLen = gainMapSoidAbs;
        // MPF gain-map offset is relative to the MPF base (per CIPA DC-007 / libultrahdr), not absolute.
        final int gainMapOffset = gainMapSoidAbs - mpfBaseAbs;
        final int gainMapLen = gainMapJpegXmp.length;

        final byte[] mpfApp2 = buildMpf(primaryLen, gainMapLen, gainMapOffset);

        final ByteArrayOutputStream out = new ByteArrayOutputStream(primaryLen + gainMapLen);
        out.write(sdrJpeg, 0, insertPos);
        out.write(xmpApp1, 0, xmpApp1.length);
        out.write(mpfApp2, 0, mpfApp2.length);
        out.write(sdrJpeg, insertPos, sdrJpeg.length - insertPos);
        out.write(gainMapJpegXmp, 0, gainMapJpegXmp.length);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------------
    // Streaming assembly (the primary JPEG is never held in memory)
    // ------------------------------------------------------------------------

    /** Size of the MPF APP2 segment: FF E2 + length(2) + MPF payload(86). */
    public static final int MPF_APP2_LENGTH = 90;

    /** Writes the baseline primary JPEG (e.g. {@code Bitmap.compress}) into the given stream. */
    public interface PrimaryEncoder {
        void encode(OutputStream out) throws IOException;
    }

    /** Overwrites already written bytes at an absolute offset of the output. */
    public interface Patcher {
        void patch(long offset, byte[] bytes) throws IOException;
    }

    /** The gain-map image as it is appended after the primary: baseline JPEG with its hdrgm XMP APP1 after SOI. */
    public static byte[] gainMapImage(byte[] gainMapJpeg, float gainMapMin, float gainMapMax, float hdrCapacityMax) {
        return prependApp1(gainMapJpeg, buildApp1(buildGainMapXmp(gainMapMin, gainMapMax, hdrCapacityMax)));
    }

    /** Primary XMP APP1 (GContainer directory) referencing a gain-map image of {@code gainMapImageLength} bytes. */
    public static byte[] primaryXmpSegment(int gainMapImageLength) {
        return buildApp1(buildPrimaryXmp(gainMapImageLength));
    }

    /**
     * MPF APP2 of a primary image of {@code primaryLength} bytes whose MPF base (the byte after the MPF
     * signature) sits at {@code mpfBase}; the gain map starts right after the primary.
     */
    public static byte[] mpfSegment(long primaryLength, int gainMapLength, long mpfBase) {
        if (primaryLength > Integer.MAX_VALUE) throw new IllegalArgumentException("primary image too large for MPF");
        return buildMpf((int) primaryLength, gainMapLength, (int) (primaryLength - mpfBase));
    }

    /**
     * Streams a complete Ultra HDR file: the primary is encoded straight into {@code out} through a
     * {@link PrimaryWriter} (EXIF / XMP / MPF placed while it is written), the gain-map image follows, and the
     * MPF segment is patched once the primary length is known. Produces the same bytes as {@link #encode} applied
     * to the same baseline with {@code exifApp1} placed right after its SOI (where ExifInterface puts it), without
     * ever holding the primary JPEG in memory.
     *
     * @param exifApp1 complete EXIF APP1 segment (FF E1 ...) or null
     * @return total number of bytes written
     */
    public static long write(OutputStream out, Patcher patcher, PrimaryEncoder primary, byte[] exifApp1,
                             byte[] gainMapJpeg, float gainMapMin, float gainMapMax, float hdrCapacityMax)
            throws IOException {
        final byte[] gainImage = gainMapImage(gainMapJpeg, gainMapMin, gainMapMax, hdrCapacityMax);
        final PrimaryWriter writer = new PrimaryWriter(out, exifApp1, primaryXmpSegment(gainImage.length));
        primary.encode(writer);
        writer.finish();
        final long primaryLength = writer.written();
        out.write(gainImage);
        out.flush();
        patcher.patch(writer.mpfOffset(), mpfSegment(primaryLength, gainImage.length, writer.mpfOffset() + 8));
        return primaryLength + gainImage.length;
    }

    /**
     * Returns a copy of the first EXIF APP1 segment (FF E1, length, "Exif" 0 0, TIFF data) of a JPEG, or null
     * when there is none before the image data.
     */
    public static byte[] findExifSegment(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return null;
        int pos = 2;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
            final int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) break; // SOS / EOI: no metadata after this point
            final int len = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (len < 2 || pos + 2 + len > jpeg.length) break;
            if (marker == 0xE1 && len >= 8 && jpeg[pos + 4] == 'E' && jpeg[pos + 5] == 'x' && jpeg[pos + 6] == 'i'
                    && jpeg[pos + 7] == 'f' && jpeg[pos + 8] == 0 && jpeg[pos + 9] == 0) {
                return Arrays.copyOfRange(jpeg, pos, pos + 2 + len);
            }
            pos += 2 + len;
        }
        return null;
    }

    /**
     * Lays out a baseline JPEG as the Ultra HDR primary while the encoder writes it: SOI, the optional EXIF APP1,
     * the encoder's own APPn/COM segments (JFIF, ICC), the primary XMP APP1 and a placeholder MPF APP2 right before
     * the first other marker (the insertion point of {@link #encode}), then the rest of the image unchanged. Only
     * the leading metadata segments are buffered.
     */
    public static final class PrimaryWriter extends OutputStream {
        private static final int MAX_HEAD = 1 << 20;
        /** Largest piece of one write that is buffered before the insertion point is known. */
        private static final int HEAD_STEP = 16 * 1024;
        private final OutputStream out;
        private final byte[] exifApp1;
        private final byte[] xmpApp1;
        private byte[] head = new byte[8192];
        private int headLength;
        private boolean streaming;
        private long written;
        private long mpfOffset = -1;

        public PrimaryWriter(OutputStream out, byte[] exifApp1, byte[] xmpApp1) {
            this.out = out;
            this.exifApp1 = exifApp1;
            this.xmpApp1 = xmpApp1;
        }

        /** Bytes of the primary image written so far (all of it after {@link #finish}). */
        public long written() {
            return written;
        }

        /** Absolute offset of the MPF APP2 placeholder; valid after {@link #finish}. */
        public long mpfOffset() {
            return mpfOffset;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            // Before the insertion point is known the bytes are buffered in small steps: a caller writing a large
            // chunk (a whole pre-encoded JPEG in one call) must neither be copied into the head nor trip MAX_HEAD,
            // which bounds the metadata, not the write size.
            while (len > 0 && !streaming) {
                final int n = Math.min(len, HEAD_STEP);
                appendHead(b, off, n);
                off += n;
                len -= n;
            }
            if (len > 0) {
                out.write(b, off, len);
                written += len;
            }
        }

        private void appendHead(byte[] b, int off, int len) throws IOException {
            if (headLength + len > head.length) {
                if (headLength + len > MAX_HEAD) throw new IOException("JPEG metadata header larger than 1 MB");
                head = Arrays.copyOf(head, Math.min(MAX_HEAD, Math.max(head.length * 2, headLength + len)));
            }
            System.arraycopy(b, off, head, headLength, len);
            headLength += len;
            final int insert = insertionPoint(false);
            if (insert >= 0) emitHead(insert);
        }

        /** Completes the primary image; the encoder must have written all of it. */
        public void finish() throws IOException {
            if (!streaming) {
                final int insert = insertionPoint(true);
                if (insert < 0) throw new IOException("truncated JPEG header");
                emitHead(insert);
            }
            out.flush();
        }

        @Override
        public void flush() {
            // The encoder flushing must not force the buffered head out early; finish() flushes.
        }

        @Override
        public void close() {
            // The underlying stream stays open: the gain map and the MPF patch follow.
        }

        /** Position before the first non-APPn/COM marker, or -1 when more bytes are needed to tell. */
        private int insertionPoint(boolean complete) throws IOException {
            if (headLength < 2) return -1;
            if ((head[0] & 0xFF) != 0xFF || (head[1] & 0xFF) != 0xD8) throw new IOException("primary image is not a JPEG");
            int pos = 2;
            while (pos + 4 <= headLength) {
                if ((head[pos] & 0xFF) != 0xFF) return pos;
                final int marker = head[pos + 1] & 0xFF;
                if ((marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE) {
                    final int len = ((head[pos + 2] & 0xFF) << 8) | (head[pos + 3] & 0xFF);
                    pos += 2 + len;
                } else {
                    return pos;
                }
            }
            // Same rule as encode() at the end of the data; mid-stream, wait for more bytes.
            return complete && pos <= headLength ? pos : -1;
        }

        private void emitHead(int insert) throws IOException {
            writeRaw(head, 0, 2);
            if (exifApp1 != null) writeRaw(exifApp1, 0, exifApp1.length);
            writeRaw(head, 2, insert - 2);
            writeRaw(xmpApp1, 0, xmpApp1.length);
            mpfOffset = written;
            // Placeholder of the final size: the real entries need the primary length (patched afterwards).
            final byte[] placeholder = buildMpf(0, 0, 0);
            writeRaw(placeholder, 0, placeholder.length);
            writeRaw(head, insert, headLength - insert);
            head = null;
            headLength = 0;
            streaming = true;
        }

        private void writeRaw(byte[] b, int off, int len) throws IOException {
            if (len <= 0) return;
            out.write(b, off, len);
            written += len;
        }
    }

    // ------------------------------------------------------------------------
    // XMP packets
    // ------------------------------------------------------------------------

    private static byte[] buildPrimaryXmp(int gainMapLen) {
        final String xml = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"PhotonCamera\">\n"
                + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "  <rdf:Description rdf:about=\"\"\n"
                + "    xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n"
                + "    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n"
                + "    xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"\n"
                + "    hdrgm:Version=\"1.0\">\n"
                + "   <Container:Directory>\n"
                + "    <rdf:Seq>\n"
                + "     <rdf:li rdf:parseType=\"Resource\">\n"
                + "      <Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/>\n"
                + "     </rdf:li>\n"
                + "     <rdf:li rdf:parseType=\"Resource\">\n"
                + "      <Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\""
                + gainMapLen + "\"/>\n"
                + "     </rdf:li>\n"
                + "    </rdf:Seq>\n"
                + "   </Container:Directory>\n"
                + "  </rdf:Description>\n"
                + " </rdf:RDF>\n"
                + "</x:xmpmeta>\n"
                + "<?xpacket end=\"w\"?>\n";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buildGainMapXmp(float gMin, float gMax, float hdrCap) {
        final String xml = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"PhotonCamera\">\n"
                + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "  <rdf:Description rdf:about=\"\"\n"
                + "    xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"\n"
                + "    hdrgm:Version=\"1.0\"\n"
                + "    hdrgm:GainMapMin=\"" + fmt(gMin) + "\"\n"
                + "    hdrgm:GainMapMax=\"" + fmt(gMax) + "\"\n"
                + "    hdrgm:Gamma=\"1\"\n"
                + "    hdrgm:OffsetSDR=\"" + fmt(GainMapComputer.DECODE_OFFSET) + "\"\n"
                + "    hdrgm:OffsetHDR=\"" + fmt(GainMapComputer.DECODE_OFFSET) + "\"\n"
                + "    hdrgm:HDRCapacityMin=\"0\"\n"
                + "    hdrgm:HDRCapacityMax=\"" + fmt(hdrCap) + "\"\n"
                + "    hdrgm:BaseRenditionIsHDR=\"False\"/>\n"
                + " </rdf:RDF>\n"
                + "</x:xmpmeta>\n"
                + "<?xpacket end=\"w\"?>\n";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static String fmt(float v) {
        if (!Float.isFinite(v)) v = 0f;
        // Fixed decimal: %.6g can emit scientific notation ("1.2345e-05"),
        // which some XMP consumers fail to parse.
        return String.format(java.util.Locale.US, "%.6f", v);
    }

    // ------------------------------------------------------------------------
    // APP1 wrapper (XMP)
    // ------------------------------------------------------------------------

    private static byte[] buildApp1(byte[] packet) {
        int total = 2 + XMP_IDENTIFIER.length + packet.length;
        byte[] padded = packet;
        if (total % 2 != 0) {
            padded = new byte[packet.length + 1];
            System.arraycopy(packet, 0, padded, 0, packet.length);
            padded[packet.length] = (byte) ' ';
            total++;
        }
        final ByteBuffer bb = ByteBuffer.allocate(total + 2).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) 0xFF);
        bb.put((byte) 0xE1);
        bb.putShort((short) (2 + XMP_IDENTIFIER.length + padded.length));
        bb.put(XMP_IDENTIFIER);
        bb.put(padded);
        return bb.array();
    }

    private static byte[] prependApp1(byte[] jpeg, byte[] app1) {
        final ByteBuffer bb = ByteBuffer.allocate(jpeg.length + app1.length).order(ByteOrder.BIG_ENDIAN);
        bb.put(jpeg, 0, 2); // SOI
        bb.put(app1);
        bb.put(jpeg, 2, jpeg.length - 2);
        return bb.array();
    }

    // ------------------------------------------------------------------------
    // APP2 MPF (Multi-Picture Format)
    // ------------------------------------------------------------------------

    private static byte[] buildMpf(int primaryLen, int gainMapLen, int dataOffset) {
        // Fixed layout: MPF\0(4) + II(2) + 42(2) + ifdOffset(4) + IFD(42) + MPEntry(32) = 86.
        final ByteBuffer data = ByteBuffer.allocate(86).order(ByteOrder.LITTLE_ENDIAN);
        data.put(MPF_IDENTIFIER);                 // 4
        data.put((byte) 0x49); data.put((byte) 0x49); // "II"
        data.putShort((short) 0x2A);
        data.putInt(8);                           // IFD offset (from "II")
        // IFD
        data.putShort((short) 3);                 // entry count
        // Entry 1: MPFVersion (0xB000), UNDEFINED, 4 bytes "0100" (CIPA DC-007 5.2.3.1, as libultrahdr writes it). It was
        // ASCII (2): Skia's MPF parser (Android 14+ decoders) requires UNDEFINED and rejected the whole MPF directory, so
        // those decoders only found the gain map through the GContainer XMP fallback.
        data.putShort((short) 0xB000);
        data.putShort((short) 0x0007);
        data.putInt(4);
        data.put((byte) '0'); data.put((byte) '1'); data.put((byte) '0'); data.put((byte) '0');
        // Entry 2: NumberOfImages (0xB001), LONG = 2
        data.putShort((short) 0xB001);
        data.putShort((short) 0x0004);
        data.putInt(1);
        data.putInt(2);
        // Entry 3: MPEntry (0xB002), UNDEFINED, 32 bytes, offset = 50 (from "II")
        data.putShort((short) 0xB002);
        data.putShort((short) 0x0007);
        data.putInt(32);
        data.putInt(50);
        // Next IFD offset
        data.putInt(0);
        // MPEntry data (2 x 16 bytes), per libultrahdr / CIPA DC-007. The first image's offset is 0 (it starts at the file
        // start); every other offset is relative to the MPF base (the byte after "MPF\0"), see the class comment.
        // Primary image: JPEG format (0x00000000) | primary type (0x030000).
        data.putInt(0x00030000);
        data.putInt(primaryLen);
        data.putInt(0);            // data offset (file start)
        data.putInt(0);            // dependence (none)
        // Gain map image: JPEG format only (0x00000000).
        data.putInt(0x00000000);
        data.putInt(gainMapLen);
        data.putInt(dataOffset);
        data.putInt(0);            // dependence (none)

        final byte[] d = data.array();
        final ByteBuffer seg = ByteBuffer.allocate(2 + 2 + d.length).order(ByteOrder.BIG_ENDIAN);
        seg.put((byte) 0xFF);
        seg.put((byte) 0xE2);
        seg.putShort((short) (2 + d.length));
        seg.put(d);
        return seg.array();
    }
}
