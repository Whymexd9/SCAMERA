package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import com.particlesdevs.photoncamera.R;
import org.xmlpull.v1.XmlPullParser;
import java.util.Map;

/** Only storage representation changes. Keys and valid user values survive menu moves/imports. */
public final class SettingsMigration {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private SettingsMigration() {}
    public static void prepare(Context context, SharedPreferences preferences) {
        Map<String, ?> values = preferences.getAll();
        SharedPreferences.Editor editor = preferences.edit();
        Object old = values.get("pref_tunable_esd4d_enableadaptivenoise");
        boolean migrateDisabledNoise = !values.containsKey("settings_audit_schema") && old != null && !PreferenceNumber.bool(old,true);
        if (!values.containsKey("settings_audit_schema")) {
            if (!values.containsKey("pref_tunable_esd4d_usencnnflow")
                    && PreferenceNumber.read(values.get("pref_processing_backend_key"),0)!=0)
                editor.putInt("pref_tunable_esd4d_usencnnflow",1); // preserve the old implicit default
            editor.putInt("settings_audit_schema",1);
        }
        try (XmlResourceParser parser = context.getResources().getXml(R.xml.preferences)) {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() != XmlPullParser.START_TAG) continue;
                String key = attribute(context,parser,"key");
                if (key == null || !values.containsKey(key)) continue;
                String type = parser.getName(); Object value = values.get(key);
                if (type.contains("Switch") || type.contains("CheckBox")) {
                    if (!(value instanceof Boolean)) editor.putBoolean(key,PreferenceNumber.bool(value,
                            Boolean.parseBoolean(attribute(context,parser,"defaultValue"))));
                } else if (type.contains("ListPreference") && !type.contains("MultiSelect")
                        || type.contains("EditText") || type.contains("UniversalSeekBar")) {
                    String text = String.valueOf(value);
                    if ("pref_remosaic_block_key".equals(key)) text = PreferenceNumber.read(value,4)==2 ? "2" : "4";
                    int options = parser.getAttributeResourceValue(ANDROID,"entryValues",0);
                    if (options != 0) {
                        String[] entries = context.getResources().getStringArray(options);
                        for (String entry : entries) {
                            if (entry.equals(text)) break;
                            double candidate = PreferenceNumber.read(entry,Double.NaN);
                            if (Double.isFinite(candidate) && candidate == PreferenceNumber.read(text,Double.NaN)) { text=entry; break; }
                        }
                    }
                    if (!(value instanceof String) || !text.equals(value)) editor.putString(key,text);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot normalize settings schema",e);
        }
        // Apply semantic migration after type normalization, which uses the original snapshot.
        if (migrateDisabledNoise) editor.putBoolean("pref_noise_dynamic_enabled_key",false);
        editor.apply();
    }
    /** Upgrade obsolete MFSR controls once for each restored module snapshot. */
    public static void migrateMultiFrame(SharedPreferences preferences) {
        Map<String, ?> values=preferences.getAll();
        SharedPreferences.Editor e=preferences.edit();
        if(!values.containsKey("settings_zsl_capacity_v2")) {
            e.putString("pref_zsl_buffer_count_key","50");
            e.putBoolean("settings_zsl_capacity_v2",true);
        }
        if(!values.containsKey("pref_mfsr_source_key")) {
            boolean clustered=PreferenceNumber.bool(values.get("pref_remosaic_enabled_key"),false);
            int block=clustered ? (PreferenceNumber.read(values.get("pref_remosaic_block_key"),4)==2 ? 2 : 4) : 1;
            e.putString("pref_mfsr_source_key",String.valueOf(block));
        }
        for(String old:new String[]{"k_detail","k_denoise","k_stretch","k_shrink","dth","dtr","tensor_stride","grad_k"})
            e.remove("pref_mfsr_"+old+"_key");
        // Calibration is a one-shot action, never a saved or copied camera profile.
        e.remove("pref_mfsr_calibrate_key");e.commit();
    }

