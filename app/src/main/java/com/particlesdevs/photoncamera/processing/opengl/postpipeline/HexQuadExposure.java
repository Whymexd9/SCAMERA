package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.Log;

/** Display brightness trim after automatic tone mapping, isolated from neural RAW radiometry. */
public final class HexQuadExposure extends Node {
    private final boolean linearInput;

    public HexQuadExposure(boolean linearInput) {
        super("", "HexQuadExposure");
        this.linearInput = linearInput;
    }

    @Override public void Compile() {}

    @Override public void Run() {
        float ev = basePipeline.mParameters.hexQuadExposureEv;
        if (!basePipeline.mParameters.hexQuadProcessed || !Float.isFinite(ev) || ev == 0f) {
            WorkingTexture = previousNode.WorkingTexture;
            glProg.closed = true;
            return;
        }
        ev = Math.max(-2f, Math.min(2f, ev));
        glProg.setDefine("LINEARINPUT", linearInput);
        glProg.useAssetProgram("HexQuad/exposure");
        glProg.setTexture("InputBuffer", previousNode.WorkingTexture);
        glProg.setVar("exposureEv", ev);
        WorkingTexture = basePipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
        Log.i(Name, "HEX DISPLAY EXPOSURE: EV=" + ev + " linear_input=" + linearInput
                + "; after auto tone; highlight protection; sensor RAW unchanged");
    }
}
