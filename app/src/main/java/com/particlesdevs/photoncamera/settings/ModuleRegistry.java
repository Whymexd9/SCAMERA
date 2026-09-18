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
    public static int order(String slot){try{return Integer.parseInt(prefs().getString("module_order_"+slot,slot.substring(slot.length()-1)));}catch(Exception e){return 0;}}
    public static void select(String slot){PreferenceKeys.profiles().activate(slot);prefs().edit().putString("module_active",slot).apply();}
}
