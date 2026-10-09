# SCAMERA — передача работы (состояние на 2026-10-09)

Документ для продолжения работы в другой нейросети. Читать целиком перед первым изменением. Полные формулировки задач —
`research/handoff/PLAN.md` (по номеру P..).

## 1. Что это за проект

- **SCAMERA** — Android-камера, форк PhotonCamera. Своя многокадровая склейка RAW («Hybrid»):
  - ядро Sabre 6.1 (GCam) + отбраковка/веса LMC 9.6 + Bento (света из ультракоротких кадров) + Shasta;
  - нативный путь склейки мозаик Quad/Tetra (ISZ), Sabre ×2 (выход 2× сенсора), Mochi (фотометрия брекетинга, GCam 11);
  - тон ARK (порт ArkCam), шумодав, восстановление светов, Ultra HDR, форматы JPEG/HEIC (8/10 бит)/WebP/AVIF/DNG;
  - SCAM HDR — нейросетевой маршрут (модели vivo NICE на NPU); работает только на vivo X200 Ultra и телефонах на 8 Elite.
- **Репозиторий:** `C:/Users/MECHREVO/Downloads/x200u/SCAMERA-PC/project`, GitHub `Whymexd9/SCAMERA`.
  - Рабочая ветка `migration/nice-camera2` (имя ветки старое, не менять), пушится **только** так: `git push origin HEAD:main`.
  - При обрыве пуша (HTTP 408): `git -c http.postBuffer=524288000 push origin HEAD:main`.
  - CI: `.github/workflows/build-test-apk.yml` (юнит-тесты по списку `--tests`, C++ проверки g++, python-проверки, сборка
    APK с приватным бандлом моделей). Артефакт `SCAMERA-Build-<код>` + `SHA256SUMS.txt`.
- **Владелец пишет по-русски.** Отвечать по-русски, кратко.
  - Код, комментарии и коммиты на английском.
  - Тексты UI: `values` = английский, `values-ru` = русский. Java-тексты UI через `util/Lang.t(ru, en)`. Проверка:
    `tools/check_ui_language.py`.

## 2. Правила владельца (обязательны)

**Git и сборки**
- Пушить только по команде владельца, одним пушем `HEAD:main`, когда все текущие задачи сделаны.
- Никогда не коммитить `*.key` (особенно `SCAMERA-PC/local-secrets/neural-assets-v2.key`), `local.properties`,
  `app/version.properties`. После локальной сборки: `git checkout -- app/version.properties` (должно быть
  `VERSION_BUILD=27074`).
- APK для владельца по умолчанию — из CI (`project/AGENTS.md`: артефакт CI, сверка с `SHA256SUMS.txt`, ничего не
  пересобирать и не переподписывать). Локальный APK — только если владелец сам просит. Локальная подпись другая: поверх
  CI-версии без удаления не встанет.

**Склейка и тесты**
- Не править GLSL `kHybMergeMain1` в `app/src/main/cpp/scam-hybrid.h`.
- md5 выхода воркера на контрольных сериях не должен меняться, если изменение не задумано (таблица в §3).
- Тесты никогда не пропускать, не отключать и не ослаблять.
- Части ArkCam **комбинировать** с нашим пайплайном, а не заменять им.
- Приложение работает **без root**.

**Камера и сенсор**
- Режимы сенсора не переключать. Исключение: tele mode 9 на Xiaomi 17 Ultra («Плавный оптический зум»).
- Не выдавать себя за стоковую камеру (подмена пакета / clientName на 17U запрещены).

**Названия (P55)**
- В APK нет «LMC», «NICE», «Vivo» как наших названий — всё «SCAM». **Названия смартфонов и бренд vivo в значении
  производителя не трогать** (владелец: «Названия смартфонов в нашей камере заменять нельзя»).
- Вендорные данные остаются как есть: ключи HAL `vivo.*`, `com.vivo.*`, библиотеки `libvivo*`, пути `/vendor`,
  символы для dlsym (`vivoNiceTce*`, `vivoShareBuf*`, `vivoNiceCREGetVersion`), имена графов QNN, файлы моделей
  `nice-*-v79.bin`, группа `nice/` внутри приватного бандла (`tools/ci_neural_assets.py`), режимы тюнинга `nicehdr*`.

