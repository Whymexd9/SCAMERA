package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/** Reuses SCAMERA's colour/LSC and log shoulder without a second tone curve. */
public final class VivoHdrTone extends HeadroomRender {
    @Override public void Run() {
        float strength=PreferenceKeys.vivoHdrValue("tone",1f);
        toneAmount=Math.min(1f,strength);
        headroomScale=0.9f*Math.max(1f,strength);
        sceneWhiteMax=Math.max(32f,16f/Math.max(1e-6f,basePipeline.mParameters.vivoHdrRawScale));
        outputExposureScale=1f;
        localContrast=PreferenceKeys.vivoHdrValue("local",0.35f);
        shadowLift=PreferenceKeys.vivoHdrValue("shadows",0.25f);
        manualTone=true;
        manualExposure=PreferenceKeys.vivoHdrValue("exposure",0f);
        manualContrast=PreferenceKeys.vivoHdrValue("contrast",1f);
        manualGamma=PreferenceKeys.vivoHdrValue("gamma",1f);
        manualSaturation=PreferenceKeys.vivoHdrValue("saturation",1f);
        manualBlack=PreferenceKeys.vivoHdrValue("black",0f);
        manualWhite=PreferenceKeys.vivoHdrValue("white",1f);
        super.Run();
    }
}
