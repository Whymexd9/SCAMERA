package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * ARK tone, step 2 (tone_port.md 2.2-2.3 / 7.3) on arkLow: bracket_denoise of the Bento range (clip > 1.05), then the
 * mod's exposure fusion of the luminance - four synthetic exposures (2^-1.5H, 1, 2^S, 2^2S) of the AE result, ACES
 * lumas and Gaussian weights, blended by a fast guided filter (2x2 down, box r = ef_gf_radius / 2, eps, box, bilinear
 * up) into "fused", the display luma ArkCombine inverts to the per-pixel gain. All statistics in RGBA32F as the
 * kernel's float buffers; the boxes average weights only and never reconstruct image detail. The image passes through.
 */
public final class ArkFusion extends Node {
    public ArkFusion() { super("", "ArkFusion"); }
    @Override public void Compile() {}

    private static final GLFormat STATS = new GLFormat(GLFormat.DataType.FLOAT_32, 4);

    private GLTexture stats(Point size) {
        return new GLTexture(size, STATS, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
    }

    /** gf.glsl with the weights of this shot. */
    private void weightsProgram(int mode, GLTexture source, float[] em, float[] lw, float ae) {
        glProg.setDefine("MODE", mode);
        glProg.useAssetProgram("ark/gf", false);
        glProg.setTexture("InputBuffer", source);
        glProg.setVar("expU", em[0], em[1], em[2], em[3]);
        glProg.setVar("layerU", lw[0], lw[1], lw[2], lw[3]);
        glProg.setVar("aeU", ae);
        glProg.setVar("centerU", ArkTone.value("weight_center", 0.6f));
        glProg.setVar("smoothU", ArkTone.value("blend_smoothness", 0.25f));
        glProg.setVar("gammaInvU", 1f / ArkTone.gamma());
        glProg.setVar("toeU", ArkTone.value("aces_toe", 0.05f));
    }

    /** Separable box of radius r; closes the input. */
    private GLTexture box(GLTexture in, int r) {
        GLTexture h = stats(in.mSize);
        glProg.useAssetProgram("ark/box", false);
        glProg.setTexture("InputBuffer", in);
        glProg.setVar("dirU", 1, 0);
        glProg.setVar("radiusU", r);
        glProg.drawBlocks(h);
        in.close();
        GLTexture v = stats(in.mSize);
        glProg.useAssetProgram("ark/box", false);
        glProg.setTexture("InputBuffer", h);
        glProg.setVar("dirU", 0, 1);
        glProg.setVar("radiusU", r);
        glProg.drawBlocks(v);
        h.close();
        return v;
    }

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        glProg.closed = true;
        ArkTone.State st = pipeline.ark;
        if (st == null || st.ae == null || st.low == null) throw new IllegalStateException("ARK tone: no statistics (ArkStats did not run)");
        long started = System.currentTimeMillis();
        ArkAe.Result r = st.ae;
        Point lowSize = st.low.mSize;
        float strength = ArkTone.value("bracket_denoise", 1f);
        st.lowDn = st.low;
        if (r.clip > 1.05f && strength > 0.001f) {
            st.lowDn = new GLTexture(lowSize, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("ark/bracket_dn", false);
            glProg.setTexture("InputBuffer", st.low);
            glProg.setVar("strengthU", strength);
            glProg.drawBlocks(st.lowDn);
        }
        float[] em = ArkAe.exposures(r);
        // layer weights in the kernel's order (hl, mid, ext_hl, shadow) [code 0x78b18-0x78b3c]
        float[] lw = {ArkTone.value("weight_hl", 0.8f), ArkTone.value("weight_mid", 1.0f),
                ArkTone.value("weight_ext_hl", 0.7f), ArkTone.value("weight_shadow", 0.5f)};
        int radius = Math.max(1, Math.round(ArkTone.value("gf_radius", 32f)));
        radius = radius > 1 ? radius >> 1 : 1;                                                  // [code 0x78f80-0x78f9c]
        // ef_gf_eps in units of 0.001 (the settings bar shows two decimals): max(eps, 1e-6) as the mod
        float eps = Math.max(ArkTone.value("gf_eps_e3", 1f) * 0.001f, 1e-6f);
        Point half = new Point((lowSize.x + 1) / 2, (lowSize.y + 1) / 2);
        GLTexture[] means = new GLTexture[3];
        for (int mode = 0; mode < 3; mode++) {
            GLTexture down = stats(half);
            weightsProgram(mode, st.lowDn, em, lw, r.ae);
            glProg.drawBlocks(down);
            means[mode] = box(down, radius);
        }
        GLTexture[] ab = new GLTexture[2];
        for (int mode = 0; mode < 2; mode++) {
            GLTexture coef = stats(half);
            glProg.setDefine("MODE", mode);
            glProg.useAssetProgram("ark/ab", false);
            glProg.setTexture("MeanII", means[0]);
            glProg.setTexture("MeanP", means[1]);
            glProg.setTexture("MeanIP", means[2]);
            glProg.setVar("epsU", eps);
            glProg.drawBlocks(coef);
            ab[mode] = box(coef, radius);
        }
        for (GLTexture t : means) t.close();
        st.fused = new GLTexture(lowSize, new GLFormat(GLFormat.DataType.FLOAT_32, 1), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        weightsProgram(3, st.lowDn, em, lw, r.ae);
        glProg.setTexture("MeanA", ab[0]);
        glProg.setTexture("MeanB", ab[1]);
        glProg.drawBlocks(st.fused);
        glProg.closed = true;
        ab[0].close();
        ab[1].close();
        Log.i("NICE_PIPELINE", "ARK fusion em=" + em[0] + "," + em[1] + "," + em[2] + "," + em[3] + " lw=" + lw[0] + "," + lw[1] + ","
                + lw[2] + "," + lw[3] + " r=" + radius + " eps=" + eps + " bracketDn=" + (st.lowDn != st.low ? strength : 0f)
                + " half=" + half.x + "x" + half.y + " ms=" + (System.currentTimeMillis() - started));
    }
}
