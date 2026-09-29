package com.particlesdevs.photoncamera.settings;

/** Scalar validation shared by text inputs and capture getters (including imported configs). */
public final class SettingsNumericRules {
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
            case "pref_vivo_hdr_highlight_pct": return new double[]{90,99.99,0};
            case "pref_vivo_hdr_adaptive_white": return new double[]{0,1,1};
            case "pref_vivo_hdr_highlight_neutral": return new double[]{0,0.95,0};
            case "pref_vivo_hdr_white_neutral": return new double[]{0.3,1,0};
            case "pref_vivo_nice_fusion_dark_ev": return new double[]{0,4,0};
            case "pref_vivo_nice_fusion_bright_ev": return new double[]{0,2,0};
            case "pref_vivo_nice_fusion_detail": return new double[]{0.5,2,0};
            case "pref_vivo_nice_fusion_sigma": return new double[]{0.1,0.4,0};
            case "pref_vivo_nice_chroma_radius": return new double[]{1,12,1};
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
