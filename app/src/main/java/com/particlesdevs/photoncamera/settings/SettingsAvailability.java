package com.particlesdevs.photoncamera.settings;

import com.particlesdevs.photoncamera.util.Lang;

import java.util.Map;

/** Conditions mirror the capture/post-pipeline branches. Pages stay open to explain inactive rows. */
public final class SettingsAvailability {
    private final Map<String, ?> values;
    private final boolean calibratedSensor;
    /** Why this phone cannot write the 10-bit HEIC (heif.Heic10Support.unavailableReason), null when it can or is not known. */
    private String heic10Unavailable;
    public SettingsAvailability(Map<String, ?> values) { this(values,false); }
    public SettingsAvailability(Map<String, ?> values, boolean calibratedSensor) { this.values = values; this.calibratedSensor = calibratedSensor; }
    /** The device fact behind «HEIC 10 бит»: the reason it is unavailable here (Android version, no Main10 / P010 encoder) or null. */
    public SettingsAvailability heic10Unavailable(String reason) { heic10Unavailable = reason; return this; }
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
     * are not chosen (HEIC quality / 10 bit, WebP quality / lossless, the AVIF rows) and «Также сохранять JPEG» while the
     * format is JPEG.
     */
    public boolean hidden(String key) {
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
        // Route selector pref_merge_route (PreferenceKeys.mergeRoute): hybrid (default) or scamhdr. Every shot is one of them.
        boolean autonomous=text("pref_merge_route","hybrid").equals("scamhdr");
        String mosaicMode=text("pref_vivo_nice_mosaic","off");
        boolean niceMosaic=!mosaicMode.equals("off") && autonomous;
        boolean neuralMosaic=niceMosaic && mosaicMode.startsWith("neural");
        boolean hybrid=!autonomous;
        // The ARK tone (ArkCore) and its sharpening are shared by both routes.
        boolean arkShared = key.startsWith("pref_lmc_hybrid_ark_") || key.equals("pref_lmc_hybrid_sharp_mode");
        if (key.startsWith("pref_lmc_hybrid_") && !hybrid && !arkShared) return Lang.t("Выберите склейку «Hybrid».", "Select the “Hybrid” merge.");
        // The denoise and watermark switches sit on a parent page of these rows (P6), so the rule replaces android:dependency.
        if (key.startsWith("pref_lmc_hybrid_dn_") && !on("pref_lmc_hybrid_denoise", true)) return Lang.t("Включите «Шумоподавление».", "Turn on “Noise reduction”.");
        // P28 RAW CA: the rows under the mode list follow it and the auto switch.
        if (key.startsWith("pref_lmc_hybrid_rawca_") && !key.equals("pref_lmc_hybrid_rawca_mode")) {
            if (text("pref_lmc_hybrid_rawca_mode", "0").equals("0")) return Lang.t("Выберите режим «Коррекция ХА в RAW».", "Select a “RAW CA correction” mode.");
            boolean auto = on("pref_lmc_hybrid_rawca_auto", true);
            if (any(key, "pref_lmc_hybrid_rawca_red", "pref_lmc_hybrid_rawca_blue") && auto) return Lang.t("Только без автоподбора.", "Only when “Auto detect” is off.");
            if (key.equals("pref_lmc_hybrid_rawca_passes") && !auto) return Lang.t("Только с автоподбором.", "Only when “Auto detect” is on.");
        }
        // P29 mosaic merge: the native rows follow the merge list, the split's edge kernel only acts on the split (P35: Tetra takes
        // the native merge too with "Tetra path" at its default, so the row is live only with the split for Quad or for Tetra).
        if (key.startsWith("pref_lmc_hybrid_mosaic_")) {
            boolean nativeMosaic = text("pref_lmc_hybrid_mosaic_path", "1").equals("1"); // P34: the native merge is the default
            if (key.equals("pref_lmc_hybrid_mosaic_edge_scale") && nativeMosaic && !text("pref_lmc_hybrid_mosaic_tetra", "1").equals("0")) // P35: T1 default
                return Lang.t("Только для разбиения на подкадры: Quad и Tetra идут нативной склейкой со своим «Ядром поперёк краёв».", "Subframe split only: Quad and Tetra both take the native merge with its own “Kernel across edges”.");
            if (any(key, "pref_lmc_hybrid_mosaic_window", "pref_lmc_hybrid_mosaic_window_full", "pref_lmc_hybrid_mosaic_kernel_scale",
                    "pref_lmc_hybrid_mosaic_native_edge_scale", "pref_lmc_hybrid_mosaic_kernel_g", "pref_lmc_hybrid_mosaic_kernel_rb",
                    "pref_lmc_hybrid_mosaic_chroma_fill", "pref_lmc_hybrid_mosaic_fill_support", "pref_lmc_hybrid_mosaic_tetra",
                    "pref_lmc_hybrid_mosaic_native_flat_scale", "pref_lmc_hybrid_mosaic_native_clamp") && !nativeMosaic)
                return Lang.t("Только для «Нативная мозаика» в «Склейка мозаики».", "Only with “Native mosaic” in “Mosaic merge”.");
            if (key.equals("pref_lmc_hybrid_mosaic_fill_support") && text("pref_lmc_hybrid_mosaic_chroma_fill", "0").equals("0"))
                return Lang.t("Только с добором ArkCam.", "Only with the ArkCam fill.");
        }
        if (key.startsWith("pref_watermark_") && !on("pref_show_watermark_key", true)) return Lang.t("Включите водяной знак.", "Turn on the watermark.");
        // «Формат фото»: Ultra HDR and the JPEG quality act on a JPEG, which a HEIC / WebP / AVIF shot writes only with «Также сохранять JPEG».
        if (!writesJpeg()) {
            if (key.equals("pref_ultrahdr_key"))
                return Lang.t("Только для JPEG: включите «Также сохранять JPEG» — Ultra HDR будет в нём.",
                        "JPEG only: turn on “Also save a JPEG” to get Ultra HDR in that JPEG.");
            if (key.equals("pref_jpeg_quality"))
                return Lang.t("Для JPEG: выберите формат JPEG или включите «Также сохранять JPEG».",
                        "For JPEG: choose the JPEG format or turn on “Also save a JPEG”.");
        }
        // «HEIC 10 бит» needs Android 13 and an HEVC Main10 encoder with P010 input; without them the shot is an 8-bit HEIC.
        if (key.equals("pref_heic_10bit") && heic10Unavailable != null) return heic10Unavailable;
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
        if (key.startsWith("pref_vivo_nice_") && !autonomous)
            return Lang.t("Выберите склейку «SCAM HDR».", "Select the “SCAM HDR” merge.");
        // SCAM HDR mosaic «neural» / «neural_sabre»: tuning of the Quad 2x2 and HexQuad networks.
        if ((key.startsWith("quad2x2_") || key.startsWith("hexquad_")) && !key.endsWith("screen")) {
            if (!neuralMosaic) return Lang.t("Используется в SCAM HDR с мозаикой «Нейросеть» (модули ISZ).", "Used in SCAM HDR with a “Neural remosaic” mosaic mode (ISZ modules).");
            String p = key.startsWith("quad2x2_") ? "quad2x2_" : "hexquad_";
            boolean auto = on(p + "auto_iso", false);
            if (any(key, p + "luma", p + "chroma") && auto) return Lang.t("Сила задаётся ниже по ISO. Для ручной настройки выключите автоматику.", "The strength is set by ISO below. Turn off the auto mode to set it by hand.");
            if (key.startsWith(p + "iso_") && !auto) return Lang.t("Включите «Люма и хрома по ISO».", "Turn on “Luma and chroma by ISO”.");
        }
        // GPU remosaic of SCAM HDR's mosaic modes (S / ES / L always, N in «SCAMERA» / «Sabre»).
        if (key.startsWith("pref_remosaic_") && !niceMosaic) return Lang.t("Используется в SCAM HDR с мозаикой Quad / Tetra (модули ISZ).", "Used in SCAM HDR with a Quad / Tetra mosaic (ISZ modules).");
        if (key.equals("pref_tetra_response_key") && !(niceMosaic && (mosaicMode.equals("detail") || mosaicMode.startsWith("neural"))))
            return Lang.t("Коррекция для мозаики «Tetra Detail» и «Нейросеть» SCAM HDR.", "Correction for the SCAM HDR “Tetra Detail” and “Neural remosaic” mosaic modes.");
        if (any(key,"scamera_quad_bayer_mode","scamera_quad_dng_metadata") && !on("scamera_quad_bayer_enabled", false)) return Lang.t("Включите обработку Quad Bayer.", "Turn on Quad Bayer handling.");
        if (key.startsWith("pref_sharp_")) {
            String master = key.contains("deconv") ? "pref_sharp_deconv_enabled_key"
                    : key.contains("micro") ? "pref_sharp_micro_enabled_key" : "pref_sharp_usm_enabled_key";
            if (!key.equals(master) && !on(master, master.contains("usm"))) return Lang.t("Включите соответствующий алгоритм резкости.", "Turn on the matching sharpening algorithm.");
            if (any(key,"pref_sharp_edges_radius_key","pref_sharp_edges_tolerance_key") && !on("pref_sharp_edges_only_key",false)) return Lang.t("Включите режим «Только края».", "Turn on the “Edges only” mode.");
            if (key.equals("pref_sharp_halo_amount_key") && !on("pref_sharp_halo_control_key",false)) return Lang.t("Включите контроль ореолов Unsharp Mask.", "Turn on Unsharp Mask halo control.");
        }
        if (key.equals("pref_show_gradient_key") && text("pref_theme_accent_key","default").equals("eszdman")) return Lang.t("Оформление задаётся выбранной темой.", "The look is set by the selected theme.");
        return null;
    }
}
