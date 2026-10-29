package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.color.ColourLook;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/**
 * WHY photo tone of the SCAM Hybrid and SCAM HDR (the one tonemap of both routes): the WhyCam 1.23 / SCAM 9.6 tone of libfc_suppressor.so (Smart-HDR AE, exposure
 * fusion on a guided filter, OKLab grading, AgX Custom; research/hybrid5/tone_port.md sections 2 and 7) with the
 * high-frequency detail of the Sabre merge. Route: WhyStats -> WhyFusion -> WhyLumaSharpen -> WhyCombine. This class holds the per-shot state handed between those nodes, their settings and the colour
 * chain of why/low.glsl and why/combine.glsl.
 */
public final class WhyTone {
    private WhyTone() {}

    /** Per-shot state; the textures are closed by WhyCombine (all but none are needed after it). */
    public static final class State {
        public WhyAe.Result ae;
        /** G_CLEAN equivalent (half the sensor, RGBA16F) before and after bracket_denoise. */
        public GLTexture low, lowDn;
        /** 2x grid only: 2x2 mean of the output grid (the 1x-equivalent colour source of WhyCombine). */
        public GLTexture mid;
        /** Fused display luma of the exposure fusion (.r), whyLow size. */
        public GLTexture fused;
        /** Bounded reference of the detail delta: box mean of min(ae * Y709, 1) (.r), whyLow size; null without detail. */
        public GLTexture detailRef;
        /** Box factor of whyLow against the output grid (2 at 1x, 4 on the Sabre 2x grid). */
        public int factor = 2;
        /** Multiplier of the input to the G_CLEAN scale (why_input_ev). */
        public float inScale = 1f;
        /**
         * WhyLumaSharpen: WhyCam's sharpened luminance S(Ya) (.r) on the output grid, null without WHY sharpening. P214e: on a
         * mosaic shot (R1) Ya + S(B(Ya)) - B(Ya), B the 3x3 binomial.
         */
        public GLTexture lumaS;
        /** Domain multiplier of lumaS: Ya = min(sharpMul * Y709, 1) (1 = G_CLEAN scale, ae = the delta's domain). */
        public float sharpMul = 1f;
        /** P134: the colour chain's linear -> sRGB matrix of this shot (the white-balance rows of «Цвет» folded in). */
        float[] colourMatrix;
        /**
         * P187: above 100 MP WhyLumaSharpen gave the full-size image's storage back after its last reader (the image texture
         * passed on from node to node is deleted; WhyCombine must not bind it).
         */
        boolean inputReleased;
        /**
         * P378: the sky mask of the sky term (WhySky, RG16F at whyLow size, its longer side kept at ~2048 px, linear filter: r the
         * sky, g the clear sky) and its decision; both null without a sky (no SKY define anywhere: the tone before P378).
         */
        GLTexture sky;
        WhySkyMask.Mask skyMask;
        /**
         * P391: the shot's one-frame noise model (PostPipeline.noiseS / noiseO: variance S x + O of the normalised raw) and what the
         * merge leaves of it (the worker's measured correction over N_eff^0.8 of its frame map; NaN: no model) - the fusion's noise
         * (WhyFusion.guideNoise).
         */
        float noiseS, noiseO, noiseMerged = Float.NaN;

        void closeTextures() {
            if (low != null) low.close();
            if (lowDn != null && lowDn != low) lowDn.close();
            if (mid != null) mid.close();
            if (fused != null) fused.close();
            if (detailRef != null) detailRef.close();
            if (lumaS != null) lumaS.close();
            if (sky != null) sky.close();
            low = lowDn = mid = fused = detailRef = lumaS = sky = null;
        }
    }

    /** Hybrid WHY setting pref_scam_hybrid_why_&lt;key&gt; (scam_dev.txt "hybrid_why_&lt;key&gt;" overrides it). */
    static float value(String key, float fallback) {
        return PreferenceKeys.hybridValue("why_" + key, fallback);
    }

    /**
     * P348: the default layer weights in the exposures' order (darkest, base, second dark, bright; WhyAe.exposures). P354 (owner,
     * 2026-10-27, the chandelier: «затенение вокруг люстры из-за неправильного локального тонмапинга»): the second dark layer
     * (sqrt(em0)) 0.4 -> 0.1 - at 0.4 it took a share wherever the lit ceiling around a lamp was bright (its luma there sits at the
     * weights' centre), and the glow of every lamp went flat with a darker ring round the shade; the darkest layer still draws the
     * shade itself. Host replay of the owner's burst: the glow back as in the reference shot; the sunset of P348 / P349: a softer,
     * brighter glow round the disc and no darker orange ring, the rungs unchanged.
     */
    static final float[] LAYER_WEIGHTS = {0.8f, 1f, 0.1f, 1f};

    /**
     * The fusion's layer weights: the kernel's highlight and base weights [code 0x78b18-0x78b3c]; P348's second dark layer 0.4 and
     * the one bright layer 1.0 (the closest to the old set's shadows and mid-tones of the curves on a grid of strengths).
     */
    static float[] layerWeights() {
        return new float[]{value("weight_hl", LAYER_WEIGHTS[0]), value("weight_mid", LAYER_WEIGHTS[1]),
                value("weight_dark2", LAYER_WEIGHTS[2]), value("weight_bright", LAYER_WEIGHTS[3])};
    }

