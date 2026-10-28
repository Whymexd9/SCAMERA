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
        // the settings keys resolve their names through it: never null under the mock (a Key enum first loaded here kept
        // null names for every later test of the JVM)
        camera.when(() -> ScameraApp.getStringStatic(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> org.robolectric.RuntimeEnvironment.getApplication().getString(i.<Integer>getArgument(0)));
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        PreferenceKeys.initialise(manager);
        manager.set(SettingsManager.SCOPE_GLOBAL, FilmLut.KEY, "");
        LutTray.lastTab = LutTray.TAB_LUT;
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
        ViewGroup row = box.findViewWithTag("lut_tray_luts");
        assertTrue(row.getChildCount() >= 6);
        assertEquals("Нет", row.getChildAt(0).getContentDescription().toString());
        assertEquals(FilmLooks.label(context, FilmLooks.values()[3]), row.getChildAt(4).getContentDescription().toString());
        // a card chooses the settings' LUT; the pill shows its name in the accent... while the tray is open, in INK on the accent
        row.getChildAt(4).performClick();
        assertEquals(FilmLooks.values()[3], PreferenceKeys.filmLut());
        assertEquals("Кино 500", pill.getText().toString());
        assertEquals(ShadeStyle.INK, pill.getCurrentTextColor());
        tray.setOpen(false);
        settle(); // the pill's colours cross-fade (TrayMotion): the state after the fade
        assertEquals(ShadeStyle.accent(context), pill.getCurrentTextColor());
        assertEquals("the tray hides once it has left", View.GONE, box.getVisibility());
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
        ViewGroup strengthRow = box.findViewWithTag("lut_tray_strength");
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
        assertEquals("«Нет» and the 4 filters", 5, filters.getChildCount());
        box.findViewWithTag("lut_tray_filter_bpm").performClick();
        assertEquals("bpm", PreferenceKeys.lensFilter());
        assertEquals(.5f, PreferenceKeys.lensFilterStrength(), 1e-6f);
        assertEquals("LUT · Pro-Mist", pill.getText().toString());
        tray.setOpen(false);
        settle();
        assertEquals("a filter alone lights the pill", ShadeStyle.accent(context), pill.getCurrentTextColor());
        tray.setOpen(true);
        box.findViewWithTag("lut_tray_filter_").performClick();
        assertEquals("", PreferenceKeys.lensFilter());
        assertEquals("LUT", pill.getText().toString());
    }

    /** Owner, 2026-10-28: the sections are tabs - one page shows, the tab chosen last opens again. */
    @Test
    public void theTabsShowOnePageAndAreRemembered() {
        TextView pill = new TextView(context);
        LinearLayout box = new LinearLayout(context);
        LutTray tray = tray(pill, box);
        tray.setOpen(true);
        assertEquals(View.VISIBLE, box.findViewWithTag("lut_tray_page_lut").getVisibility());
        for (String t : new String[]{"filter", "film", "colour"}) assertEquals(t, View.GONE, box.findViewWithTag("lut_tray_page_" + t).getVisibility());
        assertTrue(box.findViewWithTag("lut_tray_tab_lut").isActivated());
        box.findViewWithTag("lut_tray_tab_film").performClick();
        settle();
        assertEquals(View.VISIBLE, box.findViewWithTag("lut_tray_page_film").getVisibility());
        assertEquals(View.GONE, box.findViewWithTag("lut_tray_page_lut").getVisibility());
        assertTrue(box.findViewWithTag("lut_tray_tab_film").isActivated());
        assertFalse(box.findViewWithTag("lut_tray_tab_lut").isActivated());
        tray.setOpen(false);
        settle();
        tray.setOpen(true);
        assertEquals("the film tab again", View.VISIBLE, box.findViewWithTag("lut_tray_page_film").getVisibility());
        // the colour tab: no settings page of its own behind «Все ›»
        box.findViewWithTag("lut_tray_tab_colour").performClick();
        assertEquals(View.INVISIBLE, box.findViewWithTag("lut_tray_all").getVisibility());
        assertEquals(View.VISIBLE, box.findViewWithTag("lut_tray_warmth").getVisibility());
    }

    /** The chosen film's parameters in its page, written as the film page writes them; none without a film, no print for a slide. */
    @Test
    public void theFilmPageAdjustsTheChosenFilm() {
        TextView pill = new TextView(context);
        LinearLayout box = new LinearLayout(context);
        LutTray tray = tray(pill, box);
        tray.setOpen(true);
        box.findViewWithTag("lut_tray_tab_film").performClick();
        View adjust = box.findViewWithTag("lut_tray_film_adjust");
        assertEquals("no film: nothing to adjust", View.GONE, adjust.getVisibility());
        box.findViewWithTag("lut_tray_film_kodak_portra_400").performClick();
        assertEquals(View.VISIBLE, adjust.getVisibility());
        for (String p : new String[]{"strength", "grain", "halation", "softness", "exposure", "contrast", "print"})
            assertNotNull(p, box.findViewWithTag("lut_tray_film_adjust_param_" + p));
        android.widget.SeekBar seek = box.findViewWithTag("lut_tray_film_adjust_seek");
        assertEquals("the strength first: 0..100 at 100", 100, seek.getMax());
        assertEquals(100, seek.getProgress());
        box.findViewWithTag("lut_tray_film_adjust_param_grain").performClick();
        assertEquals(200, seek.getMax());
        assertEquals(100, seek.getProgress());
        seek.setProgress(150);
        shadowOf(seek).getOnSeekBarChangeListener().onProgressChanged(seek, 150, true);
        shadowOf(seek).getOnSeekBarChangeListener().onStopTrackingTouch(seek);
        assertEquals("150", manager.getString(SettingsManager.SCOPE_GLOBAL, com.particlesdevs.photoncamera.processing.color.FilmSim.GRAIN_KEY, ""));
        assertEquals("150 %", ((TextView) box.findViewWithTag("lut_tray_film_adjust_value")).getText().toString());
        box.findViewWithTag("lut_tray_film_adjust_param_exposure").performClick();
        assertEquals("-2 .. +2 EV in 0.1", 40, seek.getMax());
        seek.setProgress(25);
        shadowOf(seek).getOnSeekBarChangeListener().onProgressChanged(seek, 25, true);
        shadowOf(seek).getOnSeekBarChangeListener().onStopTrackingTouch(seek);
        assertEquals("0.50", manager.getString(SettingsManager.SCOPE_GLOBAL, com.particlesdevs.photoncamera.processing.color.FilmSim.EXPOSURE_KEY, ""));
        assertEquals("+0.5 EV", ((TextView) box.findViewWithTag("lut_tray_film_adjust_value")).getText().toString());
        // a slide is not printed
        String slide = null;
        for (com.particlesdevs.photoncamera.processing.color.FilmSim.Stock st : com.particlesdevs.photoncamera.processing.color.FilmSim.STOCKS)
            if (st.type == com.particlesdevs.photoncamera.processing.color.FilmSim.SLIDE) { slide = st.id; break; }
        assertNotNull(slide);
        box.findViewWithTag("lut_tray_film_" + slide).performClick();
        assertNull(box.findViewWithTag("lut_tray_film_adjust_param_print"));
        assertNotNull(box.findViewWithTag("lut_tray_film_adjust_param_grain"));
        box.findViewWithTag("lut_tray_film_").performClick();
        assertEquals(View.GONE, adjust.getVisibility());
    }

    /** The chosen filter's strength and fine controls in its page, stored as the filter page stores them. */
    @Test
    public void theFilterPageAdjustsTheChosenFilter() {
        TextView pill = new TextView(context);
        LinearLayout box = new LinearLayout(context);
        LutTray tray = tray(pill, box);
        tray.setOpen(true);
        View adjust = box.findViewWithTag("lut_tray_filter_adjust");
        assertEquals(View.GONE, adjust.getVisibility());
        box.findViewWithTag("lut_tray_filter_bpm").performClick();
        assertEquals(View.VISIBLE, adjust.getVisibility());
        android.widget.SeekBar seek = box.findViewWithTag("lut_tray_filter_adjust_seek");
        assertEquals("the strength at its 50 %", 50, seek.getProgress());
        shadowOf(seek).getOnSeekBarChangeListener().onProgressChanged(seek, 80, true);
        assertEquals("written as it moves", .8f, PreferenceKeys.lensFilterStrength(), 1e-6f);
        View radius = box.findViewWithTag("lut_tray_filter_adjust_param_tune_" + com.particlesdevs.photoncamera.processing.color.LensFilters.T_RADIUS);
        assertNotNull("Black Pro-Mist's fine controls", radius);
        radius.performClick();
        assertEquals(50, seek.getProgress());
        shadowOf(seek).getOnSeekBarChangeListener().onProgressChanged(seek, 70, true);
        assertEquals(.7f, PreferenceKeys.lensFilterTune("bpm").radius, 1e-6f);
        assertEquals("the other controls as designed", .5f, PreferenceKeys.lensFilterTune("bpm").knee, 1e-6f);
    }

    /** Owner, 2026-10-28: a long press on a parameter shows what it does, under the page; a tap on it closes it. */
    @Test
    public void aLongPressDescribesTheParameter() {
        TextView pill = new TextView(context);
        LinearLayout box = new LinearLayout(context);
        LutTray tray = tray(pill, box);
        tray.setOpen(true);
        TextView hint = box.findViewWithTag("lut_tray_hint");
        assertEquals(View.GONE, hint.getVisibility());
        ViewGroup strengthRow = box.findViewWithTag("lut_tray_strength");
        assertTrue(strengthRow.getChildAt(0).performLongClick());
        assertEquals(View.VISIBLE, hint.getVisibility());
        assertTrue(hint.getText().toString().contains(com.particlesdevs.photoncamera.settings.ParamHelp.of(context, FilmLut.STRENGTH_KEY)));
        box.findViewWithTag("lut_tray_film_kodak_portra_400").performClick();
        assertTrue(box.findViewWithTag("lut_tray_film_adjust_param_halation").performLongClick());
        assertTrue(hint.getText().toString().startsWith("Ореол"));
        assertTrue(hint.getText().toString().contains(com.particlesdevs.photoncamera.settings.ParamHelp.of(context,
                com.particlesdevs.photoncamera.processing.color.FilmSim.HALATION_KEY)));
        hint.performClick();
        settle();
        assertEquals(View.GONE, hint.getVisibility());
        // every film parameter of the tray has a description
        for (TrayAdjust.Param p : LutTray.filmParams(context, com.particlesdevs.photoncamera.processing.color.FilmSim.stock("kodak_portra_400")))
            assertNotNull(p.id, p.help);
        for (TrayAdjust.Param p : LutTray.filterParams(context, "bpm")) assertNotNull(p.id, p.help);
    }

    /** The tray's and the pill's animations run to their end (Robolectric's paused main looper). */
    private static void settle() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static org.robolectric.shadows.ShadowSeekBar shadowOf(android.widget.SeekBar seek) {
        return org.robolectric.Shadows.shadowOf(seek);
    }

    /**
     * Owner's video 2026-10-28 («мигает при открытии и закрытии»): camera_container animates its children's visibility changes
     * (animateLayoutChanges) - its own 300 ms fade ran over the tray's: the closed tray came back in full and faded again, the
     * opening pane faded in empty. The tray's own show / hide never starts the parent's transition; the parent keeps it.
     */
    @Test
    public void openingAndClosingSkipTheParentsLayoutTransition() {
        // the parent's transition as ViewGroup calls it on a child's visibility change: which fades it would run, per child
        final java.util.List<String> fades = new java.util.ArrayList<>();
        android.animation.LayoutTransition transition = new android.animation.LayoutTransition() {
            @Override public void showChild(ViewGroup parent, View child, int oldVisibility) {
                if (isTransitionTypeEnabled(APPEARING)) fades.add("appear:" + child.getTag());
                super.showChild(parent, child, oldVisibility);
            }
            @Override public void hideChild(ViewGroup parent, View child, int newVisibility) {
                if (isTransitionTypeEnabled(DISAPPEARING)) fades.add("disappear:" + child.getTag());
                super.hideChild(parent, child, newVisibility);
            }
        };
        android.app.Activity activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        FrameLayout root = new FrameLayout(context);
        root.setLayoutTransition(transition);
        TextView pill = new TextView(context);
        pill.setTag("pill");
        LinearLayout box = new LinearLayout(context);
        box.setTag("tray");
        box.setVisibility(View.GONE);
        root.addView(pill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        activity.setContentView(root);
        LutTray tray = new LutTray(pill, box, null, () -> { });
        tray.setOpen(true);
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(View.VISIBLE, box.getVisibility());
        tray.setOpen(false);
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(View.GONE, box.getVisibility());
        assertTrue("the parent ran no fade of its own on the tray: " + fades, fades.isEmpty());
        assertSame("the parent keeps its transition", transition, root.getLayoutTransition());
        assertTrue(transition.isTransitionTypeEnabled(android.animation.LayoutTransition.APPEARING));
        assertTrue(transition.isTransitionTypeEnabled(android.animation.LayoutTransition.DISAPPEARING));
        assertEquals("closed and at rest: shown in full next time", 1f, box.getAlpha(), 0f);
        // the parent's other children keep its fades
        pill.setVisibility(View.GONE);
        assertEquals(java.util.Collections.singletonList("disappear:pill"), fades);
    }
}
