package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mockStatic;

/**
 * «Смещение центра экспозиции» and «Вес слоя светов» act on a shot with Bento headroom too (owner, 2026-10-21: «проверь что все
 * настройки рабочие»): WhyFusion's headroom centre and highlight weight follow them, exactly P291's 0.75 / 0.4 at their defaults.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class WhyHeadroomWeightsTest {
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before public void setUp() {
        final Context context = RuntimeEnvironment.getApplication();
        final SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        PreferenceKeys.initialise(manager);
    }

    @After public void tearDown() {
        if (camera != null) camera.close();
    }

    @Test public void theMenuWeightsMoveTheHeadroomWeights() {
        // the defaults: P291's values to the bit (the pixels of a default shot unchanged)
        assertEquals(0.75f, WhyTone.headroomCenter(), 0f);
        assertEquals(0.4f, WhyTone.headroomHlWeight(), 0f);
        prefs.edit().putString("pref_scam_hybrid_why_weight_center", "0.7").putString("pref_scam_hybrid_why_weight_hl", "1.2").commit();
        assertEquals(0.85f, WhyTone.headroomCenter(), 1e-6f);
        assertEquals(0.6f, WhyTone.headroomHlWeight(), 1e-6f);
        // the centre stays inside the menu's own range
        prefs.edit().putString("pref_scam_hybrid_why_weight_center", "0.9").putString("pref_scam_hybrid_why_weight_hl", "0").commit();
        assertEquals(0.9f, WhyTone.headroomCenter(), 0f);
        assertEquals(0f, WhyTone.headroomHlWeight(), 0f);
    }

    /**
     * «Ограничение усиления по шуму матрицы» is the gain clamp only: the ISO night threshold stays ISO 1, what WhyAe made of the
     * row's value (always below 1) since P63, whatever the row says.
     */
    @Test public void theNoiseClampRowIsTheClampOnly() {
        assertEquals(0.05f, WhyTone.settings().noiseMax, 0f);
        assertEquals(1f, WhyTone.settings().nightThresh, 0f);
        prefs.edit().putString("pref_scam_hybrid_why_night_thresh", "0.15").commit();
        assertEquals(0.15f, WhyTone.settings().noiseMax, 1e-6f);
        assertEquals(1f, WhyTone.settings().nightThresh, 0f);
    }
}
