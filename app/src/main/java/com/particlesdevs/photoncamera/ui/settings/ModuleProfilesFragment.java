package com.particlesdevs.photoncamera.ui.settings;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.*;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * P189 (owner, 2026-10-17: «Я так и не увидел где я могу настраивать отдельно 50мп и 200мп, сделай отдельную группу настроек где
 * все будет разделено по модулям»): «Настройки по модулям» lists each module and, under it, its FULLRES 50 / 200 MP profiles; a
 * row opens the settings pages with only that profile's values (SettingsFragment.modulePage). The camera keeps its module:
 * leaving the group gives the main settings back to the camera's profile.
 */
public class ModuleProfilesFragment extends ModuleConceptFragment {
    @Override public void onViewCreated(View view,Bundle state){
        super.onViewCreated(view,state);
        // live only while the group is on top (going deeper keeps the edited profile); header and system back both pass here
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),new OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){ModuleRegistry.syncProfile();setEnabled(false);requireActivity().getOnBackPressedDispatcher().onBackPressed();}
        });
    }
    @Override protected void render(){
        page(Lang.t(getContext(),"Настройки по модулям","Settings per module"),null);
        boolean on=PreferenceKeys.isPerLensSettingsOn();
        note(on?Lang.t(getContext(),"Нажмите модуль или его снимки 50 / 200 Мп — откроются только их настройки. Формат, качество, звуки, сетка и водяной знак общие и остаются на обычных страницах.","Tap a module or its 50 / 200 MP shots to open only their settings. Format, quality, sounds, grid and watermark are shared and stay on the usual pages.")
            :Lang.t(getContext(),"Сейчас все модули снимают с общими настройками. Включите «Свои настройки для каждого модуля», чтобы настроить каждый объектив и его 50 / 200 Мп отдельно.","All modules shoot with the common settings now. Turn on “Separate settings for each module” to set up each lens and its 50 / 200 MP shots on their own."));
        perLensSwitch();
        // P218: moved here from «Камеры и сенсоры» (the group is the only place of the module profiles now)
        note(Lang.t(getContext(),"При первом включении используются текущие настройки. При отключении профили сохраняются.","When first turned on, the current settings are used. When turned off, the profiles are kept."));
        java.util.List<String> ids=SettingsActivity.SettingsFragment.editableModules();
        String inCamera=FullResProfiles.profileFor(ModuleRegistry.active());
        boolean rear=false,front=false,variants=false;LinearLayout card=null;
        for(String id:ids){
            if(FullResProfiles.isVariant(id)){
                variants=true;
                if(card==null)card=card();else divider(card);
                profileRow(card,id,on,inCamera);continue;
            }
            if(!rear&&!id.startsWith("front")){rear=true;caption(Lang.t(getContext(),"Основные камеры","Rear cameras"));}
            if(!front&&id.startsWith("front")){front=true;caption(Lang.t(getContext(),"Фронтальная камера","Front camera"));}
            card=card();profileRow(card,id,on,inCamera);
        }
        if(ids.isEmpty())note(Lang.t(getContext(),"Откройте видоискатель, чтобы определить доступные модули камеры.","Open the viewfinder to detect the available camera modules."));
        else if(!variants)note(Lang.t(getContext(),"Снимки 50 / 200 Мп появятся здесь у модулей с кнопкой FULLRES. Если кнопка ещё не показывалась, откройте этот модуль в камере.","50 / 200 MP shots appear here for modules with the FULLRES button. If the button has not shown yet, open that module in the camera."));
        navigation("▢",Lang.t(getContext(),"Копировать настройки между модулями","Copy settings between modules"),Lang.t(getContext(),"Выбор модулей, групп и отдельных параметров","Choose modules, groups and single parameters"),()->open(new ModuleCopyFragment()));
    }

    /** One profile: a module («Обычные снимки» when it has FULLRES ones) or its FULLRES 50 / 200 MP shots; opens its pages. */
    private void profileRow(LinearLayout card,String id,boolean on,String inCamera){
        boolean variant=FullResProfiles.isVariant(id);String slot=FullResProfiles.slotOf(id);
        String title=variant?FullResProfiles.label(id):FullResProfiles.label(id)+" · ID "+ModuleRegistry.camera(slot);
        String summary;
        if(variant){
            String size=FullResProfiles.size(id),module=ModuleRegistry.label(slot);
            summary=Lang.t(getContext(),"Снимки FULLRES "+size,"FULLRES "+size+" shots");
            if(!PreferenceKeys.profiles().stored(id))summary+=Lang.t(getContext()," · пока как у «"+module+"»"," · same as “"+module+"” until changed");
        }else summary=FullResProfiles.variants(slot).isEmpty()?Lang.t(getContext(),"Все снимки модуля","Every shot of the module"):Lang.t(getContext(),"Обычные снимки","Regular shots");
        if(on&&id.equals(inCamera))summary+=Lang.t(getContext()," · сейчас в камере"," · in the camera now");
        LinearLayout r=row();r.setPadding(dp(12),dp(14),dp(12),dp(14));r.setMinimumHeight(dp(56));
        ImageView ic=icon(variant?"▦":"▣");ic.setPadding(0,0,dp(12),0);r.addView(ic,new LinearLayout.LayoutParams(dp(38),dp(28)));
        LinearLayout labels=column();TextView head=text(title,15,TEXT);com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens.title(head);labels.addView(head);
        TextView sub=text(summary,12,SettingsStyle.SUMMARY);sub.setLineSpacing(dp(3),1);labels.addView(sub,space(-1,-2,6));
        r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));r.addView(text("›",25,MUTED));
        r.setAlpha(on?1:.55f);r.setTag("profile_"+id);r.setContentDescription(title+". "+summary);r.setFocusable(true);
        r.setOnClickListener(v->edit(id));card.addView(r);
    }

    /** The profile's pages (the page takes the profile itself); with per-module settings off, they are turned on first. */
    private void edit(String id){
        if(!PreferenceKeys.isPerLensSettingsOn()){askPerLens(id);return;}
        open(SettingsActivity.SettingsFragment.modulePage(id,null));
    }

    /** P193: the settings changed in the common ones go to every module when they are turned on (ModuleProfiles.carry). */
    private void askPerLens(String id){
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(Lang.t(getContext(),"Свои настройки для каждого модуля","Separate settings for each module"))
            .setMessage(Lang.t(getContext(),"Сейчас все модули и снимки 50 / 200 Мп снимают с общими настройками. Включить свои настройки для каждого модуля? Каждый модуль начнёт с текущих настроек; свои значения модуля вернутся только там, где вы не меняли общие настройки.","All modules and 50 / 200 MP shots use the common settings now. Turn on separate settings for each module? Every module starts from the current settings; a module's own values come back only where you have not changed the common settings."))
            .setNegativeButton(Lang.t(getContext(),"Отмена","Cancel"),null)
            .setPositiveButton(Lang.t(getContext(),"Включить","Turn on"),(d,w)->{
                // commit: the profile listener (changed()) runs now, on this thread, before the page takes its profile
                PreferenceKeys.profiles();
                ScameraApp.getSettingsManagerStatic().getDefaultPreferences().edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,true).commit();
                if(root!=null)render();
                edit(id);
            }).show();
    }
}
