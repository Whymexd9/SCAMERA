package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.BufferUtils;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * ARK tone: the photo sharpening of ArkCam 1.23 (process_luma_fp16 of libfc_suppressor.so, research/hybrid5/
 * ark_sharpen.md) on the full-size luminance of the merge, before ArkCombine takes the detail delta from it - as the mod
 * sharpens Google's guide a3 before its tone. Ya = min(m * Y709, 1) (m = 1: the G_CLEAN scale of a3, m = ae: the domain
 * of the delta), then luma_compose (USM with noise threshold, bilateral high-boost, guided-filter local contrast, damped
 * on strong 3x3 contrast and clamped to the local min/max) and up to three Richardson-Lucy stages (Airy / pillbox /
 * Gaussian PSF, rl_blend with the same halo clamp). Defaults: the X8U 2.85 main-camera values. The result "ArkLumaS"
 * replaces ArkCombine's Y_full; the image passes through.
 * <p>On the Sabre 2x grid the spatial constants are multiplied by the grid scale (the mod sharpens the 12 MP output) and
 * the bilateral and Gaussian RL amounts are trimmed (SCALE2_*), so a 50 MP image downscaled to 12 MP gets ArkCam's
 * response within 5 % up to 0.4 cycles / px (tools/check_ark_sharpen.py).
 */
public final class ArkLumaSharpen extends Node {
    /** 2x grid: the scaled kernels are finer sampled and sharpen up to 19 % more than the 1x ones (check_ark_sharpen). */
    static final float SCALE2_BIL = 0.75f;
    static final float SCALE2_RL_GAUSS = 0.85f;
    private static final int MAX_HALF = 8;

    public ArkLumaSharpen() { super("", "ArkLumaSharpen"); }
    @Override public void Compile() {}