**Телефоны владельца**
- Нажимать только когда в фокусе SCAMERA. Если владелец пользуется телефоном — только `adb shell` без UI.
- Проверять, свободен ли OPPO: `dumpsys window | grep mCurrentFocus`, `dumpsys power | grep lastUserActivityTime`,
  `dumpsys media.camera | grep "Client Package"`.
- Не удалять пользовательские данные, не выдавать разрешения, не принимать соглашения, не менять системные настройки.
  Диалоги закрывать кнопкой «Назад».

## 3. Окружение и инструменты (Windows, Git Bash)

**Сборка и тесты**
- JDK 17: `SCAMERA-PC/local-tools/jdk17/jdk-17.0.20.1+1`. SDK/NDK 27.2: `SCAMERA-PC/local-tools/android-sdk`.
- Юнит-тесты:
  ```
  ./gradlew.bat :app:testDebugUnitTest --tests <...> -x :app:buildScamNeuralWorker -I ../local-tools/compile-installed-sdk.gradle --console=plain --offline
  ```
  Проверять, что XML-отчёты в `app/build/test-results/testDebugUnitTest` действительно обновились (лучше удалить папку
  перед прогоном). Весь список CI: вытащить все `--tests "..."` из workflow и передать в gradle. Сейчас **584 теста,
  все проходят**. Известный падающий тест вне списка CI: `CaptureControllerTest.testGetCameraOutputSize_withTwoParameter`.
- Воркер (нативный исполняемый файл, gradle-задача на Windows не работает — собирать вручную):
  ```
  cd app/src/main/cpp
  <NDK>/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang++.cmd -std=c++17 -O2 -Wall -Wextra -pthread -fPIE -pie -static-libstdc++ -Wl,-z,max-page-size=16384 scam-neural-worker.cpp -ldl -lEGL -lGLESv3 -o <out>
  ```
  Для APK положить результат в `app/build/generated/scamNeuralAssets/scam-neural/arm64-v8a/scam-neural-worker`.
- CMake-библиотеки: `./gradlew.bat :app:externalNativeBuildDebug -x :app:buildScamNeuralWorker ...` (выход в
  `app/build/intermediates/cxx/Release/*/obj/arm64-v8a/libscam*.so`).
- Локальный APK: `bash local-tools/build-local.sh` (из `SCAMERA-PC`; уже на новых именах). Берёт приватный бандл из
  `C:/Users/MECHREVO/AppData/Local/Temp/claude/C--Users-MECHREVO-Downloads-x200u/fb20fea7-c9cd-45a2-b1ae-e879a37ea0a0/scratchpad/neural-assets`
  (`bundle`, `hexquad`, `nice`). Выход: `SCAMERA-PC/deliverables/local-20260923/SCAMERA-0.98-<код>-NICE-local.apk`.
  Последний: `...-27074-NICE-local.apk` от 2026-10-09 10:04 (код = `e465ab4`, воркер md5 15f606e6).

**Приватный бандл нейросетей (модели NICE / HexQuad / Quad / tele, QNN-рантайм, CRE vivo) и ключи к нему**

Вендорные модели не лежат в git открыто. Содержимое значения ключа никогда не печатать, не коммитить и не писать в документы.
- **Ключ расшифровки (AES-256-GCM, 64 hex-символа):** локально — файл `SCAMERA-PC/local-secrets/neural-assets-v2.key`
  (вне репозитория). То же значение лежит в секрете GitHub Actions `SCAMERA_NEURAL_ASSETS_KEY_V2`
  (Settings → Secrets and variables → Actions репозитория `Whymexd9/SCAMERA`). Второй, запасной секрет —
  `SCAMERA_NEURAL_ASSETS_URL` (приватная HTTPS-ссылка на архив).
- **Зашифрованный архив:** в git, ветка `origin/neural-assets-v2`, коммит `e9fedd2` (53 части). Описание частей и
  контрольные суммы — `tools/neural-assets-encrypted.json`; SHA256 расшифрованного архива `b7698172…` записан в
  `tools/ci_neural_assets.py`.
