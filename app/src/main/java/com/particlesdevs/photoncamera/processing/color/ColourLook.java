package com.particlesdevs.photoncamera.processing.color;

/**
 * P134 «Цвет»: the colour look of the photo on top of the WHY tone (owner: «добавь нам настройки цвета ... не дублируй ...
 * можешь сделать свои варианты»). Two places in the pipeline:
 * <ul>
 * <li>a 3x3 matrix on linear sRGB after the camera matrix (warmth, tint, the scene light kept): {@link #whiteMatrix}, folded
 * into WhyTone's colour chain, so the AE statistics and the photo see the same colour and it costs no GPU time;</li>
 * <li>why/combine.glsl with COLOUR_LOOK 1: skin protection / hue / evenness, vibrance by brightness, the 8-band colour
 * mixer and the monochrome filters in scene OKLab before the hue lock, the gamut compression, the hue-lock mode, and the
 * colour grading of shadows / mid-tones / highlights in display OKLab after the hue lock.</li>
 * </ul>
 * Every value at its default changes nothing: the matrix is not applied and WhyCombine does not set COLOUR_LOOK, so the
 * shader is the one before P134 token for token (tools/check_colour_output.py pins it). Sources (own implementation, no
 * code copied): Lightcraft (MIT / Apache-2.0) for the band centres, the raised-cosine bands, the grading zones; CIE for the
 * daylight and Planckian loci (Kim et al. 2002 cubic); CAT16 (CIECAM16) for the chromatic adaptation; ACES Reference Gamut
 * Compression for the gamut. The skin mask is our own OKLab design (hue ~50°, chroma / lightness ratio).
 */
public final class ColourLook {
    /** The 8 mixer bands and their OKLab hue centres in degrees. */
    public static final String[] BANDS = {"red", "orange", "yellow", "green", "aqua", "blue", "purple", "magenta"};
    public static final float[] BAND_HUE = {29f, 53f, 110f, 143f, 195f, 264f, 294f, 344f};
    /** Monochrome: off, then the filters' transmission of R, G, B (a neutral grey keeps its luminance). */
    public static final String[] MONO = {"off", "neutral", "yellow", "orange", "red", "green", "blue"};
    private static final float[][] MONO_T = {null, {1f, 1f, 1f}, {1f, 0.9f, 0.1f}, {1f, 0.6f, 0.05f}, {1f, 0.2f, 0.02f},
            {0.25f, 1f, 0.3f}, {0.1f, 0.25f, 1f}};
    public static final String[] HUE_LOCK = {"full", "blues", "off"};
    /** Grading zones. */
    public static final String[] ZONES = {"shadows", "mids", "highlights"};

    // white balance on top of the camera's (-100..100, -100..100, 0..100 %, 0..100 %)
    public float warmth, tint, keepWarm, keepCool;
    // saturation (0..100 %, -100..100, 0..100 %; -1..1, -1..1)
    public float skinProtect, skinHue, skinEven, vibShadows, vibHighlights;
    /** Mixer: band * 3 + {0 hue, 1 saturation, 2 lightness}, -100..100. */
    public final float[] mix = new float[24];
    /** Grading per zone: hue on the colour wheel in degrees (0 red, 60 yellow, 120 green ...), strength 0..100. */
    public final float[] gradeHue = new float[3], gradeStr = new float[3];
    public float gradeBalance, gradeBlend = 50f;
    public int mono, hueLock;
    public float gamut;
    /** Primaries calibration: hue and saturation of the red, green and blue primaries, -100..100. */
    public final float[] calHue = new float[3], calSat = new float[3];

    /** No shader stage to run: every G1 / G2 value at its default. */
    public boolean shaderNeutral() {
        if (skinProtect != 0f || skinHue != 0f || skinEven != 0f || vibShadows != 0f || vibHighlights != 0f) return false;
        for (float v : mix) if (v != 0f) return false;
        for (float s : gradeStr) if (s != 0f) return false;
        return mono == 0 && hueLock == 0 && gamut == 0f;
    }

    public boolean mixerOn() {
        for (float v : mix) if (v != 0f) return true;
        return false;
    }

    /** No matrix to apply (warmth, tint and the scene light all off, or no scene CCT for the scene light). */
    public boolean matrixNeutral(float sceneCct) {
        return warmth == 0f && tint == 0f && sceneLightK(sceneCct)[0] == 0f && sceneLightK(sceneCct)[1] == 0f && calibrationNeutral();
    }

