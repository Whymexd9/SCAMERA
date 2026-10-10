---
name: scamera
description: Работа над SCAMERA — Android-камерой (форк PhotonCamera) с многокадровой склейкой RAW Hybrid, SCAM HDR на NPU, модулями сенсоров vivo/OPPO/Xiaomi. Применять при любой правке, сборке, проверке или разборе логов в этом репозитории (Java/C++/GLSL, Camera2, воркер склейки, шейдеры, CI, тесты на телефонах владельца), а также при вопросах про устройства, camera ID, сенсоры и формат RAW.
---

# SCAMERA

Репозиторий: `SCAMERA-PC/project` (git), GitHub `Whymexd9/SCAMERA`. Перед работой прочитать
`research/handoff/HANDOFF.md` (состояние, правила, команды) и нужный пункт `research/handoff/PLAN.md` (P1…P62).
Корень `SCAMERA-PC/`: `AGENTS.md`, `HANDOFF.md` (история порта NICE/AE, сентябрь), `COLLAB.md`, `evidence/`, `reports/`.
`local-secrets/` не читать и ничего оттуда не копировать.

Над проектом параллельно работают несколько нейросетей в одной папке. Перед началом: `git fetch`, `git log -10`,
`git status`; чужие правки не откатывать, не делать `reset/stash/checkout` по чужим файлам.

## Правила работы (обязательно)

- Отвечать по-русски, очень коротко; проверки — пунктами. Код, комментарии, коммиты — по-английски.
- Не переписывать массово. Сначала конкретная причина (строка лога, место в коде, md5), потом минимальная правка.
- «Решено» — только по логу с телефона или по коду/тесту. Арифметический тест не доказывает работу на телефоне.
  Выводы о качестве фото — только по снимкам с телефона.
- Не добавлять настройки, которые не влияют на расчёт. Не выдумывать API, теги HAL, типы ключей — проверять по
  `CameraCharacteristics`/дампам.
- Правки показывать диффом. Не трогать GLSL `kHybMergeMain1` в `app/src/main/cpp/scam-hybrid.h`.
- Режимы сенсора не переключать (`forceSensorMode` и т.п.). Исключение — Xiaomi 17 Ultra (HAL сам, см. ниже).
- Без root (root необязателен). Без подмены пакета/`clientName` стоковой камеры.
- Части ArkCam комбинировать с нашим пайплайном, а не подменять.
- Тесты не отключать и не ослаблять. md5 контрольных серий не должен меняться без умысла.
- Трафик ограничен: одна сборка APK на проверку, без больших загрузок. APK из CI не скачивать без просьбы.
- Пуш только по команде владельца: `git push origin HEAD:main` (ветка `migration/nice-camera2`). При HTTP 408:
  `git -c http.postBuffer=524288000 push origin HEAD:main`.
- Никогда не коммитить `*.key`, `local.properties`, `app/version.properties` (после локальной сборки:
  `git checkout -- app/version.properties`, должно быть `VERSION_BUILD=27074`).
- UI: `res/values` = английский, `values-ru` = русский; тексты UI в Java только через `util/Lang.t(ru, en)`.
- Названия: в APK наши вещи называются SCAM (не LMC/NICE/Vivo). Названия телефонов и бренд vivo в текстах не трогать.
  Вендорные данные остаются как есть: теги `vivo.*`, `com.vivo.*`, `libvivo*`, `/vendor`, dlsym-символы, графы QNN,
  файлы `nice-*-v79.bin`, режимы `nicehdr*`.
- Телефоны владельца: нажимать только когда в фокусе SCAMERA; если телефоном пользуются — только `adb shell`.
  Не удалять данные, не выдавать разрешения, не принимать соглашения, не менять системные настройки.

## Стек и размер (git ls-files, без third_party/deps)

| Язык | Строк | Что |
|---|---|---|
| Java | ~80.7 тыс. (393 файла main, 114 тестов) | всё приложение |
| C++ | ~22 тыс. (`.cpp` 3.7 тыс., `.h` 18.5 тыс.) | воркер склейки, SCAM HDR/NPU, AE, кодеки |
| GLSL | ~6.3 тыс. (88 файлов в ассетах) + шейдеры строками в `.h` | обработка на GPU |

Kotlin нет. Сборка: JDK 17, Gradle 8.11.1, SDK 36, Build Tools 36.0.0, NDK 27.2.12479018, CMake 3.22.1.

