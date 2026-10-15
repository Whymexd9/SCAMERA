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

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class VivoQcomProbeActivity extends Activity {
    private static final String TAG = "SCAMERA_VCF_PROBE";
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader preview, raw, jpeg, auxiliaryRaw, previewNavigation, realtimeYuv;
    private int previewFrames;
    private boolean captured;
    private volatile boolean stopped;
    private AutoCloseable vivoDevice;
    private VivoSdkRawSession sdkRawSession;
    private Integer previousSdk;
    private Integer previousVcfType;
    private long previewTimestamp;
    private int ringResults, nonemptyRawFrames;
    private int serialRemaining;
    private final java.util.Set<Long> rawTimestamps = new java.util.HashSet<>();
    private final java.util.Set<String> savedRawSizes = new java.util.HashSet<>();
    private final java.util.Map<Integer, Integer> previousProviderParams = new java.util.LinkedHashMap<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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

    private String cameraId() {
        String value = getIntent().getStringExtra("camera");
        return value == null ? "2" : value;
    }

    @SuppressLint("MissingPermission") private void open() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraPackageSpoof.apply(this, manager);
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
            CameraCharacteristics c = manager.getCameraCharacteristics(cameraId());
            Size[] rawSizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.RAW_SENSOR);
            Log.i(TAG, "RAW sizes=" + Arrays.toString(rawSizes) + " maxResolutionMap="
                    + c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION));
            if (rawSizes == null || rawSizes.length == 0) { stop("RAW unavailable"); return; }
            Size size = rawSizes[0];
            int width = getIntent().getIntExtra("width", size.getWidth());
            int height = getIntent().getIntExtra("height", size.getHeight());
            int maxImages = getIntent().getIntExtra("max_images", 4);
            int previewWidth = getIntent().getIntExtra("preview_width", 1440);
            int previewHeight = getIntent().getIntExtra("preview_height", 1080);
            int previewFormat = getIntent().getIntExtra("preview_format", ImageFormat.YUV_420_888);
            preview = getIntent().hasExtra("preview_buffer_usage")
                    ? ImageReader.newInstance(previewWidth, previewHeight, previewFormat, maxImages,
                            getIntent().getLongExtra("preview_buffer_usage", 3))
                    : ImageReader.newInstance(previewWidth, previewHeight, previewFormat, maxImages);
            preview.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image != null && ++previewFrames == 8) {
                        Log.i(TAG, "preview format=" + image.getFormat() + " delivered 8 frames");
                        if (!getIntent().getBooleanExtra("no_capture", false))
                            handler.postDelayed(this::capture, 500);
                    }
                } catch (RuntimeException e) { stop("preview " + e); }
            }, handler);
            raw = rawReader(width, height, maxImages);
            raw.setOnImageAvailableListener(this::saveRaw, handler);
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
            if (getIntent().getBooleanExtra("aux_raw", false)) {
                auxiliaryRaw = rawReader(size.getWidth(), size.getHeight(), maxImages);
                auxiliaryRaw.setOnImageAvailableListener(this::saveRaw, handler);
            }
            setProviderApp();
            manager.openCamera(cameraId(), new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice opened) {
                    device = opened;
                    try {
                        if (getIntent().getBooleanExtra("provider_sdk_after_open", false)) enableSdk();
                        if (getIntent().getBooleanExtra("clear_sdk_after_open", false)) {
                            providerValue(40, 0);
                            android.os.SystemClock.sleep(100);
                            Log.i(TAG, "SDK after open readback=" + providerValue(40, null));
                        }
                        configure();
                    } catch (Exception e) { stop("SDK after open " + e); }
                }
                @Override public void onDisconnected(CameraDevice opened) { opened.close(); stop("disconnected"); }
                @Override public void onError(CameraDevice opened, int error) {
                    Log.e(TAG, "onError " + error);
                    if (getIntent().getBooleanExtra("defer_error_close", false)) {
                        handler.postDelayed(() -> { opened.close(); stop("camera error " + error); }, 2000);
                    } else { opened.close(); stop("camera error " + error); }
                }
            }, handler);
        } catch (Exception e) { stop("open " + e); }
    }

    private void enableSdk() throws Exception {
        if (previousSdk == null) previousSdk = providerValue(40, null);
        providerValue(40, 1);
        android.os.SystemClock.sleep(100);
        Log.i(TAG, "provider SDK=" + providerValue(40, null));
    }

    private ImageReader rawReader(int width, int height, int maxImages) {
        if (!getIntent().hasExtra("raw_buffer_usage"))
            return ImageReader.newInstance(width, height, ImageFormat.RAW_SENSOR, maxImages);
        long usage = getIntent().getLongExtra("raw_buffer_usage", 3);
        Log.i(TAG, "RAW buffer usage=" + usage);
        return ImageReader.newInstance(width, height, ImageFormat.RAW_SENSOR, maxImages, usage);
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
            outputs.add(new OutputConfiguration(preview.getSurface()));
            if (previewNavigation != null) outputs.add(new OutputConfiguration(previewNavigation.getSurface()));
            if (realtimeYuv != null) outputs.add(new OutputConfiguration(realtimeYuv.getSurface()));
            OutputConfiguration rawOutput = new OutputConfiguration(raw.getSurface());
            outputs.add(rawOutput);
            if (getIntent().hasExtra("raw_use_case"))
                rawOutput.setStreamUseCase(getIntent().getLongExtra("raw_use_case", 0));
            Log.i(TAG, "RAW stream usecase=" + rawOutput.getStreamUseCase());
            if (jpeg != null) outputs.add(new OutputConfiguration(jpeg.getSurface()));
            if (auxiliaryRaw != null) outputs.add(new OutputConfiguration(auxiliaryRaw.getSurface()));
            String physical = getIntent().getStringExtra("physical_raw");
            if (physical != null) {
                rawOutput.setPhysicalCameraId(physical);
                if (auxiliaryRaw != null) outputs.get(outputs.size() - 1).setPhysicalCameraId(physical);
            }
            SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs,
                    command -> handler.post(command), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession ready) {
                    session = ready;
                    try {
                        if (getIntent().getBooleanExtra("clear_sdk_after_configure", false)) {
                            providerValue(40, 0);
                            android.os.SystemClock.sleep(100);
                            Log.i(TAG, "SDK after configure readback=" + providerValue(40, null)
                                    + " AppType=" + providerValue(1, null) + " VCFAppType=" + providerValue(33, null));
                        }
                        CaptureRequest.Builder builder = request(getIntent().getIntExtra(
                                "repeating_template", CameraDevice.TEMPLATE_PREVIEW));
                        if (getIntent().hasExtra("repeating_ui"))
                            set(builder, "com.vivo.current_ui_module", new int[]{getIntent().getIntExtra("repeating_ui", 1)});
                        builder.addTarget(preview.getSurface());
                        if (previewNavigation != null) builder.addTarget(previewNavigation.getSurface());
                        if (realtimeYuv != null) builder.addTarget(realtimeYuv.getSurface());
                        if (getIntent().getBooleanExtra("raw_ring", false)) builder.addTarget(raw.getSurface());
                        if (jpeg != null && getIntent().getBooleanExtra("repeating_jpeg", false))
                            builder.addTarget(jpeg.getSurface());
                        setProviderApp();
                        ready.setRepeatingRequest(builder.build(), callback, handler);
                        Log.i(TAG, "configured");
                        if (getIntent().hasExtra("capture_after_ms"))
                            handler.postDelayed(VivoQcomProbeActivity.this::capture,
                                    Math.max(100, getIntent().getIntExtra("capture_after_ms", 500)));
                    } catch (Exception e) { stop("repeat " + e); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) { stop("configure failed"); }
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
            config.setSessionParameters(parameters.build());
            setProviderApp();
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
                    if (method.getName().equals("onBufferCallback") && args != null && !stopped) {
                        saveCallbackBuffer(args);
                    }
                    if (args != null) for (Object argument : args) {
                        if (argument instanceof android.os.ParcelFileDescriptor) ((android.os.ParcelFileDescriptor) argument).close();
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
        } catch (Exception e) { Log.e(TAG, "Vivo buffer save", e); }
    }

    private CaptureRequest.Builder request(int template) throws Exception {
        CaptureRequest.Builder builder = device.createCaptureRequest(template);
        if (sdkRawSession != null && !getIntent().getBooleanExtra("sdk_client_only", false)) sdkRawSession.apply(builder,
                template == CameraDevice.TEMPLATE_STILL_CAPTURE, previewTimestamp);
        if (getIntent().hasExtra("zoom_ratio"))
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, getIntent().getFloatExtra("zoom_ratio", 1f));
        if (getIntent().hasExtra("num_hdr_exposure"))
            builder.set(new CaptureRequest.Key<>(
                    "org.codeaurora.qcamera3.sessionParameters.numHDRexposure", Integer.class),
                    getIntent().getIntExtra("num_hdr_exposure", 0));
        if (getIntent().hasExtra("dcg_mode"))
            builder.set(new CaptureRequest.Key<>(
                    "org.codeaurora.qcamera3.sessionParameters.EnableHDRDCGMode", Integer.class),
                    getIntent().getIntExtra("dcg_mode", 0));
        if (getIntent().hasExtra("capture_bit_switch") && template == getIntent().getIntExtra(
                "capture_template", CameraDevice.TEMPLATE_STILL_CAPTURE))
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
        if (getIntent().getBooleanExtra("native_raw", false)) return builder;
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
        return builder;
    }

    private static void set(CaptureRequest.Builder builder, String name, int[] value) {
        try {
            builder.set(new CaptureRequest.Key<>(name, int[].class), value);
            Log.i(TAG, name + "=" + Arrays.toString(value));
        } catch (RuntimeException e) { Log.w(TAG, name + " unavailable " + e); }
    }

    private void capture() {
        if (stopped || captured || session == null) return;
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
        CaptureRequest.Builder builder = request(getIntent().getIntExtra(
                "capture_template", CameraDevice.TEMPLATE_STILL_CAPTURE));
        if (getIntent().hasExtra("capture_ui"))
            set(builder, "com.vivo.current_ui_module", new int[]{getIntent().getIntExtra("capture_ui", 1)});
        builder.addTarget(raw.getSurface());
        if (getIntent().getBooleanExtra("capture_preview", false)) builder.addTarget(preview.getSurface());
        if (jpeg != null) builder.addTarget(jpeg.getSurface());
        if (auxiliaryRaw != null && getIntent().getBooleanExtra("aux_target", false))
            builder.addTarget(auxiliaryRaw.getSurface());
        long[] id = builder.get(new CaptureRequest.Key<>("vivo.control.globalCaptureId", long[].class));
        if (id != null) Log.i(TAG, "capture globalCaptureId=" + Arrays.toString(id));
        return builder;
    }

    private final CameraCaptureSession.CaptureCallback callback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            if (stopped) return;
            Integer intent = request.get(CaptureRequest.CONTROL_CAPTURE_INTENT);
            Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (intent != null && intent == CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW && timestamp != null)
                previewTimestamp = timestamp;
            if (!getIntent().getBooleanExtra("raw_ring", false)
                    && (intent == null || intent != CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)) return;
            if (intent != null && intent == CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
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
            if (intent != null && intent == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
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
            int min = 65535, max = 0;
            long sampleHash = 0xcbf29ce484222325L;
            for (int i = 0; i + 1 < data.remaining(); i += 2054) {
                int value = data.getShort(data.position() + i) & 65535;
                min = Math.min(min, value); max = Math.max(max, value);
                sampleHash = (sampleHash ^ value) * 0x100000001b3L;
            }
            File file = new File(getExternalFilesDir("vcf-probe"), "raw-" + image.getTimestamp() + "-" + image.getWidth() + "x" + image.getHeight() + ".bin");
            boolean saved = savedRawSizes.add(image.getWidth() + "x" + image.getHeight());
            if (saved) try (FileOutputStream stream = new FileOutputStream(file); FileChannel channel = stream.getChannel()) {
                while (data.hasRemaining()) channel.write(data);
            }
            boolean unique = rawTimestamps.add(image.getTimestamp());
            Log.i(TAG, "RAW delivered width=" + image.getWidth() + " height=" + image.getHeight()
                    + " rowStride=" + plane.getRowStride() + " pixelStride=" + plane.getPixelStride()
                    + " format=" + image.getFormat() + " min=" + min + " max=" + max
                    + " sampleHash=" + Long.toUnsignedString(sampleHash, 16)
                    + " unique=" + unique + " file=" + (saved ? file : "sample only"));
            if (max > 0 && unique && ++nonemptyRawFrames >= Math.max(1,
                    Math.min(8, getIntent().getIntExtra("raw_frames", 1))))
                handler.postDelayed(() -> stop("RAW delivered count=" + nonemptyRawFrames), 1000);
            else if (max == 0) Log.w(TAG, "empty RAW buffer; sensor data not demonstrated");
        } catch (Exception e) { stop("RAW " + e); }
    }

    private void stop(String reason) {
        if (stopped) return;
        stopped = true;
        Log.i(TAG, "stop " + reason + " previewFrames=" + previewFrames);
        runOnUiThread(this::finish);
    }

    @Override public void onDestroy() {
        stopped = true;
        if (session != null) session.close();
        if (device != null) device.close();
        if (sdkRawSession != null) try {
            sdkRawSession.close();
            Log.i(TAG, "SDK RAW session restored");
        } catch (Exception e) { Log.e(TAG, "SDK RAW session restore failed", e); }
        if (preview != null) preview.close();
        if (previewNavigation != null) previewNavigation.close();
        if (realtimeYuv != null) realtimeYuv.close();
        if (raw != null) raw.close();
        if (jpeg != null) jpeg.close();
        if (auxiliaryRaw != null) auxiliaryRaw.close();
        if (vivoDevice != null) try { vivoDevice.close(); } catch (Exception e) { Log.w(TAG, "Vivo API close " + e); }
        if (previousSdk != null) try { providerValue(40, previousSdk); } catch (Exception e) { Log.w(TAG, "SDK restore " + e); }
        if (previousVcfType != null) try { providerValue(33, previousVcfType); } catch (Exception e) { Log.w(TAG, "VCF type restore " + e); }
        for (java.util.Map.Entry<Integer, Integer> entry : previousProviderParams.entrySet())
            try { providerValue(entry.getKey(), entry.getValue()); } catch (Exception e) { Log.w(TAG, "provider restore " + e); }
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
