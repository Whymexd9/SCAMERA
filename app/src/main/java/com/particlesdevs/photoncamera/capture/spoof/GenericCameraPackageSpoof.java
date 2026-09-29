package com.particlesdevs.photoncamera.capture.spoof;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import java.lang.reflect.Field;

public final class GenericCameraPackageSpoof {
    private static final String TAG = "GenericPkgSpoof";
    private static volatile Context appContext;
    private static volatile boolean wrapped;

    private GenericCameraPackageSpoof() {}

    public static void initialize(Context context) {
        Context application = context.getApplicationContext();
        appContext = application != null ? application : context;
    }

    /**
     * Best-effort implementation for non-OPlus CameraManager variants.
     * This does not bypass CameraService UID/package validation.
     */
    public static boolean apply(CameraManager cameraManager) {
        Context context = appContext;
        if (context == null || cameraManager == null) {
            return false;
        }

        String targetPackage = CameraPackageSpoofSettings.getGenericPackage(context);
        if (context.getPackageName().equals(targetPackage) && !wrapped) {
            // Default value and never spoofed: do not touch CameraManager at all.
            return true;
        }
        try {
            Field contextField = findContextField(cameraManager.getClass());
            contextField.setAccessible(true);

            Object currentValue = contextField.get(cameraManager);
            if (!(currentValue instanceof Context)) {
                Log.w(TAG, "CameraManager.mContext is not a Context");
                return false;
            }

            if (currentValue instanceof SpoofCameraContext) {
                ((SpoofCameraContext) currentValue).setPackageName(targetPackage);
            } else {
                contextField.set(
                        cameraManager,
                        new SpoofCameraContext((Context) currentValue, targetPackage));
                wrapped = true;
            }

            Log.i(TAG, "CameraManager context package: " + targetPackage);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Generic CameraManager context spoof failed", error);
            return false;
        }
    }

    // AOSP names it mContext; some vendor frameworks rename or obfuscate it,
    // so fall back to the first non-static Context-typed field.
    private static Field findContextField(Class<?> type)
            throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField("mContext");
            } catch (NoSuchFieldException ignored) {
                // Continue with the superclass for vendor CameraManager subclasses.
            }
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && Context.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            }
        }
        throw new NoSuchFieldException("Context field in " + type.getName());
    }
}
