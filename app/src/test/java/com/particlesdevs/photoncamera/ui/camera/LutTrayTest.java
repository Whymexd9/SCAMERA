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
import com.particlesdevs.photoncamera.processing.color.FilmSim;
import com.particlesdevs.photoncamera.processing.color.LensFilters;
import com.particlesdevs.photoncamera.settings.ParamHelp;
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

    /**
     * P381 (owner, 2026-10-28: the film page's «Печать» and «Формат» circled): the film's print and format in its tray page as
     * choices, written as the film page writes them (the print's id in PRINT_KEY, the format's in FORMAT_KEY) and taken by the
     * model; the print only for a printed colour negative, the format for every stock; the choices take the slider's place at
     * its height, so the tray is no taller for them.
     */
    @Test
    public void thePrintAndTheFormatAreTheFilmPagesChoices() {
        TextView pill = new TextView(context);
        LinearLayout box = new LinearLayout(context);
        LutTray tray = tray(pill, box);
        tray.setOpen(true);
        box.findViewWithTag("lut_tray_tab_film").performClick();
        box.findViewWithTag("lut_tray_film_kodak_portra_400").performClick();
        settle();
        View choices = box.findViewWithTag("lut_tray_film_adjust_choices");
        android.widget.SeekBar seek = box.findViewWithTag("lut_tray_film_adjust_seek");
        View sliderRow = (View) seek.getParent();
        assertEquals("the strength's slider first", View.GONE, choices.getVisibility());
        final int sliderHeight = height(box);
        assertTrue(sliderHeight > 0);
        // «Печать»: the page's papers and print films in its order, the stock maker's own (Portra Endura) chosen by default
        TextView print = box.findViewWithTag("lut_tray_film_adjust_param_paper");
        assertEquals("Печать", print.getText().toString());
        assertEquals("the print's exposure has the page's name", "Экспозиция печати",
                ((TextView) box.findViewWithTag("lut_tray_film_adjust_param_print")).getText().toString());
        print.performClick();
        settle();
        assertEquals(View.VISIBLE, choices.getVisibility());
        assertEquals("the slider's row keeps the line's height", View.INVISIBLE, sliderRow.getVisibility());
        ViewGroup options = box.findViewWithTag("lut_tray_film_adjust_options");
        assertEquals(FilmSim.PRINTS.length, options.getChildCount());
        for (int i = 0; i < FilmSim.PRINTS.length; i++)
            assertEquals(FilmSim.PRINTS[i].name, ((TextView) options.getChildAt(i)).getText().toString());
        final int endura = printIndex("kodak_portra_endura"), vision = printIndex("kodak_2383");
        assertTrue(options.getChildAt(endura).isActivated());
        assertEquals("nothing stored until chosen", "", manager.getString(SettingsManager.SCOPE_GLOBAL, FilmSim.PRINT_KEY, ""));
        assertEquals("the tray is no taller with the print's choices", sliderHeight, height(box));
        options.getChildAt(vision).performClick();
        assertEquals("the page's key and value", "kodak_2383", manager.getString(SettingsManager.SCOPE_GLOBAL, FilmSim.PRINT_KEY, ""));
        assertEquals("kodak_2383", FilmSim.current().print.id);
        assertTrue(options.getChildAt(vision).isActivated());
        assertFalse(options.getChildAt(endura).isActivated());
        // «Формат»: 35 mm by default, written as the page writes it
        TextView format = box.findViewWithTag("lut_tray_film_adjust_param_format");
        assertEquals("Формат", format.getText().toString());
        format.performClick();
        settle();
        assertEquals(FilmSim.FORMATS.length, options.getChildCount());
        assertEquals("Выкл.", ((TextView) options.getChildAt(0)).getText().toString());
        assertTrue(options.getChildAt(formatIndex("135")).isActivated());
        options.getChildAt(formatIndex("67")).performClick();
        assertEquals("67", manager.getString(SettingsManager.SCOPE_GLOBAL, FilmSim.FORMAT_KEY, ""));
        assertEquals(70f, FilmSim.current().format.longMm, 0f);
        assertEquals("the tray is no taller with the format's choices", sliderHeight, height(box));
        // a slider again
        box.findViewWithTag("lut_tray_film_adjust_param_grain").performClick();
        settle();
        assertEquals(View.GONE, choices.getVisibility());
        assertEquals(View.VISIBLE, sliderRow.getVisibility());
        assertEquals(200, seek.getMax());
        // a choice chosen last stays chosen for another stock, at that stock's value
        box.findViewWithTag("lut_tray_film_adjust_param_paper").performClick();
        box.findViewWithTag("lut_tray_film_kodak_vision3_500t").performClick();
        assertEquals(View.VISIBLE, choices.getVisibility());
        assertTrue("the chosen print stays chosen", options.getChildAt(vision).isActivated());
        // what acts on which stock, as on the film page: a black-and-white negative is printed (the paper, its exposure, the
        // format: its grain follows the paper); a slide and a black-and-white reversal film are not (the format only)
        for (String[] st : new String[][]{{"trix400", "print"}, {firstOfType(FilmSim.SLIDE), null}, {"trix7266", null}}) {
            box.findViewWithTag("lut_tray_film_" + st[0]).performClick();
            assertEquals(st[0], st[1] != null, box.findViewWithTag("lut_tray_film_adjust_param_paper") != null);
            assertNotNull(st[0], box.findViewWithTag("lut_tray_film_adjust_param_format"));
            assertEquals(st[0], st[1] != null, box.findViewWithTag("lut_tray_film_adjust_param_print") != null);
        }
        // a long press: what it does
        TextView hint = box.findViewWithTag("lut_tray_hint");
        assertTrue(box.findViewWithTag("lut_tray_film_adjust_param_format").performLongClick());
        assertTrue(hint.getText().toString().startsWith("Формат"));
        assertTrue(hint.getText().toString().contains(ParamHelp.of(context, FilmSim.FORMAT_KEY)));
        assertNotNull(ParamHelp.of(context, FilmSim.PRINT_KEY));
    }

    /**
     * P380 (owner, 2026-10-28: the pair with Unspektrawesome's SPEKTRA, its recipe a Pro-Mist 2.0 with the DIR couplers at 0): the
     * film page's new rows in the tray's chip row (P381's, with «Печать» and «Формат») - «Диффузия» a choice (Нет and the four
     * families), «Сила фильтра» the grade 0 .. 2 stored x 100, «Цветоразделение (DIR)» 0 .. 100 % for the measured colour stocks -
     * written as the page writes them and taken by the model; each with its description; the tray no taller.
     */
    @Test
    public void theDiffusionAndTheCouplersAreTheFilmPagesRows() {
        TextView pill = new TextView(context);
        LinearLayout box = new LinearLayout(context);
        LutTray tray = tray(pill, box);
        tray.setOpen(true);
        box.findViewWithTag("lut_tray_tab_film").performClick();
        box.findViewWithTag("lut_tray_film_kodak_portra_160").performClick();
        settle();
        final int sliderHeight = height(box);
        View choices = box.findViewWithTag("lut_tray_film_adjust_choices");
        android.widget.SeekBar seek = box.findViewWithTag("lut_tray_film_adjust_seek");
        TextView value = box.findViewWithTag("lut_tray_film_adjust_value");
        // «Диффузия»: none by default, the four families in spektrafilm's order
        TextView diffusion = box.findViewWithTag("lut_tray_film_adjust_param_diffusion");
        assertEquals("Диффузия", diffusion.getText().toString());
        diffusion.performClick();
        settle();
        assertEquals(View.VISIBLE, choices.getVisibility());
        ViewGroup options = box.findViewWithTag("lut_tray_film_adjust_options");
        final String[] names = {"Нет", "Glimmerglass", "Black Pro-Mist", "Pro-Mist", "CineBloom"};
        assertEquals(names.length, options.getChildCount());
        for (int i = 0; i < names.length; i++) assertEquals(names[i], ((TextView) options.getChildAt(i)).getText().toString());
        assertTrue(options.getChildAt(0).isActivated());
        assertEquals("nothing stored until chosen", "", manager.getString(SettingsManager.SCOPE_GLOBAL, FilmSim.DIFFUSION_KEY, ""));
        assertEquals("", FilmSim.current().diffusion);
        assertEquals("the tray is no taller with the diffusion's choices", sliderHeight, height(box));
        options.getChildAt(3).performClick();
        assertEquals("the page's key and value", LensFilters.PEARLESCENT, manager.getString(SettingsManager.SCOPE_GLOBAL, FilmSim.DIFFUSION_KEY, ""));
        assertEquals(LensFilters.PEARLESCENT, FilmSim.current().diffusion);
        assertEquals(.5f, FilmSim.current().diffusionGrade, 0f);
        // «Сила фильтра»: the grade 0 .. 2 in steps of 0.05, 0.5 by default, stored x 100 as the page
        box.findViewWithTag("lut_tray_film_adjust_param_diffusion_strength").performClick();
        settle();
        assertEquals(View.GONE, choices.getVisibility());
        assertEquals(40, seek.getMax());
        assertEquals(10, seek.getProgress());
        assertEquals("0.50", value.getText().toString());
        TrayAdjust.Param grade = null, dir = null;
        for (TrayAdjust.Param p : LutTray.filmParams(context, FilmSim.stock("kodak_portra_160"))) {
            if (p.id.equals("diffusion_strength")) grade = p;
            if (p.id.equals("dir")) dir = p;
        }
        assertNotNull(grade);
        assertEquals("2.00", grade.text(grade.progress(2f)));
        grade.write.accept(2f);
        assertEquals("200", manager.getString(SettingsManager.SCOPE_GLOBAL, FilmSim.DIFFUSION_STRENGTH_KEY, ""));
        assertEquals(2f, FilmSim.current().diffusionGrade, 0f);
        assertEquals("the owner's pair's Pro-Mist 2.0", .7875, FilmSim.current().diffusionP(), 1e-6);
        // «Цветоразделение (DIR)»: 0 .. 100 % (review: above it greys go off), 100 % by default
        TextView couplers = box.findViewWithTag("lut_tray_film_adjust_param_dir");
        assertEquals("Цветоразделение (DIR)", couplers.getText().toString());
        couplers.performClick();
        settle();
        assertEquals(100, seek.getMax());
        assertEquals(100, seek.getProgress());
        assertEquals("100 %", value.getText().toString());
        assertNotNull(dir);
        dir.write.accept(0f);
        assertEquals("0", manager.getString(SettingsManager.SCOPE_GLOBAL, FilmSim.DIR_KEY, ""));
        assertEquals(0f, FilmSim.current().couplers, 0f);
        assertEquals("the height kept", sliderHeight, height(box));
        // what acts on which stock: the diffusion on every stock (black and white too), the couplers on the measured colour ones
        for (String st : new String[]{"trix400", "trix7266", firstOfType(FilmSim.SLIDE), "kodak_vision3_250d"}) {
            box.findViewWithTag("lut_tray_film_" + st).performClick();
            assertNotNull(st, box.findViewWithTag("lut_tray_film_adjust_param_diffusion"));
            assertNotNull(st, box.findViewWithTag("lut_tray_film_adjust_param_diffusion_strength"));
            assertEquals(st, FilmSim.stock(st).spectral != null, box.findViewWithTag("lut_tray_film_adjust_param_dir") != null);
        }
        // a long press: what it does
        TextView hint = box.findViewWithTag("lut_tray_hint");
        assertTrue(box.findViewWithTag("lut_tray_film_adjust_param_diffusion").performLongClick());
        assertTrue(hint.getText().toString().startsWith("Диффузия"));
        assertTrue(hint.getText().toString().contains(ParamHelp.of(context, FilmSim.DIFFUSION_KEY)));
        for (String k : new String[]{FilmSim.DIFFUSION_KEY, FilmSim.DIFFUSION_STRENGTH_KEY, FilmSim.DIR_KEY}) assertNotNull(k, ParamHelp.of(context, k));
        // «Формат»: 65 mm and IMAX 70 among the choices
        box.findViewWithTag("lut_tray_film_kodak_portra_160").performClick();
        box.findViewWithTag("lut_tray_film_adjust_param_format").performClick();
        settle();
        options.getChildAt(formatIndex("imax70")).performClick();
        assertEquals("imax70", manager.getString(SettingsManager.SCOPE_GLOBAL, FilmSim.FORMAT_KEY, ""));
        assertEquals("IMAX 70", ((TextView) options.getChildAt(formatIndex("imax70"))).getText().toString());
        assertEquals("65 mm", ((TextView) options.getChildAt(formatIndex("65mm"))).getText().toString());
        assertEquals(70410.0, FilmSim.current().diffusionFrameUm(), .01);
    }

    /** P380 with P382: a double tap on «Плёнка» resets the diffusion, its grade and the couplers too (FilmSim.KEYS), the hint undoes it. */
    @Test
    public void aDoubleTapOnTheFilmTabResetsTheDiffusionAndTheCouplers() {
        final String g = SettingsManager.SCOPE_GLOBAL;
        manager.set(g, FilmSim.STOCK_KEY, "kodak_portra_160");
        manager.set(g, FilmSim.DIFFUSION_KEY, LensFilters.PEARLESCENT);
        manager.set(g, FilmSim.DIFFUSION_STRENGTH_KEY, "200");
        manager.set(g, FilmSim.DIR_KEY, "0");
        manager.set(g, FilmSim.FORMAT_KEY, "imax70");
        TextView pill = new TextView(context);
        LinearLayout box = new LinearLayout(context);
        LutTray tray = attachedTray(pill, box);
        tray.setOpen(true);
        settle();
        final android.content.SharedPreferences prefs = manager.getDefaultPreferences();
        final java.util.Map<String, ?> before = prefs.getAll();
        doubleTap(box.findViewWithTag("lut_tray_tab_film"), android.os.SystemClock.uptimeMillis());
        for (String k : new String[]{FilmSim.DIFFUSION_KEY, FilmSim.DIFFUSION_STRENGTH_KEY, FilmSim.DIR_KEY, FilmSim.FORMAT_KEY})
            assertFalse(k, prefs.contains(k));
        assertOthersKept(LutTray.TAB_FILM, before, prefs.getAll());
        assertNull("no film", FilmSim.current());
        TextView hint = box.findViewWithTag("lut_tray_hint");
        hint.performClick();
        settle();
        assertEquals("undone", before, prefs.getAll());
        assertEquals(.7875, FilmSim.current().diffusionP(), 1e-6);
        assertEquals(0f, FilmSim.current().couplers, 0f);
    }

    /**
     * P382 (owner, 2026-10-28): a double tap on a tab's title puts every setting of that tab back to the default the settings
     * pages' reset gives (P357) - the LUT «Нет» at 100 %, no lens filter with its strength and fine controls as designed, no
     * film with every film setting (print, format, effects), the colour rows at 0 - and nothing else; the tab's dot goes, the
     * hint line says it, a tap on it puts the values back.
     */
    @Test
    public void aDoubleTapOnATabResetsThatTabOnly() {
        final String g = SettingsManager.SCOPE_GLOBAL;
        manager.set(g, FilmLut.KEY, FilmLooks.values()[1]);
        manager.set(g, FilmLut.STRENGTH_KEY, "40");
        manager.set(g, LensFilters.KEY, "bpm");
        manager.set(g, LensFilters.STRENGTH_KEY, "80");
        LensFilters.Tune tune = PreferenceKeys.lensFilterTune("bpm");
        tune.set(LensFilters.T_RADIUS, .7f);
        manager.set(g, LensFilters.tuneKey("bpm"), tune.serialize());
        manager.set(g, FilmSim.STOCK_KEY, "kodak_portra_400");
        manager.set(g, FilmSim.GRAIN_KEY, "150");
        manager.set(g, FilmSim.PRINT_KEY, "kodak_2383");
        manager.set(g, FilmSim.FORMAT_KEY, "67");
        manager.set(g, FilmSim.PRINT_M_KEY, "5");
        manager.set(g, FilmSim.STRENGTH_KEY, "80");
        manager.set(g, LutTray.WARMTH_KEY, "30");
        manager.set(g, LutTray.TINT_KEY, "-20");
        manager.set(g, "pref_film_sim_probe_other", "kept"); // a key of no tab: never touched
        TextView pill = new TextView(context);
        LinearLayout box = new LinearLayout(context);
        LutTray tray = attachedTray(pill, box);
        tray.setOpen(true);
        settle();
        final android.content.SharedPreferences prefs = manager.getDefaultPreferences();
        TextView hint = box.findViewWithTag("lut_tray_hint");
        long t = android.os.SystemClock.uptimeMillis();
        // the film's tab: reset, then undone by a tap on the hint
        TextView film = box.findViewWithTag("lut_tray_tab_film");
        assertNotEquals("the dot: a film is on", "Плёнка", film.getText().toString());
        java.util.Map<String, ?> before = prefs.getAll();
        doubleTap(film, t);
        assertTrue("the first tap shows the tab", film.isActivated());
        assertNull(FilmSim.current());
        for (String k : FilmSim.KEYS) assertFalse(k, prefs.contains(k));
        assertOthersKept(LutTray.TAB_FILM, before, prefs.getAll());
        assertEquals("the dot went", "Плёнка", film.getText().toString());
        assertEquals(View.VISIBLE, hint.getVisibility());
        assertTrue(hint.getText().toString(), hint.getText().toString().startsWith("Плёнка") && hint.getText().toString().contains("по умолчанию"));
        assertEquals("no film: nothing to adjust", View.GONE, box.findViewWithTag("lut_tray_film_adjust").getVisibility());
        hint.performClick();
        settle();
        assertEquals("undone", before, prefs.getAll());
        assertEquals("kodak_2383", FilmSim.current().print.id);
        assertNotEquals("the dot is back", "Плёнка", film.getText().toString());
        assertEquals(View.VISIBLE, box.findViewWithTag("lut_tray_film_adjust").getVisibility());
        // each tab: its own settings at their defaults, every other key as it was
        final String[] titles = {"LUT", "Фильтр", "Плёнка", "Цвет"};
        for (int tab : new int[]{LutTray.TAB_FILM, LutTray.TAB_LUT, LutTray.TAB_FILTER, LutTray.TAB_COLOUR}) {
            t += 1000;
            TextView chip = box.findViewWithTag("lut_tray_tab_" + LutTray.TAB_IDS[tab]);
            assertNotEquals(titles[tab] + ": its dot before", titles[tab], chip.getText().toString());
            before = prefs.getAll();
            doubleTap(chip, t);
            assertTrue(chip.isActivated());
            assertOthersKept(tab, before, prefs.getAll());
            assertEquals(titles[tab] + ": the dot went", titles[tab], chip.getText().toString());
            assertTrue(hint.getText().toString(), hint.getText().toString().startsWith(titles[tab]));
            switch (tab) {
                case LutTray.TAB_LUT:
                    assertEquals("", PreferenceKeys.filmLut());
                    assertEquals(1f, PreferenceKeys.filmLutStrength(), 1e-6f);
                    break;
                case LutTray.TAB_FILTER:
                    assertEquals("", PreferenceKeys.lensFilter());
                    assertEquals(.5f, PreferenceKeys.lensFilterStrength(), 1e-6f);
                    assertEquals("the fine controls as designed", .5f, PreferenceKeys.lensFilterTune("bpm").radius, 1e-6f);
                    assertFalse(prefs.contains(LensFilters.tuneKey("bpm")));
                    break;
                case LutTray.TAB_FILM:
                    for (String k : FilmSim.KEYS) assertFalse(k, prefs.contains(k));
                    break;
                default:
                    assertEquals(0, LutTray.colourValue(LutTray.WARMTH_KEY));
                    assertEquals(0, LutTray.colourValue(LutTray.TINT_KEY));
                    assertEquals(100, ((android.widget.SeekBar) ((ViewGroup) box.findViewWithTag("lut_tray_warmth")).getChildAt(1)).getProgress());
            }
        }
        assertEquals("kept", manager.getString(g, "pref_film_sim_probe_other", ""));
        assertEquals("LUT", pill.getText().toString());
        // at its defaults already: said so, nothing written
        t += 1000;
        before = prefs.getAll();
        doubleTap(box.findViewWithTag("lut_tray_tab_colour"), t);
        assertEquals(before, prefs.getAll());
        assertTrue(hint.getText().toString(), hint.getText().toString().contains("уже по умолчанию"));
    }

    /** P382: a single tap on a tab only shows it (a tap on the shown tab, or taps on two tabs, never reset anything). */
    @Test
    public void aSingleTapOnATabOnlySwitchesIt() {
        manager.set(SettingsManager.SCOPE_GLOBAL, FilmSim.STOCK_KEY, "kodak_portra_400");
        manager.set(SettingsManager.SCOPE_GLOBAL, FilmSim.GRAIN_KEY, "150");
        manager.set(SettingsManager.SCOPE_GLOBAL, FilmLut.KEY, FilmLooks.values()[1]);
        TextView pill = new TextView(context);
        LinearLayout box = new LinearLayout(context);
        LutTray tray = attachedTray(pill, box);
        tray.setOpen(true);
        settle();
        final android.content.SharedPreferences prefs = manager.getDefaultPreferences();
        final java.util.Map<String, ?> before = prefs.getAll();
        TextView film = box.findViewWithTag("lut_tray_tab_film"), lut = box.findViewWithTag("lut_tray_tab_lut");
        long t = android.os.SystemClock.uptimeMillis();
        tap(film, t);
        settle();
        assertTrue(film.isActivated());
        assertEquals(View.VISIBLE, box.findViewWithTag("lut_tray_page_film").getVisibility());
        assertEquals(before, prefs.getAll());
        // the shown tab tapped once more, later than a double tap
        tap(film, t + 1000);
        settle();
        assertTrue(film.isActivated());
        assertEquals(before, prefs.getAll());
        // two tabs one right after the other: each a single tap
        tap(lut, t + 2000);
        tap(film, t + 2120);
        settle();
        assertTrue(film.isActivated());
        assertFalse(lut.isActivated());
        assertEquals(before, prefs.getAll());
        assertEquals(View.GONE, box.findViewWithTag("lut_tray_hint").getVisibility());
    }

    /** The tray in an activity's window (its views' clicks run: a view's posted click waits for a window). */
    private LutTray attachedTray(TextView pill, LinearLayout box) {
        android.app.Activity activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        FrameLayout root = new FrameLayout(context);
        root.addView(pill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        activity.setContentView(root);
        return new LutTray(pill, box, null, () -> { });
    }

    /** A short tap at {@code t} (40 ms down), as a finger gives it. */
    private static void tap(View v, long t) {
        final android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, 10, 10, 0);
        v.dispatchTouchEvent(down);
        down.recycle();
        final android.view.MotionEvent up = android.view.MotionEvent.obtain(t, t + 40, android.view.MotionEvent.ACTION_UP, 10, 10, 0);
        v.dispatchTouchEvent(up);
        up.recycle();
    }

    /** Two taps 120 ms apart, then what they posted runs. */
    private static void doubleTap(View v, long t) {
        tap(v, t);
        tap(v, t + 120);
        settle();
    }

    /** Every key not of tab {@code tab} as it was (the tabs' keys listed here, not read from the tray's own rule). */
    private static void assertOthersKept(int tab, java.util.Map<String, ?> before, java.util.Map<String, ?> after) {
        final java.util.Set<String> keys = new java.util.HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) if (!tabKey(tab, k)) assertEquals(k, before.get(k), after.get(k));
    }

    private static boolean tabKey(int tab, String k) {
        if (tab == LutTray.TAB_LUT) return k.equals(FilmLut.KEY) || k.equals(FilmLut.STRENGTH_KEY);
        if (tab == LutTray.TAB_FILTER) return k.equals(LensFilters.KEY) || k.equals(LensFilters.STRENGTH_KEY) || k.startsWith(LensFilters.TUNE_PREFIX);
        if (tab == LutTray.TAB_FILM) return k.startsWith("pref_film_sim");
        return k.equals(LutTray.WARMTH_KEY) || k.equals(LutTray.TINT_KEY);
    }

    /** The tray's height as its layout gives it at a phone's width. */
    private static int height(View box) {
        box.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return box.getMeasuredHeight();
    }

    private static int printIndex(String id) {
        for (int i = 0; i < FilmSim.PRINTS.length; i++) if (FilmSim.PRINTS[i].id.equals(id)) return i;
        throw new AssertionError(id);
    }

    private static int formatIndex(String id) {
        for (int i = 0; i < FilmSim.FORMATS.length; i++) if (FilmSim.FORMATS[i].id.equals(id)) return i;
        throw new AssertionError(id);
    }

    private static String firstOfType(int type) {
        for (FilmSim.Stock st : FilmSim.STOCKS) if (st.type == type) return st.id;
        throw new AssertionError(type);
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
