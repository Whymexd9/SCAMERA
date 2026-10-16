LATEST 2026-10-16: read FULLRES-NATIVE-READY-20261016.md and CONTINUE-ISZ-FULLRES-20261016.txt first. Native FULLRES RAW/AE is proven on all rear lenses; main/UW50 JPEG proven; tele200 JPEG remains a LOW_MEMORY boundary with a separately tested source fix. Full app integration is delegated; DCG deferred. Older statuses below are historical.

# FULLRES + настоящее Sabre 2× + SHDR: передача 2026-10-15

Обновление: основной текущий статус — `FULLRES-NATIVE-MOSAIC-20261015.md`.
Sabre201MP уже выполнен на main/UW/tele; main/tele JPEG сохранены и проверены на ПК,
UW save подтверждён логом, файл впоследствии отсутствует на телефоне.
Подготовлен30820: динамическое число кадров вместо3 и кандидат исправления AE.
Новая цель владельца: FULLRES и ISZ2x/4x без аппаратного ремозаика, DCG на всех задних камерах.
Текст ниже сохраняет первоначальное состояние этой правки; его «APK/201MP не проверены» устарели.

База: `0a1573918363d3610868371be2b9820a8e7752e4` и текущее незакоммиченное дерево. Ничего не пушить. Перед изменением читать `HANDOFF.md`, `PLAN.md`, `.claude/skills/scamera/SKILL.md`, свежий `git diff`: в одной папке работает другая нейросеть. Этот документ описывает отдельную правку поверх P129; установленный APK 30805 её не содержит.

Актуальная цель владельца: ISZ/DCG/FULLRES с единственной склейкой SHDR (бывший Hybrid). SCAM HDR удаляется; стоковый NICE/ZSL-брекет больше не является целью. Для FULLRES требуется настоящее Sabre 2×: RAW 8192×6144 → merge RGB 16384×12288, а не merge 1× с последующим увеличением. Режимы 12/16/20 Мп сохраняют прежний автоматический выбор сетки; явно выбранное «2×» теперь строгое.

## Подтверждённая граница

- Rootless SDK FULLRES50 на PD2454, физическая основная камера 3: production APK 30802 получил три разных RAW 8192×6144, mode 13, auto AE ISO250/16666667 ns; SHDR/Hybrid worker UID10349 сохранил JPEG 8192×6144. Это **50 Мп на сетке сенсора**, без доказательства Sabre 2×/201 Мп. Лог: `../../../reports/pd2454-sdk50-30802-capture-result-20261015.txt`.
- APK 30805 подтвердил теле camera5 force6: аппаратный remosaic, настоящий сенсорный кроп 4×, восемь совпавших по timestamp прошлых RAW, JPEG 5184×3888 после существующих настроек и точного crop 4:3. Это не ISZ без ремозаика. Логи: `../../../reports/pd2454-30805-isz-capture-result-20261015.txt`, `../../../reports/pd2454-30805-isz-verification-20261015.txt`.
- Фотография 16384×12288 из новой ветки **ещё не снята**. APK с этой правкой здесь не собирался. FULLRES других камер, полный RAW 200 Мп телевика, DCG и NoRemo не считаются работающими из-за прохождения тестов этой ветки.
- Реальные GL-лимиты X200 Ultra в обоих production-логах: texture/renderbuffer/viewport 16384. Для 16384×12288 стороны подходят; это не доказательство достаточной памяти.

## Что уже изменено в исходниках

`processing/opengl/postpipeline/ScamHybridBurst.java`:

- Удалён порог входа 16 Мп для Sabre 2×. `sabre2xFits` проверяет стороны GPU и размер **FP16 RGB** в одном Java ByteBuffer; 8192×6144 → 16384×12288 допускается.
- `CLASSIC_INPUT_PIXELS=16000000` остался только границей прежнего/большого планирования памяти, таймаута и переноса в arena. Он больше не запрещает сетку 2×.
- `rgbHalfTransport()` фиксируется на burst. Если RGB32 результата превышает `INT_MAX`, FP16 включается обязательно, даже при dev-override `hybrid_rgb_half 0`.
- Для явного output `2x`: NCH flags bit8 = strict2x; в конец tuning дописываются `grid 2`, `auto2x 0`. При ошибке GPU/worker нет retry на sensor grid и нет CPU-single, выдаётся понятная ошибка.
- Смета фиксированной памяти теперь учитывает выходную сетку; число N ограничивается существующим бюджетом. Жёсткий preflight-отказ по памяти основан на минимуме четырёх одновременных RGBA16F-текстур (input + main1/2/3), если известная свободная память меньше него. Это минимальная граница, а не гарантия успеха при большем объёме.

