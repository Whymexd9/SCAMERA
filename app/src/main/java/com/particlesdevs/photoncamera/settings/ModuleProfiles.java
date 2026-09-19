package com.particlesdevs.photoncamera.settings;

import android.content.SharedPreferences;
import java.util.*;

/** Typed module snapshots; replacing a snapshot removes absent keys as well. */
public final class ModuleProfiles {
    private final SettingsManager manager;
    private final SharedPreferences prefs, meta;
    private boolean applying;
    private String active;
    public ModuleProfiles(SettingsManager manager) {
        this.manager=manager; prefs=manager.getDefaultPreferences();
        meta=manager.getContext().getSharedPreferences("module_profiles_meta",0);
        active=meta.getString("active",ModuleRegistry.active());
        if(!meta.getBoolean("baseline",false)){write(file("common"),current());meta.edit().putBoolean("baseline",true).apply();}
    }
    public boolean isApplying(){return applying;}
    public static boolean isLocal(String key) {
        if(key==null || key.equals("pref_mfsr_calibrate_key"))return false;
        if(key.equals(PreferenceKeys.Key.CAMERA_ID.mValue)||key.equals(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue))return false;
        if(key.startsWith("lens_")||key.startsWith("module_")||key.startsWith("pref_sensorconfig_")||key.startsWith("settings_")||key.startsWith("pref_theme")||key.contains("debug")||key.contains("folder")||key.contains("config_file"))return false;
        if(key.equals("user_camera_ids")||key.equals("hidden_camera_ids")||key.equals(PreferenceKeys.Key.CAMERA_MODE.mValue))return false;
        return key.startsWith("pref_") || key.startsWith("hexquad_") || key.startsWith("rt512_") || key.startsWith("scamera_");
    }
    private SharedPreferences file(String id){return manager.getContext().getSharedPreferences("module_profile_v2_"+id,0);}
    public static void put(SharedPreferences.Editor e,String k,Object v){
        if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);
        else if(v instanceof Integer)e.putInt(k,(Integer)v);
        else if(v instanceof Long)e.putLong(k,(Long)v);
        else if(v instanceof Float)e.putFloat(k,(Float)v);
        else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));
        else if(v!=null)e.putString(k,v.toString());
    }
    private Map<String,Object> current(){Map<String,Object> out=new HashMap<>();prefs.getAll().forEach((k,v)->{if(isLocal(k))out.put(k,v);});return out;}
    private void save(String id){write(file(id),current());meta.edit().putBoolean("exists_"+id,true).apply();}
    private void write(SharedPreferences target,Map<String,?> values){SharedPreferences.Editor e=target.edit().clear();values.forEach((k,v)->put(e,k,v));e.apply();}
    public synchronized Map<String,?> snapshot(String id){
        if(PreferenceKeys.isPerLensSettingsOn()&&id.equals(active))return current();
        if(meta.getBoolean("exists_"+id,false))return new HashMap<>(file(id).getAll());
        return meta.getBoolean("baseline",false)?new HashMap<>(file("common").getAll()):current();
    }
    private void restore(Map<String,?> values){
        applying=true;
        try {SharedPreferences.Editor e=prefs.edit();for(String k:prefs.getAll().keySet())if(isLocal(k))e.remove(k);values.forEach((k,v)->{if(isLocal(k))put(e,k,v);});e.commit();SettingsMigration.migrateMultiFrame(prefs);}
        finally{applying=false;}
        if(com.particlesdevs.photoncamera.app.PhotonCamera.getSettings()!=null)com.particlesdevs.photoncamera.app.PhotonCamera.getSettings().loadCache();
    }
    public synchronized void changed(String key){
        if(applying)return;
        if(key.equals(PreferenceKeys.Key.CAMERA_ID.mValue))prefs.edit().remove("pref_mfsr_calibrate_key").apply();
        if(key.equals(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue)){
            if(PreferenceKeys.isPerLensSettingsOn()){
                write(file("common"),current());meta.edit().putBoolean("baseline",true).apply();
                active=ModuleRegistry.active();restore(snapshotStored(active));save(active);
            }else if(meta.getBoolean("baseline",false)){save(active);restore(file("common").getAll());}
        }else if(PreferenceKeys.isPerLensSettingsOn()){
            if(key.equals(PreferenceKeys.Key.CAMERA_ID.mValue)){
                String next=ModuleRegistry.active();
                if(!ModuleRegistry.camera(next).equals(PreferenceKeys.getCameraID())) next=PreferenceKeys.getCameraID();
                if(!next.equals(active)){save(active);active=next;restore(snapshotStored(next));save(next);}
            }else if(isLocal(key))save(active);
        }
        meta.edit().putString("active",active).apply();
    }
    private Map<String,?> snapshotStored(String id){
        if(meta.getBoolean("exists_"+id,false))return file(id).getAll();
        Map<String,Object> values=new HashMap<>(file("common").getAll());
        String legacy=manager.getString(PreferenceKeys.Key.PER_LENS_FILE_NAME.mValue,"settings_for_camera_"+ModuleRegistry.camera(id),"");
        if(!legacy.isEmpty())try{
            org.json.JSONObject json=new org.json.JSONObject(legacy);
            for(java.util.Iterator<String> it=json.keys();it.hasNext();){String key=it.next();Object v=json.get(key);if(isLocal(key)&&v!=org.json.JSONObject.NULL)values.put(key,v instanceof Double?((Double)v).floatValue():v);}
        }catch(org.json.JSONException ignored){}
        return values;
    }
    public synchronized void activate(String id){if(!PreferenceKeys.isPerLensSettingsOn()||id.equals(active))return;save(active);active=id;restore(snapshotStored(id));save(id);meta.edit().putString("active",id).apply();}
    public synchronized void copy(String source, Collection<String> targets, Set<String> keys){
        ModuleSensorSettings.copy(source,targets,keys);
        Map<String,?> src=snapshot(source);
        for(String target:targets){if(target.equals(source))continue;Map<String,Object> dst=new HashMap<>(snapshot(target));
            for(String key:keys)if(isLocal(key)){if(src.containsKey(key))dst.put(key,src.get(key));else dst.remove(key);}
            write(file(target),dst);meta.edit().putBoolean("exists_"+target,true).apply();
            if(PreferenceKeys.isPerLensSettingsOn()&&target.equals(active))restore(dst);
        }
    }
}
