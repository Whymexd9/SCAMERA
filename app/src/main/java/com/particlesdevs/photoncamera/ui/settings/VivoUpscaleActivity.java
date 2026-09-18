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

import com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoSoftpqeClient;

/**
 * Separate-process diagnostic for the Vivo softpqe upscale models
 * (2x/4x). Loads the bundled context binaries into QNN HTP and reports
 * their tensor descriptors; never executes the graph and does not enable
 * any capture path. See docs/vivo-softpqe-upscale.md.
 */
public final class VivoUpscaleActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private SharedPreferences saved;
    private TextView output;
    private Button start;
    private volatile boolean running;
    private boolean attempted;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Vivo Upscale — проверка");
        saved = getSharedPreferences("vivo_softpqe_report", MODE_PRIVATE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView note = new TextView(this);
        note.setText("Диагностика моделей апскейлинга Vivo softpqe (2x/4x) на NPU. Загружает модель и граф в Qualcomm HTP и один раз выполняет граф с нейтральным заглушечным входом (плоское значение) — это проверяет механику выполнения, а НЕ обрабатывает реальный снимок. Отчёт покажет реальные параметры квантования входа/выхода, которые Qualcomm сам разрешает в процессе — до этого их нельзя было прочитать. Реальная обработка фото ещё не подключена. Нужен root. Модели и QNN находятся в отдельной сборке Bundled APK. Подробности — docs/vivo-softpqe-upscale.md.");
        layout.addView(note);
        start = new Button(this);
        start.setText("Проверить softpqe 2x/4x");
        start.setOnClickListener(v -> runProbe());
        layout.addView(start);
        Button copy = new Button(this);
        copy.setText("Скопировать отчёт");
        copy.setOnClickListener(v -> ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Vivo Upscale", output.getText())));
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
            append("TIMEOUT: тест остановлен по тайм-ауту; повторно откройте пункт, чтобы скопировать отчёт.");
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    };

    private void runProbe() {
        if (running || attempted) return;
        running = attempted = true;
        start.setEnabled(false);
        synchronized (report) { report.setLength(0); }
        saved.edit().putBoolean("complete", false).commit();
        append("Vivo softpqe upscale — проверка (диагностика, без выполнения графа)\n" +
                android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n" + android.os.Build.FINGERPRINT);
        main.postDelayed(timeout, 220000);
        new Thread(() -> {
            try {
                VivoSoftpqeClient.selfTest(this, this::append);
                append("DONE: проверка завершена. Успех означает, что граф выполнился на заглушечном входе без ошибок, и в отчёте есть реальные параметры квантования. Это НЕ проверка качества апскейлинга на настоящем фото — для этого параметры квантования входа нужно применить перед следующим шагом.");
            } catch (Exception | LinkageError failure) {
                append("STOP: " + failure);
            } finally {
                running = false;
                saved.edit().putBoolean("complete", true).commit();
                main.removeCallbacks(timeout);
                main.post(() -> start.setText("Проверка завершена. Скопируйте отчёт"));
            }
        }, "vivo-softpqe-probe").start();
    }

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
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
