# Display P3 и HDR (HLG) в файлах — отчёт P46 (2026-10-08, от main a3e94db)

## Что сделано

**Две настройки** (Настройки → Конфиг, после строк AVIF, перед «Также сохранять JPEG»; обе общие для всех объективов —
префикс `pref_photo_` в `ModuleProfiles.GLOBAL_PREFIXES`; DeviceDefaults не трогал):

- «Цветовое пространство» / "Colour space" (`pref_photo_color_space`): `srgb` (по умолчанию) / `p3` — для всех форматов
  (JPEG, Ultra HDR, HEIC 8/10 бит, WebP, AVIF). RAW не меняется.
- «HDR в HEIC / AVIF» / "HDR in HEIC / AVIF" (`pref_photo_hdr`, по умолчанию выкл.). Строка видна только при формате HEIC
  или AVIF; неактивна с причиной, если: HEIC без «HEIC 10 бит» / 10-битный HEIC недоступен на телефоне; AVIF 8 бит (без
  потерь — можно: хранит 10 бит); Android < 13 (факт устройства `SettingsAvailability.hdrUnavailable`). От P3 не зависит:
  HDR всегда BT.2020.

### Display P3 в конвейере

Цвет конвейера: `ark/combine.glsl` (ArkCombine) — `intermediateToSRGB * sensorToIntermediate` даёт линейный sRGB, дальше
OKLab-грейдинг, AgX, гамма-ограничение по sRGB и степень 1/2.2. Простая замена матрицы на P3 меняла бы вид (AgX в
координатах P3: в среднем ΔE 0,7, до 8,6 — проверял моделью), поэтому сделано так:

- define `P3_OUT` (ArkCombine ставит `P3_OUT 1` только при `PostPipeline.p3Output`): рабочие координаты остаются линейным
  sRGB, но **ни одно отсечение не режет по sRGB**: отсечения нуля — по координатам P3 (`clampP3`: выборки цвета, возврат из
  OKLab), на вход AgX цвет подаётся со сниженной OKLab-хромой (L и тон те же) до неотрицательных sRGB, а потерянная хрома
  возвращается после тона (× C_вход / C_сниж), гамма-ограничение — по P3 (`fromOklabInGamutP3`), на выходе — P3 в кодировке
  1/2.2, плёночный «тоу» тем же множителем, что и в sRGB.
- Итог (GPU-проверка на продакшен-шейдере, `tools/check_colour_output.py --gl`): обычные цвета (600 случайных средней
  насыщенности) — 95 % пикселей **тот же снимок** (разница ≤ 0,6 кода 8 бит после перевода P3→sRGB); остальные 5 % — это
  места, где sRGB-рендер сам отсекал (тёмный насыщенный цвет после OKLab-контраста, яркий после AgX): в P3 хрома ×1,07,
  |ΔL| ≤ 0,002. Насыщенные цвета в пределах sRGB: хрома ≥ sRGB-рендера, |ΔL| ≤ 0,04. Цвета вне sRGB (P3-основные и смеси):
  28 из 40 остаются вне sRGB, хрома ×1,14 (медиана) — то, ради чего P3.
- `PostPipeline.p3Output` ставит HdrxProcessor из `Settings.colourSpace`; PostAb копирует флаг во второй прогон.

### Как каждый формат сообщает цвет

| Файл | sRGB (по умолч.) | Display P3 | HDR (HLG) |
|---|---|---|---|
| JPEG / Ultra HDR | как было (jpegli — без ICC; запасной Bitmap.compress — его sRGB-ICC) | APP2 ICC_PROFILE (наш Display P3 v4.3, 532 Б) сразу после SOI/JFIF, свой ICC кодировщика заменяется; в Ultra HDR — только в основном изображении (гейн-мапа скалярная, в любых первичных одна и та же); EXIF ColorSpace = 65535 (Uncalibrated) | — (JPEG остаётся SDR / Ultra HDR по своему переключателю) |
| WebP | как было | чанк ICCP после VP8X (флаг ICC; простой VP8/VP8L получает VP8X), ICCP кодировщика заменяется | — |
| HEIC 8 бит (HeifWriter) | как было | colr `prof` дописывается в готовый файл (`HeifColourPatch`: свойство в ipco, связь с grid и тайлами, nclx, если есть — первичные 12, сдвиг iloc при meta перед mdat); матрица YCbCr — как у кодировщика | — |
| HEIC 10 бит (наш контейнер) | nclx 1/13/1 full, VUI BT.709/SDR — байт в байт как было | nclx 12/13/1 full + colr `prof` (ICC) на тайлах и grid; VUI BT.709 (в MediaFormat нет P3) | HLG-картинка → P010 с матрицей **BT.2020 NCL**; VUI `COLOR_STANDARD_BT2020`, `COLOR_TRANSFER_HLG`, `COLOR_RANGE_FULL`; nclx 9/18/9 full, без ICC |
| AVIF (libavif) | CICP 1/13/1 (lossless 1/13/0) — байт в байт как было | CICP 12/13/1 + ICC (libavif пишет colr prof и nclx) | HLG-картинка (RGBA_1010102) 10/12 бит, CICP 9/18/9 (lossless 9/18/0); матрица 9 — и в RGB→YCbCr libavif |

