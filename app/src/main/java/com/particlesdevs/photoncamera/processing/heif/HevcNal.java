package com.particlesdevs.photoncamera.processing.heif;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HEVC NAL units for the HEIF container of the 10-bit HEIC: the Annex-B byte stream of MediaCodec split into NAL units,
 * the 4-byte length-prefixed sample data of an image item (ISO/IEC 14496-15), the SPS fields the container needs and the
 * HEVCDecoderConfigurationRecord ('hvcC') built from the parameter sets, as MPEG4Writer / HeifWriter build it. Pure Java
 * (no Android types): tools/check_heic10.py compiles it on the host.
 */
public final class HevcNal {
    public static final int VPS = 32, SPS = 33, PPS = 34, AUD = 35, EOS = 36, EOB = 37, FILLER = 38, SEI_PREFIX = 39, SEI_SUFFIX = 40;

    private HevcNal() {}

    /** nal_unit_type of a NAL unit (its first header byte). */
    public static int type(byte[] nal) {
        return (nal[0] >> 1) & 0x3F;
    }

    /** Whether a NAL unit is a coded slice segment (VCL types 0-31). */
    public static boolean isSlice(byte[] nal) {
        return type(nal) < 32;
    }

    /**
     * The NAL units of an Annex-B byte stream (start codes 00 00 01 or 00 00 00 01), without start codes. Trailing zero
     * bytes are dropped (they belong to the next 4-byte start code or are trailing_zero_8bits: the last byte of a NAL
     * unit is never 0). Bytes before the first start code are ignored; no start code at all gives an empty list.
     */
    public static List<byte[]> split(byte[] data, int offset, int length) {
        final List<byte[]> out = new ArrayList<>();
        final int end = offset + length;
        int start = -1; // first byte of the current NAL unit
        int i = offset;
        while (i + 2 < end) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                if (start >= 0) add(out, data, start, i);
                i += 3;
                start = i;
            } else {
                i++;
            }
        }
        if (start >= 0) add(out, data, start, end);
        return out;
    }

    public static List<byte[]> split(byte[] data) {
        return split(data, 0, data.length);
    }

    private static void add(List<byte[]> out, byte[] data, int from, int to) {
        while (to > from && data[to - 1] == 0) to--;
        if (to - from >= 2) out.add(Arrays.copyOfRange(data, from, to));
    }

    /** Whether the NAL unit belongs in the sample data of an image item (not in hvcC, not a stream delimiter or filler). */
    public static boolean keepInSample(byte[] nal) {
        final int t = type(nal);
        return t != VPS && t != SPS && t != PPS && t != AUD && t != EOS && t != EOB && t != FILLER;
    }

    /**
     * Sample data of an image item: every kept NAL unit ({@link #keepInSample}) with a 4-byte big-endian length in front
     * (lengthSizeMinusOne = 3 in hvcC). The parameter sets are dropped: they live in hvcC.
     */
    public static byte[] toLengthPrefixed(List<byte[]> nals) {
        int size = 0;
        for (byte[] n : nals) if (keepInSample(n)) size += 4 + n.length;
        final byte[] out = new byte[size];
        int p = 0;
        for (byte[] n : nals) {
            if (!keepInSample(n)) continue;
            out[p] = (byte) (n.length >>> 24);
            out[p + 1] = (byte) (n.length >>> 16);
            out[p + 2] = (byte) (n.length >>> 8);
            out[p + 3] = (byte) n.length;
            System.arraycopy(n, 0, out, p + 4, n.length);
            p += 4 + n.length;
        }
        return out;
    }

    /** The NAL unit without its emulation prevention bytes (00 00 03 -> 00 00), header included. */
    static byte[] rbsp(byte[] nal) {
        final byte[] out = new byte[nal.length];
        int n = 0, zeros = 0;
        for (byte b : nal) {
            if (zeros >= 2 && b == 3) {
                zeros = 0;
                continue;
            }
            out[n++] = b;
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return Arrays.copyOf(out, n);
    }

    /** The SPS fields of the container: profile_tier_level, chroma format, size and bit depths. */
    public static final class Sps {
        public int profileSpace, tier, profileIdc, levelIdc;
        /** general_profile_compatibility_flags (32 bits). */
        public long compatibilityFlags;
        /** The 48 general constraint indicator bits (progressive_source_flag first). */
        public long constraintFlags;
        public int maxSubLayersMinus1;
        public boolean temporalIdNesting;
        public int chromaFormatIdc;
        /** Coded size (pic_width / pic_height_in_luma_samples) and the size after the conformance window. */
        public int codedWidth, codedHeight, width, height;
        public int bitDepthLuma, bitDepthChroma;

        @Override
        public String toString() {
            return "profile " + profileIdc + " tier " + tier + " level " + levelIdc + " chroma " + chromaFormatIdc + " "
                    + width + "x" + height + " (coded " + codedWidth + "x" + codedHeight + ") " + bitDepthLuma + "/" + bitDepthChroma + " bit";
        }
    }

    /** Parses the beginning of an SPS NAL unit (ITU-T H.265 7.3.2.2.1) up to the bit depths. */
    public static Sps parseSps(byte[] nal) {
        if (nal == null || nal.length < 4 || type(nal) != SPS) throw new IllegalArgumentException("not an SPS NAL unit");
        final Bits r = new Bits(rbsp(nal));
        r.skip(16); // NAL unit header
        final Sps s = new Sps();
        r.skip(4); // sps_video_parameter_set_id
        s.maxSubLayersMinus1 = r.u(3);
        s.temporalIdNesting = r.u(1) == 1;
        s.profileSpace = r.u(2);
        s.tier = r.u(1);
        s.profileIdc = r.u(5);
        s.compatibilityFlags = r.ul(32);
        s.constraintFlags = r.ul(48);
        s.levelIdc = r.u(8);
        final boolean[] profilePresent = new boolean[8], levelPresent = new boolean[8];
        for (int i = 0; i < s.maxSubLayersMinus1; i++) {
            profilePresent[i] = r.u(1) == 1;
            levelPresent[i] = r.u(1) == 1;
        }
        if (s.maxSubLayersMinus1 > 0) for (int i = s.maxSubLayersMinus1; i < 8; i++) r.skip(2);
        for (int i = 0; i < s.maxSubLayersMinus1; i++) {
            if (profilePresent[i]) r.skip(88);
            if (levelPresent[i]) r.skip(8);
        }
        r.ue(); // sps_seq_parameter_set_id
        s.chromaFormatIdc = r.ue();
        if (s.chromaFormatIdc > 3) throw new IllegalArgumentException("chroma_format_idc " + s.chromaFormatIdc);
        if (s.chromaFormatIdc == 3) r.skip(1); // separate_colour_plane_flag
        s.codedWidth = r.ue();
        s.codedHeight = r.ue();
        int left = 0, right = 0, top = 0, bottom = 0;
        if (r.u(1) == 1) {
            left = r.ue();
            right = r.ue();
            top = r.ue();
            bottom = r.ue();
        }
        final int subW = s.chromaFormatIdc == 1 || s.chromaFormatIdc == 2 ? 2 : 1, subH = s.chromaFormatIdc == 1 ? 2 : 1;
        s.width = s.codedWidth - subW * (left + right);
        s.height = s.codedHeight - subH * (top + bottom);
        s.bitDepthLuma = r.ue() + 8;
        s.bitDepthChroma = r.ue() + 8;
        if (s.width <= 0 || s.height <= 0 || s.bitDepthLuma > 16 || s.bitDepthChroma > 16)
            throw new IllegalArgumentException("malformed SPS: " + s);
        return s;
    }

    /**
     * HEVCDecoderConfigurationRecord (ISO/IEC 14496-15 8.3.3.1) with one complete array per parameter-set type in the
     * order VPS, SPS, PPS. Profile, tier, level, chroma format and bit depths come from the first SPS;
     * min_spatial_segmentation_idc, parallelismType and avgFrameRate are 0 (unknown), lengthSizeMinusOne is 3.
     */
    public static byte[] hvcC(List<byte[]> vps, List<byte[]> sps, List<byte[]> pps) {
        if (vps.isEmpty() || sps.isEmpty() || pps.isEmpty()) throw new IllegalArgumentException("hvcC needs a VPS, an SPS and a PPS");
        final Sps s = parseSps(sps.get(0));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1); // configurationVersion
        out.write((s.profileSpace << 6) | (s.tier << 5) | s.profileIdc);
        for (int i = 3; i >= 0; i--) out.write((int) (s.compatibilityFlags >>> (8 * i)));
        for (int i = 5; i >= 0; i--) out.write((int) (s.constraintFlags >>> (8 * i)));
        out.write(s.levelIdc);
        out.write(0xF0); // reserved 1111 + min_spatial_segmentation_idc (12 bits) = 0
        out.write(0x00);
        out.write(0xFC); // reserved 111111 + parallelismType 0
        out.write(0xFC | s.chromaFormatIdc);
        out.write(0xF8 | (s.bitDepthLuma - 8));
        out.write(0xF8 | (s.bitDepthChroma - 8));
        out.write(0); // avgFrameRate
        out.write(0);
        // constantFrameRate 0, numTemporalLayers, temporalIdNested, lengthSizeMinusOne 3
        out.write(((s.maxSubLayersMinus1 + 1) << 3) | (s.temporalIdNesting ? 4 : 0) | 3);
        out.write(3); // numOfArrays
        array(out, VPS, vps);
        array(out, SPS, sps);
        array(out, PPS, pps);
        return out.toByteArray();
    }

    private static void array(ByteArrayOutputStream out, int type, List<byte[]> nals) {
        out.write(0x80 | type); // array_completeness 1: every parameter set of this type is here, none in the samples
        out.write(nals.size() >>> 8);
        out.write(nals.size());
        for (byte[] n : nals) {
            if (n.length > 0xFFFF) throw new IllegalArgumentException("parameter set of " + n.length + " bytes");
            out.write(n.length >>> 8);
            out.write(n.length);
            out.write(n, 0, n.length);
        }
    }

    /** Parameter sets and samples collected from an encoder's output, in arrival order. */
    public static final class ParameterSets {
        public final List<byte[]> vps = new ArrayList<>(), sps = new ArrayList<>(), pps = new ArrayList<>();

        /** Keeps every VPS / SPS / PPS of {@code nals} not seen yet (byte-equal duplicates are skipped). */
        public void collect(List<byte[]> nals) {
            for (byte[] n : nals) {
                final int t = type(n);
                final List<byte[]> into = t == VPS ? vps : t == SPS ? sps : t == PPS ? pps : null;
                if (into == null) continue;
                boolean known = false;
                for (byte[] k : into) known |= Arrays.equals(k, n);
                if (!known) into.add(n);
            }
        }

        public boolean complete() {
            return !vps.isEmpty() && !sps.isEmpty() && !pps.isEmpty();
        }

        public byte[] hvcC() {
            return HevcNal.hvcC(vps, sps, pps);
        }
    }

    /** MSB-first bit reader over an RBSP. */
    static final class Bits {
        private final byte[] data;
        private long pos;

        Bits(byte[] data) {
            this.data = data;
        }

        int u(int n) {
            return (int) ul(n);
        }

        long ul(int n) {
            long v = 0;
            for (int i = 0; i < n; i++) {
                final int index = (int) (pos >>> 3);
                if (index >= data.length) throw new IllegalArgumentException("SPS ends early");
                v = (v << 1) | ((data[index] >> (7 - (int) (pos & 7))) & 1);
                pos++;
            }
            return v;
        }

        void skip(int n) {
            pos += n;
            if ((pos + 7) >>> 3 > data.length) throw new IllegalArgumentException("SPS ends early");
        }

        int ue() {
            int zeros = 0;
            while (u(1) == 0) {
                if (++zeros > 31) throw new IllegalArgumentException("bad Exp-Golomb code");
            }
            return (int) ((1L << zeros) - 1 + ul(zeros));
        }
    }
}
