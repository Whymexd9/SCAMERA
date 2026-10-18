package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.SharedPreferences;

import com.particlesdevs.photoncamera.processing.color.FilmLut;

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
}
