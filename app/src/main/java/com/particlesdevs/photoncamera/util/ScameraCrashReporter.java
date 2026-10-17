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
 * permission), next to SCAMERA-debug.log. {@code /Android/data/<pkg>/files/SCAMERA-crash.log} is written only when
 * that write failed; ExitReasons reads the Download file first, then that fallback.
 *
 * <p>This is diagnostic scaffolding for the launch crash and is independent of the
 * "Full debug" switch, which only starts logging once settings are readable.
 */
public final class ScameraCrashReporter {

    private static final String FILE_NAME = "SCAMERA-crash.log";
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
        StringWriter trace = new StringWriter();
        throwable.printStackTrace(new PrintWriter(trace));

        String report = "SCAMERA crash report\n"
                + "time      : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + '\n'
                + "package   : " + context.getPackageName() + '\n'
                + "device    : " + Build.MANUFACTURER + ' ' + Build.MODEL + '\n'
                + "android   : " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "abis      : " + String.join(", ", Build.SUPPORTED_ABIS) + '\n'
                + "thread    : " + thread.getName() + '\n'
                + '\n' + trace + '\n';

        if (writeTo(ScameraFiles.file(FILE_NAME), report)) {
            return;
        }
        File privateDir = context.getExternalFilesDir(null);
        if (privateDir != null) {
            writeTo(new File(privateDir, FILE_NAME), report);
        }
    }

    /** Appends the report; false when it could not be written. */
    private static boolean writeTo(File file, String report) {
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(report.getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
