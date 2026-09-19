package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.scripts.GLHistogram;
import com.particlesdevs.photoncamera.processing.parameters.GcamFinishMath;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;

/** Optional linear-camera-RGB finish adaptations before the selected tone curve. */
public final class GcamFinish extends Node {
    public GcamFinish(){super("","GcamFinish");}
    @Override public void Compile(){}
    private float value(String key,float def,float min,float max){
        return PreferenceKeys.gcamValue("pref_gcam_"+key,def,min,max);
    }
    private static float percentile(int[][] hist,double quantile){
        long total=0;for(int c=0;c<3;c++)for(int n:hist[c])total+=Math.max(0,n);
        if(total==0)return 0;
        long count=0,target=Math.max(1,(long)Math.ceil(total*quantile));
        for(int i=0;i<hist[0].length;i++){
            for(int c=0;c<3;c++)count+=Math.max(0,hist[c][i]);
            if(count>=target)return (float)i/(hist[0].length-1);
        }
        return 1;
    }
    @Override public void Run(){
        GLTexture input=previousNode.WorkingTexture;
        WorkingTexture=input;
        if(!PreferenceKeys.isGcamStageEnabled("pref_gcam_finish")){glProg.closed=true;return;}
        float fine=value("fine",1,0,2),medium=value("medium",1,0,2),coarse=value("coarse",1,0,2);
        float slm=value("slm",0,0,1),ev=value("shadow_ev",0,0,2),high=value("highlights",0,0,1);
        float local=value("local",0,0,1),clarity=value("clarity",0,0,1),dehaze=value("dehaze",0,0,1),flare=value("flare",0,0,1);
        if(fine==1 && medium==1 && coarse==1 && slm==0 && high==0 && local==0 && clarity==0 && dehaze==0 && flare==0){glProg.closed=true;return;}
        float p01=0,p10=0,p99=1;
        if(slm>0 || dehaze>0 || flare>0)try(GLHistogram histogram=new GLHistogram(glProg,1024)){
            histogram.Ac=false;
            int[][] data=histogram.Compute(input);
            p01=percentile(data,.01);p10=percentile(data,.10);p99=percentile(data,.99);
        }
        GLTexture[] levels=new GLTexture[3];
        try{
            GLTexture source=input;
            for(int i=0;i<3;i++){
                levels[i]=new GLTexture(Math.max(1,source.mSize.x/2),Math.max(1,source.mSize.y/2),new GLFormat(GLFormat.DataType.FLOAT_16,4),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
                glProg.useAssetProgram("gcamfinish/blur");glProg.setTexture("InputBuffer",source);
                glProg.setVar("outputSize",(float)levels[i].mSize.x,(float)levels[i].mSize.y);
                glProg.drawBlocks(levels[i]);source=levels[i];
            }
            // Match the measured shadow percentile towards .03, capped by the
            // user's requested EV. This target is SCAMERA tuning, not Google AE.
            double gain=Math.min(Math.pow(2,ev),Math.max(1,.03/Math.max(p10,1e-4)));
            double[] split=GcamFinishMath.splitGain(gain);
            glProg.useAssetProgram("gcamfinish/finish");
            glProg.setTexture("InputBuffer",input);
            for(int i=0;i<3;i++)glProg.setTexture("Level"+(i+1),levels[i]);
            glProg.setVar("frequencyGain",fine,medium,coarse);
            float variance=0;
            if(basePipeline.mParameters.noiseModeler!=null)
                for(android.util.Pair<Double,Double> n:basePipeline.mParameters.noiseModeler.computeModel)
                    variance+=(float)(.18*n.first+n.second)/3;
            glProg.setVar("noiseFloor",Math.min(.1f,2*(float)Math.sqrt(Math.max(0,variance))));
            glProg.setVar("shadowMatch",slm);glProg.setVar("logMix",value("log_mix",1,0,1));
            glProg.setVar("splitGain",(float)split[0],(float)split[1]);
            glProg.setVar("highlightAmount",high);glProg.setVar("localAmount",local);
            glProg.setVar("clarityAmount",clarity);glProg.setVar("dehazeAmount",dehaze);
            glProg.setVar("flareLevel",flare*Math.min(.03f,Math.max(0,p01-.003f)));
            glProg.setVar("atmosphere",Math.max(.05f,p99));
            WorkingTexture=basePipeline.getMain();
            glProg.drawBlocks(WorkingTexture);glProg.closed=true;
        }finally{for(GLTexture texture:levels)if(texture!=null)texture.close();}
    }
}