- **Как CI его получает** (`tools/ci_neural_assets.py`, шаг «Restore and verify private neural assets»): с ключом —
  читает части из ветки, расшифровывает, сверяет SHA256; без ключа — берёт последний артефакт Actions
  `SCAMERA-neural-assets-v2` (хранится 90 дней, обновляется каждой сборкой) или скачивает по `SCAMERA_NEURAL_ASSETS_URL`.
  Затем раскладывает по группам `bundle/`, `hexquad/`, `nice/` (эти имена внутри архива менять нельзя) и сверяет каждый
  файл с SHA256 из `ScamNeuralWorker.java` (`FILES`, `HEX_FILES`, `QUAD_FILES`, `SCAM_FILES`, `SCAM_TONE_FILES`).
- **Упаковка в APK:** `tools/package_scam_neural.py` (`--bundle-dir`, `--hexquad-dir`, `--scam-dir` = папка `nice`):
  кладёт файлы в `assets/scam-neural/`, `assets/scam-hexquad/`, `assets/scam/` (каждая `arm64-v8a/`) и `lib/arm64-v8a/`, переподписывает, проверяет.
- **Локально расшифрованная копия** (для `build-local.sh`): папка `neural-assets` в scratchpad `fb20fea7-…` (путь выше).
  Если её нет: `python tools/ci_neural_assets.py --archive <zip>` проверяет архив без сети; или расшифровать части ветки
  `neural-assets-v2` тем же кодом (`encrypted_seed`) с ключом из `local-secrets/neural-assets-v2.key` в переменной
  `SCAMERA_NEURAL_ASSETS_KEY_V2`.
- Без бандла APK собирается, но SCAM HDR / нейроремозаик не работают; CI в этом случае APK не публикует.

**Python и шейдеры**
- Системный Python 3.12 (`C:/Users/MECHREVO/AppData/Local/Programs/Python/Python312/python.exe`): numpy, cv2, moderngl.
  Запускать с `PYTHONUTF8=1` и JDK в PATH. На нём GL-проверки и `tools/check_hybrid_mochi.py`.
- venv с pillow-heif (для HEIC/AVIF): `.../821626a3-f17b-4a79-9a40-c85f28791791/scratchpad/heicvenv`.
- GLSL перед сборкой компилировать офлайн: `ndk/27.2.12479018/shader-tools/windows-x86_64/glslc.exe --target-env=opengl -std=310es ...`.
  Не называть uniform именами встроенных функций (`step` и т.п.).
- Проверки C++ (`tools/check_*.cpp`) локально g++ нет: собирать NDK-clang под arm64 и запускать на OPPO через `adb shell`
  (`TMPDIR=/data/local/tmp`), иначе их прогонит CI.

**Replay склейки на OPPO** (без UI, только `adb shell`)
- Скрипт: `C:/Users/MECHREVO/AppData/Local/Temp/claude/C--Users-MECHREVO-Downloads-x200u/5445ed52-44f3-436e-802a-cafb55817883/scratchpad/rp/rp0.sh <label> <burst> <worker> [строки тюнинга]`
  — печатает `rc` и md5 выхода. Аргумент воркера теперь `--scam-capture`. Пути передавать в виде `C:/...`, не `/c/...`
  (иначе adb не найдёт файл и молча запустит старый воркер).
- Серии на телефоне: `/data/local/tmp/syn_b1|b2|b4.nch` (синтетика), `hand.nch` (штатив), `isz2.nch`, `x7u_1x.nch`,
  `uw.nch`, `hh_*.nch`, `q1x.nch`, `syn_b4m/b2m/b4p2/b4p6.nch` (движущиеся объекты).
- Эталоны md5 (проверены после P55 на воркере 15f606e6):

  | серия | md5 |
  |---|---|
  | syn_b1 | 103304c8 |
  | syn_b2 | a178996c |
  | syn_b4 | e7936ad7 |
  | hand | 0a46d3be |
  | isz2 | 06947ccd |
  | x7u_1x | ae73516c |

**Телефоны**
- OPPO Find X7 Ultra PHY110 (adb `fb27034c`, Android 16) — подключён, на нём replay и тесты.
  - Координаты (портрет): спуск (716,2853), значок формата (303,163), шестерёнка (1300,163). Лучше `uiautomator dump`.
