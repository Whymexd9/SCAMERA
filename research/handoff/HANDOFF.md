# SCAMERA — передача работы (состояние на 2026-10-08)

Документ для продолжения работы в другой нейросети. Читать целиком перед первым изменением.

## 1. Что это за проект

- **SCAMERA** — Android-камера, форк PhotonCamera. Своя многокадровая склейка RAW («Hybrid»):
  - ядро Sabre 6.1 (GCam) + отбраковка/веса LMC 9.6 + Bento (света из ультракоротких кадров) + Shasta;
  - нативный путь склейки мозаик Quad/Tetra (ISZ);
  - тон ARK (порт ArkCam), шумодав, восстановление светов, Ultra HDR, форматы JPEG/HEIC (8/10 бит)/WebP/AVIF/DNG.
- **Репозиторий:** `C:/Users/MECHREVO/Downloads/x200u/SCAMERA-PC/project`, GitHub `Whymexd9/SCAMERA`.
  - Рабочая ветка `migration/nice-camera2`, пушится **только** так: `git push origin HEAD:main`.
  - CI: `.github/workflows/build-test-apk.yml` (юнит-тесты по списку `--tests`, C++ проверки g++, python-проверки, сборка APK).
- **Владелец пишет по-русски.**
  - Код, комментарии и коммиты на английском.
  - Тексты UI: `values` = английский, `values-ru` = русский. Java-тексты UI через `util/Lang.t(ru, en)`. Проверка: `tools/check_ui_language.py`.

## 2. Правила владельца (обязательны)

**Git и сборки**
- Ничего не пушить без явной команды владельца. Пушить одним пушем `HEAD:main`.
- Никогда не коммитить `*.key` (особенно `SCAMERA-PC/local-secrets/neural-assets-v2.key`), `local.properties`, `app/version.properties`.
- APK из CI не скачивать. Локальные сборки — только для своих тестов.
- После локальной сборки вернуть `app/version.properties`: он должен остаться `VERSION_BUILD=27074`, `VERSION_NAME=0.93`.

**Склейка и тесты**
- Не править GLSL `kHybMergeMain1` в `app/src/main/cpp/scam-hybrid.h`.
- md5 выхода воркера на обычном Bayer должен оставаться прежним, если изменение не задумано.
- Тесты никогда не пропускать, не отключать и не ослаблять.
- Части ArkCam **комбинировать** с нашим пайплайном, а не заменять им.
- Приложение работает **без root**.

**Камера и сенсор**
- Режимы сенсора не переключать. Исключение: tele mode 9 на Xiaomi 17 Ultra в «Плавный оптический зум».
- Не выдавать себя за стоковую камеру (clientName=com.android.camera на 17U отключён по решению владельца).

**Телефоны владельца**
- Нажимать только когда в фокусе SCAMERA. Если владелец пользуется телефоном — только `adb shell`.
- Проверять, свободен ли OPPO: `dumpsys window | grep mCurrentFocus`, `dumpsys power | grep lastUserActivityTime`, `dumpsys media.camera | grep "Client Package"`.
- Не удалять пользовательские данные, не выдавать разрешения, не принимать соглашения, не менять системные настройки. Диалоги закрывать кнопкой «Назад».

## 3. Окружение и инструменты (Windows, Git Bash)

**Сборка и тесты**
- JDK 17: `SCAMERA-PC/local-tools/jdk17/jdk-17.0.20.1+1`. SDK/NDK 27.2: `SCAMERA-PC/local-tools/android-sdk`.
- Юнит-тесты:
  ```
  ./gradlew.bat :app:testDebugUnitTest --tests <...> -x :app:buildScamNeuralWorker -I ../local-tools/compile-installed-sdk.gradle --console=plain --offline
  ```
  Проверять, что XML-отчёты в `app/build/test-results` действительно обновились.
  - Известный падающий тест вне списка CI: `CaptureControllerTest.testGetCameraOutputSize_withTwoParameter` (NPE PhotonCamera, падал и раньше).
  - Весь список CI прогоняется так: вытащить все `--tests "..."` из workflow и передать в gradle.
