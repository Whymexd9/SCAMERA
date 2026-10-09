# Xiaomi 17 Ultra: стоковый зум по съёму владельца (2026-10-09) и P41b

Вход: `x17u_stock (2).zip` от владельца (скрипт `x17u_stock.sh`: `start.txt`, `z_<zoom>.txt` 0.6–30×, дампы после
снимков 3.5/7/10×, `end.txt`, logcat 163 МБ, getprop). Сырые файлы — `SCAMERA-PC/evidence/x17u_stock_2026-10-09/`
(в git не кладём).

## Сессия стока (`start.txt`, камера 5)

- Логическая SAT-камера **5** (физические 3, 2, 4), клиент com.android.camera, **operation mode CUSTOM 36866 (0x9002)**.
- Потоки: 1440×1080 PRIV (превью), 4096×3072 YUV, 1440×1080 YUV, 408×306. RAW нет.
- Ключи запроса/сессии: `org.codeaurora.qcamera3.sessionParameters.ExtendedMaxZoom` = 1, `…EnableInsensorZoom` = 1,
  `enableMFNR` = 1, `com.xiaomi.teleFallback.isDisable` = 0, `xiaomi.sat.targetzoom` = 0, `zoomRatio` = `userZoomRatio`
  (= цифра на шкале), `clientName` = com.android.camera, `thirdPartyCalled` = 0, `xiaomi.app.module` = 163.
- Правила ресурсов HAL (XmDevCamRFConfig) различают приложение + op mode + роль.

## Камера 0 (доступна сторонним) против 5

| | 0 | 4 (тел) | 5 (сток) |
|---|---|---|---|
| физические | 3, 2, 4 (LOGICAL_MULTI_CAMERA) | — | 3, 2, 4 |
| zoomRatioRange | 0.6–10 | 1–13.44 | 0.6–10 (satZoomRatioRange 0.6–120) |
| optReal / optUi | [3.4, 4.3] / [3.2, 4.3] | те же | те же |
| RAW_SENSOR | 4096×3072 (главная) | 4080×3072 | — |
| ExtendedMaxZoom (platform) | 100 | | |
| PRIV-размеры | все размеры камеры 4 есть | | |

Камера 0 — та же схема SAT, что и 5. Логический RAW 4096×3072 — это главная камера, поэтому RAW тела берём
физическим потоком камеры 4.

## Шаги стока (кадры камеры 7, `isThirdParty` = 0)

| шкала | оптика target/current | режим |
|---|---|---|
| 3.2 | 3.40 (19.9 мм, 75 мм экв.) | 4 |
| 3.54 | 3.68 | 4 |
| 3.88 | 3.96 | 4 |
| 4.16 | 4.19 | 4 |
| 4.3 | 4.30 (26.5 мм, 100 мм экв.) | 4 |
| 5.02, 6.0 | 4.30 | 4, кроп |
| 7.07, 8.05 | 4.30 | **2** (8160×6144 QUADCFA remosaic, 24 fps) |
| ≥ 8.525 (10, 15, 20, 30) | 4.30 | **9** (ISZ, 4080×3072 QUADCFA) |

- Оптика: `target = 3.4 + (UI − 3.2)·0.9/1.1` — ровно карта optUi → optReal.
- Режим 2 включается между 6.0 и 7.07, режим 9 — на ≈8.5× (на 8.49 ещё 4). Кнопка стока 8.6 = 2 × 4.3.
- Режимы переключает HAL сам (сток `current_mode` не шлёт).

## Что сделано в SCAMERA (P41b)

- Модуль тела на 17U открывается через логическую камеру, которая держит тел и объявляет optReal/optUi (камера 0):
  `XiaomiTeleZoom.logicalRoute`, `CaptureController.routeXiaomiTele`. `Settings.mCameraID` не меняется.
- Видоискатель — логический поток (оптика, кроп и режимы HAL, как у стока). RAW / YUV-ридеры — физические потоки камеры 4.
- Запрос: `zoomRatio` = `userZoomRatio` = цифра шкалы (до 17.2× с ExtendedMaxZoom), `current_mode` не шлём,
  `teleFallback.isDisable` = 1 (RAW — тела, видоискатель не должен уходить на главную).
- Сессия: op mode 0x9002 (как сток), ключи ExtendedMaxZoom = 1, EnableInsensorZoom = 1. **clientName / пакет не
  подменяются.**
- Кроп снимка = мм шкалы / (положение стекла по `opticalZoomCurrentRatio` × 2 в режиме 9). Если HAL стекло не двигает,
  кадр всё равно правильный — кроп считается от реального положения.
- Отказы: сессия не собралась / нет кадров → обычная сессия (op mode 0) → камера 4 одна (как раньше, режим кропа) до
  перезапуска приложения. Камера 0 не открылась или ошибка устройства → сразу камера 4.
- dev-ключи `scam_dev.txt`: `xiaomi_logical 0` (камера 4 одна), `xiaomi_opmode N` (0 = обычная сессия),
  `xiaomi_tele_fallback 1` (разрешить HAL уход на главную в темноте).
- Принудительный ISZ на камере 4 (`xiaomi_isz 1`, 150 мм) не трогали.

## Что смотреть в логе владельца (тег XiaomiTeleZoom / CaptureController)

- `opens through logical camera 0` и `P41b: session on logical camera 0 … operation mode 0x9002`;
- `logical camera session failed … a regular session next` / `logical camera route off` — ступени отказа;
- `the lens follows on the logical camera` или `the lens stays at … on the logical camera too` — главный ответ;
- `sensor mode reported N on the logical camera` — режимы 2 / 9 от HAL;
- `HAL xiaomi.thirdparty.isThirdParty = …`, `the HAL clamps zoomRatio`.

## Открытый вопрос владельцу

На логической камере HAL сам включает режим 2 (полный 50 Мп, 24 fps) на ~7× и режим 9 на ~8.5×. Мы режимы не
форсируем, но и не запрещаем. Это допустимо, или режим 2 надо как-то избегать?
