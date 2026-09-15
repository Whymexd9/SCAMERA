package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_NEAREST;

import com.particlesdevs.photoncamera.processing.opengl.GLDrawParams;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

/**
 * Sharpening using RawTherapee's algorithms: unsharp mask, Richardson-Lucy
 * deconvolution and microcontrast, each with RawTherapee's own parameters and
 * defaults. The shaders carry the port notes and the GPL attribution.
 *
 * Replaces the previous combined node, whose screen mixed working controls with
 * ones that reached no calculation: the RL amount, radius and iterations were
 * uniforms the shader never read, "Deblur iterations" was a second strength
 * multiplier rather than an iteration count, and the "guided" radius and epsilon
 * drove neither a guided filter nor anything else consistent - epsilon alone
 * served as an edge-mask width, a Wiener denominator and a local-contrast
 * divisor at once.
 *
 * RawTherapee scales L to 0..32768. Level-valued parameters (thresholds, edge
 * tolerance) are converted here so the shaders can work in 0..1.
 */
public class RTSharpening extends Node {
    /** RawTherapee's Lab L scale; its UI values are expressed against this. */
    private static final float RT_L_SCALE = 32768.0f;

    public RTSharpening() {
        super("", "Sharpening");
    }

    @Override
    public void Compile() {
    }

    @Override
    public void Run() {
        // The three algorithms stack rather than exclude each other, in
        // RawTherapee's own pipeline order: deconvolution recovers detail the
        // lens and the sensor's AA filter spread, unsharp mask raises edge
        // contrast on the result, microcontrast works the fine structure last.
        // Each stage that runs takes the previous stage's output as its input,
        // so any subset can be enabled.
        GLTexture in = previousNode.WorkingTexture;
        boolean any = false;

        if (PreferenceKeys.isSharpDeconvEnabled()) {
            in = runDeconvolution(in);
            any = true;
        }
        if (PreferenceKeys.isSharpUsmEnabled()) {
            in = runUnsharpMask(in);
            any = true;
        }
        if (PreferenceKeys.isSharpMicroEnabled()) {
            in = runMicrocontrast(in);
            any = true;
        }

        WorkingTexture = any ? in : previousNode.WorkingTexture;
    }

    private GLTexture runUnsharpMask(GLTexture input) {
        float amount = PreferenceKeys.getSharpAmount() / 100.0f;
        Log.d(Name, "RT unsharp mask: radius=" + PreferenceKeys.getSharpRadius()
                + " amount=" + amount
                + " contrast=" + PreferenceKeys.getSharpContrast()
                + " edgesOnly=" + PreferenceKeys.isSharpEdgesOnly()
                + " haloControl=" + PreferenceKeys.isSharpHaloControl());
        glProg.setDefine("INSIZE", basePipeline.mParameters.rawSize);
        glProg.useAssetProgram("sharpening/rtusm");
        glProg.setVar("radius", PreferenceKeys.getSharpRadius());
        glProg.setVar("amount", amount);
        glProg.setVar("contrastThreshold", PreferenceKeys.getSharpContrast() / 100.0f);
        glProg.setVar("thrBottomLeft", PreferenceKeys.getSharpThresholdBottomLeft() / RT_L_SCALE);
        glProg.setVar("thrTopLeft", PreferenceKeys.getSharpThresholdTopLeft() / RT_L_SCALE);
        glProg.setVar("thrBottomRight", PreferenceKeys.getSharpThresholdBottomRight() / RT_L_SCALE);
        glProg.setVar("thrTopRight", PreferenceKeys.getSharpThresholdTopRight() / RT_L_SCALE);
        glProg.setVar("edgesOnly", PreferenceKeys.isSharpEdgesOnly() ? 1.0f : 0.0f);
        glProg.setVar("edgesRadius", PreferenceKeys.getSharpEdgesRadius());
        glProg.setVar("edgesTolerance", PreferenceKeys.getSharpEdgesTolerance() / RT_L_SCALE);
        glProg.setVar("haloControl", PreferenceKeys.isSharpHaloControl() ? 1.0f : 0.0f);
        glProg.setVar("haloAmount", PreferenceKeys.getSharpHaloAmount() / 100.0f);
        glProg.setTexture("InputBuffer", input);
        GLTexture out = basePipeline.getMain();
        glProg.drawBlocks(out);
        glProg.closed = true;
        return out;
    }

