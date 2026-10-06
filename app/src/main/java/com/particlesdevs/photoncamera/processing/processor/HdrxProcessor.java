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
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.RawBin;
import com.particlesdevs.photoncamera.processing.opengl.GLLimits;
import com.particlesdevs.photoncamera.processing.ImageSaver;
import com.particlesdevs.photoncamera.processing.ProcessingEventsListener;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.PostPipeline;
import com.particlesdevs.photoncamera.processing.ultrahdr.GainMapComputer;
import com.particlesdevs.photoncamera.processing.ultrahdr.UltraHdrEncoder;
import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Allocator;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import com.particlesdevs.photoncamera.util.Lang;

public class HdrxProcessor extends ProcessorBase {
    private static final String TAG = "HdrxProcessor";
    private ArrayList<ImageFrame> mImageFramesToProcess;
    private HashMap<Long, Double> exposures;
    private int imageFormat;
    /* config */
    private int alignAlgorithm;
    private int saveRAW;
    private CameraMode cameraMode;
    private ArrayList<GyroBurst> BurstShakiness;
    private String processingStage = "initialization";
    private ByteBuffer niceOwnedOutput;
    private Parameters niceOutputParameters;
    private boolean niceCapture;
    /** This shot is merged by the LMC hybrid (its own settings profile), not by SCAM HDR (NICE). */
    private boolean hybridShot;
    /** P27: the merge route the queued shot was captured for (null: the current setting). */
    public Boolean ownedHybridRoute;


    public HdrxProcessor(ProcessingEventsListener processingEventsListener) {
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
        this.niceCapture = PreferenceKeys.isVivoNiceEnabled();
        // The route of the shot fixes the settings profile of its whole processing: pref_lmc_hybrid_* for a hybrid shot,
        // the SCAM HDR keys otherwise (PreferenceKeys.isHybridShot), even if the route changes meanwhile.
        this.hybridShot = niceCapture && (ownedHybridRoute != null ? ownedHybridRoute : PreferenceKeys.isLmcHybridEnabled());
        ownedHybridRoute = null;
        android.util.Log.i("NICE_HDR", "route=" + PreferenceKeys.mergeRoute() + (hybridShot ? " (LMC hybrid merges)" : niceCapture ? " (SCAM HDR merges)" : " (no NICE route)"));
        this.fullpairs = ownedPairs != null ? ownedPairs : IsoExpoSelector.fullpairs;
        Log.d(TAG, "HdrxProcessor called start()");
        PreferenceKeys.beginShotProfile(hybridShot);
        try {
            Run();
        } finally {
            PreferenceKeys.endShotProfile();
        }
    }

    public void Run() {
        try {
            processingStage = "camera metadata";
            Camera2ApiAutoFix.ApplyRes(captureResult);
            if (CaptureController.isRawFormat(imageFormat)) {
                ApplyHdrX();
            } else {
                Log.d(TAG, "HdrX processing skipped due to unsupported image format: " + imageFormat);
                callback.onFinished();
                return;
            }
//            if (isYuv) {
//                ApplyStabilization();
//            }
        } catch (Exception | OutOfMemoryError e) {
            // P27 any resolution: an allocation that grows with the frame (Java heap) fails the shot, not the processing thread.
            Log.e(TAG, ProcessingEventsListener.FAILED_MSG);
            Log.e(TAG, "Error in HdrX Processing:"+stackTrace(e));
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
            processingEventsListener.onProcessingError("HDRX failed at "
                    + processingStage + " — " + detail);
         } finally {
            com.particlesdevs.photoncamera.processing.opengl.postpipeline.NiceDiagnostics.finish();
            if (niceOwnedOutput != null) {
                // VivoNiceRgb may already have freed the big buffer after the GL upload (then vivoNiceRgb is a small copy).
                if (niceOutputParameters == null || niceOutputParameters.vivoNiceRgb == niceOwnedOutput) Allocator.free(niceOwnedOutput);
                niceOwnedOutput=null;
                if(niceOutputParameters!=null){niceOutputParameters.vivoNiceRgb=null;niceOutputParameters.vivoNiceRgbOwned=false;}
                niceOutputParameters=null;
            }
            if (mImageFramesToProcess != null)
                for (ImageFrame frame : mImageFramesToProcess) if (frame.buffer != null) frame.close();
        }
    }

