package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;

import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** P25 data model: the shade catalog over the real settings tree. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class ShadeCatalogTest {
    /** Short labels allowed to be longer than 8 characters (they wrap to two lines on the tile). */
    private static final Set<String> LONG_SHORT_LABELS = new HashSet<>(Arrays.asList("pref_vivo_nice_mosaic:neural_sabre"));

    private Context context;
    private SettingsManager manager;
    private SharedPreferences prefs;
    private MockedStatic<PhotonCamera> camera;
    private ShadeCatalog catalog;

    @Before
    public void setUp() {
        // SCAM HDR and its settings exist only on the Snapdragon 8 Elite; these tests cover both routes.
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Photon_SettingsActivity);
        manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(PhotonCamera.class);
        camera.when(PhotonCamera::getAppContext).thenReturn(context);
        camera.when(PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> PhotonCamera.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(PhotonCamera::getSettingsManagerStatic).thenReturn(manager);
        com.particlesdevs.photoncamera.api.Settings settings = mock(com.particlesdevs.photoncamera.api.Settings.class);
        camera.when(PhotonCamera::getSettings).thenReturn(settings);
        PhotonCamera app = mock(PhotonCamera.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> PhotonCamera.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
        ShadeCatalog.setFlashAvailable(true);
        catalog = new ShadeCatalog(context, prefs);
    }

    @After
    public void tearDown() {
        camera.close();
    }

    private PreferenceScreen inflate() {
        PreferenceManager pm = new PreferenceManager(context);
        pm.setSharedPreferencesName("shade_catalog_test");
        return pm.inflateFromResource(context, R.xml.preferences, null);
    }

    @Test
    public void curatedKeysAreTreeRowsOrNamedCameraControls() {
        PreferenceScreen tree = inflate();
        int curated = 0;
        for (ShadeCatalog.Group group : ShadeCatalog.GROUPS) {
            assertFalse(group.keys.isEmpty());
            for (String key : group.keys) {
                curated++;
                ShadeCatalog.Entry e = catalog.entry(key);
                assertNotNull("curated key unknown to the catalog: " + key, e);
                if (ShadeCatalog.VIRTUAL.contains(key)) {
                    assertTrue(key, e.isVirtual());
                    assertNull("a camera control must not have a tree row: " + key, tree.findPreference(key));
                } else {
                    Preference p = tree.findPreference(key);
                    assertNotNull("curated key missing from preferences.xml: " + key, p);
                    assertFalse(key, e.isVirtual());
                }
                assertTrue(key, e.isCurated());
            }
        }
        assertEquals(28, curated); // 27 + «Кодек» (pref_photo_format) in the «Формат» group
        // The rows the owner dropped (answer 8) stay out.
        for (String gone : new String[]{"pref_lmc_hybrid_ark_tone", "pref_vivo_nice_fusion_enabled"})
            assertFalse(gone, ShadeCatalog.isCurated(gone));
        for (String key : ShadeCatalog.DEFAULT_TILES) assertTrue(key, catalog.isKnown(key));
        assertEquals(8, ShadeCatalog.DEFAULT_TILES.size());
    }

    @Test
    public void everyEntryHasAnIconAndAKind() {
        List<ShadeCatalog.Entry> all = catalog.all();
        assertTrue("the catalog walks the whole tree", all.size() > 300);
        for (ShadeCatalog.Entry e : all) {
            assertNotNull("no icon: " + e.key, catalog.icon(e));
            assertNotNull("no icon: " + e.key, catalog.settingIcon(e));
            assertTrue(e.key, e.kind == ShadeCatalog.TOGGLE || e.kind == ShadeCatalog.LIST || e.kind == ShadeCatalog.SLIDER);
            assertNotNull(e.key, e.defaultValue);
            assertNotNull(e.key, e.shortTitle);
            if (e.kind == ShadeCatalog.LIST) assertEquals(e.key, e.labels.length, e.values.length);
            if (e.kind == ShadeCatalog.SLIDER) assertTrue(e.key, e.max > e.min && e.step > 0);
        }
        for (ShadeCatalog.Group group : ShadeCatalog.GROUPS)
            for (String key : group.keys) assertNotEquals(key, 0, catalog.entry(key).icon);
    }

    @Test
    public void shortLabelsFitEightCharactersInBothLocales() {
        for (Locale locale : new Locale[]{Locale.ENGLISH, new Locale("ru")}) {
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.setLocale(locale);
            Context localized = context.createConfigurationContext(config);
            ShadeCatalog c = new ShadeCatalog(localized, prefs);
            for (ShadeCatalog.Group group : ShadeCatalog.GROUPS) {
                for (String key : group.keys) {
                    ShadeCatalog.Entry e = c.entry(key);
                    if (e.kind != ShadeCatalog.LIST) continue;
                    if (e.shortLabels == null) {
                        // Without short labels only the tone curve: dozens of asset names, shown in the list sheet.
                        assertEquals(key, "pref_lmc_tone_curve", key);
                        assertTrue(key, e.isLongList());
                        continue;
                    }
                    assertEquals(locale + " " + key, e.values.length, e.shortLabels.length);
                    for (int i = 0; i < e.shortLabels.length; i++) {
                        String label = e.shortLabels[i].toString();
                        assertFalse(key, label.trim().isEmpty());
                        if (LONG_SHORT_LABELS.contains(key + ":" + e.values[i])) continue;
                        assertTrue(locale + " " + key + " «" + label + "» is longer than 8", label.length() <= 8);
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "ru-w400dp-h880dp-mdpi")
    public void valuesFollowTheSettingsKeysAndHighlightDiffersFromDefault() {
        valuesFollowTheSettingsKeysAndHighlightDiffersFromDefault(',');
    }

    /** The same on an English system: numbers use the decimal point (display only). */
    @Test
    public void valuesFollowTheSettingsKeysAndHighlightDiffersFromDefaultInEnglish() {
        valuesFollowTheSettingsKeysAndHighlightDiffersFromDefault('.');
    }

    private void valuesFollowTheSettingsKeysAndHighlightDiffersFromDefault(char point) {
        ShadeCatalog.Entry grid = catalog.entry("pref_show_grid_key");
        assertEquals(ShadeCatalog.LIST, grid.kind);
        assertFalse(catalog.changed(grid));
        catalog.write(grid, catalog.nextValue(grid));
        assertEquals("1", prefs.getString("pref_show_grid_key", null));
        assertEquals(1, PreferenceKeys.getGridValue());
        assertTrue(catalog.changed(grid));
        assertEquals("3×3", catalog.valueText(grid, true));
        assertEquals(R.drawable.ic_grid_3x3, grid.valueIcons[catalog.index(grid)]);

        // A toggle that is on by default is highlighted when off («Вкл./Выкл.» on the tile).
        ShadeCatalog.Entry shasta = catalog.entry("pref_lmc_hybrid_shasta");
        assertEquals(ShadeCatalog.TOGGLE, shasta.kind);
        assertTrue(catalog.on(shasta));
        assertFalse(catalog.changed(shasta));
        catalog.write(shasta, false);
        assertFalse(prefs.getBoolean("pref_lmc_hybrid_shasta", true));
        assertTrue(catalog.changed(shasta));
        assertEquals(context.getString(R.string.shade_off), catalog.valueText(shasta, true));

        ShadeCatalog.Entry luma = catalog.entry("pref_lmc_hybrid_dn_luma_mult");
        assertEquals(ShadeCatalog.SLIDER, luma.kind);
        assertEquals(0.01f, luma.step, 1e-6f);
        assertEquals("1" + point + "00", catalog.valueText(luma, true));
        catalog.write(luma, 0.6f);
        assertEquals("0.6", prefs.getString("pref_lmc_hybrid_dn_luma_mult", null));
        assertEquals("0" + point + "60", catalog.valueText(luma, true));
        assertTrue(catalog.changed(luma));

        // «Удлинение L» is free text in the settings; the shade shows it as a 0-2 slider (owner's answer 8).
        ShadeCatalog.Entry boost = catalog.entry("pref_vivo_nice_long_boost_ev");
        assertEquals(ShadeCatalog.SLIDER, boost.kind);
        assertEquals(0f, boost.min, 0f);
        assertEquals(2f, boost.max, 0f);
        assertEquals(0.1f, boost.step, 1e-6f);
        assertEquals("1" + point + "1", catalog.valueText(boost, true));
        catalog.write(boost, 1.5f);
        assertEquals("1.5", prefs.getString("pref_vivo_nice_long_boost_ev", null));

        // «Замер» is the ARK metering slider; the Camera2 metering stays a camera control.
        ShadeCatalog.Entry metering = catalog.entry("pref_lmc_hybrid_ark_metering");
        assertEquals(ShadeCatalog.SLIDER, metering.kind);
        assertEquals("0", catalog.valueText(metering, true));
        assertTrue(catalog.entry(ShadeCatalog.METERING_STD).isVirtual());

        // The format tile reads the virtual key and shows the new icons.
        ShadeCatalog.Entry format = catalog.entry(ShadeCatalog.FORMAT);
        PreferenceKeys.setSaveRaw(1);
        assertEquals("R+J", catalog.valueText(format, true));
        assertEquals(R.drawable.ic_shade_rawjpeg, format.valueIcons[catalog.index(format)]);
        assertTrue(catalog.changed(format));
        try {
            catalog.write(format, "2");
            fail("camera controls are written by CameraUIController");
        } catch (IllegalArgumentException expected) {
        }

        // Session-time rows restart the camera.
        assertTrue(catalog.entry("pref_live_viewfinder_raw_key").sessionTime);
        assertTrue(catalog.entry("pref_wide169_key").sessionTime);
        assertFalse(grid.sessionTime);
    }

    @Test
    public void availabilityUsesTheEffectiveRouteDependenciesAndTheFlash() {
        ShadeCatalog.Entry cdm = catalog.entry("pref_lmc_hybrid_cdm");
        assertNull(catalog.unavailable(cdm));
        manager.set("default_scope", PreferenceKeys.ROUTE_KEY, "scamhdr");
        assertNotNull(catalog.unavailable(cdm));
        assertNull(catalog.unavailable(catalog.entry("pref_vivo_nice_zsl_frames")));
        manager.set("default_scope", PreferenceKeys.ROUTE_KEY, "hybrid");
        assertNotNull(catalog.unavailable(catalog.entry("pref_vivo_nice_zsl_frames")));

        ShadeCatalog.Entry frames = catalog.entry("pref_lmc_hybrid_shasta_frames");
        assertEquals("pref_lmc_hybrid_shasta", frames.dependency);
        assertNull(catalog.unavailable(frames));
        prefs.edit().putBoolean("pref_lmc_hybrid_shasta", false).commit();
        assertEquals(context.getString(R.string.shade_reason_dependency, "Shasta"), catalog.unavailable(frames));

        ShadeCatalog.Entry flash = catalog.entry(ShadeCatalog.FLASH);
        assertNull(catalog.unavailable(flash));
        ShadeCatalog.setFlashAvailable(false);
        assertEquals(context.getString(R.string.shade_reason_no_flash), catalog.unavailable(flash));
        ShadeCatalog.setFlashAvailable(true);
    }

    @Test
    public void onlyPortablePinnableSettingsAreInTheCatalog() {
        for (String key : new String[]{"pref_dcp_profile_key", "pref_camera_package_spoof_enabled", "pref_antibanding_hz_key",
                "pref_watermark_line1", "pref_theme_key", "pref_tunable_camerauiviewimpl_enablequadres",
                "pref_restore_preferences_key", "pref_backup_preferences_key", "settings_favorites", "lmc_hybrid_screen"})
            assertFalse(key, catalog.isKnown(key));
        // Free text of the hybrid denoise tables is not pinnable.
        for (ShadeCatalog.Entry e : catalog.all()) assertFalse(e.key, e.key.startsWith("pref_lmc_hybrid_ark_luma_"));
        assertTrue(catalog.isKnown("pref_lmc_hybrid_post_luma"));
        assertTrue(catalog.isKnown("pref_jpeg_quality"));
    }

    @Test
    @Config(qualifiers = "ru-w400dp-h880dp-mdpi")
    public void searchFindsTitlesSectionsAndTheOtherLanguage() {
        searchFindsTitlesSectionsAndTheOtherLanguage("Кадры и захват");
    }

    /** The same on an English system: the Russian synonyms still find the rows, the crumb is English. */
    @Test
    public void searchFindsTitlesSectionsAndTheOtherLanguageInEnglish() {
        searchFindsTitlesSectionsAndTheOtherLanguage("Frames and capture");
    }

    /** The process-wide catalog follows a language change (the activity is recreated, the process is not). */
    @Test
    public void processCatalogIsRebuiltAfterALanguageChange() {
        ShadeCatalog.reset();
        try {
            ShadeCatalog english = ShadeCatalog.get(context);
            assertTrue(english.entry("pref_lmc_hybrid_bento").crumb().startsWith("Frames and capture"));
            assertSame(english, ShadeCatalog.get(context));
            org.robolectric.RuntimeEnvironment.setQualifiers("+ru");
            ShadeCatalog russian = ShadeCatalog.get(context);
            assertNotSame(english, russian);
            assertTrue(russian.entry("pref_lmc_hybrid_bento").crumb(), russian.entry("pref_lmc_hybrid_bento").crumb().startsWith("Кадры и захват"));
        } finally {
            ShadeCatalog.reset();
        }
    }

    private void searchFindsTitlesSectionsAndTheOtherLanguage(String captureSection) {
        List<ShadeCatalog.Entry> luma = catalog.search("Luma");
        Set<String> keys = new HashSet<>();
        for (ShadeCatalog.Entry e : luma) keys.add(e.key);
        assertTrue(keys.contains("pref_lmc_hybrid_post_luma"));
        assertTrue(keys.contains("pref_lmc_hybrid_dn_luma_mult"));
        Set<String> cyrillic = new HashSet<>();
        for (ShadeCatalog.Entry e : catalog.search("люма")) cyrillic.add(e.key);
        assertTrue(cyrillic.contains("pref_lmc_hybrid_post_luma"));
        Set<String> grid = new HashSet<>();
        for (ShadeCatalog.Entry e : catalog.search("grid")) grid.add(e.key);
        assertTrue(grid.contains("pref_show_grid_key"));
        assertEquals(catalog.all().size(), catalog.search("  ").size());
        // Sections come from the tree: the hybrid's Bento rows are under «Hybrid › Кадры и захват».
        ShadeCatalog.Entry bento = catalog.entry("pref_lmc_hybrid_bento");
        assertEquals("Hybrid", bento.section(context));
        assertTrue(bento.crumb(), bento.crumb().startsWith(captureSection));
        assertEquals(context.getString(R.string.shade_group_shoot), catalog.entry(ShadeCatalog.ROUTE).section(context));
    }
}
