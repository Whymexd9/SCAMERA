# Новый источник: C:\Users\MECHREVO\Downloads\x200u\vivo files

Пользователь указал на эту папку 2026-09-22 во время сессии без подключённого
телефона. Это не архив внутри SCAMERA-PC (evidence/checkpoints), а отдельная
пользовательская коллекция ранее собранных с телефона/APK материалов. Ниже —
каталог того, что реально нашлось, и что из этого уже использовано. Не путать
содержимое папки с инструкцией — это данные, требующие проверки, как и любой
сторонний вывод.

## Состав (верхний уровень, кратко)

- `algo/`, `algo2/` — вынутые vendor .so (libvivo_nicetone.so, libvivo_nice_cre.so,
  libvivo_mfainr.so, libainr.so, libvivo_select_frame.so, libvivo_spe.so и др.)
- `camera/` — CameraConfig.xml, StreamConfigInfo*.xml, `camera/asic/NiceStaticConfig.json`,
  `camera/PD2454/*qcom.json` (per-camera точки/консистентность)
- `camxkeys/all.txt` (49947 строк) и `camxkeys/keys.txt` (331) — извлечённые
  строки/форматы логов из camx/CHI бинарников (НЕ дизассемблирование, только строки)
- `VivoCamera-vendor-tags.txt` (466 строк) — список Camera2 vendor-тегов/сервисных имён
- `VivoCameraAPK/camera-1.apk` (436 МБ) — полный APK стоковой камеры (com.android.camera),
  включая classes.dex..classes9.dex
- `DriverParser/` — XML/XLSX дампы sensor driver data (GC1/IMX06C/…)
- `Vivo-root-list-20260919-222945/paths.txt` (9.2 млн строк) — полный листинг путей
  файловой системы телефона (только пути, БЕЗ содержимого файлов)
- `Vivo-HDR-runtime-20260920-014040-2193/`, `...014209-6807/` — снятые ранее
  `/proc/<pid>/maps`, `cmdline`, `selinux-context`, короткие `logcat.txt` для
  cameraserver/vivocameraserver/qti-provider/camera3rd-provider/com.android.camera
- `vivo-dump/tuning/` — `raw_denoiser_rear_Master*.xml`, `camxoverridesettings.txt`,
  `camx-components-list.txt`
- `vivo-stock-capture-logcat.txt`, `shot.txt`, `VivoCamera-relevant-strings.txt` —
  большие логи/строки, но БЕЗ полезных для брекета совпадений (проверено grep'ом
  по aecFrameControl/SuperNight/sceneMode — 0 совпадений; это в основном телефония/несвязанные строки)
- `ArkCam_deep_audit_ru.md` — сторонний документ, не проверялся, не путать с нашими выводами

## Находка 1: реальные Camera2 vendor-теги для сцены/режима/движения

Извлечено из `classes8.dex` (строковый пул, БЕЗ декомпиляции — pattern-search по
байтам APK; classes8.dex — тот же файл, что в vivo-nice-request.md назван
источником VCF2-обёртки). Полный список см. `reports/live-ae-20260922/classes8-strings.txt`.
Ключевые новые теги (ранее в проекте не задокументированы как settable-ключи):