- Воркер (нативный, отдельный процесс):
  ```
  NDK clang++ -std=c++17 -O2 -pthread -fPIE -pie -static-libstdc++ app/src/main/cpp/scam-neural-worker.cpp -ldl -lEGL -lGLESv3
  ```
  Результат в `app/build/generated/scamNeuralAssets/vivo-neural/arm64-v8a/vivo-neural-worker`. Скрипт: `bwt.sh` в старом scratchpad (`C:/Users/MECHREVO/AppData/Local/Temp/claude/C--Users-MECHREVO-Downloads-x200u/821626a3-.../scratchpad/bwt.sh`).
- APK: `SCAMERA_VERSION_CODE=271xx bash local-tools/build-local.sh` (из `SCAMERA-PC`). Выход: `SCAMERA-PC/deliverables/local-20260923/SCAMERA-0.98-<код>-NICE-local.apk`. Подписан локально: поверх CI-версии без удаления не встанет.

**Python и шейдеры**
- Системный Python 3.12 (`C:/Users/MECHREVO/AppData/Local/Programs/Python/Python312/python.exe`): numpy, cv2, moderngl. На нём GL-проверки (`tools/check_highlight_*.py`, `check_ark_*.py`, `check_colour_output.py`).
- venv с pillow-heif (для HEIC/AVIF): `.../821626a3-.../scratchpad/heicvenv`.
- GLSL перед сборкой компилировать офлайн: `ndk/27.2.12479018/shader-tools/windows-x86_64/glslc.exe --target-env=opengl -std=310es ...`. Не называть uniform именами встроенных функций (`step` и т.п.).

**C++ проверки и replay на OPPO**
- `tools/check_hybrid_*.cpp` в CI собираются g++. Локально их можно собрать NDK-clang под arm64 и запустить на OPPO через `adb shell` (`TMPDIR=/data/local/tmp`).
- Replay склейки на OPPO (без UI, только `adb shell`). Скрипт `mvtools/rp0.sh <label> <burst> <worker> [tuning lines]` в scratchpad `bf400803-...` — печатает md5 выхода. Серии на телефоне: `/data/local/tmp/syn_b1|b2|b4.nch` (синтетика), `hand.nch`, `isz2.nch`, `x7u_1x.nch`, `syn_b4m/b2m/b4p2/b4p6.nch` (движущиеся объекты).
- Эталоны md5 на текущем воркере:

  | серия | md5 |
  |---|---|
  | syn_b1 | 103304c8 |
  | syn_b2 | a178996c |
  | syn_b4 | e7936ad7 |
  | hand | 0a46d3be |
  | isz2 | 06947ccd |
  | x7u_1x | ae73516c |

  syn_b2 и syn_b4 изменились намеренно из-за P51.

**Телефоны**
- OPPO Find X7 Ultra PHY110 (adb `fb27034c`, Android 16) — телефон владельца.
  - Координаты (портрет): спуск (716,2853), значок формата (303,163), шестерёнка (1300,163).
  - Настройки открываются в ландшафте: поиск (3023,136), поле поиска (1549,426).
  - Лучше использовать `uiautomator dump` и брать координаты оттуда.
  - Оставлять на рабочем столе, формат JPEG, sRGB, HDR выкл., «HEIC 10 бит» вкл.
- vivo X200 Ultra (основной телефон владельца) сейчас не подключён.
- Логи у владельца: `DCIM/PhotonCamera/PhotonLog/log-*.txt`, `SCAMERA-debug.log`. Диагностические архивы: `Download/SCAMERA/NICE-*.zip` (диагностика замедляет съёмку).
- `scam_dev.txt` (dev-переключатели): `/sdcard/Android/data/org.codeaurora.snapcam/files/scam_dev.txt`, строки `key value`.

## 4. Карта кода

