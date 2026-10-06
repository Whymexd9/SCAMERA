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
import com.particlesdevs.photoncamera.util.Lang;

/** Separate-process HP9 HexQuad validation; does not enable capture. */
public final class VivoNeuralActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private SharedPreferences saved;
    private TextView output;
    private Button start;
    private Button captureReport;
    private volatile boolean running;
    private boolean attempted;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(Lang.t(this,"Vivo Neural — проверка","Vivo Neural check"));
        saved = getSharedPreferences("vivo_neural_report", MODE_PRIVATE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView note = new TextView(this);
        note.setText(Lang.t(this,"Проверка моделей HP9 HexQuad x1/x2 на NPU: Tetra 4×4, цвет и плавные переходы, сравнение мишеней без шума и с шумовым профилем RAW при ISO 800. Нужен root. Модели и QNN находятся в APK. Для фото выберите «Алгоритм ремозаика → HP9 HexQuad», затем модель и параметры в разделе «Нейроремозаик HP9». Эта проверка использует исходный профиль; выбранные множители проверяются отдельно при съёмке. Снимайте в режиме Фото, Tetra 4×4, 4× ISZ телевика. Ниже также доступен отчёт последней съёмки.","Checks the HP9 HexQuad x1/x2 models on the NPU: Tetra 4×4, colour and smooth gradients, test targets without noise and with the RAW noise profile at ISO 800. Root is required. The models and QNN are in the APK. For photos pick “Remosaic algorithm → HP9 HexQuad”, then the model and parameters in “HP9 neural remosaic”. This check uses the original profile; the chosen multipliers are checked separately during capture. Shoot in Photo mode, Tetra 4×4, 4× ISZ of the telephoto. The last capture report is also available below."));
        layout.addView(note);
        start = new Button(this);
        start.setText(Lang.t(this,"Проверить HP9 HexQuad","Check HP9 HexQuad"));
        start.setOnClickListener(v -> runProbe());
        layout.addView(start);
        captureReport = new Button(this);
        captureReport.setText(Lang.t(this,"Отчёт последней съёмки","Last capture report"));
        captureReport.setOnClickListener(v -> {
            SharedPreferences capture = getSharedPreferences("vivo_neural_capture_report", MODE_PRIVATE);
            String text = capture.getString("report", "");
            output.setText(text.isEmpty() ? Lang.t(this,"Отчёта съёмки пока нет.","No capture report yet.") :
                    (capture.getBoolean("complete", false) ? "" : Lang.t(this,"Съёмка не завершена. Последний этап:\n","The capture did not finish. Last stage:\n")) + text);
        });
        layout.addView(captureReport);
        Button copy = new Button(this);
        copy.setText(Lang.t(this,"Скопировать отчёт","Copy report"));
        copy.setOnClickListener(v -> ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Vivo Neural", output.getText())));
        layout.addView(copy);
        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setTextSize(12);
        String previous = saved.getString("report", "");
        if (!previous.isEmpty()) output.setText((saved.getBoolean("complete", false) ? "" :
                Lang.t(this,"Предыдущая проверка прервалась. Последний записанный этап:\n","The previous check was interrupted. Last recorded stage:\n")) + previous);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
    }

    private final Runnable timeout = () -> {
        if (running) {
            append(Lang.t(this,"TIMEOUT: тест остановлен по тайм-ауту; повторно откройте пункт, чтобы скопировать отчёт.","TIMEOUT: the test timed out; open the item again to copy the report."));
            // This PID belongs only to :vivo_neural, never the camera process.
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    };

    private void runProbe() {
        if (running || attempted) return;
        running = attempted = true;
        start.setEnabled(false);
        captureReport.setEnabled(false);
        synchronized (report) { report.setLength(0); }
        saved.edit().putBoolean("complete", false).commit();
        append(Lang.t(this,"HP9 HexQuad v14 — bundled, проверка исходного профиля\n","HP9 HexQuad v14 — bundled, checking the original profile\n") + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                "\n" + android.os.Build.FINGERPRINT);
        main.postDelayed(timeout, 220000);
        new Thread(() -> {
            try {
                VivoNeuralClient.selfTest(this, this::append);
                append(Lang.t(this,"DONE: проверка завершена. HEX PROFILE SUMMARY относится к исходному профилю x2; выбранные настройки проверяются при съёмке; результаты мишеней без шума указаны отдельно. Для фото выберите HP9 HexQuad; модель x1/x2 находится в разделе «Нейроремозаик HP9». При ошибке скопируйте отчёт последней съёмки. Качество реальных фото ещё требует проверки.","DONE: check finished. HEX PROFILE SUMMARY refers to the original x2 profile; the chosen settings are checked during capture; the results for the noise-free targets are listed separately. For photos pick HP9 HexQuad; the x1/x2 model is in “HP9 neural remosaic”. On an error, copy the last capture report. The quality of real photos still needs checking."));
            } catch (Exception | LinkageError failure) {
                append("STOP: " + failure);
            } finally {
                running = false;
                saved.edit().putBoolean("complete", true).commit();
                main.removeCallbacks(timeout);
                main.post(() -> {
                    start.setText(Lang.t(this,"Проверка завершена. Скопируйте отчёт","Check finished. Copy the report"));
                    captureReport.setEnabled(true);
                });
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
