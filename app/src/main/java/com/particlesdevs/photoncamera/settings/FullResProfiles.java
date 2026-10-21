package com.particlesdevs.photoncamera.settings;

import android.content.SharedPreferences;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.PixelPro10;
import com.particlesdevs.photoncamera.capture.SensorModeSwitches;
import com.particlesdevs.photoncamera.capture.VivoSdkFullRes;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * P181 (owner, 2026-10-16: «ты должен был сделать отдельные настройки для 50мп/200мп как для отдельных модулей»): FULLRES is a
 * module of its own for the settings. A module's FULLRES shots keep their own profile - "back2@full200" and "back2@full50" on
 * the X200 Ultra's telephoto, "back0@full50" on a main camera - which the camera takes while FULLRES is on for that module
 * and the settings' module chip lists next to the module. A FULLRES profile starts as a copy of its module's. The sensor
 * settings (vendor tags, the sensor mode) stay the module's: FULLRES sets its own sensor mode.
 */
public final class FullResProfiles {
    static final String SEP = "@full";
    /** A camera FULLRES was offered for (the Pixel 10 Pro's, known once its characteristics were read): key prefix. */
    private static final String OFFERED = "module_fullres_camera_";

    private FullResProfiles() { }

    public static boolean isVariant(String id) {
        return id != null && id.contains(SEP);
    }

    /** The module of a profile ("back2@full200" -> "back2"; a module's own profile is itself). */
    public static String slotOf(String id) {
        int i = id == null ? -1 : id.indexOf(SEP);
        return i < 0 ? id : id.substring(0, i);
    }

    /** "200", "50", "" (a FULLRES of unknown size) or null for a module's own profile. */
    static String tagOf(String id) {
        int i = id == null ? -1 : id.indexOf(SEP);
        return i < 0 ? null : id.substring(i + SEP.length());
    }

    /** Megapixels of a FULLRES size, to the nearest ten (16320x12288 -> 200, 8192x6144 -> 50); P214h the trials' and merge texts too. */
    public static int megapixels(int width, int height) {
        return (int) (Math.round((double) width * height / 1e7) * 10);
    }

    /** The profile the camera uses for {@code slot} now: its FULLRES one while FULLRES is on for it, else its own. */
    public static String profileFor(String slot) {
        if (slot == null || isVariant(slot)) return slot;
        try {
            if (!PixelPro10.fullResChosen() || !takesFullRes(slot)) return slot;
            return slot + SEP + tag(ModuleRegistry.camera(slot), PixelPro10.tele200());
        } catch (RuntimeException e) {
            return slot;
        }
    }

    /** The FULLRES profiles of {@code slot} for the settings' module list (none without FULLRES). */
    public static List<String> variants(String slot) {
        try {
            if (slot == null || isVariant(slot) || !takesFullRes(slot)) return Collections.emptyList();
            String camera = ModuleRegistry.camera(slot);
            List<String> out = new ArrayList<>();
            if (VivoSdkFullRes.teleSizes(camera)) {
                out.add(slot + SEP + tag(camera, false));
                out.add(slot + SEP + tag(camera, true));
            } else out.add(slot + SEP + tag(camera, PixelPro10.tele200()));
            return out;
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    /** A plain module of a camera with FULLRES (a sensor preset - ISZ, a crop module - never runs FULLRES). */
    private static boolean takesFullRes(String slot) {
        return offered(ModuleRegistry.camera(slot)) && !SensorModeSwitches.sensorPreset(slot);
    }

    private static String tag(String camera, boolean tele200) {
        VivoSdkFullRes.Profile p = VivoSdkFullRes.offered(camera) ? VivoSdkFullRes.profile(camera, tele200) : null;
        return p == null ? "" : String.valueOf(megapixels(p.size.getWidth(), p.size.getHeight()));
    }

    /** FULLRES is offered for {@code camera}: the vivo profiles, or the camera the FULLRES button last showed itself for. */
    public static boolean offered(String camera) {
        if (camera == null) return false;
        if (VivoSdkFullRes.offered(camera)) return true;
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(OFFERED + camera, false);
    }

    /** The FULLRES button's verdict for a camera (from its characteristics), for {@link #offered} before the camera opens. */
    public static void remember(String camera, boolean offered) {
        SharedPreferences prefs = prefs();
        if (camera == null || prefs == null || prefs.getBoolean(OFFERED + camera, false) == offered) return;
        prefs.edit().putBoolean(OFFERED + camera, offered).apply();
    }

    /** The module's name, and for a FULLRES profile its size: «2,4× · 200 Мп». */
    public static String label(String id) {
        String base = ModuleRegistry.label(slotOf(id));
        String size = size(id);
        return size == null ? base : base + " · " + size;
    }

    /** P189: the size of a FULLRES profile («200 Мп», «полное разрешение»); null for a module's own profile. */
    public static String size(String id) {
        String tag = tagOf(id);
        if (tag == null) return null;
        return tag.isEmpty() ? Lang.t("полное разрешение", "full resolution") : tag + Lang.t(" Мп", " MP");
    }

    /** A FULLRES 200 MP profile («back2@full200»): its shots never take the NLM denoise (SettingsAvailability.editing200). */
    public static boolean is200(String id) {
        return "200".equals(tagOf(id));
    }

    /** P189: the hardware remosaic row acts in this profile: a FULLRES one but 200 MP (no hardware 200 MP mode, P182). */
    public static boolean hardwareRemosaicApplies(String id) {
        String tag = tagOf(id);
        return tag != null && !"200".equals(tag);
    }

    /** P190: the frame-count rows of FULLRES 50 / 200 MP shots (PreferenceKeys.getFullRes50Frames / getFullRes200Frames). */
    public static final String FRAMES_50 = "pref_scam_hybrid_fullres50_frames", FRAMES_200 = "pref_scam_hybrid_fullres200_frames";
    /** P202: the ZSL / PSL switch of FULLRES 50 MP shots (a row of the 50 MP profile, as its frame count). */
    public static final String ZSL_50 = "pref_scam_hybrid_fullres50_zsl";

    /** P190: the FULLRES size ("50", "200") a frame-count row is for; null for any other key. */
    public static String framesTag(String key) {
        return FRAMES_50.equals(key) || ZSL_50.equals(key) ? "50" : FRAMES_200.equals(key) ? "200" : null;
    }

    /** P190: the row of {@code key} acts in {@code profile}: a frame-count row in the FULLRES profile of its own size only. */
    public static boolean framesRowApplies(String key, String profile) {
        String tag = framesTag(key);
        return tag == null || tag.equals(tagOf(profile));
    }

    /** P190: a module of this phone has a FULLRES profile of this size ("50", "200"): its frame-count row is shown. */
    public static boolean sizeOffered(String tag) {
        try {
            for (String slot : ModuleRegistry.slots())
                for (String id : variants(slot)) if (tag.equals(tagOf(id))) return true;
        } catch (RuntimeException ignored) { }
        return false;
    }

    private static SharedPreferences prefs() {
        try {
            return ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
