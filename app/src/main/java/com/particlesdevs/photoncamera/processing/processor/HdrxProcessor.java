package com.particlesdevs.photoncamera.processing.processor;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.particlesdevs.photoncamera.processing.opengl.scripts.ESD4D;
import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.api.Camera2ApiAutoFix;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.ImageFrameDeblur;
import com.particlesdevs.photoncamera.processing.ImageSaver;
import com.particlesdevs.photoncamera.processing.ml.AiBayerDenoiseProcessor;
import com.particlesdevs.photoncamera.processing.ml.VivoRaisrProcessor;
import com.particlesdevs.photoncamera.processing.ml.VivoPostDownscale;
import com.particlesdevs.photoncamera.processing.ProcessingEventsListener;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.PostPipeline;
import com.particlesdevs.photoncamera.processing.ultrahdr.GainMapComputer;
import com.particlesdevs.photoncamera.processing.ultrahdr.UltraHdrEncoder;
import com.particlesdevs.photoncamera.processing.parameters.FrameNumberSelector;
import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.ScameraPreferences;
import com.particlesdevs.photoncamera.util.Allocator;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;

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
    private ByteBuffer hexOwnedOutput;
    private ByteBuffer niceOwnedOutput;
    private Parameters niceOutputParameters;
    private boolean niceCapture;


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
        this.fullpairs = ownedPairs != null ? ownedPairs : IsoExpoSelector.fullpairs;
        Log.d(TAG, "HdrxProcessor called start()");
        Run();
    }

    public void Run() {
        try {
            processingStage = "camera metadata";
            Camera2ApiAutoFix.ApplyRes(captureResult);
            if (imageFormat == CaptureController.RAW_FORMAT) {
                ApplyHdrX();
            } else {
                Log.d(TAG, "HdrX processing skipped due to unsupported image format: " + imageFormat);
                callback.onFinished();
                return;
            }
//            if (isYuv) {
//                ApplyStabilization();
//            }
        } catch (Exception e) {
            Log.e(TAG, ProcessingEventsListener.FAILED_MSG);
            Log.e(TAG, "Error in HdrX Processing:"+Log.getStackTraceString(e));
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
                Allocator.free(niceOwnedOutput);niceOwnedOutput=null;
                if(niceOutputParameters!=null)niceOutputParameters.vivoNiceRgb=null;
                niceOutputParameters=null;
            }
            if (hexOwnedOutput != null) {
                Allocator.free(hexOwnedOutput);
                hexOwnedOutput = null;
            }
            if ((niceCapture || PreferenceKeys.isNeuralBurstRemosaic() || PreferenceKeys.isRawMfsrEnabled()
                    || (captureRequest!=null && captureRequest.getTag() instanceof com.particlesdevs.photoncamera.remosaic.CalibrationSession))
                    && mImageFramesToProcess != null)
                for (ImageFrame frame : mImageFramesToProcess) if (frame.buffer != null) frame.close();
        }
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
        int width = mImageFramesToProcess.get(0).width;
        int height = mImageFramesToProcess.get(0).height;
        Log.d(TAG, "APPLY HDRX: buffer:" + mImageFramesToProcess.get(0).buffer.asShortBuffer().remaining());
        Log.d(TAG, "Api WhiteLevel:" + characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));
        Log.d(TAG, "Api BlackLevel:" + characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN));
        Parameters processingParameters = new Parameters();
        processingParameters.vivoHdrMode = PreferenceKeys.isVivoHdrEnabled();
        processingParameters.FillConstParameters(characteristics, new Point(width, height));
        if(PreferenceKeys.isSabreEnabled()) {
            String cfa=com.particlesdevs.photoncamera.remosaic.BurstPolicy.cfa(
                    PreferenceKeys.getMultiFrameCfa(),processingParameters.cfaPattern);
            processingParameters.cfaPattern=(byte)java.util.Arrays.asList("RGGB","GRBG","GBRG","BGGR").indexOf(cfa);
        }
        // sort by timestamp first
        mImageFramesToProcess.sort(Comparator.comparingLong(ImageFrame::getTimestamp));
        if(PhotonCamera.getCaptureController()!=null) for(ImageFrame frame:mImageFramesToProcess)
            frame.setCaptureMetadata(PhotonCamera.getCaptureController().takeRawMetadata(frame.timestamp));

        if (niceCapture) {
            java.util.HashSet<Long> seen = new java.util.HashSet<>();
            for (ImageFrame frame : mImageFramesToProcess) {
                if (!seen.add(frame.timestamp) || frame.getCaptureRole() == null
                        || frame.measuredExposure <= 0 || frame.measuredIso <= 0)
                    throw new IllegalStateException("SCAM HDR: нет однозначной роли/экспозиции RAW timestamp="
                            + frame.timestamp);
                exposures.put(frame.timestamp, frame.measuredExposure / 1e9 * frame.measuredIso);
            }
        }

        // A few camera HALs occasionally omit one result callback in a mixed
        // ZSL + manual bracket even though the RAW image is delivered. HDRX
        // must not fail merely because its auxiliary timestamp/gyro entry is
        // absent: reconstruct a conservative normal role and exposure.
        if (!niceCapture && fullpairs.isEmpty()) {
            Long expNs = captureResult != null
                    ? captureResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) : null;
            Integer iso = captureResult != null
                    ? captureResult.get(CaptureResult.SENSOR_SENSITIVITY) : null;
            long safeExp = expNs != null ? expNs : 10_000_000L;
            int safeIso = iso != null ? iso : 100;
            IsoExpoSelector.ExpoPair fallback = new IsoExpoSelector.ExpoPair(
                    safeExp, safeExp, safeExp, safeIso, safeIso, safeIso, safeIso);
            fallback.isHighlightFrame = false;
            fallback.isLongFrame = false;
            fullpairs.add(fallback);
            Log.w(TAG, "No exposure roles supplied; inserted safe normal role");
        }
        if (PreferenceKeys.isNeuralBurstRemosaic()) {
            double reference = -1;
            for (ImageFrame frame : mImageFramesToProcess) {
                Double measured = exposures.get(frame.getTimestamp());
                if (measured == null || !Double.isFinite(measured) || measured <= 0)
                    throw new IllegalStateException("HP9 HexQuad: нет измеренной экспозиции RAW");
                if (reference < 0) reference = measured;
                if (Math.abs(measured / reference - 1.0) > 0.02)
                    throw new IllegalStateException("HP9 HexQuad: экспозиция кадров различается");
            }
        }
        if(PreferenceKeys.isRawMfsrEnabled()) {
            if(fullpairs.size()!=mImageFramesToProcess.size())
                throw new IllegalStateException("MFSR: число метаданных не совпадает с серией RAW");
            for(int i=0;i<mImageFramesToProcess.size();i++) {
                ImageFrame f=mImageFramesToProcess.get(i);
                IsoExpoSelector.ExpoPair requested=fullpairs.get(i);
                Double actual=exposures.get(f.timestamp);
                double expected=requested.exposure/1e9*requested.iso;
                if(actual==null || !Double.isFinite(actual) || actual<=0 || Math.abs(actual/expected-1)>0.02)
                    throw new IllegalStateException("MFSR: камера не выполнила заданную экспозицию кадра "+i);
            }
        }
        double safeExposure = niceCapture ? 0 : fullpairs.get(0).Exposure();
        for (ImageFrame frame : mImageFramesToProcess) {
            Double value = exposures.get(frame.getTimestamp());
            if (value == null || !Double.isFinite(value) || value <= 0.0) {
                exposures.put(frame.getTimestamp(), safeExposure);
                Log.w(TAG, "Recovered missing exposure metadata for timestamp "
                        + frame.getTimestamp() + " as " + safeExposure);
            }
        }
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
            if (niceCapture) {
                ImageFrame frame = mImageFramesToProcess.get(i);
                IsoExpoSelector.ExpoPair role = new IsoExpoSelector.ExpoPair(
                        frame.measuredExposure, frame.measuredExposure, frame.measuredExposure,
                        frame.measuredIso, frame.measuredIso, frame.measuredIso, frame.measuredIso);
                role.isHighlightFrame = frame.getCaptureRole() == ImageFrame.CaptureRole.SHORT;
                role.isLongFrame = frame.getCaptureRole() == ImageFrame.CaptureRole.LONG;
                pairByTimestamp.put(timestamp, role);
            } else if (i < fullpairs.size()) {
                pairByTimestamp.put(timestamp, new IsoExpoSelector.ExpoPair(
                        fullpairs.get(i)));
            }
            if (BurstShakiness.size() == mImageFramesToProcess.size()) {
                gyroByTimestamp.put(timestamp, BurstShakiness.get(i));
            }
        }

        int requestedHighlightValue = niceCapture ? 100 : Math.max(0, Math.min(200,
                PreferenceKeys.getHighlightSuppressionValue()));
        if (requestedHighlightValue == 0) {
            int removed = 0;
            for (int i = mImageFramesToProcess.size() - 1; i >= 0; i--) {
                ImageFrame frame = mImageFramesToProcess.get(i);
                IsoExpoSelector.ExpoPair role = pairByTimestamp.get(frame.getTimestamp());
                if (role != null && role.isHighlightFrame) {
                    frame.close();
                    mImageFramesToProcess.remove(i);
                    removed++;
                }
            }
            Log.i(TAG, "Highlight value is 0: ignored " + removed
                    + " short RAW frame(s) before alignment and fusion");
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
        float requestedHighlightStrength = requestedHighlightValue / 100.0f;
        processingParameters.highlightSuppressionStrength = hasShortHeadroom
                ? requestedHighlightStrength : 0.0f;
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
            if (frame.pair == null) {
                frame.pair = new IsoExpoSelector.ExpoPair(fullpairs.get(
                        Math.min(i, fullpairs.size() - 1)));
            }
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
            frame.computeSharpness(PreferenceKeys.isRawMfsrEnabled() ? PreferenceKeys.getMultiFrameBlock()
                    : PreferenceKeys.isRemosaicEnabled() || PreferenceKeys.isNiceMosaic() || processingParameters.quadCfa
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
        // Bind CAL to the submitted request, not a preference that can change during close/switch.
        com.particlesdevs.photoncamera.remosaic.CalibrationSession calibrationSession=
                captureRequest!=null && captureRequest.getTag() instanceof com.particlesdevs.photoncamera.remosaic.CalibrationSession
                ? (com.particlesdevs.photoncamera.remosaic.CalibrationSession)captureRequest.getTag() : null;
        boolean multiCapture = calibrationSession!=null || (PreferenceKeys.isRawMfsrEnabled() && !PreferenceKeys.isSabreEnabled());
        boolean hexCapture = PreferenceKeys.isNeuralBurstRemosaic();
        ByteBuffer hexOutput = null;
        boolean multiBracket=multiCapture && images.stream().anyMatch(f->f.pair.isHighlightFrame || f.pair.isLongFrame);
        if (multiCapture) {
            processingStage = "Multi-frame Remosaic";
            try {
                if(calibrationSession!=null) {
                    com.particlesdevs.photoncamera.remosaic.MobileRemosaicProcessor.calibrate(images,processingParameters,calibrationSession);
                    Log.i("RAW_MFSR","CAL group saved; controller continues the bank");
                    callback.onFinished();return;
                }
                if(multiBracket) {
                    images=com.particlesdevs.photoncamera.remosaic.MobileRemosaicProcessor.prepareBracket(images,processingParameters);
                    ImageFrameDeblur bracketDeblur=new ImageFrameDeblur(processingParameters);
                    bracketDeblur.firstFrameGyro=images.get(0).frameGyro.clone();
                    for(ImageFrame f:images)bracketDeblur.processDeblurPosition(f);
                } else {
                    hexOutput=com.particlesdevs.photoncamera.remosaic.MobileRemosaicProcessor.process(images,processingParameters);
                    hexOwnedOutput=hexOutput;
                    processingParameters.multiFrameCount=images.size();
                }
                ParseExif.syncWithParameters(exifData,processingParameters);
            } catch(Exception e) {throw new IllegalStateException("Multi-frame Remosaic: "+e.getMessage(),e);}
            finally {if(!multiBracket)for(ImageFrame frame:images)frame.close();}
        } else if (hexCapture) {
            processingStage = PreferenceKeys.isQuadNeuralCaptureEnabled() ? "Quad 2x2: four-frame NPU remosaic" : "HP9 HexQuad: six-frame NPU remosaic";
            try {
                hexOutput = com.particlesdevs.photoncamera.processing.opengl.postpipeline.HexQuadBurst.process(
                        PhotonCamera.getAppContext(), images, processingParameters);
                hexOwnedOutput = hexOutput;
                width=processingParameters.rawSize.x;height=processingParameters.rawSize.y;
                ParseExif.syncWithParameters(exifData, processingParameters);
            } catch (Exception e) {
                throw new IllegalStateException("HP9 HexQuad: " + e.getMessage(), e);
            } finally {
                for (ImageFrame frame : images) frame.close();
            }
        }
        if (!niceCapture && !hexCapture && !multiCapture) {
        ImageFrameDeblur imageFrameDeblur = new ImageFrameDeblur(processingParameters);
        imageFrameDeblur.firstFrameGyro = images.get(0).frameGyro.clone();
        for (int i = 0; i < images.size(); i++)
            imageFrameDeblur.processDeblurPosition(images.get(i));
        com.particlesdevs.photoncamera.capture.BurstFrameSelector.Sample[] candidates =
                new com.particlesdevs.photoncamera.capture.BurstFrameSelector.Sample[images.size()];
        for (int i=0;i<images.size();i++) {
            ImageFrame f=images.get(i);
            double fx=processingParameters.focalLength * processingParameters.rawSize.x
                    / processingParameters.sensorSize.getWidth();
            double fy=processingParameters.focalLength * processingParameters.rawSize.y
                    / processingParameters.sensorSize.getHeight();
            f.blurPixels=com.particlesdevs.photoncamera.control.GyroBlurEstimate.pixels(
                    f.frameGyro,fx,fy,f.width,f.height);
            candidates[i]=new com.particlesdevs.photoncamera.capture.BurstFrameSelector.Sample(
                    f.sharpness,f.blurPixels,f.focusDiopters,f.pair.exposure,f.pair.iso,
                    f.pair.isHighlightFrame || f.pair.isLongFrame,f.lensMoving);
            Log.i(TAG,"Burst quality frame="+i+" sharpness="+f.sharpness+" blurPixels="+f.blurPixels
                    +" focus="+f.focusDiopters+" lensMoving="+f.lensMoving);
        }
        int selected=com.particlesdevs.photoncamera.capture.BurstFrameSelector.reference(candidates);
        if(selected<0) throw new IllegalStateException("No valid RAW reference frame");
        boolean[] keep=com.particlesdevs.photoncamera.capture.BurstFrameSelector.keep(candidates,selected);
        ImageFrame reference=images.get(selected);
        for(int i=images.size()-1;i>=0;i--) if(!keep[i]) {
            Log.i(TAG,"Reject defocused/unsharp RAW frame="+images.get(i).number);
            images.remove(i).close();
        }
        images.remove(reference);
        images.add(0,reference);
        // Alignment, reconstruction and all later gates now share this reference.
        Log.i(TAG,"Selected reference="+reference.number+" kept="+images.size()
                +" blurPixels="+reference.blurPixels);

        Log.d(TAG, "White Level:" + processingParameters.whiteLevel);
        Log.d(TAG, "Wrapper.loadFrame");
        //float noiseLevel = (float) Math.sqrt((CaptureController.mCaptureResult.get(CaptureResult.SENSOR_SENSITIVITY)) *
        //        IsoExpoSelector.getMPY() - 40.)*6400.f / (6.2f*IsoExpoSelector.getISOAnalog());

        } // Ordinary frame selection; HexQuad owns its six-frame burst.
        boolean niceComplete=false;
        if (niceCapture && !hexCapture && !multiCapture) {
            processingStage="SCAM HDR neural burst";
            try {
                ImageFrame niceReference = images.stream()
                        .filter(f -> !f.pair.isHighlightFrame && !f.pair.isLongFrame)
                        .findFirst().orElseThrow(() -> new IllegalStateException("NICE: no normal reference"));
                images.remove(niceReference);
                images.add(0, niceReference);
                CaptureResult referenceMetadata = niceReference.getMatchedCaptureMetadata();
                if (referenceMetadata == null)
                    throw new IllegalStateException("SCAM HDR: нет метаданных опорного RAW timestamp="
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
                if (PreferenceKeys.isNiceMosaic()) {
                    // Quad / Tetra stream (ISZ modules): plain bayer before the transport, by the module's mosaic mode.
                    processingStage = "SCAM HDR: ремозаик мозаики";
                    images = new ArrayList<>(com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNiceMosaic.prepare(
                            PhotonCamera.getAppContext(), images, processingParameters));
                    ParseExif.syncWithParameters(exifData, processingParameters);
                    processingStage = "SCAM HDR neural burst";
                }
                niceOwnedOutput=com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNiceBurst.process(
                        PhotonCamera.getAppContext(),images,processingParameters,
                        saveRAW>=1 && (alignAlgorithm!=2 || multiCapture));
                niceOutputParameters=processingParameters;
                processingParameters.vivoNiceRgb=niceOwnedOutput;
                processingParameters.vivoHdrRawScale=1f;niceComplete=true;
                Log.i("NICE_HDR","Original model capture completed; RGB goes directly to WB/LSC/tone. DNG retains the reference RAW.");
            } catch(Exception e) {
                throw new IllegalStateException("NICE capture failed: "+e.getMessage(),e);
            }
        }
        ByteBuffer output = hexOutput;
        Log.d(TAG, "Packing");
        //WrapperAl.packImages();
        Log.d(TAG, "Packed");
        ESD4D esd4d = null;
        ByteBuffer niceMergedDng=null;
        if (niceComplete) {
            ImageFrame ref=images.get(0);output=ref.buffer;ref.buffer=null;
            for(ImageFrame frame:images)frame.close();
            niceMergedDng=com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNiceBurst.lastMergedDng;
            com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNiceBurst.lastMergedDng=null;
        } else if (hexCapture || (multiCapture && !multiBracket)) {
            processingParameters.highlightSuppressionStrength = 0f;
        } else if(images.size() > 1) {
            processingStage = "RAW alignment/fusion";
            byte inputCfa=processingParameters.cfaPattern;
            boolean inputQuad=processingParameters.quadCfa, inputRemosaic=processingParameters.remosaicDone;
            try {
                esd4d = new ESD4D(new Point(width, height), images);
                esd4d.parameters = processingParameters;
                esd4d.Run();
                output = esd4d.Output;
                if (output == null) {
                    throw new IllegalStateException("fusion returned no RAW buffer");
                }
                for (ImageFrame image : images) image.close();
                IncreaseWLBL(processingParameters);
            } catch (RuntimeException fusionError) {
                // A driver or shader failure must not discard the photograph.
                // Preserve the sharpest input RAW and continue through the normal
                // post-pipeline; the detailed cause remains in logcat.
                Log.e(TAG, "RAW fusion failed; using single-frame recovery", fusionError);
                processingParameters.vivoHdrRawScale=1f;
                processingParameters.effectiveStackSamples=1;
                processingParameters.cfaPattern=inputCfa;
                processingParameters.quadCfa=inputQuad;
                processingParameters.remosaicDone=inputRemosaic;
                ImageFrame recovery = images.get(0);
                output = recovery.buffer;
                recovery.buffer = null;
                for (ImageFrame image : images) image.close();
                if (esd4d != null) esd4d.close();
                esd4d = null;
                processingStage = "single-frame recovery";
            } finally {
                if (esd4d != null) esd4d.close();
            }
        } else {
            output = images.get(0).buffer;
            images.get(0).buffer = null;
        }
        Log.d(TAG, "HDRX Alignment elapsed:" + (System.currentTimeMillis() - startTime) + " ms");
        if ((saveRAW >= 1) && (alignAlgorithm != 2 || multiCapture)) {
            boolean imageSaved;
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
            processingEventsListener.notifyImageSavedStatus(imageSaved, dngFile);
            if (saveRAW == 2) {
                processingEventsListener.onProcessingFinished("HdrX RAW Processing Finished");
                callback.onFinished();
                Allocator.free(output);
                if (hexOwnedOutput == output) hexOwnedOutput = null;
                Allocator.getMemoryCount();
                return;
            }
        }

        ByteBuffer mosaicSrForJpeg = null;
        int mosaicSrWidth = 0;
        int mosaicSrHeight = 0;
        if (!niceComplete && ScameraPreferences.mosaicSrEnabled()) {
            if (processingParameters.quadCfa) {
                Log.w(TAG, "RAW SR Mosaic skipped for direct Quad CFA");
            } else {
                float scale = Math.max(1.0f, Math.min(2.0f, ScameraPreferences.mosaicSrScale()));
                mosaicSrWidth = Math.max(2, Math.round(width * scale)) & ~1;
                mosaicSrHeight = Math.max(2, Math.round(height * scale)) & ~1;
                ByteBuffer mosaic = Allocator.reconstructMosaicSr(output, width, height,
                        mosaicSrWidth, mosaicSrHeight, ScameraPreferences.mosaicSrKernel());
                if (mosaic != null) {
                    Path mosaicPath = syntheticMosaicPath(dngFile, mosaicSrWidth, mosaicSrHeight);
                    String label = mosaicSrWidth + "x" + mosaicSrHeight + ", scale "
                            + String.format(java.util.Locale.US, "%.3fx", scale);
                    boolean saved = ImageSaver.Util.saveSyntheticMosaicRaw(mosaicPath, mosaic,
                            processingParameters, mosaicSrWidth, mosaicSrHeight, label);
                    processingEventsListener.notifyImageSavedStatus(saved, mosaicPath);
                    if (ScameraPreferences.mosaicSrUseForJpeg()) mosaicSrForJpeg = mosaic;
                    else Allocator.free(mosaic);
                }
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
        boolean allowPostDenoise = !processingParameters.vivoHdrMode
                && (!processingParameters.hexQuadProcessed || processingParameters.hexQuadPostDenoise);
        if (processingParameters.hexQuadProcessed) Log.i(TAG,"HEX POST DENOISE: AI/SCAMERA/RT allowed="+allowPostDenoise);
        if (allowPostDenoise && PreferenceKeys.isAiDenoiseEnabled() && PreferenceKeys.getAiDenoiseStrength() > 0) {
            processingStage = "AI RAW denoise";
            try {
                AiBayerDenoiseProcessor.process(PhotonCamera.getAppContext(), output,
                        processingParameters, PreferenceKeys.getAiDenoiseStrength(),
                        PreferenceKeys.getAiDenoiseLuma(), PreferenceKeys.getAiDenoiseChroma(),
                        PreferenceKeys.getAiDenoiseModel());
            } catch (Throwable aiError) {
                // AI is optional and may never turn a valid HDRX capture into a failure.
                Log.e(TAG, "Optional AI denoise failed; continuing without it", aiError);
            }
        }

        processingStage = "RAW post-processing";
        ByteBuffer jpegInput = output;
        if (mosaicSrForJpeg != null) {
            jpegInput = mosaicSrForJpeg;
            processingParameters.rawSize = new Point(mosaicSrWidth, mosaicSrHeight);
            processingParameters.sensorPix = new Rect(0, 0, mosaicSrWidth, mosaicSrHeight);
            processingParameters.mapSize = new Point(1, 1);
            processingParameters.gainMap = new float[]{1f, 1f, 1f, 1f};
            processingParameters.hasGainMap = false;
            processingParameters.hotPixels = new Point[0];
            processingParameters.alignmentSize = new Point(
                    mosaicSrWidth / processingParameters.tile + 1,
                    mosaicSrHeight / processingParameters.tile + 1);
            processingParameters.tilesX = mosaicSrWidth / 800 + 1;
        }
        PostPipeline pipeline = new PostPipeline();
        pipeline.kernelParams = mosaicSrForJpeg == null && esd4d != null ? esd4d.kernelsMapCPU : null;
        pipeline.kernelParamsSize = mosaicSrForJpeg == null && esd4d != null ? esd4d.kernelsMapCPUSize : null;

        Bitmap img = pipeline.Run(jpegInput, processingParameters);
        final int beforeVivoWidth = img.getWidth(), beforeVivoHeight = img.getHeight();
        final int downscaleKernel = PreferenceKeys.getVivoDownscaleKernel();
        final String downscaleSize = PreferenceKeys.getVivoDownscaleSize();
        boolean vivoSucceeded = false;
        // VSR works on the finished bitmap, so it also follows NICE / vivo HDR.
        if ((!processingParameters.vivoHdrMode || "vsr".equals(PreferenceKeys.getVivoUpscaleBackend()))
                && PreferenceKeys.isRaisrEnabled()) {
            final String upscaleBackend = PreferenceKeys.getVivoUpscaleBackend();
            processingStage = "softpqe".equals(upscaleBackend) ? "Vivo SoftPQE" : "vsr".equals(upscaleBackend) ? "Vivo VSR" : "Vivo RAISR";
            try {
                Bitmap enhanced = "vsr".equals(upscaleBackend)
                        ? com.particlesdevs.photoncamera.processing.ml.VivoVsrProcessor.process(PhotonCamera.getAppContext(), img,
                                PreferenceKeys.getRaisrOutputScale(), PreferenceKeys.getRaisrStrength())
                        : VivoRaisrProcessor.process(PhotonCamera.getAppContext(), img,
                        processingParameters.cameraID, processingParameters.iso, PreferenceKeys.getRaisrOutputScale(), upscaleBackend);
                if (enhanced != img) {
                    img.recycle();
                    img = enhanced;
                }
                vivoSucceeded = true;
            } catch (Throwable raisrError) {
                Log.e(TAG, "Vivo upscale failed; preserving original image", raisrError);
            }
        }
        if (vivoSucceeded && downscaleKernel != 0) {
            processingStage = "Lanczos " + downscaleKernel + " after Vivo";
            try {
                Bitmap reduced = VivoPostDownscale.process(img, beforeVivoWidth, beforeVivoHeight, downscaleKernel, downscaleSize);
                if (reduced != img) {
                    img.recycle();
                    img = reduced;
                }
            } catch (Throwable downscaleError) {
                Log.e(TAG, "Lanczos failed; preserving successful Vivo result", downscaleError);
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
            } catch (Exception e) {
                Log.e(TAG, "Ultra HDR gain-map pass failed, falling back to SDR JPEG", e);
            }
        }

        Allocator.free(jpegInput);
        if (jpegInput != output) Allocator.free(output);
        if (hexOwnedOutput == output) hexOwnedOutput = null;

        img = overlay(img, pipeline.debugData.toArray(new Bitmap[0]));
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
                byte[] uhdr = UltraHdrEncoder.encode(img, res, exifData);
                Files.write(imageFile, uhdr);
                img.recycle();
                imageSaved = true;
            } catch (Exception e) {
                Log.e(TAG, "Ultra HDR encode failed, falling back to SDR JPEG", e);
                imageSaved = ImageSaver.Util.saveBitmapAsJPG(imageFile, img,
                        ImageSaver.JPG_QUALITY, exifData);
            }
        } else {
            //Saves the final bitmap
            imageSaved = ImageSaver.Util.saveBitmapAsJPG(imageFile, img,
                    ImageSaver.JPG_QUALITY, exifData);
        }

        try {
            processingEventsListener.notifyImageSavedStatus(imageSaved, imageFile);
        }
        catch (Exception e){
            Log.d(TAG,"Error in processingEventsListener.notifyImageSavedStatus:"+Log.getStackTraceString(e));
        }

        try {
            pipeline.close();
        } catch (Exception e) {
            Log.e(TAG, "PostPipeline close failed (non-fatal): " + Log.getStackTraceString(e));
        }


        Allocator.getMemoryCount();
        callback.onFinished();
    }

    /** The shortest exposure has the most pixels at the dark floor, so it bounds black best. */
    private static void refineBlackFromDarkestFrame(Parameters processingParameters, ArrayList<ImageFrame> frames) {
        ImageFrame darkest = null;
        double lowest = Double.MAX_VALUE;
        for (ImageFrame frame : frames) {
            if (frame == null || frame.buffer == null) continue;
            double exposure = frame.measuredExposure > 0 && frame.measuredIso > 0
                    ? (double) frame.measuredExposure * frame.measuredIso
                    : frame.pair != null ? frame.pair.exposure * (double) frame.pair.iso : Double.MAX_VALUE / 2;
            if (exposure < lowest) { lowest = exposure; darkest = frame; }
        }
        if (darkest != null) processingParameters.refineBlackLevel(darkest.buffer, darkest.width, darkest.height);
    }

    private static Path syntheticMosaicPath(Path original, int width, int height) {
        String name = original.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(0, dot);
        long megapixels = Math.round((width * (double) height) / 1_000_000.0);
        return original.resolveSibling(name + "_RAW_SR_MOSAIC_" + megapixels + "MP.dng");
    }

}
