package com.particlesdevs.photoncamera.settings;

/** Scalar validation shared by text inputs and capture getters (including imported configs). */
public final class SettingsNumericRules {
    private static final String HYBRID = "pref_lmc_hybrid_";
    private SettingsNumericRules() {}
    public static double[] bounds(String key) {
        switch (key) {
            case "pref_vivo_nice_norm": return new double[]{0.55,2.2,0};
            case "pref_vivo_nice_noise_photon": case "pref_vivo_nice_noise_readout":
            case "pref_vivo_nice_noise_scale": return new double[]{0.25,4,0};
            case "pref_vivo_nice_long_boost_ev": return new double[]{0,2,0};
            case "pref_vivo_nice_zsl_frames": return new double[]{4,50,1};
            case "pref_hexquad_frames": return new double[]{6,50,1};
            case "pref_quad_frames": return new double[]{4,50,1};
            case "pref_vivo_nice_luma": case "pref_vivo_nice_chroma": return new double[]{0,2,0};
            case "pref_vivo_nice_merge": return new double[]{0,100,1};
            case "pref_vivo_nice_luma_radius": return new double[]{1,4,1};
            case "pref_vivo_nice_fusion_strength": return new double[]{0,1,0};
            case "pref_vivo_nice_planner_l_ev": return new double[]{0,3,0};
            case "pref_vivo_nice_planner_s_ev": return new double[]{1,5,0};
            case "pref_vivo_nice_planner_es_ev": return new double[]{2,9,0};
            case "pref_lmc_hybrid_shasta_frames": return new double[]{1,5,1};
            case "pref_lmc_hybrid_shasta_ev": return new double[]{1,4,0};
            case "pref_lmc_hybrid_shasta_sharpness": return new double[]{0.3,1,0};
            case "pref_lmc_hybrid_shasta_max_ratio": return new double[]{2,100,0};
            case "pref_lmc_hybrid_bento": return new double[]{0,2,1};
            case "pref_lmc_hybrid_bento_factor": return new double[]{2,16,0};
            case "pref_lmc_hybrid_bento_trigger": return new double[]{0,0.1,0};
            case "pref_lmc_hybrid_bento_weight": return new double[]{0.1,8,0};
            case "pref_lmc_hybrid_bento_sigma": return new double[]{0.5,2,0};
            case "pref_lmc_hybrid_bento_frames": return new double[]{1,2,1};
            case "pref_lmc_hybrid_bento_chroma_sigma": return new double[]{0,4,0};
            case "pref_lmc_hybrid_cdm": return new double[]{0.01,2,0};
            case "pref_lmc_hybrid_boost": return new double[]{0,1,1};
            case "pref_lmc_hybrid_kernel": return new double[]{0.5,2,0};
            case "pref_lmc_hybrid_weight_cap": return new double[]{1,50,0};
            case "pref_lmc_hybrid_fwe": return new double[]{0,1,0};
            case "pref_lmc_hybrid_dilate": return new double[]{0.5,8,0};
            case "pref_lmc_hybrid_dilate_floor": return new double[]{0,0.5,0};
            case "pref_lmc_hybrid_filter_variance": return new double[]{0.1,1,0};
            case "pref_lmc_hybrid_widen_below": return new double[]{0,16,0};
            case "pref_lmc_hybrid_tensor_noise": return new double[]{0,4,0};
            case "pref_lmc_hybrid_snr_scale": return new double[]{0.05,4,0};
            case "pref_lmc_hybrid_boost_value": return new double[]{1,10,0};
            case "pref_lmc_hybrid_boost_threshold": return new double[]{5,100,0};
            case "pref_lmc_hybrid_lut_sigma": return new double[]{1,2,0};
            case "pref_lmc_hybrid_post_luma": return new double[]{0,2,0};
            case "pref_lmc_hybrid_post_chroma": return new double[]{0,2,0};
            case "pref_lmc_hybrid_despeckle": return new double[]{0,1,1};
            case "pref_lmc_hybrid_bento_denoise_max": return new double[]{1,12,0};
            // LMC hybrid noise reduction (LmcDenoise, engine "gcam")
            case "pref_lmc_hybrid_dn_snr": return new double[]{0,200,0};
            case "pref_lmc_hybrid_dn_snr_scale": return new double[]{0.25,8,0};
            case "pref_lmc_hybrid_dn_model_shot": return new double[]{0.25,4,0};
            case "pref_lmc_hybrid_dn_model_read": return new double[]{0.25,16,0};
            case "pref_lmc_hybrid_dn_luma_mult": case "pref_lmc_hybrid_dn_sabre_luma_mult": case "pref_lmc_hybrid_dn_luma_gid14_mult":
            case "pref_lmc_hybrid_dn_chroma_mult": case "pref_lmc_hybrid_dn_revert_mult": return new double[]{0,4,0};
            case "pref_lmc_hybrid_dn_revert_max": return new double[]{0,9,0};
            case "pref_lmc_hybrid_dn_coarse_stock": return new double[]{0,1,0};
            case "pref_lmc_hybrid_dn_chroma_floor": return new double[]{0,5,0};
            case "pref_lmc_hybrid_dn_chroma_2x_keep": return new double[]{0,1,0};
            case "pref_lmc_hybrid_bento_fusion": return new double[]{0,1.5,0};
            case "pref_lmc_hybrid_zsl_frames": return new double[]{4,44,1};
            case "pref_lmc_hybrid_sharp_strength": return new double[]{0,2,0};
            case "pref_lmc_hybrid_sharp_amount": return new double[]{0,2,0};
            case "pref_lmc_hybrid_fusion_strength": return new double[]{0,1,0};
            case "pref_lmc_hybrid_fusion_dark_ev": return new double[]{0,4,0};
            case "pref_lmc_hybrid_fusion_bright_ev": return new double[]{0,2,0};
            case "pref_lmc_hybrid_fusion_detail": return new double[]{0.5,2,0};
            case "pref_lmc_hybrid_fusion_sigma": return new double[]{0.1,0.4,0};
            case "pref_lmc_hybrid_texture": return new double[]{0,2,0};
            case "pref_lmc_hybrid_ae_mid": return new double[]{0.005,0.2,0};
            case "pref_lmc_hybrid_ae_high": return new double[]{0.02,0.5,0};
            case "pref_lmc_hybrid_ae_gain_max": return new double[]{1,256,0};
            case "pref_lmc_hybrid_noise_photon": case "pref_lmc_hybrid_noise_readout": return new double[]{0.25,4,0};
            case "pref_lmc_hybrid_agx_local_strength": return new double[]{0,100,0};
            case "pref_lmc_hybrid_agx_knee_start": return new double[]{0,5,0};
            // Per-channel highlight recovery of the hybrid (VivoNiceRgb clamps the strength to 100 %).
            case "pref_lmc_hybrid_highlight_recovery": return new double[]{0,100,0};
            case "pref_lmc_hybrid_highlight_chroma": return new double[]{0.05,1,0};
            // Worker round 5 (hybrid_tuning.txt): Sabre 6.1 kernel 0/1/2 (auto), its auto threshold, outlier sites, Bento checks.
            case "pref_lmc_hybrid_sabre61": return new double[]{0,2,1};
            case "pref_lmc_hybrid_local_align": return new double[]{0,2,1};
            case "pref_lmc_hybrid_s61_min_motion": return new double[]{0,20,0};
            case "pref_lmc_hybrid_s61_max_key": case "pref_lmc_hybrid_hot_max_key": return new double[]{5,100,0};
            case "pref_lmc_hybrid_hot_sigma": case "pref_lmc_hybrid_hot_base_sigma": return new double[]{0,12,0};
            // LMC hybrid ARK tone (ArkCam 1.23 photo tone; defaults ArkCam 2.85 X8U, tone_port.md section 3). The config's
            // pref_bento_meta_* carry only title/default/type, so the ranges follow research/hybrid5/PLAN.md section 4.1.
            case "pref_lmc_hybrid_ark_input_ev": return new double[]{-6,2,0};
            case "pref_lmc_hybrid_ark_ae_target": return new double[]{0.02,0.5,0};
            case "pref_lmc_hybrid_ark_ae_max_boost": return new double[]{1,16,0};
            case "pref_lmc_hybrid_ark_ae_min_limit": return new double[]{0.1,1,0};
            case "pref_lmc_hybrid_ark_hl_overflow": return new double[]{0.5,8,0};
            case "pref_lmc_hybrid_ark_hl_blend": return new double[]{0,1,0};
            case "pref_lmc_hybrid_ark_night_thresh": return new double[]{50,12800,0};
            case "pref_lmc_hybrid_ark_night_dim": return new double[]{0.5,1.5,0};
            case "pref_lmc_hybrid_ark_face_priority": return new double[]{0,1,0};
            case "pref_lmc_hybrid_ark_metering": return new double[]{0,2,1};
            case "pref_lmc_hybrid_ark_bright_thresh": return new double[]{0.2,2,0};
            case "pref_lmc_hybrid_ark_dark_thresh": return new double[]{1,50,0};
            case "pref_lmc_hybrid_ark_dark_pixel_thresh": return new double[]{1,50,0};
            case "pref_lmc_hybrid_ark_hl_boost": return new double[]{0,4,0};
            case "pref_lmc_hybrid_ark_contrast_boost": return new double[]{0,2,0};
            case "pref_lmc_hybrid_ark_shadow_str": case "pref_lmc_hybrid_ark_highlight_str": return new double[]{0,4,0};
            case "pref_lmc_hybrid_ark_weight_center": return new double[]{0.2,0.9,0};
            case "pref_lmc_hybrid_ark_blend_smoothness": return new double[]{0.05,1,0};
            case "pref_lmc_hybrid_ark_weight_hl": case "pref_lmc_hybrid_ark_weight_mid":
            case "pref_lmc_hybrid_ark_weight_ext_hl": case "pref_lmc_hybrid_ark_weight_shadow": return new double[]{0,2,0};
            case "pref_lmc_hybrid_ark_gf_radius": return new double[]{4,128,1};
            case "pref_lmc_hybrid_ark_gf_eps_e3": return new double[]{0.001,100,0};
            case "pref_lmc_hybrid_ark_aces_toe": return new double[]{0.005,0.2,0};
            case "pref_lmc_hybrid_ark_bracket_denoise": return new double[]{0,1,0};
            case "pref_lmc_hybrid_ark_gamma": return new double[]{1.6,2.8,0};
            case "pref_lmc_hybrid_ark_film_toe": return new double[]{0,0.5,0};
            case "pref_lmc_hybrid_ark_macro_contrast": return new double[]{0.8,1.5,0};
            case "pref_lmc_hybrid_ark_vibrance": case "pref_lmc_hybrid_ark_vibrance_sky":
            case "pref_lmc_hybrid_ark_vibrance_green": return new double[]{-1,1,0};
            case "pref_lmc_hybrid_ark_chroma_denoise": return new double[]{0,1,0};
            case "pref_lmc_hybrid_ark_clarity": return new double[]{-1,2,0};
            case "pref_lmc_hybrid_ark_flat_protect": return new double[]{0,10,0};
            case "pref_lmc_hybrid_ark_detail_gain": return new double[]{0,3,0};
            case "pref_lmc_hybrid_ark_delta_chroma": return new double[]{0,1,0};
            case "pref_lmc_hybrid_ark_sharp_guard": return new double[]{0,1,0};
            case "pref_lmc_hybrid_ark_sharp_gain": return new double[]{0,3,0};
            case "pref_lmc_hybrid_ark_sharp_domain": return new double[]{0,1,1};
            case "pref_lmc_hybrid_ark_sharp_scale": return new double[]{0,4,0};
            case "pref_lmc_hybrid_ark_sharp_usm_radius": return new double[]{0,5,0};
            case "pref_lmc_hybrid_ark_sharp_usm_amount": return new double[]{0,5,0};
            case "pref_lmc_hybrid_ark_sharp_usm_thresh": return new double[]{0,255,1};
            case "pref_lmc_hybrid_ark_sharp_bilateral_radius": return new double[]{0,3,0};
            case "pref_lmc_hybrid_ark_sharp_bilateral_amount": return new double[]{0,4,0};
            case "pref_lmc_hybrid_ark_sharp_bilateral_color": return new double[]{1,100,1};
            case "pref_lmc_hybrid_ark_sharp_halo_control": return new double[]{0,100,1};
            case "pref_lmc_hybrid_ark_sharp_protect_shadows": return new double[]{0,100,1};
            case "pref_lmc_hybrid_ark_sharp_protect_highlights": return new double[]{0,100,1};
            case "pref_lmc_hybrid_ark_sharp_gf_radius": return new double[]{0,32,1};
            case "pref_lmc_hybrid_ark_sharp_gf_eps_e3": return new double[]{0.1,100,0};
            case "pref_lmc_hybrid_ark_sharp_gf_lc": return new double[]{-1,2,0};
            case "pref_lmc_hybrid_ark_sharp_film_grain": return new double[]{0,50,0};
            case "pref_lmc_hybrid_ark_sharp_rl1_kernel": return new double[]{0,2,1};
            case "pref_lmc_hybrid_ark_sharp_rl1_rad": return new double[]{0,4,0};
            case "pref_lmc_hybrid_ark_sharp_rl1_amount": return new double[]{0,4,0};
            case "pref_lmc_hybrid_ark_sharp_rl1_iters": return new double[]{0,10,1};
            case "pref_lmc_hybrid_ark_sharp_rl2_kernel": return new double[]{0,2,1};
            case "pref_lmc_hybrid_ark_sharp_rl2_rad": return new double[]{0,4,0};
            case "pref_lmc_hybrid_ark_sharp_rl2_amount": return new double[]{0,4,0};
            case "pref_lmc_hybrid_ark_sharp_rl2_iters": return new double[]{0,10,1};
            case "pref_lmc_hybrid_ark_sharp_rl3_kernel": return new double[]{0,2,1};
            case "pref_lmc_hybrid_ark_sharp_rl3_rad": return new double[]{0,4,0};
            case "pref_lmc_hybrid_ark_sharp_rl3_amount": return new double[]{0,4,0};
            case "pref_lmc_hybrid_ark_sharp_rl3_iters": return new double[]{0,10,1};
            case "pref_lmc_hybrid_ark_sharp_rl_halo_control": return new double[]{0,100,1};
            case "pref_lmc_hybrid_ark_sharp_rl_halo_margin": return new double[]{0,1,0};
            case "pref_lmc_hybrid_ark_sharp_rl_halo_macro": return new double[]{0.05,1,0};
            case "pref_lmc_hybrid_ark_agx_look": return new double[]{0,4,1};
            case "pref_lmc_hybrid_ark_agx_slope": return new double[]{1,5,0};
            case "pref_lmc_hybrid_ark_agx_sp": case "pref_lmc_hybrid_ark_agx_tp": return new double[]{0.5,3,0};
            case "pref_lmc_hybrid_ark_agx_min_ev": return new double[]{-14,-4,0};
            case "pref_lmc_hybrid_ark_agx_max_ev": return new double[]{1,8,0};
            case "pref_lmc_hybrid_ark_agx_ev": return new double[]{-2,2,0};
            case "pref_lmc_hybrid_ark_agx_sat": return new double[]{0,2,0};
            case "pref_lmc_hybrid_ark_ccm_sat": return new double[]{0,1.5,0};
            case "pref_lmc_hybrid_highlight_defringe": return new double[]{0,1,0};
            case "pref_lmc_hybrid_highlight_band": return new double[]{0,1,0};
            case "pref_vivo_hdr_highlight_pct": return new double[]{90,99.99,0};
            case "pref_vivo_hdr_adaptive_white": return new double[]{0,1,1};
            case "pref_vivo_hdr_highlight_neutral": return new double[]{0,0.95,0};
            case "pref_vivo_hdr_white_neutral": return new double[]{0.3,1,0};
            case "pref_vivo_nice_fusion_dark_ev": return new double[]{0,4,0};
            case "pref_vivo_nice_fusion_bright_ev": return new double[]{0,2,0};
            case "pref_vivo_nice_texture": return new double[]{0,2,1};
            case "pref_vivo_nice_soft_tone": return new double[]{0,1,1};
            case "pref_vivo_nice_tone_key": return new double[]{0.08,0.30,0};
            case "pref_vivo_nice_sharp_amount": return new double[]{0,2,0};
            case "pref_vivo_nice_fusion_detail": return new double[]{0.5,2,0};
            case "pref_vivo_nice_fusion_sigma": return new double[]{0.1,0.4,0};
            case "pref_vivo_nice_chroma_radius": return new double[]{1,12,1};
            case "pref_vivo_nice_post_chroma": case "pref_vivo_nice_post_luma": return new double[]{0,2,0};
            case "pref_vivo_nice_warm_retention": return new double[]{-1,100,0};
            case "pref_watermark_size": return new double[]{3,20,0};
            case "pref_watermark_opacity": return new double[]{10,100,1};
            case "pref_vivo_nice_gcam_tone": return new double[]{-1,100,-1};
            case "pref_vivo_nice_luma_iso1": case "pref_vivo_nice_chroma_iso1": return new double[]{0,2,0};
            case "pref_vivo_nice_luma_iso2": case "pref_vivo_nice_chroma_iso2": return new double[]{0,2,0};
            case "pref_vivo_nice_luma_iso3": case "pref_vivo_nice_chroma_iso3": return new double[]{0,2,0};
            case "pref_vivo_nice_luma_iso4": case "pref_vivo_nice_chroma_iso4": return new double[]{0,2,0};
            case "pref_vivo_nice_luma_iso5": case "pref_vivo_nice_chroma_iso5": return new double[]{0,2,0};
            case "pref_vivo_hdr_exposure": return new double[]{-2,2,0};
            case "pref_vivo_hdr_contrast": case "pref_vivo_hdr_gamma": return new double[]{0.5,2,0};
            case "pref_vivo_hdr_black": return new double[]{0,0.1,0};
            case "pref_vivo_hdr_white": return new double[]{0.7,1,0};
        }
        // LMC hybrid noise reduction: the SNR keys of the luma (t1..t5) and chroma (t1..t4) tier tables.
        if (key.matches("pref_lmc_hybrid_dn_(luma_t[1-5]|chroma_t[1-4])_snr")) return new double[]{0.1,500,0};
        // The LMC hybrid's copies of SCAM HDR knobs (PreferenceKeys.hybridCopyKey) keep the bounds of the original key.
        if (key.startsWith(HYBRID)) {
            String k = key.substring(HYBRID.length());
            if (k.startsWith("hdr_")) return bounds("pref_vivo_hdr_" + k.substring(4));
            if (k.startsWith("agx_") || k.startsWith("ae_") || k.equals("enabled")) return null;
            return bounds("pref_vivo_nice_" + k);
        }
        if (key.startsWith("pref_vivo_hdr_") && !key.equals("pref_vivo_hdr_enabled")) return new double[]{0,2,0};
        switch (key) {
            case "pref_aces_gamma_curve_key": case "pref_aces_tone_curve_key": case "pref_aces_output_key": return new double[]{0,20,1};
            case "pref_antibanding_hz_key": return new double[]{0,1000,1};
            case "pref_noise_iso_min_key": case "pref_noise_iso_max_key": case "pref_noise_iso_manual_key": return new double[]{0,1000000,1};
            case "pref_max_hdr_ratio_key": case "pref_merge_max_exposure_ratio_key": return new double[]{1,1024,0};
            case "pref_long_frame_shutter_cap_key": return new double[]{0,120,0};
            case "pref_merge_clip_level_key": return new double[]{0.01,1,0};
            case "pref_noise_model_coefficient_key": return new double[]{0.001,100,0};
            case "pref_merge_robustness_key": case "pref_merge_floor_sigmas_key": case "pref_merge_tiling_tolerance_key": return new double[]{0,100,0};
            case "pref_hdrplus_luma_gain_slope_key": case "pref_hdrplus_chroma_gain_slope_key": return new double[]{0,10,0};
            case "pref_hdrplus_snr_target_key": return new double[]{0.001,10000,0};
            case "pref_highlight_recovery_min_ok_key": return new double[]{1,4,1};
            case "pref_highlight_protection_knee_key": return new double[]{0,0.99,0};
            case "pref_highlight_protection_strength_key": return new double[]{0,1,0};
            case "pref_mfsr_frames_key": return new double[]{3,40,1};
            case "pref_mfsr_red_ca_key": case "pref_mfsr_blue_ca_key": return new double[]{0.99,1.01,0};
            default: return key.startsWith("pref_snr_") ? new double[]{0,100,0} : null;
        }
    }
    /**
     * Bounds of a number-list setting ("a,b,c,..."): {count, min, max, integer}; null = any length, values unbounded.
     * New list keys of the LMC hybrid (PreferenceKeys.hybridList) register here.
     */
    public static double[] listBounds(String key) {
        switch (key) {
            // LMC hybrid noise reduction tables (five bands b0..b4)
            case "pref_lmc_hybrid_dn_luma_t1_strength": case "pref_lmc_hybrid_dn_luma_t2_strength": case "pref_lmc_hybrid_dn_luma_t3_strength":
            case "pref_lmc_hybrid_dn_luma_t4_strength": case "pref_lmc_hybrid_dn_luma_t5_strength":
            case "pref_lmc_hybrid_dn_chroma_t1_strength": case "pref_lmc_hybrid_dn_chroma_t2_strength":
            case "pref_lmc_hybrid_dn_chroma_t3_strength": case "pref_lmc_hybrid_dn_chroma_t4_strength": return new double[]{5,0,10,0};
            case "pref_lmc_hybrid_dn_luma_t1_revert": case "pref_lmc_hybrid_dn_luma_t2_revert": case "pref_lmc_hybrid_dn_luma_t3_revert":
            case "pref_lmc_hybrid_dn_luma_t4_revert": case "pref_lmc_hybrid_dn_luma_t5_revert": return new double[]{5,0,9,0};
            case "pref_lmc_hybrid_dn_luma_t1_outlier": case "pref_lmc_hybrid_dn_luma_t2_outlier": case "pref_lmc_hybrid_dn_luma_t3_outlier":
            case "pref_lmc_hybrid_dn_luma_t4_outlier": case "pref_lmc_hybrid_dn_luma_t5_outlier": return new double[]{5,0,1,0};
            case "pref_lmc_hybrid_dn_chroma_t1_outlier": case "pref_lmc_hybrid_dn_chroma_t2_outlier":
            case "pref_lmc_hybrid_dn_chroma_t3_outlier": case "pref_lmc_hybrid_dn_chroma_t4_outlier": return new double[]{5,0,16,1};
            default: return null;
        }
    }
    /** Parses "a,b,c" (also ';' or spaces); a wrong length or a non-finite value gives the fallback, values are clamped. */
    public static float[] listValue(String key, Object input, float[] fallback) {
        if (input == null) return fallback;
        String text = input.toString().trim();
        if (text.isEmpty()) return fallback;
        String[] parts = text.split("[;,\\s]+");
        double[] b = listBounds(key);
        int count = b != null ? (int) b[0] : fallback != null ? fallback.length : parts.length;
        if (parts.length != count) return fallback;
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            double v = PreferenceNumber.read(parts[i], Double.NaN);
            if (!Double.isFinite(v)) return fallback;
            if (b != null) { v = Math.max(b[1], Math.min(b[2], v)); if (b[3] == 1) v = Math.rint(v); }
            out[i] = (float) v;
        }
        return out;
    }
    public static String error(String key, Object input) {
        double[] b=bounds(key); if(b==null) return null;
        double v=PreferenceNumber.read(input,Double.NaN);
        if (!Double.isFinite(v) || v<b[0] || v>b[1] || b[2]==1 && v!=Math.rint(v))
            return (b[2]==1 ? "Нужно целое число" : "Нужно число")+" от "+b[0]+" до "+b[1]+".";
        return null;
    }
    public static double value(String key,Object input,double fallback) {
        double v=PreferenceNumber.read(input,fallback); double[] b=bounds(key);
        if (b!=null) { v=Math.max(b[0],Math.min(b[1],v)); if(b[2]==1) v=Math.rint(v); }
        return v;
    }
    public static String normalized(String key,String input,String fallback) {
        double d=PreferenceNumber.read(fallback,Double.NaN);
        if(!Double.isFinite(d)) return input;
        double v=value(key,input,d);
        return v == Math.rint(v) ? Long.toString(Math.round(v)) : Double.toString(v);
    }
}
