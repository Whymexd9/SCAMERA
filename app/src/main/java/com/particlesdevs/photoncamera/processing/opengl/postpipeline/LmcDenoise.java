package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Point;

import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.nodes.Node;
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
 * "LMC Noise Reduction" of the LMC hybrid: the finish denoise of LMC 9.6 (GCam) as ArkCam 2.85 runs it, on the
 * white-balanced linear RGB after {@link HighlightRecovery} (the space where GCam denoises, before the colour matrix).
 * <ol>
 * <li>Despeckle (as the NLM route: isolated dots, also hot pixels the merge let through), then the YUV of GCam's
 *     common.cl. On the Sabre 2x grid everything below runs at the SENSOR scale (luma through kD8, colour through
 *     {1,3,3,1}) and the change is put back with a Laplacian re-assembly: out = RGB_2x + RGB(Up2(Den0 - X0)).</li>
 * <li>Noise: the single-frame model of the base frame (Camera2 profile, white-balance gains) times the measured
 *     difference variance of every pyramid level and stride (GCam derives it from a white spectrum; our merge leaves
 *     correlated noise), rho = how much noisier the model ArkCam hands GCam is than the real one.</li>
 * <li>SNR of the finish input (EstimateSnr: mean 0.18 / display gain) selects and interpolates the tiers of
 *     {@link LmcDenoiseTables}.</li>
 * <li>Luma (GCam LumaDenoise F16): kD8 pyramid, 4 levels x {BilateralFilter3x3 stride 2, stride 1}, revert per band
 *     (not clamped in F16; here clamped to dn_revert_max), the 1-2-1 low pass of level 1 kept aside on level 0.</li>
 * <li>Chroma (GCam ChromaDenoise) on the luma-denoised YUV: {1,3,3,1} pyramid, 4 levels x 2 joint YUV bilateral 3x3
 *     passes, Y untouched; optional fade of the colour in the darkest pixels.</li>
 * </ol>
 * Runs for hybrid shots when pref_lmc_hybrid_dn_engine = gcam (default); {@link NiceDenoise} delegates here and stays
 * the "nlm" engine and the SCAM HDR (NICE) denoise.
 */
public final class LmcDenoise extends Node {
    public LmcDenoise() { super("", "LmcDenoise"); }
    @Override public void Compile() {}

    /** Noise reduction engine of the hybrid: "gcam" (this node, default) or "nlm" ({@link NiceDenoise}). */
    static String engine() {
        String v = PreferenceKeys.hybridString("dn_engine", "gcam");
        return "nlm".equals(v) ? "nlm" : "gcam";
    }

