package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.SharedPreferences;

import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.FilmSim;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

/** SIMPLE_UI §4 «Стиль снимка»: Обычный / Чёрно-белый / Плёнка over the black-and-white filter and the film LUT. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class PhotoStyleTest {
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("photo_style_test", 0);
        prefs.edit().clear().commit();
    }

    @Test
    public void styleFollowsTheTwoSettings() {
        assertEquals(PhotoStyle.NORMAL, PhotoStyle.current(prefs));
        prefs.edit().putString(FilmLut.KEY, "scam:cine500").commit();
        assertEquals(PhotoStyle.FILM, PhotoStyle.current(prefs));
        prefs.edit().putString(PhotoStyle.MONO_KEY, "yellow").commit();
        assertEquals("black and white wins: it is the last step of the colour", PhotoStyle.MONO, PhotoStyle.current(prefs));
    }

    @Test
    public void choosingAStyleRemembersTheOthersChoice() {
        prefs.edit().putString(FilmLut.KEY, "scam:cine500").commit();
        PhotoStyle.apply(prefs, PhotoStyle.MONO);
        assertEquals("neutral", prefs.getString(PhotoStyle.MONO_KEY, ""));
        assertEquals("", prefs.getString(FilmLut.KEY, "x"));
        prefs.edit().putString(PhotoStyle.MONO_KEY, "red").commit();
        PhotoStyle.apply(prefs, PhotoStyle.FILM);
        assertEquals("the LUT comes back", "scam:cine500", prefs.getString(FilmLut.KEY, ""));
        assertEquals("off", prefs.getString(PhotoStyle.MONO_KEY, ""));
        PhotoStyle.apply(prefs, PhotoStyle.MONO);
        assertEquals("the filter comes back", "red", prefs.getString(PhotoStyle.MONO_KEY, ""));
        PhotoStyle.apply(prefs, PhotoStyle.NORMAL);
        assertEquals(PhotoStyle.NORMAL, PhotoStyle.current(prefs));
        prefs.edit().clear().commit();
        PhotoStyle.apply(prefs, PhotoStyle.FILM);
        assertEquals("a first film: the warm look", "scam:warm400", prefs.getString(FilmLut.KEY, ""));
        assertEquals(PhotoStyle.MONO, PhotoStyle.next(PhotoStyle.NORMAL));
        assertEquals(PhotoStyle.NORMAL, PhotoStyle.next(PhotoStyle.FILM));
    }

    /** P369 (film-audit settings-2): a film stock is «Плёнка» - its print replaces the filter -, and the other styles take it off. */
    @Test
    public void aFilmStockIsTheFilmStyle() {
        final String portra = FilmSim.STOCKS[0].id;
        prefs.edit().putString(FilmSim.STOCK_KEY, portra).putString(PhotoStyle.MONO_KEY, "red").commit();
        assertEquals("the stock's print replaces the filter", PhotoStyle.FILM, PhotoStyle.current(prefs));
        prefs.edit().putString(FilmSim.STRENGTH_KEY, "0").commit();
        assertEquals("a stock at 0 % is no film", PhotoStyle.MONO, PhotoStyle.current(prefs));
        prefs.edit().remove(FilmSim.STRENGTH_KEY).putString(PhotoStyle.MONO_KEY, "off").putString(FilmLut.KEY, "scam:cine500").commit();
        PhotoStyle.apply(prefs, PhotoStyle.NORMAL);
        assertEquals("«Обычный» takes the stock off", "", prefs.getString(FilmSim.STOCK_KEY, "x"));
        assertEquals(PhotoStyle.NORMAL, PhotoStyle.current(prefs));
        PhotoStyle.apply(prefs, PhotoStyle.MONO);
        assertEquals(PhotoStyle.MONO, PhotoStyle.current(prefs));
        assertEquals("", prefs.getString(FilmSim.STOCK_KEY, "x"));
        PhotoStyle.apply(prefs, PhotoStyle.FILM);
        assertEquals("«Плёнка» brings the stock back", portra, prefs.getString(FilmSim.STOCK_KEY, ""));
        assertEquals("with the LUT it had", "scam:cine500", prefs.getString(FilmLut.KEY, ""));
        assertEquals("off", prefs.getString(PhotoStyle.MONO_KEY, ""));
        // a stock chosen without a LUT comes back without one (no Warm 400 stacked on the film)
        prefs.edit().putString(FilmLut.KEY, "").commit();
        PhotoStyle.apply(prefs, PhotoStyle.NORMAL);
        PhotoStyle.apply(prefs, PhotoStyle.FILM);
        assertEquals(portra, prefs.getString(FilmSim.STOCK_KEY, ""));
        assertEquals("", prefs.getString(FilmLut.KEY, "x"));
        // the last film a LUT alone: «Плёнка» brings the LUT, not an older stock
        prefs.edit().putString(FilmSim.STOCK_KEY, "").putString(FilmLut.KEY, "scam:cine500").commit();
        PhotoStyle.apply(prefs, PhotoStyle.NORMAL);
        PhotoStyle.apply(prefs, PhotoStyle.FILM);
        assertEquals("", prefs.getString(FilmSim.STOCK_KEY, "x"));
        assertEquals("scam:cine500", prefs.getString(FilmLut.KEY, ""));
    }
}
