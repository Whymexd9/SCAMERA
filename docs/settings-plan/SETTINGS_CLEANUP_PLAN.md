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
    - the worker build `g++ ... vivo-neural-worker.cpp ... && ... --transport-check`;
    - `tools/check_vivo_nice_probe.cpp`;
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
   - `.github/workflows/check-hexquad.yml` together with `tools/check_vivo_hexquad_controls.py`.

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
| Global | `pref_save_raw_key` (merged DNG, HP ~602-623), Ultra HDR (`LinearExposure` snapshot), watermark (`RotateWatermark` in every branch), 16:9 (`VivoNiceRgb` ~227, `SaverImplementation` ~46), zoom. |

### Shared infrastructure that must stay

- **Capture:** `scameraBracketPlan`, `HybridPlan`, `VivoStockAe`, the ZSL ring.
- **Merge:** `LmcHybridBurst`, `vivo-nice-hybrid.h`, `VivoNiceBurst`, `VivoNiceMosaic`.
- **SCAM HDR mosaic:**
  - `HexQuadBurst.processForNice`;
  - `MobileRemosaicProcessor.mergeForNice` (mosaic = `mfr`);
  - `RemosaicCore`, `TetraDetailRemosaic` (`VivoNiceMosaic` ~197, PK ~620).
- **Worker and client:** `VivoNeuralClient` / `VivoNeuralWorker` and the one native worker `app/src/main/cpp/vivo-neural-worker.cpp`. That worker hosts NICE, the hybrid, HexQuad/Quad and the CRE runtime — and VSR, which goes.
- **Post path:**
  - `VivoNiceRgb`, `HighlightRecovery`, `NiceDenoise`, `LmcDenoise`;
  - Ark* (`ArkStats`, `ArkFusion`, `ArkLumaSharpen`, `ArkCombine`, `ArkSharpenGuard`);
  - `LinearExposure`, `NiceExposureFusion`, `VivoHdrTone` (a subclass of `HeadroomRender` — keep `HeadroomRender`), `AgxTone`;
  - `LmcCurves`, `NiceLocalContrast`, `NiceSharpen`, `RTSharpening`, `HybridFinalResize`, `RotateWatermark`.
- **Ultra HDR:** `ultrahdr/*` and `PP.RunHDRGainMap`.
- **Live RAW viewfinder** (`PP.BuildPreviewPipeline` ~572, vivo branch): `Bayer2Float`, `BinnedDemosaic`, `ABLC`, `LinearExposure`, `VivoHdrTone`.
- **Hybrid final resize:** `VivoPostDownscale.resizeTo` (HP ~744, `PK.hybridDownsampler`).
- **Saving:** `Parameters` (CCM, DCP, LSC, black level), `ImageSaver`, `DngCreator`.

## 3. Phases

### P1 — Remove Vivo upscale completely

**Java:**
- Delete `processing/ml/VivoRaisrProcessor.java` and `processing/ml/VivoVsrProcessor.java`.
- Delete the HP block ~751-786: upscale plus "Lanczos after Vivo".
- In `VivoPostDownscale`: keep `resizeTo` and what it needs. Delete `process(...)` and the size/kernel options that only it uses.
- PK: delete `isRaisrEnabled`, `getRaisr*`, `getVivoUpscaleBackend`, `getVivoDownscaleKernel`, `getVivoDownscaleSize`. `getRaisrMode` and `getRaisrFilterScale` are already dead.
- Delete the VSR entry points in `VivoNeuralWorker` / `VivoNeuralClient`.
- Delete the SA RAISR/downscale rules (~190-200).

**Native:** delete:
- `app/src/main/cpp/vivo-upscale-worker.cpp`;
- `vivo-raisr-abi.h`, `vivo-raisr-controls.h`;
- `vivo-softpqe-abi.h`, `vivo-softpqe-controls.h`;
- `vivo-vsr.h`;
- the VSR paths in `vivo-neural-worker.cpp`;
- whatever builds or packages the upscale worker (CMake, gradle, jniLibs).

**Assets and resources:** delete `app/src/main/assets/vivo-upscale/`, `res/values/vivo_upscale.xml`, `res/values/softpqe_controls.xml` and the related arrays and strings.

**Tools and CI:**
- Delete `tools/vivo-upscale/`.
- Delete `tools/check_lanczos_downscale.py` only if it does not cover `resizeTo`.
- Delete the `build-test-apk.yml` steps that build or check the upscale worker, RAISR, SoftPQE or VSR.
- In `tools/package_vivo_neural.py` and `tools/ci_neural_assets.py`, drop the VSR, RAISR and SoftPQE entries from the pinned/required list. Every NICE, CRE, HexQuad and Quad file must stay required. Bundle v2 itself is untouched; it just stops shipping those files.

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
  - the `nice_dev.txt` "hybrid 0/1" override (PK ~1034);
  - SA hybrid/SCAM HDR rules;
  - the HP shot profile (~100-110).
