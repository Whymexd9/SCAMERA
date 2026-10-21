package com.particlesdevs.photoncamera.settings;

import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every scam_dev.txt line the app reads that has no row in the settings menu, for the «Для разработчика» page (owner,
 * 2026-10-13: «все скрытые настройки которых нет у нас в меню, но есть в scam_dev.txt выведи и в дальнейшем продолжай
 * выводить в группу настроек «Для разработчика»»). The page edits scam_dev.txt itself (ScamDevFile), so a line works the
 * same whether it was typed into the file or set on the page.
 * <p>
 * DevKeysCoverageTest scans the sources: a new scamDevSwitch / scamDevNumber / scamDevValue / scamDevOverrides key, or a new
 * hybrid tuning key without a pref_scam_hybrid_* row, fails it until it is listed here (or in {@link #MENU_BACKED}).
 */
public final class DevKeys {
    public enum Kind { SWITCH, NUMBER }

    /** One line: its group, key, kind, default (as the code uses it; null = the code decides, e.g. per phone) and meaning. */
    public static final class Key {
        public final String group, key, text;
        public final Kind kind;
        public final String def;

        Key(String group, String key, Kind kind, String def, String text) {
            this.group = group;
            this.key = key;
            this.kind = kind;
            this.def = def;
            this.text = text;
        }

        public boolean switchDefault() {
            return def != null && !"0".equals(def);
        }
    }

    /** scam_dev keys whose value is a menu setting as well (the line only overrides it for an A/B). */
    public static final Set<String> MENU_BACKED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "hybrid", "hybrid_output", "hybrid_downsampler", "hybrid_profile", "hybrid_motion_boost",
            "keep_burst", // P191: «Сохранять RAW-серию» in Diagnostics
            "fullres_zsl"))); // P266: «50 Мп: ZSL» in the FULLRES rows - one switch, not a second in «Для разработчика»

    /**
     * Lines of the merge and processing groups the lab («Гайд и лаборатория») does not offer: they change the speed, a priority
     * or the log, not the photo; or the saved burst and its Parameters fixed them at the shot (hybrid_isp_ccm: the colour
     * matrix of the Parameters, hybrid_clip_flags: a flag of the burst); or only the DNG reads them; or nothing does any more.
     */
    static final Set<String> NOT_IN_LAB = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "post_priority", "gpu_low_priority", "jpeg_threads", "rgba_upload", "gl_warm_up", "release_async", "worker_watchdog",
            "hybrid_la_threads", "hybrid_la_stream", "hybrid_la_scam", "hybrid_rgb_half", "worker_gpu_normal_priority",
            "isp_preview_off_always", "hybrid_isp_ccm", "hybrid_clip_flags", "dng_colour_jpeg",
            // P306: no native Tetra merge is a night shot any more, nothing reads its night point
            "hybrid_mosaic_tetra_night_kernel_scale", "hybrid_mosaic_tetra_night_edge_scale", "hybrid_mosaic_tetra_night_flat_scale")));

    private static List<Key> all;
    /** The merge and processing group names of {@link #all} as it was built. */
    private static String builtMerge, builtPost;

    private DevKeys() {}

    private static String mergeGroup() { return Lang.t("Склейка Hybrid (воркер)", "Hybrid merge (worker)"); }
    private static String postGroup() { return Lang.t("Обработка", "Processing"); }

    /** The line {@code key} of {@link #all()}, null when the page has none. */
    public static Key find(String key) {
        for (Key k : all()) if (k.key.equals(key)) return k;
        return null;
    }

    /**
     * The lines that change the photo of a saved burst (owner, 2026-10-21: «в лаборатории у нас не все пункты есть»): the merge
     * and processing groups without {@link #NOT_IN_LAB}, in the page's order, for the lab's «Для разработчика».
     */
    public static List<Key> photo() {
        final List<Key> keys = all();
        // the group names {@link #all} was built with (the language may have changed since: it is built once)
        final String merge, post;
        synchronized (DevKeys.class) { merge = builtMerge; post = builtPost; }
        List<Key> out = new ArrayList<>();
        for (Key k : keys)
            if ((k.group.equals(merge) || k.group.equals(post)) && !NOT_IN_LAB.contains(k.key)) out.add(k);
        return out;
    }

    private static Key s(String g, String key, boolean def, String text) { return new Key(g, key, Kind.SWITCH, def ? "1" : "0", text); }
    private static Key sAuto(String g, String key, String text) { return new Key(g, key, Kind.SWITCH, null, text); }
    private static Key n(String g, String key, String def, String text) { return new Key(g, key, Kind.NUMBER, def, text); }

    public static synchronized List<Key> all() {
        if (all != null) return all;
        final String cap = Lang.t("Захват и сессия", "Capture and session");
        final String vf = Lang.t("Видоискатель", "Viewfinder");
        final String merge = mergeGroup();
        final String post = postGroup();
        final String xiaomi = Lang.t("Xiaomi 17 Ultra (теле)", "Xiaomi 17 Ultra (tele)");
        final String xiaomi15 = Lang.t("Xiaomi 15 Ultra (DCG)", "Xiaomi 15 Ultra (DCG)");
        final String vivo = Lang.t("vivo", "vivo");
        final String pixel = Lang.t("Pixel 10 Pro / Pro XL", "Pixel 10 Pro / Pro XL");
        final String diag = Lang.t("Диагностика и A/B", "Diagnostics and A/B");
        List<Key> k = new ArrayList<>();
        // capture and session
        k.add(s(cap, "zsl_memfd_ring", true, Lang.t("ZSL-кольцо в общей памяти (memfd): кадры копируются сразу, камере возвращаются буферы. Выкл — старое кольцо из буферов камеры", "ZSL ring in shared memory (memfd): frames copied on arrival, buffers go back to the camera. Off: the old ring of camera buffers")));
        k.add(n(cap, "vf_min_fps", String.valueOf(com.particlesdevs.photoncamera.capture.ViewfinderFps.DEFAULT_FLOOR),
                Lang.t("Видоискатель не медленнее N к/с (автоматический диапазон AE; 0 — как разрешает камера)",
                        "Viewfinder not slower than N fps (the automatic AE range; 0: as low as the camera allows)")));
        k.add(n(cap, "zsl_reader", "20", Lang.t("Буферов RAW-ридера при memfd-кольце (не меньше 16)", "RAW reader buffers with the memfd ring (at least 16)")));
        k.add(s(cap, "zsl_steadiest", true, Lang.t("Без OIS: из кольца берутся самые устойчивые по гироскопу кадры. Выкл — самые новые", "Without OIS: the steadiest ring frames by the gyro. Off: the newest")));
        k.add(n(cap, "ae_restore", "1", Lang.t("Кадр после серии, возвращающий экспозицию превью: 0 — нет, 1 — ручной кадр, 2 — AE ON + lock", "Frame after the series restoring the preview exposure: 0 none, 1 manual frame, 2 AE on + lock")));
        k.add(n(cap, "preview_lead", "1", Lang.t("Кадров превью перед серией после сброса очереди HAL", "Preview frames ahead of the series after the HAL queue flush")));
        k.add(n(cap, "stab_rearm", null, Lang.t("Режим повторного включения стабилизации после снимка (по умолчанию выбирает приложение)", "Stabilisation re-arm mode after a shot (default: chosen by the app)")));
        k.add(s(cap, "series_viewfinder", true, Lang.t("Серия снимка тоже идёт в видоискатель (без замирания при съёмке)", "The shot series feeds the viewfinder too (no freeze at the shot)")));
        k.add(s(cap, "ois_force", true, Lang.t("Запрос OIS на всех камерах, даже если HAL его не объявляет (OPPO/OnePlus)", "OIS requested on every camera even when the HAL does not list it (OPPO / OnePlus)")));
        k.add(sAuto(cap, "force_stab", Lang.t("Принудительная стабилизация серии (по умолчанию — на телефонах из списка)", "Forced stabilisation of the series (default: on the listed phones)")));
        k.add(sAuto(cap, "scam_stock_profile", Lang.t("Стоковый профиль превью vivo (по умолчанию — на известных vivo)", "The vivo stock preview profile (default: on known vivo phones)")));
        k.add(s(cap, "force_stab_noflush", true, Lang.t("Принудительная стабилизация: снимок без сброса очереди HAL", "Forced stabilisation: the shot does not flush the HAL queue")));
        k.add(s(cap, "force_stab_rawonly", true, Lang.t("Принудительная стабилизация: добавленные кадры только в RAW, не в видоискатель", "Forced stabilisation: the added frames go to RAW only, not to the viewfinder")));
        k.add(s(cap, "force_stab_eis", false, Lang.t("Принудительная стабилизация: ключ EIS превью vivo", "Forced stabilisation: the vivo preview EIS key")));
        k.add(s(cap, "scam_preview_eis", true, Lang.t("EIS превью в профиле vivo", "Preview EIS in the vivo profile")));
        k.add(s(cap, "hal_motion", true, Lang.t("Сигнал движения от HAL ограничивает выдержку кадров Shasta", "The HAL's motion flag caps the Shasta frames' shutter")));
        k.add(s(cap, "motion_hal_keys", true, Lang.t("«Движение»: ключи HAL для движения (AI shutter MTK, fastmotion OPPO, motion AF vivo)", "«Motion»: the HAL motion keys (MTK AI shutter, OPPO fastmotion, vivo motion AF)")));
        k.add(s(cap, "long_exposure_keys", true, Lang.t("Длинные кадры дольше предела HAL: ключи Qualcomm disableFPSLimits / overrideSensorFrameDuration", "Frames longer than the HAL limit: Qualcomm disableFPSLimits / overrideSensorFrameDuration")));
        k.add(s(cap, "ideal_raw", false, Lang.t("Ключ Qualcomm EnableIdealRAW (опыт)", "Qualcomm EnableIdealRAW key (experiment)")));
        k.add(s(cap, "zoom_override", true, Lang.t("CONTROL_SETTINGS_OVERRIDE ZOOM в видоискателе (быстрее зум), где камера его объявляет", "CONTROL_SETTINGS_OVERRIDE ZOOM in the viewfinder (faster zoom) where listed")));
        k.add(s(cap, "cropped_raw", true, Lang.t("ISZ через поток CROPPED_RAW (Galaxy S24 Ultra, Xiaomi 17 Ultra)", "ISZ through the CROPPED_RAW stream (Galaxy S24 Ultra, Xiaomi 17 Ultra)")));
        k.add(s(cap, "isz_module", true, Lang.t("Модуль «2×» для камер с CROPPED_RAW", "The «2×» module for cameras with CROPPED_RAW")));
        k.add(s(cap, "raw_wide", true, Lang.t("RAW шире заявленного белого (14 бит DCG) берётся по своим значениям", "A RAW wider than the declared white (14-bit DCG) is taken at its own range")));
        k.add(s(cap, "vivo_qcom_roi", true, Lang.t("X300 Ultra: модули ISZ без ремозаика и кнопка DCG (forceSensorMode в сессии)", "X300 Ultra: ISZ modules without remosaic and the DCG button (session forceSensorMode)")));
        k.add(s(cap, "sensor_mode_pick", true, Lang.t("Режим сенсора модуля из таблицы камеры (SensorModeTable)", "The module's sensor mode from the camera's table (SensorModeTable)")));
        k.add(s(cap, "sensor_mode_series", false, Lang.t("OPPO / OnePlus / realme: кадры после затвора и в выбранном режиме сенсора (иначе снимок только из буфера)", "OPPO / OnePlus / realme: frames after the shutter in a picked sensor mode too (otherwise the shot is taken from the ring only)")));
        k.add(s(cap, "sensor_mode_pick_lease", true, Lang.t("vivo: модуль с режимом сенсора открывает камеру с арендой vivo SDK", "vivo: a module with a sensor mode opens the camera under the vivo SDK lease")));
        k.add(s(cap, "vivo_native_isz_lease", true, Lang.t("X200 Ultra (свой пакет): нативный модуль ISZ открывает камеру с арендой vivo SDK", "X200 Ultra (own package): a native ISZ module opens the camera under the vivo SDK lease")));
        k.add(s(cap, "vivo_qcom_isz_lease", true, Lang.t("X300 Ultra: модуль ISZ открывает камеру с арендой vivo SDK (VCF 33 = 2, SDK RAW 40 = 1)", "X300 Ultra: an ISZ module opens the camera under the vivo SDK lease (VCF 33 = 2, SDK RAW 40 = 1)")));
        k.add(sAuto(cap, "vivo_qcom_isz_cropped_raw", Lang.t("X300 Ultra: RAW-поток модуля ISZ: 1 — с CROPPED_RAW (как в сборке 30912), 0 — без; без строки приложение пробует без, затем с", "X300 Ultra: the ISZ module's RAW stream: 1 = with CROPPED_RAW (as in build 30912), 0 = without; no line: the app tries without, then with")));
        k.add(s(cap, "mosaic_awb", true, Lang.t("ISZ без ремозаика: баланс белого по кадру на кривой камеры, а не от камеры", "ISZ without remosaic: white balance from the frame on the camera curve, not the camera's")));
        // P212f: the mosaic white balance's locus (W5), robust statistic (W4) and ISP teacher (W5, log only)
        k.add(s(cap, "mosaic_awb_locus", true, Lang.t("Баланс белого мозаики: гладкая кривая камеры по точкам с весами, с продолжением за края и кривой из матриц DNG, пока точек мало. Выкл — ломаная P121 с обрезкой на краях",
                "Mosaic white balance: a smooth weighted curve of the camera, extended beyond its ends, from the DNG matrices while it has few points. Off: the P121 polyline clamped at its ends")));
        k.add(s(cap, "mosaic_awb_v2", false, Lang.t("Баланс белого мозаики: устойчивая оценка (клип по пикселям, веса по шуму, виньетирование, кроп снимка, до 3 кадров, своя точность) вместо P121. Выкл — только в лог рядом с P121",
                "Mosaic white balance: the robust estimate (per-site clipping, noise weights, shading, the photo's crop, up to 3 frames, its own confidence) instead of P121. Off: only logged beside P121")));
        k.add(s(cap, "mosaic_awb_teacher", true, Lang.t("Баланс белого мозаики: «учитель» — в обычном режиме запоминает, какую нейтраль ISP выбирает для нашей оценки кадра; только сбор и лог, на фото не влияет",
                "Mosaic white balance teacher: in the plain mode it learns which neutral the ISP picks for our estimate of the frame; collected and logged only, never applied to a photo")));
        // P212g: whose neutral a vivo mosaic photo takes (W6) and the plain-session colour matrices of mosaic photos (W7)
        k.add(s(cap, "mosaic_awb_resolver", true, Lang.t("vivo FULLRES: баланс белого камеры, если он установился и лежит на кривой камеры; 200 Мп без своего видоискателя — баланс видоискателя обычного режима за секунду до нажатия. Выкл — оценка по кадру (P121) на всех маршрутах",
                "vivo FULLRES: the camera's white balance when it has converged and lies on the camera's curve; 200 MP without its own viewfinder: the normal-mode viewfinder's balance of the second before the press. Off: the frame estimate (P121) on every route")));
        k.add(n(cap, "mosaic_ccm_table", "1", Lang.t("Снимки мозаики: цветовая матрица ISP из обычного режима этой камеры при том же свете вместо общих матриц DNG. 1: FULLRES, 2: также ISZ 12 Мп, 0: матрицы DNG",
                "Mosaic shots: the ISP's colour matrix of this camera's plain mode at the same light instead of the generic DNG matrices. 1: FULLRES, 2: the 12 MP ISZ modules too, 0: the DNG matrices")));
        k.add(s(cap, "oppo_cct_anchor", true, Lang.t("OPPO X7 Ultra: точка 6500 K для 6× и фронтальной камеры по меткам AWB 6000–7000 K обычного режима. Выкл — −0.6, как раньше",
                "OPPO X7 Ultra: the 6500 K point of the 6x and front cameras from the plain mode's 6000-7000 K AWB labels. Off: -0.6 as before")));
        k.add(s(cap, "oplus_ccm", true, Lang.t("OPPO: цветовая матрица com.oplus.custom.ccm вместо единичной", "OPPO: the com.oplus.custom.ccm colour matrix instead of an identity one")));
        k.add(s(cap, "fov_check", true, Lang.t("Замер реального поля зрения вендорных модулей", "Measure the real field of view of vendor modules")));
        k.add(s(cap, "hybrid_fast_capture", true, Lang.t("Сброс очереди HAL перед серией (задержка затвора ~0.4 → ~0.15 с)", "Flush the HAL request queue before the series (shutter lag ~0.4 -> ~0.15 s)")));
        // viewfinder
        k.add(s(vf, "mosaic_preview", true, Lang.t("Модуль, измеренный как мозаика, сразу показывает RAW-видоискатель", "A module measured as a mosaic starts on the RAW viewfinder")));
        k.add(n(vf, "vf_priority", "1", Lang.t("Высокий приоритет потока видоискателя (0 — обычный)", "High priority of the viewfinder thread (0: normal)")));
        // P314: the photo's tone per pixel by default (one pass, the AE on the CPU), the guided-filter chain of P301 as 2
        k.add(s(vf, "vf_black_floor", true, Lang.t("RAW-видоискатель: чёрный по самому кадру, когда поток пришёл без чёрного пьедестала (OPPO ISZ, режим сенсора)", "RAW viewfinder: the black from the frame itself when the stream lost its black pedestal (OPPO ISZ, a sensor mode)")));
        k.add(n(vf, "vf_ark", "1", Lang.t("RAW-видоискатель: тон ARK итогового фото — 1: по каждому пикселю (быстро), 2: локально, как в фото (направляемый фильтр, тяжелее, замедляет обработку), 0: собственный вид видоискателя",
                "RAW viewfinder: the photo's ARK tone - 1: per pixel (fast), 2: local as in the photo (guided filter, heavier, slows processing down), 0: the viewfinder's own look")));
        // P314: the default (medium) GPU priority, the processing contexts stay low; 1 = P90's high priority
        k.add(n(vf, "vf_gpu_priority", "0", Lang.t("Приоритет GPU-контекста видоискателя: 1 — высокий, 0 — обычный (меньше мешает обработке)",
                "Viewfinder GPU context priority: 1 high, 0 normal (gets less in the way of processing)")));
        // P212h (A6 = M11): the FULLRES viewfinder predicts the photo
        k.add(n(vf, "vf_native_gain", "1", Lang.t("OPPO FULLRES: усиление видоискателя — 1: только на экспозицию, которую режим не даёт (нехватка AE больше 0.3 EV), 2: замер видоискателя, пока экспозиция не задана вручную, 0: без усиления",
                "OPPO FULLRES viewfinder gain - 1: only for the exposure the mode cannot give (an AE deficit above 0.3 EV), 2: the viewfinder's meter unless the exposure is hand-set, 0: none")));
        k.add(s(vf, "vf_native_ccm", true, Lang.t("FULLRES: цветовая матрица фото в видоискателе вместо единичной (OPPO X7 Ultra: тюнинг по цветовой температуре; другие OPPO и vivo: матрица обычного режима при этом свете или ISP). Выкл — единичная",
                "FULLRES: the photo's colour matrix on the viewfinder instead of the identity (OPPO X7 Ultra: the tuning at the light's colour temperature; other OPPO and vivo: the plain mode's matrix at that light or the ISP's). Off: the identity")));
        k.add(s(vf, "vf_raw_scene_luma", true, Lang.t("Приоритет выдержки / ISO: яркость сцены из RAW-видоискателя (без неё в FULLRES второй параметр не двигался). Выкл — только из видоискателя ISP",
                "Shutter / ISO priority: the scene brightness from the RAW viewfinder (without it the other value never moved in FULLRES). Off: from the ISP viewfinder only")));
        // merge (worker)
        k.add(s(merge, "hybrid_rgb_half", true, Lang.t("RGB от воркера в half float (меньше данных). Выкл — float32", "Worker RGB as half floats (less data). Off: float32")));
        k.add(n(merge, "hybrid_s61_mode", null, Lang.t("Режим ковариации Sabre 6.1 (6 — от базового кадра, быстрее)", "Sabre 6.1 covariance mode (6: from the base frame, faster)")));
        k.add(n(merge, "hybrid_s61_day_tensor_noise", null, Lang.t("Sabre 6.1 днём: шум тензора", "Sabre 6.1 by day: tensor noise")));
        k.add(n(merge, "hybrid_s61_day_gd_noise", null, Lang.t("Sabre 6.1 днём: шум градиента", "Sabre 6.1 by day: gradient noise")));
        k.add(n(merge, "hybrid_s61_tensor_noise", null, Lang.t("Sabre 6.1: шум тензора", "Sabre 6.1: tensor noise")));
        k.add(n(merge, "hybrid_s61_gd_noise", null, Lang.t("Sabre 6.1: шум градиента", "Sabre 6.1: gradient noise")));
        k.add(n(merge, "hybrid_hot_frames", null, Lang.t("Горячие пиксели: кадров для поиска", "Hot pixels: frames to search")));
        k.add(n(merge, "hybrid_hot_cross", null, Lang.t("Горячие пиксели: порог креста", "Hot pixels: cross threshold")));
        k.add(n(merge, "hybrid_hot_max_level", null, Lang.t("Горячие пиксели: предел уровня", "Hot pixels: level limit")));
        k.add(n(merge, "hybrid_hot_cap", null, Lang.t("Горячие пиксели: не больше мест на Мп полосы (0 — без предела)", "Hot pixels: at most this many sites per MP of a strip (0: no cap)")));
        k.add(n(merge, "hybrid_bento_invalid", null, Lang.t("Bento: порог негодных клеток", "Bento: invalid cell threshold")));
        // P310 settings cleanup: «Кадры светов: вес» left the menu (the plain merge blends the highlight frames after it, their
        // weight does not enter; the colour-block merge's ramp fixes their share)
        k.add(n(merge, "hybrid_bento_weight", "1", Lang.t("Bento: вес кадров светов в склейке (обычный Байер — только без наложения после склейки; Quad / Tetra — в склейке без заполнения после неё, с заполнением — через маску, по которой оно идёт)",
                "Bento: weight of the highlight frames in the merge (plain Bayer: only with the blend after the merge off; Quad / Tetra: inside the merge with the fill off, with the fill on through the mask it fills by)")));
        // P310: the highlight frames blended after the merge (plain Bayer; on by default, off = the P309 mixing in the merge)
        k.add(s(merge, "hybrid_bento_blend", true, Lang.t("Bento: кадры светов накладываются после склейки по областям (выкл — смешивание внутри склейки)",
                "Bento: highlight frames blended after the merge by region (off: mixed inside the merge)")));
        // P313: the native Quad / Tetra merge keeps the N frames in Bento's cells, the highlight frames fill only what they clipped
        k.add(s(merge, "hybrid_us_fill", true, Lang.t("Bento (Quad / Tetra): кадры светов заполняют только пересвет основных кадров (выкл — по маске внутри склейки); только с полным окном, у Quad и Tetra T2 — с окном до 3",
                "Bento (Quad / Tetra): highlight frames fill only what the main frames clipped (off: by the mask inside the merge); only with the full window, for Quad and Tetra T2 a window up to 3")));
        // P309: the soft transition's levels and reach (its width is the row «Кадры светов: мягкий переход»); since P310 only for the
        // colour-block (Quad / Tetra) merge, the plain merge blends instead
        k.add(n(merge, "hybrid_bento_ramp_lo", null, Lang.t("Bento: уровень базы, где начинается переход", "Bento: base level where the transition starts")));
        k.add(n(merge, "hybrid_bento_ramp_hi", null, Lang.t("Bento: уровень базы, где тёмный кадр уже один", "Bento: base level where the dark frame is alone")));
        k.add(n(merge, "hybrid_bento_ramp_reach", null, Lang.t("Bento: радиус перехода от пересвета, клеток", "Bento: transition reach from the clip, cells")));
        k.add(n(merge, "hybrid_mosaic_block", null, Lang.t("Мозаика: блок цвета (1, 2, 4) вместо измеренного", "Mosaic: colour block (1, 2, 4) instead of the measured one")));
        k.add(n(merge, "hybrid_mosaic_share", null, Lang.t("Мозаика: доля голосов для решения", "Mosaic: vote share to decide")));
        k.add(n(merge, "hybrid_mosaic_native_night_kernel_scale", null, Lang.t("Нативная мозаика ночью: масштаб ядра", "Native mosaic at night: kernel scale")));
        k.add(n(merge, "hybrid_mosaic_native_night_edge_scale", null, Lang.t("Нативная мозаика ночью: масштаб на краях", "Native mosaic at night: edge scale")));
        k.add(n(merge, "hybrid_mosaic_tetra_night_kernel_scale", null, Lang.t("Tetra ночью: масштаб ядра (не действует: Tetra снимается днём, P306)", "Tetra at night: kernel scale (no effect: Tetra is shot by day, P306)")));
        k.add(n(merge, "hybrid_mosaic_tetra_night_edge_scale", null, Lang.t("Tetra ночью: масштаб на краях (не действует: Tetra снимается днём, P306)", "Tetra at night: edge scale (no effect: Tetra is shot by day, P306)")));
        k.add(n(merge, "hybrid_mosaic_tetra_night_flat_scale", null, Lang.t("Tetra ночью: масштаб на ровных местах (не действует: Tetra снимается днём, P306)", "Tetra at night: flat scale (no effect: Tetra is shot by day, P306)")));
        k.add(n(merge, "hybrid_mosaic_tetra_kernel_scale", null, Lang.t("Tetra днём: масштаб ядра", "Tetra by day: kernel scale")));
        k.add(n(merge, "hybrid_gain_measured", null, Lang.t("Измерять усиление кадров (1/0)", "Measure the frames' gain (1/0)")));
        k.add(n(merge, "hybrid_noise_measured", null, Lang.t("Измерять шум и поправлять модель (1/0)", "Measure the noise and correct the model (1/0)")));
        k.add(n(merge, "hybrid_la_threads", null, Lang.t("Потоков локального выравнивания F6", "Threads of the F6 local alignment")));
        k.add(n(merge, "hybrid_la_stream", null, Lang.t("F6 полосами вместе со склейкой (0 — сначала всё поле)", "F6 in bands alongside the merge (0: the whole field first)")));
        k.add(n(merge, "hybrid_la_scam", null, Lang.t("Понижение приоритета потоков F6", "Lower priority of the F6 threads")));
        k.add(s(merge, "hybrid_clip_flags", true, Lang.t("Флаги клипа каналов от воркера для восстановления светов", "The worker's channel clip flags for the highlight recovery")));
        // P220: WorkerWatchdog
        k.add(n(merge, "worker_watchdog", "2", Lang.t("Сторож воркера склейки: нет хода дольше max(90 с, 4× самый долгий шаг) — 2: воркер останавливается (без повтора, если он ждал GPU), 1: только строка в логе, 0: выкл (ждать до 14 мин)",
                "Merge worker watchdog: no progress for longer than max(90 s, 4x the slowest step) - 2: the worker is stopped (no retry when it waited for the GPU), 1: a log line only, 0: off (wait up to 14 min)")));
        // processing
        k.add(n(post, "post_priority", null, Lang.t("Приоритет потока обработки (nice; пусто — не менять)", "Priority of the processing thread (nice; empty: unchanged)")));
        k.add(n(post, "gpu_low_priority", "1", Lang.t("Низкий приоритет GPU-контекста обработки (0 — обычный)", "Low priority of the processing GPU context (0: normal)")));
        k.add(s(post, "effmap_real", true, Lang.t("Карта числа кадров для шумоподавления — реальная (выкл — старая)", "Frame-count map for the denoise: the real one (off: the old one)")));
        // P214d: ScamDenoise.lscTerm
        k.add(s(post, "denoise_lsc", true, Lang.t("Шумоподавление сильнее к углам кадра по коррекции виньетирования (g² карты затенения объектива)", "Denoise harder towards the frame corners after the vignetting correction (g² of the lens-shading map)")));
        k.add(n(post, "denoise_lsc_max", "4", Lang.t("Шумоподавление к углам: предел множителя дисперсии (1–16; 4 — шум в углах считается вдвое сильнее)", "Corner denoise: cap of the variance multiplier (1-16; 4: the corner noise counts twice as strong)")));
        // P214e: ArkLumaSharpen.smoothedSource (R1); P214z: not above 100 MP (r1Fits)
        k.add(s(post, "sharpen_r1", true, Lang.t("Резкость мозаичных снимков (ISZ Quad/Tetra, FULLRES до 100 Мп) по яркости, сглаженной 3×3: решётка мозаики почти не усиливается. Выкл — резкость как у снимков Bayer",
                "Sharpening of mosaic shots (ISZ Quad / Tetra, FULLRES up to 100 MP) on the 3x3-smoothed luma: the mosaic lattice is hardly lifted. Off: the sharpening of Bayer shots")));
        // P212h (A5 = M5): the gain clamp of the ARK exposure from the merged image's noise
        k.add(n(post, "ae_clamp_stack", "1", Lang.t("Яркость фото: предел усиления по шуму — по числу склеенных кадров N (карта склейки, N^0.4). 1: FULLRES, 2: все снимки, 0: по одному кадру",
                "Photo brightness: the noise gain clamp from the merged N frames (the merge's frame map, N^0.4). 1: FULLRES, 2: every shot, 0: one frame")));
        k.add(n(post, "jpeg_threads", null, Lang.t("Потоков кодирования JPEG (1 — один поток, прогрессивный; пусто — все ядра)", "Threads of the JPEG encode (1: one thread, progressive; empty: every core)")));
        k.add(sAuto(post, "rgba_upload", Lang.t("Загрузка RGB в GPU как RGBA (по умолчанию решает приложение)", "RGB uploaded to the GPU as RGBA (default: chosen by the app)")));
        k.add(n(post, "hybrid_isp_ccm", "1", Lang.t("vivo: цветовая матрица ISP по кадру (0 — матрицы DNG)", "vivo: the ISP colour matrix of the frame (0: the DNG matrices)")));
        k.add(n(post, "hybrid_dn_dark_chroma_hi", "4", Lang.t("Шумодав: цвет в тенях, верхний порог (x1e-4)", "Denoise: colour in the shadows, upper threshold (x1e-4)")));
        k.add(n(post, "hybrid_dn_dark_chroma_lo", "1.5", Lang.t("Шумодав: цвет в тенях, нижний порог (x1e-4)", "Denoise: colour in the shadows, lower threshold (x1e-4)")));
        k.add(n(post, "hybrid_dn_huge_map", "4.3", Lang.t("Шумодав 200 МП: сила (карта силы; 1 — как при всех склеенных кадрах)", "Denoise 200 MP: strength (strength map; 1: as with every frame merged)")));
        k.add(n(post, "hybrid_dn_huge_fine", "2", Lang.t("Шумодав 200 МП: срез мелкого шума яркости, в сигмах (0 — выкл.)", "Denoise 200 MP: fine luma noise coring, in sigmas (0: off)")));
        k.add(n(post, "hybrid_dn_dark_chroma_noise", "0.35", Lang.t("Шумодав: цвет в тенях, доля шума", "Denoise: colour in the shadows, noise share")));
        k.add(n(post, "hybrid_ark_hr_mix", "0.5", Lang.t("Тон, света Bento выше 4x: пик к p99.9 (доля)", "Tone, Bento highlights above 4x: peak towards p99.9 (share)")));
        k.add(n(post, "hybrid_ark_weight_center_hr", "0.75", Lang.t("Тон с запасом Bento: центр весов слияния (без строки — «Смещение центра экспозиции» + 0,15)", "Tone with Bento headroom: fusion weight centre (without the line: “Exposure centre shift” + 0.15)")));
        k.add(n(post, "hybrid_ark_weight_hl_hr", "0.4", Lang.t("Тон с запасом Bento: вес слоя светов (без строки — половина «Вес слоя светов»)", "Tone with Bento headroom: highlight layer weight (without the line: half the “Highlight layer weight”)")));
        k.add(n(post, "hybrid_ark_sat_light_lo", "0.35", Lang.t("Тон с запасом Bento: цветной свет: насыщенность, с которой начинаются веса ядра (нижняя = верхней — выкл.)", "Tone with Bento headroom: coloured light - saturation where the kernel's weights start (lo = hi: off)")));
        k.add(n(post, "hybrid_ark_sat_light_hi", "0.7", Lang.t("Тон с запасом Bento: цветной свет: насыщенность полных весов ядра", "Tone with Bento headroom: coloured light - saturation of the full kernel weights")));
        k.add(n(post, "hybrid_ark_sat_light_level_lo", "0.25", Lang.t("Тон с запасом Bento: цветной свет: уровень света (x AE), с которого начинаются веса ядра", "Tone with Bento headroom: coloured light - light level (x AE) where the kernel's weights start")));
        k.add(n(post, "hybrid_ark_sat_light_level_hi", "0.6", Lang.t("Тон с запасом Bento: цветной свет: уровень света полных весов ядра", "Tone with Bento headroom: coloured light - light level of the full kernel weights")));
        k.add(n(post, "hybrid_ark_fusion_mono", "0.04", Lang.t("Тон: подъём слияния к монотонной огибающей (нет тёмного кольца у лампы)", "Tone: fusion lifted to its monotonic envelope (no dark ring round a lamp)")));
        k.add(n(post, "hybrid_ark_hr_knee", "0.5", Lang.t("Тон, света Bento выше 4x: колено плеча (x верх)", "Tone, Bento highlights above 4x: shoulder knee (x top)")));
        k.add(n(post, "hybrid_ark_hr_room", "0.4", Lang.t("Тон, света Bento выше 4x: запас плеча (x верх)", "Tone, Bento highlights above 4x: shoulder room (x top)")));
        k.add(n(post, "hybrid_ark_hr_detail", "0.35", Lang.t("Тон, света Bento выше 4x: деталь светов", "Tone, Bento highlights above 4x: highlight detail")));
        k.add(n(post, "hybrid_grain_level", "0.0002", Lang.t("Шумодав: уровень оставляемого зерна", "Denoise: level of the grain left")));
        k.add(n(post, "hybrid_grain_min", "0.12", Lang.t("Шумодав: наименьшая доля возвращаемого зерна", "Denoise: least share of the grain put back")));
        k.add(n(post, "hybrid_texture_g0", null, Lang.t("Текстура: усиление полосы 0 (по умолчанию 1.5, на 8 Elite 1.35)", "Texture: gain of band 0 (default 1.5, 1.35 on the 8 Elite)")));
        k.add(n(post, "hybrid_texture_g1", "1.25", Lang.t("Текстура: усиление полосы 1", "Texture: gain of band 1")));
        k.add(n(post, "hybrid_texture_g2", "1.3", Lang.t("Текстура: усиление полосы 2", "Texture: gain of band 2")));
        k.add(n(post, "hybrid_texture_g3", "1.3", Lang.t("Текстура: усиление полосы 3", "Texture: gain of band 3")));
        k.add(n(post, "hybrid_texture_core0", "0.8", Lang.t("Текстура: порог ядра (начало)", "Texture: coring threshold (start)")));
        k.add(n(post, "hybrid_texture_core1", "1.8", Lang.t("Текстура: порог ядра (конец)", "Texture: coring threshold (end)")));
        k.add(n(post, "hybrid_texture_bmax", "0.12", Lang.t("Текстура: предел добавки", "Texture: limit of the boost")));
        // Xiaomi 17 Ultra
        k.add(n(xiaomi, "xiaomi_opmode", String.valueOf(com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.STOCK_OPERATION_MODE), Lang.t("Режим работы логической сессии (36866 = стоковый 0x9002, 0 — обычный)", "Operation mode of the logical session (36866 = stock 0x9002, 0 regular)")));
        k.add(s(xiaomi, "xiaomi_logical", true, Lang.t("Теле через логическую камеру", "Tele through the logical camera")));
        k.add(s(xiaomi, "xiaomi_hal_optics", true, Lang.t("Оптический зум по отчёту HAL", "Optical zoom from the HAL's report")));
        k.add(s(xiaomi, "xiaomi_opt_target", true, Lang.t("Цель оптического зума от HAL", "Optical zoom target from the HAL")));
        k.add(s(xiaomi, "xiaomi_lens_check", true, Lang.t("Проверка, какой объектив реально снимает", "Check which lens really captures")));
        k.add(s(xiaomi, "xiaomi_crop_mode", false, Lang.t("Режим кропа теле (опыт)", "Tele crop mode (experiment)")));
        k.add(s(xiaomi, "xiaomi_isz", false, Lang.t("ISZ теле (опыт)", "Tele ISZ (experiment)")));
        k.add(s(xiaomi, "xiaomi_tele_fallback", false, Lang.t("Запасной путь теле при сбое", "Tele fallback route on failure")));
        // Xiaomi 15 Ultra (P230)
        k.add(s(xiaomi15, "xiaomi_dcg", true, Lang.t("Кнопка DCG 14 бит на основной камере", "The DCG 14-bit button on the main camera")));
        k.add(n(xiaomi15, "xiaomi_dcg_opmode", String.valueOf(com.particlesdevs.photoncamera.capture.XiaomiDcg.OPERATION_MODE), Lang.t("DCG: режим работы сессии (37379 = 0x9203, 0 — без него)", "DCG: session operation mode (37379 = 0x9203, 0 none)")));
        k.add(n(xiaomi15, "xiaomi_dcg_mode", String.valueOf(com.particlesdevs.photoncamera.capture.XiaomiDcg.DCG_MODE), Lang.t("DCG: current_mode сенсора (24; -1 — не задавать)", "DCG: sensor current_mode (24; -1 not set)")));
        k.add(n(xiaomi15, "xiaomi_dcg_fps", String.valueOf(com.particlesdevs.photoncamera.capture.XiaomiDcg.FPS), Lang.t("DCG: кадров в секунду (0 — как в видоискателе)", "DCG: frames per second (0 as the viewfinder)")));
        k.add(n(xiaomi15, "dcg_band_crop", "1", Lang.t("DCG (15 Ultra, X200 Ultra): пустые строки под режимом (0 — не резать, 1 — если пустые, 2 — всегда снизу)", "DCG (15 Ultra, X200 Ultra): empty rows under the mode (0 keep, 1 cut when empty, 2 always the bottom)")));
        // vivo
        k.add(s(vivo, "vivo_sdk_fullres", true, Lang.t("X200 Ultra, основная камера 3: SDK RAW 50 Мп, последовательная съёмка после нажатия, Hybrid", "X200 Ultra, main camera 3: SDK 50 MP RAW, serial post-shutter capture, Hybrid")));
        // P180 / P184: FULLRES without hardware remosaic on the X100 / X300 Ultra - on (the owner tests it), off by these
        // P214j: the route on OnePlus and realme too (their cameras' session key and sensor-mode table, OppoNativeFullRes.family)
        k.add(s(cap, "oppo_fullres", true, Lang.t("OPPO, OnePlus, realme: FULLRES без аппаратного ремозаика (свои AE и баланс белого; режимы — из таблицы камеры)",
                "OPPO, OnePlus, realme: FULLRES without hardware remosaic (own AE and white balance; the modes from the camera's table)")));
        // P212: the own AE of the OPPO native FULLRES session (NativeRawExposure)
        k.add(s(cap, "native_ae_cap", true, Lang.t("OPPO FULLRES, своя AE: ISO не выше того, что режим реально даёт (запомненный предел), один пробный кадр за сессию. Выкл — ISO по объявленному диапазону",
                "OPPO FULLRES own AE: ISO no higher than the mode really gives (the stored cap), one probe frame a session. Off: ISO by the declared range")));
        k.add(s(cap, "native_ae_seed", true, Lang.t("OPPO FULLRES, своя AE: старт с последней установившейся экспозиции камеры или замера обычного режима. Выкл — с последней экспозиции превью",
                "OPPO FULLRES own AE: start from the camera's last settled exposure or a plain-mode sample. Off: from the last preview exposure")));
        k.add(s(cap, "native_ae_smooth", true, Lang.t("OPPO FULLRES, своя AE: сглаживание и гистерезис (без качания у ламп). Выкл — закон P192",
                "OPPO FULLRES own AE: smoothing and hysteresis (no hunting at lamps). Off: the P192 law")));
        k.add(s(cap, "native_ae_meter", true, Lang.t("OPPO FULLRES, своя AE: замер по кадру снимка (кроп, центр, касание, виньетирование, клип по пикселям). Выкл — замер P192 по всему кадру",
                "OPPO FULLRES own AE: metering of the photo's frame (crop, centre, tap, shading, per-site clipping). Off: the P192 whole-frame meter")));
        k.add(s(cap, "native_ae_trace", false, Lang.t("OPPO FULLRES, своя AE: строка лога на каждый кадр (SCAM_AE, раз в секунду)",
                "OPPO FULLRES own AE: a log line for every frame (SCAM_AE, once a second)")));
        // P212e: the OPPO native session's colour at a cold start (OppoPlainState) and its white balance (OppoNativeWb)
        k.add(s(cap, "oppo_plain_store", true, Lang.t("OPPO FULLRES: карта виньетирования, нейтраль и матрица обычного режима запоминаются по камере и берутся при холодном старте сразу в FULLRES. Выкл — только из обычного режима этого запуска",
                "OPPO FULLRES: the plain mode's shading map, neutral and colour matrix are stored per camera and used at a cold start straight into FULLRES. Off: only from this run's plain mode")));
        k.add(s(cap, "oppo_shading_keys", true, Lang.t("OPPO FULLRES: карта виньетирования обычного режима кладётся в кадр в своей ориентации 17×13 (раньше фото читали её как 13×17). Выкл — как в P192",
                "OPPO FULLRES: the plain mode's shading map goes into the frame in its own 17x13 orientation (the photos read it as 13x17 before). Off: as in P192")));
        k.add(s(cap, "oppo_wb_filter", true, Lang.t("OPPO FULLRES, свой баланс белого: каждая сессия с нейтрали обычного режима, замер только при установившейся AE на не тёмном и не пересвеченном кадре, фильтр с отбраковкой выбросов. Выкл — сглаживание P192",
                "OPPO FULLRES own white balance: each session starts at the plain mode's neutral, measured only at settled AE on frames neither dark nor clipped, a filter with outlier rejection. Off: the P192 smoothing")));
        k.add(s(cap, "oppo_wb_anchor", true, Lang.t("OPPO FULLRES, свой баланс белого: привязка к нейтрали обычного режима, пока сцена та же (снимает постоянный сдвиг оценки). Выкл — без привязки",
                "OPPO FULLRES own white balance: tied to the plain mode's neutral while the scene stays (removes the estimate's steady offset). Off: no tie")));
        k.add(s(cap, "oppo_wb_fuse", true, Lang.t("OPPO FULLRES: баланс белого фото — своя оценка кадра вместе с балансом видоискателя в момент кадра. Выкл — только своя оценка кадра",
                "OPPO FULLRES: the photo's white balance is its frame's own estimate fused with the viewfinder's at that frame. Off: the frame's own estimate only")));
        // P212d: the lamp flicker from evidence (MainsFlicker, RowFlicker)
        k.add(s(cap, "native_ae_flicker_both", true, Lang.t("OPPO FULLRES, своя AE: пока частота мерцания неизвестна, выдержка от 30 мс — та из 30 / 33.3 / 40 / 41.7 мс, что меньше всего полосит и при 100, и при 120 Гц. Выкл — по частоте региона",
                "OPPO FULLRES own AE: while the flicker is unknown, a shutter from 30 ms is the one of 30 / 33.3 / 40 / 41.7 ms that bands least at both 100 and 120 Hz. Off: by the region's mains")));
        k.add(s(cap, "flicker_rows", true, Lang.t("Частота мерцания «Авто»: два снимка подряд с одинаковым голосом профиля строк ультракороткого кадра задают частоту. Выкл — голос только в логе",
                "Flicker frequency Auto: two shots in a row whose ultrashort row profile votes alike set the frequency. Off: the vote is only logged")));
        // P198 / P266: «50 Мп: ZSL» (pref_scam_hybrid_fullres50_zsl) is the menu row of fullres_zsl (MENU_BACKED)
        k.add(s(cap, "fullres_plain_merge", true, Lang.t("FULLRES 50 Мп: на время склейки камера в обычном режиме (меньше нагрев и памяти). Выкл — 50 Мп поток идёт и во время склейки",
                "FULLRES 50 MP: the camera runs its plain session while the shot merges (less heat and memory). Off: the 50 MP stream keeps running")));
        // P222: the stills a native FULLRES series keeps in flight (FullResInFlight)
        k.add(n(cap, "fullres_in_flight", null, Lang.t("FULLRES: сколько кадров серии отправлено камере одновременно (1–8). По умолчанию 6 до 100 Мп, 3 выше 100 Мп и после сбоя серии на этой камере и размере; 3 — как до P222. SDK-путь X200 Ultra всегда по одному",
                "FULLRES: the stills of a series sent to the camera at once (1-8). Default 6 up to 100 MP, 3 above 100 MP and after a failed series on this camera and size; 3 = as before P222. The X200 Ultra's SDK route always one at a time")));
        // P239: the native 50 MP ring's rate and rest (FullResRingPolicy)
        k.add(n(cap, "fullres_ring_fps", "12", Lang.t("FULLRES 50 Мп: сколько кадров в секунду копируется в ZSL-кольцо (1–30). 0 — каждый кадр, как до P239",
                "FULLRES 50 MP: frames a second copied into the ZSL ring (1-30). 0: every frame, as before P239")));
        k.add(n(cap, "fullres_ring_idle_s", "10", Lang.t("FULLRES 50 Мп: кольцо отдыхает, если столько секунд нет касаний и телефон неподвижен (касание будит). 0 — не отдыхает",
                "FULLRES 50 MP: the ring rests after this many seconds without a touch and with the phone still (a touch wakes it). 0: never rests")));
        // P238: the hot-phone policy (ThermalPolicy)
        k.add(s(cap, "thermal_policy", true, Lang.t("Телефон горячий (статус SEVERE): RAW-видоискатель не чаще 15 кадров/с, кольцо FULLRES на паузе, предупреждение при снимке. Выкл — только запись в лог",
                "Hot phone (status SEVERE): the RAW viewfinder at most 15 fps, the FULLRES ring paused, a notice at the shot. Off: only logged")));
        // P237: the idle compile of the post-pipeline programs missing from the disk cache (GLProg.warmUp)
        k.add(s(post, "gl_warm_up", true, Lang.t("Шейдеры обработки, которых нет в кэше на диске (после обновления драйвера, вытеснения), компилируются в фоне через 4 с после открытия камеры, пока она не снимает. Выкл — только при снимке, как до P237",
                "Processing shaders missing from the disk cache (after a driver update, eviction) are compiled in the background 4 s after the camera opens, while it is idle. Off: only at a shot, as before P237")));
        // P242: the merge worker's GPU priority (A/B, WorkerSpawn.environment)
        k.add(s(merge, "worker_gpu_normal_priority", false, Lang.t("Склейка в воркере с обычным приоритетом GPU (сравнение A/B: может склеивать быстрее, видоискатель может подтормаживать). Выкл — низкий приоритет, как с P57",
                "The merge worker at the normal GPU priority (A/B: the merge may run faster, the viewfinder may stutter). Off: the low priority, as since P57")));
        // P243: the DNG's colour tags from the JPEG's render (DngColour)
        k.add(s(post, "dng_colour_jpeg", true, Lang.t("DNG открывается в Lightroom/ACR в цвете JPEG: в DNG записаны баланс белого и цветовая матрица, с которыми сделан JPEG. Выкл — общий профиль камеры (два иллюминанта), как до P243",
                "The DNG opens in Lightroom / ACR in the JPEG's colour: it carries the white balance and colour matrix the JPEG was made with. Off: the camera's generic two-illuminant profile, as before P243")));
        // P252: the ISP preview out of the repeating request in every photo session (A/B of the HAL's use case and its AF)
        k.add(s(merge, "isp_preview_off_always", false, Lang.t("Видоискатель из RAW во всех режимах: ISP-превью в сессии, но не в запросе (проверка автофокуса OPPO ночью)",
                "The RAW viewfinder in every mode: the ISP preview in the session, not in the request (an OPPO night autofocus check)")));
        // P236: the shot's frames closed off the critical path after the merge (ScamProcessor.releaseFrames)
        k.add(s(post, "release_async", true, Lang.t("После склейки кадры снимка освобождаются в фоне, обработка начинается сразу (не для 200 Мп). Выкл — сначала освобождение (~0.4 с на 50 Мп), как до P236",
                "After the merge the shot's frames are freed in the background and the processing starts at once (not for 200 MP). Off: freed first (~0.4 s at 50 MP), as before P236")));
        // P235: the shot arena of a FULLRES burst made at the press (RingArenaMove)
        k.add(s(cap, "fullres_press_arena", true, Lang.t("FULLRES 50 Мп: общий буфер снимка создаётся при нажатии — кадры серии пишутся сразу в него, кадры кольца переносятся, пока идёт серия. Выкл — перенос всей серии перед склейкой (как до P235)",
                "FULLRES 50 MP: the shot's shared buffer is made at the press - the stills go straight into it, the ring frames move in while the series runs. Off: the whole burst moved before the merge (as before P235)")));
        k.add(s(cap, "oppo_tele_access", true, Lang.t("OPPO Find X7 Ultra: телевики 3× и 6× через пакет Oplus",
                "OPPO Find X7 Ultra: the 3x and 6x telephotos through the Oplus package")));
        k.add(s(vivo, "vivo_fullres_x100u", true, Lang.t("X100 Ultra: FULLRES без аппаратного ремозаика (проверяется)", "X100 Ultra: FULLRES without hardware remosaic (being tested)")));
        k.add(s(vivo, "vivo_fullres_x300u", true, Lang.t("X300 Ultra: FULLRES без аппаратного ремозаика (проверяется)", "X300 Ultra: FULLRES without hardware remosaic (being tested)")));
        k.add(n(vivo, "vivo_fullres_mode_main", "-1", Lang.t("X100/X300 Ultra FULLRES: режим сенсора основной (-1: режимы таблицы по очереди)", "X100/X300 Ultra FULLRES: main sensor mode (-1: the table's rows in turn)")));
        k.add(n(vivo, "vivo_fullres_mode_main50", "-1", Lang.t("X300 Ultra FULLRES 50 Мп: режим сенсора основной (-1: режимы таблицы по очереди)", "X300 Ultra FULLRES 50 MP: main sensor mode (-1: the table's rows in turn)")));
        k.add(n(vivo, "vivo_fullres_mode_wide", "-1", Lang.t("X100/X300 Ultra FULLRES: режим сенсора сверхширика (-1: режимы таблицы по очереди)", "X100/X300 Ultra FULLRES: ultra-wide sensor mode (-1: the table's rows in turn)")));
        k.add(n(vivo, "vivo_fullres_mode_tele", "-1", Lang.t("X100/X300 Ultra FULLRES 200 Мп: режим сенсора теле (-1: режимы таблицы по очереди)", "X100/X300 Ultra FULLRES 200 MP: telephoto sensor mode (-1: the table's rows in turn)")));
        k.add(s(vivo, "vivo_fullres200_stock", true, Lang.t("X100/X300 Ultra FULLRES 200 Мп как в стоке: видоискатель в обычном режиме, 200 Мп только на снимок. Выкл — камера постоянно в режиме 200 Мп",
                "X100/X300 Ultra FULLRES 200 MP the stock way: the viewfinder in the normal mode, 200 MP for the stills only. Off - the camera held in the 200 MP mode")));
        k.add(s(vivo, "vivo_fullres200_force", false, Lang.t("X100/X300 Ultra FULLRES 200 Мп (как в стоке): ключ режима 200 Мп на кадрах снимка",
                "X100/X300 Ultra FULLRES 200 MP (the stock way): the 200 MP mode key on the stills")));
        // P212h (A7 = M14): the vivo FULLRES AE audit (log only) and the 200 MP series' exposure
        k.add(s(vivo, "vivo_ae_audit", true, Lang.t("vivo: замер RAW-видоискателя (медиана, свет, BV) рядом с экспозицией HAL в логе SCAM_AE раз в секунду; только лог",
                "vivo: the RAW viewfinder's meter (median, highlights, BV) beside the HAL's exposure in the SCAM_AE log once a second; log only")));
        k.add(s(vivo, "vivo_series_ae_lock", false, Lang.t("vivo 200 Мп: AE lock на кадрах N серии, если AE видоискателя сошлась к нажатию (проверяется)",
                "vivo 200 MP: AE lock on the series' N stills when the viewfinder AE has converged at the press (being tested)")));
        k.add(s(vivo, "vivo_still_us_from_n", false, Lang.t("vivo 200 Мп без своего видоискателя: ультракороткий кадр от экспозиции первого кадра N, а не видоискателя обычного режима (от 4 кадров)",
                "vivo 200 MP without its own viewfinder: the ultrashort still from the first N still's exposure, not the normal-mode viewfinder's (from 4 frames)")));
        k.add(n(vivo, "vivo_fullres_mode_tele50", "-1", Lang.t("X100/X300 Ultra FULLRES 50 Мп: режим сенсора теле (-1: режимы таблицы по очереди)", "X100/X300 Ultra FULLRES 50 MP: telephoto sensor mode (-1: the table's rows in turn)")));
        k.add(s(vivo, "vivo_qcom_modes", true, Lang.t("X200 Ultra: экспериментальные модули Qualcomm", "X200 Ultra: experimental Qualcomm modules")));
        k.add(s(vivo, "vivo_qcom_raw_psl", false, Lang.t("X200 Ultra: диагностический RAW без ZSL", "X200 Ultra: diagnostic RAW without ZSL")));
        k.add(s(vivo, "vivo_qcom_stream_usage", true, Lang.t("X200 Ultra: назначения потоков для экспериментальных режимов", "X200 Ultra: stream purposes for experimental modes")));
        k.add(s(vivo, "vivo_qcom_spoof", false, Lang.t("vivo Snapdragon: экспериментальная подмена клиента (RAW-сессия пока не работает)", "vivo Snapdragon: experimental client spoof (RAW session does not work yet)")));
        k.add(s(vivo, "vivo_qcom_sensor_routes", false, Lang.t("X200 Ultra: экспериментальные ISZ/DCG-параметры сессии", "X200 Ultra: experimental ISZ/DCG session parameters")));
        k.add(s(vivo, "vivo_qcom_provider_app", false, Lang.t("X200 Ultra: экспериментальный AppType vivo после открытия камеры", "X200 Ultra: experimental vivo AppType after opening the camera")));
        k.add(s(vivo, "vivo_qcom_logical", false, Lang.t("X200 Ultra: экспериментальная логическая камера 2", "X200 Ultra: experimental logical camera 2")));
        k.add(s(vivo, "vivo_qcom_vcf_bypass", false, Lang.t("X200 Ultra: диагностический VCFAppType 2 (RAW не исправляет)", "X200 Ultra: diagnostic VCFAppType 2 (does not fix RAW)")));
        k.add(s(vivo, "vivo_qcom_reprocess", false, Lang.t("X200 Ultra: диагностический reprocessMode 1 (ошибка камеры 4)", "X200 Ultra: diagnostic reprocessMode 1 (camera error 4)")));
        k.add(s(vivo, "vivo_dcg_telegram", false, Lang.t("X200 Ultra, опыт P230: DCG как клиент Telegram (60 к/с, режим DCGHDR60_1_4)", "X200 Ultra, P230 test: DCG as the Telegram client (60 fps, mode DCGHDR60_1_4)")));
        k.add(s(vivo, "dcg14", true, Lang.t("Кнопка DCG 14 бит (X300 Ultra)", "The DCG 14-bit button (X300 Ultra)")));
        k.add(s(vivo, "vivo_teleconverter", false, Lang.t("Показывать камеры-профили телеконвертера vivo (Tele…External) — только с надетой насадкой", "Show the vivo teleconverter camera profiles (Tele…External): only with the extender attached")));
        // Pixel 10 Pro / Pro XL
        k.add(s(pixel, "pixel_modules", true, Lang.t("Добавлять ISZ-кропы логической камеры как модули", "Add the logical camera's ISZ crops as modules")));
        k.add(s(pixel, "pixel_full_res", true, Lang.t("Кнопка полного разрешения (50 Мп)", "The full-resolution button (50 MP)")));
        // diagnostics and A/B
        k.add(s(diag, "stab_trace", true, Lang.t("Журнал стабилизации в логе", "Stabilisation trace in the log")));
        k.add(sAuto(diag, "stab_trace_ois", Lang.t("Отсчёты OIS в журнале стабилизации (по умолчанию — на vivo)", "OIS samples in the stabilisation trace (default: on vivo)")));
        k.add(s(diag, "post_sync", false, Lang.t("Синхронные узлы обработки (замер времени)", "Synchronous processing nodes (timing)")));
        k.add(s(diag, "post_ab", false, Lang.t("A/B обработки: старый и новый конвейер на одном снимке", "Processing A/B: old and new pipeline on one shot")));
        k.add(s(diag, "post_ab_clamp", false, Lang.t("A/B: прежнее ограничение входа", "A/B: the former input clamp")));
        k.add(s(diag, "early_release", false, Lang.t("Ранний возврат текстур обработки при любом размере (как выше 100 Мп; для A/B)",
                "Early release of the processing textures at any size (as above 100 MP; for the A/B)")));
        k.add(s(diag, "post_ab_upload", false, Lang.t("A/B: меняется только загрузка RGB", "A/B: only the RGB upload changes")));
        k.add(s(diag, "post_ab_dump", false, Lang.t("A/B: сохранить оба результата в PNG", "A/B: save both results as PNG")));
        builtMerge = merge;
        builtPost = post;
        all = Collections.unmodifiableList(k);
        return all;
    }

    /** The keys of {@link #all()}. */
    public static Set<String> keys() {
        Set<String> out = new HashSet<>();
        for (Key k : all()) out.add(k.key);
        return out;
    }
}
