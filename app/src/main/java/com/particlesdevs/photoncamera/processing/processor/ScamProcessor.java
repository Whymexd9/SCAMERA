package com.particlesdevs.photoncamera.processing.processor;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.api.Camera2ApiAutoFix;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.RawBin;
import com.particlesdevs.photoncamera.processing.RawCrop;
import com.particlesdevs.photoncamera.processing.opengl.GLLimits;
import com.particlesdevs.photoncamera.processing.ImageSaver;
import com.particlesdevs.photoncamera.processing.ProcessingEventsListener;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.PostPipeline;
import com.particlesdevs.photoncamera.processing.ultrahdr.GainMapComputer;
import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Allocator;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import com.particlesdevs.photoncamera.util.Lang;

public class ScamProcessor extends ProcessorBase {
    /** P33: frees the shot's big buffers off the save path (one at a time, in order). */
    private static final java.util.concurrent.ExecutorService BUFFER_FREE = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scam-buffer-free"); t.setDaemon(true); return t; });
    private static final String TAG = "ScamProcessor";
    private ArrayList<ImageFrame> mImageFramesToProcess;
    private HashMap<Long, Double> exposures;
    private int imageFormat;
    /* config */
    private int alignAlgorithm;
    private int saveRAW;
    private CameraMode cameraMode;
    private ArrayList<GyroBurst> BurstShakiness;
    private String processingStage = "initialization";
    private ByteBuffer scamOwnedOutput;
    private Parameters scamOutputParameters;
    /** P187: the clip flags and the frame map this shot's merge returned (dropped at its end, not by the next shot's merge). */
    private ByteBuffer shotClipFlags, shotEffectiveFrames;
    private boolean scamCapture;
    /** This shot is merged by the SCAM Hybrid (its own settings profile), not by SCAM HDR (SCAM). */
    private boolean hybridShot;
    /** P27: the merge route the queued shot was captured for (null: the current setting). */
    public Boolean ownedHybridRoute;
    /** W1.3: the post pipeline whose GL teardown waits until the JPEG is saved (null: none pending). */
    private PostPipeline deferredPipeline;
    /**
     * The post pipeline of this shot until it is closed or deferred (null: none open): when the shot fails in between, Run()'s finally
     * tears it down. Above 16 MP or with the gain-map pass nothing did, so the kept context stayed taken and its textures (GBs at
     * 50 / 200 MP) stayed allocated.
     */
    private PostPipeline openPipeline;
    /**
     * The reference RAW the shot took from its frame (an arena view up to 16 MP, its own copy above) until its frees below (null:
     * freed or handed on): when the shot fails after the merge, Run()'s finally frees it. Its frame's buffer is null by then, so the
     * frames' close skipped it and the view kept the whole shot arena mapped, or a ZSL ring slot pinned, for the process lifetime.
     */
    private ByteBuffer referenceRaw;


    public ScamProcessor(ProcessingEventsListener processingEventsListener) {
        super(processingEventsListener);
    }

    public void configure(int alignAlgorithm, int saveRAW, CameraMode cameraMode) {
        this.alignAlgorithm = alignAlgorithm;
        this.saveRAW = saveRAW;
        this.cameraMode = cameraMode;
    }

    // Per-shot copy when processing is queued: the camera thread rebuilds the
    // static list for the next shot while this one is still processing.
    public java.util.List<IsoExpoSelector.ExpoPair> ownedPairs;
    public boolean ownedIszAspect4x3;
    private boolean iszAspect4x3;
    /** P190: the FULLRES series of the queued shot (frames the merge keeps whole, 0: none; frames asked for, 0: not FULLRES). */
    public int ownedFullResSeries, ownedFullResRequested;
    /** P320: the shot's frames come from a DCG sensor mode (set at the press, ImageSaver.dcgShot). */
    public boolean ownedDcgShot;
    private boolean dcgShot;
    private int fullResSeries, fullResRequested;
    /** P212f (W2 = M3): the hand-set white balance's gains at the press of the queued shot (null: automatic). */
    public android.hardware.camera2.params.RggbChannelVector ownedManualWb;
    private android.hardware.camera2.params.RggbChannelVector manualWb;
    /** The shot's digital-zoom crop and camera at the press (ImageSaver.zoomResidual / cameraIdAtPress; null id: the open one). */
    public float ownedZoomResidual = 1f;
    private float zoomResidual = 1f;
    public String ownedCameraId;
    private String cameraIdAtPress;
    /** The shot's capture plan (ImageSaver.planNote) for MergeStamp; null: none. */
    public String ownedPlanNote;
    private String planNote;
    /** Owner, 2026-10-28: the lens filter the viewfinder showed at the press (ImageSaver.lensFilterAtPress; null: the settings). */
    public com.particlesdevs.photoncamera.processing.opengl.postpipeline.LensFilter.Choice ownedLensFilter;
    private com.particlesdevs.photoncamera.processing.opengl.postpipeline.LensFilter.Choice lensFilterAtPress;
    /** The film, LUT and «Цвет» at the press (ImageSaver.lookAtPress; null: the settings). */
    public com.particlesdevs.photoncamera.processing.opengl.postpipeline.ShotLook ownedLook;
    private com.particlesdevs.photoncamera.processing.opengl.postpipeline.ShotLook lookAtPress;
    /** P212g (W6 = M6): the mosaic white balance's route at the press of the queued shot (null: none). */
    public com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.Press ownedMosaicWb;
    private com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.Press mosaicWb;
    private java.util.List<IsoExpoSelector.ExpoPair> fullpairs;

    public void start(Path dngFile, Path imageFile,
                      ParseExif.ExifData exifData,
                      ArrayList<GyroBurst> BurstShakiness,
                      ArrayList<ImageFrame> imageBuffer,
                      HashMap<Long, Double> exposures,
                      int imageFormat,
                      int cameraRotation,
                      CameraCharacteristics characteristics,
                      CaptureResult captureResult,
                      CaptureRequest captureRequest,
                      ProcessingCallback callback) {
        this.imageFile = imageFile;
        this.dngFile = dngFile;
        this.exifData = exifData;
        this.BurstShakiness = new ArrayList<>(BurstShakiness);
        this.imageFormat = imageFormat;
        this.cameraRotation = cameraRotation;
        this.mImageFramesToProcess = imageBuffer;
        this.exposures = exposures;
        this.callback = callback;
        this.characteristics = characteristics;
        this.captureResult = captureResult;
        this.captureRequest = captureRequest;
        this.iszAspect4x3 = ownedIszAspect4x3;
        ownedIszAspect4x3 = false;
        this.fullResSeries = ownedFullResSeries;
        this.fullResRequested = ownedFullResRequested;
        ownedFullResSeries = ownedFullResRequested = 0;
        this.dcgShot = ownedDcgShot;
        ownedDcgShot = false;
        this.manualWb = ownedManualWb;
        ownedManualWb = null;
        this.mosaicWb = ownedMosaicWb;
        ownedMosaicWb = null;
        this.zoomResidual = ownedZoomResidual;
        ownedZoomResidual = 1f;
        // the crop the processing of this shot reads (the cropShot decision, the mosaic statistic): SCAM_PROCESSING is one thread
        com.particlesdevs.photoncamera.control.ZoomController.beginProcessing(zoomResidual);
        this.cameraIdAtPress = ownedCameraId;
        ownedCameraId = null;
        this.planNote = ownedPlanNote;
        ownedPlanNote = null;
        com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.resetStamp();
        this.lensFilterAtPress = ownedLensFilter;
        ownedLensFilter = null;
        this.lookAtPress = ownedLook;
        ownedLook = null;
        this.scamCapture = PreferenceKeys.isScamEnabled();
        // The route of the shot fixes the settings profile of its whole processing: pref_scam_hybrid_* for a hybrid shot,
        // the SCAM HDR keys otherwise (PreferenceKeys.isHybridShot), even if the route changes meanwhile.
        this.hybridShot = scamCapture && (ownedHybridRoute != null ? ownedHybridRoute : PreferenceKeys.isScamHybridEnabled());
        ownedHybridRoute = null;
        android.util.Log.i("SCAM_HDR", "route=" + PreferenceKeys.mergeRoute() + (hybridShot ? " (SCAM Hybrid merges)" : scamCapture ? " (SCAM HDR merges)" : " (no SCAM route)"));
        this.fullpairs = ownedPairs != null ? ownedPairs : IsoExpoSelector.fullpairs;
        Log.d(TAG, "start()");
        PreferenceKeys.beginShotProfile(hybridShot);
        try {
            Run();
        } finally {
            PreferenceKeys.endShotProfile();
        }
    }

