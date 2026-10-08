package com.particlesdevs.photoncamera.processing.heif;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Annex-B parsing, the length-prefixed sample of an image item, the SPS fields and the hvcC built from them. */
public class HevcNalTest {
    /** MSB-first bit writer for synthetic SPS NAL units (emulation prevention added by {@link #nal}). */
    static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int cur, n;

        BitWriter u(long v, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                cur = (cur << 1) | (int) ((v >>> i) & 1);
                if (++n == 8) {
                    out.write(cur);
                    cur = 0;
                    n = 0;
                }
            }
            return this;
        }

        BitWriter ue(int v) {
            final long x = v + 1L;
            final int len = 64 - Long.numberOfLeadingZeros(x);
            u(0, len - 1);
            return u(x, len);
        }

        /** rbsp_trailing_bits and the bytes. */
        byte[] rbsp() {
            u(1, 1);
            while (n != 0) u(0, 1);
            return out.toByteArray();
        }
    }

    /** Inserts emulation prevention bytes (00 00 0x with x <= 3 -> 00 00 03 0x). */
    static byte[] nal(byte[] rbsp) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int zeros = 0;
        for (byte b : rbsp) {
            if (zeros >= 2 && (b & 0xFF) <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    /** A Main10-style SPS: profile / level / sub-layers / chroma / size / conformance window / bit depths as given. */
    static byte[] sps(int maxSubLayersMinus1, int profile, int level, int chroma, int width, int height, int[] window,
                      int lumaBits, int chromaBits) {
        final BitWriter b = new BitWriter();
        b.u(0x4201, 16); // NAL header: type 33 (SPS), layer 0, temporal id 0
        b.u(0, 4).u(maxSubLayersMinus1, 3).u(1, 1); // vps id, max_sub_layers_minus1, temporal_id_nesting
        b.u(0, 2).u(0, 1).u(profile, 5); // general profile space, tier, idc
        b.u(1L << (31 - profile), 32); // compatibility flag of the profile
        b.u(0x900000000000L, 48); // progressive_source + frame_only_constraint
        b.u(level, 8);
        for (int i = 0; i < maxSubLayersMinus1; i++) b.u(0, 1).u(1, 1); // sub-layer: no profile, a level
        if (maxSubLayersMinus1 > 0) for (int i = maxSubLayersMinus1; i < 8; i++) b.u(0, 2);
        for (int i = 0; i < maxSubLayersMinus1; i++) b.u(level, 8);
        b.ue(0).ue(chroma);
        if (chroma == 3) b.u(0, 1);
        b.ue(width).ue(height);
        if (window != null) {
            b.u(1, 1);
            for (int w : window) b.ue(w);
        } else {
            b.u(0, 1);
        }
        b.ue(lumaBits - 8).ue(chromaBits - 8);
        b.ue(4); // log2_max_pic_order_cnt_lsb_minus4 (the rest of the SPS is not read)
        return nal(b.rbsp());
    }

    private static byte[] cat(byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static final byte[] SC4 = {0, 0, 0, 1}, SC3 = {0, 0, 1};
    private static final byte[] VPS = {0x40, 0x01, 0x0C, 0x01, (byte) 0xFF, (byte) 0xFF};
    private static final byte[] PPS = {0x44, 0x01, (byte) 0xC1, 0x72, (byte) 0xB0, 0x62, 0x40};
    private static final byte[] IDR = {0x26, 0x01, (byte) 0xAF, 0x00, 0x00, 0x03, 0x01, 0x10, 0x20};
    private static final byte[] SEI = {0x4E, 0x01, 0x05, 0x10, 0x00, (byte) 0x80};
    private static final byte[] AUD = {0x46, 0x01, 0x50};

    @Test
    public void annexBSplitsOnThreeAndFourByteStartCodes() {
        final byte[] stream = cat(new byte[]{9, 9}, SC4, VPS, SC3, PPS, new byte[]{0, 0}, SC4, IDR, new byte[]{0});
        final List<byte[]> nals = HevcNal.split(stream);
        assertEquals(3, nals.size());
        assertArrayEquals(VPS, nals.get(0));
        assertArrayEquals("trailing zero bytes before a 4-byte start code are not part of the NAL unit", PPS, nals.get(1));
        assertArrayEquals("emulation prevention (00 00 03) stays in the NAL unit", IDR, nals.get(2));
        assertEquals(HevcNal.VPS, HevcNal.type(nals.get(0)));
        assertEquals(HevcNal.PPS, HevcNal.type(nals.get(1)));
        assertTrue(HevcNal.isSlice(nals.get(2)));
        assertTrue("no start code: nothing", HevcNal.split(new byte[]{1, 2, 3, 4}).isEmpty());
    }

    @Test
    public void sampleIsLengthPrefixedWithoutParameterSets() {
        final byte[] sps = sps(0, 2, 93, 1, 512, 512, null, 10, 10);
        final List<byte[]> nals = HevcNal.split(cat(SC4, AUD, SC4, VPS, SC4, sps, SC4, PPS, SC3, SEI, SC4, IDR));
        final byte[] sample = HevcNal.toLengthPrefixed(nals);
        final byte[] expected = cat(new byte[]{0, 0, 0, (byte) SEI.length}, SEI, new byte[]{0, 0, 0, (byte) IDR.length}, IDR);
        assertArrayEquals(expected, sample);
    }

    @Test
    public void spsFieldsOfAMain10Tile() {
        final byte[] nal = sps(0, 2, 93, 1, 512, 512, null, 10, 10);
        final HevcNal.Sps s = HevcNal.parseSps(nal);
        assertEquals(2, s.profileIdc);
        assertEquals(0, s.tier);
        assertEquals(93, s.levelIdc);
        assertEquals(1L << 29, s.compatibilityFlags);
        assertEquals(0x900000000000L, s.constraintFlags);
        assertEquals(1, s.chromaFormatIdc);
        assertEquals(512, s.width);
        assertEquals(512, s.height);
        assertEquals(10, s.bitDepthLuma);
        assertEquals(10, s.bitDepthChroma);
        assertTrue(s.temporalIdNesting);
    }

    @Test
    public void spsWithSubLayersConformanceWindowAndEmulationPrevention() {
        // 1088 coded rows cropped to 1080 (bottom offset 4 in chroma units), two sub-layers, an 8-bit stream.
        final byte[] nal = sps(1, 1, 120, 1, 1920, 1088, new int[]{0, 0, 0, 4}, 8, 8);
        final HevcNal.Sps s = HevcNal.parseSps(nal);
        assertEquals(1, s.maxSubLayersMinus1);
        assertEquals(1, s.profileIdc);
        assertEquals(120, s.levelIdc);
        assertEquals(1920, s.width);
        assertEquals(1080, s.height);
        assertEquals(1088, s.codedHeight);
        assertEquals(8, s.bitDepthLuma);
        // the constraint flags hold 00 00 sequences: the NAL unit carries emulation prevention bytes the parser removes
        boolean prevented = false;
        for (int i = 2; i < nal.length; i++) prevented |= nal[i - 2] == 0 && nal[i - 1] == 0 && nal[i] == 3;
        assertTrue(prevented);
    }

    @Test
    public void realX265Sps() {
        // SPS of a 512x512 10-bit 4:2:0 tile coded by x265 through libheif (tools/check_heic10.py): format range extensions
        // profile, level 3 (90).
        final byte[] nal = hex("4201010408000003009db800000300005aa0040200804d96ea4929ae6e021a020800000300c8000003000840");
        final HevcNal.Sps s = HevcNal.parseSps(nal);
        assertEquals(4, s.profileIdc);
        assertEquals(90, s.levelIdc);
        assertEquals(1, s.chromaFormatIdc);
        assertEquals(512, s.width);
        assertEquals(512, s.height);
        assertEquals(10, s.bitDepthLuma);
        assertEquals(10, s.bitDepthChroma);
    }

    @Test
    public void hvcCRecord() {
        final byte[] sps = sps(0, 2, 93, 1, 512, 512, null, 10, 10);
        final byte[] hvcC = HevcNal.hvcC(Collections.singletonList(VPS), Collections.singletonList(sps), Collections.singletonList(PPS));
        assertEquals(1, hvcC[0]); // configurationVersion
        assertEquals(2, hvcC[1]); // space 0, tier 0, profile 2 (Main10)
        assertArrayEquals(new byte[]{0x20, 0, 0, 0}, Arrays.copyOfRange(hvcC, 2, 6)); // compatibility: Main10 bit
        assertArrayEquals(new byte[]{(byte) 0x90, 0, 0, 0, 0, 0}, Arrays.copyOfRange(hvcC, 6, 12));
        assertEquals(93, hvcC[12] & 0xFF);
        assertEquals(0xF0, hvcC[13] & 0xFF); // min_spatial_segmentation_idc 0
        assertEquals(0x00, hvcC[14] & 0xFF);
        assertEquals(0xFC, hvcC[15] & 0xFF); // parallelismType 0
        assertEquals(0xFD, hvcC[16] & 0xFF); // chroma 4:2:0
        assertEquals(0xFA, hvcC[17] & 0xFF); // luma 10 bits
        assertEquals(0xFA, hvcC[18] & 0xFF); // chroma 10 bits
        assertEquals(0x0F, hvcC[21] & 0xFF); // 1 temporal layer, nested, lengthSizeMinusOne 3
        assertEquals(3, hvcC[22]); // arrays
        int p = 23;
        for (byte[] nal : new byte[][]{VPS, sps, PPS}) {
            assertEquals(0x80 | HevcNal.type(nal), hvcC[p] & 0xFF); // complete array of this type
            assertEquals(1, ((hvcC[p + 1] & 0xFF) << 8) | (hvcC[p + 2] & 0xFF));
            assertEquals(nal.length, ((hvcC[p + 3] & 0xFF) << 8) | (hvcC[p + 4] & 0xFF));
            assertArrayEquals(nal, Arrays.copyOfRange(hvcC, p + 5, p + 5 + nal.length));
            p += 5 + nal.length;
        }
        assertEquals(hvcC.length, p);
    }

    @Test
    public void parameterSetsCollectedOnceFromConfigAndInBand() {
        final byte[] sps = sps(0, 2, 93, 1, 512, 512, null, 10, 10);
        final HevcNal.ParameterSets sets = new HevcNal.ParameterSets();
        assertFalse(sets.complete());
        sets.collect(HevcNal.split(cat(SC4, VPS, SC4, sps, SC4, PPS)));
        sets.collect(HevcNal.split(cat(SC4, VPS, SC4, sps, SC4, PPS, SC4, IDR))); // repeated in front of an IDR
        assertTrue(sets.complete());
        assertEquals(1, sets.vps.size());
        assertEquals(1, sets.sps.size());
        assertEquals(1, sets.pps.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void notAnSps() {
        HevcNal.parseSps(PPS);
    }

    static byte[] hex(String s) {
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
