# Plain (non-remosaic) hybrid shot speed: time split and speed-ups (P48, speed part of P53)

Branch from 9f6a459. Nothing was run on a phone for this note; the numbers come from the owner's logs:

- vivo X200 Ultra, `SCAMERA-debug.log (15).txt`, shot 14:01:40 (camera 5, 4080x3072 RAW, 35 frames = 30 N + 3 L + 2 ES,
  output mode 20: Sabre 2x grid 8160x6144, final 5184x3904 after the pipeline, WebP). The Tetra remosaic shots of the
  same log (14:02:28, 14:17:24, 14:18:09) merge 12 MP (native mosaic, 4080x3072).
- Pixel 7 (Tensor G2, Mali-G710, 8 GB), `p7.txt` (14:37-14:53) and `log-2026-10-08_P7.txt` (15:28), 1x output
  4080x3072 (12.5 MP), processing diagnostics on for most shots.

## 1. Where the time goes

### vivo X200 Ultra, plain Bayer, 2x grid (SHOT TIMELINE ms from the shutter)

| Stage | Marks | ms | Remosaic shot 14:02:28 |
|---|---|---|---|
| Capture (SHUTTER -> complete matched) | 0 -> 567 | 567 | 525 |
| Java prep (HYBRID JAVA PREP) | 567 -> 663 | 95 | 46 |
| Worker start, CRE global align (STAGES align=273) | 663 -> ~1003 | ~340 | 1363 to the GPU context (mosaic prep, align 299, mask 2) |
| Gain check, Shasta, Bento mask (mask=35) | -> 1049 | ~45 | (above) |
| **F6 local align, whole field before the merge** ("field not streamed (469 MB of gray images, MemAvailable 1836 MB)": 469 > 1836/4) | 1049 -> 2611 | **1548** | 0 on the path (streamed, field done 341 ms alongside) |
| RAW CA fit + GPU context | -> 2622 | ~10 | ~5 |
| **GPU merge** (STAGES merge=9421; + outliers, rim, CA resample 3 ms after the merge, effective map 214, clip flags 69) | 2622 -> 12330 | **9708** | 7820 |
| Worker: NICE RGB stats 62 + write() of 600 MB 310 | 12330 -> 12703 | 373 | 661 |
| Client: exit wait + copy of the result (HEX CLIENT OUTPUT 422) | 12703 -> 13256 | 553 | 157 |
| Client -> PostPipeline start | -> 13410 | 154 | 34 |
| PostPipeline: clip statistics (channelClip, CPU, one core) | | 482 | 0 (no hybrid clip) |
| PostPipeline: **RGB32F upload** (nicergb input upload=2240) | | **2240** | 312 |
| PostPipeline: decimate 239 + free 38 | | 277 | 52 |
| PostPipeline: rest of VivoNiceRgb (lens shading, chroma blocks) | | ~140 | ~120 |
| PostPipeline: NiceDenoise 495, ArkStats 524, ArkFusion 26, ArkCombine 10, FinalResize 12, Rotate 1 | | 1068 | 642 |
| PostPipeline: GPU drain + tile readback into the bitmap (Rotate -> runAll end) | | 1756 | 193 |
| PostPipeline total (post_start -> post_readback) | 13410 -> 19376 | 5966 | 1324 |
| Encode: WebP 5184x3904 (Bitmap.compress) | 19436 -> 23134 | **3697** | 2249 (12 MP) |
| **Press -> saved** | | **23134** | 14188 |

The plain shot is slower than the remosaic shot for three structural reasons, not for a slower plain path as such:

1. Output mode 20 runs the plain Bayer stream on the **Sabre 2x grid** (8160x6144 = 50 MP): 4x the pixels of the
   remosaic shot in the merge (9.7 vs 7.8 s), in the post pipeline (6.0 vs 1.3 s) and in the result hand-off (0.9 vs 0.8 s);
   the WebP is 20 MP against 12 MP (3.7 vs 2.2 s).