ICC-профиль генерируется кодом (`IccProfiles`): v4.3 `mntr` RGB→XYZ, desc «Display P3», wtpt D50, chad Bradford D65→D50,
rXYZ/gXYZ/bXYZ = P3 (совпадают с профилем Apple/Android до 6e-4), TRC — `para` тип 3 (sRGB), ID = MD5. Skia (Robolectric,
NATIVE graphics) читает наши JPEG / WebP / Ultra HDR как `ColorSpace.Named.DISPLAY_P3`; littlecms (Pillow) переводит в
него с точностью 0,4 кода от матрицы.

### HDR (HLG) в HEIC / AVIF

- При включённой опции для снимка выполняется **тот же проход гейн-мапы, что у Ultra HDR** (`Settings.gainMapPass()` =
  `ultraHdr || hdrOutput`: линейный снимок, без отложенного GL-teardown и без ресайза внутри конвейера — как с Ultra HDR).
  JPEG получает карту только при Ultra HDR, HEIC/AVIF — только при HDR.
- `HlgRendition` (по пикселю, потоки по полосам 64 строки): база (sRGB или P3, sRGB-EOTF, как её декодирует любой
  просмотрщик) → карта по ISO 21496-1 для запаса HLG-дисплея (1000/203 = 2,3 ступени): `(sdr + 1/64)·2^(boost·w) − 1/64`,
  `w = min(1, log2(4,93) / HDRCapacityMax)` → BT.2020 → мягкое колено яркого канала выше 80 % запаса (тон сохраняется) →
  белый SDR = 203 кд/м² на эталонном HLG-дисплее 1000 кд/м² (BT.2408): обратный OOTF HLG (γ 1,2 по яркости BT.2020) и OETF
  HLG (ARIB STD-B67) → 10 бит full range. **Белый SDR = 75 % HLG** (0,7499). Тёмные/средние тона без буста — та же
  картинка, что SDR.
- Ошибка картинки (память) или кодирования → обычный SDR-файл, как без опции (в журнале причина). PQ не делал (только HLG;
  коды `TRANSFER_PQ` и ветка VUI ST2084 есть, кривой PQ нет).
- Память: + RGBA_1010102 HLG-картинка (4 Б/пикс., 200 МБ на 50 Мп) рядом с исходной; проход гейн-мапы — как у Ultra HDR
  (линейный снимок 16 Б/пикс.).

### Галерея

Декодирование в галерее уже цветоуправляемое (SSIV с ARGB_8888 → BitmapRegionDecoder/BitmapFactory возвращают битмапы в
пространстве файла, Glide переводит в sRGB с учётом профиля) — P3 показывается правильно. Добавлено: при открытии страницы
читается только заголовок (`BitmapFactory` bounds → `outColorSpace`, в фоне); для широкого охвата (P3, BT.2020) окно
переходит в `COLOR_MODE_WIDE_COLOR_GAMUT` (если экран широкий), для HLG/PQ (Android 14 называет эти пространства) — в
`COLOR_MODE_HDR` (если экран HDR); sRGB-страницы — режимы как раньше.

## Байт-идентичность по умолчанию (sRGB, HDR выкл.)

1. **Шейдер**: при `P3_OUT 0` все блоки `#if P3_OUT == 1` выпадают препроцессором, ветки `#else` — старые строки. Поток
   токенов после препроцессора совпадает с a3e94db (SHA-256 `ef87351c…`, проверка `check_colour_output.py --shader`, и через
   `git show a3e94db`); на GPU (moderngl) рендер `P3_OUT 0` совпадает со старым шейдером **бит в бит** на 940 цветах. ArkCombine
   ставит define только при P3 (без define — тот же ключ программы, что раньше).
2. **Java**: четырёхаргументный `PhotoOutput.save` (его вызывает HdrxProcessor, когда P3 и HDR выключены) идёт по старым
   вызовам: `saveJpeg` без ICC → тот же `UltraHdrEncoder.encodeToFile` / `ImageSaver.Util.saveBitmapAsJPG` с потоком файла
   без обёртки; WebP без изменений; 10-битный HEIC — `Heic10Encoder.write` с шестью аргументами (SRGB); AVIF — `Options` с
   `Signal.SRGB` → в JNI коды 1/13/1 и `icc = null`. Тест `PhotoOutputColourTest.defaultJpeg…/defaultWebp…` сравнивает
   файлы с прямыми старыми вызовами (байт в байт).
