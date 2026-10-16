package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraManager;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.TunableKeyManager;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P122 Sensor modes of the vivo X300 Ultra (owner, 2026-10-13: «Я даю тебе разрешение переключать сенсормоды», «На виво на
 * снапах сенсормоды можно переключать с помощью vivo.control.forceSensorMode реквеста», and the table of its working modes).
 * <p>
 * vivo.control.forceSensorMode works there as a SESSION key only (as a request key the HAL does not take it): the modules carry
 * it as a «Session» vendor key (VendorTagUtils.TunableKey.TYPE_SESSION, set in the session parameters, not in the requests).
 * The table (14 mm ultra-wide / 35 mm main / 85 mm tele): 2x ISZ as a Quad mosaic = UW 21, main 10, tele 5; tele 4x ISZ «Quad
 * Quad Bayer» (Tetra) = 7; DCG 14 bit «Open Gate» = UW 11, main 17, tele 20 (owner: ISZ and DCG on all three modules).
 * Modules offered once each (a deleted one stays deleted; the keys are editable in the module settings): «2×» of the main, the
 * UW and the tele (7.4×), «15×» (4x) of the tele, all without remosaic (owner: «Isz используй без ремозаика»: the Quad /
 * Tetra mosaics, not the remosaiced 6 / 31); DCG 14 bit of the three by the manual strip's DCG button (Dcg14Button). The Hybrid merges the mosaics
 * natively (their colour block is measured), P121 sets their white balance; a forced sensor mode is a sensor crop, so the frame is
 * not cropped again. The camera role comes from com.vivo.chi.override.CameraType («Master», «Wide», «Tele3P5x»).
 * scam_dev "vivo_qcom_roi 0": no new modules.
 * <p>
 * P126 (X300 Ultra video / log 2026-10-14: «ISZ модули дублируются и не удаляются через настройки»): an offer is made once
 * whether or not a slot is free (a module the strip had no room for came back as soon as the owner deleted another one), a
 * module already on the strip (same camera, same forced mode) is not made again, and the extras are removed: a second ISZ
 * module of the same camera and mode, and once the plain «2×» crop the CROPPED_RAW module (IszModule) put next to the ISZ one.
 */
public final class VivoQcomIsz {
    private static final String TAG = "VivoQcomIsz";
    static final String KEY = "vivo.control.forceSensorMode";
    private static final String OFFERED = "module_vivo_qcom_roi_offered_";
    private static final String CLEANUP = "module_vivo_qcom_roi_cleanup_1";

    static final int MAIN_2X = 10, WIDE_2X = 21, TELE_2X = 5, TELE_4X = 7;
    /** DCG 14 bit «Open Gate» (the full 4:3 field) of each camera (owner: «ISZ и DCG доступен у 300 ультра на всех трех модулях»). */
    static final int MAIN_DCG = 17, WIDE_DCG = 11, TELE_DCG = 20;

    private VivoQcomIsz() {}

    /**
     * vivo X300 Ultra of any region (owner: «для любого региона»): PD2547 / V2547A / V2547DA (CN, satellite), V2562 (global), or
     * any vivo whose market name (ro.vivo.market.name) says X300 Ultra.
     */
    public static boolean device() {
        return x300Ultra(android.os.Build.MANUFACTURER, android.os.Build.DEVICE, android.os.Build.MODEL, marketName());
    }

    static boolean x300Ultra(@Nullable String manufacturer, @Nullable String device, @Nullable String model, @Nullable String market) {
        if (manufacturer == null || !manufacturer.equalsIgnoreCase("vivo")) return false;
        String d = device == null ? "" : device.toUpperCase(Locale.ROOT), m = model == null ? "" : model.toUpperCase(Locale.ROOT);
        if (d.startsWith("PD2547") || d.startsWith("PD2562") || d.equals("V2562") || m.startsWith("V2547") || m.startsWith("V2562")) return true;
        return market != null && market.toUpperCase(Locale.ROOT).replace(" ", "").contains("X300ULTRA");
    }

    private static volatile String sMarket;

