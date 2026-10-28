package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.Settings;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.PhotoFormat;
import com.particlesdevs.photoncamera.processing.avif.AvifEncoder;
import com.particlesdevs.photoncamera.processing.color.HdrOutput;
import com.particlesdevs.photoncamera.processing.color.OutputColour;
import com.particlesdevs.photoncamera.processing.heif.Heic10Support;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ManagedSwitchPreference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P46 settings: «Цветовое пространство» (sRGB default / Display P3, every format) and «HDR в HEIC / AVIF» (default off; HEIC
 * with «HEIC 10 бит», AVIF at 10 / 12 bit, Android 13): rows, defaults, strings, global scope, availability, the shot rule.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class ColourSettingsTest {
    private static final Heic10Support.Encoder HW = new Heic10Support.Encoder("c2.test.hevc.encoder", true, true, true, true, true,
            0, 100, 1000, 100_000_000);
    private Context context;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before
    public void setUp() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(mock(Settings.class));
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
        AvifEncoder.setAvailableForTesting(true);
    }

    @After
    public void tearDown() {
        AvifEncoder.setAvailableForTesting(null);
        Heic10Support.clearForTesting();
        camera.close();
    }

    private PreferenceScreen inflate() {
        PreferenceManager pm = new PreferenceManager(context);
        pm.setSharedPreferencesName("colour_settings_test");
        return pm.inflateFromResource(context, R.xml.preferences, null);
    }

    private Context localized(Locale locale) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }

    @Test
    public void defaultsAreSrgbAndSdr() {
        assertEquals(OutputColour.Space.SRGB, PreferenceKeys.getOutputColourSpace());
        assertFalse(PreferenceKeys.isHdrOutputOn());
        assertFalse(HdrOutput.wanted());
        prefs.edit().putString(PhotoFormat.KEY_COLOR_SPACE, "p3").putBoolean(PhotoFormat.KEY_HDR, true).commit();
        assertEquals(OutputColour.Space.DISPLAY_P3, PreferenceKeys.getOutputColourSpace());
        assertTrue(PreferenceKeys.isHdrOutputOn());
        prefs.edit().putString(PhotoFormat.KEY_COLOR_SPACE, "nonsense").commit();
        assertEquals(OutputColour.Space.SRGB, PreferenceKeys.getOutputColourSpace());
    }

    @Test
    public void rowsInConfig() {
        PreferenceScreen config = inflate().findPreference("output_settings_screen");
        ListPreference space = config.findPreference(PhotoFormat.KEY_COLOR_SPACE);
        assertNotNull(space);
        assertArrayEquals(new CharSequence[]{"srgb", "p3"}, space.getEntryValues());
        assertEquals(2, space.getEntries().length);
        for (CharSequence v : space.getEntryValues()) assertEquals(v.toString(), OutputColour.Space.parse(v).value);
        final ManagedSwitchPreference hdr = config.findPreference(PhotoFormat.KEY_HDR);
        assertNotNull(hdr);
        assertFalse(hdr.isChecked());
    }

    @Test
    public void bothAreSharedByEveryLens() {
        for (String key : new String[]{PhotoFormat.KEY_COLOR_SPACE, PhotoFormat.KEY_HDR}) {
            assertTrue(key, ModuleProfiles.isGlobal(key));
            assertFalse(key, ModuleProfiles.isLocal(key));
        }
    }

    @Test
    public void stringsInEnglishAndRussian() {
        Context en = localized(Locale.ENGLISH), ru = localized(new Locale("ru"));
        int[] ids = {R.string.prefs_photo_color_space_title, R.string.prefs_photo_color_space_summary, R.string.prefs_photo_hdr_title,
                R.string.prefs_photo_hdr_summary};
        for (int id : ids) {
            String e = en.getString(id), r = ru.getString(id);
            assertFalse(e, e.matches(".*[\\u0400-\\u04FF].*"));
            assertTrue(r, r.matches(".*[\\u0400-\\u04FF].*"));
        }
        assertEquals("Цветовое пространство", ru.getString(R.string.prefs_photo_color_space_title));
        assertEquals("Colour space", en.getString(R.string.prefs_photo_color_space_title));
        // P266: one «HDR-фото» for every format (the JPEG's Ultra HDR and the HEIC / AVIF map)
        assertEquals("HDR-фото", ru.getString(R.string.prefs_photo_hdr_title));
        assertEquals("HDR photo", en.getString(R.string.prefs_photo_hdr_title));
        assertEquals("Display P3 (широкий цвет)", ru.getResources().getStringArray(R.array.photo_color_space_entries)[1]);
        assertEquals("sRGB (standard)", en.getResources().getStringArray(R.array.photo_color_space_entries)[0]);
    }

    /**
     * P369 (film-audit A12, settings-5 / outputs-1): with a film simulation the rows it replaces say so at 100 % (below it they act
     * on the share without the film and stay open), the gain map and «Текстура» say they are off with any film, and the colour
     * matrix's rows (warmth, tint, the kept scene light, the calibration) and the exposure keep acting.
     */
    @Test
    public void filmSimulationExplainsItsRows() throws java.io.IOException {
        final String[] bypassed = {"pref_scam_hybrid_why_macro_contrast", "pref_scam_hybrid_why_agx_sat", "pref_scam_hybrid_why_agx_look",
                "pref_scam_hybrid_why_aces_on", "pref_scam_hybrid_why_uchi_on", "pref_scam_hybrid_why_curve_on", "pref_scam_hybrid_why_gamma",
                "pref_scam_hybrid_why_film_toe", "pref_scam_hybrid_why_contrast_boost", "pref_scam_hybrid_why_hl_soft",
                "pref_scam_hybrid_why_hl_white", "pref_scam_hybrid_why_ccm_sat", "pref_scam_hybrid_why_vibrance",
                "pref_scam_hybrid_why_vibrance_sky", "pref_scam_hybrid_why_vibrance_green", "pref_scam_hybrid_why_hl_boost",
                "pref_scam_hybrid_why_shadow_str", "pref_scam_hybrid_why_highlight_str", "pref_scam_hybrid_why_weight_mid",
                "pref_scam_hybrid_why_weight_center", "pref_scam_hybrid_why_blend_smoothness", "pref_scam_hybrid_why_ef_coarse",
                "pref_scam_hybrid_why_sharp_gf_lc", "pref_scam_hybrid_why_sharp_film_grain", "pref_scam_hybrid_colour_mono",
                "pref_scam_hybrid_colour_mix_red_hue", "pref_scam_hybrid_colour_grade_mids_str", "pref_scam_hybrid_colour_skin_protect",
                "pref_scam_hybrid_colour_gamut", "pref_scam_hybrid_colour_hue_lock", "pref_scam_hybrid_colour_vib_shadows"};
        final String[] acting = {"pref_scam_hybrid_colour_warmth", "pref_scam_hybrid_colour_tint", "pref_scam_hybrid_colour_keep_warm",
                "pref_scam_hybrid_colour_keep_cool", "pref_scam_hybrid_colour_cal_red_hue", "pref_scam_hybrid_why_ae_target",
                "pref_scam_hybrid_why_hl_overflow", "pref_scam_hybrid_why_sharp_gain", "pref_scam_hybrid_why_clarity",
                "pref_scam_hybrid_why_sharp_usm_amount", "pref_film_lut", "pref_film_lut_strength"};
        final String xml = new String(java.nio.file.Files.readAllBytes(new java.io.File("src/main/res/xml/preferences.xml").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        for (String k : bypassed) assertTrue("a real row: " + k, xml.contains("android:key=\"" + k + "\""));
        for (String k : acting) assertTrue("a real row: " + k, xml.contains("android:key=\"" + k + "\""));
        final String texture = "pref_scam_hybrid_why_texture", hdr = PhotoFormat.KEY_HDR;
        Map<String, Object> v = new HashMap<>();
        for (String k : bypassed) assertNull("no film: " + k, new SettingsAvailability(v).reason(k));
        assertNull(new SettingsAvailability(v).reason(texture));
        assertNull(new SettingsAvailability(v).reason(hdr));
        v.put(com.particlesdevs.photoncamera.processing.color.FilmSim.STOCK_KEY, com.particlesdevs.photoncamera.processing.color.FilmSim.STOCKS[0].id);
        for (String k : bypassed) assertNotNull("film at 100 %: " + k, new SettingsAvailability(v).reason(k));
        for (String k : acting) assertNull("acts on the film: " + k, new SettingsAvailability(v).reason(k));
        assertNotNull(new SettingsAvailability(v).reason(texture));
        assertTrue(new SettingsAvailability(v).reason(hdr).contains("SDR"));
        v.put(com.particlesdevs.photoncamera.processing.color.FilmSim.STRENGTH_KEY, "60");
        for (String k : bypassed) assertNull("below 100 % ours stays: " + k, new SettingsAvailability(v).reason(k));
        assertNotNull("the gates: off with any film", new SettingsAvailability(v).reason(texture));
        assertNotNull(new SettingsAvailability(v).reason(hdr));
        v.put(com.particlesdevs.photoncamera.processing.color.FilmSim.STRENGTH_KEY, 0);
        assertNull("0 % is no film", new SettingsAvailability(v).reason(texture));
        assertNull(new SettingsAvailability(v).reason(hdr));
    }

    @Test
    public void availability() {
        Map<String, Object> v = new HashMap<>();
        for (String f : new String[]{"jpeg", "heic", "webp", "avif"}) {
            v.put(PhotoFormat.KEY, f);
            SettingsAvailability a = new SettingsAvailability(v);
            assertFalse(a.hidden(PhotoFormat.KEY_COLOR_SPACE));
            assertNull(a.reason(PhotoFormat.KEY_COLOR_SPACE));
            assertFalse(f, a.hidden(PhotoFormat.KEY_HDR)); // P266: the one HDR switch shows for every format
        }
        v.put(PhotoFormat.KEY, "jpeg");
        assertNull("a JPEG holds Ultra HDR", new SettingsAvailability(v).reason(PhotoFormat.KEY_HDR));
        v.put(PhotoFormat.KEY, "webp");
        assertNotNull("WebP alone holds none", new SettingsAvailability(v).reason(PhotoFormat.KEY_HDR));
        v.put(PhotoFormat.KEY, "heic");
        assertNotNull("HEIC without 10 bit", new SettingsAvailability(v).reason(PhotoFormat.KEY_HDR));
        v.put(PhotoFormat.KEY_HEIC_10BIT, true);
        assertNull(new SettingsAvailability(v).reason(PhotoFormat.KEY_HDR));
        assertTrue(new SettingsAvailability(v).heic10Unavailable("no Main10").reason(PhotoFormat.KEY_HDR).endsWith("no Main10"));
        assertEquals("old", new SettingsAvailability(v).hdrUnavailable("old").reason(PhotoFormat.KEY_HDR));
        v.put(PhotoFormat.KEY, "avif");
        v.put(PhotoFormat.KEY_AVIF_DEPTH, "8");
        assertNull("P125: the gain map fits an 8-bit AVIF", new SettingsAvailability(v).reason(PhotoFormat.KEY_HDR));
        v.put(PhotoFormat.KEY_AVIF_LOSSLESS, true);
        assertNull(new SettingsAvailability(v).reason(PhotoFormat.KEY_HDR));
        v.remove(PhotoFormat.KEY_AVIF_LOSSLESS);
        v.put(PhotoFormat.KEY_AVIF_DEPTH, "10");
        assertNull(new SettingsAvailability(v).reason(PhotoFormat.KEY_HDR));
        // the device fact on this (Android 15) runtime
        assertNull(HdrOutput.unavailableReason());
        assertNull("AVIF from Android 12", HdrOutput.reason(32));
        assertNotNull(HdrOutput.reason(30));
        assertNull(DeviceAvailability.of(v).reason(PhotoFormat.KEY_HDR));
    }

    @Test
    public void shotRule() {
        final AvifEncoder.Options ten = new AvifEncoder.Options(90, false, 10, true, 6, 1);
        final AvifEncoder.Options eight = new AvifEncoder.Options(90, false, 8, true, 6, 1);
        final AvifEncoder.Options lossless = new AvifEncoder.Options(90, true, 8, true, 6, 1);
        assertFalse("off", HdrOutput.applies(false, PhotoFormat.HEIC, true, null, 35));
        assertTrue(HdrOutput.applies(true, PhotoFormat.HEIC, true, null, 35));
        assertFalse("no 10-bit HEIC", HdrOutput.applies(true, PhotoFormat.HEIC, false, null, 35));
        assertFalse("Android 12: no 10-bit HEIC", HdrOutput.applies(true, PhotoFormat.HEIC, true, null, 32));
        assertTrue(HdrOutput.applies(true, PhotoFormat.AVIF, false, ten, 33));
        assertTrue(HdrOutput.applies(true, PhotoFormat.AVIF, false, lossless, 33));
        assertTrue("P125: the gain map fits an 8-bit AVIF", HdrOutput.applies(true, PhotoFormat.AVIF, false, eight, 33));
        assertTrue("AVIF on Android 12", HdrOutput.applies(true, PhotoFormat.AVIF, false, eight, 31));
        assertFalse("Android 11", HdrOutput.applies(true, PhotoFormat.AVIF, false, eight, 30));
        assertFalse(HdrOutput.applies(true, PhotoFormat.JPEG, true, ten, 35));
        assertFalse(HdrOutput.applies(true, PhotoFormat.WEBP, true, ten, 35));
        // stored settings: HEIC with 10 bit on a capable phone
        Heic10Support.setForTesting(HW);
        prefs.edit().putString(PhotoFormat.KEY, "heic").putBoolean(PhotoFormat.KEY_HEIC_10BIT, true).putBoolean(PhotoFormat.KEY_HDR, true).commit();
        assertTrue(HdrOutput.wanted());
        prefs.edit().putBoolean(PhotoFormat.KEY_HEIC_10BIT, false).commit();
        assertFalse(HdrOutput.wanted());
        prefs.edit().putString(PhotoFormat.KEY, "avif").commit();
        assertTrue("AVIF default depth 10", HdrOutput.wanted());
        prefs.edit().putBoolean(PhotoFormat.KEY_HDR, false).commit();
        assertFalse(HdrOutput.wanted());
    }

    @Test
    public void gainMapPassFollowsUltraHdrWithoutHdr() {
        Settings s = new Settings();
        for (boolean ultra : new boolean[]{false, true}) {
            s.ultraHdr = ultra;
            s.hdrOutput = false;
            assertEquals(ultra, s.gainMapPass());
            s.hdrOutput = true;
            assertTrue(s.gainMapPass());
        }
        assertEquals(OutputColour.Space.SRGB, new Settings().colourSpace);
    }
}
