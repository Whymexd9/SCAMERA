package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.opengl.GLES30;
import com.particlesdevs.photoncamera.util.Log;

import static android.opengl.GLES31.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES31.GL_LINEAR;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.opengl.GLBasePipeline;
import com.particlesdevs.photoncamera.processing.opengl.GLCoreBlockProcessing;
import com.particlesdevs.photoncamera.processing.opengl.GLDrawParams;
import com.particlesdevs.photoncamera.processing.opengl.GLFormat;
import com.particlesdevs.photoncamera.processing.opengl.GLImage;
import com.particlesdevs.photoncamera.processing.opengl.GLInterface;
import com.particlesdevs.photoncamera.processing.opengl.GLProg;
import com.particlesdevs.photoncamera.processing.opengl.GLTexture;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.RotateWatermark;
import com.particlesdevs.photoncamera.processing.opengl.scripts.GLHistogram;
import com.particlesdevs.photoncamera.processing.parameters.ResolutionSolution;
import com.particlesdevs.photoncamera.processing.render.NoiseModeler;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.processing.ultrahdr.GainMapComputer;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Allocator;
import com.particlesdevs.photoncamera.util.BufferUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;

public class PostPipeline extends GLBasePipeline {
    public ByteBuffer stackFrame;
    public GLTexture FusionMap;
    public GLTexture GainMap;
    /**
     * Linear display gain of the shot: the WHY exposure (WhyStats / WhyAe).
     */
    public float linearDisplayGain = 1.0f;
    /** Noise sigma of the frame (variance stabilised units) estimated by ScamHdrDenoise; 0 = not measured. */
    public float scamNoiseSigma = 0f;
    /**
     * The working RGB holds signed values: set by ScamRgb (scamrgb signedU, SCAM Hybrid), cleared by a noise reduction
     * that writes its clamped output (the SCAM engine; the NLM engine keeps it: chromadn/apply clips once, downstream).
     * WhyCombine then clamps its B-spline colour after the interpolation; otherwise (SCAM HDR, or the hybrid after the
     * SCAM denoise) per tap as before, so negative taps of the colour matrix change nothing there.
     */
    public boolean signedRgb;
    /** WHY tone of the SCAM Hybrid: state handed from WhyStats to WhyCombine; null outside that route. */
    public WhyTone.State why;
    /**
     * P349: the glow share of the fully clipped cores (ScamRgb.sunBloom, WhyCombine's sun bloom), the image size it covers and its
     * block, px (P277: the whole frame's on a lab fragment).
     */
    public GLTexture sunBloom;
    public Point sunBloomSize;
    public int sunBloomBlock;
    /**
     * P378: the nearness of a light «Inpaint opposed» rebuilt (ScamRgb.opposedShare, WhyCombine's highlight landing), the image
     * size it covers and its block, px (as the sun bloom's).
     */
    public GLTexture opposedShare;
    public Point opposedShareSize;
    public int opposedShareBlock;
    /**
     * P378 review (Ultra HDR keeps the sky term's change: the gain map's boost is the scene plane over the SDR, so a lower SDR sky
     * came back to its old light on an HDR display): the SDR's own ratio of the sky term (WhyCombine: why/combine at whyLow with
     * and without it, why/skyratio), per pixel of a grid of whyLow (sensor layout, its longer side at most WhyCombine.SKY_RATIO_SIDE),
     * row-major; null without a non-backlit sky or Ultra HDR. RunHDRGainMap scales the scene plane by it, then lets it go.
     */
    public float[] skyHdrRatio;
    public Point skyHdrRatioSize;
    /**
     * P277: a lab shot's run records the whole frame's statistics here ({@link LabFrame}); a lab run on a fragment of it sets
     * {@link #frozen} to those, used in place of the fragment's own. Both null in an ordinary run.
     */
    public LabFrame record, frozen;
    public ArrayList<Bitmap> debugData = new ArrayList<>();
    public Point cropSize;
    /** Final output size of the hybrid 2x pipeline (sensor orientation), produced by {@link HybridFinalResize}; null = output at rawSize. */
    public Point finalSize;
    public boolean finalResized;

    // Ultra HDR state.
    /**
     * When true, the first run captures the linear (post-demosaic) buffer so
     * the scene-anchored gain-map pass can measure pre-local-tone-map scene
     * energy.
     */
    /**
     * Highest scene-referred value the raw can now reach. Opposed-colour highlight
     * inpainting reconstructs clipped photosites above 1.0, and downstream stages
     * need to know that instead of assuming 1.0. Ported with the inpainting group
     * from upstream PhotonCamera.
     */
    public float rawClipLevel = 1.0f;
    public boolean captureDemosaic = false;
    private boolean mCaptured = false;
    /**
     * CPU copy of the post-demosaic linear buffer (survives GLTexture.closeAll).
     * Backed by a native {@link Allocator} malloc - at 50 MP this snapshot is
     * ~768 MB (16 B/pixel), far above the Java heap growth limit, so it must
     * not be a Java-accounted direct ByteBuffer. Must be freed via
     * {@link #releaseDemosaicLinear()}.
     */
    public ByteBuffer demosaicLinear;
    public Point demosaicLinearSize;
    /** P147: the snapshot holds the texture's IEEE halves (8 B a pixel) instead of floats (16 B a pixel). */
    boolean demosaicLinearHalf;
    /**
     * W1.3: {@link #Run} leaves the GL state (the EGL context and the textures still open) for {@link #finishDeferredTeardown},
     * which the caller runs after the JPEG is saved. Only at 16 MP or less without Ultra HDR ({@link #canDeferTeardown}):
     * above it the encode would hold the GL working set next to the bitmap, which the earlier release exists for.
     */
    public boolean deferTeardown;
    private boolean teardownPending;
    /**
     * The processing {@link #Run} worked on, left by {@link #RunHDRGainMap} for a context of its own; {@link #close} closes it after
     * the gain-map one. Nothing closed it: the context kept for the process (P353) stayed taken for good, and every later HDR shot
     * leaked its own context (EGL context, pbuffer, tile target and programs).
     */
    private GLCoreBlockProcessing retiredProcessing;
    /**
     * 10-bit HEIC / AVIF (heif.Heic10Support.wanted, avif.AvifEncoder.tenBitImageWanted, set by the caller before
     * {@link #Run}): the last pass renders into an RGB10_A2 target and Run returns an RGBA_1010102 bitmap. Off: the RGBA8
     * target and ARGB_8888 bitmap as always.
     */
    public boolean tenBitOutput;
    /**
     * P46 «Цветовое пространство» Display P3 (set by the caller before {@link #Run}): WhyCombine renders the final image in
     * Display P3 primaries (why/combine.glsl with P3_OUT 1). Off: the sRGB shader exactly as before (the define is not set).
     */
    public boolean p3Output;
    /**
     * P369 (owner, 2026-10-27: «Убедись что при включении симуляций плёнки наши алгоритмы не будут мешать симуляциям»): the shot's
     * film simulation (FilmSim.current(): null without a stock or at strength 0) and the chosen LUT with its strength
     * (FilmChain.current()'s plain state), taken once per shot by {@link #takeFilm} - before the teardown decision and the
     * pipeline - so every film gate of the shot (the nodes, the scene feed of WhyCombine, the gain map) reads one snapshot, never
     * the settings again while the shot is processed.
     */
    @androidx.annotation.Nullable
    public com.particlesdevs.photoncamera.processing.color.FilmSim.Settings film;
    public boolean filmTaken;
    public String filmUserLut = "";
    public float filmUserStrength;
    /**
     * Owner, 2026-10-28 («в видоискателе вижу эффект, на конечном фото нет»): the shot's lens filter as the viewfinder showed it
     * at the press (ScamProcessor sets it from the shot's saver); null: none taken, the settings now (the lab's renders, under
     * their overlay).
     */
    @androidx.annotation.Nullable
    public LensFilter.Choice lensFilter;
    /** The shot's film, LUT and «Цвет» at the press (ShotLook; ScamProcessor sets it); null: the settings now (the lab's renders). */
    @androidx.annotation.Nullable
    public ShotLook look;

