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
     * Parameters the camera screen no longer offers: EIS
     * (only while recording video), exposure bracketing (only the old planner; the hybrid and SCAM HDR plan
     * their own frames), Quad Bayer (switches the RAW stream to the full sensor mode, which the ZSL burst of the hybrid
     * must not) and a fixed preview FPS (caps the exposure of the ZSL frames). They go back to their defaults, so a value
     * chosen in an older build cannot stay on unseen. Capture still reads EIS, FPS and Quad, so these resets stay even
     * though the settings bar that showed them is gone (P25: moved here from SettingsBarEntryProvider).
     */
    public static void resetRemovedSettings(android.content.res.Resources res) {
        boolean eis = res.getBoolean(R.bool.pref_eis_photo_default);
        if (PreferenceKeys.isEisPhotoOn() != eis) PreferenceKeys.setEisPhoto(eis);
        if (PreferenceKeys.getFpsMode() != 0) PreferenceKeys.setFpsMode(0);
        if (PreferenceKeys.getBracketingMode() != 0) PreferenceKeys.setBracketingMode(0);
    }

    /**
     * P118: «Частота мерцания» is a list (Авто / 100 / 120 / Выкл.), once per preference set: the old default 120 (60 Hz mains,
     * wrong in 50 Hz countries) becomes «Авто»; another number the nearest entry (50 / 60 as mains: 100 / 120), 0 or less off.
     */
    public static void migrateAntibanding(SharedPreferences preferences) {
        if (preferences.contains("settings_antibanding_list_v1")) return;
        SharedPreferences.Editor e = preferences.edit().putBoolean("settings_antibanding_list_v1", true);
        Object stored = preferences.getAll().get("pref_antibanding_hz_key");
        if (stored != null) e.putString("pref_antibanding_hz_key", antibandingEntry(PreferenceNumber.read(stored, 120)));
        e.commit();
    }

    static String antibandingEntry(double hz) {
        if (!Double.isFinite(hz) || hz == 120) return "-1";
        if (hz <= 0) return "0";
        if (hz <= 70) hz *= 2; // the mains frequency typed instead of the flicker
        return hz < 110 ? "100" : "120";
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
            "pref_scam_upscale_backend_key", "pref_scam_downscale_kernel_key", "pref_scam_downscale_size_key",
            // P2: "Кадрирование DNG" (the 16:9 crop is always centred now)
            "pref_tunable_imagesaversettings_croptype",
            // P3: the route switches, replaced by pref_merge_route (migrateScamHybrid converts them first)
            "pref_scam_hybrid_enabled", "pref_scamroute_enabled", "pref_scamhdr_enabled",
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
            "hexquad_post_denoise", "quad2x2_exposure_ev", "quad2x2_post_denoise", "pref_scamroute_luma",
            "pref_scamroute_chroma", "pref_scamroute_sharpen", "pref_ai_denoise_enabled_key",
            "pref_ai_denoise_strength_key", "pref_ai_denoise_luma_key", "pref_ai_denoise_chroma_key",
            "pref_ai_denoise_model_key", "pref_remosaic_dump_key", "pref_noise_dynamic_enabled_key",
            // P5: the legacy post-processing (RT / ESD3D denoise, reference finish, tone pipelines, ACES, Capture One,
            // darktable, false colour / CA, the old tone sliders, noise-model ISO overrides)
            "pref_rt_denoise_backend", "pref_capture_one_enabled_key", "pref_false_color_enabled_key",
            "pref_saliency_protection_key", "pref_false_color_strength_key", "pref_defringe_purple_key",
            "pref_defringe_green_key", "pref_ca_red_key", "pref_ca_blue_key", "pref_saturation_seekbar_key",
            "pref_contrast_seekbar_key", "pref_shadows_seekbar_key", "pref_compressor_seekbar_key",
            "pref_sensor_sharpening_enabled", "pref_noise_disable_digital_gain_key", "pref_noise_model_coefficient_key",
            "pref_noise_iso_curve_key", "pref_noise_iso_min_key", "pref_noise_iso_max_key", "pref_noise_iso_manual_key",
            "pref_noise_seekbar_key", "pref_agx_local_highlights", "pref_agx_local_start",
            // P10: one tone (ARK) for both routes; the SCAMERA tone of SCAM HDR and the hybrid's «Тон SCAMERA» go
            "pref_expocompensation_seekbar_key", "pref_scamold_ae_mid", "pref_scamold_ae_high", "pref_scamold_ae_gain_max",
            "pref_scamhdr_soft_tone", "pref_scamhdr_tone_key", "pref_scamhdr_sharp_amount", "pref_scamhdr_texture",
            "pref_scamhdr_warm_retention", "pref_scam_hybrid_ae_mid", "pref_scam_hybrid_ae_high",
            "pref_scam_hybrid_ae_gain_max", "pref_scam_hybrid_bento_fusion", "pref_scam_hybrid_texture", "pref_scam_hybrid_ark_tone",
            "pref_scam_hybrid_soft_tone", "pref_scam_hybrid_tone_key", "pref_scam_hybrid_warm_retention",
            // P8: the retired VCF2 route selector and the unreachable RAW video mode
            "pref_scamhdr_route", "pref_rawvideo_downscale_4x_key", "pref_rawvideo_write_zip_key", "pref_rawvideo_crop_169_key",
            // P12b: the old texture boost switch never reached the worker; pref_scam_hybrid_motion_boost replaces it
            "pref_scam_hybrid_boost",
            // P64: the «Hybrid curves» presets (tone / gamma curve and their strengths) are gone (owner, 2026-10-09)
            "pref_scam_tone_curve", "pref_scam_tone_curve_strength", "pref_scam_gamma_curve", "pref_scam_gamma_curve_strength",
            // P25: the Quad toggle of the top bar is gone, and with it its tunable «Enable Quad Resolution»; the quick
            // buttons of concept E and the ☆ favourites became the shade's tiles (migrateShadeTiles reads them first)
            "pref_tunable_camerauiviewimpl_enablequadres", "ui_sheet_quick", "settings_favorite_keys",
            // Settings audit (8 October 2026): «Формат превью» only added an ImageReader nobody read to the session
            "pref_preview_format_key",
            // Settings audit S5: keys of inherited settings with no row since P4 / P5 that api/Settings still read
            "pref_enable_system_nr_key", "pref_disable_aligning_key", "pref_enhanced_processing_key", "pref_hdrx_nr_key",
            "pref_chroma_nr_seekbar_key", "pref_luma_nr_seekbar_key", "pref_gain_seekbar_key", "pref_sharpness_seekbar_key",
            "pref_nr_luma_enabled_key", "pref_nr_chroma_enabled_key", "pref_live_viewfinder_look_key", "pref_align_method_key",
            // The former HDR switch of the top bar (read by nothing since the two SCAM routes)
            "pref_hdrx_key",
            // P118: Quad Bayer at full resolution (forced off since P25, its capture branches unreachable) and «Quad Bayer —
            // совместимость» (vendor remosaic tags, a -2 CFA mark that sent every SCAM HDR shot to the hybrid, a 4x4 DNG CFA);
            // Quad / Tetra streams come from the ISZ modules and the hybrid measures their colour block itself
            "pref_quad_bayer_key", "scamera_quad_bayer_enabled", "scamera_quad_bayer_mode", "scamera_quad_dng_metadata",
            // P118: «Показать градиентный фон» (its theme set the background to the colour it already had)
            "pref_show_gradient_key",
            // P118: two ArkCore exposure rows nothing read (faces never reach processing; the trade-off is fixed at 0.33)
            "pref_scam_hybrid_ark_face_priority", "pref_scam_hybrid_ark_hl_blend",
            // P118: only the ARK sharpening stays (owner, 2026-10-13): the mode list, its «Доп. резкость после тона», the
            // weight of a post-tone sharpening in lifted shadows, the RawTherapee multiplier, SCAM's amount, and the Sabre
            // detail used only without ARK sharpening
            "pref_scam_hybrid_sharp_mode", "pref_scam_hybrid_ark_post_sharp", "pref_scam_hybrid_ark_sharp_guard",
            "pref_scam_hybrid_sharp_strength", "pref_scam_hybrid_sharp_amount", "pref_scam_hybrid_ark_detail_gain",
            // P175: the neural remosaic (owner, 2026-10-16: it ran only on the X200 Ultra and the 8 Elite): its switch and the
            // marker of its P129 migration, the GPU remosaic of the other frames and the Tetra response correction
            // (pref_remosaic_key stays: api/Settings reads it; the HexQuad / Quad 2x2 network rows are OBSOLETE_PREFIXES)
            "pref_scam_hybrid_neural_remosaic", "pref_scam_hybrid_neural_remosaic_rev", "pref_tetra_response_key",
            "pref_remosaic_block_key", "pref_remosaic_profile_key", "pref_remosaic_steered_key", "pref_remosaic_clamp_key",
            "pref_remosaic_flatfield_key", "pref_remosaic_phase_x_key", "pref_remosaic_phase_y_key"));
    static final String[] OBSOLETE_PREFIXES = {"pref_raisr_", "pref_softpqe_",
            "pref_snr_", "pref_mfsr_", "scamera_mosaic_sr_", "pref_hdrplus_", "pref_tunable_esd4d_", "pref_tunable_pyramidalignment_",
            // P5: the legacy post-processing and the tunables of its nodes
            "rt512_", "pref_rt_nr_", "pref_aces_", "pref_c1_", "scamera_darktable_",
            "pref_tunable_postpipeline_", "pref_tunable_esd3d2_", "pref_tunable_ablc_", "pref_tunable_initial_",
            "pref_tunable_autoexposurecurve_", "pref_tunable_opendrt_", "pref_tunable_locallaplacian_",
            "pref_tunable_linearexposure_", "pref_tunable_headroomrender_", "pref_tunable_bayer2float_",
            "pref_tunable_amaze_",
            // P10: AgX, Exposure Fusion and the headroom tone of both routes (and the hybrid's copies)
            "pref_agx_", "pref_scamroute_", "pref_scamhdr_fusion_", "pref_scam_hybrid_hdr_", "pref_scam_hybrid_fusion_",
            "pref_scam_hybrid_agx_",
            // P118: RAW CA as RawTherapee's CA_correct (P28) is gone; the merge's own lateral CA fit (P19) stays
            "pref_scam_hybrid_rawca_",
            // P118: «Опыты JSR» (phase kernel, CA in the merge, polynomial field); «Sabre 2× только когда есть смысл» is its own row
            "pref_scam_hybrid_jsr_",
            // P118: only the ARK sharpening stays (owner, 2026-10-13): the RawTherapee sharpening page (USM, RL deconvolution,
            // microcontrast) and the SCAM HDR sharpening knobs ScamSharpen read (pref_scamhdr_sharp_*)
            "pref_sharp_", "pref_scamhdr_sharp_",
            // P129: SCAM HDR is gone (its settings, the L frame from the ZSL ring, the stock planner); migrateScamHybrid runs
            // first everywhere, so the values the hybrid shared were copied before (pref_merge_route stays: its marker)
            "pref_scamhdr_", "pref_scamold_",
            // P175: the HexQuad and Quad 2x2 network rows of the neural remosaic
            "hexquad_", "quad2x2_"};
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
        // «Фильтр Байера» lost MONO (4: every Hybrid / SCAM HDR shot failed, both need a 2x2 CFA) and QUAD (-2: did nothing,
        // the Quad stream is set in «Quad Bayer — совместимость»); a stored one of them becomes «Авто» (-1).
        Object cfa = prefs.getAll().get(CFA_KEY);
        if (cfa != null && isRemovedCfa(cfa)) { e.putString(CFA_KEY, "-1"); changed = true; }
        // P118: «Карта силы» lost «Карта эффективных кадров» (it did exactly what «Авто» does)
        if ("frames".equals(prefs.getAll().get("pref_scam_hybrid_dn_strength_map"))) {
            e.putString("pref_scam_hybrid_dn_strength_map", "auto"); changed = true;
        }
        // Rows that store a whole number and were decimal sliders (settings audit H4): the ARK metering, AgX look, sharpening
        // domain and RL kernels are lists now, the SCAM HDR radii integer sliders. A stored "1.00" or float 1.0 becomes "1"
        // (the list value); a value that is no number at all goes, so the row's default applies.
        for (String key : WHOLE_NUMBER_KEYS) {
            Object value = prefs.getAll().get(key);
            if (value == null) continue;
            String whole = wholeNumber(key, value);
            if (whole == null) { e.remove(key); changed = true; }
            else if (!(value instanceof String) || !whole.equals(value)) { e.putString(key, whole); changed = true; }
        }
        if (changed) e.commit();
        return changed;
    }

    /** Keys whose rows store a whole number but were decimal sliders before the settings audit. */
    static final String[] WHOLE_NUMBER_KEYS = {"pref_scam_hybrid_ark_metering", "pref_scam_hybrid_ark_agx_look",
            "pref_scam_hybrid_ark_sharp_domain", "pref_scam_hybrid_ark_sharp_rl1_kernel", "pref_scam_hybrid_ark_sharp_rl2_kernel",
            "pref_scam_hybrid_ark_sharp_rl3_kernel", "pref_scamhdr_luma_radius", "pref_scamhdr_chroma_radius"};
    /** The stored value as the whole number its row writes, within the key's bounds; null when it is no number. */
    static String wholeNumber(String key, Object value) {
        double v = PreferenceNumber.read(value, Double.NaN);
        if (!Double.isFinite(v)) return null;
        double[] bounds = SettingsNumericRules.bounds(key);
        if (bounds != null) v = Math.max(bounds[0], Math.min(bounds[1], v));
        return Long.toString(Math.round(v));
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
            if (e.getKey().startsWith("exists_") && Boolean.TRUE.equals(e.getValue())) {
                SharedPreferences profile = context.getSharedPreferences("module_profile_v2_" + e.getKey().substring(7), Context.MODE_PRIVATE);
                removeObsolete(profile);
                dropGlobal(profile);
            }
        SharedPreferences baseline = context.getSharedPreferences("module_profile_v2_common", Context.MODE_PRIVATE);
        removeObsolete(baseline);
        dropGlobal(baseline);
    }

    /**
     * Settings shared by every lens (ModuleProfiles.isGlobal, owner 8 October 2026) leave a module profile or the baseline:
     * the main settings hold their one value (the active module's when they were still per module), and an old per-module
     * copy can never come back. Returns whether anything changed.
     */
    static boolean dropGlobal(SharedPreferences profile) {
        SharedPreferences.Editor e = profile.edit();
        boolean changed = false;
        for (String key : profile.getAll().keySet())
            if (ModuleProfiles.isGlobal(key)) { e.remove(key); changed = true; }
        if (changed) e.commit();
        return changed;
    }

    /** Quick buttons of the concept E sheet: SettingType names, comma separated, oldest first. */
    static final String LEGACY_QUICK = "ui_sheet_quick";
    /** The ☆ favourites of the viewfinder: a JSON array of preference keys. */
    static final String LEGACY_FAVOURITES = "settings_favorite_keys";
    /** The concept E quick buttons that have a key; the others (EIS, Quad, FPS, bracketing, the former HDR switch) are gone. */
    private static final Map<String, String> QUICK_KEYS = new java.util.LinkedHashMap<>();
    static {
        QUICK_KEYS.put("FLASH", ShadeCatalog.FLASH);
        QUICK_KEYS.put("TIMER", ShadeCatalog.TIMER);
        QUICK_KEYS.put("RAW", ShadeCatalog.FORMAT);
        QUICK_KEYS.put("GRID", "pref_show_grid_key");
        QUICK_KEYS.put("AE_METERING_STD", ShadeCatalog.METERING_STD);
        QUICK_KEYS.put("HYBRID_OUTPUT", "pref_scam_hybrid_output");
        QUICK_KEYS.put("HYBRID_DOWNSAMPLER", "pref_scam_hybrid_downsampler");
    }

    /**
     * P25: the first value of the shade's tiles (ui_shade_tiles) is the user's old pins, then the 8 defaults (owner's
     * answer 10): the concept E quick buttons (ui_sheet_quick, mapped to their keys, other names dropped), then the ☆
     * favourites (settings_favorite_keys, obsolete keys dropped), then ShadeCatalog.defaultTiles(), without repeats.
     * Keys the catalog does not know and everything after 12 are dropped on read (ShadeTiles.load). A stored tile list
     * is never overwritten. Both old keys are removed; a second run changes nothing.
     *
     * @return whether anything changed
     */
    /** SIMPLE_UI §2: set once the old default pin list moved to the new defaults. */
    static final String SIMPLE_UI_TILES = "ui_shade_tiles_simple_ui";

    /**
     * SIMPLE_UI §2: a user who still has the old default pin list (7 tiles) gets the new defaults (18 + «Добавить»); a custom
     * list stays as it is. Once.
     */
    public static boolean migrateSimpleUiTiles(SharedPreferences prefs) {
        if (prefs.getBoolean(SIMPLE_UI_TILES, false)) return false;
        SharedPreferences.Editor e = prefs.edit().putBoolean(SIMPLE_UI_TILES, true);
        // plain strings here: SettingsManager runs this while the settings classes initialise (no ShadeCatalog yet)
        boolean moved = OLD_DEFAULT_TILES.equals(prefs.getString(ShadeTiles.KEY, null));
        // the stored list goes: the tiles then come from ShadeCatalog.defaultTiles() (the new defaults)
        if (moved) e.remove(ShadeTiles.KEY);
        e.commit();
        return moved;
    }

    /** P249: set once a stored tile list had «Размер» swapped for «Разрешение». */
    static final String OUTPUT_TILE = "ui_shade_tiles_output_p249";

    /**
     * P249 (owner, 2026-10-18: «по дефолту у нас есть эта настройка в шторке, замени её на плитку с настройкой Sabre
     * 1x/16/20/2x»): a stored tile list with «Размер» (ui_photo_size) and without «Разрешение» (pref_scam_hybrid_output) gets
     * «Разрешение» in the same place; nothing stored: the new defaults have it already. Once.
     */
    public static boolean migrateOutputTile(SharedPreferences prefs) {
        if (prefs.getBoolean(OUTPUT_TILE, false)) return false;
        SharedPreferences.Editor e = prefs.edit().putBoolean(OUTPUT_TILE, true);
        String stored = prefs.getString(ShadeTiles.KEY, null);
        boolean moved = false;
        if (stored != null && !stored.isEmpty()) {
            // plain strings: this runs while the settings classes initialise
            java.util.List<String> keys = new java.util.ArrayList<>(java.util.Arrays.asList(stored.split(",")));
            int at = keys.indexOf("ui_photo_size");
            if (at >= 0 && !keys.contains("pref_scam_hybrid_output")) {
                keys.set(at, "pref_scam_hybrid_output");
                e.putString(ShadeTiles.KEY, String.join(",", keys));
                moved = true;
            }
        }
        e.commit();
        return moved;
    }

    /** ShadeCatalog.OLD_DEFAULT_TILES as ShadeTiles stores it. */
    static final String OLD_DEFAULT_TILES = "pref_ae_mode_key,pref_countdown_timer_key,pref_save_raw_key,pref_scam_hybrid_output,"
            + "pref_show_grid_key,pref_ultrahdr_key,pref_scam_hybrid_bento";

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
            for (String key : ShadeCatalog.defaultTiles()) if (!tiles.contains(key)) tiles.add(key);
            e.putString(ShadeTiles.KEY, ShadeTiles.join(tiles));
        }
        e.remove(LEGACY_QUICK).remove(LEGACY_FAVOURITES).commit();
        return true;
    }

    private static final String LEGACY_HYBRID = ScamHybridKeys.LEGACY_PREFIX;
    /** SCAM HDR knobs (pref_scamhdr_&lt;k&gt;) the hybrid route read until it got its own settings. */
    private static final java.util.Set<String> SHARED_SCAM_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "zsl_frames", "highlight_recovery", "isp_ccm", "noise_photon", "noise_readout",
            "noise_source", "cre_source", "diagnostics"));
    private static final String[] SHARED_SCAM_PREFIXES = {"fusion_", "tone_", "look", "warm_", "texture", "grain_"};
    /** Autonomous HDR keys that are not on the hybrid route (scam HDR denoise, capture sharpening, the reset action). */
    private static final java.util.Set<String> UNSHARED_HDR_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "enabled", "luma", "chroma", "sharpen", "reset_tone"));

    /**
     * The SCAM Hybrid left SCAM HDR (section «SCAM-гибрид», 3 October 2026). Applied to the main preferences, the module
     * baseline and every stored module profile; restored snapshots go through {@link #migrateScamHybrid(SharedPreferences, boolean)}.
     */
    public static void migrateScamHybrid(Context context, SharedPreferences main) {
        // Fresh install: androidx has never written the XML defaults and nothing set the autonomous HDR switch.
        boolean fresh = !context.getSharedPreferences("_has_set_default_values", Context.MODE_PRIVATE)
                .getBoolean("_has_set_default_values", false) && !main.contains(ScamHybridKeys.LEGACY_HDR)
                && !main.contains(ScamHybridKeys.ROUTE);
        migrateScamHybrid(main, fresh);
        SharedPreferences meta = context.getSharedPreferences("module_profiles_meta", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : meta.getAll().entrySet())
            if (e.getKey().startsWith("exists_") && Boolean.TRUE.equals(e.getValue()))
                migrateScamHybrid(context.getSharedPreferences("module_profile_v2_" + e.getKey().substring(7), Context.MODE_PRIVATE), false);
        if (meta.getBoolean("baseline", false))
            migrateScamHybrid(context.getSharedPreferences("module_profile_v2_common", Context.MODE_PRIVATE), false);
    }

    /**
     * One preference set: pref_scamhdr_hybrid_&lt;k&gt; moves to pref_scam_hybrid_&lt;k&gt; (except the soft tone, gone for
     * the hybrid, and the Bento factor, which starts again from the SCAM default 8). Where the hybrid took the shots so far
     * (SCAM HDR on and pref_scamhdr_engine resolving to the hybrid), the SCAM HDR knobs it read (Exposure Fusion, tone,
     * look, texture, sharpening, AE, AgX highlights, autonomous HDR tone, noise and CRE source, N frames, diagnostics,
     * fast capture) are copied to the hybrid's keys (ScamHybridKeys.copyKey), so the hybrid keeps its look while SCAM HDR
     * keeps its own values; elsewhere (e.g. SCAM HDR SCAM on SM8750) the hybrid starts from its own defaults (N 30, ...).
     * The copy happens once, in the run that creates pref_scam_hybrid_enabled (= the hybrid took the shots so far; on a
     * fresh install: on wherever the SCAM network does not run), so later SCAM HDR changes never reach the hybrid.
     * pref_scamhdr_engine and the first switch pref_scamhdr_hybrid are removed. Keys that already exist under the new
     * names are never overwritten, so a second run changes nothing.
     *
     * @return whether anything changed
     */
    public static boolean migrateScamHybrid(SharedPreferences prefs, boolean freshInstall) {
        Map<String, ?> values = prefs.getAll();
        SharedPreferences.Editor e = prefs.edit();
        java.util.Set<String> written = new java.util.HashSet<>();
        boolean changed = false;
        final boolean firstRun = !values.containsKey(ScamHybridKeys.ENABLED) && !values.containsKey(ScamHybridKeys.ROUTE);
        final boolean hybridShots = firstRun && !freshInstall && legacyHybridShots(values);
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(LEGACY_HYBRID)) continue;
            String k = key.substring(LEGACY_HYBRID.length());
            String target = ScamHybridKeys.PREFIX + k;
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
            // The hybrid's noise profile list is auto | settings; the SCAM-only sources mean auto for it.
            if (key.equals("pref_scamhdr_noise_source") && !"settings".equals(entry.getValue().toString())) continue;
            String target = ScamHybridKeys.copyKey(key);
            if (target.equals(key) || values.containsKey(target) || !written.add(target)) continue;
            ModuleProfiles.put(e, target, entry.getValue());
            changed = true;
        }
        // Route selector (October 2026): the three switches become pref_merge_route. The hybrid switch on (or the hybrid
        // took the shots before its separation, or a fresh install) -> hybrid; otherwise SCAM HDR's two switches on ->
        // scamhdr; otherwise -> hybrid (the plain legacy route is gone). The hybrid is the default on every phone: the
        // former SM8750 exclusion (d875840, SCAM HDR stayed the default where the SCAM network runs) is dropped.
        if (!values.containsKey(ScamHybridKeys.ROUTE)) {
            boolean hybrid = firstRun ? freshInstall || hybridShots : PreferenceNumber.bool(values.get(ScamHybridKeys.ENABLED), false);
            boolean scam = PreferenceNumber.bool(values.get(ScamHybridKeys.LEGACY_HDR), false)
                    && PreferenceNumber.bool(values.get(ScamHybridKeys.LEGACY_SCAM), false);
            e.putString(ScamHybridKeys.ROUTE, hybrid || !scam ? ScamHybridKeys.ROUTE_HYBRID : ScamHybridKeys.ROUTE_SCAM_HDR);
            changed = true;
        }
        for (String old : new String[]{ScamHybridKeys.ENABLED, ScamHybridKeys.LEGACY_HDR, ScamHybridKeys.LEGACY_SCAM})
            if (values.containsKey(old)) { e.remove(old); changed = true; }
        for (String old : new String[]{"pref_scamhdr_engine", "pref_scamhdr_hybrid"})
            if (values.containsKey(old)) { e.remove(old); changed = true; }
        // Defaults revision 2 (the Sabre detail ark_detail_gain 1 -> 2) is gone with that row (P118: only the ARK sharpening
        // stays, the detail without it is never used); revision 3 lost its sharp_mode "rt" -> "ark" step the same way.
        Object rev = values.get(DEFAULTS_REV);
        int revision = rev instanceof Integer ? (Integer) rev : 0;
        boolean markOnly = false; // a revision block below only moves the marker (not reported as a change)
        // Defaults revision 3 (4 October 2026): the noise reduction is ArkCam's exact tables (no revert cap, no stock coarse
        // luma, no chroma floor); stored values equal to the former XML defaults (2, 0.5, 2.75) are those defaults, not user choices.
        if (revision < 3) {
            boolean touched = false;
            String[] keys = {"dn_revert_max", "dn_coarse_stock", "dn_chroma_floor"};
            float[] former = {2f, 0.5f, 2.75f}, ark = {9f, 0f, 0f};
            for (int i = 0; i < keys.length; i++) {
                Object v = values.get(ScamHybridKeys.PREFIX + keys[i]);
                if (v == null) continue;
                if (isNumber(v, former[i])) ModuleProfiles.put(e, ScamHybridKeys.PREFIX + keys[i], v instanceof String ? PreferenceNumber.format(ark[i], true) : (Object) ark[i]);
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
                Object v = values.get(ScamHybridKeys.PREFIX + keys[i]);
                if (v == null) continue;
                if (isNumber(v, former[i])) ModuleProfiles.put(e, ScamHybridKeys.PREFIX + keys[i], v instanceof String ? PreferenceNumber.format(now[i], i == 1) : (Object) now[i]);
                touched = true;
            }
            if (touched) {
                e.putInt(DEFAULTS_REV, 4);
                changed = true;
            }
        }
        // Defaults revision 5 (4 October 2026): the rejection colour multiplier 0.07 (SCAM) let wind-moved leaves through (luma
        // zipper); a stored 0.07 is the former XML default and moves to 0.2.
        if (revision < 5) {
            Object cdm = values.get(ScamHybridKeys.PREFIX + "cdm");
            if (cdm != null) {
                if (isNumber(cdm, 0.07f)) ModuleProfiles.put(e, ScamHybridKeys.PREFIX + "cdm", cdm instanceof String ? PreferenceNumber.format(0.2f, true) : (Object) 0.2f);
                e.putInt(DEFAULTS_REV, 5);
                changed = true;
            }
        }
        // Defaults revision 6 (7 October 2026, P34): Quad streams take the native mosaic merge (2x faster than the split, +11-12 %
        // detail at the same noise) with kernel scale 0.7; the former XML defaults a P29 build stored (merge "0", kernel scale 1)
        // move along, a chosen value stays.
        if (revision < 6) {
            boolean touched = false;
            Object path = values.get(ScamHybridKeys.PREFIX + "mosaic_path");
            if (path != null) {
                if (isNumber(path, 0f)) ModuleProfiles.put(e, ScamHybridKeys.PREFIX + "mosaic_path", path instanceof String ? "1" : (Object) 1f);
                touched = true;
            }
            Object scale = values.get(ScamHybridKeys.PREFIX + "mosaic_kernel_scale");
            if (scale != null) {
                if (isNumber(scale, 1f)) ModuleProfiles.put(e, ScamHybridKeys.PREFIX + "mosaic_kernel_scale", scale instanceof String ? PreferenceNumber.format(0.7f, true) : (Object) 0.7f);
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
            Object tetra = values.get(ScamHybridKeys.PREFIX + "mosaic_tetra");
            if (tetra != null) {
                if (isNumber(tetra, 0f)) ModuleProfiles.put(e, ScamHybridKeys.PREFIX + "mosaic_tetra", tetra instanceof String ? "1" : (Object) 1f);
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
        boolean zslMovedTo30 = false;
        if (!values.containsKey(ZSL_FRAMES_REV)) {
            Object frames = values.get(ScamHybridKeys.PREFIX + "zsl_frames");
            if (frames != null && isNumber(frames, 20f)) {
                ModuleProfiles.put(e, ScamHybridKeys.PREFIX + "zsl_frames", frames instanceof String ? "30" : (Object) 30f);
                zslMovedTo30 = true;
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
            Object frames = values.get(ScamHybridKeys.PREFIX + "mosaic_frames");
            if (frames != null && isNumber(frames, 24f)) {
                ModuleProfiles.put(e, ScamHybridKeys.PREFIX + "mosaic_frames", frames instanceof String ? "30" : (Object) 30f);
                changed = true;
            }
            e.putInt(MOSAIC_FRAMES_REV, 1);
            markOnly = true;
        }
        // P68 defaults (owner, 10 October 2026, every phone): N frames 30 -> 20, Sabre 6.1 kernel auto -> always, Mochi off -> always,
        // Bento auto -> always with 2 -> 3 frames, Shasta frames 5 -> 3 (Shasta "always" is a new row, pref_scam_hybrid_shasta_mode).
        // A stored former XML default (written when the screen was first shown) moves once, any other value is the user's and
        // stays; a 30 the N-frames move above wrote in this run is that former default too. Own marker, set in the first run.
        if (!values.containsKey(P68_DEFAULTS_REV)) {
            final String[] keys = {"zsl_frames", "sabre61", "mochi", "bento", "bento_frames", "shasta_frames"};
            final float[] former = {30f, 2f, 0f, 1f, 2f, 5f}, now = {20f, 1f, 2f, 2f, 3f, 3f};
            for (int i = 0; i < keys.length; i++) {
                final String key = ScamHybridKeys.PREFIX + keys[i];
                Object v = values.get(key);
                if (v == null || !(isNumber(v, former[i]) || (i == 0 && zslMovedTo30))) continue;
                ModuleProfiles.put(e, key, v instanceof String ? PreferenceNumber.format(now[i], false) : (Object) now[i]);
                changed = true;
            }
            e.putInt(P68_DEFAULTS_REV, 1);
            markOnly = true;
        }
        // Slider precision (settings audit H1, 8 October 2026): the sliders stored two decimals, so the first opening of the
        // screen wrote the XML default 0.0005 of the Bento auto threshold as "0.00" (Bento then triggered on almost every
        // shot) and 1.414 of the LUT sigma as "1.41". Exactly those stored strings are that rounding, not a choice, and move
        // to the defaults. Own one-time marker, set in the first run whatever it finds, so a "0.00" chosen later stays.
        if (!values.containsKey(PRECISION_REV)) {
            if ("0.00".equals(values.get(ScamHybridKeys.PREFIX + "bento_trigger"))) {
                e.putString(ScamHybridKeys.PREFIX + "bento_trigger", "0.0005");
                changed = true;
            }
            if ("1.41".equals(values.get(ScamHybridKeys.PREFIX + "lut_sigma"))) {
                e.putString(ScamHybridKeys.PREFIX + "lut_sigma", "1.414");
                changed = true;
            }
            e.putInt(PRECISION_REV, 1);
            markOnly = true;
        }
        // SCAM HDR defaults (owner, 8 October 2026): «Сохранять этапы обработки» off (it wrote a ZIP and scanned the vendor keys on
        // every shot) and the SCAMERA planner (the stock vivo AE needs Root on the vivo X200 Ultra). A stored former default (on /
        // "stock", written when the screen was first opened) moves once; own marker, so a choice made later stays.
        if (!values.containsKey(SCAM_DEFAULTS_REV)) {
            Object diagnostics = values.get("pref_scamhdr_diagnostics");
            if (diagnostics != null && PreferenceNumber.bool(diagnostics, false)) {
                e.putBoolean("pref_scamhdr_diagnostics", false);
                changed = true;
            }
            Object planner = values.get("pref_scamhdr_planner");
            if (planner != null && "stock".equals(planner.toString().trim())) {
                e.putString("pref_scamhdr_planner", "scamera");
                changed = true;
            }
            e.putInt(SCAM_DEFAULTS_REV, 1);
            markOnly = true;
        }
        // Night noise protection (AGC Noise-based Gain Clamp): legacy ISO threshold (default 100) moves to AGC noise threshold 0.05.
        // Stored values >= 1.0 are legacy ISO values and move to "0.05".
        if (!values.containsKey(NIGHT_THRESH_REV)) {
            Object night = values.get(ScamHybridKeys.PREFIX + "ark_night_thresh");
            if (night != null) {
                try {
                    float f = Float.parseFloat(String.valueOf(night).trim());
                    if (f >= 1.0f) {
                        ModuleProfiles.put(e, ScamHybridKeys.PREFIX + "ark_night_thresh", night instanceof String ? "0.05" : (Object) 0.05f);
                        changed = true;
                    }
                } catch (NumberFormatException ignored) {}
            }
            e.putInt(NIGHT_THRESH_REV, 1);
            markOnly = true;
        }
        // P135 (owner, 2026-10-15: strong grain in poor light): the stored former defaults of the night denoise tier (ArkCam's
        // SabreLow {1, 3, 0, 0, 0} with revert {9, 9, ...}) and of the revert limit (9) move to the P135 tier (the stock
        // strengths, half the fine grain put back) and the limit 1; a value chosen by hand stays. Own marker.
        if (!values.containsKey(LOW_LIGHT_DN_REV)) {
            final String p = ScamHybridKeys.PREFIX;
            if (sameList(values.get(p + "dn_luma_t1_strength"), 1f, 3f, 0f, 0f, 0f)) {
                e.putString(p + "dn_luma_t1_strength", "1.2,1.1,1.2,1.1,1");
                changed = true;
            }
            if (sameList(values.get(p + "dn_luma_t1_revert"), 9f, 9f, 0.08f, 0.12f, 0f)) {
                e.putString(p + "dn_luma_t1_revert", "0.6,0.5,0.08,0.12,0");
                changed = true;
            }
            // 9 = ArkCam's (revision 3 above may have just written it over the older default 2)
            Object max = values.get(p + "dn_revert_max");
            if (max != null && (isNumber(max, 9f) || isNumber(max, 2f) && revision < 3)) {
                ModuleProfiles.put(e, p + "dn_revert_max", max instanceof String ? "1" : (Object) 1f);
                changed = true;
            }
            e.putInt(LOW_LIGHT_DN_REV, 1);
            markOnly = true;
        }
        // P191 (owner, 2026-10-17: «Сетка не исчезла»; «Займись исправлением сеточных/мозаичных артефактов»): the daylight native
        // mosaic kernel 0.7 -> 1.0. On the owner's handheld bursts (X200 Ultra, 2x ISZ ultra-wide and FULLRES 50 MP) 1.0 halved the
        // diagonal Quad lattice (0.055 -> 0.025 %, 0.104 -> 0.043 % of the level) at 6 % of the 2-4 px band amplitude, noise in it
        // -28 %; the sharpening amplified that lattice. A stored 0.7 is the former XML default and moves; any other value stays.
        if (!values.containsKey(MOSAIC_KERNEL_REV)) {
            Object scale = values.get(ScamHybridKeys.PREFIX + "mosaic_kernel_scale");
            if (scale != null && isNumber(scale, 0.7f)) {
                ModuleProfiles.put(e, ScamHybridKeys.PREFIX + "mosaic_kernel_scale", scale instanceof String ? PreferenceNumber.format(1f, true) : (Object) 1f);
                changed = true;
            }
            e.putInt(MOSAIC_KERNEL_REV, 1);
            markOnly = true;
        }
        // P203: «Кадров в снимке 50 Мп» 12 instead of 0 (auto): a stored former default moves once
        if (!values.containsKey(FULLRES50_FRAMES_REV)) {
            Object frames = values.get(ScamHybridKeys.PREFIX + "fullres50_frames");
            if (frames != null && isNumber(frames, 0f)) {
                ModuleProfiles.put(e, ScamHybridKeys.PREFIX + "fullres50_frames", frames instanceof String ? PreferenceNumber.format(12f, false) : (Object) 12f);
                changed = true;
            }
            e.putInt(FULLRES50_FRAMES_REV, 1);
            markOnly = true;
        }
        // P164: the multiscale finish denoise's stored engine value has its own name; any value but "nlm" is that engine
        final Object engine = values.get(ScamHybridKeys.PREFIX + "dn_engine");
        if (engine instanceof String && !"nlm".equals(engine) && !"multiscale".equals(engine)) {
            e.putString(ScamHybridKeys.PREFIX + "dn_engine", "multiscale");
            changed = true;
        }
        if (changed || markOnly) e.commit();
        return changed;
    }

    /** A stored "a,b,c,d,e" list equal to {@code expected} (the former default written by setDefaultValues). */
    private static boolean sameList(Object stored, float... expected) {
        if (stored == null) return false;
        final String[] parts = stored.toString().split(",");
        if (parts.length != expected.length) return false;
        for (int i = 0; i < parts.length; i++) if (!isNumber(parts[i], expected[i])) return false;
        return true;
    }

    /** Marker of the one-time move of the former night denoise defaults (ArkCam SabreLow, revert limit 9), P135. */
    static final String LOW_LIGHT_DN_REV = "pref_scam_hybrid_low_light_dn_rev";
    /** P191: the native mosaic kernel 0.7 -> 1.0 moved once. */
    static final String MOSAIC_KERNEL_REV = "pref_scam_hybrid_mosaic_kernel_rev";
    /** P203: marker of the one-time move of a stored «Кадров в снимке 50 Мп» 0 (the former default) to 12. */
    static final String FULLRES50_FRAMES_REV = "pref_scam_hybrid_fullres50_frames_rev";

    private static final String DEFAULTS_REV = "pref_scam_hybrid_defaults_rev";
    /** Marker of the one-time move of a stored former default of pref_scam_hybrid_zsl_frames (20) to 30. */
    static final String ZSL_FRAMES_REV = "pref_scam_hybrid_zsl_frames_rev";
    /** Marker of the one-time move of the P68 former defaults (N frames, Sabre 6.1, Mochi, Bento, Shasta frames). */
    static final String P68_DEFAULTS_REV = "pref_scam_hybrid_p68_defaults_rev";
    /** Marker of the one-time move of a stored former default of pref_scam_hybrid_mosaic_frames (24) to 30. */
    static final String MOSAIC_FRAMES_REV = "pref_scam_hybrid_mosaic_frames_rev";
    /** Marker of the one-time move of the two-decimal slider defaults "0.00" (Bento auto threshold) and "1.41" (LUT sigma). */
    static final String PRECISION_REV = "pref_scam_hybrid_precision_rev";
    /** Marker of the one-time move of the former SCAM HDR defaults (diagnostics on, stock planner) to off / SCAMERA. */
    static final String SCAM_DEFAULTS_REV = "pref_scamhdr_defaults_rev";
    /** Marker of the one-time move of the former night protection threshold (default 100) to AGC noise clamp (0.05). */
    static final String NIGHT_THRESH_REV = "pref_scam_hybrid_night_thresh_rev";

    private static boolean isNumber(Object v, float expected) {
        try {
            float f = v instanceof Number ? ((Number) v).floatValue() : Float.parseFloat(v.toString().trim());
            return Math.abs(f - expected) < 1e-4f;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** A SCAM HDR key whose value the hybrid route used before the separation (see {@link #migrateScamHybrid(SharedPreferences, boolean)}). */
    static boolean sharedWithHybrid(String key) {
        if (key.startsWith(LEGACY_HYBRID) || key.equals("pref_scamhdr_hybrid")) return false;
        if (key.startsWith("pref_scamhdr_")) {
            String k = key.substring("pref_scamhdr_".length());
            // The soft tone is gone for the hybrid; so are its sharpening knobs (P118: only the ARK sharpening stays).
            if (k.equals("soft_tone")) return false;
            if (SHARED_SCAM_KEYS.contains(k)) return true;
            for (String prefix : SHARED_SCAM_PREFIXES) if (k.startsWith(prefix)) return true;
            return false;
        }
        if (key.startsWith("pref_scamold_")) {
            String k = key.substring("pref_scamold_".length());
            return k.startsWith("ae_") || k.equals("fast_capture");
        }
        if (key.startsWith("pref_agx_scam_")) return true;
        if (key.startsWith("pref_scamroute_")) return !UNSHARED_HDR_KEYS.contains(key.substring("pref_scamroute_".length()));
        return false;
    }

    /** The hybrid merged the shots before the separation: SCAM HDR on and the engine "hybrid", or "auto" away from the SCAM SoC. */
    private static boolean legacyHybridShots(Map<String, ?> values) {
        if (!PreferenceNumber.bool(values.get(ScamHybridKeys.LEGACY_HDR), false)
                || !PreferenceNumber.bool(values.get(ScamHybridKeys.LEGACY_SCAM), false)) return false;
        Object engine = values.get("pref_scamhdr_engine");
        if (engine == null && values.containsKey("pref_scamhdr_hybrid"))
            return PreferenceNumber.bool(values.get("pref_scamhdr_hybrid"), true);
        String v = engine == null ? "auto" : engine.toString();
        return "hybrid".equals(v) || !"scam".equals(v) && !ScamHybridKeys.scamNetSoc();
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
