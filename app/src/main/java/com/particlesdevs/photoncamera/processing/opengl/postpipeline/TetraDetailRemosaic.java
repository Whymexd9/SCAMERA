package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;

/** Independent, non-neural Tetra 4x4 reconstruction inspired by the stock pipeline. */
final class TetraDetailRemosaic {
    private TetraDetailRemosaic() {}

    static GLTexture run(GLProg prog, GLTexture raw, Point size, int[] phase, int[] quad,
                         float black, float white, float[] gains, float[] profile, float[] trust) {
        Point cells = new Point((size.x + phase[0] + 7) / 8, (size.y + phase[1] + 7) / 8);
        GLTexture coarse = null, chroma = null, guide = null, out = null;
        boolean success = false;
        try {
            coarse = new GLTexture(cells, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            chroma = new GLTexture(cells, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            guide = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16, 2), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            out = new GLTexture(size, new GLFormat(GLFormat.DataType.UNSIGNED_16), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("remosaic/tetra/coarse");
            common(prog,size,phase,quad,black,white,gains);
            prog.setVarFloats("blockGain",profile);
            prog.setTexture("RawBuffer",raw);
            prog.drawBlocks(coarse); prog.closed = true;

            prog.useAssetProgram("remosaic/tetra/chroma");
            prog.setVar("size",size.x,size.y);
            prog.setVar("phase",phase[0],phase[1]);
            prog.setTexture("InputBuffer",coarse);
            prog.drawBlocks(chroma); prog.closed = true;

            prog.useAssetProgram("remosaic/tetra/guide");
            common(prog,size,phase,quad,black,white,gains);
            prog.setVarFloats("blockGain",profile);
            prog.setTexture("RawBuffer",raw);
            prog.setTexture("CoarseBuffer",chroma);
            prog.setVar("detailTrust",trust[0],trust[1]);
            prog.drawBlocks(guide); prog.closed = true;

            prog.useAssetProgram("remosaic/tetra/reconstruct");
            common(prog,size,phase,quad,black,white,gains);
            prog.setTexture("GuideBuffer",guide);
            prog.drawBlocks(out); prog.closed = true;
            success = true;
            return out;
        } finally {
            if (coarse != null) coarse.close();
            if (chroma != null) chroma.close();
            if (guide != null) guide.close();
            if (!success && out != null) out.close();
        }
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
