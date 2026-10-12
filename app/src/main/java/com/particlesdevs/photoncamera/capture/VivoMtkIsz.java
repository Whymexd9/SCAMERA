package com.particlesdevs.photoncamera.capture;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.TunableKeyManager;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P109 In-sensor zoom on the vivo X500 Pro Max (owner, 2026-10-13: «мы сможем заставить работать на нём ISZ?», «нужен и 4x
 * isz, так же нужны 2x/4x isz на ширике и телевике»).
 * <p>
 * The phone's scenario tables (SCAM TEST report, /odm/etc/camera/scenario_table_*pd2602.xml): main IMX09C (50 MP Quad)
 * CROP_2X_4CELL (mode 12) in PHOTO_DEFAULT, no 4x crop; tele S5KHPE (200 MP) CROP_2X_4CELL (12) and CROP_4X_16CELL (15) in
 * PHOTO_DEFAULT; ultra-wides JN5 / JN1 only FULL_4CELL (the full 50 MP), no crop. The sensor-mode selector
 * (sensormode-selector/PD2602.json) reaches these only for AppType vivo: PHOTO (camera scenario 500, PHOTO_DEFAULT) and,
 * under the vivo app's photo UI modes, «vivo.control.seamless.roiRemosaic == 4» -> ROIREMOSAIC (2x crop, hardware remosaic),
 * «com.mediatek.control.capture.remosaicenable == 1» + «vivo.control.seamless.remosaic.enable == 1» -> SATREMOSAIC.
 * <p>
 * So the ISZ modules are duplicates (P21) of the main and the tele with their own vendor keys (module settings, «Добавить
 * vendor tag»: editable there) and, while one is active, the package spoof opens the camera as com.android.camera
 * (Dcg14Mode.spoofPackage). Main «2×» and tele 2x («7×»): seamless.roiRemosaic 4; tele 4x («14×»): the same request, the 4x
 * key is not in the files (to be found on the phone: the module's keys are editable); ultra-wide 2x (owner: «у ширика должен
 * быть 2x ISZ»): its table has no crop mode, only FULL_4CELL, so the 2x is the ROI of the full 50 MP readout (the same
 * roiRemosaic request). 4x on the tele only (owner). The module's real field of view is measured against its plain module
 * (P60 fov_check), so a crop the sensor did not make is done digitally. Each module is offered once per camera and factor; a
 * deleted one stays deleted. scam_dev "vivo_isz 0" turns the modules' keys and spoof off.
 */
public final class VivoMtkIsz {
    private static final String TAG = "VivoMtkIsz";
    static final String MARK = "module_vivo_isz_";
    private static final String OFFERED = "module_vivo_isz_offered_";
    static final String ROI_KEY = "vivo.control.seamless.roiRemosaic";

    private VivoMtkIsz() {}

    public static boolean enabled() {
        return device() && PreferenceKeys.scamDevSwitch("vivo_isz", true);
    }

    /**
     * P109b (owner, 2026-10-13: «изучи дампы всех смартфонов на MTK и сделай для них так же рабочий ISZ»): every vivo phone on
     * MediaTek. The selectors of the X500 Pro Max (PD2602), V2515 (PD2502) and X200 Pro (PD2405) all have the same branch:
     * AppType vivo, photo UI mode, vivo.control.seamless.roiRemosaic 4 -> ROIREMOSAIC.
     */
    public static boolean device() {
        return Dcg14Mode.device() || vivoMediatek(android.os.Build.MANUFACTURER, android.os.Build.HARDWARE,
                android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MANUFACTURER : null);
    }

    static boolean vivoMediatek(@Nullable String manufacturer, @Nullable String hardware, @Nullable String soc) {
        if (manufacturer == null || !manufacturer.equalsIgnoreCase("vivo")) return false;
        if (soc != null && soc.toLowerCase(Locale.ROOT).contains("mediatek")) return true;
        return hardware != null && hardware.toLowerCase(Locale.ROOT).startsWith("mt");
    }

    /** A 200 MP-class tele (1/1.4" and larger: the X500 Pro Max / X200 Pro S5KHPE / HP9 9.14 mm wide) has the 4x crop. */
    static boolean fourXSensor(float sensorWidthMm) {
        return sensorWidthMm >= 8f;
    }

