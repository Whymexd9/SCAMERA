# Mochi (P62): фотометрическое слияние брекетинга GCam 11 в гибридном воркере SCAMERA

Статус: в работе (ветка `worktree-agent-a4439e949ed61e2a8`, от 9cfae80). Заметка пополняется по ходу работы.

Источники (GCam 11.0.073, `research/gcam11`, путь от `SCAMERA-PC/`):
* `map/03b_merge_accumulate.md` §1.1 (ядро `merge_bayer.cl`: `bias_rggb`, `SNRWeight`), §3 (таблица аргументов: `snr_normalizer`,
  `photometric_merge`), §3.6 (PhotometricMergeOptions, правило включения, guide из аккумулятора);
* `map/03_spatial_merge.md` §7.3 (порядок на alt-кадр: rejection → «GaussianBlur SNR map» → 0x36f876c → «GaussianBlur correction» /
  `GenerateCorrectionImageFromGuide`), §7.4 (формулы коррекции), §8.1 (guide из аккумулятора `ACCUM_BUFFER_INPUT`), §9.1 (rejection с
  фотометрией, МНК gain/offset по тайлу), §9.3 (`UsePhotometricCorrection`, флаги photometric_*);
* `map/06_orchestration.md` §5.4 (Mochi-опции), §8.4 + §5.3 п.11a (связь с переоценкой чёрного уровня);
* исходники ядер: `map/kernels_03/correction_image.cl`, `rejection.cl` (`GenerateRejectionTextureWithPhotometricCorrection`),
  `merge_bayer.cl` (`AccumulateAlt`), `common.cl` (`SNRWeight`), `guide_image.cl` (`ACCUM_BUFFER_INPUT`);
* `map/verify_03b_merge_accumulate.md` п.8, п.19 (подтверждение констант).

## 1. Что делает GCam 11

1. Кадры сортируются: база → ultrashort → short (N) → bracketed (тип 3, у нас роль 3 = L) → прочие. Накопление (OpenCL `merge_bayer.cl`)
   инкрементальное: база, затем alt-кадры по одному.
2. После накопления кадра `first_bracketed_index − 1` (последний короткий) `SpatialMergeGenerateGuideFromAccumCl` строит guide
   **из частичного аккумулятора** (сумма/вес, приведённые к экспозиции базы, затем обычный guide 3×3 квада). Для bracketed-кадров этот guide
   служит опорой (rejection и коррекция).
3. На каждый bracketed-кадр:
   * rejection с фотометрией (`…WithPhotometricCorrection`, включается `UsePhotometricCorrection` = тип кадра 3): по тайлу 8×8 guide-пикселей
     (16×16 RAW) по ненасыщенным (≤ 0.95) пикселям — n, Σbase, Σalt, Σalt², Σbase·alt; `tile_snr = mean_base.g / sqrt(σ²_g(mean_base.g))`;
     `s = 1 + exp((tile_snr − 10)/1.5)`; при `tile_snr ≥ 11.5` → (g=1, b=0); при `photometric_set_fixed_gain` (по умолчанию **true**) →
     (g=1, b = mean_base − mean_alt); иначе МНК `g = cov/var_alt` в границах `1 ± 0.1/s`, `b = mean_base − g·mean_alt`; `|b| ≤ (35/65535)/s`.
     Расстояние кадра: `alt' = g·alt + b`, `σ²_n = σ²_ref + g²·σ²_cur`. SNR-карта: `snr = min_c(g/sqrt(var_c(g)+1e-7))`, `g = clamp(base.g,0,1)`.
   * «GaussianBlur SNR map»;
   * `GenerateCorrectionImageFromGuide`: по тайлу 8×8 guide-пикселей `correction_rgb = Σ(base − alt_aligned)·rej / (Σrej + 1e-6)`
     (alt с «пересветом» пропускается), перевод в DN кадра `(c − rggb_bias)/rggb_scale − alt_black` (= разность guide в DN alt-кадра),
     RGB → RGGB (G дважды), **обнуление при `|c_rggb|² > 500`**, иначе **клэмп ±20**; затем «GaussianBlur correction».
4. Накопление bracketed-кадра: `bias_rggb = bilinear(correction, (pixel+0.5)/TILE) · SNRWeight(snr, 5.0)`,
   `SNRWeight(s, n) = n ≤ 0 ? 1 : min(1, max(0, s)/n)`; отсчёт = `raw − black + bias_rggb` (DN, до усиления кадра).
5. Включение: `enable = mochi_enabled ∧ (mochi_force_apply ∨ (ux_mode ≠ NightSight ∧ ¬auto_night_sight ∧ bayer ∉ 5..8 ∧ #не-bracketed > 3))`;
   покадрово — только для кадров с индексом ≥ `first_bracketed_index` (лог «Photometric merge is enabled for frame %d»).
   **Важно (06 §8.4, §5.3 п.11a)**: после блока переоценки чёрного уровня `Mochi.enabled := 0` на всех путях, **кроме успешного
   применения** переоценённого чёрного уровня — т.е. в GCam Mochi работает только вместе с переоценкой ЧУ по bracketed-кадру
   (формулировка задания «выкл. после переоценки ЧУ» обратна коду). У нас переоценки ЧУ по брекетингу нет — см. §3.

## 2. Как устроено у нас (гибрид, `vivo-nice-hybrid.h`)

Наше слияние — не накопление, а «сбор»: на каждый выходной пиксель шейдер `kHybMergeMain1` обходит все кадры сразу, по полосам (strips)
128 ячеек (256 строк). Перед слиянием на полосу: guide базы, ячейки доноров (u = sqrt-домен), rejection (`kHybReject`), DilateMask
(`kHybDilate`) → веса `robust`. Порядок кадров на результат не влияет, поэтому «сначала все N, потом L» реализуется явным guide из N.

Mochi у нас — отдельные проходы (компилируются только при включённом Mochi) внутри цикла полос; при `mochi 0` ни одна ветка не
выполняется и ни одна программа не меняется (см. §5).

(разделы 3–6 — по мере реализации)
