package com.particlesdevs.photoncamera.ui.settings;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.preference.*;
import androidx.appcompat.app.AlertDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.*;
import java.util.*;

/** Production preference catalogue rendered as the approved card-based selector. */
public class ModuleCopyFragment extends ModuleConceptFragment {
    private PreferenceScreen tree;
    private final Set<String> selected=new HashSet<>(),targets=new HashSet<>();
    private final List<String> ids=new ArrayList<>();
    private String source;
    private final ArrayList<String> path=new ArrayList<>();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        PreferenceManager pm=new PreferenceManager(requireContext());pm.setSharedPreferencesName("module_copy_catalog");
        tree=pm.inflateFromResource(requireContext(),R.xml.preferences,null);pm.setPreferences(tree);
        TunableSettingsManager.ensureTunableClassesRegistered();
        for(Class<?> cls:TunableRegistry.TUNABLE_CLASSES)TunablePreferenceGenerator.registerTunableClass(cls);
        TunablePreferenceGenerator.generatePreferences(requireContext(),tree);
        PreferenceScreen manual=pm.createPreferenceScreen(requireContext());manual.setTitle("Ручные настройки");manual.setKey("manual_copy_group");
        PreferenceGroup capture=tree.findPreference("capture_settings_screen");capture.addPreference(manual);
        String[] keys={"evmodel","shuttermodel","isomodel","whitebalancemodel","focusmodel"};
        String[] titles={"Экспокоррекция","Выдержка","ISO","Баланс белого","Фокус"};
        for(int i=0;i<keys.length;i++){CheckBoxPreference p=new CheckBoxPreference(requireContext());p.setKey("pref_manual_"+keys[i]);p.setTitle(titles[i]);p.setPersistent(false);manual.addPreference(p);}
        source=ModuleRegistry.active();
        for(String id:ModuleRegistry.slots())if(ModuleRegistry.visible(id))ids.add(id);
        if(!ids.contains(source))ids.add(source);
        if(state!=null){source=state.getString("source",source);restore(selected,state.getStringArrayList("selected"));restore(targets,state.getStringArrayList("targets"));if(state.getStringArrayList("path")!=null)path.addAll(state.getStringArrayList("path"));}
        else{collect(tree,selected);for(String id:ids)if(!id.equals(source))targets.add(id);}
    }
    private void restore(Set<String> set,List<String> values){if(values!=null)set.addAll(values);}
    @Override public void onViewCreated(View view,Bundle state){
        super.onViewCreated(view,state);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),new androidx.activity.OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){if(!path.isEmpty()){path.remove(path.size()-1);render();}else{setEnabled(false);requireActivity().getOnBackPressedDispatcher().onBackPressed();}}
        });
    }
    @Override public void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("source",source);b.putStringArrayList("selected",new ArrayList<>(selected));b.putStringArrayList("targets",new ArrayList<>(targets));b.putStringArrayList("path",new ArrayList<>(path));}
    private void collect(Preference p,Set<String> keys){
        if(p instanceof PreferenceGroup){PreferenceGroup g=(PreferenceGroup)p;for(int i=0;i<g.getPreferenceCount();i++)collect(g.getPreference(i),keys);}
        else if("pref_dcp_profile_key".equals(p.getKey()) || p instanceof TwoStatePreference||p instanceof DialogPreference||p.getClass().getSimpleName().contains("SeekBar")){if(ModuleProfiles.isLocal(p.getKey()))keys.add(p.getKey());}
    }
    private PreferenceGroup current(){Preference p=tree;for(String key:path)p=((PreferenceGroup)p).getPreference(Integer.parseInt(key));return (PreferenceGroup)p;}
    private String label(String id){return ModuleRegistry.label(id)+" · ID "+ModuleRegistry.camera(id);}
    private void toggle(Set<String> keys){if(selected.containsAll(keys))selected.removeAll(keys);else selected.addAll(keys);render();}
    @Override protected void render(){
        PreferenceGroup group=current();boolean home=path.isEmpty();page(home?"Копирование настроек":String.valueOf(group.getTitle()),home?null:"Выбор параметров для копирования");
        if(home){
            caption("Источник");LinearLayout sourceCard=card(),r=row();r.setPadding(dp(14),dp(12),dp(14),dp(12));r.setMinimumHeight(dp(48));ImageView camera=icon("▣");camera.setPadding(0,0,dp(12),0);r.addView(camera,new LinearLayout.LayoutParams(dp(38),dp(26)));r.addView(text("Источник: "+label(source),15,TEXT),new LinearLayout.LayoutParams(0,-2,1));r.addView(text("⌄",20,MUTED));r.setTag("source_module");
            r.setOnClickListener(v->new AlertDialog.Builder(requireContext()).setTitle("Источник").setItems(ids.stream().map(this::label).toArray(String[]::new),(d,i)->{source=ids.get(i);targets.remove(source);render();}).show());sourceCard.addView(r);
            caption("Целевые модули");LinearLayout line=null;int index=0;
            for(String id:ids){if(id.equals(source))continue;if(index++%2==0){line=row();body.addView(line,space(-1,-2,8));}
                boolean on=targets.contains(id);LinearLayout chip=row();chip.setPadding(dp(2),dp(2),dp(8),dp(2));chip.setBackground(shape(on?((accent&0xFFFFFF)|0x28000000):CARD,on?accent:LINE,12));
                Runnable choose=()->{if(targets.contains(id))targets.remove(id);else targets.add(id);render();};chip.addView(mark(on?2:0,label(id),choose),new LinearLayout.LayoutParams(dp(36),dp(40)));chip.addView(text(label(id),13,on?accent:TEXT),new LinearLayout.LayoutParams(0,-2,1));chip.setOnClickListener(v->choose.run());chip.setTag("target_"+id);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(index%2==1)lp.rightMargin=dp(10);line.addView(chip,lp);
            }
            if(index==0)note("Добавьте ещё один модуль в разделе «Назначение Camera ID».");
        }
        actions(()->{collect(group,selected);render();},()->{Set<String> keys=new HashSet<>();collect(group,keys);selected.removeAll(keys);render();});
        if(home){
            caption("Группы параметров");LinearLayout list=card();
            for(int i=0;i<group.getPreferenceCount();i++){
                Preference p=group.getPreference(i);if(!(p instanceof PreferenceGroup)||!p.isVisible())continue;
                Set<String> keys=new HashSet<>();collect(p,keys);final int childIndex=i;
                LinearLayout r=row();r.setPadding(dp(2),0,dp(12),0);r.setMinimumHeight(dp(44));long count=keys.stream().filter(selected::contains).count();
                View check=mark(count==0?0:(count==keys.size()?2:1),p.getTitle()+", выбрано "+count+" из "+keys.size(),()->toggle(keys));check.setEnabled(!keys.isEmpty());check.setAlpha(keys.isEmpty()?.35f:1);check.setTag("group_check_"+p.getKey());r.addView(check,new LinearLayout.LayoutParams(dp(46),dp(44)));
                TextView title=text(String.valueOf(p.getTitle()),14,TEXT);title.setPadding(dp(4),dp(8),dp(4),dp(8));r.addView(title,new LinearLayout.LayoutParams(0,-2,1));r.addView(text("›",23,MUTED));r.setTag("group_"+p.getKey());r.setOnClickListener(v->{path.add(String.valueOf(childIndex));render();((ScrollView)body.getParent()).scrollTo(0,0);});
                if(list.getChildCount()>0)divider(list);list.addView(r);
            }
            note("Общие параметры помечены внутри групп");
            primary("Копировать в "+targets.size()+" "+(targets.size()==1?"модуль":targets.size()<5?"модуля":"модулей"),!targets.isEmpty()&&!selected.isEmpty(),()->{
                PreferenceKeys.profiles().copy(source,targets,selected);if(PhotonCamera.getSettings()!=null)PhotonCamera.getSettings().loadCache();
                new AlertDialog.Builder(requireContext()).setMessage("Выбранные настройки скопированы").setPositiveButton("Готово",null).show();
            });
        }else{
            // Each actual submenu becomes a card; deeper categories remain visible as headings.
            LinearLayout loose=null;
            for(int i=0;i<group.getPreferenceCount();i++){
                Preference p=group.getPreference(i);if(!p.isVisible())continue;
                if(p instanceof PreferenceGroup){LinearLayout c=card();section(c,(PreferenceGroup)p);}
                else{if(loose==null)loose=card();leaf(loose,p);}
            }
            note("Невыбранные параметры останутся прежними");
            primary("Готово",true,()->{path.remove(path.size()-1);render();});
        }
    }
    private void section(LinearLayout card,PreferenceGroup group){
        TextView title=text(String.valueOf(group.getTitle()),15,TEXT);title.setPadding(dp(14),dp(14),dp(14),dp(12));card.addView(title);
        for(int i=0;i<group.getPreferenceCount();i++){Preference p=group.getPreference(i);if(p instanceof PreferenceGroup)section(card,(PreferenceGroup)p);else leaf(card,p);}
    }
    private void leaf(LinearLayout c,Preference p){
        if(!("pref_dcp_profile_key".equals(p.getKey()) || p instanceof TwoStatePreference||p instanceof DialogPreference||p.getClass().getSimpleName().contains("SeekBar")))return;
        boolean local=ModuleProfiles.isLocal(p.getKey());Runnable change=()->{if(selected.contains(p.getKey()))selected.remove(p.getKey());else selected.add(p.getKey());render();};
        LinearLayout r=row();r.setMinimumHeight(dp(44));View box=mark(selected.contains(p.getKey())?2:0,String.valueOf(p.getTitle()),change);box.setEnabled(local);box.setAlpha(local?1:.35f);r.addView(box,new LinearLayout.LayoutParams(dp(46),dp(44)));
        TextView title=text(p.getTitle()+(local?"":" · общее"),14,local?TEXT:MUTED);title.setPadding(dp(4),dp(10),dp(12),dp(10));r.addView(title,new LinearLayout.LayoutParams(0,-2,1));r.setTag("parameter_"+p.getKey());r.setOnClickListener(v->{if(local)change.run();});if(c.getChildCount()>0)divider(c);c.addView(r);
    }
}
