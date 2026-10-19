package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

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
 * P228: the lens filter (LensFilters: Black Pro-Mist ... Infrared) on the linear scene-referred RGB after the merge and its
 * denoise, before the ARK tone - where a bright source still has its real energy. The diffusion filters (DiffusionModel, owner
 * 2026-10-18: a share of all the light spread by the filter's PSF, energy kept) build the image's own pyramid down to about two
 * pixels and sum its levels with per-channel weights fitted to the PSF (B-spline steps, lensfilter/upsum.glsl); the prism its
 * soft levels 4-6; Streak and Glimmerglass's star a highlight plane and a streak plane; then one apply pass. No filter (or strength 0): the node only passes its input on (the photo is
 * byte-identical to the one without the node).
 * <p>
 * Owner, 2026-10-18 («фильтры применяются неверно» on the OPPO): (1) the image here is still in the sensor's orientation
 * (RotateWatermark turns it at the end): GND's top, the Streak's direction and the Prism's shift now follow the photo's own frame;
 * (2) the image here is before the ARK exposure: the knee and the lift are set against the frame's own level (its log-average
 * luminance brought to middle grey, {@link #exposure}), so a filter looks the same on a dark or a bright merge and on any phone.
 */
public final class LensFilter extends Node {
    private final String id;
    private final float strength, param;
    private final LensFilters.Tune tune;

    /** The photo's middle grey: the log-average luminance of the frame is brought here to set the knee and the lift. */
    static final float KEY = 0.18f;

    /** RotateWatermark's turn code of a rotation in degrees (0, 270 -> 1, 180 -> 2, 90 -> 3). */
    static int turn(int degrees) {
        switch (((degrees % 360) + 360) % 360) {
            case 90: return 3;
            case 180: return 2;
            case 270: return 1;
            default: return 0;
        }
    }

    /** The share of the frame's brightest pixels allowed above white by the level (the light sources, a bright screen's text). */
    static final double BRIGHT = 0.005;

    /**
     * The gain that brings the frame to middle grey: KEY over the log-average luminance of {@code rgba} (RGBA floats of a small
     * copy of the image), but at most what puts the brightest pixels (the top {@link #BRIGHT}) at white - a dark room with a
     * lit screen is not lifted until the screen's dark parts glow (owner, 2026-10-18 on the OPPO: Pro-Mist turned the whole
     * screen milky at level x28) - clamped to 1/4 .. 32; 1 when the copy is empty or black.
     */
    static float exposure(java.nio.FloatBuffer rgba, int pixels) {
        double sum = 0;
        int n = 0;
        float[] lum = new float[Math.max(0, pixels)];
        for (int i = 0; i < pixels; i++) {
            float r = rgba.get(i * 4), g = rgba.get(i * 4 + 1), b = rgba.get(i * 4 + 2);
            if (!Float.isFinite(r) || !Float.isFinite(g) || !Float.isFinite(b)) continue;
            double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            lum[n] = (float) y;
            sum += Math.log(Math.max(y, 1e-4));
            n++;
        }
        if (n == 0) return 1f;
        double mean = Math.exp(sum / n);
        if (!(mean > 1e-4)) return 1f;
        double gain = KEY / mean;
        java.util.Arrays.sort(lum, 0, n);
        float bright = lum[Math.min(n - 1, (int) Math.floor(n * (1.0 - BRIGHT)))];
        if (bright > 1e-4f) gain = Math.min(gain, 1.0 / bright);
        return (float) Math.max(0.25, Math.min(32.0, gain));
    }

    /**
     * Σ weights[k] levels[k] for k = from..to at levels[from]'s size: from the coarsest level up, each step this level times its
     * weight plus the coarser sum B-spline upsampled (lensfilter/upsum.glsl).
     */
    private GLTexture upsum(GLTexture[] levels, float[][] weights, int from, int to, GLFormat format, List<GLTexture> temp) {
        GLTexture acc = null;
        for (int k = to; k >= from; k--) {
            GLTexture out = new GLTexture(levels[k].mSize, format, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            temp.add(out);
            glProg.useAssetProgram("lensfilter/upsum", false);
            glProg.setTexture("Level", levels[k]);
            glProg.setTexture("Small", acc != null ? acc : levels[k]);
            glProg.setVar("weight", weights[k][0], weights[k][1], weights[k][2]);
            glProg.setVar("hasSmall", acc != null ? 1 : 0);
            glProg.drawBlocks(out);
            acc = out;
        }
        return acc;
    }

    public LensFilter(String id, float strength, LensFilters.Tune tune) {
        super("", "LensFilter");
        this.id = id;
        this.strength = strength;
        this.tune = tune;
        this.param = tune.param;
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
        final int filter = LensFilters.index(id);
        if (filter <= 0 || strength <= 0f) return;
        final long started = System.currentTimeMillis();
        final float[] shape = LensFilters.shape(id, tune);
        final GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        final List<GLTexture> temp = new ArrayList<>();
        final int rot = turn(pipeline.getRotation());
        try {
            final boolean diffuses = com.particlesdevs.photoncamera.processing.color.DiffusionModel.diffuses(id);
            // the highlight plane: the Streak and Glimmerglass's star only (the diffusion spreads all the light, no knee)
            final boolean needsHi = LensFilters.streaks(id);
            // the frame's level: a copy at most 64 pixels wide, read back (a few KB)
            float expo = 1f;
            if (needsHi) {
                GLTexture small = in;
                while (small.mSize.x > 64) {
                    GLTexture next = glUtils.gaussianDownsample(small, new Point(Math.max(1, small.mSize.x / 2), Math.max(1, small.mSize.y / 2)));
                    temp.add(next);
                    small = next;
                }
                int count = small.mSize.x * small.mSize.y;
                java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocateDirect(count * 16).order(java.nio.ByteOrder.nativeOrder());
                small.BufferLoad();   // glReadPixels reads the bound framebuffer
                small.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_16, 4), bytes);
                bytes.rewind();
                expo = exposure(bytes.asFloatBuffer(), count);
            }
            // P277: a lab fragment takes the whole frame's level and size (knee, streak length, PSF), a lab shot records them
            final LabFrame frozen = pipeline.frozen;
            final boolean whole = frozen != null && frozen.width > 0 && frozen.height > 0;
            if (needsHi && frozen != null && Float.isFinite(frozen.lensExpo)) expo = frozen.lensExpo;
            if (pipeline.record != null) {
                if (needsHi) pipeline.record.lensExpo = expo;
                pipeline.record.width = in.mSize.x;
                pipeline.record.height = in.mSize.y;
            }
            final int frameW = whole ? frozen.width : in.mSize.x, frameH = whole ? frozen.height : in.mSize.y;
            // Glimmerglass's star keeps the filter's own knee (its fine «Ореол» control is the diffusion's halo)
            final float knee = (diffuses ? LensFilters.shape(id)[0] : shape[0]) / expo;
            final Point half = new Point(Math.max(1, in.mSize.x / 2), Math.max(1, in.mSize.y / 2));
            GLTexture hi = in;
            if (needsHi) {
                hi = new GLTexture(half, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                temp.add(hi);
                glProg.useAssetProgram("lensfilter/extract", false);
                glProg.setTexture("InputBuffer", in);
                glProg.setVar("knee", knee);
                glProg.drawBlocks(hi);
            }
            // the image's own pyramid: down to about two pixels for the diffusion, to level 6 for the prism's soft ghost
            final int levels = diffuses ? LensFilters.diffusionLevels(in.mSize.x, in.mSize.y)
                    : LensFilters.PRISM.equals(id) ? LensFilters.LEVELS : 0;
            GLTexture[] v = new GLTexture[Math.max(levels, LensFilters.LEVELS) + 1];
            v[0] = in;
            for (int k = 1; k <= levels; k++) {
                Point p = new Point(Math.max(1, v[k - 1].mSize.x / 2), Math.max(1, v[k - 1].mSize.y / 2));
                v[k] = glUtils.gaussianDownsample(v[k - 1], p);
                temp.add(v[k]);
            }
            float[] w0 = {1f, 1f, 1f};
            GLTexture diffAcc = in;
            if (diffuses) {
                float[][] w = com.particlesdevs.photoncamera.processing.color.DiffusionModel.levelWeights(id, strength, tune,
                        Math.max(frameW, frameH), com.particlesdevs.photoncamera.processing.color.DiffusionModel.pyramidSigmas(levels, 1.0));
                if (w != null && levels >= 1) {
                    diffAcc = upsum(v, w, 1, levels, rgba, temp);
                    w0 = w[0];
                }
            }
            GLTexture streaks = hi;
            if (LensFilters.streaks(id)) {
                final boolean star = LensFilters.GLIMMERGLASS.equals(id);
                streaks = new GLTexture(half, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                temp.add(streaks);
                glProg.useAssetProgram("lensfilter/streak", false);
                glProg.setTexture("Highlights", hi);
                // the Streak runs along the photo's width: the sensor's x, or its y in a turned (portrait) photo
                final boolean turned = rot == 1 || rot == 3;
                glProg.setVar("dirA", star ? 1f : turned ? 0f : 1f, star ? 1f : turned ? 1f : 0f);
                glProg.setVar("dirB", star ? 1f : 0f, star ? -1f : 0f);
                // the CPU model's length is in full-size pixels; the plane is half size
                final float len = LensFilters.streakLength(id, tune) * ((rot == 1 || rot == 3) ? frameH : frameW) * 0.5f;
                glProg.setVar("lengthPx", len);
                glProg.setVar("gain", LensFilters.streakGain(id));
                glProg.drawBlocks(streaks);
            }
            final float[] tint = LensFilters.tint(id, tune);
            // the prism's soft ghost: levels 4..6 of the image summed from the coarse end with B-spline steps
            final float[][] veilW = new float[7][];
            for (int k = 0; k < 7; k++) { float x = k == 4 ? 0.2f : k >= 5 ? 0.4f : 0f; veilW[k] = new float[]{x, x, x}; }
            final GLTexture veilAcc = LensFilters.PRISM.equals(id) ? upsum(v, veilW, 4, 6, rgba, temp) : in;
            glProg.setDefine("FILTER", filter);
            glProg.useAssetProgram("lensfilter/apply", false);
            glProg.setTexture("InputBuffer", in);
            glProg.setTexture("DiffAcc", diffAcc);
            glProg.setTexture("VeilAcc", veilAcc);
            glProg.setTexture("Streaks", streaks);
            glProg.setVar("w0", w0[0], w0[1], w0[2]);
            glProg.setVar("tint", tint[0], tint[1], tint[2]);
            glProg.setVar("rot", rot);
            glProg.setVar("strength", LensFilters.PRISM.equals(id) ? strength * LensFilters.hazeGain(tune.haze) : strength);
            glProg.setVar("param", param);
            glProg.setVar("segments", (float) LensFilters.segments(param));
            WorkingTexture = pipeline.getMain();
            glProg.drawBlocks(WorkingTexture);
            glProg.closed = true;
            Log.i("SCAM_PIPELINE", "lens filter " + id + " strength " + strength + " tune " + tune.serialize()
                    + (diffuses ? " scatter " + String.format(java.util.Locale.US, "%.3f", 1f - w0[1]) + " levels " + levels : "")
                    + (needsHi ? " level x" + expo + " knee " + knee : "") + " turn " + rot + " " + in.mSize.x + "x" + in.mSize.y
                    + " ms=" + (System.currentTimeMillis() - started));
        } finally {
            for (GLTexture t : temp) t.close();
        }
    }
}