    /** True for a shot merged by the LMC hybrid whose noise reduction engine is "gcam". */
    public static boolean enabledFor(PostPipeline p) {
        return p != null && p.mParameters != null && p.mParameters.vivoNiceRgb != null
                && PreferenceKeys.isHybridShot() && "gcam".equals(engine());
    }

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        if (!enabledFor(pipeline)) {
            // Added to a route directly while the engine is "nlm" (or not a hybrid shot): the NLM node does the work.
            NiceDenoise nlm = new NiceDenoise();
            nlm.basePipeline = basePipeline; nlm.glProg = glProg; nlm.previousNode = previousNode;
            nlm.glInt = glInt; nlm.glUtils = glUtils; nlm.mProp = mProp;
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

    /** Settings of the hybrid section «Шумоподавление ARK / LMC». */
    static LmcDenoiseTables.Config readConfig() {
        LmcDenoiseTables.Config c = new LmcDenoiseTables.Config();
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
        c.revertMax = clamp(PreferenceKeys.hybridValue("dn_revert_max", 9f), 0f, 9f);
        c.coarseStock = clamp(PreferenceKeys.hybridValue("dn_coarse_stock", 0f), 0f, 1f);
        return c;
    }
    private static float clamp(float v, float lo, float hi) { return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    /**
     * Linear display gain the tone stage will apply, mirrored from {@link LinearExposure} on block means of the frame
     * (p50/p90 of the white-balanced luminance): the denoise runs before it, GCam's EstimateSnr needs it for the mean
     * signal 0.18 / gain. The mid / high anchors and the cap are the former LinearExposure defaults (their settings went
     * with the SCAMERA tone).
     */
    static float displayGain(float p50, float p90, boolean vivoSoc) {
        final float mid = 0.050f, high = 0.180f, gainMax = 128f;
        p50 = Math.max(p50, 1.0e-5f); p90 = Math.max(p90, 1.0e-5f);
        float gain50 = mid / p50, gain90 = high / p90;
        float sceneGain = (float) Math.sqrt(Math.max(1f, gain50) * Math.max(1f, gain90));
        boolean limited = vivoSoc && PreferenceKeys.profileNumber("pref_nice_ae_limit_mode", 1f, 0f, 1f) > 0f;
        float brightMid = PreferenceKeys.profileNumber("pref_nice_ae_bright_mid", 0.25f, 0f, 1f);
        if (limited) {
            float limit = PreferenceKeys.profileNumber("pref_nice_ae_high_limit", 0.40f, 0.05f, 1f);
            float highGain = Math.max(1f, limit / p90);
            sceneGain = Math.min(Math.max(1f, gain50), highGain);
            if (brightMid > 0f && p50 >= mid) sceneGain = Math.max(sceneGain, Math.min(highGain, Math.max(1f, Math.min(2.2f, brightMid / p50))));
        } else if (brightMid > 0f) {
            sceneGain = Math.max(sceneGain, Math.max(1f, Math.min(2.2f, brightMid / p50)));
        }
        return Math.max(1f, Math.min(gainMax, sceneGain));
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
                out[2 * i] = LmcDenoiseTables.half(v & 0xffff);
                out[2 * i + 1] = LmcDenoiseTables.half((v >>> 16) & 0xffff);
            }
            return out;
        } finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
        }
    }

    /** Block statistics of one level (lmcdn/stats), reduced on the host; null when too few flat blocks (old model of post_ab). */
    private static float[] levelNoise(GLProg glProg, List<GLTexture> owned, GLTexture level, int block, float sY, float rY, float[][] rawOut) {
        Point blocks = new Point(Math.max(1, level.mSize.x / block), Math.max(1, level.mSize.y / block));
        GLTexture st = tex(owned, blocks, GLFormat.DataType.UNSIGNED_32, 4, GL_NEAREST);
        try {
            glProg.useAssetProgram("lmcdn/stats", false);
            glProg.setTexture("InputBuffer", level);
            glProg.setVar("blockU", block);
            glProg.setVar("modelU", sY, rY);
            glProg.drawBlocks(st);
            float[] raw = readStats(st);
            if (rawOut != null) rawOut[0] = raw;
            return LmcDenoiseTables.reduceLegacy(raw, blocks.x * blocks.y, 0.8f);
        } finally {
            release(owned, st);
        }
    }

    /**
     * W1.6: {@link #levelNoise} of several levels at once. Every statistics pass is drawn first, then they are read back
     * (one sync instead of one per level), and the levels are reduced in parallel with the primitive-key sorts of
     * {@link LmcDenoiseTables#reduce}. The same passes, read-backs and reductions, so the same gains; rawOut gets level 0.
     */
    private static float[][] levelNoiseBatch(GLProg glProg, List<GLTexture> owned, GLTexture[] levels, int block, float sY, float rY,
                                             float[][] rawOut) {
        final int n = levels.length;
        final GLTexture[] st = new GLTexture[n];
        final int[] counts = new int[n];
        final float[][] raws = new float[n][];
        try {
            for (int i = 0; i < n; i++) {
                Point blocks = new Point(Math.max(1, levels[i].mSize.x / block), Math.max(1, levels[i].mSize.y / block));
                counts[i] = blocks.x * blocks.y;
                st[i] = tex(owned, blocks, GLFormat.DataType.UNSIGNED_32, 4, GL_NEAREST);
                glProg.useAssetProgram("lmcdn/stats", false);
                glProg.setTexture("InputBuffer", levels[i]);
                glProg.setVar("blockU", block);
                glProg.setVar("modelU", sY, rY);
                glProg.drawBlocks(st[i]);
            }
            for (int i = 0; i < n; i++) {
                raws[i] = readStats(st[i]);
                release(owned, st[i]);
                st[i] = null;
            }
        } finally {
            for (GLTexture t : st) release(owned, t);
        }
        if (rawOut != null) rawOut[0] = raws[0];
        final float[][] out = new float[n][];
        com.particlesdevs.photoncamera.util.ParallelWork.forEach(n, i -> out[i] = LmcDenoiseTables.reduce(raws[i], counts[i], 0.8f));
        return out;
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
        ByteBuffer eff = VivoNiceBurst.lastEffectiveFrames;
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
     * The effective-frame map texture (GL_R8UI) of {@link #loadEffectiveFrames} and NiceDenoise.
     * <p>
     * Its upload has always used GL_RED, which GL_R8UI does not accept (GL_INVALID_OPERATION, the "glTexSubImage2D glError
     * 0x502" of every shot): the map kept the storage the driver handed out, zero in practice, so every code read "unknown"
     * and the strength map was 1.0 everywhere. W1.1 (step 1) still makes that upload and, when the driver rejects it, clears
     * the map to zero explicitly: the same map, now deterministic once textures are released earlier or reused (freed GPU
     * memory must not leak into it). A driver that takes the upload kept the real codes before and keeps them now, so the
     * map is the old one on every driver. The real codes everywhere (GL_RED_INTEGER) would change the denoise and are an
     * owner decision (plan W3.5). The old model of post_ab keeps the failing upload and logs what it left in the texture.
     */
    static GLTexture effectiveFramesTexture(Point size, ByteBuffer eff) {
        android.opengl.GLES30.glPixelStorei(android.opengl.GLES30.GL_UNPACK_ALIGNMENT, 1);
        final GLFormat format = new GLFormat(GLFormat.DataType.UNSIGNED_8, 1);
        if (com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy()) {
            GLTexture t = new GLTexture(size, format, eff, GL_NEAREST, GL_CLAMP_TO_EDGE);
            final long checkStart = System.nanoTime();
            final long nonZero = countNonZero(t);
            // The check's read-back is part of the old run's time in the POST AB line: its own ms are logged here.
            Log.i("NICE_PIPELINE", "EFFMAP legacy upload left " + nonZero + " non-zero of " + (long) size.x * size.y + " texels"
                    + (nonZero == 0 ? " (all zero: the cleared map is the same)" : nonZero < 0 ? " (read-back failed)" : " (NOT all zero)")
                    + " check ms=" + (System.nanoTime() - checkStart) / 1_000_000);
            // nice_dev.txt "post_ab_effclear 1": the old run gets the new run's cleared map, so a POST AB difference that remains
            // is not the stale texels the failing upload left behind.
            if (nonZero != 0 && PreferenceKeys.niceDevSwitch("post_ab_effclear", false)) {
                t.BufferLoad();
                android.opengl.GLES30.glClearBufferuiv(android.opengl.GLES30.GL_COLOR, 0, new int[]{0, 0, 0, 0}, 0);
                Log.i("NICE_PIPELINE", "EFFMAP legacy map cleared for the A/B (post_ab_effclear)");
            }
            return t;
        }
        GLTexture t = new GLTexture(size, format, null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        // The upload of the old model (the constructor's own glTexSubImage2D), with its outcome read here: errors left by
        // earlier calls are dropped first, so only this upload decides.
        for (int i = 0; i < 8 && android.opengl.GLES30.glGetError() != android.opengl.GLES30.GL_NO_ERROR; i++) {
            // drain
        }
        t.loadData(eff);
        final int uploadError = android.opengl.GLES30.glGetError();
        if (uploadError != android.opengl.GLES30.GL_NO_ERROR) {
            t.BufferLoad();
            android.opengl.GLES30.glClearBufferuiv(android.opengl.GLES30.GL_COLOR, 0, new int[]{0, 0, 0, 0}, 0);
        }
        if (!effUploadLogged) {
            effUploadLogged = true;
            Log.i("NICE_PIPELINE", uploadError != android.opengl.GLES30.GL_NO_ERROR
                    ? "EFFMAP upload (GL_RED into GL_R8UI) rejected by the driver (0x" + Integer.toHexString(uploadError)
                    + ") as before: map cleared to zero (every code unknown)"
                    : "EFFMAP upload (GL_RED into GL_R8UI) taken by the driver: its codes stay in the map as before");
        }
        return t;
    }

    /** The outcome of the effective-frame map upload is logged once per process (the driver does not change). */
    private static volatile boolean effUploadLogged;

    /** Non-zero texels of an R8UI texture (read back in bands as RGBA_INTEGER / UNSIGNED_INT); -1 when the read-back fails. */
    private static long countNonZero(GLTexture t) {
        final int w = t.mSize.x, h = t.mSize.y;
        int[] oldRead = new int[1], framebuffer = new int[1];
        android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
        try {
            android.opengl.GLES30.glGenFramebuffers(1, framebuffer, 0);
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, framebuffer[0]);
            android.opengl.GLES30.glFramebufferTexture2D(android.opengl.GLES30.GL_READ_FRAMEBUFFER, android.opengl.GLES30.GL_COLOR_ATTACHMENT0,
                    android.opengl.GLES30.GL_TEXTURE_2D, t.mTextureID, 0);
            final int rows = Math.max(1, Math.min(h, (4 << 20) / Math.max(1, w * 16)));
            ByteBuffer data = ByteBuffer.allocateDirect(w * rows * 16).order(ByteOrder.nativeOrder());
            long nonZero = 0;
            for (int y = 0; y < h; y += rows) {
                final int n = Math.min(rows, h - y);
                data.clear();
                android.opengl.GLES30.glReadPixels(0, y, w, n, android.opengl.GLES30.GL_RGBA_INTEGER, android.opengl.GLES30.GL_UNSIGNED_INT, data);
                if (android.opengl.GLES30.glGetError() != android.opengl.GLES30.GL_NO_ERROR) return -1;
                for (int i = 0; i < w * n; i++) if (data.getInt(i * 16) != 0) nonZero++;
            }
            return nonZero;
        } finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
        }
    }

    /** Set once {@link #process} took the pipeline's output texture (a failure after it cannot fall back to another pass). */
    static volatile boolean lastMainTaken;

    /** The whole noise reduction; returns the output texture (a pipeline main texture). */
    static GLTexture process(GLProg glProg, PostPipeline pipeline, GLTexture input) {
        final long started = System.nanoTime();
        lastMainTaken = false;
        final Parameters par = pipeline.mParameters;
        final int s = Math.max(1, Math.min(2, Math.round(par.outputScale)));
        final Point full = input.mSize;
        final Point size0 = new Point((full.x + s - 1) / s, (full.y + s - 1) / s);
        final LmcDenoiseTables.Config cfg = readConfig();
        final boolean despeckle = PreferenceKeys.hybridSwitch("despeckle", true);
        final boolean darkFade = PreferenceKeys.hybridSwitch("dn_dark_fade", true);
        final float rhoS = clamp(PreferenceKeys.hybridValue("dn_model_shot", 1.2f), 0.25f, 4f);
        final float rhoR = clamp(PreferenceKeys.hybridValue("dn_model_read", 6f), 0.25f, 16f);
        final float snrFixed = clamp(PreferenceKeys.hybridValue("dn_snr", 0f), 0f, 200f);
        final float snrScale = clamp(PreferenceKeys.hybridValue("dn_snr_scale", 1f), 0.25f, 8f);
        final String mapMode = PreferenceKeys.hybridString("dn_strength_map", "auto");
        final float effMax = clamp(PreferenceKeys.hybridValue("bento_denoise_max", 3f), 1f, 12f);
        // 2x grid: share of the colour finer than the sensor scale that is kept (0 = colour denoised as on the 1x grid).
        final float keep2x = clamp(PreferenceKeys.hybridValue("dn_chroma_2x_keep", 0f), 0f, 1f);
        final List<GLTexture> owned = new ArrayList<>();
        final StringBuilder log = new StringBuilder("lmc-denoise grid=").append(s).append('x');
        if (s == 2) log.append(" keep2x=").append(keep2x);
        long tPrep, tStats, tLuma;
        GLTexture output;
        try {
            // ---- noise model of one base frame in white-balanced RGB units: var_c = g_c S_c x + g_c^2 O_c
            final float[] wY = {0.2126f, 0.7152f, 0.0721996f};
            float[] S = {2e-4f, 2e-4f, 2e-4f}, O = {2e-6f, 2e-6f, 2e-6f};
            String modelSource = "fallback";
            if (par.noiseModeler != null && par.noiseModeler.baseModel != null && par.noiseModeler.baseModel[0] != null) {
                for (int c = 0; c < 3; c++) {
                    S[c] = (float) Math.max(1e-9, par.noiseModeler.baseModel[c].first);
                    O[c] = (float) Math.max(1e-12, par.noiseModeler.baseModel[c].second);
                }
                modelSource = "camera";
            }
            final float photon = PreferenceKeys.hybridValue("noise_photon", 1f), readout = PreferenceKeys.hybridValue("noise_readout", 1f);
            float sY = 0f, rY = 0f, sG = 0f, rG = 0f;
            for (int c = 0; c < 3; c++) {
                float wp = par.whitePoint != null && par.whitePoint.length > c && par.whitePoint[c] > 1e-6f ? par.whitePoint[c] : 1f;
                float g = 1f / wp;
                sY += wY[c] * wY[c] * g * S[c] * photon;
                rY += wY[c] * wY[c] * g * g * O[c] * readout;
                if (c == 1) { sG = g * S[c] * photon; rG = g * g * O[c] * readout; }
            }

            // ---- level 0 at the sensor scale (2x grid: kD8 / {1,3,3,1} down), despeckle, YUV
            GLTexture base = input;
            if (s == 2) {
                base = tex(owned, size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                glProg.useAssetProgram("lmcdn/down2x", false);
                glProg.setTexture("InputBuffer", input);
                glProg.drawBlocks(base);
            }
            final float offsetC = 0.008f;
            GLTexture noisy = tex(owned, size0, GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
            glProg.useAssetProgram("chromadn/luma", false);
            glProg.setTexture("InputBuffer", base);
            glProg.setVar("offsetC", offsetC);
            glProg.drawBlocks(noisy);
            final float sigmaU = NiceDenoise.estimateNoise(glProg, noisy, 1);
            pipeline.niceNoiseSigma = sigmaU;
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
            }
            GLTexture x0 = tex(owned, size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
            glProg.useAssetProgram("lmcdn/yuv", false);
            glProg.setTexture("InputBuffer", clean);
            glProg.drawBlocks(x0);
            if (clean != base) release(owned, clean);
            if (s == 1) base = null; // the 1x output is built from the denoised YUV alone

            // ---- pyramids: luma kD8 Y1..Y4, chroma {1,3,3,1} XC1..XC3 of the noisy YUV (noise statistics)
            GLTexture[] Y = new GLTexture[LmcDenoiseTables.LEVELS + 1];
            Y[0] = x0;
            for (int L = 1; L <= LmcDenoiseTables.LEVELS; L++) {
                Y[L] = tex(owned, half(Y[L - 1].mSize), GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
                glProg.useAssetProgram("lmcdn/down8", false);
                glProg.setTexture("InputBuffer", Y[L - 1]);
                glProg.drawBlocks(Y[L]);
            }
            GLTexture[] XC = new GLTexture[LmcDenoiseTables.LEVELS];
            XC[0] = x0;
            for (int L = 1; L < LmcDenoiseTables.LEVELS; L++) {
                XC[L] = tex(owned, half(XC[L - 1].mSize), GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                glProg.useAssetProgram("lmcdn/down4", false);
                glProg.setTexture("InputBuffer", XC[L - 1]);
                glProg.drawBlocks(XC[L]);
            }
            tPrep = System.nanoTime();

            // ---- measured noise per level and stride (luma pyramid: Y; chroma pyramid: Y, U, V)
            float[][] gL = new float[LmcDenoiseTables.LEVELS][], gC = new float[LmcDenoiseTables.LEVELS][];
            float[][] raw0 = new float[1][];
            if (com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy()) {
                gL[0] = gC[0] = levelNoise(glProg, owned, x0, 16, sY, rY, raw0);
                for (int L = 1; L < LmcDenoiseTables.LEVELS; L++) {
                    gL[L] = levelNoise(glProg, owned, Y[L], 16, sY, rY, null);
                    gC[L] = levelNoise(glProg, owned, XC[L], 16, sY, rY, null);
                }
            } else {
                // The same levels in the same order: x0, then Y[L], XC[L] for L = 1..3.
                GLTexture[] levels = new GLTexture[2 * LmcDenoiseTables.LEVELS - 1];
                levels[0] = x0;
                for (int L = 1; L < LmcDenoiseTables.LEVELS; L++) { levels[2 * L - 1] = Y[L]; levels[2 * L] = XC[L]; }
                float[][] g = levelNoiseBatch(glProg, owned, levels, 16, sY, rY, raw0);
                gL[0] = gC[0] = g[0];
                for (int L = 1; L < LmcDenoiseTables.LEVELS; L++) { gL[L] = g[2 * L - 1]; gC[L] = g[2 * L]; }
            }
            for (int L = 1; L < LmcDenoiseTables.LEVELS; L++) release(owned, XC[L]);
            // Gains used by the filters: measured, else level 0 scaled by the kernel energy, else a white spectrum of ~4 frames.
            float[][] GY = new float[LmcDenoiseTables.LEVELS][2], GC = new float[LmcDenoiseTables.LEVELS][2], UVS = new float[LmcDenoiseTables.LEVELS][4];
            int measured = 0;
            for (int L = 0; L < LmcDenoiseTables.LEVELS; L++) {
                for (int k = 0; k < 2; k++) {
                    GY[L][k] = gL[L] != null ? gL[L][k] : gL[0] != null ? gL[0][k] * (float) Math.pow(0.19269013, L) : LmcDenoiseTables.whiteGain(L, false) / 4f;
                    GC[L][k] = gC[L] != null ? gC[L][k] : gC[0] != null ? gC[0][k] * (float) Math.pow(0.09765625, L) : LmcDenoiseTables.whiteGain(L, true) / 4f;
                }
                float[] src = gC[L] != null ? gC[L] : gC[0];
                for (int k = 0; k < 4; k++) {
                    // fallback: GCam's sqrt(r_Y / r_ch) from the model
                    UVS[L][k] = src != null ? src[6 + k] : 1f;
                }
                if (gL[L] != null) measured++;
            }

            // ---- SNR of the finish input
            float mu, gain = 1f, p50 = 0f, p90 = 0f;
            if (raw0[0] != null) {
                float[] raw = raw0[0];
                int n = raw.length / 8, m = 0;
                float[] ys = new float[n];
                for (int i = 0; i < n; i++) if (raw[8 * i] >= 0f && Float.isFinite(raw[8 * i])) ys[m++] = raw[8 * i];
                if (m > 0) {
                    Arrays.sort(ys, 0, m);
                    p50 = ys[Math.min(m - 1, m / 2)];
                    p90 = ys[Math.min(m - 1, (int) (m * 0.9f))];
                }
            }
            float rawScale = par.vivoHdrMode ? Math.max(1e-6f, par.vivoHdrRawScale) : 1f;
            if (p50 > 0f) {
                gain = displayGain(p50 / rawScale, p90 / rawScale, PreferenceKeys.isVivoNetSoc());
                mu = 0.18f * rawScale / gain;
            } else {
                mu = 0.18f;
            }
            final float snrMeasured = LmcDenoiseTables.snr(mu, GY[0][0], sG, rG, rhoS, rhoR);
            final float snr = snrFixed > 0f ? snrFixed : snrMeasured * snrScale;

            // ---- tiers
            LmcDenoiseTables.Pick[] lp = new LmcDenoiseTables.Pick[1], cp = new LmcDenoiseTables.Pick[1];
            final float[][] luma = LmcDenoiseTables.luma(cfg, snr, lp);
            final float[][] chroma = LmcDenoiseTables.chroma(cfg, snr, cp);

            // ---- strength map
            float[] effRef = {64f};
            GLTexture strMap = null;
            boolean wantMap = !"uniform".equals(mapMode);
            String mapState = "uniform";
            if (wantMap) {
                GLTexture eff = loadEffectiveFrames(owned, full, effRef);
                if (eff != null) {
                    strMap = tex(owned, half(size0), GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
                    glProg.useAssetProgram("lmcdn/strmap", false);
                    glProg.setTexture("EffMap", eff);
                    glProg.setVar("factorU", 2 * s);
                    glProg.setVar("effRefU", effRef[0]);
                    glProg.setVar("effMaxU", effMax);
                    glProg.drawBlocks(strMap);
                    release(owned, eff);
                    mapState = "frames(ref=" + (int) effRef[0] + ",max=" + effMax + ")";
                } else if ("frames".equals(mapMode)) mapState = "uniform(no map)";
            }
            tStats = System.nanoTime();

            // ---- luma: levels 3 .. 0
            boolean lumaOn = false;
            for (float[] b : luma) lumaOn |= b[0] > 0f;
            GLTexture x0p = x0;
            StringBuilder lumaLog = new StringBuilder();
            if (lumaOn) {
                GLTexture delta = null;
                for (int L = LmcDenoiseTables.LEVELS - 1; L >= 0; L--) {
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
                        glProg.useAssetProgram("lmcdn/lrecon", false);
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
                                k0 * GY[L][1] * rhoS * sY, k0 * GY[L][1] * rhoR * rY, luma[L + 1][2] * 16f, 0, 0f, 0, null, null, null);
                    }
                    GLTexture out;
                    if (L >= 2) {
                        out = tex(owned, sz, GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
                        lumaPass(glProg, out, d0, recon, x0, false, strMap, mapSize, 1, f1,
                                k1 * GY[L][0] * rhoS * sY, k1 * GY[L][0] * rhoR * rY, luma[L][2] * 16f, 1, luma[L][1], 1, Y[L], null, null);
                    } else if (L == 1) {
                        GLTexture den1 = tex(owned, sz, GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
                        lumaPass(glProg, den1, d0, recon, x0, false, strMap, mapSize, 1, f1,
                                k1 * GY[L][0] * rhoS * sY, k1 * GY[L][0] * rhoR * rY, luma[L][2] * 16f, 1, luma[L][1], 0, null, null, null);
                        if (d0 != recon) release(owned, d0);
                        if (recon != Y[L]) release(owned, recon);
                        d0 = recon = null;
                        out = lowPass(glProg, owned, den1, Y[1]);
                        release(owned, den1);
                    } else {
                        out = tex(owned, size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                        lumaPass(glProg, out, d0, recon, x0, true, strMap, mapSize, 1, f1,
                                k1 * GY[0][0] * rhoS * sY, k1 * GY[0][0] * rhoR * rY, luma[0][2] * 16f, 1, luma[0][1], 2, x0, delta, x0);
                    }
                    if (d0 != null && d0 != recon) release(owned, d0);
                    if (recon != null && recon != Y[L]) release(owned, recon);
                    release(owned, delta);
                    delta = out;
                }
                x0p = delta;
            }
            for (int L = 1; L <= LmcDenoiseTables.LEVELS; L++) release(owned, Y[L]);
            tLuma = System.nanoTime();

            // ---- chroma on the luma-denoised YUV: levels 3 .. 0
            GLTexture[] XP = new GLTexture[LmcDenoiseTables.LEVELS];
            XP[0] = x0p;
            for (int L = 1; L < LmcDenoiseTables.LEVELS; L++) {
                XP[L] = tex(owned, half(XP[L - 1].mSize), GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                glProg.useAssetProgram("lmcdn/down4", false);
                glProg.setTexture("InputBuffer", XP[L - 1]);
                glProg.drawBlocks(XP[L]);
            }
            if (x0p != x0) release(owned, x0);
            GLTexture deltaUV = null;
            StringBuilder chromaLog = new StringBuilder();
            output = null;
            for (int L = LmcDenoiseTables.LEVELS - 1; L >= 0; L--) {
                final float str0 = chroma[L + 1][0], str1 = chroma[L][0];
                final boolean f0 = str0 > 0f, f1 = str1 > 0f;
                final GLTexture level = XP[L];
                final Point sz = level.mSize;
                chromaLog.append(" L").append(L).append('=').append(round(str0)).append('/').append(round(str1));
                if (L >= 1 && !f0 && !f1 && deltaUV == null) continue;
                final float k0 = str0 * str0 / 2f, k1 = str1 * str1;
                GLTexture c0 = level;
                if (deltaUV != null) {
                    // reconstruction X_L + (0, Up2(DenC_{L+1} - X_{L+1})) once per pixel (integer Up2), then the filters
                    c0 = tex(owned, sz, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                    chromaPass(glProg, c0, level, deltaUV, null, strMap, sz, 1, false, 0f, 0f, 1f, 1f, 0f, 0, false, 0f);
                    release(owned, deltaUV);
                    deltaUV = null;
                }
                if (f0) {
                    GLTexture c1 = tex(owned, sz, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                    chromaPass(glProg, c1, c0, null, null, strMap, sz, 2, true, k0 * GC[L][1] * rhoS * sY, k0 * GC[L][1] * rhoR * rY,
                            UVS[L][1], UVS[L][3], (float) (int) chroma[L + 1][1], 0, false, 0f);
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
                    chromaPass(glProg, out, c0, null, level, strMap, sz, 1, f1, k1 * GC[L][0] * rhoS * sY, k1 * GC[L][0] * rhoR * rY,
                            UVS[L][0], UVS[L][2], (float) (int) chroma[L][1], 1, false, 0f);
                } else if (s == 1) {
                    out = pipeline.getMain(); // the last write of this node
                    lastMainTaken = true;
                    chromaPass(glProg, out, c0, null, null, strMap, sz, 1, f1, k1 * GC[0][0] * rhoS * sY, k1 * GC[0][0] * rhoR * rY,
                            UVS[0][0], UVS[0][2], (float) (int) chroma[0][1], 2, darkFade, 0f);
                    output = out;
                } else {
                    // (Y change, UV(Den0) - keep UV(X0)): final2x upsamples this one texture
                    out = tex(owned, size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                    chromaPass(glProg, out, c0, null, base, strMap, sz, 1, f1, k1 * GC[0][0] * rhoS * sY, k1 * GC[0][0] * rhoR * rY,
                            UVS[0][0], UVS[0][2], (float) (int) chroma[0][1], 3, false, keep2x);
                    release(owned, base);
                    base = null;
                }
                if (c0 != level) release(owned, c0);
                deltaUV = out == output ? null : out;
            }
            if (s == 2) {
                for (int L = 0; L < LmcDenoiseTables.LEVELS; L++) release(owned, XP[L]);
                output = pipeline.getMain();
                lastMainTaken = true;
                glProg.useAssetProgram("lmcdn/final2x", false);
                glProg.setTexture("InputBuffer", input);
                glProg.setTexture("Delta", deltaUV);
                glProg.setVar("keepU", keep2x);
                glProg.setVar("fadeU", darkFade ? 1 : 0);
                glProg.setVar("darkFadeU", 0.0008f, 0.003f);
                glProg.drawBlocks(output);
            }
            final long done = System.nanoTime();
            log.append(" snr=").append(round(snr)).append(snrFixed > 0f ? "(fixed)" : "")
               .append(" mu=").append(mu).append(" gain=").append(round(gain)).append(" p50=").append(p50)
               .append(" g01=").append(round(GY[0][0])).append(" rho=").append(rhoS).append('/').append(rhoR)
               .append(" model=").append(modelSource).append(" sY=").append(sY).append(" rY=").append(rY)
               .append(" luma ").append(lp[0]).append(" str=").append(col(luma, 0)).append(" rev=").append(col(luma, 1))
               .append(" out=").append(col(luma, 2)).append(" levels").append(lumaLog)
               .append(" chroma ").append(cp[0]).append(" str=").append(col(chroma, 0)).append(" out=").append(col(chroma, 1))
               .append(" levels").append(chromaLog)
               .append(" G=").append(gainsLog(GY, GC, UVS)).append(" measured=").append(measured).append("/4")
               .append(" map=").append(mapState).append(" despeckle=").append(despeckle).append(" sigmaU=").append(sigmaU)
               .append(" ms=").append((done - started) / 1_000_000)
               .append(" (prep ").append((tPrep - started) / 1_000_000).append(", stats ").append((tStats - tPrep) / 1_000_000)
               .append(", luma ").append((tLuma - tStats) / 1_000_000).append(", chroma ").append((done - tLuma) / 1_000_000).append(')');
            Log.i("NICE_PIPELINE", log.toString());
            return output;
        } finally {
            for (GLTexture t : owned) if (t != null) t.close();
            owned.clear();
        }
    }

    /** Level-1 hand-over (lmcdn/llow): RG = (Den1 - Y1 - LP121(Den1), LP121(Den1)). */
    private static GLTexture lowPass(GLProg glProg, List<GLTexture> owned, GLTexture den1, GLTexture y1) {
        GLTexture out = tex(owned, y1.mSize, GLFormat.DataType.FLOAT_16, 2, GL_LINEAR);
        glProg.useAssetProgram("lmcdn/llow", false);
        glProg.setTexture("InputBuffer", den1);
        glProg.setTexture("Base", y1);
        glProg.drawBlocks(out);
        return out;
    }

    private static void lumaPass(GLProg glProg, GLTexture target, GLTexture in, GLTexture recon, GLTexture yin, boolean useYin,
                                 GLTexture strMap, Point levelSize, int stride, boolean filter, float nx, float ny, float thr,
                                 int stage, float rf, int mode, GLTexture base, GLTexture lowFreq, GLTexture chroma) {
        glProg.useAssetProgram("lmcdn/lbf", false);
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
                                   Point levelSize, int stride, boolean filter, float nx, float ny, float uvsU, float uvsV, float thr,
                                   int mode, boolean fade, float keep) {
        glProg.useAssetProgram("lmcdn/cbf", false);
        glProg.setTexture("InputBuffer", in);
        glProg.setTexture("DeltaUV", deltaUV != null ? deltaUV : in);
        glProg.setTexture("Orig", orig != null ? orig : in);
        glProg.setTexture("StrMap", strMap != null ? strMap : in);
        glProg.setVar("strideU", stride);
        glProg.setVar("filterU", filter ? 1 : 0);
        glProg.setVar("useDeltaU", deltaUV != null ? 1 : 0);
        glProg.setVar("useMapU", strMap != null ? 1 : 0);
        glProg.setVar("noiseU", nx, ny);
        glProg.setVar("uvsU", uvsU, uvsV);
        glProg.setVar("thrU", thr);
        glProg.setVar("mapInvU", 1f / levelSize.x, 1f / levelSize.y);
        glProg.setVar("modeU", mode);
        glProg.setVar("keepU", keep);
        glProg.setVar("fadeU", fade ? 1 : 0);
        glProg.setVar("darkFadeU", 0.0008f, 0.003f);
        glProg.drawBlocks(target);
    }

    private static float round(float v) { return Math.round(v * 100f) / 100f; }
    private static String col(float[][] t, int f) {
        float[] v = new float[t.length];
        for (int i = 0; i < t.length; i++) v[i] = t[i][f];
        return LmcDenoiseTables.fmt(v);
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
