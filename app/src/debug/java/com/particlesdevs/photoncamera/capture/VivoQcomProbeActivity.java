package com.particlesdevs.photoncamera.capture;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.widget.TextView;

import com.particlesdevs.photoncamera.capture.spoof.CameraPackageSpoof;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class VivoQcomProbeActivity extends Activity {
    private static final String TAG = "SCAMERA_VCF_PROBE";
    private static final AtomicLong VIF_CAPTURE_ID = new AtomicLong();
    private static final AtomicInteger PROBE_STARTS = new AtomicInteger();
    private int probeOrdinal;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private CaptureRequest previewRequest;
    private List<CaptureRequest.Key<?>> availableSessionKeys = new ArrayList<>();
    private ImageReader preview, raw, jpeg, auxiliaryRaw, previewNavigation, realtimeYuv, videoEncoder;
    private int previewFrames;
    private int encoderFrames;
    private boolean captured;
    private volatile boolean stopped;
    private AutoCloseable vivoDevice;
    private VivoSdkRawSession sdkRawSession;
    private String alphaCameraType, alphaCameraComposition;
    private volatile EngineerConfigureRace engineerConfigureRace;
    private final AtomicBoolean engineerCleanupStarted = new AtomicBoolean();
    private final AtomicBoolean engineerResourcesCloseStarted = new AtomicBoolean();
    private final AtomicBoolean engineerCallbackThreadFinished = new AtomicBoolean();
    private final AtomicBoolean engineerLateRestoreNeeded = new AtomicBoolean();
    private final AtomicBoolean engineerLateCleanupStarted = new AtomicBoolean();
    private final CountDownLatch engineerResourcesClosed = new CountDownLatch(1);
    private volatile boolean engineerCleanupFinished;
    private volatile boolean engineerOpenAttempted, engineerOnClosedObserved;
    private CountDownLatch engineerOpenFinished = new CountDownLatch(0);
    private CountDownLatch engineerDeviceClosed = new CountDownLatch(0);
    private Integer previousSdk;
    private Integer previousVcfType;
    private long previewTimestamp;
    private int ringResults, nonemptyRawFrames;
    private int serialRemaining;
    private final java.util.Set<Long> rawTimestamps = new java.util.HashSet<>();
    private final java.util.Set<Long> auxiliaryRawTimestamps = new java.util.HashSet<>();
    private final java.util.Set<String> savedRawSizes = new java.util.HashSet<>();
    private final java.util.Map<Integer, Integer> previousProviderParams = new java.util.LinkedHashMap<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        recordProbeStart();
        TextView status = new TextView(this);
        status.setText("SCAMERA Camera2 probe");
        setContentView(status);
        thread = new HandlerThread("SCAMERA_VCF_PROBE");
        thread.start();
        handler = new Handler(thread.getLooper());
        Log.i(TAG, "start uid=" + android.os.Process.myUid() + " camera=" + cameraId());
        handler.post(this::open);
        handler.postDelayed(() -> stop("timeout"), 20000);
    }

    private void recordProbeStart() {
        probeOrdinal = PROBE_STARTS.incrementAndGet();
        Log.i(TAG, "probe process pid=" + android.os.Process.myPid() + " ordinal=" + probeOrdinal);
    }

    private String cameraId() {
        String value = getIntent().getStringExtra("camera");
        return value == null ? "2" : value;
    }

    @SuppressLint("MissingPermission") private void open() {
        try {
            validateEngineerConfigureRace();
            validateCtsSdkBootstrap();
            validateAlphaRawGraph();
            validateVideoSnapshot();
            if (getIntent().getBooleanExtra("video_encoder", false)
                    && (!videoSnapshot() || getIntent().getBooleanExtra("three_stream_usage", false)))
                throw new IllegalArgumentException("video_encoder requires video_snapshot without three_stream_usage");
            if (getIntent().getBooleanExtra("vif_capture", false)) {
                if (!getIntent().getBooleanExtra("vivo_api", false))
                    throw new IllegalArgumentException("vif_capture requires vivo_api");
                if (captureTemplate() != CameraDevice.TEMPLATE_STILL_CAPTURE && !videoSnapshot())
                    throw new IllegalArgumentException("vif_capture requires STILL capture_template");
            }
            validatePreviewOnly();
            validateRawOnly();
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (engineerConfigureRaceEnabled()) {
                if (!getPackageName().equals(getOpPackageName())
                        || android.os.Build.VERSION.SDK_INT >= 31
                        && (!getPackageName().equals(getAttributionSource().getPackageName())
                        || getAttributionSource().getUid() != android.os.Process.myUid()))
                    throw new IllegalArgumentException("engineer configure race requires own package attribution");
                engineerConfigureRace = new EngineerConfigureRace();
                Log.i(TAG, "engineer configure race own package=" + getPackageName()
                        + " opPackage=" + getOpPackageName() + " uid=" + android.os.Process.myUid());
                if (stopped) {
                    cleanupEngineerConfigureRace("stopped during race initialization");
                    return;
                }
            } else CameraPackageSpoof.apply(this, manager);
            if (getIntent().getBooleanExtra("reset_sdk", false)) {
                providerValue(40, 0);
                android.os.SystemClock.sleep(100);
                Log.i(TAG, "SDK reset readback=" + providerValue(40, null));
                stop("SDK reset");
                return;
            }
            setProviderParams();
            if (getIntent().getBooleanExtra("provider_sdk_session", false)) sdkRawSession = VivoSdkRawSession.open();
            if (getIntent().getBooleanExtra("provider_sdk", false)) enableSdk();
            if (getIntent().getBooleanExtra("vivo_api", false)) openVivoApi();
            if (ctsSdkBootstrap()) {
                previousSdk = providerValue(40, null);
                previousVcfType = providerValue(33, null);
                if (previousSdk != 0)
                    throw new IllegalStateException("CTS bootstrap requires idle SDK40=0");
                providerValue(40, getIntent().getIntExtra("cts_sdk_value", -1));
                awaitSdkValue(getIntent().getIntExtra("cts_sdk_value", -1));
            }
            CameraCharacteristics c = manager.getCameraCharacteristics(cameraId());
            if (alphaRawGraph()) {
                alphaCameraType = alphaStaticString(c, "com.vivo.chi.override.CameraType");
                alphaCameraComposition = alphaStaticString(c, "com.vivo.chi.override.CameraComposition");
            }
            List<CaptureRequest.Key<?>> keys = c.getAvailableSessionKeys();
            availableSessionKeys = keys == null ? new ArrayList<>() : new ArrayList<>(keys);
            Size[] rawSizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.RAW_SENSOR);
            Log.i(TAG, "RAW sizes=" + Arrays.toString(rawSizes) + " maxResolutionMap="
                    + c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION));
            if (!previewOnly() && (rawSizes == null || rawSizes.length == 0)) { stop("RAW unavailable"); return; }
            Size size = previewOnly() ? null : alphaRawGraph()
                    ? new Size("5".equals(cameraId()) ? 4080 : 4096, 3072) : rawSizes[0];
            int width = previewOnly() ? 0 : getIntent().getIntExtra("width", size.getWidth());
            int height = previewOnly() ? 0 : getIntent().getIntExtra("height", size.getHeight());
            int maxImages = alphaRawGraph() ? 5 : getIntent().getIntExtra("max_images", 4);
            int previewWidth = getIntent().getIntExtra("preview_width", 1440);
            int previewHeight = getIntent().getIntExtra("preview_height", 1080);
            int previewFormat = getIntent().getIntExtra("preview_format",
                    previewOnly() ? ImageFormat.PRIVATE : ImageFormat.YUV_420_888);
            if (!rawOnly()) {
                preview = previewOnly() || alphaRawGraph()
                        ? ImageReader.newInstance(previewWidth, previewHeight, ImageFormat.PRIVATE,
                                alphaRawGraph() ? 2 : maxImages, 256L)
                        : getIntent().hasExtra("preview_buffer_usage")
                        ? ImageReader.newInstance(previewWidth, previewHeight, previewFormat, maxImages,
                                getIntent().getLongExtra("preview_buffer_usage", 3))
                        : ImageReader.newInstance(previewWidth, previewHeight, previewFormat, maxImages);
                preview.setOnImageAvailableListener(reader -> {
                    try (Image image = reader.acquireLatestImage()) {
                        if (image != null && ++previewFrames == 8) {
                            Log.i(TAG, "preview format=" + image.getFormat() + " delivered 8 frames");
                            if (!previewOnly() && !getIntent().getBooleanExtra("no_capture", false)
                                    && !(alphaRawGraph() && getIntent().getBooleanExtra("raw_ring", false)))
                                handler.postDelayed(this::capture, 500);
                        }
                    } catch (RuntimeException e) { stop("preview " + e); }
                }, handler);
            }
            if (!previewOnly()) {
                int rawFormat = getIntent().getIntExtra("raw_format",
                        rawVif10() ? ImageFormat.RAW10 : ImageFormat.RAW_SENSOR);
                if (rawFormat != ImageFormat.RAW_SENSOR && rawFormat != ImageFormat.RAW10)
                    throw new IllegalArgumentException("raw_format must be RAW_SENSOR(32) or RAW10(37)");
                if (rawVif10() && rawFormat != ImageFormat.RAW10)
                    throw new IllegalArgumentException("raw_vif10 requires RAW10(37)");
                Log.i(TAG, "RAW requested format=" + rawFormat + " size=" + width + "x" + height
                        + " advertised=" + Arrays.toString(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                                .getOutputSizes(rawFormat)));
                raw = rawReader(width, height, maxImages, rawFormat);
                raw.setOnImageAvailableListener(this::saveRaw, handler);
                if (alphaRawGraph()) {
                    realtimeYuv = drainedYuvReader(1440, 1080, 2, 3);
                    Log.i(TAG, "alpha_raw_graph PRIVATE=" + previewWidth + "x" + previewHeight
                            + " maxImages=2 YUV35=1440x1080 maxImages=2 RAW32=" + width + "x" + height
                            + " maxImages=5 CameraType=" + alphaCameraType
                            + " CameraComposition=" + alphaCameraComposition);
                }
                if (getIntent().getBooleanExtra("stock_photo_graph", false)) {
                    previewNavigation = drainedYuvReader(480, 360, maxImages, 3);
                    realtimeYuv = drainedYuvReader(1728, 1296, maxImages, 65536);
                }
                if (getIntent().getBooleanExtra("jpeg", false)) {
                    jpeg = ImageReader.newInstance(width, height, ImageFormat.JPEG, maxImages);
                    jpeg.setOnImageAvailableListener(reader -> {
                        try (Image image = reader.acquireNextImage()) {
                            if (image != null) Log.i(TAG, "JPEG delivered timestamp=" + image.getTimestamp()
                                    + " bytes=" + image.getPlanes()[0].getBuffer().remaining());
                        }
                    }, handler);
                }
                if (getIntent().getBooleanExtra("aux_raw", false) || rawVif10()) {
                    auxiliaryRaw = rawReader(size.getWidth(), size.getHeight(), maxImages,
                            rawVif10() ? ImageFormat.RAW10 : ImageFormat.RAW_SENSOR);
                    auxiliaryRaw.setOnImageAvailableListener(this::saveRaw, handler);
                }
            }
            if (getIntent().getBooleanExtra("video_encoder", false)) {
                if (preview == null || raw == null || jpeg == null || auxiliaryRaw != null
                        || previewNavigation != null || realtimeYuv != null)
                    throw new IllegalArgumentException("video_encoder requires preview, RAW and JPEG");
                videoEncoder = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 4, 65536L);
                videoEncoder.setOnImageAvailableListener(reader -> {
                    try (Image image = reader.acquireLatestImage()) {
                        if (image != null && ++encoderFrames == 8)
                            Log.i(TAG, "encoder PRIVATE usage=65536 delivered 8 frames");
                    } catch (RuntimeException e) { stop("encoder " + e); }
                }, handler);
            }
            setProviderApp();
            if (engineerConfigureRace != null) synchronized (engineerConfigureRace) {
                if (stopped) return;
                engineerOpenAttempted = true;
                engineerOpenFinished = new CountDownLatch(1);
                engineerDeviceClosed = new CountDownLatch(1);
            }
            manager.openCamera(cameraId(), new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice opened) {
                    if (engineerConfigureRace != null) {
                        device = opened;
                        engineerOpenFinished.countDown();
                    }
                    if (engineerConfigureRace != null && stopped) { opened.close(); return; }
                    if (ctsSdkBootstrap() && stopped) { opened.close(); return; }
                    device = opened;
                    try {
                        if (ctsSdkBootstrap()) {
                            if (getIntent().getBooleanExtra("cts_sdk_clear_at_end_configure", false)) {
                                installCtsEndConfigureGate(opened);
                            } else if (getIntent().getBooleanExtra("cts_sdk_retain_until_close", false)) {
                                Log.i(TAG, "CTS SDK retained through configure/capture; restored by resource cleanup");
                            } else {
                                providerValue(40, previousSdk);
                                awaitSdkValue(previousSdk);
                                Log.i(TAG, "CTS bootstrap SDK restored before configure; no global AppType override");
                            }
                        }
                        if (getIntent().getBooleanExtra("provider_sdk_after_open", false)) enableSdk();
                        if (getIntent().getBooleanExtra("clear_sdk_after_open", false)) {
                            providerValue(40, 0);
                            android.os.SystemClock.sleep(100);
                            Log.i(TAG, "SDK after open readback=" + providerValue(40, null));
                        }
                        configure();
                    } catch (Exception e) { stop("SDK after open " + e); }
                }
                @Override public void onDisconnected(CameraDevice opened) {
                    if (engineerConfigureRace != null) {
                        device = opened;
                        engineerOpenFinished.countDown();
                        if (stopped) { opened.close(); return; }
                        device = opened;
                    } else opened.close();
                    stop("disconnected");
                }
                @Override public void onError(CameraDevice opened, int error) {
                    Log.e(TAG, "onError " + error);
                    if (engineerConfigureRace != null) {
                        device = opened;
                        engineerOpenFinished.countDown();
                        if (stopped) { opened.close(); return; }
                        device = opened;
                        stop("camera error " + error);
                        return;
                    }
                    if (getIntent().getBooleanExtra("defer_error_close", false)) {
                        handler.postDelayed(() -> { opened.close(); stop("camera error " + error); }, 2000);
                    } else { opened.close(); stop("camera error " + error); }
                }
                @Override public void onClosed(CameraDevice closed) {
                    if (engineerConfigureRace != null) {
                        engineerOnClosedObserved = true;
                        engineerDeviceClosed.countDown();
                        Log.i(TAG, "engineer race CameraDevice.onClosed observed");
                        finishEngineerRaceCallbacksIfClosed();
                    }
                }
            }, handler);
        } catch (Exception e) {
            if (engineerConfigureRace != null) engineerOpenFinished.countDown();
            stop("open " + e);
        }
    }

    private void enableSdk() throws Exception {
        if (previousSdk == null) previousSdk = providerValue(40, null);
        providerValue(40, 1);
        android.os.SystemClock.sleep(100);
        Log.i(TAG, "provider SDK=" + providerValue(40, null));
    }

    private boolean alphaRawGraph() {
        return getIntent().getBooleanExtra("alpha_raw_graph", false);
    }

    private boolean ctsSdkBootstrap() {
        return getIntent().getBooleanExtra("cts_sdk_bootstrap", false);
    }

    private void validateCtsSdkBootstrap() {
        for (String key : new String[]{"cts_sdk_bootstrap", "cts_sdk_clear_at_end_configure", "cts_sdk_retain_until_close"})
            if (getIntent().hasExtra(key) && !(getIntent().getExtras().get(key) instanceof Boolean))
                throw new IllegalArgumentException(key + " must be boolean");
        if (!ctsSdkBootstrap()) {
            if (getIntent().hasExtra("cts_sdk_value") || getIntent().hasExtra("cts_sdk_clear_at_end_configure")
                    || getIntent().hasExtra("cts_sdk_retain_until_close"))
                throw new IllegalArgumentException("CTS SDK controls require cts_sdk_bootstrap");
            return;
        }
        if (!"vivo".equalsIgnoreCase(android.os.Build.MANUFACTURER)
                || !"PD2454".equals(android.os.Build.DEVICE) || probeOrdinal != 1
                || !"android.camera.cts".equals(getPackageName())
                || !getPackageName().equals(getOpPackageName())
                || !getPackageName().equals(getAttributionSource().getPackageName())
                || getAttributionSource().getUid() != android.os.Process.myUid())
            throw new IllegalArgumentException("CTS bootstrap requires fresh PD2454 own CTS attribution");
        if (!rawOnly() || !getIntent().getBooleanExtra("native_raw", false)
                || !getIntent().getBooleanExtra("force_sensor_request", false))
            throw new IllegalArgumentException("CTS bootstrap requires native RAW-only and continuous force key");
        List<String> allowed = Arrays.asList("cts_sdk_bootstrap", "cts_sdk_value", "cts_sdk_clear_at_end_configure",
                "cts_sdk_retain_until_close", "camera",
                "native_raw", "raw_only", "force_sensor_mode", "force_sensor_request", "width", "height", "raw_buffer_usage");
        for (String key : getIntent().getExtras().keySet())
            if (!allowed.contains(key)) throw new IllegalArgumentException("CTS bootstrap incompatible with " + key);
        if (getIntent().hasExtra("raw_buffer_usage")) {
            Object usage = getIntent().getExtras().get("raw_buffer_usage");
            if (!(usage instanceof Long) || (Long) usage != 0L
                    || !getIntent().getBooleanExtra("cts_sdk_clear_at_end_configure", false)
                    && !getIntent().getBooleanExtra("cts_sdk_retain_until_close", false))
                throw new IllegalArgumentException("CTS explicit RAW usage requires usage0 and a restoration boundary");
        }
        Object value = getIntent().getExtras().get("cts_sdk_value");
        if (value != null && (!(value instanceof Integer) || (Integer) value != -1 && (Integer) value != 1))
            throw new IllegalArgumentException("CTS bootstrap supports decoded SDK40 states -1 or 1");
        if (getIntent().getBooleanExtra("cts_sdk_retain_until_close", false)
                && (!Integer.valueOf(1).equals(value) || !getIntent().hasExtra("raw_buffer_usage")
                || getIntent().getBooleanExtra("cts_sdk_clear_at_end_configure", false)))
            throw new IllegalArgumentException("CTS retained SDK requires explicit SDK1/usage0 and close-only restoration");
        Object mode = getIntent().getExtras().get("force_sensor_mode");
        int width = getIntent().getIntExtra("width", 0), height = getIntent().getIntExtra("height", 0);
        boolean mainOrWide = Arrays.asList("3", "4").contains(cameraId());
        boolean tele = "5".equals(cameraId());
        if (!(mode instanceof Integer) || !(mainOrWide && (Integer) mode == 4 && width == 8192 && height == 6144
                || tele && Arrays.asList(1, 20).contains(mode) && width == 8160 && height == 6144
                || tele && (Integer) mode == 0 && width == 16320 && height == 12288))
            throw new IllegalArgumentException("CTS bootstrap requires a decoded native FULLRES geometry");
    }

    private void awaitSdkValue(int expected) throws Exception {
        int matching = 0;
        for (int attempt = 0; attempt < 20 && !stopped; attempt++) {
            int actual = providerValue(40, null);
            matching = actual == expected ? matching + 1 : 0;
            if (matching == 3) {
                Log.i(TAG, "CTS SDK40=" + actual + " observed three times; one-way queue drain unconfirmed");
                return;
            }
            android.os.SystemClock.sleep(25);
        }
        throw new IllegalStateException("CTS SDK40 state not observed: " + expected);
    }

    private void installCtsEndConfigureGate(CameraDevice opened) throws Exception {
        com.particlesdevs.photoncamera.api.NativeEngine.initialize();
        java.lang.reflect.Field wrapperField = com.particlesdevs.photoncamera.api.NativeEngine
                .getCameraField(opened.getClass(), "mRemoteDevice");
        if (wrapperField == null) throw new NoSuchFieldException("CameraDeviceImpl.mRemoteDevice");
        wrapperField.setAccessible(true);
        Object wrapper = wrapperField.get(opened);
        if (wrapper == null || !"android.hardware.camera2.impl.ICameraDeviceUserWrapper".equals(wrapper.getClass().getName()))
            throw new IllegalStateException("CTS endConfigure wrapper type differs");
        java.lang.reflect.Field remoteField = com.particlesdevs.photoncamera.api.NativeEngine
                .getCameraField(wrapper.getClass(), "mRemoteDevice");
        if (remoteField == null) throw new NoSuchFieldException("ICameraDeviceUserWrapper.mRemoteDevice");
        remoteField.setAccessible(true);
        Object original = remoteField.get(wrapper);
        Class<?> iface = Class.forName("android.hardware.camera2.ICameraDeviceUser");
        if (!iface.isInstance(original)) throw new IllegalStateException("CTS endConfigure remote type differs");
        AtomicBoolean pending = new AtomicBoolean(true);
        Object gate = java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                (proxy, method, arguments) -> {
                    if ("endConfigure".equals(method.getName()) && pending.compareAndSet(true, false)) {
                        if (stopped) throw new IllegalStateException("CTS configure gate stopped");
                        providerValue(40, previousSdk);
                        awaitSdkValue(previousSdk);
                        Log.i(TAG, "CTS SDK restored at endConfigure entry after stream creation; delegating unchanged");
                    }
                    try { return method.invoke(original, arguments); }
                    catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                });
        remoteField.set(wrapper, gate);
        Log.i(TAG, "CTS endConfigure gate installed on own CameraDevice; SDK40=" + providerValue(40, null));
    }

    private boolean engineerConfigureRaceEnabled() {
        return getIntent().getBooleanExtra("provider_engineer_configure_race", false);
    }

    private boolean engineerRawCaptureEnabled() {
        return getIntent().getBooleanExtra("engineer_raw_capture", false);
    }

    private void validateEngineerConfigureRace() {
        for (String key : new String[]{"provider_engineer_configure_race", "configure_only", "engineer_raw_capture"})
            if (getIntent().hasExtra(key) && !(getIntent().getExtras().get(key) instanceof Boolean))
                throw new IllegalArgumentException(key + " must be boolean");
        if (!engineerConfigureRaceEnabled()) {
            if (getIntent().getBooleanExtra("configure_only", false) || engineerRawCaptureEnabled())
                throw new IllegalArgumentException("engineer phases require provider_engineer_configure_race");
            return;
        }
        if (!"vivo".equalsIgnoreCase(android.os.Build.MANUFACTURER)
                || !"PD2454".equals(android.os.Build.DEVICE))
            throw new IllegalArgumentException("provider_engineer_configure_race supports only vivo PD2454");
        if (probeOrdinal != 1)
            throw new IllegalArgumentException("provider_engineer_configure_race requires first probe in a fresh process");
        if (getIntent().getBooleanExtra("configure_only", false) == engineerRawCaptureEnabled() || !alphaRawGraph()
                || !getIntent().getBooleanExtra("native_raw", false))
            throw new IllegalArgumentException("provider_engineer_configure_race requires exactly one phase, alpha_raw_graph and native_raw");
        List<String> allowed = new ArrayList<>(Arrays.asList("provider_engineer_configure_race", "configure_only", "engineer_raw_capture",
                "alpha_raw_graph", "native_raw", "camera", "width", "height", "preview_width", "preview_height"));
        if (engineerRawCaptureEnabled()) allowed.addAll(Arrays.asList("force_sensor_mode", "engineer_remosaic_mode"));
        for (String key : getIntent().getExtras().keySet())
            if (!allowed.contains(key))
                throw new IllegalArgumentException("provider_engineer_configure_race incompatible with " + key);
        if (getIntent().hasExtra("force_sensor_mode")) {
            Object mode = getIntent().getExtras().get("force_sensor_mode");
            List<Integer> modes = "5".equals(cameraId()) ? Arrays.asList(0, 1, 5, 7, 20, 23)
                    : "4".equals(cameraId()) ? Arrays.asList(3, 4, 10) : Arrays.asList(3, 4, 12);
            if (!(mode instanceof Integer) || !modes.contains(mode))
                throw new IllegalArgumentException("engineer RAW phase requires a decoded native sensor mode");
        }
    }

    private final class EngineerConfigureRace {
        private final Object manager;
        private final java.lang.reflect.Method get, set;
        private final int savedApp, savedVcf, savedSdk;
        private final AtomicBoolean running = new AtomicBoolean();
        private Thread worker;
        private CountDownLatch workerFinished = new CountDownLatch(0);
        private boolean started;
        private volatile int submitted;

        EngineerConfigureRace() throws Exception {
            Class<?> api = Class.forName("android.hardware.vivocamera.VivoCameraManager");
            manager = api.getMethod("getInstance").invoke(null);
            get = api.getMethod("getInfo", int.class);
            set = api.getMethod("setValue", int.class, int.class);
            savedApp = read(1);
            savedVcf = read(33);
            savedSdk = read(40);
            if (savedApp < 0 || savedVcf < -1 || savedSdk != 0 && savedSdk != -1)
                throw new IllegalStateException("engineer configure race needs readable AppType/VCF and disabled SDK: "
                        + savedApp + "/" + savedVcf + "/" + savedSdk);
            Log.i(TAG, "engineer race snapshot AppType=" + savedApp + " VCF=" + savedVcf
                    + (savedVcf == -1 ? " (decoded ELF initial default)" : "")
                    + " SDK=" + savedSdk + (savedSdk == -1 ? " (decoded disabled state)" : ""));
        }

        private int read(int key) throws Exception { return (Integer) get.invoke(manager, key); }

        synchronized void start() {
            if (started || stopped) throw new IllegalStateException("engineer configure race already stopped or attempted");
            started = true;
            running.set(true);
            workerFinished = new CountDownLatch(1);
            worker = new Thread(() -> {
                long deadline = android.os.SystemClock.elapsedRealtime() + 2000;
                try {
                    while (running.get() && submitted < 128
                            && android.os.SystemClock.elapsedRealtime() < deadline) {
                        set.invoke(manager, 1, 5);
                        submitted++;
                        android.os.SystemClock.sleep(4);
                    }
                } catch (Exception failure) {
                    Log.e(TAG, "engineer race setter failed", failure);
                    stop("engineer race setter failed");
                } finally {
                    running.set(false);
                    Log.i(TAG, "engineer race submissions=" + submitted + " stopped; one-way queue completion unconfirmed");
                    workerFinished.countDown();
                    if (stopped) cleanupEngineerConfigureRace("setter completed after stop");
                }
            }, "SCAMERA_ENGINEER_CONFIGURE_RACE");
            worker.start();
        }

        void requestStop() { running.set(false); }

        boolean awaitWorker() {
            requestStop();
            CountDownLatch pending;
            synchronized (this) { pending = workerFinished; }
            return awaitEngineerLatch(pending, "setter completion");
        }

        synchronized boolean workerQuiescent() { return workerFinished.getCount() == 0; }

        void restore() {
            for (int[] entry : new int[][]{{33, savedVcf}, {40, savedSdk}, {1, savedApp}})
                try { set.invoke(manager, entry[0], entry[1]); }
                catch (Exception failure) { Log.e(TAG, "engineer race restore key=" + entry[0] + " failed", failure); }
            int matching = 0;
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    int app = read(1), vcf = read(33), sdk = read(40);
                    matching = app == savedApp && vcf == savedVcf && sdk == savedSdk ? matching + 1 : 0;
                    Log.i(TAG, "engineer race restore readback " + attempt + " AppType=" + app
                            + " VCF=" + vcf + " SDK=" + sdk + " matching=" + matching);
                    if (matching == 3) {
                        Log.i(TAG, "engineer race saved values observed; one-way queue drain unconfirmed");
                        return;
                    }
                } catch (Exception failure) { Log.e(TAG, "engineer race restore readback failed", failure); }
                android.os.SystemClock.sleep(50);
            }
            Log.e(TAG, "engineer race restoration unconfirmed after finite readback");
        }
    }

    private boolean videoSnapshot() {
        return getIntent().getBooleanExtra("video_snapshot", false);
    }

    private int captureTemplate() {
        return videoSnapshot() ? CameraDevice.TEMPLATE_VIDEO_SNAPSHOT
                : getIntent().getIntExtra("capture_template", CameraDevice.TEMPLATE_STILL_CAPTURE);
    }

    private void validateVideoSnapshot() {
        if (getIntent().hasExtra("video_snapshot")
                && !(getIntent().getExtras().get("video_snapshot") instanceof Boolean))
            throw new IllegalArgumentException("video_snapshot must be boolean");
        if (!videoSnapshot()) return;
        for (String name : new String[]{"provider_sdk_session", "sdk_client_only", "vivo_api", "vif_capture"})
            if (!getIntent().getBooleanExtra(name, false))
                throw new IllegalArgumentException("video_snapshot requires " + name);
        if (getIntent().getIntExtra("ui", -1) != 3
                || getIntent().getIntExtra("repeating_ui", 3) != 3
                || getIntent().getIntExtra("capture_ui", 3) != 3)
            throw new IllegalArgumentException("video_snapshot requires UI3 throughout");
        if (getIntent().getIntExtra("capture_template", CameraDevice.TEMPLATE_VIDEO_SNAPSHOT)
                != CameraDevice.TEMPLATE_VIDEO_SNAPSHOT
                || getIntent().getIntExtra("repeating_template", CameraDevice.TEMPLATE_RECORD)
                != CameraDevice.TEMPLATE_RECORD)
            throw new IllegalArgumentException("video_snapshot requires capture_template4 and repeating_template3");
        for (String name : new String[]{"stock_pro_capture", "stock_capture_flag", "pro_base", "pro_raw",
                "native_raw", "alpha_raw_graph", "raw_only", "preview_only", "raw_ring", "raw_vif10",
                "capture_repeating"})
            if (getIntent().getBooleanExtra(name, false))
                throw new IllegalArgumentException("video_snapshot conflicts with " + name);
        for (String name : new String[]{"capture_sensor_mode", "sensor_mode", "mode_ex", "manual_iso"})
            if (getIntent().hasExtra(name))
                throw new IllegalArgumentException("video_snapshot conflicts with " + name);
        String tags = getIntent().getStringExtra("tag_ints");
        if (tags != null) for (String tag : tags.split(";")) {
            String name = tag.split("=", 2)[0];
            if (Arrays.asList("vivo.control.is_pro_mode", "vivo.control.currentModeEx",
                    "vivo.control.isPAPNeeded", "vivo.control.sensorMode", "vivo.control.isCapture",
                    "com.vivo.current_ui_module").contains(name))
                throw new IllegalArgumentException("video_snapshot conflicts with tag " + name);
        }
    }

    private static void applyVideoSnapshotControls(CaptureRequest.Builder builder, boolean capture) {
        builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF);
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        if (!capture) return;
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
        builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
        builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
    }

    private void validateAlphaRawGraph() {
        if (getIntent().hasExtra("alpha_raw_graph")
                && !(getIntent().getExtras().get("alpha_raw_graph") instanceof Boolean))
            throw new IllegalArgumentException("alpha_raw_graph must be boolean");
        if (!alphaRawGraph()) {
            if (getIntent().hasExtra("engineer_remosaic_mode"))
                throw new IllegalArgumentException("engineer_remosaic_mode requires alpha_raw_graph");
            return;
        }
        if (!getIntent().getBooleanExtra("native_raw", false))
            throw new IllegalArgumentException("alpha_raw_graph requires native_raw");
        if (!engineerConfigureRaceEnabled()
                && !"com.vivo.alphacamera".equals(getIntent().getStringExtra("diagnostic_spoof_package")))
            throw new IllegalArgumentException("alpha_raw_graph requires explicit diagnostic_spoof_package=com.vivo.alphacamera");
        if (!Arrays.asList("3", "4", "5").contains(cameraId()))
            throw new IllegalArgumentException("alpha_raw_graph requires rear camera 3, 4 or 5");
        List<String> allowed = new ArrayList<>(Arrays.asList("alpha_raw_graph", "native_raw", "diagnostic_spoof_package",
                "camera", "width", "height", "preview_width", "preview_height", "engineer_remosaic_mode",
                "raw_ring", "no_capture", "capture_after_ms", "capture_count", "serial_capture_count",
                "defer_error_close"));
        if (engineerConfigureRaceEnabled())
            allowed.addAll(Arrays.asList("provider_engineer_configure_race", "configure_only", "engineer_raw_capture"));
        if (engineerConfigureRaceEnabled() && engineerRawCaptureEnabled()) allowed.add("force_sensor_mode");
        for (String key : getIntent().getExtras().keySet())
            if (!allowed.contains(key))
                throw new IllegalArgumentException("alpha_raw_graph incompatible with " + key);
        if (getIntent().hasExtra("width") != getIntent().hasExtra("height")
                || getIntent().getIntExtra("width", 1) <= 0 || getIntent().getIntExtra("height", 1) <= 0
                || getIntent().getIntExtra("preview_width", 1440) <= 0
                || getIntent().getIntExtra("preview_height", 1080) <= 0)
            throw new IllegalArgumentException("alpha_raw_graph requires positive width/height pairs");
        if (getIntent().hasExtra("engineer_remosaic_mode")) {
            int mode = getIntent().getIntExtra("engineer_remosaic_mode", 0);
            if (mode != 1 && mode != 3)
                throw new IllegalArgumentException("alpha_raw_graph only supports source-verified engineer_remosaic_mode 1 or 3");
        }
        if (getIntent().getBooleanExtra("raw_ring", false)
                && (getIntent().hasExtra("capture_after_ms") || getIntent().hasExtra("capture_count")
                || getIntent().hasExtra("serial_capture_count")))
            throw new IllegalArgumentException("alpha_raw_graph raw_ring cannot schedule a separate capture");
    }

    private static String alphaStaticString(CameraCharacteristics characteristics, String name) {
        byte[] bytes = characteristics.get(new CameraCharacteristics.Key<>(name, byte[].class));
        if (bytes == null || bytes.length == 0)
            throw new IllegalArgumentException("alpha_raw_graph missing static " + name);
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) {
            if (bytes[length] < 32 || bytes[length] > 126)
                throw new IllegalArgumentException("alpha_raw_graph invalid static " + name);
            length++;
        }
        String value = new String(bytes, 0, length, java.nio.charset.StandardCharsets.US_ASCII).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("alpha_raw_graph empty static " + name);
        return value;
    }

    private void applyAlphaRawGraph(CaptureRequest.Builder builder) {
        builder.set(new CaptureRequest.Key<>("vivo.control.engineer", Integer.class), 0);
        builder.set(new CaptureRequest.Key<>("vivo.control.is_engineering_mode", Integer.class), 1);
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW);
        if (getIntent().hasExtra("engineer_remosaic_mode"))
            builder.set(new CaptureRequest.Key<>("vivo.control.EngineerRemosaicMode", Integer.class),
                    getIntent().getIntExtra("engineer_remosaic_mode", 0));
        builder.set(new CaptureRequest.Key<>("com.vivo.chi.override.CameraType", String.class), alphaCameraType);
        builder.set(new CaptureRequest.Key<>("com.vivo.chi.override.CameraComposition", String.class), alphaCameraComposition);
        Log.i(TAG, "alpha_raw_graph engineer=0 is_engineering_mode=1 captureIntent=1 remosaic="
                + getIntent().getIntExtra("engineer_remosaic_mode", 0));
    }

    private boolean rawOnly() {
        return getIntent().getBooleanExtra("raw_only", false);
    }

    private void validateRawOnly() {
        if (!rawOnly()) return;
        if (!getIntent().getBooleanExtra("native_raw", false))
            throw new IllegalArgumentException("raw_only requires native_raw");
        for (String flag : new String[]{"preview_only", "raw_vif10", "jpeg", "aux_raw", "stock_photo_graph",
                "repeating_jpeg", "capture_preview", "provider_sdk_session", "provider_sdk",
                "provider_sdk_after_open", "vivo_api", "reset_sdk", "clear_sdk_after_open",
                "clear_sdk_after_configure"})
            if (getIntent().getBooleanExtra(flag, false))
                throw new IllegalArgumentException("raw_only incompatible with " + flag);
        int template = getIntent().getIntExtra("repeating_template", CameraDevice.TEMPLATE_PREVIEW);
        if (template != CameraDevice.TEMPLATE_PREVIEW && template != CameraDevice.TEMPLATE_RECORD)
            throw new IllegalArgumentException("raw_only requires PREVIEW or RECORD repeating template");
    }

    private boolean previewOnly() {
        return getIntent().getBooleanExtra("preview_only", false);
    }

    private void validatePreviewOnly() {
        if (!previewOnly()) return;
        if (!getIntent().getBooleanExtra("native_raw", false))
            throw new IllegalArgumentException("preview_only requires native_raw");
        for (String flag : new String[]{"raw_ring", "raw_vif10", "jpeg", "aux_raw", "stock_photo_graph",
                "repeating_jpeg", "provider_sdk_session", "provider_sdk", "provider_sdk_after_open", "vivo_api"})
            if (getIntent().getBooleanExtra(flag, false))
                throw new IllegalArgumentException("preview_only incompatible with " + flag);
        if (getIntent().hasExtra("physical_raw") || getIntent().hasExtra("raw_use_case"))
            throw new IllegalArgumentException("preview_only has no RAW stream");
        if (getIntent().getIntExtra("preview_format", ImageFormat.PRIVATE) != ImageFormat.PRIVATE
                || getIntent().getLongExtra("preview_buffer_usage", 256L) != 256L)
            throw new IllegalArgumentException("preview_only requires PRIVATE preview with usage256");
    }

    private boolean rawVif10() {
        return getIntent().getBooleanExtra("raw_vif10", false);
    }

    private ImageReader rawReader(int width, int height, int maxImages, int format) {
        if (!getIntent().hasExtra("raw_buffer_usage"))
            return ImageReader.newInstance(width, height, format, maxImages);
        long usage = getIntent().getLongExtra("raw_buffer_usage", 3);
        Log.i(TAG, "RAW buffer usage=" + usage + " format=" + format);
        return ImageReader.newInstance(width, height, format, maxImages, usage);
    }

    private ImageReader drainedYuvReader(int width, int height, int maxImages, long usage) {
        ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, maxImages, usage);
        reader.setOnImageAvailableListener(source -> {
            try (Image image = source.acquireLatestImage()) {
                if (image != null) Log.i(TAG, "YUV delivered " + width + "x" + height);
            } catch (RuntimeException e) { stop("YUV " + e); }
        }, handler);
        return reader;
    }

    private void configure() {
        try {
            List<OutputConfiguration> outputs = new ArrayList<>();
            if (preview != null) outputs.add(new OutputConfiguration(preview.getSurface()));
            if (previewNavigation != null) outputs.add(new OutputConfiguration(previewNavigation.getSurface()));
            if (realtimeYuv != null) outputs.add(new OutputConfiguration(realtimeYuv.getSurface()));
            OutputConfiguration rawOutput = raw == null ? null : new OutputConfiguration(raw.getSurface());
            if (rawOutput != null) {
                outputs.add(rawOutput);
                if (getIntent().hasExtra("raw_use_case"))
                    rawOutput.setStreamUseCase(getIntent().getLongExtra("raw_use_case", 0));
                Log.i(TAG, "RAW stream usecase=" + rawOutput.getStreamUseCase());
            }
            if (jpeg != null) outputs.add(new OutputConfiguration(jpeg.getSurface()));
            if (auxiliaryRaw != null) outputs.add(new OutputConfiguration(auxiliaryRaw.getSurface()));
            if (videoEncoder != null) outputs.add(new OutputConfiguration(videoEncoder.getSurface()));
            if (alphaRawGraph() && (outputs.size() != 3 || preview == null || realtimeYuv == null
                    || raw == null || jpeg != null || auxiliaryRaw != null || previewNavigation != null))
                throw new IllegalStateException("alpha_raw_graph requires exactly PRIVATE, YUV35 and RAW32");
            Log.i(TAG, "session outputs=" + outputs.size() + " previewOnly=" + previewOnly()
                    + " rawOnly=" + rawOnly());
            String physical = getIntent().getStringExtra("physical_raw");
            if (physical != null) {
                if (rawOutput == null) throw new IllegalArgumentException("physical_raw requires RAW stream");
                rawOutput.setPhysicalCameraId(physical);
                if (auxiliaryRaw != null) outputs.get(outputs.size() - 1).setPhysicalCameraId(physical);
            }
            SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs,
                    command -> handler.post(command), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession ready) {
                    if (engineerConfigureRace != null && stopped) { ready.close(); return; }
                    session = ready;
                    if (engineerConfigureRace != null) {
                        engineerConfigureRace.requestStop();
                        if (!engineerRawCaptureEnabled()) {
                            Log.i(TAG, "configured configure_only; no repeating or STILL requests submitted");
                            stop("engineer configure-only complete");
                            return;
                        }
                        if (!engineerConfigureRace.awaitWorker() || stopped) {
                            stop("engineer RAW phase setter unresolved or stopped");
                            return;
                        }
                        Log.i(TAG, "engineer RAW phase; setter stopped, category admission unverified without observer");
                    }
                    try {
                        if (getIntent().getBooleanExtra("clear_sdk_after_configure", false)) {
                            providerValue(40, 0);
                            android.os.SystemClock.sleep(100);
                            Log.i(TAG, "SDK after configure readback=" + providerValue(40, null)
                                    + " AppType=" + providerValue(1, null) + " VCFAppType=" + providerValue(33, null));
                        }
                        CaptureRequest.Builder builder = request(getIntent().getIntExtra(
                                "repeating_template", videoSnapshot() ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW));
                        if (getIntent().hasExtra("repeating_ui"))
                            set(builder, "com.vivo.current_ui_module", new int[]{getIntent().getIntExtra("repeating_ui", 1)});
                        if (preview != null) builder.addTarget(preview.getSurface());
                        if (previewNavigation != null) builder.addTarget(previewNavigation.getSurface());
                        if (realtimeYuv != null) builder.addTarget(realtimeYuv.getSurface());
                        if (videoEncoder != null) builder.addTarget(videoEncoder.getSurface());
                        if (rawOnly() || getIntent().getBooleanExtra("raw_ring", false) || engineerRawCaptureEnabled()) {
                            builder.addTarget(raw.getSurface());
                            if (alphaRawGraph()) builder.setTag(Boolean.TRUE);
                        }
                        if (rawVif10() && getIntent().getBooleanExtra("raw_ring", false))
                            builder.addTarget(auxiliaryRaw.getSurface());
                        if (jpeg != null && getIntent().getBooleanExtra("repeating_jpeg", false))
                            builder.addTarget(jpeg.getSurface());
                        setProviderApp();
                        previewRequest = builder.build();
                        ready.setRepeatingRequest(previewRequest, callback, handler);
                        Log.i(TAG, "configured");
                        if (!previewOnly() && !rawOnly() && getIntent().hasExtra("capture_after_ms"))
                            handler.postDelayed(VivoQcomProbeActivity.this::capture,
                                    Math.max(100, getIntent().getIntExtra("capture_after_ms", 500)));
                    } catch (Exception e) { stop("repeat " + e); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) {
                    if (engineerConfigureRace != null) {
                        if (stopped) { failed.close(); return; }
                        session = failed;
                    }
                    stop("configure failed");
                }
            });
            CaptureRequest.Builder parameters = request(CameraDevice.TEMPLATE_PREVIEW);
            if (!getIntent().getBooleanExtra("native_raw", false))
                set(parameters, "vivo.control.streamsUsage", new int[]{2, 1, getIntent().getIntExtra("raw_usage", 7)});
            if (sdkRawSession != null) sdkRawSession.applyStreamUsage(parameters);
            if (sdkRawSession != null && getIntent().hasExtra("raw_usage"))
                set(parameters, "vivo.control.streamsUsage",
                        new int[]{2, 1, getIntent().getIntExtra("raw_usage", 0)});
            if (getIntent().getBooleanExtra("stock_photo_graph", false))
                set(parameters, "vivo.control.streamsUsage", new int[]{4, 1, 4, 19, 0});
            if (getIntent().hasExtra("force_sensor_mode"))
                set(parameters, "vivo.control.forceSensorMode", new int[]{getIntent().getIntExtra("force_sensor_mode", 0)});
            if (getIntent().getBooleanExtra("enable_isz", false))
                parameters.set(new CaptureRequest.Key<>(
                        "org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom", Integer.class), 1);
            if (getIntent().hasExtra("snapshot_only_isz"))
                parameters.set(new CaptureRequest.Key<>(
                        "org.codeaurora.qcamera3.sessionParameters.EnableSnapshotOnlyInsensorZoom", Integer.class),
                        getIntent().getIntExtra("snapshot_only_isz", 0));
            if (getIntent().hasExtra("dcg_mode"))
                parameters.set(new CaptureRequest.Key<>(
                        "org.codeaurora.qcamera3.sessionParameters.EnableHDRDCGMode", Integer.class),
                        getIntent().getIntExtra("dcg_mode", 0));
            if (getIntent().hasExtra("num_hdr_exposure"))
                parameters.set(new CaptureRequest.Key<>(
                        "org.codeaurora.qcamera3.sessionParameters.numHDRexposure", Integer.class),
                        getIntent().getIntExtra("num_hdr_exposure", 0));
            if (getIntent().hasExtra("snapshot_hdr"))
                parameters.set(new CaptureRequest.Key<>(
                        "org.codeaurora.qcamera3.sessionParameters.SnapshotHDRMode", Integer.class),
                        getIntent().getIntExtra("snapshot_hdr", 0));
            if (rawVif10()) {
                if (outputs.size() != 3 || auxiliaryRaw == null || raw.getImageFormat() != ImageFormat.RAW10
                        || auxiliaryRaw.getImageFormat() != ImageFormat.RAW10)
                    throw new IllegalArgumentException("raw_vif10 requires only preview, full RAW10 and binned RAW10");
                set(parameters, "vivo.control.streamsUsage", new int[]{3, 1, 0, 0});
            }
            applyThreeStreamUsage(parameters);
            config.setSessionParameters(parameters.build());
            setProviderApp();
            if (engineerConfigureRace != null) engineerConfigureRace.start();
            device.createCaptureSession(config);
        } catch (Exception e) { stop("configure " + e); }
    }

    private void setProviderApp() throws Exception {
        boolean app = getIntent().getBooleanExtra("provider_app", false);
        boolean vcf = getIntent().hasExtra("provider_vcf_type");
        if (!app && !vcf) return;
        Class<?> api = Class.forName("android.hardware.vivocamera.VivoCameraManager");
        Object manager = api.getMethod("getInstance").invoke(null);
        if (app) {
            if (!previousProviderParams.containsKey(1)) previousProviderParams.put(1, providerValue(1, null));
            api.getMethod("setValue", int.class, int.class).invoke(manager, 1,
                    getIntent().getIntExtra("provider_app_type", 1));
        }
        if (vcf) {
            if (previousVcfType == null) previousVcfType = providerValue(33, null);
            providerValue(33, getIntent().getIntExtra("provider_vcf_type", 2));
        }
        android.os.SystemClock.sleep(100);
        Log.i(TAG, "provider AppType=" + api.getMethod("getInfo", int.class).invoke(manager, 1)
                + " VCFAppType=" + providerValue(33, null));
    }

    private static int providerValue(int key, Integer value) throws Exception {
        Class<?> api = Class.forName("android.hardware.vivocamera.VivoCameraManager");
        Object manager = api.getMethod("getInstance").invoke(null);
        if (value != null) api.getMethod("setValue", int.class, int.class).invoke(manager, key, value);
        return (Integer) api.getMethod("getInfo", int.class).invoke(manager, key);
    }

    private void setProviderParams() throws Exception {
        String params = getIntent().getStringExtra("provider_params");
        if (params == null) return;
        for (String entry : params.split(";")) {
            String[] pair = entry.split("=", 2);
            if (pair.length != 2) throw new IllegalArgumentException("invalid provider parameter");
            int key = Integer.parseInt(pair[0]), value = Integer.parseInt(pair[1]);
            if (key != 7 && key != 25 && key != 38 && key != 52)
                throw new IllegalArgumentException("provider parameter not decoded: " + key);
            if (!previousProviderParams.containsKey(key)) previousProviderParams.put(key, providerValue(key, null));
            providerValue(key, value);
            android.os.SystemClock.sleep(100);
            Log.i(TAG, "provider parameter " + key + "=" + providerValue(key, null));
        }
    }

    private void openVivoApi() throws Exception {
        Class<?> api = Class.forName("android.hardware.vivocamera.VivoCameraManager");
        Class<?> callbackType = Class.forName("android.hardware.vivocamera.IVivoCameraDeviceCb");
        Object callback = java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                (proxy, method, args) -> {
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("toString")) return "SCAMERA_VCF_PROBE";
                    Log.i(TAG, "Vivo API callback " + method.getName());
                    try {
                        if (method.getName().equals("onBufferCallback") && args != null && !stopped)
                            saveCallbackBuffer(args);
                        if (method.getName().equals("onVIFResultCallback") && args != null && args.length > 0 && !stopped)
                            saveVifResult(args[0]);
                        if (method.getName().equals("onCaptureFrameDone") && args != null && args.length > 0)
                            Log.i(TAG, "VIF frame done captureId=" + args[0]);
                    } catch (Exception e) {
                        Log.e(TAG, "Vivo callback evidence " + method.getName(), e);
                    } finally {
                        if (args != null) for (Object argument : args) {
                            if (argument instanceof android.os.ParcelFileDescriptor) try {
                                ((android.os.ParcelFileDescriptor) argument).close();
                            } catch (Exception e) { Log.e(TAG, "Vivo callback FD close", e); }
                        }
                    }
                    return null;
                });
        Object sdk = api.getMethod("getInstance").invoke(null);
        android.content.Context sdkContext = this;
        String sdkPackage = getIntent().getStringExtra("vivo_api_package");
        if (sdkPackage != null) sdkContext = new android.content.ContextWrapper(this) {
            @Override public String getPackageName() { return sdkPackage; }
            public String getOpPackageName() { return sdkPackage; }
        };
        vivoDevice = (AutoCloseable) api.getMethod("open", android.content.Context.class, callbackType).invoke(sdk, sdkContext, callback);
        if (vivoDevice == null) throw new IllegalStateException("Vivo API returned no device");
        vivoDevice.getClass().getMethod("initialize").invoke(vivoDevice);
        Log.i(TAG, "Vivo API opened and initialized uid=" + android.os.Process.myUid());
    }

    private void saveCallbackBuffer(Object[] args) {
        android.os.ParcelFileDescriptor fd = (android.os.ParcelFileDescriptor) args[1];
        int size = (Integer) args[2];
        Log.i(TAG, "Vivo buffer args=" + Arrays.toString(args));
        if (fd == null || size <= 0 || size > 256 * 1024 * 1024) return;
        File file = new File(getExternalFilesDir("vcf-probe"), "vivo-buffer-" + args[0] + "-" + args[3] + "x" + args[4] + "-" + args[7] + ".bin");
        try (android.os.ParcelFileDescriptor.AutoCloseInputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                     android.os.ParcelFileDescriptor.dup(fd.getFileDescriptor()));
             FileOutputStream output = new FileOutputStream(file)) {
            java.nio.MappedByteBuffer buffer = input.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, size);
            try {
                while (buffer.hasRemaining()) output.getChannel().write(buffer);
                Log.i(TAG, "Vivo buffer saved bytes=" + size + " file=" + file);
            } finally {
                android.os.SharedMemory.unmap(buffer);
            }
            JSONObject metadata = new JSONObject();
            metadata.put("captureId", args[0]);
            metadata.put("uid", android.os.Process.myUid());
            metadata.put("bytes", size);
            metadata.put("width", args[3]);
            metadata.put("height", args[4]);
            metadata.put("stride", args[5]);
            metadata.put("scanlines", args[6]);
            metadata.put("format", args[7]);
            metadata.put("bufferFile", file.getName());
            writeVifJson(file.getName().replace(".bin", ".json"), metadata);
        } catch (Exception e) { Log.e(TAG, "Vivo buffer save", e); }
    }

    private void saveVifResult(Object argument) throws Exception {
        if (!(argument instanceof CaptureResult))
            throw new IllegalArgumentException("VIF result is not CaptureResult: " + argument);
        CaptureResult result = (CaptureResult) argument;
        long id = (Long) argument.getClass().getMethod("getCaptureId").invoke(argument);
        JSONObject metadata = new JSONObject();
        metadata.put("captureId", id);
        metadata.put("uid", android.os.Process.myUid());
        metadata.put("resultClass", argument.getClass().getName());
        metadata.put("sensorTimestamp", vifJsonValue(result.get(CaptureResult.SENSOR_TIMESTAMP)));
        metadata.put("dynamicWhiteLevel", vifJsonValue(result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)));
        metadata.put("dynamicBlackLevel", vifJsonValue(result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)));
        metadata.put("exposureTime", vifJsonValue(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)));
        metadata.put("sensitivity", vifJsonValue(result.get(CaptureResult.SENSOR_SENSITIVITY)));
        JSONObject vendor = new JSONObject();
        for (CaptureResult.Key<?> key : result.getKeys()) {
            String name = key.getName();
            if (name.startsWith("android.")
                    || !name.matches("(?i).*(sensor.?mode|current.?mode|mode_index|dcg|hdr|exposurecount|bitswitch|rawtype|remosaic|seamless).*"))
                continue;
            try { vendor.put(name, vifJsonValue(result.get(key))); }
            catch (Exception e) { Log.e(TAG, "VIF result key " + name, e); }
        }
        metadata.put("vendor", vendor);
        writeVifJson("vif-result-" + id + ".json", metadata);
        Log.i(TAG, "VIF result captureId=" + id + " timestamp=" + result.get(CaptureResult.SENSOR_TIMESTAMP)
                + " white=" + result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL));
    }

    private static Object vifJsonValue(Object value) throws Exception {
        if (value == null) return JSONObject.NULL;
        if (value.getClass().isArray()) {
            JSONArray array = new JSONArray();
            for (int i = 0; i < Array.getLength(value); i++) array.put(vifJsonValue(Array.get(value, i)));
            return array;
        }
        if (value instanceof Double && !Double.isFinite((Double) value)
                || value instanceof Float && !Float.isFinite((Float) value)) return value.toString();
        if (value instanceof Number || value instanceof Boolean || value instanceof String) return value;
        return value.toString();
    }

    private void writeVifJson(String name, JSONObject metadata) throws Exception {
        File file = new File(getExternalFilesDir("vcf-probe"), name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(metadata.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Log.i(TAG, "VIF metadata saved file=" + file);
    }

    private CaptureRequest.Builder request(int template) throws Exception {
        CaptureRequest.Builder builder = device.createCaptureRequest(template);
        if (alphaRawGraph()) {
            applyAlphaRawGraph(builder);
            if (engineerRawCaptureEnabled() && getIntent().hasExtra("force_sensor_mode"))
                set(builder, "vivo.control.forceSensorMode",
                        new int[]{getIntent().getIntExtra("force_sensor_mode", 0)});
            return builder;
        }
        if (sdkRawSession != null && !getIntent().getBooleanExtra("sdk_client_only", false)) sdkRawSession.apply(builder,
                template == CameraDevice.TEMPLATE_STILL_CAPTURE, previewTimestamp);
        if (sdkRawSession != null && getIntent().getBooleanExtra("sdk_auto_exposure", false)) {
            VivoSdkRawSession.applyAutomaticExposure(builder);
            Log.i(TAG, "SDK auto exposure template=" + template + " ISO=" + builder.get(CaptureRequest.SENSOR_SENSITIVITY)
                    + " shutter=" + builder.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
        }
        if (getIntent().hasExtra("zoom_ratio"))
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, getIntent().getFloatExtra("zoom_ratio", 1f));
        if (getIntent().hasExtra("xcfa_remosaic_type")) {
            int value = getIntent().getIntExtra("xcfa_remosaic_type", 0);
            builder.set(new CaptureRequest.Key<>("org.quic.camera.XCFARemosaicType.XCFARemosaicType", Integer.class), value);
            Log.i(TAG, "XCFA remosaic override=" + value + " template=" + template);
        }
        if (getIntent().hasExtra("num_hdr_exposure"))
            builder.set(new CaptureRequest.Key<>(
                    "org.codeaurora.qcamera3.sessionParameters.numHDRexposure", Integer.class),
                    getIntent().getIntExtra("num_hdr_exposure", 0));
        if (getIntent().hasExtra("dcg_mode"))
            builder.set(new CaptureRequest.Key<>(
                    "org.codeaurora.qcamera3.sessionParameters.EnableHDRDCGMode", Integer.class),
                    getIntent().getIntExtra("dcg_mode", 0));
        if (getIntent().hasExtra("capture_bit_switch") && template == captureTemplate())
            set(builder, "com.qti.stats_control.BitSwitchMode",
                    new int[]{getIntent().getIntExtra("capture_bit_switch", 1)});
        if (getIntent().getBooleanExtra("force_sensor_request", false)
                && getIntent().hasExtra("force_sensor_mode"))
            set(builder, "vivo.control.forceSensorMode",
                    new int[]{getIntent().getIntExtra("force_sensor_mode", 0)});
        if (getIntent().getBooleanExtra("shdr", false))
            set(builder, "org.codeaurora.qcamera3.sessionParameters.EnableSHDR", new int[]{1});
        if (getIntent().getBooleanExtra("enable_isz", false))
            builder.set(new CaptureRequest.Key<>(
                    "org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom", Integer.class), 1);
        if (getIntent().getBooleanExtra("log_v2", false)) {
            builder.set(new CaptureRequest.Key<>("vivo.control.session.uiSwitch_state", Integer.class), 32);
            Log.i(TAG, "Log V2 session.uiSwitch_state=32 template=" + template);
        }
        if (getIntent().getBooleanExtra("native_raw", false) && getIntent().hasExtra("raw_usage")
                && !getIntent().getBooleanExtra("three_stream_usage", false)) {
            if (preview == null || raw == null || jpeg != null || auxiliaryRaw != null
                    || previewNavigation != null || realtimeYuv != null)
                throw new IllegalArgumentException("native raw_usage requires exactly PRIVATE/YUV preview and one RAW output");
            set(builder, "vivo.control.streamsUsage", new int[]{2, 1, getIntent().getIntExtra("raw_usage", 0)});
        }
        if (rawVif10()) set(builder, "vivo.control.streamsUsage", new int[]{3, 1, 0, 0});
        if (getIntent().getBooleanExtra("native_raw", false)) {
            if (getIntent().hasExtra("ui"))
                set(builder, "com.vivo.current_ui_module", new int[]{getIntent().getIntExtra("ui", 1)});
            if (getIntent().hasExtra("sensor_mode"))
                set(builder, "vivo.control.sensorMode", new int[]{getIntent().getIntExtra("sensor_mode", 0)});
            applyThreeStreamUsage(builder);
            return builder;
        }
        if (getIntent().getBooleanExtra("stock_stream_map", false)) {
            byte[] map = java.nio.file.Files.readAllBytes(new File(getExternalFilesDir("vcf-probe"), "stock-stream-map.bin").toPath());
            if (map.length != 832) throw new IllegalArgumentException("unexpected PD2454 stock stream map size");
            builder.set(new CaptureRequest.Key<>("org.codeaurora.qcamera3.sessionParameters.availableStreamMap", byte[].class), map);
        }
        if (sdkRawSession == null || getIntent().hasExtra("ui"))
            set(builder, "com.vivo.current_ui_module", new int[]{getIntent().getIntExtra("ui", 1)});
        if (getIntent().getBooleanExtra("isz", false)) set(builder, "vcf.parameter.seamlessMode", new int[]{3, 5, 0});
        if (getIntent().hasExtra("bypass")) set(builder, "vcf.parameter.vcfBypassFlag", new int[]{getIntent().getIntExtra("bypass", 0)});
        if (getIntent().hasExtra("sensor_mode")) set(builder, "vivo.control.sensorMode", new int[]{getIntent().getIntExtra("sensor_mode", 0)});
        if (getIntent().getBooleanExtra("pro_raw", false)) set(builder, "vivo.control.is_ProRaw_on", new int[]{1});
        if (getIntent().getBooleanExtra("pro_base", false)) {
            builder.set(new CaptureRequest.Key<>("vivo.control.currentModeEx", long[].class), new long[]{256});
            set(builder, "vivo.control.is_pro_mode", new int[]{1});
        }
        if (getIntent().getBooleanExtra("stock_pro_capture", false)) {
            builder.set(CaptureRequest.LENS_FOCAL_LENGTH, 10.09f);
            builder.set(new CaptureRequest.Key<>("vivo.control.zoom_ratio", float[].class), new float[]{1f});
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 0L);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, 0);
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 0L);
            builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 0);
            if (template == CameraDevice.TEMPLATE_STILL_CAPTURE
                    || !getIntent().getBooleanExtra("pap_capture_only", false)) {
                builder.set(new CaptureRequest.Key<>("vivo.control.isPAPNeeded", byte[].class), new byte[]{1});
                long id = System.currentTimeMillis();
                builder.set(new CaptureRequest.Key<>("vivo.control.requestId", long[].class), new long[]{id});
                builder.set(new CaptureRequest.Key<>("vivo.control.globalCaptureId", long[].class), new long[]{id});
                if (template == CameraDevice.TEMPLATE_STILL_CAPTURE && getIntent().getBooleanExtra("stock_capture_flag", false)) {
                    builder.set(new CaptureRequest.Key<>("vivo.control.frameFlag", long[].class), new long[]{id});
                    builder.set(new CaptureRequest.Key<>("vivo.control.displayTimeStamp", long[].class), new long[]{previewTimestamp});
                    set(builder, "vivo.control.isCapture", new int[]{0});
                }
            }
        }
        if (template == CameraDevice.TEMPLATE_STILL_CAPTURE && getIntent().hasExtra("capture_sensor_mode")) {
            set(builder, "vivo.control.sensorMode", new int[]{getIntent().getIntExtra("capture_sensor_mode", 0)});
            set(builder, "vivo.control.isUpscale", new int[]{0});
        }
        String tags = getIntent().getStringExtra("tag_ints");
        if (tags != null) for (String tag : tags.split(";")) {
            String[] pair = tag.split("=", 2);
            if (pair.length != 2) throw new IllegalArgumentException("invalid tag " + tag);
            String[] numbers = pair[1].split(",");
            int[] values = new int[numbers.length];
            for (int i = 0; i < values.length; i++) values[i] = Integer.parseInt(numbers[i]);
            set(builder, pair[0], values);
        }
        if (getIntent().hasExtra("manual_iso")) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, getIntent().getIntExtra("manual_iso", 72));
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME,
                    getIntent().getLongExtra("manual_exposure_ns", 10000000));
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 33333333L);
            builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100);
        }
        if (getIntent().hasExtra("mode_ex"))
            builder.set(new CaptureRequest.Key<>("vivo.control.currentModeEx", long[].class),
                    new long[]{getIntent().getLongExtra("mode_ex", 0)});
        if (getIntent().hasExtra("vivo_zoom_ratio"))
            builder.set(new CaptureRequest.Key<>("vivo.control.zoom_ratio", float[].class),
                    new float[]{getIntent().getFloatExtra("vivo_zoom_ratio", 1f)});
        if (videoSnapshot()) applyVideoSnapshotControls(builder, template == CameraDevice.TEMPLATE_VIDEO_SNAPSHOT);
        applyThreeStreamUsage(builder);
        return builder;
    }

    private void applyThreeStreamUsage(CaptureRequest.Builder builder) {
        if (videoEncoder != null) {
            if (preview == null || raw == null || jpeg == null || auxiliaryRaw != null
                    || previewNavigation != null || realtimeYuv != null)
                throw new IllegalArgumentException("video_encoder requires exactly four outputs");
            set(builder, "vivo.control.streamsUsage", new int[]{4, 1, 0, 0, 6});
        }
        if (!getIntent().getBooleanExtra("three_stream_usage", false)) return;
        if (preview == null || raw == null || jpeg == null || auxiliaryRaw != null
                || previewNavigation != null || realtimeYuv != null)
            throw new IllegalArgumentException("three_stream_usage requires exactly preview, RAW and JPEG outputs");
        set(builder, "vivo.control.streamsUsage", new int[]{3, 1, 0, 0});
    }

    private static void set(CaptureRequest.Builder builder, String name, int[] value) {
        try {
            builder.set(new CaptureRequest.Key<>(name, int[].class), value);
            Log.i(TAG, name + "=" + Arrays.toString(value));
        } catch (RuntimeException e) { Log.w(TAG, name + " unavailable " + e); }
    }

    private void capture() {
        if (previewOnly() || rawOnly() || engineerRawCaptureEnabled() || stopped || captured || session == null) return;
        captured = true;
        serialRemaining = Math.max(1, Math.min(8, getIntent().getIntExtra("serial_capture_count", 1)));
        try {
            CaptureRequest.Builder builder = captureRequest();
            setProviderApp();
            if (getIntent().getBooleanExtra("capture_repeating", false)) {
                session.setRepeatingRequest(builder.build(), callback, handler);
                Log.i(TAG, "RAW repeating capture submitted");
            } else {
                int count = Math.max(1, Math.min(8, getIntent().getIntExtra("capture_count", 1)));
                if (count == 1) session.capture(builder.build(), callback, handler);
                else {
                    List<CaptureRequest> requests = new ArrayList<>();
                    requests.add(builder.build());
                    for (int i = 1; i < count; i++) requests.add(captureRequest().build());
                    session.captureBurst(requests, callback, handler);
                }
                Log.i(TAG, "RAW capture submitted count=" + count);
            }
        } catch (Exception e) { stop("capture " + e); }
    }

    private CaptureRequest.Builder captureRequest() throws Exception {
        CaptureRequest.Builder builder = request(captureTemplate());
        if (getIntent().hasExtra("capture_ui"))
            set(builder, "com.vivo.current_ui_module", new int[]{getIntent().getIntExtra("capture_ui", 1)});
        if (getIntent().getBooleanExtra("copy_preview_session_keys", false)) {
            if (previewRequest == null) throw new IllegalStateException("Preview request unavailable");
            for (CaptureRequest.Key<?> key : availableSessionKeys)
                copySessionKey(builder, previewRequest, key);
            Log.i(TAG, "Copied preview session keys=" + availableSessionKeys.size());
        }
        if (videoSnapshot()) {
            applyVideoSnapshotControls(builder, true);
            Log.i(TAG, "video_snapshot template=4 final intent=2, stock Video controls without Pro/PAP");
        }
        if (getIntent().getBooleanExtra("vif_capture", false)) {
            long id = VIF_CAPTURE_ID.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis()));
            builder.set(new CaptureRequest.Key<>("vivo.capability.capture.id", Long.class), id);
            builder.set(new CaptureRequest.Key<>("vivo.control.globalCaptureId", long[].class), new long[]{id});
            for (String name : new String[]{"vivo.control.requestId", "vivo.control.frameFlag"}) {
                CaptureRequest.Key<long[]> key = new CaptureRequest.Key<>(name, long[].class);
                if (builder.get(key) != null) builder.set(key, new long[]{id});
            }
            Log.i(TAG, "VIF capture.id=" + id + " type=Long count=1, matching globalCaptureId");
        }
        builder.addTarget(raw.getSurface());
        if (alphaRawGraph()) builder.setTag(Boolean.TRUE);
        if (getIntent().getBooleanExtra("capture_preview", false)) builder.addTarget(preview.getSurface());
        if (jpeg != null) builder.addTarget(jpeg.getSurface());
        if (videoEncoder != null) builder.addTarget(videoEncoder.getSurface());
        if (auxiliaryRaw != null && getIntent().getBooleanExtra("aux_target", rawVif10()))
            builder.addTarget(auxiliaryRaw.getSurface());
        long[] id = builder.get(new CaptureRequest.Key<>("vivo.control.globalCaptureId", long[].class));
        if (id != null) Log.i(TAG, "capture globalCaptureId=" + Arrays.toString(id));
        applyThreeStreamUsage(builder);
        return builder;
    }

    private static <T> void copySessionKey(CaptureRequest.Builder builder, CaptureRequest source,
                                          CaptureRequest.Key<T> key) {
        builder.set(key, source.get(key));
    }

    private final CameraCaptureSession.CaptureCallback callback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            if (stopped) return;
            Integer intent = request.get(CaptureRequest.CONTROL_CAPTURE_INTENT);
            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (intent != null && intent == CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW && timestamp != null)
                previewTimestamp = timestamp;
            boolean alphaRawTarget = alphaRawGraph() && Boolean.TRUE.equals(request.getTag());
            if (!previewOnly() && !rawOnly() && !getIntent().getBooleanExtra("raw_ring", false)
                    && !alphaRawTarget
                    && (intent == null || intent != CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)) return;
            if ((previewOnly() || rawOnly() || intent != null && intent == CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW)
                    && ++ringResults > 8) return;
            Log.i(TAG, "result captureIntent=" + intent + " timestamp=" + timestamp);
            for (CaptureResult.Key<?> key : result.getKeys()) {
                String name = key.getName();
                if (!name.matches(".*(seamless|sensorMode|SensorMode|mode_index|current_mode|WHITE|whiteLevel|white_level|activePhysicalId|exposureTime|sensitivity|timestamp|cropRegion|focalLength|echo|Echo|vif|VIF|satApp).*")) continue;
                Object value = result.get(key);
                if (value == null) continue;
                String text = String.valueOf(value);
                if (value.getClass().isArray()) {
                    List<String> fields = new ArrayList<>();
                    for (int i = 0; i < Math.min(Array.getLength(value), 32); i++) fields.add(String.valueOf(Array.get(value, i)));
                    text = fields.toString();
                }
                Log.i(TAG, "result " + name + "=" + text);
            }
            Log.i(TAG, "result white=" + result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL));
            if ((alphaRawTarget || intent != null && intent == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                    && serialRemaining > 1) {
                serialRemaining--;
                handler.postDelayed(() -> {
                    if (stopped || session == null) return;
                    try {
                        session.capture(captureRequest().build(), callback, handler);
                        Log.i(TAG, "serial RAW capture submitted remaining=" + serialRemaining);
                    } catch (Exception e) { stop("serial capture " + e); }
                }, 100);
            }
        }
    };

    private void saveRaw(ImageReader reader) {
        try (Image image = reader.acquireNextImage()) {
            if (image == null || stopped) return;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer data = plane.getBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            boolean packed10 = image.getFormat() == ImageFormat.RAW10;
            if (packed10 && plane.getPixelStride() != 0)
                throw new IllegalArgumentException("RAW10 must have pixelStride=0");
            if (packed10) validateRaw10(data, image.getWidth(), image.getHeight(), plane.getRowStride());
            else if (image.getFormat() != ImageFormat.RAW_SENSOR)
                throw new IllegalArgumentException("unexpected RAW format=" + image.getFormat());
            int min = 65535, max = 0;
            long sampleHash = 0xcbf29ce484222325L;
            if (packed10) {
                int stepX = Math.max(4, (image.getWidth() / 64) & ~3);
                int stepY = Math.max(1, image.getHeight() / 64);
                for (int y = 0; y < image.getHeight(); y += stepY)
                    for (int x = 0; x < image.getWidth(); x += stepX)
                        for (int lane = 0; lane < 4 && x + lane < image.getWidth(); lane++) {
                            int value = raw10Value(data, plane.getRowStride(), x + lane, y);
                            min = Math.min(min, value); max = Math.max(max, value);
                            sampleHash = (sampleHash ^ value) * 0x100000001b3L;
                        }
            } else {
                for (int i = 0; i + 1 < data.remaining(); i += 2054) {
                    int value = data.getShort(data.position() + i) & 65535;
                    min = Math.min(min, value); max = Math.max(max, value);
                    sampleHash = (sampleHash ^ value) * 0x100000001b3L;
                }
            }
            String stem = image.getTimestamp() + "-" + image.getWidth() + "x" + image.getHeight();
            File file = new File(getExternalFilesDir("vcf-probe"), packed10
                    ? "raw10-packed-" + stem + "-stride" + plane.getRowStride() + ".bin" : "raw-" + stem + ".bin");
            boolean saved = savedRawSizes.add(image.getFormat() + ":" + image.getWidth() + "x" + image.getHeight());
            if (saved) try (FileOutputStream stream = new FileOutputStream(file); FileChannel channel = stream.getChannel()) {
                ByteBuffer output = data.duplicate();
                while (output.hasRemaining()) channel.write(output);
            }
            if (saved && packed10) {
                File unpacked = null;
                if (getIntent().getBooleanExtra("unpack_raw10", true)) {
                    unpacked = new File(getExternalFilesDir("vcf-probe"), "raw10-unpacked16le-" + stem + ".bin");
                    writeUnpackedRaw10(data, image.getWidth(), image.getHeight(), plane.getRowStride(), unpacked);
                }
                String metadata = "{\"format\":37,\"packing\":\"Android RAW10\",\"width\":" + image.getWidth()
                        + ",\"height\":" + image.getHeight() + ",\"sourceRowStride\":" + plane.getRowStride()
                        + ",\"sourcePixelStride\":" + plane.getPixelStride() + ",\"sourceBytes\":" + data.remaining()
                        + ",\"unpackedRowStride\":" + (image.getWidth() * 2)
                        + ",\"unpackedFile\":" + (unpacked == null ? "null" : "\"" + unpacked.getName() + "\"") + "}";
                try (FileOutputStream output = new FileOutputStream(new File(file.getPath() + ".json"))) {
                    output.write(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                Log.i(TAG, "RAW10 packed file=" + file + " unpacked16le=" + unpacked);
            }
            boolean primary = reader == raw;
            boolean unique = (rawVif10() && !primary ? auxiliaryRawTimestamps : rawTimestamps).add(image.getTimestamp());
            Log.i(TAG, "RAW delivered width=" + image.getWidth() + " height=" + image.getHeight()
                    + " rowStride=" + plane.getRowStride() + " pixelStride=" + plane.getPixelStride()
                    + " format=" + image.getFormat() + " min=" + min + " max=" + max
                    + " sampleHash=" + Long.toUnsignedString(sampleHash, 16)
                    + " unique=" + unique + " file=" + (saved ? file : "sample only")
                    + " timestamp=" + image.getTimestamp() + " stream=" + (primary ? "primary" : "auxiliary")
                    + " stats=" + (packed10 ? "sampled-decoded-raw10" : "sampled-uint16le"));
            if (max > 0 && unique && (!rawVif10() || primary) && ++nonemptyRawFrames >= Math.max(1,
                    Math.min(8, getIntent().getIntExtra("raw_frames", 1))))
                handler.postDelayed(() -> stop("RAW delivered count=" + nonemptyRawFrames), 1000);
            else if (max == 0) Log.w(TAG, "empty RAW buffer; sensor data not demonstrated");
        } catch (Exception e) { stop("RAW " + e); }
    }

    private static void validateRaw10(ByteBuffer data, int width, int height, int rowStride) {
        long rowBytes = (long) width / 4 * 5;
        if (width <= 0 || height <= 0 || width % 4 != 0 || rowStride < rowBytes
                || (long) (height - 1) * rowStride + rowBytes > data.remaining())
            throw new IllegalArgumentException("invalid RAW10 plane geometry");
    }

    private static int raw10Value(ByteBuffer data, int rowStride, int x, int y) {
        int group = data.position() + y * rowStride + (x / 4) * 5;
        return ((data.get(group + x % 4) & 255) << 2) | ((data.get(group + 4) >>> (2 * (x % 4))) & 3);
    }

    private static void writeUnpackedRaw10(ByteBuffer data, int width, int height, int rowStride, File file)
            throws java.io.IOException {
        validateRaw10(data, width, height, rowStride);
        byte[] row = new byte[Math.multiplyExact(width, 2)];
        try (FileOutputStream output = new FileOutputStream(file)) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int value = raw10Value(data, rowStride, x, y);
                    row[x * 2] = (byte) value;
                    row[x * 2 + 1] = (byte) (value >>> 8);
                }
                output.write(row);
            }
        }
    }

    private void stop(String reason) {
        if (engineerConfigureRace != null) {
            stopped = true;
            engineerConfigureRace.requestStop();
            cleanupEngineerConfigureRace(reason);
            return;
        }
        if (stopped) return;
        stopped = true;
        Log.i(TAG, "stop " + reason + " previewFrames=" + previewFrames + " encoderFrames=" + encoderFrames);
        runOnUiThread(this::finish);
    }

    @Override public void onDestroy() {
        stopped = true;
        if (engineerConfigureRace != null) {
            engineerConfigureRace.requestStop();
            cleanupEngineerConfigureRace("destroyed");
            super.onDestroy();
            return;
        }
        closeProbeResources();
        super.onDestroy();
    }

    private void cleanupEngineerConfigureRace(String reason) {
        if (engineerCleanupFinished) {
            finishEngineerRaceCallbacksIfClosed();
            return;
        }
        if (!engineerCleanupStarted.compareAndSet(false, true)) return;
        if (engineerCleanupFinished) {
            engineerCleanupStarted.set(false);
            finishEngineerRaceCallbacksIfClosed();
            return;
        }
        Log.i(TAG, "engineer configure-only cleanup " + reason);
        new Thread(() -> {
            boolean deferred = false;
            try {
                closeEngineerRaceResourcesAsync();
                if (!engineerConfigureRace.awaitWorker()) {
                    deferred = true;
                    Log.e(TAG, "engineer race cleanup deferred: setter unresolved; no restoration before a possible late AppType=5");
                    return;
                }
                CountDownLatch opened;
                synchronized (engineerConfigureRace) { opened = engineerOpenFinished; }
                boolean openResolved = awaitEngineerLatch(opened, "camera open callback");
                boolean resourcesClosed = awaitEngineerLatch(engineerResourcesClosed, "camera resource close calls");
                boolean deviceClosed = awaitEngineerLatch(engineerDeviceClosed, "CameraDevice.onClosed");
                if (!openResolved || !resourcesClosed || !deviceClosed) engineerLateRestoreNeeded.set(true);
                engineerConfigureRace.restore();
                engineerCleanupFinished = true;
                Log.i(TAG, "engineer configure-only cleanup finished submissions=" + engineerConfigureRace.submitted
                        + " workerQuiescent=true cameraOpenAttempted=" + engineerOpenAttempted
                        + " openResolved=" + openResolved + " resourcesClosed=" + resourcesClosed
                        + " closeResolved=" + deviceClosed + " onClosedObserved=" + engineerOnClosedObserved
                        + "; one-way queue drain unconfirmed");
                if (!openResolved || !resourcesClosed || !deviceClosed)
                    Log.w(TAG, "engineer race callback thread retained for unresolved open/close; closure unconfirmed");
                finishEngineerRaceCallbacksIfClosed();
                runOnUiThread(this::finish);
            } finally {
                engineerCleanupStarted.set(false);
                // A setter may finish between the finite wait and this cleanup releasing its slot.
                if (deferred && engineerConfigureRace.workerQuiescent())
                    cleanupEngineerConfigureRace("deferred setter now completed");
            }
        }, "SCAMERA_ENGINEER_CONFIGURE_CLEANUP").start();
    }

    private static boolean awaitEngineerLatch(CountDownLatch pending, String stage) {
        try {
            if (pending.await(1000, TimeUnit.MILLISECONDS)) return true;
            Log.w(TAG, "engineer race " + stage + " unresolved after finite wait");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "engineer race " + stage + " wait interrupted; completion unconfirmed");
        }
        return false;
    }

    private void closeEngineerRaceResourcesAsync() {
        if (!engineerResourcesCloseStarted.compareAndSet(false, true)) return;
        Runnable close = () -> {
            try {
                for (AutoCloseable resource : new AutoCloseable[]{session, device, preview, realtimeYuv, raw})
                    if (resource != null) try { resource.close(); }
                    catch (Exception failure) { Log.e(TAG, "engineer race camera resource close failed", failure); }
            } finally {
                engineerResourcesClosed.countDown();
                finishEngineerRaceCallbacksIfClosed();
            }
        };
        if (handler == null || !handler.post(close))
            new Thread(close, "SCAMERA_ENGINEER_CAMERA_CLOSE").start();
    }

    private synchronized void finishEngineerRaceCallbacksIfClosed() {
        if (!engineerCleanupFinished || engineerResourcesClosed.getCount() != 0
                || engineerOpenFinished.getCount() != 0 || engineerDeviceClosed.getCount() != 0) return;
        if (engineerLateRestoreNeeded.getAndSet(false)) {
            if (engineerLateCleanupStarted.compareAndSet(false, true))
                new Thread(() -> {
                    engineerConfigureRace.restore();
                    Log.i(TAG, "engineer race late camera closure observed; saved values reapplied; queue drain unconfirmed");
                    finishEngineerRaceCallbackThread();
                }, "SCAMERA_ENGINEER_LATE_CLEANUP").start();
            return;
        }
        if (!engineerLateCleanupStarted.get()) finishEngineerRaceCallbackThread();
    }

    private void finishEngineerRaceCallbackThread() {
        if (!engineerCallbackThreadFinished.compareAndSet(false, true)) return;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        Log.i(TAG, "engineer race callback thread released cameraOpenAttempted=" + engineerOpenAttempted
                + " onClosedObserved=" + engineerOnClosedObserved);
    }

    private void closeProbeResources() {
        closeProbeResource(session);
        closeProbeResource(device);
        if (sdkRawSession != null) try {
            sdkRawSession.close();
            Log.i(TAG, "SDK RAW session restored");
        } catch (Exception e) { Log.e(TAG, "SDK RAW session restore failed", e); }
        closeProbeResource(preview);
        closeProbeResource(previewNavigation);
        closeProbeResource(realtimeYuv);
        closeProbeResource(videoEncoder);
        closeProbeResource(raw);
        closeProbeResource(jpeg);
        closeProbeResource(auxiliaryRaw);
        if (vivoDevice != null) try { vivoDevice.close(); } catch (Exception e) { Log.w(TAG, "Vivo API close " + e); }
        if (previousSdk != null) try {
            providerValue(40, previousSdk);
            if (ctsSdkBootstrap()) Log.i(TAG, "CTS cleanup SDK40=" + providerValue(40, null) + "; queue drain unconfirmed");
        } catch (Exception e) { Log.w(TAG, "SDK restore " + e); }
        if (previousVcfType != null) try { providerValue(33, previousVcfType); } catch (Exception e) { Log.w(TAG, "VCF type restore " + e); }
        for (java.util.Map.Entry<Integer, Integer> entry : previousProviderParams.entrySet())
            try { providerValue(entry.getKey(), entry.getValue()); } catch (Exception e) { Log.w(TAG, "provider restore " + e); }
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
    }

    private void closeProbeResource(AutoCloseable resource) {
        if (resource != null) try { resource.close(); }
        catch (Exception e) { Log.w(TAG, "Probe resource close " + e); }
    }
}
