package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

/**
 * GCam's pixel-grouping demosaic: gradient-selected green, then hue transit for
 * red/blue. Two light passes instead of AMaZE's heavy multi-pass reconstruction.
 */
public class PixelGroupingDemosaic extends Node {
    public PixelGroupingDemosaic() {
        super("", "PixelGroupingDemosaic");
    }

    @Override
    public void Compile() {}

    @Override
    public void Run() {
        float[] wp = basePipeline.mParameters.whitePoint;
        glProg.useAssetProgram("demosaic/pg_green");
        glProg.setTexture("RawBuffer", previousNode.WorkingTexture);
        glProg.setVar("neutral", wp[0], wp[1], wp[1], wp[2]);
        glProg.drawBlocks(basePipeline.main1);
        glProg.useAssetProgram("demosaic/pg_rb");
        glProg.setTexture("RawBuffer", previousNode.WorkingTexture);
        glProg.setTexture("GreenBuffer", basePipeline.main1);
        glProg.setVar("neutral", wp[0], wp[1], wp[1], wp[2]);
        WorkingTexture = basePipeline.main3;
        glProg.drawBlocks(WorkingTexture);
        glProg.close();
        WorkingTexture = basePipeline.swap3();
    }
}
