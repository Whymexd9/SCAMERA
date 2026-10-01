package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * SCAM HDR finishing (replaces the generic RawTherapee sharpening in the soft-tone render; pref_vivo_nice_sharp_mode 0
 * brings it back): chroma anti-aliasing and a halo-free luminance unsharp mask (nicesharp/sharp.glsl), then an
 * edge-directed anti-aliasing and the 8-bit dither (nicesharp/sharp2.glsl). A Richardson-Lucy deconvolution of a merge
 * that already resolves sub-pixel detail turned every sampling-grid pattern into stair steps and rings; this stage
 * amplifies only what the edge profile really carries and smooths the edges along their direction afterwards.
 */
public final class NiceSharpen extends Node {
    public NiceSharpen() { super("", "NiceSharpen"); }
    @Override public void Compile() {}
    @Override public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        // The unsharp mask gain falls with the frame's noise (a dim or high-ISO frame would only gain grain).
        float sigma = pipeline.niceNoiseSigma, ref = Math.max(PreferenceKeys.niceInternalValue("sharp_noise_ref", 0.0012f), 1.0e-5f);
        float amount = PreferenceKeys.niceInternalValue("sharp_amount", 1.2f) / (1f + (sigma / ref) * (sigma / ref));
        float aa = PreferenceKeys.niceInternalValue("sharp_aa", 1.0f);
        long started = System.currentTimeMillis();
        GLTexture sharpened = new GLTexture(WorkingTexture.mSize, WorkingTexture.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        try {
            glProg.useAssetProgram("nicesharp/sharp", false);
            glProg.setTexture("InputBuffer", WorkingTexture);
            glProg.setVar("amount", Math.max(0f, amount));
            glProg.setVar("radius", Math.max(0.4f, PreferenceKeys.niceInternalValue("sharp_radius", 0.8f)));
            glProg.setVar("overshoot", PreferenceKeys.niceInternalValue("sharp_overshoot", 0.015f));
            glProg.setVar("chromaAA", PreferenceKeys.niceInternalValue("sharp_chroma", 0.6f), PreferenceKeys.niceInternalValue("sharp_chroma_tol", 0.04f));
            glProg.drawBlocks(sharpened);
            glProg.useAssetProgram("nicesharp/sharp2", false);
            glProg.setTexture("InputBuffer", sharpened);
            glProg.setVar("aaStrength", Math.max(0f, Math.min(1f, aa)));
            glProg.setVar("aaGate", PreferenceKeys.niceInternalValue("sharp_coh0", 0.4f), PreferenceKeys.niceInternalValue("sharp_coh1", 0.7f),
                    PreferenceKeys.niceInternalValue("sharp_mag0", 0.006f), PreferenceKeys.niceInternalValue("sharp_mag1", 0.025f));
            glProg.setVar("alongSigma", Math.max(0.4f, PreferenceKeys.niceInternalValue("sharp_along", 1.4f)));
            glProg.setVar("ditherAmp", PreferenceKeys.niceInternalValue("sharp_dither", 0.6f) / 255f);
            WorkingTexture = pipeline.getMain();
            glProg.drawBlocks(WorkingTexture);
        } finally {
            sharpened.close();
        }
        glProg.closed = true;
        Log.i("NICE_PIPELINE", "sharpen aa=" + aa + " amount=" + amount + " ms=" + (System.currentTimeMillis() - started));
    }
}
