package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
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
        this.glProg.setDefine("SIZE", this.basePipeline.mParameters.rawSize);
        this.glProg.setDefine("STRENGTH", PreferenceKeys.isFalseColorCorrectionEnabled() ? PreferenceKeys.getFalseColorStrength() / 100.0f : 0.0f);
        this.glProg.setDefine("PURPLE", PreferenceKeys.getDefringePurple() / 100.0f);
        this.glProg.setDefine("GREEN", PreferenceKeys.getDefringeGreen() / 100.0f);
        this.glProg.setDefine("CA_RED", PreferenceKeys.getCaRed() / 100.0f);
        this.glProg.setDefine("CA_BLUE", PreferenceKeys.getCaBlue() / 100.0f);
        this.glProg.useAssetProgram("FalseColor/falsecolor");
        this.glProg.setTexture("InputBuffer", this.previousNode.WorkingTexture);
        this.WorkingTexture = this.basePipeline.getMain();
        this.glProg.drawBlocks(this.WorkingTexture);
        this.glProg.closed = true;
    }
}
