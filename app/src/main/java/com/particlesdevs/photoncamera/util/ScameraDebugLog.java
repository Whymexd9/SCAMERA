package com.particlesdevs.photoncamera.util;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Size;

import androidx.preference.PreferenceManager;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Full debug" diagnostics sink for SCAMERA.
 *
 * <p>Writes one plain-text file to {@code /storage/emulated/0/Download/SCAMERA/SCAMERA-debug.log}.
 * Everything happens on a dedicated {@link HandlerThread}: callers never touch the file system, so
 * this is safe to call from the capture and processing paths.
 *
 * <p>The whole class is inert unless the {@link #PREF_KEY} switch is on. When it is off,
 * {@link #isEnabled()} short-circuits before any string is built, so there is no cost on the
 * capture path and no file is created.
 */
public final class ScameraDebugLog {
    public static final String PREF_KEY = "scamera_full_debug";

    private static final String DIR_NAME = "SCAMERA";
    private static final String FILE_NAME = "SCAMERA-debug.log";
    private static final String MIME = "text/plain";
    private static final int FLUSH_INTERVAL_MS = 1000;

    private static final ThreadLocal<SimpleDateFormat> TS =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));

    private static final Object LOCK = new Object();

    private static volatile boolean enabled = false;
    private static Context appContext;
    private static HandlerThread thread;
    private static Handler handler;
    private static BufferedWriter writer;
    private static Uri mediaStoreUri;
    private static long sessionStartNs;

    private ScameraDebugLog() {}

    // ---------------------------------------------------------------- lifecycle

    /**
     * Reads the switch and, when it is on, opens the log and writes the session header.
     * Safe to call repeatedly; only the first enabling call opens the file.
     */
    public static void init(Context context) {
        attach(context);
        if (appContext == null) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appContext);
        setEnabled(prefs.getBoolean(PREF_KEY, false));
    }

    /** Bind the context without touching the enabled state. */
    public static void attach(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
    }

    public static void setEnabled(boolean value) {
        if (value == enabled) return;
        enabled = value;
        if (value) {
            startThread();
            sessionStartNs = System.nanoTime();
            handler.post(ScameraDebugLog::writeSessionHeader);
            handler.post(ScameraDebugLog::writeDeviceSection);
            handler.post(ScameraDebugLog::writeCameraSection);
            installCrashHandler();
            schedulePeriodicFlush();
        } else {
            if (handler != null) {
                handler.post(() -> {
                    rawWrite("--- full debug disabled ---\n");
                    closeWriter();
                });
            }
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private static void startThread() {
        synchronized (LOCK) {
            if (thread == null || !thread.isAlive()) {
                thread = new HandlerThread("ScameraDebugLog");
                thread.start();
                handler = new Handler(thread.getLooper());
            }
        }
    }

    private static void schedulePeriodicFlush() {
        if (handler == null) return;
        handler.postDelayed(() -> {
            flush();
            if (enabled) schedulePeriodicFlush();
        }, FLUSH_INTERVAL_MS);
    }

    // ---------------------------------------------------------------- public API

    /** Free-form line. {@code stage} groups related lines, e.g. "align" or "dng". */
    public static void log(String stage, String message) {
        if (!enabled) return;
        post(stage, message);
    }

    /** Line with a duration in milliseconds, for stage timing. */
    public static void timing(String stage, String what, long startNs) {
        if (!enabled) return;
        long ms = (System.nanoTime() - startNs) / 1_000_000L;
        post(stage, what + " took " + ms + " ms");
    }

    /** Error with an optional throwable; the stack trace is written in full. */
    public static void error(String stage, String message, Throwable tr) {
        if (!enabled) return;
        StringBuilder sb = new StringBuilder(message);
        if (tr != null) {
            StringWriter sw = new StringWriter();
            tr.printStackTrace(new PrintWriter(sw));
            sb.append('\n').append(sw);
        }
        post(stage, "ERROR " + sb);
    }

    /**
     * Mirror of {@link Log}. Called for every Log.d/w/e/i/v so that processing stages,
     * alignment results and GPU/JNI failures already logged by the pipeline land here too.
     */
    static void mirror(String level, String tag, String message) {
        if (!enabled) return;
        post(level + "/" + tag, message);
    }

    /** One line per captured frame. Null values are printed as "n/a". */
    public static void frame(int index, CaptureResult result) {
        if (!enabled || result == null) return;
        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
        Float focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        Float aperture = result.get(CaptureResult.LENS_APERTURE);
        post("frame", "#" + index
                + " iso=" + (iso == null ? "n/a" : iso)
                + " exposure=" + (exp == null ? "n/a" : (exp / 1_000_000.0) + "ms")
                + " timestamp=" + (ts == null ? "n/a" : ts)
                + " focusDistance=" + (focus == null ? "n/a" : focus)
                + " aperture=" + (aperture == null ? "n/a" : aperture));
    }

    /** Current heap usage; call around heavy stages. */
    public static void memory(String stage) {
        if (!enabled) return;
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        StringBuilder sb = new StringBuilder("heap used=" + usedMb + "MB max=" + maxMb + "MB");
        if (appContext != null) {
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                sb.append(" systemAvail=").append(mi.availMem / (1024 * 1024)).append("MB")
                        .append(" lowMemory=").append(mi.lowMemory);
            }
        }
        post(stage, sb.toString());
    }

    public static void flush() {
        if (handler == null) return;
        handler.post(() -> {
            synchronized (LOCK) {
                if (writer != null) {
                    try {
                        writer.flush();
                    } catch (Exception ignored) {
                        closeWriter();
                    }
                }
            }
        });
    }

    /** Absolute path of the log, for showing in the UI. */
    public static String getPath() {
        return Environment.getExternalStorageDirectory()
                + "/" + Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/" + FILE_NAME;
    }

    // ---------------------------------------------------------------- sections

    private static void writeSessionHeader() {
        rawWrite("\n================ SCAMERA full debug session ================\n");
        rawWrite("started: " + TS.get().format(new Date()) + "\n");
        rawWrite("log path: " + getPath() + "\n");
    }

    private static void writeDeviceSection() {
        StringBuilder sb = new StringBuilder("--- build & device ---\n");
        try {
            android.content.pm.PackageInfo pi = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? pi.getLongVersionCode() : pi.versionCode;
            sb.append("app: ").append(pi.versionName).append(" (").append(code).append(")\n");
        } catch (Exception e) {
            sb.append("app: version unavailable (").append(e).append(")\n");
        }
        sb.append("package: ").append(appContext.getPackageName()).append('\n');
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("board/hardware: ").append(Build.BOARD).append(" / ").append(Build.HARDWARE).append('\n');
        sb.append("android: ").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT)
                .append(" build=").append(Build.DISPLAY).append('\n');
        sb.append("abis: ").append(String.join(", ", Build.SUPPORTED_ABIS)).append('\n');
        sb.append("cpus: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        rawWrite(sb.toString());
    }

    private static void writeCameraSection() {
        StringBuilder sb = new StringBuilder("--- cameras ---\n");
        try {
            CameraManager cm = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                rawWrite("--- cameras --- unavailable (no CameraManager)\n");
                return;
            }
            for (String id : cm.getCameraIdList()) {
                sb.append(describeCamera(cm, id));
            }
        } catch (Throwable t) {
            sb.append("enumeration failed: ").append(t).append('\n');
        }
        rawWrite(sb.toString());
    }

    private static String describeCamera(CameraManager cm, String id) {
        StringBuilder sb = new StringBuilder("camera ").append(id).append(":\n");
        try {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);

            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            sb.append("  facing: ").append(facing == null ? "n/a"
                    : facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : "back").append('\n');

            float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            float[] apertures = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
            sb.append("  focal lengths: ").append(join(focal)).append(" mm\n");
            sb.append("  apertures: f/").append(join(apertures)).append('\n');

            Size sensorSize = null;
            Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active != null) {
                sb.append("  active array: ").append(active.width()).append('x').append(active.height()).append('\n');
            }
            Size pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            if (pixelArray != null) {
                sb.append("  pixel array: ").append(pixelArray.getWidth()).append('x')
                        .append(pixelArray.getHeight()).append('\n');
            }

            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] raw = map.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR);
                if (raw != null && raw.length > 0) {
                    List<String> sizes = new ArrayList<>();
                    for (Size s : raw) sizes.add(s.getWidth() + "x" + s.getHeight());
                    sb.append("  RAW_SENSOR sizes: ").append(String.join(", ", sizes)).append('\n');
                    sensorSize = raw[0];
                } else {
                    sb.append("  RAW_SENSOR sizes: none\n");
                }
            }
            if (sensorSize == null) sb.append("  RAW: not supported on this id\n");

            Integer cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
            sb.append("  CFA: ").append(cfaName(cfa)).append('\n');

            Integer whiteLevel = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            sb.append("  white level: ").append(whiteLevel == null ? "n/a" : whiteLevel).append('\n');

            android.hardware.camera2.params.BlackLevelPattern blp =
                    c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
            if (blp != null) {
                int[] pattern = new int[4];
                blp.copyTo(pattern, 0);
                sb.append("  black level: ").append(pattern[0]).append(' ').append(pattern[1])
                        .append(' ').append(pattern[2]).append(' ').append(pattern[3]).append('\n');
            } else {
                sb.append("  black level: n/a\n");
            }

            android.util.Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            android.util.Range<Long> exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            sb.append("  iso range: ").append(iso == null ? "n/a" : iso).append('\n');
            sb.append("  exposure range: ").append(exp == null ? "n/a" : exp + " ns").append('\n');

            int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (caps != null) {
                List<String> names = new ArrayList<>();
                for (int cap : caps) names.add(capabilityName(cap));
                sb.append("  capabilities: ").append(String.join(", ", names)).append('\n');
            }

            Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            sb.append("  hardware level: ").append(hardwareLevelName(level)).append('\n');
        } catch (Throwable t) {
            sb.append("  characteristics failed: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static String join(float[] values) {
        if (values == null || values.length == 0) return "n/a";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static String cfaName(Integer cfa) {
        if (cfa == null) return "n/a";
        switch (cfa) {
            case CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB: return "RGGB";
            case CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG: return "GRBG";
            case CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG: return "GBRG";
            case CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR: return "BGGR";
            case CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB: return "RGB";
            default: return "unknown(" + cfa + ")";
        }
    }

    private static String hardwareLevelName(Integer level) {
        if (level == null) return "n/a";
        switch (level) {
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
            default: return "unknown(" + level + ")";
        }
    }

    private static String capabilityName(int cap) {
        switch (cap) {
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW: return "RAW";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR: return "MANUAL_SENSOR";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING: return "MANUAL_POST";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE: return "BURST";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA: return "LOGICAL_MULTI";
            default: return "cap" + cap;
        }
    }

    // ---------------------------------------------------------------- crash handler

    private static boolean crashHandlerInstalled = false;

    private static void installCrashHandler() {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                // Synchronous: the process is about to die, a posted task would never run.
                rawWrite(TS.get().format(new Date()) + " FATAL on thread " + t.getName() + "\n" + sw + "\n");
                synchronized (LOCK) {
                    if (writer != null) writer.flush();
                }
            } catch (Throwable ignored) {
                // Never mask the original crash.
            }
            if (previous != null) previous.uncaughtException(t, e);
        });
    }

    // ---------------------------------------------------------------- writing

    private static void post(String stage, String message) {
        startThread();
        final String time = TS.get().format(new Date());
        final long sinceStartMs = (System.nanoTime() - sessionStartNs) / 1_000_000L;
        handler.post(() -> rawWrite(time + " [+" + sinceStartMs + "ms] " + stage + ": " + message + "\n"));
    }

    /** Must run on the log thread, except from the crash handler where the process is dying. */
    private static void rawWrite(String text) {
        synchronized (LOCK) {
            try {
                if (writer == null && !openWriter()) return;
                writer.write(text);
            } catch (Exception e) {
                closeWriter();
            }
        }
    }

    /**
     * Opens the log for appending. Below API 29 a plain file works; from API 29 the public
     * Download tree is managed by MediaStore, so the entry is created (or reused) there and
     * opened in append mode.
     */
    private static boolean openWriter() {
        if (appContext == null) return false;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        DIR_NAME);
                if (!dir.exists() && !dir.mkdirs()) return false;
                writer = new BufferedWriter(new FileWriter(new File(dir, FILE_NAME), true), 8192);
                return true;
            }

            ContentResolver resolver = appContext.getContentResolver();
            if (mediaStoreUri == null) mediaStoreUri = findExisting(resolver);
            if (mediaStoreUri == null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
                values.put(MediaStore.Downloads.MIME_TYPE, MIME);
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME);
                mediaStoreUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            }
            if (mediaStoreUri == null) return false;
            OutputStream os = resolver.openOutputStream(mediaStoreUri, "wa");
            if (os == null) return false;
            writer = new BufferedWriter(new OutputStreamWriter(os), 8192);
            android.util.Log.i("ScameraDebugLog", "opened " + getPath());
            return true;
        } catch (Exception e) {
            android.util.Log.e("ScameraDebugLog", "cannot open " + getPath(), e);
            writer = null;
            return false;
        }
    }

    private static Uri findExisting(ContentResolver resolver) {
        String selection = MediaStore.Downloads.RELATIVE_PATH + "=? AND "
                + MediaStore.Downloads.DISPLAY_NAME + "=?";
        String[] args = {Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/", FILE_NAME};
        try (android.database.Cursor cursor = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Downloads._ID}, selection, args, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
            }
        } catch (Exception ignored) {
            // Fall through and create a new entry.
        }
        return null;
    }

    private static void closeWriter() {
        synchronized (LOCK) {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (Exception ignored) {
                    // Nothing useful to do.
                }
                writer = null;
            }
        }
    }
}
