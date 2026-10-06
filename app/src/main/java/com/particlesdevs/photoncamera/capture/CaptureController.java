package com.particlesdevs.photoncamera.capture;
/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.particlesdevs.photoncamera.util.Allocator;
import com.particlesdevs.photoncamera.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.Camera2ApiAutoFix;
import com.particlesdevs.photoncamera.api.CameraEventsListener;
import com.particlesdevs.photoncamera.api.CameraManager2;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.api.CameraReflectionApi;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.circularbarlib.api.ManualModeConsole;
import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.control.TouchFocus;
import com.particlesdevs.photoncamera.debugclient.DebugSender;
import com.particlesdevs.photoncamera.manual.ParamController;
import com.particlesdevs.photoncamera.processing.ImageSaver;
import com.particlesdevs.photoncamera.processing.parameters.ExposureIndex;
import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector;
import com.particlesdevs.photoncamera.processing.parameters.ResolutionSolution;
import com.particlesdevs.photoncamera.processing.LiveRawFrame;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SensorConfigInjector;
import com.particlesdevs.photoncamera.settings.annotations.SensorConfig;
import com.particlesdevs.photoncamera.ui.camera.CameraFragment;
import com.particlesdevs.photoncamera.ui.camera.viewmodel.TimerFrameCountViewModel;
import com.particlesdevs.photoncamera.ui.camera.views.viewfinder.AutoFitPreviewView;
import com.particlesdevs.photoncamera.ui.camera.views.viewfinder.GLPreview;
import com.particlesdevs.photoncamera.util.log.Logger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import android.media.Image;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.ImageSaverSelector;
import com.particlesdevs.photoncamera.processing.SaverImplementation;
import com.particlesdevs.photoncamera.util.Lang;

import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON;
import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
import static android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON;
import static android.hardware.camera2.CameraMetadata.FLASH_MODE_TORCH;
import static android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_MODE;
import static android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE;
import static android.hardware.camera2.CaptureRequest.CONTROL_AE_REGIONS;
import static android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE;
import static android.hardware.camera2.CaptureRequest.CONTROL_AF_REGIONS;
import static android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE;
import static android.hardware.camera2.CaptureRequest.FLASH_MODE;

/**
 * Class responsible for image capture and sending images for subsequent processing
 * <p>
 * All relevant events are notified to cameraEventsListener
 * <p>
 * Constructor {@link CaptureController#CaptureController(Activity, ExecutorService, CameraEventsListener)}
 */
public class CaptureController implements MediaRecorder.OnInfoListener {
    public static final int RAW_FORMAT = ImageFormat.RAW_SENSOR;
    public static final int YUV_FORMAT = ImageFormat.YUV_420_888;
    private static final String TAG = CaptureController.class.getSimpleName();
    public List<Future<?>> taskResults = new ArrayList<>();
    private final ExecutorService processExecutor;
    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;
    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;
    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;
    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;
    private static final int STATE_CLOSED = 5;
    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;
    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    /**
     * Timeout for the pre-capture sequence.
     */
    private static final long PRECAPTURE_TIMEOUT_MS = 100;
    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    private boolean useMaximumResolutionKey = false;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }

    private Map<String, CameraCharacteristics> mCameraCharacteristicsMap = new HashMap<>();
    public static CameraCharacteristics mCameraCharacteristics;
    public static CaptureResult mCaptureResult;
    public static CaptureRequest mCaptureRequest;

    public static volatile CaptureResult mPreviewCaptureResult;
    public static CaptureRequest mPreviewCaptureRequest;
    public static int mPreviewTargetFormat = ImageFormat.JPEG;
    public boolean isDualSession = false;

    @SensorConfig(title = "Session Type",
            defaultValue = 0, min = 0, max = 65535, step = 0,
            description = "Тип capture-сессии Camera2 (0 = обычная)")
    public int sessionType = 0;

    @SensorConfig(
            title = "OIS Mode",
            description = "Оптическая стабилизация. В режиме «Авто» OIS выключается на штативе и в Unlimited, чтобы кадр не дрейфовал",
            entries = {"On", "Auto", "Off"},
            entryValues = {"0", "1", "2"},
            defaultValue = 0
    )
    public int oisMode = 0;

    @SensorConfig(
            title = "Exposure Balance",
            description = "Сдвиг баланса между выдержкой и ISO. Только режимы «Фото» и «Ночь»",
            entries = {
                    "0.25x (Max SNR Bias)",
                    "0.35x (High SNR Bias)",
                    "0.50x (Medium SNR Bias)",
                    "0.71x (Slight SNR Bias)",
                    "1.00x (Balanced / Default)",
                    "1.41x (Slight Speed Bias)",
                    "2.00x (Medium Speed Bias)",
                    "2.83x (High Speed Bias)",
                    "4.00x (Max Speed Bias)"
            },
            entryValues = {"0.25", "0.35", "0.5", "0.71", "1.0", "1.41", "2.0", "2.83", "4.0"},
            defaultValue = 1.0f
    )
    public float exposureBalanceMultiplier = 1.0f;

    @SensorConfig(
            title = "ISO Limit",
            description = "Ограничение наибольшей чувствительности",
            entries = {"400", "800", "1600", "3200", "6400", "12800", "Max Analog ISO / 4", "Max Analog ISO / 2", "Max Analog ISO", "Sensor Max ISO"},
            entryValues = {"400", "800", "1600", "3200", "6400", "12800", "-4", "-3", "-2", "-1"},
            defaultValue = -1
    )
    public int exposureBalanceIsoLimit = -1;

    @SensorConfig(
            title = "Shutter Limit",
            description = "Ограничение наибольшей выдержки",
            entries = {
                    "1/500", "1/250", "1/125", "1/90", "1/60", "1/45", "1/30", "1/20", 
                    "1/15", "1/10", "1/8", "1/6", "1/4", 
                    "1/3", "1/2", "1.0", "2.0", "Auto Safe (Lens Reciprocal)", "Sensor Max Time"
            },
            entryValues = {
                    "0.002", "0.004", "0.008", "0.0111", "0.0167", "0.0222", "0.0333", "0.05", 
                    "0.0667", "0.1", "0.125", "0.1667", "0.25", 
                    "0.3333", "0.5", "1.0", "2.0", "-2", "-1"
            },
            defaultValue = -1.0f
    )
    public float exposureBalanceShutterLimit = -1.0f;

    private static int mTargetFormat = RAW_FORMAT;
    private ManualModeConsole manualModeConsole;
    private final ParamController paramController;
    public TouchFocus mTouchFocus;

    public final boolean mFlashEnabled = false;
    private CameraEventsListener cameraEventsListener;
    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);
    /**
     * Guards {@link #openCamera(int, int)} against duplicate concurrent opens.
     * Held from the moment an open is requested until the device is closed
     * again (see {@link #closeCamera()} and the {@link CameraDevice} callbacks).
     */
    private final AtomicBoolean mCameraOpening = new AtomicBoolean(false);
    /**
     * True only while the camera fragment is foregrounded (between
     * {@link #resumeCamera()} and the next {@link #closeCamera()}). Open
     * requests and onOpened() deliveries that arrive after backgrounding are
     * dropped so a hidden app never grabs or holds the camera device.
     */
    private volatile boolean isCameraResumed = false;
    private CameraManager mCameraManager;
    private CameraManager2 mCameraManager2;
    private Activity activity;
    public long mPreviewExposureTime;
    /**
     * ID of the current {@link CameraDevice}.
     */
    public int mPreviewIso;
    public Rational[] mPreviewTemp;
    public ColorSpaceTransform mColorSpaceTransform;
    /**
     * A reference to the opened {@link CameraDevice}.
     */
    public CameraDevice mCameraDevice;
    /*A {@link Handler} for running tasks in the background.*/
    public Handler mBackgroundHandler;
    /*An {@link ImageReader} that handles still image capture.*/
    public ImageReader mImageReaderPreview;
    public volatile ImageReader mImageReaderRaw;
    /*{@link CaptureRequest.Builder} for the camera preview*/
    public volatile CaptureRequest.Builder mPreviewRequestBuilder;
    public CaptureRequest mPreviewInputRequest;
    /**
     * The current state of camera state for taking pictures.
     */
    public int mState = STATE_PREVIEW;
    /**
     * Orientation of the camera sensor
     */
    public int mSensorOrientation;
    public int cameraRotation;
    public boolean onUnlimited = false;
    public boolean unlimitedStarted = false;
    public boolean mFlashed = false;
    public ArrayList<GyroBurst> BurstShakiness;
    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    public ImageSaver mImageSaver;
    public HashMap<Long, Double> mExposures = new HashMap<>();
    private final java.util.concurrent.ConcurrentSkipListMap<Long,CaptureResult> rawMetadata =
            new java.util.concurrent.ConcurrentSkipListMap<>();
    private void rememberRawMetadata(CaptureResult result) {
        Long timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP);
        if(timestamp==null || timestamp<=0) return;
        rawMetadata.put(timestamp,result);
        while(rawMetadata.size()>128) rawMetadata.pollFirstEntry();
    }
    public CaptureResult takeRawMetadata(long timestamp) { return rawMetadata.remove(timestamp); }


    private final ArrayDeque<Image> mZslRingBuffer = new ArrayDeque<>();
    private final Object mZslBufferLock = new Object();
    private final java.util.LinkedHashMap<Long, TotalCaptureResult> mHexZslResults = new java.util.LinkedHashMap<>();
    private volatile boolean mZslCapturing = false;
    /**
     * True from the shutter press until the burst's frames have been handed to
     * processing. The raw viewfinder must not intercept frames during that
     * window: they belong to the shot.
     */
    private volatile boolean mShotInProgress = false;
    // Stock-like shot-to-shot: a finished NICE burst is processed here while the
    // camera already takes the next one. No count limit: each queued shot holds
    // ~175 MB of RAW, so only free system memory bounds the queue.
    private static final ExecutorService NICE_PROCESSING = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SCAMERA-NICE-processing");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final java.util.concurrent.atomic.AtomicInteger sNicePending = new java.util.concurrent.atomic.AtomicInteger();
    private long mShutterGeneration;
    private volatile boolean mLiveRawSession;
    /**
     * P13: the stream of this session is a colour-block mosaic (a sensor mode without remosaic, MosaicStream): its ISP preview
     * is purple, so the ZSL frames also go to the developed-RAW viewfinder, whose shader bins the colour blocks.
     */
    private volatile boolean mMosaicPreview;
    /** nice_dev.txt "mosaic_preview 0": no measurement and no mosaic preview (diagnostics). */
    private volatile boolean mMosaicMeasure = true;
    private volatile boolean mNativeRawPslCapture;
    private TotalCaptureResult mNativeZslBase;
    private boolean mLiveRawRejected;
    private final PreviewFrameMatcher<Image, TotalCaptureResult> mLiveMetadata =
            new PreviewFrameMatcher<>(this::onMatchedLiveRaw, Image::close);
    private final TimestampFrameRouter<Image> mLiveRawRouter = new TimestampFrameRouter<>((image, still) -> {
        if (still) mImageSaver.initProcess(image);
        else if (mZslCapturing) image.close();
        else mLiveMetadata.image(image.getTimestamp(), image);
    }, Image::close);
    /**
     * CFA pattern of the stream being previewed, resolved once per session.
     *
     * mCameraCharacteristics is swapped between logical and physical cameras
     * while the session runs, so reading the pattern per frame returned BGGR on
     * some frames and RGGB on others - the demosaic then decoded the same
     * sensor two different ways from one frame to the next, which is the colour
     * flickering.
     */
    private volatile int mLiveCfaPattern = -1;
    private volatile boolean mHybridZslCapture = false;
    // NICE: keep pre-shutter RAWs while waiting for the stock AE plan.
    private volatile boolean mNiceRingFrozen = false;
    // NICE fast shutter: the preview keeps running during the L/S/ES tail and RAWs
    // are routed by capture-start timestamp (bracket -> saver, preview -> dropped)
    // instead of stopRepeating + restart, which froze the viewfinder, emptied the
    // ZSL ring and threw AE off for the next shot.
    private volatile boolean mNiceRouted = false;
    // Shutter presses made while a NICE shot is still capturing; fired in order.
    private int mNiceQueuedShots = 0;
    // Sensor timestamp of the newest tail frame (L/S/ES) of the current NICE shot.
    private volatile long mNiceTailTimestamp = 0;
    private volatile boolean mNiceFireWaiting = false;
    private volatile long mNicePlanDeadline;
    private List<ImageFrame> mPendingZslNormalFrames = new ArrayList<>();
    private volatile java.util.concurrent.CountDownLatch mZslCopyDone;
    // The stock-AE observer attaches to the camera provider after the session
    // starts; a queue flush while it attaches hung the HAL once (no bracket for
    // 65 s, provider restarted). Flush only once the observer has delivered a plan.
    private volatile boolean mStockObserverReady;

    private final ImageReader.OnImageAvailableListener mOnYuvImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            //mImageSaver.mImage = reader.acquireNextImage();
            //mImageSaver.initProcess(reader);
//            Message msg = new Message();
//            msg.obj = reader;
//            mImageSaver.processingHandler.sendMessage(msg);
            //processExecutor.execute(() -> mImageSaver.initProcess(reader));
            mImageSaver.initProcess(reader);
        }
    };
    private final ImageReader.OnImageAvailableListener mOnRawImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            if (!isCameraResumed || reader != mImageReaderRaw) {
                try { Image stale=reader.acquireLatestImage(); if(stale!=null)stale.close(); }
                catch (IllegalStateException ignored) { }
                return;
            }

            //dequeueAndSaveImage(mRawResultQueue, mRawImageReader);
            //mImageSaver.mImage = reader.acquireNextImage();