    public PostPipeline() {
        super("PostPipeline");
    }

    /**
     * P369: the shot's film snapshot ({@link #film}, the LUT and its strength): the press's ({@link #look}), else the settings now
     * (the lab's overlay too).
     */
    public void takeFilm() {
        final ShotLook l = look;
        film = l != null ? l.film : com.particlesdevs.photoncamera.processing.color.FilmSim.current();
        filmUserStrength = l != null ? l.lutStrength : PreferenceKeys.filmLutStrength();
        filmUserLut = l != null ? l.lut : filmUserStrength > 0f ? PreferenceKeys.filmLut() : "";
        filmTaken = true;
        if (l != null) {
            final ShotLook now = ShotLook.now();
            if (!now.toString().equals(l.toString()))
                Log.i("SCAM_PIPELINE", "look of the press: " + l + " (the settings now: " + now + ")");
        }
        // the film's table now (FilmChain.scene caches it for WhyCombine): film data that cannot be read gives the whole shot the
        // path without a film - our look, the chosen LUT, the gain map - instead of the film's gates with no film drawn
        if (film != null && com.particlesdevs.photoncamera.processing.color.FilmChain.scene(
                com.particlesdevs.photoncamera.app.ScameraApp.getAppContext(), film, filmUserLut, filmUserStrength) == null) {
            Log.w("SCAM_PIPELINE", "film data unreadable (" + film.stock.id + "): the photo without the film");
            film = null;
        }
    }

    /**
     * The nodes on the linear scene light before the tone: the lens filter of the shot (P228; none chosen: no node), P380 the
     * film's camera diffusion (LensFilter.film: spektrafilm's order, the camera's filter before the film's halation; none
     * without a film or its diffusion), P281 the film's halation and scatter (FilmHalation.from). Without a film: the lens
     * filter's node alone, as before.
     */
    static java.util.List<com.particlesdevs.photoncamera.processing.opengl.nodes.Node> lightNodes(LensFilter.Choice shotFilter,
            @androidx.annotation.Nullable com.particlesdevs.photoncamera.processing.color.FilmSim.Settings film) {
        final java.util.List<com.particlesdevs.photoncamera.processing.opengl.nodes.Node> out = new java.util.ArrayList<>();
        final LensFilter lensFilterNode = LensFilter.of(shotFilter);
        if (lensFilterNode != null) out.add(lensFilterNode);
        final LensFilter diffusion = LensFilter.film(film);
        if (diffusion != null) out.add(diffusion);
        final FilmHalation halation = FilmHalation.from(film);
        if (halation != null) out.add(halation);
        return out;
    }

    /** The shot's lens filter: the press's ({@link #lensFilter}), else the settings now. */
    public LensFilter.Choice lensFilterOfShot() {
        return lensFilter != null ? lensFilter : LensFilter.Choice.now();
    }

    /**
     * P369 (outputs-1): the gain map (Ultra HDR, the HDR HEIC / AVIF) only without a film: its scene-anchored boost undoes the
     * film's highlight rendering and divides out its grain on an HDR screen, so a film photo is saved in SDR.
     */
    public static boolean gainMapWanted(boolean setting, @androidx.annotation.Nullable com.particlesdevs.photoncamera.processing.color.FilmSim.Settings film) {
        return setting && film == null;
    }

    /**
     * P369 (texture-local-contrast, settings-6): «Текстура» (ScamLocalContrast) runs after WhyCombine, on the film's print: it
     * boosted the print's 1 - 16 px bands by 25 - 50 %. Not with a film.
     */
    static boolean wantsLocalContrast(boolean texture, @androidx.annotation.Nullable com.particlesdevs.photoncamera.processing.color.FilmSim.Settings film) {
        return texture && film == null;
    }

