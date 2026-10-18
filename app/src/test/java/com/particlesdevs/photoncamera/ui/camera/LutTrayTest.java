package com.particlesdevs.photoncamera.ui.camera;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.color.FilmLooks;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * SIMPLE_UI §1: the LUT pill and tray - «LUT» with none, the LUT's name in the accent while one is on; the tray lists «Нет»
 * and the five film looks (and the folder's files), a card chooses the settings' LUT, the strength slider writes the
 * settings' strength, a swipe steps round the list with a short message; Pro and the tray close each other.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w392dp-h871dp-xxhdpi")
public class LutTrayTest {
    private Context context;
    private MockedStatic<ScameraApp> camera;
    private SettingsManager manager;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        manager = new SettingsManager(context);
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        PreferenceKeys.initialise(manager);
        manager.set(SettingsManager.SCOPE_GLOBAL, FilmLut.KEY, "");
    }

    @After
    public void tearDown() {
        camera.close();
    }

    private LutTray tray(TextView pill, LinearLayout box) {
        FrameLayout root = new FrameLayout(context);
        root.addView(pill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new LutTray(pill, box, null, () -> { });
    }

    @Test
    public void thePillNamesTheLutAndTheTrayChoosesIt() {
        TextView pill = new TextView(context);
        LinearLayout box = new LinearLayout(context);
        LutTray tray = tray(pill, box);
        assertEquals("LUT", pill.getText().toString());
        assertEquals(0xFFFFFFFF, pill.getCurrentTextColor());
        assertEquals(View.GONE, box.getVisibility());
        // the pill opens the tray: «Нет» and the five looks
        pill.performClick();
        assertTrue(tray.isOpen());
        assertEquals(View.VISIBLE, box.getVisibility());
        ViewGroup row = (ViewGroup) ((ViewGroup) box.getChildAt(0)).getChildAt(0);
        assertTrue(row.getChildCount() >= 6);
        assertEquals("Нет", row.getChildAt(0).getContentDescription().toString());
        assertEquals(FilmLooks.label(context, FilmLooks.values()[3]), row.getChildAt(4).getContentDescription().toString());
        // a card chooses the settings' LUT; the pill shows its name in the accent... while the tray is open, in INK on the accent
        row.getChildAt(4).performClick();
        assertEquals(FilmLooks.values()[3], PreferenceKeys.filmLut());
        assertEquals("Кино 500", pill.getText().toString());
        assertEquals(ShadeStyle.INK, pill.getCurrentTextColor());
        tray.setOpen(false);
        assertEquals(ShadeStyle.accent(context), pill.getCurrentTextColor());
        // a swipe steps round the list with a short message
        assertEquals("LUT: Ч/Б 400", tray.step(1));
        assertEquals(FilmLooks.values()[4], PreferenceKeys.filmLut());
        tray.step(-1);
        tray.step(-1);
        tray.step(-1);
        tray.step(-1);
        assertEquals("LUT: Нет", tray.step(-1));
        assertEquals("", PreferenceKeys.filmLut());
        assertEquals("LUT", pill.getText().toString());
    }

    @Test
    public void theStrengthIsTheSettingsStrengthAndProClosesTheTray() {
        TextView pill = new TextView(context);
        LinearLayout box = new LinearLayout(context);
        LutTray tray = tray(pill, box);
        boolean[] proClosed = {false};
        tray.setOnOpen(() -> proClosed[0] = true);
        tray.setOpen(true);
        assertTrue("opening the tray closes Pro", proClosed[0]);
        ViewGroup strengthRow = (ViewGroup) box.getChildAt(1);
        android.widget.SeekBar seek = (android.widget.SeekBar) strengthRow.getChildAt(1);
        assertEquals(100, seek.getProgress());
        assertEquals("100 %", ((TextView) strengthRow.getChildAt(2)).getText().toString());
        // a user's move writes the settings' strength
        shadowOf(seek).getOnSeekBarChangeListener().onProgressChanged(seek, 40, true);
        assertEquals(.4f, PreferenceKeys.filmLutStrength(), 1e-6f);
        // choosing a LUT at strength 0 brings the strength back to 100 %
        manager.set(SettingsManager.SCOPE_GLOBAL, FilmLut.STRENGTH_KEY, "0");
        tray.step(1);
        assertEquals(1f, PreferenceKeys.filmLutStrength(), 1e-6f);
    }

    /** P228: the lens filters' row under the strength; a card is the settings' filter, the pill names it after the LUT. */
    @Test
    public void theFilterRowChoosesTheLensFilter() {
        TextView pill = new TextView(context);
        LinearLayout box = new LinearLayout(context);
        LutTray tray = tray(pill, box);
        tray.setOpen(true);
        ViewGroup filters = box.findViewWithTag("lut_tray_filters");
        assertNotNull(filters);
        assertEquals("«Нет» and 12 filters", 13, filters.getChildCount());
        box.findViewWithTag("lut_tray_filter_bpm").performClick();
        assertEquals("bpm", PreferenceKeys.lensFilter());
        assertEquals(.5f, PreferenceKeys.lensFilterStrength(), 1e-6f);
        assertEquals("LUT · Pro-Mist", pill.getText().toString());
        tray.setOpen(false);
        assertEquals("a filter alone lights the pill", ShadeStyle.accent(context), pill.getCurrentTextColor());
        tray.setOpen(true);
        box.findViewWithTag("lut_tray_filter_").performClick();
        assertEquals("", PreferenceKeys.lensFilter());
        assertEquals("LUT", pill.getText().toString());
    }

    private static org.robolectric.shadows.ShadowSeekBar shadowOf(android.widget.SeekBar seek) {
        return org.robolectric.Shadows.shadowOf(seek);
    }
}
