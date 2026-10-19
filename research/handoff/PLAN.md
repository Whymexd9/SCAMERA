# SCAMERA: settings cleanup, route reduction and portable configs — work plan

Base: `origin/main` 56b8125 (= Actions build 30415, the latest APK). All paths below are relative to the repo root; Java paths are relative to `app/src/main/java/com/particlesdevs/photoncamera/`. Line numbers are from 56b8125 and are approximate — re-locate before editing.

Abbreviations: PK = `settings/PreferenceKeys.java`, SA = `settings/SettingsAvailability.java`, CC = `capture/CaptureController.java`, HP = `processing/processor/HdrxProcessor.java`, PP = `processing/opengl/postpipeline/PostPipeline.java`, XML = `app/src/main/res/xml/preferences.xml`.

## 0. Ground rules

- AGENTS.md applies:
  - never switch sensor modes;
  - no intermediate APKs;
  - the APK for the owner comes only from GitHub Actions.
- Work phase by phase. Make one commit per phase. Do not push until the owner says so.
- After every phase, run these checks:
  - `bash ./gradlew :app:compileDebugJavaWithJavac :app:testDebugUnitTest`. Use `bash` because `gradlew` may lack the exec bit.
  - Every host check from `.github/workflows/build-test-apk.yml`, exactly as CI runs it:
    - the worker build `g++ ... scam-neural-worker.cpp ... && ... --transport-check`;
    - `tools/check_scam_probe.cpp`;
    - `tools/check_settings_model.py`;
    - the python checks.
  - A leftover grep for the removed names.
- Never skip, disable or weaken a test to get green. Update a test only when the thing it pins was deliberately removed.

### Recipe: removing a setting safely

Deleting the XML row is not enough. The stored value stays in:
- main prefs;
- every `module_profile_v2_<slot>` file, where it comes back on a lens switch;
- backups;
- favorites.

If a getter still reads the value, it keeps acting invisibly. For every removed key:

1. **XML:** delete the row. Delete strings and arrays used only by that row. Check `res/values*/`, including `scamera_recovered_26790.xml`, `arrays.xml`, `strings.xml` and `values-ru`. Note that 97 of the 101 keys in `values/preference_keys.xml` are also referenced from Java (enum `PK.Key`).
2. **Getter:** delete the getter in PK, plus its `Key` enum entry if it has one. Make the call sites use the fixed behaviour the owner keeps.
3. **Stored values:** add the key (or prefix) to a new `SettingsMigration.removeObsolete(SharedPreferences.Editor)`:
   - Call it wherever `migrateMultiFrame` is called (SettingsManager constructor, `ModuleProfiles.restore`).
   - Also run it over every `module_profile_v2_*` file, including `_common`.
   - Strip the key from the favorites JSON `settings_favorite_keys`.
   - `migrateMultiFrame` is the existing example of key removal.
4. **Remnants:** remove the key from:
   - SA;
   - `settings/SettingsNumericRules.java`;
   - the hard-coded key arrays in `ui/settings/SettingsActivity.java` (about lines 182, 221, 236, 289, 380);
   - `settings/DeviceDefaults.java`;
   - `SettingsMigration.SHARED_*`;
   - `tools/java/SettingsModelCheck.java`;
   - `docs/settings-audit-*.csv`.
5. **Tests and CI:**
   - `SettingsMenuTest` — pins many keys and screens. It also requires more than 300 keys and more than 25 screens: lower these thresholds to the new real numbers.
   - `HybridSettingsTest` — not in CI; run it anyway.
   - `BurstPolicyTest`, `CameraResumeTest`.
   - The aapt2 assertions in `build-test-apk.yml` (about lines 251-257).
   - `.github/workflows/check-hexquad.yml` together with `tools/check_scam_hexquad_controls.py`.

### Dynamic (tunable) screens

Screens of the form `expert_*_screen` are filled at runtime by `settings/TunablePreferenceGenerator.java` from `@Tunable` fields of the classes registered in `settings/TunableRegistry.java`. The target screen is chosen by class name (generator, about lines 111-122).

If a target screen is missing, the generator throws inside a `try`, and all later categories are silently not created. So when deleting a screen, first unregister or delete the classes that target it.

`pref_sensor_config_submenu` is generated separately by `SensorConfigPreferenceGenerator` and stays.

## 1. Owner decisions (fixed)

1. **Merge routes:** only two remain, **LMC hybrid** and **SCAM HDR**. The hybrid is the default on **every** phone, with no exceptions:
   - SM8750 / Vivo X200 Ultra — today excluded, shoots legacy ESD4D + AgX by default;
   - OPPO PHY110 and PKJ110 — `DeviceDefaults` pre-sets SCAM HDR today.
2. **Neural remosaic:** it stays **only inside SCAM HDR** (`pref_vivo_nice_mosaic` = `neural` / `neural_sabre`). The standalone remosaic route goes: `pref_remosaic_enabled_key` + backend `scamera` / `tetra_detail` / `vivo_neural` / `hp9_hexquad` / `imx06c_quad`.
3. **Old post-processing:** the PhotonCamera post-processing is removed completely. It only served the legacy route and standalone neural.
4. **Vivo upscale:** removed completely — RAISR, SoftPQE, VSR, their natives, assets, CI and settings.
5. **"Конфиг" screen:** remove the row "Сохранить", the screen "Кадрирование DNG" and the screen "Сброс дополнительных параметров".
6. **Config save/apply:** reworked so that applying a config on another phone puts the per-lens settings on the matching lenses automatically.

### Defaults taken unless the owner objects

- The SCAM HDR mosaic modes `scamera` / `detail` / `mfr` / `sabre` stay as SCAM HDR options. They are also its fallback, and the S/ES/L frames go through the GPU remosaic.
- A failed hybrid or SCAM HDR merge loses the shot today: HP ~552 throws, then HP ~132 → `onFailed`. The legacy route was not a fallback either. Do not add a fallback in this work.

## 2. What the kept routes actually read (code audit)

| Settings group (XML screen) | Read by hybrid / SCAM HDR? |
|---|---|
| `burst_settings_screen` | Only `pref_zsl_buffer_count_key`; the hybrid also reads `pref_antibanding_hz_key` (`HybridPlan` ~116). Frame counts, short/long, EV, highlight suppression (HP ~290 forces 100), HDR ratio, TET and long cap are legacy only. |
| `detail_noise_settings_screen` (with `merge_snr_screen`, `expert_merge_screen`) | No. ESD4D only. |
| `scamera_quad_bayer_screen` | Yes: `pref_raw_lsc_mode`, `pref_raw_black_from_data` (`Parameters` ~406/462), `pref_raw_stream_format`, `pref_cfa_key`, Quad compatibility (`VendorTagUtils` ~214). Demosaic choice and `expert_raw_screen`: no, because NICE outputs RGB. |
| `noise_model_screen` | Only the profile, import and export, and only when `pref_lmc_hybrid_noise_source` or `pref_vivo_nice_noise_source` = `settings`. Dynamic ISO, ISO curve, digital gain and the slider: no. |
| `rt_denoise_screen` (RT512, ESD3D, AI Bayer) | No. |
| `sharp_settings_screen` | `pref_sharp_*` via `RTSharpening` only in the `rt` sharpen modes: hybrid `sharp_mode` = rt, or ark + `ark_post_sharp`; SCAM HDR with soft tone 0. `pref_sensor_sharpening_enabled`, LLF, MFSR and Mosaic SR: no. |
| `processing_group_screen` | DCP yes (`Parameters` ~724). Tone pipeline selector: no. LMC curves: yes. ACES, Capture One, darktable, optical correction, contrast, saturation, shadows, compressor and `expert_tone`: no. |
| `agx_screen` | SCAM HDR: `pref_nice_ae_*` and `pref_vivo_nice_fusion_*`, soft tone, tone key, sharp amount, texture. With soft tone = 0, also the shared AgX curve and `pref_vivo_hdr_*` tone. Hybrid with ARK off: the shared AgX curve + `pref_lmc_hybrid_*`. `pref_expocompensation_seekbar_key`: no NICE route reads it (`IsoExpoSelector` ~360). |
| `gcam_finish_screen` | No (PP ~665; `scene_ae` is off for NICE, CC ~2559). |
| `vivo_hdr_screen` | `pref_vivo_hdr_luma`, `pref_vivo_hdr_chroma` and `pref_vivo_hdr_sharpen` only on "autonomous HDR without NICE", which is removed. The rest is SCAM HDR. |
| `vivo_remosaic_screen` | In SCAM HDR mosaic mode: block, profile, steered, clamp, flatfield, phase, `pref_tetra_response_key`, `hexquad_*`, `quad2x2_*`. Not used there: `hexquad_exposure_ev`, `pref_hexquad_frames`, `pref_quad_frames`, `hexquad_full_resolution` (x2 is forbidden there), `*_post_denoise`. |
| Global | `pref_save_raw_key` (merged DNG, HP ~602-623), Ultra HDR (`LinearExposure` snapshot), watermark (`RotateWatermark` in every branch), 16:9 (`ScamRgb` ~227, `SaverImplementation` ~46), zoom. |

### Shared infrastructure that must stay

- **Capture:** `scameraBracketPlan`, `HybridPlan`, `ScamStockAe`, the ZSL ring.
- **Merge:** `ScamHybridBurst`, `scam-hybrid.h`, `ScamBurst`, `ScamMosaic`.
- **SCAM HDR mosaic:**
  - `HexQuadBurst.processForNice`;
  - `MobileRemosaicProcessor.mergeForNice` (mosaic = `mfr`);
  - `RemosaicCore`, `TetraDetailRemosaic` (`ScamMosaic` ~197, PK ~620).
- **Worker and client:** `ScamNeuralClient` / `ScamNeuralWorker` and the one native worker `app/src/main/cpp/scam-neural-worker.cpp`. That worker hosts NICE, the hybrid, HexQuad/Quad and the CRE runtime — and VSR, which goes.
- **Post path:**
  - `ScamRgb`, `HighlightRecovery`, `ScamHdrDenoise`, `ScamDenoise`;
  - Ark* (`ArkStats`, `ArkFusion`, `ArkLumaSharpen`, `ArkCombine`, `ArkSharpenGuard`);
  - `LinearExposure`, `NiceExposureFusion`, `VivoHdrTone` (a subclass of `HeadroomRender` — keep `HeadroomRender`), `AgxTone`;
  - `ScamCurves`, `ScamLocalContrast`, `ScamSharpen`, `RTSharpening`, `HybridFinalResize`, `RotateWatermark`.
- **Ultra HDR:** `ultrahdr/*` and `PP.RunHDRGainMap`.
- **Live RAW viewfinder** (`PP.BuildPreviewPipeline` ~572, vivo branch): `Bayer2Float`, `BinnedDemosaic`, `ABLC`, `LinearExposure`, `VivoHdrTone`.
- **Hybrid final resize:** `ScamPostDownscale.resizeTo` (HP ~744, `PK.hybridDownsampler`).
- **Saving:** `Parameters` (CCM, DCP, LSC, black level), `ImageSaver`, `DngCreator`.

## 3. Phases

### P1 — Remove Vivo upscale completely

**Java:**
- Delete `processing/ml/VivoRaisrProcessor.java` and `processing/ml/VivoVsrProcessor.java`.
- Delete the HP block ~751-786: upscale plus "Lanczos after Vivo".
- In `ScamPostDownscale`: keep `resizeTo` and what it needs. Delete `process(...)` and the size/kernel options that only it uses.
- PK: delete `isRaisrEnabled`, `getRaisr*`, `getVivoUpscaleBackend`, `getVivoDownscaleKernel`, `getVivoDownscaleSize`. `getRaisrMode` and `getRaisrFilterScale` are already dead.
- Delete the VSR entry points in `ScamNeuralWorker` / `ScamNeuralClient`.
- Delete the SA RAISR/downscale rules (~190-200).

**Native:** delete:
- `app/src/main/cpp/vivo-upscale-worker.cpp`;
- `vivo-raisr-abi.h`, `vivo-raisr-controls.h`;
- `vivo-softpqe-abi.h`, `vivo-softpqe-controls.h`;
- `vivo-vsr.h`;
- the VSR paths in `scam-neural-worker.cpp`;
- whatever builds or packages the upscale worker (CMake, gradle, jniLibs).

**Assets and resources:** delete `app/src/main/assets/vivo-upscale/`, `res/values/vivo_upscale.xml`, `res/values/softpqe_controls.xml` and the related arrays and strings.

**Tools and CI:**
- Delete `tools/vivo-upscale/`.
- Delete `tools/check_lanczos_downscale.py` only if it does not cover `resizeTo`.
- Delete the `build-test-apk.yml` steps that build or check the upscale worker, RAISR, SoftPQE or VSR.
- In `tools/package_scam_neural.py` and `tools/ci_neural_assets.py`, drop the VSR, RAISR and SoftPQE entries from the pinned/required list. Every NICE, CRE, HexQuad and Quad file must stay required. Bundle v2 itself is untouched; it just stops shipping those files.

**Settings:** remove the whole `raisr_settings_screen`, including "Даунскейл после Vivo", `vivo_downscale_explanation` and `softpqe_sr_only_info`. Apply the removal recipe.

**Tests:** in `SettingsMenuTest`, drop the `pref_vivo_downscale_*` assertions (~254) and the `softpqe_sr_only_info` assertion (~281).

**Docs:**
- Delete `docs/vivo-upscale.md` and `docs/softpqe-sr-only-30212.md`.
- Trim `docs/vivo-post-downscale-30211.md` down to the hybrid resize.
- Fix short mentions in other docs.

**Done when:** `grep -riE "raisr|softpqe|\bvsr\b|vivo-upscale|vivo_downscale"` hits only historical build notes; the worker host build and all checks pass.

### P2 — "Конфиг" screen (`output_settings_screen`)

1. **Row "Сохранить":** remove only the `pref_save_raw_key` row. The key, `PK.isSaveRaw` / `setSaveRaw`, the RAW button in the quick bar and DNG saving all stay.
2. **"Кадрирование DNG":** delete `processing/ImageSaverSettings.java` (`@Tunable cropType`, "Crop Edge"). Then:
   - remove its registration and the generator mapping `"ImageSaverSettings" → expert_output_screen`;
   - make the branches in `processing/SaverImplementation.java` ~50 and `processing/processor/RawVideoProcessor.java` ~138 always crop to the centre;
   - remove the SA rule for `pref_tunable_imagesaversettings_croptype` (~229);
   - remove `expert_output_screen`;
   - clear the stored `pref_tunable_imagesaversettings_croptype`.
3. **"Сброс дополнительных параметров":** remove `pref_tunable_submenu` and its reset button (`SettingsActivity` ~461, ~597-634, ~1117). The trap: `TunablePreferenceGenerator.generatePreferences` returns early when `pref_tunable_submenu` is missing (~50). Remove that dependency so the remaining dynamic screens are still filled:
   - `expert_viewfinder_screen` "Кнопки видоискателя";
   - `expert_sensor_screen` "RAW-метаданные";
   - `pref_sensor_config_submenu`.
   "Сбросить всё" (`ResetPreferences`) stays.
4. **Result:** "Конфиг" contains Ultra HDR, 16:9, watermark (+5 sub-items), Сохранить/Восстановить/Сбросить всё.

### P3 — Route: one selector, hybrid by default

- **Selector:** replace the master switches `pref_lmc_hybrid_enabled`, `pref_vivo_hdr_enabled` and `pref_vivo_nice_enabled` with one `ListPreference pref_merge_route` = `hybrid` | `scamhdr`, default `hybrid`, at the top of the settings. This removes the "both off → legacy" state.
- **Migration from the old switches:**
  - hybrid on → `hybrid`;
  - otherwise `vivo_hdr` and `vivo_nice` on → `scamhdr`;
  - otherwise → `hybrid`.
  Then remove the old keys, following the recipe.
- **Code to update to the new key:**
  - `PK.isLmcHybridEnabled` / `isScamHdrSwitchOn` / `isVivoNiceEnabled` / `isVivoHdrEnabled` (PK ~910-1063);
  - `android:dependency` attributes in XML;
  - `DeviceDefaults` (~30-31: PHY110/PKJ110 put the old switches);
  - `SettingsMigration` ~249 (enables the hybrid on PHY110);
  - the `scam_dev.txt` "hybrid 0/1" override (PK ~1034);
  - SA hybrid/SCAM HDR rules;
  - the HP shot profile (~100-110).
- **Hybrid default on every phone:**
  - `settings/LmcHybridKeys.defaultOn` currently returns false on SM8750 (`vivoNetSoc()`) and on non-arm64. Drop the SM8750 exclusion. Run `git log -S vivoNetSoc` and note the original reason in the commit message, but do not keep the exclusion.
  - Non-arm64 is not a target. If the hybrid cannot run there, `hybrid` is still the stored default.
  - `DeviceDefaults` (~30-31, ~66 and the other SCAM HDR values for PHY110/PKJ110): stop pre-selecting SCAM HDR. The SCAM HDR tuning values may stay; they only matter when the user picks SCAM HDR. The route itself is not written by `DeviceDefaults`.
  - `SettingsMigration` ~249 (PHY110 hybrid switch): drop or fold it into the route migration.
  - Existing installs: the route migration above keeps an explicit user choice (hybrid on → `hybrid`; SCAM HDR on → `scamhdr`). Only fresh installs and "Сбросить всё" land on `hybrid` by default.
- **Gates:** with no incompatible routes left, simplify `isVivoRouteCompatible`, MFSR/remosaic gates in PK ~537-640 and the SA messages. This also removes the trap where SA allows SCAMERA remosaic but `ScamHybridBurst` ~61 and `ScamBurst` ~53 throw on `isRemosaicEnabled`.

### P4 — Remove the legacy capture and merge

**Capture (CC):**
- Remove the legacy bracket branch (~3500-3575 via `IsoExpoSelector` long/ultrashort) and `FrameNumberSelector` use for legacy.
- Remove the MFSR capture count (`multiFrameCaptureCount`).
- Remove the standalone neural burst (`neuralBurstFrames`, `HexQuadZslSelector`), unless `processForNice` frame selection uses it — check first.
- `needsExposureBracket` (~2525) becomes "NICE always brackets".
- Keep `scameraBracketPlan`, `HybridPlan`, `ScamStockAe` and the ZSL logic.

**Merge (HP):**
- Remove the standalone MFSR/CAL route (~427). Keep `MobileRemosaicProcessor.mergeForNice`.
- Remove standalone `HexQuadBurst.process` (~428). Keep `processForNice`.
- Remove the ESD4D fusion branch (~566-603) and "single-frame recovery", Mosaic SR (~640) and AI Bayer denoise (~679-690).
- Remove the always-true conditions (~508 `!hex && !multi`).

**Delete when unreferenced:**
- `ESD4D`, `PyramidAlignment`, FlowNet/KernelNet (`pref_processing_backend_key`), `BurstFrameSelector`;
- the HDR+ merge, Sabre RAW (`pref_mfsr_engine_key` = `sabre`, `pref_gcam_cyclops`);
- the ESD4D VIVO_HDR branch and its `scamhdr/*` merge shaders (keep the shaders `VivoHdrTone` uses), `VivoHdrDenoise`;
- `VivoNeuralRemosaic` (per-frame `vivo_neural`), the standalone `Remosaic` node in PP, `processing/ml` AI denoise.

**Standalone remosaic settings:**
- Remove `pref_remosaic_enabled_key`, `pref_remosaic_backend_key`, `pref_hexquad_frames`, `pref_quad_frames`, `hexquad_exposure_ev`, `hexquad_full_resolution`, `hexquad_post_denoise`, `quad2x2_exposure_ev`, `quad2x2_post_denoise`.
- Keep the GPU remosaic tuning keys and the `hexquad_*` / `quad2x2_*` NN tuning used by SCAM HDR mosaic.
- Update `check-hexquad.yml` and `tools/check_scam_hexquad_controls.py`: they require `hexquad_exposure_ev` (0, [-2..2]).
- Clean up `SettingsActivity` ~217-247 and ~321-388 (remosaic backend listeners and probes). Keep the probes that SCAM HDR / neural still use.

**Settings removed (recipe):**
- `detail_noise_settings_screen`, whole, including `merge_snr_screen` and `expert_merge_screen` (ESD4D / PyramidAlignment tunables);
- `burst_settings_screen` except `pref_zsl_buffer_count_key` and `pref_antibanding_hz_key`;
- `mfsr_settings_screen`, `scamera_mosaic_sr_screen`;
- `pref_processing_backend_key`, `pref_zsl_quality_selection_key` (verify), `pref_binning_key` (verify that hybrid / SCAM HDR do not depend on `Allocator.binning`, then remove);
- `pref_energy_safe_key` — `SettingsBarEntryProvider.resetRemovedSettings` (~106) already force-resets it;
- `pref_vivo_hdr_luma` / `pref_vivo_hdr_chroma` / `pref_vivo_hdr_sharpen` (autonomous-only);
- the remains of `pref_mfsr_*` and `pref_raw_mfsr_enabled_key`.

### P5 — Remove the legacy post-processing

**PP:**
- In `BuildDefaultPipeline`, everything outside the NICE branches (~603-750) goes. Remove:
  - the non-NICE Bayer import/demosaic, `ESD3D2`, `ABLC` for non-NICE, `GcamFinish`, `RawTherapeeDenoise`;
  - the tone-pipeline switch (fusion/agx/opendrt/curve/sky/off), `LocalLaplacian`;
  - `CorrectingFlow`, `FalseColorSuppression`, `CaptureOneProcessing`, `HexQuadExposure`, `CaptureSharpening`.
- In `BuildPreviewPipeline`, keep only the vivo branch.
- Remove the always-true `&& vivoHdrMode` (~720).

**Delete classes left unreferenced:**
- `ESD3D2`, `RawTherapeeDenoise` and the native `rt-denoise` library (only if `RTSharpening` does not use it — check CMake), `RawTherapeeSettings` denoise parts;
- `GcamFinish`, `ExposureFusionBayer2`, `Amaze`, `PixelGroupingDemosaic` (keep `BinnedDemosaic`);
- `OpenDRT`, `AutoExposureCurve`, `Initial`, `LocalLaplacian`;
- ACES, darktable, Capture One, optical correction nodes;
- the `HeadroomRender` sky / "plain photo" branches (keep what `VivoHdrTone` and the hybrid need).

**Settings removed:**
- `rt_denoise_screen` (whole), `gcam_finish_screen` (whole);
- `pref_tunable_postpipeline_tonepipeline`, `pref_tunable_postpipeline_demosaicingmethod`;
- `expert_raw`, `expert_noise`, `expert_detail` and `expert_tone` screens together with their `@Tunable` classes (unregister first);
- `aces_group_screen`, `capture_one_group_screen`, `scamera_darktable_screen`, `optical_correction_screen`;
- `pref_saturation_seekbar_key`, `pref_contrast_seekbar_key`, `pref_shadows_seekbar_key`, `pref_compressor_seekbar_key`;
- `pref_sensor_sharpening_enabled`;
- `noise_model_screen` dynamic, ISO curve, digital gain, min/max/manual ISO, coefficient and slider;
- AgX keys marked "(обычная съёмка)" (`pref_agx_local_highlights`, `pref_agx_local_start`);
- `pref_expocompensation_seekbar_key` — only if the viewfinder EV control uses a different key (check first).

**Keep:**
- DCP, LMC curves;
- `pref_sharp_*` (RT sharpening for the `rt` modes);
- the shared AgX curve, look and colour keys (SCAM HDR with soft tone 0, hybrid with ARK off);
- all SCAM HDR tone keys, `pref_nice_ae_*`.

**CI:** replace the aapt2 assertions on `rt_denoise_screen` / `rt512_*` (`build-test-apk.yml` ~251-257) with assertions on screens that exist now (e.g. `lmc_hybrid_screen`, the SCAM HDR screen, `output_settings_screen`).

### P6 — New settings tree

Only move XML rows. Do not rename keys, so no migration is needed for moves.

```
Настройки камеры
├ Склейка: Hybrid | SCAM HDR                (pref_merge_route)
├ Hybrid ▸                                  (existing lmc_hybrid_* screens, unchanged)
├ SCAM HDR ▸
│   ├ Основное: N-кадров из ZSL, удлинение L, сохранять этапы
│   ├ Брекет и выравнивание: CRE, L из ZSL, планировщик и ступени SCAMERA
│   ├ Шумоподавление Luma / Chroma (+ множители по ISO)
│   ├ Модель шума нейросети
│   ├ Тон и светотень: яркость (AE), Exposure Fusion, света, тени и уровни, сброс   (from agx_screen)
│   └ Мозаика ISZ и нейроремозаик: pref_vivo_nice_mosaic, GPU remosaic tuning, HP9 / Quad 2×2 NN tuning
├ Цвет и резкость ▸ DCP, кривые Hybrid, общая кривая AgX (ARK off / soft tone 0), резкость RawTherapee (rt modes)
├ Захват и RAW ▸ буфер ZSL, антибандинг, LSC, чёрный по кадру, формат RAW-потока, CFA, Quad-совместимость,
│                 профиль модели шума (импорт / экспорт), RAW-метаданные (expert_sensor_screen)
├ Видоискатель и интерфейс ▸ (tweaks_screen content, Кнопки видоискателя)
├ Конфиг ▸ Ultra HDR, 16:9, водяной знак, Сохранить / Восстановить / Сбросить всё
├ Объективы и сенсоры ▸ (unchanged)
└ Система ▸ Root-доступ, диагностика Vivo (probes), спуф пакета, полный журнал, о нас
```

- Keep `pref_root_enabled`: the SCAM HDR stock AE planner and neural need it.
- Every screen must have content. `SettingsMenuTest.allPagesAndRegisteredTunablesAreReachableAndBind` must pass, with its thresholds lowered to the new real counts.
- The search for a key (e.g. `hexquad_luma`) must still return exactly one result (SettingsMenuTest ~422).

### P6a — Rename "LMC" to "Hybrid" everywhere the user can see it

The owner's rule: the word "LMC" must not appear anywhere in the UI, and the engine is called "Hybrid" in Latin letters, also inside Russian text (e.g. "Кривые Hybrid", "Шумодав Hybrid"). Do not use the Russian word «гибрид» anywhere in the UI. Apply it to:
- titles, summaries and list entries in `preferences.xml`;
- `res/values*` strings and arrays (all locales);
- dialogs, toasts and notifications;
- labels built in Java (`ModuleConceptFragment`, `SettingsActivity`, diagnostics screens, `ScamDiagnostics` text shown to the user);
- search results and favorites.

Replacements:

| Was | Becomes |
|---|---|
| "LMC-гибрид", "гибрид" (any case or form) | "Hybrid" (Latin, always capitalised) |
| "Тоновая и гамма-кривые (LMC)" / "Кривые LMC" | "Кривые Hybrid" |
| "Sabre 6.1 × LMC 9.6" | "Sabre 6.1" |
| "GCam / LMC 9.6 (ArkCam)" | "GCam / Hybrid (ArkCam)" |
| "Отбраковка движения (rejection LMC 9.6)" | "Отбраковка движения" |
| "1 кадр (как LMC)" | "1 кадр" |
| "Bento: проверки отказа как в LMC 9.6" | "Bento: строгие проверки отказа" |
| "SCAMERA — как LMC" (AgX look) | "SCAMERA — как Hybrid" |

For any other occurrence: drop "LMC 9.6" when it is only a provenance note, otherwise say "Hybrid".

What does **not** change, to avoid a settings migration and to keep stored values and backups valid:
- preference keys (`pref_lmc_hybrid_*`, `pref_lmc_tone_curve`, …);
- class names, log tags, file names (`hybrid_tuning.txt`, `scam_dev.txt`), docs and code comments.

Check: grep every `res/values*/*.xml`, `preferences.xml`, and the string literals in `ui/**`, `settings/**` and `processing/**` that end up in views or toasts for `LMC`. No user-visible hit may remain. Add a unit test that fails if any title, summary or entry in the inflated preference tree contains "LMC".

### P6b — One visual style for all settings (the "Камеры и сенсоры" look)

**Reference:** the full interactive concept, with every screen and every parameter of the P6 tree, defaults included: https://claude.ai/artifact/MifyXwYMaf8tt8QSCkoBH3
- It is the source of truth for screen order, grouping, group labels, row types and wording.
- Where the concept and this plan disagree on content, the plan's removal list wins.
- Where they disagree on layout or wording, the concept wins.
- Camera IDs and lens labels in "Камеры и сенсоры" sub-screens are examples.

**Goal:** every settings screen looks like the module screen "Камеры и сенсоры".
- That screen is built natively by `ui/settings/ModuleConceptFragment.java` + `ModuleSettingsFragment.java`.
- Concept (interactive, with the P6 tree): https://claude.ai/artifact/MifyXwYMaf8tt8QSCkoBH3

**Approach:**
- Keep `preferences.xml` + `PreferenceFragmentCompat`. Search, `SettingsAvailability`, dependencies, favorites, the tunable/sensor generators and the tests all live on it.
- Change only the views.
- Do not rebuild the settings as hand-made fragments.

#### 1. Shared style source

- Extract the tokens and builders now private to `ModuleConceptFragment` into one class `ui/settings/SettingsStyle` (or `res/values/settings_style.xml` + a small helper). Tokens: `BG` 0xFF101416, `CARD` 0xFF1B2023, `TEXT` 0xFFF4F3F7, `MUTED` 0xFFB2BAC9, `LINE` 0xFF30363C.
- Builders to extract: card shape, text sizes, icon tint, header, chip, info note.
- `ModuleConceptFragment` and the preference screens both use it, so the two can never drift apart.
- The accent is the user's accent (`AccentPalette.color(context)`), not a hard-coded salmon. Apply it at bind time: icons, switch track, list values, chip text and tint, slider, category labels.

#### 2. Header

- Same as the module screen: back arrow in the accent, small letter-spaced "SCAMERA" eyebrow, centred bold title.
- Hide `settings_toolbar` on preference screens too, the way `ModuleConceptFragment.onResume` does.
- Make the header a reusable view used by both.

#### 3. Row = card

- Every preference is its own card: `CARD` fill, 1dp `LINE` stroke, the same corner radius and padding as the module rows, 10–12dp gap between cards.
- Do it with a card background in each row layout plus margins (or a `RecyclerView.ItemDecoration`). Do not use one big card per category.
- Row content:
  - icon 27–32dp tinted with the accent (left);
  - title 16sp `TEXT`;
  - summary 12–13sp `MUTED`;
  - trailing widget.

| Preference type | Trailing / value |
|---|---|
| `PreferenceScreen` / plain `Preference` with fragment or intent | chevron `settings_concept_chevron` |
| `SwitchPreference(Compat)`, `ManagedSwitchPreference`, `TunableCheckBox` | the module-screen switch: accent track when on, white thumb |
| `ListPreference` | chevron; current entry shown under the summary in the accent. Use `useSimpleSummaryProvider` only where the summary has no `%s`. |
| `UniversalSeekBarPreference`, tunable seekbar | value in an accent pill on the title line, full-width slider under it (restyle `preference_seekbar.xml` / `preference_tunable_seekbar.xml`) |
| `EditTextPreference` | value under the summary in the accent; the edit dialog in the bottom-sheet style (item 5) |
| `PreferenceCategory` | no card: small uppercase accent label with letter-spacing (`preference_category_layout.xml`) |
| Info rows (plain `Preference` without click) | the module screen's info note: ⓘ in `MUTED` + text, no card |

#### 4. Apply it globally, not row by row

- Add `SettingsStyle.apply(PreferenceGroup)`. It walks the tree and sets `layoutResource` / `widgetLayoutResource` by preference class.
- Call it in `SettingsActivity.SettingsFragment.onCreatePreferences` **after** `TunablePreferenceGenerator` and `SensorConfigPreferenceGenerator`, so generated rows are styled too. Also call it in `FavoritesSettingsFragment`, `ModuleCopyFragment` and `SettingsSearchFragment`.
- Remove the per-row `android:layout="@layout/preference_with_margin…"` / `preference_concept_section` attributes from XML; the walker decides.
- Icons: give each P6 section and each top-level row an outline icon in the module-screen style (24dp vector, ~1.7dp stroke, tinted at runtime). Reuse `settings_concept_*` / `module_*` drawables where they fit; add new ones only where needed. Inner rows without an icon keep the 27dp icon slot empty, so text columns line up.

#### 5. Pickers

- `ListPreference`: open a bottom sheet (`BottomSheetDialog`, Material is already a dependency) in `CARD`/`TEXT` colours with accent radio dots. Do it via `onDisplayPreferenceDialog`.
- `EditTextPreference`: a bottom sheet with the same field validation as now (`setupScalarInputs` / `SettingsNumericRules`).

#### 6. Special rows

- **Route selector** (`pref_merge_route`, P3): its own card with title "Склейка", a summary, and a two-segment control "Hybrid | SCAM HDR" (`MaterialButtonToggleGroup`). The selected segment is filled with the accent.
- **Inactive route section:** the root row of the section whose route is not selected is shown at 45 % alpha but still opens.
- **Root chip:** the root screen shows a chip like the module screen's "Настраивается: …", e.g. "Активна: Hybrid".
- **Per-lens chip:** sections holding per-lens keys show the module chip "Настраивается: <label> · ID <id>" (`ModuleSettingsFragment` ~22) when per-lens settings are on.
- **Конфиг buttons:** Сохранить / Восстановить / Сбросить всё become one row of three equal tile buttons: icon above label; the reset tile has a warm warning tint. `BackupPreferences`, `RestorePreference` and `ResetPreferences` keep their logic and dialogs.

#### 7. Disabled rows

`SettingsAvailability` rows stay visible: the whole card at ~45 % alpha, with the reason appended to the summary as now (`applyAvailability`).

#### 8. Theme

- The look is dark-first like the module screen. The app has a light/dark/system theme setting (`pref_theme_key`).
- Either keep the module screen's fixed dark palette for all settings, or add light variants of the five tokens. **Ask the owner** which one before doing the light variant.

#### 9. Checks

- `SettingsMenuTest.allPagesAndRegisteredTunablesAreReachableAndBind` must still bind every row with the new layouts.
- Add a Robolectric render test that inflates one row of each preference type and asserts the card background, accent tint and widget ids.
- Keep `ApprovedConceptsTest` / `ViewfinderUiTest` green.
- Device check: every screen at 360dp width has no clipped text and no ellipsis. Search results, favorites and module copy use the same rows.

- **Done (2026-10-06), P6 + P6a + P6b in one commit:**
  - **Tree (concept order, keys unchanged):** root = route card + Hybrid / SCAM HDR / Цвет и резкость / Захват и RAW /
    Видоискатель и интерфейс / Конфиг / Камеры и сенсоры / Система (the two root categories are gone). Hybrid: Разрешение,
    Даунсемплер, Кадры и захват, Модель шума, Склейка (Отбраковка, Ядро Sabre, Bento и Shasta, Света), Шумоподавление
    (SNR, Множители, Отличия от ArkCam, таблицы), Обработка ArkCore, Диагностика. SCAM HDR: N, удлинение L, этапы, Брекет и
    выравнивание, Шумоподавление, Модель шума нейросети (key vivo_nice_internal_screen), a link to the shared ArkCore page,
    Мозаика ISZ. Settings newer than the concept (F6, неоднородное движение, Xiaomi zoom, звуки, дубликаты модулей, P11 path
    to white, …) stay in their sections. Rows whose master switch moved to a parent page (denoise tables, watermark text)
    lost android:dependency; SettingsAvailability disables them instead.
  - **P6a:** no «LMC» and no Russian «гибрид» in titles, summaries, entries, dialogs and Java toasts / errors; test
    SettingsMenuTest.noLmcOrRussianHybridInTheSettingsTexts walks the inflated tree (titles, summaries, entries).
  - **P6b:** ui/settings/SettingsStyle (tokens = res/values/settings_style.xml, header, chip, tree walker by class, bind
    decoration, bottom sheets); RouteSelectorPreference (MaterialButtonToggleGroup); concept icon set settings_ic_* (38);
    row layouts preference_card / seekbar / category / info / tile / route; toolbar hidden for good (search in the header);
    «Конфиг» tiles via a 3-column grid; inactive route row at 45 %; root chip «Активна: …»; module chip on pages with
    per-module values. ModuleConceptFragment, search, favorites and module copy use the same header and tokens.
  - **Theme (item 8): dark only, as the module screen.** Light variants not made: waiting for the owner's choice.
  - Tests: SettingsMenuTest render tests (every row type, route segments, inner pages at 360 dp with PNGs in
    build/reports/module-concept, bottom sheets); HybridSettingsTest updated for the new tree. All unit tests pass except the
    known CaptureControllerTest.testGetCameraOutputSize_withTwoParameter. No local APK (task rule).

### P7 — Portable config save / apply

**Verified current behaviour (`settings/BackupRestoreUtil.java`):**
- **Export** writes:
  - main prefs except `pref_tunable_*`;
  - the legacy per-lens file `<pkg>_per_lens` (`settings_for_camera_<cameraId>`);
  - tunables;
  - metadata `version` 2.1.
- **Export does not write** the real per-lens storage: `module_profile_v2_<slot>` (back0..7 / front0..7) and `module_profiles_meta`.
- **Restore:**
  1. `clear()`s main prefs and writes everything verbatim. That includes device-specific keys: `module_auto_<slot>` (Camera2 IDs of the source phone), `module_lens_sig_*`, `pref_sensorconfig_<slot>_*` (vendor tags), `device_defaults_version`.
  2. Restarts the app.
- **Result:** the target's own `module_profile_v2_*` files remain. On the first lens switch, `ModuleProfiles.activate` → `snapshotStored` loads them and the restored per-lens values are lost. This happens even on the same phone. Across phones, the slots also point at the wrong Camera IDs.

**Design:**

1. **File format v3** (JSON, `metadata.version` = 3):
   - `source`: Build manufacturer, model, SoC, app version;
   - `common`: settings that are not per-lens, minus device-only keys;
   - `baseline`: `module_profile_v2_common`;
   - `lenses`: `[{slot, facing, role, equivFocalMm, zoom, sensorCrop, sensorMode, label, cameraId, settings}]`. `settings` holds the `isLocal` keys of that slot; for the active slot, take them from main prefs;
   - `favorites`;
   - `tunables` (what remains after P2/P5);
   - `deviceOnly`: `module_*`, `pref_sensorconfig_*`, camera id key, `user_camera_ids` / `hidden_camera_ids`, package spoof keys.
2. **Lens passport per slot:**
   - `facing`;
   - `equivFocalMm`:
     - real lens: `CameraLensData.camera35mmFocalLength`;
     - sensor-crop slot (`ModuleRegistry.sensorCrop`): equiv × `zoom` / `nativeRatio`;
   - `role`:
     - FRONT;
     - MAIN — the default 1× back slot;
     - UW — focal < 0.8 × MAIN;
     - CROP — `sensorCrop`;
     - TELE — focal > 1.25 × MAIN.
3. **Same device** (same manufacturer + model and the same back/front Camera ID set): restore everything verbatim, including `deviceOnly` and every lens profile. This is the old behaviour plus profiles.
4. **Another device** — keep the target's slot mapping and sensor configs. For every visible target slot, pick the source lens with:
   - the same facing;
   - the minimal cost `|ln(focal_t / focal_s)|` + 1.0 if the roles differ;
   - on a tie, the same `sensorCrop`, then the lower zoom.
   Many-to-one is allowed: e.g. one source tele feeds both the target's 3× and 6×. Every target slot gets a profile. A target slot with no same-facing source gets `baseline`. Source lenses nobody picked are reported, not applied.
5. **Apply:**
   - write `module_profile_v2_<slot>` and `module_profiles_meta` `exists_<slot>`;
   - write `common` plus the active slot's profile into main prefs;
   - set `pref_save_per_lens_settings` as in the source;
   - keep the target's `device_defaults_version` (set it if missing), so `DeviceDefaults` does not overwrite the restored config on the next start;
   - run `SettingsMigration` (`migrateMultiFrame`, `migrateLmcHybrid`, `removeObsolete`) on main and on every written profile, so old configs lose removed keys and get the current default revisions;
   - drop paths to imported files (noise model, DCP) whose file does not exist on the target.
6. **Not enumerated yet:** if the target has no slots yet (`ModuleRegistry.initialize` runs on camera open, e.g. on a fresh install), store the source lenses in a pending file. Run the mapping inside `ModuleRegistry.initialize` once the slots exist.
7. **Old files:**
   - v2.1: no lens info. If the `module_auto_*` IDs exist on this phone, treat it as same-device. Otherwise restore `common` only, map the legacy `per_lens_settings` by Camera ID through `ModuleRegistry.camera(slot)`, and strip `deviceOnly`.
   - Legacy XML keeps its current path.
8. **UI:** after "Восстановить", show a short summary before the restart, e.g. "Основная → Основная, Теле 3.7× → Теле 3× и Перископ 6×, Фронтальная → Фронтальная". "Сохранить" is unchanged except for writing v3.
9. **Code structure:**
   - put the matcher in a pure Java class, e.g. `settings/LensProfileMatcher.java`, with no Android types in its API;
   - `BackupRestoreUtil` builds the passports, calls the matcher and writes the profiles;
   - `ModuleProfiles` gets a method to write a profile for a slot without activating it.
10. **Unit tests (fixtures):**
    - same-device round trip, including a lens switch after the restore;
    - Vivo X200 Ultra → OPPO Find X8 Ultra and back (take real slots and focal lengths from device logs / LensDiscovery);
    - fresh install with no slots, so the pending mapping is used;
    - v2.1 file;
    - a source with fewer lenses than the target;
    - ISZ crop slots on the source only.

### P8 — Dead code already found (optional, same PRs or a follow-up)

- **Unused nodes:** `AEC`, `AWB`, `Bilateral`, `ColorD`, old `ESD3D`, `ExposureFusion`, `Median`, `Equalization`, `Sharpen`.
- **Unused PK getters:** `getHdrPlusSnrLuma/ChromaExp`, `isFullGpuProcessing`, `isNrLuma/ChromaEnabled`, `niceInternalSwitch`, `hybridDefaultOn`, `setVideoResolution`, `loadSettingsForCamera`.
- **SA:** the branch for `pref_vivo_nice_route`, a key that exists nowhere.
- **VCF leftovers:** `VivoNiceAeSnapshot.read` (never called); `mNiceShutterAe` is only ever cleared (CC ~1171/1915/2169/3209), so CC ~3208-3217 and `VivoNiceRequestPlan` are dead.
- **Unreachable processors:** `UnlimitedProcessor` and `RawVideoProcessor` — their modes are not in `userModes`. After P2, `RawVideoProcessor` has no crop tunable either.
- **`SettingsActivity`:** `setHdrxTitle` (key `settings_scope_info` does not exist), `removePreferenceFromScreen` (never called).
- **Bottom-shade spec:** if it is implemented later, drop shade groups for settings removed here.

### P9 — Verification and hand-off

- **Per phase:** compile, unit tests, CI host checks, leftover greps (section 0).
- **At the end:** push only when the owner says. The APK comes only from the GitHub Actions artifact, verified against `SHA256SUMS.txt`.
- **Device checklist for the owner:**
  - Hybrid Photo and Night on main, UW and tele, plus 12/16/20 MP output.
  - SCAM HDR with mosaic `neural` on the 2× and 4× ISZ slots.
  - DNG saved; Ultra HDR on; 16:9 cropped centred; watermark present.
  - Live RAW viewfinder works.
  - Every settings screen opens; no empty screens.
  - Every screen uses the card style of "Камеры и сенсоры", in the user's accent colour.
  - Fresh install and "Сбросить всё" default to the hybrid on every phone (X200 Ultra, OPPO PHY110, PKJ110).
  - Config saved on phone A, restored on phone B: switch every lens and check the summary and the values. Restore on the same phone, switch lenses, values stay. Restore an old 2.1 file.

### P22 — Quad / Tetra remosaic of the Hybrid: more detail (owner's request, 2026-10-06)

- **Today (P14 / P15):** the GCam QuadBayerRgbMerge design, re-implemented in `scam-hybrid.h` (not a port of the GCam 11
  native code): every Quad / Tetra frame splits into b² plain-Bayer sub-frames with composed site offsets, the unchanged
  Bayer merge accumulates them on the 2× / 4× grid, then chroma median and false-colour suppression. Checked on synthetic
  bursts and on the X7 Ultra (1× / 5.9× Quad).
- **Goal:** more fine detail from the mosaic stream inside the normal Hybrid shot (same capture, Bento / Shasta, ARK finish),
  so a module without remosaic is worth shooting.
- **Input:** the owner's handheld shot (one frame with the burst dump). Replay it on the worker and measure before / after:
  - detail: MTF50 / high-band energy on edges and texture crops; lattice amplitude at period 2 / 4 px (`tools/quad/lattice.py`);
  - false colour and zippers (`eval_zipper.py` period-2 component);
  - time and memory.
- **Ideas, in order:** per-site kernel tuned for the sub-frame grid (narrower σ across edges where enough sub-frames agree);
  alignment refined per sub-frame class; site-gain map per tile instead of global; detail-preserving chroma (guided by the
  merged G instead of the median where SNR allows); optional 2× output from the Quad grid.
- **Rule:** the plain-Bayer path stays bit-identical; `kHybMergeMain1` is not edited.
- **Done (2026-10-06):** the owner's handheld shot (X7 Ultra 1x, Quad 4096x3072, 27 frames, 6.6 px shake; burst kept as
  /data/local/tmp/hand.nch on the phone). Found: the Sabre sigmas are in sub-frame px (= b native px), and the merge's detail is
  limited by the kernel, not by the alignment.
  - `mosaicEdgeScale` (default 0.6): narrower kernel across edges and the base kernel for mosaic sub-frames only; the blurred
    kernel of flat areas stays. With `mosaicFrames` 24 (was 16): +14 % energy of the 2-4 px band on text / device crops at the
    flat-area noise of before (0.1245 vs 0.1260); the plain `kernelScale 0.5` gave the same sharpness at +42 % noise. 8.0 s vs 6.8 s.
    Settings: Hybrid → Склейка → «Мозаика без ремозаика» (frames, edge kernel).
  - `mosaicShare` (default 1): the sub-frames of a frame share its local motion (field = motion + site disparity). The site spread
    was estimation noise (0.15-0.2 sub-frame px), but the output barely changed: alignment is not the limit here.
  - Checks: no lattice (`tools/quad/lattice.py`, NaN fix for noise below black); synthetic Quad: edges 41.7 vs 41.8 dB, bars 2-6 px
    13.5 vs 14.6 dB (more aliasing near Nyquist); Tetra untouched on the synthetic burst; plain Bayer bit-identical (md5).
  - Not done: Tetra on the vivo (device), per-tile site gains, guided chroma instead of the median, GCam 11 style per-frame
    edge-directed quad demosaic (kernels in research/gcam11/map/kernels_03/quad_bayer_rgb_merge.cl) as an alternative path.

### P23 — vivo X100 Ultra: broken hybrid photos (owner's log, 2026-10-05 build)

- **Symptom:** every Hybrid JPEG on the X100 Ultra (V2366GA, SM8650) is 3/8 black and 5/8 pink noise; the planner sees
  clip=0.62, the worker "RAW codes up to 65535 with white level 1023", 62.5 % of the cells clipped.
- **Owner's rule:** the fix must work automatically (no setting, no per-phone switch the user has to find).
- **Root cause (log + 3 adversarial reviews, 2026-10-06):** only the ZSL frames of camera 0 (repeating TEMPLATE_PREVIEW
  request) are packed 10-bit data (contiguous 5120-byte rows, MIPI-style by the statistics) behind ImageFormat.RAW_SENSOR
  with unchanged RAW16 metadata (rowStride 8192, 25 165 824 B). Every reader copied them as uint16: rows 0-1919 = codes up
  to 65535, rows 1920-3071 = zeros. The post-shutter frames on the same reader are plain (sharpness 0.01-0.4 vs 1.2-6.6,
  Shasta / Bento statistics), camera 3 is plain. Likely trigger (not verified on bytes): ScamPreview's PD2454 stock
  profile (~80 vendor tags incl. MultiSportStagger, preview.hdr.state 2, rawHdr, sceneMode 0xc80000), applied on every vivo,
  on both routes, every session; all tags were accepted by the X100 Ultra HAL. A 12-bit 4096x2560 payload has the same size,
  so no blind unpacker was written.
- **Done (local, not pushed):**
  - `RawPayloadCheck`: per frame, 32 rows x 256 samples; not plain = ≥ 25 % of samples ≥ 4x (white + 1), or ≥ 5 % with
    ≥ 25 % exact-zero rows; whites > 4095 are not checked. Ring frames that fail are dropped at the shutter
    (`dropNonPlainRaw`); with < 4 left the existing normal-back path takes 4 N + L + S + ES after the shutter. The ZSL clip
    estimate uses the newest plain frame. Post-shutter frames are marked: the hybrid drops a bad Bento / Shasta frame and
    refuses a bad N (Russian error, nothing saved), SCAM HDR refuses; black-from-data skips marked frames.
  - Preview watch: first 4 ring frames of a session, then every 30th. A bad frame while the vivo stock preview profile is on
    turns the profile off for that camera (this process) and restarts the session with the plain Camera2 preview; the live
    RAW viewfinder / mosaic detection skip bad frames. Once per camera a dump goes to Download/SCAMERA/raw-payload-*.zip
    (meta + 256 KB head + 64 KB at 5/8 and 3/4) to read the real layout.
  - SCAM HDR mosaic mode only on a real mosaic (forced block, ISZ sensor mode 5/7, or a confident measured block): the X100
    Ultra main was remosaicked as Tetra 4x4.
  - MONO / NIR cameras (X100 Ultra id 6: configuring it killed the HAL, every id 'unknown device' for 4.5 s) are left out of
    a fresh camera scan and never opened from a lens button (snackbar).
  - Worker: /vendor CRE only when it is byte-identical to the bundled pinned copy; the X100 Ultra's own CRE build failed and
    its preloaded vendor libc++ then broke the bundled one (`__emutls_get_address`), so alignment fell back to translation.
  - Tests: `RawPayloadCheckTest` (8 cases: plain, packed with zero / stale tail, dark packed, 12-bit under white 1023, 16-bit
    whites, black-clamped rows, bad geometry), added to the CI list; OPPO replay: CRE still "bundled".
- **Device check (X100 Ultra):** the log shows "vivo stock preview profile off ... restarted" once; then either the ZSL works
  (profile was the cause: zero shutter lag) or "ZSL: N ring RAWs dropped" + normal-back (4 N after the shutter, noisier).
  Send Download/SCAMERA/raw-payload-*.zip: with real bytes a zero-lag unpacker can follow.
- **Done later (cbabece, local):** camera reopens itself after onError (1/2/4 s, max 3 a minute), per-camera open retries
  0.5-4 s; old JSON backups must match this phone's lenses (id, facing, focal) or its slots / sensor configs / ids / camera
  scan stay; neural remosaic only on SM8750; tuning ini read-only.
- **Open (not done):** `glTexSubImage2D 0x502` on every phone: the effective-frame map never reaches the GPU
  (GL_RED vs GL_R8UI), so the frame-count NR strength map is uniform (a fix changes the look everywhere: owner's call); ISO
  range widened from preview AE on every camera; neural mosaic tried on the v75 NPU.

### P24 — JPEG output: quality setting, always 4:4:4 (owner's request, 2026-10-06)

- **Quality:** a «Качество JPEG» setting in «Конфиг» (key, range and default to fix after the audit of the encoder path),
  applied to the plain JPEG, the Ultra HDR base image and its gain map alike.
- **Measured today (2026-10-06):** the X100 Ultra JPEG and an X7 Ultra JPEG are both 4:2:0 with all-1 quantisation tables
  (quality ~100): the largest files for half the colour resolution.
- **Chroma subsampling 4:4:4 always:** today's encoder path is to be audited (Bitmap.compress, the Ultra HDR / jpegr path);
  Android's stock encoders subsample chroma 4:2:0, which halves the colour resolution of the 2x-grid detail. Use an encoder
  that writes 4:4:4 (e.g. libjpeg-turbo or libultrahdr with 4:4:4 input) for every JPEG the app saves, keeping EXIF, ICC,
  the Ultra HDR XMP / MPF gain-map metadata and the watermark.
- **Checks:** decode the saved files and verify the sampling factors (1x1 for all three components), EXIF / ICC / gain map
  still read by Google Photos; file size and encode time at 12 / 20 / 50 MP against today.
- **Correction (audit 2026-10-06):** the plain JPEG is `Bitmap.compress` q98 (`ImageSaver.JPG_QUALITY`), Ultra HDR q95
  (`UltraHdrEncoder`); the luma q-tables run 1-5, chroma 1-4 (not all 1). 12.6 MP = 3.2-4.5 MB. EXIF bug: `ParseExif.java:133`
  writes the quality (98) into the Compression tag (259); it must be 6.
- **Encoder: jpegli (libjxl's JPEG encoder, BSD-3, libjpeg62 API) via NDK** for the plain JPEG and both Ultra HDR images.
  Ordinary .jpg readable everywhere, 4:4:4, accepts 16-bit / float input; expected 12.6 MP at the same look about 1.6-2.2 MB
  instead of 4.5 MB (estimate; measure on the X200U, incl. 50 MP time).
- **Same phase, cheap:** switch on the lossless-JPEG (LJ92) DNG compression that `dngCreator.cpp` already has (measured -42 %
  on a 12.6 MP CFA); check RawTherapee / Lightroom / darktable read it.
- **JPEG XL (owner's question, replaces HEIC):** later, optional format, never the default. Note for the owner below.
- **Done (c615036, local):** «Качество JPEG» in «Конфиг» (`pref_jpeg_quality` 70-100, default 98) for the plain JPEG, the
  Ultra HDR base and gain map (was 95); EXIF Compression = 6.
- **Done (f7ef82d, local):** jpegli (third_party/, google/jpegli 031a007 + highway 271a9a0) encodes every saved JPEG 4:4:4
  (SOF components 1x1); Bitmap.compress (4:2:0) is the fallback. OPPO, 12.6 MP: q98 4.55 MB / 161 ms (old q98 4:2:0 file
  4.56 MB), q95 2.66 MB / 108 ms; libscameraJpeg.so 523 KB stripped.
- **Done (04cf8c1, local):** «Сжатие DNG без потерь» in «Захват и RAW» (`pref_dng_lossless`, off by default): LJ92 inside the
  DNG, 25.2 -> 14.7 MB at 12.6 MP, +~0.2 s per shot (the DNG is written before the JPEG); LibRaw reads it bit-exact. Owner:
  check Lightroom / darktable / gallery, then it can become the default (or the DNG write moves to the background).

### P24a — JPEG XL as an optional format (research note, 2026-10-06; not scheduled)

- **Possible:** yes, only with a bundled libjxl (NDK, ~1.5-2.5 MB encoder, ~3-4 MB with decoder); Android 14-16 has no JXL
  encoder or decoder.
- **Gives (measured on a 12.6 MP SCAMERA shot, desktop):** distance 1.0 effort 3 = 1.09 MB (-76 % vs today's q98), distance 0.5
  = 2.31 MB (-49 %); true lossless; 16-bit / float output straight from the FP16 pipeline; no chroma subsampling; HDR (PQ/HLG,
  gain map box); lossless JPEG -> JXL transcode -15...-17 % bit-exact; DNG 1.7 JXL lossless 52 % / lossy d0.3 7 % of raw size.
- **Costs:** no system thumbnail, no Google Photos backup, vivo / OPPO / Xiaomi galleries and messengers can't show it;
  ExifInterface can't write it (Exif / XMP boxes by hand); MediaStore `image/jxl` only on Android 15+; our gallery and the
  camera thumbnail need a decoder; encode 0.4-0.8 s at 12 MP, 1.5-3 s at 50 MP (effort 3, estimate); DNG 1.7 JXL is unreadable
  by darktable / RawTherapee.
- **Order:** P24 + jpegli + LJ92 DNG first; then FP16 final pass with half-float readback (feeds jpegli 16-bit or JXL); then
  the optional «Формат: JPEG XL» with «также сохранять JPEG»; DNG 1.7 JXL opt-in last.

### P25 — Quick-settings shade and viewfinder chrome (owner's SHADE_TASK.md, 2026-10-06; done, local)

- **Source:** `Downloads/SHADE_TASK.md` (copy to `docs/settings-plan/SHADE_TASK.md` when the work starts) and the concept
  https://claude.ai/artifact/B8KCemNX8QeNyyR1rGx2fQ. It supersedes `docs/SHADE_SPEC.md` / `docs/shade-sheet.html` (concept E,
  built by the "sheet:" commits 4000a9f..4af0fca, 8968de8); mark those two as superseded.
- **Rules:** UI only. Never switch sensor modes, never touch capture / session / AE / processing; existing hooks may be
  called, not changed (`setPreviewAEModeRebuild`, `applyAeMetering`, `restartCamera`, `invalidateSurfaceView`). Never skip,
  disable or weaken tests. No intermediate APKs, no push until the owner says; the APK comes only from GitHub Actions.
- **Process vs the spec (audit 2026-10-06, HEAD edc8e58):**
  - There is no `settings-cleanup` branch and no `docs/settings-plan/`; the cleanup lives on `migration/nice-camera2` =
    `origin/main`. Branch `shade` from `origin/main`. Push later goes to `main` only (owner rule).
  - The preconditions exist: `SettingsStyle`, `pref_merge_route` (`RouteSelectorPreference`), the Hybrid naming,
    `SettingsMigration.removeObsolete`, the portable config. The config is `ConfigXml` (XML; non-local keys travel in the
    `main` file, `BackupRestoreUtil.java:140`), not a JSON file with a `common` section.
  - P23 / P24 are still open. Start the shade only after them, or on the owner's word (P24 adds «Качество JPEG», a natural
    Формат tile).
  - Steps a-g below, one commit each, then the checks and one short paragraph.
  - Checks per step:
    - compile and unit tests with `-I ../local-tools/compile-installed-sdk.gradle --offline`; the known failure
      `CaptureControllerTest.testGetCameraOutputSize_withTwoParameter` is reported as pre-existing;
    - `python tools/check_settings_model.py`;
    - the new test classes added to the CI `--tests` list (`build-test-apk.yml:113`).

#### What already exists (concept E) and what changes

| Spec item | Today | Work |
|---|---|---|
| Levels HIDDEN / PEEK / FULL | `CameraFragmentModel.SHEET_*`, `SettingsBarLayout` (BottomSheetBehavior in `settings_sheet_container` above `layout_bottombar`) | keep |
| FULL height ~86 % | 0.66 of `layout_viewfinder` (`CameraFragment.java:121, 381-384`), forced EXACTLY (`SettingsBarLayout.java:369-381`) | 0.86 |
| Swipes on the viewfinder ±1 level; FULL list scrolls to the top first | done (`Swipe.java:137-151`, NestedScrollView) | keep |
| `SwipeUp` without `ManualModeConsole` / `ocManual` | done; leftovers only in `Swipe.init()` | optional cleanup |
| `manual_mode` only at HIDDEN | done (`camera_fragment.xml:93`) | re-anchor when the strip moves |
| PEEK = pinned tiles | ≤ 4 round quick buttons + 3 group cells; a 5th pin drops the oldest; pins = SettingType names in `ui_sheet_quick` | grid of ≤ 12 tiles, group cells go |
| FULL = tiles + groups | the PEEK rows fade out; a 3-group accordion | one scroll list: tiles, curated groups, «Другие настройки», «Все настройки» |
| Handle tap | sheet handle FULL→PEEK else →FULL; HIDDEN handle tap →FULL (`SettingsBarLayout.java:216, 486-519`) | spec: HIDDEN→PEEK→FULL→PEEK (question 3) |
| Scrim in FULL | none; a tap in FULL lowers the level AND focuses | new, at root level (question 3) |
| Tile kinds, toast, dimming | list entries only, no toast, unavailable entries hidden, fixed #90C7FF | list / toggle / slider tiles, toast «Имя: значение», 45 % dim with reason |
| Reorder | nothing; every `updateSettingsBar()` rebuilds all views | RecyclerView + GridLayoutManager(4) + ItemTouchHelper, diff-based adapter |
| Any setting pinnable | the ☆ favourites already do it (`FavoriteSettings`, `settings_favorite_keys`, per-lens keys only, restart after each change) | catalog built on the `FavoriteSettings` tree walker (question 9) |
| Lens strip | `aux_buttons_container` on the preview bottom, hidden at PEEK / FULL (`camera_fragment.xml:70-86`) | moves above / into `layout_bottombar`, outside the sheet, usable at every level |
| RAW icons | `ic_raw` / `ic_raw_off` are drawn letters; R+J and RAW share one | three new 24dp vectors (JPEG / RAW / R+J) |

#### Key table (the spec's names → current build)

The settings live in different places now; the curated groups use these keys.

| Spec | Current key | Where / type | Note |
|---|---|---|---|
| flash | `pref_ae_mode_key` | no tree row; 0 torch / 1 off | virtual entry; write via CameraUIController FLASH; there is no «авто» |
| timer | `pref_countdown_timer_key` | no tree row; off / 3 s / 10 s | virtual; top-bar TimerButton follows |
| замер | `pref_ae_metering_std_mode_key` (Camera2, no tree row) or `pref_lmc_hybrid_ark_metering` (ARK slider, Hybrid › Обработка ArkCore › Тонмап) | | question 6 |
| склейка | `pref_merge_route` | root, hybrid / scamhdr | show the effective `mergeRoute()` (MediaTek lock, nice_dev) |
| RAW | `pref_save_raw_key` | no tree row; JPEG / R+J / RAW | virtual; new icons |
| разрешение Hybrid | `pref_lmc_hybrid_output` | Hybrid, list | effective value via the nice_dev override; dimmed on SCAM HDR |
| даунсемплер | `pref_lmc_hybrid_downsampler` | Hybrid, list | short Lanc / Bicub / Area / Bilin |
| Ultra HDR, 16:9, водяной знак | `pref_ultrahdr_key`, `pref_wide169_key`, `pref_show_watermark_key` | Конфиг, switches | 16:9 also changes the viewfinder layout (question 7) |
| Bento, Bento кадров | `pref_lmc_hybrid_bento`, `pref_lmc_hybrid_bento_frames` | Hybrid › Кадры и захват, lists | «Выключено» needs a short label |
| Shasta | `pref_lmc_hybrid_shasta` | Hybrid, switch, on by default | |
| N-кадров (Hybrid) | `pref_lmc_hybrid_zsl_frames` | Hybrid, slider 4-44 | same title as the SCAM HDR row: shortTitle «N-кадров Hybrid» |
| Luma, Chroma | `pref_lmc_hybrid_post_luma` / `_post_chroma` (NLM only) or `pref_lmc_hybrid_dn_luma_mult` / `_dn_chroma_mult` (gcam engine, the default) | Hybrid › Шумоподавление | duplicate titles; question 8 |
| резкость | `pref_lmc_hybrid_sharp_mode` | Hybrid › Обработка ArkCore › Резкость, ark / rt / scam / off | moved |
| тон ARK | `pref_lmc_hybrid_ark_tone` | **removed** (P10, ARK always on) | drop or replace (question 8) |
| отбраковка | `pref_lmc_hybrid_cdm` | Hybrid › Склейка › Отбраковка движения, slider | moved |
| N-кадров (SCAM HDR) | `pref_vivo_nice_zsl_frames` | SCAM HDR, slider 4-50 | dimmed on Hybrid; hidden on MediaTek |
| удлинение L | `pref_vivo_nice_long_boost_ev` | EditTextPreference | not pinnable by the spec's rule; question 8 |
| мозаика ISZ | `pref_vivo_nice_mosaic` | SCAM HDR › Мозаика ISZ, 7 values | «Нейро + Sabre» > 8 characters; 7 segments don't fit at 360dp |
| Exposure Fusion | `pref_vivo_nice_fusion_*` | **removed** (P10) | drop or replace (question 8) |
| кривая Hybrid | `pref_lmc_tone_curve` | Цвет и резкость › Кривые Hybrid, 77 entries | opens the list sheet instead of cycling |
| USM | `pref_sharp_usm_enabled_key` | Цвет и резкость › Резкость RawTherapee | a no-op with sharp_mode = ark; `SettingsModelCheck.java:47,62` asserts it stays active |
| сетка | `pref_show_grid_key` | Видоискатель, 0-4 | short Выкл / 3×3 / 4×4 / Φ / Диаг |
| пик фокуса | `pref_peak_method_key` | Видоискатель, Off / On / Auto | needs Russian short labels |
| живой RAW | `pref_live_viewfinder_raw_key` | Видоискатель, switch | read at session build: restart or «после перезапуска» |
| отладка | `pref_show_afdata_key` | Видоискатель, 0-3 | «HUD + гист.» > 8 characters |
| `pref_shade_tiles` | `ui_shade_tiles` (proposed) | ordered list, max 12 | a `pref_` key is per-lens when per-lens mode is on (`ModuleProfiles.isLocal`); old pins: `ui_sheet_quick` |

#### Steps (owner's order a-g, adapted)

- **a) Data model.**
  - `ShadeCatalog`, built on the `FavoriteSettings` walker without its local-only filter:
    - tree keys plus virtual entries (flash, timer, RAW, Camera2 metering);
    - per key: kind, shortTitle, shortEntries (≤ 8 characters, explicit exemptions), icon, section path, session-time flag;
    - excluded: sensor-config, device-only, DCP and spoof keys; the runtime visibility rules of `SettingsActivity`
      (MediaTek SCAM HDR, Xiaomi-only rows) are replicated.
  - Storage in `ui_shade_tiles`:
    - default `pref_ae_mode_key`, `pref_countdown_timer_key`, `pref_save_raw_key`, `pref_merge_route`,
      `pref_lmc_hybrid_output`, `pref_show_grid_key`, `pref_ultrahdr_key`, `pref_lmc_hybrid_bento`;
    - max 12; drop on read with the same catalog; a `removeObsolete` block; travels in the config `main` file.
  - The effective-route / MediaTek / dependency logic sits in `ShadeCatalog`, not in `SettingsAvailability` (the CI host
    check compiles it standalone).
- **b) Levels and gestures.**
  - FULL 0.86; PEEK = tile grid only; the scrim; the handle cycle.
  - Back order: catalog → drag → edit mode → one level down.
  - `setDraggable(false)` while a tile is pressed and in edit mode, together with the burst lock.
- **c) Tiles.**
  - 4 columns of square `CARD` + `LINE` tiles with a 10dp gap; a dashed «Добавить» tile while < 12; the 13th pin is refused
    with a toast.
  - Each tile shows the icon of the current value, a short value and the name.
  - Highlight: accent + `INK`; defaults are looked up DeviceDefaults → XML → `setDefaults`.
  - Tap: list = next value, toggle = flip, slider = an inline card. Long lists open the list sheet.
  - Toast «Имя: значение»; unavailable tiles are dimmed to 45 % and a tap shows the reason.
  - Writes go through the existing paths: CameraUIController for virtual keys, a `FavoriteSettings`-style `write()` for tree
    keys.
  - One listener registered via `SettingsManager.addListener` (strong reference). While `ModuleProfiles.isApplying()`, the
    tiles refresh once, never mid-drag.
- **d) Catalog.**
  - Full-screen overlay inside CameraFragment, camera running; an Activity would close the camera and drop the shade to
    HIDDEN.
  - Header «SCAMERA» / «Добавить в шторку», search over title + section, results grouped by section.
  - Each row: icon, title, current value, «Добавить» / «В шторке»; counter «В шторке N из 12».
  - The tree is inflated lazily on the first open. `SettingsSearchFragment.index` stays unchanged (`ApprovedConceptsTest`).
- **e) Reorder.**
  - Long press ~400 ms + haptic lifts the tile; «Изменить» → edit mode with a ~120 ms press-drag.
  - Edit mode: wobble (off when the animator scale is 0), × to unpin, header «Перетащи плитки» / «Готово».
  - `notifyItemMoved` only, the order is saved on drop; TalkBack actions: earlier / later / unpin.
- **f) Viewfinder chrome.**
  - Preview frame: 26dp, `LINE` stroke, corner marks, grid lines ~15 % white.
  - Top bar: route badge (effective value), format badge (new icons), 44dp gear card.
  - Lens strip: a `CARD` + `LINE` pill, active lens in accent + `INK`, decimal comma «2,5×». It moves outside the sheet, so
    the hide rule and the handle pass-through go away.
  - Zoom ruler: a small card above the strip.
  - Bottom bar ×1.15 and the three-column bottom row (gallery card | shutter | «Фото | Ночь»).
  - Three entry points to settings, all `launchSettings()`: top-bar gear, shade-header gear, «Все настройки» card with a new
    summary string in both locales (no SCAM HDR on MediaTek); reuse `sheet_all_settings`.
  - Card-style hints and toasts.
- **g) Migration and tests.**
  - Map `ui_sheet_quick` once: FLASH→`pref_ae_mode_key`, TIMER→`pref_countdown_timer_key`, RAW→`pref_save_raw_key`,
    GRID→`pref_show_grid_key`, AE_METERING_STD→`pref_ae_metering_std_mode_key`, HYBRID_OUTPUT→`pref_lmc_hybrid_output`,
    HYBRID_DOWNSAMPLER→`pref_lmc_hybrid_downsampler`. Drop the other names, then delete `ui_sheet_quick`.
  - **Keep the `resetRemovedSettings` resets (HDRX, EIS, Quad, FPS, bracketing) outside the shade**, e.g. in
    `SettingsMigration`: capture still reads EIS, FPS and Quad, and a stored Quad = true would switch the sensor mode.
  - Tests:
    - `ShadeCatalog`: curated tree keys exist in the tree; virtual keys exempt by an explicit list; every key has an icon;
      short labels ≤ 8 characters per locale with explicit exemptions;
    - list round-trip, drop on read, max 12;
    - config round-trip;
    - Robolectric: highlight for non-default values, the catalog search «Luma» finds `pref_lmc_hybrid_post_luma`.

#### Owner's answers (2026-10-06) — these override the spec where they differ

1. **Base and timing:** branch `shade` from `origin/main`; start after the X100 Ultra fix (P23) is pushed, P24 waits;
   copy SHADE_TASK.md and this plan into `docs/settings-plan/`.
2. **Lens strip:** behaviour exactly as today (every horizontal drag zooms, no flick to the next lens); the look must not
   differ from the concept. While the shade is open the zoom ruler sits above the strip and never over the tiles.
3. **Handle and scrim:** handle tap as in the concept: HIDDEN → PEEK → FULL → HIDDEN. Scrim in FULL: yes (a tap on the
   viewfinder in FULL only lowers the shade, no focus, no pinch zoom while FULL). FULL = 86 % of the viewfinder height.
4. **Viewfinder chrome:**
   - the bottom bar is **not** made taller and its content is not scaled (spec item "15 % taller" dropped);
   - the **Quad toggle is removed** from the top bar (the stored `pref_quad_bayer_key` stays reset to false);
   - the **Night mode is removed** (owner's decision): `CameraMode.userModes()` becomes Photo only, a saved Night mode
     migrates to Photo, the «Фото | Ночь» switch goes; in its place on the right of the bottom row sits the
     **front / back switch** (`flip_camera_button` moves there). Today Night differs from Photo only by the gyro tripod
     detection (`Gyro.getTripod()`: OIS off on a tripod with OIS «Smart auto», legacy IsoExpoSelector tripod rule); both
     merges are the same. Default: tripod detection moves to Photo (owner to confirm);
   - timer, flash, grid become shade tiles; gear top right; `ViewfinderUiTest` numbers updated to the new layout (no
     weaker assertions; the mode-tab assertions become front-switch assertions);
   - shutter: ring with a filled centre (concept), all states and the frame counter;
   - preview frame on the edge, preview full-width.
5. **Tiles:** highlight = differs from the default (lists, sliders and toggles alike); toggle tiles show «Вкл./Выкл.»; flash
   keeps its 2 values; accent = the camera accent (`AccentPalette.camera`); hardware-unavailable tiles are dimmed, a tap shows
   the reason.
6. **«Замер»** in Съёмка = the ARK slider `pref_lmc_hybrid_ark_metering` (slider tile); Camera2 metering
   `pref_ae_metering_std_mode_key` stays a virtual catalog entry.
7. **Session-time tiles** (живой RAW, 16:9): restart the camera at once through the existing `restartCamera`.
8. **Curated rows:** «тон ARK» and «Exposure Fusion» dropped; «удлинение L» as a 0-2 slider from SettingsNumericRules;
   Luma / Chroma = `pref_lmc_hybrid_dn_luma_mult` / `pref_lmc_hybrid_dn_chroma_mult`; USM stays the RT switch.
9. **☆ favourites:** removed; its keys migrate into the shade list (max 12), its settings row and fragment go.
10. **Storage:** `ui_shade_tiles`; first value = the user's old pins, then the 8 defaults (max 12).
11. **Smaller:** a lens switch ends edit mode and closes the slider card; the shade level is restored after settings; the
    first-run hint shows once; extra toasts as in the concept except «Объектив 2,5×»; bilingual search keywords; landscape
    rotates tile icons only; decimal comma on tiles and the strip; new tests go into the CI list.

#### Questions to the owner asked 2026-10-06 (answered above)

1. **Base and timing:** branch from `origin/main` (there is no `settings-cleanup`)? Start before P23 / P24 are done? Copy the
   plan into `docs/settings-plan/` too?
2. **Lens gestures (stop rule):**
   - «a quick swipe = next lens» doesn't exist today: zoomDrag is always on (`CameraFragment.java:346`), so every horizontal
     drag zooms and the ≥ 24dp branch (`AuxButtonsLayout.java:277-282`) never runs;
   - the concept's 140 ms flick / zoom split and its drag direction differ from the code. Keep the code exactly (my advice),
     or add the flick?
   - the ruler card over PEEK / FULL would swallow tile taps for 1.6 s.
3. **Handle and scrim:** the handle cycle (spec FULL→PEEK, concept FULL→HIDDEN, today HIDDEN→FULL). A scrim in FULL disables
   tap-to-focus and pinch zoom. The top bar lies outside `camera_container`, so the scrim has to be added at root level to
   cover the gear. FULL = 86 % of the viewfinder (spec) or of the screen minus the bottom bar (concept)?
4. **Viewfinder chrome:**
   - «bottom bar 15 % taller» is undefined: the bar fills whatever the 3:4 preview leaves, and on ≤ 16:9 screens or with
     16:9 on the preview can't move up. Proposal: content ×1.15 with a minimum bar height.
   - The new bottom row has no flip-camera button. Where does it go?
   - A 168dp mode switch ×1.15 ≈ 193dp doesn't fit the ~106dp side column at 360dp (ellipsis stop rule).
   - The top bar has no place for the timer / flash / grid / Quad buttons. Quad switches the sensor mode, so it cannot be
     moved into the shade.
   - `ViewfinderUiTest` (72dp shutter, square timer button, the 172px fixture) must be updated to the new sizes: OK
     (updated, not weakened)?
   - The shutter «ring with a filled centre» reverses the approved flat disc.
   - The concept's inset preview frame would hide part of the preview: keep it full-bleed?
5. **Tiles:**
   - toggle highlight rule: «on» (spec) or «differs from default» (concept; watermark and Shasta are on by default)?
   - value text «Вкл./Выкл.» on toggle tiles?
   - flash has only 2 values (torch / off): adding «авто» would change AE;
   - the accent: settings (lavender) or camera (yellow)?
   - hide or dim tiles that are blocked by hardware (no flash on a lens)?
6. **«замер»:** Camera2 metering or the ARK slider? If ARK, Camera2 metering stays as a virtual catalog entry (its only UI).
7. **Session-time tiles** (живой RAW, 16:9): restart the camera via the existing `restartCamera` (still UI-only?) or show
   «применится после перезапуска»?
8. **Curated rows:**
   - replacements for the removed «тон ARK» and «Exposure Fusion» (e.g. `pref_lmc_hybrid_ark_detail_gain`,
     `pref_nice_zsl_long`) or drop them;
   - «удлинение L» (EditText): exclude it or show a 0-2 slider;
   - Luma / Chroma: `post_*` (NLM) or `dn_*_mult` (gcam engine, the default);
   - USM stays the RT switch (a no-op with ARK sharpening).
9. **☆ favourites:** remove (migrating its keys into the shade list) or keep both?
10. **Storage key:** `ui_shade_tiles` instead of `pref_shade_tiles` (otherwise per-lens). Precedence when old pins exist: the
    user's ≤ 4 pins only / pins then the 8 defaults / the 8 defaults.
11. **Smaller:**
    - a lens switch ends edit mode and closes the slider card?
    - restore the shade level after returning from settings?
    - first-run hint once or every launch; the concept's extra toasts (added / removed / place N of M, «Объектив 2,5×»);
    - bilingual search keywords («люма» → Luma);
    - landscape: rotate tile icons and labels?
    - decimal comma on tiles and the strip;
    - OK to extend the CI test list?

#### Device checklist (end of the phase)

- Long-press drag on a real phone; edit-mode drag.
- Swipes between levels; a scrolled FULL list scrolls up before lowering.
- Lens strip at every level: tap, drag-zoom, ruler; a horizontal move never changes the level.
- No ellipsis at 360dp in both locales.
- The shade never covers the shutter, the strip or the mode switch.
- Shade ↔ settings stay in sync both ways; lens switch with per-lens mode on.
- Config save / restore keeps the tile order.

#### Done (2026-10-06, branch `shade` from 0bf8d62, local, not pushed): ef96bd0 a, 468fa85 b, 85781e0 c, b667e68 d, f22deae e, 3766a45 f, a2dd53e g

- **a) Data model:**
  - `settings/ShadeCatalog`: the virtual entries (flash, timer, format, Camera2 metering) plus the inflated settings tree with
    the tunables, walked with a data store that reads defaults and writes nothing (built once per process).
  - Per key: kind, short title, short values, icons, section path, default (DeviceDefaults, then XML / tunable /
    setDefaults), dependency, session-time flag.
  - The curated groups follow the key table with the owner's answers; 8 default tiles.
  - Not pinnable: sensor configs, device-only, DCP, spoof, theme, Quad, free text except «удлинение L», actions, screens.
  - The MediaTek / Xiaomi visibility rules, the effective route, scam_dev.txt and the dependencies live in ShadeCatalog.
  - `settings/ShadeTiles`: `ui_shade_tiles`, at most 12, unknown keys and repeats dropped on read, a `removeObsolete`
    block; it travels in the config's main file.
  - Short values in both locales (at most 8 characters, except «Нейро+Sabre»). New 24dp icons: JPEG, RAW, R+J,
    metering, focus, plus, slash.
- **b) Levels:**
  - FULL is 86 %; PEEK shows only the tiles.
  - The handle goes HIDDEN -> PEEK -> FULL -> HIDDEN.
  - Scrim in two pieces, over the viewfinder and over the top bar; under FULL there is no focus and no pinch.
- **c) Tiles:**
  - Grid: RecyclerView with GridLayoutManager(4), DiffUtil, rebinds in place.
  - Tile kinds: list (next value), toggle («Вкл./Выкл.»), slider (inline card). Lists with more than 5 values open the
    list sheet (`SettingsStyle.optionSheet`, now shared with ListPreference).
  - A toast «Имя: значение» after each change. An unavailable tile is dimmed to 45 % and a tap shows the reason.
  - Writes: virtual keys through CameraUIController, tree keys through the settings storage; session-time keys (live RAW,
    16:9, RAW stream format, ZSL buffer, AF mode, preview format) restart the camera.
  - FULL rows: a segmented control, switch or slider per setting, plus a pin.
  - One settings listener for the shade.
  - The old settings bar classes and their resources are gone. `resetRemovedSettings` moved unchanged to
    `SettingsMigration` and still runs when the camera screen is built.
- **d) Catalog:** a full-screen overlay «Добавить в шторку»: search with keywords in both languages, sections, counter,
  «Добавить» / «В шторке». It opens from the «Добавить» tile and from «Другие настройки».
- **e) Reorder:**
  - Long press (~400 ms + haptic) or «Изменить», then a ~120 ms press-drag.
  - ItemTouchHelper with notifyItemMoved only; the order is saved on the drop; the grid never auto-scrolls.
  - Edit mode: wobble (off when animations are off), ×, TalkBack «Раньше / Позже / Убрать».
  - Back order: catalog -> drag -> edit mode -> one level down. A lens switch and HIDDEN end the edit mode.
- **f) Chrome:**
  - Preview: a 26dp frame (the screen background is BG now, was black), LINE stroke, corner marks, grid at 15 % white.
  - Top bar: route badge (effective value), format badge, 44dp gear.
  - Strip: a CARD + LINE pill in the bottom bar with decimal commas; the zoom ruler is a small card in a slot above it.
    The hide rule and the pass-through are gone.
  - Shutter row: ring shutter (pressed / self-timer / busy states); gallery card | shutter | front / back switch.
  - Night removed: a stored Night reads and is stored as Photo, and the tripod detection runs in Photo.
  - The Quad toggle went together with its tunable and the page «Кнопки видоискателя» (the stored value is obsolete).
  - Three settings entries; the shade level is restored after the settings.
  - Card toasts; the first-run hint shows once.
- **g) Migration and tests:**
  - `ui_sheet_quick` and the ☆ favourites become the first value of `ui_shade_tiles`: old pins, then favourites, then the
    defaults. It runs in SettingsManager and in a config restore, before `removeObsolete`.
  - The ☆ button, `FavoriteSettings`, its fragment and its settings row are removed.
  - New tests, also on the CI list: `ShadeCatalogTest` (7), `ShadeTilesTest` (6, incl. config round-trip on the same and
    another phone), `ShadeUiTest` (10 Robolectric: render, highlight, dimming, slider card, max 12, catalog «Luma», long
    press and edit drag, TalkBack, settings entries, Russian at 360dp without ellipsis).
  - `ViewfinderUiTest` and `SettingsMenuTest` follow the new UI; each removed assertion was replaced by an equally strict
    one (listed in f's commit message).
- **Checks:** unit tests 206, only the known `CaptureControllerTest.testGetCameraOutputSize_withTwoParameter` fails;
  `check_settings_model.py` PASS. No APK built.
- **Decided here (owner may change):**
  - Shade strings in both locales (values = English, values-ru = Russian).
  - The settings tree is inflated when the camera screen is idle, because tree tiles need it at PEEK. The plan said:
    on the first catalog open.
  - The session-time key set listed in c.
  - The long-list threshold is more than 5 values.
  - A slider writes on release.
  - «A tile pressed -> not draggable» is read as «a tile lifted»: a plain press on a tile still swipes the sheet, as in
    the concept.
  - The ruler slot is reserved inside the bottom bar. On 16:9 phones, and with the 16:9 setting on, the slot sticks out
    above the bar, behind the shade.
  - The badges are not clickable.
  - The HIDDEN handle is the sheet's full-width 28dp top edge.
  - Switch tiles that are off show a struck-through icon.
- **Device check (in addition to the checklist above):**
  - The first open of the shade: inflating the tree costs time once.
  - Ring shutter states and the frame counter.
  - Session-time tiles restart the camera without a sensor-mode switch.
  - Tripod / OIS in Photo.
  - Badges on notch phones.
  - Settings level restore.
  - The first-run hint shows once.
  - Landscape tile icons.
  - TalkBack actions.
  - Card toasts.

### P26 — vivo X200 Pro (pd2405): every Hybrid shot fails (owner's log 2026-10-06; not started)

- **Device:** vivo X200 Pro, `pd2405`, MediaTek Dimensity 9400, Mali GPU. Old build (worker v30 / hybrid v10, toast
  "HDRX failed at LMC hybrid merge — IllegalStateException: NICE capture failed: …", the legacy ESD4D merge still present).
  Log: `Desktop/log-2026-10-06.txt` (10 017 lines).
- **Symptom:** all 5 Hybrid shots (11:57:17, 11:58:21, 11:59:00, 12:02:15, 13:34:31) end with «LMC-гибрид: склейка не
  завершена»: the worker reads the burst, runs Shasta / Bento / local alignment (1.3 s), prints "HYBRID FRAMES", the Mali
  driver logs "Could not open module param file /sys/module/mali_kbase/parameters/large_page_conf" (GPU context for the
  merge) and 0.3 s later the client sees the worker gone without "NICE CAPTURE OK". The client does not log the exit code
  or signal, so the crash point is unknown: most likely the GPU merge (shader compile / dispatch / buffer limits on Mali;
  every test so far ran on Adreno).
- **Also in the log:**
  - `Bundled CRE load failed: … __emutls_get_address` on every shot (vendor CRE of another build): fixed by P23 (986fbc6).
  - ZSL N frames dropped by the plan check (`не совпали с планом SCAMERA (slot 0: ISO 418/659 …)`): the shot went on
    (W only). The 13:34 night shot asked for ISO 12000 / 80 ms and got ISO 2738 / 50 ms (2.81 EV): the planner asks for
    values the sensor does not deliver (see P27).
  - `Selected remosaic backend is unavailable; select SCAMERA in settings` (11:56:41, legacy ESD4D path, removed in P4).
- **Steps:**
  1. Client: log the worker's exit code / signal and the last stderr lines on every failed job (ScamNeuralClient:297).
  2. Worker: every GL stage of the hybrid checks shader compile / link logs, `glGetError` after dispatch and the
     `GL_MAX_COMPUTE_*` / SSBO size limits, and reports a readable error instead of dying.
  3. Reproduce on Mali: owner's X200 Pro on adb (replay the burst `nice_burst.bin` with the worker, like rp.sh on the OPPO).
  4. Fix the failing stage for Mali; a fallback that always saves a photo when the GPU merge fails (P27).
- **Check:** Hybrid Photo on the X200 Pro main / UW / tele saves a photo; CRE "bundled (vendor CRE is another build)".
- **Done (cbabece, local):** step 1 (WORKER EXIT: code / signal) and the first part of step 2 ("HYBRID GPU: context …" right
  after the GL context, and off Adreno one line per program before it compiles). The next X200 Pro log names the failing step.

### P27 — A shot never fails on a plan mismatch or a lost frame (owner's request, 2026-10-06; done, local)

- **Owner:** «Сделай так чтобы "Exception NICE: incomplete result 7/7 LMC-гибрид выдержка/iso RAW не совпали с планом гибрида
  Long iso 320/640 …" подобных ошибок не было в принципе ни при каких условиях».
- **Audit:** workflow, 3 auditors + critic; full text in the session scratchpad `p27_audits.txt` / `p27_critic.txt`.
  - The quoted toast is the pre-91846fc HybridPlan.verify. Since 91846fc those LONG frames were dropped silently (the HAL
    capped the gain at N's: they are N exposures).
  - The fatal member left was the Hybrid falling into SCAM HDR's strict normal-back when fewer than 4 ring frames matched
    within 0.05 EV (X300 Ultra 2026-10-06: AE ramp after a module switch, 'NICE: incomplete results 3/7; RAW buffer lost').
- **Done:**
  - **Every capture series is tolerant** (`ScamCaptureSequence`, hybrid ZSL, hybrid normal-back `hybridFuture`, SCAM HDR
    ZSL and normal-back):
    - a lost / failed / foreign / duplicate / invalid result or a missing / duplicate / null RAW drops that frame only, with
      every reason kept;
    - the series fails only when no RAW arrived.
  - **Roles by measured exposure (Hybrid):**
    - `HybridPlan.classify`: within 0.4 EV the planned role (plans that worked merge as before);
    - otherwise ×1.5+ → bracketed, ×0.5- → ultrashort, ×0.71..×1.035 → N, else dropped.
    - The role reaches the frame (`ImageFrame.measuredRole`).
  - **Hybrid N without the SCAM HDR planner** (`hybridNPlan`, `ScamStockAe.Plan.nOnly`).
    - Ring selection (`selectHybridRing`): frames at the newest frame's exposure first; widened only to fill
      min(requested, 8), only darker, down to −0.5 EV; never emptied while one frame is left.
    - Empty ring → `HybridPlan.buildNormalBack` (4 N after the shutter + Bento / Shasta).
    - No N known at all → AE frames; a plan that cannot be built → one N frame.
  - **SCAM HDR:**
    - no S/ES plan (bright scene, no N) → the shot is captured and merged by the Hybrid;
    - a mismatch keeps the frame;
    - an L beyond the sensor in normal-back → 6 requests + synthetic L;
    - S / ES slotted by measured exposure;
    - Java's exposure window = the worker's (x1/256..256);
    - any SCAM HDR failure (frames, ScamMosaic, worker) → Hybrid merge of the same frames.
  - **ScamHybridBurst:**
    - drops unusable frames (no data / role / exposure, packed, out of range) instead of failing;
    - no N → the closest frame is the base;
    - LONG / short frames at the N exposure are merged as N;
    - noise profile fallback (another frame scaled by ISO, the built-in model);
    - p.quadCfa ignored (the stream is measured).
  - **Merge failure tiers:**
    1. conservative retry (sensor grid, 16 N, localAlign / sabre61 / rim / chromaDiff off);
    2. if the worker died before the GPU context (X200 Pro Mali) or timed out: CPU single-frame bilinear (Quad / Tetra
       aware), and the GPU is marked unusable for the session.
    The report keeps the first attempt.
  - **Lifecycle:**
    - one completion per shot (`shotDone`) with its own saver;
    - camera close / error / disconnect, abort, submit failure and a watchdog (4 s + 3× planned exposure) complete the shot
      with what arrived and free the shutter;
    - ZSL frames are copied before the reader can close;
    - routing kept during a session restart, payload restart deferred to the end of the shot.
  - **Other:**
    - route per queued shot (`detachForQueue(hybrid)`);
    - a DNG failure (I/O or RuntimeException) costs only the DNG;
    - frames without data / role / size leave HdrxProcessor's burst; the reference falls back to the frame closest to N;
    - 16:9 height even;
    - per-frame `capture failed` / `buffer lost` log lines.
- **Tests:**
  - `check_scam_capture_sequence.py` (56 checks; tolerant cases incl. the X300 Ultra shape; strict constructor still
    rejects) now in CI;
  - `HybridPlanTest` (classify, unchanged X200 Pro plans, normal-back, AE);
  - `ScamHybridBurstRolesTest` (LONG at N merged as N, promoted base, drops, noise fallback);
  - `CameraResumeTest` +3 (X300 Ultra ring ramp, darker-only widening, one frame).
  - Unit tests: 183, only the known `testGetCameraOutputSize_withTwoParameter` fails.
- **Done later (2026-10-06, local e63687c):** learned honoured ISO / shutter limits (`ExposureLimits`: two clamped results
  at an exact shutter set a cap, an honoured request at or above it clears it; the Hybrid planners use the capped ranges).
- **Decided, no change (critic item 10 / M8, press queue when memory is low):** the press is not queued. A press while the
  previous shot is still processing, before the current-session preview exists after a resume, or when memory is low is
  rejected with a message («Предыдущий снимок ещё обрабатывается…», «Камера ещё готовится. Повторите снимок.»), so no press is
  lost silently; `CameraResumeTest` encodes this. A queued press would fire at an unknown later moment with stale framing.
- **Done later (2026-10-06, local):**
  - 8dd0cc7: a direct Quad Bayer stream («scamera_quad_bayer_enabled»: Parameters cfaPattern -2) is merged by the hybrid with
    baseCfaPattern (H14 was incomplete: every such shot was refused);
  - 66956b7: X300 Ultra 'first requests lost after the flush' (M6): per-camera FlushLossStats logs every series; two flushed
    series in a row with the leading requests lost → that camera skips the flush (FlushLossStatsTest in CI);
  - RAW > 16 MP: replaced by the owner's «гибридная склейка принимает любое разрешение» (branch anyres: no MP cap, 64-bit
    sizes, GPU max-texture checks, 2x grid only where it fits, 2x2 binning only past hard limits).
  - 01b061e: worker GAIN CHECK as an optional ratio source (tuning gainMeasured / hybrid_gain_measured, default 0 = report
    only; per sub-frame on a mosaic; tools/check_hybrid_measured_gain.cpp in CI) and the ring tier statistics in every shot.
    Switch the default to 1 only after device logs show GAIN CHECK within 5 % on frames that matched their plan.
- **All P27 items done** (2026-10-07).
- **Device check:**
  - X300 Ultra / X200 Pro: shots after a lens switch and at night;
  - X200 Pro: 'Hybrid fallback=cpu-single' once, then no worker attempt;
  - log lines 'hybrid: LONG #i delivered x1.000 … used as NORMAL', 'hybrid ZSL: … widened', 'SCAM HDR fallback=hybrid'.

### P28 — RAW CA correction as in PC RAW / SABRE 6.1 (owner's request, 2026-10-06; done on branch rawca, device check pending)

- **Reference:** the owner's `Desktop/RAW_CA_Correction_SABRE.md` = RawTherapee `rtengine/CA_correct_RT.cc` (GPL-3, same licence
  as SCAMERA; downloaded to the scratchpad, 1383 lines): tiles 128 with border 8 / 16, G estimated at R/B sites with
  directional weights, per-tile R/B dx/dy, neighbour / median rejection, 2D polynomial fit (16 coefficients, 4 when few
  blocks, abort below the minimum), shifts clamped to ±3.99 px, correction through the colour difference to G with gradient
  weights and an overshoot guard (`oldDiff × newDiff < 0`), multi-pass auto, manual radial Red / Blue, `avoidColourshift`,
  G1 / G2 untouched, back to uint16 sensor codes.
- **Today (P19, 84af245):** a radial model `d = (k1 + k2 r²) p` measured on the base frame, R / B of the merged RGB shifted
  once (bilinear) after the merge; mosaic streams not corrected.
- **Plan (default 2a, owner may change):**
  1. Port `CA_correct_RT` (estimation + fit + colour-difference correction) into the worker (C++); build the original file on
     the desktop as the parity reference (same RAW in, compare coefficients, local shifts, R / B CFA samples).
  2. Estimate once per burst on the base frame (all frames share the lens CA; multi-pass auto), correct every frame of the
     burst on the GPU before Align / Merge (colour difference to G, overshoot guard, avoid colour shift); ~1.5 s for 27
     frames measured for a per-frame pass in P19 — the budget to beat.
  3. Replace the P19 post-merge shift when 2 is on; settings «Hybrid → Склейка → Хроматическая аберрация RAW»: auto / manual
     Red / Blue / passes / avoid colour shift (keys `pref_lmc_hybrid_rawca_*`, numeric rules, settings model check).
  4. Mosaic streams: estimate and correct on the plain-Bayer sub-frames (P22 path).
- **Data:** bursts with clear CA (high-contrast edges in the corners of the ultrawide: wires / branches against the sky,
  window frames) from the X200 Ultra and the OPPO; the P19 X7 Ultra ultrawide burst and `hand.nch` to start.
- **Checks:** parity with the C++ reference; outer-ring R / B shift before / after (tools/quad/measure_raw_ca.py); no new
  fringes of the opposite sign; time per burst; plain-Bayer output bit-identical with the setting off.
- **Done (2026-10-06; branch `rawca` in `SCAMERA-PC/wt-rawca`, local commits 948e9d7, f017eed, 60fe990, not pushed).** Owner's
  decision: both variants, selectable, default off.
  - **Port:** `app/src/main/cpp/scam-rawca.h` = CA_correct_RT (RT's scalar path line by line, with RT's buffer layout and
    quirks), standard library only. Burst extensions:
    - fits / avoid-colour-shift factors carried from the base frame to the other frames;
    - uint16 ↔ RT's working scale with a grey-world WB (the NCH has no WB);
    - clipped and unchanged sites keep their codes; G1 / G2 are never written.
  - **Modes (worker key `rawCa`, default 0 = off; P19 runs as before):**
    - 1 "base": RT's auto fit of the base frame (or manual red / blue). After the merge, one `correctRgb` per fitted pass moves
      R / B of the merged RGB onto G (RT's colour-difference rule on the dense grid). Replaces P19's shift.
    - 2 "frames": the same estimate. Then every frame (base included) gets RT's correction pass with the base's fits and factors,
      before alignment / merge. This runs as a new GLES 3.1 compute pre-pass (`scam-rawca-gpu.h`, own EGL context), with
      the CPU port as fallback. The corrected copies go to a recursive `hybridReconstruct` with `rawCa 0`.
    - Mosaic streams (P14 / P22): both modes run on the plain-Bayer sub-frames (estimate on sub-frame 0 of the base; the
      sub-frames are corrected in place).
    - Either mode skips P19 (`caCorrect && rawCa == 0`).
  - **Keys and settings:**
    - worker: `rawCa`, `rawCaAuto` 1, `rawCaPasses` 2, `rawCaRed` / `rawCaBlue` 0 (RT's manual, px at the frame edge),
      `rawCaAvoidShift` 1, `rawCaGpu` 1;
    - UI: «Hybrid → Склейка → Хроматическая аберрация RAW» (`pref_lmc_hybrid_rawca_mode|auto|passes|red|blue|avoid_shift`), with
      numeric rules, availability rules and the settings model check.
  - **Parity** (`tools/rawca/run_parity.py`): the unmodified `CA_correct_RT.cc` with minimal stubs (zig c++ on the desktop)
    against the port.
    - Inputs, 12 RAWs:
      - 7 synthetic with known lateral CA: four CFA phases, 12 MP, RT's pass-one border-overrun size 1574×1236, the
        4-coefficient case 640×480, 320×256, and no CA;
      - 5 real: X7U ultrawide `research/ca19/uw.nch` frames 0 + 1, OPPO payload N000 + N001, three 12 MP DNGs.
    - Each input runs 6 configurations (auto 1 / 2 passes ± avoid colour shift, manual ± avoid), plus fitParamsIn and 1 vs 16
      threads.
    - **All bit-exact:** output plane, whole work buffer (G at R / B, corrected R / B, block weights, per-tile shifts) and the
      coefficients.
    - Informative: RT's own SSE2 path differs from its scalar path (summation order): coefficients ≤ 6e-4 relative, per-tile
      shifts ≤ 0.04 px, isolated output samples.
  - **GPU vs CPU port** (`tools/rawca/check_gpu.py`: the same GLSL through desktop GL 4.3; NDK glslc ES 3.10 compile OK):
    ≤ 0.01 % of R / B sites differ (float flips of RT's guards), max 11 codes; real UW frame: 6 of 6.3 M sites.
  - **Accuracy against synthetic truth** (12 MP; truth R +1.6 / B −0.9 px at the corner):
    - RAW, RMS at r > 0.6: R 122 → 66 / 53 / 50 codes with 1 / 2 / 3 passes, B 90 → 63. Measured residual radial shift
      (P19's measure): R +0.69 → +0.13 px, B −0.42 → −0.08 px.
    - Base mode on a merged RGB: edge RMS R 257 → 72, B 171 → 86; residual R +0.07 / B −0.02 px at the corner. The passes in
      sequence beat the summed field (R 99) and pass 1 alone (R 104).
  - **Real bursts (finding):** RT's auto estimate under-corrects in dark scenes with sparse edges.
    - UW ship interior: R outer radial +0.71 → +0.47 px, B +0.24 → +0.15. P19 had measured 0.61 → 0.14 on the merged result.
    - OPPO main payload: R +0.28 → +0.29; B corner −0.98 → −0.47.
    - Cause: RT feeds the fit the 3×3 median of block shifts and ignores the block weights. Flat-area blocks (shift ~0)
      outvote the few strong edges, whose weighted mean (−0.65..−0.81 px) matches the LK measurement. A weight-aware median
      (scratch experiment) gives UW +0.23 px, but then the 16-coefficient polynomial runs to the ±3.99 px clamp in the
      corners. Not changed (faithful port).
    - RT's 4-coefficient fallback (< 32 blocks) is broken: it solves the leading 4×4 of the 16×16 matrix, so the shifts hit the
      clamp and B gets worse. The worker drops such passes; 12 MP frames have ~1000 blocks.
  - **Time** (desktop laptop, 12.6 MP):
    - estimate on the base, 2 passes + avoid: 0.9-1.2 s on 1 thread, ~0.18-0.2 s on 8-16 threads;
    - frames mode per frame: GPU 17-19 ms including upload / readback (RTX), CPU port 115-145 ms;
    - base mode RGB: ~65 ms per pass.
    - Device guess for 27 frames: estimate ~0.4-0.6 s + GPU 27 × ~25-40 ms ≈ 1.1-1.7 s against the 1.5 s budget. To be
      measured.
  - **`rawCa 0` is bit-identical to before by construction** (argued; no host GLES replay on Windows):
    - the frames branch and the base estimate / apply are skipped;
    - P19's condition equals `caCorrect`;
    - the new headers add only namespace `vivo_rawca`;
    - `kHybMergeMain1` and all merge passes are untouched;
    - the app now writes `rawCa 0.0`, `rawCaPasses 2.0`, `rawCaRed 0.0`, `rawCaBlue 0.0`, which are the worker defaults.
  - **Checks run:**
    - `tools/check_settings_model.py` PASS;
    - unit tests: 184, only the known `CaptureControllerTest.testGetCameraOutputSize_withTwoParameter` fails;
    - Java compile OK;
    - the worker compiles and links for arm64 (NDK 27.2, `-Wall -Wextra`, no warnings).
  - **Device check (owner; OPPO PHY110 `fb27034c`; replays only, nothing installed):**
    1. Build the worker of this branch, with the command in the header of `tools/rawca/device_replay.sh`.
    2. Push the UW burst once: `adb push research/ca19/uw.nch /data/local/tmp/uw.nch` (680 MB). `hand.nch` is already there.
    3. For each burst B (`/data/local/tmp/hand.nch` = Quad 1x handheld, `/data/local/tmp/uw.nch` = plain Bayer UW), run
       `tools/rawca/device_replay.sh <label> B <lines>` with these tuning lines (set `APP_TUNING` to the app's own lines from the
       shot's log, e.g. the list in the P22 `run.sh`):
       - `rawCa 0 caCorrect 0`: no CA correction;
       - `rawCa 0`: P19, today's default;
       - `rawCa 1`: base mode;
       - `rawCa 2`: frames mode;
       - optionally `rawCa 2 rawCaPasses 1`, `rawCa 2 rawCaAvoidShift 0`, `rawCa 2 rawCaGpu 0` (CPU time), and
         `rawCa 1 rawCaAuto 0 rawCaRed 1.0 rawCaBlue -0.3` (manual).
    4. Compare:
       - log lines `HYBRID RAW CA (base|frames)`: per-pass blocks and corner shifts, `GPU <renderer> (init … ms), N frames in … ms`,
         and `HYBRID STAGES ms … total` (frames-mode cost against the 1.5 s budget);
       - `measure_rgb_ca.py` (run by the script): residual R / B corner and outer-ring radial shift of each output. Expect
         "frames" / "base" < no correction; compare with P19. A residual of the opposite sign means overcorrection;
       - crops of the corner window frames: no fringes of the opposite colour, no desaturated coloured details;
       - md5 of `rawCa 0` from this worker against the same replay with the main-branch worker (must be equal);
       - memory: frames mode holds one corrected copy per plain frame (UW: 27 × 25 MB); mosaic sub-frames are corrected in place.
  - **Open items:**
    - the real-scene under-correction above; options for the owner: a weight-aware block median and / or a radial-constrained
      fit (P19's k1 / k2 on RT's per-tile shifts, weighted) under a new key, keeping the RT-faithful path as is;
    - device time and memory of frames mode;
    - WB: grey world only (an NCH header word for the shot's WB would help RT's guards).

- **Device check (2026-10-06, merged into main):**
  - **Unchanged when off:** with `rawCa` 0 (the default) the worker output is bit-identical (daylight 1x, handheld Quad md5).
  - **Ultrawide burst** (`uw.nch`, 2x grid). Outer-ring median radial R shift and |d|, measured with `measure_rgb_ca.py`:

    | Mode | R shift / \|d\| (px) | Time |
    |---|---|---|
    | no correction | +0.25 / 0.37 | — |
    | P19 (today's default) | 0.00 / 0.18 | 0.41 s |
    | `rawCa 1` base | +0.13 / 0.23 | 2.5 s on the 2x RGB |
    | `rawCa 2` frames | +0.15 / 0.33 | GPU 27 frames 1.27 s + estimate 0.43 s |

  - **Decision:** the RT estimate under-corrects real scenes, as the port's author found. P19 stays the default; both RT
    variants are selectable in settings (owner's request).
  - **Next idea:** RT's per-tile shifts with a reliability-weighted median, or P19's radial fit on them.

### P29 — Sabre Quad remosaic from ArkCam v23, combined with our GCam 11 remosaic (owner's request, 2026-10-06; built, measured, default off)

- **Result (2026-10-07):** S0 numpy reference (research/p29/S0_results.md) and the native mosaic path in the worker (branch p29:
  S1 parity, S2 kernel per colour, S3 site gains, S5 Ark R/B fill, T2 Tetra; settings «Hybrid → Склейка → Мозаика без
  ремозаика», bilingual). Device check on the OPPO (research/p29/DEVICE_CHECK.md §8): byte-identical with the key off; synthetic
  Quad +1..+3 dB and -55 % false colour; on hand.nch (a TRIPOD burst, owner's correction: not handheld) no visible gain, best
  es 0.6 +49 % detail at matched noise with +12 % false colour; the merge is x6-8 slower (44-57 s against 7 s). The native merge
  left the settings screen (developer keys only, mosaicPath 0). Open: a handheld 1x Quad burst to judge it fairly (its gain
  comes from sub-pixel shifts a tripod lacks); why alignment reported ~9 px motion on a tripod burst; speed.

- **Owner:** «К разбору ARC Camera добавь ещё Sabre квад ремозаик», then «Я хочу совместить этот ремозаик с нашим который мы
  взяли с 11 гкама».
- **ArkCam v23 (libopenhdr_engine.so) has its own Quad Bayer path:** `uCfaSensorType` 0 = Bayer / 1 = Quad, a
  `k_fs_gcam_sabre_merge_quad` program, `k_cs_sabre_superResLinearRaw / superResDetailMerge / superResDetailResolve`, a 5x5
  binomial for the 4x4 CFA, the OpenCL `quad_speckle_filter` (`pref_quad_speckle_radius_key`), a "quad CA killer" and a
  "standard binned mode". On the Java side: Quad needs 16 ring frames (5 for Bayer), and the -3 EV burst is skipped on Quad.
- **Ours (P14 / P15 / P22):** GCam 11 QuadBayerRgbMerge design. b² plain-Bayer sub-frames go through the unchanged merge on
  the 2x / 4x grid, followed by a chroma median and false-colour suppression; plus `mosaicEdgeScale` and `mosaicShare`.
- **Study done (2026-10-06, `research/ark23/quad_report.md`, disassembly listings in `research/ark23/quad_disasm/`):**
  - **No remosaic.** ArkCam's Quad path merges every frame's raw sites straight from the mosaic into native-resolution RGB:
    - `merge_quad`: a 5x5 native-pixel Sabre RBF; R / B kernel 1.18x wider than G (scales 1.0 / 0.85, constants in `main()`);
    - R / B fill from G + the local (R−G) / (B−G) of the 3x3 block means when their weight < 0.25 × G's weight;
    - the base frame is merged last, with a cone kernel where coverage is below 4.5 frames;
    - 3-pass YUV resolve with a 3x3 chroma median as the false-colour guard.
  - **Not part of it:** the super-res shaders are dead code; speckle / CA killer / binned mode are generic post stages.
  - **Owner's shader file:** byte-identical to the dump.
- **Combined design (to implement after P27 / P30, default off until measured):**
  - **Keep ours:** block detector, site gains, binned alignment, F6, robustness, Bento / Shasta, chroma median + false-colour
    suppression.
  - **Per frame:** guide / rejection / F6 once per frame on the binned burst (not per sub-frame).
  - **New pass `kHybMergeMosaic`:** ArkCam's RBF over the raw mosaic with our gains instead of the b² sub-frame split
    (`kHybMergeMain1` untouched, plain Bayer md5-identical).
  - **R / B fill:** ArkCam's fill before our median, later GCam 11's directional colour-difference fill.
  - **Low coverage:** GCam 11's edge-directed Quad demosaic of the base instead of ArkCam's cone kernel.
  - **Tetra:** 2x2 binning into a true Quad at W/2 by default, native 7x7 as an option.
  - **Keys:** `mosaicPath` (0 = today), `mosaicWindow`, `mosaicKernelG` / `RB`, `mosaicChromaFill`, `mosaicFillSupport`,
    `mosaicFallback`, `mosaicTetra`.
  - **Estimate:** Quad 24 frames 8.0 → ~5.5-6.5 s; 50 MP Tetra ~20 s → ~5-6 s.
  - **Steps:**
    - S0: numpy prototype;
    - S1: parity pass;
    - S2: kernel split;
    - S3: site gains;
    - S4: sweep / default;
    - S5: Ark fill;
    - S6: GCam fallback;
    - S7: directional fill;
    - S8: Tetra.
    Each step gated on lattice 2 / 4 px, zone-plate false colour, maze (`eval_zipper.py`), 2-4 px band energy at equal noise,
    time and the Bayer md5.
  - **Not to port:** ArkCam's hot-pixel filter (compares different colours on Quad), the cone fallback, the super-res shaders.
- **Checks (owner's rule for every remosaic change):**
  - no lattice at period 2 / 4 px (`tools/quad/lattice.py`), no maze, no false colour;
  - detail band energy, time;
  - synthetic bursts (`gen_mosaic_burst.py` / `eval_mosaic_burst.py`), then a replay of `hand.nch` on the OPPO.

### P30 — Hybrid speed: what ArkCam v23 does faster (study 2026-10-06; done locally 7c6c56e, a057380, bc005ee)

- **Study:** `libopenhdr_engine.so` (openhdr_engine 3.0.3) is a GLSL port of Sabre. Everything runs on the GPU in the app process,
  in one EGL context that is created and compiled once at app start:
  - native ZSL ring of RAW10;
  - GPU unpack;
  - FFT + LK translation alignment;
  - per-frame merge with blend ONE,ONE;
  - GL post;
  - one glReadPixels;
  - libjpeg-turbo.
- **Our time (X100U log 2026-10-05, 27 frames, 1x, shutter → JPEG 8.0 s):**
  - CPU alignment 1.63 s;
  - Shasta / Bento 0.46 s;
  - transport between processes 0.55-0.65 s;
  - EGL + shader compile ~0.7 s on every shot;
  - GPU passes 1.77 s;
  - PostPipeline 1.56 s;
  - jpegli 0.23 s.
- **ArkCam:** not measured; estimated 1.3-2.3 s from the code.
- **Speed-ups with the same image (target 8.0 → ~4.5-5.5 s), in order:**
  1. glProgramBinary cache of the worker programs (−0.55-0.75 s);
  2. global alignment in parallel over frames, bit-exact (−0.6 s);
  3. Shasta / Bento in parallel (−0.2-0.35 s);
  4. worker spawn + GPU init while the bracket exposes (−0.1-0.4 s);
  5. ZSL / bracket written straight into the memfd (−0.2-0.5 s);
  6. integer textures instead of the SSBO, double-buffered strips, PBO + fence readback (−0.4-0.9 s at 1x, −2-4 s at 2x / Quad);
  7. lighter worker result (no single-threaded sum, multithreaded effMap / clip flags) (−0.15-0.3 s);
  8. persistent PostPipeline context and program cache (−0.1-0.5 s, measure first).
  Later, with more risk: F6 on the GPU, per-frame accumulation.
- **Not to take:**
  - translation-only alignment on large tiles;
  - dropping RL / ScamHdrDenoise;
  - dropping L / ES;
  - RGBA16F for 12-14-bit RAW;
  - TurboJPEG instead of jpegli;
  - the FrameSelector culling.
- **To measure:** both apps on one phone, same scene, 1x, diagnostics and `nice_keep` off. Full list in the session
  scratchpad `ark_all.txt`, to copy to `research/ark23/`.

- **Done (2026-10-06, owner: «Делай все 8 пунктов ускорения»), every worker change bit-identical on burst replays (md5 of
  daylight 1x / 2x, lamp, handheld Quad):**
  1. GPU program binary cache (app cache dir, `gl-cache` job file): worker init 0.65-0.70 s → ~0.05 s.
  2. Fallback global alignment (no CRE) on all cores 617 → 168 ms; CRE path: the clipped reference of brighter frames
     kept per burst (the CRE track itself stays sequential: its thread safety is unknown).
  3. Shasta sharpness (base guide once), gain check, Bento validation masks in parallel.
  4. Worker spawned before the burst is written (`wait-go` pipe), CRE warm-up + GPU driver init meanwhile.
  5. Shot arena: ring and post-shutter RAWs copied straight into the worker's memfd (NCH v12, frames at pages), raw_write
     240 → 0 ms; `Allocator.free` arena-aware; fallback = v11 copy. v12 vs v11 replay of an app shot bit-identical.
  6. Merge strips pipelined over two buffer banks with fences (shaders unchanged): merge stage 1x 1.95 → 1.75 s, 2x 4.8 →
     4.0 s. Integer textures instead of SSBO not done: they need edits of the merge program (Adreno: 15x slower rule).
  7. Worker tails on all cores (effective map, clip flags, CFA origin shift verified on 450 cases, log sum); mosaic chroma
     median 1.58 → 0.73 s. fp16 result / input texture not done: not bit-identical (quality rule).
  8. Post-pipeline program driver binaries kept across shots (GLProg, 96 MB LRU): 32 programs 106 → 29-86 ms (Android's
     blob cache already hid most of the compile).
- **Totals:** worker replays daylight 1x 4.6 → 3.8 s, 2x 7.3 → 6.0 s, lamp 5.0 → 4.0 s, Quad 7.8 → 6.2 s. App on the OPPO
  1x (Quad stream, 27 frames → 96 sub-frames): press → JPEG ~11.1 → ~10.9 s; the rest is the 96-sub-frame GPU merge
  (4.2 s, P29's target), F6 0.85 s, binned CRE alignment 0.7 s, PostPipeline 2.2 s (ScamHdrDenoise 1 s).
- **Note:** the OPPO's hybrid diagnostics switch (1x · ID 2) was on from the P22 work (+8 s per shot): turned off.
- **2026-10-07, against ArkCam v40 on the OPPO** (same scene, 3 shots each, press → saved JPEG, 4096x3072 both):
  SCAMERA 1x normal (Hybrid diagnostics off) 7.6-7.9 s: capture 0.57, wait 0.32, worker 4.7-4.8 (CRE global align 0.9,
  local align 1.0, mask 0.28, merge 2.1, I/O 0.3), PostPipeline 1.5-1.7, JPEG 0.47. ArkCam v40 12 MP 3.1-3.3 s: 30+4
  frames ready 0.36, whole Sabre stage with GPU alignment 2.2-2.4, resolve+LSC 0.06, post 0.2-0.25, TurboJPEG 0.15.
  Gaps: CPU alignment ~1.9 s, post ~1.4 s, merge ~0.5 s, JPEG ~0.3 s, waits/worker I/O ~0.6 s. Hybrid diagnostics on:
  13.1-14.1 s (NICE_DIAG thumbnails ~1.3 s each). 2x ISZ Quad 11-12 s vs ArkCam 50 MP super-res 6.2 s.

### P31 — Permissions asked like ArkCam (owner's request, 2026-10-07; done, pushed 0bb832c)

- Owner: «сделай так чтобы наше приложение просило разрешения также, а не как это сделано у нас».
- ArkCam v40 has no dialog of its own (its APK has no such text). It asks for every runtime permission in one
  RequestMultiplePermissions call, and ColorOS shows one combined system sheet («"ArkCam" требуются следующие разрешения»:
  permission, purpose, grant scope, OK / Отмена).
- Ours today: our own AlertDialog before every step, then camera + mic, then media read, then the SAF DCIM picker, one at a time.
- To do: one batched request (camera, microphone, media read per SDK) with no pre-dialogs. Re-ask while the system still
  allows it, then send the user to the app settings. Keep the SAF DCIM step after it, without our dialog.

### P32 — Viewfinder chrome: rotated module buttons, controls 10 % higher, shade over the viewfinder (owner, 2026-10-07; done, pushed 0bb832c)

- **Rotated module buttons:** with the phone in landscape (screenshot sc3s.png, 1× (2) selected) the lens strip draws its
  buttons wrongly: labels turned, the selected pill and the labels out of place. Fix the rotation of the module buttons.
- **10 % higher:** raise the module buttons by 10 % together with all the other bottom controls (shutter, gallery, switch).
- **Shade:** the shade's black area covers the bottom of the viewfinder. Lower it so the viewfinder is not covered.

### P33 — Shot speed close to ArkCam (owner's request, 2026-10-07; analysis running)

- Owner: «Нам нужно ускорить съемку чтобы скорость была не сильно дольше ArkCam».
- Now (1x normal, OPPO): press → saved JPEG 7.6-7.9 s against ArkCam v40 12 MP 3.1-3.3 s (numbers under P30). Target ~3.5-4 s.
- Budget to win: CPU alignment ~1.9 s, PostPipeline ~1.4 s, merge ~0.5 s, JPEG ~0.3 s, waits/worker I/O ~0.6 s.
- Plan: analysis workflow (post + JPEG, alignment + worker start, merge + capture) → waves: bit-exact first, measured on the
  phone after each wave (press → ImageSaved, 3 shots, worker replays with md5); output-changing options only with owner's OK.
- 2026-10-07: the analysis plan is research/speed/SHOT_SPEED_PLAN.md (wave 1 bit-exact → ~4.9 s, wave 2 → ~3.6 s, wave 3
  output-changing options for the owner). Wave 1 is being implemented (worktrees wt-speed-app, wt-speed-worker).
- 2026-10-07 14:05: WAVE 1 DONE and pushed (merges cadb394 app, fa6167d worker, 375b7fe post_ab dev switches). Both branches
  reviewed (3 + 5 fixes). Worker md5 ref == new on hand, syn_b1/b2/b4, hh_1, isz2 (+app tuning), day0815; replays: align
  0.99-1.44 s → 0.29-0.38 s, Bento mask 0.22-0.54 s → 0.04-0.06 s, GPU init hidden. App: post_ab on the OPPO EQUAL once the
  old run's stale effective-frame texels (100-1062 per shot, freed GPU memory behind a failing GL_RED upload) are cleared,
  otherwise 235 of 12.6 M px differ by 1 level; post 2.8-4.6 s → 1.3-1.9 s. In-app 1x Quad shots: mask 0.24-0.33 → 0.03-0.04 s;
  a clean press → saved A/B (old 0bb832c vs new) is still to do — the quad agent's replays shared the GPU and the phone ran at
  ~73 °C. Also to do: the same in 1x normal (owner switches the module), then wave 2 (W2.1, F6 alongside the merge, GPU moves).

### P34 — Quad detail like ArkCam: its quad-sensor Sabre merge (owner, 2026-10-07; in progress)

- Owner: ArkCam has much better fine detail in sensor mode 29 (Quad 4096x3072, no remosaic; both apps get the same RAW);
  «У арк используется Sabre сделанный для квад сенсоров». Evidence: research/p29/device_out/isz2_signs.png (JPEGs),
  isz_merge_vs_arkcam_dng.png (merge vs ArkCam's linear DNG).
- Merge against merge (isz2 burst vs ArkCam DNG): our split detail 0.78 at noise 0.78; P29 native 0.86 / 0.82 (54 s); split
  kernelScale 0.7 / 0.5 / 0.35 → 0.82 / 0.88 / 0.94 at noise 1.03 / 1.44 / 2.10. ArkCam holds ~15-20 % more detail at equal
  noise; the rest of the JPEG difference is its post (sharpening, local contrast).
- Owner's correction: «Я просил тебя совместить сабре квад арка и нашу реализацию. И код сабре квад ремозаика я тебе кидал».
  So the work is the P29 COMBINATION (ArkCam's quad Sabre shader research/ark23/k_fs_gcam_sabre_merge_quad.glsl + our block
  detector, site gains, alignment, F6, robustness, Bento/Shasta, chroma median, GCam 11 fill/fallback) = mosaicPath 1, not a
  new path. Steps: line-by-line diff against the shader, speed restructuring first, ablations against the ArkCam DNG to find
  where the combination loses ~15 % detail, fixes; default for Quad streams once it beats the split at equal noise and time.
- Post-processing (2026-10-07, scratchpad isz3/post_gain.py: band energy JPEG / tone-matched merge on textured tiles):
  poster scene (SNR 167): ArkCam's post adds x1.24 on low-contrast finest detail (0.6-1.2 px) and x1.07-1.12 on strong edges;
  ours x0.98 (erases weak fine texture: the "watercolour" floor) and x1.19-1.41 on strong edges (over-sharpens). On a brighter
  scene (SNR ~830) ours gives x1.17 / x1.16-1.19 and hybrid_dn_luma_mult 1 / 0.5 / 0.25 changes nothing, so the loss is a
  low-SNR denoise effect: A/B it on the dark poster scene (after P34), and soften the strong-edge sharpening.

### P35 — Remember a module's mosaic block once detected (owner, 2026-10-07; to do)

- Owner: «если у нас уже определило что модулю нужен ремозаик, то пускай он применяется в дальнейшем сразу же».
- Today the block is decided per shot from the data: the hybrid (ScamHybridBurst:134) runs MosaicBlockDetector on the first
  usable frame, and an unconfident answer (< 50 tile votes or < 60 % agreement: dark, flat or clipped scenes) is merged as
  plain Bayer, so a Quad stream can come out with the lattice. SCAM HDR (HdrxProcessor.niceMosaicStream) is covered only when
  the module declares sensor mode 5 / 7 or mosaic_block. Only the viewfinder's MosaicStream remembers a block per module key,
  and only in memory (lost on restart).
- Do: once a confident block > 1 is found for a module (its id + sensor mode + vendor request set, the MosaicStream key),
  persist it (SharedPreferences) and use it directly in every later shot and session of that module: the hybrid, SCAM HDR's
  niceMosaicStream and the viewfinder start from it without waiting for the detector. A module that declares sensor mode
  5 / 7 or mosaic_block takes that block at once even before the first detection.
- Goal is SPEED on re-entry (owner: «Чтобы при повторном включении работало быстрее»). With a stored block:
  - the shot path does not run the detector at all (hybrid mosaic_start..mosaic_done and niceMosaicStream are skipped);
  - the viewfinder's first frame already renders the mosaic right (no purple frames, no 12-frame measuring);
  - on module open (not on the shutter) the worker / GPU programs of that block's merge route (mosaicPath 0 split or 1
    native, the remosaic shaders for SCAM HDR) are prewarmed alongside the P33 GPU init, so the first shot pays no compile.
- Safety check off the critical path: the detector runs in the background on a viewfinder frame of the session, never
  delaying a shot; the stored block is replaced only when it confidently disagrees (MosaicStream's 3 agreeing confident
  answers), never by an unconfident one; the switch is logged. A change of the module's sensor mode or request set is a new
  key, so the old answer never applies to a different stream.
- Measure: shutter → saved and module open → first correct preview frame, first and second session, before / after.
- Tests: a unit test for the store (confident → stored; unconfident → kept; confident disagreement ×3 → replaced; new key →
  fresh), and on the OPPO a dark / flat Quad shot that the detector alone misses must merge as Quad.

### P36 — Front camera switch: the preview first shows upside down (owner, 2026-10-07; bug, to do)

- Owner: «При переключении на фронтальную камеру изображение сначала переворачивается на 180 градусов».
- Symptom: right after switching to the front camera the viewfinder shows the image rotated by 180° for a moment, then
  corrects itself. Likely the first frames of the new session are drawn with the back camera's transform (sensor
  orientation 90 vs 270 and the front mirror) before the renderer picks up the front camera's characteristics, or the
  transform is applied after the first frame (MainRenderer / the viewfinder matrix, the raw viewfinder of P13).
- Do: reproduce on the OPPO (screen recording / frame-by-frame log of the applied orientation per frame), find where the
  orientation and mirror of the new camera are set, and set them before the first frame of the new session is drawn (or
  hold the preview until they are). Check back → front, front → back, and every back module; check the saved photo's
  orientation too (EXIF and pixels) for the first shot after the switch.

### P37 — Zoom slider starts at 1x on a tele / other module (owner, 2026-10-07; bug, to do)

- Owner: «у нас есть баг с слайдером зума, если выбран например телевик, то зум начинается с 1х».
- Symptom: with a tele module selected, the zoom slider shows / starts from 1x instead of the module's own equivalent
  zoom (e.g. 3x / 6.7x / 10x), so the scale and the first steps do not match what is on screen.
- Do: the slider's range and starting value come from the active module's equivalent focal length relative to the main
  camera (ModuleRegistry / the module's lens settings, as the zoom buttons already use), on module switch and on app start
  with a tele module restored; the digital zoom inside the module then goes from that base upward. Check every module on
  the OPPO and vivo (UW < 1x, main, tele, ISZ slots), switching by buttons and by the slider, and that the shot's crop
  matches the label.

### P38 — vivo X300 Ultra: preview stabilisation lost after a shot (owner, 2026-10-07; bug, diagnosis started)

- Owner: «На Vivo x300 ultra есть проблема, после съемки кадра словно перестаёт работать стабилизация» (video
  Desktop/т/video_2026-10-07_15-19-41.mp4, log log-2026-10-07.txt; tele camera 5, route hybrid, ISP viewfinder, not RAW).
- Video (phase correlation, 46.5 fps): before the press the preview drifts smoothly (~0.5 px/frame, one direction); the
  preview freezes ~0.3 s at the shot; afterwards it jitters frame to frame in random directions (0.5-0.9 px jumps, hand shake
  no longer compensated) until the end of the clip. The session is NOT reconfigured after the shot (preview kept running).
- What the shot does to the running preview (CaptureController ~4000-4076): flushDevice() (reflective CameraDevice.flush,
  fast capture, "HAL queue flushed in 12 ms") → captureBurst(bracket, OIS/EIS/intent copied from the preview builder) →
  queueNiceAeRestore() (one preview-builder frame with AE OFF at the pre-shot exposure) → setRepeatingRequest(preview).
  The OIS key of the preview is ON (Camera2ApiAutoFix.applyPrev); default oisMode 0 sets ON only on stills.
- Suspects, in order: (1) the mid-stream flush resets the HAL's preview EIS / OIS state on this vivo HAL, and the plain
  repeating request does not re-arm it (only a new session does); (2) the manual (AE OFF) bracket / AE-restore frames switch
  the vendor preview stabilisation off and it stays off.
- Quick owner test with the current build: scam_dev.txt (Android/data/org.codeaurora.snapcam/files/) line
  "hybrid_fast_capture 0" disables the flush → if stabilisation survives the shot, suspect (1).
- Do: per-frame log of the preview results' LENS_OPTICAL_STABILIZATION_MODE / CONTROL_VIDEO_STABILIZATION_MODE / OIS samples
  (STATISTICS_OIS_DATA_MODE where supported) for ~1 s before and after the shot; dev switches for no-flush and no-AE-restore;
  fix per cause (re-arm: re-issue the preview with the stabilisation keys toggled, or per-device no-flush via FlushLossStats
  style memory, or the AE restore with AE ON + lock). Verify on the X300 Ultra (adb or the owner's CI build); check the
  OPPO is unchanged; measure that the shot-start latency gain of the flush is kept where possible.
- 2026-10-08 (owner: «Добавь в логи поведение OIS/EIS»): STAB_TRACE extended — phone gyroscope per frame (hand shake), crop
  region per frame (EIS crop moves), OIS samples on by default on vivo when offered, vendor char/request/result/session keys about
  OIS/EIS/gyro listed at the session start, the preview request's stabilisation keys, the series' still requests, a period line
  every 2 s (also long after a shot) and a before/after verdict per shot (OIS key off / OIS stopped following the hand / EIS crop
  frozen / preview gap). Shots on every route are marked now (was the NICE-routed path only). Test StabilizationTraceTest.
  Owner: send PhotonLog (lines STAB_TRACE) from the X300 Ultra: hold the phone in the hand 5 s, shoot, hold 10 s more.

### P39 — vivo X200 Pro (MediaTek, Mali): worker crash, pink photo, front camera hang (owner, 2026-10-07; in progress)

- Owner: pink photo on the main camera; switching to the front camera flips the preview, freezes it and shows «Системная
  ошибка конфигурации, попробуйте другой формат превью» (material: Desktop/т/200 pro.zip).
- Log: every hybrid shot the worker dies with SIGSEGV (during the bundled vivo CRE motion or right after the gain check, while
  GPU programs compile), the conservative retry too, then the cpu-single fallback (one frame) gives the pink image. Front
  camera 1 and camera 5: onConfigureFailed → onError 4 recovery loop (preview 1440x1080 + RAW stream).
- Agent (worktree wt-x200pro): crash site + defensive fixes + crash-site diagnostics in the worker, cause of the pink
  fallback colour, graceful fallback for unsupported stream combinations. The flip itself is P36.

### P40 — Native Sabre mosaic merge on Tetra too (owner, 2026-10-07; in progress)

- Owner: «Наш гибрид сабре ремозаика должен работать и на Tetra, доделай». mosaicPath 1 is the default for Quad since P34;
  Tetra still takes the sub-frame split (mosaicTetra 0). Agent (worktree wt-tetra): native path for 4x4 blocks, evaluation
  against the split (syn_b4 + real vivo Tetra bursts), default for Tetra if it wins at equal noise and time.
- 2026-10-07 18:40 P40 DONE (merged locally ea5b23b, review fixes 3b12369/3459190): native merge default for Tetra. Owner's real
  handheld 10x bursts on the X200 Ultra (172507 ISO 773, 172902 ISO 423), native vs split: 17 vs 8 frames, mosaic 6.5-7.2 s vs
  9.2-12.5 s, flat noise ×0.08-0.09, lattice 0.03-0.04 % vs 0.7-1.4 %, false colour −21..−39 %, edges equal, 4-8 px detail
  0.81-0.92 (night kernel scale 1 / flat ×2.4 — a tuning lever). Split's level errors are R/B misregistration at near-object
  edges. Window 3 kept (window 4: −11 % noise, +21 % time). Sheets: scratchpad v10x/tetra_sheet_*.png.
- 2026-10-07: also merged locally: tune (one-sided real effective-frame map, relaxed dark fade on signed hybrid input, 30
  frames default for plain and mosaic merges with one-time migrations), appbugs P35-P38, x200pro P39. Pending: camera
  selection fixes (wt-fixes), final OPPO check, then the single push.
- 2026-10-07 19:55: pushed ec0dcd7..7516d8f (all of the above + camera selection fixes); final OPPO check PASS (real effmap
  uploaded, 30 frames, new dark fade; press→saved 6.7-7.4 s 1x, 7.5-7.7 s 2x Quad at 30 frames). CI run 37635956888.

### P41 — Xiaomi 17 Ultra zoom switching like the stock camera (owner, 2026-10-07 20:05; in progress)

- Owner: «Исправь зум переключение которое мы сделали для 17у ... чтобы оно переключалось как на изначальных роликах со сток
  камеры и с логикой которую я описывал». Material: Desktop/т/video_2026-10-07_20-05-41.mp4 (ours), log-2026-10-07_17u.txt,
  "log-2026-10-07 x17u.txt"; stock videos research/xiaomi17u. Logic (P17): 75-100 mm optical (userZoomRatio + zoomRatio),
  100-150 mm crop, 150-200 mm ISZ current_mode 9 with smooth optical inside. Agent: worktree wt-x17u, frame-by-frame stock vs
  ours, fix XiaomiTeleZoom / zoom UI, tests. 17U not connected: owner verifies with a CI build.
- 2026-10-07 20:20 owner: post item (soften strong-edge sharpening / dark "watercolour" A/B) NOT needed — dropped. Do all the
  rest: speed wave 2, continuous chroma median, Tetra low-light kernel, night dark-chroma check, vivo ultrashort brightness,
  SCAM HDR signed RGB, small items.
- 2026-10-07 20:25 owner: «ScamHDR пока не занимаемся» — no SCAM HDR work for now (SCAM HDR signed RGB dropped from the
  queue; the vivo ultrashort-brightness item is done on the hybrid path only).
- 2026-10-07 20:40 owner: «Занимаемся только нашим гибридом ... ScamHDR доступен только на смартах на 8 Elite». Done locally
  e6c18ca: PreferenceKeys.isScamHdrSupported() = Build.SOC_MODEL starts with SM8750; elsewhere the route is always hybrid
  and the SCAM HDR screen / route choice are hidden (replaces the MediaTek-only lock). Tests emulate SM8750; new test for the
  fallback. Work focus: the hybrid only.

- 2026-10-09 owner sent `x17u_stock (2).zip` (stock camera capture: 0.6-30x zoom steps, logcat 163 MB, getprop, dumps).
  Stock opens SAT logical camera 5 with op mode 0x9002 (36866), session keys ExtendedMaxZoom=1, EnableInsensorZoom=1,
  teleFallback.isDisable=0, zoomRatio = userZoomRatio = dial ratio (3.2-4.3 moves glass, above 4.3 crop; HAL switches mode 2
  at ~7x and mode 9 at ~8.5x). Implemented P41b: tele opens through logical SAT camera 0, session keys and op mode matched to stock,
  logical preview (HAL controls optics and modes) with physical RAW on camera 4, automatic step-down ladder (0x9002 -> 0 -> camera 4 alone).
  Photo crop is residual from HAL reported lens position. Report: `research/xiaomi17u/STOCK_ZOOM_2026-10-09.md`.

---
## Status 2026-10-08 (pushed main 9bf9ea8)
- P33 speed wave 2 done (bit-exact on all replay bursts; plain Bayer worker ~2.2 -> 1.7 s; module-open prewarm at low priority, off the camera thread).
- Measured exposure ratio used when reliable (gainMeasured 1), NLM denoise on signed hybrid input, worker log line fix.
- Owner bugs fixed: «Показать градиентный фон» / theme change crash ("Error onSurfaceTextureAvailable"), front camera upside down with «Живой видоискатель».
- Waiting on owner logs with this CI build: X300U STAB_TRACE, X200 Pro worker crash + stripe, vivo lamp, CA location, 17U zoom.

### P42 — ArkCamera v50: face detection modes and tracking autofocus (owner, 2026-10-08; queued)

- Source: Desktop/arkcamv50.apk (new ArkCamera build from the owner).
- Owner: «Возьми из неё режимы распознавания лиц и следящий автофокус».
- To do: decompile (apktool / jadx as for the earlier ArkCam studies), map how it detects faces (Camera2 STATISTICS_FACE_DETECT_MODE
  SIMPLE/FULL vs. an ML detector, which modes it offers, how faces drive AE/AF regions and the UI boxes) and how its tracking AF
  works (object tracking between frames, AF regions following the subject, CONTROL_AF_TRIGGER / continuous AF use, tap-to-track).
- Bring both into SCAMERA in our style (settings in the LMC layout, strings ru+en, card UI), combined with our capture path; no
  sensor-mode changes; tests; device check on the vivo/OPPO.

### P43 — Manual controls and the other viewfinder buttons placed as in the concepts, following the phone's display scale (owner, 2026-10-08; queued)

- Owner (two screenshots of build 30626, vivo): «Исправить положения кнопок вызова и самих кнопок ручника. Должно находиться также
  как на концептах, сделай так чтобы положение учитывало масштаб выставленный на смартфоне, то же самое сделай и для остальных кнопок».
- Concept (https://claude.ai/artifact/2dsrwfur4fJMzSyZzCnLep, CSS px = dp): the manual block sits 12 dp from the left/right edges and
  its bottom 40 dp above the bottom bar, i.e. 12 dp above the shade handle; toggle 52 dp wide, min 58 dp high, radius 20; chip strip
  in the same row (5 equal chips, 4 dp gaps, padding 4, radius 20), ruler card above with an 8 dp gap; chip value 14 sp, label 9 sp.
- On the device the toggle and the chips are about twice as tall (toggle ~95 dp) and sit ~45 dp above the handle, inside the frame
  corner marks: the sizes do not follow the concept and the layout does not adapt to the display size / font size the owner set.
- Do: lay the panel out from dp anchored to the handle / bottom bar (not to fixed pixels or to the preview frame), text in sp with the
  boxes growing with the font scale but capped so the strip keeps one row; check the top bar, lens pill, shutter row, gallery, flip and
  the shade handle the same way. Verify at display size small / default / large and font scale 0.85 / 1.0 / 1.3 (adb shell wm density,
  settings font_scale only with the owner's consent on their phone; prefer an emulator or Robolectric qualifiers in tests);
  layout tests with several densities / font scales.

### P44 — The viewfinder must not freeze after the shutter press (owner, 2026-10-08; queued)

- Owner: «сделай так чтобы видоискатель у нас не замирал после спуска затвора».
- Known so far (P38 video, X300 Ultra): the preview stops ~0.3 s at the press. Causes in the hybrid path: the HAL queue flush
  (flushDevice, "hybrid_fast_capture"), the bracket requests submitted ahead of the repeating preview, the AE-restore frame and the
  preview restart behind the series; on routes without ZSL the repeating preview is stopped for the burst.
- Do: measure the preview gap per route with the shot timeline + STAB_TRACE dts (largest frame interval around the press), find
  which step stops the preview; keep the preview stream in the bracket requests / restart the repeating request right after the
  flush, or show the last preview frame with a short animation only if the HAL really pauses. Shot-start latency must not get worse.

### P45 — Black viewfinder after a format change (owner, 2026-10-08; watchdog done locally 83831ad, root cause open)

- Owner (vivo X200 Ultra, 5x tele, build 30626): after changing HEIC / WebP / JPEG in the viewfinder the preview is black; only a
  module switch or an app restart helps. Log log-2026-10-08 + SCAMERA-debug.log (10): camera 5 (forceSensorMode 5) sessions with no
  preview result at all on a cold start (11:16:11, 11:16:19), frames that stop ~11:16:08.8 in a running session (likely the format
  change; the change itself was not logged), HAL abort after 12 s without frames at 11:14:18 (and 10:22:29). The format choice
  only writes two preferences (no session change), so the stop is on the camera side or in a held buffer.
- Done: PreviewStall watchdog (no frame for max(2.5 s, 3 frame durations + 1 s), no shot in flight -> PREVIEW_STALL log + camera
  restart, at most 3 in a row); "format choice X -> Y" logged. Test PreviewStallTest.
- To do: with the owner's next log, find what stops the frames (held RAW images, the format sheet window, the tele's sensor mode
  5 cold start); fix the cause so the watchdog stays a safety net.

### P46 — More colour in HEIC / WebP (owner's question, 2026-10-08; proposal, waiting for the owner)

- Today every format is 8-bit sRGB (no ColorSpace / ICC handling in the output code). WebP: 8-bit only, lossy WebP is 4:2:0 (less
  colour detail than our 4:4:4 JPEG), wide gamut only via an ICC profile. HEIC (HEVC Main 10): 10-bit, wide gamut (Display P3 /
  BT.2020) and HLG/PQ HDR are possible, but androidx HeifWriter 1.1.0 encodes 8-bit only; its AvifWriter has
  setHighBitDepthEnabled (10-bit AVIF).
- Possible steps: (1) Display P3 output with an embedded ICC profile for JPEG / HEIC / WebP (pipeline renders in P3 instead of
  sRGB); (2) 10-bit HEIC through our own MediaCodec HEVC Main10 encoder + MediaMuxer HEIF (smoother gradients, HDR display);
  (3) optional 10-bit AVIF. Check viewers (Google Photos, our gallery) before making any of it the default.
- 2026-10-08: owner «начни реализацию 10 бит heic» -> agent in worktree wt-heic10 (branch heic10): RGBA_1010102 final image when
  the option is on (API 33), own HEVC Main10 encoder (P010, 512 grid tiles) + own HEIF container (hvcC, colr nclx sRGB, pixi 10),
  setting «HEIC 10 бит» default off, host decode check tools/check_heic10.py. 8-bit path must stay byte-identical.

### P47 — AVIF with settings + viewfinder icon (owner, 2026-10-08; agent running in wt-avif, branch avif)

- Owner: «добавь AVIF с возможностью настройки, не забудь добавить иконку в видоискателе для нового формата».
- Bundled libavif + libaom (all-intra), JNI encoder from ARGB_8888 / RGBA_1010102; settings: quality, lossless, 8/10/12 bit,
  4:4:4 / 4:2:0, speed; FormatChoice AVIF + RAW+AVIF with icons; gallery support; host check tools/check_avif.py.
- Owner also asked for «максимальные варианты JPEG / WebP»: JPEG is already at the practical maximum (quality up to 100, 4:4:4,
  Ultra HDR); 12-bit JPEG exists in the standard but Android / browsers / Google Photos cannot open it. WebP is 8-bit only (VP8 /
  VP8L; no 10-bit WebP exists); lossless WebP is its maximum and is already offered. Possible extras: Display P3 (P46),
  progressive / optimised JPEG (smaller file, same pixels).

### P48 — Plain (non-remosaic) shots as fast as ArkCam (owner, 2026-10-08; queued)

- Owner: «скорость обработки с сабре ремозаиком по сути такая же как у арк камеры, но при обычной съёмке мы дольше, ускорь
  обычную съёмку тоже».
- Do: measure press -> saved for a plain Bayer shot (no remosaic / mosaic path) on the OPPO X7U and the vivo with the SHOT TIMELINE
  and HDRX tail lines, next to ArkCam on the same scene; split worker (merge, F6, Bento/Shasta) vs Java/GL post vs encode; port the
  remaining P30/P33 ideas to the plain path (what the Sabre remosaic path already does faster: prewarm, banded overlap, fewer
  passes, GPU vs CPU split); every change bit-exact on the replay bursts or explicitly approved; the plain-Bayer worker md5 changes
  only where intended.

### P49 — Find X7 Ultra colour and spoof defaults (owner, 2026-10-08; done locally e192250, device check pending)

- Owner's pair (7u.zip): our photos had half of ArkCam 9.6 (X7U config) chroma. The CC14 matrix is right (plain render matches);
  the ARK tone compensation ccm_sat 0.6 (tuned vs ArkCam 2.85 X8U config) removed it. Now 1.1 (numpy reference of the tone;
  reproduces our JPEG at 0.6). Device defaults v3 on PHY110: package spoof com.ss.android.ugc.aweme on all three methods (5 cameras
  instead of 3) + ccm_sat 1.1. Check on the X7U when it is free.

### P38 update (2026-10-08, X300 Ultra trace from the owner)
- Camera 5: availOis [0,1], availVs [0] (no Camera2 EIS), no OIS samples; OIS ON before and after every shot; hand shake (gyro)
  similar before/after; preview gap ~300 ms at the shot. Vendor EIS keys exist (request + session): vivo.control.eis.config.enable,
  vivo.control.eis.enhance, vivo.control.EISsolution, org.codeaurora.qcamera3.sessionParameters.EISMode — we set none.
- Stock Photo mode sets vivo.control.eis.config.enable = 5 as a session parameter and in every request (vivo app log 2026-09-20).
  Done locally 5eb0922: the NICE preview sets it too (nice_dev "vivo_preview_eis 0" = off). Owner: check stabilisation after a shot.

### P50 — Settings audit and fixes (owner, 2026-10-08; agent in wt-settings, branch settings-fix)
- Audit: research/settings-audit/SETTINGS_AUDIT.md (395 rows: 316 OK, 2 DEAD, 2 BROKEN, 73 MISLEADING, 2 DUPLICATE + cross-cutting).
- Owner's answers: UI-type settings global for all lenses (incl. merge route); drop the dead OPPO SCAM HDR device defaults; remove
  upstream PhotonCamera links and config download; SCAM HDR defaults: diagnostics off, planner SCAMERA.
- After the fixes: full CI test list, then push (owner: «Затем можешь заливать на гит»).

### P51 — Moving objects on Quad / Tetra ISZ: block lattice ("honeycomb") (owner, 2026-10-08; done locally 089d768)
- vivo X200 Ultra 4x ISZ (Tetra native merge), a moving boat: lattice around the boat and on the moving water; 2x ISZ (Quad) the same, finer.
- Cause: the native merge never widened the base kernel where donors were rejected (Tetra rule 16F+15 < 4 never true).
- Fix: mosaicNativeWiden 2 (smooth by accepted frames; Tetra x3 / 8 frames, Quad x2.5 / 4). Synthetic moving bursts on the OPPO:
  research/moving-objects/MOVING_OBJECTS_REPORT.md; CI check tools/check_hybrid_native_widen.cpp. Before the push: plain-Bayer md5
  on syn_b1 (OPPO, when free). Owner checks 4x / 2x ISZ with motion on the vivo.

### P52 — vivo X200 Pro front camera: session configuration fails (owner's log 2026-10-08; done locally 9f6a459)
- Every stream size failed (1920x2560, 1200x1600, guaranteed YUV) with the vivo stock preview profile in the session parameters;
  back to the previous camera. Now onConfigureFailed retries once with the plain Camera2 preview before the size fallbacks.
  Owner checks the front camera on the X200 Pro (if it still fails: the log's next attempt lines).

### P53 — Pixel 7: main-camera RAW has no preview, processing very slow (owner's log + video, 2026-10-08; analysis)
- Video: RAW-only format; our gallery shows main-camera (camera 2) DNGs black / "RAW" placeholder, ultrawide (camera 3) DNGs render.
  Needs: one main and one UW DNG from the owner to see why the platform decoder (Glide -> ImageDecoder / SkRawCodec) fails.
- Time (RAW shots 22-41 s, JPEG 11-20 s): processing diagnostics were ON (NICE zip per shot: client output 2-8.5 s + zip);
  F6 local alignment 2.8 s cool -> 14.7-16.5 s later (Tensor G2 heat + "field not streamed": 497 MB gray images > MemAvailable/4,
  MemAvailable 0.3-1.1 GB); Mali-G710 merge 3-8 s; post pipeline ~5 s. JPEG-only log: 10 frames (dim ring), worker 2.6 s, total 11.5 s.
- Options: diagnostics off; on low-RAM / Tensor phones cap the burst (e.g. 15 frames) or F6 frames; half-float gray images so F6
  streams (not bit-exact); embed a preview in the DNG.

### P46 decision (owner, 2026-10-08)
- Owner chose option 1: a setting «Цветовое пространство: sRGB / Display P3» for JPEG / HEIC / WebP / AVIF, default sRGB (the
  pipeline renders in P3 and the file carries the profile only when the owner turns it on).
- HDR (HLG / PQ) in HEIC / AVIF: «делай как отдельную функцию, по дефолту выкл» — a separate option, default off.
- Agent in a worktree implements both; coordinator checks on the OPPO (files decode, profile / CICP present, sRGB default
  byte-identical to before).

- Owner 2026-10-09: «На пикселе теперь днг обрабатывается также быстро как и жпег» — processing time closed. The black main-camera
  DNG in the gallery is covered by the P59 DNG decoder (own raw render); owner to confirm in the new gallery.

### P54 — vivo X300 Ultra: preview stabilisation still lost after a shot (owner's log 2026-10-08 «log-2026-10-08 X300U.txt»; to do)
- Build before CI #344 (no PREVIEW_GAP lines): camera 5, 9 shots 10:04-16:15. The preview request has vivo.control.eis.config.enable=[5]
  (session start, also after the restart at 16:15:42), OIS on before and after every shot, videoStabilizationMode 0, crop region
  constant 4080x3072 (the HAL's EIS crop is invisible to Camera2), re-arm mode 3 (second flush) 15-34 ms, preview gap ~300 ms.
  Yet the owner sees no stabilisation after the shot.
- Unknown: whether the repeating request rebuilt after the shot (AE restore / re-arm / P44 lead frame) still carries the EIS key and
  the stock profile tags, and whether the vivo HAL's EIS survives a flush at all (stock never flushes?). STAB_TRACE logs the request
  keys only at session start.
- Do: log the stabilisation keys of every repeating request set after a shot (and of the lead / AE-restore frames); compare with the
  stock app's request sequence around a shot (vivo app log 2026-09-20); try on the X300U with nice_dev: preview_lead 1 (default in
  CI #344, re-arm mode 1 = no second flush), stab_rearm 0/1/3, hybrid_fast_capture 0 (no flush), and a session re-creation after the
  shot as the last resort; ask the owner which variant keeps EIS (screen recording + log). Also check vivo.control.eis.enhance /
  EISsolution / qcamera3 EISMode session keys the stock app sets.

- 2026-10-09, owner: the vivo vendor keys may be what breaks the X300U's stabilisation (non-vivo phones never get them:
  ScamPreview.supported() is vivo / iQOO only and the HAL rejects unknown tags). Done: the stock preview profile recorded on
  the X200 Ultra (≈80 vivo.control / vivo.capability tags, vivo.control.zoom_ratio, motion metering, Camera2 scene mode
  FACE_PRIORITY, preview EIS 5) is sent only on PD2454; other vivo phones (X300U v2562) get the NICE keys alone (MagicEnable,
  capture.nice, sceneMode NICE bank). Then the owner: «Scam HDR на остальных виво не работает, он работает только на 200 ультра и
  смартфонах на 8 элит» — so the NICE preview (all its vivo keys, session MagicEnable included) runs only on PD2454
  (ScamPreview.vendorKeys()); other vivo phones get the plain Camera2 preview. nice_dev "vivo_stock_profile 1" = old
  behaviour. Owner: check X300U stabilisation after a shot.

- 2026-10-09, owner log `log-2026-10-09.txt` (V2562 / X300 Ultra, tele camera 5, shot 09:50:47):
  - Result: vendor keys removal (`vendorKeys=` empty) did NOT fix the issue; owner reports stabilisation is STILL broken after the shot.
  - Evidence from trace:
    - Camera2 metadata keeps reporting `req[ois=1 vs=0]` and `res[ois=1 vs=0]`, so at the Camera2 framework level OIS appears enabled, but hardware OIS actuator / HAL EIS enters an inactive / frozen state after the burst.
    - Shutter timeline: `submit requests=5 flush=true previewKept=true` -> `HAL queue flushed in 12 ms` -> preview lead frame queued -> AE restore frame queued (AE OFF) -> preview repeating restarted.
    - PREVIEW_GAP logs: `preview requests failed 5`, preview gap 233 ms.
  - Hypotheses & Action Plan:
    1. **HAL queue flush (`abortCaptures()`)**: on X300U (V2562), flushing the queue during capture series breaks the HAL's internal OIS/EIS loop or puts the lens actuator into parked/limp state. Test with flush disabled (`scam_dev hybrid_fast_capture 0` / skip `abortCaptures()` on X300U).
    2. **OIS cycle (wake-up pulse)**: after the burst sequence completes, explicitly cycle optical stabilization off and on (`LENS_OPTICAL_STABILIZATION_MODE = 0` for 1-2 repeating frames, then back to `1`) to force the HAL / OIS driver to re-initialize the lens actuator.
    3. **Repeating request restart**: call `stopRepeating()` and explicitly recreate the repeating request via `setRepeatingRequest()` with clean builder after sequence completion rather than queuing re-arm into existing repeating flow.
    4. **Capture Intent**: hybrid series requests were sent with `CONTROL_CAPTURE_INTENT = 1` (PREVIEW) instead of `STILL_CAPTURE`. Verify whether the HAL's OIS expects STILL_CAPTURE for series and PREVIEW afterwards, or if the intent transition caused OIS disarming.
    5. **Session Re-creation fallback**: if the HAL driver cannot recover OIS without a session reconfigure, implement seamless session restart on X300U after burst capture.
- P54b (owner 2026-10-09: «зафорсить постоянную стабилизацию для Vivo X300 Ultra»; local, not pushed): capture/ForcedStabilization —
  on vivo V2562 in photo modes: OIS ON in every request (preview, lead / AE restore via the preview builder, the series; not
  when the user chose OIS off), vivo.control.eis.config.enable=5 as a session parameter and in every request (only this key,
  not the PD2454 profile; a session refused with it retries once plain, as the stock profile does), and no HAL flush on the
  shot (re-arm then defaults to 1 = re-send the repeating request). Log: "forced stabilisation (P54b) camera N: preview ois=1
  eis=5, no HAL flush on the shot", "HAL queue not flushed: camera N holds the forced stabilisation". scam_dev: force_stab 0/1,
  force_stab_eis 0, force_stab_noflush 0. Cost: the series starts ~0.1-0.2 s later (no flush). Owner: X300U shot + STAB_TRACE log;
  if still lost, A/B force_stab_eis 0 and force_stab_noflush 0 to tell which part matters.
- P54c (owner 2026-10-09: ArkCam v73 keeps the X300U stabilisation after shots; «fix it without changing the merge and the
  result»). ArkCam v73 study (jadx, com/arkcam/CameraManager): preview = TEMPLATE_PREVIEW + RAW (ZSL), AE / AF / AWB auto,
  OIS 1, no vivo / EIS keys at all, session op mode 0; the shot is taken from the ZSL ring, no stopRepeating / abortCaptures /
  flush; an optional 2-frame STILL_CAPTURE burst (AE OFF, OIS 1) goes to the RAW stream ONLY while the preview keeps running,
  the repeating request is re-set in onCaptureSequenceCompleted. Ours on the X300U (log 09:50): series already RAW only
  (unchanged), flush gone since P54b, but the AE restore frame behind the series is an AE OFF request ON THE VIEWFINDER
  stream. Done (X300U / force_stab only): the AE restore frame goes to the RAW stream only (same exposure / ISO / RAW, so the
  AE hand-back and the next shot's ZSL ring are unchanged; the viewfinder misses one frame); the vivo EIS key of P54b is
  opt-in (scam_dev force_stab_eis 1), as ArkCam sends none - session / series requests are back to the pre-P54b keys.
  Merge, series and output untouched. scam_dev force_stab_rawonly 0 = old frame. Log: "AE restore frame queued ... (RAW
  stream only, P54c)". Owner: X300U shot + STAB_TRACE log.
- P54d (owner 2026-10-09: «На Vivo X200FE вообще не работает стабилизация ни на одной камере, исправь»):
  Root cause analysis on vivo V2503 (Dimensity / MTK HAL):
  1. OIS modes check: `mCameraCharacteristics.get(LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)` returned `[1]` (length 1)
     on cameras 2 (main) and 3 (tele). Previous code checked `modes.length > 1` (assuming Qualcomm `[0, 1]` format),
     evaluating to false on MTK HAL! Thus OIS was never turned on in `applyPrev`, `applyOisMode`, or still capture.
  2. Electronic Image Stabilization (EIS): `CONTROL_VIDEO_STABILIZATION_MODE` was only set inside `if (mIsRecordingVideo)`.
     In photo mode, `CONTROL_VIDEO_STABILIZATION_MODE` remained 0 (OFF) for all cameras, including camera 4 (ultrawide)
     and camera 1 (front) which have no OIS hardware.
  3. Session parameters: `CONTROL_VIDEO_STABILIZATION_MODE` and `vivo.control.eis.config.enable` were absent from session
     parameters because `ScamPreview.stockProfile` was restricted to PD2454 and `ForcedStabilization` to V2562.
  Fix:
  - `CaptureController.isOisSupported(modes)`: checks if array contains `LENS_OPTICAL_STABILIZATION_MODE_ON` (1) instead of
    relying on `length > 1`. Applied across `CaptureController`, `Camera2ApiAutoFix`, `CameraFragment`, and `SensorConfigPreferenceGenerator`.
  - `getPreferredVideoStabilizationMode()`: in photo mode when `isEisPhotoOn` is enabled, activates `PREVIEW_STABILIZATION` (mode 2,
    Android 13+) if supported, or `ON` (mode 1) for non-OIS cameras (ultrawide/front).
  - Vivo EIS key `vivo.control.eis.config.enable = 5` and video stabilization mode configured in both session parameters and
    repeating preview requests for vivo devices.
  - Comprehensive unit test added in `ForcedStabilizationTest.oisSupportDetectionHandlesVariousHalModes`.

### P55 — Rebrand inside the APK: no "LMC", "Vivo", "NICE" anywhere; everything "scam" (owner, 2026-10-08; DONE e465ab4, see HANDOFF §5; phone names kept per owner 2026-10-09)
- Owner: «удалить все упоминания LMC, Vivo, NIce из нашего апк. Заменяй всё на scam».
- Scope: everything that ships in the APK — UI strings (values / values-ru), settings keys shown to the user, log tags and log
  lines (NICE_HDR, NICE_CAPTURE, NICE_PIPELINE, NICE_DIAG, "LMC hybrid", "Vivo Neural"...), class / package / file names
  (ScamBurst, ScamRgb, ScamPreview, ScamHybridBurst, ScamDenoise, scam-hybrid.h, vivo-neural-worker, libvivo*),
  asset names, diagnostic zip names (NICE-*.zip), JNI symbols, preference keys (pref_lmc_hybrid_*, pref_nice_*, isVivoNiceEnabled),
  scam_dev.txt, native strings in the worker binary. Replace with "scam" naming (e.g. SCAM_HDR / ScamBurst / scam_dev.txt / SCAM-*.zip).
- Must keep working: stored preferences (migrate old keys -> new keys once, DeviceDefaults versions), the vendor Camera2 tag names
  (vivo.control.*, vivo.parameter.* are the HAL's own keys — cannot be renamed; keep them as data, never as our naming), the
  vendor library / AEC runtime names that the HAL or a vendor lib loads by name (only if loading needs them), CI workflow, tools,
  tests (rename with the code), research docs outside the APK can stay. Device checks (vivo/OPPO), md5 gates on the worker
  unchanged (renames only), unit + CI lists updated, tools/check_* that grep names updated.
- Check: unzip the APK and grep -i for scam|vivo|nice (strings, dex via dexdump/strings, .so via strings) — only vendor HAL tag names
  and third-party library internals may remain; list them in the report.

### P56 — Sabre x2 (2x output grid) does not work with ISZ (owner, 2026-10-08; Quad done; Tetra done 2026-10-10)
- Owner: «с isz не работает Sabre X2».
- Cause (by design today): ScamHybridBurst.java ~line 155: wants2x = ... && mosaicBlock <= 1 — a Quad / Tetra (ISZ) stream never
  gets the 2x grid; the native mosaic merge (P29/P35) outputs the sensor grid 4080x3072 ("HYBRID OUTPUT: Sabre 4x grid 4080x3072"
  = 4x of the binned 1020x768 frames, kernel in sensor px). The log says "hybrid output mode=2x ... twoX=false" without a reason line.
- Do: (1) log why 2x was refused for a mosaic stream; (2) make the native merge write a 2x grid of the sensor (8160x6144) when the
  output mode is 2x: kHybMergeMosaicFast / kHybMergeMosaic already take the output grid factor (kG.x = g) — check the native path
  passes g = 2 correctly (output positions, the F6 field / covariance lookups at 2x, the chroma median and clip flags at 2x), the
  memory budget (50 MP float RGB + GL working set; vivo X200 Ultra has the RAM, Pixel-class phones do not -> keep sabre2xFits-style
  limits), and the post pipeline at 50 MP (P48 numbers: ~6 s post + 3.7 s WebP at 2x). (3) Measure on ISZ 2x / 4x bursts: detail vs
  the sensor-grid output (owner's 4x ISZ Tetra and 2x Quad), time and memory; keep plain Bayer 2x and the sensor-grid ISZ output
  byte-identical; replay md5 gates (hand / isz2 / syn_b2 / syn_b4 at 1x grid must not change).

- 2026-10-10 Tetra (Realme GT8 Pro 4x ISZ log: "Sabre 2x grid off for a Tetra mosaic stream"; vivo X200 Ultra 10x): the native
  merge writes the Tetra 2x grid itself (g = 8: kHybMergeMosaicFast position (X + 0.5) 4 / g - 2, HybridGpu grid 8 for native
  Tetra only, k2 for Tetra T1); ScamHybridBurst allows 2x for block 4. md5 gates at 1x and Quad 2x unchanged. vivo 10x burst
  (grabbed from the worker's memfd, root) on the OPPO: merge 4.7 s at 1x, 16.3 s at 2x with window 6; new key
  mosaicTetraWindow2x = 5 (12.3 s, 62 dB from window 6, flat dark noise +4 %; 4 = 8.8 s, 57 dB, +20 %). Tried and dropped
  (Adreno 750): 2 / 4 output pixels per invocation (exact, but 26x slower / GPU reset: register spills), the window test before
  exp2 (exact, 6 % slower). Owner to check on the GT8 Pro and the vivo 10x.

### P57 — The viewfinder stutters heavily after a shot, while the photo is processed (owner, 2026-10-08; to do)
- Owner: «после съемки у нас видоискатель очень сильно тормозит».
- Evidence (X300U log 2026-10-08, STAB_TRACE period lines): the CAMERA keeps delivering ~30 fps after the shot (60-61 frames per 2 s,
  maxGap 33 ms, apart from the shot's own ~300 ms gap and one 700 ms gap 10 s after a shot) — so the stutter is on our side: drawing /
  presenting the frames while the hybrid worker (GPU merge, F6 on all cores), the GL post pipeline (same GPU, readback), the encoders
  and the GC run. STAB_TRACE does not measure displayed frames.
- Do: (1) measure what the user sees: a per-2 s line of frames DRAWN by the viewfinder renderer (MainRenderer / GLPreview onDrawFrame
  count, max interval, time spent per draw) and the UI thread's frame stats (Choreographer jank / dumpsys gfxinfo) next to the shot
  timeline stages; (2) find the contention: worker process priority (it runs at nice 10 for the prewarm only?), its thread count vs
  big cores, GPU sharing (the worker's EGL context and the post pipeline's GL work vs the viewfinder's GL context: low context
  priority EGL_IMG_context_priority / EGL_CONTEXT_PRIORITY_LOW for processing, split big GL dispatches into chunks with flushes so the
  viewfinder's draws get in), main-thread work after a shot (thumbnail decode, gallery refresh, Bitmap copies, toasts), memory
  pressure / GC (large Java buffers); (3) fix so the viewfinder keeps ~30 fps drawn during processing without slowing processing
  more than a few %; verify on the OPPO and the vivo (screen recording + the new drawn-fps lines) on plain, ISZ and RAW shots.

### P58 — OnePlus 15: artifacts in blown highlights (owner's 1_15.zip, 2026-10-08; to do)
- OnePlus 15 (CPH2747, camera 2, hybrid + Bento). Blown sky through leaves turns pink / magenta with white blotches
  (IMG_20261008_180208: research crop of the window). Log: Bento applied (factor 2.71, 2 ultrashorts, motion 1.75 %), then
  ScamRgb per-channel recovery "hi=2.478(plateau),2.484(plateau),0.0(off)": R and G recovered to the plateau, BLUE off -> the
  recovered sky keeps R,G high and B at the old clip = pink. Other shots of the session: hi=0(off),6.74,0(off) (k 7.35) and the
  "plateau / nominal" mixes; usClipped 0.06-0.19 on later shots. CLIP FLAGS R=118 G=52 B=90, bento=335020.
- Do: replay with the NICE zip (NICE-20261008-181021, input.nch if present); fix the per-channel recovery so a channel without a
  measured hi level never stays at the old clip next to recovered channels (neutral roll-off / take the measured max of the
  channel or the k level, as for the vivo lilac window fix hl2), check the Bento mask edges (white blotches = cells inside vs
  outside the mask at different levels) and the clip-border pass; tools/check_highlight_neutral.py / check_highlight_recovery.py
  cases for "one channel off"; device check on the OPPO (OnePlus / OPPO share the HAL family) with a backlit window.

### P59 — Gallery: support every format we can shoot (owner, 2026-10-08; to do)
- Owner: «добавь нам в галерею поддержку всех форматов в которых мы можем снимать».
- Formats: JPEG (incl. Ultra HDR, P3 ICC), HEIC 8 / 10 bit (P3 nclx, HLG BT.2020), WebP (lossy / lossless, ICC), AVIF 8/10/12 bit
  (4:4:4 / 4:2:0, lossless, P3, HLG), DNG (merged 14-bit; Pixel 7 main-camera DNG shows black — P53), RAW+X pairs (one item
  with both files?), 10-bit / HDR display (wide colour / HDR window mode).
- Do: a test matrix (files produced by our encoders on the host tools + device shots) through our gallery's decode paths
  (tiled BitmapRegionDecoder, Glide, ImageDecoder) on Android 10 / 12 / 13+ behaviour; thumbnails in the filmstrip and the viewfinder
  thumbnail for every format; DNG: our own preview (embedded preview at save time, or our decoder) when the platform decoder fails;
  share / delete / info for every format; Robolectric / instrumentation tests where possible.
- 2026-10-09 AVIF (owner: AVIF pages black): done fdaab04 (AvifGlideDecoder, AVIF / DNG pages through a Glide bitmap).
- 2026-10-09 WebP & HEIC gallery stability (owner: «не всегда фото прогружаются», «HEIC тоже исправь, галерея может переодически вылетать»):
  Only JPEG uses Android's tiled `BitmapRegionDecoder` (mature SkJpegCodec / libjpeg-turbo).
  For HEIC/HEIF, `BitmapRegionDecoder` caused periodic native crashes (SIGSEGV / abort in libstagefright/libheif/MediaCodec)
  due to concurrent tile decoding across threads, and failed on 10-bit HEIC (Main10).
  For WebP, `BitmapRegionDecoder` dropped tiles or left pages blank on VP8X / ICC files.
  All non-JPEG formats (HEIC, HEIF, WebP, AVIF, DNG) now load directly through Glide into `ImageSource.cachedBitmap(bitmap)`:
  - Bounded decode size: at most 4096 px (`FALLBACK_MAX_SIDE` in `GalleryDecodeRetry`): a 12 MP photo is 100% full uncompressed
    pixel resolution (zero downsampling), while a 50 MP photo uses ~48 MB RAM instead of 200 MB, preventing OOM while keeping crisp detail.
  - Automatic retry with backoff (0.7 s, 1.4 s) when files are opened immediately after capture while still being written / flushed.
  - `ImageAdapter.detachPage` calls `Glide.clear(page)` to eliminate late callbacks on recycled views.
  - `UltraHdrGalleryUtil.colourOf` catches `Throwable` to safely handle any decode errors during bounds probe.
- 2026-10-09 SGallery renaming & minimalist companion icon (owner: «преименуй галерею в SGallery и измени иконку под наш стиль интерфейса», «Сделай иконку более минималистичной»):
  - Renamed `gallery_name` string from `PGallery` to `SGallery`.
  - Created minimalist companion icon matching `scamera_launcher` aesthetic: upright rounded photo frame with clean geometric
    navy mountain peaks and warm orange circular sun accent on the identical dark squircle background (`#1E1E1E`).
  - Generated `sgallery_icon_generated.png`, configured adaptive `ic_gallery_launcher.xml` and `ic_gallery_launcher_round.xml`,
    and rendered mipmap PNGs for mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi.

### P60 — Any phone: never crop a sensor-cropped stream twice (owner, 2026-10-08; part done 9cfae80)
- X9 Ultra bug (P-fix 9cfae80): ISZ by a vendor tag not recognised as a sensor crop -> preview and photo cropped again by the
  residual. Fixed generically when the stream is measured as a Quad / Tetra mosaic at the binned size.
- Remaining gap: a vendor ISZ mode whose HAL remosaics in the ISP (Bayer stream, block 1) cannot be detected that way. Do: measure
  the field of view of a module with vendor requests against its plain sibling on the same Camera ID at the first frames
  (feature match scale, as done offline on the OPPO: 2x module = 2.00x of 1x) and set the stream crop from it; log
  "module X: measured field 1/2.0 of camera Y"; cache per module signature.

### P61 — Re-check that the compression settings of every format really work (owner, 2026-10-08; to do)
- Owner: «Перепроверить что сжатие всех наших форматов работает полноценно, потому что на предыдущих версиях разницы не было»
  (changing the compression / quality made no visible or size difference on earlier builds).
- Do, per format and per setting (JPEG quality + 4:4:4 + Ultra HDR; WebP quality + lossless; HEIC 8-bit quality (HeifWriter),
  HEIC 10-bit (CQ value of c2.qti.hevc.encoder.cq / bitrate mode fallback on other encoders); AVIF quality, lossless, depth, chroma,
  speed): trace the setting from the preference to the encoder call (log the effective parameters per shot: "JPEG q=.. 4:4:4",
  "WebP q=.. lossless=..", "HEIC 10-bit CQ ..", "AVIF q=.. speed=..") and confirm the encoder honours it (some MediaCodec HEVC
  encoders ignore CQ / quality: check KEY_QUALITY support via EncoderCapabilities.isBitrateModeSupported + getQualityRange and fall
  back to VBR with a computed bitrate). Measure on one fixed image (host tools for jpegli / libwebp / libavif; device for HEIC):
  file size and PSNR/SSIM vs quality must change monotonically; add a CI check for the host encoders and a device test list for
  HEIC. Fix any setting that is ignored or mapped wrong; settings audit text updated if a range is meaningless for an encoder.

### P62 — Mochi: GCam 11 photometric merge of the bracketed (long) frames (owner, 2026-10-08; UI exposed and tested 2026-10-09)
- Owner: «Помнишь в 11 мы находили "Mochi"? Реализуй его работу у нас», «"Mochi" у нас реализован? Не вижу его настроек у нас в камере. Проверь реализацию», «Выводи». Reference: research/gcam11/map/03b_merge_accumulate.md §3.6,
  GCAM11_PIPELINE.md (+0x1b4 PhotometricMergeOptions, §0.5 device defaults: Java enables it in normal photo on P25/P26).
- Mochi implementation in SCAMERA:
  - Implemented at C++/GPU level in `scam-hybrid.h` (`kHybMochiStats`, `kHybMochiBlur`, `kHybMochiApply`, `HybridGpu::mochi`). Calibrates bracketed (long/L) frames against the base accumulator on GPU before the merge, eliminating color and brightness drift across exposures.
  - UI exposure: added `pref_scam_hybrid_mochi` under `scam_hybrid_merge_screen -> scam_hybrid_mochi_screen` with 3 choices:
    - 0: Off (stock) / Выкл. (сток)
    - 1: Auto (GCam 11: > 3 short frames) / Авто (правило GCam 11: > 3 коротких)
    - 2: Always (force on bracketed frames) / Всегда (принудительно для брекетинга)
  - Connected in `PreferenceKeys.java` (`getHybridMochi()`, `hybridTuningText()` pairs), `SettingsNumericRules.java` (bounds [0, 2, 1]), `SettingsModelCheck.java`, and covered by unit tests in `HybridSettingsTest.java` (`mochiKeysReachTheTuningFileAndDefaultOff` for both Russian and English locales).

### P59b — Gallery in the new card style (owner's GALLERY_TASK.md, 2026-10-08; to do, after/with P59)
- Task file: C:/Users/MECHREVO/Downloads/GALLERY_TASK.md; concept https://claude.ai/artifact/AE4t3NyLqPJXWG43m5PfXS (library, viewer,
  details sheet, delete sheet, folders sheet, compare). UI only; keep GalleryViewModel / ExifDialog* / SelectionHelper /
  GalleryFileOperations / ImageFile / MediaFile / compare ScaleAndPan / folders pref / UltraHdrGalleryUtil / intents / deletion.
  Reuse SettingsStyle + colour tokens, route monograms (H / S), format icons. ru + en texts; no ellipsis / overlaps at 360 dp;
  Robolectric + 360 dp layout tests; phone checks listed in the task. No push until the owner says.
- Owner addition: a setting to show / hide the gallery as a separate app in the launcher (its own icon; activity-alias enabled /
  disabled with PackageManager.setComponentEnabledSetting(DONT_KILL_APP)), ru + en, default = today's behaviour.

### P64 — Remove the "Hybrid curves" settings completely (owner, 2026-10-09; done 2026-10-10)
- Owner (screenshot of the settings category «Кривые Hybrid»): «удалить пункты полностью».
- The category `scam_curves_screen` in `res/xml/preferences.xml` (~line 476): the note `scam_curves_note`, `pref_scam_tone_curve`
  (17-point tone curve) + `pref_scam_tone_curve_strength`, `pref_scam_gamma_curve` (33-point per-channel gamma curve) +
  `pref_scam_gamma_curve_strength`. Not the separate `scam_hybrid_ark_curves_category` (ARK) unless the owner says so.
- Do: remove the preferences, their strings (`values/` + `values-ru/`, incl. `scam_curves.xml` arrays), the keys in
  `PreferenceKeys`, the processing (`postpipeline/ScamCurves.java` and its call in `PostPipeline`, plus any use in
  `ToneCurveBuilder` / `PreviewLook` / `MainRenderer` that only serves these keys), settings search / availability / migration
  entries; drop the stored values in `SettingsMigration` (old installs must not keep applying a curve). Default today = "off", so
  photos with the default settings must stay byte-identical (post_ab / replay check). Tests: check_settings_model.py,
  check_ui_language.py, the settings unit tests; update `app/src/test/resources/brand/vectors.tsv` if it lists ScamCurves.
- Done 2026-10-10: category, strings, arrays (`scam_curves.xml`), PreferenceKeys getters, `ScamCurves` + its PostPipeline node,
  `assets/curves`, the shade tile «Кривая»; the four keys in SettingsMigration.OBSOLETE_KEYS (main, module profiles, baseline).
  The curves were off by default, so default photos do not change. brand/vectors.tsv keeps its rename rows (the P55 rename
  table; the renamed keys are then dropped as obsolete). Tests moved to the DCP profile / DNG lossless rows.

### P65 — Gallery: every format we shoot must display, file names in the info bubbles (owner, 2026-10-10; done, device check pending)
- Owner (screenshot of SGallery «Сравнение», two shots «22:53 · ISO 12800»): «исправить отображение всех форматов которые
  поддерживает наша камера. Avif как пример в нашей галерее не отображается. Так же добавь в "пузыри" с информацией о фото название
  файлов».
- AVIF still does not show (after fdaab04 AvifGlideDecoder): check every view that decodes a photo — filmstrip / grid thumbnails,
  the viewer page, the compare screen (`gallery/ui/fragments/ImageCompareFragment`, `gallery/compare/*`), share / info — for JPEG
  (incl. Ultra HDR), HEIC 8 / 10 bit, WebP, AVIF 8 / 10 / 12 bit, DNG, on Android 12 / 13+ (platform AVIF decode exists only from
  Android 12; our own decoder otherwise). Robolectric / instrumentation tests with files from our encoders.
- Info bubbles (the time · ISO chips over each photo in the compare screen, and the same chip in the viewer): add the file name
  (ellipsized in the middle, no overlap at 360 dp), ru + en.
- Done 2026-10-10: AvifGlideDecoder tries ImageDecoder (Android 12+), and when it throws, returns null or an all-black bitmap, or
  below Android 12, decodes with the app's own libavif + libaom AV1 decoder (`processing/avif/AvifDecoder`, JNI
  `scamera_avif::decodeToRgba8`, scaled to the request before YUV -> RGB; libaom built with CONFIG_AV1_DECODER, +~1 MB). Every
  gallery view (filmstrip, viewer, compare) goes through it. tools/check_avif.py (CI) decodes every test file with the host build
  of the same code against Pillow (libavif + dav1d): exact for lossless, >= 45 dB otherwise, maxside scaling. The compare labels
  carry the file name on a second, smaller muted line. HLG / PQ AVIF is shown as coded (no tone mapping) by the own decoder.

### P66 — Pixel 7: every module works (owner, 2026-10-10; partly done, device check pending)
- Owner sent the Pixel 7 camera characteristics (`research/pixel7_camera_characteristics_2026-10-09.html`): «изучи и проверь что
  все модули работают на Pixel 7. Так же если найдешь что-то полезное для него, используй/исправь».
- From the dump: logical back 0 (zoomRatio 0.67..8) = physical 2 (main, 6.81 mm, 4080x3072, GRBG cfa 2, OIS [0,1]) + 3
  (ultra-wide 2.35 mm, 4032x3016, RGGB cfa 0, no OIS); camera 4 = the main sensor's 2x in-sensor crop (6.81 mm, half the physical
  size 4.896x3.6864, 4080x3072, exposure up to 23.8 s); fronts 1 / 6 (3440x2448) and 5 (3840x2736, capability 13); RAW10 and
  RAW_SENSOR everywhere, white 1023, black 64.
- Do: check ModuleRegistry / DeviceDefaults / SettingsAvailability for Pixel 7 (panther) so 0.6x / 1x / 2x / front modules exist
  and open (2x = camera 4, a sensor crop: no second crop of the stream, P60; colour block measured), RAW stream / CFA per camera,
  zoom ratios, OIS / stabilization keys; fix what the dump shows wrong.
- Found 2026-10-10: the camera scan (CameraManager2.scanAllCameras) dropped camera 4 (the 2x crop) and front 5 as duplicates of
  cameras 2 / 1: CameraLensData.equals compares facing, focal length, aperture and flash only (6.81 mm f/1.85 both). On Google
  devices the scan now also compares the 35 mm focal length (50 vs 25 mm; 21 vs 23.5 mm), and a Pixel rescans once (scan_revision
  p66). The 2x module then gets zoom 2.0 from its own field (no second crop). Needs the owner's Pixel 7 log: the modules list,
  opening 0-4 / 0-5, the RAW colour block of camera 4.

### P67 — Owner's logs of 2026-10-09/10: Xiaomi 17 Ultra, Xiaomi 13 Ultra, OPPO Find X9 Ultra, Realme GT8 Pro (done, device check pending)
- 13 Ultra / Find X9 Ultra / GT8 Pro: P54d (d4f2533) put PREVIEW_STABILIZATION and the session parameters (with the module's
  vendor tag, agingtest.mode.select) on every brand; those sessions stalled (13U) or killed the HAL with onError 4 (OPPO family).
  Photo-mode stabilisation and its session parameters are vivo / iQOO only again (CaptureController.vivoBrand); video keeps ON.
  com.xiaomi.sessionparams.clientName = com.android.camera is never sent (no stock-camera impersonation on any phone).
- Recovery: a module that never delivered a frame and fails reverts to the last module that did (sGoodModules, by slot);
  onError / onDisconnected stop the preview-stall watchdog and drop the session; the stall restart count resets only after a
  second of frames; publishLiveRawFrame without a matched result returns (57 NPEs on the GT8 Pro).
- 17 Ultra: the gallery crash (ViewPager cast: the pager's generated id equals a page id) fixed in ImageViewerFragment.getSsivAt;
  the ZSL clip estimate reads packed RAW10 (short frames were never planned on the tele); module picking prefers the active /
  original module over a duplicate of the same ratio and waits 0.1x below a module's ratio before switching down; no
  raw-payload zip for an understood packed RAW10. GT8 Pro: the RAW viewfinder's dynamic black only within 25 % of the static one.
- 17 Ultra ISZ (owner: «при 8.5 не включался ISZ», decision: force mode 9; «2x ISZ с оптическим зумом», in x): the HAL never
  entered mode 2 / 9 for this app (zoomRatio clamped to 3.225, mode 4 up to 17x). On the logical camera current_mode 9 is now
  requested from 6.45x (2 x 3.225x) with the HAL's dial at half the zoom: 3.225-4.3x lens, 4.3-6.45x crop, 6.45-8.6x the lens
  again inside 2x ISZ, 8.6-17.2x crop of the ISZ frame; below 6.41x the mode reported before is requested again. The preview RAW
  (packed RAW10) is read with its stride by the colour-block measurement and the developed RAW viewfinder, shown in mode 9.

### P68 — Hybrid defaults on every phone, forced Shasta with a motion fallback (done, not pushed; device check pending)
- Owner, 2026-10-10: defaults Sabre 6.1 kernel «Всегда», Mochi «Всегда», Bento «Всегда», Bento frames 3, N frames 20, Shasta
  frames 3, Shasta «Всегда». XML defaults and HYBRID_DEFAULTS changed; the app writes sabre61 / mochi / bento / bentoFrames /
  shastaMotionMax to hybrid_tuning.txt also when unset (worker defaults unchanged: replay md5 syn_b1/b2/b4, hand, x7u_1x,
  isz2 unchanged). SettingsMigration P68_DEFAULTS_REV moves stored former defaults once (30, "2", "0", "1", "2", 5).
- New rows under the Shasta switch: «Shasta: режим» (pref_scam_hybrid_shasta_mode 1 auto / 2 always) and «Shasta: отказ при
  движении, % кадра» (pref_scam_hybrid_shasta_motion_max, 4). Always = worker shastaForce 1: no sharpness gate, no ratio limit
  (also in HybridPlan), a frame is dropped when more than shastaMotionMax % of its unclipped 8x8 blocks differ from the base by
  > 4 sigma + 15 % (shastaMotion). Replays: still scenes 0.01 % (x7u_1x), 2.2 % (hand), 1.6 % (isz2); hand with 1 % dropped.

### P69 — Redmi Note 11 Pro (viva, Helio G96, Mali-G57 MC2): speed and device defaults (done, not pushed; device check pending)
- Owner's log 2026-10-10 (Desktop/log-2026-10-10 11 pro.txt, APK before P67): shot 29.7 s; worker 19.4 s of which merge 16.7 s
  (19 frames, Sabre 6.1, F6 7.1 s on the CPU alongside, strips waited 0 ms); post pipeline 5.7 s of which 2954 ms compiling 32
  programs (in-process binary cache empty on the first shot after start); viewfinder 4-5 fps while the GPU merges.
- DeviceDefaults v4 (owner): 12 N frames, 2 Shasta frames, ARK RL 3 amount 1.2 (main preferences and module profiles).
- GLProgramDiskCache: post-pipeline program binaries on disk (driver + source key), loaded on the first shot after a start.
- Open: per-pass GPU times on Mali (scam_dev.txt "hybrid_profile 1") to speed the merge itself; viewfinder starvation.

### P70 — Pixel 7: check that the merge and the denoise work as intended (open)
- Owner 2026-10-10: Desktop/т/p7/p7.zip (3 JPEG 08:51:42/52/59 + log-2026-10-10_P7.txt); RL 3 amount was 1.5 on these shots.
- DeviceDefaults v4: Pixel 7 (Google, device panther) RL 3 amount 1.5 by default (owner).
- Review of p7.zip (100 % crops + log): merge works on all three lenses (camera 3 ultra-wide 0.6x, 2 main 1x, 4 = main sensor 2x
  crop): no ghosts or double edges on wind-moved branches, alignment residual 0.1-0.5 px, Sabre 6.1 on, 18-23 frames, no worker
  errors, programs from driver binaries (32 in 24-36 ms). Denoise (scam-denoise, camera noise model) leaves no visible luma noise in
  sky / shadows. Findings to act on:
  - 2x (camera 4): merge factors 0.55-0.69 on every frame (1x / 0.6x: 0.89-0.95), effective frames 11.8 of 19, Mochi corrections
    3.6 DN10 (0.9 on 0.6x); softer result. Suspect: the HAL noise profile (slope 4.1e-5, near the binned 1x 3.6e-5) understates
    the noise of the full-resolution crop -> over-rejection. Check with a still 2x burst (factors should approach 0.9).
  - Deep shadows of 1x (085152) are smooth to waxy; compare at denoise strength 0.75 / off.
  - 085152 dropped all 5 Shasta frames: the stored shastaSharpness is 0.8 (default 0.35), scores 76-78 %; with P68 Shasta "always"
    they are kept unless they moved.
  - Bento "always" runs on masks of 679-2031 cells (clipping 0.001-0.01 %), ~130 ms each, no visible effect.
  - RL 3 at 1.5: no strong halos at 100 %; thin branches of 2x show light outlines.

### P71 — Pixel 7 follow-up (done, not verified on the phone)
- Worker noiseMeasured (default 1, plain Bayer only): HYBRID NOISE CHECK compares base vs the nearest normal frame on flat
  32x32 green blocks; measured / model 1.5..6 scales every frame's slope / offset (up to x4), above 6 is reported as implausible
  and kept. Replays: x7u_1x 0.95, uw 1.24 (kept); control md5 unchanged (syn_b1/b2/b4, hand, x7u_1x, isz2, uw).
- Pixel 7 only: Bento "always" behaves as auto (no ultrashort frames without clipping); RL 3 at 1.2 on camera 4 (2x) while
  the setting holds the device default 1.5 (DeviceDefaults.rl3Amount).
- Redmi Note 11 Pro only: hybrid_profile 1 written to the tuning (per-pass GPU times).

### P72 — Xiaomi 17 Ultra: module switching, zoom presets, in-sensor zoom RAW14 (done, device check pending)
- Owner's video + log 2026-10-10 (07:50-07:52): pinching below the tele's 3.225x was held by the P67 down-hysteresis; the tele
  cannot show less, so every step snapped back to 3.225x (`sync ... -> 3.225`) until a jump to the main camera at 3.01x; going
  up, the tele took over at 3.18x (below its ratio). Now: down to the wider module at once below the active ratio, up only from
  1.03 x the longer module's ratio (tele from 3.32x); ZoomController.inRange follows (no snap-back while the main camera crops).
- Buttons after «3.2×» on this phone (smooth zoom on): «4.3×» (lens 100 mm), «6.45×» (2x ISZ on, lens back at 75 mm), «8.6×»
  (ISZ, lens 100 mm). XiaomiTeleZoom.presets, ZoomController.onPreset, AuxButtonsLayout preset buttons.
- Mode 9 (ISZ) delivers packed MIPI RAW14 (7140 of 8160 bytes a row) alternating with packed RAW10 frames: the preview fell
  back to the purple ISP picture every ~3 s and 4 post-shutter + 16 ring frames were dropped. RawUnpack / liballocator decode
  RAW14 (RawUnpack.RAW14 / RAW14_TO_10 = >> 4 when the darkest samples sit at 16 x black); RawPayloadCheck.packedLayout finds it
  by the zero tail and a plausibility test; the viewfinder picks each frame's layout (frameLayout); a merge keeps the layout
  most frames have (ScamHybridBurst.keepOneLayout, logged). RAW10 row padding search narrowed to 128 bytes.
- A preview stall of the 0x9002 logical session after it ran (07:36: frames stopped 3 s after leaving mode 9) retries the same
  operation mode twice before stepping down (stepping down parked the lens at 75 mm for the rest of the process).
- Open: whether RAW14 frames share the RAW10 frames' field and colour block (the merge log line tells which layout won).

### P73 — Hybrid Quad / Tetra reconstruction: detail check (done, Tetra day kernel changed; device check pending)
- Owner, 2026-10-10: «перепроверь 2x2 и 4x4 ремозаик, особенно деталь» (Hybrid route only). Bench: a natural scene (Pixel 7 shot,
  linearised, 4096x3072) through tools/quad/gen_mosaic_burst.py (daylight noise 4e-5, 1 and 8 frames, b1 / b2 / b4), worker replays
  on the OPPO, luma PSNR and per-band signal transfer (cross-spectrum / scene spectrum) on the four most textured 1024 px crops.
- Found: the native kernel is the binned one x b x mosaicKernelScale; the shared daylight 0.7 made the Tetra kernel twice as wide
  in sensor px as the Quad one. Tetra 8 frames kept 0.59 / 0.29 / 0.10 of the 0.12-0.2 / 0.2-0.3 / 0.3-0.42 cyc/px bands (Bayer
  0.96 / 0.89 / 0.77, Quad 0.90 / 0.73 / 0.55); 1 frame 0.02 (a binned image). Edge / flat scales did not move it, the kernel scale did.
- Fix: mosaicTetraKernelScale 0.35 by day (Tetra T1; night keeps mosaicTetraNight*). Tetra 8 frames: 38.2 dB (was 33.2),
  0.87 / 0.69 / 0.48; synthetic chart: neutral false colour 0.037 -> 0.017, edges +1.5 dB, background 75.4 -> 70.6 dB (Quad 66.6,
  Bayer 62.5), lattice 3.3 -> 4.8. 0.25 gave a little more detail at more lattice (8.5) and noise (67.2 dB).
- Replays: syn_b1 / syn_b2 / hand / x7u_1x / isz2 unchanged, v10_1 / v10_2 (real night Tetra) unchanged, syn_b4 changed
  (daylight Tetra, intended): new reference e7177fe4.
- Quad left at 0.7 (tuned in P34 on real bursts against ArkCam); on the synthetic natural scene 0.5 gave +1.1 dB. Tetra 2x grid
  (P56) by day uses the new scale too (not measured separately).
- Open: a real daylight Tetra burst (vivo 10x ISZ outdoors) to confirm noise / lattice on the phone.

### P75 — Plain-language descriptions of every setting (open)
- Owner, 2026-10-10: simplify the description of every parameter so an ordinary user understands what it does; each description
  says roughly what happens when the value goes up / down (or the switch is turned on / off). RU (values-ru) and EN (values);
  keep the technical notes out of the UI text (code comments / PLAN instead).

### P74 — «Опыты JSR»: ideas of Jiangtherapee Super Resolution next to Sabre (done, all off by default; device comparison pending)
- github.com/y-g-jiang/Jiangtherapee-Super-Resolution-World-Best: no licence, binary releases + NN weights; ideas only, no code.
- Settings group Hybrid -> Склейка -> «Опыты JSR» (pref_scam_hybrid_jsr_*), worker keys jsr*; off = bit-identical merge (control
  md5 unchanged: syn_b1/b2/b4, hand, x7u_1x, isz2, v10_1).
- jsrPhase: green fill distance of the burst's sites over the CFA period (homography + F6 field, sensor px; jsrCoverage) against
  one frame's; donor kernel sigma x clamp((h/h1)^0.5, 0.4, 1). Natural-scene bench, 8 frames: Bayer 46.24 -> 46.79 dB (top band
  0.77 -> 0.82), Quad 40.23 -> 41.00 (0.55 -> 0.69), Tetra 38.15 -> 38.75 (0.48 -> 0.55); flat noise -6..+7 %.
  Found on the way: on the Tetra native path the global homography is the identity, F6 carries the whole shift.
- jsrLca: the P19 CA model moves every frame's R / B sites inside the merge (JSR_LCA define, kHybMergeCommon only; plain Bayer)
  instead of resampling the result. Synthetic lateral CA (0.8 px corners): 45.03 -> 46.50 dB, top band 0.73 -> 0.80, colour
  error 2.19e-3 -> 2.25e-3. Found: RAW CA modes 1 / 2 (RawTherapee port) misjudged that CA (4.4 / 1.7 px corners, colour error
  x1.7-1.9): look at P28 separately.
- jsrAuto2x: a requested 2x grid merges at 1x and resizes when the green fill distance > 0.5 px (1 Bayer frame: 912 ms vs 1347;
  8 Quad frames 0.53 px: 1396 vs 2795 ms; 8 Bayer frames 0.46 px: 2x kept). 2x detail not measurable on this bench.
- jsrPoly: per-frame robust polynomial (deg <= 4, Huber) of the F6 field, replace or limit (+-0.25). Real handheld bursts: merge
  factors -1..-2 % (replace), ~0 (limit): no gain there; meant for repeating patterns (none at hand).
- Bench: tools/quad gen_mosaic_burst + scratchpad remo/ (eval_one.py, bench.sh), natural scene from the Pixel 7 shot.

### P76 — Fastest possible photo processing on every CPU / GPU without any quality change (open, next after the push)
- Owner, 2026-10-10: speed up processing to the maximum on all processors / GPUs, with no effect on quality. Deep work.
  Gates: control replay md5 bit-identical (or proven equal output), per-stage timings on OPPO (Adreno 750), Mali logs (Redmi
  profile, Pixel 7), post pipeline timings.
- Step 1 (2026-10-10, all SoCs: Snapdragon 8 Gen 2 / 8 Gen 3 / 8 Elite / 8 Elite Gen 5, Dimensity / Mali, Tensor):
  - CPU `mosaicChromaMedian`: interior median-5 without clamps, row pointers, row blends, column bands for the box filters
    (bit-identical; OPPO 0.8-1.0 s -> 0.75-0.84 s per mosaic shot).
  - GPU passes read their neighbourhoods once per 8x8 group from shared memory: `kHybCells` (the 6.1 per-frame covariance read
    36 RAW sites a cell, each quad for 9 cells; `sabreQuad61` / `sabreCov61Q`), `kHybFlags` (25 + 25 reads a site), `kHybDilate`
    (25 reads a cell); `kHybReject` reuses its 3x3 cells for the bilinear sample; `kHybMark` / `kHybNatMark` write a RAW word only
    when its flag bits change. Cells / flags / reject / mark bit-identical; dilate differs by float rounding only (Adreno 750:
    max |diff| 1.2e-7 on x7u_1x, 2.3e-6 on syn_b4 at signal 0.015 / 0.3, trailers equal).
  - OPPO profile 1, x7u_1x: flags 309 -> 132-193, mark 393 -> 304, cells 623 -> 536-543, dilate 533 -> 234-240 ms. Mali-G57
    (Redmi Note 11 Pro: cells 2.0-3.0 s, mark 1.2-1.8 s, flags 0.8-0.9 s, dilate 0.5-0.8 s a shot) waits for the owner's log.
  - New control md5 (since this step): syn_b1 5b988db1, syn_b2 99f29aab, syn_b4 421efc48, hand 6cab2c98, x7u_1x 52bcce71,
    isz2 99c972ae, v10_1 58c09ee5, v10_2 2176b21b.
  - Owner allowed up to 10 % quality loss on the Redmi Note 11 Pro only; nothing traded yet (needs its Mali timings first).

### P77 — Redmi Note 11 Pro: ghosts on moving objects and blotches on walls (2026-10-10, owner's archive «redmi 11 pro.zip»)
- Noise: HYBRID NOISE CHECK measured / model 0.06..0.24 in every shot (spatial noise of the reference RAW ~0.05 of the model as
  well): the HAL SENSOR_NOISE_PROFILE is ~16 x too high, the rejection took motion for noise (double images, the smeared cat).
  The worker now scales the model down as it scaled it up (ratio 0.02..0.67 -> x max(ratio, 1/16)); the finish denoise
  (`ScamDenoise`) takes the same factor from the report line (`ScamHybridBurst.lastNoiseFactor`, test ScamNoiseFactorReportTest).
- ZSL ring: 4 of 12 frames were 3.6 s older than the other 8 (same exposure before an AE ramp): the scene of another moment,
  merged as blotches on the wall (owner: not there in reality). `selectHybridRing` drops the frames behind a gap of more than
  max(0.5 s, 4 base exposures).
- Open: owner's shots with the new build (the same scenes), Mali timings with the shared-memory passes.
- 2026-10-10 evening (owner's r11p.zip, P77 build): noise factor applied in every shot (x0.22..0.31 by day, x1/16 at night),
  ring frames contiguous, no ghosts on the cat. Mali-G57 per shot vs the old build: flags -60 %, cells -50 %, mark unchanged,
  dilate +20 %, reject slightly slower -> P78.

### P78 — Redmi Note 11 Pro without OIS: steadiest ZSL frames, RL 3 1.5, night colour denoise, Mali pass variants (2026-10-10)
- No OIS (LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION without ON, every such camera): of the newest 2 x requested ring frames at
  the exposure the newest and then the steadiest by gyro shakiness are kept (`steadiestFirst` / `steadiestOrder`, only frames
  with gyro samples; dev switch `zsl_steadiest 0` = newest as before). Night shots there: 60 ms frames, shake 10..60 x apart.
- DeviceDefaults v5: Redmi Note 11 Pro RL 3 amount 1.5. `DeviceDefaults.chromaDenoiseFactor`: its colour denoise x1.2 from base
  ISO 3200 (ScamDenoise multiplies `dn_chroma_mult`; log `chromaNight=x1.2`).
- GPU: kHybDilate / kHybReject shared-memory variants only on Adreno (`SHARED_TILES`; SCAM_HYB_NO_TILES=1 for debugging); every
  other GPU runs the earlier code: on the OPPO with SCAM_HYB_NO_TILES the output is the pre-P76 md5 (syn_b1 103304c8, x7u_1x
  ae73516c, hand 0a46d3be), i.e. cells / flags / mark / reject are bit-identical everywhere. kHybMark: one invocation per word of
  two rows (the cell clip test once per cell, not per row); Adreno md5 unchanged (P76 references).
- Open: Mali timings of the owner's next log.

### P79 — Redmi Note 11 Pro: fastest processing within 10 % quality (owner, 2026-10-10; not pushed yet: owner's order)
- GPU profile off by default again (P69 had it on for the Redmi): profile 1 (a glFinish after every pass) made the merge
  30-60 % slower on the OPPO replays (x7u 4.0 -> 3.0 s, hand 4.7 -> 2.4 s, syn_b2 4.5 -> 1.8 s total), output unchanged. New
  switch «Замер времени проходов GPU» (pref_scam_hybrid_profile, Hybrid diagnostics) or scam_dev.txt "hybrid_profile 1".
- Bento "always" works as auto on the Redmi (as on the Pixel 7): its 3 ultrashort frames went through every GPU pass with
  weight 0 in each shot ("would fall back: not enough clipping"); with clipping in the buffered frame Bento runs as before.
- s61Mode 6 on the Redmi (quality trade allowed there): donors take the base 6.1 covariance, no per-frame covariance in the
  cells pass (Mali-G57 1.1-2.6 s a shot). OPPO: synthetic PSNR 46.24 -> 46.30 / 42.24 -> 42.24 dB, real x7u detail energy
  -0.2 %, output rms difference 2.7 % of the signal. A user / scam_dev s61_mode value is kept.
- Not traded: N frames (12 -> 10 would be +9.5 % noise), F6 local alignment (CPU, parallel to the merge; check "strips waited"
  in the next log once the GPU is faster).

### P80 — CPU steps of the shot to the GPU, timing file, fast JPEG (owner, 2026-10-10; Colour processing of 9.6 dropped by the owner)
- Table of CPU steps measured on the OPPO (Adreno 750) before porting; only what pays was moved:
  1. Chroma median of the mosaic results on the GPU (`scam-chroma-gpu.h` ChromaMedianGpu; context built during the merge,
     torn down on a helper thread; tuning chromaGpu 0 = CPU): 740-850 -> 300-440 ms a Quad / Tetra / ISZ shot. The boxes sum in
     float and the soft counts run in float: max |diff| 1.9e-6 (syn_b2), 6e-8 (v10_1); chromaGpu 0 gives the old md5.
  2. Mosaic preparation: measured 0.2 s (binning 0.2 s, site response 4-18 ms on real bursts, 0.58 s on syn_b2 only), not the
     0.6-0.8 s estimated; a row-wise CPU rewrite was bit-identical but slower (reverted). HYBRID MOSAIC NATIVE: total now
     reports before / merge / after.
  3. Mochi was already on the GPU (54-133 ms a frame on Adreno 750, ~650 ms on the Mali-G57): the base rows are uploaded once
     for every bracketed frame of the shot (bit-identical, x7u mochi 2: frames 2.. 20-35 % faster).
  4. Worker -> app RGB as IEEE half floats (job marker rgb-half; scam_dev "hybrid_rgb_half 0" = float32): 96 instead of 168 MB
     for 12 MP, ScamRgb reads the halves (clip statistics, decimated float copy) and uploads RGBA16F bands. The first stage stored
     the RGB in half floats anyway; transport error <= 5e-4 relative, trailers byte-identical. Write on the OPPO 144 -> 118 ms.
  5. ScamRgb statistics: already on a helper thread alongside the upload (0-46 ms left on the shot's path): not moved.
  6. F6 local alignment on the GPU: not done. On the flagships it runs alongside the GPU merge (not on the shot's path); on the
     Redmi the GPU is the bottleneck and the F6 field would take GPU time from the merge.
  7. «Быстрый JPEG» (pref_jpeg_fast_encoder, «Конфиг», off): Android's encoder (4:2:0) instead of jpegli; the owner chooses.
- «Замер времени проходов GPU» on: every timing line of the shot also goes to Download/SCAMERA/SCAMERA-timings.log
  (ScameraTimingLog: a header at the shutter, then the lines with ms / elapsed / TIMELINE; test ScameraTimingLogTest).
- Control md5 since P80 (default tuning): syn_b1 5b988db1, x7u_1x 52bcce71 (plain Bayer, unchanged); mosaic series (GPU chroma
  median): syn_b2 0bfa468b, syn_b4 fc0b4a35, hand 1d1ac3bc, isz2 3c58de7a, v10_1 104bd930, v10_2 94ec0f73. With chromaGpu 0 the
  P76 references (hand 6cab2c98, isz2 99c972ae, syn_b4 421efc48).

### P81 — Gallery: crashes on swipe, WebP / AVIF blank until a restart (owner, 2026-10-11)
- Crashes: all 9 entries of the X200 Ultra's SCAMERA-crash.log (2026-10-09..10) are the ViewPager-cast ClassCastException that
  fe419b2 (P67) fixed; the phone ran the build of 2026-10-09 22:44, older than that fix.
- WebP blank until a restart: the camera writes the file in place for 7.4-7.7 s (WebP 12 MP; a 12-bit AVIF 9.3 s) and its
  MediaStore row exists from the first byte; the viewer gave up after 2 retries (2.1 s), and every Glide key was name +
  DATE_ADDED, which never changes, so a picture decoded from the unfinished file stayed cached. Now: 5 retries (~22 s), a page
  that gave up is decoded again when shown or on resume (ImageAdapter.reloadIfGaveUp), keys carry the file's current length
  and mtime (gallery/glide/GalleryKeys, test GalleryKeysTest) in the viewer, grid, filmstrip and histogram.
- «WebP: Display P3 profile not added … .webp.icc.tmp: Operation not permitted»: DCIM refuses a non-image extension. The ICC
  (WebP) and colr (HEIC) rewrites take their scratch copy in the app's cache and write it back in place (the MediaStore row
  and content URI stay).
- AVIF: the decoder of P65 (750bf96) is newer than the build on the X200 Ultra; device check of a 12-bit 4:4:4 file pending.

### P82 — Manual mode: shutter / ISO priority (owner, 2026-10-11)
- One of ISO and shutter by hand, the other follows the scene (EV applies): Android 16 CONTROL_AE_PRIORITY_MODE where the
  module lists it (Xiaomi 17 Ultra [0 1 2]; a HAL that does not keep the hand-set value 15 frames in a row falls back),
  else AE off and the free parameter moves on the viewfinder's mean brightness (LiveSceneMeter -> manual/SceneLuma, 64 x 64
  every eighth frame, only while the loop runs): the target is the AE's own brightness (first measurement after the switch,
  which keeps the AE's exposure), 1/6-stop dead band, 2/3 of the error a step, <= 2 stops (manual/ExposurePriority, test
  ExposurePriorityTest). Before, the other parameter stayed frozen at the last metered value.
- Open: the ISO / shutter range the rulers show (owner: «не отображается верно») — what is wrong on which phone / lens is not
  in a log yet. Found on the way: a hand-set shutter longer than 1/5 s reaches the viewfinder only as 1/5 s and the photo takes
  the viewfinder's (ZSL) exposure, so the photo is 1/5 s too. -> P84.
- Owner's answer on the range (2026-10-11): the minimum and maximum of ISO and shutter are wrong. Which phone / lens and what
  the rulers show vs what is expected: still to be seen on a device (the X200 Ultra was not connected).

### P84 — Manual shutter longer than 1/8 s: PSL instead of ZSL (owner, 2026-10-11; done, device check pending)
- The Hybrid with a hand-set shutter > 1/8 s (ParamController.PSL_SHUTTER_NS) takes N after the shutter at that shutter
  (hybridNPlan(manualPsl) -> HybridPlan.buildNormalBack, 4 N + Bento / Shasta of the plan), the ring is released, the RAWs are
  routed as with an empty ring. ISO: the hand-set one, or in shutter priority the viewfinder's exposure (<= 1/5 s) moved onto
  the long shutter (ParamController.pslIso, test ExposurePriorityTest). The SCAM HDR route keeps the ring.

### P85 — Gallery button: the badge inside the gallery icon 20 % smaller (owner, 2026-10-11; queued)

### P86 — Photo mode «Движение» (owner, 2026-10-11; done, not on a device)
- A separate photo mode for moving subjects: the best possible quality of what moves and the fastest capture (no moment
  missed), fast processing; HDR may be weaker.
- Its own settings group inside the Hybrid settings group.
- To work out: capture (shutter-moment frame, short exposures, fewer / no bracketed Shasta frames, ZSL ring at the press),
  merge (reject / motion masks favouring the base frame, Bento only where it does not ghost), processing time budget, the
  shutter-to-shutter interval; measure on the OPPO replays and the owner's handheld shots.
- Done 2026-10-11 (owner: switch beside the settings gear in the viewfinder, «красивая иконка и анимация», «максимально
  хорошее движение и максимально быстрый буфер», «максимально возможный фпс видоискателя»):
  - settings/MotionMode + PreferenceKeys.isMotionMode (global pref_motion_mode, also a row under the route selector);
    hybridValue / hybridSwitch return the mode's own pref_scam_hybrid_mm_<key> for zsl_frames 8, shasta off, bento auto,
    motion_boost on, boost_value 10, motion_threshold 0.5 (group «Движение» in Hybrid, scam_hybrid_motion_screen, with
    mm_fps: 0 = maximum).
  - Viewfinder + ZSL ring: the highest fixed AE range [f, f] within the RAW stream's min frame duration (X8U / X200 Pro
    [60, 60] if RAW keeps up, else [30, 30]): exposure <= 1/f, ring refreshed most often; toggling restarts the session
    (AE range is a session key).
  - Top bar: motion_mode_button (44dp card left of the gear): off = gear card, subject standing; on = accent card, the
    subject overshoots forward and three speed lines are drawn one after another (topbar_avd_motion_on / _off), the card
    springs 0.86 -> 1. Tests MotionModeTest, ViewfinderUiTest (position / size).
  - Check on the phones: log "motion mode: camera N AE range [f, f]", ring frame exposure, press-to-frame time, merge time.

### P87 — Manual rulers' ISO / shutter range, priority on every phone (owner, 2026-10-11; done, device check pending)
- Owner's screenshots (X200 Ultra): stock pro mode ISO 72..12800 (main, ultra-wide), 50..3200 (tele), up to 32 s; ours
  1144..3200, then 1144..5359 on the same module. Camera service dump (2026-10-11): every module declares
  sensitivityRange [1144 3200] (tele [400 800]) and exposureTimeRange up to 0.33 s, with maxFrameDuration 33 s (tele 29 s);
  the vivo keys com.vivo.pro_isoMin 72 / pro_isoMax 12800 on the main and ultra-wide modules, none on the tele. MotionCam Pro
  5.0.9 (decompiled) reads only SENSOR_INFO_SENSITIVITY_RANGE.
- capture/ExposureRanges (test ExposureRangesTest), through CameraProperties.rangeProvider: ISO = declared ∪ vendor pro range
  ∪ the ISO extremes the module's own AE used (kept per physical camera in prefs, scam_exposure_ranges); shutter ceiling =
  maxFrameDuration rounded down to a stop when the declared ceiling is >= 4x below it (main 32 s, tele 25 s). The priority loop
  and the PSL ISO use the same ranges.
- Priority, in order: Android 16 CONTROL_AE_PRIORITY_MODE (X200 Ultra lists only [0]); the Qualcomm vendor priority as MotionCam
  does it (disassembled: org.codeaurora.qcamera3.iso_exp_priority select_priority 0 = ISO with use_iso_exp_priority and
  use_iso_value = ISO, 1 = exposure with use_iso_exp_priority = ns and use_iso_value 0, AE on; all 0 for auto); else the
  software loop of P82, which works on every phone. Each HAL way falls back to the next after 15 frames off the hand-set value.
- To check on the phone: the HAL honours ISO 72 / 12800 and shutters > 0.33 s for our package (ExposureLimits learns caps).

### P88 — MotionCam's frame buffer vs our ZSL ring (owner, 2026-10-11; queued)
- Study how MotionCam Pro buffers RAW frames (decompiled 5.0.9: GpuRawBufferManager, high-priority buffers / containers in
  libnative-camera-host.so, NDK and Java capture backends) and whether our ZSL ring (CaptureController ring, ShotArena memfd,
  stale-frame cutoff P77) can be improved: capacity, memory, copy cost, shutter-moment latency.
- First read (2026-10-11, symbols of libnative-camera-host.so): every RAW is copied into the app's own buffers when it arrives
  (RawImageConsumer::doCopyImage -> RawImageBuffer; AImageReader via onRawImageAvailable), so the camera's image goes back to
  the HAL at once and the reader stays small; the history lives in GPU (Vulkan) memory sized to a memory budget
  (GpuRawBufferManager::initBuffers / growInputBuffersToBudget / setTargetInputFrameBuffers / bufferSpaceUse, memory events
  onMemoryAdjusting / onMemoryStable, clearOldBuffers); a shot takes frames from that history without another copy
  (consumeBuffersForCapture(n), copyFramesFromZsl(n), consumeLatestBuffer, setBurstModeHistoryEnabled, createContainer into an
  in-memory container).
- Ours: the ring holds the camera's own Images (ImageReader maxImages, «Буфер ZSL» up to 50 HAL buffers held) and copies the
  N frames into the ShotArena memfd after the press.
- Idea to measure: copy-on-arrival into a preallocated memfd ring the worker maps (ShotArena as a ring), the reader down to a
  few images: no copy after the press (shutter-to-merge latency), fewer HAL buffers held (preview stalls, HAL memory), ring
  depth set by a memory budget. Cost: ~24 MB a 12 MP frame, 0.7 GB/s at 30 fps of memcpy (battery, heat): copy only frames the
  shot can use (exposure-matched, every k-th) or only while the shutter button is touched. Prototype and measure on the OPPO
  before deciding.

### P89 — Gallery: black photos right after the shot (owner, 2026-10-11; done, device check pending)
- Log: WebP Display P3 saved in 11.1 s, AVIF HLG in 36.8 s; a WebP read while it is written decodes with the missing part
  black (BitmapFactory takes incomplete input) and was shown and cached as it was. processing/SavingFiles: PhotoOutput marks
  each file while it writes it (encode + EXIF / ICC); the viewer page, grid, filmstrip and the binding thumbnail wait for it
  (GalleryKeys.waitForFile, every 0.4 s, at most 3 min), then decode under the new length / mtime key.
- Tele ISO: the stock pro range 50..3200 of the X200 Ultra tele (ExposureRanges.knownProRange, it declares 400..800) under the
  AE widening the owner keeps; long shutter on the device still capped at 1/3 s: ExposureRanges logs declared / maxFrameDuration.

### P84b — Manual long shutter: N at the hand-set shutter, no LONG frames (owner's 4 s shot, 2026-10-11)
- Log of the 4 s shot: normal-back clamped N to the declared 0.33 s (the HAL then gave 0.5 s and 4 s frames exactly), the plan
  added three 4 s LONG frames at ISO 144, the 16 s series ended in camera error 4 and the photo was merged from 0.33 s frames.
- HybridPlan.manualLong: manualNormals(t) N frames (4 down to 1, the series about 8 s at most) at exactly the hand-set shutter
  and ISO, plus the plan's Bento frames; no LONG (test HybridPlanTest). Device check pending.

- P88 measurement (X200 Ultra log, 2026-10-11): the ring copy after the press is "ZSL copy 20 RAWs in 144 ms" / "11 RAWs in
  62 ms", started after the series submit and overlapped with it (series 192-196 ms, preview->series ~300 ms): copy-on-arrival
  would take < 0.15 s off a shot that is not waiting on it, for a continuous ~0.7 GB/s memcpy. Not implemented. What the log
  shows instead: "38 ring RAWs not used (exposure +0.07..+0.12 EV from the newest frame)" of 49 — the ring holds ~1.1 GB of HAL
  buffers of which a fifth matched the shot; candidates: a smaller ring, or frames kept by exposure match.

### P84c — The 4 s shot's camera error: session reconfiguration; Shasta from the shutter; viewfinder at 10 fps (owner, 2026-10-11)
- logcat of the 4 s shot: "reconfigureCamera: Can't idle device in 12.500000 seconds!" then error 4. AE_TARGET_FPS_RANGE is a
  session key on the X200 Ultra: the series (still template's range) and the viewfinder's repeating request (its own) made the
  service reconfigure between them, waiting for idle behind the three 4 s frames. The Hybrid series now takes the
  viewfinder's fps range. (The same switch may be part of the ~0.7 s viewfinder gap around every shot: check PREVIEW_GAP.)
- Owner: the series may take any time, with Bento and Shasta that help it: manualLong = 4 N at the hand-set shutter, Bento
  always, every Shasta LONG of the plan with its ratio from a longer shutter at the same ISO (4 s -> 8 s ISO 72, not 4 s ISO
  144), the gain only past the module's shutter ceiling (HybridPlan.manualShasta, tests).
- Viewfinder in manual at most 1/10 s (10 fps, ParamController.PREVIEW_SHUTTER_MAX_NS, was 1/5 s); PSL from 1/10 s (the ring
  holds viewfinder frames).

### P90 — Viewfinder freeze at the shot and during processing (owner, 2026-10-11; built, device check pending)
- Measured (X200 Ultra, PREVIEW_GAP / VF_DRAW): 718-822 ms viewfinder gap at every shot (preview->series ~300 ms, series
  ~200 ms, series->preview ~250 ms); during processing single draws up to 167 ms.
- ArkCam 1.05 / GCam keep the viewfinder in the requests; ours sent a RAW-only series. Now the Hybrid / SCAM series also
  targets the viewfinder surface (scam_dev "series_viewfinder", default 1) and MainRenderer scales each series frame back to
  the viewfinder's exposure (ViewfinderGain: sensor timestamp -> ISO x shutter recorded at onCaptureStarted, main_fs.glsl
  uGain in linear light), so the Bento / Shasta frames do not flash. With P84c (the series takes the viewfinder's
  AE_TARGET_FPS_RANGE, a session key on the X200 Ultra) the HAL no longer reconfigures between preview and series.
- SurfaceTexture frame callbacks on their own HandlerThread (VfFrames, display priority) instead of the main looper; the
  viewfinder's EGL context at high priority (EGL_IMG_context_priority, scam_dev "vf_gpu_priority"; processing contexts are low).

### P91 — ZSL ring: hybrid of ours, MotionCam, ArkCam 1.05 and LMC (owner, 2026-10-11; built, device check pending)
- Studied: MotionCam Pro 5.0.9 (every RAW copied into its own GPU buffers on arrival, depth by a memory budget,
  consumeBuffersForCapture without a copy); ArkCam 1.05 (com.arkcam, decompiled: RAW reader min(max(2 x frames, 8), 48),
  acquireLatestImage, each frame pushed into a native ring of max(30, 2 x frames) / 40 and its Image closed at once, live view
  from RAW, post-shutter only 2 ultrashort frames without flush); LMC 9.6 remod (GCam: 25 ZSL + a few post-shutter frames,
  ImageReader of 50); ours (ring of up to 50 HAL Images, reader ring + 3 = 53 HAL buffers, copy after the press into a
  per-shot memfd, exposure tiers / stale gap / steadiest selection).
- Built: capture/ZslRing — one memfd ring; every preview RAW copied into a free slot on arrival and its Image closed
  (processing/RingFrame: the ring holds RingFrames, a camera Image when no memfd); depth min(setting, 40 % of available
  memory / slot) + 16 spare slots for the shot; RAW reader down to scam_dev "zsl_reader" (default 20). Native per-slot view
  counts (allocator.cpp arenaView / arenaSlotRefs): a slot is written again only when no view is left, so a shot's frames stay
  pinned while the merge runs and the ring keeps cycling the other slots. A plain RAW16 ring frame goes to the worker as a
  view of its slot (no copy after the press); packed / cropped / binned frames are converted into reserved slots of the same
  memfd, and the post-shutter series too (also for PSL shots), so the whole burst is one arena for the worker (no transport
  copy). The frame selection is unchanged. scam_dev "zsl_memfd_ring 0": the old ring. Tests ZslRingBudgetTest,
  CameraResumeTest (ring frames as RingFrame).
- To check: PREVIEW_GAP / VF_DRAW at shots and while processing, "ZSL ring in memfd" line, shot-to-shot, memory, md5 of a
  replay is unaffected (the worker reads the same bytes).

### P92 — Processing continues when the app is left (owner, 2026-10-11; built, device check pending)
- Behind the screen the app (and its merge worker, a child process) was cached and frozen or killed. processing/
  ProcessingKeepAlive: a foreground service (specialUse, FOREGROUND_SERVICE + FOREGROUND_SERVICE_SPECIAL_USE, a silent
  low-priority notification «Обработка фото…», a partial wake lock of at most 10 min) started at the shutter (takePicture,
  while the app is in front: a start from the background is refused on Android 12+), stopping itself 3 s after nothing is
  captured, queued (sScamPending) or processed.

### P85b — SGallery launcher frame 15 % smaller (owner, 2026-10-11; done)
- drawable/sgallery_icon_scaled: the generated foreground inset 7.5 % on every side, used by ic_gallery_launcher(_round).

### P93 — SCAM TEST report of the OPPO Find X7 Ultra with root (owner, 2026-10-11; read)
- File: Desktop/SCAM-TEST_OPPO-PHY110_20261011-080917.txt (com.scam.test, Android 16, 142 k lines, root sections).
- Without the package spoof the app sees cameras 0 (logical, physical 2 + 3), 1 (front), 2 (main 8.67 mm), 3 (UW 2.59 mm);
  the HAL has 7: 4 = 15.38 mm (3x tele), 5 = 22.36 mm (6x tele), 6 = main 8.67 mm (another mode), all ISO 100..6400,
  exposure up to 32 s (4) / 105 s (5).
- aeAvailablePriorityModes [0] on every module (no Android 16 priority); org.codeaurora.qcamera3.iso_exp_priority
  present in the static metadata (iso_available_modes, exposure_time_range), not in the app's request-key list: the P87
  Qualcomm way is tried and falls back to the software loop after 15 frames if the HAL ignores it.
- aeTargetFpsRange is a session key here too (P84c applies).
- ImageReader hold test, RAW_SENSOR 4096x3072 (~24 MB): camera 0 stalls with 13 frames held (116 capture failures),
  cameras 1 / 2 hold 62 but with 164 / 187 capture failures; YUV holds 62 without failures. The old ZSL ring held up to 50
  camera Images: evidence for P91 (copy on arrival, reader of 20). maxImages <= 62 (63 aborts natively).
- OIS: Camera2 lists only OFF on every module while the device tree has qcom,ois@0 / @1 (the hardware has OIS, the HAL does
  not expose it): isOisSupported() is false on the OPPO, so the P78 steadiest-frame selection runs there (harmless: it keeps
  the newest frame and picks the least shaken of the rest).

### P94 — SCAM TEST reports of the Find X8 Ultra, Find X9 Ultra, X100 Ultra, X200 Ultra (owner, 2026-10-11; read)

- Find X8 Ultra (PKJ110, no root): 6 cameras listed without a spoof (0 = logical 2/3/4/5, 1 front, 2 main 8.67 mm, 3 UW,
  4 tele 16.53 mm, 5 tele 24.85 mm). ISO 100-6400 (UW 100-16000), exposure up to 30 s, frame duration up to 36-105 s.
  aeAvailablePriorityModes [0], no iso_exp_priority keys: priority goes through the software loop.
- Find X9 Ultra (CPH2841, owner: «это оппо X9U»): 6 cameras listed; main RAW 4080x3064; ISO main 100-9100, tele 20.1 mm
  100-12700, 34.8 mm 100-16000; exposure up to 30 s. aeAvailablePriorityModes [0 1 2] on every camera and aePriorityMode is
  a request key: the HAL priority path of ParamController applies; the result also carries iso_exp_priority.use_iso_value.
- X100 Ultra (V2366GA): only 0 / 1 listed (2-6 hidden, 6 = mono 3840x1080). ISO 50-3200 (tele 22.48 mm 50-6400), exposure
  up to 22-29 s declared without narrowing (unlike the X200 Ultra). RAW_SENSOR from the test is plain 16-bit.
- X200 Ultra (V2454A, root): declared ranges narrowed as known (main/UW 1144-3200, 0.33 s; tele 400-800, 0.23 s);
  com.vivo.pro_isoMin/Max 72/12800 on main/UW only, none on the tele (the tele's postRawSensitivityBoostRange 100-3199 is not
  a sensor range); iso_exp_priority.exposure_time_range repeats the narrowed exposure (so the vendor shutter priority stops at
  0.33 s; the preview is capped at 1/10 s anyway). Logical camera 0: the RAW hold test got 0 frames (stalled) - the app runs
  on the physical cameras, check the test itself.
- All four: aeTargetFpsRange is a session key (P84c covers every phone). Every OPPO module lists OIS OFF only.
- RAW hold test (maxImages 62): RAW_SENSOR held -> capture failures on every phone (X8U 34-40, X9U 33-39, X200U 1-22,
  X100U ~1900-2300, the X100U also for YUV/PRIVATE), YUV on the OPPOs 0-3. Supports P91's short reader. Open: the test only
  holds 62, so the depth where failures start is unknown - a stepped hold test (8/12/16/20/24/32) in SCAM TEST would set the
  zsl_reader default per phone.

### P95 — OIS forced on every phone (owner, 2026-10-11: «нужен рабочий OIS на оппо, форсировать его работу на всех смартах»; done, not on a device)

- Found in the SCAM TEST reports: the Find X8 / X9 Ultra list LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION = [OFF] on every
  module, yet TEMPLATE_PREVIEW / STILL_CAPTURE / ZSL carry OIS ON on the main and tele modules (0/2/4/5) and the live results
  report ON; MANUAL carries OFF. UW and front: OFF everywhere, and the HAL's gyro config says "ultrawide ois_on false".
- CaptureController.oisRequestable: OIS mode requests (applyOisMode on the preview and the series, the P54b forced
  stabilisation) now go to every camera, not only to those listing ON; the user's «OIS off» (oisMode 2) still sends OFF.
  scam_dev "ois_force 0" restores the listed-only behaviour. Log once per camera: "OIS forced ON on camera <id>".
- hasOisHardware / halHiddenOis (PHY110, PKJ110, CPH2841): the P78 steadiest-frame selection is skipped there (gyro shake
  does not show what OIS removed). HUD OIS badge follows the reported result mode.
- Check on the OPPO: stab_trace 1 -> reqOis/resOis on the preview and the series of the main, UW and tele; no capture
  failures from the forced key on the UW/front (where the HAL may reject it).

### P96 — SCAM TEST reports 2: Find X9 Ultra (16:08), X100 Ultra (11:07), Xiaomi 13 Ultra (2304FPN6DG, root), Find X7 Ultra (root) (owner, 2026-10-11; read)

- Find X9 Ultra, memory-bounded hold test: 24-53 RAW_SENSOR frames held with 0 capture failures on every camera (the first
  report's 33-39 failures were at 62 held). The X9U is not short of camera buffers below ~50.
- Xiaomi 13 Ultra: the HAL stalls with 7-9 RAW frames held by the app on every camera (19-880 capture failures), and the
  camera provider died during the hold test of camera 6 (all devices removed at 14:11:55; cameras 7-12 missing from the report).
  The old ZSL ring (up to 50 held Images) could not have worked there; P91 (copy on arrival, Image closed at once) fits, and
  the post-shutter frames are copied on arrival too (SaverImplementation -> ImageFrame into the shot arena). Exposure is
  declared up to 1 s (front 0.5 s), maxFrameDuration 1 s; ISO main 50-3200, others 50-804 / 50-1119. OIS listed [0 1] on
  main / tele modules. The HAL carries org.quic.camera.manualExposure.disableFPSLimits (byte) and overrideSensorFrameDuration
  (int64) in its requests/results (the 17U stock app always sends 0): a possible route past 1 s - untested, needs a device.
- Find X7 Ultra: as P93 (logical 0 stalls at 13 held, physical 0/2/3 hold 62 with 113-212 failures); OIS ON in the
  PREVIEW / STILL templates and results of cameras 0 and 2, OFF on UW 3 and front 1 (as the X8U / X9U, see P95).
- X100 Ultra: unchanged (holding RAW / YUV / PRIVATE -> thousands of failures).
- SCAM TEST 1.10: after a stalled hold, the other formats of that camera skip the hold test (kept the Xiaomi provider alive).

### P97 — SCAM TEST report of the vivo X200 Pro (V2405A, MT6991, Mali; owner, 2026-10-11; read, one fix done)

- Cameras: listed 0 (logical main, physical 2/3/4) and 1 (front, FULL, no RAW capability); unlisted 2 main 6.54 mm,
  3 tele 22.48 mm (OIS [1] = ON only), 4 UW 2.32 mm (RAW 4080x3060), 5 = auxiliary: focal 0.0, ISO [0, 0], 1920x1080,
  black 256.
- Fix (done, not on a device): CameraManager2.isAuxiliarySensor also drops sensors with focal length 0, ISO max 0 or
  exposure max 0 (AuxiliarySensors.noLensGainOrExposure; X200 Pro camera 5 = P39's configure-failure / error 4 loop, X200
  Ultra camera 7 = exposure [0, 0], no preview frame); the camera scan cache is dropped once (aux_revision "aux2"); the lens
  switch refuses such an id with «служебный поток». Test AuxiliarySensorTest.
- RAW: the main (0 and 2) delivers 4096x3072 with the last row all zero (the known 4000x3000 picture with zero padding; the
  arrays still say 4096x3072); tele and UW have signal in the last row. Black-floor skip of the padding exists (Parameters);
  a crop of the saved photo to the valid area is still open (needs a shot from the phone to measure the area).
- ISO: logical 0 says 71-6400, its physical main 2 says 72-12800 (the app can show 12800 on the main if the logical HAL
  takes it - untested). Exposure up to 16 s on rear modules, front 0.43 s. Priority modes [0] only: software loop.
- Hold test (MediaTek HAL): 52-62 RAW frames held on every camera with 0 capture failures - no buffer limit concern.
- Front camera (owner: «сможем её запустить?»): FULL, no RAW capability; the HAL accepts PRIV 1920x1440 + YUV 3264x2448 (and
  + JPEG / PRIVATE) and streams 27 fps; a YUV / PRIVATE reader configures up to maxImages 52 (53+: configure failed) and holds
  52 without failures. P52 (retry without the vivo stock preview profile, 9f6a459) is on main, unconfirmed by the owner.
  Found and fixed (P91 regression): a YUV ring frame went into the memfd ring, which copies one plane (colour lost), and the
  reader was cut to 20 while YUV Images stay held (the ring would starve). Now multi-plane frames are held as Images and the
  short reader applies to RAW streams only. Open: the photo route of a RAW-less camera is untested on the phone.

### P98 — Vendor tags worth using, from all SCAM TEST reports of 2026-10-11 (owner's question; candidates, nothing wired yet)

Reports: X8U, X9U, X7U (root), X100U, X200U (root), X200 Pro, vivo V2515 (MT6993), Xiaomi 13U (root), Xiaomi 17U (root).
- Real exposure / ISO ranges above the declared ones (manual mode, ExposureRanges):
  - OPPO X7U: com.oplus.pro.extension.iso.range (int32[2], support flag com.oplus.pro.extension.iso.support = 1): main
    50-12800, UW / teles 50-3200 (declared 100-6400) - the OPPO twin of com.vivo.pro_isoMin/Max. X8U / X9U: unknown (no root
    dump), try reading it by name. iso_exp_priority.exposure_time_range on X7U: UW 91 s, 6x tele 105 s (declared 36 / 32 s).
  - Xiaomi 17U: xiaomi.sensor.info.exposureTimeRange / maxFrameDuration up to 36-180 s and iso_exp_priority.exposure_time_range
    100-112 s on main / tele, while Camera2 declares 1 s; com.xiaomi.sensorInfo.fullsizeQuad.isoRange 50-12800. Long frames
    probably need org.quic.camera.manualExposure.disableFPSLimits / overrideSensorFrameDuration (present on every Qualcomm
    phone; stock 17U always sends 0) - device test needed.
  - vivo X200 Pro / V2515 (MediaTek): request keys vivo.control.maxExposureTime, vivo.control.iso / iso_total,
    vivo.control.slowShutterExposureTime (V2515) - candidates past the declared 16 s.
- Motion / handshake (P86 «Движение», N-frame exposure cap, steadiest frames):
  com.qti.stats_control.ais_safe_exposure_time (X8U 5.6 ms, X7U 40 ms: the HAL's motion-safe shutter), motion_detected,
  ais_motion_type (X8U / X7U); com.oplus.rawhdr.shake.state (X8U / X9U / X7U; X9U reported 1 while handheld);
  vivo.feedback.motionResult / gyroDetect (vivo); xiaomi.thermal.IsOnTripod (17U).
- Per-frame gyro / OIS synced to the sensor (alignment hints, P78 without the gyro-clock problem): vivo.parameter.captureGyroData
  + captureOisData (X100U, X200U, X200 Pro, V2515), vivo.feedback.oisPerframeDatas (MTK vivo), com.oplus.gyro.data (OPPO,
  3 floats), com.mediatek.3afeature.gyrodata, com.xiaomi.ThridPartyGyroState.gyroData (17U, 4 KB); exposure start:
  org.quic.camera.qtimer.startOfExposure (Qualcomm), vivo.feedback.shutterReadoutTimestamp (MTK).
- OIS: com.xiaomi.camera.supportedfeatures.TeleOisSupported = 1, com.xiaomi.EISSensorMeta.isOisManagerSupported = 1 (17U).
- Colour / black: com.oplus.custom.ccm (52 bytes per frame on all three OPPOs; X7U's CC14 tuning is empty -> OppoTunedColor),
  vivo.feedback.ccm (X100U / X200 Pro / V2515), vivo.feedback.blackLevel (MTK vivo; the X200 Pro padding issue).
- MediaTek RAW control: com.mediatek.control.capture.packedRaw.enable / raw.bpp (request keys, X200 Pro / V2515).
- Already used: com.vivo.pro_isoMin/Max, iso_exp_priority.*, com.qti.chi.statsaec.AecLux, Xiaomi optical zoom keys,
  vivo AEC frame control / rawHDR keys, EnableInsensorZoom / ExtendedMaxZoom, mediatek remosaicenable.
- Done 2026-10-11 (owner: «делай все 4 пункта» = groups 1-4 above; not on a device):
  1. ExposureRanges: OPPO com.oplus.pro.extension.iso.range joins the vivo pro range (ISO ruler); the shutter ceiling also
     rises to the vendor exposure range (iso_exp_priority.exposure_time_range, xiaomi.sensor.info.exposureTimeRange) on a stop
     (max 32 s). LongExposureKeys: a series frame longer than SENSOR_INFO_MAX_FRAME_DURATION sets disableFPSLimits = 1,
     overrideSensorFrameDuration = its exposure and SENSOR_FRAME_DURATION (others back to 0), only where the template has the
     keys (scam_dev long_exposure_keys 0 = off); the vivo X200U (33 s frame limit) is unaffected. MediaTek vivo
     maxExposureTime / iso keys not used: their meaning is unknown.
  2. HalMotion: motion flags (qti motion_detected, oplus rawhdr.shake.state, vivo motionResult) + ais_safe_exposure_time;
     while the HAL reports motion a Shasta frame's shutter is capped at that safe exposure (never below N; not for the manual
     long shutter); the plan line says "HAL motion: shutter cap". scam_dev hal_motion 0 = off.
  3. Steadiest-frame selection (cameras without OIS) falls back to the HAL's per-frame shake (|com.oplus.gyro.data|,
     |vivo.feedback.gyroDetect|) when the phone gyro has no samples. vivo captureGyroData / captureOisData / gyroData not
     parsed (undocumented layouts).
  4. Parameters: an identity colorCorrection.transform (X7U) is replaced by com.oplus.custom.ccm (OplusCcm: 4-byte header +
     9 LE floats, rows sum to 1; scam_dev oplus_ccm 0 = off). The X7U's OppoTunedColor path still wins where it applies.
     vivo.feedback.ccm equals the standard transform (nothing new); vivo.feedback.blackLevel is not in live results.
  Tests VendorHintsTest, OplusCcmTest (681/681).

### P99 — SCAM TEST report of the Galaxy S24 Ultra (SM-S928B, e3q, Snapdragon; taken with the package spoof com.ss.android.ugc.aweme; owner, 2026-10-11; read)

- Cameras: listed 0 (logical main 6.3 mm), 1 / 3 fronts, 2 UW; with the spoof also 5 (main), 6 (7.9 mm 3x), 7 (18.6 mm 5x),
  logical groups 20 / 21 / 23 / 24, physical 52 / 54 / 56 / 58. Main ISO 12-3200, exposure only up to 117 ms (logical) /
  255 ms (physical 5, maxFrameDuration 255 ms); UW / fronts up to 150 ms. OIS on main and teles. Priority modes [0].
- Sensor modes (owner's question: a key like vivo.control.forceSensorMode?): no request key that forces a mode index.
  Closest: samsung.android.sensor.pixelMode (byte, 0 in every template; android.sensor.pixelMode also a request key) and a
  hidden size table (static 0x81200021 / 0x81200027, names not readable without the registry): camera 20 = 16320x12240
  (200 MP) and 8160x6120 (50 MP) as RAW10 (format 37) next to 4080x3060 RAW_SENSOR; the max-resolution arrays of 5 / 7 /
  23 / 24 / 54 / 56 equal 4080x3060 with binning 2x2 (in-sensor 2x crop) but no ULTRA_HIGH_RESOLUTION capability.
  Qualcomm session keys EnableInsensorZoom / ExtendedMaxZoom are request keys; com.qti.sensorbps.mode_index reports the active
  mode (2, 6, 8, 15, 16, 34 seen). All untested: switching needs the owner's approval and a phone.
- RAW hold 62 frames: 108-193 capture failures (P91 holds). Ring depth search (SCAM TEST 1.11): 8 reader buffers -> 15 fps,
  12 -> 28 fps, 16+ -> 30 fps, 57 fails. Done: the RAW reader floor is 16 (was 8; the motion mode's ring of 8 gave 11).
- Other per-frame tags: samsung.android.sensor.wdrExposureTime / wdrSensitivity (2 values), drcRatio, captureTotalGain;
  samsung.android.control.multiExposureTimeList / multiSensorSensitivityList (5 entries, request templates, 0) - a possible
  in-request bracket, untested; samsung.android.listener.gyro.gyroTimestamp.

### P100 — In-sensor zoom (ISZ) on the Galaxy S24 Ultra through CROPPED_RAW, as a «2×» module (owner, 2026-10-11; done, not on a device)

- S24U report: SCALER_AVAILABLE_STREAM_USE_CASES lists 6 (CROPPED_RAW, API 34) on the main camera 0 / 20 / 23 and the 5x
  tele 24; results carry android.scaler.rawCropRegion (1x: (32,0)-(4048,3060)); the max-resolution arrays say binning 2x2.
  CROPPED_RAW is the Android in-sensor-zoom RAW: at the HAL's ISZ ratio the RAW stream (same 4080x3060) holds the sensor's
  centre read at full resolution (remosaicked), rawCropRegion shows the part held. The 200 / 50 MP RAW10 sizes stay
  unusable (not in the public stream map; the framework refuses them).
- CroppedRaw: the RAW_SENSOR output gets setStreamUseCase(CROPPED_RAW) where listed (scam_dev cropped_raw 0 = off; a refused
  session is configured again without it, per camera); preview results log "raw crop ... xF (in-sensor zoom)" on change;
  the hybrid ring keeps only frames of the base frame's crop; ZoomController.rawCropOfShot divides the photo's own crop by
  the RAW's crop (no second crop; the viewfinder zoom unchanged). Also "sensor mode index A -> B" from
  com.qti.sensorbps.mode_index.
- IszModule: once per main camera with CROPPED_RAW, a duplicate module «2×» (zoom 2, same camera, not a sensor-crop module so
  the viewfinder sends zoom ratio 2 to the HAL); deleting it keeps it deleted (module_isz_offered_<camera>). scam_dev
  isz_module 0 = off.
- Check on the S24U: «2×» button present; log "RAW stream with CROPPED_RAW", "raw crop ... x2 (in-sensor zoom)" with the RAW
  still 4080x3060, the photo 4080x3060 with 2x detail (not upscaled), no double crop.

### P101 — SCAM TEST reports 3: OnePlus 15, Find X9 Pro, Find X8 Ultra (root), Xiaomi 15 Ultra, vivo X500 Pro Max (owner, 2026-10-11; read)

- OnePlus 15 (CPH2747, SM8850, root): 5 cameras listed, AE priority [0 1 2] native, exposure up to 54-74 s; OIS OFF only in the
  characteristics but ON in the main / tele templates (0, 2, 4) -> added to halHiddenOis (done, 75ebecb). OPPO pro ISO range
  com.oplus.pro.extension.iso.range 50-3200 on main / tele (read by ExposureRanges, P98). SCAM TEST reported a RAW
  ImageReader limit of 7 (probe process aborted at 8): not real for the app (P58 shots ran with a reader of ~23) - probe
  artifact after 4 earlier probe crashes.
- Find X9 Pro (PLG110, MT6993, Android 17): ISO 100-19200, 32 s on every camera, no RAW hold failures; OIS OFF in the
  characteristics and in the templates (not added to halHiddenOis; forced OIS requests ON anyway, P95). MediaTek
  insensorzoomPlatformSupport = 1, request keys remosaicenable / seamless.remosaicenable, session key
  insensorzoomIsInternalAP, com.oplus.custom.support.raw.size (request + session), com.oplus.custom.support.raw.200m.size,
  com.oplus.capture.turboraw.zslpsl.num - candidates for an ISZ module on the X9 Pro, untested.
- Find X8 Ultra (root): pro ISO range main 50-12800, UW / teles 50-3200; vendor exposure = declared (30 s); OIS ON in the
  templates of 0 / 2 / 4 / 5 (already in halHiddenOis); ring depth 57-58.
- Xiaomi 15 Ultra (25010PN30G, xuanyuan, root): listed 0 / 1, hidden 2-7; exposure declared 1 s; OIS ON in the main / tele
  templates. Opening the hidden tele camera 4 (11.5 mm) alone with YUV + RAW gave 0 preview frames and the camera provider
  restarted (all devices removed, 12:51:56): its tele probably needs the logical camera as on the 17 Ultra (P41b). Open: route
  the 15U tele through its logical camera, or keep camera 4 out of the module list there.
- vivo X500 Pro Max (V2602A, MT6995, Android 17): ISO 50/67-19200, 32 s, OIS on main / teles, no RAW hold failures;
  ultra-wide 4 previews at 13.5 fps. Nothing to fix from the report.
- S24 Ultra: same report as P99 / P100.
- Generic: no OPPO / OnePlus / vivo / Xiaomi phone in these reports lists CROPPED_RAW (only the S24 Ultra).

### P102 — SCAM TEST report of the Motorola Edge 50 Pro (eqe, SM7550, Android 15, root; owner, 2026-10-11; read)

- Cameras: listed 0 (logical main, physical 2 / 3 / 5) and 1 (front); hidden 2 main 5.9 mm (OV50E, 4096x3072 binned,
  com.lenovo.moto.envinfo.snapshot_crop_region 8192x6144 = full 50 MP), 3 UW 2.13 mm (4208x3120), 4 logical, 5 tele 7.06 mm.
  ISO main 100-25500 (UW / tele 1600), exposure only up to 0.25 s everywhere (Qualcomm priority range repeats it).
  OIS listed and ON in the templates of 0 / 2 / 4 / 5 (no hidden OIS). No CROPPED_RAW. Preview 3.5-12 fps in the test is the
  dim scene (AE 33-100 ms), not a HAL limit. RAW hold: stalls and 61-263 failures (P91 holds).
- Vendor (com.lenovo.moto.*): control.exp_pri (long, 0 in requests: an exposure-priority / long-shutter value?),
  control.super_zoom (request key: the stock super zoom / ISZ?), control.scene_mode, envinfo.analog_gain / iso100_gain /
  lux_idx / awb_cct / lens_pos per frame, tf.ipe_ccm_value (ISP CCM) and ipe_gamma_value (256-point ISP gamma),
  uwdc.process_in_app (UW distortion correction left to the app), SATFallback.T2W / W2UW. Candidates for a Motorola profile:
  super_zoom as an ISZ module and exp_pri past 0.25 s - both need a test on the phone (values unknown).

### P103 — Hidden features in all SCAM TEST reports (owner, 2026-10-11: «найди все скрытые функции и фишки»; read, one fix)

Newest report per phone (15 phones). Already known items (P93-P102) are not repeated.
- Fix (done, not on a device): the Xiaomi 17 Ultra also lists CROPPED_RAW (logical 0 / 5 / 6, main 2, tele 4; P101 said
  "only the S24 Ultra"), so P100 turns on there by itself (and IszModule offers «2×» on the main). The tele keeps its P41 /
  P41b crop from the HAL zoom report: CROPPED_RAW is not set on a P41b logical session or while XiaomiTeleZoom is active
  (two crop sources would divide the photo's crop twice).
- Standard Camera2, unused by the app:
  - LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY (AE mode 6, luminance range 0.5-10 lux): X200 Ultra (all cameras), Xiaomi 17 Ultra.
    Brighter viewfinder in the dark; affects the ring frames' exposure too - test before use.
  - Settings override ZOOM (lower zoom latency): Xiaomi 17U logical 5 / 6, X100 Ultra 2 / 5, X200 Ultra 0 / 2 / 9.
  - Camera2 extensions (JPEG / YUV, no RAW): Night on every phone; 17U all five (AUTO, FACE_RETOUCH, BOKEH, HDR, NIGHT),
    15U AUTO / BOKEH / NIGHT, S24U FACE_RETOUCH / BOKEH / NIGHT, OPPO BOKEH / NIGHT.
  - Extended scene mode BOKEH (still / continuous): X7U, 13U 6, 17U 5, Moto, X100U, X200U, vivo MediaTek.
  - AE priority (Android 16) native also on the 17 Ultra (ParamController already uses it where listed).
- Sensor HDR / DR (all switch the sensor mode: need the owner's approval and a phone):
  - Qualcomm session key org.codeaurora.qcamera3.sessionParameters.EnableHDRDCGMode, static
    org.codeaurora.qcamera3.supportedHDRmodes.HDRDCGModes: 17U HAL 0 [658949 3588 3076], 1 [3588], 2 [3588 658949],
    3 [3076], 4 [3588]; OnePlus 15 [657924] on two cameras; X8U [2563] on one; per-frame com.qti.stats_control.DCGMode (0 in
    every result seen). Encoding of the mode values unknown.
  - Xiaomi 17U: session key com.xiaomi.sessionparams.enableLofic, per-frame com.xiaomi.stats.internal.perFrame.loficAERatioInfo
    (float ratio 1.0 + 0 now), supportVideoLofic.
  - vivo Qualcomm (X100U, X200U) and MediaTek vivo: session key vivo.control.EnableDCGHDR; vivo.control.SportStagger /
    MultiSportStagger (com.vivo.SupportSportStagger = 1: «1X sport capture with dual stream»; ScamPreview sends 0 / [1,3]).
  - X500 Pro Max (MT6995) scenario tables (/odm/etc/camera/scenario_table_*.xml): main IMX06H has DCG sensor-merged 14-bit
    Bayer (modes 18, 19 LOFIC, 20 crop 2x) in the video scenario, DCG AP-merge 2-exposure (14, 26), LBMF 2-exposure (15, 17)
    = CUSTOM_CAMERA_SCENARIO_MOTION_DEBLUR; third-party apps get CUSTOM_CAMERA_SCENARIO_3RD_APP = binned mode 0 only.
    Session keys com.mediatek.seamlessfeature.cameraScenario / sensorScenario pick them (values unknown).
- Motion (P86 candidates): MediaTek AI shutter com.mediatek.3afeature.aishutterMode (session; modes {0, 1} on X9 Pro,
  X500 Pro Max, V2515), com.oplus.aishutter.enable (X9 Pro); com.oplus.fastmotion.mode.enable (X7U, session);
  vivo.control.is_motion_autofocus / motionCaptureMode (vivo session keys); xiaomi.ai.misd.motionCaptureEnabled (13U).
- RAW / resolution: org.codeaurora.qcamera3.sessionParameters.EnableIdealRAW on every Qualcomm phone (com.qti.chi.rawcbinfo.
  IdealRaw on 13U / 15U); EnableSnapshotOnlyInsensorZoom (Moto); com.xiaomi.control.qcfa.isSuperRemosaic (15U, 17U);
  com.oplus.snapshot.rawformat (X9U); vivo.control.ultra_highresolution / advance_fullsize / ai_highresolution / is_ProRaw_on;
  enableQLL + org.quic.camera.swcapabilities.isQLLSupported (Qualcomm low-light engine, YUV path).
- Nothing above is wired except the 17U guard; each needs a phone test (and the sensor-mode ones the owner's approval).
- Done 2026-10-11 (owner: «1-3 делай»; not on a device): HiddenFeatures - «Движение» sends com.mediatek.3afeature.aishutterMode = 1
  (byte), com.oplus.fastmotion.mode.enable = 1 (byte), vivo.control.is_motion_autofocus = 1 (int32) where the camera lists them
  as session keys (scam_dev motion_hal_keys 0 = off); scam_dev "ideal_raw 1" sends EnableIdealRAW = 1 (byte); the viewfinder
  sends CONTROL_SETTINGS_OVERRIDE ZOOM where listed (scam_dev zoom_override 0 = off); setting «Яркий видоискатель в темноте»
  (pref_low_light_boost, global, off) puts the viewfinder on AE mode 6 where listed (not in «Движение», video or manual).
  Session parameters are only built when one of these keys is listed. Test HiddenFeaturesTest.

### P104 — «DCG 14 бит» switch for the vivo X500 Pro Max (owner, 2026-10-11; done, not on a device)

- Report facts (SCAM-TEST_vivo-V2602A_20261011-184617, PD2602): main IMX09C, telephotos S5KHPE; the 14-bit DCG tables quoted
  first (imx06h*) belong to PD2608, not this phone. imx09cpd2602: CUSTOM_CAMERA_SCENARIO_3RD_APP = FAEC mode 14;
  CUSTOM_CAMERA_SCENARIO_3RD_APP_HDR = DCG_SENSOR_MERGE_14BIT_BAYER mode 33 («dcg 1:16») + FAEC; MOTION_DEBLUR = LBMF 2-exp
  40 / 41 + crop 46; video default and 60 fps = DCG 14-bit 18 / 19 (LOFIC) / 20 (crop 2x). S5KHPE 3RD_APP_HDR = DCG AP merge
  2-exp mode 25. /vendor/etc/camera/sensormode-selector/PD2602.json chooses scenarios: DCG / Sport / LBMF / LN2 results only
  under AppType vivo (package based; no spoof); third-party apps get PHOTO (CameraScenario 532) / VIDEO (552); the selector's
  CameraScenario numbers are not the XML order (unknown enum). Results carry com.mediatek.seamlessfeature.sensorScenario as
  (camera, scenario) pairs, {2, 1} = binned on the main. Main / tele list availableVhdrModes {8, 128, 0}, HDR photo {0, 3, 1}.
- Done: Dcg14Mode (V2602* / PD2602 only, cameras listing VHDR 128) sends on the session and every request
  com.mediatek.hdrfeature.hdrMode + SessionParamhdrMode = dcg14_hdr_mode (default 3), vivo.control.EnableDCGHDR = dcg14_dcghdr
  (1), vivo.control.session.seamlesscontrol = dcg14_seamless (1), optional cameraScenario = dcg14_camera_scenario and
  sensorScenario = [camera, dcg14_sensor_scenario] (default -1 = not sent); scam_dev dcg14 0 hides it. Log "DCG14 camera …"
  on change of sensorScenario / vhdrResult / hdrDetectionResult / dynamic white and black; "DCG14 session keys …".
- Button (owner: «справа в нижнем углу напротив кнопки ручника, квадратной и меньше»): 40dp square in the manual strip's new
  end slot (ExpandingManualPanel.endSlot, the toggle is 52 x 58dp), shown while the strip is collapsed, only on cameras with
  the mode; tap = toggle + snackbar + camera restart (Dcg14Button).
- Wide RAW: RawPayloadCheck takes a plain RAW whose samples pass 1.5x the declared white (2+ samples, no zero tail, all within
  12 / 14 bits) as 4095 / 16383 white instead of refusing it (log "wide RAW: …"); Parameters.applyWideRaw raises the white
  and scales the black when the darkest samples sit >= 4x the declared black; the ZSL clip estimate uses that white.
  scam_dev raw_wide 0 = off. Tests in RawPayloadCheckTest.
- Check on the phone: tap «DCG» on the main; log "DCG14 session keys" (no "n/a"), "DCG14 camera 2 …: sensorScenario [2, x]"
  with x != 1, "wide RAW: … -> white 16383"; a shot in high contrast vs. off. If the scenario stays 1: try dcg14_hdr_mode 1,
  then dcg14_camera_scenario / dcg14_sensor_scenario values.

### P105 — SCAM TEST report of the Xiaomi Mi 10T Pro (M2007J3SG, apollo, SM8250, Lineage-based Android 15 on MIUI 14 vendor, root; owner, 2026-10-11; read)

- Main = Samsung HMX 108 MP Tetracell (camera 0, 6.72 mm f/1.69, OIS): RAW_SENSOR / RAW10 only 6016x4512 (2x2 binned, plain
  Bayer GRBG, black 64 / white 1023, values checked), JPEG / YUV / HEIC up to 6016x4512 (HIGH_RES burst, 12 fps); no
  max-resolution maps, no ULTRA_HIGH_RESOLUTION, no STREAM_USE_CASE (no CROPPED_RAW).
- 108 MP exists only in vendor statics: org.codeaurora.qcamera3.quadra_cfa.is_qcfa_sensor = 1, qcfa_dimension 12032x9024,
  quadra_cfa.availableStreamConfigurations / xiaomi.scaler.availableStreamConfigurations / availableSuperResolutionStream-
  Configurations list 12032x9024 (and 12032x6768, 9248x6944) for formats 33 JPEG, 34 PRIVATE, 35 YUV only - no RAW at
  108 MP even there; org.quic.camera2.sensormode.info.SensorModeTable: 12032x9024 @ 10 fps (full, remosaic in
  com.qti.node.remosaic / libremosaiclib), 6016x4512 @ 30 (binned), no crop (in-sensor zoom) mode. The AOSP framework of this
  ROM only configures sizes of the public map, so a third-party app cannot get a 12032x9024 stream; the stock way (MIUI
  framework + package checks) is out (no spoof). Front: QCFA 5184x3880, RAW 2592x1940.
- ISZ: no session keys (only com.xiaomi.sessionparams.cameraxConnection / clientName / operation, xiaomi.pro.video.movie.enabled);
  com.qti.insensor_control.seamless_insensor_state / com.qti.izoom_control.state_izoom_snapshot exist in the registry only (not
  in statics, templates or results). Untested idea (sensor-mode switch, owner's approval): xiaomi.quadcfa.enabled /
  xiaomi.remosaic.enabled (byte) as module keys with the 6016x4512 RAW - the HAL might run the 10 fps full mode and crop the
  centre (a 2x Quad-pattern RAW), or refuse.
- Opening the hidden logical camera 4 (main + UW) for streams killed the camera provider (configure Broken pipe, provider
  SIGSEGV, all devices removed 22:19:41) - as the 15 Ultra's camera 4 (P101): keep hidden logical cameras out of the modules.
- Ring depth 24 RAW frames (stopped by free memory: 54 MB per frame), exposure up to 32 s on the main, ISO 50-1600.

### P106 — SCAM TEST report of the Redmi Note 11 Pro (2201116TG, viva, MT6781 Helio G96, Mali-G57 MC2, HyperOS 1 / Android 13, no root; owner, 2026-10-11; read)

- Main HM2 108 MP (6.04 mm f/1.9, no OIS, CFA GBRG, black 64 / white 1023): RAW only 4000x3000 (3x3 binned, plain 16-bit,
  30 fps); com.xiaomi.qcfa.supported = 1, xiaomi.sensorSize.size 4000x3000; com.xiaomi.scaler.availableStreamConfigurations
  tops at 4000x3000 too: no full-resolution stream anywhere. ISO 50-6400 (max analog 640), exposure up to 30 s; logical camera
  5 (role 61) says ISO 100-15500. Hidden 2 = UW 1.65 mm 3264x2448 (15 s), 3 / 4 = 2 MP 1600x1200 fixed-focus (roles 25 / 22:
  macro and depth), front 2328x1752 (ISO 8000, 0.4 s). No priority AE, no extensions, EIS ON only.
- RAW hold test: the HAL stalled with 1 RAW frame held by the app and then closed the device (onError 4); afterwards every
  camera was «disabled by policy» for the test app, so the ring depth search and the hidden cameras are untested. P91 (copy
  on arrival) is required here; never hold camera Images on this phone.
- Unused per-frame HAL data: com.mediatek.3afeature.aishutExistMotion / aishutExposuretime / aishutISO (MediaTek AI shutter:
  motion flag and its suggested exposure; also on X9 Pro, X500 Pro Max, V2515, X200 Pro) -> HalMotion candidate (this phone has
  no OIS); com.xiaomi.statsconfigs.AecLux (lux) and com.mediatek.3afeature.aeLuxIndex; aeSensorGain / aeIspGain (x1024),
  com.xiaomi.sensorbps.gain.
- Request keys worth a phone test: com.mediatek.control.capture.inSensorZoom.mode (MediaTek ISZ; values unknown, no
  characteristics), remosaicenable / seamless.remosaicenable, processRaw.enable, raw.bpp, packedRaw.enable,
  xiaomi.remosaic.enabled, xiaomi.supernight.raw.enabled; MTK AE com.mediatek.3afeature.aeIsoSpeed / aePlineIndex.
- HW HEIF encoder c2.mtk.heif.encoder up to 16368 (8-bit).

### P100b — S24 Ultra: no «2×» module (owner's log 2026-05-18, build 30749; fixed, not on a device)

- The module of the main camera is "0-5" (session on logical 0, RAW from physical 5): IszModule asked
  getCameraCharacteristics("0-5") -> IllegalArgumentException on every start, so the module was never created. Now the physical
  part of a "logical-physical" id is used.
- CROPPED_RAW was set on cameras 5 and 7, but the raw crop was read from the logical result (always the 1x trim (32,0)-(4048,3060)),
  also at 2.63x before the switch to the tele: the physical camera's own result is read first now (CroppedRaw.of(result,
  physical, logical)), in the log line and the hybrid ring. The 5x tele 7 reports Rect(0,0-0,0): treated as no crop.
- Check: «2×» button on the main; at 2x the log "raw crop … xF (in-sensor zoom)" with F ≈ 2.

### P100c — S24 Ultra: stock zoom steps on the lens strip, ISZ logging (owner, 2026-10-12; done, not on a device)

- Owner's log 21:43 (build with P100b): the «2×» module back4 is created on "0-5"; passing it by pinch at 2.62x for ~1 s kept
  the raw crop x1.0 and sensor mode 34 (main camera 5, not the tele: the 3x tele 6 took over at 2.78x). The screencast
  (Screen_Recording_20260518_211733) shows the strip «0,6× 1× 2,7× 5,1×».
- Labels / zoom: the automatic ratio is the field of view against the main (metadata 35 mm eq.: 13.6 / 22.3 / 61.0 / 112.7 mm =
  0.61 / 1 / 2.73 / 5.05). ZoomLabels: on Samsung the label and the module zoom snap to the stock steps 0.6 / 1 / 2 / 3 / 5 / 10
  (within 12 %); the measured field of view is stored per camera (module_fov_<id>) and is the native ratio there, so at 3x the
  tele is cropped 3 / 2.73 = 1.1 and the frame does not widen at the switch from the main. Existing automatic labels equal to
  the measured ratio are renamed on the next start. Other brands unchanged. Test ZoomLabelsTest.
- Log: "raw crop … [physical 5 | logical 0 result, zoomRatio Z]" on a crop change or a 0.25 zoom step; the sensor mode index
  is read from the physical camera's result first.
- Check: strip «0,6× 1× 2× 3× 5×»; on «2×» held 3-5 s: raw crop / sensor mode lines.

### P107 — X500 Pro Max: DCG 14-bit and DCG + LOFIC by the sensor scenario (owner, 2026-10-12: «сделай всё чтобы заставить работать DCG … если возможно, то сделай и LOFIC»; built, not on a device)

- Owner's log 2026-10-12 (Desktop/log-2026-10-12.txt, P104 build): «DCG14 session keys» accepted on cameras 2 / 3 / 6 (hdrMode 3,
  SessionParamhdrMode 3, EnableDCGHDR 1, session.seamlesscontrol 1, no n/a), but every result stayed sensorScenario [2, 1],
  vhdrResult / hdrDetection absent, dynamic white 1023: those keys do not switch the sensor. The «DCG14 camera» line was
  logged 2510 times (the black level drifts every frame): now the black is rounded.
- Report (SCAM-TEST_vivo-V2602A_20261011-184617, root files):
  - sensormode-selector/PD2602.json: a non-vivo app gets CameraScenario 532 «PHOTO» (ratio <= 1.6) / 552 «VIDEO» (>= 1.777)
    = SensorScenario 1 (binned), CustomHint 0 — matches the live [2, 1] / [2, 0]. DCG / Sport / LBMF / LN2 results are under
    AppType vivo only. vivo's video: CameraScenario 501 (VIDEO30), «seamless HDR 1 exposure» (session.seamlesscontrol 1) =
    DCGHDR1 = SensorScenario 1 + seamless switching. SensorModeSelector table: PHOTO 1/0, PHOTOHDR2 9, REMOSAIC 4, ROIREMOSAIC 8,
    SATREMOSAIC 2, 44HWREMOSAIC 13, DCGHDR2 17, MFHDR 258, 8K 259/260, PHOTOCROP hint 15, PHOTOLN2 / VIDEOLN2 hint 19.
  - scenario_table_imx09cpd2602.xml (main IMX09C): video default (501) = BIN 2, DCG_SENSOR_MERGE_14BIT_BAYER 18,
    DCG_SENSOR_MERGE_14BIT_BAYER_LOFIC 19 (= DCG and LOFIC together, one mode), DCG 14-bit crop 2x 20; 3RD_APP_HDR = BIN 32,
    DCG 14-bit 33 («dcg 1:16»), FAEC 14; PHOTO_DEFAULT: LBMF 6, DCG AP-merge 2-exp 7, crops 8 / 11 / 12, full 4-cell 13 (50 MP);
    CUSTOM_CAMERA_SCENARIO_FORCE_CAMERA_CHOOSE: CustomHint 100 + n -> sensor mode n (0..53). The numeric value of that camera
    scenario and the enum of the seamless sensor-scenario types are not in the files.
  - Keys: com.mediatek.seamlessfeature.cameraScenario, sensorScenario, sensorScenarioCustomHint (int[] [camera, value]) are
    session and request keys; sensorScenarioSwitchPolicy request; results carry sensorScenario and the hint. hdrMode is a
    request key only (not a session key). jsons/HW_PD2602_tuning.json has seamlessMode 1286 «QUAD2x2_HDR_ISZ2X_DCG» (tuning).
- Built: DcgSearch (tries, likeliest first: hint 133 / 118; request sweep of sensorScenario 2..40; camera scenario 501 + hints
  and sweep; camera scenarios 500..560 with hint 133; LOFIC: hints 119 / 124 / 129) and Dcg14Mode: one try per session, the next
  try remembered before the session (a try that kills the session is skipped after the restart), request sweep by
  rebuildPreviewBuilder every 10 results, a try ends after 24 results; FOUND = dynamic white / wide RAW above 1.5x 1023 or a
  sensor scenario other than 1 for the camera; the try (and swept value) is kept per mode and used from then on. Button: tap
  off -> DCG -> DCG + LOFIC (own icon) -> off; long press = search on from the next try; snackbars on found / none.
  scam_dev dcg14_camera_scenario / dcg14_sensor_scenario still force values. Test DcgSearchTest.
- Check on the phone: tap «DCG» on the main 1x, wait (the camera restarts by itself, up to ~66 tries), log lines «DCG search …»
  (try N / FOUND / all tries done) and «DCG14 camera 2 …» (sensorScenario, hint, white). Then a high-contrast shot.

### P108 — Pixel 10 Pro / 10 Pro XL: missing modules (ISZ), DCG button, full-resolution button (owner, 2026-10-12; queued)

- Report SCAM-TEST_Google-Pixel_10_Pro_XL_20261012-172642 (mustang, Tensor G5, Android 17, no root): listed 0 (logical back,
  physical 2 / 3 / 4 / 5 / 6 / 9) and 1 (logical front, 7 / 8). Physical: 2 main 6.9 mm 4080x3072 (2x2 of 8160x6144),
  3 UW 2.02 mm 4032x3024, 4 tele 17.906 mm 4032x3024, 5 = main 2x crop (phys 4.896x3.686 mm = half of 2's), 6 = tele 2x crop
  (2.82 mm wide), 9 = UW 2x crop; 7 / 8 front. The app shows only 0.5x / 1x / 4.5x (owner's screenshot): 5 (2x), 6 (~9x) and
  9 (~1x) are the in-sensor-zoom modules of the logical camera 0 — add them as modules ("0-5", "0-6", "0-9"; 9 maybe hidden).
- Full resolution: streamConfigurationMapMaximumResolution RAW10 8160x6144 @ 24 fps on 0 / 2 / 5, 8064x6048 on 6 / 9 (no
  ULTRA_HIGH_RESOLUTION capability listed): SENSOR_PIXEL_MODE MAXIMUM_RESOLUTION (OutputConfiguration.setSensorPixelModeUsed).
  Owner: a button above the DCG button to switch it, camera restart.
- DCG: RAW12 4080x3072 (0x26) on 0 / 2 only (RAW_SENSOR white 4095); vendor registry has com.google.internal.sequoiaIdcgEnabled
  (byte, internal; not in the request / result key lists). Owner: «доступен DCG с raw12» -> a DCG button like the X500 Pro Max
  (manual strip end slot, camera restart): RAW12 stream (MIPI RAW12 unpack needed: RawUnpack / RawPayloadCheck / worker) +
  sequoiaIdcgEnabled 1 as a try; owner mentions «DCG4» on Pixels — not in the report, ask where it comes from.
- Both switches on the Pixel 10 Pro (blazer) as well as the 10 Pro XL (mustang) (owner, 2026-10-12).
- Done 2026-10-12 (not on a device): PixelPro10 — modules: every physical camera of the back logical 0 whose field (focal /
  sensor width against the main) no visible module has becomes a module "0-<id>" once (label / zoom by its field, added to
  user_camera_ids; the UW crop 9 at 1.02x is left out as the main's field); log «back logical 0 physical cameras: …». DCG
  button (same end slot as the X500 Pro Max, main camera only = cameras listing RAW12): on = RAW stream RAW12 (resolveRawFormat)
  + com.google.internal.sequoiaIdcgEnabled 1 on the session and every request, camera restart. Test PixelPro10Test.
  scam_dev pixel_modules 0 / pixel_dcg 0 = off. Why the scan (CameraManager2, P66) did not show 5 / 6 is unknown without a
  SCAMERA log of the phone (it should have): the log line above tells next time.
- Full resolution (first notes; done below): MotionCam Pro 5.0.9 (decompiled NativeCameraManager.a.from) lists the outputs of
  SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION next to the default map (flag false) and needs no ULTRA_HIGH_RESOLUTION
  capability — the same test fits here: a RAW size in the max-resolution map (Pixel 10 Pro XL: RAW10 8160x6144 @ 24 fps on
  0 / 2 / 5, 8064x6048 on 6 / 9). Needs: RAW reader RAW10 at that size with OutputConfiguration.setSensorPixelModeUsed(
  MAXIMUM_RESOLUTION), SENSOR_PIXEL_MODE MAXIMUM_RESOLUTION on the requests that target it; the preview surfaces must then be
  sizes of the max-resolution map too (PRIVATE / YUV only up to 1920x1080 / 1440x1080) or the full-resolution frames come as
  post-shutter stills (no ZSL ring, no RAW viewfinder in that mode). Button above the DCG button, camera restart.
- Done 2026-10-12 (owner: «доделай всё на пиксель», «у пикселя при использовании raw12 работает dcg», «в режиме полного
  разрешения ZSL должен тоже работать», «красивую иконку в стиле нашего интерфейса»; not on a device):
  - DCG = the RAW12 stream only; sequoiaIdcgEnabled only with scam_dev pixel_idcg 1 (and only then session parameters).
  - Full resolution (PixelPro10.fullRes, scam_dev pixel_full_res 0 = off): RAW = the largest RAW of the max-resolution map
    (RAW10 8160x6144 / 8064x6048), every output addSensorPixelModeUsed(MAXIMUM_RESOLUTION), viewfinder and YUV sizes of the
    max-resolution map with the RAW's aspect (1440x1080), every request SENSOR_PIXEL_MODE MAXIMUM_RESOLUTION
    (useMaximumResolutionKey), ZSL ring on (copy on arrival), RAW viewfinder off, readers 8; Parameters takes the
    max-resolution active array for a RAW wider than the binned one (else the photo was cropped to a quarter). A refused
    session (onConfigureFailed / exception) runs binned again with a toast (retryWithoutFullRes). Not while recording.
  - FullResButton: icon manual_ic_fullres (corner brackets + 3x3 pixel grid, stroke 1.7 like the manual icons), floats
    above the DCG button (overlay kept over the end slot), or sits in the slot on a camera without DCG. Tests
    PixelFullResSizeTest (Robolectric), PixelPro10Test.
  - Check on the phone: log «full resolution 8160x6144 format 37», «full resolution: viewfinder 1440x1080», «ZSL ring in
    memfd … MB» (~96 MB slots), shot-to-shot and merge time at 50 MP, or «full resolution refused».

### P108c — Pixel 10 Pro XL first log (owner, 2026-10-12 evening: DCG crash, restart at 1x -> 2x, green full-resolution viewfinder, processing error)
- Modules: the scan found 2 / 3 / 4 / 5 / 6 / 9 (strip 0.5x, 1x, 1x = UW crop 9, 2x, 4.5x, 9x).
- DCG crash (SCAMERA-debug.log 7): ArithmeticException in SaverImplementation.getFrame — a RAW12 post-shutter Image reports
  pixel stride 0 and the width was rowStride / pixelStride (RAW10 was special-cased). Fixed: packed RAW10 / RAW12 (or pixel
  stride 0) take the image's width.
- Full resolution: the session configured, the ring took 95 MB slots (50 MP frames arrive), the merge ran as a native Quad
  mosaic (measured block 2: the max-resolution RAW is unremosaicked Quad-Bayer) and saved. The ISP viewfinder in that mode is
  green (it develops the Quad pattern as Bayer, like the 17U's purple mode-9 preview). Now the RAW viewfinder is on in full
  resolution: LiveRawFrame bins the 2x2 same-colour cells into the plain 4080x3072 Bayer (native unpack + Java bin, at most
  every 150 ms on the camera thread). The YUV reader stayed 1920x1440 (not part of that session's outputs): harmless.
- Processing error (processing error.zip): the merge and the photo completed; SCAM_DIAG's stage dump read the half-float RGB
  (P80) as float32 -> IndexOutOfBounds. Fixed (ScamDiagnostics.buffer half). The photo itself (tele 4, night, ISO 1056,
  Sabre 2x grid) is grainy with bright speckles vs the stock one: scam-denoise left L2 / L3 luma at 0 with ARK gain 39 —
  tuning item, open.
- App restarts 2-14 s after module switches (0-5 -> 0-2, 0-4, 0-6): no Java exception, no crash file. ExitReasons now logs the
  previous process's ApplicationExitInfo (reason, PSS / RSS, tombstone text for a native crash) at the next start. Suspects:
  memory (each camera's memfd ring 66 x 24 MB = 1.6 GB, 95 MB slots in full resolution) or a native fault in the ring code.
- 2026-10-12 (owner: «у пикселя DCG работает в raw12 и raw sensor сразу же на основной камере, так что переключатель можно
  убрать»): the Pixel DCG switch, its RAW12 preference and the sequoiaIdcgEnabled key are removed; the full-resolution switch
  takes the manual strip's end slot on the Pixel.

### P108e - Pixel RAW10: green, crushed, banded shadows (owner's photo IMG_20261012_221728, raw10 setting, main camera)
- Shadows R/G/B = 12/23/5 at luma 8-20, pure black at 0: the main camera declares 12-bit levels (black 256, white 4095) and the
  RAW10 stream carries 10-bit samples (black ~64): the 12-bit black was taken off 10-bit data (crushed, posterized, green as
  the unbalanced G survives). Packed RAW10 / RAW12 samples are now shifted onto the declared white on unpacking
  (RawUnpack.shiftFor / setPackedShift per session; liballocator decodeRaw10Row / decodeRaw12Row / allocateAndCopyConvert,
  Java fallback); log «declared white 4095, packed RAW10 << 2». 10-bit sensors (white 1023) unchanged. Test RawUnpackShiftTest.
- A weak 4-px column ripple (0.01 of an 8-bit level) is far below visibility; re-check stripes on the next RAW10 photo.
- App crash after module switches: ExitReasons says CRASH (Java); the stack is now copied from SCAMERA-crash.log at the next
  start (owner can also send Download/SCAMERA/SCAMERA-crash.log).
- The crash after module switches (SCAMERA-crash.log, 22:24:17 / 22:24:32 / 22:35:50): IllegalStateException «The specified
  child already has a parent» in FullResButton.bind - the button moved between the overlay (above the Pixel DCG button) and
  the slot (a camera without RAW12) while the old parent still held it in its layout transition. Fixed: found in the slot
  first, detached past the transition (endViewTransition), a new button when the old one is still held.

### P107b - X500 Pro Max: result of the DCG / LOFIC search (owner's log 2026-10-13 02:49-03:26)
- All 66 DCG tries and 44 LOFIC tries ran; nothing gave a 14-bit white / wide RAW or a DCG sensor scenario.
- Hint 133 / 118 / 119 (100 + sensor mode) with cameraScenario unset or 501 / most of 500..560: the session configures, no
  preview frame (PREVIEW_STALL), then the vivo camera provider dies («unknown device», «Error configuring streams: Function
  not implemented (-38) / Broken pipe (-32)», onError 4) and comes back after ~10 s. The HAL acts on the hint (it tries the
  mode) but fails with our streams (RAW_SENSOR 4096x3072 + preview + YUV).
- cameraScenario 504, 508, 509, 512, 522, 523, 525, 527, 543, 548 with the hint: frames flow, hint echoed, sensorScenario
  [cam, 0] (none), white 1023: those scenarios ignore it.
- The automatic search is off by default now (scam_dev dcg14_search 1 to run it): it crashed the provider dozens of times.
- Next (needs the owner): the provider's own crash reason (adb logcat -b crash / -b main around one try of hint 133, or the
  vendor tombstone) to see which stream or format the DCG mode refuses; candidate tries: RAW-only session with hint 133,
  MediaTek packedRaw / raw.bpp request keys, the RAW stream at the mode's size.

### P107c - X500 Pro Max dump re-read (owner: «изучи внимательно его дамп»; 2026-10-13)
- Read all 96 configs of the report. Confirmed: every HDR branch of sensormode-selector/PD2602.json (DCGHDR1/2, PHOTOHDR1/2,
  VIDEO60_DCG, MFHDR by vivo.control.shdr_enable, LN2) is under AppType vivo / VIVODEMO; a 3rd_party app gets PHOTO / VIDEO
  by the ratio only. StaticAppNameConfig / AEtuning: app types vivoCamera, engcamera, CTS, Wechat, TikTok, QQ, protake,
  3rd_party (by package; no spoof). camera_config.xml: sensor mode «DCG» only as the Bokeh / BokehTele2L master.
- Missed before: hdrMode 1 (photo HDR; photo modes {0, 3, 1}, only 3 was sent) and vivo.control.shdr_enable. Now the DCG
  switch sends hdrMode 1 (dcg14_hdr_mode) and shdr_enable 1 (dcg14_shdr; int, else byte), no hint, no search.
- Owner: the RAW-format try with the hint (raw10.convertfmt / raw.bpp + hint 133) is skipped.
- P107d (owner, 2026-10-13: «у нас есть спуф в приложении, нужно в нем подменить имя пакета на нужное для работы DCG»): while
  DCG / LOFIC is on (X500 Pro Max), the package spoof (generic + Binder) opens the cameras as com.android.camera (VivoCamera.apk,
  AppType vivo in the selector). scam_dev dcg14_spoof 0 = off; dcg14_uimode N sends vivo.control.currentmodeS (the selector's
  photo HDR is under UIMode 1 / 13 / 70 / 74 / 80 / 81; key and type unverified). Not on a device.

### P109 - X500 Pro Max ISZ (owner, 2026-10-13: «мы сможем заставить работать на нём ISZ?», «нужен и 4x isz ... 2x/4x isz на ширике и телевике», «4x ISZ есть только у телевика», «у ширика должен быть 2x ISZ»; owner allowed the sensor-mode switch by asking for it)
- Scenario tables: main IMX09C CROP_2X_4CELL 12 (no 4x), tele S5KHPE CROP_2X_4CELL 12 + CROP_4X_16CELL 15, ultra-wide JN5 / JN1
  FULL_4CELL only. Selector: AppType vivo + photo UI mode: seamless.roiRemosaic 4 -> ROIREMOSAIC (2x crop, HW remosaic),
  remosaicenable 1 + seamless.remosaic.enable 1 -> SATREMOSAIC.
- Built (VivoMtkIsz, not on a device): duplicate modules main «2×», ultra-wide «1.3×» (2x), tele «7×» (2x) and «14×» (4x),
  each with the module vendor key vivo.control.seamless.roiRemosaic 4 (editable in the module settings); while one is
  active the package spoof opens the camera as com.android.camera. The real field of view is measured (P60 fov_check). The
  DCG14 log line now carries vivo.feedback.SensorCropRegion / isROIRemosaicPreview. scam_dev vivo_isz 0 = off.
- Open: the vivo UI-mode input of the selector (UIMode; key unknown: vivo.control.currentModeEx / currentmodeS candidates);
  the 4x key of the tele (CROP_4X_16CELL) is not in the files.- Owner: «спуф ... на постоянку для этого смартфона»: on the X500 Pro Max every camera opens as com.android.camera
  (Dcg14Mode.spoofPackage; the owner's spoof settings win; scam_dev dcg14_spoof 0 = off).

### P109b - ISZ on the other MediaTek phones (owner, 2026-10-13: «изучи дампы всех смартфонов на MTK и сделай для них так же рабочий ISZ»)
- MediaTek dumps: vivo V2515 (MT6993, PD2502, root), vivo X200 Pro V2405A (MT6991, PD2405), OPPO Find X9 Pro PLG110 (MT6993),
  Redmi Note 11 Pro 2201116TG (MT6781), Xiaomi 13T 2306EPN60G (MT6896). V2562 / V2547A are Snapdragon (SM8850).
- vivo: PD2502 and PD2405 selectors have the X500 Pro Max branch (AppType vivo, UI mode 1/13/70/74 or 7,
  seamless.roiRemosaic 4 -> ROIREMOSAIC). PD2502 tables: main IMX09A CROP_2X_4CELL 12, tele HP9 CROP_2X 12 + CROP_4X_16CELL 15,
  ultra-wide JN1 no crop. Built: VivoMtkIsz for every vivo + MediaTek phone (Build.SOC_MANUFACTURER / HARDWARE mt*): main 2x,
  each tele 2x (one per ratio), 4x on a tele whose sensor is >= 8 mm wide (200 MP class: 9.14 mm on X500 Pro Max / X200 Pro;
  V2515 teles 6.55 mm -> 2x only), ultra-wide 2x; spoof com.android.camera while an ISZ module is active (always on X500 Pro Max).
- OPPO Find X9 Pro: no selector in the dump (no root); logical camera 0 multiCamZoomSteps {0.6, 1.0, 2.0},
  insensorzoomPlatformSupport 1, session key insensorzoomIsInternalAP, oplus alternativecropregion results: its ISZ is the
  HAL's 2x step on the logical camera (as the S24 Ultra). Xiaomi 13T: logical camera 4 zoom steps {0.6, 1.0, 2.0},
  xiaomi.insensorzoom.enabled; Redmi Note 11 Pro: com.mediatek.control.capture.inSensorZoom.mode, no 2.0 step. Not built:
  needs a session on the logical camera with the HAL's zoom ratio 2 (open item).- Owner (2026-10-13): no ISZ work for OPPO / Xiaomi MediaTek; «на виво сделай подмену как на 500 про макс»: every vivo MediaTek
  phone opens its cameras as com.android.camera always (Dcg14Mode.spoofPackage; dcg14_spoof 0 = off).

### P109c/d - vivo MediaTek ISZ test round (owner's logs 2026-10-13_3/4/5, photoss.zip: V2515 PD2502, X200 Pro V2405A)
- The ISZ key alone does nothing: V2515 log_4 «FovSelfCheck: module back5: field 1/1.00 of camera 2» (the 2x module saw the
  main camera's full field; the 1944x2592 photo is the digital crop). The selector's photo UI mode is the session key
  com.vivo.current_ui_module (V2515 session keys; the vivo camera SDK sends it as a session and global request parameter,
  1 = Photo, vivo-camera-app-log 2026-09-20). Now sent (int, else byte) in every session while the vivo spoof is on
  (Dcg14Mode.applyUiModule; scam_dev vivo_ui_module N, -1 = off); a camera that refuses it runs without it (this run).
- «Function not implemented (-38)» at endConfigure + onError 4 under the spoof is transient: the first session after a module
  switch (cameras 2, 3, 4 once each in log_5; the same camera a few seconds later ran). Was: back to the previous module.
  Now: the same camera reopens after 0.5 s / 1 s (twice per switch; the second try without the UI module).
- Module sources: only physical cameras of the current list (log_4: the logical «0-2» became «17×» / «33×»; IszModule threw
  «unknown device» on every start for a slot of the unspoofed list). No free slot (X200 Pro: all 8 back buttons in use): not
  marked offered any more, the module comes once a button is deleted; the log lists the buttons.
- Other session keys of interest on V2515 (not used yet): vivo.control.forceSensorMode, vivo.control.EngineerRemosaicMode,
  vivo.control.advance_fullsize, vivo.control.ultra_highresolution, com.mediatek.seamlessfeature.sensorScenario.
- X200 Pro test (owner's log 2026-10-13_6, APK 7df72e2): ISZ WORKS without the UI module - FovSelfCheck measured field 1/2.00
  on the main 2x (camera 2) and the tele 2x (camera 3), the RAW stays a Bayer stream (block 1) under roiRemosaic 4
  (ROIREMOSAIC: HW-remosaiced 2x crop); the ultra-wide 2x read 1/2.00 too (cached). com.vivo.current_ui_module 1 failed every
  session (-38), so it is off by default now (scam_dev vivo_ui_module 1 to try). The 4x tele module sends the same key (its
  field was not measured: scene changed); the 4x crop key is still unknown.
- FovSelfCheck cache key now holds the package-spoof signature: V2515's «1/1.00 (measured earlier)» was cached before the
  spoof, so the module was cropped digitally; it is measured again.
- X200 Pro video 2026-10-13 (dim room, APK 1606131): the main «2×» ISZ button showed the 1x field (cached 1/2.00, the HAL
  gave the uncropped stream this time; ISZ probably light-dependent in the selector), the other «2×» (camera 2, 1/1.00) the
  real 2x. P109f: FovSelfCheck measures every vendor session again (the cache is only the start value; a different result
  replaces it). The HAL's per-frame vivo.feedback.isROIRemosaicPreview / SensorCropRegion are logged on change
  («ISZ feedback: ...», with ISO) to see whether they can drive the crop directly. Not pushed (owner: «на гит пока не лей»).


### P110 - «Движение»: shutter ceiling (owner, 2026-10-13: «он не отличается в плане захвата движения от обычного»)
- Cause: P86 only fixed the AE range at [30, 30], i.e. at most 1/30 s - what the photo AE already picks indoors and at night
  (X500 Pro Max log 2026-10-13: ring frames 20-30 ms, up to ISO 35000 at night, in both modes), so motion smeared the same.
- Now (manual/MotionShutter, ParamController.motionStep): the camera AE runs until its settled shutter (AE state
  CONVERGED / LOCKED / FLASH_REQUIRED, or 8 still frames) passes the ceiling («Выдержка не длиннее», 1/250 s default;
  1/1000 ... 1/125, Off) by 15 % for 3 frames; then AE off with the same exposure (ISO x shutter) at the shortest shutter
  the top ISO allows (>= ceiling, <= one ring frame; top = rulers' ISO, at most ExposureLimits' honoured AE-off ISO).
- Level loop: the ring RAW's mean (capture/RawLevel, 40x30 grid of 2x2 sites, every viewfinder mode; wide P104 payload at
  its own range) is paired with its own frame's exposure by sensor timestamp, so frames in flight from an older request
  give the same scene estimate (no overshoot, no windup; the product is clamped to what the request can follow). Holds the
  AE's settled level x 2^EV (1/6 stop deadband, 2/3 damping, 2 stops max); a clipped frame may only lower the exposure.
  Back to the AE when the exposure fits ISO min at the ceiling (4 levels) or after 2 s without a level.
- AE written on behind it (3A reset after a shot, the console): its request again at once. A tap (TouchFocus): the AE meters
  the point, the ceiling learns it and takes over again. Nothing during a shot, while ISO / shutter is set by hand, or
  under the X300 Ultra forced stabilisation (P54c: AE-off viewfinder request). RAW viewfinder keeps its auto rendering.
- Mains flicker («Без мерцания ламп», on): STATISTICS_SCENE_FLICKER, or 6 AE shutters in whole 10 / 8.33 ms (decided before
  the take-over; the frame's longest shutter does not vote) -> shutters in whole half periods, at least 1/100 (1/120) s.
- Log: «motion shutter ceiling on: AE ... ns at ISO ... -> ... ns at ISO ...», «motion shutter: mains flicker ...»,
  «motion shutter ceiling: no RAW level ...» / «no settled AE level after 90 frames ...». Not on a device yet.

### P111 - «Движение» like the stock vivo «Моментальный снимок», on every phone (owner, 2026-10-13: «ловила движущиеся объекты на уровне режима "Моментальный снимок" из стока виво», «работал одинаково везде, а не только на виво»)
- Stock study (VivoCamera 13.0.47.154 from the X200 Ultra, jadx + HAL configs; notes in SCAMERA-PC/evidence/vivo_camera_2026-10-13/
  notes_requests.md, notes_processing.md, notes_ours.md): module motion_deblur. The HAL measures motion on the preview
  (vivo.feedback.motionResult, levels 1..5, fed back as vivo.control.motion_level; each level its own AE tuning), the
  capture fires on touch-down with the frame on the screen (vivo.control.displayTimeStamp), every shot a ~5-frame RAW merge
  with best-frame pick, fresh frames when the level changed, a stagger (2-exposure) sensor mode, flicker sensor + AI
  debanding. Vendor keys are not used: the same ideas with public Camera2 and our pipeline.
- Done (generic):
  - capture/MotionMeter: thumbnails of the ring RAW (a sample per 16 px), block matching of consecutive frames (checkerboard
    of tiles, +-64 px) on a low-priority thread: camera motion (median) and subject motion (2nd largest residual), px/s.
  - MotionShutter.Levels: the ceiling drops in half stops so the smear stays within 2 RAW px (at most 3 stops, >= 1/4000 s);
    faster at once, back one half stop per 0.6 s. Log «motion level a -> b: subject .. px/s, camera .. px/s -> ceiling ..».
  - The moment: the frame the viewfinder showed when the finger touched the shutter (MainRenderer.shownTimestamp, else the
    newest result) is the ZSL cutoff (was: the newest result at release, ~0.1 s later). Log «motion: shutter at the touch».
  - ScamHybridBurst: the base is the newest ring frame or one of the two before it (70 ms), not the post-shutter frame.
  - Ring frames with a shutter > 1.12x the moment's frame stay out (AE frames before the ceiling: same brightness, smeared).
  - HybridPlan.buildNormalBack (empty ring after a shot): the viewfinder's ISO and at most the ceiling's shutter (was the
    declared ISO and up to 125 ms: 4 ms at ISO 20000 became 25 ms at ISO 3200 on the X200 Ultra).
  - Bug: hybridTuningText sent the photo boost / motion threshold / Bento to the worker in «Движение»; now the mode's values;
    widen_below 0 in the mode (the rejected moving subject keeps its detail, the denoise takes the noise).
  - Flicker: AE anti-banding shutters only suggest it; the ceiling takes over short and the row profiles of the short frames
    against a band-free AE frame decide (2 frames with > 4 % bands latch 1/100 s, 6 without clear it; 1.5 s no verdict latches).
- Not on a device yet. Check: «motion level», «motion: shutter at the touch», «motion: base = frame», «no lamp bands» /
  «mains flicker» lines; press-to-moment and shutter values in the ring.

### P112 - DCG on the X500 Pro Max: the third-party video route (owner, 2026-10-13: «закончи с запуском DCG на 500 про макс»)
- Where it stands: the vivo camera's keys under the package spoof never gave a 14-bit RAW (log 2026-10-13 13:33: with
  com.vivo.current_ui_module 3 every session -38; without it sensorScenario [2, 1], white 1023); the P107 search (66 DCG / 44
  LOFIC tries of MediaTek's seamless keys) stalled the provider or stayed binned. The selector (PD2602.json) keeps DCG / Sport /
  LBMF to the vivo camera and lists of other apps; any other app gets PHOTO(532) / VIDEO(552) by the streams' aspect ratio
  (>= 1777 -> VIDEO). The main IMX09C's video scenario is the 14-bit DCG mode. Not pursued: how the HAL tells clients apart.
- Done: Dcg14Mode route state per mode. DCG on the main (camera 2), no verdict: the session is watched for 60 results; a 14-bit
  white (dynamic or a wide RAW) keeps the route; else the next route: a plain third-party session (no package spoof) with a
  16:9 viewfinder (CaptureController.getAspect); if that gives none either, or breaks the camera, back to the spoof route,
  verdict kept (button toast «found» / «none»; long press searches again). Logs «DCG route: …».
- Check on the X500 Pro Max: DCG button on the main; «DCG route: … (under test)», «DCG14 camera 2 (DCG): sensorScenario …,
  dynamic white …», «wide RAW: … -> white 16383» and «DCG route: 14-bit RAW on the third-party 16:9 video route, kept».

### P113 - «Движение» as fast as the stock snapshot mode (owner, 2026-10-13: «не могу снять быстро подряд например 5 фото, после каждого фото у нас очень большая задержка»)
- Where the time went (X200 Ultra log 2026-10-13 10:15, Motion mode): press -> submit 20-42 ms -> first post-shutter frame
  ~420 ms (HAL flush + latency) -> series complete 570-670 ms -> shutter free ~0.7-0.8 s; a queued press then waited for 5 frames
  after the tail and 4 same-exposure ring RAWs (~0.2-0.3 s more); a merge in progress blocked the next press when less than
  1.5 GB was free. The stock snapshot mode takes its frames from the buffer, falls back to fewer (one) frames when its queue
  fills, and does not wait.
- Done (every phone): HybridPlan.zslOnly - in «Движение» the shot is the ring's frames only (no Bento / extra N after the
  shutter: no flush, lead, series or AE restore frame), complete at the press; the next press fires as soon as the ring holds
  one frame (5 ms polling; a press faster than a frame waits up to 300 ms for one, then shoots as before); behind 2-3 queued
  merges a shot takes at most 4 ring frames, behind more 2 (MotionMode.framesForQueue); the memory gate is only the system's
  low-memory state in the mode (the frames are views of the ring's memfd). HDR is weaker (no ultrashort highlight frames).
- Check: a fast series of 5; log «hybrid plan: … ring frames only», «shutter free» right after each SHUTTER, «motion: N shots
  wait for their merge, k ring frames», «motion: shutter waits for a ring frame».

### P114 - OPPO Find X8 Ultra: teal shadows until the phone was restarted (owner, 2026-10-13: «словил на Oppo X8 Ultra, прошло после перезапуска телефона»)
- Owner's log 11:51-12:43: every shot «RAW black floor: 10100-12279 of 12288 blocks below 16.0 DN skipped» and «RAW black
  level from data: 64 -> 18-23»; the bracket's measured gains +20..24 % (x2 frames) and -18..22 % (x1/8): the black subtracted
  was too high for the data. After the restart (12:44-12:46): no low samples, gains within 1.5 %, normal colour. The HAL had
  delivered RAW_SENSOR without its black pedestal (reported 64 static and dynamic); the padding rule (a block with a sample
  below black / 4 is padding) left out exactly the dark blocks, so the floor came from mid-tones and the shadows lost red.
- Done: Parameters.refineBlackLevel - when more than half of the blocks hold such samples it is not padding: the floor is
  measured over every block except zero padding (blocks of exact zeros and their neighbours); log «RAW black floor: … not
  padding, the RAW came without its black pedestal». The X200 Pro padding and the OPPO's 64 -> 60 drift stay as they were.
  Tests: ParametersBlackFloorTest (pedestal lost -> black < 5; noisy night with padding keeps ~64).

### P115 - PhotonCamera / HdrX leftovers removed or replaced (owner, 2026-10-13: «Удали и замени все хвосты связанные с оригинальной Photon Camera, HdrX и тд. Замени всё это на наш лучший вариант, либо сделай сам»)
- Owner's answers: a new signing key (CN=SCAMERA; every phone uninstalls the app once), the Java package
  com.particlesdevs.photoncamera stays (other agents' uncommitted work lives in it).
- Renamed: the PhotonCamera application class -> ScameraApp; HdrxProcessor -> ScamProcessor (log lines «SCAM processing failed
  at», «Shot tail ms»); Theme.Photon.* -> Theme.Scamera.*; the app row of «Система» pref_photoncamera -> pref_app_key.
- Removed (Photon-only or dead): the device specifics (pro/*, assets/specific: camera ID lists, noise models, CCM / HSV / Look
  tables of old OnePlus / Xiaomi phones, DCIM/.../Tuning/*.txt), customCCT.txt, PhotonCameraTuning.ini (getTuning had no
  caller), the TCP debug client (debugclient/*), the dual session (isDualSession came only from the specifics), the HDRX switch
  (KEY_HDRX, SettingType.HDRX; SettingsMigration drops the stored pref_hdrx_key), the eszdman accent, the duplicate
  processing/opengl/preview renderer, ABL, ColorCorrectionCube / FillCCT, the camera2native sample JNI functions, the INTERNET
  permission (nothing uses the network), fastlane metadata (Photon's store listing), HDRPLUS_ULTRA.md.
- Replaced: DCIM/PhotonCamera -> DCIM/SCAMERA (Raw/, logs in Log/ instead of PhotonLog/, no Tuning/; the gallery lists
  DCIM/SCAMERA and DCIM/Camera); EXIF copyright, DNG Software, XMP toolkit, gyro CSV -> SCAMERA; the watermark logo is a SCAMERA
  camera glyph drawn in code (RotateWatermark.drawLogo), and nothing is drawn when logo and both lines are off (was the full
  «SHOT ON PHOTON CAMERA» banner); the themed launcher icon scamera_launch_mono (was the Photon ring); the live RAW viewfinder
  renders the ArkCore path of rawdevelop_fs (the libvf_demosaic port: exposure fusion metered by LiveRawMeter, ACES, OKLab
  vibrance, gamma 2.2 + film toe; its uniforms were already set) instead of the Photon gamma / tone curve (PreviewPhotoLook +
  AutoExposureCurve every frame); the retired ISP-viewfinder tone curve (PreviewLook / ToneCurveBuilder, always off) is gone,
  LiveSceneMeter only measures SceneLuma for the manual priorities. CI runs tools/checks/preview_gl.py (photo_look_gl.py gone).
- Signing: key/PcamLeak.jks (CN=PhotonCamera developers, its password in this public repository) is gone. The SCAMERA key lives in
  SCAMERA-PC/signing (outside git): app/build.gradle and tools/package_scam_neural.py read signing/signing.properties or
  SCAMERA_KEYSTORE / SCAMERA_KEYSTORE_PASSWORD / SCAMERA_KEY_ALIAS; CI needs the secrets SCAMERA_SIGNING_KEYSTORE (base64 .jks) and
  SCAMERA_SIGNING_PASSWORD, else the packaging step fails with an error (key/README.md).
- Kept on purpose: the GPL copyright headers (PhotonCamera / Eszdman), «From <fork>/PhotonCamera <hash>» attributions, the
  README licence note, the lists of old stored keys that the migrations remove.
- Checks: a clean copy of the commit: 806 unit tests, failing only the old CaptureControllerTest (809 / + DevKeysCoverageTest
  in the shared tree: another agent's uncommitted vivo_qcom keys); check_ui_language / check_settings_model / check_scam_* PASS; rawdevelop_fs compiles offline (glslc).
  Not tested on a phone: the RAW viewfinder look (ISZ / mosaic modes), the watermark, DCIM/SCAMERA/Log, the themed icon.

### P116 - Gallery compare: zoom and pan together through the photos (owner, 2026-10-13: «В галерее сделай синхронный сдвиг фото с учётом разного зума. И сделай относительный зум не зависящий от разрешения»)
- Before: «Зум и сдвиг вместе» copied the touched pane's SSIV scale and centre in source pixels to the other pane, so a
  12 MP photo next to a 50 MP one showed a different zoom (pill 250 % vs 120 %) and a different place, and panes at a
  different zoom drifted apart while panning.
- Now gallery/compare/PaneLink: photo 2 = a * photo 1 + b in source pixels, pane 2's scale = pane 1's / a. Linked at the
  touch-down (ImageCompareFragment, CustomSSIV.TouchCallBack.onTouchedView) as the panes are shown: new photos or a swap link
  them centred at their fit (a = the size ratio: the same relative zoom whatever the resolution), or by the EXIF 35 mm
  focal lengths when both photos have them and they differ (a 2x photo next to a 1x one: the same scene point stays in the
  middle, the pan of one moves the other through its own zoom); switching «Зум и сдвиг вместе» on again links them as they
  are, so a zoom or offset set while it was off is kept. The touched pane leads; the other follows its scale and centre.
- SCAMERA photos have no FocalLengthIn35mmFilm (ParseExif leaves it out): a 1x / 2x pair of ours is linked as shown; zoom
  one pane with the switch off to match the other, then switch it on.
- Tests: PaneLinkTest 3/3 (12 vs 50 MP, 2x vs 1x, a zoom and offset kept), GalleryUiTest 7/7. Not in the CI test list yet
  (that line of build-test-apk.yml has another agent's uncommitted change). Not tested on a phone.

### P117 - OPEN: SGallery crashes, WebP / AVIF black (owner, 2026-10-13, screen recording VID_20261013_174745 on an OPPO / ColorOS phone)
- Crashes in the compare screen, the app closes to the home screen: the top right button «Поменять местами» (swap();
  owner: «Вылет был при нажати на верхнюю правую кнопку») - twice in the recording, about 0.7 s after compare opened, and
  once after the share sheet was cancelled. A Robolectric probe (ImageCompareFragment in the gallery NavHostFragment,
  swap twice, close) did not crash without photos: it needs loaded pages (SSIV / Glide / Ultra HDR window mode) or that phone. The photos in the recording carry the old «SHOT ON PHOTON CAMERA» banner (P115 replaced it).
- Owner: «Галерея всё ещё вылетает при различных обстоятельствах» - not only compare; «Вылетает и на других смартфонах»:
  the swap crash is not one phone's. And: «Вылетает с ultraHDR или если выбран не JPEG формат» - the photos shown are Ultra
  HDR or HEIC / WebP / AVIF / DNG. Those are exactly the pages ImageAdapter loads asynchronously as Glide cachedBitmaps
  (loadGlideBitmap, decodeHdrBitmap; a plain JPEG is SSIV tiles from the file): first suspect a decode / retry / HDR
  callback reaching a pane that swap() has just destroyed (detachPage clears Glide and recycles the page), and the
  window HDR mode of the old / new pane (UltraHdrGalleryUtil.setWindowHdr in onPause / onResume). The probe above had
  no photos; with a WebP / AVIF / Ultra HDR JPEG in GalleryViewModel it should reproduce it.
- WebP and AVIF photos still show a black square instead of the photo (P65 added the native AVIF decoder, P81 the reload
  of pages decoded while the file was being written: neither fixed it on the owner's phone).
- Next: the crash report of that phone (Download/SCAMERA/SCAMERA-crash.log, the log in DCIM/SCAMERA/Log from P115 on,
  DCIM/PhotonCamera/PhotonLog before) - not guessed; candidates to check against it: ImageCompareFragment.onDestroy removes
  the panes through the parent FragmentManager (they belong to the child one), swap() re-adds panes whose pages share ids,
  the old ScaleAndPan sync (gone in P116). WebP / AVIF: which file, its size and bit depth (10-bit AVIF / HEIC-like
  RGBA_1010102?), Glide vs SSIV path in ImageAdapter (line ~131), thumbnails vs viewer.

### P118 - Settings: fixed, checked, the needless ones gone (owner, 2026-10-13: «Исправь и проверь работоспособность всего, лишнее/ненужное/дублирующее удали полностью», screenshots of the settings; «Это можешь вырезать» on «Хроматическая аберрация RAW»; «Эту группу настроек ты тоже можешь вырезать кроме Сабр 2х когда это имеет смысл, улучши этот параметр ... в зависимости от освещения»; «поменяй описание у абсолютно всех настроек и параметров на минимально возможное»)
- Gone, with their code, strings, tests and stored values (SettingsMigration.removeObsolete):
  - «Хроматическая аберрация RAW» (P28: RawTherapee CA_correct port scam-rawca*.h, tools/rawca, the worker's rawCa keys, the
    memory budget of its frame copies). The merge's own lateral CA fit (P19) stays; it is how CA is removed.
  - «Quad Bayer — совместимость» and the hidden «Quad Bayer» full resolution: the switch never changed the stream (pref_quad_bayer_key
    was forced off since P25, its CaptureController branches unreachable); it only sent vendor remosaic tags with the shot, marked the
    CFA -2 (every SCAM HDR shot then fell back to the hybrid on vivo) and wrote a 4x4 QBCFA into DNGs of plain Bayer frames.
    Quad / Tetra streams come from the ISZ modules; the hybrid measures their colour block itself.
  - «Показать градиентный фон»: its theme set the camera background to the colour it already had.
  - «Опыты JSR» (phase kernel, CA inside the merge, polynomial field). The JSR_LCA stub in kHybMergeCommon stays untouched
    (never defined now; the merge GLSL is not edited, see the Adreno 750 note).
  - ArkCore «Приоритет лица» and «Компромисс защиты светов»: read by nothing (faces never reach processing; the trade-off is a
    fixed 0.33 in ArkAe).
- «Sabre 2× только когда есть смысл» (was «Опыты JSR» → jsrAuto2x, off): its own row under «Разрешение», on by default, live on
  the 2x grid only. The worker (auto2x, off in replays) keeps the 2x grid only when the burst samples finer than the sensor (green
  fill distance <= 0.5 px, as before) AND the light allows detail at 2x: SNR of a 2x cell = base noise model at 0.1 of white x
  sqrt(frames / 4) >= 20; else 1x merge + 2x resize. Log: «HYBRID SABRE 2X: ... -> 2x grid kept / 1x grid, resized (...)».
  Thresholds not tuned on phone shots yet (auto2xMaxGap, auto2xMinSnr in the tuning file).
- «Включить закруглённые края» did nothing: the viewfinder frame (P25) masked 26dp corners always, over the switch's 40 px ones.
  The frame follows the switch now (off = square corners); the old corners are gone.
- «Показать данные отладки»: level 3 is «Рамки AF / AE и данные кадра» (was «Полная отладка», confused with the processing log
  «Полный журнал обработки»); the summary said «данные автофокуса» only. The row was not disabled: the screenshot was taken under
  the open list's scrim.
- «Частота мерцания» → «Мерцание ламп», a list Авто / Сеть 50 Гц / Сеть 60 Гц / Выкл. (default Авто; the stored old default 120,
  wrong in 50 Hz countries, becomes Авто once). Auto: STATISTICS_SCENE_FLICKER of the preview, else six AE shutters on whole
  periods of one mains at two multiples, else the region (capture/MainsFlicker). Only the long Shasta frames of the hybrid snap.
- «16:9»: one crop for every frame (processing/Wide169): the frames after the press kept an odd row count (4080 wide: 2295 rows)
  and were dropped or failed the merge; rows and first row on multiples of 8 (whole Quad / Tetra blocks); no second crop in
  PostPipeline / ScamRgb (the output was a row taller than its data); the lens shading map is cut to the crop's rows (the top and
  bottom of 16:9 photos got the sensor edges' brightening). DNG (all aspect ratios): the gain map area is the stored image as
  (xmin, ymin, xmax, ymax); the active array's (top, left, bottom, right) went into those arguments, so it spanned rows up to the
  image width and columns up to its height.
- Kept on purpose: «Фильтр Байера» (works; only for a HAL with a wrong CFA, per module with per-lens settings), «RAW-метаданные»
  (three working rows: dynamic black / white level, no front mirror).
- Every settings description rewritten short (RU / EN, about 470 rows plus the per-module and «RAW-метаданные» rows): what the
  setting is and what a higher / lower value (or on) does; the meaning checked in the code. Rows that had none got one; summaries
  that were the title string got their own. Icons: the sounds and the rounded corners got their own.
- Found while rewriting (not changed, worth a look): «Карта силы» Auto = «Карта эффективных кадров»; Bento frames 1-2 do nothing
  while Mochi is «Всегда» (3 dark frames always); the Sabre 6.1 «Авто» rows (max key, min motion) and the old kernel's SNR scale /
  tensor noise do nothing while 6.1 is «Всегда» (default); Shasta sharpness / max ratio skipped with Shasta «Всегда» (default);
  motion boost rows need F6; the colour fill support needs the fill on; the AgX curve sliders act in «Свой» look only;
  pref_color_method_key is overridden on vivo (ISP CCM) and OPPO (tuned CCM).
- Tests: 823 unit tests, only DevKeysCoverageTest fails (another agent's uncommitted vivo_qcom keys); new MainsFlickerTest,
  Wide169Test, FileNamePatternTest, HybridSettingsTest.rawCaIsGone / auto2xOnByDefaultAndJsrGone, QuadBayerResetTest,
  SettingsAuditFixTest.antibandingBecomesAList. CaptureControllerTest passes again (its QuadBayer branch read null settings).
  check_ui_language, check_settings_model PASS; the worker and dngCreator.cpp compile (NDK clang -fsyntax-only).
  Not tested on a phone.

### P119 - «Название фото»: SCA instead of IMG, a template like ArkCam's (owner, 2026-10-13: «у фото подписать вместо IMG сделать SCA», «Дай возможность настраивать как это сделано в ArcCam»)
- Output settings → «Название фото»: the template (date / time variables yyyy yy MM dd HH mm ss SSS, any other text kept as it
  is), the name it gives now, tap-to-insert variables, ready templates, «Сбросить» / «Отмена» / «Сохранить» (ui/settings/
  FileNameDialog, processing/FileNamePattern). Default SCA_yyyyMMdd_HHmmss; characters a file name cannot hold are dropped.
- One name per shot for the DNG and the photo (ImageSaver / DefaultSaver called the name generator per file: with milliseconds
  they would differ). The gallery's edit copy uses the template too. Videos keep VID_.

### P120 - OPEN: SCAM TEST reports of the nubia NX563J and the vivo V2547DA (X300 Ultra, root) (owner, 2026-10-13: «изучить очень внимательно и точно отчёты чтобы найти что-то что мы можем исправить/улучшить использовать, либо расширить функционал/возможности для данных смартфонов. X300 Ultra тут с рут доступом»)
- Reports (outside git): SCAMERA-PC/reports/scam-test/SCAM-TEST_nubia-NX563J_20261013-062017.txt (11.6 k lines),
  SCAM-TEST_vivo-V2547DA_20261013-083557.txt (199 k lines, root sections).
- To do: cameras, RAW sizes / CFA / levels, sensor modes and vendor tags (ISZ, DCG, remosaic), AE / OIS / EIS facts, what SCAMERA
  does on these phones today and what it could use (root on the X300 Ultra: vendor libraries, tuning, the worker paths).

### P121 - ISZ without remosaic: white balance as without ISZ (owner, 2026-10-13: «при использовании ISZ без ремозаика баланс белого не работает нормально на всех смартфонах, придумай как это исправить ... сделать его аналогичным тому как он работает без ISZ ... Если да, то реализуй»)
- Code check: the merge keeps the colour of a Quad / Tetra mosaic (same CFA order, per-site gains within a colour, per-colour
  black / white); every route takes the white point from the frame's SENSOR_NEUTRAL_COLOR_POINT and nothing else. So the neutral
  the camera reports for an un-remosaiced mosaic stream is the suspect: its ISP reads the mosaic as Bayer (the purple / green ISP
  pictures of P41b / P108), every 2x2 phase then holds about the same mix of R, G and B, its AWB sees a grey scene. Not confirmed
  on a phone (no ISZ shot log in the repo).
- Now (processing/color/MosaicNeutral, ScamProcessor.applyMosaicNeutral): a shot whose stream has a colour block > 1 measures the
  neutral of its reference frame (each colour block averaged, shades of grey p = 4 over cells neither clipped nor dark) and puts it
  on the camera's own illuminant curve: the neutral points the same physical camera reported in plain-Bayer viewfinder sessions with
  the AWB settled (ParamController.learnNeutral, every 16th result, kept per camera in SharedPreferences «mosaic_awb_curve»).
  Without a curve yet the measured neutral is used. The ISP colour transform (vivo) is skipped for such a neutral (picked at the
  camera's wrong one). DNG AsShotNeutral, ArkTone and the neural route follow. Log: «mosaic white balance: colour block b, camera
  r / b -> measured ..., on the curve of camera N ...». scam_dev.txt "mosaic_awb 0": the camera's neutral as before.
- Tests: MosaicNeutralTest (Quad / Tetra BGGR mosaics with ±4 % site gains: R/G and B/G within 3 %; clipped / tiny frames give
  nothing; the projection on the curve). To check on phones: one scene with the ISZ module and its plain module, daylight and
  tungsten; the log lines above.

### P117 (continued, 2026-10-13 evening) - fixed in code, not checked on a phone
- WebP / AVIF (and HEIC, DNG) black in the viewer and the compare panes: ImageAdapter.instantiateItem started the page's decode
  before the page was in the pager; the Glide path (every non-JPEG page) returns at once for a page without a parent, silently and
  for good (since P59, 33dd003; P65 / P81 could not help). The page is added first now, and an early return is logged.
  AvifGlideDecoder: the platform decode fits the request (4096 px): a power-of-two sample left a 50 MP AVIF at full size (~200 MB,
  more than a view may draw).
- Crash on the swap button with Ultra HDR / non-JPEG photos (code reading; no crash log): memory. Compare loaded a full-resolution
  Ultra HDR bitmap per pane (~200 MB each at 50 MP) and every non-JPEG page as one bitmap up to 4096 px (~50 MB) with 3 pages kept
  on each side of each pane, and a swap built two new panes before the old were gone. Now: no Ultra HDR rendition in compare, 1
  page kept on each side in compare (2 in the viewer), the gallery bitmaps software (the compare screenshot drew hardware ones into
  a software canvas), the screenshot drawn on the main thread (it raced the panes' tiles on a background thread), and the two
  removals of panes through a FragmentManager they do not belong to are gone.
- Tests: AvifGlideDecoderTest.platformDecodeFitsTheRequest; gallery tests pass. To check on a phone: compare with Ultra HDR / HEIC /
  WebP / AVIF photos, swap many times, share; WebP / AVIF / HEIC / DNG pages show.

### P118 (continued)
- Sharpening: only ARK stays (owner, 2026-10-13: «Оставляем только резкость Ark, всё остальное вырезаем»). Gone: the mode list,
  RawTherapee (USM, RL deconvolution, microcontrast; RTSharpening and its shaders, every pref_sharp_* row), SCAM «без ореолов»
  (ScamSharpen, scamsharp shaders), «Выключено», «Доп. резкость после тона», the shadow guard of the post-tone sharpening
  (ArkSharpenGuard, ark/guard.glsl; it acted only on that sharpening), the RawTherapee strength, «Деталь Sabre» (acted only
  without the ARK sharpening). ArkLumaSharpen always runs; its own rows stay. Defaults give the same picture as before.
- Rows that act only with another setting off its default are greyed with the reason: Sabre 6.1 «Авто» rows, the round-4 kernel
  rows (snr scale, tensor noise), Shasta sharpness / max ratio (Shasta «Авто»), Bento trigger (Bento «Авто», also Motion mode's),
  motion boost (needs F6), the AgX curve sliders (look «Custom»). «Карта силы»: «Карта эффективных кадров» removed (it did
  exactly what «Авто» does; stored ones become «Авто»).
- Tests 825, only DevKeysCoverageTest fails (another agent's uncommitted vivo_qcom keys). tools/check_ark_tone.py: the two
  synthetic cases fail with the same numbers on the previous commit 478913e too (not from this round).

### P120 (continued) - findings of the two reports; fixed what needs no phone and no sensor-mode change
- Full notes (outside git): SCAMERA-PC/reports/scam-test/ + the study files x300u.md / nubia.md copied there.
- Fixed:
  - vivo X300 Ultra PD2547 / V2547DA (the owner's rooted one): the forced stabilisation (P54b/c: no HAL flush on the shot) matched
    only V2562; now this variant too.
  - Its camera 7 (CameraType «MultiSpectrum», gc59a0_ms) passed every check and became a «lens»; opening it took every camera
    device down (kernel CSIPHY combo-mode error). vivo «MultiSpectrum» / «PreISP» profiles and cameras without
    BACKWARD_COMPATIBLE (depth-only camera 8) are auxiliary now; the camera scan reruns once (aux4).
  - ImageReader with 63 images aborts the process natively on both phones (a session takes 59): every reader is capped at 59, the
    image ring without memfd at 56 (the ZSL slider goes to 100).
- Open (needs the owner / a phone):
  - nubia NX563J: the HAL's noise profile is implausible (offset ~1000x too high, the same on all 3 cameras) -> ghosts, heavy shadow
    denoise, Sabre 2x never kept; a sanity check against the measured noise (P71) or the burst fit. RAW_SENSOR 10 of 24 fps on the
    main camera, RAW10 listed at full rate: one RAW10 test, then DeviceDefaults raw10 for NX563J. Camera 2 is shown as 0.8x but is
    most likely the 2x tele (0.70 m min focus, impossible view angles in its config): one pair of shots of cameras 0 and 2.
    RAW reader usage flag 0x00100000 is set on nubia (unverified).
  - X300 Ultra: tele ISZ 2x / 4x and DCG 14-bit (main, UW) exist but switch sensor modes (owner OK needed; the third-party DCG
    route key vcf.parameter.seamlessMode is not in this phone's session keys); 50 / 200 MP RAW; teleconverter tuning present; the
    tele's real ISO range (declared 50-800, own AE 12800) to check in a log; SCAM TEST again with cameras 6 and 7 skipped (8-11
    were lost when the service went down).

### P122 - vivo X300 Ultra sensor modes; module vendor keys on / off (owner, 2026-10-13: «Я даю тебе разрешение переключать сенсормоды», «На виво на снапах сенсормоды можно переключать с помощью vivo.control.forceSensorMode реквеста», a table of the X300U's working modes, «посмотри как сделаны реквесты в моушн камере», «дай возможность включать и выключать реквесты если у меня вписано несколько штук и обнови для этой группы настроек интерфейс»)
- The table (community, X300U): forceSensorMode works as a SESSION key only. 14 mm: 21 = 2x ISZ Quad (5/10/11/23 14-bit, 8/14/19
  60-120 fps); 35 mm: 10 = 2x ISZ Quad (15/17/19/21 14-bit, 18/20/22 fast); 85 mm: 5 = 2x ISZ Quad, 6 = 4x ISZ remosaiced, 7 = 4x ISZ
  Quad-Quad (Tetra), 31 = 2x ISZ remosaiced, 20 = Open Gate 30 fps 14-bit (DCG; «14-bit modes on the telephoto are DCG and they work
  fine»; on 14 / 35 mm the 14-bit modes are not). camera_config.xml PreviewRoiQuadSensorMode 5 (PD2547) / 31 (V2562) agrees.
- Session-only vendor keys (VendorTagUtils.TunableKey.TYPE_SESSION «Session»): set in the session parameters only, without a
  physical copy, never in a request (as MotionCam: VendorRequestTagValue.isSessionKey -> the session parameters builder);
  TunableKeyManager.applySessionKeys in CaptureController's session block; the key dialog has «Только при открытии сессии».
- capture/VivoQcomIsz (X300 Ultra of any region: PD2547 / V2547* / V2562 or the market name «X300 Ultra»; PD2454 stays
  VivoQcomModes'): ISZ modules without remosaic (owner: «Isz используй без ремозаика»), once each, by the camera's vivo
  CameraType (Master / Wide / Tele3P5x): «2×» main (10), «1.2×» UW (21), «7.4×» tele 2x (5), «15×» tele 4x Tetra (7). DCG 14 bit
  by the DCG button of the manual strip as on the X500 Pro Max (owner: «включение DCG по кнопке как у 500 про макс», «ISZ и DCG
  доступен у 300 ультра на всех трех модулях»): Open Gate main 17, UW 11, tele 20 as a session key while the button is on and
  the module forces no mode of its own; the camera restarts on the tap. Hybrid merges the mosaics natively (block measured;
  5 / 7 declared), P121 white balance, raw_wide takes the 14-bit range. scam_dev "vivo_qcom_roi 0": none of it. Not tested on
  the phone.
- Module vendor keys: each has an on / off switch on its card («Сессия» / «Запрос», value, taken / unknown / not tried; off cards
  dimmed); off keys are not sent and a switched-off forceSensorMode is no sensor mode (ModuleRegistry.forcedMode).
- Tests 830 (only DevKeysCoverageTest: another agent's keys); VivoQcomIszTest, ModuleKeysSwitchTest.

### P123 - vivo MediaTek ISZ and DCG removed (owner, 2026-10-13: «На виво на мтк не работает ISZ и DCG, поэтому мы можем их убрать на всех смартфонах где пытались добавить»)
- Gone: capture/VivoMtkIsz (P109: roiRemosaic ISZ modules on every vivo MediaTek phone, its ROI-key bookkeeping and feedback log),
  capture/DcgSearch and the X500 Pro Max route of Dcg14Mode (P104-P112: MediaTek HDR / seamless / sensorScenario keys, the
  DCG / LOFIC search, LOFIC, the 16:9 video route, the vivo UI module), the automatic vivo-camera package spoof of vivo MediaTek
  phones (Dcg14Mode.spoofPackage; the owner's own spoof settings stay), the -38 busy-HAL reopen and the UI-module retry in
  CaptureController, eleven scam_dev keys (dcg14_*, vivo_isz, vivo_ui_module), the LOFIC icon and texts.
- Kept: the DCG button and Dcg14Mode as its on / off switch, now for the X300 Ultra (P122; VivoQcomIsz.applyDcg on the session
  only: the per-request Dcg14Mode.apply call is gone, forceSensorMode is a session key).
- Once on a vivo MediaTek phone (IszModule.removeVivoMtk): the ISZ duplicates P109 made (marked module_vivo_isz_<slot>) are
  removed with all module_vivo_isz_* flags.
- Tests: IszModuleTest; VivoQcomIszTest takes the on / off step; VivoMtkIszTest, DcgSearchTest and the X500 Pro Max device test
  removed.

### P125 - ISO 21496-1 gain map in HEIC / AVIF (owner, 2026-10-14: formats like AVIF with good compatibility, «Делаем только пункт 1»)
- «HDR в HEIC / AVIF» now writes the SDR photo with the Ultra HDR JPEG's gain map (ultrahdr/IsoGainMap: GainMapMin/Max, gamma 1,
  offsets 1/64, headroom 0 / HDRCapacityMax, one channel, base colour space; the map at half size per axis) instead of the HLG
  picture: every viewer shows the photo, HDR screens the highlights (Android 15+ / Chrome AVIF / iOS 18 HEIC).
- AVIF: libavif's own gain map (8-bit monochrome image, 'tmap' item, altr, brand), any depth (8-bit too), Android 12.
- HEIC: the app's HEIF writer (HeifContainerWriter.setGainMap): a second hidden grid of Main10 tiles coded by the same encoder
  (Heic10Encoder.writeWithGainMap), a 'tmap' item (ToneMapImage v0 + GainMapMetadata, the bytes libavif writes) deriving from
  the primary grid and the map, grpl/altr {tmap, grid}, ftyp 'tmap'; primary stays the SDR grid. Needs «HEIC 10 бит».
- A failed map encode writes the SDR file. HlgRendition stays (tools/check_colour_output checks it) but the app no longer uses it.
- Checks: IsoGainMapTest, HeifGainMapTest, PhotoOutputColourTest (gain map paths and fallbacks); tools/check_avif.py: libavif
  reads the map back (size, metadata exact, map within 0 codes, SDR photo in Pillow) - run locally with AOM_TARGET_CPU=generic;
  tools/check_heic10.py --self-test: the HEIC with a map passes the structure check and libheif decodes the primary unchanged
  (CI only: pillow-heif). Not checked on a phone: whether the gallery / Google Photos show the HDR.

### P126 - X300 Ultra: ISZ modules doubled and coming back; dark DCG photos (owner, 2026-10-14, video + log)
- Modules: IszModule (CROPPED_RAW «2×») skips the X300 Ultra (VivoQcomIsz makes its main 2x); an ISZ offer is spent at the first
  try (a module that had no free slot came back as soon as another button was deleted); an existing duplicate with the same
  camera and forced mode is not made again; extras removed (a second ISZ module of the same camera + mode always, the plain
  twin at an ISZ module's zoom once). Log: 8 back buttons in use, «no free module slot» at every start, two «4.8×» / «9.6×».
- DCG levels: the DCG modes stream 14 bits with the HAL's white 1023 and static black 64, the dynamic black 1021.75 / 1030.75;
  the measured range flapped 4095 / 16383 / 1023 with the scene (dark first photo, others over / under): RawPayloadCheck.
  pedestalWhite - a dynamic black 2^k x the static one is a k bits wider stream (white (1023 + 1) * 2^k - 1); Parameters takes
  that black and white for the shot, the clip estimate and the motion level read it through wideWhite(). Not phone-checked.

### P135 - low light: very strong grain made stronger by sharpening, on every phone (owner, 2026-10-15: «часто при плохом освещении ловлю вот такие отвалы обработки с непонятным очень сильным зерном и выделяющим его нереально сильным шарпингом на абсолютно разных телефонах. Исправь чтобы такого никогда не было. Добавил парную с гкама для сравнения.»)
- Done locally (owner, then: «Мне не нужно устранять зерно полностью, мне нужно устранить аномальное его количество»). Inputs:
  SCAM-20261015-024212-860 diagnostics, SCA_20261015_024212.heic + log-2026-10-15, the ArkCam pair ARK_20261015_024247781.jpg.
  The shot: «Движение» with a hand-set 0.8 s, ISO 12800 asked, 6400 delivered, 4 N + 3 ES frames, finish SNR 3.04.
  Causes: (1) the denoise's night tier (SNR <= 5, ArkCam's SabreLow) was {1, 3, 0, 0, 0} with revert {9, 9, ..}: the fine levels
  put the removed noise back amplified (out = d + rf (recon - d), rf' = 9/2 at 4 effective frames), the coarse ones were not
  denoised at all - every shot below SNR 10 got some of it (interpolated with the SNR-10 tier); (2) the ARK sharpening (USM,
  bilateral boost, local contrast, two Richardson-Lucy stages x3) ran at full strength whatever the noise; (3) the automatic
  ISO of a hand-set long shutter ignored the ISO the HAL honours for AE-off frames (P110).
  Fixes: night tier = GCam's stock SabreLow strengths (all levels, the coarse blotches and colour mottle cleaned) with 60 / 50 %
  of the fine grain put back (revert 0.6 / 0.5 < the SNR-10 tier's 0.75 / 0.7: a darker shot is never grainier), revert limit
  dn_revert_max 9 -> 1 (never more grain than the shot had), migration of the stored former defaults (LOW_LIGHT_DN_REV);
  ArkLumaSharpen.lowLightFactor: share of every sharpening amount by the finish SNR (0.3 at <= 4, 0.66 at 10, 0.84 at 16, all
  from 24; daylight shots unchanged); ParamController.pslIso capped by honouredIsoCap. Tests: ScamDenoiseTablesTest (now in the
  CI list), ArkSharpenLowLightTest, HybridSettingsTest.lowLightDenoiseDefaultsMoveOnce. Not phone-checked.

### P136 - settings icons in the style of the new launcher icon (owner, 2026-10-15, on the P131b preview: «Переделай стиль иконок настроек под подобный стиль»)
- OPEN, after the current P129 step. Every settings icon (res/drawable/settings_ic_*, the module page glyphs of ModuleConceptFragment,
  the shade tiles if they share them) redrawn like the P131b launcher icon: filled light glyphs with a soft vertical gradient,
  dark inner parts, coral (accent) details, on the existing dark cards; generated from one script like
  tools/make_launcher_icons.py so they stay consistent; the accent detail follows the chosen accent where the icon is tinted.
- Also the shade (шторка) and the viewfinder icons (owner, 2026-10-15: «В шторке и видоискателе тоже сделай иконки в таком
  стиле»): the shade tiles' glyphs and the viewfinder's top bar / side buttons in the same filled style.
- Done locally (2026-10-15). tools/make_ui_icons.py makes every UI icon from one geometry per glyph (24-unit viewport,
  primitives written as polygons so the winding is exact): the body in the launcher icon's light vertical gradient, the
  «dark inner parts» as cut-outs (the card shows through), the details in ?android:attr/colorAccent (the activity's accent
  theme, so they follow «Цветовой акцент»), the grey back card, and a 40 % «dim» body for things that are off. 83 drawables:
  the 39 settings_ic_* (+ settings_ic_motion / _focus / _plus, so every row icon of the settings is a generated glyph), the
  shade's own (flash, torch, timers, grids, metering, focus, downsampler, pins, plus, slash, the format glyphs and the RAW +
  format pairs with the film dimmed behind), the top bar's (motion on / off - the AVDs restyled to match, the SHDR badge «S»,
  the legacy SCAM HDR badge «N») and the manual controls' (circularbarlib manual_ic_ev / focus / iso / shutter / sliders /
  wb). The settings rows draw them as they are (SettingsStyle / ModuleConceptFragment no longer tint; the reset rows keep
  the warning colour); the shade, the top bar and the manual panel tint them with SRC_IN as before - the glyphs are built
  to read as silhouettes there (cut-outs, not dark paint). The «Настройки» shortcut (P140) takes shortcut_ic_gear, the same
  gear with the coral fixed (a launcher applies no app theme), and its mono twin for themed icons. Checks: make_ui_icons.py
  --check in CI (the drawables are what the script makes), IconStyleTest (rows untinted but reset, every XML row icon a
  generated glyph, all load in the accent theme and in the application context; contact sheets in build/reports/icons).
  Not seen on a phone; ChatGPT's manual_ic_dcg14 / manual_ic_fullres were left as they are.

### P137 - value editors in the camera's style, «add to the shade» from them (owner, 2026-10-15, screenshots of the «N-кадров из ZSL» / «Bento: отказ при движении» editors: «все вот такие меню переделай под стиль интерфейса нашей камеры, так же добавь возможность прямо из этого подменю добавлять настройки в шторку»)
- OPEN, after P136. The number / list editors of the settings (the ruler dialog with the step chips, «По умолчанию», Отмена /
  Применить: ui/controls/PrecisionEditor, PrecisionRuler and their dialog host) redrawn as the camera's own sheets (the shade /
  manual panel look: dark sheet, rounded top, the accent ruler, pill buttons), and a «В шторку» / «Add to shade» action that
  pins the setting as a shade tile (ShadeTiles, max 12; «Убрать из шторки» when it is pinned; hidden for NOT_PINNABLE keys).
- Done locally (2026-10-15, before P136). PrecisionEditor.show is a bottom sheet like the list / text pickers
  (SettingsStyle.sheetBody / sheet, now public): the value large in the middle (tap: typed in place in a field, «2,5» accepted,
  out of range marked and not saved), the range, the ruler with the settings accent (PrecisionRuler.setAccent), the step as
  chips, «По умолчанию: x», «Применить» as the accent button; no stock AlertDialog left in the slider path. Every value editor
  (slider sheet, list sheet, text sheet) has «В шторку» / «В шторке» on its title line when ShadeCatalog knows the key:
  ShadeTiles.toggle pins the setting at the end of the shade or takes it out (null when the shade holds 12, a toast says so);
  the shade reloads its tiles on its next refresh (camera resume). Test SettingsMenuTest.valueEditorsAreSheetsAndPinToTheShade.
  Not seen on a phone yet.

### P138 - sliders without drawing bugs (owner, 2026-10-15, screenshot of «Shasta: EV над N» = 1.00: «Исправить все ползунки чтобы нигде не было таких багов»)
- OPEN (quick, with the settings work). A value at the end of its range draws the thumb cut in half at the card's edge (the
  SeekBar's padding / thumb offset in the settings row layout); every UniversalSeekBarPreference / tunable / shade slider row
  checked at min, max and mid values (thumb whole, track and value pill agree, the value matches the stored one).
- Done locally (2026-10-15). Cause: preference_seekbar.xml pulls the SeekBar 14 dp left / 10 dp right out of the text column
  (so the track lines up with the text); at the minimum the thumb (centre 16 dp in, 9 dp radius) reached 7 dp past the column,
  which the card clipped (clipChildren). The column and the card no longer clip (clipChildren / clipToPadding false): the
  thumb stays whole at both ends and the track keeps its alignment. Every slider row of the settings uses this layout
  (UniversalSeekBarPreference, the tunables, the SHDR rows); the shade's sliders (ShadeRows, SettingsBarLayout) keep the
  SeekBar's own padding inside their card and were not affected. Test SettingsMenuTest.sliderThumbIsNeverClipped walks the
  thumb's rectangle up to the card at the minimum and maximum (fails on the old layout).

### P139 - row titles that stand out from their descriptions (owner, 2026-10-15: «Сделать так чтобы у всех параметров/настроек заголовки отличались от описания чтобы они не сливались вместе»)
- OPEN (quick, with the settings work). Every settings row (XML rows through SettingsStyle.bind, the Java pages of
  ModuleConceptFragment, the shade's rows): title medium weight, a little larger and in the full text colour; the description
  smaller, regular and in the muted colour, with a small gap between them, so the two never read as one paragraph.
- Done locally (2026-10-15). UiTokens.title (sans-serif-medium) / UiTokens.SUMMARY (#959EAD, a step dimmer than MUTED) and
  @color/settings_summary: the XML rows (preference_card / _seekbar / _info / _tile), the Java pages (ModuleConceptFragment
  navigation rows, the module-profile switch, the search results) and the shade (ShadeRows, the catalog, «Все настройки»,
  «Ещё», the slider card) take the medium title and the dimmer description. Test SettingsMenuTest.
  titlesStandOutFromTheirDescriptions (switch, list, slider and page rows). Not seen on a phone yet.

### P140 - «Настройки» shortcut on the launcher icon (owner, 2026-10-15, screenshot of GCam's long-press menu: «добавить шортат для перехода в настройки»; «Учитывай что мы будем обновлять иконки по плану»)
- Done locally. res/xml/shortcuts.xml (static shortcut «settings»: «Настройки» / «Открыть настройки», en Settings / Open
  settings) on SplashActivity, the camera launcher icon (the gallery's own icon has none); the intent is
  SettingsActivity.ACTION_SHORTCUT. From the shortcut SettingsActivity first does what CameraActivity.onCreate would
  (setDefaultValues, PreferenceKeys.setDefaults, the settings cache), so a change made there still lands in the active
  module's profile; PreferenceKeys.PROFILE_LISTENER makes that listener one per process (every camera start used to add one
  more). Icon: adaptive, the launcher icon's dark background + the settings gear of tools/make_ui_icons.py (shortcut_ic_gear,
  coral fixed since a launcher applies no app theme; P136 regenerates it with the rest). Test SettingsMenuTest.launcherShortcutOpensTheSettings. Not checked on a phone.

### P127 - RAW black / white levels by bit depth (owner, 2026-10-15: «Переделай алгоритм определения уровня белого и черного, не всегда 8 бит: 16/255 10 бит: 64/1023 12 бит: 256/4095 14 бит: 1024/16383 16 бит: 4096/65535. Нам нужен надёжный и точный алгоритм который будет определять правильно уровень, при этом не замедлять съёмку и тд. На гит не надо лить»)
- Done locally (not pushed, owner). processing/RawLevels: one resolver for every consumer (shot Parameters, RAW viewfinder,
  motion level, clip estimate, mosaic check, RawBin) instead of the separate P104 measured range, P126 pedestal and the
  viewfinder's 25 % rule. Depth (even 8..16) = max of: the declared white; the dynamic black against the static one (ratio 4^n
  within 0.25 octave = 2n bits; 0.75..1.25 = drift; anything else ignored) and the dynamic white; the samples of RawPayloadCheck
  (second largest value beyond the declared range, no zero tail, up to 14 bits; a frame lit to 12 bits whose 0.5 % floor sits
  on static black x 16 is 14 bits). Metadata never narrows (Realme 16-for-64 is rescaled x4); a wider metadata reading is
  refused when the sampled floor is under half its pedestal. Sampled depth needs two frames and only grows within a camera
  session (RawPayloadCheck.setWideEnabled = new session), so no flapping. Black: the dynamic one in the stream's units (X300U
  1030.75), else static x 2^n (static kept when the samples' floor shows the pedestal did not move); white: dynamic in the
  stream's units, else (declared + 1) x 2^n - 1. Shot frame check in Parameters.refineBlackLevel (same pass as the black floor):
  its own samples beyond the resolved white widen the levels. No extra frame reads. Tests: RawLevelsTest (new),
  RawPayloadCheckTest, ParametersBlackFloorTest. Not phone-checked.

### P128 - settings audit: duplicates, broken and useless settings (owner, 2026-10-15: «пройдись по всем настройкам и удали/исправь все дубликаты и если найдешь какие-либо не рабочие настройки, то исправь их, либо, если они не нужны, удали из приложения»)
- OPEN, after P127 (then P129). Every preference screen and key: duplicates merged (one key, one place), settings whose value no code reads
  fixed or removed, unneeded ones removed (with SettingsMigration cleanup of the stale keys); check_settings_model.py and
  SettingsAuditFixTest stay green.
- Done locally (2026-10-15; part 1 with P135, the rest after P129 step 3a), from the inventory (350 rows, no key without a
  reader): duplicates of SCAM HDR gone with it; the highlight frames and the shadow frames each in one category of one page
  («Кадры светов и теней», the old «… в склейке» page dissolved, LUT σ with the shadow frames); the two highlight-denoise rows
  and the two dark-colour rows side by side on the noise page; every diagnostics row in «Система › Диагностика»; «Замер для
  тона» / «Замер камеры» in the shade (two «Замер» before); local contrast of the sharpening vs the tone's layer filter told
  apart; the noise source says what it does («Из профиля модели шума (при «Авто» — от камеры)», it silently fell back);
  «Кадров в склейке» says the ZSL buffer bounds it; the denoise tables' t-row titles, the chroma points (Low / Med / High),
  «Revert», «GID14» translated / renamed; the SCAM AE limit / bright-mid / high-limit reads with no row and no scam_dev line
  are constants (ScamDenoise.displayGain). Test SettingsMenuTest.oneTopicInOnePlaceAndNoLiteralTitles (also: no literal
  title left in preferences.xml but «SHDR»).
- Not done: the hidden scam_dev keys missing from DevKeys and the DevKeys group still titled «vivo X500 Pro Max: DCG»
  (DevKeys.java has ChatGPT's uncommitted work); the saturation rows (#12) go with P134 «Цвет».
- DONE 2026-10-16 (DevKeys free again): 21 hybrid settings read by literal key with no row in preferences.xml (so only
  reachable through scam_dev.txt "hybrid_<key>") are on «Для разработчика» now: the series planner (fast_capture,
  planner_adaptive, planner_l/s/es_ev, long_boost_ev), the merge (clip_flags, mosaic_gain), the processing (isp_ccm,
  dn_dark_chroma_hi/lo/noise, grain_level/min, texture_g0..g3, texture_core0/1, texture_bmax), with the code's defaults.
  DevKeysCoverageTest now also scans hybridValue / hybridSwitch / hybridString / routeInternalValue / ArkTone.value literal
  keys without a row (it failed with these 21 on the old list). The group is «vivo» (it holds X200 / X300 Ultra keys).
  getScamLuma / getScamChroma / getScamMerge (SCAM HDR network strengths, no caller since P129) removed.

### P129 - one merge only: SCAM HDR removed, the Hybrid becomes SHDR, own names for the code names (owner, 2026-10-15: «SCAM HDR можешь вырезать из приложения, оставляем нейроремозаик. Все настройки что связаны с scamhdr, если они не связаны с гибридом вырезаем. У нас должен остаться только гибрид как единственный тип склейки. Его же мы переименовываем в SHDR. Так же убери из приложения упоминание LMC/AGC. Все кодовые названия по типу Bento, Shasta, mochi, Sabre и другие переделываем в наши собственные названия. Все названия придумай сам, главное чтобы звучало логично и нормально.»)
- OPEN, after P128. Remove the SCAM HDR merge route (NPU 4N + L/S/ES graph, its settings and UI) but keep the neural remosaic
  (Quad / Tetra / HP9 HexQuad models and their assets). Settings of the SCAM HDR route that the Hybrid does not use go
  (SettingsMigration drops the stale keys). The Hybrid stays as the only merge and is called SHDR in the UI and logs.
- No «LMC» / «AGC» left in the app (UI, logs, settings, code names). ARK stays (owner, 2026-10-15: «ARK можешь оставить»).
  Code names Sabre, Bento, Shasta, Mochi and the like get
  our own descriptive names (UI, settings, log tags, class / method names where cheap); keys renamed with a migration so owners'
  settings survive. Vendor names (vivo.*, libvivo*, model files) stay as they are (P55 rule).
- Step 1 done locally (2026-10-15): PreferenceKeys.mergeRoute() is always SHDR (a stored route ignored; pref_merge_route
  stays as the migrations' hidden marker, no row); the route row, its chip, the dimmed route sections, the route tile and the
  SCAM HDR shade group, the SCAM HDR screen (66 rows), its launch check (scam_probe) and the SCAM HDR -> ArkCore link are gone;
  pref_scamhdr_* / pref_scamold_* are obsolete (migrateScamHybrid runs first everywhere). The neural remosaic stays and is
  SHDR's now: «Нейроремозаик (NPU)» (pref_scam_hybrid_neural_remosaic, off by default as SCAM HDR's mosaic mode was; a stored
  SCAM HDR «neural*» mode with the SCAM HDR route turns it on once) on the mosaic page with the HexQuad / Quad 2x2 / GPU remosaic
  / Tetra response rows; ScamMosaic.prepareForShdr before ScamHybridBurst.process (SM8750, ISZ Quad / Tetra stream, unbinned):
  the equal-exposure N frames through the NPU model into one plain-Bayer frame (SHDR's base, ImageFrame.neuralReference), the
  other frames GPU-remosaiced, SHDR then merges block 1; any failure leaves the frames untouched (native mosaic merge). EXIF
  Route=SHDR (the gallery still reads Hybrid / SCAM HDR), top-bar badge «SHDR». Not phone-checked (the NPU path needs the
  X200 Ultra).
- Step 2 done locally (2026-10-15), the visible names: Hybrid -> SHDR, Bento -> «Кадры светов» / "Highlight frames",
  Shasta -> «Кадры теней» / "Shadow frames", Sabre -> «Ядро склейки» / "Merge kernel" (Sabre 6.1 -> «Новое ядро склейки»,
  the round-4 core -> «прежнее ядро», Sabre 2x -> «Суперразрешение 2×», the Sabre Low / Med / High denoise points -> dark / dim
  / bright), Mochi -> «Фотометрическая склейка», the GCam denoiser -> «Многомасштабный шумодав», ArkCore -> ARK, «(AGC)»
  dropped, «Фотонный шум» -> «Дробовой шум»; the literal XML titles (Bento, Shasta, Sabre Luma Denoise, Luma Denoise GID14,
  Revert, Guided filter) became translated strings. 835 unreferenced string / array entries removed (most of them SCAM HDR's
  and the 26790 recovery's). The gallery shows an old Route=Hybrid shot as SHDR (old SCAM HDR shots keep their name). The
  SHDR capture report (it used to sit behind the SCAM HDR launch check) is «Система -> Диагностика -> Отчёт последней
  съёмки SHDR» (ScamActivity, now the report viewer only); the worker's failure message points there.
  SettingsMenuTest.checkNoOldNames now also walks every string and string array of the APK (en and ru).
  Left for later: the Lang.t texts of CaptureController and DevKeys (ChatGPT's uncommitted work there), and the SCAM HDR
  classes' own texts (they go with the code).
- Step 3a done locally (2026-10-15), the SCAM HDR code outside CaptureController: ScamBurst / ScamAe gone (SHDR's merged DNG
  and frame map statics now ScamHybridBurst.lastMergedDng / lastEffectiveFrames); ScamProcessor's SCAM HDR branch and its
  mosaic detection; ScamMosaic is the SHDR neural remosaic only (prepare / Sabre-over-mosaic copies / mfr gone); the
  Multi-frame Remosaic (remosaic package, libmobileRemosaic.so 400 KB, its CI binary check) and Tetra Detail
  (TetraDetailRemosaic, shaders remosaic/tetra, RemosaicCore's backend switch, tools/check_tetra_*.py) - both were SCAM HDR
  mosaic modes; the HighlightRecovery node (a pass-through under SHDR, ScamRgb recovers the highlights) and its prep / apply
  shaders; the stock AE runtime (assets/scam-aec: the Frida observer scripts and the 24 MB frida-inject the CI downloaded,
  tools/fetch_scam_ae_runtime.py, the scamAe JNI library with scam-aec-*.h, scam-camx-ae-plan.h) - useStockBracketPlanner()
  is false; the scamProbe JNI target; SCAM HDR's forward and tone networks (nice-main-forward, 5 nice-tone) out of
  ScamNeuralWorker / the APK, still verified in the private bundle (ci_neural_assets.py BUNDLE_ONLY, 28 entries as before);
  the dead PreferenceKeys mosaic helpers (mosaicBlock() = 1), ImageFrame.mosaic, tools/check_scam_measured_exposure.py;
  the CI APK check now fails if scam-aec / libscamAe / libscamProbe / libmobileRemosaic come back. 847 unit tests green,
  the host checks (legacy isolation, AE context, capture sequence, settings model, UI language) pass.
- Still to do (step 3b, waits for ChatGPT's uncommitted CaptureController work): CaptureController's stock-AE / SCAM HDR
  planner branches, then ScamStockAe (keep a slim Plan for SHDR's N exposure), ScamAeContext, their tests and checks; the
  worker's SCAM HDR paths (MappedScamBurst, scam::reconstruct, --scam-check / --scam-tone-check / --scam-forward, the
  dump-forward marker) with the C++ host checks that drive them (check_scam_capture.cpp, check_scam_motion_capture.cpp,
  check_scam_reference_metadata.py) - needs an NDK worker build and the OPPO replay md5 check.

### P130 - accent colour from the system (owner, 2026-10-15: «сделай так чтобы цветовые акценты брались автоматически из самого смартфона, В пункте меню где можно менять цветовой акцент добавь пункт "Из Системы"»)
- Done locally. AccentPalette.SYSTEM «Из системы» / «From system» is the first choice and the default (an accent never chosen
  = system): Android 12+ android.R.color.system_accent1_200 for the UI and the camera controls, SystemAccentTheme for the
  settings activity (res values-v31/accent_system.xml = system_accent1_200 / _800; values/ = the old lavender); before Android
  12 the old defaults (lavender UI, yellow camera controls). Test AccentSystemTest. Not phone-checked.

### P131 - app icon in the style of our UI (owner, 2026-10-15: «переделать иконку приложения под стиль нашего интерфейса»)
- Done locally. SCAMERA and SGallery launcher icons redrawn in the UI's style: the dark surface gradient (#23272E -> #121417)
  as the background, light outline glyphs (#ECEEF4) with the lavender accent (camera: body + lens + LED; gallery: two photo
  cards, hills, sun), monochrome layers of the same geometry for Android 13 themed icons, the splash raster from the same
  drawing. Vectors only (minSdk 26: the density PNG mipmaps were never used - removed with the old 0.7 MB generated PNG
  foregrounds and the unused gallery_launch_bg / fg). Generator: tools/make_launcher_icons.py [PREVIEW_DIR]. Not phone-checked.
- P131b (owner, 2026-10-15, on the preview: «фиолетовый замени на коралловый, а стиль иконок сделай чуть ближе к старым которые
  были до изменения»): done locally. Coral (#FFA99E, the UI's «Коралловый») instead of the lavender; filled glyphs like the old
  icon: a light camera body (vertical gradient), a dark glass lens (radial gradient, highlight) in a coral ring, coral shutter
  button and LED; the gallery as a grey back card and a light front card with a dark picture window, grey hills, coral sun;
  the dark UI surface kept. Themed (monochrome) layers cut the lens / picture out with evenOdd paths.

### P132 - settings groups reorganised for ordinary users (owner, 2026-10-15: «группу настроек "Камеры и сенсоры" упорядочить и превести в логический и более простой вид. В целом пересмотри все группы настроек и упорядочи/перераспредели всё для того чтобы было проще ориентироваться и обычному не сильно понимающему человеку было проще все найти»)
- OPEN, done in the same pass as P128 (after P127). «Камеры и сенсоры» first: a simple logical order (modules / cameras,
  then per-sensor options, expert keys at the end or under «Для разработчика»). Then every group: settings moved to where a
  non-expert looks for them, short clear group titles, rarely used / expert rows grouped at the bottom. Supersedes the
  «settings follow the LMC layout» rule; preference keys stay (only screens / order / titles change), check_settings_model.py green.
- Core done locally (2026-10-15, with P129 step 1): root = «Режим «Движение»», «Фото» (formats, resolution / downsampler /
  2x from SHDR, DNG compression, file name, watermark), «Съёмка» (new: frames in the merge, ZSL buffer, lamp flicker, sounds,
  Xiaomi zoom, «Движение» page), «Видоискатель», «Цвет» (DCP, colour method, vibrance), «SHDR» (noise reduction, sharpening,
  ARK tone first; frames, merge, noise expert pages after; the «Обработка ArkCore» container dissolved), «Камеры и сенсоры»
  (Java page: rear / front / hidden modules, module profiles, «Для опытных» -> «RAW и сенсор» = the former «Захват и RAW» with
  SHDR's noise source next to the noise-model profile), «Интерфейс» (new: theme, accent, gallery icon), «Система» (root, spoof,
  new «Резервная копия» page, diagnostics with the full log, developer, about). Keys unchanged; tests updated
  (HybridSettingsTest, SettingsAuditFixTest, SettingsMenuTest.rootGroupsForOrdinaryUsers, shade tests), SettingsModelCheck,
  CI aapt checks. 847 unit tests green.

### P133 - processing speed on every SoC (owner, 2026-10-15: «Проверить чтобы на всех процессорах скорость обработки была нормальная, потому что на 8 элит ген 5 на 300 ультра и на оппо х9 ультра обработка занимает больше времени чем на 8 элит на 200 ультра»)
- OPEN, after P131. Snapdragon 8 Elite Gen 5 (vivo X300 Ultra, OPPO Find X9 Ultra) processes slower than 8 Elite (X200 Ultra).
  Find why from ShotTimeline / worker timings in the owners' logs (SoC-gated paths: NPU / QNN only on 8 Elite, GPU paths
  chosen by device or GPU name, thread counts / big-core affinity, Adreno 840 shader or driver fallbacks, frame sizes 14-bit
  DCG / ISZ); make every SoC take the fast path it can. Needs a log of one shot from each phone to compare stage times.
- Analysed 2026-10-15 from the owner's logs on the desktop (X300 Ultra V2562 log-2026-10-14, 9 shots; X200 Ultra V2454A
  log 2026-10-13, 2 shots; the X9 Ultra log-2026-10-10 has no stage timings - an older build). Same route (Hybrid, sensor-size
  output): X300 Ultra 2.5-3.6 s per shot (merge 0.8-1.2 s for 23-26 frames, post 0.43-0.78 s), X200 Ultra 5.4-6.9 s (merge
  1.6-2.7 s for 11-26 frames, post 0.7-1.1 s, JPEG encode + save ~1.4 s) - the 8 Elite Gen 5 is not slower per shot here.
  The slow X300 Ultra shots (5.7 s) are the 2x modules: "hybrid output mode=12 twoX=true", Sabre 2x grid 8160x6128 (4x the
  pixels): local align 331 ms, merge 2.3 s, post 1.7 s (the queued GPU work of the nodes lands in the readback after the
  last node: 870 ms). No SoC-specific path in the code: the merge's Adreno fast path (SHARED_TILES) matches any "Adreno"
  (Adreno 840 included), the program cache hits (11/11), gpuPrio=low on both phones, thread counts by core count only;
  SCAM HDR / neural remosaic need Hexagon v79 (SM8750) - not used by the Hybrid route. Nothing to fix without a log of the
  same scene / module on both phones; the 2x modules cost 2x the time by design.

### P134 - colour settings from Lightcraft and RawTherapee, all in «Цвет» (owner, 2026-10-15: «Изучи https://github.com/storytold/lightcraft https://github.com/RawTherapee/RawTherapee И добавь нам настройки цвета которые логически должны подойти нашему приложению, не дублируй одни и те же параметры, так же можешь сделать свои варианты настроек которых нет в данных примерах. Все настройки собирай в группе "Цвет"»)
- OPEN, after P132 (the settings regrouping makes the «Цвет» group; P129 names apply). Study the colour tools of both
  projects (RawTherapee: white balance, colour toning, channel mixer, HSV equalizer, colour appearance CIECAM, vibrance,
  Lab adjustments, film simulation; Lightcraft: its colour / look controls; ArkCam - the standalone app, not the LMC mod
  (owner, 2026-10-15: «можешь так же изучить и ArkCam(не мод LMC, а отдельное приложение)»; that app is research/ark50,
  com.arkcam v1.0.50 - research/hybrid5 describes ArkCam 1.23 / LMC 9.6, the wrong one) and our existing colour rows (WB, saturation, hue
  look, ARK tone, DCP / ISP CCM, output colour space). Add the ones that fit a phone camera's processed photo, each a real
  stage of our pipeline (GPU shader / post-pipeline node) that changes the output, no duplicate of an existing control; own
  variants where useful. Every colour setting (new and existing) moves into one «Цвет» / «Colour» group. Licences: RawTherapee
  is GPL-3 - reimplement from the published maths, copy no code; Lightcraft's licence checked before reading code.
- Done locally (2026-10-15). «Цвет» now: «Баланс белого» (new Тепло / Оттенок / Сохранять тёплый свет / Сохранять холодный
  свет + Цветовой метод, Матрицы DCP), «Насыщенность» (Насыщенность = the AgX one, moved; the three vibrance rows, their page
  dissolved; new Сочность теней / светов, Защита кожи, Тон кожи, Ровность тона кожи), «Цветовой микшер» (8 colours x hue /
  saturation / lightness), «Цветокоррекция» (shadows / mid-tones / highlights hue + strength, balance, blending), «Стиль»
  (Монохром: neutral or a yellow / orange / red / green / blue filter), «Для опытных» (Сжатие охвата, Удержание оттенка:
  full / blues only / off, Насыщенность при матрице OPPO and Путь к белому, both moved). Keys pref_scam_hybrid_colour_*
  (per module like the other SHDR rows), moved rows keep their keys; the colour space stays in «Фото» (a file property).
  How: processing/color/ColourLook - warmth / tint / scene light as one CAT16 3x3 on linear sRGB folded into ArkTone's colour
  chain once per shot (AE statistics and photo agree, no GPU cost; the scene light uses Parameters.sceneCct, unused since P10;
  white keeps its luminance); everything else in ark/combine.glsl under COLOUR_LOOK 1, which ArkCombine sets only when a
  colour row is off its default - COLOUR_LOOK 0 is the shader before P134 token for token (check_colour_output.py's pinned
  hash still matches, so default photos are bit-identical). Scene-OKLab stage before the hue direction is saved (mono filter,
  skin protection of positive gains, vibrance by brightness, 8-band raised-cosine mixer after Lightcraft, skin hue /
  evenness), ACES RGC gamut compression of the linear colour, the hue-lock mode, and the grading in display OKLab after the
  hue lock. The grading hue is a colour-wheel (HSV) angle mapped to OKLab. Availability: a grading hue waits for its strength,
  the AgX saturation acts only with the «Custom» AgX look. Licences: Lightcraft (MIT / Apache-2.0) formulas noted in
  THIRD_PARTY_NOTICES.md; RawTherapee and ArkCam v50's SkinSat (GPL-derived) not used - the skin mask is our own (OKLab
  hue ~50°, C/L 0.03..0.32). Checks: ColourLookTest (matrix identity at defaults, warmth / tint / scene-light directions,
  white luminance, wheel -> OKLab), tools/check_colour_look.py on the GPU (defaults bit-exact in sRGB and P3; mono, filters,
  skin protection, band-limited mixer, grading zones, hue-lock modes, gamut compression near the boundary only, skin hue),
  SettingsMenuTest.colourRowsSayWhenTheyAct; both added to CI.
- Added after (2026-10-15): «Для опытных › Калибровка основных цветов» - hue and saturation of the camera's red, green and
  blue (pref_scam_hybrid_colour_cal_*), ColourLook.calibrationMatrix: each primary turned in OKLab hue (up to 0.5 rad) and
  its chroma scaled, columns rescaled so white stays white; folded into the same per-shot matrix before the white-balance
  rows (ColourLookTest.primariesCalibrationKeepsWhite).
- DONE 2026-10-16: «Плёночный LUT» (Цвет › Стиль): a .cube (LUT_3D_SIZE 2..65, DOMAIN_MIN / MAX, LUT_3D_INPUT_RANGE;
  FilmLut.parse refuses 1D, wrong row counts, non-numbers) from Download/SCAMERA/LUT (ScameraFolder.LUTS; «Импортировать
  .cube…» copies a picked file there after checking it, «Открыть папку» grants the folder), applied by ArkCombine (FILM_LUT 1:
  a 3D RGB16F texture, trilinear, on the display-encoded output before the dither) and mixed by «Сила LUT» (0..100, 100).
  GLProg.setTexture3D. FilmLutTest (3), SettingsMenuTest.filmLutRowPicksAFileOrNone, tools/check_film_lut.py (GPU: identity
  unchanged, a warm look = numpy trilinear, strength 0.5 = half, the input range; within the 8-bit sub-texel weights of the
  filtering, < 4e-4) and glslc for FILM_LUT 0/1 x P3_OUT 0/1; in CI. Hald PNG not added (.cube covers the usual looks).
- Still not done: the skin protection of the AgX saturation above 1, and the live RAW viewfinder's preview of the look (it
  renders with its own ArkCore defaults). Not seen on a phone.
- DONE 2026-10-16, both:
  (1) «Защита кожи» also holds back the AgX saturation above 1 («Custom» look): on skin it acts with (1 - protection), as
  vibrance does; below 1 untouched; under COLOUR_LOOK 1 only (the default shader token for token, check_colour_output's pin).
  check_colour_look: skin under saturation 1.5 x1.525 -> x1.036 with full protection, colours far from skin unchanged.
  The row's text names it.
  (2) The live RAW viewfinder shows the look: LiveRawRenderer reads ArkTone.colourLook() twice a second and folds the white
  matrix (warmth / tint / primaries calibration; no scene light, the viewfinder has no scene CCT) after the camera transform,
  and rawdevelop_fs.glsl runs the same stages as COLOUR_LOOK 1 behind u_look_on (monochrome before OKLab, skin protection /
  vibrance by brightness / mixer / skin hue after its vibrance, the grading on the display value before the gamma) and the
  film LUT in its existing LUT slot with the file's domain (parsed off the GL thread, uploaded once per file). The tone and
  hue lock stay the viewfinder's own (ArkCore). tools/check_viewfinder_look.py (GPU: look off = the shader before P134 bit
  for bit, a neutral look = off, mono grey, shadow grading only in the shadows, skin protection of the mixer, identity LUT
  and its domain; in CI), LiveRawLookTest (matrix order; in CI). Not seen on a phone.

### P140b - fixes after the 30816/30817 builds (owner, 2026-10-15: screenshots + OPPO Find X7 Ultra log, «Исправь»)
- OPPO Find X7 Ultra (PHY110), build 30817: black viewfinder, «Камера не отвечает» / «Сбой камеры (код 4)» on every module. The log:
  with the package spoof ON (5 cameras listed) the first request after onConfigured failed in submitRequestList with
  «Function not implemented (-38)» -> onError 4, three restarts; with the spoof OFF (12:26:32, 3 cameras) the session ran.
  Cause: P109l's typed AttributionSource spoof (ICameraService java Proxy + SpoofCameraContext.getAttributionSource with the
  foreign package) replaced the raw Binder package rewrite of build 30799 on Android 12+. Fix: the typed path only for the vivo
  Qualcomm experiment (CameraPackageSpoof.typedAttribution(): dev switch vivo_qcom_spoof), the owner's spoof uses the raw
  Binder rewrite again; an installed typed proxy is taken out when the experiment is off. Test
  CameraAttributionSpoofTest.ownersSpoofKeepsTheRealAttribution. Not seen on the OPPO yet (not connected).
- «Новый vendor tag»: the stock AlertDialog became a bottom sheet like the other editors (P137), the key type is an explicit
  choice CaptureRequest / Session (the X300 Ultra's forceSensorMode is a Session key) instead of the checkbox, the value is
  checked against its type before saving, «Удалить» on an existing key (SettingsMenuTest.vendorTagEditorIsASheetWithTheSessionType).
- «Цвет» and «Интерфейс» had the same palette icon: «Интерфейс» gets settings_ic_ui (a window with an accent switch, from
  tools/make_ui_icons.py); SettingsMenuTest.rootPagesHaveTheirOwnIcons.
- Option sheets scroll in landscape (P137 fix, f68ec29 / 0554978).

### P140c - X300 Ultra DCG white level; AgX / ACES curve on top of «Тон ARK» (owner, 2026-10-15: «На Vivo X300 Ultra мы не исправили уровень белого, займись этим. Причём появляется проблема например в этой сцене если тапнуть по темному участку»; «Пункт "Кривая AgX и ACES" перенеси в самый верх группы Тон ARK»)
- Log + photo (pink window behind the curtain): the pink shots are the DCG 14-bit ones (forceSensorMode 17). RawLevels took the
  scaled white 16383 (14 bit, black 1022 dynamic), but the merge's highlight statistics show every channel clipped at 0.4995
  of it (12:10:43 R/G/B 0.4995; 12:17:24 G 0.4990 B 0.4988): the stream clips at ~8694 = 1022 + 8 x (1023 - 64). A blown area
  under half the white is not a clip for the worker flags nor for the highlight recovery (lo 1.0, nearClip false), so its equal
  raw channels went through the white balance (R x2.47, B x1.58): pink. A tap on a dark area raised the exposure and blew the
  window. The 10-bit shots (white 1023) clip where declared.
- Fix: RawPayloadCheck.clipPlateau finds a clip in a wider stream's samples (>= 0.2 % on one top value within 0.1 %, the band
  under it thinner; the 4th largest sample as top against hot pixels); RawLevels.noteSaturation keeps the session's highest
  plateau and resolve() takes it as the white of a wider stream when it lies under the scaled one ("white = measured clip").
  Shots, viewfinder and meters use it (forFrame / current). A DCG scene without a blown area keeps 16383 (nothing to measure).
  Tests RawLevelsTest.measuredClipIsTheWhiteOfAWiderStream / clipPlateauOfTheSamples. Not seen on the X300 Ultra yet.
- «Кривая AgX и ACES» is the first row of «Тон ARK» (SettingsMenuTest.curvesOpenTheArkTonePage).

### P143 - the config button in the shade (owner, 2026-10-15, screenshot of the shade header: «Сделай так чтобы в этой области отображалось название действующего конфига в рамке. Убедись что длинные названия не выходят за рамки и масштабируются внутри. Также сделай её как рабочую кнопку с всплывающим меню менеджмента конфигов через которое можно сохранять и импортировать конфиги. Все это должно быть в стиле нашей камеры и с красивыми плавными анимациями»; «Помимо сохранения и применения конфига также должна быть кнопка сброса настроек»)
- «ЗАКРЕПЛЕНО» gives its place to ConfigChip: the config icon, the active config's name and a chevron in an accent frame; a
  long name shrinks 13 -> 9sp and only then ends in an ellipsis, never past the frame (its slot ends before «Изменить»). The
  edit mode still shows «Перетащи плитки» there.
- settings/ConfigState: the active config's name in its own prefs file (scam_config_state, never inside a config); set by
  BackupRestoreUtil on save (the name MediaStore kept) and import (before the restart), cleared by the reset; «Стандартный»
  without one.
- ConfigMenu (a PopupWindow card in the shade's style): «Сохранить» (over the active config), «Сохранить как…» (an inline
  name field; spaces -> «_»), the saved configs (newest first, the active one marked; load after an inline confirm), «Импорт
  из файла…» (SettingsActivity.ACTION_IMPORT_CONFIG opens the system picker at once, back to the camera when cancelled),
  «Сбросить настройки» (inline confirm, warn colour; the app restarts). Motion: the card unfolds from the button (scale .9,
  fade, a 10dp drop, 240ms, PathInterpolator 0.2/0/0/1), its rows follow 18ms apart, inline steps open one at a time with a
  layout transition, the chevron turns, the button dips on press; it folds back on a tap outside / on the button.
- Deleting (owner: «Добавь также кнопку удаления текущего конфига»; «Сохраненные тоже дай возможность удалять»): «Удалить
  конфиг» (bin icon settings_ic_delete, warn) deletes the active config's file after an inline confirm - shown only when that
  file is in Download/SCAMERA/XML; the settings stay, the button says «Без конфига» (ConfigState.detach). A saved config's
  inline step has «Загрузить» and «Удалить»; «Удалить» turns into «Отмена / Удалить», and the deleted row folds out of the
  list (the menu stays open). ConfigFolder.delete: MediaStore for the app's files, the File API otherwise, names only.
- Tests ConfigMenuTest (8 + a render to build/reports/config-menu). Not seen on a phone yet.
- Fixes 2026-10-16 (owner's screenshot: «нельзя прокрутить это меню вниз из-за чего кнопки сбросить не видно»; «кнопка сброса
  должна полностью все настройки сбрасывать на дефолтные»; «Полный сброс должен быть по кнопке в меню конфигов»; «Полный сброс
  не должен удалять конфиги»; «наш апк должен автоматически добавлять новые конфиги в список в случае если я закину его к нам
  в папку /storage/emulated/0/Download/SCAMERA/XML/»; «Android/data не подходит»):
  - the whole card is one scroll (ConfigMenu.MaxHeightScroll), never taller than the room under the button (limitHeight
    from getWindowVisibleDisplayFrame); the saved list is part of it (no scroll in a scroll).
  - BackupRestoreUtil.resetPreferences: every shared_prefs file cleared through its SharedPreferences (Android keeps them in
    memory: a write before the restart put the whole old file back when only the folder was deleted), then deleted;
    scam_dev.txt -> scam_dev.txt.bak. Configs (Download/SCAMERA/XML) and curves are not touched; the reset text says so.
  - Download/SCAMERA opened to the app once through the system folder picker (SAF, a lasting grant; no storage permission):
    ScameraFolder lists, opens and deletes every file of XML / ToneCurves through it (MediaStore shows an app only its own
    files). «Открыть папку конфигов» in the config menu (SettingsActivity.ACTION_OPEN_FOLDER) and «Открыть папку» in
    «Загрузить кривую» until the grant exists.

### P144 - ACES, Uchimura and an own curve in «Кривая AgX и ACES» (owner, 2026-10-15, ArkCam 1.42 APK: «Добавь алгоритмы тоноотображения как в arkcam "ACES" "Uchimura" Включение каждой из них сделай в отдельной группе. Также дай возможность вводить свою кривую с наглядной визуализацией, также на визуализации дай возможность менять кривую пальцем. Мы должны иметь возможность добавлять сколько хотим точек у кривой»; «Для "ACES" "Uchimura" в отдельных группах настроек где они включаются должны быть только настройки касающиеся их»; «Дай также возможность ползунками двигать добавленные точки и ползунками, либо вписывать вручную значения»)
- ArkCam v142 tonemap_final: eval_acescg x(ax+b)/(x(2.43x+d)+e) with b = max(toe*0.75, 0.005), e = max(0.14-(toe-0.04)*0.8,
  0.03) after a pre-gain; uchimura_curve (GT: P, a, m, l, c, b) between a pre- and a post-gain; the own curve as a 256-entry LUT
  over x = 0.5 + EV/10 (±5 EV around 0.18) -> display -> ^2.4. Ours: the same, in place of the AgX sigmoid inside ark/combine.glsl
  (AgX inset and outset kept; ACES / Uchimura clamp 0..1 before the outset). `#define TONE_OP 0` keeps the old token stream
  (check_colour_output pins it); ArkCombine sets TONE_OP 1 only when one of them is on (ArkTone.toneOperator: curve > Uchimura >
  ACES > AgX) and drops the AgX look then. Uniforms toneOpU, acesU, uchiAU, uchiBU, curveU[64] (GLProg.setVarVec4s).
- Settings: the curves page holds AgX, then the groups ACES, Uchimura, «Своя кривая»; each starts with its «… вместо AgX»
  switch and holds only its own rows (they depend on it; ACES Toe stays live - it also shapes the ACES inversion). One at a time:
  switching one on turns the other two off (SettingsActivity.TONE_OPERATORS, the store first). AgX's rows say which one works
  instead. Bounds as ArkCam (SettingsNumericRules).
- The own curve (processing.color.ToneCurve, "x:y,..." in pref_scam_hybrid_ark_curve_points, default ArkCam's S): a
  Fritsch-Carlson monotone cubic, as many points as wanted (0.01 apart), the ends keep x. ToneCurvePreference / ToneCurveView:
  a graph with a grid, the straight line dashed, −5 EV / 0 (grey) / +5 EV; tap = a point, drag = move, hold = delete; «S-кривая»
  and «Прямая». The touched point stays selected: ‹ ›, «Удалить», sliders «Экспозиция» (EV) and «Яркость» (%), each value taps
  open the exact-value sheet (PrecisionEditor; exposure limited to the neighbours). Stored when a gesture / slider ends.
- Curve files (owner: «Дай также возможность сохранять и импортировать тоновые кривые, папку можешь сделать для них в
  Download/SCAMERA»; «для тоновых кривых сделай отдельную папку»): Download/SCAMERA/ToneCurves, apart from the configs' XML
  (util/ScameraFolder: one MediaStore / File API folder class, ConfigFolder now delegates to ScameraFolder.CONFIGS). A file
  is a header comment and the "x:y,..." points (.txt); reading takes every "x:y" pair of any text (ArkCam's string, hand-typed
  lists; 0..255 scaled), refuses fewer than two points. «Сохранить» (a free «Кривая_N» offered), «Загрузить» (the saved
  curves with a preview, a tap loads, the bin asks once more and deletes; «Из файла…» = the system picker, opened by
  SettingsFragment.curveImportLauncher in that folder).

### P145 - processing from the CPU to the GPU (owner, 2026-10-16: «Какие из процессов обработки у нас всё ещё идут на ЦПУ?», then «Переноси всё что можно из этого списка на гпу»)
The CPU audit (three readers over capture, worker and post pipeline) found on every default shot: CRE + tile LK alignment,
RAW CA correction, the Bento mask and the effective-map / output passes in the worker; the frame copies (ZSL ring, arena,
worker result); the ScamRgb half -> RGBA16F upload repack; ArkAe statistics (arkLow read back, 2 single-threaded passes);
the single-threaded jpegli encode and the ExifInterface rewrite. By option: HEIC 10-bit RGB -> P010 in Java, AVIF (libaom),
Ultra HDR GainMapComputer (2 Java passes), live-RAW viewfinder copies and Quad 2x2 binning, gallery DNG render.
Each step moves with a check that the GPU gives the CPU's result; the CPU path stays as the fallback.
- a. DONE: ArkAe statistics -> compute ark/aestats (ArkAeGpu): max key, two 4096-bin histograms (luma / max in one shared uint
  per bin), per-group sums; 33 KB back instead of 25 MB; self-check of the total weight, else the old read-back.
  ArkAe split into stats / finish (same code for both). tools/check_ark_ae_gpu.py (GPU vs a float32 copy of the Java loops:
  max exact, <= 0.0015 % of the weight in a neighbour bin from fused multiply-adds), ArkAeTest.gpuStatisticsGiveTheSameExposure.
- b. DONE: ScamRgb's half RGB -> RGBA16F repack -> compute scamhdr/halfrgba (HalfRgbUpload): bands copied unchanged into an
  SSBO (two, alternating), widened by unpackHalf2x16 and stored with imageStore. Used only where a once-per-process probe
  of all 65536 halves comes back bit for bit (GLES may flush 16-bit denormals); the CPU repack otherwise, for post_ab and
  on a GL error. tools/check_half_rgba.py, HalfRgbUploadTest. Log: "scamrgb input ms: upload=.. (half rgb, gpu repack)".
- d. DONE (Ultra HDR option): GainMapComputer's two Java passes -> ultrahdr/gainmax (the largest byte, compute) and
  ultrahdr/gainquant (GainMapComputer.requantisation as a 256-entry table) inside RunHDRGainMap; GainMapRaw carries
  normalisedMax, ScamProcessor takes the map as it is. At 1x bit for bit the CPU's map and range (GainMapComputerTest); a
  later resize / crop (2x grid, zoom, ISZ) filters the requantised bytes instead of the encoded ones (within 1/255, the
  range stays). tools/check_gainmap_gpu.py.
- e. DONE (Ultra HDR on the 2x grid): the base was resized on the CPU after the pipeline (ScamPostDownscale Lanczos:
  816 ms 50 MP -> 20 MP, 2650 ms 200 MP -> 20 MP in the X200 Ultra log of 2026-10-15) because the gain-map pass needed the
  base at the grid size. Now HybridFinalResize runs with the gain-map pass too; the linear snapshot is resized on the GPU to
  the same final size (final_resize LINEAR_IN 1, "area": no clamp, no sRGB) before its read-back, and RunHDRGainMap builds
  the map for that size. The read-back shrinks (16 B a pixel: 800 MB -> 320 MB), and the 200 MP grid no longer exceeds the
  2 GB snapshot limit (Ultra HDR fell back to SDR there). tools/check_linear_resize.py.
- NEXT (worker, needs the OPPO for the md5 replay of the control series, rp0.sh; not done without it): measured in the X200
  Ultra log of 2026-10-15 - the CPU front end is spawn -> w_f6 = 0.64 s of a 1.4 s worker on a 12 MP shot, 1.3-1.7 s on the 2x
  grid ("HYBRID STAGES ms: align=366 localAlign=983", up to align=1740 localAlign=3727 on FULLRES). In order:
  1. F6 tile Lucas-Kanade (scam-hybrid.h 4233-4314, 5897-5938, LaStream): a compute pass per pyramid level (the gray
     pyramid, the 32x32 windows every 16 px, 2 iterations), the field already goes to the GPU (originL). Check: the field
     against the CPU's within 1/64 px, then md5-free replay metrics (dark_fade, texture) on the 8 series.
  2. RAW CA correction (H 5021-5075): the R/B resample as a pass on the merged strip before its read-back (halo rows of
     the largest shift); the fit stays on the CPU (sampled).
  3. Bento mask (H 4049-4132): clip scan, dilation and the 7x7 Gaussian as passes on the half-res cell grid; the hole search
     (connected components) stays on the CPU.
  4. Effective map / clip flags / half conversion after the merge (H 6317-6359, W 224-256): written by the merge shader
     directly in the transport format.
- Replay on the X200 Ultra instead of the OPPO (2026-10-16): the worker runs over adb shell from /data/local/tmp/scam_rp
  (job/ = symlinks of the installed APK's .so, SCAM_HYBRID=1, scratchpad vrp/vrp.sh). Its outputs flipped between two md5 on
  the same burst: the Adreno 830 driver compiles the merge program (with LOCAL_ALIGN) into one of two binaries (8425 / 8427 B,
  output follows the binary 1:1; every input buffer, the frame table and the uniforms byte-identical, per-pass hashes). Not a
  race of ours; both give the same quality (syn_b1 against its truth: PSNR 20.33 / 20.32, MAE 0.0334 both). The app keeps
  the first binary (program cache + prewarm). Replays pin it: SCAM_GL_CACHE=<dir>, then md5 repeat (5/5). vivo references
  (cache glc_head, SCAM_NO_CRE=1): syn_b1 3079e529.
- DONE on the vivo replay (2026-10-16; synthetic textured bursts, scratchpad vrp/gen_big.py: t12 12 MP x12, t50 50 MP x8,
  t50 with grid 2 = the 200 MP output; md5 unchanged at every step: t12 3d734caa, t50 b4c5cfd3, t50 grid 2 65a51054 /
  00f4fe18 with rgb-half):
  - P151: the F6 field streams alongside the merge above 16 MP too (it ran whole before the merge, the GPU idle): 50 MP
    5.1-5.7 s -> 2.7-2.9 s.
  - P152: the alignment guide above 16 MP from per-phase tables on the pool (binnedGuide called sampleRaw for every site
    on one thread): align 0.76-0.81 s -> 0.34-0.36 s; byte-identical (check_hybrid_prefetch 1b, run on the phone).
  - P153: the half-float output converted straight into the app's memfd with FCVT (no 1.2 GB half copy): 200 MP output
    1344 MB in ~0.93 s (memfd replay helper vrp/memfd_run.c); «SCAM OUTPUT» log line. A file output keeps write().
  - Now: 50 MP 2.2-2.4 s merged; the 200 MP grid 5.6-7 s merged + ~0.9 s output (was 7.4 + 2.3 s); 12 MP unchanged
    (F6 already hidden behind the merge, align 0.1 s).
  - Left, and why: F6 LK on the GPU would now compete with the merge for the GPU (the CPU field is hidden behind it at every
    size); the merge pass itself (kHybMergeMain1, 4.9 s of the 2x grid's GPU time) is not to be edited; RAW CA 0.1 s, Bento
    only with ultrashort frames.
- Remaining CPU work, why it stays: jpegli JPEG (entropy coding; ~1 s at 20 MP q100, 4-5 s at 50 MP - could run in strips on
  several threads with restart markers, a CPU change), ExifInterface rewrite (I/O), frame copies (camera buffers must be
  released), CRE global alignment (vendor library), ScamRgb's sampled clip statistics (every 17th pixel, overlapped with the
  upload), HEIC 10-bit P010 (Java on 4 threads; a GPU pass needs the pipeline to read back P010 planes instead of the
  1010102 bitmap - a restructure), AVIF (libaom), live-RAW copies and Quad binning (binning before the upload is the cheap
  side), gallery DNG preview (Java render outside any GL pipeline).
- Not for the GPU: JPEG / AV1 entropy coding, the vendor CRE alignment (closed library), memory copies, EXIF, the cpu-single
  fallback (it exists for a GPU that failed).
- Checks: ToneCurveTest 5, SettingsMenuTest (groups, one at a time, graph + sliders + typed values, a render to
  build/reports/tone-curve), tools/check_tone_ops.py (GPU vs numpy: ACES, Uchimura, the straight and the S curve, max 1e-4);
  glslc for every TONE_OP variant. Not seen on a phone yet; the look vs ArkCam not compared on photos.

### P147 - faster saving and processing without the OPPO (owner, 2026-10-16: «Сделай полностью всё что можешь по ускорению без оппо»)
From the X200 Ultra log of 2026-10-15: JPEG save 0.6-1.0 s at 20 MP and 3.8-4.8 s at 50 MP (jpegli on one thread), and the
EXIF rewrite after it 56-654 ms (ExifInterface reads and writes the whole file again).
- a. DONE: the EXIF APP1 goes into the JPEG stream right after SOI (ExifBlock.app1Segment: the same tags) together with the ICC
  segments (IccEmbed.jpegInserting(out, exif, icc)); no rewrite. Without the block (it failed) the old rewrite runs.
  JpegExifStreamTest: the tags equal the rewrite's, one EXIF segment, the profile kept.
- b. DONE: the JPEG in strips on every core (scamera-jpeg-parallel.h; owner: «можешь добавить другие варианты помимо jpegli
  если они будут эффективней»). Whole MCU rows a strip, each strip a baseline jpegli JPEG on its own thread; pass 1 (every
  k-th strip) gives code lengths -> shared optimal Huffman tables (Annex K.2, every valid symbol keeps a code); pass 2 encodes
  every strip with them; the entropy data joined with RSTn (DRI = the MCUs of a strip). Headers of all strips must match,
  else the one-thread progressive encode (nothing written yet). From 4 MP; scam_dev "jpeg_threads" (1 = one thread).
  X200 Ultra (tools/jpeg-check, adb shell): 20 MP photo q100 542 -> 152 ms (3.6x), 8 % smaller; 50 MP q95 1026 -> 251 ms
  (4.1x), 10 % smaller; PSNR to the source the same, strips vs one thread 71-84 dB (only jpegli's adaptive quantisation at a
  strip's edge differs, max 1-4 levels); libjpeg-turbo (Pillow) decodes it the same. CI builds and runs the check on Linux.
  libjpeg-turbo not added: jpegli in strips is already ~0.15 s for 20 MP; another library (a download, a new dependency) would
  save ~0.1 s more at a slightly lower quality per byte.
- c. DONE: Ultra HDR's linear snapshot read back and uploaded again as the halves the texture holds (8 B a pixel instead of
  16; only where ArkStats' probe finds the driver's half read-back exact - the same texels): LinearExposure took 1.2 s at
  50 MP. ScamRgb's decimated copy (every 61st pixel, 140 ms on one thread at 50 MP - the slowest input step once the repack
  went to the GPU) on the worker threads.

### P148 - «Камера и сенсоры» simpler for an ordinary user (owner, 2026-10-16: «Займись меню камеры и сенсоры, упрости его максимально чтобы обычному человеку было проще им пользоваться, при этом функционал никак не урезай. Можешь перестроить/переделать его как хочешь, главное чтобы оно соответствовало общему стилю нашего приложения»)
- The page pref_sensor_config_submenu (SettingsFragment.generateSensorConfigPreferences, ModuleSettingsFragment, the module
  chips and the per-slot sensor overrides, vendor tags, TunableKeyPreference): every setting stays reachable, nothing
  removed.
- Plan before building: map every row of the page today (rows, what each changes, who needs it); then a structure for an
  ordinary user - the module first (a chip row as now), then a few plain groups (Изображение / Экспозиция / Фокус /
  Стабилизация / Для опытных), the vendor tags and raw sensor values under «Для опытных» (folded), plain titles and
  one-line descriptions, the reason a row is off (SettingsAvailability) as elsewhere; the cards, icons and sheets of
  SettingsStyle (P132 / P136 / P137).
- A module copy with its own lens crop (owner, 2026-10-16: «я хочу иметь возможность при копировании выставлять кроп
  объектива, как мне нужно. Например я копирую 1х камеру и я хочу чтобы копия всегда была условно с 1.3х кропом»): the copy
  step (ModuleCopyFragment / ModuleRegistry slot) asks for the crop (1.0 = none, e.g. 1.3x; a slider plus a typed value), the
  slot keeps it ("crop" of the lens passport, LensProfileMatcher already reads it), the camera on that module starts at that
  crop (ZoomController: the module's base zoom, the zoom label shows 1.3x) and the photo is the cropped field of view
  (ZoomController.crop / the digital zoom path, the gain map follows). Configs carry it (P141).
- Checks: every key of today still on the page and bound (SettingsMenuTest: allPagesAndRegisteredTunablesAreReachableAndBind,
  the sensor page tests), renders for a look (build/reports/module-concept), check_ui_language / check_settings_model.
- DONE 2026-10-16 (nothing removed):
  - «Камеры и сенсоры»: one line on what a module is; the untouched filler slots (hidden slots beyond the lens count that
    nobody set up) are a count («свободных мест: 5») instead of five «2× · ID 5 · скрыт» rows; «Добавить модуль с кропом»
    on the page (pick the module, then the crop); hidden modules someone set up are listed as before.
  - Module page in three groups: «Основное» (name, zoom and sensor crop, viewfinder button), «Копии модуля» / «Копия модуля»
    («Сделать копию с кропом», «Кроп копии» on a copy, «Удалить копию»), «Для опытных» (Camera ID, «Сенсор и вендорные
    ключи», the stream's colour block). «Дубликат» is «копия» in the texts.
  - Copy with a lens crop: ModuleRegistry.duplicate(source, crop) - the copy's zoom = the source's x crop, named after it
    («1.3×»), module_crop_<slot> keeps the crop (a copy of a copy multiplies it); nativeRatio uses zoom / crop, so the
    camera crops the frame by it from the lens's uncropped zoom also with the original button hidden and for a copy of a
    sensor-cropped (ISZ) module; every other module has crop 1 (unchanged). The crop sheet: none / 1.2 / 1.3 / 1.5 / 2 / a
    typed value (1..10) with the resulting button zoom beside each. Configs carry module_crop_ with the other module_ keys.
  - ModuleDuplicates.freeSlot takes an untouched filler first (before a hidden lens module the owner may still want).
  - Tests: SettingsMenuTest.aModuleCopyWithItsOwnLensCrop (page groups, the sheet, zoom / crop / nativeRatio, hidden 1×, ISZ
    copy), ModuleDuplicatesTest.aCopyWithACropTakesTheCroppedZoomAndKeepsItsCrop; renders modules-p148 / module-page-p148 /
    module-copy-p148. Not seen on a phone yet (the photo's crop follows ZoomController's residual as for any zoom).

### P146 - FULLRES as its own settings group (owner, 2026-10-16: «Так же сделай отдельные настройки для 50мп как для отдельного модуля, сделай отдельную группу "FULLRES" где я смогу полностью всё так же настраивать как для остальных. Пункт пускай будет доступен только на смартфонах где у нас будет рабочий фулрез»)
- A FULLRES profile beside the module profiles (ModuleProfiles / module_profile_v2_<slot>): every per-module setting
  (capture, SHDR / Hybrid, ARK tone and curves, denoise, sharpening, output) stored for the 50 MP FULLRES shot of a lens and
  applied when the shot runs FULLRES (VivoSdkFullRes route), the normal module profile otherwise.
- Settings: a «FULLRES» group in the module settings, built like a module page (the same rows, copy from / to a module);
  shown only where FULLRES works on this phone (the VivoSdkFullRes support check of the lens: today the X200 Ultra main
  camera; never on phones / lenses without it).
- Configs (ConfigXml) carry the FULLRES profiles like module profiles; moving to another phone maps them by lens (P141).
- Before starting: the FULLRES route is ChatGPT's uncommitted work in CaptureController / VivoSdkFullRes* - build on it only
  once it is committed; the open onError 4 of FULLRES + 2x (HANDOFF §6) first.

### P149 - «Уровень чёрного» / «Уровень белого» of a module checked and brought up to date (owner, 2026-10-16, screenshot of the two rows on the sensor page, both -1 with the thumb at the left end: «перепроверить работоспособность этих настроек, обнови их под нынешние реалии»)
- Today: Parameters @SensorConfig blackLevelOverride (float -1..8192) / whiteLevelOverride (int -1..65535), per module slot
  (ModuleSensorSettings pref_sensorconfig_<slot>_*); applied in Parameters after RawLevels.forFrame as absolute values in the
  stream's units, then Allocator.binning multiplies them by 4, widenToSamples keeps them; an active black override turns
  the data floor («Уровень чёрного по кадру», refineBlackLevel) off. The slider runs over the whole range: one step of
  the thumb is hundreds of levels, -1 sits at the left end and reads as a number, not as «Авто».
- Check every consumer gets the override, on each path, by a log line and a test: post pipeline / SCAM HDR (ScamProcessor,
  ProcessorBase), Hybrid worker (ScamHybridBurst: the levels sent with the burst), Quad / Tetra mosaic (ScamMosaic,
  HexQuadBurst), DNG tags (DngCreator, SaverImplementation), live RAW viewfinder (LiveRawFrame / LiveRawRenderer /
  LiveRawMeter), ScamDiagnostics' levels, FULLRES and ISZ shots (another stream depth on the same lens), binned and
  10 / 12 / 14-bit streams (P127 RawLevels, X300 Ultra DCG 14 bit of P140c). The override must act once (not twice through
  binning or widening) and in the same units everywhere.
- Bring up to date: the row shows the camera's own value for the active module (e.g. «Авто: 64 / 1023, 10 бит», from the
  last shot's levelSources or the characteristics), «Авто» instead of -1, the range of the current stream's depth (not
  0..65535) with a fine slider and the typed value (PrecisionEditor as in P144); a value given at one bit depth follows the
  stream's depth (stored with its depth, rescaled like widenToSamples) so a FULLRES / ISZ / DCG shot of the same module is
  not wrong. Keep the related rows together (dynamic white level, «Уровень чёрного по кадру») and say in the description
  that the override turns the data floor off. Configs (P141) carry the values with their depth.
- Checks: unit tests for each path (override set -> the consumer's levels), the binning / widening cases, SettingsMenuTest
  for the row (Авто, range, typed value), check_ui_language / check_settings_model; on the X200 Ultra a shot with a black
  override and the «RAW levels: ... black override» line in the log; DNG tags read back.
- DONE 2026-10-16 (audit + UI): every consumer takes the override - Parameters (FillDynamicParameters) feeds the post
  pipeline / SCAM HDR, Hybrid (ScamHybridBurst: p.black / p.white with the burst), ScamMosaic, HexQuadBurst, DngCreator tags,
  ScamDiagnostics; the live RAW viewfinder reads the same keys per frame (CaptureController publishLiveRawFrame). Units: both
  apply the value in the stream's resolved units (after P127 depth resolution: the viewfinder after RawLevels.current, the
  shot after RawLevels.forFrame; a black override is kept when the shot's samples widen the depth, a white override stops the
  widening): consistent, so no rescaling added. Binning (x4) scales the override with the data. UI: «Авто» for -1
  (TunableSeekBarPreference.setAutoValue), the module's own levels under the description («Авто у этого модуля: чёрный 64,
  белый 1023 (10 бит)», its physical camera's characteristics), ranges in that module's units (black up to white / 4 and 4x
  black, white up to four bits above the declared white; a stored value beyond widens it), descriptions say the units and
  that a black override turns «Уровень чёрного по кадру» off. SettingsMenuTest.rawLevelRowsShowAutoAndTheModulesOwnLevels.
  Not seen on a phone yet.

### P150 - artifacts of a still shot: dashed wires, dotted edges, purple dots at a lit lamp (owner, 2026-10-16, SCA_20261016_001335.jpg in SCAMERA.zip, crop of a chandelier: «словил вот такие артефакты, пойми в чём причина и исправь»; «Учитывай что виво сейчас неподвижен, а у нас детализация увеличивается за счёт тряски рук/сдвига кадра»)
- The shot: vivo V2419A (not the X200 Ultra; Dimensity / Mali), camera 3 (13.3 mm f/2.57 tele), ISO 199, 1/50 s, 4096x3072,
  Ultra HDR; no log in the archive. Artifacts only next to the lit lamps and the dark metal in front of them (a map of the 2x2
  checker energy over the frame): 1 px checker / dashes along dark edges on clipped glass, purple dots, broken horizontal
  segments at the orange / white rim. The checker sits on ONE Bayer phase (odd row, even column: 3.3x the others, nothing at
  mod 4): a Bayer-domain merge problem, not the RGB post pipeline and not a Quad remosaic.
- Cause (DONE, reproduced on the X200 Ultra by worker replay of synthetic bursts, scratchpad vrp/gen_static.py): cellClip
  (a 2x2 cell with any clipped site is clipped for every colour of that frame) takes the same sites away from EVERY frame of a
  still burst; a thin dark detail in front of a clipped light then has no sample of a colour phase and gets the clipped mean:
  dashed wires, dotted edges. With hand shake other frames hold those phases (the same scene with +-1.5 px jitter is clean).
- Fix (DONE): hybridMovedDonors / hybridStillBurst - fewer than a quarter of the donors moved >= staticShift (0.75 RAW px,
  homography at the corners and the centre) = a still burst: the merge runs with cellClip 0 (log «HYBRID STILL: ...»). Moving
  bursts are untouched (syn_b1 and the jittered synthetic burst: the same md5). Synthetic still burst, error at the detail
  0.079 -> 0.034, p99 0.75 -> 0.26, chroma p99 0.24 -> 0.15; the green-clipped sky (the magenta-rim case cellClip was made for)
  shows no magenta rim either way. tools/check_hybrid_still.cpp (CI; run on the X200 via NDK clang + adb: PASS).
- Open: the owner's phone's log of such a shot (DCIM/SCAMERA/Log) to confirm «HYBRID STILL» there; Mali was not checked.

### P154 - highlights: a smooth roll-off, white stays white, colour recovered in blown areas (owner, 2026-10-16, Desktop/т/7u/300.zip: X300 Ultra SCA_20261016_085018 (DCG 14 bit, ISO 3200, 23 N + 3 L, no short frame) and _085037 (10 bit, ISO 270, Bento), a backlit window with a curtain: «Посмотри на засветы на данных примерах, я хочу чтобы засветы были с более плавным переходом и не такие резкие»; «Одно фото снято с DCG, второе без»; «Также нам нужно чтобы белый цвет никогда не уходил в серый»; «И также нам нужно восстановление цвета в областях засветов»)
- Found (diagnostic stages, 1020x766): the DCG shot is clipped in every channel over the window (ScamRgb plateau 2.41 in R, G and B,
  «highlight recovery per channel ... plateau, nearClip»): no colour there, it must end white. The output plateau is 0.92
  (235/255): the AgX shoulder does not reach 1 at the data's clip (clip 3.16 / 3.72 after ae), so white renders grey. The
  output rises with the signal up to the clip and is flat from there: the slope stops at once (a Mach band at the plateau's
  edge) - the «резкий» look. hlWhite (0.15, path to white) mixes saturated bright light towards its luminance Y, i.e. down
  towards grey, not up towards white.
- Do: (1) a highlight finish after the tone: the pixel's clip ratio (signal / data ceiling, from the colour source) lands the
  display value smoothly (zero slope) on the white point, starting well below the clip, so the gradient fades into the
  plateau; the clipped plateau = 255. (2) Colour where the data has it: channels below the clip keep the ratio (per-channel
  recovery of ScamRgb / the worker's rim pass), the landing goes to the colour at full brightness (max channel 1), the
  path to white goes up to white, not down to grey. (3) Fully clipped (no channel left) = white. Check on host (the combine
  shader on ramps and a clipped plateau: white = 1.0, C1 at the landing, hue kept below the clip) and on the owner's
  shots: no grey whites, no new halos, mid-tones unchanged.
- DONE 2026-10-16 (ArkCombine HL_LANDING 1, «Мягкость засветов» pref_scam_hybrid_ark_hl_soft 0..0.9, default 0.5, 0 = the
  shader before): only with clipped data in the frame (headroomU; the ARK clip is the frame's maximum, so a frame without a
  real clip keeps its tone - a white paper in shade is not pushed to white), the display value lands on the white point by
  smoothstep(1 - soft, 1, max3(colour source) / ceiling): the pixel's own colour at full brightness (max channel 1), a
  near-neutral one (saturation < ~0.1) pure white; the path to white goes to the white of the brightest channel, not the
  grey of its luminance. On the owner's diagnostic stages (numpy of the same formula): the DCG window 0.906-0.919 grey ->
  0.965 / 0.991 / 0.999 / 1.0 by fifths up to the ceiling; the non-DCG window 0.88/0.90/0.93 blue-grey -> 1.0 white.
  tools/check_highlight_landing.py (GPU: the blown grey and a slightly blue near-white end on 1.0, nothing changes below the
  start, monotonic, the last steps shrink, an orange light keeps its channel ratios, nothing without clipped data; in CI);
  check_colour_output still pins HL_LANDING 0 to the pre-P46 render. Not seen on a phone yet; the AgX desaturation of very
  saturated light below the clip is as before.
- v2 2026-10-16 after the owner's look at the v1 preview («Тут очень резкие переходы с уходом в серый»): the per-pixel
  smoothstep doubled the edge gradient of the curtain folds between the white blobs (p95 of |grad| 0.0066 -> 0.0122 on the
  DCG window), the folds read as grey stripes. Now the landing follows the BLURRED share of the ceiling: ark/hlmask.glsl on
  the arkLow grid (max channel / ceiling, then a gaussian of 1.2 % of the width along x and y; three passes into R16F
  textures, only with a real clip and hl_soft > 0), sampled bilinearly by combine.glsl (ArkHl); the curve is a shoulder
  1 - (1 - t)^2 from 1 - hl_soft of the ceiling (steepest at its start, zero slope at white), gated by the pixel's own
  brightness (smoothstep 0.15 - 0.45 of the ceiling: dark objects in front of a light get no glow). Default 0.7. On the DCG
  window (numpy of the same chain): p95 0.0071, the folds at 0.93 instead of grey stripes, the blown area 1.0.
  check_highlight_landing: hlmask = numpy gaussian (1e-7), the ramps as before with the shoulder from 0.3, dark pixels inside
  a blown neighbourhood untouched, a blown disc with a soft edge ends white and its steepest step relative to the whole rise
  of the edge is lower (0.175 -> 0.128); glslc of all 32 combine variants + hlmask. Not seen on a phone yet.

### P141 - config files: saving, applying, moving between phones (owner, 2026-10-15: «Ещё добавь в план работы перепроверка работы с конфиг файлами. Проверь чтобы всё правильно сохранялось и применялось. Так же проверь чтобы на разных смартфонах с разными айди модулей конфиг применялся нормально и автоматически подставлялся/адаптировался под другое количество модулей, при этом сделай так чтобы модули не дублировались и не появлялись лишние»)
- Audit «Сохранить / Восстановить» (BackupPreferences, RestorePreference, ModuleProfiles, module_profile_v2_* files, the
  sensor-config keys pref_sensorconfig_<id>_*, vendor tags, shade tiles, the colour settings): everything saved is restored
  and acts after a restart; nothing restored that the app no longer has.
- Another phone: modules are matched by what they are (lens: facing, focal length / zoom, camera kind - BackupLensMatchTest is
  the start), not by slot or camera id; a config from a phone with more modules drops the extras, one with fewer leaves the
  phone's own; ISZ / sensor-mode presets and vendor keys only land on a camera of the same model (Build.DEVICE + id), else
  they are skipped and said so.
- No duplicates and no extra modules after a restore (ModuleDuplicates, ModuleRegistry capacity, the vivo QCOM / PD2454 ISZ
  offers must not run again for a restored module).
- Tests with configs of the X200 Ultra, X300 Ultra, OPPO X7U and a Pixel restored onto each other.
- DONE 2026-10-16 (the audit found five ways a restore went wrong; BackupRestoreUtil / ModuleProfiles):
  (1) on the same phone the profile listener reacted to the new main settings while they were written (CAMERA_ID /
  per-lens change) and loaded this phone's OLD profile of the module into them, which stayed: the whole apply now runs
  under ModuleProfiles.quietly (the app restarts after an import and reads meta / profiles / main as written);
  (2) a file without the phone's name (a plain prefs map) counted as this phone's and replaced its slots and sensor modes:
  now only when every Camera ID of its module slots exists here, otherwise it goes as «an unknown phone»'s;
  (3) the automatic module offers (module_isz_offered_*) this phone already made are kept on a same-phone import (an old
  file let them run again and add a second copy of a module);
  (4) this phone's hidden modules kept their profiles from before an import from another phone: their exists_ flag goes, so
  shown again they start from the config's baseline;
  (5) the lens passport of a «1.3×» copy read the lens as 1.3x longer only while the 1x button was shown: the uncropped zoom
  is zoom / module crop. SettingsMenuTest (4 new, each fails on the old code) besides the existing same-phone / another-phone
  / per-lens-off / legacy-map tests and BackupLensMatchTest. Not tried with real configs of the four phones.

### P142 - separate exposure and focus points (owner, 2026-10-15: «добавь раздельную экспозицию и фокусировку. Сделай это как опцию. Т.е. при тапе появляется кольцо фокусировки и вокруг него квадрат экспозиции, я должен иметь возможность переносить куда мне нужно раздельно квадрат экспозиции и круг фокусировки»)
- An option (off by default): a tap shows the focus ring with the exposure square around it; each can be dragged on its own
  (AF regions follow the ring, AE regions the square), both in the viewfinder's coordinates mapped to the sensor crop of the
  current module / zoom (control/SubjectMapping). Long press or a reset tap joins them again; the existing AE/AF lock stays.
- Exposure metering of the square also for the SHDR plan (the N exposure is taken from the preview AE).
- DONE 2026-10-16: «Раздельные фокус и экспозиция» (pref_split_af_ae, off by default, after «Следящий автофокус»). A tap puts
  both regions on the point and shows the ring with the exposure square (ExposureSquareView, 1.7x the ring, a sun at its
  corner) around it; dragging the square sends only the AE regions in the repeating request (throttled to 80 ms, no AF
  trigger), dragging the ring runs the focus sequence on its new region with the square's AE region kept; a tap on the ring
  resets to auto as before, a tap on the square joins it back to the ring, a tap elsewhere puts both there; while separate
  they stay on screen, dimmed after 3 s. The AUTO fallback retry keeps the square's region. CaptureController untouched
  (TouchFocus only). TouchFocusSplitTest (5). Long press stays the tracking AF (P42), so it does not join them. Not seen on a
  phone yet.

### P155 - tap-to-focus on the X300 Ultra without DCG (owner, 2026-10-16, Desktop/log-2026-10-16.txt and video_2026-10-16_10-00-09.mp4: «На 300 ультра также не работал нормально фокус по тапу, с DCG вкл же работает нормально»)
- Found: the video taps on a near object several times without DCG: it stays blurred, the window behind it sharp; with DCG
  the same tap makes it sharp. The log: with DCG 14 bit the HAL answers the new AF region with a passive scan (PASSIVE_SCAN
  ~0.4 s after the cancel, ~0.6 s long), then the START locks the focused lens; without DCG no passive scan comes at all, the
  START is sent after the 400 ms dwell on PASSIVE_FOCUSED and every tap ends FOCUSED_LOCKED after 12 frames without an
  ACTIVE_SCAN. In CONTINUOUS_PICTURE a START on PASSIVE_FOCUSED only locks the lens where it is (the Camera2 AF state
  machine), so the tap focused only when the HAL's passive AF had already swept the region.
- DONE 2026-10-16 (TouchFocus only): without an observed passive scan in the dwell the START goes in AF_MODE_AUTO (the
  one-shot and the repeating request after it): an active scan on the tap region, locked there until the next tap or the
  reset to auto (as the AUTO fallback; the ZSL capture copies the preview's AF mode, no new trigger). With a passive scan,
  without AF_MODE_AUTO on the camera or with the 2 s budget spent the bare START stays. TouchFocusActiveScanTest (4, in CI).
  Not seen on the X300 Ultra yet: check that a tap without DCG now shows ACTIVE_SCAN in the log («sending AF START in
  AF_MODE_AUTO») and the object comes sharp.

### P156 - the settings chip picks the module to edit; a fresh start opens on 1x (owner, 2026-10-16, screenshot of «Настраивается: 1× · ID 3» on the settings root: «Сделай так чтобы эта кнопка была интерактивна и мы могли менять модули тут для настройки. Так же сделай так чтобы после полного закрытия приложения у нас открывалась не последняя используемая камера, а сбрасывалось на 1х»)
- DONE 2026-10-16. The chip on pages with per-module values (per-lens settings on) is a picker with more than one shown module
  (back by zoom, then front; the camera's module marked «сейчас в камере»): the chosen module's profile goes into the main
  settings (ModuleProfiles.activate, the activity recreated, the page stays), so every page shows and edits its values; the
  camera keeps its module. Leaving the settings (SettingsActivity.onPause while finishing) and the camera's onResume bring
  the camera module's profile back (ModuleRegistry.syncProfile; the edit stays in the edited module), not while an import /
  reset restart is pending (ScameraApp.restarting). Fresh start: CameraActivity.onCreate without a saved state, once per
  process (ModuleRegistry.openHomeOnce), selects the shown back module nearest 1x (a plain one before a sensor-mode or
  cropped one at the same zoom; ModuleChoice.home) and its Camera ID; a screen the system restores keeps its module.
  Tests: ModuleChoiceTest.freshStartHomeIsTheShownBackModuleNearestOneX, SettingsMenuTest.moduleChipEditsAnotherModuleAndLeaving
  ReturnsToTheCameraModule / aFreshStartOpensOnTheBackOneX. Not seen on a phone.

### P157 - the bottom bar of the viewfinder: the same proportions at every display scale, restyled in the camera's style (owner, 2026-10-16, two screenshots - an empty band above the zoom strip on one phone, not on the other: «Исправь масштабирование нижних кнопок в видоискателе, чтобы при разных масштабах было примерно одинаково, так же переделай стиль всех кнопкок в нижней части видоискателя под стиль нашей камеры»)
- Later the same day: «По поводу стиля всех кнопок на нижней части, переделай их полностью под наш стиль», «Сделай эти иконки
  ближе к monet стилю, но чтобы узнавался всё ещё наш стиль» (a screenshot of the strip and the shutter row), «Мне не нравится
  стиль кнопок переключения модулей камер, переделай».
- Found: every size of the bar was fixed dp, the bar's height is what the 3:4 preview leaves (a share of the width): at a
  small display size (more dp: the X300 Ultra at 434dp) the controls shrank and left an empty band; at 361dp (the X200
  Ultra at a large display size) they filled it.
- DONE 2026-10-16:
  - Scale: BottomChrome.scale = screen width / 360dp (0.85..1.4): the shutter row, the lens strip and the zoom ruler card
    take the same share of the screen at every display size (the shutter a fifth of the width; X300 Ultra x1.21).
    BottomChrome.scaleBar scales the bar's views once (sizes, margins, paddings, text) before attach; LensButton,
    AuxButtonsLayout, ZoomDialView and the shutter ring scale their own drawing.
  - Style (Material You's tonal manner on the camera's colours): the strip, the gallery card and the front / back switch
    filled with the accent's tone over the card colour (a faint tonal outline instead of the grey line), 20dp corners;
    the lenses are tonal discs with the short number («0,6», «2», «2,8»), the active lens a larger accent capsule with its
    full label in bold («1×», the live zoom), no dots between them; the shutter an accent ring round the light disc
    (pressed: the disc in the accent); the front / back glyph regenerated in the P136 filled style (vf_flip_camera in
    tools/make_ui_icons.py), fitted to its button.
  - Tests: ViewfinderScaleLayoutTest (the bar's places x the scale), BottomBarStyleTest (the owner's two widths: the
    shutter a fifth of the width, the tonal fills, the ring; renders build/reports/viewfinder/bottom-style-434 / -361.png),
    LensButtonTest (discs and capsule), BottomChromeTest, ViewfinderUiTest and AuxButtonsOverflowTest follow the new sizes.
    Not seen on a phone.

### P158 - processing progress around the gallery thumbnail, as in ArkCam (owner, 2026-10-16: «сделай процесс обработки фото как в ArkCam, прогресс постепенно должен идти вокруг иконки галереи»)
- DONE 2026-10-16: ui/camera/views/ProcessingRing replaces the indeterminate spinner over the gallery card: a line in the
  accent runs clockwise round the card's edge from the top centre over a faint track. It moves on by time (an ease-out over
  the last shot's processing time, 3.5 s before the first; at most 0.94 of the ring) and never lags the pipeline's
  milestones (ScamProcessor -> onProcessingChanged: merged 0.55, processed 0.85, saved 0.97), closes the ring when the shot
  is done and fades; a shot queued behind starts it over. ProcessingRingTest (in CI). Not seen on a phone.

### P159 - highlight artifacts on a bright LED screen (owner, 2026-10-16, screen.zip: SCA_20261016_121257.jpg + log, X300 Ultra, a concert hall with an LED screen at the top: «посмотри на артефакты светов на экране сверху и придумай как исправлять такое»)
- Seen: on the screen the orange cones are hollow - a saturated orange outline around a pale / white inside; a light blue outline
  around a white cone (bottom left), coloured fringes on white shirts; the whole screen washed towards white.
- Log (12:12:57): X300 Ultra main, 10 bit (DCG off), N 10.4 ms x ISO 1422 + 3 L + 3 ES (x0.125); Bento applied, 5.8 % of the
  cells clipped in N, the ES frames not clipped (usClipped 0); clip flags: Bento 1.06 M px, border 0.83 M px; highlight
  recovery per channel max R 1.82 / G 4.55 / B 3.68, clipWhite 2.20, nearClip; ARK clip 4.0, gmax 10.8, headroom on,
  hlWhite 0.15, hlSoft 0.7 (P154 v2 in this build).
- Working hypothesis: the screen is far brighter than the scene, so its colours reach the tone's shoulder: the path to white
  (hlWhite) and the AgX shoulder (and the P154 landing on its own colour / white) desaturate PER PIXEL by brightness - a cone's
  bright inside goes pale, its darker blurred edge (mixed with the background) keeps the full chroma: an outline. Second
  candidate: the Bento region (inside, from the ES frames) and the rim / normal frames (edge, per-channel recovery with the
  rim's real-site ratios) disagree in colour. R max 1.82 against G 4.55 inside an orange-rich area also asks whether R is
  limited somewhere (white balance gain x clip, the per-channel recovery's ceiling).
- Next: get the shot's stages (Diagnostics ZIP or the merged DNG) or replay the burst; split by switches (hl_white 0, hl_soft 0,
  Bento off, highlight recovery off) and measure the chroma across one cone (inside vs edge) at each stage: merge output,
  after recovery, after ARK. Fix ideas, chosen by what the split shows: desaturation driven by a blurred brightness (as the
  P154 mask) so an object's edge and inside go together; a hue- and chroma-keeping shoulder for saturated light (OKLab chroma
  scaled with the luminance compression instead of mixing to white); the Bento / rim colour taken from the same ratio source.
- Measured in the JPEG (a cone of the screen): outside 252 grey (OKLab L 0.99), the rim (253, 216, 217) L 0.93, the inside
  (255, 243, 243) L 0.97: the rim is DARKER than both sides and more saturated (C/L 0.04 vs 0.016) - an undershoot ring, and
  the shoulder bleaches the brighter inside more than the darker rim. On the host (ark/combine.glsl, a profile «screen ground
  -> orange cone -> ground» above the clip, as with Bento) the tone alone bleaches the cone to (255, 230, 208) and the
  P154 landing made it paler still (it landed on the toned, bleached colour).
- DONE 2026-10-16 (ark/combine.glsl, both under HL_LANDING: only with real clipped data and «Мягкость засветов» on):
  (1) the landing takes a coloured light to ITS scene colour at full brightness (the linear colour before the tone,
  display-encoded; near-neutral below ~0.1 linear saturation still to pure white): the host cone comes out orange
  (255, 197, 149) inside, its chroma highest inside, no outline; the P154 owner request «восстановление цвета в областях
  засветов» now holds for blown colour as well. check_highlight_landing: the orange at the ceiling = its scene colour, never
  more bleached than the tone left it; nothing without clipped data. (A second rule of 214e63e3 - no negative detail on
  near-white pixels - was taken out again: in ARK a pixel's base is the reduced colour source, so thin dark text on a white
  card exists only as that negative detail and would have gone.)
- Second example, with stages (owner, 2026-10-16, «screen artifacts.zip»: SCA_20261016_122610 + its Diagnostics ZIP + the
  stock camera's auto JPEG and its SRAW developed): the slide «Единый день профилактики» - dark blue text on a white card -
  came out as pale blue hollow letters with dark outlines, the small white text below unreadable; the stock SRAW develop
  shows solid dark blue letters. Stages (white card of the screen, 1020x766 thumbnails): letters / background Y 0.21 in
  after-ivst, ScamRgb and ArkLow (the data is right: letters Y ~1.1, background ~5.2), 0.89 after ArkCombine. Cause: the
  ARK statistics (smart_hdr, ported from ArkCam's native code) cap the data ceiling at 4.0, while the Bento frames (x1/8)
  give data up to ~16: the screen (7.2 % of the frame) all lay at the ceiling (log: p98 = p995 = 3.999, gmax 16.5), the
  highlight peak was read as ae x 4 = 2.96, the fusion's highlight strength came out effH 1.26 (its setting allows 2): the
  low layer pulled the screen only to x0.27 and background and letters both stayed in the tone's shoulder (letters 0.88).
- DONE 2026-10-16 (second part): ArkAe counts the max channel above the ceiling in 48 log2 bins (12 an octave, up to 16x;
  CPU stats and the compute shader ark/aestats, binding 5), p98x / p995x read it, and only the highlight strength
  (realPeak -> effH) uses them; the exposure, the ceiling and the histograms stay the native ones. This shot: realPeak
  2.96 -> 12.2, effH 1.26 -> 2.0, the screen's low layer x0.27 -> x0.125 (background ~0.93, letters ~0.2 in it). SMART_HDR_STAT
  logs p98x / p995x. ArkAeTest: theHighlightStrengthReadsTheBrightAreaBeyondTheCeiling (same exposure, effH near its
  setting, nothing without data above the ceiling), the reference case «bento» now effH 2 (was 1.314: the 7x corner at the
  ceiling), the GPU path with its tail; tools/check_ark_ae_gpu.py compares the tail (2 boundary pixels of 6856 may move).
  Not seen on a phone: the same scene again; the log should show p98x above 4 and effH near 2.

### P160 - the gallery thumbnail slid over the card's right edge (owner, 2026-10-16, screenshot of 30866: «Исправь галерею»)
- Seen: the last shot's picture inside the gallery card had a wide margin on the left and none on the right: it covered the
  card's right outline and its rounded right corners were cut by the card's edge.
- Cause: BottomChrome.scaleTree (P157) scaled the left / right margins, then set start = getMarginStart() x f and end =
  getMarginEnd() x f. The thumbnail has a plain layout_margin (no start / end): getMarginStart returned the already scaled
  left margin (scaled twice), and setMarginStart marked the margins for resolution, so getMarginEnd resolved the unset end
  to 0. The 3dp margin became 3dp x f^2 on the left and 0 on the right; centred in the card, the picture moved right by
  1.5 x that and the card clipped it. The P157 renders had no thumbnail (the placeholder), so it did not show there.
- DONE 2026-10-16: start / end are read before any change and set only when the view has relative margins.
  BottomBarStyleTest puts a thumbnail in the card and checks the same 3dp x scale margin on all four sides (failed on the
  old code: 18 px on the left against 9.7 at 434dp).

### P161 - the lens strip in our style, not Google Camera's (owner, 2026-10-16: «у нас получился стиль один в один гугл
камера, такое нам не подходит, сделай стиль более подходящий нашему интерфейсу», then «Сделай стиль что-то между material you
и нашим»)
- The P157 strip (tonal discs with the short number, the active lens a larger accent capsule «1×», a pill strip) was Google
  Camera's look.
- DONE 2026-10-16: Material You's tonal colours of the camera accent on our card shapes. Every lens a rounded tile 38dp
  high (42dp least width, wider than tall), corners 14dp - the gallery card's 20 of 52dp in proportion - with a tonal fill
  (accent 12 % over CARD) and a faint tonal outline (26 %): our CARD + LINE. The active lens a tile of the SAME height (at
  least 56dp wide) filled with the accent, its full label in bold INK, as an active shade tile: no size jump, no circles.
  The strip a tonal card with 18dp corners, concentric with the tiles (14 + its 4dp padding), not a pill. A press ripples
  in the accent. LensButton HEIGHT_DP / WIDTH_DP / SELECTED_WIDTH_DP / CORNER_DP, BottomChrome.STRIP_RADIUS_DP; tests:
  LensButtonTest.aTileShowsTheShortNumberAndTheActiveTileTheFullLabel, BottomBarStyleTest (strip corners, tiles of one
  height, wider than tall), sizes in ViewfinderScaleLayoutTest / ViewfinderUiTest / BottomChromeTest.

### P162 - automatic crop of the RAW's black stripes, own margins, the crop picture (owner, 2026-10-16: a photo of the X200
Pro Mini with black stripes on its left and bottom: «сделай автоматическую обрезку для него и для X200 Pro. Также добавь
соответвующие настройки», then «Сделай эту группу настроек с наглядной красивой визуализацией как кадр будет обрезан, с
красивыми анимациями и видом»)
- Measured on the owner's JPEG (1500x2000): 35 px on the left, 46 at the bottom = 72 / 96 of the 3072x4096 frame: the known
  X200 Pro RAW (4096x3072, the image in 4000x3000, exact zeros on the sensor's right 96 columns and bottom 72 rows; P114 only
  kept those zeros out of the black floor). The diagnostics line «arrays: pixel= active=» (CaptureController) has not been read
  from a phone yet; the crop does not need it.
- DONE 2026-10-16: processing/RawCrop: before anything reads the frames (ScamProcessor.cropFrames, ahead of RawBin) the shot's
  frames are cut to the kept area: the zero padding (whole rows / columns of exact zeros at the edges, at most 1/8 of a side,
  only with a black pedestal of 16 DN or more) when «Убирать чёрные полосы» is on (default), and the own margins of the upright
  photo (pref_raw_crop_left/top/right/bottom, 0..512 sensor px) turned to the sensor's sides by SENSOR_ORIENTATION; each side
  the larger. The origin rounds inwards to 8 px (Bayer / Quad / Tetra keep their colour order), the sides are even, less than
  half of a side never stays. The kept rows go into one new shot arena (the merge reads it without a copy; the ring slots are
  given back), else plain buffers. Parameters.rawCrop / rawCropFull: the lens shading map is resampled over the kept area (after
  the 16:9 rows of P118) and the hot pixels move with it. The shot's detection is stored per module (pref_raw_crop_detected).
- Settings: Камера -> Съёмка -> «Обрезка кадра» (raw_crop_screen): RawCropPreference / RawCropView on top - the upright frame as
  a small picture, the black stripes hatched (at least 10dp thick so 72 of 3072 px shows), the own margins in the accent's tint,
  the kept frame with its corners, each side's px, «3072 x 4096 -> 3000 x 4000 · -4,6 %» and what the automatic crop found;
  every change glides (420 ms, emphasized deceleration, an intro from the whole frame), the kept frame's edges drag with a
  finger and store that side's margin through its slider (UniversalSeekBarPreference.setNumber). Then the switch and the four
  sliders.
- Tests: RawCropTest (the X200 Pro padding, no pedestal / all zeros / too wide, the turns, the rounding, a shot's frames cut with
  their samples, the shading map and hot pixels, the detection text), RawCropSettingsTest (the page, the X200 Pro state, a margin,
  a dragged edge stored, the switch; renders build/reports/module-concept/page-raw_crop_screen*.png, sent to the owner as
  deliverables/p157/p162-crop-screen.png). Not seen on a phone: a shot on the X200 Pro / Pro Mini should log «RAW crop
  4096x3072 -> 4000x3000 at 0,0» and give a photo without the stripes.

### P163 - highlights on the LED screen still wrong after P159 (owner, 2026-10-16 15:06: «Изучи света, всё еще не исправил»; DCG part and LED specks done)
- Input: Telegram.zip (kept in SCAMERA-PC/evidence/p163/Telegram-2026-10-16-1506.zip): SCA_20261016_132656.jpg and
  SCA_20261016_132710.jpg with their Diagnostics ZIPs (SCAM-20261016-132656-989-6fd25a5e.zip, SCAM-20261016-132710-365-7f0060eb.zip),
  log-2026-10-16.txt, SCAMERA-debug.log.txt. X300 Ultra (PD2547), camera 5 (tele, CROPPED_RAW), a build with P159 (the log
  shows p98x).
- Seen: the same concert hall, the blue LED screen with white text: the letters come out bleached white with a blue halo
  round them, the screen's blue washed, the white curtain blown. Log: ARK clip 4, gmax 11.8 / 16.9 / 21.0, p98x 7.1 / 10.1 /
  6.0, p995x 9.0 / 12.7 / 13.5, effH 2.0 (the P159 tail works, at the setting's cap), hlWhite 0.15, hlSoft 0.7, Bento k ~8,
  highlight recovery max R 4.2-5.5 / G 5.3-8.0 / B 6.0-8.0, clipWhite 15.7-17.5.
- Next: the stages of the Diagnostics ZIPs across the screen (letters / background at merge, after recovery, ScamRgb, ArkLow,
  ArkCombine) as in P159; whether effH capped at 2 is still too weak for a screen 3-5x above the ceiling (the low layer
  x0.125), whether the halo is the detail delta or the hl mask, and the blue's chroma at each stage.
- Second input (owner, 2026-10-16 15:20 «Вот ещё с 300у», then «Это фото с DCG, узнай почему с ним не работают ультракороткие»
  and its Diagnostics ZIP; kept in SCAMERA-PC/evidence/p163/Desktop-2026-10-16-1520.zip and dcg-144900/): SCA_20261016_144900,
  X300 Ultra main (camera 3) with «DCG 14 бит» (session vivo.control.forceSensorMode 17), a window: the sky clipped, the clouds
  with pale blue patches.
- Cause of the missing ultrashorts: the DCG mode gives no gain under ~ISO 1336. The AE sat at 10 ms x ISO 1339 (the plain mode
  had 10 ms x ISO 179 for the same scene seconds before); the plan's ultrashorts «gain first» asked 10 ms x ISO 167 and came
  back at ISO 1336 (x0.998 of N), so they were classified and merged as N (frames.txt: 16 NORMAL + 3 LONG), Bento got 0 frames,
  1.24 M px of the window stayed clipped at the DCG clip (measured 8696 of 14 bit) and the per-channel recovery alone (R max
  0.62 after WB) painted the clouds cyan. The metadata is true: the LONG frames (ISO 1600 asked, 3200 given) measured x4.03
  against x4.009 by metadata. The shutter is honoured to the nanosecond.
- DONE 2026-10-16 (HybridPlan.build): in a DCG session (Dcg14Mode.active) the ultrashort keeps the N gain and takes its x1/8
  from the shutter (10 ms x ISO 1339 -> 1.25 ms x ISO 1339); the plan's description says «DCG: ultrashort by the shutter».
  Outside DCG the gain still goes first. HybridPlanTest.aDcgSessionTakesTheUltrashortFromTheShutter. Not seen on the phone.
- LED screen (first input), measured on the Diagnostics stages (13:26:56 tele, 13:27:10 main): the white letters are clipped
  in N, the Bento merge gives them (after WB) R/G 1.04, B/G 1.31 - a lavender LED white, kept as such by the P159 landing
  (saturation 0.24, above its 0.08-0.30 white ramp). Defect: white specks in the letters (3 % of their pixels, the brightest
  diodes clipped even in the ultrashort frames, neutral after the highlight recovery: saturation 0.05 against 0.25 around
  them) landed on white amid lavender neighbours - the P159 landing took its colour per pixel.
- DONE 2026-10-16 (ark/combine.glsl, HL_LANDING only): landingColour - the light's colour from the colour source at the pixel
  (a quarter weight) and on a ring of eight taps ~12 output px around it, each weighted by how near the ceiling it is (a dark
  or saturated ground beside the light does not tint it). check_highlight_landing: each tint now a band of its own (the ramp
  along x) and a new LED case (a lavender letter at the ceiling on a blue ground with neutral 3x3 dots): the dots differ from
  the letter by 0.020 (0.096 with the former shader: white specks), the letter keeps its tint; orange still lands on its own
  colour, every other case as before; glslc 33 variants, check_colour_output / look / ark_highlights / tone_ops / film_lut /
  viewfinder_look PASS. Not seen on a phone.
- Seen, not changed: the magenta rim of the podium and the red ribbon is the defocused blend of the blue screen with the
  orange podium (camera RGB 0.62 / 0.62 / 0.82, not clipped, magenta after the warm WB), no landing or recovery in it.
- Still open in P163: in DCG the LONG frames come x4 instead of x2 (ISO 1600 -> 3200; merged by their measured ratio,
  harmless); ExposureLimits is keyed by camera and RAW size only, so DCG and plain share it.



### P165 - the denoise switch and the RAW: no denoise in the DNG, the ultrashort and bracketed frames in it (owner, 2026-10-16:
«Перепроверь точно ли этот переключатель отключает все шумоподавление», «перепроверь чтобы у нас в Рав файлах не было никакого
шумодава кроме склейки и также чтобы ультракороткие и длинные кадры были частью склейки у рав файлов», «Шумодава в раве просто
не должно быть, вне зависимости от того что выставлено в этом переключателе»)
- Checked: «Шумоподавление» (pref_scam_hybrid_denoise) gated every denoise of the photo - ScamHdrDenoise (the GCam-style finish
  and its despeckle, the NLM route) and the ARK tone's bracket_denoise / chroma_denoise (ArkFusion, ArkCombine) - but only on a
  shot processed under the hybrid's profile (isHybridShot); every shot is one since P129, the gate now does not ask.
  ArkLumaSharpen only sharpens (its USM noise threshold limits the boost, smooths nothing); ScamRgb's highlight recovery and
  the ARK colour from the half-size ArkLow are no denoise. Inside the merge (the worker) there is no denoise pass: the Sabre
  kernel's widening at low SNR, the chroma difference where few frames are accepted and the Bento chroma sigma are the merge.
- The DNG is the worker's merged Bayer (when saved: saveRAW >= 1), written straight from the merged RGB after the merge and
  the RAW CA shift: no denoise at all, whatever the switch. The N, the bracketed (Shasta) and the ultrashort (Bento) frames are
  all in that merge, but the 14-bit DNG clamped it at the N frames' white: what Bento took from the ultrashort frames (merged
  RGB up to 3-10x N's white, the owner's 13:27 shots) was cut off.
- DONE 2026-10-16: the merged DNG is 16 bit over its headroom H (the merge's highest level, 1 when nothing is above the N clip,
  at most 16): hybridWriteMergedDng in all three merge paths, «HYBRID DNG: ... headroom=H ..., no denoise»; ScamProcessor
  writes white 65535 and BaselineExposure log2(H) (DngCreator.setBaselineExposure -> dngCreator -> tinydngwriter tag 50730,
  SRATIONAL), so a raw converter shows the DNG as bright as before with the highlights kept. Without Bento the DNG is as
  before, at 16 bit. ScamHdrDenoise / ArkFusion: the master switch alone. ScamNoiseFactorReportTest.mergedDngHeadroomIsRead;
  the worker builds (NDK), dngCreator compiles. Not seen on a phone: a Bento shot's DNG should open in a raw converter with
  the window's highlights, the log says «DNG: merged burst RAW (N, bracketed and ultrashort frames), 16-bit, headroom x...».

### P166 - smaller split focus ring and exposure square (owner, 2026-10-16: «Уменьши область для раздельных квадрата экспозиции
и кружка фокусировки, квадрат сделай на 35% меньше, кружок фокусировки на 15% меньше»)
- DONE 2026-10-16 (TouchFocus): in the split mode (P142) the ring is 0.85 of its own size (its layout, so the pulse / hide
  scale animations keep working) and the square 0.65 of the former 1.7 x ring; their metering regions shrink the same way
  around the same points (AF x0.85, AE x0.65: mapTapToCrop with a size scale). A plain tap keeps the ring and region as they
  were. TouchFocusSplitTest: the regions and sizes.

### P167 - no limit of 12 tiles in the shade (owner, 2026-10-16: «убери лимит на 12 плиток в шторке и дай возможность
закреплять столько сколько я хочу»)
- DONE 2026-10-16: ShadeCatalog.MAX_TILES is gone; ShadeTiles keeps every known key, toggle always pins, «Добавить» is always
  there, the catalog counter says «В шторке N» (no «из 12»), the «шторка заполнена» message is gone. The tile grid scrolls
  with the shade's list. Tests: ShadeTilesTest, ShadeUiTest (13 tiles), SettingsMenuTest (31 pinned).

### P168 - the value pill of a changed setting in the theme's opposite (owner, 2026-10-16: «Если параметр отличается от
стандартного, то пускай пилюля со значением меняет цвет на противоположный от используемой темы»)
- DONE 2026-10-16: SettingsStyle.paintPill - off the default the seekbar's value pill is filled with the theme's light text
  colour and written in its dark background colour; at the default the accent's tint as before. Live while the bar moves
  (UniversalSeekBarPreference.updateLabel, isChanged = 1e-5 off the XML default) and for the tunables (a stored value of
  their own; was green text).

### P169 - the own tone curve in the shade (owner, 2026-10-16: «дай возможность добавлять кастомную кривую в шторку чтобы там
её регулировать»; done)
- The curve (pref_scam_hybrid_ark_curve_points, ToneCurvePreference / ToneCurveView) as a pinnable shade entry whose tile
  opens the curve editor in the shade's card (as the slider card), the switch «Своя кривая вместо AgX» next to it.
- DONE 2026-10-16: ShadeCatalog kind CURVE (the points as text, default the S); its tile says «5 точек», its row in the
  full list and the tile's card hold the same graph (ToneCurveView: drag, add, hold to delete), the chips «S-кривая» /
  «Прямая» / «Ещё…» (the templates of P177) and «Сравнить» (P177); a change is stored at once and reaches the settings page
  and the viewfinder as any shade value. Tests: ShadeUiTest ownCurveTileOpensItsGraphInTheCard (render
  deliverables/p157/p169-shade-curve.png), ShadeCatalogTest.

### P170 - imported LUTs in the shade, five own film LUTs (owner, 2026-10-16: «дай возможность выбора импортированных лутов из
списка в шторке. Так же добавь 5 своих лутов. Сделай их сам в плёночном стиле»; done)
- A shade entry listing the imported .cube LUTs (and the built-in ones) to pick from; five own film-style 3D LUTs made here
  (generated .cube files bundled in the APK, listed with the imported ones).
- DONE 2026-10-16: FilmLooks - five looks built in code as 33-point 3D LUTs of the display output (no files in the APK):
  «Тёплая 400» (portrait negative: soft S, lifted warm blacks, olive greens), «Прохладная 160» (pastel, cyan-green
  shadows), «Слайд 100» (reversal: deep blacks, contrast, dense blues), «Кино 500» (print: teal shadows, warm highlights),
  «Ч/Б 400» (panchromatic mix, firm S); a film S per channel, luma-keeping split toning, hue bands, saturation. Stored as
  «scam:<id>» in pref_film_lut (no .cube has that name); FilmLut.chosen builds them, so the photo (ArkCombine) and the
  viewfinder (LiveRawRenderer) take them as any LUT, the strength slider applies. The settings row lists them after «Нет»,
  before the folder's files. The shade: pref_film_lut is a pinnable list (ShadeCatalog LIST, the list sheet; its values
  read again from Download/SCAMERA/LUT whenever it opens), search «лут» / «плёнка». FilmLooksTest (new, in the CI list:
  range, grey ramp monotone, smooth nodes, each look's character; renders deliverables/p157/p170-film-looks-*.png),
  ShadeCatalogTest filmLutListsOurLooksThenTheFolder, SettingsMenuTest.

### P171 - a rounder outline round the lens strip (owner, 2026-10-16: «Сделай обводку вокруг модулей камер более
закругленную»)
- DONE 2026-10-16: BottomChrome.STRIP_RADIUS_DP 18 -> 22 (of the strip's 46dp height), the drawable too; the tiles inside
  unchanged. Render sent to the owner: deliverables/p157/p171-lens-strip-434dp-361dp.png.

### P172 - the value editor's step chips step the value (owner, 2026-10-16, «Кадров в буфере ZSL» editor with «Шаг 1 / 10 /
100»: «Сделай так чтобы при выборе шага с таким шагом параметр и менялся»)
- The ruler (PrecisionRuler) moved the value continuously: fractions of a step per pixel, a whole-number setting rounded them,
  so «Шаг 10» still gave 51, 52...
- DONE 2026-10-16: a drag or a fling moves the value by whole steps, one 16dp tick a step, from where it is (53 with «Шаг
  10»: 63, 73); the part of a tick not yet passed only slides the scale, which settles on the tick when the finger lifts or
  the fling ends (and on a new touch). PrecisionRulerTest (new, in the CI list).

### P173 - the shade at 16:9 (owner, 2026-10-16: «Исправь отображение шторки при 16:9»; done with P176)
- Look at the quick-settings shade with the 16:9 preview (its height / anchoring against the taller preview and the bottom
  bar) and fix what is off.
- DONE 2026-10-16 (P176): at 16:9 the HIDDEN handle was the sheet's full-width dark top edge lying across the preview; it
  is a pill in the middle above the lens strip now.

### P174 - the horizon level in our style, a colour and a light haptic at 0 degrees (owner, 2026-10-16: «поменяй стиль уровня
горизонта на схожий с нашим общим стилем камеры. Сделай так чтобы при 0° он менял цвет и давай слабый виброотклик»)
- DONE 2026-10-16 (HorizonIndicatorView): a thin white line with round ends and a gap in its middle turning with the phone,
  two fixed marks at its ends, a small ring in the centre, the tilt in degrees above it, soft shadows (was yellow / magenta
  pitch lines and a cross); level (within 0.5 degrees of the frame's horizontal in any orientation, leaving at 1 degree)
  everything turns the camera accent, the line closes, and a light haptic tick (CLOCK_TICK, system haptics setting).
  HorizonIndicatorViewTest (new, in the CI list): the tilt per orientation, the hysteresis and the tick, renders sent to the
  owner (deliverables/p157/p174-horizon-tilted-level.png).

### P175 - remove the neural remosaic (owner, 2026-10-16: «Вырежи нейроремозаик который работает только на 200у и 8 элит из
нашей камеры»; done)
- DONE 2026-10-17 (7d8b986b, cherry-picked from the agent's worktree onto the 200 MP memory work; the one conflict, the
  shrinking read-back of ScamNeuralClient, kept with the Hybrid-only allocation): the setting, its page, ScamNeuralActivity,
  the HexQuad / Quad / tele576 / check jobs and their assets, RemosaicCore / TetraResponseProfile and the remosaic shaders,
  the worker's other modes; the private bundle unchanged (BUNDLE_ONLY). 995 CI-list tests; OPPO replays 8/8 bit-identical to
  origin/main's worker.
- Blocked: its call (ScamMosaic.prepareForShdr) is in ScamProcessor, which ChatGPT has uncommitted edits in (2026-10-16
  evening; owner: «Если файл используется ChatGPT, то мы это не делаем»). When that file is free: the «Нейроремозаик (NPU)»
  setting (isShdrNeuralRemosaic, its XML / strings / availability / numeric rules / migration P129), ScamMosaic.prepareForShdr
  and its call, the NPU remosaic graphs it loads (the QUAD_FILES group of the bundle, if nothing else uses them), the tests.

### P176 - at 16:9 the shade comes out of the pill like a macOS app opening (owner, 2026-10-16: «Шторка пускай вылезает из
пилюли с такой же анимацией как открытие приложений в MacOS»; «По поводу анимации шторки я написал только про 16:9»; «Пилюля
должна быть по центру»; done)
- Part of P173: with the 16:9 preview the sheet grows out of its pill (the handle) - scale and corner radius from the pill's
  bounds to the sheet's, a quick ease-out as the macOS genie / zoom - and goes back into it; the other aspect ratios keep
  today's slide.
- DONE 2026-10-16: BottomChrome tells the sheet when the HIDDEN handle floats over the preview (the preview goes on below
  the handle's top: 16:9, video): the handle is then a 76dp pill (the sheet colour, a thin line, its grip), centred above the
  lens strip. A request from HIDDEN puts the sheet in place at once (the behavior has nothing to slide; after a pending
  layout, on the next pre-draw) and ShadeZoom grows it out of the pill: one uniform scale from the pill's width, its centre
  from the pill's to its own, quickly visible, 420 ms PathInterpolator(0.16, 1, 0.3, 1); a request to HIDDEN shrinks it
  back into the pill (300 ms, the FULL scrim fading with it) and then hides it; the container does not clip it meanwhile.
  A finger still drags the sheet as before; 3:4 keeps the full-width edge and the slide. Tests: ShadeZoomTest (new, frames),
  ShadeUiTest (pill, zoom both ways, the slide without it), BottomChromeTest handleFloats, Shade169Test (new: the real camera
  screen at 16:9, the pill centred above the strip, render deliverables/p157/p176-shade-169-pill.png).

### P177 - AgX / ACES / Uchimura on the own curve's graph, and loaded as own curves (owner, 2026-10-16: «для кастомных кривых
добавь возможность визуализации кривых AGX/ACES и тд для сравнения чтобы можно было на графике по ним ориентироваться. Ну и
дай возможность их подгружать чтобы потом редактировать»; done)
- DONE 2026-10-16: the own curve's domain (x = 0.5 + EV / 10 around the 0.18 grey, y = display value) gets each operator
  with today's settings (ArkTone.reference: AgX through ToneCurve.agxAt = combine.glsl agxTonescale over its log range,
  ACES / Uchimura linear output to the power 1 / 2.4). ToneCurveCompare: the chips «Сравнить: AgX / ACES / Uchimura» draw
  each one dashed in its colour with its name (remembered for both editors, a per-viewer UI file, not a setting); the
  templates (S-кривая, Прямая, AgX, ACES, Uchimura) - «Шаблоны…» at the top of the settings page's load sheet and «Ещё…» in
  the shade - load one as an own curve of nine points following it (never falling), to edit and save as any curve.
  ToneCurveTest operatorsInTheOwnCurvesDomain, ToneCurveCompareTest (new, in the CI list; render
  deliverables/p157/p177-curve-compare.png).

### P178 - undo and redo while editing the curve (owner, 2026-10-16: «Добавь возможность делать шаг действий назад и вперед
при редактировании кривой»)
- Each finished edit of the own curve (a drag let go, a point added or deleted, a typed value, a template, S-curve /
  straight) is a step; «Отменить» / «Повторить» buttons next to the graph walk the steps back and forth, both on the
  settings page and in the shade; the curve shown is stored as any edit.
- DONE 2026-10-16: ToneCurveView keeps the steps (at most 100 serialized curves): a gesture, the editor's stored edits
  (a slider let go, a typed value, a point deleted), the shortcuts / templates / a loaded file (edit) and a change made
  elsewhere (the shade's sync) each add one; a step back then a new edit drops the steps after it; the curve the editor
  first shows (the stored one) is where they start. ↶ / ↷ (Material undo / redo icons) in a small pill over the graph's top
  left corner (the corner a tone curve never reaches), dimmed while there is no step that way or the curve is off; a step
  is stored through onCommit like any edit, with a light tick. Settings page, the shade's card and its full-list row.
  ToneCurveHistoryTest (new, in the CI list), SettingsMenuTest (↶ ↶ ↷ ↷ on the page), ShadeUiTest (↶ ↷ in the card);
  renders deliverables/p157/p178-*.png.

### P179 - finish the ISZ / FULLRES integration on the X200 Ultra (owner, 2026-10-16, a task brief; DCG postponed)
- Read first: AGENTS.md, the scamera skill, HANDOFF.md, this plan, then research/handoff/FULLRES-NATIVE-READY-20261016.md
  and research/handoff/NATIVE-ISZ-READY-20261016.md (newer RAW / photos outrank older negative notes). Start from git
  status / diff / log; no reset / stash / clean, no archived patches over changed sources, kHybMergeMain1 untouched, other
  agents' TouchFocus / settings / workflow edits kept.
- Confirmed on PD2454 (SM8750, adb 10AFAQ1QE60071E): (1) a real separate android.camera.cts build
  (-PscameraCtsNativeRaw=true, UID 10469, AppType 21; org.codeaurora.snapcam 30820 / UID 10349 kept) - not an
  AttributionSource under the same UID; (2) native ISZ without HW remosaic: one RAW_SENSOR usage 3, int[] forceSensorMode
  in the session AND every request; MAIN3 / mode 3 and UW4 / mode 3 Quad 2x, TELE5 / mode 5 Quad 2x, mode 7 Tetra 4x - all
  four gave real UI / RAW preview / SHDR / JPEG at 4:3 in 30827 (RAW / CFA / FOV evidence kept); no native 4x on main / UW
  (a digital crop is not ISZ 4x); (3) native FULLRES: SDK40 = 1 held through configure / capture, SDK33 / 40 restored after
  CameraDevice.onClosed; one RAW_SENSOR usage 0 / maxImages 4, no PRIVATE / YUV / JPEG, no ProUI16 / streamsUsage; Main3 /
  mode 4 and UW4 / mode 4 Quad 8192x6144, Tele5 / mode 1 Quad 8160x6144, Tele5 / mode 0 Tetra 16320x12288 - real non-zero
  RAW, metadata timestamps, SHA, CFA 25/25 tiles; (4) main / UW 50 MP save SHDR JPEG 8192x6144; FULLRES AE on all three
  rear cameras checked EV 0 -> +0.8 -> 0 (AE_ON, lock false, CONVERGED, the exposure follows and comes back).
- Left, in order:
  - Tele 200 MP: 30833 took 4 different RAW (20 requested, a real memory guard), chose 2N, finished the GPU merge
    16320x12288, then LOW_MEMORY (reason 3) killed the app on the full-size chroma / moire planes - no 200 MP JPEG.
    mosaicChromaMedian now runs in 256-row bands with the original halo 8*block (native Quad / Tetra / 2x tests, seams /
    edges / osc pass, max abs delta 7.15e-7, green unchanged) - a candidate after 30833, not yet a full 200 MP
    app / PostPipeline / JPEG test: build a fresh worker from source, check the whole path, fix the next real limit by the
    log.
  - The old FP32-only limit is fixed in RawBin / SHDR and MappedHybridBurst (the real FP16 / FP32 format and output grid;
    the real GPU / 2 GiB limits kept).
  - SDK lease: after an LMK the finally does not run and SDK40 stays 1 (set back to 0 by hand through the public API from
    a temporary app, UID 10471, then removed; a passive check later showed mUseVivocamera = 0). Needed: a reliable journal
    of our SDK lease and recovery after our own crash / LMK, never resetting another client's global SDK; not the
    app_process / run-as way (the CTS APK is not debuggable).
  - Prove explicit FULLRES + Sabre 2x on native 50 MP: a real merge / JPEG 16384x12288 on main / UW (the Sabre 2x 8192x6144
    line on a proxy native mosaic does not prove it). Tele 200 MP x2 exceeds the real GPU 16384 side - do not hide a
    smaller result.
  - P146 (FULLRES profiles / settings) by this plan; today's diagnostics are not the general integration for every vivo.
  - Two FULLRES buttons on the telephoto (owner, 2026-10-16: «сделай отдельную кнопку на телевике для 50мп и для 200мп. По
    иконке 200мп должно быть понятно что это выше разрешение»): «50» = Tele5 / mode 1 Quad 8160x6144, «200» = Tele5 /
    mode 0 Tetra 16320x12288 (today VivoSdkFullRes.nativeProfile gives the tele only the 200 MP profile); one of them or
    none on at a time, the choice read by the profile. The 200 MP icon must read as the higher resolution (a denser
    pixel grid / a bigger «200» badge than the 50 MP one, in our button style next to FullResButton). Main / UW keep the
    one 50 MP button.
  - Another agent may take the ready source / capture mechanisms: re-check all four ISZ, FULLRES on three cameras, 4:3
    output, AE and a correct lease return.
  - DCG not touched. No stock NICE / ZSL bracket / SCAM HDR.
  - X100 Ultra / X300 Ultra: no checked port of this FULLRES path - needs Camera2 / firmware reports, the HAL's configs and
    their own RAW probes; do not copy PD2454's sensor-mode indices, keep the device guard without proof.
- The local diagnostic 30833 (not a release, not Actions):
  deliverables/local-shdr-20261015/SCAMERA-0.98-30833-CTS-NATIVE-ISZ-LOCAL-DIAGNOSTIC.apk, SHA256
  c30984db256247759928100220e7c4149d9ff948751ced5996e4bb38afcf5a59, without the banded chroma. Paths from SCAMERA-PC;
  research / tools in evidence/vivo_hal_2026-10-13/, results in reports/pd2454-*. Builds only on a separate checked
  snapshot, never over the shared tree; tests not disabled or weakened; the phone only when free, taps only in SCAMERA, no
  other apps' data.
- Status 2026-10-16 evening: the owner allowed ChatGPT's files («Теперь ты можешь трогать файлы ChatGPT»); its tree was
  checked (973 CI-list tests) and committed (f87e3cc8); the SDK lease journal (2c213746) and the tele 50 / 200 MP buttons
  (430ba5b4) are done; owner: «Не забудь исправить вылет по памяти для 200мп» - the 200 MP memory boundary is next, on the
  phone.
- Status 2026-10-17 (verified on PD2454, CTS 30834-30840, reports/pd2454-3083x-*):
  - 200 MP saves its JPEG 16320x12240 (4:3): the worker's sparse output and the app's shrinking read-back (026e8d0f), frames
    queued back to back (3ad14b56), the merge alone - no pipeline denoise above 100 MP (90e32945), the preview stopped at the
    shutter (fdeda96a), a live viewfinder in the plain session during the merge and a sparse 200 MP viewfinder bin (449ea1ff).
  - Owner: «хотя бы 3 кадра и один из них был ультракороткий», «Сделай 4 кадра» (169f24e0): 3 N (AE) + 1 ultrashort (manual,
    the preview's exposure / bento_factor, same ISO, EXTRA_SHORT) whatever the frame setting; app and worker merge all four
    (30840: «0N 1N 2N 3U», merged=4, bento=1).
  - 30839 was killed for low memory in the ARK luma sharpening (its guided filter's full-grid RG32F stack, 4.4 GB with the
    image); above 100 MP it now runs on the 2x2 means (6f99c9f2): 30840 sharpening 678 ms (6.2 s before), MemAvailable min
    1.8 GB, worker peakRss 5286 MB, shutter -> saved 38.4 s (63 s at the start). Memory timeline:
    reports/pd2454-30840-memory-timeline-20261016.txt.
  - P146 is done by P181 (FULLRES 50 / 200 MP profiles of their own).
  - Left (phone needed): Sabre 2x on native 50 MP (main / UW 16384x12288); a re-check of the four ISZ and of FULLRES on the
    three cameras with the final build.

### P180 - FULLRES without hardware remosaic on the X100 Ultra and the X300 Ultra (owner, 2026-10-16: a task brief from
the other agent's notes, «Изучи и реализуй, учитывай что х300у есть для разных регионов»)
- Brief: research/handoff/PROMPT-FULLRES-X100U-X300U-20261016.txt and FULLRES-X100U-X300U-20261016.md, data in
  evidence/vivo_hal_2026-10-13/fullres-x100u-x300u-20261016/. Only FULLRES: real RAW, own remosaic + SHDR, AUTO AE, 4:3
  output; ISZ / DCG on these phones (forceSensorMode requests) stay as they are; nothing on the X200 Ultra changes.
- Facts: X100 Ultra PD2366 (SM8650): main 2 (profile 0 max 8192x6144), UW 3 8192x6144, tele 4 16320x12288; RGGB main,
  BGGR UW / tele. X300 Ultra PD2547 (SM8850; V2547DA satellite edition, other regions too): main 3 16320x12256, UW 4
  8192x6144, tele 5 16320x12288, all RGGB. Both selectors have DNGSATREMOSAIC (QuadCFA, NORemosaic, DNG) - a candidate,
  not proof; internal selector camera IDs differ from Android IDs; no forceSensorMode index is known for FULLRES there
  (do not copy PD2454's); the SDK 40 / 33 contract and the CTS app category are unproven on these firmwares.
- Plan: per-device FULLRES profile tables (camera IDs, geometry, CFA / block, region variants of PD2547), gated off until
  verified (a sensor mode found from the module descriptors or a targeted test); the PD2454 transport (CTS package, SDK
  lease, one RAW_SENSOR usage 0, own RAW preview, matched capture) reused per device; tests for the tables, the isolation
  from ISZ / DCG and the lease; ask only for the exact missing sensor-module descriptors.
- DONE 2026-10-17 (8620dc85), gated: VivoNativeFullRes - X200 Ultra unchanged; X100 Ultra (PD2366 / V2366*) main 2 /
  ultra-wide 3 8192x6144 Quad, tele 4 16320x12288 Tetra or 8160x6144 Quad; X300 Ultra of every region (PD2547 / V2547A /
  V2547DA, V2562, market name) main 3 16320x12256 Tetra or 8160x6128 Quad, ultra-wide 4 8192x6144 Quad, tele 5 16320x12288
  Tetra or 8160x6144 Quad (two FULLRES buttons on its main camera and telephoto). Sizes from each phone's own sensor-mode
  table (tag 0x8038 X100 / 0x8017 X300: row 0; row 1 the first 50 MP full-field row); no X200 Ultra mode copied. Off until a
  targeted test: scam_dev.txt vivo_fullres_x100u / vivo_fullres_x300u 1 (CTS package); vivo_fullres_mode_main / main50 /
  wide / tele / tele50 try another row of the table (X100: main rows 0 / 11 / 31, UW 0 / 9, tele 0 and 1 / 14 / 18 / 20;
  X300: main 0 and 1 / 2 / 3, UW 0 / 4, tele 0 and 1 / 14 / 27). Every shot proves its Quad / Tetra mosaic from the pixels.
- To verify without guessing (ask the owner for exactly these, from /vendor/lib64/camera/ of each phone): X100 Ultra
  com.qti.sensormodule.pd2366_imx06a.bin, pd2366_imx882.bin, pd2366_s5khp9sp.bin; X300 Ultra pd2547_imx09e.bin (and
  pd2547_imx09e_MP.bin - whichever the phone loads), pd2547_wide_imx06c.bin (or pd2547_wide_imx09a.bin), pd2547_s5khpe.bin -
  the per-row remosaic type of the sensor mode tables. Then a targeted test per camera: real RAW without HW remosaic ->
  SHDR -> JPEG 4:3, AE EV 0 -> + -> 0, the SDK lease returned.

### P181 - the lens strip, ISZ names, FULLRES 50 / 200 MP settings, a lighter 50 MP viewfinder (owner, 2026-10-16 / 17:
«модули выходят за границы экрана», «у 0.8x/2x/4.8x/9.6x убери подпись "ISZ"», «ты должен был сделать отдельные настройки для
50мп/200мп как для отдельных модулей», «Для 50мп сделай тоже упрощенный видоискатель как ты сделал для 200мп»; done)
- ca82baea: the lens tiles shrink together (least width, padding, text, gaps; down to half) when they do not fit the strip;
  their own size whenever they fit; the long press's module list stays for a narrower strip. The X200 Ultra's ISZ modules are
  «2×», «4,8×», old «… ISZ» names migrated, the owner's own names kept.
- 3e0e50c6 / af06e1ad: per-module settings profiles «slot@full200» / «slot@full50» («@full» for the Pixel) taken while FULLRES
  is on for the module, starting as a copy of the module's; listed after their module by the settings' module chip and the
  copy page; carried by a config with their module.
- 25557ca6: a 50 MP Quad viewfinder frame takes one site of each 2 x 2 cell (a quarter of the reads, as 200 MP).
- Not yet seen on the phone (the X200 Ultra was disconnected): the strip with every module, the FULLRES profiles switching,
  the 50 MP viewfinder.
- 1a5f0ef8: fixes of a review of this round (5 reviewers, each finding checked by a skeptic): no ultrashort with Bento off
  (four N then); the strip never squeezes a tile unnoticed; an unopened FULLRES profile reads as its module's; every FULLRES
  state change switches the profile (DCG, fallbacks); a deleted module's FULLRES profiles go; the X100 / X300 stale lease is
  given back with the test switch off.

### P182 - FULLRES with the sensor's hardware remosaic (owner, 2026-10-17: «дай возможность для fullres включать аппаратный
ремозаик»; done, not yet seen on the phone)
- 91b376e2: «FULLRES: аппаратный ремозаик» (Съёмка, off by default, per module profile - so FULLRES 50 / 200 MP keep their
  own) in the native build on the X200 Ultra: 50 MP shots by the SDK route's DNGHWREMOSAIC modes (main 13 / UW 11 8192x6144,
  tele 18 8160x6144, plain Bayer); 200 MP keeps the own remosaic (no hardware 200 MP mode). Hidden elsewhere.
- To check on the phone: the SDK route inside the CTS package (verified so far only in the default package): RAW non-zero,
  JPEG reader drained, AE, SHDR JPEG 8192x6144.

### P183 - one APK with everything: CI builds the CTS package (owner, 2026-10-17: «соберай только один апк. Собери сейчас
один апк со всеми нововведениями и залей на гит»; done)
- Why: the X200 Ultra's native ISZ (main / UW 2x, tele 2x / 4x), FULLRES without remosaic and 200 MP need the package
  android.camera.cts (the HAL gives RAW without remosaic only to it); the CI APK was org.codeaurora.snapcam, where only the
  tele «9,6× (ремозаик)» module exists - the owner took that for the ISZ modules being deleted by P181.
- CI's assembleDebug runs with -PscameraCtsNativeRaw=true: the one APK (SCAMERA-Build-<code>) is android.camera.cts, signed
  with the SCAMERA key. The launcher shortcut targets the build's own package (resValue shortcut_target_package). Unit tests
  run with the same flag.
- For the owner: a different package from org.codeaurora.snapcam (its own settings; a config export / import carries them),
  and a different key from the local CTS diagnostics (uninstall those once). Not yet seen on non-vivo phones under this
  package (installing it there needs the camera permission granted by the owner).

### P184 - FULLRES buttons on the X100 Ultra and the X300 Ultra (owner, 2026-10-17: «На х100 ультра не появляются кнопки с
переключением фулреза, исправь. на 300у они тоже должны быть»; «на X300 Ultra не только теле камера имеет 200мп фулрез, нам
нужно чтобы он был и на основной»; done)
- 9e2806d9: the P180 profiles are on (scam_dev vivo_fullres_x100u / x300u 0 turns a phone off); the X100 Ultra's listed main
  camera 0 takes the main profile too. The X300 Ultra's main camera 3 has both sizes (16320x12256 Tetra 200 MP, 8160x6128
  Quad 50 MP): two FULLRES buttons there and on its telephoto. On the phones: which row gives the raw mosaic, the lease.

### P185 - the shade's morph out of the pill, its rounded bottom, the lens strip as a capsule with dots (owner, 2026-10-17:
«Исправь анимацию шторки» - a jump, not out of the pill, «При раскрытии шторки нижняя часть не должна появляться и
исчезать»; «сделай такие же закругления как сверху шторки снизу»; the strip «ближе к тому что на 3 скриншоте, но при этом в
стиле Material You/Gcam»; done)
- Cause of the jump: the zoom scaled the whole sheet with the containers' clipping off, so its part below the visible one
  showed while it grew and went at the end; and it started as a small copy of the shade, not as the pill.
- Now a container transform (ShadeZoom.frame): the sheet's outline clip is the pill's rectangle and radius at the start and
  its visible part at the end, the content fitted to the shape's width fading in; nothing outside the shape is ever drawn.
  In place at 16:9 the sheet keeps that clip: its bottom corners are rounded like the top (and lined).
- The strip: one capsule (24dp radius), lenses 40dp high with their whole labels («0,4×») and no tiles, the active one an
  accent pill (54dp+), three dots between lenses. Renders: build/reports/viewfinder/shade-169.png (Shade169Test).

### P186 - X300 Ultra FULLRES: a mode the camera cannot run is passed, not looped (owner, 2026-10-17: «На основном объективе -
50/200мп не работают, видоискатель зависает, переключает на ширик. Телевик - 50мп работают, 200мп нет / ширик работает»; done
in code, waits for the phone)
- From the owner's log: UW row 0 and tele 50 MP row 1 work; tele 200 MP row 0 gives no frames, main 50 MP row 1 gives no frames,
  main 200 MP row 0 crashes the HAL (error 4) and the provider restarts - the app then fell back to another camera (the UW).
- 199f5201: every size tries the rows of the phone's own sensor-mode table in turn (VivoNativeFullRes.candidates; X300 main
  {0} / {1,2,3}, UW {0,4}, tele {0} / {1,14,27}; X100 main {0,11,31}, UW {0,9}, tele {0} / {1,14,18,20}); a failed row is
  counted in prefs (module_fullres_rowfail_<cam>_<200|50>, generation ROW_TRIALS) and the next start takes the next row; a
  native FULLRES session without a preview frame turns FULLRES off for that start; after a device error the camera waits for
  the provider (up to 12 x 700 ms) instead of opening another camera. Tests: VivoNativeFullResTest, CameraResumeTest.
- Open: which row (if any) gives the X300 Ultra's main 200 / 50 MP and tele 200 MP - the owner can record the HAL log
  (instructions given 2026-10-17).

### P187 - less memory at full resolution, the same picture (owner, 2026-10-17: «придумай пока как мы можем уменьшить
потребление памяти для полного разрешения при этом никак не потеряв в качестве, найди решение и приступай к реализации. Все
решения принимай сам без моего участия»; done in code)
- Map first (5 areas, each checked by a second reader; research notes in the session, numbers from shot 30840 on the X200
  Ultra: worker peakRss 5286 MB, MemAvailable 3.3 GB in the GPU merge and 1.8 GB in the pipeline). The kgsl column of
  reports/pd2454-30840-memory-timeline-20261016.txt is 32-bit wrapped: about 5.7 GB of GPU memory at the pipeline's minimum,
  as the textures add up.
- Only changes that keep every output byte: what nothing reads any more is freed earlier, what nothing reads is not made.
- Pipeline (above 100 MP; scam_dev early_release 1 does it at any size for a post_ab A/B; never in post_ab's old GL model):
  ScamRgb's input texture becomes main2 for the clip-band pass (no new 1.6 GB main2), then the unbanded output, the flags
  and the chroma blocks go back (glFinish first); ArkLumaSharpen draws ArkCombine's detail reference right after its own
  read of the image and gives the image back (ArkCombine binds the same-size lumaS to the InputBuffer it never reads with
  sharpU 1); hlA closes after the blur; glFinish before ArkCombine's output and before the 0.8 GB bitmap. GPU set: about
  5.2 GB -> 3.4 GB at the worst node.
- App: clip flags in native memory, freed right after their upload (they waited for a GC); the 40 MB decimated copy is one
  sample above 100 MP (only null-checked); the shot's clip flags and frame map are dropped at its end (they stayed until the
  next shot's merge); the plain session under a 200 MP merge fills no ZSL ring (125 MB); the viewfinder's binning buffers
  and the mosaic check's unpacked frame go with their use (401 MB on a packed 200 MP stream).
- DNG: the native file of every DNG was never freed (401 MB leaked per 200 MP DNG); DngCreator is closed after the write;
  WriteToMemory builds the file in one buffer of its final size (the stream path copied the image three more times: up
  to 2.4 GB). tools/check_dng_memory.cpp (CI): WriteToMemory = WriteToFile byte for byte; old vs new on the OPPO: 5/5 md5.
- Worker: header flag 16 (ScamHybridBurst.effectiveMap() = the denoisers' own predicate, ScamHdrDenoise.noDenoise) - no
  effective-frame map above 100 MP: no 765 MB coverage plane, no median sample, no map, no trailer; the clip flags follow
  the RGB / the merged RAW (ScamNeuralClient.trailers, FullResMemoryTest). No chroma GPU context above 67 MP (never used).
- Checks: FullResMemoryTest (trailer layouts, the early-release sizes), ScamHybridBurstAnyResolutionTest (flag 16 at 201 MP
  only), the CI list; OPPO replay of the 8 reference bursts with the P187 worker = the 199f5201 worker byte for byte; with
  flag 16 (SCAM_NO_EFFMAP) on 5 bursts the RGB and the clip trailer are the same bytes, only the map is gone (v10_1 12.5 MP:
  peakRss 1300 -> 1250 MB; at 200 MP about 1 GB less at the merge's peak by the map). Not on a phone yet: the pipeline part
  (post_ab 1 + early_release 1 in scam_dev.txt on a <= 16 MP shot gives EQUAL / DIFFERENT for it), a 200 MP shot's memory.
- Not done (checked, not worth it or not exact): ArkCombine straight to RGBA8 (1 LSB on 1-3 % of values), R16F planes,
  progressive punching of the arena during the merge (loses the conservative retry), streaming the merge into the output
  memfd (days of work, worker phase only), the RAW-less plain session under a merge (sensor mode / framing risk, untested
  on the vivo).

### P189 — «Настройки по модулям»: одна группа для каждого модуля и его FULLRES 50 / 200 Мп (owner, 2026-10-17: «Я так и
не увидел где я могу настраивать отдельно 50мп и 200мп, сделай отдельную группу настроек где все будет разделено по модулям»;
done, not yet seen on the phone)
- Root «Настройки камеры» → «Настройки по модулям» (between SHDR and «Камеры и сенсоры»; also found by the search and opened
  from «Камеры и сенсоры», which loses its non-interactive «Настраивается» pill): the per-lens switch, then each visible
  module (rear by zoom, front) with its FULLRES profiles under it (FullResProfiles.variants: «3× · 50 Мп», «3× · 200 Мп»,
  «1× · полное разрешение»), «сейчас в камере» on the camera's profile, «пока как у «3×»» on a FULLRES profile never opened.
- A row opens the usual XML pages filtered to that profile (SettingsFragment.modulePage + ModulePageFilter): Фото, Съёмка,
  Видоискатель, Цвет, SHDR, «Сенсор и RAW» with only per-module values (ModuleProfiles.isLocal); the hardware remosaic row
  only on a FULLRES profile but 200 MP; the sensor page is the module's slot (shared with its FULLRES shots). The page takes
  the profile into the main settings (onCreatePreferences, again in onResume after the camera took its own back); the chip
  names it («Настраивается: 3× · 200 Мп · ID 5»), no picker, no search. The camera keeps its module and FULLRES state.
- Back from the group (its OnBackPressedCallback) or closing the settings gives the main settings back to the camera's
  profile (ModuleRegistry.syncProfile). With per-lens settings off the rows are dimmed and a tap asks to turn them on first.
- Tests: ModuleProfilesPageTest (root row / search, order and texts in both languages, editing a 200 MP profile and the
  return, the filter, availability keeping shared rows hidden, navigation inside the group, per-lens off, recreate and the
  camera taking its profile back, no hub group in the copy catalogue), SettingsMenuTest (root order, the «Камеры и сенсоры»
  row), FullResProfilesTest (size, hardwareRemosaicApplies).
- On the phone (X200 Ultra, CTS build): the tele shows 50 and 200 MP rows with separate values; a FULLRES 200 MP shot uses
  the 200 MP profile; a long label («2,4× · полное разрешение · ID 4») fits the chip at 360 dp.

### P190 - frames per FULLRES 50 / 200 MP shot (owner, 2026-10-17: «Также дай возможность ставить столько кадров для 50/200мп
сколько я хочу»; his X100 Ultra tele 50 MP shot took 20 frames and the Java memory budget merged 2; done in code, not on a phone)
- Two per-module rows in «Съёмка» (after «Кадров в склейке»): «Кадров в снимке 50 Мп» pref_scam_hybrid_fullres50_frames 0..64
  (0 = auto: as «Кадров в склейке», «Движение» included, the path before P190) and «Кадров в снимке 200 Мп»
  pref_scam_hybrid_fullres200_frames 3..64 (default 4 = 3 N + 1 ultrashort, the P179 series). PreferenceKeys.getFullRes50Frames /
  getFullRes200Frames clamp a scam_dev override too; an explicit count applies in «Движение». Shown only where a module has
  FULLRES of that size (FullResProfiles.sizeOffered, SettingsAvailability.fullResSizes); in the P189 module group the 50 row only
  on a @full50 profile, the 200 row only on @full200 (ModulePageFilter.moduleRow -> FullResProfiles.framesRowApplies).
- Series mode (every 200 MP shot; 50 MP with a count >= 1): CaptureController asks for the count, VivoSdkFullRes.seriesCount sizes
  it (frameCount's RAW guard; at least the legacy 4 / 2; beyond it seriesFit: (avail + 4 RAW at 200 MP - fixed 20/40 B x px x
  grid^2 - one reference RAW) / (RAW + 2 px / b^2 + 3800 x width)); a cut series toasts «Снято K из N кадров: свободно X МБ»
  (and the 2x output when that was the reason). ImageSaver/DefaultSaver/ScamProcessor carry fullResSeries / fullResRequested to
  Parameters; ScamHybridBurst keeps a series whole (normalLimit, no memory budget, no worker limit; <= 64) and writes
  "seriesFrames N" except for a 200 MP series of <= 4 (default shot unchanged byte for byte).
- Worker (scam-hybrid.h; kHybMergeMain1 untouched): tuning seriesFrames floors the native and split mosaic pick
  (hybridMosaicNativeBudget / hybridMosaicSplitBudget, factored out unchanged without the key; split capped at 128 / b^2),
  reports «HYBRID SERIES CUT: K of N (...)» and «HYBRID SERIES: merged K of N sent (N frames k of n)»; the P179 «merges whole»
  line only when it is whole. ScamHybridBurst.notice -> ScamProcessor -> CameraFragment snackbar: N frames the worker left out,
  a fallback tier (conservative retry / single frame), or (auto 50 MP) the N frames the memory budget left out.
- Tests: FullResFramesTest (new), VivoSdkFullResModeTest (seriesFit / seriesCount), ScamHybridBurstAnyResolutionTest (50 MP series
  of 20 kept whole, default cut as before, notices, 200 MP rules), ModuleProfilesPageTest, HybridSettingsTest, DevKeysCoverageTest;
  tools/check_hybrid_series.cpp (CI: budgets without the key = the cc70eb27 formulas, the floors, the lines).
- Not checked: seriesFit's constants on a phone (200 MP at 8 frames, 50 MP at 44, 50 MP 2x output: watch peakRss / MemAvailable
  in shot.txt; if the app is killed raise seriesFit's reserve), the OPPO byte-identity replay with the new worker, timing of
  long series (worker alarm 840 s). Also fixed: ScamNeuralClient's P188 keep-burst writer reused the name `w` (no compile).

### P164 - no «GCam» in the camera (owner, 2026-10-16: «Удали/измени/замени все упоминания Gcam в камере. В чейнжлоге не
указывай»; done)
- Every «GCam» / «Gcam» / «Google Camera» the app shows (settings titles and summaries, list entries, toasts, logs that reach
  the user, the diagnostics README) removed or renamed to our own names (as P55 did for LMC / NICE); code names and keys only
  where they are user-visible or where renaming is safe (a stored key keeps its value through SettingsMigration).
- Not in any changelog (owner).
- DONE 2026-10-16: app/src/main has no «GCam» left (CaptureController's one comment waits: ChatGPT's uncommitted file). The
  multiscale finish denoise's stored value «gcam» is «multiscale» (arrays, XML default, ScamDenoise, the dev list,
  availability); SettingsMigration.migrateScamHybrid turns any stored value but «nlm» into it (HybridSettingsTest
  theDenoiseEngineValueTakesItsOwnName). GcamFinishMath -> FinishMath. The worker's report lines and every comment in Java,
  C++ and the shaders say «the reference», «v11», «6.1», «9.6», «Sabre 6.1» instead (kHybMergeMain1 untouched; comment-only
  changes inside kHybHelpers / kHybReject / kHybMergeMosaic / kHybMochiStats). Four obsolete key names of P5 / P10 left the
  removal lists (cleaned on every device long ago). sound_timer.mp3 only matches by its ID3 «TAG» + «Camera timer».
## P109l - Snapdragon client attribution and VCF RAW boundary

Typed AttributionSourceState spoof is implemented and observed on PD2454. An ordinary open followed by
VivoCameraManager.setValue(1,1) yields AppType 1 / VCFAppType 2 after the one-way Binder call completes.
26 RAWs and JPEG were delivered with sensor routes off; AppType subsequently resets to 2.
Further research: typed spoof's first-request failure is the CameraService permission check against real UID,
and the provider empty-map crash is secondary to closing that errored session. Property 40=1 promotes the
real app to AppType 1 and allows full VCF preview/JPEG, but 12 MP RAW remains zero. Stock's 50 MP RAW is a
dummy too; stock's 12 MP RAW is nonzero. Replaying stock availableStreamMap does not fix our 12 MP delivery.
ModeSelector Parameter offsets are bytes: ROI triplet [3,5,0]. Native forceSensorMode 1/2 gives identical
FOV, white 1023. ISZ/DCG/FULLRES remain unverified; investigate stock versus SDK RAW routing. Experiments default off.
Evidence, tests and continuation prompt: SNAPDRAGON-CLIENT-HANDOFF-20261013.md.


### P109m - PD2454 SDK RAW usage and actual sensor selection (local research, 2026-10-14)
- Corrected stock Pro ID to camera3. Default RAW usage rejects 8192x6144 in CameraService rounding;
  usage0 supplies nonzero Camera2 RAW on ordinary UID10349 after SDK40 before open and Pro UI16.
- Main DNGHWREMOSAIC mode13 (8192x6144); tele mode18 (8160x6144). Wide delivers 8192x6144 too.
  Tele200 MP stays empty. Pro capture sensorMode0 delivers 12 MP auxiliary RAW; mode1 delivers 50 MP.
- Minimal probe: three streams, no Vivo API registration or copied availableStreamMap. Not yet integrated
  into production CaptureController or a live RAW ZSL ring. SDK40/restoration code remains diagnostic.
- SHDR1/AutoHDR0/UI3/EIS0 reaches DCGHDR30_LOG mode12 RAW14, then CHI aborts in AcquireResource.
  ISZ without remosaic remains open. Real com.vivo.bsptest resolves to AppType7, not ENGINEER5.
- SCAMERA diagnostic APK27098 SHA256 in SNAPDRAGON-CLIENT-HANDOFF. No push or Actions artifact.
  Host regression 24/24; all logs, runnable commands and readiness boundaries preserved locally.

### P109n - SDK lifecycle and internal selector IDs (local, 2026-10-14)
- VivoSdkRawSession readback/rollback/restore is exercised on ordinary UID10467 with PRIVATE preview
  and RAW50MP. Four lifecycle tests plus prior checks: 28/28. Production CaptureController not wired.
- SDK RAW repeating target suppresses all RAW delivery. ISO72/10ms delivers50MP; ISO2300/30ms
  falls back to BIN. Exact AE gate unknown; do not fake ISO/vendor gain or claim ZSL.
- JSON Camera ID is internal: Camera2 main3 resolves0. Its ROI needs SCENARIO6; selector sees
  scenario0 and seamlessMode.4=null despite public [3,5,0]. Previous main/wide tuple interpretation wrong.
- Encoder PRIVATE and logical2/physical3 DCG graphs still abort in CHI. Stock video AutoHDR1 route
  is DCG1_4 RAW10, distinct from LOG RAW14. Readiness and runnable commands in latest handoff section.

### P109o - Bright scene and SDK request lifetime (local, 2026-10-14)
- Owner supplied the bright scene. Genuine com.scamera.probe UID10467 obtains automatic-AE RAW50MP,
  main mode13, ISO250/16.7ms. Three serial STILL requests produce three nonzero RAW50MP frames with
  different timestamps/hashes; captureBurst(3) produces only one. No full RAW ZSL claim.
- Native force7 session-only does not crop delivered RAW: measured scale1, correlation0.9932.
  Force7 also in requests crashes the native graph. EnableInsensorZoom/CROPPED_RAW/zoom2 alone
  remains BIN1. SDK photo RAW12MP is dummy, including Vivo global zoom3 and VCFAppType1.
- Stock main ISZ uses global zoom3 = base1.5 * ROI2, SAT1, lux below380; VAS returns [3,3,-1].
  SDK UI1/scene0/config1 resolves SeamlessDecisionRule modern0 and runs legacy. Scene+8 is subUI,
  previously mislabelled camType. Public enableSeamlessMode is overwritten by HAL.
- Explicit SHDR1/numHDRexposure2/EnableHDRDCGMode2 initializes main DCG3/14, caps8192/type0;
  AutoHDR1/DCG3 initializes DCG4/10, caps8192/type1, with working preview. RAW request still
  aborts RTMCX after semaphore timeout. Observer shows repeated HDR configure of same3/0,
  first4/10 then0/0; no camera-ID mismatch proved. Request num/DCG propagation alone does not fix it.
- Standalone v15 has request/session DCG keys, capture-template/usecase options, independent
  burst builders, serial capture with fresh SDK IDs, and a capture-only BitSwitchMode probe.
  Production CaptureController remains unwired to SDK. No push or final Actions APK.

### P109p - Verified production SDK50 and tele HW-remosaic4x fallback (local, 2026-10-15)
- P109l-o above retain their historical snapshot status. Preserve concurrent owner commit
  a0f5911462253ae3443797cf3eba4ae746b5118a and all other AI changes. Firmware scope: tested PD2454 only.
- Production30802 / org.codeaurora.snapcam UID10349 main3 SDK50: three matched serial future NORMAL RAWs,
  mode13,8192x6144, automatic AE ISO250/16666667ns -> ordinary-UID Hybrid PSL -> JPEG8192x6144.
  RAW/JPEG-only STILL targets, preview-only repeating, usage0, exact timestamp/nonzero guards and40/33 restore.
  This is three-frame PSL, not the stock NICE ZSL bracket. Detailed timestamps/hashes are in SDK-RAW-ROUTES.
- Rootless tele5 force6 diagnostic proves4x sensor ROI4080x3072 inside16320x12288, HW remosaicType1,
  nonzero RAW and continuous RAW ring. Production fallback keeps enabled Session/Integer6 in every request,
  RAW usage0, baseTele*4 label, and mutual exclusion with existing DCG/FULLRES controls.
- PD2454 rear capacity9 preserves all8 owner modules; front/other devices stay8. A failed duplicate consumes
  no offer, a successfully offered/deleted module stays deleted. Existing phone marker was not reset.
  Overflow long hold -> all visible module labels/camera IDs -> existing switch handler;4/4 UI tests PASS,
  including a real stationary hold and unchanged zoom drag. Capacity/coordinator/session67/67 PASS.
- Installed diagnostic30805 selected ordinary-UI back8 camera5 zoom9.6(remosaic), measured mode6/Bayer block1.
  At04:01:41 Hybrid took8matched pre-shutter RAWs, future0, and processed as UID10349. Native RAW4080x3072
  was retained; existing2x/Lanczos output5184x3904 received final RGB/HDR-map crop5184x3888 (exact4:3).
  JPEG33355826B SHA256ed5246ae5efe7c0f38d674a08dc480126bda7680537d25d27b3bccbb62716293 verified.
  reports/pd2454-30805-isz-capture-result-20261015.txt and reports/isz-live-20261015/JPEG-SHA256.json.
- APK30805 SHA2564ea7694e2f3f0bd3e74d777144495fa68133c0fa2c439adf2f2c38d10bee1bae verified against
  local .sha256/manifest (25 pinned assets), immutable source-snapshot-20261015-033646. Local diagnostic only.
- Native-mosaic ISZ, DCG RAW, tele200, SDK50 RAW repeating and stock7-frame NICE ZSL remain unfinished.
  The8N/future0 tele shot is not a4N+S+ES+L equivalence claim; no stock image-quality equivalence was tested.
  No push/Actions run for this transfer. Final APK requires the owner's separate push command, Actions artifact,
  and byte-for-byte SHA256SUMS verification. Current sharpening is ArkLuma, not the retired RawTherapee stage.

### P109q - SHDR-only local handoff, unrestricted Sabre2x input and native DCG boundary (2026-10-15)
- Owner supersedes the old stock NICE/ZSL bracket and Actions delivery requirements: no push/Actions;
  prepare current source/evidence for another AI to build a local APK. DCG/ISZ/FULLRES target SHDR only.
- Removed the artificial16MP Sabre2x cap in Java/native, added strict explicit2x, forced FP16 above RGB32
  ByteBuffer capacity, overflow-safe import and actual GPU texture checks. Hardware/overflow bounds remain.
  94 Java tests pass; native50MP transport/guard fixture passes on X200 UID2000. No201MP photo/APK proof.
  kHybMergeMain1 unchanged. FULLRES-SABRE2X-SHDR-20261015.md documents memory and fresh worker rebuild.
- Four fresh native DCG probes are saved.4096x2632 rejected-38; advertised3072 with HDR controls stalls;
  force12-only+JPEG crashes on null Bayer RS config; RECORD/useCase3 plus HDR controls produces nonnull
  primary/alternate AFD configs but still preview0/RAW0. AFD null alone is not the remaining blocker.
  No production DCG availability change. Native tag_ints early-return limitation is explicit in the prompt.
- Primary continuation: CONTINUE-SNAPDRAGON-CLIENT.txt; raw proof/observer note:
  ../reports/pd2454-native-dcg14-video-afd-verification-20261015.txt from the repository root.
  Preserve all parallel P127-P140 work. Old30805 is historical, not a build of these new changes.

### P194 - «FULLRES: аппаратный ремозаик» on the X100 Ultra and the X300 Ultra (owner, 2026-10-17: the switch shown and
working there too, not only on the X200 Ultra; done in code, not verified on the phones)
- The X100 / X300 Ultra have no verified SDK (DNGHWREMOSAIC) modes, so their hardware remosaic is the native route (the CTS
  package, RAW-only session, vivo forceSensorMode row, own RAW viewfinder, matched serial stills, SHDR) with the row their HAL
  gave remosaicked in the owner's P186 trials (MosaicBlockDetector block 1): X300 Ultra main camera 3 50 MP row 2 (8160x6128),
  X100 Ultra main camera 2 (and its listed 0) row 0 (8192x6144). VivoNativeFullRes.hardwareRow / hardwareProfile: a profile
  with nativeBlock 1 ("native Bayer"; Profile.nativeRoute() = nativeBlock > 0, nativeBayer() = 1).
- Acts on: the main camera's 50 MP only. The X300 ultra-wide / telephoto and the X100 ultra-wide / telephoto gave their own
  Quad on the rows tried: the switch changes nothing there (the summary says so in both languages); 200 MP never.
- VivoSdkFullRes.choose(phone, ...): X100 / X300 with the switch on and a Bayer row -> that row; otherwise the P186 table; the
  X200 Ultra (and any phone outside the tables) unchanged (SDK route). hardwareRemosaicOffered() = the native vivo build (never
  the OPPO Find X7 Ultra); hardwareRemosaicOn() on any native-table phone.
- Every place that meant "native route" by nativeBlock > 1 takes the Bayer profile too (CaptureController.nativeVivoFullRes,
  VivoPd2454NativeRaw.fullResProfile / applyFullRes, VivoSdkFullResCapture's router, in-flight stills, requests); the stills
  must not be a confident Quad / Tetra (a flat scene's unconfident Bayer passes; only a mosaic profile must be proven). P188 stays
  for Quad / Tetra profiles; its mirror: a Bayer row whose stream measures block 2 / 4 is a failed hardware row. A failed hardware
  row (no frame, the camera died, a mosaic) is remembered (module_fullres_rowfail_<cam>_hw, generation ROW_TRIALS) and the camera
  takes its own mosaic from the next start; the own rows' trial counts are not touched. The series' memory block is the profile's.
- Viewfinder: LiveRawFrame bins a plain Bayer frame of >= 30 MP (QUAD_BIN_PIXELS) sparse: one 2x2 Bayer quad of each 4x4,
  (w/2) x (h/2) of the same CFA phase (a quarter of the reads, as the 50 MP Quad bin); any phone's live RAW Bayer stream that
  large gets it too (viewfinder only).
- Tests: VivoSdkFullResModeTest (choose per phone / camera / size with the switch on and off, the failed row, offered on the
  native vivo builds only), VivoNativeFullResTest (the rows), VivoSdkFullResCaptureTest (a Bayer row's native series, a Quad still
  rejected), LiveRawQuadBinTest / LiveRawFrameTest (the Bayer bin).
- To check on the phones: X300 Ultra main 50 MP with the switch on - frames 8160x6128 block 1, the viewfinder at ~30 fps, a
  shot = SHDR of plain Bayer, 4:3; X100 Ultra main the same at 8192x6144; whether the SDK lease (keys 40 / 33) is right for a
  remosaicked row; AE / white balance of the HAL in that row.
### P193 - settings changed with per-module settings off reach every module when they are turned on (owner, 2026-10-17:
«Исправь, при изменении каких-либо настроек без включенного переключателя "Свои настройки для каждого модуля" все настройки
должны копироваться по модулям при включении данного переключателя, а не сбрасываться на дефолтные»; done in code, not on a phone)
- What was wrong (two causes): (1) a module without a profile of its own started from the common settings PLUS the old per-camera
  JSON (settings_for_camera_<id> in <pkg>_per_lens), which PreferenceKeys.addIds wrote once at the first camera start (setInitial)
  and nothing ever updated - so it held that start's defaults and put them over the owner's settings on the first turn-on;
  (2) a module (or FULLRES 50 / 200 MP profile) with a profile from an earlier on-period, a config import or a duplicate got
  that whole stored profile back, so everything changed while the switch was off was lost for it.
- Now (ModuleProfiles.changed -> carry): turning the switch on compares the main settings with the common profile (what the main
  settings took back when the switch went off) and writes every per-module value changed, added or reset since into every stored
  profile (all exists_* ids: hidden modules and FULLRES profiles too); a FULLRES profile never opened reads its module's as
  before. A module's other values stay its own: what the owner set for one module and did not change in the common settings
  comes back with it (the choice: a stored profile cannot tell an own value from a copied one, so only what the owner actually
  changed in the common settings wins). Modules without a profile start from the new common profile (the per-camera JSON is
  no longer read, nor written by addIds). Shared settings (ModuleProfiles.isGlobal, module_* keys, the switch itself) are never
  in a profile, as before. Modules added later while on start from the common profile; on again without changes: unchanged.
- The turn-on dialog of «Настройки по модулям» says so: «Каждый модуль начнёт с текущих настроек; свои значения модуля вернутся
  только там, где вы не меняли общие настройки».
- Tests: ModuleProfilesCarryTest (new, in the CI list; 4 of its 5 fail on the old ModuleProfiles): a change made while off reaches
  back0 / back1 / back2 / back5 / back1@full50 / back1@full200, a module's and a FULLRES profile's own values survive, off and on
  again without changes keeps them, a reset while off is reset everywhere, the stale per-camera JSON never wins, shared
  settings stay out of profiles. SettingsMenuTest's legacy-JSON test now checks that the JSON no longer overrides. Run: 181/182
  of ModuleProfilesCarryTest, FullResProfilesTest, FullResFramesTest, SettingsMenuTest, ModuleProfilesPageTest, DeviceDefaultsTest,
  ShadeTilesTest, HybridSettingsTest, ColourSettingsTest, SettingsAuditFixTest, CameraResumeTest, BackupLensMatchTest; the one
  failure, FullResFramesTest.aDevOverrideIsClampedToTheHardLimits, fails the same on c3707991 (not P193).
- On the phone: switch off, change a few processing settings (e.g. SHDR strength, sharpening), switch on, open «Настройки по
  модулям» - every module and its 50 / 200 MP rows show the new values; a module given its own value earlier keeps it where the
  common setting was not changed.

### P188 - mosaic grid, the shutter beep, the 50 MP viewfinder (owner, 2026-10-17: «На всех isz и фулрезах я получаю вот такие артефакты сетки, исправь все», «убери пик после нажатия на кнопку спуска затвора», «Нам нужен фулрез без ремозаика»)
- Site-class response (mosaicGain 2, hybridMosaicGainsRobust): the 64 site classes' gains from the mean of all normal frames in
  sensor coordinates (pattern coherent, noise down by the frame count), predicted from the group mean and the neighbour tiles'
  gradient, ratio of sums, two rounds, error from even / odd tiles (logged). Works in the dark (the old one found no tile).
- Smooth donor weights between the robust cells (mosaicRobustBilinear 1) instead of 4x4 steps (square halos, staircases).
- The synthesized shutter tone is gone (CameraSounds). A native FULLRES row that delivers HAL-remosaicked Bayer is a failed row.
- scam_dev keep_burst: the worker keeps the shot's burst (P191 moved it to Download/SCAMERA).
- Replays on the OPPO: plain bursts byte-identical. The owner's JPEGs still showed lattice peaks (period 2 / 4); kernel width and
  ArkCam's R/B fill do not change the lattice amplitude in replays (0.14-0.27 %), see P191 for the frame-count cause.

### P191 - 50 MP viewfinder speed, the frame count of 50 MP merges, files out of Android/data (owner, 2026-10-17: «фпс всё ещё очень низкий», «Сетка не исчезла, посмотри на буквы на 50мп», «переноси всё туда чтобы в data ничего не хранилось», «Добавь настройку с сохранением рав серии в группу настроек "Диагностика"»)
- Viewfinder: the camera gave 30 fps, the screen ~11 (the per-site ShortBuffer bin took 22-45 ms, the wait was twice that). Rows
  in bulk short[] (5 ms at 50 MP on the OPPO, 8 threads), the wait is the bin's cost plus a quarter. Same output (test).
- 50 MP merges: the owner's shot took 20 frames and merged 2 - the Java estimate counted each mosaic frame at full size (156 MB)
  against a 1.9 GB fixed cost. A mosaic stream of 16-100 MP leaves the count to the worker's native budget, which now takes 80 %
  of MemAvailable (was half: 1 GB of output alone took more than half at 1.7 GB available). OPPO: 20 of 20 merged.
- Every file the app keeps for the user is in Download/SCAMERA (scam_dev.txt with a one-time move and the folder grant as
  fallback, crash log, dumps, kept bursts one per shot scam_burst-<time>.bin, the worker's tuning / SR files).
- Diagnostics «Сохранять RAW-серию» (pref_keep_raw_burst; scam_dev keep_burst still overrides).
- Replays: 8/8 reference bursts byte-identical (worker 2a21ee9d vs 62da6c91).

### P192 - FULLRES without hardware remosaic on OPPO phones (owner, 2026-10-17: «Продолжи реализацию FULLRES без аппаратного ремозаика для OPPO Find X7 Ultra», «Добавь все смартфоны оппо в список»)
- The native FULLRES route (one RAW stream, RAW viewfinder, serial stills, SHDR with the own remosaic) with the session key
  com.oplus.engineercamera.agingtest.mode.select (byte). Candidates from the camera's Qualcomm sensor-mode table (0x80390000): the
  full-size rows after row 0, in order - on the PHY110 exactly the measured NoRemosaic modes (main 35/37, UW 16, 3x 19, 6x
  17/20). Every session proves its mode (result) and mosaic (pixels); a failed mode is noted, the next is tried next time.
  The table is taken from the characteristics the app read (an own CameraManager failed with the owner's spoof).
- The mode's HAL has no statistics (AE SEARCHING, neutral NaN): own AE on the native RAW (NativeRawExposure: median to 0.12 of
  white, p98 under 0.85 within two stops, shutter up to 1/30 s in 10 ms steps, then ISO), own white balance (MosaicNeutral on
  the frames, on the camera's curve), the plain session's colour transform and shading map fill the results (setter looked up
  once: per-result lookups filled the JNI global reference table and aborted the app after ~2 min). The series ends with an
  ultrashort Bento frame (50 MP too): without it bright windows turned pink and lost the sky.
- Telephotos through OplusCameraManager (saveOpPackageName + setPackageName) unless the owner's spoof settings say otherwise.
- Checked on the PHY110 (owner's aweme spoof on): main mode 35 and 3x mode 19 measured Quad by pixels, viewfinder ~24 fps,
  own AE 3.8 -> 4.2 ms ISO 100 (HAL in plain mode 3.79 ms), neutral 0.330 / 0.705 on the curve, 20 of 20 frames merged, photo
  8192x6144 saved, FULLRES off returns mode 1 / ISP viewfinder, 4 min native viewfinder without the abort. Not checked: the
  Bento-frame shot's result (the phone locked during its merge), 6x and UW shots, other OPPO models.

### P195 - «Наш ремозаик»: Quad / Tetra without our remosaic (owner, 2026-10-17: «добавь так же возможность отключать наш 2х2 и 4х4
ремозаик чтобы получать фото без него. настройка должна находиться в группе настроек "Мозаика без ремозаика" В самом верху»; done in
code, replayed on the OPPO, not shot on a phone)
- What «without remosaic» is here: no reconstruction of the full-resolution mosaic at all (neither the native merge kHybMergeMosaic /
  Fast nor the sub-frame split). Every b x b colour block becomes ONE plain Bayer site - the site-gained (mosaicGain), clip-aware
  block mean the native path already builds for its alignment (white when a site clipped, black of the block's Bayer phase, noise
  slope / offset / b^2) - and the binned burst W/b x H/b goes through the regular Bayer merge (hybridReconstruct as for a plain
  stream: alignment, F6, rejection, Sabre 6.1 kernel, Bento, Shasta, chroma-difference / rim / Bento colour passes, RAW CA fit,
  noise check, auto2x). No lattice or maze by construction; the quality is that of a plain Bayer burst at the binned size.
- Switch: pref_scam_hybrid_mosaic_remosaic (SwitchPreferenceCompat, default on = today), the FIRST row of «Мозаика без ремозаика»
  (scam_hybrid_mosaic_screen); per module like every pref_scam_hybrid_*; scam_dev.txt "hybrid_mosaic_remosaic 0/1". Off: the
  hybrid tuning gets "mosaicRemosaic 0" (PreferenceKeys.hybridTuningText; on writes nothing), SettingsAvailability greys the rest
  of the page except «Кадров в склейке мозаики» (mosaicFrames still caps the binned pick).
- Worker (scam-hybrid.h): HybridTuning.mosaicRemosaic (1 default), hybridMosaicBinnable (Quad / Tetra, whole 2b x 2b periods, >= 64
  binned px a side), hybridReconstructMosaicBinned (frame pick: base + nearest N within mosaicFrames / kHybridGpuFrames / 80 % of
  MemAvailable above 16 MP, every ultrashort and bracketed frame; seriesFrames and the 200 MP kHybridHugeFrames floor it as in the
  native pick). Routed in hybridReconstruct before the native / split routes only when the block is the header's (the app sized its
  result for it) or forced by tuning (replays); a measured-only block or a partial frame keeps the remosaic ("HYBRID MOSAIC BINNED:
  remosaic kept (...)"). hybridPrewarmGpu builds the plain set for such a module. kHybMergeMain1 untouched; the native / split code
  untouched.
- App: ScamHybridBurst.binnedBlock (same rule) -> gridWidth / gridHeight = W/b x H/b, outWidth = that or its Sabre 2x grid
  (sabre2xFits on the binned size), header grid word against the binned grid, tuningOverride() appends mosaicRemosaic 0 for a binned
  burst (mosaicRemosaic 1 when the switch is off but the burst keeps the remosaic), memory budget counted at the sensor grid.
  ScamTransport.mergedRawPixels: the merged DNG trailer is W/b x H/b; ScamProcessor writes that DNG at its size (rawSize swapped
  for the write, the same CFA order, black and white; ScamHybridBurst.lastMergedDngSize). The pipeline already follows a result
  smaller than the sensor (ScamProcessor: rawSize / sensorPix / XPerMm / outputScale 0.5; post stages use max(1, round(scale))).
  MosaicNeutral (P121) still applies: the stream and its misread ISP neutral are the same mosaic. Viewfinder not touched. The CPU
  last tier stays at the sensor grid (block-aware demosaic of the base), as before.
- Photo sizes (final size = the merged size unless 12 / 16 / 20 MP is smaller, as before):
  12 MP ISZ Quad 4096x3072: «Разрешение» сенсор -> 2048x1536 (3.1 MP); 2x -> 4096x3072; 12 MP -> 4096x3072 (the 2x grid = the sensor
  size class); 50 MP Quad 8192x6144: сенсор -> 4096x3072; 2x -> 8192x6144; 12 / 16 / 20 MP -> resized from 8192x6144; 200 MP Tetra
  16320x12240: сенсор -> 4080x3060; 2x -> 8160x6120; 12 / 16 / 20 MP -> resized from 8160x6120. The 2x grid of the binned merge
  restores resolution only from the frames' sub-pixel shifts (handheld); auto2x (on by default) merges it at 1x and resizes when the
  frames do not sample finer.
- OPPO PHY110 replay of isz2.nch (Quad 4096x3072, 27 frames; worker NDK build): default (no key) and "mosaicRemosaic 1" md5
  e7015691 = the HEAD worker's (byte-identical); "mosaicRemosaic 0": 2048x1536, 27 of 27 frames, 2.4 s total (native: 22 frames,
  30.1 s); with "grid 2": 4096x3072 Sabre 2x, 2.9 s. Colour, framing and brightness match the native result (means within 1-3 %;
  crops in the session scratchpad).
- Tests: ScamHybridBurstAnyResolutionTest.withoutTheRemosaicAMosaicIsMergedAsBinnedBayer (rule, sizes 1x / 2x, Quad / Tetra,
  header, trailer, tuning), HybridSettingsTest.remosaicSwitchIsTheTopMosaicRowAndReachesTheWorker; tools/check_ui_language.py,
  tools/check_settings_model.py.
- Open: a real shot on the vivo X200 / X300 Ultra (ISZ Quad / Tetra, FULLRES 50 / 200 MP) and the OPPO with the switch off (photo
  size, DNG opening in a RAW editor at the binned size, white balance); the FULLRES series memory sizing (CaptureController /
  VivoSdkFullRes.seriesCount) still counts the remosaic's memory (conservative: fewer frames than the binned merge could hold).

### P196

Owner (2026-10-17): «нам нужно чтобы в информации о фото писало фактическое разрешение и модель смартфона» (gallery «Сравнение»: the
first pane's pill «11:56 · ISO 50 · 1/852 · f/2,27 · 22,5 мм · JPEG», the second one only «11:55 · ISO 50»).
- Why the second pane lacked shutter / aperture / focal length: not the file. The pane label had a per-pane flag, off by default:
  «time · ISO» until that pane itself was tapped (the tap meant to show the full EXIF). After a tap on the first pane only the first
  showed everything; a file whose EXIF had no shutter would still have shown the format («· JPEG»), which the second pill lacked.
  All our writers already store the same tags (ParseExif.setAllAttributes: plain JPEG, ExifBlock for Ultra HDR / HEIC 8 and 10-bit /
  AVIF, ExifInterface for WebP; DNG its own tags). Fix: ImageViewerFragment.paneFullExif is one static flag, on, reset on every new
  comparison; a tap on either pane switches both (ImageCompareFragment.refreshLabels).
- Compare pane label, three lines (max 4, one may wrap): «11:56 · ISO 50 · 1/852 · f/2,27 · 22,5 мм · JPEG» / «8192×6144 · 50 Мп ·
  vivo X200 Ultra» / file name (muted). Compact mode (after a tap): «11:55 · ISO 50» + name, as before.
- Details sheet («Сведения»): «Разрешение» 8192×6144 · 50 Мп (was «8192 × 6144 · 50 МП» from the EXIF size tags), the phone on its
  own «Смартфон» row (ic_gallery_phone; it was appended to «Объектив»).
- Actual resolution: gallery/helper/ImageHeaderSize reads the file's own headers (JPEG SOFn - an Ultra HDR JPEG's primary; WebP
  VP8 / VP8L / VP8X canvas; HEIC / AVIF primary item 'ispe' via pitm / iprp / ipma, mdat before meta too), forward-only with skips;
  else BitmapFactory bounds (not for DNG); else the EXIF tags (a DNG: ExifInterface reads the raw IFD itself). Swapped for an EXIF /
  irot 90 / 270 orientation (as shown). Megapixels (PhotoInfo): whole from 20 MP («50 Мп», «200 Мп»), one decimal below («12,6 Мп»
  for 4096x3072, «12 Мп» for 4000x3000), two below 1 MP.
- Phone: PhotoInfo.device(EXIF Make, Model) - «vivo X200 Ultra» (the brand once when the model already starts with it), the make alone
  without a model, nothing without either (nothing invented).
- Writer: util/DeviceName - Make = Build.BRAND (MANUFACTURER without one), as before; Model = the market name when the system has one
  (ro.vivo.market.name as VivoQcomIsz reads it, ro.vendor.oplus.market.name, ro.product.marketname / ro.product.vendor.marketname as
  XiaomiTeleZoom reads it), ASCII start only («vivo X300 Ultra 卫星通信版» -> «vivo X300 Ultra»; no Latin start -> Build.MODEL), else
  Build.MODEL. ParseExif.ExifData and our DngCreator (Make / Model; UniqueCameraModel stays Build.MODEL-based) use it.
- Also: HybridSettingsTest (P195, HEAD b05885f7) did not compile - a closing brace of theFormerMosaicKernelDefaultMovesOnce was lost
  when the P195 test was inserted; restored (no assertion changed).
- Tests: PhotoInfoTest, gallery.helper.ImageHeaderSizeTest (incl. our HeifContainerWriter's 150x70 grid HEIC), gallery.ui.fragments.
  ComparePaneLabelTest, util.DeviceNameTest, processing.PhotoMakeModelTest (Robolectric: JPEG, WebP lossy / lossless and the EXIF
  block store Make «vivo» / Model «vivo X200 Ultra» with ro.vivo.market.name set, Build.MODEL without; real encoder files sized from
  their headers); registered in build-test-apk.yml. GalleryUiTest, JpegExifStreamTest, PhotoOutput*, AvifOutputTest,
  HeifContainerWriterTest, HybridSettingsTest pass; tools/check_ui_language.py PASS.
- Open: on the phones - the compare / details of a JPEG, Ultra HDR, HEIC 8 / 10-bit, AVIF, WebP and DNG shot (resolution, «vivo X200
  Ultra»; whether ro.vendor.oplus.market.name is set on the OPPO PHY110 / Find X8 Ultra - otherwise «OPPO PHY110»); AVIF EXIF is read
  by ExifInterface only from Android 12 on (MediaMetadataRetriever), resolution comes from the header on any version; photos taken
  before P196 keep the Build.MODEL they were written with («vivo V2446A»).

### P198

Owner (2026-10-17): «Так же сделай так чтобы 50мп снимались в zsl и только 200мп снимались в psl».
- Before: a native FULLRES session (vivo X200 / X100 / X300 Ultra native build, OPPO phones: one RAW_SENSOR stream at full size, the
  RAW viewfinder) took every still after the shutter (VivoSdkFullResCapture, serial PSL) at 50 and 200 MP alike; a 50 MP series ended
  with one ultrashort (P192). The ring was never fed: isZslMode() is false whenever the FULLRES session runs.
- Routing (VivoSdkFullResCapture.zslRoute): the native route (nativeBlock > 0: Quad, or P194's hardware-remosaic Bayer row) up to
  HUGE_SHOT_PIXELS (100 MP) -> ZSL; 200 MP (Tetra) and the X200 Ultra's SDK route (nativeBlock 0, JPEG reader) -> serial PSL exactly as
  before (stopped preview, P190 «Кадров в снимке 200 Мп», its own ultrashort). scam_dev.txt «fullres_zsl 0» (DevKeys, «Для
  разработчика»): the PSL series for every native size, as before.
- Ring feed (CaptureController.onMatchedLiveRaw): in a native 50 MP session each matched live frame (the viewfinder keeps running from
  the same frame) goes to the ring when no shot is in progress (from the press until its series is complete) and its result reports no
  other sensor mode (VivoSdkFullResCapture.modeMatches, the stills' rule; misses logged «50 MP ZSL: n frames of another sensor mode»);
  an OPPO result is completed first (OppoNativeFullRes.complete: own neutral, plain transform, shading); the matched result goes to
  mHexZslResults (the plain session fills it from the preview callback). Only a memfd copy enters: the camera's own Image is never held
  (the native reader has 4; one held would stall the viewfinder). A copy out of the camera's buffer is slow (uncached reads: 53-56 ms
  for 96 MB on the PHY110, the first 149 ms), longer than a 24 fps frame, on the thread that also draws the viewfinder and runs the own
  AE: a frame is copied only when twice the last copy's time has passed since the last one began (CaptureController.ringCopyDue; on
  the PHY110 every third frame, ~8 a second); logged «50 MP ZSL ring: copy n ms» on the first frame and every 150th.
- Ring size (ZslRing.createFullRes / fullResSlots, 96 MB slots): total slots = a quarter of MemAvailable (the 12 MP rule takes 40 %),
  4..16 (16 x 96 MB = 1.5 GB = the 12 MP default ring's 66 x 24 MB); shot slots = min(6, total / 2) (the plan's 3 ultrashort + 3
  bracketed); ring = min(ring setting, N + 2, total - shot). N = «Кадров в снимке 50 Мп» when > 0, else the merge's frame setting
  (VivoSdkFullResCapture.zslRequested). With the default N 20: >= 6 GB available -> ring 10 + shot 6; 4 GB -> 5 + 5; 2 GB -> 3 + 2;
  less -> 2 + 2. Logged «50 MP ZSL ring in memfd: ... cap ... slots = min(16, a quarter of ... MB available)». A ring of the other kind
  (12 MP <-> 50 MP) is replaced.
- Shot (captureVivoSdkFullResChecked -> takeNativeZsl): the ring is drained as the 12 MP Hybrid's (drainZslNormalFrames: before the
  cutoff, matched, the newest exposure tier, no stale gap, steadiest without OIS), each frame materialised (a view of its slot) and
  checked with MosaicBlockDetector as the stills are: a confident other colour block leaves the merge. No frame left -> the PSL series as
  before (its stills keep VivoNativeFullRes.failed / OppoNativeFullRes.failed bookkeeping). The post-shutter part is
  HybridPlan.build (the 12 MP plan: bento 3 EXTRA_SHORT, shasta 3 LONG by default) from the newest ring frame's exposure (OPPO: its
  own NativeRawExposure's, AE off; fallback mNativeAe), taken by VivoSdkFullResCapture.plan(): every still keeps the native keys
  (VivoPd2454NativeRaw.applyFullRes / OppoNativeFullRes.apply, session keys copied, EV and AE regions), AE off at the plan's exposure /
  ISO, its frame duration raised to a longer shutter; results checked for the mode, pixels for the mosaic (as before), OPPO results
  completed. finish(): ScamCaptureSequence.hybridZsl (ring N + stills), tolerant as at 12 MP (HybridPlan.classify: a still no role fits
  is dropped, the rest merged); the mosaic counts as verified by a ring frame or a still. Memory: the series budget (seriesCount /
  frameCount) for N + post with the ring frames' bytes counted as available (they are resident already); the oldest N go when it is
  short (zslNormals; toast only for that, the ring's own cap is in the log). Base metadata = the newest ring frame; instant-shot UI
  («ScamZslCaptureStarted»); P192's single ultrashort only on the PSL path.
- Tests: capture.FullResZslTest (routing 50 MP native -> ZSL, 200 MP / SDK -> PSL; frame-count rules; ring mode rule; Bento 3
  ultrashort; the copy throttle; a ZSL shot = 4 ring N + 6 plan stills with the native keys, AE off, plan exposures, longer frame duration; an off-role still
  dropped and the shot goes on), ZslRingBudgetTest (50 MP slots), FullResMemoryTest (the 50 MP ring within a quarter of the memory);
  registered in build-test-apk.yml; DevKeys «fullres_zsl» (DevKeysCoverageTest). The whole CI list passes: 183 classes, 1105 tests
  (FullResZslTest 7). tools/check_ui_language.py, check_settings_model.py PASS.
- Phone (OPPO Find X7 Ultra PHY110, local snapcam APK 30930, SCAMERA key, worker built from this tree): the app opened straight into
  its native 50 MP session (camera 2, mode 35, Quad confirmed by MosaicStream): «50 MP ZSL ring in memfd: 7 slots x 96 MB (ring 4 +
  shot 3 ..., cap 7 slots = min(16, a quarter of 2732 MB available))», copies 53-56 ms, the RAW viewfinder kept 24 fps (STAB_TRACE
  46-52 frames / 2 s, maxGap 41.7 ms) with the ring running, own AE and white balance as before. No shot was taken: a system dialog
  (com.oplus.battery, low storage) was in focus, so the UI was not touched.
- Open: a 50 MP shot on the OPPO and the vivo native build (the log lines «50 MP ZSL: n ring frames ...; hybrid plan ...», «NATIVE
  FULLRES ZSL: n ring RAWs + k of 6 after the shutter»), the merge of ring N + 3 ultrashort + 3 bracketed (Bento / Shasta result, the
  shutter lag); with 2.7 GB available the ring holds only 4 frames - more memory gives up to 10; a faster (multi-threaded) copy would
  let more frames into the ring.
### P197

Owner (2026-10-17): «Сделай для нативной мозаики ту же цветовую проверку Bento, что у 12 Мп». vivo X200 Ultra, flickering 50 Hz
ceiling lamp: the FULLRES 50 MP native Quad shot painted the clipped diffuser orange on one side, blue on the other; the 12 MP shot
of the same lamp was white. Logs: 50 MP «HYBRID GAIN CHECK frame=14 role=5 ... measured=0.1383 (+10.7 %) mad=26.9 % -> inconsistent
ratios», Bento forced with that one ultrashort (2.08 ms at 1/60 N); 12 MP: three ultrashort frames, +2.5 %, MAD 5.1 % -> ok.
- Cause: rolling-shutter flicker. The 2 ms ultrashort is read row by row while the lamp runs at 100 Hz: every row another phase,
  another level and (white LED: the blue die follows the current, the phosphor glows on) another colour. Not a check the native
  path lacked: the plain path's Bento checks (bentoMask intensity error, per-frame validation, motion share) already run on the
  native path's binned frames; none of them sees a colour inside a clipped base (GainUp(us) is min(., 1) there), and the Bento
  colour pass (kHybBento) takes R/G, B/G FROM the ultrashort frames, so porting it (or kHybRim) would paint the same bands. The
  12 MP shot was white because its data was consistent.
- New (scam-hybrid.h, hybridUsRowsCheck; all three mosaic paths: native hybridReconstructMosaicNativeMerge, split
  hybridReconstructMosaic, binned hybridReconstructMosaicBinned), on every ultrashort frame they merge, on its binned frame against
  the binned base:
  1. row profile (hybridUltrashortRows): bands of 32 binned rows (4..64), 16x16 tiles where neither frame clips and every colour
     has signal; per tile the green ratio g and the colour ratios (R, B ratio) / g; a band counts with >= 24 tiles whose MADs are
     all <= 12 % (a row profile means every tile of a band sees the same factor). Factors G = E / g_band (E = the exposure ratio
     the merge uses; gainMeasured has decided it on the full-resolution frames before), R / B = G / colour ratio (colour from the
     same tiles as the level). Other bands interpolated; fewer than half count: no profile.
  2. a factor off 1 by mosaicUsRowsMin (8 %) or more: the frame is corrected, (v - black) x factor of its row and colour, linear
     between band centres: binned frame in place, split sub-frames (row J + (c - (b-1)/2) / b), native sites at GPU upload
     (HybridMosaicNative::rowGain / fixRows, a strip-sized copy; the native clip scan reads the corrected rows; no corrected copy of
     the 50 / 200 MP frame is held), T2's Quad mosaic in place. Clipped sites stay; a corrected value is at most white.
  3. decision for «forced Bento with an inconsistent frame»: the merge's gain check (hybridMeasuredGain) on the corrected pair; still
     MAD >= 15 % (not a row profile: lamps on other phases, motion) = the frame is refused - not merged, no Bento from it, the base's
     clipped highlight stays for the highlight recovery (neutral white) - in every Bento mode, as the motion refusal.
  Tuning: mosaicUsRows (1; 0 = off), mosaicUsRowsMin (0.08). Plain Bayer bursts never reach it. Report lines «HYBRID MOSAIC
  NATIVE: ultrashort frame N rows against the base: ... -> corrected / as captured», «... after the row correction: ... ->
  consistent», «... refused: ...».
- OPPO PHY110 replays (owner's tuning + clipFlags 1; tool ana: pixels with Bento + clipped base flags and G > 1 in the reference,
  16 row bands, R/G, B/G, G of the test against the reference own_114335 old worker; synthetic variants of own_114335 made on the
  phone, frame 19 = the ultrashort, DN above black):
  - own_114335 (no flicker): profile 4.6 % max (54 of 64 bands agree) -> as captured; md5 0592b43a old = new (native), binned
    fa79ef61 old = new.
  - colour ramp down the rows (R x0.75..1.25, G x0.9..1.1, B x1.25..0.75; its gain check said ok, mad 2.9 %): old native R/G
    x0.866..1.064, B/G x0.853..1.313 of the reference across the rows (the band); new: corrected (factors R 0.77..1.26, B 0.82..1.31),
    gain mad 1.6 %, R/G x0.982..1.003, B/G x0.997..1.010, G x0.981..1.010. Binned path old R/G x0.866..1.064 / B/G x0.853..1.313 ->
    new x0.982..1.004 / x0.997..1.010; split path old x0.864..1.064 / x0.852..1.313 -> new x0.982..1.004 / x0.996..1.010 (binned / split
    measured with the build before the level normalisation moved from the median g to E; on the final build the binned run
    reports the same profile and mad 1.6 %).
  - owner-like flicker (3 sine periods down the frame, R +-60 %, G +-45 %, B +-30 %; gain check «inconsistent ratios», mad 29.1 %):
    old R/G x0.741..1.100, B/G x0.901..1.257, G x0.56..1.42; new: corrected, mad 1.9 %, R/G x0.979..1.001, B/G x0.994..1.010,
    G x0.979..1.016.
  - own_114300 (2x ISZ Quad, 26 frames, ultrashort consistent): profile 6.6 % -> as captured, md5 ed15e42f old = new.
  - reference bursts (rp181/ab2.sh, old and new worker): syn_b1 5b988db1, x7u_1x 52bcce71, syn_b2 7c1a3756, syn_b4 5e586261,
    hand db8d449d, isz2 d5dceb41, v10_1 161b2e8e, v10_2 fe39aa5e - identical.
  - tools/check_hybrid_us_rows.cpp (CI, and NDK build run on the phone: OK): consistent frame untouched bit for bit; row ramp
    corrected to 0.6 % flat; sine flicker mad 29 % -> 2.5 % at the merge's ratio; COLUMN ramp: no row profile (0 of 24 bands
    agree), refused; native fixRows vs binned correction within 1 DN; mosaicUsRows 0 changes nothing.
- Open: the split path (mosaicPath 0) is not deterministic run to run (old worker: ab43f711 / cdcf9a62 on the same input), so it
  was checked by the measurements only. A column-wise level ramp on the real burst (x0.5..1.5, gain mad 12.4 % = not
  «inconsistent») still got a level profile (53 of 64 bands agree because each band's usable tiles sit in a narrow column range):
  colours stayed right (R/G x0.978..0.999) but the highlight level spread x0.83..1.05 instead of the old x1.005..1.086 - a
  physically unlikely case, a tighter band MAD would trade it against real flicker under mixed light. Not on a phone shot yet: the
  owner's own flickering 50 MP burst (only its log was available); the plain 12 MP path is unchanged by design (an inconsistent
  plain ultrashort would still paint).

### Research 2026-10-17 - what removes the lattice in ArkCam (owner: «выясни что на ark убирает решётку»)
- No mechanism of its own: ArkCam 1.56 does not sharpen by default (JNI setUSMParams / setRLParams are empty stubs at 0x7fdbc;
  native defaults of every sharpening radius / strength 0), while ArkLumaSharpen multiplies our diagonal Quad lattice x2.5-2.9
  and the 2 px one x1.2-1.5. ArkCam's merge itself leaves as much lattice (its own linear DNG vs ours) - per-site merge_quad, no
  texel-grid upscale (super-res off above 4200 px), quad speckle p066 off, its kernel ~0.5 in our units, no site gains.
- Measured on the owner's 50 MP burst (diagonal, % of the level): k0.7 + our sharpening 0.173 / 0.185, k1.0 + our sharpening
  0.084 / 0.093 (P191 default), k1.0 + sharpening on a 3x3-smoothed luma (R1) 0.045 / 0.049. R1 changes the sharpening: not done
  (owner: «Шарпинг не трогаем»), offered. Notes: scratchpad arklattice (table.md, crops).

### P200 - FULLRES 50 MP on 8 Gen 3 finishes; ring copy; X300 main row order (owner, 2026-10-17: «На 100у и 7у обработка с
### фулрезом не заканчивается», «на 300 ультре ... на основе не с первого раза ... видоискатель имеет низкий ФПС»)
- Cause (X7U / X100U logs, then reproduced on the OPPO fb27034c): the app was killed 1-4 min into every 50 MP merge - first for
  memory (after the shot the camera built a new 50 MP ZSL ring, up to 16 x 96 MB, and a 66-slot plain ring while the worker held
  80 % of MemAvailable), then, with that fixed, by ColorOS for heat (ApplicationExitInfo «o-stop(22)», thermal level 14 at 53 C;
  the 50 MP session kept streaming + binning at 24 fps through the long merge). The worker never hung: «HYBRID MOCHI: off» was
  just the last line before the GPU merge.
- Fixes: (1) a FULLRES 50 MP shot is merged with the camera in its plain session, as a 200 MP one (P179 closeCameraForProcessing;
  dev key fullres_plain_merge); (2) while a FULLRES merge runs no 50 MP ring is kept (released at the shot, its free slots punched
  at once) and the plain ring holds SUSPENDED_RING frames; (3) Allocator: a live ring's slots are not hole-punched when a view goes
  (only a released arena's) - a punched 96 MB slot refaulted every page on its next copy: X200U bench 69 ms -> 6.7 ms, X7U ring
  copy 63-75 ms -> 7-10 ms, the 50 MP viewfinder 23 fps; copies >= 32 MB on 4 threads; (4) the native merge's GPU strips may take
  half of MemAvailable up to 512 MB (a quarter halved 189 of 190 strips to 8 cells); the shrink report names the first limit;
  (5) X300U main 50 MP rows 3 / 1 (row 3 = the working native Quad; 2 is the hardware-remosaic row), tried count under a new key.
- Phone (OPPO X7U, local 30935/30936): 50 MP shots now complete with the app alive (camera back to FULLRES after). Open: the merge
  is slow when the phone is warm - 95-133 s for 17-20 native frames at 39-41 C battery (20 s in the cool morning test); not yet
  separated GPU throttling vs the low-priority context; X100U main / UW have no own-mosaic row that gives frames (main 0/11/31,
  UW 0/9) - its main 50 MP is the hardware-remosaic route only.

### P201 - the manual mode with FULLRES (owner, 2026-10-17: «убедись чтобы при работе фулреза работал полностью ручной режим»)
- Every FULLRES viewfinder request had its exposure rewritten (vivo native AE on, X200U SDK route AE on at ISO 0, the OPPO's own
  AE) and the series' stills came from the still template (auto WB / AF). Now: ParamController.applyManualTo after the FULLRES
  keys (both by hand AE off at them, one by hand the loop's values, the HAL / Qualcomm priority untouched), hand-set WB and focus;
  the OPPO's NativeRawExposure.setManual (both: exact, one: meters the other), a hand-set WB is the OPPO shot's neutral; stills copy
  AWB-off gains / transform and AF-off distance; PSL N at the hand-set exposure; a shutter > 1/10 s skips the ring.
- Tests: NativeRawExposureTest.aHandSetIsoAndShutterHold. Not yet checked on a phone with the manual panel.

### P202 - FULLRES 50 MP ZSL or PSL; bracketed frames in PSL (owner, 2026-10-17: «давай дадим выбор ZSL или PSL», «В PSL у нас
### тоже должны быть брекет кадры»)
- «50 Мп: ZSL» (pref_scam_hybrid_fullres50_zsl, default on, a row of the 50 MP profile; scam_dev fullres_zsl wins). A native 50 MP
  PSL series is the asked N + the 12 MP Hybrid plan's ultrashort / bracketed frames (from the viewfinder's or the hand-set N
  exposure; manualLong for a long hand-set shutter), within the memory's count. ZSL was not the cause of the slow merges (P204).
- Not yet shot on a phone in PSL with the plan (the PSL check shot ran 30936: 19 N + 1 ultrashort, 179 s merge = P204's cause).

### P203 - 12 normal frames by default for FULLRES 50 MP (owner: «сделай по дефолту 12 обычных кадров для 50мп»)
- pref_scam_hybrid_fullres50_frames default 12 (was 0 = the merge's 20); a stored 0 moves to 12 once (FULLRES50_FRAMES_REV).

### P204 - the marked native merge 35-50x slower on the Adreno 750 (owner: «выясни почему у нас раньше обработка была быстрее»)
- Kept 19-frame X7U burst (Download/SCAMERA/scam_burst-20261017-185248.bin), replays on the OPPO: merge pass 216 / 190 / 128 s
  with the outlier test on, 3.8 s with the merge program unmarked. Not the cause: the 11:10 worker (128 s too), ZSL vs PSL (PSL 179
  s), the generic program (154 s), cellClip 0 (128 s), 0 fixed-pattern flags (130 s). The morning 20 s shot was bright (outlier
  key 67 > hotMaxKey 30: no marked strips). The cause: natBaseFill inside the marked fast merge (Adreno 750 code generation; 12 s
  with it removed). Fix: the marked merge writes NAT_FILL_MARK, a NAT_BASEFILL pass of the same program text fills it.
- Checked: kept burst md5 fde0c549 identical, merge pass 128 s -> 12 s, total 141 -> 24 s; the 8 reference bursts identical
  (syn_b1 5b988db1, x7u_1x 52bcce71, syn_b2 7c1a3756, syn_b4 5e586261, hand db8d449d, isz2 d5dceb41, v10_1 161b2e8e, v10_2
  fe39aa5e); in the app (30937, X7U indoors ISO 1600, 12 N + 6): merge 19 s, photo 26 s after the press.
- Open: the native fixed-pattern test flags 13-25k sites/MP on a still (table) burst - texture taken for fixed pattern; worth a
  plausibility cap (quality, not speed now).

### P205 - DNG band in Lightroom on X100U / X300U (owner, 2026-10-17, SCA_20261017_153558.dng)
- OpcodeList2 GainMap area written Top, Left, Right, Bottom (file: (1,1)-(4079,3071) on 4080 x 3072): the columns past 3071 got no
  lens-shading gain in editors that apply opcodes. Fixed order, exclusive Bottom / Right, spacing 1 / (n - 1). Checked with an
  arm64 build of tiny_dng_writer on the OPPO. Not yet: a phone DNG opened in Lightroom.


### P207 - X100 Ultra FULLRES without the HAL's remosaic (owner, 2026-10-17: «Нам нужен фулрез без ремозаика»; X100U log on 30912)
- Log: main camera 0, 50 MP row 0 (8192x6144 @15) streams a plain Bayer (MosaicStream block 1, 101451 / 0 / 0 tile votes): the
  HAL's hardware remosaic. Each still was refused («Native FULLRES mosaic does not match its sensor profile»), twice on row 0, because
  a refused vivo still never noted its row (only OPPO did, P192).
- Its sensor-mode table (0x8038, camera 0 = 2): 8192x6144 rows 0 @15, 11 @19, 31 @19. Fix: the own mosaic tries 11 / 31 (row 0
  stays the «FULLRES: аппаратный ремозаик» row, P194), counted under the key _r11; a still refused for its mosaic / mode notes the
  row on vivo too.
- «Наш ремозаик» off works as built (binned Bayer merge, DNG W/2 x H/2) only on a stream that is a Quad / Tetra mosaic: a HAL
  remosaic (block 1) is a plain Bayer the switch cannot undo.
- Tele 200 MP (camera 4, row 0 16320x12288 @7, its only 200 MP row): the camera provider aborts «nodeTimeout ... node(RtNode),
  SCENE_FULL_BYPASS» on the first request (no preview frame, camera 4 gone until the provider restarts). Open: a RAW-only session.
- Checked: VivoNativeFullResTest, VivoSdkFullResModeTest, VivoSdkFullResCaptureTest, OppoNativeFullResTest (35/35). Not on the X100U.

### P208 - X100 / X300 Ultra 200 MP the stock camera's way (owner's X300U logs and stock dumpsys, 2026-10-17)
- Our 200 MP session held the sensor in its 200 MP row (forceSensorMode 0 on the session and every request, one RAW stream at
  16320 wide): X300 main - onError 4 and the provider abort (Usecase::DumpSystemEvent from the recovery thread, 70 ms after the
  session); X300 tele and X100 tele - no frame for 3 s (X100: nodeTimeout RtNode). The 50 MP rows work the same way, so not the size.
- Stock camera (com.android.camera) in 200 MP, dumpsys: operation mode NORMAL, streams 1440x1080 viewfinder, RAW16 16320x12288,
  YUV 16320x12288, RAW16 4080x3072; the repeating request targets the viewfinder only, at 30 fps (the normal mode), no vendor
  key in it: the HAL switches the sensor for a still of the full-size stream.
- Fix: Profile.stillOnly for every 200 MP size of the X100 / X300 Ultra: a viewfinder RAW reader at the camera's largest listed
  RAW size (normal mode, live RAW viewfinder) beside the full-size RAW reader; no forceSensorMode on the session or the
  viewfinder; the stills target the full-size reader (scam_dev "vivo_fullres200_force 1": with the mode key; "vivo_fullres200_stock
  0": the route before). The stills' checks stay (mode index 0, Tetra block) and say what the HAL gave.
- Checked: capture unit tests 42 classes / 250 tests. Not on a phone: APK 30965 and deliverables/X300U-200MP-тест-30965.txt sent.

### P209 - stripes in the OPPO FULLRES viewfinder and photo (owner's X7 Ultra video, 2026-10-17 23:00; «проблема с flicker»)
- NativeRawExposure snapped every shutter of 10 ms or more to whole 10 ms (100 Hz flicker, 50 Hz mains). Under 60 Hz mains (the
  camera reported 120 Hz: «mains flicker 120 Hz (camera)») 30 ms is 3.6 flicker periods: rolling-shutter bands in the viewfinder
  and in every frame of the series.
- Fix: the period from «Частота мерцания» (PreferenceKeys.getAntibandingHz: Auto = the camera's report, else the region; 0 = no
  snap), set when the AE is made and checked on every metered frame (a change re-splits the exposure and rebuilds the requests).
  120 Hz: 8.33 / 16.7 / 25 / 33.3 ms.
- Also: the P208 dev keys vivo_fullres200_stock / vivo_fullres200_force on the developer page (DevKeysCoverageTest).
- Checked: all 202 unit-test classes (1175 tests), check_ui_language, check_settings_model. Not on the phone yet.

### P210 - X100 Ultra: no FULLRES 50 MP button on 0.6x / 1x (owner, 2026-10-17, log-2026-10-17 on 30966)
- Main (0 / 2): row 0 gives the HAL's remosaic (block 1), rows 11 and 31 give no frame (3 s stall), on every build today (11:58,
  14:07, 19:18): all own rows fail, the size goes off, the button hides. Ultra-wide (3): row 0 the HAL's remosaic, row 9 no frame.
  Telephoto (4) 50 MP: own Quad, works.
- Fix: the ultra-wide's row 0 is its hardware-remosaic row (as the main's, P194): with «FULLRES: аппаратный ремозаик» both give
  50 MP; their own mosaic tries the rows that are not the HAL's remosaic (main 11 / 31, ultra-wide 9; failure keys _r11 / _r9).
- Open: an own Quad on the X100 main / ultra-wide needs a mode that streams; rows 11 / 31 / 9 never gave a frame in a RAW-only
  session (maybe the still-only route of P208 with the mode key on the stills).

### P211 - «Тип сессии камеры» (owner, 2026-10-18: «проверь работу этого параметра»)
- How it works: @SensorConfig CaptureController.sessionType, per module (pref_sensorconfig_<slot>_sessiontype, read for the active
  module of the camera: ModuleSensorSettings.runtimeScope), injected before each session, passed as SessionConfiguration's
  operation mode. Checked on PHY110 (snapcam 30938, module 1x ID 2 = 32777): dumpsys media.camera «Operation mode: CUSTOM
  (32777)», frames at 30 fps. The value only applies when the session is made again (camera reopen / module switch), not when
  the settings page closes.
- Danger: the same 32777 with FULLRES on (OPPO agingtest mode 35) - kernel panic and reboot of the phone («Unable to handle kernel
  paging request at virtual address fffffffffffffffc», BootReceiver 00:10:50), reproduced once; the owner's own log of
  2026-10-17 20:30:43 (32777 injected, «full resolution on», then 13 min of nothing) is the same.
- Android takes 0, 1 (constrained high speed: refused with our RAW streams) and 0x8000..0xFFFF (vendor); 2..0x7FFF fail the
  configuration. There was no fallback: a refused mode left the camera without a session.
- Fix: sessionOperationMode(setting, fullRes): FULLRES (vivo native / SDK, OPPO, Pixel max-resolution) always 0; only
  0x8000..0xFFFF pass; a session refused with the vendor mode runs again in mode 0 (this camera, this run) with a toast; the
  mode used is logged. Description says so. SessionOperationModeTest (CI list).
- The test phone still holds 32777 on its 1x module with FULLRES on (snapcam 30938, old code): opening it would panic again
  until the setting is 0 or the build has P211.

### P215 - merged DNG: purple pulled highlights, banded pushed shadows (owner, X200 Ultra, 2026-10-18; raw.zip / dng.zip)
- Measured on SCA_20261018_085452.dng / SCA_20261018_091130.dng (30949, 12 MP, merged 16-bit, BlackLevel 4100, WhiteLevel 65535):
  BaselineExposure 3.996 / 4.000 = headroom 16 (the cap), set by a few stray sites (99-167 G at >= 16 x N white, overshoot next to
  lamps); every highlight clipped even in the 1/8 ultrashort sat at 8 x N white = code 34896 / 34817 in all three channels, half
  of WhiteLevel. A raw converter took them for unclipped grey; after its white balance (R x2.5, B x1.6) they turned purple.
  The N range had ~3840 codes: dark areas 5-40 codes above black with 0.7-1.5 codes of noise and ~5 distinct values per 16x16
  block -> bands when the shadows are pushed. Stock SRAW (14 bit, black 0) clips each channel at 16383 x AsShotNeutral (R 6213 /
  6621, B 11060 / 10519): neutral after the white balance.
- Also found: our DNGs carry no AsShotNeutral and no ForwardMatrix (X200U and X100U files). The native writer and the APK's own
  libdngCreator.so write both tags from finite values (checked on the phone through a fake JNIEnv), so the app passes non-finite
  values on those shots; cause not seen yet (no X200U log) - DngCreator now logs non-finite tags with their values.
- Fix: hybridDngCeiling = 1 / the shortest ultrashort TET (x8; the measured ratio), 1 without one, <= 16; no site above it; the
  headroom is the highest site up to it; a site within 1e-4 of the ceiling is written at 65535 = WhiteLevel (clipped for the
  converter too, one bit more for the N range); tuning dngDither 1: deterministic triangular +-1 code dither of the 16-bit codes.
  RGB output untouched: 6 reference bursts byte-identical, v10_1 / v10_2 differ only after byte 150405120 (= the RGB size, the
  DNG trailer). tools/check_dng_ceiling.cpp (CI). Demo: the owner's two DNGs rewritten with the rule (deliverables/dng-fix/).
- Open: AsShotNeutral / ForwardMatrix root cause (next X200U log); a 16-bit float DNG option if the shadows still band.

### P216 - Bento highlights above 4 x white: the window of the x8 ultrashort (owner, 2026-10-18, SCA_20261018_091910 on 30949)
- Owner: the bridge window came out white with an orange smear where stock, ArkCam and ARK show the sea and a crane; «исправь
  ... даже если я поставлю ультракороткий кадр 16х»; then «результат между ArkCam и стоком ... света более мягкие» and «чтобы не
  было резких белых пятен».
- Kept burst replayed on the OPPO (worker 6be17f97): the merge holds the window (sky 3.7, sea 2.4, crane 12-17 x white, the
  crane's top lines clipped even in the ultrashort: G 8). Lost in the ARK tone (production shaders on the desktop GPU with the
  log's AE): ArkAe.clip = min(gmax, 4) is the native histogram cap, yet (1) the P154 landing measured the window against 4 and
  painted it white with the colour of its 12 px ring (the orange smear: the crane's warm rim), (2) the 1.23 kernel's shoulders
  (everything above 1 into <= 1.35, then a roll-off from 0.5) put 1.5 .. 8 x white on one grey (crane 0.93, sky 0.91), (3) the
  bounded detail reference min(ae Y, 1) gives no detail above white, (4) effH (P159: p98x / p995x) left the window in the
  saturated part of the fusion.
- Fix, only with real headroom (ArkAe.Result.hr: Bento merged and gmax above the cap; the maximum, at most 16 x the clip):
  the landing on hr; ArkCam 1.58's max-channel Reinhard shoulder on the tonemapper top (libopenhdr_engine: knee 0.6, room
  0.35 - here 0.5 / 0.4: the knee keeps a crane's clipped lines off 255, the limit a little above white lets a sun climb
  steadily into its white core); the highlight peak half way (log, ark_hr_mix
  0.5) from P159's to p99.9 when an AREA lies beyond the ceiling (0.3 .. 0.8 % of the frame; the window 1.4 %), the strength
  capped by log2(hr ae / bt) / 1.5 instead of 2; a full-size highlight detail term
  (ark_hr_detail 0.35 of the log contrast against the 2 x 2 grid, -0.03 .. +0.12 L, fading towards white). Without Bento or
  without data above 4 every value and pixel is as before (hrU 0 render byte-identical on the whole shot).
- Checked: harness (scratchpad tools/arkwin.py: ScamRgb recovery + ark/low, bracket_dn, gf, box, ab, hlmask, combine on the
  replayed RGB): window glass p50 0.984 -> 0.939 (ArkCam 0.982, stock 0.872), no pixel at 254, crane / horizon / sea back,
  rest of the frame unchanged; a x16-like stretch of the same data (gmax 34) gives the same look (p50 0.942). ArkAeTest 5/5
  (new case), tools/check_ark_headroom.py (new, CI), check_ark_highlights, check_highlight_landing, tone_ops, colour_look,
  film_lut, ark_ae_gpu, viewfinder_look, dark_fade PASS; glslc on the combine variants. check_ark_tone fails its two synthetic
  cases identically on main (not in CI).
- Owner's sun scene (scam_burst-20261018-101655, ultrawide, x4.3 Bento: the ultrashort is at the 0.036 ms minimum, the sun
  clipped in it, 0.12 % of the frame beyond 4): «В такой сцене небо не должно темнить» - the p99.9 pull (the sun's glow)
  had darkened the sky by 5 - 12 codes, hence the area gate; now effH as before, sky / sea / deck unchanged, the sun a
  soft disc rising steadily into white. «Солнце должно быть мягким без каких-либо ореолов» / «внутри солнца есть ореол»:
  with room 0.3 the rim stood on a 251 plateau with the clipped core (recovered at the white bound, 2.4 x the rim) as a
  254 dot, and the highlight detail dug a 1 - 2 code trench beside it; now room 0.4 and no darker detail where the
  neighbourhood is much brighter (ratio < 0.55 .. 0.85: the rim of a light, not texture).
  «Небо тёмное у обоих вариантов ... не затемненное и мягкое»: the darkening came from ArkAe pulling the bright scene to the
  target grey (ae 0.861: geo 0.174 > 0.15). With real headroom the meter no longer takes ae below 1 (the captured exposure;
  the highlight-overflow limit may still lower it): ae 1.0, sky / sea / deck +5 .. +9 codes, 0.04 % of the pixels darker
  (the rim of the sun's disc). Harness with the shot's vivo ISP matrix (170 -37 -5 / -19 179 -32 / 3 -90 215) / 128. The window-up scene (101853, gmax 3.2 < 4) renders byte-identical.
  Harness with a radial lens-shading model (log: corner 7.3 / edge 2.4 on the ultrawide, 3.0 / 1.65 on the main) matches
  the owner's JPEG of the window (p50 0.987 vs 0.984); new p50 0.954.
- Not yet: a phone shot with the build; the crane's colour where two channels clipped in the ultrashort (recovered neutral at
  R's level, 2 x its body) stays a hard line in the data.

### P217 - FULLRES 50 / 200 MP come out at 12 MP, yet take as long as 50 / 200 MP (owner, 2026-10-18: «Изучить почему фото в 50
и 200мп выходят в 12, при этом обработка долгая словно обрабатываются 50 и 200 соответсвенно»)
- To study. First facts from SCAMERA-debug.log (2) / (3) (X200U, 09:57 - 10:00, SCA_20261018_095801 / 100012 = 3060 x 4080):
  «HYBRID MOSAIC BINNED: block 2 without remosaic (mosaicRemosaic 0): 35 of 35 frames binned to plain Bayer 4080x3072»,
  «hybrid output 4080x3072 (scale 0.5)» - «Наш ремозаик» off bins the Quad mosaic, so a 12 MP result is the documented
  behaviour (P207); the time goes to 34 - 36 full-size frames (N 8 - 10 from the ring + 26 after the shutter, requested 30,
  «series cut=memory»), the plain-session switch and the worker's reading / binning before the merge (spawn 5.1 s -> f6 18.4 s,
  shot 23 s; the 10:00 shot 15 s).
- Questions: should a binned FULLRES shot bin on the sensor (the 12 MP mode) instead of capturing 50 / 200 MP; why 26 frames
  after the shutter for FULLRES (owner earlier: «для фулреза 1 длинный достаточно», part B); where the worker's 13 s go
  (reading the memfd, mosaic detection - part B «skip the redundant worker mosaic detection» -, binning); what the UI should
  say when the remosaic is off (the FULLRES icon promises 50 / 200 MP).
- Studied (2026-10-18, log (2) 09:58 tele 8160 x 6144, 36 frames of 95 MB, total 22.9 s): the 12 MP is P195 as the owner
  asked it («отключать наш 2х2 и 4х4 ремозаик чтобы получать фото без него»: every colour block binned, the photo at W/2 x H/2,
  W/4 x H/4 for Tetra). Where the time goes: 26 frames after the shutter 2.8 s; the burst moved into the shot arena 1.3 s;
  the worker's kept burst (scam_dev / the owner's «keep burst», scam_burst-*.bin in Download: 3.4 GB) ~7.2 s before it starts;
  the worker's mosaic detector 0.7 s (the header already says block 2); binning 36 frames 3.2 s; the merge and the tone the rest.
  Without the kept burst the shot is ~16 s. Part B takes the capture-side costs (one long frame for FULLRES, the arena copy
  during the capture, no second mosaic detection). Open for the owner: a binned FULLRES gives the sensor's own 12 MP binning
  in software - whether FULLRES should then capture in the normal (sensor-binned) mode instead (same size, a fraction of
  the time).
- P219 (2026-10-18): the one long frame applies to the FULLRES routes merged natively; this log's case, a binned FULLRES shot
  («Наш ремозаик» off), keeps 3 + 3 on purpose (the binned P195 merge takes every frame) - open for the owner whether «для фулреза
  1 длинный достаточно» covers it too (then HybridPlan.fullResLongFrames returns 1 for every FULLRES plan).
- DECIDED 2026-10-18 (the owner: «Все решения принимай сам»): (1) a binned FULLRES shot keeps capturing in the full-size mode -
  P195 was asked to compare the binned mosaic with our remosaic, so the route stays; (2) the owner's «для фулреза 1 длинный
  достаточно» covers it too: HybridPlan.fullResLongFrames returns 1 for every FULLRES plan (3 + 1 instead of 3 + 3: -0.3-0.5 s,
  -192 MB at 50 MP); (3) the «Размер фото» sheet says it on the 50 / 200 MP rows while «Наш ремозаик» is off: «снимок выйдет 12
  Мп». The kept-burst cost (scam_dev keep burst, ~7 s) stays a diagnostics choice; the capture-side costs are part B.

### P218 - «Настройки по модулям» only on the main settings page (owner, 2026-10-18: «группа настроек "Настройки по модулям"
дублируется в нескольких местах, исправь чтобы было только на главной странице»)
- The group (P189: each module and its FULLRES 50 / 200 MP profiles) was reachable from the main settings page and repeated on
  «Камеры и сенсоры» as the block «Профили модулей»: the per-module switch, a row opening the group and «Копировать настройки
  между модулями» - all three are inside the group itself. The block is gone from «Камеры и сенсоры»; its note («При первом
  включении используются текущие настройки…») moved under the switch in the group. The settings search still finds the group.
- Checked: SettingsMenuTest (the cameras page shows neither row; copying is reached inside the group), ModuleProfilesPageTest,
  ModuleProfilesCarryTest, check_ui_language.

### P219 - every frame into the native mosaic merge, one long frame for FULLRES, no second mosaic detection (owner, 2026-10-17:
«сделай так чтобы брались все кадры, но для фулреза 1 длинный достаточно»)
- Evidence: the native merge (FULLRES Quad / Tetra, ISZ mosaics) picked one ultrashort and one bracketed frame
  (hybridReconstructMosaicNativeMerge), so every owner FULLRES shot logged «HYBRID SERIES CUT: 10 of 14 / 8 of 12 / 14 of 18
  (frame rules)» and «HYBRID BENTO ... frames=1» although the plan captured 3 + 3 after the shutter (HybridPlan.build); the
  split (mosaicPath 0) has merged up to bentoFrames ultrashort and every bracketed frame since P63. The worker's mosaic detector
  ran on every mosaic burst only for its log line when the header already gave block 2 / 4 (roadmap speed-ux B1).
- Worker: hybridMosaicPick, one pick for the split (its P63 rules, unchanged) and the native merge. Native (tuning
  mosaicNativePick 1, default): every ultrashort frame Bento merges (bentoFrames, at most 3, within x1.3 of the shortest) and every
  bracketed frame; the first of each keeps its slot ahead of the N frames as before, the others take what the N frames leave - an
  ultrashort frame an N slot where that lowers the noise (review round below) - so the pick always holds the old ultrashort and
  bracketed frames (mosaicNativePick 0 = the pick before P219). Nothing else had to change: the merge program, the Bento
  validation and the Shasta gates work per frame role (the marked merge program untouched).
  hybridMosaicBlock: header block 2 / 4 -> «HYBRID MOSAIC: header block 2, detection skipped (the app's block)», no detector.
- Found on the way (pre-existing, exposed by the earlier merge start): a chroma context built ahead (chromaPrewarm) and never
  taken - every 50 MP merge, its median runs on the CPU - was torn down in a static destructor at exit, and eglMakeCurrent
  crashed there (SIGSEGV in libGLESv2_adreno after «SCAM CAPTURE OK»; the app counts a non-zero exit as a failed merge) whenever
  the vendor CRE library had loaded after the context was built. The integrate worker crashes the same way with SCAM_NO_WARMUP=1
  (the CRE loaded only at the alignment); the new worker crashed in 2 of 4 50 MP replays before the fix. Fix
  (scam-chroma-gpu.h): an untaken context is left to the process exit.
- App: HybridPlan.fullResLongFrames / withLongFrames - a FULLRES plan keeps all its ultrashort frames and the first long frame:
  native 50 MP ZSL (takeNativeZsl) and PSL (pslPlan, manual long shutter too: vivo / OPPO native, block from the profile), the
  Pixel / Snapdragon MAXIMUM_RESOLUTION session (block 0). 12 MP plans are untouched; «Наш ремозаик» off on a Quad / Tetra or
  unknown block keeps 3 + 3 (the binned P195 merge uses them all); a Bayer FULLRES stream (block 1) takes one long frame. The
  freed frames go to N where the memory budget sized the series. Not touched: the 200 MP series (N + one ultrashort, P179) and the
  X200 Ultra's SDK route (N only), which have no bracketed frames.
- Checked: tools/check_mosaic_pick.cpp (new, CI; built with the NDK and run on the OPPO): 200000 random bursts - the split pick
  frame for frame as before, mosaicNativePick 0 = the old native pick, mosaicNativePick 1 holds it with the same N frames and
  takes every frame with room; the owner's 12 N + 3 + 3 shot 14 -> 18 frames; the detector skipped / still run for block 0.
  HybridPlanTest (+1) and FullResZslTest (+1: 3 ultrashort + 1 long through the native capture); the whole CI list 205 classes,
  1278 tests, 0 failures; check_ui_language, check_settings_model PASS. OPPO replays against worker_int: syn_b1 5b988db1, x7u_1x
  52bcce71, syn_b2 7c1a3756, syn_b4 5e586261, v10_1 3e84061b, v10_2 12389449 byte-identical (v10: 30 of 35 frames, the
  mosaicFrames budget leaves no slot after the N frames); hand db8d449d -> fef2af11, isz2 d5dceb41 -> d72715ce (27 of 27 frames,
  5 bracketed merged instead of 1; Bento not applied there): darkest 10 % tiles' fine band x0.61 / x0.76, textured bright tiles'
  detail bands x1.00 (no blur), merge +0.4..1.1 s, peak RSS +150 MB. With mosaicNativePick 0 all 8 identical to worker_int.
  Owner's kept 50 MP X7U bursts (app tuning): 185248 (17 N + 3 + 3) fde0c549 -> 8c0090ff, 23 of 23 frames, Bento frames=3 (same
  mask, 296816 px), high-pass noise inside it R x0.59 G x0.59 B x0.59 (1/sqrt 3 = 0.58), R/G 0.577 -> 0.575, B/G 0.766 -> 0.764,
  level x0.997; one more bracketed frame merged (the other two refused by the Shasta motion gate): darkest tiles x0.94, textured
  detail x1.00, no NaN / Inf; merge 15.4 -> 16.1..16.8 s, peak RSS 3650 -> 4280 MB. 191825 (19 N + 1 ultrashort) d7b19079
  identical. Mosaic detector 0.52..0.61 s in 7 runs of worker_int, 0.2 ms now.
- Not checked: no shot with the app (camera app not run); vivo X200 / X300 Ultra, Pixel, the Tetra / 200 MP routes and the
  split on the phone; the peak memory of a 16-frame FULLRES merge on 8-12 GB phones; capture time saved by the 2 long frames
  (not measured, roadmap estimate 0.35-0.5 s, more at night).
- Review round (2026-10-18). A budget short of the burst (v10_1 / v10_2: 30 of 35 by mosaicFrames; a memory budget at 50 MP):
  the reviewer asked for every ultrashort frame ahead of the N frames, as the split and the plain Bayer app keep their extras.
  Measured that way on 185248 in 14 frames (mosaicFrames 14 standing in for the memory budget): 10 N + 3 + 1 instead of 12 + 1 + 1,
  inside Bento (0.6 % of the frame) high-pass noise x0.62, over the other 99.4 % x1.11 (darkest 10 % of the tiles x1.12, their
  coarse band x1.19; the bracketed frame then refused by the motion gate); v10_2 (night: 0.004 % of the cells clipped, «BENTO: not
  applied (not enough clipping)») lost an N frame for nothing (28 merged, not 29), and v10_1 would (its ultrashort frames are
  not aligned: no Bento line, 29 of 30 merged). So hybridMosaicNativePick moves an ultrashort frame after the first into the slot
  of the N frame farthest in time only where that lowers the noise of the picture: f (1 - sqrt(u / (u + 1))) > (1 - f)
  (sqrt(m / (m - 1)) - 1), f = the share of Bento's cells the base clips (hybridMosaicBaseClipped: bentoBase's scan on the base
  binned as the merge bins it, without the site-class gains - 16.48 % against Bento's 16.21 % with them on 185248 at
  bentoHighlight 0.01), m = the base and the N frames kept, u = the ultrashort frames in: the second of 12 N from 13 % of the cells
  clipped, the third from 21 %, the second of 28 N from 5.9 %; never below usFrom - 2 N frames (two, one for a huge burst). Log «HYBRID MOSAIC NATIVE: 14 of 23 frames: 1 ultrashort frames picked, the base clips
  0.483 % of Bento's cells (...)». The scan runs only with a budget short of a burst with 2+ ultrashort frames: 17-31 ms at
  12 MP, 83-110 ms at 50 MP. Bracketed frames after the first keep taking the slots left (the motion gate refused 2 of 3 on
  185248).
- Chroma context: chromaGpuPossible now takes the result's pixels against the median's own 16.8 MP (it took any sensor up to 4x
  that); the native merge computes its result (Quad / Tetra T1 W x H, x4 on the natively merged 2x grid, T2 a quarter), the split
  W x H, so a 50 MP Quad merge no longer builds a context its CPU median never takes; 12 MP results prewarm as before.
- Memory (reviewer: RSS +160 MB per 50 MP frame): MemAvailable (/proc/meminfo, ~4 samples/s) dropped by 1517-1594 / 1883-1975 /
  2105 / 2542-2565 MB on 185248 with 8 / 14 / 16 / 23 frames picked: 66 MB per frame and ~1.0 GB fixed (the model's 20 B/px).
  RssAnon +32 MB per frame (the binned copy 25 + ~7), the rest the GPU strip windows (~31 MB: the 3.8 KB per column of
  VivoSdkFullRes.seriesFit); RSS +137 MB per frame, 100 of them the burst's own pages (RssFile; in the app its memfd, no new
  memory). hybridMosaicNativeBudget counts 25 MB a frame. Left as it is: the default 50 MP shot is a series (P203, 12 N), sized
  by the app's seriesFit (RAW + binned + 3.8 KB per column = 156 MB a frame, 40 B/px fixed, against the measured 100 + 66 MB and
  20 B/px) and floored in the worker; the worker's model sizes only a «Кадров в снимке 50 Мп» 0 (auto) shot, where the measured
  cost would give 6 frames instead of 17 at the 1733 MB of P191's shot (merged 20 of 20 on the OPPO; check_hybrid_series pins
  17) - an owner decision. 30 N + 3 + 1 extrapolates to ~3.3 GB of MemAvailable besides the 3.4 GB burst.
- Not changed: a binned FULLRES shot keeps 3 + 3 (P217 line: open for the owner). The empty /data/local/tmp/scamera-pick-UjZbpz of
  the first round's check is gone; the check now runs with TMPDIR in its own p219f_ directory.
- Checked: tools/check_mosaic_pick.cpp (CI; the native picks against the old ones for a base without clipping and one clipped all
  over: N frames give way only to ultrashort frames without a free slot, never below the floor; the 13 / 21 / 5.9 % thresholds;
  short budgets; hybridMosaicBaseClipped = bentoBase's clippedFraction of the binned base on 300 random bases, any CFA; the
  prewarm gate) PASS on the OPPO (NDK clang, also with UBSan traps), and the 10 other host checks with the worker headers PASS
  there; CI's g++ ASan build not run (no host compiler). Final worker e51885ef against worker_int: syn_b1, x7u_1x, syn_b2,
  syn_b4, v10_1, v10_2 byte-identical (v10: «the base clips 0.000 % / 0.004 %», one ultrashort frame), hand fef2af11, isz2 d72715ce
  as the first round; 185248: default 8c0090ff (= the first round, 23 of 23), mosaicFrames 14 7ed5796e (= the first round's
  worker: 0.483 %, 12 N + 1 + 1), bentoHighlight 0.01 (16.5 % clipped) 11 N + 2 + 1 with «BENTO: applied ... frames=2», no NaN /
  Inf; SCAM_NO_WARMUP=1 rc 0 twice; every replay rc 0. Java untouched in this round (no unit-test rerun).

### P220 - merge worker watchdog on progress; the merge's progress in the processing ring and the notification (roadmap
stability 8, speed-ux C1; owner: a processing progress ring and a notification)
- Evidence: the worker ends a hung merge only by its alarm(840) (scam-neural-worker.cpp), the app waits 900 - 3600 s
  (ScamHybridBurst.workerTimeoutSeconds), and the SIGALRM exit is neither a timeout nor a named crash stage, so the conservative
  retry ran for up to 840 s more: ~28 min with the FULLRES shutter held (P200). A pass can be silent for minutes (owner's log
  2026-10-17 18:25:45 «HYBRID MOCHI: off» -> 18:29:51: the 245 s native merge before P204), so log gaps say nothing. The ring
  ran on the last shot's time only (ProcessingRing: after a 12 MP shot a 50 MP ring sat at 94 % ~20 s); the keep-alive
  notification was indeterminate.
- Worker (scam-progress.h, new): pass() at each stage (cre, burst, map, keep-burst, prepare, mosaic-detect / mosaic / mosaic-bin /
  mosaic-split / mosaic-binned, align, bento, local-align, gpu, mochi, merge, finish, write), step() / at() as units complete (CRE
  groups, frames, binned rows, Mochi frames, GPU strips read back = output rows, 64 MB kept-burst writes), tick() per GPU program;
  FenceWait round the strip readback (the 120 s glClientWaitSync and the maps behind it). A heartbeat thread prints every 5 s
  «HYBRID PROGRESS pass=<name> done=<k>/<n> seq=<events> slowest=<ms>[ fence=<ms>]» (slowest = the longest gap between two
  events, the open one excluded), stopped before «SCAM CAPTURE OK». The 840 s alarm stays. Replay-only hook SCAM_STALL_STRIP=
  <strip>,<s>[,fence] (the app never sets it), SCAM_PROGRESS_MS for the period. No merge program text touched.
- App: WorkerWatchdog (new) waits in 1 s slices; armed by the first heartbeat; seq unchanged for max(90 s, 4 x the slowest step:
  the worker's or the gap the app saw) -> the worker is killed (StalledException); time the app itself did not run (a slice
  back > 5 s late: frozen / asleep) is not counted. ScamHybridBurst.conservativeRetry: no retry after the outer timeout (as before)
  or a stall with fence >= half the silence (hung GPU: the base frame alone at once); a CPU stall, a crash and SIGALRM keep it.
  scam_dev worker_watchdog 0 off / 1 log only / 2 on (default), on «Для разработчика» (DevKeys). MergeProgress (new): pass table ->
  share of the merge (the strips 26 - 95 %), never back in a job. Ring: ScamProcessor sends ProcessingProgress.large() above
  16 MP (the creep goes only to 10 % in its first 7 s instead of running on the 12 MP time) and the heartbeat's share; measured
  mode carries the ring from where it stands to 55 % (moving on at the measured speed for at most one heartbeat, holding when the
  worker is slower, never back), after the merged milestone the rest creeps on the last measured shot's post-merge time. A merge
  shorter than 5 s reports nothing: the ring as before (and a large shot no longer sets the 12 MP time estimate). ProcessingKeepAlive:
  a merge running >= 10 s with the app not in front (process importance > FOREGROUND) shows «Склейка снимка: N %» (determinate,
  silent, only-alert-once) in its notification, only if POST_NOTIFICATIONS is granted and notifications are on; never asks;
  back to the plain one in front / after the merge.
- Checked: WorkerWatchdogTest 9/9 (new: a fake worker on a fake clock silent after 30 s is killed at 120 s, one that reports for
  600 s with 35 s steps survives, 40 s steps -> 160 s threshold (150 s silence survives, the stuck one dies at 360 s), fence stall
  -> no retry, CPU stall -> retry, log-only / off never kill, no heartbeat -> only the timeout, a 120 s app freeze not counted;
  the parse fixture is a real worker line), MergeProgressTest 4/4 (new), ProcessingRingTest 7/7 (+4), DevKeysCoverageTest; the
  whole CI list 207 classes, 1295 tests, 0 failures; check_ui_language, check_settings_model PASS. tools/check_worker_progress.cpp
  (new, CI) built with the NDK and run on the OPPO: PASS. OPPO replays, worker e51885ef (HEAD, = P219 final) vs 86b56fda: syn_b1
  5b988db1, x7u_1x 52bcce71, syn_b2 7c1a3756, syn_b4 5e586261, hand fef2af11, isz2 d72715ce, v10_1 3e84061b, v10_2 12389449 -
  whole outputs byte-identical (cmp), 0 - 2 heartbeats each; owner's 50 MP scam_burst-20261017-185248 2bd67597 identical (also
  with a 1 s heartbeat: mosaic -> mosaic-bin 3072/3072 -> gpu -> merge 128 .. 5888/6144 -> finish, merge 17.2 vs 17.3 s). End to
  end on the OPPO (real worker + WorkerWatchdog in an app_process harness, syn_b1): strip 3 held 200 s -> killed at 95 s,
  gpuFence false; held in the fence wait -> killed at 95 s, «waiting for the GPU 93 s», gpuFence true; held 60 s -> finished,
  5b988db1; log only with 120 s -> one line, finished; off with 100 s -> finished. Phone files p220_* / job_p220 removed.
- Not checked: no shot with the app (camera app not run): the ring and the notification on screen, the kill / fallback inside
  the app (WorkerSpawn SIGKILL path), a real GPU hang; CI's g++ ASan build of the new check (no host compiler); vivo / Mali.
  Seen, not changed: CameraFragment still posts its old indeterminate «processing» notification (id 1) at every shot.
### P222 - FULLRES stills back to back (roadmap speed-ux A3; the owner's X7 Ultra log 2026-10-17; done in code, NOT tested
on a phone)
- Evidence: the stills of a FULLRES 50 MP series arrived in groups of 3 with 4-5 preview frames between them - shot 1 resultFrame
  116-118, 123-125, 130-132, 138-140, 145 (13 stills in 30 frame slots, 1.66 s); the 250 ms PSL shot 173-175, 180-182, 187-189,
  194-196 (12 in 24 slots, 5.5 s). Cause: NATIVE_IN_FLIGHT = 3 (P179): the next still was sent only when the oldest had its RAW,
  result and sequence, ~5 slots later, and the repeating preview took the slots meanwhile; only shots above 100 MP stop the
  preview. The RAWs are copied and closed on arrival (RAW16Saver.addImage), so more requests in flight hold no more of the
  RAW reader's 4 buffers.
- Now (capture/FullResInFlight): a native FULLRES series (vivo native rows of the X200 / X100 / X300 Ultra, P194's Bayer rows,
  the OPPO native modes - all VivoSdkFullResCapture) keeps min(series, 6) stills in flight up to 100 MP; above 100 MP P179's 3
  (measured on the X200 Ultra with the preview stopped and the memory at its edge; the preview stop is unchanged); the X200
  Ultra's SDK route stays one at a time (each still carries the SDK's capture id and display timestamp; nothing measured
  queued). scam_dev.txt "fullres_in_flight N" (1..8, on «Для разработчика») sets N on every native route, 3 = before P222;
  other values are ignored (logged). No other FULLRES route has such a count (the Pixel 10 Pro FULLRES takes the regular shot
  path, whose burst goes out at once).
- Fallback: a series sent with more than 3 in flight that fails - a capture failure, buffer lost, aborted sequence, timeout,
  empty / invalid RAW, RAW copy error, invalid / mismatched metadata or timestamps, an incomplete PSL series, a missing RAW
  frame, a camera error under the series, or a 50 MP ZSL shot that completes with stills after the shutter missing - is
  noted per camera and size (module_fullres_inflight_fail_<cam>_<w>x<h> = generation TRIALS 1, the reason beside it), and
  the default is 3 from the next series on (an explicit scam_dev value still wins). Not counted: a wrong sensor mode / mosaic
  (the row memory of VivoNativeFullRes / OppoNativeFullRes), the camera closed or disconnected under the series (the app left,
  a camera switch), a failure before the series was sent. Bumping FullResInFlight.TRIALS tries 6 again in a later build.
- The still timeout (8 s) now runs from the moment a still becomes the oldest one in flight (SerialRawCapture.Listener.head),
  not from its submit: with 6 queued at a long hand-set shutter (1 s+) the last ones would have timed out while waiting for
  the ones ahead. One in flight (the SDK route): from its submit, as before.
- Log per series: «FULLRES in flight: 6 (default) for 12 stills after the shutter, camera 2 8192x6144» (or «3 (above 100 MP,
  as P179)», «3 (after a failure with more than 3: <reason>)», «8 (scam_dev fullres_in_flight 8)», «1 (the SDK route: one at
  a time)»), «FULLRES in flight: series done with N in flight», and on a noted failure «FULLRES in flight: camera C WxH failed
  with N stills in flight (<reason>); 3 from the next series on».
- Expected on the phone (from the roadmap's model, not measured): the X7U shot 1 from 1.66 s to ~0.75 s of capture after the
  press, the 250 ms PSL shot from 5.5 s to ~3.9 s; resultFrame numbers mostly consecutive (6 in flight covers ~6 of the ~7
  slots a still takes to complete; scam_dev 8 for fully back to back). The viewfinder of the native route (the RAW stream
  itself) gets fewer frames during the series - the series is shorter.
- Photos: no worker, shader or merge change; reference replays are unaffected by construction (cmp8 not run: the worker
  source is untouched). On a phone the captured frames change only in timing.
- Checked: FullResInFlightTest 7/7 (new, in the CI list: counts per route / size, scam_dev values, the failure classes, the
  memory per camera and size, its generation), VivoSdkFullResCaptureTest 17/17 (+4: 6 in flight on a 12-still series, 3 / 4 / 8
  as given, the SDK route 1 whatever is asked, the timeout from the head - a mutation back to the timer from the submit fails
  it - and one in flight at 8 s from the submit), SerialRawCaptureTest 8/8 (+1: head notifications for 1 and 3 in flight),
  FullResZslTest 9/9 (+1: a lost RAW of a ZSL shot is reported missing, an exposure outside every role is not),
  DevKeysCoverageTest; the whole CI list 206 classes / 1289 tests, 0 failures; check_ui_language, check_settings_model,
  check_scam_capture_sequence, check_scam_legacy_isolation, check_scam_ae_context PASS.
- Not checked: anything on a phone - how the HALs of the X7U / X200U / X100U / X300U take 6 queued FULLRES stills (buffer
  waits, dropped frames, heat), the real capture times, whether the fallback ever fires; the 200 MP route with more than 3
  (only reachable through scam_dev).

### P223 - FULLRES and ISZ: switch off the ISP preview and our white-balance corrections (owner, 2026-10-18: «Для фулреза и ISZ
дать возможность полностью отключить isp превью и отключить наши исправления баланса белого, настройку добавь в группу
настроек "видоискатель"»)
- Context (owner's question «Идет ли у нас превью поток паралельно нашего рав видоискателя?»): in the ordinary sessions the
  repeating request targets both the ISP preview surface and the RAW reader (CaptureController setCaptureRequestBuilder: the
  preview surface unless nativeVivoFullRes() / VivoPd2454NativeRaw.active, the RAW reader in ZSL / live-RAW sessions); vivo native
  FULLRES and the PD2454 native ISZ RAW already run RAW only; the vivo SDK FULLRES, OPPO FULLRES and the other ISZ routes keep
  the ISP preview beside the RAW viewfinder.
- To do: in «Видоискатель» two switches for FULLRES and ISZ shots - «ISP-превью» (off: the repeating request carries only the RAW
  stream, the viewfinder is drawn from RAW; AF / AE metering and stabilisation that need the preview stream checked per route,
  with a fallback when a HAL refuses a session without it) and «Исправления баланса белого» (off: the photo and the viewfinder use
  the camera's own white balance - no P121 / P212 mosaic locus, native-session AWB anchoring, learned anchors).
- DONE 2026-10-18: two switches in «Экран камеры» (per module, both on = as before).
  - «ISP-превью в FULLRES и ISZ» (pref_mosaic_isp_preview): off, a FULLRES session (mFullResSession) or a mosaic module
    (IszCapturePolicy.mosaicModule: an ISZ / vendor session mode, a sensor-mode preset, a module sensor mode) shows the RAW
    viewfinder (forced on) and its repeating request targets the RAW stream only (setCaptureRequestBuilder, log «ISP preview off
    (P223)»); the surface stays configured. The vivo SDK FULLRES graph and the native routes (vivo / OPPO native FULLRES, PD2454
    native ISZ) were RAW-only already. AE / AF / faces come from the results of the RAW requests (P225 is the study of how far that
    holds per HAL); a HAL that refuses frames without the preview is not detected yet - to check on the phones.
  - «Поправки баланса белого в FULLRES и ISZ» (pref_mosaic_wb_corrections): off, no frame estimate on the camera's curve (P121 /
    P212f, ScamProcessor.applyMosaicNeutral), no FULLRES neutral resolver (P212g), no OPPO native own estimate (OppoNativeWb.on):
    the camera's white balance; the OPPO native mode has no AWB of its own, so it takes the camera's plain-mode neutral
    (OppoNativeFullRes.neutral). Test: SettingsMenuTest.mosaicPreviewAndWhiteBalanceSwitchesSitOnTheCameraScreenGroup.

### P224 - the viewfinder never below 20 fps (owner, 2026-10-18: «Минимальный фпс для видоискателя ограничить на 20фпс чтобы при
плохом освещении не было слишком медленного видоискателя»)
- To do: the preview's AE target FPS range floor 20 (CONTROL_AE_TARGET_FPS_RANGE lower bound >= 20 where the camera offers such a
  range, else the closest one; our own native AE / RAW viewfinder exposure capped at 1/20 s per frame with the rest from gain),
  in every route (normal, ZSL ring, FULLRES / ISZ native sessions, Motion); stills keep their own exposures (PSL long frames are
  not the preview). Check the dark-scene preview fps and the ZSL ring exposure on the phones.
- DONE 2026-10-18 (capture/ViewfinderFps): the viewfinder's automatic AE ranges get the floor 20 fps - the normal route's
  getSelectedFpsRange() (auto [minLower, 30] -> [20, 30]), vivo's stock-profile STOCK_FPS [5, 30] -> [20, 30], the FULLRES / ISZ
  viewfinder (applyVivoSdkPreview, the preview builder only; a still keeps the camera's whole range), applyFpsRange. Fixed 24 /
  30 / 60, «Движение» and DCG keep theirs. The own native AE (NativeRawExposure) already caps a frame at the mode's frame time
  (<= 1/24 s). scam_dev «vf_min_fps N» (0: the old range). Note: the ZSL ring frames are viewfinder frames, so in the dark they
  are now at most 1/20 s with more gain; the merge takes more of them. To check on the phones: a dark room, the viewfinder at
  20+ fps (VF_DRAW), the PD2454 ultra-wide SDK FULLRES still delivering frames with [20, 30] (only a fixed 30 failed there).
  Test: ViewfinderFpsTest.

### P225 - study: RAW-only sessions (usage 0L readers, 3A and faces without a preview stream) (owner, 2026-10-18: «изучить то что
написано выше», a note the owner pasted)
- Claims to check (the owner's note, not yet verified):
  1. ImageReader.newInstance(w, h, format, maxImages, usage) (API 29+) with usage 0L: no client usage bits; cameraserver sets only
     the camera write usage; on Oplus CamX a stream of a SensorModeTable size missing from the public StreamConfigurationMap
     (8192 x 6144) then passes the gralloc validation, while image.getPlanes()[0].getBuffer() still reads the data on the CPU
     (the 4-argument overload forces USAGE_CPU_READ_OFTEN).
  2. On Qualcomm Spectra (CamX) AE / AWB / AF / LSC statistics come from the IFE on the Bayer data (BG stats, Bayer histogram,
     BF / PDAF, the 17 x 13 LSC grid), independent of a YUV preview stream; the hardware face detector runs on an internal IFE
     downscale whenever STATISTICS_FACE_DETECT_MODE != OFF and writes STATISTICS_FACES into each RAW frame's TotalCaptureResult.
  3. So a session can run on the RAW stream alone (the viewfinder drawn from the RAW ring, as ArkCam's ViewfinderBridge does),
     the RAW repeating request driving the pipeline at 24 - 30 fps with 3A and faces in the results.
- Where it matters: P223 (switch off the ISP preview for FULLRES / ISZ), the OPPO native FULLRES readers (P192), vivo native
  sessions. Check on the OPPO (Oplus CamX) and the vivo phones: reader usage flags (dumpsys media.camera), stream acceptance of
  non-public sizes with 0L vs the default, CPU reads, 3A convergence, faces and fps with and without the preview surface.
- STUDIED 2026-10-18 from our code and its logs (phones not available for new runs in this round):
  1. Usage 0L readers: our native FULLRES readers already use usage 0 (CaptureController: sdkFullRes ImageReader.newInstance(...,
     0)); the PHY110's 8192x6144 stream (not in the public map) is accepted that way and the CPU reads the planes (P192 logs) -
     claim 1 holds on the PHY110. The 4-argument overload (USAGE_CPU_READ_OFTEN) is kept for the ordinary sessions, which only ask
     for listed sizes.
  2. / 3. 3A and faces without a preview stream: the vivo native routes (P180 / P194 / P207: native FULLRES, PD2454 native ISZ)
     and the OPPO native route run RAW-only sessions; their results carry AE / AF states (vivo: AE ON converges; OPPO: own AE by
     design, AF works) - claims 2 and 3 hold for these routes; faces in RAW-only results were not logged yet.
  - Used by P223: «ISP-превью в FULLRES и ISZ» off makes the other FULLRES / ISZ sessions RAW-only the same way.
  - To verify on the phones (log lines): with P223 off on an ISZ module - «ISP preview off (P223)», VF_DRAW fps, AF/AE states in
    SCAM_CAPTURE, and STATISTICS_FACES present (the face frames on the viewfinder) on the vivo X200 Ultra and the OPPO X7 Ultra.

### P226 - a switch for the upscale of crop shots (owner, 2026-10-18: «Добавить пункт отключающий и включающий апскейл при съемке с
кропом. расположить его в соответствии с новой логикой простого интерфейса»)
- To do: one switch «Увеличивать кадр с кропом» / "Upscale cropped shots" (on: today's behaviour - a crop module / digital zoom shot is
  resized up to the output size; off: the photo keeps the cropped pixel size, no upscale). Find every upscale of a crop (module
  crop P148, sensor crop, ISZ / zoom crops, HybridFinalResize, the final resize nodes) and gate only the crop's upscale. Place it
  per SIMPLE_UI: «Фото → Размер фото» (and in the «Размер фото» sheet's «Для опытных» group next to the output resolution),
  plain subtitle («Выкл. — снимок с кропом остаётся в своём размере, без увеличения»); a per-module value (module profiles).
- DONE 2026-10-18: «Увеличивать кадр с кропом» (pref_crop_upscale, per module, on = as before) on «Фото → Размер фото» under «Для
  опытных». The only place a crop shot is enlarged is the merge's output: the Sabre 2x grid (output mode 2x / 16 / 20 MP or
  auto 2x) and the resize up to the output resolution (hybridFinalSize); the digital zoom itself only crops (ZoomController.crop).
  Off, a crop shot (PreferenceKeys.cropShot: the digital zoom's residual > 1, a P148 lens crop, a sensor-crop / ISZ module) merges
  on the sensor grid and keeps its pixels («hybrid output: a crop shot without upscale (P226)» in the log). Test:
  SettingsMenuTest.cropUpscaleSwitchSitsOnThePhotoSizePage.

### P227 - study and fix: the OPPO sensor-mode route against the ArkCam implementation note (owner, 2026-10-18: «добавь в план
изучть и исправить у нас», the note oppo_sensor_mode_implementation.md)
- The note (ArkCam, not yet verified on our phones):
  1. Table: org.quic.camera2.sensormode.info.SensorModeTable (count, then rows of stride 10: width, height, fps, ...) per camera id;
     the mode in effect comes back as org.quic.camera.sensorbps.SensorModeIndex (we read com.qti.sensorbps.mode_index).
  2. Select: com.oplus.engineercamera.agingtest.mode.select = (byte) mode, set on the request builder that goes BOTH into
     SessionConfiguration.setSessionParameters and into setRepeatingRequest.
  3. Spoof: OplusCameraManager.saveOpPackageName(pkg), then setPackageName() with no arguments.
  4. Reader: ImageReader.newInstance(w, h, RAW10 / RAW_SENSOR, n, 0L) at the table size (8192 x 6144 is not in the public
     StreamConfigurationMap; CPU usage bits are refused by the Oplus CamX stream validator).
  5. Session: RAW-only (no preview, no YUV analysis surface - otherwise "Camera HAL struggled to configure streams"), opMode 0
     (SESSION_REGULAR), the own GL RAW viewfinder forced on while a mode is active.
  6. UI: a per-lens «Sensor Mode» list (Auto = -1, "Mode #N: W x H @ fps") on Oplus devices only; picking a mode fills the
     lens's RAW size from the table row.
  7. Find X8 (PKJ110) NoRemosaic 8192 x 6144 indices: main (LYT-808, 39 modes) 32 @30 / 37 @24, ultra-wide 0.6x (JN5, 24) 17 @15,
     tele 3x (LYT-600, 22) 18 @30, tele 6x (IMX858, 19) 18 @15. Ours (P192, PHY110 measured): 2 -> 35, 3 -> 16, 4 -> 19, 5 -> 17.
- Ours to compare: OppoNativeFullRes (TABLE_KEY, fullModes candidates, MODE_KEY, the mode-table rule), the CaptureController
  reader block (usage 0 / 0x00100000 branches near the RAW reader), OplusCameraPackageSpoof (setPackageName signature),
  VivoNativeFullRes (the RAW-only route the OPPO reuses), CaptureController's vendor-mode fallback (a refused mode -> 0).
- To check and fix where we differ: the result key name (read both); the mode in the session parameters AND every repeating /
  still request; the stride-10 table parse and the per-camera candidates on phones other than PHY110 (X8 / X8 Ultra indices above,
  pick NoRemosaic rows by size and fps, not by a fixed list); RAW10 as well as RAW_SENSOR; no stray YUV / preview surface in the
  mode session; whether a per-module «Режим сенсора» choice (Auto + table rows, OPPO only) belongs in the module editor under the
  SIMPLE_UI rules. Ties to P223 / P225 (RAW-only sessions) and P192.
- Test only on the OPPO with adb replays and the probe; never start SCAMERA on fb27034c with a saved type 32777 + FULLRES session.
- DONE 2026-10-18 (compared with the note item by item):
  1. Table: ours reads org.quic.camera2.sensormode.info.SensorModeTable with the stride from table[1] (fullModes), as the note.
     Result key: we read com.qti.sensorbps.mode_index only - now also org.quic.camera.sensorbps.SensorModeIndex
     (OppoNativeFullRes.modeMatches; either one naming another mode is a mismatch). Test theModeReportIsReadUnderBothNames.
  2. Select: the byte key (int fallback when the HAL refuses the byte, P214j) goes on the session parameters
     (VivoPd2454NativeRaw.apply on the session-parameter builder), on the repeating request (applyVivoSdkPreview) and on every still
     (VivoSdkFullResCapture) - as the note.
  3. Spoof: OplusCameraPackageSpoof already calls saveOpPackageName(pkg), then setPackageName() with no arguments - same.
  4. Reader: the full-size RAW_SENSOR reader is created with usage 0 (CaptureController, sdkFullRes) - same. RAW10 is not used:
     RAW_SENSOR is proven on the PHY110 (P192) and the ring / merge take 16-bit samples; RAW10 would need a packed ring copy -
     not done (no phone needs it so far).
  5. Session: the native route is RAW-only (no preview surface, no YUV), opMode 0, the own GL RAW viewfinder forced on - same.
  6. UI: no per-lens mode list - the candidates come from the table by size (fullModes: the largest rows that also have a binned
     row) and a failed one is remembered (P214h trials); the module editor's sensor mode (module_sensormode_<slot>) still takes a
     number by hand under «Для опытных».
  7. Find X8 (PKJ110) indices from the note are the table rows fullModes picks (main 32 / 37, UW 17, tele 18 / 18): no list
     needed. Candidate order stays the table order (the PHY110 rows were measured in it).

### P228 - diffusion filters: Black Pro-Mist, Glimmerglass and the like, adjustable (owner, 2026-10-18: «Реализовать симуляцию Black
pro mist, glimmer glass и других подобных фильтров у нас в камере с возможностью настройки, расположи настройки в соответствии с
нашей новой логикой простого интерфейса, я думаю можно расположить группу этих настроек рядом с LUT»)
- Filters (each a physical model on the linear scene-referred RGB before the tone curve, where the highlights still hold their
  real headroom - P216 hr - so a bright source blooms by its true energy, not by its clipped code):
  - Black Pro-Mist: a wide low-level halation of the highlights (energy-preserving: the spread light is taken from the source),
    slight contrast drop in the mids, blacks kept (the "black" variant) - strength 1/8, 1/4, 1/2, 1 as presets + a slider;
  - Glimmerglass: a sparkle / short star-like glow on point lights and specular highlights, little haze;
  - White Pro-Mist / Glow (lifted blacks, softer), Soft FX / softening (fine detail diffusion on skin, highlights barely glow),
    Cinebloom / halation (red-orange film halation fringe) - as further presets if the model covers them.
- Model to build and check: highlight extraction above a knee in linear light, a multi-scale PSF (Gaussian pyramid sum with a
  long tail; streak kernel for Glimmerglass), energy conservation, colour of the glow (neutral vs warm halation), depending on the
  focal length / crop so the look matches between lenses; GPU pass in PostPipeline (after the merge, before ARK tone), the same
  pass on the viewfinder (GL preview, downscaled) so the effect is visible live.
- UI per SIMPLE_UI: a «Фильтр» group next to the LUT - in the LUT tray (a second row of filter cards: «Нет», Black Pro-Mist,
  Glimmerglass, ... with live thumbnails) and on the settings LUT page (a «Фильтр на объектив» section: the chosen filter, its
  strength 1/8 - 1, plain one-line descriptions); the «Стиль» tile shows LUT + filter; per-module value (module profiles).
- Owner's list (2026-10-18, the filters to simulate, in this order): Black Pro-Mist, CineBloom, Glimmerglass, Pearlescent, Fog,
  Double Fog, GND (Graduated ND), Light Pollution, Streak, Prism, Kaleidoscope, Infrared. Models to work out per filter:
  - diffusion family (Black Pro-Mist, CineBloom, Glimmerglass, Pearlescent): highlight halation with different PSF widths /
    tails, glow colour (Pearlescent: pastel, iridescent tint) and sparkle (Glimmerglass);
  - Fog / Double Fog: a veil over the whole frame (lifted blacks, lower contrast) plus a wide glow; Double Fog keeps more
    definition with the same glow;
  - GND: a graduated neutral density (stops, transition hardness, angle, position - drag on the preview), applied in linear light
    before the tone so the sky keeps its highlight detail;
  - Light Pollution: cuts the sodium / mercury-vapour bands (a spectral notch emulated on the camera RGB: a CCM-space notch around
    the orange-yellow hue), cooler night sky;
  - Streak: anamorphic horizontal (or chosen angle) streaks from point lights, coloured (blue / gold / clear);
  - Prism: a displaced, tinted, softened ghost copy of part of the frame (offset, angle, strength, edge);
  - Kaleidoscope: mirrored / repeated segments (count, rotation), a creative geometry pass;
  - Infrared: false-colour IR look (foliage white / pink, sky dark, channel swap presets) from the RGB data, with strength.
  Each filter with its own few settings (strength first; the rest only where they change the look).
- Live demonstration in the settings, the same way the new LUT page works (owner, 2026-10-18: «при выборе какого-либо из
  фильтров нам нужна наглядная демонстрация прямо в настройках как у нас реализовано с LUT в новом интерфейсе»): a «Фильтры» page
  next to the LUT page with the 4:3 sample (left original, right with the filter at the current settings; a sample with bright
  point lights and sky so the glow / streak / GND show), a strip of filter cards with live thumbnails, the chosen filter's
  one-line description, its sliders updating the sample immediately; the viewfinder LUT tray gets the same filter row.
- Checks: off = byte-identical output (md5 of the reference replays), energy test on a synthetic point source, no banding at
  12 / 50 / 200 MP, time budget per size.
- DONE (2026-10-18, wt-int):
  - Model: processing/color/LensFilters - the 12 filters on the linear RGB after ScamHdrDenoise, before the ARK tone (the
    highlights keep their headroom). Diffusion family: the light above a knee, a 6-level Gaussian pyramid PSF (weights sum to
    1, the spread light taken from the source - energy kept, LensFiltersTest), glow colour, veil from the image's levels 4-6,
    lift; Glimmerglass a short X star, Streak a horizontal streak (length = its parameter); GND (transition = parameter),
    Light Pollution (the orange-yellow band loses chroma), Prism (shifted tinted soft ghost, offset = parameter), Kaleidoscope
    (3-12 segments), Infrared (mono / false colour / R-B swap).
  - Settings (owner: «дай возможность настраивать эти фильтры»): strength, the filter's own parameter and, per filter, fine
    controls «Порог свечения» (knee x0.25..x4), «Радиус» (PSF tilt; Glimmerglass star length), «Дымка» (veil / lift x0..x4;
    Prism: the ghost), «Тон свечения» (cooler / warmer glow), 50 % = as designed, «Сбросить». Stored: pref_lens_filter,
    pref_lens_filter_strength (0..100, default 50), pref_lens_filter_tune_<id> ("param,knee,radius,haze,warmth").
  - GPU: postpipeline/LensFilter node (shaders/lensfilter extract / streak / apply, FILTER define per filter; glslc OK for all
    12 variants); no filter = no node, the output is the one without P228.
  - UI: «Фильтр на объектив» page next to «Плёночный LUT» (LensFilterPreference: before / after preview on Огни / Пейзаж /
    Закат, the strip of 13 cards with thumbnails at the filter's own tune, sliders redraw at once, description); the LUT tray
    on the viewfinder has a second row of filter cards (thumbnails of the current frame), the pill reads «LUT · Pro-Mist»;
    the shade lists the filter (LIST). Global like the film LUT (not per module).
  - Tests: LensFiltersTest, SettingsMenuTest.lensFilterPageShowsTheStripPreviewAndSliders (also draws
    build/reports/module-concept/lens-filters-*.png and page-lens_filter_screen.png), LutTrayTest.theFilterRowChoosesTheLensFilter.
  - Not done: the live viewfinder pass (the RAW live renderer is one pass; the tray thumbnails and the settings preview show
    the look); phone check of the GPU node (log line «lens filter <id> ... ms=»), time at 50 / 200 MP.

### P229 - X300 Ultra: study rawr and fix FULLRES; fix ISZ (owner, 2026-10-18: «https://github.com/adityawarmanfw/rawr/releases/tag/debug
изучить и исправить фулрез на 300 Ultra. Так же исправь isz на нем», with the X300U mode table again)
- rawr: an open-source (GPL-3.0) RAW camera (github.com/adityawarmanfw/rawr; debug pre-release of commit ce767e8, package
  com.rawr.camera.debug; GPU processing in Vulkan, JPEG + DNG, native code in app/src/main/cpp and native/). Clone the source
  (not the APK) and find how it opens the X300 Ultra's full-resolution RAW: session type / keys, stream sizes and formats,
  reader usage, forceSensorMode or another vendor key, request vs session parameters, AE in that mode. Compare with ours
  (VivoNativeFullRes, P180 / P184 / P186, VivoQcomIsz, CaptureController's session block) and fix what differs. GPL code is
  read for the mechanism only, not copied.
- ISZ on the X300 Ultra (owner's reminder): vivo.control.forceSensorMode works when it stays a SESSION key (not a request
  key). The owner's mode table:
  - 14mm: 5 16:9 60 fps 14-bit; 8 16:9 60 fps; 10 16:9 30 fps 14-bit; 11 open gate 30 fps 14-bit; 14 wide 120 fps;
    19 open gate 60 fps; 21 2x ISZ (Quad Bayer); 23 open gate 60 fps 14-bit.
  - 35mm: 10 2x ISZ (Quad Bayer); 15 16:9 30 fps 14-bit (auto exposure); 17 open gate 14-bit; 18 open gate 60 fps;
    19 open gate 60 fps 14-bit; 20 16:9 60 fps; 21 16:9 60 fps 14-bit; 22 wide 120 fps.
  - 85mm: 5 2x ISZ (Quad Bayer); 6 4x ISZ (remosaiced); 7 4x ISZ (Quad-Quad Bayer); 8 open gate 60 fps; 10 16:9 2x ISZ
    (Quad); 12 wide 120 fps; 13 wide 2x ISZ 120 fps (Quad); 16 16:9 60 fps; 17 16:9 2x ISZ 60 fps; 19 open gate 60 fps
    14-bit; 20 open gate 30 fps 14-bit; 21 16:9 30 fps 14-bit; 22 16:9 60 fps 14-bit; 31 2x ISZ (remosaiced); 32 16:9 120 fps.
  - 14-bit modes on the tele are DCG and work. On 14 and 35 mm they turn highlights pink; 35mm mode 15 works only with
    auto exposure (manual exposure turns the highlights pink at once).
- To check: our P122 table and the ISZ modules on the X300U use these modes as a session key on every session of the
  module (also after a restart / FULLRES toggle / format change); the ISZ modules (14mm 21, 35mm 10, 85mm 5 / 7 / 31 / 6)
  give the expected mosaic (CFA / block) and crop; the pink 14-bit modes on 14 / 35 mm are not offered (or only 35mm 15 with
  AE, manual exposure blocked there); P126 (doubled ISZ modules, dark DCG photos) stays fixed. Verify with the owner's log /
  photos (no X300U here).
- DONE 2026-10-18. rawr studied from its source (ce767e8 «vivo 50mp fixes», docs/vivo-fullres.md and its X300 Ultra profile,
  measured on a V2562 with Android 16; GPL code read only):
  - Gate: VivoCameraManager key 40 = 1 («useVivoCamera») makes cameraserver classify the client as vivo's camera; the HAL's
    sensor-mode selector (PD2547.json) keeps the full-size modes for that AppType only. cameraserver reads it while it configures
    streams; key 33 is rewritten at open. Ours (VivoSdkRawSession lease) sets 40 = 1 (and 33) for the whole session - the same
    gate, held longer; kept.
  - 50 MP: forceSensorMode on the session parameters AND every request (session-only leaves the sensor binned, a 12 MP image in a
    corner of the 50 MP buffer) - ours does both (VivoPd2454NativeRaw.apply on the session builder, applyFullRes on requests).
    Reader usage none or GPU_DATA_BUFFER only - ours 0. RAW-only session - ours. Main 3 mode 2 (8160x6128) and tele 5 mode 14
    (8160x6144) are Bayer by rawr's colour-block test: main row 2 was our hardware-remosaic row already (P194); the tele's row 14
    is now its hardware-remosaic row too (hardwareRow) and its own-mosaic candidates are 1 / 27 (were 1 / 14 / 27: row 14 was a
    wasted «wrong mosaic» trial).
  - 200 MP: rawr copies the stock still session (YUV 1440x1080 + RAW and YUV 16320x12288 + RAW 4080x3072, streamsUsage
    [4 1 0 5], ultra_highresolution / is_pro_mode / is_ProRaw_on 2 / current_ui_module 74 ... on session and requests, a
    STILL_CAPTURE with onlyDng 1, isUpscale 1, sensorMode 0): that gives the HAL's remosaicked 200 MP Bayer, not the Tetra our
    merge wants; forcing mode 0 streams the raw Tetra and stops the preview (as our P208 saw). Not ported: our 200 MP stays the
    P208 still-only route; the stock-session copy is the way to a hardware-remosaic 200 MP if the owner wants one.
  - Pitfall: vivo's own camera open in the background fails full-size sessions (error 3 / 4): the refusal message now says to
    close it.
- ISZ: the owner's table is P214i's (VivoQcomIsz.MODES: 14 mm 21, 35 mm 10, 85 mm 5 / 7 / 10 / 13 / 17, remosaiced 6 / 31 not
  offered as modules) and forceSensorMode stays a session key there (TunableKeyManager.applySessionKeys). DCG: rawr's levels for
  the 14 / 35 mm DCG modes are black 1024 / white 8712 (the tele's 16383): our 2026-10-15 log measured the same clip (8694) - the
  pink highlights were a blown area read against 16383. RawLevels.priorClip now sets that clip from the session's first frame on
  the main / ultra-wide DCG modes (VivoQcomIsz.applyDcg), not only after a plateau showed; DCG stays on all three by the vivo
  CameraType (by focal length the tele only, as P214i). Tests: VivoNativeFullResTest, FullResTrialsTest, RawLevelsTest.
- To verify on the owner's X300 Ultra: the tele 50 MP (rows 1 / 27; «FULLRES: аппаратный ремозаик» -> row 14), the main / UW DCG
  in a bright scene (log «RAW clip 8694 known for this mode», no pink highlights).

### P230 - DCG without root on Snapdragon 8 Elite: vendor op mode + sensor mode; Xiaomi 15 Ultra first, then the vivo X200 Ultra (owner, 2026-10-18: a Discord post from MotionCam, «Изучи внимательно и реализуй на 15 Ultra. затем займись реализацией на X200 Ultra» - corrected from «17 Ultra»)
- The post (Xiaomi 15 Ultra, SM8750, no root, @JohnTheFarm3r): DCG works with
  - Vendor op mode 37379 (0x9203) for the capture session (SessionConfiguration operating mode), found in Xiaomi's
    com.qti.chi.override.so where CheckVideoNightModeHint tests for it; alone it selects sensor mode 23 (14-bit, 4096 x 2560,
    the DCG format);
  - org.codeaurora.qcamera3.sensor_meta_data.current_mode (i32) = 24, the actual DCG mode;
  - 60 fps only; confirmed by the logs and the RAW data (14-bit);
  - normal 4096 x 3072 frames of that sensor have 512 empty rows at the bottom: crop them.
  Claim: the 8 Elite platform itself does not block DCG without root; the missing piece is the right op mode / sensor mode
  / stream configuration, per phone.
- Xiaomi 15 Ultra (25010PN30G, SM8750; SCAM TEST: logical 0/1 + hidden 2-7, the tele 4 alone kills the provider): the post's
  values are for this phone - op mode 37379 (0x9203) on the session, current_mode = 24 (i32) as a session key, 60 fps.
  Add DCG 14-bit for the 15U's main camera the way Dcg14Mode works on the X300 Ultra (the top bar's «DCG» button /
  «14 бит (DCG)»): session op mode + current_mode, RAW 14-bit read right (white 16383, black from the frame), the 512 empty
  rows of the normal 4096 x 3072 frames cropped, the preview at the mode's 60 fps, a refused configuration falls back
  (0x9203 -> 0) without a restart loop; the mode checked in the result (com.qti.sensorbps.mode_index / current_mode).
  Find the mode numbers of the other 15U cameras (ultra-wide, tele) from their sensor mode tables before offering DCG there.
- Xiaomi 17 Ultra (SM8850): only after the 15U works - check whether 0x9203 / a DCG current_mode exist there too.
- vivo X200 Ultra (PD2454, SM8750): the same idea - vivo's own op modes / vivo.control.forceSensorMode (P122: works as a
  session key on the X300U) for a 14-bit DCG mode of the IMX06C (main / ultra-wide) and the HP9 tele; study the vivo camera
  stack (libs on the phone, the stock app's session) for the op mode that unlocks DCG, then implement as above.
- Verification needs the phones' logs (owner): RAW bit depth (max code > 4095), the mode in the result
  (com.qti.sensorbps.mode_index / current_mode), fps, no pink highlights.
- DONE 2026-10-18 (worktree wt-dcg, branch dcg-8elite; commits 670140fc + the next one; not pushed, not run on a phone):
  - Xiaomi 15 Ultra (capture/XiaomiDcg, wired through Dcg14Mode / the top bar «DCG» button): the switch is shown on a back
    camera whose org.quic.camera2.sensormode.info.SensorModeTable has mode 24 at 4096 wide (dump 2026-10-11: cameras 0 / 2 /
    6 / 7 = the main; the ultra-wide 3 has 15 modes, the tele 4 14, the front 9: never offered there; nothing opens the tele 4
    alone). DCG on: the session runs operation mode 0x9203 (instead of the module's session type; never in FULLRES or on the
    17U logical route); current_mode 24 (Integer) goes on the session parameters AND on every request (the dump lists it neither
    as a session nor as a request key; the camera service drops unlisted session parameters, the 17U HAL takes current_mode per
    request); AE range [60, 60] on the session, the viewfinder and the shot (the series takes the viewfinder's range). Note: the
    15U's table says mode 24 = 4096 x 2560 at 30 and modes 28-32 = 4096 x 2560 at 60 - the post's «60 fps only» is followed as
    written; scam_dev overrides below. Refusal (onConfigureFailed, an exception, or a DCG session without a preview frame): DCG
    off, the error card, one camera restart in mode 0 (retryWithoutDcgRoute) - no loop; a device error already turns DCG off.
  - Log lines: «camera N: DCG session operation mode 0x9203», «DCG 14 bit on camera N: operation mode 0x9203, current_mode 24,
    AE fps [60, 60] (table: mode 24 = 4096x2560 at 30 fps)», «Xiaomi DCG camera N: current_mode X, mode_index Y (asked 24) =
    14-bit DCG route / NOT the DCG mode, dynamic white W», «DCG camera N: HAL mode X = WxH at F fps in the 4096x3072 RAW
    stream: rows 2560-3072 are its empty band», «RAW levels: sensor mode depth 14 bit», «DCG band: mode 24 ... cut 512 rows at
    the bottom».
  - Levels: RawLevels.noteKnownBits - the session's 14-bit depth up front (white 16383, black = the frame's dynamic black in
    its units, else 64 x 16), back to the declared 10 bit as soon as a result reports a mode other than 24 / 23; RawPayloadCheck
    checks against the known white (14-bit samples and the empty band are no «impossible payload»).
  - Crop (capture/SensorModeBand, ScamProcessor's RAW crop before anything reads the frames): the mode the shot's result
    reports (else the asked one) against the frame; a shorter mode's band is cut when every one of its rows is one value
    (bottom first, then top); the lens shading map is read at the centred window (+256 rows; assumption: the 16:10 mode is
    centred on the sensor); «16:9» takes its rows out of the mode's 2560; during a DCG session the payload check samples only
    the mode's rows.
  - scam_dev (all in DevKeys): xiaomi_dcg 0 (hide), xiaomi_dcg_opmode N (37379; 0 = none), xiaomi_dcg_mode N (24; -1 = not
    sent), xiaomi_dcg_fps N (60; 0 = the viewfinder's range), dcg_band_crop 0 / 1 / 2 (keep / cut when empty / always bottom).
  - Tests: XiaomiDcgTest and SensorModeBandTest (both added to the CI list), RawLevelsTest, RawPayloadCheckTest, RawCropTest;
    with the related classes 263 / 263 passed locally; check_ui_language and check_settings_model PASS.
  - Owner's test on the 15U (CI APK): main camera 1x, a plain module, tap «DCG» (the camera restarts). Send the log
    (DCIM/SCAMERA/Log) with the lines above; the viewfinder must run (60 fps); take 2 shots (a bright window, a dark room) and
    send the DNGs / photos. Proof: current_mode / mode_index 24 (or 23) in the result, «RAW levels: sensor mode depth 14 bit»,
    DNG max value > 4095 (white 16383), no pink highlights, no black band at the bottom (the 512 rows cut), even vignetting top
    and bottom. If the HAL reports another mode: try scam_dev xiaomi_dcg_mode 28 (4096 x 2560 at 60 in the table) or
    xiaomi_dcg_fps 30. If the bottom rows are not one value the log says «no empty band found»: try scam_dev dcg_band_crop 2.
    Risks: the 14-bit RAW may arrive packed (MIPI RAW14 behind RAW_SENSOR, like the 17U's mode 9) - then the log shows «RAW is
    not plain 16-bit» and RawPayloadCheck dumps a sample; the mode caps exposures at 1/60 s.
  - vivo X200 Ultra (PD2454): the phone was not on adb during this pass (only the OPPO, untouched); studied from the pulled HAL
    and config files of 2026-10-13 and the P109-P200 reports. Found: no 0x9203 (no 0x92xx operation mode at all) in PD2454's
    com.qti.chi.override.so / camera.qcom*.so / libvcf*.so (MOVZ scan: the only 0x92xx constants are the EXIF tags 0x9201 /
    0x9204); its vendor operation modes are 0xF040 (fast shutter, org.quic.camera.SensorModeFS) and the EIS modes; the stock
    camera's sessions all run NORMAL (0). DCG is chosen by vivo's selector (sensormode-selector/PD2454.json) from AppType, UI
    mode, EnableSHDR / EnableAutoHDR and the frame rate: the 14-bit DCG_SENSORFUSION LOG modes (DCGHDR30/60_LOG; main mode 12
    = 4096 x 2632 at 30) are AppType vivo only; a third-party AppType gets PHOTO_V2H2 on the main / ultra-wide, except AppType
    TELEGRAM (org.telegram.messenger = 307 in CameraService's package table): 50 / 60 fps -> DCGHDR60_1_4 (DCG1_4, 4096 x 2632
    at 60: main modes 10 / 11, ultra-wide 8 / 9). current_mode is a result key there; vivo.control.forceSensorMode exists (as a
    session key), but forcing the DCG mode under the selector's PHOTO geometry gave no RAW frame (P109-P200: selector 4096 x 3072
    against CHI's 4096 x 2632). PD2454's DCG modes are 4096 x 2632 (main, ultra-wide) / 4080 x 2616 (tele) windows, like the
    15U's 4096 x 2560.
  - Implemented for the X200U, guarded (scam_dev vivo_dcg_telegram 1, off by default): the «DCG» button on the main and the
    ultra-wide (a 16:10 4096-wide mode at 60 fps in their table; not the tele: its 2616-row modes run 30 fps only). DCG on =
    the client as org.telegram.messenger (the typed attribution spoof of VivoQcomModes.spoofPackage) + AE [60, 60] on the
    session and every request, so the selector itself picks DCGHDR60_1_4 and its geometry is the mode's; the band crop and the
    mode-rows payload sampling as on the 15U (the depth comes from the samples: no known depth on vivo); refusal / no frame ->
    DCG off, one restart. Uncertain: DCG1_4 is a 2-exposure (HDRExposureType1) mode, the RAW may be the ISP's 10-bit input or
    may not come at all; the selector's evaluation order is not known (its «!vivo» 60 fps node comes before TELEGRAM).
  - Owner's test on the X200U (CI APK): scam_dev vivo_dcg_telegram 1, main camera 1x, a plain module, tap «DCG». Send the log
    with: «provider AppType=...» (VivoQcomModes, expect 307), «SCAMERA_SPOOF ... org.telegram.messenger», «DCG 14 bit on
    camera 3: AppType TELEGRAM ...», «sensor mode index A -> B» and «VivoQcomModes camera 3 mode=...» (expect 10 / 11, not 1),
    «DCG camera 3: HAL mode ... 4096x2632 ...», «RAW samples prove N bit» if any, PREVIEW_STALL / «DCG session ... refused»
    lines; one shot (DNG). Then the same on the ultra-wide (camera 4). Useful too: adb shell dumpsys media.camera during the
    session (Client Package, session parameters, operation mode). No other X200U route looked like it should work.

### P231 - import of JSON tone curves ({"name", "points": [{"x", "y"}]}) (owner, 2026-10-18: a file ark_art_drama_faces.json, «добавь в план: добавить поддержку импорта такого формата кривых»)
- The sample (945 bytes): `{"name": "ark_art_drama_faces", "points": [{"x": 0.0, "y": 0.0}, {"x": 0.0961, "y": 0.011}, ...
  {"x": 1.0, "y": 1.0}]}` - 19 points in the unit square, x ascending, both ends pinned; a strong S (deep shadows, bright
  highlights), the name says ArkCam's «art / drama / faces» looks.
- Today «Кривая AgX и ACES» → own curve → «Загрузить» → «Из файла…» (ToneCurvePreference.readCurve →
  ToneCurve.fromFileText) reads only "x:y" pairs; in this JSON the numbers follow `"x":` / `"y":` keys, so the file gives
  no pairs and is refused («нет кривой»).
- Do:
  - ToneCurve.fromFileText: when the text parses as JSON with a `points` array (objects with numeric x / y, or [x, y]
    arrays), take those points (the same 0..1 / 0..255 scaling, clamping, sorting, MIN_GAP and the pinned ends as the
    "x:y" route); otherwise the old pair scan, so our .txt files and ArkCam's "x:y,..." string keep working.
  - The `name` field: the suggested name when the imported curve is saved into Download/SCAMERA/ToneCurves (sanitised as
    the other file names); the in-folder list of ToneCurvePreference shows .json files next to .txt (ScameraFolder.CURVES
    lists one extension today).
  - Check the meaning of x against ArkCam (ToneCurveHelper / u_tex_custom_curve in ArkCam 1.42, research/hybrid5): our x
    is the normalised log exposure (0.5 + EV / 10) and y the display value. If ArkCam's exported JSON uses another domain
    (e.g. display-in → display-out), convert on import so the curve looks the same as in ArkCam; note the finding here.
  - Export: «Сохранить» may also write this JSON (the same shape) so the curves go back to ArkCam - only if ArkCam reads it.
- Tests: ToneCurveTest - this sample file (19 points, ends 0:0 and 1:1, monotone), [x, y] arrays, a broken JSON falls back
  to the pair scan / refusal, the 0..255 scale; ToneCurvePreference listing with a .json file.
- DONE 2026-10-18: ArkCam 1.58 (com.arkcam.ui.ToneCurveFileManager, decompiled) writes exactly this file - {"name", "points":
  [{"x", "y"}]} at 4 decimals into Documents/ArkCam/Curves - from the same points as its pref_custom_tone_curve_points "x:y"
  string, which P144 ported (x the normalised log exposure, y the display value): no domain conversion. Its reader also takes a
  bare array and [x, y] pairs and clamps to 0..1. ToneCurve.jsonPoints / jsonName / fromFileText do the same (no 8-bit scaling in
  JSON); the import (SettingsActivity.importToneCurve) also saves an ArkCam file into Download/SCAMERA/ToneCurves under its
  «name» (our .txt); the folder lists .txt, .json and .curve files (ScameraFolder.CURVES). Export stays our .txt: ArkCam's reader
  falls back to its "x:y" parser for other text. Test: ToneCurveTest.arkCamJsonCurveFiles (the owner's 19-point sample).

### P232 - FULLRES 50 MP ZSL: the memory budget before the N after the shutter, the ring frames kept (roadmap speed-ux A2)
- Evidence: owner's X7U log 2026-10-17 20:20:35.714 «50 MP ZSL: 5 ring frames + 7 N after the shutter» then «frames=14 (ZSL: N 1
  from the ring + 13 after the shutter) ... cut=memory»; ring frames kept per ZSL shot 1, 1, 1, 3, 1, 1, 2, 1, 6, 5 (test phone)
  and 1, 1, 5, 10 (owner), median 1. Cause: takeNativeZsl fixed the top-up (requested - ring) first, the budget
  (VivoSdkFullResCapture.zslNormals) then removed the oldest ring frames - the zero-lag ones.
- Now: takeNativeZsl returns the plan without N; captureVivoSdkFullResChecked asks the budget for the whole shot (max(ring,
  requested) N + the plan's ultrashort / long stills) and splits it with VivoSdkFullResCapture.zslSplit: the ring frames stay
  (only when even the extras do not fit do they go, the newest one always stays), the N after the shutter fill the rest up to the
  N asked. Log «50 MP ZSL: K of R ring frames + T N after the shutter (asked A, budget B frames); <plan>».
- Checked: FullResZslTest 11/11 (+1: the owner's case 5 ring / 6 extras / 14 budget -> 5 + 3; with A1's 2 extras 5 + 7; a tight
  budget drops after-press N first), VivoSdkFullResCaptureTest 17/17.
- Phone (not tested): «ZSL: N k from the ring» with k close to the ring size; fewer frames after the press, shorter hold.

### P233 - «Снято K из N кадров» counts the N the merge takes (roadmap speed-ux A5)
- Evidence: owner's shot 1 (X7U 2026-10-17 20:20:35) showed «Снято 1 из 12 кадров» while the worker merged 8 N («N frames 8 of
  8»): the toast's K was shotN, the ring's frames alone.
- Now: K = the ring's N + the N after the shutter (VivoSdkFullResCapture.zslTaken; the plan's ultrashort / bracketed stills are
  not N); the cut («memory» / «ring») compares that K with the N asked. The log line «NATIVE FULLRES frames=… (ZSL: N r from the
  ring + s after the shutter, N t of them)». After the merge the worker's «HYBRID SERIES: merged … (N frames k of n)» still gives
  ScamHybridBurst.notice «Склеено k из n обычных кадров» when the worker merged fewer N than were sent (unchanged).
- Checked: FullResZslTest 12/12 (+1), ScamHybridBurstAnyResolutionTest 13/13.
- Phone (not tested): a memory-cut ZSL shot's toast K = the «N r from the ring» + «N t of them» of that line.

### P234 - no instant-shot UI while a long ZSL series is still capturing (roadmap speed-ux A4)
- Evidence: every ZSL shot sent «ScamZslCaptureStarted» (the instant UI: no capture ring, controls live) while the owner's 5
  FULLRES 50 MP shots took 8-15 stills over 1.2-2.3 s after the press; moving then costs rejected / misaligned frames.
- Now: CaptureController.zslStartTag(stills after the press): up to INSTANT_ZSL_STILLS = 6 (the 12 MP plan, ~400 ms) the instant
  UI as before; above it «ScamZslSeriesStarted» (CaptureController.ZSL_SERIES_STARTED): CameraFragment shows the capture ring
  (max = the shot's frames, set to the frame number of each still - the ring's frames count as taken - via the new
  CameraUIView.setCaptureProgress), locks the controls as a PSL burst, and gives a haptic tick (CONFIRM, API < 30 CLOCK_TICK) at
  onCaptureSequenceCompleted. Both ZSL routes use it (FULLRES 50 MP and the 12 MP Hybrid, whose plan stays <= 6: unchanged).
  Log «50 MP ZSL UI: instant shot | capture ring (N stills after the press)».
- Checked: FullResZslTest 13/13 (+1).
- Phone (not tested): a 50 MP ZSL shot with > 6 stills after the press shows the ring and ticks at its end; with P232 + A1 most
  shots should now stay instant (2-6 stills).

### P235 - FULLRES: the shot arena made at the press, the ring frames moved in while the stills arrive (roadmap speed-ux B3)
- Evidence: «HEX CLIENT PREP raw_write=606» (owner's X7U) / 903 ms (test phone): a 50 MP burst whose stills did not all fit the
  ring memfd's free slots (ZslRing.reserve gives what is free) had ring views and stills in two places, and
  ScamHybridBurst.moveIntoArena copied the whole burst into a new arena after the series, between the camera and the worker.
- Now (CaptureController.pressArena / pressArenaWanted, processing/RingArenaMove): when the reserved ring slots do not take every
  still (or a ring frame is not in the ring memfd) and the frames are above 16 MP, the shot gets its own ShotArena of ring + stills
  frames at the press; the stills are written straight into it (the saver's shotArena, as before), the FULLRES ring is released
  at the press (it takes no frame during the series and was released for the merge anyway, P200) and RingArenaMove copies the
  ring frames in on its own thread, one at a time under the frame's lock, each ring view freed after its copy (the released
  ring's slot pages are punched at once: the peak stays one frame above the burst, as moveIntoArena's). complete() waits for the
  move before the arena is released; failed() cancels it before the frames are discarded. A frame not moved (arena full /
  released, closed, another size) keeps its view and moveIntoArena moves the burst as before. Burst in one memfd already (the
  usual 12 MP and the PSL series that fit), 200 MP (own arena already), binning: unchanged. scam_dev fullres_press_arena 0 = as
  before (DevKeys). Log «FULLRES shot arena at the press: arena … for R ring frames + S stills (the ring's free slots held F)»,
  «FULLRES shot arena: R/R ring frames moved in T ms»; the merge's «hybrid burst moved into a shot arena» should be gone.
- Byte identity: the worker reads every frame at its own page of the memfd (NCH v12 header, pages from Allocator.arenaOf), the
  frames keep their bytes and order; only their slots differ. RingArenaMoveTest (new, CI) builds the burst both ways (the move
  after the series vs the press arena with the stills written concurrently from another thread) and compares the merge input
  byte for byte, plus each ring view freed once, a full / released arena leaving the rest in place, closed / odd frames skipped,
  cancel. FullResZslTest +1 (pressArenaWanted), DevKeysCoverageTest, ScamHybridBurstAnyResolutionTest, CameraResumeTest pass.
- Phone (not tested): raw_write in «HEX CLIENT PREP» drops by the move (~0.6 s on the X7U); no memory warnings during the series.

### P236 - shot timeline marks, one summary line per shot, the frames' release off the critical path (roadmap speed-ux B4 + F)
- Evidence (owner's X7U 2026-10-17 20:20:51): «reference RAW copied out of the shot arena» 51.832, «D/ImageFrame: Buffer is
  already null» 51.832, then nothing until «Alignment elapsed» 52.219: ScamProcessor closed the shot's frames there (the last
  views of a 50 MP arena: a punched hole per 96 MB slot, then the memfd's munmap / close giving back ~1.3 GB) before the post
  pipeline. The other part of the 0.4-0.6 s after «SCAM CAPTURE OK» is the client's output map (114 ms) and the reference copy.
- Now: ScamProcessor.releaseFrames closes the frames on one low-priority thread («shot frames released off the critical path: N
  frames, M MB in T ms») once the reference RAW is out; bursts of 16 MP or less and 200 MP shots (memory edge, P179) close in
  place as before; scam_dev release_async 0 = as before (DevKeys). Nothing reads the frames after the merge.
- ShotTimeline: marks series_done (FULLRES complete and the 12 MP path), arena_moved (P235's press arena or the merge's
  moveIntoArena), plain_ready (the plain session for the merge), w_detect («HYBRID MOSAIC: header block»), w_response /
  w_binning / w_merge (back from the native merge's «total … (before the merge P: pick a response b binning c, merge d, after it
  e)» line, the stage ends computed from the worker's own times), w_chroma (the chroma median line's end), ref_copy,
  post_compile (GLProg.takeProgramStats, with a note «post_compile_ms=compiled/ms+binaries bin/ms»); notes thermal_press /
  thermal_end (util/ThermalState: PowerManager thermal status name / headroom) and vf_fps_press / vf_fps_end (VfDrawMeter.fpsLast2s:
  the frames drawn in the last 2 s, recorded at every draw). The timeline line is printed in time order. Fix on the way: worker
  lines starting with 'S' were filtered out, so «SCAM STOCK MOTION» (w_align) and «SCAM CAPTURE OK» (w_ok) never marked.
  One line per shot «SHOT SUMMARY ms: hold= to_worker= merge= gap= post= save= total= <notes>» (-1: a stage the route does not
  have) in the log and, whatever the profiling switch, in Download/SCAMERA/SCAMERA-timings.txt (ScameraTimingLog.summary).
- Checked: ShotTimelineTest 4/4 (new, CI: the owner's total line parsed, the marks in order, the summary's fields, no timeline
  opened by a GL pass outside a shot), VfDrawMeterTest 2/2 (+1), ScameraTimingLogTest, DevKeysCoverageTest, FullResZslTest,
  GLProgramDiskCacheTest, ShotSpeedBitExactTest.
- Phone (not tested): «SHOT SUMMARY» per shot with gap close to the client's output map (~0.1 s) instead of 0.4-0.6 s; the
  release line after the post pipeline has started.

### P237 - the shader disk cache: counts, fsync, idle compile of missing programs (roadmap speed-ux B5)
- Evidence: the X7U compiled all 36 post-pipeline programs on its first shot (790 ms, owner's log 20:20:54) and again after the
  P211 reboot (746 ms, 20:44:41) although GLProgramDiskCache (P69) keeps their driver binaries; the X300U 493 ms. The P211 reboot
  was a kernel panic: binaries written by the writer thread were still in the page cache. The cache said nothing about hits.
- Now: each binary (and source) file is fsync'ed before its rename; the cache counts hits / misses / damaged / written / evicted,
  and «runAll programs: N compiled in X ms, M from driver binaries in Y ms (disk H hit / M miss / D damaged / W written / E
  evicted)» says it per shot (GLProg.takeProgramStats). Every compiled program's cache key (its source) is kept driver-independent
  as gls-<sha>.src (LRU, 160 files / 16 MB, touched on every hit); GLProg.warmUp compiles, in its own 1x1 context on a
  background thread, the kept sources whose binary this driver lacks (a driver update, an evicted or lost binary), at most 64,
  stopping between programs when a shot starts. CaptureController.scheduleProgramWarmUp runs it once per process 4 s after a
  session opened when the camera is idle (no shot, nothing processing, no FULLRES merge); scam_dev gl_warm_up 0 = off (DevKeys).
  Log «program warm-up: K of N missing binaries compiled in idle time in T ms». A first install has no kept sources: its first
  shot still compiles (the defines of the post pipeline come from the shot).
- Checked: GLProgramDiskCacheTest 5/5 (+2: counts, the missing sources of a new driver, a damaged source deleted), DevKeysCoverageTest,
  CameraResumeTest, ShotTimelineTest.
- Phone (not tested): after an app update / reboot the first shot's line shows «0 compiled ... 36 from driver binaries», the
  warm-up line once per start when something was missing.

### P238 - thermal awareness: the state at the press and the merge end, a policy at SEVERE (roadmap speed-ux D2)
- Evidence: the app never read the thermal state; ColorOS force-stopped it for heat mid-merge (P200, «o-stop(22)», 53 °C) while
  the FULLRES session developed and ringed 96 MB frames at 24 fps; P200's open question (GPU throttling or the low priority) had
  no data.
- Now: util/ThermalState (PowerManager.getCurrentThermalStatus, getThermalHeadroom(0) at most once a second) is logged
  «THERMAL at the press: <status>/<headroom>» (SCAM_CAPTURE) and «THERMAL at the merge end: …» (SCAM_HDR, also the timeline note
  thermal_merge_end; P236's thermal_press / thermal_end are in the SHOT SUMMARY). util/ThermalPolicy follows the platform's
  thermal listener (registered once per process at the first session; «THERMAL status a -> b» lines) and every press's sample.
  At SEVERE or worse: the developed RAW viewfinder publishes at most 15 frames a second (ThermalPolicy.viewfinderFrameDue: every
  second frame of a 30 fps stream; the AE, the ring and the stream still see every frame; the ISP preview's texture draw is
  left alone - its buffers must flow back to the camera); the FULLRES ring pauses («50 MP ZSL ring paused: the phone is hot
  (thermal status SEVERE)», its frames dropped so no stale frame becomes a ZSL frame; a press takes the series after the
  shutter; «… runs again» after); a FULLRES press shows «Телефон горячий — 50 Мп будет дольше» (the shot's megapixels; at most once
  in 5 min). scam_dev thermal_policy 0 = log only (DevKeys).
- Checked: ThermalPolicyTest 4/4 (new, CI: no cap below SEVERE, 15 of 30 fps at SEVERE, the notice once in 5 min, the status
  names), DevKeysCoverageTest, CameraResumeTest, FullResZslTest, NativeViewfinderTest, ScamHybridBurstAnyResolutionTest;
  check_ui_language PASS.
- Phone (not tested): the THERMAL lines per shot; a hot phone (or `adb shell cmd thermalservice override-status 3`, a system
  setting change the owner does himself) shows the viewfinder at ~15 fps in VF_DRAW, the ring pause line and the notice.

### P239 - the FULLRES ring copies 8-12 frames a second and rests on a still, untouched phone (roadmap speed-ux D1)
- Evidence: ringCopyDue only waits twice the last copy's time; with 7-10 ms copies every frame went into the 50 MP ring (150
  copies in 6.35 s, ~23.6 a second x 96 MB = 2.3 GB/s) for a ring of 3-10 slots; ColorOS already killed the app for heat (P200).
- Now (capture/FullResRingPolicy): at most 12 copies a second (rateDue: every 2nd frame of 24 / 20 fps, every 3rd of 30), and the
  ring rests after 10 s with no touch-down / key on the camera screen (CameraActivity.onUserInteraction) and the gyro still for
  as long (Gyro.getFilteredShakiness below the tripod threshold 25): its frames go (P238's pause path: «50 MP ZSL ring paused: no
  touch for 10 s and the phone still»), a touch or a moving gyro starts it again («… runs again»). scam_dev fullres_ring_fps
  (1-30, 0 = every frame as before) and fullres_ring_idle_s (0 = never rests), DevKeys. The ring copy line says «at most 12 a
  second».
- Default on, decided from the merge's frame selection (selectHybridRing / drainZslNormalFrames): frames 83-100 ms apart stay far
  inside its stale gap (ZSL_RING_MAX_GAP_NS 500 ms), the exposure-tier and crop rules do not depend on the rate, the ring keeps
  its slots (the same candidates once it ran slots / 12 s: <= 1 s for the 50 MP ring's <= 10 frames), and a press while the ring is
  short (just after an AE change, a resume) takes the missing N after the shutter (P232's split): the merge's N count is
  unchanged. What changes: the ring's N span about twice the time (10 frames: ~0.8 s instead of ~0.4 s) - more hand motion
  between them for the alignment / rejection; a press right after a rest is a PSL series. Both to check on the phone.
- Checked: FullResRingPolicyTest 2/2 (new, CI: 12 of 24, 10 of 30, 10 of 20 fps, 0 = every frame; rest / wake by touch and
  motion, 0 = never), DevKeysCoverageTest, CameraResumeTest, FullResZslTest, ZslRingBudgetTest.
- Phone (not tested): «50 MP ZSL ring: copy … (N frames this session …)» grows ~12 a second (was ~24); the battery current_now
  and the temperature against the old build; HYBRID rejection counts and photos of handheld 50 MP ZSL shots with
  fullres_ring_fps 0 vs default (A/B); the pause / resume lines on a phone lying on a table.

### P240 - «Камера перезапускается…» during a camera recovery; an untested route says «experimental» on its first use (roadmap speed-ux C4)
- Evidence: the X300U 200 MP attempt (older build): error 4, then 7 retries of 700 ms, the viewfinder frozen 7.4 s (21:41:34 ->
  21:41:41.6) with nothing on screen but the error card.
- Now: CaptureController.recovering(on, why) («camera recovery started / ended (why)» in the log) -> CameraEventsListener.onCameraRecovery
  (new, default no-op) -> CameraFragment lays «Камера перезапускается…» / «Camera is restarting…» (res camera_restarting) over the
  viewfinder (a dimmed full-size text, not clickable; hidden after 30 s at the latest). On: every CameraDevice.onError while
  resumed (all its paths open the camera again: the FULLRES / sensor-mode fallbacks, the module / camera revert, scheduleRecovery),
  every retryOpen. Off: the next session configured, the recovery given up (three in a minute), the retries exhausted, an error
  without a recovery («Камера недоступна»).
- Experimental routes (capture/ExperimentalRoutes, prefs file scam_experimental, once per install): the P208 200 MP stock route
  (X100 / X300 Ultra, stillOnlyFullRes, not OPPO) says on its first session «Экспериментально: 200 Мп как в стоковой камере …
  ещё не проверено на телефоне. Если снимок не выйдет — выключите «vivo_fullres200_stock» в «Для разработчика»» on the message
  card; log «experimental route p208_stock_200mp: first use».
- Checked: ExperimentalRoutesTest 1/1 (new, CI), CameraResumeTest 24/24, LangTest, MessageCardTest; check_ui_language,
  check_settings_model PASS.
- Phone (not tested): a HAL error (or a camera switch into a failing module) shows the overlay until the viewfinder runs again;
  the P208 notice once on the X100U / X300U 200 MP.

### P241 - diagnostics: a «DIAG» badge, off by itself after 24 h, the dumps written off the processing thread (roadmap speed-ux C6)
- Evidence: with diagnostics each shot writes 5 SCAM_DIAG dumps; on the X300U debug log (08:56:53-57) a 12 MP shot took 5.6 s
  against 3.0-3.6 s without (x300u_log.txt 21:41); timing complaints may come from diagnostics sessions nobody remembered.
- Now: ScamDiagnostics.buffer samples the stage on the calling thread (its own copy) and writes the PFM, the PNG (compress 100)
  and stages.txt on the single «SCAM-diagnostic-export» thread, in order with the ZIP export (queue 32; full: written in place as
  before). ScamDiagnostics.gpu reads the sampled rows through a pixel pack buffer, 64 rows per fill and one map each (11 waits for
  a 50 MP stage instead of 682 per-row readbacks); the same rows and pixels, any GL error falls back to the row-by-row read. The
  photo is not touched (the dumps only read).
- settings/DiagnosticsAutoOff: the stored switch (pref_scam_hybrid_diagnostics, legacy pref_scamhdr_diagnostics) goes off 24 h
  after the camera first saw it on (the time in prefs file scam_diag; a clock set back starts the 24 h again), said on the card
  «Диагностика выключена: прошло 24 часа (она замедляет каждый снимок)», log «diagnostics switched off: on for 24 h». A scam_dev
  hybrid_diagnostics line is left alone. CameraFragment shows «DIAG» (res diag_badge, amber on a dark plate, top left of the
  viewfinder) while the stored switch or the scam_dev line is on; checked at every camera start (onCharacteristicsUpdated).
- Checked: DiagnosticsAutoOffTest 2/2 (new, CI), SettingsMenuTest, HybridSettingsTest, SettingsAuditFixTest; check_ui_language,
  check_settings_model PASS.
- Phone (not tested): the badge with «Диагностика» on; the 12 MP shot time with diagnostics against without (SHOT SUMMARY); the
  ZIP still complete (stages.txt, the PNG / PFM of each stage).

### P242 - scam_dev worker_gpu_normal_priority: the merge worker's GPU priority for an A/B (roadmap speed-ux B6)
- Evidence: the app's merge took 0.86-1.15 s a frame, the P204 replay ~0.63 s; during a FULLRES merge the viewfinder is the cheap
  ISP one (displaying=ISP), so P57's low-priority worker contexts (scam-superres-gpu.h scamProcessingContext,
  EGL_IMG_context_priority LOW unless SCAM_GPU_NORMAL_PRIORITY is set) may only slow the merge down.
- Now: util/WorkerEnv builds the worker's environment (WorkerSpawn.environment, the merge and the GPU prewarm) and adds
  SCAM_GPU_NORMAL_PRIORITY=1 with scam_dev worker_gpu_normal_priority 1 (DevKeys, «Склейка Hybrid (воркер)»; default 0 = low, as
  since P57). Each job logs «WORKER GPU priority: low (EGL_IMG_context_priority, P57 default) | normal (scam_dev
  worker_gpu_normal_priority 1)» into the worker report, and the SHOT SUMMARY note w_gpu_priority=low|normal sits beside the
  thermal state (P238) and the merge time. The worker source is unchanged (it read the variable already); results are
  unchanged (a scheduling hint).
- Checked: WorkerEnvTest 1/1 (new, CI: the environment as before P242, one variable more with the key), DevKeysCoverageTest,
  WorkerWatchdogTest, ScamHybridBurstCrashTest.
- Phone (A/B, not run): the same scene 3x with the key 0 and 3x with 1 - «HYBRID STAGES ms: merge=», «SHOT SUMMARY … merge=»,
  thermal_merge_end and VF_DRAW during the merge; keep the default unless the merge is clearly faster without a stutter.

### P243 - «DNG colour = JPEG»: the DNG carries the JPEG's white balance and colour matrix (the P221 follow-ups, defined from the code)
- What was planned: nothing - no P221 entry in PLAN.md, no «P221» / «DNG colour» / «native colour pass» in the code or the
  research notes of this tree. Defined here from the code:
  - «DNG colour = JPEG»: DngCreator wrote the camera's static dual-illuminant profile (SENSOR_COLOR_TRANSFORM1/2, FORWARD_MATRIX1/2,
    CALIBRATION_TRANSFORM1/2) with AsShotNeutral = Parameters.whitePoint, while the JPEG renders with whatever Parameters chose:
    the ISP's colour transform of the result (vivo: the DNG matrices are generic, «Using the ISP colour transform»), the OPPO tuned
    forward matrix per CCT (OppoTunedColor), the plain-session matrix of a mosaic shot (PlainColourMemory), a DCP (DcpProfiles), or
    the DNG interpolation itself - only the last matched Lightroom / ACR. Implemented below.
  - «native colour passes»: the worker's native mosaic merge switches the colour-difference, clip-border (rim) and Bento colour
    passes off («HYBRID MOSAIC NATIVE: colour-difference, clip-border and Bento colour passes off (they work on the binned frames;
    native variants not built)», scam-hybrid.h). Native variants of them change every FULLRES / ISZ photo and can only be judged on
    the owner's photos with replays and new reference md5s; not implemented here (left as a plan item: build them behind a default-off
    tuning key, replay the syn_b* / x7u / hand / isz2 series, compare photos, then decide the default).
- Now (processing/color/DngColour): the JPEG's linear render of a camera value c is intermediateToSRGB · sensorToProPhoto · c
  (ark/low.glsl, combine.glsl); with the render's own XYZ -> sRGB (Converter.sXYZtoSRGB, D50-adapted) the DNG's tags are
  ForwardMatrix1 = sRGB->XYZ · intermediateToSRGB · sensorToProPhoto · diag(n), scaled to Y(FM · 1) = 1, and ColorMatrix1 =
  diag(n) · FM^-1 · Bradford(scene light (Parameters.sceneCct, CIE daylight / Planckian locus) -> FM · 1), one illuminant (label 21
  written for both CalibrationIlluminant tags), no ColorMatrix2 / ForwardMatrix2 / CameraCalibration: a raw converter has nothing to
  interpolate and renders FM · diag(1/AsShotNeutral) - the JPEG's colour; the temperature readout follows the scene light. The «Цвет»
  look's white matrix (warmth / tint) and the tone are not camera colour and stay out. ScamProcessor sets Parameters.dngColour
  for its DNG writes (the merged DNG and the reference RAW), DngCreator writes it and logs «DNG: the JPEG's colour: CM1 … FM1 …
  (FM white xy …), illuminant 21, single»; not usable matrices -> the camera's profile with a warning. scam_dev dng_colour_jpeg 0 =
  the camera's profile as before (DevKeys). The gallery's DngPreview (FM1 · diag(AsShotNeutral^-1)) shows the same colour.
- Checked: DngColourTest 4/4 (new, CI: the ISP-matrix case renders the JPEG's colour through the DNG model for four camera
  values, white stays D50; the DNG-interpolation case gives back its normalised forward matrix; CM1 · XYZ(scene) = the neutral
  at 2856 / 4300 / 6504 K; the D65 / A locus points; the guards), DngMetadataTest, DngPreviewTest, ParametersHandSetWbTest,
  OutputColourTest, DevKeysCoverageTest.
- Phone (not tested): open a vivo / OPPO FULLRES and a 12 MP DNG in Lightroom / ACR with the «Embedded» profile, «As Shot» white
  balance, beside the JPEG (no tone: compare the colour of neutrals and skin, not the contrast); the log line per DNG.

### P244 - import of a camera's linear tone curve (owner, 2026-10-18: ToneCurve_imx06A.txt, «Добавь поддержку такого формата кривых нам»)
- The file: 128 pairs x0, y0, x1, y1 ... as a bare comma list, x 0..1 in steps of 1/127 - vivo's DNG ProfileToneCurve for the
  IMX06A (the tag is in vivo's camera, evidence/vivo_camera_2026-10-13/dexstrings.txt), which the DNG specification gives «in
  linear gamma»: linear in, linear out.
- DONE 2026-10-18: ToneCurve.linearPairs recognises such a list (even count >= 4, all 0..1, x rising from 0 to 1, no "x:y", no
  words); fromLinearCurve turns it into our curve (x = 0.5 + EV / 10 around linear 0.18, y = sRGB display): sampled every 0.03 of
  x up to the curve's white (linear 1 = +2.47 EV), flat above. ToneCurve_imx06A puts middle grey at y 0.571. Import and the
  ToneCurves folder listing take it through fromFileText. Test: ToneCurveTest.aCamerasLinearToneCurveFile (the owner's file in
  test resources).
- Phone (not tested): the look of the imported curve against vivo's own JPEG.

### P245 - the horizon level: a soft, light vibration when it becomes level (owner, 2026-10-18: «Для уровня горизонта когда он стоит ровно добавь мягкую легкую вибрацию»)
- One short light tick (HapticFeedbackConstants / a light VibrationEffect) when the level indicator enters «level», none while
  it stays there; again only after it has left level by a margin (hysteresis, no buzzing at the edge). Respect the system's
  touch-feedback setting.
- Found: P174 already ticked on the way in, through the View's HapticFeedbackConstants.CLOCK_TICK - which the OPPO / vivo
  phones play as nothing.
- DONE 2026-10-18: Vibration.Soft - an 18 ms pulse at amplitude 70 where the vibrator has amplitude control, the system's
  EFFECT_TICK otherwise, USAGE_TOUCH, skipped when the system's touch feedback is off; HorizonIndicatorView plays it once on
  the way in (level within 0.5°, out beyond 1°). Tests: HorizonIndicatorViewTest. Phone: not felt yet.

### P246 - configs put into Download/SCAMERA/XML by hand are not listed (owner, 2026-10-18: «если я в папку добавляю новый конфиг вручную не через наше приложение, то у нас его не видно»)
- ScameraFolder.list: MediaStore lists only the app's own files (Android 11+); other files only through the folder grant
  (grantedTree, the P2xx fix of 2026-10-16). To check: is the grant asked for / kept on the config screen, does the listing
  merge the grant's files, does the list refresh when the screen is shown again.
- Found: the listing does merge the grant's files, but the only way to give the grant was the shade's config menu
  («Открыть папку конфигов») - the settings page «Профили» that replaced it had no such row; a folder picked under the picker's
  «Загрузки» root (ids "raw:/storage/emulated/0/...") was not recognised; the page did not read the folder again on return.
- DONE 2026-10-18: ProfilesPreference shows «Открыть папку конфигов» while the folder is not open (the settings' folder picker,
  once); the list is read again on every return to the settings and after the grant; ScameraFolder accepts a «Загрузки» tree
  (primaryId / documentIdFor) and compares ids without case. Tests: SettingsMenuTest.profilesPageListsAndLoadsConfigs (the row,
  a hand-copied config listed after the grant), the covers / primaryId asserts.

### P247 - check the shutter sound and the timer sound switching off and on (owner, 2026-10-18: «Проверить отключение и включение звука затвора и таймера»)
- Both settings, both ways: off -> no shutter / timer-tick sound (where the system lets an app stay silent), on -> the sound
  back; after a restart of the app; the timer's countdown ticks and the shutter of a timed shot follow their own settings.
- Checked in code: both switches are read per shot / per countdown (global keys, not per module; CameraSoundsTest,
  SettingsAuditFixTest), silent / vibrate mute both, the region rule forces the shutter.
- Found: the X200 Ultra's CTS package (MP3 crashes natively there) had no shutter sound at all since P188 and a synthesized
  beep for the timer - the shutter switch did nothing there.
- DONE 2026-10-18: there the real two sounds play from 16-bit mono 24 kHz PCM (res/raw/sound_shutter_pcm.raw 0.13 s,
  sound_timer_pcm.raw 10.7 s, decoded from the MP3s) through static AudioTracks, under the same switches; ToneGenerator gone.
  Test: CameraSoundsTest.thePcmCopiesOfBothSounds. Phone: to hear on the X200 Ultra (switch off / on, the timer 3 s / 10 s).

### P248 - a settings row with a long value squeezes its title and description into a narrow column (owner, 2026-10-18, screenshot of «Режим автофокуса» on «Экран камеры»: «исправить описание параметра, оно не должно так криво отображаться»)
- The value «Непрерывный (фото)» on the right takes most of the row: the title wraps by word, the description runs one or two
  words a line, the «Камера перезапустится» chip breaks mid-word («пере / запустится»).
- Fix: the value gets at most about 40 % of the row and ellipsizes (or moves under the title when it is long), the title /
  description column takes the rest; the chip never breaks inside a word (one line, or wraps as a whole). Check every
  list-type row on the narrow screens of ScalingMatrixTest.
- Owner, 2026-10-18 (two more screenshots: «Кадры светов: число / проверка», «Разрешение»): «исправь все вот такие описания».
- DONE 2026-10-18: every preference_card row - SettingsStyle.bind measures the value's line: wider than VALUE_SHARE (38 %) of
  the row's text width (screen less card margins, padding, icon, chevron) it shows under the title (settings_value_below,
  13sp, the title's colour) and the right-hand value is hidden; a short value stays on the right, at most that share wide.
  Test: SettingsMenuTest.aLongValueGoesUnderTheTitle. Phone: OPPO screenshot of «Экран камеры» after the change.

### P249 - the shade's default «Размер · 12 Мп» tile becomes the Sabre output tile 1x / 16 / 20 / 2x (owner, 2026-10-18, screenshot of the tile: «по дефолту у нас есть эта настройка в шторке, замени её на плитку с настройкой Sabre 1x/16/20/2x которая у нас есть»)
- The tile is the settings' «Разрешение» (pref_scam_hybrid_output, PreferenceKeys.HYBRID_OUTPUT_VALUES: «Сенсор (1×, по
  умолчанию)», «12 МП (2× → 12)», «16 МП (2× → 16)», «20 МП (2× → 20)», «2× (≈50 МП, без уменьшения)» - the owner's second
  screenshot: this list, the one with «В шторку»), all five values, labelled as there; it takes the «Размер» tile's place in
  the default shade layout (a user's own layout is kept). The output size stays reachable from the top bar's «12 Мп» pill.
- SettingsAvailability rules apply (where the hybrid merge does not run, the tile is shown as off, with the reason).
- DONE 2026-10-18: ShadeCatalog.DEFAULT_TILES has «Разрешение» (OUTPUT, its catalog entry: 1× / 12 / 16 / 20 / 2×) at «Размер»'s
  place; SettingsMigration.migrateOutputTile swaps them once in a stored list that has «Размер» and not «Разрешение». The
  «12 Мп» pill of the top bar still opens «Размер». Test: ShadeTilesTest.theResolutionTileTakesThePhotoSizeTilesPlace.

### P250 - the diffusion filters as a physical model (owner, 2026-10-18: «Посмотри как симуляция сделана ... и исправь у нас»)
- Before: the light above a knee spread by a fixed six-level Gaussian PSF, plus a separate lift and veil - a threshold edge,
  a look that changed with the frame's level, and no colour structure in the halo.
- DONE 2026-10-18: DiffusionModel - a share p of all the light (from the filter grade: our strength 0.5 = grade 1/2) spread by
  the filter's PSF, energy kept: out = (1 - p) E + p (K * E). K = core + halo + bloom, each a geometric range of 2D
  exponentials (the bloom with a power-law tail); sizes in micrometres of a 36 mm frame (the same share of the frame at any
  resolution); the halo tinted by moving warm light to its rim and cool light to its core without adding energy. Families:
  Glimmerglass, Black Pro-Mist, Pearlescent, CineBloom, Fog, Double Fog. Fine controls: «Ореол» the halo's share, «Радиус» the
  size, «Дымка» the bloom's share, «Тон свечения» the warmth. On the GPU / CPU the PSF is a sum of the image's own pyramid
  levels (to ~2 px) with per-channel non-negative weights fitted to its encircled energy (photo: gaussdown + B-spline widths;
  viewfinder: the mip pyramid's); Glimmerglass keeps its short star, Pearlescent its pastel; no exposure dependence left.
- Tests: DiffusionModelTest (grade table, warm halo energy, fit within 1 % encircled energy, size invariance), LensFiltersTest
  (energy kept exactly, the glow falls off, the controls), LiveLensFilterTest. Phone: OPPO viewfinder / photo, see below.

### P251 - pink highlights on an X300 Ultra photo (owner, 2026-10-18, Desktop.rar: SCA_20261014_110119.jpg)
- The photo is build 30798 (2026-10-14, Route=Hybrid, ISO 1089, 1/100 s): the white church and the sky through the bus window
  pink - a clipped channel shifted by the white balance. The highlight work after it (P215 merged clip at the real ceiling, P216
  shoulder room, P212 white balance) may already cover it; ISO 58 of the same scene is clean.
- Open: a shot of the same kind of scene on the current build (with its log / DNG) to see whether it is still there.

- Closed 2026-10-19 without a device (owner: no X300 Ultra at hand): the highlight work since build 30798 (P215, P216, P212) and
  P268 (a censored G never ends below both R and B, the ultrashort frames no longer refused on hand shake) cover the cause; reopen
  with a current-build shot if pink comes back.
### P252 - the OPPO Find X7 Ultra does not focus at night (owner, 2026-10-18/19: «Оппо почему-то не фокусируется», «на всех», «На виво фокус работает нормально»)
- Found and fixed (3b414aa7): unlockFocus - run at every session start - left AF_TRIGGER_CANCEL in the repeating preview request
  (every frame cancelled the AF); lockFocus sent START repeating; a SCAM-routed shot never released its AF lock. Test:
  AfTriggerRepeatTest.
- Still open: in a dark, far scene (a ship's deck at night) the HAL's continuous AF reports PASSIVE_FOCUSED with the lens at its
  near limit (7.14 dpt) on cameras 2 / 4 / 5; a tap (AUTO + trigger) locks there too. Manual focus at ∞ (0.077 dpt) is sharp, the
  stock camera (logical camera 6) is sharp; indoors near (a laptop) our AF was sharp.
- Ruled out by test builds on the phone: the 20 fps floor, face detection / FACE_PRIORITY, the metering regions (stock's
  zero rectangles), the RAW stream in the repeating request, the package spoof, the stock's mode tags (com.oplus.camera.mode
  "photo_mode", is.from.main.menu, light.sensor.lux, ai.scene, salient object).
- Next: the physical camera opened alone vs the stock's logical camera 6 (AF of the logical SAT use case), OIS on the physical
  request, the session parameters; a fallback if the HAL stays parked at the near limit in the dark (our contrast sweep on the
  preview, or the hyperfocal distance).
- 2026-10-19 03:20, the OPPO on the night scene with 31121: CAF, PASSIVE_UNFOCUSED, the lens STATIONARY at 7.14 dpt beyond the
  declared 5 dpt (focusRange 5..0), 60 ms ISO 3842 - the AF never drove the lens. The use-case idea (the ISP preview in the
  session, not in the request: RealTimeFeatureZSLPreviewRaw) concerns the 8192x6144 FULLRES session; an A/B switch
  (scam_dev isp_preview_off_always) did not take effect in its first try and was not needed.
- DONE (local, phone-checked): ParkedLens - a continuous AF that leaves the lens beyond the declared nearest focus (margin
  10 % + 0.1 dpt), STATIONARY, for 1.5 s: the viewfinder and the shot take the hyperfocal distance (f, aperture, two-pixel CoC)
  until the scene gets 8x brighter (the HAL's AF again, 5 s cooldown); a touch focus / hand-set focus takes the lens from it;
  the shutter skips the AF lock while AF is off (no 1 s wait). OPPO: 1x lens 7.14 -> 0.153 dpt, 2.8x -> 0.044 dpt, the night
  photo SCA_20261019_033808 sharp (the 01:25 one fully blurred). Test: ParkedLensTest.

### P253 - a flickering lamp painted light blue in a 12 MP photo (owner, 2026-10-19, X200 Ultra build 31113: «исправь работу этой настройки» (Мерцание ламп), «ты смотри на лампу справа сверху», «исправь это во всех режимах»)
- The shot: ISO 245, 10 ms base (whole 100 Hz periods: no bands in the frame), Route SHDR, plain 12 MP. The clipped ceiling lamp
  came from the ultrashort frame (Bento), read row by row through the lamp's 100 Hz flicker (P197's cause): its colour wrong.
  P197 had added the row check to the mosaic paths only («the plain 12 MP path is unchanged by design»).
- DONE 2026-10-19 (fc7ef964): hybridReconstruct runs the same check (hybridPlainUsRows) on a plain burst's ultrashort frames -
  measured in place, a corrected copy only when a band departs by 8 % or more (above 64 MP refused instead of copied), refused
  when the gain check stays inconsistent; HybridInput.usRowsChecked keeps the binned path from checking twice. Every mode now.
- Checks: tools/check_hybrid_us_rows.cpp plain case (row flicker corrected to MAD 2.6 %, consistent frame untouched without a
  copy, column ramp refused, mosaicUsRows 0 unchanged) - NDK build run on the OPPO: OK. OPPO replays old (fa9941fc) vs new: the
  8 references and q1x / uw / hh_1 / arena11 / syn_b2m bit for bit; uw (plain, 2 ultrashort): «HYBRID PLAIN ... 2.5 % -> as
  captured». Phone: a 12 MP shot of a flickering lamp on the X200 Ultra (log line «HYBRID PLAIN»).

### P254 - vivo X300: «Камера не запускается, error 4, перезапуск» on every camera's first start (owner, 2026-10-19: «при первом запуске каждая из них сопровождается надписью ... убедись что нигде не будет такой ошибки»)
- Log (V2515, 2026-10-18): each camera's first session of an app run - endConfigure -38 with the vivo EIS session key ->
  onConfigureFailed -> retryWithoutScamSession (plain preview) -> the HAL closes the device (onError 4) -> «Сбой камеры (код 4),
  перезапуск» + the recovery overlay, or for a module without a frame yet «Модуль 3× не запускается ... возврат к предыдущему»
  (revertToLastGoodModule). The plain-preview set was per process, so every app start repeated it.
- DONE 2026-10-19: PlainPreviewCameras keeps a refused camera per Build.FINGERPRINT (SharedPreferences scam_plain_preview): the
  next runs start with the plain preview. onConfigureFailed marks the device (mRetryDevice) while its retry chain runs; an onError
  on that device reopens the camera quietly (no overlay, message or module revert). onError waits up to 0.5 s for the session's
  own callback (mConfiguringDevice), since on the X300 both came within 1 ms on different threads.
- Checks: PlainPreviewCamerasTest (storage per firmware, the quiet path before the revert / message). Phone: X300 first start
  of each camera (log «closed by the HAL ... reopened quietly»), then an app restart (log «refused the vivo preview keys on this
  firmware»).
- Round 2 (owner, 2026-10-19, build 31093 = ba778e96 on the X300 PD2502 / V2502A, screenshot «Camera is restarting…»: «исправь
  полностью это»): the quiet reopen ran, but (1) the HAL restarted after the refusal (~5 s «unknown device»), the open
  retries put the overlay up; (2) the quiet reopen's session failed meanwhile (Broken pipe -32) and retryConfigureFallback took
  camera 2 to YUV without RAW for the process; (3) after an app restart the refusal was forgotten: BrandMigration rewrote
  "vivo/" in the stored Build.FINGERPRINT on every launch - every per-firmware note of a vivo phone (ExposureLimits,
  FullResTrials, VivoQcomIsz notes, PlainColourMemory, OppoPlainState stamp) was lost the same way.
- DONE: BrandMigration keeps a word next to '/' (paths, fingerprints); PlainPreviewCameras.KNOWN_REFUSERS (PD2502, V2502A,
  V2515) never send the vivo EIS session key; a configure fallback step of a device the HAL then closed is undone (once per
  camera and process); open retries within 12 s of a quiet reopen keep the overlay off. Tests: PlainPreviewCamerasTest (5),
  BrandMigrationTest (the fingerprint stays).

### P255 - the configs menu back in the shade (owner, 2026-10-19: «вернуть меню конфигов из старого интерфейса в шторке, но обнови его под нашу новую логику и стиль»)
- The old shade (P143, 9798403a: SettingsBarLayout + ConfigMenu - the active config in a frame, a menu to save, load, import,
  reset; the code is still in SettingsBarLayout.configChip) is not reachable in the current shade. Bring it back in the current shade: the profiles of
  «Профили» (ModuleProfiles / BackupRestoreUtil, the Download/SCAMERA/XML folder incl. configs put there by hand, P246), the
  shade's tile style, Lang.t texts. Open: where it sits (a tile or the shade's header), what «reset» does under the new model.
- DONE 2026-10-19 (local): the profile button (ConfigChip) is back in the shade's header in the title's place («Перетащите
  плитку» shows while the tiles are edited); ConfigMenu speaks the «Профили» page's words - «Активный профиль», «Сохранить»
  over it, «Сохранить текущие как…», «Удалить профиль», «Сохранённые» newest first (load / delete), «Открыть папку конфигов»
  until the folder is open, «Загрузить из файла», «Сбросить всё»; ConfigState «Без профиля». Reset = BackupRestoreUtil
  .resetPreferences + restart, as the page's «Сбросить всё». Tests: ConfigMenuTest (button visible, title while editing).

### P256 - the X200 Ultra torch does not light when switched on (owner, 2026-10-19: «на X200 Ultra не работает вспышка»; «Фонарик не горит»; «если я оставляю кнопку с фонариком включенной и перезагружаю приложение, то фонарик включается, но если после этого я его выключу и опять включу то он не работает»)
- The button is the torch (pref_ae_mode 0 = FLASH_MODE TORCH in the repeating request); camera 3 declares a flash (dumpsys:
  flash.info.available TRUE, torch strength default 5 of 5). Started with the torch on, a session lights it; switched on in the
  running repeating request (setPreviewAEModeRebuild) the vivo HAL keeps it dark.
- DONE (local): on vivo (ScamPreview.supported) switching the torch on creates the session again (not under a shot); the shot's
  still request keeps FLASH_MODE TORCH while the torch is on (before: only when the preview result said FIRED); the first 15
  results after the switch log FLASH_MODE / FLASH_STATE. Test: TorchToggleTest. Phone: X200 Ultra torch off -> on.

### P257 - «Увеличивать кадр с кропом» did nothing (owner, 2026-10-19, screenshot of «Размер фото»: «настройка со скриншота не работает»)
- ScamProcessor cropped the digital zoom's residual after the resize to the photo size and never brought it back: on or off,
  a crop shot came out at the cropped size (ScamHybridBurst's keepCrop only changed the final size when the output was not
  the sensor size).
- DONE (local): on, the crop is taken first (on the merge's full image, the 2x grid's detail kept) and the cropped image goes
  to the photo size (ScamProcessor.photoSize), up if need be: ScamPostDownscale.scaleTo - Lanczos-2 in linear light, native
  scamera_lanczos::resample / Lanczos1010102.resample with an upscale of at most 1:4 (resize stays downscale only; its tests
  unchanged); the gain map follows. Off: as before (the crop keeps its pixels).

### P258 - our RAW in Scam Remosaic: neural remosaic for shots taken without remosaic (owner, 2026-10-19: «Наш рав проявляется нормально в нашем приложение Scam Remosaic, нам нужно это исправить чтобы при съемке без ремозаика мы могли применить нейроремозаик в нём»)
- Goal: a SCAMERA DNG shot without remosaic (the Quad / Tetra mosaic as captured) opens in Scam Remosaic and its neural
  remosaic runs on it. To check first: what Scam Remosaic does with our DNG today (the owner's «нормально» may mean it is
  developed as a plain Bayer, so no remosaic is offered), which DNG tags it reads for the mosaic (CFA repeat pattern / our
  header, sensor id, black / white levels), and what our no-remosaic DNG writes there.
- Found: without our remosaic the DNG was the merge binned into a plain Bayer W/b x H/b - Scam Remosaic just demosaics it.
- DONE (local): such a shot's DNG is the reference frame's mosaic as captured (full size, one-frame noise profile;
  uncompressed 16-bit CFA, what Scam Remosaic's reader takes); scam_dev dng_mosaic_as_captured 0: the binned merge as before.
  Test: CropUpscaleTest. Phone: a no-remosaic Quad / Tetra shot opened in Scam Remosaic (X200 Ultra).
- Checked against remosaic-app/RAW_FOR_SCAM_REMOSAIC.md (2026-10-19): one frame, no merge / denoise / gain / WB, lens shading
  as OpcodeList2 GainMap (not in the pixels), the frame's ISO and exposure, 16-bit CFA, the blocks' 2x2 order, AsShotNeutral and
  the matrices, sizes of whole 8s - as written. Fixed: «DNG без потерь» compressed it (with a LinearizationTable), which that
  reader refuses: the mosaic DNG is always uncompressed now. Sizes: 12 / 50 / 200 MP alike (the owner: the app takes 50 / 200).

### P259 - 200 MP: «Native FULLRES mosaic has not been verified from RAW pixels» on a flat scene (owner, 2026-10-19, X200 Ultra 2.4x, a blurred sheet)
- Every 200 MP still is checked by MosaicBlockDetector; a scene with no confident colour block verified none, and finish()
  threw - the shot lost although nothing contradicted the Tetra profile.
- DONE (local): a contradicting still still fails at once; with no confident still the shot is kept when a result reported the
  profile's sensor mode (com.qti.sensorbps.mode_index) or a confident still proved this profile before on this firmware
  (VivoSdkFullResCapture.MosaicProof, SharedPreferences scam_fullres_mosaic_proof). Never proven and not reported: fails as
  before. Test: FullResMosaicProofTest.

### P260 - Xiaomi 12X: the shutter sound does not turn off (owner, 2026-10-19)
- Our only sound is CameraSounds.shutter(): off with the switch unless the phone requires it (MediaActionSound
  .mustPlayShutterSound) or the ringer is not normal. No Xiaomi 12X log yet. Added a log line at the first shot of a process
  («shutter sound: switch ..., the phone requires it ..., normal ringer ... -> played / silent»). Open: the owner's log - if
  «silent» while a sound plays, it is MIUI's own; if the phone requires it, that is the region's rule.

### P261 - «ты не решил проблему с разрешением доступа к памяти» (owner, 2026-10-19)
- Asked which one: the owner answered «Другое» without a description yet. Waiting for the description / screenshot.

- Tests: six Robolectric tests mocked ScameraApp without getStringStatic; a PreferenceKeys.Key enum first loaded under such a
  mock kept null names, and every later test of the JVM failed («scope is null») - ShadeTilesTest failed alone even at
  d16a480c. They now stub getStringStatic with the real resources.

### P262 - importing a curve restarts the app, the curve lost (owner, 2026-10-19: ToneCurve_imx06A.txt, ark_art_drama_faces.json: «оно просто перезапускается, а кривая не переносится»)
- Both files parse (P231 / P244 tests). The system file picker in front, Android may stop the camera's process; it comes back
  with the settings recreated, CameraActivity never run in that process: the defaults and the profile listener (PreferenceKeys
  .setDefaults) were set only by CameraActivity and the settings shortcut (P140).
- DONE (local): SettingsActivity sets them up in any process that has not (PreferenceKeys.defaultsSet()); the curve import
  never takes the app down (an error is a toast and a log line). Phone: import both files on the owner's phone; if it still
  restarts, Download/SCAMERA/SCAMERA-crash.log.

### P263 - «Сбой камеры (код 4), перезапуск» on any phone (owner, 2026-10-19: «исправь ошибку код4 чтобы её не было ни на одном телефоне»; seen on the OPPO once after an update, the old process's camera still being released)
- DONE (local): the first device / service error since the last configured session reopens the same camera and module quietly
  (no message, no module revert, no overlay for 6 s); a second before a session configures: the revert / the recovery as before,
  the overlay; a message only when scheduleRecovery gives up. The revert messages no longer carry the HAL's code. With P254
  (a refused configuration's error is quiet as well). Test: PlainPreviewCamerasTest.
- Also: «Камера N недоступна, открыта основная камера M» shows only when the requested camera has not come back after 3 s
  (the OPPO's 2.8x camera 4 came back at once, the note had shown anyway).

### P264 - DCG without root on the X200 Ultra (owner, 2026-10-19: «После того как закончишь все, учитывая опыт с 15 ультра подумай как нам получить DCG без рута на X200 Ultra»)
- After everything else. Study: what the Xiaomi 15 Ultra work taught (P230 Xiaomi DCG route: operation mode / session keys,
  14-bit samples; P104 X500 Pro Max 3RD_APP_HDR scenario), the X200 Ultra's own DCG modes (P122 X300 Ultra mode table,
  forceSensorMode as a session key, vivo.control.* tags the stock app sends for its DCG / 14-bit capture), what a third-party
  app may request without root. Output: a written plan of candidate routes and a test order, before any code.

- Closed 2026-10-19 (owner: «Остальное решай без меня»): the study is the X200 Ultra part of the 15 Ultra DCG entry above (2026-10-18:
  no 0x92xx operation mode in PD2454's CHI / camx libraries, the stock camera runs NORMAL sessions, vivo's sensor-mode selector
  picks the 14-bit DCG modes by the client's AppType, the UI mode, SHDR and the frame rate; forceSensorMode alone under the
  selector's PHOTO geometry gave no RAW frame) and its guarded route (scam_dev vivo_dcg_telegram, off by default) with its test
  order. No further third-party route without root was found; the route's verification needs the X200 Ultra (owner's test above).
### P265 - X300 Ultra «4.8×» / «9.6×»: «камера не включила ISZ-режим 5 (кадры — обычный Bayer)» (owner, 2026-10-19 07:03, V2562, build 31093, «Исправь»)
- Log: forceSensorMode 5 / 7 as a session key; the HAL changes the sensor mode index (camera 5: -> 2), yet the RAW frames are a
  confident plain Bayer on the default stream and on CROPPED_RAW (camera 5 arrays 4080x3072, raw crop the whole array) -> the
  Bayer fallback with the button's zoom as a crop, and the message.
- Found: after the app restart at 07:03:11 the trial began again from NONE (the firmware note lost: BrandMigration rewrote the
  stored Build.FINGERPRINT, P254 round 2) - the restart and the message came back on every app start. Fixed by 2e0277a5: once
  per firmware now.
- Open (needs the X300 Ultra on adb): why mode 5 / 7 give Bayer in our session - the session's other keys (Motion mode
  «Движение» was on: session mode=MOTION; P54b forced stabilisation; the vivo EIS key), the CTS package's AppType, the stream
  set (preview + RAW vs RAW only), mode 31 (2x ISZ remosaiced: is the frame a 2x crop?) - and whether the Bayer frames are the
  2x crop already (then the extra 2x crop of the fallback doubles the zoom). Test order: FOV of 2.4x vs 4.8x on one scene,
  Motion off, RAW-only session, mode 31.
- Owner, 2026-10-19 07:30: the full X300U table again (85 mm: 5 = 2x ISZ Quad, 7 = 4x ISZ Quad-Quad, 6 / 31 remosaiced, 10 /
  13 / 17 16:9 / wide 2x ISZ ...; «Quad Bayer» = ISZ without remosaic; the key works as a SESSION key, as in MotionCam).
- Found in the log: camera 5 runs sensor mode index 2 at 2.4x already (07:02:48), and with forceSensorMode 5 / 7 the index stays
  2 - the HAL ignored the key in our sessions (no other session key was sent: tele availVs [0], no Motion HAL keys, operation
  mode 0). Most likely cause: vivo's sensor-mode selector goes by the client's AppType (P230), our CTS package is AppType 21
  (P227), MotionCam a plain third-party app.
- Done (local): the ISZ trial notes count on the firmware and the camera package spoof (VivoQcomIsz.stamp): with «Подмена
  пакета» on, the modes are tried again. Owner test: «Подмена пакета» with a plain third-party package (MotionCam's), the
  4.8x module; the log's «sensor mode index 2 -> N (camera 5)» and «MosaicStream ... block 2» say it runs.
- Owner, 2026-10-19: «vivo.control.forceSensorMode работает на любом пакете» - the AppType idea is out (the stamp change stays:
  harmless). «Движение» is the app's own mode: the log has no «motion mode ... AE range», so the viewfinder ran its usual range -
  the fps idea is out too.
- Next (needs data): the X300U's dumpsys media.camera while MotionCam runs mode 5 and while SCAMERA runs «4.8×» (session
  parameters, streams with sizes / formats / use cases, the latest request) - the difference is in there; or the phone on adb.

- Closed 2026-10-19 without a device (owner: «X300 Ultra подключенного не будет, у меня лично его нет»): what the log allowed is
  done (the per-firmware note, the stamp); the HAL ignoring forceSensorMode in our session needs a dumpsys from an X300 Ultra
  owner - reopen when one arrives.
### P266 - a per-lens camera setup (owner, 2026-10-19: screenshots of another app's «Настройка камеры / Профили объективов», «сделай как в ...», «это тоже можно добавить»; «сделай так чтобы фпс работал автоматически», «дай возможность выставить вручную количество кадров»)
- In that setup, per lens: the RAW size, OIS for photos, «Дополнительная поверхность ISP» (Off: an invisible Android stream
  that may change which sensor modes are available), «Частота кадров RAW» (the lens's default / a request of its own, «Проверить
  частоты кадров»), «Исходных кадров на выходной кадр» and «Кадров для усреднения» (each group gives one frame, the first ones
  averaged: 120 -> 30 fps), the viewfinder's resolution from the RAW (640x480 ... 4096x3072 Full, the RAW's aspect, the photo
  unchanged).
- Step 1 DONE (local): @SensorConfig «Поток ISP» (Auto / On / Off; Auto = out of the session for a module that forces a sensor
  mode - the X300 Ultra ISZ, P265) - the session has the RAW stream alone, the RAW viewfinder, the shot adds no ISP target;
  @SensorConfig «Частота кадров RAW» (0 = the lens's own; else [f, f] in every viewfinder request and in the session
  parameters). The «Движение» mode's own rate: Auto (default, the photo mode's range) / the camera's maximum / by hand.
- No settings of one meaning twice (owner: «убедись что нигде не дублируются схожие по смыслу настройки ... сделай схоже по
  смыслу с новой реализацией»): the global «ISP-превью в FULLRES и ISZ» (P223) is the modules' «Поток ISP» (Off = the P223 RAW
  viewfinder in FULLRES / ISZ; a switched-off global migrates to Off on the modules with a sensor mode of their own,
  SettingsMigration.migrateIspPreview); the «Движение» mode's own rate rows (incl. the by-hand one of this round) are gone -
  the module's «Частота кадров RAW» (0 = auto) is the one rate; not in the SDK FULLRES session.
- The settings audit (25 overlaps found): merged - «HDR-фото» is one switch for every format (the JPEG's Ultra HDR and the HEIC /
  AVIF gain map; PhotoFormat.KEY_HDR = pref_ultrahdr_key, the format page; «HDR в HEIC / AVIF» on migrates to it, the root
  «Ultra HDR» row gone; WebP alone explains it holds none); scam_dev fullres_zsl is MENU_BACKED («50 Мп: ZSL»); a FULLRES profile
  page hides «Кадров в склейке» (its count is «Кадров в снимке 50 / 200 Мп»); the per-frame black / white level descriptions
  name their source (the frame's metadata) next to «Уровень чёрного по кадру» (the pixels). Kept: the three curve switches (one
  exclusive choice with its own parameter sections), stage-specific rows of similar names (denoise vs merge noise, sharpening
  halo vs RL halo, local-contrast tools, highlight band vs Bento chroma), the overall x per-scene multipliers, «Размер фото»
  (sensor mode) vs «Разрешение» (merge output, P249), the «Движение» merge values (the mode's own copies by design).
- Step 2 DONE (local): @SensorConfig «Разрешение видоискателя RAW» (Auto / 640 / 960 / 1440 / 1920 / 2880 / Full; a plain Bayer
  frame halved - one 2 x 2 quad of each 4 x 4 - until its longest side fits; Full skips the 50 / 200 MP bin too;
  LiveRawFrame.halvings / halveBayer, LiveRawFrameTest); «Проверить частоты кадров» on the module's sensor page (FpsProbe: asked
  there, run a second after the module's next session configured: the camera's fixed rates and 15 / 24 / 30 / 60 / 90 / 120 /
  240 up to twice its fastest, each [f, f] for 0.4 s settle + 0.9 s measured, the median frame interval and the sensor mode
  index; shown as a message and under the button; the module's own range back after; FpsProbeTest).
- Dropped: the frame groups («Исходных кадров на выходной кадр» / «Кадров для усреднения») - in this app they would average frames
  before the merge, which «Кадров в склейке» already merges with alignment - owner, 2026-10-19: «не нужно». Not done.

### P267 - «Наш ремозаик» off: ISZ / FULLRES came out as a 12 MP binned Bayer photo (owner, 2026-10-19: «если выключить наш ремозаик, то исз и фулрезы отключается и происходит съемка в 12мп баера, исправь это»; asked what then: «При выкл сохраняй только днг без ремозаика чтобы можно было его проявлять через сторонние проявщики по типу того же нашего Scam Remosaic»; «Склейка должны быть, просто без ремозаика»)

- Done (8ae78095, local): P195's binned merge is gone. A Quad / Tetra burst without the remosaic (ScamHybridBurst.dngMosaic,
  the old binnedBlock rule) is merged as always on the sensor grid (no 2x), the worker gets `dngMosaic 1` and writes its merged
  RAW in the sensor's mosaic layout (hybridWriteMergedDng with the colour block: native merge and sub-frame split); ScamProcessor
  saves that DNG alone, uncompressed, whatever the RAW setting says, and ends the shot (no JPEG; notice «сохранён только DNG
  склеенной мозаики»). CPU tier (no merged RAW): the reference frame's mosaic as captured. Every merge tells the worker
  `mosaicRemosaic 1`; PreferenceKeys no longer writes `mosaicRemosaic 0`; dev key `dng_mosaic_as_captured` removed; mosaic page
  rows stay live with the switch off.
- OPPO PHY110, APK 31127, 1x FULLRES 50 MP, switch off: worker «HYBRID DNG: merged mosaic layout, colour block 2»; only
  SCA_20261019_102218.dng saved, 8192x6144, compression 1, 16 bit; horizontal neighbour difference within / across the 2x2
  pairs 27.5 / 1140.3 (vertical 24.0 / 1140.8): Quad layout. check_dng_ceiling (block 1/2/4 layout) passed on the OPPO.
- Open: the in-app thumbnail of such a DNG is pink (our gallery demosaics it as Bayer); the X200U ISZ (Tetra) case not shot yet.

### P268 - the 2x2 / 4x4 grid and the highlight artefacts of the mosaic merges (owner, 2026-10-19, X200 Ultra 10:04-10:05 shots + 4 bursts: «нужно исправить сетку на 2x2 4x4 ... особенно она заметна на сверхширике»; 2x crops with ragged clip borders; «нам нужно чтобы таких артефактов не было и без бенто, причём в любом режиме»; «никогда не было фиолетовых засветов»; «С баером тоже не должно быть артефактов»)

- Cause of the grid: the IMX06C (wide cam 4 and main cam 3, sensor mode 3, Quad) has one microlens per 2x2 block: each site
  class's response changes smoothly over the field (wide: -16..+16 % from corner to corner, main ~1.4 % rms), and the merge
  corrected it with ONE gain set per class: a 4 px hatch whose phase flips across the frame (JPEG: period-4 diagonal peaks 170-220x
  the background on the wide, 11x on the main; HP9 tele fine).
- Done (7a998ea4, 12c24b88, local): HybridGainField - the robust estimate also sums its tiles per zone (24 across the longer side),
  a second pass with that field divided out and the strict tile test, each class a cubic of the zone position fitted with Tukey
  weights (scene-biased zones drop out), applied only where it differs from the global set by more than 1 % rms (else the merge is
  bit for bit as before). Applied on the GPU (kHybNatAccess / kHybNatMark natGainAt, binding 19, bilinear between zone centres)
  and in the CPU binning (native and the sub-frame split; per row once for all frames). Tuning mosaicGainField 1, mosaicGainZones 24.
  Replays (OPPO, worker on the owner's bursts): wide grid tiles median 256 -> 20-31, p90 940 -> 72-136; main 22 -> 15, max 1006 -> 332;
  tele 4x Tetra p90 25 -> 10; tele 2x Quad (0.40 %) and every OPPO / synthetic series (<= 0.63 %) unchanged; cost +0.3 s response.
- Done: the mosaic paths' ultrashort check measures on smooth tiles (hybridMeasuredGain smooth): the main 2x burst's three
  ultrashort frames were refused at a MAD of 16.8 % (median 1.2 % from the metadata; hand shake on textured tiles), so no Bento and
  every highlight stayed clipped. Now Bento applies there (mosaicUsSmooth 1; the plain path unchanged).
- Done: a clipped sample is a lower bound in the native mosaic merges (kHybMergeMosaic / Fast, not kHybMergeMain1): a pixel whose
  own sites clipped took the mean of its dark neighbours (G 0.90 -> 0.45 -> 1.00 across a screw head: the dark dots / ragged
  borders of the 2x crops, also without Bento). Now max(unclipped mean, mean with the clipped samples at their clip).
- Done: scamrgb.glsl never makes a censored G end below both R and B (magenta) - G is raised to min(R, B) by its censoring weight.
- Checks: tools/check_gain_field.cpp (smooth field found within 0.65 %, uniform set / gratings / biased zones: no field; in CI),
  check_dng_ceiling, check_highlight_* (scene C: G clipped in the ultrashort under a blue sky), replays md5: plain series identical
  (syn_b1, x7u_1x, hh_1, zip), mosaic series change only near clipped samples (hand: 14403 px, 0.007 max away from clip flags).
- Phone: OPPO 50 MP Quad before / after (31127 / 31128, same scene): magenta highlight pixels 590 -> 186 (> 8), 21 -> 0 (> 16),
  the speckle inside the clipped windows gone. Not yet shot on the X200 Ultra.
- Open (P269): a 12 MP plain Bayer shot on a resting phone (no shifts between frames) keeps orange / blue dashes along slanted bright
  edges, inside the Bento mask where G clipped even in the ultrashort (the Bento colour pass skips any window with a clipped G)
  and outside it (single-frame demosaic false colour).

### P269 - the Bento mask and its processing (owner, 2026-10-19: «Займись затем маской бенто, нужно улучшить маску бенто и её обработку»)

- Start from P268's open item: the orange / blue dashes along a slanted sill edge (OPPO 12 MP, 2026-10-19 11:40, burst kept as
  scam_burst-20261019-114017.bin): kHybBento returns when its wide window holds any clipped ultrashort G; rebuild the colour from
  the unclipped real-site ratios there (as kHybRim does), and look at the mask edge (bentoNearClip, dilate, smooth) for steps.

- Owner: «Посмотри как работает маска бенто в арк кам и посмотри как можно улучшить нашу». Comparison (agent, from
  research/lmc/audit/part2_bento.md + errata v4.1, research/ark12, ark23/quad_report.md; notes in the session scratchpad
  bento_research/notes.md): LMC 9.6 / ArkCam v1.23 = replacement (rej *= 1 - mask for every frame but the ultrashort, the
  ultrashort's map = the mask), mask from a ramp 0.98..1 on the base RGB max, binary dilation r 4, Gaussian sigma 1, the ultrashort
  rendered ALIGNED (biquadratic) before the error test, the mask read bilinearly in the merge; the standalone ArkCam app: a soft
  mask from 0.88 (smoothstep 0.88..0.98, max-dilation, a soft near-clip product 0.70..0.88), the short frames on a wider kernel
  (G 1.26 / RB 1.50 px) and a highlight chroma dezipper (3x3 bilateral of R-G, B-G inside the mask).
- Done (local): bentoSubpixel 1 - the ultrashort check reads the aligned position bilinearly (LMC) instead of the nearest cell:
  invalid cells wide Quad 148 -> 101, main inpaint hole 1 -> 0, the tele / OPPO bursts unchanged.
- Not taken: the mask's bilinear read and the per-colour ultrashort kernel live in kHybMergeMain1 (never edited: any edit of the
  merge program made it ~15x slower on Adreno 750); the soft 0.88 mask lets the x8-noisier ultrashort replace unclipped bright areas
  (skies at 0.9 of the clip) - worse noise there; the 3x3 dezipper does not reach the OPPO sill dashes (they alternate along a line
  thinner than the CFA period over tens of px: a single resting frame's aliasing, not a 1-2 px zipper; plainChromaMedian takes the
  1-2 px fringes). A capture-side /16 ultrashort when the ultrashort clips (usClippedRatio 0.24 on sunny windows) stays an idea.
### P270 - the shade: a strange darkening (owner, 2026-10-19 video_2026-10-19_11-32-30.mp4: the quick-settings sheet open over the
viewfinder, the dimming behind it pulses darker / lighter frame by frame)

### P271 - «Для опытных» shows one setting only, «Живой видоискатель» (owner, 2026-10-19)

### P272 - the 4x ISZ (HP9 Tetra) through SCAM Remosaic's neural remosaic (owner's question 2026-10-19: «Мы можем взять что-то из
нейроремозаика в SCAM Remosaic чтобы улучшить результат на 4х isz?»)

- Idea: P267's merged mosaic (dngMosaic: the burst merged on the sensor grid, the Tetra layout kept) is exactly a single-DNG input
  of the HP9 neural remosaic (hexquad / quad-hp9 QNN models already in the private bundle): merge in the mosaic domain, then the
  NPU remosaic instead of our remosaic for the RGB. Compare detail and artefacts on the owner's 4x bursts (scam_burst-...100546).

### P273 - a short beginner guide to the camera's settings (owner, 2026-10-19: «Когда закончишь полностью всё по плану и зальешь на гит сделай короткий и понятный для новичка гайд по настройке нашей камеры с понятным описанием абсолютно каждого параметра и с наглядной демонстрацией работы каждого»): after the push (the push waits until only P273 is left).
- Done 2026-10-19: artifact https://claude.ai/artifact/UK4frsnSXbMv9uBWpRV74T (private; built from preferences.xml + values-ru, @SensorConfig, the shade / Pro / module UI; 370 settings with descriptions, values and defaults, searchable; real before/after from the X200 Ultra bursts (one frame vs the merge, Bento off / on) and canvas demos marked as imitations). Builder scripts in the session scratchpad guide/ (extract.py, sensor.py, images.py, template.html, build.py).

### P274 - ISZ does not work on the X300 Ultra (owner, 2026-10-19, log-2026-10-19_2, build 31093: «isz не работает на X300 Ultra ... должен быть доступен просто через реквест vivo.control.forceSensorMode»): done, pushed.
Cause: every ISZ session streamed a plain Bayer at the normal sensor mode index 2 (the result key), «камера не включила ISZ-режим».
vivo.control.forceSensorMode (0x811b0126) is in the camera's android.request.availableSessionKeys (x300u_camera.txt), and the
camera service compares the session keys of every request with the session parameters: a request without the key removes it
and reconfigures the session at the normal mode. We sent it on the session only («no request copy», P214i). Fix:
VivoQcomIsz.sessionBuilt / mirrorRequest - the forceSensorMode of the session parameters (ISZ module or DCG) is repeated on every
request (VendorTagUtils.builderSessionApply); the old trials (BAYER notes) are void (stamp "#req"). Test VivoQcomIszTest
sessionModeIsRepeatedOnEveryRequest; CI list 252 classes / 1533 tests pass. Not device-tested: check «4.8×» / «9.6×» / DCG
on the X300 Ultra (log: «X300 Ultra camera 5 sensor mode: vivo.control.forceSensorMode 5 on the session and on every request»,
«sensor mode index 2 -> 5»).

### P275 - X300 Ultra ISZ still off after P274; the app's own package (owner, 2026-10-19: log-2026-10-19_3 «на 300 ультра не работает isz, раньше он работал»; «для того чтобы ISZ и фулрез работали на виво, в том числе и 200у, не обязательна подпись android.camera.cts»; «имя пакета делаем наше собственное»; «на 300у перестал работать ISZ после 30906 билда»): done, pushed, not device-tested.
Facts: build 31097 (P274) still streamed a plain Bayer with the key on the session and every request. Build 30906 (09b7b27c) was
already android.camera.cts and sent the same session key; its X300 Ultra modules streamed CROPPED_RAW from the first session (the
logs of 2026-10-15 / 16: raw crop x2.0 / x4.0 in-sensor, Quad / Tetra blocks). P214i (merge 30916) made the default use case the
first try. Changes: (1) CROPPED_RAW first again, the default stream second (Trial PLAIN = Bayer on CROPPED_RAW, new DEFAULT =
the mosaic on the default stream), old notes void (stamp "#lease"); (2) the vivo SDK lease of FULLRES (VivoCameraManager 33 = 2
VCF bypass, 40 = 1 SDK RAW) is taken before an ISZ camera opens and given back when it closes: X300 Ultra modules (scam_dev
vivo_qcom_isz_lease) and the X200 Ultra native ISZ modules of our own package (vivo_native_isz_lease); (3) applicationId
com.scamera.app (CI no longer passes -PscameraCtsNativeRaw), the vivo native routes accept it (VivoPd2454NativeRaw.nativePackage).
A new package installs next to the CTS one: settings move by «Резервная копия». CI list 252 classes / 1534 tests pass.
To check on the phone: «4.8×» / «9.6×» on the X300 Ultra (log: «ISZ camera 5: vivo SDK lease taken», «raw crop ... x2.0 (in-sensor
zoom)», «RAW stream colour block 2»); X200 Ultra 2× / 4× native modules and FULLRES 50 / 200 MP with the new package.

### P276 - a module's sensor mode from the camera's SensorModeTable, as the new ArkCam (owner, 2026-10-19, video_20261019_153550: «вот так это реализовано в новой Арк камере, мы сможем сделать схоже?», «я в видео имел ввиду работу с сенсормодами», «учитывай всю новую информацию которую я кидал тебе по оппо и виво»): done, local commit, not device-tested.
ArkCam's lens page (the video): RAW10 / 12 / 16, the RAW stream's size (max / binning / own), «Sensor Mode (SensorModeTable)» with
every row "Mode #N: W x H @ fps", the preview stream on / fake off / real off, the RAW buffer pool, the CFA pattern, black / white.
Ours: «Режим сенсора (SensorModeTable)» on the module's sensor page (Auto + the rows of
org.quic.camera2.sensormode.info.SensorModeTable, pref_sensorconfig_<slot>_sensormoderow) - capture/SensorModePick. A picked row:
the RAW reader at the row's size (usage 0: sizes outside StreamConfigurationMap, as the native FULLRES readers and the ArkCam
note), the brand's mode key on the session parameters and on every request (builderSessionApply): vivo forceSensorMode int[]
with an Integer fallback, OPPO / OnePlus / realme agingtest.mode.select byte -> int32 (OppoNativeFullRes.setMode), other
Qualcomm current_mode; «Поток ISP» Auto drops the ISP stream (RAW viewfinder); vivo opens under the SDK lease (33 = 2, 40 = 1);
the shot's declared block is the sensor's mosaic for a full-size row with a binned row (Quad, Tetra above 100 MP), else
measured; a refused session puts the module back on Auto with a message. «Поток ISP» gained «Фиктивное откл.» (3: the stream in
the session, no request targets it). scam_dev sensor_mode_pick / sensor_mode_pick_lease. Test SensorModePickTest; CI list 253
classes / 1539 tests pass. Not done: RAW10 / RAW12 stream format and the buffer pool choice (RAW_SENSOR as the native routes),
the CFA pattern override. To check on the phones: a full-size row (vivo 8192x6144 / 16320x12288, OPPO 8192x6144) and a 2x ISZ
row on a module, log «the module's sensor mode ... (SensorModeTable, P276)», «sensor mode pick on the session», the RAW size
and «colour block».
P276 follow-up (owner, 2026-10-19: «сделай его раздельным по модулям камер»): the pick is stored per module slot (the sensor page is
per module); a module copy (ModuleRegistry.duplicate, the automatic ISZ modules made from a plain one) no longer inherits it -
ModuleDuplicates.copyOf skips pref_sensorconfig_<source>_sensormoderow, the copy starts on Auto; the «Копировать в модули» dialog
never offered it. So a lens can have a plain module and a copy with its own sensor mode side by side. Test
ModuleDuplicatesTest.aCopyDoesNotTakeTheSensorModeOfItsSource; CI list 1540 tests pass.


### P277 - «Гайд и лаборатория»: the settings shown on the owner's own RAW series (owner, 2026-10-19: «гайд как небольшое приложение в котором всё будет полноценно показано ... чтобы в приложение можно было подгрузить например свою Рав серию»; «тогда лучше всё-таки встроить в наше приложение»; «Может мы сделаем отдельный тогл специально для этого раздела?»; «Сохранять будем в /storage/emulated/0/Download/SCAMERA/Lab и разделять по дате и времени по папкам»; «нам нужно чтобы было наглядно видно что и как работает, те же настройки склейки и тд чтобы можно было понять разницу»): done, local commit, not device-tested.
Capture: «Сохранять снимки для лаборатории» (pref_scam_lab_keep; the lab page and Diagnostics) keeps each SHDR shot in
Download/SCAMERA/Lab/yyyy-MM-dd_HH-mm-ss (ScamLab): burst.nch = the worker's input copied by the worker's keep-burst marker (the
lab's folder wins over keep_burst), its header's merged-DNG and diagnostics flags cleared at the end of the shot; meta.json = the
post pipeline's Parameters right before the post pipeline (ScamLabParams, reflection over the plain fields + CCT + noise model;
the shot's metadata path needs the live CaptureResult), the worker tuning (settings' part and the burst's own lines apart), every
default preference typed, sizes, lastNormals, and "orient" - how the photo lies over the sensor frame, found by correlating the
worker RGB's luma with the photo (ScamLabOrient, 8 codes); preview.jpg (1600 px, the pipeline output before the zoom / ISZ crops).
An incomplete folder (failed merge, DNG-only shot) is removed. ScamLab.LOCK: a shot's processing and a lab run never overlap.
Replay (ScamLabRun): ScamLabBurst (a ScamTransport from the file: whole = the file's fd mapped by the worker, or a fragment =
a v11 transport of a 32-aligned window of every frame), the job's ScamHybridBurst-only branches now interface methods
(hybrid, rgbHalfTransport, creOff, settingsTuning, burstTuning, keepable); while a side runs PreferenceValue.overlay makes the
default preferences read as the shot's snapshot (+ the tried value for B), so the tuning, the denoise, tone, colour, sharpening
take the side's value. A merge setting (B's tuning differs) merges again; A of a shot and fragment is cached. The fragment gets
its lens-shading map cropped (RawCrop.cropGainMap), its size and final size. PostPipeline.getRotation no longer needs an open camera.
UI (ScamLabFragment, settings root «Гайд и лаборатория»): the shots (thumbnail, device, size, frames, file size; hold to delete),
a shot's page (the photo with the fragment, tap to move it; 1/9, 1/4, whole frame; the groups SHDR photo / sharpening / tone /
merge, style, LUT, lens filters, colour fine), a group's settings with the value at the shot, a value sheet (switch: the other
state; list; slider), and the comparison (LabCompareView): before / after with a draggable border, double tap x3, pinch, a held
magnifier; «Разница ×4»; «Карта кадров» (the worker's effective-frame map of A and B, few frames dark blue -> twice the median
yellow); the times, whether the merge ran again, «A и B совпадают до пикселя» with why; «Другое значение», «Сохранить B в
настройки». Tests ScamLabOrientTest, ScamLabBurstTest, ScamLabParamsTest. Not device-tested: the capture, the replay, the UI.
To check on the phone: switch on, an SHDR shot -> Lab/<date_time> with three files (log «SCAM_LAB: lab shot kept», «photo over
the sensor frame: code N»); the lab page lists it; a merge setting (e.g. a rejection strength) and a processing one (sharpening)
on the 1/9 fragment; the frame map; the fragment lies where it is on the photo.

### P278 - Xiaomi 12X: the shutter sound does not turn off (owner, 2026-10-19, log-2026-10-19_4 + SCAMERA-debug, build 31098, Xiaomi 2112123AG psyche): done, not device-tested.
The log says why: «CameraSounds: shutter sound: switch off, the phone requires it true, normal ringer true -> played» -
MediaActionSound.mustPlayShutterSound() is true on this phone (the ROM's ro.camera.sound.forced / its region config), and
CameraSounds.shutterAudible lets the phone's rule win over «Звук затвора». Plan: the phone's must-play rule only where the law asks
for it (the SIM / network country JP or KR, as the ROMs' own region tables mean it); elsewhere the switch decides; log the
country with the line above. Check on the 12X: the switch off -> «-> silent».
Done: CameraSounds.lawRequires (the first known of SIM, network, locale country is JP or KR) gates the phone's rule; the log line
«shutter sound: the phone requires it; country sim=.. network=.. locale=.. - not required there, the switch decides (P278)».
Test CameraSoundsTest.thePhonesShutterRuleStandsOnlyInJapanAndKorea.
