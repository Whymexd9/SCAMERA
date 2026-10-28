package com.particlesdevs.photoncamera.ui.settings;

import android.app.Application;
import android.content.Context;

import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.color.FilmSim;
import com.particlesdevs.photoncamera.processing.color.LensFilters;
import com.particlesdevs.photoncamera.settings.DevKeys;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.FilmSimPreference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The lab («Гайд и лаборатория», P277) offers every processing setting the owner can change (owner, 2026-10-21: «в лаборатории у
 * нас не все пункты есть, исправь. также проверь что все настройки рабочие»), and a comparison changes what the shot's merge and
 * pipeline read: the «Движение» mode's own values on a shot taken in it, an ACES / Uchimura parameter with its operator on, a
 * film setting with a stock, a lens filter's tune, the denoise tables.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class ScamLabFragmentTest {
    private static final String H = "pref_scam_hybrid_";
    private Context context;
    private PreferenceScreen tree;
    private MockedStatic<ScameraApp> camera;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        final SettingsManager manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        final ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        tree = ScamLabFragment.defaultsTree(context);
    }

    @After public void tearDown() {
        if (camera != null) camera.close();
    }

    /** The keys the lab lists, as its pages walk the settings tree (renderShot / addGroup). */
    private Set<String> offered() {
        final Set<String> out = new LinkedHashSet<>();
        for (String g : ScamLabFragment.GROUPS) {
            final Preference p = tree.findPreference(g);
            assertTrue("lab group " + g, p instanceof PreferenceGroup);
            walk((PreferenceGroup) p, out);
        }
        return out;
    }

    private void walk(PreferenceGroup group, Set<String> out) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            final Preference p = group.getPreference(i);
            if (!p.isVisible()) continue;
            if (p instanceof PreferenceGroup) walk((PreferenceGroup) p, out);
            else if (p instanceof FilmSimPreference) out.addAll(Arrays.asList(FilmSim.KEYS));
            else if (ScamLabFragment.comparable(p)) out.add(p.getKey());
        }
    }

    @Test public void everyProcessingPageAndRowIsOffered() {
        final PreferenceGroup shdr = (PreferenceGroup) tree.findPreference("scam_hybrid_screen");
        final List<String> groups = Arrays.asList(ScamLabFragment.GROUPS);
        // every SHDR page, «Кадры светов и теней» too (the burst keeps those frames, the worker merges them again)
        for (int i = 0; i < shdr.getPreferenceCount(); i++)
            assertTrue(shdr.getPreference(i).getKey(), groups.contains(shdr.getPreference(i).getKey()));
        final Set<String> offered = offered();
        for (String key : new String[]{
                // «Кадры светов и теней»
                H + "bento", H + "bento_frames", H + "bento_validate", H + "bento_motion_max", H + "bento_sigma", H + "bento_ramp",
                H + "bento_chroma_sigma", H + "bento_scam", H + "shasta", H + "shasta_mode", H + "shasta_motion_max",
                H + "shasta_sharpness", H + "shasta_max_ratio", H + "lut_sigma", H + "bento_factor", H + "shasta_ev",
                // the denoise tables
                H + "dn_luma_t1_snr", H + "dn_luma_t5_outlier", H + "dn_chroma_t4_strength", H + "dn_chroma_t1_outlier",
                // «Движение», the noise model, the photo size
                H + "mm_boost_value", H + "mm_bento", H + "mm_shutter", H + "noise_photon", "pref_noise_model_profile_key",
                H + "downsampler", H + "auto2x", H + "output", "pref_crop_upscale",
                // «Цвет и стиль»: the warmth and saturation beside its pages, its pages' rows
                H + "colour_warmth", H + "why_agx_sat", H + "colour_tint", "pref_color_method_key", FilmSim.GRAIN_KEY,
                LensFilters.KEY, "pref_film_lut", H + "colour_mono"})
            assertTrue(key, offered.contains(key));
        // a fixed-at-the-shot entry is a row the lab shows (none left unreachable)
        for (String key : ScamLabFragment.AT_SHOT) assertTrue("AT_SHOT " + key, offered.contains(key));
        // and every row offered has its control
        for (String key : offered) assertNotNull(key, ScamLabFragment.spec(tree, context, key));
    }

    @Test public void onlyWhatTheShotFixedIsMarkedFixed() {
        // the per-channel recovery runs in the replayed ScamRgb on the burst's clip flags
        assertFalse(ScamLabFragment.AT_SHOT.contains(H + "highlight_recovery"));
        // the merge reads these from the replay's tuning: never fixed at the shot
        for (String key : new String[]{H + "bento_sigma", H + "bento_ramp", H + "shasta_mode", H + "lut_sigma", H + "downsampler", H + "auto2x"})
            assertFalse(key, ScamLabFragment.AT_SHOT.contains(key));
        // the capture plan and the shot's Parameters
        for (String key : new String[]{H + "bento_factor", H + "bento_trigger", H + "shasta_frames", H + "shasta_ev", H + "mm_shutter",
                H + "mm_flicker", H + "mm_zsl_frames", "pref_color_method_key", "pref_noise_model_profile_key", "pref_crop_upscale",
                "pref_mosaic_wb_corrections"}) // P370 (A9): the mosaic white balance is in the saved white point
            assertTrue(key, ScamLabFragment.AT_SHOT.contains(key));
    }

    @Test public void theDenoiseTablesCompareAsNumbers() {
        final ScamLabFragment.Spec snr = ScamLabFragment.spec(tree, context, H + "dn_luma_t1_snr");
        assertEquals(ScamLabFragment.Spec.SLIDER, snr.kind);
        assertEquals(0.1f, snr.min, 0f);
        assertEquals(500f, snr.max, 0f);
        assertEquals("5.0", snr.def);
        final ScamLabFragment.Spec strength = ScamLabFragment.spec(tree, context, H + "dn_luma_t1_strength");
        assertEquals(ScamLabFragment.Spec.TEXT, strength.kind);
        assertEquals(5, strength.count);
        assertEquals("1.2,1.1,1.2,1.1,1", strength.def);
        // each band a quarter of the range (0..10) away
        assertEquals("3.7,3.6,3.7,3.6,3.5", ScamLabFragment.suggested(strength, strength.def));
        assertEquals("7.5,7.5,7.5,7.5,7.5", ScamLabFragment.suggested(strength, "10,10,10,10,10"));
        final ScamLabFragment.Spec outlier = ScamLabFragment.spec(tree, context, H + "dn_chroma_t1_outlier");
        assertFalse(outlier.decimal);
        assertEquals("4,9,9,8,8", ScamLabFragment.suggested(outlier, "0,5,5,4,4"));
        // a value of another length reads as the default
        assertArrayEquals(new float[]{1.2f, 1.1f, 1.2f, 1.1f, 1f}, ScamLabFragment.numbers(strength, "1,2"), 0f);
        // the denoise switch off at the shot: the table compares with it on
        assertEquals(Collections.singletonMap(H + "denoise", true),
                ScamLabFragment.forced(H + "dn_luma_t1_strength", Collections.singletonMap(H + "denoise", false), tree));
    }

    @Test public void aMotionShotComparesTheModesOwnValues() {
        final Map<String, Object> motion = Collections.singletonMap("pref_motion_mode", true);
        assertTrue(ScamLabFragment.motionShot(motion));
        assertFalse(ScamLabFragment.motionShot(Collections.emptyMap()));
        assertEquals(H + "mm_boost_value", ScamLabFragment.effectiveKey(H + "boost_value", true, tree));
        assertEquals(H + "mm_motion_threshold", ScamLabFragment.effectiveKey(H + "motion_threshold", true, tree));
        assertEquals(H + "mm_motion_boost", ScamLabFragment.effectiveKey(H + "motion_boost", true, tree));
        assertEquals(H + "mm_bento", ScamLabFragment.effectiveKey(H + "bento", true, tree));
        assertEquals(H + "mm_shasta", ScamLabFragment.effectiveKey(H + "shasta", true, tree));
        // the mode has no row of the base kernel's widening: it does nothing there, and the row says so
        assertNull(ScamLabFragment.effectiveKey(H + "widen_below", true, tree));
        assertNotNull(ScamLabFragment.inert(context, H + "widen_below", null, motion, new HashMap<>()));
        // other keys and other shots: the key itself
        assertEquals(H + "cdm", ScamLabFragment.effectiveKey(H + "cdm", true, tree));
        assertEquals(H + "boost_value", ScamLabFragment.effectiveKey(H + "boost_value", false, tree));
        // the mode's rows act only on a shot taken in it
        assertNotNull(ScamLabFragment.inert(context, H + "mm_boost_value", H + "mm_boost_value", Collections.emptyMap(), new HashMap<>()));
        assertNull(ScamLabFragment.inert(context, H + "mm_boost_value", H + "mm_boost_value", motion, new HashMap<>()));
        // its boost switch off at the shot: the boost value compares with it on
        final Map<String, Object> off = new HashMap<>(motion);
        off.put(H + "mm_motion_boost", false);
        assertEquals(Collections.singletonMap(H + "mm_motion_boost", true), ScamLabFragment.forced(H + "mm_boost_value", off, tree));
    }

    /**
     * Merge rows of the settings audit 2026-10-21: the variance threshold compares under the boost switch the shot's merge read
     * (the mode's own on a «Движение» shot), the highlight / shadow frame rows say a saved burst can only lose frames.
     */
    @Test public void theVarianceThresholdComparesUnderTheBoostInEffect() {
        final Map<String, Object> off = new HashMap<>();
        off.put(H + "motion_boost", false);
        assertEquals(Collections.singletonMap(H + "motion_boost", true), ScamLabFragment.forced(H + "boost_threshold", off, tree));
        assertNotNull(ScamLabFragment.inert(context, H + "boost_threshold", H + "boost_threshold", off, new HashMap<>()));
        final Map<String, Object> motion = new HashMap<>(off);
        motion.put("pref_motion_mode", true);
        assertTrue(ScamLabFragment.forced(H + "boost_threshold", motion, tree).isEmpty()); // the mode's boost is on by default
        motion.put(H + "mm_motion_boost", false);
        final LinkedHashMap<String, Object> forced = ScamLabFragment.forced(H + "boost_threshold", motion, tree);
        assertEquals(Collections.singletonMap(H + "mm_motion_boost", true), forced);
        assertNull(ScamLabFragment.inert(context, H + "boost_threshold", H + "boost_threshold", motion, forced));
        for (String k : new String[]{"bento", "bento_frames", "shasta", "shasta_mode", "shasta_max_ratio", "mm_bento", "mm_shasta"})
            assertTrue(k, ScamLabFragment.BURST_FRAMES.contains(H + k));
        assertFalse(ScamLabFragment.BURST_FRAMES.contains(H + "bento_sigma"));
        // the shadow frames' rows compare under the shadow switch the shot's merge read (no android:dependency any more)
        final Map<String, Object> noShasta = new HashMap<>();
        noShasta.put(H + "shasta", false);
        assertEquals(Collections.singletonMap(H + "shasta", true), ScamLabFragment.forced(H + "shasta_mode", noShasta, tree));
        noShasta.put("pref_motion_mode", true);
        assertEquals(Collections.singletonMap(H + "mm_shasta", true), ScamLabFragment.forced(H + "shasta_mode", noShasta, tree));
    }

    @Test public void acesAndUchimuraParametersActWithTheirOperator() {
        final Map<String, Object> none = Collections.emptyMap();
        assertEquals(Collections.singletonMap(H + "why_aces_on", true), ScamLabFragment.forced(H + "why_aces_a", none, tree));
        assertEquals(Collections.singletonMap(H + "why_uchi_on", true), ScamLabFragment.forced(H + "why_uchi_b", none, tree));
        // WhyTone.toneOperator: own curve > Uchimura > ACES
        final Map<String, Object> both = new HashMap<>();
        both.put(H + "why_curve_on", true);
        both.put(H + "why_uchi_on", true);
        final LinkedHashMap<String, Object> aces = ScamLabFragment.forced(H + "why_aces_d", both, tree);
        assertEquals(Boolean.TRUE, aces.get(H + "why_aces_on"));
        assertEquals(Boolean.FALSE, aces.get(H + "why_curve_on"));
        assertEquals(Boolean.FALSE, aces.get(H + "why_uchi_on"));
        final LinkedHashMap<String, Object> uchi = ScamLabFragment.forced(H + "why_uchi_pre_gain", both, tree);
        assertEquals(Collections.singletonMap(H + "why_curve_on", false), uchi); // Uchimura is already on
        final LinkedHashMap<String, Object> acesOn = ScamLabFragment.forced(H + "why_aces_on", both, tree);
        assertEquals(2, acesOn.size());
        assertEquals(Boolean.FALSE, acesOn.get(H + "why_curve_on"));
        assertEquals(Boolean.FALSE, acesOn.get(H + "why_uchi_on"));
        // the toe acts under AgX too: nothing to switch
        assertTrue(ScamLabFragment.forced(H + "why_aces_toe", none, tree).isEmpty());
    }

    @Test public void aFilmSettingOfAShotWithoutFilmGetsAStock() {
        final LinkedHashMap<String, Object> grain = ScamLabFragment.forced(FilmSim.GRAIN_KEY, Collections.emptyMap(), tree);
        assertEquals(Collections.singletonMap(FilmSim.STOCK_KEY, FilmSim.STOCKS[0].id), grain);
        assertTrue(ScamLabFragment.forced(FilmSim.STOCK_KEY, Collections.emptyMap(), tree).isEmpty());
        final Map<String, Object> film = new HashMap<>();
        film.put(FilmSim.STOCK_KEY, FilmSim.STOCKS[1].id);
        assertTrue(ScamLabFragment.forced(FilmSim.GRAIN_KEY, film, tree).isEmpty());
        film.put(FilmSim.STRENGTH_KEY, "0");
        assertEquals(Collections.singletonMap(FilmSim.STRENGTH_KEY, "100"), ScamLabFragment.forced(FilmSim.GRAIN_KEY, film, tree));
        assertTrue(ScamLabFragment.forced(FilmSim.STRENGTH_KEY, film, tree).isEmpty());
    }

    @Test public void aLensFiltersTuneIsOnItsPage() {
        // every offered filter (the diffusion family) has the four fine controls of its settings page
        for (String id : LensFilters.IDS) {
            final String k = LensFilters.tuneKey(id) + "#";
            final List<String> expected = new java.util.ArrayList<>();
            if (LensFilters.paramName(context, id) != null) expected.add(k + 0);
            for (int control : LensFilters.CONTROLS) if ((LensFilters.tunable(id) & control) != 0) expected.add(k + control);
            assertEquals(id, expected, ScamLabFragment.tuneKeys(id, context));
        }
        assertEquals(Arrays.asList(LensFilters.tuneKey(LensFilters.CINEBLOOM) + "#" + LensFilters.T_KNEE,
                LensFilters.tuneKey(LensFilters.CINEBLOOM) + "#" + LensFilters.T_RADIUS, LensFilters.tuneKey(LensFilters.CINEBLOOM) + "#" + LensFilters.T_HAZE,
                LensFilters.tuneKey(LensFilters.CINEBLOOM) + "#" + LensFilters.T_WARMTH), ScamLabFragment.tuneKeys(LensFilters.CINEBLOOM, context));
        // no filter, or one no longer offered: none
        assertTrue(ScamLabFragment.tuneKeys("", context).isEmpty());
        assertTrue(ScamLabFragment.tuneKeys(LensFilters.STREAK, context).isEmpty());
        final String haze = LensFilters.tuneKey(LensFilters.BLACK_PRO_MIST) + "#" + LensFilters.T_HAZE;
        assertEquals(LensFilters.BLACK_PRO_MIST, ScamLabFragment.tuneId(haze));
        assertNull(ScamLabFragment.tuneId(LensFilters.tuneKey(LensFilters.BLACK_PRO_MIST)));
        final ScamLabFragment.Spec spec = ScamLabFragment.spec(tree, context, haze);
        assertEquals(ScamLabFragment.Spec.SLIDER, spec.kind);
        assertEquals("50", spec.def);
        assertEquals("50", ScamLabFragment.shotValue(spec, Collections.emptyMap()));
        assertEquals("70", ScamLabFragment.shotValue(spec, Collections.singletonMap(LensFilters.tuneKey(LensFilters.BLACK_PRO_MIST), "0,50,50,70,50")));
    }

    /**
     * «Для разработчика» in the lab (audit 2026-10-21): the scam_dev.txt lines that change a saved burst's photo are offered, each
     * a switch or a number whose value is its line (none: the code's value); speed, priority and log lines are not, nor the lines
     * the burst and its Parameters fixed at the shot.
     */
    @Test public void theDeveloperLinesThatChangeThePhotoAreOffered() {
        final Set<String> lines = new LinkedHashSet<>();
        for (DevKeys.Key k : DevKeys.photo()) {
            lines.add(k.key);
            final ScamLabFragment.Spec spec = ScamLabFragment.spec(tree, context, ScamLabFragment.DEV + k.key);
            assertNotNull(k.key, spec);
            assertEquals(k.key, k.kind == DevKeys.Kind.SWITCH ? ScamLabFragment.Spec.SWITCH : ScamLabFragment.Spec.NUMBER, spec.kind);
            assertTrue(spec.dev);
            assertEquals(k.def, spec.devDefault);
            // nothing switches on around it, and nothing in the settings' availability rules greys it out
            assertTrue(ScamLabFragment.forced(spec.key, Collections.emptyMap(), tree).isEmpty());
            assertNull(ScamLabFragment.inert(context, spec.key, spec.key, Collections.emptyMap(), new HashMap<>()));
        }
        for (String k : new String[]{"hybrid_texture_g1", "hybrid_why_weight_center_hr", "hybrid_dn_huge_map", "sharpen_r1",
                "hybrid_bento_blend", "hybrid_us_fill", "hybrid_hot_cap", "hybrid_s61_mode", "ae_clamp_stack", "effmap_real"})
            assertTrue(k, lines.contains(k));
        for (String k : new String[]{"post_priority", "jpeg_threads", "hybrid_la_threads", "worker_watchdog", "hybrid_rgb_half",
                "hybrid_isp_ccm", "hybrid_clip_flags", "dng_colour_jpeg", "vf_why", "zsl_reader"})
            assertFalse(k, lines.contains(k));
        // a line's value at the shot, and B's first value
        final ScamLabFragment.Spec blend = ScamLabFragment.spec(tree, context, ScamLabFragment.DEV + "hybrid_bento_blend");
        assertNull(ScamLabFragment.devValue(blend, Collections.emptyMap()));
        assertEquals(Boolean.FALSE, ScamLabFragment.devValue(blend, Collections.singletonMap("hybrid_bento_blend", 0f)));
        assertEquals(Boolean.FALSE, ScamLabFragment.suggested(blend, null)); // the code's default is on
        final ScamLabFragment.Spec g1 = ScamLabFragment.spec(tree, context, ScamLabFragment.DEV + "hybrid_texture_g1");
        assertEquals("1.25", ScamLabFragment.devValue(g1, Collections.singletonMap("hybrid_texture_g1", 1.25f)));
        assertEquals("2.5", ScamLabFragment.suggested(g1, null));
        assertEquals("3", ScamLabFragment.suggested(g1, "1.5"));
        assertEquals("1", ScamLabFragment.suggested(g1, "0"));
        final ScamLabFragment.Spec g0 = ScamLabFragment.spec(tree, context, ScamLabFragment.DEV + "hybrid_texture_g0");
        assertNull(g0.devDefault); // the app decides (per SoC)
        assertEquals("1", ScamLabFragment.suggested(g0, null));
        assertNull(ScamLabFragment.spec(tree, context, ScamLabFragment.DEV + "no_such_line"));
    }

    /** The rows a fragment shows from the shot's whole-frame statistics are rows the lab offers. */
    @Test public void theFragmentStatisticsRowsAreOffered() {
        final Set<String> offered = offered();
        for (String key : ScamLabFragment.FRAGMENT_STATS) {
            if (key.startsWith(ScamLabFragment.DEV)) assertNotNull(key, DevKeys.find(key.substring(ScamLabFragment.DEV.length())));
            else assertTrue(key, offered.contains(key));
        }
        // the input scale is rescaled exactly (LabFrame.scaled): no note
        assertFalse(ScamLabFragment.FRAGMENT_STATS.contains(H + "why_input_ev"));
    }

    @Test public void aRowSaysWhyItDoesNothingOnThisShot() {
        // AgX's own values act only in the «Custom» look (SettingsAvailability on the shot's values)
        assertNotNull(ScamLabFragment.inert(context, H + "why_agx_slope", H + "why_agx_slope",
                Collections.singletonMap(H + "why_agx_look", "1"), new HashMap<>()));
        assertNull(ScamLabFragment.inert(context, H + "why_agx_slope", H + "why_agx_slope", Collections.emptyMap(), new HashMap<>()));
        // a fixed-at-the-shot row is said apart
        assertNull(ScamLabFragment.inert(context, H + "bento_factor", H + "bento_factor", Collections.emptyMap(), new HashMap<>()));
    }

    /**
     * Settings audit 2026-10-21 (post rows): the highlights' colour prior and defringe compare with the per-channel recovery on (a
     * shot taken without it), the clip band says why it cannot act there (no clip flags in the burst).
     */
    @Test public void theHighlightRowsCompareInsideTheRecovery() {
        final Map<String, Object> off = Collections.singletonMap(ScamLabFragment.RECOVERY, "0");
        for (String k : new String[]{H + "highlight_chroma", H + "highlight_defringe"}) {
            final LinkedHashMap<String, Object> f = ScamLabFragment.forced(k, off, tree);
            assertEquals(k, Collections.singletonMap(ScamLabFragment.RECOVERY, "100"), f);
            assertNull(k, ScamLabFragment.inert(context, k, k, off, f));
            assertTrue(k, ScamLabFragment.forced(k, Collections.emptyMap(), tree).isEmpty());
        }
        final String band = H + "highlight_band";
        assertTrue(ScamLabFragment.forced(band, off, tree).isEmpty());
        assertNotNull(ScamLabFragment.inert(context, band, band, off, new HashMap<>()));
        assertNull(ScamLabFragment.inert(context, band, band, Collections.emptyMap(), new HashMap<>()));
    }

    /**
     * The lab leaves out what the settings hide for the shot (the OPPO matrix's saturation on a shot without that matrix) and
     * prints no caption over a category with nothing to compare.
     */
    @Test public void hiddenRowsAndEmptyCaptionsAreLeftOut() {
        final Map<String, Object> none = Collections.emptyMap();
        assertTrue(ScamLabFragment.hiddenFor(H + "why_ccm_sat", none, false));
        assertFalse(ScamLabFragment.hiddenFor(H + "why_ccm_sat", none, true));
        assertFalse(ScamLabFragment.hiddenFor(H + "why_vibrance", none, false));
        final PreferenceGroup photo = (PreferenceGroup) tree.findPreference("scam_hybrid_photo_screen");
        assertTrue(ScamLabFragment.hasRows(photo, none, false));
        final PreferenceScreen screen = new androidx.preference.PreferenceManager(context).createPreferenceScreen(context);
        final androidx.preference.PreferenceCategory empty = new androidx.preference.PreferenceCategory(context);
        screen.addPreference(empty);
        empty.addPreference(new Preference(context)); // a row without a value: nothing to compare
        assertFalse(ScamLabFragment.hasRows(empty, none, false));
        assertFalse(ScamLabFragment.hasRows(screen, none, false));
    }
}
