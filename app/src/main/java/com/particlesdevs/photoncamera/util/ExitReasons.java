package com.particlesdevs.photoncamera.util;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * Why the previous process of the app ended, logged once at the next start (owner's Pixel 10 Pro XL log, 2026-10-12: the app
 * restarted 2-14 s after switching modules with no Java exception in its log and no crash file; a native crash or a kill by
 * the system leaves nothing the app writes itself). Android 11+: ActivityManager's exit records of this package (reason,
 * status / signal, importance, PSS / RSS, description); for a native crash (Android 12+) the first lines of the tombstone
 * trace. Each record is logged once (its timestamp is remembered).
 */
public final class ExitReasons {
    private static final String TAG = "ExitReasons";
    private static final String PREFS = "scam_exit_reasons";
    private static final int TRACE_BYTES = 6000;

    private ExitReasons() {}

    public static void logPrevious(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        new Thread(() -> {
            try {
                Thread.sleep(4000); // the log file is attached by the first activity (Log.setLogFolder)
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am == null) return;
                List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 5);
                SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                long seen = p.getLong("last", 0);
                long newest = seen;
                for (ApplicationExitInfo e : list) {
                    if (e.getTimestamp() <= seen) continue;
                    newest = Math.max(newest, e.getTimestamp());
                    String line = String.format(Locale.ROOT,
                            "previous process %s (pid %d) ended %tF %<tT: reason %s, status %d, importance %d, pss %d MB, rss %d MB, %s",
                            e.getProcessName(), e.getPid(), e.getTimestamp(), reason(e.getReason()), e.getStatus(),
                            e.getImportance(), e.getPss() / 1024, e.getRss() / 1024, e.getDescription());
                    Log.w(TAG, line);
                    if (e.getReason() == ApplicationExitInfo.REASON_CRASH) {
                        // the Java stack is in ScameraCrashReporter's file only: its last report into this log
                        String report = lastCrashReport(context);
                        if (!report.isEmpty()) Log.w(TAG, "last crash report:\n" + report);
                    }
                    if (e.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE || e.getReason() == ApplicationExitInfo.REASON_ANR) {
                        String trace = trace(e);
                        if (!trace.isEmpty()) Log.w(TAG, "trace of pid " + e.getPid() + ":\n" + trace);
                    }
                }
                if (newest > seen) p.edit().putLong("last", newest).apply();
            } catch (Throwable t) {
                Log.w(TAG, "exit reasons not read: " + t);
            }
        }, "ExitReasons").start();
    }

    /**
     * The last report of SCAMERA-crash.log (at most 8 KB of it): P191 Download/SCAMERA, this install's own file beside it
     * (written when the Download file is an earlier install's, which the File API cannot open: audit 2026-10-27, lab-app-5),
     * and the package's external files, where ScameraCrashReporter writes only when both failed. The newest file that can be
     * read gives the report; "crash file not read" only when none of them can.
     */
    static String lastCrashReport(Context context) {
        final java.util.List<java.io.File> files = new java.util.ArrayList<>();
        try {
            final String stem = RotatingLogFile.installStem(context, ScameraCrashReporter.STEM);
            if (stem != null) files.add(ScameraFiles.file(stem + ScameraCrashReporter.EXTENSION));
            files.add(ScameraFiles.file("SCAMERA-crash.log"));
            final java.io.File legacy = ScameraFiles.legacy(context, "SCAMERA-crash.log");
            if (legacy != null) files.add(legacy);
        } catch (Throwable t) {
            return "crash file not read: " + t;
        }
        return lastCrashReport(files);
    }

    /** The report of the newest of {@code files} that reads ("" when none exists). */
    static String lastCrashReport(java.util.List<java.io.File> files) {
        final java.util.List<java.io.File> found = new java.util.ArrayList<>();
        for (java.io.File f : files) if (f != null && f.isFile()) found.add(f);
        found.sort((x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        Throwable failure = null;
        for (java.io.File f : found) {
            try {
                long len = f.length(), from = Math.max(0, len - 8192);
                byte[] buf = new byte[(int) (len - from)];
                try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
                    r.seek(from);
                    r.readFully(buf);
                }
                String text = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
                int at = text.lastIndexOf("SCAMERA crash report");
                return at >= 0 ? text.substring(at).trim() : text.trim();
            } catch (Throwable t) {
                if (failure == null) failure = t;
            }
        }
        return failure == null ? "" : "crash file not read: " + failure;
    }

    private static String trace(ApplicationExitInfo e) {
        try (InputStream in = e.getTraceInputStream()) {
            if (in == null) return "";
            byte[] buf = new byte[TRACE_BYTES];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            // a native crash's trace is a tombstone protobuf (Android 12+): keep the printable runs (frames, names, offsets)
            StringBuilder out = new StringBuilder();
            StringBuilder run = new StringBuilder();
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xFF);
                if (c >= 32 && c < 127) run.append(c);
                else {
                    if (run.length() >= 6) out.append(run).append('\n');
                    run.setLength(0);
                }
            }
            if (run.length() >= 6) out.append(run);
            return out.toString();
        } catch (Throwable t) {
            return "trace not read: " + t;
        }
    }

    static String reason(int r) {
        switch (r) {
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH (Java)";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "UNKNOWN(" + r + ")";
        }
    }
}
