package com.particlesdevs.photoncamera.settings;

import android.content.SharedPreferences;
import java.util.*;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;

/** Stable button slots are distinct from physical IDs and from their display names. */
public final class ModuleRegistry {
    private static SharedPreferences prefs(){return PhotonCamera.getSettingsManagerStatic().getDefaultPreferences();}
    public static String active(){String id=prefs().getString("module_active",PreferenceKeys.getCameraID());return id==null||id.isEmpty()?"0":id;}
    public static List<String> slots(){
        List<String> out=new ArrayList<>();for(String side:new String[]{"back","front"})for(int i=0;i<8;i++)if(prefs().contains("module_auto_"+side+i))out.add(side+i);return out;
    }
    public static List<String> initialize(String side,List<CameraLensData> cameras){
        SharedPreferences.Editor e=prefs().edit();
        for(int i=0;i<8;i++){
            String slot=side+i;
            if(!prefs().contains("module_auto_"+slot)&&!cameras.isEmpty()){
                CameraLensData c=cameras.get(Math.min(i,cameras.size()-1));
                e.putString("module_auto_"+slot,c.getCameraId());
                e.putBoolean("module_visible_"+slot,i<cameras.size());
                e.putString("module_label_"+slot,String.format(java.util.Locale.US,"%.1f×",c.getZoomFactor()).replace(".0×","×"));
            }
        }e.apply();List<String> out=new ArrayList<>();for(int i=0;i<8;i++)out.add(side+i);return out;
    }
    public static String camera(String slot){String manual=prefs().getString("module_id_"+slot,"").trim();return manual.isEmpty()?prefs().getString("module_auto_"+slot,slot):manual;}
    public static String label(String slot){String custom=prefs().getString("module_name_"+slot,"").trim();return custom.isEmpty()?prefs().getString("module_label_"+slot,"Камера"):custom;}
    public static boolean visible(String slot){return prefs().getBoolean("module_visible_"+slot,false);}
    /** Zoom ratio of the module button; defaults to the ratio in its automatic label ("0.6×", "3×"). */
    public static float zoom(String slot){
        String v=prefs().getString("module_zoom_"+slot,"").trim();
        try{if(!v.isEmpty())return Math.max(.1f,Float.parseFloat(v.replace(',','.')));}catch(NumberFormatException ignored){}
        java.util.regex.Pattern number=java.util.regex.Pattern.compile("[0-9]*\\.?[0-9]+");
        for(String text:new String[]{label(slot),prefs().getString("module_label_"+slot,"")}){
            java.util.regex.Matcher m=number.matcher(text.replace(',','.'));
            if(m.find())try{return Math.max(.1f,Float.parseFloat(m.group()));}catch(NumberFormatException ignored){}
        }
        return 1f;
    }
    /** True when the module's frames are already cropped on the sensor (vendor mode), not by digital zoom. */
    public static boolean sensorCrop(String slot){
        if(prefs().contains("module_sensorcrop_"+slot))return prefs().getBoolean("module_sensorcrop_"+slot,false);
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("forceSensorMode\"[^}]*\"value\":\"(\\d+)\"").matcher(prefs().getString("pref_sensorconfig_"+slot+"_tunablekeys",""));
        return m.find()&&!"0".equals(m.group(1));
    }
    /** Zoom ratio at which the module's frame is uncropped: its own ratio for sensor-crop modules, else the widest module on the same Camera ID. */
    public static float nativeRatio(String slot){
        if(sensorCrop(slot))return zoom(slot);
        String id=camera(slot);float min=zoom(slot);
        for(String other:slots())if(visible(other)&&!sensorCrop(other)&&camera(other).equals(id))min=Math.min(min,zoom(other));
        return min;
    }
    public static void select(String slot){PreferenceKeys.profiles().activate(slot);prefs().edit().putString("module_active",slot).apply();}
}