    /** The ISZ factor of the active module (2 / 4), 0 when it is not one. */
    public static int activeFactor() {
        if (!enabled()) return 0;
        try {
            SharedPreferences p = PhotonCamera.getSettingsManagerStatic().getDefaultPreferences();
            return p.getInt(MARK + ModuleRegistry.active(), 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Creates once: main 2x, every tele 2x (two teles of the same ratio once), 4x on a 200 MP-class tele, ultra-wide 2x
     * (vivo MediaTek phones).
     */
    public static void ensureModules(@Nullable Context context) {
        if (context == null || !enabled()) return;
        try {
            SharedPreferences p = PhotonCamera.getSettingsManagerStatic().getDefaultPreferences();
            android.hardware.camera2.CameraManager manager =
                    (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String main = null, wide = null;
            float wideZoom = 0f;
            List<String> teles = new ArrayList<>();
            List<Float> teleZooms = new ArrayList<>();
            for (String slot : ModuleRegistry.slots()) {
                if (!slot.startsWith("back") || !ModuleRegistry.visible(slot) || ModuleRegistry.duplicateOf(slot) != null) continue;
                if (p.getInt(MARK + slot, 0) > 0) continue;
                float z = ModuleRegistry.zoom(slot);
                if (main == null && Math.abs(z - 1f) < 0.05f) main = slot;
                if (wide == null && z > 0.3f && z < 0.9f) { wide = slot; wideZoom = z; }
                if (z >= 1.8f) {
                    boolean same = false;
                    for (float t : teleZooms) same |= Math.abs(z / t - 1f) < 0.05f; // X500 Pro Max: two 3.5x modules
                    if (!same) { teles.add(slot); teleZooms.add(z); }
                }
            }
            if (main != null) offer(context, p, main, 2, 2f, "2×");
            for (int i = 0; i < teles.size(); i++) {
                String tele = teles.get(i);
                float z = teleZooms.get(i);
                offer(context, p, tele, 2, z * 2f, label(z * 2f));
                if (fourXSensor(sensorWidth(manager, ModuleRegistry.camera(tele)))) offer(context, p, tele, 4, z * 4f, label(z * 4f));
            }
            if (wide != null) offer(context, p, wide, 2, wideZoom * 2f, label(wideZoom * 2f));
        } catch (Exception e) {
            Log.w(TAG, "ISZ modules not created: " + e);
        }
    }

    private static float sensorWidth(@Nullable android.hardware.camera2.CameraManager manager, String cameraId) {
        try {
            String id = cameraId.contains("-") ? cameraId.split("-", 2)[1] : cameraId;
            android.util.SizeF s = manager == null ? null : manager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            return s == null ? 0f : s.getWidth();
        } catch (Exception e) {
            return 0f;
        }
    }

    /** «1.3×» / «7×» / «14×» as the automatic labels are stored (the strip shows the decimal comma on a Russian UI). */
    static String label(float zoom) {
        String s = zoom >= 10f || Math.abs(zoom - Math.round(zoom)) < 0.05f
                ? String.valueOf(Math.round(zoom)) : String.format(Locale.US, "%.1f", zoom);
        return s + "×";
    }

    private static void offer(Context context, SharedPreferences p, String source, int factor, float zoom, String label) {
        String camera = ModuleRegistry.camera(source);
        String offered = OFFERED + camera + "_" + factor;
        if (p.getBoolean(offered, false)) return;
        String target = ModuleRegistry.duplicate(source);
        SharedPreferences.Editor e = p.edit().putBoolean(offered, true);
        if (target == null) {
            Log.w(TAG, "no free module slot for the " + factor + "x ISZ of camera " + camera);
            e.apply();
            return;
        }
        e.putString("module_name_" + target, label)
                .putString("module_zoom_" + target, String.format(Locale.US, "%.2f", zoom))
                .putInt(MARK + target, factor)
                .apply();
        List<VendorTagUtils.TunableKey> keys = new ArrayList<>();
        keys.add(new VendorTagUtils.TunableKey("CaptureRequest", ROI_KEY, Integer.class, 4));
        TunableKeyManager.saveKeys(context, target, keys);
        Log.i(TAG, "ISZ module " + target + " («" + label + "», " + factor + "x of camera " + camera + "): " + ROI_KEY + " 4");
    }
}