    @Nullable
    static String marketName() {
        String m = sMarket;
        if (m != null) return m;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            m = (String) sp.getMethod("get", String.class, String.class).invoke(null, "ro.vivo.market.name", "");
        } catch (Exception e) {
            m = "";
        }
        sMarket = m;
        return m;
    }

    public static boolean enabled() {
        return device() && PreferenceKeys.scamDevSwitch("vivo_qcom_roi", true);
    }

    /** The camera's role from its vivo CameraType: "main", "wide", "tele" or null. */
    @Nullable
    static String role(@Nullable String cameraType) {
        if (cameraType == null) return null;
        String t = cameraType.trim().toLowerCase(Locale.ROOT);
        if (t.contains("external") || t.equals("dummy")) return null;
        if (t.equals("master") || t.equals("main")) return "main";
        if (t.equals("wide") || t.startsWith("uw") || t.contains("ultrawide")) return "wide";
        if (t.startsWith("tele")) return "tele";
        return null;
    }

    /** Creates the ISZ modules once each. */
    public static void ensureModules(@Nullable Context context) {
        if (context == null || !enabled()) return;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            SharedPreferences p = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
            removeExtras(p);
            for (String slot : ModuleRegistry.slots()) {
                if (!slot.startsWith("back") || !ModuleRegistry.visible(slot) || ModuleRegistry.duplicateOf(slot) != null) continue;
                String camera = ModuleRegistry.camera(slot);
                if (camera == null || camera.isEmpty() || camera.contains("-") || ModuleRegistry.sensorMode(slot) != 0) continue;
                String role;
                try {
                    role = role(com.particlesdevs.photoncamera.api.CameraManager2.vivoCameraType(manager.getCameraCharacteristics(camera)));
                } catch (Exception e) {
                    continue; // a slot of another camera list
                }
                if (role == null) continue;
                float z = ModuleRegistry.zoom(slot);
                switch (role) {
                    case "main": offer(context, p, slot, camera, "2x", MAIN_2X, z * 2f); break;
                    case "wide": offer(context, p, slot, camera, "2x", WIDE_2X, z * 2f); break;
                    default:
                        offer(context, p, slot, camera, "2x", TELE_2X, z * 2f);
                        offer(context, p, slot, camera, "4x", TELE_4X, z * 4f);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "sensor-mode modules not created: " + e);
        }
    }

    static VendorTagUtils.TunableKey sessionKey(int mode) {
        return new VendorTagUtils.TunableKey(VendorTagUtils.TunableKey.TYPE_SESSION, KEY, Integer.class, mode);
    }

    /** «3.7×» / «7.4×» / «15×» as the automatic labels are stored. */
    static String label(float zoom) {
        String s = zoom >= 10f || Math.abs(zoom - Math.round(zoom)) < 0.05f
                ? String.valueOf(Math.round(zoom)) : String.format(Locale.US, "%.1f", zoom);
        return s + "×";
    }

    /** A module button as {@link #extras} sees it: camera, zoom, its forced sensor mode (0: none), duplicate or original. */
    static final class Mod {
        final String slot, camera;
        final float zoom;
        final int mode;
        final boolean duplicate;

        Mod(String slot, String camera, float zoom, int mode, boolean duplicate) {
            this.slot = slot;
            this.camera = camera;
            this.zoom = zoom;
            this.mode = mode;
            this.duplicate = duplicate;
        }
    }

    /**
     * P126: the back buttons that are too many: a duplicate with the same camera and forced mode as an earlier one, and (once,
     * {@code plainTwins}) a duplicate without a forced mode at the zoom of an ISZ module of its camera. Originals stay.
     */
    static List<String> extras(List<Mod> mods, boolean plainTwins) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Mod m : mods)
            if (m.mode > 0 && !seen.add(m.camera + "_" + m.mode) && m.duplicate) out.add(m.slot);
        if (plainTwins) for (Mod m : mods) {
            if (!m.duplicate || m.mode > 0) continue;
            for (Mod isz : mods)
                if (isz.mode > 0 && isz.camera.equals(m.camera) && !out.contains(isz.slot) && Math.abs(m.zoom / isz.zoom - 1f) < 0.05f) {
                    out.add(m.slot);
                    break;
                }
        }
        return out;
    }

    private static void removeExtras(SharedPreferences p) {
        List<Mod> mods = new ArrayList<>();
        for (String slot : ModuleRegistry.slots())
            if (slot.startsWith("back") && ModuleRegistry.visible(slot))
                mods.add(new Mod(slot, ModuleRegistry.camera(slot), ModuleRegistry.zoom(slot), ModuleRegistry.sensorMode(slot),
                        ModuleRegistry.duplicateOf(slot) != null));
        final boolean once = !p.getBoolean(CLEANUP, false);
        for (String slot : extras(mods, once)) {
            String label = ModuleRegistry.label(slot), camera = ModuleRegistry.camera(slot);
            if (ModuleRegistry.remove(slot)) Log.i(TAG, "extra module " + slot + " («" + label + "», camera " + camera + ") removed");
        }
        if (once) p.edit().putBoolean(CLEANUP, true).apply();
    }

    /** A visible duplicate of {@code camera} that forces {@code mode} already (made before, or restored with the settings). */
    @Nullable
    private static String existing(String camera, int mode) {
        for (String slot : ModuleRegistry.slots())
            if (slot.startsWith("back") && ModuleRegistry.visible(slot) && ModuleRegistry.duplicateOf(slot) != null
                    && camera.equals(ModuleRegistry.camera(slot)) && ModuleRegistry.sensorMode(slot) == mode) return slot;
        return null;
    }

    private static void offer(Context context, SharedPreferences p, String source, String camera, String what, int mode, float zoom) {
        String offered = OFFERED + camera + "_" + what;
        if (p.getBoolean(offered, false)) return;
        // offered once, made or not: a module deleted (or never made for want of a slot) does not come back
        p.edit().putBoolean(offered, true).apply();
        if (existing(camera, mode) != null) return;
        String target = ModuleRegistry.duplicate(source);
        if (target == null) {
            Log.w(TAG, "no free module slot for the " + what + " ISZ of camera " + camera + " (delete a button and add it by hand: "
                    + "duplicate the module, session key " + KEY + " " + mode + ")");
            return;
        }
        String label = label(zoom);
        p.edit()
                .putString("module_name_" + target, label)
                .putString("module_zoom_" + target, String.format(Locale.US, "%.2f", zoom))
                .apply();
        List<VendorTagUtils.TunableKey> keys = new ArrayList<>();
        keys.add(sessionKey(mode));
        TunableKeyManager.saveKeys(context, target, keys);
        Log.i(TAG, "ISZ module " + target + " («" + label + "», " + what + " of camera " + camera + "): session " + KEY + " " + mode);
    }

    // ---- DCG 14 bit by the button of the manual strip (Dcg14Button, its on / off in Dcg14Mode; owner: «включение DCG по кнопке
    // как у 500 про макс»), on the main, the ultra-wide and the tele (their 14-bit Open Gate modes)

    /** The DCG sensor mode of a camera role, 0 for none. */
    static int dcgMode(@Nullable String role) {
        if (role == null) return 0;
        switch (role) {
            case "main": return MAIN_DCG;
            case "wide": return WIDE_DCG;
            case "tele": return TELE_DCG;
            default: return 0;
        }
    }

    private static int dcgMode(@Nullable android.hardware.camera2.CameraCharacteristics c) {
        return c == null ? 0 : dcgMode(role(com.particlesdevs.photoncamera.api.CameraManager2.vivoCameraType(c)));
    }

    /** The button is shown: an X300 Ultra camera with a DCG mode (main, ultra-wide, tele). */
    public static boolean dcgAvailable(@Nullable android.hardware.camera2.CameraCharacteristics c) {
        return c != null && enabled() && dcgMode(c) > 0;
    }

    /** The active module forces no sensor mode of its own (an ISZ module): DCG may set one. */
    public static boolean plainModule() {
        try {
            return ModuleRegistry.sensorMode(ModuleRegistry.active()) == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Sets the DCG sensor mode as a session parameter; the log text of what was done. */
    public static String applyDcg(android.hardware.camera2.CaptureRequest.Builder session,
                                  @Nullable android.hardware.camera2.CameraCharacteristics c) {
        int mode = dcgMode(c);
        if (session == null || mode <= 0) return "off";
        try {
            session.set(new android.hardware.camera2.CaptureRequest.Key<>(KEY, Integer.class), mode);
            return KEY + " " + mode + " (DCG 14 bit)";
        } catch (RuntimeException e) {
            return "refused: " + e.getMessage();
        }
    }
}
