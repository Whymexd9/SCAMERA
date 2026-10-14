package com.particlesdevs.photoncamera.ui.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * «Отчёт последней съёмки SHDR» (Система → Диагностика): what the merge worker wrote for the last shot (ScamNeuralClient,
 * scam_capture_report), to read and copy. P129: the SCAM HDR launch checks that used to live here are gone with SCAM HDR.
 */
public final class ScamActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);setTitle(Lang.t(this,"Отчёт последней съёмки SHDR","Last SHDR capture report"));
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(16*getResources().getDisplayMetrics().density);layout.setPadding(pad,pad,pad,pad);
        TextView output=new TextView(this);output.setTextSize(12);output.setTextIsSelectable(true);
        output.setText(reportText());
        Button copy=new Button(this);copy.setText(Lang.t(this,"Скопировать отчёт","Copy report"));
        copy.setOnClickListener(v->((ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("SHDR",output.getText())));layout.addView(copy);
        ScrollView scroll=new ScrollView(this);scroll.addView(output);
        layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(layout);
    }

    String reportText() {
        SharedPreferences capture=getSharedPreferences("scam_capture_report",MODE_PRIVATE);
        String text=capture.getString("report","");
        if(text.isEmpty())return Lang.t(this,"Отчёта съёмки SHDR пока нет.","No SHDR capture report yet.");
        return (capture.getBoolean("complete",false)?"":Lang.t(this,"Съёмка не завершена. Последний этап:\n","The capture did not finish. Last stage:\n"))+text;
    }

    @Override protected void onDestroy(){super.onDestroy();
        // its own process (:scam), as before
        android.os.Process.killProcess(android.os.Process.myPid());}
}
