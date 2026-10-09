package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * SCAM HDR finishing (replaces the generic RawTherapee sharpening in the soft-tone render; pref_scamhdr_sharp_mode 0
 * brings it back): chroma anti-aliasing and a halo-free luminance unsharp mask (scamsharp/sharp.glsl), then an
 * edge-directed anti-aliasing and the 8-bit dither (scamsharp/sharp2.glsl). A Richardson-Lucy deconvolution of a merge
 * that already resolves sub-pixel detail turned every sampling-grid pattern into stair steps and rings; this stage
 * amplifies only what the edge profile really carries and smooths the edges along their direction afterwards.
 */
public final class ScamSharpen extends Node {
    public ScamSharpen() { super("", "ScamSharpen"); }
    @Override public void Compile() {}
    @Override public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        // The unsharp mask gain falls with the frame's noise (a dim or high-ISO frame would only gain grain).
        float sigma = pipeline.scamNoiseSigma, ref = Math.max(PreferenceKeys.routeInternalValue("sharp_noise_ref", 0.0012f), 1.0e-5f);
        float amount = PreferenceKeys.routeInternalValue("sharp_amount", 1.2f) / (1f + (sigma / ref) * (sigma / ref));
        float aa = PreferenceKeys.routeInternalValue("sharp_aa", 1.0f);
        // On the Sabre 2x grid (outputScale 2) every fixed window of the two passes is dilated by s so the 5x5/3x3
        // neighbourhoods, the Sobel gate and the along-edge taps span the same SENSOR pixels as at 1x (s = 1 there:
        // the shaders behave exactly as before). The USM sigma 'radius' stays in output pixels (scaled below).
        final int s = Math.max(1, Math.round(basePipeline.mParameters.outputScale));
        long started = System.currentTimeMillis();
        GLTexture sharpened = new GLTexture(WorkingTexture.mSize, WorkingTexture.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        try {
            glProg.useAssetProgram("scamsharp/sharp", false);
            glProg.setTexture("InputBuffer", WorkingTexture);
            glProg.setVar("amount", Math.max(0f, amount));
            glProg.setVar("radius", Math.max(0.4f, PreferenceKeys.routeInternalValue("sharp_radius", 0.8f) * Math.max(1f, basePipeline.mParameters.outputScale)));
            glProg.setVar("overshoot", PreferenceKeys.routeInternalValue("sharp_overshoot", 0.015f));
            glProg.setVar("chromaAA", PreferenceKeys.routeInternalValue("sharp_chroma", 0.6f), PreferenceKeys.routeInternalValue("sharp_chroma_tol", 0.04f));
            glProg.setVar("coring", PreferenceKeys.routeInternalValue("sharp_core0", 0.006f), PreferenceKeys.routeInternalValue("sharp_core1", 0.02f));
            glProg.setVar("pxStepU", 1); // full lattice: dilated taps gave saw-tooth edges on the 2x grid (vivo 27020)
            glProg.drawBlocks(sharpened);
            glProg.useAssetProgram("scamsharp/sharp2", false);
            glProg.setTexture("InputBuffer", sharpened);
            glProg.setVar("aaStrength", Math.max(0f, Math.min(1f, aa)));
            glProg.setVar("aaGate", PreferenceKeys.routeInternalValue("sharp_coh0", 0.4f), PreferenceKeys.routeInternalValue("sharp_coh1", 0.7f),
                    PreferenceKeys.routeInternalValue("sharp_mag0", 0.006f), PreferenceKeys.routeInternalValue("sharp_mag1", 0.025f));
            // alongSigma is in output pixels; the shader's taps are 0.85 * step apart, so the sigma scales with s too.
            glProg.setVar("alongSigma", Math.max(0.4f, PreferenceKeys.routeInternalValue("sharp_along", 1.4f)));
            glProg.setVar("ditherAmp", PreferenceKeys.routeInternalValue("sharp_dither", 0.6f) / 255f);
            glProg.setVar("pxStepU", 1); // full lattice: dilated taps gave saw-tooth edges on the 2x grid (vivo 27020)
            WorkingTexture = pipeline.getMain();
            glProg.drawBlocks(WorkingTexture);
        } finally {
            sharpened.close();
        }
        glProg.closed = true;
        Log.i("SCAM_PIPELINE", "sharpen aa=" + aa + " amount=" + amount + " ms=" + (System.currentTimeMillis() - started));
    }
}