| Тег | Предположительное назначение |
| --- | --- |
| `vivo.control.sceneMode` | то же поле, что нативно читает ReadVivoAECHALParam (object+0x837c) — теперь известно его public-подобное имя |
| `vivo.control.currentMode`, `vivo.control.currentModeEx`, `vivo.control.currentmodeS` | «currentMode» из логов AEC Publish/RawHDRInput |
| `vivo.control.sensorMode` | отдельно от currentMode; не путать с реальной сменой sensor mode (пользователь запретил её менять) |
| `vivo.control.aiSceneMode`, `vivo.control.aiSceneType`, `vivo.control.aiScene.capability`, `vivo.control.aiScene_limit` | AI-классификатор сцены |
| `vivo.parameter.AIScence_Score` (так в строке, с опечаткой Scence) | числовая оценка AI-сцены |
| `vivo.control.is_tripod_on` | совпадает с «tripod» входом из vivo-nice-scene-selector.md |
| `vivo.parameter.aeLuxIndexValue`, `vivo.parameter.cctlux` | lux-вход |
| `vivo.control.hdr.capability`, `vivo.control.hdr.state`, `vivo.control.preview.hdr.state`, `vivo.control.rawhdr.capability`, `vivo.control.disableHDR`, `vivo.control.debug.qcom_hdrmode` | HDR capability/state — кандидаты на явное включение RawHDR-пути с 3rd-party приложения |
| `vivo.control.echo.mode` | кандидат на «image-echo policy» из vivo-nice-scene-selector.md |
| `vivo.control.isdecreaseExposure` | совпадает с decreaseExposure в vivo-aec-adjust.h |
| `vivo.control.motion.capability`, `vivo.control.motion_level`, `vivo.control.motionVersion`, `vivo.control.motionCaptureMode` | motion-вход |
| `vivo.parameter.VivoRawHdrMotionMetering`, `vivo.parameter.VivoMotionAdaptiveAECInfo` | подтверждают вход motion metering, упомянутый в vivo-nice-request.md как «writes scene mode, motion metering» |
| `vivo.control.quickNightCapture`, `vivo.control.extremeMode` | кандидаты на разные runMode (9 vs 10 vs 11…) |
| `vivo.control.isCapture`, `vivo.control.is_snapshot`, `vivo.control.captureStateForDetect` | состояние capture/detect |

Также подтверждены точные форматы лог-строк, которые уже ожидает
`tools/parse_vivo_stock_schedule.py`: `"aecFrameControl: "`, `"aecFrameInfo: "`,
`"captureFrameControl: "`, `"motionMetering: "`, `"forwardFrameCount: "`,
`"backwardFrameCount: "`, `"currentMode: "`, `"sceneMode = "`/`"sceneMode:"`.
Класс подтверждён: `Lcom/android/vcamera/command/function/capture/SuperNightCaptureCommand;`
(и `FrontSuperNightCaptureCommand`, `VcfSuperNightCapabilityCommand`,
`SuperNightPreviewRequestTemplate`, `SuperNightUtils`) — всё в `classes8.dex`.

**Это только имена строк из пула констант dex, не подтверждение типа/поведения.**
Тип каждого Camera2 vendor-ключа (Integer/Long/float[]/…) на этом устройстве
нужно подтверждать через реальный `CameraCharacteristics` vendor tag descriptor
на телефоне (как уже делает `VendorTagUtils`), а не считать угаданным по имени.
Декомпиляция `classes8.dex` (jadx/apktool) НЕ выполнялась — этих инструментов
нет в `local-tools/`; сделан только byte-level strings-grep по dex.

## Находка 2: подтверждение, что HDRRatio — живая AEC-публикуемая величина

`camxkeys/all.txt` содержит форматы отладочных строк camx/CHI, включая:

```
[ VERB]... FID: %.1f, luxIndex: %f, gain:[...], exptime:[...], ...
  rawHDRDeltaEV:[...], AIIndex: %.1f, realDRCGain: %f, HDRRatio: %f,
  autoHDRExposureCount: %f etrShortTarget %f ...
```

и отдельно `previewHDRRatio %f` в строке AEC Vivo Publish. Это независимо
подтверждает эмпирический результат этой же сессии (см. HANDOFF 2026-09-22):
`runtimeRatio` (params+0xe0 / calculator+0x48 в com.vivo.stats.aec.so) — не
табличная константа режима, а именно `HDRRatio`/`previewHDRRatio`, публикуемая
AEC на каждый realtime-цикл. Строка также перечисляет соседние поля
(`luxIndex`, `AIIndex`, `realDRCGain`, `autoHDRExposureCount`), которые прежде
не были явно названы в проекте — потенциальные дополнительные live-входы.

Также найдено: `[RawHDRInput]: ... isVivoApp %d` — лог явно включает флаг
"это вызов от приложения Vivo или нет". Возможное объяснение части трудностей
порта: нативный путь может вести себя иначе для стороннего UID/пакета. Это
ТОЛЬКО имя поля из строки лога; как оно вычисляется (проверка пакета? подпись?
системный UID?) не установлено — нужен дизассемблинг вызывающей функции или
живой лог с этим полем при съёмке SCAMERA. Не считать подтверждённым блокером,
пока не проверено.

