package com.particlesdevs.photoncamera.ui.camera.views;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.particlesdevs.photoncamera.settings.*;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.ui.controls.PrecisionEditor;
import com.particlesdevs.photoncamera.ui.settings.SettingsActivity;

public class FavoriteSettingsButton extends androidx.appcompat.widget.AppCompatTextView {
    private FavoriteSettings catalogue;
    private PopupWindow popup;
    private Runnable applied;
    private String editModule;
    public FavoriteSettingsButton(Context c,AttributeSet a){super(c,a);setText("☆");setTextSize(26);setTextColor(0xFFFFFFFF);setGravity(Gravity.CENTER);setContentDescription("Избранные настройки");setFocusable(true);setBackground(background());setOnClickListener(v->open());}
    public void setOnApplied(Runnable r){applied=r;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(){GradientDrawable d=new GradientDrawable();d.setColor(0x66000000);d.setCornerRadius(dp(24));return d;}
    private TextView button(String label,Runnable click){TextView t=new TextView(getContext());t.setText(label);t.setTextColor(0xFFFFFFFF);t.setTextSize(13);t.setGravity(Gravity.CENTER);t.setPadding(dp(16),dp(12),dp(16),dp(12));t.setMinHeight(dp(48));t.setFocusable(true);t.setOnClickListener(v->click.run());return t;}
    private void configure(){getContext().startActivity(new Intent(getContext(),SettingsActivity.class).putExtra("open_favorites",true));}
    private void open(){
        if(CaptureController.isProcessing)return;
        if(catalogue==null)catalogue=new FavoriteSettings(getContext());
        if(popup!=null&&popup.isShowing()){popup.dismiss();return;}
        LinearLayout content=new LinearLayout(getContext());content.setOrientation(LinearLayout.VERTICAL);content.setBackground(background());
        HorizontalScrollView scroll=new HorizontalScrollView(getContext());scroll.setHorizontalScrollBarEnabled(false);LinearLayout chips=new LinearLayout(getContext());scroll.addView(chips);content.addView(scroll);
        for(String key:FavoriteSettings.selected()){FavoriteSettings.Entry e=catalogue.entries.get(key);if(e!=null)chips.addView(button(e.title+"\n"+e.label(),()->{popup.dismiss();edit(e);}));}
        content.addView(button("Настроить избранное",()->{popup.dismiss();configure();}));
        popup=new PopupWindow(content,Math.max(dp(200),getResources().getDisplayMetrics().widthPixels-dp(40)),-2,true);popup.setBackgroundDrawable(background());popup.setOutsideTouchable(true);popup.setElevation(dp(8));popup.showAsDropDown(this,0,-getHeight());content.setPivotX(0);content.setPivotY(0);content.setScaleX(.85f);content.setAlpha(0);content.animate().scaleX(1).alpha(1).setDuration(220).start();
    }
    private void save(FavoriteSettings.Entry e,Object value){
        if(!ModuleRegistry.active().equals(editModule)||CaptureController.isProcessing){Toast.makeText(getContext(),"Модуль сменился или идёт обработка. Откройте параметр повторно.",Toast.LENGTH_SHORT).show();return;}
        e.write(value);if(applied!=null)applied.run();
    }
    private void edit(FavoriteSettings.Entry e){
        editModule=ModuleRegistry.active();String reason=e.unavailable();if(reason!=null){new AlertDialog.Builder(getContext()).setTitle(e.title).setMessage(reason).setPositiveButton("Понятно",null).show();return;}
        if(e.kind==0){boolean current=PreferenceNumber.bool(e.value(),false);new AlertDialog.Builder(getContext()).setTitle(e.title).setSingleChoiceItems(new String[]{"Выкл","Вкл"},current?1:0,(d,w)->{save(e,w==1);d.dismiss();}).setNegativeButton("Отмена",null).show();}
        else if(e.kind==1){int index=-1;for(int i=0;i<e.values.length;i++)if(e.values[i].toString().equals(String.valueOf(e.value())))index=i;new AlertDialog.Builder(getContext()).setTitle(e.title).setSingleChoiceItems(e.labels,index,(d,w)->{save(e,e.values[w].toString());d.dismiss();}).setNegativeButton("Отмена",null).show();}
        else PrecisionEditor.show(getContext(),e.title,e.min,e.max,PreferenceNumber.read(e.value(),((Number)e.defaultValue).doubleValue()),((Number)e.defaultValue).doubleValue(),e.decimal,v->save(e,v));
    }
    @Override protected void onDetachedFromWindow(){if(popup!=null)popup.dismiss();super.onDetachedFromWindow();}
}
