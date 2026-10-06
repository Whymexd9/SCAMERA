package com.particlesdevs.photoncamera.ui.settings;

import android.os.Bundle;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.widget.*;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.PreferenceFragmentCompat;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.*;

public class ModuleSettingsFragment extends ModuleConceptFragment {
    @Override protected void render(){
        page("Камеры и сенсоры",null);
        LinearLayout c=card(),r=row();r.setPadding(dp(12),dp(16),dp(10),dp(16));
        ImageView gear=icon("gear");gear.setPadding(0,0,dp(12),0);r.addView(gear,new LinearLayout.LayoutParams(dp(40),dp(28)));
        LinearLayout labels=column();labels.addView(text("Отдельные настройки модулей",15,TEXT));
        TextView sub=text("При смене объектива применяется его профиль",12,MUTED);sub.setLineSpacing(dp(4),1);labels.addView(sub,space(-1,-2,8));r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        SwitchCompat toggle=new SwitchCompat(requireContext());toggle.setContentDescription("Отдельные настройки модулей");toggle.setChecked(PreferenceKeys.isPerLensSettingsOn());toggle.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked},{}},new int[]{accent,0xFF606570}));
        r.addView(toggle,new LinearLayout.LayoutParams(dp(56),dp(48)));c.addView(r);
        toggle.setOnCheckedChangeListener((v,on)->{PreferenceKeys.profiles();PhotonCamera.getSettingsManagerStatic().getDefaultPreferences().edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,on).apply();render();});
        TextView scope=text((PreferenceKeys.isPerLensSettingsOn()?"Настраивается: ":"Текущий модуль: ")+ModuleRegistry.label(ModuleRegistry.active())+" · ID "+ModuleRegistry.camera(ModuleRegistry.active()),12,accent);
        scope.setPadding(dp(16),dp(10),dp(16),dp(10));scope.setBackground(shape(0x25000000|(accent&0x00FFFFFF),0,20));body.addView(scope,space(-2,-2,10));
        note("При первом включении используются текущие настройки. При отключении профили сохраняются.");
        // P21: one page per module (button), in the order of the zoom bar; hidden ones after the shown ones
        java.util.List<String> slots=ModuleRegistry.slots();
        slots.sort(java.util.Comparator.comparing((String id)->id.startsWith("front")).thenComparing(id->!ModuleRegistry.visible(id)).thenComparingDouble(ModuleRegistry::zoom));
        caption("Модули");
        boolean hiddenCaption=false;
        for(String slot:slots){
            boolean shown=ModuleRegistry.visible(slot);
            if(!shown&&ModuleRegistry.duplicateOf(slot)==null){if(!hiddenCaption){caption("Скрытые слоты");hiddenCaption=true;}}
            String dup=ModuleRegistry.duplicateOf(slot);
            navigation(shown?"●":"○",ModuleRegistry.label(slot)+" · ID "+ModuleRegistry.camera(slot),
                String.format(java.util.Locale.US,"Зум %.2f×",ModuleRegistry.zoom(slot)).replaceAll("\\.?0+×","×")
                +(ModuleRegistry.sensorCrop(slot)?" · кроп на сенсоре":"")+(ModuleRegistry.sensorMode(slot)!=0?" · сенсормод "+ModuleRegistry.sensorMode(slot):"")
                +(dup!=null?" · дубликат «"+ModuleRegistry.label(dup)+"»":"")+(shown?"":" · скрыт"),
                ()->open(ModuleLensFragment.module(slot)));
        }
        if(slots.isEmpty())note("Откройте видоискатель, чтобы определить доступные модули камеры.");
        caption("Все модули сразу");
        navigation("▣","Назначение Camera ID","Авто, список камер или ручной ввод",()->open(ModuleLensFragment.create("id")));
        navigation("⌕","Зум-факторы кнопок","Порог переключения модулей при зуме и кроп на сенсоре",()->open(ModuleLensFragment.create("zoom")));
        navigation("☷","Отображение кнопок","Порядок задаётся зум-фактором",()->open(ModuleLensFragment.create("order")));
        navigation("◇","Названия модулей",null,()->open(ModuleLensFragment.create("names")));
        caption("Сенсоры");
        navigation("⚙︎","Настройки сенсоров и вендорные ключи","Уровни, шум и цвет по модулям, реквесты (Vendor Tags), метод цвета",()->{
            SettingsActivity.SettingsFragment extra=new SettingsActivity.SettingsFragment();Bundle args=new Bundle();args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT,"camera_settings_screen");extra.setArguments(args);open(extra);
        });
        caption("Профили");
        navigation("▢","Копировать настройки между модулями","Выбор модулей, групп и отдельных параметров",()->open(new ModuleCopyFragment()));
    }
}