- vivo X200 Ultra (основной телефон владельца), vivo X300 Ultra, Xiaomi 17 Ultra, Pixel 7, OnePlus 15, X200 Pro —
  у владельца, не подключены. Данные с них — только логи/дампы от владельца.
- Логи у владельца: `DCIM/PhotonCamera/PhotonLog/log-*.txt`. Диагностические архивы: `Download/SCAMERA/SCAM-*.zip`.
- Dev-переключатели: `/sdcard/Android/data/org.codeaurora.snapcam/files/scam_dev.txt`, строки `key value`
  (старый `nice_dev.txt` приложение само переименует при первом запуске после P55).

## 4. Карта кода (имена после P55)

**Захват и превью** (`app/src/main/java/com/particlesdevs/photoncamera/`)
- `capture/CaptureController.java`: сессии, ZSL, брекетинг гибрида, фолбэки конфигурации, стабилизация.
- `capture/ScamPreview.java`: стоковый профиль превью vivo (теги записаны с X200 Ultra), EIS; `vendorKeys()` — только PD2454.
- `capture/PreviewContinuity.java`, `PreviewGapMeter.java`, `StabilizationTrace.java`, `PreviewStall.java`.
- `capture/XiaomiTeleZoom.java`: зум 17 Ultra (75–400 мм, оптика HAL, режим кропа, ISZ режим 9).
- `control/ZoomController.java`, `control/FovSelfCheck.java` (P60), `settings/ModuleRegistry.java`.
- `ui/camera/views/viewfinder/MainRenderer.java` + `VfDrawMeter` (P57, строки `VF_DRAW`).

**Склейка (Java)** (`processing/opengl/postpipeline/`)
- `ScamHybridBurst.java` (серия, размер выхода, Sabre 2×), `ScamBurst.java`, `ScamNeuralClient.java` (запуск воркера),
  `ScamNeuralWorker.java` (списки файлов бандла с SHA256), `ScamRgb.java` (импорт RGB, света), `ScamDenoise.java`,
  `ScamHdrDenoise.java`, `ScamSharpen.java`, `ScamLocalContrast.java`, `PostAb.java`.

**Склейка (нативно)** (`app/src/main/cpp/`)
- `scam-hybrid.h`: HybridTuning, шейдеры склейки, нативные мозаики `kHybMergeMosaic`/`Fast`, Mochi (`kHybMochi*`).
- `scam-neural-worker.cpp` (точка входа воркера), `scam-superres-gpu.h` (`scamProcessingContext`, низкий приоритет EGL).
- `scam-aec-*.h` / `scam-aec-jni.cpp` → библиотека `libscamAe.so`; `scam-*.h` — порт SCAM HDR (бывший vivo-nice-*).

**Вывод**: `processing/processor/HdrxProcessor.java`, `processing/PhotoOutput.java`, `processing/heif/*`, `processing/avif/*`,
`processing/color/*`.

**Шейдеры** (`app/src/main/assets/shaders`): `ark/*` (тон), `scamhdr/scamrgb.glsl`, `scamhdr/clipband.glsl`, `hlrecovery/*`,
`scam/`, `scamdn/`, `scamlc/`, `scamsharp/` (бывшие lmc, lmcdn, nicelc, nicesharp).

**Настройки**: `settings/PreferenceKeys.java`, `res/xml/preferences*.xml`, `SettingsAvailability`, `DeviceDefaults`,
`SettingsMigration`, `ScamHybridKeys.java`, `BrandMigration.java` (P55, перенос старых имён).
- Префиксы ключей: `pref_scam_hybrid_` (бывш. `pref_lmc_hybrid_`), `pref_scamhdr_` (бывш. `pref_vivo_nice_`),
  `pref_scamold_` (бывш. `pref_nice_`), `pref_scamroute_` (бывш. `pref_vivo_hdr_`). Ни один новый префикс не начинается
  с другого — проверки `startsWith` сохранили смысл.

**Галерея**: `gallery/**` — `ui/GalleryUi`, `GalleryFormat`, `LibraryAdapter`, `SelectionBar`, `GallerySheets`,
`ViewerChrome`, фрагменты Library/Viewer/Compare, `dng/DngPreview` + `Lj92Decoder` (своя отрисовка DNG),
`glide/ScameraGlideModule`, `GalleryLauncherIcon`.

