# Xiaomi 17 Ultra: дампы зума 2026-10-08 — что в них есть

Входные данные: 8 дампов `stock_N`, 8 дампов `scamera_N` (`dumpsys media.camera`), `stock_logcat.txt` (22 МБ),
PhotonLog `log-2026-10-08.txt`. Оригиналы (110 МБ) лежат вне git: `SCAMERA-PC/research/xiaomi17u/dumps-2026-10-08/`.
Выжимки и скрипт — рядом: `dumps-2026-10-08/` (`dumps_summary.tsv`, `camera_static_zoom.txt`, `vendor_tags_zoom.txt`,
`logcat_excerpt.txt`, `photonlog_excerpt.txt`, `extract_zoom_dumps.py`).

## 1. Главное

1. **В «стоковых» дампах нет стоковой камеры.** Во всех 16 дампах единственный клиент — камера 4, PID 24080, пакет
   `org.codeaurora.snapcam`. Это SCAMERA (её applicationId — `org.codeaurora.snapcam`). Стоковая камера
   (`com.android.camera`) с 10:01 до 10:06 камеру не открывала ни разу: в журнале событий cameraserver нет ни одного
   CONNECT от неё, в logcat в 10:01:27 запускается процесс SCAMERA (SplashActivity → CameraActivity), а
   `com.android.camera` встречается только как фоновый процесс (CpuResourceTracker). В 10:03:52 владелец ушёл в Termux,
   в 10:04:37 снова открылась SCAMERA. Значит, обе серии — это две сессии SCAMERA, и сравнить «сток против нас» по этим
   данным нельзя. Скорее всего, камера была открыта кнопкой/ярлыком камеры, а камерой по умолчанию стоит SCAMERA.
2. **На телефоне стояла старая сборка** (эпоха 97234c6: обрезка по умолчанию, ISZ с 150 мм, нет строк
   `vendor request keys`). Во всех дампах `userZoomRatio` = 3.225 (стекло на 75 мм), зум шёл только обрезкой
   `zoomRatio`. Оптику эта сборка не командовала вовсе.
3. **Что дампы всё-таки показали (это новое):**
   - В каждом результате тела HAL сам сообщает состояние оптического зума: `com.xiaomi.optical.zoom.opticalZoomCurrentRatio`,
     `opticalZoomTargetRatio`, `opticalZoomState`, `opticalZoomSpeedPerMs` (0.005), `opticalZoomCurrentAFDac`,
     `opticalZoomCurrentFNumber`/`TargetFNumber`. Во всех дампах цель = 3.400, текущее = 3.4004–3.4005, состояние 0,
     `android.lens.state` = STATIONARY: стекло стояло на широком конце.
   - Шкала этого отчёта — из характеристик (камеры HAL 0, 4, 5, 6): `optRealZoomRange` = [3.4, 4.3],
     `optUiZoomRange` = [3.2, 4.3], `enableOptZoomratio` = 1. Карта `smartFOV.zoomRatioMap` (камера 5): UI 3.2 → 3.4,
     UI 4.3 → 4.3; для камеры 4: zoomRatio 1.0 → 1.0625, 1.34375 → 1.34375. То есть оптика HAL — это UI 3.2–4.3 =
     75–100 мм (`userZoomRatio`), во внутренней шкале 3.4–4.3. Это совпадает с данными владельца (4.30000019 и 1.34375 на
     100 мм).
   - `android.lens.focalLength` (20.05) положения стекла не показывает: в сборке b087541 он «ехал» за `userZoomRatio`,
     а превью не зумилось (сообщение владельца). Настоящий датчик — `opticalZoomCurrentRatio` (от драйвера зума: дробные
     3.40042 / 3.40047 / 3.40052, плюс AF DAC).
   - HAL считает SCAMERA сторонним приложением: `xiaomi.thirdparty.isThirdParty` = 1 во всех дампах, конвейер HAL —
     `RealtimeDefault0` + `OfflineReprocessForThirdPartyApp0`. И это **при том, что SCAMERA уже отправляла**
     `com.xiaomi.sessionparams.clientName = "com.android.camera"` (код VendorTagUtils с 2026-09-21). Настоящий пакет HAL
     знает сам: `XmDevCamRFConfig … target app: org.codeaurora.snapcam`. Подмена имени ничего не давала и нарушает правило
     владельца — на 17U она убрана.
   - Логические SAT-камеры HAL 0, 5, 6 состоят из физических 3, 2, 4 (сверхширик, основная, телевик). SCAMERA открывает
     камеру 4 напрямую, режим сессии NORMAL (0).

