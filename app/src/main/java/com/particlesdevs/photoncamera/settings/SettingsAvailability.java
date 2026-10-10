package com.particlesdevs.photoncamera.settings;

import com.particlesdevs.photoncamera.util.Lang;

import java.util.Map;

/**
 * Conditions mirror the capture/post-pipeline branches. Pages stay open to explain inactive rows. The facts of the phone
 * (they need Android; tools/check_settings_model.py compiles this class alone) are set by DeviceAvailability.
 */
public final class SettingsAvailability {
    private final Map<String, ?> values;
    /** Why this phone cannot write the 10-bit HEIC (heif.Heic10Support.unavailableReason), null when it can or is not known. */
    private String heic10Unavailable;
    /** Why «HDR в HEIC / AVIF» cannot act on this phone (processing.color.HdrOutput.unavailableReason: Android 13), or null. */
    private String hdrUnavailable;
    /** The vivo stock AE observer runs on this phone (capture.ScamStockAe.supportedDevice: the vivo X200 Ultra). */
    private boolean stockAeDevice;
    /** Why «Цветовой метод» does not act here (a tuned / ISP matrix replaces it), null when it does. */
    private String colorMethodOverride;
    /** SCAM HDR runs on this phone (PreferenceKeys.isScamHdrSupported: the Snapdragon 8 Elite); assumed unless told otherwise. */
    private boolean scamHdrSupported = true;
    /** The tele's smooth optical zoom exists here (capture.XiaomiTeleZoom.phone: the Xiaomi 17 Ultra); assumed unless told otherwise. */
    private boolean xiaomiSmoothZoom = true;
    public SettingsAvailability(Map<String, ?> values) { this.values = values; }
    /** The device fact behind «HEIC 10 бит»: the reason it is unavailable here (Android version, no Main10 / P010 encoder) or null. */
    public SettingsAvailability heic10Unavailable(String reason) { heic10Unavailable = reason; return this; }
    /** The device fact behind «HDR в HEIC / AVIF»: the reason it cannot act here (Android version) or null. */
    public SettingsAvailability hdrUnavailable(String reason) { hdrUnavailable = reason; return this; }
    /** The device fact behind «Стоковый AE vivo»: the stock AE observer exists on this phone. */
    public SettingsAvailability stockAeDevice(boolean supported) { stockAeDevice = supported; return this; }
    /** The device fact behind «Цветовой метод»: the matrix that replaces the choice on this phone, or null. */
    public SettingsAvailability colorMethodOverride(String reason) { colorMethodOverride = reason; return this; }
    /** The device fact behind the SCAM HDR screen and its checks: they are hidden where SCAM HDR cannot run. */
    public SettingsAvailability scamHdrSupported(boolean supported) { scamHdrSupported = supported; return this; }
    /** The device fact behind «Плавный оптический зум»: hidden on any other phone than the Xiaomi 17 Ultra. */
    public SettingsAvailability xiaomiSmoothZoom(boolean supported) { xiaomiSmoothZoom = supported; return this; }
    private String text(String key, String fallback) {
        Object value = values.get(key); return value == null ? fallback : value.toString();
    }
    private boolean on(String key, boolean fallback) { return PreferenceNumber.bool(values.get(key), fallback); }
    private boolean any(String key, String... alternatives) {
        for (String value : alternatives) if (key.equals(value)) return true;
        return false;
    }
    /** «Формат фото» as stored (pref_photo_format): jpeg (default), heic, webp or avif. */
    private String photoFormat() {
        String f = text("pref_photo_format", "jpeg").trim().toLowerCase(java.util.Locale.ROOT);
        return f.equals("heic") || f.equals("webp") || f.equals("avif") ? f : "jpeg";
    }
    /** «Резкость Hybrid» as stored (PreferenceKeys.scamSharpenMode): ark (default), rt, scam or off. */
    private String sharpMode() {
        String v = text("pref_scam_hybrid_sharp_mode", "ark").trim();
        return v.equals("rt") || v.equals("scam") || v.equals("off") ? v : "ark";
    }
    /** Whether a shot writes a JPEG: the JPEG format, or HEIC / WebP / AVIF with «Также сохранять JPEG». */
    private boolean writesJpeg() {
        return photoFormat().equals("jpeg") || on("pref_photo_also_jpeg", false);
    }

