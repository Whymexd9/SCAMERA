package com.particlesdevs.photoncamera.ui.settings;

import android.os.Bundle;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.widget.*;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.PreferenceFragmentCompat;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.*;
import com.particlesdevs.photoncamera.util.Lang;

public class ModuleSettingsFragment extends ModuleConceptFragment {
    @Override protected void render(){
        page(Lang.t(getContext(),"Камеры и сенсоры","Cameras and sensors"),null);
        LinearLayout c=card(),r=row();r.setPadding(dp(12),dp(16),dp(10),dp(16));
        ImageView gear=icon("gear");gear.setPadding(0,0,dp(12),0);r.addView(gear,new LinearLayout.LayoutParams(dp(40),dp(28)));
        LinearLayout labels=column();labels.addView(text(Lang.t(getContext(),"Отдельные настройки модулей","Per-module settings"),15,TEXT));
        TextView sub=text(Lang.t(getContext(),"При смене объектива применяется его профиль","Switching lenses applies the lens profile"),12,MUTED);sub.setLineSpacing(dp(4),1);labels.addView(sub,space(-1,-2,8));r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        SwitchCompat toggle=new SwitchCompat(requireContext());toggle.setContentDescription(Lang.t(getContext(),"Отдельные настройки модулей","Per-module settings"));toggle.setChecked(PreferenceKeys.isPerLensSettingsOn());toggle.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{accent,0xFF606570}));
        r.addView(toggle,new LinearLayout.LayoutParams(dp(56),dp(48)));c.addView(r);
        toggle.setOnCheckedChangeListener((v,on)->{PreferenceKeys.profiles();PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,on).apply();render();});
        TextView scope=text((PreferenceKeys.isPerLensSettingsOn()?Lang.t(getContext(),"Настраивается: ","Editing: "):Lang.t(getContext(),"Текущий модуль: ","Current module: "))+ModuleRegistry.label(ModuleRegistry.active())+" · ID "+ModuleRegistry.camera(ModuleRegistry.active()),12,accent);
        scope.setPadding(dp(16),dp(10),dp(16),dp(10));scope.setBackground(shape(0x25000000|(accent&0x00FFFFFF),0,20));body.addView(scope,space(-2,-2,10));
        note(Lang.t(getContext(),"При первом включении используются текущие настройки. При отключении профили сохраняются.","When first turned on, the current settings are used. When turned off, the profiles are kept."));
        // P21: one page per module (button), in the order of the zoom bar; hidden ones after the shown ones
        java.util.List<String> slots=ModuleRegistry.slots();
        slots.sort(java.util.Comparator.comparing((String id)->id.startsWith("front")).thenComparing(id->!ModuleRegistry.visible(id)).thenComparingDouble(ModuleRegistry::zoom));
        caption(Lang.t(getContext(),"Модули","Modules"));
        boolean hiddenCaption=false;
        for(String slot:slots){
            boolean shown=ModuleRegistry.visible(slot);
            if(!shown&&ModuleRegistry.duplicateOf(slot)==null){if(!hiddenCaption){caption(Lang.t(getContext(),"Скрытые слоты","Hidden slots"));hiddenCaption=true;}}
            String dup=ModuleRegistry.duplicateOf(slot);
            navigation(shown?"●":"○",ModuleRegistry.label(slot)+" · ID "+ModuleRegistry.camera(slot),
                String.format(java.util.Locale.US,Lang.t(getContext(),"Зум %.2f×","Zoom %.2f×"),ModuleRegistry.zoom(slot)).replaceAll("\\.?0+×","×")
                +(ModuleRegistry.sensorCrop(slot)?Lang.t(getContext()," · кроп на сенсоре"," · sensor crop"):"")+(ModuleRegistry.sensorMode(slot)!=0?Lang.t(getContext()," · сенсормод "," · sensor mode ")+ModuleRegistry.sensorMode(slot):"")
                +(dup!=null?Lang.t(getContext()," · дубликат «"+ModuleRegistry.label(dup)+"»"," · duplicate of “"+ModuleRegistry.label(dup)+"”"):"")+(shown?"":Lang.t(getContext()," · скрыт"," · hidden")),
                ()->open(ModuleLensFragment.module(slot)));
        }
        if(slots.isEmpty())note(Lang.t(getContext(),"Откройте видоискатель, чтобы определить доступные модули камеры.","Open the viewfinder to detect the available camera modules."));
        // Name, Camera ID, zoom / sensor crop and the viewfinder button live on each module's page only (P21 listed
        // them a second time as «Все модули сразу» bulk pages; the module rows above already show zoom, crop and «скрыт»).
        caption(Lang.t(getContext(),"Сенсоры","Sensors"));
        navigation("⚙︎",Lang.t(getContext(),"Сенсоры и вендорные ключи","Sensors and vendor keys"),Lang.t(getContext(),"Цветовой метод; уровни RAW, экспозиция, стабилизация и vendor tags каждого модуля","Colour method; RAW levels, exposure, stabilization and vendor tags of each module"),()->{
            SettingsActivity.SettingsFragment extra=new SettingsActivity.SettingsFragment();Bundle args=new Bundle();args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT,"camera_settings_screen");extra.setArguments(args);open(extra);
        });
        caption(Lang.t(getContext(),"Профили","Profiles"));
        navigation("▢",Lang.t(getContext(),"Копировать настройки между модулями","Copy settings between modules"),Lang.t(getContext(),"Выбор модулей, групп и отдельных параметров","Choose modules, groups and single parameters"),()->open(new ModuleCopyFragment()));
    }
}