## Архитектура

Пакеты `app/src/main/java/com/particlesdevs/photoncamera/`:
`api` (Camera2, вендорные теги `VendorTagUtils`, `CameraManager2`) · `capture` (`CaptureController`, ZSL/брекет,
превью, стабилизация `ForcedStabilization`/`StabilizationTrace`, `ScamPreview` профиль vivo, `XiaomiTeleZoom`) ·
`control` (зум `ZoomController`, `FovSelfCheck`, фокус) · `processing` (сохранение, DNG, форматы, `opengl/postpipeline`,
`processor/HdrxProcessor`, `color`, `heif`, `avif`, `ultrahdr`) · `settings` (`PreferenceKeys`, `ModuleRegistry`,
`DeviceDefaults`, `SettingsMigration`, `BrandMigration`) · `gallery` (SGallery, DNG-превью, Glide) · `ui` · `util`
(`Log`, `Lang`, `WorkerSpawn`) · `manual` · `remosaic` · `pro`.

Путь кадра:
1. `CaptureController`: сессия Camera2, RAW ImageReader, ZSL-кольцо + брекет (N из кольца до нажатия, L/S/ES после).
2. `ImageSaver` → `DefaultSaver` → `processing/processor/HdrxProcessor`.
3. `ScamHybridBurst.process` (Hybrid) или `ScamBurst.process` (SCAM HDR) → `ScamNeuralClient` → воркер
   `scam-neural-worker` (отдельный процесс, `WorkerSpawn` fork/exec, данные через memfd `fd:3/4`, `--scam-capture`).
4. Воркер: выравнивание, склейка на GPU (Sabre + отбраковка + Bento + Shasta, Mochi, нативные мозаики Quad/Tetra),
   либо NPU-модели SCAM HDR (QNN HTP V79). Результат — float RGB обратно в приложение.
5. `PostPipeline` (тон ARK, шумодав, света, резкость) → `RunHDRGainMap` → `PhotoOutput.save` (JPEG/HEIC/WebP/AVIF/DNG).

Нативные модули (`app/src/main/cpp/CMakeLists.txt`): `dngCreator`, `allocator` (+`worker-spawn.cpp`),
`camera2native`, `scamAe` (`scam-aec-*`, AE vivo), `scamNeuralProbe`/`scamProbe` (диагностика), `scameraJpeg`
(jpegli+Highway), `scameraAvif`, `lanczosDownscale`. Воркер `scam-neural-worker.cpp` — отдельный PIE-исполняемый
файл (не JNI), собирается задачей `buildScamNeuralWorker` (на Windows не работает — собирать NDK clang вручную,
см. HANDOFF §3). В APK: `assets/scam-neural/arm64-v8a/scam-neural-worker` + `lib/arm64-v8a/libscamera_worker.so`.

Шейдеры:
- ассеты `app/src/main/assets/shaders/` (через `GLProg`, `shaders/<имя>.glsl`): `ark/` тон, `scamhdr/scamrgb.glsl`,
  `clipband.glsl` света, `hlrecovery/`, `scamdn/` шумодав, `scamlc/`, `scamsharp/`, `sharpening/`, `chromadn/`,
  `remosaic/`, `preview/`, `ultrahdr/`, `utils/`;
- строками в заголовках: `scam-hybrid.h` (`kHyb*`: склейка, `kHybMergeMosaic/Fast`, `kHybMochi*`, Bento, chroma),
  `scam-superres-gpu.h`, `scam-rawca-gpu.h`, `scam-hexquad-gpu-shaders.h`.

## Устройства (только из файлов проекта; «н/п» = не подтверждено)

