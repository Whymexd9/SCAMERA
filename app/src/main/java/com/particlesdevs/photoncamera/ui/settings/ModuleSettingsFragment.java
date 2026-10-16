package com.particlesdevs.photoncamera.ui.settings;

import android.os.Bundle;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.widget.*;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.PreferenceFragmentCompat;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.*;
import com.particlesdevs.photoncamera.util.Lang;

public class ModuleSettingsFragment extends ModuleConceptFragment {
    @Override protected void render(){
        page(Lang.t(getContext(),"Камеры и сенсоры","Cameras and sensors"),null);
        // P132 (owner, 2026-10-15: a simple, logical order): the modules first - rear by zoom, front, hidden ones last -, then
        // the per-module profiles, then the expert RAW / sensor page. Name, Camera ID, zoom / sensor crop, the viewfinder
        // button and the sensor page of a module live on its own page.
        // P148 (owner, 2026-10-16: simpler for an ordinary user): what a module is, in one line; adding a copy with a crop is an
        // action of the page, not only of a module page
        note(Lang.t(getContext(),"Модуль — это кнопка зума в видоискателе: объектив, его зум и свои настройки. Нажмите модуль, чтобы его настроить.","A module is a zoom button of the viewfinder: a lens, its zoom and its own settings. Tap a module to set it up."));
        java.util.List<String> slots=ModuleRegistry.slots();
        slots.sort(java.util.Comparator.comparingDouble(ModuleRegistry::zoom));
        java.util.List<String> rear=new java.util.ArrayList<>(),front=new java.util.ArrayList<>(),hidden=new java.util.ArrayList<>();
        int free=0;
        for(String slot:slots){
            if(ModuleRegistry.freeSlot(slot))free++; // P148: an untouched filler slot is room for a copy, not a module to set up
            else if(!ModuleRegistry.visible(slot))hidden.add(slot);
            else if(slot.startsWith("front"))front.add(slot);
            else rear.add(slot);
        }
        if(!rear.isEmpty()){caption(Lang.t(getContext(),"Основные камеры","Rear cameras"));for(String slot:rear)moduleRow(slot);}
        if(!front.isEmpty()){caption(Lang.t(getContext(),"Фронтальная камера","Front camera"));for(String slot:front)moduleRow(slot);}
        if(slots.isEmpty())note(Lang.t(getContext(),"Откройте видоискатель, чтобы определить доступные модули камеры.","Open the viewfinder to detect the available camera modules."));
        else navigation("⧉",Lang.t(getContext(),"Добавить модуль с кропом","Add a module with a crop"),Lang.t(getContext(),"Копия объектива со своим кропом и настройками — например, 1.3× от 1×","A copy of a lens with its own crop and settings — e.g. 1.3× of 1×")
            +(free>0?Lang.t(getContext()," · свободных мест: "+free," · free places: "+free):""),()->addCopy(rear,front));
        if(!hidden.isEmpty()){caption(Lang.t(getContext(),"Скрытые модули","Hidden modules"));for(String slot:hidden)moduleRow(slot);}
        caption(Lang.t(getContext(),"Профили модулей","Module profiles"));
        LinearLayout c=card(),r=row();r.setPadding(dp(12),dp(16),dp(10),dp(16));
        ImageView gear=icon("gear");gear.setPadding(0,0,dp(12),0);r.addView(gear,new LinearLayout.LayoutParams(dp(40),dp(28)));
        LinearLayout labels=column();TextView head=text(Lang.t(getContext(),"Свои настройки для каждого модуля","Separate settings for each module"),15,TEXT);
        com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.title(head);labels.addView(head);
        TextView sub=text(Lang.t(getContext(),"При смене объектива применяется его профиль","Switching lenses applies the lens profile"),12,SettingsStyle.SUMMARY);sub.setLineSpacing(dp(4),1);labels.addView(sub,space(-1,-2,8));r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        SwitchCompat toggle=new SwitchCompat(requireContext());toggle.setContentDescription(Lang.t(getContext(),"Свои настройки для каждого модуля","Separate settings for each module"));toggle.setChecked(PreferenceKeys.isPerLensSettingsOn());toggle.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{accent,0xFF606570}));
        r.addView(toggle,new LinearLayout.LayoutParams(dp(56),dp(48)));c.addView(r);
        toggle.setOnCheckedChangeListener((v,on)->{PreferenceKeys.profiles();ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,on).apply();render();});
        TextView scope=text((PreferenceKeys.isPerLensSettingsOn()?Lang.t(getContext(),"Настраивается: ","Editing: "):Lang.t(getContext(),"Текущий модуль: ","Current module: "))+ModuleRegistry.label(ModuleRegistry.active())+" · ID "+ModuleRegistry.camera(ModuleRegistry.active()),12,accent);
        scope.setPadding(dp(16),dp(10),dp(16),dp(10));scope.setBackground(shape(0x25000000|(accent&0x00FFFFFF),0,20));body.addView(scope,space(-2,-2,10));
        note(Lang.t(getContext(),"При первом включении используются текущие настройки. При отключении профили сохраняются.","When first turned on, the current settings are used. When turned off, the profiles are kept."));
        navigation("▢",Lang.t(getContext(),"Копировать настройки между модулями","Copy settings between modules"),Lang.t(getContext(),"Выбор модулей, групп и отдельных параметров","Choose modules, groups and single parameters"),()->open(new ModuleCopyFragment()));
        caption(Lang.t(getContext(),"Для опытных","Advanced"));
        navigation("⚙︎",Lang.t(getContext(),"RAW и сенсор","RAW and sensor"),Lang.t(getContext(),"Уровни RAW, формат потока, фильтр Байера, модель шума","RAW levels, stream format, Bayer filter, noise model"),()->{
            SettingsActivity.SettingsFragment extra=new SettingsActivity.SettingsFragment();Bundle args=new Bundle();args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT,"capture_settings_screen");extra.setArguments(args);open(extra);
        });
    }

    /** P148: «Добавить модуль с кропом»: which module to copy (the shown ones), then its crop. */
    private void addCopy(java.util.List<String> rear,java.util.List<String> front){
        java.util.List<String> sources=new java.util.ArrayList<>(rear);sources.addAll(front);
        if(sources.isEmpty())return;
        CharSequence[] labels=new CharSequence[sources.size()],tags=new CharSequence[sources.size()];
        for(int i=0;i<sources.size();i++){String s=sources.get(i);labels[i]=ModuleRegistry.label(s)+" · ID "+ModuleRegistry.camera(s);tags[i]="source_"+s;}
        SettingsStyle.optionSheet(requireContext(),Lang.t(getContext(),"Какой модуль скопировать","Which module to copy"),labels,tags,-1,accent,i->{
            String source=sources.get(i);
            cropSheet(source,1.3f,crop->copyWithCrop(source,crop));
        }).show();
    }

    /** One module: zoom, sensor crop, sensor mode, duplicate, hidden; opens its page. */
    private void moduleRow(String slot){
        boolean shown=ModuleRegistry.visible(slot);
        String dup=ModuleRegistry.duplicateOf(slot);
        navigation(shown?"●":"○",ModuleRegistry.label(slot)+" · ID "+ModuleRegistry.camera(slot),
            String.format(java.util.Locale.US,Lang.t(getContext(),"Зум %.2f×","Zoom %.2f×"),ModuleRegistry.zoom(slot)).replaceAll("\\.?0+×","×")
            +(ModuleRegistry.sensorCrop(slot)?Lang.t(getContext()," · кроп на сенсоре"," · sensor crop"):"")+(ModuleRegistry.sensorMode(slot)!=0?Lang.t(getContext()," · сенсормод "," · sensor mode ")+ModuleRegistry.sensorMode(slot):"")
            +(dup!=null?Lang.t(getContext()," · копия «"+ModuleRegistry.label(dup)+"»"," · copy of “"+ModuleRegistry.label(dup)+"”"):"")
            +(ModuleRegistry.crop(slot)>1.0001f?Lang.t(getContext()," · кроп "+cropText(ModuleRegistry.crop(slot))+"×"," · crop "+cropText(ModuleRegistry.crop(slot))+"×"):"")+(shown?"":Lang.t(getContext()," · скрыт"," · hidden")),
            ()->open(ModuleLensFragment.module(slot)));
    }
}
