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
 * mod's exposure fusion of the luminance - four synthetic exposures (2^-1.5H, 1, 2^S, 2^2S) of the AE result, ACES
 * lumas and Gaussian weights, blended by a fast guided filter (2x2 down, box r = ef_gf_radius / 2, eps, box, bilinear
 * up) into "fused", the display luma WhyCombine inverts to the per-pixel gain. All statistics in RGBA32F as the
 * kernel's float buffers; the boxes average weights only and never reconstruct image detail. The image passes through.
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
        glProg.setDefine("MODE", mode);
        glProg.useAssetProgram("why/gf", false);
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
        st.lowDn = st.low;
        if (r.clip > 1.05f && strength > 0.001f) {
            st.lowDn = new GLTexture(lowSize, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("why/bracket_dn", false);
            glProg.setTexture("InputBuffer", st.low);
            glProg.setVar("strengthU", strength);
            glProg.drawBlocks(st.lowDn);
        }
        float[] em = WhyAe.exposures(r);
        // layer weights in the kernel's order (hl, mid, ext_hl, shadow) [code 0x78b18-0x78b3c]
        float[] lw = {WhyTone.value("weight_hl", 0.8f), WhyTone.value("weight_mid", 1.0f),
                WhyTone.value("weight_ext_hl", 0.7f), WhyTone.value("weight_shadow", 0.5f)};
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
        int radius = Math.max(1, Math.round(WhyTone.value("gf_radius", 32f)));
        radius = radius > 1 ? radius >> 1 : 1;                                                  // [code 0x78f80-0x78f9c]
        // ef_gf_eps in units of 0.001 (the settings bar shows two decimals): max(eps, 1e-6) as the mod
        float eps = Math.max(WhyTone.value("gf_eps_e3", 1f) * 0.001f, 1e-6f);
        Point half = new Point((lowSize.x + 1) / 2, (lowSize.y + 1) / 2);
        GLTexture[] means = new GLTexture[3];
        for (int mode = 0; mode < 3; mode++) {
            GLTexture down = stats(half);
            weightsProgram(mode, st.lowDn, em, lw, r.ae, center, satLight);
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
        st.fused = new GLTexture(lowSize, new GLFormat(GLFormat.DataType.FLOAT_32, 1), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        weightsProgram(3, st.lowDn, em, lw, r.ae, center, satLight);
        glProg.setTexture("MeanA", ab[0]);
        glProg.setTexture("MeanB", ab[1]);
        glProg.drawBlocks(st.fused);
        glProg.closed = true;
        ab[0].close();
        ab[1].close();
        Log.i("SCAM_PIPELINE", "WHY fusion em=" + em[0] + "," + em[1] + "," + em[2] + "," + em[3] + " lw=" + lw[0] + "," + lw[1] + ","
                + lw[2] + "," + lw[3] + " center=" + center
                + (satLight[0] > 0f ? " saturatedLight=" + satLight[0] + "/" + satLight[1] + "@" + satLight[2] + ".." + satLight[3]
                        + " level " + satLight[4] + ".." + satLight[5] : "") + " r=" + radius + " eps=" + eps + " bracketDn=" + (st.lowDn != st.low ? strength : 0f)
                + " half=" + half.x + "x" + half.y + " ms=" + (System.currentTimeMillis() - started));
    }
}
