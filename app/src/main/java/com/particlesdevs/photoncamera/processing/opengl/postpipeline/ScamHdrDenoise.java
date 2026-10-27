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
 * of a stock render.
 * <p>
 * Dots: isolated dark or bright dots in flat areas (defective pixel groups, which the merge keeps)
 * are scaled back to their surroundings.
 * Colour: the colour is filtered edge-aware at half resolution over about 56 px (four dilated
 * passes) and replaces the full-resolution colour (joint bilateral upsampling); the darkest pixels
 * fade to neutral because their colour is a black-level error rather than a measurement.
 * Luma: non-local means on sqrt(Y + c) plus an edge-stopping correction of the blotch-scale residue,
 * then a share of the removed noise is put back so the remaining grain is fine and even (a flat
 * spectrum like a reference render) instead of a denoiser's cloudy mid-frequency residue.
 * Runs on the white-balanced linear image after ScamRgb.
 * <p>
 * SCAM Hybrid shots go to {@link ScamDenoise} (SCAM 9.6 finish denoise, pref_scam_hybrid_dn_engine = multiscale, default);
 * this NLM route stays the hybrid's "nlm" engine and the SCAM HDR (SCAM) denoise, unchanged.
 */
public final class ScamHdrDenoise extends Node {
    public ScamHdrDenoise() { super("", "ScamHdrDenoise"); }
    @Override public void Compile() {}

    /**
     * Developer switch (adb: touch /sdcard/Download/SCAMERA/dump-denoise): the full-resolution denoise input and its
     * parameters, written there too (P191: Download/SCAMERA, not Android/data; ScameraFiles).
     */
    private void dumpInput(PostPipeline pipeline) { dumpTexture(pipeline, previousNode.WorkingTexture, "denoise-in"); }

