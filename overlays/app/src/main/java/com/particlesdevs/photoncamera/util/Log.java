package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;

import androidx.documentfile.provider.DocumentFile;

import com.anggrayudi.storage.file.DocumentFileCompat;
import com.anggrayudi.storage.file.DocumentFileType;
import com.anggrayudi.storage.file.DocumentFileUtils;
import com.anggrayudi.storage.file.StorageId;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class Log {
    private static final String DEBUG_FOLDER = "Download/SCAMERA/";
    private static final String DEBUG_FILE = "SCAMERA-debug.txt";

    private static java.io.File logDir = null;
    private static Context logContext = null; // Application context for SimpleStorage
    private static final int LOG_RETENTION_DAYS = 10;
    private static String currentLogFileName = null;
    private static boolean logEnabled = true;
    private static Uri mediaStoreLogUri;
    private static UncaughtExceptionHandler previousCrashHandler;
    private static boolean sessionHeaderWritten;
    private static boolean logcatStarted;

    // Thread-safe date formatters
    private static final ThreadLocal<SimpleDateFormat> dateFormatter =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> timeFormatter =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));

    // Async logging
    private static HandlerThread logThread;
    private static Handler logHandler;
    private static BufferedWriter bufferedWriter = null;
    private static String currentDate = null;
    private static final int BUFFER_FLUSH_INTERVAL = 1000; // Flush every 1 second

    static {
        initLogThread();
    }

    private static void initLogThread() {
        logThread = new HandlerThread("LogWriterThread");
        logThread.start();
        logHandler = new Handler(logThread.getLooper());

        // Schedule periodic flush
        schedulePeriodicFlush();
    }

    private static void schedulePeriodicFlush() {
        logHandler.postDelayed(() -> {
            flushBuffer();
            schedulePeriodicFlush();
        }, BUFFER_FLUSH_INTERVAL);
    }

    /**
     * Use SimpleStorage to write logs to DCIM/PhotonCamera/PhotonLog.
     * Call this when the app has SAF storage access (e.g. from SplashActivity).
     */
    public static void setLogFolder(Context context) {
        if (context != null) {
            logContext = context.getApplicationContext();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                java.io.File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                logDir = new java.io.File(downloads, "SCAMERA");
                //noinspection ResultOfMethodCallIgnored
                logDir.mkdirs();
            } else {
                logDir = null;
            }
            logHandler.post(() -> {
                writeSessionHeader();
                startLogcatCapture();
            });
        } else {
            logContext = null;
            closeWriter();
        }
    }

    /** Installs a crash recorder while preserving Android's original crash handling. */
    public static synchronized void installCrashHandler() {
        if (previousCrashHandler != null) return;
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            e("FATAL", "Uncaught exception on thread " + thread.getName(), throwable);
            flushSync();
            if (previousCrashHandler != null) {
                previousCrashHandler.uncaughtException(thread, throwable);
            }
        });
    }

    /** Writes device/build/runtime details once per application process. */
    public static void writeSystemInfo(Context context) {
        if (context == null) return;
        Runtime runtime = Runtime.getRuntime();
        String packageInfo = "unknown";
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            packageInfo = info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Exception ignored) {
        }
        i("Debug", "Package=" + context.getPackageName() + " version=" + packageInfo);
        i("Debug", "Device=" + Build.MANUFACTURER + "/" + Build.BRAND + "/" + Build.MODEL
                + " product=" + Build.PRODUCT + " device=" + Build.DEVICE);
        i("Debug", "Android=" + Build.VERSION.RELEASE + " SDK=" + Build.VERSION.SDK_INT
                + " fingerprint=" + Build.FINGERPRINT);
        i("Debug", "ABI=" + Arrays.toString(Build.SUPPORTED_ABIS)
                + " processors=" + runtime.availableProcessors());
        i("Debug", "Heap max=" + runtime.maxMemory() + " total=" + runtime.totalMemory()
                + " free=" + runtime.freeMemory());
        i("Debug", "Locale=" + Locale.getDefault() + " timezone=" + TimeZone.getDefault().getID());
        i("Debug", "LogFile=/Download/SCAMERA/" + DEBUG_FILE);
    }

    /** Captures Java, framework and native messages emitted by this process. */
    private static synchronized void startLogcatCapture() {
        if (logcatStarted) return;
        logcatStarted = true;
        Thread thread = new Thread(() -> {
            try {
                Process process = new ProcessBuilder("logcat", "--pid=" + android.os.Process.myPid(),
                        "-v", "threadtime", "*:V").redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writeDirect("LOGCAT " + line + "\n");
                    }
                }
            } catch (Exception exception) {
                writeDirect("LOGCAT_CAPTURE_FAILED " + exception + "\n");
            }
        }, "SCAMERA-Logcat");
        thread.setDaemon(true);
        thread.start();
    }

    /** @deprecated Prefer {@link #setLogFolder(Context)} with SimpleStorage. Kept for fallback. */
    @Deprecated
    public static void setLogFile(java.io.File folder) {
        if (folder != null && folder.isDirectory()) {
            logDir = folder;
            logContext = null;
            logHandler.post(() -> cleanupOldLogs());
        } else {
            logDir = null;
            closeWriter();
        }
    }

    /** Returns PhotonCamera/PhotonLog folder via SimpleStorage, or null if no access. */
    private static DocumentFile getLogFolderDocumentFile() {
        if (logContext == null || !SimpleStorageHelper.hasStorageAccess(logContext)) {
            return null;
        }
        DocumentFile photonCamera = DocumentFileCompat.fromSimplePath(
                logContext,
                StorageId.PRIMARY,
                SimpleStorageHelper.PHOTON_CAMERA_RELATIVE_PATH,
                DocumentFileType.FOLDER,
                true);
        if (photonCamera == null || !photonCamera.exists()) {
            return null;
        }
        DocumentFile logFolder = photonCamera.findFile(PHOTON_LOG_SUBFOLDER);
        if (logFolder != null && logFolder.exists()) {
            return logFolder;
        }
        logFolder = photonCamera.createDirectory(PHOTON_LOG_SUBFOLDER);
        return logFolder;
    }

    private static DocumentFile getLogFileDocumentFile() {
        DocumentFile folder = getLogFolderDocumentFile();
        if (folder == null || !folder.isDirectory()) return null;
        String today = dateFormatter.get().format(new java.util.Date());
        if (currentDate == null || !currentDate.equals(today)) {
            currentDate = today;
            currentLogFileName = "log-" + today + ".txt";
            closeWriter();
        }
        DocumentFile file = folder.findFile(currentLogFileName);
        if (file != null && file.exists()) {
            return file;
        }
        file = folder.createFile("text/plain", currentLogFileName);
        return file;
    }

    private static java.io.File getLogFile() {
        if (logDir == null) return null;
        return new java.io.File(logDir, DEBUG_FILE);
    }

    private static Uri getMediaStoreLogUri() {
        if (logContext == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        if (mediaStoreLogUri != null) return mediaStoreLogUri;
        ContentResolver resolver = logContext.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        try (Cursor cursor = resolver.query(collection,
                new String[]{MediaStore.MediaColumns._ID}, selection,
                new String[]{DEBUG_FILE, DEBUG_FOLDER}, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                mediaStoreLogUri = Uri.withAppendedPath(collection, cursor.getString(0));
                return mediaStoreLogUri;
            }
        } catch (Exception exception) {
            android.util.Log.e("SCAMERA-Debug", "Cannot query debug file", exception);
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, DEBUG_FILE);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, DEBUG_FOLDER);
        try {
            mediaStoreLogUri = resolver.insert(collection, values);
        } catch (Exception exception) {
            android.util.Log.e("SCAMERA-Debug", "Cannot create debug file", exception);
        }
        return mediaStoreLogUri;
    }

    private static void cleanupOldLogs() {
        if (logContext != null) {
            DocumentFile folder = getLogFolderDocumentFile();
            if (folder != null && folder.isDirectory()) {
                DocumentFile[] files = folder.listFiles();
                if (files != null) {
                    long now = System.currentTimeMillis();
                    long retentionMillis = LOG_RETENTION_DAYS * 24L * 60L * 60L * 1000L;
                    for (DocumentFile file : files) {
                        if (file != null && file.isFile()) {
                            String name = file.getName();
                            if (name != null && name.startsWith("log-") && name.endsWith(".txt")) {
                                long lastModified = file.lastModified();
                                if (lastModified > 0 && now - lastModified > retentionMillis) {
                                    file.delete();
                                }
                            }
                        }
                    }
                }
            }
            return;
        }
        if (logDir == null) return;
        java.io.File[] files = logDir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        long retentionMillis = LOG_RETENTION_DAYS * 24L * 60L * 60L * 1000L;
        for (java.io.File file : files) {
            if (file.isFile() && file.getName().startsWith("log-") && file.getName().endsWith(".txt")) {
                long lastModified = file.lastModified();
                if (now - lastModified > retentionMillis) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
    }

    private static void closeWriter() {
        if (bufferedWriter != null) {
            try {
                bufferedWriter.close();
            } catch (Exception e) {
                // Ignore
            }
            bufferedWriter = null;
        }
    }

    private static void flushBuffer() {
        if (bufferedWriter != null) {
            try {
                bufferedWriter.flush();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private static void flushSync() {
        if (logHandler == null) return;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        logHandler.post(() -> {
            flushBuffer();
            latch.countDown();
        });
        try {
            latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeSessionHeader() {
        if (sessionHeaderWritten) return;
        sessionHeaderWritten = true;
        writeDirect("\n============================================================\n"
                + "SCAMERA DEBUG SESSION START " + timeFormatter.get().format(new Date()) + "\n"
                + "============================================================\n");
    }

    private static void writeDirect(String text) {
        try {
            if (logContext != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Uri uri = getMediaStoreLogUri();
                if (uri == null) return;
                java.io.OutputStream stream = logContext.getContentResolver().openOutputStream(uri, "wa");
                if (stream == null) return;
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream), 8192)) {
                    writer.write(text);
                    writer.flush();
                }
                return;
            }
            java.io.File file = getLogFile();
            if (file == null) return;
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true), 8192)) {
                writer.write(text);
                writer.flush();
            }
        } catch (Exception exception) {
            mediaStoreLogUri = null;
            android.util.Log.e("SCAMERA-Debug", "Cannot write debug file", exception);
        }
    }

    private static void writeToFile(String level, String tag, String message) {
        if (!logEnabled) return;
        if (logContext == null && logDir == null) return;

        long timestamp = System.currentTimeMillis();

        logHandler.post(() -> {
            String time = timeFormatter.get().format(new java.util.Date(timestamp));
            String thread = Thread.currentThread().getName();
            writeDirect(time + " " + level + "/" + tag + " [" + thread + "]: " + message + "\n");
        });
    }

    public static void d(String tag, String message) {
        if(!logEnabled) return;
        android.util.Log.d(tag, message);
        writeToFile("D", tag, message);
    }

    public static void w(String tag, String message) {
        if(!logEnabled) return;
        android.util.Log.w(tag, message);
        writeToFile("W", tag, message);
    }
    
    public static void w(String tag, String message, Throwable tr) {
        if(!logEnabled) return;
        android.util.Log.w(tag, message, tr);
        writeToFile("W", tag, message + "\n" + android.util.Log.getStackTraceString(tr));
    }

    public static void e(String tag, String message) {
        if(!logEnabled) return;
        android.util.Log.e(tag, message);
        writeToFile("E", tag, message);
    }
    
    public static void e(String tag, String message, Throwable tr) {
        if(!logEnabled) return;
        android.util.Log.e(tag, message, tr);
        writeToFile("E", tag, message + "\n" + android.util.Log.getStackTraceString(tr));
    }

    public static void i(String tag, String message) {
        if(!logEnabled) return;
        android.util.Log.i(tag, message);
        writeToFile("I", tag, message);
    }

    public static void v(String tag, String s) {
        if(!logEnabled) return;
        android.util.Log.v(tag, s);
        writeToFile("V", tag, s);
    }

    public static String getStackTraceString(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.toString()).append("\n");
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append("\tat ").append(element).append("\n");
        }
        String stackTrace = sb.toString();
        e.printStackTrace();
        writeToFile("E", "Exception", stackTrace);
        return stackTrace;
    }
    
    public static void setLogEnabled(boolean enabled) {
        logEnabled = enabled;
    }
    
    // Cleanup method to call when app is closing
    public static void shutdown() {
        if (logHandler != null) {
            logHandler.post(() -> {
                flushBuffer();
                closeWriter();
            });
            logThread.quitSafely();
        }
    }
}
