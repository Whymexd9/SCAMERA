package com.particlesdevs.photoncamera.capture.spoof;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.util.Log;

/**
 * Applies the three package spoof methods in the same order as the LMC mod:
 * OplusCameraManager.saveOpPackageName, CameraManager context, raw ICameraService Binder.
 * Called before the camera list is read and again right before every openCamera(),
 * so a changed setting or a restarted camera service is picked up without a relaunch.
 */
public final class CameraPackageSpoof {
    private static final String TAG = "CameraPkgSpoof";
    private static boolean initialized;

    private CameraPackageSpoof() {}

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
        } catch (Throwable error) {
            // A spoof failure must never block camera startup.
            Log.e(TAG, "Package spoof failed", error);
        }
    }
}
