package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.color.DiffusionGauss;
import com.particlesdevs.photoncamera.processing.color.LensFilters;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * P228 / P287 / P289: the lens filter on the linear scene-referred RGB after the merge and its denoise, before the WHY tone
 * (where a bright source still has its real energy): the reference model's diffusion filters (Glimmerglass, Black Pro-Mist,
 * Pro-Mist, CineBloom; DiffusionModel), out = (1 - p) E + p (K * E), run as the reference app runs them - DiffusionGauss's
 * plan: every exponential of the PSF as three Gaussians, each a separable blur on the box-pyramid level where it is 3..6 pixels
 * wide (lensgauss/blur.glsl, the y pass adding it to the level's sum), the levels summed from the coarsest up
 * (lensgauss/up.glsl). The filter is linear: no knee, no level of the frame. No filter (or strength 0): the node only passes
 * its input on.
 */
public final class LensFilter extends Node {
    private final String id;
    private final float strength;
    private final LensFilters.Tune tune;

    public LensFilter(String id, float strength, LensFilters.Tune tune) {
        super("", "LensFilter");
        this.id = id;
        this.strength = strength;
        this.tune = tune;
    }

    /** The node for the current settings, null when no filter is chosen (the pipeline skips it). */
    public static LensFilter fromSettings() {
        final String id = PreferenceKeys.lensFilter();
        final float s = PreferenceKeys.lensFilterStrength();
        if (LensFilters.index(id) <= 0 || s <= 0f) return null;
        return new LensFilter(id, s, PreferenceKeys.lensFilterTune(id));
    }

    @Override
    public void Compile() {}

    @Override
    public void Run() {
        final PostPipeline pipeline = (PostPipeline) basePipeline;
        final GLTexture in = previousNode.WorkingTexture;
        WorkingTexture = in;
        if (LensFilters.index(id) <= 0 || strength <= 0f) return;
        final long started = System.currentTimeMillis();
        // P277: a lab fragment takes the whole frame's size for the PSF, a lab shot records it
        final LabFrame frozen = pipeline.frozen;
        final boolean whole = frozen != null && frozen.width > 0 && frozen.height > 0;
        if (pipeline.record != null) {
            pipeline.record.width = in.mSize.x;
            pipeline.record.height = in.mSize.y;
        }
        final int frameW = whole ? frozen.width : in.mSize.x, frameH = whole ? frozen.height : in.mSize.y;
        final DiffusionGauss.Plan plan = DiffusionGauss.plan(id, strength, tune, in.mSize.x, in.mSize.y, Math.max(frameW, frameH));
        if (plan == null) return;
        final GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        final List<GLTexture> temp = new ArrayList<>();
        int passes = 0;
        try {
            final GLTexture[] level = new GLTexture[plan.top + 1];
            level[0] = in;
            for (int l = 1; l <= plan.top; l++) {
                level[l] = new GLTexture(new Point(DiffusionGauss.levelSize(in.mSize.x, l), DiffusionGauss.levelSize(in.mSize.y, l)), rgba,
                        null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                temp.add(level[l]);
                glProg.useAssetProgram("lensgauss/down", false);
                glProg.setTexture("InputBuffer", level[l - 1]);
                glProg.drawBlocks(level[l]);
                passes++;
            }
            GLTexture coarse = null;
            for (int l = plan.top; l >= 0; l--) {
                final Point size = level[l].mSize;
                GLTexture sum = null;
                for (DiffusionGauss.Blur b : plan.blurs) {
                    if (b.level != l) continue;
                    final GLTexture h = new GLTexture(size, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                    final GLTexture next = new GLTexture(size, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                    temp.add(h);
                    temp.add(next);
                    glProg.useAssetProgram("lensgauss/blur", false);
                    glProg.setTexture("InputBuffer", level[l]);
                    glProg.setTexture("Acc", level[l]);
                    glProg.setVar("dir", 1f, 0f);
                    glProg.setVar("sigma", b.sigma);
                    glProg.setVar("weight", 1f, 1f, 1f);
                    glProg.setVar("accU", 0);
                    glProg.setVar("hasAccU", 0);
                    glProg.drawBlocks(h);
                    glProg.useAssetProgram("lensgauss/blur", false);
                    glProg.setTexture("InputBuffer", h);
                    glProg.setTexture("Acc", sum != null ? sum : level[l]);
                    glProg.setVar("dir", 0f, 1f);
                    glProg.setVar("sigma", b.sigma);
                    glProg.setVar("weight", b.weight[0], b.weight[1], b.weight[2]);
                    glProg.setVar("accU", 1);
                    glProg.setVar("hasAccU", sum != null ? 1 : 0);
                    glProg.drawBlocks(next);
                    passes += 2;
                    sum = next;
                }
                final GLTexture target;
                if (l == 0) target = new GLTexture(in.mSize, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                else {
                    target = new GLTexture(size, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                    temp.add(target);
                }
                glProg.useAssetProgram("lensgauss/up", false);
                glProg.setTexture("Fine", sum != null ? sum : level[l]);
                glProg.setTexture("Coarse", coarse != null ? coarse : level[l]);
                glProg.setTexture("Image", level[l]);
                glProg.setVar("hasFineU", sum != null ? 1 : 0);
                glProg.setVar("hasCoarseU", coarse != null ? 1 : 0);
                glProg.setVar("finalU", l == 0 ? 1 : 0);
                glProg.setVar("pU", (float) plan.p);
                glProg.drawBlocks(target);
                passes++;
                coarse = target;
            }
            glProg.closed = true;
            WorkingTexture = coarse;
            Log.i("SCAM_PIPELINE", "lens filter " + id + String.format(java.util.Locale.ROOT, " p %.3f", plan.p) + " gaussians "
                    + plan.blurs.size() + " levels " + (plan.top + 1) + " passes " + passes + " ms=" + (System.currentTimeMillis() - started));
        } finally {
            for (GLTexture t : temp) t.close();
        }
    }
}
