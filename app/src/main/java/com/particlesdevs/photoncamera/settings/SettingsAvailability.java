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
        // Route selector pref_merge_route (PreferenceKeys.mergeRoute): hybrid (default) or scamhdr. Every shot is one of them.
        boolean autonomous=text("pref_merge_route","hybrid").equals("scamhdr");
        String mosaicMode=text("pref_vivo_nice_mosaic","off");
        boolean niceMosaic=!mosaicMode.equals("off") && autonomous;
        boolean neuralMosaic=niceMosaic && mosaicMode.startsWith("neural");
        boolean hybrid=!autonomous;
        if (key.startsWith("pref_lmc_hybrid_") && !hybrid) return "Выберите склейку «LMC-гибрид».";
        if (key.startsWith("pref_vivo_nice_") && !key.equals("pref_vivo_nice_route") && !autonomous)
            return "Выберите склейку «SCAM HDR».";
        if (key.equals("pref_vivo_nice_route")) {
            return "SCAM HDR использует RAW. Выбор пути больше не применяется.";
        }
        if ((key.startsWith("rt512_") || key.startsWith("pref_rt_")
                    || key.startsWith("pref_tunable_esd3d2_")
                    || key.startsWith("pref_tunable_ablc_") || key.startsWith("pref_aces_")
                    || key.startsWith("scamera_darktable_") || key.startsWith("pref_tunable_initial_")
                    || key.startsWith("pref_tunable_autoexposurecurve_") || key.startsWith("pref_tunable_opendrt_")
                    || key.startsWith("pref_tunable_locallaplacian_")
                    || any(key, "rt_original_controls", "legacy_denoise_screen",
                        "expert_noise_screen", "aces_group_screen", "scamera_darktable_screen",
                        "expert_detail_screen", "pref_tunable_postpipeline_demosaicingmethod",
                        "pref_contrast_seekbar_key", "pref_saturation_seekbar_key", "pref_shadows_seekbar_key",
                        "pref_compressor_seekbar_key")))
            return hybrid ? "В LMC-гибриде этот этап пропускается. Настройки тона и шума — в разделе «LMC-гибрид»."
                    : "В RAW-пути SCAM HDR этот этап пропускается. Настройки тона и шума доступны в меню SCAM HDR.";
        if(key.startsWith("pref_vivo_hdr_") && !autonomous) return "Выберите склейку «SCAM HDR».";
        if(any(key,"pref_tunable_postpipeline_tonepipeline","pref_gcam_finish"))
            return hybrid ? "Сейчас снимает LMC-гибрид: его склейка, шумоподавление и тон настраиваются в разделе «LMC-гибрид»."
                    : "Сейчас снимает SCAM HDR: его шумоподавление и тон настраиваются в его меню.";
        if(key.equals("pref_gcam_cyclops")) return "Маска Cyclops относилась к Sabre RAW, этот путь удалён.";
        if(key.startsWith("pref_gcam_") && !any(key,"pref_gcam_finish","pref_gcam_cyclops","pref_gcam_scene_ae")
                && !on("pref_gcam_finish",false)) return "Включите тональную обработку и резкость в меню GCam.";
        boolean original = text("pref_rt_denoise_backend", "legacy").equals("rt512");
        String tone = text("pref_tunable_postpipeline_tonepipeline", "fusion");
        boolean initial = tone.equals("fusion") || tone.equals("curve");
        boolean aces = on("pref_aces_enabled_key", false);
        // SCAM HDR mosaic «neural» / «neural_sabre»: tuning of the Quad 2x2 and HexQuad networks.
        if ((key.startsWith("quad2x2_") || key.startsWith("hexquad_")) && !key.endsWith("screen")) {
            if (!neuralMosaic) return "Используется в SCAM HDR с мозаикой «Нейросеть» (модули ISZ).";
            String p = key.startsWith("quad2x2_") ? "quad2x2_" : "hexquad_";
            boolean auto = on(p + "auto_iso", false);
            if (any(key, p + "luma", p + "chroma") && auto) return "Сила задаётся ниже по ISO. Для ручной настройки выключите автоматику.";
            if (key.startsWith(p + "iso_") && !auto) return "Включите «Люма и хрома по ISO».";
        }
        if ((key.startsWith("pref_noise_iso_") || key.equals("pref_noise_disable_digital_gain_key"))
                && text("pref_noise_model_profile_key","auto").equals("auto") && !calibratedSensor)
            return "Для изменения ISO модели выберите калиброванный профиль шума. Camera2 Auto использует измеренный шум текущего кадра.";
        if (key.equals("pref_tunable_opendrt_greyboost") || key.equals("pref_tunable_opendrt_hdrpurity")) {
            if (PreferenceNumber.read(values.get("pref_tunable_opendrt_displaypeak"),100)==100)
                return "Этот параметр начинает влиять при Display Peak выше 100 нит.";
        }
        // GPU remosaic of SCAM HDR's mosaic modes (S / ES / L always, N in «SCAMERA» / «Sabre»).
        if (key.startsWith("pref_remosaic_") && !niceMosaic) return "Используется в SCAM HDR с мозаикой Quad / Tetra (модули ISZ).";
        if (key.equals("pref_tetra_response_key") && !(niceMosaic && (mosaicMode.equals("detail") || mosaicMode.startsWith("neural"))))
            return "Коррекция для мозаики «Tetra Detail» и «Нейросеть» SCAM HDR.";
        if (any(key,"scamera_quad_bayer_mode","scamera_quad_dng_metadata") && !on("scamera_quad_bayer_enabled", false)) return "Включите обработку Quad Bayer.";
        if (key.equals("pref_tunable_postpipeline_demosaicingmethod") && any(text("pref_cfa_key","-1"),"-2","4")) return "Для Quad и монохромного сенсора используется специальная демозаика.";
        if (key.startsWith("rt512_") || key.startsWith("pref_rt_nr_") || key.startsWith("pref_ai_denoise_") || key.startsWith("pref_tunable_esd3d2_") || key.equals("pref_rt_denoise_backend")) {
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
                if (!key.equals("pref_tunable_esd3d2_enable") && !on("pref_tunable_esd3d2_enable",true)) return "Включите ESD3D в дополнительных параметрах шумоподавления.";
            }
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
        if (key.startsWith("pref_tunable_ablc_") && !key.endsWith("_enable") && !on("pref_tunable_ablc_enable",true)) return "Включите коррекцию чёрного ABLC.";
        if (key.equals("pref_tunable_bayer2float_hlclip") && !on("pref_tunable_bayer2float_hlinpaintopposed",true)) return "Включите восстановление светов RAW.";
        if (key.startsWith("pref_tunable_autoexposurecurve_")) {
            if (any(key,"pref_tunable_autoexposurecurve_whiteapply","pref_tunable_autoexposurecurve_adaptivewhitepointenable")
                    && !on("pref_tunable_autoexposurecurve_enablewp",true)) return "Включите поиск точки белого.";
            if (any(key,"pref_tunable_autoexposurecurve_kneemax","pref_tunable_autoexposurecurve_kneemin","pref_tunable_autoexposurecurve_kneeref","pref_tunable_autoexposurecurve_cliptolerance")
                    && !on("pref_tunable_autoexposurecurve_highlightcompression",true)) return "Включите сжатие светов.";
        }
        if (key.equals("pref_show_gradient_key") && text("pref_theme_accent_key","default").equals("eszdman")) return "Оформление задаётся выбранной темой.";
        return null;
    }
}
