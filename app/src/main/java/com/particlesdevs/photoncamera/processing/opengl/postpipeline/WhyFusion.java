package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * WHY tone, step 2 (tone_port.md 2.2-2.3 / 7.3) on whyLow: bracket_denoise of the Bento range (clip > 1.05), then the
 * mod's exposure fusion of the luminance - four synthetic exposures (since P348 two dark and one bright: em0, 1, sqrt(em0),
 * 2^1.5S; WhyAe.exposures) of the AE result, ACES
 * lumas and Gaussian weights - into "fused", the display luma WhyCombine inverts to the per-pixel gain. The image passes through.
 * <p>
 * P333 (owner, 2026-10-25: «замени блок локального тонмапинга с гуидед фильтра на пирамиду Лапласа», after Wronski's exposure
 * fusion for real-time local tonemapping and Mertens et al.): the lumas are blended as a Laplacian pyramid, every band with the
 * weights blurred (binomial, not edge-aware) to its own scale (why/lpyr.glsl), down to the level whose shorter side is about
 * ef_coarse px. Flat areas equal the per-pixel fusion; a strong edge gets Mertens' halo (a bright band on its bright side, a dark
 * band on its dark side, about as wide as the coarse levels: ~30 whyLow px at 12 MP; a larger ef_coarse narrows it), where the
 * guided filter (edge-aware: 2x2 down, box r = ef_gf_radius / 2, eps, box, bilinear up; statistics in RGBA32F) stays within ~1 %.
 * The guided filter stays for the old run of post_ab only, so the A/B shows this change on one shot.
 * <p>
 * P391 (owner, 2026-10-28, approved: «делаем»; an edge-aware pyramid without the halos at window frames, twigs, lamps, the horizon;
 * the depth; the shadow lift and the fine bands' gain bounded by the frame's noise): every band of the pyramid is the per-pixel
 * fusion's band plus the pyramid's excess over it, taken out where the guide's log light at that level stands off its Gaussian 3
 * levels down as a step of more than WhyTone.bands' EV does (a +-0.5 EV texture keeps the excess), a coarse level at least where
 * the finer one took it out (no dark fringe beside thin dark objects) - WhyTone.bands, why/lpyr EDGE_BANDS 1; the coarsest level
 * moved towards the per-pixel fusion's by its carried share - so P349's guided share and P389's edge share are off with it (an
 * explicit scam_dev line brings either back for an A/B) and the guided filter is not run; the depth is continuous across
 * resolutions (the lowest band moved towards one level less by 1 - the fractional depth, {@link #depth}); with the frame's noise
 * model ({@link #guideNoise}: the one-frame camera model over N_eff^0.8 of the merge's map, the worker's measured correction, the
 * whyLow box, the AE, WhyTone.noiseScale) the bright layer's deep-shadow floor lifts only as far as the shadow's fused-luma noise
 * stays under WhyTone.deepNoise (import why_fusion deepNoiseU; the RAW viewfinder's FusedCurve the same), the excess of the fine
 * bands passes only above the noise (WhyTone.fusionNoise) and noise alone is no edge (WhyTone.bandsNoise). The literal guided
 * filter of every level's weights was measured on the host and left out: it does not remove a frame's or the horizon's halo, and on
 * top of the bands it changes nothing measurable.
 */
public final class WhyFusion extends Node {
    public WhyFusion() { super("", "WhyFusion"); }
    @Override public void Compile() {}

    private static final GLFormat STATS = new GLFormat(GLFormat.DataType.FLOAT_32, 4);

    private GLTexture stats(Point size) {
        return new GLTexture(size, STATS, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
    }

    /** gf.glsl with the weights of this shot. */
    private void weightsProgram(int mode, GLTexture source, float[] em, float[] lw, float ae, float center, float[] satLight) {
        fusionProgram("why/gf", mode, source, em, lw, ae, center, satLight);
    }

    /** P391: import why_fusion deepNoiseU of this shot (the noise-bound deep floor; zeros: the floor as before). */
    private final float[] deepNoise = new float[4];

    /**
     * P391: the noise model of the fusion's guide I (x AE) - {S_I, O_I}, variance S_I I + O_I - of whyLow: the one-frame camera
     * model ({@code s}, {@code o}: variance s x + o of the normalised raw) times {@code merged} (what the merge leaves of it: the
     * worker's measured correction over N_eff^0.8), the 2 x 2 sensor box of whyLow (1 / 4), the input scale and the AE, times the
     * calibration {@code c}; null without a model.
     */
    static float[] guideNoise(float s, float o, float merged, float ae, float inScale, float c) {
        if (!(merged > 0f) || !(s > 0f || o > 0f) || !(ae > 0f) || !(inScale > 0f) || !(c > 0f) || !Float.isFinite(merged)) return null;
        final double g = (double) ae * inScale, k = (double) c * merged / 4.0;
        return new float[]{(float) (k * g * Math.max(s, 0f)), (float) (k * g * g * Math.max(o, 0f))};
    }

    /** {@code asset} (gf or lpyr) in MODE {@code mode} with the fusion's uniforms of this shot (import why_fusion). */
    private void fusionProgram(String asset, int mode, GLTexture source, float[] em, float[] lw, float ae, float center, float[] satLight) {
        glProg.setDefine("MODE", mode);
        glProg.useAssetProgram(asset, false);
        glProg.setTexture("InputBuffer", source);
        glProg.setVar("expU", em[0], em[1], em[2], em[3]);
        glProg.setVar("layerU", lw[0], lw[1], lw[2], lw[3]);
        glProg.setVar("aeU", ae);
        glProg.setVar("centerU", center);
        glProg.setVar("smoothU", WhyTone.value("blend_smoothness", 0.25f));
        glProg.setVar("gammaInvU", 1f / WhyTone.gamma());
        glProg.setVar("toeU", WhyTone.value("aces_toe", 0.05f));
        // P291 (owner, 2026-10-20: «сделай максимально похоже на результат Why, но с чуть большим количеством информации»): the
        // fusion lifted to its monotonic envelope where its curve falls (Bento headroom: no dark ring round a lamp); 0.04 = the
        // replay of the owner's desk-lamp sheet closest to WhyCam with its gradient kept. 0 = the kernel's fusion.
        glProg.setVar("monoU", WhyTone.fusionMono());
        // P310: bright saturated light keeps the kernel's weights (the P291 lift's plateau, not its rise); zeros: off
        glProg.setVar("satLightU", satLight[0], satLight[1], satLight[2], satLight[3]);
        glProg.setVar("satLevelU", satLight[4], satLight[5]);
        final float[] warm = WhyTone.warmLight();
        glProg.setVar("warmU", warm[0], warm[1]);
        glProg.setVar("deepFloorU", WhyTone.deepFloor());
        // set on every pass (a linked program keeps its uniforms): zeros = the floor as before
        glProg.setVar("deepNoiseU", deepNoise[0], deepNoise[1], deepNoise[2], deepNoise[3]);
    }

    /** Separable box of radius r; closes the input. */
    private GLTexture box(GLTexture in, int r) {
        GLTexture h = stats(in.mSize);
        glProg.useAssetProgram("why/box", false);
        glProg.setTexture("InputBuffer", in);
        glProg.setVar("dirU", 1, 0);
        glProg.setVar("radiusU", r);
        glProg.drawBlocks(h);
        in.close();
        GLTexture v = stats(in.mSize);
        glProg.useAssetProgram("why/box", false);
        glProg.setTexture("InputBuffer", h);
        glProg.setVar("dirU", 0, 1);
        glProg.setVar("radiusU", r);
        glProg.drawBlocks(v);
        h.close();
        return v;
    }

    /**
     * The WHY tone's own denoise (bracket_denoise, chroma_denoise): only with the master switch «Шумоподавление» on (P165: on
     * every shot; P306: a 200 MP shot too, as its finish denoise).
     */
    static boolean whyDenoise(com.particlesdevs.photoncamera.processing.render.Parameters p) {
        return PreferenceKeys.isHybridDenoiseEnabled();
    }

    /**
     * P320: the strength of bracket_denoise for this shot: the setting's, 0 for a DCG frame without Bento (its Y > 0.95 range
     * is the frame's own highlight data - clouds, snow - not a gained ultrashort, and the fused high/low-gain reads are clean
     * there); the old run of post_ab keeps the setting's.
     */
    static float bracketStrength(float setting, boolean dcg, boolean bentoApplied, boolean legacy) {
        return dcg && !bentoApplied && !legacy ? 0f : setting;
    }

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        glProg.closed = true;
        WhyTone.State st = pipeline.why;
        if (st == null || st.ae == null || st.low == null) throw new IllegalStateException("WHY tone: no statistics (WhyStats did not run)");
        long started = System.currentTimeMillis();
        WhyAe.Result r = st.ae;
        Point lowSize = st.low.mSize;
        float strength = whyDenoise(basePipeline.mParameters) ? WhyTone.value("bracket_denoise", 1f) : 0f;
        strength = bracketStrength(strength, basePipeline.mParameters != null && basePipeline.mParameters.dcgShot,
                ScamHybridBurst.lastBentoApplied, com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy());
        st.lowDn = st.low;
        if (r.clip > 1.05f && strength > 0.001f) {
            st.lowDn = new GLTexture(lowSize, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("why/bracket_dn", false);
            glProg.setTexture("InputBuffer", st.low);
            glProg.setVar("strengthU", strength);
            glProg.drawBlocks(st.lowDn);
        }
        float[] em = WhyAe.exposures(r, headroomExposure(WhyAe.darkestExposure(r), r.ae, r.clip, r.hr));
        float[] lw = WhyTone.layerWeights();
        // P391: the noise model of the fusion's guide (the noise-bound deep floor, the bands' noise cap); the old run of post_ab
        // keeps the fusion before P391
        final boolean legacy = com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy();
        final float[] noiseI = legacy ? null : guideNoise(st.noiseS, st.noiseO, st.noiseMerged, r.ae, st.inScale, WhyTone.noiseScale());
        final float tau = noiseI == null ? 0f : WhyTone.deepNoise();
        deepNoise[0] = tau;
        deepNoise[1] = tau > 0f ? noiseI[0] : 0f;
        deepNoise[2] = tau > 0f ? noiseI[1] : 0f;
        deepNoise[3] = 0f;
        // P291 (owner, 2026-10-20: «белый никогда не уходил в серый», «максимально похоже на результат Why»): with Bento headroom
        // the highlight layer is far darker than WhyCam's (em.x 0.038 at 22 x white where WhyCam's clipped data gives ~0.125), so
        // the kernel's weights put lit paper on 150 - 190 grey where WhyCam has 245. There the well-exposed centre moves up and
        // the highlight layer weighs less (host replay of the owner's desk-lamp shots: the sheet 255 .. 205 against WhyCam's
        // 249 .. 199); without headroom the kernel's weights as before.
        float center = WhyTone.value("weight_center", 0.6f);
        float[] satLight = new float[6];
        if (r.hr > 0f) {
            // P310: only neutral light (a white sheet under a lamp) takes the headroom weights; a coloured light and what it lights
            // keep the kernel's centre and highlight weight (and no monotonic lift), ramped over the light's saturation
            final float[] ramp = WhyTone.saturatedLightRamp();
            if (ramp[1] > ramp[0]) satLight = new float[]{center, lw[0], ramp[0], ramp[1], ramp[2], ramp[3]};
            center = WhyTone.headroomCenter();
            lw[0] = WhyTone.headroomHlWeight();
        }
        if (!legacy) {
            // P378: the sky mask, once per photo (none: no SKY define in the fusion or the combine, the tone as before); a failure
            // gives the photo no sky term, not a failed shot
            try {
                WhySky.build(glProg, pipeline, st, st.lowDn);
            } catch (RuntimeException e) {
                Log.w("SCAM_PIPELINE", "WHY sky: failed (" + e + "), no sky term");
                if (st.sky != null) st.sky.close();
                st.sky = null;
                st.skyMask = null;
                glProg.closed = true;
            }
            pyramid(st, em, lw, r.ae, center, satLight, started, strength, noiseI);
            return;
        }
        st.fused = guided(st, em, lw, r.ae, center, satLight);
        Log.i("SCAM_PIPELINE", "WHY fusion (guided filter, post_ab old run) " + params(em, lw, center, satLight) + " r=" + (GF_RADIUS > 1 ? GF_RADIUS >> 1 : 1)
                + " eps=" + GF_EPS + " bracketDn=" + (st.lowDn != st.low ? strength : 0f) + " ms=" + (System.currentTimeMillis() - started));
    }

    /** The guided filter's fused luma of whyLow (why/gf, WhyCam's kernel): the post_ab old run, and P349's share in the lights. */
    private GLTexture guided(WhyTone.State st, float[] em, float[] lw, float ae, float center, float[] satLight) {
        final Point lowSize = st.low.mSize;
        final int radius = GF_RADIUS > 1 ? GF_RADIUS >> 1 : 1;                                    // [code 0x78f80-0x78f9c]
        final float eps = GF_EPS;
        Point half = new Point((lowSize.x + 1) / 2, (lowSize.y + 1) / 2);
        GLTexture[] means = new GLTexture[3];
        for (int mode = 0; mode < 3; mode++) {
            GLTexture down = stats(half);
            weightsProgram(mode, st.lowDn, em, lw, ae, center, satLight);
            glProg.drawBlocks(down);
            means[mode] = box(down, radius);
        }
        GLTexture[] ab = new GLTexture[2];
        for (int mode = 0; mode < 2; mode++) {
            GLTexture coef = stats(half);
            glProg.setDefine("MODE", mode);
            glProg.useAssetProgram("why/ab", false);
            glProg.setTexture("MeanII", means[0]);
            glProg.setTexture("MeanP", means[1]);
            glProg.setTexture("MeanIP", means[2]);
            glProg.setVar("epsU", eps);
            glProg.drawBlocks(coef);
            ab[mode] = box(coef, radius);
        }
        for (GLTexture t : means) t.close();
        final GLTexture fused = new GLTexture(lowSize, new GLFormat(GLFormat.DataType.FLOAT_32, 1), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        weightsProgram(3, st.lowDn, em, lw, ae, center, satLight);
        glProg.setTexture("MeanA", ab[0]);
        glProg.setTexture("MeanB", ab[1]);
        glProg.drawBlocks(fused);
        glProg.closed = true;
        ab[0].close();
        ab[1].close();
        return fused;
    }

    /**
     * P340 (owner, 2026-10-25: pairs against LMC 9.6, ArkCam and the stock camera, «сделай так чтобы у нас был лучший результат среди
     * всех пар»): with Bento headroom (recovered light above the clip, ceiling hr) the darkest synthetic exposure reaches that
     * ceiling: at most 2 / (ae x hr) (P341), so the recovered range lands inside the fusion instead of above it. Its multiplier came
     * from the frame's statistics alone (2^(-1.5 effH)), which a setting sun or the moon (< 0.5 % of the frame) never moves:
     * 0.48 and 1.0 on the owner's sunsets, the glow at 2 - 8 x white went white with the disc and the clipped core drew a ring.
     * Host replay of the owner's three bursts: the disc white, the glow orange, the frame's percentiles unchanged (p1 / p50 0.072 /
     * 0.410 against LMC's 0.078 / 0.404). Neutral light keeps its own weights (P291 / P310), so lit paper stays white.
     * P341 (owner, 2026-10-26: «у нас пропадает ступенька возле солнца»): the ceiling at 2 x, not 1 x, of that exposure. At
     * 1 / (ae x hr) the darkest exposure sat 40 x (5.3 stops) below the next one: a ladder rung inside the glow (2 - 5 x white)
     * was dark in it and clipped in the next, well exposed in none, and the pyramid filled it with the glow. At 2 / (ae x hr)
     * the disc itself clips (white, as every camera shows it) and the rung keeps its contrast up to the disc; the glow stays
     * orange.
     */
    static float headroomExposure(float em0, float ae, float clip, float hr) {
        if (!(hr > clip) || !(ae > 0f) || !Float.isFinite(hr)) return em0;
        return Math.min(em0, 2f / (ae * hr));
    }

    private static String params(float[] em, float[] lw, float center, float[] satLight) {
        return "em=" + em[0] + "," + em[1] + "," + em[2] + "," + em[3] + " lw=" + lw[0] + "," + lw[1] + "," + lw[2] + "," + lw[3] + " center=" + center
                + (satLight[0] > 0f ? " saturatedLight=" + satLight[0] + "/" + satLight[1] + "@" + satLight[2] + ".." + satLight[3]
                        + " level " + satLight[4] + ".." + satLight[5] : "");
    }

    /** The guided filter's ef_gf_radius and ef_gf_eps (WhyCam's), since P333 for the post_ab old run and the viewfinder's vf_why 2. */
    static final int GF_RADIUS = 32;
    static final float GF_EPS = 0.001f;

    /** P333: the pyramid's default coarsest level (its shorter side, px of whyLow): 5 levels below a 12 MP shot's whyLow (1536 px). */
    static final float COARSE = 48f;

    /**
     * P333: the levels below whyLow (at least 1): halving (ceil) while the next level's shorter side stays at least {@code coarse}
     * px, so the coarsest band covers the same share of the frame at any size (a 12 MP whyLow 2048 x 1536: 5, 64 x 48; 50 MP: 6).
     */
    static int pyramidLevels(int w, int h, float coarse) {
        final int c = Math.max(2, Math.round(coarse));
        int levels = 0;
        while (Math.min((w + 1) / 2, (h + 1) / 2) >= c && levels < 16) {
            w = (w + 1) / 2;
            h = (h + 1) / 2;
            levels++;
        }
        return Math.max(1, levels);
    }

    /**
     * P391 (item 2, owner: «глубина: самые грубые уровни задают ширину ореола»): the pyramid's depth without jumps across resolutions -
     * {levels, f}: d = log2(shorter side / coarse) of whyLow {@code w} x {@code h}, levels = ceil(d) (at least 1), f = d - (levels - 1)
     * the weight of the lowest band (WhyTone.bands: its reconstruction moved by 1 - f towards the base of one level less, so f = 0
     * is one level less exactly and f = 1 the whole levels). With {@link #pyramidLevels} a 4096 x 3072 sensor (whyLow 1536 rows at ef_coarse 48) got 5
     * levels and a 4000 x 3000 one 4: half the halo width and the clouds' modelling for a 2 % smaller frame; here 5 at f 1 and 5 at
     * f 0.97.
     */
    static float[] depth(int w, int h, float coarse) {
        final int c = Math.max(2, Math.round(coarse));
        final double d = Math.log(Math.min(w, h) / (double) c) / Math.log(2.0);
        if (!(d > 1.0)) return new float[]{1f, 1f};
        final int levels = (int) Math.ceil(d - 1e-6);
        final double f = d - (levels - 1);
        return new float[]{levels, f >= 1.0 - 1e-6 ? 1f : (float) Math.max(0.0, f)};
    }

    /** P333: the Laplacian pyramid fusion (why/lpyr.glsl) into st.fused. */
    private void pyramid(WhyTone.State st, float[] em, float[] lw, float ae, float center, float[] satLight, long started, float strength,
                         float[] noiseI) {
        final Point low = st.low.mSize;
        final float coarse = WhyTone.value("ef_coarse", COARSE);
        // P391: the edge-aware bands (null: off, the pyramid as before)
        final float[] band = WhyTone.bands();
        final boolean bands = band != null;
        // a lab fragment takes the whole frame's depth (its own size would give its lowest band weights blurred ~4x less), as far as
        // the fragment halves (fewer levels than the whole frame only for a fragment under ~2 px at that depth)
        final LabFrame frozen = ((PostPipeline) basePipeline).frozen;
        final boolean fragment = frozen != null && frozen.lowWidth > 0 && frozen.lowHeight > 0;
        final int levels;
        // P391: the lowest band's weight of the fractional depth (1: whole levels)
        float lowBand = 1f;
        if (bands && WhyTone.depthSmooth()) {
            final float[] d = depth(fragment ? frozen.lowWidth : low.x, fragment ? frozen.lowHeight : low.y, coarse);
            levels = Math.min((int) d[0], pyramidLevels(low.x, low.y, 2f));
            if (levels == (int) d[0]) lowBand = d[1];
        } else {
            levels = fragment ? Math.min(pyramidLevels(frozen.lowWidth, frozen.lowHeight, coarse), pyramidLevels(low.x, low.y, 2f))
                    : pyramidLevels(low.x, low.y, coarse);
        }
        final GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4), one = new GLFormat(GLFormat.DataType.FLOAT_32, 1);
        final GLTexture[] L = new GLTexture[levels + 1], W = new GLTexture[levels + 1], F = new GLTexture[levels + 1];
        final GLTexture[] G = new GLTexture[levels + 1];
        // P391: (G, Fpp) of every level and REF_DOWN - 1 beyond (the edge measure's reference), the edge measure D of the levels
        // 1 .. n - 1 (level 0's at whyLow's size), the edge share of the levels 1 .. n
        final int deep = bands ? levels + REF_DOWN - 1 : levels;
        final GLTexture[] GF = new GLTexture[deep + 1], D = new GLTexture[levels + 1], A = new GLTexture[levels + 1];
        GLTexture D0 = null;
        final float[] edge = WhyTone.edgeShare();
        final boolean edgeOn = edge[1] > edge[0];
        // P391 (item 4): the excess's noise cap on the fine levels (with a noise model; lo = hi: off)
        final float[] nz = WhyTone.fusionNoise();
        final boolean noiseCap = bands && noiseI != null && nz[1] > nz[0] && nz[2] > 0;
        final float[] beta = new float[4];
        if (noiseCap) for (int l = 0; l < Math.min(4, (int) nz[2]); l++) beta[l] = WhyTone.BAND_BETA[l];
        GLTexture guidedF = null;
        // P400: the daylit-window term (null: off) at the window level of the log light's pyramid
        final com.particlesdevs.photoncamera.processing.render.Parameters prm = basePipeline.mParameters;
        final float[] win = bands ? windowParams(WhyTone.windowDepth(), st.ae, prm != null
                ? WhySkyMask.evOffset(prm.iso, prm.exposureTime, prm.aperture, st.inScale) : Float.NaN) : null;
        final int winLevel = win != null ? windowLevel(fragment ? frozen.lowWidth : low.x, deep) : 0;
        try {
            Point size = low;
            final GLFormat r16 = new GLFormat(GLFormat.DataType.FLOAT_16, 1), rg16 = new GLFormat(GLFormat.DataType.FLOAT_16, 2);
            for (int l = 1; l <= deep; l++) {
                size = new Point((size.x + 1) / 2, (size.y + 1) / 2);
                if (bands) GF[l] = new GLTexture(size, rg16, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                if (l > levels) continue;
                L[l] = new GLTexture(size, rgba, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                W[l] = new GLTexture(size, rgba, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                F[l] = new GLTexture(size, one, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                if (edgeOn) G[l] = new GLTexture(size, r16, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                if (bands) {
                    if (l < levels) D[l] = new GLTexture(size, r16, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                    A[l] = new GLTexture(size, rg16, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                }
            }
            // level 1 of the lumas and the weights straight from whyLow, then the Gaussian pyramids
            fusionProgram("why/lpyr", 0, st.lowDn, em, lw, ae, center, satLight);
            glProg.drawBlocks(L[1]);
            fusionProgram("why/lpyr", 1, st.lowDn, em, lw, ae, center, satLight);
            glProg.drawBlocks(W[1]);
            for (int l = 2; l <= levels; l++) {
                for (GLTexture[] p : new GLTexture[][]{L, W}) {
                    glProg.setDefine("MODE", 2);
                    glProg.useAssetProgram("why/lpyr", false);
                    glProg.setTexture("InputBuffer", p[l - 1]);
                    glProg.drawBlocks(p[l]);
                }
            }
            if (bands) {
                // P391: level 1 of the guide's log light and the per-pixel fusion from whyLow (MODE 7), the Gaussian pyramid of both
                // REF_DOWN - 1 levels beyond the pyramid's
                fusionProgram("why/lpyr", 7, st.lowDn, em, lw, ae, center, satLight);
                glProg.setVar("bandEdgeU", 0f, 0f, band[2], band[3]);
                glProg.drawBlocks(GF[1]);
                for (int l = 2; l <= deep; l++) {
                    glProg.setDefine("MODE", 2);
                    glProg.useAssetProgram("why/lpyr", false);
                    glProg.setTexture("InputBuffer", GF[l - 1]);
                    glProg.drawBlocks(GF[l]);
                }
                // the edge measure D of level 0 (MODE 9, whyLow's size) and of the levels 1 .. n - 1 (MODE 8): the guide's log light
                // against its Gaussian REF_DOWN levels down, its noise taken out
                D0 = new GLTexture(low, new GLFormat(GLFormat.DataType.FLOAT_16, 1), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                fusionProgram("why/lpyr", 9, st.lowDn, em, lw, ae, center, satLight);
                glProg.setTexture("RefG", GF[REF_DOWN]);
                measureUniforms(band, noiseI, 0);
                glProg.drawBlocks(D0);
                for (int l = 1; l < levels; l++) {
                    glProg.setDefine("MODE", 8);
                    glProg.useAssetProgram("why/lpyr", false);
                    glProg.setTexture("LevelGF", GF[l]);
                    glProg.setTexture("RefG", GF[l + REF_DOWN]);
                    measureUniforms(band, noiseI, l);
                    glProg.drawBlocks(D[l]);
                }
                // the edge share of the levels 1 .. n (MODE 10): the ramp of the level's cone max of D, at least the finer level's
                // carried 3 x 3 (a coarse band never keeps more excess than the finer one round the same edge); the coarsest the carry
                for (int l = 1; l <= levels; l++) {
                    glProg.setDefine("MODE", 10);
                    glProg.useAssetProgram("why/lpyr", false);
                    glProg.setTexture("FineA", l == 1 ? D0 : A[l - 1]);
                    if (l < levels) glProg.setTexture("LevelA", D[l]);
                    shareUniforms(band, l, l == levels);
                    glProg.drawBlocks(A[l]);
                }
                for (int l = 1; l < levels; l++) {
                    D[l].close();
                    D[l] = null;
                }
                for (int l = levels + 1; l <= deep; l++) {
                    if (l == winLevel) continue;
                    GF[l].close();
                    GF[l] = null;
                }
            }
            // window halo: the guide's log light down to the coarsest level (MODE 6, then the plain reduce), for MODE 5's edge share
            final float edgeScale = (float) (1 << levels);
            if (edgeOn) {
                fusionProgram("why/lpyr", 6, st.lowDn, em, lw, ae, center, satLight);
                glProg.setVar("edgeMixU", edge[0], edge[1], edge[2], edgeScale);
                glProg.drawBlocks(G[1]);
                for (int l = 2; l <= levels; l++) {
                    glProg.setDefine("MODE", 2);
                    glProg.useAssetProgram("why/lpyr", false);
                    glProg.setTexture("InputBuffer", G[l - 1]);
                    glProg.drawBlocks(G[l]);
                    G[l - 1].close();
                    G[l - 1] = null;
                }
            }
            // the coarsest level blended by its weights (P391 bands: moved towards the per-pixel fusion's by its carried edge share),
            // then every band from coarse to fine
            if (bands) glProg.setDefine("EDGE_BANDS", 1);
            glProg.setDefine("MODE", 3);
            glProg.useAssetProgram("why/lpyr", false);
            glProg.setTexture("LevelL", L[levels]);
            glProg.setTexture("LevelW", W[levels]);
            if (bands) {
                glProg.setTexture("LevelGF", GF[levels]);
                glProg.setTexture("LevelA", A[levels]);
                glProg.setVar("bandEdgeU", 0f, 0f, band[2], band[3]);
            }
            glProg.drawBlocks(F[levels]);
            for (int l = levels - 1; l >= 1; l--) {
                if (bands) glProg.setDefine("EDGE_BANDS", 1);
                glProg.setDefine("MODE", 4);
                glProg.useAssetProgram("why/lpyr", false);
                glProg.setTexture("LevelL", L[l]);
                glProg.setTexture("LevelW", W[l]);
                glProg.setTexture("CoarseL", L[l + 1]);
                glProg.setTexture("CoarseF", F[l + 1]);
                if (bands) {
                    glProg.setTexture("LevelGF", GF[l]);
                    glProg.setTexture("CoarseGF", GF[l + 1]);
                    glProg.setTexture("LevelA", A[l]);
                    bandUniforms(band, noiseCap ? nz : null, noiseI, beta, l, l == levels - 1 ? 1f - lowBand : 0f);
                }
                glProg.drawBlocks(F[l]);
            }
            // P349: the guided filter's fusion for the light above white (no Mertens halo round dark objects in a glow)
            final float[] gmix = WhyTone.guidedLights();
            if (gmix[1] > gmix[0] || edgeOn) {
                for (GLTexture t : W) if (t != null) t.close();
                java.util.Arrays.fill(W, null);
                guidedF = guided(st, em, lw, ae, center, satLight);
            }
            st.fused = new GLTexture(low, one, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            // P378 (M1): the clear sky keeps the pyramid's fusion of its clouds (only with the guided share and a sky)
            final boolean sky = guidedF != null && st.sky != null;
            if (sky) glProg.setDefine("SKY", 1);
            if (bands) glProg.setDefine("EDGE_BANDS", 1);
            if (win != null) glProg.setDefine("WINDOW", 1);
            fusionProgram("why/lpyr", 5, st.lowDn, em, lw, ae, center, satLight);
            if (win != null) {
                glProg.setTexture("WinMean", GF[winLevel]);
                glProg.setVar("winU", win[0], win[1], win[2], 0f);
                glProg.setVar("winScaleU", (float) (1 << winLevel));
            }
            glProg.setTexture("CoarseL", L[1]);
            glProg.setTexture("CoarseF", F[1]);
            if (bands) {
                glProg.setTexture("CoarseGF", GF[1]);
                glProg.setTexture("LevelA", D0);
                bandUniforms(band, noiseCap ? nz : null, noiseI, beta, 0, levels == 1 ? 1f - lowBand : 0f);
            }
            if (guidedF != null) {
                glProg.setTexture("GuidedF", guidedF);
                glProg.setVar("guidedMixU", gmix[0], gmix[1]);
            } else {
                // P391: no guided filter (its share and the edge share off with the bands): a linked program keeps its uniforms, so
                // a share set by an earlier shot must not read an unbound GuidedF
                glProg.setVar("guidedMixU", 0f, 0f);
            }
            if (sky) {
                glProg.setTexture("WhySky", st.sky);
                glProg.setVar("skyGuidedU", WhyTone.sky());
            }
            // set on every shot (a linked program keeps its uniforms): zeros = the share off
            if (edgeOn) glProg.setTexture("GuideC", G[levels]);
            glProg.setVar("edgeMixU", edgeOn ? edge[0] : 0f, edgeOn ? edge[1] : 0f, edgeOn ? edge[2] : 0f, edgeScale);
            glProg.drawBlocks(st.fused);
            glProg.closed = true;
        } finally {
            for (GLTexture[] p : new GLTexture[][]{L, W, F, G, GF, D, A}) for (GLTexture t : p) if (t != null) t.close();
            if (D0 != null) D0.close();
            if (guidedF != null) guidedF.close();
        }
        final float[] gmixLog = WhyTone.guidedLights();
        Log.i("SCAM_PIPELINE", "WHY fusion (Laplacian pyramid) " + params(em, lw, center, satLight) + " levels=" + levels
                + (lowBand < 1f ? String.format(java.util.Locale.ROOT, " lowBand=%.3f", lowBand) : "") + " coarse=" + coarse
                + (bands ? " bands=" + band[0] + ".." + band[1] + "EV reach=" + (int) band[4] + " fall=" + band[5] + " strength=" + band[3]
                        + (noiseI != null && WhyTone.bandsNoise() > 0f ? " edgeNoise=" + WhyTone.bandsNoise() + "sigma" : "")
                        + (noiseCap ? " noiseCap=" + nz[0] + ".." + nz[1] + "sigma levels=" + (int) nz[2] : " noiseCap=off") : " bands=off")
                + (noiseI != null ? String.format(java.util.Locale.ROOT, " noise S=%.3g O=%.3g", noiseI[0], noiseI[1]) : " noise=none")
                + (deepNoise[0] > 0f ? " deepNoise=" + deepNoise[0] : "")
                + (gmixLog[1] > gmixLog[0] ? " guidedShare=" + gmixLog[0] + ".." + gmixLog[1] : "")
                + (edgeOn ? " edgeShare=" + edge[0] + ".." + edge[1] + "EV floor=" + edge[2] : "")
                + (win != null ? String.format(java.util.Locale.ROOT, " window=%.3f@%.2f..%.2f level %d", win[0], win[1], win[2], winLevel) : "")
                + " bracketDn=" + (st.lowDn != st.low ? strength : 0f) + " ms=" + (System.currentTimeMillis() - started));
    }

    /**
     * P400: the daylit-window term of the fusion (why/lpyr WINDOW) - {strength, the window's log light ramp lo, hi (log2 of the guide
     * I = Y x AE, lpyr's G)} - or null (off): only with Bento headroom (r.hr above the clip) and a known exposure ({@code ev}: the
     * EV100 of a whyLow value of 1, WhySkyMask.evOffset), in a room (the frame's median at EV 10 or less, gone from EV 11.5: no
     * landscape under a bright sky takes it); the window is daylight at EV 10.5 .. 12.
     */
    static float[] windowParams(float depth, WhyAe.Result r, float ev) {
        if (!(depth > 0f) || r == null || !(r.hr > r.clip) || !Float.isFinite(ev) || !(r.ae > 0f)) return null;
        final float median = (float) (Math.log(Math.max(r.p50, 1e-6f)) / Math.log(2.0)) + ev;
        final float t = Math.max(0f, Math.min(1f, (median - 10f) / 1.5f));
        final float strength = depth * (1f - t * t * (3f - 2f * t));
        if (strength < 1e-4f) return null;
        final float shift = (float) (Math.log(r.ae) / Math.log(2.0)) - ev;
        return new float[]{strength, 10.5f + shift, 12f + shift};
    }

    /** P400: the window level: the Gaussian of the log light at about 1 / 110 of whyLow's width (level 5 at 2048 px). */
    static int windowLevel(int width, int deepest) {
        final int l = Math.round((float) (Math.log(Math.sqrt(3.0) * width / 111.0) / Math.log(2.0)));
        return Math.max(1, Math.min(l, deepest));
    }

    /** P391: the edge measure's reference is the guide's Gaussian this many levels down (lpyr RefG; its node spacing 2^REF_DOWN). */
    static final int REF_DOWN = 3;

    /**
     * P391: the bands' uniforms of a MODE 4 / 5 draw of level {@code level} (EDGE_BANDS 1): the level's edge ramp, its reach and fall
     * (MODE 5: level 0's share is the cone max of D0 in place), the noise cap ({@code nz} null: off), the level and the share of its
     * reconstruction moved to one level less ({@code lowCut}: 1 - f on the lowest band). Set on every draw (a linked program keeps
     * its uniforms).
     */
    private void bandUniforms(float[] band, float[] nz, float[] noiseI, float[] beta, int level, float lowCut) {
        final float[] ramp = WhyTone.edgeRamp(band, level);
        glProg.setVar("bandEdgeU", ramp[0], ramp[1], band[2], band[3]);
        glProg.setVar("bandReachU", (int) band[4], 0);
        glProg.setVar("coneU", ramp[2], 0f, 0f, 0f);
        if (nz != null) {
            glProg.setVar("noiseBandU", nz[0], nz[1], noiseI[0], noiseI[1]);
            glProg.setVar("noiseBetaU", beta[0], beta[1], beta[2], beta[3]);
        } else {
            glProg.setVar("noiseBandU", 0f, 0f, 0f, 0f);
            glProg.setVar("noiseBetaU", 0f, 0f, 0f, 0f);
        }
        glProg.setVar("levelU", level);
        glProg.setVar("lowCutU", lowCut);
    }

    /**
     * P391: the uniforms of a MODE 8 / 9 draw (the edge measure D of level {@code level}): the guide's light floor, RefG's node
     * spacing and the noise taken out of D in quadrature - WhyTone.bandsNoise() sigmas of it (zeros without a noise model).
     */
    private void measureUniforms(float[] band, float[] noiseI, int level) {
        glProg.setVar("bandEdgeU", 0f, 0f, band[2], band[3]);
        glProg.setVar("refScaleU", (float) (1 << REF_DOWN));
        final float k = WhyTone.bandsNoise();
        if (noiseI != null && k > 0f) glProg.setVar("bandNoiseU", k * WhyTone.edgeBeta(level), noiseI[0], noiseI[1]);
        else glProg.setVar("bandNoiseU", 0f, 0f, 0f);
    }

    /**
     * P391: the uniforms of a MODE 10 draw (the edge share of level {@code level} >= 1): its own ramp, reach and fall (none on the
     * coarsest level), and the carry: from level 0's D on level 1 (level 0's ramp and fall, its window one wider: the 3 x 3 max of
     * level 0's share), else the 3 x 3 max of the finer level's share.
     */
    private void shareUniforms(float[] band, int level, boolean coarsest) {
        final float[] ramp = WhyTone.edgeRamp(band, level), ramp0 = WhyTone.edgeRamp(band, 0);
        glProg.setVar("bandEdgeU", ramp[0], ramp[1], band[2], band[3]);
        glProg.setVar("bandReachU", coarsest ? -1 : (int) band[4], level == 1 ? (int) band[4] : 1);
        if (level == 1) glProg.setVar("coneU", ramp[2], ramp0[0], ramp0[1], ramp0[2]);
        else glProg.setVar("coneU", ramp[2], 0f, 0f, 0f);
    }
}
