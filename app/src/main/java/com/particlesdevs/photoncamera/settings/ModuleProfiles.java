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
    /**
     * Settings shared by every lens (owner, 8 October 2026): never part of a module profile, whatever per-lens settings say.
     * The one list behind {@link #isLocal} and PreferenceKeys' legacy per-lens JSON. Sounds, grid, the photo format with every
     * format / quality option (JPEG, HEIC, HEIC 10 bit, WebP, AVIF, «Также сохранять JPEG», the RAW save mode, Ultra HDR), the
     * watermark and its caption, Root, the camera package spoof, face detection and tracking AF, the gallery icon and theme,
     * the flicker frequency and the merge route. Processing, tuning and sensor settings stay per module.
     */
    static final Set<String> GLOBAL_KEYS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "pref_camera_sounds_key","pref_timer_sound_key","pref_show_grid_key",
            "pref_save_raw_key","pref_ultrahdr_key","pref_show_watermark_key",
            "pref_root_enabled","pref_camera_package_spoof_enabled","pref_oplus_spoof_package_key","pref_generic_spoof_package_key",
            "pref_binder_spoof_package_key","pref_face_detect_mode","pref_tracking_af_mode","pref_hide_gallery_icon_key",
            "pref_show_gradient_key","pref_antibanding_hz_key",ScamHybridKeys.ROUTE)));
    /** Key prefixes of {@link #GLOBAL_KEYS}: the photo format rows (pref_photo_format, pref_photo_also_jpeg, the qualities, AVIF), the caption, the theme. */
    static final String[] GLOBAL_PREFIXES={"pref_photo_","pref_jpeg_","pref_heic_","pref_webp_","pref_avif_","pref_watermark_","pref_theme"};
    /** A setting shared by every lens ({@link #GLOBAL_KEYS}). */
    public static boolean isGlobal(String key){
        if(key==null)return false;
        if(GLOBAL_KEYS.contains(key))return true;
        for(String prefix:GLOBAL_PREFIXES)if(key.startsWith(prefix))return true;
        return false;
    }
    public static boolean isLocal(String key) {
        if(key==null || key.equals("pref_zsl_buffer_count_key") || isGlobal(key))return false;
        if(key.equals(PreferenceKeys.Key.CAMERA_ID.mValue)||key.equals(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue))return false;
        if(key.startsWith("lens_")||key.startsWith("module_")||key.startsWith("pref_sensorconfig_")||key.startsWith("settings_")||key.startsWith("pref_theme")||key.contains("debug")||key.contains("folder")||key.contains("config_file"))return false;
        if(key.equals("user_camera_ids")||key.equals("hidden_camera_ids")||key.equals(PreferenceKeys.Key.CAMERA_MODE.mValue))return false;
        return key.startsWith("pref_") || key.startsWith("hexquad_") || key.startsWith("quad2x2_") || key.startsWith("scamera_");
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
        values=BrandMigration.map(values); // P55: a profile written before the SCAM rename
        applying=true;
        try {SharedPreferences.Editor e=prefs.edit();for(String k:prefs.getAll().keySet())if(isLocal(k))e.remove(k);values.forEach((k,v)->{if(isLocal(k))put(e,k,v);});e.commit();SettingsMigration.migrateMultiFrame(prefs);SettingsMigration.migrateScamHybrid(prefs,false);SettingsMigration.removeObsolete(prefs);}
        finally{applying=false;}
        if(com.particlesdevs.photoncamera.app.PhotonCamera.getSettings()!=null)com.particlesdevs.photoncamera.app.PhotonCamera.getSettings().loadCache();
    }
    public synchronized void changed(String key){
        if(applying)return;
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