3. **Golden-тест** `DefaultOutputGoldenTest`: SHA-256 контейнера 10-битного HEIF, конвертации P010 (BT.709) и
   MediaFormat кодировщика (CQ и VBR) сняты на коде a3e94db **до** изменений и совпадают после.
4. **AVIF нативно**: `check_avif.py --baseline-exe` — хост-сборка a3e94db и новая дают **11 из 11** файлов байт в байт
   (8/10/12 бит, 4:4:4/4:2:0, lossless, из RGBA_8888 и RGBA_1010102).
5. **Конвейер**: `gainMapPass()` = `ultraHdr` при выключенном HDR (тест `ColourSettingsTest`), `p3Output` = false → те же
   ветки PostPipeline / PostAb / HdrxProcessor. `HdrOutput.wanted()` сразу false без опции (список кодеков не читается).

## Тесты и проверки (локально, Windows)

- Полный список CI `testDebugUnitTest`: **82 класса, 554 теста, 0 падений** (XML-отчёты проверены). Новые классы (8, 60
  тестов): `processing.color.OutputColourTest` (матрицы против опубликованных, P3-яркость, константы GLSL = Java),
  `IccProfilesTest`, `IccEmbedTest`, `HlgRenditionTest` (OETF, 75 %, обратимость, колено, рендерер = битмап попиксельно),
  `DefaultOutputGoldenTest`, `processing.heif.HeifColourTest` (nclx/prof, патч HEIF, BT.2020 P010, VUI),
  `processing.PhotoOutputColourTest` (маршруты всех форматов, Skia читает P3, HDR и откаты), `settings.ColourSettingsTest`.
  Обновлены ожидания порядка строк в `PhotoFormatSettingsTest`, `AvifSettingsTest`, список общих ключей в
  `SettingsAuditFixTest`, заглушка писателя в `PhotoOutputHeic10Test` (параметр цвета; проверяет, что по умолчанию sRGB).
- `tools/check_colour_output.py` (новый, в CI): `--shader` (токены + glslc обоих вариантов), `--gl` (см. выше), `--files`:
  матрицы Java = эталон, ICC через littlecms, наш APP2/ICCP/патч HEIF на файлах Pillow / libwebp / libheif (профиль читается,
  пиксели не меняются, Exif на месте, чужой ICC заменяется), HLG Java = независимая реализация BT.2100 (1e-16), рендерер =
  эталонная цепочка (0 кодов на 300 случаях: 8/10 бит, sRGB/P3, разные карты).
- `tools/check_heic10.py --self-test` расширен: та же сетка, объявленная P3 (nclx 12/13/1, ICC отдаёт libheif, пиксели как
  у sRGB-файла) и HLG-сетка (тайлы с матрицей BT.2020, nclx 9/18/9, декод 10 бит в пределах кодека) — PASS.
- `tools/check_avif.py` расширен: P3 (CICP 12/13/1 + ICC, Pillow читает профиль, пиксели как у файла по умолчанию), HLG
  10/12 бит (PSNR 51 / 50,5 дБ к сигналу — значит, матрица BT.2020 та же при кодировании и декодировании), HLG lossless
  (точно), опция `--baseline-exe` — PASS.
- `check_settings_model.py` PASS (новые правила в `SettingsModelCheck`), `check_ui_language.py` PASS.
- `:app:assembleDebug` (Windows, CMake 4.4.3, NDK 27.2) — собран: `SCAMERA-0.98-27075-debug.apk`, `libscameraAvif.so`
  пересобран с новым JNI (не ставился; для выдачи — только APK из CI). `app/version.properties` восстановлен,
  `local.properties` не создавался. `check_ultrahdr.py --self-test`, `check_ark_highlights.py`, `check_signed_rgb.py`,
  `check_dark_fade.py`, `check_highlight_neutral.py` — PASS.

## Поддержка просмотрщиков (ожидания, проверить)

- JPEG / WebP с ICC: Android 8+ (Skia), Google Фото, Chrome, Safari/Photos, Windows Photos — цветоуправляемые. Без
  управления цветом (редкие приложения) P3-файл выглядит чуть бледнее.
- HEIC: декодер Android берёт ICC из colr `prof` (nclx — нет; поэтому ICC пишется и в 10-битный файл); libheif-приложения и
  macOS читают nclx/ICC. Если на телефоне 10-битный P3 HEIC выглядит бледнее JPEG — значит, декодер взял VUI (BT.709).
- AVIF: Chrome — по CICP; Skia/Android — по ICC (он есть).
- HLG HEIC/AVIF: Apple Photos/macOS (HEIC HLG), Chrome на HDR-экране (AVIF HLG) показывают HDR; Android — зависит от версии
  (Android 14+ знает BT2020_HLG; что делает системный декодер HEIF/AVIF и Google Фото — проверить). Без HDR-поддержки HLG
  выглядит как SDR с сжатыми светами, немного темнее/бледнее.