## 2. Таблица по шагам

Мм — фокусное на выходе, которое просила SCAMERA (zoomRatio × 74.419, при ISZ ×2). «Цель/текущее» — отчёт HAL
`opticalZoomTargetRatio` / `opticalZoomCurrentRatio`. Прочерк — в дампе последний результат был частичным (18 ключей),
отчёта оптики в нём нет.

| дамп | время | мм | zoomRatio | userZoomRatio | current_mode (запрос/результат) | цель / текущее | lens.state | focalLength | focalLength35mm | isThirdParty |
|---|---|---|---|---|---|---|---|---|---|---|
| stock_1 | 10:01:39 | 75.0 | 1.0078 | 3.225 | – / 4 | 3.400 / 3.4005 | STATIONARY | 20.05 | 71 | 1 |
| stock_2 | 10:01:51 | 83.3 | 1.1195 | 3.225 | – / 4 | 3.400 / 3.4005 | STATIONARY | 20.05 | 79 | 1 |
| stock_3 | 10:02:02 | 86.1 | 1.1573 | 3.225 | – / – | – | – | – | – | – |
| stock_4 | 10:02:14 | 90.3 | 1.2130 | 3.225 | – / 4 | 3.400 / 3.4005 | STATIONARY | 20.05 | 87 | 1 |
| stock_5 | 10:02:25 | 99.7 | 1.3401 | 3.225 | – / – | – | – | – | – | – |
| stock_6 | 10:02:36 | 99.7 | 1.3401 | 3.225 | – / – | – | – | – | – | – |
| stock_7 | 10:02:48 | 116.0 | 1.5581 | 3.225 | – / – | – | – | – | – | – |
| stock_8 | 10:02:59 | 150.8 (ISZ) | 1.0131 | 3.225 | 9 / – | – | – | – | – | – |
| scamera_1 | 10:04:48 | 75.0 | 1.0078 | 3.225 | – / 4 | 3.400 / 3.4005 | STATIONARY | 20.05 | 71 | 1 |
| scamera_2 | 10:05:00 | 83.8 | 1.1259 | 3.225 | – / 4 | 3.400 / 3.4005 | STATIONARY | 20.05 | 80 | 1 |
| scamera_3 | 10:05:11 | 92.6 | 1.2437 | 3.225 | – / – | – | – | – | – | – |
| scamera_4 | 10:05:22 | 100.0 | 1.3434 | 3.225 | – / 4 | 3.400 / 3.4005 | STATIONARY | 20.05 | 100 | 1 |
| scamera_5 | 10:05:34 | 116.4 | 1.5646 | 3.225 | – / – | – | – | – | – | – |
| scamera_6 | 10:05:46 | 150.4 (ISZ) | 1.0104 | 3.225 | 9 / 9 | 3.400 / 3.4004 | STATIONARY | 20.05 | 71 | 1 |
| scamera_7 | 10:05:57 | 175.3 (ISZ) | 1.1781 | 3.225 | 9 / 9 | 3.400 / 3.4005 | STATIONARY | 20.05 | 84 | 1 |
| scamera_8 | 10:06:09 | 200.0 (ISZ) | 1.3438 | 3.225 | 9 / 9 | 3.400 / 3.4004 | STATIONARY | 20.05 | 100 | 1 |

Во всех 16 дампах одинаково: клиент `org.codeaurora.snapcam` (PID 24080), камера 4, потоки 1920×1440 PRIVATE (0x22,
usage 0x20100) + 4080×3072 RAW_SENSOR (0x20), режим сессии NORMAL (0), `cropRegion` 0 0 4080 3072,
`EnableInsensorZoom` 0, `clientName` = "com.android.camera", `trdCloudSwitch` 120. `focalLength35mm` идёт за обрезкой
и не учитывает ISZ (это поле для EXIF). Фокус AF (`com.xiaomi.afinfo.exifinfo`): `zoom_ratio 1.00`,
`FocalLengthRatio 1.01` — тоже широкий конец.

## 3. Различия сток / SCAMERA

Сравнивать нечего: обе серии — SCAMERA одной сборки с одинаковыми потоками, ключами сессии и камерой. Различаются только
положения зума и включённый ISZ (режим 9) в stock_8 и scamera_6–8. Чего нам не хватает относительно стока (ключи запроса,
режим сессии, логическая камера, operation), по этим дампам **не видно**.

## 4. Вывод и что изменено

