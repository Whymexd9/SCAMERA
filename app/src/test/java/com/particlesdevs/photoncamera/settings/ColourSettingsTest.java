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
import com.particlesdevs.photoncamera.app.PhotonCamera;
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
    private MockedStatic<PhotonCamera> camera;

    @Before
    public void setUp() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Photon_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(PhotonCamera.class);
        camera.when(PhotonCamera::getAppContext).thenReturn(context);
        camera.when(PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> PhotonCamera.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(PhotonCamera::getSettingsManagerStatic).thenReturn(manager);
        camera.when(PhotonCamera::getSettings).thenReturn(mock(Settings.class));
        PhotonCamera app = mock(PhotonCamera.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> PhotonCamera.getInstance(any(Context.class))).thenReturn(app);
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
        assertEquals("HDR в HEIC / AVIF", ru.getString(R.string.prefs_photo_hdr_title));
        assertEquals("HDR in HEIC / AVIF", en.getString(R.string.prefs_photo_hdr_title));
        assertEquals("Display P3 (широкий цвет)", ru.getResources().getStringArray(R.array.photo_color_space_entries)[1]);
        assertEquals("sRGB (standard)", en.getResources().getStringArray(R.array.photo_color_space_entries)[0]);
    }

    @Test
    public void availability() {
        Map<String, Object> v = new HashMap<>();
        for (String f : new String[]{"jpeg", "heic", "webp", "avif"}) {
            v.put(PhotoFormat.KEY, f);
            SettingsAvailability a = new SettingsAvailability(v);
            assertFalse(a.hidden(PhotoFormat.KEY_COLOR_SPACE));
            assertNull(a.reason(PhotoFormat.KEY_COLOR_SPACE));
            assertEquals(f, f.equals("jpeg") || f.equals("webp"), a.hidden(PhotoFormat.KEY_HDR));
        }
        v.put(PhotoFormat.KEY, "heic");
        assertNotNull("HEIC without 10 bit", new SettingsAvailability(v).reason(PhotoFormat.KEY_HDR));
        v.put(PhotoFormat.KEY_HEIC_10BIT, true);
        assertNull(new SettingsAvailability(v).reason(PhotoFormat.KEY_HDR));
        assertTrue(new SettingsAvailability(v).heic10Unavailable("no Main10").reason(PhotoFormat.KEY_HDR).endsWith("no Main10"));
        assertEquals("old", new SettingsAvailability(v).hdrUnavailable("old").reason(PhotoFormat.KEY_HDR));
        v.put(PhotoFormat.KEY, "avif");
        v.put(PhotoFormat.KEY_AVIF_DEPTH, "8");
        assertNotNull(new SettingsAvailability(v).reason(PhotoFormat.KEY_HDR));
        v.put(PhotoFormat.KEY_AVIF_LOSSLESS, true);
        assertNull(new SettingsAvailability(v).reason(PhotoFormat.KEY_HDR));
        v.remove(PhotoFormat.KEY_AVIF_LOSSLESS);
        v.put(PhotoFormat.KEY_AVIF_DEPTH, "10");
        assertNull(new SettingsAvailability(v).reason(PhotoFormat.KEY_HDR));
        // the device fact on this (Android 15) runtime
        assertNull(HdrOutput.unavailableReason());
        assertNotNull(HdrOutput.reason(32));
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
        assertFalse("Android 12", HdrOutput.applies(true, PhotoFormat.HEIC, true, null, 32));
        assertTrue(HdrOutput.applies(true, PhotoFormat.AVIF, false, ten, 33));
        assertTrue(HdrOutput.applies(true, PhotoFormat.AVIF, false, lossless, 33));
        assertFalse(HdrOutput.applies(true, PhotoFormat.AVIF, false, eight, 33));
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
