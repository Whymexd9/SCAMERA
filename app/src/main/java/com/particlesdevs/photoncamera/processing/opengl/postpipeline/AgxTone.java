package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.settings.RawTherapeeSettings;
import com.particlesdevs.photoncamera.util.Log;

/**
 * AgX picture formation (replaces Exposure Fusion when enabled).
 *
 * Core: Kraken-AgX by Jed Smith / Juan Pablo Zambrano (sobotka/AgX-Resolve):
 * inset matrix (per-primary chroma attenuation and hue flight, computed on the
 * CIE xy plane), normalized log2 around 0.18 (-10 / +6.5 EV), Jed Smith's
 * sigmoid with separate toe / shoulder / general contrast powers, 2.2 display
 * encoding, outset (purity) matrix. With default parameters it forms the same
 * picture as the reference AgX (sobotka/AgX, analytic curve in
 * bWFuanVzYWth/AgX). Looks follow Blender 4 (contrast 0.7..1.57) and the
 * minimal-AgX CDL looks used by the FairplexVR Unity pack (Punchy, Hue, ...).
 *
 * All user controls are bipolar around 0 (minus = less / darker, plus = more /
 * brighter) and are applied on top of the selected look.
 */
public final class AgxTone {
    private AgxTone() {}

    /** Parameters handed to the shader. */
    public static final class Params {
        public float exposure = 1f;          // linear multiplier
        public float minEv = -10f, maxEv = 6.5f;
        public float px, py = 0.5f;          // pivot: log position of 0.18, display 0.5
        public float slope = 2f, toe = 3f, shoulder = 3.25f;
        public float ss, ts;                 // precomputed sigmoid scales
        public float lookSlope = 1f, lookOffset = 0f, lookPower = 1f, saturation = 1f;
        public float[] inset = new float[9], outset = new float[9];
        public float localStrength = 0.5f;   // local highlight compression, 0..1
        public float localStart = 3f;        // EV above grey where it starts
        public float highlightDesat = 0f;    // near-white tint removal, 0..1 (SCAM HDR only)
        public float desatStart = 0.78f;     // display level where that removal begins
        public String describe;
    }

    private static float num(String key, float fallback, float lo, float hi) {
        return RawTherapeeSettings.number(key, fallback, lo, hi);
    }

    public static boolean enabled() {
        return !"off".equals(RawTherapeeSettings.text("pref_agx_mode", "agx"));
    }

    public static Params load() { return load(false); }

    /** The AgX linear exposure multiplier (EV slider + look offset), without loading the rest. */
    public static float exposureMultiplier() {
        float evLook = "scamera".equals(RawTherapeeSettings.text("pref_agx_look", "scamera")) ? 0.25f : 0f;
        return (float) Math.pow(2.0, num("pref_agx_exposure", 0f, -3f, 3f) + (enabled() ? evLook : 0f));
    }

