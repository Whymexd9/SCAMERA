package com.particlesdevs.photoncamera.capture;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.capture.spoof.CameraPackageSpoof;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

public final class VivoSdkFullRes {
    private VivoSdkFullRes() { }

    public static boolean supportsMode(CameraMode mode) {
        return CameraMode.isStill(mode);
    }

    public static boolean available(String cameraId) {
        return offered(cameraId) && !SensorModeSwitches.sensorPreset(
                com.particlesdevs.photoncamera.settings.ModuleRegistry.active()) && !Dcg14Mode.on();
    }

    public static boolean offered(String cameraId) {
        return VivoQcomModes.device() && "3".equals(cameraId)
                && "off".equals(CameraPackageSpoof.signature(ScameraApp.getAppContext()))
                && PreferenceKeys.scamDevSwitch("vivo_sdk_fullres", true);
    }
}
