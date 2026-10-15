package com.particlesdevs.photoncamera.capture.spoof;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.AttributionSource;
import android.os.Build;

final class SpoofCameraContext extends ContextWrapper {
    private volatile String packageName;

    SpoofCameraContext(Context base, String packageName) {
        super(base);
        this.packageName = packageName;
    }

    void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    @Override
    public String getPackageName() {
        return packageName;
    }

    // Intentionally no @Override annotation: getOpPackageName() is absent from
    // some public SDK stubs but is virtual in the Android framework at runtime.
    public String getOpPackageName() {
        return packageName;
    }

    @Override
    public AttributionSource getAttributionSource() {
        // only for the typed experiment: with the owner's spoof a foreign package in the source failed every request (-38)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !CameraPackageSpoof.typedAttribution()) return super.getAttributionSource();
        // CameraManager on Android 16 sends this source instead of getOpPackageName().
        return new AttributionSource.Builder(super.getAttributionSource())
                .setPackageName(packageName).build();
    }
}
