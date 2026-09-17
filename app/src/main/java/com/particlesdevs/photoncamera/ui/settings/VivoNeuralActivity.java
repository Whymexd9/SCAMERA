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

import com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNeuralClient;

/** Separate-process HP9 HexQuad validation; does not enable capture. */
public final class VivoNeuralActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private SharedPreferences saved;
    private TextView output;
    private Button start;
    private volatile boolean running;
    private boolean attempted;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Vivo Neural — проверка");
        saved = getSharedPreferences("vivo_neural_report", MODE_PRIVATE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView note = new TextView(this);
        note.setText("Проверка моделей HP9 HexQuad x1/x2 на NPU: Tetra 4×4, цвет и плавные переходы при двух ISO. Нужен root. Модели и QNN находятся в APK. Для фото выберите на главной странице настроек «Алгоритм ремозаика → HP9 HexQuad x2». Снимайте в режиме Фото, Tetra 4×4, 4× ISZ телевика. Ниже также доступен отчёт последней съёмки.");
        layout.addView(note);
        start = new Button(this);
        start.setText("Проверить HP9 HexQuad");
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
            append("TIMEOUT: тест остановлен по тайм-ауту; повторно откройте пункт, чтобы скопировать отчёт.");
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
        append("HP9 HexQuad v2 — bundled, проверка\n" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                "\n" + android.os.Build.FINGERPRINT);
        main.postDelayed(timeout, 220000);
        new Thread(() -> {
            try {
                VivoNeuralClient.selfTest(this, this::append);
                append("DONE: проверка завершена. Итог цвета указан в HEX SUMMARY: PASS/FAIL. Для тестовой съёмки отдельно выберите HP9 HexQuad x2 в алгоритмах ремозаика.");
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
        // This dedicated UI process does not host capture. The root helper has
        // its own bounded lifetime if the user closes this screen mid-test.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
