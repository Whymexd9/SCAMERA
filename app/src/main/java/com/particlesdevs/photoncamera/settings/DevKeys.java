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
            "gl_warm_up_recipes", "ok_read", "worker_nice", "worker_prespawn", "thermal_async", "black_scan_bulk", "zero_copy_read", // P371: speed only
            "hybrid_la_threads", "hybrid_la_stream", "hybrid_la_scam", "hybrid_rgb_half", "worker_gpu_normal_priority",
            "isp_preview_off_always", "hybrid_isp_ccm", "hybrid_clip_flags", "dng_colour_jpeg",
            "dng_white_spec", // P370 (A1): the scene light and calibration weight of the shot's Parameters
            "pedestal_memory", // P386: the black of the shot's Parameters (the lab replays it as measured at the shot)
            "hybrid_sharp_select", // P331: picks the frames of the saved burst at the shot
            // P306: no native Tetra merge is a night shot any more, nothing reads its night point
            "hybrid_mosaic_tetra_night_kernel_scale", "hybrid_mosaic_tetra_night_edge_scale", "hybrid_mosaic_tetra_night_flat_scale")));

    private static List<Key> all;
    /** The merge and processing group names of {@link #all} as it was built. */
    private static String builtMerge, builtPost;

    private DevKeys() {}

    private static String mergeGroup() { return Lang.t("Склейка SHDR (воркер)", "SHDR merge (worker)"); }
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
        k.add(n(cap, "zsl_reader", null, Lang.t("Буферов RAW-ридера при memfd-кольце (по умолчанию 20, не меньше 16; OPPO Find X7 Ultra: 8, не меньше 8)",
                "RAW reader buffers with the memfd ring (default 20, at least 16; OPPO Find X7 Ultra: 8, at least 8)")));
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
        k.add(s(cap, "mosaic_awb_teacher", true, Lang.t("Баланс белого мозаики: «учитель» — в обычном режиме запоминает, какую нейтраль ISP выбирает для нашей оценки кадра (на фото — по mosaic_awb_teacher_use). Выкл — ни сбора, ни лога",
                "Mosaic white balance teacher: in the plain mode it learns which neutral the ISP picks for our estimate of the frame (photos: see mosaic_awb_teacher_use). Off: neither collected nor logged")));
        // P366: the X200U ISZ photos at noon (green): the locus's slope held to the DNG prior's, the cool end, the teacher on photos
        k.add(s(cap, "mosaic_awb_slope_guard", true, Lang.t("Баланс белого мозаики: кривая камеры для оценки кадра не круче 2× наклона по матрицам DNG, пока её точки не покрывают половину диапазона дневной свет — лампа накаливания (иначе наклон DNG через центр точек; баланс камеры на маршрутах проверяется по кривой точек). Выкл — любой наклон",
                "Mosaic white balance: the camera curve for the frame's estimate no more than 2x as steep as the DNG matrices' slope while its points cover less than half of the daylight-to-tungsten range (else the DNG slope through their centre; the camera's balance on the routes is checked against the points' curve). Off: any slope")));
        k.add(s(cap, "mosaic_awb_cool_keep", true, Lang.t("Баланс белого мозаики: свет холоднее всего выученного не тянется вдоль кривой к выученному больше, чем к самой кривой (цветовая температура сохраняется), но не дальше 0.2 ln за концом кривой. Выкл — конец кривой",
                "Mosaic white balance: a light cooler than anything learned is not pulled along the curve towards the learned lights by more than towards the curve (its colour temperature kept), at most 0.2 ln beyond the curve's end. Off: the curve's end")));
        k.add(n(cap, "mosaic_awb_teacher_use", "1", Lang.t("Баланс белого мозаики по «учителю» (своя сетка или сетка камеры с тем же сенсором, смесь с кривой): 1 — только RAW-сессия без 3A ISP (ISZ X200 Ultra), 2 — любой снимок мозаики без маршрута, 0 — только лог",
                "Mosaic white balance from the teacher (its own grid or a same-sensor camera's, blended with the curve): 1 = only a RAW-only session without the ISP's 3A (X200 Ultra ISZ), 2 = any route-less mosaic photo, 0 = log only")));
        // P212g: whose neutral a vivo mosaic photo takes (W6) and the plain-session colour matrices of mosaic photos (W7)
        k.add(s(cap, "mosaic_awb_resolver", true, Lang.t("vivo FULLRES: баланс белого камеры, если он установился и лежит на кривой камеры; 200 Мп без своего видоискателя — баланс видоискателя обычного режима за секунду до нажатия. Выкл — оценка по кадру (P121) на всех маршрутах",
                "vivo FULLRES: the camera's white balance when it has converged and lies on the camera's curve; 200 MP without its own viewfinder: the normal-mode viewfinder's balance of the second before the press. Off: the frame estimate (P121) on every route")));
        k.add(n(cap, "mosaic_ccm_table", "2", Lang.t("Снимки мозаики: цветовая матрица ISP из обычного режима этой камеры при том же свете вместо общих матриц DNG. 2: все (и ISZ 12 Мп), 1: только FULLRES, 0: матрицы DNG",
                "Mosaic shots: the ISP's colour matrix of this camera's plain mode at the same light instead of the generic DNG matrices. 2: all (the 12 MP ISZ modules too), 1: FULLRES only, 0: the DNG matrices")));
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
        k.add(n(vf, "vf_why", "1", Lang.t("RAW-видоискатель: тон Why итогового фото — 1: по каждому пикселю (быстро), 2: локально направляемым фильтром (прежнее слияние фото, до пирамиды Лапласа; тяжелее, замедляет обработку), 0: собственный вид видоискателя",
                "RAW viewfinder: the photo's Why tone - 1: per pixel (fast), 2: local with the guided filter (the photo's fusion before the Laplacian pyramid; heavier, slows processing down), 0: the viewfinder's own look")));
        // P319: an ISP-woken redraw of the same RAW frame reuses its tone (MainRenderer.toneKey)
        k.add(s(vf, "vf_tone_reuse", true, Lang.t("RAW-видоискатель: повторная отрисовка того же RAW-кадра берёт готовый тон (0 — тонировать каждую отрисовку)",
                "RAW viewfinder: a redraw of the same RAW frame reuses its tone (0: tone every draw)")));
        // P314: the default (medium) GPU priority, the processing contexts stay low; 1 = P90's high priority
        k.add(n(vf, "vf_gpu_priority", "0", Lang.t("Приоритет GPU-контекста видоискателя: 1 — высокий, 0 — обычный (меньше мешает обработке)",
                "Viewfinder GPU context priority: 1 high, 0 normal (gets less in the way of processing)")));
        // P353: the developed RAW viewfinder's tone rate while a shot is processed
        k.add(n(vf, "vf_busy_fps", "15", Lang.t("RAW-видоискатель во время обработки снимка: не больше стольких кадров в секунду (обработка не делит GPU с ним); 0 — каждый кадр",
                "RAW viewfinder while a shot is processed: at most this many frames a second (processing does not share the GPU with it); 0 = every frame")));
        k.add(s(vf, "vf_isp_latch", true, Lang.t("RAW-видоискатель: кадр ISP только принимается, без перерисовки того же RAW-кадра (выкл — перерисовка на каждый кадр ISP)",
                "RAW viewfinder: an ISP frame is only latched, the same RAW frame is not drawn again (off: a redraw for every ISP frame)")));
        // P365: the photo's tone in one fused pass from the RAW texture (MainRenderer / LiveWhyTone.drawFused), the last RAW frame
        // kept on the screen while the shot's series is taken
        k.add(s(vf, "vf_fused", false, Lang.t("RAW-видоискатель: тон Why за один проход прямо из RAW-текстуры (без промежуточного кадра, сразу на экран, если нет фильтра и фокус-пикинга), две RAW-текстуры по очереди (выкл — прежние проходы P314)",
                "RAW viewfinder: the Why tone in one pass straight from the RAW texture (no intermediate frame, straight to the screen without a lens filter or focus peaking), two RAW textures in turn (off: the passes of P314)")));
        // P383: the RAW viewfinder's frames drawn against their own exposure; a per-frame trace for the phone's numbers
        k.add(s(vf, "vf_ae_scene", true, Lang.t("RAW-видоискатель: каждый кадр по его собственной выдержке и ISO — шаг автоэкспозиции камеры не виден, плавно меняется только сцена (выкл — как до P383, для сравнения)",
                "RAW viewfinder: each frame drawn by its own exposure time and ISO - a step of the camera's auto exposure does not show, only the scene eases (off: as before P383, for an A/B)")));
        k.add(s(vf, "vf_ae_trace", false, Lang.t("RAW-видоискатель: 5 с после включения в журнал каждый кадр — выдержка, ISO, уровень кадра, усиление (для отладки плавности)",
                "RAW viewfinder: for 5 s after it is switched on, every frame to the log - exposure, ISO, the frame's level, the gain (to debug smoothness)")));
        k.add(s(vf, "vf_series_hold", true, Lang.t("RAW-видоискатель: пока снимается серия, на экране последний RAW-кадр (до 2 с), без вспышки вида ISP (выкл — через 0.3 с показывается поток ISP)",
                "RAW viewfinder: while the series is taken the last RAW frame stays on the screen (up to 2 s), no flash of the ISP look (off: the ISP stream shows after 0.3 s)")));
        // P212h (A6 = M11): the FULLRES viewfinder predicts the photo
        k.add(n(vf, "vf_native_gain", "1", Lang.t("OPPO FULLRES: усиление видоискателя — 1: только на экспозицию, которую режим не даёт (нехватка AE больше 0.3 EV), 2: замер видоискателя, пока экспозиция не задана вручную, 0: без усиления",
                "OPPO FULLRES viewfinder gain - 1: only for the exposure the mode cannot give (an AE deficit above 0.3 EV), 2: the viewfinder's meter unless the exposure is hand-set, 0: none")));
        k.add(s(vf, "vf_native_ccm", true, Lang.t("FULLRES: цветовая матрица фото в видоискателе вместо единичной (OPPO X7 Ultra: тюнинг по цветовой температуре; другие OPPO и vivo: матрица обычного режима при этом свете или ISP). Выкл — единичная",
                "FULLRES: the photo's colour matrix on the viewfinder instead of the identity (OPPO X7 Ultra: the tuning at the light's colour temperature; other OPPO and vivo: the plain mode's matrix at that light or the ISP's). Off: the identity")));
        k.add(s(vf, "vf_mosaic_wb", true, Lang.t("RAW-видоискатель мозаики (ISZ, режим сенсора), когда ISP не ведёт баланс белого: баланс белого фото по кадру видоискателя (выкл — замершие усиления HAL)",
                "Mosaic RAW viewfinder (ISZ, a sensor mode) while the ISP runs no white balance: the photo's white balance from the viewfinder frame (off: the HAL's frozen gains)")));
        k.add(s(vf, "vf_raw_scene_luma", true,Lang.t("Приоритет выдержки / ISO: яркость сцены из RAW-видоискателя (без неё в FULLRES второй параметр не двигался). Выкл — только из видоискателя ISP",
                "Shutter / ISO priority: the scene brightness from the RAW viewfinder (without it the other value never moved in FULLRES). Off: from the ISP viewfinder only")));
        // P365: the RAW viewfinder with the reference viewfinder's resource requirements (one draw request, the ring slot, RAW10)
        k.add(s(vf, "vf_one_request", true, Lang.t("RAW-видоискатель: один запрос отрисовки на кадр (выкл — как раньше: второй запрос, лишняя перерисовка того же кадра)",
                "RAW viewfinder: one draw request a frame (off: as before, a second request and a redraw of the same frame)")));
        k.add(s(vf, "vf_ring_ref", true, Lang.t("RAW-видоискатель берёт кадр из ячейки ZSL-кольца, без своей копии (выкл — копия из буфера камеры)",
                "RAW viewfinder takes the frame from its ZSL ring slot, no copy of its own (off: a copy out of the camera's buffer)")));
        k.add(s(vf, "zsl_ring_unpack", true, Lang.t("RAW10 / RAW12 распаковывается в ячейку ZSL-кольца при приходе кадра: склейка и видоискатель берут её без копии (выкл — распаковка после нажатия)",
                "RAW10 / RAW12 is unpacked into the ZSL ring slot as the frame arrives: the merge and the viewfinder take it without a copy (off: unpacked after the press)")));
        k.add(sAuto(vf, "vf_isp_light", Lang.t("Под RAW-видоискателем поток ISP 1440x1080, шумодав и резкость ISP выкл. в повторяющемся запросе (по умолчанию — на OPPO Find X7 Ultra; со следующего открытия камеры)",
                "Under the RAW viewfinder the ISP stream at 1440x1080, ISP noise reduction and edge off in the repeating request (default: on the OPPO Find X7 Ultra; from the next camera start)")));
        // merge (worker)
        k.add(s(merge, "hybrid_rgb_half", true, Lang.t("RGB от воркера в half float (меньше данных). Выкл — float32", "Worker RGB as half floats (less data). Off: float32")));
        k.add(n(merge, "hybrid_s61_mode", null, Lang.t("Режим ковариации Sabre 6.1 (6 — от базового кадра, быстрее)", "Sabre 6.1 covariance mode (6: from the base frame, faster)")));
        k.add(n(merge, "hybrid_s61_day_tensor_noise", null, Lang.t("Sabre 6.1 днём: шум тензора", "Sabre 6.1 by day: tensor noise")));
        k.add(n(merge, "hybrid_s61_day_gd_noise", null, Lang.t("Sabre 6.1 днём: шум градиента", "Sabre 6.1 by day: gradient noise")));
        k.add(n(merge, "hybrid_s61_tensor_noise", null, Lang.t("Sabre 6.1: шум тензора", "Sabre 6.1: tensor noise")));
        k.add(n(merge, "hybrid_s61_gd_noise", null, Lang.t("Sabre 6.1: шум градиента", "Sabre 6.1: gradient noise")));
        k.add(n(merge, "hybrid_us_need_clip", "1", Lang.t("Склейка: кадры светов не обрабатываются, если в опорном кадре нет клипа (быстрее; 0 — всегда)", "Merge: highlight frames are skipped when the base frame has no clipping (faster; 0: always)")));
        k.add(n(merge, "hybrid_clip_reject", "1", Lang.t("Склейка: кадр заметно темнее клипа опорного кадра отбрасывается (блики на воде не тускнеют; 0 — как раньше)", "Merge: a frame clearly darker than the base's clip is rejected (sparkles on water stay bright; 0: as before)")));
        k.add(n(merge, "hybrid_rim_lift", "1", Lang.t("Склейка: зелёный в клипе не ниже среднего всех сэмплов, клиппированные по их значению (0 — как раньше)", "Merge: a clipped green at least the mean of all its samples, the clipped ones at their values (0: as before)")));
        k.add(n(merge, "hybrid_s61_night_scale", "0.8", Lang.t("Sabre 6.1 ночью: множитель ширины ядра при SNR-ключе до 8 (к 16 — снова 1; 1 — как раньше)", "Sabre 6.1 at night: kernel width multiplier at an SNR key up to 8 (back to 1 at 16; 1: as before)")));
        k.add(n(merge, "hybrid_s61_fine", "0", Lang.t("Sabre 6.1: структура по зелёным пикселям в полном разрешении, множитель её шума (0 — по квадам 2×2; 2 медленнее и даёт решётку на подсветке)", "Sabre 6.1: structure from the green sites at full resolution, its noise multiplier (0: from the 2x2 quads; 2 is slower and leaves a grid on backlights)")));
        k.add(n(merge, "hybrid_us_set", "1", Lang.t("Короткие кадры: набор P350 (регистрация, маска по своей яркости, отказ по областям; 0 — как раньше)", "Ultrashort frames: the P350 set (registration, own-brightness mask, refusal per region; 0: as before)")));
        // P366: the lamp-flicker corrections of the ultrashort frames held in daylight / for an incoherent tile field
        k.add(n(merge, "hybrid_us_daylight_ev", "10", Lang.t("Короткие кадры: коррекция мерцания ламп не работает от этой яркости сцены (EV100 опорного кадра; 0 — всегда работает)",
                "Ultrashort frames: the lamp-flicker correction is off from this scene brightness up (the base frame's EV100; 0: always on)")));
        k.add(n(merge, "hybrid_us_incoherent", "0.5", Lang.t("Короткие кадры: поле мерцания по плиткам не применяется, если плитки расходятся с соседями на эту долю своего отклонения (движение; 0 — всегда)",
                "Ultrashort frames: the tile flicker field is not applied when its tiles depart from their neighbours by this share of their deviation (motion; 0: always)")));
        k.add(n(merge, "hybrid_long_set", "1", Lang.t("Длинные кадры: набор P358 (шум по замеру, вес по шуму; 0 — как раньше)",
                "Long frames: the P358 set (measured noise, weight by noise; 0: as before)")));
        // P392: partial rejection of the ultrashort frames and of the long frames under motion
        k.add(n(merge, "hybrid_us_partial", "1", Lang.t("Короткие кадры: отказ только в областях движения, замена по пикселю, заполнение пересвета без швов (0 — как раньше)",
                "Ultrashort frames: refusal only in the moved regions, the replacement chosen per pixel, the clip filled without seams (0: as before)")));
        k.add(n(merge, "hybrid_long_scope", "1", Lang.t("Длинные кадры: при движении убираются только сдвинутые блоки, а не весь кадр (0 — как раньше)",
                "Long frames: under motion only the moved blocks are left out, not the whole frame (0: as before)")));
        k.add(n(merge, "hybrid_rej_edge", "1", Lang.t("Реджекты: ячейка за краем кадра не считается движением и не расширяет отказ (без полосы у края; 0 — как раньше)",
                "Rejection: a cell beyond the frame's edge is no motion and does not spread the refusal (no strip at the edge; 0: as before)")));
        // P415: the reject's read term in the deep shadows as they measured
        k.add(n(merge, "hybrid_dark_read", "1", Lang.t("Длинные кадры: шум глубоких теней по замеру в реджекте (без цветных пятен; 0 — как раньше)",
                "Long frames: the deep shadows' measured noise in the rejection (no colour clumps; 0: as before)")));
        k.add(n(merge, "hybrid_rej_set", "1", Lang.t("Реджекты: набор P344 (чистая карта на статике, движение отсекается; 0 — как P341)", "Rejection: the P344 set (a clean map on static areas, motion still cut; 0: as P341)")));
        k.add(n(merge, "hybrid_rej_pool", "1", Lang.t("Реджекты: расстояние по 3×3 клеткам вместо одной (чистая карта без шумовых отказов; 0 — как раньше)", "Rejection: the distance over 3x3 cells instead of one (a clean map without noise rejections; 0: as before)")));
        k.add(n(merge, "hybrid_rej_prior", "1", Lang.t("Реджекты: плавный учёт движения по полю выравнивания (0 — порог по плитке, как раньше)", "Rejection: soft motion prior from the alignment field (0: the per-tile threshold as before)")));
        k.add(n(merge, "hybrid_base_demosaic_alias", "1", Lang.t("Одиночный кадр: подавление ложного цвета по ступеньке Gr/Gb выше шума (0 — выкл.)", "Single frame: false-colour damping by the Gr/Gb step above the noise (0: off)")));
        k.add(n(merge, "hybrid_plain_chroma_hand", "1", Lang.t("Цветовая медиана при съёмке с рук: доля (1 — целиком; со штатива всегда целиком)", "Chroma median of a hand-held burst: share (1: whole; a still burst always takes it whole)")));
        k.add(n(merge, "hybrid_sharp_select", "0.75", Lang.t("Отбор кадров по резкости: N-кадры мягче этой доли медианы не склеиваются (0 — все кадры)", "Frame selection by sharpness: N frames softer than this share of the median are not merged (0: every frame)")));
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
        // P362: RawTherapee's «Inpaint opposed» where no ultrashort frame holds the highlight
        k.add(s(post, "hybrid_highlight_opposed", true, Lang.t("Пересветы без коротких кадров (одиночные кадры, «Движение», отказ Bento): «Inpaint opposed» из RawTherapee (выкл — восстановление SCAM)",
                "Highlights without ultrashort frames (single frames, Motion, Bento refused): RawTherapee's «Inpaint opposed» (off: the SCAM recovery)")));
        // P393: a clipped channel of a coloured surface also takes the local light's colour (scamhdr/scamrgb oppLocalU)
        k.add(s(post, "hybrid_highlight_opposed_local", true, Lang.t("Пересветы без коротких кадров: клипнутый канал цветной поверхности (листва на солнце) по цвету соседних неклипнутых пикселей, а не только по общей поправке кадра (выкл — как раньше: жёлтые листья уходили в оранжевый и розовый)",
                "Highlights without ultrashort frames: a clipped channel of a coloured surface (sunlit leaves) from the colour of the unclipped pixels around, not the frame's one correction alone (off: as before - yellow leaves went orange and pink)")));
        // P399: the lens's purple fringe on the dark side of an edge against a bright light
        k.add(n(post, "hybrid_highlight_purple", "1.0", Lang.t("Пересветы: фиолетовая и пурпурная кайма объектива на тёмной стороне края у яркого света (рамы окон, предметы на фоне неба) становится нейтральной; цветные источники (фиолетовые вывески, LED) и всё дальше 6 px от края как раньше; 0 — выкл.",
                "Highlights: the lens's violet and magenta fringe on the dark side of an edge against a bright light (window frames, objects against the sky) goes neutral; coloured lights (violet signs, LEDs) and everything beyond 6 px of the edge as before; 0: off")));
        k.add(n(post, "post_priority", null, Lang.t("Приоритет потока обработки (nice; пусто — не менять)", "Priority of the processing thread (nice; empty: unchanged)")));
        k.add(n(post, "gpu_low_priority", "2", Lang.t("Приоритет GPU-контекста обработки: 2 — средний, 1 — низкий, 0 — обычный", "Priority of the processing GPU context: 2 medium, 1 low, 0 normal")));
        k.add(s(post, "post_shared_context", true, Lang.t("Один GPU-контекст обработки на всё время работы: программы шейдеров не загружаются заново на каждый снимок (выкл — новый контекст на каждый снимок)",
                "One processing GPU context for the whole run: the shader programs are not loaded again for every shot (off: a new context every shot)")));
        k.add(s(post, "effmap_real", true, Lang.t("Карта числа кадров для шумоподавления — реальная (выкл — старая)", "Frame-count map for the denoise: the real one (off: the old one)")));
        // P214d: ScamDenoise.lscTerm
        k.add(s(post, "denoise_lsc", true, Lang.t("Шумоподавление сильнее к углам кадра по коррекции виньетирования (g² карты затенения объектива)", "Denoise harder towards the frame corners after the vignetting correction (g² of the lens-shading map)")));
        k.add(n(post, "denoise_lsc_max", "2", Lang.t("Шумоподавление к углам: предел множителя дисперсии (1–16; 2 — шум в углах считается в 1,4 раза сильнее)", "Corner denoise: cap of the variance multiplier (1-16; 2: the corner noise counts 1.4 times as strong)")));
        // P214e: WhyLumaSharpen.smoothedSource (R1); P214z: not above 100 MP (r1Fits)
        k.add(s(post, "sharpen_r1", true, Lang.t("Резкость мозаичных снимков (ISZ Quad/Tetra, FULLRES до 100 Мп) по яркости, сглаженной 3×3: решётка мозаики почти не усиливается. Выкл — резкость как у снимков Bayer",
                "Sharpening of mosaic shots (ISZ Quad / Tetra, FULLRES up to 100 MP) on the 3x3-smoothed luma: the mosaic lattice is hardly lifted. Off: the sharpening of Bayer shots")));
        k.add(s(post, "sharpen_lattice", true, Lang.t("Резкость снимков Bayer без 2-пиксельной решётки склейки: сетка на воде и ровных текстурах не усиливается. Выкл — как раньше",
                "Sharpening of Bayer shots without the merge's 2 px lattice: no mesh lifted on water and flat texture. Off: as before")));
        // P212h (A5 = M5): the gain clamp of the WHY exposure from the merged image's noise
        k.add(n(post, "ae_clamp_stack", "1", Lang.t("Яркость фото: предел усиления по шуму — по числу склеенных кадров N (карта склейки, N^0.4). 1: FULLRES, 2: все снимки, 0: по одному кадру",
                "Photo brightness: the noise gain clamp from the merged N frames (the merge's frame map, N^0.4). 1: FULLRES, 2: every shot, 0: one frame")));
        k.add(n(post, "jpeg_threads", null, Lang.t("Потоков кодирования JPEG (1 — один поток, прогрессивный; пусто — все ядра)", "Threads of the JPEG encode (1: one thread, progressive; empty: every core)")));
        k.add(sAuto(post, "rgba_upload", Lang.t("Загрузка RGB в GPU как RGBA (по умолчанию решает приложение)", "RGB uploaded to the GPU as RGBA (default: chosen by the app)")));
        k.add(n(post, "hybrid_isp_ccm", "1", Lang.t("vivo: цветовая матрица ISP по кадру (0 — матрицы DNG)", "vivo: the ISP colour matrix of the frame (0: the DNG matrices)")));
        // P370 (A1, B2): the DNG white estimate (fixed in the shot's Parameters: not in the lab) and the hue outside sRGB
        k.add(s(post, "dng_white_spec", true, Lang.t("Цветовая температура сцены и вес калибровок DNG по оценке белого DNG SDK (выкл — прежняя оценка по матрицам с нормированными строками)",
                "The scene's colour temperature and the DNG calibration weight from the DNG SDK's white estimate (off: the former estimate on row-normalised matrices)")));
        k.add(s(post, "ccm_signed_hue", true, Lang.t("Цвет за пределами sRGB после матрицы камеры: тон и яркость OKLab по цвету без обрезки каналов (выкл — по обрезанному цвету, как раньше)",
                "A colour the camera matrix puts outside sRGB: OKLab hue and lightness from the colour before the channel clamp (off: from the clamped colour, as before)")));
        k.add(n(post, "hybrid_dn_luma_grain", "0.5", Lang.t("Шумодав: доля убранного мелкого зерна яркости, которая возвращается (0 — как раньше)", "Denoise: share of the removed fine luma grain that comes back (0: as before)")));
        // P421: the finish denoise's diagnostics out of the menu
        k.add(n(post, "hybrid_dn_snr", "0", Lang.t("Шумодав: ручная чистота кадра (SNR), 0 — авто", "Denoise: manual shot cleanness (SNR), 0: auto")));
        k.add(n(post, "hybrid_dn_strength_map", "0", Lang.t("Шумодав: карта силы — 0 авто, 1 по кадрам, 2 равномерно", "Denoise: strength map - 0 auto, 1 by frames, 2 uniform")));
        k.add(n(post, "hybrid_dn_chroma_day", "0.5", Lang.t("Шумодав цвета днём: множитель при SNR 80 и выше (1 — как раньше; до SNR 20 всегда 1)", "Daylight colour denoise: multiplier at SNR 80 and above (1: as before; up to SNR 20 always 1)")));
        k.add(n(post, "hybrid_dn_dark_chroma_hi", "4", Lang.t("Шумодав: цвет в тенях, верхний порог (x1e-4)", "Denoise: colour in the shadows, upper threshold (x1e-4)")));
        k.add(n(post, "hybrid_dn_dark_chroma_lo", "1.5", Lang.t("Шумодав: цвет в тенях, нижний порог (x1e-4)", "Denoise: colour in the shadows, lower threshold (x1e-4)")));
        k.add(n(post, "hybrid_dn_dark_tier", "0.1", Lang.t("Шумодав цвета в глубоких тенях: доля самых тёмных блоков, по уровню которых выбирается сила (0 — как раньше)",
                "Colour denoise in the deep shadows: the share of the darkest blocks whose level picks its strength (0: as before)")));
        k.add(n(post, "hybrid_dn_edge_band", "6", Lang.t("Шумодав: цвет крайних пикселей кадра из соседней области, ширина полосы (0 — как раньше)",
                "Denoise: the frame edge's colour from the area beside it, the band's width (0: as before)")));
        k.add(n(post, "hybrid_dn_huge_map", "4.3", Lang.t("Шумодав 200 МП: сила (карта силы; 1 — как при всех склеенных кадрах)", "Denoise 200 MP: strength (strength map; 1: as with every frame merged)")));
        k.add(n(post, "hybrid_dn_huge_fine", "2", Lang.t("Шумодав 200 МП: срез мелкого шума яркости, в сигмах (0 — выкл.)", "Denoise 200 MP: fine luma noise coring, in sigmas (0: off)")));
        k.add(n(post, "hybrid_dn_dark_chroma_noise", "0.35", Lang.t("Шумодав: цвет в тенях, доля шума", "Denoise: colour in the shadows, noise share")));
        k.add(n(post, "hybrid_why_hr_mix", "0.5", Lang.t("Тон, света Bento выше 4x: пик к p99.9 (доля)", "Tone, Bento highlights above 4x: peak towards p99.9 (share)")));
        k.add(n(post, "hybrid_why_weight_center_hr", "0.75", Lang.t("Тон с запасом Bento: центр весов слияния (без строки — «Смещение центра экспозиции» + 0,15)", "Tone with Bento headroom: fusion weight centre (without the line: “Exposure centre shift” + 0.15)")));
        k.add(n(post, "hybrid_why_weight_hl_hr", "0.4", Lang.t("Тон с запасом Bento: вес слоя светов (без строки — половина «Вес слоя светов»)", "Tone with Bento headroom: highlight layer weight (without the line: half the “Highlight layer weight”)")));
        k.add(n(post, "hybrid_why_sat_light_lo", "0.15", Lang.t("Тон с запасом Bento: цветной свет: насыщенность, с которой начинаются веса ядра (нижняя = верхней — выкл.)", "Tone with Bento headroom: coloured light - saturation where the kernel's weights start (lo = hi: off)")));
        k.add(n(post, "hybrid_why_sat_light_hi", "0.95", Lang.t("Тон с запасом Bento: цветной свет: насыщенность полных весов ядра", "Tone with Bento headroom: coloured light - saturation of the full kernel weights")));
        k.add(n(post, "hybrid_why_sat_light_warm_lo", "0.1", Lang.t("Тон с запасом Bento: цветной свет: тёплые оттенки (закат, огонь) без весов ядра, начало ((G-B)/(R-B)); нижняя = верхней — выкл.", "Tone with Bento headroom: coloured light - warm hues (sunset, fire) left out of the kernel's weights, start ((G-B)/(R-B)); lo = hi: off")));
        k.add(n(post, "hybrid_why_sat_light_warm_hi", "0.25", Lang.t("Тон с запасом Bento: цветной свет: тёплые оттенки, полное исключение", "Tone with Bento headroom: coloured light - warm hues fully left out")));
        k.add(n(post, "hybrid_why_fusion_guided_lo", "0.8", Lang.t("Тон: света выше белого через guided filter вместо пирамиды (без ореолов у тёмных предметов), начало (яркость x AE); нижняя = верхней — выкл.; при включённых полосах пирамиды — выкл., если строка не задана явно", "Tone: light above white through the guided filter instead of the pyramid (no halos round dark objects), start (luminance x AE); lo = hi: off; off with the pyramid's bands on unless the line is set explicitly")));
        k.add(n(post, "hybrid_why_fusion_guided_hi", "1.6", Lang.t("Тон: света через guided filter, полностью (яркость x AE); при включённых полосах пирамиды — выкл., если строка не задана явно", "Tone: light through the guided filter, fully (luminance x AE); off with the pyramid's bands on unless the line is set explicitly")));
        k.add(n(post, "hybrid_why_fusion_edge_lo", "0.3", Lang.t("Тон: у резких границ (рама окна) guided filter вместо пирамиды (без ореола вдоль рамы), начало: отличие света пикселя от грубого уровня, EV; нижняя = верхней — выкл.; при включённых полосах пирамиды — выкл., если строка не задана явно", "Tone: at strong edges (a window frame) the guided filter instead of the pyramid (no glow along the frame), start: the pixel's light against the coarsest level, EV; lo = hi: off; off with the pyramid's bands on unless the line is set explicitly")));
        k.add(n(post, "hybrid_why_fusion_edge_hi", "1.0", Lang.t("Тон: у резких границ guided filter полностью (EV); при включённых полосах пирамиды — выкл., если строка не задана явно", "Tone: at strong edges the guided filter fully (EV); off with the pyramid's bands on unless the line is set explicitly")));
        k.add(n(post, "hybrid_why_fusion_edge_floor", "0.0078125", Lang.t("Тон: порог света (яркость x AE), ниже которого глубокие тени считаются одним уровнем (без доли на их шуме)", "Tone: the light floor (luminance x AE) under which the deep shadows count as one level (no share on their noise)")));
        // P391: the edge-aware bands of the WHY pyramid, its continuous depth, the noise-bound deep floor and band gain
        // P395: the orange-yellows towards yellow
        k.add(n(post, "hybrid_why_yellow_hue", "1.0", Lang.t("Цвет (vivo): насыщенные жёлто-оранжевые (фасады, жёлтая листва, машины) поворачиваются к жёлтому до 8°, как у ArkCam; кожа, оранжевые, зелень и бледные стены как раньше; 0 — выкл. На других телефонах по умолчанию 0",
                "Colour (vivo): saturated orange-yellows (facades, yellow foliage, cars) turn up to 8 deg towards yellow, as ArkCam's; skin, oranges, greens and pale walls as before; 0: off. Other phones: 0 by default")));
        // P398: pale light tones towards white
        k.add(n(post, "hybrid_why_hl_neutral", "1.0", Lang.t("Цвет: бледные цвета у самого белого (освещённые солнцем белые рамы, стены, плафоны) уходят в белый, а не в кремовый; голубое небо, море и насыщенные цвета как раньше; 0 — выкл.",
                "Colour: pale colours at the very top (sunlit white frames, walls, lamps) go to white, not cream; blue sky, sea and saturated colours as before; 0: off")));
        // P394: the sharpening's overshoot held and the pixel steps smoothed along strong edges
        k.add(n(post, "hybrid_why_edge_hold", "1.0", Lang.t("Тон: на резких границах (рама окна, край лампы на фоне света) резкость сглаживается вдоль границы и не выходит за яркость соседей — без тёмной обводки и лесенки; текстура как раньше; 0 — выкл.",
                "Tone: at strong edges (a window frame, a lamp's rim against light) the sharpening is smoothed along the edge and held within its neighbours' light - no dark outline, no pixel steps; texture as before; 0: off")));
        k.add(n(post, "hybrid_why_fusion_bands", "1", Lang.t("Тон: у резких границ полосы пирамиды берутся из попиксельного слияния (без ореолов у рам окон, веток, ламп, горизонта); 0 — выкл., пирамида как раньше",
                "Tone: at strong edges the pyramid's bands come from the per-pixel fusion (no halos at window frames, twigs, lamps, the horizon); 0: off, the pyramid as before")));
        k.add(n(post, "hybrid_why_fusion_bands_lo", "1.2", Lang.t("Тон, полосы: перепад света у границы (EV), с которого она берёт полосы попиксельного слияния (текстура ±0,5 EV остаётся пирамиде)",
                "Tone, bands: the light step at an edge (EV) from which it takes the per-pixel fusion's bands (a ±0.5 EV texture stays the pyramid's)")));
        k.add(n(post, "hybrid_why_fusion_bands_hi", "1.8", Lang.t("Тон, полосы: перепад света у границы (EV), с которого полосы попиксельного слияния берутся полностью",
                "Tone, bands: the light step at an edge (EV) from which the per-pixel fusion's bands are taken fully")));
        k.add(n(post, "hybrid_why_fusion_bands_reach", "3", Lang.t("Тон, полосы: охват вокруг границы на каждом уровне, пикс. уровня",
                "Tone, bands: reach round an edge at every level, level px")));
        k.add(n(post, "hybrid_why_fusion_bands_fall", "0.25", Lang.t("Тон, полосы: спад меры границы на пиксель уровня от неё, доля верхнего порога (сильная граница охватывает больше, пик текстуры почти не расходится)",
                "Tone, bands: the edge measure's fall per level px away from it, a share of the upper threshold (a strong edge reaches further, a texture's peak hardly spreads)")));
        k.add(n(post, "hybrid_why_fusion_bands_strength", "1.0", Lang.t("Тон, полосы: сила (0 — пирамида, кроме предела шума мелких полос)",
                "Tone, bands: strength (0: the pyramid, but for the fine bands' noise limit)")));
        k.add(n(post, "hybrid_why_fusion_bands_noise", "2", Lang.t("Тон, полосы: граница считается только сверх стольких сигм шума её меры (вычитаются в квадратуре; шум — не граница); 0 — выкл.",
                "Tone, bands: an edge counts only beyond this many noise sigmas of its measure (taken out in quadrature; noise is no edge); 0: off")));
        k.add(n(post, "hybrid_why_fusion_depth_smooth", "1", Lang.t("Тон: глубина пирамиды без скачков по разрешению (нижняя полоса по дробной глубине, с полосами); 0 — целые уровни, как раньше",
                "Tone: the pyramid's depth without jumps across resolutions (the lowest band by the fractional depth, with the bands); 0: whole levels, as before")));
        k.add(n(post, "hybrid_why_deep_noise", "0.008", Lang.t("Тон: глубокие тени поднимаются ярким слоем не дальше, чем позволяет шум кадра — предел шума в тенях (доля яркости слияния); 0 — выкл.",
                "Tone: deep shadows lifted by the bright layer only as far as the frame's noise allows - the shadow noise limit (share of fused luma); 0: off")));
        k.add(n(post, "hybrid_why_noise_scale", "1.0", Lang.t("Тон: поправка модели шума к шуму whyLow (после шумодава) для пределов шума тона",
                "Tone: noise-model correction to whyLow's noise (after the denoise) for the tone's noise limits")));
        k.add(n(post, "hybrid_why_fusion_noise_lo", "2", Lang.t("Тон: усиление мелких полос пирамиды сверх кривой — только где полоса выше шума, начало (сигмы шума); нижняя = верхней — выкл.",
                "Tone: the pyramid's fine-band gain beyond the curve only above the noise, start (noise sigmas); lo = hi: off")));
        k.add(n(post, "hybrid_why_fusion_noise_hi", "4", Lang.t("Тон: усиление мелких полос сверх кривой полностью (сигмы шума)",
                "Tone: the fine-band gain beyond the curve fully (noise sigmas)")));
        k.add(n(post, "hybrid_why_fusion_noise_levels", "3", Lang.t("Тон: сколько мелких уровней пирамиды ограничены шумом (до 4)",
                "Tone: how many fine pyramid levels the noise limits (up to 4)")));
        k.add(n(post, "hybrid_why_sun_bloom", "1.0", Lang.t("Тон: ореол вокруг полностью клипнутого ядра (солнце на закате), 0 — выкл.", "Tone: glow round a fully clipped core (a setting sun), 0 = off")));
        k.add(n(post, "hybrid_why_sun_bloom_radius", "0.033", Lang.t("Тон: радиус ореола солнца, доля короткой стороны кадра", "Tone: radius of the sun glow, share of the frame's shorter side")));
        k.add(n(post, "hybrid_why_vibrance_hl", "1.0", Lang.t("Тон: насыщенность светлых тонов (небо на закате), яркость не меняется; 0 — выкл.", "Tone: vibrance of the light tones (a sunset sky), lightness kept; 0 = off")));
        // P393: the light-tone vibrance's shape for pale colour (why/combine.glsl vibHlShapeU)
        k.add(s(post, "vibrance_hl_shape", true, Lang.t("Тон: насыщенность светлых тонов не усиливает оттенок баланса белого на бледных стенах, потолке, фасадах (выкл — как раньше, P341)",
                "Tone: the light-tone vibrance leaves the white balance's tint of pale walls, ceilings and facades as it is (off: as before, P341)")));
        // P419: the reference app's vibrance in one gain (why/combine.glsl ARK_VIBRANCE)
        k.add(s(post, "ark_vibrance", true, Lang.t("Цвет: сочность как в Арк — одна формула с защитой кожи (выкл — прежние формы P341 / P404 и сочность «Цвета»)",
                "Colour: the vibrance as in Ark, one gain with the skin protection (off: the former P341 / P404 forms and the look's vibrance)")));
        // P378: the WHY tone's sky term (WhySky / WhySkyMask; why/combine.glsl SKY)
        k.add(n(post, "hybrid_why_sky", "1.0", Lang.t("Тон: небо (облака нейтральные, небо ниже и объёмнее, без белой пелены на облаках; в контровом свете яркость неба не меняется), сила; 0 — выкл., как раньше",
                "Tone: the sky (neutral clouds, a lower and more modelled sky, no white veil on the clouds; a backlit sky keeps its brightness), strength; 0 = off, as before")));
        k.add(n(post, "hybrid_why_sky_gain", "0.94", Lang.t("Тон, небо: множитель света неба (не в контровом свете)", "Tone, sky: gain of the sky's light (not in a backlit scene)")));
        k.add(n(post, "hybrid_why_sky_macro", "0.91", Lang.t("Тон, небо: контраст полутонов в небе, доля от «Контраста»", "Tone, sky: midtone contrast in the sky, a share of “Contrast”")));
        k.add(n(post, "hybrid_why_sky_top", "2.0", Lang.t("Тон, небо: запас плеча над белым в небе, x (без Bento)", "Tone, sky: room of the shoulder above white in the sky, x (without Bento)")));
        k.add(n(post, "hybrid_why_sat_light_level_lo", "0.25", Lang.t("Тон с запасом Bento: цветной свет: уровень света (x AE), с которого начинаются веса ядра", "Tone with Bento headroom: coloured light - light level (x AE) where the kernel's weights start")));
        k.add(n(post, "hybrid_why_sat_light_level_hi", "0.6", Lang.t("Тон с запасом Bento: цветной свет: уровень света полных весов ядра", "Tone with Bento headroom: coloured light - light level of the full kernel weights")));
        k.add(n(post, "hybrid_why_fusion_mono", "0.04", Lang.t("Тон: подъём слияния к монотонной огибающей (нет тёмного кольца у лампы)", "Tone: fusion lifted to its monotonic envelope (no dark ring round a lamp)")));
        // P401: a daylit window in a room as build 31091 drew it
        k.add(n(post, "hybrid_why_window_31091", "1.0", Lang.t("Тон: в комнате освещённое окно (облака, солнце, краны, надстройки за стеклом) как в сборке 31091, а не выбелено; сама комната, лампы и сцены на улице как сейчас; 0 — выкл., 1 — полностью",
                "Tone: in a room, a daylit window (clouds, the sun, cranes, superstructures behind the glass) as build 31091 drew it, not bleached; the room itself, lamps and outdoor scenes as now; 0: off, 1: fully")));
        k.add(n(post, "hybrid_why_deep_floor", "2.0", Lang.t("Тон: глубокие тени берут яркий слой слияния (порог в сигмах под центром; комната под окном не уходит в чёрный); 0 — выкл.",
                "Tone: deep shadows take the fusion's bright layer (threshold in sigmas under the centre; a room under a window is not crushed to black); 0 = off")));
        k.add(n(post, "hybrid_why_macro_toe", "1", Lang.t("Тон: контраст ниже середины степенью, без сдвига чёрного (тени не обрезаются в чёрный); 0 — прямая, как раньше",
                "Tone: contrast below the middle as a power, no black offset (shadows are not clipped to black); 0 = the line, as before")));
        // P393: the light tones of a saturated colour keep its mid-tone chroma (why/combine.glsl LIGHT_CHROMA, AgX only)
        k.add(n(post, "hybrid_why_light_chroma", "1.0", Lang.t("Тон: насыщенные оранжево-жёлтые цвета в светах (листва на солнце) держат насыщенность полутонов, оттенок сдвигается к AgX только там, где sRGB его не удержит; кожа, зелень, синие, лампы, небо и бледные цвета как раньше; 0 — выкл., как раньше",
                "Tone: saturated orange-yellow colours in the light tones (sunlit leaves) keep their mid-tone saturation, the hue moves towards AgX's only where sRGB cannot hold it; skin, greens, blues, lamps, the sky and pale colours as before; 0 = off, as before")));
        k.add(n(post, "hybrid_why_hr_detail", "0.35", Lang.t("Тон, света Bento выше 4x: деталь светов", "Tone, Bento highlights above 4x: highlight detail")));
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
        // P356: ETTR over the shade's switch (A/B without the UI)
        k.add(sAuto(cap, "ettr", Lang.t("ETTR (экспозиция вправо): 1 — вкл, 0 — выкл поверх переключателя в шторке",
                "ETTR (expose to the right): 1 on, 0 off over the shade's switch")));
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
        // P371: recipes of the post programs, made again from the installed APK by that warm-up (the first shot after an install)
        k.add(s(post, "gl_warm_up_recipes", true, Lang.t("Рецепты шейдеров обработки (ассет и define-ы) сохраняются, и фоновый прогрев собирает их заново из установленного APK: первый снимок после обновления не компилирует ~46 шейдеров (~0,8 с). Выкл — как до P371",
                "Recipes of the processing shaders (asset and defines) are kept and the background warm-up builds them again from the installed APK: the first shot after an update does not compile ~46 shaders (~0.8 s). Off: as before P371")));
        // P371: the merge worker's result at its completion line, its CPU priority
        k.add(s(merge, "ok_read", true, Lang.t("Результат воркера читается по строке завершения, его выход (~0,1 с) — в фоне (до 16 Мп). Выкл — ждать выхода процесса, как до P371",
                "The worker's result is read at its completion line, its exit (~0.1 s) in the background (up to 16 MP). Off: wait for the process to exit, as before P371")));
        k.add(s(merge, "worker_prespawn", false, Lang.t("Воркер склейки запускается при нажатии (до 16 Мп, без снимка в очереди): его старт и прогрев GPU идут во время съёмки серии (копия процесса камеры в эти 0,4 с; для замера на телефоне). Выкл — запуск после подготовки кадров, как до P371",
                "The merge worker starts at the press (up to 16 MP, no shot queued): its start and GPU warm-up run during the series (a fork of the camera process in those 0.4 s; for a phone measurement). Off: started after the frames' preparation, as before P371")));
        k.add(n(merge, "worker_nice", "10", Lang.t("Приоритет процессора воркера склейки (nice −8…19; 10 — приоритет потока обработки, как до P371; 0 — для замера на телефоне; вне диапазона — не менять)",
                "CPU priority of the merge worker (nice -8..19; 10: the processing thread's, as before P371; 0: for a phone measurement; outside the range: unchanged)")));
        k.add(s(merge, "zero_copy_read", true, Lang.t("Результат воркера (RGB и флаги клипа) читается на месте из его памяти (memfd), без копии в новые буферы (до 1 ГБ). Выкл — копия, как до P371",
                "The worker's result (the RGB and the clip flags) is read in place from its memory (memfd), without a copy into fresh buffers (up to 1 GB). Off: copied, as before P371")));
        k.add(s(post, "black_scan_bulk", true, Lang.t("Уровень чёрного по данным кадра: строки выборки копируются целиком (те же отсчёты, ~20 мс быстрее). Выкл — чтение по отсчёту, как до P371",
                "The black level from the frame's data: the sampled rows copied whole (the same samples, ~20 ms faster). Off: a read per sample, as before P371")));
        // P386: a stream proven without its black pedestal gives its floor to its frames without deep shade (PedestalMemory)
        k.add(s(post, "pedestal_memory", true, Lang.t("Уровень чёрного по данным кадра: поток, пришедший без чёрного пьедестала (так показал кадр с глубокой тенью или RAW-видоискатель), отдаёт свой чёрный кадрам без глубокой тени (небо, фасад на солнце) до перезапуска приложения. Выкл — каждый кадр сам по себе, как до P386",
                "The black level from the frame's data: a stream that came without its black pedestal (shown by a frame with deep shade or the RAW viewfinder) gives its black to frames without deep shade (the sky, a sunlit facade) until the app restarts. Off: each frame on its own, as before P386")));
        k.add(s(post, "thermal_async", true, Lang.t("Температурный статус после склейки запрашивается в фоне (вызов системы не задерживает обработку). Выкл — на потоке обработки, как до P371",
                "The thermal status after the merge is read in the background (the system call does not hold the processing). Off: on the processing thread, as before P371")));
        // P242: the merge worker's GPU priority (A/B, WorkerSpawn.environment)
        k.add(s(merge, "worker_gpu_normal_priority", false, Lang.t("Склейка в воркере с обычным приоритетом GPU (сравнение A/B: может склеивать быстрее, видоискатель может подтормаживать). Выкл — средний приоритет (P364)",
                "The merge worker at the normal GPU priority (A/B: the merge may run faster, the viewfinder may stutter). Off: the medium priority (P364)")));
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
        k.add(s(diag, "diag_sync", false, Lang.t("Диагностика: превью этапов считаются и пишутся на потоке обработки, как до P371 (выкл — в фоне, снимок не ждёт)",
                "Diagnostics: the stage previews are computed and written on the processing thread, as before P371 (off: in the background, the shot does not wait)")));
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
