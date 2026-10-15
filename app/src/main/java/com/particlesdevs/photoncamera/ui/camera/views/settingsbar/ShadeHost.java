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

    /** The settings screen (the shade header's gear and «Все настройки»): CameraFragment.launchSettings. */
    void openSettings();

    /** Values may have changed (a write, a refresh): the camera screen updates what shows them (top bar badges). */
    void onValuesChanged();

    /** P143: the config menu's «Импорт из файла…» - the system picker (SettingsActivity.ACTION_IMPORT_CONFIG). */
    default void importConfigFile() { }

    /** P143: «Открыть папку конфигов» - the lasting access to Download/SCAMERA (SettingsActivity.ACTION_OPEN_FOLDER). */
    default void openConfigFolder() { }
}