    private boolean calibrationNeutral() {
        for (int i = 0; i < 3; i++) if (calHue[i] != 0f || calSat[i] != 0f) return false;
        return true;
    }

    /**
     * Primaries calibration (row-major, linear sRGB): each primary's OKLab hue turned by up to 0.5 rad and its chroma scaled by
     * 1 + 0.6 * s, the columns rescaled so white stays white (M * diag(M^-1 * 1)). Null = identity.
     */
    public float[] calibrationMatrix() {
        if (calibrationNeutral()) return null;
        double[] m = new double[9];
        for (int p = 0; p < 3; p++) {
            double[] lab = oklab(p == 0 ? 1 : 0, p == 1 ? 1 : 0, p == 2 ? 1 : 0);
            double c = Math.hypot(lab[1], lab[2]) * (1 + 0.6 * clamp(calSat[p] / 100f, -1f, 1f));
            double h = Math.atan2(lab[2], lab[1]) + 0.5 * clamp(calHue[p] / 100f, -1f, 1f);
            double[] rgb = linearOf(lab[0], c * Math.cos(h), c * Math.sin(h));
            for (int r = 0; r < 3; r++) m[r * 3 + p] = rgb[r];
        }
        double[] k = mulVec(invert(m), new double[]{1, 1, 1});
        float[] out = new float[9];
        for (int r = 0; r < 3; r++)
            for (int p = 0; p < 3; p++) out[r * 3 + p] = (float) (m[r * 3 + p] * k[p]);
        return out;
    }

    private static double[] oklab(double r, double g, double b) {
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[]{0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s, 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s};
    }

    private static double[] linearOf(double L, double a, double b) {
        double l = L + 0.3963377774 * a + 0.2158037573 * b, m = L - 0.1055613458 * a - 0.0638541728 * b, s = L - 0.0894841775 * a - 1.2914855480 * b;
        l = l * l * l; m = m * m * m; s = s * s * s;
        return new double[]{4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s};
    }