- Ultra HDR + P3: libultrahdr (Android 14+) поддерживает базу в P3 через ICC.

## Проверка на телефоне (координатор)

1. Настройки → Конфиг: «Цветовое пространство» видно при любом формате; «HDR в HEIC / AVIF» — только HEIC/AVIF, неактивна
   с причиной при HEIC без «HEIC 10 бит» и при AVIF 8 бит; на Android 13+ активна.
2. **По умолчанию** (sRGB, HDR выкл.): снимок в JPEG, HEIC 8 и 10 бит, WebP, AVIF. В PhotonLog нет `output=DisplayP3`
   (строка `ARK combine`), нет `Display P3` / `HDR` в строках `PhotoOutput: … saved`. Файлы: JPEG без APP2 ICC_PROFILE
   (`python -c "from PIL import Image; print(Image.open('X.jpg').info.get('icc_profile'))"` → None), HEIC 10 бит —
   `python tools/check_heic10.py X.heic` → `nclx 1/13/1`, AVIF — nclx 1/13/1 без ICC.
3. **Display P3**: сцена с насыщенными цветами (красные/оранжевые цветы, неон, закат, зелень) — снимок в каждом формате и
   для сравнения тот же кадр в sRGB. Журнал: `ARK combine … output=DisplayP3`, `PhotoOutput: JPEG Display P3 saved`,
   `HEIC 8-bit Display P3 ICC saved` (или `HEIC 10-bit Display P3`), `WEBP Display P3`, `AVIF … Display P3`. Файлы: Pillow
   `info['icc_profile']` (JPEG/WebP/AVIF) и pillow-heif `info['icc_profile']` (HEIC) — профиль «Display P3»;
   `check_heic10.py` для 10 бит → `nclx 12/13/1`. Если строка `HEIC: Display P3 profile not added` — прислать ошибку
   (раскладка файла HeifWriter). Смотреть в Google Фото и в галерее SCAMERA рядом с sRGB-кадром: обычные цвета одинаковы,
   насыщенные глубже; в галерее окно переходит в wide colour (`adb shell dumpsys window windows | grep -i colormode`).
   Ultra HDR + P3: JPEG показывается как HDR в Google Фото / галерее.
4. **HDR**: HEIC с «HEIC 10 бит» и AVIF 10/12 бит, сцена со светами (небо, лампы, блики). Журнал: `Ultra HDR gain map …`,
   `HEIC HDR: HLG BT.2020 picture WxH from RGBA_1010102 (…) in N ms`, `HEIC 10-bit nclx 9/18/9 full written`, `HEIC 10-bit HDR
   HLG saved`; для AVIF `AVIF HDR: …` и `AVIF … nclx 9/18/9 …`, `AVIF HDR HLG saved`. Файлы: `check_heic10.py` → `nclx
   9/18/9`; pillow-heif `info['nclx_profile']`. Посмотреть на телефоне (Google Фото, системная галерея, галерея SCAMERA),
   на Mac/iPhone (HEIC) и в Chrome на HDR-экране (AVIF): светлые места ярче белого. Время прохода гейн-мапы и HLG-картинки,
   память на 50 Мп (нет ли OOM; при ошибке — SDR-файл и строка `HDR picture failed`).
5. HDR + «Также сохранять JPEG»: JPEG обычный (или Ultra HDR, если включён Ultra HDR).

## Ограничения

- VUI 10-битного P3 HEIC — BT.709 (в MediaFormat нет P3); P3 сообщают nclx и ICC контейнера.
- 8-битный P3 HEIC: только ICC (матрица YCbCr кодировщика не меняется); если файл HeifWriter устроен неожиданно (moov за
  растущим meta), профиль не добавляется — в журнале ошибка, файл читается как sRGB.
- P3-режим делает насыщеннее и те цвета, которые sRGB-рендер отсекал внутри конвейера (см. выше); узлы после ArkCombine
  (резкость, LMC-кривые, локальный контраст, ресайз) работают с P3-значениями как раньше с sRGB (веса яркости sRGB — разница
  пренебрежима). Проход гейн-мапы Ultra HDR считает яркость базы весами sRGB и при P3-базе (малое отличие).
- HDR — только HLG (PQ не делал); запас 2,3 ступени (HLG 1000 кд/м²), всё выше — мягким коленом; HDR-картинка — база × карта
  (как Ultra HDR), а не отдельный рендер сцены.
- Галерея: HLG-файлы показываются тем, что отдаст декодер платформы через SSIV (ARGB_8888) — на устройстве проверить.
- Ничего не запушено; на телефоне не проверялось.
