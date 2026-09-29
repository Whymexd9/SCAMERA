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
        float exposure = Math.max(1f, pipeline.linearDisplayGain)
                * AgxTone.exposureMultiplier();
        long started = System.currentTimeMillis();
        Point half = new Point(Math.max(1, WorkingTexture.mSize.x / 2), Math.max(1, WorkingTexture.mSize.y / 2));
        GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        GLTexture small = glUtils.interpolate(WorkingTexture, half);
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
                + " ms=" + (System.currentTimeMillis() - started));
    }
}
