package com.particlesdevs.photoncamera.ui.settings;

import android.os.Bundle;
import android.widget.*;
import androidx.preference.*;
import com.particlesdevs.photoncamera.settings.FavoriteSettings;
import java.util.*;

public class FavoritesSettingsFragment extends ModuleConceptFragment {
    private FavoriteSettings catalogue;
    private final ArrayList<String> path=new ArrayList<>();
    @Override public void onCreate(Bundle b){super.onCreate(b);catalogue=new FavoriteSettings(requireContext());if(b!=null&&b.getStringArrayList("path")!=null)path.addAll(b.getStringArrayList("path"));}
    @Override public void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putStringArrayList("path",path);}
    @Override public void onViewCreated(android.view.View v,Bundle b){
        super.onViewCreated(v,b);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),new androidx.activity.OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){if(!path.isEmpty()){path.remove(path.size()-1);render();}else{setEnabled(false);requireActivity().getOnBackPressedDispatcher().onBackPressed();}}
        });
    }
    private void toggle(String key){List<String> selected=FavoriteSettings.selected();if(!selected.remove(key))selected.add(key);FavoriteSettings.selected(selected);render();}
    private void move(String key,int delta){List<String> selected=FavoriteSettings.selected();int i=selected.indexOf(key),j=i+delta;if(i>=0&&j>=0&&j<selected.size()){Collections.swap(selected,i,j);FavoriteSettings.selected(selected);render();}}
    private boolean contains(Preference p){if(p instanceof PreferenceGroup){PreferenceGroup g=(PreferenceGroup)p;for(int i=0;i<g.getPreferenceCount();i++)if(contains(g.getPreference(i)))return true;return false;}return catalogue.entries.containsKey(p.getKey());}
    @Override protected void render(){
        PreferenceGroup group=catalogue.tree;
        try {for(String step:path)group=(PreferenceGroup)group.getPreference(Integer.parseInt(step));}
        catch(RuntimeException ex){path.clear();group=catalogue.tree;}
        page(path.isEmpty()?"Избранное":String.valueOf(group.getTitle()),"Быстрый доступ в видоискателе");
        List<String> selected=FavoriteSettings.selected();
        if(path.isEmpty()){
            for(String key:selected){FavoriteSettings.Entry e=catalogue.entries.get(key);if(e==null)continue;LinearLayout r=row();TextView t=text(e.title,14,TEXT);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));r.addView(button("↑",false,()->move(key,-1)));r.addView(button("↓",false,()->move(key,1)));r.addView(button("×",false,()->toggle(key)));card().addView(r);}
            caption("Добавить параметры");
        }else navigation("back","Назад к группам",null,()->{path.remove(path.size()-1);render();});
        for(int i=0;i<group.getPreferenceCount();i++){
            final int index=i;Preference p=group.getPreference(i);if(!contains(p))continue;
            if(p instanceof PreferenceGroup){navigation("☷",String.valueOf(p.getTitle()),null,()->{path.add(String.valueOf(index));render();});}
            else{boolean on=selected.contains(p.getKey());LinearLayout r=row();r.addView(mark(on?2:0,String.valueOf(p.getTitle()),()->toggle(p.getKey())),new LinearLayout.LayoutParams(dp(48),dp(48)));TextView label=text(String.valueOf(p.getTitle()),14,TEXT);label.setPadding(0,dp(10),dp(12),dp(10));r.addView(label,new LinearLayout.LayoutParams(0,-2,1));r.setOnClickListener(v->toggle(p.getKey()));card().addView(r);}
        }
        note("Порядок избранного общий. Значения берутся из активного профиля модуля. Изменения применяются после подтверждения.");
    }
}
