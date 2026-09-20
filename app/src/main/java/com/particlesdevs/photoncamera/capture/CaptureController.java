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
import com.particlesdevs.photoncamera.processing.parameters.FrameNumberSelector;
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
            description = "Camera capture session type (0 = regular)")
    public int sessionType = 0;

    @SensorConfig(
            title = "OIS Mode",
            description = "Controls optical stabilization. Auto mode disables OIS on a tripod and in Unlimited to prevent drift",
            entries = {"On", "Auto", "Off"},
            entryValues = {"0", "1", "2"},
            defaultValue = 0
    )
    public int oisMode = 0;

    @SensorConfig(
            title = "Exposure Balance",
            description = "Shift balance between shutter speed and ISO. Photo and Night modes only",
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
            description = "Limit the maximum sensitivity allowed",
            entries = {"400", "800", "1600", "3200", "6400", "12800", "Max Analog ISO / 4", "Max Analog ISO / 2", "Max Analog ISO", "Sensor Max ISO"},
            entryValues = {"400", "800", "1600", "3200", "6400", "12800", "-4", "-3", "-2", "-1"},
            defaultValue = -1
    )
    public int exposureBalanceIsoLimit = -1;

    @SensorConfig(
            title = "Shutter Limit",
            description = "Limit the maximum exposure duration allowed",
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
    private long mShutterGeneration;
    private volatile boolean mLiveRawSession;
    private volatile boolean mNativeRawPslCapture;
    private TotalCaptureResult mNativeZslBase;
    private com.particlesdevs.photoncamera.remosaic.CalibrationSession mCalibrationSession;
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
    private List<ImageFrame> mPendingZslNormalFrames = new ArrayList<>();

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
            if (mNativeRawPslCapture || (!isZslMode() && mLiveRawSession)) {
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
                synchronized (mZslBufferLock) {
                    if (!isCameraResumed || reader != mImageReaderRaw) { img.close(); return; }
                    updateDistributionAe(img,mHexZslResults.get(img.getTimestamp()));
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
    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    public ProcessCallbacks debugCallback = new ProcessCallbacks();
    private final CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            synchronized (mPreviewStateLock) {
                if (!isCurrentPreviewSession(session)) return;
                if (mNativeRawPslCapture || (mLiveRawSession && !isZslMode())) mLiveRawRouter.request(timestamp, false);
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
            mCameraOpenCloseLock.release();
            mCameraOpening.set(false);
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            mCameraOpening.set(false);
            cameraDevice.close();
            mCameraDevice = null;
            showToast("onError() : cameraDevice = [" + cameraDevice + "], error = [" + error + "]");
            Log.d(TAG, "onError() : cameraDevice = [" + cameraDevice + "], error = [" + error + "]");
        }
    };
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
        return isCameraResumed && session != null && session == mCaptureSession && mPreviewRequestBuilder != null;
    }

    private void clearZslPreviewFrames() {
        synchronized (mZslBufferLock) {
            mHexZslResults.clear();
            while (!mZslRingBuffer.isEmpty()) mZslRingBuffer.removeFirst().close();
        }
    }

    public void closeCamera() {
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
        mLiveRawSession = false;
        mNativeRawPslCapture = false;
        if(mCalibrationSession!=null) {mCalibrationSession.cancel();mCalibrationSession=null;PreferenceKeys.finishMultiFrameCalibration();}
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
            cameraEventsListener.onOpenCamera(this.mCameraManager);
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

                this.mCameraManager.openCamera(logicalID, mStateCallback, mBackgroundHandler);
            } catch (CameraAccessException e) {
                mCameraOpening.set(false);
                Log.e(TAG, Log.getStackTraceString(e));
            } catch (InterruptedException e) {
                mCameraOpening.set(false);
                throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
            }
    });
    }
    public void UpdateCameraCharacteristics(String cameraId) {
        PhotonCamera.getSpecificSensor().selectSpecifics(Integer.parseInt(cameraId));
        CameraCharacteristics characteristics = this.mCameraCharacteristicsMap.get(cameraId);
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
        activity.runOnUiThread(() -> {
            //Preview drawing size changing
            mPreviewSize = getTextureOutputSize(getSafeDisplay(), PhotonCamera.getSettings().selectedMode);
            mTextureView.setAspectRatio(
                    mPreviewSize.getHeight(), mPreviewSize.getWidth());
            updatePreviewMirror();
            cameraEventsListener.onCharacteristicsUpdated(characteristics);
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
            mLiveRawRouter.clear();
            mLiveMetadata.clear();
            boolean photoMode = PhotonCamera.getSettings().selectedMode == CameraMode.PHOTO
                    || PhotonCamera.getSettings().selectedMode == CameraMode.NIGHT
                    || PhotonCamera.getSettings().selectedMode == CameraMode.MOTION;
            mLiveRawSession = photoMode && !isBurstSession && !mIsRecordingVideo && !mLiveRawRejected
                    && mTargetFormat == ImageFormat.RAW_SENSOR && PreferenceKeys.isLiveViewfinderRawEnabled();
            LiveRawFrame.setEnabled(false); // invalidate the previous session even when RAW remains enabled
            LiveRawFrame.setEnabled(mLiveRawSession);
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
                        try {
                            // Auto focus should be continuous for camera preview.
                            //mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            // Flash is automatically enabled when necessary.
                            resetPreviewAEMode();
                            applyAeMeteringRegions(mPreviewRequestBuilder);
                            Camera2ApiAutoFix.applyPrev(mPreviewRequestBuilder);
                            VendorTagUtils.builderSessionApply(mPreviewRequestBuilder, false, useMaximumResolutionKey, physicalID);
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
                                    getSelectedFpsRange());
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
        showToast("RAW-превью недоступно с текущими потоками. Переключаемся на обычный видоискатель.");
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
    }

    private void showToast(String msg) {
        if (activity != null) {
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * Initiate a still image capture.
     */
    public boolean takePicture() {
        synchronized (mPreviewStateLock) {
            Log.i(TAG, "SHUTTER camera=" + physicalID + " state=" + mState
                    + " processing=" + isProcessing + " zsl=" + mZslCapturing
                    + " shot=" + mShotInProgress + " burst=" + burst);
            if (mCalibrationSession != null || mZslCapturing || isProcessing || mShotInProgress || burst) {
                showToast("Предыдущий снимок ещё обрабатывается. Дождитесь завершения.");
                return false;
            }
            if (!isCameraResumed || mPreviewRequestBuilder == null || mCaptureSession == null ||
                    mCameraDevice == null || mPreviewCaptureResult == null) {
                Log.w(TAG, "takePicture(): waiting for current-session preview after resume");
                cameraEventsListener.onProcessingError("Камера ещё готовится. Повторите снимок.");
                return false;
            }
            Long previewTimestamp = mPreviewCaptureResult.get(CaptureResult.SENSOR_TIMESTAMP);
            niceZslShutterTimestamp = previewTimestamp == null ? 0 : previewTimestamp;
            mShotInProgress = true;
            final long shotGeneration = ++mShutterGeneration;
            if (isZslMode()) {
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

    private boolean isWaitingForCapture() {
        return mState == STATE_WAITING_LOCK || mState == STATE_WAITING_PRECAPTURE
                || mState == STATE_WAITING_NON_PRECAPTURE;
    }

    private void failPendingShutter(Exception error) {
        mShotInProgress = false;
        mState = STATE_PREVIEW;
        Log.e(TAG, "SHUTTER failed camera=" + physicalID, error);
        cameraEventsListener.onProcessingError("Не удалось запустить съёмку: " + error.getMessage());
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    public void unlockFocus() {
        // The burst is done with the reader by here, so the raw viewfinder may
        // take frames again.
        mShotInProgress = false;
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
            Camera2ApiAutoFix.applyEnergySaving();
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
                    if (mNativeRawPslCapture || (mLiveRawSession && !isZslMode())) mLiveRawRouter.request(timestamp, true);

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

    private boolean needsExposureBracket() {
        return !PreferenceKeys.isMultiFrameCalibration() && !PreferenceKeys.isHexQuadCaptureEnabled() && (PreferenceKeys.getShortFrameCountValue() > 0
                || PreferenceKeys.getLongFrameCountValue() > 0);
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
    private long distributionAeTime;
    private boolean distributionAeOwned;
    private CaptureRequest.Builder distributionAeBuilder;
    private int distributionAeLast;
    private void updateDistributionAe(Image image,CaptureResult result) {
        if(mPreviewRequestBuilder==null || mCameraCharacteristics==null || burst || mZslCapturing || mHybridZslCapture)return;
        int baseline=paramController==null?0:paramController.EV;
        boolean enabled=PreferenceKeys.isGcamStageEnabled("pref_gcam_scene_ae");
        if(distributionAeBuilder!=mPreviewRequestBuilder){distributionAeOwned=false;distributionAeBuilder=mPreviewRequestBuilder;}
        if(!enabled){
            Integer current=mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
            if(distributionAeOwned && current!=null && current==distributionAeLast){
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,baseline);rebuildPreviewBuilder();
            }
            distributionAeOwned=false;return;
        }
        if(result==null || image.getFormat()!=ImageFormat.RAW_SENSOR || image.getPlanes()[0].getPixelStride()!=2)return;
        if(paramController!=null && (paramController.ISO!=-1 || paramController.SHUTTER!=-1))return;
        Integer mode=mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_MODE);
        Boolean locked=mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_LOCK);
        Integer state=result.get(CaptureResult.CONTROL_AE_STATE);
        if(mode==null || mode!=CaptureRequest.CONTROL_AE_MODE_ON || Boolean.TRUE.equals(locked)
                || state==null || state!=CaptureResult.CONTROL_AE_STATE_CONVERGED)return;
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-distributionAeTime<1000)return;
        distributionAeTime=now;
        Integer white=mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        android.hardware.camera2.params.BlackLevelPattern black=mCameraCharacteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        if(white==null || black==null)return;
        double level=0;for(int y=0;y<2;y++)for(int x=0;x<2;x++)level+=black.getOffsetForIndex(x,y)*.25;
        int block=PreferenceKeys.isRawMfsrEnabled()?PreferenceKeys.getMultiFrameBlock()
                :PreferenceKeys.isRemosaicEnabled()?PreferenceKeys.getRemosaicBlockSize():1;
        double[] stats=SceneDistributionMeter.measure(image.getPlanes()[0].getBuffer(),image.getWidth(),image.getHeight(),
                image.getPlanes()[0].getRowStride(),block,level,white);
        Range<Integer> range=mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        Rational step=mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if(stats==null || range==null || step==null)return;
        Integer value=mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
        int current=value==null?baseline:value;
        int next=SceneDistributionMeter.nextSteps(stats[0],stats[1],current,baseline,step.doubleValue(),range.getLower(),range.getUpper());
        if(next!=current){
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,next);
            distributionAeOwned=true;distributionAeLast=next;rebuildPreviewBuilder();
            Log.i("SCENE_AE","p50="+stats[0]+" p99="+stats[1]+" compensation="+next);
        }
    }

    private void onMatchedLiveRaw(Image img, TotalCaptureResult result) {
        boolean retained = false;
        try {
            if (!isCameraResumed || !mLiveRawSession || mZslCapturing || mHybridZslCapture) return;
            updateDistributionAe(img,result);
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

    private void publishLiveRawFrame(Image img, TotalCaptureResult matchedResult) {
        if (!LiveRawFrame.isEnabled() || img == null) return;
        if (img.getFormat() != ImageFormat.RAW_SENSOR) return;
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
            java.util.Map<String,?> sensorValues=PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().getAll();
            float overrideBlack=(float)com.particlesdevs.photoncamera.settings.PreferenceNumber.read(sensorValues.get(sensorPrefix+"blackleveloverride"),-1);
            float overrideWhite=(float)com.particlesdevs.photoncamera.settings.PreferenceNumber.read(sensorValues.get(sensorPrefix+"whiteleveloverride"),-1);
            if(overrideWhite>0)white=overrideWhite;
            if(overrideBlack>=0&&overrideBlack<white)java.util.Arrays.fill(black,overrideBlack);
            float[] dcpMatrix = com.particlesdevs.photoncamera.processing.color.DcpProfiles.previewMatrix(gains);
            if (dcpMatrix != null) ccm = dcpMatrix;
            float[] shading=null;int sw=1,sh=1;
            android.hardware.camera2.params.LensShadingMap map=colorResult==null?null:colorResult.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
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
                    PreferenceKeys.isRawMfsrEnabled() ? PreferenceKeys.getMultiFrameBlock() : PreferenceKeys.isRemosaicEnabled() ? PreferenceKeys.getRemosaicBlockSize() : 1,
                    colorResult.get(CaptureResult.SENSOR_SENSITIVITY),
                    c == null ? null : c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY),
                    !Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_OFF).equals(colorResult.get(CaptureResult.CONTROL_AE_MODE)),shotNoise,readNoise);
            if (mTextureView != null) mTextureView.requestRender();
        } catch (Exception e) {
            Log.w(TAG, "publishLiveRawFrame: " + e.getMessage());
        }
    }

    private int multiFrameCaptureCount() {
        ImageReader reader=mImageReaderRaw;
        if(reader==null)throw new IllegalStateException("MFSR: RAW-поток не готов");
        int requested=PreferenceKeys.isMultiFrameCalibration()?4:PreferenceKeys.getMultiFrameCount();
        int count=com.particlesdevs.photoncamera.remosaic.BurstPolicy.frameCount(requested,reader.getWidth(),reader.getHeight());
        if(PreferenceKeys.isMultiFrameCalibration() && count!=4)
            throw new IllegalStateException("CAL: для четырёх RAW недостаточно памяти; уменьшите разрешение");
        Log.i("RAW_MFSR","capture requested="+requested+" actual="+count+" RAW="+reader.getWidth()+"x"+reader.getHeight());
        return count;
    }

    public static int zslRingCapacity() {
        // One global, user-adjustable capacity. Capture counts never resize the ring.
        return PreferenceKeys.getZslBufferCountValue();
    }

    private long niceZslShutterTimestamp;

    private List<ImageFrame> drainZslNormalFrames(int requestedCount) {
        List<Image> rawImages;
        java.util.Map<Long,TotalCaptureResult> selectedMetadata;
        synchronized (mZslBufferLock) {
            rawImages = new ArrayList<>(mZslRingBuffer);
            mZslRingBuffer.clear();
            selectedMetadata = new HashMap<>(mHexZslResults);
            mHexZslResults.clear();
        }
        mNativeZslBase=null;
        if (PreferenceKeys.isVivoNiceEnabled()) {
            // RAW may arrive before its TotalCaptureResult. Select from matched
            // pairs BEFORE taking the newest N images, so older complete ZSL
            // frames can fill the burst without guessing another frame's ISO.
            for (java.util.Iterator<Image> it = rawImages.iterator(); it.hasNext();) {
                Image image = it.next();
                TotalCaptureResult result = selectedMetadata.get(image.getTimestamp());
                Long exposure = result == null ? null : result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer iso = result == null ? null : result.get(CaptureResult.SENSOR_SENSITIVITY);
                if (exposure == null || exposure <= 0 || iso == null || iso <= 0
                        || (niceZslShutterTimestamp > 0 && image.getTimestamp() > niceZslShutterTimestamp)) {
                    Log.w("NICE_HDR", "Skip unpaired ZSL RAW timestamp=" + image.getTimestamp()
                            + " result=" + (result != null) + " exposureNs=" + exposure + " ISO=" + iso);
                    image.close();
                    it.remove();
                }
            }
            Log.i("NICE_HDR", "ZSL matched RAWs=" + rawImages.size() + " requested=" + requestedCount);
        }
        if(PreferenceKeys.isRawMfsrEnabled()) {
            rawImages.sort(java.util.Comparator.comparingLong(Image::getTimestamp));
            java.util.Map<Long,TotalCaptureResult> results=selectedMetadata;
            List<HexQuadZslSelector.Sample> samples=new ArrayList<>();
            for(Image image:rawImages) {
                TotalCaptureResult result=results.get(image.getTimestamp());
                Long e=result==null?null:result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer iso=result==null?null:result.get(CaptureResult.SENSOR_SENSITIVITY);
                samples.add(new HexQuadZslSelector.Sample(image.getTimestamp(),e==null?0:e,iso==null?0:iso, zslFrameQuality(image)));
            }
            int[] keep=HexQuadZslSelector.select(samples,requestedCount,PreferenceKeys.isZslQualitySelectionEnabled());
            if(keep.length!=requestedCount) {
                for(Image image:rawImages)image.close();
                throw new IllegalStateException("MFSR ZSL: дождитесь стабильной экспозиции и заполнения буфера");
            }
            List<Image> chosen=new ArrayList<>();java.util.Set<Integer> indices=new java.util.HashSet<>();
            for(int i:keep)indices.add(i);
            for(int i=0;i<rawImages.size();i++) {if(indices.contains(i))chosen.add(rawImages.get(i));else rawImages.get(i).close();}
            rawImages=chosen;mNativeZslBase=results.get(chosen.get(chosen.size()/2).getTimestamp());
            Log.i("RAW_MFSR","ZSL normal="+chosen.size()+"; only bracket donors captured after shutter");
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
            int width = img.getFormat() == ImageFormat.RAW10 ? img.getWidth()
                    : (pixelStride > 0 ? rowStride / pixelStride : img.getWidth());
            int height = img.getHeight();
            int capacity = img.getPlanes()[0].getBuffer().capacity();
            int offset = 0;
            if (PhotonCamera.getSettings().aspect169 && width > height) {
                height = width * 9 / 16;
                int offsetH = (img.getHeight() - height) / 2;
                offsetH -= offsetH % 2;
                offset = rowStride * offsetH;
                capacity = rowStride * height;
            }
            Allocator.binning = PhotonCamera.getSettings().binning;
            ImageFrame frame = new ImageFrame(img.getPlanes()[0].getBuffer(), img.getFormat(),
                    width, rowStride, offset, capacity);
            frame.timestamp = img.getTimestamp();frame.fromZsl=true;
            frame.setCaptureMetadata(selectedMetadata.get(frame.timestamp));
            frame.width = PhotonCamera.getSettings().binning ? width / 2 : width;
            frame.height = PhotonCamera.getSettings().binning ? height / 2 : height;
            img.close();
            mExposures.put(frame.timestamp, frame.measuredExposure > 0 && frame.measuredIso > 0
                    ? frame.measuredExposure / 1e9 * frame.measuredIso : exposureProduct);
            selected.add(frame);
        }
        return selected;
    }

    private boolean isGyroClockComparable() {
        Integer source = mCameraCharacteristics == null ? null
                : mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        return source != null && source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
    }

    private double zslFrameQuality(Image image) {
        if (!PreferenceKeys.isZslQualitySelectionEnabled() || image.getFormat() != ImageFormat.RAW_SENSOR)
            return Double.NaN;
        int block = PreferenceKeys.isRawMfsrEnabled() ? PreferenceKeys.getMultiFrameBlock()
                : PreferenceKeys.isRemosaicEnabled() ? PreferenceKeys.getRemosaicBlockSize() : 1;
        Image.Plane plane = image.getPlanes()[0];
        return RawFrameQuality.score(plane.getBuffer(), image.getWidth(), image.getHeight(),
                plane.getRowStride(), plane.getPixelStride(), block);
    }

    private void triggerZslCapture() {
        if (mZslCapturing || CaptureController.isProcessing) {
            Log.w(TAG, "ZSL: capture already in progress, ignoring");
            return;
        }
        mZslCapturing = true;
        burst = false;

        final boolean hex = PreferenceKeys.isHexQuadCaptureEnabled();
        final boolean multi=PreferenceKeys.isRawMfsrEnabled();
        int frameCount = multi ? multiFrameCaptureCount() : hex ? 6 : FrameNumberSelector.getFrames();
        cameraRotation = PhotonCamera.getGravity().getCameraRotation(mSensorOrientation);
        BurstShakiness = new ArrayList<>();
        mExposures = new HashMap<>();

        // Drain raw Image objects from the ring buffer (no copy yet)
        List<Image> rawImages;
        java.util.Map<Long, TotalCaptureResult> results;
        synchronized (mZslBufferLock) {
            rawImages = new ArrayList<>(mZslRingBuffer);
            mZslRingBuffer.clear();
            results = new HashMap<>(mHexZslResults);
            mHexZslResults.clear();
        }

        rawImages.sort(java.util.Comparator.comparingLong(Image::getTimestamp));
        CaptureResult selectedResult = mPreviewCaptureResult;
        CaptureRequest selectedRequest = mPreviewCaptureRequest;
        if (hex || multi || (PreferenceKeys.isZslQualitySelectionEnabled() && frameCount >= 3 && frameCount <= 40)) {
            java.util.List<HexQuadZslSelector.Sample> candidates = new ArrayList<>();
            for (Image image : rawImages) {
                TotalCaptureResult result = results.get(image.getTimestamp());
                Long exp = result == null ? null : result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer iso = result == null ? null : result.get(CaptureResult.SENSOR_SENSITIVITY);
                candidates.add(new HexQuadZslSelector.Sample(image.getTimestamp(),exp==null?0:exp,iso==null?0:iso, zslFrameQuality(image)));
            }
            int[] keep = HexQuadZslSelector.select(candidates,frameCount,PreferenceKeys.isZslQualitySelectionEnabled());
            if (keep.length != frameCount && (hex || multi)) {
                for (Image image : rawImages) image.close();
                mZslCapturing=false;mShotInProgress=false;
                Log.w(TAG,"Native RAW burst ZSL: no matched equal-exposure RAWs; no PSL substitution");
                cameraEventsListener.onProcessingError("ZSL: дождитесь " + frameCount + " кадров со стабильной экспозицией и повторите снимок");
                mBackgroundHandler.post(this::unlockFocus);
                return;
            }
            if (keep.length == frameCount) {
                List<Image> chosen = new ArrayList<>();
                for (int i=0;i<rawImages.size();++i) {
                    if (i>=keep[0] && i<=keep[keep.length-1]) chosen.add(rawImages.get(i));
                    else rawImages.get(i).close();
                }
                rawImages=chosen;
                TotalCaptureResult reference=results.get(rawImages.get(rawImages.size()/2).getTimestamp());
                selectedResult=reference;selectedRequest=reference.getRequest();
                Log.i(TAG,"RAW ZSL: "+frameCount+" timestamp-matched pre-shutter frames; quality="+PreferenceKeys.isZslQualitySelectionEnabled());
            }
        }
        final CaptureResult capturedResult=selectedResult;
        final CaptureRequest capturedRequest=selectedRequest;
        if (rawImages.isEmpty() || capturedResult == null || capturedRequest == null) {
            for (Image image : rawImages) image.close();
            mZslCapturing = false;
            mShotInProgress = false;
            cameraEventsListener.onProcessingError("Камера готовит новые кадры после возврата. Повторите снимок.");
            return;
        }
        int take = Math.min(rawImages.size(), frameCount);
        int skip = rawImages.size() - take;
        for (int i = 0; i < skip; i++) {
            rawImages.get(i).close();
        }

        // Populate exposures map from preview capture result — all ZSL frames share preview exposure
        double previewExpTime = 1.0;
        double previewISO = 100.0;
        long exposureTimeNs = 0;
        if (capturedResult != null) {
            Long expTimeNs = capturedResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer isoVal = capturedResult.get(CaptureResult.SENSOR_SENSITIVITY);
            if (expTimeNs != null) {
                exposureTimeNs = expTimeNs;
                previewExpTime = expTimeNs / 1_000_000_000.0;
            }
            if (isoVal != null) previewISO = isoVal.doubleValue();
        }
        final double exposureVal = previewExpTime * previewISO;

        // Copy selected Images to ImageFrames only now (on shutter press)
        List<ImageFrame> selected = new ArrayList<>();
        for (int i = skip; i < rawImages.size(); i++) {
            Image img = rawImages.get(i);
            int rowStride = img.getPlanes()[0].getRowStride();
            int pixelStride = img.getPlanes()[0].getPixelStride();
            int width = (img.getFormat() == ImageFormat.RAW10)
                    ? img.getWidth()
                    : (pixelStride > 0 ? rowStride / pixelStride : img.getWidth());
            int height = img.getHeight();
            int bufCapacity = img.getPlanes()[0].getBuffer().capacity();
            int offset = 0;
            if (PhotonCamera.getSettings().aspect169 && width > height) {
                height = width * 9 / 16;
                int offsetH = (img.getHeight() - height) / 2;
                offsetH -= offsetH % 2;
                offset = rowStride * offsetH;
                bufCapacity = rowStride * height;
            }
            Allocator.binning = PhotonCamera.getSettings().binning;
            ImageFrame frame = new ImageFrame(img.getPlanes()[0].getBuffer(), img.getFormat(),
                    width, rowStride, offset, bufCapacity);
            frame.timestamp = img.getTimestamp();
            frame.fromZsl = true;
            frame.setCaptureMetadata(results.get(frame.timestamp));

            frame.width = width;
            frame.height = height;
            if(PhotonCamera.getSettings().binning) {
                frame.width/= 2;
                frame.height/= 2;
            }
            img.close();
            mExposures.put(frame.timestamp, frame.measuredExposure > 0 && frame.measuredIso > 0
                    ? frame.measuredExposure / 1e9 * frame.measuredIso : exposureVal);
            selected.add(frame);
        }
        int actualCount = selected.size();

        mImageSaver = new ImageSaver(cameraEventsListener);
        mImageSaver.setFrameCount(actualCount);
        mImageSaver.setImageFormat(CaptureController.RAW_FORMAT);
        mImageSaver.implementation = ImageSaverSelector.getImageSaver(CaptureController.RAW_FORMAT, mImageSaver.implementation);
        mImageSaver.implementation.frameCount = actualCount;

        SaverImplementation.IMAGE_BUFFER.clear();
        SaverImplementation.IMAGE_BUFFER.addAll(selected);

        mCaptureResult = capturedResult;
        mCaptureRequest = capturedRequest;
        mMeasuredFrameCnt = actualCount;

        cameraEventsListener.onFrameCountSet(actualCount);
        cameraEventsListener.onCaptureStillPictureStarted("ZSLCaptureStarted!");
        cameraEventsListener.onBurstPrepared(null);
        final double frametime = ExposureIndex.time2sec(IsoExpoSelector.GenerateExpoPair(-1, this).exposure);
        for (int i = 0; i < actualCount; i++) {
            cameraEventsListener.onFrameCaptureStarted(null);
            cameraEventsListener.onFrameCaptureCompleted(
                    new TimerFrameCountViewModel.FrameCntTime(i, actualCount, frametime));
        }
        cameraEventsListener.onCaptureSequenceCompleted(null);

        long[] frameTimestamps = new long[actualCount];
        for (int i = 0; i < actualCount; i++) {
            frameTimestamps[i] = selected.get(i).timestamp;
        }
        PhotonCamera.getGyro().buildZslBurstShakiness(frameTimestamps, exposureTimeNs, BurstShakiness, isGyroClockComparable());

        // Populate fullpairs the same way setExpo() does for a normal burst
        IsoExpoSelector.fullpairs.clear();
        for (int i = 0; i < actualCount; i++) {
            if(hex || multi) {
                // Selection already verified exact measured exposure/ISO for each frame.
                IsoExpoSelector.ExpoPair pair = new IsoExpoSelector.ExpoPair(exposureTimeNs,
                        exposureTimeNs,exposureTimeNs,(int)previewISO,(int)previewISO,(int)previewISO,(int)previewISO);
                pair.isHighlightFrame=false;pair.isLongFrame=false;pair.layerMpy=1f;
                IsoExpoSelector.fullpairs.add(pair);
            } else IsoExpoSelector.fullpairs.add(IsoExpoSelector.GenerateExpoPair(i, this));
        }

        final int capturedCount = actualCount;
        processExecutor.execute(() -> {
            try {
                PhotonCamera.getGyro().CompleteSequence();
                mBackgroundHandler.post(this::unlockFocus);
                if (capturedCount == 0) {
                    Log.w(TAG, "ZSL ring buffer was empty, no frames to process");
                    cameraEventsListener.onProcessingFinished("ZSL buffer empty");
                    return;
                }
                mImageSaver.implementation.bufferLock = false;
                mImageSaver.updateFrameCount(capturedCount);
                mImageSaver.runRaw(mCameraCharacteristics, capturedResult, capturedRequest,
                        new ArrayList<>(BurstShakiness), cameraRotation, mExposures);
            } catch (Exception e) {
                Log.e(TAG, "ZSL runRaw: " + Log.getStackTraceString(e));
                cameraEventsListener.onProcessingError(e.getLocalizedMessage());
            } finally {
                mZslCapturing = false;
            }
        });
    }

    private void captureStillPicture() {
        try {
            if (null == mCameraDevice) {
                failPendingShutter(new IllegalStateException("Камера закрыта"));
                return;
            }
            SensorConfigInjector.applyToSensor(physicalID, this);
            if (isZslMode() && !needsExposureBracket() && !PreferenceKeys.isMultiFrameCalibration()) {
                triggerZslCapture();
                return;
            }
            final boolean nativePsl=PreferenceKeys.isRawMfsrEnabled();
            final boolean calibration=nativePsl && PreferenceKeys.isMultiFrameCalibration();
            if(nativePsl && !calibration && !isZslMode())
                throw new IllegalStateException("MFSR требует ZSL RAW-поток; выберите режим фото");
            if(calibration && mCalibrationSession==null) {
                android.util.Range<Integer> ir=mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                android.util.Range<Long> er=mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                mCalibrationSession=new com.particlesdevs.photoncamera.remosaic.CalibrationSession(
                    new com.particlesdevs.photoncamera.remosaic.CalibrationPlan(ir==null?100:ir.getLower(),ir==null?3200:ir.getUpper(),
                        er==null?1_000_000L:er.getLower(),er==null?66_666_667L:er.getUpper()),PhotonCamera.getSettings().mCameraID);
                com.particlesdevs.photoncamera.remosaic.CalibrationSession.active=mCalibrationSession;
            }
            final com.particlesdevs.photoncamera.remosaic.CalibrationSession calSession=calibration?mCalibrationSession:null;
            final int calStep=calibration?calSession.completed:-1;
            final boolean hybridZslRequested = isZslMode() && needsExposureBracket();
            final boolean niceZslRequested = hybridZslRequested && PreferenceKeys.isVivoNiceEnabled();
            if (niceZslRequested) {
                mLiveRawRouter.clear();
                mNativeRawPslCapture = true;
            }
            if(nativePsl) {
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
            if(calibration)captureBuilder.setTag(calSession);
            float focus = mFocus;
            double frametime = ExposureIndex.time2sec(IsoExpoSelector.GenerateExpoPair(-1, this).exposure);
            //this.mCaptureSession.stopRepeating();
            if(isDualSession) {
                if (mTargetFormat != mPreviewTargetFormat)
                    captureBuilder.addTarget(mImageReaderRaw.getSurface());
                else
                    captureBuilder.addTarget(mImageReaderPreview.getSurface());
            } else {
                captureBuilder.addTarget(mImageReaderRaw.getSurface());
                CameraMode selectedMode = PhotonCamera.getSettings().selectedMode;
                if(frametime > 0.06 && !isDualSession || selectedMode == CameraMode.RAWVIDEO || selectedMode == CameraMode.UNLIMITED || (!IsoExpoSelector.HDR)) {
                    captureBuilder.addTarget(surface);
                }
            }
            Camera2ApiAutoFix.applyEnergySaving();
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

            int selectedFrameMode = FrameNumberSelector.getFrames();
            int denoiseFrameCount = selectedFrameMode > 0
                    ? Math.max(1, Math.min(20, PreferenceKeys.getFrameCountValue()))
                    : selectedFrameMode;
            int shortFrameCount = denoiseFrameCount > 0
                    ? Math.max(0, Math.min(8, PreferenceKeys.getShortFrameCountValue())) : 0;
            int longFrameCount = denoiseFrameCount > 0
                    ? Math.max(0, Math.min(8, PreferenceKeys.getLongFrameCountValue())) : 0;
            if (PreferenceKeys.isHexQuadCaptureEnabled() && selectedFrameMode > 0) {
                denoiseFrameCount = 6;
                shortFrameCount = 0;
                longFrameCount = 0;
                Log.i(TAG, "HP9 HexQuad: six real RAWs, equal exposure, PSL session (ZSL unavailable)");
            }
            if (PreferenceKeys.isRawMfsrEnabled() && selectedFrameMode > 0) {
                if(PreferenceKeys.isMultiFrameCalibration()) {
                    denoiseFrameCount=multiFrameCaptureCount();shortFrameCount=0;longFrameCount=0;
                } else {
                    denoiseFrameCount=com.particlesdevs.photoncamera.remosaic.BurstPolicy.bracketBaseCount(
                            PreferenceKeys.getMultiFrameCount(),shortFrameCount,longFrameCount,
                            mImageReaderRaw.getWidth(),mImageReaderRaw.getHeight());
                }
            }
            if (hybridZslRequested) {
                // Block preview RAWs first. Do not route them into ImageSaver:
                // queued preview images would consume the bracket frame slots.
                mHybridZslCapture = false;
                mZslCapturing = true;
                // This graph consumes four N inputs; avoid selecting an old
                // reference from a longer series that the graph cannot use.
                mPendingZslNormalFrames = drainZslNormalFrames(
                        PreferenceKeys.isVivoNiceEnabled() ? 4 : denoiseFrameCount);
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
                    // Camera was just opened and the ring has not filled yet:
                    // safely fall back to the complete manual burst this once.
                    Log.w(TAG, "Hybrid ZSL ring empty; falling back to manual normal frames");
                }
                try {
                    mCaptureSession.stopRepeating();
                    // NICE routes each RAW by its capture-start timestamp;
                    // flushing the HAL twice only delays the L/S tail.
                    if (!niceZslRequested) mCaptureSession.abortCaptures();
                } catch (CameraAccessException e) {
                    Log.w(TAG, "Could not fully stop ZSL preview before bracket", e);
                }
                try {
                    Image queued;
                    while ((queued = mImageReaderRaw.acquireNextImage()) != null) queued.close();
                } catch (Exception ignored) {
                }
            }
            final boolean hybridZsl = hybridZslRequested && !mPendingZslNormalFrames.isEmpty();
            Log.i(TAG, "HDR+ Ultra controls: normal=" + denoiseFrameCount
                    + " short=" + shortFrameCount + " @-"
                    + PreferenceKeys.getShortExposureEvValue() + "EV, long="
                    + longFrameCount + " @+" + PreferenceKeys.getLongExposureEvValue()
                    + "EV, highlights=" + PreferenceKeys.getHighlightSuppressionValue()
                    + ", backend=" + PreferenceKeys.getProcessingBackendValue());
            // Keep the total inside PhotonCamera's proven RAW buffer ceiling.
            int frameCount = denoiseFrameCount > 0
                    ? Math.min(PreferenceKeys.isRawMfsrEnabled()?40:37, denoiseFrameCount + shortFrameCount + longFrameCount)
                    : denoiseFrameCount;
            longFrameCount = denoiseFrameCount > 0
                    ? Math.max(0, frameCount - denoiseFrameCount - shortFrameCount) : 0;
            //if (frameCount == 1) frameCount++;
            cameraEventsListener.onFrameCountSet(calibration?40:frameCount);
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
            if (frameCount == -1) {
                for (int i = 0; i < 1; i++) {
                    if(!PhotonCamera.getSettings().selectedMode.equals(CameraMode.RAWVIDEO))
                        IsoExpoSelector.setExpo(captureBuilder, i, this);
                    else {
                        captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, mPreviewAFMode);
                        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, mPreviewAEMode);
                    }
                    captures.add(captureBuilder.build());
                }
            } else {
                long[] times = new long[hybridZsl ? shortFrameCount + longFrameCount : frameCount];
                int captureIndex = 0;
                if (!hybridZsl) {
                    for (int i = 0; i < denoiseFrameCount; i++, captureIndex++) {
                        if(calibration) {
                            if(i==0)IsoExpoSelector.fullpairs.clear();
                            int iso=calSession.plan.isos[calStep];long exposure=calSession.plan.exposures[calStep];
                            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
                            captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY,iso);
                            captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME,exposure);
                            IsoExpoSelector.lastSelectedExposure=exposure;
                            IsoExpoSelector.fullpairs.add(new IsoExpoSelector.ExpoPair(exposure,exposure,exposure,iso,iso,iso,iso));
                        } else if(PreferenceKeys.isRawMfsrEnabled() && i>0) {
                            IsoExpoSelector.fullpairs.add(new IsoExpoSelector.ExpoPair(IsoExpoSelector.fullpairs.get(0)));
                        } else IsoExpoSelector.setHdrPlusExpo(captureBuilder, i, this);
                        if(PreferenceKeys.isRawMfsrEnabled()) {
                            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
                            captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE,focus);
                        }
                        times[captureIndex] = IsoExpoSelector.lastSelectedExposure;
                        captures.add(captureBuilder.build());
                        mCaptureRequest = captureBuilder.build();
                    }
                } else {
                    IsoExpoSelector.fullpairs.clear();
                }
                if(hybridZsl && mNativeZslBase != null) {
                    IsoExpoSelector.setMeasuredBracketBase(mNativeZslBase.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                            mNativeZslBase.get(CaptureResult.SENSOR_SENSITIVITY),this);
                }
                // Bracket tail order: long first, ultra-short last.
                for (int i = 0; i < longFrameCount; i++, captureIndex++) {
                    IsoExpoSelector.setLongExpo(captureBuilder, this);
                    times[captureIndex] = IsoExpoSelector.lastSelectedExposure;
                    captures.add(captureBuilder.build());
                    mCaptureRequest = captureBuilder.build();
                }
                for (int i = 0; i < shortFrameCount; i++) {
                    if (!IsoExpoSelector.setUltraShortExpo(captureBuilder, this)) {
                        // Spread collapsed to the base frame; nothing was appended to
                        // fullpairs either, so the burst is simply one frame shorter.
                        break;
                    }
                    times[captureIndex++] = IsoExpoSelector.lastSelectedExposure;
                    captures.add(captureBuilder.build());
                    mCaptureRequest = captureBuilder.build();
                }
                if (hybridZsl) {
                    ArrayList<IsoExpoSelector.ExpoPair> bracketPairs =
                            new ArrayList<>(IsoExpoSelector.fullpairs);
                    IsoExpoSelector.fullpairs.clear();
                    for (int i = 0; i < denoiseFrameCount; i++) {
                        IsoExpoSelector.ExpoPair normal = IsoExpoSelector.GenerateExpoPair(i, this);
                        ImageFrame measured = mPendingZslNormalFrames.get(i);
                        if (measured.measuredExposure > 0 && measured.measuredIso > 0) {
                            normal.exposure=measured.measuredExposure;
                            normal.iso=measured.measuredIso;
                        }
                        normal.isHighlightFrame = false;
                        normal.isLongFrame = false;
                        IsoExpoSelector.fullpairs.add(normal);
                    }
                    IsoExpoSelector.fullpairs.addAll(bracketPairs);
                }
                // captureIndex, not times.length: a skipped ultra-short frame would
                // otherwise leave a trailing zero exposure in the gyro burst.
                PhotonCamera.getGyro().PrepareGyroBurst(
                        Arrays.copyOf(times, captureIndex), BurstShakiness);
            }

            //img
            Log.d(TAG, "FrameCount:" + frameCount);
            mImageSaver = new ImageSaver(cameraEventsListener);
            mImageSaver.setFrameCount(frameCount);
            if (hybridZsl) {
                mImageSaver.setImageFormat(CaptureController.RAW_FORMAT);
                SaverImplementation.IMAGE_BUFFER.addAll(mPendingZslNormalFrames);
                mImageSaver.implementation.frameCount = frameCount;
            }
