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
            "hybrid", "hybrid_output", "hybrid_downsampler", "hybrid_profile", "hybrid_sharp_mode", "hybrid_motion_boost")));

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
        final String vivo = Lang.t("vivo X500 Pro Max: DCG", "vivo X500 Pro Max: DCG");
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
        k.add(s(cap, "oplus_ccm", true, Lang.t("OPPO: цветовая матрица com.oplus.custom.ccm вместо единичной", "OPPO: the com.oplus.custom.ccm colour matrix instead of an identity one")));
        k.add(s(cap, "fov_check", true, Lang.t("Замер реального поля зрения вендорных модулей", "Measure the real field of view of vendor modules")));
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
        // processing
        k.add(n(post, "post_priority", null, Lang.t("Приоритет потока обработки (nice; пусто — не менять)", "Priority of the processing thread (nice; empty: unchanged)")));
        k.add(n(post, "gpu_low_priority", "1", Lang.t("Низкий приоритет GPU-контекста обработки (0 — обычный)", "Low priority of the processing GPU context (0: normal)")));
        k.add(s(post, "effmap_real", true, Lang.t("Карта числа кадров для шумоподавления — реальная (выкл — старая)", "Frame-count map for the denoise: the real one (off: the old one)")));
        k.add(sAuto(post, "rgba_upload", Lang.t("Загрузка RGB в GPU как RGBA (по умолчанию решает приложение)", "RGB uploaded to the GPU as RGBA (default: chosen by the app)")));
        // Xiaomi 17 Ultra
        k.add(n(xiaomi, "xiaomi_opmode", String.valueOf(com.particlesdevs.photoncamera.capture.XiaomiTeleZoom.STOCK_OPERATION_MODE), Lang.t("Режим работы логической сессии (36866 = стоковый 0x9002, 0 — обычный)", "Operation mode of the logical session (36866 = stock 0x9002, 0 regular)")));
        k.add(s(xiaomi, "xiaomi_logical", true, Lang.t("Теле через логическую камеру", "Tele through the logical camera")));
        k.add(s(xiaomi, "xiaomi_hal_optics", true, Lang.t("Оптический зум по отчёту HAL", "Optical zoom from the HAL's report")));
        k.add(s(xiaomi, "xiaomi_opt_target", true, Lang.t("Цель оптического зума от HAL", "Optical zoom target from the HAL")));
        k.add(s(xiaomi, "xiaomi_lens_check", true, Lang.t("Проверка, какой объектив реально снимает", "Check which lens really captures")));
        k.add(s(xiaomi, "xiaomi_crop_mode", false, Lang.t("Режим кропа теле (опыт)", "Tele crop mode (experiment)")));
        k.add(s(xiaomi, "xiaomi_isz", false, Lang.t("ISZ теле (опыт)", "Tele ISZ (experiment)")));
        k.add(s(xiaomi, "xiaomi_tele_fallback", false, Lang.t("Запасной путь теле при сбое", "Tele fallback route on failure")));
        // vivo X500 Pro Max
        k.add(s(vivo, "dcg14", true, Lang.t("Кнопка DCG 14 бит на X500 Pro Max", "The DCG 14-bit button on the X500 Pro Max")));
        k.add(s(vivo, "dcg14_search", false, Lang.t("Автопоиск режима DCG/LOFIC (может ронять камеру vivo!)", "Automatic DCG / LOFIC mode search (may crash the vivo camera!)")));
        k.add(n(vivo, "dcg14_hdr_mode", "1", Lang.t("com.mediatek.hdrfeature.hdrMode: 1 — HDR фото, 3 — HDR видео (-1 — не слать)", "com.mediatek.hdrfeature.hdrMode: 1 photo HDR, 3 video HDR (-1: not sent)")));
        k.add(s(vivo, "vivo_isz", true, Lang.t("ISZ-модули X500 Pro Max (основная 2×, ширик 2×, теле 2×/4×) и подмена пакета для них", "X500 Pro Max ISZ modules (main 2x, ultra-wide 2x, tele 2x / 4x) and their package spoof")));
        k.add(s(vivo, "dcg14_spoof", true, Lang.t("vivo на MediaTek: камера всегда открывается как com.android.camera (камера vivo) — для DCG и ISZ", "vivo on MediaTek: the camera always opens as com.android.camera (the vivo camera), for DCG and ISZ")));
        k.add(n(vivo, "vivo_ui_module", "-1", Lang.t("com.vivo.current_ui_module — модуль интерфейса vivo в сессии (1 — Фото; X200 Pro отвечает ошибкой -38; -1 — не слать)", "com.vivo.current_ui_module, the vivo UI module in the session (1: Photo; the X200 Pro answers -38; -1: not sent)")));
        k.add(n(vivo, "dcg14_uimode", "-1", Lang.t("vivo.control.currentmodeS — режим интерфейса vivo для селектора (-1 — не слать)", "vivo.control.currentmodeS, the vivo UI mode for the selector (-1: not sent)")));
        k.add(n(vivo, "dcg14_shdr", "1", Lang.t("vivo.control.shdr_enable — сенсорный HDR (-1 — не слать)", "vivo.control.shdr_enable, the sensor HDR (-1: not sent)")));
        k.add(n(vivo, "dcg14_dcghdr", "1", Lang.t("vivo.control.EnableDCGHDR (-1 — не слать)", "vivo.control.EnableDCGHDR (-1: not sent)")));
        k.add(n(vivo, "dcg14_seamless", "1", Lang.t("vivo.control.session.seamlesscontrol (-1 — не слать)", "vivo.control.session.seamlesscontrol (-1: not sent)")));
        k.add(n(vivo, "dcg14_camera_scenario", "-1", Lang.t("cameraScenario вручную (-1 — нет)", "cameraScenario by hand (-1: none)")));
        k.add(n(vivo, "dcg14_sensor_scenario", "-1", Lang.t("sensorScenario вручную (-1 — нет)", "sensorScenario by hand (-1: none)")));
        // Pixel 10 Pro / Pro XL
        k.add(s(pixel, "pixel_modules", true, Lang.t("Добавлять ISZ-кропы логической камеры как модули", "Add the logical camera's ISZ crops as modules")));
        k.add(s(pixel, "pixel_full_res", true, Lang.t("Кнопка полного разрешения (50 Мп)", "The full-resolution button (50 MP)")));
        // diagnostics and A/B
        k.add(s(diag, "stab_trace", true, Lang.t("Журнал стабилизации в логе", "Stabilisation trace in the log")));
        k.add(sAuto(diag, "stab_trace_ois", Lang.t("Отсчёты OIS в журнале стабилизации (по умолчанию — на vivo)", "OIS samples in the stabilisation trace (default: on vivo)")));
        k.add(s(diag, "post_sync", false, Lang.t("Синхронные узлы обработки (замер времени)", "Synchronous processing nodes (timing)")));
        k.add(s(diag, "post_ab", false, Lang.t("A/B обработки: старый и новый конвейер на одном снимке", "Processing A/B: old and new pipeline on one shot")));
        k.add(s(diag, "post_ab_clamp", false, Lang.t("A/B: прежнее ограничение входа", "A/B: the former input clamp")));
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
