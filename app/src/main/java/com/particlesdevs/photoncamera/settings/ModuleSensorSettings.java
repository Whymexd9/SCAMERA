package com.particlesdevs.photoncamera.settings;

import android.content.SharedPreferences;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.util.Lang;
import java.util.*;

/** Hardware overrides belong to stable module slots, not display labels or shared Camera IDs. */
public final class ModuleSensorSettings {
    private ModuleSensorSettings() {}
    public static final String COPY_PREFIX="sensor_copy_";
    private static SharedPreferences prefs(){return PhotonCamera.getSettingsManagerStatic().getDefaultPreferences();}
    public static String title(String title){
        switch(title){
            case "Black Level Override":return Lang.t("Уровень чёрного","Black level");
            case "White Level Override":return Lang.t("Уровень белого","White level");
            case "OIS Mode":return Lang.t("Оптическая стабилизация","Optical stabilization");
            case "Session Type":return Lang.t("Тип сессии камеры","Camera session type");
            default:return title;
        }
    }
    /** English text of a @SensorConfig description (the annotations hold the Russian text); other texts as they are. */
    public static String description(String text){
        switch(text){
            case "Уровень чёрного этого сенсора вручную (-1 = авто)":return Lang.t(text,"Manual black level of this sensor (-1 = auto)");
            case "Уровень белого для всех каналов вручную (-1 = из метаданных)":return Lang.t(text,"Manual white level for all channels (-1 = from metadata)");
            case "Оптическая стабилизация. В режиме «Авто» OIS выключается на штативе и в Unlimited, чтобы кадр не дрейфовал":return Lang.t(text,"Optical stabilization. In “Auto” OIS turns off on a tripod and in Unlimited so the frame does not drift");
            case "Тип capture-сессии Camera2 (0 = обычная)":return Lang.t(text,"Camera2 capture session type (0 = normal)");
            default:return text;
        }
    }
    public static String prefix(String slot){return "pref_sensorconfig_"+slot+"_";}
    public static String physical(String slot){return SensorConfigPreferenceGenerator.toPhysicalId(ModuleRegistry.camera(slot));}
    public static synchronized void ensure(String slot){
        if(!ModuleRegistry.slots().contains(slot))return;
        migrate(prefs(),slot,physical(slot));
    }
    static void migrate(SharedPreferences p,String slot,String physical){
        String marker="module_sensor_migrated_"+slot;
        if(p.getBoolean(marker,false))return;
        String from=prefix(physical),to=prefix(slot);
        SharedPreferences.Editor e=p.edit();
        for(Map.Entry<String,?> item:p.getAll().entrySet())if(item.getKey().startsWith(from)){
            String dest=to+item.getKey().substring(from.length());
            if(!p.contains(dest))ModuleProfiles.put(e,dest,item.getValue());
        }
        e.putBoolean(marker,true).commit();
    }
    public static String runtimeScope(String sensor){
        String slot=ModuleRegistry.active();
        if(ModuleRegistry.slots().contains(slot)&&Objects.equals(physical(slot),SensorConfigPreferenceGenerator.toPhysicalId(sensor))){ensure(slot);return slot;}
        return sensor;
    }
    public static void reset(String slot){
        ensure(slot);
        SharedPreferences.Editor e=prefs().edit();
        for(String key:prefs().getAll().keySet())if(key.startsWith(prefix(slot)))e.remove(key);
        e.commit(); // Keep migration marker: reset must not resurrect legacy values.
    }
    public static void copy(String source,Collection<String> targets,Set<String> selected){
        ensure(source);
        Map<String,?> values=prefs().getAll();
        for(String target:targets){if(target.equals(source))continue;ensure(target);
            SharedPreferences.Editor e=prefs().edit();
            for(String key:selected)if(key.startsWith(COPY_PREFIX)){
                String suffix=key.substring(COPY_PREFIX.length()),from=prefix(source)+suffix,to=prefix(target)+suffix;
                if(values.containsKey(from))ModuleProfiles.put(e,to,values.get(from));else e.remove(to);
            }
            e.commit();
        }
    }
}
