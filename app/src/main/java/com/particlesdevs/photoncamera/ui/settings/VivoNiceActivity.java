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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNeuralWorker;

/** App-UID/root execution prerequisite, in :vivo_nice; no camera frames. */
public final class VivoNiceActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final StringBuilder report=new StringBuilder();
    private SharedPreferences saved;
    private TextView output;
    private Button start,rootStart,toneStart;
    private volatile boolean running;
    private native void nativeProbe(String directory);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);setTitle("NICE HDR — проверка запуска");
        saved=getSharedPreferences("vivo_nice_report",MODE_PRIVATE);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(16*getResources().getDisplayMetrics().density);layout.setPadding(pad,pad,pad,pad);
        TextView note=new TextView(this);
        note.setText("Запуск оригинальной HDR-модели основной камеры Vivo. Модель и QNN находятся в APK. Проверка пока не обрабатывает фотографии. Root использует тот же механизм запуска HTP, что нейроремозаик. Обычный запуск проверяет доступ без root. После завершения скопируйте отчёт.");
        layout.addView(note);
        start=new Button(this);start.setText("Проверить без root");start.setOnClickListener(v->runProbe(false));layout.addView(start);
        rootStart=new Button(this);rootStart.setText("Проверить NICE через root");rootStart.setOnClickListener(v->runProbe(true));layout.addView(rootStart);
        toneStart=new Button(this);toneStart.setText("Проверить тональные модели NICE через root");
        toneStart.setOnClickListener(v->runProbe(true,true));layout.addView(toneStart);
        Button captureReport=new Button(this);captureReport.setText("Отчёт последней съёмки NICE");
        captureReport.setOnClickListener(v->{
            if(running)return;
            SharedPreferences capture=getSharedPreferences("vivo_nice_capture_report",MODE_PRIVATE);
            String text=capture.getString("report","");
            output.setText(text.isEmpty()?"Отчёта съёмки NICE пока нет.":
                    (capture.getBoolean("complete",false)?"":"Съёмка не завершена. Последний этап:\n")+text);
        });layout.addView(captureReport);
        Button copy=new Button(this);copy.setText("Скопировать отчёт");
        copy.setOnClickListener(v->((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("NICE HDR",output.getText())));layout.addView(copy);
        output=new TextView(this);output.setTextSize(12);output.setTextIsSelectable(true);
        String previous=saved.getString("report","");
        if(!previous.isEmpty())output.setText((saved.getBoolean("complete",false)?"":"Проверка прервалась. Последний этап:\n")+previous);
        ScrollView scroll=new ScrollView(this);scroll.addView(output);
        layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(layout);
    }
    private final Runnable timeout=()-> {
        if(running){onNativeProgress("TIMEOUT: NICE остановлен. Откройте пункт снова и скопируйте отчёт.");
            android.os.Process.killProcess(android.os.Process.myPid());}
    };
    private void copyVerified(File directory,String prefix,String[] item)throws Exception {
        File target=new File(directory,item[0]);
        // Never replace an already-loaded library: this test runs once per PID.
        if(target.exists()&&!target.delete())throw new java.io.IOException("Cannot replace "+item[0]);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=getAssets().open(prefix+item[0]);FileOutputStream out=new FileOutputStream(target)) {
            if(!target.setReadOnly())throw new java.io.IOException("Cannot protect "+item[0]);
            byte[] block=new byte[65536];int n;
            while((n=in.read(block))!=-1){digest.update(block,0,n);out.write(block,0,n);}
        }
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(!hash.toString().equals(item[1])){target.delete();throw new java.io.IOException("SHA-256 mismatch: "+item[0]);}
        onNativeProgress("VERIFIED APK: "+item[0]);
    }
    private void runProbe(boolean root) {runProbe(root,false);}
    private void runProbe(boolean root,boolean tone) {
        if(running)return;running=true;start.setEnabled(false);rootStart.setEnabled(false);toneStart.setEnabled(false);
        synchronized(report){report.setLength(0);}
        saved.edit().putBoolean("complete",false).commit();
        onNativeProgress("SCAMERA NICE prerequisite v2; root_path="+root+" uid="+android.os.Process.myUid()+"\n"+android.os.Build.FINGERPRINT);
        main.postDelayed(timeout,tone?480000:240000);
        new Thread(()-> {
            try {
                if(tone) {
                    com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNeuralClient.selfTestNiceTone(this,this::onNativeProgress);
                } else if(root) {
                    com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNeuralClient.selfTestNice(this,this::onNativeProgress);
                } else {
                File dir=new File(getFilesDir(),"nice-bundled-v1");
                if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException("Cannot create NICE directory");
                for(String[] item:VivoNeuralWorker.NICE_FILES)copyVerified(dir,"vivo-nice/arm64-v8a/",item);
                for(String[] item:VivoNeuralWorker.HEX_FILES)if(item[0].endsWith(".so"))copyVerified(dir,"vivo-hexquad/arm64-v8a/",item);
                System.loadLibrary("vivoNiceProbe");nativeProbe(dir.getAbsolutePath());
                }
                onNativeProgress("CHECK FINISHED: результат указан выше. Это ещё не проверка обработки фото.");
            } catch(Exception|LinkageError e){onNativeProgress("STOP: "+e);}
            finally{running=false;main.removeCallbacks(timeout);saved.edit().putBoolean("complete",true).commit();
                main.post(()->{if(!isDestroyed())start.setText("Проверка завершена. Скопируйте отчёт");});}
        },"nice-runtime-check").start();
    }
    public void onNativeProgress(String line) {
        final String text;
        synchronized(report){report.append(line).append('\n');text=report.toString();saved.edit().putString("report",text).commit();}
        main.post(()->{if(!isDestroyed())output.setText(text);});
    }
    @Override protected void onDestroy(){main.removeCallbacks(timeout);super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());}
}