**Документация по темам** (`research/`): `mochi/MOCHI_IMPL.md`, `xiaomi17u/*`, `bento/BENTO_IN_SABRE.md`,
`moving-objects/`, `viewfinder-freeze/`, `speed/`, `colour-hdr/`, `heic10/`, `avif/`, `settings-audit/`.
Большие разборы GCam/LMC вне репозитория: `SCAMERA-PC/research/gcam11`, `research/scam`, `research/p29`.
`docs/` — старые исследовательские заметки (старые имена там остались, в APK не попадают).

## 5. Что сделано и залито

`origin/main` = `e465ab4`, CI #346 success, сборка **SCAMERA-Build-30713**
(https://github.com/Whymexd9/SCAMERA/actions/runs/37881494903). APK из CI не скачивался.

С прошлой передачи (dd284cb):
- **P58** — OnePlus 15: нет розового неба, когда у канала нет плато клипа Bento.
- **P61** — в лог пишутся фактические параметры кодировщика и размер каждого файла; отчёт проверки сжатия.
- **P62 Mochi** — фотометрическая поправка брекетинга GCam 11 на GPU перед склейкой. Ключ тюнинга `mochi`
  (0 = выкл., по умолчанию; 1 = правило GCam, >3 небрекетированных; 2 = всегда). На нативном мозаичном пути не применяется.
- **P41 17U** — проверка «стекло пошло за командой» считает только движение к команде; режим кропа теперь включается
  (раньше поле зрения на 100–400 мм было в 1.33 раза шире). Разбор стоковых дампов: `research/xiaomi17u/STOCK_ZOOM_2026-10-08.md`.
- **P57** — видоискатель после снимка: низкий приоритет EGL у контекстов обработки, приоритет DISPLAY у потока
  видоискателя, счётчик `VF_DRAW`. dev: `gpu_low_priority`, `vf_priority`.
- **P60** — модуль с вендорными тегами сам меряет своё поле зрения против обычного модуля той же камеры (NCC), чтобы не
  обрезать ISZ дважды. dev: `fov_check`.
- **P56** — Sabre ×2 для ISZ 2× (Quad): на OPPO 8192×6144. Для Tetra (4×) ещё нет.
- **P59 / P53** — галерея показывает любые DNG (встроенное превью или своя отрисовка). Владелец: DNG на Pixel теперь
  обрабатывается так же быстро, как JPEG.
- **P59b** — новая галерея в карточном стиле (сетка по дням, просмотр, лист сведений, удаление, папки, сравнение,
  выделение протяжкой).
- **P54** — SCAM HDR работает только на X200 Ultra (и 8 Elite), поэтому вендорные ключи превью vivo (≈80 тегов,
  сценарный режим, EIS) отправляются только на PD2454. На X300U и других vivo — обычное превью Camera2. Подозрение
  владельца: эти ключи ломали стабилизацию на X300U. dev: `scam_stock_profile 1` = старое поведение.
- **P55** — переименование LMC / Vivo / NICE → SCAM во всём, что попадает в APK (1 315 идентификаторов, ~150 путей),
  с переносом сохранённых настроек (`BrandMigration`: ключи и значения всех файлов настроек, резервные копии, профили
  модулей, `nice_dev.txt` → `scam_dev.txt`, удаление старой папки извлечённого бандла). Тест `BrandMigrationTest`
  сверяет Java-правило со всеми словами инструмента (`app/src/test/resources/brand/vectors.tsv`). Инструмент:
  `.../5445ed52-44f3-436e-802a-cafb55817883/scratchpad/p55/rebrand.py` (+ `prefix_check.py`).

Локально, **не залито**: `ca1c4c4`, `6871db9` — скрипты съёма стоковой камеры 17U (`research/xiaomi17u/x17u_stock.sh`)
и эта передача.

## 6. Что осталось

### Xiaomi 17 Ultra — оптический зум (главное открытое)
- Наши теги (`com.xiaomi.camera.userZoomRatio.userZoomRatio` + `android.control.zoomRatio`, 4.30000019 / 1.34375 на
  100 мм; `org.codeaurora.qcamera3.sensor_meta_data.current_mode` = 9 для ISZ 2×) SCAMERA **уже отправляет** на камеру 4.
  HAL при `xiaomi.thirdparty.isThirdParty` = 1 держит свою цель `opticalZoomTargetRatio` 3.400 и уводит стекло на 74.4 мм
  (лог прогона x17u, 21:31:49).
- Сток открывает логическую SAT-камеру 5 (физические 3, 2, 4). Сторонним приложениям доступна такая же логическая
  камера **0** (те же физические, zoomRatio 0.6–10, `enableOptZoomratio` 1), но SCAMERA её не открывает:
  `CameraManager2.scanAllCameras` пропускает логические камеры (`!isLogical`).
- Оптика стока: `target = 3.4 + (UI − 3.2)·0.9/1.1` (UI 3.2–4.3 → HAL 3.4–4.3, 75–100 мм), выше 4.3× — кроп, на 10× режим 9.
- **Ждём** от владельца архив `/sdcard/x17u_stock` (скрипт `research/xiaomi17u/x17u_stock.sh`: 16 шагов зума 0.6–30×,
  история ключей по кадрам `dumpsys -m`, дампы после снимков на 3.5/7/10×, logcat, getprop, JPEG).
- **Дальше:** по дампам повторить у нас последовательность стока — логическая камера 0 для 75–100 мм (и выше),
  те же ключи сессии/запроса, точка включения режима 9. Если на камере 0 HAL стекло стороннему приложению не даёт —
  без подмены пакета (запрещено) оптика недоступна, остаётся режим кропа.

### Ждёт проверки владельцем (сборка 30713)
- Настройки после обновления сохранились (перенос P55 проверен только тестами).
- X300 Ultra: стабилизация после снимка (P54) — НЕ решено отключением вендорных ключей (лог 2026-10-09, V2562, камера 5: vendorKeys пустые, но стабилизация всё ещё отваливается; добавлен план диагностики и исправления в PLAN.md §P54).
- P57: строки `VF_DRAW` в логе после снимка; новая галерея (жесты, удаление, сравнение); P60 на X9U; P56 Sabre ×2 на ISZ 2×.
- P45: чёрный видоискатель после смены формата (vivo) — нужен лог с `PREVIEW_STALL` / `format choice`.
- P49/P38: проверки на конкретных телефонах.

### Можно делать сразу
- **P56 для Tetra (4×)**: Sabre ×2 на нативном мозаичном пути с блоком 4 (сейчас только Quad, блок 2).
- **Mochi на нативном мозаичном пути** (`in.nativeFrames`) и решение о включении по умолчанию (A/B через `hybrid_tuning.txt`:
  `mochi 2`) — по снимкам владельца.
- Остальное — `PLAN.md`.

## 7. Как проверять перед пушем

1. Полный список юнит-тестов CI проходит (584), XML-отчёты свежие.
2. `tools/check_ui_language.py`, `tools/check_settings_model.py` и python-проверки CI — PASS (с JDK в PATH, `PYTHONUTF8=1`).
3. md5 replay на OPPO не изменились (таблица в §3), если изменение не задумано.
4. Изменения поста — через `scam_dev post_ab 1` (+ `post_ab_upload 1`).
5. Пуш: `git push origin HEAD:main`, затем CI: `https://api.github.com/repos/Whymexd9/SCAMERA/actions/runs?per_page=2`.

## 8. Состояние на момент передачи

- Рабочее дерево чистое, кроме неотслеживаемой `.claude/` (старые worktree агентов — без уникальной работы, можно удалить).
- Память прошлых сессий Claude: `C:/Users/MECHREVO/.claude/projects/C--Users-MECHREVO-Downloads-x200u/memory/*.md`
  (`MEMORY.md` — индекс, `pending-user-tasks.md` — журнал решений владельца, `p55-rebrand-done.md` — новые имена).
  В старых заметках файлы и классы названы по-старому: vivo-nice-hybrid.h = scam-hybrid.h, LmcHybridBurst =
  ScamHybridBurst, VivoNice* = Scam*, nice_dev.txt = scam_dev.txt.
