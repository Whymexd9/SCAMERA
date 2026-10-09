package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * Highlight recovery for the SCAM HDR RGB (colour propagation, as in the DaVinci Resolve
 * highlight recovery): pixels whose channels all clipped at the sensor white carry no colour,
 * and white balance turns them magenta (the R and B gains exceed G). Their colour is rebuilt
 * from the surrounding unclipped pixels, keeping only their luminance. Runs on the
 * white-balanced linear image right after {@link ScamRgb}; tone and colour stay untouched.
 * SCAM HDR (SCAM network) route only: SCAM Hybrid shots get the per-channel recovery inside {@link ScamRgb}.
 */
public final class HighlightRecovery extends Node {
    public HighlightRecovery() { super("", "HighlightRecovery"); }
    @Override public void Compile() {}

    /** White-balanced luminance (about the 98.5th percentile) that marks the scene's highlight level. */
    private static float highlightReference(ByteBuffer rgb, float[] wp) {
        FloatBuffer f = rgb.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
        int n = f.limit() / 3;
        float[] samples = new float[n / 17 + 1];
        int count = 0;
        for (int i = 0; i < n; i += 17) {
            float r = Math.max(0f, f.get(i * 3)) / Math.max(wp[0], 1e-6f);
            float g = Math.max(0f, f.get(i * 3 + 1)) / Math.max(wp[1], 1e-6f);
            float b = Math.max(0f, f.get(i * 3 + 2)) / Math.max(wp[2], 1e-6f);
            samples[count++] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        }
        if (count == 0) return 0f;
        Arrays.sort(samples, 0, count);
        return samples[Math.min(count - 1, (int) (count * 0.985f))];
    }

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        float strength = Math.max(0f, Math.min(1f, PreferenceKeys.routeInternalValue("highlight_recovery", 100f) / 100f));
        float[] wp = pipeline.mParameters.whitePoint;
        // Fully clipped pixel: equal raw channels, so after white balance min(R,B)/G = min(wpG/wpR, wpG/wpB).
        float kFull = Math.min(wp[1] / Math.max(wp[0], 1e-6f), wp[1] / Math.max(wp[2], 1e-6f));
        // SCAM Hybrid (with or without Bento): the per-channel recovery already ran in ScamRgb, on the camera channels
        // before WB (G of a white highlight from R and B, all clipped -> neutral white). Propagating the surroundings'
        // colour into those whites here would undo it.
        if (PreferenceKeys.isHybridShot() || ScamHybridBurst.lastBentoApplied) {
            Log.i("SCAM_PIPELINE", "highlightRecovery skipped: SCAM Hybrid, per-channel recovery in ScamRgb");
            glProg.closed = true;
            return;
        }
        if (strength <= 0f || kFull < 1.15f || pipeline.mParameters.scamRgb == null) { glProg.closed = true; return; }
        float yRef = highlightReference(pipeline.mParameters.scamRgb, wp);
        if (yRef <= 0f) { glProg.closed = true; return; }
        long started = System.currentTimeMillis();
        GLTexture input = previousNode.WorkingTexture;
        GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        // The colour neighbourhoods are 8x8 and 32x32 SENSOR pixels: on the Sabre 2x grid (outputScale 2) the
        // blocks are dilated by s so a blown window borrows colour from the same distance as at 1x. At s = 1 the
        // sizes and the shaders' loops are exactly those of before; the 4x4 reduce needs no change (4 * 8s = 32s).
        final int s = Math.max(1, Math.round(pipeline.mParameters.outputScale));
        final int block = 8 * s;
        Point near = new Point((input.mSize.x + block - 1) / block, (input.mSize.y + block - 1) / block);
        Point wide = new Point((near.x + 3) / 4, (near.y + 3) / 4);
        GLTexture chromaNear = new GLTexture(near, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        GLTexture chromaWide = new GLTexture(wide, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        try {
            glProg.useAssetProgram("hlrecovery/prep", false);
            glProg.setTexture("InputBuffer", input);
            glProg.setVar("kFull", kFull);
            glProg.setVar("yRef", yRef);
            glProg.setVar("block", block);
            glProg.drawBlocks(chromaNear);
            glProg.useAssetProgram("hlrecovery/reduce", false);
            glProg.setTexture("InputBuffer", chromaNear);
            glProg.drawBlocks(chromaWide);
            glProg.useAssetProgram("hlrecovery/apply", false);
            glProg.setTexture("InputBuffer", input);
            glProg.setTexture("Chroma8", chromaNear);
            glProg.setTexture("Chroma32", chromaWide);
            glProg.setVar("kFull", kFull);
            glProg.setVar("yRef", yRef);
            glProg.setVar("strength", strength);
            glProg.setVar("pxStepU", s); // 3x3 tint test one sensor pixel apart
            WorkingTexture = pipeline.getMain();
            glProg.drawBlocks(WorkingTexture);
            glProg.closed = true;
        } finally {
            chromaNear.close();
            chromaWide.close();
        }
        Log.i("SCAM_PIPELINE", "highlightRecovery strength=" + strength + " kFull=" + kFull + " yRef=" + yRef
                + " ms=" + (System.currentTimeMillis() - started));
    }
}
