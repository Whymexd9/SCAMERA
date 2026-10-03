package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.GLUtils;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * Exposure fusion tone mapping for the NICE RGB output (HDR+ style): a dark
 * (highlight) and a bright (normal/shadow) synthetic exposure of the linear
 * HDR frame are Mertens-fused on a Laplacian pyramid. The result is handed to
 * HeadroomRender as its FusionMap (linear local gains), so colour, lens
 * shading and the SCAMERA tone controls stay in one renderer. Runs at half
 * resolution; the image itself passes through unchanged.
 */
public final class NiceExposureFusion extends Node {
    public NiceExposureFusion() { super("", "NiceExposureFusion"); }
    @Override public void Compile() {}
    @Override public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        float strength = PreferenceKeys.niceInternalValue("fusion_strength", 1f);
        if (!PreferenceKeys.isNiceFusionEnabled() || strength <= 0f) { glProg.closed = true; return; }
        float darkEv = PreferenceKeys.niceInternalValue("fusion_dark_ev", 1f);
        float brightEv = PreferenceKeys.niceInternalValue("fusion_bright_ev", 0.3f);
        float detail = PreferenceKeys.niceInternalValue("fusion_detail", 1.5f);
        float sigma = PreferenceKeys.niceInternalValue("fusion_sigma", 0.2f);
        boolean liftOnly = PreferenceKeys.isNiceFusionLiftOnly();
        final boolean softTone = PreferenceKeys.isNiceSoftTone();
        if (softTone) {
            // The shadow lift follows the scene: a low-contrast scene (daylight, 4 stops between its 10th and 99th
            // percentile) is rendered as it is, a room with bright windows (8+ stops) gets the full local lift.
            float lo = PreferenceKeys.niceInternalValue("tone_dr_lo", 4f), span = PreferenceKeys.niceInternalValue("tone_dr_span", 3.5f);
            strength *= Math.max(0f, Math.min(1f, (pipeline.sceneDynamicRange - lo) / Math.max(span, 0.1f)));
            if (strength <= 0.02f) { glProg.closed = true; return; }
        }
        float exposure = Math.max(1f, pipeline.linearDisplayGain)
                * (softTone ? 1f : AgxTone.exposureMultiplier());
        // SCAM HDR hybrid with Bento: the merged frame carries real content up to k x white (ultrashort frame). As in
        // the HDR+ finish, the dark synthetic exposure takes that range into the fusion, so windows keep their local
        // contrast instead of being flattened by the per-pixel AgX knee; the map may darken (no lift-only) there.
        final boolean bento = !softTone && PreferenceKeys.isNiceHybridEnabled() && LmcHybridBurst.lastBentoApplied;
        float bentoHeadroom = 0f;
        if (bento) {
            final float k = Math.max(1f, LmcHybridBurst.lastBentoFactor);
            final float share = Math.max(0f, Math.min(1.5f, PreferenceKeys.hybridValue("bento_fusion", 1f)));
            bentoHeadroom = (float) (Math.log(k * exposure) / Math.log(2)) * share;
            if (bentoHeadroom > darkEv) { darkEv = bentoHeadroom; liftOnly = false; }
        }
        long started = System.currentTimeMillis();
        // Sabre 2x grid (outputScale 2): the fusion runs at 1/(2s) of the working grid, i.e. half of the SENSOR grid as at
        // 1x (same pyramid depth, same finest band, same cost). The 4:1 reduction needs a real low-pass (gaussdown, sigma
        // 0.5 x factor, radius 4 fits its MAX_TAPS 9); at s = 1 the bicubic 2:1 path is unchanged.
        final int s = Math.max(1, Math.round(pipeline.mParameters.outputScale));
        Point half = new Point(Math.max(1, WorkingTexture.mSize.x / (2 * s)), Math.max(1, WorkingTexture.mSize.y / (2 * s)));
        GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        GLTexture small = s > 1 ? glUtils.gaussianDownsample(WorkingTexture, half) : glUtils.interpolate(WorkingTexture, half);
        GLTexture exposures = new GLTexture(half, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        glProg.useAssetProgram("nicefusion/expose", false);
        glProg.setTexture("InputBuffer", small);
        glProg.setVar("exposure", exposure);
        glProg.setVar("darkEv", darkEv);
        glProg.setVar("brightEv", brightEv);
        glProg.setVar("sigma", sigma);
        glProg.drawBlocks(exposures);
        int levels = Math.max(2, (int) (Math.log(Math.min(half.x, half.y)) / Math.log(2)) - 3);
        GLUtils.Pyramid pyramid = glUtils.createPyramid(levels, 2, exposures);
        int top = pyramid.gauss.length - 1;
        GLTexture wip = new GLTexture(pyramid.sizes[top], rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        glProg.useAssetProgram("nicefusion/blend", false);
        glProg.setTexture("gaussLevel", pyramid.gauss[top]);
        glProg.setTexture("lapLevel", pyramid.gauss[top]);
        glProg.setTexture("upsampled", pyramid.gauss[top]);
        glProg.setVar("useUpsampled", 0);
        glProg.setVar("invSize", 1f / pyramid.sizes[top].x, 1f / pyramid.sizes[top].y);
        glProg.setVar("detail", detail);
        glProg.drawBlocks(wip);
        for (int i = top - 1; i >= 0; i--) {
            GLTexture next = new GLTexture(pyramid.sizes[i], rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("nicefusion/blend", false);
            glProg.setTexture("gaussLevel", pyramid.gauss[i]);
            glProg.setTexture("lapLevel", pyramid.laplace[i]);
            glProg.setTexture("upsampled", wip);
            glProg.setVar("useUpsampled", 1);
            glProg.setVar("invSize", 1f / pyramid.sizes[i].x, 1f / pyramid.sizes[i].y);
            glProg.setVar("detail", detail);
            glProg.drawBlocks(next);
            wip.close();
            wip = next;
        }
        GLTexture map = new GLTexture(half, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        glProg.useAssetProgram("nicefusion/map", false);
        glProg.setTexture("InputBuffer", small);
        glProg.setTexture("Fused", wip);
        glProg.setVar("exposure", exposure);
        glProg.setVar("strength", Math.min(1f, strength));
        glProg.setVar("invSize", 1f / half.x, 1f / half.y);
        glProg.setVar("liftOnly", liftOnly ? 1f : 0f);
        glProg.drawBlocks(map);
        wip.close();
        pyramid.releasePyramid();
        small.close();
        if (pipeline.FusionMap != null) pipeline.FusionMap.close();
        pipeline.FusionMap = map;
        glProg.closed = true;
        Log.i("NICE_PIPELINE", "exposureFusion strength=" + strength + " darkEv=" + darkEv + " brightEv=" + brightEv
                + " detail=" + detail + " sigma=" + sigma + " liftOnly=" + liftOnly + " exposure=" + exposure + " levels=" + levels
                + (bento ? " bentoHeadroomEv=" + bentoHeadroom + " k=" + LmcHybridBurst.lastBentoFactor : "")
                + " ms=" + (System.currentTimeMillis() - started));
    }
}