| Телефон | Модель / SoC / GPU | Android, root | Камеры (ID) и сенсоры | RAW, CFA, режимы |
|---|---|---|---|---|
| vivo X200 Ultra (основной у владельца) | PD2454 / V2454A, SM8750 (8 Elite), Adreno 830, adb `10AFAQ1QE60071E` | 16, root KernelSU (в одной заметке Magisk — противоречие) | 3 = основная (IMX06C), 4 = сверхширик (IMX06C), 5 = теле (HP9, s5khp9) | кам.4: 4096×3072 (max 8192×6144), CFA 0 RGGB, ISO 72–3200; кам.5: 4080×3072 (max 16320×12288), CFA 3 BGGR, ISO 50–6400; чёрный 64, белый 1023. Теле `forceSensorMode` 5 = 2× ISZ Quad, 7 = 4× ISZ Tetra (модули, не переключать); RAW ISZ — мозаика, объявленная BGGR |
| vivo X300 Ultra | V2562; SoC н/п | н/п | теле = 5 (OIS [0,1], VS [0]) | 4080×3072 на кам.5; CFA н/п |
| vivo X200 Pro | PD2405, Dimensity 9400, Mali-G925 | н/п | фронт 1, кам.5 | RAW 4096×3072, картинка 4000×3000 (остальное нули); фронт YUV 3264×2448 |
| vivo X200 FE | V2503, Dimensity (MTK HAL) | н/п | 2 основная, 3 теле (OIS `[1]`), 4 сверхширик, 1 фронт | н/п |
| vivo X100 Ultra | V2366GA, SM8650, Adreno 750 | н/п | 0–6; 0 основная, 6 = MONO | 4096×3072; ZSL — упакованный 10-бит под видом RAW16 (rowStride 8192) |
| OPPO Find X7 Ultra (подключён к ПК) | PHY110, SM8650, Adreno 750, adb `fb27034c` | 16, без root | без подмены пакета `[1,2,3]`, 2 = основная, 4 = теле 2.8×; с подменой 5 камер; модули 0.6/1/2/2.8/5.9× | 1× Quad sensor mode 29 4096×3072; ISZ 2× Quad + Sabre ×2 → 8192×6144; CFA н/п |
| OPPO Find X8 Ultra | PKJ110 (`op5dd3l1`), SM8750 | н/п | 1–5, 2 = основная | 4096×3072 Bayer, чёрный 64 (плывёт до ~60), белый 1023, по умолчанию RAW10 |
| OPPO Find X9 Ultra | н/п | н/п | модуль 6× на 3×-камере с вендорным 2× ISZ | был двойной кроп 2048×1536 (P60) |
| Xiaomi 17 Ultra | 25128PNA1C (`nezha`), SM8850, Adreno | 16; root н/п | логические SAT 0/5/6 = физ. 3 (сверхширик), 2 (основная 23.256 мм), 4 (теле 75–100 мм, оптический зум); 7 = системная репроцессинга | лог.0 RAW 4096×3072 (основная); кам.4 4080×3072; режим 4 обычный, 2 = 8160×6144 QUADCFA (~7×), 9 = ISZ 4080×3072 (≥8.5×) — переключает HAL сам |
| Google Pixel 7 | Tensor G2, Mali-G710 | н/п | 2 основная, 3 сверхширик | н/п |
| OnePlus 15 | CPH2747 | н/п | 2 | н/п |
| Nubia NX563J | н/п | 10 | н/п | н/п |

Известные особенности:
- X200U: теле объявляет ложный ISO [400,800] (в пробе 50–6400 — противоречие); `LENS_SHADING_APPLIED=true` не
  верить; DNG-матрицы общие — цвет от ISP CCM. P45 чёрный видоискатель после смены формата (watchdog, причина открыта).
  SCAM HDR (NPU) работает только здесь и на 8 Elite. Стоковый профиль превью (~80 тегов vivo) — только PD2454.
- X300U: после снимка пропадает стабилизация превью (P38/P54: `ForcedStabilization`, без flush, ждёт проверки);
  после flush теряются первые запросы (`FlushLossStats`).
- X200 Pro: воркер падал SIGSEGV на компиляции шейдера (Mali, P26/P39); фронт не конфигурировался со стоковым
  профилем (P52).
- X200 FE: OIS объявлен `[1]` (MTK), раньше считался неподдержанным (P54d).
- X100U: MONO id 6 в RAW-сессии роняет HAL; упакованный ZSL давал чёрные/розовые фото (P23, `RawPayloadCheck`).
- OPPO X7U: CC14 тюнинга пустые → `OppoTunedColor`; без подмены пакета видно 3 камеры. Тестовая `hand.nch` — штатив.
- OPPO X8U: плавающий уровень чёрного → зелёные тени.
- Xiaomi 17U: для камеры 4 HAL держит стекло на 75 мм (`isThirdParty=1`). P41b: теле через логическую камеру 0,
  op mode `0x9002`, ExtendedMaxZoom/EnableInsensorZoom = 1, видоискатель — логический поток, RAW — физ. камера 4,
  кроп снимка от `opticalZoomCurrentRatio`; отказ → op mode 0 → камера 4 одна. Ждёт проверки. Разбор:
  `research/xiaomi17u/STOCK_ZOOM_2026-10-09.md`.
