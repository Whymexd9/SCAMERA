package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.*;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.*;
import java.util.*;
import org.json.JSONArray;

/** Uses the real preference catalogue and preserves its storage types and numeric limits. */
public final class FavoriteSettings {
    private static final String KEY="settings_favorite_keys";
    public final PreferenceScreen tree;
    public final Map<String,Entry> entries=new LinkedHashMap<>();
    public static SharedPreferences prefs(){return PhotonCamera.getSettingsManagerStatic().getDefaultPreferences();}
    public FavoriteSettings(Context context){
        PreferenceManager pm=new PreferenceManager(context);pm.setSharedPreferencesName("favorites_catalog");
        tree=pm.inflateFromResource(context,R.xml.preferences,null);pm.setPreferences(tree);
        TunableSettingsManager.ensureTunableClassesRegistered();
        for(Class<?> c:TunableRegistry.TUNABLE_CLASSES)TunablePreferenceGenerator.registerTunableClass(c);
        TunablePreferenceGenerator.generatePreferences(context,tree);collect(tree);
    }
    private void collect(Preference p){
        if(p instanceof PreferenceGroup){PreferenceGroup g=(PreferenceGroup)p;for(int i=0;i<g.getPreferenceCount();i++)collect(g.getPreference(i));return;}
        if(!ModuleProfiles.isLocal(p.getKey())||p.getKey().equals("pref_dcp_profile_key"))return;
        Entry e=new Entry(p);
        if(p instanceof UniversalSeekBarPreference){UniversalSeekBarPreference n=(UniversalSeekBarPreference)p;e.min=n.minimum();e.max=n.maximum();e.decimal=n.decimal();e.defaultValue=n.defaultNumber();e.kind=2;}
        else if(p instanceof TunableSeekBarPreference){TunableSeekBarPreference n=(TunableSeekBarPreference)p;e.min=n.minimum();e.max=n.maximum();e.decimal=n.decimal();e.defaultValue=n.defaultNumber();e.kind=3;}
        else if(p instanceof TwoStatePreference){e.kind=0;e.defaultValue=((TwoStatePreference)p).isChecked();}
        else if(p instanceof ListPreference){ListPreference l=(ListPreference)p;if(l.getEntries()==null||l.getEntryValues()==null)return;e.kind=1;e.defaultValue=l.getValue();e.labels=l.getEntries();e.values=l.getEntryValues();}
        else return;
        entries.put(e.key,e);
    }
    public static List<String> selected(){List<String> result=new ArrayList<>();try{JSONArray a=new JSONArray(prefs().getString(KEY,"[]"));for(int i=0;i<a.length();i++){String k=a.getString(i);if(!result.contains(k))result.add(k);}}catch(Exception ignored){}return result;}
    public static void selected(List<String> keys){prefs().edit().putString(KEY,new JSONArray(keys).toString()).apply();}
    public static final class Entry {
        public final String key,title;public final Preference preference;
        public int kind;public float min,max;public boolean decimal;
        public Object defaultValue;public CharSequence[] labels,values;
        Entry(Preference p){preference=p;key=p.getKey();title=String.valueOf(p.getTitle());}
        public Object value(){Object value=prefs().getAll().get(key);return value==null?defaultValue:value;}
        public String label(){Object v=value();if(kind==0)return PreferenceNumber.bool(v,false)?"Вкл":"Выкл";if(kind==1){for(int i=0;i<values.length;i++)if(values[i].toString().equals(String.valueOf(v)))return labels[i].toString();}return String.valueOf(v);}
        public String unavailable(){return new SettingsAvailability(prefs().getAll()).reason(key);}
        public void write(Object value){SharedPreferences.Editor e=prefs().edit();if(kind==0)e.putBoolean(key,(Boolean)value);else if(kind==3){float n=((Number)value).floatValue();if(decimal)e.putFloat(key,n);else e.putInt(key,Math.round(n));}else if(kind==2)e.putString(key,PreferenceNumber.format(((Number)value).floatValue(),decimal));else e.putString(key,value.toString());e.apply();}
    }
}
