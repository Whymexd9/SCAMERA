package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

/** Autonomous noise-aware RGB substitute for the unrecovered NICE neural stage. */
public final class VivoHdrDenoise extends Node {
    public VivoHdrDenoise() { super("", "VivoHdrDenoise"); }
    @Override public void Compile() {}
    @Override public void Run() {
        WorkingTexture=previousNode.WorkingTexture;
        float luma=PreferenceKeys.vivoHdrValue("luma",0.6f);
        float chroma=PreferenceKeys.vivoHdrValue("chroma",1f);
        if(luma==0f && chroma==0f) { glProg.closed=true;return; }
        float[] slope=new float[3],offset=new float[3];
        if(basePipeline.mParameters.noiseModeler!=null) {
            for(int c=0;c<3;c++) {
                android.util.Pair<Double,Double> n=basePipeline.mParameters.noiseModeler.computeModel[c];
                slope[c]=Float.isFinite(n.first.floatValue())?Math.max(0,n.first.floatValue()):0;
                offset[c]=Float.isFinite(n.second.floatValue())?Math.max(0,n.second.floatValue()):0;
            }
        }
        PostPipeline pipeline=(PostPipeline)basePipeline;
        Log.i("VIVO_HDR","RGB denoise: luma="+luma+" chroma="+chroma
                +"; per-channel RAW variance transformed by WB and normalized lens shading");
        for(int step=1;step<=2;step++) {
            GLTexture source=WorkingTexture;
            WorkingTexture=basePipeline.getMain();
            glProg.useAssetProgram("vivohdr/denoise");
            glProg.setTexture("InputBuffer",source);
            glProg.setTexture("GainMap",pipeline.GainMap);
            glProg.setVar("whitePoint",basePipeline.mParameters.whitePoint);
            glProg.setVar("noiseSlope",slope);
            glProg.setVar("noiseOffset",offset);
            glProg.setVar("lumaAmount",step==1?luma:0f);
            glProg.setVar("chromaAmount",chroma);
            glProg.setVar("radiusStep",step);
            glProg.drawBlocks(WorkingTexture);
        }
        glProg.closed=true;
    }
}
