package com.particlesdevs.photoncamera.ui.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Explicit, separate-process firmware compatibility test. Never a capture backend. */
public final class VivoNeuralActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private SharedPreferences saved;
    private TextView output;
    private Button start;
    private volatile boolean running;
    private boolean attempted;
    private native void nativeProbe(String directory);

    private static final String ROOT = "/vendor/lib64/";
    private static final String[][] LIBRARIES = {
            {"libremosaiclib_s5khp3.so", "7495113303fb01cff07434eb9896253774846a7b2c5ff1e13017654c884c3aa5"},
            {"hw/libQnnSystem.so", "4c221cdd15eedfda218ed3c020e71c6c4f373f4f7149753b5eded92ef3c127e6"},
            {"libcdsprpc.so", "874a3df45641ebee875868c66417e334c4ae3bffa99ea6936d708e125e1bd920"},
            {"hw/libQnnHtpV79Stub.so", "77c35c65a6c6f3b059223575689d4294fb486ba8e062a9ad63a9ac15380076a5"},
            {"hw/libQnnHtp.so", "73683f1dabfafe1199ff922b43cf748198bbc793783d50585aeeb22e0e14caa2"}
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Vivo Neural — проверка");
        saved = getSharedPreferences("vivo_neural_report", MODE_PRIVATE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView note = new TextView(this);
        note.setText("Проверка загрузки модели Vivo на нейроускоритель. Обработка фотографий нейросетью ещё не подключена. После проверки скопируйте отчёт.");
        layout.addView(note);
        start = new Button(this);
        start.setText("Проверить Vivo Neural");
        start.setOnClickListener(v -> runProbe());
        layout.addView(start);
        Button copy = new Button(this);
        copy.setText("Скопировать отчёт");
        copy.setOnClickListener(v -> ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Vivo Neural", output.getText())));
        layout.addView(copy);
        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setTextSize(12);
        String previous = saved.getString("report", "");
        if (!previous.isEmpty()) output.setText((saved.getBoolean("complete", false) ? "" :
                "Предыдущая проверка прервалась. Последний записанный этап:\n") + previous);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
    }

    private final Runnable timeout = () -> {
        if (running) {
            append("TIMEOUT: тест остановлен через 45 секунд; повторно откройте пункт, чтобы скопировать отчёт.");
            // This PID belongs only to :vivo_neural, never the camera process.
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    };

    private void runProbe() {
        if (running || attempted) return;
        running = attempted = true;
        start.setEnabled(false);
        synchronized (report) { report.setLength(0); }
        saved.edit().putBoolean("complete", false).commit();
        append("Vivo Neural probe v1\n" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                "\n" + android.os.Build.FINGERPRINT);
        main.postDelayed(timeout, 45000);
        new Thread(() -> {
            try {
                if (!android.os.Process.is64Bit()) throw new IllegalStateException("Нужен ARM64-процесс");
                File directory = new File(getCodeCacheDir(), "vivo-neural-v1");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Нет папки для библиотек");
                for (int i = 0; i < LIBRARIES.length; ++i) {
                    String relative = LIBRARIES[i][0];
                    append("VERIFY: " + relative);
                    // Read only accessible firmware files. No root, private vendor
                    // namespace, calibration fabrication or proprietary APK assets.
                    File source = new File(ROOT + relative);
                    if (source.length() <= 0 || source.length() > 128L * 1024 * 1024)
                        throw new IllegalStateException("Файл недоступен или имеет неверный размер: " + source);
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    File destination = i == 0 ? null : new File(directory, source.getName());
                    if (destination != null && destination.exists() && !destination.delete())
                        throw new IllegalStateException("Нельзя обновить копию библиотеки");
                    try (FileInputStream input = new FileInputStream(source);
                         FileOutputStream out = destination == null ? null : new FileOutputStream(destination)) {
                        // Android 14 dynamic-code rules: mark the open file read-only
                        // before writing, and load it only after verifying its hash.
                        if (destination != null && !destination.setReadOnly()) throw new IllegalStateException("Нельзя защитить файл библиотеки");
                        byte[] buffer = new byte[65536];
                        int n;
                        long total = 0;
                        while ((n = input.read(buffer)) != -1) {
                            total += n;
                            if (total > 128L * 1024 * 1024) throw new IllegalStateException("Слишком большой файл");
                            digest.update(buffer, 0, n);
                            if (out != null) out.write(buffer, 0, n);
                        }
                    }
                    StringBuilder hex = new StringBuilder();
                    for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
                    if (!LIBRARIES[i][1].contentEquals(hex))
                        throw new IllegalStateException("Неизвестная версия " + relative + ": " + hex);
                    append("HASH OK");
                }
                System.loadLibrary("vivoNeuralProbe");
                nativeProbe(directory.getAbsolutePath());
                append("DONE: это проверка загрузки; нейроремозаик HP9 4× ISZ ещё не включён.");
            } catch (Exception | LinkageError failure) {
                append("STOP: " + failure);
            } finally {
                running = false;
                saved.edit().putBoolean("complete", true).commit();
                main.removeCallbacks(timeout);
                main.post(() -> start.setText("Проверка завершена. Скопируйте отчёт"));
            }
        }, "vivo-neural-probe").start();
    }

    // Called on the native worker; persist each stage before entering vendor code.
    @androidx.annotation.Keep public void onNativeProgress(String line) { append(line); }

    private void append(String line) {
        final String snapshot;
        synchronized (report) {
            report.append(line).append('\n');
            snapshot = report.toString();
            saved.edit().putString("report", snapshot).commit();
        }
        main.post(() -> { if (!isDestroyed()) output.setText(snapshot); });
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(timeout);
        super.onDestroy();
        // Unload every vendor allocation, including failed initialization. The
        // activity has a dedicated process and does not host capture or settings.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