    /**
     * Rows that only belong to another choice and are hidden instead of explained: the settings of the photo formats that
     * are not chosen (HEIC quality / 10 bit, WebP quality / lossless, the AVIF rows, «HDR в HEIC / AVIF» outside HEIC / AVIF)
     * and «Также сохранять JPEG» while the format is JPEG («Цветовое пространство» serves every format); and the rows of what this phone does not have (SCAM HDR and its checks without the 8 Elite, the Xiaomi
     * smooth zoom).
     */
    public boolean hidden(String key) {
        // Rows of what this phone does not have (the settings screen also hides them when it builds the tree, for the search).
        if (!scamHdrSupported && any(key, "scam_hdr_screen", "scam_diagnostics_screen", "scam_probe", "scam_neural_probe")) return true;
        if (!xiaomiSmoothZoom && key.equals("pref_xiaomi_smooth_zoom")) return true;
        String format = photoFormat();
        switch (key) {
            case "pref_heic_quality": case "pref_heic_10bit": return !format.equals("heic");
            case "pref_webp_quality": case "pref_webp_lossless": return !format.equals("webp");
            case "pref_avif_quality": case "pref_avif_lossless": case "pref_avif_depth": case "pref_avif_chroma":
            case "pref_avif_speed": return !format.equals("avif");
            case "pref_photo_also_jpeg": return format.equals("jpeg");
            case "pref_photo_hdr": return !format.equals("heic") && !format.equals("avif");
            default: return false;
        }
    }