    /**
     * @param nice SCAM HDR: the HDR data of large bright areas (windows, sky) keeps its
     *             range (stronger, earlier local compression) and near-white highlights
     *             lose the colour cast that white balance and the look saturation leave
     *             on them. Plain photos keep their settings.
     */
    public static Params load(boolean nice) {
        Params p = new Params();
        String look = RawTherapeeSettings.text("pref_agx_look", "scamera");
        // Look presets (Blender 4 contrast looks; minimal-AgX / FairplexVR CDL looks).
        float contrastLook = 1f, toeLook = 1f, midLook = 0f, evLook = 0f;
        switch (look) {
            // Fitted on a daylight NICE shot against LMC 9.6 (luminance quantiles and
            // saturation, AgX inverted from our own output): Blender "base" is a flat
            // log-like look meant for grading, too hazy as a camera default.
            case "scamera": contrastLook = 2f; toeLook = 0.595f; midLook = 10f; evLook = 0.25f; p.saturation = 1.08f; break;
            case "very_low": contrastLook = 0.7f; break;
            case "low": contrastLook = 0.8f; break;
            case "medium_low": contrastLook = 0.9f; break;
            case "medium_high": contrastLook = 1.2f; break;
            case "high": contrastLook = 1.4f; break;
            case "very_high": contrastLook = 1.57f; break;
            case "punchy": p.lookPower = 1.35f; p.saturation = 1.4f; break;
            case "hue": p.lookSlope = 1.1f; p.lookPower = 1.1f; p.saturation = 1.3f; break;
            case "powerful": p.lookSlope = 1.12f; p.lookPower = 1.2f; p.saturation = 1.2f; break;
            case "golden": p.lookPower = 0.8f; p.saturation = 0.8f; break;
            case "greyscale": p.saturation = 0f; break;
            default: break;
        }
        // Bipolar controls (-100..+100 unless noted; 0 = look as is).
        float ev = num("pref_agx_exposure", 0f, -3f, 3f);
        float contrast = num("pref_agx_contrast", 0f, -100f, 100f);
        float shadows = num("pref_agx_shadow_contrast", 0f, -100f, 100f);
        float highlights = num("pref_agx_highlight_contrast", 0f, -100f, 100f);
        float midtones = num("pref_agx_midtones", 0f, -100f, 100f);
        float headroom = num("pref_agx_highlight_range", 0f, -3f, 3f);
        float depth = num("pref_agx_shadow_range", 0f, -3f, 3f);
        float saturation = num("pref_agx_saturation", 0f, -100f, 100f);
        float density = num("pref_agx_density", 0f, -100f, 100f);
        float attenuation = num("pref_agx_attenuation", 0f, -100f, 100f);
        float purity = num("pref_agx_purity", 0f, -100f, 100f);
        float hueR = num("pref_agx_hue_red", 0f, -25f, 25f);
        float hueG = num("pref_agx_hue_green", 0f, -25f, 25f);
        float hueB = num("pref_agx_hue_blue", 0f, -25f, 25f);
        // Local highlight range: 0..100 % (default 50), start bipolar around 3 EV.
        if (nice) {
            // SCAM HDR has its own highlight controls (group "Света в SCAM HDR").
            p.localStrength = num("pref_agx_nice_local_strength", 70f, 0f, 100f) / 100f;
            p.localStart = num("pref_agx_nice_knee_start", 0.75f, 0f, 5f);
            p.highlightDesat = num("pref_agx_highlight_desat", 45f, 0f, 100f) / 100f;
            p.desatStart = num("pref_agx_desat_start", 88f, 50f, 95f) / 100f;
        } else {
            p.localStrength = num("pref_agx_local_highlights", 50f, 0f, 100f) / 100f;
            p.localStart = 3f + num("pref_agx_local_start", 0f, -2f, 3f);
        }

        p.exposure = (float) Math.pow(2.0, ev + evLook);
        p.maxEv = 6.5f + headroom;
        p.minEv = -10f - depth;
        p.px = -p.minEv / (p.maxEv - p.minEv);
        p.py = clamp(0.5f + 0.15f * (midtones + midLook) / 100f, 0.3f, 0.7f);
        p.slope = 2f * contrastLook * (float) Math.pow(2.0, contrast / 100.0);
        p.toe = 3f * toeLook * (float) Math.pow(2.0, shadows / 100.0);
        p.shoulder = 3.25f * (float) Math.pow(2.0, highlights / 100.0);
        p.saturation *= (float) Math.pow(2.0, saturation / 100.0);
        p.lookPower *= (float) Math.pow(2.0, density / 100.0);
        // Sigmoid is defined only while slope exceeds the pivot's own ratios.
        float minSlope = 1.02f * Math.max((1f - p.py) / (1f - p.px), p.py / p.px);
        p.slope = Math.max(p.slope, minSlope);
        p.ss = scale(p.slope, p.shoulder, 1f - p.px, 1f - p.py);
        p.ts = scale(p.slope, p.toe, p.px, p.py);

        // Attenuation 0.2 (Kraken default), 0..0.6; purity -0.4..+0.4.
        float inset = clamp(0.2f * (float) Math.pow(2.0, attenuation / 100.0 * 1.5), 0.001f, 0.6f);
        float outset = clamp(0.4f * purity / 100f, -0.4f, 0.4f);
        double[][] rec709 = {{0.64, 0.33}, {0.30, 0.60}, {0.15, 0.06}, {0.3127, 0.3290}};
        double[][] insetPrim = insetPrimaries(rec709, inset, inset, inset, hueR, hueG, hueB);
        double[][] outsetPrim = insetPrimaries(rec709, outset, outset, outset, hueR, hueG, hueB);
        p.inset = toFloat(mul(xyzToRgb(rec709), rgbToXyz(insetPrim)));   // RGBtoRGB(inset, in)
        p.outset = toFloat(mul(xyzToRgb(outsetPrim), rgbToXyz(rec709))); // RGBtoRGB(in, outset)
        p.describe = String.format(java.util.Locale.ROOT,
                "look=%s EV=%.2f range=[%.2f,%.2f] pivot=(%.3f,%.3f) slope=%.3f toe=%.3f shoulder=%.3f "
                        + "cdl(slope=%.2f power=%.2f) sat=%.2f inset=%.3f outset=%.3f hue=(%.1f,%.1f,%.1f) local=%.2f@%.1fEV desat=%.2f@%.2f",
                look, ev, p.minEv, p.maxEv, p.px, p.py, p.slope, p.toe, p.shoulder,
                p.lookSlope, p.lookPower, p.saturation, inset, outset, hueR, hueG, hueB, p.localStrength, p.localStart,
                p.highlightDesat, p.desatStart);
        Log.i("AgX", p.describe);
        return p;
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Jed Smith sigmoid scale for one side (s0=1, t0=0 folded into length/height). */
    private static float scale(float slope, float power, float length, float height) {
        double a = Math.pow(slope * length / height, power) - 1.0;
        double b = Math.pow(slope * length, -power);
        return (float) Math.pow(a * b, -1.0 / power);
    }

    // ---- Chromaticity geometry (port of Camera-AgX-Lib.h) ----
    private static double[][] copy(double[][] n) {
        double[][] m = new double[4][];
        for (int i = 0; i < 4; i++) m[i] = n[i].clone();
        return m;
    }

    private static double[][] scalePrim(double[][] n, double rs, double gs, double bs) {
        double[][] m = copy(n);
        double[] s = {rs, gs, bs};
        for (int i = 0; i < 3; i++) {
            m[i][0] = (n[i][0] - n[3][0]) * s[i] + n[3][0];
            m[i][1] = (n[i][1] - n[3][1]) * s[i] + n[3][1];
        }
        return m;
    }

    private static double[][] rotatePrim(double[][] n, double r, double g, double b) {
        double[][] m = copy(n);
        double[] rot = {Math.toRadians(r), Math.toRadians(g), Math.toRadians(b)};
        for (int i = 0; i < 3; i++) {
            double x = n[i][0] - n[3][0], y = n[i][1] - n[3][1];
            double len = Math.hypot(x, y), ang = Math.atan2(y, x) + rot[i];
            m[i][0] = len * Math.cos(ang) + n[3][0];
            m[i][1] = len * Math.sin(ang) + n[3][1];
        }
        return m;
    }

    private static double[] line(double[] a, double[] b) {
        double m = (b[1] - a[1]) / (b[0] - a[0]);
        return new double[]{m, a[1] - m * a[0]};
    }

    private static double[] intersect(double[] a, double[] b) {
        double x = (b[1] - a[1]) / (a[0] - b[0]);
        return new double[]{x, x * a[0] + a[1]};
    }

    /** InsetPrimaries: scale x2, rotate, clip to the gamut polygon, scale by 1-cp. */
    static double[][] insetPrimaries(double[][] n, double cpr, double cpg, double cpb,
                                     double ored, double og, double ob) {
        double[][] m = copy(n);
        double[][] s = rotatePrim(scalePrim(n, 2, 2, 2), ored, og, ob);
        double[] rg = line(m[0], m[1]), rb = line(m[0], m[2]), bg = line(m[2], m[1]);
        double[] redEdge = ored > 0 ? rg : rb;
        double[] greenEdge = og > 0 ? bg : rg;
        double[] blueEdge = ob > 0 ? rb : bg;
        double[][] out = copy(n);
        out[0] = intersect(line(s[0], n[3]), redEdge);
        out[1] = intersect(line(s[1], n[3]), greenEdge);
        out[2] = intersect(line(s[2], n[3]), blueEdge);
        return scalePrim(out, 1 - cpr, 1 - cpg, 1 - cpb);
    }

    static double[][] rgbToXyz(double[][] n) {
        double[][] m = new double[3][3];
        for (int i = 0; i < 3; i++) {
            double x = n[i][0], y = n[i][1];
            m[0][i] = x / y; m[1][i] = 1; m[2][i] = (1 - x - y) / y;
        }
        double[] w = {n[3][0] / n[3][1], 1, (1 - n[3][0] - n[3][1]) / n[3][1]};
        double[] s = apply(inv(m), w);
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) m[r][c] *= s[c];
        return m;
    }

