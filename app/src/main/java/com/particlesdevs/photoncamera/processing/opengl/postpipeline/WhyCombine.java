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
     * The detail gain of the sharpened luma: «sharp_gain» x {@link #detailBoost}. P369 (texture-detail-boost): with a film (any, B10)
     * the boost - our look, not the film's - only on the share the simulation's strength leaves (none at 100 %: the film gets the
     * merge's own detail); without one the expression as before, bit for bit.
     */
    static float detailGain(float sharpGain, float snr, @androidx.annotation.Nullable com.particlesdevs.photoncamera.processing.color.FilmSim.Settings film) {
        return film == null ? sharpGain * detailBoost(snr) : sharpGain * (1f + (detailBoost(snr) - 1f) * (1f - film.strength));
    }

    /** P154 «Мягкость засветов»: where the highlight landing starts below the data ceiling (fraction 0 .. 0.9; 0 = off). */
    static float hlSoft() {
        return Math.max(0f, Math.min(0.9f, WhyTone.value("hl_soft", 0.7f)));
    }

    /**
     * P378: whether this shot's highlight landing can be drawn - «Мягкость засветов» on and no film at 100 % (Run's filmOnly) - for
     * ScamRgb, which skips the opposed landing's map otherwise. A film at 100 % whose data cannot be read gets the landing without
     * the map (HL_OPPOSED off: the landing as before P378).
     */
    static boolean landingDrawn(PostPipeline pipeline) {
        return landingDrawn(hlSoft(), pipeline.film);
    }

    /** P378: {@link #landingDrawn(PostPipeline)} of «Мягкость засветов» {@code hlSoft} and the shot's film snapshot. */
    static boolean landingDrawn(float hlSoft, @androidx.annotation.Nullable com.particlesdevs.photoncamera.processing.color.FilmSim.Settings film) {
        return hlSoft > 0f && !(film != null && film.strength >= 1f);
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
        // P369: the shot's film snapshot (PostPipeline.takeFilm); null: no film, every expression as before
        final com.particlesdevs.photoncamera.processing.color.FilmSim.Settings film = pipeline.film;
        final float detail = sharp ? detailGain(WhyTone.value("sharp_gain", 1.0f), pipeline.mParameters.finishSnr, film) : 2.0f;
        // P134 «Цвет»: COLOUR_LOOK only when a colour setting is off its default (else the shader before P134)
        final com.particlesdevs.photoncamera.processing.color.ColourLook look = WhyTone.colourLook(pipeline);
        final boolean colourOn = !look.shaderNeutral();
        final int toneOp = WhyTone.toneOperator();
        // P393: the light tones of a saturated colour keep its mid-tone chroma (LIGHT_CHROMA; AgX only, the other operators as before)
        final float lightChroma = toneOp == com.particlesdevs.photoncamera.processing.color.ToneCurve.AGX ? WhyTone.lightChroma() : 0f;
        // P369: with a film the scene-referred table (FILM_SIM: the film fed the exposed scene light, the chosen LUT on its print);
        // a film whose data cannot be read gives the photo no film (never the display table)
        final com.particlesdevs.photoncamera.processing.color.FilmChain.Scene scene = film == null ? null
                : com.particlesdevs.photoncamera.processing.color.FilmChain.scene(com.particlesdevs.photoncamera.app.ScameraApp.getAppContext(),
                film, pipeline.filmUserLut, pipeline.filmUserStrength);
        // P369: the film alone at 100 % - our highlight landing and sun bloom (only in our share) are not drawn
        final boolean filmOnly = scene != null && film.strength >= 1f;
        final float filmExp = WhyAe.filmAe(r) / Math.max(r.ae, 1e-6f) * com.particlesdevs.photoncamera.processing.color.FilmSim.METER_TO_FILM;
        // P134 «Плёночный LUT»: the chosen .cube on the display-encoded output (FILM_LUT), only with a strength above 0
        // P281: the film simulation (FilmSim) and the LUT as one table (FilmChain); P369: without a film only (of the snapshot)
        final com.particlesdevs.photoncamera.processing.color.FilmChain.State chain = scene != null ? null
                : com.particlesdevs.photoncamera.processing.color.FilmChain.current(null, pipeline.filmUserLut, pipeline.filmUserStrength);
        final float lutStrength = chain != null ? chain.strength : 0f;
        final String lutName = chain != null ? chain.key : "";
        final com.particlesdevs.photoncamera.processing.color.FilmLut lut = lutStrength > 0f && !lutName.isEmpty()
                ? com.particlesdevs.photoncamera.processing.color.FilmChain.lut(com.particlesdevs.photoncamera.app.ScameraApp.getAppContext(), lutName) : null;
        int lutTexture = 0;
        GLTexture hlA = null, hlB = null, sunBloom = null, oppShare = null, skyWith = null, skyWithout = null;
        boolean sunBloomOn = false, oppOn = false;
        String skyLog = "";
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
            final float hlSoft = hlSoft();
            final boolean realClip = ScamRgb.lastRealClip || ScamHybridBurst.lastBentoUsClipped > 0f;
            if (hlSoft > 0f && realClip && !filmOnly) {
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
            // P378: the low-chroma glow round a light «Inpaint opposed» rebuilt (ScamRgb.opposedShare: none without a filled block)
            // lands on white, with the landing only and on the image the map covers
            oppShare = pipeline.opposedShare;
            pipeline.opposedShare = null;
            final Point oppSize = pipeline.opposedShareSize;
            if (oppShare != null && (hlB == null || oppSize == null || oppSize.x * input.mSize.y != oppSize.y * input.mSize.x)) {
                oppShare.close();
                oppShare = null;
            }
            if (oppShare != null) glProg.setDefine("HL_OPPOSED", 1);
            oppOn = oppShare != null;
            // P46 Display P3: the same shader with P3_OUT 1 (colour source, OKLab and gamut in P3); sRGB sets no define.
            if (pipeline.p3Output) glProg.setDefine("P3_OUT", 1);
            if (colourOn) glProg.setDefine("COLOUR_LOOK", 1);
            // P144: ACES / Uchimura / the own curve instead of AgX (TONE_OP 0, the AgX shader, otherwise)
            if (toneOp != com.particlesdevs.photoncamera.processing.color.ToneCurve.AGX) glProg.setDefine("TONE_OP", 1);
            if (lut != null) glProg.setDefine("FILM_LUT", 1);
            // P369: the film's scene feed (never with FILM_LUT: the chosen LUT is in the scene table then)
            if (scene != null) glProg.setDefine("FILM_SIM", 1);
            // P216: real Bento headroom above the capped clip (without it the shader is the one before P216, token for token)
            if (r.hr > 0f) glProg.setDefine("HEADROOM_WIDE", 1);
            // P349: the sun bloom round fully clipped cores (ScamRgb.sunBloom), on the image those flags covered
            sunBloom = pipeline.sunBloom;
            pipeline.sunBloom = null;
            final Point bloomSize = pipeline.sunBloomSize;
            if (sunBloom != null && (filmOnly || bloomSize == null || bloomSize.x * input.mSize.y != bloomSize.y * input.mSize.x)) {
                sunBloom.close();
                sunBloom = null;
            }
            if (sunBloom != null) glProg.setDefine("SUN_BLOOM", 1);
            sunBloomOn = sunBloom != null;
            // P378: the sky term only with a sky mask (WhySky; none: the shader before P378 token for token)
            if (st.sky != null) glProg.setDefine("SKY", 1);
            // window burst (a room 7 EV under the window crushed to black): the macro contrast below its pivot as a power
            if (WhyTone.macroToe()) glProg.setDefine("MACRO_TOE", 1);
            // P393: a saturated colour's light tones under the AgX white keep its mid-tone chroma (0: the shader before, token for token)
            if (lightChroma > 0f) glProg.setDefine("LIGHT_CHROMA", 1);
            skyLog = st.sky == null ? "" : " sky=" + Math.round(100f * st.skyMask.coverage) + "%" + (st.skyMask.keep < 1f
                    ? " backlit (level x" + Math.round(100f * st.skyMask.keep) / 100f + ")" : "");
            // P378 review: Ultra HDR keeps the sky term's change - the combine at whyLow with and without it (skyHdrRatio), its
            // targets made before the combine's samplers are bound: RGBA8 (the output is display-encoded; review: two RGBA16F were
            // 802 MB at 200 MP), none above 100 MP (P187's window for the output: the gain map takes the scene as before there)
            if (st.sky != null && st.skyMask.keep > 0f && pipeline.captureDemosaic && !filmOnly) {
                if (ScamRgb.earlyRelease(input.mSize)) skyLog += " hdrSkyRatio=skipped (over 100 MP)";
                else {
                    skyWith = new GLTexture(st.low.mSize, new GLFormat(GLFormat.DataType.SIMPLE_8, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                    skyWithout = new GLTexture(st.low.mSize, new GLFormat(GLFormat.DataType.SIMPLE_8, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                }
            }
            // P187: above 100 MP the 1.6 GB output is made only after the GPU has finished with the fusion's and the mask's
            // temporaries (their storage is freed when the GPU is done with them). Made before the combine's samplers are bound:
            // a new texture binds itself on unit 1 + its name, which may be a sampler's unit (the pass then read its own output).
            if (st.inputReleased) android.opengl.GLES30.glFinish();
            WorkingTexture = pipeline.getMain();
            glProg.useAssetProgram("why/combine", false);
            if (sunBloom != null) {
                final int block = Math.max(1, pipeline.sunBloomBlock);
                glProg.setTexture("SunBloom", sunBloom);
                glProg.setVar("sunGridU", bloomSize.x / (float) (sunBloom.mSize.x * block), bloomSize.y / (float) (sunBloom.mSize.y * block));
                glProg.setVar("sunBloomU", WhyTone.sunBloom()[0]);
            }
            if (oppShare != null) {
                final int block = Math.max(1, pipeline.opposedShareBlock);
                glProg.setTexture("OppShare", oppShare);
                glProg.setVar("oppGridU", oppSize.x / (float) (oppShare.mSize.x * block), oppSize.y / (float) (oppShare.mSize.y * block));
            }
            if (lut != null) {
                lutTexture = lut.upload();
                glProg.setTexture3D("FilmLut", lutTexture);
                glProg.setVar("lutU", lutStrength, (float) lut.size);
                glProg.setVar("lutMinU", lut.domainMin);
                glProg.setVar("lutScaleU", 1f / (lut.domainMax[0] - lut.domainMin[0]), 1f / (lut.domainMax[1] - lut.domainMin[1]),
                        1f / (lut.domainMax[2] - lut.domainMin[2]));
            }
            if (scene != null) {
                // P369: the film's exposure is the meter's before the highlight overflow's cut (ae x deficit, B1); the noise
                // clamp and the Bento hold stay in ae
                lutTexture = scene.lut.upload();
                glProg.setTexture3D("FilmLut", lutTexture);
                glProg.setVar("lutU", scene.strength, (float) scene.lut.size);
                glProg.setVar("filmExpU", filmExp);
                glProg.setVar("filmLogU", com.particlesdevs.photoncamera.processing.color.FilmSim.LOG_LO,
                        com.particlesdevs.photoncamera.processing.color.FilmSim.LOG_SPAN);
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
            if (st.sky != null) {
                // P378: L2 / L3 keep the backlit sky's level (owner: «наш тонмапинг не должен затемнять небо в контровых сценах»);
                // C1, M1 and L4 act on every sky
                final float sky = WhyTone.sky();
                final float[] tone = WhyTone.skyTone(), tops = st.skyMask.tops();
                glProg.setTexture("WhySky", st.sky);
                glProg.setVar("skyU", sky * st.skyMask.keep, tone[0], tone[1], tone[2]);
                glProg.setVar("skyMU", sky, sky, sky * WhyTone.SKY_GLOW, WhyTone.SKY_VIBRANCE);
                glProg.setVar("skyTopU", tops[0], tops[1]);
            }
            glProg.setVar("sharpU", sharp ? 1 : 0);
            glProg.setVar("fullInputU", st.inputReleased ? 0 : 1);   // P349: the bright rim reads the full-size merge
            glProg.setVar("edgeHoldU", WhyTone.edgeHold());          // P394: smooth edges (the full-size merge's range, photo only)
            glProg.setVar("sharpToneU", sharp ? r.ae / Math.max(st.sharpMul, 1e-6f) : 1f);
            glProg.setVar("fU", st.factor);
            glProg.setVar("colourFU", colourFactor);
            glProg.setVar("signedColourU", pipeline.signedRgb ? 1 : 0);
            glProg.setVar("signedHueU", WhyTone.signedHue() ? 1 : 0);   // P370 (B2): a colour outside sRGB keeps its hue
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
            if (lightChroma > 0f) glProg.setVar("lightChromaU", lightChroma);
            // P154 «Мягкость засветов»: the landing of blown light on the white point starts this far below the ceiling
            if (hlB != null) glProg.setVar("hlSoftU", hlSoft);
            glProg.setVar("toeU", WhyTone.value("aces_toe", 0.05f));
            glProg.setVar("gammaInvU", 1f / WhyTone.gamma());
            glProg.setVar("macroU", WhyTone.value("macro_contrast", 1.1f));
            glProg.setVar("vibU", WhyTone.value("vibrance", 0f), WhyTone.value("vibrance_sky", 0.40f), WhyTone.value("vibrance_green", 0.20f));
            glProg.setVar("vibHlU", WhyTone.vibranceHl());
            glProg.setVar("vibHlShapeU", WhyTone.vibranceHlShape());   // P393: no cast doubled on pale mid-light tones
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
            glProg.drawBlocks(WorkingTexture);
            if (skyWith != null) {
                try {
                    skyHdrRatio(pipeline, st, r, skyWith, skyWithout, lightChroma > 0f);
                    skyLog += pipeline.skyHdrRatio != null ? " hdrSkyRatio=" + pipeline.skyHdrRatioSize.x + "x" + pipeline.skyHdrRatioSize.y : "";
                } catch (RuntimeException e) {
                    // the photo stands; its gain map takes the scene as before
                    pipeline.skyHdrRatio = null;
                    pipeline.skyHdrRatioSize = null;
                    Log.w("SCAM_PIPELINE", "WHY combine: the sky's Ultra HDR ratio failed (" + e + ")");
                }
            }
            glProg.closed = true;
        } finally {
            if (skyWith != null) skyWith.close();
            if (skyWithout != null) skyWithout.close();
            st.closeTextures();
            if (fallback != null) fallback.close();
            if (lutTexture != 0) android.opengl.GLES30.glDeleteTextures(1, new int[]{lutTexture}, 0);
            if (hlA != null) hlA.close();
            if (hlB != null) hlB.close();
            if (sunBloom != null) sunBloom.close();
            if (oppShare != null) oppShare.close();
        }
        Log.i("SCAM_PIPELINE", "WHY combine ae=" + r.ae + " clip=" + r.clip + (r.hr > 0f ? " hr=" + r.hr : "") + " headroom=" + (ScamRgb.lastRealClip || ScamHybridBurst.lastBentoUsClipped > 0f)
                + " hlWhite=" + WhyTone.value("hl_white", 0.15f) + " hlSoft=" + WhyTone.value("hl_soft", 0.7f)
                + (lightChroma > 0f ? " lightChroma=" + lightChroma : "") + " grid=" + input.mSize.x + "x" + input.mSize.y
                + " lowFactor=" + st.factor + (st.factor > 2 ? " colour=whyMid" : " colour=whyLow") + " detail=" + detail
                + (sharp ? " (WhyLumaS, mul " + sharpMul + ")" : " (Sabre Y, bounded ref)")
                + (sunBloomOn ? " sunBloom=" + WhyTone.sunBloom()[0] + "@" + WhyTone.sunBloom()[1] : "")
                + (oppOn ? " opposedLanding" : "") + skyLog
                + (pipeline.p3Output ? " output=DisplayP3" : "") + (colourOn ? " colourLook" : "")
                + (lut != null ? " filmLut=" + lutName + " " + lut.size + "^3 x" + lutStrength : lutStrength > 0f && !lutName.isEmpty() ? " filmLut=" + lutName + " unreadable" : "")
                + (scene != null ? " filmSim=" + scene.key + " x" + scene.strength + " exp=" + filmExp + (filmOnly ? " (film only)" : "")
                : film != null ? " filmSim=" + film.lutKey() + " film data unreadable: no film" : "")
                + " ms=" + (System.currentTimeMillis() - started));
    }

    /** P378 review: the sky ratio grid's longer side at most (why/skyratio, PostPipeline.skyHdrRatio: ~0.8 MB of floats at 512 x 384). */
    static final int SKY_RATIO_SIDE = 512;

    /**
     * P378 review (Ultra HDR, owner: «только небо» lowered, but the gain map's boost - the scene plane over the SDR - gave an HDR
     * display the sky's old light back): the SDR's own ratio of the sky term into PostPipeline.skyHdrRatio - the combine program
     * still bound draws whyLow (the kernel's same-size bypass fU 1 on whyLow: no detail, no dither) with the sky term and without
     * it (skyU.x, skyMU 0: every sky branch is skipped, as off the mask), then why/skyratio takes their luminance ratio on a grid
     * of whyLow (its longer side SKY_RATIO_SIDE) and it is read back. RunHDRGainMap scales the scene plane by it: the HDR
     * rendition is the SDR's sky term times the scene's own headroom over the SDR before it. P393 review: both draws without the light
     * tones' chroma (LIGHT_CHROMA, lightChromaU 0 when {@code lightChroma}): the sky term holds it back under the mask, so the draw
     * without the sky term took it there and the ratio carried it.
     */
    private void skyHdrRatio(PostPipeline pipeline, WhyTone.State st, WhyAe.Result r, GLTexture with, GLTexture without, boolean lightChroma) {
        final GLTexture colour = st.lowDn != null ? st.lowDn : st.low;
        if (lightChroma) glProg.setVar("lightChromaU", 0f);
        glProg.setTexture("InputBuffer", st.low);
        glProg.setTexture("WhyColour", colour);
        glProg.setVar("fU", 1);
        glProg.setVar("colourFU", 1);
        glProg.setVar("detailGainU", 0f);
        glProg.setVar("ditherU", 0);
        if (r.hr > 0f) glProg.setVar("hrDetailU", 0f);
        glProg.drawBlocks(with);
        glProg.setVar("skyU", 0f, 0f, 1f, 1f);
        glProg.setVar("skyMU", 0f, 0f, 0f, 1f);
        glProg.drawBlocks(without);
        final Point low = st.low.mSize;
        final int box = Math.max(1, (Math.max(low.x, low.y) + SKY_RATIO_SIDE - 1) / SKY_RATIO_SIDE);
        final Point size = WhyTone.reduced(low, box);
        final GLTexture ratio = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_32, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        try {
            glProg.useAssetProgram("why/skyratio", false);
            glProg.setTexture("WithSky", with);
            glProg.setTexture("WithoutSky", without);
            glProg.setVar("boxU", box);
            glProg.setVar("p3U", pipeline.p3Output ? 1 : 0);
            glProg.setVar("epsU", com.particlesdevs.photoncamera.processing.ultrahdr.GainMapComputer.DECODE_OFFSET);
            glProg.drawBlocks(ratio);
            final java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(size.x * size.y * 16).order(java.nio.ByteOrder.nativeOrder());
            ratio.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_32, 4), b);
            b.rewind();
            final java.nio.FloatBuffer f = b.asFloatBuffer();
            final float[] out = new float[size.x * size.y];
            for (int i = 0; i < out.length; i++) {
                final float v = f.get(4 * i);
                out[i] = v > 0f && v < 16f ? v : 1f;
            }
            pipeline.skyHdrRatio = out;
            pipeline.skyHdrRatioSize = size;
        } finally {
            ratio.close();
        }
    }
}