    /** AE / Smart-HDR / AgX settings (defaults: WhyCam 2.85 X8U effective values, tone_port.md section 3). */
    static WhyAe.Settings settings() {
        WhyAe.Settings s = new WhyAe.Settings();
        s.aeTarget = value("ae_target", 0.15f);
        s.maxBoost = value("ae_max_boost", 5.0f);
        s.minLimit = Math.min(value("ae_min_limit", 0.5f), s.maxBoost);
        s.hlOverflow = value("hl_overflow", 2.0f);
        float nightVal = value("night_thresh", 0.05f);
        if (nightVal >= 1.0f) nightVal = 0.05f; // migration of legacy ISO values (e.g. 100)
        // The row is the noise-based gain clamp only. Since P63 the ISO night threshold took the same value, always below ISO 1,
        // so WhyAe used ISO 1 (max(nightThresh, 1)) whatever the row said; that threshold is now set here on purpose, the pixels
        // as before (settings audit 2026-10-21). The reference app's ISO 100 would strengthen the shadow lift (nf) at every ISO: a tone
        // change for the owner to decide.
        s.nightThresh = 1f;
        s.noiseMax = nightVal;
        s.nightDim = value("night_dim", 1.0f);
        s.metering = Math.round(value("metering", 0f));
        s.brightThresh = value("bright_thresh", 0.8f);
        s.darkThresh = value("dark_thresh", 15f);
        s.darkPixelThresh = value("dark_pixel_thresh", 10f);
        s.hlBoost = value("hl_boost", 1.0f);
        s.contrastBoost = value("contrast_boost", 0.5f);
        s.shadowStr = Math.max(value("shadow_str", 2.0f), 0f);   // pushCurrentLut 0x80db8
        s.highlightStr = value("highlight_str", 2.0f);
        s.efEnabled = true;
        s.look = Math.round(value("agx_look", 4f));
        s.slope = value("agx_slope", 2.7f);
        s.sp = value("agx_sp", 1.35f);
        s.tp = value("agx_tp", 1.6f);
        s.minEv = value("agx_min_ev", -8.5f);
        s.maxEv = value("agx_max_ev", 3.5f);
        s.sat = value("agx_sat", 1.0f);
        s.ev = value("agx_ev", 0.30f);
        // P216: the Bento ultrashort frames merged - data above the native ceiling of 4 is the shot's real highlight range
        s.bentoHeadroom = ScamHybridBurst.lastBentoApplied;
        s.hrMix = Math.max(0f, Math.min(1f, value("hr_mix", 0.5f)));
        return s;
    }

    /**
     * P216: WhyCombine's handling of real Bento headroom - the shoulder knee and room (x the tonemapper top) and the share of the
     * full-size highlight contrast kept (scam_dev hybrid_why_hr_knee / _room / _detail).
     */
    /**
     * P291: the fusion's well-exposed centre and highlight-layer weight with Bento headroom (WhyFusion; kernel: 0.6, 0.8).
     * Lab round (owner, 2026-10-21: «проверь что все настройки рабочие»): they follow the menu's «Смещение центра экспозиции» and «Вес
     * слоя светов» (the headroom centre 0.15 above the menu's, the highlight weight half of it: 0.75 / 0.4 at the defaults, the
     * pixels as before), so the two rows act on a headroom shot's neutral highlights too, not only on its coloured light (P310
     * keeps the menu values there); a scam_dev line (hybrid_why_weight_center_hr / _hl_hr) still sets them outright.
     */
    static float headroomCenter() {
        if (PreferenceKeys.scamDevOverrides("hybrid_why_weight_center_hr")) return value("weight_center_hr", 0.75f);
        return Math.max(0.2f, Math.min(0.9f, 0.75f + (value("weight_center", 0.6f) - 0.6f)));
    }

    static float headroomHlWeight() {
        if (PreferenceKeys.scamDevOverrides("hybrid_why_weight_hl_hr")) return Math.max(0f, value("weight_hl_hr", 0.4f));
        return Math.max(0f, 0.4f * (value("weight_hl", 0.8f) / 0.8f));
    }

    /**
     * P310: the saturation ((max - min) / max of the light) over which a pixel moves from the headroom weights to the kernel's
     * (why/gf.glsl satLightU; hi <= lo: off, every pixel the headroom weights as P291), then the light's level (its brightest
     * channel x the AE) over which that fades in (satLevelU; only light near the top of the curve).
     * P341 (owner, 2026-10-26: «в новой версии у нас теперь артефакт оранжевый, переход должен быть плавный»): the saturation ramp
     * 0.15 .. 0.95 (was 0.35 .. 0.7). A sunset sky warms steadily towards the sun; the narrow ramp switched its weights within
     * ~100 px of that slope and the two tones (kernel: darker, orange; headroom: paler) met along an iso-saturation line, an
     * orange wedge with an edge. The wide ramp spreads the same switch over the whole slope (host replay of the owner's
     * 17:57:57 sunset: no edge, the glow still orange); a fully saturated LED (0.9+) keeps ~all of the kernel's weights.
     */
    static float[] saturatedLightRamp() {
        return new float[]{value("sat_light_lo", 0.15f), value("sat_light_hi", 0.95f),
                value("sat_light_level_lo", 0.25f), Math.max(value("sat_light_level_hi", 0.6f), value("sat_light_level_lo", 0.25f) + 0.01f)};
    }

