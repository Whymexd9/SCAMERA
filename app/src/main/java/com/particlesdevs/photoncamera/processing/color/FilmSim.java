package com.particlesdevs.photoncamera.processing.color;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.Lang;

import java.util.Locale;

/**
 * P281 (owner, 2026-10-19: a film simulation - film stock, print, format, effects - with the structure of the LUTs and the lens
 * filters): our own spectral model of a film photograph, built as a 3D LUT of the display-encoded output (the pipeline's
 * FILM_LUT path, so the photo and the viewfinder both show it) plus the effects of the film's size (FilmEffects: halation,
 * film sharpness, grain) that the pipeline draws on the GPU.
 *
 * <p>The model, per colour of the LUT's input:
 * <ol>
 *   <li>the display colour back to scene light (sRGB decode, the tone's shoulder undone, mid grey kept at 0.18);</li>
 *   <li>the film's three layers expose through their own spectral sensitivities: each a Gaussian band (peak, width) integrated
 *       over the spectra of the sRGB primaries (a partition of unity of the visible range, so white exposes every layer
 *       alike);</li>
 *   <li>each layer's characteristic curve (density over log exposure: a toe and a shoulder as a difference of two softplus
 *       terms; its contrast, latitude, base and maximum density per stock), mid grey placed at the stock's mid density;</li>
 *   <li>the interlayer (DIR) effect: each layer's density pushed away from the other two in proportion, the neutral scale
 *       untouched - the film's colour separation;</li>
 *   <li>the dyes the densities form (cyan, magenta, yellow bands with their side absorptions) on the film's base (the colour
 *       negative's orange mask), as a spectral transmittance;</li>
 *   <li>a negative is printed: a tungsten enlarger lamp through the negative onto the paper's three narrow sensitivities, the
 *       printing balanced on mid grey (the enlarger's filtration), the paper's curve and its dyes, the print seen under D65; a
 *       slide or a reversal film is projected (the film itself under D65); black and white has one silver layer;</li>
 *   <li>the light back to display RGB (CIE 1931 observer as the multi-lobe fit of Wyman, Sloan and Shirley; XYZ to linear sRGB),
 *       the paper's (or the clear film's) white as white, and encoded.</li>
 * </ol>
 * The stocks are named after real films; their parameters are our own estimates from the makers' published characteristics
 * (contrast, speed, granularity, resolving power, sensitivity and dye peaks), not measured data.
 */
public final class FilmSim {
    private FilmSim() {}

    public static final String STOCK_KEY = "pref_film_sim_stock", PRINT_KEY = "pref_film_sim_print", FORMAT_KEY = "pref_film_sim_format",
            GRAIN_KEY = "pref_film_sim_grain", HALATION_KEY = "pref_film_sim_halation", SOFTNESS_KEY = "pref_film_sim_softness",
            EXPOSURE_KEY = "pref_film_sim_exposure", PRINT_EV_KEY = "pref_film_sim_print_ev", TONE_KEY = "pref_film_sim_tone",
            STRENGTH_KEY = "pref_film_sim_strength", PRINT_M_KEY = "pref_film_sim_print_m", PRINT_Y_KEY = "pref_film_sim_print_y";
    /** Every stored key (the lab, the backups). */
    public static final String[] KEYS = {STOCK_KEY, PRINT_KEY, FORMAT_KEY, GRAIN_KEY, HALATION_KEY, SOFTNESS_KEY, EXPOSURE_KEY,
            PRINT_EV_KEY, TONE_KEY, PRINT_M_KEY, PRINT_Y_KEY, STRENGTH_KEY};

    public static final int NEGATIVE = 0, CINE = 1, SLIDE = 2, BW_NEGATIVE = 3, BW_REVERSAL = 4;

    // ───── the stocks

    /** A film stock. Layers 0 red (cyan dye), 1 green (magenta), 2 blue (yellow). */
    public static final class Stock {
        public final String id, brand, name;
        public final int type, iso;
        /** Spectral sensitivity peaks and widths (nm), red, green, blue layer. */
        final float[] peak, width;
        /** Contrast (density per log10 exposure), per layer multipliers, density range above the base. */
        final float gamma;
        final float[] tilt;
        final float range;
        /** Toe and shoulder softness (log10 exposure). */
        final float toe, shoulder;
        /** Interlayer effect (0 none .. 0.35 strong). */
        final float dir;
        /** Dye peaks (nm) cyan, magenta, yellow; their widths; the side absorptions' scale. */
        final float[] dyePeak, dyeWidth;
        final float impurity;
        /** A warm (+) / cool (-) balance of the exposure (stops of red against blue). */
        final float warmth;
        /** Diffuse RMS granularity (x1000, 48 um aperture), dye-cloud / grain size (um), film MTF (Gaussian sigma, um). */
        public final float rms, grainUm, mtfUm;
        /** Halation strength of the red, green, blue layer (the antihalation layer's leak) and its scale (um). */
        public final float[] halation;
        public final float halationUm;
        /** P286: the stock's measured data (FilmSpectral, the reference model); null: our own model (black and white). */
        @Nullable public final String spectral;
        /** P286: the paper the stock's maker pairs it with (the default print). */
        @Nullable final String targetPrint;

        /** P286: a stock of the reference model (its curves, dyes and sensitivities from the data; grain and halation ours). */
        Stock(String spectral, String brand, String name, int type, int iso, @Nullable String targetPrint, float rms, float grainUm,
              float mtfUm, float[] halation, float halationUm) {
            this.id = spectral;
            this.spectral = spectral;
            this.targetPrint = targetPrint;
            this.brand = brand;
            this.name = name;
            this.type = type;
            this.iso = iso;
            this.rms = rms;
            this.grainUm = grainUm;
            this.mtfUm = mtfUm;
            this.halation = halation;
            this.halationUm = halationUm;
            peak = width = tilt = dyePeak = dyeWidth = null;
            gamma = range = toe = shoulder = dir = impurity = warmth = 0;
        }

        Stock(String id, String brand, String name, int type, int iso, float[] peak, float[] width, float gamma, float[] tilt,
              float range, float toe, float shoulder, float dir, float[] dyePeak, float[] dyeWidth, float impurity, float warmth,
              float rms, float grainUm, float mtfUm, float[] halation, float halationUm) {
            this.id = id;
            this.brand = brand;
            this.name = name;
            this.type = type;
            this.iso = iso;
            this.peak = peak;
            this.width = width;
            this.gamma = gamma;
            this.tilt = tilt;
            this.range = range;
            this.toe = toe;
            this.shoulder = shoulder;
            this.dir = dir;
            this.dyePeak = dyePeak;
            this.dyeWidth = dyeWidth;
            this.impurity = impurity;
            this.warmth = warmth;
            this.rms = rms;
            this.grainUm = grainUm;
            this.mtfUm = mtfUm;
            this.halation = halation;
            this.halationUm = halationUm;
            spectral = null;
            targetPrint = null;
        }

