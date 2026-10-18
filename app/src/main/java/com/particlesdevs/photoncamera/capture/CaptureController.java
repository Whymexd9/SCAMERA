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
import android.hardware.camera2.CaptureFailure;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.Camera2ApiAutoFix;
import com.particlesdevs.photoncamera.api.CameraEventsListener;
import com.particlesdevs.photoncamera.api.CameraManager2;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.api.CameraReflectionApi;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.circularbarlib.api.ManualModeConsole;
import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.control.TouchFocus;
import com.particlesdevs.photoncamera.manual.ParamController;
import com.particlesdevs.photoncamera.processing.ImageSaver;
import com.particlesdevs.photoncamera.processing.parameters.ExposureIndex;
import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector;
import com.particlesdevs.photoncamera.processing.parameters.ResolutionSolution;
import com.particlesdevs.photoncamera.processing.LiveRawFrame;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.processing.RingFrame;
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
    /**
     * Format of the extra ImageReader; its JPEG sizes also bound
     * the RAW size choice. Always JPEG since «Формат превью» (which only added an unread stream to the plain session) is gone.
     */
    public static final int mPreviewTargetFormat = ImageFormat.JPEG;

    @SensorConfig(title = "Session Type",
            defaultValue = 0, min = 0, max = 65535, step = 0,
            description = "Режим сессии камеры: 0 — обычный, 32768–65535 — режимы производителя (неверный может не открыть камеру). В FULLRES всегда 0. Применяется при следующем открытии камеры.")
    public int sessionType = 0;
    /**
     * P211 (owner's X7 Ultra, 2026-10-17/18: «Тип сессии камеры» 32777 with FULLRES on rebooted the phone - kernel panic «Unable to
     * handle kernel paging request», reproduced on PHY110): the operation mode a session runs. The module's «Тип сессии камеры»
     * only as Android takes it (0 normal; 0x8000..0xFFFF vendor modes; 1 = constrained high speed and 2..0x7FFF are refused for
     * our streams) and never in a FULLRES session, whose sensor mode is set by its own keys (the vivo forceSensorMode, the OPPO
     * agingtest mode); a vendor mode the camera refused falls back to 0 for this camera (this run).
     */
    static int sessionOperationMode(int setting, boolean fullRes) {
        if (fullRes || setting < 0x8000 || setting > 0xFFFF) return 0;
        return setting;
    }
    /** P211: the module's vendor operation mode the running session was configured with (0: none). */
    private volatile int mVendorOperationMode;
    /** P211: cameras whose session refused the module's vendor operation mode (this run): they run mode 0. */
    private static final java.util.Set<String> sVendorModeRefused = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * P214g: the session settings (settings/SessionKeys: the @SensorConfig fields above, the module's vendor keys, the
     * preferences the build reads) the last session was built with, and the module scope of its sensor keys; null before the
     * first session. Kept over closeCamera, so the camera screen can say on the way back from the settings what the new
     * session takes, and can tell whether a session still running beside the settings (multi-window) needs a restart.
     */
    private volatile Map<String, String> mSessionSettings;
    private volatile String mSessionSettingsScope;

    /** P214g: what this session is built with (createCameraPreviewSession, right after the @SensorConfig injection). */
    private void recordSessionSettings() {
        try {
            String scope = com.particlesdevs.photoncamera.settings.ModuleSensorSettings.runtimeScope(physicalID);
            mSessionSettings = com.particlesdevs.photoncamera.settings.SessionKeys.snapshot(
                    ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getAll(), scope);
            mSessionSettingsScope = scope;
        } catch (RuntimeException e) {
            mSessionSettings = null;
            Log.w(TAG, "session settings not recorded: " + e);
        }
    }

    /**
     * P214g: the session settings whose value differs from the one the last session was built with (empty when none did or
     * no session was built yet), sorted.
     */
    public List<String> sessionSettingsChanged() {
        Map<String, String> built = mSessionSettings;
        if (built == null) return Collections.emptyList();
        try {
            return com.particlesdevs.photoncamera.settings.SessionKeys.changed(built,
                    com.particlesdevs.photoncamera.settings.SessionKeys.snapshot(
                            ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getAll(), mSessionSettingsScope));
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    /** P214g: a configured session runs now (not closed, not opening): a restart has a session to replace. */
    public boolean sessionRunning() {
        return isCameraResumed && mCaptureSession != null;
    }

    @SensorConfig(
            title = "OIS Mode",
            description = "Оптическая стабилизация. Авто выключает её на штативе и в Unlimited, чтобы кадр не плыл.",
            entries = {"On", "Auto", "Off"},
            entryValues = {"0", "1", "2"},
            defaultValue = 0
    )
    public int oisMode = 0;

    private static int mTargetFormat = RAW_FORMAT;
    private ManualModeConsole manualModeConsole;
    private final ParamController paramController;
    public TouchFocus mTouchFocus;
    /** P42: face detection and tracking AF (null until the viewfinder is ready). */
    public volatile com.particlesdevs.photoncamera.control.subject.SubjectFocus mSubjectFocus;

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
    /**
     * P208: the viewfinder's RAW stream of a still-only FULLRES session (the X100 / X300 Ultra's 200 MP): the camera's own RAW size
     * in its normal mode; mImageReaderRaw then takes the 200 MP stills only.
     */
    private volatile ImageReader mImageReaderRawView;
    private ImageReader mVivoSdkJpeg;
    private volatile VivoSdkRawSession mVivoSdkRawSession;
    private volatile CameraDevice mVivoSdkRawDevice;
    private volatile VivoSdkFullResCapture mVivoSdkCapture;
    private String mVivoSdkCameraId;
    private long mVivoSdkPreviewLogMs;
    private final Object mVivoSdkLock = new Object();
    private HandlerThread mVivoSdkCallbackThread;
    private Handler mVivoSdkCallbackHandler;
    private volatile boolean mVivoSdkRestorePending;
    private int mVivoSdkRestoreAttempts;
    private volatile java.util.concurrent.CountDownLatch mVivoSdkClosed = new java.util.concurrent.CountDownLatch(0);
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


    private final ArrayDeque<RingFrame> mZslRingBuffer = new ArrayDeque<>();
    /** P91: the ring's memfd (null: the camera's own Images are held, as before or when no memfd is available). */
    private ZslRing mZslRing;
    private final Object mZslBufferLock = new Object();
    private final java.util.LinkedHashMap<Long, TotalCaptureResult> mHexZslResults = new java.util.LinkedHashMap<>();
    private volatile boolean mZslCapturing = false;
    /**
     * True from the shutter press until the burst's frames have been handed to
     * processing. The raw viewfinder must not intercept frames during that
     * window: they belong to the shot.
     */
    private volatile boolean mShotInProgress = false;
    // Stock-like shot-to-shot: a finished SCAM burst is processed here while the
    // camera already takes the next one. No count limit: each queued shot holds
    // ~175 MB of RAW, so only free system memory bounds the queue.
    private static final ExecutorService SCAM_PROCESSING = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SCAMERA-SCAM-processing");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final java.util.concurrent.atomic.AtomicInteger sScamPending = new java.util.concurrent.atomic.AtomicInteger();
    /**
     * P200 (owner, X7 / X100 Ultra 2026-10-17: «обработка с фулрезом не заканчивается»): FULLRES 50 MP merges waiting or running.
     * The app was killed for memory 1-3 min into each merge: the worker budgets 80 % of MemAvailable at its start, and the camera
     * then built a new 50 MP ZSL ring (up to 16 x 96 MB) and a plain one (66 x 24 MB). While one runs, no 50 MP ring is kept and
     * the plain ring holds SUSPENDED_RING frames (as during a 200 MP merge, P179).
     */
    private static final java.util.concurrent.atomic.AtomicInteger sFullResMerges = new java.util.concurrent.atomic.AtomicInteger();

    static boolean fullResMergeRunning() {
        return sFullResMerges.get() > 0;
    }
    private long mShutterGeneration;
    private boolean mShutterIszAspect4x3;
    /** P212f (W2 = M3): the hand-set white balance's gains at the press (null: automatic), the shot's white point on a mosaic photo. */
    private android.hardware.camera2.params.RggbChannelVector mShutterManualWb;
    /** P212g (W6 = M6): the mosaic white balance's route at the press (the vivo native route, P208's still-only one; null: none). */
    private com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.Press mShutterMosaicWb;
    /** P212g (W6): the still-only session's viewfinder neutrals (its normal mode), and whether this session feeds them. */
    private final com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.Ring mVfNeutrals =
            new com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.Ring();
    private volatile boolean mVfNeutralFeed;
    /** P212g (W7 = M12): the AWB colour temperature label of a plain result (Qualcomm; vivo and OPPO report it). */
    private static final CaptureResult.Key<int[]> AWB_CCT_LABEL =
            new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlCCT", int[].class);
    private volatile boolean mLiveRawSession;
    /**
     * P13: the stream of this session is a colour-block mosaic (a sensor mode without remosaic, MosaicStream): its ISP preview
     * is purple, so the ZSL frames also go to the developed-RAW viewfinder, whose shader bins the colour blocks.
     */
    private volatile boolean mMosaicPreview;
    /** scam_dev.txt "mosaic_preview 0": no measurement and no mosaic preview (diagnostics). */
    private volatile boolean mMosaicMeasure = true;
    private volatile boolean mNativeRawPslCapture;
    private TotalCaptureResult mNativeZslBase;
    private boolean mLiveRawRejected;
    private final PreviewFrameMatcher<Image, TotalCaptureResult> mLiveMetadata =
            new PreviewFrameMatcher<>(this::onMatchedLiveRaw, Image::close);
    /** P36: the session generation whose first capture start armed the viewfinder transform. */
    private int mTransformArmedGeneration = -1;
    /** P38: the last SCAM-routed shot flushed the HAL queue before its series (picks the default re-arm). */
    private volatile boolean mLastShotFlushed;
    /** P38: a flush re-arm waits for the first repeating-preview result after the series (until the deadline). */
    private volatile boolean mRearmPending;
    private volatile long mRearmDeadlineMs;
    /** P38: the session the pending re-arm belongs to (a new session never runs an old session's re-arm). */
    private volatile CameraCaptureSession mRearmSession;
    /** P38: preview stabilisation keys per frame around a shot (tag STAB_TRACE). */
    private final StabilizationTrace mStabTrace = new StabilizationTrace();
    /** P44: the viewfinder freeze of every shot from the preview timestamps (tag PREVIEW_GAP, one line per shot). */
    private final PreviewGapMeter mPreviewGap = new PreviewGapMeter();
    /** P44: preview frames the last shot queued right after its flush, ahead of its series (PreviewContinuity). */
    private volatile int mLastShotLead;
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
    // SCAM: keep pre-shutter RAWs while waiting for the stock AE plan.
    private volatile boolean mScamRingFrozen = false;
    // SCAM fast shutter: the preview keeps running during the L/S/ES tail and RAWs
    // are routed by capture-start timestamp (bracket -> saver, preview -> dropped)
    // instead of stopRepeating + restart, which froze the viewfinder, emptied the
    // ZSL ring and threw AE off for the next shot.
    private volatile boolean mScamRouted = false;
    /**
     * Preview stall watchdog (PreviewStall): the last preview result, the configured session and the frame duration
     * (elapsedRealtime ms), and the restarts in a row that brought no frame.
     */
    private volatile long mLastPreviewResultMs, mPreviewSessionStartMs, mPreviewFrameMs;
    private volatile int mStallRestarts;
    private final Runnable mStallCheck = this::checkPreviewStall;
    // Shutter presses made while a SCAM shot is still capturing; fired in order.
    private int mScamQueuedShots = 0;
    // Sensor timestamp of the newest tail frame (L/S/ES) of the current SCAM shot.
    private volatile long mScamTailTimestamp = 0;
    private volatile boolean mScamFireWaiting = false;
    private volatile long mScamPlanDeadline;
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

            if (mVivoSdkRawSession != null) {
                Image image;
                try { image = reader.acquireNextImage(); } catch (IllegalStateException closed) { return; }
                if (image != null) {
                    VivoSdkFullResCapture capture = mVivoSdkCapture;
                    if (capture != null) capture.image(image);
                    else if (nativeVivoFullRes()) mLiveRawRouter.image(image.getTimestamp(), image);
                    else image.close();
                }
                return;
            }

            //dequeueAndSaveImage(mRawResultQueue, mRawImageReader);
            //mImageSaver.mImage = reader.acquireNextImage();
//            Message msg = new Message();
//            msg.obj = reader;
//            mImageSaver.processingHandler.sendMessage(msg);
            if (mNativeRawPslCapture || mScamRouted || (!isZslMode() && mLiveRawSession)) {
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
                if (!passModeBarrier(img)) { img.close(); return; }
                watchRawPayload(img);
                observeRawLevel(img, null);
                observeMosaic(img);
                plainMeter(img, null); // P212b: the native AE's start, sampled in the plain session (also while a merge runs)
                plainTeacher(img, null); // P212f: the mosaic white balance's teacher (log only)
                // P187: the plain session of a running 200 MP merge takes no shot (captureStillPicture refuses it), so its
                // frames stay out of the ZSL ring: 125 MB of memfd less under the merge and the pipeline
                if (mClosedForProcessing) { img.close(); return; }
                synchronized (mZslBufferLock) {
                    if (!isCameraResumed || reader != mImageReaderRaw) { img.close(); return; }
                    mZslRingBuffer.addLast(ringFrameLocked(img));
                    int maxFrames = ringCapacityLocked();
                    while (mZslRingBuffer.size() > maxFrames) {
                        RingFrame old = mZslRingBuffer.pollFirst();
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
            if(ScameraApp.getSettings().frameCount != 1) {
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
    /** P222: the reason of the last rescueInFlightShot, read by the rescue it runs. */
    private volatile String mInFlightRescueReason;
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
    private volatile ScamStockAe mStockAe;
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
                // P36: the first frame of this session: the viewfinder takes the camera's orientation / mirror from it.
                if (mTransformArmedGeneration != mConfiguredSessionGeneration) {
                    mTransformArmedGeneration = mConfiguredSessionGeneration;
                    if (mTextureView != null) mTextureView.armPendingTransform(timestamp);
                }
                if (mNativeRawPslCapture || mScamRouted || (mLiveRawSession && !isZslMode())) mLiveRawRouter.request(timestamp, false);
                VivoSdkFullResCapture nativeCapture = mVivoSdkCapture;
                if (nativeCapture != null) nativeCapture.previewStarted(timestamp);
            }
        }


        @Override public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                              @NonNull CaptureFailure failure) {
            // P44: a flush drops the preview requests in flight this way (counted in the shot's PREVIEW_GAP line).
            synchronized (mPreviewStateLock) {
                if (isCurrentPreviewSession(session)) mPreviewGap.previewFailed();
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
                // P192: a plain session keeps its colour for the native mode, a native result gets it; P214j: on every phone of
                // the route (OPPO, OnePlus, realme) for a camera with full-size modes - was the PHY110 only (OppoNativeFullRes.keepsColour)
                if (mVivoSdkRawSession == null) {
                    if (OppoNativeFullRes.keepsColour(physicalID)) OppoNativeFullRes.rememberPlain(physicalID, result);
                } else if (VivoSdkFullRes.oppo()) OppoNativeFullRes.complete(mVivoSdkCameraId, result);
                // P212g (W6 = M6): P208's still-only viewfinder is the normal mode (its ISP AWB valid): its neutrals for the press
                if (mVfNeutralFeed && mVivoSdkRawSession != null)
                    mVfNeutrals.offer(physicalID, android.os.SystemClock.elapsedRealtime(), result);
                // P212g (W7 = M12): a converged plain result teaches the camera's colour table (about once a second)
                if (mVivoSdkRawSession == null) plainColour(result);
                if (mLastPreviewResultMs == 0) markModuleGood();
                mLastPreviewResultMs = android.os.SystemClock.elapsedRealtime();
                // Restarts count as successful only after a second of frames: one result per new session reset the count and the
                // stall restarts never stopped (Xiaomi 13 Ultra, 14 restarts in a row, 2026-10-10)
                if (mPreviewSessionStartMs > 0 && mLastPreviewResultMs - mPreviewSessionStartMs > 1000) mStallRestarts = 0;
                Long frameNs = result.get(CaptureResult.SENSOR_FRAME_DURATION);
                if (frameNs != null) mPreviewFrameMs = frameNs / 1_000_000L;
                Object exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Object iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                Object focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                Rational[] mTemp = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
                if (exposure != null) mPreviewExposureTime = (long) exposure;
                if (iso != null) mPreviewIso = (int) iso;
                // P212b: the camera's own plain HAL exposure, the native AE's last fallback start (never another camera's value)
                if (mVivoSdkRawSession == null && exposure != null && iso != null && OppoNativeFullRes.brand())
                    NativeAeSeed.plainHal(physicalID, (long) exposure, (int) iso, mLastPreviewResultMs);
                if (mVivoSdkRawSession != null && mLastPreviewResultMs - mVivoSdkPreviewLogMs >= 2000) {
                    mVivoSdkPreviewLogMs = mLastPreviewResultMs;
                    Log.d("SCAM_CAPTURE", "SDK preview camera=" + physicalID + " exposureNs=" + exposure + " iso=" + iso
                            + " aeState=" + result.get(CaptureResult.CONTROL_AE_STATE)
                            + " aeMode=" + request.get(CaptureRequest.CONTROL_AE_MODE)
                            + " aeLock=" + request.get(CaptureRequest.CONTROL_AE_LOCK)
                            + " ev=" + request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)
                            + " requestedNs=" + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
                            + " requestedISO=" + request.get(CaptureRequest.SENSOR_SENSITIVITY));
                }
                if (iso != null) widenSensitivityRange((int) iso);
                // P27: the AE restore frame after a shot is a manual request at N: it re-checks the learned limits.
                final ExposureLimits limits = mExposureLimits;
                if (limits != null) limits.observeManual(request, result);
                paramController.onPreviewResult(result); // P82 shutter / ISO priority
                // P87: the ISO extremes the camera's own AE uses (the manual rulers' range)
                if (iso != null) {
                    Integer aeMode = request.get(CaptureRequest.CONTROL_AE_MODE);
                    if (aeMode != null && aeMode != CaptureRequest.CONTROL_AE_MODE_OFF)
                        ExposureRanges.observeAutoIso(physicalID, (int) iso);
                }
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
                logSensorModeIndex(result);
                VivoQcomModes.onResult(result, physicalID);
                if (XiaomiTeleZoom.active() && isCurrentPreviewSession(session)
                        && XiaomiTeleZoom.onResult(result, xiaomiLensResult(result), PreferenceKeys.scamDevSwitch("xiaomi_lens_check", true)))
                    onZoomChanged();
                mStabTrace.record(request, result);
                final Long gapTs = result.get(CaptureResult.SENSOR_TIMESTAMP);
                if (gapTs != null) {
                    final String gapLine = mPreviewGap.preview(gapTs, android.os.SystemClock.elapsedRealtimeNanos());
                    if (gapLine != null) Log.i(PreviewGapMeter.TAG, gapLine);
                }
                maybeRunPendingRearm(session, request, result);
                if(PreferenceKeys.isScamEnabled() && PreferenceKeys.useStockBracketPlanner() && isCurrentPreviewSession(session)) {
                    ScamStockAe stock=mStockAe;
                    if(stock==null || stock.generation!=mSessionGeneration.get()) {
                        if(stock!=null)stock.close();
                        stock=new ScamStockAe(ScameraApp.getAppContext(),mSessionGeneration.get(),physicalID);
                        mStockAe=stock;
                    }
                    stock.offer(result);
                }
                process(result);
                if (mTouchFocus != null) {
                    mTouchFocus.onCaptureResult(result);
                }
                final com.particlesdevs.photoncamera.control.subject.SubjectFocus subject = mSubjectFocus;
                if (subject != null) subject.onPreviewResult(result);
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
                if (mVivoSdkRawSession != null) mVivoSdkRawDevice = cameraDevice;
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
                if (mVivoSdkRawSession == null) VivoQcomModes.onCameraOpened();
                mImageSaver = new ImageSaver(cameraEventsListener);
                createCameraPreviewSession(false);
            }
        }

        @Override public void onClosed(@NonNull CameraDevice cameraDevice) {
            closeVivoSdkRawSession(cameraDevice);
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            if (mVivoSdkRawSession != null && mVivoSdkRawDevice == null) mVivoSdkRawDevice = cameraDevice;
            rescueInFlightShot("camera disconnected");
            stopStallWatch();
            mCameraOpenCloseLock.release();
            mCameraOpening.set(false);
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mDeviceErrorMs = android.os.SystemClock.elapsedRealtime();
            if (mVivoSdkRawSession != null && mVivoSdkRawDevice == null) mVivoSdkRawDevice = cameraDevice;
            rescueInFlightShot("camera error " + error);
            stopStallWatch();
            mCameraOpenCloseLock.release();
            mCameraOpening.set(false);
            cameraDevice.close();
            mCameraDevice = null;
            Log.w(TAG, "onError() : cameraDevice = [" + cameraDevice + "], error = [" + error + "]");
            if (retryWithoutVivoSdkFullRes(null, "camera error " + error)) return;
            if (retryWithoutRequestedSensorMode(null, "camera error " + error)) return;
            if (mXiaomiRouted) {
                // P41b: the logical camera failed for this app: the tele alone from now on
                mXiaomiRouted = false;
                XiaomiTeleZoom.disableRoute("logical camera error " + error);
                scheduleRecovery("Xiaomi logical camera error " + error);
                return;
            }
            if (revertToLastGoodModule("error " + error)) return;
            if (revertToLastGoodCamera("error " + error)) return;
            if (error == ERROR_CAMERA_DEVICE || error == ERROR_CAMERA_SERVICE) {
                showError(Lang.t("Сбой камеры (код ", "Camera failure (code ") + error + Lang.t("), перезапуск", "), restarting"));
                scheduleRecovery("onError " + error);
            } else {
                showError(Lang.t("Камера недоступна (код ", "Camera unavailable (code ") + error + ")");
            }
        }
    };

    // Recovery after the camera HAL or service died: the vivo X100 Ultra (owner's log 2026-10-05) answered
    // ERROR_CAMERA_DEVICE, every id was 'unknown device' for ~4.5 s while the provider restarted, and nothing reopened the
    // camera until the user tapped a module (27 s of a dead viewfinder once). 1, 2, 4 s, at most three times a minute.
    private android.os.Handler mRecoveryHandler;
    private final java.util.ArrayDeque<Long> mRecoveries = new java.util.ArrayDeque<>();

    /**
     * Session fallbacks per camera (vivo X200 Pro, owner's log 2026-10-07: the front camera 1 and camera 5 answered every session
     * of the preview + RAW streams with onConfigureFailed, the viewfinder froze on the last frame of the main camera with a toast,
     * and camera 5 died with ERROR_CAMERA_DEVICE on every reopen). A failed configuration retries once with the next smaller RAW
     * size the camera lists, then once without a RAW stream (YUV), then returns to the last camera that ran in this process.
     * Kept for the process: the next switch to that camera starts at the step that worked.
     */
    private static final java.util.Map<String, Integer> sRawFallback = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * A YUV session (no RAW stream) that failed to configure on a camera that never ran: 1 = the preview stream at a guaranteed
     * size (at most 1080p), 2 = the YUV stream as well (SafeStreamSizes).
     */
    private static final java.util.Map<String, Integer> sSafeStreams = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Size> sRawSizeAlt = new java.util.concurrent.ConcurrentHashMap<>();
    /** Cameras (full camera ID setting) whose session was configured in this process, and the last one of them. */
    private static final java.util.Set<String> sGoodCameras = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile String sLastGoodCamera;
    /** The RAW target format this session replaced with YUV (no RAW stream on this camera); restored for the next camera. */
    private static volatile int sDemotedFrom = 0;
    private volatile boolean mReverting;

    /** RAW sizes of the target format the camera lists, largest first (plain stream configuration only). */
    private static Size[] rawSizesLargestFirst(StreamConfigurationMap map, int format) {
        Size[] sizes = map == null ? null : map.getOutputSizes(format);
        if (sizes == null) return new Size[0];
        sizes = sizes.clone();
        Arrays.sort(sizes, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
        return sizes;
    }

    /**
     * Next fallback step after onConfigureFailed of this camera: true when a session is being created again (smaller RAW, then no
     * RAW stream). Not while recording, and only for a session with a RAW stream.
     */
    private boolean retryConfigureFallback(CameraCaptureSession session) {
        final CameraDevice device = mCameraDevice;
        if (device == null || mIsRecordingVideo) return false;
        final String id = physicalID;
        if (!isRawFormat(mTargetFormat)) {
            // A YUV session (the camera lists no RAW size, or its RAW sessions failed): retrying it as it is only fails again
            // (vivo X200 Pro front camera, owner's log 2026-10-08). Smaller streams next: the preview, then the YUV stream.
            if (sGoodCameras.contains(ScameraApp.getSettings().mCameraID)) return false;
            final int safe = sSafeStreams.getOrDefault(id, 0);
            if (safe >= 2) return false;
            sSafeStreams.put(id, safe + 1);
            Log.w(TAG, "camera " + id + ": session configuration failed (preview " + mBufferSize + ", "
                    + (mImageReaderRaw == null ? "no reader" : "reader " + mImageReaderRaw.getWidth() + "x" + mImageReaderRaw.getHeight()
                    + " format " + mImageReaderRaw.getImageFormat()) + "); retry with "
                    + (safe == 0 ? "a preview of a guaranteed size" : "a YUV stream of a guaranteed size too"));
            try { session.close(); } catch (Exception ignored) {}
            final Handler handler = mBackgroundHandler;
            if (handler == null) return false;
            handler.post(() -> {
                if (!isCameraResumed || mCameraDevice != device) return;
                UpdateCameraCharacteristics(id);
                createCameraPreviewSession(false);
            });
            return true;
        }
        final int stage = sRawFallback.getOrDefault(id, 0);
        if (stage >= 2) return false;
        // A camera whose session configured in this process keeps its streams: a later failure is transient (the old path), not a
        // reason to give up its RAW size or RAW stream for the rest of the process.
        if (sGoodCameras.contains(ScameraApp.getSettings().mCameraID)) return false;
        int next = stage + 1;
        Size alt = null;
        // Not with the maximum-resolution pixel mode (Quad / remosaic): a size of the default map would switch the sensor mode.
        if (next == 1 && mImageReaderRaw != null && isRawFormat(mTargetFormat) && !useMaximumResolutionKey) {
            final long current = (long) mImageReaderRaw.getWidth() * mImageReaderRaw.getHeight();
            CameraCharacteristics c = mCameraCharacteristicsMap.get(id);
            StreamConfigurationMap map = c == null ? null : c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            for (Size size : rawSizesLargestFirst(map, mTargetFormat))
                if ((long) size.getWidth() * size.getHeight() < current) { alt = size; break; }
        }
        if (alt == null) next = 2; else sRawSizeAlt.put(id, alt);
        sRawFallback.put(id, next);
        Log.w(TAG, "camera " + id + ": session configuration failed (preview " + mBufferSize + ", "
                + (mImageReaderRaw == null ? "no reader" : "reader " + mImageReaderRaw.getWidth() + "x" + mImageReaderRaw.getHeight()
                + " format " + mImageReaderRaw.getImageFormat()) + "); retry " + (next == 1 ? "with RAW " + alt : "without the RAW stream (YUV)"));
        try { session.close(); } catch (Exception ignored) {}
        final Handler handler = mBackgroundHandler;
        if (handler == null) return false;
        handler.post(() -> {
            if (!isCameraResumed || mCameraDevice != device) return;
            UpdateCameraCharacteristics(id);
            createCameraPreviewSession(false);
        });
        return true;
    }

    /**
     * A camera that never ran in this process failed (configuration, device error): back to the last camera that did, instead of a
     * frozen viewfinder or reopening the failing one again and again. False when there is none to go back to.
     */
    /**
     * Modules (slots) that delivered a preview frame in this process, and the last of them. A module is its Camera ID plus its
     * vendor keys: the OPPO Find X9 Ultra / Realme GT8 Pro ISZ modules (camera 4 + agingtest.mode.select) killed the HAL while
     * the plain camera 4 ran, so the camera-level record (marked at onConfigured) never reverted and the recovery reopened the
     * failing module again and again (2026-10-10 logs).
     */
    private static final java.util.Set<String> sGoodModules = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile String sLastGoodModule;

    private void markModuleGood() {
        try {
            String slot = com.particlesdevs.photoncamera.settings.ModuleRegistry.active();
            sGoodModules.add(slot);
            sLastGoodModule = slot;
        } catch (RuntimeException ignored) {
        }
    }

    /** A module that never delivered a frame failed: back to the last module that did (another slot), with a toast. */
    private boolean revertToLastGoodModule(String reason) {
        final String current;
        try {
            current = com.particlesdevs.photoncamera.settings.ModuleRegistry.active();
        } catch (RuntimeException e) {
            return false;
        }
        final String good = sLastGoodModule;
        if (good == null || good.equals(current) || sGoodModules.contains(current) || mReverting || !isCameraResumed) return false;
        mReverting = true;
        final String label = com.particlesdevs.photoncamera.settings.ModuleRegistry.label(current);
        Log.e(TAG, "module " + current + " (" + label + ") failed (" + reason + ") before its first frame: back to module " + good);
        showError(Lang.t("Модуль " + label + " не запускается на этом телефоне (" + reason + "), возврат к предыдущему",
                "Module " + label + " does not start on this phone (" + reason + "), back to the previous one"));
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            mReverting = false;
            com.particlesdevs.photoncamera.settings.ModuleRegistry.select(good);
            PreferenceKeys.setCameraID(com.particlesdevs.photoncamera.settings.ModuleRegistry.camera(good));
            restartCamera();
        });
        return true;
    }

    private boolean revertToLastGoodCamera(String reason) {
        final String current = ScameraApp.getSettings().mCameraID;
        final String good = sLastGoodCamera;
        if (good == null || good.equals(current) || sGoodCameras.contains(current) || mReverting || !isCameraResumed) return false;
        mReverting = true;
        Log.e(TAG, "camera " + current + " failed (" + reason + ") and never ran in this session: back to camera " + good);
        showError(Lang.t("Камера " + current + " не запускается (" + reason + "), возврат к камере " + good,
                "Camera " + current + " does not start (" + reason + "), back to camera " + good));
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            mReverting = false;
            PreferenceKeys.setCameraID(good);
            restartCamera();
        });
        return true;
    }

    private void scheduleRecovery(String reason) {
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (mRecoveries) {
            while (!mRecoveries.isEmpty() && now - mRecoveries.peekFirst() > 60_000) mRecoveries.pollFirst();
            if (mRecoveries.size() >= 3) {
                Log.e(TAG, "camera recovery stopped after three restarts within a minute (" + reason + ")");
                showError(Lang.t("Камера не отвечает. Выберите другой модуль или перезапустите приложение", "The camera is not responding. Choose another module or restart the app"));
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
    /** P186: when the open camera last died (onError), and how often its return has been waited for since. */
    private volatile long mDeviceErrorMs;
    private int mProviderWaits;
    /** Set when filling the characteristics failed (camera provider died during startup): refilled before the next open. */
    private volatile boolean mCharacteristicsIncomplete;
    /** Waits for the requested camera after a fallback to the main camera of its side (see {@link #awaitRequestedCamera}). */
    private CameraManager.AvailabilityCallback mAwaitCallback;
    private static final long AWAIT_REQUESTED_MS = 60_000;

    /**
     * Characteristics of the requested camera (Settings.mCameraID, "logical-physical" or a plain ID); logicalID / physicalID are
     * set to the camera that will open. A camera missing from the list read at start is asked for directly first: the
     * cached list can lack a hidden lens the camera service has. Without it, the main camera of the requested module's side
     * opens in its place and the module strip shows that camera's module: the old fallback took the first entry of the map,
     * so on the OPPO PHY110 a restart on the 2.8x tele (camera 4, list [1, 2, 3]) opened the front camera under the back
     * "2.8x" label. Null when that side has no camera at all (a recovery is scheduled).
     */
    private CameraCharacteristics requestedCharacteristics() {
        String curID = ScameraApp.getSettings().mCameraID;
        if (curID.contains("-")) {
            logicalID = curID.split("-")[0];
            physicalID = curID.split("-")[1];
        } else {
            logicalID = curID;
            physicalID = curID;
        }
        Log.d(TAG, "ID:" + mCameraCharacteristicsMap.get(physicalID));
        CameraCharacteristics chars = mCameraCharacteristicsMap.get(physicalID);
        if (chars == null) chars = characteristicsOnDemand(physicalID);
        if (chars != null) {
            mProviderWaits = 0;
            routeXiaomiTele();
            return chars;
        }
        // P186 (X300 Ultra: a FULLRES mode the camera could not run killed it; the provider restarts and hides its cameras for a
        // moment): shortly after a device error the same camera is waited for, not replaced by the main camera of its side
        if (mDeviceErrorMs > 0 && android.os.SystemClock.elapsedRealtime() - mDeviceErrorMs < 15_000 && mProviderWaits < 12) {
            mProviderWaits++;
            Log.w(TAG, "camera " + physicalID + " not back yet after a device error: again in 700 ms (" + mProviderWaits + ")");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isCameraResumed && mCameraDevice == null && !mCameraOpening.get()) restartCamera();
            }, 700);
            return null;
        }
        String requestedSlot = com.particlesdevs.photoncamera.settings.ModuleRegistry.active();
        int wanted = com.particlesdevs.photoncamera.settings.ModuleChoice.wantedFacing(requestedSlot, null);
        String side = com.particlesdevs.photoncamera.settings.ModuleChoice.side(wanted);
        Map<String, Integer> facing = new HashMap<>();
        java.util.Set<String> auxiliary = new java.util.HashSet<>();
        for (Map.Entry<String, CameraCharacteristics> e : mCameraCharacteristicsMap.entrySet()) {
            CameraCharacteristics c = e.getValue();
            if (c == null) continue;
            Integer f = c.get(CameraCharacteristics.LENS_FACING);
            if (f != null) facing.put(e.getKey(), f);
            if (CameraManager2.isAuxiliarySensor(c)) auxiliary.add(e.getKey());
        }
        String fallback = com.particlesdevs.photoncamera.settings.ModuleChoice.fallbackCamera(facing, auxiliary, wanted);
        if (fallback == null) {
            Log.e(TAG, "No characteristics for physicalID=" + physicalID + " (mCameraID=" + curID + ", module " + requestedSlot
                    + ") and no other " + side + " camera: not opened");
            scheduleRecovery("no " + side + " camera with characteristics");
            return null;
        }
        Log.e(TAG, "No characteristics for physicalID=" + physicalID + " (mCameraID=" + curID + ", module " + requestedSlot
                + "). Falling back to the main " + side + " camera " + fallback + ".");
        String module = com.particlesdevs.photoncamera.settings.ModuleRegistry.moduleForCamera(side, fallback);
        if (module != null) {
            // The strip, the label and the zoom follow the camera that really opens.
            com.particlesdevs.photoncamera.settings.ModuleRegistry.select(module);
            com.particlesdevs.photoncamera.control.ZoomController.onButton(module);
        }
        PreferenceKeys.setCameraID(fallback);
        ScameraApp.getSettings().mCameraID = fallback;
        awaitRequestedCamera(curID, requestedSlot, fallback, module);
        showError(Lang.t("Камера " + logicalID + " недоступна, открыта основная камера " + fallback,
                "Camera " + logicalID + " is not available, the main camera " + fallback + " is open"));
        logicalID = fallback;
        physicalID = fallback;
        return mCameraCharacteristicsMap.get(fallback);
    }

    /** P41b: this camera runs through the logical SAT camera of the Xiaomi 17 Ultra (see {@link #routeXiaomiTele}). */
    private volatile boolean mXiaomiRouted;

    /**
     * P41b (the owner's 17U stock capture, 2026-10-09): the tele opens through the logical SAT camera that holds it, as the stock
     * camera does ({@link XiaomiTeleZoom#logicalRoute}). logicalID becomes that camera, physicalID stays the tele (its RAW
     * stream and characteristics); Settings.mCameraID is not changed. A "logical-physical" ID chosen by the user stays as it is.
     */
    private void routeXiaomiTele() {
        mXiaomiRouted = false;
        if (mVivoSdkRawSession != null) return;
        if (!Objects.equals(logicalID, physicalID)) return;
        if (VivoQcomModes.device() && VivoQcomModes.spoofPackage() != null
                && PreferenceKeys.scamDevSwitch("vivo_qcom_logical", false)) {
            try {
                CameraCharacteristics sat = mCameraManager.getCameraCharacteristics("2");
                if (sat.getPhysicalCameraIds().contains(physicalID)) {
                    logicalID = "2";
                    Log.i(TAG, "vivo Qualcomm: physical camera " + physicalID + " through logical camera 2");
                    return;
                }
            } catch (CameraAccessException | RuntimeException e) {
                Log.w(TAG, "vivo Qualcomm logical route unavailable: " + e.getMessage());
            }
        }
        try {
            String logical = XiaomiTeleZoom.logicalRoute(mCameraManager, physicalID, mCameraCharacteristicsMap.get(physicalID),
                    PreferenceKeys.scamDevSwitch("xiaomi_logical", true));
            if (logical != null && !logical.equals(physicalID)) {
                logicalID = logical;
                mXiaomiRouted = true;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Xiaomi logical route: " + e.getMessage());
        }
    }

    /** P41b: the logical camera did not open: the tele opens alone (the route is off for this process). */
    private boolean retryWithoutXiaomiRoute(int width, int height, Exception e) {
        final Handler handler = mBackgroundHandler;
        if (!mXiaomiRouted || handler == null) return false;
        mXiaomiRouted = false;
        XiaomiTeleZoom.disableRoute("logical camera " + logicalID + " does not open: " + e.getMessage());
        handler.post(() -> openCamera(width, height));
        return true;
    }

    /** P41b: the logical camera refused the session or stalled: a regular session on it, then the tele alone. */
    private boolean retryXiaomiRoute(CameraCaptureSession session, String reason) {
        if (!mXiaomiRouted) return false;
        final boolean again = XiaomiTeleZoom.stepDownRoute(reason);
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        if (again) {
            final CameraDevice device = mCameraDevice;
            final Handler handler = mBackgroundHandler;
            if (device == null || handler == null) return false;
            handler.post(() -> {
                if (!isCameraResumed || mCameraDevice != device) return;
                createCameraPreviewSession(false);
            });
        } else {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (isCameraResumed) restartCamera();
            });
        }
        return true;
    }

    /** Asks the camera service for a camera the start-up list did not have; null when it does not know it either. */
    private CameraCharacteristics characteristicsOnDemand(String id) {
        try {
            CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
            if (c != null) {
                mCameraCharacteristicsMap.put(id, c);
                Log.i(TAG, "characteristics of camera " + id + " fetched on demand");
            }
            return c;
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.w(TAG, "no characteristics for camera " + id + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * After a fallback: when the requested camera appears within {@link #AWAIT_REQUESTED_MS} (hidden lenses can show up later
     * in the process) and the fallback is still what runs, its module is selected again and the camera restarts on it.
     */
    private void awaitRequestedCamera(String requestedId, String requestedSlot, String fallbackId, String fallbackModule) {
        stopAwaitingRequestedCamera();
        if (requestedSlot == null || com.particlesdevs.photoncamera.settings.ModuleChoice.side(requestedSlot) == null) return;
        final String logical = requestedId.contains("-") ? requestedId.split("-")[0] : requestedId;
        final String physical = requestedId.contains("-") ? requestedId.split("-")[1] : requestedId;
        final long deadline = SystemClock.elapsedRealtime() + AWAIT_REQUESTED_MS;
        final Handler main = new Handler(Looper.getMainLooper());
        CameraManager.AvailabilityCallback callback = new CameraManager.AvailabilityCallback() {
            @Override
            public void onCameraAvailable(@NonNull String cameraId) {
                if (mAwaitCallback != this || !(logical.equals(cameraId) || physical.equals(cameraId))) return;
                boolean stillFallback = fallbackId.equals(PreferenceKeys.getCameraID()) && (fallbackModule == null
                        || fallbackModule.equals(com.particlesdevs.photoncamera.settings.ModuleRegistry.active()));
                if (SystemClock.elapsedRealtime() > deadline || !stillFallback) { stopAwaitingRequestedCamera(); return; }
                if (!isCameraResumed || characteristicsOnDemand(physical) == null) return; // a later report may still work
                stopAwaitingRequestedCamera();
                Log.i(TAG, "camera " + requestedId + " appeared: back to module " + requestedSlot);
                com.particlesdevs.photoncamera.settings.ModuleRegistry.select(requestedSlot);
                com.particlesdevs.photoncamera.control.ZoomController.onButton(requestedSlot);
                PreferenceKeys.setCameraID(requestedId);
                ScameraApp.getSettings().mCameraID = requestedId;
                restartCamera();
            }
        };
        mAwaitCallback = callback;
        try {
            mCameraManager.registerAvailabilityCallback(callback, main);
        } catch (RuntimeException e) {
            mAwaitCallback = null;
            return;
        }
        main.postDelayed(() -> { if (mAwaitCallback == callback) stopAwaitingRequestedCamera(); }, AWAIT_REQUESTED_MS);
    }

    private void stopAwaitingRequestedCamera() {
        CameraManager.AvailabilityCallback callback = mAwaitCallback;
        mAwaitCallback = null;
        if (callback != null) {
            try { mCameraManager.unregisterAvailabilityCallback(callback); } catch (RuntimeException ignored) {}
        }
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
            // A theme change recreates the activity after this fragment has resumed: the dying fragment's preview
            // can still report its texture once its executor is shut down. The new fragment opens the camera.
            if (processExecutor.isShutdown()) {
                Log.d(TAG, "onSurfaceTextureAvailable(): controller already destroyed, ignored");
                return;
            }
            try {
                // list available characteristics ids
                for (String id : mCameraCharacteristicsMap.keySet()) {
                    Log.d(TAG, "Available camera ID: " + id);
                }
                CameraCharacteristics chars = requestedCharacteristics();
                if (chars == null) return;
                Size optimal = getPreviewOutputSize(getSafeDisplay(), chars,
                        ScameraApp.getSettings().selectedMode);
                openCamera(optimal.getWidth(), optimal.getHeight());
            } catch (Exception e){
                Log.e(TAG,Log.getStackTraceString(e));
                showError("Error onSurfaceTextureAvailable:"+e.getLocalizedMessage());
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
        this.activity = activity;
        this.cameraEventsListener = cameraEventsListener;
        this.mTextureView = activity.findViewById(R.id.texture);
        this.mCameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        // Before the first camera list read: vendors filter hidden cameras by package.
        com.particlesdevs.photoncamera.capture.spoof.CameraPackageSpoof.apply(activity, mCameraManager);
        this.mCameraManager2 = new CameraManager2(mCameraManager, ScameraApp.getInstance(activity).getSettingsManager());
        PreferenceKeys.addIds(mCameraManager2.getCameraIdList());

        this.processExecutor = processExecutor;
        this.paramController = new ParamController(this);
        // P92: the processing foreground service runs while a shot is captured, queued or merged
        com.particlesdevs.photoncamera.processing.ProcessingKeepAlive.busy = () -> sScamPending.get() > 0 || nextShotPending() || isProcessing;

        this.fillInCameraCharacteristics();
    }

    /**
     * Fills in {@link CaptureController#mCameraCharacteristicsMap} that is used in
     * {@link CaptureController#UpdateCameraCharacteristics}.
     */
    private void fillInCameraCharacteristics() {
        String failure = null;
        for (String cameraId : mCameraManager2.getCameraIdList()) {
            String physicalID = cameraId;
            if(cameraId.contains("-")){
                physicalID = cameraId.split("-")[1];
            }
            try {
                mCameraCharacteristicsMap.put(physicalID, mCameraManager.getCameraCharacteristics(physicalID));
            } catch (CameraAccessException | IllegalArgumentException e) {
                // IllegalArgumentException "unknown device" when the camera provider dies during startup (OPPO PHY110,
                // 2026-10-07: the app crashed in the CaptureController constructor). The other cameras are still read.
                Log.e(TAG, "characteristics of camera " + physicalID + " unavailable: " + e.getMessage());
                if (failure == null) failure = "camera " + physicalID + ": " + e.getMessage();
            }
        }
        mCharacteristicsIncomplete = failure != null;
        // The usual bounded recovery (three restarts a minute); the restart reads the characteristics again.
        if (failure != null) scheduleRecovery("camera characteristics unavailable (" + failure + ")");
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
        sDemotedFrom = 0; // the user's choice: no session fallback restores a RAW target over it
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

        if (largestSizeArea <= ResolutionSolution.highRes) {
            temp = sizes[largestSizeIdx];
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
            String curID = ScameraApp.getSettings().mCameraID;
            if(curID.contains("-")) {
                logicalID = curID.split("-")[0];
                physicalID = curID.split("-")[1];
            } else {
                logicalID = curID;
                physicalID = logicalID;
            }
            routeXiaomiTele();
            
            UpdateCameraCharacteristics(physicalID);
            //Thread thr = new Thread(mImageSaver);
            //thr.start();
        } catch (Exception e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            Log.e(TAG, Log.getStackTraceString(e));
            showError(activity.getString(R.string.camera_error));
            if (mVivoSdkRawSession != null) throw new IllegalStateException("SDK camera output setup failed", e);
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

    /** P179: a shot above this many pixels (200 MP) is merged with the camera in its plain session. */
    static final long HUGE_SHOT_PIXELS = 100_000_000L;
    /** P179 (owner): frames of a 200 MP series, the last one ultrashort; the shortest ultrashort exposure asked for. */
    static final int HUGE_SERIES = 4;
    static final long ULTRASHORT_MIN_NS = 50_000L;
    /** P179: the camera was closed for a 200 MP merge; it opens again after it while the screen is up. */
    private volatile boolean mClosedForProcessing;
    /** P179: counted down once the camera closed for a 200 MP merge (the merge's memory budget is read after it). */
    private volatile java.util.concurrent.CountDownLatch mClosedForProcessingDone = new java.util.concurrent.CountDownLatch(0);

    /**
     * P179 (200 MP: the app was killed for LOW_MEMORY in the merge): the camera's 200 MP RAW buffers (4 x 401 MB) and the HAL's
     * own stay allocated while the 200 MP session runs. For a 200 MP shot, once its frames are copied, the camera goes over to
     * its plain session (FULLRES suspended: a live viewfinder, owner: «чтобы не было ... черного видоискателя в конце», at a
     * fraction of the memory; the SDK lease goes back as the device closes) and back to 200 MP after the merge.
     */
    private void closeCameraForProcessing(int megapixels) {
        mClosedForProcessing = true;
        // P214h: the switch and the shutter name the shot being merged (50 MP since P200, not only 200 MP)
        PixelPro10.suspendFullRes(true, megapixels);
        final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        mClosedForProcessingDone = closed;
        // posted (never run inside the capture's completion, which still clears the shot's state after this call)
        new Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (!mClosedForProcessing) { closed.countDown(); return; }
            Log.i("SCAM_CAPTURE", "FULLRES: the camera goes to its plain session for the merge (the FULLRES buffers go back)");
            try {
                if (isCameraResumed) restartCamera(); else closeCamera();
            } finally {
                closed.countDown();
            }
        });
    }

    /** P179: the merge of a 200 MP shot starts once the camera's buffers are back (at most 3 s). */
    private void awaitClosedForProcessing() {
        try {
            if (!mClosedForProcessingDone.await(3, TimeUnit.SECONDS)) Log.w("SCAM_CAPTURE", "FULLRES: the camera did not close in 3 s");
            else android.os.SystemClock.sleep(300); // the HAL hands its buffers back just after the device closes
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void reopenCameraAfterProcessing() {
        new Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (!mClosedForProcessing) return;
            mClosedForProcessing = false;
            PixelPro10.suspendFullRes(false);
            boolean resumed = activity instanceof androidx.lifecycle.LifecycleOwner
                    && ((androidx.lifecycle.LifecycleOwner) activity).getLifecycle().getCurrentState()
                    .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
            if (!resumed) return; // left meanwhile: onResume opens it at 200 MP
            Log.i("SCAM_CAPTURE", "FULLRES: the merge is done, the camera goes back to FULLRES");
            restartCamera();
        });
    }

    public void closeCamera() {
        boolean wasOpening = mCameraOpening.get();
        VivoSdkFullResCapture sdkCapture = mVivoSdkCapture;
        mVivoSdkCapture = null;
        if (sdkCapture != null) sdkCapture.cancel();
        rescueInFlightShot("camera closed");
        releaseZslRing();
        mStabTrace.stop();
        mPreviewSessionStartMs = 0;
        if (mBackgroundHandler != null) mBackgroundHandler.removeCallbacks(mStallCheck);
        mScamQueuedShots = 0;
        ScamStockAe stock=mStockAe;mStockAe=null;if(stock!=null)stock.close();
        mScamRingFrozen=false;
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
            } else if (!wasOpening) {
                closeVivoSdkRawSession(null);
            }
            if (mVivoSdkJpeg != null) { mVivoSdkJpeg.close(); mVivoSdkJpeg = null; }
            if (!isProcessing) {
                if (mImageReaderPreview != null) {
                    mImageReaderPreview.close();
                    mImageReaderPreview = null;
                }
                ImageReader closingRaw = mImageReaderRaw;
                mImageReaderRaw = null;
                if (closingRaw != null) closingRaw.close();
                ImageReader closingView = mImageReaderRawView;
                mImageReaderRawView = null;
                if (closingView != null) closingView.close();
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

    /**
     * P99: the sensor mode the Qualcomm HAL reports (com.qti.sensorbps.mode_index; the Galaxy S24 Ultra showed 2..34), logged
     * when it changes, so a vendor key added per module (Settings, sensor settings per module, «Добавить vendor tag») can be
     * seen to switch the mode. Silent where the HAL has no such tag.
     */
    private void logSensorModeIndex(CaptureResult result) {
        if (mCroppedRawSession && result != null) {
            android.graphics.Rect crop = CroppedRaw.of(result, physicalID, logicalID);
            // P100b (S24 Ultra, 2026-05-18: x1.0 at zoom 2.62 for ~1 s): which result held the crop, and the zoom the HAL ran
            final boolean physical = !Objects.equals(physicalID, logicalID) && crop != null
                    && Objects.equals(crop, CroppedRaw.of(physicalResult(result)));
            Float ratio = null;
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    ratio = result.get(CaptureResult.CONTROL_ZOOM_RATIO);
                } catch (RuntimeException ignored) {
                    // not reported
                }
            }
            final boolean zoomStep = ratio != null && Math.abs(ratio - mLastRawCropZoom) >= 0.25f;
            if (crop != null && (!CroppedRaw.same(crop, mLastRawCrop) || zoomStep)) {
                android.graphics.Rect active = mCameraCharacteristics == null ? null
                        : mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                Log.i("SCAM_CAPTURE", "raw crop " + crop + " of " + active + ": x" + CroppedRaw.factor(crop, active)
                        + (CroppedRaw.factor(crop, active) > 1f ? " (in-sensor zoom)" : "")
                        + " [" + (physical ? "physical " + physicalID : "logical " + logicalID) + " result, zoomRatio " + ratio + "]");
                mLastRawCrop = crop;
                if (ratio != null) mLastRawCropZoom = ratio;
            }
        }
        if (sModeIndexMissing || result == null) return;
        int[] v;
        try {
            // the physical camera's own mode in a logical session (S24 Ultra), else the logical result's
            CaptureResult own = physicalResult(result);
            v = own != null ? own.get(SENSOR_MODE_INDEX) : null;
            if (v == null) v = result.get(SENSOR_MODE_INDEX);
        } catch (RuntimeException unknown) {
            sModeIndexMissing = true;
            return;
        }
        if (v == null || v.length == 0 || v[0] == mLastModeIndex) return;
        Log.i("SCAM_CAPTURE", "sensor mode index " + mLastModeIndex + " -> " + v[0] + " (camera " + physicalID + ")");
        mLastModeIndex = v[0];
    }

    private float mLastRawCropZoom = -1f;

    /** The physical camera's own result inside a logical session's TotalCaptureResult, or null. */
    private CaptureResult physicalResult(CaptureResult result) {
        if (!(result instanceof TotalCaptureResult) || Build.VERSION.SDK_INT < 28 || Objects.equals(physicalID, logicalID)) return null;
        try {
            return ((TotalCaptureResult) result).getPhysicalCameraResults().get(physicalID);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final CaptureResult.Key<int[]> SENSOR_MODE_INDEX = new CaptureResult.Key<>("com.qti.sensorbps.mode_index", int[].class);
    private static volatile boolean sModeIndexMissing;
    private int mLastModeIndex = Integer.MIN_VALUE;

    /** P86: the «Движение» mode's AE range on this camera (null: the mode is off or the camera lists no fixed range). */
    private Range<Integer> motionFpsRange() {
        if (mVivoSdkRawSession != null || !com.particlesdevs.photoncamera.settings.MotionMode.on() || mIsRecordingVideo) return null;
        ImageReader raw = mImageReaderRaw;
        Size size = raw != null ? new Size(raw.getWidth(), raw.getHeight()) : null;
        return com.particlesdevs.photoncamera.settings.MotionMode.fpsRange(mCameraCharacteristics, mTargetFormat, size);
    }

    private Range<Integer> getSelectedFpsRange() {
        switch (ScameraApp.getSettings().fpsMode) {
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
                // P41: a shot request takes the preview's keys as they are (it never toggles the sensor mode itself)
                if (builder != mPreviewRequestBuilder && XiaomiTeleZoom.applyLast(builder, physicalID)) {
                    com.particlesdevs.photoncamera.control.ZoomController.overrideResidual(XiaomiTeleZoom.last().residual);
                    return;
                }
                boolean wasIsz = XiaomiTeleZoom.isz();
                final float moduleZoom = com.particlesdevs.photoncamera.settings.ModuleRegistry.zoom(com.particlesdevs.photoncamera.settings.ModuleRegistry.active());
                final float zoom = com.particlesdevs.photoncamera.control.ZoomController.zoom();
                boolean wasActive = XiaomiTeleZoom.active();
                // P41, 17U dumps 2026-10-08: the HAL's own optical zoom report / target (dev switches to go back)
                XiaomiTeleZoom.halOptics(PreferenceKeys.scamDevSwitch("xiaomi_hal_optics", true),
                        PreferenceKeys.scamDevSwitch("xiaomi_opt_target", true));
                XiaomiTeleZoom.Plan plan = XiaomiTeleZoom.apply(builder, mCameraCharacteristics,
                        ScameraApp.getSettingsManagerStatic().getDefaultPreferences().getBoolean(XiaomiTeleZoom.PREF, true),
                        moduleZoom, zoom, physicalID, PreferenceKeys.scamDevSwitch("xiaomi_lens_check", true),
                        PreferenceKeys.scamDevSwitch("xiaomi_crop_mode", false),
                        PreferenceKeys.scamDevSwitch("xiaomi_isz", false));
                if (plan != null) {
                    com.particlesdevs.photoncamera.control.ZoomController.overrideResidual(plan.residual);
                    // P41: the first request of a session already carries its mode; only a change inside the session re-measures
                    if (plan.isz != wasIsz && (wasActive || plan.isz)) onSensorModeChangedInSession(plan.isz);
                    // a toggle held back by the debounce happens once it expires, also when the slider stopped
                    Handler handler = mBackgroundHandler;
                    if (builder == mPreviewRequestBuilder && XiaomiTeleZoom.togglePending(moduleZoom, zoom) && handler != null) {
                        // one retry at a time, and only for this session (not after a module switch or a closed camera)
                        mXiaomiToggleGeneration = mSessionGeneration.get();
                        handler.removeCallbacks(mXiaomiToggleRetry);
                        handler.postDelayed(mXiaomiToggleRetry, XiaomiTeleZoom.TOGGLE_MS);
                    }
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

    /** P41: the deferred ISZ toggle of the Xiaomi tele; dropped when the session it was posted for is gone. */
    private volatile int mXiaomiToggleGeneration = -1;
    private final Runnable mXiaomiToggleRetry = () -> {
        if (isCameraResumed && mXiaomiToggleGeneration == mSessionGeneration.get() && XiaomiTeleZoom.active()) onZoomChanged();
    };

    /** P41: the result that carries the tele's own focal length (its physical result for a physical stream of a logical camera). */
    private CaptureResult xiaomiLensResult(TotalCaptureResult result) {
        if (Build.VERSION.SDK_INT >= 28 && !Objects.equals(physicalID, logicalID)) {
            try {
                CaptureResult physical = result.getPhysicalCameraResults().get(physicalID);
                if (physical != null) return physical;
            } catch (RuntimeException ignored) {
                // no physical results: the logical one
            }
        }
        return result;
    }

    /**
     * The stream changed its sensor mode inside the session (Xiaomi ISZ): the buffered ZSL frames belong to the other mode and
     * are dropped as on a module switch, and the colour block of the stream is measured again (ISZ may deliver a Quad mosaic).
     */
    private void onSensorModeChangedInSession(boolean isz) {
        clearZslPreviewFrames();
        // P41: the frames already in flight still come from the old mode (the 17U log measured the old mode's block for the
        // new one and stored it). Until a frame of the new mode arrives, RAW frames neither enter the ZSL ring nor the
        // colour-block measurement; the measurement of the new mode starts with that frame.
        mModeBarrierKey = mosaicStreamKey() + (isz ? "|isz" : "");
        mModeBarrierFrames = 0;
        mModeBarrierMatching = 0;
        mModeBarrier = true;
        // the new mode's key at once (a shot meanwhile finds its stored block); no frame is measured before the barrier opens
        com.particlesdevs.photoncamera.processing.MosaicStream.startSession(mMosaicMeasure ? mModeBarrierKey : "off");
        // P41: the Xiaomi tele's ISP preview outside ISZ; in ISZ (raw colour mosaic from the ISP) the developed RAW viewfinder,
        // at once when this mode's block is stored, else once the measurement finds it
        if (mMosaicPreview && (XiaomiTeleZoom.ispPreview() || com.particlesdevs.photoncamera.processing.MosaicStream.block() <= 1)) {
            mMosaicPreview = false;
            LiveRawFrame.setMosaicPreview(false);
            if (!mLiveRawSession) LiveRawFrame.setEnabled(false);
        } else if (!mMosaicPreview && !mLiveRawSession && !mIsRecordingVideo && mMosaicMeasure && !XiaomiTeleZoom.ispPreview()
                && com.particlesdevs.photoncamera.processing.MosaicStream.block() > 1) {
            mMosaicPreview = true;
            LiveRawFrame.setEnabled(true);
            LiveRawFrame.setMosaicPreview(true);
            Log.i(TAG, "ISZ: stored colour block " + com.particlesdevs.photoncamera.processing.MosaicStream.block() + ", developed RAW viewfinder");
        }
        Log.i(TAG, "sensor mode changed in session (ISZ " + (isz ? "on" : "off") + "): ZSL ring dropped, waiting for the first frame of the new mode");
    }

    /** P41: RAW frames after an in-session sensor mode change are dropped until one of the new mode arrives. */
    private volatile boolean mModeBarrier;
    private volatile String mModeBarrierKey = "";
    private int mModeBarrierFrames, mModeBarrierMatching;

    /** True when the RAW frame may be used (no barrier, or the first frame of the new mode releases it). */
    private boolean passModeBarrier(Image img) {
        return passModeBarrier(img, null);
    }

    /** {@code matched}: the frame's own result when the caller has it (live RAW matcher). */
    private boolean passModeBarrier(Image img, TotalCaptureResult matched) {
        if (!mModeBarrier) return true;
        TotalCaptureResult r = matched;
        if (r == null) synchronized (mZslBufferLock) { r = mHexZslResults.get(img.getTimestamp()); }
        // an image whose result has not arrived yet is newer than the latest completed result: that one stands for it
        CaptureResult latest = mPreviewCaptureResult;
        if (r == null && latest instanceof TotalCaptureResult) r = (TotalCaptureResult) latest;
        mModeBarrierFrames++;
        if (XiaomiTeleZoom.requestMatches(r)) mModeBarrierMatching++;
        if (!XiaomiTeleZoom.frameReady(r, mModeBarrierFrames, mModeBarrierMatching)) return false;
        mModeBarrier = false;
        Log.i(TAG, "first frame of the new sensor mode after " + mModeBarrierFrames + " frames"
                + (r == null ? " (no result)" : " (request mode " + XiaomiTeleZoom.requestMode(r) + ", reported " + XiaomiTeleZoom.resultMode(r) + ")"));
        return true;
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
            if (mVivoSdkRawSession != null) applyVivoSdkPreview(mPreviewRequestBuilder);
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
        int rotation = ScameraApp.getGravity().getRotation();//activity.getWindowManager().getDefaultDisplay().getRotation();
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
        updatePreviewMirror();
    }

    /**
     * Orientation and mirror of the viewfinder for the camera being opened. P36: they apply from the new camera's first
     * frame (GLPreview / MainRenderer), not to the previous camera's frame still on screen.
     */
    private void updatePreviewMirror() {
        if (mTextureView == null || mCameraCharacteristics == null) {
            return;
        }
        Integer facing = mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
        boolean mirror = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        mTextureView.setViewfinderTransform(mSensorOrientation + 90, mirror);
    }

    private ArrayList<Size> getAllTargets(){
        CameraCharacteristics characteristics =  this.mCameraCharacteristicsMap.get(physicalID);
        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        ArrayList<Size> allTargets = new ArrayList<>();

        Size[] targetSizes = map.getOutputSizes(mTargetFormat);
        if(targetSizes != null)
            allTargets.addAll(Arrays.asList(targetSizes));
        return allTargets;
    }
    @SuppressLint("MissingPermission")
    /**
     * Preview stall watchdog, every second on the camera thread while a session runs: the camera stopped delivering preview
     * frames (black viewfinder, vivo 5x tele) while no shot is in flight: log the state and restart the camera, as switching the
     * module did by hand. At most PreviewStall.MAX_RESTARTS restarts in a row without a frame in between.
     */
    /**
     * A dead device: the preview-stall watchdog stops (it restarted the camera behind the error recovery and past its limit on
     * the OPPO Find X9 Ultra, 2026-10-10), and the session is dropped so no control reaches the closed device.
     */
    private void stopStallWatch() {
        mPreviewSessionStartMs = 0;
        if (mBackgroundHandler != null) mBackgroundHandler.removeCallbacks(mStallCheck);
        mCaptureSession = null;
    }

    private void checkPreviewStall() {
        final Handler handler = mBackgroundHandler;
        if (handler == null || !isCameraResumed || mPreviewSessionStartMs == 0) return;
        handler.postDelayed(mStallCheck, 1000);
        final CameraMode mode = ScameraApp.getSettings().selectedMode;
        if (mIsRecordingVideo || mShotInProgress || mZslCapturing || mHybridZslCapture || mScamRingFrozen || burst
                || (mode != CameraMode.PHOTO && mode != CameraMode.NIGHT && mode != CameraMode.MOTION)) return;
        final long now = android.os.SystemClock.elapsedRealtime();
        // A shot stops or holds the preview for a moment on some routes: none of that counts.
        if (sTimelineSubmitNs > 0 && now - sTimelineSubmitNs / 1_000_000L < 6000) return;
        if (!PreviewStall.stalled(now, mLastPreviewResultMs, mPreviewSessionStartMs, mPreviewFrameMs)) return;
        final long noFrame = now - Math.max(mLastPreviewResultMs, mPreviewSessionStartMs);
        Log.w(TAG, "PREVIEW_STALL camera " + physicalID + ": no preview frame for " + noFrame + " ms ("
                + (mLastPreviewResultMs == 0 ? "none since the session started" : "frames stopped") + ", session "
                + (now - mPreviewSessionStartMs) + " ms) liveRaw=" + mLiveRawSession + " mosaicPreview=" + mMosaicPreview
                + " block=" + com.particlesdevs.photoncamera.processing.MosaicStream.block() + " scamRouted=" + mScamRouted
                + " frameMs=" + mPreviewFrameMs + " restartsInARow=" + mStallRestarts);
        // P186: a native FULLRES session that never gave a frame runs a mode the camera cannot: FULLRES goes off (its mode is
        // noted on the X100 / X300 Ultra) instead of the same session again and again
        if (mLastPreviewResultMs == 0 && mVivoSdkRawSession != null && nativeVivoFullRes()
                && retryWithoutVivoSdkFullRes(mCaptureSession, "no preview frame for " + noFrame + " ms")) {
            mPreviewSessionStartMs = 0;
            handler.removeCallbacks(mStallCheck);
            return;
        }
        if (!PreviewStall.mayRestart(mStallRestarts)) {
            mPreviewSessionStartMs = 0;   // one log line, no more restarts until the next session
            Log.w(TAG, "PREVIEW_STALL: " + mStallRestarts + " restarts brought no frame, leaving the camera as it is");
            return;
        }
        // P41b: a stalled logical session steps down (a regular session, then the tele alone) before the restart
        if (mXiaomiRouted) XiaomiTeleZoom.stepDownRoute("preview stall, no frame for " + noFrame + " ms", mLastPreviewResultMs != 0);
        mStallRestarts++;
        mPreviewSessionStartMs = 0;
        handler.removeCallbacks(mStallCheck);
        activity.runOnUiThread(() -> {
            if (!isCameraResumed) return;
            Log.w(TAG, "PREVIEW_STALL: restarting the camera");
            restartCamera();
        });
    }

    public void restartCamera() {
        Log.d(TAG, "restartCamera() called from \"" + Thread.currentThread().getName() + "\" Thread");
        // Reuse the normal resume path: it prepares readers/outputs before
        // opening the device on a live handler. The old restart opened first,
        // with a null handler, racing onOpened against reader replacement.
        if (mIsRecordingVideo) VideoEnd();
        closeCamera();
        if (mCharacteristicsIncomplete) fillInCameraCharacteristics();
        mLiveRawRejected = false;
        cameraEventsListener.onCameraRestarted();
        startBackgroundThread();
        resumeCamera();
    }
    private Size getAspect(CameraMode targetMode){
        Size aspectRatio;
        if (targetMode == CameraMode.VIDEO || targetMode == CameraMode.RAWVIDEO || ScameraApp.getSettings().aspect169) {
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
    /** P120: ImageReader.newInstance aborts the process natively at 63 images (SCAM TEST: X300 Ultra, nubia NX563J); a session takes 59. */
    static final int MAX_READER_IMAGES = 59;

    /** P87: the physical camera the session's RAW and characteristics come from (the manual rulers' range is kept per camera). */
    public String physicalId() {
        return physicalID;
    }
    private String logicalID = "";

    /**
     * Opens the camera specified by {@link Settings#mCameraID}.
     */
    private void prepareVivoSdkRawSession() throws Exception {
        if (mVivoSdkRestorePending) closeVivoSdkRawSession(null);
        // P179: a FULLRES lease left by a process of ours that died (LOW_MEMORY) is given back before a camera opens
        // (any phone of the native route, its switch on or off: a lease of a session that was killed must come back)
        if (mVivoSdkRawSession == null && VivoNativeFullRes.phone() != null) VivoSdkRawSession.recoverStale(ScameraApp.getAppContext());
        if (!mVivoSdkClosed.await(2000, TimeUnit.MILLISECONDS))
            throw new IllegalStateException("Previous Vivo SDK camera has not closed");
        String id = ScameraApp.getSettings().mCameraID;
        CameraMode mode = ScameraApp.getSettings().selectedMode;
        // P192: an OPPO camera's table from the characteristics the app reads (its own CameraManager is not used); P214j: OnePlus
        // and realme too
        if (OppoNativeFullRes.family()) {
            CameraCharacteristics known = mCameraCharacteristicsMap.get(id);
            if (known == null) try { known = mCameraManager.getCameraCharacteristics(id); } catch (Exception ignored) { known = null; }
            OppoNativeFullRes.learn(id, known);
        }
        if (!VivoSdkFullRes.available(id) || !PixelPro10.fullResOn()
                || !PreferenceKeys.isScamEnabled()
                || !VivoSdkFullRes.supportsMode(mode) || mIsRecordingVideo) return;
        synchronized (mVivoSdkLock) {
            // P192: the OPPO Find X7 Ultra's native modes need no vivo SDK lease
            mVivoSdkRawSession = VivoSdkFullRes.oppo() ? VivoSdkRawSession.inert() : VivoSdkRawSession.open();
            mVivoSdkCameraId = id;
            mVivoSdkRestorePending = false;
            mVivoSdkRestoreAttempts = 0;
            mVivoSdkClosed = new java.util.concurrent.CountDownLatch(1);
            mVivoSdkCallbackThread = new HandlerThread("VivoSdkCameraClose");
            mVivoSdkCallbackThread.start();
            mVivoSdkCallbackHandler = new Handler(mVivoSdkCallbackThread.getLooper());
            mCameraCharacteristicsMap.remove(id);
        }
        // P198: a native 50 MP session takes its shots' N from a ZSL ring of its frames; 200 MP and the SDK route stay serial PSL
        final String route = nativeZslSession() ? "ZSL ring + the Hybrid plan's frames after the shutter" : "serial PSL";
        Log.i("SCAM_CAPTURE", (nativeVivoFullRes() ? "NATIVE FULLRES" : "SDK FULLRES50") + " camera=" + id + " size=" + VivoSdkFullRes.profile(id).size
                + (VivoSdkFullRes.oppo() ? ": own RAW AE and white balance (the mode's HAL has no statistics), " + route + ", SHDR processing"
                : ": automatic AE, " + route + ", SHDR processing"));
    }

    /**
     * P198 (owner, 2026-10-17: «Так же сделай так чтобы 50мп снимались в zsl и только 200мп снимались в psl»): the running session
     * is a native FULLRES one of up to 100 MP (VivoSdkFullResCapture.zslRoute): its frames go to the ZSL ring and a shot takes its N
     * from there, as the 12 MP Hybrid. scam_dev.txt "fullres_zsl 0": the serial PSL series of every native size, as before.
     */
    private boolean nativeZslSession() {
        if (mVivoSdkRawSession == null || !PreferenceKeys.isScamEnabled() || !PreferenceKeys.isFullRes50Zsl()) return false;
        return VivoSdkFullResCapture.zslRoute(VivoSdkFullRes.profile(mVivoSdkCameraId));
    }

    /** P198: the N frames a native 50 MP shot asks of the ring (P190's «Кадров в снимке 50 Мп», 0 = the merge's frame setting). */
    private static int fullResZslWanted() {
        return VivoSdkFullResCapture.zslRequested(PreferenceKeys.getFullRes50Frames(), PreferenceKeys.getHybridZslFrames());
    }

    /** The FULLRES session runs the native route: a Quad / Tetra row, or P194's hardware-remosaic Bayer row (X100 / X300 Ultra). */
    private boolean nativeVivoFullRes() {
        VivoSdkFullRes.Profile profile = mVivoSdkRawSession == null ? null : VivoSdkFullRes.profile(mVivoSdkCameraId);
        return profile != null && profile.nativeRoute();
    }

    /** P208: the native FULLRES session keeps its viewfinder in the normal mode, the full-size stream takes the stills only. */
    private boolean stillOnlyFullRes() {
        VivoSdkFullRes.Profile profile = mVivoSdkRawSession == null ? null : VivoSdkFullRes.profile(mVivoSdkCameraId);
        return profile != null && profile.nativeRoute() && profile.stillOnly;
    }

    private void closeVivoSdkRawSession(@Nullable CameraDevice device) {
        synchronized (mVivoSdkLock) {
            if (mVivoSdkRawSession == null || device != mVivoSdkRawDevice) return;
            try {
                mVivoSdkRawSession.close();
                mVivoSdkRawSession = null;
                mNativeAe = null;
                OppoNativeFullRes.end();
                mVivoSdkRawDevice = null;
                mVivoSdkRestorePending = false;
                mCameraCharacteristicsMap.remove(mVivoSdkCameraId);
                mVivoSdkCameraId = null;
                mVivoSdkClosed.countDown();
                if (mVivoSdkCallbackThread != null) mVivoSdkCallbackThread.quitSafely();
                mVivoSdkCallbackThread = null;
                mVivoSdkCallbackHandler = null;
            } catch (Exception failure) {
                mVivoSdkRawDevice = null;
                mVivoSdkRestorePending = true;
                Log.e(TAG, "SDK provider properties not restored: " + failure);
                if (++mVivoSdkRestoreAttempts <= 3 && mVivoSdkCallbackHandler != null)
                    mVivoSdkCallbackHandler.postDelayed(() -> closeVivoSdkRawSession(null), 500);
                else showError(Lang.t("Не удалось восстановить параметры SDK камеры", "Camera SDK properties could not be restored"));
            }
        }
    }

    public void openCamera(int width, int height) {
        // Both the SurfaceTexture listener and resumeCamera() can request an
        // open for the same surface lifecycle event; a second open while one is
        // in flight fails with CAMERA_IN_USE and kills the preview.
        if (mCameraDevice != null || !mCameraOpening.compareAndSet(false, true)) {
            Log.d(TAG, "openCamera(): an open is already in flight, skipping");
            return;
        }
        if (processExecutor.isShutdown()) {
            // This controller's fragment is destroyed (activity recreated); never open a camera nobody closes.
            mCameraOpening.set(false);
            Log.d(TAG, "openCamera(): controller already destroyed, skipping");
            return;
        }
        //Open camera in non ui thread
        processExecutor.execute(()->{
            CameraFragment.mSelectedMode = ScameraApp.getSettings().selectedMode;
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
            try {
                prepareVivoSdkRawSession();
            } catch (Exception failure) {
                mCameraOpening.set(false);
                if (VivoSdkFullRes.offered(ScameraApp.getSettings().mCameraID)) PixelPro10.setFullRes(false);
                closeVivoSdkRawSession(null);
                Log.e(TAG, "SDK camera setup failed: " + failure);
                cameraEventsListener.onProcessingError(failure.getMessage());
                return;
            }
            // Attaching the stock AE observer takes seconds; start it with the
            // camera instead of on the first SCAM preview frame.
            if (mVivoSdkRawSession == null && PreferenceKeys.isScamEnabled() && PreferenceKeys.useStockBracketPlanner())
                ScamStockAe.warmUp(ScameraApp.getAppContext());
            try {
                cameraEventsListener.onOpenCamera(this.mCameraManager);
                // The package spoof first: a module only the spoof lists has no characteristics without it.
                com.particlesdevs.photoncamera.capture.spoof.CameraPackageSpoof.apply(activity, mCameraManager);
                setUpCameraOutputs(width, height);
                if (mVivoSdkRawSession != null && (mCameraCharacteristics == null || mImageReaderRaw == null
                        || !nativeVivoFullRes() && mVivoSdkJpeg == null))
                    throw new IllegalStateException("SDK RAW outputs are incomplete");
                configureTransform(width, height);
            } catch (RuntimeException failure) {
                mCameraOpening.set(false);
                if (mVivoSdkRawSession != null) PixelPro10.setFullRes(false);
                closeVivoSdkRawSession(null);
                Log.e(TAG, "Camera outputs failed before open: " + failure);
                cameraEventsListener.onProcessingError(failure.getMessage());
                return;
            }
            if (!isCameraResumed) {
                // The app was backgrounded while outputs were being set up.
                mCameraOpening.set(false);
                closeVivoSdkRawSession(null);
                Log.d(TAG, "openCamera(): app backgrounded during setup, skipping");
                return;
            }
            boolean openLockAcquired = false;
            try {
                if (!mCameraOpenCloseLock.tryAcquire(1000, TimeUnit.MILLISECONDS)) {
                    mCameraOpening.set(false);
                    throw new RuntimeException("Time out waiting to lock camera opening.");
                }
                openLockAcquired = true;
                physicalID = ScameraApp.getSettings().mCameraID;
                logicalID = ScameraApp.getSettings().mCameraID;
                // Split x-y, x - logical, y - physical
                if(ScameraApp.getSettings().mCameraID.contains("-")){
                    String[] ids = ScameraApp.getSettings().mCameraID.split("-");
                    logicalID = ids[0];
                    physicalID = ids[1];
                }
                routeXiaomiTele();
                if (mXiaomiRouted) Log.i(TAG, "camera " + physicalID + " opens through logical camera " + logicalID + " (P41b)");

                // The retry budget belongs to one camera: switching modules during a provider restart used it up for the next one.
                if (!logicalID.equals(mOpenRetryId)) { mOpenRetryId = logicalID; mOpenRetries = 0; }
                this.mCameraManager.openCamera(logicalID, mStateCallback,
                        mVivoSdkRawSession != null ? mVivoSdkCallbackHandler : mBackgroundHandler);
                mOpenRetries = 0;
            } catch (CameraAccessException e) {
                mCameraOpening.set(false);
                closeVivoSdkRawSession(null);
                // No device callback follows a refused open: the lock taken above is released here (it used to stay held, so
                // the next open waited 1 s and failed with "Time out waiting to lock camera opening").
                if (openLockAcquired) mCameraOpenCloseLock.release();
                Log.e(TAG, Log.getStackTraceString(e));
                if (retryWithoutXiaomiRoute(width, height, e)) return;
                // CAMERA_ERROR / CAMERA_DISCONNECTED while the provider restarts: the same retry as an unknown device.
                if (e.getReason() == CameraAccessException.CAMERA_ERROR || e.getReason() == CameraAccessException.CAMERA_DISCONNECTED)
                    retryOpen(width, height, e);
            } catch (IllegalArgumentException e) {
                // "Unknown device" while the vendor camera provider restarts after a HAL
                // crash: the device list comes back a few seconds later. Retry instead of
                // taking the app down with the HAL.
                mCameraOpening.set(false);
                closeVivoSdkRawSession(null);
                if (openLockAcquired) mCameraOpenCloseLock.release();
                if (retryWithoutXiaomiRoute(width, height, e)) return;
                retryOpen(width, height, e);
            } catch (InterruptedException e) {
                mCameraOpening.set(false);
                closeVivoSdkRawSession(null);
                if (openLockAcquired) mCameraOpenCloseLock.release();
                throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
            } catch (RuntimeException e) {
                mCameraOpening.set(false);
                closeVivoSdkRawSession(null);
                if (openLockAcquired) mCameraOpenCloseLock.release();
                cameraEventsListener.onProcessingError(e.getMessage());
                Log.e(TAG, Log.getStackTraceString(e));
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
        final boolean sdkFullRes = mVivoSdkRawSession != null;
        if (sdkFullRes) { mTargetFormat = ImageFormat.RAW_SENSOR; useMaximumResolutionKey = false; }
        if (sDemotedFrom != 0) { // the previous camera ran without a RAW stream: this one starts from the RAW format again
            if (mTargetFormat == YUV_FORMAT) mTargetFormat = sDemotedFrom;
            sDemotedFrom = 0;
        }
        if (isRawFormat(mTargetFormat)) {
            final boolean noRawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) == null && map.getOutputSizes(ImageFormat.RAW10) == null
                    && map.getOutputSizes(ImageFormat.RAW12) == null;
            if (noRawSizes || sRawFallback.getOrDefault(physicalID, 0) >= 2) {
                Log.w(TAG, "camera " + physicalID + ": no RAW stream (" + (noRawSizes ? "the camera lists no RAW size"
                        : "its RAW sessions failed to configure") + "), YUV session");
                sDemotedFrom = mTargetFormat;
                mTargetFormat = YUV_FORMAT;
            }
        }
        if (isRawFormat(mTargetFormat)) {
            int resolved = resolveRawFormat(map);
            Log.i(TAG, "RAW stream format " + mTargetFormat + " -> " + resolved + " (setting " + PreferenceKeys.getRawStreamFormat()
                    + ", camera " + physicalID + ")");
            mTargetFormat = resolved;
        }
        // Packed RAW10 / RAW12 samples onto the camera's declared levels (Pixel 10 Pro XL main: white 4095 over RAW10)
        {
            Integer declaredWhite = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            int dw = declaredWhite == null ? 0 : declaredWhite;
            int s10 = com.particlesdevs.photoncamera.util.RawUnpack.shiftFor(ImageFormat.RAW10, dw);
            int s12 = com.particlesdevs.photoncamera.util.RawUnpack.shiftFor(ImageFormat.RAW12, dw);
            com.particlesdevs.photoncamera.util.RawUnpack.setPackedShift(s10, s12);
            if (s10 > 0 || s12 > 0) Log.i(TAG, "camera " + physicalID + ": declared white " + dw + ", packed RAW10 << " + s10 + ", RAW12 << " + s12);
        }
        // P108: the Pixel 10 Pro / Pro XL's full-resolution switch: the RAW stream of the max-resolution map (RAW10 8160x6144)
        mFullResSession = false;
        Size fullRes = sdkFullRes ? VivoSdkFullRes.profile(mVivoSdkCameraId).size : null;
        if (sdkFullRes && VivoSdkFullRes.oppo()) {
            // P192: the own AE of the OPPO native mode meters the native RAW. P212b: it starts from this camera's own history
            // (NativeAeSeed: its last settled native exposure or a fresh plain RAW sample, else its plain HAL exposure), never
            // from the global mPreviewExposureTime that any camera's result overwrites
            final NativeRawExposure ae = new NativeRawExposure(characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE), OppoNativeFullRes.frameNs(mVivoSdkCameraId), 0L, 0);
            // P212a: the ISO the mode delivers, stored by earlier sessions (the key CC uses for mExposureLimits below: the physical
            // camera and the RAW reader's size, which is the profile's size in the native session), so the first split is capped
            final int cap = PreferenceKeys.scamDevSwitch("native_ae_cap", true)
                    ? ExposureLimits.of(String.valueOf(physicalID), fullRes.getWidth(), fullRes.getHeight(), false).isoCap() : Integer.MAX_VALUE;
            ae.isoCap(cap);
            ae.flicker(PreferenceKeys.getAntibandingHz(), nativeFlickerKnown()); // P209; P212d: a guess bands least at both
            ae.smoothing(PreferenceKeys.scamDevSwitch("native_ae_smooth", true)); // P212c
            final NativeAeSeed.Seed seed = PreferenceKeys.scamDevSwitch("native_ae_seed", true)
                    ? NativeAeSeed.pick(mVivoSdkCameraId, nativeAeEv(characteristics), android.os.SystemClock.elapsedRealtime(),
                    System.currentTimeMillis())
                    : new NativeAeSeed.Seed(mPreviewExposureTime > 0 && mPreviewIso > 0 ? (double) mPreviewExposureTime * mPreviewIso / 100.0 : 0,
                    "the last preview exposure (native_ae_seed 0)");
            ae.seed(seed.total);
            mNativeAeStartMs = android.os.SystemClock.elapsedRealtime();
            mNativeAeSettledMs = 0;
            mNativeAeStoredMs = 0;
            mNativeAeStoredTotal = 0;
            mNativeAe = ae;
            OppoNativeFullRes.begin(mVivoSdkCameraId, ae);
            Log.i("SCAM_CAPTURE", "OPPO native FULLRES camera " + mVivoSdkCameraId + ": mode "
                    + VivoSdkFullRes.profile(mVivoSdkCameraId).sensorMode + ", own AE from " + ae + " (" + seed.source
                    + (cap == Integer.MAX_VALUE ? "" : ", ISO cap " + cap) + ")");
        }
        if (!sdkFullRes && isRawFormat(mTargetFormat) && !sFullResRefused.contains(physicalID) && !mIsRecordingVideo) {
            PixelPro10.FullRes fr = PixelPro10.fullRes(characteristics);
            if (fr != null) {
                mTargetFormat = fr.format;
                fullRes = fr.size;
                mFullResSession = true;
                Log.i("SCAM_CAPTURE", "camera " + physicalID + ": full resolution " + fr.size + " format " + fr.format
                        + " (every stream and request in MAXIMUM_RESOLUTION pixel mode, ZSL ring on, RAW viewfinder binned)");
            }
        }
        ArrayList<Size> allTargets = getAllTargets();
        // P108: every request of the full-resolution session in MAXIMUM_RESOLUTION pixel mode (VendorTagUtils.builderSessionApply)
        if (mFullResSession) useMaximumResolutionKey = true;

        Size preview = getCameraOutputSize(map.getOutputSizes(mPreviewTargetFormat));

        int maxjpg = 3;
        // The raw viewfinder consumes from this reader continuously, so it
        // needs its own headroom even when ZSL is off.
        if (PreferenceKeys.isLiveViewfinderRawEnabled()) maxjpg = 6;
        if (isZslMode())
            // The ring buffer can hold up to zslRingCapacity() frames, and the
            // reader has to have room for all of them plus the in-flight ones,
            // otherwise acquireNextImage starves once the ring is full.
            // P91: with the ring in the app's memfd the reader only passes frames through (copied on arrival) and holds the
            // post-shutter series: scam_dev "zsl_reader" (default 20; LMC keeps 50, ArkCam 1.05 min(max(2 x frames, 8), 48))
            // A YUV ring (no RAW) still holds the camera's Images: reader = ring + 3, as before.
            maxjpg = PreferenceKeys.scamDevSwitch("zsl_memfd_ring", true) && isRawFormat(mTargetFormat)
                    // floor 16: the Galaxy S24 Ultra's RAW stream ran at 15 fps with 8 reader buffers, 28 with 12, 30 from 16 (SCAM
                    // TEST ring depth search, 2026-05-18); the «Движение» ring of 8 frames would otherwise get a reader of 11
                    ? Math.max(16, Math.min(Math.min(zslRingCapacity() + 3, MAX_READER_IMAGES), (int) PreferenceKeys.scamDevNumber("zsl_reader", 20f)))
                    : Math.min(zslRingCapacity() + 3, MAX_READER_IMAGES);
        Size target = getCameraOutputSize(allTargets.toArray(new Size[0]), preview);
        // Diagnostics: the vivo X200 Pro's 4096x3072 RAW holds its image in 4000x3000 (zeros elsewhere, black stripe at the edge);
        // whether the arrays say so decides how a crop to the valid area can be done.
        Log.i(TAG, "camera " + physicalID + " arrays: pixel=" + characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                + " active=" + characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                + " preCorrection=" + characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                + " level=" + characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                + " capabilities=" + Arrays.toString(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES))
                + " target=" + target + " format=" + mTargetFormat
                + " rawSizes=" + Arrays.toString(isRawFormat(mTargetFormat) ? rawSizesLargestFirst(map, mTargetFormat) : new Size[0]));
        if (!isRawFormat(mTargetFormat) && sSafeStreams.getOrDefault(physicalID, 0) >= 2) {
            final Size safe = SafeStreamSizes.capped(map.getOutputSizes(mTargetFormat), target, SafeStreamSizes.PREVIEW_AREA);
            Log.w(TAG, "camera " + physicalID + ": YUV " + safe + " instead of " + target + " (session fallback)");
            target = safe;
            this.target = safe;
        }
        final Size rawAlt = isRawFormat(mTargetFormat) && sRawFallback.getOrDefault(physicalID, 0) == 1 ? sRawSizeAlt.get(physicalID) : null;
        if (rawAlt != null) {
            Log.w(TAG, "camera " + physicalID + ": RAW " + rawAlt + " instead of " + target + " (session fallback)");
            target = rawAlt;
            this.target = rawAlt;
        }
        Size aspect = getAspect(ScameraApp.getSettings().selectedMode);
        if(preview.getWidth() > preview.getHeight())
            preview = new Size(preview.getWidth(),preview.getWidth()*aspect.getWidth()/aspect.getHeight());
        else {
            preview = new Size(preview.getHeight()*aspect.getWidth()/aspect.getHeight(),preview.getHeight());
        }
        if(mImageReaderPreview != null)
            mImageReaderPreview.close();

        if (mFullResSession) {
            // P108: a size of the max-resolution map (the stream is a MAXIMUM_RESOLUTION one), the RAW's aspect
            Size fullPreview = PixelPro10.maxResSize(characteristics, mPreviewTargetFormat, fullRes);
            if (fullPreview != null) preview = fullPreview;
            maxjpg = Math.min(maxjpg, 8); // 50 MP frames: a short reader, every frame is copied on arrival (P91)
        }
        mImageReaderPreview = null;
        if (!sdkFullRes) {
            mImageReaderPreview = ImageReader.newInstance(preview.getWidth(), preview.getHeight(), mPreviewTargetFormat, maxjpg);
            mImageReaderPreview.setOnImageAvailableListener(mOnYuvImageAvailableListener, mBackgroundHandler);
        }
            mBufferSize = getPreviewOutputSize(getSafeDisplay(),characteristics,ScameraApp.getSettings().selectedMode);
        if (sSafeStreams.getOrDefault(physicalID, 0) >= 1 && mBufferSize != null) {
            // mBufferSize is portrait (short x long); the stream sizes are sensor-oriented
            final Size sensor = new Size(Math.max(mBufferSize.getWidth(), mBufferSize.getHeight()),
                    Math.min(mBufferSize.getWidth(), mBufferSize.getHeight()));
            final Size safe = SafeStreamSizes.capped(map.getOutputSizes(SurfaceTexture.class), sensor, SafeStreamSizes.PREVIEW_AREA);
            Log.w(TAG, "camera " + physicalID + ": preview " + safe + " instead of " + sensor + " (session fallback)");
            mBufferSize = new Size(safe.getHeight(), safe.getWidth());
        }

        if (fullRes != null) {
            target = fullRes;
            this.target = fullRes;
            // P108: the viewfinder stream too is a size of the max-resolution map (Pixel 10 Pro XL: 1440x1080 for 4:3)
            Size st = sdkFullRes ? new Size(1440, 1080) : PixelPro10.maxResSize(characteristics, -1, fullRes);
            if (st != null) mBufferSize = new Size(Math.min(st.getWidth(), st.getHeight()), Math.max(st.getWidth(), st.getHeight()));
            Log.i("SCAM_CAPTURE", "full resolution: viewfinder " + st + ", YUV " + preview + ", readers " + maxjpg);
        }
        clearZslPreviewFrames();
        if(mImageReaderRaw != null)
            mImageReaderRaw.close();
        if (mImageReaderRawView != null) { mImageReaderRawView.close(); mImageReaderRawView = null; }
        if (mVivoSdkJpeg != null) { mVivoSdkJpeg.close(); mVivoSdkJpeg = null; }
        if (sdkFullRes) {
            mTargetFormat = ImageFormat.RAW_SENSOR;
            mImageReaderRaw = ImageReader.newInstance(fullRes.getWidth(), fullRes.getHeight(), ImageFormat.RAW_SENSOR,
                    nativeVivoFullRes() ? 4 : 3, 0);
            if (stillOnlyFullRes()) {
                // P208: the viewfinder is the camera's own (largest listed) RAW stream; its frames go the live RAW way
                final Size[] own = rawSizesLargestFirst(map, ImageFormat.RAW_SENSOR);
                if (own.length == 0) throw new IllegalStateException("No RAW stream for the 200 MP viewfinder");
                mImageReaderRawView = ImageReader.newInstance(own[0].getWidth(), own[0].getHeight(), ImageFormat.RAW_SENSOR, 4);
                mImageReaderRawView.setOnImageAvailableListener(reader -> {
                    Image image;
                    try { image = reader.acquireNextImage(); } catch (IllegalStateException closed) { return; }
                    if (image == null) return;
                    if (!isCameraResumed || reader != mImageReaderRawView) { image.close(); return; }
                    mLiveRawRouter.image(image.getTimestamp(), image);
                }, mBackgroundHandler);
                Log.i("SCAM_CAPTURE", "FULLRES 200 MP still-only: viewfinder RAW " + own[0] + " in the normal mode, "
                        + fullRes + " for the stills (P208)");
            }
            if (!nativeVivoFullRes()) {
            mVivoSdkJpeg = ImageReader.newInstance(fullRes.getWidth(), fullRes.getHeight(), ImageFormat.JPEG, 3);
            mVivoSdkJpeg.setOnImageAvailableListener(reader -> {
                try {
                    Image image;
                    while ((image = reader.acquireNextImage()) != null) {
                        long timestamp = image.getTimestamp();
                        image.close();
                        VivoSdkFullResCapture capture = mVivoSdkCapture;
                        if (capture != null) capture.jpegDrained(timestamp);
                    }
                } catch (IllegalStateException closed) { }
            }, mBackgroundHandler);
            }
        } else if (VivoPd2454NativeRaw.active(physicalID)) {
            mTargetFormat = ImageFormat.RAW_SENSOR;
            target = new Size("5".equals(physicalID) ? 4080 : 4096, 3072);
            this.target = target;
            mImageReaderRaw = ImageReader.newInstance(target.getWidth(), target.getHeight(), mTargetFormat, maxjpg);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && VivoPd2454Isz.active(physicalID)) {
            mImageReaderRaw = ImageReader.newInstance(target.getWidth(), target.getHeight(), mTargetFormat, maxjpg, 0);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (!(Build.BRAND.equalsIgnoreCase("oppo")
                || Build.BRAND.equalsIgnoreCase("vivo")
                || Build.BRAND.equalsIgnoreCase("oneplus")
                || Build.BRAND.equalsIgnoreCase("realme")
                || Build.BRAND.equalsIgnoreCase("iqoo")
                || Build.BRAND.equalsIgnoreCase("nothing")
                || Build.BRAND.equalsIgnoreCase("google")
                || Build.BRAND.equalsIgnoreCase("xiaomi")
                || Build.BRAND.equalsIgnoreCase("redmi")
                || Build.BRAND.equalsIgnoreCase("poco")
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
        int displayRotation = ScameraApp.getGravity().getRotation();
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
            mPreviewSize = getTextureOutputSize(getSafeDisplay(), ScameraApp.getSettings().selectedMode);
            mTextureView.setAspectRatio(
                    mPreviewSize.getHeight(), mPreviewSize.getWidth());
            updatePreviewMirror();
            // The fragment may be gone by now (activity recreated, e.g. on a language change).
            final CameraEventsListener listener = cameraEventsListener;
            if (listener != null) listener.onCharacteristicsUpdated(updated);
            if (ScameraApp.getSettings().DebugData)
                showToast("preview:" + new Point(mPreviewWidth, mPreviewHeight));
        });
        //activity.runOnUiThread(() -> cameraEventsListener.onCharacteristicsUpdated(characteristics));
    }
    Surface surface;
    public void createCameraPreviewSession(boolean isBurstSession) {
        final int generation = mSessionGeneration.incrementAndGet();
        final CameraDevice sessionDevice = mCameraDevice;
        if (!isCameraResumed || sessionDevice == null) return;
        final boolean sdkFullRes = mVivoSdkRawSession != null;
        try {
            if (!sdkFullRes) SensorConfigInjector.applyToSensor(physicalID, this);
            recordSessionSettings();
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
            Log.d(TAG, "ID:" + ScameraApp.getSettings().mCameraID + " deviceID:" + mCameraDevice.getId() + " logicalID:" + logicalID + " physicalID:" + physicalID);
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
            boolean photoMode = ScameraApp.getSettings().selectedMode == CameraMode.PHOTO
                    || ScameraApp.getSettings().selectedMode == CameraMode.NIGHT
                    || ScameraApp.getSettings().selectedMode == CameraMode.MOTION;
            VivoQcomModes.ensureModules(ScameraApp.getAppContext());
            VivoPd2454Isz.ensureModules(ScameraApp.getAppContext());
            VivoPd2454NativeRaw.ensureModules(ScameraApp.getAppContext());
            final boolean nativeIsz = VivoPd2454NativeRaw.active(physicalID);
            final boolean vivoQcomIsz = nativeIsz || VivoQcomModes.activeFactor() > 0 || VivoPd2454Isz.active(physicalID);
            // P214i: the X300 Ultra camera's role (by its CameraType or focal length: the declared block of an ISZ module's merge)
            // and the frame check of an ISZ module's session
            CameraCharacteristics iszCamera = mCameraCharacteristicsMap.get(physicalID);
            VivoQcomIsz.learn(physicalID, iszCamera != null ? iszCamera : mCameraCharacteristics);
            mIszCheck = VivoQcomIsz.check(physicalID);
            // Vendor detector tags exist only on a vivo HAL and were recorded on the X200 Ultra, the one vivo phone SCAM HDR runs
            // on (P54: none on the X300 Ultra and other vivo phones); elsewhere SCAM HDR uses the plain preview.
            final boolean scamPreview = PreferenceKeys.isScamEnabled() && photoMode
                    && ScamPreview.vendorKeys() && !isBurstSession && !mIsRecordingVideo && !vivoQcomIsz && !sdkFullRes
                    && !sPlainPreviewCameras.contains(physicalID);
            mScamPreviewActive = scamPreview;
            // P54b: X300 Ultra - stabilisation held on for the whole session (OIS + vivo EIS key in every request, no flush)
            mForcedStab = photoMode && !isBurstSession && !mIsRecordingVideo && !sdkFullRes && ForcedStabilization.active();
            mForcedStabEis = mForcedStab && ForcedStabilization.eisKey() && !sPlainPreviewCameras.contains(physicalID);
            mPayloadFrames = 0;
            mPayloadBad = false;
            mPreviewLayouts.clear();
            mLayoutTs = Long.MIN_VALUE;
            Log.i("SCAM_CAPTURE", "session mode=" + ScameraApp.getSettings().selectedMode
                    + " route=" + (PreferenceKeys.isScamHybridEnabled() ? "SCAM_HYBRID" : PreferenceKeys.isScamEnabled() ? "SCAM_RAW" : "SCAMERA"));
            // RAW_SENSOR, RAW10 and RAW12: packed rows are unpacked for the viewfinder (RawUnpack).
            // P108: the full-resolution session always shows the RAW viewfinder (the Pixel ISP's own one is green in that mode)
            mLiveRawSession = photoMode && !isBurstSession && !mIsRecordingVideo && !mLiveRawRejected && (!sdkFullRes || nativeVivoFullRes())
                    && isRawFormat(mTargetFormat) && (nativeIsz || nativeVivoFullRes() || PreferenceKeys.isLiveViewfinderRawEnabled() || mFullResSession);
            LiveRawFrame.setEnabled(false); // invalidate the previous session even when RAW remains enabled
            LiveRawFrame.setEnabled(mLiveRawSession);
            // P13: a module whose stream was measured as a mosaic before starts on the RAW viewfinder at once
            mMosaicMeasure = PreferenceKeys.scamDevSwitch("mosaic_preview", true);
            com.particlesdevs.photoncamera.processing.MosaicStream.startSession(mMosaicMeasure ? mosaicStreamKey() : "off");
            // P60: a vendor module measures its field of view against the plain module of its camera (cached once measured)
            if (photoMode && !XiaomiTeleZoom.phone() && PreferenceKeys.scamDevSwitch("fov_check", true)) try {
                com.particlesdevs.photoncamera.control.FovSelfCheck.get().session(ScameraApp.getAppContext(), physicalID,
                        com.particlesdevs.photoncamera.settings.ModuleRegistry.active(),
                        com.particlesdevs.photoncamera.settings.TunableKeyManager.signature(physicalID), this::onZoomChanged);
            } catch (RuntimeException e) {
                Log.w(TAG, "FOV self-check: " + e.getMessage());
            }
            updateStreamCrop(false);
            // P35 / P33: the GPU programs of this module's merge route (its stored / declared colour block) built now, not on the shutter
            // (an optimisation only: a failure here must never stop the session from starting)
            if (photoMode && !isBurstSession && !mIsRecordingVideo && PreferenceKeys.isScamHybridEnabled()) try {
                com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralClient.prewarmHybridGpu(ScameraApp.getAppContext(),
                        com.particlesdevs.photoncamera.processing.MosaicBlockStore.blockForModule(
                                com.particlesdevs.photoncamera.processing.MosaicStream.key(),
                                // P214i: the declared block by the X300 Ultra's list (elsewhere 5 -> 2, 7 -> 4 as before)
                                VivoQcomIsz.declaredBlockOf(com.particlesdevs.photoncamera.settings.ModuleRegistry.active())));
            } catch (RuntimeException e) {
                Log.w(TAG, "Hybrid GPU prewarm skipped: " + e);
            }
            mMosaicPreview = mMosaicMeasure && !mLiveRawSession && photoMode && !isBurstSession && !mIsRecordingVideo && isZslMode()
                    && isRawFormat(mTargetFormat) && com.particlesdevs.photoncamera.processing.MosaicStream.block() > 1;
            if (mMosaicPreview) LiveRawFrame.setEnabled(true);
            LiveRawFrame.setMosaicPreview(mMosaicPreview);
            mModeBarrier = false;
            final boolean xiaomiLogical = mXiaomiRouted && !Objects.equals(physicalID, logicalID);
            XiaomiTeleZoom.startSession(xiaomiLogical, PreferenceKeys.scamDevSwitch("xiaomi_tele_fallback", false));
            setCaptureRequestBuilder();
            if (mMosaicPreview && XiaomiTeleZoom.ispPreview()) {
                // P41: the switch owns the tele's sensor mode (a module's tunable current_mode is overridden): ISP preview
                mMosaicPreview = false;
                LiveRawFrame.setMosaicPreview(false);
                if (!mLiveRawSession) LiveRawFrame.setEnabled(false);
            }

            // Here, we create a CameraCaptureSession for camera preview.
            List<Surface> surfaces = configureSurfaces(isBurstSession);
            Log.d(TAG, "createCameraPreviewSession() surfaces:" + Arrays.toString(surfaces.toArray()));
            ArrayList<OutputConfiguration> outputConfigurations = new ArrayList<>();
            boolean croppedRaw = false;
            final Surface rawSurface = mImageReaderRaw == null ? null : mImageReaderRaw.getSurface();
            final Surface yuvSurface = mImageReaderPreview == null ? null : mImageReaderPreview.getSurface();
            for (Surface surfacei : surfaces) {
                var config = new OutputConfiguration(surfacei);
                // P41b: on the Xiaomi logical camera the viewfinder (and a recording) is the logical camera's stream, zoomed and
                // switched by the HAL as for the stock camera; the image readers stay the tele's physical streams
                final boolean vivoLogicalStream = VivoQcomModes.wantsStreamUsage() && "2".equals(logicalID);
                final boolean logicalStream = vivoLogicalStream || xiaomiLogical && surfacei != rawSurface && surfacei != yuvSurface;
                if(!Objects.equals(physicalID, logicalID) && !logicalStream && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P){
                    config.setPhysicalCameraId(physicalID);
                }
                // P100: in-sensor zoom (ISZ) through the RAW stream's CROPPED_RAW use case where the camera lists it; not on the
                // Xiaomi tele (P41 / P41b crop the photo from the HAL's own zoom report: a second crop source would divide twice)
                // P214i: not on an X300 Ultra module that forces a sensor mode (the mode is the sensor crop; the HAL's own ISZ by
                // the zoom ratio may remosaic or override it): its RAW stream is of the default use case
                if (surfacei == rawSurface && mTargetFormat == ImageFormat.RAW_SENSOR && Build.VERSION.SDK_INT >= 34
                        && !xiaomiLogical && !XiaomiTeleZoom.active() && !vivoQcomIsz
                        && !sdkFullRes && VivoQcomIsz.croppedRawAllowed(physicalID)
                        && !sCroppedRawRefused.contains(physicalID) && CroppedRaw.supported(mCameraCharacteristics)) {
                    try {
                        config.setStreamUseCase(CroppedRaw.USE_CASE);
                        croppedRaw = true;
                    } catch (RuntimeException e) {
                        Log.w(TAG, "CROPPED_RAW not set: " + e);
                    }
                }
                // P108: every stream of the full-resolution session (sizes of the max-resolution map) in MAXIMUM_RESOLUTION mode
                if (mFullResSession && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    config.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
                outputConfigurations.add(config);
            }
            mCroppedRawSession = croppedRaw;
            mLastRawCrop = null;
            mLastRawCropZoom = -1f;
            if (croppedRaw) Log.i("SCAM_CAPTURE", "camera " + physicalID + ": RAW stream with CROPPED_RAW (in-sensor zoom from ~2x, P100)");
            final VivoQcomIsz.StreamCheck iszCheck = mIszCheck;
            if (iszCheck != null) Log.i("SCAM_CAPTURE", VivoQcomIsz.describe(iszCheck, croppedRaw));
            if (xiaomiLogical) Log.i(TAG, "P41b: session on logical camera " + logicalID + ": viewfinder logical, readers on camera "
                    + physicalID + ", operation mode 0x" + Integer.toHexString(XiaomiTeleZoom.operationMode(xiaomiOperationModeSetting())));

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
                        final String runningId = ScameraApp.getSettings().mCameraID;
                        if (sGoodCameras.add(runningId) && sRawFallback.getOrDefault(physicalID, 0) > 0) {
                            final int step = sRawFallback.get(physicalID);
                            Log.w(TAG, "camera " + physicalID + " runs with the session fallback " + step);
                            showToast(step == 1 ? Lang.t("Камера " + physicalID + ": RAW " + mImageReaderRaw.getWidth() + "x" + mImageReaderRaw.getHeight(),
                                    "Camera " + physicalID + ": RAW " + mImageReaderRaw.getWidth() + "x" + mImageReaderRaw.getHeight())
                                    : Lang.t("Камера " + physicalID + " без RAW-потока: только обычная съёмка",
                                    "Camera " + physicalID + " has no RAW stream: plain capture only"));
                        }
                        sLastGoodCamera = runningId;
                        mConfiguredSessionGeneration = generation;
                        mLastPreviewResultMs = 0;
                        mPreviewSessionStartMs = android.os.SystemClock.elapsedRealtime();
                        // P212d: the scene-flicker report of a session counts after its first results, never in a native route
                        // with its own RAW viewfinder (no HAL statistics there; the still-only route keeps the normal viewfinder)
                        MainsFlicker.newSession(nativeVivoFullRes() && !stillOnlyFullRes());
                        mVfCcmSession++; // P212z: the native viewfinder's colour matrix is looked up again in this session
                        // P212g (W6): a still-only vivo session's viewfinder neutrals, this session's only
                        mVfNeutrals.clear();
                        mVfNeutralFeed = stillOnlyFullRes() && !VivoSdkFullRes.oppo();
                        if (mBackgroundHandler != null) {
                            mBackgroundHandler.removeCallbacks(mStallCheck);
                            mBackgroundHandler.postDelayed(mStallCheck, 1000);
                        }
                        mPreviewCaptureResult = null;
                        mPreviewCaptureRequest = null;
                        try {
                            // Auto focus should be continuous for camera preview.
                            //mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            // Flash is automatically enabled when necessary.
                            resetPreviewAEMode();
                            applyAeMeteringRegions(mPreviewRequestBuilder);
                            Camera2ApiAutoFix.applyPrev(mPreviewRequestBuilder);
                            if (!sdkFullRes) VendorTagUtils.builderSessionApply(mPreviewRequestBuilder, false, useMaximumResolutionKey, physicalID);
                            XiaomiTeleZoom.applyLast(mPreviewRequestBuilder, physicalID);
                            if (scamPreview) {
                                try {
                                    // Detector controls only: VCF2 JPEG stream usage and
                                    // SnapshotJpegStreamMap do not describe this RAW session.
                                    ScamPreview.applyRepeating(mPreviewRequestBuilder);
                                    Log.i("SCAM_CAPTURE", "preview SCAM AUTO; capture route=Camera2_RAW"
                                            + " stockAeSource=preview_metadata stockPlanApplied=false");
                                } catch (IllegalArgumentException unsupported) {
                                    Log.w("SCAM_CAPTURE", "SCAM preview controls unavailable", unsupported);
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
                            applyVideoStabilization(mPreviewRequestBuilder);
                            if (!sdkFullRes && ScamPreview.supported() && PreferenceKeys.isEisPhotoOn() && !ForcedStabilization.active() && !sPlainPreviewCameras.contains(physicalID)) {
                                ScamPreview.applyPreviewEis(mPreviewRequestBuilder);
                            }
                            if (mForcedStab) Log.i("SCAM_CAPTURE", "forced stabilisation (P54b) camera " + physicalID + ": preview "
                                    + ForcedStabilization.apply(mPreviewRequestBuilder, oisForceable(), mForcedStabEis)
                                    + (ForcedStabilization.noFlush() ? ", no HAL flush on the shot" : "")
                                    + (ForcedStabilization.rawOnlySwitch() ? ", AE restore frame on the RAW stream only (P54c)" : ""));
                            // P38: stabilisation trace of this session; OIS samples only on the dev switch.
                            CameraCharacteristics traceChars = mCameraCharacteristicsMap.get(physicalID);
                            if (traceChars == null) traceChars = mCameraCharacteristics;
                            mStabTrace.startSession(physicalID, traceChars);
                            mPreviewGap.reset();
                            mRearmPending = false;
                            if (StabilizationTrace.wantsOisSamples(traceChars))
                                mPreviewRequestBuilder.set(CaptureRequest.STATISTICS_OIS_DATA_MODE, CaptureRequest.STATISTICS_OIS_DATA_MODE_ON);

                            // Finally, we start displaying the camera preview.
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    scamPreview ? ScamPreview.STOCK_FPS : getSelectedFpsRange());
                            // P86 «Движение»: the fastest fixed rate the RAW stream allows (exposure <= 1/f, ring refreshed most often)
                            Range<Integer> motionFps = motionFpsRange();
                            if (motionFps != null) {
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, motionFps);
                                Log.i("SCAM_CAPTURE", "motion mode: camera " + physicalID + " AE range " + motionFps);
                            }
                            if (sdkFullRes) applyVivoSdkPreview(mPreviewRequestBuilder);
                            mPreviewInputRequest = mPreviewRequestBuilder.build();
                            mCaptureSession.setRepeatingRequest(mPreviewInputRequest,
                                    mCaptureCallback, mBackgroundHandler);
                            unlockFocus();
                        } catch (Exception e) {
                            Log.e(TAG, Log.getStackTraceString(e));
                            if (retryWithoutVivoSdkFullRes(cameraCaptureSession, "preview: " + e.getMessage())) return;
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
                        Log.d(TAG, "CameraCaptureSession onConfigureFailed()");
                        if (retryWithoutVendorMode(cameraCaptureSession)) return; // P211
                        if (retryWithoutVivoSdkFullRes(cameraCaptureSession, "session configuration failed")) return;
                        if (retryWithoutRequestedSensorMode(cameraCaptureSession, "session configuration failed")) return;
                        if (retryXiaomiRoute(cameraCaptureSession, "session configuration")) return;
                        if (retryWithoutCroppedRaw(cameraCaptureSession)) return;
                        if (retryWithoutFullRes(cameraCaptureSession, "session configuration failed")) return;
                        if (retryWithoutLiveRaw(cameraCaptureSession)) return;
                        if (retryWithoutScamSession(cameraCaptureSession)) return;
                        if (retryConfigureFallback(cameraCaptureSession)) return;
                        if (revertToLastGoodCamera("session configuration")) return;
                        showError(activity.getString(R.string.session_on_configure_failed));
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // P211: the module's «Тип сессии камеры» as Android takes it, never in FULLRES, 0 after the camera refused it
                final int vendorMode = sVendorModeRefused.contains(physicalID) ? 0 : sessionOperationMode(sessionType, sdkFullRes || mFullResSession);
                mVendorOperationMode = xiaomiLogical ? 0 : vendorMode;
                if (!xiaomiLogical && sessionType != 0)
                    Log.i("SCAM_CAPTURE", "camera " + physicalID + ": session operation mode " + vendorMode + " (setting " + sessionType
                            + (vendorMode == sessionType ? ")" : sdkFullRes || mFullResSession ? ": FULLRES runs 0)"
                            : sVendorModeRefused.contains(physicalID) ? ": refused by this camera, 0)" : ": not a vendor mode 32768..65535, 0)"));
                SessionConfiguration configuration = new SessionConfiguration(
                        xiaomiLogical ? XiaomiTeleZoom.operationMode(xiaomiOperationModeSetting()) : vendorMode,
                        outputConfigurations,
                        processExecutor,
                        stateCallback
                );
                boolean vivoEis = !sdkFullRes && ScamPreview.supported() && PreferenceKeys.isEisPhotoOn() && !ForcedStabilization.active() && !vivoQcomIsz && !sPlainPreviewCameras.contains(physicalID);
                mVivoEisSession = vivoEis;
                // P54d's stabilisation mode as a session parameter on vivo only: on the Xiaomi 13 Ultra and the OPPO Find X9 Ultra
                // (2026-10-10 logs) every session with session parameters / PREVIEW_STABILIZATION next to the RAW stream stopped
                // delivering frames or killed the HAL (onError 4); before P54d no other brand got session parameters.
                int vsMode = vivoBrand() ? getPreferredVideoStabilizationMode(mCameraCharacteristics, mIsRecordingVideo, PreferenceKeys.isEisPhotoOn())
                        : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
                // P102: vendor keys the owner set on the module («Добавить vendor tag») are session parameters as well (the
                // Motorola Edge 50 Pro's com.lenovo.moto.control.super_zoom is a session key); only when there are such keys
                final boolean moduleKeys = !com.particlesdevs.photoncamera.settings.TunableKeyManager.signature(physicalID).isEmpty();
                // P103: the HAL features of «Движение» and Ideal RAW are session keys
                final boolean hiddenKeys = HiddenFeatures.wantsSession(mCameraCharacteristics);
                final boolean dcg14 = Dcg14Mode.active(mCameraCharacteristics);
                final boolean vivoQcomStreams = VivoQcomModes.wantsStreamUsage();
                com.particlesdevs.photoncamera.processing.RawPayloadCheck.setWideEnabled(PreferenceKeys.scamDevSwitch("raw_wide", true));
                if (sdkFullRes) {
                    CaptureRequest.Builder sdkSession = sessionDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    if (nativeVivoFullRes()) applyVivoSdkPreview(sdkSession);
                    else {
                        mVivoSdkRawSession.apply(sdkSession, false, 0);
                        mVivoSdkRawSession.applyStreamUsage(sdkSession);
                    }
                    configuration.setSessionParameters(sdkSession.build());
                } else if (scamPreview || mForcedStabEis || xiaomiLogical || vivoEis || moduleKeys || hiddenKeys || dcg14 || vivoQcomIsz || vivoQcomStreams
                        || vsMode != CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) {
                    try {
                        CaptureRequest.Builder scamSession = sessionDevice.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW);
                        VendorTagUtils.builderSessionApply(scamSession, false, useMaximumResolutionKey, physicalID);
                        if (vivoQcomStreams) {
                            int[] roles = new int[surfaces.size()];
                            for (int i = 0; i < roles.length; i++) {
                                Surface output = surfaces.get(i);
                                roles[i] = output == rawSurface ? (mIsRecordingVideo ? 12 : 7)
                                        : output == yuvSurface ? 2 : mIsRecordingVideo ? 6 : 1;
                            }
                            Log.i("SCAM_CAPTURE", "vivo Qualcomm stream purposes: "
                                    + VivoQcomModes.applyStreamUsage(scamSession, roles));
                        }
                        if (dcg14) Log.i("SCAM_CAPTURE", "DCG 14 bit on camera " + physicalID + ": "
                                + Dcg14Mode.apply(scamSession, mCameraCharacteristics, physicalID));
                        if (vivoQcomIsz) Log.i("SCAM_CAPTURE", "vivo Qualcomm ISZ session keys on camera " + physicalID + ": "
                                + VivoQcomModes.apply(scamSession, mCameraCharacteristics, physicalID));
                        if (scamPreview) ScamPreview.applySession(scamSession);
                        // P54b: the stock Photo mode sets the EIS key as a session parameter too
                        if (mForcedStabEis) ForcedStabilization.apply(scamSession, false, true);
                        if (vivoEis) ScamPreview.applyPreviewEis(scamSession);
                        if (vsMode != CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) {
                            scamSession.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, vsMode);
                        }
                        // P41b: the stock camera's session keys of the logical camera
                        if (xiaomiLogical) Log.i(TAG, "P41b: logical session keys: " + XiaomiTeleZoom.applySessionKeys(scamSession));
                        // P122: the module's session-only vendor keys (vivo X300 Ultra: forceSensorMode)
                        com.particlesdevs.photoncamera.settings.TunableKeyManager.applySessionKeys(scamSession, physicalID);
                        VivoPd2454NativeRaw.apply(scamSession, physicalID);
                        configuration.setSessionParameters(scamSession.build());
                    } catch (IllegalArgumentException unsupported) {
                        Log.w("SCAM_CAPTURE", "SCAM session control unavailable", unsupported);
                    }
                }
                mCameraDevice.createCaptureSession(configuration);
            } else {
                mCameraDevice.createCaptureSession(surfaces, stateCallback, mBackgroundHandler);
            }
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
            if (retryWithoutVendorMode(null)) return; // P211
            if (retryWithoutVivoSdkFullRes(null, "session: " + e.getMessage())) return;
            if (retryWithoutRequestedSensorMode(null, "session: " + e.getMessage())) return;
            // P41b: a logical session the framework refuses outright (no onConfigureFailed follows) steps down as well
            if (mXiaomiRouted) retryXiaomiRoute(null, "session: " + e.getMessage());
            else retryWithoutFullRes(null, "session: " + e.getMessage());
        }
    }

    /** P41b: dev switch {@code xiaomi_opmode}: the logical session's operation mode (stock 0x9002 = 36866, 0 = regular). */
    private static int xiaomiOperationModeSetting() {
        return Math.round(PreferenceKeys.scamDevNumber("xiaomi_opmode", XiaomiTeleZoom.STOCK_OPERATION_MODE));
    }

    /**
     * A camera that refuses the session with the vivo stock preview profile in its session parameters (vivo X200 Pro front
     * camera, owner's log 2026-10-08: every stream size failed, the back cameras of the same session code ran) retries once
     * with the plain Camera2 preview, before smaller streams; the camera keeps the plain preview for this process.
     */
    private boolean retryWithoutScamSession(CameraCaptureSession session) {
        final CameraDevice device = mCameraDevice;
        final Handler handler = mBackgroundHandler;
        if ((!mScamPreviewActive && !mForcedStabEis && !mVivoEisSession) || device == null || handler == null || !sPlainPreviewCameras.add(physicalID)) return false;
        Log.w("SCAM_CAPTURE", "camera " + physicalID + ": session configuration failed with the vivo "
                + (mScamPreviewActive ? "stock preview profile" : "EIS session key") + "; retry with the plain Camera2 preview");
        try { session.close(); } catch (Exception ignored) {}
        handler.post(() -> {
            if (!isCameraResumed || mCameraDevice != device) return;
            createCameraPreviewSession(false);
        });
        return true;
    }

    /**
     * P108: a full-resolution session the camera refuses (or that the framework rejects) runs binned again: the camera restarts
     * with the normal RAW stream (this camera, this run) and the owner sees why.
     */
    private boolean retryWithoutVivoSdkFullRes(@Nullable CameraCaptureSession session, String why) {
        final String note;
        synchronized (mVivoSdkLock) {
            if (mVivoSdkRawSession == null || !PixelPro10.fullResOn()) return false;
            // P186: the X100 / X300 Ultra note the row that failed and try the next one of its size the next time
            note = !nativeVivoFullRes() ? null : VivoSdkFullRes.oppo() // P192: an OPPO camera tries its next mode the next time
                    ? OppoNativeFullRes.failed(mVivoSdkCameraId, VivoSdkFullRes.profile(mVivoSdkCameraId), why)
                    : VivoNativeFullRes.failed(mVivoSdkCameraId, VivoSdkFullRes.profile(mVivoSdkCameraId), why);
            PixelPro10.setFullRes(false);
        }
        if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
        Log.w("SCAM_CAPTURE", "SDK FULLRES50 refused: " + why);
        showError(note != null ? note : Lang.t("SDK 50 Мп: камера отказала, обычный RAW", "SDK 50 MP: camera refused, normal RAW"));
        new Handler(Looper.getMainLooper()).post(this::restartCamera);
        return true;
    }

    private boolean retryWithoutRequestedSensorMode(@Nullable CameraCaptureSession session, String why) {
        final boolean dcg = Dcg14Mode.on();
        try {
            String slot = com.particlesdevs.photoncamera.settings.ModuleRegistry.active();
            if (!dcg && !SensorModeSwitches.sensorPreset(slot)) return false;
            if (!SensorModeSwitches.selectPlain()) {
                Dcg14Mode.setMode(Dcg14Mode.OFF);
                showError(Lang.t("Режим сенсора отклонён; выберите обычный модуль", "Sensor mode refused; choose a plain module"));
                return false;
            }
        } catch (RuntimeException unavailable) {
            return false;
        }
        Dcg14Mode.setMode(Dcg14Mode.OFF);
        PixelPro10.setFullRes(false);
        if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
        Log.w("SCAM_CAPTURE", "requested " + (dcg ? "DCG" : "sensor preset") + " refused: " + why);
        showError(Lang.t("Режим сенсора отклонён; обычный модуль", "Sensor mode refused; using a plain module"));
        new Handler(Looper.getMainLooper()).post(this::restartCamera);
        return true;
    }

    private boolean retryWithoutFullRes(@Nullable CameraCaptureSession session, String why) {
        if (!mFullResSession || !sFullResRefused.add(physicalID)) return false;
        Log.w("SCAM_CAPTURE", "camera " + physicalID + ": full resolution refused (" + why + "), binned RAW again");
        mFullResSession = false;
        PixelPro10.setFullRes(false);
        if (session != null) try { session.close(); } catch (Exception ignored) {}
        showError(Lang.t("Полное разрешение: камера отказала, обычный режим", "Full resolution: refused by the camera, normal mode"));
        final Handler main = new Handler(android.os.Looper.getMainLooper());
        main.post(this::restartCamera);
        return true;
    }

    /** P100: a session refused with the CROPPED_RAW RAW stream is configured again without it (this camera, this run). */
    /** P211: a session the camera refused with the module's vendor operation mode runs again in mode 0 (this camera, this run). */
    private boolean retryWithoutVendorMode(@Nullable CameraCaptureSession session) {
        if (mVendorOperationMode == 0 || mCameraDevice == null || !sVendorModeRefused.add(physicalID)) return false;
        Log.w("SCAM_CAPTURE", "camera " + physicalID + ": session refused with operation mode " + mVendorOperationMode + ", again in mode 0");
        showError(Lang.t("Камера " + physicalID + ": режим сессии " + mVendorOperationMode + " не принят, обычный режим",
                "Camera " + physicalID + ": session mode " + mVendorOperationMode + " refused, normal mode"));
        mVendorOperationMode = 0;
        if (session != null) try { session.close(); } catch (RuntimeException ignored) { }
        final Handler handler = mBackgroundHandler;
        if (handler != null) handler.post(() -> { if (isCameraResumed && mCameraDevice != null) createCameraPreviewSession(false); });
        return true;
    }

    private boolean retryWithoutCroppedRaw(CameraCaptureSession session) {
        if (!mCroppedRawSession || mCameraDevice == null || !sCroppedRawRefused.add(physicalID)) return false;
        Log.w("SCAM_CAPTURE", "camera " + physicalID + ": session refused with CROPPED_RAW, again without it");
        mCroppedRawSession = false;
        session.close();
        createCameraPreviewSession(false);
        return true;
    }

    private static final java.util.Set<String> sCroppedRawRefused = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** P108: Pixel 10 Pro / Pro XL full-resolution session (RAW at the max-resolution size, MAXIMUM_RESOLUTION pixel mode). */
    private volatile boolean mFullResSession;
    /** P108: cameras whose full-resolution session was refused (this run): they run binned. */
    private static final java.util.Set<String> sFullResRefused = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean mCroppedRawSession;
    private android.graphics.Rect mLastRawCrop;

    private boolean retryWithoutLiveRaw(CameraCaptureSession session) {
        if (VivoPd2454NativeRaw.active(physicalID)) return false;
        if (!mLiveRawSession || mLiveRawRejected || mCameraDevice == null || isZslMode()) return false;
        mLiveRawRejected = true;
        mLiveRawSession = false;
        LiveRawFrame.setEnabled(false);
        mLiveRawRouter.clear();
        mLiveMetadata.clear();
        session.close();
        showError(Lang.t("RAW-превью недоступно с текущими потоками. Переключаемся на обычный видоискатель.", "RAW preview is not available with the current streams. Switching to the normal viewfinder."));
        createCameraPreviewSession(false);
        return true;
    }

    @NotNull
    private List<Surface> configureSurfaces(boolean isBurstSession) {
        if (stillOnlyFullRes() && mImageReaderRawView != null)
            return Arrays.asList(mImageReaderRawView.getSurface(), mImageReaderRaw.getSurface());
        if (nativeVivoFullRes() || VivoPd2454NativeRaw.active(physicalID))
            return java.util.Collections.singletonList(mImageReaderRaw.getSurface());
        if (mVivoSdkRawSession != null)
            return Arrays.asList(surface, mImageReaderRaw.getSurface(), mVivoSdkJpeg.getSurface());
        // The viewfinder and the RAW stream.
        List<Surface> surfaces = Arrays.asList(surface, mImageReaderRaw.getSurface());
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

        if (!nativeVivoFullRes() && !VivoPd2454NativeRaw.active(physicalID)) mPreviewRequestBuilder.addTarget(surface);
        if (mVivoSdkRawSession != null) {
            if (nativeVivoFullRes()) mPreviewRequestBuilder.addTarget(stillOnlyFullRes() && mImageReaderRawView != null
                    ? mImageReaderRawView.getSurface() : mImageReaderRaw.getSurface());
            clearZslPreviewFrames();
            mInitialMeteringAF = mPreviewRequestBuilder.get(CONTROL_AF_REGIONS);
            mPreviewMeteringAF = mInitialMeteringAF;
            mInitialMeteringAE = mPreviewRequestBuilder.get(CONTROL_AE_REGIONS);
            mPreviewMeteringAE = mInitialMeteringAE;
            mPreviewAFMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            mPreviewAEMode = CONTROL_AE_MODE_ON;
            applyVivoSdkPreview(mPreviewRequestBuilder);
            return;
        }
        synchronized (mZslBufferLock) {
            mHexZslResults.clear();
            while (!mZslRingBuffer.isEmpty()) {
                RingFrame img = mZslRingBuffer.pollFirst();
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
        }
        applyVideoStabilization(mPreviewRequestBuilder);
        mInitialMeteringAE = mPreviewRequestBuilder.get(CONTROL_AE_REGIONS);
        mPreviewMeteringAE = mInitialMeteringAE;
        // P103: zoom override and the low-light boost of the viewfinder, where the camera lists them
        HiddenFeatures.applyPreview(mPreviewRequestBuilder, mCameraCharacteristics, mIsRecordingVideo);
        mPreviewAEMode = mPreviewRequestBuilder.get(CONTROL_AE_MODE);
        applyZoom(mPreviewRequestBuilder, false);
        // P42: face statistics on the preview request only (modes the module advertises).
        com.particlesdevs.photoncamera.control.subject.SubjectFocus.applyFaceDetectMode(mPreviewRequestBuilder, mCameraCharacteristics);
    }

    /**
     * P214f (speed-ux.md C3): the camera screen's message card (UiMessages, MessageCard) instead of a ~2 s system toast: it
     * shows 1.5 s + 50 ms per character (at most 7 s), merges repeats into one message with a counter and keeps the last 20
     * (Система → Диагностика → «Последние сообщения», or a long press on the card). Any thread.
     */
    private void showToast(String msg) {
        com.particlesdevs.photoncamera.util.UiMessages.info(msg);
    }

    /**
     * P214f: a failure the owner has to see (the camera failed or refused what was asked, a fallback was taken): the card keeps
     * it, in the warning colour, until it is tapped; also in the history.
     */
    private void showError(String msg) {
        com.particlesdevs.photoncamera.util.UiMessages.error(msg);
    }

    private void applyVivoSdkPreview(CaptureRequest.Builder builder) {
        if (nativeVivoFullRes()) {
            VivoPd2454NativeRaw.applyFullRes(builder, VivoSdkFullRes.profile(mVivoSdkCameraId));
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, FpsRangeAuto);
            // P201: the manual mode over the FULLRES keys of the viewfinder (not the session parameters; the OPPO's own exposure
            // takes the hand-set ISO / shutter itself)
            if (builder == mPreviewRequestBuilder) {
                if (VivoSdkFullRes.oppo()) paramController.applyManualWbFocusTo(builder);
                else paramController.applyManualTo(builder);
            }
            return;
        }
        mVivoSdkRawSession.apply(builder, false, 0);
        // Fixed 30 fps returns empty FULLRES RAW on PD2454 ultra-wide; this graph captures serial stills, not a RAW ring.
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, FpsRangeAuto);
        VivoSdkRawSession.applyAutomaticExposure(builder);
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        if (builder == mPreviewRequestBuilder) paramController.applyManualTo(builder); // P201
    }

    /**
     * Initiate a still image capture.
     */
    private boolean scamQueueMemoryAvailable() {
        android.app.ActivityManager manager = (android.app.ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        // P113: a «Движение» shot is views of the ring's memfd (no new memory) and fewer frames behind a queue: only the
        // system's low-memory state stops it (was: 1.5 GB free, so a merge in progress blocked the next press on most phones)
        if (com.particlesdevs.photoncamera.settings.MotionMode.on()) return !info.lowMemory;
        return !info.lowMemory && info.availMem > 1536L * 1024 * 1024;
    }

    public boolean takePicture() {
        // P92: the shot survives the app going behind the screen (started here, while the app is in front)
        com.particlesdevs.photoncamera.processing.ProcessingKeepAlive.hold(ScameraApp.getAppContext());
        synchronized (mPreviewStateLock) {
            Log.i(TAG, "SHUTTER camera=" + physicalID + " state=" + mState
                    + " processing=" + isProcessing + " zsl=" + mZslCapturing
                    + " shot=" + mShotInProgress + " burst=" + burst);
            boolean processingBlocks = mVivoSdkRawSession != null ? isProcessing || sScamPending.get() > 0
                    : isProcessing && !(PreferenceKeys.isScamEnabled() && scamQueueMemoryAvailable());
            if (scamShutterQueues() && !processingBlocks
                    && (mZslCapturing || mShotInProgress || burst || mScamFireWaiting)) {
                // Stock-like: a press during the tail capture is kept, not dropped.
                if (mScamQueuedShots < 4) {
                    mScamQueuedShots++;
                    Log.i("SCAM_CAPTURE", "shutter queued behind current capture pending=" + mScamQueuedShots);
                    return true;
                }
            }
            // P113 «Движение»: a press with no ring frame since the previous shot (faster than one frame) waits for one,
            // instead of exposing frames after the shutter
            final boolean motionWaited = mMotionWaitExpired;
            mMotionWaitExpired = false;
            if (scamShutterQueues() && !mScamFireWaiting && !motionWaited && com.particlesdevs.photoncamera.settings.MotionMode.on()
                    && !mZslCapturing && !mShotInProgress && !burst && motionRingFrames() == 0) {
                Log.i("SCAM_CAPTURE", "motion: shutter waits for a ring frame");
                mScamFireWaiting = true;
                fireQueuedScamShot(android.os.SystemClock.elapsedRealtime() + MOTION_RING_WAIT_MS);
                return true;
            }
            if (scamShutterQueues() && !mScamFireWaiting && mScamTailTimestamp != 0 && !previewFreshAfterTail()) {
                // Pressed just after a tail: the newest preview result still predates
                // it, so wait for fresh frames exactly like a queued press.
                Log.i("SCAM_CAPTURE", "shutter waits for fresh preview after previous tail");
                mScamFireWaiting = true;
                fireQueuedScamShot(android.os.SystemClock.elapsedRealtime() + 2000);
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
            scamZslShutterTimestamp = previewTimestamp == null ? 0 : previewTimestamp;
            // P111 «Движение»: the frame on the screen when the finger touched the shutter, not the one at its release (a tap
            // lasts ~0.1 s, three ring frames at 30 fps); only a touch of this session after the previous shot's tail
            final long touched = pressTimestamp(scamZslShutterTimestamp);
            if (touched > 0) {
                Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT, "motion: shutter at the touch, cutoff %.1f ms before the release",
                        (scamZslShutterTimestamp - touched) / 1e6));
                scamZslShutterTimestamp = touched;
            }
            // The vendor shutter AE snapshot (diagnostic only, never applied) walked
            // every vendor key of the result: 37 ms on the UI thread per press, and
            // this HAL does not publish it for Camera2 clients anyway.
            if(PreferenceKeys.isScamEnabled()) Log.i("SCAM_CAPTURE","shutter camera="+physicalID
                    +" mode="+ScameraApp.getSettings().selectedMode+" zslMode="+isZslMode()
                    +" sensorCutoffNs="+scamZslShutterTimestamp+" cutoffSource=latest_preview_result"
                    +" route="+PreferenceKeys.mergeRoute());
            mShotInProgress = true;
            // P80: «Замер времени проходов GPU» on: this shot's timing lines also go to Download/SCAMERA/SCAMERA-timings.log
            com.particlesdevs.photoncamera.util.ScameraTimingLog.shot(ScameraApp.getAppContext(), PreferenceKeys.hybridSwitch("profile", false),
                    android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " (" + android.os.Build.DEVICE + ") camera " + physicalID
                    + " route " + PreferenceKeys.mergeRoute());
            com.particlesdevs.photoncamera.processing.ShotTimeline.begin("shutter");
            final long shotGeneration = ++mShutterGeneration;
            mShutterIszAspect4x3 = IszCapturePolicy.isIsz(
                    com.particlesdevs.photoncamera.settings.ModuleRegistry.active(), mCameraCharacteristics,
                    mPreviewCaptureResult, physicalID, logicalID, mCroppedRawSession);
            mShutterManualWb = paramController.manualWhiteBalanceGains(); // P212f: a later switch to Auto stays out of the shot
            mShutterMosaicWb = mosaicWbAtPress(); // P212g (W6 = M6)
            if (mVivoSdkRawSession != null) {
                mBackgroundHandler.post(this::captureVivoSdkFullRes);
                return true;
            }
            if (isZslMode()) {
                mScamRingFrozen = PreferenceKeys.isScamEnabled();
                mScamPlanDeadline = android.os.SystemClock.elapsedRealtime() + 3000;
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

    /** SCAM ZSL shots accept presses during capture; the UI keeps the shutter enabled. */
    public boolean scamShutterQueues() {
        // Only the SCAM route (no remosaic backend) frees the shutter through
        // finishScamShot(); any other route would leave queued presses stuck.
        return PreferenceKeys.isScamEnabled() && isZslMode();
    }

    /**
     * The vendor AE resumes from the last applied sensor settings: after the ES
     * frame (e.g. 10 ms, ISO 77) the preview ramped back over ~8 frames and the next
     * shot found no ZSL RAWs at the planned N. One preview frame at the pre-shutter
     * exposure, queued behind the tail, hands AE back at the right values.
     * P38 dev switch scam_dev.txt "ae_restore": 0 = no restore frame, 1 = the manual frame (default),
     * 2 = one AE ON + AE lock frame instead of the manual one (the vendor AE / stabilisation never sees AE OFF).
     */
    private void queueScamAeRestore() {
        final int mode = Math.round(PreferenceKeys.scamDevNumber("ae_restore", 1f));
        if (mode == 0) {
            mStabTrace.event("AE restore frame skipped (ae_restore 0)");
            return;
        }
        if (mode == 2) {
            queueScamAeLockFrame();
            return;
        }
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
        // P54c: under the forced stabilisation (X300U) the manual frame skips the viewfinder stream, as ArkCam's manual
        // frames do: the HAL's preview / stabilisation path never sees an AE OFF request. Same exposure, ISO and RAW.
        final Surface viewfinder = surface;
        final boolean dropViewfinder = viewfinder != null && ForcedStabilization.dropViewfinder(mForcedStab,
                ForcedStabilization.rawOnlySwitch(), isZslMode() || mLiveRawSession, mIsRecordingVideo);
        boolean viewfinderRemoved = false;
        try {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            if (duration != null && duration > 0) b.set(CaptureRequest.SENSOR_FRAME_DURATION, duration);
            if (dropViewfinder) {
                b.removeTarget(viewfinder);
                viewfinderRemoved = true;
            }
            CaptureRequest restore = b.build();
            mCaptureSession.capture(restore, mCaptureCallback, mBackgroundHandler);
            String where = viewfinderRemoved ? " (RAW stream only, P54c)" : "";
            Log.i("SCAM_CAPTURE", "AE restore frame queued exposureNs=" + exposure + " ISO=" + iso + where);
            mStabTrace.event("AE restore frame queued (AE OFF) exposureNs=" + exposure + " ISO=" + iso + where);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w("SCAM_CAPTURE", "AE restore frame not queued: " + e);
        } finally {
            if (viewfinderRemoved) b.addTarget(viewfinder);
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, oldExposure);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, oldIso);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, oldDuration);
        }
    }

    /** P38 "ae_restore 2": one preview frame with AE ON and AE locked behind the tail, the repeating preview unlocks it. */
    private void queueScamAeLockFrame() {
        CaptureRequest.Builder b = mPreviewRequestBuilder;
        if (b == null || mCaptureSession == null) return;
        Boolean oldLock = b.get(CaptureRequest.CONTROL_AE_LOCK);
        try {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true);
            mCaptureSession.capture(b.build(), mCaptureCallback, mBackgroundHandler);
            Log.i("SCAM_CAPTURE", "AE restore frame queued as AE ON + lock (ae_restore 2)");
            mStabTrace.event("AE restore frame queued (AE ON + lock)");
        } catch (CameraAccessException | RuntimeException e) {
            Log.w("SCAM_CAPTURE", "AE lock frame not queued: " + e);
        } finally {
            b.set(CaptureRequest.CONTROL_AE_LOCK, oldLock);
        }
    }

    /**
     * P38: the vivo X300 Ultra preview lost its stabilisation after a shot (the HAL queue flush before the series is
     * the first suspect: the HAL went idle and restarted its pipeline on a bracket request, not on the preview). Once
     * the shot's series has completed, the preview is re-armed - off the shutter path, so the flush keeps its latency
     * gain. Requested from finishScamShot, when every RAW of the series is in memory. scam_dev.txt "stab_rearm":
     *   0 = off;
     *   1 = re-issue the repeating preview request (settings re-sent to the HAL);
     *   2 = one preview frame with OIS (and video stabilisation, when the preview sets it) OFF, then the repeating
     *       preview with the normal keys: the HAL sees a stabilisation mode change OFF -> ON;
     *   3 = flush the HAL queue again and restart the repeating preview, so the pipeline restarts on a preview request.
     *       It waits for the first result of the repeating preview after the series: the AE restore frame queued
     *       behind the series is then done (a flush from finishScamShot itself dropped it on the OPPO, 0.47 s after
     *       the press) and only preview frames are dropped;
     *   default (no line) on a vivo HAL (where the loss was seen) = 3 after a flushed shot, 1 otherwise; elsewhere 0 (the OPPO
     *   and the other cameras keep the preview exactly as before).
     * Never under a queued or waiting next shot, nor under another shot's frames: that shot flushes and re-arms on its own.
     */
    private void requestPreviewRearm(boolean shotFlushed, boolean skip) {
        int mode = previewRearmMode(shotFlushed, mLastShotLead);
        if (mode <= 0 || mode > 3) return;
        if (skip) {
            mStabTrace.event("re-arm skipped: a queued shot follows or the session restarts");
            return;
        }
        if (mode == 3) {
            mRearmDeadlineMs = android.os.SystemClock.elapsedRealtime() + 1500;
            mRearmSession = mCaptureSession;
            mRearmPending = true;
            mStabTrace.event("re-arm (flush) waits for the first preview frame after the series");
            return;
        }
        rearmPreviewStabilisation(mCaptureSession, mode);
    }

    /** P38 / P44: the re-arm mode after a shot (scam_dev "stab_rearm" wins over the default of PreviewContinuity). */
    private static int previewRearmMode(boolean shotFlushed, int leadFrames) {
        int fallback = PreviewContinuity.defaultRearm(ScamPreview.supported(), shotFlushed, leadFrames);
        return Math.round(PreferenceKeys.scamDevNumber("stab_rearm", fallback));
    }

    /**
     * P44 scam_dev "preview_lead N": N frames of the normal preview request right after the flush, ahead of the series. The
     * viewfinder gets a frame as soon as the HAL has restarted, and the restart happens on a preview request (the P38 re-arm
     * flush after the series is then not needed). Their RAWs are routed as preview frames (dropped during the shot) like the
     * AE restore frame's. Each one starts the series a preview frame later.
     */
    private void queuePreviewLead(int frames) {
        CaptureRequest.Builder b = mPreviewRequestBuilder;
        CameraCaptureSession session = mCaptureSession;
        if (b == null || session == null) return;
        try {
            CaptureRequest lead = b.build();
            for (int i = 0; i < frames; i++) session.capture(lead, mCaptureCallback, mBackgroundHandler);
            Log.i("SCAM_CAPTURE", "preview lead frames queued ahead of the series: " + frames);
            mStabTrace.event("preview lead frames queued ahead of the series: " + frames);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w("SCAM_CAPTURE", "preview lead frames not queued: " + e);
        }
    }

    /** P38: from the preview callback: the first repeating-preview result after the series runs the pending re-arm. */
    private void maybeRunPendingRearm(CameraCaptureSession session, CaptureRequest request, CaptureResult result) {
        if (!mRearmPending || request != mPreviewInputRequest) return;
        if (android.os.SystemClock.elapsedRealtime() > mRearmDeadlineMs || session != mRearmSession || nextShotPending()) {
            mRearmPending = false;  // a new shot flushes on its own; a lost moment is not chased
            mStabTrace.event("pending re-arm dropped");
            return;
        }
        Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (ts == null || ts <= mScamTailTimestamp) return;
        mRearmPending = false;
        final android.os.Handler handler = mBackgroundHandler;
        if (handler != null) handler.post(() -> rearmPreviewStabilisation(session, 3));
    }

    /** P38: a shot is in flight, pressed, queued or waiting for fresh preview frames (read under mPreviewStateLock to be exact). */
    private boolean nextShotPending() {
        return shotInFlight() || mShotInProgress || mScamFireWaiting || mScamQueuedShots > 0;
    }

    private void rearmPreviewStabilisation(CameraCaptureSession session, int mode) {
        synchronized (mPreviewStateLock) {
            if (!isCameraResumed || session == null || session != mCaptureSession || mPreviewRequestBuilder == null) return;
            // Checked under the lock takePicture() sets its flags under: a press is either fully before (skipped here) or
            // waits for this re-arm and then takes the frames after it. A re-arm never flushes or re-issues the preview under
            // another shot's requests (it would drop them, or put preview frames ahead of its bracket).
            if (nextShotPending()) {
                mStabTrace.event("re-arm mode=" + mode + " skipped: another shot is pending");
                return;
            }
            long t0 = android.os.SystemClock.elapsedRealtime();
            try {
                if (mode == 2) {
                    CaptureRequest.Builder b = mPreviewRequestBuilder;
                    Integer ois = b.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE);
                    Integer vs = b.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE);
                    try {
                        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                        if (vs != null && vs != CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                        session.capture(b.build(), mCaptureCallback, mBackgroundHandler);
                    } finally {
                        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, ois);
                        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, vs);
                    }
                } else if (mode == 3) {
                    // The series and its AE restore frame are done: only preview frames are dropped.
                    flushDevice(session);
                }
                session.setRepeatingRequest(mPreviewInputRequest = mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
                String what = "preview stabilisation re-armed mode=" + mode + " in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms"
                        + " keys[" + StabilizationTrace.stabKeysLine(mPreviewInputRequest) + "]";
                Log.i("SCAM_CAPTURE", what);
                mStabTrace.event(what);
            } catch (CameraAccessException | RuntimeException e) {
                Log.w("SCAM_CAPTURE", "preview stabilisation re-arm failed: " + e);
                // A failed flush leaves the preview stopped: bring it back.
                try {
                    session.setRepeatingRequest(mPreviewInputRequest = mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
                } catch (CameraAccessException | RuntimeException ignored) { }
            }
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
        Log.w("SCAM_CAPTURE", "shot in flight completed with the frames that arrived: " + reason);
        mInFlightRescueReason = reason; // P222: a FULLRES series tells a camera error from the camera closed under it
        try {
            rescue.run();
        } catch (RuntimeException e) {
            Log.e(TAG, "shot rescue failed: " + Log.getStackTraceString(e));
        }
    }

    /** P111: the newest preview result's sensor time when the finger touched the shutter, its session and wall time. */
    private volatile long mPressPreviewTimestamp, mPressAtMs;
    private volatile int mPressGeneration;

    /**
     * P111: the finger is on the shutter (the shot fires on release): the frame on the screen now is the moment, as the stock
     * vivo snapshot mode sends its displayTimeStamp: the viewfinder's last shown camera frame, else the newest preview result.
     */
    public void notePressDown() {
        final CaptureResult latest = mPreviewCaptureResult;
        final Long ts = latest == null ? null : latest.get(CaptureResult.SENSOR_TIMESTAMP);
        final long newest = ts == null ? 0 : ts;
        final long shown = com.particlesdevs.photoncamera.ui.camera.views.viewfinder.MainRenderer.shownTimestamp();
        mPressGeneration = mSessionGeneration.get();
        mPressAtMs = android.os.SystemClock.elapsedRealtime();
        mPressPreviewTimestamp = shown > 0 && shown <= newest && newest - shown < 300_000_000L ? shown : newest;
    }

    /**
     * P111: the touch's frame for the «Движение» cutoff, 0 when it does not apply (mode off, no touch: the volume key fires on
     * key-down already; a touch of an older session, older than 1.5 s, before the previous shot's tail, or not before
     * {@code releaseCutoff}). Used once.
     */
    private long pressTimestamp(long releaseCutoff) {
        final long touched = mPressPreviewTimestamp;
        mPressPreviewTimestamp = 0;
        if (touched <= 0 || !com.particlesdevs.photoncamera.settings.MotionMode.on()) return 0;
        if (mPressGeneration != mSessionGeneration.get() || android.os.SystemClock.elapsedRealtime() - mPressAtMs > 1500) return 0;
        if (touched >= releaseCutoff || (mScamTailTimestamp != 0 && touched <= mScamTailTimestamp)) return 0;
        return touched;
    }

    /** P27: a shot is between its press and the end of its post-shutter frames. */
    private boolean shotInFlight() {
        return mInFlightRescue != null || mZslCapturing || burst;
    }

    /** P42: face / tracking regions must not change the repeating request while a shot is being captured. */
    public boolean subjectRegionsFrozen() {
        return shotInFlight() || mShotInProgress;
    }

    private void finishScamShot() {
        final boolean sessionRestart = mPendingPayloadRestart && mBackgroundHandler != null;
        if (sessionRestart) {
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
            mScamRingFrozen = false;
            mState = STATE_PREVIEW;
            fire = mScamQueuedShots > 0;
            if (fire) mScamQueuedShots--;
        }
        // P38: every frame of the series is in memory and the shot's flags are clear: re-arm the preview stabilisation (the
        // preview kept running on this route). Not when a queued press fires now (that shot re-arms after its own series), nor
        // when the session is restarted anyway.
        requestPreviewRearm(mLastShotFlushed, fire || mScamFireWaiting || sessionRestart);
        Log.i("SCAM_CAPTURE", "shutter free (preview kept running) queuedNext=" + fire);
        if (fire) fireQueuedScamShot(android.os.SystemClock.elapsedRealtime() + 2000);
    }

    /**
     * After the manual tail the HAL resumes preview results late, so the newest
     * preview frame at this moment can still predate the bracket (no fresh AE plan,
     * no ZSL RAWs at the planned N). Fire once five preview frames follow the tail:
     * four ring RAWs plus the frame the shutter timestamp is taken from.
     */
    private boolean previewFreshAfterTail() {
        // P113 «Движение»: the next shot fires as soon as the ring holds a frame (the stock snapshot mode drops to fewer frames
        // when it is pressed fast, it does not wait); the ring's frames are all newer than the previous shot's (it drained them)
        if (com.particlesdevs.photoncamera.settings.MotionMode.on()) return motionRingFrames() > 0;
        CaptureResult latest = mPreviewCaptureResult;
        Long ts = latest == null ? null : latest.get(CaptureResult.SENSOR_TIMESTAMP);
        if (ts == null || ts <= mScamTailTimestamp + 5 * 33_000_000L) return false;
        // Preview RAWs arrive later than their results: wait until the ring holds
        // four RAWs taken after the previous tail, so N come from ZSL instead of
        // being re-shot after the press (0.9 s and shutter lag).
        // Count the newest run of ring RAWs after the tail that share one exposure
        // and ISO: the first frames after a tail can still carry AE recovery.
        int run = 0;
        synchronized (mZslBufferLock) {
            Long exposure = null; Integer iso = null;
            java.util.Iterator<RingFrame> it = mZslRingBuffer.descendingIterator();
            while (it.hasNext()) {
                RingFrame image = it.next();
                if (image.getTimestamp() <= mScamTailTimestamp) break;
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

    /** P113: how long a «Движение» press waits for its first ring frame before the shot takes its frames after the shutter. */
    static final long MOTION_RING_WAIT_MS = 300;
    /** P113: the wait ran out without a ring frame: the next takePicture shoots without waiting again. */
    private volatile boolean mMotionWaitExpired;

    /** P113: ring frames with their result (a shot can use them), newer than the previous shot's tail. */
    private int motionRingFrames() {
        int n = 0;
        synchronized (mZslBufferLock) {
            for (RingFrame image : mZslRingBuffer)
                if (image.getTimestamp() > mScamTailTimestamp && mHexZslResults.get(image.getTimestamp()) != null) n++;
        }
        return n;
    }

    private void fireQueuedScamShot(long deadline) {
        if (mBackgroundHandler == null) {mScamFireWaiting = false; return;}
        CaptureResult latest = mPreviewCaptureResult;
        Long ts = latest == null ? null : latest.get(CaptureResult.SENSOR_TIMESTAMP);
        long tail = mScamTailTimestamp;
        boolean fresh = previewFreshAfterTail();
        if (!fresh && android.os.SystemClock.elapsedRealtime() < deadline) {
            mScamFireWaiting = true;
            // P113: «Движение» looks every 5 ms (a ring frame is the whole wait), the other modes every 30 ms
            mBackgroundHandler.postDelayed(() -> fireQueuedScamShot(deadline),
                    com.particlesdevs.photoncamera.settings.MotionMode.on() ? 5 : 30);
            return;
        }
        Log.i("SCAM_CAPTURE", "queued shutter fires fresh=" + fresh + " previewAfterTailMs="
                + (ts == null ? -1 : (ts - tail) / 1_000_000));
        if (!fresh) mMotionWaitExpired = true;
        mScamFireWaiting = false;
        mScamTailTimestamp = 0;
        if (!takePicture()) Log.w("SCAM_CAPTURE", "queued shutter could not start");
    }

    private boolean isWaitingForCapture() {
        return mState == STATE_WAITING_LOCK || mState == STATE_WAITING_PRECAPTURE
                || mState == STATE_WAITING_NON_PRECAPTURE;
    }

    private void failPendingShutter(Exception error) {
        mScamRingFrozen = false;
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
        mScamQueuedShots = 0;
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
    public boolean isZslMode() {
        if (mVivoSdkRawSession != null) return false;
        if (VivoQcomModes.wantsStreamUsage()
                && PreferenceKeys.scamDevSwitch("vivo_qcom_raw_psl", false)) return false;
        CameraMode mode=ScameraApp.getSettings().selectedMode;
        return mode==CameraMode.MOTION || mode==CameraMode.PHOTO || mode==CameraMode.NIGHT;
    }

    /** Both routes (the SCAM Hybrid and SCAM HDR) bracket after the ZSL N frames. */
    private boolean needsExposureBracket() {
        return PreferenceKeys.isScamEnabled();
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
    /**
     * Packed layouts seen behind this session's preview RAW_SENSOR frames (Xiaomi 17 Ultra tele: MIPI RAW10 in mode 4; in the
     * in-sensor zoom, mode 9, RAW10 and RAW14 frames alternate, P72). Empty: plain 16-bit. Every frame picks its own by where its
     * data ends ({@link #frameLayout}); the full check runs only on a frame none fits.
     */
    private final java.util.List<com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout> mPreviewLayouts = new java.util.concurrent.CopyOnWriteArrayList<>();
    private long mLayoutTs = Long.MIN_VALUE;
    private com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout mLayoutOfTs;
    private synchronized com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout frameLayout(Image img) {
        if (mPreviewLayouts.isEmpty() || img.getFormat() != ImageFormat.RAW_SENSOR) return null;
        if (img.getTimestamp() == mLayoutTs) return mLayoutOfTs;
        java.nio.ByteBuffer buffer = img.getPlanes()[0].getBuffer();
        final int end = com.particlesdevs.photoncamera.processing.RawPayloadCheck.dataEnd(buffer);
        com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout found = null;
        for (com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout l : mPreviewLayouts)
            if (com.particlesdevs.photoncamera.processing.RawPayloadCheck.fits(l, end, img.getWidth(), img.getHeight())) { found = l; break; }
        if (found == null && end < buffer.capacity() - img.getWidth()) {
            found = packedLayout(img);
            if (found != null) addPreviewLayout(found);
        }
        mLayoutTs = img.getTimestamp();
        mLayoutOfTs = found;
        return found;
    }
    private void addPreviewLayout(com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout layout) {
        for (com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout l : mPreviewLayouts) if (l.format == layout.format && l.stride == layout.stride) return;
        mPreviewLayouts.add(layout);
        Log.i(TAG, "preview RAW of camera " + physicalID + ": " + layout.describe() + ", unpacked for the viewfinder");
    }
    /** The format a RAW frame is read as: its packed layout behind RAW_SENSOR ({@link #frameLayout}), else its own. */
    private int readFormat(Image img) {
        com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout l = frameLayout(img);
        return l != null ? l.format : img.getFormat();
    }
    private int readStride(Image img) {
        com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout l = frameLayout(img);
        return l != null ? l.stride : img.getPlanes()[0].getRowStride();
    }
    private boolean mScamPreviewActive;
    /** P54b: this session holds the stabilisation on (ForcedStabilization: X300 Ultra); with the vivo EIS key. */
    private boolean mForcedStab;
    private boolean mForcedStabEis;
    private boolean mVivoEisSession;
    /**
     * Cameras whose preview RAW was not plain 16-bit while ScamPreview's stock profile ran (vivo X100 Ultra main: packed
     * 10-bit ZSL frames under the PD2454 stagger / HDR preview tags). Their sessions run the plain Camera2 preview from then on
     * (this process); the ring check still guards the shot when the plain preview is packed too.
     */
    private static final java.util.Set<String> sPlainPreviewCameras = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * P110: the RAW level of a ring frame for the «Движение» shutter ceiling (RawLevel), only while the mode is on and the
     * payload is a plain 16-bit RAW (no packed layout in use). The previewed physical camera's levels at the stream's bit depth
     * (P127 RawLevels, with the frame's own metadata when its result is at hand).
     */
    private void observeRawLevel(Image img, CaptureResult result) {
        if (!com.particlesdevs.photoncamera.settings.MotionMode.on()) return;
        CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
        if (c == null) c = mCameraCharacteristics;
        if (c == null) return;
        final com.particlesdevs.photoncamera.processing.RawLevels.Levels levels =
                com.particlesdevs.photoncamera.processing.RawLevels.current(c, result);
        RawLevel.observe(img, !mPayloadBad && mPreviewLayouts.isEmpty(), levels.meanBlack(), levels.white);
    }

    /** P110: the ISO the HAL honours for AE-off requests of this camera and RAW size (P27), MAX_VALUE while none is learned. */
    public int honouredIsoCap() {
        final ExposureLimits limits = mExposureLimits;
        return limits == null ? Integer.MAX_VALUE : limits.isoCap();
    }

    /**
     * P110: the repeating viewfinder request must not run AE off (P54c, X300 Ultra forced stabilisation: the HAL's preview
     * stabilisation path never sees an AE OFF request).
     */
    public boolean previewAeOffUnsafe() {
        return mForcedStab && ForcedStabilization.rawOnlySwitch();
    }

    private void watchRawPayload(Image img) {
        int n = mPayloadFrames++;
        if (n >= 4 && n % 30 != 0) return;
        com.particlesdevs.photoncamera.processing.RawPayloadCheck.Result payload =
                com.particlesdevs.photoncamera.processing.RawPayloadCheck.check(img, rawPayloadWhite(null));
        if (payload.plain()) { mPayloadBad = false; mPreviewLayouts.clear(); return; }
        if (img.getFormat() == ImageFormat.RAW_SENSOR) {
            // Xiaomi 17 Ultra tele on the logical camera: packed MIPI RAW10 / RAW14 behind RAW_SENSOR, read in that layout by the
            // colour-block measurement and the developed RAW viewfinder (the forced 2x ISZ shows that viewfinder)
            com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout layout = packedLayout(img);
            if (layout != null) {
                addPreviewLayout(layout);
                mPayloadBad = false;
                return;
            }
        }
        if (!mPayloadBad) {
            Log.w(TAG, "preview RAW of camera " + physicalID + ": " + payload.error
                    + (mScamPreviewActive ? " (vivo stock preview profile on)" : ""));
            com.particlesdevs.photoncamera.processing.RawPayloadCheck.dumpOnce(img, payload, physicalID);
        }
        mPayloadBad = true;
        if (mScamPreviewActive && sPlainPreviewCameras.add(physicalID) && mBackgroundHandler != null) {
            final int generation = mSessionGeneration.get();
            Log.w("SCAM_CAPTURE", "camera " + physicalID + ": vivo stock preview profile off, session restarted with the plain Camera2 preview");
            mBackgroundHandler.post(() -> {
                if (!isCameraResumed || generation != mSessionGeneration.get() || mCaptureSession == null) return;
                // P27: never under a shot in flight (its post-shutter RAWs are still routed): after it, from finishScamShot.
                if (shotInFlight()) { mPendingPayloadRestart = true; return; }
                mCaptureSession.close();
                createCameraPreviewSession(false);
            });
        }
    }

    private void onMatchedLiveRaw(Image img, TotalCaptureResult result) {
        boolean retained = false;
        try {
            if (!isCameraResumed || !mLiveRawSession || mZslCapturing || mHybridZslCapture || mScamRingFrozen) return;
            watchRawPayload(img);
            observeRawLevel(img, result);
            observeMosaic(img);
            plainMeter(img, result); // P212b: a plain session's sample for the native AE's start
            plainTeacher(img, result); // P212f: the mosaic white balance's teacher (log only)
            // P212a: the native AE's ISO probe is no viewfinder frame, no AE frame and no ring frame (the plan never sees it)
            final boolean probe = isNativeIsoProbe(result);
            if (!probe) publishLiveRawFrame(img, result);
            if (!probe) nativeExposure(img, result);
            vivoAeAudit(img, result); // P212h: the vivo HAL AE against our RAW meter (log only)
            if (mTextureView != null) mTextureView.requestRender();
            // P41: after an in-session sensor mode change (Xiaomi ISZ) frames of the old mode stay out of the ZSL ring
            // P187: not while a 200 MP merge runs (its plain session takes no shot)
            // P198: the native 50 MP FULLRES session rings its frames as well (the viewfinder keeps running from them), not while a
            // shot takes its frames (from the press until its series is complete: the ring holds the moment of the press)
            // P200: none while a FULLRES merge runs (its memory)
            final boolean nativeRing = !isZslMode() && mVivoSdkCapture == null && !mShotInProgress && !fullResMergeRunning() && !probe
                    && nativeZslSession() && nativeRingDue(android.os.SystemClock.elapsedRealtimeNanos());
            if ((isZslMode() && passModeBarrier(img, result) || nativeRing && nativeRingFrame(result)) && !mClosedForProcessing)
                synchronized (mZslBufferLock) {
                if (!isCameraResumed || !mLiveRawSession) return;
                final long timestamp = img.getTimestamp(), copyStart = android.os.SystemClock.elapsedRealtimeNanos();
                final RingFrame frame = ringFrameLocked(img); // P91: copied into the ring's memfd (img closed) or held as it is
                retained = true;
                if (nativeRing) nativeRingCopied(copyStart, android.os.SystemClock.elapsedRealtimeNanos() - copyStart);
                if (nativeRing && frame.arenaSlot() == null) {
                    // P198: never the camera's own image: the native reader holds 4, one held by the ring stalls the viewfinder
                    frame.close();
                    return;
                }
                mZslRingBuffer.addLast(frame);
                if (nativeRing) {
                    // the plain ZSL session keeps the results from the preview callback; the native one has them here, matched
                    mHexZslResults.put(timestamp, result);
                    while (mHexZslResults.size() > ringCapacityLocked() + 16)
                        mHexZslResults.remove(mHexZslResults.keySet().iterator().next());
                }
                while (mZslRingBuffer.size() > ringCapacityLocked()) {
                    RingFrame old = mZslRingBuffer.pollFirst();
                    if (old != null) old.close();
                }
            }
        } finally { if (!retained) img.close(); }
    }

    private int mNativeRingRejected;
    /** P198: start and length of the last 50 MP ring copy, frames copied this session. */
    private long mNativeRingLastNs, mNativeRingCopyNs;
    private int mNativeRingCopies;

    /**
     * P198: a 96 MB copy out of the camera's buffer (uncached reads, ~2 GB/s a core, startZslCopy) can take longer than a frame of the
     * 30 fps native viewfinder, on the thread that also draws the viewfinder and runs the own AE: a frame is copied into the ring only
     * when at least twice the last copy's time has passed since the last one began (the copies take at most half of that thread).
     */
    static boolean ringCopyDue(long nowNs, long lastStartNs, long lastCopyNs) {
        return lastStartNs <= 0 || nowNs - lastStartNs >= 2 * lastCopyNs;
    }

    private boolean nativeRingDue(long nowNs) {
        return ringCopyDue(nowNs, mNativeRingLastNs, mNativeRingCopyNs);
    }

    private void nativeRingCopied(long startNs, long copyNs) {
        mNativeRingLastNs = startNs;
        mNativeRingCopyNs = copyNs;
        if (++mNativeRingCopies == 1 || mNativeRingCopies % 150 == 0)
            Log.i("SCAM_CAPTURE", "50 MP ZSL ring: copy " + copyNs / 1_000_000 + " ms (" + mNativeRingCopies + " frames this session, ring "
                    + mZslRingBuffer.size() + ")");
    }

    /**
     * P198: a native frame may enter the 50 MP ZSL ring: its result does not report another sensor mode than the profile's (the rule
     * of the stills, VivoSdkFullResCapture.modeMatches); an OPPO result gets the own neutral, the plain session's colour transform
     * and shading map first (OppoNativeFullRes.complete), as a still's does before anything reads it.
     */
    private boolean nativeRingFrame(TotalCaptureResult result) {
        final VivoSdkFullRes.Profile profile = VivoSdkFullRes.profile(mVivoSdkCameraId);
        if (profile == null || result == null) return false;
        int[] mode = null;
        try { mode = result.get(SENSOR_MODE_INDEX); } catch (IllegalArgumentException ignored) { }
        if (!VivoSdkFullResCapture.modeMatches(profile, mode)) {
            if (++mNativeRingRejected == 1 || mNativeRingRejected % 120 == 0)
                Log.w("SCAM_CAPTURE", "50 MP ZSL: " + mNativeRingRejected + " frames of another sensor mode ("
                        + (mode == null || mode.length == 0 ? "none" : String.valueOf(mode[0])) + ", profile " + profile.sensorMode
                        + ") kept out of the ring");
            return false;
        }
        if (VivoSdkFullRes.oppo()) OppoNativeFullRes.completeFrame(mVivoSdkCameraId, result); // P212e: its white balance recorded
        return true;
    }

    /**
     * The crop the stream already holds (ZoomController.streamCrop): a module with vendor requests whose binned-size stream is a
     * Quad / Tetra mosaic reads the centre of the sensor at full resolution (ISZ by a vendor tag), so its zoom must not be
     * cropped again. {@code reapply}: re-issue the preview zoom when it changed (after the stream was measured).
     */
    private void updateStreamCrop(boolean reapply) {
        try {
            if (nativeVivoFullRes()) {
                if (com.particlesdevs.photoncamera.control.ZoomController.setStreamCrop(1f) && reapply) onZoomChanged();
                return;
            }
            String slot = com.particlesdevs.photoncamera.settings.ModuleRegistry.active();
            int block = com.particlesdevs.photoncamera.processing.MosaicStream.block();
            boolean vendor = !com.particlesdevs.photoncamera.settings.TunableKeyManager.signature(physicalID).isEmpty();
            float crop = XiaomiTeleZoom.phone() ? 1f : com.particlesdevs.photoncamera.control.ZoomController.streamCropFor(block, vendor,
                    com.particlesdevs.photoncamera.settings.ModuleRegistry.sensorCrop(slot),
                    com.particlesdevs.photoncamera.settings.ModuleRegistry.sensorCropExplicit(slot), useMaximumResolutionKey);
            // P60: a measured field of view wins over the block rule (an explicit sensor-crop setting still wins over both)
            float measured = com.particlesdevs.photoncamera.control.FovSelfCheck.get().sessionCrop();
            if (vendor && !XiaomiTeleZoom.phone() && !Float.isNaN(measured)
                    && com.particlesdevs.photoncamera.settings.ModuleRegistry.sensorCropExplicit(slot) == null
                    && !com.particlesdevs.photoncamera.settings.ModuleRegistry.sensorCrop(slot)) crop = measured;
            if (com.particlesdevs.photoncamera.control.ZoomController.setStreamCrop(crop) && reapply) onZoomChanged();
        } catch (RuntimeException e) {
            Log.w(TAG, "stream crop: " + e.getMessage());
        }
    }

    /** Key of the stream's sensor mode for MosaicStream: the physical sensor, its vendor requests and the RAW size. */
    private String mosaicStreamKey() {
        String size = mImageReaderRaw == null ? "" : mImageReaderRaw.getWidth() + "x" + mImageReaderRaw.getHeight();
        return physicalID + "|" + size + "|" + com.particlesdevs.photoncamera.settings.TunableKeyManager.signature(physicalID)
                + "|" + com.particlesdevs.photoncamera.settings.ModuleRegistry.sensorMode(com.particlesdevs.photoncamera.settings.ModuleRegistry.active())
                + (nativeVivoFullRes() ? "|native-full-" + VivoSdkFullRes.profile(mVivoSdkCameraId).sensorMode : "");
    }

    /** P214i: the frame check of this session's X300 Ultra ISZ module (VivoQcomIsz.check), null for any other session. */
    private volatile VivoQcomIsz.StreamCheck mIszCheck;

    /**
     * P214i: one measurement of an X300 Ultra ISZ module's stream (VivoQcomIsz.Trial). Frames that prove a plain Bayer (the camera
     * did not run the mode as its mosaic: build 30912's tele 5 / 7) on the default RAW stream restart the camera with the
     * CROPPED_RAW stream, as a FULLRES row the HAL remosaicked makes the next start try the next row (P188); a plain Bayer there
     * too falls back: said in the log and on the message card (once per firmware), the shots merge as Bayer
     * (VivoQcomIsz.declaredBlock 0: the stored block 1) and the button's zoom becomes a crop (ModuleRegistry.sensorCrop false: the
     * residual follows here). The session key stays, so a later session whose frames are the mosaic moves the trial on.
     */
    private void onIszFrames(VivoQcomIsz.StreamCheck check, com.particlesdevs.photoncamera.processing.MosaicBlockDetector.Result r) {
        final VivoQcomIsz.StreamCheck.Verdict verdict = check.observe(r.confident, r.block, r.votes);
        if (verdict == VivoQcomIsz.StreamCheck.Verdict.NONE) return;
        final VivoQcomIsz.Outcome outcome = VivoQcomIsz.onVerdict(check, verdict, r.toString(), mCroppedRawSession);
        if (outcome.restart) {
            new Handler(Looper.getMainLooper()).post(this::restartCamera);
            return;
        }
        if (outcome.zoom) {
            com.particlesdevs.photoncamera.control.ZoomController.refreshResidual();
            onZoomChanged();
        }
        if (verdict == VivoQcomIsz.StreamCheck.Verdict.PLAIN && PreferenceKeys.isScamHybridEnabled()) try {
            // the merge of this session's shots is a Bayer one now: its programs built ahead (an optimisation only)
            com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralClient.prewarmHybridGpu(ScameraApp.getAppContext(), 1);
        } catch (RuntimeException e) {
            Log.w(TAG, "Hybrid GPU prewarm skipped: " + e);
        }
        if (outcome.card != null) showError(outcome.card);
    }

    /**
     * P13: colour block of the ZSL stream from its first frames (MosaicStream: three agreeing measurements). A mosaic stream
     * switches the viewfinder to the developed RAW; its frames are published with the latest matching result (the colour
     * parameters of the viewfinder change slowly, the ZSL frames are not paired one by one outside the live RAW session).
     */
    private void observeMosaic(Image img) {
        try {
            if (!mMosaicMeasure || !isRawFormat(img.getFormat()) || !isCameraResumed || mPayloadBad) return;
            // P35: the measurement is the background check of the stored block: never while a shot takes its frames.
            if (!shotInFlight() && !mShotInProgress && com.particlesdevs.photoncamera.processing.MosaicStream.wantsFrame()) {
                Image.Plane plane = img.getPlanes()[0];
                CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
                if (c == null) c = mCameraCharacteristics;
                // P127: the stream's levels at its bit depth (RawLevels)
                final com.particlesdevs.photoncamera.processing.RawLevels.Levels levels =
                        com.particlesdevs.photoncamera.processing.RawLevels.current(c, null);
                final float black = levels.meanBlack(), white = levels.white;
                java.nio.ByteBuffer samples = plane.getBuffer();
                int sampleStride = readStride(img);
                final int readFormat = readFormat(img);
                if (com.particlesdevs.photoncamera.util.RawUnpack.isPacked(readFormat)) {
                    // RAW10 / RAW12: measured on the unpacked frame (only the first frames of a session are measured)
                    final int bytes = img.getWidth() * img.getHeight() * 2;
                    if (mMosaicUnpacked == null || mMosaicUnpacked.capacity() < bytes)
                        mMosaicUnpacked = java.nio.ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.nativeOrder());
                    if (!com.particlesdevs.photoncamera.util.RawUnpack.unpack(samples.duplicate(), readFormat, img.getWidth(), img.getHeight(),
                            sampleStride, mMosaicUnpacked)) return;
                    samples = mMosaicUnpacked.duplicate().order(java.nio.ByteOrder.nativeOrder());
                    samples.position(0); samples.limit(bytes);
                    sampleStride = img.getWidth() * 2;
                }
                final com.particlesdevs.photoncamera.processing.MosaicBlockDetector.Result measured =
                        com.particlesdevs.photoncamera.processing.MosaicBlockDetector.detect(samples, img.getWidth(), img.getHeight(),
                                sampleStride, black, white, 32);
                int block = com.particlesdevs.photoncamera.processing.MosaicStream.observe(measured);
                // P187: measured - the unpacked frame (2 B a pixel, 401 MB on a packed 200 MP stream) is not kept for the
                // rest of the session
                if (!com.particlesdevs.photoncamera.processing.MosaicStream.wantsFrame()) mMosaicUnpacked = null;
                // P214i: an X300 Ultra ISZ module's frames against the mosaic its mode declares (before the stream crop below,
                // which reads the module's sensor crop)
                final VivoQcomIsz.StreamCheck iszCheck = mIszCheck;
                if (iszCheck != null && measured != null) onIszFrames(iszCheck, measured);
                updateStreamCrop(true);
                // P188 (owner, 2026-10-17: «Нам нужен фулрез без ремозаика»): a native FULLRES row whose frames are a plain Bayer
                // was remosaicked by the HAL (X300 Ultra main camera, 50 MP row 2): that row counts as failed and the next start
                // tries the next row of the table, instead of shooting the HAL's remosaic
                if (block == 1 && nativeVivoFullRes()) {
                    final VivoSdkFullRes.Profile full = VivoSdkFullRes.profile(mVivoSdkCameraId);
                    // P208: not the still-only route, whose viewfinder is the normal mode's Bayer
                    if (full != null && full.nativeBlock > 1 && !full.stillOnly
                            && retryWithoutVivoSdkFullRes(mCaptureSession, "the frames are remosaicked by the HAL (plain Bayer, no mosaic)")) return;
                }
                // P194: the other way round, a hardware-remosaic row (plain Bayer expected) whose frames are a Quad / Tetra mosaic is
                // no hardware remosaic: noted, its camera takes the own mosaic from the next start
                if (block > 1 && nativeVivoFullRes()) {
                    final VivoSdkFullRes.Profile full = VivoSdkFullRes.profile(mVivoSdkCameraId);
                    if (full != null && full.nativeBayer()
                            && retryWithoutVivoSdkFullRes(mCaptureSession, "the hardware-remosaic row gives a block " + block + " mosaic, not a Bayer")) return;
                }
                if (block > 1 && !mMosaicPreview && !mLiveRawSession && !XiaomiTeleZoom.ispPreview()) {
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
            if (mMosaicPreview && !mZslCapturing && !mHybridZslCapture && !mScamRingFrozen) {
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

    /** P192: the own exposure of the OPPO native FULLRES session (null otherwise); the PHY110's frame time (24 fps). */
    private volatile NativeRawExposure mNativeAe;
    static final long NATIVE_FRAME_NS = 41_666_666L;
    private int mNativeFrames;
    private long mNativeAeLogMs;
    /** P212a / b: the native session's start, since when its AE holds, when it was last stored (elapsedRealtime ms; 0: not). */
    private long mNativeAeStartMs, mNativeAeSettledMs, mNativeAeStoredMs;
    private double mNativeAeStoredTotal;
    /** P212c: the unthrottled trace of the native AE (scam_dev.txt "native_ae_trace 1"), one line a frame, flushed once a second. */
    private final StringBuilder mNativeAeTrace = new StringBuilder();
    private long mNativeAeTraceMs;
    /**
     * P212c: the gains (copyGainFactors order) of the shading map the meter last read, reused while the results carry the same. P212z:
     * the same factors and size (meterGains), not the same map object (a result builds a new one on every get); the map's size and
     * the copy buffer a frame's factors are read into.
     */
    private float[] mMeterGains;
    private int mMeterCols, mMeterRows;
    private final float[][] mMeterScratch = new float[1][];
    /** P212b: the last plain-session sample for the native AE's start (elapsedRealtime ms). */
    private long mPlainMeterMs;
    /**
     * P212a: the native AE's ISO probe request: its frame stays out of the viewfinder, the AE and the 50 MP ring. P212z (review): the
     * request object itself (a result's getRequest() is the request submitted), no longer a tag set on the shared preview builder for
     * the build - a rebuild from another thread in between (a WB dial, a tap, a zoom step) took the tag into the repeating request,
     * and every frame after it was a "probe": no RAW viewfinder, no AE, an empty native ring until the next UI rebuild.
     */
    private volatile CaptureRequest mNativeIsoProbeRequest;

    /**
     * P212d: the native AE's flicker is known - set by hand, or «Авто» latched from evidence (MainsFlicker.known) - or scam_dev.txt
     * "native_ae_flicker_both 0" snaps to the region's guess as before; false: long shutters band least at both frequencies.
     */
    private static boolean nativeFlickerKnown() {
        return PreferenceKeys.antibandingKnown() || !PreferenceKeys.scamDevSwitch("native_ae_flicker_both", true);
    }

    /**
     * P192: the OPPO native mode's own AE and white balance from the frame just shown: the frame is metered and the exposure moved
     * (the repeating request rebuilt) when it is off the setpoint; every 8th frame the neutral is measured (MosaicNeutral, on the
     * camera's curve). Not while a series is taken: its frames keep the exposure of the shutter.
     * P212: the ISO cap the mode delivers before the update (A1), the settled exposure stored for the next start (A2), the meter of
     * NativeRawExposure.meter2 (A4: crop, centre / tap weights, the result's shading map, per-site clipping), the trace.
     */
    private void nativeExposure(Image img, TotalCaptureResult result) {
        final NativeRawExposure ae = mNativeAe;
        if (ae == null || img == null || result == null || mVivoSdkCapture != null || burst || !nativeVivoFullRes()) return;
        try {
            final Image.Plane plane = img.getPlanes()[0];
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            final com.particlesdevs.photoncamera.processing.RawLevels.Levels levels =
                    com.particlesdevs.photoncamera.processing.RawLevels.current(c, result);
            int cfa = mLiveCfaPattern;
            if (cfa < 0) {
                Integer arrangement = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                cfa = arrangement == null ? 0 : arrangement;
            }
            final int block = VivoSdkFullRes.profile(mVivoSdkCameraId).nativeBlock;
            final java.nio.ByteBuffer buffer = plane.getBuffer();
            // P212c (scam_dev.txt "native_ae_meter 0": the P192 meter of the whole frame)
            final NativeRawExposure.Metered metered = PreferenceKeys.scamDevSwitch("native_ae_meter", true)
                    ? NativeRawExposure.meter2(buffer, img.getWidth(), img.getHeight(), plane.getRowStride(), cfa, block, levels.black,
                    levels.white, meterSetup(c, result, OppoNativeFullRes.neutral(mVivoSdkCameraId))) : null;
            final float[] m = metered != null ? new float[]{metered.p50, metered.p98}
                    : NativeRawExposure.meter(buffer, img.getWidth(), img.getHeight(), plane.getRowStride(), cfa, block, levels.black, levels.white);
            final Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            final Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            // P212a: the ISO the mode really delivers (ExposureLimits, learned from these frames and stored): no request above it
            // (they came back at ISO 1600, 2 EV short, and were rebuilt every 6 frames); first, so a hand-set ISO stops there too
            final boolean capOn = PreferenceKeys.scamDevSwitch("native_ae_cap", true);
            if (ae.isoCap(capOn ? honouredIsoCap() : Integer.MAX_VALUE)) {
                Log.i("SCAM_CAPTURE", "native AE camera " + mVivoSdkCameraId + ": ISO cap "
                        + (ae.isoCap() == Integer.MAX_VALUE ? "-" : String.valueOf(ae.isoCap())) + " -> " + ae);
                rebuildPreviewBuilder();
            }
            // P201: a hand-set ISO / shutter holds (both: no metering, one: the other follows)
            if (ae.setManual(paramController.manualIsoValue(), paramController.manualPreviewShutterNs())) {
                Log.i("SCAM_CAPTURE", "native AE camera " + mVivoSdkCameraId + ": manual -> " + ae);
                rebuildPreviewBuilder();
            }
            // P209: whole periods of the place's lamp flicker («Частота мерцания»; Auto: latched from the camera's report)
            // P212d: «Авто» without evidence (the PHY110's report is a placeholder: MainsFlicker.known false) is the region's guess, and
            // a shutter from 30 ms takes the one of 30 / 33.3 / 40 / 41.7 ms that bands least at both 100 and 120 Hz
            final int flickerHz = PreferenceKeys.getAntibandingHz();
            final boolean flickerKnown = nativeFlickerKnown();
            if (ae.flicker(flickerHz, flickerKnown)) {
                Log.i("SCAM_CAPTURE", "native AE camera " + mVivoSdkCameraId + ": flicker " + flickerHz + " Hz ("
                        + (flickerKnown ? "known" : MainsFlicker.source() + ", both bands from 30 ms") + ") -> " + ae);
                rebuildPreviewBuilder();
            }
            final float ev = nativeAeEv(c);
            final boolean changed = m != null && exposure != null && iso != null && ae.update(m[0], m[1], exposure, iso, ev);
            final long now = android.os.SystemClock.elapsedRealtime();
            if (changed) {
                if (now - mNativeAeLogMs >= 1000) {
                    mNativeAeLogMs = now;
                    Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT,
                            "native AE camera %s: median %.4f p98 %.3f%s at %.3f ms ISO %d (ev %+.1f) -> %s%s",
                            mVivoSdkCameraId, m[0], m[1], metered == null ? "" : String.format(java.util.Locale.ROOT, " clip %.4f", metered.clip),
                            exposure / 1e6, iso, ev, ae, ae.deficitEv() > 0.05 ? String.format(java.util.Locale.ROOT,
                                    ", %.2f EV short of the meter (cap %s)", ae.deficitEv(),
                                    ae.isoCap() == Integer.MAX_VALUE ? "-" : String.valueOf(ae.isoCap())) : ""));
                }
                rebuildPreviewBuilder();
            }
            // P212b: a settled exposure (a second inside the band or at the deliverable limit) is the next session's start
            if (exposure != null && iso != null && ae.settled() && !ae.handSet()) {
                if (mNativeAeSettledMs == 0) mNativeAeSettledMs = now;
                else if (now - mNativeAeSettledMs >= NativeAeSeed.SETTLED_HOLD_MS) {
                    final double delivered = (double) exposure * iso / 100.0;
                    if (NativeAeSeed.storeDue(now, mNativeAeStoredMs, delivered, mNativeAeStoredTotal)) {
                        mNativeAeStoredMs = now;
                        mNativeAeStoredTotal = delivered;
                        NativeAeSeed.storeSettled(mVivoSdkCameraId, delivered, ev, System.currentTimeMillis());
                    }
                }
            } else mNativeAeSettledMs = 0;
            // P212a: once a session, a request above the cap shows whether the firmware gives more gain after all (never while a
            // shot is pending: its frame would be in the ring; isNativeIsoProbe keeps it out of the ring and the AE anyway)
            if (capOn && !mShotInProgress && !shotInFlight() && !fullResMergeRunning()) {
                final int probe = ae.probeIso(now - mNativeAeStartMs);
                if (probe > 0) sendNativeIsoProbe(ae, probe);
            }
            if (PreferenceKeys.scamDevSwitch("native_ae_trace", false)) traceNativeAe(ae, metered, m, exposure, iso, ev, changed, now);
            final android.hardware.camera2.params.RggbChannelVector wb = paramController.manualWhiteBalanceGains();
            final boolean wbFilter = OppoNativeWb.on();
            if (wb != null) {
                // P201: a hand-set white balance is the shot's neutral (the inverse of its gains), not the own estimate
                final float g = 0.5f * (wb.getGreenEven() + wb.getGreenOdd());
                if (wb.getRed() > 0f && wb.getBlue() > 0f) {
                    if (wbFilter) OppoNativeWb.hand(mVivoSdkCameraId, new float[]{g / wb.getRed(), 1f, g / wb.getBlue()});
                    else OppoNativeFullRes.setNeutral(mVivoSdkCameraId, g / wb.getRed(), g / wb.getBlue()); // P212f: not averaged
                }
            } else if (wbFilter) {
                // P212e (W3 = M7): the session's gated filter - measured every 8th frame while the AE holds (nothing pending, no
                // rebuild in its last 3 frames) and the frame is neither dark nor clipped; a gated frame waits for the next one
                OppoNativeWb.hand(mVivoSdkCameraId, null);
                final boolean settled = ae.settled();
                if (OppoNativeWb.wants(mVivoSdkCameraId, ae.pending(), ae.sinceRebuild(), settled, m == null ? -1f : m[0],
                        m == null ? -1f : m[1], metered == null ? -1f : metered.clip)) {
                    final com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Result own =
                            nativeOwnNeutral(buffer, img, plane, cfa, levels, block, c, result);
                    final String how = OppoNativeWb.measure(mVivoSdkCameraId, own == null ? null : own.neutral, own == null ? 0 : own.cells,
                            settled, now);
                    if (how != null) Log.i("SCAM_CAPTURE", "native white balance camera " + mVivoSdkCameraId + ": "
                            + (own == null ? "too few usable cells" : own.how + ", " + own.cells + " cells") + how);
                }
            } else if ((mNativeFrames++ & 7) == 0) {
                final com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Result own =
                        nativeOwnNeutral(buffer, img, plane, cfa, levels, block, c, result);
                if (own != null) OppoNativeFullRes.measuredNeutral(mVivoSdkCameraId, own.neutral[0], own.neutral[2]);
                if ((mNativeFrames & 127) == 1 && own != null) Log.i("SCAM_CAPTURE", "native white balance camera " + mVivoSdkCameraId + ": " + own.how);
            }
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "native AE: " + e);
        }
    }

    /**
     * P212f: the own neutral of a native frame: the P121 statistic on the camera's locus (MosaicNeutral, the DNG prior for a curve
     * of fewer than 2 points); with scam_dev.txt "mosaic_awb_v2 1" the robust statistic (W4 = M10: the viewfinder's crop, the
     * result's shading map - else the stored plain one - and noise profile, the RawLevels black). Null: too few usable cells.
     */
    @Nullable
    private com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Result nativeOwnNeutral(java.nio.ByteBuffer buffer, Image img,
            Image.Plane plane, int cfa, com.particlesdevs.photoncamera.processing.RawLevels.Levels levels, int block,
            @Nullable CameraCharacteristics c, TotalCaptureResult result) {
        com.particlesdevs.photoncamera.processing.color.MosaicNeutral.init(ScameraApp.getAppContext());
        final com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Prior prior =
                com.particlesdevs.photoncamera.processing.color.MosaicNeutral.prior(c, mVivoSdkCameraId);
        if (!PreferenceKeys.scamDevSwitch("mosaic_awb_v2", false))
            return com.particlesdevs.photoncamera.processing.color.MosaicNeutral.estimate(mVivoSdkCameraId, buffer,
                    img.getWidth(), img.getHeight(), plane.getRowStride(), cfa, levels.black, levels.white, block, prior);
        final com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Stat stat =
                com.particlesdevs.photoncamera.processing.color.MosaicNeutral.measure2(buffer, img.getWidth(), img.getHeight(),
                        plane.getRowStride(), cfa, levels.black, levels.white, block, mosaicSetup(c, result));
        return stat == null ? null : com.particlesdevs.photoncamera.processing.color.MosaicNeutral.resolve(mVivoSdkCameraId, stat, prior);
    }

    /** P212f (W4): what MosaicNeutral.measure2 reads of a viewfinder frame: the meter's crop and shading map, the noise profile. */
    private com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Setup mosaicSetup(@Nullable CameraCharacteristics c,
                                                                                           @Nullable CaptureResult result) {
        final NativeRawExposure.MeterSetup m = meterSetup(c, result, null);
        final com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Setup s =
                new com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Setup();
        s.left = m.left;
        s.top = m.top;
        s.right = m.right;
        s.bottom = m.bottom;
        s.gains = m.gains;
        s.gainCols = m.gainCols;
        s.gainRows = m.gainRows;
        final Integer iso = result == null ? null : result.get(CaptureResult.SENSOR_SENSITIVITY);
        return s.noise(result == null ? null : result.get(CaptureResult.SENSOR_NOISE_PROFILE), iso == null ? 100 : iso);
    }

    /**
     * P212g (W6 = M6): the press's route for the mosaic white balance (MosaicNeutralResolver): a vivo native FULLRES session (its
     * HAL 3A runs), on P208's still-only route with the median of the normal-mode viewfinder's converged neutrals of the last second;
     * null for any other shot (the OPPO native route and the 12 MP ISZ modules keep P121).
     */
    @Nullable
    private com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.Press mosaicWbAtPress() {
        try {
            if (mVivoSdkRawSession == null || VivoSdkFullRes.oppo() || !nativeVivoFullRes()) return null;
            if (stillOnlyFullRes()) {
                final com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.Press p =
                        mVfNeutrals.stillOnly(physicalID, android.os.SystemClock.elapsedRealtime());
                Log.i("SCAM_CAPTURE", "mosaic white balance at the press: " + p);
                return p;
            }
            return com.particlesdevs.photoncamera.processing.color.MosaicNeutralResolver.Press.vivoNative();
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "mosaic white balance at the press: " + e);
            return null;
        }
    }

    /**
     * P212g (W7 = M12): a plain-Bayer session's converged result (AWB AUTO and CONVERGED, block 1, no native session; at most once a
     * second a camera) teaches PlainColourMemory its neutral, the ISP's matrix - COLOR_CORRECTION_TRANSFORM when the ISP branch of
     * Parameters takes it, else the identity's OPPO ISP matrix com.oplus.custom.ccm (Parameters.oplusCcmInstead's rule) - and the
     * AWB label AWBFrameControlCCT (not the 3745 K init signature). Any brand; a mosaic photo of the camera reads it.
     */
    private void plainColour(TotalCaptureResult result) {
        final long now = android.os.SystemClock.elapsedRealtime();
        if (!com.particlesdevs.photoncamera.processing.color.PlainColourMemory.due(physicalID, now)) return;
        try {
            if (com.particlesdevs.photoncamera.processing.MosaicStream.block() != 1 || OppoNativeFullRes.running()) return;
            final Integer awb = result.get(CaptureResult.CONTROL_AWB_MODE), state = result.get(CaptureResult.CONTROL_AWB_STATE);
            if (awb == null || awb != CaptureResult.CONTROL_AWB_MODE_AUTO || state == null || state != CaptureResult.CONTROL_AWB_STATE_CONVERGED)
                return;
            final Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
            if (n == null || n.length < 3 || !(n[1].floatValue() > 0f)) return;
            final android.hardware.camera2.params.ColorSpaceTransform cst = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
            if (cst == null) return;
            final float[] m = new float[9];
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) m[r * 3 + c] = cst.getElement(c, r).floatValue();
            float[] ccm = null;
            if (com.particlesdevs.photoncamera.processing.render.Parameters.ispCcmUsable(cst)) ccm = m;
            else if (PreferenceKeys.scamDevSwitch("oplus_ccm", true) && com.particlesdevs.photoncamera.processing.color.OplusCcm.identity(m)) {
                try {
                    ccm = com.particlesdevs.photoncamera.processing.color.OplusCcm.parse(result.get(new CaptureResult.Key<>(
                            com.particlesdevs.photoncamera.processing.color.OplusCcm.TAG_NAME, byte[].class)));
                } catch (RuntimeException noTag) {
                    ccm = null;
                }
            }
            if (ccm == null) return;
            int[] label;
            try { label = result.get(AWB_CCT_LABEL); } catch (RuntimeException noTag) { label = null; }
            com.particlesdevs.photoncamera.processing.color.PlainColourMemory.offer(physicalID, n[0].floatValue() / n[1].floatValue(),
                    n[2].floatValue() / n[1].floatValue(), ccm, com.particlesdevs.photoncamera.processing.color.PlainColourMemory.label(label), now);
        } catch (RuntimeException ignored) {
        }
    }

    /** P212f (W5 = M13 phase 2): the last plain teacher sample (elapsedRealtime ms). */
    private long mPlainTeacherMs;
    static final long PLAIN_TEACHER_MS = 1000L;

    /**
     * P212f (W5 = M13 phase 2, log only): a plain-Bayer session's RAW frame about once a second teaches the mosaic white balance
     * (MosaicTeacher): our statistic of the frame (MosaicNeutral.measure2, block 1: the result's shading map and noise profile,
     * the viewfinder's crop) beside the ISP's neutral of the same result - the gates of ParamController.learnNeutral (block 1, AWB
     * AUTO converged / locked, no native session). Any brand; nothing a photo uses (scam_dev.txt "mosaic_awb_teacher 0": none).
     */
    private void plainTeacher(Image img, @Nullable TotalCaptureResult result) {
        if (mVivoSdkRawSession != null || img == null || img.getFormat() != ImageFormat.RAW_SENSOR || mPayloadBad || !mPreviewLayouts.isEmpty())
            return;
        final long now = android.os.SystemClock.elapsedRealtime();
        if (now - mPlainTeacherMs < PLAIN_TEACHER_MS) return;
        try {
            if (com.particlesdevs.photoncamera.processing.MosaicStream.block() != 1 || OppoNativeFullRes.running()
                    || !com.particlesdevs.photoncamera.processing.color.MosaicTeacher.on()) return;
            if (result == null) synchronized (mZslBufferLock) { result = mHexZslResults.get(img.getTimestamp()); }
            if (result == null) return;
            mPlainTeacherMs = now;
            final Integer awb = result.get(CaptureResult.CONTROL_AWB_MODE), state = result.get(CaptureResult.CONTROL_AWB_STATE);
            if (awb == null || awb != CaptureResult.CONTROL_AWB_MODE_AUTO) return;
            if (state != null && state != CaptureResult.CONTROL_AWB_STATE_CONVERGED && state != CaptureResult.CONTROL_AWB_STATE_LOCKED) return;
            final android.util.Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
            if (n == null || n.length < 3 || !(n[1].floatValue() > 0f)) return;
            final float nr = n[0].floatValue() / n[1].floatValue(), nb = n[2].floatValue() / n[1].floatValue();
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            int cfa = mLiveCfaPattern;
            if (cfa < 0) {
                Integer arrangement = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                cfa = arrangement == null ? 0 : arrangement;
            }
            if (cfa > 3) return;
            final com.particlesdevs.photoncamera.processing.RawLevels.Levels levels =
                    com.particlesdevs.photoncamera.processing.RawLevels.current(c, result);
            final Image.Plane plane = img.getPlanes()[0];
            final com.particlesdevs.photoncamera.processing.color.MosaicNeutral.Stat m =
                    com.particlesdevs.photoncamera.processing.color.MosaicNeutral.measure2(plane.getBuffer(), img.getWidth(), img.getHeight(),
                            plane.getRowStride(), cfa, levels.black, levels.white, 1, mosaicSetup(c, result));
            if (m == null) return;
            String id = physicalID;
            if (id == null) return;
            id = id.substring(id.lastIndexOf('/') + 1);
            com.particlesdevs.photoncamera.processing.color.MosaicTeacher.init(ScameraApp.getAppContext());
            final com.particlesdevs.photoncamera.processing.color.MosaicTeacher.Prediction before =
                    com.particlesdevs.photoncamera.processing.color.MosaicTeacher.observe(id, m.r, m.b, nr, nb);
            final int seen = com.particlesdevs.photoncamera.processing.color.MosaicTeacher.seen(id);
            if (seen <= 3 || seen % 30 == 0)
                Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT,
                        "WB TEACHER camera %s: m %.3f / %.3f (sd %.4f, %d cells) -> ISP %.3f / %.3f, predicted before %s%s [%d pairs, %d cells]",
                        id, m.r, m.b, m.sigma(), m.cells, nr, nb, before == null ? "-" : before.toString(),
                        before == null ? "" : String.format(java.util.Locale.ROOT, " (%.3f ln off)",
                                Math.hypot(Math.log(before.r / nr), Math.log(before.b / nb))),
                        seen, com.particlesdevs.photoncamera.processing.color.MosaicTeacher.cells(id)));
        } catch (RuntimeException e) {
            mPlainTeacherMs = now;
        }
    }

    /** P212c: one trace line a metered frame, written once a second (one log entry). */
    private void traceNativeAe(NativeRawExposure ae, @Nullable NativeRawExposure.Metered metered, @Nullable float[] m, Long exposure,
                               Integer iso, float ev, boolean changed, long now) {
        if (m != null) {
            mNativeAeTrace.append('\n').append(String.format(java.util.Locale.ROOT,
                    "%d p50 %.5f p98 %.3f%s | ev %+.1f del %s ms ISO %s | %s%s", now - mNativeAeStartMs, m[0], m[1],
                    metered == null ? "" : String.format(java.util.Locale.ROOT, " P %.3f C %.4f lm %.5f%s law2 x%.3f now x%.3f",
                            metered.peak, metered.clip, metered.logMean, metered.shaded ? " lsc" : "",
                            NativeRawExposure.clipBudget(metered, ev), NativeRawExposure.multiplier(metered.p50, metered.p98, ev)),
                    ev, exposure == null ? "-" : String.format(java.util.Locale.ROOT, "%.3f", exposure / 1e6), iso, ae.state(),
                    changed ? " REBUILD" : ""));
        }
        if (now - mNativeAeTraceMs >= 1000 && mNativeAeTrace.length() > 0) {
            mNativeAeTraceMs = now;
            Log.i("SCAM_AE", "native AE trace camera " + mVivoSdkCameraId + ":" + mNativeAeTrace);
            mNativeAeTrace.setLength(0);
        }
    }

    /** P212c: the user's exposure compensation of the viewfinder request (EV). */
    private float nativeAeEv(@Nullable CameraCharacteristics c) {
        final CaptureRequest.Builder builder = mPreviewRequestBuilder;
        final Integer index = builder == null ? null : builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
        final android.util.Rational step = c == null ? null : c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        return index != null && step != null ? index * step.floatValue() : 0f;
    }

    /**
     * P212z (review): the gains of {@code map} (copyGainFactors order): {@code last} itself when they are its factors at its size
     * ({@code lastCols} x {@code lastRows}) - the array NativeRawExposure.gainMedian caches its median by - else a new array. The
     * factors are read into {@code scratch[0]} (reused frame after frame); a copy that becomes the gains leaves it (never written again).
     */
    static float[] meterGains(android.hardware.camera2.params.LensShadingMap map, @Nullable float[] last, int lastCols, int lastRows,
                              float[][] scratch) {
        final int n = map.getGainFactorCount();
        float[] buffer = scratch[0];
        if (buffer == null || buffer.length != n) buffer = scratch[0] = new float[n];
        map.copyGainFactors(buffer, 0);
        if (last != null && lastCols == map.getColumnCount() && lastRows == map.getRowCount() && Arrays.equals(buffer, last)) return last;
        scratch[0] = null;
        return buffer;
    }

    /**
     * P212c: what the native AE's meter weights: the output crop (the result's crop region, inside it the zoom ratio around its
     * centre - CONTROL_ZOOM_RATIO leaves the crop region whole), the AE regions a tap or a subject set (they differ from the
     * metering mode's own), the result's shading map unless the RAW is shaded already, and the white point.
     */
    private NativeRawExposure.MeterSetup meterSetup(@Nullable CameraCharacteristics c, @Nullable CaptureResult result, @Nullable float[] neutral) {
        final NativeRawExposure.MeterSetup s = new NativeRawExposure.MeterSetup();
        final Rect active = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active != null && active.width() > 0 && active.height() > 0) {
            final Rect region = result == null || XiaomiTeleZoom.routed() ? null : result.get(CaptureResult.SCALER_CROP_REGION);
            if (region != null) {
                final Rect clipped = new Rect(region);
                if (clipped.intersect(active)) {
                    s.left = (clipped.left - active.left) / (float) active.width();
                    s.top = (clipped.top - active.top) / (float) active.height();
                    s.right = (clipped.right - active.left) / (float) active.width();
                    s.bottom = (clipped.bottom - active.top) / (float) active.height();
                }
            }
            Float ratio = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && result != null) {
                try { ratio = result.get(CaptureResult.CONTROL_ZOOM_RATIO); } catch (IllegalArgumentException ignored) { ratio = null; }
            }
            if (ratio != null && ratio > 1.001f) {
                final float cx = 0.5f * (s.left + s.right), cy = 0.5f * (s.top + s.bottom);
                final float hw = 0.5f * (s.right - s.left) / ratio, hh = 0.5f * (s.bottom - s.top) / ratio;
                s.left = cx - hw;
                s.right = cx + hw;
                s.top = cy - hh;
                s.bottom = cy + hh;
            }
            final CaptureRequest.Builder builder = mPreviewRequestBuilder;
            final MeteringRectangle[] regions = builder == null ? null : builder.get(CaptureRequest.CONTROL_AE_REGIONS);
            if (regions != null && regions.length > 0 && !Arrays.equals(regions, mPreviewMeteringAE)) {
                final java.util.ArrayList<float[]> taps = new java.util.ArrayList<>();
                for (MeteringRectangle q : regions) {
                    if (q == null || q.getMeteringWeight() <= 0 || q.getWidth() <= 0 || q.getHeight() <= 0) continue;
                    taps.add(new float[]{(q.getX() - active.left) / (float) active.width(), (q.getY() - active.top) / (float) active.height(),
                            (q.getX() + q.getWidth() - active.left) / (float) active.width(), (q.getY() + q.getHeight() - active.top) / (float) active.height()});
                }
                if (!taps.isEmpty()) s.regions = taps.toArray(new float[0][]);
            }
        }
        // the result's shading map (an OPPO native result has the plain session's, OppoNativeFullRes.complete); P212e (W1): a
        // stored plain map goes into s.gains when the result has none
        final android.hardware.camera2.params.LensShadingMap map = result == null ? null
                : result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        if (map != null && map.getColumnCount() > 1 && map.getRowCount() > 1
                && !com.particlesdevs.photoncamera.processing.render.Parameters.rawShadingAlreadyApplied(c)) {
            // P212z (review): the key is synthesized, every get() builds a new LensShadingMap - the cache keyed on the map's
            // identity never hit, so every metered frame allocated new gains and NativeRawExposure.gainMedian (keyed on the gains
            // array's identity) re-ran its 64 x 48 grid and sort. The factors are compared instead: the same map keeps its array.
            mMeterGains = meterGains(map, mMeterGains, mMeterCols, mMeterRows, mMeterScratch);
            mMeterCols = map.getColumnCount();
            mMeterRows = map.getRowCount();
            s.gains = mMeterGains;
            s.gainCols = map.getColumnCount();
            s.gainRows = map.getRowCount();
        } else if (map == null && !com.particlesdevs.photoncamera.processing.render.Parameters.rawShadingAlreadyApplied(c)) {
            // P212e (W1): a cold start's native result without a map: the camera's stored plain one (one array: the median caches)
            final OppoPlainState.Shading stored = nativeStoredShading(neutral);
            if (stored != null) {
                s.gains = stored.gains;
                s.gainCols = stored.cols;
                s.gainRows = stored.rows;
            }
        }
        if (neutral != null && neutral.length >= 3 && neutral[1] > 0f) {
            s.neutralR = neutral[0] / neutral[1];
            s.neutralB = neutral[2] / neutral[1];
        }
        return s;
    }

    /**
     * P212e (W1 = M2): the stored plain lens shading map of the running OPPO native session's camera (OppoPlainState, the bin of the
     * light nearest to {@code neutral}), for a frame whose result has none - a cold start straight into FULLRES, whose results
     * the stored map could not be put into (OppoNativeFullRes.complete). Null outside an OPPO native session or without one.
     */
    @Nullable
    private OppoPlainState.Shading nativeStoredShading(@Nullable float[] neutral) {
        if (mVivoSdkRawSession == null || !VivoSdkFullRes.oppo() || !nativeVivoFullRes()) return null;
        return OppoNativeFullRes.storedShading(mVivoSdkCameraId, neutral);
    }

    /**
     * P212b: a plain-session frame of an OPPO camera that has a native FULLRES mode, metered as the native AE meters (block 1) for
     * that AE's next start (NativeAeSeed), about 3 times a second, only while the HAL AE is converged / locked and the frame is a
     * plain 16-bit Bayer RAW (no colour-block mosaic, no packed layout). {@code result}: the matched one, else the ZSL results'.
     */
    private void plainMeter(Image img, @Nullable TotalCaptureResult result) {
        if (mVivoSdkRawSession != null || img == null || img.getFormat() != ImageFormat.RAW_SENSOR || mPayloadBad || !mPreviewLayouts.isEmpty())
            return;
        final long now = android.os.SystemClock.elapsedRealtime();
        if (now - mPlainMeterMs < 300) return;
        try {
            if (!OppoNativeFullRes.device() || OppoNativeFullRes.offer(physicalID) == null
                    || com.particlesdevs.photoncamera.processing.MosaicStream.block() > 1) return;
            if (result == null) synchronized (mZslBufferLock) { result = mHexZslResults.get(img.getTimestamp()); }
            if (result == null) return;
            final Integer state = result.get(CaptureResult.CONTROL_AE_STATE);
            if (state == null || state != CaptureResult.CONTROL_AE_STATE_CONVERGED && state != CaptureResult.CONTROL_AE_STATE_LOCKED) return;
            final Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            final Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            if (exposure == null || iso == null || exposure <= 0 || iso <= 0) return;
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            int cfa = mLiveCfaPattern;
            if (cfa < 0) {
                Integer arrangement = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                cfa = arrangement == null ? 0 : arrangement;
            }
            if (cfa > 3) return;
            final com.particlesdevs.photoncamera.processing.RawLevels.Levels levels =
                    com.particlesdevs.photoncamera.processing.RawLevels.current(c, result);
            float[] neutral = null;
            final android.util.Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
            if (n != null && n.length >= 3) neutral = new float[]{n[0].floatValue(), n[1].floatValue(), n[2].floatValue()};
            final Image.Plane plane = img.getPlanes()[0];
            final NativeRawExposure.Metered metered = NativeRawExposure.meter2(plane.getBuffer(), img.getWidth(), img.getHeight(),
                    plane.getRowStride(), cfa, 1, levels.black, levels.white, meterSetup(c, result, neutral));
            mPlainMeterMs = now;
            if (metered != null)
                NativeAeSeed.plainSample(physicalID, metered.p50, metered.p98, (double) exposure * iso / 100.0, now);
        } catch (RuntimeException e) {
            mPlainMeterMs = now;
        }
    }

    /** P212h (A7 = M14 phase 0): the vivo AE audit's frame count and its second's samples (camera thread). */
    private final VivoAeAudit mVivoAeAudit = new VivoAeAudit();

    /**
     * P212h (A7 = M14 phase 0, log only): every 8th viewfinder RAW of a vivo session - the native FULLRES mosaic, an ISZ module's, P208's
     * still-only viewfinder, the plain Bayer stream - metered as the OPPO own AE meters (meter2 at the stream's colour block) and logged
     * once a second beside the HAL's exposure (VivoAeAudit). Only a plain 16-bit RAW, never under a shot.
     */
    private void vivoAeAudit(Image img, @Nullable TotalCaptureResult result) {
        if (img == null || result == null || img.getFormat() != ImageFormat.RAW_SENSOR || mPayloadBad || !mPreviewLayouts.isEmpty()
                || VivoSdkFullRes.oppo() || mVivoSdkCapture != null || burst || mShotInProgress || shotInFlight()
                || !VivoAeAudit.wanted() || !mVivoAeAudit.due()) return;
        try {
            final Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            final Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            if (exposure == null || iso == null || exposure <= 0 || iso <= 0) return;
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            final String kind;
            final int block;
            if (nativeVivoFullRes() && !stillOnlyFullRes()) {
                kind = "native";
                block = Math.max(1, VivoSdkFullRes.profile(mVivoSdkCameraId).nativeBlock);
            } else {
                final int stream = com.particlesdevs.photoncamera.processing.MosaicStream.block();
                block = Math.max(1, stream);
                kind = stillOnlyFullRes() ? "still-only viewfinder" : stream > 1 ? "isz" : "plain";
            }
            int cfa = mLiveCfaPattern;
            if (cfa < 0) {
                Integer arrangement = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                cfa = arrangement == null ? 0 : arrangement;
            }
            if (cfa > 3) return;
            final com.particlesdevs.photoncamera.processing.RawLevels.Levels levels =
                    com.particlesdevs.photoncamera.processing.RawLevels.current(c, result);
            float[] neutral = null;
            final android.util.Rational[] n = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
            if (n != null && n.length >= 3) neutral = new float[]{n[0].floatValue(), n[1].floatValue(), n[2].floatValue()};
            final Image.Plane plane = img.getPlanes()[0];
            final NativeRawExposure.Metered m = NativeRawExposure.meter2(plane.getBuffer(), img.getWidth(), img.getHeight(),
                    plane.getRowStride(), cfa, block, levels.black, levels.white, meterSetup(c, result, neutral));
            if (m == null) return;
            final String line = mVivoAeAudit.add(physicalID, kind, block, m.p50, m.p98, m.peak, m.clip, m.shaded, exposure, iso,
                    result.get(CaptureResult.CONTROL_AE_STATE), nativeAeEv(c), android.os.SystemClock.elapsedRealtime());
            if (line != null) Log.i("SCAM_AE", line);
        } catch (RuntimeException e) {
            Log.d("SCAM_AE", "vivo AE audit: " + e);
        }
    }

    /** P212a: the native AE's ISO probe frame (P212z: its request is the probe request sent). */
    private boolean isNativeIsoProbe(@Nullable TotalCaptureResult result) {
        return isProbe(result == null ? null : result.getRequest(), mNativeIsoProbeRequest);
    }

    /** P212z: a result's request is the probe request (by identity: a repeating request built meanwhile is never one). */
    static boolean isProbe(@Nullable CaptureRequest request, @Nullable CaptureRequest probe) {
        return request != null && request == probe;
    }

    /**
     * P212a: one request of the viewfinder at {@code probeIso} (the AE's shutter, everything else as the repeating request): its
     * result goes through ExposureLimits.observeManual as every result does, so a delivered ISO above the cap clears the cap.
     */
    private void sendNativeIsoProbe(NativeRawExposure ae, int probeIso) {
        final CaptureRequest.Builder builder = mPreviewRequestBuilder;
        final CameraCaptureSession session = mCaptureSession;
        if (builder == null || session == null || burst) return;
        try {
            final Integer before = builder.get(CaptureRequest.SENSOR_SENSITIVITY);
            final CaptureRequest probe;
            // P212z: the builder's tag stays (the probe is known by its request object); its ISO is restored right after the build
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, probeIso);
            try {
                probe = builder.build();
            } finally {
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, before != null ? before : ae.iso());
            }
            mNativeIsoProbeRequest = probe;
            session.capture(probe, mCaptureCallback, mBackgroundHandler);
            Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT, "native AE camera %s: ISO probe %d at %.3f ms (cap %d, %.2f EV short)",
                    mVivoSdkCameraId, probeIso, ae.exposureNs() / 1e6, ae.isoCap(), ae.deficitEv()));
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w("SCAM_CAPTURE", "native AE ISO probe: " + e);
        }
    }

    private void publishLiveRawFrame(Image img, TotalCaptureResult matchedResult) {
        if (!LiveRawFrame.isEnabled() || img == null || matchedResult == null) return; // no result: nothing to develop it with
        if (!isRawFormat(img.getFormat())) return;
        if (mPayloadBad) {
            // The developed RAW viewfinder would show the same garbage: the ISP preview takes over for this session.
            LiveRawFrame.setEnabled(false);
            return;
        }
        // P72: a packed session (17 Ultra tele) whose frame fits none of its layouts and is not plain either: skipped (the last
        // developed frame stays) instead of developing it as 16-bit
        if (!mPreviewLayouts.isEmpty() && img.getFormat() == ImageFormat.RAW_SENSOR && frameLayout(img) == null
                && com.particlesdevs.photoncamera.processing.RawPayloadCheck.dataEnd(img.getPlanes()[0].getBuffer())
                   < img.getPlanes()[0].getBuffer().capacity() - img.getWidth()) return;
        try {
            Image.Plane plane = img.getPlanes()[0];
            CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
            if (c == null) c = mCameraCharacteristics;
            int cfa = 0;
            if (c != null) {
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
            }
            // P127: black and white at the stream's bit depth (RawLevels): the frame's dynamic black when it is in the stream's
            // units or only drifts (OPPO X8U 64 -> 60), rescaled when it is in other units (Realme GT8 Pro: 16 for 64, X300 Ultra
            // DCG: 1030.75 for 64 over 14-bit samples)
            final com.particlesdevs.photoncamera.processing.RawLevels.Levels levels =
                    com.particlesdevs.photoncamera.processing.RawLevels.current(c, colorResult);
            float white = levels.white;
            float[] black = levels.black.clone();
            String sensorPrefix=com.particlesdevs.photoncamera.settings.ModuleSensorSettings.prefix(
                    com.particlesdevs.photoncamera.settings.ModuleSensorSettings.runtimeScope(physicalID));
            android.content.SharedPreferences sensorValues=ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
            float overrideBlack=(float)com.particlesdevs.photoncamera.settings.PreferenceNumber.read(
                    com.particlesdevs.photoncamera.settings.PreferenceValue.get(sensorValues,sensorPrefix+"blackleveloverride"),-1);
            float overrideWhite=(float)com.particlesdevs.photoncamera.settings.PreferenceNumber.read(
                    com.particlesdevs.photoncamera.settings.PreferenceValue.get(sensorValues,sensorPrefix+"whiteleveloverride"),-1);
            if(overrideWhite>0)white=overrideWhite;
            if(overrideBlack>=0&&overrideBlack<white)java.util.Arrays.fill(black,overrideBlack);
            float[] dcpMatrix = com.particlesdevs.photoncamera.processing.color.DcpProfiles.previewMatrix(gains);
            if (dcpMatrix != null) ccm = dcpMatrix;
            // P212h (A6 = M11): a native FULLRES viewfinder whose result transform is the identity (the X7 Ultra's, a vivo mosaic
            // result's) shows the photo's matrix at its neutral (NativeViewfinder; scam_dev.txt "vf_native_ccm 0": the identity)
            else if (nativeVivoFullRes() && com.particlesdevs.photoncamera.processing.color.OplusCcm.identity(ccm)
                    && PreferenceKeys.scamDevSwitch("vf_native_ccm", true)) {
                final float[] photo = nativeViewfinderMatrix(c, colorResult, gains);
                if (photo != null) ccm = photo;
            }
            float[] shading=null;int sw=1,sh=1;
            android.hardware.camera2.params.LensShadingMap map=colorResult==null?null:colorResult.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
            final boolean lscSkipped=com.particlesdevs.photoncamera.processing.render.Parameters.rawShadingAlreadyApplied(c);
            if(map!=null&&lscSkipped)map=null;
            if(map!=null){sw=map.getColumnCount();sh=map.getRowCount();shading=new float[sw*sh*3];
                for(int y=0;y<sh;y++)for(int x=0;x<sw;x++){int i=(y*sw+x)*3;shading[i]=map.getGainFactor(0,x,y);shading[i+1]=(map.getGainFactor(1,x,y)+map.getGainFactor(2,x,y))*.5f;shading[i+2]=map.getGainFactor(3,x,y);}}
            else if(!lscSkipped){
                // P212e (W1): an OPPO native frame of a cold start (no plain session in this process): the stored plain map
                final OppoPlainState.Shading stored=nativeStoredShading(gains[0]>0f&&gains[2]>0f?new float[]{gains[1]/gains[0],1f,gains[1]/gains[2]}:null);
                if(stored!=null){sw=stored.cols;sh=stored.rows;shading=new float[sw*sh*3];final float[] g=stored.gains;
                    for(int i=0;i<sw*sh;i++){shading[3*i]=g[4*i];shading[3*i+1]=(g[4*i+1]+g[4*i+2])*.5f;shading[3*i+2]=g[4*i+3];}}
            }
            Rect imageCrop = img.getCropRect();
            float[] crop = {imageCrop.left/(float)img.getWidth(), imageCrop.top/(float)img.getHeight(),
                    imageCrop.width()/(float)img.getWidth(), imageCrop.height()/(float)img.getHeight()};
            // Camera2 crop coordinates are in the active sensor array, not in the RAW buffer.
            Rect active = c == null ? null : c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            // P41b: on the Xiaomi logical camera the result's crop region is the logical stream's, not the tele RAW frame's
            Rect sensorCrop = colorResult == null || XiaomiTeleZoom.routed() ? null : colorResult.get(CaptureResult.SCALER_CROP_REGION);
            if (active != null && sensorCrop != null && active.width()>0 && active.height()>0) {
                Rect clipped = new Rect(sensorCrop);
                if (clipped.intersect(active)) {
                    crop = new float[]{(clipped.left-active.left)/(float)active.width(),
                        (clipped.top-active.top)/(float)active.height(), clipped.width()/(float)active.width(),
                        clipped.height()/(float)active.height()};
                }
            }
            // P41: the Xiaomi tele's preview crop (zoomRatio against the claimed optics) is not in SCALER_CROP_REGION
            final float teleCrop = XiaomiTeleZoom.active() ? XiaomiTeleZoom.previewCrop() : 1f;
            if (teleCrop > 1.001f) {
                float w = crop[2] / teleCrop, h = crop[3] / teleCrop;
                crop = new float[]{crop[0] + (crop[2] - w) * 0.5f, crop[1] + (crop[3] - h) * 0.5f, w, h};
            }
            double shotNoise=0,readNoise=0;
            android.util.Pair<Double,Double>[] noiseProfile=colorResult.get(CaptureResult.SENSOR_NOISE_PROFILE);
            if(noiseProfile!=null&&noiseProfile.length>0){
                int count=0;for(android.util.Pair<Double,Double> n:noiseProfile)if(n!=null&&n.first!=null&&n.second!=null){shotNoise+=n.first;readNoise+=n.second;count++;}
                if(count>0){shotNoise/=count;readNoise/=count;}
            }
            // P110: the «Движение» shutter ceiling runs AE off for the camera AE: still the automatic rendering
            boolean automatic = !Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_OFF).equals(colorResult.get(CaptureResult.CONTROL_AE_MODE))
                    || paramController.motionCeilingActive();
            // P212h (A6 = M11): the OPPO native results say AE off (its own AE): the display gain covers the exposure the mode cannot
            // deliver (the own AE's deficit), never a hand-set one (NativeViewfinder; scam_dev.txt "vf_native_gain")
            float minGain = 0f, maxGain = Float.POSITIVE_INFINITY;
            final NativeRawExposure nativeAe = mNativeAe;
            final boolean ownAe = nativeAe != null && nativeVivoFullRes() && VivoSdkFullRes.oppo();
            if (!automatic && ownAe) {
                final float[] range = NativeViewfinder.displayGain((int) PreferenceKeys.scamDevNumber("vf_native_gain", 1f),
                        nativeAe.manual(), nativeAe.deficitEv());
                if (range != null) {
                    automatic = true;
                    minGain = range[0];
                    maxGain = range[1];
                }
            }
            // P212h: the frame's median is the shutter / ISO priority's SceneLuma, but not where the own AE meters the other value
            final boolean sceneLuma = !ownAe && PreferenceKeys.scamDevSwitch("vf_raw_scene_luma", true);
            LiveRawFrame.publish(plane.getBuffer(), img.getWidth(), img.getHeight(),
                    readStride(img), cfa, white, black, gains, ccm,shading,sw,sh,crop,
                    // P108: the Pixel's full-resolution RAW is Quad-Bayer (measured block 2), binned for the viewfinder
                    // P208: the still-only route's viewfinder is the normal mode's stream
                    stillOnlyFullRes() ? Math.max(1, com.particlesdevs.photoncamera.processing.MosaicStream.block())
                            : nativeVivoFullRes() ? VivoSdkFullRes.profile(mVivoSdkCameraId).nativeBlock
                            : mFullResSession ? 2 : com.particlesdevs.photoncamera.processing.MosaicStream.block() > 1
                            ? com.particlesdevs.photoncamera.processing.MosaicStream.block() : PreferenceKeys.mosaicBlock(),
                    colorResult.get(CaptureResult.SENSOR_SENSITIVITY),
                    c == null ? null : c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY),
                    automatic,shotNoise,readNoise,
                    readFormat(img), minGain, maxGain, sceneLuma);
            if (mTextureView != null) mTextureView.requestRender();
        } catch (Exception e) {
            Log.w(TAG, "publishLiveRawFrame: " + e.getMessage());
        }
    }

    /** P212h (A6): the camera, neutral (ln r, ln b), matrix (column-major, null: none) and source of the last native viewfinder matrix. */
    private String mVfCcmCamera;
    private float mVfCcmLnR = Float.NaN, mVfCcmLnB = Float.NaN;
    @Nullable private float[] mVfCcm;
    @Nullable private String mVfCcmHow;
    /**
     * P212z (review): counted up by every configured session (onConfigured), so a session never reuses the last one's cached matrix
     * - a cached null too: a cold start into FULLRES had no plain table, a plain session in between taught it this light (or the
     * X7 Ultra's 6x / front anchor), and the identity stayed while the neutral held within 0.01 ln. The session the cache is of.
     */
    private volatile int mVfCcmSession;
    private int mVfCcmSessionSeen = -1;

    /**
     * P212h (A6 = M11): the photo's colour matrix for a native FULLRES viewfinder frame with an identity transform, column-major as the
     * viewfinder takes it, at the frame's neutral (the inverse of its white-balance gains, which the OPPO session fills with its own):
     * the X7 Ultra's tuned matrix (OppoTunedColor.previewMatrix, the photo's math), else the camera's plain-session matrix at that
     * light (PlainColourMemory, as a mosaic photo without an ISP matrix), else on OPPO the frame's com.oplus.custom.ccm. Null: none
     * (the identity stays). Recomputed when the neutral moves by more than 0.01 ln; a change of source is logged once.
     */
    @Nullable
    private float[] nativeViewfinderMatrix(@Nullable CameraCharacteristics c, @Nullable CaptureResult result, float[] gains) {
        if (c == null || gains == null || !(gains[0] > 0f) || !(gains[1] > 0f) || !(gains[2] > 0f)) return null;
        final float[] neutral = {gains[1] / gains[0], 1f, gains[1] / gains[2]};
        final float lnR = (float) Math.log(neutral[0]), lnB = (float) Math.log(neutral[2]);
        final String camera = physicalID;
        final int session = mVfCcmSession;
        if (session != mVfCcmSessionSeen) {
            // P212z (review): a new session looks again (and logs its source once)
            mVfCcmSessionSeen = session;
            mVfCcmCamera = null;
            mVfCcmHow = "";
        }
        if (camera.equals(mVfCcmCamera) && !NativeViewfinder.moved(mVfCcmLnR, mVfCcmLnB, lnR, lnB)) return mVfCcm;
        float[] rowMajor = null;
        String how = null;
        try {
            if (VivoSdkFullRes.oppo() && com.particlesdevs.photoncamera.processing.render.OppoTunedColor.applies()) {
                rowMajor = com.particlesdevs.photoncamera.processing.render.OppoTunedColor.previewMatrix(c, neutral, camera);
                if (rowMajor != null) how = "the OPPO tuned matrix";
            }
            if (rowMajor == null) {
                final com.particlesdevs.photoncamera.processing.color.PlainColourMemory.Lookup table =
                        com.particlesdevs.photoncamera.processing.color.PlainColourMemory.lookup(camera, neutral);
                if (table != null) {
                    rowMajor = table.ccm;
                    how = "the plain-session matrix";
                }
            }
            if (rowMajor == null && VivoSdkFullRes.oppo() && result != null) {
                final float[] oplus = com.particlesdevs.photoncamera.processing.color.OplusCcm.parse(result.get(new CaptureResult.Key<>(
                        com.particlesdevs.photoncamera.processing.color.OplusCcm.TAG_NAME, byte[].class)));
                if (oplus != null && !com.particlesdevs.photoncamera.processing.color.OplusCcm.identity(oplus)) {
                    rowMajor = oplus;
                    how = "the frame's OPPO ISP matrix";
                }
            }
        } catch (RuntimeException e) {
            rowMajor = null;
            how = null;
        }
        mVfCcmCamera = camera;
        mVfCcmLnR = lnR;
        mVfCcmLnB = lnB;
        mVfCcm = rowMajor == null ? null : NativeViewfinder.columnMajor(rowMajor);
        if (!Objects.equals(how, mVfCcmHow)) {
            mVfCcmHow = how;
            Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT, "native viewfinder colour camera %s: %s (neutral %.3f / %.3f)", camera,
                    how == null ? "the identity (no matrix for this light)" : how, neutral[0], neutral[2]));
        }
        return mVfCcm;
    }

    /**
     * P91: the frame for the ring. With «zsl_memfd_ring» (scam_dev, default on) the camera's RAW is copied into the ring's
     * memfd and its Image goes back at once (ArkCam 1.05 / MotionCam: the reader holds a few buffers, not the whole ring); the
     * oldest ring frame makes room when every slot is taken. Without a memfd the Image itself is held, as before.
     */
    private RingFrame ringFrameLocked(Image img) {
        if (mZslRingUnavailable || !PreferenceKeys.scamDevSwitch("zsl_memfd_ring", true)) return RingFrame.of(img);
        // A camera without RAW (vivo X200 Pro front: YUV only, SCAM TEST 2026-10-11) rings YUV: a slot holds one plane, so a
        // multi-plane frame is held as the camera's Image, as before P91.
        if (img.getPlanes().length != 1) return RingFrame.of(img);
        final long need = Math.max(img.getPlanes()[0].getBuffer().capacity(), (long) img.getWidth() * img.getHeight() * 2);
        // P198: the native 50 MP session's ring is sized for its 96 MB frames (ZslRing.createFullRes); a ring of the other kind goes
        final boolean fullRes = nativeZslSession();
        if (mZslRing == null || mZslRing.slotBytes() < need || mZslRing.fullRes() != fullRes) {
            if (mZslRing != null) {
                // a larger stream (another lens / mode): the ring's frames go, then the ring
                while (!mZslRingBuffer.isEmpty()) mZslRingBuffer.pollFirst().close();
                mZslRing.close();
            }
            mZslRing = fullRes ? ZslRing.createFullRes(ScameraApp.getAppContext(), zslRingCapacity(), fullResZslWanted(), need)
                    : ZslRing.create(ScameraApp.getAppContext(), zslRingCapacity(), need);
            if (mZslRing == null) { mZslRingUnavailable = true; return RingFrame.of(img); }
        }
        RingFrame f = mZslRing.store(img);
        while (f == null && !mZslRingBuffer.isEmpty()) {
            mZslRingBuffer.pollFirst().close();
            f = mZslRing.store(img);
        }
        return f != null ? f : RingFrame.of(img);
    }

    private boolean mZslRingUnavailable;

    /** The frames the ring keeps: the setting, within the memfd's slots (without it: the reader's images, P120). */
    private int ringCapacityLocked() {
        return mZslRing != null ? Math.min(zslRingCapacity(), mZslRing.ringSlots()) : Math.min(zslRingCapacity(), MAX_READER_IMAGES - 3);
    }

    /**
     * P200: a FULLRES 50 MP shot's merge starts: the 50 MP ring goes (its frames, then the memfd: the slots no shot frame holds
     * are given back at once, Allocator.arenaRelease); a new one is built after the merge.
     */
    private void releaseFullResRing() {
        synchronized (mZslBufferLock) {
            if (mZslRing == null || !mZslRing.fullRes()) return;
            while (!mZslRingBuffer.isEmpty()) mZslRingBuffer.pollFirst().close();
            mZslRing.close();
            mZslRing = null;
            mZslRingUnavailable = false;
        }
        Log.i("SCAM_CAPTURE", "50 MP ZSL ring released for the merge");
    }

    /** P91: the ring's memfd goes (camera closed); frames a shot still holds keep it until they are freed. */
    private void releaseZslRing() {
        synchronized (mZslBufferLock) {
            while (!mZslRingBuffer.isEmpty()) mZslRingBuffer.pollFirst().close();
            if (mZslRing != null) mZslRing.close();
            mZslRing = null;
            mZslRingUnavailable = false;
        }
    }

    /**
     * P91: the shot's own frames (converted ring frames, the post-shutter series) in free slots of the ring's memfd, so the
     * whole burst is one arena for the worker; a separate arena when the ring has none.
     */
    private com.particlesdevs.photoncamera.util.ShotArena shotArena(int frames, long frameBytes) {
        ZslRing ring;
        synchronized (mZslBufferLock) { ring = mZslRing; }
        com.particlesdevs.photoncamera.util.ShotArena a = ring != null ? ring.reserve(frames, frameBytes) : null;
        return a != null ? a : com.particlesdevs.photoncamera.util.ShotArena.create(frames, frameBytes);
    }

    public static int zslRingCapacity() {
        // One global, user-adjustable capacity. Capture counts never resize the ring.
        // P179: the plain session that shows the viewfinder while a 200 MP shot is merged holds a small ring (no shot is taken
        // then; the merge needs the memory)
        if (PixelPro10.fullResSuspended() || fullResMergeRunning()) return Math.min(SUSPENDED_RING, PreferenceKeys.getZslBufferCountValue());
        return PreferenceKeys.getZslBufferCountValue();
    }

    /** P179: ring slots of the plain session during a 200 MP merge. */
    static final int SUSPENDED_RING = 4;

    private long scamZslShutterTimestamp;
    private int mOpenRetries;

    /** Camera2-domain bracket (no root): N = preview exposure, L/S/ES by the configured steps. */
    private ScamStockAe.Plan scameraBracketPlan() {
        // N is the buffered ZSL RAW at the shutter: take its own metadata, not the
        // latest preview result (that can already belong to another request).
        CaptureResult preview;String nSource="zsl_cutoff";
        synchronized (mZslBufferLock) {
            preview=mHexZslResults.get(scamZslShutterTimestamp);
            if(preview==null) {
                long best=Long.MIN_VALUE;nSource="zsl_newest";
                for(java.util.Map.Entry<Long,TotalCaptureResult> e:mHexZslResults.entrySet())
                    if(e.getKey()<=scamZslShutterTimestamp && e.getKey()>best){best=e.getKey();preview=e.getValue();}
            }
        }
        if(preview==null){preview=mPreviewCaptureResult;nSource="preview";}
        Long nShutter=preview==null?null:preview.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer nIso=preview==null?null:preview.get(CaptureResult.SENSOR_SENSITIVITY);
        // Real N gain from the vendor AE (can exceed the Camera2 range at night).
        final double trueIso=ScamStockAe.Plan.vendorIso(preview);
        final float clip=zslClipFraction();
        mLastZslClipFraction=clip;
        ScamStockAe.Plan plan=ScamStockAe.Plan.scameraPlanner(nShutter==null?0:nShutter,nIso==null?0:nIso,trueIso,clip,
                PreferenceKeys.routeInternalValue("planner_l_ev",1f),PreferenceKeys.routeInternalValue("planner_s_ev",3f),
                PreferenceKeys.routeInternalValue("planner_es_ev",6f),PreferenceKeys.isScamPlannerAdaptive(),
                mCameraCharacteristics,scamZslShutterTimestamp,mSessionGeneration.get())
                .withLongBoost(PreferenceKeys.getScamLongBoostEv(),mCameraCharacteristics);
        Log.i("SCAM_CAPTURE","bracket=SCAMERA_planner Camera2_RAW timestamp="+plan.timestamp+" N="+nShutter+"ns ISO"+nIso
                +" from="+nSource+" "+plan.sceneDescription);
        return plan;
    }

    /**
     * P27: the Hybrid's N without the SCAM HDR planner (whose throws cost Hybrid shots): the ring frame at the shutter, else
     * the newest ring result before it, the preview result, the preview AE values. Null when no N exposure is known at all
     * (the Hybrid then lets the camera's AE expose its frames).
     */
    private ScamStockAe.Plan hybridNPlan() {
        return hybridNPlan(false);
    }

    private ScamStockAe.Plan hybridNPlan(boolean manualPsl) {
        // P84: a hand-set shutter longer than 1/8 s: N at that shutter, exposed after the shutter (PSL)
        final long pslNs=manualPsl?paramController.pslShutterNs():0;
        if(pslNs>0) {
            final int pslIso=paramController.pslIso(pslNs);
            mLastZslClipFraction=0;
            Log.i("SCAM_CAPTURE","bracket=HYBRID N="+pslNs+"ns ISO"+pslIso+" from=manual_psl (shutter longer than the viewfinder's 1/10 s)");
            return ScamStockAe.Plan.nOnly(pslNs,pslIso,scamZslShutterTimestamp,mSessionGeneration.get());
        }
        CaptureResult n;String source="zsl_cutoff";
        synchronized (mZslBufferLock) {
            n=mHexZslResults.get(scamZslShutterTimestamp);
            if(n==null) {
                long best=Long.MIN_VALUE;source="zsl_newest";
                for(java.util.Map.Entry<Long,TotalCaptureResult> e:mHexZslResults.entrySet())
                    if(e.getKey()<=scamZslShutterTimestamp && e.getKey()>best){best=e.getKey();n=e.getValue();}
            }
        }
        if(n==null){n=mPreviewCaptureResult;source="preview";}
        Long ns=n==null?null:n.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso=n==null?null:n.get(CaptureResult.SENSOR_SENSITIVITY);
        long shutter=ns!=null&&ns>0?ns:0;int sensitivity=iso!=null&&iso>0?iso:0;
        if(shutter<=0||sensitivity<=0){shutter=mPreviewExposureTime;sensitivity=mPreviewIso;source="preview_ae";}
        mLastZslClipFraction=zslClipFraction();
        if(shutter<=0||sensitivity<=0){
            Log.w("SCAM_CAPTURE","bracket=HYBRID no N exposure known: the camera's AE exposes the frames");
            return null;
        }
        Log.i("SCAM_CAPTURE","bracket=HYBRID N="+shutter+"ns ISO"+sensitivity+" from="+source);
        return ScamStockAe.Plan.nOnly(shutter,sensitivity,scamZslShutterTimestamp,mSessionGeneration.get());
    }

    /** Fraction of clipped RAW samples in the newest buffered ZSL frame (sparse sample). */
    /** Clipped fraction of the newest buffered RAW at the last plan (the ring is drained before the hybrid plan is built). */
    private float mLastZslClipFraction;
    private float zslClipFraction() {
        // The newest ring frame whose RAW is plain 16-bit: a packed payload reads as ~62 % clipped (X100 Ultra) and planned
        // the bracket from garbage. None plain: no clipping is assumed (the shot then takes N after the shutter).
        RingFrame newest = null;
        com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout packed = null;
        synchronized (mZslBufferLock) {
            int looked = 0;
            for (java.util.Iterator<RingFrame> it = mZslRingBuffer.descendingIterator(); it.hasNext() && looked < 4; looked++) {
                RingFrame candidate = it.next();
                com.particlesdevs.photoncamera.processing.RawPayloadCheck.Result payload = com.particlesdevs.photoncamera.processing.RawPayloadCheck.check(
                        candidate, rawPayloadWhite(mHexZslResults.get(candidate.getTimestamp())));
                if (payload.plain()) { newest = candidate; break; }
                // Xiaomi 17 Ultra tele (logical camera 0): packed MIPI RAW10 / RAW14 behind RAW_SENSOR; its clipping is read from
                // the high bytes (every tele shot planned clip = 0 and no short frame before, 2026-10-10)
                if (candidate.getFormat() == ImageFormat.RAW_SENSOR) {
                    packed = packedLayout(candidate);
                    if (packed != null) { newest = candidate; break; }
                }
            }
            if (newest == null && looked > 0) Log.w("SCAM_CAPTURE", "ZSL clip estimate: no plain 16-bit RAW among the newest " + looked);
        }
        if (newest == null || mCameraCharacteristics == null || newest.getFormat() != ImageFormat.RAW_SENSOR) return 0f;
        try {
            // P104 / P127: the stream clips at the white of its own bit depth (RawLevels: DCG 14 bit under a declared 1023)
            CameraCharacteristics lc = mCameraCharacteristicsMap.get(physicalID);
            int limit = (int) (com.particlesdevs.photoncamera.processing.RawLevels.current(
                    lc == null ? mCameraCharacteristics : lc, null).white * 0.95f);
            if (packed != null) {
                // MIPI RAW10 / RAW14: bytes 0..3 of each 5- / 7-byte group are the high 8 bits of four samples (RAW14 read >> 4:
                // its high byte is the 10-bit value >> 2 as RAW10's)
                final int group = com.particlesdevs.photoncamera.util.RawUnpack.isRaw14(packed.format) ? 7 : 5;
                java.nio.ByteBuffer bytes = newest.buffer().duplicate();
                int w = newest.getWidth(), h = newest.getHeight();
                int limitHigh = packed.format == com.particlesdevs.photoncamera.util.RawUnpack.RAW14 ? limit >> 6 : limit >> 2;
                long clipped = 0, total = 0;
                byte[] row = new byte[w * group / 4];
                for (int y = 0; y < h; y += 8) {
                    bytes.position(y * packed.stride);
                    bytes.get(row, 0, row.length);
                    for (int g = 0; g + group - 1 < row.length; g += group) {
                        if ((row[g] & 0xff) >= limitHigh) clipped++;
                        total++;
                    }
                }
                return total == 0 ? 0f : (float) clipped / total;
            }
            java.nio.ShortBuffer data = newest.buffer().duplicate().order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
            int stride = newest.rowStride() / 2, w = newest.getWidth(), h = newest.getHeight();
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
            Log.w("SCAM_CAPTURE", "ZSL clip estimate unavailable: " + e);
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
    private void dropNonPlainRaw(List<RingFrame> images, java.util.Map<Long, TotalCaptureResult> results) {
        mZslPackedStride.clear();
        int dropped = 0, packed = 0;
        String first = null, packedFirst = null;
        for (java.util.Iterator<RingFrame> it = images.iterator(); it.hasNext();) {
            RingFrame image = it.next();
            com.particlesdevs.photoncamera.processing.RawPayloadCheck.Result payload =
                    com.particlesdevs.photoncamera.processing.RawPayloadCheck.check(image, rawPayloadWhite(results.get(image.getTimestamp())));
            if (payload.plain()) continue;
            if (image.getFormat() == ImageFormat.RAW_SENSOR) {
                // Xiaomi 17 Ultra tele through logical camera 0: packed MIPI RAW10 (mode 4) or RAW14 (mode 9, P72) behind
                // RAW_SENSOR, unpacked on copy.
                com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout layout = packedLayout(image);
                if (layout != null) {
                    mZslPackedStride.put(image.getTimestamp(), layout);
                    if (packedFirst == null) packedFirst = layout.describe();
                    packed++;
                    continue;
                }
            }
            if (first == null) {
                first = payload.error;
                com.particlesdevs.photoncamera.processing.RawPayloadCheck.dumpOnce(image, payload, physicalID);
            }
            image.close();
            it.remove();
            dropped++;
        }
        if (dropped > 0) Log.w("SCAM_HDR", "ZSL: " + dropped + " ring RAWs dropped (" + first + "), " + images.size() + " kept");
        if (packed > 0) Log.w("SCAM_HDR", "ZSL: " + packed + " ring RAWs hold " + packedFirst + " (first), unpacked on copy");
    }

    /** P72: the packed layout behind a RAW_SENSOR image of this camera (RAW10 / RAW14), or null. */
    private com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout packedLayout(Image image) {
        return packedLayout(RingFrame.of(image));
    }

    private com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout packedLayout(RingFrame image) {
        CameraCharacteristics c = mCameraCharacteristicsMap.get(physicalID);
        return com.particlesdevs.photoncamera.processing.RawPayloadCheck.packedLayout(image.buffer(),
                image.getWidth(), image.getHeight(),
                com.particlesdevs.photoncamera.processing.RawPayloadCheck.blackLevel(c == null ? mCameraCharacteristics : c),
                rawPayloadWhite(null));
    }

    /** Ring RAWs (by timestamp) whose RAW_SENSOR buffer holds packed MIPI RAW10 / RAW14, with the layout. */
    private final java.util.Map<Long, com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout> mZslPackedStride = new java.util.HashMap<>();

    private List<ImageFrame> drainZslNormalFrames(int requestedCount,ScamStockAe.Plan stockPlan,boolean defer) {
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
    private List<ImageFrame> drainZslNormalFrames(int requestedCount,ScamStockAe.Plan stockPlan,boolean defer,boolean hybrid) {
        List<RingFrame> rawImages;
        java.util.Map<Long,TotalCaptureResult> selectedMetadata;
        synchronized (mZslBufferLock) {
            rawImages = new ArrayList<>(mZslRingBuffer);
            mZslRingBuffer.clear();
            mScamRingFrozen = false;
            selectedMetadata = new HashMap<>(mHexZslResults);
            mHexZslResults.clear();
        }
        mNativeZslBase=null;
        dropNonPlainRaw(rawImages, selectedMetadata);
        if (PreferenceKeys.isScamEnabled()) {
            // RAW may arrive before its TotalCaptureResult. Select from matched
            // pairs BEFORE taking the newest N images, so older complete ZSL
            // frames can fill the burst without guessing another frame's ISO.
            boolean firstMismatch=true;
            for (java.util.Iterator<RingFrame> it = rawImages.iterator(); it.hasNext();) {
                RingFrame image = it.next();
                TotalCaptureResult result = selectedMetadata.get(image.getTimestamp());
                Long exposure = result == null ? null : result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer iso = result == null ? null : result.get(CaptureResult.SENSOR_SENSITIVITY);
                boolean matchesPlan=true;
                if(stockPlan!=null && !hybrid)try{stockPlan.verifyZslNormal(result,scamZslShutterTimestamp);}
                catch(RuntimeException mismatch){matchesPlan=false;
                    if(result!=null && firstMismatch){firstMismatch=false;Log.w("SCAM_HDR",mismatch.getMessage());}}
                if (exposure == null || exposure <= 0 || iso == null || iso <= 0
                        || !matchesPlan
                        || (scamZslShutterTimestamp <= 0 || image.getTimestamp() > scamZslShutterTimestamp)) {
                    Log.w("SCAM_HDR", "Skip unpaired ZSL RAW timestamp=" + image.getTimestamp()
                            + " result=" + (result != null) + " exposureNs=" + exposure + " ISO=" + iso);
                    image.close();
                    it.remove();
                }
            }
            if (hybrid && !rawImages.isEmpty()) selectHybridRing(rawImages, selectedMetadata, requestedCount);
            Log.i("SCAM_HDR", "ZSL matched RAWs=" + rawImages.size() + " requested=" + requestedCount);
            if(!hybrid && stockPlan!=null && rawImages.size()<4) {
                // Stock normal-back (VAF scamNormalBack 0x29a714): when the
                // planned N exposure is not what the preview stream ran at
                // (low light: longer N than preview), the stock takes N after
                // the shutter. The caller submits all seven planned requests.
                for(RingFrame image:rawImages)image.close();
                Log.i("SCAM_HDR","normalBack: no four buffered RAWs at planned N; N captured after shutter");
                return new ArrayList<>();
            }
        }
        rawImages.sort(java.util.Comparator.comparingLong(RingFrame::getTimestamp));
        int take = Math.min(rawImages.size(), Math.max(0, requestedCount));
        int skip = rawImages.size() - take;
        for (int i = 0; i < skip; i++) rawImages.get(i).close();

        if (PreferenceKeys.isScamEnabled() && take > 0) {
            mNativeZslBase = selectedMetadata.get(rawImages.get(rawImages.size()-1).getTimestamp());
            Log.i("SCAM_HDR", "ZSL shutter cutoff=" + scamZslShutterTimestamp
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
            RingFrame img = rawImages.get(i);
            int format = img.getFormat();
            int rowStride = img.rowStride();
            int pixelStride = img.pixelStride();
            int width = com.particlesdevs.photoncamera.util.Allocator.isPackedRaw(format) ? img.getWidth()
                    : (pixelStride > 0 ? rowStride / pixelStride : img.getWidth());
            int height = img.getHeight();
            int capacity = img.buffer().capacity();
            com.particlesdevs.photoncamera.processing.RawPayloadCheck.Layout packedLayout = mZslPackedStride.get(img.getTimestamp());
            if (packedLayout != null) {
                format = packedLayout.format;
                width = img.getWidth();
                rowStride = packedLayout.stride;
                capacity = packedLayout.stride * height;
            }
            int offset = 0;
            if (!mShutterIszAspect4x3 && ScameraApp.getSettings().aspect169 && width > height) {
                // P118: the one 16:9 crop of every frame of the shot (whole colour blocks, the post-shutter frames the same size)
                final int full = img.getHeight();
                height = com.particlesdevs.photoncamera.processing.Wide169.rows(width, full);
                offset = rowStride * com.particlesdevs.photoncamera.processing.Wide169.firstRow(width, full);
                capacity = rowStride * height;
            }
            Allocator.binning = ScameraApp.getSettings().binning;
            // Deferred: the copy of ~20 RAWs (~200 ms) runs after the bracket is
            // submitted, in parallel with its exposure (startZslCopy).
            ImageFrame frame = defer
                    ? ImageFrame.deferred(img, format, width, rowStride, offset, capacity)
                    : new ImageFrame(img.buffer(), format, width, rowStride, offset, capacity);
            frame.timestamp = img.getTimestamp();frame.fromZsl=true;frame.sourceFormat=format;
            frame.setCaptureMetadata(selectedMetadata.get(frame.timestamp));
            frame.width = ScameraApp.getSettings().binning ? width / 2 : width;
            frame.height = ScameraApp.getSettings().binning ? height / 2 : height;
            if (!defer) img.close();
            mExposures.put(frame.timestamp, frame.measuredExposure > 0 && frame.measuredIso > 0
                    ? frame.measuredExposure / 1e9 * frame.measuredIso : exposureProduct);
            selected.add(frame);
        }
        return selected;
    }

    /**
     * P78: a camera without OIS (Redmi Note 11 Pro: 60 ms handheld frames at night, gyro shake 10..60 x apart inside one burst)
     * takes the steadiest of the newest 2 x requested ring frames at the exposure instead of simply the newest; the newest frame
     * stays (the shutter moment). Reorders {@code exact} (newest first) so that its first {@code requested} frames are the kept ones.
     */
    private void steadiestFirst(List<RingFrame> exact, java.util.Map<Long,TotalCaptureResult> metadata, int requested) {
        if (requested < 2 || exact.size() <= requested || hasOisHardware(mCameraCharacteristics)) return;
        if (!PreferenceKeys.scamDevSwitch("zsl_steadiest", true)) return;
        final boolean comparable = isGyroClockComparable();
        float[] all = new float[Math.min(exact.size(), 2 * requested)];
        int pool = 0; // the newest frames with gyro samples (the gyro ring holds ~2 s)
        for (; pool < all.length; pool++) {
            RingFrame image = exact.get(pool);
            CaptureResult r = metadata.get(image.getTimestamp());
            Long exposure = r != null ? r.get(CaptureResult.SENSOR_EXPOSURE_TIME) : null;
            all[pool] = exposure != null ? ScameraApp.getGyro().zslShake(image.getTimestamp(), exposure, comparable) : Float.NaN;
            if (!(all[pool] >= 0f)) break;
        }
        String source = "gyro";
        if (pool <= requested) {
            // P98: the phone's gyro has no samples for these frames (clock not comparable, ring older than ~2 s): the shake
            // value the HAL put into each frame's result (OPPO gyro.data, vivo gyroDetect), when every candidate has one
            final int n = all.length;
            int hal = 0;
            for (; hal < n; hal++) {
                all[hal] = HalMotion.shake(metadata.get(exact.get(hal).getTimestamp()));
                if (!(all[hal] >= 0f)) break;
            }
            if (hal <= requested) { Log.i("SCAM_HDR", "hybrid ZSL steadiest: gyro samples for " + pool + " ring frames only, newest kept"); return; }
            pool = hal;
            source = "HAL shake";
        }
        int[] order = steadiestOrder(java.util.Arrays.copyOf(all, pool));
        if (order == null) { Log.i("SCAM_HDR", "hybrid ZSL steadiest: no gyro samples, newest frames kept"); return; }
        List<RingFrame> head = new ArrayList<>(exact.subList(0, pool));
        for (int i = 0; i < pool; i++) exact.set(i, head.get(order[i]));
        StringBuilder l = new StringBuilder("hybrid ZSL steadiest (no OIS, ").append(source).append("): ").append(requested).append(" of the newest ").append(pool)
                .append(", ring positions");
        for (int i = 0; i < requested; i++) l.append(' ').append(order[i]);
        Log.i("SCAM_HDR", l.toString());
    }

    /** Order of the pool frames (0 = newest, always first): then by gyro shakiness, lowest first; null when any is unknown. */
    static int[] steadiestOrder(float[] shake) {
        for (float s : shake) if (!(s >= 0f)) return null;
        Integer[] rest = new Integer[shake.length - 1];
        for (int i = 1; i < shake.length; i++) rest[i - 1] = i;
        java.util.Arrays.sort(rest, (a, b) -> shake[a] != shake[b] ? Float.compare(shake[a], shake[b]) : Integer.compare(a, b));
        int[] order = new int[shake.length];
        for (int i = 1; i < shake.length; i++) order[i] = rest[i - 1];
        return order;
    }

    /** P77: the longest gap between two ring frames of one shot (and 4 exposures of the base for long frames); the frames before it
     *  are another moment of the scene. */
    static final long ZSL_RING_MAX_GAP_NS = 500_000_000L;
    static long ringMaxGapNs(CaptureResult base) {
        Long exposure = base != null ? base.get(CaptureResult.SENSOR_EXPOSURE_TIME) : null;
        return Math.max(ZSL_RING_MAX_GAP_NS, exposure != null ? 4 * exposure : 0L);
    }

    /** P111: {@code frame}'s shutter is more than 12 % longer than {@code base}'s (unknown: not longer). */
    static boolean longerShutter(CaptureResult base, CaptureResult frame) {
        Long b = base == null ? null : base.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Long f = frame == null ? null : frame.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        return b != null && f != null && b > 0 && f > b * 1.12;
    }

    /** P27: the Hybrid's ring selection (see drainZslNormalFrames); closes the frames it does not keep. */
    private void selectHybridRing(List<RingFrame> rawImages, java.util.Map<Long,TotalCaptureResult> metadata, int requestedCount) {
        RingFrame base = null;
        for (RingFrame image : rawImages) if (base == null || image.getTimestamp() > base.getTimestamp()) base = image;
        // P100: with CROPPED_RAW every frame says which part of the sensor it holds; frames of another crop (the in-sensor zoom
        // switched while they were taken) are not merged with the base, and the photo is not cropped again by what the RAW holds
        final android.graphics.Rect baseCrop = mCroppedRawSession ? CroppedRaw.of(metadata.get(base.getTimestamp()), physicalID, logicalID) : null;
        if (baseCrop != null) {
            int other = 0;
            for (java.util.Iterator<RingFrame> it = rawImages.iterator(); it.hasNext(); ) {
                RingFrame image = it.next();
                if (image != base && !CroppedRaw.same(baseCrop, CroppedRaw.of(metadata.get(image.getTimestamp()), physicalID, logicalID))) {
                    image.close();
                    it.remove();
                    other++;
                }
            }
            final android.graphics.Rect active = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            final float isz = CroppedRaw.factor(baseCrop, active);
            com.particlesdevs.photoncamera.control.ZoomController.rawCropOfShot(isz);
            Log.i("SCAM_HDR", "hybrid ZSL raw crop " + baseCrop + ": x" + isz + (isz > 1f ? " in-sensor zoom" : "")
                    + (other > 0 ? ", " + other + " ring frames of another crop dropped" : ""));
        }
        final double ref = product(metadata.get(base.getTimestamp()));
        List<RingFrame> exact = new ArrayList<>(), darker = new ArrayList<>(), rest = new ArrayList<>();
        for (RingFrame image : rawImages) {
            double ev = Math.log(product(metadata.get(image.getTimestamp())) / ref) / Math.log(2);
            if (Math.abs(ev) <= 0.05) exact.add(image);
            else if (ev >= -0.5 && ev < 0) darker.add(image);
            else rest.add(image);
        }
        // P111 «Движение»: a ring frame with a longer shutter than the moment's frame is smeared even at the same brightness
        // (the frames of the camera AE before the shutter ceiling took over); as the stock snapshot mode takes fresh frames when
        // its motion level changed, they stay out of the merge
        if (com.particlesdevs.photoncamera.settings.MotionMode.on()) {
            final CaptureResult baseResult = metadata.get(base.getTimestamp());
            int longer = 0;
            for (List<RingFrame> tier : java.util.Arrays.asList(exact, darker)) {
                for (java.util.Iterator<RingFrame> it = tier.iterator(); it.hasNext(); ) {
                    RingFrame image = it.next();
                    if (image != base && longerShutter(baseResult, metadata.get(image.getTimestamp()))) {
                        rest.add(image);
                        it.remove();
                        longer++;
                    }
                }
            }
            if (longer > 0) Log.i("SCAM_HDR", "motion: " + longer + " ring frames with a longer shutter than the moment's frame left out");
        }
        exact.sort(java.util.Comparator.comparingLong(RingFrame::getTimestamp).reversed());
        // P77: frames of the same exposure behind a gap of the ring (the AE went away and came back) show another moment of the
        // scene: everything that moved since then ghosts (Redmi Note 11 Pro: 4 of 12 frames 3.6 s older than the other 8).
        int stale = 0;
        final long maxGap = ringMaxGapNs(metadata.get(base.getTimestamp()));
        for (int i = 1; i < exact.size(); i++) {
            if (exact.get(i - 1).getTimestamp() - exact.get(i).getTimestamp() > maxGap) {
                List<RingFrame> old = exact.subList(i, exact.size());
                stale = old.size();
                rest.addAll(old);
                old.clear();
                break;
            }
        }
        if (stale > 0) {
            final long oldest = exact.get(exact.size() - 1).getTimestamp() - maxGap;
            for (java.util.Iterator<RingFrame> it = darker.iterator(); it.hasNext(); ) {
                RingFrame image = it.next();
                if (image.getTimestamp() < oldest) { rest.add(image); it.remove(); }
            }
            Log.w("SCAM_HDR", "hybrid ZSL: " + stale + " frames at the newest exposure dropped: older than a gap of more than "
                    + maxGap / 1_000_000 + " ms in the ring");
        }
        steadiestFirst(exact, metadata, requestedCount);
        List<RingFrame> keep = new ArrayList<>(exact.subList(0, Math.min(exact.size(), Math.max(1, requestedCount))));
        for (RingFrame image : exact.subList(keep.size(), exact.size())) rest.add(image);
        final int fill = Math.min(Math.max(1, requestedCount), 8);
        int widened = 0;
        if (keep.size() < fill) {
            darker.sort(java.util.Comparator.comparingDouble(image -> -product(metadata.get(image.getTimestamp()))));
            for (RingFrame image : darker) {
                if (keep.size() < fill) { keep.add(image); widened++; } else rest.add(image);
            }
        } else rest.addAll(darker);
        // P27 (VERIFY-11): the ring statistics of every shot (tier 1 = at the newest exposure, tier 2 = darker down to -0.5 EV).
        Log.i("SCAM_HDR", "hybrid ZSL ring: tier1=" + (keep.size() - widened) + " tier2=" + widened + " requested=" + requestedCount
                + " exact=" + exact.size() + " darker=" + darker.size());
        if (widened > 0) Log.w("SCAM_HDR", "hybrid ZSL: " + exact.size() + " ring frames at the newest exposure, widened by " + widened
                + " frames down to -0.5 EV (AE ramp)");
        // W1.0: one summary line for the ring frames not used (one line each pushed the shot over OPPO's log quota).
        if (!rest.isEmpty()) {
            double evMin = Double.POSITIVE_INFINITY, evMax = Double.NEGATIVE_INFINITY;
            long oldest = Long.MAX_VALUE, newestUnused = Long.MIN_VALUE;
            for (RingFrame image : rest) {
                final double ev = Math.log(product(metadata.get(image.getTimestamp())) / ref) / Math.log(2);
                if (ev < evMin) evMin = ev;
                if (ev > evMax) evMax = ev;
                oldest = Math.min(oldest, image.getTimestamp());
                newestUnused = Math.max(newestUnused, image.getTimestamp());
            }
            Log.w("SCAM_HDR", "hybrid ZSL: " + rest.size() + " ring RAWs not used (exposure "
                    + String.format(java.util.Locale.ROOT, "%+.2f..%+.2f EV", evMin, evMax) + " from the newest frame, timestamps "
                    + oldest + ".." + newestUnused + ")");
        }
        for (RingFrame image : rest) image.close();
        rawImages.clear();
        rawImages.addAll(keep);
    }

    private static double product(CaptureResult result) {
        Long ns = result == null ? null : result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = result == null ? null : result.get(CaptureResult.SENSOR_SENSITIVITY);
        return ns == null || iso == null ? 0 : (double) ns * iso;
    }

    private void captureVivoSdkFullRes() {
        try {
            captureVivoSdkFullResChecked();
        } catch (RuntimeException failure) {
            if (mInFlightRescue != null) rescueInFlightShot("SDK setup: " + failure.getMessage());
            else {
                if (mShotArena != null) { mShotArena.release(); mShotArena = null; }
                failPendingShutter(failure);
            }
        }
    }

    /** P198: the ring part of a native 50 MP ZSL shot: its N frames (oldest first), their base, the plan after the shutter. */
    private static final class NativeZslShot {
        final List<ImageFrame> frames;
        final CaptureResult base;
        final HybridPlan plan;
        final boolean verified;

        NativeZslShot(List<ImageFrame> frames, CaptureResult base, HybridPlan plan, boolean verified) {
            this.frames = frames;
            this.base = base;
            this.plan = plan;
            this.verified = verified;
        }
    }

    /**
     * P198: the N frames of a native 50 MP shot from the ZSL ring, selected as the 12 MP Hybrid's (drainZslNormalFrames: before the
     * press, matched results, the newest exposure tier, no stale gap) and checked as the series' stills are (VivoSdkFullResCapture):
     * a frame whose pixels are a confident mosaic of another colour block leaves the merge (the sensor mode was checked as it entered
     * the ring). The post-shutter plan is the 12 MP Hybrid's (HybridPlan.build) from the newest frame's exposure: the OPPO's frames
     * carry its own AE's (NativeRawExposure, AE off), the vivo's the HAL's. Null when no frame is left: the shot is the PSL series.
     */
    @Nullable
    private NativeZslShot takeNativeZsl(VivoSdkFullRes.Profile profile, int requested) {
        if (profile == null) return null;
        final float clip = zslClipFraction();
        mLastZslClipFraction = clip;
        mShutterIszAspect4x3 = true; // the native frames are never cropped to 16:9 (the series' saver: iszAspect4x3)
        final List<ImageFrame> frames = drainZslNormalFrames(requested, null, true, true);
        boolean verified = false;
        int wrongMosaic = 0, notCopied = 0;
        for (java.util.Iterator<ImageFrame> it = frames.iterator(); it.hasNext(); ) {
            final ImageFrame f = it.next();
            try {
                f.materialize(); // a view of its ring slot (plain RAW16), else a copy
            } catch (RuntimeException e) {
                f.close();
                it.remove();
                notCopied++;
                continue;
            }
            final boolean[] mosaic = VivoSdkFullResCapture.mosaicCheck(profile, f.buffer, f.width, f.height, f.width * 2);
            if (mosaic[1]) {
                f.close();
                it.remove();
                wrongMosaic++;
                continue;
            }
            verified |= mosaic[0];
        }
        if (wrongMosaic + notCopied > 0) Log.w("SCAM_CAPTURE", "50 MP ZSL: " + wrongMosaic + " ring frames of another colour block than "
                + profile.nativeBlock + ", " + notCopied + " not copied, left out of the merge");
        if (frames.isEmpty()) {
            Log.w("SCAM_CAPTURE", "50 MP ZSL: no usable ring frame, the series is taken after the shutter (PSL)");
            return null;
        }
        final CaptureResult base = frames.get(frames.size() - 1).getMatchedCaptureMetadata();
        final Long baseNs = base == null ? null : base.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        final Integer baseIso = base == null ? null : base.get(CaptureResult.SENSOR_SENSITIVITY);
        final NativeRawExposure ae = mNativeAe;
        final long nNs = baseNs != null && baseNs > 0 ? baseNs : ae != null ? ae.exposureNs() : (long) mPreviewExposureTime;
        final int nIso = baseIso != null && baseIso > 0 ? baseIso : ae != null ? ae.iso() : mPreviewIso;
        logUnsettledPress(ae, "50 MP ZSL", nNs, nIso);
        HybridPlan plan;
        HybridPlan.setMotionCap(HalMotion.motionCapNs(base));
        try {
            plan = HybridPlan.build(nNs, nIso, clip, mCameraCharacteristics, mExposureLimits);
        } catch (RuntimeException noPlan) {
            Log.w("SCAM_CAPTURE", "50 MP ZSL: hybrid plan unavailable (" + noPlan.getMessage() + "): one more N frame after the shutter");
            plan = HybridPlan.single(nNs, nIso);
        } finally {
            HybridPlan.setMotionCap(0);
        }
        // P219: every ultrashort frame of the plan, one long frame (HybridPlan.fullResLongFrames)
        plan = plan.withLongFrames(HybridPlan.fullResLongFrames(true, Math.max(1, profile.nativeBlock), PreferenceKeys.isHybridMosaicRemosaic()));
        // P198: a ring smaller than the frames asked (96 MB a frame: 4-10 of them) takes the rest of the N right after the shutter -
        // the merge keeps the frame count of the series before P198 (fewer frames left more lattice and noise), the first frames
        // still come from before the press
        final int topUp = Math.max(0, requested - frames.size());
        plan = plan.withLeadingNormals(topUp);
        Log.i("SCAM_CAPTURE", "50 MP ZSL: " + frames.size() + " ring frames + " + topUp + " N after the shutter (asked " + requested + ", mosaic "
                + (verified ? "verified" : "not proven by the ring") + "); " + plan.description);
        return new NativeZslShot(frames, base, plan, verified);
    }

    /**
     * P212b (M8 step 5, the log-only variant): a press while the own AE is still moving. The shot keeps today's frames - the ring's
     * newest exposure tier (selectHybridRing) and the plan from the newest frame, so its N stay one exposure - instead of dropping
     * the ring frames off the AE's target and topping up after the shutter: that trades frames from before the press for later ones
     * (shutter lag, motion between them, fewer N when the memory guard trims the top-up) on every press of a hunting AE, and the
     * seeds (NativeAeSeed) and the EMA / hysteresis already make such presses rare. The line says how often and how far it happens.
     */
    private static void logUnsettledPress(@Nullable NativeRawExposure ae, String route, long nNs, int nIso) {
        if (ae == null || ae.settled()) return;
        Log.i("SCAM_CAPTURE", String.format(java.util.Locale.ROOT, "%s: press while AE unsettled: N %.3f ms ISO %d, the AE asks %s "
                        + "(%+.2f EV)%s", route, nNs / 1e6, nIso, ae, Math.log(ae.total() / Math.max(1.0, (double) nNs * nIso / 100.0)) / Math.log(2),
                ae.pending() ? ", its last change not shown yet" : ""));
    }

    /**
     * P202: the plan of a native 50 MP series after the shutter (PSL): N, then the 12 MP Hybrid plan's frames (ultrashort for
     * Bento, bracketed for Shasta) from the N exposure - the viewfinder's (the HAL's or the OPPO's own AE), the hand-set one
     * (P201), or a hand-set long shutter's plan (HybridPlan.manualLong); {@code requested} N as a ZSL shot's, all of them within
     * what {@code budget} (frames asked -> frames the memory holds) gives; P219: one long frame unless the route's colour
     * {@code block} is binned (HybridPlan.fullResLongFrames). Null when no plan can be made (all N, as before).
     */
    @Nullable
    private HybridPlan pslPlan(int requested, java.util.function.IntUnaryOperator budget, long manualPslNs, int block) {
        final CaptureResult latest = mPreviewCaptureResult;
        final Long previewNs = latest == null ? null : latest.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        final Integer previewIso = latest == null ? null : latest.get(CaptureResult.SENSOR_SENSITIVITY);
        final int manualIso = paramController.manualIsoValue();
        final long manualNs = paramController.manualPreviewShutterNs();
        final float clip = zslClipFraction();
        try {
            HybridPlan plan;
            if (manualPslNs > 0) {
                plan = HybridPlan.manualLong(manualPslNs, paramController.pslIso(manualPslNs), clip, mCameraCharacteristics, mExposureLimits);
            } else {
                final long nNs = manualNs > 0 ? manualNs : previewNs == null ? 0 : previewNs;
                final int nIso = manualIso > 0 ? manualIso : previewIso == null ? 0 : previewIso;
                if (nNs <= 0 || nIso <= 0) return null;
                logUnsettledPress(mNativeAe, "50 MP PSL", nNs, nIso);
                HybridPlan.setMotionCap(HalMotion.motionCapNs(latest));
                try {
                    plan = HybridPlan.build(nNs, nIso, clip, mCameraCharacteristics, mExposureLimits);
                } finally {
                    HybridPlan.setMotionCap(0);
                }
            }
            // P219: every ultrashort frame of the plan, one long frame (HybridPlan.fullResLongFrames); the N take the slots freed
            plan = plan.withLongFrames(HybridPlan.fullResLongFrames(true, block, PreferenceKeys.isHybridMosaicRemosaic()));
            final int extras = plan.requests.size();
            final int frames = budget.applyAsInt(Math.max(1, requested) + extras);
            plan = plan.withLeadingNormals(Math.max(1, frames - extras));
            Log.i("SCAM_CAPTURE", "50 MP PSL: " + plan.requests.size() + " frames after the shutter (" + requested + " N asked, "
                    + frames + " by the memory); " + plan.description);
            return plan;
        } catch (RuntimeException noPlan) {
            Log.w("SCAM_CAPTURE", "50 MP PSL: no hybrid plan (" + noPlan.getMessage() + "): N frames only");
            return null;
        }
    }

    private void captureVivoSdkFullResChecked() {
        final VivoSdkRawSession sdk = mVivoSdkRawSession;
        final CameraCaptureSession session = mCaptureSession;
        if (sdk == null || !isCurrentPreviewSession(session) || mCameraDevice == null
                || !nativeVivoFullRes() && mVivoSdkJpeg == null) {
            failPendingShutter(new IllegalStateException(Lang.t("Сессия SDK RAW закрыта", "SDK RAW session closed")));
            return;
        }
        final long frameBytes = (long) mImageReaderRaw.getWidth() * mImageReaderRaw.getHeight() * 2;
        android.app.ActivityManager manager = (android.app.ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(memory);
        final int rawWidth = mImageReaderRaw.getWidth(), rawHeight = mImageReaderRaw.getHeight();
        final boolean hugeSeries = (long) rawWidth * rawHeight > HUGE_SHOT_PIXELS && nativeVivoFullRes();
        // P179 (owner, 2026-10-16: «в склейке участвовало хотя бы 3 кадра и один из них был ультракороткий», «Сделай 4 кадра»):
        // a 200 MP series is its own count of frames whatever the merge's frame setting - the normal ones, then one ultrashort - as
        // far as the RAW memory guard allows (at least three). P190 (owner, 2026-10-17: «дай возможность ставить столько кадров для
        // 50/200мп сколько я хочу»): «Кадров в снимке 200 Мп» (default HUGE_SERIES) and «Кадров в снимке 50 Мп» (0 = as the merge's
        // frame setting, the path before P190); an explicit count is a series the merge keeps whole, sized here by the memory.
        final int fifty = hugeSeries ? 0 : PreferenceKeys.getFullRes50Frames();
        final boolean seriesMode = hugeSeries || fifty > 0;
        final int requested = hugeSeries ? PreferenceKeys.getFullRes200Frames() : fifty > 0 ? fifty : PreferenceKeys.getHybridZslFrames();
        final long availableBytes = manager == null ? -1 : memory.availMem;
        final int grid = !hugeSeries && !"sensor".equals(PreferenceKeys.hybridOutputMode()) ? 2 : 1;
        // P194: the native route's own colour block (Quad 2, the X100 / X300 Ultra's hardware-remosaic Bayer 1); the SDK route 1
        final int block = hugeSeries ? 4 : nativeVivoFullRes() ? Math.max(1, VivoSdkFullRes.profile(mVivoSdkCameraId).nativeBlock) : 1;
        final int guarded = VivoSdkFullRes.frameCount(requested, frameBytes, availableBytes);
        final int frames = seriesMode ? VivoSdkFullRes.seriesCount(requested, (long) rawWidth * rawHeight, rawWidth, frameBytes, block, grid,
                hugeSeries, availableBytes) : guarded;
        if (hugeSeries) {
            if (frames < 3) {
                failPendingShutter(new IllegalStateException(Lang.t("Недостаточно памяти для серии 200 Мп", "Not enough memory for a 200 MP series")));
                return;
            }
        }
        // P198: a native 50 MP shot takes its N from the ZSL ring (the frames before the press) and only the 12 MP Hybrid plan's
        // frames after the shutter (3 ultrashort for Bento, the bracketed ones for Shasta); none usable in the ring (just opened,
        // every frame of another mode or mosaic): the serial PSL series as before, whose stills keep the failure bookkeeping
        // P201: a hand-set shutter longer than the viewfinder's (PSL_SHUTTER_NS) is never in the ring: the series after the shutter
        final long manualPslNs = paramController.pslShutterNs();
        final NativeZslShot zsl = hugeSeries || !nativeZslSession() || manualPslNs > 0 ? null
                : takeNativeZsl(VivoSdkFullRes.profile(mVivoSdkCameraId), requested);
        final int count, shotN;
        boolean zslTrimmed = false;
        HybridPlan pslPlanBuilt = null;
        if (zsl != null) {
            final int post = zsl.plan.requests.size();
            // the ring frames are in memory already (the ring's memfd): the budget of the series counts their bytes as its own
            final long withRing = availableBytes < 0 ? -1 : availableBytes + (long) zsl.frames.size() * frameBytes;
            final int all = zsl.frames.size() + post;
            final int budget = seriesMode ? VivoSdkFullRes.seriesCount(all, (long) rawWidth * rawHeight, rawWidth, frameBytes, block, grid,
                    false, withRing) : VivoSdkFullRes.frameCount(all, frameBytes, withRing);
            final int keep = VivoSdkFullResCapture.zslNormals(zsl.frames.size(), post, budget);
            zslTrimmed = keep < zsl.frames.size();
            while (zsl.frames.size() > keep) zsl.frames.remove(0).close(); // the oldest go, the moment of the press stays
            count = post;
            shotN = zsl.frames.size();
        } else {
            // P202 (owner, 2026-10-17: «В PSL у нас тоже должны быть брекет кадры»): a native 50 MP series after the shutter is N
            // and the 12 MP Hybrid plan's bracketed / ultrashort frames, as a ZSL shot's frames after the shutter
            pslPlanBuilt = hugeSeries || !nativeVivoFullRes() ? null : pslPlan(requested, all -> seriesMode
                    ? VivoSdkFullRes.seriesCount(all, (long) rawWidth * rawHeight, rawWidth, frameBytes, block, grid, false, availableBytes)
                    : VivoSdkFullRes.frameCount(all, frameBytes, availableBytes), manualPslNs, block);
            count = pslPlanBuilt != null ? pslPlanBuilt.requests.size() : frames;
            shotN = 0;
        }
        final HybridPlan pslPlan = pslPlanBuilt;
        final int total = shotN + count;
        if (count < 1 || (manager != null && memory.lowMemory)) {
            if (zsl != null) for (ImageFrame f : zsl.frames) f.close();
            failPendingShutter(new IllegalStateException(Lang.t("Недостаточно памяти для серии FULLRES", "Not enough memory for a FULLRES series")));
            return;
        }
        cameraEventsListener.onFrameCountSet(total);
        // P190: a series shorter than asked is said aloud (the RAW guard, the merge's memory, or the 2x output at 50 MP); P198 a ZSL
        // shot's N below the ask only when the memory cut it (the ring's own cap is in the log: ZslRing «50 MP ZSL ring in memfd»)
        final String cut = zsl != null ? (shotN >= requested ? "" : zslTrimmed ? "memory" : "ring")
                : count >= requested ? "" : count < guarded && grid == 2 ? "2x" : "memory";
        final int taken = zsl != null ? shotN : count;
        Log.i("SCAM_CAPTURE", (nativeVivoFullRes() ? "NATIVE FULLRES" : "SDK FULLRES50") + " frames=" + total
                + (zsl != null ? " (ZSL: N " + shotN + " from the ring + " + count + " after the shutter)" : "") + " requested=" + requested
                + " availableMB=" + (memory.availMem >> 20) + (seriesMode ? " series" : "") + (cut.isEmpty() ? "" : " cut=" + cut));
        if (!cut.isEmpty() && !"ring".equals(cut))
            showToast(Lang.t("Снято " + taken + " из " + requested + " кадров: свободно " + (memory.availMem >> 20) + " МБ"
                        + ("2x".equals(cut) ? ", вывод 2×" : ""),
                "Took " + taken + " of " + requested + " frames: " + (memory.availMem >> 20) + " MB free" + ("2x".equals(cut) ? ", 2× output" : "")));
        final ImageSaver saver = new ImageSaver(cameraEventsListener);
        saver.iszAspect4x3 = mShutterIszAspect4x3 || nativeVivoFullRes();
        saver.manualWbAtPress = mShutterManualWb;
        saver.mosaicWbAtPress = mShutterMosaicWb;
        saver.setFrameCount(count);
        saver.fullResSeries = seriesMode ? total : 0; // P190: the merge keeps a series whole
        saver.fullResRequested = requested;
        saver.setImageFormat(ImageFormat.RAW_SENSOR);
        mImageSaver = saver;
        clearZslPreviewFrames();
        mPendingZslNormalFrames = new ArrayList<>();
        IsoExpoSelector.fullpairs.clear();
        BurstShakiness = new ArrayList<>();
        final long[] times = new long[count];
        Arrays.fill(times, Math.max(1L, (long) mPreviewExposureTime));
        if (zsl != null) {
            // P198: the ring's N are the first frames of the shot (the saver's buffer, their exposure pairs and gyro windows), as the
            // 12 MP Hybrid's; the stills after the shutter follow
            SaverImplementation.IMAGE_BUFFER.addAll(zsl.frames);
            final long[] zslTimestamps = new long[shotN];
            for (int i = 0; i < shotN; i++) {
                final ImageFrame f = zsl.frames.get(i);
                zslTimestamps[i] = f.timestamp;
                IsoExpoSelector.fullpairs.add(new IsoExpoSelector.ExpoPair(f.measuredExposure, f.measuredExposure, f.measuredExposure,
                        f.measuredIso, f.measuredIso, f.measuredIso, f.measuredIso));
            }
            for (int i = 0; i < count; i++) {
                final HybridPlan.Request r = zsl.plan.requests.get(i);
                if (!r.autoExposure && r.shutterNs > 0) times[i] = r.shutterNs;
            }
            final Long baseExposure = zsl.base.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            ScameraApp.getGyro().buildZslBurstShakiness(zslTimestamps, baseExposure != null ? baseExposure : 0L, BurstShakiness,
                    isGyroClockComparable());
        }
        ScameraApp.getGyro().PrepareGyroBurst(times, BurstShakiness, true);
        final CameraCharacteristics characteristics = mCameraCharacteristics;
        final int rotation = ScameraApp.getGravity().getCameraRotation(mSensorOrientation);
        final com.particlesdevs.photoncamera.util.ShotArena arena = ScameraApp.getSettings().binning
                ? null : shotArena(count, (long) mImageReaderRaw.getWidth() * mImageReaderRaw.getHeight() * 2);
        // P179: a 200 MP shot is merged with the camera closed (its own RAW buffers and the HAL's are free for the merge)
        final boolean hugeShot = (long) mImageReaderRaw.getWidth() * mImageReaderRaw.getHeight() > HUGE_SHOT_PIXELS;
        // P200 (OPPO Find X7 Ultra, 2026-10-17): ColorOS force-stopped the app mid-merge for heat («o-stop(22)», thermal level
        // 14) - the 50 MP session kept streaming and binning 24 fps through a 1-4 min merge. A 50 MP shot is merged with the
        // camera in its plain session as well (its shutter waits for the merge anyway); back to FULLRES after it.
        final boolean plainForMerge = hugeShot || PreferenceKeys.scamDevSwitch("fullres_plain_merge", true);
        final int shotMegapixels = com.particlesdevs.photoncamera.settings.FullResProfiles.megapixels(
                mImageReaderRaw.getWidth(), mImageReaderRaw.getHeight());
        saver.shotArena = arena;
        mShotArena = arena;
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        // P222: the stills the series keeps in flight (FullResInFlight: 6 up to 100 MP, P179's 3 above it or after a failure
        // with more, the SDK route one at a time); a failure of a series with more than 3 is noted for this camera and size
        final String seriesCamera = mVivoSdkCameraId;
        final VivoSdkFullRes.Profile seriesProfile = VivoSdkFullRes.profile(seriesCamera);
        final FullResInFlight.Choice inFlight = seriesProfile == null ? new FullResInFlight.Choice(1, "no profile")
                : FullResInFlight.forSeries(seriesCamera, seriesProfile, count);
        Log.i("SCAM_CAPTURE", "FULLRES in flight: " + inFlight + " for " + count + " stills after the shutter, camera "
                + seriesCamera + (seriesProfile == null ? "" : " " + seriesProfile.size));
        final java.util.concurrent.atomic.AtomicBoolean sent = new java.util.concurrent.atomic.AtomicBoolean();
        // the camera closed or failed under the series (rescueInFlightShot): its reason, which decides whether the count is noted
        final java.util.concurrent.atomic.AtomicReference<String> rescueCause = new java.util.concurrent.atomic.AtomicReference<>();
        final VivoSdkFullResCapture.Listener listener = new VivoSdkFullResCapture.Listener() {
            public void started() {
                cameraEventsListener.onFrameCaptureStarted(null);
            }

            public void result(int index, TotalCaptureResult result) {
                if (done.get()) return;
                // P192: the OPPO native still gets the own neutral, the plain session's colour transform and shading map
                if (VivoSdkFullRes.oppo()) {
                    final String filled = OppoNativeFullRes.completeFrame(mVivoSdkCameraId, result); // P212e: the photo's prior
                    if (index == 0) Log.i("SCAM_CAPTURE", "OPPO native still " + mVivoSdkCameraId + " at "
                            + result.get(CaptureResult.SENSOR_EXPOSURE_TIME) / 1e6 + " ms ISO " + result.get(CaptureResult.SENSOR_SENSITIVITY)
                            + (filled.isEmpty() ? ", nothing filled" : ", filled:" + filled));
                }
                rememberRawMetadata(result);
                mCaptureResult = result;
                mCaptureRequest = result.getRequest();
                long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                int iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                IsoExpoSelector.ExpoPair pair = new IsoExpoSelector.ExpoPair(exposure, exposure, exposure, iso, iso, iso, iso);
                final Object tag = result.getRequest() == null ? null : result.getRequest().getTag();
                final ImageFrame.CaptureRole role = tag instanceof ImageFrame.ScamCaptureTag ? ((ImageFrame.ScamCaptureTag) tag).role : null;
                pair.isLongFrame = role == ImageFrame.CaptureRole.LONG;
                pair.isHighlightFrame = role == ImageFrame.CaptureRole.SHORT || role == ImageFrame.CaptureRole.EXTRA_SHORT;
                IsoExpoSelector.fullpairs.add(pair);
                ScamCaptureLog.result(result.getRequest(), result, scamZslShutterTimestamp, false);
                ScameraApp.getGyro().CaptureGyroBurst();
                cameraEventsListener.onFrameCaptureCompleted(new TimerFrameCountViewModel.FrameCntTime(shotN + index + 1, total, exposure / 1e9));
            }

            public void complete(ScamCaptureSequence sequence, HashMap<Long, Double> exposures) {
                if (done.get()) return;
                try {
                    for (ImageFrame frame : saver.snapshotFrames())
                        if (frame == null || frame.rawPayloadError != null)
                            throw new IllegalStateException(frame == null ? "SDK RAW frame missing" : frame.rawPayloadError);
                    // P198: a ZSL shot's base is the newest ring frame (the moment of the press), as the 12 MP Hybrid's
                    CaptureResult base = zsl != null ? zsl.base : sequence.firstBoundResult(ImageFrame.CaptureRole.NORMAL);
                    if (base == null) throw new IllegalStateException("SDK RAW base metadata missing");
                    saver.updateFrameCount(zsl != null ? saver.bufferSize() : count);
                    saver.detachForQueue(true);
                    // Captured views pin the data; release the capture owner before processing.
                    if (arena != null) arena.release();
                    ScameraApp.getGyro().CompleteSequence();
                    final ArrayList<GyroBurst> shakiness = new ArrayList<>(BurstShakiness);
                    final String mosaicKey = com.particlesdevs.photoncamera.processing.MosaicStream.key();
                    final com.particlesdevs.photoncamera.processing.ShotTimeline timeline =
                            com.particlesdevs.photoncamera.processing.ShotTimeline.detach();
                    sScamPending.incrementAndGet();
                    if (plainForMerge) closeCameraForProcessing(shotMegapixels);
                    else {
                        sFullResMerges.incrementAndGet();
                        releaseFullResRing();
                    }
                    SCAM_PROCESSING.execute(() -> {
                        com.particlesdevs.photoncamera.processing.ShotTimeline.attach(timeline);
                        try {
                            com.particlesdevs.photoncamera.processing.MosaicBlockStore.setShotKey(mosaicKey, 1);
                            if (plainForMerge) awaitClosedForProcessing();
                            saver.runRaw(characteristics, base, base.getRequest(), shakiness, rotation, exposures);
                        } catch (Exception failure) {
                            Log.e(TAG, "SDK FULLRES50 processing: " + Log.getStackTraceString(failure));
                            cameraEventsListener.onProcessingError(failure.getLocalizedMessage());
                        } finally {
                            if (arena != null) arena.release();
                            sScamPending.decrementAndGet();
                            if (!hugeShot) sFullResMerges.decrementAndGet();
                            com.particlesdevs.photoncamera.processing.ShotTimeline.print();
                            if (plainForMerge) reopenCameraAfterProcessing();
                        }
                    });
                    done.set(true);
                    mMeasuredFrameCnt = zsl != null ? saver.bufferSize() : count;
                    mInFlightRescue = null;
                    mVivoSdkCapture = null;
                    mShotArena = null;
                    mNativeRawPslCapture = false;
                    mShotInProgress = false;
                    burst = false;
                    mState = STATE_PREVIEW;
                    cameraEventsListener.onCaptureSequenceCompleted(null);
                    if (zsl != null) {
                        final String dropped = sequence.droppedSummary();
                        Log.i("SCAM_CAPTURE", "NATIVE FULLRES ZSL: " + shotN + " ring RAWs + " + sequence.boundFutureCount() + " of " + count
                                + " after the shutter; Hybrid ZSL" + (dropped.isEmpty() ? "" : " (dropped: " + dropped + ")"));
                    } else Log.i("SCAM_CAPTURE", "SDK FULLRES50: " + count + " matched future RAWs; Hybrid PSL, stock ZSL=false");
                    Log.i("SCAM_CAPTURE", "FULLRES in flight: series done with " + inFlight.count + " in flight");
                } catch (RuntimeException failure) {
                    failed(failure.getMessage());
                }
            }

            public void degraded(String reason) {
                if (!done.get()) FullResInFlight.failed(seriesCamera, seriesProfile, inFlight.count, reason);
            }

            public void failed(String reason) {
                if (!done.compareAndSet(false, true)) return;
                // P222: a series sent with more than 3 in flight that failed for a reason the count may cause: 3 from the next one
                if (sent.get()) {
                    final String cause = rescueCause.get();
                    FullResInFlight.failed(seriesCamera, seriesProfile, inFlight.count, cause != null ? cause : reason);
                }
                VivoSdkFullResCapture capture = mVivoSdkCapture;
                mVivoSdkCapture = null;
                if (capture != null) capture.cancel();
                mInFlightRescue = null;
                saver.discardFrames();
                if (arena != null) arena.release();
                if (mShotArena == arena) mShotArena = null;
                ScameraApp.getGyro().CompleteSequence();
                mNativeRawPslCapture = false;
                mShotInProgress = false;
                burst = false;
                mState = STATE_PREVIEW;
                cameraEventsListener.onCaptureSequenceCompleted(null);
                // P192: an OPPO still that proved another mode or mosaic: the camera's next candidate the next time; P207 the X100 /
                // X300 Ultra's too (the X100 Ultra's log: its main's row 0 refused twice as no Quad, never noted as failed)
                if (reason != null && (reason.contains("sensor mode is not active")
                        || reason.contains("mosaic does not match") || reason.contains("not been verified"))) {
                    if (VivoSdkFullRes.oppo())
                        showError(OppoNativeFullRes.failed(mVivoSdkCameraId, VivoSdkFullRes.profile(mVivoSdkCameraId), reason));
                    else if (nativeVivoFullRes()) {
                        final String note = VivoNativeFullRes.failed(mVivoSdkCameraId, VivoSdkFullRes.profile(mVivoSdkCameraId), reason);
                        if (note != null) showError(note);
                    }
                }
                cameraEventsListener.onProcessingError(reason);
                Log.w("SCAM_CAPTURE", "SDK FULLRES50 failed: " + reason);
                // P179: a 200 MP series stopped the preview at the shutter: the camera opens again for it
                if (hugeShot) new Handler(android.os.Looper.getMainLooper()).post(CaptureController.this::restartCamera);
                PixelPro10.setFullRes(false);
                if (isCameraResumed && mCaptureSession == session)
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (isCurrentPreviewSession(session)) restartCamera();
                    });
            }
        };
        mNativeRawPslCapture = true;
        burst = true;
        mState = STATE_PICTURE_TAKEN;
        final VivoSdkFullResCapture capture;
        try {
            capture = new VivoSdkFullResCapture(mCameraDevice, session, sdk,
                mImageReaderRaw.getSurface(), mVivoSdkJpeg == null ? null : mVivoSdkJpeg.getSurface(), saver, mBackgroundHandler,
                () -> {
                    CaptureResult latest = mPreviewCaptureResult;
                    Long timestamp = latest == null ? null : latest.get(CaptureResult.SENSOR_TIMESTAMP);
                    return timestamp == null ? 0 : timestamp;
                }, rotation, mShutterGeneration, scamZslShutterTimestamp, count,
                VivoSdkFullRes.profile(mVivoSdkCameraId), mPreviewCaptureResult.getRequest(),
                characteristics.getAvailableSessionKeys(), listener,
                image -> mLiveMetadata.image(image.getTimestamp(), image), inFlight.count);
            // P198: the stills after the shutter are the Hybrid plan's (the native mode's keys on each, as every still of the series)
            if (zsl != null) {
                capture.plan(zsl.plan);
                capture.zsl(zsl.frames, zsl.verified);
            } else if (pslPlan != null) {
                capture.plan(pslPlan);
            } else {
                // P201: the series' N at the hand-set exposure: a long shutter at its ISO (pslIso), else the hand-set ISO / shutter
                // with the viewfinder's other value (the ZSL shot's N are its ring frames, the plan built from them)
                final CaptureResult latest = mPreviewCaptureResult;
                final Long previewNs = latest == null ? null : latest.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                final Integer previewIso = latest == null ? null : latest.get(CaptureResult.SENSOR_SENSITIVITY);
                final int manualIso = paramController.manualIsoValue();
                final long manualNs = paramController.manualPreviewShutterNs();
                long nNs = 0;
                int nIso = 0;
                if (manualPslNs > 0) {
                    nNs = manualPslNs;
                    nIso = paramController.pslIso(manualPslNs);
                } else if (manualIso > 0 || manualNs > 0) {
                    nNs = manualNs > 0 ? manualNs : previewNs == null ? 0 : previewNs;
                    nIso = manualIso > 0 ? manualIso : previewIso == null ? 0 : previewIso;
                }
                if (nNs > 0 && nIso > 0) {
                    capture.manualNormals(nNs, nIso);
                    Log.i("SCAM_CAPTURE", "FULLRES manual exposure: N " + nNs + " ns ISO " + nIso);
                }
            }
        } catch (RuntimeException failure) {
            listener.failed(failure.getMessage()); // the ring frames already in the saver's buffer go with it
            return;
        }
        // P192 (OPPO Find X7 Ultra 50 MP, 2026-10-17: the windows came out pink and without sky, the plain shot of the same scene
        // white with sky): a native 50 MP series ends with the ultrashort frame as well - the plain shots and ISZ have their
        // Bento frame, the native merge takes it (ISZ: «23U», «BENTO: applied»); without it the clipped green turned pink
        if (zsl == null && pslPlan == null && (hugeSeries || nativeVivoFullRes() && count >= 3) && HybridPlan.bentoMode() > 0) {
            // P179: the last still of a 200 MP series is the ultrashort (Bento) one: the preview's exposure over the ultrashort
            // factor at its sensitivity; with Bento off (the worker would leave it out) the series is all N
            CaptureResult latest = mPreviewCaptureResult;
            Long exposure = latest == null ? null : latest.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = latest == null ? null : latest.get(CaptureResult.SENSOR_SENSITIVITY);
            if (exposure != null && iso != null && exposure > 0 && iso > 0) {
                long es = Math.max(ULTRASHORT_MIN_NS, Math.round(exposure / HybridPlan.ultrashortFactor()));
                capture.ultrashortLast(es, iso);
                Log.i("SCAM_CAPTURE", (hugeSeries ? "200 MP: " : "50 MP: ") + (count - 1) + " N + 1 ultrashort " + es + " ns ISO " + iso
                        + " (N " + exposure + " ns; " + count + " of " + requested + " frames asked)");
                // P212h (A7 = M14 phase 1b, scam_dev.txt "vivo_still_us_from_n", default off): P208's still-only viewfinder is the
                // normal mode; the ultrashort follows the first N the 200 MP mode delivers instead (from 4 stills)
                if (stillOnlyFullRes() && !VivoSdkFullRes.oppo() && PreferenceKeys.scamDevSwitch("vivo_still_us_from_n", false)) {
                    final android.util.Range<Integer> isoRange = characteristics == null ? null
                            : characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                    capture.ultrashortFromFirstN(HybridPlan.ultrashortFactor(), ULTRASHORT_MIN_NS, isoRange == null ? 1 : isoRange.getLower());
                }
            }
        }
        // P212h (A7 = M14 phase 1a, scam_dev.txt "vivo_series_ae_lock", default off): a vivo 200 MP PSL series holds the exposure the
        // viewfinder AE converged to on its automatic N stills (AE lock); not converged at the press: AE on as before
        if (hugeSeries && zsl == null && !VivoSdkFullRes.oppo() && PreferenceKeys.scamDevSwitch("vivo_series_ae_lock", false)) {
            final CaptureResult latest = mPreviewCaptureResult;
            final Integer aeState = latest == null ? null : latest.get(CaptureResult.CONTROL_AE_STATE);
            final boolean converged = aeState != null && aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED;
            capture.lockNormals(converged);
            Log.i("SCAM_CAPTURE", "200 MP series: " + (converged ? "AE lock on the N stills (the viewfinder AE converged)"
                    : "AE on, no lock (the viewfinder AE state " + aeState + " at the press)"));
        }
        mVivoSdkCapture = capture;
        mInFlightRescue = () -> {
            rescueCause.set(mInFlightRescueReason);
            listener.failed(Lang.t("Серия SDK RAW прервана", "SDK RAW series interrupted"));
        };
        // P198: a ZSL shot's N are taken already: the press is an instant shot for the UI, as the 12 MP Hybrid's
        cameraEventsListener.onCaptureStillPictureStarted(zsl != null ? "ScamZslCaptureStarted" : "CaptureStarted!");
        cameraEventsListener.onBurstPrepared(null);
        ScameraApp.getGyro().startBurstRegistration();
        sent.set(true);
        capture.start();
    }

    private boolean isGyroClockComparable() {
        Integer source = mCameraCharacteristics == null ? null
                : mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        return source != null && source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
    }

    private void captureStillPicture() {
        // SCAM may wait briefly for the stock AE plan; never block the UI thread.
        if (PreferenceKeys.isScamEnabled() && mBackgroundHandler != null
                && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            mBackgroundHandler.post(this::captureStillPicture);
            return;
        }
        com.particlesdevs.photoncamera.control.ZoomController.markShot();
        try {
            if (mClosedForProcessing) { // P179: the plain session of a running FULLRES merge (200 MP; P200 50 MP too) takes no shot
                failPendingShutter(new IllegalStateException(PixelPro10.suspendedText())); // P214h: the size being merged
                return;
            }
            if (null == mCameraDevice) {
                failPendingShutter(new IllegalStateException(Lang.t("Камера закрыта", "Camera closed")));
                return;
            }
            SensorConfigInjector.applyToSensor(physicalID, this);
            // Every photo is merged by the SCAM Hybrid or SCAM HDR, both from the ZSL RAW stream (Photo and Night).
            if (!PreferenceKeys.isScamEnabled() || !isZslMode())
                throw new IllegalStateException(Lang.t("Нет маршрута склейки: Hybrid и SCAM HDR снимают из ZSL RAW-потока (режимы Фото и Ночь)", "No merge route: Hybrid and SCAM HDR shoot from the ZSL RAW stream (Photo and Night modes)"));
            final boolean scamCapture = PreferenceKeys.isScamEnabled();
            // The SCAM Hybrid takes the SCAM capture route with its own settings (independent of SCAM HDR; wins over it).
            final boolean scamHybridSelected = scamCapture && PreferenceKeys.isScamHybridEnabled();
            final boolean hybridZslRequested = isZslMode() && needsExposureBracket();
            // P84: the Hybrid with a hand-set shutter longer than 1/8 s takes its frames after the shutter (PSL), not from the ring
            final boolean manualPsl = scamHybridSelected && paramController.pslShutterNs() > 0;
            ScamStockAe.Plan planned;
            boolean scamToHybrid=false;
            {
                if(!hybridZslRequested)throw new IllegalStateException((scamHybridSelected?"Hybrid":"SCAM HDR")+Lang.t(" требует ZSL RAW-поток"," needs the ZSL RAW stream"));
                // The hybrid's N is the ring at the preview exposure (P27: its own N, never the SCAM HDR planner).
                if(scamHybridSelected) {
                    planned=hybridNPlan(manualPsl);
                } else if(!PreferenceKeys.useStockBracketPlanner()) {
                    try {
                        planned=scameraBracketPlan();
                    } catch(IllegalStateException noBracket) {
                        // P27: no S/ES plan (bright scene at the sensor floor, no N metadata): this shot is captured and merged
                        // by the Hybrid instead of failing.
                        Log.w("SCAM_CAPTURE","SCAM HDR plan unavailable ("+noBracket.getMessage()+"); this shot is taken by the Hybrid");
                        scamToHybrid=true;
                        planned=hybridNPlan();
                    }
                } else {
                ScamStockAe stock=mStockAe;
                final boolean mayWait=android.os.SystemClock.elapsedRealtime()<mScamPlanDeadline && mBackgroundHandler!=null;
                if(stock==null || stock.generation!=mSessionGeneration.get()) {
                    if(mayWait){mBackgroundHandler.postDelayed(this::captureStillPicture,50);return;}
                }
                // Poll without blocking this camera thread: it also delivers
                // the preview results the stock AE plan is bound to.
                ScamStockAe.Plan solved;
                try {
                    if(stock==null || stock.generation!=mSessionGeneration.get())
                        throw new IllegalStateException(Lang.t("Стоковый AE ещё не готов","Stock AE is not ready yet"));
                    ScamStockAe.Plan ready=stock.tryFreeze(scamZslShutterTimestamp,mayWait);
                    if(ready==null){mBackgroundHandler.postDelayed(this::captureStillPicture,50);return;}
                    solved=ready.withDistinctBracket(mCameraCharacteristics)
                            .withLongBoost(PreferenceKeys.getScamLongBoostEv(),mCameraCharacteristics);
                } catch(IllegalStateException unavailable) {
                    // No usable stock plan (observer failed, stale, or bright scene the
                    // stock bracket cannot separate): the shot still goes through.
                    Log.w("SCAM_CAPTURE","stock plan unavailable ("+unavailable.getMessage()+"); using SCAMERA planner for this shot");
                    solved=null;
                }
                mStockObserverReady=solved!=null;
                if(solved!=null && solved.fitsCamera(mCameraCharacteristics)) {
                    planned=solved;
                    Log.i("SCAM_CAPTURE","bracket=Scam_stock_solver Camera2_RAW frameId="+planned.frameId+" timestamp="+planned.timestamp+" "+planned.sceneDescription);
                } else {
                    // The vendor gain->ISO conversion is the main camera's; on other
                    // modules (HP9 tele) the plan can land outside the Camera2 range.
                    if(solved!=null)Log.w("SCAM_CAPTURE","stock plan outside Camera2 range for camera "+ScameraApp.getSettings().mCameraID
                            +" ("+solved.describeRanges()+"; "+ScamStockAe.Plan.describeCamera(mCameraCharacteristics)
                            +"); using SCAMERA planner for this shot");
                    try {
                        planned=scameraBracketPlan();
                    } catch(IllegalStateException noBracket) {
                        Log.w("SCAM_CAPTURE","SCAM HDR plan unavailable ("+noBracket.getMessage()+"); this shot is taken by the Hybrid");
                        scamToHybrid=true;
                        planned=hybridNPlan();
                    }
                }
                }
            }
            final ScamStockAe.Plan stockPlan=planned;
            // The capture route of this shot (P27: a SCAM HDR shot without an S/ES plan is captured and merged by the Hybrid).
            final boolean scamHybridShot=scamHybridSelected||scamToHybrid;
            final HybridPlan[] hybridPlanHolder={null};
            final float[] normalBackLongRatio={0f};
            final boolean scamZslRequested = hybridZslRequested && scamCapture;
            if (scamZslRequested) {
                mLiveRawRouter.clear();
                mNativeRawPslCapture = true;
            }
            {
                mLiveRawRouter.clear();
                mNativeRawPslCapture=true;mZslCapturing=true;
                if(!hybridZslRequested || manualPsl) synchronized(mZslBufferLock) {
                    for(RingFrame image:mZslRingBuffer)image.close();
                    mZslRingBuffer.clear();mHexZslResults.clear();
                }
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder;
            if(ScameraApp.getSettings().selectedMode.equals(CameraMode.RAWVIDEO)) {
                captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                captureBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, getSelectedFpsRange());
            } else {
                captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            }
            float focus = mFocus;
            double frametime = stockPlan != null ? ExposureIndex.time2sec(stockPlan.shutter(0)) : 1 / 30.0;
            //this.mCaptureSession.stopRepeating();
            // SCAM keeps the preview running: its L/S/ES must not reach the
            // viewfinder (they flashed dark/bright there), as in the stock camera.
            captureBuilder.addTarget(mImageReaderRaw.getSurface());
            // P90 (owner: the viewfinder froze 0.7-0.8 s at every shot): as ArkCam and other stock cameras do, the series also feeds the viewfinder;
            // the renderer scales each series frame back to the viewfinder's exposure (ViewfinderGain), so nothing flashes.
            // scam_dev "series_viewfinder 0": RAW only, as before.
            if (surface != null && !VivoPd2454NativeRaw.active(physicalID)
                    && PreferenceKeys.scamDevSwitch("series_viewfinder", true)) {
                captureBuilder.addTarget(surface);
                com.particlesdevs.photoncamera.ui.camera.views.viewfinder.ViewfinderGain.setReference(
                        mPreviewIso * (mPreviewExposureTime / 1e9));
            }
            Camera2ApiAutoFix.resetTileSize();
            applyZoom(captureBuilder, false);
            cameraRotation = ScameraApp.getGravity().getCameraRotation(mSensorOrientation);

            //captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            //setCaptureAEMode(captureBuilder);
            if (mFlashed) captureBuilder.set(FLASH_MODE, FLASH_MODE_TORCH);
            Log.d(TAG, "Focus:" + focus);
            captureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);

            int[] stabilizationModes = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            if (oisRequestable(stabilizationModes)) {
                Log.d(TAG, "LENS_OPTICAL_STABILIZATION_MODE");
                applyOisMode(captureBuilder, true);//Fix ois bugs for preview and burst
            }
            // P54b: the series keeps the session's stabilisation (OIS ON, the vivo EIS key), as the stock app's requests do
            if (mForcedStab) ForcedStabilization.apply(captureBuilder, oisForceable(), mForcedStabEis);

            for (int i = 0; i < 3; i++) {
                Log.d(TAG, "Temperature:" + mPreviewTemp[i]);
            }
            Log.d(TAG, "CaptureBuilderStarted!");
            //setAutoFlash(captureBuilder);
            //int rotation = Interface.getGravity().getCameraRotation();//activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ScameraApp.getGravity().getCameraRotation(mSensorOrientation));
            if (mTouchFocus != null && mTouchFocus.isTouchFocus) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_REGIONS));
                captureBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AF_REGIONS));
            } else {
                applyAeMeteringRegions(captureBuilder);
            }
            VendorTagUtils.builderSessionApply(captureBuilder, true, useMaximumResolutionKey, physicalID);
            // P108: the full-resolution RAW stream takes MAXIMUM_RESOLUTION requests only
            if (mFullResSession && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                captureBuilder.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
            XiaomiTeleZoom.applyLast(captureBuilder, physicalID);
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
            if (manualPsl) {
                // as an empty ring: every frame after the shutter, the viewfinder keeps running, RAWs routed by their timestamp
                mHybridZslCapture = false;
                mZslCapturing = true;
                mPendingZslNormalFrames = new ArrayList<>();
                if (scamZslRequested) {
                    mLiveRawRouter.clear();
                    mScamRouted = true;
                }
                Log.i("SCAM_CAPTURE", "manual shutter " + paramController.pslShutterNs() + " ns: PSL, the ZSL ring is not used");
            } else if (hybridZslRequested) {
                // Block preview RAWs first. Do not route them into ImageSaver:
                // queued preview images would consume the bracket frame slots.
                mHybridZslCapture = false;
                mZslCapturing = true;
                // This graph consumes four N inputs; avoid selecting an old
                // reference from a longer series that the graph cannot use.
                // The graph consumes four N; frames beyond four (same exposure,
                // newest first) are merged into those slots by the worker.
                // P113: «Движение» behind a queue of merges takes fewer ring frames, as the stock snapshot mode
                final int zslWanted = scamHybridShot ? PreferenceKeys.getHybridZslFrames() : Math.max(4, PreferenceKeys.getScamZslFrames());
                final int zslFrames = scamHybridShot && com.particlesdevs.photoncamera.settings.MotionMode.on()
                        ? com.particlesdevs.photoncamera.settings.MotionMode.framesForQueue(zslWanted, sScamPending.get()) : zslWanted;
                if (zslFrames != zslWanted) Log.i("SCAM_CAPTURE", "motion: " + sScamPending.get() + " shots wait for their merge, "
                        + zslFrames + " ring frames for this one (of " + zslWanted + ")");
                mPendingZslNormalFrames = drainZslNormalFrames(zslFrames,stockPlan,scamZslRequested,scamHybridShot);
                if (!mPendingZslNormalFrames.isEmpty()) {
                    denoiseFrameCount = mPendingZslNormalFrames.size();
                    long[] zslTimestamps = new long[denoiseFrameCount];
                    for (int i = 0; i < denoiseFrameCount; i++) {
                        zslTimestamps[i] = mPendingZslNormalFrames.get(i).timestamp;
                    }
                    CaptureResult gyroBase=mNativeZslBase!=null?mNativeZslBase:mPreviewCaptureResult;
                    Long previewExposure = gyroBase != null ? gyroBase.get(CaptureResult.SENSOR_EXPOSURE_TIME) : null;
                    ScameraApp.getGyro().buildZslBurstShakiness(zslTimestamps,
                            previewExposure != null ? previewExposure : 0L, BurstShakiness, isGyroClockComparable());
                } else {
                    // Camera was just opened, the ring has not filled yet or holds no usable frame: N is taken after the
                    // shutter this once (tolerant for both routes, P27).
                    Log.w(TAG, (scamHybridShot ? "Hybrid" : "SCAM HDR") + " ZSL ring empty; N frames taken after the shutter");
                }
                if (scamZslRequested) {
                    // Keep the viewfinder and AE running: requests submitted with
                    // capture() go ahead of the repeating preview, and every RAW is
                    // routed by its capture-start timestamp from here on.
                    mLiveRawRouter.clear();
                    mScamRouted = true;
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
            Log.i(TAG, "SCAM controls: normal=" + denoiseFrameCount + " short=" + shortFrameCount
                    + " long=" + longFrameCount + " route=" + PreferenceKeys.mergeRoute());
            // Keep the total inside the proven RAW buffer ceiling.
            int frameCount = denoiseFrameCount > 0
                    ? Math.min(37, denoiseFrameCount + shortFrameCount + longFrameCount)
                    : denoiseFrameCount;
            longFrameCount = denoiseFrameCount > 0
                    ? Math.max(0, frameCount - denoiseFrameCount - shortFrameCount) : 0;
            //if (frameCount == 1) frameCount++;
            cameraEventsListener.onFrameCountSet(frameCount);
            Log.d(TAG, "HDRFact1:" + paramController.isManualMode() + " HDRFact2:" + ScameraApp.getSettings().alignAlgorithm);
            //IsoExpoSelector.HDR = (!manualParamModel.isManualMode()) && (ScameraApp.getSettings().alignAlgorithm == 0);
            //IsoExpoSelector.HDR = (ScameraApp.getSettings().alignAlgorithm == 1);
            Log.d(TAG, "HDR:" + IsoExpoSelector.HDR);
            Object mode = mPreviewRequestBuilder.get(CONTROL_AF_MODE);
            if(mode != null && (int) mode != CaptureRequest.CONTROL_AF_MODE_AUTO || PreferenceKeys.getAfMode() == CaptureRequest.CONTROL_AF_MODE_AUTO && !ScameraApp.getSettings().selectedMode.equals(CameraMode.RAWVIDEO)) {
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



            /*mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            if (focus != 0.0)
                mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
            rebuildPreviewBuilder();*/

            paramController.applyWhiteBalance(captureBuilder);
            IsoExpoSelector.useTripod = ScameraApp.getGyro().getTripod();
            if(scamHybridShot) {
                // SCAM Hybrid: N from the ring, then the ultrashort (Bento) and the bracketed frames (Shasta) of the plan.
                // P27: one ring frame is enough; with an empty ring N is taken after the shutter (tolerant), without any
                // known N exposure the camera's AE exposes the frames. Planning never throws.
                IsoExpoSelector.fullpairs.clear();
                HybridPlan plan;
                // P98: while the HAL reports motion, a bracketed frame's shutter stays within its motion-safe exposure
                HybridPlan.setMotionCap(manualPsl?0:HalMotion.motionCapNs(mNativeZslBase!=null?mNativeZslBase:mPreviewCaptureResult));
                try {
                if(hybridZsl) {
                    // N = the newest ring frame (the base of the selection): its own exposure, not the cutoff plan's.
                    CaptureResult base=mNativeZslBase;
                    Long baseNs=base==null?null:base.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer baseIso=base==null?null:base.get(CaptureResult.SENSOR_SENSITIVITY);
                    long nNs=baseNs!=null&&baseNs>0?baseNs:stockPlan!=null?stockPlan.shutter(0):0;
                    int nIso=baseIso!=null&&baseIso>0?baseIso:stockPlan!=null?stockPlan.iso(0):0;
                    try {
                        // P113: «Движение» shoots from the ring alone, as the stock snapshot mode (no frames after the shutter)
                        plan=com.particlesdevs.photoncamera.settings.MotionMode.on() ? HybridPlan.zslOnly(nNs,nIso)
                                : HybridPlan.build(nNs,nIso,mLastZslClipFraction,mCameraCharacteristics,mExposureLimits);
                    } catch(RuntimeException noPlan) {
                        Log.w("SCAM_CAPTURE","hybrid plan unavailable ("+noPlan.getMessage()+"): one more N frame after the shutter");
                        plan=HybridPlan.single(nNs,nIso);
                    }
                } else {
                    plan=manualPsl && stockPlan!=null
                            ? HybridPlan.manualLong(stockPlan.shutter(0),stockPlan.iso(0),mLastZslClipFraction,mCameraCharacteristics,mExposureLimits)
                            : stockPlan!=null
                            ? HybridPlan.buildNormalBack(stockPlan.shutter(0),stockPlan.iso(0),mLastZslClipFraction,mCameraCharacteristics,4,mExposureLimits,
                                    paramController.motionCeilingNs())
                            : HybridPlan.autoExposure(4);
                }
                } finally { HybridPlan.setMotionCap(0); }
                // P219: a full-resolution session (Pixel / Snapdragon MAXIMUM_RESOLUTION) asks for one long frame; its colour block
                // is the stream's (0 here: «Наш ремозаик» off keeps every frame for the binned merge)
                if(mFullResSession) plan=plan.withLongFrames(HybridPlan.fullResLongFrames(true,0,PreferenceKeys.isHybridMosaicRemosaic()));
                hybridPlanHolder[0]=plan;
                Log.i("SCAM_CAPTURE",plan.description);
                for(ImageFrame normal:mPendingZslNormalFrames) {
                    IsoExpoSelector.ExpoPair pair=new IsoExpoSelector.ExpoPair(normal.measuredExposure,normal.measuredExposure,normal.measuredExposure,
                            normal.measuredIso,normal.measuredIso,normal.measuredIso,normal.measuredIso);
                    IsoExpoSelector.fullpairs.add(pair);
                }
                // Focus and stabilisation as the preview that delivered the N frames (ArkCam / SCAM 9.6: continuous_picture, no
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
                // P84c: AE_TARGET_FPS_RANGE is a session parameter on the X200 Ultra (availableSessionKeys): a series request with
                // the still template's range and the viewfinder's repeating request with its own made the camera service
                // reconfigure between them, waiting for idle; behind three 4 s frames that wait passed its 12.5 s limit
                // ("reconfigureCamera: Can't idle device in 12.5 seconds") and the camera died (error 4). The series takes the
                // viewfinder's range (manual AE-off frames are timed by their exposure, not by this range).
                android.util.Range<Integer> fps=mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
                if(fps!=null) captureBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fps);
                captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                long[] times=new long[plan.requests.size()];
                for(int i=0;i<plan.requests.size();i++) {
                    HybridPlan.Request r=plan.requests.get(i);
                    plan.apply(captureBuilder,i);
                    // P98: a frame past the declared frame-duration limit (Xiaomi 17 Ultra: 1 s) lifts the Qualcomm FPS limit
                    if(!r.autoExposure&&LongExposureKeys.apply(captureBuilder,r.shutterNs,
                            mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)))
                        Log.i("SCAM_CAPTURE","long frame "+r.shutterNs/1e6+" ms: Qualcomm FPS limit lifted (P98)");
                    captureBuilder.setTag(new ImageFrame.ScamCaptureTag(mShutterGeneration,captures.size(),r.role));
                    CaptureRequest request=captureBuilder.build();captures.add(request);mCaptureRequest=request;
                    times[i]=r.shutterNs;
                    IsoExpoSelector.ExpoPair pair=new IsoExpoSelector.ExpoPair(r.shutterNs,r.shutterNs,r.shutterNs,r.iso,r.iso,r.iso,r.iso);
                    pair.isLongFrame=r.role==ImageFrame.CaptureRole.LONG;
                    pair.isHighlightFrame=r.role==ImageFrame.CaptureRole.SHORT||r.role==ImageFrame.CaptureRole.EXTRA_SHORT;
                    IsoExpoSelector.fullpairs.add(pair);
                }
                // W1.9: the sensor re-registration (~39 ms) runs after the submit (Gyro.startBurstRegistration).
                ScameraApp.getGyro().PrepareGyroBurst(times,BurstShakiness,true);
            } else {
                IsoExpoSelector.fullpairs.clear();
                // L from the ZSL N frames: after the press only S and ES are exposed
                // (the 125 ms L frame cost ~0.25 s of shutter time).
                final boolean zslLong=hybridZsl && (PreferenceKeys.isScamZslLong() || stockPlan.longBeyondSensor);
                // P27: in normal-back an L the sensor cannot expose is built from the N taken after the shutter (six requests).
                final boolean backLong=!hybridZsl && stockPlan.longBeyondSensor;
                if(zslLong) {
                    final float ratio=(float)Math.max(1,Math.min(64,stockPlan.longRatio()));
                    for(ImageFrame normal:mPendingZslNormalFrames)normal.syntheticLongRatio=ratio;
                    Log.i("SCAM_CAPTURE","L from "+mPendingZslNormalFrames.size()+" ZSL N frames, ratio="+ratio);
                }
                normalBackLongRatio[0]=backLong?(float)Math.max(1,Math.min(64,stockPlan.longRatio())):0f;
                if(backLong)Log.i("SCAM_CAPTURE","normal-back: L beyond the sensor, built from the N frames, ratio="+normalBackLongRatio[0]);
                long[] times=new long[hybridZsl?(zslLong?2:3):backLong?6:7];int timeIndex=0;
                for(int i=0;i<7;i++) {
                    long ns;int iso;
                    if(i==4 && (zslLong||backLong))continue;
                    if(i<4 && hybridZsl) {
                        ImageFrame normal=mPendingZslNormalFrames.get(i);
                        stockPlan.verifyZslNormal(normal.getMatchedCaptureMetadata(),scamZslShutterTimestamp);
                        ns=normal.measuredExposure;iso=normal.measuredIso;
                    } else {
                        stockPlan.apply(captureBuilder,i,mCameraCharacteristics);
                        captureBuilder.setTag(new ImageFrame.ScamCaptureTag(mShutterGeneration,captures.size(),stockPlan.role(i)));
                        CaptureRequest request=captureBuilder.build();captures.add(request);mCaptureRequest=request;
                        ns=stockPlan.shutter(i);iso=stockPlan.iso(i);times[timeIndex++]=ns;
                    }
                    IsoExpoSelector.ExpoPair pair=new IsoExpoSelector.ExpoPair(ns,ns,ns,iso,iso,iso,iso);
                    pair.isLongFrame=i==4;pair.isHighlightFrame=i>=5;IsoExpoSelector.fullpairs.add(pair);
                }
                ScameraApp.getGyro().PrepareGyroBurst(times,BurstShakiness,true);
            }

            //img
            Log.d(TAG, "FrameCount:" + frameCount);
            mImageSaver = new ImageSaver(cameraEventsListener);
            mImageSaver.iszAspect4x3 = mShutterIszAspect4x3;
            mImageSaver.manualWbAtPress = mShutterManualWb;
            final ScamCaptureSequence scamSequence = hybridPlanHolder[0]!=null
                        ? (hybridZsl ? ScamCaptureSequence.hybridZsl(captures,mPendingZslNormalFrames,scamZslShutterTimestamp)
                                     : ScamCaptureSequence.hybridFuture(captures,scamZslShutterTimestamp))
                        : !hybridZsl
                        ? ScamCaptureSequence.stockNormalBack(captures,scamZslShutterTimestamp)
                        : ScamCaptureSequence.stockZsl(captures,mPendingZslNormalFrames,scamZslShutterTimestamp);
            // Buffered RAWs are already present; only submitted requests consume reader slots.
            mImageSaver.setFrameCount(scamSequence != null ? captures.size() : frameCount);
            // P30: the Hybrid's RAWs (ring and post-shutter) are copied straight into one memfd the worker maps (ShotArena);
            // without it (no memfd, binning, packed RAW) they are copied into native buffers as before.
            com.particlesdevs.photoncamera.util.ShotArena arenaForShot = null;
            if (scamHybridShot && !ScameraApp.getSettings().binning) {
                // P91: in free slots of the ring's memfd (ring frames of a plain RAW16 stream are views of their own slots and
                // take none); also for a shot without ring frames (PSL), its series then reaches the worker without the copy
                int frameBytes = 0, copies = 0;
                for (ImageFrame f : mPendingZslNormalFrames) {
                    frameBytes = Math.max(frameBytes, f.pendingBytes());
                    if (!f.viewable()) copies++;
                }
                if (frameBytes == 0 && mImageReaderRaw != null) frameBytes = mImageReaderRaw.getWidth() * mImageReaderRaw.getHeight() * 2;
                if (frameBytes > 0) arenaForShot = shotArena(copies + captures.size(), frameBytes);
                if (arenaForShot != null) {
                    for (ImageFrame f : mPendingZslNormalFrames) f.arena = arenaForShot;
                    mImageSaver.shotArena = arenaForShot;
                    mShotArena = arenaForShot;
                    Log.i("SCAM_CAPTURE", "RAWs of the shot into " + arenaForShot);
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

            final Surface scamRawSurface = scamSequence != null ? mImageReaderRaw.getSurface() : null;
            final long[] baseFrameNumber = {0};
            final int[] maxFrameCount = {hybridZsl ? captures.size() : frameCount};
            final int zslNormalCount=hybridZsl ? mPendingZslNormalFrames.size() : 0;
            final boolean scamNormalBack=scamSequence!=null && !hybridZsl;
            // P27: one completion per shot (sequence completed, aborted, rescued after a camera close or a stall); callbacks of a
            // completed shot are ignored, the shot's own saver is used even if the field was replaced.
            final java.util.concurrent.atomic.AtomicBoolean shotDone=new java.util.concurrent.atomic.AtomicBoolean();
            // P27 (M6): request indices the HAL failed or lost the RAW of, and whether the queue was flushed (FlushLossStats).
            final java.util.Set<Integer> halLost=java.util.concurrent.ConcurrentHashMap.newKeySet();
            final java.util.concurrent.atomic.AtomicBoolean shotFlushed=new java.util.concurrent.atomic.AtomicBoolean();
            final FlushLossStats flushStats=FlushLossStats.of(String.valueOf(physicalID));
            final ImageSaver shotSaver=mImageSaver;
            final boolean shotHybridRoute=scamHybridShot;
            // P35: the stream key of this shot (its stored colour block skips the detector in the processing).
            final String shotMosaicKey=com.particlesdevs.photoncamera.processing.MosaicStream.key();
            // P214i: by the X300 Ultra's list; 0 once the module's frames proved a plain Bayer (the stored 1 merges then)
            final int shotDeclaredBlock=VivoQcomIsz.declaredBlockOf(com.particlesdevs.photoncamera.settings.ModuleRegistry.active());
            final int nativeBaseIndex=denoiseFrameCount/2;
            final TotalCaptureResult[] nativeBaseResult={hybridZsl?mNativeZslBase:null};
            // W1.9: the vendor-key scan of every bracket result (10-15 ms on the camera callback thread) only with diagnostics.
            final boolean vendorScan=scamCapture&&PreferenceKeys.isScamDiagnosticsEnabled();

            // ZSL SCAM HDR: the N frames are already buffered and only the short tail is exposed, so the UI
            // treats the press as an instant shot (no capture ring, no locked controls, provisional thumbnail).
            cameraEventsListener.onCaptureStillPictureStarted(hybridZsl ? "ScamZslCaptureStarted" : "CaptureStarted!");
            mMeasuredFrameCnt = 0;

            cameraEventsListener.onBurstPrepared(null);
            this.CaptureCallback = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                             @NonNull CaptureRequest request,
                                             long timestamp,
                                             long frameNumber) {
                    if (shotDone.get()) return;
                    Long startNs = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME);
                    Integer startIso = request.get(CaptureRequest.SENSOR_SENSITIVITY);
                    if (startNs != null && startIso != null) // P90: its viewfinder frame's brightness
                        com.particlesdevs.photoncamera.ui.camera.views.viewfinder.ViewfinderGain.record(timestamp, startIso * (startNs / 1e9));
                    if (mNativeRawPslCapture || mScamRouted || (mLiveRawSession && !isZslMode())) mLiveRawRouter.request(timestamp, true);
                    if (mScamRouted) mScamTailTimestamp = Math.max(mScamTailTimestamp, timestamp);
                    mPreviewGap.seriesStart(timestamp);
                    if (sTimelineSubmitNs > 0) Log.i("SCAM_TIMELINE", "bracket start frame=" + frameNumber + " dtMs=" + (timestamp - sTimelineSubmitNs) / 1_000_000
                            + " nowMs=" + (android.os.SystemClock.elapsedRealtimeNanos() - sTimelineSubmitNs) / 1_000_000);

                    if (baseFrameNumber[0] == 0) {
                        baseFrameNumber[0] = frameNumber;
                        com.particlesdevs.photoncamera.processing.ShotTimeline.capture("first_start");
                        if (maxFrameCount[0] != -1) ScameraApp.getGyro().CaptureGyroBurst();
                        Log.v("BurstCounter", "CaptureStarted with FirstFrameNumber:" + frameNumber);
                    } else {
                        Log.v("BurstCounter", "CaptureStarted:" + frameNumber);
                    }
                    cameraEventsListener.onFrameCaptureStarted(null);
                    //if (maxFrameCount[0] != -1) ScameraApp.getGyro().CaptureGyroBurst();
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
                    if(scamSequence != null) {
                        // P27: a frame never costs the shot for its exposure. The Hybrid uses it in the role its measured
                        // exposure gives (HybridPlan.classify); SCAM HDR keeps it with its measured exposure (ScamBurst
                        // slots by measurement, else the Hybrid merges the burst).
                        if(hybridPlanHolder[0]!=null) {
                            ImageFrame.CaptureRole actual=hybridPlanHolder[0].classify(request,result);
                            if(actual==null)scamSequence.lost(request,"exposure outside every hybrid role");
                            else scamSequence.reclassify(request,actual);
                        } else if(stockPlan!=null)try{stockPlan.verify(request,result);}
                        catch(RuntimeException mismatch){Log.w("SCAM_CAPTURE",mismatch.getMessage()+"; kept with its measured exposure");}
                        scamSequence.completed(request, result);
                    }
                    if(scamCapture) ScamCaptureLog.result(request,result,scamZslShutterTimestamp,vendorScan);
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
                    if (maxFrameCount[0] != -1) ScameraApp.getGyro().CaptureGyroBurst();
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull android.hardware.camera2.CaptureFailure failure) {
                    if (shotDone.get()) return;
                    Log.w("SCAM_CAPTURE", "capture failed: " + request.getTag() + " reason=" + failure.getReason()
                            + " imageCaptured=" + failure.wasImageCaptured() + " frame=" + failure.getFrameNumber());
                    if (scamSequence != null) scamSequence.lost(request, "HAL capture failure=" + failure.getReason());
                    if (request.getTag() instanceof ImageFrame.ScamCaptureTag) halLost.add(((ImageFrame.ScamCaptureTag) request.getTag()).index);
                }

                @Override
                public void onCaptureBufferLost(@NonNull CameraCaptureSession session,
                                                @NonNull CaptureRequest request,
                                                @NonNull Surface target, long frameNumber) {
                    if (shotDone.get()) return;
                    Log.w("SCAM_CAPTURE", "buffer lost: " + request.getTag() + " frame=" + frameNumber + " raw=" + (target == scamRawSurface));
                    if (scamSequence != null && target == scamRawSurface)
                        scamSequence.lost(request, "RAW buffer lost frame=" + frameNumber);
                    if (target == scamRawSurface && request.getTag() instanceof ImageFrame.ScamCaptureTag)
                        halLost.add(((ImageFrame.ScamCaptureTag) request.getTag()).index);
                }

                @Override
                public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session, int sequenceId) {
                    if (shotDone.get()) return;
                    if (scamSequence != null) {
                        // P27: the frames that arrived (and the buffered N) still make the photo.
                        Log.w(TAG, "SHUTTER sequence aborted camera=" + physicalID + " sequence=" + sequenceId + ": merging the frames that arrived");
                        scamSequence.abandonOutstanding("sequence aborted");
                        onCaptureSequenceCompleted(session, sequenceId, -1);
                        return;
                    }
                    if (session != mCaptureSession) return;
                    if (!shotDone.compareAndSet(false, true)) return;
                    mNativeRawPslCapture=false;mZslCapturing=false;mShotInProgress=false;mScamRouted=false;
                    mScamQueuedShots=0;mLiveRawRouter.clear();burst=false;
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
                    if (scamSequence != null && scamSequence.futureCount > 0)
                        Log.i("SCAM_CAPTURE", flushStats.record(shotFlushed.get(), scamSequence.futureCount, halLost));
                    final int finalFrameCount = scamSequence != null ? scamSequence.futureCount
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
                    //activity.runOnUiThread(() -> UpdateCameraCharacteristics(ScameraApp.getSettings().mCameraID));
                    if (ScameraApp.getSettings().selectedMode != CameraMode.UNLIMITED && ScameraApp.getSettings().selectedMode != CameraMode.RAWVIDEO) {
                        //processExecutor.submit(() -> mImageSaver.runRaw(mCameraCharacteristics, mCaptureResult, new ArrayList<>(BurstShakiness), cameraRotation));
                        /*taskResults.removeIf(Future::isDone); //remove already completed results
                        Future<?> result =processExecutor.submit(() -> {
                            while (ScameraApp.getGyro().capturingNumber < finalFrameCount){
                                try {
                                    Thread.sleep(1);
                                } catch (InterruptedException ignored) {
                                }
                            }
                            if (maxFrameCount[0] != -1) ScameraApp.getGyro().CompleteGyroBurst();
                            mImageSaver.runRaw(mCameraCharacteristics, mCaptureResult, new ArrayList<>(BurstShakiness), cameraRotation);
                        });
                        //Future<?> result = processExecutor.submit(() -> mImageSaver.runRaw(mCameraCharacteristics, mCaptureResult, new ArrayList<>(BurstShakiness), cameraRotation));
                        taskResults.add(result);*/
                        processExecutor.execute(() -> {
                            int cnt = 0;
                            //int captureNumber = ScameraApp.getGyro().capturingNumber;
                            while (ScameraApp.getGyro().capturingNumber < finalFrameCount - (scamSequence != null ? scamSequence.droppedCount() : 0)
                                    || shotSaver.bufferSize() < zslNormalCount + finalFrameCount - (scamSequence != null ? scamSequence.droppedCount() : 0)){
                                if(cnt > 1000) {
                                    Log.d(TAG, "GyroBurstTimeout");
                                    break;
                                }
                                try {
                                    Thread.sleep(1);
                                } catch (InterruptedException ignored) {
                                }
                                //if(captureNumber - ScameraApp.getGyro().capturingNumber != 0)
                                //    cnt = 0;
                                //else
                                    cnt++;
                            }
                            ScameraApp.getGyro().CompleteSequence();
                            awaitZslCopy();
                            // P27: a ZSL frame whose copy did not finish is copied now; one that cannot be copied is dropped.
                            for (ImageFrame zsl : new ArrayList<>(mPendingZslNormalFrames)) {
                                try { zsl.materialize(); } catch (RuntimeException e) { Log.w("SCAM_CAPTURE", "ZSL frame " + zsl.timestamp + " not copied: " + e); }
                            }
                            final boolean routedScam = mScamRouted;
                            // The camera may be closed by now (a shot rescued from onPause / onDestroy): no handler, no preview.
                            final android.os.Handler unlockHandler = mBackgroundHandler;
                            if (!routedScam && unlockHandler != null) unlockHandler.post(() -> {
                                unlockFocus();
                            });
                            try{
                            if (scamSequence != null) {
                                java.util.List<ImageFrame> unmatched = scamSequence.bindAndValidate(shotSaver.snapshotFrames());
                                if (!unmatched.isEmpty()) shotSaver.removeFrames(unmatched);
                                String summary = scamSequence.droppedSummary();
                                if (!summary.isEmpty())
                                    Log.w("SCAM_CAPTURE", (shotHybridRoute ? "hybrid" : "SCAM HDR") + ": " + scamSequence.droppedCount() + " of "
                                            + scamSequence.futureCount + " post-shutter frames dropped, merging the rest: " + summary);
                                com.particlesdevs.photoncamera.processing.ShotTimeline.capture("complete");
                                Log.i("SCAM_CAPTURE", "complete matched RAWs=" + shotSaver.bufferSize()
                                        + " futureRequests=" + scamSequence.futureCount + " bound=" + scamSequence.boundFutureCount()
                                        + " stockAePlan=" + (stockPlan!=null));
                                // Normal-back (no buffered N): the base metadata is the first N frame that arrived, else any frame.
                                if (scamNormalBack) {
                                    CaptureResult first = scamSequence.firstBoundResult(ImageFrame.CaptureRole.NORMAL);
                                    if (first == null) first = scamSequence.firstBoundResult(null);
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
                                boolean useZslBase = scamZslRequested && (hybridZsl || scamNormalBack);
                                CaptureResult baseMetadata = useZslBase ? nativeBaseResult[0] : mCaptureResult;
                                if (baseMetadata == null) {
                                    // P27: never without base metadata while a frame exists: the newest frame's own result.
                                    for (ImageFrame frame : shotSaver.snapshotFrames()) {
                                        CaptureResult own = frame.getMatchedCaptureMetadata();
                                        Long ts = own == null ? null : own.get(CaptureResult.SENSOR_TIMESTAMP);
                                        Long best = baseMetadata == null ? null : baseMetadata.get(CaptureResult.SENSOR_TIMESTAMP);
                                        if (own != null && (best == null || (ts != null && ts > best))) baseMetadata = own;
                                    }
                                    Log.w("SCAM_CAPTURE", "base metadata from the newest frame of the burst");
                                }
                                if (baseMetadata == null) throw new IllegalStateException(Lang.t("RAW: отсутствуют метаданные основного кадра", "RAW: base frame metadata missing"));
                                if (scamSequence != null && shotSaver.bufferSize() != scamSequence.presentCount())
                                    Log.w("SCAM_CAPTURE", "RAWs in the saver " + shotSaver.bufferSize() + ", in the series " + scamSequence.presentCount());
                                final ImageSaver saver = shotSaver;
                                final CameraCharacteristics shotCharacteristics = mCameraCharacteristics;
                                final CaptureResult shotResult = baseMetadata;
                                final CaptureRequest shotRequest = baseMetadata.getRequest() != null ? baseMetadata.getRequest() : mCaptureRequest;
                                final ArrayList<GyroBurst> shotShakiness = new ArrayList<>(BurstShakiness);
                                final HashMap<Long, Double> shotExposures = mExposures;
                                if (scamSequence != null) {
                                    saver.detachForQueue(shotHybridRoute);
                                    sScamPending.incrementAndGet();
                                    Log.i("SCAM_CAPTURE", "queued for processing pending=" + sScamPending.get());
                                    com.particlesdevs.photoncamera.processing.ShotTimeline.capture("queued");
                                    final com.particlesdevs.photoncamera.processing.ShotTimeline shotTimeline =
                                            com.particlesdevs.photoncamera.processing.ShotTimeline.detach();
                                    SCAM_PROCESSING.execute(() -> {
                                        com.particlesdevs.photoncamera.processing.ShotTimeline.attach(shotTimeline);
                                        com.particlesdevs.photoncamera.processing.ShotTimeline.mark("proc");
                                        try {
                                            com.particlesdevs.photoncamera.processing.MosaicBlockStore.setShotKey(shotMosaicKey, shotDeclaredBlock);
                                            saver.runRaw(shotCharacteristics, shotResult, shotRequest,
                                                    shotShakiness, cameraRotation, shotExposures);
                                        } catch (Exception e) {
                                            Log.e(TAG, "runRaw:" + Log.getStackTraceString(e));
                                            cameraEventsListener.onProcessingError(e.getLocalizedMessage());
                                        } finally {
                                            sScamPending.decrementAndGet();
                                            // W1.0: the stage times of the shot in one line, after it was saved.
                                            com.particlesdevs.photoncamera.processing.ShotTimeline.print();
                                        }
                                    });
                                } else {
                                    com.particlesdevs.photoncamera.processing.MosaicBlockStore.setShotKey(shotMosaicKey, shotDeclaredBlock);
                                    saver.runRaw(shotCharacteristics, shotResult, shotRequest,
                                            shotShakiness, cameraRotation, shotExposures);
                                }
                            }
                            } catch (Exception e){
                                Log.e(TAG, "runRaw:"+Log.getStackTraceString(e));
                                cameraEventsListener.onProcessingError(e.getLocalizedMessage());
                                if (scamSequence != null) {
                                    shotSaver.discardFrames();
                                }
                            } finally {
                                // P30: no more frames for this shot's arena (it goes once the processing frees them)
                                if (shotArena != null) { shotArena.release(); if (mShotArena == shotArena) mShotArena = null; }
                                mScamRouted = false;
                                mNativeRawPslCapture=false;mZslCapturing=false;mLiveRawRouter.clear();
                                if (hybridZslRequested) {
                                    mHybridZslCapture = false;
                                    mZslCapturing = false;
                                    mPendingZslNormalFrames = new ArrayList<>();
                                }
                                // Preview never stopped: no AF re-trigger or 3A reset, the
                                // shutter is free as soon as the tail RAWs are in memory.
                                final android.os.Handler finishHandler = mBackgroundHandler;
                                if (routedScam && finishHandler != null) finishHandler.post(CaptureController.this::finishScamShot);
                            }
                        });
                        /*mBackgroundHandler.post(() -> {
                                    while (ScameraApp.getGyro().capturingNumber < finalFrameCount){
                                        try {
                                            Thread.sleep(1);
                                        } catch (InterruptedException ignored) {
                                        }
                                    }
                                    if (maxFrameCount[0] != -1) ScameraApp.getGyro().CompleteGyroBurst();
                                    mImageSaver.runRaw(mCameraCharacteristics, mCaptureResult, new ArrayList<>(BurstShakiness), cameraRotation);
                                });*/
                        //mBackgroundHandler.post(() -> {mImageSaver.runRaw(mCameraCharacteristics, mCaptureResult, new ArrayList<>(BurstShakiness), cameraRotation);});
                    }
                }
            };
            //mCaptureSession.setRepeatingBurst(captures, CaptureCallback, null);
            burst = true;
            Camera2ApiAutoFix.ApplyBurst();
            if (hybridZslRequested) {
                // SCAM uses capture-start timestamps to reject any late
                // preview RAWs; other hybrid paths retain their flushed routing.
                mHybridZslCapture = true;
            }
            if (!mScamRouted) mCaptureSession.stopRepeating();
            if(scamCapture) {
                Log.i("SCAM_CAPTURE","submit zslNormals="+mPendingZslNormalFrames.size()
                        +" futureRequests="+captures.size()+" hybrid="+hybridZsl
                        +" stockAePlan="+(stockPlan!=null)+" previewRunning="+mScamRouted);
                for(int i=0;i<captures.size();i++) ScamCaptureLog.request(captures.get(i),i);
            }
            if (!scamZslRequested) mCaptureSession.abortCaptures();
            // SCAM keeps the preview repeating; its in-flight requests (~12 frames on
            // this HAL) otherwise sit ahead of the bracket. Flushing them brings the
            // first bracket exposure ~0.25 s closer to the press; the preview is
            // restarted right behind the burst.
            final boolean flushWanted = mScamRouted && scamSequence != null && PreferenceKeys.isScamFastCapture()
                    && !captures.isEmpty() && (mStockObserverReady || !PreferenceKeys.useStockBracketPlanner());
            // P27 (M6): a camera whose HAL lost the first requests after the flush twice in a row is not flushed any more.
            // P54b: nor a camera under the forced stabilisation (the flush restarts the HAL pipeline; the stock app never flushes).
            final boolean forcedNoFlush = mForcedStab && ForcedStabilization.noFlush();
            final boolean flushQueue = ForcedStabilization.flush(flushWanted && !flushStats.skipFlush(), forcedNoFlush);
            if (flushWanted && !flushQueue) Log.i("SCAM_CAPTURE", "HAL queue not flushed: camera " + flushStats.camera
                    + (forcedNoFlush ? " holds the forced stabilisation (P54b)" : " lost the first requests after a flush (FlushLossStats)"));
            shotFlushed.set(flushQueue);
            mLastShotFlushed = flushQueue;
            // P44: preview frames right after the flush, ahead of the series (scam_dev "preview_lead"; one by default).
            final int leadFrames = PreviewContinuity.leadFrames(
                    PreferenceKeys.scamDevNumber("preview_lead", PreviewContinuity.DEFAULT_LEAD), flushQueue);
            mLastShotLead = leadFrames;
            sTimelineSubmitNs = android.os.SystemClock.elapsedRealtimeNanos();
            {
                final String previousGap = mPreviewGap.markShot("camera=" + physicalID + " flush=" + flushQueue + " lead=" + leadFrames
                                + " rearm=" + previewRearmMode(flushQueue, leadFrames)
                                + " aeRestore=" + (mScamRouted ? Math.round(PreferenceKeys.scamDevNumber("ae_restore", 1f)) : -1),
                        sTimelineSubmitNs, captures.size(), isGyroClockComparable());
                if (previousGap != null) Log.i(PreviewGapMeter.TAG, previousGap + " (closed early by the next shot)");
            }
            com.particlesdevs.photoncamera.processing.ShotTimeline.capture("submit");
            mStabTrace.markShot("submit requests=" + captures.size() + " flush=" + flushQueue + " previewKept=" + mScamRouted);
            if (!captures.isEmpty()) {
                mStabTrace.seriesRequest(captures.get(0), 0);
                if (captures.size() > 1) mStabTrace.seriesRequest(captures.get(captures.size() - 1), captures.size() - 1);
            }
            if (flushQueue) {
                long t0 = android.os.SystemClock.elapsedRealtime();
                // Without stopping the repeating preview first, the HAL sometimes
                // returns from flush at once without dropping anything (+0.17 s).
                flushDevice(mCaptureSession);
                Log.i("SCAM_CAPTURE", "HAL queue flushed in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
                mStabTrace.event("HAL queue flushed in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
                // P44: the flush also cleared the repeating preview; lead frames go into the request queue ahead of the
                // series (capture() requests are taken in order, the series follows them without a preview frame between).
                if (leadFrames > 0) queuePreviewLead(leadFrames);
            }
            if(captures.isEmpty() && hybridZsl) {
                startZslCopy(mPendingZslNormalFrames);
                CaptureCallback.onCaptureSequenceCompleted(mCaptureSession,0,-1);return;
            }
            // P27: from here the shot completes with what arrived whatever happens: a camera close / error, an abort, a
            // submit failure or a HAL that never reports the series (the watchdog; 65 s HAL stalls were seen).
            if (scamSequence != null) {
                final CameraCaptureSession.CaptureCallback shotCallback = CaptureCallback;
                final CameraCaptureSession shotSession = mCaptureSession;
                final Runnable rescue = () -> {
                    if (shotDone.get()) return;
                    scamSequence.abandonOutstanding("camera stopped delivering the series");
                    for (ImageFrame zsl : new ArrayList<>(mPendingZslNormalFrames)) {
                        try { zsl.materialize(); } catch (RuntimeException e) { Log.w("SCAM_CAPTURE", "ZSL frame " + zsl.timestamp + " not copied: " + e); }
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
                    Log.w("SCAM_CAPTURE", "series not completed after " + watchdogMs + " ms: aborting it, merging the frames that arrived");
                    try { if (shotSession != null) shotSession.abortCaptures(); } catch (CameraAccessException | RuntimeException ignored) {}
                    rescueInFlightShot("watchdog " + watchdogMs + " ms");
                    // abortCaptures also dropped the repeating preview, which the SCAM route keeps running (no unlockFocus
                    // after the shot): bring it back while this session is still the camera's.
                    if (isCameraResumed && mCaptureSession == shotSession) rebuildPreviewBuilder();
                }, watchdogMs);
            }
                switch (ScameraApp.getSettings().selectedMode) {
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
                        if (mScamRouted) queueScamAeRestore();
                        if (flushQueue) {
                            mCaptureSession.setRepeatingRequest(
                                    mPreviewInputRequest = mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
                            mStabTrace.event("preview repeating restarted behind the series");
                        }
                        break;
                }
                // W1.9: the gyro's burst-rate registration, now that the series is submitted (its first exposure starts
                // 109 ms or more after the flush); before, it delayed the submit by ~39 ms.
                ScameraApp.getGyro().startBurstRegistration();
                startZslCopy(mPendingZslNormalFrames);
        } catch (CameraAccessException | RuntimeException e) {
            // P27: the series failed to submit after the shot was set up (session closed, device disconnecting): the
            // buffered N frames still make the photo.
            if (mInFlightRescue != null) {
                Log.w("SCAM_CAPTURE", "series submit failed (" + e + "): merging the buffered frames");
                rescueInFlightShot("submit failed: " + e.getMessage());
                return;
            }
            mNativeRawPslCapture=false;mZslCapturing=false;mShotInProgress=false;mScamRingFrozen=false;
            mScamRouted=false;mScamQueuedShots=0;mLiveRawRouter.clear();
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
            Log.w("SCAM_CAPTURE", "device flush failed, session abort: " + e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w("SCAM_CAPTURE", "device flush unavailable, session abort: " + e);
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
            Log.i("SCAM_CAPTURE", "sensitivity range camera=" + physicalID + " " + r + " -> " + wider + " (observed ISO " + iso + ")");
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
                        Log.i("SCAM_CAPTURE", "ZSL copy " + pending.size() + " RAWs in "
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
        ScameraApp.getSettings().fpsMode = PreferenceKeys.getFpsMode();
        Range<Integer> motionFps = motionFpsRange();
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, motionFps != null ? motionFps : getSelectedFpsRange());
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
        String cameraIdStr = ScameraApp.getSettings().mCameraID;
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
        mMediaRecorder.setOrientationHint(ScameraApp.getGravity().getCameraRotation(mSensorOrientation));
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
            // P214f: one message on the camera screen's card (two English-only system toasts before)
            final boolean removed = vid.delete();
            showError(Lang.t("Запись видео не остановилась" + (removed ? "; файл удалён" : ""),
                    "The recording did not stop" + (removed ? "; the video file was removed" : "")));
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
        processExecutor.execute(() -> {
            if (mTextureView == null)
                mTextureView = new GLPreview(activity);
            if (mTextureView.isAvailable()) {
                // The GL surface survived backgrounding (no onSurfaceCreated will
                // fire on resume), so open the camera directly against the
                // existing SurfaceTexture instead of waiting for a callback.
                CameraCharacteristics chars = requestedCharacteristics();
                if (chars == null) return;
                Size optimal = getPreviewOutputSize(getSafeDisplay(), chars,
                        ScameraApp.getSettings().selectedMode);
                openCamera(optimal.getWidth(), optimal.getHeight());
            } else {
                mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
                // The availability callback is delivered through the main-thread
                // handler; it may have fired between the check above and arming
                // the listener. Re-check so the camera is never left waiting for
                // an event that already happened.
                if (mTextureView.isAvailable()) {
                    CameraCharacteristics chars = requestedCharacteristics();
                    if (chars == null) return;
                    Size optimal = getPreviewOutputSize(getSafeDisplay(), chars,
                            ScameraApp.getSettings().selectedMode);
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
     * Returns true if the optical image stabilization modes indicate OIS hardware support.
     * Android spec: LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION contains supported modes.
     * Qualcomm HALs typically return [0, 1] (OFF, ON); MediaTek/Vivo HALs often return [1] (ON only).
     * Any array containing LENS_OPTICAL_STABILIZATION_MODE_ON (1) supports OIS.
     */
    public static boolean isOisSupported(int[] modes) {
        if (modes == null) return false;
        for (int mode : modes) {
            if (mode == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                return true;
            }
        }
        return false;
    }

    public static boolean isOisSupported(CameraCharacteristics chars) {
        if (chars == null) return false;
        return isOisSupported(chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION));
    }

    /**
     * Whether the lens is optically stabilised, also where Camera2 does not say so: the OPPO Find X7 / X8 / X9 Ultra list OIS
     * OFF only on every module (SCAM TEST reports, 2026-10-11) while every module has OIS (owner; qcom,ois nodes in the X7U
     * device tree) that the HAL drives itself. Only for decisions about the frames; OIS modes are still sent only where listed.
     */
    static boolean hasOisHardware(CameraCharacteristics chars) {
        return isOisSupported(chars) || halHiddenOis(Build.MANUFACTURER, Build.MODEL);
    }

    static boolean halHiddenOis(String manufacturer, String model) {
        if (model == null) return false;
        final String m = model.toUpperCase(java.util.Locale.ROOT);
        // OnePlus 15 (SCAM TEST 2026-10-11): OIS OFF only in the characteristics, ON in the main / tele templates (0, 2, 4)
        if ("OnePlus".equalsIgnoreCase(manufacturer)) return m.equals("CPH2747");
        if (!"OPPO".equalsIgnoreCase(manufacturer)) return false;
        return m.equals("PHY110") || m.equals("PKJ110") || m.equals("CPH2841");
    }

    /**
     * Selects the best video/preview stabilization mode supported by this camera and Android version.
     * In video mode: prefers CONTROL_VIDEO_STABILIZATION_MODE_ON.
     * In photo mode: if EIS Photo is enabled, prefers CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
     * (Android 13+, API 33) to stabilize the viewfinder without affecting still capture framing.
     * On cameras lacking OIS (e.g. UltraWide/Front), falls back to CONTROL_VIDEO_STABILIZATION_MODE_ON.
     */
    public static int getPreferredVideoStabilizationMode(CameraCharacteristics chars, boolean isRecordingVideo, boolean eisPhotoOn) {
        if (!eisPhotoOn || chars == null) {
            return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
        }
        int[] vsModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (vsModes == null || vsModes.length == 0) {
            return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
        }
        boolean hasPreviewStab = false;
        boolean hasOn = false;
        for (int m : vsModes) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) {
                hasPreviewStab = true;
            }
            if (m == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                hasOn = true;
            }
        }
        if (isRecordingVideo) {
            if (hasOn) return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON;
            if (hasPreviewStab) return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION;
        } else if (vivoBrand()) {
            // Photo mode: P54d (vivo X200 FE / X300 Ultra) only. Elsewhere the photo preview keeps no stabilisation mode, as
            // before P54d (Xiaomi 13 Ultra / OPPO Find X9 Ultra: mode 2 next to the RAW stream stalled or killed the HAL).
            if (hasPreviewStab) {
                return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION;
            }
            if (!isOisSupported(chars) && hasOn) {
                return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON;
            }
        }
        return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
    }

    /** vivo / iQOO: the brands P54d's photo-mode stabilisation (PREVIEW_STABILIZATION, session parameter) was made for. */
    static boolean vivoBrand() {
        return "vivo".equalsIgnoreCase(Build.BRAND) || "iqoo".equalsIgnoreCase(Build.BRAND) || "vivo".equalsIgnoreCase(Build.MANUFACTURER);
    }

    private void applyVideoStabilization(CaptureRequest.Builder builder) {
        int vsMode = getPreferredVideoStabilizationMode(mCameraCharacteristics, mIsRecordingVideo, PreferenceKeys.isEisPhotoOn());
        if (vsMode != CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, vsMode);
        }
    }

    /** P54b: OIS can be forced on: the camera has OIS and the user did not choose «OIS off» (oisMode 2). */
    private boolean oisForceable() {
        int[] modes = mCameraCharacteristics == null ? null
                : mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        return oisRequestable(modes) && oisMode != 2;
    }

    /**
     * P95 (owner, 2026-10-11: «нужен рабочий OIS на оппо, форсировать его работу на всех смартах»): the OIS mode is sent on
     * every camera, also where Camera2 lists OFF only. The OPPO Find X8 / X9 Ultra HALs list OFF only yet their own preview and
     * still templates carry ON on the main and tele modules and the results report ON (SCAM TEST, 2026-10-11): the HAL takes
     * the key, it only hides it from the list. scam_dev "ois_force 0" goes back to the listed modes only.
     */
    private boolean oisRequestable(int[] modes) {
        if (isOisSupported(modes)) return true;
        if (!PreferenceKeys.scamDevSwitch("ois_force", true)) return false;
        String id = physicalID;
        if (id != null && sOisForcedLogged.add(id))
            Log.i("SCAM_CAPTURE", "OIS forced ON on camera " + id + " (Camera2 lists " + java.util.Arrays.toString(modes) + ")");
        return true;
    }

    private static final java.util.Set<String> sOisForcedLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Dynamically applies Optical Image Stabilization (OIS) configuration to a CaptureRequest.
     * Respects user preference (Default/Smart/Off) and physical hardware limitations.
     *
     * @param builder        the builder for which to configure OIS
     * @param isStillCapture true if configuring a still capture request, false for preview stream
     */
    private void applyOisMode(CaptureRequest.Builder builder, boolean isStillCapture) {
        int[] stabilizationModes = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (oisRequestable(stabilizationModes)) {
            int oisMode = this.oisMode;
            if (oisMode == 2) {
                // Always Off
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            } else if (oisMode == 1) {
                // Smart Auto
                boolean isTripod = ScameraApp.getGyro() != null && ScameraApp.getGyro().getTripod();
                CameraMode mode = ScameraApp.getSettings().selectedMode;
                boolean isContinuousCapture = (mode == CameraMode.UNLIMITED);

                if (isTripod || isContinuousCapture) {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                } else {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                }
            } else {
                // Default: OIS ON for both preview and still capture.
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
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
            String lens = ScameraApp.getSettings().mCameraID;
            Log.d(TAG, "focusRange(" + lens + ") : " + (focusRange == null ? "Fixed [" + maxFocal + "]" : focusRange.toString()));
            Log.d(TAG, "isoRange(" + lens + ") : " + isoRange.toString());
            Log.d(TAG, "expRange(" + lens + ") : " + expRange.toString());
            Log.d(TAG, "evCompRange(" + lens + ") : " + evRange.toString());
        }

    }
}
