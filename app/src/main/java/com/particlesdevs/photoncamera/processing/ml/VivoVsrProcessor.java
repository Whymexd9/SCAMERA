package com.particlesdevs.photoncamera.processing.ml;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNeuralWorker;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

/**
 * Vivo VSR still super-resolution (sr1x/sr2x/sr4x from /vendor/camera3rd/nti/VSR),
 * run by the bundled root NPU worker on the finished image. The model contexts and
 * QNN runtime come from the APK; no vendor library is loaded.
 */
public final class VivoVsrProcessor {
    private VivoVsrProcessor() {}
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    /** Largest output the worker accepts (pixels). */
    public static final long MAX_OUTPUT = 96_000_000L;

    /** Model scale for a requested output scale in tenths: 1, 2 or 4 (4 only while it fits). */
    public static int modelScale(int scaleTenths, int w, int h) {
        if (scaleTenths >= 30 && (long) w * h * 16 <= MAX_OUTPUT) return 4;
        if (scaleTenths >= 13 && (long) w * h * 4 <= MAX_OUTPUT) return 2;
        return 1;
    }

    public static synchronized Bitmap process(Context context, Bitmap source, int scaleTenths, int strengthPercent) throws Exception {
        final int w = source.getWidth(), h = source.getHeight();
        if (w < 64 || h < 64) throw new IOException("Vivo VSR: слишком маленький снимок");
        final int scale = modelScale(scaleTenths, w, h);
        final float blend = Math.max(0, Math.min(100, strengthPercent)) / 100f;
        final int ow = w * scale, oh = h * scale;
        File dir = new File(context.getCacheDir(), "vivo-vsr-" + UUID.randomUUID());
        if (!dir.mkdir()) throw new IOException("Не удалось создать папку VSR");
        com.particlesdevs.photoncamera.util.WorkerSpawn.Child process = null;
        // Without root when the worker is installed as a native library.
        final boolean direct = com.particlesdevs.photoncamera.util.WorkerSpawn.available(context);
        if (!direct && !com.particlesdevs.photoncamera.settings.PreferenceKeys.isRootEnabled())
            throw new IOException("Vivo VSR: обработчик не установлен; включите «Root-доступ»");
        StringBuilder report = new StringBuilder("Vivo VSR x" + scale + " " + w + "x" + h + " -> " + ow + "x" + oh + " blend=" + blend + "\n");
        AtomicBoolean complete = new AtomicBoolean(false);
        try {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            names.add("vivo-neural-worker");
            for (String[] item : VivoNeuralWorker.HEX_FILES) if (item[0].endsWith(".so")) names.add(item[0]);
            names.add(VivoNeuralWorker.vsrFile(scale)[0]);
            try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
                for (String name : names) {
                    if (direct && name.equals("vivo-neural-worker")) continue;
                    // QNN runtime: installed once as native libraries (also read by the su launcher).
                    File installed = name.endsWith(".so") ? com.particlesdevs.photoncamera.util.WorkerSpawn.library(context, name) : null;
                    if (direct && name.endsWith(".so") && installed == null) throw new IOException("Неполная установка: нет " + name);
                    if (installed != null) {
                        android.system.Os.symlink(installed.getAbsolutePath(), new File(dir, name).getAbsolutePath());
                        continue;
                    }
                    String prefix = name.equals("vivo-neural-worker") ? "assets/vivo-neural/arm64-v8a/" : "assets/vivo-hexquad/arm64-v8a/";
                    java.util.zip.ZipEntry entry = apk.getEntry(prefix + name);
                    if (entry == null) throw new IOException("Неполный APK: отсутствует " + name);
                    File file = new File(dir, name);
                    try (InputStream in = apk.getInputStream(entry); FileOutputStream out = new FileOutputStream(file)) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    if (name.equals("vivo-neural-worker") && !file.setExecutable(true, true))
                        throw new IOException("Не удалось разрешить запуск нейромодуля");
                }
            }
            File input = new File(dir, "input.rgb"), output = new File(dir, "output.rgb");
            int[] row = new int[w];
            byte[] line = new byte[w * 3];
            try (FileOutputStream out = new FileOutputStream(input)) {
                for (int y = 0; y < h; y++) {
                    source.getPixels(row, 0, w, 0, y, w, 1);
                    for (int x = 0; x < w; x++) {
                        int c = row[x];
                        line[x * 3] = (byte) (c >> 16); line[x * 3 + 1] = (byte) (c >> 8); line[x * 3 + 2] = (byte) c;
                    }
                    out.write(line);
                }
            }
            if (!output.createNewFile()) throw new IOException("Не удалось создать файл результата VSR");
            if (direct) {
                process = com.particlesdevs.photoncamera.util.WorkerSpawn.start(context, java.util.Arrays.asList("--vsr-capture", dir.getAbsolutePath(),
                        Integer.toString(scale), input.getAbsolutePath(), output.getAbsolutePath(),
                        Integer.toString(w), Integer.toString(h), Float.toString(blend)),
                        com.particlesdevs.photoncamera.util.WorkerSpawn.environment(context, dir));
            } else {
            String command = "export CLASSPATH=" + quote(context.getApplicationInfo().sourceDir) +
                    "; export LD_LIBRARY_PATH=" + quote("/system/lib64:/system_ext/lib64:" + dir.getAbsolutePath() + ":/vendor/lib64") +
                    "; export ADSP_LIBRARY_PATH=" + quote(dir.getAbsolutePath() + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp;/system/lib/rfsa/adsp") +
                    "; exec /system/bin/app_process64 /system/bin " + VivoNeuralWorker.class.getName() + " " + quote(dir.getAbsolutePath()) +
                    " --vsr-capture " + scale + " " + quote(input.getAbsolutePath()) + " " + quote(output.getAbsolutePath()) +
                    " " + w + " " + h + " " + blend;
            process = com.particlesdevs.photoncamera.util.WorkerSpawn.Child.of(new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start());
            }
            final com.particlesdevs.photoncamera.util.WorkerSpawn.Child child = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(child.output))) {
                    String l;
                    while ((l = lines.readLine()) != null) {
                        if (l.equals("VSR CAPTURE OK")) complete.set(true);
                        synchronized (report) { if (report.length() < 64000) report.append(l).append('\n'); }
                        Log.d("VivoVSR", l);
                    }
                } catch (IOException e) { Log.e("VivoVSR", "Worker output error", e); }
            }, "vivo-vsr-log");
            reader.setDaemon(true); reader.start();
            if (!process.waitFor(TimeUnit.SECONDS.toMillis(300))) throw new IOException("Vivo VSR: тайм-аут");
            reader.join(3000);
            if (process.exitValue() != 0 || !complete.get())
                throw new IOException("Vivo VSR не завершён (exit=" + process.exitValue() + "); исходный снимок сохранён");
            if (output.length() != (long) ow * oh * 3) throw new IOException("Неверный размер результата VSR");
            Bitmap result = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            int[] outRow = new int[ow];
            byte[] outLine = new byte[ow * 3];
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(output), 1 << 20))) {
                for (int y = 0; y < oh; y++) {
                    in.readFully(outLine);
                    for (int x = 0; x < ow; x++)
                        outRow[x] = 0xff000000 | ((outLine[x * 3] & 255) << 16) | ((outLine[x * 3 + 1] & 255) << 8) | (outLine[x * 3 + 2] & 255);
                    result.setPixels(outRow, 0, ow, 0, y, ow, 1);
                }
            }
            // Requested scale between the model scales (e.g. 1.5x, 3x): resample the
            // model output to the exact size; above 4x or past the size cap, upsample.
            int tw = Math.max(1, Math.round(w * scaleTenths / 10f)), th = Math.max(1, Math.round(h * scaleTenths / 10f));
            if ((long) tw * th > MAX_OUTPUT) { double k = Math.sqrt((double) MAX_OUTPUT / ((long) tw * th)); tw = (int) (tw * k) & ~1; th = (int) (th * k) & ~1; }
            if (tw != ow || th != oh) {
                Bitmap resized = Bitmap.createScaledBitmap(result, tw, th, true);
                if (resized != result) result.recycle();
                synchronized (report) { report.append("resampled to ").append(tw).append('x').append(th).append('\n'); }
                return resized;
            }
            return result;
        } catch (Exception e) {
            synchronized (report) { report.append("CLIENT STOP: ").append(e).append('\n'); }
            throw e;
        } finally {
            if (process != null) process.destroy();
            synchronized (report) { context.getSharedPreferences("vivo_upscale_report", Context.MODE_PRIVATE).edit().putString("report", report.toString()).apply(); }
            File[] files = dir.listFiles(); if (files != null) for (File f : files) f.delete(); dir.delete();
        }
    }
}
