package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
import com.particlesdevs.photoncamera.processing.render.NoiseModeler;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * "SCAM Noise Reduction" of the SCAM Hybrid: the finish denoise of SCAM 9.6 as WhyCam 2.85 runs it, on the
 * white-balanced linear RGB after ScamRgb (the space where the reference denoises, before the colour matrix).
 * <ol>
 * <li>Despeckle (as the NLM route: isolated dots, also hot pixels the merge let through), then the YUV of the reference
 *     common.cl. On the Sabre 2x grid everything below runs at the SENSOR scale (luma through kD8, colour through
 *     {1,3,3,1}) and the change is put back with a Laplacian re-assembly: out = RGB_2x + RGB(Up2(Den0 - X0)).</li>
 * <li>Noise: the single-frame model of the base frame (Camera2 profile, white-balance gains) times the measured
 *     difference variance of every pyramid level and stride (the reference derives it from a white spectrum; our merge leaves
 *     correlated noise), rho = how much noisier the model WhyCam hands the reference is than the real one.</li>
 * <li>SNR of the finish input (EstimateSnr: mean 0.18 / display gain) selects and interpolates the tiers of
 *     {@link ScamDenoiseTables}.</li>
 * <li>Luma (the reference LumaDenoise F16): kD8 pyramid, 4 levels x {BilateralFilter3x3 stride 2, stride 1}, revert per band
 *     (not clamped in F16; here clamped to dn_revert_max), the 1-2-1 low pass of level 1 kept aside on level 0.</li>
 * <li>Chroma (the reference ChromaDenoise) on the luma-denoised YUV: {1,3,3,1} pyramid, 4 levels x 2 joint YUV bilateral 3x3
 *     passes, Y untouched; optional fade of the colour in the darkest pixels.</li>
 * </ol>
 * Runs for hybrid shots when pref_scam_hybrid_dn_engine = multiscale (default); {@link ScamHdrDenoise} delegates here and stays
 * the "nlm" engine and the SCAM HDR (SCAM) denoise.
 */
public final class ScamDenoise extends Node {
    public ScamDenoise() { super("", "ScamDenoise"); }
    @Override public void Compile() {}

    /**
     * P335 (owner, 2026-10-25: the grain «между арк и нашим»): the share of the luma noise the denoise removed on the finest scale
     * that comes back at the output (scam_dev hybrid_dn_luma_grain; 0 = the denoise as before).
     */
    static final float LUMA_GRAIN = 0.5f;

    /**
     * P369 (texture-double-grain, night shots): the luma grain put back under a film that adds grain (FilmGrain.addsGrain: a
     * format and «Зерно» above 0) - x (1 - the simulation's strength), none at 100 % (the film's own grain stands for it); the
     * value itself, bit for bit, without a film or with a film that adds none.
     */
    static float lumaGrainUnderFilm(float lumaGrain, @androidx.annotation.Nullable com.particlesdevs.photoncamera.processing.color.FilmSim.Settings film) {
        return film != null && FilmGrain.addsGrain(film) ? lumaGrain * (1f - film.strength) : lumaGrain;
    }

    /** Noise reduction engine of the hybrid: "multiscale" (this node, default) or "nlm" ({@link ScamHdrDenoise}). */
    static String engine() {
        String v = PreferenceKeys.hybridString("dn_engine", "multiscale");
        return "nlm".equals(v) ? "nlm" : "multiscale";
    }

