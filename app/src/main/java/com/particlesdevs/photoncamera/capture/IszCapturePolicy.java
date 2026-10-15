package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;

import com.particlesdevs.photoncamera.api.CameraManager2;
import com.particlesdevs.photoncamera.api.VendorTagUtils;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.control.ZoomController;
import com.particlesdevs.photoncamera.settings.ModuleRegistry;
import com.particlesdevs.photoncamera.settings.TunableKeyManager;

public final class IszCapturePolicy {
    private IszCapturePolicy() { }

    public static boolean isIsz(String slot, CameraCharacteristics characteristics, CaptureResult result,
                                String physicalId, String logicalId, boolean croppedRawSession) {
        if (Dcg14Mode.on() || PixelPro10.fullResOn()) return false;
        int mode = sessionForceMode(slot);
        if (mode != 0) {
            if (VivoQcomModes.device()) {
                if ("3".equals(physicalId) || "4".equals(physicalId)) return mode == 3;
                return "5".equals(physicalId) && (mode == 5 || mode == 7 || mode == 6 || mode == 17);
            }
            if (!VivoQcomIsz.enabled() || characteristics == null) return false;
            String role = VivoQcomIsz.role(CameraManager2.vivoCameraType(characteristics));
            return "main".equals(role) && mode == VivoQcomIsz.MAIN_2X
                    || "wide".equals(role) && mode == VivoQcomIsz.WIDE_2X
                    || "tele".equals(role) && (mode == VivoQcomIsz.TELE_2X || mode == VivoQcomIsz.TELE_4X);
        }
        if (ModuleRegistry.sensorMode(slot) != 0) return false;
        if (SensorModeSwitches.sensorPreset(slot)) return true;
        if (!croppedRawSession || !CroppedRaw.supported(characteristics)) return false;
        return CroppedRaw.factor(CroppedRaw.of(result, physicalId, logicalId),
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)) > 1f
                || ZoomController.residual() >= 1.8f;
    }

    private static int sessionForceMode(String slot) {
        int mode = 0;
        for (VendorTagUtils.TunableKey key : TunableKeyManager.loadKeys(
                ScameraApp.getSettingsManagerStatic().getContext(), slot)) {
            if (key == null || !key.enabled || !VendorTagUtils.TunableKey.TYPE_SESSION.equals(key.type)
                    || !VivoQcomIsz.KEY.equals(key.name) || !"Integer".equals(key.valueType)) continue;
            try {
                mode = (Integer) key.parseValue();
            } catch (RuntimeException invalid) { }
        }
        return mode;
    }
}
