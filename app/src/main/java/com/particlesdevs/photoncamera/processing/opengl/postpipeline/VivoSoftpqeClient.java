package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/**
 * Diagnostic-only probe for the Vivo softpqe upscale models: loads the
 * bundled 2x/4x context binaries into QNN HTP and reports their tensor
 * descriptors. Never executes the graph. See docs/vivo-softpqe-upscale.md.
 * Modelled on {@link VivoNeuralClient}'s self-test path; kept separate so
 * this experiment cannot regress the working remosaic transport.
 */
public final class VivoSoftpqeClient {
    private VivoSoftpqeClient() {}
    public static synchronized void selfTest(Context context, Consumer<String> observer) throws Exception {
        File dir = new File(context.getCacheDir(), "vivo-softpqe-job-" + UUID.randomUUID());
        if (!dir.mkdir()) throw new IOException("Не удалось создать папку задания");
        Process process = null;
        final long startMs = android.os.SystemClock.elapsedRealtime();
        SharedPreferences prefs = context.getSharedPreferences("vivo_softpqe_report", Context.MODE_PRIVATE);
        StringBuilder report = new StringBuilder("SCAMERA: softpqe upscale check (diagnostic only)\n");
        prefs.edit().putString("report", report.toString()).putBoolean("complete", false).commit();
        final long[] lastWriteMs = {startMs};
        Consumer<String> log = line -> {
            synchronized (report) {
                if (report.length() < 128000) report.append(line).append('\n');
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastWriteMs[0] >= 500 || line.startsWith("STOP:") || line.startsWith("CLIENT STOP:")) {
                    prefs.edit().putString("report", report.toString()).commit();
                    lastWriteMs[0] = now;
                }
            }
            observer.accept(line);
        };
        try {
            try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
                java.util.ArrayList<String> names = new java.util.ArrayList<>();
                names.add("vivo-neural-worker");
                for (String[] item : VivoNeuralWorker.SOFTPQE_FILES) names.add(item[0]);
                for (String name : names) {
                    String prefix = name.equals("vivo-neural-worker") ? "assets/vivo-neural/arm64-v8a/" : "assets/vivo-softpqe/arm64-v8a/";
                    java.util.zip.ZipEntry entry = apk.getEntry(prefix + name);
                    if (entry == null) throw new IOException("Неполный APK: отсутствует " + name + ". Установите сборку Bundled.");
                    File file = new File(dir, name);
                    try (InputStream in = apk.getInputStream(entry); FileOutputStream out = new FileOutputStream(file)) {
                        if (!file.setReadOnly()) throw new IOException("Не удалось защитить " + name);
                        byte[] buf = new byte[65536]; int n; long total = 0;
                        while ((n = in.read(buf)) != -1) { total += n; if (total > 128L * 1024 * 1024) throw new IOException("Слишком большой ресурс"); out.write(buf, 0, n); }
                    }
                    if (name.equals("vivo-neural-worker") && !file.setExecutable(true, true)) throw new IOException("Не удалось разрешить запуск нейромодуля");
                }
            }
            String command = "export CLASSPATH=" + quote(context.getApplicationInfo().sourceDir) +
                    "; export LD_LIBRARY_PATH=" + quote("/system/lib64:/system_ext/lib64:" + dir.getAbsolutePath() + ":/vendor/lib64") +
                    "; export ADSP_LIBRARY_PATH=" + quote(dir.getAbsolutePath() + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/system/lib/rfsa/adsp") +
                    "; exec /system/bin/app_process64 /system/bin " + VivoNeuralWorker.class.getName() + " " + quote(dir.getAbsolutePath()) + " --softpqe";
            log.accept("ROOT: запуск отдельного процесса; разрешите запрос root");
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            final Process child = process;
            final boolean[] completed = {false};
            Thread reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.equals("SOFTPQE CHECK COMPLETE")) completed[0] = true;
                        log.accept(line);
                    }
                } catch (IOException e) { log.accept("READ: " + e); }
            }, "vivo-softpqe-output");
            reader.setDaemon(true); reader.start();
            if (!process.waitFor(200, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Тайм-аут проверки"); }
            reader.join(5000);
            if (reader.isAlive() || process.exitValue() != 0 || !completed[0])
                throw new IOException("Проверка softpqe не завершена. Скопируйте отчёт.");
            log.accept("CLIENT TOTAL ms=" + (android.os.SystemClock.elapsedRealtime() - startMs));
        } catch (Exception e) {
            log.accept("CLIENT STOP: " + e.getMessage());
            throw e;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            synchronized (report) { prefs.edit().putString("report", report.toString()).putBoolean("complete", true).commit(); }
            File[] files = dir.listFiles(); if (files != null) for (File f : files) f.delete();
            dir.delete();
        }
    }
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