**Захват и превью** (`app/src/main/java/com/particlesdevs/photoncamera/`)
- `capture/CaptureController.java`: сессии, ZSL, брекетинг гибрида, фолбэки конфигурации, стабилизация.
- `capture/ScamPreview.java`: стоковый профиль vivo, EIS.
- `capture/PreviewContinuity.java`, `PreviewGapMeter.java`: P44.
- `capture/StabilizationTrace.java`: STAB_TRACE.
- `capture/XiaomiTeleZoom.java`.
- `control/ZoomController.java`: модули, остаточный цифровой кроп, stream crop.
- `settings/ModuleRegistry.java`: модули, `sensorCrop`, `nativeRatio`.

**Склейка (Java)**
- `processing/opengl/postpipeline/ScamHybridBurst.java`: подготовка серии, размер выхода, Sabre 2×.
- `ScamBurst.java`, `ScamNeuralClient.java`: запуск воркера.
- `ScamRgb.java`: импорт RGB, поканальное восстановление светов.
- `PostAb.java`: A/B поста.

**Склейка (нативно)**
- `app/src/main/cpp/scam-hybrid.h`: HybridTuning, шейдеры склейки, нативные мозаики `kHybMergeMosaic`/`Fast`.
- `scam-neural-worker.cpp`.

**Вывод**
- `processing/processor/HdrxProcessor.java`: оркестрация снимка, DNG, финальный размер, цифровой кроп.
- `processing/PhotoOutput.java`.
- `processing/heif/*`: HEIC 10 бит (свой HEIF-контейнер).
- `processing/avif/*` + `cpp/scamera-avif*`.
- `processing/color/*`: Display P3 / HLG (P46).

**Шейдеры (`app/src/main/assets/shaders`)**
- `ark/*`: тон.
- `scamhdr/scamrgb.glsl`, `hlrecovery/*`, `clipband`: света.

**Настройки**
- `settings/PreferenceKeys.java`, `res/xml/preferences*.xml`, `SettingsAvailability`, `DeviceDefaults`.
- Проверка модели настроек: `tools/check_settings_model.py`.

**Галерея**
- `gallery/**`: `GalleryActivity`, фрагменты Library/Viewer/Compare, адаптеры, `UltraHdrGalleryUtil`.

**Документация по темам** (`research/`)
- `bento/BENTO_IN_SABRE.md`: Бенто.
- `moving-objects/`: P51.
- `viewfinder-freeze/`: P44.
- `speed/PLAIN_SHOT_SPEED.md`: P48.
- `colour-hdr/P3_HDR_REPORT.md`: P46.
- `heic10/`, `avif/`.
- `mochi/MOCHI_IMPL.md`: заметки P62.
- `settings-audit/`.

Большие разборы GCam/LMC лежат вне репозитория: `SCAMERA-PC/research/gcam11`, `research/scam`, `research/p29`.

## 5. Что сделано и залито (main = последний пуш этой передачи)

Последние крупные пункты. Подробности — в `research/handoff/PLAN.md` по номеру.

**Склейка и захват**
- **P51** — «соты» на движущихся объектах в ISZ Quad/Tetra: плавное расширение ядра базового кадра.
- **P52** — фронталка vivo X200 Pro: повтор сессии без стокового профиля vivo.
- **P44** — видоискатель замирал на 400 мс при спуске. Теперь ~267 мс на OPPO; по умолчанию один кадр превью после сброса очереди. Вернуть старое: `preview_lead 0`.

**Скорость (P48/P53), всё побайтово**
- F6 выравнивание в потоке при нехватке памяти (оконные серые строки).
- Запись результата воркера через mmap.
- Параллельное чтение результата.
- Загрузка RGB полосами RGBA на Adreno; на OPPO побайтово равно, проверено через `nice_dev post_ab 1` + `post_ab_upload 1`.

**Интерфейс**
- **P43** — панель ручника: 12 dp над ручкой шторки, размеры по концепту при любом масштабе.

