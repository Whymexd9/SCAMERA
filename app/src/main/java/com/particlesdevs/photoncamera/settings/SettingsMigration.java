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
        if (!values.containsKey("settings_audit_schema")) editor.putInt("settings_audit_schema",1);
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
        editor.apply();
    }
    /**
     * Parameters the camera screen no longer offers, left over from PhotonCamera: the HDRX switch (read by nothing), EIS
     * (only while recording video), exposure bracketing (only the old PhotonCamera planner; the hybrid and SCAM HDR plan
     * their own frames), Quad Bayer (switches the RAW stream to the full sensor mode, which the ZSL burst of the hybrid
     * must not) and a fixed preview FPS (caps the exposure of the ZSL frames). They go back to their defaults, so a value
     * chosen in an older build cannot stay on unseen. Capture still reads EIS, FPS and Quad, so these resets stay even
     * though the settings bar that showed them is gone (P25: moved here from SettingsBarEntryProvider).
     */
    public static void resetRemovedSettings(android.content.res.Resources res) {
        boolean hdrx = res.getBoolean(R.bool.pref_hdrx_mode_default);
        boolean eis = res.getBoolean(R.bool.pref_eis_photo_default);
        boolean quad = res.getBoolean(R.bool.pref_quad_bayer_default);
        if (PreferenceKeys.isHdrXOn() != hdrx) PreferenceKeys.setHdrX(hdrx);
        if (PreferenceKeys.isEisPhotoOn() != eis) PreferenceKeys.setEisPhoto(eis);
        if (PreferenceKeys.isQuadBayerOn() != quad) PreferenceKeys.setQuadBayer(quad);
        if (PreferenceKeys.getFpsMode() != 0) PreferenceKeys.setFpsMode(0);
        if (PreferenceKeys.getBracketingMode() != 0) PreferenceKeys.setBracketingMode(0);
    }

    /** ZSL ring capacity 50 once for each restored module snapshot (the RAW MFSR keys go with removeObsolete). */
    public static void migrateMultiFrame(SharedPreferences preferences) {
        Map<String, ?> values=preferences.getAll();
        if(values.containsKey("settings_zsl_capacity_v2")) return;
        preferences.edit().putString("pref_zsl_buffer_count_key","50").putBoolean("settings_zsl_capacity_v2",true).commit();
    }

    /**
     * Settings of removed features (settings cleanup, October 2026). Their stored values are dropped from the main
     * preferences, every module profile and the baseline, and from the shade's tiles, so that a getter or an old config
     * can never bring them back invisibly. Keys listed here have no getter and no XML row any more.
     */
    static final java.util.Set<String> OBSOLETE_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            // P1: vivo upscale (RAISR, SoftPQE, VSR) and the Lanczos after it
            "pref_vivo_upscale_backend_key", "pref_vivo_downscale_kernel_key", "pref_vivo_downscale_size_key",
            // P2: "Кадрирование DNG" (the 16:9 crop is always centred now)
            "pref_tunable_imagesaversettings_croptype",
            // P3: the route switches, replaced by pref_merge_route (migrateLmcHybrid converts them first)
            "pref_lmc_hybrid_enabled", "pref_vivo_hdr_enabled", "pref_vivo_nice_enabled",
            // P4: the legacy capture and merge (frame counts / brackets, HDR+ / ESD4D merge, RAW MFSR, mosaic SR, the
            // standalone remosaic and neural bursts, AI Bayer denoise, software binning)
            "pref_frame_count_key", "pref_short_frame_count_key", "pref_short_exposure_ev_key",
            "pref_long_frame_count_key", "pref_long_exposure_ev_key", "pref_highlight_suppression_key",
            "pref_zsl_quality_selection_key", "pref_max_hdr_ratio_key", "pref_tet_model_enabled_key",
            "pref_long_frame_shutter_cap_key", "pref_zsl_merge_algorithm_key", "pref_night_merge_algorithm_key",
            "pref_processing_backend_key", "pref_merge_robustness_key", "pref_merge_clip_level_key",
            "pref_merge_max_exposure_ratio_key", "pref_merge_floor_sigmas_key", "pref_merge_tiling_tolerance_key",
            "pref_merge_seekbar_key", "pref_highlight_recovery_key", "pref_highlight_recovery_min_ok_key",
            "pref_highlight_protection_key", "pref_highlight_protection_knee_key",
            "pref_highlight_protection_strength_key", "pref_binning_key", "pref_energy_safe_key",
            "pref_raw_mfsr_enabled_key", "pref_remosaic_enabled_key", "pref_remosaic_backend_key",
            "pref_hexquad_frames", "pref_quad_frames", "hexquad_exposure_ev", "hexquad_full_resolution",
            "hexquad_post_denoise", "quad2x2_exposure_ev", "quad2x2_post_denoise", "pref_vivo_hdr_luma",
            "pref_vivo_hdr_chroma", "pref_vivo_hdr_sharpen", "pref_ai_denoise_enabled_key",
            "pref_ai_denoise_strength_key", "pref_ai_denoise_luma_key", "pref_ai_denoise_chroma_key",
            "pref_ai_denoise_model_key", "pref_remosaic_dump_key", "pref_noise_dynamic_enabled_key",
            // P5: the legacy post-processing (RT / ESD3D denoise, GCam finish, tone pipelines, ACES, Capture One,
            // darktable, false colour / CA, PhotonCamera tone sliders, noise-model ISO overrides)
            "pref_rt_denoise_backend", "pref_capture_one_enabled_key", "pref_false_color_enabled_key",
            "pref_saliency_protection_key", "pref_false_color_strength_key", "pref_defringe_purple_key",
            "pref_defringe_green_key", "pref_ca_red_key", "pref_ca_blue_key", "pref_saturation_seekbar_key",
            "pref_contrast_seekbar_key", "pref_shadows_seekbar_key", "pref_compressor_seekbar_key",
            "pref_sensor_sharpening_enabled", "pref_noise_disable_digital_gain_key", "pref_noise_model_coefficient_key",
            "pref_noise_iso_curve_key", "pref_noise_iso_min_key", "pref_noise_iso_max_key", "pref_noise_iso_manual_key",
            "pref_noise_seekbar_key", "pref_agx_local_highlights", "pref_agx_local_start",
            // P10: one tone (ARK) for both routes; the SCAMERA tone of SCAM HDR and the hybrid's «Тон SCAMERA» go
            "pref_expocompensation_seekbar_key", "pref_nice_ae_mid", "pref_nice_ae_high", "pref_nice_ae_gain_max",
            "pref_vivo_nice_soft_tone", "pref_vivo_nice_tone_key", "pref_vivo_nice_sharp_amount", "pref_vivo_nice_texture",
            "pref_vivo_nice_warm_retention", "pref_vivo_nice_gcam_tone", "pref_lmc_hybrid_ae_mid", "pref_lmc_hybrid_ae_high",
            "pref_lmc_hybrid_ae_gain_max", "pref_lmc_hybrid_bento_fusion", "pref_lmc_hybrid_texture", "pref_lmc_hybrid_ark_tone",
            "pref_lmc_hybrid_soft_tone", "pref_lmc_hybrid_tone_key", "pref_lmc_hybrid_warm_retention", "pref_lmc_hybrid_gcam_tone",
            // P8: the retired VCF2 route selector and the unreachable RAW video mode
            "pref_vivo_nice_route", "pref_rawvideo_downscale_4x_key", "pref_rawvideo_write_zip_key", "pref_rawvideo_crop_169_key",
            // P12b: the old texture boost switch never reached the worker; pref_lmc_hybrid_motion_boost replaces it
            "pref_lmc_hybrid_boost",
            // P25: the Quad toggle of the top bar is gone, and with it its tunable «Enable Quad Resolution»; the quick
            // buttons of concept E and the ☆ favourites became the shade's tiles (migrateShadeTiles reads them first)
            "pref_tunable_camerauiviewimpl_enablequadres", "ui_sheet_quick", "settings_favorite_keys",
            // Settings audit (8 October 2026): «Формат превью» only added an ImageReader nobody read to the session
            "pref_preview_format_key"));
    static final String[] OBSOLETE_PREFIXES = {"pref_raisr_", "pref_softpqe_",
            "pref_snr_", "pref_mfsr_", "scamera_mosaic_sr_", "pref_hdrplus_", "pref_tunable_esd4d_", "pref_tunable_pyramidalignment_",
            // P5: the legacy post-processing and the tunables of its nodes
            "rt512_", "pref_rt_nr_", "pref_gcam_", "pref_aces_", "pref_c1_", "scamera_darktable_",
            "pref_tunable_postpipeline_", "pref_tunable_esd3d2_", "pref_tunable_ablc_", "pref_tunable_initial_",
            "pref_tunable_autoexposurecurve_", "pref_tunable_opendrt_", "pref_tunable_locallaplacian_",
            "pref_tunable_linearexposure_", "pref_tunable_headroomrender_", "pref_tunable_bayer2float_",
            "pref_tunable_amaze_",
            // P10: AgX, Exposure Fusion and the headroom tone of both routes (and the hybrid's copies)
            "pref_agx_", "pref_vivo_hdr_", "pref_vivo_nice_fusion_", "pref_lmc_hybrid_hdr_", "pref_lmc_hybrid_fusion_",
            "pref_lmc_hybrid_agx_"};
    /**
     * Removed rows of the per-module sensor settings (pref_sensorconfig_&lt;slot or camera id&gt;_&lt;field&gt;): the exposure limits
     * «Максимальное ISO», «Максимальная выдержка» and «Баланс выдержки и ISO», which no capture code read (settings audit).
     */
    static final String[] OBSOLETE_SENSOR_FIELDS = {"exposurebalanceisolimit", "exposurebalanceshutterlimit", "exposurebalancemultiplier"};
    static boolean isObsolete(String key) {
        if (OBSOLETE_KEYS.contains(key)) return true;
        for (String prefix : OBSOLETE_PREFIXES) if (key.startsWith(prefix)) return true;
        if (key.startsWith("pref_sensorconfig_"))
            for (String field : OBSOLETE_SENSOR_FIELDS) if (key.endsWith("_" + field)) return true;
        return false;
    }

    /**
     * Removes the obsolete keys from one preference set and from the shade tiles, and resets stored values of removed list
     * entries; returns whether anything changed. Idempotent: a second run changes nothing.
     */
    public static boolean removeObsolete(SharedPreferences prefs) {
        SharedPreferences.Editor e = prefs.edit();
        boolean changed = false;
        for (String key : prefs.getAll().keySet())
            if (isObsolete(key)) { e.remove(key); changed = true; }
        // The shade's tiles (P25): a removed setting leaves the list too.
        String tiles = prefs.getString(ShadeTiles.KEY, null);
        if (tiles != null) {
            java.util.List<String> in = ShadeTiles.split(tiles), out = new java.util.ArrayList<>();
            for (String key : in) if (!isObsolete(key)) out.add(key);
            if (out.size() != in.size()) { e.putString(ShadeTiles.KEY, ShadeTiles.join(out)); changed = true; }
        }
        // Quad Bayer switches the RAW stream to the full sensor mode, which the hybrid's ZSL burst must not: a profile of an
        // older build (main, a module, a restored config) cannot bring it back on. Before P25 the camera screen forced it off
        // on every resume / lens switch; resetRemovedSettings only covers the screen's start.
        Object quad = prefs.getAll().get("pref_quad_bayer_key");
        if (quad != null && PreferenceNumber.bool(quad, false)) { e.putBoolean("pref_quad_bayer_key", false); changed = true; }
        // «Фильтр Байера» lost MONO (4: every Hybrid / SCAM HDR shot failed, both need a 2x2 CFA) and QUAD (-2: did nothing,
        // the Quad stream is set in «Quad Bayer — совместимость»); a stored one of them becomes «Авто» (-1).
        Object cfa = prefs.getAll().get(CFA_KEY);
        if (cfa != null && isRemovedCfa(cfa)) { e.putString(CFA_KEY, "-1"); changed = true; }
        if (changed) e.commit();
        return changed;
    }

    static final String CFA_KEY = "pref_cfa_key";
    /** The removed «Фильтр Байера» values: MONO (4) and QUAD (-2). */
    static boolean isRemovedCfa(Object value) {
        double v = PreferenceNumber.read(value, -1);
        return v == 4 || v == -2;
    }

    /** {@link #removeObsolete(SharedPreferences)} over the main preferences, every stored module profile and the baseline. */
    public static void removeObsolete(Context context, SharedPreferences main) {
        removeObsolete(main);
        SharedPreferences meta = context.getSharedPreferences("module_profiles_meta", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : meta.getAll().entrySet())
            if (e.getKey().startsWith("exists_") && Boolean.TRUE.equals(e.getValue()))
                removeObsolete(context.getSharedPreferences("module_profile_v2_" + e.getKey().substring(7), Context.MODE_PRIVATE));
        removeObsolete(context.getSharedPreferences("module_profile_v2_common", Context.MODE_PRIVATE));
    }

    /** Quick buttons of the concept E sheet: SettingType names, comma separated, oldest first. */
    static final String LEGACY_QUICK = "ui_sheet_quick";
    /** The ☆ favourites of the viewfinder: a JSON array of preference keys. */
    static final String LEGACY_FAVOURITES = "settings_favorite_keys";
    /** The concept E quick buttons that have a key; HDRX, EIS, Quad, FPS and bracketing are gone. */
    private static final Map<String, String> QUICK_KEYS = new java.util.LinkedHashMap<>();
    static {
        QUICK_KEYS.put("FLASH", ShadeCatalog.FLASH);
        QUICK_KEYS.put("TIMER", ShadeCatalog.TIMER);
        QUICK_KEYS.put("RAW", ShadeCatalog.FORMAT);
        QUICK_KEYS.put("GRID", "pref_show_grid_key");
        QUICK_KEYS.put("AE_METERING_STD", ShadeCatalog.METERING_STD);
        QUICK_KEYS.put("HYBRID_OUTPUT", "pref_lmc_hybrid_output");
        QUICK_KEYS.put("HYBRID_DOWNSAMPLER", "pref_lmc_hybrid_downsampler");
    }

    /**
     * P25: the first value of the shade's tiles (ui_shade_tiles) is the user's old pins, then the 8 defaults (owner's
     * answer 10): the concept E quick buttons (ui_sheet_quick, mapped to their keys, other names dropped), then the ☆
     * favourites (settings_favorite_keys, obsolete keys dropped), then ShadeCatalog.DEFAULT_TILES, without repeats.
     * Keys the catalog does not know and everything after 12 are dropped on read (ShadeTiles.load). A stored tile list
     * is never overwritten. Both old keys are removed; a second run changes nothing.
     *
     * @return whether anything changed
     */
    public static boolean migrateShadeTiles(SharedPreferences prefs) {
        Map<String, ?> values = prefs.getAll();
        if (!values.containsKey(LEGACY_QUICK) && !values.containsKey(LEGACY_FAVOURITES)) return false;
        SharedPreferences.Editor e = prefs.edit();
        if (!values.containsKey(ShadeTiles.KEY)) {
            java.util.List<String> tiles = new java.util.ArrayList<>();
            Object quick = values.get(LEGACY_QUICK);
            if (quick != null) for (String name : quick.toString().split(",")) {
                String key = QUICK_KEYS.get(name.trim());
                if (key != null && !tiles.contains(key)) tiles.add(key);
            }
            Object favourites = values.get(LEGACY_FAVOURITES);
            if (favourites != null) {
                try {
                    org.json.JSONArray keys = new org.json.JSONArray(favourites.toString());
                    for (int i = 0; i < keys.length(); i++) {
                        String key = keys.getString(i);
                        if (!isObsolete(key) && !tiles.contains(key)) tiles.add(key);
                    }
                } catch (org.json.JSONException ignored) {}
            }
            for (String key : ShadeCatalog.DEFAULT_TILES) if (!tiles.contains(key)) tiles.add(key);
            e.putString(ShadeTiles.KEY, ShadeTiles.join(tiles));
        }
        e.remove(LEGACY_QUICK).remove(LEGACY_FAVOURITES).commit();
        return true;
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
                .getBoolean("_has_set_default_values", false) && !main.contains(LmcHybridKeys.LEGACY_HDR)
                && !main.contains(LmcHybridKeys.ROUTE);
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
     * keeps its own values; elsewhere (e.g. SCAM HDR NICE on SM8750) the hybrid starts from its own defaults (N 30, ...).
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
        final boolean firstRun = !values.containsKey(LmcHybridKeys.ENABLED) && !values.containsKey(LmcHybridKeys.ROUTE);
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
        // Route selector (October 2026): the three switches become pref_merge_route. The hybrid switch on (or the hybrid
        // took the shots before its separation, or a fresh install) -> hybrid; otherwise SCAM HDR's two switches on ->
        // scamhdr; otherwise -> hybrid (the plain legacy route is gone). The hybrid is the default on every phone: the
        // former SM8750 exclusion (d875840, SCAM HDR stayed the default where the NICE network runs) is dropped.
        if (!values.containsKey(LmcHybridKeys.ROUTE)) {
            boolean hybrid = firstRun ? freshInstall || hybridShots : PreferenceNumber.bool(values.get(LmcHybridKeys.ENABLED), false);
            boolean scam = PreferenceNumber.bool(values.get(LmcHybridKeys.LEGACY_HDR), false)
                    && PreferenceNumber.bool(values.get(LmcHybridKeys.LEGACY_NICE), false);
            e.putString(LmcHybridKeys.ROUTE, hybrid || !scam ? LmcHybridKeys.ROUTE_HYBRID : LmcHybridKeys.ROUTE_SCAM_HDR);
            changed = true;
        }
        for (String old : new String[]{LmcHybridKeys.ENABLED, LmcHybridKeys.LEGACY_HDR, LmcHybridKeys.LEGACY_NICE})
            if (values.containsKey(old)) { e.remove(old); changed = true; }
        for (String old : new String[]{"pref_vivo_nice_engine", "pref_vivo_nice_hybrid"})
            if (values.containsKey(old)) { e.remove(old); changed = true; }
        // Defaults revision 2 (4 October 2026): the Sabre detail of the ARK tone moved from 1 to 2 after the A/B against
        // ArkCam on the Oppo; a stored 1 is the old XML default written by the first round-5 build, not a user choice.
        Object rev = values.get(DEFAULTS_REV);
        int revision = rev instanceof Integer ? (Integer) rev : 0;
        boolean markOnly = false; // a revision block below only moves the marker (not reported as a change)
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
        // Defaults revision 4 (4 October 2026): Shasta as ArkCam on the same phone, 5 bracketed frames at x2 by gain (the
        // shutter-first x4 frames were blurred and never merged); stored former XML defaults (2 frames, EV 2) move along.
        if (revision < 4) {
            boolean touched = false;
            String[] keys = {"shasta_frames", "shasta_ev"};
            float[] former = {2f, 2f}, now = {5f, 1f};
            for (int i = 0; i < keys.length; i++) {
                Object v = values.get(LmcHybridKeys.PREFIX + keys[i]);
                if (v == null) continue;
                if (isNumber(v, former[i])) ModuleProfiles.put(e, LmcHybridKeys.PREFIX + keys[i], v instanceof String ? PreferenceNumber.format(now[i], i == 1) : (Object) now[i]);
                touched = true;
            }
            if (touched) {
                e.putInt(DEFAULTS_REV, 4);
                changed = true;
            }
        }
        // Defaults revision 5 (4 October 2026): the rejection colour multiplier 0.07 (LMC) let wind-moved leaves through (luma
        // zipper); a stored 0.07 is the former XML default and moves to 0.2.
        if (revision < 5) {
            Object cdm = values.get(LmcHybridKeys.PREFIX + "cdm");
            if (cdm != null) {
                if (isNumber(cdm, 0.07f)) ModuleProfiles.put(e, LmcHybridKeys.PREFIX + "cdm", cdm instanceof String ? PreferenceNumber.format(0.2f, true) : (Object) 0.2f);
                e.putInt(DEFAULTS_REV, 5);
                changed = true;
            }
        }
        // Defaults revision 6 (7 October 2026, P34): Quad streams take the native mosaic merge (2x faster than the split, +11-12 %
        // detail at the same noise) with kernel scale 0.7; the former XML defaults a P29 build stored (merge "0", kernel scale 1)
        // move along, a chosen value stays.
        if (revision < 6) {
            boolean touched = false;
            Object path = values.get(LmcHybridKeys.PREFIX + "mosaic_path");
            if (path != null) {
                if (isNumber(path, 0f)) ModuleProfiles.put(e, LmcHybridKeys.PREFIX + "mosaic_path", path instanceof String ? "1" : (Object) 1f);
                touched = true;
            }
            Object scale = values.get(LmcHybridKeys.PREFIX + "mosaic_kernel_scale");
            if (scale != null) {
                if (isNumber(scale, 1f)) ModuleProfiles.put(e, LmcHybridKeys.PREFIX + "mosaic_kernel_scale", scale instanceof String ? PreferenceNumber.format(0.7f, true) : (Object) 0.7f);
                touched = true;
            }
            if (touched) {
                e.putInt(DEFAULTS_REV, 6);
                changed = true;
            } else if (written.isEmpty()) {
                // Neither key stored yet (they had no rows before P34): the rows store today's defaults ("1", 0.7) when the screen
                // is first opened, so a "0" or a 1 stored after this run is the user's choice and must not move on a later run.
                // Mark the revision now, also when an older revision moved its own stored keys in this run (its marker is
                // overwritten in the same commit; deferring the mark to the next run let a choice made in between move back).
                // Only a run that copied legacy keys marks it on the next run, which sees the copied values (a marker past the
                // older revisions now would skip their former defaults among the copies). The marker alone is not reported as a
                // change.
                e.putInt(DEFAULTS_REV, 6);
                markOnly = true;
            }
        }
        // Defaults revision 7 (7 October 2026, P35): Tetra streams take the native mosaic merge as well (T1 on the fast merge: on the
        // vivo X200 Ultra +2..+5 dB detail, a quarter of the false colour, 46 % less flat noise at device noise and less time than
        // the split's 8 frames with 17). The "Tetra path" row stored "0" (the split) as its XML default since P29; a stored "0" is
        // that former default and moves to "1", a chosen T2 ("2") stays. As in revision 6, the marker is set when the key is not
        // stored yet (the row stores today's "1" when the screen is first opened), so a "0" chosen after this run stays, also when
        // an older revision moved its stored keys in this run; only a run that copied legacy keys marks it on the next run.
        if (revision < 7) {
            Object tetra = values.get(LmcHybridKeys.PREFIX + "mosaic_tetra");
            if (tetra != null) {
                if (isNumber(tetra, 0f)) ModuleProfiles.put(e, LmcHybridKeys.PREFIX + "mosaic_tetra", tetra instanceof String ? "1" : (Object) 1f);
                e.putInt(DEFAULTS_REV, 7);
                changed = true;
            } else if (written.isEmpty()) {
                e.putInt(DEFAULTS_REV, 7);
                markOnly = true;
            }
        }
        // N frames from the ZSL ring 20 -> 30 (owner, 7 October 2026: towards ArkCam's ~30 merged frames; detail grows with
        // the frame count). A stored 20 is the former XML default (the row stores it when the screen is first shown) and
        // moves to 30; any other value is the user's and stays. Its own one-time marker, set in the first run whatever it
        // finds: a value copied from SCAM HDR in this run (the hybrid took the shots with it) is not in `values` and stays,
        // and a 20 chosen after this run is never moved.
        if (!values.containsKey(ZSL_FRAMES_REV)) {
            Object frames = values.get(LmcHybridKeys.PREFIX + "zsl_frames");
            if (frames != null && isNumber(frames, 20f)) {
                ModuleProfiles.put(e, LmcHybridKeys.PREFIX + "zsl_frames", frames instanceof String ? "30" : (Object) 30f);
                changed = true;
            }
            e.putInt(ZSL_FRAMES_REV, 1);
            markOnly = true;
        }
        // Frames in the mosaic merge 24 -> 30 (owner, 7 October 2026, with the N frames above: our Quad merge took 21 frames,
        // ArkCam 30). A stored 24 is the former XML default and moves to 30; any other value is the user's and stays. Its own
        // one-time marker, as the N frames' (the key has no legacy or SCAM HDR source to copy from): set in the first run
        // whatever it finds, so a 24 chosen after this run is never moved.
        if (!values.containsKey(MOSAIC_FRAMES_REV)) {
            Object frames = values.get(LmcHybridKeys.PREFIX + "mosaic_frames");
            if (frames != null && isNumber(frames, 24f)) {
                ModuleProfiles.put(e, LmcHybridKeys.PREFIX + "mosaic_frames", frames instanceof String ? "30" : (Object) 30f);
                changed = true;
            }
            e.putInt(MOSAIC_FRAMES_REV, 1);
            markOnly = true;
        }
        // Slider precision (settings audit H1, 8 October 2026): the sliders stored two decimals, so the first opening of the
        // screen wrote the XML default 0.0005 of the Bento auto threshold as "0.00" (Bento then triggered on almost every
        // shot) and 1.414 of the LUT sigma as "1.41". Exactly those stored strings are that rounding, not a choice, and move
        // to the defaults. Own one-time marker, set in the first run whatever it finds, so a "0.00" chosen later stays.
        if (!values.containsKey(PRECISION_REV)) {
            if ("0.00".equals(values.get(LmcHybridKeys.PREFIX + "bento_trigger"))) {
                e.putString(LmcHybridKeys.PREFIX + "bento_trigger", "0.0005");
                changed = true;
            }
            if ("1.41".equals(values.get(LmcHybridKeys.PREFIX + "lut_sigma"))) {
                e.putString(LmcHybridKeys.PREFIX + "lut_sigma", "1.414");
                changed = true;
            }
            e.putInt(PRECISION_REV, 1);
            markOnly = true;
        }
        if (changed || markOnly) e.commit();
        return changed;
    }

    private static final String DEFAULTS_REV = "pref_lmc_hybrid_defaults_rev";
    /** Marker of the one-time move of a stored former default of pref_lmc_hybrid_zsl_frames (20) to 30. */
    static final String ZSL_FRAMES_REV = "pref_lmc_hybrid_zsl_frames_rev";
    /** Marker of the one-time move of a stored former default of pref_lmc_hybrid_mosaic_frames (24) to 30. */
    static final String MOSAIC_FRAMES_REV = "pref_lmc_hybrid_mosaic_frames_rev";
    /** Marker of the one-time move of the two-decimal slider defaults "0.00" (Bento auto threshold) and "1.41" (LUT sigma). */
    static final String PRECISION_REV = "pref_lmc_hybrid_precision_rev";

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
        if (!PreferenceNumber.bool(values.get(LmcHybridKeys.LEGACY_HDR), false)
                || !PreferenceNumber.bool(values.get(LmcHybridKeys.LEGACY_NICE), false)) return false;
        Object engine = values.get("pref_vivo_nice_engine");
        if (engine == null && values.containsKey("pref_vivo_nice_hybrid"))
            return PreferenceNumber.bool(values.get("pref_vivo_nice_hybrid"), true);
        String v = engine == null ? "auto" : engine.toString();
        return "hybrid".equals(v) || !"nice".equals(v) && !LmcHybridKeys.vivoNetSoc();
    }
    /** An android: attribute of the current tag, a resource reference resolved to its text (also used by XmlDefaults). */
    static String attribute(Context context, XmlResourceParser parser, String name) {
        int id=parser.getAttributeResourceValue(ANDROID,name,0);
        if(id==0) return parser.getAttributeValue(ANDROID,name);
        android.util.TypedValue value=new android.util.TypedValue();
        context.getResources().getValue(id,value,true);
        CharSequence text=value.coerceToString();return text==null ? null : text.toString();
    }
}
