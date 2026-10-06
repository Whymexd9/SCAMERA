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
        if(key.startsWith("pref_vivo_hdr_") && !autonomous) return "Выберите склейку «SCAM HDR».";
        // SCAM HDR mosaic «neural» / «neural_sabre»: tuning of the Quad 2x2 and HexQuad networks.
        if ((key.startsWith("quad2x2_") || key.startsWith("hexquad_")) && !key.endsWith("screen")) {
            if (!neuralMosaic) return "Используется в SCAM HDR с мозаикой «Нейросеть» (модули ISZ).";
            String p = key.startsWith("quad2x2_") ? "quad2x2_" : "hexquad_";
            boolean auto = on(p + "auto_iso", false);
            if (any(key, p + "luma", p + "chroma") && auto) return "Сила задаётся ниже по ISO. Для ручной настройки выключите автоматику.";
            if (key.startsWith(p + "iso_") && !auto) return "Включите «Люма и хрома по ISO».";
        }
        // GPU remosaic of SCAM HDR's mosaic modes (S / ES / L always, N in «SCAMERA» / «Sabre»).
        if (key.startsWith("pref_remosaic_") && !niceMosaic) return "Используется в SCAM HDR с мозаикой Quad / Tetra (модули ISZ).";
        if (key.equals("pref_tetra_response_key") && !(niceMosaic && (mosaicMode.equals("detail") || mosaicMode.startsWith("neural"))))
            return "Коррекция для мозаики «Tetra Detail» и «Нейросеть» SCAM HDR.";
        if (any(key,"scamera_quad_bayer_mode","scamera_quad_dng_metadata") && !on("scamera_quad_bayer_enabled", false)) return "Включите обработку Quad Bayer.";
        if (key.startsWith("pref_sharp_")) {
            String master = key.contains("deconv") ? "pref_sharp_deconv_enabled_key"
                    : key.contains("micro") ? "pref_sharp_micro_enabled_key" : "pref_sharp_usm_enabled_key";
            if (!key.equals(master) && !on(master, master.contains("usm"))) return "Включите соответствующий алгоритм резкости.";
            if (any(key,"pref_sharp_edges_radius_key","pref_sharp_edges_tolerance_key") && !on("pref_sharp_edges_only_key",false)) return "Включите режим «Только края».";
            if (key.equals("pref_sharp_halo_amount_key") && !on("pref_sharp_halo_control_key",false)) return "Включите контроль ореолов Unsharp Mask.";
        }
        if (key.equals("pref_show_gradient_key") && text("pref_theme_accent_key","default").equals("eszdman")) return "Оформление задаётся выбранной темой.";
        return null;
    }
}
