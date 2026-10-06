package com.particlesdevs.photoncamera.settings;

import android.content.SharedPreferences;
import java.util.*;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.util.Lang;
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
        }e.apply();
        // Slots are created once. Lenses that appear later (hidden cameras unlocked by the package
        // spoof, a lens discovered by the user) take the free slots, existing buttons stay as they are.
        StringBuilder signature=new StringBuilder();for(CameraLensData c:cameras)signature.append(c.getCameraId()).append(',');
        if(!cameras.isEmpty()&&!signature.toString().equals(prefs().getString("module_lens_sig_"+side,""))){
            SharedPreferences.Editor fill=prefs().edit();Set<String> assigned=new HashSet<>();
            for(int i=0;i<8;i++)if(visible(side+i))assigned.add(camera(side+i));
            int next=0;
            for(int i=0;i<8;i++){
                String slot=side+i;if(visible(slot))continue;
                while(next<cameras.size()&&assigned.contains(cameras.get(next).getCameraId()))next++;
                if(next>=cameras.size())break;
                CameraLensData c=cameras.get(next++);assigned.add(c.getCameraId());
                fill.putString("module_auto_"+slot,c.getCameraId());fill.putBoolean("module_visible_"+slot,true);
                fill.putString("module_label_"+slot,String.format(java.util.Locale.US,"%.1f×",c.getZoomFactor()).replace(".0×","×"));
            }
            fill.putString("module_lens_sig_"+side,signature.toString()).apply();
        }
        List<String> out=new ArrayList<>();for(int i=0;i<8;i++)out.add(side+i);return out;
    }
    public static String camera(String slot){String manual=prefs().getString("module_id_"+slot,"").trim();return manual.isEmpty()?prefs().getString("module_auto_"+slot,slot):manual;}
    public static String label(String slot){String custom=prefs().getString("module_name_"+slot,"").trim();return custom.isEmpty()?prefs().getString("module_label_"+slot,Lang.t("Камера","Camera")):custom;}
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
    /** The module's forced vendor sensor mode (vivo.control.forceSensorMode): 7 = 4x ISZ Tetra, 5 = 2x ISZ Quad, 0 = none. */
    public static int sensorMode(String slot){
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("forceSensorMode\"[^}]*\"value\":\"(\\d+)\"").matcher(prefs().getString("pref_sensorconfig_"+slot+"_tunablekeys",""));
        try{return m.find()?Integer.parseInt(m.group(1)):0;}catch(NumberFormatException e){return 0;}
    }
    /** Zoom ratio at which the module's frame is uncropped: its own ratio for sensor-crop modules, else the widest module on the same Camera ID. */
    public static float nativeRatio(String slot){
        if(sensorCrop(slot))return zoom(slot);
        String id=camera(slot);float min=zoom(slot);
        for(String other:slots())if(visible(other)&&!sensorCrop(other)&&camera(other).equals(id))min=Math.min(min,zoom(other));
        return min;
    }
    /** P21: the source module of a duplicate, or null for an original. */
    public static String duplicateOf(String slot){return ModuleDuplicates.sourceOf(prefs().getAll(),slot);}
    /**
     * P21: a second button on the same lens (own name, zoom, vendor requests and profile) in a free slot of the same side;
     * returns the new slot, or null when the side has no free slot.
     */
    public static String duplicate(String source){
        java.util.Map<String,?> all=prefs().getAll();
        String target=ModuleDuplicates.freeSlot(all,source);
        if(target==null)return null;
        SharedPreferences.Editor e=prefs().edit();
        for(java.util.Map.Entry<String,Object> w:ModuleDuplicates.copyOf(all,source,target,label(source),zoom(source)).entrySet())ModuleProfiles.put(e,w.getKey(),w.getValue());
        e.apply();
        try{
            java.util.Set<String> keys=new java.util.HashSet<>(PreferenceKeys.profiles().snapshot(source).keySet());
            PreferenceKeys.profiles().copy(source,java.util.Collections.singletonList(target),keys);
        }catch(RuntimeException noProfiles){/* per-module profiles off or not created yet: the slot settings are copied */}
        return target;
    }
    /** P21: delete a duplicate (an original cannot be deleted); the active module falls back to its source. */
    public static boolean remove(String slot){
        java.util.Map<String,?> all=prefs().getAll();
        String source=ModuleDuplicates.sourceOf(all,slot);
        if(source==null)return false;
        SharedPreferences.Editor e=prefs().edit();
        for(String k:ModuleDuplicates.removalOf(all,slot))e.remove(k);
        e.putBoolean("module_visible_"+slot,false).apply();
        if(slot.equals(active()))select(source);
        return true;
    }
    private static volatile String pendingSlot="";
    private static volatile long pendingAt;
    public static void select(String slot){
        PreferenceKeys.profiles().activate(slot);prefs().edit().putString("module_active",slot).apply();
        pendingSlot=slot;pendingAt=android.os.SystemClock.elapsedRealtime();
    }
    /**
     * True while the module just chosen waits for its Camera ID to open. The session restart still reports the
     * previous camera first; reconciling the module with that stale ID undid the choice (and the next report
     * then took the first module of the new camera, so 6.7x and 10x on the 2.4x lens came out as 2.4x).
     */
    public static boolean switching(String openedCameraId){
        String slot=pendingSlot;
        if(slot.isEmpty())return false;
        if(android.os.SystemClock.elapsedRealtime()-pendingAt>5000||camera(slot).equals(openedCameraId)){pendingSlot="";return false;}
        return true;
    }
}
