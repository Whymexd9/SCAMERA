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
            "keep_burst"))); // P191: «Сохранять RAW-серию» in Diagnostics

    private static List<Key> all;

    private DevKeys() {}

    private static Key s(String g, String key, boolean def, String text) { return new Key(g, key, Kind.SWITCH, def ? "1" : "0", text); }
    private static Key sAuto(String g, String key, String text) { return new Key(g, key, Kind.SWITCH, null, text); }
    private static Key n(String g, String key, String def, String text) { return new Key(g, key, Kind.NUMBER, def, text); }

    public static synchronized List<Key> all() {
        if (all != null) return all;
        final String cap = Lang.t("Захват и сессия", "Capture and session");
        final String vf = Lang.t("Видоискатель", "Viewfinder");
        final String merge = Lang.t("Склейка Hybrid (воркер)", "Hybrid merge (worker)");
        final String post = Lang.t("Обработка", "Processing");
        final String xiaomi = Lang.t("Xiaomi 17 Ultra (теле)", "Xiaomi 17 Ultra (tele)");
        final String vivo = Lang.t("vivo", "vivo");
        final String pixel = Lang.t("Pixel 10 Pro / Pro XL", "Pixel 10 Pro / Pro XL");
        final String diag = Lang.t("Диагностика и A/B", "Diagnostics and A/B");
        List<Key> k = new ArrayList<>();
        // capture and session
        k.add(s(cap, "zsl_memfd_ring", true, Lang.t("ZSL-кольцо в общей памяти (memfd): кадры копируются сразу, камере возвращаются буферы. Выкл — старое кольцо из буферов камеры", "ZSL ring in shared memory (memfd): frames copied on arrival, buffers go back to the camera. Off: the old ring of camera buffers")));
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
        k.add(s(cap, "mosaic_awb", true, Lang.t("ISZ без ремозаика: баланс белого по кадру на кривой камеры, а не от камеры", "ISZ without remosaic: white balance from the frame on the camera curve, not the camera's")));
        // P212f: the mosaic white balance's locus (W5), robust statistic (W4) and ISP teacher (W5, log only)
        k.add(s(cap, "mosaic_awb_locus", true, Lang.t("Баланс белого мозаики: гладкая кривая камеры по точкам с весами, с продолжением за края и кривой из матриц DNG, пока точек мало. Выкл — ломаная P121 с обрезкой на краях",
                "Mosaic white balance: a smooth weighted curve of the camera, extended beyond its ends, from the DNG matrices while it has few points. Off: the P121 polyline clamped at its ends")));
        k.add(s(cap, "mosaic_awb_v2", false, Lang.t("Баланс белого мозаики: устойчивая оценка (клип по пикселям, веса по шуму, виньетирование, кроп снимка, до 3 кадров, своя точность) вместо P121. Выкл — только в лог рядом с P121",
                "Mosaic white balance: the robust estimate (per-site clipping, noise weights, shading, the photo's crop, up to 3 frames, its own confidence) instead of P121. Off: only logged beside P121")));
        k.add(s(cap, "mosaic_awb_teacher", true, Lang.t("Баланс белого мозаики: «учитель» — в обычном режиме запоминает, какую нейтраль ISP выбирает для нашей оценки кадра; только сбор и лог, на фото не влияет",
                "Mosaic white balance teacher: in the plain mode it learns which neutral the ISP picks for our estimate of the frame; collected and logged only, never applied to a photo")));
        k.add(s(cap, "oplus_ccm", true, Lang.t("OPPO: цветовая матрица com.oplus.custom.ccm вместо единичной", "OPPO: the com.oplus.custom.ccm colour matrix instead of an identity one")));
        k.add(s(cap, "fov_check", true, Lang.t("Замер реального поля зрения вендорных модулей", "Measure the real field of view of vendor modules")));
        k.add(s(cap, "hybrid_fast_capture", true, Lang.t("Сброс очереди HAL перед серией (задержка затвора ~0.4 → ~0.15 с)", "Flush the HAL request queue before the series (shutter lag ~0.4 -> ~0.15 s)")));
        k.add(s(cap, "hybrid_planner_adaptive", true, Lang.t("Планировщик серии подстраивает L / S / ES под клип кадров кольца", "The series planner fits L / S / ES to the clip of the ring frames")));
        k.add(n(cap, "hybrid_planner_l_ev", "1", Lang.t("Планировщик: EV длинного кадра над обычным", "Planner: EV of the long frame above the normal one")));
        k.add(n(cap, "hybrid_planner_s_ev", "3", Lang.t("Планировщик: EV короткого кадра под обычным", "Planner: EV of the short frame below the normal one")));
        k.add(n(cap, "hybrid_planner_es_ev", "6", Lang.t("Планировщик: EV сверхкороткого кадра под обычным", "Planner: EV of the extra-short frame below the normal one")));
        k.add(n(cap, "hybrid_long_boost_ev", "1.1", Lang.t("Добавка EV длинного кадра к стоковому плану (0 — стоковый)", "Extra EV of the long frame over the stock plan (0: stock)")));
        // viewfinder
        k.add(s(vf, "mosaic_preview", true, Lang.t("Модуль, измеренный как мозаика, сразу показывает RAW-видоискатель", "A module measured as a mosaic starts on the RAW viewfinder")));
        k.add(n(vf, "vf_priority", "1", Lang.t("Высокий приоритет потока видоискателя (0 — обычный)", "High priority of the viewfinder thread (0: normal)")));
        k.add(n(vf, "vf_gpu_priority", "1", Lang.t("Высокий приоритет GPU-контекста видоискателя (0 — обычный)", "High priority of the viewfinder GPU context (0: normal)")));
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
        k.add(n(merge, "hybrid_bento_invalid", null, Lang.t("Bento: порог негодных клеток", "Bento: invalid cell threshold")));
        k.add(n(merge, "hybrid_mosaic_block", null, Lang.t("Мозаика: блок цвета (1, 2, 4) вместо измеренного", "Mosaic: colour block (1, 2, 4) instead of the measured one")));
        k.add(n(merge, "hybrid_mosaic_share", null, Lang.t("Мозаика: доля голосов для решения", "Mosaic: vote share to decide")));
        k.add(n(merge, "hybrid_mosaic_native_night_kernel_scale", null, Lang.t("Нативная мозаика ночью: масштаб ядра", "Native mosaic at night: kernel scale")));
        k.add(n(merge, "hybrid_mosaic_native_night_edge_scale", null, Lang.t("Нативная мозаика ночью: масштаб на краях", "Native mosaic at night: edge scale")));
        k.add(n(merge, "hybrid_mosaic_tetra_night_kernel_scale", null, Lang.t("Tetra ночью: масштаб ядра", "Tetra at night: kernel scale")));
        k.add(n(merge, "hybrid_mosaic_tetra_night_edge_scale", null, Lang.t("Tetra ночью: масштаб на краях", "Tetra at night: edge scale")));
        k.add(n(merge, "hybrid_mosaic_tetra_night_flat_scale", null, Lang.t("Tetra ночью: масштаб на ровных местах", "Tetra at night: flat scale")));
        k.add(n(merge, "hybrid_mosaic_tetra_kernel_scale", null, Lang.t("Tetra днём: масштаб ядра", "Tetra by day: kernel scale")));
        k.add(n(merge, "hybrid_gain_measured", null, Lang.t("Измерять усиление кадров (1/0)", "Measure the frames' gain (1/0)")));
        k.add(n(merge, "hybrid_noise_measured", null, Lang.t("Измерять шум и поправлять модель (1/0)", "Measure the noise and correct the model (1/0)")));
        k.add(n(merge, "hybrid_la_threads", null, Lang.t("Потоков локального выравнивания F6", "Threads of the F6 local alignment")));
        k.add(n(merge, "hybrid_la_stream", null, Lang.t("F6 полосами вместе со склейкой (0 — сначала всё поле)", "F6 in bands alongside the merge (0: the whole field first)")));
        k.add(n(merge, "hybrid_la_scam", null, Lang.t("Понижение приоритета потоков F6", "Lower priority of the F6 threads")));
        k.add(s(merge, "hybrid_clip_flags", true, Lang.t("Флаги клипа каналов от воркера для восстановления светов", "The worker's channel clip flags for the highlight recovery")));
        // processing
        k.add(n(post, "post_priority", null, Lang.t("Приоритет потока обработки (nice; пусто — не менять)", "Priority of the processing thread (nice; empty: unchanged)")));
        k.add(n(post, "gpu_low_priority", "1", Lang.t("Низкий приоритет GPU-контекста обработки (0 — обычный)", "Low priority of the processing GPU context (0: normal)")));
        k.add(s(post, "effmap_real", true, Lang.t("Карта числа кадров для шумоподавления — реальная (выкл — старая)", "Frame-count map for the denoise: the real one (off: the old one)")));
        // P214d: ScamDenoise.lscTerm
        k.add(s(post, "denoise_lsc", true, Lang.t("Шумоподавление сильнее к углам кадра по коррекции виньетирования (g² карты затенения объектива)", "Denoise harder towards the frame corners after the vignetting correction (g² of the lens-shading map)")));
        k.add(n(post, "denoise_lsc_max", "4", Lang.t("Шумоподавление к углам: предел множителя дисперсии (1–16; 4 — шум в углах считается вдвое сильнее)", "Corner denoise: cap of the variance multiplier (1-16; 4: the corner noise counts twice as strong)")));
        // P214e: ArkLumaSharpen.smoothedSource (R1)
        k.add(s(post, "sharpen_r1", true, Lang.t("Резкость мозаичных снимков (ISZ Quad/Tetra, FULLRES) по яркости, сглаженной 3×3: решётка мозаики почти не усиливается. Выкл — резкость как у снимков Bayer",
                "Sharpening of mosaic shots (ISZ Quad / Tetra, FULLRES) on the 3x3-smoothed luma: the mosaic lattice is hardly lifted. Off: the sharpening of Bayer shots")));
        k.add(n(post, "jpeg_threads", null, Lang.t("Потоков кодирования JPEG (1 — один поток, прогрессивный; пусто — все ядра)", "Threads of the JPEG encode (1: one thread, progressive; empty: every core)")));
        k.add(sAuto(post, "rgba_upload", Lang.t("Загрузка RGB в GPU как RGBA (по умолчанию решает приложение)", "RGB uploaded to the GPU as RGBA (default: chosen by the app)")));
        k.add(n(post, "hybrid_isp_ccm", "1", Lang.t("vivo: цветовая матрица ISP по кадру (0 — матрицы DNG)", "vivo: the ISP colour matrix of the frame (0: the DNG matrices)")));
        k.add(n(post, "hybrid_dn_dark_chroma_hi", "4", Lang.t("Шумодав: цвет в тенях, верхний порог (x1e-4)", "Denoise: colour in the shadows, upper threshold (x1e-4)")));
        k.add(n(post, "hybrid_dn_dark_chroma_lo", "1.5", Lang.t("Шумодав: цвет в тенях, нижний порог (x1e-4)", "Denoise: colour in the shadows, lower threshold (x1e-4)")));
        k.add(n(post, "hybrid_dn_dark_chroma_noise", "0.35", Lang.t("Шумодав: цвет в тенях, доля шума", "Denoise: colour in the shadows, noise share")));
        k.add(n(post, "hybrid_ark_hr_mix", "0.5", Lang.t("Тон, света Bento выше 4x: пик к p99.9 (доля)", "Tone, Bento highlights above 4x: peak towards p99.9 (share)")));
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
        // vivo
        k.add(s(vivo, "vivo_sdk_fullres", true, Lang.t("X200 Ultra, основная камера 3: SDK RAW 50 Мп, последовательная съёмка после нажатия, Hybrid", "X200 Ultra, main camera 3: SDK 50 MP RAW, serial post-shutter capture, Hybrid")));
        // P180 / P184: FULLRES without hardware remosaic on the X100 / X300 Ultra - on (the owner tests it), off by these
        k.add(s(cap, "oppo_fullres", true, Lang.t("OPPO Find X7 Ultra: FULLRES 50 Мп без аппаратного ремозаика (свои AE и баланс белого)",
                "OPPO Find X7 Ultra: FULLRES 50 MP without hardware remosaic (own AE and white balance)")));
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
        // P198: a native 50 MP FULLRES shot from the ZSL ring (200 MP stays serial PSL)
        k.add(s(cap, "fullres_zsl", true, Lang.t("FULLRES 50 Мп: кадры из ZSL-кольца до нажатия (200 Мп всегда после нажатия). Выкл — вся серия после нажатия",
                "FULLRES 50 MP: frames from the ZSL ring before the press (200 MP always after it). Off: the whole series after the press")));
        k.add(s(cap, "fullres_plain_merge", true, Lang.t("FULLRES 50 Мп: на время склейки камера в обычном режиме (меньше нагрев и памяти). Выкл — 50 Мп поток идёт и во время склейки",
                "FULLRES 50 MP: the camera runs its plain session while the shot merges (less heat and memory). Off: the 50 MP stream keeps running")));
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
