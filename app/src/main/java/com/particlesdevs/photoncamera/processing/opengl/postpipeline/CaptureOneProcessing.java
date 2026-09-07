package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

/* loaded from: classes14.dex */
public class CaptureOneProcessing extends Node {
    public CaptureOneProcessing() {
        super("", "CaptureOneProcessing");
    }

    @Override // com.particlesdevs.photoncamera.processing.opengl.nodes.Node
    public void Compile() {
    }

    @Override // com.particlesdevs.photoncamera.processing.opengl.nodes.Node
    public void Run() {
        this.glProg.setDefine("SIZE", this.basePipeline.mParameters.rawSize);
        this.glProg.setDefine("ENABLED", PreferenceKeys.isCaptureOneEnabled());
        this.glProg.setDefine("LCC", PreferenceKeys.getC1Lcc() / 100.0f);
        this.glProg.setDefine("LCC_RED", PreferenceKeys.getC1LccRed() / 100.0f);
        this.glProg.setDefine("LCC_BLUE", PreferenceKeys.getC1LccBlue() / 100.0f);
        this.glProg.setDefine("CLARITY", PreferenceKeys.getC1Clarity() / 100.0f);
        this.glProg.setDefine("STRUCTURE", PreferenceKeys.getC1Structure() / 100.0f);
        this.glProg.setDefine("MOIRE", PreferenceKeys.getC1Moire() / 100.0f);
        this.glProg.setDefine("SINGLE_PIXEL", PreferenceKeys.getC1SinglePixel() / 100.0f);
        this.glProg.setDefine("SKIN", PreferenceKeys.getC1Skin() / 100.0f);
        this.glProg.setDefine("COLOR_HUE", PreferenceKeys.getC1ColorHue() / 100.0f);
        this.glProg.setDefine("COLOR_RANGE", PreferenceKeys.getC1ColorRange() / 100.0f);
        this.glProg.setDefine("COLOR_SHIFT", PreferenceKeys.getC1ColorShift() / 100.0f);
        this.glProg.setDefine("COLOR_SAT", PreferenceKeys.getC1ColorSaturation() / 100.0f);
        this.glProg.setDefine("SHADOWS", PreferenceKeys.getC1Shadows() / 100.0f);
        this.glProg.setDefine("MIDTONES", PreferenceKeys.getC1Midtones() / 100.0f);
        this.glProg.setDefine("HIGHLIGHTS", PreferenceKeys.getC1Highlights() / 100.0f);
        this.glProg.useAssetProgram("CaptureOne/captureone");
        this.glProg.setTexture("InputBuffer", this.previousNode.WorkingTexture);
        this.WorkingTexture = this.basePipeline.getMain();
        this.glProg.drawBlocks(this.WorkingTexture);
        this.glProg.closed = true;
    }
}
