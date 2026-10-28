package com.particlesdevs.photoncamera.settings;

import android.content.SharedPreferences;

import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.FilmSim;

/**
 * SIMPLE_UI §4 «Стиль снимка» (the concept's style page and «Стиль» tile): Обычный, Чёрно-белый or Плёнка - a view over the
 * two real settings, the black-and-white filter (pref_scam_hybrid_colour_mono, off / neutral / colour filters) and the film
 * LUT (pref_film_lut); P369: and the film simulation (pref_film_sim_stock), whose print replaces our colour - the filter and our
 * grading included - so a stock is «Плёнка» and the other styles take it off. Leaving a style remembers its choice, so coming
 * back restores the same filter, LUT or stock (with the LUT it had).
 */
public final class PhotoStyle {
    public static final int NORMAL = 0, MONO = 1, FILM = 2;
    public static final String MONO_KEY = "pref_scam_hybrid_colour_mono";
    /** The last black-and-white filter and film LUT (restored when their style is chosen again). */
    static final String LAST_MONO = "ui_style_last_mono", LAST_LUT = "ui_style_last_lut";
    /** P369: the last film stock and the LUT that was on with it. */
    static final String LAST_STOCK = "ui_style_last_stock", LAST_STOCK_LUT = "ui_style_last_stock_lut";
    static final String DEFAULT_LUT = "scam:warm400";

    private PhotoStyle() {}

    /** A film stock first (its print replaces the filter), then black and white (the last step of our colour), then a LUT, else normal. */
    public static int current(SharedPreferences prefs) {
        if (stock(prefs) != null) return FILM;
        String mono = prefs.getString(MONO_KEY, "off");
        if (mono != null && !"off".equals(mono)) return MONO;
        String lut = prefs.getString(FilmLut.KEY, "");
        return lut != null && !lut.isEmpty() ? FILM : NORMAL;
    }

    public static void apply(SharedPreferences prefs, int style) {
        String mono = prefs.getString(MONO_KEY, "off"), lut = prefs.getString(FilmLut.KEY, "");
        final String stock = stock(prefs);
        SharedPreferences.Editor e = prefs.edit();
        if (mono != null && !"off".equals(mono)) e.putString(LAST_MONO, mono);
        if (lut != null && !lut.isEmpty()) e.putString(LAST_LUT, lut);
        if (stock != null) {
            e.putString(LAST_STOCK, stock);
            e.putString(LAST_STOCK_LUT, lut == null ? "" : lut);
        } else if (lut != null && !lut.isEmpty()) {
            e.remove(LAST_STOCK); // the last film was a LUT alone: «Плёнка» brings that back
        }
        final String lastStock = stock != null ? stock : lut != null && !lut.isEmpty() ? null : prefs.getString(LAST_STOCK, "");
        switch (style) {
            case MONO:
                e.putString(MONO_KEY, prefs.getString(LAST_MONO, "neutral"));
                e.putString(FilmLut.KEY, "");
                e.putString(FilmSim.STOCK_KEY, "");
                break;
            case FILM:
                e.putString(MONO_KEY, "off");
                if (lastStock != null && FilmSim.stock(lastStock) != null) { // the film that was on, with its LUT
                    e.putString(FilmSim.STOCK_KEY, lastStock);
                    e.putString(FilmLut.KEY, stock != null ? (lut == null ? "" : lut) : prefs.getString(LAST_STOCK_LUT, ""));
                } else {
                    e.putString(FilmLut.KEY, prefs.getString(LAST_LUT, DEFAULT_LUT));
                }
                break;
            default:
                e.putString(MONO_KEY, "off");
                e.putString(FilmLut.KEY, "");
                e.putString(FilmSim.STOCK_KEY, "");
        }
        e.apply();
        FilmLut.forget();
    }

    /** The stock in effect (FilmSim.current: a known stock at a strength above 0), or null. */
    private static String stock(SharedPreferences prefs) {
        final String id = prefs.getString(FilmSim.STOCK_KEY, "");
        if (id == null || id.trim().isEmpty() || FilmSim.stock(id) == null) return null;
        return PreferenceNumber.read(prefs.getAll().get(FilmSim.STRENGTH_KEY), 100) > 0 ? id : null;
    }

    /** The next style (the shade's «Стиль» tile cycles). */
    public static int next(int style) {
        return (style + 1) % 3;
    }

    public static String name(android.content.Context c, int style) {
        switch (style) {
            case MONO: return com.particlesdevs.photoncamera.util.Lang.t(c, "Ч/б", "B&W");
            case FILM: return com.particlesdevs.photoncamera.util.Lang.t(c, "Плёнка", "Film");
            default: return com.particlesdevs.photoncamera.util.Lang.t(c, "Обычный", "Normal");
        }
    }
}
