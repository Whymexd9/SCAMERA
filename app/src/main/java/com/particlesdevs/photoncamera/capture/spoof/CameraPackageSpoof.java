package com.particlesdevs.photoncamera.capture.spoof;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.util.Log;

/**
 * Applies the three package spoof methods in the same order as the SCAM mod:
 * OplusCameraManager.saveOpPackageName, CameraManager context, raw ICameraService Binder.
 * Called before the camera list is read and again right before every openCamera(),
 * so a changed setting or a restarted camera service is picked up without a relaunch.
 */
public final class CameraPackageSpoof {
    private static final String TAG = "CameraPkgSpoof";
    private static boolean initialized;

    private CameraPackageSpoof() {}

    private static String lastDiagnosis = "";

    /** "off", or the configured packages: the cameras the service exposes depend on it. */
    public static String signature(Context context) {
        try {
            if (!android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean(CameraPackageSpoofSettings.KEY_ENABLED, false)
                    && com.particlesdevs.photoncamera.capture.VivoQcomModes.spoofPackage() == null) return "off";
            return "on:" + CameraPackageSpoofSettings.getOplusPackage(context) + "|"
                    + CameraPackageSpoofSettings.getGenericPackage(context) + "|"
                    + CameraPackageSpoofSettings.getBinderPackage(context);
        } catch (Throwable error) {
            return "off";
        }
    }

    /** With the spoof enabled: what the camera service now answers (logcat tag SCAMERA_SPOOF). */
    private static void diagnose(Context context, CameraManager manager) {
        try {
            if (!android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean(CameraPackageSpoofSettings.KEY_ENABLED, false)
                    && com.particlesdevs.photoncamera.capture.VivoQcomModes.spoofPackage() == null) return;
            StringBuilder sb = new StringBuilder("spoof oplus=" + CameraPackageSpoofSettings.getOplusPackage(context)
                    + " generic=" + CameraPackageSpoofSettings.getGenericPackage(context)
                    + " binder=" + CameraPackageSpoofSettings.getBinderPackage(context) + " ids=");
            String[] listed = manager.getCameraIdList();
            sb.append(java.util.Arrays.toString(listed));
            for (String id : listed) {
                try {
                    java.util.Set<String> physical = manager.getCameraCharacteristics(id).getPhysicalCameraIds();
                    if (physical != null && !physical.isEmpty()) sb.append(" logical ").append(id).append("->").append(physical);
                } catch (Throwable ignored) { }
            }
            java.util.List<String> known = java.util.Arrays.asList(listed);
            for (int i = 0; i < 10; i++) {
                String id = String.valueOf(i);
                if (known.contains(id)) continue;
                try {
                    manager.getCameraCharacteristics(id);
                    sb.append(" unlisted ").append(id).append(" readable");
                } catch (Throwable t) {
                    sb.append(" unlisted ").append(id).append(' ').append(t.getClass().getSimpleName());
                }
            }
            String text = sb.toString();
            if (!text.equals(lastDiagnosis)) { lastDiagnosis = text; Log.i("SCAMERA_SPOOF", text); }
        } catch (Throwable error) {
            Log.w(TAG, "Spoof diagnosis failed", error);
        }
    }

    /** Tests only: forces {@link #typedAttribution()} (null: the device and the dev switch decide). */
    static volatile Boolean typedForTest;

    /**
     * The typed AttributionSource rewrite (ICameraService proxy + the context's AttributionSource) is the vivo Qualcomm
     * experiment only (dev switch vivo_qcom_spoof, P109l). With the owner's spoof it broke every camera: the OPPO Find X7 Ultra
     * (2026-10-15 log) opened all five cameras, then every request failed with -38 and onError 4; the raw Binder package
     * rewrite of build 30799 ran there.
     */
    static boolean typedAttribution() {
        Boolean forced = typedForTest;
        if (forced != null) return forced;
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                && com.particlesdevs.photoncamera.capture.VivoQcomModes.spoofPackage() != null;
    }

    public static synchronized void apply(Context context, CameraManager cameraManager) {
        try {
            if (!initialized) {
                OplusCameraPackageSpoof.initialize(context);
                GenericCameraPackageSpoof.initialize(context);
                BinderCameraPackageSpoof.initialize(context);
                initialized = true;
            } else {
                OplusCameraPackageSpoof.apply();
            }
            GenericCameraPackageSpoof.apply(cameraManager);
            BinderCameraPackageSpoof.apply();
            diagnose(context, cameraManager);
        } catch (Throwable error) {
            // A spoof failure must never block camera startup.
            Log.e(TAG, "Package spoof failed", error);
        }
    }
}