    /**
     * P348: the warm hue band ((g - b) / (r - b) of r >= g >= b: orange to yellow, a setting sun's glow, fire) that keeps the
     * pyramid instead of P310's kernel weights (import why_fusion warmU; hi <= lo: off).
     */
    static float[] warmLight() {
        return new float[]{value("sat_light_warm_lo", 0.1f), value("sat_light_warm_hi", 0.25f)};
    }

    /**
     * P349: the sun bloom round fully clipped cores (why/combine.glsl SUN_BLOOM): strength (0 = off) and the glow's radius as a
     * share of the frame's shorter side.
     */
    static float[] sunBloom() {
        return new float[]{Math.max(0f, Math.min(1f, value("sun_bloom", 1f))), Math.max(0.005f, value("sun_bloom_radius", 0.033f))};
    }

    /**
     * P349: the guide's range (luminance x AE) over which the WHY fusion goes from the Laplacian pyramid to the guided filter
     * (why/lpyr guidedMixU): light above white, where the pyramid draws halos round dark objects; hi <= lo: the pyramid alone.
     */
    static float[] guidedLights() {
        // P391: the edge-aware bands do this share's job (host: the rungs in a glow p95 0.0032 of fused luma against its 0.0076) - off
        // with them unless a scam_dev line sets it (the A/B)
        if (bands() != null && !PreferenceKeys.scamDevOverrides("hybrid_why_fusion_guided_lo")
                && !PreferenceKeys.scamDevOverrides("hybrid_why_fusion_guided_hi")) return new float[]{0f, 0f};
        return new float[]{value("fusion_guided_lo", 0.8f), value("fusion_guided_hi", 1.6f)};
    }

    /**
     * P391 (owner, 2026-10-28, approved: an edge-aware pyramid without the halos at window frames, twigs, lamps, the horizon):
     * the edge-aware bands of why/lpyr (EDGE_BANDS 1) - {lo, hi} the light step (EV) from which an edge starts to take the
     * per-pixel fusion's bands / takes them fully, z the guide's light floor (x AE) of its log, the strength, the reach round an
     * edge (level px, every level), the fall of its edge measure per level px (a share of the level's hi) - or null: off, the
     * pyramid as before. The edge measure of a level is the guide's log light against its Gaussian 3 levels down (a step of S EV
     * reads S / 2 x EDGE_STEP, a +-0.5 EV texture 0.5: the ramp from 1.2 EV keeps a texture's local contrast, a 2 EV window frame
     * takes the per-pixel bands); review of P391 (host): the band's own contrast as the measure, 0.15 .. 0.5 EV, flattened +-0.5 EV
     * textures to x0.34 - 0.57 of the pyramid and, cut on the fine levels only, drew a dark fringe of -0.008 beside thin dark
     * twigs; here a coarse level's share is at least the finer one's round the same place (no fringe) and texture keeps >= 0.9.
     * The literal form of the owner's idea - every level's weights smoothed by a guided filter instead of the binomial - was
     * measured on the host and left the frame and horizon halos as they were (their height is made at the finest level by the
     * edge's own band); on top of these bands it changed nothing measurable (+-0.0003), so it is not in the shader.
     */
    static float[] bands() {
        if (value("fusion_bands", 1f) < 0.5f) return null;
        final float lo = Math.max(0f, value("fusion_bands_lo", 1.2f));
        return new float[]{lo, Math.max(value("fusion_bands_hi", 1.8f), lo + 0.01f), BAND_FLOOR,
                Math.max(0f, Math.min(1f, value("fusion_bands_strength", 1f))), Math.max(0, Math.min(4, Math.round(value("fusion_bands_reach", 3f)))),
                Math.max(0f, value("fusion_bands_fall", 0.25f))};
    }

    /**
     * P391: the guide's light floor (x AE) of the bands' log light, 2^-9 (review: at 2^-7 a 0.004 -> 0.016 step in a deep shadow read
     * as 1 EV, inside the ramp, and its dark side glowed 0.0138 against the pyramid's 0.0119).
     */
    static final float BAND_FLOOR = 0.001953125f;

    /**
     * P391: the edge measure's response to a step per level (its largest |G_l - G_l+3| over half the step's EV, the mean over the
     * step's phase on the grid; host numpy, tools/check_why_pyramid_bands.py re-measures it); the last for the coarser levels.
     */
    static final float[] EDGE_STEP = {0.95f, 0.83f, 0.81f, 0.82f, 0.82f};

    /** P391: the white-noise factor of the edge measure of levels 0 .. 4 (sigma of D per sigma of the guide's log light; host numpy). */
    static final float[] EDGE_BETA = {0.997f, 0.271f, 0.122f, 0.059f, 0.029f};

