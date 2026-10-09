# Mochi (P62): фотометрическое слияние брекетинга GCam 11 в гибридном воркере SCAMERA

Статус: сделано (2026-10-09), на GPU. Ключ тюнинга `mochi` (0 по умолчанию = как раньше, 1 = правило GCam, 2 = всегда).

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

## 2. Как устроено у нас (гибрид, `scam-hybrid.h`)

Наше слияние — не накопление, а «сбор»: на каждый выходной пиксель шейдер `kHybMergeMain1` обходит все кадры сразу, по полосам (strips)
128 ячеек (256 строк). Перед слиянием на полосу: guide базы, ячейки доноров (u = sqrt-домен), rejection (`kHybReject`), DilateMask
(`kHybDilate`) → веса `robust`. Порядок кадров на результат не влияет, поэтому «сначала все N, потом L» реализуется явным guide из N.

Mochi у нас — отдельные проходы (компилируются только при включённом Mochi) внутри цикла полос; при `mochi 0` ни одна ветка не
выполняется и ни одна программа не меняется (см. §5).

## 3. Реализация (GPU, требование владельца)

Отдельный проход перед слиянием, `HybridGpu::mochi` (`app/src/main/cpp/scam-hybrid.h`), вызывается один раз в
`gpuMerge` до `gpu.merge`. Три самостоятельные compute-программы со своими буферами (биндинги 0..5, после прохода
биндинги `merge()` восстанавливаются); компилируются только при `mochi > 0`, поэтому при `mochi 0` не меняется ни одна
программа и ни один буфер слияния, `kHybMergeMain1` не тронут.

1. `kHybMochiStats` — инвокация на тайл 16×16 RAW **в геометрии bracketed-кадра**. Для каждой ячейки 2×2: сайты кадра и база
   в той же точке сцены (гомография кадра, обращённая в первом порядке: `p = q − (H(q) − q)`, билинейно по сетке каждой фазы).
   Ячейка пропускается, если что-то ≥ 0.95 (клип) или разница > 3σ шума (+ максимум поправки) — это движение/несовмещение;
   в GCam ту же роль играет вес rejection. Среднее `база − кадр/t`, в собственных единицах кадра, ×1023 = DN10;
   `|c_rggb|² > 500` → 0, иначе клэмп ±20 (как GCam). SNR-вес `min(1, snr/5)` по базе (`SNRWeight(snr, 5.0)`).
   Меньше 16 принятых ячеек — тайл не измерен.
2. `kHybMochiBlur` — «GaussianBlur correction» и «GaussianBlur SNR map»: [1 2 1]², только по измеренным тайлам (отличие от GCam:
   у GCam тайл без принятых ячеек = 0 и тянет соседей к нулю; у нас у движущегося объекта фон сохранял до 2 DN смещения,
   теперь неизмеренный тайл берёт поправку соседей). Final = blur(c) × blur(w).
3. `kHybMochiApply` — инвокация на слово RAW (два сайта): `raw + bilinear(Final)·range`, округление, не выше
   `clipLevel − 0.005`; сайты ≥ 0.95 не трогаются (их клип-состояние сохраняется). Чтение обратно на CPU — новый буфер
   кадра, `in.frames[i]` указывает на него; дальше слияние как обычно.

Полосы по 16-строчным тайлам держат буферы в пределах storage block (≤ 64 МБ); база берётся с запасом по вертикальному
смещению гомографии. Не применяется: нативное мозаичное слияние (`mosaicPath` P29, отчёт `HYBRID MOCHI: off (native mosaic
merge)`), `upRatio ≠ 1`. Правило `mochi 1`: больше 3 не-bracketed кадров (как GCam); ночного режима у нас в воркере нет.
Переоценки чёрного уровня по брекетингу у нас нет, поэтому Mochi работает сам по себе (в GCam он идёт только вместе с ней).

## 4. Проверки

- `tools/check_hybrid_mochi.py` (в CI, moderngl): реальные шейдеры из заголовка на синтетике — поправка снимается до 1 DN
  (и при сдвиге (4, 2) px), клип нетронут побайтово и не достигается после поправки, `|c|² > 500` обнуляется, движущийся
  объект не влияет на фон, тёмные тайлы — с SNR-весом.
- OPPO replay (2026-10-09): при `mochi 0` md5 всех шести серий прежние (103304c8, a178996c, e7936ad7, 0a46d3be, 06947ccd,
  ae73516c). `uw.nch` (5 bracketed кадров ×2): 45 900 из 49 152 тайлов измерено, ~70–100 обнулено, средняя |c| 0.5–0.97 DN10
  по фазам, 50–170 мс на кадр; md5 579ac114 → c97dbef3, детерминировано. `hh_1`, `q1x`, `arena*` — нативный мозаичный путь
  (Mochi не применяется), `zip*` — без брекетинга.

## 5. Открыто

- Нативный мозаичный путь (Quad/Tetra, P29): поправку можно применить к нативным кадрам (`in.nativeFrames`) тем же проходом
  на их сетке — не сделано.
- Включение по умолчанию — после сравнения на реальных снимках владельца (A/B через `hybrid_tuning.txt`: `mochi 2`).
