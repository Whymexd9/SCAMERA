package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/** Autonomous noise-aware RGB substitute for the unrecovered NICE neural stage. */
public final class VivoHdrDenoise extends Node {
    public VivoHdrDenoise() { super("", "VivoHdrDenoise"); }
    @Override public void Compile() {}
    @Override public void Run() {
        WorkingTexture=previousNode.WorkingTexture;
        float luma=PreferenceKeys.vivoHdrValue("luma",0.6f);
        float chroma=PreferenceKeys.vivoHdrValue("chroma",1f);
        if(luma==0f && chroma==0f) { glProg.closed=true;return; }
        float slope=0f,offset=0f;
        if(basePipeline.mParameters.noiseModeler!=null) {
            for(android.util.Pair<Double,Double> n:basePipeline.mParameters.noiseModeler.computeModel) {
                slope+=n.first.floatValue()/3f;offset+=n.second.floatValue()/3f;
            }
        }
        slope=Float.isFinite(slope)?Math.max(0,slope):0;
        offset=Float.isFinite(offset)?Math.max(0,offset):0;
        for(int step=1;step<=2;step++) {
            GLTexture source=WorkingTexture;
            WorkingTexture=basePipeline.getMain();
            glProg.useAssetProgram("vivohdr/denoise");
            glProg.setTexture("InputBuffer",source);
            glProg.setVar("noiseModel",slope,offset);
            glProg.setVar("lumaAmount",step==1?luma:0f);
            glProg.setVar("chromaAmount",chroma);
            glProg.setVar("radiusStep",step);
            glProg.drawBlocks(WorkingTexture);
        }
        glProg.closed=true;
    }
}
