package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.*;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.BufferUtils;
import static android.opengl.GLES20.*;

/** Import reconstructed sensor RGB; apply WB and normalized lens shading once. */
public final class VivoNiceRgb extends Node {
    public VivoNiceRgb(){super("","VivoNiceRgb");}
    @Override public void Compile(){}
    /**
     * Per-channel level where the NICE output saturates (the plateau of blown
     * windows/sky). Channels clip at different scene levels, so after white
     * balance a clipped highlight turned pink; the shader neutralises it near
     * this level. No plateau (nothing clipped): effectively disabled.
     */
    private static float[] clipLevels(java.nio.ByteBuffer rgb){
        java.nio.FloatBuffer f=rgb.duplicate().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        int n=f.limit()/3;float[] max=new float[3];
        for(int i=0;i<n;i+=17)for(int c=0;c<3;c++)max[c]=Math.max(max[c],f.get(i*3+c));
        int near=0,total=0;
        for(int i=0;i<n;i+=17){total++;
            for(int c=0;c<3;c++)if(max[c]>0&&f.get(i*3+c)>=0.97f*max[c]){near++;break;}}
        boolean plateau=total>0&&near>=total/2000;
        com.particlesdevs.photoncamera.util.Log.i("NICE_PIPELINE","highlight clip levels="+max[0]+","+max[1]+","+max[2]
                +" plateau="+plateau+" ("+near+"/"+total+")");
        if(!plateau)return new float[]{1e30f,1e30f,1e30f};
        return max;
    }
    @Override public void Run(){
        PostPipeline p=(PostPipeline)basePipeline;
        GLTexture input=new GLTexture(p.mParameters.rawSize,new GLFormat(GLFormat.DataType.FLOAT_32,3),
                p.mParameters.vivoNiceRgb,GL_NEAREST,GL_CLAMP_TO_EDGE);
        try {
            p.GainMap=new GLTexture(p.mParameters.mapSize,new GLFormat(GLFormat.DataType.FLOAT_16,4),
                    BufferUtils.getFrom(p.mParameters.gainMap),GL_LINEAR,GL_CLAMP_TO_EDGE);
            p.main1=new GLTexture(p.mParameters.rawSize,new GLFormat(GLFormat.DataType.FLOAT_16,GLDrawParams.WorkDim),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
            p.main2=new GLTexture(p.mParameters.rawSize,new GLFormat(GLFormat.DataType.FLOAT_16,GLDrawParams.WorkDim),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
            p.main3=new GLTexture(p.mParameters.rawSize,new GLFormat(GLFormat.DataType.FLOAT_16,GLDrawParams.WorkDim),null,GL_LINEAR,GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("vivohdr/nicergb");glProg.setTexture("InputBuffer",input);glProg.setTexture("GainMap",p.GainMap);
            glProg.setVar("whitePoint",p.mParameters.whitePoint);
            float[] clip=clipLevels(p.mParameters.vivoNiceRgb);
            glProg.setVar("clipLevel",clip[0],clip[1],clip[2]);
            int ox=0,oy=0;
            if(com.particlesdevs.photoncamera.app.PhotonCamera.getSettings().aspect169){
                int w=p.mParameters.rawSize.x,h=p.mParameters.rawSize.y;
                if(w>h)oy=2*((h-w*9/16)/4);else ox=2*((w-h*9/16)/4);
            }
            glProg.setVar("cropOffset",ox,oy);
            glProg.setVar("inverseSize",1f/p.mParameters.rawSize.x,1f/p.mParameters.rawSize.y);
            // Keep the ping-pong cursor in sync for every following postprocessing pass.
            WorkingTexture=p.getMain();glProg.drawBlocks(WorkingTexture);glProg.closed=true;p.regenerationSense=1;
        }finally{input.close();}
    }
}
