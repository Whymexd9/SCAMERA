package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.CroppedRaw;
import com.particlesdevs.photoncamera.util.Log;

/**
 * P100 The in-sensor zoom as its own module button (owner, 2026-10-11: «ISZ для S24U как отдельную кнопку модуля ... с
 * соответствующим обозначением X зума относительно основы»). A main camera whose RAW stream takes CROPPED_RAW (Galaxy S24
 * Ultra) gets, once, a second button on the same camera at {@link #ZOOM}x labelled «2×»: on it the viewfinder runs at zoom
 * ratio 2, the HAL switches the sensor to its full-resolution centre (the max-resolution arrays of the S24U say binning 2x2) and
 * the photo is not cropped again (CroppedRaw / ZoomController.rawCropOfShot). It is an ordinary duplicate (P21): renamed,
 * re-zoomed or deleted in the module settings like any other; a deleted one is not created again.
 */
public final class IszModule {
    private static final String TAG = "IszModule";
    static final float ZOOM = 2f;
    static final String LABEL = "2×";
    private static final String OFFERED = "module_isz_offered_";
    /** P109's vivo MediaTek ISZ modules (VivoMtkIsz, removed in P123): their marks and offered / refused flags. */
    static final String VIVO_MTK = "module_vivo_isz_";
    private static final String VIVO_MTK_REMOVED = "module_vivo_mtk_isz_removed";

    private IszModule() {}

    /** Creates the ISZ button of the back main camera when it can and has not done so before. */
    public static void ensure(Context context) {
        if (context == null || !PreferenceKeys.scamDevSwitch("isz_module", true)) return;
        removeVivoMtk();
        // P126: the X300 Ultra's main has its own ISZ module (VivoQcomIsz, sensor mode 10): no second «2×» crop here
        if (com.particlesdevs.photoncamera.capture.VivoQcomIsz.enabled()) return;
        try {
            SharedPreferences p = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            String main = null;
            CameraCharacteristics c = null;
            for (String slot : ModuleRegistry.slots()) {
                if (!slot.startsWith("back") || !ModuleRegistry.visible(slot) || ModuleRegistry.duplicateOf(slot) != null
                        || Math.abs(ModuleRegistry.zoom(slot) - 1f) >= 0.05f) continue;
                // the module's camera may be "logical-physical" (Galaxy S24 Ultra: "0-5"); its RAW comes from the physical
                // camera. A slot of another camera list (vivo with the package spoof on / off, V2515 log 2026-10-13_5: «unknown
                // device» on every start) is skipped.
                try {
                    c = manager.getCameraCharacteristics(SensorConfigPreferenceGenerator.toPhysicalId(ModuleRegistry.camera(slot)));
                } catch (IllegalArgumentException unknown) {
                    continue;
                }
                main = slot;
                break;
            }
            if (main == null) return;
            final String cameraId = ModuleRegistry.camera(main);
            if (p.getBoolean(OFFERED + cameraId, false)) return;
            if (!CroppedRaw.supported(c)) return;
            String target = ModuleRegistry.duplicate(main);
            SharedPreferences.Editor e = p.edit().putBoolean(OFFERED + cameraId, true);
            if (target != null) {
                e.putString("module_name_" + target, LABEL)
                        .putString("module_zoom_" + target, "2");
                Log.i(TAG, "in-sensor zoom module " + target + " («" + LABEL + "») on camera " + cameraId + " (CROPPED_RAW)");
            } else Log.w(TAG, "no free module slot for the in-sensor zoom of camera " + cameraId);
            e.apply();
        } catch (Exception e) {
            Log.w(TAG, "in-sensor zoom module not created: " + e);
        }
    }

    /**
     * P123 (owner, 2026-10-13: «На виво на мтк не работает ISZ и DCG»): the ISZ modules P109 made on vivo MediaTek phones (main
     * «2×», tele «7×» / «14×», ultra-wide 2x: duplicates with vivo.control.seamless.roiRemosaic 4) gave no sensor crop. Once:
     * those duplicates go and their marks with them.
     */
    private static void removeVivoMtk() {
        if (!vivoMediatek(android.os.Build.MANUFACTURER, android.os.Build.HARDWARE,
                android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MANUFACTURER : null)) return;
        try {
            SharedPreferences p = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
            if (p.getBoolean(VIVO_MTK_REMOVED, false)) return;
            for (String slot : ModuleRegistry.slots()) {
                if (p.getInt(VIVO_MTK + slot, 0) <= 0 || ModuleRegistry.duplicateOf(slot) == null) continue;
                String label = ModuleRegistry.label(slot);
                if (ModuleRegistry.remove(slot)) Log.i(TAG, "vivo MediaTek ISZ module " + slot + " («" + label + "») removed");
            }
            SharedPreferences.Editor e = p.edit();
            for (String k : p.getAll().keySet()) if (k.startsWith(VIVO_MTK)) e.remove(k);
            e.putBoolean(VIVO_MTK_REMOVED, true).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "vivo MediaTek ISZ modules not removed: " + e);
        }
    }

    static boolean vivoMediatek(String manufacturer, String hardware, String soc) {
        if (manufacturer == null || !manufacturer.equalsIgnoreCase("vivo")) return false;
        if (soc != null && soc.toLowerCase(java.util.Locale.ROOT).contains("mediatek")) return true;
        return hardware != null && hardware.toLowerCase(java.util.Locale.ROOT).startsWith("mt");
    }
}
