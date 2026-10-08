# HEIC 10 бит — отчёт (ветка `heic10`, от main 83831ad, 2026-10-08)

## Что сделано

**Настройка** «HEIC 10 бит» / "10-bit HEIC" (`pref_heic_10bit`, по умолчанию выкл.) — сразу под «Качество HEIC»,
видна только при формате HEIC. Строка неактивна с причиной, если Android < 13 или нет кодировщика HEVC Main10 с входом
P010 и кадром 512×512; тогда снимок — обычный 8-битный HEIC.

**Новые файлы** (`app/src/main/java/com/particlesdevs/photoncamera/processing/heif/`):
- `HeifContainerWriter.java` — контейнер HEIF (чистая Java): ftyp `heic` + `mif1 heic heix miaf`, meta (hdlr pict,
  pitm = grid, скрытые тайлы hvc1, grid, Exif), iref dimg/cdsc, ipco hvcC (essential) / ispe / colr nclx
  (BT.709, sRGB transfer 13, full range) / pixi 3×10, iloc v1 → mdat. Exif = смещение TIFF (6) + "Exif\0\0" + TIFF,
  как у HeifWriter (так его читает ExifInterface платформы).
- `HevcNal.java` — Annex-B → NAL, длина-префиксные сэмплы без VPS/SPS/PPS, разбор SPS, сборка hvcC.
- `P010.java` — RGBA_1010102 → P010 (BT.709 full range, хрома = среднее блока 2×2, 10 бит в старших битах LE-слова,
  край повторяется в паддинге).
- `TileGrid.java` — сетка тайлов 512×512.
- `Heic10Support.java` — доступность (API 33+, кодировщики REGULAR_CODECS), выбор: аппаратный > программный,
  с CQ > без CQ; `wanted()` читает список кодеков только при включённой настройке и формате HEIC.
- `Heic10Encoder.java` — MediaCodec HEVC Main10, COLOR_FormatYUVP010, I-frame interval 0, без B-кадров,
  BT.709/full/SDR VUI, CQ с качеством HEIC (иначе VBR 0,5–4 бит/пиксель), один входной буфер на тайл, синхронный цикл
  с таймаутами, csd из config-буфера / формата / IDR. Конвертация в P010 — пул потоков на полосу тайлов вперёд,
  полоса читается полосками (без копии всего кадра). Проверка SPS (10 бит, 4:2:0, 512×512). Кодек освобождается в
  finally, любая ошибка → false.
- `TenBitBitmaps.java` — обрезка / масштаб с сохранением RGBA_1010102, построчное чтение/запись, 8-битные копии.
- `processing/ml/Lanczos1010102.java` — Java-порт нативного Lanczos для 10 бит (те же веса, линейный свет).

**Изменены:** `GLCoreBlockProcessing` (RGB10_A2-цель + readback GL_UNSIGNED_INT_2_10_10_10_REV в RGBA_1010102, откат
на RGBA8, если драйвер не умеет), `PostPipeline` / `PostAb` / `HdrxProcessor` (флаг `tenBitOutput`), `GLImage`
(любая не-RGBA8 картинка → 8-битные полоски: проход гейн-мапы Ultra HDR, гистограмма галереи), `ZoomController.crop`,
`VivoPostDownscale` (10-битная ветка для всех ядер), `PhotoOutput`, настройки (`PhotoFormat`, `PreferenceKeys`,
`SettingsAvailability`, `SettingsActivity`, preferences.xml, строки en/ru).

## Как идёт 10-битный путь

1. HdrxProcessor: `pipeline.tenBitOutput = Heic10Support.wanted()` (HEIC + настройка + телефон умеет).
2. PostPipeline: последний проход (тот же шейдер RotateWatermark) рисует в RGB10_A2, тайлы читаются в
   Bitmap RGBA_1010102 (те же 4 байта/пиксель).
3. Дальше картинка остаётся 10-битной: гейн-мапа Ultra HDR (8-битная копия полосками для текстуры), уменьшение
   гибрида (lanczos/bicubic — Java-порт; area/bilinear — canvas с сохранением конфига), цифровой зум, debug-оверлей.
4. PhotoOutput: HEIC → Heic10Encoder → файл .heic (Main10). JPEG / Ultra HDR / WebP / 8-битный HEIC получают одну
   ARGB_8888-копию (`Bitmap.copy`), 10-битный оригинал освобождается, когда больше не нужен.

