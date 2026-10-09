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
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralWorker;
import com.particlesdevs.photoncamera.util.Lang;

/** App-UID/root execution prerequisite, in :scam; no camera frames. */
public final class ScamActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final StringBuilder report=new StringBuilder();
    private SharedPreferences saved;
    private TextView output;
    private Button start,rootStart,toneStart;
    private volatile boolean running;
    private native void nativeProbe(String directory);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);setTitle(Lang.t(this,"SCAM HDR — проверка запуска","SCAM HDR: launch check"));
        saved=getSharedPreferences("scam_report",MODE_PRIVATE);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(16*getResources().getDisplayMetrics().density);layout.setPadding(pad,pad,pad,pad);
        TextView note=new TextView(this);
        note.setText(Lang.t(this,"Запуск оригинальной HDR-модели основной камеры Vivo. Модель и QNN находятся в APK. Проверка пока не обрабатывает фотографии. Root использует тот же механизм запуска HTP, что нейроремозаик. Обычный запуск проверяет доступ без root. После завершения скопируйте отчёт.","Runs the original HDR model of the Vivo main camera. The model and QNN are in the APK. The check does not process photos yet. Root uses the same HTP launch path as the neural remosaic. A normal run checks access without root. When it finishes, copy the report."));
        layout.addView(note);
        start=new Button(this);start.setText(Lang.t(this,"Проверить без root","Check without root"));start.setOnClickListener(v->runProbe(false));layout.addView(start);
        rootStart=new Button(this);rootStart.setText(Lang.t(this,"Проверить SCAM HDR через root","Check SCAM HDR with root"));rootStart.setOnClickListener(v->runProbe(true));layout.addView(rootStart);
        toneStart=new Button(this);toneStart.setText(Lang.t(this,"Проверить тональные модели SCAM HDR через root","Check the SCAM HDR tone models with root"));
        toneStart.setOnClickListener(v->runProbe(true,true));layout.addView(toneStart);
        Button captureReport=new Button(this);captureReport.setText(Lang.t(this,"Отчёт последней съёмки SCAM HDR","Last SCAM HDR capture report"));
        captureReport.setOnClickListener(v->{
            if(running)return;
            SharedPreferences capture=getSharedPreferences("scam_capture_report",MODE_PRIVATE);
            String text=capture.getString("report","");
            output.setText(text.isEmpty()?Lang.t(this,"Отчёта съёмки SCAM HDR пока нет.","No SCAM HDR capture report yet."):
                    (capture.getBoolean("complete",false)?"":Lang.t(this,"Съёмка не завершена. Последний этап:\n","The capture did not finish. Last stage:\n"))+text);
        });layout.addView(captureReport);
        Button copy=new Button(this);copy.setText(Lang.t(this,"Скопировать отчёт","Copy report"));
        copy.setOnClickListener(v->((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("SCAM HDR",output.getText())));layout.addView(copy);
        output=new TextView(this);output.setTextSize(12);output.setTextIsSelectable(true);
        String previous=saved.getString("report","");
        if(!previous.isEmpty())output.setText((saved.getBoolean("complete",false)?"":Lang.t(this,"Проверка прервалась. Последний этап:\n","The check was interrupted. Last stage:\n"))+previous);
        ScrollView scroll=new ScrollView(this);scroll.addView(output);
        layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(layout);
    }
    private final Runnable timeout=()-> {
        if(running){onNativeProgress(Lang.t(this,"TIMEOUT: SCAM HDR остановлен. Откройте пункт снова и скопируйте отчёт.","TIMEOUT: SCAM HDR stopped. Open the item again and copy the report."));
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
        onNativeProgress("SCAMERA SCAM prerequisite v2; root_path="+root+" uid="+android.os.Process.myUid()+"\n"+android.os.Build.FINGERPRINT);
        main.postDelayed(timeout,tone?480000:240000);
        new Thread(()-> {
            try {
                if(tone) {
                    com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralClient.selfTestScamTone(this,this::onNativeProgress);
                } else if(root) {
                    com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamNeuralClient.selfTestScam(this,this::onNativeProgress);
                } else {
                File dir=new File(getFilesDir(),"scam-bundled-v1");
                if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException("Cannot create SCAM directory");
                for(String[] item:ScamNeuralWorker.SCAM_FILES)copyVerified(dir,"scam/arm64-v8a/",item);
                for(String[] item:ScamNeuralWorker.HEX_FILES)if(item[0].endsWith(".so"))copyVerified(dir,"scam-hexquad/arm64-v8a/",item);
                System.loadLibrary("scamProbe");nativeProbe(dir.getAbsolutePath());
                }
                onNativeProgress(Lang.t(this,"CHECK FINISHED: результат указан выше. Это ещё не проверка обработки фото.","CHECK FINISHED: the result is above. Photo processing is not checked yet."));
            } catch(Exception|LinkageError e){onNativeProgress("STOP: "+e);}
            finally{running=false;main.removeCallbacks(timeout);saved.edit().putBoolean("complete",true).commit();
                main.post(()->{if(!isDestroyed())start.setText(Lang.t(this,"Проверка завершена. Скопируйте отчёт","Check finished. Copy the report"));});}
        },"scam-runtime-check").start();
    }
    public void onNativeProgress(String line) {
        final String text;
        synchronized(report){report.append(line).append('\n');text=report.toString();saved.edit().putString("report",text).commit();}
        main.post(()->{if(!isDestroyed())output.setText(text);});
    }
    @Override protected void onDestroy(){main.removeCallbacks(timeout);super.onDestroy();
        android.os.Process.killProcess(android.os.Process.myPid());}
}
