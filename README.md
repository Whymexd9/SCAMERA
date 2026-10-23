# SCAMERA

RAW-камера для Android, которая склеивает несколько кадров в одно чистое и детальное фото.

## Чем хороша

- **Склейка SHDR.** Серия RAW-кадров с момента нажатия (без задержки затвора) плюс тёмные кадры для светов и светлые для
  теней. Кадры выравниваются и склеиваются на видеокарте: меньше шума, больше деталей, света и тени не теряются, движущееся
  почти не двоится.
- **Тон Why, шумодав и резкость** — естественный вид снимка с мягкими светами.
- **Режим «Движение»** — короткая выдержка и быстрая серия для детей, животных и спорта.
- **Каждый объектив — свой модуль:** зум сенсора и полное разрешение (50 / 200 Мп) на поддерживаемых vivo, OPPO и Xiaomi,
  свои настройки для каждой камеры.
- **Стиль:** LUT с переключением свайпом по кадру, плёночная симуляция, фильтры на объектив, микшер и цветокоррекция,
  тепло и оттенок прямо в видоискателе.
- **Форматы:** JPEG, 10-битный HEIC, AVIF, WebP, RAW (DNG), HDR-фото (Ultra HDR), Display P3.
- **Ручной режим**, своя галерея, понятные настройки: долгое нажатие на любой пункт объясняет, что он делает.

## About

An Android RAW camera that merges a burst of frames into one clean, detailed photo: a zero-shutter-lag RAW burst with
highlight and shadow frames merged on the GPU, its own tone, denoise and sharpening, a Motion mode, per-lens modules with
in-sensor zoom and 50 / 200 MP full resolution, LUTs and film simulation, JPEG / HEIC / AVIF / WebP / DNG and Ultra HDR.

## Building

The APK is built by GitHub Actions (`.github/workflows/build-test-apk.yml`): unit tests, native checks, `assembleDebug`, then
`tools/package_scam_neural.py` adds the worker and the model bundle and signs the APK. The release key is not in the repository:
the workflow reads it from the `SCAMERA_SIGNING_KEYSTORE` / `SCAMERA_SIGNING_PASSWORD` secrets (`key/README.md`).

## License

SCAMERA is free software, licensed under the GNU General Public License v3.0 (`LICENSE`).

It started as a fork of PhotonCamera (Copyright (C) 2020-2022 Eszdman and contributors, GPL-3.0); the files that still carry
PhotonCamera code keep their copyright notices. Third-party components are listed in `THIRD_PARTY_NOTICES.md`.
