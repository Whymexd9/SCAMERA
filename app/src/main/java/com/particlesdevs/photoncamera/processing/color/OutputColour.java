package com.particlesdevs.photoncamera.processing.color;

import java.util.Locale;

/**
 * Colour of the saved photo (P46): «Цветовое пространство» sRGB (default) / Display P3, and the primaries, transfer and
 * matrix codes (ITU-T H.273 / CICP, the 'nclx' colour box of HEIF / AVIF) each output carries. Pure Java: the matrices are
 * derived here from the CIE xy chromaticities of the primaries and the D65 white (the same derivation as SMPTE RP 177),
 * so the GLSL constants of ark/combine.glsl and the tests can be checked against one source.
 *
 * <ul>
 *   <li>sRGB: BT.709 primaries (1), sRGB transfer (13); the photo as before, no profile in the file.</li>
 *   <li>Display P3: P3 primaries with the D65 white (SMPTE EG 432-1, code 12), sRGB transfer (13); the post pipeline
 *       renders the final image in P3 (ArkCombine, define P3_OUT) and every file says so (ICC profile, nclx 12/13).</li>
 *   <li>HDR (HLG): BT.2020 primaries (9), HLG transfer (ARIB STD-B67, 18), BT.2020 non-constant-luminance matrix (9);
 *       only the HDR HEIC / AVIF ({@link HlgRendition}).</li>
 * </ul>
 */
public final class OutputColour {
    private OutputColour() {}

    /** «Цветовое пространство» of the photo. */
    public enum Space {
        SRGB("srgb"), DISPLAY_P3("p3");

        /** The stored list value (pref_photo_color_space). */
        public final String value;

        Space(String value) {
            this.value = value;
        }

        /** The stored value; anything unknown (or null) is sRGB, the default. */
        public static Space parse(Object stored) {
            if (stored != null) {
                final String v = stored.toString().trim().toLowerCase(Locale.ROOT);
                for (Space s : values()) if (s.value.equals(v)) return s;
            }
            return SRGB;
        }
    }

    // ITU-T H.273 code points (the nclx box of HEIF / AVIF, the HEVC / AV1 VUI).
    public static final int PRIMARIES_BT709 = 1, PRIMARIES_BT2020 = 9, PRIMARIES_P3 = 12;
    public static final int TRANSFER_SRGB = 13, TRANSFER_PQ = 16, TRANSFER_HLG = 18;
    public static final int MATRIX_IDENTITY = 0, MATRIX_BT709 = 1, MATRIX_BT2020_NCL = 9;

    /**
     * What a HEIF / AVIF file says about its colour: the nclx (CICP) codes and an optional ICC profile. {@link #SRGB} is
     * what the files said before P46 (and still say by default): BT.709 / sRGB / BT.709, full range, no profile.
     */
    public static final class Signal {
        public final int primaries, transfer, matrix;
        public final boolean fullRange;
        /** ICC profile stored next to the nclx box (colr 'prof'), or null. */
        private final byte[] icc;

        public Signal(int primaries, int transfer, int matrix, boolean fullRange, byte[] icc) {
            this.primaries = primaries;
            this.transfer = transfer;
            this.matrix = matrix;
            this.fullRange = fullRange;
            this.icc = icc == null ? null : icc.clone();
        }

        /** The default: BT.709 primaries, sRGB transfer, BT.709 matrix, full range, no ICC profile. */
        public static final Signal SRGB = new Signal(PRIMARIES_BT709, TRANSFER_SRGB, MATRIX_BT709, true, null);
        /** HDR: BT.2020 primaries, HLG, BT.2020 non-constant-luminance matrix, full range (no ICC: it cannot describe HLG). */
        public static final Signal HLG = new Signal(PRIMARIES_BT2020, TRANSFER_HLG, MATRIX_BT2020_NCL, true, null);

        /** Display P3: P3 primaries (D65), sRGB transfer, BT.709 matrix (the encoders' own), full range, the P3 ICC profile. */
        public static Signal displayP3() {
            return new Signal(PRIMARIES_P3, TRANSFER_SRGB, MATRIX_BT709, true, IccProfiles.displayP3());
        }

        /** The signal of an SDR photo rendered in {@code space}. */
        public static Signal of(Space space) {
            return space == Space.DISPLAY_P3 ? displayP3() : SRGB;
        }

        public byte[] icc() {
            return icc == null ? null : icc.clone();
        }

        public boolean hasIcc() {
            return icc != null && icc.length > 0;
        }

        /** HLG or PQ transfer. */
        public boolean hdr() {
            return transfer == TRANSFER_HLG || transfer == TRANSFER_PQ;
        }

        /** Exactly the pre-P46 colour ({@link #SRGB}): the writers then take their old path. */
        public boolean isDefault() {
            return primaries == PRIMARIES_BT709 && transfer == TRANSFER_SRGB && matrix == MATRIX_BT709 && fullRange && !hasIcc();
        }