        public boolean mono() { return type == BW_NEGATIVE || type == BW_REVERSAL; }

        /** Projected (no print): a slide or a black-and-white reversal film. */
        public boolean reversal() { return type == SLIDE || type == BW_REVERSAL; }
    }

    private static float[] f(float... v) { return v; }

    private static final float[] NEG_PEAK = {645, 548, 445}, NEG_WIDTH = {26, 30, 28};
    private static final float[] NEG_DYE = {685, 550, 448}, NEG_DYE_W = {48, 34, 30};
    private static final float[] REV_DYE = {660, 548, 448}, REV_DYE_W = {44, 36, 30};
    private static final float[] WEAK_AH = {.08f, .02f, 0f}, STRONG_AH = {.015f, .005f, 0f}, BW_AH = {.05f, .05f, .05f};

    public static final Stock[] STOCKS = {
            // P286: the reference model's stocks (the halation preset by base and antihalation layer, as the reference's)
            new Stock("kodak_portra_160", "Kodak", "Portra 160", NEGATIVE, 160, "kodak_portra_endura", 4.0f, 2.6f, 4.2f, STRONG_AH, 65),
            new Stock("kodak_portra_400", "Kodak", "Portra 400", NEGATIVE, 400, "kodak_portra_endura", 5.0f, 3.0f, 4.8f, STRONG_AH, 65),
            new Stock("kodak_portra_800", "Kodak", "Portra 800", NEGATIVE, 800, "kodak_portra_endura", 7.0f, 3.6f, 5.6f, STRONG_AH, 65),
            new Stock("kodak_portra_800_push1", "Kodak", "Portra 800 (Push 1)", NEGATIVE, 1600, "kodak_portra_endura", 8.5f, 4.0f, 6.0f,
                    STRONG_AH, 65),
            new Stock("kodak_portra_800_push2", "Kodak", "Portra 800 (Push 2)", NEGATIVE, 3200, "kodak_portra_endura", 10.0f, 4.4f, 6.4f,
                    STRONG_AH, 65),
            new Stock("kodak_gold_200", "Kodak", "Gold 200", NEGATIVE, 200, "kodak_portra_endura", 5.0f, 3.0f, 4.8f, WEAK_AH, 65),
            new Stock("kodak_ultramax_400", "Kodak", "Ultramax 400", NEGATIVE, 400, "kodak_portra_endura", 6.0f, 3.4f, 5.2f, WEAK_AH, 65),
            new Stock("kodak_ektar_100", "Kodak", "Ektar 100", NEGATIVE, 100, "kodak_portra_endura", 3.0f, 2.2f, 3.6f, STRONG_AH, 65),
            new Stock("fujifilm_c200", "Fujifilm", "C200", NEGATIVE, 200, "fujifilm_crystal_archive_typeii", 5.0f, 3.0f, 4.8f, WEAK_AH, 65),
            new Stock("fujifilm_xtra_400", "Fujifilm", "X-Tra 400", NEGATIVE, 400, "fujifilm_crystal_archive_typeii", 6.0f, 3.4f, 5.2f,
                    WEAK_AH, 65),
            new Stock("fujifilm_pro_400h", "Fujifilm", "Pro 400H", NEGATIVE, 400, "fujifilm_crystal_archive_typeii", 5.0f, 3.2f, 5.0f,
                    STRONG_AH, 65),
            new Stock("kodak_vision3_50d", "Kodak", "Vision3 50D", CINE, 50, "kodak_2383", 4.0f, 2.4f, 3.8f, STRONG_AH, 50),
            new Stock("kodak_vision3_200t", "Kodak", "Vision3 200T", CINE, 200, "kodak_2383", 5.0f, 2.8f, 4.4f, STRONG_AH, 50),
            new Stock("kodak_vision3_250d", "Kodak", "Vision3 250D", CINE, 250, "kodak_2383", 5.0f, 2.8f, 4.4f, STRONG_AH, 50),
            new Stock("kodak_vision3_500t", "Kodak", "Vision3 500T", CINE, 500, "kodak_2383", 7.0f, 3.4f, 5.2f, STRONG_AH, 50),
            new Stock("kodak_verita_200d", "Kodak", "Verita 200D", CINE, 200, "kodak_2383", 5.0f, 2.8f, 4.4f, STRONG_AH, 50),
            new Stock("kodak_ektachrome_100", "Kodak", "Ektachrome 100", SLIDE, 100, null, 8.0f, 2.4f, 4.0f, STRONG_AH, 65),
            new Stock("fujifilm_provia_100f", "Fujifilm", "Provia 100F", SLIDE, 100, null, 8.0f, 2.3f, 3.8f, STRONG_AH, 65),
            new Stock("fujifilm_velvia_100", "Fujifilm", "Velvia 100", SLIDE, 100, null, 9.0f, 2.2f, 3.6f, STRONG_AH, 65),
            new Stock("kodak_kodachrome_64", "Kodak", "Kodachrome 64", SLIDE, 64, null, 9.0f, 2.4f, 3.8f, WEAK_AH, 65),
            // black and white: no measured data in the reference; our own model
            new Stock("trix400", "Kodak", "Tri-X 400", BW_NEGATIVE, 400, f(560, 560, 560), f(110, 110, 110), .65f, f(1, 1, 1), 2.3f, .30f,
                    .40f, 0f, f(0, 0, 0), f(0, 0, 0), 0f, 0f, 17.0f, 6.0f, 6.0f, BW_AH, 65),
            new Stock("tmax100", "Kodak", "T-Max 100", BW_NEGATIVE, 100, f(555, 555, 555), f(105, 105, 105), .62f, f(1, 1, 1), 2.4f, .26f,
                    .40f, 0f, f(0, 0, 0), f(0, 0, 0), 0f, 0f, 8.0f, 3.0f, 3.6f, BW_AH, 65),
            new Stock("hp5", "Ilford", "HP5 Plus", BW_NEGATIVE, 400, f(565, 565, 565), f(115, 115, 115), .62f, f(1, 1, 1), 2.3f, .32f,
                    .42f, 0f, f(0, 0, 0), f(0, 0, 0), 0f, 0f, 16.0f, 5.6f, 5.8f, BW_AH, 65),
            new Stock("trix7266", "Kodak", "Tri-X 7266", BW_REVERSAL, 200, f(560, 560, 560), f(110, 110, 110), 1.75f, f(1, 1, 1), 3.0f,
                    .24f, .30f, 0f, f(0, 0, 0), f(0, 0, 0), 0f, 0f, 14.0f, 5.0f, 5.2f, BW_AH, 50),
    };

    // ───── the prints

    /** A print material: the paper's sensitivities, curve and dyes. */
    public static final class Print {
        public final String id, brand, name;
        /** A projected print film (cine), not a reflection paper. */
        public final boolean film;
        final float[] sensPeak, sensWidth;
        final float gamma, dmax, mid, toe, shoulder;
        final float[] dyePeak, dyeWidth;
        final float impurity;

