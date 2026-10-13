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
                    .getBoolean(CameraPackageSpoofSettings.KEY_ENABLED, false)) return "off";
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
                    .getBoolean(CameraPackageSpoofSettings.KEY_ENABLED, false)) return;
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
