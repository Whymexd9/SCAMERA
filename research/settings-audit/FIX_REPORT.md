# Исправление настроек по аудиту — ветка `settings-fix` (от main 5c8873c), 08.10.2026

Основа: `research/settings-audit/SETTINGS_AUDIT.md` (снимок e192250) и ответы владельца от 08.10.2026.
Не запушено. Телефоны не трогались. Сокращения: SA = `settings/SettingsAvailability.java`, PK = `settings/PreferenceKeys.java`.

## Что сделано (по коммитам)

| Коммит | Пункт | Что |
|---|---|---|
| 3c2c023 | BROKEN | «Фильтр Байера»: убраны MONO и QUAD (en+ru); сохранённые 4 / −2 → −1 в основных настройках, всех профилях модулей, базовом профиле и восстановленных конфигах; `getCFAValue` считает всё вне 0..3 «Авто». |
| 3c3f50a | BROKEN H1 | Ползунки пишут столько знаков, сколько нужно шагу и минимуму (не меньше 2): 0.0005, 1.414, 0.001 сохраняются. Разовая миграция `bento_trigger` "0.00" → "0.0005", `lut_sigma` "1.41" → "1.414" (маркер `pref_lmc_hybrid_precision_rev`, все профили). |
| 718281e | DEAD | Удалены «Максимальное ISO», «Максимальная выдержка», «Баланс выдержки и ISO» модулей вместе с `applyExposureBalance` и др.; сохранённые `pref_sensorconfig_*_exposurebalance*` удаляются. |
| 08e2a65 | DEAD | Удалён «Формат превью». См. «Отклонения» про `isDualSession`. |
| 2f98fbe | DEAD | Удалена «Системная библиотека Vivo — диагностика», `VivoRemosaicAvailability`, цель CMake `vivoRemosaicProbe` и `vivo-remosaic-probe.cpp`. |
| 901726b | S2 | Геттеры через `Key` без своего default берут `android:defaultValue` из `preferences.xml` (`XmlDefaults`, читается один раз), а не 0/false: АФ, CFA, звуки, скругление, водяной знак, пик фокуса, цветовой метод, тема галереи, все `pref_sharp_*`, ремозаик. |
| fe967fc | S1 (владелец) | Один список общих настроек `ModuleProfiles.GLOBAL_KEYS` / `GLOBAL_PREFIXES` (isLocal и JSON `addIds`; `COMMON_KEYS` и мёртвый `saveJsonForCamera` удалены): звуки, сетка, формат фото и все опции форматов (`pref_photo_*`, `pref_jpeg_*`, `pref_heic_*`, `pref_webp_*`, `pref_avif_*`, режим RAW, Ultra HDR), водяной знак и подпись, Root, 4 ключа спуфа, лица, следящий АФ, иконка/тема/градиент, частота мерцания, `pref_merge_route`. Миграция: значение остаётся в основных настройках, из профилей модулей и базового профиля ключи удаляются. |
| 36b8642 | S4 (владелец) | Из `DeviceDefaults` убран блок v1 (SCAM HDR для OPPO, 35 значений) и тестовый файл `force-oppo-defaults`; v2 (RAW10 X8U) и v3 (спуф X7U + ccm_sat 1.1) остались, VERSION = 3. Спуф пишется только в основные настройки. |
| f014132 | владелец | Удалены «О нас», «Разработчики», «Группа Telegram», «Поддерживаемые устройства», «Подгрузить конфигурации», сетевые загрузчики `SupportedDevice`/`Specific`/`SensorSpecifics`, `HttpLoader`, `SupportedList.txt`. Строки «SCAMERA», «Версия», «Это устройство» перенесены в «Система». Ранее скачанный кэш `sensor_specific_val` и список устройств удаляются при запуске: работают только локальный файл и встроенные assets. |
| 9bd34fd | владелец | SCAM HDR: «Сохранять этапы обработки» по умолчанию выкл., планировщик — SCAMERA (XML и геттеры). Разовая миграция сохранённых старых default (true → false, stock → scamera), маркер `pref_vivo_nice_defaults_rev`. |
| 5b7f8f4 | H3, S3 | Правила SA с причиной: резкость (ARK / RawTherapee / SCAM / защита теней / «Деталь Sabre» / сила RT), шумодав (GCam `dn_*` / NLM `post_*`), разрешение (даунсемплер 12/16/20 МП, `dn_chroma_2x_keep` на сетке 2×), `pref_nice_*` по маршруту, планировщик (только Root + vivo X200 Ultra), «Цветовой метод» (OPPO X7U, vivo). Факты устройства — `DeviceAvailability`. Без 8 Elite скрыты проверки SCAM HDR/нейроремозаика и страница диагностики, плитка маршрута не в плитках по умолчанию. |
| 11728b0 | тексты | H2, антибандинг, ZSL, кривые, модель шума, иконка галереи, «Тема галереи», HexQuad, русские заголовки `@Tunable`, «Шумодав NLM/GCam: яркость/цвет». |
| dbb0a00 | H4, UI | 6 ползунков-перечислений → списки с названиями (значения "0", "1"… те же; миграция "1.00"/float → "1" во всех профилях); радиусы SCAM HDR — целые; «Резкость RawTherapee» перенесена под «Резкость Hybrid» в «Обработка ArkCore → Резкость», «Цвет и резкость» → «Цвет». |
| 4c52e7c | S5 | Удалены мёртвые геттеры и `Key` удалённых настроек PhotonCamera, их чтение в `api/Settings`, границы SNR удалённых ключей, поле SA `calibratedSensor`, дублирующая логика `updateQuad/HexQuadDenoiseControls`. |
| 57d7e9c, f95e754 | — | Документ про OPPO defaults; `check_vivo_hexquad_controls.py` проверяет правила в SA. |