        /** P286: a print of the reference model. */
        Print(String id, String brand, String name, boolean film) {
            this(id, brand, name, film, null, null, 0, 0, 0, 0, 0, null, null, 0);
        }

        Print(String id, String brand, String name, boolean film, float[] sensPeak, float[] sensWidth, float gamma, float dmax,
              float mid, float toe, float shoulder, float[] dyePeak, float[] dyeWidth, float impurity) {
            this.id = id;
            this.brand = brand;
            this.name = name;
            this.film = film;
            this.sensPeak = sensPeak;
            this.sensWidth = sensWidth;
            this.gamma = gamma;
            this.dmax = dmax;
            this.mid = mid;
            this.toe = toe;
            this.shoulder = shoulder;
            this.dyePeak = dyePeak;
            this.dyeWidth = dyeWidth;
            this.impurity = impurity;
        }
    }

    /** The papers and print films of the reference model (the colour negatives print on them). */
    public static final Print[] PRINTS = {
            new Print("kodak_portra_endura", "Kodak", "Portra Endura", false),
            new Print("kodak_endura_premier", "Kodak", "Endura Premier", false),
            new Print("kodak_ultra_endura", "Kodak", "Ultra Endura", false),
            new Print("kodak_supra_endura", "Kodak", "Supra Endura", false),
            new Print("kodak_ektacolor_edge", "Kodak", "Ektacolor Edge", false),
            new Print("fujifilm_crystal_archive_typeii", "Fujifilm", "Crystal Archive Type II", false),
            new Print("kodak_2383", "Kodak", "Vision 2383", true),
            new Print("kodak_2393", "Kodak", "Vision Premier 2393", true),
    };

    /** The paper our black-and-white model prints on (its curve; the colour papers are measured data). */
    static final Print MONO_PAPER = new Print("endura_premier", "Kodak", "Endura Premier", false, f(692, 545, 450), f(18, 20, 22), 2.75f,
            2.35f, .80f, .22f, .30f, f(655, 546, 446), f(42, 34, 29), 1f);

    // ───── the formats

    /** A film format: its frame's long edge (mm). 0: no format (no grain, halation or film softness). */
    public static final class Format {
        public final String id, name;
        public final float longMm;

        Format(String id, String name, float longMm) {
            this.id = id;
            this.name = name;
            this.longMm = longMm;
        }
    }

    public static final Format[] FORMATS = {
            new Format("off", "", 0f),
            new Format("8mm", "8 mm", 4.8f),
            new Format("super8", "Super 8", 5.79f),
            new Format("16mm", "16 mm", 10.26f),
            new Format("super16", "Super 16", 12.52f),
            new Format("super35", "Super 35", 24.89f),
            new Format("135", "35 mm", 36f),
            new Format("645", "645", 56f),
            new Format("67", "6×7", 70f),
    };

    /** P286: the P281 ids (stored settings) to the reference model's. */
    private static final String[][] OLD_IDS = {{"portra160", "kodak_portra_160"}, {"portra400", "kodak_portra_400"},
            {"portra800", "kodak_portra_800"}, {"gold200", "kodak_gold_200"}, {"ultramax400", "kodak_ultramax_400"},
            {"ektar100", "kodak_ektar_100"}, {"c200", "fujifilm_c200"}, {"xtra400", "fujifilm_xtra_400"}, {"pro400h", "fujifilm_pro_400h"},
            {"vision50d", "kodak_vision3_50d"}, {"vision250d", "kodak_vision3_250d"}, {"vision500t", "kodak_vision3_500t"},
            {"ektachrome100", "kodak_ektachrome_100"}, {"velvia50", "fujifilm_velvia_100"}, {"provia100f", "fujifilm_provia_100f"},
            {"kodachrome64", "kodak_kodachrome_64"}, {"endura_premier", "kodak_endura_premier"}, {"ultra_endura", "kodak_ultra_endura"},
            {"ektacolor_edge", "kodak_ektacolor_edge"}, {"portra_endura", "kodak_portra_endura"},
            {"crystal_archive", "fujifilm_crystal_archive_typeii"}, {"vision2383", "kodak_2383"}, {"vision2393", "kodak_2393"}};

    static String currentId(String id) {
        for (String[] o : OLD_IDS) if (o[0].equals(id)) return o[1];
        return id;
    }

    @Nullable
    public static Stock stock(@Nullable String id) {
        if (id != null) {
            id = currentId(id);
            for (Stock s : STOCKS) if (s.id.equals(id)) return s;
        }
        return null;
    }

    public static Print print(@Nullable String id) {
        if (id != null) {
            id = currentId(id);
            for (Print p : PRINTS) if (p.id.equals(id)) return p;
        }
        return PRINTS[0];
    }

    /** The print a stock is printed on: the chosen one, or (none chosen) the one its maker pairs it with. */
    public static Print print(@Nullable String id, @Nullable Stock stock) {
        if ((id == null || id.isEmpty()) && stock != null && stock.targetPrint != null) return print(stock.targetPrint);
        return print(id);
    }

    public static Format format(@Nullable String id) {
        if (id != null) for (Format f : FORMATS) if (f.id.equals(id)) return f;
        return FORMATS[6];
    }

    public static String typeName(@Nullable android.content.Context c, Stock s) {
        switch (s.type) {
            case CINE: return Lang.t(c, "Кинонегатив · ISO " + s.iso, "Cine negative · ISO " + s.iso);
            case SLIDE: return Lang.t(c, "Слайд · ISO " + s.iso, "Slide · ISO " + s.iso);
            case BW_NEGATIVE: return Lang.t(c, "Ч/б негатив · ISO " + s.iso, "B&W negative · ISO " + s.iso);
            case BW_REVERSAL: return Lang.t(c, "Ч/б обращаемая · ISO " + s.iso, "B&W reversal · ISO " + s.iso);
            default: return Lang.t(c, "Цветной негатив · ISO " + s.iso, "Colour negative · ISO " + s.iso);
        }
    }

    public static String formatName(@Nullable android.content.Context c, Format f) {
        return f.longMm <= 0 ? Lang.t(c, "Выкл.", "Off") : f.name;
    }

    public static String formatHint(@Nullable android.content.Context c, Format f) {
        return f.longMm <= 0 ? Lang.t(c, "без зерна и ореола", "no grain or halation")
                : Lang.t(c, String.format(Locale.ROOT, "кадр %.2f мм по длинной стороне", f.longMm),
                String.format(Locale.ROOT, "%.2f mm frame long edge", f.longMm));
    }

    // ───── the settings

