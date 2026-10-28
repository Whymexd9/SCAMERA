package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.graphics.*;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Lang;

/** Shared native layout for the approved module-settings mockup. */
public abstract class ModuleConceptFragment extends Fragment {
    protected static final int BG=SettingsStyle.BG,CARD=SettingsStyle.CARD,TEXT=SettingsStyle.TEXT,MUTED=SettingsStyle.MUTED,LINE=SettingsStyle.LINE;
    protected LinearLayout root,body;
    protected FrameLayout footer;
    protected TextView heading,subtitle;
    protected int accent;
    protected abstract void render();
    protected int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public View onCreateView(LayoutInflater i,ViewGroup p,Bundle b){
        accent=AccentPalette.color(requireContext());
        root=column();root.setBackgroundColor(BG);root.setPadding(dp(16),dp(12),dp(16),dp(16));
        SettingsStyle.Header header=SettingsStyle.header(requireContext(),this::back,null); // P6b: the header shared with the preference pages
        heading=header.heading;subtitle=header.subtitle;root.addView(header.view,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(requireContext());scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));body=column();body.setPadding(0,dp(4),0,dp(18));scroll.addView(body);
        footer=new FrameLayout(requireContext());footer.setPadding(0,dp(12),0,0);root.addView(footer,new LinearLayout.LayoutParams(-1,-2));
        render();return root;
    }
    @Override public void onResume(){super.onResume();View toolbar=requireActivity().findViewById(R.id.settings_toolbar);if(toolbar!=null)toolbar.setVisibility(View.GONE);if(root!=null){accent=AccentPalette.color(requireContext());render();}}
    @Override public void onDestroyView(){root=null;super.onDestroyView();}
    protected void back(){requireActivity().getOnBackPressedDispatcher().onBackPressed();}
    protected void page(String title,String sub){heading.setText(title);subtitle.setText(sub);subtitle.setVisibility(sub==null||sub.isEmpty()?View.GONE:View.VISIBLE);body.removeAllViews();footer.removeAllViews();}
    protected LinearLayout column(){LinearLayout v=new LinearLayout(requireContext());v.setOrientation(LinearLayout.VERTICAL);return v;}
    protected LinearLayout row(){LinearLayout v=new LinearLayout(requireContext());v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    protected LinearLayout.LayoutParams space(int w,int h,int top){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(w,h);lp.topMargin=dp(top);return lp;}
    protected TextView text(String value,float size,int color){TextView t=new TextView(requireContext());t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");t.setIncludeFontPadding(false);return t;}
    protected GradientDrawable shape(int color,int stroke,int radius){return SettingsStyle.shape(requireContext(),color,stroke,radius);}
    protected LinearLayout card(){LinearLayout c=column();c.setBackground(shape(CARD,LINE,12));c.setPadding(dp(2),dp(2),dp(2),dp(2));body.addView(c,space(-1,-2,10));return c;}
    protected void caption(String title){TextView t=text(title,13,MUTED);body.addView(t,space(-1,-2,18));}
    protected void note(String value){LinearLayout n=row();TextView icon=text("ⓘ",18,MUTED);n.addView(icon,new LinearLayout.LayoutParams(dp(28),dp(36)));TextView label=text(value,12,MUTED);label.setLineSpacing(dp(3),1);n.addView(label,new LinearLayout.LayoutParams(0,-2,1));body.addView(n,space(-1,-2,16));}
    protected TextView button(String title,boolean filled,Runnable action){TextView t=text(title,14,filled?0xFF15131D:accent);t.setGravity(Gravity.CENTER);t.setPadding(dp(8),dp(12),dp(8),dp(12));t.setMinHeight(dp(44));t.setBackground(shape(filled?accent:BG,filled?0:0xFF626974,10));t.setOnClickListener(v->action.run());t.setFocusable(true);t.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.Button");}});return t;}
    protected void actions(Runnable all,Runnable none){LinearLayout r=row();TextView a=button(Lang.t(getContext(),"Выбрать всё","Select all"),false,all),b=button(Lang.t(getContext(),"Отменить выбор","Clear selection"),false,none);a.setTag("select_all");b.setTag("clear_selection");LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.rightMargin=dp(10);r.addView(a,lp);r.addView(b,new LinearLayout.LayoutParams(0,-2,1));body.addView(r,space(-1,-2,12));}
    protected void primary(String title,boolean enabled,Runnable action){TextView t=button(title,true,action);t.setTypeface(null,1);t.setMinHeight(dp(52));t.setEnabled(enabled);t.setAlpha(enabled?1:.4f);t.setTag("primary_action");footer.addView(t,new FrameLayout.LayoutParams(-1,-2));}
    protected void divider(LinearLayout parent){View v=new View(requireContext());v.setBackgroundColor(LINE);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.leftMargin=dp(48);parent.addView(v,lp);}
    protected ImageView icon(String name){
        ImageView v=new ImageView(requireContext());int resource;
        switch(name){ // P6b: the concept icon set shared with the preference pages
            case "▣":resource=R.drawable.settings_ic_camera;break;
            case "☷":resource=R.drawable.settings_ic_list;break;
            case "◇":resource=R.drawable.settings_ic_tag;break;
            case "▢":case "⧉":resource=R.drawable.settings_ic_copy;break;
            case "back":resource=R.drawable.settings_ic_back;break;
            case "◉":resource=R.drawable.settings_ic_palette;break;
            case "⌕":resource=R.drawable.settings_ic_zoom;break;
            case "⚙︎":resource=R.drawable.settings_ic_chip;break;
            case "●":resource=R.drawable.settings_ic_eye;break;
            case "○":resource=R.drawable.settings_ic_view;break;
            case "✕":resource=R.drawable.settings_ic_reset;break;
            case "≡":resource=R.drawable.settings_ic_sliders;break; // P189: the module group
            case "▦":resource=R.drawable.settings_ic_mosaic;break; // P189: a FULLRES profile
            default:resource=R.drawable.settings_ic_gear;
        }
        v.setImageResource(resource);v.clearColorFilter(); // P136: multi-tone glyphs, drawn as they are
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return v;
    }
    protected void navigation(String icon,String title,String summary,Runnable action){LinearLayout c=card(),r=row();r.setPadding(dp(12),dp(14),dp(12),dp(14));r.setMinimumHeight(dp(56));ImageView ic=icon(icon);ic.setPadding(0,0,dp(12),0);r.addView(ic,new LinearLayout.LayoutParams(dp(38),dp(28)));LinearLayout labels=column();TextView head=text(title,15,TEXT);com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.title(head);labels.addView(head);if(summary!=null){TextView sub=text(summary,12,SettingsStyle.SUMMARY);sub.setLineSpacing(dp(3),1);labels.addView(sub,space(-1,-2,6));}r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));r.addView(text("›",25,MUTED));r.setOnClickListener(v->action.run());r.setTag(title);c.addView(r);}
    /** P189: «Свои настройки для каждого модуля» (P218: only in the module group, «Настройки по модулям»). */
    protected void perLensSwitch(){
        LinearLayout c=card(),r=row();r.setPadding(dp(12),dp(16),dp(10),dp(16));
        ImageView gear=icon("gear");gear.setPadding(0,0,dp(12),0);r.addView(gear,new LinearLayout.LayoutParams(dp(40),dp(28)));
        LinearLayout labels=column();TextView head=text(Lang.t(getContext(),"Свои настройки для каждого модуля","Separate settings for each module"),15,TEXT);
        com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.title(head);labels.addView(head);
        TextView sub=text(Lang.t(getContext(),"При смене объектива применяется его профиль: стиль, LUT, тепло и обработка — свои у каждого","Switching lenses applies its profile: style, LUT, warmth and processing are its own"),12,SettingsStyle.SUMMARY);sub.setLineSpacing(dp(4),1);labels.addView(sub,space(-1,-2,8));r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        SwitchCompat toggle=new SwitchCompat(requireContext());toggle.setContentDescription(Lang.t(getContext(),"Свои настройки для каждого модуля","Separate settings for each module"));toggle.setChecked(PreferenceKeys.isPerLensSettingsOn());toggle.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{accent,0xFF606570}));
        r.addView(toggle,new LinearLayout.LayoutParams(dp(56),dp(48)));c.addView(r);
        toggle.setOnCheckedChangeListener((v,on)->{PreferenceKeys.profiles();ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,on).apply();render();});
    }
    protected View mark(int state,String label,Runnable action){View v=new View(requireContext()){
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override protected void onDraw(Canvas canvas){float cx=getWidth()/2f,cy=getHeight()/2f,h=dp(10);RectF rect=new RectF(cx-h,cy-h,cx+h,cy+h);paint.setStyle(state==0?Paint.Style.STROKE:Paint.Style.FILL);paint.setStrokeWidth(dp(1.5f));paint.setColor(state==0?MUTED:accent);canvas.drawRoundRect(rect,dp(4),dp(4),paint);
            if(state!=0){paint.setStyle(Paint.Style.STROKE);paint.setColor(0xFF17141F);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);if(state==1)canvas.drawLine(cx-dp(4),cy,cx+dp(4),cy,paint);else{canvas.drawLine(cx-dp(5),cy,cx-dp(1),cy+dp(4),paint);canvas.drawLine(cx-dp(1),cy+dp(4),cx+dp(6),cy-dp(5),paint);}}}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.CheckBox");info.setCheckable(true);info.setChecked(state>0);}
    };v.setContentDescription(label+(state==1?Lang.t(getContext(),", выбрано частично",", partly selected"):""));v.setFocusable(true);v.setOnClickListener(w->action.run());return v;}
    protected void open(Fragment fragment){getParentFragmentManager().beginTransaction().replace(R.id.settings_container,fragment).addToBackStack(null).commit();}
    /** P148: the crop presets of a copy (1 = none). */
    protected static final float[] CROPS={1f,1.2f,1.3f,1.5f,2f};
    protected static String cropText(float v){return String.format(java.util.Locale.US,"%.3f",v).replaceAll("\\.?0+$","");}
    /** P148: the crop of a copy of {@code source}: the presets (the button's zoom beside each) and a typed value. */
    protected void cropSheet(String source,float current,java.util.function.Consumer<Float> onCrop){
        float base=com.particlesdevs.photoncamera.settings.ModuleRegistry.zoom(source);
        CharSequence[] labels=new CharSequence[CROPS.length+1],tags=new CharSequence[CROPS.length+1];int selected=-1;
        for(int i=0;i<CROPS.length;i++){
            float c=CROPS[i];tags[i]="crop_"+cropText(c);
            labels[i]=c==1f?Lang.t(getContext(),"Без кропа · кнопка "+cropText(base)+"×","No crop · button "+cropText(base)+"×")
                :Lang.t(getContext(),"Кроп "+cropText(c)+"× · кнопка "+cropText(base*c)+"×","Crop "+cropText(c)+"× · button "+cropText(base*c)+"×");
            if(Math.abs(c-current)<0.001f)selected=i;
        }
        tags[CROPS.length]="crop_custom";labels[CROPS.length]=Lang.t(getContext(),"Своё значение…","Custom value…");
        SettingsStyle.optionSheet(requireContext(),Lang.t(getContext(),"Кроп копии «"+com.particlesdevs.photoncamera.settings.ModuleRegistry.label(source)+"»","Copy crop of “"+com.particlesdevs.photoncamera.settings.ModuleRegistry.label(source)+"”"),labels,tags,selected,accent,i->{
            if(i<CROPS.length){onCrop.accept(CROPS[i]);return;}
            android.widget.EditText input=DialogStyle.field(new android.widget.EditText(requireContext()));input.setSingleLine(true);input.setTag("crop_value");
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setHint(Lang.t(getContext(),"От 1 до 10, например 1.3","1 to 10, e.g. 1.3"));input.setText(cropText(current));
            DialogStyle.builder(requireContext()).setTitle(Lang.t(getContext(),"Кроп копии","Copy crop")).setView(DialogStyle.body(requireContext(),input))
                .setPositiveButton(Lang.t(getContext(),"Сохранить","Save"),(d,w)->{
                    try{float c=Float.parseFloat(input.getText().toString().trim().replace(',','.'));if(c>=1f&&c<=10f)onCrop.accept(c);}catch(NumberFormatException bad){}
                }).setNegativeButton(Lang.t(getContext(),"Отмена","Cancel"),null).show();
        }).show();
    }
    /** P148: a copy of {@code slot} with {@code crop}; its page opens (a message when the side has no free slot). */
    protected void copyWithCrop(String slot,float crop){
        String copy=com.particlesdevs.photoncamera.settings.ModuleRegistry.duplicate(slot,crop);
        if(copy==null){android.widget.Toast.makeText(requireContext(),Lang.t(getContext(),"Нет свободного слота: скройте или удалите другой модуль","No free slot: hide or delete another module"),android.widget.Toast.LENGTH_LONG).show();return;}
        android.widget.Toast.makeText(requireContext(),Lang.t(getContext(),"Создан «"+com.particlesdevs.photoncamera.settings.ModuleRegistry.label(copy)+"»","Created “"+com.particlesdevs.photoncamera.settings.ModuleRegistry.label(copy)+"”"),android.widget.Toast.LENGTH_SHORT).show();
        getParentFragmentManager().beginTransaction().replace(com.particlesdevs.photoncamera.R.id.settings_container,ModuleLensFragment.module(copy)).addToBackStack("module").commit();
    }
}
