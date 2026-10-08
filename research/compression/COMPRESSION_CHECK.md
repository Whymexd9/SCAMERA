# P61 — проверка сжатия всех форматов (2026-10-08)

Владелец: «на предыдущих версиях разницы не было» при смене качества.

## Причина (уже исправлена аудитом настроек, сборка 30679+)
До аудита ключи качества (`pref_jpeg_quality`, `pref_heic_quality`, `pref_webp_quality`, `pref_avif_*`) были настройками
модуля: профиль модуля (`ModuleProfiles`) сохранял и восстанавливал их при каждом переключении объектива, и изменение
в настройках затиралось старым значением активного модуля. Теперь эти префиксы глобальные (`ModuleProfiles.GLOBAL_PREFIXES`)
и `SettingsMigration` убирает их из профилей модулей.

## Проверка на OPPO Find X7 Ultra (локальная сборка 27103, одна и та же сцена)
| формат | настройка | файл |
|---|---|---|
| JPEG (jpegli 4:4:4) | качество 70 | 1.01 МБ |
| JPEG | качество 98 | 6.85 МБ |
| HEIC 10 бит (c2.qti.hevc.encoder.cq) | качество 30 → CQ 29 | 1.25 МБ |
| HEIC 10 бит | качество 90 → CQ 90 | 2.72 МБ |

Цепочка настройка → кодировщик прослежена в коде для всех форматов: JPEG (`getJpegQuality` → jpegli / Bitmap.compress),
WebP (`getWebpQuality`, `isWebpLossless` → `webpEncoding`: WEBP_LOSSY q / WEBP_LOSSLESS effort 75, до Android 11 lossy ≤ 99),
HEIC 8 бит (`HeifWriter.setQuality`), HEIC 10 бит (CQ из качества, иначе VBR), AVIF (`getAvifOptions` → JNI: q, lossless,
глубина, цветность, скорость).

## Изменение
`PhotoOutput`: после каждого файла строка `encode params <ФОРМАТ>: <параметры>, <байты> bytes` — по логу видно, с какими
параметрами реально записан файл (JPEG quality, WEBP_LOSSY/WEBP_LOSSLESS, HEIC quality, AVIF описание опций).
Пиксели и файлы не меняются.

## Что можно проверить ещё на телефоне
WebP 40 vs 90 и lossless; AVIF q 40 vs 90, 4:2:0 vs 4:4:4: размер файла должен заметно меняться, строки `encode params`.