- Pixel 7: DNG основной были чёрными в галерее (закрыто своей отрисовкой DNG).
- OnePlus 15: розовое небо без плато клипа Bento (P58, исправлено).

## Модули и BIN-файлы vivo

- Модули (`settings/ModuleRegistry.java`): слоты `back0..7`/`front0..7` (камера, метка, зум). Вендорные ключи слота —
  `pref_sensorconfig_<slot>_tunablekeys`; `vivo.control.forceSensorMode` 5 = 2× ISZ Quad, 7 = 4× ISZ Tetra
  (режим задан модулем, сами не переключаем). Применение: `SensorConfigInjector`, `TunableKeyManager`,
  `VendorTagUtils.applyTunableKeys`.
- BIN — контекстные бинарники QNN HTP V79, закреплены SHA256 в `ScamNeuralWorker.java`:
  `FILES` (`tele576-v79.bin` + QNN .so), `SCAM_FILES` (`nice-main-forward-v79.bin` + CRE `libvivo_nice_cre.so` и
  зависимости), `SCAM_TONE_FILES` (`nice-tone-*-v79.bin`), `HEX_FILES` (`hexquad-x1/x2-v79.bin`, HP9 HexQuad),
  `QUAD_FILES` (`quad-x1` IMX06C, `quad-hp9-x1`, `quad-hp9-highdrc-x1`). `vsr*` не упаковываются.
- Хранение: зашифровано (AES-256-GCM) в ветке `origin/neural-assets-v2`; ключ — секрет Actions
  `SCAMERA_NEURAL_ASSETS_KEY_V2` и локальный файл в `local-secrets/` (не читать). Запасные пути: артефакт
  `SCAMERA-neural-assets-v2`, секрет `SCAMERA_NEURAL_ASSETS_URL`. Распаковка `tools/ci_neural_assets.py` (группы
  `bundle/`, `hexquad/`, `nice/` — не переименовывать), упаковка `tools/package_scam_neural.py`
  (`assets/scam-neural/`, `assets/scam-hexquad/`, `assets/scam/`, `lib/arm64-v8a/`, переподпись, проверка хэшей).
- Цель «200 Мп на телефото» в документах проекта **не подтверждена**: `docs/tetra-detail.md` прямо говорит, что
  реконструкция полного 200 Мп не делается. Есть: HP9 теле 4× ISZ Tetra 4080×3072 → ремозаик в Bayer того же размера,
  нативная склейка Sabre на Tetra (P40, 10× у X200U), Sabre ×2 для ISZ 2× Quad (P56, 8192×6144). Для Tetra 4× ×2 — не
  сделано. Стоковый ремозаик HP9 (`libremosaiclib_s5khp3.so`, `EngineerRemosaicMode`) — только диагностика.
- Статус: SCAM HDR без root работает (воркер в APK), TCE стока не подключён, ZSL-брекет 4N + L/S/ES проверен на
  сенсоре X200U.

## Сборка и проверка

- CI `.github/workflows/build-test-apk.yml`: push в `main`, `work`, `codex/zsl-bracket-completion`,
  `migration/nice-camera2`, PR, вручную. Ветки `work` в репозитории нет; рабочая — `migration/nice-camera2` → `main`.
  Шаги: python-проверки, C++ проверки (g++), юнит-тесты по списку `--tests`, assembleDebug, приватный бандл,
  упаковка, `SHA256SUMS.txt`. Артефакт `SCAMERA-Build-<код>`.
- versionCode в CI = `30196 + git rev-list --count c2d9e9bc..HEAD` (комментарий «30000 + commits» в build.gradle
  устарел). Локально — `VERSION_BUILD` из `app/version.properties` (+1 за сборку, вернуть). versionName `0.98`.
  Локальный клон shallow — код CI локально не пересчитывать.
- Статус CI: `https://api.github.com/repos/Whymexd9/SCAMERA/actions/runs?per_page=3`.
- Юнит-тесты: `./gradlew.bat :app:testDebugUnitTest --tests <...> -x :app:buildScamNeuralWorker -I ../local-tools/compile-installed-sdk.gradle --console=plain --offline`;
  весь список — все `--tests` из workflow; проверять, что XML в `app/build/test-results` свежие.
  Проверки: `tools/check_ui_language.py`, `tools/check_settings_model.py` (JDK в PATH, `PYTHONUTF8=1`).