    /**
     * P391: level {@code l}'s ramp of the edge measure and its fall per level px - {lo, hi, fall}: {@code band}'s step EV through
     * the level's step response, the fall a share of hi.
     */
    static float[] edgeRamp(float[] band, int l) {
        final float s = EDGE_STEP[Math.max(0, Math.min(l, EDGE_STEP.length - 1))];
        final float lo = 0.5f * band[0] * s, hi = 0.5f * band[1] * s;
        return new float[]{lo, hi, band[5] * hi};
    }

    /** P391: the white-noise factor of the edge measure of level {@code l} (halving per level below the table). */
    static float edgeBeta(int l) {
        return l < EDGE_BETA.length ? EDGE_BETA[Math.max(0, l)] : EDGE_BETA[EDGE_BETA.length - 1] / (float) (1 << Math.min(20, l - EDGE_BETA.length + 1));
    }

    /**
     * P391 (item 2): the pyramid's depth without jumps across resolutions - the lowest band's excess weighted by the fractional
     * depth (WhyFusion.depth); only with the bands (their per-pixel coarsest level makes it exact); false: whole levels as before.
     */
    static boolean depthSmooth() {
        return value("fusion_depth_smooth", 1f) >= 0.5f;
    }

    /**
     * P391 (item 3): the cap of the deep shadows' fused-luma noise the bright layer's floor may lift them to (import why_fusion
     * deepNoiseU.x; 0: the floor as before).
     */
    static float deepNoise() {
        return Math.max(0f, value("deep_noise", DEEP_NOISE));
    }

    /** P391: the default of {@link #deepNoise}: the fused luma's noise at most 0.008 (two 8-bit steps of the curve) in a lifted shadow. */
    static final float DEEP_NOISE = 0.008f;

    /**
     * P391 (items 3, 4): the correction c of the fusion's noise model (the one-frame camera model over N_eff^0.8 of the merge, a
     * 2 x 2 sensor box, the worker's measured correction k of the camera model) to the noise whyLow keeps after the denoise.
     * tools/check_why_noise.py --calibrate (the 10th percentile of 8 x 8 blocks' plane residual, its bias measured on simulated noise
     * of the same correlation; it recovers a known c within 8 % on white and correlated synthetic noise) with each dump's k: the X200
     * Ultra's 02-WhyLow (ISO 597, k 0.625, N_eff 16.4) 1.09 (its flat dark bins 0.63 - 1.13), a camera 2 dump (ISO 150, no merge
     * report: N_eff 1) 1.25; a facade dump has no flat area to measure. Review of P391: the first estimate left out k and leant on an
     * assumed N_eff, and the review's 0.4 came from two estimators that read 27 % and 38 % under a known c on white noise (more on
     * correlated noise), so 1 stays, now measured.
     */
    static float noiseScale() {
        return Math.max(0f, value("noise_scale", NOISE_SCALE));
    }

    static final float NOISE_SCALE = 1.0f;

    /**
     * P391: what the merge leaves of the one-frame noise where its frame map is not known (the RAW viewfinder's curve, a lab
     * fragment of a frame recorded before P391): N_eff ~ 0.55 x the ZSL frames (LiveWhyTone's merged noise), ^-0.8 as the photo's.
     */
    static float expectedMerged() {
        return (float) WhyNoiseClamp.modelScale(Math.max(1f, WhyNoiseClamp.EXPECTED_SHARE * PreferenceKeys.hybridValue("zsl_frames", 20f)));
    }

    /**
     * P391 (item 4): the pyramid's excess over the per-pixel band on the fine levels passes only where the guide's band stands
     * {lo .. hi} noise sigmas out (hi <= lo: off), on the {levels} finest levels (at most 4).
     */
    static float[] fusionNoise() {
        return new float[]{Math.max(0f, value("fusion_noise_lo", 2f)), Math.max(0f, value("fusion_noise_hi", 4f)),
                Math.max(0, Math.min(4, Math.round(value("fusion_noise_levels", 3f))))};
    }

    /** P391: the white-noise factor of the band of levels 0 .. 3 (5-tap binomial reduce, linear expand; host numpy). */
    static final float[] BAND_BETA = {0.9279f, 0.2203f, 0.0933f, 0.0462f};

    /** P391: the white-noise factor of the band of level {@code l} (halving per level below level 3). */
    static float bandBeta(int l) {
        return l < BAND_BETA.length ? BAND_BETA[Math.max(0, l)] : BAND_BETA[BAND_BETA.length - 1] / (float) (1 << Math.min(20, l - BAND_BETA.length + 1));
    }

    /**
     * P391: an edge of the bands counts only beyond this many noise sigmas of its edge measure, taken out in quadrature (why/lpyr
     * bandNoiseU; 0: none) - noise alone is no edge (a deep shadow's random «edges» took the per-pixel band, more fine noise than the
     * pyramid's; review: subtracted as a shift it moved a deep shadow's 2 EV step into the ramp's middle, +10 - 30 % glow).
     */
    static float bandsNoise() {
        return Math.max(0f, value("fusion_bands_noise", 2f));
    }