    private static double[] mulVec(double[] m, double[] v) {
        return new double[]{m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    // ---------------------------------------------------------------- uniforms of why/combine.glsl (COLOUR_LOOK 1)

    /** skinU: protection 0..1, hue shift -1..1 (x 12 degrees), evenness 0..1. */
    public float[] skinUniform() {
        return new float[]{clamp(skinProtect / 100f, 0f, 1f), clamp(skinHue / 100f, -1f, 1f), clamp(skinEven / 100f, 0f, 1f)};
    }

    public float[] vibUniform() {
        return new float[]{clamp(vibShadows, -1f, 1f), clamp(vibHighlights, -1f, 1f)};
    }

    /** mixU[24]: the mixer in -1..1. */
    public float[] mixUniform() {
        float[] u = new float[24];
        for (int i = 0; i < 24; i++) u[i] = clamp(mix[i] / 100f, -1f, 1f);
        return u;
    }

    /** monoU: the filter's transmission, 0 = colour. */
    public float[] monoUniform() {
        float[] t = mono > 0 && mono < MONO_T.length ? MONO_T[mono] : null;
        return t == null ? new float[]{0f, 0f, 0f} : t.clone();
    }

    /** gradeAU (shadows a, b, mid-tones a, b) and gradeBU (highlights a, b, balance -1..1, blending 0..1) in OKLab. */
    public float[][] gradeUniforms() {
        float[] off = new float[6];
        for (int z = 0; z < 3; z++) {
            float s = clamp(gradeStr[z] / 100f, 0f, 1f) * 0.06f;
            double h = Math.toRadians(oklabHueOfWheel(gradeHue[z]));
            off[2 * z] = (float) (s * Math.cos(h));
            off[2 * z + 1] = (float) (s * Math.sin(h));
        }
        return new float[][]{{off[0], off[1], off[2], off[3]},
                {off[4], off[5], clamp(gradeBalance / 100f, -1f, 1f), clamp(gradeBlend / 100f, 0f, 1f)}};
    }

    /**
     * OKLab hue (degrees) of the colour-wheel hue a user picks (HSV: 0 red, 60 yellow, 120 green, 180 cyan, 240 blue,
     * 300 magenta): the hue of the fully saturated sRGB colour there.
     */
    public static double oklabHueOfWheel(double wheel) {
        double h = ((wheel % 360.0) + 360.0) % 360.0 / 60.0;
        int i = (int) Math.floor(h);
        double f = h - i;
        double[] rgb;
        switch (i % 6) {
            case 0: rgb = new double[]{1, f, 0}; break;
            case 1: rgb = new double[]{1 - f, 1, 0}; break;
            case 2: rgb = new double[]{0, 1, f}; break;
            case 3: rgb = new double[]{0, 1 - f, 1}; break;
            case 4: rgb = new double[]{f, 0, 1}; break;
            default: rgb = new double[]{1, 0, 1 - f}; break;
        }
        for (int c = 0; c < 3; c++) rgb[c] = rgb[c] <= 0.04045 ? rgb[c] / 12.92 : Math.pow((rgb[c] + 0.055) / 1.055, 2.4);
        double l = Math.cbrt(0.4122214708 * rgb[0] + 0.5363325363 * rgb[1] + 0.0514459929 * rgb[2]);
        double m = Math.cbrt(0.2119034982 * rgb[0] + 0.6806995451 * rgb[1] + 0.1073969566 * rgb[2]);
        double s = Math.cbrt(0.0883024619 * rgb[0] + 0.2817188376 * rgb[1] + 0.6299787005 * rgb[2]);
        double a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        double b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
        return (Math.toDegrees(Math.atan2(b, a)) + 360.0) % 360.0;
    }

    // ---------------------------------------------------------------- white matrix

    /** sRGB (D65) <-> XYZ, row-major. */
    private static final double[] SRGB_TO_XYZ = {0.4123908, 0.3575843, 0.1804808, 0.2126390, 0.7151687, 0.0721923,
            0.0193308, 0.1191948, 0.9505322};
    private static final double[] XYZ_TO_SRGB = invert(SRGB_TO_XYZ);
    /** CAT16 (CIECAM16) XYZ -> LMS. */
    private static final double[] M16 = {0.401288, 0.650173, -0.051461, -0.250268, 1.204414, 0.045854, -0.002079, 0.048952, 0.953127};
    private static final double[] M16_INV = invert(M16);
    private static final double REF_T = 6504.0;

    /**
     * The matrix (row-major, linear sRGB in and out) of the white-balance rows: the image adapted (CAT16) from D65 to the
     * target white - the daylight white of the warmth (0.5 mired per step: -100..100 is 9600..4900 K), moved off the locus
     * by the tint (Duv -0.00015 per step, positive = magenta), and partly towards the scene's light when it is kept (warm:
     * from 5000 K, full at 2700 K; cool: from 7000 K, full at 12000 K); after the primaries calibration
     * ({@link #calibrationMatrix}). White keeps its luminance. Null = identity.
     */
    public float[] whiteMatrix(float sceneCct) {
        if (matrixNeutral(sceneCct)) return null;
        float[] cal = calibrationMatrix();
        if (warmth == 0f && tint == 0f && sceneLightK(sceneCct)[0] == 0f && sceneLightK(sceneCct)[1] == 0f) return cal;
        double[] ref = lms(daylightXy(REF_T));
        double t = 1e6 / (1e6 / REF_T + 0.5 * clamp(warmth, -100f, 100f));
        double[] uv = xyToUv(daylightXy(t));
        double[] n = locusNormal(t);
        double duv = -0.00015 * clamp(tint, -100f, 100f);
        double[] target = lms(uvToXy(new double[]{uv[0] + duv * n[0], uv[1] + duv * n[1]}));
        float[] k = sceneLightK(sceneCct);
        double[] dst = new double[3];
        if (k[0] > 0f || k[1] > 0f) {
            double kk = Math.max(k[0], k[1]);
            double[] scene = lms(k[0] > 0f ? planckXy(sceneCct) : daylightXy(sceneCct));
            for (int c = 0; c < 3; c++) dst[c] = ref[c] + (scene[c] - ref[c]) * kk;
        } else {
            System.arraycopy(ref, 0, dst, 0, 3);
        }
        double[] diag = new double[9];
        for (int c = 0; c < 3; c++) diag[c * 4] = dst[c] / ref[c] * (target[c] / ref[c]);
        double[] b = mul(XYZ_TO_SRGB, mul(M16_INV, mul(diag, mul(M16, SRGB_TO_XYZ))));
        // keep the luminance of white
        double y = 0.2126 * (b[0] + b[1] + b[2]) + 0.7152 * (b[3] + b[4] + b[5]) + 0.0722 * (b[6] + b[7] + b[8]);
        float[] out = new float[9];
        for (int i = 0; i < 9; i++) out[i] = (float) (b[i] / y);
        return cal == null ? out : mul(out, cal);   // the primaries first, then the white balance
    }

    /** Share kept of the scene light: {warm, cool}, 0 when off or the CCT is unknown. */
    float[] sceneLightK(float cct) {
        if (!(cct > 1000f) || cct > 40000f) return new float[]{0f, 0f};
        float mired = 1e6f / cct;
        float warm = clamp(keepWarm / 100f, 0f, 1f) * clamp((mired - 200f) / (370f - 200f), 0f, 1f);
        float cool = clamp(keepCool / 100f, 0f, 1f) * clamp((cct - 7000f) / (12000f - 7000f), 0f, 1f);
        return new float[]{warm, cool};
    }

    /** CIE daylight locus (D series), 4000..25000 K (clamped). */
    static double[] daylightXy(double t) {
        t = Math.max(4000.0, Math.min(25000.0, t));
        double x = t <= 7000.0
                ? 0.244063 + 0.09911e3 / t + 2.9678e6 / (t * t) - 4.6070e9 / (t * t * t)
                : 0.237040 + 0.24748e3 / t + 1.9018e6 / (t * t) - 2.0064e9 / (t * t * t);
        return new double[]{x, -3.0 * x * x + 2.870 * x - 0.275};
    }

    /** Planckian locus, Kim et al. 2002 cubic, 1667..25000 K (clamped). */
    static double[] planckXy(double t) {
        t = Math.max(1667.0, Math.min(25000.0, t));
        double t2 = t * t, t3 = t2 * t;
        double x = t <= 4000.0 ? -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
                : -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390;
        double x2 = x * x, x3 = x2 * x;
        double y = t <= 2222.0 ? -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
                : t <= 4000.0 ? -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
                : 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483;
        return new double[]{x, y};
    }

    /** Unit normal of the daylight locus in CIE 1960 uv at t, pointing to +Duv (above the locus, towards green). */
    private static double[] locusNormal(double t) {
        double m = 1e6 / t;
        double[] a = xyToUv(daylightXy(1e6 / (m - 1.0))), b = xyToUv(daylightXy(1e6 / (m + 1.0)));
        double du = b[0] - a[0], dv = b[1] - a[1];
        double len = Math.hypot(du, dv);
        if (len < 1e-12) return new double[]{0, 1};
        double nu = -dv / len, nv = du / len;
        return nv >= 0 ? new double[]{nu, nv} : new double[]{-nu, -nv};
    }

    private static double[] xyToUv(double[] xy) {
        double d = -2.0 * xy[0] + 12.0 * xy[1] + 3.0;
        return new double[]{4.0 * xy[0] / d, 6.0 * xy[1] / d};
    }

    private static double[] uvToXy(double[] uv) {
        double d = 2.0 * uv[0] - 8.0 * uv[1] + 4.0;
        return new double[]{3.0 * uv[0] / d, 2.0 * uv[1] / d};
    }

    /** LMS (CAT16) of the white with chromaticity xy and Y = 1. */
    private static double[] lms(double[] xy) {
        double[] xyz = {xy[0] / xy[1], 1.0, (1.0 - xy[0] - xy[1]) / xy[1]};
        return new double[]{M16[0] * xyz[0] + M16[1] * xyz[1] + M16[2] * xyz[2], M16[3] * xyz[0] + M16[4] * xyz[1] + M16[5] * xyz[2],
                M16[6] * xyz[0] + M16[7] * xyz[1] + M16[8] * xyz[2]};
    }

    /** Row-major 3x3 product a * b; a float[9] times a float[9] for the camera chain. */
    public static float[] mul(float[] a, float[] b) {
        float[] r = new float[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return r;
    }

    private static double[] mul(double[] a, double[] b) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return r;
    }

    private static double[] invert(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        return new double[]{A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
                B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
                C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
    }

    private static float clamp(float v, float lo, float hi) {
        return Float.isNaN(v) ? 0f : Math.max(lo, Math.min(hi, v));
    }
}
