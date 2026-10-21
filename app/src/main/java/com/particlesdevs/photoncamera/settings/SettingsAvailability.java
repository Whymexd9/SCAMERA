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
    /** Why «Цветовой метод» does not act here (a tuned / ISP matrix replaces it), null when it does. */
    private String colorMethodOverride;
    /** The tele's smooth optical zoom exists here (capture.XiaomiTeleZoom.phone: the Xiaomi 17 Ultra); assumed unless told otherwise. */
    private boolean xiaomiSmoothZoom = true;
    /** P128: the OPPO tuned ISP matrix renders this phone's photos (OppoTunedColor.applies); assumed unless told otherwise. */
    private boolean oppoTunedColor = true;
    /** P182: «Аппаратный ремозаик» of FULLRES acts here (VivoSdkFullRes.hardwareRemosaicOffered); assumed unless told otherwise. */
    private boolean fullResHwRemosaic = true;
    /** P128: a module of this phone has streamed a Quad / Tetra mosaic (MosaicBlockStore); assumed unless told otherwise. */
    private boolean mosaicStreams = true;
    /** P190: a module here has FULLRES 50 / 200 MP shots (FullResProfiles.sizeOffered); assumed unless told otherwise. */
    private boolean fullRes50 = true, fullRes200 = true;
    public SettingsAvailability(Map<String, ?> values) { this.values = values; }
    /** The device fact behind «HEIC 10 бит»: the reason it is unavailable here (Android version, no Main10 / P010 encoder) or null. */
    public SettingsAvailability heic10Unavailable(String reason) { heic10Unavailable = reason; return this; }
    /** The device fact behind «HDR в HEIC / AVIF»: the reason it cannot act here (Android version) or null. */
    public SettingsAvailability hdrUnavailable(String reason) { hdrUnavailable = reason; return this; }
    /** The device fact behind «Стоковый AE vivo»: the stock AE observer exists on this phone. */
    /** The device fact behind «Цветовой метод»: the matrix that replaces the choice on this phone, or null. */
    public SettingsAvailability colorMethodOverride(String reason) { colorMethodOverride = reason; return this; }
    /** The device fact behind «Плавный оптический зум»: hidden on any other phone than the Xiaomi 17 Ultra. */
    public SettingsAvailability xiaomiSmoothZoom(boolean supported) { xiaomiSmoothZoom = supported; return this; }
    /** The device fact behind «Насыщенность матрицы OPPO»: hidden where the OPPO tuned matrix never renders a photo. */
    public SettingsAvailability oppoTunedColor(boolean applies) { oppoTunedColor = applies; return this; }
    /** The device fact behind «Аппаратный ремозаик»: hidden where FULLRES has no choice of remosaic. */
    public SettingsAvailability fullResHwRemosaic(boolean offered) { fullResHwRemosaic = offered; return this; }
    /** P190: the device facts behind «Кадров в снимке 50 / 200 Мп»: hidden where no module has FULLRES of that size. */
    public SettingsAvailability fullResSizes(boolean fifty, boolean twoHundred) { fullRes50 = fifty; fullRes200 = twoHundred; return this; }
    /** The device fact behind the mosaic merge rows: whether a module here has streamed a Quad / Tetra mosaic. */
    public SettingsAvailability mosaicStreams(boolean seen) { mosaicStreams = seen; return this; }
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
     * and «Также сохранять JPEG» while the format is JPEG («Цветовое пространство» serves every format); and the rows of what this phone does not have (the Xiaomi
     * smooth zoom, the OPPO tuned matrix's saturation).
     */
    public boolean hidden(String key) {
        // Rows of what this phone does not have (the settings screen also hides them when it builds the tree, for the search).
        if (!xiaomiSmoothZoom && key.equals("pref_xiaomi_smooth_zoom")) return true;
        if (!fullResHwRemosaic && key.equals("pref_fullres_hw_remosaic")) return true;
        if (!fullRes50 && (key.equals("pref_scam_hybrid_fullres50_frames") || key.equals("pref_scam_hybrid_fullres50_zsl"))) return true;
        if (!fullRes200 && key.equals("pref_scam_hybrid_fullres200_frames")) return true;
        // P128: the AgX saturation of the OPPO tuned matrix acts only on the phones that matrix renders (ArkTone.ccmSatComp)
        if (!oppoTunedColor && key.equals("pref_scam_hybrid_ark_ccm_sat")) return true;
        String format = photoFormat();
        switch (key) {
            case "pref_heic_quality": case "pref_heic_10bit": return !format.equals("heic");
            case "pref_webp_quality": case "pref_webp_lossless": return !format.equals("webp");
            case "pref_avif_quality": case "pref_avif_lossless": case "pref_avif_depth": case "pref_avif_chroma":
            case "pref_avif_speed": return !format.equals("avif");
            case "pref_photo_also_jpeg": return format.equals("jpeg");
            default: return false;
        }
    }

    public String reason(String key) {
        // The denoise and watermark switches sit on a parent page of these rows (P6), so the rule replaces android:dependency.
        if ((key.startsWith("pref_scam_hybrid_dn_") || any(key, "pref_scam_hybrid_ark_bracket_denoise", "pref_scam_hybrid_ark_chroma_denoise"))
                && !on("pref_scam_hybrid_denoise", true)) return Lang.t("Включите «Шумоподавление».", "Turn on “Noise reduction”.");
        // Denoise engine (pref_scam_hybrid_dn_engine): The reference finish (ScamDenoise) reads the dn_ rows, NLM (ScamHdrDenoise) its two strengths.
        boolean nlm = text("pref_scam_hybrid_dn_engine", "multiscale").trim().equals("nlm");
        if (key.startsWith("pref_scam_hybrid_dn_") && !key.equals("pref_scam_hybrid_dn_engine") && nlm)
            return Lang.t("Только для многомасштабного шумодава в «Шумодав SHDR».", "Multiscale denoiser only (“SHDR denoiser”).");
        if (any(key, "pref_scam_hybrid_post_luma", "pref_scam_hybrid_post_chroma") && !nlm)
            return Lang.t("Только для шумодава NLM в «Шумодав SHDR».", "NLM denoiser only (“SHDR denoiser”).");
        // Output size (pref_scam_hybrid_output): every size but the sensor's merges on the Sabre 2x grid; 12 / 16 / 20 MP are
        // then made with the downsampler (HybridFinalResize), 2x is kept as it is.
        String output = text("pref_scam_hybrid_output", "sensor").trim();
        if (key.equals("pref_scam_hybrid_downsampler") && !any(output, "12", "16", "20"))
            return Lang.t("Только при «Разрешение» 12, 16 или 20 МП.", "Only with “Resolution” 12, 16 or 20 MP.");
        if (any(key, "pref_scam_hybrid_dn_chroma_2x_keep", "pref_scam_hybrid_auto2x") && output.equals("sensor"))
            return Lang.t("Только на сетке 2×: «Разрешение» 12, 16, 20 МП или 2×.", "2× grid only: “Resolution” 12, 16, 20 MP or 2×.");
        if (key.equals("pref_scam_hybrid_auto2x") && output.equals("2x"))
            return Lang.t("В «Разрешение 2×» всегда используется настоящая сетка склейки 2×; автоматическая замена на 1× выключена.",
                    "“Resolution 2×” always uses the native 2× merge grid; automatic 1× replacement is disabled.");
        // P128: the «Движение» rows replace the normal ones only while the mode is on (MotionMode, ParamController)
        if (key.startsWith("pref_scam_hybrid_mm_") && !on("pref_motion_mode", false))
            return Lang.t("Действует только в режиме «Движение» (кнопка в верхней панели).", "Acts only in “Motion” mode (the button in the top bar).");
        // «Мерцание ламп» is never greyed out: besides the long frames' shutter (HybridPlan) the native FULLRES AE (OPPO, vivo)
        // rounds its shutter to whole flicker periods with or without them (P310 settings cleanup; P128 greyed it with Shasta off)
        // P128: the mosaic merge acts on Quad / Tetra streams (ISZ modules without the sensor's remosaic) only
        if (key.startsWith("pref_scam_hybrid_mosaic_") && !mosaicStreams)
            return Lang.t("Только для модулей с мозаикой Quad / Tetra (ISZ без ремозаика); на этом телефоне её ещё не было.",
                    "Only for modules streaming a Quad / Tetra mosaic (ISZ without remosaic); none has on this phone yet.");
        // P29 mosaic merge: the native rows follow the merge list, the split's edge kernel only acts on the split (P35: Tetra takes
        // the native merge too with "Tetra path" at its default, so the row is live only with the split for Quad or for Tetra).
        if (key.startsWith("pref_scam_hybrid_mosaic_")) {
            // P267: without our remosaic a Quad / Tetra shot is merged as always, its merged mosaic is the DNG alone: every mosaic
            // merge row still acts (the RGB the DNG is sampled from is that merge)
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
        // P134 «Цвет»: the AgX looks but «Custom» set their own saturation (ArkAe.LOOKS); a grading zone's hue needs its strength
        if (key.equals("pref_scam_hybrid_ark_agx_sat") && !text("pref_scam_hybrid_ark_agx_look", "4").equals("4"))
            return Lang.t("Только при «Стиль AgX» = «Custom»: другие стили задают насыщенность сами.",
                    "Only with “AgX look” = “Custom”: the other looks set their own saturation.");
        for (String zone : new String[]{"shadows", "mids", "highlights"})
            if (key.equals("pref_scam_hybrid_colour_grade_" + zone + "_hue") && PreferenceNumber.read(values.get("pref_scam_hybrid_colour_grade_" + zone + "_str"), 0) <= 0)
                return Lang.t("Сначала задайте силу этой зоны.", "Set this zone's strength first.");
        // P118: rows that act only with another setting off its default (found while the descriptions were rewritten)
        final String sabre61 = text("pref_scam_hybrid_sabre61", "1");
        if (key.equals("pref_scam_hybrid_s61_min_motion") && !sabre61.equals("2"))
            return Lang.t("Только при «Новое ядро склейки» = «Авто (ночью)».", "Only with “New merge kernel” = “Auto (at night)”.");
        if (any(key, "pref_scam_hybrid_snr_scale", "pref_scam_hybrid_tensor_noise") && sabre61.equals("1"))
            return Lang.t("Только для прежнего ядра: «Новое ядро склейки» выключено или «Авто».", "Previous kernel only: “New merge kernel” off or “Auto”.");
        if (any(key, "pref_scam_hybrid_shasta_sharpness", "pref_scam_hybrid_shasta_max_ratio") && !text("pref_scam_hybrid_shasta_mode", "2").equals("1"))
            return Lang.t("Только при «Кадры теней: режим» = «Авто».", "Only with “Shadow frames: mode” = “Auto”.");
        if (key.equals("pref_scam_hybrid_bento_trigger") && !text("pref_scam_hybrid_bento", "2").equals("1")
                && !(on("pref_motion_mode", false) && text("pref_scam_hybrid_mm_bento", "1").equals("1")))
            return Lang.t("Только при «Кадры светов» = «Авто».", "Only with “Highlight frames” = “Auto”.");
        if (any(key, "pref_scam_hybrid_motion_boost", "pref_scam_hybrid_mm_motion_boost", "pref_scam_hybrid_la_engine") && text("pref_scam_hybrid_local_align", "1").equals("0"))
            return Lang.t("Нужно «Локальное выравнивание F6».", "Needs “Local alignment F6”.");
        // P144: AgX and its look give way to the tone operator switched on in «Кривая AgX и ACES»
        if (key.startsWith("pref_scam_hybrid_ark_agx_")) {
            String other = on("pref_scam_hybrid_ark_curve_on", false) ? Lang.t("своя кривая", "the own curve")
                    : on("pref_scam_hybrid_ark_uchi_on", false) ? "Uchimura" : on("pref_scam_hybrid_ark_aces_on", false) ? "ACES" : null;
            if (other != null)
                return Lang.t("Сейчас вместо AgX работает " + other + " (в «Кривая AgX и ACES»).",
                        "Now " + other + " works instead of AgX (in “AgX and ACES curve”).");
        }
        if (key.startsWith("pref_scam_hybrid_ark_agx_") && !key.equals("pref_scam_hybrid_ark_agx_look")
                && !text("pref_scam_hybrid_ark_agx_look", "4").equals("4"))
            return Lang.t("Только в стиле AgX «Custom»: другие стили задают эти значения сами.", "AgX look “Custom” only: the other looks set these values themselves.");
        if (key.startsWith("pref_watermark_") && !on("pref_show_watermark_key", true)) return Lang.t("Включите водяной знак.", "Turn on the watermark.");
        // «Формат фото»: Ultra HDR and the JPEG quality act on a JPEG, which a HEIC / WebP / AVIF shot writes only with «Также сохранять JPEG».
        if (!writesJpeg()) {
            if (key.equals("pref_jpeg_quality") || key.equals("pref_jpeg_fast_encoder"))
                return Lang.t("Для JPEG: выберите формат JPEG или включите «Также сохранять JPEG».",
                        "For JPEG: choose the JPEG format or turn on “Also save a JPEG”.");
        }
        // «HEIC 10 бит» needs Android 13 and an HEVC Main10 encoder with P010 input; without them the shot is an 8-bit HEIC.
        if (key.equals("pref_heic_10bit") && heic10Unavailable != null) return heic10Unavailable;
        // P266 «HDR-фото» (one switch: the JPEG's Ultra HDR, P46 / P125 the HEIC / AVIF gain map): a JPEG of the shot holds it
        // always; the HEIC needs the app's 10-bit HEIC writer, AVIF holds the map at any depth, WebP none.
        if (key.equals("pref_ultrahdr_key") && !writesJpeg()) {
            final String format = photoFormat();
            if (format.equals("webp"))
                return Lang.t("В WebP HDR не хранится: включите «Также сохранять JPEG» — HDR будет в нём.",
                        "WebP holds no HDR: turn on “Also save a JPEG” to get it in that JPEG.");
            if (hdrUnavailable != null) return hdrUnavailable;
            if (format.equals("heic")) {
                if (!on("pref_heic_10bit", false))
                    return Lang.t("Включите «HEIC 10 бит» или «Также сохранять JPEG»: HDR хранится в 10-битном HEIC или в JPEG.",
                            "Turn on “10-bit HEIC” or “Also save a JPEG”: HDR needs the 10-bit HEIC or a JPEG.");
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
        if (key.equals("pref_color_method_key") && colorMethodOverride != null) return colorMethodOverride;
        // a DCP profile replaces the matrix the colour method picks (Parameters: sensorToProPhoto from the DCP)
        if (key.equals("pref_color_method_key") && !text("pref_dcp_profile_key", "").trim().isEmpty())
            return Lang.t("Выбран DCP-профиль: цвет задаёт его матрица, выбор не действует.",
                    "A DCP profile is chosen: its matrix sets the colour, the choice has no effect.");
        // the worker keeps one copy of a shot's burst: with the lab switch on it goes to the lab's folder (ScamNeuralClient)
        if (key.equals("pref_keep_raw_burst") && on("pref_scam_lab_keep", false))
            return Lang.t("Включено «Сохранять снимки для лаборатории»: RAW-серия уже сохраняется в папку снимка лаборатории.",
                    "“Keep shots for the lab” is on: the RAW burst is already kept in the lab shot's folder.");
        return null;
    }
}