    /**
     * P378 (owner, 2026-10-28: the sky like the reference app's, «при этом чтобы в остальных сценариях ничего не сломалось»; «Нет,
     * только небо»): the sky term's master strength 0 .. 1 (0 = off: no mask, no define, the tone before P378 bit for bit). It
     * scales every term: the clouds' neutrality, the sky's level and top (not in a backlit scene), the clouds' modelling, the
     * landing's glow off in the clear sky.
     */
    static float sky() {
        return Math.max(0f, Math.min(1f, value("sky", 1f)));
    }

    /**
     * P378 (L2, L3): the sky's own tone - the gain of its level (scene light before OKLab; it fades out over the clouds' tops,
     * WhySkyMask.TOP_FROM .. TOP_TO), its macro contrast as a share of «Контраст» (P378 review: a fixed 1.0 in its place made the
     * term brighten the sky at «Контраст» 0.8 and lower it x0.73 at 1.5; 0.91 is the 1.0 of the default 1.1) and the room of the
     * shoulders above white x this (the no-Bento ceiling and roll-off). Tuned on tools/check_why_sky.py's overcast sky against the
     * transfer of the owner's flats to the reference app (its sky x0.86 of ours in linear output up to the 80th percentile, x0.95
     * at the 91st, x1 - 1.05 at the top; local contrast 1.3x in the sky, 1.8x in the tops): gain 0.94, macro x0.91 - at a ceiling
     * 1.2 (the landing on: the flats' own levels) x0.82 .. 0.90 up to the 90th percentile, x0.97 at the top, contrast 1.5x / 2.0x;
     * at 2.5 (no landing) x0.85 .. 0.87, x0.93, 1.2x / 1.6x.
     */
    static float[] skyTone() {
        return new float[]{Math.max(0.5f, Math.min(1.5f, value("sky_gain", 0.94f))), Math.max(0.5f, Math.min(1.5f, value("sky_macro", 0.91f))),
                Math.max(1f, Math.min(8f, value("sky_top", 2.0f)))};
    }

    /**
     * P378 review (C1: the blue gaps between the clouds went x1.06 in chroma under the term, where the reference app's are x0.87 of
     * ours - L* 68.0 / C* 22.8 against 71.8 / 26.4 on the owner's flats): the share of the sky vibrance («vibrance_sky») the sky's
     * clear blue keeps under the mask (why/combine.glsl skyMU.w; tools/check_why_sky.py gates the gaps on that target: 1 gave x1.05,
     * 0.25 x0.92, 0.05 x0.87). The ground's blue keeps it all.
     */
    static final float SKY_VIBRANCE = 0.05f;

    /**
     * P378 (L4): how much of the clear sky's landing follows the pixel's own share of the ceiling instead of the blurred one (1:
     * all - at a low ceiling the clouds' local contrast 2.2x / 2.9x, far above the reference's 1.3x / 1.8x; 0.2: 1.5x / 2.0x).
     */
    static final float SKY_GLOW = 0.2f;

    /**
     * Window halo (owner's window burst, 2026-10-28: a glow inside the glass along every frame bar, the bars darker): the band
     * contrast of the guide's log light against its coarsest pyramid level (EV) over which the WHY fusion goes from the Laplacian
     * pyramid to the guided filter's blend (why/lpyr edgeMixU x, y), and the light floor (x AE) under which the deep shadows count
     * as one level (z: no share on their noise); hi <= lo: off, the pyramid as before.
     */
    static float[] edgeShare() {
        // P391: the edge-aware bands remove this halo inside the pyramid (host: the window zones within 0.0006 of fused luma of the guided
        // filter against 0.0098 with this share, the horizon 0.0008 against 0.0051) - off with them unless a scam_dev line sets it (the A/B)
        if (bands() != null && !PreferenceKeys.scamDevOverrides("hybrid_why_fusion_edge_lo")
                && !PreferenceKeys.scamDevOverrides("hybrid_why_fusion_edge_hi"))
            return new float[]{0f, 0f, Math.max(1e-4f, value("fusion_edge_floor", 0.0078125f))};
        return new float[]{value("fusion_edge_lo", 0.3f), value("fusion_edge_hi", 1.0f), Math.max(1e-4f, value("fusion_edge_floor", 0.0078125f))};
    }

    /** P341: the vibrance of the light tones (why/combine.glsl vibHlU; 0 = off). */
    /**
     * P394 (owner, 2026-10-29: «нам нужно сделать грань максимально гладкой без зубчиков»): why/combine edgeHoldU - at a strong edge
     * the sharpened luma averaged along the edge (+-3 px on its tangent) and held within the full-size merge's 3 x 3 range (+-5 %):
     * no dark outline from the sharpening, no pixel steps on a slanted edge against a light; texture and soft edges as before.
     * scam_dev hybrid_why_edge_hold 0..1 (0 = off, as before).
     */
    static float edgeHold() {
        return Math.max(0f, Math.min(1f, value("edge_hold", 1f)));
    }

    static float vibranceHl() {
        return Math.max(0f, value("vibrance_hl", 1.0f));
    }