    private static GLTexture plane(Point size, GLFormat.DataType type, int channels) {
        return new GLTexture(size, new GLFormat(type, channels), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
    }

    /** Amount factor at grid scale s: 1 at s <= 1, k at s >= 2, linear in between. */
    static float scaleComp(float s, float k) {
        return 1f + (k - 1f) * Math.min(Math.max(s - 1f, 0f), 1f);
    }

    /** One PSF for sharp_rl.glsl: the half size and the quadrant q[|dy| * (half + 1) + |dx|] of the normalised kernel. */
    static final class Psf {
        final int half;
        final float[] quadrant = new float[81];
        Psf(int half) { this.half = half; }
    }

    /**
     * The mod's PSF generators [helper 0x7a844; ark_sharpen.md 3.2]: 0 Gaussian (sigma = rad), 1 pillbox (8x8 subsamples
     * on the rim), 2 Airy ((2 J1(x) / x)^2, x = 3.831706 d / rad, 4x4 subsamples at d <= 1.5, J1(x) / x as coded in the
     * library). Kernels wider than 17x17 are truncated and renormalised (the shader holds one 9x9 quadrant).
     */
    static Psf psf(int kernel, float rad) {
        double[][] k;
        if (kernel == 2) k = airy(rad);
        else if (kernel == 1) k = disk(rad);
        else {
            double[] g = gauss1d(rad);
            k = new double[g.length][g.length];
            for (int y = 0; y < g.length; y++) for (int x = 0; x < g.length; x++) k[y][x] = g[y] * g[x];
        }
        int full = k.length / 2;
        int half = Math.min(full, MAX_HALF);
        double sum = 0;
        for (int y = -half; y <= half; y++) for (int x = -half; x <= half; x++) sum += k[full + y][full + x];
        Psf out = new Psf(half);
        for (int y = 0; y <= half; y++)
            for (int x = 0; x <= half; x++) out.quadrant[y * (half + 1) + x] = (float) (k[full + y][full + x] / sum);
        return out;
    }

    static double[] gauss1d(double sigma) {
        int r = (int) Math.ceil(sigma * 3.5);
        double[] w = new double[2 * r + 1];
        double sum = 0;
        for (int i = -r; i <= r; i++) { w[i + r] = Math.exp(-i * i / (2 * sigma * sigma)); sum += w[i + r]; }
        for (int i = 0; i < w.length; i++) w[i] /= sum;
        return w;
    }

    /** J1(x) / x exactly as coded in libfc_suppressor 0x7a844. */
    static double j1OverX(double x) {
        double ax = Math.abs(x);
        if (ax < 1e-4) return 0.5;
        if (ax <= 3.75) {
            double y = (ax / 3.75) * (ax / 3.75);
            return 0.5 + y * (-0.56249982 + y * (0.21093573 + y * (-0.03954289 + y * (0.00443319 + y * -0.00031761))));
        }
        double z = 3.75 / ax, y = z * z;
        double p = 0.79788458 + y * (-7.7e-7 + y * (-0.0055274 + y * (9.512e-5 + y * -0.00137237)));
        double q = 0.046875 + y * (-0.00020033 + y * (0.00844919 + y * -0.00088126));
        return (p / Math.sqrt(ax)) * Math.cos(ax - 2.3561945 + z * q) / ax;
    }

    private static double airyAt(double d, double rad) {
        if (rad <= 1e-4) return 0;
        double v = 2 * j1OverX(d / rad * 3.831706);
        return v * v;
    }

    static double[][] airy(double rad) {
        int half = Math.min((int) Math.ceil(rad * 1.85), 16);
        double[][] k = new double[2 * half + 1][2 * half + 1];
        double[] offs = {-0.375, -0.125, 0.125, 0.375};
        double sum = 0;
        for (int dy = -half; dy <= half; dy++) {
            for (int dx = -half; dx <= half; dx++) {
                double d = Math.hypot(dx, dy), v;
                if (d > 1.5) v = airyAt(d, rad);
                else {
                    v = 0;
                    for (double oy : offs) for (double ox : offs) v += airyAt(Math.hypot(dx + ox, dy + oy), rad);
                    v /= 16;
                }
                if (v <= 1e-5) v = 0;
                k[dy + half][dx + half] = v;
                sum += v;
            }
        }
        for (double[] row : k) for (int i = 0; i < row.length; i++) row[i] /= sum;
        return k;
    }

    static double[][] disk(double rad) {
        int half = Math.min((int) Math.ceil(rad + 0.5), 16);
        double[][] k = new double[2 * half + 1][2 * half + 1];
        double sum = 0;
        for (int dy = -half; dy <= half; dy++) {
            for (int dx = -half; dx <= half; dx++) {
                double d = Math.hypot(dx, dy), v;
                if (d + 0.7071 <= rad) v = 1;
                else if (d - 0.7071 >= rad) v = 0;
                else {
                    int in = 0;
                    for (int j = 0; j < 8; j++)
                        for (int i = 0; i < 8; i++) {
                            double ox = (i + 0.5) / 8 - 0.5, oy = (j + 0.5) / 8 - 0.5;
                            if ((dx + ox) * (dx + ox) + (dy + oy) * (dy + oy) <= rad * rad) in++;
                        }
                    v = in / 64.0;
                }
                k[dy + half][dx + half] = v;
                sum += v;
            }
        }
        for (double[] row : k) for (int i = 0; i < row.length; i++) row[i] /= sum;
        return k;
    }

    /** A free texture of the pool (any but the busy ones), allocated on demand. */
    private static GLTexture spare(List<GLTexture> pool, Point size, GLTexture... busy) {
        for (GLTexture t : pool) {
            boolean used = false;
            for (GLTexture b : busy) used |= t == b;
            if (!used) return t;
        }
        GLTexture t = plane(size, GLFormat.DataType.FLOAT_16, 1);
        pool.add(t);
        return t;
    }

    /** W1.6: the input the passes were issued on by {@link #runEarly}, null when they run in {@link #Run}. */
    private GLTexture earlyInput;

    /**
     * W1.6: called by ArkStats right after its arkLow read-back, before the CPU Smart-HDR statistics. In the G_CLEAN domain
     * (sharp_domain 1, the default) the passes use nothing of the AE (Ya = min(Y709, 1)), so they are issued now on the same
     * input and run on the GPU while the CPU works; {@link #Run} then only passes the image on. False (nothing issued) in the
     * ae domain, which needs the exposure.
     */
    boolean runEarly(PostPipeline pipeline, GLTexture input) {
        if (Math.round(ArkTone.value("sharp_domain", 1f)) < 1 || pipeline.ark == null || input == null) return false;
        issue(pipeline, input, true);
        earlyInput = input;
        return true;
    }

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        GLTexture input = previousNode.WorkingTexture;
        WorkingTexture = input;
        glProg.closed = true;
        ArkTone.State st = pipeline.ark;
        if (earlyInput != null) {
            final boolean same = earlyInput == input;
            earlyInput = null;
            if (same) return;
            // Not expected (ArkFusion passes the image on): the passes run again on this node's own input.
            Log.w("SCAM_PIPELINE", "ARK luma sharpen: input changed after the early passes, sharpening again");
            if (st != null && st.lumaS != null) { st.lumaS.close(); st.lumaS = null; }
        }
        if (st == null || st.ae == null) throw new IllegalStateException("ARK sharpen: no statistics (ArkStats did not run)");
        issue(pipeline, input, false);
    }

