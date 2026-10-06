package com.particlesdevs.photoncamera.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The UI language: Russian on a Russian system, English on any other (values / values-ru and {@link Lang} agree). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class LangTest {
    private static void check(boolean russian) {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals(russian ? "ru" : "en", context.getString(R.string.ui_language));
        assertEquals(russian, Lang.ru(context));
        assertEquals(russian ? "Снимок" : "Shot", Lang.t(context, "Снимок", "Shot"));
        // the app-wide form reads the app context
        try (MockedStatic<PhotonCamera> camera = mockStatic(PhotonCamera.class)) {
            camera.when(PhotonCamera::getAppContext).thenReturn(context);
            assertEquals(russian, Lang.ru());
            assertEquals(russian ? "Кадры: %d" : "Frames: %d", Lang.t("Кадры: %d", "Frames: %d"));
        }
    }

    @Test
    @Config(qualifiers = "ru")
    public void russianSystemGivesTheRussianUi() {
        check(true);
    }

    @Test
    @Config(qualifiers = "ru-rRU")
    public void russianWithRegionGivesTheRussianUi() {
        check(true);
    }

    @Test
    public void defaultSystemGivesTheEnglishUi() {
        check(false);
    }

    @Test
    @Config(qualifiers = "en-rUS")
    public void englishSystemGivesTheEnglishUi() {
        check(false);
    }

    @Test
    @Config(qualifiers = "uk-rUA")
    public void anyOtherLanguageGivesTheEnglishUi() {
        check(false);
    }

    /** Without an app context (early start) the system locale decides. */
    @Test
    @Config(qualifiers = "en-rUS")
    public void withoutResourcesAnEnglishSystemIsEnglish() {
        assertFalse(Lang.ru(null));
    }

    @Test
    @Config(qualifiers = "ru")
    public void withoutResourcesARussianSystemIsRussian() {
        assertTrue(Lang.ru(null));
    }
}
