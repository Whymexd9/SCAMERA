package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * P80 (owner, 2026-10-10): with «Замер времени проходов GPU» (pref_scam_hybrid_profile, or scam_dev.txt "hybrid_profile 1") every
 * timing line of a shot also goes to {@code Download/SCAMERA/SCAMERA-timings.txt}: the worker's report (GPU passes, stages, F6,
 * Mochi, chroma median, timeline), the post pipeline nodes, the encoders and the shot timeline. Off: one volatile read per log line.
 * All file work runs on its own thread (as {@link ScameraDebugLog}).
 *
 * <p>P214b: one file per phone like the debug log ({@link RotatingLogFile}, 8 MB x 3): it was a MediaStore entry looked up by a
 * name MediaStore had changed (SCAMERA-timings.log -> .log.txt), so processes made "SCAMERA-timings.log (1).txt", (2)... Every
 * open writes a line with the build and the file's real path.
 */
public final class ScameraTimingLog {
    static final String STEM = "SCAMERA-timings";
    private static final Object LOCK = new Object();
    private static final ThreadLocal<SimpleDateFormat> TS =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));
    private static volatile boolean enabled;
    private static Context appContext;
    private static HandlerThread thread;
    private static Handler handler;
    private static final RotatingLogFile FILE = new RotatingLogFile(STEM, ".txt", RotatingLogFile.CAP_BYTES,
            RotatingLogFile.GENERATIONS, ScameraFiles::dir, () -> RotatingLogFile.installStem(appContext, STEM),
            (file, rotated) -> {
                ScameraFiles.index(appContext, file);
                return "--- " + TS.get().format(new Date()) + (rotated ? " timings continued after rotation: " : " timings: ")
                        + BuildIdentity.line() + ", file " + file.getAbsolutePath() + " ---\n";
            });

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
            if (appContext == null) return;
            if (FILE.write(text)) FILE.flush();
        }
    }

    private static void flushAndClose() {
        synchronized (LOCK) { FILE.close(); }
    }
}