    /** The passes of the ARK sharpening on input; the result goes to pipeline.ark.lumaS. */
    private void issue(PostPipeline pipeline, GLTexture input, boolean early) {
        ArkTone.State st = pipeline.ark;
        long started = System.currentTimeMillis();
        Point size = input.mSize;
        boolean gClean = Math.round(ArkTone.value("sharp_domain", 1f)) >= 1;
        float mul = gClean ? 1f : st.ae.ae;
        float scaleSetting = ArkTone.value("sharp_scale", 0f);
        float s = scaleSetting > 0f ? Math.min(scaleSetting, 4f) : Math.max(1, Math.round(pipeline.mParameters.outputScale));
        int down = Math.max(1, (int) Math.floor(s + 1e-4f));
        int nb = Math.max(1, Math.round(s));

        float usmRadius = ArkTone.value("sharp_usm_radius", 0.5f);
        float usmAmount = ArkTone.value("sharp_usm_amount", 1f);
        float usmThresh = ArkTone.value("sharp_usm_thresh", 50f) / 255f;
        float bilRadius = ArkTone.value("sharp_bilateral_radius", 0.5f);
        float bilAmount = ArkTone.value("sharp_bilateral_amount", 1f);
        float bilColor = Math.max(ArkTone.value("sharp_bilateral_color", 10f) / 100f, 0.001f);
        float halo = clamp01(ArkTone.value("sharp_halo_control", 100f) / 100f);
        float protectShadows = clamp01(ArkTone.value("sharp_protect_shadows", 0f) / 100f);
        float protectHighlights = clamp01(ArkTone.value("sharp_protect_highlights", 100f) / 100f);
        int gfRadius = Math.round(ArkTone.value("sharp_gf_radius", 8f));
        float gfEps = Math.max(ArkTone.value("sharp_gf_eps_e3", 10f) * 0.001f, 1e-6f);
        float gfLc = ArkTone.value("sharp_gf_lc", 0.25f);
        float grain = ArkTone.value("sharp_film_grain", 0f) / 255f;
        float rlHalo = clamp01(ArkTone.value("sharp_rl_halo_control", 100f) / 100f);
        float rlMargin = ArkTone.value("sharp_rl_halo_margin", 0.15f);
        float rlMacro = ArkTone.value("sharp_rl_halo_macro", 0.4f);
        // (kernel, radius, amount, iterations) of the three RL stages; X8U: Airy 1 x3, off, Gaussian 0.5 x3
        float[][] rl = {
                {ArkTone.value("sharp_rl1_kernel", 2f), ArkTone.value("sharp_rl1_rad", 1f), ArkTone.value("sharp_rl1_amount", 1f), ArkTone.value("sharp_rl1_iters", 3f)},
                {ArkTone.value("sharp_rl2_kernel", 1f), ArkTone.value("sharp_rl2_rad", 0f), ArkTone.value("sharp_rl2_amount", 0f), ArkTone.value("sharp_rl2_iters", 0f)},
                {ArkTone.value("sharp_rl3_kernel", 0f), ArkTone.value("sharp_rl3_rad", 0.5f), ArkTone.value("sharp_rl3_amount", 1f), ArkTone.value("sharp_rl3_iters", 3f)}};

        GLTexture gainMap = pipeline.GainMap;
        GLTexture fallback = null;
        if (gainMap == null) {
            fallback = new GLTexture(new Point(1, 1), new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                    BufferUtils.getFrom(new float[]{1f, 1f, 1f, 1f}), GL_LINEAR, GL_CLAMP_TO_EDGE);
            gainMap = fallback;
        }
        GLTexture ya = plane(size, GLFormat.DataType.FLOAT_16, 1);
        glProg.useAssetProgram("ark/sharp_in", false);
        glProg.setTexture("InputBuffer", input);
        ArkTone.setColour(glProg, pipeline, gainMap, st.inScale);
        glProg.setVar("mulU", mul);
        glProg.drawBlocks(ya);
        if (fallback != null) fallback.close();

        boolean gf = gfRadius >= 1 && Math.abs(gfLc) > 0.001f;
        GLTexture ab = null;
        int gfBox = 0;
        if (gf) {
            gfBox = Math.max(1, Math.round(gfRadius * s / down));
            Point reduced = ArkTone.reduced(size, down);
            GLTexture h = plane(reduced, GLFormat.DataType.FLOAT_32, 2);
            glProg.useAssetProgram("ark/sharp_box", false);
            glProg.setTexture("InputBuffer", ya);
            glProg.setVar("modeU", 0);
            glProg.setVar("radiusU", gfBox);
            glProg.setVar("downU", down);
            glProg.drawBlocks(h);
            for (int mode = 1; mode <= 3; mode++) {
                GLTexture next = plane(reduced, GLFormat.DataType.FLOAT_16, 2);
                glProg.useAssetProgram("ark/sharp_box", false);
                glProg.setTexture("InputBuffer", h);
                glProg.setVar("modeU", mode);
                glProg.setVar("radiusU", gfBox);
                glProg.setVar("epsU", gfEps);
                glProg.drawBlocks(next);
                h.close();
                h = next;
            }
            ab = h;
        }

        boolean usm = usmRadius > 0f && Math.abs(usmAmount) > 0.001f;
        boolean bil = bilRadius > 0f && Math.abs(bilAmount) > 0.001f;
        List<GLTexture> pool = new ArrayList<>();
        GLTexture obs = spare(pool, size);
        glProg.useAssetProgram("ark/sharp_compose", false);
        glProg.setTexture("InputBuffer", ya);
        glProg.setTexture("GfAB", ab != null ? ab : ya);
        glProg.setVar("downU", down);
        glProg.setVar("gfLcU", gf ? gfLc : 0f);
        glProg.setVar("usmSigmaU", usm ? usmRadius * s : 0f);
        glProg.setVar("usmAmountU", usmAmount);
        glProg.setVar("usmThreshU", usmThresh);
        glProg.setVar("bilAmountU", bil ? bilAmount * scaleComp(s, SCALE2_BIL) : 0f);
        glProg.setVar("bilRadiusU", (int) Math.ceil(Math.ceil(bilRadius) * s));
        glProg.setVar("bilSigmaSU", Math.max(0.5f, bilRadius * 0.5f) * s);
        glProg.setVar("bilSigmaLU", bilColor);
        glProg.setVar("nbU", nb);
        glProg.setVar("haloU", halo);
        glProg.setVar("protectShadowsU", protectShadows);
        glProg.setVar("protectHighlightsU", protectHighlights);
        glProg.setVar("grainU", grain);
        glProg.drawBlocks(obs);
        ya.close();
        if (ab != null) ab.close();

        StringBuilder stages = new StringBuilder();
        GLTexture ratio = null;
        for (int i = 0; i < rl.length; i++) {
            int kernel = Math.round(rl[i][0]);
            float rad = rl[i][1], amount = rl[i][2];
            int iters = Math.min(Math.round(rl[i][3]), 20);
            if (!(rad > 0f && Math.abs(amount) > 0.001f && iters >= 1)) continue;
            Psf k = psf(kernel, rad * s);
            if (kernel == 0) amount *= scaleComp(s, SCALE2_RL_GAUSS);
            if (ratio == null) ratio = plane(size, GLFormat.DataType.FLOAT_16, 1);
            GLTexture est = obs;
            for (int it = 0; it < iters; it++) {
                glProg.useAssetProgram("ark/sharp_rl", false);
                glProg.setTexture("Observed", obs);
                glProg.setTexture("Estimate", est);
                glProg.setVar("modeU", 0);
                glProg.setVar("psfHalfU", k.half);
                glProg.setVarFloats("psfU", k.quadrant);
                glProg.drawBlocks(ratio);
                GLTexture next = spare(pool, size, obs, est);
                glProg.useAssetProgram("ark/sharp_rl", false);
                glProg.setTexture("Observed", obs);
                glProg.setTexture("Estimate", est);
                glProg.setTexture("Ratio", ratio);
                glProg.setVar("modeU", it == iters - 1 ? 2 : 1);
                glProg.setVar("psfHalfU", k.half);
                glProg.setVarFloats("psfU", k.quadrant);
                glProg.setVar("amountU", amount);
                glProg.setVar("haloU", rlHalo);
                glProg.setVar("marginU", rlMargin);
                glProg.setVar("macroU", rlMacro);
                glProg.setVar("protectShadowsU", protectShadows);
                glProg.setVar("protectHighlightsU", protectHighlights);
                glProg.setVar("nbU", nb);
                glProg.drawBlocks(next);
                est = next;
            }
            obs = est;
            stages.append(" rl").append(i + 1).append("=").append(kernel == 2 ? "airy" : kernel == 1 ? "disk" : "gauss")
                    .append(rad * s).append("x").append(iters).append("@").append(amount);
        }
        glProg.closed = true;
        if (ratio != null) ratio.close();
        for (GLTexture t : pool) if (t != obs) t.close();
        st.lumaS = obs;
        st.sharpMul = mul;
        Log.i("SCAM_PIPELINE", "ARK luma sharpen grid=" + size.x + "x" + size.y + " scale=" + s + " domain=" + (gClean ? "G_CLEAN" : "ae")
                + " mul=" + mul + " bil=" + (bil ? bilAmount * scaleComp(s, SCALE2_BIL) : 0f) + " gf=" + (gf ? gfLc + "@r" + gfBox + "/" + down : "off")
                + " usm=" + (usm ? usmAmount + "@" + usmRadius * s : "off") + stages + " ms=" + (System.currentTimeMillis() - started)
                + (early ? " (issued before the AE)" : ""));
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
