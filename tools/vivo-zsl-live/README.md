# SCAMERA ZSL live v1

Диагностика очереди и брекета стоковой камеры PD2454. Это не обновление APK.
Запускать после закрытия стоковой камеры, затем сделать один снимок 1× в режиме
Фото по сообщению ПОДКЛЮЧЕНО. Сохранить архив в Download и выполнить:

```sh
su -c 'scamera_zsl_dir=$(mktemp -d /data/local/tmp/scamera-zsl.XXXXXX) && tar -xzf /sdcard/Download/SCAMERA-ZSL-Live-v1.tar.gz -C "$scamera_zsl_dir" && sh "$scamera_zsl_dir/run.sh"'
```

Дождаться ГОТОВО и прислать Download/SCAMERA/scamera-zsl-trace.*.tar.gz.
Если подключение не удалось — прислать тот же архив, не менять SELinux.
Сбор завершается через 10 секунд после первого плана NICE либо через 100 секунд.

Сборщик читает параметры и метаданные, без пикселей изображений. Не меняет
экспозицию, системные свойства, SELinux или файлы прошивки, не перезапускает
сервисы и не нажимает затвор. Временные Frida hooks меняют тайминг исполнения;
совместимость нового набора перехватов проверена только на host-макетах.

## Проверяемые границы

- `libvivo.vas.adapter.vcf.so`, SHA256
  `f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901`:
  getNiceHdrCaptureControlInfo at 0x101f48, arguments before/after.
  PreviewToQueryParams prefix 0x3e48 includes the last verified +0x3e44 field;
  CaptureControl prefix 0xb34, QueryParams prefix 0x48, QueryToShot RAW slice
  +0x3890/320 bytes, owner image-echo byte +0x44f5. Opaque pointers are not followed.
- `libvcf_session.so`, SHA256
  `93a3149a70fea5f177bb0542278c47ec232fc477aa96cb7a63534b1f6b5f8dad`:
  preparePastAndNextBuffers at 0x12b6f8; captures requested past/future counts,
  request IDs, timestamp, catch mode and raw 48-byte queue records before/after.
  Vector bounds are checked, with at most 256 entries. No image pointer dereference.
- At 0x1417e8, the already-built ready-candidate vector at SP+0x200 contains
  40-byte records: exposure ns, timestamp, ISO, motion word, short exposure/gain,
  an opaque word, and settled byte. Stack/field offsets are also used by the
  existing original ARM64 oracle in check_vivo_vcf_ready_selection.py.
  This single mid-function hook has no onLeave handler.

At most one NICE query and 63 associated queue calls are observed, in one process.
Thread IDs/call IDs are retained; no cross-thread association is invented.
The return value of the NICE function is recorded as raw bits, not a success code.
The full scene/AE solver, model dispatch and Camera2 integration remain separate
implementation work. This does not establish stock capture parity in SCAMERA.

## Injector provenance

Same previously supplied Frida 17.18.0 arm64 injector as TCE Live v4.
Bundled SHA256 `4952c58fa7f3caca7816e5e8a2b8be9c2b88b95a28110296504007b661c2fc4f`.
Only the automatic SELinux-patching tail branch at 0x7d8dec was replaced with
RET; normal runtime initialization remains. Firmware is not patched.
Official source: https://github.com/frida/frida-core/tree/17.18.0
COPYING and COPYING.LIB are included. No Frida server or network is needed.

Host checks: node check_trace.js and sh -n run.sh. Actual phone recording remains
unverified until its output is received. No CameraService TagMonitor is used.