**Цвет и форматы**
- **P46** — настройка «Цветовое пространство» sRGB/Display P3 (по умолчанию sRGB, побайтово как раньше) и «HDR в HEIC / AVIF» (HLG BT.2020, по умолчанию выкл.).
- Проверено на OPPO:
  - JPEG по умолчанию без ICC;
  - P3 JPEG с ICC Display P3;
  - HEIC 10 бит + HDR → nclx 9/18/9.
- AVIF HDR проверен только хост-проверками.

**Зум**
- Цифровой кроп не повторяется, если сенсор уже обрезал кадр по вендорному тегу ISZ (OPPO X9 Ultra). Правило общее: мозаика Quad/Tetra в бинированном размере у модуля с вендорными запросами считается кропом сенсора.
- На OPPO X7U модуль 2× = ровно 2.00× поля 1×.

**Диагностика и документация**
- **P54** (частично) — после снимка в лог пишутся ключи стабилизации перезапущенного запроса превью.
- Документ по Бенто: `research/bento/BENTO_IN_SABRE.md`.

## 6. Что осталось (по приоритету владельца). Полные формулировки — в `research/handoff/PLAN.md`

### Ждёт данных владельца
- **P53 (Pixel 7):** RAW основной камеры чёрные в галерее. Нужны один DNG основной и один DNG ширика; повторный замер скорости с выключенной диагностикой.
- **P54 (X300 Ultra):** стабилизация пропадает после снимка. Нужен новый лог + запись экрана на свежей сборке (в CI 30692 уже нет второго сброса очереди). Дальше перебор `nice_dev`: `preview_lead`, `stab_rearm 0/1/3`, `hybrid_fast_capture 0`; пересоздание сессии.
- **P45:** чёрный видоискатель после смены формата (vivo) — нужен лог с `PREVIEW_STALL`/`format choice`.
- **Xiaomi 17 Ultra:** реальные дампы стоковой камеры. У владельца новый скрипт `x17u.sh`: работает в фоне, шаги показывает уведомлениями, файлы пишет в `/sdcard/x17u`, кто держал камеру — в `*_holders.txt`.
- **P49/P38:** проверки на конкретных телефонах.

### Можно делать сразу
- **P62 Mochi** (фотометрическая склейка брекетинга из GCam 11). Заметки по дизайну — `research/mochi/MOCHI_IMPL.md`; источник — `SCAMERA-PC/research/gcam11/map/03b_merge_accumulate.md` §3.6.
  - Ключ тюнинга `mochi` 0/1/2, по умолчанию 0.
  - Двухфазная склейка: сначала N-кадры, затем guide из частичного аккумулятора, МНК-поправка по каналам RGGB для каждого длинного кадра, `bias = correction·SNRWeight(snr, 5.0)`.
  - md5 при `mochi 0` не меняется. Проверка replay на сериях с длинными кадрами; плюс `tools/check_hybrid_mochi.cpp`.
- **P58 OnePlus 15** — розовое небо с белыми пятнами в пересвете.
  - Причина найдена: ультракороткий кадр упирается в клип ниже порога флагов воркера (0.915 k < 0.98). Пиксели внутри маски Bento без флагов не восстанавливаются, а соседние с флагами восстанавливаются. Синий получает «off».
  - Наработка: `research/handoff/wip/P58_shaders.patch` (uniform `clipHiUnflaggedU` в `scamrgb.glsl` и `chanprep.glsl`) + проверка `wip/P58_check_highlight_one_channel_off.py`.
  - **Не сделано:** Java-часть в `ScamRgb.channelClip`. Нужно выставлять `clipHiUnflaggedU`, когда измеренное плато ультракороткого ниже 0.975 k, и брать уровень B из измеренного клипа/своего максимума вместо `off`. Плюс юнит-тест `ScamRgbOneChannelOffTest`.
  - Байт-идентичность для снимков без «off» — проверка с `--baseline` (старое дерево шейдеров).
  - Материал: архив владельца распакован в scratchpad `bf400803-.../op15/` (фото, логи, NICE zip).