    public String reason(String key) {
        // Route selector pref_merge_route (PreferenceKeys.mergeRoute): hybrid (default) or scamhdr. Every shot is one of them.
        boolean autonomous=text("pref_merge_route","hybrid").equals("scamhdr");
        String mosaicMode=text("pref_scamhdr_mosaic","off");
        boolean scamMosaic=!mosaicMode.equals("off") && autonomous;
        boolean neuralMosaic=scamMosaic && mosaicMode.startsWith("neural");
        boolean hybrid=!autonomous;
        // The ARK tone (ArkCore) and its sharpening are shared by both routes.
        boolean arkShared = key.startsWith("pref_scam_hybrid_ark_") || key.equals("pref_scam_hybrid_sharp_mode");
        if (key.startsWith("pref_scam_hybrid_") && !hybrid && !arkShared) return Lang.t("Выберите склейку «Hybrid».", "Select the “Hybrid” merge.");
        // Sharpening after the merge (PostPipeline.BuildDefaultPipeline): "ark" runs ArkLumaSharpen before the detail delta and
        // RawTherapee after the tone only with «Доп. резкость после тона»; "rt" / "scam" sharpen after the tone; "off" nothing.
        String sharp = sharpMode();
        boolean arkSharp = sharp.equals("ark");
        boolean postSharp = arkSharp ? on("pref_scam_hybrid_ark_post_sharp", false) : !sharp.equals("off");
        boolean rtSharp = sharp.equals("rt") || arkSharp && postSharp;
        if ((key.startsWith("pref_scam_hybrid_ark_sharp_") && !any(key, "pref_scam_hybrid_ark_sharp_guard", "pref_scam_hybrid_ark_sharp_note")
                || key.equals("pref_scam_hybrid_ark_post_sharp")) && !arkSharp)
            return Lang.t("Только для резкости ARK в «Резкость Hybrid».", "ARK sharpening only (“Hybrid sharpening”).");
        if (key.equals("pref_scam_hybrid_ark_sharp_guard") && !postSharp)
            return Lang.t("Только с резкостью после тона: RawTherapee, SCAM или ARK с «Доп. резкость после тона».",
                    "Only with sharpening after the tone: RawTherapee, SCAM, or ARK with “Extra sharpening after the tone”.");
        if (key.equals("pref_scam_hybrid_ark_detail_gain") && arkSharp)
            return Lang.t("Только без резкости ARK: с ней деталь задаёт «Сила детали резкости ARK».",
                    "Only without ARK sharpening: with it “ARK sharpening detail strength” sets the detail.");
        if ((key.startsWith("pref_sharp_") || key.equals("pref_scam_hybrid_sharp_strength")) && !rtSharp)
            return Lang.t("Только для резкости RawTherapee: выберите её в «Резкость Hybrid» или включите «Доп. резкость после тона» у ARK.",
                    "RawTherapee sharpening only: choose it in “Hybrid sharpening” or turn on “Extra sharpening after the tone” with ARK.");
        if (key.equals("pref_scam_hybrid_sharp_amount") && !sharp.equals("scam"))
            return Lang.t("Только для резкости SCAM в «Резкость Hybrid».", "SCAM sharpening only (“Hybrid sharpening”).");
        // The denoise and watermark switches sit on a parent page of these rows (P6), so the rule replaces android:dependency.
        if (key.startsWith("pref_scam_hybrid_dn_") && !on("pref_scam_hybrid_denoise", true)) return Lang.t("Включите «Шумоподавление».", "Turn on “Noise reduction”.");
        // Denoise engine (pref_scam_hybrid_dn_engine): the GCam finish (ScamDenoise) reads the dn_ rows, NLM (ScamHdrDenoise) its two strengths.
        boolean nlm = text("pref_scam_hybrid_dn_engine", "gcam").trim().equals("nlm");
        if (key.startsWith("pref_scam_hybrid_dn_") && !key.equals("pref_scam_hybrid_dn_engine") && nlm)
            return Lang.t("Только для шумодава GCam в «Шумодав Hybrid».", "GCam denoiser only (“Hybrid denoiser”).");
        if (any(key, "pref_scam_hybrid_post_luma", "pref_scam_hybrid_post_chroma") && !nlm)
            return Lang.t("Только для шумодава NLM в «Шумодав Hybrid».", "NLM denoiser only (“Hybrid denoiser”).");
        // Output size (pref_scam_hybrid_output): every size but the sensor's merges on the Sabre 2x grid; 12 / 16 / 20 MP are
        // then made with the downsampler (HybridFinalResize), 2x is kept as it is.
        String output = text("pref_scam_hybrid_output", "sensor").trim();
        if (key.equals("pref_scam_hybrid_downsampler") && !any(output, "12", "16", "20"))
            return Lang.t("Только при «Разрешение» 12, 16 или 20 МП.", "Only with “Resolution” 12, 16 or 20 MP.");
        if (key.equals("pref_scam_hybrid_dn_chroma_2x_keep") && output.equals("sensor"))
            return Lang.t("Только на сетке 2×: «Разрешение» 12, 16, 20 МП или 2×.", "2× grid only: “Resolution” 12, 16, 20 MP or 2×.");
        // P28 RAW CA: the rows under the mode list follow it and the auto switch.
        if (key.startsWith("pref_scam_hybrid_rawca_") && !key.equals("pref_scam_hybrid_rawca_mode")) {
            if (text("pref_scam_hybrid_rawca_mode", "0").equals("0")) return Lang.t("Выберите режим «Коррекция ХА в RAW».", "Select a “RAW CA correction” mode.");
            boolean auto = on("pref_scam_hybrid_rawca_auto", true);
            if (any(key, "pref_scam_hybrid_rawca_red", "pref_scam_hybrid_rawca_blue") && auto) return Lang.t("Только без автоподбора.", "Only when “Auto detect” is off.");
            if (key.equals("pref_scam_hybrid_rawca_passes") && !auto) return Lang.t("Только с автоподбором.", "Only when “Auto detect” is on.");
        }
        // P29 mosaic merge: the native rows follow the merge list, the split's edge kernel only acts on the split (P35: Tetra takes
        // the native merge too with "Tetra path" at its default, so the row is live only with the split for Quad or for Tetra).
        if (key.startsWith("pref_scam_hybrid_mosaic_")) {
            boolean nativeMosaic = text("pref_scam_hybrid_mosaic_path", "1").equals("1"); // P34: the native merge is the default
            if (key.equals("pref_scam_hybrid_mosaic_edge_scale") && nativeMosaic && !text("pref_scam_hybrid_mosaic_tetra", "1").equals("0")) // P35: T1 default
                return Lang.t("Только для разбиения на подкадры: Quad и Tetra идут нативной склейкой со своим «Ядром поперёк краёв».", "Subframe split only: Quad and Tetra both take the native merge with its own “Kernel across edges”.");
            if (any(key, "pref_scam_hybrid_mosaic_window", "pref_scam_hybrid_mosaic_window_full", "pref_scam_hybrid_mosaic_kernel_scale",
                    "pref_scam_hybrid_mosaic_native_edge_scale", "pref_scam_hybrid_mosaic_kernel_g", "pref_scam_hybrid_mosaic_kernel_rb",
                    "pref_scam_hybrid_mosaic_chroma_fill", "pref_scam_hybrid_mosaic_fill_support", "pref_scam_hybrid_mosaic_tetra",
                    "pref_scam_hybrid_mosaic_native_flat_scale", "pref_scam_hybrid_mosaic_native_clamp") && !nativeMosaic)
                return Lang.t("Только для «Нативная мозаика» в «Склейка мозаики».", "Only with “Native mosaic” in “Mosaic merge”.");
            if (key.equals("pref_scam_hybrid_mosaic_fill_support") && text("pref_scam_hybrid_mosaic_chroma_fill", "0").equals("0"))
                return Lang.t("Только с добором ArkCam.", "Only with the ArkCam fill.");
        }
        if (key.startsWith("pref_watermark_") && !on("pref_show_watermark_key", true)) return Lang.t("Включите водяной знак.", "Turn on the watermark.");
        // «Формат фото»: Ultra HDR and the JPEG quality act on a JPEG, which a HEIC / WebP / AVIF shot writes only with «Также сохранять JPEG».
        if (!writesJpeg()) {
            if (key.equals("pref_ultrahdr_key"))
                return Lang.t("Только для JPEG: включите «Также сохранять JPEG» — Ultra HDR будет в нём.",
                        "JPEG only: turn on “Also save a JPEG” to get Ultra HDR in that JPEG.");
            if (key.equals("pref_jpeg_quality") || key.equals("pref_jpeg_fast_encoder"))
                return Lang.t("Для JPEG: выберите формат JPEG или включите «Также сохранять JPEG».",
                        "For JPEG: choose the JPEG format or turn on “Also save a JPEG”.");
        }
        // «HEIC 10 бит» needs Android 13 and an HEVC Main10 encoder with P010 input; without them the shot is an 8-bit HEIC.
        if (key.equals("pref_heic_10bit") && heic10Unavailable != null) return heic10Unavailable;
        // «HDR в HEIC / AVIF» (P46): HLG needs 10 bits - the 10-bit HEIC, or AVIF at 10 / 12 bit (lossless keeps 10) - and
        // RGBA_1010102 bitmaps (Android 13); otherwise the HEIC / AVIF stays SDR.
        if (key.equals("pref_photo_hdr")) {
            if (hdrUnavailable != null) return hdrUnavailable;
            if (photoFormat().equals("heic")) {
                if (!on("pref_heic_10bit", false))
                    return Lang.t("Включите «HEIC 10 бит»: HDR хранится только в 10-битном HEIC.",
                            "Turn on “10-bit HEIC”: HDR needs the 10-bit HEIC.");
                if (heic10Unavailable != null)
                    return Lang.t("HDR хранится только в 10-битном HEIC, а он здесь недоступен. ", "HDR needs the 10-bit HEIC, which is unavailable here. ")
                            + heic10Unavailable;
            }
            if (photoFormat().equals("avif") && !on("pref_avif_lossless", false) && text("pref_avif_depth", "10").trim().startsWith("8"))
                return Lang.t("Выберите «Глубина цвета» 10 или 12 бит: в 8 битах HDR не хранится.",
                        "Choose “Colour depth” 10 or 12 bit: 8 bits cannot hold HDR.");
        }
        if (key.equals("pref_webp_quality") && on("pref_webp_lossless", false))
            return Lang.t("Не используется в WebP без потерь.", "Not used by lossless WebP.");
        // AVIF «Без потерь» stores the exact pixels: identity matrix, 4:4:4 and the photo's own bit depth.
        if (on("pref_avif_lossless", false)) {
            if (key.equals("pref_avif_quality")) return Lang.t("Не используется в AVIF без потерь.", "Not used by lossless AVIF.");
            if (key.equals("pref_avif_depth"))
                return Lang.t("Без потерь — глубина самого снимка: 10 бит с Android 13, иначе 8.",
                        "Lossless keeps the photo's own depth: 10 bit from Android 13, else 8.");
            if (key.equals("pref_avif_chroma")) return Lang.t("Без потерь — всегда 4:4:4.", "Lossless is always 4:4:4.");
        }
        // pref_scamold_* (the L frame from the ZSL ring) are SCAM HDR keys too: the hybrid takes no L frame (PreferenceKeys.isScamZslLong).
        if ((key.startsWith("pref_scamhdr_") || key.startsWith("pref_scamold_")) && !autonomous)
            return Lang.t("Выберите склейку «SCAM HDR».", "Select the “SCAM HDR” merge.");
        // The stock vivo AE plans only with Root on the vivo X200 Ultra (PreferenceKeys.useStockBracketPlanner); else SCAMERA plans.
        if (key.equals("pref_scamhdr_planner") && !(stockAeDevice && on("pref_root_enabled", false)))
            return stockAeDevice ? Lang.t("Стоковому AE vivo нужен «Root-доступ» (раздел «Система»); без него планирует SCAMERA.",
                            "The stock vivo AE needs “Root access” (System); without it SCAMERA plans.")
                    : Lang.t("Стоковый AE vivo есть только на vivo X200 Ultra с Root-доступом; здесь планирует SCAMERA.",
                            "The stock vivo AE exists only on the vivo X200 Ultra with Root access; SCAMERA plans here.");
        // SCAM HDR mosaic «neural» / «neural_sabre»: tuning of the Quad 2x2 and HexQuad networks.
        if ((key.startsWith("quad2x2_") || key.startsWith("hexquad_")) && !key.endsWith("screen")) {
            if (!neuralMosaic) return Lang.t("Используется в SCAM HDR с мозаикой «Нейросеть» (модули ISZ).", "Used in SCAM HDR with a “Neural remosaic” mosaic mode (ISZ modules).");
            String p = key.startsWith("quad2x2_") ? "quad2x2_" : "hexquad_";
            boolean auto = on(p + "auto_iso", false);
            if (any(key, p + "luma", p + "chroma") && auto) return Lang.t("Сила задаётся ниже по ISO. Для ручной настройки выключите автоматику.", "The strength is set by ISO below. Turn off the auto mode to set it by hand.");
            if (key.startsWith(p + "iso_") && !auto) return Lang.t("Включите «Люма и хрома по ISO».", "Turn on “Luma and chroma by ISO”.");
        }
        // GPU remosaic of SCAM HDR's mosaic modes (S / ES / L always, N in «SCAMERA» / «Sabre»).
        if (key.startsWith("pref_remosaic_") && !scamMosaic) return Lang.t("Используется в SCAM HDR с мозаикой Quad / Tetra (модули ISZ).", "Used in SCAM HDR with a Quad / Tetra mosaic (ISZ modules).");
        if (key.equals("pref_tetra_response_key") && !(scamMosaic && (mosaicMode.equals("detail") || mosaicMode.startsWith("neural"))))
            return Lang.t("Коррекция для мозаики «Tetra Detail» и «Нейросеть» SCAM HDR.", "Correction for the SCAM HDR “Tetra Detail” and “Neural remosaic” mosaic modes.");
        if (any(key,"scamera_quad_bayer_mode","scamera_quad_dng_metadata") && !on("scamera_quad_bayer_enabled", false)) return Lang.t("Включите обработку Quad Bayer.", "Turn on Quad Bayer handling.");
        if (key.startsWith("pref_sharp_")) {
            String master = key.contains("deconv") ? "pref_sharp_deconv_enabled_key"
                    : key.contains("micro") ? "pref_sharp_micro_enabled_key" : "pref_sharp_usm_enabled_key";
            if (!key.equals(master) && !on(master, master.contains("usm"))) return Lang.t("Включите соответствующий алгоритм резкости.", "Turn on the matching sharpening algorithm.");
            if (any(key,"pref_sharp_edges_radius_key","pref_sharp_edges_tolerance_key") && !on("pref_sharp_edges_only_key",false)) return Lang.t("Включите режим «Только края».", "Turn on the “Edges only” mode.");
            if (key.equals("pref_sharp_halo_amount_key") && !on("pref_sharp_halo_control_key",false)) return Lang.t("Включите контроль ореолов Unsharp Mask.", "Turn on Unsharp Mask halo control.");
        }
        if (key.equals("pref_color_method_key") && colorMethodOverride != null) return colorMethodOverride;
        if (key.equals("pref_show_gradient_key") && text("pref_theme_accent_key","default").equals("eszdman")) return Lang.t("Оформление задаётся выбранной темой.", "The look is set by the selected theme.");
        return null;
    }
}
