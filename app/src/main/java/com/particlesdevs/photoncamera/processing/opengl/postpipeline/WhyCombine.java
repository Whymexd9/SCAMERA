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
 * WHY tone, final step (tone_port.md 2.4 / 7.4): ef_guided_combine of the mod on every output pixel - gain from the
 * inverted fused luma, Bento ceiling and roll-off, OKLab macro contrast / vibrance / hue lock, AgX with the kernel's
 * Kraken matrices, display power 1/gamma and film toe - with the detail delta of the full-size Sabre merge added after
 * the tone (not multiplied by the shadow lift). The colour comes from whyLow (B-spline) on the 1x grid; on the 2x grid
 * from "whyMid", the 2x2 mean of the output grid, so colour edges stay sharp at 50 MP while the statistics, the fusion
 * and the delta reference stay on whyLow. Output: display-encoded RGB, alpha 1.
 */
public final class WhyCombine extends Node {
    public WhyCombine() {
        super("", "WhyCombine");
    }

    @Override public void Compile() {}

    /**
     * P340 (owner, 2026-10-25: pairs against LMC 9.6, ArkCam and the stock camera): the detail delta of the full-size merge x this,
     * by the finish SNR: 1 up to SNR 20, log-linear to 1.7 at SNR 150 and above (the owner's sunsets at SNR 527 / 683: 1.7, the
     * ISO 2900 night at SNR 39: 1.22). Host replay of the sunset: the finest band of the display luma 0.0072 -> 0.0101 (LMC 9.6
     * 0.0106 .. 0.016 on the same water and deck, the stock camera 0.030 .. 0.036), the 1 - 3 px band +20 %; noise is the denoise's
     * matter at low SNR, where it stays x1. 1 when the SNR is unknown.
     */
    static float detailBoost(float snr) {
        if (!(snr > 20f)) return 1f;   // NaN (unknown) included
        if (snr >= 150f) return 1.7f;
        return (float) (1.0 + 0.7 * Math.log(snr / 20.0) / Math.log(150.0 / 20.0));
    }