## Миграции (все идут по основным настройкам, всем `module_profile_v2_*`, базовому профилю и при восстановлении конфига)
- `removeObsolete` (идемпотентно): CFA 4/−2 → −1; удалённые ключи (`pref_preview_format_key`, ключи S5, `pref_sensorconfig_*_exposurebalance*`); целые значения списков/радиусов.
- `removeObsolete(Context, main)`: общие ключи S1 удаляются из профилей модулей и базового.
- `migrateLmcHybrid` (разовые маркеры): точность ползунков; default SCAM HDR.

## Тесты и проверки
- Новый `SettingsAuditFixTest` (16 тестов) добавлен в список CI.
- Обновлены, потому что фиксировали старое поведение: `SettingsMenuTest` (порядок строк сенсора без лимитов; копирование модулей по строке «Цвет» вместо переехавшей USM), `DeviceDefaultsTest` (утверждения v1 «planner scamera» → «нет значений SCAM HDR» + тест спуфа), `ShadeCatalogTest` («Замер» — список), `ShadeUiTest` (новое имя строки NLM), `HybridSettingsTest` (в ArkCore допускается переехавшая страница RawTherapee), `tools/java/SettingsModelCheck.java` (строки 47 и 62: USM по умолчанию не работает при резкости ARK; добавлены проверки всех новых правил), `check_ui_language.py` (allow-list описаний удалённых полей), `check_nice_legacy_isolation.py` (заглушка CC), `check_vivo_hexquad_controls.py` (правила теперь в SA).
- Итог: полный список CI — 68 классов, 452 теста, 0 падений (было 67 / 435). `check_settings_model.py`, `check_ui_language.py`, `check_vivo_hexquad_controls.py`, `check_nice_legacy_isolation.py` — PASS. `assembleDebug` (-x buildVivoNeuralWorker) — BUILD SUCCESSFUL, в APK нет `libvivoRemosaicProbe.so`.

## Отклонения и что не сделано
- **`isDualSession` бывает true** (аудит ошибся): `CameraFragment` берёт его из `isDualSessionSupported` (assets `specific/redmi/lime` = true, локальный `DeviceSpecific.txt`). Поэтому ридер превью, его слушатель и ветки двойной сессии оставлены; убраны только настройка и лишний поток в обычной сессии (сессия = как при старом default «Нет»), формат ридера — константа JPEG.
- «Цветовой метод»: причина по устройству (OPPO PHY110 с настроенной матрицей; vivo, пока `isp_ccm` не выключен в nice_dev.txt), не по фактическому кадру.
- Планировщик неактивен (с причиной) везде, где стоковый AE не может работать, в том числе на X200 Ultra без Root.
- Не входят в список владельца и остались по модулю: 16:9, «Сжатие DNG без потерь», режим АФ, пик фокуса, скругление, данные АФ.
- Скрытие экрана SCAM HDR и строки зума Xiaomi раньше не держалось (проход доступности снова показывал строки) — исправлено через `SettingsAvailability.hidden`.
- `hybridDownsamplerIndex` оставлен (используется тестом); S6 (пояснение двух множителей модели шума) не делался — в задаче его нет.
- `tools/check_nice_reference_metadata.py` локально не проходит из-за отсутствующей нативной утилиты Windows (файлы, которые он проверяет, не менялись); в CI на Linux не затронут.
