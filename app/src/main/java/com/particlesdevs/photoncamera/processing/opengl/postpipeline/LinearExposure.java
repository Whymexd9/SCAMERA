package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

/**
 * Keeps the linear scene snapshot for the Ultra HDR gain-map pass (the linear RGB of the merge, before the ARK
 * tone). The exposure itself is ArkStats' (ArkAe). Renders nothing: passes the input texture through and marks the
 * program closed so the pipeline skips the draw for this node.
 */
public class LinearExposure extends Node {
    public LinearExposure() {
        super("", "LinearExposure");
    }

    @Override
    public void Compile() {}

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        if (pipeline.captureDemosaic) {
            pipeline.captureDemosaicLinear(previousNode.WorkingTexture);
        }
        WorkingTexture = previousNode.WorkingTexture;
        glProg.closed = true;
    }
}
