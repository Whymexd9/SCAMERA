package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.settings.MotionMode;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * P103 Hidden HAL features found in the SCAM TEST reports (owner, 2026-10-11: «1-3 делай»). Each is set only where the camera
 * lists its key (session keys for the vendor ones), so phones without it see no change and get no session parameters:
 * <ul>
 *   <li>«Движение» (P86): the HAL's own motion helpers - MediaTek AI shutter {@code com.mediatek.3afeature.aishutterMode} = 1
 *       (byte; X9 Pro, X500 Pro Max, vivo V2515 list modes {0, 1}), OPPO {@code com.oplus.fastmotion.mode.enable} = 1 (byte; Find
 *       X7 Ultra), vivo {@code vivo.control.is_motion_autofocus} = 1 (int32). scam_dev "motion_hal_keys 0" = off.</li>
 *   <li>Qualcomm Ideal RAW: {@code org.codeaurora.qcamera3.sessionParameters.EnableIdealRAW} = 1 (byte, every Qualcomm phone),
 *       only with scam_dev "ideal_raw 1" (a trial: the RAW may come with the HAL's pixel corrections).</li>
 *   <li>Settings override ZOOM (Android 14; X100 Ultra, X200 Ultra, Xiaomi 17 Ultra): the viewfinder's zoom changes take effect
 *       a few frames earlier. scam_dev "zoom_override 0" = off.</li>
 *   <li>Low-light boost (AE mode 6, Android 15; X200 Ultra, Xiaomi 17 Ultra): a brighter viewfinder in the dark, the setting
 *       «Яркий видоискатель в темноте» (off by default: the ZSL ring frames get the boosted exposure too); not in «Движение».</li>
 * </ul>
 */
public final class HiddenFeatures {
    private static final String TAG = "HiddenFeatures";
    public static final String PREF_LOW_LIGHT_BOOST = "pref_low_light_boost";
    /** CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY (API 35). */
    static final int AE_LOW_LIGHT_BOOST = 6;
    /** CONTROL_SETTINGS_OVERRIDE_ZOOM (API 34). */
    static final int SETTINGS_OVERRIDE_ZOOM = 1;

    private HiddenFeatures() {}

    /** One vendor key to set: name, value class and value. */
    static final class VendorKey {
        final String name;
        final Class<?> type;
        final Object value;

        VendorKey(String name, Class<?> type, Object value) {
            this.name = name; this.type = type; this.value = value;
        }
    }

    /** The vendor keys of the active features this camera lists as session keys (empty: nothing to send). */
    static List<VendorKey> sessionKeys(@Nullable CameraCharacteristics c) {
        List<VendorKey> out = new ArrayList<>();
        if (c == null || Build.VERSION.SDK_INT < 28) return out;
        List<String> listed = new ArrayList<>();
        try {
            for (CaptureRequest.Key<?> k : c.getAvailableSessionKeys()) listed.add(k.getName());
        } catch (RuntimeException e) {
            return out;
        }
        return select(listed, MotionMode.on() && PreferenceKeys.scamDevSwitch("motion_hal_keys", true),
                PreferenceKeys.scamDevSwitch("ideal_raw", false));
    }

    /** The keys for these switches among the listed session key names. */
    static List<VendorKey> select(List<String> listed, boolean motion, boolean idealRaw) {
        List<VendorKey> out = new ArrayList<>();
        if (motion) {
            add(out, listed, "com.mediatek.3afeature.aishutterMode", Byte.class, (byte) 1);
            add(out, listed, "com.oplus.fastmotion.mode.enable", Byte.class, (byte) 1);
            add(out, listed, "vivo.control.is_motion_autofocus", Integer.class, 1);
        }
        if (idealRaw) add(out, listed, "org.codeaurora.qcamera3.sessionParameters.EnableIdealRAW", Byte.class, (byte) 1);
        return out;
    }

    private static void add(List<VendorKey> out, List<String> listed, String name, Class<?> type, Object value) {
        if (listed.contains(name)) out.add(new VendorKey(name, type, value));
    }

    /** Sets the vendor keys on a session-parameter or request builder (and the physical camera's, where it is one). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void apply(CaptureRequest.Builder builder, @Nullable CameraCharacteristics c) {
        if (builder == null) return;
        for (VendorKey k : sessionKeys(c)) {
            try {
                builder.set(new CaptureRequest.Key(k.name, k.type), k.value);
            } catch (RuntimeException e) {
                Log.w(TAG, k.name + " not set: " + e);
            }
        }
    }

    /** Whether this camera's session needs session parameters for these features. */
    public static boolean wantsSession(@Nullable CameraCharacteristics c) {
        List<VendorKey> keys = sessionKeys(c);
        if (!keys.isEmpty()) {
            StringBuilder s = new StringBuilder();
            for (VendorKey k : keys) s.append(k.name).append('=').append(k.value).append(' ');
            Log.i(TAG, "session keys: " + s.toString().trim());
        }
        return !keys.isEmpty();
    }

    /** The viewfinder request: zoom override and the low-light boost, where the camera lists them. */
    public static void applyPreview(CaptureRequest.Builder builder, @Nullable CameraCharacteristics c, boolean manualOrVideo) {
        if (builder == null || c == null) return;
        if (Build.VERSION.SDK_INT >= 34 && PreferenceKeys.scamDevSwitch("zoom_override", true)
                && contains(c.get(CameraCharacteristics.CONTROL_AVAILABLE_SETTINGS_OVERRIDES), SETTINGS_OVERRIDE_ZOOM)) {
            builder.set(CaptureRequest.CONTROL_SETTINGS_OVERRIDE, SETTINGS_OVERRIDE_ZOOM);
        }
        if (Build.VERSION.SDK_INT >= 35 && !manualOrVideo && !MotionMode.on() && lowLightBoostOn()
                && contains(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES), AE_LOW_LIGHT_BOOST)) {
            Integer ae = builder.get(CaptureRequest.CONTROL_AE_MODE);
            if (ae == null || ae == CaptureRequest.CONTROL_AE_MODE_ON) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, AE_LOW_LIGHT_BOOST);
                Log.i(TAG, "viewfinder: low-light boost AE mode");
            }
        }
    }

    static boolean lowLightBoostOn() {
        return PreferenceKeys.isLowLightBoost();
    }

    static boolean contains(@Nullable int[] values, int v) {
        if (values == null) return false;
        for (int x : values) if (x == v) return true;
        return false;
    }

}
