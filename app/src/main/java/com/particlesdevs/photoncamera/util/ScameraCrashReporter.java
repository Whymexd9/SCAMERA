package com.particlesdevs.photoncamera.util;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

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
 * <p>The report is written to two places, because the more convenient one needs a runtime
 * permission that may not have been granted yet when the crash happens:
 * <ul>
 *     <li>{@code /Android/data/<pkg>/files/SCAMERA-crash.log} — always writable, no permission;</li>
 *     <li>{@code /Download/SCAMERA/SCAMERA-crash.log} — best effort, ignored on failure.</li>
 * </ul>
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

        File privateDir = context.getExternalFilesDir(null);
        if (privateDir != null) {
            writeTo(new File(privateDir, FILE_NAME), report);
        }

        File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File scameraDir = new File(download, "SCAMERA");
        if (scameraDir.isDirectory() || scameraDir.mkdirs()) {
            writeTo(new File(scameraDir, FILE_NAME), report);
        }
    }

    private static void writeTo(File file, String report) {
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(report.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
            // Best effort only.
        }
    }
}