`processing/opengl/postpipeline/ScamNeuralClient.java`: маркер `rgb-half` теперь берётся из конкретного burst, а не повторно из живых настроек. Формат half-транспорта и его чтение уже существовали; новый контейнер результата не придумывался.

`app/src/main/cpp/scam-hybrid.h`:

- `HybridInput.strict2x` читается из flags bit8 существующего NCH v11/v12; strict-флаг требует header grid2.
- Убраны произвольные 16-Мп запреты/понижения grid2 в парсере, plain Bayer, split/native mosaic и выборе Tetra 2×.
- `hybridRequestedGrid` не позволяет tuning grid1 заменить явно заказанную native-сетку; `hybridAuto2x` запрещает coarse merge + bilinear resize при strict2x.
- Для strict2x native-мозаика должна действительно брать позиции исходных сайтов. Split-мозаика, Tetra T2 и `mosaicNative2x 0` отказываются явно. При strict2x исключение native mosaic не уходит в split fallback.
- Сам четырёхпозиционный GPU merge уже существовал: ±0,25 сенсорного пикселя и четыре dispatch. Его ядро не переписывалось.
- **`kHybMergeMain1` не изменён.** SHA-256 тела шейдера, нормализованного как Git LF: `a43afbb4c03af969e37e6f35f218ef033021e0b76135c2d67d583c15ca7f41f1`; совпадает с HEAD.

`processing/opengl/postpipeline/ScamRgb.java`:

- Исправлено распознавание FP16: прежнее `width*height*12` считалось как int и на 201 Мп переполнялось, из-за чего half принимался за float32. Размеры считаются в long, включая padded PostAb-буферы.
- Для нового большого 2× результата проверяется реальное выделение input/main1/main2/main3 через `GLES31.glGetTexLevelParameteriv` и framebuffer completeness. Ошибка выделения не должна молча дать чёрный результат на этих стадиях; текстуры освобождаются при отказе. Реальный API проверен в локальном Android36 SDK.

`settings/SettingsAvailability.java`: при явном output `2x` существующая настройка auto2x отключена с объяснением через `Lang.t`. Она продолжает работать для 12/16/20 Мп. Сам коэффициент chroma 2× остаётся доступен.

Не изменялись этой задачей: `CaptureController`, `VivoSdkFullResCapture`, `ScamProcessor`, `PostPipeline`, источники RAW/CFA, экспозиция/ISO/vendor gain, шумовая модель, kernel merge. Чужие изменения этих файлов уже присутствовали в рабочем дереве и должны быть сохранены.

## Проверки

Последний локальный Gradle run: **94 теста, 0 failures/errors**, main Java compile прошёл. Набор:

| Suite | Tests |
|---|---:|
| HybridSettingsTest | 31 |
| ScamHybridBurstAnyResolutionTest | 9 |
| ScamRgbHalfTransportTest | 3 |
| ScamHybridBurstRolesTest | 8 |
| ScamHybridBurstCrashTest | 5 |
| VivoSdkFullResCaptureTest | 1 |
| VivoSdkFullResModeTest | 3 |
| VivoSdkRawSessionTest | 4 |
| SensorModeSwitchesTest | 11 |
| IszCapturePolicyTest | 9 |
| VivoPd2454IszTest | 10 |

`ScamHybridBurstAnyResolutionTest` проверяет настоящий запрос 16384×12288 для трёх RAW50, bit8/grid2 в header, обязательный FP16, физические GPU/ByteBuffer-ограничения, память, отсутствие sensor-grid retry при ошибке worker. Гигантский RGB-буфер для теста не выделяется. `ScamRgbHalfTransportTest` проверяет переполнение, неполный/padded half, float32 и неверные размеры. Тесты со старой ошибочной политикой «50 Мп всегда 1×» заменены проверкой нового требуемого поведения; остальные проверки сохранены.