    public int getRotation() {
        int rotation = mParameters.cameraRotation;
        String TAG = "ParseExif";
        // P277: the lab renders with no camera open (no gravity sensor or capture controller in the process yet)
        try {
            Log.d(TAG, "Gravity rotation:" + ScameraApp.getGravity().getRotation());
            Log.d(TAG, "Sensor rotation:" + ScameraApp.getCaptureController().mSensorOrientation);
        } catch (RuntimeException noCamera) {
            Log.d(TAG, "rotation " + rotation + " (no camera open)");
        }
        return rotation;
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private Point getRotatedCoords(Point in) {
        switch (getRotation()) {
            case 0:
            case 180:
                return in;
            case 90:
            case 270:
                return new Point(in.y, in.x);
        }
        return in;
    }

    private void computeNoise(Parameters parameters) {
        NoiseModeler modeler = parameters.noiseModeler;
        if (modeler == null || modeler.computeModel == null
                || modeler.computeModel.length < 3
                || modeler.computeModel[0] == null
                || modeler.computeModel[1] == null
                || modeler.computeModel[2] == null) {
            Log.w("PostPipeline", "Missing noise model; using conservative fallback");
            noiseS = 1.0e-4f;
            noiseO = 1.0e-6f;
            return;
        }
        noiseS = modeler.computeModel[0].first.floatValue() +
                modeler.computeModel[1].first.floatValue() +
                modeler.computeModel[2].first.floatValue();
        noiseO = modeler.computeModel[0].second.floatValue() +
                modeler.computeModel[1].second.floatValue() +
                modeler.computeModel[2].second.floatValue();
        noiseS /= 3.f;
        noiseO /= 3.f;
        // P320: a DCG shot's clamp (WhyAe.gainClamp: sigma at 0.5, WhyStats' only reader of noiseS / noiseO) takes the measured
        // zones: the slope and the offset of 0.5's zone (low gain above the knee) in place of the green one-frame pair, scaled as
        // the model averaged here (its channel ratios and stacking); without zones the camera's curve stays (logged)
        if (parameters.dcgShot && !com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy()) {
            final float[] zones = NoiseModeler.dcgZones(parameters.dcgNoise);
            final float[] clamp = zones == null ? null : WhyAe.dcgClampModel(zones, noiseS, noiseO,
                    modeler.baseModel != null && modeler.baseModel.length > 1 && modeler.baseModel[1] != null
                            ? modeler.baseModel[1].first : Double.NaN,
                    modeler.baseModel != null && modeler.baseModel.length > 1 && modeler.baseModel[1] != null
                            ? modeler.baseModel[1].second : Double.NaN);
            if (clamp != null) {
                Log.i("PostPipeline", "DCG gain clamp model: S " + noiseS + " -> " + clamp[0] + ", O " + noiseO + " -> " + clamp[1]
                        + " (zones " + java.util.Arrays.toString(zones) + ", offset of 0.5's zone)");
                noiseS = clamp[0];
                noiseO = clamp[1];
            } else {
                Log.i("PostPipeline", "DCG gain clamp: no measured noise zones, the camera's single curve");
            }
        }
        Log.d("PostPipeline", "NoiseS:" + noiseS + "\n" + "NoiseO:" + noiseO);
        noiseO = Math.max(noiseO, Float.MIN_NORMAL);
        noiseS = Math.max(noiseS, Float.MIN_NORMAL);
    }

    public Bitmap Run(ByteBuffer inBuffer, Parameters parameters) {
        // The old-model run of post_ab is not the shot's own: its times are in the POST AB line, not in the SHOT TIMELINE.
        final boolean legacyRun = com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy();
        if (!legacyRun) com.particlesdevs.photoncamera.processing.ShotTimeline.mark("post_start");
        com.particlesdevs.photoncamera.processing.opengl.PostGlMode.setSyncNodes(!legacyRun && PreferenceKeys.scamDevSwitch("post_sync", false));
        mParameters = parameters;
        mSettings = ScameraApp.getSettings();
        workSize = new Point(mParameters.rawSize.x, mParameters.rawSize.y);
        computeNoise(parameters);
        // P369: the film snapshot (ScamProcessor takes it before the teardown decision; the lab's sides take it here, under their
        // own overlay)
        if (!filmTaken) takeFilm();
        captureDemosaic = gainMapWanted(mSettings.gainMapPass(), film);
        mCaptured = false;
        linearDisplayGain = 1.0f;
        scamNoiseSigma = 0f;
        signedRgb = false;
        sunBloom = null;
        sunBloomSize = null;
        sunBloomBlock = 0;
        opposedShare = null;
        opposedShareSize = null;
        opposedShareBlock = 0;
        skyHdrRatio = null;
        skyHdrRatioSize = null;
        // Drop any stale reference from a previous run; the texture itself is
        // reclaimed by GLTexture.closeAll().
        rawClipLevel = 1.0f;
        Point rawSliced = parameters.rawSize;
        cropSize = new Point(parameters.rawSize);
        finalSize = null;
        finalResized = false;
        // P145: with Ultra HDR too - the linear snapshot is taken at this size (captureDemosaicLinear) and the gain map is
        // built for the resized base, instead of the base resized on the CPU (Lanczos: 0.8 s at 50 MP, 2.6 s at 200 MP)
        if (parameters.hybridFinalSize != null
                && parameters.hybridFinalSize.x < parameters.rawSize.x && parameters.hybridFinalSize.y < parameters.rawSize.y) {
            // The output image, the crop and the rotation work on the resized image (HybridFinalResize before Rotate).
            finalSize = new Point(parameters.hybridFinalSize);
            rawSliced = new Point(finalSize);
            cropSize = new Point(finalSize);
        }
        // «16:9»: the frames arrive cropped (Wide169); a second crop here made the output a row taller than its data (P118)
        Point rotatedSize = getRotatedCoords(rawSliced);
        if (mParameters.rawSize.x * mParameters.rawSize.y < ResolutionSolution.smallRes) {
            GLDrawParams.TileSize = 8;
        } else {
            GLDrawParams.TileSize = 256;
        }
        GLFormat format = new GLFormat(GLFormat.DataType.SIMPLE_8, 4);
        GLImage output = new GLImage(rotatedSize, format, false);
        // No full-frame readback buffer: runAllToBitmap() copies the last pass tile by tile into the bitmap.
        // P353: on the context kept for the process (its programs linked once)
        GLCoreBlockProcessing glproc = new GLCoreBlockProcessing(rotatedSize, output, format, GLDrawParams.Allocate.None, tenBitOutput, true);
        glint = new GLInterface(glproc);
        stackFrame = inBuffer;
        glint.parameters = parameters;

        BuildDefaultPipeline();
        // The last pass is rendered tile by tile straight into the bitmap (GLCoreBlockProcessing.drawBlocksToBitmap):
        // the peak is the GL working set + the bitmap, without the full-frame readback buffer (~200 MB at 50 MP) that
        // used to sit next to one or the other (textures + buffer, then buffer + bitmap).
        Bitmap res = runAllToBitmap();
        if (tenBitOutput) Log.i("PostPipeline", "final image " + res.getWidth() + "x" + res.getHeight() + " " + res.getConfig()
                + (res.getConfig() == Bitmap.Config.ARGB_8888 ? " (10-bit target unavailable)" : " for the 10-bit HEIC / AVIF"));
        if (!legacyRun) com.particlesdevs.photoncamera.processing.ShotTimeline.mark("post_readback");
        if (deferTeardown && canDeferTeardown(parameters, film)) {
            // W1.3: released after the encode (finishDeferredTeardown), off the press-to-saved path.
            teardownPending = true;
            return res;
        }
        // The GL working set (three FP16 textures = 1.2 GB on the 50 MP Sabre 2x grid) is released right after the
        // readback. closeAll must run while the EGL context is still current; the linear scene buffer was snapshotted
        // to CPU from inside LinearExposure.Run, so the gain-map pass still works later.
        try {
            GLES30.glFinish();
        } catch (Exception ignored) {}
        GLTexture.closeAll();
        return res;
    }

    /**
     * W1.3: the GL teardown may wait until the JPEG is saved: 16 MP or less, no Ultra HDR pass, not the old model of post_ab.
     * P369: the gain-map pass as the shot's film snapshot decides it ({@link #gainMapWanted}).
     */
    public static boolean canDeferTeardown(Parameters parameters, @androidx.annotation.Nullable com.particlesdevs.photoncamera.processing.color.FilmSim.Settings film) {
        return parameters != null && parameters.rawSize != null && !gainMapWanted(ScameraApp.getSettings().gainMapPass(), film)
                && (long) parameters.rawSize.x * parameters.rawSize.y <= 16_000_000L
                && !com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy();
    }

    /**
     * W1.3: the teardown {@link #Run} skipped with {@link #deferTeardown} (textures, framebuffers, EGL context), after the
     * JPEG encode; also after a run with {@link #deferTeardown} that failed half way (its textures are still open).
     */
    public void finishDeferredTeardown() {
        if ((teardownPending || deferTeardown) && glint != null) {
            teardownPending = false;
            deferTeardown = false;
            try {
                GLCoreBlockProcessing proc = glint.glProcessing;
                if (proc != null) proc.makeCurrent();
                GLES30.glFinish();
                GLTexture.closeAll();
            } catch (RuntimeException e) {
                Log.e("PostPipeline", "deferred GL teardown failed: " + e);
            }
        }
        teardownPending = false;
        close();
    }

    /** Called from LinearExposure.Run to keep the linear scene buffer. */
    public void captureDemosaicLinear(GLTexture tex) {
        captureDemosaicLinear(tex, glint != null ? glint.glProgram : null);
    }

    /**
     * The linear scene snapshot; P145: when the pipeline resizes to the hybrid's final size, the snapshot is that size too
     * (the "area" box mean of HybridFinalResize on the GPU), so the gain map matches the base resized on the GPU and the
     * read-back shrinks (16 B a pixel: 800 MB of the 50 MP grid -> 320 MB of 20 MP; the 200 MP grid fits under 2 GB).
     */
    public void captureDemosaicLinear(GLTexture full, com.particlesdevs.photoncamera.processing.opengl.GLProg prog) {
        if (mCaptured || demosaicLinear != null) return;
        GLTexture reduced = null;
        if (finalSize != null && (full.mSize.x != finalSize.x || full.mSize.y != finalSize.y)) {
            if (prog == null) {
                Log.e("PostPipeline", "Linear scene snapshot: no program to resize it; Ultra HDR will fall back to SDR");
                return;
            }
            try {
                reduced = HybridFinalResize.resized(prog, full, finalSize, "area");
            } catch (RuntimeException e) {
                Log.e("PostPipeline", "Linear scene snapshot resize failed (" + e + "); Ultra HDR will fall back to SDR");
                return;
            }
        }
        try {
            snapshot(reduced != null ? reduced : full);
        } finally {
            if (reduced != null) reduced.close();
        }
    }

    private void snapshot(GLTexture tex) {
        // FLOAT_16 textures are read back as GL_FLOAT (4 B/channel, RGBA),
        // i.e. 16 B/pixel: ~768 MB at 50 MP - above the Java heap growth
        // limit, so the snapshot is allocated in native memory instead
        // (same backing as the pipeline's other large I/O buffers).
        // P27 any resolution: in long; above one 2 GB buffer (~134 MP) the snapshot is skipped like a failed allocation.
        // P147: as the halves the texture holds (8 B/pixel) where the driver reads them back exactly (WhyStats' probe): half
        // the read-back and the upload of RunHDRGainMap (1.2 s for the 50 MP snapshot on the X200 Ultra), the same values.
        tex.BindBuffer();
        final int halfType = com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy() ? 0 : WhyStats.halfReadType();
        if (halfType != 0 && WhyStats.halfReadbackExact(halfType)) {
            final long halfBytes = 8L * tex.mSize.x * tex.mSize.y;
            ByteBuffer buf = halfBytes > Integer.MAX_VALUE ? null : Allocator.allocate((int) halfBytes);
            if (buf != null) {
                buf.order(java.nio.ByteOrder.nativeOrder());
                tex.BindBuffer();
                GLES30.glReadPixels(0, 0, tex.mSize.x, tex.mSize.y, GLES30.GL_RGBA, halfType, buf);
                if (GLES30.glGetError() == GLES30.GL_NO_ERROR) {
                    buf.rewind();
                    demosaicLinear = buf;
                    demosaicLinearSize = new Point(tex.mSize.x, tex.mSize.y);
                    demosaicLinearHalf = true;
                    mCaptured = true;
                    return;
                }
                Allocator.free(buf);
            }
        }
        demosaicLinearHalf = false;
        final long bytes = 16L * tex.mSize.x * tex.mSize.y;
        if (bytes > Integer.MAX_VALUE) {
            Log.e("PostPipeline", "Linear scene snapshot of " + (bytes >> 20) + " MB is above 2 GB; Ultra HDR will fall back to SDR");
            return;
        }
        int size = (int) bytes;
        ByteBuffer buf = Allocator.allocate(size);
        if (buf == null) {
            Log.e("PostPipeline", "Linear scene snapshot allocation of " + size + " B failed; Ultra HDR will fall back to SDR");
            return;
        }
        tex.BindBuffer();
        tex.textureBuffer(new GLFormat(GLFormat.DataType.FLOAT_16, 4), buf);
        buf.rewind();
        demosaicLinear = buf;
        demosaicLinearSize = new Point(tex.mSize.x, tex.mSize.y);
        mCaptured = true;
    }

    /** Frees the native linear scene snapshot; safe to call repeatedly. */
    public void releaseDemosaicLinear() {
        if (demosaicLinear != null) {
            Allocator.free(demosaicLinear);
            demosaicLinear = null;
        }
        demosaicLinearHalf = false;
        demosaicLinearSize = null;
    }

    /**
     * Ultra HDR gain-map pass (scene-anchored): fusion local tone mapping
     * compresses clipped highlights below display white inside the rendering
     * chain, so no comparison of pipeline outputs can recover them. Instead
     * this pass measures the pre-local-tone-map scene energy directly from the
     * captured post-demosaic buffer ({@link #demosaicLinear}):
     *
     * <ol>
     *   <li>render the scene-luma plane L (linear luminance, rotated/cropped to
     *       match the stored base) via {@code ultrahdr/sceneluma}</li>
     *   <li>anchor the two midtones to each other:
     *       {@code k = median(linearized base luma) / median(L)} - matching
     *       medians makes the scene/render ratio grow monotonically as the SDR
     *       signal saturates, so clipped cores always dominate</li>
     *   <li>encode {@code log2((L·k + eps) / (baseLuma + eps))} via
     *       {@code ultrahdr/gainmap}</li>
     * </ol>
     *
     * Scene content above the anchor with a saturated SDR signal yields real,
     * multi-stop boost; the anchor itself is exactly zero and everything below
     * is floored to identity. Independent of AutoExposure and local tone
     * mapping state.
     *
     * @param sdr   the SDR (display-encoded) base rendition, already computed by {@link #Run}
     * @param down  downsample factor per axis for the gain map (1/down^2 pixels)
     * @param scale total log2 range of the encoding; must equal {@link GainMapComputer#SCALE}
     * @return the encoded RGBA8 gain map plus its dimensions, downsample and scale
     */
    public GainMapRaw RunHDRGainMap(ByteBuffer inBuffer, Parameters parameters, Bitmap sdr, int down, float scale) {
        if (demosaicLinear == null || demosaicLinearSize == null) {
            throw new IllegalStateException("Linear buffer missing; Run() must complete first with ultraHdr enabled");
        }
        mParameters = parameters;
        mSettings = ScameraApp.getSettings();
        // P145: the snapshot and the base are at the hybrid's final size when the pipeline resized on the GPU (Run)
        final Point base = finalSize != null ? new Point(finalSize) : new Point(mParameters.rawSize);
        workSize = new Point(base.x, base.y);
        computeNoise(parameters);
        captureDemosaic = false;
        Point rawSliced = base;
        cropSize = new Point(base);
        Point rotatedSize = getRotatedCoords(rawSliced);
        // The gain map must be pixel-aligned with the stored SDR base; any
        // size/orientation mismatch displaces the boost field from the scene.
        // Fail loudly -> caller falls back to a plain SDR JPEG.
        if (sdr.getWidth() != rotatedSize.x || sdr.getHeight() != rotatedSize.y) {
            throw new IllegalStateException("SDR/gain-map size mismatch: sdr="
                    + sdr.getWidth() + "x" + sdr.getHeight()
                    + " map=" + rotatedSize.x + "x" + rotatedSize.y);
        }
        GLFormat format = new GLFormat(GLFormat.DataType.FLOAT_16, 4);
        // Dummy output keeps the original driver path (Direct allocation) while
        // GLImage/GLCoreBlockProcessing null-guards prevent the NPE.
        GLImage output = new GLImage(rotatedSize, format, false);
        GLCoreBlockProcessing glproc = new GLCoreBlockProcessing(rotatedSize, output, format, GLDrawParams.Allocate.Direct);
        // Do not destroy the previous EGL context here: destroying it before
        // GLTexture re-creation caused SEGV_MAPERR on waffle/Adreno. It is
        // closed at the final pipeline.close(), after this one.
        useGainMapContext(glproc);
        stackFrame = inBuffer;
        glint.parameters = parameters;

        // Defensive: the measured linear buffer must match the pipeline input size,
        // otherwise the scene sampling is out of bounds and the gain map is garbage.
        if (demosaicLinearSize.x != workSize.x || demosaicLinearSize.y != workSize.y) {
            throw new IllegalStateException("Linear buffer size " + demosaicLinearSize
                    + " does not match workSize " + workSize
                    + "; cannot measure scene plane");
        }

        GLTexture gainTex = null, skyRatioTex = null;
        try {
            demosaicLinear.rewind();
            GLTexture linTex;
            if (demosaicLinearHalf) {
                // P147: the halves go up as they came (GL_HALF_FLOAT into the RGBA16F texture): the same texels
                linTex = new GLTexture(demosaicLinearSize, new GLFormat(GLFormat.DataType.FLOAT_16, 4));
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, linTex.mTextureID);
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, demosaicLinearSize.x, demosaicLinearSize.y,
                        GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, demosaicLinear);
                checkGlError("half snapshot upload");
            } else {
                linTex = new GLTexture(demosaicLinearSize, new GLFormat(GLFormat.DataType.FLOAT_16, 4), demosaicLinear);
            }
            GLImage sdrImage = new GLImage(sdr);
            GLTexture sdrTex = new GLTexture(sdrImage);
            // The direct copy of the SDR bitmap (~200 MB at 50 MP) is only needed for the upload; drop it now instead of
            // at the end of the pass (GLImage.close releases the buffer, the bitmap stays with the caller).
            sdrImage.close();

            // Lens-shading GainMap for flat-fielding the scene plane (see sceneluma.glsl).
            // Must match the map used on the SDR path (ScamRgb) so the ratio
            // is vignette-free. Re-upload from Parameters; the pipeline's GainMap field
            // was deleted by Run()'s closeAll().
            try {
                Point mapSz = mParameters.mapSize != null ? mParameters.mapSize : new Point(1, 1);
                float[] gmArr = mParameters.gainMap;
                if (gmArr == null || gmArr.length < 4) {
                    gmArr = new float[]{1f, 1f, 1f, 1f};
                    mapSz = new Point(1, 1);
                }
                gainTex = new GLTexture(mapSz, new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                        BufferUtils.getFrom(gmArr), GL_LINEAR, GL_CLAMP_TO_EDGE);
            } catch (Exception e) {
                Log.e("PostPipeline", "Failed to upload GainMap for UltraHDR, falling back to identity", e);
                gainTex = new GLTexture(new Point(1, 1), new GLFormat(GLFormat.DataType.FLOAT_16, 4),
                        BufferUtils.getFrom(new float[]{1f, 1f, 1f, 1f}), GL_LINEAR, GL_CLAMP_TO_EDGE);
            }

            // P378 review: the sky term's ratio (WhyCombine), linear filter, made before the samplers are bound
            if (skyHdrRatio != null && skyHdrRatioSize != null && skyHdrRatio.length == skyHdrRatioSize.x * skyHdrRatioSize.y)
                skyRatioTex = new GLTexture(skyHdrRatioSize, new GLFormat(GLFormat.DataType.FLOAT_16, 1), BufferUtils.getFrom(skyHdrRatio),
                        GL_LINEAR, GL_CLAMP_TO_EDGE);
            skyHdrRatio = null;
            skyHdrRatioSize = null;
            // Scene-luma plane at full resolution, pixel-aligned with the base.
            GLTexture lTex = new GLTexture(new Point(rotatedSize.x, rotatedSize.y),
                    new GLFormat(GLFormat.DataType.FLOAT_16, 4));
            lTex.BufferLoad();

            GLProg prog = glint.glProgram;
            prog.useAssetProgram("ultrahdr/sceneluma");
            prog.setTexture("InputBuffer", linTex);
            prog.setTexture("GainMap", gainTex);
            prog.setVar("rotate", rotationIndex());
            prog.setVar("mirror", parameters.mirror ? 1 : 0);
            prog.setVar("cropSize", cropSize);
            prog.setVar("rawSize", base);
            // P370 (B3): the scene's luminance as the render sees it (its colour matrix and white balance), not Rec.709 on camera RGB
            final float[] w = sceneLumaWeights(WhyTone.colourMatrix(this), parameters.sensorToProPhoto, parameters.whitePoint);
            prog.setVar("wU", w[0], w[1], w[2]);
            // P378 review: the scene plane takes the sky term's ratio (an HDR display shows the sky lowered as the SDR does)
            if (skyRatioTex != null) {
                prog.setTexture("SkyRatio", skyRatioTex);
                prog.setVar("skyRatioU", 1);
            } else {
                prog.setVar("skyRatioU", 0);
            }
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lTex.mBuffer);
            GLES30.glViewport(0, 0, rotatedSize.x, rotatedSize.y);
            prog.draw();
            checkGlError("scene-luma draw");

