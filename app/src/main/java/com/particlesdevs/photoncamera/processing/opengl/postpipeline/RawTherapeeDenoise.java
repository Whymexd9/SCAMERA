package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.opengl.GLES30;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.RawTherapeeSettings;
import com.particlesdevs.photoncamera.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** RT 5.12 native NR on linear ProPhoto, between ABLC and tone/color rendering. */
public final class RawTherapeeDenoise extends Node {
    private static final class Library { static { System.loadLibrary("rtDenoise"); } static void load() {} }
    private static native String nativeRun(ByteBuffer image,int width,int height,float[] parameters,
                                          float[] matrix,float[] lumaCurve,float[] chromaCurve);
    private static native float[] nativeCurve(double[] points);
    public RawTherapeeDenoise() { super("","RawTherapeeDenoise"); }
    @Override public void Compile() {}
    @Override public void Run() {
        WorkingTexture=previousNode.WorkingTexture;
        glProg.closed=true;
        if(!RawTherapeeSettings.original())return;
        long started=System.nanoTime();
        try {
            float[] p=RawTherapeeSettings.parameters();
            double[] lp=RawTherapeeSettings.curve(RawTherapeeSettings.text("rt512_lcurve","0"));
            double[] cp=RawTherapeeSettings.curve(RawTherapeeSettings.text("rt512_ccurve","0"));
            if(p[0]==0&&p[1]==0&&p[8]==0&&p[11]==0&&lp==null&&cp==null) {
                Log.i(Name,"RT 5.12 bypass: all filters disabled");return;
            }
            Library.load();
            float[] lc=lp==null?null:nativeCurve(lp), cc=cp==null?null:nativeCurve(cp);
            float[] matrix=basePipeline.mParameters.sensorToProPhoto.clone();
            float[] neutral=basePipeline.mParameters.whitePoint;
            // Same row-major camera-to-ProPhoto transform as Initial, including neutral point.
            for(int row=0;row<3;row++)for(int col=0;col<3;col++)matrix[row*3+col]*=neutral[col];
            GLTexture source=previousNode.WorkingTexture;
            source.BufferLoad();
            if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("NR input framebuffer is incomplete");
            ByteBuffer data=source.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_32,4),true).order(ByteOrder.nativeOrder());
            int error=GLES30.glGetError();
            if(error!=GLES30.GL_NO_ERROR)throw new IllegalStateException("NR readback GL error "+error);
            String failure=nativeRun(data,source.mSize.x,source.mSize.y,p,matrix,lc,cc);
            if(failure!=null)throw new IllegalStateException(failure);
            data.rewind();
            GLTexture output=new GLTexture(source.mSize,new GLFormat(GLFormat.DataType.FLOAT_16,4),data);
            error=GLES30.glGetError();
            if(error!=GLES30.GL_NO_ERROR) { output.close();throw new IllegalStateException("NR upload GL error "+error); }
            WorkingTexture=output;
            Log.i(Name,"RT 5.12 applied "+source.mSize+" params="+Arrays.toString(p)
                    +" curves="+(lc!=null)+","+(cc!=null)+" ms="+(System.nanoTime()-started)/1000000);
        } catch(Throwable e) {
            // Original GPU input has never been mutated. Do not silently run a different denoiser.
            WorkingTexture=previousNode.WorkingTexture;
            Log.e(Name,"RT 5.12 failed; image preserved without RT denoise",e);
            PhotonCamera.showToast("RawTherapee: шумоподавление не применено. Подробности в журнале.");
        }
    }
}
