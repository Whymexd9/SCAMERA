package com.particlesdevs.photoncamera.settings;

import java.util.Map;

/** Conditions mirror the capture/post-pipeline branches. Pages stay open to explain inactive rows. */
public final class SettingsAvailability {
    private final Map<String, ?> values;
    private final boolean calibratedSensor;
    public SettingsAvailability(Map<String, ?> values) { this(values,false); }
    public SettingsAvailability(Map<String, ?> values, boolean calibratedSensor) { this.values = values; this.calibratedSensor = calibratedSensor; }
    private String text(String key, String fallback) {
        Object value = values.get(key); return value == null ? fallback : value.toString();
    }
    private boolean on(String key, boolean fallback) { return PreferenceNumber.bool(values.get(key), fallback); }
    private boolean any(String key, String... alternatives) {
        for (String value : alternatives) if (key.equals(value)) return true;
        return false;
    }
    public String reason(String key) {
        boolean remosaic = on("pref_remosaic_enabled_key", false);
        String backend = text("pref_remosaic_backend_key", "scamera");
        boolean hex = remosaic && backend.equals("hp9_hexquad");
        boolean post = !hex || on("hexquad_post_denoise", false);
        boolean original = text("pref_rt_denoise_backend", "legacy").equals("rt512");
        String tone = text("pref_tunable_postpipeline_tonepipeline", "fusion");
        boolean initial = tone.equals("fusion") || tone.equals("curve");
        boolean aces = on("pref_aces_enabled_key", false);
        boolean hdr = text("pref_camera_mode_key", "2").equals("4")
                ? text("pref_night_merge_algorithm_key", "legacy").equals("hdrplus")
                : text("pref_zsl_merge_algorithm_key", "legacy").equals("hdrplus");
        // Both per-mode selectors remain editable even while a different mode is open.
        if (key.startsWith("hexquad_") && !key.endsWith("screen")) {
            if (!hex) return "Доступно: включите ремозаик и выберите HP9 HexQuad.";
            if (key.equals("hexquad_full_resolution") && !text("hexquad_model", "2").equals("2")) return "Полный выход доступен только для модели x2.";
            boolean auto = on("hexquad_auto_iso", false);
            if (any(key,"hexquad_luma","hexquad_chroma") && auto) return "Сила задаётся ниже по ISO. Для ручной настройки выключите автоматику.";
            if (key.startsWith("hexquad_iso_") && !auto) return "Включите «Люма и хрома по ISO».";
        }
        if ((key.startsWith("pref_noise_iso_") || key.equals("pref_noise_disable_digital_gain_key"))
                && text("pref_noise_model_profile_key","auto").equals("auto") && !calibratedSensor)
            return "Для изменения ISO модели выберите калиброванный профиль шума. Camera2 Auto использует измеренный шум текущего кадра.";
        if (key.startsWith("scamera_mosaic_sr_") && !key.equals("scamera_mosaic_sr_enabled") && !on("scamera_mosaic_sr_enabled",false))
            return "Включите увеличение RAW-мозаики.";
        if (key.equals("scamera_quad_bayer_enabled") && remosaic) return "При ремозаике используется выбранный выше размер блока и исходный порядок CFA.";
        if (key.equals("pref_tunable_opendrt_greyboost") || key.equals("pref_tunable_opendrt_hdrpurity")) {
            if (PreferenceNumber.read(values.get("pref_tunable_opendrt_displaypeak"),100)==100)
                return "Этот параметр начинает влиять при Display Peak выше 100 нит.";
        }
        if (key.startsWith("pref_remosaic_") && !key.equals("pref_remosaic_enabled_key")) {
            if (!remosaic) return "Включите ремозаик.";
            if (any(key,"pref_remosaic_profile_key","pref_remosaic_steered_key","pref_remosaic_clamp_key","pref_remosaic_flatfield_key") && !backend.equals("scamera")) return "Эта настройка относится к алгоритму SCAMERA.";
        }
        if (key.equals("pref_tetra_response_key") && (!remosaic || backend.equals("scamera"))) return "Коррекция для Tetra Detail / HP9 HexQuad.";
        if (any(key,"scamera_quad_bayer_mode","scamera_quad_dng_metadata") && !on("scamera_quad_bayer_enabled", false)) return "Включите обработку Quad Bayer.";
        if (key.equals("pref_tunable_postpipeline_demosaicingmethod") && !remosaic && any(text("pref_cfa_key","-1"),"-2","4")) return "Для Quad и монохромного сенсора используется специальная демозаика.";
        if (key.startsWith("rt512_") || key.startsWith("pref_rt_nr_") || key.startsWith("pref_ai_denoise_") || key.startsWith("pref_tunable_esd3d2_") || key.equals("pref_rt_denoise_backend")) {
            if (!post) return "HexQuad: включите дополнительный шумодав после нейроремозаика.";
            if (key.startsWith("rt512_")) {
                if (!original) return "Выберите RawTherapee 5.12 в алгоритме шумоподавления.";
                if (any(key,"rt512_chroma","rt512_red","rt512_blue") && !text("rt512_auto","0").equals("0")) return "Цветовой шум оценивается автоматически.";
                if (any(key,"rt512_kernel","rt512_passes") && text("rt512_median","0").equals("0")) return "Выберите каналы медианного фильтра.";
                if (key.equals("rt512_exposure") && !text("rt512_gain","1").equals("1")) return "Включите учёт экспокоррекции в модели шума.";
                if (key.equals("rt512_luma")) {
                    try { double[] curve = RawTherapeeSettings.curve(text("rt512_lcurve","0"));
                        if (curve != null) for (int i=2;i<curve.length;i+=4) if (curve[i]!=0) return "Сила задана яркостной кривой.";
                    } catch (IllegalArgumentException ignored) {}
                }
            }
            if (key.startsWith("pref_rt_nr_") || key.startsWith("pref_tunable_esd3d2_")) {
                if (original) return "Здесь параметры ESD3D. Сейчас выбран RawTherapee 5.12.";
                if (hdr && !hex) return "В режиме HDR+ этот дополнительный ESD3D-проход пропускается.";
                if (!key.equals("pref_tunable_esd3d2_enable") && !on("pref_tunable_esd3d2_enable",true)) return "Включите ESD3D в дополнительных параметрах шумоподавления.";
            }
            if (key.startsWith("pref_ai_denoise_") && !key.equals("pref_ai_denoise_enabled_key") && !on("pref_ai_denoise_enabled_key",false)) return "Включите ИИ-шумоподавление Bayer.";
        }
        if (key.startsWith("pref_sharp_")) {
            String master = key.contains("deconv") ? "pref_sharp_deconv_enabled_key"
                    : key.contains("micro") ? "pref_sharp_micro_enabled_key" : "pref_sharp_usm_enabled_key";
            if (!key.equals(master) && !on(master, master.contains("usm"))) return "Включите соответствующий алгоритм резкости.";
            if (any(key,"pref_sharp_edges_radius_key","pref_sharp_edges_tolerance_key") && !on("pref_sharp_edges_only_key",false)) return "Включите режим «Только края».";
            if (key.equals("pref_sharp_halo_amount_key") && !on("pref_sharp_halo_control_key",false)) return "Включите контроль ореолов Unsharp Mask.";
        }
        if (key.startsWith("pref_c1_") && !on("pref_capture_one_enabled_key",false)) return "Включите коррекцию цвета и деталей.";
        if (key.startsWith("scamera_darktable_")) {
            if (!initial) return "Доступно с Exposure Fusion или PhotonCamera Curve.";
            if (!key.equals("scamera_darktable_enabled") && !on("scamera_darktable_enabled",false)) return "Включите дополнительную обработку darktable-style.";
        }
        if (key.startsWith("pref_aces_")) {
            if (!initial) return "ACES доступен с Exposure Fusion или PhotonCamera Curve.";
            if (!key.equals("pref_aces_enabled_key") && !aces) return "Включите ACES.";
            if (key.equals("pref_aces_custom_gamma_key") && !text("pref_aces_gamma_curve_key","0").equals("8")) return "Выберите пользовательскую гамма-кривую ACES.";
        }
        if (key.startsWith("pref_tunable_initial_") || key.startsWith("pref_tunable_autoexposurecurve_") || any(key,"pref_contrast_seekbar_key","pref_saturation_seekbar_key","pref_shadows_seekbar_key")) {
            if (!initial) return "Доступно с Exposure Fusion или PhotonCamera Curve.";
            if (aces && (any(key,"pref_contrast_seekbar_key","pref_saturation_seekbar_key","pref_shadows_seekbar_key")
                    || key.startsWith("pref_tunable_initial_gamma") || key.startsWith("pref_tunable_initial_tonemap")
                    || any(key,"pref_tunable_initial_tonemix","pref_tunable_initial_saturationred"))) return "При ACES используются его регуляторы тона и цвета.";
        }
        if (key.startsWith("pref_tunable_headroomrender_") || key.startsWith("pref_tunable_linearexposure_")) {
            if (!tone.equals("sky")) return "Доступно только в режиме Sky (Headroom).";
        }
        if (key.startsWith("pref_tunable_opendrt_") && !tone.equals("opendrt")) return "Выберите OpenDRT.";
        if (key.startsWith("pref_tunable_locallaplacian_")) {
            if (tone.equals("fusion") || tone.equals("opendrt")) return "LLF используется с PhotonCamera Curve, Sky или линейным выводом.";
            if (!key.endsWith("_enabled") && !on("pref_tunable_locallaplacian_enabled",true)) return "Включите Local Laplacian.";
        }
        if (key.equals("pref_compressor_seekbar_key") && !tone.equals("fusion")) return "Доступно только с Exposure Fusion.";
        if (key.equals("pref_false_color_strength_key") && !on("pref_false_color_enabled_key",true)) return "Включите автокоррекцию цветных граней.";
        if (key.startsWith("pref_raisr_") && !key.equals("pref_raisr_enabled_key") && !on("pref_raisr_enabled_key",false)) return "Включите RAISR.";
        if (key.startsWith("pref_mfsr_") && !on("pref_raw_mfsr_enabled_key",false)) return "Включите RAW MFSR.";
        if (hex && (key.startsWith("pref_merge_") || key.startsWith("pref_hdrplus_") || key.startsWith("pref_snr_")
                || key.startsWith("pref_mfsr_") || key.startsWith("pref_tunable_esd4d_") || key.startsWith("pref_tunable_pyramidalignment_")
                || any(key,"pref_raw_mfsr_enabled_key","pref_frame_count_key","pref_short_frame_count_key","pref_long_frame_count_key","pref_short_exposure_ev_key","pref_long_exposure_ev_key","pref_highlight_suppression_key","pref_tet_model_enabled_key","pref_zsl_merge_algorithm_key","pref_night_merge_algorithm_key")))
            return "HexQuad использует свою склейку шести кадров одинаковой экспозиции.";
        if ((key.startsWith("pref_hdrplus_") || key.startsWith("pref_snr_")) && !hdr) return "Эти регуляторы применяются при склейке HDR+ в соответствующем режиме Фото/Ночь.";
        if (key.equals("pref_merge_seekbar_key") && hdr) return "HDR+ использует отдельные регуляторы частот и цвета.";
        if (key.equals("pref_highlight_recovery_min_ok_key") && !on("pref_highlight_recovery_key",false)) return "Включите восстановление светов при склейке.";
        if (any(key,"pref_highlight_protection_knee_key","pref_highlight_protection_strength_key") && !on("pref_highlight_protection_key",false)) return "Включите защиту светов при склейке.";
        if (key.equals("pref_antibanding_hz_key") && !on("pref_tet_model_enabled_key",false)) return "Частота применяется в TET-модели экспозиций.";
        if (key.startsWith("pref_tunable_ablc_") && !key.endsWith("_enable") && !on("pref_tunable_ablc_enable",true)) return "Включите коррекцию чёрного ABLC.";
        if (key.equals("pref_tunable_bayer2float_hlclip") && !on("pref_tunable_bayer2float_hlinpaintopposed",true)) return "Включите восстановление светов RAW.";
        if (key.equals("pref_tunable_esd4d_flowrefinemaxdisp") && !on("pref_tunable_esd4d_enableflowrefinement",true)) return "Включите уточнение оптического потока.";
        if (key.startsWith("pref_tunable_autoexposurecurve_")) {
            if (any(key,"pref_tunable_autoexposurecurve_whiteapply","pref_tunable_autoexposurecurve_adaptivewhitepointenable")
                    && !on("pref_tunable_autoexposurecurve_enablewp",true)) return "Включите поиск точки белого.";
            if (any(key,"pref_tunable_autoexposurecurve_kneemax","pref_tunable_autoexposurecurve_kneemin","pref_tunable_autoexposurecurve_kneeref","pref_tunable_autoexposurecurve_cliptolerance")
                    && !on("pref_tunable_autoexposurecurve_highlightcompression",true)) return "Включите сжатие светов.";
        }
        if (key.startsWith("pref_tunable_esd4d_")) {
            if (any(key,"pref_tunable_esd4d_usencnnflow","pref_tunable_esd4d_enableflowrefinement")
                    && !on("pref_tunable_esd4d_enablealignment",true)) return "Включите выравнивание кадров.";
            if (any(key,"pref_tunable_esd4d_detectthr","pref_tunable_esd4d_max_hot_pixels","pref_tunable_esd4d_max_reasonable_hotpixels")
                    && !on("pref_tunable_esd4d_enablehotpixelcorrection",true)) return "Включите коррекцию горячих пикселей.";
            if (any(key,"pref_tunable_esd4d_enablenoisestore","pref_tunable_esd4d_noiseblendmaxframes","pref_tunable_esd4d_noiseblendcalmpy","pref_tunable_esd4d_noisescansubsample","pref_tunable_esd4d_noisefitvarbins","pref_tunable_esd4d_noisefitgatempy","pref_tunable_esd4d_enablefitocorrection","pref_tunable_esd4d_adaptivefallbackmin","pref_tunable_esd4d_adaptivefallbackmax")
                    && !on("pref_noise_dynamic_enabled_key",true)) return "Включите динамическую оценку модели шума.";
        }
        if (key.startsWith("pref_tunable_pyramidalignment_") && (!on("pref_tunable_esd4d_enablealignment",true)
                || on("pref_tunable_esd4d_usencnnflow",false))) return "Параметры блочного выравнивания. При FlowNet применяются только при возврате к пирамиде.";
        if (key.equals("pref_tunable_imagesaversettings_croptype") && !on("pref_wide169_key",false)) return "Выберите формат 16:9.";
        if (key.equals("pref_show_gradient_key") && text("pref_theme_accent_key","default").equals("eszdman")) return "Оформление задаётся выбранной темой.";
        return null;
    }
}
