package com.particlesdevs.photoncamera.ui.settings;

import android.os.Bundle;
import android.hardware.camera2.CameraManager;
import android.content.Context;
import androidx.preference.*;
import androidx.appcompat.app.AlertDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.*;
import java.util.*;

/** Builds the selection tree from the actual settings hierarchy, never a parallel key list. */
public class ModuleCopyFragment extends PreferenceFragmentCompat {
    private PreferenceScreen tree;
    private final Set<String> selected=new HashSet<>(),targets=new HashSet<>();
    private final List<String> ids=new ArrayList<>();
    private String source;
    private final ArrayList<String> path=new ArrayList<>();
    @Override public void onCreatePreferences(Bundle state,String root){
        // Inflate into an isolated file so defaults do not change the active camera.
        getPreferenceManager().setSharedPreferencesName("module_copy_catalog");
        setPreferencesFromResource(R.xml.preferences,null);tree=getPreferenceScreen();
        TunableSettingsManager.ensureTunableClassesRegistered();
        for(Class<?> cls:TunableRegistry.TUNABLE_CLASSES) TunablePreferenceGenerator.registerTunableClass(cls);
        PreferenceScreen tunable=tree.findPreference("pref_tunable_submenu");
        if(tunable!=null)TunablePreferenceGenerator.generatePreferences(requireContext(),tree);
        PreferenceScreen manual=getPreferenceManager().createPreferenceScreen(requireContext());manual.setTitle("Ручные настройки");manual.setKey("manual_copy_group");tree.addPreference(manual);
        String[] manualKeys={"evmodel","shuttermodel","isomodel","whitebalancemodel","focusmodel"};
        String[] manualTitles={"Экспокоррекция","Выдержка","ISO","Баланс белого","Фокус"};
        for(int i=0;i<manualKeys.length;i++){CheckBoxPreference p=new CheckBoxPreference(requireContext());p.setKey("pref_manual_"+manualKeys[i]);p.setTitle(manualTitles[i]);p.setPersistent(false);manual.addPreference(p);}
        source=ModuleRegistry.active();
        for(String id:ModuleRegistry.slots())if(ModuleRegistry.visible(id))ids.add(id);
        if(!ids.contains(source))ids.add(source);
        if(state!=null){source=state.getString("source",source);selected.addAll(state.getStringArrayList("selected"));targets.addAll(state.getStringArrayList("targets"));path.addAll(state.getStringArrayList("path"));}
        else {collect(tree,selected);for(String id:ids)if(!id.equals(source))targets.add(id);}
        render();
    }
    @Override public void onViewCreated(android.view.View view,Bundle state){
        super.onViewCreated(view,state);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),new androidx.activity.OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){if(!path.isEmpty()){path.remove(path.size()-1);render();}else{setEnabled(false);requireActivity().getOnBackPressedDispatcher().onBackPressed();}}
        });
    }
    @Override public void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("source",source);b.putStringArrayList("selected",new ArrayList<>(selected));b.putStringArrayList("targets",new ArrayList<>(targets));b.putStringArrayList("path",path);}
    private void collect(Preference p,Set<String> keys){
        if(p instanceof PreferenceGroup){PreferenceGroup g=(PreferenceGroup)p;for(int i=0;i<g.getPreferenceCount();i++)collect(g.getPreference(i),keys);}
        else if(p instanceof TwoStatePreference||p instanceof DialogPreference||p.getClass().getSimpleName().contains("SeekBar")){if(ModuleProfiles.isLocal(p.getKey()))keys.add(p.getKey());}
    }
    private PreferenceGroup current(){Preference p=tree;for(String key:path)p=((PreferenceGroup)p).getPreference(Integer.parseInt(key));return (PreferenceGroup)p;}
    private Preference row(String title,String summary,Runnable action){Preference p=new Preference(requireContext());p.setTitle(title);p.setSummary(summary);p.setIconSpaceReserved(false);p.setOnPreferenceClickListener(v->{action.run();return true;});getPreferenceScreen().addPreference(p);return p;}
    private String label(String id){return ModuleRegistry.label(id)+" · ID "+ModuleRegistry.camera(id);}
    private void render(){
        PreferenceGroup group=current();setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
        row(path.isEmpty()?"Копирование настроек":"‹ "+group.getTitle(),path.isEmpty()?"Выберите источник, получателей и параметры":"Назад к группам",()->{if(!path.isEmpty()){path.remove(path.size()-1);render();}});
        if(path.isEmpty()){
            row("Источник: "+label(source),"Выбрать модуль",()->new AlertDialog.Builder(requireContext()).setTitle("Источник").setItems(ids.stream().map(this::label).toArray(String[]::new),(d,i)->{source=ids.get(i);targets.remove(source);render();}).show());
            row("Получатели: "+targets.size(),"Можно выбрать несколько модулей",()->{
                List<String> list=new ArrayList<>(ids);list.remove(source);boolean[] checked=new boolean[list.size()];for(int i=0;i<list.size();i++)checked[i]=targets.contains(list.get(i));
                new AlertDialog.Builder(requireContext()).setTitle("Копировать в модули").setMultiChoiceItems(list.stream().map(this::label).toArray(String[]::new),checked,(d,i,on)->checked[i]=on).setPositiveButton("Готово",(d,w)->{targets.clear();for(int i=0;i<list.size();i++)if(checked[i])targets.add(list.get(i));render();}).setNegativeButton("Отмена",null).show();});
        }
        row("Выбрать всё",null,()->{collect(group,selected);render();});
        row("Отменить выбор",null,()->{Set<String> keys=new HashSet<>();collect(group,keys);selected.removeAll(keys);render();});
        for(int i=0;i<group.getPreferenceCount();i++){
            final int index=i; Preference original=group.getPreference(i);Set<String> keys=new HashSet<>();collect(original,keys);
            if(original instanceof PreferenceGroup){
                long count=keys.stream().filter(selected::contains).count();
                Preference p=new Preference(requireContext()) {
                    @Override public void onBindViewHolder(PreferenceViewHolder holder){
                        super.onBindViewHolder(holder);
                        android.widget.CheckBox box=(android.widget.CheckBox)holder.findViewById(R.id.module_group_check);
                        box.setOnCheckedChangeListener(null);box.setChecked(!keys.isEmpty()&&selected.containsAll(keys));box.setEnabled(!keys.isEmpty());
                        box.setButtonTintList(android.content.res.ColorStateList.valueOf(count>0&&count<keys.size()?0xFFFFD447:0xFFC5B8F2));
                        box.setContentDescription((count>0&&count<keys.size()?"Частично выбрана: ":"Выбрать группу ")+original.getTitle());
                        box.setOnCheckedChangeListener((v,on)->{if(on)selected.addAll(keys);else selected.removeAll(keys);render();});
                    }
                };
                p.setTitle(original.getTitle()+"  ›");p.setSummary(keys.isEmpty()?"Общие или аппаратные параметры":"Выбрано "+count+" из "+keys.size());
                p.setIconSpaceReserved(false);p.setWidgetLayoutResource(R.layout.module_group_checkbox);
                p.setOnPreferenceClickListener(v->{path.add(String.valueOf(index));render();return true;});getPreferenceScreen().addPreference(p);
            }else if(!keys.isEmpty()){
                CheckBoxPreference p=new CheckBoxPreference(requireContext());p.setPersistent(false);p.setTitle(original.getTitle());p.setChecked(selected.contains(original.getKey()));p.setIconSpaceReserved(false);
                p.setOnPreferenceChangeListener((v,value)->{if((Boolean)value)selected.add(original.getKey());else selected.remove(original.getKey());return true;});getPreferenceScreen().addPreference(p);
            }
        }
        if(!path.isEmpty())row("Готово",null,()->{path.remove(path.size()-1);render();});
        else {Preference apply=row("Копировать в "+targets.size()+" модуля","Невыбранные параметры останутся прежними",()->{
            PreferenceKeys.profiles().copy(source,targets,selected);PhotonCamera.getSettings().loadCache();
            new AlertDialog.Builder(requireContext()).setMessage("Выбранные настройки скопированы").setPositiveButton("Готово",null).show();});apply.setEnabled(!targets.isEmpty()&&!selected.isEmpty());}
    }
}
