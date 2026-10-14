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
        boolean arkShared = key.startsWith("pref_scam_hybrid_ark_");
        if (key.startsWith("pref_scam_hybrid_") && !hybrid && !arkShared) return Lang.t("Выберите склейку «Hybrid».", "Select the “Hybrid” merge.");
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
        if (any(key, "pref_scam_hybrid_dn_chroma_2x_keep", "pref_scam_hybrid_auto2x") && output.equals("sensor"))
            return Lang.t("Только на сетке 2×: «Разрешение» 12, 16, 20 МП или 2×.", "2× grid only: “Resolution” 12, 16, 20 MP or 2×.");
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
        // P118: rows that act only with another setting off its default (found while the descriptions were rewritten)
        final String sabre61 = text("pref_scam_hybrid_sabre61", "1");
        if (any(key, "pref_scam_hybrid_s61_max_key", "pref_scam_hybrid_s61_min_motion") && !sabre61.equals("2"))
            return Lang.t("Только при «Ядро Sabre 6.1» = «Авто (ночью)».", "Only with “Sabre 6.1 kernel” = “Auto (at night)”.");
        if (any(key, "pref_scam_hybrid_snr_scale", "pref_scam_hybrid_tensor_noise") && sabre61.equals("1"))
            return Lang.t("Только для ядра раунда 4: «Ядро Sabre 6.1» выключено или «Авто».", "Round-4 kernel only: “Sabre 6.1 kernel” off or “Auto”.");
        if (any(key, "pref_scam_hybrid_shasta_sharpness", "pref_scam_hybrid_shasta_max_ratio") && !text("pref_scam_hybrid_shasta_mode", "2").equals("1"))
            return Lang.t("Только при «Shasta: режим» = «Авто».", "Only with “Shasta: mode” = “Auto”.");
        if (key.equals("pref_scam_hybrid_bento_trigger") && !text("pref_scam_hybrid_bento", "2").equals("1")
                && !(on("pref_motion_mode", false) && text("pref_scam_hybrid_mm_bento", "1").equals("1")))
            return Lang.t("Только при «Bento» = «Авто».", "Only with “Bento” = “Auto”.");
        if (any(key, "pref_scam_hybrid_motion_boost", "pref_scam_hybrid_mm_motion_boost") && text("pref_scam_hybrid_local_align", "1").equals("0"))
            return Lang.t("Нужно «Локальное выравнивание F6».", "Needs “Local alignment F6”.");
        if (key.startsWith("pref_scam_hybrid_ark_agx_") && !key.equals("pref_scam_hybrid_ark_agx_look")
                && !text("pref_scam_hybrid_ark_agx_look", "4").equals("4"))
            return Lang.t("Только в стиле AgX «Custom»: другие стили задают эти значения сами.", "AgX look “Custom” only: the other looks set these values themselves.");
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
        // «HDR в HEIC / AVIF» (P46, P125 gain map): the HEIC needs the app's 10-bit HEIC writer; AVIF holds the map at any depth.
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
        if (key.equals("pref_color_method_key") && colorMethodOverride != null) return colorMethodOverride;
        return null;
    }
}
