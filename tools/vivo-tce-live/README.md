# SCAMERA TCE live trace v4

This is a diagnostic instrument, **not a working TCE port or an APK update**.
It observes the stock implementation without substituting arguments, return
values or processing settings. Frida inserts temporary in-memory interception
hooks; this changes timing and phone stability has not been tested here.

## Что делать на Vivo X200 Ultra

1. Сохранить `SCAMERA-TCE-Live-v4.tar.gz` в Download. Закрыть стоковую камеру.
2. Выполнить в Termux:

```sh
su -c 'scamera_tce_dir=$(mktemp -d /data/local/tmp/scamera-tce.XXXXXX) && tar -xzf /sdcard/Download/SCAMERA-TCE-Live-v4.tar.gz -C "$scamera_tce_dir" && sh "$scamera_tce_dir/run.sh"'
```

3. Только после **ПОДКЛЮЧЕНО** открыть стоковую камеру и сделать один обычный
   снимок 1× в режиме Фото. Вернуться в Termux.
4. Прислать `scamera-tce-trace.*.tar.gz` из Download/SCAMERA. Если подключение
   не удалось, прислать тот же архив; самостоятельно менять SELinux не нужно.

Сборщик останавливается после первого завершённого Process или через 250 секунд
(ожидание первого вызова — до 90 секунд, общий лимит агента — 240 секунд). Он не перезапускает сервис, не вызывает затвор,
не меняет системные свойства, разрешения камеры или SELinux. Завершается только
его собственный процесс frida-inject. В архив включается RGB до и после TCE, поэтому выбирай обычную тестовую сцену.
Нужно минимум 1 ГиБ свободного места. Чтение больших буферов замедляет этот снимок.
Ранее включённый дамп Vivo сборщик не отключает: его значение записывается в отчёт.

## What it captures

- The exact 0x4c8-byte Create argument, thirteen bounded config/path strings (including GPU binary path),
  returned handle and call association; at most eight creations.
- First Process input (0x6d0), known output prefix (0x2e0), before/after and status.
- First 32 SetParam keys and payload addresses; key 4 copies exactly 4 bytes, key 8 exactly 0x55 bytes. Other payloads are not dereferenced.
- Build fingerprint, process domain/UID, existing dump property, donor SHA256.

A `process_leave` with status 0 only means the **stock** call returned success;
it does not establish that SCAMERA can reproduce it. If Create predates attach,
`createId` is null and the trace is explicitly incomplete. v4 copies the input
and successful output RGB16 planes, color LUT and six bounded face arrays.
It does not copy segmentation masks or unverified opaque pointer graphs.
RGB must match the observed packed 0x1004 layout (6 bytes per pixel, stride
width*6, scanline height), maximum 96 MiB per plane. LUT edge is bounded to
65 and the stored count must equal 3*edge^3; 65 is a collector limit, not a
claim about the vendor API. Faces are bounded to the recovered capacity 40.
The total payload budget is 192 MiB. Memory is read in 16 KiB chunks through
the existing injector stdout channel, without target filesystem permissions.
The trace can exceed 300 MB before compression. This captures the observed
CPU mapping; GPU/CPU cache coherence still requires device validation.
`extract_payloads.py trace.log new-directory` streams the payloads into files,
checks ordering/extents/completeness, refuses unsafe names and existing output,
and generates SHA256 metadata. Incomplete captures are rejected and staged
files removed. Extraction needs Python on the host, not on the phone.
`native_call_start` separates input-copy delay from measured Process duration.
This trace cannot be replayed: addresses belong to the phone's current process.

The shell requires the donor SHA256 documented in `../../docs/vivo-tce-boundary.md`.
The agent additionally verifies architecture, module path and four exact exported
function offsets. There are no pattern-scan or guessed-address fallbacks.
Errors and partially observed calls remain in the trace, rather than a fake success.

## Host checks

`node tools/vivo-tce-live/check_trace.js` runs the real agent in a mock Frida API:
structure extents, handle association, concurrent calls, unreadable memory,
destroy/reuse, capture limits, timeout and wrong-build/architecture refusal.
`check_runner.py` checks report packaging and cleanup on success and attach failure
in a fake Android environment. `sh -n tools/vivo-tce-live/run.sh` checks shell syntax. These are **not** Android
instrumentation tests. `analyze_trace.py trace.log` decodes known fields on a host.

## Runtime provenance

Based on official Frida release 17.18.0, Android arm64 `frida-inject` (no server required):
https://github.com/frida/frida/releases/tag/17.18.0

Compressed upstream SHA256:
`a72de74276d914f6769b8b85f8dd287cbafa4527c42ae1c8dd87b0d23d261391`.
Uncompressed executable SHA256:
`d8ce6fe18db97594d1c7a43be4f8fb405fc76f6a01470df34504bca6461328d0`.

API documentation: https://frida.re/docs/javascript-api/
CLI source: https://github.com/frida/frida-core/blob/17.18.0/inject/inject.vala
Frida source and licensing: https://github.com/frida/frida-core/tree/17.18.0

## Important: injector modification

The bundled executable is **not byte-identical to upstream Frida**. Upstream
`inject/inject-glue.c` invokes `frida_selinux_patch_policy()` during initialization.
Its implementation adds policy rules and can temporarily switch enforcing off
on a fallback path. This is inappropriate for a collector described as leaving
SELinux policy intact.

`prepare_injector.py` validates both upstream archive/executable hashes, then
replaces only the ARM64 tail-branch at file/VA `0x7d8dec` with `RET`.
The original startup at `0x7d8dd8` calls runtime init `0x32d90a4`, restores FP/LR,
then branches to the policy function `0x32d99d0`. The latter references
`/sys/fs/selinux/policy` at `0x32d9a04..0x32d9a08`. Runtime initialization remains.
This modifies only the supplied helper, never a phone library or firmware.

Bundled modified helper SHA256:
`4952c58fa7f3caca7816e5e8a2b8be9c2b88b95a28110296504007b661c2fc4f`.

`check_injector_patch.py` executes original and patched ARM64 startup in Unicorn
with runtime initialization stubbed: the original enters the policy function;
the modified version does not, and preserves stack/FP/LR. This establishes the
startup change, not injection compatibility on this phone. If existing policy
blocks attachment, the collector reports failure instead of installing rules.

Sources: https://github.com/frida/frida-core/blob/17.18.0/inject/inject-glue.c
and https://github.com/frida/frida-core/blob/17.18.0/lib/selinux/patch.c