    /**
     * P393 (owner, 2026-10-29: «У нас что-то с желтыми цветом ... В последней паре более жёлтое фото наше», pairs against the
     * reference app on the X200 Ultra): the light-tone vibrance's shape for pale colour (why/combine.glsl vibHlShapeU: the OKLab
     * lightness where it starts / is full, the chroma where its gate starts / is open). P341's ramp 0.45 .. 0.75 and gate 0.002 ..
     * 0.008 took pale walls, ceilings and facades in the mid-light tones to x1.9 - 2.0 of the scene's chroma and doubled the white
     * balance's cast on near-whites (cream walls of the V2405A room burst b* 5.5 -> 12.2); now, for pale colour (chroma under
     * 0.020), 0.50 .. 0.85 (the light tones keep the mid-tones' x1.4 - 1.5, the shoulder's loss made up above) and 0.010 .. 0.025
     * (a near-white's cast not raised; the gate about as steep as P341's: chroma noise on a white wall x1.12 of P341's, not x1.41):
     * the owner's bedroom walls b* 11.2 / 7.5 / 16.0 -> 6.6 / 3.2 / 11.0, the reference app's 6.2 / 3.6 / 10.2. A colour from
     * chroma 0.020 .. 0.035 up (skin, yellow leaves, facades) and the sky keep P341's / P378's.
     */
    static final float[] VIBRANCE_HL_SHAPE = {0.50f, 0.85f, 0.010f, 0.025f};
    /** P341's shape (scam_dev.txt "vibrance_hl_shape 0"): the shader before P393 (bit for bit on the host's GL, within rounding on a phone GPU). */
    static final float[] VIBRANCE_HL_SHAPE_P341 = {0.45f, 0.75f, 0.002f, 0.008f};

    /** P393: {@link #VIBRANCE_HL_SHAPE}, or P341's with scam_dev.txt "vibrance_hl_shape 0" (the photo and the RAW viewfinder). */
    static float[] vibranceHlShape() {
        return (PreferenceKeys.scamDevSwitch("vibrance_hl_shape", true) ? VIBRANCE_HL_SHAPE : VIBRANCE_HL_SHAPE_P341).clone();
    }

    /** P291: the rise of the fusion's monotonic envelope per ln of the light (why/gf.glsl monoU; 0 = the kernel's fusion). */
    static float fusionMono() {
        return Math.max(0f, value("fusion_mono", 0.04f));
    }

    /**
     * Window burst (2026-10-28, a room 7 EV under the window rendered black): the bright layer's weight floor in deep shadows, in
     * sigma under the fusion's centre (import why_fusion deepFloorU; 0 = off) - far under the centre every layer's weight was about
     * equal and a deep shadow took the four layers' average (x1.47 of the bright layer's x2.61).
     */
    static float deepFloor() {
        return Math.max(0f, value("deep_floor", 2f));
    }

    /** Window burst: why/combine MACRO_TOE - the macro contrast below its pivot as a power, no black offset (scam_dev macro_toe 0: off). */
    static boolean macroToe() {
        return value("macro_toe", 1f) >= 0.5f;
    }

    /**
     * P393 (owner, 2026-10-29: sunlit yellow birches pale peach-white beside ArkCam's saturated yellow): why/combine LIGHT_CHROMA - the
     * share of its mid-tone chroma a saturated orange-yellow keeps in the light tones under the AgX white, its hue turned towards the AgX
     * rendition's only as far as the locked hue cannot hold that chroma (AgX only; skin, greens and blues as before). 0 = off, the AgX
     * shoulder's chroma and the hue lock as before (scam_dev hybrid_why_light_chroma 0).
     */
    static float lightChroma() {
        return Math.max(0f, Math.min(1f, value("light_chroma", 1f)));
    }

    static float[] headroomTone() {
        return new float[]{value("hr_knee", 0.5f), value("hr_room", 0.4f), Math.max(0f, value("hr_detail", 0.35f))};
    }

    /**
     * AgX saturation factor for shots rendered with the OPPO tuned ISP matrix (Find X7 Ultra). 0.6 matched WhyCam 2.85 with
     * the X8U config at night (research/hybrid5); against WhyCam SCAM 9.6 with its X7U config (owner's pair 2026-10-08, main and
     * 3x tele, indoor ~3800 K) 0.6 left half of WhyCam's chroma (C*ab x0.48, lightness-normalised), and the numpy reference of
     * the tone put the match at 1.1. 1 = no compensation.
     */
    static float ccmSatComp(Parameters p) {
        return ccmSatComp(p != null && p.oppoTunedCcm);
    }

    /**
     * P370 (B2): why/combine signedHueU (and the RAW viewfinder's linear frame, rawdevelop_fs u_signed_hue): a colour the camera
     * matrix put outside sRGB keeps the hue of its signed value; scam_dev.txt "ccm_signed_hue 0": the per-channel clip as before.
     */
    public static boolean signedHue() {
        return PreferenceKeys.scamDevSwitch("ccm_signed_hue", true);
    }

    /** P370 (B4): {@link #ccmSatComp(Parameters)} of a frame the tuned matrix renders or not (the RAW viewfinder's). */
    static float ccmSatComp(boolean tuned) {
        return tuned ? Math.max(value("ccm_sat", 1.1f), 0f) : 1f;
    }

