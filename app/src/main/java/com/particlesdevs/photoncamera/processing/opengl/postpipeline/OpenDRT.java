package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.exifinterface.media.ExifInterface;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.ultrahdr.GainMapComputer;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;

/* loaded from: classes14.dex */
public class OpenDRT extends Node {

    @Tunable(category = "OpenDRT", defaultValue = 1.66f, description = "OpenDRT Standard look tone contrast", max = 2.0f, min = 1.0f, step = 0.01f, title = ExifInterface.TAG_CONTRAST)
    float contrast;

    @Tunable(category = "OpenDRT", defaultValue = 10.0f, description = "OpenDRT display grey luminance", max = 25.0f, min = 3.0f, step = DynamicAnimation.MIN_VISIBLE_CHANGE_ROTATION_DEGREES, title = "Display Grey (nits)")
    float displayGrey;

    @Tunable(category = "OpenDRT", defaultValue = 100.0f, description = "SDR display peak used by the OpenDRT tone scale", max = 400.0f, min = 80.0f, step = 1.0f, title = "Display Peak (nits)")
    float displayPeak;

    @Tunable(category = "OpenDRT", defaultValue = 0.13f, description = "OpenDRT HDR grey boost; neutral at SDR peak", max = 1.0f, min = 0.0f, step = 0.001f, title = "HDR Grey Boost")
    float greyBoost;

    @Tunable(category = "OpenDRT", defaultValue = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY, description = "OpenDRT purity-compression blend from 100 to 1000 nit display peaks", max = 1.0f, min = 0.0f, step = 0.01f, title = "HDR Purity")
    float hdrPurity;

    @Tunable(category = "OpenDRT", defaultValue = 0.0f, description = "0 Standard, 1 Arriba, 2 Sylvan, 3 Colorful, 4 Aery, 5 Dystopic, 6 Umbra", max = GainMapComputer.SCALE, min = 0.0f, step = 1.0f, title = "Look Preset")
    int lookPreset;

    @Tunable(category = "OpenDRT", defaultValue = 0.003f, description = "OpenDRT shadow toe", max = DynamicAnimation.MIN_VISIBLE_CHANGE_ROTATION_DEGREES, min = 0.0f, step = 0.001f, title = "Toe")
    float toe;

    @Tunable(category = "OpenDRT", defaultValue = 0.0f, description = "0 uses the look; 1 Low, 2 Medium, 3 High, 4-9 look scales, 10 ACES 1.x, 11 ACES 2.0, 12 Marvelous, 13 DaGrinchi", max = 13.0f, min = 0.0f, step = 1.0f, title = "Tone-scale Preset")
    int toneScalePreset;

    public OpenDRT() {
        super("", "OpenDRT");
        this.displayPeak = 100.0f;
        this.displayGrey = 10.0f;
        this.contrast = 1.66f;
        this.toe = 0.003f;
        this.greyBoost = 0.13f;
        this.hdrPurity = 0.5f;
        this.lookPreset = 0;
        this.toneScalePreset = 0;
    }

    @Override // com.particlesdevs.photoncamera.processing.opengl.nodes.Node
    public void Compile() {
    }

    @Override // com.particlesdevs.photoncamera.processing.opengl.nodes.Node
    public void Run() {
        this.glProg.setDefine("ODRT_PEAK", this.displayPeak);
        this.glProg.setDefine("ODRT_GREY", this.displayGrey);
        this.glProg.setDefine("ODRT_CONTRAST", this.contrast);
        this.glProg.setDefine("ODRT_TOE", this.toe);
        this.glProg.setDefine("ODRT_GREY_BOOST", this.greyBoost);
        this.glProg.setDefine("ODRT_HDR_PURITY", this.hdrPurity);
        this.glProg.setDefine("ODRT_LOOK", this.lookPreset);
        this.glProg.setDefine("ODRT_TONESCALE", this.toneScalePreset);
        this.glProg.setDefine("ODRT_OUTPUT_P3", false);
        this.glProg.setDefine("ODRT_POSTLUT", false);
        this.glProg.useAssetProgram("opendrt");
        this.glProg.setTexture("InputBuffer", this.previousNode.WorkingTexture);
        this.WorkingTexture = this.basePipeline.getMain();
        this.glProg.drawBlocks(this.WorkingTexture);
        this.glProg.closed = true;
    }
}