//            final int[] burstcount = {0, 0, frameCount};
            /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mImageReaderRaw.discardFreeBuffers();
            }*/
            Log.d(TAG, "CaptureStarted!");

            final long[] baseFrameNumber = {0};
            final int[] maxFrameCount = {hybridZsl ? captures.size() : frameCount};
            final int zslNormalCount=hybridZsl ? mPendingZslNormalFrames.size() : 0;
            final int nativeBaseIndex=denoiseFrameCount/2;
            final TotalCaptureResult[] nativeBaseResult={hybridZsl?mNativeZslBase:null};

            cameraEventsListener.onCaptureStillPictureStarted("CaptureStarted!");
            mMeasuredFrameCnt = 0;

            cameraEventsListener.onBurstPrepared(null);
            this.CaptureCallback = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                             @NonNull CaptureRequest request,
                                             long timestamp,
                                             long frameNumber) {
                    if (mNativeRawPslCapture || (mLiveRawSession && !isZslMode())) mLiveRawRouter.request(timestamp, true);

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

                    int frameCount = (int) (result.getFrameNumber() - baseFrameNumber[0]);
                    if(nativePsl && !hybridZsl && frameCount==nativeBaseIndex)nativeBaseResult[0]=result;
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
                            if(calibration && frameCount>=0 && frameCount<IsoExpoSelector.fullpairs.size()) {
                                IsoExpoSelector.ExpoPair measured=IsoExpoSelector.fullpairs.get(frameCount);
                                measured.exposure=actualTime;measured.iso=iso;
                            }
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
                            new TimerFrameCountViewModel.FrameCntTime(calibration?calStep*4+frameCount:hybridZsl?mPendingZslNormalFrames.size()+frameCount:frameCount,
                                    calibration?40:hybridZsl?mPendingZslNormalFrames.size()+maxFrameCount[0]:maxFrameCount[0], frametime));

                    if (onUnlimited && !unlimitedStarted) {
                        mImageSaver.processStart(mCameraCharacteristics, result, request, cameraRotation);
                        unlimitedStarted = true;
                    }
                    //if(frameCount == 0)
                        mCaptureResult = result;
                    if (maxFrameCount[0] != -1) PhotonCamera.getGyro().CaptureGyroBurst();
                }

                @Override
                public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session, int sequenceId) {
                    if (session != mCaptureSession) return;
                    mNativeRawPslCapture=false;mZslCapturing=false;mShotInProgress=false;
                    mLiveRawRouter.clear();burst=false;
                    if(calSession!=null) {calSession.cancel();mCalibrationSession=null;PreferenceKeys.finishMultiFrameCalibration();}
                    for(ImageFrame frame:mPendingZslNormalFrames)frame.close();mPendingZslNormalFrames=new ArrayList<>();
                    mHybridZslCapture=false;
                    Log.w(TAG, "SHUTTER sequence aborted camera=" + physicalID + " sequence=" + sequenceId);
                    cameraEventsListener.onCaptureSequenceCompleted(null);
                    cameraEventsListener.onProcessingError("Серия RAW прервана камерой. Повторите снимок.");
                    unlockFocus();
                }

                @Override
                public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session,
                                                       int sequenceId,
                                                       long lastFrameNumber) {

                    int finalFrameCount = (int) (lastFrameNumber - baseFrameNumber[0]) + 1;
                    Log.v("BurstCounter", "CaptureSequenceCompleted! FrameCount:" + finalFrameCount);
                    Log.d("DefaultSaver", "CaptureSequenceCompleted! FrameCount:" + finalFrameCount);
                    Log.v("BurstCounter", "CaptureSequenceCompleted! LastFrameNumber:" + lastFrameNumber);
                    Log.d(TAG, "SequenceCompleted");
                    mMeasuredFrameCnt = finalFrameCount;
                    if(!calibration)cameraEventsListener.onCaptureSequenceCompleted(null);
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
                            while (PhotonCamera.getGyro().capturingNumber < finalFrameCount || mImageSaver.bufferSize() < zslNormalCount + finalFrameCount){
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
                            mBackgroundHandler.post(() -> {
                                if(calibration)return;
                                if (!isDualSession)
                                    unlockFocus();
                                else
                                    createCameraPreviewSession(false);
                            });
                            try{
                            if(mImageSaver.bufferSize() == 0){
                                cameraEventsListener.onProcessingError("Камера не передала RAW-кадры. Повторите снимок.");
                                return;
                            }
                            mImageSaver.updateFrameCount(mImageSaver.bufferSize());
                            if (mImageSaver.bufferSize() != 0) {
                                boolean useZslBase = niceZslRequested && hybridZsl;
                                if((nativePsl || useZslBase) && nativeBaseResult[0]==null)
                                    throw new IllegalStateException("RAW: отсутствуют метаданные основного кадра");
                                if (niceZslRequested && mImageSaver.bufferSize() < zslNormalCount + finalFrameCount)
                                    throw new IllegalStateException("NICE: камера передала неполную серию RAW");
                                mImageSaver.runRaw(mCameraCharacteristics,
                                        nativePsl || useZslBase ? nativeBaseResult[0] : mCaptureResult,
                                        nativePsl || useZslBase ? nativeBaseResult[0].getRequest() : mCaptureRequest,
                                        new ArrayList<>(BurstShakiness), cameraRotation, mExposures);
                            }
                            } catch (Exception e){
                                Log.e(TAG, "runRaw:"+Log.getStackTraceString(e));
                                cameraEventsListener.onProcessingError(e.getLocalizedMessage());
                            } finally {
                                if (nativePsl || niceZslRequested) {
                                    mNativeRawPslCapture=false;mZslCapturing=false;mLiveRawRouter.clear();
                                }
                                if (hybridZslRequested) {
                                    mHybridZslCapture = false;
                                    mZslCapturing = false;
                                    mPendingZslNormalFrames = new ArrayList<>();
                                }
                                if(calibration) mBackgroundHandler.post(() -> {
                                    if(mCalibrationSession!=calSession)return;
                                    if(calSession.completed==calStep+1 && calSession.completed<10 && mCameraDevice!=null) {
                                        mShotInProgress=true;
                                        captureStillPicture();
                                    } else {
                                        boolean complete=calSession.completed==10;
                                        calSession.cancel();mCalibrationSession=null;
                                        PreferenceKeys.finishMultiFrameCalibration();mShotInProgress=false;
                                        unlockFocus();
                                        if(complete) {
                                            cameraEventsListener.onCaptureSequenceCompleted(null);
                                            cameraEventsListener.onProcessingFinished("CAL: банк из 10 профилей сохранён");
                                            activity.runOnUiThread(() -> Toast.makeText(activity,"CAL: 10 профилей сохранены. Можно открыть объектив.",Toast.LENGTH_LONG).show());
                                        }
                                    }
                                });
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
            if (isDualSession)
                createCameraPreviewSession(true);
            else {
            if (hybridZslRequested) {
                // NICE uses capture-start timestamps to reject any late
                // preview RAWs; other hybrid paths retain their flushed routing.
                mHybridZslCapture = true;
            }
            mCaptureSession.stopRepeating();
            if (!niceZslRequested) mCaptureSession.abortCaptures();
            if(captures.isEmpty() && hybridZsl) {
                CaptureCallback.onCaptureSequenceCompleted(mCaptureSession,0,-1);return;
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
                        break;
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            mNativeRawPslCapture=false;mZslCapturing=false;mShotInProgress=false;mLiveRawRouter.clear();
            for(ImageFrame frame:mPendingZslNormalFrames)frame.close();mPendingZslNormalFrames=new ArrayList<>();
            if(mCalibrationSession!=null) {mCalibrationSession.cancel();mCalibrationSession=null;PreferenceKeys.finishMultiFrameCalibration();}
            mHybridZslCapture=false;burst=false;
            cameraEventsListener.onCaptureSequenceCompleted(null);
            cameraEventsListener.onProcessingError(e.getMessage());
            unlockFocus();
            Log.e(TAG, Log.getStackTraceString(e));
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
