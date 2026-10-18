package com.particlesdevs.photoncamera.settings;

import android.content.SharedPreferences;

import com.particlesdevs.photoncamera.processing.color.FilmLut;

/**
 * SIMPLE_UI §4 «Стиль снимка» (the concept's style page and «Стиль» tile): Обычный, Чёрно-белый or Плёнка - a view over the
 * two real settings, the black-and-white filter (pref_scam_hybrid_colour_mono, off / neutral / colour filters) and the film
 * LUT (pref_film_lut). Leaving a style remembers its choice, so coming back restores the same filter or LUT.
 */
public final class PhotoStyle {
    public static final int NORMAL = 0, MONO = 1, FILM = 2;
    public static final String MONO_KEY = "pref_scam_hybrid_colour_mono";
    /** The last black-and-white filter and film LUT (restored when their style is chosen again). */
    static final String LAST_MONO = "ui_style_last_mono", LAST_LUT = "ui_style_last_lut";
    static final String DEFAULT_LUT = "scam:warm400";

    private PhotoStyle() {}

    /** Black and white wins (it is the last step of the colour), then a LUT, else normal. */
    public static int current(SharedPreferences prefs) {
        String mono = prefs.getString(MONO_KEY, "off");
        if (mono != null && !"off".equals(mono)) return MONO;
        String lut = prefs.getString(FilmLut.KEY, "");
        return lut != null && !lut.isEmpty() ? FILM : NORMAL;
    }

    public static void apply(SharedPreferences prefs, int style) {
        String mono = prefs.getString(MONO_KEY, "off"), lut = prefs.getString(FilmLut.KEY, "");
        SharedPreferences.Editor e = prefs.edit();
        if (mono != null && !"off".equals(mono)) e.putString(LAST_MONO, mono);
        if (lut != null && !lut.isEmpty()) e.putString(LAST_LUT, lut);
        switch (style) {
            case MONO:
                e.putString(MONO_KEY, prefs.getString(LAST_MONO, "neutral"));
                e.putString(FilmLut.KEY, "");
                break;
            case FILM:
                e.putString(MONO_KEY, "off");
                e.putString(FilmLut.KEY, prefs.getString(LAST_LUT, DEFAULT_LUT));
                break;
            default:
                e.putString(MONO_KEY, "off");
                e.putString(FilmLut.KEY, "");
        }
        e.apply();
        FilmLut.forget();
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
