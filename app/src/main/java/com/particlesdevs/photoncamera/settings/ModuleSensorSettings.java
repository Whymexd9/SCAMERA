package com.particlesdevs.photoncamera.settings;

import android.content.SharedPreferences;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.util.Lang;
import java.util.*;

/** Hardware overrides belong to stable module slots, not display labels or shared Camera IDs. */
public final class ModuleSensorSettings {
    private ModuleSensorSettings() {}
    public static final String COPY_PREFIX="sensor_copy_";
    private static SharedPreferences prefs(){return ScameraApp.getSettingsManagerStatic().getDefaultPreferences();}
    public static String title(String title){
        switch(title){
            case "Black Level Override":return Lang.t("Уровень чёрного","Black level");
            case "White Level Override":return Lang.t("Уровень белого","White level");
            case "OIS Mode":return Lang.t("Оптическая стабилизация","Optical stabilization");
            case "Session Type":return Lang.t("Тип сессии камеры","Camera session type");
            case "ISP Surface":return Lang.t("Поток ISP","ISP stream");
            case "RAW Frame Rate":return Lang.t("Частота кадров RAW","RAW frame rate");
            case "RAW Viewfinder Size":return Lang.t("Разрешение видоискателя RAW","RAW viewfinder size");
            case "Sensor Mode":return Lang.t("Режим сенсора (SensorModeTable)","Sensor mode (SensorModeTable)");
            default:return title;
        }
    }
    /** English text of a @SensorConfig description (the annotations hold the Russian text); other texts as they are. */
    public static String description(String text){
        switch(text){
            case "Чёрный уровень RAW вручную, в единицах RAW этого модуля; Авто — от камеры, ручное значение выключает «Уровень чёрного по кадру». Больше — тени темнее и проваливаются; меньше — тени серые.":return Lang.t(text,"Manual RAW black level in this module's RAW units; Auto: from the camera, a manual value turns «Black level from the frame» off. Higher: darker, crushed shadows; lower: greyish shadows.");
            case "Белый уровень RAW вручную, в единицах RAW этого модуля; Авто — от камеры и по отсчётам кадра. Больше — снимок темнее; меньше — света выгорают раньше.":return Lang.t(text,"Manual RAW white level in this module's RAW units; Auto: from the camera and the frame's samples. Higher: darker shot; lower: highlights clip sooner.");
            case "Оптическая стабилизация. Авто выключает её на штативе и в Unlimited, чтобы кадр не плыл.":return Lang.t(text,"Optical stabilization. Auto turns it off on a tripod and in Unlimited so the frame does not drift.");
            case "Режим сессии камеры: 0 — обычный, 32768–65535 — режимы производителя (неверный может не открыть камеру). В FULLRES всегда 0. Применяется при следующем открытии камеры.":return Lang.t(text,"Camera session mode: 0 is normal, 32768-65535 are vendor modes (a wrong one may stop the camera opening). FULLRES always runs 0. Applied the next time the camera opens.");
            case "Поток ISP в сессии камеры. Он может мешать режимам сенсора (ISZ, DCG): без него видоискатель строится из RAW. Авто — без потока ISP для модулей с режимом сенсора. Фиктивное откл. — поток в сессии, но камера его не заполняет.":return Lang.t(text,"The ISP stream in the camera session. It may get in the way of sensor modes (ISZ, DCG): without it the viewfinder is built from the RAW. Auto: no ISP stream for modules with a sensor mode. Fake off: the stream stays in the session, the camera does not fill it.");
            case "Размер кадра видоискателя из RAW по длинной стороне; соотношение сторон RAW сохраняется, фото не меняется. Меньше — быстрее и холоднее.":return Lang.t(text,"The RAW viewfinder frame's longest side; the RAW's aspect is kept, the photo does not change. Smaller: faster and cooler.");
            case "Частота кадров RAW, к/с. 0 — по умолчанию для объектива; своё значение может выбрать недокументированный режим сенсора.":return Lang.t(text,"RAW frame rate, fps. 0: the lens's default; a value of your own may pick an undocumented sensor mode.");
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
