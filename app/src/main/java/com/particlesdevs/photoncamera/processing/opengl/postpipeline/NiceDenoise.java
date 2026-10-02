package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * Noise removal on the SCAM HDR RGB after the network, for SoCs whose network is the distilled
 * merge-only route (it leaves more noise than the vivo original). White balance multiplies the red and blue
 * noise by about two and the colour matrix spreads it further, which shows as coloured
 * blotches in dark areas, and the luminance noise of dark flat surfaces is about twice the level
 * of a GCam/stock render.
 * <p>
 * Dots: isolated dark or bright dots in flat areas (defective pixel groups, which the merge keeps)
 * are scaled back to their surroundings.
 * Colour: the colour is filtered edge-aware at half resolution over about 56 px (four dilated
 * passes) and replaces the full-resolution colour (joint bilateral upsampling); the darkest pixels
 * fade to neutral because their colour is a black-level error rather than a measurement.
 * Luma: non-local means on sqrt(Y + c) plus an edge-stopping correction of the blotch-scale residue,
 * then a share of the removed noise is put back so the remaining grain is fine and even (a flat
 * spectrum like a GCam render) instead of a denoiser's cloudy mid-frequency residue.
 * Runs on the white-balanced linear image after {@link HighlightRecovery}.
 */
public final class NiceDenoise extends Node {
    public NiceDenoise() { super("", "NiceDenoise"); }
    @Override public void Compile() {}

    /** Developer switch (adb: touch <external files>/dump-denoise): the full-resolution denoise input and its parameters. */
    private void dumpInput(PostPipeline pipeline) { dumpTexture(pipeline, previousNode.WorkingTexture, "denoise-in"); }

