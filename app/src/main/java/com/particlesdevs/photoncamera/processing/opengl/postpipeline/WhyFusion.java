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
        if (!com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy()) {
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
            pyramid(st, em, lw, r.ae, center, satLight, started, strength);
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

    /** P333: the Laplacian pyramid fusion (why/lpyr.glsl) into st.fused. */
    private void pyramid(WhyTone.State st, float[] em, float[] lw, float ae, float center, float[] satLight, long started, float strength) {
        final Point low = st.low.mSize;
        final float coarse = WhyTone.value("ef_coarse", COARSE);
        // a lab fragment takes the whole frame's depth (its own size would give its lowest band weights blurred ~4x less), as far as
        // the fragment halves (fewer levels than the whole frame only for a fragment under ~2 px at that depth)
        final LabFrame frozen = ((PostPipeline) basePipeline).frozen;
        final int levels = frozen != null && frozen.lowWidth > 0 && frozen.lowHeight > 0
                ? Math.min(pyramidLevels(frozen.lowWidth, frozen.lowHeight, coarse), pyramidLevels(low.x, low.y, 2f))
                : pyramidLevels(low.x, low.y, coarse);
        final GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4), one = new GLFormat(GLFormat.DataType.FLOAT_32, 1);
        final GLTexture[] L = new GLTexture[levels + 1], W = new GLTexture[levels + 1], F = new GLTexture[levels + 1];
        final GLTexture[] G = new GLTexture[levels + 1];
        final float[] edge = WhyTone.edgeShare();
        final boolean edgeOn = edge[1] > edge[0];
        GLTexture guidedF = null;
        try {
            Point size = low;
            for (int l = 1; l <= levels; l++) {
                size = new Point((size.x + 1) / 2, (size.y + 1) / 2);
                L[l] = new GLTexture(size, rgba, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                W[l] = new GLTexture(size, rgba, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                F[l] = new GLTexture(size, one, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
                if (edgeOn) G[l] = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16, 1), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
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
            // the coarsest level blended by its weights, then every band from coarse to fine
            glProg.setDefine("MODE", 3);
            glProg.useAssetProgram("why/lpyr", false);
            glProg.setTexture("LevelL", L[levels]);
            glProg.setTexture("LevelW", W[levels]);
            glProg.drawBlocks(F[levels]);
            for (int l = levels - 1; l >= 1; l--) {
                glProg.setDefine("MODE", 4);
                glProg.useAssetProgram("why/lpyr", false);
                glProg.setTexture("LevelL", L[l]);
                glProg.setTexture("LevelW", W[l]);
                glProg.setTexture("CoarseL", L[l + 1]);
                glProg.setTexture("CoarseF", F[l + 1]);
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
            fusionProgram("why/lpyr", 5, st.lowDn, em, lw, ae, center, satLight);
            glProg.setTexture("CoarseL", L[1]);
            glProg.setTexture("CoarseF", F[1]);
            if (guidedF != null) {
                glProg.setTexture("GuidedF", guidedF);
                glProg.setVar("guidedMixU", gmix[0], gmix[1]);
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
            for (GLTexture[] p : new GLTexture[][]{L, W, F, G}) for (GLTexture t : p) if (t != null) t.close();
            if (guidedF != null) guidedF.close();
        }
        Log.i("SCAM_PIPELINE", "WHY fusion (Laplacian pyramid) " + params(em, lw, center, satLight) + " levels=" + levels + " coarse=" + coarse
                + (edgeOn ? " edgeShare=" + edge[0] + ".." + edge[1] + "EV floor=" + edge[2] : "")
                + " bracketDn=" + (st.lowDn != st.low ? strength : 0f) + " ms=" + (System.currentTimeMillis() - started));
    }
}
