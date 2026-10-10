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
 * "SCAM Noise Reduction" of the SCAM Hybrid: the finish denoise of SCAM 9.6 (GCam) as ArkCam 2.85 runs it, on the
 * white-balanced linear RGB after {@link HighlightRecovery} (the space where GCam denoises, before the colour matrix).
 * <ol>
 * <li>Despeckle (as the NLM route: isolated dots, also hot pixels the merge let through), then the YUV of GCam's
 *     common.cl. On the Sabre 2x grid everything below runs at the SENSOR scale (luma through kD8, colour through
 *     {1,3,3,1}) and the change is put back with a Laplacian re-assembly: out = RGB_2x + RGB(Up2(Den0 - X0)).</li>
 * <li>Noise: the single-frame model of the base frame (Camera2 profile, white-balance gains) times the measured
 *     difference variance of every pyramid level and stride (GCam derives it from a white spectrum; our merge leaves
 *     correlated noise), rho = how much noisier the model ArkCam hands GCam is than the real one.</li>
 * <li>SNR of the finish input (EstimateSnr: mean 0.18 / display gain) selects and interpolates the tiers of
 *     {@link ScamDenoiseTables}.</li>
 * <li>Luma (GCam LumaDenoise F16): kD8 pyramid, 4 levels x {BilateralFilter3x3 stride 2, stride 1}, revert per band
 *     (not clamped in F16; here clamped to dn_revert_max), the 1-2-1 low pass of level 1 kept aside on level 0.</li>
 * <li>Chroma (GCam ChromaDenoise) on the luma-denoised YUV: {1,3,3,1} pyramid, 4 levels x 2 joint YUV bilateral 3x3
 *     passes, Y untouched; optional fade of the colour in the darkest pixels.</li>
 * </ol>
 * Runs for hybrid shots when pref_scam_hybrid_dn_engine = gcam (default); {@link ScamHdrDenoise} delegates here and stays
 * the "nlm" engine and the SCAM HDR (SCAM) denoise.
 */
public final class ScamDenoise extends Node {
    public ScamDenoise() { super("", "ScamDenoise"); }
    @Override public void Compile() {}

    /** Noise reduction engine of the hybrid: "gcam" (this node, default) or "nlm" ({@link ScamHdrDenoise}). */
    static String engine() {
        String v = PreferenceKeys.hybridString("dn_engine", "gcam");
        return "nlm".equals(v) ? "nlm" : "gcam";
    }

    /** True for a shot merged by the SCAM Hybrid whose noise reduction engine is "gcam". */
    public static boolean enabledFor(PostPipeline p) {
        return p != null && p.mParameters != null && p.mParameters.scamRgb != null
                && PreferenceKeys.isHybridShot() && "gcam".equals(engine());
    }

