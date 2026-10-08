package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import com.particlesdevs.photoncamera.util.Log;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Factory configuration of the OPPO Find X7 Ultra (PHY110) and Find X8 Ultra (PKJ110). Applied once per defaults version
 * (marker {@code device_defaults_version}, not a per-module key) when the app first runs after install or update: on the
 * main preferences, and the per-module entries also on the shared baseline and every module profile that already exists.
 * The user changes anything afterwards; a newer defaults version applies only its own entries.
 *
 * <p>Version 1 was a SCAM HDR tuning set for both phones; SCAM HDR runs only on the Snapdragon 8 Elite, so nothing read it
 * and it is gone (owner, 8 October 2026). Versions 2 and 3 stay.
 */
public final class DeviceDefaults {
    private static final String TAG = "DeviceDefaults";
    private static final String MARKER = "device_defaults_version";
    /**
     * 1: the SCAM HDR set of both OPPO phones (removed 2026-10-08: SCAM HDR never runs there). 2 (owner, 2026-10-06): the
     * Find X8 Ultra streams RAW10 by default. 3 (owner, 2026-10-08): the Find X7 Ultra opens with the camera package spoof of com.ss.android.ugc.aweme (all three methods: the
     * phone then lists 5 cameras instead of 3, owner's log) and the OPPO-matrix saturation of the ARK tone at 1.1. A phone
     * whose marker is older gets only the entries of the newer versions, so an update never resets the user's other settings.
     */
    static final int VERSION = 3;
    /** The package the Find X7 Ultra's camera service shows every lens to (owner's choice). */
    static final String X7U_SPOOF_PACKAGE = "com.ss.android.ugc.aweme";
    private DeviceDefaults() {}

    /** The defaults that apply to this device, or null. */
    static Map<String, Object> forDevice(Context context) {
        return forDevice(context, 0);
    }

    /** The defaults of this device introduced after defaults version {@code since}; null when the device has none. */
    static Map<String, Object> forDevice(Context context, int since) {
        String model = Build.MODEL == null ? "" : Build.MODEL.toUpperCase(java.util.Locale.ROOT);
        final boolean oppo = "OPPO".equalsIgnoreCase(Build.MANUFACTURER) && (model.equals("PHY110") || model.equals("PKJ110"));
        if (!oppo) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        // v2: RAW10 stream on the Find X8 Ultra (unpacked to 16 bit on copy; the RAW viewfinder needs RAW_SENSOR and is off).
        if (since < 2 && model.equals("PKJ110")) out.put("pref_raw_stream_format", "raw10");
        // v3: the Find X7 Ultra shows its tele / ultra-wide lenses to a whitelisted package only, and its tuned ISP matrix
        // under the ARK tone needs AgX saturation 1.1 (not 0.6) to match ArkCam LMC 9.6 with the X7U config (2026-10-08 pair).
        if (since < 3 && model.equals("PHY110")) {
            out.put("pref_camera_package_spoof_enabled", true);
            out.put("pref_oplus_spoof_package_key", X7U_SPOOF_PACKAGE);
            out.put("pref_generic_spoof_package_key", X7U_SPOOF_PACKAGE);
            out.put("pref_binder_spoof_package_key", X7U_SPOOF_PACKAGE);
            out.put("pref_lmc_hybrid_ark_ccm_sat", "1.1");
        }
        return out;
    }

    public static void applyOnce(Context context, SharedPreferences main) {
        final int stored = main.getInt(MARKER, 0);
        if (stored >= VERSION) return;
        Map<String, Object> defaults = forDevice(context, stored);
        if (defaults == null) return;
        try {
            write(main, defaults);
            // Module profiles that already exist (and the baseline new ones start from) hold their own copy of the per-module
            // entries; the shared ones (the spoof, ModuleProfiles.isGlobal) live in the main preferences only.
            Map<String, Object> perModule = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : defaults.entrySet()) if (!ModuleProfiles.isGlobal(e.getKey())) perModule.put(e.getKey(), e.getValue());
            SharedPreferences meta = context.getSharedPreferences("module_profiles_meta", Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : meta.getAll().entrySet()) {
                if (e.getKey().startsWith("exists_") && Boolean.TRUE.equals(e.getValue()))
                    write(context.getSharedPreferences("module_profile_v2_" + e.getKey().substring(7), Context.MODE_PRIVATE), perModule);
            }
            if (meta.getBoolean("baseline", false))
                write(context.getSharedPreferences("module_profile_v2_common", Context.MODE_PRIVATE), perModule);
            main.edit().putInt(MARKER, VERSION).apply();
            Log.i(TAG, "OPPO " + Build.MODEL + ": device configuration v" + stored + " -> v" + VERSION + " applied (" + defaults.size() + " settings)");
        } catch (RuntimeException failure) {
            Log.e(TAG, "device defaults not applied: " + failure);
        }
    }

    private static void write(SharedPreferences target, Map<String, Object> values) {
        SharedPreferences.Editor editor = target.edit();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) editor.putBoolean(e.getKey(), (Boolean) v);
            else editor.putString(e.getKey(), String.valueOf(v));
        }
        editor.apply();
    }
}
