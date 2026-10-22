package com.particlesdevs.photoncamera.ui.settings;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import androidx.preference.PreferenceFragmentCompat;
import com.particlesdevs.photoncamera.settings.*;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * P319 (user report 2026-10-22: «раздел "Камеры и сенсоры" объединить с "Настройки по модулям". По сути это один раздел, где
 * хардварно настраиваем сенсоры на работу со скамом»): one page «Камеры и модули» - the modules (lens, zoom, crop, copies),
 * then their processing profiles (ModuleProfilesFragment.profilesSection, whose back handling keeps the edited profile), then
 * «RAW и сенсор». «Настройки по модулям» of the main page opens this page too.
 */
public class ModuleSettingsFragment extends ModuleProfilesFragment {
    @Override protected void render(){
        page(Lang.t(getContext(),"Камеры и модули","Cameras and modules"),null);
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
        // P218 (owner, 2026-10-18: «группа настроек "Настройки по модулям" дублируется в нескольких местах, исправь чтобы было
        // только на главной странице»): the module profiles (per-module switch, the group, copying between modules) live only in
        // «Настройки по модулям», opened from the main settings page
        // P319: the processing profiles of the modules on the same page (was the separate «Настройки по модулям»)
        caption(Lang.t(getContext(),"Настройки по модулям","Settings per module"));
        profilesSection();
        caption(Lang.t(getContext(),"Для опытных","Advanced"));
        navigation("⚙︎",Lang.t(getContext(),"RAW и сенсор","RAW and sensor"),Lang.t(getContext(),"Уровни RAW, обрезка, формат RAW-потока, фильтр Байера, RAW-метаданные, модель шума","RAW levels, crop, RAW stream format, Bayer filter, RAW metadata, noise model"),()->{
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
            +(com.particlesdevs.photoncamera.capture.SensorModePick.picked(slot)>=0?Lang.t(getContext()," · режим сенсора #"," · sensor mode #")+com.particlesdevs.photoncamera.capture.SensorModePick.picked(slot):"")
            +(dup!=null?Lang.t(getContext()," · копия «"+ModuleRegistry.label(dup)+"»"," · copy of “"+ModuleRegistry.label(dup)+"”"):"")
            +(ModuleRegistry.crop(slot)>1.0001f?Lang.t(getContext()," · кроп "+cropText(ModuleRegistry.crop(slot))+"×"," · crop "+cropText(ModuleRegistry.crop(slot))+"×"):"")+(shown?"":Lang.t(getContext()," · скрыт"," · hidden")),
            ()->open(ModuleLensFragment.module(slot)));
    }
}