        @Override
        public String toString() {
            return "nclx " + primaries + "/" + transfer + "/" + matrix + (fullRange ? " full" : " limited") + (hasIcc() ? " + ICC " + icc.length + " B" : "");
        }
    }

    /** CIE 1931 xy of the D65 white of all three spaces. */
    static final double WHITE_X = 0.3127, WHITE_Y = 0.3290;
    /** xy of the red, green and blue primaries. */
    static final double[] SRGB_XY = {0.640, 0.330, 0.300, 0.600, 0.150, 0.060};
    static final double[] P3_XY = {0.680, 0.320, 0.265, 0.690, 0.150, 0.060};
    static final double[] BT2020_XY = {0.708, 0.292, 0.170, 0.797, 0.131, 0.046};

    /** Linear RGB -> CIE XYZ (row-major 3 x 3, Y of white = 1) of a space with these primaries and the D65 white. */
    public static double[] rgbToXyz(double[] xy) {
        final double[] p = new double[9]; // columns: X, Y, Z of each primary at Y = 1
        for (int i = 0; i < 3; i++) {
            final double x = xy[2 * i], y = xy[2 * i + 1];
            p[i] = x / y;
            p[3 + i] = 1.0;
            p[6 + i] = (1.0 - x - y) / y;
        }
        final double[] w = {WHITE_X / WHITE_Y, 1.0, (1.0 - WHITE_X - WHITE_Y) / WHITE_Y};
        final double[] s = multiply(invert(p), w);
        final double[] m = new double[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) m[3 * r + c] = p[3 * r + c] * s[c];
        return m;
    }

    /** Linear RGB of the source primaries -> linear RGB of the target primaries (row-major 3 x 3, white kept). */
    public static double[] convert(double[] fromXy, double[] toXy) {
        return multiply(invert(rgbToXyz(toXy)), rgbToXyz(fromXy));
    }

    public static double[] srgbToP3() {
        return convert(SRGB_XY, P3_XY);
    }

    public static double[] p3ToSrgb() {
        return convert(P3_XY, SRGB_XY);
    }

    public static double[] srgbToBt2020() {
        return convert(SRGB_XY, BT2020_XY);
    }

    public static double[] p3ToBt2020() {
        return convert(P3_XY, BT2020_XY);
    }

    /** Luminance weights (the Y row of RGB -> XYZ) of a space. */
    public static double[] luma(double[] xy) {
        final double[] m = rgbToXyz(xy);
        return new double[]{m[3], m[4], m[5]};
    }

    public static double[] lumaP3() {
        return luma(P3_XY);
    }

    public static double[] lumaBt2020() {
        return luma(BT2020_XY);
    }

    /** xy of the primaries of a space (P3 for DISPLAY_P3, BT.709 / sRGB otherwise). */
    public static double[] primaries(Space space) {
        return space == Space.DISPLAY_P3 ? P3_XY.clone() : SRGB_XY.clone();
    }

    /** H.273 colour primaries code of a space: 12 for Display P3, 1 for sRGB. */
    public static int primariesCode(Space space) {
        return space == Space.DISPLAY_P3 ? PRIMARIES_P3 : PRIMARIES_BT709;
    }

    // ---------------------------------------------------------------------------------------------- 3 x 3 algebra

    /** a (3 x 3) x b (3 x 3, or a 3-vector), row-major. */
    public static double[] multiply(double[] a, double[] b) {
        if (b.length == 3) {
            return new double[]{a[0] * b[0] + a[1] * b[1] + a[2] * b[2], a[3] * b[0] + a[4] * b[1] + a[5] * b[2],
                    a[6] * b[0] + a[7] * b[1] + a[8] * b[2]};
        }
        final double[] m = new double[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) m[3 * r + c] = a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c];
        return m;
    }

    public static double[] invert(double[] m) {
        final double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        final double co0 = e * i - f * h, co1 = f * g - d * i, co2 = d * h - e * g;
        final double det = a * co0 + b * co1 + c * co2;
        if (Math.abs(det) < 1e-12) throw new IllegalArgumentException("singular matrix");
        final double k = 1.0 / det;
        return new double[]{co0 * k, (c * h - b * i) * k, (b * f - c * e) * k,
                co1 * k, (a * i - c * g) * k, (c * d - a * f) * k,
                co2 * k, (b * g - a * h) * k, (a * e - b * d) * k};
    }

    public static float[] toFloat(double[] m) {
        final float[] out = new float[m.length];
        for (int i = 0; i < m.length; i++) out[i] = (float) m[i];
        return out;
    }

    // ---------------------------------------------------------------------------------------------- transfer

    /** sRGB EOTF (IEC 61966-2-1): encoded 0..1 -> linear 0..1. */
    public static double srgbToLinear(double v) {
        if (v <= 0.0) return 0.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** sRGB inverse EOTF: linear 0..1 -> encoded 0..1. */
    public static double linearToSrgb(double v) {
        if (v <= 0.0) return 0.0;
        return v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1.0 / 2.4) - 0.055;
    }
}