    /** The simulation chosen in the settings (null: off). */
    public static final class Settings {
        public final Stock stock;
        public final Print print;
        public final Format format;
        /**
         * 0..2 of the stock's own grain / halation / softness; exposure and print exposure in stops; tone (the film's contrast) -1..1;
         * the enlarger's magenta / yellow filters over the neutral (Kodak CC); strength 0..1 (the colour, the grain, the halation
         * and the softness: {@link #halationAmounts}, {@link #softnessApplied}, {@link #grainApplied}).
         */
        public final float grain, halation, softness, exposure, printEv, tone, strength;
        public final float printM, printY;

        public Settings(Stock stock, Print print, Format format, float grain, float halation, float softness, float exposure,
                        float printEv, float tone, float strength) {
            this(stock, print, format, grain, halation, softness, exposure, printEv, tone, 0f, 0f, strength);
        }

        public Settings(Stock stock, Print print, Format format, float grain, float halation, float softness, float exposure,
                        float printEv, float tone, float printM, float printY, float strength) {
            this.printM = printM;
            this.printY = printY;
            this.stock = stock;
            this.print = print;
            this.format = format;
            this.grain = grain;
            this.halation = halation;
            this.softness = softness;
            this.exposure = exposure;
            this.printEv = printEv;
            this.tone = tone;
            this.strength = strength;
        }

        /** What the LUT depends on (the effects do not change it). */
        public String lutKey() {
            return stock.id + "|" + (stock.reversal() ? "-" : print.id) + "|" + fmt(exposure) + "|" + fmt(printEv) + "|" + fmt(tone)
                    + "|" + fmt(printM) + "|" + fmt(printY);
        }

        /**
         * «Сила симуляции» on the spatial effects (settings audit 2026-10-21: at 50 % they applied in full): each layer's halation
         * amount (the stock's antihalation leak x «Ореол» x the strength), as FilmHalation and the preview (FilmEffects) apply it.
         */
        public float[] halationAmounts() {
            final float[] a = new float[3];
            for (int c = 0; c < 3; c++) a[c] = stock.halation[c] * halation * strength;
            return a;
        }

        /** «Мягкость плёнки» x the strength: the scale of the in-emulsion scatter and of a classic stock's sharpness blur. */
        public float softnessApplied() {
            return softness * strength;
        }

        /** «Зерно» x the strength: the grain of every stock (the reference model's and the classic one). */
        public float grainApplied() {
            return grain * strength;
        }

        /** The film's pixel size (um) on an image {@code longPx} pixels long; 0 without a format. */
        public float pixelUm(int longPx) {
            return format.longMm <= 0 || longPx <= 0 ? 0f : format.longMm * 1000f / longPx;
        }