- GLSL перед сборкой: `glslc --target-env=opengl -std=310es`; uniform не называть как встроенные функции (`step`).
- Replay склейки на OPPO (без UI): скрипт `rp0.sh` (путь в HANDOFF §3), серии `/data/local/tmp/*.nch`; эталоны md5:
  syn_b1 5b988db1, syn_b2 99f29aab, syn_b4 421efc48, hand 6cab2c98, isz2 99c972ae, x7u_1x 52bcce71, v10_1 58c09ee5, v10_2 2176b21b (P76). Пути в adb —
  `C:/...`, не `/c/...`.
- Локальный APK (только по просьбе): `bash local-tools/build-local.sh` из `SCAMERA-PC`; подпись локальная, поверх
  CI-версии не встаёт. Владельцу по умолчанию — APK из CI, сверенный с `SHA256SUMS.txt`.

adb / логи:
- Свободен ли телефон: `adb -s <serial> shell "dumpsys window | grep mCurrentFocus; dumpsys power | grep lastUserActivityTime; dumpsys media.camera | grep 'Client Package'"`.
- `adb logcat -s SCAM_CAPTURE SCAM_HDR SCAM_PIPELINE SCAM_AE SCAM_DIAG SCAM_TIMELINE VF_DRAW STAB_TRACE XiaomiTeleZoom CaptureController`.
- На телефоне: `DCIM/PhotonCamera/PhotonLog/log-YYYY-MM-DD.txt`, `Download/SCAMERA/SCAMERA-debug.log`,
  `SCAMERA-crash.log` (Download/SCAMERA и files приложения), диагностика `Download/SCAMERA/SCAM-*.zip`
  (замедляет съёмку). Отчёты воркера (`WORKER EXIT/CRASH`, `HYBRID …`) — в SharedPreferences `scam_*_report`.
- Dev-переключатели: `/sdcard/Android/data/org.codeaurora.snapcam/files/scam_dev.txt`, строки `key value`
  (`hybrid`, `hybrid_<key>`, `post_ab`, `stab_trace`, `force_stab`, `scam_preview_eis`, `gpu_low_priority`,
  `vf_priority`, `fov_check`, `xiaomi_logical`, `xiaomi_opmode`…). Пакет приложения: `org.codeaurora.snapcam`.
- Дампы камеры: `adb shell dumpsys media.camera` (ключи кадра, клиент, режим сессии), `-m <теги>` — история тегов.

## Типичные сбои и где искать

- Шейдер не собрался: лог `Error compiling shader` (`GLProg`), в воркере `HYBRID GPU shader:` / `GPU shader:`.
  Сначала `glslc` офлайн. Mali строже Adreno.
- Воркер упал: `WORKER EXIT: signal` / `WORKER CRASH` в отчёте; `ScamHybridBurst.learnFromCrash` отключает шаг
  (gpu → gpu-conservative → cpu-single). `HYBRID GPU readback failed slot=…` (`scam-hybrid.h`) — нехватка/сбой GPU.
- Склейка/HDR: «соты» на движении (P51, ядро нативной мозаики), розовое небо (Bento без плато, P58), пересветы
  (`scamrgb.glsl`, `clipband.glsl`, `hlrecovery`). Регрессию ловить md5 replay и `post_ab 1`.
- Цвет RAW: CFA/порядок Bayer модуля (ISZ объявляет BGGR), чёрный уровень и padding (X200 Pro, X8U), ISP CCM vivo,
  `OppoTunedColor`, RAW CA (P28). Проверки `tools/check_colour_output.py`, `check_signed_rgb.py`.
- Чёрные/розовые кадры: упакованный 10-бит под видом RAW16 (`RawPayloadCheck`).
- Видоискатель: чёрный после смены формата (`PREVIEW_STALL`), тормозит после снимка (`VF_DRAW`), стабилизация
  (`STAB_TRACE`).
- Камера/HAL: вспомогательные MONO/NIR не открывать в RAW-сессии; фолбэки конфигурации сессии в `CaptureController`.

## Формат ответа

Очень коротко, по-русски. Что сделано / найдено — 1–3 строки. Проверки пунктами: что запускал и результат
(тесты N/N, md5, лог). Чего не проверено — прямо. Без заявлений «работает» без лога или теста.