    private void dumpTexture(PostPipeline pipeline, GLTexture source, String name) {
        android.content.Context context = com.particlesdevs.photoncamera.app.ScameraApp.getAppContext();
        if (!com.particlesdevs.photoncamera.util.ScameraFiles.exists(context, "dump-denoise")) return;
        java.io.File dir = com.particlesdevs.photoncamera.util.ScameraFiles.dir();
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
            Log.i("SCAM_PIPELINE", name + " dumped " + w + "x" + h);
        } catch (Exception e) {
            Log.e("SCAM_PIPELINE", "denoise dump failed: " + e);
        } finally {
            android.opengl.GLES30.glBindFramebuffer(android.opengl.GLES30.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) android.opengl.GLES30.glDeleteFramebuffers(1, framebuffer, 0);
        }
    }

    /**
     * Effective merged frames per pixel of this shot (uint8, 1/8 frame) as a texture for the filters;
     * null when the merge did not report it (the noise is then taken as uniform). effRef = its median.
     */
    private float effRef = 1f;
    private GLTexture loadEffectiveFrames(Point size) {
        // developer switch (adb: touch /sdcard/Download/SCAMERA/noeff; P191): uniform noise
        if (com.particlesdevs.photoncamera.util.ScameraFiles.exists(com.particlesdevs.photoncamera.app.ScameraApp.getAppContext(), "noeff")) return null;
        java.nio.ByteBuffer eff = ScamHybridBurst.lastEffectiveFrames;
        if (eff == null || eff.capacity() != size.x * size.y) return null;
        eff.rewind();
        int[] histogram = new int[256];
        long count = ScamDenoise.effHistogram(eff, histogram);
        if (count == 0) return null;
        long seen = 0;
        int median = 1;
        for (int v = 1; v < 256; v++) { seen += histogram[v]; if (seen * 2 >= count) { median = v; break; } }
        // The median itself (>= 1/8 frame): a floor of 1 frame would strengthen pixels at the median of a map whose median is
        // below one frame (the shaders read it one-sided).
        effRef = median / 8f;
        eff.rewind();
        // W3.5: the real codes, read one-sided by chromadn/nlm and chromadn/apply (only fewer than the median frames
        // strengthen the filter); see ScamDenoise.effectiveFramesTexture.
        return ScamDenoise.effectiveFramesTexture(size, eff);
    }

    /** Upper clamp of the per-pixel noise boost derived from the effective-frames map (Bento regions merged from one gained frame). */
    private float effMax = 3f;
    /**
     * P317 (Mali): the usampler2D EffMap always gets a unit of its own - left unset it read unit 0, where the sampler2D
     * InputBuffer sits (two sampler types on one unit: GL_INVALID_OPERATION at the draw, which Mali enforces). The 1 x 1
     * placeholder is never read (useEff 0), as ScamDenoise's.
     */
    private GLTexture effPlaceholder;

    private void bindEffectiveFrames(GLTexture effMap) {
        GLTexture bound = effMap;
        if (bound == null) {
            if (effPlaceholder == null)
                effPlaceholder = new GLTexture(new Point(1, 1), new GLFormat(GLFormat.DataType.UNSIGNED_8, 1), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            bound = effPlaceholder;
        }
        glProg.setTexture("EffMap", bound);
        glProg.setVar("useEff", effMap != null ? 1 : 0);
        glProg.setVar("effRef", effRef);
        glProg.setVar("effMax", effMax);
    }

    /**
     * Noise level (sigma of u) from the flat, quiet blocks of the frame (25th percentile of the block statistics).
     * s = output pixels per sensor pixel: on the Sabre 2x grid adjacent output pixels are kernel averages of the
     * same donor samples, so the neighbour difference is taken s pixels apart and a block covers 8 x 8 sensor
     * pixels; this keeps the reading (and every constant tuned against it) the same as on the 1x grid.
     */
    static float estimateNoise(com.particlesdevs.photoncamera.processing.opengl.GLProg glProg, GLTexture noisy, int s) {
        final int block = 8 * s;
        Point blocks = new Point((noisy.mSize.x + block - 1) / block, (noisy.mSize.y + block - 1) / block);
        GLTexture est = new GLTexture(blocks, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_LINEAR, GL_CLAMP_TO_EDGE);
        int[] oldRead = new int[1], framebuffer = new int[1];
        try {
            glProg.useAssetProgram("chromadn/noiseest", false);
            glProg.setTexture("InputBuffer", noisy);
            glProg.setVar("blockU", block);
            glProg.setVar("pxStepU", s);
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
            // P364: the quarter's element by selection (the sort of ~200 k blocks took ~20 ms): the same value
            return ScamDenoiseTables.selectFloat(values, n, n / 4) / 0.8463f / (float) Math.sqrt(1.125);
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
        if (!PreferenceKeys.isHybridDenoiseEnabled()) {
            // The master switch «Шумоподавление» is off: the merge goes on untouched (P165: on every shot, not only one
            // processed under the hybrid's profile).
            WorkingTexture = previousNode.WorkingTexture;
            glProg.closed = true;
            Log.i("SCAM_PIPELINE", "denoise off (pref_scam_hybrid_denoise)");
            return;
        }
        // P306 (owner, 2026-10-20: «можешь включить шумодав для 200мп, настрой по этому фото его», «только дай возможность его
        // полностью отключать»): a 200 MP shot is denoised again (P179 had it the merge alone), on its 2x2 means (ScamDenoise: the
        // Sabre 2x grid path, every pyramid at the 50 MP scale; the 200 MP pixel noise cored in scamdn/final2x); «Шумоподавление»
        // off (above) still leaves it the merge alone. With too little memory for its textures it is the merge alone, in the log.
        if (huge(pipeline.mParameters)) {
            final long need = ScamDenoise.hugeBytes(previousNode.WorkingTexture.mSize), avail = availableMemory();
            if (avail >= 0 && avail < need) {
                WorkingTexture = previousNode.WorkingTexture;
                glProg.closed = true;
                Log.i("SCAM_PIPELINE", "denoise skipped: " + (avail >> 20) + " MB free, the 200 MP denoise needs " + (need >> 20) + " MB");
                return;
            }
        }
        // SCAM Hybrid shots: the SCAM finish denoise (pref_scam_hybrid_dn_engine = multiscale, default); this NLM route
        // stays the "nlm" engine and the SCAM HDR (SCAM) denoise. A failure there falls back to the NLM route.
        // P306: never on a 200 MP shot: its full-grid textures (~3.6 GB) are what P179 took away; the "nlm" engine and a failure of
        // the 2x2-means denoise leave it the merge alone.
        // (a lab fragment of a 200 MP shot as the shot: its whole size)
        final com.particlesdevs.photoncamera.processing.render.Parameters par = pipeline.mParameters;
        final boolean huge = par != null && par.labWholeSize != null ? huge(par.labWholeSize.x, par.labWholeSize.y) : huge(par);
        if (huge && !ScamDenoise.enabledFor(pipeline)) {
            WorkingTexture = previousNode.WorkingTexture;
            glProg.closed = true;
            Log.i("SCAM_PIPELINE", "denoise skipped: the NLM denoise does not run on a 200 MP shot (pref_scam_hybrid_dn_engine)");
            return;
        }
        if (ScamDenoise.enabledFor(pipeline)) {
            try {
                dumpInput(pipeline); // developer switch dump-denoise: the linear input for offline replays (tools/check_scamdn.py)
                WorkingTexture = ScamDenoise.process(glProg, pipeline, previousNode.WorkingTexture);
                glProg.closed = true;
                pipeline.signedRgb = false; // scamdn/cbf mode 2 and scamdn/final2x clamp their output
                dumpTexture(pipeline, WorkingTexture, "denoise-out");
                return;
            } catch (RuntimeException error) {
                if (ScamDenoise.lastInputReleased) throw error; // P179: the input is gone (200 MP): no NLM pass can read it
                Log.e("SCAM_PIPELINE", "scam-denoise failed, NLM denoise instead: " + error);
                if (ScamDenoise.lastMainTaken) {
                    // The failure came after the output texture was taken: the ping-pong cursor already points past
                    // this node's input. Taking it once more turns it back (getMain alternates two textures), so the
                    // NLM pass writes the texture ScamDenoise took and the next node does not get its own input as
                    // target (which a plain pass-through would have caused).
                    pipeline.getMain();
                    ScamDenoise.lastMainTaken = false;
                }
                if (huge) {
                    WorkingTexture = previousNode.WorkingTexture;
                    glProg.closed = true;
                    Log.i("SCAM_PIPELINE", "denoise skipped: the 200 MP denoise failed, no NLM pass on that size");
                    return;
                }
            }
        }
        runNlm();
    }

    /**
     * Above this many output pixels (200 MP): the finish denoise runs on the 2x2 means (P306) and the worker makes no
     * effective-frame map (P187: its planes sat on the 200 MP merge's peak).
     */
    static final long HUGE_PIXELS = 100_000_000L;

    static boolean huge(com.particlesdevs.photoncamera.processing.render.Parameters p) {
        return p != null && p.rawSize != null && huge(p.rawSize.x, p.rawSize.y);
    }

    static boolean huge(int width, int height) {
        return (long) width * height > HUGE_PIXELS;
    }

    /** MemAvailable of the system (bytes), -1 when unknown. */
    private static long availableMemory() {
        try {
            android.content.Context context = com.particlesdevs.photoncamera.app.ScameraApp.getAppContext();
            android.app.ActivityManager manager = context == null ? null
                    : (android.app.ActivityManager) context.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (manager == null) return -1;
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return info.availMem;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The NLM route (SCAM HDR shots, or the hybrid with dn_engine = nlm). */
    void runNlm() {
        PostPipeline pipeline = (PostPipeline) basePipeline;
        WorkingTexture = previousNode.WorkingTexture;
        // Output pixels per sensor pixel (2 on the Sabre 2x grid, else 1): every fixed window below is dilated
        // by s and the half/quarter stages run at 1/(2s), 1/(4s), so the processing is the same in sensor
        // pixels on either grid; at s = 1 nothing changes.
        final int s = Math.max(1, Math.round(pipeline.mParameters.outputScale));
        // The SCAM-hybrid engine has its own strengths («Шумоподавление после склейки» in the hybrid section).
        final boolean hybrid = PreferenceKeys.isHybridShot();
        final float[] darkChroma = ScamDenoise.darkChroma(pipeline.signedRgb);
        // Signed input (the SCAM Hybrid: ScamRgb signedU): chromadn/luma and chromadn/apply clip nothing per pixel (the
        // hybrid clips once, after averaging, downstream); the output may keep negative values, so signedRgb stays set.
        final boolean signed = pipeline.signedRgb;
        float chroma = Math.max(0f, Math.min(2f, hybrid ? PreferenceKeys.hybridValue("post_chroma", 1f) : PreferenceKeys.routeInternalValue("post_chroma", 1f)));
        float luma = Math.max(0f, Math.min(2f, hybrid ? PreferenceKeys.hybridValue("post_luma", 0.6f) : PreferenceKeys.routeInternalValue("post_luma", 0.6f)));
        boolean despeckle = hybrid ? PreferenceKeys.hybridSwitch("despeckle", true) : PreferenceKeys.isScamDespeckleEnabled();
        effMax = hybrid ? Math.max(1f, Math.min(12f, PreferenceKeys.hybridValue("bento_denoise_max", 3f))) : 3f;
        if (chroma <= 0f && luma <= 0f && !despeckle) { glProg.closed = true; return; }
        long started = System.currentTimeMillis();
        dumpInput(pipeline);
        float offsetC = 0.008f;
        GLTexture original = previousNode.WorkingTexture;
        GLFormat rgba = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        GLFormat mono = new GLFormat(GLFormat.DataType.FLOAT_16, 1);
        Point half = new Point((original.mSize.x + 2 * s - 1) / (2 * s), (original.mSize.y + 2 * s - 1) / (2 * s));
        float noiseSigma = 0f;
        pipeline.scamNoiseSigma = 0f;
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
                glProg.setVar("signedU", signed ? 1 : 0);
                glProg.drawBlocks(noisy);
                noiseSigma = estimateNoise(glProg, noisy, s);
                pipeline.scamNoiseSigma = noiseSigma;
            }
            if (despeckle) {
                cleaned = new GLTexture(original.mSize, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                glProg.useAssetProgram("chromadn/despeckle", false);
                glProg.setTexture("InputBuffer", original);
                glProg.setVar("sigma", noiseSigma);
                glProg.setVar("offsetC", offsetC);
                glProg.setVar("pxStepU", s);
                glProg.drawBlocks(cleaned);
                input = cleaned;
            }
            before = new GLTexture(half, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            ping = new GLTexture(half, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            pong = new GLTexture(half, rgba, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
            glProg.useAssetProgram("chromadn/down", false);
            glProg.setTexture("InputBuffer", input);
            glProg.setVar("factorU", 2 * s);
            glProg.drawBlocks(before);
            GLTexture source = before;
            if (chroma > 0f) {
                GLTexture[] targets = {ping, pong, ping, pong};
                int[] steps = {1, 2};
                float tolerance = Math.max(1f, chroma);
                for (int pass = 0; pass < steps.length; pass++) {
                    glProg.useAssetProgram("chromadn/filter", false);
                    glProg.setTexture("InputBuffer", source);
                    glProg.setVar("stepU", steps[pass]);
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
                    glProg.setVar("signedU", signed ? 1 : 0);
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
                glProg.setVar("pxStepU", 1); // full lattice: a dilated patch/candidate set decouples even/odd pixels of the 2x grid (2x2 blocks, smeared text)
                glProg.drawBlocks(clean);
                Point quarterSize = new Point((original.mSize.x + 4 * s - 1) / (4 * s), (original.mSize.y + 4 * s - 1) / (4 * s));
                quarter = new GLTexture(quarterSize, mono, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                coarse = new GLTexture(quarterSize, mono, null, GL_LINEAR, GL_CLAMP_TO_EDGE);
                glProg.useAssetProgram("chromadn/down4", false);
                glProg.setTexture("InputBuffer", clean);
                glProg.setVar("factorU", 4 * s);
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
            glProg.setVar("pxStepU", 1); // full lattice for the 3x3 means / residual smoothing (see nlm)
            glProg.setVar("lowRatio", 2f * s);
            // Share of the removed noise put back: the grain that stays is held near an absolute level
            // (about 0.0002 in u: the merge leaves about half the noise it did before its kernel followed the
            // SNR, and measured against an SCAM render of the same scene this level gives the same noise in
            // flat and in textured areas), so a noisier frame (dim scene, high ISO) is cleaned harder, as a
            // The reference render does, instead of keeping a fixed fraction of its noise.
            glProg.setVar("grain", luma > 0f ? Math.max(PreferenceKeys.routeInternalValue("grain_min", 0.12f), Math.min(1f, PreferenceKeys.routeInternalValue("grain_level", 0.0002f) / (Math.max(noiseSigma, 1.0e-4f) * luma))) : 1f);
            glProg.setVar("offsetC", offsetC);
            glProg.setVar("lumaAmount", luma > 0f ? 1f : 0f);
            // below 1 the filtered colour is blended in (settings audit 2026-10-21: every value in (0, 1] gave the same picture);
            // above 1 the filter's tolerance grows instead
            glProg.setVar("chromaAmount", Math.min(chroma, 1f));
            glProg.setVar("darkFade", 0.0008f, 0.003f);
            glProg.setVar("darkChroma", darkChroma[0], darkChroma[1]);
            glProg.setVar("signedU", signed ? 1 : 0);
            WorkingTexture = pipeline.getMain();
            glProg.drawBlocks(WorkingTexture);
            glProg.closed = true;
            pipeline.signedRgb = signed; // chromadn/apply writes >= 0 unless its input is signed
        } finally {
            for (GLTexture t : new GLTexture[]{cleaned, before, ping, pong, noisy, clean, quarter, coarse, effMap}) if (t != null) t.close();
            if (effPlaceholder != null) { effPlaceholder.close(); effPlaceholder = null; }
        }
        dumpTexture(pipeline, WorkingTexture, "denoise-out");
        Log.i("SCAM_PIPELINE", "denoise chroma=" + chroma + " luma=" + luma + " despeckle=" + despeckle + " sigma=" + noiseSigma
                + " ms=" + (System.currentTimeMillis() - started));
    }
}