            // Anchor: medians of the rendering and of the scene plane. Median
            // (not top-percentile) anchoring is required - with large blown
            // regions both top percentiles land inside the same saturated area
            // and would cancel out, while matched medians keep the scene/render
            // ratio growing monotonically toward saturation.
            final int histSize = 256;
            float lMed;
            float sMedLin;
            GLHistogram hist = new GLHistogram(prog, histSize);
            try {
                lMed = Math.max(histogramMedian(hist.Compute(lTex), histSize), 1e-4f);
                float sMedDisp = histogramMedian(hist.Compute(sdrTex), histSize);
                sMedLin = srgbToLinear(sMedDisp);
            } finally {
                hist.close();
            }
            float anchor = sMedLin / lMed;
            Log.d("PostPipeline", "UltraHDR anchor:" + anchor + " Lmed:" + lMed + " SmedLin:" + sMedLin);

            int gw = Math.max(1, rotatedSize.x / down);
            int gh = Math.max(1, rotatedSize.y / down);
            GLTexture outTex = new GLTexture(new Point(gw, gh), new GLFormat(GLFormat.DataType.SIMPLE_8, 4));
            outTex.BufferLoad();

            prog.useAssetProgram("ultrahdr/gainmap");
            prog.setTexture("InputBuffer", sdrTex);
            prog.setTexture("LBuffer", lTex);
            prog.setVar("uAnchor", anchor);
            prog.setVar("uDown", down);
            prog.setVar("uScale", scale);
            prog.setVar("uEps", GainMapComputer.DECODE_OFFSET);
            prog.setVar("uP3", p3Output ? 1 : 0); // P370 (B3): a Display P3 base's own luminance
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outTex.mBuffer);
            GLES30.glViewport(0, 0, gw, gh);
            prog.draw();
            checkGlError("gain-map comparison draw");

            // P145: GainMapComputer's range and requantisation on the GPU (the CPU's two passes over the map otherwise)
            float normalisedMax = Float.NaN;
            GLTexture quantTex = null;
            if (down == 1 && !com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy()) {
                final int maxByte = gainMapMaxByte(prog, outTex, gw, gh);
                if (maxByte >= 0) {
                    final float gMax = GainMapComputer.rangeMax(GainMapComputer.boost(maxByte, scale));
                    final int[] lut = GainMapComputer.requantisation(gMax, scale);
                    final float[] lutF = new float[256];
                    for (int i = 0; i < 256; i++) lutF[i] = lut[i];
                    quantTex = new GLTexture(new Point(gw, gh), new GLFormat(GLFormat.DataType.SIMPLE_8, 4));
                    quantTex.BufferLoad();
                    prog.useAssetProgram("ultrahdr/gainquant");
                    prog.setTexture("InputBuffer", outTex);
                    prog.setVarVec4s("lutU", lutF);
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, quantTex.mBuffer);
                    GLES30.glViewport(0, 0, gw, gh);
                    prog.draw();
                    if (GLES30.glGetError() == GLES30.GL_NO_ERROR) {
                        normalisedMax = gMax;
                    } else {
                        quantTex.close();
                        quantTex = null;
                    }
                    Log.d("PostPipeline", "UltraHDR gain map on the GPU: max byte " + maxByte + " -> GainMapMax " + gMax
                            + (quantTex == null ? " (GL error: CPU normalisation)" : ""));
                }
            }

            ByteBuffer gm = (quantTex != null ? quantTex : outTex).textureBuffer(new GLFormat(GLFormat.DataType.SIMPLE_8, 4), false);
            gm.position(0);
            Bitmap gmBmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
            gmBmp.copyPixelsFromBuffer(gm);

            sdrTex.close();
            linTex.close();
            lTex.close();
            if (skyRatioTex != null) {
                skyRatioTex.close();
                skyRatioTex = null;
            }
            outTex.close();
            if (quantTex != null) quantTex.close();
            if (gainTex != null) {
                try { gainTex.close(); } catch (Exception ignored) {}
                gainTex = null;
            }
            return new GainMapRaw(gmBmp, gw, gh, down, scale, normalisedMax);
        } finally {
            if (gainTex != null) {
                try { gainTex.close(); } catch (Exception ignored) {}
            }
            if (skyRatioTex != null) {
                try { skyRatioTex.close(); } catch (Exception ignored) {}
            }
            skyHdrRatio = null;
            GLTexture.closeAll();
            // Consume-once: the snapshot is only needed for this pass.
            releaseDemosaicLinear();
        }
    }

    /** RunHDRGainMap's context takes over from Run()'s, which is kept for {@link #close} ({@link #retiredProcessing}). */
    void useGainMapContext(GLCoreBlockProcessing glproc) {
        if (glint != null && glint.glProcessing != null && glint.glProcessing != glproc) retiredProcessing = glint.glProcessing;
        glint = new GLInterface(glproc);
    }

    @Override
    public void close() {
        if (teardownPending) {
            // W1.3: a deferred teardown is still due (an error path): the textures go before the context.
            finishDeferredTeardown();
            return;
        }
        // Safety net for paths where the gain-map pass never ran.
        releaseDemosaicLinear();
        super.close();
        // Run()'s context after the gain-map one: made current, its tile target deleted, then released (the kept context) or destroyed
        final GLCoreBlockProcessing retired = retiredProcessing;
        retiredProcessing = null;
        if (retired != null) {
            try {
                retired.close();
            } catch (Exception e) {
                Log.e("PostPipeline", "retired GL context close failed: " + e);
            }
        }
    }

    private void checkGlError(String what) {
        int err = GLES30.glGetError();
        if (err != GLES30.GL_NO_ERROR) {
            Log.e("PostPipeline", "GL error 0x" + Integer.toHexString(err) + " in " + what);
        }
    }

    /** Rotation index used by the rotate-style shaders: 90->3, 180->2, 270->1, else 0. */
    private int rotationIndex() {
        switch (getRotation()) {
            case 90: return 3;
            case 180: return 2;
            case 270: return 1;
            default: return 0;
        }
    }

    /**
     * Median of a {@link GLHistogram} result across RGB channels: the bin
     * where the cumulative count from the top crosses half of all samples.
     * Returns the bin value normalized to [0,1].
     */
    private static float histogramMedian(int[][] result, int histSize) {
        long total = 0L;
        for (int i = 0; i < histSize; i++) {
            total += (long) result[0][i] + (long) result[1][i] + (long) result[2][i];
        }
        if (total <= 0L) return 0f;
        final long threshold = total / 2L;
        long acc = 0;
        for (int i = histSize - 1; i >= 0; i--) {
            acc += (long) result[0][i] + (long) result[1][i] + (long) result[2][i];
            if (acc > threshold) return (float) i / (float) (histSize - 1);
        }
        return 1f;
    }

    /**
     * P370 (B3): the Ultra HDR scene plane's luminance weights of white-balanced camera RGB (ultrahdr/sceneluma wU): Rec.709
     * of the render's linear sRGB, LUMA^T . intermediateToSRGB . sensorToProPhoto . diag(neutral) (why/low.glsl: the input times
     * the neutral, then the two matrices). A white maps to the render's white: 1 on the ISP routes.
     */
    static float[] sceneLumaWeights(float[] intermediateToSRGB, float[] sensorToProPhoto, float[] neutral) {
        final float[] luma = {0.2126f, 0.7152f, 0.0722f}, m = new float[9], w = new float[3];
        com.particlesdevs.photoncamera.processing.render.Converter.multiply(intermediateToSRGB, sensorToProPhoto, m);
        for (int c = 0; c < 3; c++) {
            float s = 0f;
            for (int r = 0; r < 3; r++) s += luma[r] * m[r * 3 + c];
            w[c] = s * neutral[c];
        }
        return w;
    }

    private static float srgbToLinear(float c) {
        c = Math.min(Math.max(c, 0f), 1f);
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /**
     * P145: the largest encoded byte of the gain map (compute ultrahdr/gainmax), -1 when the pass failed (the CPU
     * normalisation then runs).
     */
    static int gainMapMaxByte(GLProg prog, GLTexture map, int w, int h) {
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            // drain: only the pass below decides
        }
        try (com.particlesdevs.photoncamera.processing.opengl.GLBuffer max = new com.particlesdevs.photoncamera.processing.opengl.GLBuffer(
                1, new GLFormat(GLFormat.DataType.UNSIGNED_32))) {
            prog.setLayout(16, 16, 1);
            prog.useAssetProgram("ultrahdr/gainmax", true);
            prog.setTexture("InputBuffer", map);
            prog.setVar("sizeU", w, h);
            prog.setBufferCompute("gainMax", max);
            prog.computeManual((w + 15) / 16, (h + 15) / 16, 1);
            final int[] v = max.readBufferIntegers(false);
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR || v == null || v[0] < 0 || v[0] > 255) return -1;
            return v[0];
        } catch (RuntimeException e) {
            Log.w("PostPipeline", "UltraHDR gain map max on the GPU failed: " + e);
            return -1;
        }
    }

    public static class GainMapRaw {
        public final Bitmap bitmap;
        public final int w;
        public final int h;
        public final int down;
        public final float scale;
        /**
         * P145: GainMapMax when the GPU already requantised the map to [0, it] (GainMapComputer.normalised); NaN for the
         * encoded map that GainMapComputer.compute normalises. A resize or crop afterwards filters the requantised bytes, which
         * the same range still decodes (within one step of 1/255).
         */
        public final float normalisedMax;
        GainMapRaw(Bitmap bitmap, int w, int h, int down, float scale) {
            this(bitmap, w, h, down, scale, Float.NaN);
        }
        GainMapRaw(Bitmap bitmap, int w, int h, int down, float scale, float normalisedMax) {
            this.bitmap = bitmap;
            this.w = w;
            this.h = h;
            this.down = down;
            this.scale = scale;
            this.normalisedMax = normalisedMax;
        }

        /**
         * The map following a resize of the SDR base to {@code baseW} x {@code baseH} (the hybrid's final size after the
         * 2x pipeline): the map is resampled to the same field of view at 1/down of the new size. The encoded values are
         * log gains, so bilinear filtering averages in the log domain. Recycles this map's bitmap when it is replaced.
         */
        public GainMapRaw resizedFor(int baseW, int baseH) {
            final int gw = Math.max(1, baseW / Math.max(1, down)), gh = Math.max(1, baseH / Math.max(1, down));
            if (gw == w && gh == h) return this;
            // Box average through the half-size mip chain (like the "area" downsampler), then the exact size.
            Bitmap cur = bitmap;
            while (cur.getWidth() >= 2 * gw && cur.getHeight() >= 2 * gh) {
                final Bitmap half = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
                if (cur != bitmap) cur.recycle();
                cur = half;
            }
            final Bitmap scaled = Bitmap.createScaledBitmap(cur, gw, gh, true);
            if (cur != bitmap && cur != scaled) cur.recycle();
            if (scaled != bitmap) bitmap.recycle();
            return new GainMapRaw(scaled, gw, gh, down, scale, normalisedMax);
        }

        /** The map following a centre crop of the base (digital zoom): the same crop factor (ZoomController.crop). */
        public GainMapRaw croppedBy(float factor) {
            final Bitmap cropped = com.particlesdevs.photoncamera.control.ZoomController.crop(bitmap, factor);
            if (cropped == bitmap) return this;
            return new GainMapRaw(cropped, cropped.getWidth(), cropped.getHeight(), down, scale, normalisedMax);
        }

        public GainMapRaw croppedTo(Rect crop, int baseWidth, int baseHeight) {
            if (baseWidth <= 0 || baseHeight <= 0 || crop.isEmpty() || crop.left < 0 || crop.top < 0
                    || crop.right > baseWidth || crop.bottom > baseHeight)
                throw new IllegalArgumentException("Invalid SDR/gain-map crop");
            if (crop.left == 0 && crop.top == 0 && crop.width() == baseWidth && crop.height() == baseHeight) return this;
            int gw = Math.max(1, crop.width() / Math.max(1, down));
            int gh = Math.max(1, crop.height() / Math.max(1, down));
            RectF source = new RectF((float) crop.left * w / baseWidth, (float) crop.top * h / baseHeight,
                    (float) crop.right * w / baseWidth, (float) crop.bottom * h / baseHeight);
            Matrix transform = new Matrix();
            transform.setRectToRect(source, new RectF(0, 0, gw, gh), Matrix.ScaleToFit.FILL);
            Bitmap out = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
            try {
                new Canvas(out).drawBitmap(bitmap, transform, new Paint(Paint.FILTER_BITMAP_FLAG));
            } catch (RuntimeException failure) {
                out.recycle();
                throw failure;
            }
            bitmap.recycle();
            return new GainMapRaw(out, gw, gh, down, scale, normalisedMax);
        }
    }

    private void BuildDefaultPipeline() {
        if (mParameters.scamRgb == null)
            throw new IllegalStateException("no merge route: the photo pipeline takes the RGB of Hybrid or SCAM HDR");
        // One tone for both routes: the WHY tone (WhyStats -> WhyFusion -> [WhyLumaSharpen] -> WhyCombine).
        Log.i("SCAM_PIPELINE","route="+(PreferenceKeys.isHybridShot()?"SCAM_hybrid":"SCAM_HDR")
                +" import=SCAM_linear_RGB WB_LSC=SCAMERA TCE=not_connected"
                +" postDenoise=bypassed"
                +" tone=WHY (WhyStats/WhyFusion/WhyCombine)"
                +"; actual node order/timings follow in Pipeline log");
        add(new ScamRgb());
        // P129: HighlightRecovery is gone (SCAM HDR's; SHDR recovers the highlights per channel in ScamRgb)
        add(new ScamHdrDenoise());
        // P228: the lens filter on the linear RGB (highlights with their headroom), before the tone; none: no node at all.
        // Owner, 2026-10-28: the one of the press, not of the settings after the merge
        final LensFilter.Choice shotFilter = lensFilterOfShot(), filterNow = lensFilter != null ? LensFilter.Choice.now() : shotFilter;
        if (!filterNow.toString().equals(shotFilter.toString()))
            Log.i("SCAM_PIPELINE", "lens filter of the press: " + shotFilter + " (the settings now: " + filterNow + ")");
        // P281: the film simulation's halation, on the linear light too (none: no node); P369: of the shot's film snapshot; P380: the
        // film's diffusion between the two (lightNodes)
        for (com.particlesdevs.photoncamera.processing.opengl.nodes.Node n : lightNodes(shotFilter, film)) add(n);
        // The WhyCam 1.23 photo tone (tone_port.md 7.1): its own AE, fusion and AgX. LinearExposure only keeps the
        // Ultra HDR linear snapshot.
        if (captureDemosaic) add(new LinearExposure());
        final WhyStats whyStats = new WhyStats();
        add(whyStats);
        add(new WhyFusion());
        // The only sharpening is WhyCam's own (WhyLumaSharpen before the detail delta; P118, owner 2026-10-13: the
        // RawTherapee and SCAM sharpenings and «off» are gone, the WHY strength rows weaken it).
        final WhyLumaSharpen whyLumaSharpen = new WhyLumaSharpen();
        // W1.6: in the G_CLEAN domain the sharpening needs nothing of the AE, so WhyStats issues its GPU passes before
        // the CPU Smart-HDR statistics run (WhyLumaSharpen then only passes the image on).
        whyStats.earlySharpen = whyLumaSharpen;
        add(whyLumaSharpen);
        add(new WhyCombine());
        // Texture only on request (WhyCam has no mid-frequency boost).
        final boolean texture = PreferenceKeys.hybridSwitch("why_texture", false);
        Log.i("SCAM_PIPELINE", "WHY tail: sharpen=why (WhyLumaSharpen) texture=" + texture
                + (texture && film != null ? " (off: film simulation)" : ""));
        if (wantsLocalContrast(texture, film)) add(new ScamLocalContrast());
        if (finalSize != null) add(new HybridFinalResize(finalSize, PreferenceKeys.hybridDownsampler()));
        // P281: the film's sharpness and grain on the finished picture, at its final size (none: no node); P369: of the snapshot
        final FilmGrain grain = FilmGrain.from(film);
        if (grain != null) add(grain);
        add(new RotateWatermark(getRotation()));
    }
}
