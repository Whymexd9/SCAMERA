package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;

/** GPU calibration/normalization -> root HTP inference -> GPU Bayer encoding. */
final class VivoNeuralRemosaic {
    static GLTexture run(GLProg prog,GLTexture raw,Point size,int[] phase,int[] quad,float black,float white,
                         float[] gains,TetraResponseProfile.GainMap response) {
        if(phase[0]!=0||phase[1]!=0)throw new IllegalArgumentException("Vivo Neural пока требует фазу Tetra 0,0");
        if((long)size.x*size.y>16000000)throw new IllegalArgumentException("Vivo Neural: максимум 16 МП на кадр");
        GLTexture map=null,prepared=null,neural=null,out=null;boolean ok=false;
        try {
            ByteBuffer values=ByteBuffer.allocateDirect(response.values.length*4).order(ByteOrder.nativeOrder());values.asFloatBuffer().put(response.values);
            map=new GLTexture(new Point(TetraResponseProfile.GainMap.NX*4,TetraResponseProfile.GainMap.NY*4),new GLFormat(GLFormat.DataType.FLOAT_32,4),values,GL_NEAREST,GL_CLAMP_TO_EDGE);
            prepared=new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_32),null,GL_NEAREST,GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("remosaic/tetra/prepare");
            prog.setVar("size",size.x,size.y);prog.setVar("phase",phase[0],phase[1]);
            prog.setVar("quadColors",quad[0],quad[1],quad[2],quad[3]);prog.setVar("blackLevel",black);prog.setVar("whiteLevel",white);
            // Stock packing works in unbalanced sensor space. Applying measured
            // WB here would clip a channel before its normal downstream WB.
            prog.setVar("gainR",1.0f);prog.setVar("gainB",1.0f);prog.setTexture("RawBuffer",raw);prog.setTexture("GainMap",map);
            prog.drawBlocks(prepared);prog.closed=true;
            map.close();map=null;prepared.BufferLoad();
            ByteBuffer input=prepared.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_32)).order(ByteOrder.nativeOrder());input.rewind();
            prepared.close();prepared=null;
            int red=0;for(int i=0;i<4;i++)if(quad[i]==0)red=i;
            ByteBuffer result=VivoNeuralClient.process(PhotonCamera.getAppContext(),input,size.x,size.y,red);
            neural=new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_32),result,GL_NEAREST,GL_CLAMP_TO_EDGE);
            out=new GLTexture(size,new GLFormat(GLFormat.DataType.UNSIGNED_16),null,GL_NEAREST,GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("remosaic/tetra/neuralfinish");prog.setTexture("NeuralBuffer",neural);
            prog.setVar("quadColors",quad[0],quad[1],quad[2],quad[3]);prog.setVar("blackLevel",black);prog.setVar("whiteLevel",white);
            prog.setVar("gainR",1.0f);prog.setVar("gainB",1.0f);prog.drawBlocks(out);prog.closed=true;ok=true;return out;
        } catch(Exception e){throw new IllegalStateException("Vivo Neural: "+e.getMessage(),e);}
        finally{if(map!=null)map.close();if(prepared!=null)prepared.close();if(neural!=null)neural.close();if(!ok&&out!=null)out.close();}
    }
}