- **Hybrid default on every phone:**
  - `settings/LmcHybridKeys.defaultOn` currently returns false on SM8750 (`vivoNetSoc()`) and on non-arm64. Drop the SM8750 exclusion. Run `git log -S vivoNetSoc` and note the original reason in the commit message, but do not keep the exclusion.
  - Non-arm64 is not a target. If the hybrid cannot run there, `hybrid` is still the stored default.
  - `DeviceDefaults` (~30-31, ~66 and the other SCAM HDR values for PHY110/PKJ110): stop pre-selecting SCAM HDR. The SCAM HDR tuning values may stay; they only matter when the user picks SCAM HDR. The route itself is not written by `DeviceDefaults`.
  - `SettingsMigration` ~249 (PHY110 hybrid switch): drop or fold it into the route migration.
  - Existing installs: the route migration above keeps an explicit user choice (hybrid on → `hybrid`; SCAM HDR on → `scamhdr`). Only fresh installs and "Сбросить всё" land on `hybrid` by default.
- **Gates:** with no incompatible routes left, simplify `isVivoRouteCompatible`, MFSR/remosaic gates in PK ~537-640 and the SA messages. This also removes the trap where SA allows SCAMERA remosaic but `LmcHybridBurst` ~61 and `VivoNiceBurst` ~53 throw on `isRemosaicEnabled`.

### P4 — Remove the legacy capture and merge

**Capture (CC):**
- Remove the legacy bracket branch (~3500-3575 via `IsoExpoSelector` long/ultrashort) and `FrameNumberSelector` use for legacy.
- Remove the MFSR capture count (`multiFrameCaptureCount`).
- Remove the standalone neural burst (`neuralBurstFrames`, `HexQuadZslSelector`), unless `processForNice` frame selection uses it — check first.
- `needsExposureBracket` (~2525) becomes "NICE always brackets".
- Keep `scameraBracketPlan`, `HybridPlan`, `VivoStockAe` and the ZSL logic.

**Merge (HP):**
- Remove the standalone MFSR/CAL route (~427). Keep `MobileRemosaicProcessor.mergeForNice`.
- Remove standalone `HexQuadBurst.process` (~428). Keep `processForNice`.
- Remove the ESD4D fusion branch (~566-603) and "single-frame recovery", Mosaic SR (~640) and AI Bayer denoise (~679-690).
- Remove the always-true conditions (~508 `!hex && !multi`).

**Delete when unreferenced:**
- `ESD4D`, `PyramidAlignment`, FlowNet/KernelNet (`pref_processing_backend_key`), `BurstFrameSelector`;
- the HDR+ merge, Sabre RAW (`pref_mfsr_engine_key` = `sabre`, `pref_gcam_cyclops`);
- the ESD4D VIVO_HDR branch and its `vivohdr/*` merge shaders (keep the shaders `VivoHdrTone` uses), `VivoHdrDenoise`;
- `VivoNeuralRemosaic` (per-frame `vivo_neural`), the standalone `Remosaic` node in PP, `processing/ml` AI denoise.

**Standalone remosaic settings:**
- Remove `pref_remosaic_enabled_key`, `pref_remosaic_backend_key`, `pref_hexquad_frames`, `pref_quad_frames`, `hexquad_exposure_ev`, `hexquad_full_resolution`, `hexquad_post_denoise`, `quad2x2_exposure_ev`, `quad2x2_post_denoise`.
- Keep the GPU remosaic tuning keys and the `hexquad_*` / `quad2x2_*` NN tuning used by SCAM HDR mosaic.
- Update `check-hexquad.yml` and `tools/check_vivo_hexquad_controls.py`: they require `hexquad_exposure_ev` (0, [-2..2]).
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
- labels built in Java (`ModuleConceptFragment`, `SettingsActivity`, diagnostics screens, `NiceDiagnostics` text shown to the user);
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
- class names, log tags, file names (`hybrid_tuning.txt`, `nice_dev.txt`), docs and code comments.

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

- **Today (P14 / P15):** the GCam QuadBayerRgbMerge design, re-implemented in `vivo-nice-hybrid.h` (not a port of the GCam 11
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
  Shasta / Bento statistics), camera 3 is plain. Likely trigger (not verified on bytes): VivoNicePreview's PD2454 stock
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

#### Done (2026-10-06, branch `shade` from 0bf8d62, local, not pushed): ef96bd0 a, 468fa85 b, 85781e0 c, b667e68 d, f22deae e, 3766a45 f, this commit g

