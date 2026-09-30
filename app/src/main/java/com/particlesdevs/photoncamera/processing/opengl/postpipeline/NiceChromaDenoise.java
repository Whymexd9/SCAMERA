package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * Colour-noise removal on the SCAM HDR RGB. White balance multiplies the red and blue
 * noise of the model output by 1.8-2.2 and the colour matrix spreads it further, which shows up
 * as coloured blotches in flat dark areas (walls, sky). The stock ISP removes them with a wide
 * chroma filter; here the colour ratios are filtered edge-aware at half resolution over about
 * 28 px (three dilated passes) and only the correction is applied to the full-resolution image.
 * Runs on the white-balanced linear image after {@link HighlightRecovery}.
 */
public final class NiceChromaDenoise extends Node {
    public NiceChromaDenoise() { super("", "NiceChromaDenoise"); }
    @Override public void Compile() {}

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        float amount = Math.max(0f, Math.min(2f, PreferenceKeys.niceInternalValue("post_chroma", 1f)));
        if (amount <= 0f) { glProg.closed = true; return; }
        long started = System.currentTimeMillis();
        GLTexture input = previousNode.WorkingTexture;
        GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        Point half = new Point((input.mSize.x + 1) / 2, (input.mSize.y + 1) / 2);
        GLTexture before = new GLTexture(half, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        GLTexture ping = new GLTexture(half, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        GLTexture pong = new GLTexture(half, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        try {
            glProg.useAssetProgram("chromadn/down", false);
            glProg.setTexture("InputBuffer", input);
            glProg.drawBlocks(before);
            GLTexture source = before;
            GLTexture[] targets = {ping, pong, ping};
            int[] steps = {1, 2, 4};
            float strength = Math.min(1f, amount);
            float tolerance = 0.2f * Math.max(1f, amount);
            for (int pass = 0; pass < 3; pass++) {
                glProg.useAssetProgram("chromadn/filter", false);
                glProg.setTexture("InputBuffer", source);
                glProg.setVar("step", steps[pass]);
                glProg.setVar("strength", strength);
                glProg.setVar("tolerance", tolerance);
                glProg.drawBlocks(targets[pass]);
                source = targets[pass];
            }
            glProg.useAssetProgram("chromadn/apply", false);
            glProg.setTexture("InputBuffer", input);
            glProg.setTexture("Before", before);
            glProg.setTexture("After", source);
            WorkingTexture = pipeline.getMain();
            glProg.drawBlocks(WorkingTexture);
            glProg.closed = true;
        } finally {
            before.close();
            ping.close();
            pong.close();
        }
        Log.i("NICE_PIPELINE", "chromaDenoise amount=" + amount + " ms=" + (System.currentTimeMillis() - started));
    }
}
