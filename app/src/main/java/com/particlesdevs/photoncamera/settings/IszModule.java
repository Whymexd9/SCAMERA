package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import com.particlesdevs.photoncamera.app.PhotonCamera;
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

    private IszModule() {}

    /** Creates the ISZ button of the back main camera when it can and has not done so before. */
    public static void ensure(Context context) {
        if (context == null || !PreferenceKeys.scamDevSwitch("isz_module", true)) return;
        // vivo MediaTek: VivoMtkIsz makes the main «2×» (a second «2×» here was one too many, X200 Pro video 2026-10-13)
        if (com.particlesdevs.photoncamera.capture.VivoMtkIsz.enabled()) return;
        try {
            SharedPreferences p = PhotonCamera.getSettingsManagerStatic().getDefaultPreferences();
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
}