    /** True for a shot merged by the SCAM Hybrid whose noise reduction engine is "multiscale". */
    public static boolean enabledFor(PostPipeline p) {
        return p != null && p.mParameters != null && p.mParameters.scamRgb != null
                && PreferenceKeys.isHybridShot() && "multiscale".equals(engine());
    }

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        if (!enabledFor(pipeline)) {
            // Added to a route directly while the engine is "nlm" (or not a hybrid shot): the NLM node does the work.
            ScamHdrDenoise nlm = new ScamHdrDenoise();
            nlm.basePipeline = basePipeline; nlm.glProg = glProg; nlm.previousNode = previousNode;
            nlm.glInt = glInt; nlm.glUtils = glUtils;
            nlm.runNlm();
            WorkingTexture = nlm.WorkingTexture;
            glProg.closed = true;
            return;
        }
        WorkingTexture = process(glProg, pipeline, previousNode.WorkingTexture);
        glProg.closed = true;
    }

    private static GLTexture tex(List<GLTexture> owned, Point size, GLFormat.DataType type, int channels, int filter) {
        GLTexture t = new GLTexture(size, new GLFormat(type, channels), null, filter, GL_CLAMP_TO_EDGE);
        owned.add(t);
        return t;
    }
    private static void release(List<GLTexture> owned, GLTexture t) {
        if (t != null && owned.remove(t)) t.close();
    }
    private static Point half(Point p) { return new Point((p.x + 1) / 2, (p.y + 1) / 2); }

    /** Settings of the hybrid section «Шумоподавление WHY / SCAM». */
    static ScamDenoiseTables.Config readConfig() {
        ScamDenoiseTables.Config c = new ScamDenoiseTables.Config();
        for (int i = 0; i < c.lumaSnr.length; i++) {
            String k = "dn_luma_t" + (i + 1) + "_";
            c.lumaSnr[i] = clamp(PreferenceKeys.hybridValue(k + "snr", c.lumaSnr[i]), 0.1f, 500f);
            c.lumaStrength[i] = PreferenceKeys.hybridList(k + "strength", c.lumaStrength[i]);
            c.lumaRevert[i] = PreferenceKeys.hybridList(k + "revert", c.lumaRevert[i]);
            c.lumaOutlier[i] = PreferenceKeys.hybridList(k + "outlier", c.lumaOutlier[i]);
        }
        for (int i = 0; i < c.chromaSnr.length; i++) {
            String k = "dn_chroma_t" + (i + 1) + "_";
            c.chromaSnr[i] = clamp(PreferenceKeys.hybridValue(k + "snr", c.chromaSnr[i]), 0.1f, 500f);
            c.chromaStrength[i] = PreferenceKeys.hybridList(k + "strength", c.chromaStrength[i]);
            c.chromaOutlier[i] = PreferenceKeys.hybridList(k + "outlier", c.chromaOutlier[i]);
        }
        c.lumaMult = clamp(PreferenceKeys.hybridValue("dn_luma_mult", 1f), 0f, 4f);
        c.gid14Mult = clamp(PreferenceKeys.hybridValue("dn_luma_gid14_mult", 1f), 0f, 4f);
        c.sabreMult = clamp(PreferenceKeys.hybridValue("dn_sabre_luma_mult", 1f), 0f, 4f);
        c.chromaMult = clamp(PreferenceKeys.hybridValue("dn_chroma_mult", 1f), 0f, 4f);
        c.chromaFloor = clamp(PreferenceKeys.hybridValue("dn_chroma_floor", 0f), 0f, 5f);
        c.revertMult = clamp(PreferenceKeys.hybridValue("dn_revert_mult", 1f), 0f, 4f);
        c.revertMax = clamp(PreferenceKeys.hybridValue("dn_revert_max", 1f), 0f, 9f);
        c.coarseStock = clamp(PreferenceKeys.hybridValue("dn_coarse_stock", 0f), 0f, 1f);
        return c;
    }
    private static float clamp(float v, float lo, float hi) { return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    /**
     * Linear display gain the tone stage will apply, mirrored from {@link LinearExposure} on block means of the frame
     * (p50/p90 of the white-balanced luminance): the denoise runs before it, the reference EstimateSnr needs it for the mean
     * signal 0.18 / gain. The mid / high anchors and the cap are the former LinearExposure defaults (their settings went
     * with the SCAMERA tone).
     */
    static float displayGain(float p50, float p90, boolean scamSoc) {
        final float mid = 0.050f, high = 0.180f, gainMax = 128f;
        p50 = Math.max(p50, 1.0e-5f); p90 = Math.max(p90, 1.0e-5f);
        float gain50 = mid / p50, gain90 = high / p90;
        float sceneGain = (float) Math.sqrt(Math.max(1f, gain50) * Math.max(1f, gain90));
        // P128: the former SCAM AE limit / bright-mid / high-limit settings had no row and no scam_dev line: their defaults
        boolean limited = scamSoc;
        final float brightMid = 0.25f;
        if (limited) {
            final float limit = 0.40f;
            float highGain = Math.max(1f, limit / p90);
            sceneGain = Math.min(Math.max(1f, gain50), highGain);
            if (brightMid > 0f && p50 >= mid) sceneGain = Math.max(sceneGain, Math.min(highGain, Math.max(1f, Math.min(2.2f, brightMid / p50))));
        } else if (brightMid > 0f) {
            sceneGain = Math.max(sceneGain, Math.max(1f, Math.min(2.2f, brightMid / p50)));
        }
        return Math.max(1f, Math.min(gainMax, sceneGain));
    }

    /**
     * P364: scam_dev.txt "post_sync 1" (PostGlMode.syncNodes): the GPU time of each stage of this node - a glFinish at every
     * stage end, logged as "scam-denoise laps ms". Off (every normal shot): nothing is synced or logged.
     */
    static final class Laps {
        final boolean on = com.particlesdevs.photoncamera.processing.opengl.PostGlMode.syncNodes();
        final StringBuilder text = new StringBuilder();
        private long at = System.nanoTime();

        void lap(String stage) {
            if (!on) return;
            android.opengl.GLES30.glFinish();
            final long now = System.nanoTime();
            text.append(' ').append(stage).append('=').append((now - at) / 1_000_000);
            at = now;
        }
    }

    /** Reads an RGBA32UI statistics texture back as 8 floats per block. */
    private static float[] readStats(GLTexture t) {
        int w = t.mSize.x, h = t.mSize.y;
        int[] oldRead = new int[1], framebuffer = new int[1];
        android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
        try {
            android.opengl.GLES30.glGenFramebuffers(1, framebuffer, 0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, framebuffer[0]);
            android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_READ_FRAMEBUFFER, android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
                    android.opengl.GLES30.GL_TEXTURE_2D, t.mTextureID, 0);
            ByteBuffer data = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder());
            android.opengl.GLES30.glReadPixels(0, 0, w, h, android.opengl.GLES30.GL_RGBA_INTEGER, android.opengl.GLES30.GL_UNSIGNED_INT, data);
            float[] out = new float[w * h * 8];
            for (int i = 0; i < w * h * 4; i++) {
                int v = data.getInt(i * 4);
                out[2 * i] = ScamDenoiseTables.half(v & 0xffff);
                out[2 * i + 1] = ScamDenoiseTables.half((v >>> 16) & 0xffff);
            }
            return out;
        } finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
        }
    }

    /** Block statistics of one level (scamdn/stats), reduced on the host; null when too few flat blocks (old model of post_ab). */
    private static float[] levelNoise(GLProg glProg, List<GLTexture> owned, GLTexture level, int block, float sY, float rY, float[] dcg,
                                      float[][] rawOut) {
        Point blocks = new Point(Math.max(1, level.mSize.x / block), Math.max(1, level.mSize.y / block));
        GLTexture st = tex(owned, blocks, GLFormat.DataType.UNSIGNED_32, 4, GL_NEAREST);
        try {
            statsProgram(glProg, dcg, sY, rY);
            glProg.setTexture("InputBuffer", level);
            glProg.setVar("blockU", block);
            glProg.drawBlocks(st);
            float[] raw = readStats(st);
            if (rawOut != null) rawOut[0] = raw;
            return ScamDenoiseTables.reduceLegacy(raw, blocks.x * blocks.y, 0.8f);
        } finally {
            release(owned, st);
        }
    }

    /**
     * scamdn/stats with the single-frame model (sY, rY); P320: a DCG shot ({@code dcg} = {rY_lcg / rY, knee in Y}, null for any
     * other) takes the DCG_ZONES variant, rY being the high-gain zone's and modelHiU the low-gain one's. P364: the atlas corner
     * atU is (0, 0) until levelNoiseBatch sets it (the program keeps a uniform between uses).
     */
    private static void statsProgram(GLProg glProg, float[] dcg, float sY, float rY) {
        if (dcg != null) glProg.setDefine("DCG_ZONES", 1);
        glProg.useAssetProgram("scamdn/stats", false);
        glProg.setVar("atU", 0, 0);
        glProg.setVar("modelU", sY, rY);
        if (dcg != null) {
            glProg.setVar("modelHiU", rY * dcg[0]);
            glProg.setVar("kneeU", dcg[1]);
        }
    }

    /**
     * W1.6: {@link #levelNoise} of several levels at once. Every statistics pass is drawn first, then they are read back
     * (one sync instead of one per level), and the levels are reduced in parallel with the primitive-key sorts of
     * {@link ScamDenoiseTables#reduce}. The same passes, read-backs and reductions, so the same gains; rawOut gets level 0.
     * <p>
     * P364: the passes write the rectangles of one RGBA32UI atlas ({@link #statsAtlas}; scamdn/stats atU = the level's corner,
     * the same block origins), read back by ONE glReadPixels (seven read-backs were seven GPU round trips behind the viewfinder's
     * work: statsRead 14-44 ms), widened by HalfFloat.TABLE (= ScamDenoiseTables.half) on the helpers beside the reductions.
     * {@code whileGpu} (the strength map: nothing in it reads the statistics) runs after the passes are queued, so its CPU part
     * (effective-frame histogram, upload) overlaps the GPU. (A Y-only variant of the statistics for the luma levels was the same
     * bits but 2.5 x slower on level 1 on the OPPO's Adreno 750, so every level keeps the full shader.)
     */
    private static float[][] levelNoiseBatch(GLProg glProg, List<GLTexture> owned, GLTexture[] levels, int block,
                                             float sY, float rY, float[] dcg, float[][] rawOut, Laps laps, Runnable whileGpu) {
        final int n = levels.length;
        final int[] bw = new int[n], bh = new int[n], ax = new int[n], ay = new int[n];
        for (int i = 0; i < n; i++) {
            bw[i] = Math.max(1, levels[i].mSize.x / block);
            bh[i] = Math.max(1, levels[i].mSize.y / block);
        }
        final int[] atlasSize = statsAtlas(bw, bh, ax, ay);
        final float[][] raws = new float[n][];
        final int[] texels;
        GLTexture atlas = null;
        try {
            atlas = tex(owned, new Point(atlasSize[0], atlasSize[1]), GLFormat.DataType.UNSIGNED_32, 4, GL_NEAREST);
            for (int i = 0; i < n; i++) {
                statsProgram(glProg, dcg, sY, rY);
                glProg.setTexture("InputBuffer", levels[i]);
                glProg.setVar("blockU", block);
                glProg.setVar("atU", ax[i], ay[i]);
                drawRegion(glProg, atlas, ax[i], ay[i], bw[i], bh[i]);
            }
            laps.lap("statsDraw");
            if (whileGpu != null) {
                whileGpu.run();
                laps.lap("strmap");
            }
            texels = readInts(atlas);
        } finally {
            release(owned, atlas);
        }
        laps.lap("statsRead");
        final float[][] out = new float[n][];
        com.particlesdevs.photoncamera.util.ParallelWork.forEach(n, i -> {
            raws[i] = statsOf(texels, atlasSize[0], ax[i], ay[i], bw[i], bh[i]);
            out[i] = ScamDenoiseTables.reduce(raws[i], bw[i] * bh[i], 0.8f);
        });
        if (rawOut != null) rawOut[0] = raws[0];
        laps.lap("statsReduce");
        return out;
    }

    /**
     * P364: shelf layout of the statistics atlas: rectangle 0 (level 0) alone on the first shelf, then the others left to right in
     * order, a new shelf when one would pass the width so far. Writes the corners into x / y; returns {width, height}.
     */
    static int[] statsAtlas(int[] w, int[] h, int[] x, int[] y) {
        int width = 0, shelfY = 0, shelfH = 0, at = 0;
        for (int i = 0; i < w.length; i++) {
            if (i > 0 && at + w[i] > width) { shelfY += shelfH; shelfH = 0; at = 0; }
            x[i] = at;
            y[i] = shelfY;
            at += w[i];
            shelfH = Math.max(shelfH, h[i]);
            width = Math.max(width, at);
        }
        return new int[]{width, shelfY + shelfH};
    }

    /**
     * P364: the statistics of one level from the atlas ints (4 per texel, rows from y = 0, {@code atlasWidth} texels a row): 8
     * floats per block in {@link #readStats}' layout, each half widened by HalfFloat.TABLE (bit for bit ScamDenoiseTables.half).
     */
    static float[] statsOf(int[] texels, int atlasWidth, int x0, int y0, int w, int h) {
        final float[] table = HalfFloat.TABLE;
        final float[] out = new float[w * h * 8];
        for (int y = 0; y < h; y++) {
            final int src = ((y0 + y) * atlasWidth + x0) * 4, dst = y * w * 8;
            for (int k = 0; k < w * 4; k++) {
                final int v = texels[src + k];
                out[dst + 2 * k] = table[v & 0xffff];
                out[dst + 2 * k + 1] = table[v >>> 16];
            }
        }
        return out;
    }

    /** P364: one pass into the rectangle (x, y, w, h) of target, rows tiled as drawBlocks tiles them (gl_FragCoord is target's). */
    private static void drawRegion(GLProg glProg, GLTexture target, int x, int y, int w, int h) {
        target.BufferLoad();
        final com.particlesdevs.photoncamera.processing.opengl.GLBlockDivider divider =
                new com.particlesdevs.photoncamera.processing.opengl.GLBlockDivider(h, com.particlesdevs.photoncamera.processing.opengl.GLDrawParams.TileSize);
        final int[] row = new int[2];
        while (divider.nextBlock(row)) {
            android.opengl.GLES30.glViewport(x, y + row[0], w, row[1]);
            glProg.draw();
        }
        android.opengl.GLES30.glFlush();
    }

    /** P364: an RGBA32UI texture read back whole as ints (4 per texel, rows from y = 0): one glReadPixels. */
    private static int[] readInts(GLTexture t) {
        int w = t.mSize.x, h = t.mSize.y;
        int[] oldRead = new int[1], framebuffer = new int[1];
        android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
        try {
            android.opengl.GLES30.glGenFramebuffers(1, framebuffer, 0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, framebuffer[0]);
            android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_READ_FRAMEBUFFER, android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
                    android.opengl.GLES30.GL_TEXTURE_2D, t.mTextureID, 0);
            ByteBuffer data = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder());
            android.opengl.GLES30.glReadPixels(0, 0, w, h, android.opengl.GLES30.GL_RGBA_INTEGER, android.opengl.GLES30.GL_UNSIGNED_INT, data);
            final int[] out = new int[w * h * 4];
            data.asIntBuffer().get(out);
            return out;
        } finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
        }
    }

    /** Histogram of every 7th code of the effective-frame map (codes 1..255); returns the number of non-zero samples. */
    static long effHistogram(ByteBuffer eff, int[] histogram) {
        // Bulk reads in chunks of a multiple of 7 bytes, so every chunk starts on a sampled code: the samples of eff.get(i)
        // for i = 0, 7, 14, ... (1.8 M buffer calls at 12 MP before).
        final int capacity = eff.capacity();
        final byte[] chunk = new byte[7 * 9362];
        final ByteBuffer src = eff.duplicate();
        long count = 0;
        for (int start = 0; start < capacity; start += chunk.length) {
            final int n = Math.min(chunk.length, capacity - start);
            src.clear();
            src.position(start);
            src.get(chunk, 0, n);
            for (int j = 0; j < n; j += 7) {
                int v = chunk[j] & 255;
                if (v > 0) { histogram[v]++; count++; }
            }
        }
        return count;
    }

    /** Effective-frame map of the worker as a texture (codes, 0 = unknown); median code in effRef[0]. Null when absent. */
    private static GLTexture loadEffectiveFrames(List<GLTexture> owned, Point size, float[] effRef) {
        ByteBuffer eff = ScamHybridBurst.lastEffectiveFrames;
        if (eff == null || eff.capacity() != size.x * size.y) return null;
        eff.rewind();
        int[] histogram = new int[256];
        long count = effHistogram(eff, histogram);
        if (count == 0) return null;
        long seen = 0;
        int median = 1;
        for (int v = 1; v < 256; v++) { seen += histogram[v]; if (seen * 2 >= count) { median = v; break; } }
        effRef[0] = median;
        eff.rewind();
        GLTexture t = effectiveFramesTexture(size, eff);
        owned.add(t);
        return t;
    }

    /**
     * The effective-frame map texture (GL_R8UI) of {@link #loadEffectiveFrames} and ScamHdrDenoise.
     * <p>
     * Until W3.5 its upload used GL_RED, which GL_R8UI does not accept (GL_INVALID_OPERATION, the "glTexSubImage2D glError
     * 0x502" of every shot): the map was cleared to zero (W1.1), every code read "unknown" and the strength map was 1.0
     * everywhere. W3.5 (owner, 2026-10-07) uploads the real codes (GL_RED_INTEGER / GL_UNSIGNED_BYTE); the shaders read them
     * ONE-SIDED (scamdn/strmap, chromadn/nlm, chromadn/apply: the noise factor is clamped to >= 1), so the map only
     * strengthens the denoise where fewer than the median frames merged (the frame edge, rejected motion) and every pixel
     * at or above the median keeps the strength of the cleared map exactly.
     * <p>
     * The cleared map (the behaviour before W3.5) is kept for scam_dev.txt "effmap_real 0", for the old run of post_ab (so
     * the A/B shows this change on one worker result) and when the driver rejects the integer upload.
     */
    static GLTexture effectiveFramesTexture(Point size, ByteBuffer eff) {
        android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_UNPACK_ALIGNMENT, 1);
        final GLFormat format = new GLFormat(GLFormat.DataType.UNSIGNED_8, 1);
        final GLTexture t = new GLTexture(size, format, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        final boolean legacy = com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy();
        final boolean real = !legacy && PreferenceKeys.scamDevSwitch("effmap_real", true);
        int uploadError = android.opengl.GLES30.GL_NO_ERROR;
        if (real) {
            // Errors left by earlier calls are dropped first, so only this upload decides.
            for (int i = 0; i < 8 && android.opengl.GLES30.glGetError() != android.opengl.GLES30.GL_NO_ERROR; i++) {
                // drain
            }
            eff.rewind();
            android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, t.mTextureID);
            android.opengl.GLES30.glTexSubImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0, 0, 0, size.x, size.y,
                    android.opengl.GLES30.GL_RED_INTEGER, android.opengl.GLES30.GL_UNSIGNED_BYTE, eff);
            uploadError = android.opengl.GLES30.glGetError();
            eff.rewind();
        }
        final boolean loaded = real && uploadError == android.opengl.GLES30.GL_NO_ERROR;
        if (!loaded) {
            t.BufferLoad();
            android.opengl.GLES30.glClearBufferuiv(android.opengl.GLES30.GL_COLOR, 0, new int[]{0, 0, 0, 0}, 0);
        }
        if (legacy) {
            Log.i("SCAM_PIPELINE", "EFFMAP old run of post_ab: map cleared (as before W3.5)");
        } else if (!effUploadLogged || !loaded) {
            effUploadLogged = true;
            Log.i("SCAM_PIPELINE", loaded ? "EFFMAP real codes uploaded (GL_RED_INTEGER), one-sided strength"
                    : real ? "EFFMAP integer upload rejected by the driver (0x" + Integer.toHexString(uploadError) + "): map cleared"
                    : "EFFMAP effmap_real 0: map cleared (as before W3.5)");
        }
        return t;
    }

    /** The successful upload of the effective-frame map is logged once per process (the driver does not change). */
    private static volatile boolean effUploadLogged;

    /**
     * P214d: the reference gain of {@link #lscTerm} sits this much above the map's smallest luminance gain: the GainMap
     * texture holds fp16 (2^-11 relative) and is sampled bilinearly, so the texels next to the minimum read g <= 1 and keep
     * f = 1 exactly instead of 1 + a rounding error.
     */
    static final float LSC_MARGIN = 1f + 1f / 512f;

    /**
     * P214d: default cap of the lens-shading variance multiplier: scam_dev "denoise_lsc_max". P330: 2 (sigma at most x1.41; was 4):
     * at f > 2 scamdn/lbf also cuts the revert to 0.71, so the corners lost about 30 % of a level that the centre puts back whole.
     */
    static final float LSC_CAP = 2f;

    /**
     * P214d: the luminance gain of one lens-shading site (r, g_even, g_odd, b): the mean of r, (g_even + g_odd) / 2 and b,
     * the factor why/low, why/combine and why/sharp_in multiply the denoised image by (scamrgb applies only the colour part,
     * normalised to mean 1).
     */
    static float lscGain(float r, float gEven, float gOdd, float b) {
        return (r + 0.5f * (gEven + gOdd) + b) / 3f;
    }

    /**
     * P214d: the lens-shading factor of scamdn/strmap for one (interpolated) site gain: min(g^2, cap) with g = gain / the
     * reference gain, 1 where g <= 1 or the gain is not a number (the Java mirror of the shader, for the tests and the log).
     */
    static float lscFactor(float gain, float invReference, float cap) {
        final float g = gain * invReference;
        return g > 1f ? Math.min(g * g, Math.max(cap, 1f)) : 1f;
    }

    /**
     * P214d: the lens-shading term of the strength map, from the map ScamRgb uploaded as the pipeline's GainMap
     * (Parameters.gainMap, {@code mw} x {@code mh} sites of (r, g_even, g_odd, b)).
     * <p>
     * Why: the denoise works before the WHY tone, which multiplies every pixel by the luminance gain of the map (corner
     * gains 2.0-3.8 in the phone logs, quality survey section 7): the noise the denoise leaves in a corner comes out
     * up to 3.8 x larger in amplitude than the same residue in the centre, on a signal that collected fewer photons. GCam 11
     * multiplies its strength map by (1, g, g^2) of the shading gain (GCAM11_PIPELINE.md in SCAMERA-PC/research/gcam11,
     * section 9 item 10a: "the corners with the amplified vignetting correction are denoised harder"). Here the
     * variance multiplier of scamdn/strmap is multiplied by min(g^2, cap), g = the site's luminance gain over the smallest
     * one of the map (times
     * {@link #LSC_MARGIN}): one-sided like the effective-frame factor, so the optical centre keeps f = 1 exactly and only the
     * shaded part of the frame is filtered harder (cap 4 = sigma at most x2: a corner is filtered as for twice its noise).
     * <p>
     * Not a noise-model correction: GCam denoises AFTER its gain map, and its (1, g, g^2) on the (shot, read) terms only
     * carries the model through that gain. Here only the colour part of the shading comes before the denoise, and the
     * model already reads the local, still unshaded signal (lbf / cbf: sigma^2 = f (noise.x Y + noise.y)): at f = 1 a
     * corner is filtered for the noise it has (GCam's behaviour). The g^2 on top is the deliberate part: a corner keeps
     * less of its noise, so after the WHY gain its grain comes closer to the centre's - at the cost of fine corner texture
     * near the noise level (the lbf revert also drops to 0.71 x where f > 2). Phone check: sigma of a flat wall / sky in
     * the centre and the corners, and the detail of a textured scene's corners, against "denoise_lsc 0".
     * <p>
     * Null (no term, the map as before) without a real map: no data, a 1 x 1 map (the identity of a HAL without one, or of
     * a RAW whose shading the HAL applied), a flat map, no valid site, or cap <= 1. Sites that are not a positive finite
     * number are skipped (the shader reads them as factor 1).
     *
     * @return {1 / reference gain, cap, the largest factor of the map (for the log)} or null
     */
    static float[] lscTerm(float[] gainMap, int mw, int mh, float cap) {
        if (gainMap == null || mw < 1 || mh < 1 || (long) mw * mh < 2 || gainMap.length < 4L * mw * mh) return null;
        if (!(cap > 1f) || Float.isInfinite(cap)) return null;
        float lo = Float.POSITIVE_INFINITY, hi = 0f;
        for (int i = 0, n = mw * mh; i < n; i++) {
            final float g = lscGain(gainMap[4 * i], gainMap[4 * i + 1], gainMap[4 * i + 2], gainMap[4 * i + 3]);
            if (!(g > 0f) || Float.isInfinite(g)) continue;
            lo = Math.min(lo, g);
            hi = Math.max(hi, g);
        }
        if (lo == Float.POSITIVE_INFINITY) return null;
        final float reference = lo * LSC_MARGIN;
        if (!(hi > reference)) return null; // flat: no shading to follow
        final float inv = 1f / reference;
        return new float[]{inv, cap, lscFactor(hi, inv, cap)};
    }

    /**
     * P214d: {@link #lscTerm} of this shot: scam_dev "denoise_lsc" (default 1) and "denoise_lsc_max" (the cap, default
     * {@link #LSC_CAP}, 1..16); off in the old run of post_ab (the A/B shows this change on one worker result) and without
     * the pipeline's GainMap texture.
     */
    static float[] lscTerm(PostPipeline pipeline) {
        if (com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy()) return null;
        if (!PreferenceKeys.scamDevSwitch("denoise_lsc", true)) return null;
        if (pipeline == null || pipeline.GainMap == null || pipeline.mParameters == null) return null;
        final Parameters par = pipeline.mParameters;
        if (par.mapSize == null) return null;
        final float cap = clamp(PreferenceKeys.scamDevNumber("denoise_lsc_max", LSC_CAP), 1f, 16f);
        // P277: a lab fragment's map is cropped: the reference of the whole map (LabFrame), a lab shot records it
        final LabFrame frozen = pipeline.frozen;
        if (frozen != null && (frozen.lsc != null || frozen.lscNone)) return frozen.lsc == null ? null : frozen.lsc.clone();
        final float[] term = lscTerm(par.gainMap, par.mapSize.x, par.mapSize.y, cap);
        if (pipeline.record != null) {
            pipeline.record.lsc = term == null ? null : term.clone();
            pipeline.record.lscNone = term == null;
        }
        return term;
    }

    /**
     * Dark fade of the denoise (scamdn/cbf, scamdn/final2x, chromadn/apply): the colour of pixels darker than mean RGB
     * 0.0008..0.003 fades to neutral, which hid the black-level tint, mostly the per-pixel clip bias of the clamped merge
     * RGB. On signed hybrid input that bias is gone and the fade greyed dark saturated colours (the teal curtain of the
     * OPPO shot 2026-10-07 at mean RGB ~0.0011 kept 18-54 % of its colour). There a colour whose deviation from neutral
     * |RGB - mean| exceeds what a black-level tint gives keeps it: {lo, hi} = where it starts to stay / stays fully, in
     * linear white-balanced RGB (default 1.5e-4 / 4e-4: a 0.05 DN black-level error tints a neutral black by ~1.5e-4).
     * scam_dev.txt "hybrid_dn_dark_chroma_hi 0" (or the old run of post_ab, or clamped input such as SCAM HDR) = the
     * fade of the luminance alone, as before; "hybrid_dn_dark_chroma_lo / _hi v" set the floor in units of 1e-4.
     */
    static float[] darkChroma(boolean signedInput) {
        if (!signedInput || com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy()) return new float[]{0f, 0f};
        final float hi = clamp(PreferenceKeys.hybridValue("dn_dark_chroma_hi", 4f), 0f, 100f) * 1e-4f;
        if (hi <= 0f) return new float[]{0f, 0f};
        final float lo = Math.min(clamp(PreferenceKeys.hybridValue("dn_dark_chroma_lo", 1.5f), 0f, 100f) * 1e-4f, 0.99f * hi);
        return new float[]{lo, hi};
    }

    /**
     * The keep floor of {@link #darkChroma} following the colour noise the denoise leaves (scamdn/cbf, scamdn/final2x
     * darkNoiseU): with the fixed floor alone, the colour noise of a neutral black at high ISO or with few frames
     * reached the floor and stayed as coloured blotches (host ScamDenoise port, OPPO ISO 6400 night scene and a dark chart:
     * neutral blotch +10..22 % against the former fade, the more the noisier). The floor becomes max(lo, k sigma) and
     * the ramp ends at max(hi, 2 k sigma), sigma^2 = GC[0][1] (sY mean + rY): the measured noise of the level-0 input
     * before the denoise (the stride-2 difference gain of Y at level 0 on the single-frame luma model, the term the chroma
     * filters scale by UVS), in linear white-balanced RGB variance like darkChroma; k = 0.35 (the residual colour noise is ~0.18 sigma: the floor sits at ~2 x the noise left). At the
     * noise of the 37-frame ISO 6400 shot of 2026-10-07 (measured G_C0 0.034-0.046) the fixed floor stays the larger
     * one (nothing changes); at 5 / 10 x that noise variance the blotches come back to within 3 % of the former fade's
     * while 92 % / 79 % of the dark scene colour is kept (former fade 56 % / 53 %, fixed floor alone 95 % / 86 % with
     * +18 / +22 % blotches). scam_dev.txt "hybrid_dn_dark_chroma_noise k" (0 = the fixed floor alone).
     * Returns {lo, hi, x, y}: darkChromaU = (lo, hi), darkNoiseU = (x, y), floor^2 = x mean + y.
     */
    static float[] darkKeep(float[] darkChroma, float gc0, float sY, float rY) {
        if (darkChroma == null || darkChroma[1] <= 0f) return new float[]{0f, 0f, 0f, 0f};
        final float k = clamp(PreferenceKeys.hybridValue("dn_dark_chroma_noise", 0.35f), 0f, 4f);
        final float g = Float.isFinite(gc0) ? Math.max(gc0, 0f) : 0f;
        return new float[]{darkChroma[0], darkChroma[1], k * k * g * sY, k * k * g * rY};
    }

    /**
     * P320: the measured two noise zones of a DCG shot (NoiseModeler.dcgZones of Parameters.dcgNoise), which replace the
     * camera's single curve here; null for any other shot, a DCG shot without usable zones, and the old run of post_ab (the A/B
     * shows this change on one worker result).
     */
    static float[] dcgZones(Parameters par) {
        if (par == null || !par.dcgShot || com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy()) return null;
        return NoiseModeler.dcgZones(par.dcgNoise);
    }

    /**
     * P320: the DCG_ZONES uniforms of scamdn stats / lbf / cbf, {rY_lcg / rY, knee in Y}; null (the plain shaders on the measured
     * single curve) for one measured zone (knee 0: GLSL smoothstep(0, 0, Y) is undefined) or a term that is not usable.
     */
    static float[] dcgShaderPass(float rY, float rYlcg, float kneeY) {
        if (!(kneeY > 0f) || !Float.isFinite(kneeY) || !(rY > 0f)) return null;
        final float ratio = rYlcg / rY;
        return Float.isFinite(ratio) ? new float[]{ratio, kneeY} : null;
    }

    /** Set once {@link #process} took the pipeline's output texture (a failure after it cannot fall back to another pass). */
    static volatile boolean lastMainTaken;

    /** The whole noise reduction; returns the output texture (a pipeline main texture). */
    /** P179: the input's storage went back during the last process (no NLM fallback can read it then). */
    static boolean lastInputReleased;

    static GLTexture process(GLProg glProg, PostPipeline pipeline, GLTexture input) {
        final long started = System.nanoTime();
        final Laps laps = new Laps();
        laps.lap("before"); // the GPU work queued by the nodes before this one
        lastMainTaken = false;
        lastInputReleased = false;
        final Parameters par = pipeline.mParameters;
        final Point full = input.mSize;
        // P306: a 200 MP shot is denoised on its 2x2 means (the Sabre 2x grid path: every pyramid at the 50 MP scale, 0.4 GB
        // textures instead of 1.6 GB); scamdn/final2x puts the result on the full grid and cores its pixel noise there
        // (a lab fragment of such a shot as the shot: its whole size, ScamLabRun)
        final Point whole = par.labWholeSize != null ? par.labWholeSize : full;
        final boolean huge = ScamHdrDenoise.huge(whole.x, whole.y);
        final int s = huge ? 2 : Math.max(1, Math.min(2, Math.round(par.outputScale)));
        final Point size0 = new Point((full.x + s - 1) / s, (full.y + s - 1) / s);
        final ScamDenoiseTables.Config cfg = readConfig();
        final float chromaNight = com.particlesdevs.photoncamera.settings.DeviceDefaults.chromaDenoiseFactor(par.iso);
        cfg.chromaMult *= chromaNight;
        final boolean despeckle = PreferenceKeys.hybridSwitch("despeckle", true);
        final boolean darkFade = PreferenceKeys.hybridSwitch("dn_dark_fade", true);
        final float[] darkChroma = darkChroma(pipeline.signedRgb);
        final float rhoS = clamp(PreferenceKeys.hybridValue("dn_model_shot", 1.2f), 0.25f, 4f);
        // P369 (texture-double-grain): with a film that adds its own grain, the digital luma grain put back only on the share the
        // simulation's strength leaves (the tiers of the denoise unchanged)
        final float lumaGrain = lumaGrainUnderFilm(clamp(PreferenceKeys.hybridValue("dn_luma_grain", LUMA_GRAIN), 0f, 1f), pipeline.film);
        // P330: 2 (was 6). G is the noise measured on the merge itself, so rho_r = 6 filtered the shadows for 2.45 x their real sigma
        // (strict threshold ~3.5-4 sigma, soft ~7-8): low-contrast shadow texture went with the noise
        final float rhoR = clamp(PreferenceKeys.hybridValue("dn_model_read", 2f), 0.25f, 16f);
        final float snrFixed = clamp(PreferenceKeys.hybridValue("dn_snr", 0f), 0f, 200f);
        final float snrScale = clamp(PreferenceKeys.hybridValue("dn_snr_scale", 1f), 0.25f, 8f);
        final String mapMode = PreferenceKeys.hybridString("dn_strength_map", "auto");
        final float effMax = clamp(PreferenceKeys.hybridValue("bento_denoise_max", 3f), 1f, 12f);
        // 2x grid (and a 200 MP shot, denoised on its 2x2 means: s = 2 above): share of the colour finer than the sensor scale
        // that is kept (0 = colour denoised as on the 1x grid).
        final float keep2x = clamp(PreferenceKeys.hybridValue("dn_chroma_2x_keep", 0f), 0f, 1f);
        // P306 (owner, 2026-10-20: «настрой по этому фото его»), tuned on the owner's 200 MP ship (5 frames, ISO 50; host replay of
        // the scamdn shaders on its merge): its finish SNR at the 2x2 means is ~97 (the top tier) and without an effective-frame map
        // (none above 100 MP, P187) every pixel counts as fully merged, where the tiers put the removed noise back (revert 1): the
        // denoise did nearly nothing. A flat strength map of 4.3 (the pixels as a quarter of the frames merged: noise x4.3, revert
        // halved) and the pixel noise cored at 2 sigma: high-pass noise of the sea 0.92 -> 0.58 %, the hull 1.36 -> 1.00 %, the
        // letters of 'NO SMOKING' still legible. scam_dev: hybrid_dn_huge_map (1 = as before this tuning), hybrid_dn_huge_fine (0 = off).
        final float hugeMap = huge ? clamp(PreferenceKeys.hybridValue("dn_huge_map", 4.3f), 1f, 9f) : 1f;
        final float hugeFine = huge ? clamp(PreferenceKeys.hybridValue("dn_huge_fine", 2f), 0f, 8f) : 0f;
        final List<GLTexture> owned = new ArrayList<>();
        final StringBuilder log = new StringBuilder("scam-denoise grid=").append(s).append('x');
        if (s == 2) log.append(" keep2x=").append(keep2x);
        long tPrep, tStats, tLuma;
        GLTexture output;
        try {
            // ---- noise model of one base frame in white-balanced RGB units: var_c = g_c S_c x + g_c^2 O_c
            final float[] wY = {0.2126f, 0.7152f, 0.0721996f};
            float[] S = {2e-4f, 2e-4f, 2e-4f}, O = {2e-6f, 2e-6f, 2e-6f};
            String modelSource = "fallback";
            // P320: a DCG shot's two measured noise zones (null for any other shot, without them, in the old run of post_ab)
            final float[] zones = dcgZones(par);
            if (par.dcgShot && zones == null && !com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy())
                Log.i("SCAM_PIPELINE", "scam-denoise DCG shot without measured noise zones (" + (par.dcgNoise == null ? "none"
                        : Arrays.toString(par.dcgNoise)) + "): the camera's single curve");
            if (par.noiseModeler != null && par.noiseModeler.baseModel != null && par.noiseModeler.baseModel[0] != null) {
                for (int c = 0; c < 3; c++) {
                    S[c] = (float) Math.max(1e-9, par.noiseModeler.baseModel[c].first);
                    O[c] = (float) Math.max(1e-12, par.noiseModeler.baseModel[c].second);
                }
                modelSource = "camera";
                // P77: the factor the worker measured on the burst and merged with (Redmi Note 11 Pro: HAL profile ~16 x too high);
                // P320: never on top of a DCG shot's measured zones (they are the burst's own noise)
                final float k = ScamHybridBurst.lastNoiseFactor;
                if (k != 1f && zones == null) {
                    for (int c = 0; c < 3; c++) { S[c] *= k; O[c] *= k; }
                    modelSource = "camera x" + k;
                }
            }
            // The merge's noise multipliers («Склейка: дробовая составляющая / шум считывания») no longer scale this model: the
            // denoise has its own ρs / ρr (dn_model_shot / dn_model_read) for the same job, and both pairs multiplied here (P310
            // settings cleanup; at their defaults 1 nothing changes).
            float sY = 0f, rY = 0f, sG = 0f, rG = 0f;
            for (int c = 0; c < 3; c++) {
                float wp = par.whitePoint != null && par.whitePoint.length > c && par.whitePoint[c] > 1e-6f ? par.whitePoint[c] : 1f;
                float g = 1f / wp;
                sY += wY[c] * wY[c] * g * S[c];
                rY += wY[c] * wY[c] * g * g * O[c];
                if (c == 1) { sG = g * S[c]; rG = g * g * O[c]; }
            }
            // P320: with DCG zones rY / rG are the high-gain zone's (below the knee: the shadows, the darkKeep floor) and
            // rYhi / rGhi the low-gain one's; dcgPass = {rYhi / rY, knee in Y} turns on DCG_ZONES in scamdn stats / lbf / cbf
            // (two zones only: one measured zone, knee 0, runs the plain shaders on its own single curve)
            float rYhi = rY, rGhi = rG, kneeY = 0f;
            float[] dcgPass = null;
            if (zones != null) {
                final float[] t = ScamDenoiseTables.dcgTerms(zones, S, O, par.whitePoint);
                sY = t[0]; rY = t[1]; rYhi = t[2]; sG = t[3]; rG = t[4]; rGhi = t[5]; kneeY = t[6];
                dcgPass = dcgShaderPass(rY, rYhi, kneeY);
                modelSource = "dcg(S=" + zones[0] + ",hcg=" + zones[1] + ",lcg=" + zones[2] + ",knee=" + zones[3]
                        + (modelSource.startsWith("camera") ? ",camera ratios" : "") + ")";
            }

            // ---- level 0 at the sensor scale (2x grid: kD8 / {1,3,3,1} down), despeckle, YUV
            // P306: the pixel noise of a 200 MP shot (sigma of u = sqrt(Y + c) on the full grid) for the coring in scamdn/final2x
            float sigmaFull = 0f;
            if (huge && hugeFine > 0f) {
                GLTexture noisyFull = tex(owned, full, GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
                glProg.useAssetProgram("chromadn/luma", false);
                glProg.setTexture("InputBuffer", input);
                glProg.setVar("offsetC", 0.008f);
                glProg.setVar("signedU", 0);
                glProg.drawBlocks(noisyFull);
                sigmaFull = ScamHdrDenoise.estimateNoise(glProg, noisyFull, 1);
                release(owned, noisyFull);
            }
            GLTexture base = input;
            if (s == 2) {
                base = tex(owned, size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                glProg.useAssetProgram("scamdn/down2x", false);
                glProg.setTexture("InputBuffer", input);
                glProg.drawBlocks(base);
            }
            final float offsetC = 0.008f;
            GLTexture noisy = tex(owned, size0, GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
            glProg.useAssetProgram("chromadn/luma", false);
            glProg.setTexture("InputBuffer", base);
            glProg.setVar("offsetC", offsetC);
            glProg.setVar("signedU", 0); // the noise estimate as before (the program may hold ScamHdrDenoise's value)
            glProg.drawBlocks(noisy);
            laps.lap("lumaU");
            final float sigmaU = ScamHdrDenoise.estimateNoise(glProg, noisy, 1);
            laps.lap("noiseEst");
            pipeline.scamNoiseSigma = sigmaU;
            release(owned, noisy);
            GLTexture clean = base;
            if (despeckle) {
                clean = tex(owned, size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                glProg.useAssetProgram("chromadn/despeckle", false);
                glProg.setTexture("InputBuffer", base);
                glProg.setVar("sigma", sigmaU);
                glProg.setVar("offsetC", offsetC);
                glProg.setVar("pxStepU", 1);
                glProg.drawBlocks(clean);
                laps.lap("despeckle");
            }
            GLTexture x0 = tex(owned, size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
            glProg.useAssetProgram("scamdn/yuv", false);
            glProg.setTexture("InputBuffer", clean);
            glProg.drawBlocks(x0);
            laps.lap("yuv");
            if (clean != base) release(owned, clean);
            if (s == 1) base = null; // the 1x output is built from the denoised YUV alone
            // P179 (200 MP: LOW_MEMORY in the pipeline): on the sensor grid nothing reads the input after x0 - above 100 MP its
            // storage (a ping-pong texture, 1.6 GB) goes back now and the pipeline makes it again on its next use
            if (s == 1 && (long) full.x * full.y > 100_000_000L && pipeline.releaseMain(input)) {
                lastInputReleased = true;
                log.append(" inputReleased");
            }

            // ---- pyramids: luma kD8 Y1..Y3, chroma {1,3,3,1} XC1..XC3 of the noisy YUV (noise statistics)
            GLTexture[] Y = new GLTexture[ScamDenoiseTables.LEVELS + 1];
            Y[0] = x0;
            // P364: no Y4 (it was made and never read: the luma levels run 3 .. 0, band 4 is level 3's stride-2 strength)
            for (int L = 1; L < ScamDenoiseTables.LEVELS; L++) {
                Y[L] = tex(owned, half(Y[L - 1].mSize), GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
                glProg.useAssetProgram("scamdn/down8", false);
                glProg.setTexture("InputBuffer", Y[L - 1]);
                glProg.drawBlocks(Y[L]);
            }
            GLTexture[] XC = new GLTexture[ScamDenoiseTables.LEVELS];
            XC[0] = x0;
            for (int L = 1; L < ScamDenoiseTables.LEVELS; L++) {
                XC[L] = tex(owned, half(XC[L - 1].mSize), GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                glProg.useAssetProgram("scamdn/down4", false);
                glProg.setTexture("InputBuffer", XC[L - 1]);
                glProg.drawBlocks(XC[L]);
            }
            laps.lap("pyramids");
            tPrep = System.nanoTime();

            // ---- strength map (P364: a Runnable, drawn by levelNoiseBatch while the GPU runs the statistics passes - it reads none
            // of them -, so the effective-frame histogram and upload no longer wait behind the statistics read-back; the same pass)
            final float[] effRef = {64f};
            final GLTexture[] strMapOut = {null};
            final String[] mapStateOut = {"uniform"};
            final Runnable strengthMap = () -> {
                if ("uniform".equals(mapMode)) return;
                GLTexture eff = loadEffectiveFrames(owned, full, effRef);
                // P214d: times min(g^2, cap) of the lens-shading gain the WHY tone applies after this denoise (lscTerm);
                // a map is made for it also when the worker sent no effective-frame map
                final float[] lsc = lscTerm(pipeline);
                if (eff != null || lsc != null || hugeMap > 1f) {
                    final GLTexture strMap = tex(owned, half(size0), GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
                    // Both samplers are always bound, each on a unit of its own: an unset one reads unit 0, where the
                    // other sampler type would sit (GL_INVALID_OPERATION at the draw). The 1 x 1 placeholders are never
                    // read (noEffU 1 / lscU.x 0).
                    final GLTexture effTex = eff != null ? eff
                            : tex(owned, new Point(1, 1), GLFormat.DataType.UNSIGNED_8, 1, GL_NEAREST);
                    final GLTexture gainTex = lsc != null ? pipeline.GainMap
                            : tex(owned, new Point(1, 1), GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                    glProg.useAssetProgram("scamdn/strmap", false);
                    glProg.setTexture("EffMap", effTex);
                    glProg.setTexture("GainMap", gainTex);
                    glProg.setVar("factorU", 2 * s);
                    glProg.setVar("effRefU", effRef[0]);
                    glProg.setVar("effMaxU", effMax);
                    glProg.setVar("noEffU", eff != null ? 0 : 1);
                    glProg.setVar("flatU", eff != null ? 0f : hugeMap); // P306: the 200 MP flat strength (unset 0: 1)
                    glProg.setVar("lscU", lsc != null ? lsc[0] : 0f, lsc != null ? lsc[1] : 0f);
                    glProg.setVar("fullInvU", 1f / full.x, 1f / full.y);
                    glProg.drawBlocks(strMap);
                    release(owned, effTex);
                    if (gainTex != pipeline.GainMap) release(owned, gainTex);
                    strMapOut[0] = strMap;
                    mapStateOut[0] = (eff != null ? "frames(ref=" + (int) effRef[0] + ",max=" + effMax + ")" : hugeMap > 1f ? "flat(" + hugeMap + ")" : "uniform")
                            + (lsc != null ? "+lsc(cap=" + lsc[1] + ",peak=" + round(lsc[2]) + ")" : "");
                } else if ("frames".equals(mapMode)) mapStateOut[0] = "uniform(no map)";
            };

            // ---- measured noise per level and stride (luma pyramid: Y; chroma pyramid: Y, U, V)
            float[][] gL = new float[ScamDenoiseTables.LEVELS][], gC = new float[ScamDenoiseTables.LEVELS][];
            float[][] raw0 = new float[1][];
            if (com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy()) {
                gL[0] = gC[0] = levelNoise(glProg, owned, x0, 16, sY, rY, dcgPass, raw0);
                for (int L = 1; L < ScamDenoiseTables.LEVELS; L++) {
                    gL[L] = levelNoise(glProg, owned, Y[L], 16, sY, rY, dcgPass, null);
                    gC[L] = levelNoise(glProg, owned, XC[L], 16, sY, rY, dcgPass, null);
                }
                strengthMap.run();
                laps.lap("strmap");
            } else {
                // The same levels in the same order: x0, then Y[L], XC[L] for L = 1..3.
                GLTexture[] levels = new GLTexture[2 * ScamDenoiseTables.LEVELS - 1];
                levels[0] = x0;
                for (int L = 1; L < ScamDenoiseTables.LEVELS; L++) { levels[2 * L - 1] = Y[L]; levels[2 * L] = XC[L]; }
                float[][] g = levelNoiseBatch(glProg, owned, levels, 16, sY, rY, dcgPass, raw0, laps, strengthMap);
                gL[0] = gC[0] = g[0];
                for (int L = 1; L < ScamDenoiseTables.LEVELS; L++) { gL[L] = g[2 * L - 1]; gC[L] = g[2 * L]; }
            }
            final GLTexture strMap = strMapOut[0];
            final String mapState = mapStateOut[0];
            for (int L = 1; L < ScamDenoiseTables.LEVELS; L++) release(owned, XC[L]);
            // Gains used by the filters: measured, else level 0 scaled by the kernel energy, else a white spectrum of ~4 frames.
            float[][] GY = new float[ScamDenoiseTables.LEVELS][2], GC = new float[ScamDenoiseTables.LEVELS][2], UVS = new float[ScamDenoiseTables.LEVELS][4];
            int measured = 0;
            for (int L = 0; L < ScamDenoiseTables.LEVELS; L++) {
                for (int k = 0; k < 2; k++) {
                    GY[L][k] = gL[L] != null ? gL[L][k] : gL[0] != null ? gL[0][k] * (float) Math.pow(0.19269013, L) : ScamDenoiseTables.whiteGain(L, false) / 4f;
                    GC[L][k] = gC[L] != null ? gC[L][k] : gC[0] != null ? gC[0][k] * (float) Math.pow(0.09765625, L) : ScamDenoiseTables.whiteGain(L, true) / 4f;
                }
                float[] src = gC[L] != null ? gC[L] : gC[0];
                for (int k = 0; k < 4; k++) {
                    // fallback: the reference sqrt(r_Y / r_ch) from the model
                    UVS[L][k] = src != null ? src[6 + k] : 1f;
                }
                if (gL[L] != null) measured++;
            }

            final float[] darkKeep = darkKeep(darkChroma, GC[0][1], sY, rY);
            // ---- SNR of the finish input
            float mu, gain = 1f, p50 = 0f, p90 = 0f, pDark = 0f;
            // P417: the dark colour tier's level: this quantile of the 16 px block means (0 = off)
            final float darkQ = clamp(PreferenceKeys.hybridValue("dn_dark_tier", 0.1f), 0f, 0.5f);
            if (raw0[0] != null) {
                float[] raw = raw0[0];
                int n = raw.length / 8, m = 0;
                float[] ys = new float[n];
                for (int i = 0; i < n; i++) if (raw[8 * i] >= 0f && Float.isFinite(raw[8 * i])) ys[m++] = raw[8 * i];
                if (m > 0) {
                    // P364: the two elements of the sorted means by selection (the sort of ~50 k floats ran on the GL thread
                    // while the GPU waited): the same values (Arrays.sort's order = Float.compare = ScamDenoiseTables.sortableBits)
                    p50 = ScamDenoiseTables.selectFloat(ys, m, Math.min(m - 1, m / 2));
                    p90 = ScamDenoiseTables.selectFloat(ys, m, Math.min(m - 1, (int) (m * 0.9f)));
                    if (darkQ > 0f) pDark = ScamDenoiseTables.selectFloat(ys, m, Math.min(m - 1, (int) (m * darkQ)));
                }
            }
            // P277: a lab fragment measures its SNR on the whole frame's brightness, a lab shot records it
            if (pipeline.frozen != null && pipeline.frozen.p50 > 0f) {
                p50 = pipeline.frozen.p50;
                p90 = pipeline.frozen.p90;
                pDark = pipeline.frozen.pDark > 0f && darkQ > 0f ? pipeline.frozen.pDark : 0f;
            }
            if (pipeline.record != null) {
                pipeline.record.p50 = p50;
                pipeline.record.p90 = p90;
                pipeline.record.pDark = pDark;
            }
            float rawScale = par.scamHdrMode ? Math.max(1e-6f, par.scamHdrRawScale) : 1f;
            if (p50 > 0f) {
                gain = displayGain(p50 / rawScale, p90 / rawScale, PreferenceKeys.isScamNetSoc());
                mu = 0.18f * rawScale / gain;
            } else {
                mu = 0.18f;
            }
            // P320: a DCG shot's SNR at mu takes the read term of mu's zone (high gain below the knee, blended over +-10 %)
            final float rGmu = zones != null ? NoiseModeler.zoneOffset(mu, rG, rGhi, kneeY) : rG;
            final float snrMeasured = ScamDenoiseTables.snr(mu, GY[0][0], sG, rGmu, rhoS, rhoR);
            final float snr = snrFixed > 0f ? snrFixed : snrMeasured * snrScale;
            par.finishSnr = snr; // P135: the WHY sharpening backs off on a noisy shot

            // ---- tiers
            ScamDenoiseTables.Pick[] lp = new ScamDenoiseTables.Pick[1], cp = new ScamDenoiseTables.Pick[1];
            final float[][] luma = ScamDenoiseTables.luma(cfg, snr, lp);
            // P335 (owner, 2026-10-25: «днём слишком сильный хрома шумодав»): the colour denoise eases off above SNR 20
            final float chromaDay = ScamDenoiseTables.dayChroma(snr,
                    clamp(PreferenceKeys.hybridValue("dn_chroma_day", ScamDenoiseTables.CHROMA_DAY), 0f, 1f));
            // P417 (owner, 2026-10-30, OPPO Find X8 Ultra: «На трубе снизу мелкие шумовые пятна»): the tier follows the SNR at mu, the
            // mid-tones (a dim room by a window: SNR 130, the day tier - colour 0.25 on levels 0 - 1, nothing coarser), and the deep
            // shadows kept their 4 - 8 px colour blots. A second, dark tier at the SNR of the pDark level (the darkest tenth of the
            // blocks), with the read term the worker measured there (lastNoiseDarkRead), filters the pixels at or below pDark at its
            // strength, fading to the day tier's at 4 x pDark (scamdn/cbf darkMixU); off when it is not stronger. scam_dev
            // "hybrid_dn_dark_tier 0" = before.
            final float darkRead = zones == null ? Math.max(1f, ScamHybridBurst.lastNoiseDarkRead) : 1f;
            float[][] chromaDark = null;
            float snrDark = 0f;
            final float dayAtHigh = clamp(PreferenceKeys.hybridValue("dn_chroma_day", ScamDenoiseTables.CHROMA_DAY), 0f, 1f);
            if (pDark > 0f && snrFixed <= 0f && pDark < 0.5f * mu) { // pDark: finish-input units, as mu
                snrDark = ScamDenoiseTables.snr(pDark, GY[0][0], sG, rGmu * darkRead, rhoS, rhoR) * snrScale;
                if (snrDark < snr) {
                    chromaDark = ScamDenoiseTables.chroma(cfg, snrDark, null);
                    final float dayDark = ScamDenoiseTables.dayChroma(snrDark, dayAtHigh);
                    for (float[] b : chromaDark) b[0] *= dayDark;
                }
            }
            cfg.chromaMult *= chromaDay;
            final float[][] chroma = ScamDenoiseTables.chroma(cfg, snr, cp);

            // ---- strength map: drawn with the statistics above (P364)
            tStats = System.nanoTime();

            // ---- luma: levels 3 .. 0
            boolean lumaOn = false;
            for (float[] b : luma) lumaOn |= b[0] > 0f;
            GLTexture x0p = x0;
            StringBuilder lumaLog = new StringBuilder();
            if (lumaOn) {
                GLTexture delta = null;
                for (int L = ScamDenoiseTables.LEVELS - 1; L >= 0; L--) {
                    final float str0 = luma[L + 1][0], str1 = luma[L][0];
                    final boolean f0 = str0 > 0f, f1 = str1 > 0f;
                    final Point sz = Y[L].mSize;
                    lumaLog.append(" L").append(L).append('=').append(round(str0)).append('/').append(round(str1)).append(",rf").append(round(luma[L][1]));
                    if (L >= 1 && !f0 && !f1 && delta == null) {
                        if (L == 1) {
                            // nothing filtered on levels 1..3: Den1 = Y1, only the low-pass hand-over
                            delta = lowPass(glProg, owned, Y[1], Y[1]);
                        }
                        continue;
                    }
                    GLTexture recon = Y[L];
                    if (delta != null) {
                        recon = tex(owned, sz, GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
                        glProg.useAssetProgram("scamdn/lrecon", false);
                        glProg.setTexture("Base", Y[L]);
                        glProg.setTexture("Delta", delta);
                        glProg.drawBlocks(recon);
                    }
                    final float k0 = str0 * str0 / 2f, k1 = str1 * str1;
                    final Point mapSize = sz;
                    GLTexture d0 = recon;
                    if (f0) {
                        d0 = tex(owned, sz, GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
                        lumaPass(glProg, d0, recon, recon, x0, L == 0, strMap, mapSize, 2, true,
                                k0 * GY[L][1] * rhoS * sY, k0 * GY[L][1] * rhoR * rY, dcgPass, luma[L + 1][2] * 16f, 0, 0f, 0, null, null, null);
                    }
                    GLTexture out;
                    if (L >= 2) {
                        out = tex(owned, sz, GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
                        lumaPass(glProg, out, d0, recon, x0, false, strMap, mapSize, 1, f1,
                                k1 * GY[L][0] * rhoS * sY, k1 * GY[L][0] * rhoR * rY, dcgPass, luma[L][2] * 16f, 1, luma[L][1], 1, Y[L], null, null);
                    } else if (L == 1) {
                        GLTexture den1 = tex(owned, sz, GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
                        lumaPass(glProg, den1, d0, recon, x0, false, strMap, mapSize, 1, f1,
                                k1 * GY[L][0] * rhoS * sY, k1 * GY[L][0] * rhoR * rY, dcgPass, luma[L][2] * 16f, 1, luma[L][1], 0, null, null, null);
                        if (d0 != recon) release(owned, d0);
                        if (recon != Y[L]) release(owned, recon);
                        d0 = recon = null;
                        out = lowPass(glProg, owned, den1, Y[1]);
                        release(owned, den1);
                    } else {
                        out = tex(owned, size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                        lumaPass(glProg, out, d0, recon, x0, true, strMap, mapSize, 1, f1,
                                k1 * GY[0][0] * rhoS * sY, k1 * GY[0][0] * rhoR * rY, dcgPass, luma[0][2] * 16f, 1, luma[0][1], 2, x0, delta, x0);
                    }
                    if (d0 != null && d0 != recon) release(owned, d0);
                    if (recon != null && recon != Y[L]) release(owned, recon);
                    release(owned, delta);
                    delta = out;
                }
                x0p = delta;
            }
            for (int L = 1; L <= ScamDenoiseTables.LEVELS; L++) release(owned, Y[L]);
            laps.lap("luma");
            tLuma = System.nanoTime();

            // ---- chroma on the luma-denoised YUV: levels 3 .. 0
            GLTexture[] XP = new GLTexture[ScamDenoiseTables.LEVELS];
            XP[0] = x0p;
            for (int L = 1; L < ScamDenoiseTables.LEVELS; L++) {
                XP[L] = tex(owned, half(XP[L - 1].mSize), GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                glProg.useAssetProgram("scamdn/down4", false);
                glProg.setTexture("InputBuffer", XP[L - 1]);
                glProg.drawBlocks(XP[L]);
            }
            // P335: the share of the finest-scale luma noise the denoise removed that comes back at the output (scamdn/lgrain)
            GLTexture grain = null;
            if (lumaOn && x0p != x0 && lumaGrain > 0f) {
                grain = tex(owned, size0, GLFormat.DataType.FLOAT_16, 1, GL_NEAREST);
                glProg.useAssetProgram("scamdn/lgrain", false);
                glProg.setTexture("Orig", x0);
                glProg.setTexture("Den", x0p);
                glProg.setVar("keepU", lumaGrain);
                glProg.drawBlocks(grain);
            }
            if (x0p != x0) release(owned, x0);
            GLTexture deltaUV = null;
            StringBuilder chromaLog = new StringBuilder();
            // P416 (owner, 2026-10-30, OPPO Find X8 Ultra: «шумную цветную полосу, она возникает на оппо х8у давно»): the merge's
            // outer ~6 px (colour blots and 1.4 - 2.4 x the colour noise along every edge of the replays) take the colour of the
            // band's inner border in the last colour pass (scamdn/cbf edgeBandU, level-0 pixels). scam_dev.txt
            // "hybrid_dn_edge_band 0" = before.
            final int edgeBand = (int) Math.ceil(clamp(PreferenceKeys.hybridValue("dn_edge_band", 6f), 0f, 32f) / s);
            output = null;
            // P417: the colour passes' read term with the deep shadows' measured factor while the dark tier runs
            final float rYc = chromaDark != null ? rY * darkRead : rY;
            for (int L = ScamDenoiseTables.LEVELS - 1; L >= 0; L--) {
                final float day0 = chroma[L + 1][0], day1 = chroma[L][0];
                final float dark0 = chromaDark != null ? chromaDark[L + 1][0] : 0f, dark1 = chromaDark != null ? chromaDark[L][0] : 0f;
                final float str0 = Math.max(day0, dark0), str1 = Math.max(day1, dark1);
                final boolean f0 = str0 > 0f, f1 = str1 > 0f;
                final GLTexture level = XP[L];
                final Point sz = level.mSize;
                chromaLog.append(" L").append(L).append('=').append(round(day0)).append('/').append(round(day1));
                if (chromaDark != null) chromaLog.append(",dark").append(round(dark0)).append('/').append(round(dark1));
                if (L >= 1 && !f0 && !f1 && deltaUV == null) continue;
                final float k0 = str0 * str0 / 2f, k1 = str1 * str1;
                // P417: sigma^2 x (day k / k) from 4 x pDark up, the dark tier's k at and below pDark (null: one tier)
                final float[] mix0 = dark0 > day0 ? new float[]{day0 * day0 / (str0 * str0), pDark, 4f * pDark} : null;
                final float[] mix1 = dark1 > day1 ? new float[]{day1 * day1 / (str1 * str1), pDark, 4f * pDark} : null;
                GLTexture c0 = level;
                if (deltaUV != null) {
                    // reconstruction X_L + (0, Up2(DenC_{L+1} - X_{L+1})) once per pixel (integer Up2), then the filters
                    c0 = tex(owned, sz, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                    chromaPass(glProg, c0, level, deltaUV, null, strMap, sz, 1, false, 0f, 0f, null, 1f, 1f, 0f, 0, false, 0f, null, null);
                    release(owned, deltaUV);
                    deltaUV = null;
                }
                if (f0) {
                    GLTexture c1 = tex(owned, sz, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                    chromaPass(glProg, c1, c0, null, null, strMap, sz, 2, true, k0 * GC[L][1] * rhoS * sY, k0 * GC[L][1] * rhoR * rYc, dcgPass,
                            UVS[L][1], UVS[L][3], (float) (int) chroma[L + 1][1], 0, false, 0f, null, null, 0, mix0);
                    if (c0 != level) release(owned, c0);
                    c0 = c1;
                }
                if (L == 0 && c0 != level) {
                    // the last level's outputs (RGB, or the 2x delta) never read X_0 again: free it before the last pass
                    release(owned, level);
                    XP[0] = null;
                }
                GLTexture out;
                if (L >= 1) {
                    out = tex(owned, sz, GLFormat.DataType.FLOAT_16, 2, GL_LINEAR);
                    chromaPass(glProg, out, c0, null, level, strMap, sz, 1, f1, k1 * GC[L][0] * rhoS * sY, k1 * GC[L][0] * rhoR * rYc, dcgPass,
                            UVS[L][0], UVS[L][2], (float) (int) chroma[L][1], 1, false, 0f, null, null, 0, mix1);
                } else if (s == 1) {
                    out = pipeline.getMain(); // the last write of this node
                    lastMainTaken = true;
                    chromaPass(glProg, out, c0, null, null, strMap, sz, 1, f1, k1 * GC[0][0] * rhoS * sY, k1 * GC[0][0] * rhoR * rYc, dcgPass,
                            UVS[0][0], UVS[0][2], (float) (int) chroma[0][1], 2, darkFade, 0f, darkKeep, grain, edgeBand, mix1);
                    output = out;
                } else {
                    // (Y change, UV(Den0) - keep UV(X0)): final2x upsamples this one texture
                    out = tex(owned, size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                    chromaPass(glProg, out, c0, null, base, strMap, sz, 1, f1, k1 * GC[0][0] * rhoS * sY, k1 * GC[0][0] * rhoR * rYc, dcgPass,
                            UVS[0][0], UVS[0][2], (float) (int) chroma[0][1], 3, false, keep2x, null, grain, edgeBand, mix1);
                    release(owned, base);
                    base = null;
                }
                if (c0 != level) release(owned, c0);
                deltaUV = out == output ? null : out;
            }
            if (s == 2) {
                for (int L = 0; L < ScamDenoiseTables.LEVELS; L++) release(owned, XP[L]);
                output = pipeline.getMain();
                lastMainTaken = true;
                glProg.useAssetProgram("scamdn/final2x", false);
                glProg.setTexture("InputBuffer", input);
                glProg.setTexture("Delta", deltaUV);
                glProg.setVar("keepU", keep2x);
                glProg.setVar("fadeU", darkFade ? 1 : 0);
                glProg.setVar("darkFadeU", 0.0008f, 0.003f);
                glProg.setVar("darkChromaU", darkKeep[0], darkKeep[1]);
                glProg.setVar("darkNoiseU", darkKeep[2], darkKeep[3]);
                glProg.setVar("fineU", huge ? hugeFine : 0f, sigmaFull); // P306: the 200 MP pixel-noise coring (0: none)
                glProg.drawBlocks(output);
                if (huge) log.append(" huge(2x2 means, fine=").append(hugeFine).append(" sigmaFull=").append(sigmaFull).append(')');
            }
            laps.lap("chroma");
            final long done = System.nanoTime();
            if (laps.on) Log.i("SCAM_PIPELINE", "scam-denoise laps ms:" + laps.text);
            log.append(" snr=").append(round(snr)).append(snrFixed > 0f ? "(fixed)" : "")
               .append(" mu=").append(mu).append(" gain=").append(round(gain)).append(" p50=").append(p50)
               .append(" g01=").append(round(GY[0][0])).append(" rho=").append(rhoS).append('/').append(rhoR)
               .append(" model=").append(modelSource).append(chromaNight != 1f ? " chromaNight=x" + chromaNight : "")
               .append(chromaDay != 1f ? " chromaDay=x" + round(chromaDay) : "").append(" sY=").append(sY).append(" rY=").append(rY)
               .append(zones != null ? " rYlcg=" + rYhi + " kneeY=" + kneeY + " rGmu=" + rGmu : "")
               .append(" luma ").append(lp[0]).append(" str=").append(col(luma, 0)).append(" rev=").append(col(luma, 1))
               .append(" out=").append(col(luma, 2)).append(" levels").append(lumaLog)
               .append(" chroma ").append(cp[0]).append(" str=").append(col(chroma, 0)).append(" out=").append(col(chroma, 1))
               .append(" levels").append(chromaLog)
               .append(" G=").append(gainsLog(GY, GC, UVS)).append(" measured=").append(measured).append("/4")
               .append(" map=").append(mapState).append(" edge=").append(edgeBand)
               .append(chromaDark != null ? " darkTier=snr" + round(snrDark) + "@" + pDark + ",read x" + round(darkRead) : "").append(" despeckle=").append(despeckle).append(" sigmaU=").append(sigmaU)
               .append(" darkFade=").append(!darkFade ? "off" : darkKeep[1] > 0f ? "keep " + darkKeep[0] + ".." + darkKeep[1]
                       + " noise " + darkKeep[2] + "/" + darkKeep[3] : "luma")
               .append(" ms=").append((done - started) / 1_000_000)
               .append(" (prep ").append((tPrep - started) / 1_000_000).append(", stats ").append((tStats - tPrep) / 1_000_000)
               .append(", luma ").append((tLuma - tStats) / 1_000_000).append(", chroma ").append((done - tLuma) / 1_000_000).append(')');
            Log.i("SCAM_PIPELINE", log.toString());
            return output;
        } finally {
            for (GLTexture t : owned) if (t != null) t.close();
            owned.clear();
        }
    }

    /**
     * P306: memory the 200 MP denoise asks to be free before it starts (bytes): on the 2x2 means its largest set of RGBA16F
     * textures is five half-size ones (the chroma pass of level 0: base, the luma result, two filter stages, the strength map and
     * pyramids beside them), plus the full-grid noise statistic (R16F), plus a quarter and 512 MB of margin.
     */
    static long hugeBytes(Point full) { return hugeBytes(full.x, full.y); }

    static long hugeBytes(int width, int height) {
        final long half = (long) ((width + 1) / 2) * ((height + 1) / 2);
        return (long) (1.25 * (5 * half * 8 + (long) width * height * 2)) + (512L << 20);
    }

    /** Level-1 hand-over (scamdn/llow): RG = (Den1 - Y1 - LP121(Den1), LP121(Den1)). */
    private static GLTexture lowPass(GLProg glProg, List<GLTexture> owned, GLTexture den1, GLTexture y1) {
        GLTexture out = tex(owned, y1.mSize, GLFormat.DataType.FLOAT_16, 2, GL_LINEAR);
        glProg.useAssetProgram("scamdn/llow", false);
        glProg.setTexture("InputBuffer", den1);
        glProg.setTexture("Base", y1);
        glProg.drawBlocks(out);
        return out;
    }

    private static void lumaPass(GLProg glProg, GLTexture target, GLTexture in, GLTexture recon, GLTexture yin, boolean useYin,
                                 GLTexture strMap, Point levelSize, int stride, boolean filter, float nx, float ny, float[] dcg, float thr,
                                 int stage, float rf, int mode, GLTexture base, GLTexture lowFreq, GLTexture chroma) {
        if (dcg != null) glProg.setDefine("DCG_ZONES", 1); // P320: dcg = {noiseHiU / noiseU.y, knee in Y} of a DCG shot
        glProg.useAssetProgram("scamdn/lbf", false);
        glProg.setTexture("InputBuffer", in);
        glProg.setTexture("Recon", recon);
        glProg.setTexture("Yin", yin);
        glProg.setTexture("Base", base != null ? base : in);
        glProg.setTexture("LowFreq", lowFreq != null ? lowFreq : in);
        glProg.setTexture("Chroma", chroma != null ? chroma : yin);
        glProg.setTexture("StrMap", strMap != null ? strMap : in);
        glProg.setVar("strideU", stride);
        glProg.setVar("filterU", filter ? 1 : 0);
        glProg.setVar("noiseU", nx, ny);
        if (dcg != null) {
            glProg.setVar("noiseHiU", ny * dcg[0]);
            glProg.setVar("kneeU", dcg[1]);
        }
        glProg.setVar("thrU", thr);
        glProg.setVar("stageU", stage);
        glProg.setVar("rfU", rf);
        glProg.setVar("modeU", mode);
        glProg.setVar("useYinU", useYin ? 1 : 0);
        glProg.setVar("useMapU", strMap != null ? 1 : 0);
        glProg.setVar("mapInvU", 1f / levelSize.x, 1f / levelSize.y);
        glProg.drawBlocks(target);
    }

    private static void chromaPass(GLProg glProg, GLTexture target, GLTexture in, GLTexture deltaUV, GLTexture orig, GLTexture strMap,
                                   Point levelSize, int stride, boolean filter, float nx, float ny, float[] dcg, float uvsU, float uvsV,
                                   float thr, int mode, boolean fade, float keep, float[] darkKeep, GLTexture grain) {
        chromaPass(glProg, target, in, deltaUV, orig, strMap, levelSize, stride, filter, nx, ny, dcg, uvsU, uvsV, thr, mode, fade, keep,
                darkKeep, grain, 0, null);
    }

    private static void chromaPass(GLProg glProg, GLTexture target, GLTexture in, GLTexture deltaUV, GLTexture orig, GLTexture strMap,
                                   Point levelSize, int stride, boolean filter, float nx, float ny, float[] dcg, float uvsU, float uvsV,
                                   float thr, int mode, boolean fade, float keep, float[] darkKeep, GLTexture grain, int edgeBand,
                                   float[] darkMix) {
        if (dcg != null) glProg.setDefine("DCG_ZONES", 1); // P320: dcg = {noiseHiU / noiseU.y, knee in Y} of a DCG shot
        glProg.useAssetProgram("scamdn/cbf", false);
        glProg.setTexture("InputBuffer", in);
        glProg.setTexture("DeltaUV", deltaUV != null ? deltaUV : in);
        glProg.setTexture("Orig", orig != null ? orig : in);
        glProg.setTexture("StrMap", strMap != null ? strMap : in);
        glProg.setTexture("Grain", grain != null ? grain : in);   // P335: modes 2 / 3 add it to Y (grainU 1)
        glProg.setVar("grainU", grain != null ? 1 : 0);
        glProg.setVar("strideU", stride);
        glProg.setVar("filterU", filter ? 1 : 0);
        glProg.setVar("useDeltaU", deltaUV != null ? 1 : 0);
        glProg.setVar("useMapU", strMap != null ? 1 : 0);
        glProg.setVar("noiseU", nx, ny);
        if (dcg != null) {
            glProg.setVar("noiseHiU", ny * dcg[0]);
            glProg.setVar("kneeU", dcg[1]);
        }
        glProg.setVar("uvsU", uvsU, uvsV);
        glProg.setVar("thrU", thr);
        glProg.setVar("mapInvU", 1f / levelSize.x, 1f / levelSize.y);
        glProg.setVar("modeU", mode);
        glProg.setVar("keepU", keep);
        glProg.setVar("fadeU", fade ? 1 : 0);
        glProg.setVar("darkFadeU", 0.0008f, 0.003f);
        glProg.setVar("darkChromaU", darkKeep != null ? darkKeep[0] : 0f, darkKeep != null ? darkKeep[1] : 0f);
        glProg.setVar("darkNoiseU", darkKeep != null ? darkKeep[2] : 0f, darkKeep != null ? darkKeep[3] : 0f);
        glProg.setVar("edgeBandU", edgeBand);
        glProg.setVar("darkMixU", darkMix != null ? darkMix[0] : 1f, darkMix != null ? darkMix[1] : 0f, darkMix != null ? darkMix[2] : 0f);
        glProg.drawBlocks(target);
    }

    private static float round(float v) { return Math.round(v * 100f) / 100f; }
    private static String col(float[][] t, int f) {
        float[] v = new float[t.length];
        for (int i = 0; i < t.length; i++) v[i] = t[i][f];
        return ScamDenoiseTables.fmt(v);
    }
    private static String gainsLog(float[][] gy, float[][] gc, float[][] uvs) {
        StringBuilder sb = new StringBuilder();
        for (int L = 0; L < gy.length; L++) {
            if (L > 0) sb.append(';');
            sb.append('L').append(L).append(':').append(round3(gy[L][0])).append(',').append(round3(gy[L][1]))
              .append('|').append(round3(gc[L][0])).append(',').append(round3(gc[L][1]))
              .append("|uvs ").append(round(uvs[L][0])).append(',').append(round(uvs[L][2]));
        }
        return sb.toString();
    }
    private static float round3(float v) { return Math.round(v * 1000f) / 1000f; }
}