2. **F6 is not streamed**: 34 donors x 12.5 MB of gray = 469 MB, just above MemAvailable / 4 = 459 MB, so the whole field is
   computed before the merge (1.5 s on the path). The remosaic shots stream it (0 ms on the path).
3. The post input stage costs **3 s at 50 MP** (statistics 0.48, upload 2.24, decimate 0.24): the RGB32F upload is a CPU
   repack in the Adreno driver (270 MB/s).

### Pixel 7 (P53), 1x 12.5 MP

| Shot | Capture | Java prep (header) | Align | F6 | Merge | Client output | Post | Encode | Total |
|---|---|---|---|---|---|---|---|---|---|
| 14:37:50 JPEG | 1444 | 958 (301, diagnostics) | 1032 | 3632 not streamed (414 MB > 305/4) | 2996 | 375 | 2587 | 490 | 14684 |
| 14:39:17 JPEG | 762 | 2085 (1933, diagnostics) | 887 | streamed (field done 2852 alongside) | 3023 | 884 (diagnostics) | 4289 | 803 | 13544 |
| 14:40:08 RAW only | 551 | 1294 (1231) | 849 | 5954 not streamed (456 > 560/4) | 8379 | 2136 (diagnostics) | - | - | 21711 |
| 14:51:33 RAW only | 569 | 2475 (2334) | 1746 | 16554 not streamed (497 > 1149/4; gray 7832 ms thread sum: heat + memory pressure) | 6740 | 8484 (diagnostics zip) | - | - | 41468 |
| 15:28:30 JPEG (10 frames) | 570 | 1266 (1138) | 269 | 877 not streamed (193 > 619/4) | 1239 | 1183 | 4939 | 447 | 11509 |

On the Pixel the time is F6 not streamed (0.9-16.5 s on the path), processing diagnostics (header 0.3-2.3 s for the NICE
thumbnails, client output 1-8.5 s for the NICE zip), a Mali merge of 3-8 s and a post pipeline of 2.6-4.9 s.

## 2. Speed-ups

Bit-exact means: the same worker output bytes (md5 of the replay output) and the same post-pipeline floats.

| # | Change | Where | Gain (vivo 2x plain) | Gain elsewhere | Bit-exact | State |
|---|---|---|---|---|---|---|
| 1 | **F6 streamed with windowed gray rows** when the whole gray images do not fit: phase 1 builds L1 from two gray rows at a time; each band computes just the gray rows its tile passes can read; rows behind the bands go to a spare list. ~140 MB instead of 470 MB | worker (c4b75e7) | **-1.5 s** | Pixel -0.9..-16 s (every not-streamed shot of the logs fits except MemAvailable 305 / 560 MB) | yes (same functions on the same rows; host check item 4/5) | done, default |
| 2 | Result written through a shared mapping of the memfd, filled on all cores (fallocate + mmap) | worker (8249ee0) | -0.25 s | 1x: -0.05 s | yes (same bytes) | done, default |
| 3 | Result (and trailers) read from the memfd by parallel pread | app (d10f17c) | -0.3 s | 1x: -0.07 s; Pixel similar | yes (same bytes, unit test) | done, default |
| 4 | Clip statistics (parallel) and decimation on a helper thread beside the upload | app (d10f17c) | -0.72 s | 1x: -0.1..-0.15 s | yes (exact sums, unit test) | done, default |
| 5 | RGB uploaded as RGBA32F row bands (alpha 1.0), next band repacked during the current upload | app (d10f17c) | **-1.5..-2.0 s expected, not measured** | OPPO 1x: -0.15..-0.2 s expected | yes (texelFetch .rgb; repack unit-tested) | done, default on Adreno only; nice_dev `rgba_upload 0/1` |
| 6 | Read the result when "NICE CAPTURE OK" arrives instead of after the process exit | app | -0.05..-0.13 s | same | yes | not done (restructures the wait / exit-code checks) |
| 7 | Zero-copy hand-off: map the memfd as the result buffer (W1.10 b) | app + allocator.cpp | -0.1 s more after #3, and 600 MB less peak memory at 2x | | yes | not done (native Allocator change, every consumer must stay read-only) |
| 8 | 2x merge passes: W2.2 items (guide over the whole frame, mark only clipped blocks, skip empty Bento / rim strips) | worker | unknown, profile first (`profile 1`) | | likely | not done |
| 9 | GPU drain + readback at 50 MP (1.76 s): PBO readback overlapped with the encode | app | unknown | | yes | not done (measure with `post_sync 1` first) |

