package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Installs a default uncaught exception handler before anything else runs, so that a crash
 * during {@code Application.onCreate()} still leaves a readable trace on disk.
 *
 * <p>P191 (owner, 2026-10-17: «переноси всё туда чтобы в data ничего не хранилось»): the report goes to
 * {@code /Download/SCAMERA/SCAMERA-crash.log} only (ScameraFiles; the app writes its own files there without a
 * permission), next to SCAMERA-debug.txt. {@code /Android/data/<pkg>/files/SCAMERA-crash.log} is written only when
 * that write failed; ExitReasons reads the Download file first, then that fallback.
 *
 * <p>P214b (engineering.md B4): every report names its build ({@link BuildIdentity}: version, package, commit, CI/local)
 * and its process; the :scam process (the last capture report screen) installs it too; the file is capped: at 1 MB it
 * becomes SCAMERA-crash.1.log before the next report (the older .1 goes), so at most 2 MB stay and the newest report is
 * always at the end of SCAMERA-crash.log, where ExitReasons reads it.
 *
 * <p>This is diagnostic scaffolding for the launch crash and is independent of the
 * "Full debug" switch, which only starts logging once settings are readable.
 */
public final class ScameraCrashReporter {

    static final String STEM = "SCAMERA-crash";
    static final String EXTENSION = ".log";
    private static final String FILE_NAME = STEM + EXTENSION;
    static final long CAP_BYTES = 1L << 20;
    private static boolean installed;

    private ScameraCrashReporter() {
    }

    public static synchronized void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;

        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                write(appContext, thread, throwable);
            } catch (Throwable ignored) {
                // Never let the reporter mask the original crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private static void write(Context context, Thread thread, Throwable throwable) {
        String report = report(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()),
                BuildIdentity.line(), processName(), context.getPackageName(), thread.getName(), throwable);

        if (append(ScameraFiles.file(FILE_NAME), report)) {
            return;
        }
        File privateDir = context.getExternalFilesDir(null);
        if (privateDir != null) {
            append(new File(privateDir, FILE_NAME), report);
        }
    }

    /** The report text; its first line is what ExitReasons looks for. */
    static String report(String time, String build, String process, String packageName, String threadName,
                         Throwable throwable) {
        StringWriter trace = new StringWriter();
        throwable.printStackTrace(new PrintWriter(trace));
        return "SCAMERA crash report\n"
                + "time      : " + time + '\n'
                + "build     : " + build + '\n'
                + "process   : " + process + '\n'
                + "package   : " + packageName + '\n'
                + "device    : " + Build.MANUFACTURER + ' ' + Build.MODEL + '\n'
                + "android   : " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "abis      : " + (Build.SUPPORTED_ABIS == null ? "n/a" : String.join(", ", Build.SUPPORTED_ABIS)) + '\n'
                + "thread    : " + threadName + '\n'
                + '\n' + trace + '\n';
    }

    private static String processName() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return android.app.Application.getProcessName();
        } catch (Throwable ignored) {
            // the pid below
        }
        return "pid " + android.os.Process.myPid();
    }

    /**
     * Appends the report; a file at {@link #CAP_BYTES} first becomes SCAMERA-crash.1.log (one that can be neither moved
     * nor deleted is started again). False when it could not be written.
     */
    static boolean append(File file, String report) {
        try {
            boolean full = file.length() >= CAP_BYTES;
            if (full) RotatingLogFile.rotate(file.getParentFile(), STEM, EXTENSION, 2);
            try (FileOutputStream out = new FileOutputStream(file, !(full && file.exists()))) {
                out.write(report.getBytes("UTF-8"));
                out.flush();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
