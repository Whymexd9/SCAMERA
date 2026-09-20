package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.*;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.BufferUtils;
import static android.opengl.GLES20.*;

/** Import reconstructed sensor RGB; apply WB and normalized lens shading once. */
public final class VivoNiceRgb extends Node {
    public VivoNiceRgb(){super("","VivoNiceRgb");}
    @Override public void Compile(){}
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
