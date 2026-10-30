package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * P402 (owner, 2026-10-29: «Перенеси то чего нам не хватает», the reference app's settings): «Пирамидальный шумодав» - its ms
 * denoise (why/msdn.glsl) on the merge, after the merge's own denoise and before the tone: OKLab of the render's linear sRGB, three
 * Laplacian levels and a base, luminance detail soft-thresholded and colour detail pulled towards a luminance-guided mean. Its
 * thresholds as the reference app's driver sets them: 0.012 x «Яркостный шумодав» and 0.02 x «Цветовой шумодав», x a level's own
 * share, x the noise model's factor (the frame's SNR at grey 0.18: 20 / SNR in 0.4 .. 3.5, eased by «Масштаб модели шума» into
 * 0.25 .. 4). Off by default (pref_scam_hybrid_why_ms_denoise); a 200 MP shot is left alone (its level 1 alone would be 400 MB).
 * Where the merge kept few frames (its frame map) the detail thresholds rise up to x1.3 (luminance) and x1.5 (colour) x «Усиление в
 * зонах движения».
 */
public final class WhyMsDenoise extends Node {
    public WhyMsDenoise() { super("", "WhyMsDenoise"); }

    @Override
    public void Compile() {}

    static boolean enabled() {
        return PreferenceKeys.hybridSwitch("why_ms_denoise", true);
    }

    /** The noise model's factor of the thresholds (1 without a model or with the scale at 0). */
    static float modelFactor(float noiseS, float noiseO, float scale) {
        if (!(scale > 0.001f) || !(noiseS > 0f || noiseO > 0f)) return 1f;
        final double sigma = Math.sqrt(Math.max(Math.max(noiseS, 0f) * 0.18 + Math.max(noiseO, 0f), 1e-10));
        final double nf = Math.max(0.4, Math.min(3.5, 20.0 / Math.max(0.18 / sigma, 1.0)));
        return (float) Math.max(0.25, Math.min(4.0, 1.0 + (nf - 1.0) * scale));
    }

    /** The levels' default shares (the owner's reference config, 2026-10-29). */
    static final float[] LUMA_LEVELS = {0.4f, 0.1f, 0.35f}, CHROMA_LEVELS = {0.2f, 0.4f, 0.6f};

    /** {luma, chroma} thresholds of level 0, 1, 2 and the base's chroma (index 3): the settings x the model factor. */
    static float[][] thresholds(float model) {
        final float luma = 0.012f * Math.max(0f, WhyTone.value("ms_luma", 0.2f)) * model;
        final float chroma = 0.02f * Math.max(0f, WhyTone.value("ms_chroma", 0.5f)) * model;
        final float[][] t = new float[4][2];
        for (int l = 0; l < 3; l++) {
            t[l][0] = luma * Math.max(0f, WhyTone.value("ms_luma_l" + l, LUMA_LEVELS[l]));
            t[l][1] = chroma * Math.max(0f, WhyTone.value("ms_chroma_l" + l, CHROMA_LEVELS[l]));
        }
        t[3][1] = t[2][1];   // the base takes the coarsest level's colour share, as the reference app's driver
        return t;
    }