    /**
     * Bounded reference of the detail delta (review_whytone F1): box mean of min(m * Y, 1) on the whyLow grid (m = ae, or the
     * domain of WhyLumaSharpen). The unbounded delta of a light edge reached -0.7 in OKLab L and drew black rings around lamps.
     * P187: drawn here, or by WhyLumaSharpen right after its own read of the image when the image goes back early.
     */
    static GLTexture detailRef(com.particlesdevs.photoncamera.processing.opengl.GLProg glProg, PostPipeline pipeline, GLTexture input,
                               GLTexture gainMap, float detailClip) {
        WhyTone.State st = pipeline.why;
        GLTexture ref = new GLTexture(st.low.mSize, new GLFormat(GLFormat.DataType.FLOAT_32, 1), null,
                GL_NEAREST, GL_CLAMP_TO_EDGE);
        glProg.setDefine("DETAIL_REF", 1);
        glProg.useAssetProgram("why/low", false);
        glProg.setTexture("InputBuffer", input);
        WhyTone.setColour(glProg, pipeline, gainMap, st.inScale);
        glProg.setVar("factorU", st.factor);
        glProg.setVar("detailClipU", detailClip);
        glProg.drawBlocks(ref);
        return ref;
    }

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        WhyTone.State st = pipeline.why;
        if (st == null || st.ae == null || st.fused == null) throw new IllegalStateException("WHY tone: no fusion (WhyStats/WhyFusion did not run)");
        long started = System.currentTimeMillis();
        GLTexture input = previousNode.WorkingTexture;
        WhyAe.Result r = st.ae;
        GLTexture gainMap = pipeline.GainMap;
        GLTexture fallback = null;
        if (gainMap == null) {
            fallback = new GLTexture(new Point(1, 1), new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                    BufferUtils.getFrom(new float[]{1f, 1f, 1f, 1f}), GL_LINEAR, GL_CLAMP_TO_EDGE);
            gainMap = fallback;
        }
        // With WhyLumaSharpen (always in the pipeline since P118) the delta is WhyCam's own (cbrt S(Ya) - cbrt ref, x1);
        // the Sabre luminance with the former default gain 2 only if no sharpened luma arrived (defensive fallback).
        final boolean sharp = st.lumaS != null;
        final float sharpMul = st.sharpMul;
        final float detail = sharp ? WhyTone.value("sharp_gain", 1.0f) * detailBoost(pipeline.mParameters.finishSnr) : 2.0f;
        // P134 «Цвет»: COLOUR_LOOK only when a colour setting is off its default (else the shader before P134)
        final com.particlesdevs.photoncamera.processing.color.ColourLook look = WhyTone.colourLook();
        final boolean colourOn = !look.shaderNeutral();
        final int toneOp = WhyTone.toneOperator();
        // P134 «Плёночный LUT»: the chosen .cube on the display-encoded output (FILM_LUT), only with a strength above 0
        // P281: the film simulation (FilmSim) and the LUT as one table (FilmChain)
        final com.particlesdevs.photoncamera.processing.color.FilmChain.State chain = com.particlesdevs.photoncamera.processing.color.FilmChain.current();
        final float lutStrength = chain.strength;
        final String lutName = chain.key;
        final com.particlesdevs.photoncamera.processing.color.FilmLut lut = lutStrength > 0f && !lutName.isEmpty()
                ? com.particlesdevs.photoncamera.processing.color.FilmChain.lut(com.particlesdevs.photoncamera.app.ScameraApp.getAppContext(), lutName) : null;
        int lutTexture = 0;
        GLTexture hlA = null, hlB = null;
        try {
            GLTexture colour = st.lowDn != null ? st.lowDn : st.low;
            int colourFactor = st.factor;
            if (st.factor > 2) {
                // 2x grid: the 1x-equivalent colour source (verify_tone risk D).
                st.mid = new GLTexture(WhyTone.reduced(input.mSize, 2), new GLFormat(GLFormat.DataType.FLOAT_16, 4), null,
                        GL_NEAREST, GL_CLAMP_TO_EDGE);
                glProg.useAssetProgram("why/low", false);
                glProg.setTexture("InputBuffer", input);
                WhyTone.setColour(glProg, pipeline, gainMap, st.inScale);
                glProg.setVar("factorU", 2);
                glProg.drawBlocks(st.mid);
                colour = st.mid;
                colourFactor = 2;
            }
            if (detail == 0f && st.detailRef != null) { st.detailRef.close(); st.detailRef = null; }
            // P187: WhyLumaSharpen drew it already when it gave the full-size image back (above 100 MP)
            if (detail != 0f && st.detailRef == null) st.detailRef = detailRef(glProg, pipeline, input, gainMap, sharp ? st.sharpMul : r.ae);
            // P154: the highlight landing and the upward path to white (HL_LANDING 0 = the shader before P154)
            final float hlSoft = Math.max(0f, Math.min(0.9f, WhyTone.value("hl_soft", 0.7f)));
            final boolean realClip = ScamRgb.lastRealClip || ScamHybridBurst.lastBentoUsClipped > 0f;
            if (hlSoft > 0f && realClip) {
                // the blurred share of the ceiling on the whyLow grid (why/hlmask.glsl): max channel / ceiling, then a gaussian of
                // 1.2 % of the width along x and y
                final Point hlSize = st.low.mSize;
                hlA = new GLTexture(hlSize, new GLFormat(GLFormat.DataType.FLOAT_16, 1), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                hlB = new GLTexture(hlSize, new GLFormat(GLFormat.DataType.FLOAT_16, 1), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                // P277: of the whole frame's width on a lab fragment
                final float sigma = Math.max(1f, 0.012f * (pipeline.frozen != null && pipeline.frozen.lowWidth > 0 ? pipeline.frozen.lowWidth : hlSize.x));
                glProg.useAssetProgram("why/hlmask", false);
                glProg.setTexture("InputBuffer", st.low);
                // P216: the share of the REAL ceiling (Bento data above the capped clip): a window at 4-8 x white is not blown
                glProg.setVar("invClipU", 1f / Math.max(Math.max(r.clip, r.hr), 1e-6f));
                glProg.setVar("dirU", 0, 0);
                glProg.drawBlocks(hlB);
                glProg.useAssetProgram("why/hlmask", false);
                glProg.setTexture("InputBuffer", hlB);
                glProg.setVar("dirU", 1, 0);
                glProg.setVar("sigmaU", sigma);
                glProg.drawBlocks(hlA);
                glProg.useAssetProgram("why/hlmask", false);
                glProg.setTexture("InputBuffer", hlA);
                glProg.setVar("dirU", 0, 1);
                glProg.setVar("sigmaU", sigma);
                glProg.drawBlocks(hlB);
                // P187: hlA is only the middle step of the blur
                hlA.close();
                hlA = null;
            }
            if (hlB != null) glProg.setDefine("HL_LANDING", 1);
            // P46 Display P3: the same shader with P3_OUT 1 (colour source, OKLab and gamut in P3); sRGB sets no define.
            if (pipeline.p3Output) glProg.setDefine("P3_OUT", 1);
            if (colourOn) glProg.setDefine("COLOUR_LOOK", 1);
            // P144: ACES / Uchimura / the own curve instead of AgX (TONE_OP 0, the AgX shader, otherwise)
            if (toneOp != com.particlesdevs.photoncamera.processing.color.ToneCurve.AGX) glProg.setDefine("TONE_OP", 1);
            if (lut != null) glProg.setDefine("FILM_LUT", 1);
            // P216: real Bento headroom above the capped clip (without it the shader is the one before P216, token for token)
            if (r.hr > 0f) glProg.setDefine("HEADROOM_WIDE", 1);
            glProg.useAssetProgram("why/combine", false);
            if (lut != null) {
                lutTexture = lut.upload();
                glProg.setTexture3D("FilmLut", lutTexture);
                glProg.setVar("lutU", lutStrength, (float) lut.size);
                glProg.setVar("lutMinU", lut.domainMin);
                glProg.setVar("lutScaleU", 1f / (lut.domainMax[0] - lut.domainMin[0]), 1f / (lut.domainMax[1] - lut.domainMin[1]),
                        1f / (lut.domainMax[2] - lut.domainMin[2]));
            }
            // P187: with WhyCam's sharpened luma (sharpU 1) the shader reads nothing of InputBuffer but its size; once the
            // image is given back, the same-size lumaS stands in (the image's texture name may be another texture's now)
            if (st.inputReleased && !sharp) throw new IllegalStateException("WHY combine: the full-size image was released without a sharpened luma");
            glProg.setTexture("InputBuffer", st.inputReleased ? st.lumaS : input);
            WhyTone.setColour(glProg, pipeline, gainMap, st.inScale);
            glProg.setTexture("WhyLow", st.low);
            glProg.setTexture("WhyColour", colour);
            glProg.setTexture("WhyFused", st.fused);
            glProg.setTexture("WhyDetailRef", st.detailRef != null ? st.detailRef : st.low);
            glProg.setVar("detailRefU", st.detailRef != null ? 1 : 0);
            glProg.setTexture("WhyLumaS", sharp ? st.lumaS : st.low);
            if (hlB != null) glProg.setTexture("WhyHl", hlB);
            glProg.setVar("sharpU", sharp ? 1 : 0);
            glProg.setVar("sharpToneU", sharp ? r.ae / Math.max(st.sharpMul, 1e-6f) : 1f);
            glProg.setVar("fU", st.factor);
            glProg.setVar("colourFU", colourFactor);
            glProg.setVar("signedColourU", pipeline.signedRgb ? 1 : 0);
            glProg.setVar("aeU", r.ae);
            glProg.setVar("clipU", r.clip);
            // P216: real Bento headroom above the capped clip - WhyCam 1.58's shoulder, the landing on the real ceiling and the
            // highlight detail (not when the full-size image is gone: InputBuffer is then the sharpened luma)
            if (r.hr > 0f) {
                final float[] hrTone = WhyTone.headroomTone();
                glProg.setVar("hrU", r.hr);
                glProg.setVar("hrKneeU", hrTone[0]);
                glProg.setVar("hrRoomU", hrTone[1]);
                glProg.setVar("hrDetailU", st.inputReleased ? 0f : hrTone[2]);
            }
            // P11: the Bento ceiling / roll-off only with real headroom; saturated light fades towards white in the shoulder.
            glProg.setVar("headroomU", ScamRgb.lastRealClip || ScamHybridBurst.lastBentoUsClipped > 0f ? 1 : 0);
            glProg.setVar("hlWhiteU", Math.max(0f, Math.min(1f, WhyTone.value("hl_white", 0.15f))));
            // P154 «Мягкость засветов»: the landing of blown light on the white point starts this far below the ceiling
            if (hlB != null) glProg.setVar("hlSoftU", hlSoft);
            glProg.setVar("toeU", WhyTone.value("aces_toe", 0.05f));
            glProg.setVar("gammaInvU", 1f / WhyTone.gamma());
            glProg.setVar("macroU", WhyTone.value("macro_contrast", 1.1f));
            glProg.setVar("vibU", WhyTone.value("vibrance", 0f), WhyTone.value("vibrance_sky", 0.40f), WhyTone.value("vibrance_green", 0.20f));
            glProg.setVar("vibHlU", WhyTone.vibranceHl());
            glProg.setVar("chromaDnU", WhyFusion.whyDenoise(basePipeline.mParameters) ? WhyTone.value("chroma_denoise", 0f) : 0f);
            glProg.setVar("clarityU", WhyTone.value("clarity", 0f));
            glProg.setVar("flatProtectU", WhyTone.value("flat_protect", 0f));
            glProg.setVar("detailGainU", detail);
            glProg.setVar("deltaChromaU", Math.max(0f, Math.min(1f, WhyTone.value("delta_chroma", 1f))));
            glProg.setVar("filmToeU", WhyTone.value("film_toe", 0.10f));
            glProg.setVar("agxAU", r.slope, r.sp, r.tp, r.sat);
            glProg.setVar("agxBU", r.minEv, r.maxEv, r.ev,
                    toneOp == com.particlesdevs.photoncamera.processing.color.ToneCurve.AGX ? (float) r.look : 0f);
            if (toneOp != com.particlesdevs.photoncamera.processing.color.ToneCurve.AGX) {
                glProg.setVar("toneOpU", toneOp);
                glProg.setVar("acesU", WhyTone.acesUniform());
                float[][] uchi = WhyTone.uchimuraUniforms();
                glProg.setVar("uchiAU", uchi[0]);
                glProg.setVar("uchiBU", uchi[1]);
                glProg.setVarVec4s("curveU", WhyTone.curveSamples());
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
            // P187: above 100 MP the 1.6 GB output is made only after the GPU has finished with the fusion's and the mask's
            // temporaries (their storage is freed when the GPU is done with them)
            if (st.inputReleased) android.opengl.GLES30.glFinish();
            WorkingTexture = pipeline.getMain();
            glProg.drawBlocks(WorkingTexture);
            glProg.closed = true;
        } finally {
            st.closeTextures();
            if (fallback != null) fallback.close();
            if (lutTexture != 0) android.opengl.GLES30.glDeleteTextures(1, new int[]{lutTexture}, 0);
            if (hlA != null) hlA.close();
            if (hlB != null) hlB.close();
        }
        Log.i("SCAM_PIPELINE", "WHY combine ae=" + r.ae + " clip=" + r.clip + (r.hr > 0f ? " hr=" + r.hr : "") + " headroom=" + (ScamRgb.lastRealClip || ScamHybridBurst.lastBentoUsClipped > 0f)
                + " hlWhite=" + WhyTone.value("hl_white", 0.15f) + " hlSoft=" + WhyTone.value("hl_soft", 0.7f) + " grid=" + input.mSize.x + "x" + input.mSize.y
                + " lowFactor=" + st.factor + (st.factor > 2 ? " colour=whyMid" : " colour=whyLow") + " detail=" + detail
                + (sharp ? " (WhyLumaS, mul " + sharpMul + ")" : " (Sabre Y, bounded ref)")
                + (pipeline.p3Output ? " output=DisplayP3" : "") + (colourOn ? " colourLook" : "")
                + (lut != null ? " filmLut=" + lutName + " " + lut.size + "^3 x" + lutStrength : lutStrength > 0f && !lutName.isEmpty() ? " filmLut=" + lutName + " unreadable" : "") + " ms=" + (System.currentTimeMillis() - started));
    }
}