    @Override
    public void Run() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        if (!enabledFor(pipeline)) {
            // Added to a route directly while the engine is "nlm" (or not a hybrid shot): the NLM node does the work.
            ScamHdrDenoise nlm = new ScamHdrDenoise();
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

    /** Settings of the hybrid section «Шумоподавление ARK / SCAM». */
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
    static float displayGain(float p50, float p90, boolean scamSoc) {
        final float mid = 0.050f, high = 0.180f, gainMax = 128f;
        p50 = Math.max(p50, 1.0e-5f); p90 = Math.max(p90, 1.0e-5f);
        float gain50 = mid / p50, gain90 = high / p90;
        float sceneGain = (float) Math.sqrt(Math.max(1f, gain50) * Math.max(1f, gain90));
        boolean limited = scamSoc && PreferenceKeys.profileNumber("pref_scamold_ae_limit_mode", 1f, 0f, 1f) > 0f;
        float brightMid = PreferenceKeys.profileNumber("pref_scamold_ae_bright_mid", 0.25f, 0f, 1f);
        if (limited) {
            float limit = PreferenceKeys.profileNumber("pref_scamold_ae_high_limit", 0.40f, 0.05f, 1f);
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
    private static float[] levelNoise(GLProg glProg, List<GLTexture> owned, GLTexture level, int block, float sY, float rY, float[][] rawOut) {
        Point blocks = new Point(Math.max(1, level.mSize.x / block), Math.max(1, level.mSize.y / block));
        GLTexture st = tex(owned, blocks, GLFormat.DataType.UNSIGNED_32, 4, GL_NEAREST);
        try {
            glProg.useAssetProgram("scamdn/stats", false);
            glProg.setTexture("InputBuffer", level);
            glProg.setVar("blockU", block);
            glProg.setVar("modelU", sY, rY);
            glProg.drawBlocks(st);
            float[] raw = readStats(st);
            if (rawOut != null) rawOut[0] = raw;
            return ScamDenoiseTables.reduceLegacy(raw, blocks.x * blocks.y, 0.8f);
        } finally {
            release(owned, st);
        }
    }

    /**
     * W1.6: {@link #levelNoise} of several levels at once. Every statistics pass is drawn first, then they are read back
     * (one sync instead of one per level), and the levels are reduced in parallel with the primitive-key sorts of
     * {@link ScamDenoiseTables#reduce}. The same passes, read-backs and reductions, so the same gains; rawOut gets level 0.
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
                glProg.useAssetProgram("scamdn/stats", false);
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
        com.particlesdevs.photoncamera.util.ParallelWork.forEach(n, i -> out[i] = ScamDenoiseTables.reduce(raws[i], counts[i], 0.8f));
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
        ByteBuffer eff = ScamBurst.lastEffectiveFrames;
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
        final ScamDenoiseTables.Config cfg = readConfig();
        final boolean despeckle = PreferenceKeys.hybridSwitch("despeckle", true);
        final boolean darkFade = PreferenceKeys.hybridSwitch("dn_dark_fade", true);
        final float[] darkChroma = darkChroma(pipeline.signedRgb);
        final float rhoS = clamp(PreferenceKeys.hybridValue("dn_model_shot", 1.2f), 0.25f, 4f);
        final float rhoR = clamp(PreferenceKeys.hybridValue("dn_model_read", 6f), 0.25f, 16f);
        final float snrFixed = clamp(PreferenceKeys.hybridValue("dn_snr", 0f), 0f, 200f);
        final float snrScale = clamp(PreferenceKeys.hybridValue("dn_snr_scale", 1f), 0.25f, 8f);
        final String mapMode = PreferenceKeys.hybridString("dn_strength_map", "auto");
        final float effMax = clamp(PreferenceKeys.hybridValue("bento_denoise_max", 3f), 1f, 12f);
        // 2x grid: share of the colour finer than the sensor scale that is kept (0 = colour denoised as on the 1x grid).
        final float keep2x = clamp(PreferenceKeys.hybridValue("dn_chroma_2x_keep", 0f), 0f, 1f);
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
            if (par.noiseModeler != null && par.noiseModeler.baseModel != null && par.noiseModeler.baseModel[0] != null) {
                for (int c = 0; c < 3; c++) {
                    S[c] = (float) Math.max(1e-9, par.noiseModeler.baseModel[c].first);
                    O[c] = (float) Math.max(1e-12, par.noiseModeler.baseModel[c].second);
                }
                modelSource = "camera";
                // P77: the factor the worker measured on the burst and merged with (Redmi Note 11 Pro: HAL profile ~16 x too high)
                final float k = ScamHybridBurst.lastNoiseFactor;
                if (k != 1f) {
                    for (int c = 0; c < 3; c++) { S[c] *= k; O[c] *= k; }
                    modelSource = "camera x" + k;
                }
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
            final float sigmaU = ScamHdrDenoise.estimateNoise(glProg, noisy, 1);
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
            }
            GLTexture x0 = tex(owned, size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
            glProg.useAssetProgram("scamdn/yuv", false);
            glProg.setTexture("InputBuffer", clean);
            glProg.drawBlocks(x0);
            if (clean != base) release(owned, clean);
            if (s == 1) base = null; // the 1x output is built from the denoised YUV alone

            // ---- pyramids: luma kD8 Y1..Y4, chroma {1,3,3,1} XC1..XC3 of the noisy YUV (noise statistics)
            GLTexture[] Y = new GLTexture[ScamDenoiseTables.LEVELS + 1];
            Y[0] = x0;
            for (int L = 1; L <= ScamDenoiseTables.LEVELS; L++) {
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
            tPrep = System.nanoTime();

            // ---- measured noise per level and stride (luma pyramid: Y; chroma pyramid: Y, U, V)
            float[][] gL = new float[ScamDenoiseTables.LEVELS][], gC = new float[ScamDenoiseTables.LEVELS][];
            float[][] raw0 = new float[1][];
            if (com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy()) {
                gL[0] = gC[0] = levelNoise(glProg, owned, x0, 16, sY, rY, raw0);
                for (int L = 1; L < ScamDenoiseTables.LEVELS; L++) {
                    gL[L] = levelNoise(glProg, owned, Y[L], 16, sY, rY, null);
                    gC[L] = levelNoise(glProg, owned, XC[L], 16, sY, rY, null);
                }
            } else {
                // The same levels in the same order: x0, then Y[L], XC[L] for L = 1..3.
                GLTexture[] levels = new GLTexture[2 * ScamDenoiseTables.LEVELS - 1];
                levels[0] = x0;
                for (int L = 1; L < ScamDenoiseTables.LEVELS; L++) { levels[2 * L - 1] = Y[L]; levels[2 * L] = XC[L]; }
                float[][] g = levelNoiseBatch(glProg, owned, levels, 16, sY, rY, raw0);
                gL[0] = gC[0] = g[0];
                for (int L = 1; L < ScamDenoiseTables.LEVELS; L++) { gL[L] = g[2 * L - 1]; gC[L] = g[2 * L]; }
            }
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
                    // fallback: GCam's sqrt(r_Y / r_ch) from the model
                    UVS[L][k] = src != null ? src[6 + k] : 1f;
                }
                if (gL[L] != null) measured++;
            }

            final float[] darkKeep = darkKeep(darkChroma, GC[0][1], sY, rY);
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
            float rawScale = par.scamHdrMode ? Math.max(1e-6f, par.scamHdrRawScale) : 1f;
            if (p50 > 0f) {
                gain = displayGain(p50 / rawScale, p90 / rawScale, PreferenceKeys.isScamNetSoc());
                mu = 0.18f * rawScale / gain;
            } else {
                mu = 0.18f;
            }
            final float snrMeasured = ScamDenoiseTables.snr(mu, GY[0][0], sG, rG, rhoS, rhoR);
            final float snr = snrFixed > 0f ? snrFixed : snrMeasured * snrScale;

            // ---- tiers
            ScamDenoiseTables.Pick[] lp = new ScamDenoiseTables.Pick[1], cp = new ScamDenoiseTables.Pick[1];
            final float[][] luma = ScamDenoiseTables.luma(cfg, snr, lp);
            final float[][] chroma = ScamDenoiseTables.chroma(cfg, snr, cp);

            // ---- strength map
            float[] effRef = {64f};
            GLTexture strMap = null;
            boolean wantMap = !"uniform".equals(mapMode);
            String mapState = "uniform";
            if (wantMap) {
                GLTexture eff = loadEffectiveFrames(owned, full, effRef);
                if (eff != null) {
                    strMap = tex(owned, half(size0), GLFormat.DataType.FLOAT_16, 1, GL_LINEAR);
                    glProg.useAssetProgram("scamdn/strmap", false);
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
            for (int L = 1; L <= ScamDenoiseTables.LEVELS; L++) release(owned, Y[L]);
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
            if (x0p != x0) release(owned, x0);
            GLTexture deltaUV = null;
            StringBuilder chromaLog = new StringBuilder();
            output = null;
            for (int L = ScamDenoiseTables.LEVELS - 1; L >= 0; L--) {
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
                    chromaPass(glProg, c0, level, deltaUV, null, strMap, sz, 1, false, 0f, 0f, 1f, 1f, 0f, 0, false, 0f, null);
                    release(owned, deltaUV);
                    deltaUV = null;
                }
                if (f0) {
                    GLTexture c1 = tex(owned, sz, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                    chromaPass(glProg, c1, c0, null, null, strMap, sz, 2, true, k0 * GC[L][1] * rhoS * sY, k0 * GC[L][1] * rhoR * rY,
                            UVS[L][1], UVS[L][3], (float) (int) chroma[L + 1][1], 0, false, 0f, null);
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
                            UVS[L][0], UVS[L][2], (float) (int) chroma[L][1], 1, false, 0f, null);
                } else if (s == 1) {
                    out = pipeline.getMain(); // the last write of this node
                    lastMainTaken = true;
                    chromaPass(glProg, out, c0, null, null, strMap, sz, 1, f1, k1 * GC[0][0] * rhoS * sY, k1 * GC[0][0] * rhoR * rY,
                            UVS[0][0], UVS[0][2], (float) (int) chroma[0][1], 2, darkFade, 0f, darkKeep);
                    output = out;
                } else {
                    // (Y change, UV(Den0) - keep UV(X0)): final2x upsamples this one texture
                    out = tex(owned, size0, GLFormat.DataType.FLOAT_16, 4, GL_LINEAR);
                    chromaPass(glProg, out, c0, null, base, strMap, sz, 1, f1, k1 * GC[0][0] * rhoS * sY, k1 * GC[0][0] * rhoR * rY,
                            UVS[0][0], UVS[0][2], (float) (int) chroma[0][1], 3, false, keep2x, null);
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
                                 GLTexture strMap, Point levelSize, int stride, boolean filter, float nx, float ny, float thr,
                                 int stage, float rf, int mode, GLTexture base, GLTexture lowFreq, GLTexture chroma) {
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
                                   int mode, boolean fade, float keep, float[] darkKeep) {
        glProg.useAssetProgram("scamdn/cbf", false);
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
        glProg.setVar("darkChromaU", darkKeep != null ? darkKeep[0] : 0f, darkKeep != null ? darkKeep[1] : 0f);
        glProg.setVar("darkNoiseU", darkKeep != null ? darkKeep[2] : 0f, darkKeep != null ? darkKeep[3] : 0f);
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
