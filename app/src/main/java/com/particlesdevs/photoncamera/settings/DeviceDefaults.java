package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import com.particlesdevs.photoncamera.util.Log;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Factory configuration of the devices SCAM HDR is tuned for. Applied once per defaults version (marker
 * {@code device_defaults_version}, not a per-module key) when the app first runs after install or update: on the main
 * preferences, the shared baseline and every module profile that already exists. These are SCAM HDR's tuning values; the
 * route itself is not chosen here (the LMC hybrid is the default on every phone, SCAM HDR is the user's choice). The user
 * changes anything afterwards; a newer defaults version applies again.
 *
 * <p>OPPO Find X7 Ultra (PHY110) and Find X8 Ultra (PKJ110), for when SCAM HDR is selected: the bracket planned by SCAMERA (the stock vivo AE needs a vivo root observer), 20 N frames from the ZSL ring and
 * the merge / denoise set tuned on the vivo main camera (Sabre-style SNR-adaptive merge, Luma/Chroma of the network
 * at 0 so the merge does the denoising).
 */
public final class DeviceDefaults {
    private static final String TAG = "DeviceDefaults";
    private static final String MARKER = "device_defaults_version";
    static final int VERSION = 1;
    private DeviceDefaults() {}

    private static final Map<String, Object> OPPO = new LinkedHashMap<>();
    private static void put(String key, Object value) { OPPO.put(key, value); }
    static {
        put("pref_vivo_nice_planner", "scamera");
        put("pref_vivo_nice_zsl_frames", "20");
        put("pref_vivo_nice_diagnostics", false);
        put("pref_vivo_nice_chroma_iso1", "0.00");
        put("pref_vivo_nice_chroma_iso2", "0.00");
        put("pref_vivo_nice_chroma_iso3", "0.00");
        put("pref_vivo_nice_chroma_iso4", "0.00");
        put("pref_vivo_nice_chroma_iso5", "0.00");
        put("pref_vivo_nice_noise_scale", "0.25");
        put("pref_vivo_nice_noise_photon", "1.00");
        put("pref_vivo_nice_merge", "100");
        put("pref_vivo_nice_chroma_radius", "1.00");
        put("pref_vivo_nice_planner_s_ev", "3");
        put("pref_vivo_nice_post_despeckle", true);
        put("pref_vivo_nice_noise_readout", "1.00");
        put("pref_vivo_nice_norm", "1.1");
        put("pref_vivo_nice_noise_source", "auto");
        put("pref_vivo_nice_planner_l_ev", "2");
        put("pref_nice_zsl_long", true);
        put("pref_vivo_nice_planner_es_ev", "6");
        put("pref_vivo_nice_post_chroma", "0.40");
        put("pref_vivo_nice_luma", "0.00");
        put("pref_vivo_nice_chroma", "0.00");
        put("pref_vivo_nice_post_luma", "0.10");
        put("pref_vivo_nice_long_boost_ev", "1.1");
        put("pref_vivo_nice_luma_radius", "1.00");
        put("pref_vivo_nice_luma_iso1", "0.00");
        put("pref_vivo_nice_luma_iso3", "0.00");
        put("pref_vivo_nice_luma_iso2", "0.00");
        put("pref_vivo_nice_luma_iso5", "0.00");
        put("pref_vivo_nice_luma_iso4", "0.00");
        put("pref_vivo_nice_cre_source", "auto");
        put("pref_vivo_nice_grain_level", "0.0002");
        put("pref_vivo_nice_planner_adaptive", true);
        put("pref_nice_fast_capture", true);
    }

    /** The defaults that apply to this device, or null. A file "force-oppo-defaults" in the app's external files dir tests them on any device. */
    static Map<String, Object> forDevice(Context context) {
        try {
            java.io.File external = context.getExternalFilesDir(null);
            if (external != null && new java.io.File(external, "force-oppo-defaults").exists()) return OPPO;
        } catch (RuntimeException ignored) {}
        if (!"OPPO".equalsIgnoreCase(Build.MANUFACTURER)) return null;
        String model = Build.MODEL == null ? "" : Build.MODEL.toUpperCase(java.util.Locale.ROOT);
        return model.equals("PHY110") || model.equals("PKJ110") ? OPPO : null;
    }

    public static void applyOnce(Context context, SharedPreferences main) {
        Map<String, Object> defaults = forDevice(context);
        if (defaults == null || main.getInt(MARKER, 0) >= VERSION) return;
        try {
            write(main, defaults);
            // Module profiles that already exist (and the baseline new ones start from) hold their own copy.
            SharedPreferences meta = context.getSharedPreferences("module_profiles_meta", Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> e : meta.getAll().entrySet()) {
                if (e.getKey().startsWith("exists_") && Boolean.TRUE.equals(e.getValue()))
                    write(context.getSharedPreferences("module_profile_v2_" + e.getKey().substring(7), Context.MODE_PRIVATE), defaults);
            }
            if (meta.getBoolean("baseline", false))
                write(context.getSharedPreferences("module_profile_v2_common", Context.MODE_PRIVATE), defaults);
            main.edit().putInt(MARKER, VERSION).apply();
            Log.i(TAG, "OPPO " + Build.MODEL + ": SCAM HDR configuration v" + VERSION + " applied (" + defaults.size() + " settings)");
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
