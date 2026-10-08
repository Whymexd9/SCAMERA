package com.particlesdevs.photoncamera.pro;

import android.content.Context;
import android.os.Build;
import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;

/**
 * Device-specific tuning of this phone: {@link Specific} (dual session, black level, camera ids) and {@link SensorSpecifics}
 * (noise model, colour transforms per sensor), from a local tuning file or the assets bundled with the app. The upstream
 * PhotonCamera downloads («Подгрузить конфигурации», the supported-devices list) are gone (owner, 8 October 2026).
 */
public class SupportedDevice {
    public static final String THIS_DEVICE = Build.BRAND.toLowerCase() + ":" + Build.DEVICE.toLowerCase();
    private static final String TAG = "SupportedDevice";
    /** Left in the devices file by the removed downloads: the supported-devices list and the downloaded sensor tuning. */
    private static final String[] DOWNLOADED = {"all_devices_names", "sensor_specific_val"};
    private final SettingsManager mSettingsManager;
    private final Context mContext;
    public Specific specific;
    public SensorSpecifics sensorSpecifics;
    private int checkedCount = 0;

    public SupportedDevice(SettingsManager manager, Context context) {
        mSettingsManager = manager;
        mContext = context;
        sensorSpecifics = new SensorSpecifics();
        specific = new Specific(mSettingsManager);
    }

    public void loadCheck() {
        if (checkedCount < 1) {
            checkedCount++;
            dropDownloads();
            specific.loadSpecific(mContext);
        }
        Log.d(TAG, "Checked count:" + checkedCount);
        sensorSpecifics.loadSpecifics(mSettingsManager, mContext); // No need for thread with assets
    }

    /** An earlier download must not keep acting: the bundled assets or the local tuning file are the only sources now. */
    private void dropDownloads() {
        try {
            android.content.SharedPreferences devices = mContext.getSharedPreferences(
                    mContext.getPackageName() + PreferenceKeys.Key.DEVICES_PREFERENCE_FILE_NAME.mValue, Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = null;
            for (String key : DOWNLOADED) {
                if (!devices.contains(key)) continue;
                if (editor == null) editor = devices.edit();
                editor.remove(key);
            }
            if (editor != null) editor.apply();
        } catch (RuntimeException e) {
            Log.e(TAG, "Downloaded device configurations not removed: " + e);
        }
    }
}
