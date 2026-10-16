package com.particlesdevs.photoncamera.settings;

import android.content.SharedPreferences;
import java.util.*;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;

/** Stable button slots are distinct from physical IDs and from their display names. */
public final class ModuleRegistry {
    private static SharedPreferences prefs(){return ScameraApp.getSettingsManagerStatic().getDefaultPreferences();}
    public static int capacity(String side){return "back".equals(side)&&com.particlesdevs.photoncamera.capture.VivoQcomModes.device()?9:8;}
    public static String active(){String id=prefs().getString("module_active",PreferenceKeys.getCameraID());return id==null||id.isEmpty()?"0":id;}
    public static List<String> slots(){
        List<String> out=new ArrayList<>();for(String side:new String[]{"back","front"})for(int i=0;i<capacity(side);i++)if(prefs().contains("module_auto_"+side+i))out.add(side+i);return out;
    }
    public static List<String> initialize(String side,List<CameraLensData> cameras){
        SharedPreferences.Editor e=prefs().edit();
        for(int i=0;i<capacity(side);i++){
            String slot=side+i;
            if(!prefs().contains("module_auto_"+slot)&&!cameras.isEmpty()){
                CameraLensData c=cameras.get(Math.min(i,cameras.size()-1));
                e.putString("module_auto_"+slot,c.getCameraId());
                e.putBoolean("module_visible_"+slot,i<cameras.size());
                e.putString("module_label_"+slot,ZoomLabels.label(c.getZoomFactor(),android.os.Build.BRAND));
            }
        }e.apply();
        // Slots are created once. Lenses that appear later (hidden cameras unlocked by the package
        // spoof, a lens discovered by the user) take the free slots, existing buttons stay as they are.
        StringBuilder signature=new StringBuilder();for(CameraLensData c:cameras)signature.append(c.getCameraId()).append(',');
        if(!cameras.isEmpty()&&!signature.toString().equals(prefs().getString("module_lens_sig_"+side,""))){
            SharedPreferences.Editor fill=prefs().edit();Set<String> assigned=new HashSet<>();
            for(int i=0;i<capacity(side);i++)if(visible(side+i))assigned.add(camera(side+i));
            int next=0;
            for(int i=0;i<capacity(side);i++){
                String slot=side+i;if(visible(slot))continue;
                while(next<cameras.size()&&assigned.contains(cameras.get(next).getCameraId()))next++;
                if(next>=cameras.size())break;
                CameraLensData c=cameras.get(next++);assigned.add(c.getCameraId());
                fill.putString("module_auto_"+slot,c.getCameraId());fill.putBoolean("module_visible_"+slot,true);
                fill.putString("module_label_"+slot,ZoomLabels.label(c.getZoomFactor(),android.os.Build.BRAND));
            }
            fill.putString("module_lens_sig_"+side,signature.toString()).apply();
        }
        // P100c: each lens's measured field of view (its native zoom) and, on brands with stock steps (Samsung), the stock
        // label of the automatic modules created before (an S24 Ultra kept «2.7×» / «5.1×» for its 3× / 5× teles)
        SharedPreferences.Editor fov=prefs().edit();
        for(CameraLensData c:cameras)fov.putFloat("module_fov_"+c.getCameraId(),c.getZoomFactor());
        if(ZoomLabels.snaps(android.os.Build.BRAND))for(int i=0;i<capacity(side);i++){
            String slot=side+i;String auto=prefs().getString("module_auto_"+slot,null);if(auto==null)continue;
            for(CameraLensData c:cameras)if(c.getCameraId().equals(auto)){
                String measured=String.format(java.util.Locale.US,"%.1f×",c.getZoomFactor()).replace(".0×","×");
                if(measured.equals(prefs().getString("module_label_"+slot,"")))fov.putString("module_label_"+slot,ZoomLabels.label(c.getZoomFactor(),android.os.Build.BRAND));
            }
        }
        fov.apply();
        List<String> out=new ArrayList<>();for(int i=0;i<capacity(side);i++)out.add(side+i);return out;
    }
    public static String camera(String slot){String manual=prefs().getString("module_id_"+slot,"").trim();return manual.isEmpty()?prefs().getString("module_auto_"+slot,slot):manual;}
    /** P41: on the Xiaomi 17 Ultra with smooth zoom the tele's automatic label reads as the stock camera's («3.2×» = 75 mm). */
    public static String label(String slot){String custom=prefs().getString("module_name_"+slot,"").trim();return custom.isEmpty()?com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.stockLabel(prefs().getString("module_label_"+slot,Lang.t("Камера","Camera"))):custom;}
    public static boolean visible(String slot){return prefs().getBoolean("module_visible_"+slot,false);}
    /** Zoom ratio of the module button; defaults to the ratio in its automatic label ("0.6×", "3×"). */
    public static float zoom(String slot){
        String v=prefs().getString("module_zoom_"+slot,"").trim();
        try{if(!v.isEmpty())return Math.max(.1f,Float.parseFloat(v.replace(',','.')));}catch(NumberFormatException ignored){}
        java.util.regex.Pattern number=java.util.regex.Pattern.compile("[0-9]*\\.?[0-9]+");
        for(String text:new String[]{label(slot),prefs().getString("module_label_"+slot,"")}){
            java.util.regex.Matcher m=number.matcher(text.replace(',','.'));
            if(m.find())try{return com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.stockRatio(Math.max(.1f,Float.parseFloat(m.group())));}catch(NumberFormatException ignored){}
        }
        return 1f;
    }
    /**
     * P148: the lens crop of a module made as a copy «с кропом» (owner: a copy of the 1x camera that always shoots with a 1.3x
     * crop): its zoom is the source's times this, and the frame is cropped by it; 1 for every other module.
     */
    public static float crop(String slot){
        String v=prefs().getString("module_crop_"+slot,"").trim();
        try{if(!v.isEmpty()){float c=Float.parseFloat(v.replace(',','.'));if(c>=1f&&c<=20f)return c;}}catch(NumberFormatException ignored){}
        return 1f;
    }
    /** True when the module's frames are already cropped on the sensor (vendor mode), not by digital zoom. */
    /** The owner's explicit «crop on the sensor» setting of the module, or null when it is on «Auto». */
    public static Boolean sensorCropExplicit(String slot){
        return prefs().contains("module_sensorcrop_"+slot)?prefs().getBoolean("module_sensorcrop_"+slot,false):null;
    }
    public static boolean sensorCrop(String slot){
        if(prefs().contains("module_sensorcrop_"+slot))return prefs().getBoolean("module_sensorcrop_"+slot,false);
        return sensorMode(slot)!=0;
    }
    /** The module's forced vendor sensor mode (vivo.control.forceSensorMode): 7 = 4x ISZ Tetra, 5 = 2x ISZ Quad, 0 = none. */
    public static int sensorMode(String slot){
        return forcedMode(prefs().getString("pref_sensorconfig_"+slot+"_tunablekeys",""));
    }
    /** The value of the first switched-on vivo.control.forceSensorMode key of a module's vendor key list (JSON), 0 without one. */
    static int forcedMode(String json){
        java.util.regex.Matcher o=java.util.regex.Pattern.compile("\\{[^{}]*\\}").matcher(json==null?"":json);
        while(o.find()){
            String key=o.group();
            if(!key.contains("forceSensorMode\"")||key.contains("\"enabled\":false"))continue;
            java.util.regex.Matcher v=java.util.regex.Pattern.compile("\"value\":\"(\\d+)\"").matcher(key);
            try{return v.find()?Integer.parseInt(v.group(1)):0;}catch(NumberFormatException e){return 0;}
        }
        return 0;
    }
    /** Zoom ratio at which the module's frame is uncropped: its own ratio for sensor-crop modules, else the widest module on the same Camera ID. */
    public static float nativeRatio(String slot){
        // P148: a module with a lens crop starts from the uncropped zoom of its lens (zoom / crop), whether the original module
        // is shown or hidden and whether its frames are cropped on the sensor: the crop stays a digital crop of that frame
        if(sensorCrop(slot))return zoom(slot)/crop(slot);
        String id=camera(slot);
        // P100c: where labels snap to stock steps, the lens's measured field of view is its native zoom (S24 Ultra 3× tele = 2.73)
        if(ZoomLabels.snaps(android.os.Build.BRAND)&&prefs().contains("module_fov_"+id)){
            float fov=prefs().getFloat("module_fov_"+id,0f);
            if(fov>0f)return fov;
        }
        float min=zoom(slot)/crop(slot);
        for(String other:slots())if(visible(other)&&!sensorCrop(other)&&camera(other).equals(id))min=Math.min(min,zoom(other)/crop(other));
        return min;
    }
    /** P148: a hidden filler slot nobody set up: a free place for a copy (the module list counts these instead of showing them). */
    public static boolean freeSlot(String slot){return ModuleDuplicates.untouchedFiller(prefs().getAll(),slot);}
    /** P21: the source module of a duplicate, or null for an original. */
    public static String duplicateOf(String slot){return ModuleDuplicates.sourceOf(prefs().getAll(),slot);}
    /**
     * P21: a second button on the same lens (own name, zoom, vendor requests and profile) in a free slot of the same side;
     * returns the new slot, or null when the side has no free slot.
     */
    public static String duplicate(String source){return duplicate(source,1f);}
    /**
     * P148: a copy of {@code source} with a lens crop (1 = none, as {@link #duplicate(String)}): its button zoom is the source's
     * times the crop («1.3×» for a 1.3x copy of the 1x camera) and the camera crops its frame by it; null without a free slot.
     */
    public static String duplicate(String source,float crop){
        java.util.Map<String,?> all=prefs().getAll();
        String target=ModuleDuplicates.freeSlot(all,source,capacity(source.startsWith("front")?"front":"back"));
        if(target==null)return null;
        SharedPreferences.Editor e=prefs().edit();
        for(java.util.Map.Entry<String,Object> w:ModuleDuplicates.copyOf(all,source,target,label(source),zoom(source),crop(source),crop).entrySet())ModuleProfiles.put(e,w.getKey(),w.getValue());
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
    /**
     * Module to restore when the camera flips to {@code side} (back0 "2x" with its Quad mode, not the side's plain 1x): the
     * module last used there while it is still a visible slot on an existing camera; null otherwise.
     */
    /** Records the active module as its side's last one (before a flip; a module restored at start was never select()ed). */
    public static void rememberActive(){String a=active();String k=ModuleChoice.lastKey(a);if(k!=null)prefs().edit().putString(k,a).apply();}
    public static String lastOnSide(String side,java.util.Collection<String> cameraIds){
        String slot=prefs().getString("module_last_"+side,null);
        return ModuleChoice.flipTarget(slot,side,slot!=null&&visible(slot),slot==null?null:camera(slot),cameraIds);
    }
    /** The module standing for {@code cameraId} on {@code side}: plain (no forced sensor mode) and nearest 1x preferred. */
    public static String moduleForCamera(String side,String cameraId){
        List<String> slots=new ArrayList<>();Map<String,String> cameraOf=new HashMap<>();Set<String> shown=new HashSet<>();
        Map<String,Float> zooms=new HashMap<>();Map<String,Integer> modes=new HashMap<>();
        for(int i=0;i<capacity(side);i++){
            String slot=side+i;if(!prefs().contains("module_auto_"+slot))continue;
            slots.add(slot);cameraOf.put(slot,camera(slot));if(visible(slot))shown.add(slot);
            zooms.put(slot,zoom(slot));modes.put(slot,sensorMode(slot));
        }
        return ModuleChoice.moduleFor(slots,side,cameraId,cameraOf,shown,zooms,modes);
    }
    /** P156: {@link ModuleChoice#home} over this phone's back modules. */
    public static String homeSlot(){
        List<String> slots=new ArrayList<>();Set<String> shown=new HashSet<>();
        Map<String,Float> zooms=new HashMap<>(),crops=new HashMap<>();Map<String,Integer> modes=new HashMap<>();
        for(int i=0;i<capacity("back");i++){
            String slot="back"+i;if(!prefs().contains("module_auto_"+slot))continue;
            slots.add(slot);if(visible(slot))shown.add(slot);
            zooms.put(slot,zoom(slot));modes.put(slot,sensorMode(slot));crops.put(slot,crop(slot));
        }
        return ModuleChoice.home(slots,shown,zooms,modes,crops);
    }
    private static boolean homeChecked;
    /**
     * P156: on the camera's first start in this process. A fresh start (the app was closed for good; not the system bringing
     * back a screen it closed) opens on the back 1x module ({@link #homeSlot}) with its profile, whatever module was used
     * last. Returns the slot switched to, null when nothing changed.
     */
    public static synchronized String openHomeOnce(boolean freshStart){
        if(homeChecked)return null;
        homeChecked=true;
        if(!freshStart)return null;
        String home=homeSlot();
        if(home==null)return null;
        if(home.equals(active())&&camera(home).equals(PreferenceKeys.getCameraID())){syncProfile();return null;}
        select(home);
        PreferenceKeys.setCameraID(camera(home));
        return home;
    }
    /** Tests: the next {@link #openHomeOnce} counts as the process's first camera start. */
    static synchronized void forgetHomeCheck(){homeChecked=false;}
    /**
     * P156: the main settings hold the camera module's profile again. The settings screen can show another module's values
     * for editing (its chip); leaving it, or the camera coming back, returns to the module the camera uses. Not while the app
     * is about to restart (an imported config or a reset wrote the files behind the profiles' back).
     */
    public static void syncProfile(){
        if(ScameraApp.restarting)return;
        try{PreferenceKeys.profiles().activate(active());}catch(RuntimeException ignored){}
    }
    private static volatile String pendingSlot="";
    private static volatile long pendingAt;
    public static void select(String slot){
        com.particlesdevs.photoncamera.capture.SensorModeSwitches.onModuleSelected(slot);
        PreferenceKeys.profiles().activate(slot);
        SharedPreferences.Editor e=prefs().edit().putString("module_active",slot);
        // The module of each side is remembered, so flipping back to that side returns to it (and not to the side's 1x).
        String last=ModuleChoice.lastKey(slot);if(last!=null)e.putString(last,slot);
        e.apply();
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
