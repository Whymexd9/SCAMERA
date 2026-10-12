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
        return Dcg14Mode.device() && PreferenceKeys.scamDevSwitch("vivo_isz", true);
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

    /** Creates the main 2x and tele 2x / 4x ISZ modules once (X500 Pro Max). */
    public static void ensureModules(@Nullable Context context) {
        if (context == null || !enabled()) return;
        try {
            SharedPreferences p = PhotonCamera.getSettingsManagerStatic().getDefaultPreferences();
            String main = null, tele = null, wide = null;
            float teleZoom = 0f, wideZoom = 0f;
            for (String slot : ModuleRegistry.slots()) {
                if (!slot.startsWith("back") || !ModuleRegistry.visible(slot) || ModuleRegistry.duplicateOf(slot) != null) continue;
                float z = ModuleRegistry.zoom(slot);
                if (main == null && Math.abs(z - 1f) < 0.05f) main = slot;
                if (wide == null && z > 0.3f && z < 0.9f) { wide = slot; wideZoom = z; }
                // the tele around 3.5x (the X500 Pro Max lists two: the module of the highest ratio below 6x)
                if (z > 2.5f && z < 6f && z > teleZoom) { tele = slot; teleZoom = z; }
            }
            if (main != null) offer(context, p, main, 2, 2f, "2×");
            if (wide != null) offer(context, p, wide, 2, wideZoom * 2f, label(wideZoom * 2f));
            if (tele != null) {
                offer(context, p, tele, 2, teleZoom * 2f, label(teleZoom * 2f));
                offer(context, p, tele, 4, teleZoom * 4f, label(teleZoom * 4f));
            }
        } catch (Exception e) {
            Log.w(TAG, "ISZ modules not created: " + e);
        }
    }

    /** «1,3×» / «7×» / «14×»: one decimal below 10 (comma), none above; a whole number without it. */
    static String label(float zoom) {
        String s = zoom >= 10f || Math.abs(zoom - Math.round(zoom)) < 0.05f
                ? String.valueOf(Math.round(zoom)) : String.format(Locale.US, "%.1f", zoom).replace('.', ',');
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
