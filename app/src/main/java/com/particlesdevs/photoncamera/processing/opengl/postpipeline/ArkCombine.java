package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLDrawParams;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.BufferUtils;
import com.particlesdevs.photoncamera.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * ARK tone, final step (tone_port.md 2.4 / 7.4): ef_guided_combine of the mod on every output pixel - gain from the
 * inverted fused luma, Bento ceiling and roll-off, OKLab macro contrast / vibrance / hue lock, AgX with the kernel's
 * Kraken matrices, display power 1/gamma and film toe - with the detail delta of the full-size Sabre merge added after
 * the tone (not multiplied by the shadow lift). The colour comes from arkLow (B-spline) on the 1x grid; on the 2x grid
 * from "arkMid", the 2x2 mean of the output grid, so colour edges stay sharp at 50 MP while the statistics, the fusion
 * and the delta reference stay on arkLow. Output: display-encoded RGB; alpha = sharpening weight for ArkSharpenGuard
 * when a guard follows (the image is then written to main3, which the sharpeners leave alone), else 1.
 */
public final class ArkCombine extends Node {
    private final float guard;

    /** @param guard exponent of the sharpening weight gain^-guard (0: no ArkSharpenGuard follows, alpha 1). */
    public ArkCombine(float guard) {
        super("", "ArkCombine");
        this.guard = Math.max(0f, guard);
    }

    @Override public void Compile() {}

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        ArkTone.State st = pipeline.ark;
        if (st == null || st.ae == null || st.fused == null) throw new IllegalStateException("ARK tone: no fusion (ArkStats/ArkFusion did not run)");
        long started = System.currentTimeMillis();
        GLTexture input = previousNode.WorkingTexture;
        ArkAe.Result r = st.ae;
        GLTexture gainMap = pipeline.GainMap;
        GLTexture fallback = null;
        if (gainMap == null) {
            fallback = new GLTexture(new Point(1, 1), new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                    BufferUtils.getFrom(new float[]{1f, 1f, 1f, 1f}), GL_LINEAR, GL_CLAMP_TO_EDGE);
            gainMap = fallback;
        }
        try {
            GLTexture colour = st.lowDn != null ? st.lowDn : st.low;
            int colourFactor = st.factor;
            if (st.factor > 2) {
                // 2x grid: the 1x-equivalent colour source (verify_tone risk D).
                st.mid = new GLTexture(ArkTone.reduced(input.mSize, 2), new GLFormat(GLFormat.DataType.FLOAT_16, 4), null,
                        GL_NEAREST, GL_CLAMP_TO_EDGE);
                glProg.useAssetProgram("ark/low", false);
                glProg.setTexture("InputBuffer", input);
                ArkTone.setColour(glProg, pipeline, gainMap, st.inScale);
                glProg.setVar("factorU", 2);
                glProg.drawBlocks(st.mid);
                colour = st.mid;
                colourFactor = 2;
            }
            float detail = ArkTone.value("detail_gain", 2.0f);
            if (detail != 0f) {
                // Bounded reference of the detail delta (review_arktone F1): box mean of min(ae * Y, 1) on the arkLow
                // grid. The unbounded delta of a light edge reached -0.7 in OKLab L and drew black rings around lamps.
                st.detailRef = new GLTexture(st.low.mSize, new GLFormat(GLFormat.DataType.FLOAT_32, 1), null,
                        GL_NEAREST, GL_CLAMP_TO_EDGE);
                glProg.setDefine("DETAIL_REF", 1);
                glProg.useAssetProgram("ark/low", false);
                glProg.setTexture("InputBuffer", input);
                ArkTone.setColour(glProg, pipeline, gainMap, st.inScale);
                glProg.setVar("factorU", st.factor);
                glProg.setVar("detailClipU", r.ae);
                glProg.drawBlocks(st.detailRef);
            }
            glProg.useAssetProgram("ark/combine", false);
            glProg.setTexture("InputBuffer", input);
            ArkTone.setColour(glProg, pipeline, gainMap, st.inScale);
            glProg.setTexture("ArkLow", st.low);
            glProg.setTexture("ArkColour", colour);
            glProg.setTexture("ArkFused", st.fused);
            glProg.setTexture("ArkDetailRef", st.detailRef != null ? st.detailRef : st.low);
            glProg.setVar("detailRefU", st.detailRef != null ? 1 : 0);
            glProg.setVar("fU", st.factor);
            glProg.setVar("colourFU", colourFactor);
            glProg.setVar("aeU", r.ae);
            glProg.setVar("clipU", r.clip);
            glProg.setVar("toeU", ArkTone.value("aces_toe", 0.05f));
            glProg.setVar("gammaInvU", 1f / ArkTone.gamma());
            glProg.setVar("macroU", ArkTone.value("macro_contrast", 1.1f));
            glProg.setVar("vibU", ArkTone.value("vibrance", 0f), ArkTone.value("vibrance_sky", 0.40f), ArkTone.value("vibrance_green", 0.20f));
            glProg.setVar("chromaDnU", ArkTone.value("chroma_denoise", 0f));
            glProg.setVar("clarityU", ArkTone.value("clarity", 0f));
            glProg.setVar("flatProtectU", ArkTone.value("flat_protect", 0f));
            glProg.setVar("detailGainU", detail);
            glProg.setVar("filmToeU", ArkTone.value("film_toe", 0.10f));
            glProg.setVar("agxAU", r.slope, r.sp, r.tp, r.sat);
            glProg.setVar("agxBU", r.minEv, r.maxEv, r.ev, (float) r.look);
            glProg.setVar("ditherU", 1);
            final boolean keep = guard > 0f && pipeline.main3 != input;
            glProg.setVar("guardU", keep ? guard : 0f);
            if (keep) {
                // Keep the toned image for the guard: the sharpeners only ping-pong main1/main2.
                if (pipeline.main3 == null || !pipeline.main3.mSize.equals(input.mSize)) {
                    if (pipeline.main3 != null) pipeline.main3.close();
                    pipeline.main3 = new GLTexture(input.mSize, new GLFormat(GLFormat.DataType.FLOAT_16, GLDrawParams.WorkDim), null,
                            GL_LINEAR, GL_CLAMP_TO_EDGE);
                }
                WorkingTexture = pipeline.main3;
            } else {
                WorkingTexture = pipeline.getMain();
            }
            st.guard = keep ? guard : 0f;
            st.preSharpen = keep ? WorkingTexture : null;
            glProg.drawBlocks(WorkingTexture);
            glProg.closed = true;
        } finally {
            st.closeTextures();
            if (fallback != null) fallback.close();
        }
        Log.i("NICE_PIPELINE", "ARK combine ae=" + r.ae + " clip=" + r.clip + " grid=" + input.mSize.x + "x" + input.mSize.y
                + " lowFactor=" + st.factor + (st.factor > 2 ? " colour=arkMid" : " colour=arkLow") + " detail=" + ArkTone.value("detail_gain", 2.0f)
                + " (bounded ref)"
                + " guard=" + guard + " ms=" + (System.currentTimeMillis() - started));
    }
}
