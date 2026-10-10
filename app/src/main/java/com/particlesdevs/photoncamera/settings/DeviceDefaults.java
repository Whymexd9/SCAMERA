package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import com.particlesdevs.photoncamera.util.Log;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Factory configuration of the OPPO Find X7 Ultra (PHY110), Find X8 Ultra (PKJ110), Redmi Note 11 Pro (viva) and Pixel 7 (panther). Applied once per defaults version
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
     * phone then lists 5 cameras instead of 3, owner's log) and the OPPO-matrix saturation of the ARK tone at 1.1. 4 (owner,
     * 2026-10-10): the Redmi Note 11 Pro (Helio G96, Mali-G57 MC2: 19 frames took 16.7 s of GPU merge) merges 12 N frames and
     * 2 Shasta frames, with the ARK RL 3 (micro-texture) deconvolution at 1.2; the Pixel 7 takes RL 3 at 1.5. 5 (owner,
     * 2026-10-10 evening, shots with the P77 build): the Redmi Note 11 Pro takes RL 3 at 1.5. A phone whose marker is older gets only the
     * entries of the newer versions, so an update never resets the user's other settings.
     */
    static final int VERSION = 5;
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
        final boolean note11Pro = redmiNote11Pro(), pixel7 = pixel7();
        if (!oppo && !note11Pro && !pixel7) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        // v4: Redmi Note 11 Pro (owner's log 2026-10-10: device viva, model 2201116TG)
        if (since < 4 && note11Pro) {
            out.put("pref_scam_hybrid_zsl_frames", "12");
            out.put("pref_scam_hybrid_shasta_frames", "2");
            out.put("pref_scam_hybrid_ark_sharp_rl3_amount", "1.2");
        }
        // v5: Redmi Note 11 Pro RL 3 at 1.5 (owner, after the P77 shots)
        if (since < 5 && note11Pro) out.put("pref_scam_hybrid_ark_sharp_rl3_amount", String.valueOf(NOTE11PRO_RL3));
        // v4: Pixel 7 (owner 2026-10-10, his shots of p7.zip were taken with it)
        if (since < 4 && pixel7) out.put("pref_scam_hybrid_ark_sharp_rl3_amount", String.valueOf(PIXEL7_RL3));
        if (!oppo) return out;
        // v2: RAW10 stream on the Find X8 Ultra (unpacked to 16 bit on copy; the RAW viewfinder needs RAW_SENSOR and is off).
        if (since < 2 && model.equals("PKJ110")) out.put("pref_raw_stream_format", "raw10");
        // v3: the Find X7 Ultra shows its tele / ultra-wide lenses to a whitelisted package only, and its tuned ISP matrix
        // under the ARK tone needs AgX saturation 1.1 (not 0.6) to match ArkCam SCAM 9.6 with the X7U config (2026-10-08 pair).
        if (since < 3 && model.equals("PHY110")) {
            out.put("pref_camera_package_spoof_enabled", true);
            out.put("pref_oplus_spoof_package_key", X7U_SPOOF_PACKAGE);
            out.put("pref_generic_spoof_package_key", X7U_SPOOF_PACKAGE);
            out.put("pref_binder_spoof_package_key", X7U_SPOOF_PACKAGE);
            out.put("pref_scam_hybrid_ark_ccm_sat", "1.1");
        }
        return out;
    }

    /** RL 3 amount of the Redmi Note 11 Pro default (v5). */
    static final float NOTE11PRO_RL3 = 1.5f;
    /**
     * P78 (owner, 2026-10-10): the Redmi Note 11 Pro (no OIS) takes the colour denoise 20 % stronger in low light (base ISO from
     * {@link #NOTE11PRO_NIGHT_ISO}: his night shots at ISO 7700); x1 elsewhere and on every other phone.
     */
    public static float chromaDenoiseFactor(int iso) {
        return iso >= NOTE11PRO_NIGHT_ISO && redmiNote11Pro() ? 1.2f : 1f;
    }
    static final int NOTE11PRO_NIGHT_ISO = 3200;
    /** Redmi Note 11 Pro 4G (Helio G96): device viva (owner's log, model 2201116TG). */
    public static boolean redmiNote11Pro() {
        return "Xiaomi".equalsIgnoreCase(Build.MANUFACTURER) && "viva".equalsIgnoreCase(Build.DEVICE);
    }

    /** RL 3 amount of the Pixel 7 default (v4). */
    static final float PIXEL7_RL3 = 1.5f, PIXEL7_RL3_2X = 1.2f;
    /**
     * P71 (owner, 2026-10-10): on the Pixel 7 the 2x module (camera 4, full-resolution crop of the main sensor) takes RL 3 at 1.2
     * while the setting holds the device default 1.5 (light outlines on thin branches at 1.5); any other stored value is the
     * user's and stays. Module settings may be shared by every lens, so this is decided per shot, not stored.
     */
    public static float rl3Amount(float stored, String cameraId) {
        if (Math.abs(stored - PIXEL7_RL3) < 1e-4f && "4".equals(cameraId) && pixel7()) return PIXEL7_RL3_2X;
        return stored;
    }
    /** Pixel 7: Google, device panther (owner's log 2026-10-10). */
    public static boolean pixel7() {
        return "Google".equalsIgnoreCase(Build.MANUFACTURER) && "panther".equalsIgnoreCase(Build.DEVICE);
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
            Log.i(TAG, Build.MANUFACTURER + " " + Build.MODEL + ": device configuration v" + stored + " -> v" + VERSION + " applied (" + defaults.size() + " settings)");
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
