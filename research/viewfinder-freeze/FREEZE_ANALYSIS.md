# P44: viewfinder freeze at the shutter press

Owner: «сделай так чтобы видоискатель у нас не замирал после спуска затвора».

## Shot path (CaptureController, the only photo route)

`captureStillPicture` accepts only the ZSL route (Hybrid / SCAM HDR, `isZslMode()`; anything else throws), so every shot is
NICE-routed (`mNiceRouted = true`): the repeating preview is never stopped with `stopRepeating` for the series. The
`stopRepeating + abortCaptures` branch for "routes without ZSL" is unreachable today.

1. Shutter: the ZSL cutoff is the newest preview result; N frames come from the ring (before the press).
2. The post-shutter series (L / S / ES, 5 to 7 requests) is built from TEMPLATE_STILL_CAPTURE with **only the RAW reader
   as target**: if the preview surface were a target, the dark ES and bright L frames would flash on the viewfinder
   (seen before, see the comment at the target setup).
3. If `fast_capture` is on and FlushLossStats allows it: `flushDevice()` (reflective `CameraDeviceImpl.flush()`; it also
   clears the repeating request) drops every request in flight, preview included.
4. `captureBurst(series)`, then `queueNiceAeRestore()` (one preview-builder frame, AE OFF at the pre-shot exposure), then
   `setRepeatingRequest(preview)` again ("preview repeating restarted behind the series").
5. After the series (finishNiceShot) the P38 re-arm: on vivo mode 3 = wait for the first repeating frame, flush again,
   restart the repeating request.

## Where the time goes (sensor timestamps)

Gap = last preview frame exposed before the series to the first preview frame after it. Split into A = last preview ->
first series frame, B = the series itself (never shown), C = last series frame start -> next preview frame,
D = a second gap later (re-arm flush). "start" = first series frame start after the submit (NICE_TIMELINE dtMs).

| Device / camera | shot | A | B | C | gap | D | start |
|---|---|---|---|---|---|---|---|
| vivo X200 Ultra tele (cam 5), log (15) | 14:01:40 | 469 | 163 (5 fr) | 358 | 990 | 163 | 363 |
| | 14:02:28 | 379 | 166 | 265 | 810 | 199 | 297 |
| | 14:17:24 | 449 | 166 | 359 | 973 | 199 | 363 |
| | 14:18:09 | 357 | 166 | 246 | 769 | 199 | 268 |
| OPPO Find X7 Ultra (cam 2), speed2/ab2 | 22:48:59 | 167 | 260 (7 fr) | 75 | 502 | none | 85 |
| | 22:49:25 | 167 | 185 (7 fr) | 50 | 402 | none | 89 |
| Pixel 7 (cam 2), p7.txt | 14:39:17 | 334 | 133 (5 fr) | 33 | 500 | none | 275 |
| | 14:40:08 | 300 | 133 (5 fr) | 33 | 467 | none | 225 |

(0-5 preview frames in flight complete right after the flush and are shown; A starts at the last of them.
The X300 Ultra trace in P38 shows ~300 ms; its log is not in the workspace.)

Reading:
- **A is the flush**: the frames in flight are dropped (preview included) and the HAL restarts its pipeline on the first
  request it gets, the first series request (RAW only). This is the latency the flush buys; without the flush the preview
  keeps running until the series starts (A ~ one frame) but the series starts later (+0.17-0.25 s, the reason for the flush).
- **B cannot be shown** (exposures differ by up to 4 EV). Interleaving preview frames between the series frames would need
  per-request callbacks (the series is one sequence for the saver) and would spread the series in time; not done.
- **C is one frame on OPPO / Pixel** (the AE restore frame follows the series directly) but **245-360 ms on the vivo tele**:
  frame numbers are consecutive (90 series, 91 AE restore), so the HAL itself pauses before the first request that has the
  preview stream again.
- **D (vivo only) is ours**: the P38 re-arm flush after the series, 163-199 ms.
- vivo restart cost depends on the first request: after the shot flush (first request = RAW-only series) the first frame
  starts 268-363 ms after the submit; after the re-arm flush (first request = preview + RAW) the next preview frame comes
  ~100-170 ms after the flush. The vivo HAL restarts faster, and keeps the preview stream, when the pipeline restarts on a
  preview request. This is the case for a lead preview frame (below); it is a vendor HAL behaviour not verified on the phone.

## Change (P44)

- `PreviewGapMeter`: one line per shot, tag **PREVIEW_GAP**, from the preview results' sensor timestamps (all routes, always
  on): gap, A / B / C, later max gap (D), preview requests failed (flushed), first series frame after the submit (latency),
  and the switches used (flush, lead, rearm, aeRestore).
- nice_dev **`preview_lead N`** (0..3, default 0): N frames of the normal preview request queued right after the flush, ahead
  of the series (only when the queue was flushed). The viewfinder gets a frame as soon as the HAL restarted, the pipeline
  restarts on a preview request, and the P38 re-arm default then becomes 1 (re-send the repeating request, no second flush).
  Cost on a HAL that restarts as fast on a RAW request (OPPO: series start 85-89 ms): one preview frame (~33 ms) of start
  latency. Expected on the vivo tele from the numbers above: shorter series start, C ~ one frame, no D.
- Default unchanged (`preview_lead 0`): the shot-start latency must not get worse without a phone measurement.

## What to measure on the phone

`adb logcat -s PREVIEW_GAP NICE_TIMELINE` (or PhotonLog, lines PREVIEW_GAP), 5 shots each, hand-held, same scene:

1. default;
2. `preview_lead 1` (nice_dev.txt in Android/data/org.codeaurora.snapcam/files/);
3. `hybrid_fast_capture 0` (no flush: A ~ one frame, latency up);
4. vivo only: `ae_restore 2` and `stab_rearm 1` with `preview_lead 0` (does C / D depend on the AE OFF frame / the re-arm).

Compare `gap=`, `later max gap`, and `first series frame +X ms after the submit` (latency). Keep `preview_lead 1` as the
default for a HAL where the gap drops and the latency does not rise.
