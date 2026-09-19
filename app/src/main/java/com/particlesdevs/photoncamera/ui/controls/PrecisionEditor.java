package com.particlesdevs.photoncamera.ui.controls;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;

public final class PrecisionEditor extends LinearLayout {
    public interface Save {void value(float value);}
    private final PrecisionRuler ruler;
    public PrecisionEditor(Context c,double min,double max,double value,double fallback,boolean decimal){
        super(c);setOrientation(VERTICAL);int pad=dp(16);setPadding(pad,pad,pad,pad);setBackgroundColor(0xFF1B2023);
        TextView number=label(com.particlesdevs.photoncamera.settings.PreferenceNumber.format((float)value,decimal),24);addView(number,new LayoutParams(-1,dp(48)));
        ruler=new PrecisionRuler(c,min,max,value,decimal?.1:1);addView(ruler,new LayoutParams(-1,dp(76)));
        ruler.setListener(v->number.setText(com.particlesdevs.photoncamera.settings.PreferenceNumber.format((float)v,decimal)));
        number.setContentDescription("Точное значение — нажмите для ввода");number.setOnClickListener(v->{
            EditText input=new EditText(c);input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));input.setText(com.particlesdevs.photoncamera.settings.PreferenceNumber.format((float)ruler.getValue(),decimal));input.selectAll();
            AlertDialog dialog=new AlertDialog.Builder(c).setTitle("Точное значение").setMessage(PrecisionRuler.format(min)+" … "+PrecisionRuler.format(max)).setView(input).setPositiveButton("Готово",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(w->dialog.getButton(-1).setOnClickListener(b->{try{double n=Double.parseDouble(input.getText().toString().trim().replace(',','.'));if(!Double.isFinite(n)||n<min||n>max)throw new NumberFormatException();ruler.setValue(decimal?n:Math.round(n));dialog.dismiss();}catch(NumberFormatException ex){input.setError("Введите число в указанном диапазоне");}}));dialog.show();
        });
        LinearLayout steps=new LinearLayout(c);addView(steps);double[] values=decimal?new double[]{.001,.01,.1,1}:new double[]{1,10,100};
        for(double step:values){TextView b=label(PrecisionRuler.format(step),13);steps.addView(b,new LayoutParams(0,dp(44),1));b.setTextColor(step==(decimal?.1:1)?AccentPalette.camera(c):0xFFAAAAAA);b.setContentDescription("Шаг "+PrecisionRuler.format(step));b.setOnClickListener(v->{ruler.setStep(step);for(int i=0;i<steps.getChildCount();i++)((TextView)steps.getChildAt(i)).setTextColor(steps.getChildAt(i)==b?AccentPalette.camera(c):0xFFAAAAAA);});}
        TextView reset=label("По умолчанию: "+PrecisionRuler.format(fallback),13);addView(reset,new LayoutParams(-1,dp(44)));reset.setOnClickListener(v->ruler.setValue(fallback));
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView label(String s,int size){TextView t=new TextView(getContext());t.setText(s);t.setTextColor(0xFFF4F3F7);t.setTextSize(size);t.setGravity(Gravity.CENTER);t.setFocusable(true);return t;}
    public static void show(Context c,String title,double min,double max,double value,double fallback,boolean decimal,Save save){
        PrecisionEditor editor=new PrecisionEditor(c,min,max,value,fallback,decimal);
        new AlertDialog.Builder(c).setTitle(title).setView(editor).setPositiveButton("Применить",(d,w)->save.value((float)(decimal?editor.ruler.getValue():Math.round(editor.ruler.getValue())))).setNegativeButton("Отмена",null).show();
    }
}