    private GLTexture runMicrocontrast(GLTexture input) {
        boolean matrix3x3 = PreferenceKeys.isSharpMicroMatrix3x3();
        // RT: amount / 1500, times 2.7 for the 3x3 matrix so both kernels land at
        // a comparable strength for the same slider position.
        float amount = (matrix3x3 ? 2.7f : 1.0f) * PreferenceKeys.getSharpMicroAmount() / 1500.0f;
        Log.d(Name, "RT microcontrast: amount=" + amount
                + " uniformity=" + PreferenceKeys.getSharpMicroUniformity()
                + " contrast=" + PreferenceKeys.getSharpMicroContrast()
                + " matrix3x3=" + matrix3x3);
        glProg.setDefine("INSIZE", basePipeline.mParameters.rawSize);
        glProg.useAssetProgram("sharpening/rtmicrocontrast");
        glProg.setVar("amount", amount);
        glProg.setVar("uniformity", (float) PreferenceKeys.getSharpMicroUniformity());
        glProg.setVar("contrastThreshold", PreferenceKeys.getSharpMicroContrast() / 100.0f);
        glProg.setVar("matrix3x3", matrix3x3 ? 1.0f : 0.0f);
        glProg.setTexture("InputBuffer", input);
        GLTexture out = basePipeline.getMain();
        glProg.drawBlocks(out);
        glProg.closed = true;
        return out;
    }

    private GLTexture runDeconvolution(GLTexture input) {
        int iterations = Math.max(1, PreferenceKeys.getSharpDeconvIterations());
        float radius = PreferenceKeys.getSharpDeconvRadius();
        // RT: damping = deconvdamping / 5, and zero disables the damping branch.
        float damping = PreferenceKeys.getSharpDeconvDamping() / 5.0f;
        float amount = PreferenceKeys.getSharpDeconvAmount() / 100.0f;
        Log.d(Name, "RT RL deconvolution: radius=" + radius + " amount=" + amount
                + " iterations=" + iterations + " damping=" + damping);

        // Two single-channel-in-a-vec3 buffers for the estimate, one for the
        // per-iteration ratio. RT keeps three float planes for the same reason:
        // each half of an iteration needs a neighbourhood of the other's output,
        // so neither can be done in place.
        GLFormat fmt = new GLFormat(GLFormat.DataType.FLOAT_16, GLDrawParams.WorkDim);
        GLTexture estimate = new GLTexture(basePipeline.mParameters.rawSize, fmt, null,
                GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture estimateNext = new GLTexture(basePipeline.mParameters.rawSize, fmt, null,
                GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture ratio = new GLTexture(basePipeline.mParameters.rawSize, fmt, null,
                GL_NEAREST, GL_CLAMP_TO_EDGE);
        GLTexture out;
        try {
            glProg.useAssetProgram("sharpening/rtluma");
            glProg.setTexture("InputBuffer", input);
            glProg.drawBlocks(estimate);
            glProg.closed = true;

            for (int i = 0; i < iterations; i++) {
                glProg.setDefine("INSIZE", basePipeline.mParameters.rawSize);
                glProg.useAssetProgram("sharpening/rtdeconv1");
                glProg.setVar("radius", radius);
                glProg.setVar("damping", damping);
                glProg.setTexture("EstimateBuffer", estimate);
                glProg.setTexture("OriginalBuffer", input);
                glProg.drawBlocks(ratio);
                glProg.closed = true;

                glProg.setDefine("INSIZE", basePipeline.mParameters.rawSize);
                glProg.useAssetProgram("sharpening/rtdeconv2");
                glProg.setVar("radius", radius);
                glProg.setTexture("RatioBuffer", ratio);
                glProg.setTexture("EstimateBuffer", estimate);
                glProg.drawBlocks(estimateNext);
                glProg.closed = true;

                GLTexture swap = estimate;
                estimate = estimateNext;
                estimateNext = swap;
            }

            glProg.setDefine("INSIZE", basePipeline.mParameters.rawSize);
            glProg.useAssetProgram("sharpening/rtdeconvblend");
            glProg.setVar("amount", amount);
            glProg.setVar("contrastThreshold", PreferenceKeys.getSharpContrast() / 100.0f);
            glProg.setTexture("InputBuffer", input);
            glProg.setTexture("EstimateBuffer", estimate);
            out = basePipeline.getMain();
            glProg.drawBlocks(out);
            glProg.closed = true;
        } finally {
            estimate.close();
            estimateNext.close();
            ratio.close();
        }
        return out;
    }
}