    static float[] inverse3(float[] m) {
        final double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        final double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-12) return null;
        return new float[]{(float) ((e * i - f * h) / det), (float) ((c * h - b * i) / det), (float) ((b * f - c * e) / det),
                (float) ((f * g - d * i) / det), (float) ((a * i - c * g) / det), (float) ((c * d - a * f) / det),
                (float) ((d * h - e * g) / det), (float) ((b * g - a * h) / det), (float) ((a * e - b * d) / det)};
    }

    /** The motion boost's {luminance, colour} at full motion for the setting {@code k} (1: the reference app's 0.30, 0.50). */
    static float[] motionBoost(float k) {
        k = Math.max(0f, Math.min(3f, k));
        return new float[]{0.30f * k, 0.50f * k};
    }

    /** Frames per code of the merge's frame map (its median code 64 stands for {@code median} frames); NaN without one. */
    static float effScale(float median) {
        return median > 0f && !Float.isInfinite(median) ? median / 64f : Float.NaN;
    }

    private static Point half(Point p) { return new Point((p.x + 1) / 2, (p.y + 1) / 2); }

    @Override
    public void Run() {
        final PostPipeline pipeline = (PostPipeline) basePipeline;
        final GLTexture input = previousNode.WorkingTexture;
        WorkingTexture = input;
        glProg.closed = true;
        if (!enabled()) return;
        if ((long) input.mSize.x * input.mSize.y > 100_000_000L) {
            Log.i("SCAM_PIPELINE", "ms denoise skipped: a 200 MP shot");
            return;
        }
        final float[] toRender = ScamRgb.toRender(pipeline), fromRender = inverse3(toRender);
        if (fromRender == null) return;
        final float[] wp = pipeline.mParameters.whitePoint;
        final long started = System.currentTimeMillis();
        final float model = modelFactor(pipeline.noiseS, pipeline.noiseO, WhyTone.value("ms_model_scale", 1.5f));
        final float[][] th = thresholds(model);
        final float shadow = Math.max(0f, WhyTone.value("ms_shadow", 1.5f));
        final List<GLTexture> owned = new ArrayList<>();
        final float[] motion = motionBoost(WhyTone.value("ms_motion", 1f));
        if (com.particlesdevs.photoncamera.settings.MotionMode.sharpPriority()) motion[0] = -0.75f;
        final java.nio.ByteBuffer eff = ScamHybridBurst.lastEffectiveFrames;
        final float effScale = effScale(ScamHybridBurst.lastEffectiveMedian);
        try {
            GLTexture effMap = null;
            if ((motion[0] != 0f || motion[1] != 0f) && eff != null && !Float.isNaN(effScale) && eff.capacity() == input.mSize.x * input.mSize.y) {
                effMap = ScamDenoise.effectiveFramesTexture(input.mSize, eff);
                owned.add(effMap);
            }
            final GLTexture[] lab = new GLTexture[4];   // lab[0]: the input itself (OKLab per pixel in the shader)
            Point size = input.mSize;
            for (int l = 1; l <= 3; l++) {
                size = half(size);
                lab[l] = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                owned.add(lab[l]);
                glProg.setDefine("MODE", l == 1 ? 0 : 1);
                glProg.useAssetProgram("why/msdn", false);
                glProg.setTexture("InputBuffer", l == 1 ? input : lab[l - 1]);
                glProg.setVar("toRenderU", toRender);
                glProg.setVar("neutralU", wp[0], wp[1], wp[2]);
                glProg.drawBlocks(lab[l]);
            }
            GLTexture rebuilt = new GLTexture(lab[3].mSize, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            owned.add(rebuilt);
            glProg.setDefine("MODE", 2);
            glProg.useAssetProgram("why/msdn", false);
            glProg.setTexture("InputBuffer", lab[3]);
            glProg.setVar("chromaThU", th[3][1]);
            glProg.setVar("shadowU", shadow);
            glProg.drawBlocks(rebuilt);
            for (int l = 2; l >= 1; l--) {
                final GLTexture next = new GLTexture(lab[l].mSize, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                owned.add(next);
                glProg.setDefine("MODE", 3);
                motionDefine(effMap);
                glProg.useAssetProgram("why/msdn", false);
                glProg.setTexture("InputBuffer", lab[l]);
                glProg.setTexture("Coarse", lab[l + 1]);
                glProg.setTexture("CoarseRecon", rebuilt);
                glProg.setVar("lumaThU", th[l][0]);
                glProg.setVar("chromaThU", th[l][1]);
                glProg.setVar("shadowU", shadow);
                glProg.setVar("levelU", l);
                motionVars(effMap, effScale, motion);
                glProg.drawBlocks(next);
                rebuilt = next;
            }
            final GLTexture out = pipeline.getMain();
            glProg.setDefine("MODE", 4);
            motionDefine(effMap);
            glProg.useAssetProgram("why/msdn", false);
            glProg.setTexture("InputBuffer", input);
            glProg.setTexture("Coarse", lab[1]);
            glProg.setTexture("CoarseRecon", rebuilt);
            glProg.setVar("toRenderU", toRender);
            glProg.setVar("fromRenderU", fromRender);
            glProg.setVar("neutralU", wp[0], wp[1], wp[2]);
            glProg.setVar("lumaThU", th[0][0]);
            glProg.setVar("chromaThU", th[0][1]);
            glProg.setVar("shadowU", shadow);
            glProg.setVar("levelU", 0);
            motionVars(effMap, effScale, motion);
            glProg.drawBlocks(out);
            WorkingTexture = out;
            Log.i("SCAM_PIPELINE", "ms denoise: model x" + model + " luma " + th[0][0] + "/" + th[1][0] + "/" + th[2][0] + " chroma "
                    + th[0][1] + "/" + th[1][1] + "/" + th[2][1] + " shadow " + shadow + (effMap != null ? " motion " + motion[0] + "/"
                    + motion[1] : "") + " ms=" + (System.currentTimeMillis() - started));
        } finally {
            for (GLTexture t : owned) t.close();
            glProg.closed = true;
        }
    }

    private void motionDefine(GLTexture effMap) {
        if (effMap != null) glProg.setDefine("MOTION", 1);
    }

    private void motionVars(GLTexture effMap, float effScale, float[] motion) {
        if (effMap == null) return;
        glProg.setTexture("EffMap", effMap);
        glProg.setVar("effScaleU", effScale);
        glProg.setVar("motionU", motion[0], motion[1]);
    }
}