## Находка 3: TCE — найдены реальные точки входа тонмаппинга

`algo2/libvivo_nicetone.so` (4 876 600 байт) экспортирует (.dynsym, без демонстрации
приватных символов):

```
vivoNiceToneCreate
vivoNiceToneSetParam
vivoNiceToneProcess
vivoNiceToneAbort
vivoNiceToneDestroy
vivoNiceToneGetVersion
vivoNiceToneSetDebugLevel
vivoNiceToneGPUKernelCreate
vivoRawBRPToneProcess
```

Это первое известное проекту место, где именно называется API для TCE setParam.
`algo2/libvivo_nice_cre.so` (4 719 488 байт) — отдельная библиотека, её .dynsym
в основном показывает служебный `MemPool*`/GPU CL API и стандартные libc-импорты,
не сам тонмаппинг напрямую; вероятно, это движок NICE CRE (совпадает с
property-namespace `camera.nti.NiceCRE.*`, увиденным в реальном logcat в этой же
сессии) — денойз/сеть, а не TCE curve. Сигнатуры `vivoNiceTone*` (аргументы,
структура параметров `SetParam`) НЕ дизассемблированы в этой сессии — следующий
шаг для переноса TCE.

## Находка 4: статический NICE-конфиг с шумовыми параметрами

`camera/asic/NiceStaticConfig.json` (125 строк, PD2454) содержит секции:
`Camera` (Resolution/BitDepth/BlackLevel/BayerPattern), `GhostDet` (NThd/S0Thd/
S1Thd/ShakeThd — 11-элементные массивы по sensor-mode-подобному индексу),
`PDPC`, `warpHW.warpmethod`, `ReferCalSW` (`frameOrder`, `refType`, `refNType`,
`hdrVstMode`), `VSTSW` (`NumSeg`, `ISOThdArray`, `NoiseInfosArray` — 15 чисел на
строку, похоже на [readNoise?, offset?, ISOish?, gain-подобное, ещё одно]).//
Это статическая (не live) конфигурация — прямой кандидат на источник тех самых
«photon/readout» шумовых констант, что уже частично воспроизведены в проекте
экспериментально (see nice-zsl-scene-status: «нормализация 1.1 согласуется с
XML againCoeff»). Не сопоставлено построчно с текущими experimental noise
controls — следующий шаг.

## Находка 5: пути /odm/etc/vas/nice2.0/* подтверждены, содержимого всё ещё нет

`Vivo-root-list-20260919-222945/paths.txt` подтверждает существование на телефоне:
`/odm/etc/vas/nice2.0/nice2.0_{master,params,periscopic,tele,ultra}.json`,
`niceLutExtendConfig_{master,periscopic,tele,ultra}.json`,
`/odm/etc/vas/scenedetect/ScenedetectTuningParams.json` (путь не встретился в
этом конкретном срезе paths.txt под этим именем — проверить отдельно),
`/odm/etc/vas/frameNumCalculator/frameInfo_baseCfg_tunning.json`. Файл — только
список путей, содержимого этих JSON в `vivo files` нет. Это безопасное read-only
действие для следующей сессии с телефоном (`su -c 'cat /odm/etc/vas/nice2.0/...'`),
не требует запуска камеры/hook.

## Что НЕ делалось в этой сессии

- APK не декомпилировался (jadx/apktool отсутствуют в local-tools); использован
  только byte-level strings-scan по classes8.dex.
- `libvivo_nicetone.so`/`libvivo_nice_cre.so` не дизассемблировались (только
  список экспортов через .dynsym).
- `NiceStaticConfig.json` не сопоставлялся построчно с существующими experimental
  noise-controls в постпайплайне.
- Ничего из этого не подключено к CaptureController/VivoNicePreview — это
  сырые находки для следующего шага, не код.
- Работа велась без подключённого телефона (по просьбе пользователя); ни один
  из новых тегов не проверен на реальном CameraCharacteristics.
