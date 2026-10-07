package com.particlesdevs.photoncamera.settings;

import com.particlesdevs.photoncamera.util.Lang;

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