    private static final String LEGACY_HYBRID = LmcHybridKeys.LEGACY_PREFIX;
    /** SCAM HDR knobs (pref_vivo_nice_&lt;k&gt;) the hybrid route read until it got its own settings. */
    private static final java.util.Set<String> SHARED_NICE_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "zsl_frames", "gcam_tone", "highlight_recovery", "isp_ccm", "noise_photon", "noise_readout",
            "noise_source", "cre_source", "diagnostics"));
    private static final String[] SHARED_NICE_PREFIXES = {"fusion_", "tone_", "look", "warm_", "sharp_", "texture", "grain_"};
    /** Autonomous HDR keys that are not on the hybrid route (vivo HDR denoise, capture sharpening, the reset action). */
    private static final java.util.Set<String> UNSHARED_HDR_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "enabled", "luma", "chroma", "sharpen", "reset_tone"));

    /**
     * The LMC hybrid left SCAM HDR (section «LMC-гибрид», 3 October 2026). Applied to the main preferences, the module
     * baseline and every stored module profile; restored snapshots go through {@link #migrateLmcHybrid(SharedPreferences, boolean)}.
     */
    public static void migrateLmcHybrid(Context context, SharedPreferences main) {
        // Fresh install: androidx has never written the XML defaults and nothing set the autonomous HDR switch.
        boolean fresh = !context.getSharedPreferences("_has_set_default_values", Context.MODE_PRIVATE)
                .getBoolean("_has_set_default_values", false) && !main.contains("pref_vivo_hdr_enabled");
        migrateLmcHybrid(main, fresh);
        SharedPreferences meta = context.getSharedPreferences("module_profiles_meta", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : meta.getAll().entrySet())
            if (e.getKey().startsWith("exists_") && Boolean.TRUE.equals(e.getValue()))
                migrateLmcHybrid(context.getSharedPreferences("module_profile_v2_" + e.getKey().substring(7), Context.MODE_PRIVATE), false);
        if (meta.getBoolean("baseline", false))
            migrateLmcHybrid(context.getSharedPreferences("module_profile_v2_common", Context.MODE_PRIVATE), false);
    }

    /**
     * One preference set: pref_vivo_nice_hybrid_&lt;k&gt; moves to pref_lmc_hybrid_&lt;k&gt; (except the soft tone, gone for
     * the hybrid, and the Bento factor, which starts again from the LMC default 8). Where the hybrid took the shots so far
     * (SCAM HDR on and pref_vivo_nice_engine resolving to the hybrid), the SCAM HDR knobs it read (Exposure Fusion, tone,
     * look, texture, sharpening, AE, AgX highlights, autonomous HDR tone, noise and CRE source, N frames, diagnostics,
     * fast capture) are copied to the hybrid's keys (LmcHybridKeys.copyKey), so the hybrid keeps its look while SCAM HDR
     * keeps its own values; elsewhere (e.g. SCAM HDR NICE on SM8750) the hybrid starts from its own defaults (N 20, ...).
     * The copy happens once, in the run that creates pref_lmc_hybrid_enabled (= the hybrid took the shots so far; on a
     * fresh install: on wherever the NICE network does not run), so later SCAM HDR changes never reach the hybrid.
     * pref_vivo_nice_engine and the first switch pref_vivo_nice_hybrid are removed. Keys that already exist under the new
     * names are never overwritten, so a second run changes nothing.
     *
     * @return whether anything changed
     */
    public static boolean migrateLmcHybrid(SharedPreferences prefs, boolean freshInstall) {
        Map<String, ?> values = prefs.getAll();
        SharedPreferences.Editor e = prefs.edit();
        java.util.Set<String> written = new java.util.HashSet<>();
        boolean changed = false;
        final boolean firstRun = !values.containsKey(LmcHybridKeys.ENABLED);
        final boolean hybridShots = firstRun && !freshInstall && legacyHybridShots(values);
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(LEGACY_HYBRID)) continue;
            String k = key.substring(LEGACY_HYBRID.length());
            String target = LmcHybridKeys.PREFIX + k;
            if (!k.equals("soft_tone") && !k.equals("bento_factor") && entry.getValue() != null && !values.containsKey(target)) {
                ModuleProfiles.put(e, target, entry.getValue());
                written.add(target);
            }
            e.remove(key);
            changed = true;
        }
        if (hybridShots) for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            if (entry.getValue() == null || !sharedWithHybrid(key)) continue;
            // The hybrid's noise profile list is auto | settings; the NICE-only sources mean auto for it.
            if (key.equals("pref_vivo_nice_noise_source") && !"settings".equals(entry.getValue().toString())) continue;
            String target = LmcHybridKeys.copyKey(key);
            if (target.equals(key) || values.containsKey(target) || !written.add(target)) continue;
            ModuleProfiles.put(e, target, entry.getValue());
            changed = true;
        }
        if (firstRun) {
            e.putBoolean(LmcHybridKeys.ENABLED, freshInstall ? LmcHybridKeys.defaultOn() : hybridShots);
            changed = true;
        }
        for (String old : new String[]{"pref_vivo_nice_engine", "pref_vivo_nice_hybrid"})
            if (values.containsKey(old)) { e.remove(old); changed = true; }
        // Defaults revision 2 (4 October 2026): the Sabre detail of the ARK tone moved from 1 to 2 after the A/B against
        // ArkCam on the Oppo; a stored 1 is the old XML default written by the first round-5 build, not a user choice.
        Object rev = values.get(DEFAULTS_REV);
        int revision = rev instanceof Integer ? (Integer) rev : 0;
        Object detail = values.get(LmcHybridKeys.PREFIX + "ark_detail_gain");
        if (detail != null && revision < 2) {
            if (isNumber(detail, 1f)) ModuleProfiles.put(e, LmcHybridKeys.PREFIX + "ark_detail_gain", detail instanceof String ? "2" : (Object) 2f);
            e.putInt(DEFAULTS_REV, 2);
            changed = true;
        }
        // Defaults revision 3 (4 October 2026): the hybrid sharpening is ArkCam's own (sharp_mode "ark", ArkLumaSharpen) and
        // the noise reduction ArkCam's exact tables (no revert cap, no stock coarse luma, no chroma floor); stored values
        // equal to the former XML defaults ("rt", 2, 0.5, 2.75) are those defaults, not user choices.
        if (revision < 3) {
            boolean touched = false;
            Object sharpMode = values.get(LmcHybridKeys.PREFIX + "sharp_mode");
            if (sharpMode != null) {
                if ("rt".equals(sharpMode.toString().trim())) ModuleProfiles.put(e, LmcHybridKeys.PREFIX + "sharp_mode", "ark");
                touched = true;
            }
            String[] keys = {"dn_revert_max", "dn_coarse_stock", "dn_chroma_floor"};
            float[] former = {2f, 0.5f, 2.75f}, ark = {9f, 0f, 0f};
            for (int i = 0; i < keys.length; i++) {
                Object v = values.get(LmcHybridKeys.PREFIX + keys[i]);
                if (v == null) continue;
                if (isNumber(v, former[i])) ModuleProfiles.put(e, LmcHybridKeys.PREFIX + keys[i], v instanceof String ? PreferenceNumber.format(ark[i], true) : (Object) ark[i]);
                touched = true;
            }
            if (touched) {
                e.putInt(DEFAULTS_REV, 3);
                changed = true;
            }
        }
        if (changed) e.commit();
        return changed;
    }

    private static final String DEFAULTS_REV = "pref_lmc_hybrid_defaults_rev";

    private static boolean isNumber(Object v, float expected) {
        try {
            float f = v instanceof Number ? ((Number) v).floatValue() : Float.parseFloat(v.toString().trim());
            return Math.abs(f - expected) < 1e-4f;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** A SCAM HDR key whose value the hybrid route used before the separation (see {@link #migrateLmcHybrid(SharedPreferences, boolean)}). */
    static boolean sharedWithHybrid(String key) {
        if (key.startsWith(LEGACY_HYBRID) || key.equals("pref_vivo_nice_hybrid")) return false;
        if (key.startsWith("pref_vivo_nice_")) {
            String k = key.substring("pref_vivo_nice_".length());
            // sharp_mode is a number for SCAM HDR and a list (rt/scam/off) for the hybrid; the soft tone is gone for it.
            if (k.equals("sharp_mode") || k.equals("soft_tone")) return false;
            if (SHARED_NICE_KEYS.contains(k)) return true;
            for (String prefix : SHARED_NICE_PREFIXES) if (k.startsWith(prefix)) return true;
            return false;
        }
        if (key.startsWith("pref_nice_")) {
            String k = key.substring("pref_nice_".length());
            return k.startsWith("ae_") || k.equals("fast_capture");
        }
        if (key.startsWith("pref_agx_nice_")) return true;
        if (key.startsWith("pref_vivo_hdr_")) return !UNSHARED_HDR_KEYS.contains(key.substring("pref_vivo_hdr_".length()));
        return false;
    }

    /** The hybrid merged the shots before the separation: SCAM HDR on and the engine "hybrid", or "auto" away from the NICE SoC. */
    private static boolean legacyHybridShots(Map<String, ?> values) {
        if (!PreferenceNumber.bool(values.get("pref_vivo_hdr_enabled"), false)
                || !PreferenceNumber.bool(values.get("pref_vivo_nice_enabled"), false)) return false;
        Object engine = values.get("pref_vivo_nice_engine");
        if (engine == null && values.containsKey("pref_vivo_nice_hybrid"))
            return PreferenceNumber.bool(values.get("pref_vivo_nice_hybrid"), true);
        String v = engine == null ? "auto" : engine.toString();
        return "hybrid".equals(v) || !"nice".equals(v) && !LmcHybridKeys.vivoNetSoc();
    }
    private static String attribute(Context context, XmlResourceParser parser, String name) {
        int id=parser.getAttributeResourceValue(ANDROID,name,0);
        if(id==0) return parser.getAttributeValue(ANDROID,name);
        android.util.TypedValue value=new android.util.TypedValue();
        context.getResources().getValue(id,value,true);
        CharSequence text=value.coerceToString();return text==null ? null : text.toString();
    }
}