Expected vivo plain 2x shot after 1-5: 23.1 s -> about 18.3-18.8 s. The rest is the 2x merge (9.4 s), the 50 MP post
pipeline on the GPU and the 20 MP WebP: they need output-changing options (section 3).

Expected Pixel 7: F6 never on the path in the logged conditions (-0.9..-16 s), -0.3..-2 s more with diagnostics off.

### What the windowed F6 holds and why it is exact

- Donor rows a tile pass reads: `oy + floor(ty) .. oy + floor(ty) + win` (clamped to the image), ty = the homography's
  translation of the tile + the field's y. The L1 field moves at most 4 RAW px (2 level px) per coarse LK step (each step is
  clamped to 1 L1 px, the 3x3 medians and the bilinear lookup stay within the values), each L0 step at most 1 level px;
  3 rows more for floor and rounding. A non-finite or out-of-image translation takes every row.
- `laGrayRow` / `laDownRow` are the former loop bodies of `laGray` / `laDown` (the same arithmetic in the same order, the
  NEON path unchanged); `laTilePass` reads the donor rows through `LaImage::row()` (only the addressing changed).
- `tune laStream 2` forces the windowed mode for A/B replays; `SCAM_F6_MEMAVAIL_MB` replaces MemAvailable in this decision
  only, so the low-memory route can be replayed on the OPPO. The report says
  `HYBRID LOCAL ALIGN: field streamed with windowed gray rows (...)` and the F6 STREAM line ends with
  `windowed gray rows, N MB held at most`.

## 3. Output-changing options for the owner (not implemented)

| Option | Gain (vivo plain) | Cost |
|---|---|---|
| Plain Bayer on the sensor grid instead of the Sabre 2x grid (output mode 12 MP for plain streams, keep 2x for remosaic) | merge 9.7 -> ~2.5 s, post 6.0 -> ~1.3 s, hand-off -0.6 s, WebP 3.7 -> ~2.2 s: about **-13 s** | loses the 2x super-resolution detail of mode 20 |
| Resize to the final 20 MP before the post pipeline instead of after it | post 6.0 -> ~2.5-3 s | the ARK tone / sharpening run on resampled pixels (different look of fine detail) |
| JPEG (jpegli) instead of WebP for the photo | 3.7 -> ~0.7 s at 20 MP | the owner's format choice |
| Pixel 7 / low-RAM phones: cap the burst (e.g. 15 frames) or the F6 frames | merge and F6 roughly halved | more noise |
| Processing diagnostics off (Pixel log) | -1.1..-2.3 s header, -1..-8.5 s client output | no NICE zip per shot |

## 4. Verification

- Worker built with the NDK (bwt.sh): no warning. Reference = clean 9f6a459 build.
- `tools/check_hybrid_prefetch.cpp` (CI runs it with g++ / ASan): item 4 also compares the windowed streams, new item 5 =
  tall frame, strong motion, out-of-image translation, LK iteration variants, memory bound. Built for arm64 with the NDK
  (not run here; runnable on the phone, see the replay list).
- App unit tests: `PlainShotSpeedTest` (3) and `ShotSpeedBitExactTest` (7) pass; full suite 534 tests, 1 failure
  `CaptureControllerTest.testGetCameraOutputSize_withTwoParameter`, which also fails alone and is in code this branch does
  not touch (PhotonCamera.sPhotonCamera null in the test).

### Device replays for the coordinator (OPPO fb27034c)

