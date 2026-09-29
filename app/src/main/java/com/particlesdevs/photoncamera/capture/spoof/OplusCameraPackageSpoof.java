package com.particlesdevs.photoncamera.capture.spoof;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

public final class OplusCameraPackageSpoof {
    private static final String TAG = "OplusPkgSpoof";
    private static volatile Context appContext;
    private static volatile boolean applied;

    private OplusCameraPackageSpoof() {}

    public static boolean initialize(Context context) {
        Context application = context.getApplicationContext();
        appContext = application != null ? application : context;
        return apply();
    }

    public static boolean apply() {
        Context context = appContext;
        if (context == null) {
            Log.w(TAG, "Context is unavailable");
            return false;
        }

        String targetPackage = CameraPackageSpoofSettings.getOplusPackage(context);
        // Default value and never spoofed: nothing to restore, do not touch the vendor manager.
        if (!applied && context.getPackageName().equals(targetPackage)) return true;
        try {
            Class<?> managerClass =
                    Class.forName("android.hardware.camera2.OplusCameraManager");

            Method getInstance = managerClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object manager = getInstance.invoke(null);
            if (manager == null) {
                Log.w(TAG, "OplusCameraManager.getInstance() returned null");
                return false;
            }

            Method saveOpPackageName =
                    managerClass.getDeclaredMethod("saveOpPackageName", String.class);
            saveOpPackageName.setAccessible(true);
            saveOpPackageName.invoke(manager, targetPackage);
            applied = !context.getPackageName().equals(targetPackage);

            Log.i(TAG, "saveOpPackageName called: " + targetPackage);
            return true;
        } catch (ClassNotFoundException notOplusDevice) {
            Log.d(TAG, "OplusCameraManager is not present");
            return false;
        } catch (Throwable error) {
            Log.e(TAG, "OplusCameraManager package spoof failed", error);
            return false;
        }
    }
}