    /**
     * P144: the tone operator of «Кривая AgX и ACES» - ToneCurve.AGX unless the own curve, Uchimura or ACES is switched on (the
     * settings keep one on at a time; should several be stored, that order wins).
     */
    static int toneOperator() {
        if (PreferenceKeys.hybridSwitch("why_curve_on", false)) return com.particlesdevs.photoncamera.processing.color.ToneCurve.CUSTOM;
        if (PreferenceKeys.hybridSwitch("why_uchi_on", false)) return com.particlesdevs.photoncamera.processing.color.ToneCurve.UCHIMURA;
        if (PreferenceKeys.hybridSwitch("why_aces_on", false)) return com.particlesdevs.photoncamera.processing.color.ToneCurve.ACES;
        return com.particlesdevs.photoncamera.processing.color.ToneCurve.AGX;
    }

    /** ACES (WhyCam defaults): pre-gain 1, a 2.61, d 0.59, toe (the same toe as the inversion of the fusion target). */
    static float[] acesUniform() {
        return new float[]{value("aces_pre_gain", 1.0f), value("aces_a", 2.61f), value("aces_d", 0.59f), value("aces_toe", 0.05f)};
    }

    /** Uchimura (WhyCam defaults): pre-gain 1, post-gain 0.9, P 1, a 1 | m 0.22, l 0.4, c 1.33, b 0. */
    static float[][] uchimuraUniforms() {
        float m = Math.max(value("uchi_m", 0.22f), 0.01f), a = Math.max(value("uchi_a", 1.0f), 0.05f);
        // the shoulder needs P above the linear start (P - S1 = (P - m)(1 - l) > 0): the ranges allow P 0.5 with m 0.8
        float p = Math.max(value("uchi_p", 1.0f), m + 0.05f), l = Math.min(Math.max(value("uchi_l", 0.4f), 0f), 0.95f);
        return new float[][]{{value("uchi_pre_gain", 1.0f), value("uchi_post_gain", 0.9f), p, a},
                {m, l, value("uchi_c", 1.33f), value("uchi_b", 0.0f)}};
    }

    /**
     * P177 (owner, 2026-10-16: «для кастомных кривых добавь возможность визуализации кривых AGX/ACES и тд для сравнения ...
     * дай возможность их подгружать чтобы потом редактировать»): AgX, ACES or Uchimura with today's settings as a function of
     * the own curve's x, a display value as its y (ToneCurve.agxAt / acesAt / uchimuraAt).
     */
    public static java.util.function.DoubleUnaryOperator reference(int op) {
        if (op == com.particlesdevs.photoncamera.processing.color.ToneCurve.ACES) {
            final float[] a = acesUniform();
            return x -> com.particlesdevs.photoncamera.processing.color.ToneCurve.acesAt((float) x, a);
        }
        if (op == com.particlesdevs.photoncamera.processing.color.ToneCurve.UCHIMURA) {
            final float[][] u = uchimuraUniforms();
            return x -> com.particlesdevs.photoncamera.processing.color.ToneCurve.uchimuraAt((float) x, u[0], u[1]);
        }
        final float slope = value("agx_slope", 2.7f), sp = value("agx_sp", 1.35f), tp = value("agx_tp", 1.6f),
                minEv = value("agx_min_ev", -8.5f), maxEv = value("agx_max_ev", 3.5f), ev = value("agx_ev", 0.30f);
        return x -> com.particlesdevs.photoncamera.processing.color.ToneCurve.agxAt((float) x, slope, sp, tp, minEv, maxEv, ev);
    }

    /** The own curve's 256 samples (pref_scam_hybrid_why_curve_points, WhyCam's S when empty). */
    static float[] curveSamples() {
        return com.particlesdevs.photoncamera.processing.color.ToneCurve
                .parse(PreferenceKeys.hybridString("why_curve_points", com.particlesdevs.photoncamera.processing.color.ToneCurve.DEFAULT_POINTS))
                .samples(com.particlesdevs.photoncamera.processing.color.ToneCurve.SAMPLES);
    }

    /** ef_gamma; the mod takes 2.2 for a value at or below 0.1 [pushCurrentLut 0x80da4]. */
    static float gamma() {
        float g = value("gamma", 2.2f);
        return g > 0.1f ? g : 2.2f;
    }

    /** Box factor of whyLow: half the SENSOR grid, i.e. 2 at 1x and 4 on the Sabre 2x grid. */
    static int lowFactor(Parameters p) {
        return 2 * Math.max(1, Math.round(p.outputScale));
    }

    /** WHY size of a grid reduced by a box of f (the last box is clamped at the edge). */
    static Point reduced(Point size, int f) {
        return new Point((size.x + f - 1) / f, (size.y + f - 1) / f);
    }

    /** ProPhoto -> sRGB matrix of the render. */
    static float[] intermediateToSRGB(Parameters p) {
        return p.CCT.matrix;
    }

    /**
     * P134 «Цвет»: the colour settings of the shot (pref_scam_hybrid_colour_*; scam_dev.txt "hybrid_colour_<key>" overrides).
     * Every default is «no change».
     */
    /** The «Цвет» rows of a shot: its press's (PostPipeline.look), else the settings now (the lab's renders). */
    static ColourLook colourLook(PostPipeline pipeline) {
        final ShotLook l = pipeline == null ? null : pipeline.look;
        return l != null ? l.colour : colourLook();
    }