    static double[][] xyzToRgb(double[][] n) { return inv(rgbToXyz(n)); }

    private static double[] apply(double[][] m, double[] v) {
        double[] o = new double[3];
        for (int r = 0; r < 3; r++) o[r] = m[r][0] * v[0] + m[r][1] * v[1] + m[r][2] * v[2];
        return o;
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] o = new double[3][3];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++)
            for (int k = 0; k < 3; k++) o[r][c] += a[r][k] * b[k][c];
        return o;
    }

    private static double[][] inv(double[][] m) {
        double d = m[0][0] * (m[1][1] * m[2][2] - m[2][1] * m[1][2])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        double[][] c = new double[3][3];
        c[0][0] = (m[1][1] * m[2][2] - m[2][1] * m[1][2]) / d;
        c[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / d;
        c[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / d;
        c[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / d;
        c[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / d;
        c[1][2] = (m[1][0] * m[0][2] - m[0][0] * m[1][2]) / d;
        c[2][0] = (m[1][0] * m[2][1] - m[2][0] * m[1][1]) / d;
        c[2][1] = (m[2][0] * m[0][1] - m[0][0] * m[2][1]) / d;
        c[2][2] = (m[0][0] * m[1][1] - m[1][0] * m[0][1]) / d;
        return c;
    }

    /** Row-major 3x3 to the column-major order glProg.setVar(mat3) expects is handled by the caller. */
    private static float[] toFloat(double[][] m) {
        float[] f = new float[9];
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) f[r * 3 + c] = (float) m[r][c];
        return f;
    }
}