- Доказано: в нашей сессии оптика HAL не сдвигалась (3.400 → 3.400), а `android.lens.focalLength` положения стекла не
  показывает. Поэтому прежняя проверка «линза идёт за командой» в сборке b087541 обманывалась (фокусное шло за
  `userZoomRatio`), не переключалась на обрезку, и превью в 75–100 мм стояло.
- Изменено (коммиты в ветке `x17u-zoom2`):
  1. `0603612` — выжимки дампов и скрипт.
  2. `d118089` — на 17 Ultra больше не отправляется `clientName = com.android.camera` (без подмены; на других Xiaomi как было).
  3. `6afcacf` — XiaomiTeleZoom: положение линзы берётся из `opticalZoomCurrentRatio` (шкала HAL пересчитывается в мм по
     `optRealZoomRange`/`optUiZoomRange`); если стекло за 0.9 с не пошло за командой — режим обрезки, и 75–100 мм зумится
     в любом случае. В запрос добавлен `com.xiaomi.optical.zoom.opticalZoomTargetRatio` (цель в шкале HAL: 3.42 на 75 мм …
     4.3 на 100 мм) — единственный входной кандидат, который видно в дампах; работает ли он, не проверено. Все изменения
     отчёта HAL пишутся в PhotonLog. Возврат: в `nice_dev.txt` строка `xiaomi_opt_target 0` убирает цель,
     `xiaomi_hal_optics 0` — прежнее поведение целиком.
- Что заставляет оптику ехать у стока — по этим данным неизвестно. Подозрение (не доказано): движение оптики ведёт
  SAT-конвейер HAL для «своего» приложения; признак «стороннее» HAL берёт из настоящего пакета процесса, а не из
  `clientName`.

## 5. Что без root / подмены невозможно

- Если окажется, что стекло у стока едет только в «своём» конвейере HAL (`isThirdParty` = 0), то это решается настоящим
  пакетом процесса. Изменить его можно только подменой пакета или root — этого делать нельзя. Пока это не доказано:
  нужен настоящий дамп стока.
- Открыть логическую SAT-камеру (0/5/6) вместо камеры 4 — не подмена, но это другой конвейер HAL и, скорее всего, другие
  режимы сенсора; без разрешения владельца не делалось.

## 6. Как снять настоящий сток

1. Открыть «Камеру» Xiaomi с её значка (не кнопкой камеры и не ярлыком — они открывают камеру по умолчанию, SCAMERA),
   или `am start -n com.android.camera/.Camera`, затем перейти на 3.2×.
2. Перед каждым дампом проверить: `dumpsys media.camera | grep "Client Package Name"` должен показать
   `com.android.camera`.
3. На каждом шаге (75, 83, 90, 100, 120, 150, 175, 200 мм) — `dumpsys media.camera > stock_N.txt`; logcat одновременно
   (`logcat -b all`). В дампе смотреть «Logical request settings» и «Latest received frame»: ключи
   `com.xiaomi.optical.zoom.*`, `userZoomRatio`, `zoomRatio`, `xiaomi.isZooming`, `xiaomi.satIsZooming`,
   `com.xiaomi.sessionparams.operation`, физические ID, режим сессии, `isThirdParty`.

## 7. Что смотреть в PhotonLog на 17U (новая сборка)

- `XiaomiTeleZoom: tele 4: HAL optical range [3.4, 4.3] = UI [3.2, 4.3]; lens position from the HAL's …opticalZoomCurrentRatio, request names …opticalZoomTargetRatio`
- `request key com.xiaomi.optical.zoom.opticalZoomTargetRatio accepted`
- `HAL xiaomi.thirdparty.isThirdParty = 1 (…)`
- `optics (HAL): current 3.400 = lens 74.4 mm, target 3.400 = 74.4 mm, state 0; commanded 92.0 mm (HAL 4.019, sent as target)` —
  если `current`/`target` идут за `commanded`, стекло движется.
- Успех: `the lens moves: 74.4 -> 9x.x mm (HAL opticalZoomCurrentRatio …)`.
- Неудача: `the lens does not follow userZoomRatio (commanded … by HAL opticalZoomCurrentRatio 3.400 and never moved): crop mode from now on` —
  стекло стоит, превью зумится обрезкой.
- `VendorTagUtils: Xiaomi 17 Ultra: com.xiaomi.sessionparams.clientName not sent (no stock-camera impersonation)`.
- На глаз: между 75 и 100 мм превью должно увеличиваться (оптикой или обрезкой).