- **a) Data model:**
  - `settings/ShadeCatalog`: the virtual entries (flash, timer, format, Camera2 metering) plus the inflated settings tree with
    the tunables, walked with a data store that reads defaults and writes nothing (built once per process).
  - Per key: kind, short title, short values, icons, section path, default (DeviceDefaults, then XML / tunable /
    setDefaults), dependency, session-time flag.
  - The curated groups follow the key table with the owner's answers; 8 default tiles.
  - Not pinnable: sensor configs, device-only, DCP, spoof, theme, Quad, free text except «удлинение L», actions, screens.
  - The MediaTek / Xiaomi visibility rules, the effective route, nice_dev.txt and the dependencies live in ShadeCatalog.
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
  1. Client: log the worker's exit code / signal and the last stderr lines on every failed job (VivoNeuralClient:297).
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
  - **Every capture series is tolerant** (`VivoNiceCaptureSequence`, hybrid ZSL, hybrid normal-back `hybridFuture`, SCAM HDR
    ZSL and normal-back):
    - a lost / failed / foreign / duplicate / invalid result or a missing / duplicate / null RAW drops that frame only, with
      every reason kept;
    - the series fails only when no RAW arrived.
  - **Roles by measured exposure (Hybrid):**
    - `HybridPlan.classify`: within 0.4 EV the planned role (plans that worked merge as before);
    - otherwise ×1.5+ → bracketed, ×0.5- → ultrashort, ×0.71..×1.035 → N, else dropped.
    - The role reaches the frame (`ImageFrame.measuredRole`).
  - **Hybrid N without the SCAM HDR planner** (`hybridNPlan`, `VivoStockAe.Plan.nOnly`).
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
    - any SCAM HDR failure (frames, VivoNiceMosaic, worker) → Hybrid merge of the same frames.
  - **LmcHybridBurst:**
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
  - `check_nice_capture_sequence.py` (56 checks; tolerant cases incl. the X300 Ultra shape; strict constructor still
    rejects) now in CI;
  - `HybridPlanTest` (classify, unchanged X200 Pro plans, normal-back, AE);
  - `LmcHybridBurstRolesTest` (LONG at N merged as N, promoted base, drops, noise fallback);
  - `CameraResumeTest` +3 (X300 Ultra ring ramp, darker-only widening, one frame).
  - Unit tests: 183, only the known `testGetCameraOutputSize_withTwoParameter` fails.
- **Not done (later, P1 / P2 of the critic):**
  - press queue when memory is low (M8);
  - learned honoured ISO / shutter limits instead of the preview-widened range (`ExposureLimits`);
  - X300 Ultra 'first requests lost after the flush' statistics;
  - worker GAIN CHECK as a ratio source;
  - RAW > 16 MP binned in Java.
- **Device check:**
  - X300 Ultra / X200 Pro: shots after a lens switch and at night;
  - X200 Pro: 'Hybrid fallback=cpu-single' once, then no worker attempt;
  - log lines 'hybrid: LONG #i delivered x1.000 … used as NORMAL', 'hybrid ZSL: … widened', 'SCAM HDR fallback=hybrid'.

### P28 — RAW CA correction as in PC RAW / SABRE 6.1 (owner's request, 2026-10-06; not started)

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

### P29 — Sabre Quad remosaic from ArkCam v23, combined with our GCam 11 remosaic (owner's request, 2026-10-06; study running)

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
- **Built (branch p29, 2026-10-07; host replays only, device check pending):**
  - S0 numpy reference (`research/p29/S0_results.md`); S1 native pass at parity, S2 ks per colour, S3 site gains at read
    time, S5 ArkCam R / B fill, S8 Tetra T2 / T1 (all in `vivo-nice-hybrid.h`, `kHybMergeMain1` untouched).
  - Worker keys: `mosaicPath` (0), `mosaicWindow` (3), `mosaicWindowFull` (1), `mosaicKernelScale` (1),
    `mosaicNativeEdgeScale` (0.4), `mosaicKernelG` / `RB` (1 / 0.85), `mosaicChromaFill` (0), `mosaicFillSupport` (0.25),
    `mosaicTetra` (2). The path-1 defaults are the S0 point; `mosaicPath` stays 0 until the device check.
  - Settings: Hybrid → Склейка → «Мозаика без ремозаика»: «Склейка мозаики» list, category «Нативная склейка мозаики»
    (`pref_lmc_hybrid_mosaic_*`); rows inactive unless the native merge is chosen.
  - Device check plan for the coordinator: `research/p29/DEVICE_CHECK.md`.
  - Not built: S4 device sweep, S6 GCam fallback, S7 directional fill, T2 evaluated at the sensor sub-positions (S0).

### P30 — Hybrid speed: what ArkCam v23 does faster (study 2026-10-06; not started)

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
  - dropping RL / NiceDenoise;
  - dropping L / ES;
  - RGBA16F for 12-14-bit RAW;
  - TurboJPEG instead of jpegli;
  - the FrameSelector culling.
- **To measure:** both apps on one phone, same scene, 1x, diagnostics and `nice_keep` off. Full list in the session
  scratchpad `ark_all.txt`, to copy to `research/ark23/`.
