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
        caption(" ");
        navigation("▣","Назначение Camera ID","Авто, список камер или ручной ввод",()->open(ModuleLensFragment.create("id")));
        navigation("☷","Отображение и порядок",null,()->open(ModuleLensFragment.create("order")));
        navigation("◇","Названия модулей",null,()->open(ModuleLensFragment.create("names")));
        navigation("▢","Копировать настройки",null,()->open(new ModuleCopyFragment()));
        note("При первом включении используются текущие настройки. При отключении профили сохраняются.");
        navigation("◉","Цветовой акцент",null,()->open(new AccentSettingsFragment()));
        navigation("⚙︎","Дополнительные настройки сенсоров",null,()->{
            SettingsActivity.SettingsFragment extra=new SettingsActivity.SettingsFragment();Bundle args=new Bundle();args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT,"camera_settings_screen");extra.setArguments(args);open(extra);
        });
    }
}