Native: полный `scam-neural-worker.cpp` и `tools/check_hybrid_fullres_2x.cpp` прошли NDK clang syntax-check. Тест полностью скомпилирован/слинкован для ARM64, libc++ статически. Он разбирает sparse transport с тремя отдельными RAW50, проверяет strict grid2 против tuning grid1/auto2x, отказ strict grid1, split/TetraT2-resize. Он **не выполняет фотографическую GPU-склейку 201 Мп**.

Бинарник: `../../../evidence/vivo_hal_2026-10-13/client_identity/agent_sdk_hybrid_fullres_2x_arm64`, 2498424 B, SHA-256 `ea6e9e9e13caa13e244b38251949a3a0825ee8bde4873fb4e88f4658a4eb087d`. Запуск: `TMPDIR=/data/local/tmp /data/local/tmp/agent_sdk_hybrid_fullres_2x_arm64`. На X200 Ultra тест реально прошёл с явно заданным **UID2000(shell)**, `ADB_EXIT=0`: `../../../reports/pd2454-sabre2x-native-check-result-20261015.txt`. Предыдущий root-run отдельно сохранён в `../../../reports/pd2454-sabre2x-native-check-root-result-20261015.txt`. Не путать их: обычный `adb shell` здесь может оказаться UID0 из-за KernelSU. Этот shell-тест не доказывает обработку фото 201 Мп обычным приложением UID10349.

Оба новых теста добавлены в существующий workflow: Java `ScamRgbHalfTransportTest` в список `--tests`, C++ fixture в блок g++/ASAN. Остальные фильтры не удалялись. Actions не запускались, пуша нет. На Windows Linux/ASAN-вариант C++ не запускался; фактический Android shell-run фиксируется отдельно, не заменяется syntax-check.

## Память и незавершённая live-проверка

Для 8192×6144 → 16384×12288 (201326592 выходных пикселей):

| Буфер | Байт | МиБ |
|---|---:|---:|
| RGB float32 внутри worker | 2415919104 | 2304 |
| RGB float16 в транспорте | 1207959552 | 1152 |
| Float coverage во время merge | 805306368 | 768 |
| Одна uint8 карта output | 201326592 | 192 |
| Одна RGBA16F texture | 1610612736 | 1536 |
| Четыре RGBA16F textures одновременно | 6442450944 | 6144 |

Native GPU merge уже полосовой: SSBO/readback-полосы уменьшаются по реальным лимитам, полноразмерный FBO для него не нужен. Полноразмерные GPU textures появляются в **Java post-pipeline**. Worker пока держит полный RGB32; на стадии записи создаёт полный RGB16 и копирует в выходной memfd. Это дорого, но не повод возвращать косметический upscale. До нового снимка нельзя заявлять, что 201 Мп помещаются на конкретном телефоне или имеют больше реальных деталей.

Ultra HDR имеет отдельную прежнюю границу: `PostPipeline.captureDemosaicLinear` хочет RGBA float32, 16 B/output pixel; на 201 Мп это 3 ГиБ, больше одного Java ByteBuffer. Он явно пропускает snapshot, последующий gain-map pass сохраняет SDR fallback. HDR gain-map **201 Мп не завершён** этой правкой. Не подменять это утверждением, что весь SHDR merge выключен: обычный SDR JPEG использует другой путь.

Дальше проверить полный свежий APK с output `2x`: журналы native `HYBRID OUTPUT: Sabre 2x grid 16384x12288`, затем `pipeline runs at the merged size`, конечный JPEG/HEIC нужных размеров и ненулевые осмысленные пиксели. Не принимать сообщения `1x grid, resized`, `merged ... resized to ...`, `sensor grid` или `cpu-single` за исполнение этого требования. При OOM записать точную стадию. Последующие ARK-текстуры/конечный bitmap тоже требуют памяти; они не стали полосовыми от удаления порога 16 Мп.

Если требуется уменьшить пик памяти, следующий обоснованный шаг — писать завершённые native-полосы прямо в FP16/memfd после необходимых CA halo/нормировки и отдельно располосовать тяжёлые post-стадии. Перед таким переносом сравнивать небольшой grid2 с прежним результатом; сохранять фазу CFA, DNG mean по 2×2 под-позициям и геометрию. `kHybMergeMain1` не менять.

## SHDR-only совместимость SDK/переключателей

Аудит после P129 не нашёл функциональной регрессии наших веток:

- Старые Java-имена `isScamEnabled`/`isScamHybridEnabled` сейчас ведут в SHDR; `mergeRoute()` фиксирован на Hybrid. `useStockBracketPlanner()` всегда false, поэтому старые warmup/наблюдатель stock AE не включаются.
- SDK50 lifetime: `VivoSdkRawSession` открывается до characteristics/camera open, session RAW8192×6144 usage0 + JPEG того же размера + PRIVATE preview; repeating содержит только preview. Каждый свежий STILL содержит **RAW+JPEG, без preview target**. Добавление preview к STILL в live-пробе давало ноль RAW/JPEG; граф не менять без проверки.
- `VivoSdkFullResCapture` связывает RAW/result/JPEG по timestamp, проверяет mode13 и ненулевой payload, даёт три serial будущих NORMAL. `CaptureController` вызывает `detachForQueue(true)`, то есть SHDR. SDK session исключена из ожидания RAW ZSL ring. Это PSL, не прошлые кадры.
- MOTION/PHOTO/NIGHT допускаются как still; UI «Фото» может быть внутренним MOTION. Нельзя вернуть прежний guard только PHOTO, иначе кнопка останется ON с незаметным fallback12MP.
- ISZ/DCG/FULLRES исключают друг друга через `SensorModeSwitches`/`ModuleRegistry.select`, без дополнительных restart внутри coordinator. `IszCapturePolicy` исключает FULLRES/DCG и распознаёт только реальные typed enabled Session/Integer presets/валидные маркеры.
- Старые ветки stock AE/SCAM HDR в CaptureController ещё местами скомпилированы, но runtime отключён. Для следующего удаления сохранять нужный SHDR `ScamStockAe.Plan.nOnly` или сначала выделить этот минимальный тип. Восстанавливать SCAM HDR ради FULLRES не требуется.
- Текущее sharpening — ArkLumaSharpen; RawTherapee удалён владельцем в P118. Эта задача sharpening не заменяла.

## Новая локальная сборка: важная несовместимость старой подготовки

Пользователь просит передать работу другой нейросети **для новой локальной сборки APK**; здесь APK не нужен. Не отправлять прежний 30805 как APK с новым Sabre2×.

`evidence/vivo_hal_2026-10-13/local-sdk50-prep-20261014/` содержит исторические source-snapshot/template/helper старых APK. Они **не являются текущей сборочной базой после P129**. Старый собранный `scam-neural-worker` всё ещё содержит 16-Мп отказ и не поддерживает новое strict-поведение. Подменить только classes.dex или перепаковать прежний worker нельзя.

Сначала пересобрать `app/src/main/cpp/scam-neural-worker.cpp` **с текущим `scam-hybrid.h`**. Штатная задача `:app:buildScamNeuralWorker` делает это на Linux/macOS; её параметры есть в `app/build.gradle`: NDK27.2.12479018, Android26, C++17/O2, pthread, PIE, static libc++, `-Wl,-z,max-page-size=16384`, `-ldl -lEGL -lGLESv3`. На Windows задача намеренно отказывает: нужен эквивалентный ручной NDK build свежего worker, помещённого в `app/build/generated/scamNeuralAssets/scam-neural/arm64-v8a/scam-neural-worker`, а не исключение задачи с оставшимся старым файлом.

Затем собрать template из **текущего дерева** и упаковать согласно текущему `tools/package_scam_neural.py`; актуальные pinned manifests находятся в `processing/opengl/postpipeline/ScamNeuralWorker.java`. После P129 `SCAM_TONE_FILES` и SCAM HDR forward/tone-модели удалены; старые списки/pins preparation-скриптов несовместимы. Сохраняются нужные SHDR CRE (`SCAM_FILES`) и remosaic/QNN-бандлы. `local-secrets` не читать и не переносить в handoff. Проверить, что asset `assets/scam-neural/arm64-v8a/scam-neural-worker` и `lib/arm64-v8a/libscamera_worker.so` идентичны **новому** worker, проверить актуальный manifest pins и SHA-256 итогового APK. Сам `check_worker` проверяет ELF, поэтому прохождение проверки старого ELF ещё не доказывает пересборку из новых исходников.

Не менять/не коммитить чужое, не пушить и не запускать Actions без команды владельца. Сохранить новое дерево/патчи и этот документ вместе с исходными логами; источником истины о DCG остаётся отдельный свежий handoff родительского агента.