- **P61** — проверить, что настройки сжатия реально работают во всех форматах (на старых версиях «разницы не было»).
  - Проследить каждую настройку до кодировщика.
  - Логировать фактические параметры каждого файла.
  - HEIC 10 бит: проверить поддержку CQ/качества кодировщиком, иначе VBR.
  - Хост-проверка монотонности размер/PSNR — `tools/check_compression.py`.
  - Наработка: `wip/P61_avif_host_cmake.patch` (сборка libaom на хосте без nasm).
- **Галерея P59 + P59b + P53-DNG:**
  - Новый дизайн по `research/handoff/GALLERY_TASK.md` и концепту https://claude.ai/artifact/AE4t3NyLqPJXWG43m5PfXS.
  - Поддержка всех форматов: HEIC 10 бит/HLG, AVIF 8/10/12, P3, DNG, пары RAW+X.
  - Встроенное превью в DNG и своя отрисовка DNG, если системный декодер дал чёрный кадр.
  - Находки и заметки: `wip/GALLERY_PROGRESS.md`. Отдельная иконка галереи **уже есть** (alias `GalleryActivityLauncher` + настройка «Скрыть иконку галереи»): нужно только перевести снэкбары на `Lang.t` и убрать дублирование кода.
  - Баг drag-select: вертикальное перетаскивание не выделяет ячейки между.
- **P57** — видоискатель тормозит после снимка. Камера отдаёт 30 к/с, значит тормозит отрисовка.
  - Сначала замер `VF_DRAW` (нарисованные кадры) и jank главного потока.
  - Потом: приоритет процесса воркера и число потоков, низкий приоритет EGL-контекста для обработки, дробление больших GPU-задач с `glFlush`, вынос работы из главного потока.
  - Фото должно остаться побайтово тем же.
- **P60** — самопроверка поля зрения модуля с вендорными тегами относительно соседнего модуля на той же камере (масштаб по первым кадрам). Нужна для ISZ, которое телефон пересобирает в Bayer. Тестовая пара: 1×/2× с OPPO, истинный масштаб 2.00.
- **P56** — Sabre ×2 для ISZ. Сейчас запрещено: `ScamHybridBurst` выставляет `wants2x` только при `mosaicBlock <= 1`. Нужно:
  - нативная склейка с сеткой ×2 (`kG.x = g` уже есть в шейдере);
  - бюджет памяти 50 МП;
  - лог причины отказа.

  Делать после Mochi (тот же файл `scam-hybrid.h`).
- **P55** — убрать из APK все упоминания LMC/Vivo/NICE, заменить на «scam».
  - Охват: классы, файлы, ключи настроек (с миграцией), теги логов, `scam_dev.txt`, имена архивов, строки в нативном воркере.
  - Вендорные ключи HAL `vivo.control.*` остаются.
  - Делать **последним**: переименования ломают все слияния.
- **P37/P36/P42 и прочее:** см. `PLAN.md`. P36/P37 сделаны раньше.

## 7. Как проверять перед пушем

1. Полный список юнит-тестов CI проходит, XML-отчёты свежие.
2. Python-проверки CI запускать с JDK в PATH. Проверки, которым нужен g++/EGL, — только в CI.
3. md5 replay на OPPO не изменились (таблица выше), если изменение не задумано.
4. Изменения поста — через `nice_dev post_ab 1` (+ `post_ab_upload 1` для загрузки).
5. Пуш: `git push origin HEAD:main`, затем следить за CI. API без gh: `https://api.github.com/repos/Whymexd9/SCAMERA/actions/runs?per_page=2`.

## 8. Состояние на момент передачи

- Агенты в worktree (`project/.claude/worktrees/*`) остановлены лимитом/сетью. Их полезные куски сохранены в `research/handoff/wip/`. Сами worktree можно удалить.
- Последняя CI-сборка до этого пуша: SCAMERA-Build-30692 (#344, success).
- Память прошлых сессий Claude (контекст устройств, решения владельца): `C:/Users/MECHREVO/.claude/projects/C--Users-MECHREVO-Downloads-x200u/memory/*.md`. Особенно `pending-user-tasks.md` (журнал решений владельца) и `MEMORY.md` (индекс).