**Откаты:** RGB10_A2 не собрался / readback отклонён → 8-битный кадр → 8-битный HEIC. Кодировщик отказал на configure →
следующий кандидат. Ошибка кодирования / таймаут → 8-битный HEIC через HeifWriter → JPEG. Снимок не теряется.
**Опция выключена:** тот же конструктор, формат, цель, readback, вызов HeifWriter, что и раньше (проверяется
`check_heic10.py` source guard и `PhotoOutputHeic10Test.defaultPathNeverTouchesTheTenBitWriter`).

## Тесты (локально, Windows)

- Полный список CI `testDebugUnitTest`: 63 класса, **406 тестов, 0 падений**. Новых классов 9, 52 теста
  (HevcNal, HeifContainerWriter, P010, TileGrid, Heic10Support, TenBitBitmaps, Lanczos1010102, TenBitReadback,
  PhotoOutputHeic10); PhotoFormatSettingsTest дополнен строкой 10 бит.
- `tools/check_heic10.py --self-test` (pillow-heif 1.8.0 / libheif 1.23.4 / x265): 3 сетки (1100×700 в 3×2 тайлах
  512, 300×200 в тайлах 128, один тайл 256) — libheif декодирует **10 бит**, размер верный, средняя ошибка
  0,53–1,06 кода, p99 на градиентах 2–4 кода, Exif читается libheif и Pillow; 4 испорченных варианта отклонены;
  8-битный путь не изменён. Добавлен в CI рядом с `check_ultrahdr.py`.
- `check_settings_model.py` PASS, `check_ui_language.py` PASS, `check_ultrahdr.py --self-test` PASS.
- `check_lanczos_downscale.py`: проверка порядка этапов PASS; нативная часть не запускалась — на этом Windows нет
  g++ (нативный код не трогал, в CI она идёт).
- Debug APK собран (`app/build/outputs/apk/debug/SCAMERA-0.98-27074-debug.apk`, не ставился; для выдачи — только
  APK из CI). `local.properties` удалён, `app/version.properties` восстановлен.

## OPPO (только чтение `dumpsys media.player`, телефон на лаунчере, без касаний 15 мин)

Android 16 (SDK 36). Кодировщики HEVC с Main10 и YUVP010: `c2.qti.hevc.encoder` (VBR/CBR, до 8192),
`c2.qti.hevc.encoder.cq` (только CQ 0–100, 128–512 px — тайловый кодировщик HeifWriter), `c2.qti.hevc.encoder.hdr`.
Выбор поставлен на `.cq` (CQ = качество HEIC). vivo не опрашивался.

## Не сделано / риски

- На телефоне не проверено (по правилам ничего не ставил): реальная раскладка входного Image P010, CQ на Main10,
  что кодировщик пишет VUI BT.709 full range. libheif-приложения берут цвет из нашего colr; декодер Android — из VUI.
  Если цвета HEIC отличаются от JPEG (сдвиг/вылиняние) — дело в VUI.
- Это не HDR: картинка та же SDR sRGB, только 10 бит точности. Ultra HDR остаётся только в JPEG.
- Во время сборки один раз выполнил `gradlew --stop` (останавливает все демоны Gradle этой версии).

## Проверка на OPPO / vivo

1. Настройки → Вывод: формат HEIC, включить «HEIC 10 бит» (строка активна; на vivo — если неактивна, причина в
   подписи). Для сравнения снять ту же сцену с выключенной опцией.
2. Что снимать: чистое небо с плавным градиентом, закат, гладкая стена с боковым светом, тени в помещении.
3. Сравнить 8-бит и 10-бит HEIC: в редакторе (Snapseed / Lightroom) поднять контраст/экспозицию неба — на 10-бит
   ступеньки (бандинг) должны быть мельче или исчезнуть.
4. Файл открывается в Google Photos, в нашей галерее (страница, EXIF-диалог: ISO, выдержка, размер; гистограмма),
   в системной галерее. Цвета как у JPEG («Также сохранять JPEG»).
5. PhotonLog: `PostPipeline: final image WxH RGBA_1010102 for the 10-bit HEIC`, затем
   `PhotoOutput: HEIC 10-bit written in N ms, WxH: c2.qti.hevc.encoder.cq CQ 90, T tiles, S KB, convert … ms,
   total … ms, SPS profile 2 … 10/10 bit` и `HEIC 10-bit saved in … ms`. При отказе — `HEIC 10-bit failed, 8-bit HEIC
   instead` и ошибка `Heic10Encoder` (её прислать). Сравнить время и размер с 8-битным HEIC.
6. Гибрид + Ultra HDR + «Также сохранять JPEG»: строка `VivoDownscale: lanczos (10-bit) … ms=` и JPEG с Ultra HDR.
7. Файл с телефона можно проверить: `python tools/check_heic10.py IMG.heic` (с pillow-heif — ещё и декод).
