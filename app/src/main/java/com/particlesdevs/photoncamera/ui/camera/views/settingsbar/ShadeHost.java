package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import com.particlesdevs.photoncamera.settings.SettingType;
import com.particlesdevs.photoncamera.settings.ShadeCatalog;

/** What the shade needs from the camera screen (CameraFragment). */
public interface ShadeHost {
    /** A camera control (flash, self-timer, file format, Camera2 metering): CameraUIController's existing path. */
    void applyCameraControl(SettingType type, int value);

    /** A setting of the tree was written: redraw or restart what reads it (grid overlay, session-time keys). */
    void onSettingWritten(ShadeCatalog.Entry entry);

    /** A short card toast. */
    void showMessage(CharSequence text);

    /** The catalog «Добавить в шторку» over the camera screen. */
    void openCatalog();
}