    /** The «Цвет» rows of the current module (also the RAW viewfinder's look, P134). */
    public static ColourLook colourLook() {
        ColourLook c = new ColourLook();
        c.warmth = PreferenceKeys.hybridValue("colour_warmth", 0f);
        c.tint = PreferenceKeys.hybridValue("colour_tint", 0f);
        c.keepWarm = PreferenceKeys.hybridValue("colour_keep_warm", 0f);
        c.keepCool = PreferenceKeys.hybridValue("colour_keep_cool", 0f);
        c.skinProtect = PreferenceKeys.hybridValue("colour_skin_protect", 0f);
        c.skinHue = PreferenceKeys.hybridValue("colour_skin_hue", 0f);
        c.skinEven = PreferenceKeys.hybridValue("colour_skin_even", 0f);
        c.vibShadows = PreferenceKeys.hybridValue("colour_vib_shadows", 0f);
        c.vibHighlights = PreferenceKeys.hybridValue("colour_vib_highlights", 0f);
        final String[] parts = {"hue", "sat", "light"};
        for (int b = 0; b < ColourLook.BANDS.length; b++)
            for (int k = 0; k < 3; k++) c.mix[b * 3 + k] = PreferenceKeys.hybridValue("colour_mix_" + ColourLook.BANDS[b] + "_" + parts[k], 0f);
        for (int z = 0; z < 3; z++) {
            c.gradeHue[z] = PreferenceKeys.hybridValue("colour_grade_" + ColourLook.ZONES[z] + "_hue", 0f);
            c.gradeStr[z] = PreferenceKeys.hybridValue("colour_grade_" + ColourLook.ZONES[z] + "_str", 0f);
        }
        c.gradeBalance = PreferenceKeys.hybridValue("colour_grade_balance", 0f);
        c.gradeBlend = PreferenceKeys.hybridValue("colour_grade_blend", 50f);
        c.mono = Math.max(0, java.util.Arrays.asList(ColourLook.MONO).indexOf(PreferenceKeys.hybridString("colour_mono", "off")));
        c.hueLock = Math.max(0, java.util.Arrays.asList(ColourLook.HUE_LOCK).indexOf(PreferenceKeys.hybridString("colour_hue_lock", "full")));
        c.gamut = PreferenceKeys.hybridValue("colour_gamut", 0f);
        final String[] primaries = {"red", "green", "blue"};
        for (int p = 0; p < 3; p++) {
            c.calHue[p] = PreferenceKeys.hybridValue("colour_cal_" + primaries[p] + "_hue", 0f);
            c.calSat[p] = PreferenceKeys.hybridValue("colour_cal_" + primaries[p] + "_sat", 0f);
        }
        return c;
    }

    /**
     * Linear -> sRGB matrix of the colour chain: the camera's ProPhoto -> sRGB, with the white-balance rows of «Цвет»
     * (warmth, tint, the scene light kept) on top; once per shot, so the AE statistics and the photo share it.
     */
    static float[] colourMatrix(PostPipeline pipeline) {
        State st = pipeline.why;
        if (st != null && st.colourMatrix != null) return st.colourMatrix;
        Parameters p = pipeline.mParameters;
        float[] white = colourLook(pipeline).whiteMatrix(p.sceneCct);
        float[] m = colourMatrixOf(p, white);
        if (white != null) {
            com.particlesdevs.photoncamera.util.Log.i("SCAM_PIPELINE", "colour: white matrix " + java.util.Arrays.toString(white)
                    + " scene " + p.sceneCct + " K");
        }
        if (st != null) st.colourMatrix = m;
        return m;
    }

    /**
     * P370 (B1): the intermediateToSRGB uniform of a shot: the «Цвет» white matrix ({@code white}, null: none) after CCT.matrix -
     * what {@link #colourMatrix(PostPipeline)} uploads and the diagnostic README prints.
     */
    static float[] colourMatrixOf(Parameters p, float[] white) {
        float[] m = intermediateToSRGB(p);
        return white != null ? ColourLook.mul(white, m) : m;
    }

    /** P370 (B1): {@link #colourMatrixOf} with the «Цвет» rows of now (the diagnostic README before the pipeline runs). */
    static float[] colourMatrixNow(Parameters p) {
        return colourMatrixOf(p, colourLook().whiteMatrix(p.sceneCct));
    }

    /** Colour uniforms of why/low.glsl and why/combine.glsl (sceneLinear): the chain of headroom/render.glsl. */
    static void setColour(GLProg prog, PostPipeline pipeline, GLTexture gainMap, float inScale) {
        Parameters p = pipeline.mParameters;
        prog.setTexture("GainMap", gainMap);
        prog.setVar("sensorToIntermediate", p.sensorToProPhoto);
        prog.setVar("intermediateToSRGB", colourMatrix(pipeline));
        prog.setVar("neutralPointU", p.whitePoint[0], p.whitePoint[1], p.whitePoint[2]);
        prog.setVar("inScaleU", inScale);
    }
}
