package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;
import android.opengl.GLES31;

import com.particlesdevs.photoncamera.processing.color.DiffusionFft;
import com.particlesdevs.photoncamera.processing.color.LensFilters;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;

/**
 * P228 / P287 / P288: the lens filter on the linear scene-referred RGB after the merge and its denoise, before the ARK tone
 * (where a bright source still has its real energy): the reference model's diffusion filters (Glimmerglass, Black Pro-Mist,
 * Pro-Mist, CineBloom; DiffusionModel) as the reference applies them, out = (1 - p) E + p (K * E), by FFT convolution on the
 * GPU - DiffusionFft's plan: each exponential of the PSF on the box-pyramid level where it is 4..8 pixels wide, one FFT per
 * level (overlapping tiles of 1024 where a level is larger), the levels summed from the coarsest up (lensfft/*.glsl). The
 * filter is linear: no knee, no level of the frame. No filter (or strength 0): the node only passes its input on.
 */
public final class LensFilter extends Node {
    private final String id;
    private final float strength;
    private final LensFilters.Tune tune;

    /** The FFT planes' memory budget of one batch of tiles (bytes; the ping-pong buffer doubles it). */
    static final int BATCH_BYTES = 48 << 20;

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
        final DiffusionFft.Plan plan = DiffusionFft.plan(id, strength, tune, in.mSize.x, in.mSize.y, Math.max(frameW, frameH));
        if (plan == null || plan.top() < 0) return;
        final GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        final List<GLTexture> temp = new ArrayList<>();
        final int[] buf = new int[4];
        GLES31.glGenBuffers(4, buf, 0);
        int capacity = 0, kernelCapacity = 0;
        GLTexture out = null;
        try {
            final int top = plan.top();
            final GLTexture[] level = new GLTexture[top + 1];
            level[0] = in;
            for (int l = 1; l <= top; l++) {
                final DiffusionFft.Tier t = plan.tiers[l];
                level[l] = new GLTexture(new Point(t.w, t.h), rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                temp.add(level[l]);
                glProg.useAssetProgram("lensfft/down", false);
                glProg.setTexture("InputBuffer", level[l - 1]);
                glProg.drawBlocks(level[l]);
            }
            out = new GLTexture(in.mSize, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            GLTexture coarse = null;
            int ffts = 0;
            for (int l = top; l >= 0; l--) {
                final DiffusionFft.Tier t = plan.tiers[l];
                final GLTexture target;
                if (l == 0) target = out;
                else {
                    target = new GLTexture(new Point(t.w, t.h), rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                    temp.add(target);
                }
                if (t.empty()) {
                    glProg.useAssetProgram("lensfft/carry", false);
                    glProg.setTexture("Coarse", coarse);
                    glProg.setTexture("Image", level[l]);
                    glProg.setVar("finalU", l == 0 ? 1 : 0);
                    glProg.setVar("pU", (float) plan.p);
                    glProg.drawBlocks(target);
                    coarse = target;
                    continue;
                }
                final int g = t.grid;
                final int plane = g * g * 8;
                // the kernel's spectrum (three planes, one per channel)
                if (3 * plane > kernelCapacity) {
                    kernelCapacity = 3 * plane;
                    for (int k = 2; k < 4; k++) {
                        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[k]);
                        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, kernelCapacity, null, GLES31.GL_DYNAMIC_COPY);
                    }
                }
                final FloatBuffer kb = ByteBuffer.allocateDirect(3 * plane).order(ByteOrder.nativeOrder()).asFloatBuffer();
                for (int c = 0; c < 3; c++) {
                    final float[] k = t.kernel(c);
                    for (float v : k) kb.put(v).put(0f);
                }
                kb.rewind();
                GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[2]);
                GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 3 * plane, kb);
                fft2(buf[2], buf[3], g, 3, -1);
                ffts += 3;
                // the tiles in batches
                final int tiles = t.tilesX * t.tilesY;
                final int batch = Math.max(1, Math.min(tiles, BATCH_BYTES / (3 * plane)));
                if (batch * 3 * plane > capacity) {
                    capacity = batch * 3 * plane;
                    for (int k = 0; k < 2; k++) {
                        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[k]);
                        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, capacity, null, GLES31.GL_DYNAMIC_COPY);
                    }
                }
                for (int first = 0; first < tiles; first += batch) {
                    final int nb = Math.min(batch, tiles - first);
                    glProg.setLayout(16, 16, 1);
                    glProg.useAssetProgram("lensfft/load", true);
                    GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buf[0]);
                    glProg.setTexture("Level", level[l]);
                    glProg.setVar("levelU", t.w, t.h);
                    glProg.setVar("tileU", g, t.useful, t.radius, t.tilesX);
                    glProg.setVar("firstTileU", first);
                    GLES31.glDispatchCompute((g + 15) / 16, (g + 15) / 16, nb * 3);
                    GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
                    fft2(buf[0], buf[1], g, nb * 3, -1);
                    glProg.setLayout(16, 16, 1);
                    glProg.useAssetProgram("lensfft/mul", true);
                    GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buf[0]);
                    GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, buf[2]);
                    glProg.setVar("gridU", g);
                    GLES31.glDispatchCompute((g + 15) / 16, (g + 15) / 16, nb * 3);
                    GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
                    fft2(buf[0], buf[1], g, nb * 3, 1);
                    ffts += 2 * nb * 3;
                    glProg.setLayout(16, 16, 1);
                    glProg.useAssetProgram("lensfft/store", true);
                    GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buf[0]);
                    GLES31.glBindImageTexture(0, target.mTextureID, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA16F);
                    glProg.setTexture("Coarse", coarse != null ? coarse : level[l]);
                    glProg.setTexture("Image", in);
                    glProg.setVar("levelU", t.w, t.h);
                    glProg.setVar("tileU", g, t.useful, t.radius, t.tilesX);
                    glProg.setVar("firstTileU", first);
                    glProg.setVar("hasCoarseU", coarse != null ? 1 : 0);
                    glProg.setVar("finalU", l == 0 ? 1 : 0);
                    glProg.setVar("pU", (float) plan.p);
                    GLES31.glDispatchCompute((t.useful + 15) / 16, (t.useful + 15) / 16, nb);
                    GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT
                            | GLES31.GL_FRAMEBUFFER_BARRIER_BIT | GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
                    GLES31.glBindImageTexture(0, 0, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA16F);
                }
                coarse = target;
            }
            glProg.closed = true;
            final int error = GLES31.glGetError();
            if (error != GLES31.GL_NO_ERROR) {
                Log.w("SCAM_PIPELINE", "lens filter FFT: GL error 0x" + Integer.toHexString(error) + ", the photo without the filter");
                out.close();
                return;
            }
            WorkingTexture = out;
            out = null;
            final StringBuilder levels = new StringBuilder();
            for (DiffusionFft.Tier t : plan.tiers)
                if (!t.empty()) levels.append(' ').append(t.level).append(':').append(t.grid).append('x').append(t.tilesX * t.tilesY);
            Log.i("SCAM_PIPELINE", "lens filter " + id + String.format(java.util.Locale.ROOT, " p %.3f", plan.p) + " levels" + levels
                    + " ffts " + ffts + " ms=" + (System.currentTimeMillis() - started));
        } finally {
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
            GLES31.glDeleteBuffers(4, buf, 0);
            for (GLTexture t : temp) t.close();
            if (out != null && out != WorkingTexture) out.close();
        }
    }

    /** The 2D FFT of {@code planes} grid x grid planes in buffer {@code a} ({@code b} the scratch): the result ends in a. */
    private void fft2(int a, int b, int grid, int planes, int sign) {
        int src = a, dst = b;
        for (int dir = 0; dir < 2; dir++) {
            for (int ns = 1; ns < grid; ns <<= 1) {
                glProg.setLayout(64, 1, 1);
                glProg.useAssetProgram("lensfft/fft", true);
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, src);
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, dst);
                glProg.setVar("fftU", grid, ns, dir, sign);
                GLES31.glDispatchCompute(Math.max(1, (grid / 2 + 63) / 64), grid, planes);
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
                final int t = src;
                src = dst;
                dst = t;
            }
        }
        // 2 log2(grid) passes: an even count, the result is back in a
    }
}
