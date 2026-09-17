package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;

/** Independent, non-neural Tetra 4x4 reconstruction inspired by the stock pipeline. */
final class TetraDetailRemosaic {
    private TetraDetailRemosaic() {}

    static GLTexture run(GLProg prog, GLTexture raw, Point size, int[] phase, int[] quad,
                         float black, float white, float[] gains, TetraResponseProfile.GainMap response) {
        Point cells = new Point((size.x + phase[0] + 7) / 8, (size.y + phase[1] + 7) / 8);
        GLTexture map=null, prepared=null, scratch=null, chroma=null, energy=null, correlation=null, guide=null, out=null;
        boolean success = false;
        try {
            ByteBuffer data=ByteBuffer.allocateDirect(response.values.length*4).order(ByteOrder.nativeOrder());
            data.asFloatBuffer().put(response.values);
            map=new GLTexture(new Point(TetraResponseProfile.GainMap.NX*4,TetraResponseProfile.GainMap.NY*4),
                    new GLFormat(GLFormat.DataType.FLOAT_32,4),data,GL_NEAREST,GL_CLAMP_TO_EDGE);
            prepared=new GLTexture(size,new GLFormat(GLFormat.DataType.FLOAT_16),null,GL_NEAREST,GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("remosaic/tetra/prepare");
            common(prog,size,phase,quad,black,white,gains);
            prog.setTexture("RawBuffer",raw);prog.setTexture("GainMap",map);
            prog.drawBlocks(prepared);prog.closed=true;
            map.close();map=null;

            scratch = new GLTexture(cells, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            chroma = new GLTexture(cells, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            energy = new GLTexture(cells, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            correlation = new GLTexture(cells, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            coarseField(prog,"coarse",prepared,scratch,chroma,size,phase,quad,black,white,gains);
            coarseField(prog,"energy",prepared,scratch,energy,size,phase,quad,black,white,gains);
            coarseField(prog,"correlation",prepared,scratch,correlation,size,phase,quad,black,white,gains);
            scratch.close();scratch=null;

            guide = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16, 2), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("remosaic/tetra/guide");
            common(prog,size,phase,quad,black,white,gains);
            prog.setTexture("RawBuffer",prepared);
            prog.setTexture("CoarseBuffer",chroma);prog.setTexture("EnergyBuffer",energy);
            prog.setTexture("CorrelationBuffer",correlation);
            prog.drawBlocks(guide); prog.closed = true;
            // Release preparation before allocating output: only one full-size
            // R16F/R16UI scratch plus the RG16F guide is live at a time.
            prepared.close();prepared=null;chroma.close();chroma=null;
            energy.close();energy=null;correlation.close();correlation=null;

            out = new GLTexture(size, new GLFormat(GLFormat.DataType.UNSIGNED_16), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("remosaic/tetra/reconstruct");
            common(prog,size,phase,quad,black,white,gains);
            prog.setTexture("GuideBuffer",guide);
            prog.drawBlocks(out); prog.closed = true;
            success = true;
            return out;
        } finally {
            if(map!=null)map.close();if(prepared!=null)prepared.close();if(scratch!=null)scratch.close();
            if(chroma!=null)chroma.close();if(energy!=null)energy.close();if(correlation!=null)correlation.close();
            if(guide!=null)guide.close();if(!success && out!=null)out.close();
        }
    }

    private static void coarseField(GLProg prog,String shader,GLTexture prepared,GLTexture scratch,GLTexture output,
                                    Point size,int[] phase,int[] quad,float black,float white,float[] gains) {
        prog.useAssetProgram("remosaic/tetra/"+shader);
        common(prog,size,phase,quad,black,white,gains);prog.setTexture("RawBuffer",prepared);
        prog.drawBlocks(scratch);prog.closed=true;
        prog.useAssetProgram("remosaic/tetra/chroma");
        prog.setVar("size",size.x,size.y);prog.setVar("phase",phase[0],phase[1]);
        prog.setTexture("InputBuffer",scratch);prog.drawBlocks(output);prog.closed=true;
    }

    private static void common(GLProg p, Point size, int[] phase, int[] quad,
                               float black, float white, float[] gains) {
        p.setVar("size",size.x,size.y);
        p.setVar("phase",phase[0],phase[1]);
        p.setVar("quadColors",quad[0],quad[1],quad[2],quad[3]);
        p.setVar("blackLevel",black);p.setVar("whiteLevel",white);
        p.setVar("gainB",gains[0]);p.setVar("gainR",gains[1]);
    }
}
