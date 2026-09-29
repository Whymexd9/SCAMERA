package com.particlesdevs.photoncamera.capture.spoof;

import android.content.Context;
import android.content.ContextWrapper;

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
}
