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

#### Done (2026-10-06, branch `shade` from 0bf8d62, local, not pushed): ef96bd0 a, 468fa85 b, 85781e0 c, b667e68 d, f22deae e, 3766a45 f, a2dd53e g

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
  - **Port:** `app/src/main/cpp/vivo-nice-rawca.h` = CA_correct_RT (RT's scalar path line by line, with RT's buffer layout and
    quirks), standard library only. Burst extensions:
    - fits / avoid-colour-shift factors carried from the base frame to the other frames;
    - uint16 ↔ RT's working scale with a grey-world WB (the NCH has no WB);
    - clipped and unchanged sites keep their codes; G1 / G2 are never written.
  - **Modes (worker key `rawCa`, default 0 = off; P19 runs as before):**
    - 1 "base": RT's auto fit of the base frame (or manual red / blue). After the merge, one `correctRgb` per fitted pass moves
      R / B of the merged RGB onto G (RT's colour-difference rule on the dense grid). Replaces P19's shift.
    - 2 "frames": the same estimate. Then every frame (base included) gets RT's correction pass with the base's fits and factors,
      before alignment / merge. This runs as a new GLES 3.1 compute pre-pass (`vivo-nice-rawca-gpu.h`, own EGL context), with
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
  - dropping RL / NiceDenoise;
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
  (4.2 s, P29's target), F6 0.85 s, binned CRE alignment 0.7 s, PostPipeline 2.2 s (NiceDenoise 1 s).
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
- Today the block is decided per shot from the data: the hybrid (LmcHybridBurst:134) runs MosaicBlockDetector on the first
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
- Quick owner test with the current build: nice_dev.txt (Android/data/org.codeaurora.snapcam/files/) line
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
  VivoNicePreview.supported() is vivo / iQOO only and the HAL rejects unknown tags). Done: the stock preview profile recorded on
  the X200 Ultra (≈80 vivo.control / vivo.capability tags, vivo.control.zoom_ratio, motion metering, Camera2 scene mode
  FACE_PRIORITY, preview EIS 5) is sent only on PD2454; other vivo phones (X300U v2562) get the NICE keys alone (MagicEnable,
  capture.nice, sceneMode NICE bank). Then the owner: «Scam HDR на остальных виво не работает, он работает только на 200 ультра и
  смартфонах на 8 элит» — so the NICE preview (all its vivo keys, session MagicEnable included) runs only on PD2454
  (VivoNicePreview.vendorKeys()); other vivo phones get the plain Camera2 preview. nice_dev "vivo_stock_profile 1" = old
  behaviour. Owner: check X300U stabilisation after a shot.

### P55 — Rebrand inside the APK: no "LMC", "Vivo", "NICE" anywhere; everything "scam" (owner, 2026-10-08; to do)
- Owner: «удалить все упоминания LMC, Vivo, NIce из нашего апк. Заменяй всё на scam».
- Scope: everything that ships in the APK — UI strings (values / values-ru), settings keys shown to the user, log tags and log
  lines (NICE_HDR, NICE_CAPTURE, NICE_PIPELINE, NICE_DIAG, "LMC hybrid", "Vivo Neural"...), class / package / file names
  (VivoNiceBurst, VivoNiceRgb, VivoNicePreview, LmcHybridBurst, LmcDenoise, vivo-nice-hybrid.h, vivo-neural-worker, libvivo*),
  asset names, diagnostic zip names (NICE-*.zip), JNI symbols, preference keys (pref_lmc_hybrid_*, pref_nice_*, isVivoNiceEnabled),
  nice_dev.txt, native strings in the worker binary. Replace with "scam" naming (e.g. SCAM_HDR / ScamBurst / scam_dev.txt / SCAM-*.zip).
- Must keep working: stored preferences (migrate old keys -> new keys once, DeviceDefaults versions), the vendor Camera2 tag names
  (vivo.control.*, vivo.parameter.* are the HAL's own keys — cannot be renamed; keep them as data, never as our naming), the
  vendor library / AEC runtime names that the HAL or a vendor lib loads by name (only if loading needs them), CI workflow, tools,
  tests (rename with the code), research docs outside the APK can stay. Device checks (vivo/OPPO), md5 gates on the worker
  unchanged (renames only), unit + CI lists updated, tools/check_* that grep names updated.
- Check: unzip the APK and grep -i for lmc|vivo|nice (strings, dex via dexdump/strings, .so via strings) — only vendor HAL tag names
  and third-party library internals may remain; list them in the report.

### P56 — Sabre x2 (2x output grid) does not work with ISZ (owner, 2026-10-08; to do)
- Owner: «с isz не работает Sabre X2».
- Cause (by design today): LmcHybridBurst.java ~line 155: wants2x = ... && mosaicBlock <= 1 — a Quad / Tetra (ISZ) stream never
  gets the 2x grid; the native mosaic merge (P29/P35) outputs the sensor grid 4080x3072 ("HYBRID OUTPUT: Sabre 4x grid 4080x3072"
  = 4x of the binned 1020x768 frames, kernel in sensor px). The log says "hybrid output mode=2x ... twoX=false" without a reason line.
- Do: (1) log why 2x was refused for a mosaic stream; (2) make the native merge write a 2x grid of the sensor (8160x6144) when the
  output mode is 2x: kHybMergeMosaicFast / kHybMergeMosaic already take the output grid factor (kG.x = g) — check the native path
  passes g = 2 correctly (output positions, the F6 field / covariance lookups at 2x, the chroma median and clip flags at 2x), the
  memory budget (50 MP float RGB + GL working set; vivo X200 Ultra has the RAM, Pixel-class phones do not -> keep sabre2xFits-style
  limits), and the post pipeline at 50 MP (P48 numbers: ~6 s post + 3.7 s WebP at 2x). (3) Measure on ISZ 2x / 4x bursts: detail vs
  the sensor-grid output (owner's 4x ISZ Tetra and 2x Quad), time and memory; keep plain Bayer 2x and the sensor-grid ISZ output
  byte-identical; replay md5 gates (hand / isz2 / syn_b2 / syn_b4 at 1x grid must not change).

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
  VivoNiceRgb per-channel recovery "hi=2.478(plateau),2.484(plateau),0.0(off)": R and G recovered to the plateau, BLUE off -> the
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

### P62 — Mochi: GCam 11 photometric merge of the bracketed (long) frames (owner, 2026-10-08; to do)
- Owner: «Помнишь в 11 мы находили "Mochi"? Реализуй его работу у нас». Reference: research/gcam11/map/03b_merge_accumulate.md §3.6,
  GCAM11_PIPELINE.md (+0x1b4 PhotometricMergeOptions, §0.5 device defaults: Java enables it in normal photo on P25/P26).
- What it does: merge all short (N) frames first; build a guide from the partial accumulator after the last short frame
  (GenerateGuideFromAccum); per bracketed frame a correction map from the guide (GenerateCorrectionImageFromGuide: least-squares per
  RGGB channel) and an SNR map; accumulate the bracketed samples with bias_rggb = correction * SNRWeight(snr, 5.0)
  (snr_normalizer 5.0); rejection of bracketed frames takes the correction into account. Enabled when mochi_enabled and not night
  sight, not quad-Bayer, > 3 non-bracketed frames; applied from the first bracketed frame on; off after a bracketed black-level
  re-estimate / night series.
- Our merge: Sabre 6.1 kernel + LMC rejection/weights, N / L / S / US roles (L = bracketed long, role 3), Shasta. Implement as a
  tuning key (mochi 0/1/2 = off/auto/force), plain-Bayer md5 unchanged with it off; evaluate on replays with long frames; default
  per the measured result.

### P59b — Gallery in the new card style (owner's GALLERY_TASK.md, 2026-10-08; to do, after/with P59)
- Task file: C:/Users/MECHREVO/Downloads/GALLERY_TASK.md; concept https://claude.ai/artifact/AE4t3NyLqPJXWG43m5PfXS (library, viewer,
  details sheet, delete sheet, folders sheet, compare). UI only; keep GalleryViewModel / ExifDialog* / SelectionHelper /
  GalleryFileOperations / ImageFile / MediaFile / compare ScaleAndPan / folders pref / UltraHdrGalleryUtil / intents / deletion.
  Reuse SettingsStyle + colour tokens, route monograms (H / S), format icons. ru + en texts; no ellipsis / overlaps at 360 dp;
  Robolectric + 360 dp layout tests; phone checks listed in the task. No push until the owner says.
- Owner addition: a setting to show / hide the gallery as a separate app in the launcher (its own icon; activity-alias enabled /
  disabled with PackageManager.setComponentEnabledSetting(DONT_KILL_APP)), ru + en, default = today's behaviour.
