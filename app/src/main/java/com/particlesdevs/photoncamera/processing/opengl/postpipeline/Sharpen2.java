package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.hardware.camera2.CaptureResult;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;

public class Sharpen2 extends Node {
    public Sharpen2() {
        super("", "Sharpening");
    }

    @Override
    public void Compile() {
    }
    
    @Tunable(
            title = "Sharp Size", description = "Size parameter for sharpening",
            category = "Sharpening", min = 0.0f, max = 2.0f, defaultValue = 0.8f, step = 0.01f
    )
    float sharpSize;
    
    @Tunable(
            title = "Sharp Min", description = "Minimum sharpening threshold",
            category = "Sharpening", min = 0.0f, max = 2.0f, defaultValue = 0.25f, step = 0.01f
    )
    float sharpMin;
    
    @Tunable(
            title = "Sharp Max", description = "Maximum sharpening threshold",
            category = "Sharpening", min = 0.0f, max = 2.0f, defaultValue = 1.0f, step = 0.01f
    )
    float sharpMax;
    
    @Tunable(
            title = "Denoise Activity", description = "Denoise intensity parameter",
            category = "Sharpening", min = 0.0f, max = 1.0f, defaultValue = 0.0f, step = 0.01f)
    float denoiseActivity;
    
    @Override
    public void Run() {
        glProg.setDefine("INTENSE",denoiseActivity);
        glProg.setDefine("INSIZE",basePipeline.mParameters.rawSize);
        glProg.setDefine("SHARPSIZE",sharpSize);
        glProg.setDefine("SHARPMIN",sharpMin);
        glProg.setDefine("SHARPMAX",sharpMax);
        glProg.setDefine("NOISES",basePipeline.noiseS);
        glProg.setDefine("NOISEO",basePipeline.noiseO);
        glProg.useAssetProgram("sharpening/lsharpening3");
        glProg.setVar("size", PreferenceKeys.getSharpRadius());
        glProg.setVar("strength", PreferenceKeys.getSharpLensStrength());
        // Cast to float: the getter returns int, which picks the int... overload
        // and emits glUniform1i against a float uniform. That is
        // GL_INVALID_OPERATION - the value never reaches the shader and the
        // uniform keeps whatever was there, which is what posterised the
        // sharpened output.
        glProg.setVar("lensIterations", (float) PreferenceKeys.getSharpLensIterations());
        glProg.setVar("gaussianRadius", PreferenceKeys.getSharpGaussianRadius());
        glProg.setVar("gaussianAmount", PreferenceKeys.getSharpGaussianAmount());
        glProg.setVar("threshold", (float) PreferenceKeys.getSharpThreshold() / 255.0f);
        glProg.setVar("smartThreshold", (float) PreferenceKeys.getSharpSmartThreshold() / 255.0f);
        glProg.setVar("edgeStrength", PreferenceKeys.getSharpEdge());
        glProg.setVar("bilateralRadius", PreferenceKeys.getSharpBilateralRadius());
        glProg.setVar("bilateralStrength", PreferenceKeys.getSharpBilateral());
        glProg.setVar("colorTolerance", Math.max(PreferenceKeys.getSharpTolerance() / 255.0f, 0.001f));
        glProg.setVar("localContrast", PreferenceKeys.getSharpLocalContrast());
        glProg.setVar("guidedRadius", PreferenceKeys.getSharpGuidedRadius());
        glProg.setVar("guidedEpsilon", PreferenceKeys.getSharpEpsilon());
        glProg.setVar("textureRestore", PreferenceKeys.getSharpTexture());
        glProg.setVar("lumaGrain", PreferenceKeys.getSharpGrain());
        glProg.setVar("rlAmount", PreferenceKeys.getSharpRlAmount());
        glProg.setVar("rlRadius", PreferenceKeys.getSharpRlRadius());
        // Cast to float: the getter returns int, which picks the int... overload
        // and emits glUniform1i against a float uniform. That is
        // GL_INVALID_OPERATION - the value never reaches the shader and the
        // uniform keeps whatever was there, which is what posterised the
        // sharpened output.
        glProg.setVar("rlIterations", (float) PreferenceKeys.getSharpRlIterations());
        glProg.setVar("damping", PreferenceKeys.getSharpDamping());
        glProg.setVar("shadowProtection", (float) PreferenceKeys.getSharpShadowProtection() / 100.0f);
        glProg.setVar("highlightProtection", (float) PreferenceKeys.getSharpHighlightProtection() / 100.0f);
        glProg.setVar("haloControl", (float) PreferenceKeys.getSharpHaloControl() / 100.0f);
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        glProg.setTexture("BlurBuffer",previousNode.WorkingTexture);
        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
    }
}
