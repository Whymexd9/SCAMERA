package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.ml.SaliencyProtection;
import com.particlesdevs.photoncamera.util.Log;
import java.nio.ByteBuffer;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/* loaded from: classes14.dex */
public class FalseColorSuppression extends Node {
    public FalseColorSuppression() {
        super("", "FalseColorSuppression");
    }

    @Override // com.particlesdevs.photoncamera.processing.opengl.nodes.Node
    public void Compile() {
    }

    @Override // com.particlesdevs.photoncamera.processing.opengl.nodes.Node
    public void Run() {
        GLTexture mask=null;
        int rotation=((PostPipeline)basePipeline).getRotation();
        if (PreferenceKeys.isSaliencyProtectionEnabled() && PreferenceKeys.isFalseColorCorrectionEnabled()
                && PreferenceKeys.getFalseColorStrength()>0) {
            long start=System.nanoTime();
            try(GLTexture thumbnail=new GLTexture(SaliencyProtection.WIDTH,SaliencyProtection.HEIGHT,
                    new GLFormat(GLFormat.DataType.FLOAT_16,4))) {
                glProg.useAssetProgram("FalseColor/saliency_input");
                glProg.setTexture("InputBuffer",previousNode.WorkingTexture);
                glProg.setVar("rotation",rotation);
                glProg.drawBlocks(thumbnail);
                ByteBuffer pixels=thumbnail.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_32,4));
                ByteBuffer weights=SaliencyProtection.infer(pixels);
                mask=new GLTexture(SaliencyProtection.WIDTH,SaliencyProtection.HEIGHT,
                        new GLFormat(GLFormat.DataType.FLOAT_16,1),weights);
                Log.i("SaliencyProtection","Google model applied, ms="+(System.nanoTime()-start)/1_000_000L);
            } catch(Exception | LinkageError error) {
                // A missing delegate/operator must never discard a captured photo.
                Log.e("SaliencyProtection","Mask unavailable; ordinary colour filter: "+error);
            }
        }
        try {
            this.glProg.setDefine("HAS_SALIENCY",mask!=null);
            this.glProg.setDefine("SIZE", this.previousNode.WorkingTexture.mSize);
            this.glProg.setDefine("STRENGTH", PreferenceKeys.isFalseColorCorrectionEnabled() ? PreferenceKeys.getFalseColorStrength() / 100.0f : 0.0f);
            this.glProg.setDefine("PURPLE", PreferenceKeys.getDefringePurple() / 100.0f);
            this.glProg.setDefine("GREEN", PreferenceKeys.getDefringeGreen() / 100.0f);
            this.glProg.setDefine("CA_RED", PreferenceKeys.getCaRed() / 100.0f);
            this.glProg.setDefine("CA_BLUE", PreferenceKeys.getCaBlue() / 100.0f);
            this.glProg.useAssetProgram("FalseColor/falsecolor");
            this.glProg.setTexture("InputBuffer", this.previousNode.WorkingTexture);
            if(mask!=null) {
                glProg.setTexture("SaliencyMask",mask);
                glProg.setVar("maskRotation",rotation);
            }
            this.WorkingTexture = this.basePipeline.getMain();
            this.glProg.drawBlocks(this.WorkingTexture);
            this.glProg.closed = true;
        } finally { if(mask!=null)mask.close(); }
    }
}
