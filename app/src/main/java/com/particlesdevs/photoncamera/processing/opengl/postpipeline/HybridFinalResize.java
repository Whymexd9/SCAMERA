package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * SCAM HDR hybrid on the Sabre 2x grid: the final 12/16/20 MP size is produced on the GPU as the last processing
 * step (after sharpening, before the rotation), so the readback buffer, the bitmap and the JPEG encoder never see the
 * 50 MP image (the CPU resize of a 201 MB bitmap got the process killed on a phone that was already low on memory).
 * Kernels follow {@link com.particlesdevs.photoncamera.processing.ml.ScamPostDownscale}: lanczos, bicubic, area, bilinear.
 */
public class HybridFinalResize extends Node {
    private final Point target;
    private final String kernelName;

    public HybridFinalResize(Point target, String kernelName) {
        super("", "FinalResize");
        this.target = target;
        this.kernelName = kernelName == null ? "lanczos" : kernelName;
    }

    static int kernelIndex(String name) {
        if ("bicubic".equals(name)) return 1;
        if ("area".equals(name)) return 2;
        if ("bilinear".equals(name)) return 3;
        return 0;
    }

    @Override
    public void Compile() {}

    @Override
    public void Run() {
        GLTexture in = previousNode.WorkingTexture;
        if (target.x >= in.mSize.x || target.y >= in.mSize.y || target.x * 4 < in.mSize.x || target.y * 4 < in.mSize.y)
            throw new IllegalStateException("final resize " + in.mSize.x + "x" + in.mSize.y + " -> " + target.x + "x" + target.y
                    + " out of range (the output buffer is already at the final size)");
        final long started = System.nanoTime();
        // The two idle ping-pong textures (and the scratch one) are not needed any more: free them before the
        // allocation so the GPU peak drops instead of growing (runAll would only close them after the readback).
        GLTexture[] mains = {basePipeline.main1, basePipeline.main2, basePipeline.main3};
        for (int i = 0; i < mains.length; i++) {
            if (mains[i] != null && mains[i] != in) {
                mains[i].close();
                if (i == 0) basePipeline.main1 = null; else if (i == 1) basePipeline.main2 = null; else basePipeline.main3 = null;
            }
        }
        glProg.useAssetProgram("hybrid/final_resize", false);
        glProg.setTexture("InputBuffer", in);
        glProg.setVar("kernel", kernelIndex(kernelName));
        // Integer sizes: the shader splits the input-space centre exactly (a float centre lost precision on Adreno).
        glProg.setVar("inSize", in.mSize.x, in.mSize.y);
        glProg.setVar("outSize", target.x, target.y);
        WorkingTexture = new GLTexture(target, in.mFormat, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        glProg.drawBlocks(WorkingTexture);
        ((PostPipeline) basePipeline).finalResized = true;
        android.util.Log.i("SCAM_HDR", "hybrid final size " + target.x + "x" + target.y + " (" + kernelName + ", GPU) from "
                + in.mSize.x + "x" + in.mSize.y + " ms=" + (System.nanoTime() - started) / 1000000);
    }
}
