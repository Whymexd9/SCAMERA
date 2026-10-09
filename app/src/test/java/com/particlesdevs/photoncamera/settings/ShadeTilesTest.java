package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;

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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** P25: the shade's tile list in ui_shade_tiles, its migration from the old pins and favourites, the config file. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ShadeTilesTest {
    private Context context;
    private SharedPreferences prefs;
    private MockedStatic<PhotonCamera> camera;
    private ShadeCatalog catalog;

    @Before
    public void setUp() {
        // SCAM HDR and its settings exist only on the Snapdragon 8 Elite; these tests cover both routes.
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Photon_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(PhotonCamera.class);
        camera.when(PhotonCamera::getAppContext).thenReturn(context);
        camera.when(PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        camera.when(PhotonCamera::getSettingsManagerStatic).thenReturn(manager);
        camera.when(PhotonCamera::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        PhotonCamera app = mock(PhotonCamera.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> PhotonCamera.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
        catalog = new ShadeCatalog(context, prefs);
    }

    @After
    public void tearDown() {
        camera.close();
    }

    private List<String> load() {
        return ShadeTiles.load(prefs, catalog::isKnown);
    }

    @Test
    public void defaultsUntilSomethingIsStoredAndEmptyMeansNoTile() {
        assertNull(ShadeTiles.stored(prefs));
        assertEquals(ShadeCatalog.DEFAULT_TILES, load());
        ShadeTiles.save(prefs, new ArrayList<>());
        assertEquals("", prefs.getString(ShadeTiles.KEY, null));
        assertTrue(load().isEmpty());
        // The list is a ui_ key: not per module, so it never follows the lens.
        assertFalse(ModuleProfiles.isLocal(ShadeTiles.KEY));
    }

    @Test
    public void orderSurvivesSaveAndLoad() {
        List<String> order = Arrays.asList("pref_scam_hybrid_cdm", ShadeCatalog.FORMAT, "pref_show_grid_key", ShadeCatalog.FLASH,
                "pref_scamhdr_long_boost_ev", "pref_scam_hybrid_post_luma");
        ShadeTiles.save(prefs, order);
        assertEquals(order, ShadeTiles.stored(prefs));
        assertEquals(order, load());
        List<String> reversed = new ArrayList<>(order);
        java.util.Collections.reverse(reversed);
        ShadeTiles.save(prefs, reversed);
        assertEquals(reversed, load());
    }

    @Test
    public void unknownRepeatedAndExtraKeysAreDroppedOnReadAtMostTwelve() {
        List<String> stored = new ArrayList<>(Arrays.asList("not_a_setting", ShadeCatalog.FLASH, ShadeCatalog.FLASH,
                "pref_sensorconfig_0_mode", "pref_dcp_profile_key"));
        for (ShadeCatalog.Group group : ShadeCatalog.GROUPS) stored.addAll(group.keys);
        prefs.edit().putString(ShadeTiles.KEY, String.join(",", stored)).commit();
        List<String> tiles = load();
        assertEquals(ShadeCatalog.MAX_TILES, tiles.size());
        assertEquals(ShadeCatalog.FLASH, tiles.get(0));
        assertEquals(new java.util.HashSet<>(tiles).size(), tiles.size());
        for (String key : tiles) assertTrue(key, catalog.isKnown(key));
        // The first twelve known keys, in the stored order.
        List<String> expected = new ArrayList<>();
        for (String key : stored) if (catalog.isKnown(key) && !expected.contains(key) && expected.size() < 12) expected.add(key);
        assertEquals(expected, tiles);
    }

    @Test
    public void removeObsoleteCleansTheTileList() {
        prefs.edit().putString(ShadeTiles.KEY, "pref_show_grid_key,pref_scam_hybrid_ark_tone,pref_agx_contrast," + ShadeCatalog.TIMER).commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertEquals(Arrays.asList("pref_show_grid_key", ShadeCatalog.TIMER), ShadeTiles.stored(prefs));
        assertFalse(SettingsMigration.removeObsolete(prefs));
    }

    @Test
    public void oldPinsThenFavouritesThenTheDefaultsBecomeTheTiles() {
        prefs.edit().putString(SettingsMigration.LEGACY_QUICK, "GRID,HDRX,HYBRID_OUTPUT,QUAD,AE_METERING_STD")
                .putString(SettingsMigration.LEGACY_FAVOURITES, "[\"pref_scam_hybrid_cdm\",\"pref_agx_contrast\",\"pref_show_grid_key\",\"pref_scamhdr_mosaic\"]")
                .commit();
        assertTrue(SettingsMigration.migrateShadeTiles(prefs));
        assertFalse(prefs.contains(SettingsMigration.LEGACY_QUICK));
        assertFalse(prefs.contains(SettingsMigration.LEGACY_FAVOURITES));
        List<String> expected = new ArrayList<>(Arrays.asList("pref_show_grid_key", "pref_scam_hybrid_output",
                ShadeCatalog.METERING_STD, "pref_scam_hybrid_cdm", "pref_scamhdr_mosaic"));
        for (String key : ShadeCatalog.DEFAULT_TILES) if (!expected.contains(key)) expected.add(key);
        assertEquals(expected, ShadeTiles.stored(prefs));
        assertEquals(11, expected.size());
        assertEquals(expected, load());
        // Once only.
        assertFalse(SettingsMigration.migrateShadeTiles(prefs));
        // A stored tile list is never replaced; the old keys still go.
        ShadeTiles.save(prefs, Arrays.asList(ShadeCatalog.TIMER));
        prefs.edit().putString(SettingsMigration.LEGACY_QUICK, "FLASH").commit();
        assertTrue(SettingsMigration.migrateShadeTiles(prefs));
        assertEquals(Arrays.asList(ShadeCatalog.TIMER), ShadeTiles.stored(prefs));
        assertFalse(prefs.contains(SettingsMigration.LEGACY_QUICK));
        // Both old keys are obsolete afterwards, wherever they are left.
        assertTrue(SettingsMigration.isObsolete(SettingsMigration.LEGACY_QUICK));
        assertTrue(SettingsMigration.isObsolete(SettingsMigration.LEGACY_FAVOURITES));
    }

    @Test
    public void configFileKeepsTheTileOrderOnThisAndAnotherPhone() throws Exception {
        List<String> order = Arrays.asList("pref_scam_hybrid_post_luma", ShadeCatalog.TIMER, "pref_show_grid_key", ShadeCatalog.ROUTE);
        ShadeTiles.save(prefs, order);
        prefs.edit().commit();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConfigXml.write(out, BackupRestoreUtil.header(RuntimeEnvironment.getApplication()), BackupRestoreUtil.collect(RuntimeEnvironment.getApplication()));
        ConfigXml.Config config = ConfigXml.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(String.join(",", order), config.files.get(ConfigXml.MAIN).get(ShadeTiles.KEY));
        // Same phone.
        ShadeTiles.save(prefs, Arrays.asList(ShadeCatalog.FLASH));
        BackupRestoreUtil.apply(RuntimeEnvironment.getApplication(), config);
        assertEquals(order, ShadeTiles.stored(prefs));
        // Another phone: the main settings travel, the tiles with them.
        ShadeTiles.save(prefs, Arrays.asList(ShadeCatalog.FLASH));
        config.attributes.put("device", "oppo/op627cl1");
        BackupRestoreUtil.apply(RuntimeEnvironment.getApplication(), config);
        assertEquals(order, ShadeTiles.stored(prefs));
        // A config from a build before the shade: its pins become the tiles.
        config.files.get(ConfigXml.MAIN).remove(ShadeTiles.KEY);
        config.files.get(ConfigXml.MAIN).put(SettingsMigration.LEGACY_QUICK, "RAW");
        BackupRestoreUtil.apply(RuntimeEnvironment.getApplication(), config);
        List<String> migrated = ShadeTiles.stored(prefs);
        assertNotNull(migrated);
        assertEquals(ShadeCatalog.FORMAT, migrated.get(0));
        assertFalse(prefs.contains(SettingsMigration.LEGACY_QUICK));
    }
}
