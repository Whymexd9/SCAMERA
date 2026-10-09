package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.util.Log;

/**
 * ARK tone: weakens the post-tone sharpening (RawTherapee or SCAM, unchanged and with their own settings) where the
 * fusion lifted the shadows (verify_tone risk C). ArkCam sharpens its guide before the detail delta, so the lift never
 * multiplies sharpened noise; here out = pre + w (sharpened - pre) with w = gain^-ark_sharp_guard, which ArkCombine
 * stored in the alpha of its kept output. Nothing lifted (gain <= 1): the sharpening stays as it is. Output alpha 1.
 */
public final class ArkSharpenGuard extends Node {
    public ArkSharpenGuard() { super("", "ArkSharpenGuard"); }
    @Override public void Compile() {}

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        GLTexture sharp = previousNode.WorkingTexture;
        ArkTone.State st = pipeline.ark;
        GLTexture pre = st != null ? st.preSharpen : null;
        if (pre == null || pre.mSize == null || !pre.mSize.equals(sharp.mSize)) {
            WorkingTexture = sharp;
            glProg.closed = true;
            pipeline.ark = null;
            return;
        }
        long started = System.currentTimeMillis();
        glProg.useAssetProgram("ark/guard", false);
        glProg.setTexture("InputBuffer", sharp);
        glProg.setTexture("PreBuffer", pre);
        WorkingTexture = pipeline.getMain();
        if (WorkingTexture == sharp || WorkingTexture == pre) WorkingTexture = pipeline.getMain();
        glProg.drawBlocks(WorkingTexture);
        glProg.closed = true;
        Log.i("SCAM_PIPELINE", "ARK sharpen guard exponent=" + st.guard + (sharp == pre ? " (no sharpening ran)" : "")
                + " ms=" + (System.currentTimeMillis() - started));
        pipeline.ark = null;
    }
}