    /** The app log's stack trace for an Exception (as before); an Error (OutOfMemoryError) through android.util.Log. */
    private static String stackTrace(Throwable t) {
        return t instanceof Exception ? Log.getStackTraceString((Exception) t) : android.util.Log.getStackTraceString(t);
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

    private void ApplyHdrX() {
        processingStage = "input validation";
        callback.onStarted();
        processingEventsListener.onProcessingStarted("HDRX");

        Log.d(TAG, "ApplyHdrX() called from" + Thread.currentThread().getName());

        long startTime = System.currentTimeMillis();
        Log.d(TAG, "ApplyHdrX() mImageFramesToProcess.size():" + mImageFramesToProcess.size());
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
        // P27 any resolution: the Hybrid merges any RAW stream at its own resolution. Only a stream beyond the hard limits (the
        // sensor-grid RGB float result above 2 GB, i.e. above ~178 MP, or a side above the GPU's texture / render-target limit) is
        // binned 2x2 (same colours averaged) as the last resort instead of failing.
        final int gpuMaxSide = GLLimits.get().maxSide();
        int rawBinning = 1;
        if (!RawBin.fits(mImageFramesToProcess.get(0).width, mImageFramesToProcess.get(0).height, gpuMaxSide)) {
            android.hardware.camera2.params.BlackLevelPattern pattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
            Integer whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            float black = pattern == null ? 64f : (pattern.getOffsetForIndex(0, 0) + pattern.getOffsetForIndex(1, 0)
                    + pattern.getOffsetForIndex(0, 1) + pattern.getOffsetForIndex(1, 1)) / 4f;
            try {
                rawBinning = RawBin.binOversized(mImageFramesToProcess, black, whiteLevel == null ? 1023f : whiteLevel, gpuMaxSide);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        int width = mImageFramesToProcess.get(0).width;
        int height = mImageFramesToProcess.get(0).height;
        Log.d(TAG, "APPLY HDRX: buffer:" + mImageFramesToProcess.get(0).buffer.asShortBuffer().remaining());
        Log.d(TAG, "Api WhiteLevel:" + characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));
        Log.d(TAG, "Api BlackLevel:" + characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN));
        Parameters processingParameters = new Parameters();
        processingParameters.rawBinning = rawBinning;
        processingParameters.vivoHdrMode = PreferenceKeys.isVivoHdrEnabled();
        processingParameters.FillConstParameters(characteristics, new Point(width, height));
        // Every shot is merged by the LMC hybrid or SCAM HDR (the legacy merge routes are gone).
        if (!niceCapture) throw new IllegalStateException("no merge route: Hybrid or SCAM HDR needs the ZSL RAW stream");
        // sort by timestamp first
        mImageFramesToProcess.sort(Comparator.comparingLong(ImageFrame::getTimestamp));
        if(PhotonCamera.getCaptureController()!=null) for(ImageFrame frame:mImageFramesToProcess)
            frame.setCaptureMetadata(PhotonCamera.getCaptureController().takeRawMetadata(frame.timestamp));

        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (java.util.Iterator<ImageFrame> it = mImageFramesToProcess.iterator(); it.hasNext();) {
            ImageFrame frame = it.next();
            if (!seen.add(frame.timestamp) || frame.getCaptureRole() == null
                    || frame.measuredExposure <= 0 || frame.measuredIso <= 0
                    || frame.width != width || frame.height != height) {
                // P27: one frame without a clear role, exposure or size never costs the shot: it leaves the burst.
                Log.w("NICE_HDR", (hybridShot ? "Hybrid" : "SCAM HDR") + ": RAW timestamp=" + frame.timestamp
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
            Log.d(TAG, "Mpy:" + frame.pair.layerMpy);
            images.add(frame);
            // Measured for every frame now, bracket members included. Their value is
            // not directly comparable with a regular frame's - the measure is
            // normalised by level, not by the noise model, and a longer exposure has
            // a better SNR, which lowers the noise part of the gradient energy on its
            // own - so it is logged for diagnosis and used only against the same
            // exposure, never as a cross-exposure threshold.
            frame.computeSharpness(PreferenceKeys.isNiceMosaic() || processingParameters.quadCfa
                    ? PreferenceKeys.getRemosaicBlockSize() : 1);
            Log.d(TAG, "frame " + i + ": mpy=" + frame.pair.layerMpy
                    + " iso=" + frame.pair.iso
                    + " sharpness=" + frame.sharpness
                    + " shakiness=" + frame.frameGyro.shakiness
                    + " long=" + frame.pair.isLongFrame
                    + " short=" + frame.pair.isHighlightFrame);
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

        {
            processingStage="SCAM HDR neural burst";
            try {
                // The oldest N frame calibrates the shot (as before); P27: without any N frame the frame closest to N does.
                ImageFrame niceReference = images.stream()
                        .filter(f -> !f.pair.isHighlightFrame && !f.pair.isLongFrame && f.getCaptureRole() == ImageFrame.CaptureRole.NORMAL)
                        .findFirst().orElse(null);
                if (niceReference == null) {
                    final ArrayList<ImageFrame> candidates = images;
                    niceReference = candidates.stream().filter(f -> f.getCaptureRole() == ImageFrame.CaptureRole.LONG)
                            .min(Comparator.comparingDouble(f -> (double) f.measuredExposure * f.measuredIso))
                            .orElseGet(() -> candidates.stream().max(Comparator.comparingDouble(f -> (double) f.measuredExposure * f.measuredIso)).get());
                    Log.w("NICE_HDR", "no N frame in the burst: frame " + niceReference.number + " (" + niceReference.getCaptureRole()
                            + ") calibrates the shot");
                }
                images.remove(niceReference);
                images.add(0, niceReference);
                CaptureResult referenceMetadata = niceReference.getMatchedCaptureMetadata();
                if (referenceMetadata == null)
                    throw new IllegalStateException(Lang.t("SCAM HDR: нет метаданных опорного RAW timestamp=", "SCAM HDR: no metadata of the reference RAW timestamp=")
                            + niceReference.timestamp);
                captureResult = referenceMetadata;
                captureRequest = referenceMetadata.getRequest();
                processingParameters.FillDynamicParameters(referenceMetadata, captureRequest,
                        niceReference.measuredIso);
                refineBlackFromDarkestFrame(processingParameters, images);
                ParseExif.syncWithParameters(exifData, processingParameters);
                Log.i("NICE_HDR", "Reference calibration timestamp=" + niceReference.timestamp
                        + " ISO=" + processingParameters.iso
                        + " exposureSeconds=" + processingParameters.exposureTime);
                if (!hybridShot) {
                    // P27: SCAM HDR builds its fixed 4 N + L + S + ES graph when the frames allow it; a burst it cannot use
                    // (frames lost, exposures off plan, the worker failed) is merged by the Hybrid from the same frames
                    // instead of losing the photo.
                    try {
                        if (PreferenceKeys.isNiceMosaic() && niceMosaicStream(images, processingParameters)) {
                            // Quad / Tetra stream (ISZ modules): plain bayer before the transport, by the module's mosaic mode.
                            processingStage = Lang.t("SCAM HDR: ремозаик мозаики", "SCAM HDR: mosaic remosaic");
                            images = new ArrayList<>(com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNiceMosaic.prepare(
                                    PhotonCamera.getAppContext(), images, processingParameters));
                            ParseExif.syncWithParameters(exifData, processingParameters);
                        }
                        processingStage = "SCAM HDR neural burst";
                        niceOwnedOutput = com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNiceBurst.process(
                                PhotonCamera.getAppContext(), images, processingParameters, saveRAW >= 1 && alignAlgorithm != 2);
                    } catch (Exception scam) {
                        Log.w("NICE_HDR", "SCAM HDR fallback=hybrid reason=" + scam.getMessage());
                        android.util.Log.w("NICE_HDR", "SCAM HDR fallback=hybrid", scam);
                        com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNiceBurst.lastMergedDng = null;
                        hybridShot = true;
                        PreferenceKeys.endShotProfile();
                        PreferenceKeys.beginShotProfile(true);
                        // VivoNiceMosaic may have closed frames it did not keep: the hybrid drops frames without data.
                        images.removeIf(f -> f == null || f.buffer == null);
                    }
                }
                if (hybridShot) {
                    processingStage = "Hybrid merge";
                    niceOwnedOutput = com.particlesdevs.photoncamera.processing.opengl.postpipeline.LmcHybridBurst.process(
                            PhotonCamera.getAppContext(), images, processingParameters, saveRAW >= 1 && alignAlgorithm != 2);
                }
                niceOutputParameters=processingParameters;
                processingParameters.vivoNiceRgb=niceOwnedOutput;
                processingParameters.vivoNiceRgbOwned=true;
                processingParameters.vivoHdrRawScale=1f;
                Log.i("NICE_HDR","Original model capture completed; RGB goes directly to WB/LSC/tone. DNG retains the reference RAW.");
            } catch(Exception e) {
                throw new IllegalStateException("NICE capture failed: "+e.getMessage(),e);
            }
        }
        ImageFrame ref=images.get(0);
        ByteBuffer output=ref.buffer;ref.buffer=null;
        if ((long) width * height > 16_000_000L) output = outOfArena(output);
        for(ImageFrame frame:images)frame.close();
        ByteBuffer niceMergedDng=com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNiceBurst.lastMergedDng;
        com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNiceBurst.lastMergedDng=null;
        Log.d(TAG, "HDRX Alignment elapsed:" + (System.currentTimeMillis() - startTime) + " ms");
        if ((saveRAW >= 1) && alignAlgorithm != 2) {
            boolean imageSaved = false;
            try {
            if (niceMergedDng != null) {
                // Whole-burst merge (like the merged DNG of GCam/LMC), 14-bit levels.
                float[] black = processingParameters.blackLevel.clone();
                int white = processingParameters.whiteLevel;
                float k = 16383f / white;
                for (int i = 0; i < 4; i++) processingParameters.blackLevel[i] = Math.round(black[i] * k);
                processingParameters.whiteLevel = 16383;
                try {
                    imageSaved = ImageSaver.Util.saveStackedRaw(dngFile, niceMergedDng, processingParameters);
                    Log.i("NICE_HDR", "DNG: merged burst RAW (all N frames), 14-bit");
                } finally {
                    System.arraycopy(black, 0, processingParameters.blackLevel, 0, 4);
                    processingParameters.whiteLevel = white;
                    Allocator.free(niceMergedDng);
                }
            } else imageSaved = ImageSaver.Util.saveStackedRaw(dngFile, output,
                    processingParameters);
            } catch (RuntimeException | OutOfMemoryError dngError) {
                // P27: the DNG is optional, the JPEG is not.
                Log.e(TAG, "DNG not saved: " + stackTrace(dngError));
            }
            processingEventsListener.notifyImageSavedStatus(imageSaved, dngFile);
            if (saveRAW == 2) {
                processingEventsListener.onProcessingFinished("HdrX RAW Processing Finished");
                callback.onFinished();
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
        if (processingParameters.vivoHdrMode) {
            double scale=processingParameters.vivoHdrRawScale;
            for (int c=0;c<processingParameters.noiseModeler.computeModel.length;c++) {
                android.util.Pair<Double,Double> n=processingParameters.noiseModeler.computeModel[c];
                processingParameters.noiseModeler.computeModel[c]=new android.util.Pair<>(n.first*scale,n.second*scale*scale);
            }
        }

        processingStage = "RAW post-processing";
        ByteBuffer jpegInput = output;
        // SCAM HDR hybrid on the Sabre 2x grid: the RGB is larger than the sensor grid (the DNG above stayed sensor
        // size). Like mosaic SR, the pipeline size follows the RGB; the LSC map is sampled in normalised coordinates.
        // Only a hybrid shot has the hybrid's output size: the static keeps the last hybrid shot's size, which a SCAM HDR
        // shot (sensor size) must not take (it rendered the SCAM HDR RGB at the 2x size, black).
        final Point hybridOut = hybridShot ? com.particlesdevs.photoncamera.processing.opengl.postpipeline.LmcHybridBurst.lastOutputSize : null;
        processingParameters.hybridFinalSize = null;
        if (hybridOut != null && (hybridOut.x != width || hybridOut.y != height)) {
            // 12/16/20 MP from the 2x grid: resized on the GPU at the end of the pipeline (HybridFinalResize); the CPU
            // resize below only remains as a fallback when the pipeline did not apply it.
            final Point fin = com.particlesdevs.photoncamera.processing.opengl.postpipeline.LmcHybridBurst.lastFinalSize;
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
            Log.i("NICE_HDR", "hybrid output " + hybridOut.x + "x" + hybridOut.y + " (scale " + sx + "): pipeline runs at the merged size");
        }
        PostPipeline pipeline = new PostPipeline();

        Bitmap img = pipeline.Run(jpegInput, processingParameters);
        // SCAM HDR hybrid on the Sabre 2x grid: the whole pipeline (including sharpening) ran on the 2x image; the
        // final size (12/16/20 MP, or the sensor size) is produced here, keeping the bitmap's aspect and rotation.
        final Point hybridFinal = hybridOut != null ? com.particlesdevs.photoncamera.processing.opengl.postpipeline.LmcHybridBurst.lastFinalSize : null;
        if (hybridFinal != null && (long) hybridFinal.x * hybridFinal.y < (long) img.getWidth() * img.getHeight()) {
            final double s = Math.sqrt((double) hybridFinal.x * hybridFinal.y / ((double) img.getWidth() * img.getHeight()));
            final int tw = Math.max(2, (int) Math.round(img.getWidth() * s)) & ~1, th = Math.max(2, (int) Math.round(img.getHeight() * s)) & ~1;
            processingStage = "SCAM HDR: resize " + img.getWidth() + "x" + img.getHeight() + " -> " + tw + "x" + th;
            try {
                Bitmap reduced = com.particlesdevs.photoncamera.processing.ml.VivoPostDownscale.resizeTo(img, tw, th, PreferenceKeys.hybridDownsampler());
                if (reduced != img) { img.recycle(); img = reduced; }
                Log.i("NICE_HDR", "hybrid final size " + tw + "x" + th + " (" + PreferenceKeys.hybridDownsamplerName() + ")");
            } catch (Throwable resizeError) {
                Log.e(TAG, "hybrid resize failed; keeping the 2x image", resizeError);
            }
        }
        final float zoomCrop = com.particlesdevs.photoncamera.control.ZoomController.shotResidual();
        if (zoomCrop > 1.005f && !PhotonCamera.getSettings().ultraHdr) {
            processingStage = "digital zoom crop";
            img = com.particlesdevs.photoncamera.control.ZoomController.crop(img, zoomCrop);
        }
        processingStage = "image encoding";

        PostPipeline.GainMapRaw gm = null;
        if (PhotonCamera.getSettings().ultraHdr) {
            // Must run before the raw frame buffer is freed.
            try {
                gm = pipeline.RunHDRGainMap(jpegInput, processingParameters, img,
                        GainMapComputer.SCALE_DOWN, GainMapComputer.SCALE);
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "Ultra HDR gain-map pass failed, falling back to SDR JPEG", e);
            }
        }

        Allocator.free(jpegInput);
        if (jpegInput != output) Allocator.free(output);

        final Bitmap withDebug = overlay(img, pipeline.debugData.toArray(new Bitmap[0]));
        if (withDebug != img) { img.recycle(); img = withDebug; }
        // The EGL context and the rest of the GL state are not needed for the encoding: release them before it.
        try {
            pipeline.close();
        } catch (Exception e) {
            Log.e(TAG, "PostPipeline close failed (non-fatal): " + Log.getStackTraceString(e));
        }
        try {
            processingEventsListener.onProcessingFinished("HdrX JPG Processing Finished");
        }
        catch (Exception e){
            Log.d(TAG,"Error in processingEventsListener.onProcessingFinished:"+Log.getStackTraceString(e));
        }
        imageFile = Paths.get(imageFile.toAbsolutePath() + ".jpg");
        boolean imageSaved;
        if (PhotonCamera.getSettings().ultraHdr && gm != null) {
            try {
                GainMapComputer.Result res = GainMapComputer.compute(gm.bitmap, gm.down, gm.scale);
                UltraHdrEncoder.encodeToFile(imageFile, img, res, exifData, PreferenceKeys.getJpegQuality());
                img.recycle();
                imageSaved = true;
            } catch (Exception e) {
                Log.e(TAG, "Ultra HDR encode failed, falling back to SDR JPEG", e);
                imageSaved = ImageSaver.Util.saveBitmapAsJPG(imageFile, img,
                        PreferenceKeys.getJpegQuality(), exifData);
            }
        } else {
            //Saves the final bitmap
            imageSaved = ImageSaver.Util.saveBitmapAsJPG(imageFile, img,
                    PreferenceKeys.getJpegQuality(), exifData);
        }

        try {
            processingEventsListener.notifyImageSavedStatus(imageSaved, imageFile);
        }
        catch (Exception e){
            Log.d(TAG,"Error in processingEventsListener.notifyImageSavedStatus:"+Log.getStackTraceString(e));
        }

        Allocator.getMemoryCount();
        callback.onFinished();
    }

    /**
     * SCAM HDR's mosaic mode applies to a burst that really is a mosaic: a forced block or an ISZ sensor mode, else a confident
     * colour block measured on the first frame. A plain-Bayer module keeps its Bayer frames (vivo X100 Ultra main, owner's log
     * 2026-10-05: 'mode=neural_sabre block=4' remosaicked a 4096x3072 Bayer stream as Tetra).
     */
    private static boolean niceMosaicStream(java.util.List<ImageFrame> images, Parameters p) {
        if (PreferenceKeys.niceMosaicDeclared()) return true;
        for (ImageFrame f : images) {
            if (f.buffer == null || f.buffer.capacity() < (long) f.width * f.height * 2) continue;
            float black = (p.blackLevel[0] + p.blackLevel[1] + p.blackLevel[2] + p.blackLevel[3]) / 4f;
            com.particlesdevs.photoncamera.processing.MosaicBlockDetector.Result r =
                    com.particlesdevs.photoncamera.processing.MosaicBlockDetector.detect(f.buffer, f.width, f.height, f.width * 2,
                            black, p.whiteLevel, 8);
            boolean mosaic = r.confident && r.block > 1;
            Log.i("NICE_HDR", "SCAM HDR mosaic: stream colour block " + r + (mosaic ? "" : "; mosaic mode ignored, plain Bayer"));
            return mosaic;
        }
        return false;
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


}