        /**
         * P369 (viewfinder-filmchain-cache-key): the settings a {@link #lutKey} stands for - the stock, the print and the five numbers
         * the LUT depends on - at {@code strength}; no format, grain, halation or softness (the LUT does not depend on them). The
         * leading fields of {@code key} are read (a chain key's further fields are left); null for a key of no known stock.
         */
        @Nullable
        public static Settings forLut(@Nullable String key, float strength) {
            if (key == null) return null;
            final String[] p = key.split("\\|", -1);
            if (p.length < 7) return null;
            final Stock stock = stock(p[0]);
            if (stock == null) return null;
            return new Settings(stock, print(p[1].equals("-") ? "" : p[1], stock), FORMATS[0], 0f, 0f, 0f, number(p[2], 0f),
                    number(p[3], 0f), number(p[4], 0f), number(p[5], 0f), number(p[6], 0f), strength);
        }
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** The settings now (the default preferences, through the lab's overlay too); null when no stock is chosen. */
    @Nullable
    public static Settings current() {
        try {
            final com.particlesdevs.photoncamera.settings.SettingsManager m =
                    com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic();
            final String scope = "default_scope";
            final Stock stock = stock(m.getString(scope, STOCK_KEY, ""));
            if (stock == null) return null;
            final float strength = clamp(number(m.getString(scope, STRENGTH_KEY, "100"), 100f) / 100f, 0f, 1f);
            if (strength <= 0f) return null;
            return new Settings(stock, print(m.getString(scope, PRINT_KEY, ""), stock), format(m.getString(scope, FORMAT_KEY, "135")),
                    clamp(number(m.getString(scope, GRAIN_KEY, "100"), 100f) / 100f, 0f, 2f),
                    clamp(number(m.getString(scope, HALATION_KEY, "100"), 100f) / 100f, 0f, 4f),
                    clamp(number(m.getString(scope, SOFTNESS_KEY, "100"), 100f) / 100f, 0f, 3f),
                    clamp(number(m.getString(scope, EXPOSURE_KEY, "0"), 0f), -3f, 3f),
                    clamp(number(m.getString(scope, PRINT_EV_KEY, "0"), 0f), -2f, 2f),
                    clamp(number(m.getString(scope, TONE_KEY, "0"), 0f) / 100f, -1f, 1f),
                    clamp(number(m.getString(scope, PRINT_M_KEY, "0"), 0f), -50f, 50f),
                    clamp(number(m.getString(scope, PRINT_Y_KEY, "0"), 0f), -50f, 50f), strength);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static float number(@Nullable String v, float fallback) {
        if (v == null) return fallback;
        try {
            final float f = Float.parseFloat(v.trim().replace(',', '.'));
            return Float.isFinite(f) ? f : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ───── the spectral model

    /** 400..700 nm in 10 nm steps. */
    static final int BINS = 31;
    static final float[] LAMBDA = new float[BINS];
    /** The sRGB primaries' spectra (a partition of unity) and the D65 observer weights to linear sRGB per bin. */
    static final float[][] PRIMARY = new float[3][BINS];
    static final float[][] VIEW = new float[3][BINS];
    static final float[] LAMP = new float[BINS];

    static {
        for (int i = 0; i < BINS; i++) LAMBDA[i] = 400 + 10 * i;
        final double[][] xyzToRgb = {{3.2404542, -1.5371385, -0.4985314}, {-0.9692660, 1.8760108, 0.0415560},
                {0.0556434, -0.2040259, 1.0572252}};
        for (int i = 0; i < BINS; i++) {
            final double l = LAMBDA[i];
            final double b = 1 / (1 + Math.exp((l - 492) / 11.0)), r = 1 / (1 + Math.exp(-(l - 584) / 11.0));
            PRIMARY[0][i] = (float) r;
            PRIMARY[2][i] = (float) b;
            PRIMARY[1][i] = (float) Math.max(0, 1 - r - b);
            final double ill = planck(l, 6504);
            final double x = 1.056 * lobe(l, 599.8, 37.9, 31.0) + 0.362 * lobe(l, 442.0, 16.0, 26.7) - 0.065 * lobe(l, 501.1, 20.4, 26.2);
            final double y = 0.821 * lobe(l, 568.8, 46.9, 40.5) + 0.286 * lobe(l, 530.9, 16.3, 31.1);
            final double z = 1.217 * lobe(l, 437.0, 11.8, 36.0) + 0.681 * lobe(l, 459.0, 26.0, 13.8);
            for (int c = 0; c < 3; c++)
                VIEW[c][i] = (float) (ill * (xyzToRgb[c][0] * x + xyzToRgb[c][1] * y + xyzToRgb[c][2] * z));
            // the enlarger: a tungsten-halogen lamp behind a heat-absorbing glass
            LAMP[i] = (float) (planck(l, 3200) / (1 + Math.exp((l - 680) / 14.0)));
        }
    }

    static double lobe(double l, double mu, double s1, double s2) {
        final double t = (l - mu) / (l < mu ? s1 : s2);
        return Math.exp(-.5 * t * t);
    }

    static double gauss(double l, double mu, double s) {
        final double t = (l - mu) / s;
        return Math.exp(-.5 * t * t);
    }

    static double planck(double nm, double k) {
        final double m = nm * 1e-9;
        return 1.0 / (Math.pow(m, 5) * (Math.exp(1.4387769e-2 / (m * k)) - 1)) * 1e-30;
    }

    static double softplus10(double u) {
        return u > 30 ? u : Math.log10(1 + Math.pow(10, u));
    }

    /** A rising characteristic curve: density above the base over log10 exposure (0 = mid grey), {0 .. range}. */
    static final class Curve {
        final double g, range, toe, shoulder, x0;

        Curve(double g, double range, double toe, double shoulder, double mid) {
            this.g = g;
            this.range = range;
            this.toe = toe;
            this.shoulder = shoulder;
            // the toe's position so that mid grey lands at density {@code mid} above the base
            double lo = -6, hi = 6;
            for (int i = 0; i < 60; i++) {
                final double m = (lo + hi) / 2;
                if (raw(0, m) > mid) lo = m; else hi = m;
            }
            x0 = (lo + hi) / 2;
        }

        double raw(double x, double origin) {
            final double r = range / g;
            return g * toe * softplus10((x - origin) / toe) - g * shoulder * softplus10((x - origin - r) / shoulder);
        }

        double at(double x) {
            return Math.max(0, Math.min(range, raw(x, x0)));
        }
    }

    /** One simulation, prepared: the per-bin spectra, the curves, the printing balance and the grey balance. */
    static final class Model {
        final Stock s;
        final Print p;
        final boolean mono, reversal;
        final float[][] crosstalk = new float[3][3];
        final Curve[] film = new Curve[3];
        final float[][] dye = new float[3][BINS];
        final float[] base = new float[BINS];
        final float[][] paperSens = new float[3][BINS], paperDye = new float[3][BINS];
        final Curve[] paper = new Curve[3];
        final float[] balance = new float[3];
        final float[] white = new float[3];
        final double exposure;
        /** The grey balance: per-channel gains over the neutral scale (scene log2 exposure GREY_LO .. GREY_HI). */
        static final int GREY = 48;
        static final double GREY_LO = -8, GREY_HI = 5;
        final float[][] grey = new float[GREY][3];

        Model(Stock s, Print p, float exposureEv, float printEv, float tone) {
            this.s = s;
            this.p = p;
            mono = s.mono();
            reversal = s.reversal();
            exposure = exposureEv * Math.log10(2);
            // the layers' exposure from the sRGB primaries (rows sum to 1: white exposes every layer alike)
            for (int i = 0; i < 3; i++) {
                double sum = 0;
                final double[] acc = new double[3];
                for (int k = 0; k < BINS; k++) {
                    final double sens = gauss(LAMBDA[k], s.peak[i], s.width[i]);
                    sum += sens;
                    for (int j = 0; j < 3; j++) acc[j] += sens * PRIMARY[j][k];
                }
                // the spectral overlap kept at SEPARATION: the masking couplers and the interlayer effects of a real film take most
                // of it back (rows still sum to 1)
                for (int j = 0; j < 3; j++) crosstalk[i][j] = (float) (SEPARATION * acc[j] / sum + (i == j ? 1 - SEPARATION : 0));
            }
            final double mid = reversal ? s.range - .75 : NEGATIVE_MID;
            for (int i = 0; i < 3; i++) film[i] = new Curve(s.gamma * s.tilt[i], s.range, s.toe, s.shoulder, mid);
            for (int k = 0; k < BINS; k++) {
                final double l = LAMBDA[k];
                if (mono) {
                    // one silver layer, spectrally flat
                    dye[0][k] = 1f;
                    base[k] = .18f;
                    continue;
                }
                final double imp = s.impurity;
                dye[0][k] = (float) (gauss(l, s.dyePeak[0], s.dyeWidth[0]) + imp * (.07 * gauss(l, 545, 40) + .04 * gauss(l, 450, 35)));
                dye[1][k] = (float) (gauss(l, s.dyePeak[1], s.dyeWidth[1]) + imp * (.12 * gauss(l, 440, 30) + .03 * gauss(l, 630, 40)));
                dye[2][k] = (float) (gauss(l, s.dyePeak[2], s.dyeWidth[2]) + imp * .03 * gauss(l, 545, 40));
                // the colour negative's orange mask (taken out by the printing balance), a slide's clear base
                base[k] = (float) (s.type == NEGATIVE || s.type == CINE ? .55 * gauss(l, 440, 45) + .28 * gauss(l, 520, 40) + .12 : .06);
            }
            for (int k = 0; k < BINS; k++) {
                final double l = LAMBDA[k];
                for (int c = 0; c < 3; c++) paperSens[c][k] = (float) gauss(l, p.sensPeak[c], p.sensWidth[c]);
                if (mono) {
                    paperDye[0][k] = 1f;
                    continue;
                }
                paperDye[0][k] = (float) (gauss(l, p.dyePeak[0], p.dyeWidth[0]) + p.impurity * .06 * gauss(l, 550, 40));
                paperDye[1][k] = (float) (gauss(l, p.dyePeak[1], p.dyeWidth[1]) + p.impurity * (.10 * gauss(l, 445, 30) + .02 * gauss(l, 640, 40)));
                paperDye[2][k] = (float) (gauss(l, p.dyePeak[2], p.dyeWidth[2]) + p.impurity * .02 * gauss(l, 545, 40));
            }
            for (int c = 0; c < 3; c++) paper[c] = new Curve(p.gamma, p.dmax - PAPER_DMIN, p.toe, p.shoulder, p.mid - PAPER_DMIN);
            // the printing balance: a mid-grey negative exposes each paper layer to 1 (the enlarger's filtration), then the
            // print exposure and the tone (warmer: more exposure of the blue-sensitive layer, less of the red one)
            final float[] t = transmittance(filmDensity(new double[]{.18, .18, .18}));
            for (int c = 0; c < 3; c++) {
                double q = 0;
                for (int k = 0; k < BINS; k++) q += LAMP[k] * t[k] * (mono ? paperSens[0][k] + paperSens[1][k] + paperSens[2][k] : paperSens[c][k]);
                balance[c] = (float) (1 / Math.max(q, 1e-30));
            }
            final double ev = Math.pow(2, printEv);
            balance[0] *= (float) (ev * Math.pow(10, mono ? 0 : -.035 * tone));
            balance[1] *= (float) (ev * Math.pow(10, mono ? 0 : .010 * tone));
            balance[2] *= (float) (ev * Math.pow(10, mono ? 0 : .045 * tone));
            // the white: the paper's base (a print) or the clear film (a slide)
            final float[] w = reversal ? light(transmittance(new double[]{0, 0, 0}))
                    : light(reflectance(new double[]{PAPER_DMIN, PAPER_DMIN, PAPER_DMIN}));
            for (int c = 0; c < 3; c++) white[c] = Math.max(1e-9f, w[c]);
            // the grey balance: a print of a neutral scale is neutral (the paper's dyes are balanced to it); a tone keeps its tint
            for (int i = 0; i < GREY; i++) {
                final double e = .18 * Math.pow(2, GREY_LO + (GREY_HI - GREY_LO) * i / (GREY - 1));
                final float[] o = raw(new double[]{e, e, e});
                final float y = luma(o);
                for (int c = 0; c < 3; c++) grey[i][c] = o[c] > 1e-7f ? y / o[c] : 1f;
            }
            if (!mono && tone != 0) {
                // the chosen tone stays: its tint at mid grey is put back after the neutral balance
                final float[] o = raw(new double[]{.18, .18, .18});
                final float y = luma(o);
                for (int i = 0; i < GREY; i++) for (int c = 0; c < 3; c++) grey[i][c] *= o[c] > 1e-7f ? o[c] / y : 1f;
            }
        }

        static float luma(float[] o) {
            return .2126f * o[0] + .7152f * o[1] + .0722f * o[2];
        }

        /** The film's three dye amounts (density above the base) for scene light {@code rgb} (linear, 0.18 = mid grey). */
        double[] filmDensity(double[] rgb) {
            if (mono) {
                double e = 0;
                for (int j = 0; j < 3; j++) e += (j == 0 ? .30 : j == 1 ? .55 : .15) * rgb[j];
                final double d = film[0].at(Math.log10(Math.max(e, 1e-7) / .18) + exposure);
                return new double[]{reversal ? s.range - d : d, 0, 0};
            }
            final double warm = s.warmth * Math.log10(2) * .5;
            final double[] d = new double[3];
            for (int i = 0; i < 3; i++) {
                double e = 0;
                for (int j = 0; j < 3; j++) e += crosstalk[i][j] * rgb[j];
                d[i] = film[i].at(Math.log10(Math.max(e, 1e-7) / .18) + exposure + (i == 0 ? warm : i == 2 ? -warm : 0));
            }
            // the interlayer effect on each layer's log-exposure scale: away from the other two, the neutral scale kept
            final double[] n = new double[3];
            for (int i = 0; i < 3; i++) n[i] = d[i] / film[i].g;
            for (int i = 0; i < 3; i++) {
                double push = 0;
                for (int k = 0; k < 3; k++) if (k != i) push += (n[i] - n[k]) * .5;
                d[i] = Math.max(0, Math.min(s.range + .2, (n[i] + s.dir * push) * film[i].g));
            }
            if (reversal) for (int i = 0; i < 3; i++) d[i] = Math.max(0, s.range - d[i]);
            return d;
        }

        /** The film's spectral transmittance for its dye amounts. */
        float[] transmittance(double[] d) {
            final float[] t = new float[BINS];
            for (int k = 0; k < BINS; k++) {
                double dl = base[k];
                for (int c = 0; c < 3; c++) dl += d[c] * dye[c][k];
                t[k] = (float) Math.pow(10, -dl);
            }
            return t;
        }

        /** The print's spectral reflectance (a print film's transmittance) for its dye densities. */
        float[] reflectance(double[] dp) {
            final float[] r = new float[BINS];
            for (int k = 0; k < BINS; k++) {
                double dl = .03;
                for (int c = 0; c < 3; c++) dl += dp[c] * paperDye[c][k];
                r[k] = (float) Math.pow(10, -dl);
            }
            return r;
        }

        /** Linear sRGB of a spectrum seen under D65. */
        static float[] light(float[] spectrum) {
            final float[] o = new float[3];
            for (int c = 0; c < 3; c++) {
                double v = 0;
                for (int k = 0; k < BINS; k++) v += spectrum[k] * VIEW[c][k];
                o[c] = (float) v;
            }
            return o;
        }

        /** Scene light (linear sRGB, mid grey 0.18) to display light (linear sRGB, white 0.97), before the grey balance. */
        float[] raw(double[] rgb) {
            final double[] d = filmDensity(rgb);
            final float[] t = transmittance(d);
            final float[] seen;
            if (reversal) {
                seen = light(t);
            } else {
                final double[] dp = new double[3];
                for (int c = 0; c < 3; c++) {
                    if (mono && c > 0) break;
                    double q = 0;
                    for (int k = 0; k < BINS; k++) q += LAMP[k] * t[k] * (mono ? paperSens[0][k] + paperSens[1][k] + paperSens[2][k] : paperSens[c][k]);
                    dp[c] = paper[c].at(Math.log10(Math.max(q * balance[c], 1e-12))) + PAPER_DMIN;
                }
                seen = light(reflectance(dp));
            }
            final float[] o = new float[3];
            for (int c = 0; c < 3; c++) o[c] = Math.max(0f, seen[c] / white[c] * .97f);
            if (mono) o[0] = o[1] = o[2] = luma(o);
            return o;
        }

        /** Scene light to display light, grey balanced. */
        float[] render(double[] rgb) {
            final float[] o = raw(rgb);
            final double e = Math.max(1e-9, .2126 * rgb[0] + .7152 * rgb[1] + .0722 * rgb[2]);
            final double pos = (Math.log(e / .18) / Math.log(2) - GREY_LO) / (GREY_HI - GREY_LO) * (GREY - 1);
            final int i = (int) Math.max(0, Math.min(GREY - 2, Math.floor(pos)));
            final float f = (float) Math.max(0, Math.min(1, pos - i));
            for (int c = 0; c < 3; c++) o[c] *= grey[i][c] * (1 - f) + grey[i + 1][c] * f;
            return o;
        }
    }

    /** The share of the layers' spectral overlap a print keeps (the rest is the film's masking and interlayer correction). */
    static final double SEPARATION = .35;
    /** A negative's mid grey above its base: about five stops of shadow detail under it. */
    static final double NEGATIVE_MID = 1.0;

    /** The paper's base density (its white). */
    static final double PAPER_DMIN = .08;

    /**
     * The display colour (sRGB encoded, the tone's output) back to scene light, mid grey kept at 0.18: above it the tone's
     * shoulder undone (display white 1.0 maps to 8.38, about 5.5 stops over mid grey - P369: not the three stops this said), below
     * it the stops brought closer ({@link #SHADOW_STOPS} of each: the tone already lifted the shadows, and the film and the print
     * add their own contrast). P369: the photo and the RAW viewfinder feed the film scene light instead ({@link #lutScene}); this
     * display-domain table stays for the ISP viewfinder, the RAW viewfinder's own look and the settings' preview.
     */
    static double scene(double v) {
        final double y = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        if (y < .18) return y <= 0 ? 0 : .18 * Math.pow(y / .18, SHADOW_STOPS);
        final double c = .9;
        return y * (1 - c * .18) / Math.max(1e-4, 1 - c * Math.min(y, 1));
    }

    static final double SHADOW_STOPS = .62;

    static float encode(double y) {
        y = Math.max(0, Math.min(1, y));
        return (float) (y <= 0.0031308 ? 12.92 * y : 1.055 * Math.pow(y, 1 / 2.4) - 0.055);
    }

    /** P286: the film's contrast (the reference's density_curve_gamma) of the tone setting -1..1: x0.7 .. x1.4. */
    static double gamma(float tone) {
        return Math.pow(2, tone * .5);
    }

    /** P286: the reference model for a colour stock (null for our own black-and-white model, or when its data cannot be read). */
    @Nullable
    static FilmSpectral.Engine engine(Settings set) {
        if (set.stock.spectral == null) return null;
        try {
            return FilmSpectral.engine(set.stock.spectral, set.print.id, set.exposure, set.printEv, set.printM, set.printY, gamma(set.tone));
        } catch (java.io.IOException | RuntimeException e) {
            com.particlesdevs.photoncamera.util.Log.e("FilmSim", "film data " + set.stock.id + ": " + e);
            return null;
        }
    }

    /** The simulation as a LUT of the display-encoded colour, {@code n} points a side (red fastest). */
    public static FilmLut lut(Settings set, int n) {
        if (set.stock.spectral != null) {
            final FilmSpectral.Engine e = engine(set);
            final float[] v = new float[n * n * n * 3];
            final double[] sc = new double[n], o = new double[3];
            for (int i = 0; i < n; i++) sc[i] = scene(i / (double) (n - 1));
            int k = 0;
            for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
                if (e == null) {
                    v[k++] = r / (float) (n - 1);
                    v[k++] = g / (float) (n - 1);
                    v[k++] = b / (float) (n - 1);
                    continue;
                }
                e.render(sc[r], sc[g], sc[b], o);
                for (int c = 0; c < 3; c++) v[k++] = (float) Math.max(0, Math.min(1, o[c]));
            }
            return FilmLut.of(n, v, set.stock.brand + " " + set.stock.name);
        }
        final Model m = new Model(set.stock, MONO_PAPER, set.exposure, set.printEv, set.tone);
        final float[] v = new float[n * n * n * 3];
        final double[] sc = new double[n];
        for (int i = 0; i < n; i++) sc[i] = scene(i / (double) (n - 1));
        final double[] rgb = new double[3];
        int k = 0;
        for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
            rgb[0] = sc[r];
            rgb[1] = sc[g];
            rgb[2] = sc[b];
            final float[] o = m.render(rgb);
            v[k++] = encode(o[0]);
            v[k++] = encode(o[1]);
            v[k++] = encode(o[2]);
        }
        return FilmLut.of(n, v, set.stock.brand + " " + set.stock.name);
    }

    // ───── P369: the scene-referred film table (why/combine FILM_SIM 1)

    /** The reference model's mid grey (FilmSpectral.Engine.render: linear sRGB scene light, mid grey 0.184). */
    public static final float FILM_GREY = .184f;
    /**
     * The scene table's log grid: node i of n at FILM_GREY x 2^(LOG_LO + LOG_SPAN i / (n - 1)) (-10 .. +10 stops around it). The
     * model's colour goes on changing up to about +10 stops (a red light bleaches toward white from +6): a grid ending at +6 kept
     * a saturated light's colour (Portra up to 89 / 255 off, p99 50); Bento data reach 16 x the clip, x ae x deficit up to 5.
     */
    public static final float LOG_LO = -10f, LOG_SPAN = 20f;
    /** The scene table's points a side (FilmChain.scene): 41 keeps half a stop per node, as 33 did over 16 stops (in-grid max 2.4 / 255). */
    public static final int SCENE_SIZE = 41;
    /** The WHY AE's target (WhyAe ae_target, 0.15 of the exposed linear light) to the film's mid grey: the meter's grey prints as mid grey. */
    public static final float METER_TO_FILM = .184f / .15f;

    /**
     * P369 (owner, 2026-10-27: «Убедись что при включении симуляций плёнки наши алгоритмы не будут мешать симуляциям и в итоге у
     * нас получался "Look" такой какой должен быть с ними»): the simulation as a LUT of scene light, {@code n} points a side (red
     * fastest), the grid logarithmic per channel ({@link #FILM_GREY}, {@link #LOG_LO}, {@link #LOG_SPAN}) - why/combine's FILM_SIM
     * feed takes the exposed scene colour before our fusion, AgX and colour rows, as the model is made for (FilmSpectral.render), so
     * the photo prints what the film prints. Out: encoded sRGB, clamped to 0..1. Our black-and-white model is rendered at its own
     * mid grey 0.18 (x 0.18 / 0.184), so the AE's grey meets each model's own. Null when the reference model's data cannot be read
     * (the photo then has no film: WhyCombine says so; it never falls back to the display table).
     */
    @Nullable
    public static FilmLut lutScene(Settings set, int n) {
        final double[] sc = new double[n];
        for (int i = 0; i < n; i++) sc[i] = FILM_GREY * Math.pow(2, LOG_LO + LOG_SPAN * i / (double) (n - 1));
        final float[] v = new float[n * n * n * 3];
        int k = 0;
        if (set.stock.spectral != null) {
            final FilmSpectral.Engine e = engine(set);
            if (e == null) return null;
            final double[] o = new double[3];
            for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
                e.render(sc[r], sc[g], sc[b], o);
                for (int c = 0; c < 3; c++) v[k++] = (float) Math.max(0, Math.min(1, o[c]));
            }
            return FilmLut.of(n, v, set.stock.brand + " " + set.stock.name);
        }
        final Model m = new Model(set.stock, MONO_PAPER, set.exposure, set.printEv, set.tone);
        final double toModel = .18 / FILM_GREY;
        final double[] rgb = new double[3];
        for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
            rgb[0] = sc[r] * toModel;
            rgb[1] = sc[g] * toModel;
            rgb[2] = sc[b] * toModel;
            final float[] o = m.render(rgb);
            v[k++] = encode(o[0]);
            v[k++] = encode(o[1]);
            v[k++] = encode(o[2]);
        }
        return FilmLut.of(n, v, set.stock.brand + " " + set.stock.name);
    }

    /**
     * P369: one scene colour (linear sRGB, mid grey {@link #FILM_GREY}) through the simulation, as {@link #lutScene} renders a node
     * (null when the reference model's data cannot be read).
     */
    @Nullable
    public static float[] applyScene(Settings set, double r, double g, double b) {
        if (set.stock.spectral != null) {
            final FilmSpectral.Engine e = engine(set);
            if (e == null) return null;
            final double[] o = new double[3];
            e.render(r, g, b, o);
            return new float[]{(float) Math.max(0, Math.min(1, o[0])), (float) Math.max(0, Math.min(1, o[1])),
                    (float) Math.max(0, Math.min(1, o[2]))};
        }
        final double toModel = .18 / FILM_GREY;
        final float[] o = new Model(set.stock, MONO_PAPER, set.exposure, set.printEv, set.tone).render(
                new double[]{r * toModel, g * toModel, b * toModel});
        return new float[]{encode(o[0]), encode(o[1]), encode(o[2])};
    }

    // ───── the classic (black-and-white) stocks' sharpness and grain: FilmGrain on the photo, FilmEffects on the preview (P369)

    /** Selwyn: sigma(a) = sigma(48 um aperture) x sqrt(area(48 um) / a^2) = RMS / 1000 x 42.5 / a. */
    public static final float SELWYN = 42.5f;
    /** Our calibration of a granularity to the density noise a scan shows. */
    public static final float GRAIN_SCALE = .35f;
    /** The film sharpness blur below this (pixels) is skipped. */
    public static final float CLASSIC_BLUR_MIN_PX = .33f;

    /** The density sigma of one pixel of {@code pixelUm} for granularity {@code rms} and grains of {@code grainUm}. */
    public static float grainSigma(float rms, float grainUm, float pixelUm) {
        return rms / 1000f * SELWYN / Math.max(1f, Math.max(pixelUm, grainUm));
    }

    /** A print sees the negative's grain through its own contrast; a projected film shows its own. */
    public static float grainThrough(Settings set) {
        return set.stock.reversal() ? 1f : set.print.film ? 3.0f : 2.7f;
    }

    /** film/grain.glsl's density sigma at mid grey (green layer) of a classic stock on pixels of {@code um}, x «Зерно» x the strength. */
    public static float classicGrainSigma(Settings set, float um) {
        return grainSigma(set.stock.rms, set.stock.grainUm, um) * grainThrough(set) * GRAIN_SCALE * set.grainApplied();
    }

    /** film/grain.glsl's grain clump size (pixels) of the green layer. */
    public static float classicGrainCell(Settings set, float um) {
        return Math.max(1f, set.stock.grainUm / um);
    }

    /** The classic stock's sharpness (its MTF sigma) in pixels of {@code um}, x «Мягкость плёнки» x the strength. */
    public static float classicBlurPx(Settings set, float um) {
        return set.stock.mtfUm / um * set.softnessApplied();
    }

    private static String grainKey;
    private static FilmLut grainLut;

    /**
     * P286: the grain of the reference model on the finished picture, as a LUT over the picture's colour (the film LUT's output,
     * {@code n} a side): per channel |d out / d density| x the grain's density sigma for a 1 um pixel (FilmSpectral.grainSigma) -
     * the density noise seen through the print and the scan. Built from the film LUT's inputs (21 a side) scattered onto the
     * output grid, the empty cells filled from their neighbours. Null for our own (black-and-white) model.
     */
    @Nullable
    public static synchronized FilmLut grainLut(Settings set, int n) {
        if (set.stock.spectral == null) return null;
        final String key = set.lutKey() + "|" + n;
        if (key.equals(grainKey)) return grainLut;
        final FilmSpectral.Engine e = engine(set);
        if (e == null) return null;
        final int cells = n * n * n, side = 21;
        final double[] sum = new double[cells * 3];
        final int[] count = new int[cells];
        final double[] d = new double[3], sig = new double[3], o = new double[3], hi = new double[3], lo = new double[3];
        final double step = .02;
        for (int b = 0; b < side; b++) for (int g = 0; g < side; g++) for (int r = 0; r < side; r++) {
            e.densities(scene(r / (side - 1.0)), scene(g / (side - 1.0)), scene(b / (side - 1.0)), d);
            e.fromDensities(d, o);
            e.grainSigma(d, sig);
            final int cell = (cellIndex(o[2], n) * n + cellIndex(o[1], n)) * n + cellIndex(o[0], n);
            for (int c = 0; c < 3; c++) {
                final double keep = d[c];
                d[c] = keep + step;
                e.fromDensities(d, hi);
                d[c] = keep - step;
                e.fromDensities(d, lo);
                d[c] = keep;
                sum[cell * 3 + c] += Math.abs(hi[c] - lo[c]) / (2 * step) * sig[c];
            }
            count[cell]++;
        }
        final float[] v = new float[cells * 3];
        boolean[] known = new boolean[cells];
        for (int i = 0; i < cells; i++) if (count[i] > 0) {
            known[i] = true;
            for (int c = 0; c < 3; c++) v[i * 3 + c] = (float) (sum[i * 3 + c] / count[i]);
        }
        // fill the cells no colour of the film reaches from their known neighbours (a few growing passes)
        for (int pass = 0; pass < 3 * n; pass++) {
            final boolean[] next = known.clone();
            boolean grew = false;
            for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
                final int i = (z * n + y) * n + x;
                if (known[i]) continue;
                final float[] acc = new float[3];
                int k = 0;
                for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    final int xx = x + dx, yy = y + dy, zz = z + dz;
                    if (xx < 0 || yy < 0 || zz < 0 || xx >= n || yy >= n || zz >= n) continue;
                    final int j = (zz * n + yy) * n + xx;
                    if (!known[j]) continue;
                    for (int c = 0; c < 3; c++) acc[c] += v[j * 3 + c];
                    k++;
                }
                if (k == 0) continue;
                for (int c = 0; c < 3; c++) v[i * 3 + c] = acc[c] / k;
                next[i] = true;
                grew = true;
            }
            known = next;
            if (!grew) break;
        }
        grainKey = key;
        grainLut = FilmLut.of(n, v, set.stock.name + " grain");
        return grainLut;
    }

    private static int cellIndex(double v, int n) {
        return Math.max(0, Math.min(n - 1, (int) Math.round(v * (n - 1))));
    }

    /** One colour through a simulation (tests, previews): display-encoded in and out. */
    public static float[] apply(Settings set, float r, float g, float b) {
        if (set.stock.spectral != null) {
            final FilmSpectral.Engine e = engine(set);
            if (e == null) return new float[]{r, g, b};
            final double[] o = new double[3];
            e.render(scene(r), scene(g), scene(b), o);
            return new float[]{(float) Math.max(0, Math.min(1, o[0])), (float) Math.max(0, Math.min(1, o[1])),
                    (float) Math.max(0, Math.min(1, o[2]))};
        }
        final Model m = new Model(set.stock, MONO_PAPER, set.exposure, set.printEv, set.tone);
        final float[] o = m.render(new double[]{scene(r), scene(g), scene(b)});
        return new float[]{encode(o[0]), encode(o[1]), encode(o[2])};
    }
}