    public void Run() {
        // P277: a lab run (settings «Гайд и лаборатория») and a shot never process at once
        com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLab.LOCK.lock();
        try {
            processingStage = "camera metadata";
            Camera2ApiAutoFix.ApplyRes(captureResult, characteristics); // P370 (review): the shot's black-level pattern
            if (CaptureController.isRawFormat(imageFormat)) {
                processBurst();
            } else {
                Log.d(TAG, "Processing skipped: unsupported image format " + imageFormat);
                callback.onFinished();
                return;
            }
//            if (isYuv) {
//                ApplyStabilization();
//            }
        } catch (Exception | OutOfMemoryError e) {
            // P27 any resolution: an allocation that grows with the frame (Java heap) fails the shot, not the processing thread.
            Log.e(TAG, ProcessingEventsListener.FAILED_MSG);
            Log.e(TAG, "Processing failed: "+stackTrace(e));
            callback.onFailed();
            String detail = e.getClass().getSimpleName();
            if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                detail += ": " + e.getMessage();
            }
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            if (root != e) {
                detail += " / " + root.getClass().getSimpleName();
                if (root.getMessage() != null && !root.getMessage().isEmpty()) {
                    detail += ": " + root.getMessage();
                }
            }
            processingEventsListener.onProcessingError("SCAM processing failed at "
                    + processingStage + " — " + detail);
         } finally {
          try {
            com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLab.endShot();
            releaseOpenPipeline();
            finishDeferredTeardown();
            // after the GL teardown: nothing reads it any more
            final ByteBuffer reference = referenceRaw;
            referenceRaw = null;
            if (reference != null) Allocator.free(reference);
            com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamDiagnostics.finish();
            if (scamOwnedOutput != null) {
                // ScamRgb may already have freed the big buffer after the GL upload (then scamRgb is a small copy).
                if (scamOutputParameters == null || scamOutputParameters.scamRgb == scamOwnedOutput) Allocator.free(scamOwnedOutput);
                scamOwnedOutput=null;
                if(scamOutputParameters!=null){scamOutputParameters.scamRgb=null;scamOutputParameters.scamRgbOwned=false;}
                scamOutputParameters=null;
            }
            if (mImageFramesToProcess != null)
                for (ImageFrame frame : mImageFramesToProcess) if (frame.buffer != null) frame.close();
            // P187: nothing reads this shot's clip flags or frame map any more; until now they stayed until the next shot's
            // worker had finished (through its capture and merge)
            com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamRgb.dropClipFlags(shotClipFlags);
            if (com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastEffectiveFrames == shotEffectiveFrames)
                com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastEffectiveFrames = null;
            shotClipFlags = shotEffectiveFrames = null;
          } finally {
            com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLab.LOCK.unlock();
          }
        }
    }

    /**
     * The pipeline a failed shot left open ({@link #openPipeline}): its textures go with its context current (glFinish, closeAll),
     * then the context is released (the kept one) or destroyed, as a deferred teardown.
     */
    private void releaseOpenPipeline() {
        final PostPipeline open = openPipeline;
        openPipeline = null;
        releaseFailedPipeline(open);
    }

    static void releaseFailedPipeline(PostPipeline open) {
        if (open == null) return;
        try {
            open.deferTeardown = true;
            open.finishDeferredTeardown();
        } catch (RuntimeException e) {
            Log.e(TAG, "failed PostPipeline teardown: " + e);
        }
    }

    /** W1.3: the GL teardown of the post pipeline after the JPEG (or after a failure); nothing when none is pending. */
    private void finishDeferredTeardown() {
        final PostPipeline pending = deferredPipeline;
        if (pending == null) return;
        deferredPipeline = null;
        try {
            pending.finishDeferredTeardown();
        } catch (RuntimeException e) {
            Log.e(TAG, "PostPipeline teardown failed (non-fatal): " + Log.getStackTraceString(e));
        }
        com.particlesdevs.photoncamera.processing.ShotTimeline.mark("gl_teardown");
    }

    /**
     * W1.11 test switch: scam_dev.txt "post_priority N" runs the post pipeline at thread nice value N (the processing thread
     * runs at Java priority NORM_PRIORITY - 1); returns the previous nice value to restore, or Integer.MIN_VALUE when unset.
     */
    private static int raisePostPriority() {
        if (!PreferenceKeys.scamDevOverrides("post_priority")) return Integer.MIN_VALUE;
        try {
            final int tid = android.os.Process.myTid();
            final int before = android.os.Process.getThreadPriority(tid);
            final int scam = Math.max(-8, Math.min(19, Math.round(PreferenceKeys.scamDevNumber("post_priority", before))));
            android.os.Process.setThreadPriority(scam);
            Log.i(TAG, "post pipeline at thread priority " + scam + " (was " + before + ")");
            return before;
        } catch (RuntimeException e) {
            Log.w(TAG, "post_priority not applied: " + e);
            return Integer.MIN_VALUE;
        }
    }

    private static void restorePriority(int before) {
        if (before == Integer.MIN_VALUE) return;
        try { android.os.Process.setThreadPriority(before); } catch (RuntimeException ignored) {}
    }

    /** The app log's stack trace for an Exception (as before); an Error (OutOfMemoryError) through android.util.Log. */
    private static String stackTrace(Throwable t) {
        return t instanceof Exception ? Log.getStackTraceString((Exception) t) : android.util.Log.getStackTraceString(t);
    }

    /**
     * The DNG's saved status goes to the listener when it is saved, or when it is the shot (RAW only, the merged mosaic DNG alone).
     * A failed optional DNG is a notice: its «could not be saved» card said the photo was lost while the JPEG was saved after it.
     */
    static void reportDng(ProcessingEventsListener listener, boolean saved, int saveRAW, boolean mosaicDngOnly, Path dngFile) {
        if (saved || saveRAW == 2 || mosaicDngOnly) {
            listener.notifyImageSavedStatus(saved, dngFile);
            return;
        }
        try {
            listener.onProcessingChanged(Lang.t("DNG не сохранён; фото сохраняется", "DNG not saved; the photo is still being saved"));
        } catch (RuntimeException e) {
            Log.d(TAG, "DNG notice: " + e);
        }
    }

    /**
     * P27 any resolution, above 16 MP only: the reference RAW leaves the shot arena before the post pipeline. One arena view keeps
     * the whole memfd (every frame of the shot, 2.7 GB at 50 MP x 27) mapped until the end of the shot; an own copy (same bytes,
     * order and position) lets the arena go once the other frames are closed. The view itself when it is no arena view or the
     * copy fails.
     */
    private static ByteBuffer outOfArena(ByteBuffer view) {
        if (view == null || Allocator.arenaOf(view) == null) return view;
        final int bytes = view.capacity();
        ByteBuffer own = Allocator.allocateAndCopy(bytes, view, 0);
        if (own == null) return view;
        own.order(view.order());
        own.position(view.position());
        Allocator.free(view);
        Log.i(TAG, "reference RAW copied out of the shot arena (" + (bytes >> 20) + " MB): the arena goes with the other frames");
        return own;
    }

    /**
     * P236 (speed-ux B4): the shot's frames are closed off the critical path. The owner's X7U shot (2026-10-17 20:20:51.832 «reference
     * RAW copied out» -> 52.219 «Alignment elapsed») spent ~390 ms here before the post pipeline: the last views of a 50 MP shot
     * arena give its 1.3 GB back (a punched hole per 96 MB slot, the munmap and close of the memfd). Nothing reads the frames after
     * the merge (the reference RAW was taken out above); one thread closes them in order, the post pipeline starts meanwhile.
     * A burst of 16 MP or less (tens of ms) and a 200 MP one are closed here as before; scam_dev release_async 0: always here.
     */
    private void releaseFrames(java.util.List<ImageFrame> images) {
        final java.util.List<ImageFrame> frames = new java.util.ArrayList<>(images);
        long bytes = 0;
        for (ImageFrame f : frames) if (f != null && f.buffer != null) bytes += f.buffer.capacity();
        // a 200 MP shot keeps the release here: its post pipeline runs at the memory's edge (P179), the frames' 1.6 GB go first
        final long framePixels = frames.isEmpty() || frames.get(0) == null ? 0 : (long) frames.get(0).width * frames.get(0).height;
        if (bytes <= 16_000_000L * 2 * 2 || framePixels > 100_000_000L || !PreferenceKeys.scamDevSwitch("release_async", true)) {
            for (ImageFrame frame : frames) frame.close();
            return;
        }
        final long total = bytes;
        RELEASE.execute(() -> {
            final long t0 = android.os.SystemClock.elapsedRealtime();
            for (ImageFrame frame : frames) {
                try { frame.close(); } catch (RuntimeException e) { Log.w(TAG, "frame release: " + e); }
            }
            Log.i(TAG, "shot frames released off the critical path: " + frames.size() + " frames, " + (total >> 20) + " MB in "
                    + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
        });
    }

    /** P236: one thread for the frames' release (in order, never two shots' at once). */
    private static final java.util.concurrent.ExecutorService RELEASE = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "scam-frame-release");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    /** P158: a milestone of this shot (0..1) for the ring round the gallery card; never stops the processing. */
    /** P162: cuts the shot's frames (RawCrop) and keeps what it found for the settings' picture; a failure keeps them whole. */
    private RawCrop.Shot cropFrames() {
        final boolean auto = PreferenceKeys.isRawCropAuto();
        final int[] manual = PreferenceKeys.rawCropMargins();
        final ImageFrame first = mImageFramesToProcess.get(0);
        final int w = first.width, h = first.height;
        final Integer mounted = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        final int orientation = mounted == null ? 90 : mounted;
        try {
            final float black = com.particlesdevs.photoncamera.processing.RawLevels.current(characteristics, captureResult).meanBlack();
            // P230: a DCG mode shorter than the buffer (Xiaomi 15 Ultra: 2560 rows of 4096 x 3072): its empty band goes as well
            final int[] mode = com.particlesdevs.photoncamera.capture.SensorModeBand.shotPadding(characteristics, captureResult, first.buffer, w, h);
            final RawCrop.Shot shot = RawCrop.cutShot(mImageFramesToProcess, black, auto, manual, orientation, mode);
            if (auto) PreferenceKeys.setRawCropDetected(RawCrop.describe(w, h, shot.padding, orientation));
            return shot;
        } catch (java.io.IOException | RuntimeException e) {
            Log.w(TAG, "RAW crop skipped: " + e.getMessage());
            return null;
        }
    }

    private void stage(float fraction) {
        try {
            processingEventsListener.onProcessingChanged(fraction);
        } catch (RuntimeException e) {
            Log.d(TAG, "onProcessingChanged: " + e);
        }
    }

    /** P220: a progress event of this shot for the ring (the large-shot hint, the merge's measured share); never stops it. */
    private void progress(com.particlesdevs.photoncamera.processing.ProcessingProgress event) {
        try {
            processingEventsListener.onProcessingChanged(event);
        } catch (RuntimeException e) {
            Log.d(TAG, "onProcessingChanged: " + e);
        }
    }

    private void processBurst() {
        processingStage = "input validation";
        com.particlesdevs.photoncamera.processing.ShotTimeline.mark("prep");
        callback.onStarted();
        processingEventsListener.onProcessingStarted("SCAM");

        Log.d(TAG, "processBurst() called from " + Thread.currentThread().getName());

        long startTime = System.currentTimeMillis();
        Log.d(TAG, "processBurst() mImageFramesToProcess.size():" + mImageFramesToProcess.size());
        if (mImageFramesToProcess == null || mImageFramesToProcess.isEmpty()) {
            throw new IllegalStateException("no RAW frames received");
        }
        // P27: a frame without its RAW (copy failed, closed reader) leaves the burst; the shot goes on with the others.
        for (java.util.Iterator<ImageFrame> it = mImageFramesToProcess.iterator(); it.hasNext();) {
            ImageFrame frame = it.next();
            if (frame != null && frame.buffer != null) continue;
            Log.w(TAG, "RAW frame without data dropped" + (frame == null ? "" : ": timestamp=" + frame.timestamp));
            it.remove();
        }
        if (mImageFramesToProcess.isEmpty()) throw new IllegalStateException("no RAW frame with data received");
        // P162 «Обрезка кадра»: the RAW's zero padding (vivo X200 Pro / Pro Mini: 4000x3000 of image in 4096x3072) and the
        // manual margins are cut off every frame before anything reads them
        final RawCrop.Shot rawCrop = cropFrames();
        // P27 any resolution: the Hybrid merges any RAW stream at its own resolution. Only a stream beyond the hard limits (the
        // sensor-grid RGB float result above 2 GB, i.e. above ~178 MP, or a side above the GPU's texture / render-target limit) is
        // binned 2x2 (same colours averaged) as the last resort instead of failing.
        final int gpuMaxSide = GLLimits.get().maxSide();
        final boolean halfCapable = PreferenceKeys.isScamHybridEnabled();
        int rawBinning = 1;
        if (!RawBin.fits(mImageFramesToProcess.get(0).width, mImageFramesToProcess.get(0).height, gpuMaxSide, halfCapable)) {
            // P127: the stream's levels at its bit depth (RawLevels) for the mosaic check of the binning
            final com.particlesdevs.photoncamera.processing.RawLevels.Levels levels =
                    com.particlesdevs.photoncamera.processing.RawLevels.current(characteristics, captureResult);
            try {
                rawBinning = RawBin.binOversized(mImageFramesToProcess, levels.meanBlack(), levels.white, gpuMaxSide, halfCapable);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        int width = mImageFramesToProcess.get(0).width;
        int height = mImageFramesToProcess.get(0).height;
        // P220: a large shot's ring does not run ahead on the last 12 MP shot's time (its merge reports its own progress)
        if ((long) width * height > 16_000_000L) progress(com.particlesdevs.photoncamera.processing.ProcessingProgress.large());
        Log.d(TAG, "processBurst: buffer:" + mImageFramesToProcess.get(0).buffer.asShortBuffer().remaining());
        Log.d(TAG, "Api WhiteLevel:" + characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));
        Log.d(TAG, "Api BlackLevel:" + characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN));
        Parameters processingParameters = new Parameters();
        processingParameters.rawBinning = rawBinning;
        processingParameters.fullResSeries = fullResSeries;
        processingParameters.fullResRequested = fullResRequested;
        processingParameters.dcgShot = dcgShot;
        processingParameters.manualWbAtPress = manualWb;
        processingParameters.mosaicWbAtPress = mosaicWb;
        if (rawCrop != null && rawCrop.area != null) {
            processingParameters.rawCrop = rawCrop.area;
            processingParameters.rawCropFull = new Point(rawCrop.fullWidth, rawCrop.fullHeight);
            processingParameters.rawCropSensorShiftY = rawCrop.sensorShiftY;
        }
        processingParameters.scamHdrMode = PreferenceKeys.isScamHdrEnabled();
        processingParameters.cameraIdAtPress = cameraIdAtPress;
        processingParameters.FillConstParameters(characteristics, new Point(width, height));
        logShotCamera(processingParameters, width, height);
        // Every shot is merged by the SCAM Hybrid or SCAM HDR (the legacy merge routes are gone).
        if (!scamCapture) throw new IllegalStateException("no merge route: Hybrid or SCAM HDR needs the ZSL RAW stream");
        // sort by timestamp first
        mImageFramesToProcess.sort(Comparator.comparingLong(ImageFrame::getTimestamp));
        if(ScameraApp.getCaptureController()!=null) for(ImageFrame frame:mImageFramesToProcess)
            frame.setCaptureMetadata(ScameraApp.getCaptureController().takeRawMetadata(frame.timestamp));

        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (java.util.Iterator<ImageFrame> it = mImageFramesToProcess.iterator(); it.hasNext();) {
            ImageFrame frame = it.next();
            if (!seen.add(frame.timestamp) || frame.getCaptureRole() == null
                    || frame.measuredExposure <= 0 || frame.measuredIso <= 0
                    || frame.width != width || frame.height != height) {
                // P27: one frame without a clear role, exposure or size never costs the shot: it leaves the burst.
                Log.w("SCAM_HDR", (hybridShot ? "Hybrid" : "SCAM HDR") + ": RAW timestamp=" + frame.timestamp
                        + " dropped (no clear role/exposure/size: role=" + frame.getCaptureRole() + " exposureNs=" + frame.measuredExposure
                        + " ISO=" + frame.measuredIso + " " + frame.width + "x" + frame.height + ")");
                frame.close();
                it.remove();
                continue;
            }
            exposures.put(frame.timestamp, frame.measuredExposure / 1e9 * frame.measuredIso);
        }
        if (mImageFramesToProcess.isEmpty())
            throw new IllegalStateException((hybridShot ? "Hybrid" : "SCAM HDR") + Lang.t(": нет ни одного RAW с ролью и экспозицией", ": no RAW with a role and an exposure"));

        if (BurstShakiness.isEmpty()) {
            BurstShakiness.add(new GyroBurst(1));
            Log.w(TAG, "No gyro burst supplied; using zero-motion fallback");
        }

        // Preserve capture roles and gyro samples by timestamp. Processing later
        // reorders frames by sharpness, while strength=0 removes the short frames
        // entirely; positional indexing would then attach the wrong exposure role.
        HashMap<Long, IsoExpoSelector.ExpoPair> pairByTimestamp = new HashMap<>();
        HashMap<Long, GyroBurst> gyroByTimestamp = new HashMap<>();
        for (int i = 0; i < mImageFramesToProcess.size(); i++) {
            long timestamp = mImageFramesToProcess.get(i).getTimestamp();
            ImageFrame roleFrame = mImageFramesToProcess.get(i);
            IsoExpoSelector.ExpoPair role = new IsoExpoSelector.ExpoPair(
                    roleFrame.measuredExposure, roleFrame.measuredExposure, roleFrame.measuredExposure,
                    roleFrame.measuredIso, roleFrame.measuredIso, roleFrame.measuredIso, roleFrame.measuredIso);
            role.isHighlightFrame = roleFrame.getCaptureRole() == ImageFrame.CaptureRole.SHORT;
            role.isLongFrame = roleFrame.getCaptureRole() == ImageFrame.CaptureRole.LONG;
            pairByTimestamp.put(timestamp, role);
            if (BurstShakiness.size() == mImageFramesToProcess.size()) {
                gyroByTimestamp.put(timestamp, BurstShakiness.get(i));
            }
        }

        if (mImageFramesToProcess.isEmpty()) {
            throw new IllegalStateException("all RAW frames were rejected by bracket roles");
        }

        double normalReferenceExpo = exposures.get(mImageFramesToProcess.get(0).getTimestamp());
        for (ImageFrame frame : mImageFramesToProcess) {
            IsoExpoSelector.ExpoPair role = pairByTimestamp.get(frame.getTimestamp());
            if (role != null && !role.isHighlightFrame && !role.isLongFrame) {
                normalReferenceExpo = exposures.get(frame.getTimestamp());
                break;
            }
        }
        double minExpo = normalReferenceExpo;
        for (int i = 1; i < mImageFramesToProcess.size(); i++) {
            minExpo = Math.min(minExpo, exposures.get(mImageFramesToProcess.get(i).getTimestamp()));
        }
        Log.i(TAG, "Highlight reference exposure product: " + minExpo
                + "; regular RAW frames will be normalized to this frame");
        boolean hasShortHeadroom = false;
        for (ImageFrame frame : mImageFramesToProcess) {
            IsoExpoSelector.ExpoPair role = pairByTimestamp.get(frame.getTimestamp());
            if (role != null && role.isHighlightFrame
                    && exposures.get(frame.getTimestamp()) < normalReferenceExpo * 0.99) {
                hasShortHeadroom = true;
                break;
            }
        }
        processingParameters.highlightSuppressionStrength = hasShortHeadroom ? 1.0f : 0.0f;
        Log.i(TAG, "Short-frame highlight suppression strength: "
                + processingParameters.highlightSuppressionStrength
                + " (short headroom available: " + hasShortHeadroom + ")");
        Log.d(TAG, "Wrapper.init");
        ArrayList<ImageFrame> images = new ArrayList<>();
        int ISO = 0;
        int isoFrames = 0;
        int normalFrames = 0;
        if(BurstShakiness.size() < mImageFramesToProcess.size()){
            Log.d(TAG,"Warning: Gyro data size:"+BurstShakiness.size()+" is less than image size:"+mImageFramesToProcess.size());
        }
        for (int i = 0; i < mImageFramesToProcess.size(); i++) {
            ImageFrame frame = mImageFramesToProcess.get(i);
            GyroBurst mappedGyro = gyroByTimestamp.get(frame.getTimestamp());
            frame.frameGyro = mappedGyro != null ? mappedGyro
                    : new GyroBurst(1); // missing association is unknown, never another frame's motion
            //frame.image = mImageFramesToProcess.get(i);
            //Log.d(TAG,"Timestamp:"+frame.image.getTimestamp());
            //frame.pair = IsoExpoSelector.pairs.get(i % IsoExpoSelector.patternSize);
            frame.pair = pairByTimestamp.get(frame.getTimestamp());
            if(frame.measuredExposure>0 && frame.measuredIso>0) {
                frame.pair.exposure=frame.measuredExposure; frame.pair.iso=frame.measuredIso;
            }
            frame.number = i;
            frame.pair.layerMpy = (float) (exposures.get(mImageFramesToProcess.get(i).getTimestamp()) / minExpo);
            if (frame.pair.layerMpy > 1.0) {
                frame.pair.curlayer = IsoExpoSelector.ExpoPair.exposureLayer.High;
            } else {
                frame.pair.curlayer = IsoExpoSelector.ExpoPair.exposureLayer.Normal;
                normalFrames++;
            }
            /*if(i == mImageFramesToProcess.size()-1){
                int ind = Math.max(0,mImageFramesToProcess.size()-2);
                frame.frameGyro = BurstShakiness.get(ind);
            }*/
            images.add(frame);
            // Bracket members shoot at their own ISO (72 for the ultra-short, 166
            // for the long one here), so averaging all frames moved the burst's ISO
            // away from the regular frames the result is actually built from, and
            // with it the noise model and the EXIF value. They contribute highlights
            // and shadows, not exposure.
            if (!frame.pair.isHighlightFrame && !frame.pair.isLongFrame) {
                ISO += frame.pair.iso;
                isoFrames++;
            }
        }
        ISO = isoFrames > 0 ? ISO / isoFrames : ISO / images.size();
        // Measured for every frame, bracket members included. Their value is not directly comparable with a regular
        // frame's - the measure is normalised by level, not by the noise model, and a longer exposure has a better SNR,
        // which lowers the noise part of the gradient energy on its own - so it is logged for diagnosis and used only
        // against the same exposure, never as a cross-exposure threshold.
        // W1.5: the frames are independent, so they are measured in parallel (each writes its own sharpness).
        final int sharpnessBlock = 1; // P129: SHDR measures the sharpness on the frames as streamed (SCAM HDR's mosaic modes are gone)
        com.particlesdevs.photoncamera.processing.ShotTimeline.mark("sharp_start");
        final ArrayList<ImageFrame> measured = images;
        com.particlesdevs.photoncamera.util.ParallelWork.forEach(measured.size(), k -> measured.get(k).computeSharpness(sharpnessBlock));
        com.particlesdevs.photoncamera.processing.ShotTimeline.mark("sharp_done");
        // W1.0: one line for the burst instead of two per frame (OPPO's log flow control dropped the shot's rows).
        StringBuilder frameLog = new StringBuilder("frames ").append(images.size()).append(" (index=mpy/iso/sharpness/shakiness, L long, S short):");
        for (int i = 0; i < images.size(); i++) {
            ImageFrame frame = images.get(i);
            frameLog.append(' ').append(i).append('=').append(frame.pair.layerMpy).append('/').append(frame.pair.iso)
                    .append('/').append(frame.sharpness).append('/').append(frame.frameGyro.shakiness)
                    .append(frame.pair.isLongFrame ? "L" : "").append(frame.pair.isHighlightFrame ? "S" : "");
        }
        Log.d(TAG, frameLog.toString());

        processingParameters.FillDynamicParameters(captureResult, captureRequest,ISO);
        // The last burst callback belongs to the ultra-short highlight frame: the exposure of the photo (EXIF, DNG) and the key of the
        // noise store are those of the normal frames the picture is built from.
        for (ImageFrame f : images) {
            if (f.pair != null && !f.pair.isHighlightFrame && !f.pair.isLongFrame && f.measuredExposure > 0) {
                double normalSeconds = com.particlesdevs.photoncamera.processing.parameters.ExposureIndex.time2sec(f.measuredExposure);
                if (Math.abs(normalSeconds - processingParameters.exposureTime) > 1e-9)
                    Log.i(TAG, "Exposure time of the normal frames " + normalSeconds + " s instead of the last callback's "
                            + processingParameters.exposureTime + " s");
                processingParameters.exposureTime = normalSeconds;
                break;
            }
        }
        refineBlackFromDarkestFrame(processingParameters, images);
        processingParameters.cameraRotation = cameraRotation;
        processingStage = "frame selection";

        ParseExif.syncWithParameters(exifData, processingParameters);

        // P267: «Наш ремозаик» off and a Quad / Tetra shot (ScamHybridBurst.lastDngMosaic): the merged mosaic DNG alone, no photo
        boolean mosaicDngOnly = false;
        // P318: the colour blocks of the merged DNG (lastDngMosaic) and of the reference frame's RAW (the stream's)
        int mergedDngBlock = 1, referenceDngBlock = 1;
        {
            processingStage="SCAM HDR neural burst";
            try {
                // The oldest N frame calibrates the shot (as before); P27: without any N frame the frame closest to N does.
                ImageFrame scamReference = images.stream()
                        .filter(f -> !f.pair.isHighlightFrame && !f.pair.isLongFrame && f.getCaptureRole() == ImageFrame.CaptureRole.NORMAL)
                        .findFirst().orElse(null);
                if (scamReference == null) {
                    final ArrayList<ImageFrame> candidates = images;
                    scamReference = candidates.stream().filter(f -> f.getCaptureRole() == ImageFrame.CaptureRole.LONG)
                            .min(Comparator.comparingDouble(f -> (double) f.measuredExposure * f.measuredIso))
                            .orElseGet(() -> candidates.stream().max(Comparator.comparingDouble(f -> (double) f.measuredExposure * f.measuredIso)).get());
                    Log.w("SCAM_HDR", "no N frame in the burst: frame " + scamReference.number + " (" + scamReference.getCaptureRole()
                            + ") calibrates the shot");
                }
                images.remove(scamReference);
                images.add(0, scamReference);
                CaptureResult referenceMetadata = scamReference.getMatchedCaptureMetadata();
                if (referenceMetadata == null)
                    throw new IllegalStateException(Lang.t("SCAM HDR: нет метаданных опорного RAW timestamp=", "SCAM HDR: no metadata of the reference RAW timestamp=")
                            + scamReference.timestamp);
                captureResult = referenceMetadata;
                captureRequest = referenceMetadata.getRequest();
                processingParameters.FillDynamicParameters(referenceMetadata, captureRequest,
                        scamReference.measuredIso);
                refineBlackFromDarkestFrame(processingParameters, images);
                if (dcgShot) dcgLinearity(processingParameters, images, scamReference);
                applyMosaicNeutral(processingParameters, scamReference, referenceMetadata, images, characteristics, iszAspect4x3);
                ParseExif.syncWithParameters(exifData, processingParameters);
                Log.i("SCAM_HDR", "Reference calibration timestamp=" + scamReference.timestamp
                        + " ISO=" + processingParameters.iso
                        + " exposureSeconds=" + processingParameters.exposureTime);
                // P129: SHDR is the only merge (the SCAM HDR neural graph and its mosaic modes are gone)
                {
                    processingStage = "Hybrid merge";
                    com.particlesdevs.photoncamera.processing.ShotTimeline.mark("hybrid_call");
                    // P212d: only this shot's merge jobs give row profiles (a shot merged without the worker gives none)
                    com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralClient.lastRowProfiles = java.util.Collections.emptyList();
                    // P220: the merge worker's heartbeat moves the ring (MergeProgress, from ScamNeuralClient's reader thread)
                    com.particlesdevs.photoncamera.processing.MergeProgress.listener =
                            share -> progress(com.particlesdevs.photoncamera.processing.ProcessingProgress.merge(share));
                    com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLab.beginShot(hybridShot); // P277: «Сохранять снимки для лаборатории»
                    try {
                        scamOwnedOutput = com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.process(
                                ScameraApp.getAppContext(), images, processingParameters, saveRAW >= 1 && alignAlgorithm != 2);
                    } finally {
                        com.particlesdevs.photoncamera.processing.MergeProgress.listener = null;
                    }
                    // P320: the DCG shot's own two noise zones (the worker measured them on the merged frames)
                    if (dcgShot) {
                        final float[] zones = com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastDcgNoise;
                        processingParameters.dcgNoise = zones == null ? null : zones.clone();
                        Log.i("SCAM_HDR", "DCG noise " + (zones == null ? "not measured: the camera's model x the merge's factor"
                                : java.util.Arrays.toString(zones) + " (slope, high-gain offset, low-gain offset, knee)"));
                        if (zones == null) {
                            // the owner's rule is two noise zones for DCG: a shot the merge could not measure says so
                            try {
                                processingEventsListener.onProcessingChanged(Lang.t("DCG: шум не измерен (мало ровных участков) — одна кривая шума",
                                        "DCG: noise not measured (too few flat areas): one noise curve"));
                            } catch (RuntimeException e) {
                                Log.d(TAG, "DCG noise notice: " + e);
                            }
                        }
                    }
                    mergedDngBlock = com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastDngMosaic;
                    referenceDngBlock = com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastStreamBlock;
                    mosaicDngOnly = mergedDngBlock > 1;
                    // P190: a FULLRES series the merge cut (the worker, a fallback tier, the memory budget) is said aloud
                    final String notice = com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.takeSeriesNotice();
                    if (notice != null) {
                        try { processingEventsListener.onProcessingChanged(notice); }
                        catch (RuntimeException e) { Log.d(TAG, "series notice: " + e); }
                    }
                    // P212d: the ultrashort frames' row profiles vote on the lamp flicker (MainsFlicker «Авто»), at the reference
                    // frame's rolling shutter skew; nothing happens when the burst had none (no ultrashort frame, no profile)
                    com.particlesdevs.photoncamera.capture.RowFlicker.afterMerge(
                            com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralClient.lastRowProfiles,
                            rollingSkew(referenceMetadata));
                }
                scamOutputParameters=processingParameters;
                shotClipFlags=com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamRgb.lastClipFlags;
                shotEffectiveFrames=com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastEffectiveFrames;
                stage(0.55f); // P158: merged
                processingParameters.scamRgb=scamOwnedOutput;
                processingParameters.scamRgbOwned=true;
                processingParameters.scamHdrRawScale=1f;
                Log.i("SCAM_HDR","Original model capture completed; RGB goes directly to WB/LSC/tone. DNG retains the reference RAW.");
            } catch(Exception e) {
                throw new IllegalStateException("SCAM capture failed: "+e.getMessage(),e);
            }
        }
        ImageFrame ref=images.get(0);
        ByteBuffer output=ref.buffer;ref.buffer=null;
        referenceRaw = output;
        if ((long) width * height > 16_000_000L) output = outOfArena(output);
        referenceRaw = output;
        com.particlesdevs.photoncamera.processing.ShotTimeline.mark("ref_copy"); // P236
        releaseFrames(images);
        ByteBuffer scamMergedDng=com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastMergedDng;
        com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastMergedDng=null;
        Log.d(TAG, "Alignment elapsed:" + (System.currentTimeMillis() - startTime) + " ms");
        if (mosaicDngOnly || ((saveRAW >= 1) && alignAlgorithm != 2)) {
            boolean imageSaved = false;
            // P243: the DNG carries the JPEG's white balance and colour matrix (DngColour); scam_dev dng_colour_jpeg 0: the camera's
            // dual-illuminant profile as before. P370 (review): a camera whose own ColorMatrix a DNG cannot carry (all zero without
            // the tags, singular) gets the JPEG's profile with dng_colour_jpeg 0 too
            if ((PreferenceKeys.scamDevSwitch("dng_colour_jpeg", true) || !processingParameters.dngColourMatrices())
                    && processingParameters.CCT != null) {
                processingParameters.dngColour = com.particlesdevs.photoncamera.processing.color.DngColour.jpegProfile(
                        processingParameters.sensorToProPhoto, processingParameters.CCT.matrix, processingParameters.whitePoint,
                        processingParameters.sceneCct);
                if (processingParameters.dngColour == null)
                    Log.w(TAG, "DNG colour: the JPEG's matrices are not usable, the camera's profile is written");
            }
            try {
            // P258 / P267 (owner, 2026-10-19: «Наш рав проявляется нормально в нашем приложение Scam Remosaic, нам нужно это
            // исправить чтобы при съемке без ремозаика мы могли применить нейроремозаик в нём»; «При выкл сохраняй только днг без
            // ремозаика»; «Склейка должны быть, просто без ремозаика»): a Quad / Tetra shot without our remosaic is the merged
            // mosaic DNG alone (the worker's dngMosaic: the sensor's layout, uncompressed - Scam Remosaic's single-DNG input for its
            // neural remosaic); a merge that gave none (the CPU tier) leaves the reference frame's mosaic as captured.
            final boolean mosaicDng = mosaicDngOnly;
            if (mosaicDng) Log.i("SCAM_HDR", "DNG: " + (scamMergedDng != null ? "the merged" : "the reference frame's (no merged RAW)")
                    + " mosaic " + processingParameters.rawSize.x + "x" + processingParameters.rawSize.y
                    + " (no remosaic: the DNG alone, for the neural remosaic of Scam Remosaic)");
            if (scamMergedDng != null) {
                // The whole burst's merge (the N frames, the bracketed and the ultrashort ones; nothing else, no denoise),
                // P165: 16 bit over its headroom, BaselineExposure log2(headroom) keeps its brightness.
                float[] black = processingParameters.blackLevel.clone();
                int white = processingParameters.whiteLevel;
                float k = 65535f / white;
                final float headroom = Math.max(1f, com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastMergedDngHeadroom);
                for (int i = 0; i < 4; i++) processingParameters.blackLevel[i] = Math.round(black[i] * k);
                processingParameters.whiteLevel = 65535;
                processingParameters.dngBaselineExposure = headroom > 1.0001f ? (float) (Math.log(headroom) / Math.log(2)) : 0f;
                try {
                    // P214c: NoiseProfile of what the file holds - the merge's level over the headroom, averaged over the frames
                    // the worker merged (DngMetadata.noiseProfile); without the worker's frames the legacy computeModel stands
                    // for them (base / FrameNumberSelector.frameCount, which nothing sets: the one-frame model)
                    final double mergeFrames = com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastMergeFrames;
                    final boolean framesKnown = Double.isFinite(mergeFrames) && mergeFrames >= 1;
                    final com.particlesdevs.photoncamera.processing.render.NoiseModeler nm = processingParameters.noiseModeler;
                    final double[] camera = nm == null ? null : com.particlesdevs.photoncamera.processing.DngMetadata.planes(framesKnown ? nm.baseModel : nm.computeModel);
                    // P320: a DCG shot with measured zones: (S, O_hcg) per plane (DngMetadata.dcgPlanes, the same channel ratios,
                    // frames and headroom), its zones the burst's own noise, so not times the merge's factor; without zones as before
                    final float[] dcgZones = processingParameters.dcgShot
                            ? com.particlesdevs.photoncamera.processing.render.NoiseModeler.dcgZones(processingParameters.dcgNoise) : null;
                    final double[] baseG = nm == null ? null : com.particlesdevs.photoncamera.processing.DngMetadata.planes(nm.baseModel);
                    final double[] single = dcgZones != null ? com.particlesdevs.photoncamera.processing.DngMetadata.dcgPlanes(dcgZones, camera,
                            baseG != null ? baseG[2] : Double.NaN, baseG != null ? baseG[3] : Double.NaN) : camera;
                    final float noiseFactor = dcgZones != null ? 1f : com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastNoiseFactor;
                    if (processingParameters.dcgShot) Log.i("SCAM_HDR", "DNG: NoiseProfile of a DCG shot: " + (dcgZones != null
                            ? "slope and high-gain offset of the measured zones" : "no measured zones, the camera's single curve"));
                    if (single != null) processingParameters.dngNoiseProfile = com.particlesdevs.photoncamera.processing.DngMetadata.noiseProfile(
                            single, noiseFactor, headroom, framesKnown ? mergeFrames : 1, 1);
                    processingParameters.dngMosaicBlock = mergedDngBlock;
                    imageSaved = mosaicDng ? ImageSaver.Util.saveStackedRaw(dngFile, scamMergedDng, processingParameters, true)
                            : ImageSaver.Util.saveStackedRaw(dngFile, scamMergedDng, processingParameters);
                    Log.i("SCAM_HDR", String.format(java.util.Locale.ROOT, "DNG: merged burst RAW (N, bracketed and ultrashort frames), "
                            + "16-bit, headroom x%.3f, BaselineExposure %+.2f EV", headroom, processingParameters.dngBaselineExposure));
                    final double[] np = processingParameters.dngNoiseProfile;
                    if (np != null && single != null) Log.i("SCAM_HDR", String.format(java.util.Locale.ROOT, "DNG: NoiseProfile G %.4g / %.4g (frames %s, "
                            + "headroom x%.3f, noise x%.3f; one frame %.4g / %.4g)", np[2], np[3],
                            framesKnown ? String.format(java.util.Locale.ROOT, "%.2f", mergeFrames) : "unknown: legacy model", headroom,
                            noiseFactor, single[2], single[3]));
                } finally {
                    System.arraycopy(black, 0, processingParameters.blackLevel, 0, 4);
                    processingParameters.whiteLevel = white;
                    processingParameters.dngBaselineExposure = 0f;
                    processingParameters.dngNoiseProfile = null;
                    processingParameters.dngMosaicBlock = 1;
                    Allocator.free(scamMergedDng);
                }
            } else {
                // P214c: the reference RAW is one frame as the sensor gave it: the one-frame model (H, N, sites = 1), with the
                // worker's measured correction of it when it made one (1 after the CPU fallback)
                final com.particlesdevs.photoncamera.processing.render.NoiseModeler nm = processingParameters.noiseModeler;
                final double[] camera = nm == null ? null : com.particlesdevs.photoncamera.processing.DngMetadata.planes(nm.baseModel);
                // P320: a DCG shot with measured zones: (S, O_hcg) per plane of the one frame, not times the merge's factor
                final float[] dcgZones = processingParameters.dcgShot
                        ? com.particlesdevs.photoncamera.processing.render.NoiseModeler.dcgZones(processingParameters.dcgNoise) : null;
                final double[] single = dcgZones != null ? com.particlesdevs.photoncamera.processing.DngMetadata.dcgPlanes(dcgZones, camera,
                        camera != null ? camera[2] : Double.NaN, camera != null ? camera[3] : Double.NaN) : camera;
                if (processingParameters.dcgShot) Log.i("SCAM_HDR", "DNG: NoiseProfile of a DCG reference frame: " + (dcgZones != null
                        ? "slope and high-gain offset of the measured zones" : "no measured zones, the camera's single curve"));
                if (single != null) processingParameters.dngNoiseProfile = com.particlesdevs.photoncamera.processing.DngMetadata.noiseProfile(
                        single, dcgZones != null ? 1f : com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastNoiseFactor, 1, 1, 1);
                processingParameters.dngMosaicBlock = referenceDngBlock;
                try {
                    // P258: the mosaic as captured goes uncompressed (Scam Remosaic's reader: 16-bit CFA, no LinearizationTable)
                    imageSaved = mosaicDng ? ImageSaver.Util.saveSingleRaw(dngFile, output, processingParameters, true)
                            : ImageSaver.Util.saveStackedRaw(dngFile, output, processingParameters);
                } finally {
                    processingParameters.dngNoiseProfile = null;
                    processingParameters.dngMosaicBlock = 1;
                }
            }
            } catch (RuntimeException | OutOfMemoryError dngError) {
                // P27: the DNG is optional, the JPEG is not.
                Log.e(TAG, "DNG not saved: " + stackTrace(dngError));
            } finally {
                processingParameters.dngColour = null; // P243: only these DNG writes
            }
            reportDng(processingEventsListener, imageSaved, saveRAW, mosaicDngOnly, dngFile);
            if (mosaicDngOnly) {
                try {
                    processingEventsListener.onProcessingChanged(Lang.t("«Наш ремозаик» выключен: сохранён только DNG склеенной мозаики",
                            "«Our remosaic» is off: only the merged mosaic DNG is saved"));
                } catch (RuntimeException e) { Log.d(TAG, "DNG-only notice: " + e); }
            }
            if (saveRAW == 2 || mosaicDngOnly) {
                processingEventsListener.onProcessingFinished("RAW processing finished");
                callback.onFinished();
                referenceRaw = null;
                Allocator.free(output);
                Allocator.getMemoryCount();
                return;
            }
        }

        double effective=Double.isFinite(processingParameters.effectiveStackSamples)
                ? processingParameters.effectiveStackSamples
                : processingParameters.multiFrameCount>0?processingParameters.multiFrameCount:1;
        // The ordinary merge has spatially rejected donors but no weight map:
        // do not pretend every pixel received images.size() independent samples.
        // Allocator binning sums 2x2 pixels; white/black are scaled by four,
        // so normalized variance falls by four (independence assumption).
        processingParameters.noiseModeler.computeStackingNoiseModel(effective,Allocator.binning?4:1);

        // The autonomous mode owns denoising; do not run a second AI/vendor pass.
        if (processingParameters.scamHdrMode) {
            double scale=processingParameters.scamHdrRawScale;
            for (int c=0;c<processingParameters.noiseModeler.computeModel.length;c++) {
                android.util.Pair<Double,Double> n=processingParameters.noiseModeler.computeModel[c];
                processingParameters.noiseModeler.computeModel[c]=new android.util.Pair<>(n.first*scale,n.second*scale*scale);
            }
        }

        processingStage = "RAW post-processing";
        // P179 (200 MP: LOW_MEMORY): the hybrid's pipeline takes the merged RGB (scamRgbOwned), not the reference RAW, which is
        // only held for the post_ab comparison now: above 16 MP it goes before the pipeline (401 MB at 200 MP).
        if (hybridShot && processingParameters.scamRgbOwned && (long) width * height > 16_000_000L
                && !com.particlesdevs.photoncamera.processing.opengl.postpipeline.PostAb.wanted(processingParameters,
                        lookAtPress != null ? lookAtPress.film : com.particlesdevs.photoncamera.processing.color.FilmSim.current())) {
            referenceRaw = null;
            Allocator.free(output);
            output = null;
        }
        ByteBuffer jpegInput = output;
        // SCAM HDR hybrid on the Sabre 2x grid: the RGB is larger than the sensor grid (the DNG above stayed sensor
        // size). Like mosaic SR, the pipeline size follows the RGB; the LSC map is sampled in normalised coordinates.
        // Only a hybrid shot has the hybrid's output size: the static keeps the last hybrid shot's size, which a SCAM HDR
        // shot (sensor size) must not take (it rendered the SCAM HDR RGB at the 2x size, black).
        final Point hybridOut = hybridShot ? com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastOutputSize : null;
        processingParameters.hybridFinalSize = null;
        if (hybridOut != null && (hybridOut.x != width || hybridOut.y != height)) {
            // 12/16/20 MP from the 2x grid: resized on the GPU at the end of the pipeline (HybridFinalResize); the CPU
            // resize below only remains as a fallback when the pipeline did not apply it.
            final Point fin = com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastFinalSize;
            if (fin != null && (long) fin.x * fin.y < (long) hybridOut.x * hybridOut.y) processingParameters.hybridFinalSize = new Point(fin.x, fin.y);
            final float sx = (float) hybridOut.x / width, sy = (float) hybridOut.y / height;
            processingParameters.rawSize = new Point(hybridOut.x, hybridOut.y);
            if (processingParameters.sensorPix != null)
                processingParameters.sensorPix = new Rect(Math.round(processingParameters.sensorPix.left * sx), Math.round(processingParameters.sensorPix.top * sy),
                        Math.round(processingParameters.sensorPix.right * sx), Math.round(processingParameters.sensorPix.bottom * sy));
            processingParameters.XPerMm *= sx; processingParameters.YPerMm *= sy;
            processingParameters.hotPixels = new Point[0];
            processingParameters.alignmentSize = new Point(hybridOut.x / processingParameters.tile + 1, hybridOut.y / processingParameters.tile + 1);
            processingParameters.tilesX = hybridOut.x / 800 + 1;
            processingParameters.outputScale = sx;
            Log.i("SCAM_HDR", "hybrid output " + hybridOut.x + "x" + hybridOut.y + " (scale " + sx + "): pipeline runs at the merged size");
        }
        // P369 (texture-zoom, outputs-3): the digital zoom's crop of this shot (taken at its press: ImageSaver.zoomResidual), and
        // the share of the image the photo keeps - the film's frame: the zoom's crop and the ISZ 4:3 crop below come after the
        // film's grain, scatter and halation. Before the lab's snapshot, which keeps it: a lab render of the uncropped field sizes
        // the film's effects as the photo had them, then and now alike.
        final float zoomCrop = zoomResidual;
        processingParameters.filmFrameScale = com.particlesdevs.photoncamera.processing.opengl.postpipeline.FilmHalation.filmFrameScale(
                zoomCrop, iszAspect4x3, processingParameters.rawSize);
        if (hybridShot) com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLab.snapshot(processingParameters, String.valueOf(processingParameters.physicalID)); // P277
        PostPipeline pipeline = new PostPipeline();
        openPipeline = pipeline;
        // P277: a lab shot's pipeline records the whole frame's statistics (the lab's fragments use them)
        if (hybridShot && com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLab.recording()) pipeline.record = new com.particlesdevs.photoncamera.processing.opengl.postpipeline.LabFrame();
        // «HEIC 10 бит»: the final image comes out RGBA_1010102 for the HEVC Main10 HEIC; every 8-bit file of the shot gets an
        // 8-bit copy at its encode (PhotoOutput). The same for a 10 / 12-bit or lossless AVIF, which takes the 10 bits as they
        // are. Off: the pipeline output is the ARGB_8888 bitmap as before.
        pipeline.tenBitOutput = com.particlesdevs.photoncamera.processing.heif.Heic10Support.wanted()
                || com.particlesdevs.photoncamera.processing.avif.AvifEncoder.tenBitImageWanted();
        // P46 «Цветовое пространство»: Display P3 renders the final image in P3 primaries; every file of the shot says so.
        final com.particlesdevs.photoncamera.processing.color.OutputColour.Space colourSpace = ScameraApp.getSettings().colourSpace;
        pipeline.p3Output = colourSpace == com.particlesdevs.photoncamera.processing.color.OutputColour.Space.DISPLAY_P3;
        // owner, 2026-10-28: the lens filter, film, LUT and «Цвет» of the press (the tray may have changed them during the merge)
        pipeline.lensFilter = lensFilterAtPress;
        pipeline.look = lookAtPress;
        // P369: the shot's film snapshot (the press's), before the teardown decision and the pipeline (every film gate reads it)
        pipeline.takeFilm();
        // W1.3: at 16 MP or less without Ultra HDR the GL teardown waits until the JPEG is saved (finishDeferredTeardown).
        pipeline.deferTeardown = PostPipeline.canDeferTeardown(processingParameters, pipeline.film);
        if (pipeline.deferTeardown) { deferredPipeline = pipeline; openPipeline = null; }
        final boolean postAb = com.particlesdevs.photoncamera.processing.opengl.postpipeline.PostAb.wanted(processingParameters, pipeline.film);
        final int priorityBefore = raisePostPriority();
        Bitmap img;
        try {
            img = postAb ? com.particlesdevs.photoncamera.processing.opengl.postpipeline.PostAb.run(pipeline, jpegInput, processingParameters)
                    : pipeline.Run(jpegInput, processingParameters);
        } finally {
            restorePriority(priorityBefore);
        }
        com.particlesdevs.photoncamera.processing.ShotTimeline.mark("post_done");
        com.particlesdevs.photoncamera.util.ThermalPolicy.processing(false); // P353: the GPU work of the shot is done (the encoders are CPU)
        com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamLab.preview(img, pipeline.record); // P277: the lab shot's photo (before the zoom / ISZ crops)
        stage(0.85f); // P158: processed
        // Ultra HDR (settings.ultraHdr is on only when the shot writes a JPEG) and the HDR HEIC / AVIF (P46, settings.hdrOutput)
        // take the same gain map (settings.gainMapPass). Ultra HDR: the gain-map pass runs on the bitmap exactly
        // as the pipeline produced it - the pass checks the base against the pipeline size. It used to run after the hybrid
        // resize below, so every hybrid shot on the 2x grid with a 12/16/20 MP output failed the check and was saved as SDR,
        // and the digital zoom crop was skipped with Ultra HDR on. Now the map follows the resize and the crop instead.
        // Must run before the raw frame buffer is freed.
        PostPipeline.GainMapRaw gm = null;
        // P369 (outputs-1): no gain map with a film - the photo is saved in SDR
        if (ScameraApp.getSettings().gainMapPass() && pipeline.film != null) Log.i(TAG, "HDR off: film simulation " + pipeline.film.stock.id);
        if (PostPipeline.gainMapWanted(ScameraApp.getSettings().gainMapPass(), pipeline.film)) {
            processingStage = "Ultra HDR gain map";
            try {
                gm = pipeline.RunHDRGainMap(jpegInput, processingParameters, img,
                        GainMapComputer.SCALE_DOWN, GainMapComputer.SCALE);
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "Ultra HDR gain-map pass failed, falling back to SDR JPEG", e);
            }
            Log.i(TAG, "Ultra HDR gain map " + (gm != null ? gm.w + "x" + gm.h + " for " + img.getWidth() + "x" + img.getHeight() : "not produced"));
        }
        // SCAM HDR hybrid on the Sabre 2x grid: the whole pipeline (including sharpening) ran on the 2x image; the
        // final size (12/16/20 MP, or the sensor size) is produced here, keeping the bitmap's aspect and rotation.
        final Point hybridFinal = hybridOut != null ? com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastFinalSize : null;
        // P226 «Увеличивать кадр с кропом» (owner, 2026-10-19: «настройка ... не работает»): the digital zoom's crop was taken
        // after the resize to the photo size and never brought back - the switch changed nothing. On, the crop comes first
        // (on the merge's full image, the 2x grid's detail kept) and the cropped image goes to the photo size, up if need be.
        // P369: zoomCrop is the shot's, read before the pipeline
        final Point cropTarget = zoomCrop > 1.005f && PreferenceKeys.cropUpscale() ? photoSize(img, hybridFinal) : null;
        if (cropTarget == null && hybridFinal != null && (long) hybridFinal.x * hybridFinal.y < (long) img.getWidth() * img.getHeight()) {
            final double s = Math.sqrt((double) hybridFinal.x * hybridFinal.y / ((double) img.getWidth() * img.getHeight()));
            final int tw = Math.max(2, (int) Math.round(img.getWidth() * s)) & ~1, th = Math.max(2, (int) Math.round(img.getHeight() * s)) & ~1;
            processingStage = "SCAM HDR: resize " + img.getWidth() + "x" + img.getHeight() + " -> " + tw + "x" + th;
            try {
                Bitmap reduced = com.particlesdevs.photoncamera.processing.ml.ScamPostDownscale.resizeTo(img, tw, th, PreferenceKeys.hybridDownsampler());
                if (reduced != img) { img.recycle(); img = reduced; }
                Log.i("SCAM_HDR", "hybrid final size " + tw + "x" + th + " (" + PreferenceKeys.hybridDownsamplerName() + ")");
            } catch (Throwable resizeError) {
                Log.e(TAG, "hybrid resize failed; keeping the 2x image", resizeError);
            }
            if (gm != null) {
                try {
                    gm = gm.resizedFor(img.getWidth(), img.getHeight());
                } catch (RuntimeException | OutOfMemoryError e) {
                    Log.e(TAG, "Ultra HDR gain map resize failed, SDR JPEG", e);
                    gm = null;
                }
            }
        }
        if (zoomCrop > 1.005f) {
            processingStage = "digital zoom crop";
            img = com.particlesdevs.photoncamera.control.ZoomController.crop(img, zoomCrop);
            if (gm != null) gm = gm.croppedBy(zoomCrop);
        }
        if (cropTarget != null && (cropTarget.x != img.getWidth() || cropTarget.y != img.getHeight())) {
            processingStage = "crop to the photo size " + img.getWidth() + "x" + img.getHeight() + " -> " + cropTarget.x + "x" + cropTarget.y;
            try {
                final Bitmap scaled = com.particlesdevs.photoncamera.processing.ml.ScamPostDownscale.scaleTo(img, cropTarget.x, cropTarget.y);
                if (scaled != img) { img.recycle(); img = scaled; }
                if (gm != null) gm = gm.resizedFor(img.getWidth(), img.getHeight());
                Log.i("SCAM_HDR", "crop " + zoomCrop + "x brought to the photo size " + img.getWidth() + "x" + img.getHeight() + " (P226)");
            } catch (Throwable scaleError) {
                Log.e(TAG, "crop upscale failed; keeping the cropped image", scaleError);
            }
        }
        if (iszAspect4x3) {
            processingStage = "ISZ 4:3 crop";
            final int baseWidth = img.getWidth(), baseHeight = img.getHeight();
            android.graphics.Rect crop = com.particlesdevs.photoncamera.processing.IszAspectCrop.bounds(baseWidth, baseHeight);
            Bitmap cropped = com.particlesdevs.photoncamera.processing.IszAspectCrop.crop(img, crop);
            if (cropped != img) {
                if (gm != null) try {
                    gm = gm.croppedTo(crop, baseWidth, baseHeight);
                } catch (RuntimeException | OutOfMemoryError failure) {
                    Log.e(TAG, "ISZ gain-map crop failed, SDR output", failure);
                    gm.bitmap.recycle();
                    gm = null;
                }
                img.recycle();
                img = cropped;
                Log.i("SCAM_HDR", "ISZ output 4:3 " + img.getWidth() + "x" + img.getHeight() + "; native RAW retained");
            }
        }
        processingStage = "image encoding";

        final long tailFree = System.nanoTime(); // P33: where post_done -> encode goes (log only)
        // P33: the last reference of the shot arena unmaps it (~0.9 GB at 30 frames: 150-200 ms on the OPPO): up to 16 MP on a
        // helper thread while the JPEG is encoded (nothing reads these buffers any more); above it here, as before (memory).
        referenceRaw = null; // freed here
        if ((long) width * height <= 16_000_000L) {
            final ByteBuffer freeInput = jpegInput, freeOutput = jpegInput != output ? output : null;
            BUFFER_FREE.execute(() -> {
                Allocator.free(freeInput);
                if (freeOutput != null) Allocator.free(freeOutput);
            });
        } else {
            Allocator.free(jpegInput);
            if (jpegInput != output) Allocator.free(output);
        }
        final long tailOverlay = System.nanoTime();

        final Bitmap withDebug = overlay(img, pipeline.debugData.toArray(new Bitmap[0]));
        if (withDebug != img) { img.recycle(); img = withDebug; }
        // «Отметка ES / L на фото»: whether the ultrashort and long frames went into this photo, or a short code why not
        final String[] stamp = com.particlesdevs.photoncamera.processing.MergeStamp.lines(planNote,
                com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastEsCaptured,
                com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastEsSent,
                com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastLongCaptured,
                com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastLongSent,
                com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.lastReport);
        Log.i("SCAM_PIPELINE", "merge stamp: " + stamp[0] + " | " + stamp[1]);
        if (com.particlesdevs.photoncamera.processing.MergeStamp.on()) {
            final Bitmap stamped = com.particlesdevs.photoncamera.processing.MergeStamp.draw(img, stamp);
            if (stamped != img) { img.recycle(); img = stamped; }
        }
        // The EGL context and the rest of the GL state are not needed for the encoding: release them before it, unless the
        // teardown waits for the end of the encode (W1.3, 16 MP or less).
        if (deferredPipeline == null) try {
            openPipeline = null;
            pipeline.close();
        } catch (Exception e) {
            Log.e(TAG, "PostPipeline close failed (non-fatal): " + Log.getStackTraceString(e));
        }
        final long tailFinished = System.nanoTime();
        try {
            processingEventsListener.onProcessingFinished("Processing finished");
        }
        catch (Exception e){
            Log.d(TAG,"Error in processingEventsListener.onProcessingFinished:"+Log.getStackTraceString(e));
        }
        Log.d(TAG, "Shot tail ms: free=" + (tailOverlay - tailFree) / 1000000 + " overlay+close=" + (tailFinished - tailOverlay) / 1000000
                + " finished=" + (System.nanoTime() - tailFinished) / 1000000);
        com.particlesdevs.photoncamera.processing.ShotTimeline.mark("encode");
        GainMapComputer.Result gain = null;
        if (gm != null) {
            // P145: a map the GPU already requantised is the result itself (its bitmap stays); the CPU normalises the others
            final boolean gpu = !Float.isNaN(gm.normalisedMax);
            try {
                gain = gpu ? GainMapComputer.normalised(gm.bitmap, gm.normalisedMax) : GainMapComputer.compute(gm.bitmap, gm.down, gm.scale);
                Log.i(TAG, "Ultra HDR gain map: min=" + gain.gainMapMin + " max=" + gain.gainMapMax + " (log2) " + gain.gainW + "x" + gain.gainH
                        + (gpu ? " normalised on the GPU" : ""));
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "Ultra HDR gain map normalisation failed, falling back to SDR JPEG", e);
            } finally {
                if (!gpu || gain == null) gm.bitmap.recycle();
            }
        }
        // imageFile has no extension yet: PhotoOutput names the files by the chosen format (.jpg / .heic / .webp).
        // P46: the gain map makes the JPEG an Ultra HDR JPEG only with Ultra HDR in effect, and the HEIC / AVIF HDR only with
        // «HDR в HEIC / AVIF» in effect; without either option this is the call as before (sRGB, the map for the JPEG).
        final boolean hdrOutput = ScameraApp.getSettings().hdrOutput;
        final com.particlesdevs.photoncamera.processing.PhotoOutput.Result saved = hdrOutput || pipeline.p3Output
                ? com.particlesdevs.photoncamera.processing.PhotoOutput.save(imageFile, img, exifData,
                        ScameraApp.getSettings().ultraHdr ? gain : null, hdrOutput ? gain : null, colourSpace)
                : com.particlesdevs.photoncamera.processing.PhotoOutput.save(imageFile, img, exifData, gain);
        if (gain != null) gain.gainMap.recycle();
        stage(0.97f); // P158: saved
        final java.util.List<Path> savedFiles = saved.notifyOrder();
        if (savedFiles.isEmpty()) savedFiles.add(com.particlesdevs.photoncamera.processing.PhotoFormat.JPEG.fileFor(imageFile));
        for (Path file : savedFiles) {
            try {
                processingEventsListener.notifyImageSavedStatus(!saved.files.isEmpty(), file);
            }
            catch (Exception e){
                Log.d(TAG,"Error in processingEventsListener.notifyImageSavedStatus:"+Log.getStackTraceString(e));
            }
        }
        com.particlesdevs.photoncamera.processing.ShotTimeline.mark("saved");
        finishDeferredTeardown();

        Allocator.getMemoryCount();
        callback.onFinished();
    }

    /**
     * P121: a Quad / Tetra stream (an ISZ module without the sensor's remosaic): the camera's neutral is not the scene's (its ISP
     * reads the mosaic as Bayer), so the neutral of the reference frame is measured and put on the camera's curve learned in
     * plain-Bayer sessions (MosaicNeutral). scam_dev.txt "mosaic_awb 0": the camera's neutral as before. P195: also with «Наш
     * ремозаик» off - the stream (and the ISP's misread neutral) is the same mosaic, only the worker bins it instead of remosaicking.
     * P212f: a hand-set white balance (P201) holds (W2 = M3); the curve is the smooth counted locus with the camera's DNG prior
     * (W5 = M13 phase 1); the robust statistic of up to 3 N frames (W4 = M10) is logged beside the P121 one and taken with
     * scam_dev.txt "mosaic_awb_v2 1"; the ISP teacher's prediction is logged only (W5 phase 2). P366: in a RAW-only session whose ISP
     * runs no 3A (no route; MosaicNeutralResolver.ispIdle) the teacher's neutral - the camera's own grid, else a same-sensor
     * camera's - blended with the locus when it has learned enough (MosaicTeacher.use, MosaicNeutral.taught).
     */
    private static void applyMosaicNeutral(Parameters p, ImageFrame ref, CaptureResult meta, java.util.List<ImageFrame> frames,
                                           CameraCharacteristics characteristics, boolean aspect4x3) {
        if (!PreferenceKeys.scamDevSwitch("mosaic_awb", true) || !PreferenceKeys.mosaicWbCorrections() // P223
                || p.rawBinning > 1 || ref == null || ref.buffer == null
                || ref.buffer.capacity() < (long) ref.width * ref.height * 2) return;
        int block = com.particlesdevs.photoncamera.processing.MosaicBlockStore.blockForShot(0);
        if (block <= 0) {
            final float black = (p.blackLevel[0] + p.blackLevel[1] + p.blackLevel[2] + p.blackLevel[3]) / 4f;
            final com.particlesdevs.photoncamera.processing.MosaicBlockDetector.Result r =
                    com.particlesdevs.photoncamera.processing.MosaicBlockDetector.detect(ref.buffer, ref.width, ref.height, ref.width * 2,
                            black, p.whiteLevel, 8);
            block = r.confident ? r.block : 1;
        }
        if (block <= 1) return;
        // P212g (W7 = M12): every colour computation of the shot from here on may take the camera's plain-session matrix
        p.mosaicShot = true;
        // P212f (W2 = M3): a hand-set white balance is the shot's white point (the photo and the DNG's AsShotNeutral), as on a
        // 12 MP shot - the gains at the press, else a result that says AWB off; ownNeutral stays false
        if (p.keepHandSetWhiteBalance(meta)) {
            p.ReCalcColor(true, meta);
            Log.i("SCAM_HDR", String.format(java.util.Locale.ROOT, "mosaic white balance: colour block %d, hand-set white balance kept %.3f / %.3f (%s)",
                    block, p.whitePoint[0] / p.whitePoint[1], p.whitePoint[2] / p.whitePoint[1], p.handSetSource));
            return;
        }
        com.particlesdevs.photoncamera.processing.color.MosaicNeutral.init(ScameraApp.getAppContext());
        final String id = String.valueOf(p.physicalID);
        // P212f (W5): the camera's DNG locus for a curve of fewer than 2 points; the curve itself once a process (offline replay)
        final com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Prior prior =
                com.particlesdevs.photoncamera.processing.color.MosaicNeutral.prior(characteristics, id);
        if (CURVE_LOGGED.add(id))
            Log.i("SCAM_HDR", "mosaic white balance curve of camera " + id + ": "
                    + com.particlesdevs.photoncamera.processing.color.MosaicNeutral.describe(id, prior));
        // P212f (W4 = M10): the robust statistic of up to 3 N frames (logged beside the P121 one; "mosaic_awb_v2 1": the photo's)
        com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Stat stat = null;
        try {
            stat = mosaicStatistic(p, ref, frames, block, aspect4x3);
        } catch (RuntimeException e) {
            Log.w("SCAM_HDR", "mosaic white balance statistic: " + e);
        }
        final boolean v2 = stat != null && PreferenceKeys.scamDevSwitch("mosaic_awb_v2", false);
        final com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Result own = v2
                ? com.particlesdevs.photoncamera.processing.color.MosaicNeutral.resolve(id, stat, prior)
                : com.particlesdevs.photoncamera.processing.color.MosaicNeutral.estimate(id, ref.buffer,
                        ref.width, ref.height, ref.width * 2, p.baseCfaPattern, p.blackLevel, p.whiteLevel, block, prior);
        final String camera = String.format(java.util.Locale.ROOT, "%.3f / %.3f", p.whitePoint[0] / p.whitePoint[1], p.whitePoint[2] / p.whitePoint[1]);
        if (stat != null) Log.i("SCAM_HDR", "mosaic white balance statistic" + (v2 ? "" : " (log only)") + ": " + stat);
        // P212f (W5 phase 2): what the ISP would choose for this statistic; P366: its own grid, else a same-sensor camera's (X200U
        // cameras 3 and 4), taken below in a RAW-only session whose ISP runs no 3A
        com.particlesdevs.photoncamera.processing.color.MosaicTeacher.Use teacher = null;
        if (stat != null && com.particlesdevs.photoncamera.processing.color.MosaicTeacher.on()) {
            com.particlesdevs.photoncamera.processing.color.MosaicTeacher.init(ScameraApp.getAppContext());
            teacher = com.particlesdevs.photoncamera.processing.color.MosaicTeacher.use(id, stat.r, stat.b,
                    com.particlesdevs.photoncamera.processing.color.MosaicTeacher.sameSensor(id, characteristics, ScamProcessor::cameraCharacteristics));
            Log.i("SCAM_HDR", String.format(java.util.Locale.ROOT, "WB TEACHER camera %s: m %.3f / %.3f -> %s", id, stat.r, stat.b,
                    teacher == null ? "nothing learned near it (" + com.particlesdevs.photoncamera.processing.color.MosaicTeacher.cells(id) + " cells)"
                            : teacher + (teacher.usable() ? "" : ", too little support for a photo")));
        }
        // P212g (W6 = M6): the vivo native routes, whose HAL 3A runs, keep the HAL's neutral when it is converged and on the camera's
        // curve, and P208's still-only route takes its normal-mode viewfinder's neutral at the press (MosaicNeutralResolver); ownNeutral
        // stays false, so the ISP's matrix (or the plain-session table) renders it. Our estimate above is logged only then.
        final com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.Press press = p.mosaicWbAtPress;
        if (press != null && press.route != com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.ROUTE_NONE) {
            final Integer awbState = meta == null ? null : meta.get(CaptureResult.CONTROL_AWB_STATE);
            final float[] hal = p.whitePoint.clone();
            final float distance = press.viewfinder != null ? Float.NaN
                    : com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.curveDistance(id, hal, prior);
            final com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.Decision d =
                    com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.decide(press, hal, awbState, distance);
            if (d != null) {
                p.customNeutral = d.neutral;
                p.ownNeutral = false;
                p.ReCalcColor(true, meta);
                Log.i("SCAM_HDR", "mosaic white balance: colour block " + block + ", " + d.how + "; own estimate "
                        + (own == null ? "none (too few usable cells)" : own.how) + " (log only)");
                return;
            }
            Log.i("SCAM_HDR", "mosaic white balance: colour block " + block + ", " + press + ": P121 ("
                    + com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.whyNot(press, hal, awbState, distance) + ")");
        }
        // P366 (owner, 2026-10-27: «вот такие фото получаю с исз, исправь»): without a route, a RAW-only session whose ISP runs no 3A
        // (the X200U's ISZ modules under the vivo SDK lease: the identity transform, gains frozen at R 2.434 / B 1.569) has nothing
        // but the projection, whose median error on the owner's log is 3 x the teacher's: the teacher's neutral blended with the
        // locus (scam_dev.txt "mosaic_awb_teacher_use": 1 default, 2 any route-less mosaic photo, 0 log only)
        if ((press == null || press.route == com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.ROUTE_NONE)
                && stat != null && teacher != null && teacher.usable()) {
            final int use = com.particlesdevs.photoncamera.processing.color.MosaicTeacher.useMode();
            final String idle = use == com.particlesdevs.photoncamera.processing.color.MosaicTeacher.USE_NEVER ? null
                    : com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.ispIdle(frameResults(frames));
            if (use == com.particlesdevs.photoncamera.processing.color.MosaicTeacher.USE_ALWAYS
                    || use == com.particlesdevs.photoncamera.processing.color.MosaicTeacher.USE_ISP_IDLE && idle != null) {
                final com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Result taught =
                        com.particlesdevs.photoncamera.processing.color.MosaicNeutral.taught(id, stat, prior, teacher);
                Log.i("SCAM_HDR", "mosaic white balance: colour block " + block + ", camera " + camera + " ("
                        + (idle != null ? idle : "mosaic_awb_teacher_use 2") + ") -> " + taught.how + "; P121 "
                        + (own == null ? "none (too few usable cells)" : own.how) + " (not used)");
                p.customNeutral = taught.neutral;
                p.ownNeutral = true;
                p.ReCalcColor(true, meta);
                return;
            }
            Log.i("SCAM_HDR", "WB TEACHER not used: " + (use == com.particlesdevs.photoncamera.processing.color.MosaicTeacher.USE_NEVER
                    ? "mosaic_awb_teacher_use 0" : "the ISP may run its 3A (" + (idle == null ? "not an idle RAW-only burst" : idle) + ")"));
        }
        if (own == null) {
            Log.i("SCAM_HDR", "mosaic white balance: colour block " + block + ", too few usable cells; the camera's neutral " + camera + " kept");
            p.ReCalcColor(false, meta); // P212g (W7): the camera's neutral, the colour matrix of a mosaic shot
            return;
        }
        // P315: an estimate far off every learned illuminant (the X8U ISZ shot's LED glow on a white case) is not taken as one; P366
        // (review): its distance from the curve the learned points fit, not from the one the slope guard projected it on
        final String offLocus = com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.offLocus(id, own.learnedDistance,
                p.whitePoint.clone(), prior);
        if (offLocus != null) {
            Log.i("SCAM_HDR", "mosaic white balance: colour block " + block + ", " + offLocus + "; own estimate " + own.how + " (not used)");
            p.ReCalcColor(false, meta);
            return;
        }
        Log.i("SCAM_HDR", "mosaic white balance: colour block " + block + ", camera " + camera + " -> " + own.how);
        float[] neutral = own.neutral;
        // P212e (W3 = M7): an OPPO native shot's reference frame has the viewfinder's white balance recorded at its timestamp (the
        // session's gated filter): its own estimate is fused with it (one that joins the outliers the viewfinder had begun to see -
        // a new scene - keeps its own; scam_dev.txt "oppo_wb_fuse 0": as before)
        if (com.particlesdevs.photoncamera.capture.OppoNativeWb.fuseOn()) {
            final com.particlesdevs.photoncamera.capture.OppoNativeWb.Fused fused = com.particlesdevs.photoncamera.capture.OppoNativeWb.fuse(
                    com.particlesdevs.photoncamera.capture.OppoNativeWb.prior(ref.timestamp), own.neutral, own.cells);
            if (fused != null) {
                Log.i("SCAM_HDR", "mosaic white balance, OPPO native: " + fused.how);
                neutral = fused.neutral;
            }
        }
        p.customNeutral = neutral;
        p.ownNeutral = true;
        p.ReCalcColor(true, meta);
    }

    /**
     * P226: the photo size of {@code img}: the hybrid's final size kept to the bitmap's aspect and rotation (as the resize above),
     * or the image's own size when nothing reduces it.
     */
    static Point photoSize(Bitmap img, @androidx.annotation.Nullable Point hybridFinal) {
        return photoSize(img.getWidth(), img.getHeight(), hybridFinal);
    }

    static Point photoSize(int w, int h, @androidx.annotation.Nullable Point hybridFinal) {
        if (hybridFinal == null || (long) hybridFinal.x * hybridFinal.y >= (long) w * h) return new Point(w & ~1, h & ~1);
        final double s = Math.sqrt((double) hybridFinal.x * hybridFinal.y / ((double) w * h));
        return new Point(Math.max(2, (int) Math.round(w * s)) & ~1, Math.max(2, (int) Math.round(h * s)) & ~1);
    }

    /** P366: the results of a burst's frames (MosaicNeutralResolver.ispIdle). */
    private static java.util.List<CaptureResult> frameResults(java.util.List<ImageFrame> frames) {
        final java.util.ArrayList<CaptureResult> out = new java.util.ArrayList<>();
        if (frames == null) return out;
        for (ImageFrame f : frames) {
            final CaptureResult r = f == null ? null : f.getMatchedCaptureMetadata();
            if (r != null) out.add(r);
        }
        return out;
    }

    /** P366: another camera's characteristics (MosaicTeacher.sameSensor), null when they cannot be read. */
    @androidx.annotation.Nullable
    private static CameraCharacteristics cameraCharacteristics(String id) {
        try {
            final android.hardware.camera2.CameraManager m = (android.hardware.camera2.CameraManager)
                    ScameraApp.getAppContext().getSystemService(android.content.Context.CAMERA_SERVICE);
            return m == null ? null : m.getCameraCharacteristics(id);
        } catch (Exception e) {
            return null;
        }
    }

    /** The shot / open camera pairs (and sizes) whose characteristics this process has compared (logShotCamera). */
    private static final java.util.Set<String> SHOT_CAMERA_LOGGED = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * A shot develops with the characteristics it was captured with (Parameters.camera()), no longer the open camera's: where that
     * is another object - a FULLRES shot merged in the plain session, a lens switch while it waited - the static keys that differ
     * are logged once per camera pair and size (log only; nothing in the photo changes here).
     */
    private void logShotCamera(Parameters p, int width, int height) {
        try {
            final String diff = Parameters.shotCameraDiff(characteristics, CaptureController.mCameraCharacteristics);
            if (diff == null) return;
            final String pair = p.cameraID + "/" + ScameraApp.getSettings().mCameraID + " " + width + "x" + height;
            if (!SHOT_CAMERA_LOGGED.add(pair)) return;
            Log.i("SCAM_CAPTURE", "shot camera " + pair + ": its own characteristics, not the open camera's object: "
                    + (diff.isEmpty() ? "the levels and colour matrices agree" : "differ in " + diff));
        } catch (RuntimeException e) {
            Log.w(TAG, "shot camera comparison: " + e);
        }
    }

    /** P212f: the cameras whose curve this process has logged. */
    private static final java.util.Set<String> CURVE_LOGGED = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** P212f (W4): the N frames the mosaic statistic reads (the reference first). */
    static final int STAT_FRAMES = 3;

    /**
     * P212f (W4 = M10): MosaicNeutral.measure2 of the reference and up to 2 more N frames of its exposure (the refined black, the
     * lens shading map the photo uses, the reference's noise profile, the photo's crop: the digital zoom's residual, then 4:3 for
     * ISZ / native FULLRES), combined; null when none gives one.
     */
    @androidx.annotation.Nullable
    private static com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Stat mosaicStatistic(Parameters p, ImageFrame ref,
            java.util.List<ImageFrame> frames, int block, boolean aspect4x3) {
        final com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Setup setup =
                new com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Setup()
                        .crop(ref.width, ref.height, com.particlesdevs.photoncamera.control.ZoomController.processingResidual(), aspect4x3);
        if (p.hasGainMap && p.gainMap != null && p.mapSize != null && p.mapSize.x > 1 && p.mapSize.y > 1) {
            setup.gains = p.gainMap;
            setup.gainCols = p.mapSize.x;
            setup.gainRows = p.mapSize.y;
        }
        setup.iso = p.iso > 0 ? p.iso : 100;
        if (p.noiseModeler != null && p.noiseModeler.baseModel != null && p.noiseModeler.baseModel.length > 1 && p.noiseModeler.baseModel[1] != null) {
            setup.noiseS = p.noiseModeler.baseModel[1].first;
            setup.noiseO = p.noiseModeler.baseModel[1].second;
        }
        final java.util.ArrayList<com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Stat> stats = new java.util.ArrayList<>();
        final double refExposure = (double) ref.measuredExposure * Math.max(1, ref.measuredIso);
        for (ImageFrame f : frames) {
            if (stats.size() >= STAT_FRAMES) break;
            if (f != ref && (f == null || f.pair == null || f.pair.isHighlightFrame || f.pair.isLongFrame
                    || f.getCaptureRole() != ImageFrame.CaptureRole.NORMAL || f.width != ref.width || f.height != ref.height
                    || f.rawPayloadError != null
                    || Math.abs((double) f.measuredExposure * Math.max(1, f.measuredIso) / Math.max(1.0, refExposure) - 1) > 0.05))
                continue;
            if (f.buffer == null || f.buffer.capacity() < (long) f.width * f.height * 2) continue;
            final com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Stat s =
                    com.particlesdevs.photoncamera.processing.color.MosaicNeutral.measure2(f.buffer, f.width, f.height, f.width * 2,
                            p.baseCfaPattern, p.blackLevel, p.whiteLevel, block, setup);
            if (s != null) stats.add(s);
        }
        return com.particlesdevs.photoncamera.processing.color.MosaicNeutral.combine(stats);
    }

    /** P212d: the rolling shutter skew of a result (ns), null when the HAL gives none. */
    private static Long rollingSkew(CaptureResult r) {
        try { return r == null ? null : r.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW); }
        catch (RuntimeException e) { return null; }
    }

    /** The shortest exposure has the most pixels at the dark floor, so it bounds black best. */
    private static void refineBlackFromDarkestFrame(Parameters processingParameters, ArrayList<ImageFrame> frames) {
        ImageFrame darkest = null;
        double lowest = Double.MAX_VALUE;
        for (ImageFrame frame : frames) {
            if (frame == null || frame.buffer == null || frame.rawPayloadError != null) continue;
            double exposure = frame.measuredExposure > 0 && frame.measuredIso > 0
                    ? (double) frame.measuredExposure * frame.measuredIso
                    : frame.pair != null ? frame.pair.exposure * (double) frame.pair.iso : Double.MAX_VALUE / 2;
            if (exposure < lowest) { lowest = exposure; darkest = frame; }
        }
        if (darkest != null) processingParameters.refineBlackLevel(darkest.buffer, darkest.width, darkest.height);
    }

    /**
     * P320: a DCG shot's codes are taken as linear only when the reference frame's histogram proves it (RawLinearity: no
     * companding knee in the range the shot occupies). A knee found is undone on every frame before any other math (the white
     * balance, the merge, the noise check): the frames are expanded through the inverse curve and the levels follow it. The
     * HAL's noise profile describes the companded codes then; the merge measures the shot's noise itself (DCG bursts: two zones).
     */
    /**
     * P320 / P322: the factor a DCG mode's ISO carries above the gain its photons saw: the stream's range against the declared
     * range, snapped to a power of two within 15 % (1 for a stream no wider than declared). Seen twice: the X300 Ultra's DCG main
     * (8694 - 1030.75 over 1023 - 64 = 8; ISO 1336 given for 167 asked, HybridPlan P163) and the X200 Ultra's DCG main (16383 -
     * 1028 over 959 = 16: its ISO floor 1144 against the plain mode's 72, the HAL noise profile 14 - 19x the measured slope).
     */
    static float dcgIsoScale(Parameters p, CameraCharacteristics c) {
        if (p == null || c == null) return 1f;
        if (!isoInflationSeen()) {
            Log.i("SCAM_HDR", "DCG ISO scale: not applied (an inflated ISO was seen on the vivo DCG modes only)");
            return 1f;
        }
        final Integer declaredWhite = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        final android.hardware.camera2.params.BlackLevelPattern pattern = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        if (declaredWhite == null || declaredWhite <= 0) return 1f;
        float declaredBlack = 0f;
        if (pattern != null) {
            for (int i = 0; i < 4; i++) declaredBlack += pattern.getOffsetForIndex(i & 1, i >> 1);
            declaredBlack /= 4f;
        }
        float black = 0f;
        for (float b : p.blackLevel) black += b;
        black /= 4f;
        return isoScale(p.whiteLevel - black, declaredWhite - declaredBlack);
    }

    /** P320: range over declared range, a power of two when within 15 % of one (else 1 decimal); 1 below x1.5. */
    /** P322: the phones whose DCG ISO was seen inflated by the range (vivo X300 / X200 Ultra), not the Xiaomi 15 Ultra's route. */
    public static boolean isoInflationSeen() {
        return !com.particlesdevs.photoncamera.capture.XiaomiDcg.device();
    }

    public static float isoScale(double range, double declaredRange) {
        if (!(range > 0) || !(declaredRange > 0)) return 1f;
        final double r = range / declaredRange;
        if (r < 1.5) return 1f;
        final double pow = Math.pow(2, Math.round(Math.log(r) / Math.log(2)));
        return (float) (Math.abs(r / pow - 1) <= 0.15 ? pow : Math.round(r * 10) / 10.0);
    }

    /** P320: no frame of a DCG shot is scaled by its exposure while its codes are not shown linear: the reference's exposure only. */
    private static void keepReferenceExposure(ArrayList<ImageFrame> frames, ImageFrame reference, String why) {
        final double ref = (double) reference.measuredExposure * reference.measuredIso;
        int dropped = 0;
        for (java.util.Iterator<ImageFrame> it = frames.iterator(); it.hasNext();) {
            ImageFrame f = it.next();
            if (f == reference || f == null) continue;
            final double ev = Math.abs(Math.log((double) f.measuredExposure * f.measuredIso / ref) / Math.log(2));
            if (ev <= 0.07) continue;
            f.close();
            it.remove();
            dropped++;
        }
        if (dropped > 0) Log.w("SCAM_HDR", "DCG RAW " + why + ": " + dropped + " frame(s) of another exposure left out");
    }

    private void dcgLinearity(Parameters p, ArrayList<ImageFrame> frames, ImageFrame reference) {
        p.dcgIsoScale = dcgIsoScale(p, characteristics);
        if (p.dcgIsoScale > 1f) {
            Log.i("SCAM_HDR", "DCG ISO scale x" + p.dcgIsoScale + " (" + p.levelSource + ")");
            p.applyDcgIsoScale(); // the noise model of a chosen profile / the fallback at the gain, not at the reported ISO
        }
        final com.particlesdevs.photoncamera.processing.RawLinearity.Result r;
        try {
            r = com.particlesdevs.photoncamera.processing.RawLinearity.analyze(reference.buffer, reference.width, reference.height,
                    reference.width * 2, p.blackLevel, p.whiteLevel);
        } catch (RuntimeException e) {
            Log.w("SCAM_HDR", "DCG RAW linearity: not checked (" + e + ")");
            keepReferenceExposure(frames, reference, "linearity not checked");
            return;
        }
        Log.i("SCAM_HDR", "DCG " + r.describe());
        if (r.verdict == com.particlesdevs.photoncamera.processing.RawLinearity.Verdict.NO_DATA) {
            keepReferenceExposure(frames, reference, "linearity not proven");
            return;
        }
        if (r.verdict != com.particlesdevs.photoncamera.processing.RawLinearity.Verdict.COMPANDED) return;
        for (ImageFrame f : frames)
            if (f != null && f.buffer != null && f.buffer.isReadOnly()) {
                Log.w("SCAM_HDR", "DCG RAW companded but frame " + f.number + " is read-only: not linearised");
                keepReferenceExposure(frames, reference, "companded, not linearised");
                return;
            }
        final int[] lut = com.particlesdevs.photoncamera.processing.RawLinearity.expandLut(r.knees, r.ratios, p.whiteLevel);
        for (ImageFrame f : frames) if (f != null && f.buffer != null) com.particlesdevs.photoncamera.processing.RawLinearity.expand(f.buffer, lut);
        for (int i = 0; i < 4; i++) p.blackLevel[i] = lut[Math.max(0, Math.min(65535, Math.round(p.blackLevel[i])))];
        p.whiteLevel = lut[Math.max(0, Math.min(65535, p.whiteLevel))];
        Log.w("SCAM_HDR", "DCG RAW companded: " + frames.size() + " frames expanded, black " + p.blackLevel[0] + " white " + p.whiteLevel);
        try {
            processingEventsListener.onProcessingChanged(Lang.t("DCG: RAW сжат кривой, выпрямлен перед склейкой",
                    "DCG: the RAW is companded, linearised before the merge"));
        } catch (RuntimeException e) {
            Log.d(TAG, "DCG notice: " + e);
        }
    }


}
