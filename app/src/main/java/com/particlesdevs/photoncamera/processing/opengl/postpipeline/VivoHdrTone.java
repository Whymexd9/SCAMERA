package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/** Reuses SCAMERA's colour/LSC and log shoulder without a second tone curve. */
public final class VivoHdrTone extends HeadroomRender {
    @Override public void Run() {
        // Legacy headroom shoulder (AgX off) runs on fixed values; with AgX the tone comes
        // from the AgX group (curve, local highlights, highlight desaturation).
        final boolean soft=PreferenceKeys.isNiceSoftTone();
        final boolean agxOn=AgxTone.enabled()&&!soft;
        float strength=2f;
        toneAmount=Math.min(1f,strength);
        headroomScale=0.9f*Math.max(1f,strength);
        // Adaptive white: at least the scene's own highlight level (percentile of
        // the HDR data), so reflections/lit walls roll off instead of clipping.
        float highlight=((PostPipeline)basePipeline).linearHighlight;
        if(highlight>0f)
            headroomScale=Math.max(headroomScale,1.02f*highlight*Math.max(1f,strength));
        sceneWhiteMax=Math.max(32f,16f/Math.max(1e-6f,basePipeline.mParameters.vivoHdrRawScale));
        outputExposureScale=1f;
        localContrast=soft?PreferenceKeys.niceInternalValue("tone_local",0.12f):PreferenceKeys.vivoHdrValue("local",0.35f);
        shadowLift=soft?0f:PreferenceKeys.vivoHdrValue("shadows",0.25f);
        highlightNeutralStart=0.45f;
        displayNeutralStart=0.55f;
        manualTone=true;
        agx=agxOn;
        gcam=soft;
        niceTone=true;
        manualExposure=0f;   // exposure, contrast and saturation are the AgX group's
        manualContrast=1f;
        // The soft tone has its own curve; the legacy gamma / black / white sliders belong to the former stack.
        manualGamma=soft?1f:PreferenceKeys.vivoHdrValue("gamma",1f);
        manualSaturation=1f;
        manualBlack=soft?0f:PreferenceKeys.vivoHdrValue("black",0f);
        manualWhite=soft?1f:PreferenceKeys.vivoHdrValue("white",1f);
        super.Run();
    }
}
