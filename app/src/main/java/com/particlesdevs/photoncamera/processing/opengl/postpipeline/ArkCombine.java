package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
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
 * and the delta reference stay on arkLow. Output: display-encoded RGB, alpha 1.
 */
public final class ArkCombine extends Node {
    public ArkCombine() {
        super("", "ArkCombine");
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
        // With ArkLumaSharpen (always in the pipeline since P118) the delta is ArkCam's own (cbrt S(Ya) - cbrt ref, x1);
        // the Sabre luminance with the former default gain 2 only if no sharpened luma arrived (defensive fallback).
        final boolean sharp = st.lumaS != null;
        final float sharpMul = st.sharpMul;
        final float detail = sharp ? ArkTone.value("sharp_gain", 1.0f) : 2.0f;
        // P134 «Цвет»: COLOUR_LOOK only when a colour setting is off its default (else the shader before P134)
        final com.particlesdevs.photoncamera.processing.color.ColourLook look = ArkTone.colourLook();
        final boolean colourOn = !look.shaderNeutral();
        final int toneOp = ArkTone.toneOperator();
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
            if (detail != 0f) {
                // Bounded reference of the detail delta (review_arktone F1): box mean of min(m * Y, 1) on the arkLow
                // grid (m = ae, or the domain of ArkLumaSharpen). The unbounded delta of a light edge reached -0.7 in
                // OKLab L and drew black rings around lamps.
                st.detailRef = new GLTexture(st.low.mSize, new GLFormat(GLFormat.DataType.FLOAT_32, 1), null,
                        GL_NEAREST, GL_CLAMP_TO_EDGE);
                glProg.setDefine("DETAIL_REF", 1);
                glProg.useAssetProgram("ark/low", false);
                glProg.setTexture("InputBuffer", input);
                ArkTone.setColour(glProg, pipeline, gainMap, st.inScale);
                glProg.setVar("factorU", st.factor);
                glProg.setVar("detailClipU", sharp ? st.sharpMul : r.ae);
                glProg.drawBlocks(st.detailRef);
            }
            // P46 Display P3: the same shader with P3_OUT 1 (colour source, OKLab and gamut in P3); sRGB sets no define.
            if (pipeline.p3Output) glProg.setDefine("P3_OUT", 1);
            if (colourOn) glProg.setDefine("COLOUR_LOOK", 1);
            // P144: ACES / Uchimura / the own curve instead of AgX (TONE_OP 0, the AgX shader, otherwise)
            if (toneOp != com.particlesdevs.photoncamera.processing.color.ToneCurve.AGX) glProg.setDefine("TONE_OP", 1);
            glProg.useAssetProgram("ark/combine", false);
            glProg.setTexture("InputBuffer", input);
            ArkTone.setColour(glProg, pipeline, gainMap, st.inScale);
            glProg.setTexture("ArkLow", st.low);
            glProg.setTexture("ArkColour", colour);
            glProg.setTexture("ArkFused", st.fused);
            glProg.setTexture("ArkDetailRef", st.detailRef != null ? st.detailRef : st.low);
            glProg.setVar("detailRefU", st.detailRef != null ? 1 : 0);
            glProg.setTexture("ArkLumaS", sharp ? st.lumaS : st.low);
            glProg.setVar("sharpU", sharp ? 1 : 0);
            glProg.setVar("sharpToneU", sharp ? r.ae / Math.max(st.sharpMul, 1e-6f) : 1f);
            glProg.setVar("fU", st.factor);
            glProg.setVar("colourFU", colourFactor);
            glProg.setVar("signedColourU", pipeline.signedRgb ? 1 : 0);
            glProg.setVar("aeU", r.ae);
            glProg.setVar("clipU", r.clip);
            // P11: the Bento ceiling / roll-off only with real headroom; saturated light fades towards white in the shoulder.
            glProg.setVar("headroomU", ScamRgb.lastRealClip || ScamHybridBurst.lastBentoUsClipped > 0f ? 1 : 0);
            glProg.setVar("hlWhiteU", Math.max(0f, Math.min(1f, ArkTone.value("hl_white", 0.15f))));
            glProg.setVar("toeU", ArkTone.value("aces_toe", 0.05f));
            glProg.setVar("gammaInvU", 1f / ArkTone.gamma());
            glProg.setVar("macroU", ArkTone.value("macro_contrast", 1.1f));
            glProg.setVar("vibU", ArkTone.value("vibrance", 0f), ArkTone.value("vibrance_sky", 0.40f), ArkTone.value("vibrance_green", 0.20f));
            glProg.setVar("chromaDnU", ArkFusion.arkDenoise() ? ArkTone.value("chroma_denoise", 0f) : 0f);
            glProg.setVar("clarityU", ArkTone.value("clarity", 0f));
            glProg.setVar("flatProtectU", ArkTone.value("flat_protect", 0f));
            glProg.setVar("detailGainU", detail);
            glProg.setVar("deltaChromaU", Math.max(0f, Math.min(1f, ArkTone.value("delta_chroma", 1f))));
            glProg.setVar("filmToeU", ArkTone.value("film_toe", 0.10f));
            glProg.setVar("agxAU", r.slope, r.sp, r.tp, r.sat);
            glProg.setVar("agxBU", r.minEv, r.maxEv, r.ev,
                    toneOp == com.particlesdevs.photoncamera.processing.color.ToneCurve.AGX ? (float) r.look : 0f);
            if (toneOp != com.particlesdevs.photoncamera.processing.color.ToneCurve.AGX) {
                glProg.setVar("toneOpU", toneOp);
                glProg.setVar("acesU", ArkTone.acesUniform());
                float[][] uchi = ArkTone.uchimuraUniforms();
                glProg.setVar("uchiAU", uchi[0]);
                glProg.setVar("uchiBU", uchi[1]);
                glProg.setVarVec4s("curveU", ArkTone.curveSamples());
            }
            glProg.setVar("ditherU", 1);
            if (colourOn) {
                glProg.setVar("skinU", look.skinUniform());
                glProg.setVar("vibLU", look.vibUniform());
                glProg.setVarFloats("mixU", look.mixUniform());
                glProg.setVar("mixOnU", look.mixerOn() ? 1 : 0);
                glProg.setVar("monoU", look.monoUniform());
                glProg.setVar("gamutU", Math.max(0f, Math.min(1f, look.gamut / 100f)));
                glProg.setVar("hueLockU", look.hueLock);
                float[][] grade = look.gradeUniforms();
                glProg.setVar("gradeAU", grade[0]);
                glProg.setVar("gradeBU", grade[1]);
            }
            WorkingTexture = pipeline.getMain();
            glProg.drawBlocks(WorkingTexture);
            glProg.closed = true;
        } finally {
            st.closeTextures();
            if (fallback != null) fallback.close();
        }
        Log.i("SCAM_PIPELINE", "ARK combine ae=" + r.ae + " clip=" + r.clip + " headroom=" + (ScamRgb.lastRealClip || ScamHybridBurst.lastBentoUsClipped > 0f)
                + " hlWhite=" + ArkTone.value("hl_white", 0.15f) + " grid=" + input.mSize.x + "x" + input.mSize.y
                + " lowFactor=" + st.factor + (st.factor > 2 ? " colour=arkMid" : " colour=arkLow") + " detail=" + detail
                + (sharp ? " (ArkLumaS, mul " + sharpMul + ")" : " (Sabre Y, bounded ref)")
                + (pipeline.p3Output ? " output=DisplayP3" : "") + (colourOn ? " colourLook" : "") + " ms=" + (System.currentTimeMillis() - started));
    }
}
