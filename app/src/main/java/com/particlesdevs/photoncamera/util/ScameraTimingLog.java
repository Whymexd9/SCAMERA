package com.particlesdevs.photoncamera.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * P80 (owner, 2026-10-10): with «Замер времени проходов GPU» (pref_scam_hybrid_profile, or scam_dev.txt "hybrid_profile 1") every
 * timing line of a shot also goes to {@code Download/SCAMERA/SCAMERA-timings.log}: the worker's report (GPU passes, stages, F6,
 * Mochi, chroma median, timeline), the post pipeline nodes, the encoders and the shot timeline. Off: one volatile read per log line.
 * All file work runs on its own thread (as {@link ScameraDebugLog}).
 */
public final class ScameraTimingLog {
    private static final String DIR_NAME = "SCAMERA";
    static final String FILE_NAME = "SCAMERA-timings.log";
    private static final Object LOCK = new Object();
    private static final ThreadLocal<SimpleDateFormat> TS =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));
    private static volatile boolean enabled;
    private static Context appContext;
    private static HandlerThread thread;
    private static Handler handler;
    private static BufferedWriter writer;
    private static Uri mediaStoreUri;

    private ScameraTimingLog() {}

    /** Called when a shot starts processing: the switch decides for this shot; on, a header line opens its block. */
    public static void shot(Context context, boolean on, String header) {
        if (context != null && appContext == null) appContext = context.getApplicationContext();
        enabled = on;
        if (!on) { if (handler != null) handler.post(ScameraTimingLog::flushAndClose); return; }
        start();
        final String line = "\n=== " + TS.get().format(new Date()) + " " + header + " ===\n";
        handler.post(() -> write(line));
    }

    /** A line is a timing line: it names milliseconds or a timeline. */
    static boolean isTiming(String message) {
        if (message == null) return false;
        return message.contains(" ms") || message.contains("ms=") || message.contains("ms:") || message.contains("elapsed")
                || message.contains("TIMELINE") || message.contains("timing");
    }

    /** From {@link Log}: every line while on; the timing lines are kept. */
    static void mirror(String level, String tag, String message) {
        if (!enabled || !isTiming(message)) return;
        final String line = TS.get().format(new Date()) + " " + level + "/" + tag + ": " + message + "\n";
        final Handler h = handler;
        if (h != null) h.post(() -> write(line));
    }

    private static void start() {
        synchronized (LOCK) {
            if (thread == null || !thread.isAlive()) {
                thread = new HandlerThread("ScameraTimingLog");
                thread.start();
                handler = new Handler(thread.getLooper());
            }
        }
    }

    private static void write(String text) {
        synchronized (LOCK) {
            try {
                if (writer == null && !open()) return;
                writer.write(text);
                writer.flush();
            } catch (Exception e) {
                close();
            }
        }
    }

    private static void flushAndClose() {
        synchronized (LOCK) { close(); }
    }

    private static void close() {
        if (writer == null) return;
        try { writer.flush(); writer.close(); } catch (Exception ignored) {}
        writer = null;
    }

    /** As {@link ScameraDebugLog}: a plain file below API 29, a MediaStore Downloads entry (append) from API 29. */
    private static boolean open() {
        if (appContext == null) return false;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
                if (!dir.exists() && !dir.mkdirs()) return false;
                writer = new BufferedWriter(new FileWriter(new File(dir, FILE_NAME), true), 8192);
                return true;
            }
            ContentResolver resolver = appContext.getContentResolver();
            if (mediaStoreUri == null) mediaStoreUri = findExisting(resolver);
            if (mediaStoreUri == null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME);
                mediaStoreUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            }
            if (mediaStoreUri == null) return false;
            OutputStream os = resolver.openOutputStream(mediaStoreUri, "wa");
            if (os == null) { mediaStoreUri = null; return false; }
            writer = new BufferedWriter(new OutputStreamWriter(os), 8192);
            return true;
        } catch (Exception e) {
            android.util.Log.e("ScameraTimingLog", "cannot open Download/" + DIR_NAME + "/" + FILE_NAME, e);
            writer = null;
            mediaStoreUri = null; // a deleted entry: created again next time
            return false;
        }
    }

    private static Uri findExisting(ContentResolver resolver) {
        String selection = MediaStore.Downloads.RELATIVE_PATH + "=? AND " + MediaStore.Downloads.DISPLAY_NAME + "=?";
        String[] args = {Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME + "/", FILE_NAME};
        try (android.database.Cursor cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Downloads._ID}, selection, args, null)) {
            if (cursor != null && cursor.moveToFirst())
                return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0));
        } catch (Exception ignored) {}
        return null;
    }
}