Binaries: `scratchpad/p48/worker_ref` (9f6a459, md5 628e303a12c9c15bfbabe39b1f90aa6f) and `scratchpad/p48/worker_new`
(this branch, md5 01c0d1f6fe97ab6415084b82c7d78eff). Every pair below must print the same output md5.

```
R=C:/Users/MECHREVO/AppData/Local/Temp/claude/C--Users-MECHREVO-Downloads-x200u/821626a3-f17b-4a79-9a40-c85f28791791/scratchpad/p30
P=C:/Users/MECHREVO/AppData/Local/Temp/claude/C--Users-MECHREVO-Downloads-x200u/bf400803-7f8d-4a20-b0ba-c4377f5d5f71/scratchpad/p48
RP=C:/Users/MECHREVO/Downloads/x200u/SCAMERA-PC/research/p29/proto/rp29.sh
for B in syn_b1 syn_b2 syn_b4 hand isz2 x7u_1x; do
  RPBIN=$P/worker_ref bash $RP p48_${B}_ref /data/local/tmp/$B.nch                     # reference
  RPBIN=$P/worker_new bash $RP p48_${B}_new /data/local/tmp/$B.nch                     # = ref (row refactor, mapped write)
  RPBIN=$P/worker_new bash $RP p48_${B}_win /data/local/tmp/$B.nch "laStream 2"        # = ref (windowed gray forced)
done
# the low-memory decision itself (plain Bayer bursts): full stream refused, windowed taken / everything refused.
# The value must put MemAvailable/4 between the two estimates the log line prints ("N MB of gray images, about M MB
# windowed"): 1000 for a 35-frame 12 MP burst (470 / ~140 MB); for a smaller burst read N and M from p48_*_win's log
# (the p48_*_win logs print both: pick 4*M < value < 4*N).
RPENV=SCAM_F6_MEMAVAIL_MB=1000 RPBIN=$P/worker_new bash $RP p48_b1_lowmem /data/local/tmp/syn_b1.nch   # = ref; log: "field streamed with windowed gray rows"
RPENV=SCAM_F6_MEMAVAIL_MB=1000 RPBIN=$P/worker_new bash $RP p48_x7u_lowmem /data/local/tmp/x7u_1x.nch  # = ref
RPENV=SCAM_F6_MEMAVAIL_MB=100  RPBIN=$P/worker_new bash $RP p48_b1_nomem /data/local/tmp/syn_b1.nch    # = ref; log: "field not streamed"
RPENV=SCAM_NO_MAP_WRITE=1      RPBIN=$P/worker_new bash $RP p48_b1_write /data/local/tmp/syn_b1.nch    # = ref (write() path)
# the vivo plain shot's tuning (2x grid): rp.sh prints no md5, compare scratchpad/mvdata/out_<label>.f32 locally
MV=C:/Users/MECHREVO/AppData/Local/Temp/claude/C--Users-MECHREVO-Downloads-x200u/bf400803-7f8d-4a20-b0ba-c4377f5d5f71/scratchpad/mvtools/rp.sh
bash $MV p48v_ref /data/local/tmp/syn_b1.nch $P/worker_ref
bash $MV p48v_new /data/local/tmp/syn_b1.nch $P/worker_new
bash $MV p48v_win /data/local/tmp/syn_b1.nch $P/worker_new "laStream 2"
# host check on the phone (the NEON paths; CI runs it on x86): prints PASS
adb -s fb27034c push $P/check_hybrid_prefetch-arm64 /data/local/tmp/ && adb -s fb27034c shell 'chmod 755 /data/local/tmp/check_hybrid_prefetch-arm64 && /data/local/tmp/check_hybrid_prefetch-arm64'
```

Timing to read in the new logs: `HYBRID STAGES localAlign` (whole field) vs `HYBRID F6 STREAM ... strips waited`, and
`NICE WORKER TIMELINE merged -> written` (mapped write). In the app (vivo / OPPO in-app shots): `nicergb input ms: upload=...
(rgba bands) stats= decimate= wait=` and `HEX CLIENT OUTPUT ms`; `rgba_upload 0` in nice_dev.txt gives the old upload for an
A/B on the same phone.