//            Message msg = new Message();
//            msg.obj = reader;
//            mImageSaver.processingHandler.sendMessage(msg);
            if (mNativeRawPslCapture || mNiceRouted || (!isZslMode() && mLiveRawSession)) {
                // A shutter press is not a frame boundary: old preview images can arrive during AF.
                // Route by the matching capture-start timestamp, never by the current UI state.
                Image image;
                try { image = reader.acquireNextImage(); } catch (IllegalStateException ex) { return; }
                if (image != null) mLiveRawRouter.image(image.getTimestamp(), image);
                return;
            }
            if (isZslMode()) {
                if (mHybridZslCapture) {
                    mImageSaver.initProcess(reader);
                    return;
                }
                Image img;
                try { img = reader.acquireNextImage(); } catch (IllegalStateException closed) { return; }
                if (img == null) return;
                if (mZslCapturing) {
                    img.close();
                    return;
                }
                if (mLiveRawSession) {
                    mLiveMetadata.image(img.getTimestamp(), img);
                    return;
                }
                watchRawPayload(img);
                observeMosaic(img);
                synchronized (mZslBufferLock) {
                    if (!isCameraResumed || reader != mImageReaderRaw) { img.close(); return; }
                    mZslRingBuffer.addLast(img);
                    int maxFrames = zslRingCapacity();
                    while (mZslRingBuffer.size() > maxFrames) {
                        Image old = mZslRingBuffer.pollFirst();
                        if (old != null) old.close();
                    }
                }
                return;
            }
            if (onUnlimited && !unlimitedStarted) {
                return;
            }

            //This code creates single frame bugs
            //taskResults.removeIf(Future::isDone); //remove already completed results
            //Future<?> result = processExecutor.submit(() -> mImageSaver.initProcess(reader));
            //taskResults.add(result);
            if(PhotonCamera.getSettings().frameCount != 1) {
                //taskResults.removeIf(Future::isDone); //remove already completed results
                //Future<?> result = processExecutor.submit(() -> mImageSaver.initProcess(reader));
                //taskResults.add(result);
                //processExecutor.execute(() -> mImageSaver.initProcess(reader));
                mImageSaver.initProcess(reader);
                //mBackgroundHandler.post(() -> mImageSaver.initProcess(reader));
                //AsyncTask.execute(() -> mImageSaver.initProcess(reader));
            }
            else {
                mBackgroundHandler.post(() -> mImageSaver.initProcess(reader));
                //mImageSaver.initProcess(reader);
                //processExecutor.execute(() -> mImageSaver.initProcess(reader));
            }
        }

    };
    private Range<Integer> FpsRangeAuto;
    private int[] mCameraAfModes;
    private int mPreviewWidth;
    private int mPreviewHeight;
    private ArrayList<CaptureRequest> captures;
    private CameraCaptureSession.CaptureCallback CaptureCallback;
    /**
     * P27: completes the shot in flight with the frames that arrived (the outstanding requests count as lost); null when no
     * shot is in flight. A camera close, error, disconnect or a stalled HAL then still gives a photo and frees the shutter.
     */
    private volatile Runnable mInFlightRescue;
    /** P27: manual ISO / shutter this camera's HAL honours (learned from shots; null before the first session). */
    private volatile ExposureLimits mExposureLimits;
    /** Unpacked copy of a RAW10 / RAW12 preview frame for the mosaic measurement (reused). */
    private java.nio.ByteBuffer mMosaicUnpacked;
    /** P30: the arena of the shot being set up / in flight (released when the shot completes or fails). */
    private volatile com.particlesdevs.photoncamera.util.ShotArena mShotArena;
    /** P27: watchRawPayload's session restart waits until the shot in flight is complete. */
    private volatile boolean mPendingPayloadRestart;
    private File vid = null;
    public int mMeasuredFrameCnt;
    public static boolean isProcessing;
    /**
     * An {@link AutoFitPreviewView} for camera preview.
     */
    private GLPreview mTextureView;
    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private volatile CameraCaptureSession mCaptureSession;
    private final Object mPreviewStateLock = new Object();
    private final java.util.concurrent.atomic.AtomicInteger mSessionGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private volatile VivoStockAe mStockAe;
    private int mConfiguredSessionGeneration = -1;
    /**
     * MediaRecorder
     */
    private MediaRecorder mMediaRecorder;
    /**
     * Whether the app is recording video now
     */
    public boolean mIsRecordingVideo;
    private Size target;
    public float mFocus;
    public int mPreviewAFMode;
    public int mPreviewAEMode;
    public MeteringRectangle[] mPreviewMeteringAF;
    public MeteringRectangle[] mPreviewMeteringAE;
    private MeteringRectangle[] mInitialMeteringAF;
    private MeteringRectangle[] mInitialMeteringAE;
    /**
     * The {@link Size} of camera preview.
     */
    public Size mPreviewSize;
    public Size mBufferSize;
    /*An additional thread for running tasks that shouldn't block the UI.*/
    private HandlerThread mBackgroundThread;
    /**
     * Timer to use with pre-capture sequence to ensure a timely capture if 3A convergence is
     * taking too long.
     */
    private long mCaptureTimer;
    /**
     * Whether the current camera device supports Flash or not.
     */
    private boolean mFlashSupported;
    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    public static boolean burst = false;
    private static volatile long sTimelineSubmitNs;
    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    public ProcessCallbacks debugCallback = new ProcessCallbacks();
    private final CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            synchronized (mPreviewStateLock) {
                if (!isCurrentPreviewSession(session)) return;
                if (mNativeRawPslCapture || mNiceRouted || (mLiveRawSession && !isZslMode())) mLiveRawRouter.request(timestamp, false);
            }
        }


        private void process(CaptureResult result) {
            debugCallback.process();
            switch (mState) {
                case STATE_PREVIEW:
                    previewProcess();
                    break;
                case STATE_WAITING_LOCK:
                    waitingLockProcess(result);
                    break;
                case STATE_WAITING_PRECAPTURE:
                    waitingPrecaptureProcess(result);
                    break;
                case STATE_WAITING_NON_PRECAPTURE:
                    waitingNonPrecaptureProcess(result);
                    break;
            }
        }

        private void previewProcess() {
            // We have nothing to do when the camera preview is working normally.
            //Log.v(TAG, "PREVIEW");
        }

        private void waitingLockProcess(CaptureResult result) {
            //Log.v(TAG, "WAITING_LOCK");
            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
            // If we haven't finished the pre-capture sequence but have hit our maximum
            // wait timeout, too bad! Begin capture anyway.
            if (hitTimeoutLocked()) {
                Log.w(TAG, "Timed out waiting for pre-capture sequence to complete.");
                mState = STATE_PICTURE_TAKEN;
                captureStillPicture();
                return;
            }
            if (afState == null) {
                mState = STATE_PICTURE_TAKEN;
                captureStillPicture();
            } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                // CONTROL_AE_STATE can be null on some devices
                Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                    mState = STATE_PICTURE_TAKEN;
                    captureStillPicture();
                } else {
                    runPreCaptureSequence();
                }
            }
        }

        private void waitingPrecaptureProcess(CaptureResult result) {
            Log.v(TAG, "WAITING_PRECAPTURE");
            // CONTROL_AE_STATE can be null on some devices
            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
            if (hitTimeoutLocked()) {
                mState = STATE_PICTURE_TAKEN;
                captureStillPicture();
                return;
            }
            if (aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                    aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                mState = STATE_WAITING_NON_PRECAPTURE;
            }
            if (paramController.isManualMode())
                mState = STATE_WAITING_NON_PRECAPTURE;
        }

        private void waitingNonPrecaptureProcess(CaptureResult result) {
            // CONTROL_AE_STATE can be null on some devices
            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
            if (hitTimeoutLocked() || aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                mState = STATE_PICTURE_TAKEN;
                captureStillPicture();
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            synchronized (mPreviewStateLock) {
                if (!isCurrentPreviewSession(session)) return;
                process(partialResult);
                if (mTouchFocus != null) {
                    mTouchFocus.onCaptureResult(partialResult);
                }
            }
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            synchronized (mPreviewStateLock) {
                if (!isCurrentPreviewSession(session)) return;
                Object exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Object iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                Object focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                Rational[] mTemp = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
                if (exposure != null) mPreviewExposureTime = (long) exposure;
                if (iso != null) mPreviewIso = (int) iso;
                if (iso != null) widenSensitivityRange((int) iso);
                // P27: the AE restore frame after a shot is a manual request at N: it re-checks the learned limits.
                final ExposureLimits limits = mExposureLimits;
                if (limits != null) limits.observeManual(request, result);
                if (focus != null) mFocus = (float) focus;
                if (mTemp != null) mPreviewTemp = mTemp;
                if (mPreviewTemp == null) {
                    mPreviewTemp = new Rational[3];
                    for (int i = 0; i < mPreviewTemp.length; i++)
                        mPreviewTemp[i] = new Rational(101, 100);
                }
                mColorSpaceTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
                Integer state = result.get(CaptureResult.FLASH_STATE);
                mFlashed = state != null && (state == CaptureResult.FLASH_STATE_PARTIAL || state == CaptureResult.FLASH_STATE_FIRED);
                if (isZslMode()) {
                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (timestamp != null) synchronized (mZslBufferLock) {
                        mHexZslResults.put(timestamp, result);
                        while (mHexZslResults.size() > zslRingCapacity()+16)
                            mHexZslResults.remove(mHexZslResults.keySet().iterator().next());
                    }
                }
                if (mLiveRawSession && session == mCaptureSession) {
                    Long rawTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (rawTimestamp != null) mLiveMetadata.result(rawTimestamp, result);
                    if (Build.VERSION.SDK_INT >= 28) {
                        CaptureResult physicalResult = result.getPhysicalCameraResults().get(physicalID);
                        Long physicalTimestamp = physicalResult == null ? null : physicalResult.get(CaptureResult.SENSOR_TIMESTAMP);
                        if (physicalTimestamp != null && !physicalTimestamp.equals(rawTimestamp))
                            mLiveMetadata.result(physicalTimestamp, result);
                    }
                }
                mPreviewCaptureRequest = request;
                mPreviewCaptureResult = result;
                if(PreferenceKeys.isVivoNiceEnabled() && PreferenceKeys.useStockBracketPlanner() && isCurrentPreviewSession(session)) {
                    VivoStockAe stock=mStockAe;
                    if(stock==null || stock.generation!=mSessionGeneration.get()) {
                        if(stock!=null)stock.close();
                        stock=new VivoStockAe(PhotonCamera.getAppContext(),mSessionGeneration.get(),physicalID);
                        mStockAe=stock;
                    }
                    stock.offer(result);
                }
                process(result);
                if (mTouchFocus != null) {
                    mTouchFocus.onCaptureResult(result);
                }
                cameraEventsListener.onPreviewCaptureCompleted(result);
                if(PreferenceKeys.getAfMode() == CaptureRequest.CONTROL_AF_MODE_AUTO && !burst && (mTouchFocus == null || !mTouchFocus.isTouchFocus)) {
                    CaptureRequest.Builder builder = mPreviewRequestBuilder;
                    if (builder != null && isCurrentPreviewSession(session)) {
                        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                        rebuildPreviewBuilderOneShot();
                    }
                }
            }
        }

    };
    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            synchronized (mPreviewStateLock) {
                // This method is called when the camera is opened.  We start camera preview here.
                mCameraOpenCloseLock.release();
                mCameraOpening.set(false);
                if (!isCameraResumed) {
                    // The app was backgrounded while the open was in flight; a
                    // hidden activity must not hold the camera device.
                    Log.d(TAG, "onOpened(): fragment already paused, closing device");
                    cameraDevice.close();
                    return;
                }
                mCameraDevice = cameraDevice;
                mImageSaver = new ImageSaver(cameraEventsListener);
                createCameraPreviewSession(false);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            rescueInFlightShot("camera disconnected");
            mCameraOpenCloseLock.release();
            mCameraOpening.set(false);
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            rescueInFlightShot("camera error " + error);
            mCameraOpenCloseLock.release();
            mCameraOpening.set(false);
            cameraDevice.close();
            mCameraDevice = null;
            Log.w(TAG, "onError() : cameraDevice = [" + cameraDevice + "], error = [" + error + "]");
            if (error == ERROR_CAMERA_DEVICE || error == ERROR_CAMERA_SERVICE) {
                showToast(Lang.t("Сбой камеры (код ", "Camera failure (code ") + error + Lang.t("), перезапуск", "), restarting"));
                scheduleRecovery("onError " + error);
            } else {
                showToast(Lang.t("Камера недоступна (код ", "Camera unavailable (code ") + error + ")");
            }
        }
    };

    // Recovery after the camera HAL or service died: the vivo X100 Ultra (owner's log 2026-10-05) answered
    // ERROR_CAMERA_DEVICE, every id was 'unknown device' for ~4.5 s while the provider restarted, and nothing reopened the
    // camera until the user tapped a module (27 s of a dead viewfinder once). 1, 2, 4 s, at most three times a minute.
    private android.os.Handler mRecoveryHandler;
    private final java.util.ArrayDeque<Long> mRecoveries = new java.util.ArrayDeque<>();

    private void scheduleRecovery(String reason) {
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (mRecoveries) {
            while (!mRecoveries.isEmpty() && now - mRecoveries.peekFirst() > 60_000) mRecoveries.pollFirst();
            if (mRecoveries.size() >= 3) {
                Log.e(TAG, "camera recovery stopped after three restarts within a minute (" + reason + ")");
                showToast(Lang.t("Камера не отвечает. Выберите другой модуль или перезапустите приложение", "The camera is not responding. Choose another module or restart the app"));
                return;
            }
            mRecoveries.addLast(now);
        }
        long delay = 1000L << (mRecoveries.size() - 1);
        Log.w(TAG, "camera recovery in " + delay + " ms (" + reason + ")");
        if (mRecoveryHandler == null) mRecoveryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mRecoveryHandler.postDelayed(() -> {
            if (isCameraResumed && mCameraDevice == null && !mCameraOpening.get()) restartCamera();
        }, delay);
    }
    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    public final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
            // The availability callback is delivered through the main-thread
            // handler; if the GL surface was recreated in the meantime, verify
            // against an already active texture before dropping the request.
            SurfaceTexture currentTexture = (mTextureView != null) ? mTextureView.getSurfaceTexture() : null;
            if (currentTexture != null && texture != currentTexture) {
                Log.d(TAG, "onSurfaceTextureAvailable(): stale texture ignored");
                return;
            }
            try {
                String curID = PhotonCamera.getSettings().mCameraID;
                if(curID.contains("-")){
                    logicalID = curID.split("-")[0];
                    physicalID = curID.split("-")[1];
                } else {
                    logicalID = curID;
                    physicalID = curID;
                }
                
                Log.d(TAG, "ID:" + mCameraCharacteristicsMap.get(physicalID));
                // list available characteristics ids
                for (String id : mCameraCharacteristicsMap.keySet()) {
                    Log.d(TAG, "Available camera ID: " + id);
                }
                CameraCharacteristics chars = mCameraCharacteristicsMap.get(physicalID);
                if (chars == null) {
                    Log.e(TAG, "No characteristics for physicalID=" + physicalID
                            + " (mCameraID=" + PhotonCamera.getSettings().mCameraID + "). Falling back to first available.");
                    if (!mCameraCharacteristicsMap.isEmpty()) {
                        Map.Entry<String, CameraCharacteristics> first = mCameraCharacteristicsMap.entrySet().iterator().next();
                        physicalID = first.getKey();
                        logicalID = physicalID;
                        PhotonCamera.getSettings().mCameraID = physicalID;
                        chars = first.getValue();
                    } else {
                        showToast("No cameras available");
                        return;
                    }
                }
                Size optimal = getPreviewOutputSize(getSafeDisplay(), chars,
                        PhotonCamera.getSettings().selectedMode);
                openCamera(optimal.getWidth(), optimal.getHeight());
            } catch (Exception e){
                Log.e(TAG,Log.getStackTraceString(e));
                showToast("Error onSurfaceTextureAvailable:"+e.getLocalizedMessage());
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
            Log.d(TAG, " CHANGED SIZE:" + width + ' ' + height);
            configureTransform(width, height);
        }

        @Override

        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
        }

    };
    public CaptureController(Activity activity, ExecutorService processExecutor, CameraEventsListener cameraEventsListener) {
        if(PhotonCamera.getSettings().previewFormat != 0) {
            mPreviewTargetFormat = PhotonCamera.getSettings().previewFormat;
        } else {
            mPreviewTargetFormat = ImageFormat.JPEG;
        }
        this.activity = activity;
        this.cameraEventsListener = cameraEventsListener;
        this.mTextureView = activity.findViewById(R.id.texture);
        this.mCameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        // Before the first camera list read: vendors filter hidden cameras by package.
        com.particlesdevs.photoncamera.capture.spoof.CameraPackageSpoof.apply(activity, mCameraManager);
        this.mCameraManager2 = new CameraManager2(mCameraManager, PhotonCamera.getInstance(activity).getSettingsManager());
        PreferenceKeys.addIds(mCameraManager2.getCameraIdList());

        this.processExecutor = processExecutor;
        this.paramController = new ParamController(this);

        this.fillInCameraCharacteristics();
    }

    /**
     * Fills in {@link CaptureController#mCameraCharacteristicsMap} that is used in
     * {@link CaptureController#UpdateCameraCharacteristics}.
     */
    private void fillInCameraCharacteristics() {
        try {
            String[] cameraIds = mCameraManager2.getCameraIdList();
            for (String cameraId : cameraIds) {
                String physicalID = cameraId;
                if(cameraId.contains("-")){
                    physicalID = cameraId.split("-")[1];
                }
                mCameraCharacteristicsMap.put(physicalID, mCameraManager.getCameraCharacteristics(physicalID));
            }
        } catch (CameraAccessException cameraAccessException) {
            // Should not be possible to get here but anyway
            cameraAccessException.printStackTrace();
            showToast("Failed to fetch camera characteristics: " + cameraAccessException.getLocalizedMessage());
        }

    }

    public ManualModeConsole getManualModeConsole() {
        return manualModeConsole;
    }

    public void setManualModeConsole(ManualModeConsole manualModeConsole) {
        this.manualModeConsole = manualModeConsole;
    }

    public ParamController getParamController() {
        return paramController;
    }

    public static int getTargetFormat() {
        return mTargetFormat;
    }

    public static void setTargetFormat(int targetFormat) {
        mTargetFormat = targetFormat;
    }
    /** RAW_SENSOR (16-bit container), RAW10 or RAW12 — every one is unpacked to uint16 on copy. */
    public static boolean isRawFormat(int format) {
        return format == ImageFormat.RAW_SENSOR || format == ImageFormat.RAW10 || format == ImageFormat.RAW12;
    }
    /**
     * The RAW stream format of this camera: pref_raw_stream_format auto (RAW_SENSOR when the camera offers it,
     * else RAW10, else RAW12), or the forced format when the camera offers it. ZSL / live RAW stay RAW_SENSOR-only.
     */
    private static int resolveRawFormat(StreamConfigurationMap map) {
        String mode = PreferenceKeys.getRawStreamFormat();
        int[] order;
        if ("raw10".equals(mode)) order = new int[]{ImageFormat.RAW10, ImageFormat.RAW_SENSOR, ImageFormat.RAW12};
        else if ("raw12".equals(mode)) order = new int[]{ImageFormat.RAW12, ImageFormat.RAW_SENSOR, ImageFormat.RAW10};
        else order = new int[]{ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.RAW12};
        if (map != null) for (int format : order) {
            Size[] sizes = map.getOutputSizes(format);
            if (sizes != null && sizes.length > 0) return format;
        }
        return ImageFormat.RAW_SENSOR;
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     *                          class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int targetWidth = aspectRatio.getWidth();
        int targetHeight = aspectRatio.getHeight();
        for (Size option : choices) {
            int width = option.getWidth();
            int height = option.getHeight();
            boolean isAspectRatioMatching = (height * targetWidth == width * targetHeight);

            if (width <= maxWidth && height <= maxHeight && isAspectRatioMatching) {
                if (width >= textureViewWidth && height >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough.
        // If there is no one big enough, pick the largest of those not big enough.
        if (!bigEnough.isEmpty()) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (!notBigEnough.isEmpty()) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    private Size getCameraOutputSize(Size[] sizes) {
        if (sizes != null) {
            if (sizes.length > 0) {
                Arrays.sort(sizes, new CompareSizesByArea());

                int largestSizeIdx = sizes.length - 1;
                int largestSizeArea = sizes[largestSizeIdx].getWidth() * sizes[largestSizeIdx].getHeight();

                if (largestSizeArea <= ResolutionSolution.highRes) {
                    target = sizes[largestSizeIdx];
                    return target;
                } else if (sizes.length > 1) {
                    target = sizes[largestSizeIdx - 1];
                    return target;
                }
            }
        }
        return null;
    }

    /**
     * For test method {@link CaptureController#getCameraOutputSize(Size[])}
     */
    @TestOnly
    private static Size getCameraOutputSizeTest(Size[] sizes) {
        if (sizes != null) {
            if (sizes.length > 0) {
                Arrays.sort(sizes, new CompareSizesByArea());

                int largestSizeIdx = sizes.length - 1;
                int largestSizeArea = sizes[largestSizeIdx].getWidth() * sizes[largestSizeIdx].getHeight();

                if (largestSizeArea <= ResolutionSolution.highRes) {
                    return sizes[largestSizeIdx];
                } else if (sizes.length > 1) {
                    return sizes[largestSizeIdx - 1];
                }
            }
        }
        return null;
    }

    private Size getCameraOutputSize(Size[] sizes, Size previewSize) {
        if (sizes == null || sizes.length == 0) return previewSize;

        Arrays.sort(sizes, new CompareSizesByArea());
        int largestSizeIdx = sizes.length - 1;
        int largestSizeArea = sizes[largestSizeIdx].getWidth() * sizes[largestSizeIdx].getHeight();

        if (PhotonCamera.getSettings().QuadBayer) {
            target = sizes[largestSizeIdx];
            Rect preCorrectionActiveArraySize = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
            Rect activeArraySize = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (preCorrectionActiveArraySize != null && activeArraySize != null) {
                double k = (double) (target.getHeight()) / activeArraySize.bottom;
                mul(preCorrectionActiveArraySize, k);
                mul(activeArraySize, k);
                CameraReflectionApi.set(mCameraCharacteristics, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, activeArraySize);
                CameraReflectionApi.set(mCameraCharacteristics, CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE, preCorrectionActiveArraySize);
            }
            return target;
        }

        int[] preferred = PhotonCamera.getSpecificSensor().selectedSensorSpecifics.preferredResolution;
        if (preferred != null && preferred.length >= 2) {
            for (Size size : sizes) {
                if (size.getWidth() == preferred[0] && size.getHeight() == preferred[1]) {
                    target = size;
                    return target;
                }
            }
        }

        if (largestSizeArea <= ResolutionSolution.highRes) {
            target = sizes[largestSizeIdx];
            return target;
        } else if (sizes.length > 1) {
            target = sizes[largestSizeIdx - 1];
            return target;
        }
        return previewSize;
    }

    /**
     * For test method {@link CaptureController#getCameraOutputSize(Size[], Size)}
     */
    @TestOnly
    private static Size getCameraOutputSizeTest(Size[] sizes, Size previewSize) {
        if (sizes == null || sizes.length == 0) return previewSize;

        Size temp = null;

        Arrays.sort(sizes, new CompareSizesByArea());
        int largestSizeIdx = sizes.length - 1;
        int largestSizeArea = sizes[largestSizeIdx].getWidth() * sizes[largestSizeIdx].getHeight();

        if (largestSizeArea <= ResolutionSolution.highRes || PhotonCamera.getSettings().QuadBayer) {
            temp = sizes[largestSizeIdx];
            if (PhotonCamera.getSettings().QuadBayer) {
                Rect preCorrectionActiveArraySize = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
                Rect activeArraySize = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

                if (preCorrectionActiveArraySize != null && activeArraySize != null) {
                    double k = (double) (temp.getHeight()) / activeArraySize.bottom;
                    mulForTest(preCorrectionActiveArraySize, k);
                    mulForTest(activeArraySize, k);
                    CameraReflectionApi.set(mCameraCharacteristics, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, activeArraySize);
                    CameraReflectionApi.set(mCameraCharacteristics, CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE, preCorrectionActiveArraySize);
                }
            }
            return temp;
        } else if (sizes.length > 1) {
            temp = sizes[largestSizeIdx - 1];
            return temp;
        }
        return previewSize;
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private void setUpCameraOutputs(int width, int height) {
        try {
            mPreviewWidth = width;
            mPreviewHeight = height;
            String curID = PhotonCamera.getSettings().mCameraID;
            if(curID.contains("-")) {
                logicalID = curID.split("-")[0];
                physicalID = curID.split("-")[1];
            } else {
                logicalID = curID;
                physicalID = logicalID;
            }
            
            UpdateCameraCharacteristics(physicalID);
            //Thread thr = new Thread(mImageSaver);
            //thr.start();
        } catch (Exception e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            Log.e(TAG, Log.getStackTraceString(e));
            showToast(activity.getString(R.string.camera_error));
            //cameraEventsListener.onError(R.string.camera_error);
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private boolean isCurrentPreviewSession(CameraCaptureSession session) {
        return isCameraResumed && session != null && session == mCaptureSession
                && mConfiguredSessionGeneration == mSessionGeneration.get() && mPreviewRequestBuilder != null;
    }

    private void clearZslPreviewFrames() {
        synchronized (mZslBufferLock) {
            mHexZslResults.clear();
            while (!mZslRingBuffer.isEmpty()) mZslRingBuffer.removeFirst().close();
        }
    }

    public void closeCamera() {
        rescueInFlightShot("camera closed");
        mNiceQueuedShots = 0;
        VivoStockAe stock=mStockAe;mStockAe=null;if(stock!=null)stock.close();
        mNiceRingFrozen=false;
        // Invalidate callbacks before releasing their resources. onConfigured and
        // capture results can still arrive after returning from Settings.
        synchronized (mPreviewStateLock) {
            isCameraResumed = false;
            mSessionGeneration.incrementAndGet();
            mPreviewRequestBuilder = null;
            mPreviewCaptureResult = null;
            mPreviewCaptureRequest = null;
            mShotInProgress = false;
        }
        clearZslPreviewFrames();
        LiveRawFrame.setEnabled(false);
        LiveRawFrame.setMosaicPreview(false);
        mLiveRawSession = false;
        mMosaicPreview = false;
        mNativeRawPslCapture = false;
        mLiveRawRouter.clear();
        mLiveMetadata.clear();
        mCameraOpening.set(false);
        try {
            mCameraOpenCloseLock.acquire();
            CameraCaptureSession closingSession = mCaptureSession;
            mCaptureSession = null;
            if (closingSession != null) closingSession.close();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (!isProcessing) {
                if (mImageReaderPreview != null) {
                    mImageReaderPreview.close();
                    mImageReaderPreview = null;
                }
                ImageReader closingRaw = mImageReaderRaw;
                mImageReaderRaw = null;
                if (closingRaw != null) closingRaw.close();
            }
            if (null != mMediaRecorder) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
            mState = STATE_CLOSED;
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    public void startBackgroundThread() {
        if (mBackgroundThread == null) {
            mBackgroundThread = new HandlerThread("CameraBackground");
            mBackgroundThread.start();
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
            Log.d(TAG, "startBackgroundThread() called from \"" + Thread.currentThread().getName() + "\" Thread");
        }
        //mBackgroundHandler.post(mImageSaver);
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    public void stopBackgroundThread() {
        if (mBackgroundThread == null)
            return;
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
            Log.d(TAG, "stopBackgroundThread() called from \"" + Thread.currentThread().getName() + "\" Thread");
        } catch (InterruptedException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

//    public void rebuildPreview() {
//        try {
////            mCaptureSession.stopRepeating();
//            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
//        } catch (CameraAccessException e) {
//            Log.e(TAG, Log.getStackTraceString(e));
//        }
//    }

    private Range<Integer> getSelectedFpsRange() {
        switch (PhotonCamera.getSettings().fpsMode) {
            case 1: return new Range<>(24, 24);
            case 2: return new Range<>(30, 30);
            case 3: return new Range<>(60, 60);
            default: return FpsRangeAuto;
        }
    }
    
    /** Digital/optical zoom of the current module: CONTROL_ZOOM_RATIO (R+) or SCALER_CROP_REGION. */
    private void applyZoom(CaptureRequest.Builder builder, boolean force) {
        // P17: the Xiaomi 17 Ultra tele zooms optically (75-100 mm) and by ISZ (150-200 mm) over vendor keys
        if (builder != null && mCameraCharacteristics != null) {
            try {
                boolean wasIsz = XiaomiTeleZoom.isz();
                XiaomiTeleZoom.Plan plan = XiaomiTeleZoom.apply(builder, mCameraCharacteristics,
                        PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().getBoolean(XiaomiTeleZoom.PREF, true),
                        com.particlesdevs.photoncamera.settings.ModuleRegistry.zoom(com.particlesdevs.photoncamera.settings.ModuleRegistry.active()),
                        com.particlesdevs.photoncamera.control.ZoomController.zoom(), physicalID);
                if (plan != null) {
                    com.particlesdevs.photoncamera.control.ZoomController.overrideResidual(plan.residual);
                    if (plan.isz != wasIsz) onSensorModeChangedInSession(plan.isz);
                    return;
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Xiaomi tele zoom: " + e.getMessage());
            }
        }
        final float z = com.particlesdevs.photoncamera.control.ZoomController.residual();
        if (builder == null || (z <= 1.001f && !force)) return;
        try {
            final CameraCharacteristics characteristics = mCameraCharacteristics;
            if (characteristics == null) return;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.util.Range<Float> range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (range != null) {
                    builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, Math.max(range.getLower(), Math.min(range.getUpper(), z)));
                    return;
                }
            }
            android.graphics.Rect active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active == null) return;
            int w = Math.round(active.width() / z), h = Math.round(active.height() / z);
            int x = active.left + (active.width() - w) / 2, y = active.top + (active.height() - h) / 2;
            builder.set(CaptureRequest.SCALER_CROP_REGION, new android.graphics.Rect(x, y, x + w, y + h));
        } catch (RuntimeException e) {
            Log.w(TAG, "applyZoom: " + e.getMessage());
        }
    }

    /**
     * The stream changed its sensor mode inside the session (Xiaomi ISZ): the buffered ZSL frames belong to the other mode and
     * are dropped as on a module switch, and the colour block of the stream is measured again (ISZ may deliver a Quad mosaic).
     */
    private void onSensorModeChangedInSession(boolean isz) {
        clearZslPreviewFrames();
        com.particlesdevs.photoncamera.processing.MosaicStream.startSession(mosaicStreamKey() + "|isz=" + isz);
        if (mMosaicPreview && com.particlesdevs.photoncamera.processing.MosaicStream.block() <= 1) {
            mMosaicPreview = false;
            LiveRawFrame.setMosaicPreview(false);
            if (!mLiveRawSession) LiveRawFrame.setEnabled(false);
        }
        Log.i(TAG, "sensor mode changed in session (ISZ " + (isz ? "on" : "off") + "): ZSL ring dropped, colour block measured again");
    }

    /** One zoom update waits at a time: the slider sends many, the request is rebuilt at most once per pending update. */
    private final java.util.concurrent.atomic.AtomicBoolean mZoomPending = new java.util.concurrent.atomic.AtomicBoolean();

    /** Zoom changed inside the current module: push it to the repeating preview request. */
    public void onZoomChanged() {
        Handler handler = mBackgroundHandler;
        Runnable update = () -> {
            mZoomPending.set(false);
            if (mPreviewRequestBuilder == null || mCaptureSession == null) return;
            applyZoom(mPreviewRequestBuilder, true);
            rebuildPreviewBuilder();
        };
        if (handler == null) { update.run(); return; }
        if (mZoomPending.compareAndSet(false, true)) handler.post(update); // the pending one reads the newest zoom
    }

    public void rebuildPreviewBuilder() {
        if(burst) return;
        if (mPreviewRequestBuilder == null || mCaptureSession == null) {
            return;
        }
        try {
//            mCaptureSession.stopRepeating();
            mCaptureSession.setRepeatingRequest(mPreviewInputRequest = mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (IllegalStateException | IllegalArgumentException | NullPointerException e) {
            Logger.warnShort(TAG, "Cannot rebuildPreviewBuilder()!", e);
        } catch (CameraAccessException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

     public void rebuildPreviewBuilderOneShot() {
        if(burst) return;
        if (mPreviewRequestBuilder == null || mCaptureSession == null) {
            return;
        }
        try {
            Log.d(TAG, "rebuildPreviewBuilderOneShot: " + mCaptureSession + " " + mPreviewRequestBuilder + " " + mCaptureCallback + " " + mBackgroundHandler);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (IllegalStateException | IllegalArgumentException | NullPointerException e) {
            Logger.warnShort(TAG, "Cannot rebuildPreviewBuilderOneShot()!", e);
        } catch (CameraAccessException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == mTextureView || null == mPreviewSize) {
            return;
        }
        int rotation = PhotonCamera.getGravity().getRotation();//activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        /*
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }*/
        //mTextureView.setTransform(matrix);
        mTextureView.setOrientation(mSensorOrientation+90);
        updatePreviewMirror();
    }

    private void updatePreviewMirror() {
        if (mTextureView == null || mCameraCharacteristics == null) {
            return;
        }
        Integer facing = mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
        boolean mirror = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        mTextureView.setMirror(mirror);
    }

    private ArrayList<Size> getAllTargets(){
        CameraCharacteristics characteristics =  this.mCameraCharacteristicsMap.get(physicalID);
        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        ArrayList<Size> allTargets = new ArrayList<>();

        Size[] targetSizes = map.getOutputSizes(mTargetFormat);
        if(targetSizes != null)
            allTargets.addAll(Arrays.asList(targetSizes));
        if(PhotonCamera.getSettings().QuadBayer) {
            useMaximumResolutionKey = false;
            int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            for (int capability : capabilities) {
                if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) {
                    Size arraySize = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        arraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION);
                    }
                    if(arraySize != null) {
                        useMaximumResolutionKey = true;
                        allTargets.add(arraySize);
                    }
                }
            }
            if(!useMaximumResolutionKey) {
                Size[] highResSizes = map.getHighResolutionOutputSizes(mTargetFormat);
                // Extend targetSizes with high resolution sizes
                if (highResSizes != null && highResSizes.length > 0) {
                    allTargets.addAll(Arrays.asList(highResSizes));
                }
                var keys = CameraReflectionApi.getCameraCharacteristicsKeys(characteristics, null, true);
                for (Object keyObj : keys) {
                    try {
                        if (keyObj instanceof CameraCharacteristics.Key<?>) {
                            CameraCharacteristics.Key<?> key = (CameraCharacteristics.Key<?>) keyObj;
                            if (key.getName().contains("StreamConfigurations")) {
                                Object res = characteristics.get(key);
                                int[] vals = (int[]) res;
                                for (int i = 0; i < vals.length; i += 4) {
                                    int format = vals[i];
                                    int width = vals[i + 1];
                                    int height = vals[i + 2];
                                    if (format == mTargetFormat) {
                                        allTargets.add(new Size(width, height));
                                        Log.d(TAG, "Added custom resolution(" + key.getName() + "):" + width + " " + height);
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return allTargets;
    }
    @SuppressLint("MissingPermission")
    public void restartCamera() {
        Log.d(TAG, "restartCamera() called from \"" + Thread.currentThread().getName() + "\" Thread");
        // Reuse the normal resume path: it prepares readers/outputs before
        // opening the device on a live handler. The old restart opened first,
        // with a null handler, racing onOpened against reader replacement.
        if (mIsRecordingVideo) VideoEnd();
        closeCamera();
        mLiveRawRejected = false;
        com.particlesdevs.photoncamera.processing.PreviewLook.clear();
        cameraEventsListener.onCameraRestarted();
        startBackgroundThread();
        resumeCamera();
    }
    private Size getAspect(CameraMode targetMode){
        Size aspectRatio;
        if (targetMode == CameraMode.VIDEO || targetMode == CameraMode.RAWVIDEO || PhotonCamera.getSettings().aspect169) {
            aspectRatio = new Size(9, 16);
        } else {
            aspectRatio = new Size(3, 4);
        }
        return aspectRatio;
    }

    private Display getSafeDisplay() {
        if (mTextureView != null) {
            Display d = mTextureView.getDisplay();
            if (d != null) return d;
        }
        //noinspection deprecation
        return activity.getWindowManager().getDefaultDisplay();
    }

    //Size for preview drawing
    private Size getTextureOutputSize(
            Display display,
            CameraMode targetMode
    ) {
        Size aspectRatio = getAspect(targetMode);
        Point displayPoint = new Point();
        display.getRealSize(displayPoint);
        int shortSide = Math.min(displayPoint.x, displayPoint.y);
        int longSide = shortSide * aspectRatio.getHeight() / aspectRatio.getWidth();

        return new Size(longSide, shortSide);
    }

    //Size for preview buffer
    private Size getPreviewOutputSize(
            Display display,
            CameraCharacteristics characteristics,
            CameraMode targetMode
    ) {
        if (characteristics == null) {
            if (mCameraCharacteristicsMap == null || mCameraCharacteristicsMap.isEmpty()) {
                fillInCameraCharacteristics();
            }
            if (mCameraCharacteristicsMap != null && mCameraCharacteristicsMap.containsKey(physicalID)) {
                characteristics = mCameraCharacteristicsMap.get(physicalID);
                mCameraCharacteristics = characteristics;
            }
        }

        Size aspectRatio = getAspect(targetMode);
        Point displayPoint = new Point();
        display.getRealSize(displayPoint);
        int shortSide = Math.min(displayPoint.x, displayPoint.y);
        int longSide = shortSide / aspectRatio.getWidth() * aspectRatio.getHeight();

        if (characteristics == null) {
            return new Size(800, 600);
        }

        // If image format is provided, use it to determine supported sizes; else use target class
        StreamConfigurationMap config = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (config == null) {
            return new Size(800, 600);
        }

        Size[] allSizes = config.getOutputSizes(SurfaceTexture.class);
        if (allSizes == null || allSizes.length == 0) {
            return new Size(800, 600);
        }

        Size retsize = null;
        for (Size size : allSizes) {
            int sizeShort = Math.min(size.getHeight(), size.getWidth());
            int sizeLong = Math.max(size.getHeight(), size.getWidth());
            if (sizeLong % aspectRatio.getHeight() == 0 &&
                    sizeShort == aspectRatio.getWidth() * sizeLong / aspectRatio.getHeight() &&
                    sizeShort * sizeLong <= ResolutionSolution.previewRes) {
                retsize = new Size(sizeShort, sizeLong);
                break;
            }
            /*if (sizeShort <= shortSide && sizeLong <= longSide) {
                retsize = new Size(sizeShort, sizeLong);
                break;
            }*/
        }
        if (retsize == null) {
            retsize = new Size(800, 600);
        }
        return retsize;
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        if(burst) return;
        if (mPreviewRequestBuilder == null || mCaptureSession == null) {
            Log.w(TAG, "lockFocus(): camera not ready (builder=" + mPreviewRequestBuilder + " session=" + mCaptureSession + ")");
            return;
        }
        startTimerLocked();
        // This is how to tell the camera to lock focus.
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START);
        // Tell #mCaptureCallback to wait for the lock.
        mState = STATE_WAITING_LOCK;
        try {
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException | RuntimeException e) {
            failPendingShutter(e);
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPreCaptureSequence() {
        if(burst) return;
        if (mPreviewRequestBuilder == null || mCaptureSession == null) {
            return;
        }
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException | RuntimeException e) {
            failPendingShutter(e);
        }
    }

    private String physicalID = "";
    private String logicalID = "";

    /**
     * Opens the camera specified by {@link Settings#mCameraID}.
     */
    public void openCamera(int width, int height) {
        // Both the SurfaceTexture listener and resumeCamera() can request an
        // open for the same surface lifecycle event; a second open while one is
        // in flight fails with CAMERA_IN_USE and kills the preview.
        if (mCameraDevice != null || !mCameraOpening.compareAndSet(false, true)) {
            Log.d(TAG, "openCamera(): an open is already in flight, skipping");
            return;
        }
        //Open camera in non ui thread
        processExecutor.execute(()->{
            CameraFragment.mSelectedMode = PhotonCamera.getSettings().selectedMode;
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                //requestCameraPermission();
                mCameraOpening.set(false);
                return;
            }
            if (!isCameraResumed) {
                // The app was backgrounded before this task ran.
                mCameraOpening.set(false);
                Log.d(TAG, "openCamera(): app already backgrounded, skipping");
                return;
            }
            processExecutor.execute(()-> {
                mMediaRecorder = new MediaRecorder();
            });
            // Attaching the stock AE observer takes seconds; start it with the
            // camera instead of on the first NICE preview frame.
            if (PreferenceKeys.isVivoNiceEnabled() && PreferenceKeys.useStockBracketPlanner()) VivoStockAe.warmUp(PhotonCamera.getAppContext());
            cameraEventsListener.onOpenCamera(this.mCameraManager);
            // The package spoof first: a module only the spoof lists (OPPO tele 5, a "system only device" otherwise) has no
            // characteristics without it, so no reader was set up when the previous module ran without the spoof.
            com.particlesdevs.photoncamera.capture.spoof.CameraPackageSpoof.apply(activity, mCameraManager);
            setUpCameraOutputs(width, height);
            configureTransform(width, height);
            if (!isCameraResumed) {
                // The app was backgrounded while outputs were being set up.
                mCameraOpening.set(false);
                Log.d(TAG, "openCamera(): app backgrounded during setup, skipping");
                return;
            }
            try {
                if (!mCameraOpenCloseLock.tryAcquire(1000, TimeUnit.MILLISECONDS)) {
                    mCameraOpening.set(false);
                    throw new RuntimeException("Time out waiting to lock camera opening.");
                }
                physicalID = PhotonCamera.getSettings().mCameraID;
                logicalID = PhotonCamera.getSettings().mCameraID;
                // Split x-y, x - logical, y - physical
                if(PhotonCamera.getSettings().mCameraID.contains("-")){
                    String[] ids = PhotonCamera.getSettings().mCameraID.split("-");
                    logicalID = ids[0];
                    physicalID = ids[1];
                    //isDualSession = true;
                }

                // The retry budget belongs to one camera: switching modules during a provider restart used it up for the next one.
                if (!logicalID.equals(mOpenRetryId)) { mOpenRetryId = logicalID; mOpenRetries = 0; }
                this.mCameraManager.openCamera(logicalID, mStateCallback, mBackgroundHandler);
                mOpenRetries = 0;
            } catch (CameraAccessException e) {
                mCameraOpening.set(false);
                // No device callback follows a refused open: the lock taken above is released here (it used to stay held, so
                // the next open waited 1 s and failed with "Time out waiting to lock camera opening").
                mCameraOpenCloseLock.release();
                Log.e(TAG, Log.getStackTraceString(e));
                // CAMERA_ERROR / CAMERA_DISCONNECTED while the provider restarts: the same retry as an unknown device.
                if (e.getReason() == CameraAccessException.CAMERA_ERROR || e.getReason() == CameraAccessException.CAMERA_DISCONNECTED)
                    retryOpen(width, height, e);
            } catch (IllegalArgumentException e) {
                // "Unknown device" while the vendor camera provider restarts after a HAL
                // crash: the device list comes back a few seconds later. Retry instead of
                // taking the app down with the HAL.
                mCameraOpening.set(false);
                mCameraOpenCloseLock.release();
                retryOpen(width, height, e);
            } catch (InterruptedException e) {
                mCameraOpening.set(false);
                throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
            }
    });
    }
    private String mOpenRetryId = "";

    /** Up to 8 retries 0.5, 1, 2, 4, 4 ... s apart (~27 s): the X100 Ultra provider needed ~4.5 s, the old 5 x 1 s ran out. */
    private void retryOpen(int width, int height, Exception e) {
        if (mOpenRetries < 8 && mBackgroundHandler != null && isCameraResumed) {
            long delay = Math.min(4000L, 500L << mOpenRetries++);
            Log.w(TAG, "openCamera(" + logicalID + ") failed (" + e.getMessage() + "), retry " + mOpenRetries + " in " + delay + " ms");
            mBackgroundHandler.postDelayed(() -> openCamera(width, height), delay);
        } else {
            Log.e(TAG, "openCamera(" + logicalID + ") failed after retries: " + Log.getStackTraceString(e));
        }
    }

    public void UpdateCameraCharacteristics(String cameraId) {
        PhotonCamera.getSpecificSensor().selectSpecifics(Integer.parseInt(cameraId));
        CameraCharacteristics characteristics = this.mCameraCharacteristicsMap.get(cameraId);
        if (characteristics == null) {
            // A camera ID the list filled at start did not have: a hidden module that only the package spoof lists (OPPO tele
            // 5 when the app started on a lens without the spoof). Without its characteristics no reader was set up and the
            // session start failed on a null ImageReader, leaving the device open and unconfigured (frozen viewfinder).
            try {
                characteristics = mCameraManager.getCameraCharacteristics(cameraId);
                mCameraCharacteristicsMap.put(cameraId, characteristics);
                Log.i(TAG, "characteristics of camera " + cameraId + " fetched on demand");
            } catch (CameraAccessException | IllegalArgumentException e) {
                Log.w(TAG, "no characteristics for camera " + cameraId + ": " + e.getMessage());
            }
        }
        mCameraCharacteristics = characteristics;
        //Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

        StreamConfigurationMap map = null;
        if (mCameraCharacteristics != null) {
            map = mCameraCharacteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        }
        if (map == null) {
            return;
        }
        if (isRawFormat(mTargetFormat)) {
            int resolved = resolveRawFormat(map);
            Log.i(TAG, "RAW stream format " + mTargetFormat + " -> " + resolved + " (setting " + PreferenceKeys.getRawStreamFormat()
                    + ", camera " + physicalID + ")");
            mTargetFormat = resolved;
        }
        ArrayList<Size> allTargets = getAllTargets();

        Size preview = getCameraOutputSize(map.getOutputSizes(mPreviewTargetFormat));

        int maxjpg = 3;
        // The raw viewfinder consumes from this reader continuously, so it
        // needs its own headroom even when ZSL is off.
        if (PreferenceKeys.isLiveViewfinderRawEnabled()) maxjpg = 6;
        if (mTargetFormat == mPreviewTargetFormat && isDualSession)
            maxjpg = PhotonCamera.getSettings().frameCount + 3;
        if (isZslMode())
            // The ring buffer can hold up to zslRingCapacity() frames, and the
            // reader has to have room for all of them plus the in-flight ones,
            // otherwise acquireNextImage starves once the ring is full.
            maxjpg = Math.min(zslRingCapacity() + 3, 103);
        Size target = getCameraOutputSize(allTargets.toArray(new Size[0]), preview);
        Size aspect = getAspect(PhotonCamera.getSettings().selectedMode);
        if(preview.getWidth() > preview.getHeight())
            preview = new Size(preview.getWidth(),preview.getWidth()*aspect.getWidth()/aspect.getHeight());
        else {
            preview = new Size(preview.getHeight()*aspect.getWidth()/aspect.getHeight(),preview.getHeight());
        }
        if(mImageReaderPreview != null)
            mImageReaderPreview.close();

        mImageReaderPreview = ImageReader.newInstance(preview.getWidth(), preview.getHeight(), mPreviewTargetFormat, maxjpg);
        mImageReaderPreview.setOnImageAvailableListener(mOnYuvImageAvailableListener, mBackgroundHandler);
            mBufferSize = getPreviewOutputSize(getSafeDisplay(),characteristics,PhotonCamera.getSettings().selectedMode);

        clearZslPreviewFrames();
        if(mImageReaderRaw != null)
            mImageReaderRaw.close();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (PhotonCamera.getSettings().QuadBayer
                || !(Build.BRAND.equalsIgnoreCase("oppo")
                || Build.BRAND.equalsIgnoreCase("vivo")
                || Build.BRAND.equalsIgnoreCase("oneplus")
                || Build.BRAND.equalsIgnoreCase("realme")
                || Build.BRAND.equalsIgnoreCase("iqoo")
                || Build.BRAND.equalsIgnoreCase("nothing")
                || Build.BRAND.equalsIgnoreCase("google")
        ))
        ) {
            mImageReaderRaw = ImageReader.newInstance(target.getWidth(), target.getHeight(), mTargetFormat, maxjpg, 0x00100000);
        } else {
            mImageReaderRaw = ImageReader.newInstance(target.getWidth(), target.getHeight(), mTargetFormat, maxjpg);
        }
        mImageReaderRaw.setOnImageAvailableListener(mOnRawImageAvailableListener, mBackgroundHandler);
        mExposureLimits = ExposureLimits.of(String.valueOf(physicalID), mImageReaderRaw.getWidth(), mImageReaderRaw.getHeight(), useMaximumResolutionKey);
        // Find out if we need to swap dimension to get the preview size relative to sensor
        // coordinate.
        int displayRotation = PhotonCamera.getGravity().getRotation();
        mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) {
            ranges = new Range[1];
            ranges[0] = new Range<>(14, 30);
        }
        int minLower = Integer.MAX_VALUE;
        for (Range<Integer> range : ranges) {
            if (range.getLower() < minLower) {
                minLower = range.getLower();
            }
        }
        if (minLower == Integer.MAX_VALUE) minLower = 14;
        FpsRangeAuto = new Range<>(minLower, 30);

        /*boolean swappedDimensions = false;
        switch (displayRotation) {
            case 0:
            case 180:
                if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                    swappedDimensions = true;
                }
                break;
            case 90:
            case 270:
                if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                    swappedDimensions = true;
                }
                break;
            default:
                Log.e(TAG, "Display rotation is invalid: " + displayRotation);
        }*/

        mCameraAfModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);

        /*Point displaySize = new Point();
        activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
        int rotatedPreviewWidth = mPreviewWidth;
        int rotatedPreviewHeight = mPreviewHeight;

        mPreviewWidth = Math.max(rotatedPreviewHeight, rotatedPreviewWidth);
        mPreviewHeight = Math.min(rotatedPreviewHeight, rotatedPreviewWidth);*/




        /*mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth*2,
                maxPreviewHeight*2, target);*/
        //mPreviewSize = new Size(mPreviewWidth, mPreviewHeight);


        // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
        //        // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
        //        // garbage capture data.



        // We fit the aspect ratio of TextureView to the size of preview we picked.
        /*
        int orientation = activity.getResources().getConfiguration().orientation;

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mTextureView.setAspectRatio(
                    mPreviewSize.getWidth(), mPreviewSize.getHeight());
            mTextureView.cameraSize = new Point(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        } else {
            mTextureView.setAspectRatio(
                    mPreviewSize.getHeight(), mPreviewSize.getWidth());
            mTextureView.cameraSize = new Point(mPreviewSize.getHeight(), mPreviewSize.getWidth());
        }*/


        // Check if the flash is supported.
        Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        mFlashSupported = available != null && available;
        Camera2ApiAutoFix.Init();
        if (mMediaRecorder == null) {
            mMediaRecorder = new MediaRecorder();
//            setUpMediaRecorder();
        }
        final CameraCharacteristics updated = characteristics;
        activity.runOnUiThread(() -> {
            //Preview drawing size changing
            mPreviewSize = getTextureOutputSize(getSafeDisplay(), PhotonCamera.getSettings().selectedMode);
            mTextureView.setAspectRatio(
                    mPreviewSize.getHeight(), mPreviewSize.getWidth());
            updatePreviewMirror();
            // The fragment may be gone by now (activity recreated, e.g. on a language change).
            final CameraEventsListener listener = cameraEventsListener;
            if (listener != null) listener.onCharacteristicsUpdated(updated);
            if (PhotonCamera.getSettings().DebugData)
                showToast("preview:" + new Point(mPreviewWidth, mPreviewHeight));
        });
        //activity.runOnUiThread(() -> cameraEventsListener.onCharacteristicsUpdated(characteristics));
    }
    Surface surface;
    public void createCameraPreviewSession(boolean isBurstSession) {
        final int generation = mSessionGeneration.incrementAndGet();
        final CameraDevice sessionDevice = mCameraDevice;
        if (!isCameraResumed || sessionDevice == null) return;
        try {
            SensorConfigInjector.applyToSensor(physicalID, this);
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            if (texture == null) {
                Log.w(TAG, "createCameraPreviewSession(): SurfaceTexture not ready, waiting for surface");
                mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
                return;
            }
            // We configure the size of default buffer to be the size of camera preview we want.
            Log.d(TAG, "createCameraPreviewSession() mTextureView:" + mTextureView);
            Log.d(TAG, "createCameraPreviewSession() Texture:" + texture);
            Log.d(TAG, "bufferSize:" + mBufferSize);
            Log.d(TAG, "previewSize:" + mPreviewSize);
            Log.d(TAG, "ID:" + PhotonCamera.getSettings().mCameraID + " deviceID:" + mCameraDevice.getId() + " logicalID:" + logicalID + " physicalID:" + physicalID);
            // Resolve the CFA pattern here, from the physical camera actually
            // being previewed, rather than per frame from mCameraCharacteristics
            // - that field is swapped between logical and physical cameras while
            // the session runs, and the pattern flipped between BGGR and RGGB
            // from frame to frame.
            try {
                CameraCharacteristics live = mCameraCharacteristicsMap.get(physicalID);
                if (live == null) live = mCameraCharacteristics;
                Integer arr = live == null ? null
                        : live.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                mLiveCfaPattern = arr == null ? -1 : arr;
                Log.d(TAG, "live viewfinder CFA: " + mLiveCfaPattern + " (physical " + physicalID + ")");
            } catch (Exception e) {
                mLiveCfaPattern = -1;
            }

            //Camera output
            texture.setDefaultBufferSize(mBufferSize.getHeight(), mBufferSize.getWidth());

            // This is the output Surface we need to start preview.
            if(surface == null)
                surface = new Surface(texture);
            // We set up a CaptureRequest.Builder with the output Surface.
            // P27: the routing of a shot in flight stays (its post-shutter RAWs would be closed as preview frames).
            if (!shotInFlight()) mLiveRawRouter.clear();
            mLiveMetadata.clear();
            boolean photoMode = PhotonCamera.getSettings().selectedMode == CameraMode.PHOTO
                    || PhotonCamera.getSettings().selectedMode == CameraMode.NIGHT
                    || PhotonCamera.getSettings().selectedMode == CameraMode.MOTION;
            // Vendor detector tags exist only on a vivo HAL; elsewhere SCAM HDR uses the plain preview.
            final boolean nicePreview = PreferenceKeys.isVivoNiceEnabled() && photoMode
                    && VivoNicePreview.supported() && !isBurstSession && !mIsRecordingVideo
                    && !sPlainPreviewCameras.contains(physicalID);
            mNicePreviewActive = nicePreview;
            mPayloadFrames = 0;
            mPayloadBad = false;
            Log.i("NICE_CAPTURE", "session mode=" + PhotonCamera.getSettings().selectedMode
                    + " route=" + (PreferenceKeys.isLmcHybridEnabled() ? "LMC_HYBRID" : PreferenceKeys.isVivoNiceEnabled() ? "NICE_RAW" : "SCAMERA"));
            // RAW_SENSOR, RAW10 and RAW12: packed rows are unpacked for the viewfinder (RawUnpack).
            mLiveRawSession = photoMode && !isBurstSession && !mIsRecordingVideo && !mLiveRawRejected
                    && isRawFormat(mTargetFormat) && PreferenceKeys.isLiveViewfinderRawEnabled();
            LiveRawFrame.setEnabled(false); // invalidate the previous session even when RAW remains enabled
            LiveRawFrame.setEnabled(mLiveRawSession);
            // P13: a module whose stream was measured as a mosaic before starts on the RAW viewfinder at once
            mMosaicMeasure = PreferenceKeys.niceDevSwitch("mosaic_preview", true);
            com.particlesdevs.photoncamera.processing.MosaicStream.startSession(mMosaicMeasure ? mosaicStreamKey() : "off");
            mMosaicPreview = mMosaicMeasure && !mLiveRawSession && photoMode && !isBurstSession && !mIsRecordingVideo && isZslMode()
                    && isRawFormat(mTargetFormat) && com.particlesdevs.photoncamera.processing.MosaicStream.block() > 1;
            if (mMosaicPreview) LiveRawFrame.setEnabled(true);
            LiveRawFrame.setMosaicPreview(mMosaicPreview);
            setCaptureRequestBuilder();

            // Here, we create a CameraCaptureSession for camera preview.
            List<Surface> surfaces = configureSurfaces(isBurstSession);
            Log.d(TAG, "createCameraPreviewSession() surfaces:" + Arrays.toString(surfaces.toArray()));
            ArrayList<OutputConfiguration> outputConfigurations = new ArrayList<>();
            for (Surface surfacei : surfaces) {
                var config = new OutputConfiguration(surfacei);
                if(!Objects.equals(physicalID, logicalID) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P){
                    config.setPhysicalCameraId(physicalID);
                }
                outputConfigurations.add(config);
            }

            CameraCaptureSession.StateCallback stateCallback =
                    new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    synchronized (mPreviewStateLock) {
                        Log.d(TAG, "CameraCaptureSession onConfigured():" + cameraCaptureSession);
                        // The camera is already closed
                        if (!isCameraResumed || sessionDevice != mCameraDevice ||
                                generation != mSessionGeneration.get()) {
                            cameraCaptureSession.close();
                            return;
                        }
                        // When the session is ready, we start displaying the preview.
                        mCaptureSession = cameraCaptureSession;
                        mConfiguredSessionGeneration = generation;
                        mPreviewCaptureResult = null;
                        mPreviewCaptureRequest = null;
                        try {
                            // Auto focus should be continuous for camera preview.
                            //mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            // Flash is automatically enabled when necessary.
                            resetPreviewAEMode();
                            applyAeMeteringRegions(mPreviewRequestBuilder);
                            Camera2ApiAutoFix.applyPrev(mPreviewRequestBuilder);
                            VendorTagUtils.builderSessionApply(mPreviewRequestBuilder, false, useMaximumResolutionKey, physicalID);
                            if (nicePreview) {
                                try {
                                    // Detector controls only: VCF2 JPEG stream usage and
                                    // SnapshotJpegStreamMap do not describe this RAW session.
                                    VivoNicePreview.applyRepeating(mPreviewRequestBuilder);
                                    Log.i("NICE_CAPTURE", "preview NICE AUTO; capture route=Camera2_RAW"
                                            + " stockAeSource=preview_metadata stockPlanApplied=false");
                                } catch (IllegalArgumentException unsupported) {
                                    Log.w("NICE_CAPTURE", "NICE preview controls unavailable", unsupported);
                                }
                            }
                            //if(isZslMode()){
                                try {
                                    mPreviewRequestBuilder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
                                } catch (Exception e) {
                                    Log.d(TAG, "Failed to set LENS_SHADING_MAP_MODE_ON for ZSL mode:" + Log.getStackTraceString(e));
                                }
                            //}

                            // Apply dynamic OIS for preview stream
                            applyOisMode(mPreviewRequestBuilder, false);

                            // Finally, we start displaying the camera preview.
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    nicePreview ? VivoNicePreview.STOCK_FPS : getSelectedFpsRange());
                            mPreviewInputRequest = mPreviewRequestBuilder.build();
                            if (isBurstSession && isDualSession) {
                                switch (CameraFragment.mSelectedMode) {
                                    case NIGHT:
                                    case PHOTO:
                                    case MOTION:
                                        mCaptureSession.captureBurst(captures, CaptureCallback, mBackgroundHandler);
                                        break;
                                    case UNLIMITED:
                                    case RAWVIDEO:
                                        mCaptureSession.setRepeatingBurst(captures, CaptureCallback, mBackgroundHandler);
                                        break;
                                }
                            } else {
                                //if(mSelectedMode != CameraMode.VIDEO)
                                mCaptureSession.setRepeatingRequest(mPreviewInputRequest,
                                        mCaptureCallback, mBackgroundHandler);
                                unlockFocus();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, Log.getStackTraceString(e));
                            if (retryWithoutLiveRaw(cameraCaptureSession)) return;
                        }
                        if (mIsRecordingVideo)
                            activity.runOnUiThread(() -> {
                                // Start recording
                                mMediaRecorder.start();
                            });
                    }
                }

                @Override
                public void onConfigureFailed(
                        @NonNull CameraCaptureSession cameraCaptureSession) {
                    synchronized (mPreviewStateLock) {
                        if (!isCameraResumed || sessionDevice != mCameraDevice ||
                                generation != mSessionGeneration.get()) {
                            cameraCaptureSession.close();
                            return;
                        }
                        if (retryWithoutLiveRaw(cameraCaptureSession)) return;
                        showToast(activity.getString(R.string.session_on_configure_failed));
                        Log.d(TAG, "CameraCaptureSession onConfigureFailed()");
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SessionConfiguration configuration = new SessionConfiguration(
                        sessionType,
                        outputConfigurations,
                        processExecutor,
                        stateCallback
                );
                if (nicePreview) {
                    try {
                        CaptureRequest.Builder niceSession = sessionDevice.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW);
                        VendorTagUtils.builderSessionApply(niceSession, false, useMaximumResolutionKey, physicalID);
                        VivoNicePreview.applySession(niceSession);
                        configuration.setSessionParameters(niceSession.build());
                    } catch (IllegalArgumentException unsupported) {
                        Log.w("NICE_CAPTURE", "NICE session control unavailable", unsupported);
                    }
                }
                mCameraDevice.createCaptureSession(configuration);
            } else {
                mCameraDevice.createCaptureSession(surfaces, stateCallback, mBackgroundHandler);
            }
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    private boolean retryWithoutLiveRaw(CameraCaptureSession session) {
        if (!mLiveRawSession || mLiveRawRejected || mCameraDevice == null || isZslMode()) return false;
        mLiveRawRejected = true;
        mLiveRawSession = false;
        LiveRawFrame.setEnabled(false);
        mLiveRawRouter.clear();
        mLiveMetadata.clear();
        session.close();
        showToast(Lang.t("RAW-превью недоступно с текущими потоками. Переключаемся на обычный видоискатель.", "RAW preview is not available with the current streams. Switching to the normal viewfinder."));
        createCameraPreviewSession(false);
        return true;
    }

    @NotNull
    private List<Surface> configureSurfaces(boolean isBurstSession) {
        List<Surface> surfaces = Arrays.asList(surface, mImageReaderPreview.getSurface());
        if (isDualSession) {
            if (isBurstSession) {
                surfaces = Arrays.asList(mImageReaderPreview.getSurface(), mImageReaderRaw.getSurface());
            }
            if (mTargetFormat == mPreviewTargetFormat) {
                surfaces = Arrays.asList(surface, mImageReaderPreview.getSurface());
            }
        } else {
           if(Build.BRAND.equalsIgnoreCase("samsung")){
                surfaces = Arrays.asList(surface, mImageReaderRaw.getSurface());
            } else {
                surfaces = Arrays.asList(surface, mImageReaderPreview.getSurface(), mImageReaderRaw.getSurface());
            }
           if(PhotonCamera.getSettings().previewFormat == 0) {
                surfaces = Arrays.asList(surface, mImageReaderRaw.getSurface());
           }
        }
        if (mLiveRawSession && !surfaces.contains(mImageReaderRaw.getSurface())) {
            surfaces = new ArrayList<>(surfaces);
            surfaces.add(mImageReaderRaw.getSurface());
        }
        if (mIsRecordingVideo) {
            setUpMediaRecorder();
            surfaces = Arrays.asList(surface, mMediaRecorder.getSurface());
            mPreviewRequestBuilder.addTarget(mMediaRecorder.getSurface());
        }
        return surfaces;
    }

    private void setCaptureRequestBuilder() throws CameraAccessException {
        mPreviewRequestBuilder = null;
        if (mIsRecordingVideo) {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        } else {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        }

        mPreviewRequestBuilder.addTarget(surface);
        synchronized (mZslBufferLock) {
            mHexZslResults.clear();
            while (!mZslRingBuffer.isEmpty()) {
                Image img = mZslRingBuffer.pollFirst();
                if (img != null) img.close();
            }
        }
        // Drain any frames still queued in the RAW ImageReader to prevent them leaking
        // into the next non-ZSL capture's IMAGE_BUFFER
        if (mImageReaderRaw != null) {
            Image stale;
            try {
                while ((stale = mImageReaderRaw.acquireNextImage()) != null) stale.close();
            } catch (Exception ignored) {}
        }
        if (isZslMode() || mLiveRawSession) {
            // Still-photo modes keep the RAW ring; outside them the RAW stream
            // was never part of the repeating request and the raw viewfinder
            // had nothing to develop. It needs the stream in every mode.
            mPreviewRequestBuilder.addTarget(mImageReaderRaw.getSurface());
        }
        mInitialMeteringAF = mPreviewRequestBuilder.get(CONTROL_AF_REGIONS);
        mPreviewMeteringAF = mInitialMeteringAF;
        mPreviewAFMode = PreferenceKeys.getAfMode();
        if (mIsRecordingVideo) {
            mPreviewRequestBuilder.set(CONTROL_AF_MODE, CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            mPreviewAFMode = CONTROL_AF_MODE_CONTINUOUS_VIDEO;
            if (PreferenceKeys.isEisPhotoOn()) {
                mPreviewRequestBuilder.set(CONTROL_VIDEO_STABILIZATION_MODE, CONTROL_VIDEO_STABILIZATION_MODE_ON);
            }
        }
        mInitialMeteringAE = mPreviewRequestBuilder.get(CONTROL_AE_REGIONS);
        mPreviewMeteringAE = mInitialMeteringAE;
        mPreviewAEMode = mPreviewRequestBuilder.get(CONTROL_AE_MODE);
        applyZoom(mPreviewRequestBuilder, false);
    }

    private void showToast(String msg) {
        if (activity != null) {
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * Initiate a still image capture.
     */
    private boolean niceQueueMemoryAvailable() {
        android.app.ActivityManager manager = (android.app.ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return !info.lowMemory && info.availMem > 1536L * 1024 * 1024;
    }

    public boolean takePicture() {
        synchronized (mPreviewStateLock) {
            Log.i(TAG, "SHUTTER camera=" + physicalID + " state=" + mState
                    + " processing=" + isProcessing + " zsl=" + mZslCapturing
                    + " shot=" + mShotInProgress + " burst=" + burst);
            boolean processingBlocks = isProcessing && !(PreferenceKeys.isVivoNiceEnabled()
                    && niceQueueMemoryAvailable());
            if (niceShutterQueues() && !processingBlocks
                    && (mZslCapturing || mShotInProgress || burst || mNiceFireWaiting)) {
                // Stock-like: a press during the tail capture is kept, not dropped.
                if (mNiceQueuedShots < 4) {
                    mNiceQueuedShots++;
                    Log.i("NICE_CAPTURE", "shutter queued behind current capture pending=" + mNiceQueuedShots);
                    return true;
                }
            }
            if (niceShutterQueues() && !mNiceFireWaiting && mNiceTailTimestamp != 0 && !previewFreshAfterTail()) {
                // Pressed just after a tail: the newest preview result still predates
                // it, so wait for fresh frames exactly like a queued press.
                Log.i("NICE_CAPTURE", "shutter waits for fresh preview after previous tail");
                mNiceFireWaiting = true;
                fireQueuedNiceShot(android.os.SystemClock.elapsedRealtime() + 2000);
                return true;
            }
            if (mZslCapturing || processingBlocks || mShotInProgress || burst) {
                showToast(Lang.t("Предыдущий снимок ещё обрабатывается. Дождитесь завершения.", "The previous shot is still processing. Wait until it finishes."));
                return false;
            }
            if (!isCurrentPreviewSession(mCaptureSession) ||
                    mCameraDevice == null || mPreviewCaptureResult == null) {
                Log.w(TAG, "takePicture(): waiting for current-session preview after resume");
                cameraEventsListener.onProcessingError(Lang.t("Камера ещё готовится. Повторите снимок.", "The camera is still getting ready. Take the shot again."));
                return false;
            }
            Long previewTimestamp = mPreviewCaptureResult.get(CaptureResult.SENSOR_TIMESTAMP);
            niceZslShutterTimestamp = previewTimestamp == null ? 0 : previewTimestamp;
            // The vendor shutter AE snapshot (diagnostic only, never applied) walked
            // every vendor key of the result: 37 ms on the UI thread per press, and
            // this HAL does not publish it for Camera2 clients anyway.
            if(PreferenceKeys.isVivoNiceEnabled()) Log.i("NICE_CAPTURE","shutter camera="+physicalID
                    +" mode="+PhotonCamera.getSettings().selectedMode+" zslMode="+isZslMode()
                    +" sensorCutoffNs="+niceZslShutterTimestamp+" cutoffSource=latest_preview_result"
                    +" route="+PreferenceKeys.mergeRoute());
            mShotInProgress = true;
            final long shotGeneration = ++mShutterGeneration;
            if (isZslMode()) {
                mNiceRingFrozen = PreferenceKeys.isVivoNiceEnabled();
                mNicePlanDeadline = android.os.SystemClock.elapsedRealtime() + 3000;
                captureStillPicture();
                return true;
            }
            startTimerLocked();
            // Some auxiliary cameras never signal AF/AE convergence. Bound
            // every precapture state, including missing preview callbacks.
            final CameraCaptureSession shotSession = mCaptureSession;
            final int generation = mSessionGeneration.get();
            if (mBackgroundHandler != null) mBackgroundHandler.postDelayed(() -> {
                synchronized (mPreviewStateLock) {
                    if (shotGeneration != mShutterGeneration || generation != mSessionGeneration.get()
                            || !isCurrentPreviewSession(shotSession)
                            || !mShotInProgress || !isWaitingForCapture()) return;
                    Log.w(TAG, "SHUTTER precapture timeout camera=" + physicalID + " state=" + mState);
                    mState = STATE_PICTURE_TAKEN;
                    captureStillPicture();
                }
            }, 1000);
            if (mCameraAfModes != null && mCameraAfModes.length > 1) lockFocus();
            else {
                try {
                    mState = STATE_WAITING_NON_PRECAPTURE;
                    mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback,
                            mBackgroundHandler);
                } catch (CameraAccessException | RuntimeException e) {
                    failPendingShutter(e);
                    return false;
                }
            }
            return mShotInProgress;
        }
    }

    /** NICE ZSL shots accept presses during capture; the UI keeps the shutter enabled. */
    public boolean niceShutterQueues() {
        // Only the NICE route (no remosaic backend) frees the shutter through
        // finishNiceShot(); any other route would leave queued presses stuck.
        return PreferenceKeys.isVivoNiceEnabled() && isZslMode();
    }

    /**
     * The vendor AE resumes from the last applied sensor settings: after the ES
     * frame (e.g. 10 ms, ISO 77) the preview ramped back over ~8 frames and the next
     * shot found no ZSL RAWs at the planned N. One preview frame at the pre-shutter
     * exposure, queued behind the tail, hands AE back at the right values.
     */
    private void queueNiceAeRestore() {
        CaptureResult base = mNativeZslBase != null ? mNativeZslBase : mPreviewCaptureResult;
        if (base == null || mPreviewRequestBuilder == null) return;
        Long exposure = base.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = base.get(CaptureResult.SENSOR_SENSITIVITY);
        Long duration = base.get(CaptureResult.SENSOR_FRAME_DURATION);
        if (exposure == null || iso == null || exposure <= 0 || iso <= 0) return;
        CaptureRequest.Builder b = mPreviewRequestBuilder;
        Integer aeMode = b.get(CaptureRequest.CONTROL_AE_MODE);
        Long oldExposure = b.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
        Integer oldIso = b.get(CaptureRequest.SENSOR_SENSITIVITY);
        Long oldDuration = b.get(CaptureRequest.SENSOR_FRAME_DURATION);
        try {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            if (duration != null && duration > 0) b.set(CaptureRequest.SENSOR_FRAME_DURATION, duration);
            CaptureRequest restore = b.build();
            mCaptureSession.capture(restore, mCaptureCallback, mBackgroundHandler);
            Log.i("NICE_CAPTURE", "AE restore frame queued exposureNs=" + exposure + " ISO=" + iso);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w("NICE_CAPTURE", "AE restore frame not queued: " + e);
        } finally {
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, oldExposure);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, oldIso);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, oldDuration);
        }
    }

    /**
     * P27: the camera closed, failed or stalled while a shot was in flight: the frames that arrived are processed (the
     * outstanding requests count as lost) instead of vanishing with the shutter left locked (shot flags, static burst, ring
     * routing, ~25 MB of native memory per deferred ZSL frame).
     */
    private void rescueInFlightShot(String reason) {
        final Runnable rescue = mInFlightRescue;
        mInFlightRescue = null;
        if (rescue == null) {
            mNativeRawPslCapture = false; mZslCapturing = false; mHybridZslCapture = false; burst = false;
            return;
        }
        Log.w("NICE_CAPTURE", "shot in flight completed with the frames that arrived: " + reason);
        try {
            rescue.run();
        } catch (RuntimeException e) {
            Log.e(TAG, "shot rescue failed: " + Log.getStackTraceString(e));
        }
    }

    /** P27: a shot is between its press and the end of its post-shutter frames. */
    private boolean shotInFlight() {
        return mInFlightRescue != null || mZslCapturing || burst;
    }

    private void finishNiceShot() {
        if (mPendingPayloadRestart && mBackgroundHandler != null) {
            mPendingPayloadRestart = false;
            final int generation = mSessionGeneration.get();
            mBackgroundHandler.post(() -> {
                if (!isCameraResumed || generation != mSessionGeneration.get() || mCaptureSession == null) return;
                mCaptureSession.close();
                createCameraPreviewSession(false);
            });
        }
        final boolean fire;
        synchronized (mPreviewStateLock) {
            mShotInProgress = false;
            mNiceRingFrozen = false;
            mState = STATE_PREVIEW;
            fire = mNiceQueuedShots > 0;
            if (fire) mNiceQueuedShots--;
        }
        Log.i("NICE_CAPTURE", "shutter free (preview kept running) queuedNext=" + fire);
        if (fire) fireQueuedNiceShot(android.os.SystemClock.elapsedRealtime() + 2000);
    }

    /**
     * After the manual tail the HAL resumes preview results late, so the newest
     * preview frame at this moment can still predate the bracket (no fresh AE plan,
     * no ZSL RAWs at the planned N). Fire once five preview frames follow the tail:
     * four ring RAWs plus the frame the shutter timestamp is taken from.
     */
    private boolean previewFreshAfterTail() {
        CaptureResult latest = mPreviewCaptureResult;
        Long ts = latest == null ? null : latest.get(CaptureResult.SENSOR_TIMESTAMP);
        if (ts == null || ts <= mNiceTailTimestamp + 5 * 33_000_000L) return false;
        // Preview RAWs arrive later than their results: wait until the ring holds
        // four RAWs taken after the previous tail, so N come from ZSL instead of
        // being re-shot after the press (0.9 s and shutter lag).
        // Count the newest run of ring RAWs after the tail that share one exposure
        // and ISO: the first frames after a tail can still carry AE recovery.
        int run = 0;
        synchronized (mZslBufferLock) {
            Long exposure = null; Integer iso = null;
            java.util.Iterator<Image> it = mZslRingBuffer.descendingIterator();
            while (it.hasNext()) {
                Image image = it.next();
                if (image.getTimestamp() <= mNiceTailTimestamp) break;
                TotalCaptureResult result = mHexZslResults.get(image.getTimestamp());
                if (result == null) { if (run == 0) continue; else break; }
                Long e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer s = result.get(CaptureResult.SENSOR_SENSITIVITY);
                if (e == null || s == null) break;
                if (exposure == null) { exposure = e; iso = s; }
                else if (!exposure.equals(e) || !iso.equals(s)) break;
                run++;
            }
        }
        return run >= 4;
    }

    private void fireQueuedNiceShot(long deadline) {
        if (mBackgroundHandler == null) {mNiceFireWaiting = false; return;}
        CaptureResult latest = mPreviewCaptureResult;
        Long ts = latest == null ? null : latest.get(CaptureResult.SENSOR_TIMESTAMP);
        long tail = mNiceTailTimestamp;
        boolean fresh = previewFreshAfterTail();
        if (!fresh && android.os.SystemClock.elapsedRealtime() < deadline) {
            mNiceFireWaiting = true;
            mBackgroundHandler.postDelayed(() -> fireQueuedNiceShot(deadline), 30);
            return;
        }
        Log.i("NICE_CAPTURE", "queued shutter fires fresh=" + fresh + " previewAfterTailMs="
                + (ts == null ? -1 : (ts - tail) / 1_000_000));
        mNiceFireWaiting = false;
        mNiceTailTimestamp = 0;
        if (!takePicture()) Log.w("NICE_CAPTURE", "queued shutter could not start");
    }

    private boolean isWaitingForCapture() {
        return mState == STATE_WAITING_LOCK || mState == STATE_WAITING_PRECAPTURE
                || mState == STATE_WAITING_NON_PRECAPTURE;
    }

    private void failPendingShutter(Exception error) {
        mNiceRingFrozen = false;
        mShotInProgress = false;
        mState = STATE_PREVIEW;
        Log.e(TAG, "SHUTTER failed camera=" + physicalID, error);
        cameraEventsListener.onProcessingError(Lang.t("Не удалось запустить съёмку: ", "Could not start the capture: ") + error.getMessage());
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    public void unlockFocus() {
        // The burst is done with the reader by here, so the raw viewfinder may
        // take frames again.
        mShotInProgress = false;
        mNiceQueuedShots = 0;
        if (mPreviewRequestBuilder == null || mCaptureSession == null) {
            Log.d(TAG, "unlockFocus(): camera not ready (builder=" + mPreviewRequestBuilder + ", session=" + mCaptureSession + ")");
            return;
        }
        try {
            // Reset the auto-focus trigger
            //mCaptureSession.stopRepeating();
            //mCaptureSession.abortCaptures();
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            rebuildPreviewBuilderOneShot();
            //mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
            //        CameraMetadata.CONTROL_AF_TRIGGER_START);
            //rebuildPreviewBuilderOneShot();
            reset3Aparams();
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            rebuildPreviewBuilderOneShot();
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            rebuildPreviewBuilderOneShot();
            paramController.setupPreview();
            /*mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);*/
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW;
            rebuildPreviewBuilder();
            //mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
            //        mBackgroundHandler);
        }catch(Exception e){
            Log.d(TAG, "unlockFocus:"+e);
        }
    }
    public CaptureRequest.Builder getDebugCaptureRequestBuilder(){
        final CaptureRequest.Builder captureBuilder;
        try {
            captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            if (mTargetFormat != mPreviewTargetFormat)
                captureBuilder.addTarget(mImageReaderRaw.getSurface());
            else
                captureBuilder.addTarget(mImageReaderPreview.getSurface());
            return captureBuilder;
        } catch (CameraAccessException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
        return null;
    }
    private void debugCapture(CaptureRequest.Builder builder){
        try {
            if (null == mCameraDevice) {
                return;
            }
            Camera2ApiAutoFix.resetTileSize();
            captures = new ArrayList<>();

            int frameCount = 1;
            cameraEventsListener.onFrameCountSet(frameCount);

            captures.add(builder.build());


            Log.d(TAG, "FrameCount:" + frameCount);

            Log.d(TAG, "CaptureStarted!");

            final long[] baseFrameNumber = {0};
            final int[] maxFrameCount = {frameCount};

            cameraEventsListener.onCaptureStillPictureStarted("CaptureStarted!");
            mMeasuredFrameCnt = 0;
            applyAeMeteringRegions(builder);
            mImageSaver.implementation = new DebugSender(cameraEventsListener);

            cameraEventsListener.onBurstPrepared(null);
            this.CaptureCallback = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                             @NonNull CaptureRequest request,
                                             long timestamp,
                                             long frameNumber) {
                    if (mNativeRawPslCapture || mNiceRouted || (mLiveRawSession && !isZslMode())) mLiveRawRouter.request(timestamp, true);
                    if (mNiceRouted) mNiceTailTimestamp = Math.max(mNiceTailTimestamp, timestamp);
                    if (sTimelineSubmitNs > 0) Log.i("NICE_TIMELINE", "bracket start frame=" + frameNumber + " dtMs=" + (timestamp - sTimelineSubmitNs) / 1_000_000
                            + " nowMs=" + (android.os.SystemClock.elapsedRealtimeNanos() - sTimelineSubmitNs) / 1_000_000);

                    if (baseFrameNumber[0] == 0) {
                        baseFrameNumber[0] = frameNumber - 1L;
                        Log.v("BurstCounter", "CaptureStarted with FirstFrameNumber:" + frameNumber);
                    } else {
                        Log.v("BurstCounter", "CaptureStarted:" + frameNumber);
                    }
                    cameraEventsListener.onFrameCaptureStarted(null);
                }

                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                                @NonNull CaptureResult partialResult) {
                    //mCaptureResult = partialResult;
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {

                    int frameCount = (int) (result.getFrameNumber() - baseFrameNumber[0]);
                    Log.v("BurstCounter", "CaptureCompleted! FrameCount:" + frameCount);
                    com.particlesdevs.photoncamera.util.ScameraDebugLog.frame(frameCount, result);
                    com.particlesdevs.photoncamera.util.ScameraDebugLog.remosaicMetadata(
                            session.getDevice().getId() + "/" + physicalID,
                            mCameraCharacteristics, result);
                    long frametime = 100;
                    Object time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    if(time != null) frametime = (long)time;
                    cameraEventsListener.onFrameCaptureCompleted(
                            new TimerFrameCountViewModel.FrameCntTime(frameCount, maxFrameCount[0], frametime));
                    mCaptureResult = result;
                }

                @Override
                public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session,
                                                       int sequenceId,
                                                       long lastFrameNumber) {

                    int finalFrameCount = (int) (lastFrameNumber - baseFrameNumber[0]);
                    Log.v("BurstCounter", "CaptureSequenceCompleted! FrameCount:" + finalFrameCount);
                    Log.v("BurstCounter", "CaptureSequenceCompleted! LastFrameNumber:" + lastFrameNumber);
                    Log.d(TAG, "SequenceCompleted");
                    mBackgroundHandler.postDelayed(() -> {
                        while(mImageSaver.implementation.IMAGE_BUFFER.size() > PhotonCamera.getSettings().frameCount/2) {
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException ignored) {}
                        }
                        cameraEventsListener.onCaptureSequenceCompleted(null);
                    }, 100);
                    mMeasuredFrameCnt = finalFrameCount;
                    burst = false;
                    //Surface texture related
                    activity.runOnUiThread(() -> UpdateCameraCharacteristics(physicalID));
                    if (!isDualSession)
                        unlockFocus();
                    else
                        createCameraPreviewSession(false);
                    taskResults.removeIf(Future::isDone); //remove already completed results
                    Future<?> result = processExecutor.submit(() -> mImageSaver.runRaw(mCameraCharacteristics, mCaptureResult, mCaptureRequest, new ArrayList<>(BurstShakiness), cameraRotation, mExposures));
                    taskResults.add(result);
                }
            };
            burst = true;
            Camera2ApiAutoFix.ApplyBurst();
            if (isDualSession)
                createCameraPreviewSession(true);
            else {
                mCaptureSession.captureBurst(captures, CaptureCallback, mBackgroundHandler);
            }

        } catch (CameraAccessException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    public void runDebug(CaptureRequest.Builder builder){
        activity.runOnUiThread(() -> debugCapture(builder));
    }

    public boolean isZslMode() {
        CameraMode mode=PhotonCamera.getSettings().selectedMode;
        return (mode==CameraMode.MOTION || mode==CameraMode.PHOTO || mode==CameraMode.NIGHT)
                && !isDualSession;
    }

    /** Both routes (the LMC hybrid and SCAM HDR) bracket after the ZSL N frames. */
    private boolean needsExposureBracket() {
        return PreferenceKeys.isVivoNiceEnabled();
    }

    /**
     * How many pre-shutter RAW frames the ZSL ring keeps.
     *
     * The user setting wins when set; 0 means follow the burst frame count, the
     * behaviour before the setting was wired up.
     *
     * Every frame in the ring is a full-size RAW buffer - about 25 MB at 12.6 MP
     * on this sensor - so the ring is the largest single memory consumer in the
     * app. 100 frames is roughly 2.5 GB and will not fit; the setting allows it
     * because it was asked for, but the practical ceiling is where the device
     * stops allocating, and past it the reader starves rather than buffering
     * more.
     */
    /**
     * Hand the newest preview RAW frame to the viewfinder.
     *
     * Called from the image callback, on the frame that is about to go into the
     * ZSL ring - the same data, so no extra stream and no extra HAL load. The
     * copy inside LiveRawFrame is what the viewfinder develops; the Image goes
     * on to the ring untouched.
     */
    // RawPayloadCheck on the preview stream: the first frames of every session, then every 30th.
    private int mPayloadFrames;
    private volatile boolean mPayloadBad;
    private boolean mNicePreviewActive;
    /**
     * Cameras whose preview RAW was not plain 16-bit while VivoNicePreview's stock profile ran (vivo X100 Ultra main: packed
     * 10-bit ZSL frames under the PD2454 stagger / HDR preview tags). Their sessions run the plain Camera2 preview from then on
     * (this process); the ring check still guards the shot when the plain preview is packed too.
     */
    private static final java.util.Set<String> sPlainPreviewCameras = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void watchRawPayload(Image img) {
        int n = mPayloadFrames++;
        if (n >= 4 && n % 30 != 0) return;
        com.particlesdevs.photoncamera.processing.RawPayloadCheck.Result payload =
                com.particlesdevs.photoncamera.processing.RawPayloadCheck.check(img, rawPayloadWhite(null));
        if (payload.plain()) { mPayloadBad = false; return; }
        if (!mPayloadBad) {
            Log.w(TAG, "preview RAW of camera " + physicalID + ": " + payload.error
                    + (mNicePreviewActive ? " (vivo stock preview profile on)" : ""));
            com.particlesdevs.photoncamera.processing.RawPayloadCheck.dumpOnce(img, payload, physicalID);
        }
        mPayloadBad = true;
        if (mNicePreviewActive && sPlainPreviewCameras.add(physicalID) && mBackgroundHandler != null) {
            final int generation = mSessionGeneration.get();
            Log.w("NICE_CAPTURE", "camera " + physicalID + ": vivo stock preview profile off, session restarted with the plain Camera2 preview");
            mBackgroundHandler.post(() -> {
                if (!isCameraResumed || generation != mSessionGeneration.get() || mCaptureSession == null) return;
                // P27: never under a shot in flight (its post-shutter RAWs are still routed): after it, from finishNiceShot.
                if (shotInFlight()) { mPendingPayloadRestart = true; return; }
                mCaptureSession.close();
                createCameraPreviewSession(false);
            });
        }
    }

    private void onMatchedLiveRaw(Image img, TotalCaptureResult result) {
        boolean retained = false;
        try {
            if (!isCameraResumed || !mLiveRawSession || mZslCapturing || mHybridZslCapture || mNiceRingFrozen) return;
            watchRawPayload(img);
            publishLiveRawFrame(img, result);
            if (isZslMode()) synchronized (mZslBufferLock) {
                if (!isCameraResumed || !mLiveRawSession) return;
                mZslRingBuffer.addLast(img);
                retained = true;
                while (mZslRingBuffer.size() > zslRingCapacity()) {
                    Image old = mZslRingBuffer.pollFirst();
                    if (old != null) old.close();
                }
            }
        } finally { if (!retained) img.close(); }
    }

    /** Key of the stream's sensor mode for MosaicStream: the physical sensor, its vendor requests and the RAW size. */
    private String mosaicStreamKey() {
        String size = mImageReaderRaw == null ? "" : mImageReaderRaw.getWidth() + "x" + mImageReaderRaw.getHeight();
        return physicalID + "|" + size + "|" + com.particlesdevs.photoncamera.settings.TunableKeyManager.signature(physicalID)
                + "|" + com.particlesdevs.photoncamera.settings.ModuleRegistry.sensorMode(com.particlesdevs.photoncamera.settings.ModuleRegistry.active());
    }

    /**
     * P13: colour block of the ZSL stream from its first frames (MosaicStream: three agreeing measurements). A mosaic stream
     * switches the viewfinder to the developed RAW; its frames are published with the latest matching result (the colour
     * parameters of the viewfinder change slowly, the ZSL frames are not paired one by one outside the live RAW session).
     */
    private void observeMosaic(Image img) {
        try {
            if (!mMosaicMeasure || !isRawFormat(img.getFormat()) || !isCameraResumed || mPayloadBad) return;
            if (com.particlesdevs.photoncamera.processing.MosaicStream.wantsFrame()) {
                Image.Plane plane = img.getPlanes()[0];
                float black = 0, white = 1023f;
                CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
                if (c == null) c = mCameraCharacteristics;
                BlackLevelPattern blp = c == null ? null : c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
                if (blp != null) { int[] bl = new int[4]; blp.copyTo(bl, 0); black = (bl[0] + bl[1] + bl[2] + bl[3]) / 4f; }
                Integer wl = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
                if (wl != null && wl > black) white = wl;
                java.nio.ByteBuffer samples = plane.getBuffer();
                int sampleStride = plane.getRowStride();
                if (com.particlesdevs.photoncamera.util.RawUnpack.isPacked(img.getFormat())) {
                    // RAW10 / RAW12: measured on the unpacked frame (only the first frames of a session are measured)
                    final int bytes = img.getWidth() * img.getHeight() * 2;
                    if (mMosaicUnpacked == null || mMosaicUnpacked.capacity() < bytes)
                        mMosaicUnpacked = java.nio.ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.nativeOrder());
                    if (!com.particlesdevs.photoncamera.util.RawUnpack.unpack(samples.duplicate(), img.getFormat(), img.getWidth(), img.getHeight(),
                            sampleStride, mMosaicUnpacked)) return;
                    samples = mMosaicUnpacked.duplicate().order(java.nio.ByteOrder.nativeOrder());
                    samples.position(0); samples.limit(bytes);
                    sampleStride = img.getWidth() * 2;
                }
                int block = com.particlesdevs.photoncamera.processing.MosaicStream.observe(
                        com.particlesdevs.photoncamera.processing.MosaicBlockDetector.detect(samples, img.getWidth(), img.getHeight(),
                                sampleStride, black, white, 32));
                if (block > 1 && !mMosaicPreview && !mLiveRawSession) {
                    mMosaicPreview = true;
                    LiveRawFrame.setEnabled(true);
                    LiveRawFrame.setMosaicPreview(true);
                    Log.i(TAG, "mosaic stream (block " + block + "): developed RAW viewfinder");
                } else if (block == 1 && mMosaicPreview) {
                    mMosaicPreview = false;
                    LiveRawFrame.setMosaicPreview(false);
                    LiveRawFrame.setEnabled(false);
                }
            }
            if (mMosaicPreview && !mZslCapturing && !mHybridZslCapture && !mNiceRingFrozen) {
                TotalCaptureResult r;
                synchronized (mZslBufferLock) { r = mHexZslResults.get(img.getTimestamp()); }
                CaptureResult latest = mPreviewCaptureResult;
                if (r == null && latest instanceof TotalCaptureResult) r = (TotalCaptureResult) latest;
                publishLiveRawFrame(img, r);
                if (mTextureView != null) mTextureView.requestRender();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "mosaic preview: " + e.getMessage());
        }
    }

    private void publishLiveRawFrame(Image img, TotalCaptureResult matchedResult) {
        if (!LiveRawFrame.isEnabled() || img == null) return;
        if (!isRawFormat(img.getFormat())) return;
        if (mPayloadBad) {
            // The developed RAW viewfinder would show the same garbage: the ISP preview takes over for this session.
            LiveRawFrame.setEnabled(false);
            return;
        }
        try {
            Image.Plane plane = img.getPlanes()[0];
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            float white = 1023.0f;
            float[] black = new float[]{0, 0, 0, 0};
            int cfa = 0;
            if (c != null) {
                Integer wl = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
                if (wl != null) white = wl;
                BlackLevelPattern blp = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
                if (blp != null) {
                    int[] bl = new int[4];
                    blp.copyTo(bl, 0);
                    for (int i = 0; i < 4; i++) black[i] = bl[i];
                }
                if (mLiveCfaPattern >= 0) {
                    cfa = mLiveCfaPattern;
                } else {
                    Integer arr = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                    if (arr != null) cfa = arr;
                }
            }
            // Exact CaptureResult paired with this image timestamp, never the latest result from another frame.
            float[] gains = new float[]{1, 1, 1};
            float[] ccm = new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
            CaptureResult colorResult=matchedResult;
            if(android.os.Build.VERSION.SDK_INT>=28 && colorResult instanceof TotalCaptureResult){
                CaptureResult physical=((TotalCaptureResult)colorResult).getPhysicalCameraResults().get(physicalID);
                if(physical!=null)colorResult=physical;
            }
            if(colorResult != null){
                android.hardware.camera2.params.RggbChannelVector wb=colorResult.get(CaptureResult.COLOR_CORRECTION_GAINS);
                if(wb!=null){gains[0]=wb.getRed();gains[1]=(wb.getGreenEven()+wb.getGreenOdd())*.5f;gains[2]=wb.getBlue();}
                android.hardware.camera2.params.ColorSpaceTransform matrix=colorResult.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
                if(matrix!=null)for(int col=0;col<3;col++)for(int row=0;row<3;row++)ccm[col*3+row]=matrix.getElement(col,row).floatValue();
                float[] dynamicBlack=colorResult.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
                if(dynamicBlack!=null&&dynamicBlack.length==4)black=dynamicBlack;
                Integer dynamicWhite=colorResult.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
                if(dynamicWhite!=null)white=dynamicWhite;
            }
            String sensorPrefix=com.particlesdevs.photoncamera.settings.ModuleSensorSettings.prefix(
                    com.particlesdevs.photoncamera.settings.ModuleSensorSettings.runtimeScope(physicalID));
            android.content.SharedPreferences sensorValues=PhotonCamera.getSettingsManagerStatic().getDefaultPreferences();
            float overrideBlack=(float)com.particlesdevs.photoncamera.settings.PreferenceNumber.read(
                    com.particlesdevs.photoncamera.settings.PreferenceValue.get(sensorValues,sensorPrefix+"blackleveloverride"),-1);
            float overrideWhite=(float)com.particlesdevs.photoncamera.settings.PreferenceNumber.read(
                    com.particlesdevs.photoncamera.settings.PreferenceValue.get(sensorValues,sensorPrefix+"whiteleveloverride"),-1);
            if(overrideWhite>0)white=overrideWhite;
            if(overrideBlack>=0&&overrideBlack<white)java.util.Arrays.fill(black,overrideBlack);
            float[] dcpMatrix = com.particlesdevs.photoncamera.processing.color.DcpProfiles.previewMatrix(gains);
            if (dcpMatrix != null) ccm = dcpMatrix;
            float[] shading=null;int sw=1,sh=1;
            android.hardware.camera2.params.LensShadingMap map=colorResult==null?null:colorResult.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
            if(map!=null&&com.particlesdevs.photoncamera.processing.render.Parameters.rawShadingAlreadyApplied(c))map=null;
            if(map!=null){sw=map.getColumnCount();sh=map.getRowCount();shading=new float[sw*sh*3];
                for(int y=0;y<sh;y++)for(int x=0;x<sw;x++){int i=(y*sw+x)*3;shading[i]=map.getGainFactor(0,x,y);shading[i+1]=(map.getGainFactor(1,x,y)+map.getGainFactor(2,x,y))*.5f;shading[i+2]=map.getGainFactor(3,x,y);}}
            Rect imageCrop = img.getCropRect();
            float[] crop = {imageCrop.left/(float)img.getWidth(), imageCrop.top/(float)img.getHeight(),
                    imageCrop.width()/(float)img.getWidth(), imageCrop.height()/(float)img.getHeight()};
            // Camera2 crop coordinates are in the active sensor array, not in the RAW buffer.
            Rect active = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Rect sensorCrop = colorResult == null ? null : colorResult.get(CaptureResult.SCALER_CROP_REGION);
            if (active != null && sensorCrop != null && active.width()>0 && active.height()>0) {
                Rect clipped = new Rect(sensorCrop);
                if (clipped.intersect(active)) {
                    crop = new float[]{(clipped.left-active.left)/(float)active.width(),
                        (clipped.top-active.top)/(float)active.height(), clipped.width()/(float)active.width(),
                        clipped.height()/(float)active.height()};
                }
            }
            double shotNoise=0,readNoise=0;
            android.util.Pair<Double,Double>[] noiseProfile=colorResult.get(CaptureResult.SENSOR_NOISE_PROFILE);
            if(noiseProfile!=null&&noiseProfile.length>0){
                int count=0;for(android.util.Pair<Double,Double> n:noiseProfile)if(n!=null&&n.first!=null&&n.second!=null){shotNoise+=n.first;readNoise+=n.second;count++;}
                if(count>0){shotNoise/=count;readNoise/=count;}
            }
            LiveRawFrame.publish(plane.getBuffer(), img.getWidth(), img.getHeight(),
                    plane.getRowStride(), cfa, white, black, gains, ccm,shading,sw,sh,crop,
                    com.particlesdevs.photoncamera.processing.MosaicStream.block() > 1
                            ? com.particlesdevs.photoncamera.processing.MosaicStream.block() : PreferenceKeys.mosaicBlock(),
                    colorResult.get(CaptureResult.SENSOR_SENSITIVITY),
                    c == null ? null : c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY),
                    !Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_OFF).equals(colorResult.get(CaptureResult.CONTROL_AE_MODE)),shotNoise,readNoise,
                    img.getFormat());
            if (mTextureView != null) mTextureView.requestRender();
        } catch (Exception e) {
            Log.w(TAG, "publishLiveRawFrame: " + e.getMessage());
        }
    }

    public static int zslRingCapacity() {
        // One global, user-adjustable capacity. Capture counts never resize the ring.
        return PreferenceKeys.getZslBufferCountValue();
    }

    private long niceZslShutterTimestamp;
    private int mOpenRetries;

    /** Camera2-domain bracket (no root): N = preview exposure, L/S/ES by the configured steps. */
    private VivoStockAe.Plan scameraBracketPlan() {
        // N is the buffered ZSL RAW at the shutter: take its own metadata, not the
        // latest preview result (that can already belong to another request).
        CaptureResult preview;String nSource="zsl_cutoff";
        synchronized (mZslBufferLock) {
            preview=mHexZslResults.get(niceZslShutterTimestamp);
            if(preview==null) {
                long best=Long.MIN_VALUE;nSource="zsl_newest";
                for(java.util.Map.Entry<Long,TotalCaptureResult> e:mHexZslResults.entrySet())
                    if(e.getKey()<=niceZslShutterTimestamp && e.getKey()>best){best=e.getKey();preview=e.getValue();}
            }
        }
        if(preview==null){preview=mPreviewCaptureResult;nSource="preview";}
        Long nShutter=preview==null?null:preview.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer nIso=preview==null?null:preview.get(CaptureResult.SENSOR_SENSITIVITY);
        // Real N gain from the vendor AE (can exceed the Camera2 range at night).
        final double trueIso=VivoStockAe.Plan.vendorIso(preview);
        final float clip=zslClipFraction();
        mLastZslClipFraction=clip;
        VivoStockAe.Plan plan=VivoStockAe.Plan.scameraPlanner(nShutter==null?0:nShutter,nIso==null?0:nIso,trueIso,clip,
                PreferenceKeys.niceInternalValue("planner_l_ev",1f),PreferenceKeys.niceInternalValue("planner_s_ev",3f),
                PreferenceKeys.niceInternalValue("planner_es_ev",6f),PreferenceKeys.isNicePlannerAdaptive(),
                mCameraCharacteristics,niceZslShutterTimestamp,mSessionGeneration.get())
                .withLongBoost(PreferenceKeys.getNiceLongBoostEv(),mCameraCharacteristics);
        Log.i("NICE_CAPTURE","bracket=SCAMERA_planner Camera2_RAW timestamp="+plan.timestamp+" N="+nShutter+"ns ISO"+nIso
                +" from="+nSource+" "+plan.sceneDescription);
        return plan;
    }

    /**
     * P27: the Hybrid's N without the SCAM HDR planner (whose throws cost Hybrid shots): the ring frame at the shutter, else
     * the newest ring result before it, the preview result, the preview AE values. Null when no N exposure is known at all
     * (the Hybrid then lets the camera's AE expose its frames).
     */
    private VivoStockAe.Plan hybridNPlan() {
        CaptureResult n;String source="zsl_cutoff";
        synchronized (mZslBufferLock) {
            n=mHexZslResults.get(niceZslShutterTimestamp);
            if(n==null) {
                long best=Long.MIN_VALUE;source="zsl_newest";
                for(java.util.Map.Entry<Long,TotalCaptureResult> e:mHexZslResults.entrySet())
                    if(e.getKey()<=niceZslShutterTimestamp && e.getKey()>best){best=e.getKey();n=e.getValue();}
            }
        }
        if(n==null){n=mPreviewCaptureResult;source="preview";}
        Long ns=n==null?null:n.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso=n==null?null:n.get(CaptureResult.SENSOR_SENSITIVITY);
        long shutter=ns!=null&&ns>0?ns:0;int sensitivity=iso!=null&&iso>0?iso:0;
        if(shutter<=0||sensitivity<=0){shutter=mPreviewExposureTime;sensitivity=mPreviewIso;source="preview_ae";}
        mLastZslClipFraction=zslClipFraction();
        if(shutter<=0||sensitivity<=0){
            Log.w("NICE_CAPTURE","bracket=HYBRID no N exposure known: the camera's AE exposes the frames");
            return null;
        }
        Log.i("NICE_CAPTURE","bracket=HYBRID N="+shutter+"ns ISO"+sensitivity+" from="+source);
        return VivoStockAe.Plan.nOnly(shutter,sensitivity,niceZslShutterTimestamp,mSessionGeneration.get());
    }

    /** Fraction of clipped RAW samples in the newest buffered ZSL frame (sparse sample). */
    /** Clipped fraction of the newest buffered RAW at the last plan (the ring is drained before the hybrid plan is built). */
    private float mLastZslClipFraction;
    private float zslClipFraction() {
        // The newest ring frame whose RAW is plain 16-bit: a packed payload reads as ~62 % clipped (X100 Ultra) and planned
        // the bracket from garbage. None plain: no clipping is assumed (the shot then takes N after the shutter).
        Image newest = null;
        synchronized (mZslBufferLock) {
            int looked = 0;
            for (java.util.Iterator<Image> it = mZslRingBuffer.descendingIterator(); it.hasNext() && looked < 4; looked++) {
                Image candidate = it.next();
                if (com.particlesdevs.photoncamera.processing.RawPayloadCheck.check(candidate,
                        rawPayloadWhite(mHexZslResults.get(candidate.getTimestamp()))).plain()) { newest = candidate; break; }
            }
            if (newest == null && looked > 0) Log.w("NICE_CAPTURE", "ZSL clip estimate: no plain 16-bit RAW among the newest " + looked);
        }
        if (newest == null || mCameraCharacteristics == null || newest.getFormat() != ImageFormat.RAW_SENSOR) return 0f;
        try {
            Integer white = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            int limit = (int) ((white == null ? 1023 : white) * 0.95f);
            Image.Plane plane = newest.getPlanes()[0];
            java.nio.ShortBuffer data = plane.getBuffer().duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
            int stride = plane.getRowStride() / 2, w = newest.getWidth(), h = newest.getHeight();
            long clipped = 0, total = 0;
            // Bulk row reads: per-sample get() on the direct view cost ~10 ms per press.
            short[] row = new short[w];
            for (int y = 0; y < h; y += 8) {
                data.position(y * stride);
                data.get(row, 0, w);
                for (int x = 0; x < w; x += 4) {
                    if ((row[x] & 0xffff) >= limit) clipped++;
                    total++;
                }
            }
            return total == 0 ? 0f : (float) clipped / total;
        } catch (RuntimeException e) {
            Log.w("NICE_CAPTURE", "ZSL clip estimate unavailable: " + e);
            return 0f;
        }
    }

    /** White level for RawPayloadCheck: the previewed physical camera, the frame's dynamic white when its result is known. */
    private int rawPayloadWhite(CaptureResult result) {
        CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
        return com.particlesdevs.photoncamera.processing.RawPayloadCheck.whiteLevel(c == null ? mCameraCharacteristics : c, result);
    }

    /**
     * Ring RAWs whose payload is not plain 16-bit (RawPayloadCheck) leave the burst before anything reads them. With fewer than
     * four left the existing normal-back path takes N after the shutter (those frames were plain on the X100 Ultra).
     */
    private void dropNonPlainRaw(List<Image> images, java.util.Map<Long, TotalCaptureResult> results) {
        int dropped = 0;
        String first = null;
        for (java.util.Iterator<Image> it = images.iterator(); it.hasNext();) {
            Image image = it.next();
            com.particlesdevs.photoncamera.processing.RawPayloadCheck.Result payload =
                    com.particlesdevs.photoncamera.processing.RawPayloadCheck.check(image, rawPayloadWhite(results.get(image.getTimestamp())));
            if (payload.plain()) continue;
            if (first == null) {
                first = payload.error;
                com.particlesdevs.photoncamera.processing.RawPayloadCheck.dumpOnce(image, payload, physicalID);
            }
            image.close();
            it.remove();
            dropped++;
        }
        if (dropped > 0) Log.w("NICE_HDR", "ZSL: " + dropped + " ring RAWs dropped (" + first + "), " + images.size() + " kept");
    }

    private List<ImageFrame> drainZslNormalFrames(int requestedCount,VivoStockAe.Plan stockPlan,boolean defer) {
        return drainZslNormalFrames(requestedCount,stockPlan,defer,false);
    }

    /**
     * P27, {@code hybrid}: the Hybrid needs one usable N frame, never SCAM HDR's four at the plan's exact exposure. Its ring
     * selection keeps the frames at the newest frame's exposure (0.05 EV, as before) and, only when fewer than
     * min(requested, 8) remain (a press during an AE ramp, the first press after a lens switch), adds frames up to 0.5 EV
     * darker, nearest first; never brighter (the merge would accumulate their clipped samples). Each frame keeps its own
     * exposure (the worker normalises by it). The ring is never emptied for the Hybrid while one plain frame is left (X300
     * Ultra 2026-10-06: 3 of 15 ring frames matched, the shot fell into SCAM HDR's strict normal-back and was lost).
     */
    private List<ImageFrame> drainZslNormalFrames(int requestedCount,VivoStockAe.Plan stockPlan,boolean defer,boolean hybrid) {
        List<Image> rawImages;
        java.util.Map<Long,TotalCaptureResult> selectedMetadata;
        synchronized (mZslBufferLock) {
            rawImages = new ArrayList<>(mZslRingBuffer);
            mZslRingBuffer.clear();
            mNiceRingFrozen = false;
            selectedMetadata = new HashMap<>(mHexZslResults);
            mHexZslResults.clear();
        }
        mNativeZslBase=null;
        dropNonPlainRaw(rawImages, selectedMetadata);
        if (PreferenceKeys.isVivoNiceEnabled()) {
            // RAW may arrive before its TotalCaptureResult. Select from matched
            // pairs BEFORE taking the newest N images, so older complete ZSL
            // frames can fill the burst without guessing another frame's ISO.
            boolean firstMismatch=true;
            for (java.util.Iterator<Image> it = rawImages.iterator(); it.hasNext();) {
                Image image = it.next();
                TotalCaptureResult result = selectedMetadata.get(image.getTimestamp());
                Long exposure = result == null ? null : result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer iso = result == null ? null : result.get(CaptureResult.SENSOR_SENSITIVITY);
                boolean matchesPlan=true;
                if(stockPlan!=null && !hybrid)try{stockPlan.verifyZslNormal(result,niceZslShutterTimestamp);}
                catch(RuntimeException mismatch){matchesPlan=false;
                    if(result!=null && firstMismatch){firstMismatch=false;Log.w("NICE_HDR",mismatch.getMessage());}}
                if (exposure == null || exposure <= 0 || iso == null || iso <= 0
                        || !matchesPlan
                        || (niceZslShutterTimestamp <= 0 || image.getTimestamp() > niceZslShutterTimestamp)) {
                    Log.w("NICE_HDR", "Skip unpaired ZSL RAW timestamp=" + image.getTimestamp()
                            + " result=" + (result != null) + " exposureNs=" + exposure + " ISO=" + iso);
                    image.close();
                    it.remove();
                }
            }
            if (hybrid && !rawImages.isEmpty()) selectHybridRing(rawImages, selectedMetadata, requestedCount);
            Log.i("NICE_HDR", "ZSL matched RAWs=" + rawImages.size() + " requested=" + requestedCount);
            if(!hybrid && stockPlan!=null && rawImages.size()<4) {
                // Stock normal-back (VAF niceNormalBack 0x29a714): when the
                // planned N exposure is not what the preview stream ran at
                // (low light: longer N than preview), the stock takes N after
                // the shutter. The caller submits all seven planned requests.
                for(Image image:rawImages)image.close();
                Log.i("NICE_HDR","normalBack: no four buffered RAWs at planned N; N captured after shutter");
                return new ArrayList<>();
            }
        }
        rawImages.sort(java.util.Comparator.comparingLong(Image::getTimestamp));
        int take = Math.min(rawImages.size(), Math.max(0, requestedCount));
        int skip = rawImages.size() - take;
        for (int i = 0; i < skip; i++) rawImages.get(i).close();

        if (PreferenceKeys.isVivoNiceEnabled() && take > 0) {
            mNativeZslBase = selectedMetadata.get(rawImages.get(rawImages.size()-1).getTimestamp());
            Log.i("NICE_HDR", "ZSL shutter cutoff=" + niceZslShutterTimestamp
                    + " selected=" + take + " newest=" + rawImages.get(rawImages.size()-1).getTimestamp());
        }
        double exposureSeconds = 1.0;
        double isoValue = 100.0;
        CaptureResult baseResult=mNativeZslBase!=null?mNativeZslBase:mPreviewCaptureResult;
        if (baseResult != null) {
            Long exp = baseResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = baseResult.get(CaptureResult.SENSOR_SENSITIVITY);
            if (exp != null) exposureSeconds = exp / 1_000_000_000.0;
            if (iso != null) isoValue = iso.doubleValue();
        }
        double exposureProduct = exposureSeconds * isoValue;
        List<ImageFrame> selected = new ArrayList<>();
        for (int i = skip; i < rawImages.size(); i++) {
            Image img = rawImages.get(i);
            int rowStride = img.getPlanes()[0].getRowStride();
            int pixelStride = img.getPlanes()[0].getPixelStride();
            int width = com.particlesdevs.photoncamera.util.Allocator.isPackedRaw(img.getFormat()) ? img.getWidth()
                    : (pixelStride > 0 ? rowStride / pixelStride : img.getWidth());
            int height = img.getHeight();
            int capacity = img.getPlanes()[0].getBuffer().capacity();
            int offset = 0;
            if (PhotonCamera.getSettings().aspect169 && width > height) {
                // Even rows (P27): a 4080-wide stream gave 2295 rows, which the merges refuse.
                height = (width * 9 / 16) & ~1;
                int offsetH = (img.getHeight() - height) / 2;
                offsetH -= offsetH % 2;
                offset = rowStride * offsetH;
                capacity = rowStride * height;
            }
            Allocator.binning = PhotonCamera.getSettings().binning;
            // Deferred: the copy of ~20 RAWs (~200 ms) runs after the bracket is
            // submitted, in parallel with its exposure (startZslCopy).
            ImageFrame frame = defer
                    ? ImageFrame.deferred(img, img.getFormat(), width, rowStride, offset, capacity)
                    : new ImageFrame(img.getPlanes()[0].getBuffer(), img.getFormat(), width, rowStride, offset, capacity);
            frame.timestamp = img.getTimestamp();frame.fromZsl=true;
            frame.setCaptureMetadata(selectedMetadata.get(frame.timestamp));
            frame.width = PhotonCamera.getSettings().binning ? width / 2 : width;
            frame.height = PhotonCamera.getSettings().binning ? height / 2 : height;
            if (!defer) img.close();
            mExposures.put(frame.timestamp, frame.measuredExposure > 0 && frame.measuredIso > 0
                    ? frame.measuredExposure / 1e9 * frame.measuredIso : exposureProduct);
            selected.add(frame);
        }
        return selected;
    }

    /** P27: the Hybrid's ring selection (see drainZslNormalFrames); closes the frames it does not keep. */
    private void selectHybridRing(List<Image> rawImages, java.util.Map<Long,TotalCaptureResult> metadata, int requestedCount) {
        Image base = null;
        for (Image image : rawImages) if (base == null || image.getTimestamp() > base.getTimestamp()) base = image;
        final double ref = product(metadata.get(base.getTimestamp()));
        List<Image> exact = new ArrayList<>(), darker = new ArrayList<>(), rest = new ArrayList<>();
        for (Image image : rawImages) {
            double ev = Math.log(product(metadata.get(image.getTimestamp())) / ref) / Math.log(2);
            if (Math.abs(ev) <= 0.05) exact.add(image);
            else if (ev >= -0.5 && ev < 0) darker.add(image);
            else rest.add(image);
        }
        exact.sort(java.util.Comparator.comparingLong(Image::getTimestamp).reversed());
        List<Image> keep = new ArrayList<>(exact.subList(0, Math.min(exact.size(), Math.max(1, requestedCount))));
        for (Image image : exact.subList(keep.size(), exact.size())) rest.add(image);
        final int fill = Math.min(Math.max(1, requestedCount), 8);
        int widened = 0;
        if (keep.size() < fill) {
            darker.sort(java.util.Comparator.comparingDouble(image -> -product(metadata.get(image.getTimestamp()))));
            for (Image image : darker) {
                if (keep.size() < fill) { keep.add(image); widened++; } else rest.add(image);
            }
        } else rest.addAll(darker);
        // P27 (VERIFY-11): the ring statistics of every shot (tier 1 = at the newest exposure, tier 2 = darker down to -0.5 EV).
        Log.i("NICE_HDR", "hybrid ZSL ring: tier1=" + (keep.size() - widened) + " tier2=" + widened + " requested=" + requestedCount
                + " exact=" + exact.size() + " darker=" + darker.size());
        if (widened > 0) Log.w("NICE_HDR", "hybrid ZSL: " + exact.size() + " ring frames at the newest exposure, widened by " + widened
                + " frames down to -0.5 EV (AE ramp)");
        for (Image image : rest) {
            Log.w("NICE_HDR", "hybrid ZSL: ring RAW timestamp=" + image.getTimestamp() + " not used (exposure "
                    + String.format(java.util.Locale.ROOT, "%+.2f EV", Math.log(product(metadata.get(image.getTimestamp())) / ref) / Math.log(2))
                    + " from the newest frame)");
            image.close();
        }
        rawImages.clear();
        rawImages.addAll(keep);
    }

    private static double product(CaptureResult result) {
        Long ns = result == null ? null : result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result == null ? null : result.get(CaptureResult.SENSOR_SENSITIVITY);
        return ns == null || iso == null ? 0 : (double) ns * iso;
    }

    private boolean isGyroClockComparable() {
        Integer source = mCameraCharacteristics == null ? null
                : mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        return source != null && source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
    }

    private void captureStillPicture() {
        // NICE may wait briefly for the stock AE plan; never block the UI thread.
        if (PreferenceKeys.isVivoNiceEnabled() && mBackgroundHandler != null
                && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            mBackgroundHandler.post(this::captureStillPicture);
            return;
        }
        com.particlesdevs.photoncamera.control.ZoomController.markShot();
        try {
            if (null == mCameraDevice) {
                failPendingShutter(new IllegalStateException(Lang.t("Камера закрыта", "Camera closed")));
                return;
            }
            SensorConfigInjector.applyToSensor(physicalID, this);
            // Every photo is merged by the LMC hybrid or SCAM HDR, both from the ZSL RAW stream (Photo and Night).
            if (!PreferenceKeys.isVivoNiceEnabled() || !isZslMode())
                throw new IllegalStateException(Lang.t("Нет маршрута склейки: Hybrid и SCAM HDR снимают из ZSL RAW-потока (режимы Фото и Ночь)", "No merge route: Hybrid and SCAM HDR shoot from the ZSL RAW stream (Photo and Night modes)"));
            final boolean niceCapture = PreferenceKeys.isVivoNiceEnabled();
            // The LMC hybrid takes the NICE capture route with its own settings (independent of SCAM HDR; wins over it).
            final boolean lmcHybridSelected = niceCapture && PreferenceKeys.isLmcHybridEnabled();
            final boolean hybridZslRequested = isZslMode() && needsExposureBracket();
            VivoStockAe.Plan planned;
            boolean scamToHybrid=false;
            {
                if(!hybridZslRequested)throw new IllegalStateException((lmcHybridSelected?"Hybrid":"SCAM HDR")+Lang.t(" требует ZSL RAW-поток"," needs the ZSL RAW stream"));
                // The hybrid's N is the ring at the preview exposure (P27: its own N, never the SCAM HDR planner).
                if(lmcHybridSelected) {
                    planned=hybridNPlan();
                } else if(!PreferenceKeys.useStockBracketPlanner()) {
                    try {
                        planned=scameraBracketPlan();
                    } catch(IllegalStateException noBracket) {
                        // P27: no S/ES plan (bright scene at the sensor floor, no N metadata): this shot is captured and merged
                        // by the Hybrid instead of failing.
                        Log.w("NICE_CAPTURE","SCAM HDR plan unavailable ("+noBracket.getMessage()+"); this shot is taken by the Hybrid");
                        scamToHybrid=true;
                        planned=hybridNPlan();
                    }
                } else {
                VivoStockAe stock=mStockAe;
                final boolean mayWait=android.os.SystemClock.elapsedRealtime()<mNicePlanDeadline && mBackgroundHandler!=null;
                if(stock==null || stock.generation!=mSessionGeneration.get()) {
                    if(mayWait){mBackgroundHandler.postDelayed(this::captureStillPicture,50);return;}
                }
                // Poll without blocking this camera thread: it also delivers
                // the preview results the stock AE plan is bound to.
                VivoStockAe.Plan solved;
                try {
                    if(stock==null || stock.generation!=mSessionGeneration.get())
                        throw new IllegalStateException(Lang.t("Стоковый AE ещё не готов","Stock AE is not ready yet"));
                    VivoStockAe.Plan ready=stock.tryFreeze(niceZslShutterTimestamp,mayWait);
                    if(ready==null){mBackgroundHandler.postDelayed(this::captureStillPicture,50);return;}
                    solved=ready.withDistinctBracket(mCameraCharacteristics)
                            .withLongBoost(PreferenceKeys.getNiceLongBoostEv(),mCameraCharacteristics);
                } catch(IllegalStateException unavailable) {
                    // No usable stock plan (observer failed, stale, or bright scene the
                    // stock bracket cannot separate): the shot still goes through.
                    Log.w("NICE_CAPTURE","stock plan unavailable ("+unavailable.getMessage()+"); using SCAMERA planner for this shot");
                    solved=null;
                }
                mStockObserverReady=solved!=null;
                if(solved!=null && solved.fitsCamera(mCameraCharacteristics)) {
                    planned=solved;
                    Log.i("NICE_CAPTURE","bracket=Vivo_stock_solver Camera2_RAW frameId="+planned.frameId+" timestamp="+planned.timestamp+" "+planned.sceneDescription);
                } else {
                    // The vendor gain->ISO conversion is the main camera's; on other
                    // modules (HP9 tele) the plan can land outside the Camera2 range.
                    if(solved!=null)Log.w("NICE_CAPTURE","stock plan outside Camera2 range for camera "+PhotonCamera.getSettings().mCameraID
                            +" ("+solved.describeRanges()+"; "+VivoStockAe.Plan.describeCamera(mCameraCharacteristics)
                            +"); using SCAMERA planner for this shot");
                    try {
                        planned=scameraBracketPlan();
                    } catch(IllegalStateException noBracket) {
                        Log.w("NICE_CAPTURE","SCAM HDR plan unavailable ("+noBracket.getMessage()+"); this shot is taken by the Hybrid");
                        scamToHybrid=true;
                        planned=hybridNPlan();
                    }
                }
                }
            }
            final VivoStockAe.Plan stockPlan=planned;
            // The capture route of this shot (P27: a SCAM HDR shot without an S/ES plan is captured and merged by the Hybrid).
            final boolean lmcHybridShot=lmcHybridSelected||scamToHybrid;
            final HybridPlan[] hybridPlanHolder={null};
            final float[] normalBackLongRatio={0f};
            final boolean niceZslRequested = hybridZslRequested && niceCapture;
            if (niceZslRequested) {
                mLiveRawRouter.clear();
                mNativeRawPslCapture = true;
            }
            {
                mLiveRawRouter.clear();
                mNativeRawPslCapture=true;mZslCapturing=true;
                if(!hybridZslRequested) synchronized(mZslBufferLock) {
                    for(Image image:mZslRingBuffer)image.close();
                    mZslRingBuffer.clear();mHexZslResults.clear();
                }
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder;
            if(PhotonCamera.getSettings().selectedMode.equals(CameraMode.RAWVIDEO)) {
                captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                captureBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, getSelectedFpsRange());
            } else {
                captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            }
            float focus = mFocus;
            double frametime = stockPlan != null ? ExposureIndex.time2sec(stockPlan.shutter(0)) : 1 / 30.0;
            //this.mCaptureSession.stopRepeating();
            if(isDualSession) {
                if (mTargetFormat != mPreviewTargetFormat)
                    captureBuilder.addTarget(mImageReaderRaw.getSurface());
                else
                    captureBuilder.addTarget(mImageReaderPreview.getSurface());
            } else {
                // NICE keeps the preview running: its L/S/ES must not reach the
                // viewfinder (they flashed dark/bright there), as in the stock camera.
                captureBuilder.addTarget(mImageReaderRaw.getSurface());
            }
            Camera2ApiAutoFix.resetTileSize();
            applyZoom(captureBuilder, false);
            cameraRotation = PhotonCamera.getGravity().getCameraRotation(mSensorOrientation);

            //captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            //setCaptureAEMode(captureBuilder);
            if (mFlashed) captureBuilder.set(FLASH_MODE, FLASH_MODE_TORCH);
            Log.d(TAG, "Focus:" + focus);
            captureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);

            int[] stabilizationModes = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            if (stabilizationModes != null && stabilizationModes.length > 1) {
                Log.d(TAG, "LENS_OPTICAL_STABILIZATION_MODE");
                applyOisMode(captureBuilder, true);//Fix ois bugs for preview and burst
            }

            for (int i = 0; i < 3; i++) {
                Log.d(TAG, "Temperature:" + mPreviewTemp[i]);
            }
            Log.d(TAG, "CaptureBuilderStarted!");
            //setAutoFlash(captureBuilder);
            //int rotation = Interface.getGravity().getCameraRotation();//activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, PhotonCamera.getGravity().getCameraRotation(mSensorOrientation));
            if (mTouchFocus != null && mTouchFocus.isTouchFocus) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_REGIONS));
                captureBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AF_REGIONS));
            } else {
                applyAeMeteringRegions(captureBuilder);
            }
            VendorTagUtils.builderSessionApply(captureBuilder, true, useMaximumResolutionKey, physicalID);
            try {
                captureBuilder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            } catch (Exception e) {
                Log.d(TAG, "Failed to set LENS_SHADING_MAP_MODE_ON:" + Log.getStackTraceString(e));
            }

            captures = new ArrayList<>();
            BurstShakiness = new ArrayList<>();
            mExposures = new HashMap<>();
            SaverImplementation.IMAGE_BUFFER.clear();

            // SCAM HDR's plan: four N, short S and ES, long L (the N normally come from the ZSL ring); the hybrid
            // takes its own N count from the ring and its Bento / Shasta requests from HybridPlan.
            int denoiseFrameCount = 4, shortFrameCount = 2, longFrameCount = 1;
            if (hybridZslRequested) {
                // Block preview RAWs first. Do not route them into ImageSaver:
                // queued preview images would consume the bracket frame slots.
                mHybridZslCapture = false;
                mZslCapturing = true;
                // This graph consumes four N inputs; avoid selecting an old
                // reference from a longer series that the graph cannot use.
                // The graph consumes four N; frames beyond four (same exposure,
                // newest first) are merged into those slots by the worker.
                mPendingZslNormalFrames = drainZslNormalFrames(
                        lmcHybridShot ? PreferenceKeys.getHybridZslFrames() : Math.max(4, PreferenceKeys.getNiceZslFrames()),stockPlan,
                        niceZslRequested,lmcHybridShot);
                if (!mPendingZslNormalFrames.isEmpty()) {
                    denoiseFrameCount = mPendingZslNormalFrames.size();
                    long[] zslTimestamps = new long[denoiseFrameCount];
                    for (int i = 0; i < denoiseFrameCount; i++) {
                        zslTimestamps[i] = mPendingZslNormalFrames.get(i).timestamp;
                    }
                    CaptureResult gyroBase=mNativeZslBase!=null?mNativeZslBase:mPreviewCaptureResult;
                    Long previewExposure = gyroBase != null ? gyroBase.get(CaptureResult.SENSOR_EXPOSURE_TIME) : null;
                    PhotonCamera.getGyro().buildZslBurstShakiness(zslTimestamps,
                            previewExposure != null ? previewExposure : 0L, BurstShakiness, isGyroClockComparable());
                } else {
                    // Camera was just opened, the ring has not filled yet or holds no usable frame: N is taken after the
                    // shutter this once (tolerant for both routes, P27).
                    Log.w(TAG, (lmcHybridShot ? "Hybrid" : "SCAM HDR") + " ZSL ring empty; N frames taken after the shutter");
                }
                if (niceZslRequested) {
                    // Keep the viewfinder and AE running: requests submitted with
                    // capture() go ahead of the repeating preview, and every RAW is
                    // routed by its capture-start timestamp from here on.
                    mLiveRawRouter.clear();
                    mNiceRouted = true;
                } else {
                    try {
                        mCaptureSession.stopRepeating();
                        mCaptureSession.abortCaptures();
                    } catch (CameraAccessException e) {
                        Log.w(TAG, "Could not fully stop ZSL preview before bracket", e);
                    }
                    try {
                        Image queued;
                        while ((queued = mImageReaderRaw.acquireNextImage()) != null) queued.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            final boolean hybridZsl = hybridZslRequested && !mPendingZslNormalFrames.isEmpty();
            Log.i(TAG, "NICE controls: normal=" + denoiseFrameCount + " short=" + shortFrameCount
                    + " long=" + longFrameCount + " route=" + PreferenceKeys.mergeRoute());
            // Keep the total inside PhotonCamera's proven RAW buffer ceiling.
            int frameCount = denoiseFrameCount > 0
                    ? Math.min(37, denoiseFrameCount + shortFrameCount + longFrameCount)
                    : denoiseFrameCount;
            longFrameCount = denoiseFrameCount > 0
                    ? Math.max(0, frameCount - denoiseFrameCount - shortFrameCount) : 0;
            //if (frameCount == 1) frameCount++;
            cameraEventsListener.onFrameCountSet(frameCount);
            Log.d(TAG, "HDRFact1:" + paramController.isManualMode() + " HDRFact2:" + PhotonCamera.getSettings().alignAlgorithm);
            //IsoExpoSelector.HDR = (!manualParamModel.isManualMode()) && (PhotonCamera.getSettings().alignAlgorithm == 0);
            //IsoExpoSelector.HDR = (PhotonCamera.getSettings().alignAlgorithm == 1);
            Log.d(TAG, "HDR:" + IsoExpoSelector.HDR);
            Object mode = mPreviewRequestBuilder.get(CONTROL_AF_MODE);
            if(mode != null && (int) mode != CaptureRequest.CONTROL_AF_MODE_AUTO || PreferenceKeys.getAfMode() == CaptureRequest.CONTROL_AF_MODE_AUTO && !PhotonCamera.getSettings().selectedMode.equals(CameraMode.RAWVIDEO)) {
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            }
            //captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_EDOF);
            //if ((!(focus == 0.0 && Build.BRAND.equalsIgnoreCase("samsung")))) {
                MeteringRectangle rectaf = new MeteringRectangle(0, 0, 0, 0, 0);
                //captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CONTROL_AF_MODE_OFF);
                //captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_CANCEL);
                //captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
                /*if(!mTouchFocus.isTouchFocus)
                    captureBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{rectaf});
                else {
                    captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                    captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                    //captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, mFocus);
                }
                if (paramController.FOCUS != -1){
                    captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                    captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, paramController.FOCUS);
                }*/
            //}
            /*
            if(!isDualSession){
                captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_IDLE);
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
            }*/



            /*mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            if (focus != 0.0)
                mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
            rebuildPreviewBuilder();*/

            paramController.applyWhiteBalance(captureBuilder);
            IsoExpoSelector.useTripod = PhotonCamera.getGyro().getTripod();
            if(lmcHybridShot) {
                // LMC hybrid: N from the ring, then the ultrashort (Bento) and the bracketed frames (Shasta) of the plan.
                // P27: one ring frame is enough; with an empty ring N is taken after the shutter (tolerant), without any
                // known N exposure the camera's AE exposes the frames. Planning never throws.
                IsoExpoSelector.fullpairs.clear();
                HybridPlan plan;
                if(hybridZsl) {
                    // N = the newest ring frame (the base of the selection): its own exposure, not the cutoff plan's.
                    CaptureResult base=mNativeZslBase;
                    Long baseNs=base==null?null:base.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer baseIso=base==null?null:base.get(CaptureResult.SENSOR_SENSITIVITY);
                    long nNs=baseNs!=null&&baseNs>0?baseNs:stockPlan!=null?stockPlan.shutter(0):0;
                    int nIso=baseIso!=null&&baseIso>0?baseIso:stockPlan!=null?stockPlan.iso(0):0;
                    try {
                        plan=HybridPlan.build(nNs,nIso,mLastZslClipFraction,mCameraCharacteristics,mExposureLimits);
                    } catch(RuntimeException noPlan) {
                        Log.w("NICE_CAPTURE","hybrid plan unavailable ("+noPlan.getMessage()+"): one more N frame after the shutter");
                        plan=HybridPlan.single(nNs,nIso);
                    }
                } else {
                    plan=stockPlan!=null
                            ? HybridPlan.buildNormalBack(stockPlan.shutter(0),stockPlan.iso(0),mLastZslClipFraction,mCameraCharacteristics,4,mExposureLimits)
                            : HybridPlan.autoExposure(4);
                }
                hybridPlanHolder[0]=plan;
                Log.i("NICE_CAPTURE",plan.description);
                for(ImageFrame normal:mPendingZslNormalFrames) {
                    IsoExpoSelector.ExpoPair pair=new IsoExpoSelector.ExpoPair(normal.measuredExposure,normal.measuredExposure,normal.measuredExposure,
                            normal.measuredIso,normal.measuredIso,normal.measuredIso,normal.measuredIso);
                    IsoExpoSelector.fullpairs.add(pair);
                }
                // Focus and stabilisation as the preview that delivered the N frames (ArkCam / LMC 9.6: continuous_picture, no
                // trigger). The still template above switches to AF_MODE_AUTO with a CANCEL trigger, which moved the lens on
                // PHY110: every post-shutter frame came out ~26 % softer than the N frames at the same shutter, so the Shasta
                // gate dropped all bracketed frames and the ultrashort (Bento) highlights were soft.
                for(CaptureRequest.Key<Integer> key:java.util.Arrays.asList(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_CAPTURE_INTENT,CaptureRequest.NOISE_REDUCTION_MODE,CaptureRequest.EDGE_MODE,
                        CaptureRequest.HOT_PIXEL_MODE)) {
                    Integer v=mPreviewRequestBuilder.get(key);
                    if(v!=null) captureBuilder.set(key,v);
                }
                captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                long[] times=new long[plan.requests.size()];
                for(int i=0;i<plan.requests.size();i++) {
                    HybridPlan.Request r=plan.requests.get(i);
                    plan.apply(captureBuilder,i);
                    captureBuilder.setTag(new ImageFrame.NiceCaptureTag(mShutterGeneration,captures.size(),r.role));
                    CaptureRequest request=captureBuilder.build();captures.add(request);mCaptureRequest=request;
                    times[i]=r.shutterNs;
                    IsoExpoSelector.ExpoPair pair=new IsoExpoSelector.ExpoPair(r.shutterNs,r.shutterNs,r.shutterNs,r.iso,r.iso,r.iso,r.iso);
                    pair.isLongFrame=r.role==ImageFrame.CaptureRole.LONG;
                    pair.isHighlightFrame=r.role==ImageFrame.CaptureRole.SHORT||r.role==ImageFrame.CaptureRole.EXTRA_SHORT;
                    IsoExpoSelector.fullpairs.add(pair);
                }
                PhotonCamera.getGyro().PrepareGyroBurst(times,BurstShakiness);
            } else {
                IsoExpoSelector.fullpairs.clear();
                // L from the ZSL N frames: after the press only S and ES are exposed
                // (the 125 ms L frame cost ~0.25 s of shutter time).
                final boolean zslLong=hybridZsl && (PreferenceKeys.isNiceZslLong() || stockPlan.longBeyondSensor);
                // P27: in normal-back an L the sensor cannot expose is built from the N taken after the shutter (six requests).
                final boolean backLong=!hybridZsl && stockPlan.longBeyondSensor;
                if(zslLong) {
                    final float ratio=(float)Math.max(1,Math.min(64,stockPlan.longRatio()));
                    for(ImageFrame normal:mPendingZslNormalFrames)normal.syntheticLongRatio=ratio;
                    Log.i("NICE_CAPTURE","L from "+mPendingZslNormalFrames.size()+" ZSL N frames, ratio="+ratio);
                }
                normalBackLongRatio[0]=backLong?(float)Math.max(1,Math.min(64,stockPlan.longRatio())):0f;
                if(backLong)Log.i("NICE_CAPTURE","normal-back: L beyond the sensor, built from the N frames, ratio="+normalBackLongRatio[0]);
                long[] times=new long[hybridZsl?(zslLong?2:3):backLong?6:7];int timeIndex=0;
                for(int i=0;i<7;i++) {
                    long ns;int iso;
                    if(i==4 && (zslLong||backLong))continue;
                    if(i<4 && hybridZsl) {
                        ImageFrame normal=mPendingZslNormalFrames.get(i);
                        stockPlan.verifyZslNormal(normal.getMatchedCaptureMetadata(),niceZslShutterTimestamp);
                        ns=normal.measuredExposure;iso=normal.measuredIso;
                    } else {
                        stockPlan.apply(captureBuilder,i,mCameraCharacteristics);
                        captureBuilder.setTag(new ImageFrame.NiceCaptureTag(mShutterGeneration,captures.size(),stockPlan.role(i)));
                        CaptureRequest request=captureBuilder.build();captures.add(request);mCaptureRequest=request;
                        ns=stockPlan.shutter(i);iso=stockPlan.iso(i);times[timeIndex++]=ns;
                    }
                    IsoExpoSelector.ExpoPair pair=new IsoExpoSelector.ExpoPair(ns,ns,ns,iso,iso,iso,iso);
                    pair.isLongFrame=i==4;pair.isHighlightFrame=i>=5;IsoExpoSelector.fullpairs.add(pair);
                }
                PhotonCamera.getGyro().PrepareGyroBurst(times,BurstShakiness);
            }

            //img
            Log.d(TAG, "FrameCount:" + frameCount);
            mImageSaver = new ImageSaver(cameraEventsListener);
            final VivoNiceCaptureSequence niceSequence = hybridPlanHolder[0]!=null
                        ? (hybridZsl ? VivoNiceCaptureSequence.hybridZsl(captures,mPendingZslNormalFrames,niceZslShutterTimestamp)
                                     : VivoNiceCaptureSequence.hybridFuture(captures,niceZslShutterTimestamp))
                        : !hybridZsl
                        ? VivoNiceCaptureSequence.stockNormalBack(captures,niceZslShutterTimestamp)
                        : VivoNiceCaptureSequence.stockZsl(captures,mPendingZslNormalFrames,niceZslShutterTimestamp);
            // Buffered RAWs are already present; only submitted requests consume reader slots.
            mImageSaver.setFrameCount(niceSequence != null ? captures.size() : frameCount);
            // P30: the Hybrid's RAWs (ring and post-shutter) are copied straight into one memfd the worker maps (ShotArena);
            // without it (no memfd, binning, packed RAW) they are copied into native buffers as before.
            com.particlesdevs.photoncamera.util.ShotArena arenaForShot = null;
            if (lmcHybridShot && hybridZsl && !PhotonCamera.getSettings().binning) {
                int frameBytes = 0;
                for (ImageFrame f : mPendingZslNormalFrames) frameBytes = Math.max(frameBytes, f.pendingBytes());
                if (frameBytes > 0) arenaForShot = com.particlesdevs.photoncamera.util.ShotArena.create(mPendingZslNormalFrames.size() + captures.size(), frameBytes);
                if (arenaForShot != null) {
                    for (ImageFrame f : mPendingZslNormalFrames) f.arena = arenaForShot;
                    mImageSaver.shotArena = arenaForShot;
                    mShotArena = arenaForShot;
                    Log.i("NICE_CAPTURE", "RAWs of the shot into " + arenaForShot);
                }
            }
            final com.particlesdevs.photoncamera.util.ShotArena shotArena = arenaForShot;
            if (hybridZsl) {
                mImageSaver.setImageFormat(mTargetFormat);
                SaverImplementation.IMAGE_BUFFER.addAll(mPendingZslNormalFrames);
                mImageSaver.implementation.frameCount = frameCount;
            }
//            final int[] burstcount = {0, 0, frameCount};
            /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mImageReaderRaw.discardFreeBuffers();
            }*/
            Log.d(TAG, "CaptureStarted!");

            final Surface niceRawSurface = niceSequence != null ? mImageReaderRaw.getSurface() : null;
            final long[] baseFrameNumber = {0};
            final int[] maxFrameCount = {hybridZsl ? captures.size() : frameCount};
            final int zslNormalCount=hybridZsl ? mPendingZslNormalFrames.size() : 0;
            final boolean niceNormalBack=niceSequence!=null && !hybridZsl;
            // P27: one completion per shot (sequence completed, aborted, rescued after a camera close or a stall); callbacks of a
            // completed shot are ignored, the shot's own saver is used even if the field was replaced.
            final java.util.concurrent.atomic.AtomicBoolean shotDone=new java.util.concurrent.atomic.AtomicBoolean();
            // P27 (M6): request indices the HAL failed or lost the RAW of, and whether the queue was flushed (FlushLossStats).
            final java.util.Set<Integer> halLost=java.util.concurrent.ConcurrentHashMap.newKeySet();
            final java.util.concurrent.atomic.AtomicBoolean shotFlushed=new java.util.concurrent.atomic.AtomicBoolean();
            final FlushLossStats flushStats=FlushLossStats.of(String.valueOf(physicalID));
            final ImageSaver shotSaver=mImageSaver;
            final boolean shotHybridRoute=lmcHybridShot;
            final int nativeBaseIndex=denoiseFrameCount/2;
            final TotalCaptureResult[] nativeBaseResult={hybridZsl?mNativeZslBase:null};

            // ZSL SCAM HDR: the N frames are already buffered and only the short tail is exposed, so the UI
            // treats the press as an instant shot (no capture ring, no locked controls, provisional thumbnail).
            cameraEventsListener.onCaptureStillPictureStarted(hybridZsl ? "NiceZslCaptureStarted" : "CaptureStarted!");
            mMeasuredFrameCnt = 0;

            cameraEventsListener.onBurstPrepared(null);
            this.CaptureCallback = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                             @NonNull CaptureRequest request,
                                             long timestamp,
                                             long frameNumber) {
                    if (shotDone.get()) return;
                    if (mNativeRawPslCapture || mNiceRouted || (mLiveRawSession && !isZslMode())) mLiveRawRouter.request(timestamp, true);
                    if (mNiceRouted) mNiceTailTimestamp = Math.max(mNiceTailTimestamp, timestamp);
                    if (sTimelineSubmitNs > 0) Log.i("NICE_TIMELINE", "bracket start frame=" + frameNumber + " dtMs=" + (timestamp - sTimelineSubmitNs) / 1_000_000
                            + " nowMs=" + (android.os.SystemClock.elapsedRealtimeNanos() - sTimelineSubmitNs) / 1_000_000);

                    if (baseFrameNumber[0] == 0) {
                        baseFrameNumber[0] = frameNumber;
                        if (maxFrameCount[0] != -1) PhotonCamera.getGyro().CaptureGyroBurst();
                        Log.v("BurstCounter", "CaptureStarted with FirstFrameNumber:" + frameNumber);
                    } else {
                        Log.v("BurstCounter", "CaptureStarted:" + frameNumber);
                    }
                    cameraEventsListener.onFrameCaptureStarted(null);
                    //if (maxFrameCount[0] != -1) PhotonCamera.getGyro().CaptureGyroBurst();
                }

                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                                @NonNull CaptureResult partialResult) {
                    if (shotDone.get()) return;
                    int frameCount = (int) (partialResult.getFrameNumber() - baseFrameNumber[0]);
                    Log.v("BurstCounter", "CaptureProgressed! FrameCount:" + frameCount);
                    if (mCaptureResult == null) {
                        mCaptureResult = partialResult;
                    }
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {

                    if (shotDone.get()) return;
                    int frameCount = (int) (result.getFrameNumber() - baseFrameNumber[0]);
                    final ExposureLimits shotLimits = mExposureLimits;
                    if (shotLimits != null) shotLimits.observeManual(request, result);
                    if(niceSequence != null) {
                        // P27: a frame never costs the shot for its exposure. The Hybrid uses it in the role its measured
                        // exposure gives (HybridPlan.classify); SCAM HDR keeps it with its measured exposure (VivoNiceBurst
                        // slots by measurement, else the Hybrid merges the burst).
                        if(hybridPlanHolder[0]!=null) {
                            ImageFrame.CaptureRole actual=hybridPlanHolder[0].classify(request,result);
                            if(actual==null)niceSequence.lost(request,"exposure outside every hybrid role");
                            else niceSequence.reclassify(request,actual);
                        } else if(stockPlan!=null)try{stockPlan.verify(request,result);}
                        catch(RuntimeException mismatch){Log.w("NICE_CAPTURE",mismatch.getMessage()+"; kept with its measured exposure");}
                        niceSequence.completed(request, result);
                    }
                    if(niceCapture) VivoNiceCaptureLog.result(request,result,niceZslShutterTimestamp);
                    Log.v("BurstCounter", "CaptureCompleted! FrameCount:" + frameCount);
                    com.particlesdevs.photoncamera.util.ScameraDebugLog.frame(frameCount, result);
                    com.particlesdevs.photoncamera.util.ScameraDebugLog.remosaicMetadata(
                            session.getDevice().getId() + "/" + physicalID,
                            mCameraCharacteristics, result);
                    rememberRawMetadata(result);
                    Object time = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    Log.d(TAG, "Timestamp:" + time);
                    if (time != null) {
                        // get exposure multiply ISO and exposure time
                        Object isoKey = result.get(CaptureResult.SENSOR_SENSITIVITY);
                        int iso = 100;
                        if (isoKey != null) {
                            iso = (int) isoKey;
                        }
                        Object timeKey = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                        if (timeKey != null) {
                            long actualTime = (long) timeKey;
                            double exposureTime = ExposureIndex.time2sec(actualTime);
                            mExposures.put((long) time, exposureTime * iso);
                            Long requestedTime = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
                            Integer requestedIso = request.get(CaptureRequest.SENSOR_SENSITIVITY);
                            Log.i(TAG, "HDR frame " + frameCount + " request="
                                    + requestedTime + "ns ISO" + requestedIso
                                    + " actual=" + actualTime + "ns ISO" + iso);
                        }
                    }
                    cameraEventsListener.onFrameCaptureCompleted(
                            new TimerFrameCountViewModel.FrameCntTime(hybridZsl?mPendingZslNormalFrames.size()+frameCount:frameCount,
                                    hybridZsl?mPendingZslNormalFrames.size()+maxFrameCount[0]:maxFrameCount[0], frametime));

                    if (onUnlimited && !unlimitedStarted) {
                        mImageSaver.processStart(mCameraCharacteristics, result, request, cameraRotation);
                        unlimitedStarted = true;
                    }
                    //if(frameCount == 0)
                        mCaptureResult = result;
                    if (maxFrameCount[0] != -1) PhotonCamera.getGyro().CaptureGyroBurst();
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull android.hardware.camera2.CaptureFailure failure) {
                    if (shotDone.get()) return;
                    Log.w("NICE_CAPTURE", "capture failed: " + request.getTag() + " reason=" + failure.getReason()
                            + " imageCaptured=" + failure.wasImageCaptured() + " frame=" + failure.getFrameNumber());
                    if (niceSequence != null) niceSequence.lost(request, "HAL capture failure=" + failure.getReason());
                    if (request.getTag() instanceof ImageFrame.NiceCaptureTag) halLost.add(((ImageFrame.NiceCaptureTag) request.getTag()).index);
                }

                @Override
                public void onCaptureBufferLost(@NonNull CameraCaptureSession session,
                                                @NonNull CaptureRequest request,
                                                @NonNull Surface target, long frameNumber) {
                    if (shotDone.get()) return;
                    Log.w("NICE_CAPTURE", "buffer lost: " + request.getTag() + " frame=" + frameNumber + " raw=" + (target == niceRawSurface));
                    if (niceSequence != null && target == niceRawSurface)
                        niceSequence.lost(request, "RAW buffer lost frame=" + frameNumber);
                    if (target == niceRawSurface && request.getTag() instanceof ImageFrame.NiceCaptureTag)
                        halLost.add(((ImageFrame.NiceCaptureTag) request.getTag()).index);
                }

                @Override
                public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session, int sequenceId) {
                    if (shotDone.get()) return;
                    if (niceSequence != null) {
                        // P27: the frames that arrived (and the buffered N) still make the photo.
                        Log.w(TAG, "SHUTTER sequence aborted camera=" + physicalID + " sequence=" + sequenceId + ": merging the frames that arrived");
                        niceSequence.abandonOutstanding("sequence aborted");
                        onCaptureSequenceCompleted(session, sequenceId, -1);
                        return;
                    }
                    if (session != mCaptureSession) return;
                    if (!shotDone.compareAndSet(false, true)) return;
                    mNativeRawPslCapture=false;mZslCapturing=false;mShotInProgress=false;mNiceRouted=false;
                    mNiceQueuedShots=0;mLiveRawRouter.clear();burst=false;
                    for(ImageFrame frame:mPendingZslNormalFrames)frame.close();mPendingZslNormalFrames=new ArrayList<>();
                    mHybridZslCapture=false;
                    Log.w(TAG, "SHUTTER sequence aborted camera=" + physicalID + " sequence=" + sequenceId);
                    cameraEventsListener.onCaptureSequenceCompleted(null);
                    cameraEventsListener.onProcessingError(Lang.t("Серия RAW прервана камерой. Повторите снимок.", "The camera interrupted the RAW burst. Take the shot again."));
                    unlockFocus();
                }

                @Override
                public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session,
                                                       int sequenceId,
                                                       long lastFrameNumber) {
                    if (!shotDone.compareAndSet(false, true)) return;
                    mInFlightRescue = null;
                    if (niceSequence != null && niceSequence.futureCount > 0)
                        Log.i("NICE_CAPTURE", flushStats.record(shotFlushed.get(), niceSequence.futureCount, halLost));
                    final int finalFrameCount = niceSequence != null ? niceSequence.futureCount
                            : (int) (lastFrameNumber - baseFrameNumber[0]) + 1;
                    Log.v("BurstCounter", "CaptureSequenceCompleted! FrameCount:" + finalFrameCount);
                    Log.d("DefaultSaver", "CaptureSequenceCompleted! FrameCount:" + finalFrameCount);
                    Log.v("BurstCounter", "CaptureSequenceCompleted! LastFrameNumber:" + lastFrameNumber);
                    Log.d(TAG, "SequenceCompleted");
                    mMeasuredFrameCnt = finalFrameCount;
                    cameraEventsListener.onCaptureSequenceCompleted(null);
                    burst = false;
                    //unlockFocus();
                    //Surface texture related
                    //activity.runOnUiThread(() -> UpdateCameraCharacteristics(PhotonCamera.getSettings().mCameraID));
                    if (PhotonCamera.getSettings().selectedMode != CameraMode.UNLIMITED && PhotonCamera.getSettings().selectedMode != CameraMode.RAWVIDEO) {
                        //processExecutor.submit(() -> mImageSaver.runRaw(mCameraCharacteristics, mCaptureResult, new ArrayList<>(BurstShakiness), cameraRotation));
                        /*taskResults.removeIf(Future::isDone); //remove already completed results
                        Future<?> result =processExecutor.submit(() -> {
                            while (PhotonCamera.getGyro().capturingNumber < finalFrameCount){
                                try {
                                    Thread.sleep(1);
                                } catch (InterruptedException ignored) {
                                }
                            }
                            if (maxFrameCount[0] != -1) PhotonCamera.getGyro().CompleteGyroBurst();
                            mImageSaver.runRaw(mCameraCharacteristics, mCaptureResult, new ArrayList<>(BurstShakiness), cameraRotation);
                        });
                        //Future<?> result = processExecutor.submit(() -> mImageSaver.runRaw(mCameraCharacteristics, mCaptureResult, new ArrayList<>(BurstShakiness), cameraRotation));
                        taskResults.add(result);*/
                        processExecutor.execute(() -> {
                            int cnt = 0;
                            //int captureNumber = PhotonCamera.getGyro().capturingNumber;
                            while (PhotonCamera.getGyro().capturingNumber < finalFrameCount - (niceSequence != null ? niceSequence.droppedCount() : 0)
                                    || shotSaver.bufferSize() < zslNormalCount + finalFrameCount - (niceSequence != null ? niceSequence.droppedCount() : 0)){
                                if(cnt > 1000) {
                                    Log.d(TAG, "GyroBurstTimeout");
                                    break;
                                }
                                try {
                                    Thread.sleep(1);
                                } catch (InterruptedException ignored) {
                                }
                                //if(captureNumber - PhotonCamera.getGyro().capturingNumber != 0)
                                //    cnt = 0;
                                //else
                                    cnt++;
                            }
                            PhotonCamera.getGyro().CompleteSequence();
                            awaitZslCopy();
                            // P27: a ZSL frame whose copy did not finish is copied now; one that cannot be copied is dropped.
                            for (ImageFrame zsl : new ArrayList<>(mPendingZslNormalFrames)) {
                                try { zsl.materialize(); } catch (RuntimeException e) { Log.w("NICE_CAPTURE", "ZSL frame " + zsl.timestamp + " not copied: " + e); }
                            }
                            final boolean routedNice = mNiceRouted;
                            if (!routedNice) mBackgroundHandler.post(() -> {
                                if (!isDualSession)
                                    unlockFocus();
                                else
                                    createCameraPreviewSession(false);
                            });
                            try{
                            if (niceSequence != null) {
                                java.util.List<ImageFrame> unmatched = niceSequence.bindAndValidate(shotSaver.snapshotFrames());
                                if (!unmatched.isEmpty()) shotSaver.removeFrames(unmatched);
                                String summary = niceSequence.droppedSummary();
                                if (!summary.isEmpty())
                                    Log.w("NICE_CAPTURE", (shotHybridRoute ? "hybrid" : "SCAM HDR") + ": " + niceSequence.droppedCount() + " of "
                                            + niceSequence.futureCount + " post-shutter frames dropped, merging the rest: " + summary);
                                Log.i("NICE_CAPTURE", "complete matched RAWs=" + shotSaver.bufferSize()
                                        + " futureRequests=" + niceSequence.futureCount + " bound=" + niceSequence.boundFutureCount()
                                        + " stockAePlan=" + (stockPlan!=null));
                                // Normal-back (no buffered N): the base metadata is the first N frame that arrived, else any frame.
                                if (niceNormalBack) {
                                    CaptureResult first = niceSequence.firstBoundResult(ImageFrame.CaptureRole.NORMAL);
                                    if (first == null) first = niceSequence.firstBoundResult(null);
                                    if (first instanceof TotalCaptureResult) nativeBaseResult[0] = (TotalCaptureResult) first;
                                }
                                if (normalBackLongRatio[0] > 0)
                                    for (ImageFrame frame : shotSaver.snapshotFrames())
                                        if (frame.getCaptureRole() == ImageFrame.CaptureRole.NORMAL) frame.syntheticLongRatio = normalBackLongRatio[0];
                            }
                            if(shotSaver.bufferSize() == 0){
                                cameraEventsListener.onProcessingError(Lang.t("Камера не передала RAW-кадры. Повторите снимок.", "The camera delivered no RAW frames. Take the shot again."));
                                return;
                            }
                            shotSaver.updateFrameCount(shotSaver.bufferSize());
                            if (shotSaver.bufferSize() != 0) {
                                boolean useZslBase = niceZslRequested && (hybridZsl || niceNormalBack);
                                CaptureResult baseMetadata = useZslBase ? nativeBaseResult[0] : mCaptureResult;
                                if (baseMetadata == null) {
                                    // P27: never without base metadata while a frame exists: the newest frame's own result.
                                    for (ImageFrame frame : shotSaver.snapshotFrames()) {
                                        CaptureResult own = frame.getMatchedCaptureMetadata();
                                        Long ts = own == null ? null : own.get(CaptureResult.SENSOR_TIMESTAMP);
                                        Long best = baseMetadata == null ? null : baseMetadata.get(CaptureResult.SENSOR_TIMESTAMP);
                                        if (own != null && (best == null || (ts != null && ts > best))) baseMetadata = own;
                                    }
                                    Log.w("NICE_CAPTURE", "base metadata from the newest frame of the burst");
                                }
                                if (baseMetadata == null) throw new IllegalStateException(Lang.t("RAW: отсутствуют метаданные основного кадра", "RAW: base frame metadata missing"));
                                if (niceSequence != null && shotSaver.bufferSize() != niceSequence.presentCount())
                                    Log.w("NICE_CAPTURE", "RAWs in the saver " + shotSaver.bufferSize() + ", in the series " + niceSequence.presentCount());
                                final ImageSaver saver = shotSaver;
                                final CameraCharacteristics shotCharacteristics = mCameraCharacteristics;
                                final CaptureResult shotResult = baseMetadata;
                                final CaptureRequest shotRequest = baseMetadata.getRequest() != null ? baseMetadata.getRequest() : mCaptureRequest;
                                final ArrayList<GyroBurst> shotShakiness = new ArrayList<>(BurstShakiness);
                                final HashMap<Long, Double> shotExposures = mExposures;
                                if (niceSequence != null) {
                                    saver.detachForQueue(shotHybridRoute);
                                    sNicePending.incrementAndGet();
                                    Log.i("NICE_CAPTURE", "queued for processing pending=" + sNicePending.get());
                                    NICE_PROCESSING.execute(() -> {
                                        try {
                                            saver.runRaw(shotCharacteristics, shotResult, shotRequest,
                                                    shotShakiness, cameraRotation, shotExposures);
                                        } catch (Exception e) {
                                            Log.e(TAG, "runRaw:" + Log.getStackTraceString(e));
                                            cameraEventsListener.onProcessingError(e.getLocalizedMessage());
                                        } finally {
                                            sNicePending.decrementAndGet();
                                        }
                                    });
                                } else saver.runRaw(shotCharacteristics, shotResult, shotRequest,
                                        shotShakiness, cameraRotation, shotExposures);
                            }
                            } catch (Exception e){
                                Log.e(TAG, "runRaw:"+Log.getStackTraceString(e));
                                cameraEventsListener.onProcessingError(e.getLocalizedMessage());
                                if (niceSequence != null) {
                                    shotSaver.discardFrames();
                                }
                            } finally {
                                // P30: no more frames for this shot's arena (it goes once the processing frees them)
                                if (shotArena != null) { shotArena.release(); if (mShotArena == shotArena) mShotArena = null; }
                                mNiceRouted = false;
                                mNativeRawPslCapture=false;mZslCapturing=false;mLiveRawRouter.clear();
                                if (hybridZslRequested) {
                                    mHybridZslCapture = false;
                                    mZslCapturing = false;
                                    mPendingZslNormalFrames = new ArrayList<>();
                                }
                                // Preview never stopped: no AF re-trigger or 3A reset, the
                                // shutter is free as soon as the tail RAWs are in memory.
                                if (routedNice) mBackgroundHandler.post(CaptureController.this::finishNiceShot);
                            }
                        });
                        /*mBackgroundHandler.post(() -> {
                                    while (PhotonCamera.getGyro().capturingNumber < finalFrameCount){
                                        try {
                                            Thread.sleep(1);
                                        } catch (InterruptedException ignored) {
                                        }
                                    }
                                    if (maxFrameCount[0] != -1) PhotonCamera.getGyro().CompleteGyroBurst();
                                    mImageSaver.runRaw(mCameraCharacteristics, mCaptureResult, new ArrayList<>(BurstShakiness), cameraRotation);
                                });*/
                        //mBackgroundHandler.post(() -> {mImageSaver.runRaw(mCameraCharacteristics, mCaptureResult, new ArrayList<>(BurstShakiness), cameraRotation);});
                    }
                }
            };
            //mCaptureSession.setRepeatingBurst(captures, CaptureCallback, null);
            burst = true;
            Camera2ApiAutoFix.ApplyBurst();
            if (isDualSession) {
                createCameraPreviewSession(true);
                startZslCopy(mPendingZslNormalFrames);
            } else {
            if (hybridZslRequested) {
                // NICE uses capture-start timestamps to reject any late
                // preview RAWs; other hybrid paths retain their flushed routing.
                mHybridZslCapture = true;
            }
            if (!mNiceRouted) mCaptureSession.stopRepeating();
            if(niceCapture) {
                Log.i("NICE_CAPTURE","submit zslNormals="+mPendingZslNormalFrames.size()
                        +" futureRequests="+captures.size()+" hybrid="+hybridZsl
                        +" stockAePlan="+(stockPlan!=null)+" previewRunning="+mNiceRouted);
                for(int i=0;i<captures.size();i++) VivoNiceCaptureLog.request(captures.get(i),i);
            }
            if (!niceZslRequested) mCaptureSession.abortCaptures();
            // NICE keeps the preview repeating; its in-flight requests (~12 frames on
            // this HAL) otherwise sit ahead of the bracket. Flushing them brings the
            // first bracket exposure ~0.25 s closer to the press; the preview is
            // restarted right behind the burst.
            final boolean flushWanted = mNiceRouted && niceSequence != null && PreferenceKeys.isNiceFastCapture()
                    && !captures.isEmpty() && (mStockObserverReady || !PreferenceKeys.useStockBracketPlanner());
            // P27 (M6): a camera whose HAL lost the first requests after the flush twice in a row is not flushed any more.
            final boolean flushQueue = flushWanted && !flushStats.skipFlush();
            if (flushWanted && !flushQueue) Log.i("NICE_CAPTURE", "HAL queue not flushed: camera " + flushStats.camera
                    + " lost the first requests after a flush (FlushLossStats)");
            shotFlushed.set(flushQueue);
            sTimelineSubmitNs = android.os.SystemClock.elapsedRealtimeNanos();
            if (flushQueue) {
                long t0 = android.os.SystemClock.elapsedRealtime();
                // Without stopping the repeating preview first, the HAL sometimes
                // returns from flush at once without dropping anything (+0.17 s).
                flushDevice(mCaptureSession);
                Log.i("NICE_CAPTURE", "HAL queue flushed in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
            }
            if(captures.isEmpty() && hybridZsl) {
                startZslCopy(mPendingZslNormalFrames);
                CaptureCallback.onCaptureSequenceCompleted(mCaptureSession,0,-1);return;
            }
            // P27: from here the shot completes with what arrived whatever happens: a camera close / error, an abort, a
            // submit failure or a HAL that never reports the series (the watchdog; 65 s HAL stalls were seen).
            if (niceSequence != null) {
                final CameraCaptureSession.CaptureCallback shotCallback = CaptureCallback;
                final CameraCaptureSession shotSession = mCaptureSession;
                final Runnable rescue = () -> {
                    if (shotDone.get()) return;
                    niceSequence.abandonOutstanding("camera stopped delivering the series");
                    for (ImageFrame zsl : new ArrayList<>(mPendingZslNormalFrames)) {
                        try { zsl.materialize(); } catch (RuntimeException e) { Log.w("NICE_CAPTURE", "ZSL frame " + zsl.timestamp + " not copied: " + e); }
                    }
                    shotCallback.onCaptureSequenceCompleted(shotSession, -1, -1);
                };
                mInFlightRescue = rescue;
                long plannedNs = 0;
                for (CaptureRequest request : captures) {
                    Long t = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
                    plannedNs += t == null || t <= 0 ? 33_000_000L : t;
                }
                final long watchdogMs = 4000 + 3 * plannedNs / 1_000_000;
                if (mBackgroundHandler != null) mBackgroundHandler.postDelayed(() -> {
                    if (shotDone.get() || mInFlightRescue != rescue) return;
                    Log.w("NICE_CAPTURE", "series not completed after " + watchdogMs + " ms: aborting it, merging the frames that arrived");
                    try { if (shotSession != null) shotSession.abortCaptures(); } catch (CameraAccessException | RuntimeException ignored) {}
                    rescueInFlightShot("watchdog " + watchdogMs + " ms");
                }, watchdogMs);
            }
                switch (PhotonCamera.getSettings().selectedMode) {
                    case UNLIMITED:
                        mCaptureSession.setRepeatingBurst(captures, CaptureCallback, mBackgroundHandler);
                        break;
                    case RAWVIDEO:
                        mCaptureSession.setRepeatingRequest(captures.get(0), CaptureCallback, mBackgroundHandler);
                        break;
                    case NIGHT:
                    case PHOTO:
                    case MOTION:
                        mCaptureSession.captureBurst(captures, CaptureCallback, mBackgroundHandler);
                        if (mNiceRouted) queueNiceAeRestore();
                        if (flushQueue) mCaptureSession.setRepeatingRequest(
                                mPreviewInputRequest = mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
                        break;
                }
                startZslCopy(mPendingZslNormalFrames);
            }
        } catch (CameraAccessException | RuntimeException e) {
            // P27: the series failed to submit after the shot was set up (session closed, device disconnecting): the
            // buffered N frames still make the photo.
            if (mInFlightRescue != null) {
                Log.w("NICE_CAPTURE", "series submit failed (" + e + "): merging the buffered frames");
                rescueInFlightShot("submit failed: " + e.getMessage());
                return;
            }
            mNativeRawPslCapture=false;mZslCapturing=false;mShotInProgress=false;mNiceRingFrozen=false;
            mNiceRouted=false;mNiceQueuedShots=0;mLiveRawRouter.clear();
            if (mShotArena != null) { mShotArena.release(); mShotArena = null; }
            for(ImageFrame frame:mPendingZslNormalFrames)frame.close();mPendingZslNormalFrames=new ArrayList<>();
                mHybridZslCapture=false;burst=false;
            cameraEventsListener.onCaptureSequenceCompleted(null);
            cameraEventsListener.onProcessingError(e.getMessage());
            unlockFocus();
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    /**
     * Some physical cameras publish a sensitivity range narrower than the sensor
     * runs: the vivo X200 Ultra 2.4x tele declares ISO 400..800 while its own AE
     * runs at ISO 50 in daylight and 6400 at night. Manual bracket frames were
     * clamped to 800, so at night the long frame could not reach N and the shot
     * failed. The range is widened to what the camera itself has delivered.
     */
    /**
     * Drops the in-flight preview requests ahead of the bracket. The idle
     * notification of the previous flush arrives after the preview is restarted,
     * so Camera2 believed the running device idle and skipped every second flush
     * (session: "already aborting"; device: early return), and the bracket waited
     * behind ~12 queued preview frames (+0.17 s). The stale idle flag is cleared
     * under the device lock first; abortCaptures() is the fallback.
     */
    private static void flushDevice(CameraCaptureSession session) throws CameraAccessException {
        try {
            java.lang.reflect.Field field = session.getClass().getDeclaredField("mDeviceImpl");
            field.setAccessible(true);
            Object device = field.get(session);
            Class<?> type = device.getClass();
            java.lang.reflect.Field lockField = type.getDeclaredField("mInterfaceLock");
            java.lang.reflect.Field idleField = type.getDeclaredField("mIdle");
            lockField.setAccessible(true);
            idleField.setAccessible(true);
            java.lang.reflect.Method flush = type.getMethod("flush");
            synchronized (lockField.get(device)) {
                idleField.setBoolean(device, false);
                flush.invoke(device);
            }
            return;
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof CameraAccessException) throw (CameraAccessException) e.getCause();
            Log.w("NICE_CAPTURE", "device flush failed, session abort: " + e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w("NICE_CAPTURE", "device flush unavailable, session abort: " + e);
        }
        session.stopRepeating();
        session.abortCaptures();
    }

    private void widenSensitivityRange(int iso) {
        CameraCharacteristics c = mCameraCharacteristics;
        if (c == null || iso <= 0) return;
        android.util.Range<Integer> r = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (r == null || (iso >= r.getLower() && iso <= r.getUpper())) return;
        android.util.Range<Integer> wider = new android.util.Range<>(Math.min(r.getLower(), iso), Math.max(r.getUpper(), iso));
        try {
            com.particlesdevs.photoncamera.api.CameraReflectionApi.set(c, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE, wider);
            Log.i("NICE_CAPTURE", "sensitivity range camera=" + physicalID + " " + r + " -> " + wider + " (observed ISO " + iso + ")");
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot widen sensitivity range: " + e);
        }
    }

    /** Copies deferred ZSL frames out of the RAW reader while the bracket exposes. */
    private void startZslCopy(List<ImageFrame> frames) {
        final List<ImageFrame> pending = new ArrayList<>(frames);
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        mZslCopyDone = done;
        if (pending.isEmpty()) { done.countDown(); return; }
        // Reads from camera buffers are slow per core (~2 GB/s, uncached) and the
        // previous shot's processing competes for them: copy on four threads.
        final int workers = Math.min(4, pending.size());
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger(pending.size());
        final java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger(workers);
        final long t0 = android.os.SystemClock.elapsedRealtime();
        for (int w = 0; w < workers; w++) {
            Thread copy = new Thread(() -> {
                try {
                    // Newest first: the reference frame is ready earliest.
                    for (int i = next.decrementAndGet(); i >= 0; i = next.decrementAndGet()) pending.get(i).materialize();
                } catch (RuntimeException e) {
                    Log.e(TAG, "ZSL copy failed: " + Log.getStackTraceString(e));
                } finally {
                    if (running.decrementAndGet() == 0) {
                        done.countDown();
                        Log.i("NICE_CAPTURE", "ZSL copy " + pending.size() + " RAWs in "
                                + (android.os.SystemClock.elapsedRealtime() - t0) + " ms (after submit)");
                    }
                }
            }, "zsl-copy-" + w);
            copy.setPriority(Thread.MAX_PRIORITY);
            copy.start();
        }
    }

    private void awaitZslCopy() {
        java.util.concurrent.CountDownLatch done = mZslCopyDone;
        if (done == null) return;
        try {
            if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) Log.w(TAG, "ZSL copy timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void abortCaptures() {
        if (mCaptureSession == null) {
            return;
        }
        try {
            mCaptureSession.abortCaptures();
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    public void reset3Aparams() {
        setAEMode(mPreviewRequestBuilder, PreferenceKeys.getAeMode());
        setAFMode(mPreviewRequestBuilder, PreferenceKeys.getAfMode());
        rebuildPreviewBuilder();
    }

    public void setPreviewAEModeRebuild(int aeMode) {
        setAEMode(mPreviewRequestBuilder, aeMode);
        rebuildPreviewBuilder();
    }

    public void applyFpsRange() {
        if (mPreviewRequestBuilder == null) return;
        PhotonCamera.getSettings().fpsMode = PreferenceKeys.getFpsMode();
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, getSelectedFpsRange());
        rebuildPreviewBuilder();
    }

    public void applyAeMetering() {
        if (mPreviewRequestBuilder == null) return;
        applyAeMeteringRegions(mPreviewRequestBuilder);
        rebuildPreviewBuilder();
    }

    private void applyAeMeteringRegions(CaptureRequest.Builder builder) {
        int mode = PreferenceKeys.getAeMeteringStd();
        Log.d(TAG, "applyAeMeteringRegions mode:" + mode);
        MeteringRectangle[] rectangles = getAEMeteringRectangles(mode);
        if (mode == -1) {
            rectangles = mInitialMeteringAE;
        }
        Integer maxAeRegions = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        if (maxAeRegions != null && maxAeRegions > 0) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, rectangles);
            if (builder == mPreviewRequestBuilder) {
                mPreviewMeteringAE = rectangles;
            }
        }
    }

    private MeteringRectangle[] getAEMeteringRectangles(int mode) {
        Rect activeArray = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (activeArray == null) return null;

        int width = activeArray.width();
        int height = activeArray.height();

        switch (mode) {
            case 0: // Center Weighted
                Integer maxRegionsObj = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
                int maxRegions = maxRegionsObj != null ? maxRegionsObj : 0;
                if (maxRegions >= 3) {
                    // Concentric overlapping rectangles
                    // 1. Large Base: 70%, Weight: 200
                    int w1 = (int) (width * 0.70);
                    int h1 = (int) (height * 0.70);
                    int x1 = (width - w1) / 2;
                    int y1 = (height - h1) / 2;

                    // 2. Medium Core: 45%, Weight: 300
                    int w2 = (int) (width * 0.45);
                    int h2 = (int) (height * 0.45);
                    int x2 = (width - w2) / 2;
                    int y2 = (height - h2) / 2;

                    // 3. Small Center: 20%, Weight: 500
                    int w3 = (int) (width * 0.20);
                    int h3 = (int) (height * 0.20);
                    int x3 = (width - w3) / 2;
                    int y3 = (height - h3) / 2;

                    return new MeteringRectangle[]{
                            new MeteringRectangle(x1, y1, w1, h1, 200),
                            new MeteringRectangle(x2, y2, w2, h2, 300),
                            new MeteringRectangle(x3, y3, w3, h3, 500)
                    };
                } else {
                    // Fallback: single large center rectangle (60%)
                    int cwWidth = (int) (width * 0.60);
                    int cwHeight = (int) (height * 0.60);
                    int cwX = (width - cwWidth) / 2;
                    int cwY = (height - cwHeight) / 2;
                    return new MeteringRectangle[]{new MeteringRectangle(cwX, cwY, cwWidth, cwHeight, MeteringRectangle.METERING_WEIGHT_MAX)};
                }
            case 1: // Frame Average
                // Full active array
                return new MeteringRectangle[]{new MeteringRectangle(0, 0, width, height, MeteringRectangle.METERING_WEIGHT_MAX)};
            case 2: // Spot Metering
                // Approximately 2.5% of the sensor area (sqrt(0.025) ≈ 0.158)
                int sWidth = (int) (width * 0.158);
                int sHeight = (int) (height * 0.158);
                int sX = (width - sWidth) / 2;
                int sY = (height - sHeight) / 2;
                return new MeteringRectangle[]{new MeteringRectangle(sX, sY, sWidth, sHeight, MeteringRectangle.METERING_WEIGHT_MAX)};
            default:
                return null;
        }
    }

    public void resetPreviewAEMode() {
        setAEMode(mPreviewRequestBuilder, PreferenceKeys.getAeMode());
    }

    /**
     * @param requestBuilder CaptureRequest.Builder
     * @param aeMode         possible values = 0, 1, 2, 3
     */
    private void setAEMode(CaptureRequest.Builder requestBuilder, int aeMode) {
        if (requestBuilder != null) {
            if (mFlashSupported) {
                requestBuilder.set(CONTROL_AE_MODE, Math.max(aeMode, 1));//here AE_MODE will never be OFF(0)

                //if PreferenceKeys.getAeMode() returns zero, we set the FLASH_MODE_TORCH instead of setting AE_MODE to OFF(0)
                requestBuilder.set(CaptureRequest.FLASH_MODE,
                        aeMode == 0 ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            } else {
                requestBuilder.set(CONTROL_AE_MODE, CONTROL_AE_MODE_ON);
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            }
        }
    }

    private void setAFMode(CaptureRequest.Builder builder, int afMode) {
        if (builder != null) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, builder.get(CONTROL_AF_REGIONS));
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, builder.get(CONTROL_AE_REGIONS));
            builder.set(CaptureRequest.CONTROL_AF_MODE, afMode);
        }
    }

    /**
     * Start the timer for the pre-capture sequence.
     * <p/>
     * Call this only with { #mCameraStateLock} held.
     */
    private void startTimerLocked() {
        mCaptureTimer = SystemClock.elapsedRealtime();
    }

    /**
     * Check if the timer for the pre-capture sequence has been hit.
     * <p/>
     * Call this only with { #mCameraStateLock} held.
     *
     * @return true if the timeout occurred.
     */
    private boolean hitTimeoutLocked() {
        return (SystemClock.elapsedRealtime() - mCaptureTimer) > PRECAPTURE_TIMEOUT_MS;
    }

    public void callUnlimitedEnd() {
        onUnlimited = false;
        //mImageSaver.unlimitedEnd();
        mBackgroundHandler.post(() -> mImageSaver.processEnd());
        abortCaptures();
        createCameraPreviewSession(false);
        unlimitedStarted = false;
    }

    public void callUnlimitedStart() {
        onUnlimited = true;
        takePicture();
    }

    public void VideoEnd() {
        mIsRecordingVideo = false;
        stopRecordingVideo();
    }

    public void VideoStart() {
        mIsRecordingVideo = true;
        createCameraPreviewSession(false);
    }

    private CamcorderProfile resolveVideoProfile(int cameraId, String resolution) {
        int[] qualities;
        switch (resolution) {
            case "3840x2160": qualities = new int[]{CamcorderProfile.QUALITY_2160P, CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P}; break;
            case "1280x720":  qualities = new int[]{CamcorderProfile.QUALITY_720P,  CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_2160P}; break;
            default:          qualities = new int[]{CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_720P,  CamcorderProfile.QUALITY_2160P}; break;
        }
        for (int q : qualities) {
            if (CamcorderProfile.hasProfile(cameraId, q)) {
                return CamcorderProfile.get(cameraId, q);
            }
        }
        return CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH);
    }

    private void setUpMediaRecorder() {
        mMediaRecorder.reset();
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        String cameraIdStr = PhotonCamera.getSettings().mCameraID;
        if (cameraIdStr.contains("-")) cameraIdStr = cameraIdStr.split("-")[0];
        int cameraIdInt;
        try { cameraIdInt = Integer.parseInt(cameraIdStr); } catch (NumberFormatException e) { cameraIdInt = 0; }
        CamcorderProfile profile = resolveVideoProfile(cameraIdInt, PreferenceKeys.getVideoResolution());
        mMediaRecorder.setVideoFrameRate(profile.videoFrameRate);
        mMediaRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        mMediaRecorder.setVideoEncodingBitRate(profile.videoBitRate);
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mMediaRecorder.setAudioEncodingBitRate(profile.audioBitRate);
        mMediaRecorder.setAudioSamplingRate(profile.audioSampleRate);
        mMediaRecorder.setOnInfoListener(this);
        mMediaRecorder.setOrientationHint(PhotonCamera.getGravity().getCameraRotation(mSensorOrientation));
        Date currentDate = new Date();
        DateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        String dateText = dateFormat.format(currentDate);
        File dir = new File(Environment.getExternalStorageDirectory() + "//DCIM//Camera//");
        vid = new File(dir.getAbsolutePath(), "VID_" + dateText + ".mp4");
        try {
            vid.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
        mMediaRecorder.setOutputFile(vid.getAbsolutePath());
        try {
            mMediaRecorder.prepare();
            Log.d(TAG, "video record start");

        } catch (Exception e) {
            Log.d(TAG, "video record failed");
        }
    }

    private void stopRecordingVideo() {
        mIsRecordingVideo = false;

        try {
            mMediaRecorder.stop();
        } catch (Exception stopFailure) {
            Log.d(TAG, "Failed to stop recording " + Log.getStackTraceString(stopFailure));
            Toast.makeText(activity.getApplicationContext(), "Failed to stop recording", Toast.LENGTH_SHORT).show();
            if (vid.delete()) {
                Toast.makeText(activity.getApplicationContext(), "Video file has been removed", Toast.LENGTH_SHORT).show();
            }
        }
        mMediaRecorder.reset();
        cameraEventsListener.onRequestTriggerMediaScanner(Uri.fromFile(vid));
        createCameraPreviewSession(false);
    }

    @Override
    public void onInfo(MediaRecorder mr, int what, int extra) {
        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
            Log.v(TAG, "Maximum Duration Reached, Call stopRecordingVideo()");
            stopRecordingVideo();
        }
    }

    private void mul(Rect in, double k) {
        in.bottom *= k;
        in.left *= k;
        in.right *= k;
        in.top *= k;
    }

    @TestOnly
    private static void mulForTest(Rect in, double k) {
        in.bottom *= k;
        in.left *= k;
        in.right *= k;
        in.top *= k;
    }

    @Override
    protected void finalize() throws Throwable {
        activity = null;
        cameraEventsListener = null;
        mCameraManager = null;
        mTextureView = null;
        super.finalize();
    }
    public void resumeCamera() {
        isCameraResumed = true;
        if(PhotonCamera.getSettings().previewFormat != 0) {
            mPreviewTargetFormat = PhotonCamera.getSettings().previewFormat;
        } else {
            mPreviewTargetFormat = ImageFormat.JPEG;
        }
        processExecutor.execute(() -> {
            if (mTextureView == null)
                mTextureView = new GLPreview(activity);
            if (mTextureView.isAvailable()) {
                // The GL surface survived backgrounding (no onSurfaceCreated will
                // fire on resume), so open the camera directly against the
                // existing SurfaceTexture instead of waiting for a callback.
                Log.d(TAG,"ID:"+mCameraCharacteristicsMap.get(physicalID));
                Size optimal = getPreviewOutputSize(getSafeDisplay(),
                        mCameraCharacteristicsMap.get(physicalID),
                        PhotonCamera.getSettings().selectedMode);
                openCamera(optimal.getWidth(), optimal.getHeight());
            } else {
                mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
                // The availability callback is delivered through the main-thread
                // handler; it may have fired between the check above and arming
                // the listener. Re-check so the camera is never left waiting for
                // an event that already happened.
                if (mTextureView.isAvailable()) {
                    Size optimal = getPreviewOutputSize(getSafeDisplay(),
                            mCameraCharacteristicsMap.get(physicalID),
                            PhotonCamera.getSettings().selectedMode);
                    openCamera(optimal.getWidth(), optimal.getHeight());
                }
            }
        });
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    /**
     * Dynamically applies Optical Image Stabilization (OIS) configuration to a CaptureRequest.
     * Respects user preference (Default/Smart/Off) and physical hardware limitations.
     *
     * @param builder        the builder for which to configure OIS
     * @param isStillCapture true if configuring a still capture request, false for preview stream
     */
    private void applyOisMode(CaptureRequest.Builder builder, boolean isStillCapture) {
        int[] stabilizationModes = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (stabilizationModes != null && stabilizationModes.length > 1) {
            int oisMode = this.oisMode;
            if (oisMode == 2) {
                // Always Off
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            } else if (oisMode == 1) {
                // Smart Auto
                boolean isTripod = PhotonCamera.getGyro() != null && PhotonCamera.getGyro().getTripod();
                CameraMode mode = PhotonCamera.getSettings().selectedMode;
                boolean isContinuousCapture = (mode == CameraMode.UNLIMITED);

                if (isTripod || isContinuousCapture) {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                } else {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                }
            } else {
                // Default: Maintain 100% identical behavior with the original code.
                // For still captures, we explicitly force OIS ON.
                // For preview/video streams, we do NOT set the OIS key, letting the HAL default handle it.
                if (isStillCapture) {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                }
            }
        }
    }

    public static class CameraProperties {
        private final Float minFocal = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        private final Float maxFocal = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE);
        public Range<Float> focusRange = (!(minFocal == null || maxFocal == null || minFocal == 0.0f)) ? new Range<>(Math.min(minFocal, maxFocal), Math.max(minFocal, maxFocal)) : null;
        public Range<Integer> isoRange = new Range<>(IsoExpoSelector.getISOLOWExt(), IsoExpoSelector.getISOHIGHExt());
        public Range<Long> expRange = new Range<>(IsoExpoSelector.getEXPLOW(), IsoExpoSelector.getEXPHIGH());
        private final float evStep = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).floatValue();
        public Range<Float> evRange = new Range<>((mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE).getLower() * evStep),
                (mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE).getUpper() * evStep));

        public CameraProperties() {
            logIt();
        }

        private void logIt() {
            String lens = PhotonCamera.getSettings().mCameraID;
            Log.d(TAG, "focusRange(" + lens + ") : " + (focusRange == null ? "Fixed [" + maxFocal + "]" : focusRange.toString()));
            Log.d(TAG, "isoRange(" + lens + ") : " + isoRange.toString());
            Log.d(TAG, "expRange(" + lens + ") : " + expRange.toString());
            Log.d(TAG, "evCompRange(" + lens + ") : " + evRange.toString());
        }

    }
}
