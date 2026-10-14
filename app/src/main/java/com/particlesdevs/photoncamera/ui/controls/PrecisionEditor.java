package com.particlesdevs.photoncamera.ui.controls;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.settings.PreferenceNumber;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * The exact-value editor of a settings slider. P137 (owner: «все вот такие меню переделай под стиль интерфейса нашей камеры,
 * так же добавь возможность прямо из этого подменю добавлять настройки в шторку»): a bottom sheet like the other pickers
 * instead of the stock dialog - the value (tap: type it in place), the range, the relative ruler with its step chips,
 * «По умолчанию» and «Применить», and «В шторку» on the title line.
 */
public final class PrecisionEditor extends LinearLayout {
    public interface Save {void value(float value);}
    private final PrecisionRuler ruler;
    private final TextView number;
    private final EditText field;
    private final double min,max;
    private final boolean decimal;
    public PrecisionEditor(Context c,double min,double max,double value,double fallback,boolean decimal){
        super(c);setOrientation(VERTICAL);this.min=min;this.max=max;this.decimal=decimal;
        int accent=SettingsStyle.accent(c);
        number=SettingsStyle.text(c,PreferenceNumber.format((float)value,decimal),34,SettingsStyle.TEXT);
        number.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));number.setGravity(Gravity.CENTER);number.setTag("precision_value");
        number.setFocusable(true);number.setContentDescription(Lang.t(c,"Точное значение — нажмите для ввода","Exact value, tap to type it"));
        addView(number,new LayoutParams(-1,dp(52)));
        field=new EditText(c);field.setSingleLine(true);field.setTextSize(22);field.setGravity(Gravity.CENTER);field.setTextColor(SettingsStyle.TEXT);
        field.setBackground(SettingsStyle.shape(c,SettingsStyle.FIELD,SettingsStyle.LINE,14));field.setPadding(dp(16),dp(10),dp(16),dp(10));
        field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);field.setTag("precision_field");field.setVisibility(GONE);
        addView(field,new LayoutParams(-1,dp(52)));
        TextView range=SettingsStyle.text(c,PrecisionRuler.format(min)+" … "+PrecisionRuler.format(max),12,SettingsStyle.MUTED);range.setGravity(Gravity.CENTER);
        LayoutParams rp=new LayoutParams(-1,-2);rp.topMargin=dp(2);addView(range,rp);
        ruler=new PrecisionRuler(c,min,max,value,decimal?.1:1);ruler.setAccent(accent);addView(ruler,new LayoutParams(-1,dp(76)));
        ruler.setListener(v->number.setText(PreferenceNumber.format((float)v,decimal)));
        number.setOnClickListener(v->{field.setText(PreferenceNumber.format((float)ruler.getValue(),decimal));field.selectAll();number.setVisibility(GONE);field.setVisibility(VISIBLE);field.requestFocus();
            InputMethodManager ime=(InputMethodManager)c.getSystemService(Context.INPUT_METHOD_SERVICE);if(ime!=null)ime.showSoftInput(field,0);});
        field.setOnEditorActionListener((v,action,e)->{if(action==EditorInfo.IME_ACTION_DONE){commitField();return true;}return false;});
        // the ruler's step: chips, the chosen one in the accent
        LinearLayout steps=new LinearLayout(c);steps.setGravity(Gravity.CENTER);
        LayoutParams sp=new LayoutParams(-1,-2);sp.topMargin=dp(6);addView(steps,sp);
        double[] values=decimal?new double[]{.001,.01,.1,1}:new double[]{1,10,100};
        for(double step:values){
            TextView b=SettingsStyle.text(c,Lang.t(c,"Шаг ","Step ")+PrecisionRuler.format(step),13,SettingsStyle.MUTED);b.setGravity(Gravity.CENTER);b.setFocusable(true);
            b.setTag("precision_step_"+PrecisionRuler.format(step));
            LayoutParams bp=new LayoutParams(0,dp(38),1);bp.setMarginStart(dp(3));bp.setMarginEnd(dp(3));steps.addView(b,bp);
            b.setOnClickListener(v->{ruler.setStep(step);paintSteps(steps,b,accent);});
            if(step==(decimal?.1:1))paintSteps(steps,b,accent);else b.setBackground(SettingsStyle.shape(c,SettingsStyle.FIELD,0,19));
        }
        TextView reset=SettingsStyle.text(c,Lang.t(c,"По умолчанию: ","Default: ")+PrecisionRuler.format(fallback),14,SettingsStyle.TEXT);
        reset.setGravity(Gravity.CENTER);reset.setPadding(dp(12),dp(13),dp(12),dp(13));reset.setBackground(SettingsStyle.shape(c,SettingsStyle.FIELD,SettingsStyle.LINE,16));
        reset.setTag("precision_reset");reset.setOnClickListener(v->{ruler.setValue(fallback);number.setText(PreferenceNumber.format((float)ruler.getValue(),decimal));});
        LayoutParams xp=new LayoutParams(-1,-2);xp.topMargin=dp(16);addView(reset,xp);
    }
    private void paintSteps(LinearLayout steps,TextView chosen,int accent){
        for(int i=0;i<steps.getChildCount();i++){TextView t=(TextView)steps.getChildAt(i);boolean on=t==chosen;
            t.setTextColor(on?accent:SettingsStyle.MUTED);t.setBackground(SettingsStyle.shape(getContext(),on?SettingsStyle.tint(accent,.18f):SettingsStyle.FIELD,0,19));t.setSelected(on);}
    }
    /** The typed value into the ruler; false (and the field marked) when it is not a number in the range. */
    private boolean commitField(){
        if(field.getVisibility()!=VISIBLE)return true;
        try{double n=Double.parseDouble(field.getText().toString().trim().replace(',','.'));if(!Double.isFinite(n)||n<min||n>max)throw new NumberFormatException();
            ruler.setValue(decimal?n:Math.round(n));number.setText(PreferenceNumber.format((float)ruler.getValue(),decimal));
            field.setVisibility(GONE);number.setVisibility(VISIBLE);return true;}
        catch(NumberFormatException ex){field.setError(Lang.t(getContext(),"Введите число в указанном диапазоне","Enter a number in the given range"));return false;}
    }
    /** The value to save: decimals as the ruler holds them, whole numbers rounded. */
    public float result(){return (float)(decimal?ruler.getValue():Math.round(ruler.getValue()));}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    public static BottomSheetDialog show(Context c,String title,double min,double max,double value,double fallback,boolean decimal,Save save){
        return show(c,null,title,min,max,value,fallback,decimal,save);
    }
    /** {@code key}: the setting, for «В шторку» (null: no pin). */
    public static BottomSheetDialog show(Context c,String key,String title,double min,double max,double value,double fallback,boolean decimal,Save save){
        PrecisionEditor editor=new PrecisionEditor(c,min,max,value,fallback,decimal);
        LinearLayout body=SettingsStyle.sheetBody(c,title,key);
        body.addView(editor);
        TextView apply=SettingsStyle.text(c,Lang.t(c,"Применить","Apply"),16,SettingsStyle.INK);
        apply.setTypeface(null,Typeface.BOLD);apply.setGravity(Gravity.CENTER);apply.setPadding(0,editor.dp(15),0,editor.dp(15));
        apply.setBackground(SettingsStyle.shape(c,SettingsStyle.accent(c),0,16));apply.setTag("sheet_save");
        LayoutParams ap=new LayoutParams(-1,-2);ap.topMargin=editor.dp(10);body.addView(apply,ap);
        BottomSheetDialog d=SettingsStyle.sheet(c,body);
        apply.setOnClickListener(v->{if(!editor.commitField())return;save.value(editor.result());d.dismiss();});
        if(d.getWindow()!=null)d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        return d;
    }
}