    private void dumpTexture(PostPipeline pipeline, GLTexture source, String name) {
        java.io.File dir = com.particlesdevs.photoncamera.app.PhotonCamera.getAppContext().getExternalFilesDir(null);
        if (dir == null || !new java.io.File(dir, "dump-denoise").exists()) return;
        int[] oldRead = new int[1], framebuffer = new int[1];
        android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
        try (java.nio.channels.FileChannel out = new java.io.FileOutputStream(new java.io.File(dir, name + ".f32")).getChannel()) {
            android.opengl.GLES30.glGenFramebuffers(1, framebuffer, 0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, framebuffer[0]);
            android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_READ_FRAMEBUFFER, android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
                    android.opengl.GLES30.GL_TEXTURE_2D, source.mTextureID, 0);
            int w = source.mSize.x, h = source.mSize.y;
            java.nio.ByteBuffer row = java.nio.ByteBuffer.allocateDirect(w * 16).order(java.nio.ByteOrder.nativeOrder());
            java.nio.ByteBuffer packed = java.nio.ByteBuffer.allocateDirect(w * 12).order(java.nio.ByteOrder.nativeOrder());
            for (int y = 0; y < h; y++) {
                row.clear();
                android.opengl.GLES30.glReadPixels(0, y, w, 1, android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_FLOAT, row);
                packed.clear();
                for (int x = 0; x < w; x++) {
                    packed.putFloat(row.getFloat(x * 16)).putFloat(row.getFloat(x * 16 + 4)).putFloat(row.getFloat(x * 16 + 8));
                }
                packed.flip();
                while (packed.hasRemaining()) out.write(packed);
            }
            String json = "{\"w\":" + w + ",\"h\":" + h + ",\"iso\":" + pipeline.mParameters.iso
                    + ",\"whitePoint\":" + java.util.Arrays.toString(pipeline.mParameters.whitePoint)
                    + ",\"sensorToProPhoto\":" + java.util.Arrays.toString(pipeline.mParameters.sensorToProPhoto)
                    + ",\"intermediateToSRGB\":" + java.util.Arrays.toString(pipeline.mParameters.CCT.matrix) + "}";
            java.nio.file.Files.write(new java.io.File(dir, name + ".json").toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Log.i("NICE_PIPELINE", name + " dumped " + w + "x" + h);
        } catch (Exception e) {
            Log.e("NICE_PIPELINE", "denoise dump failed: " + e);
        } finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
        }
    }

    /** Noise level (sigma of u) from the flat, quiet blocks of the frame (25th percentile of the block statistics). */
    /**
     * Effective merged frames per pixel of this shot (uint8, 1/8 frame) as a texture for the filters;
     * null when the merge did not report it (the noise is then taken as uniform). effRef = its median.
     */
    private float effRef = 1f;
    private GLTexture loadEffectiveFrames(Point size) {
        java.io.File debugDir = com.particlesdevs.photoncamera.app.PhotonCamera.getAppContext().getExternalFilesDir(null);
        if (debugDir != null && new java.io.File(debugDir, "noeff").exists()) return null; // developer switch: uniform noise
        java.nio.ByteBuffer eff = VivoNiceBurst.lastEffectiveFrames;
        if (eff == null || eff.capacity() != size.x * size.y) return null;
        eff.rewind();
        int[] histogram = new int[256];
        long count = 0;
        for (int i = 0; i < eff.capacity(); i += 7) {
            int v = eff.get(i) & 255;
            if (v > 0) { histogram[v]++; count++; }
        }
        if (count == 0) return null;
        long seen = 0;
        int median = 1;
        for (int v = 1; v < 256; v++) { seen += histogram[v]; if (seen * 2 >= count) { median = v; break; } }
        effRef = Math.max(1f, median / 8f);
        eff.rewind();
        android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_UNPACK_ALIGNMENT, 1);
        return new GLTexture(size, new GLFormat(GLFormat.DataType.UNSIGNED_8, 1), eff, GL_NEAREST, GL_CLAMP_TO_EDGE);
    }

    private void bindEffectiveFrames(GLTexture effMap) {
        if (effMap != null) glProg.setTexture("EffMap", effMap);
        glProg.setVar("useEff", effMap != null ? 1 : 0);
        glProg.setVar("effRef", effRef);
    }

    private float estimateNoise(GLTexture noisy) {
        Point blocks = new Point((noisy.mSize.x + 7) / 8, (noisy.mSize.y + 7) / 8);
        GLTexture est = new GLTexture(blocks, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        int[] oldRead = new int[1], framebuffer = new int[1];
        try {
            glProg.useAssetProgram("chromadn/noiseest", false);
            glProg.setTexture("InputBuffer", noisy);
            glProg.drawBlocks(est);
            android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
            android.opengl.GLES30.glGenFramebuffers(1, framebuffer, 0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, framebuffer[0]);
            android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_READ_FRAMEBUFFER, android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
                    android.opengl.GLES30.GL_TEXTURE_2D, est.mTextureID, 0);
            java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(blocks.x * blocks.y * 16).order(java.nio.ByteOrder.nativeOrder());
            android.opengl.GLES30.glReadPixels(0, 0, blocks.x, blocks.y, android.opengl.GLES30.GL_RGBA, android.opengl.GLES30.GL_FLOAT, data);
            float[] values = new float[blocks.x * blocks.y];
            int n = 0;
            for (int i = 0; i < values.length; i++) {
                float v = data.getFloat(i * 16);
                if (v > 0f && !Float.isNaN(v)) values[n++] = v;
            }
            if (n == 0) return 0f;
            java.util.Arrays.sort(values, 0, n);
            return values[n / 4] / 0.8463f / (float) Math.sqrt(1.125);
        } catch (RuntimeException e) {
            return 0f;
        } finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
            est.close();
        }
    }

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        float chroma = Math.max(0f, Math.min(2f, PreferenceKeys.niceInternalValue("post_chroma", 1f)));
        float luma = Math.max(0f, Math.min(2f, PreferenceKeys.niceInternalValue("post_luma", 0.6f)));
        boolean despeckle = PreferenceKeys.isNiceDespeckleEnabled();
        if (chroma <= 0f && luma <= 0f && !despeckle) { glProg.closed = true; return; }
        long started = System.currentTimeMillis();
        dumpInput(pipeline);
        float offsetC = 0.008f;
        GLTexture original = previousNode.WorkingTexture;
        GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        GLFormat mono = new GLFormat(GLFormat.DataType.FLOAT_16, 1);
        Point half = new Point((original.mSize.x + 1) / 2, (original.mSize.y + 1) / 2);
        float noiseSigma = 0f;
        pipeline.niceNoiseSigma = 0f;
        GLTexture effMap = loadEffectiveFrames(original.mSize);
        GLTexture cleaned = null, before = null, ping = null, pong = null, noisy = null, clean = null, quarter = null, coarse = null;
        try {
            GLTexture input = original;
            noisy = new GLTexture(original.mSize, mono, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            clean = new GLTexture(original.mSize, mono, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            if (luma > 0f || despeckle || chroma > 0f) {
                glProg.useAssetProgram("chromadn/luma", false);
                glProg.setTexture("InputBuffer", original);
                glProg.setVar("offsetC", offsetC);
                glProg.drawBlocks(noisy);
                noiseSigma = estimateNoise(noisy);
                pipeline.niceNoiseSigma = noiseSigma;
            }
            if (despeckle) {
                cleaned = new GLTexture(original.mSize, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                glProg.useAssetProgram("chromadn/despeckle", false);
                glProg.setTexture("InputBuffer", original);
                glProg.setVar("sigma", noiseSigma);
                glProg.setVar("offsetC", offsetC);
                glProg.drawBlocks(cleaned);
                input = cleaned;
            }
            before = new GLTexture(half, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            ping = new GLTexture(half, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            pong = new GLTexture(half, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("chromadn/down", false);
            glProg.setTexture("InputBuffer", input);
            glProg.drawBlocks(before);
            GLTexture source = before;
            if (chroma > 0f) {
                GLTexture[] targets = {ping, pong, ping, pong};
                int[] steps = {1, 2};
                float tolerance = Math.max(1f, chroma);
                for (int pass = 0; pass < steps.length; pass++) {
                    glProg.useAssetProgram("chromadn/filter", false);
                    glProg.setTexture("InputBuffer", source);
                    glProg.setVar("step", steps[pass]);
                    glProg.setVar("strength", 1f);
                    glProg.setVar("tolerance", tolerance);
                    glProg.setVar("sigmaU", Math.max(noiseSigma, 0.0008f));
                    glProg.setVar("offsetC", offsetC);
                    glProg.drawBlocks(targets[pass]);
                    source = targets[pass];
                }
            }
            if (luma > 0f) {
                if (despeckle) {
                    glProg.useAssetProgram("chromadn/luma", false);
                    glProg.setTexture("InputBuffer", input);
                    glProg.setVar("offsetC", offsetC);
                    glProg.drawBlocks(noisy);
                }
                float sigma = noiseSigma;
                // Flat level: non-local means strong enough to remove the noise (h about three
                // sigma), then the blotch-scale residue at quarter resolution; a share of the
                // removed noise is put back in the last step so the grain stays fine and even.
                float h = luma * Math.max(0.0035f, 3f * sigma);
                glProg.useAssetProgram("chromadn/nlm", false);
                glProg.setTexture("InputBuffer", noisy);
                bindEffectiveFrames(effMap);
                glProg.setVar("h", h);
                glProg.drawBlocks(clean);
                Point quarterSize = new Point((original.mSize.x + 3) / 4, (original.mSize.y + 3) / 4);
                quarter = new GLTexture(quarterSize, mono, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                coarse = new GLTexture(quarterSize, mono, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                glProg.useAssetProgram("chromadn/down4", false);
                glProg.setTexture("InputBuffer", clean);
                glProg.drawBlocks(quarter);
                glProg.useAssetProgram("chromadn/coarse", false);
                glProg.setTexture("InputBuffer", quarter);
                glProg.setVar("tolerance", Math.max(0.004f, 3.5f * sigma));
                glProg.drawBlocks(coarse);
            }
            glProg.useAssetProgram("chromadn/apply", false);
            glProg.setTexture("InputBuffer", input);
            bindEffectiveFrames(effMap);
            glProg.setTexture("Before", before);
            glProg.setTexture("After", source);
            glProg.setTexture("Noisy", noisy);
            glProg.setTexture("Clean", luma > 0f ? clean : noisy);
            glProg.setTexture("Coarse", luma > 0f ? coarse : noisy);
            glProg.setVar("sigma", luma > 0f ? noiseSigma : 0f);
            // Share of the removed noise put back: the grain that stays is held near an absolute level
            // (about 0.0002 in u: the merge leaves about half the noise it did before its kernel followed the
            // SNR, and measured against an LMC render of the same scene this level gives the same noise in
            // flat and in textured areas), so a noisier frame (dim scene, high ISO) is cleaned harder, as a
            // GCam render does, instead of keeping a fixed fraction of its noise.
            glProg.setVar("grain", luma > 0f ? Math.max(PreferenceKeys.niceInternalValue("grain_min", 0.12f), Math.min(1f, PreferenceKeys.niceInternalValue("grain_level", 0.0002f) / (Math.max(noiseSigma, 1.0e-4f) * luma))) : 1f);
            glProg.setVar("offsetC", offsetC);
            glProg.setVar("lumaAmount", luma > 0f ? 1f : 0f);
            glProg.setVar("chromaAmount", chroma > 0f ? 1f : 0f);
            glProg.setVar("darkFade", 0.0008f, 0.003f);
            WorkingTexture = pipeline.getMain();
            glProg.drawBlocks(WorkingTexture);
            glProg.closed = true;
        } finally {
            for (GLTexture t : new GLTexture[]{cleaned, before, ping, pong, noisy, clean, quarter, coarse, effMap}) if (t != null) t.close();
        }
        dumpTexture(pipeline, WorkingTexture, "denoise-out");
        Log.i("NICE_PIPELINE", "denoise chroma=" + chroma + " luma=" + luma + " despeckle=" + despeckle + " sigma=" + noiseSigma
                + " ms=" + (System.currentTimeMillis() - started));
    }
}
