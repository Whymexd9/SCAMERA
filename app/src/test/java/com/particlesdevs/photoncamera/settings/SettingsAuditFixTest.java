package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import androidx.preference.*;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Fixes of the settings audit (research/settings-audit/SETTINGS_AUDIT.md): broken values migrated in the main preferences and
 * every module profile, removed rows gone with their stored values, getters falling back to the XML defaults.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, qualifiers="w400dp-h880dp-mdpi")
public class SettingsAuditFixTest {
    private Context context;
    private SettingsManager manager;
    private SharedPreferences prefs;
    private MockedStatic<PhotonCamera> camera;
    @Before public void setUp(){
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Photon_SettingsActivity);
        manager=new SettingsManager(context); prefs=manager.getDefaultPreferences();prefs.edit().clear().commit();
        camera=mockStatic(PhotonCamera.class);
        camera.when(PhotonCamera::getAppContext).thenReturn(context);
        camera.when(PhotonCamera::getResourcesStatic).thenReturn(context.getResources());
        camera.when(()->PhotonCamera.getStringStatic(anyInt())).thenAnswer(inv->context.getString(inv.getArgument(0)));
        PreferenceKeys.initialise(manager);
        camera.when(PhotonCamera::getSettingsManagerStatic).thenReturn(manager);
        PhotonCamera app=mock(PhotonCamera.class,RETURNS_DEEP_STUBS);when(app.getSettingsManager()).thenReturn(manager);
        camera.when(()->PhotonCamera.getInstance(any(Context.class))).thenReturn(app);
    }
    @After public void tearDown(){
        context.getSharedPreferences("module_profiles_meta",Context.MODE_PRIVATE).edit().clear().commit();
        for(String id:new String[]{"back1","back2","common"})
            context.getSharedPreferences("module_profile_v2_"+id,Context.MODE_PRIVATE).edit().clear().commit();
        if(camera!=null)camera.close();
    }

    private PreferenceScreen inflate(){
        SettingsMigration.prepare(context,prefs);
        PreferenceManager pm=new PreferenceManager(context);
        PreferenceScreen screen=pm.inflateFromResource(context,R.xml.preferences,null);pm.setPreferences(screen);
        return screen;
    }
    /** Two module profiles and the baseline, as ModuleProfiles stores them. */
    private SharedPreferences[] profiles(){
        context.getSharedPreferences("module_profiles_meta",Context.MODE_PRIVATE).edit().clear()
                .putBoolean("exists_back1",true).putBoolean("exists_back2",true).putBoolean("baseline",true).commit();
        return new SharedPreferences[]{context.getSharedPreferences("module_profile_v2_back1",Context.MODE_PRIVATE),
                context.getSharedPreferences("module_profile_v2_back2",Context.MODE_PRIVATE),
                context.getSharedPreferences("module_profile_v2_common",Context.MODE_PRIVATE)};
    }

    /** «Фильтр Байера»: MONO broke every shot and QUAD did nothing; both left the list, stored ones become «Авто». */
    @Test public void cfaMonoAndQuadAreGoneEverywhere() {
        ListPreference cfa=inflate().findPreference("pref_cfa_key");
        assertNotNull(cfa);
        assertEquals(Arrays.asList("-1","0","3","1","2"),Arrays.asList(Arrays.stream(cfa.getEntryValues()).map(CharSequence::toString).toArray()));
        assertEquals(cfa.getEntryValues().length,cfa.getEntries().length);
        SharedPreferences[] module=profiles();
        prefs.edit().putString("pref_cfa_key","4").commit();
        module[0].edit().clear().putString("pref_cfa_key","-2").commit();
        module[1].edit().clear().putString("pref_cfa_key","2").commit();
        module[2].edit().clear().putString("pref_cfa_key","4").commit();
        SettingsMigration.removeObsolete(context,prefs);
        assertEquals("-1",prefs.getString("pref_cfa_key",""));
        assertEquals("-1",module[0].getString("pref_cfa_key",""));
        assertEquals("a forced 2x2 order stays","2",module[1].getString("pref_cfa_key",""));
        assertEquals("-1",module[2].getString("pref_cfa_key",""));
        assertFalse("a second run changes nothing",SettingsMigration.removeObsolete(prefs));
        // An imported value that skipped the migration still means «Авто» to the capture.
        prefs.edit().putString("pref_cfa_key","4").commit();assertEquals(-1,PreferenceKeys.getCFAValue());
        prefs.edit().putString("pref_cfa_key","-2").commit();assertEquals(-1,PreferenceKeys.getCFAValue());
        prefs.edit().putString("pref_cfa_key","3").commit();assertEquals(3,PreferenceKeys.getCFAValue());
    }

    /** The per-module exposure limits (ISO / shutter / balance) were read by nothing: their stored values are dropped. */
    @Test public void sensorExposureLimitsAreDropped() {
        prefs.edit().putString("pref_sensorconfig_back0_exposurebalanceisolimit","800")
                .putString("pref_sensorconfig_back0_exposurebalanceshutterlimit","0.05")
                .putString("pref_sensorconfig_2_exposurebalancemultiplier","2.0")
                .putString("pref_sensorconfig_back0_blackleveloverride","64").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        for(String key:prefs.getAll().keySet())assertFalse(key,key.contains("exposurebalance"));
        assertEquals("the other sensor rows stay","64",prefs.getString("pref_sensorconfig_back0_blackleveloverride",""));
        for(java.lang.reflect.Field f:com.particlesdevs.photoncamera.capture.CaptureController.class.getFields())
            assertFalse(f.getName(),f.getName().startsWith("exposureBalance"));
    }

    /** «Формат превью» only added an ImageReader nobody read to the session: row, stored value and shade session key go. */
    @Test public void previewFormatIsGone() {
        assertNull(inflate().findPreference("pref_preview_format_key"));
        prefs.edit().putString("pref_preview_format_key","35").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertFalse(prefs.contains("pref_preview_format_key"));
        assertFalse(ShadeCatalog.SESSION_KEYS.contains("pref_preview_format_key"));
        assertEquals(android.graphics.ImageFormat.JPEG,com.particlesdevs.photoncamera.capture.CaptureController.mPreviewTargetFormat);
    }

    /** The probe of the old vivo system remosaic path (removed in P4) is gone; the SCAM HDR and neural checks stay. */
    @Test public void vivoRemosaicProbeIsGone() {
        PreferenceScreen screen=inflate();
        assertNull(screen.findPreference("remosaic_vivo_probe"));
        assertNotNull(screen.findPreference("vivo_nice_probe"));assertNotNull(screen.findPreference("vivo_neural_probe"));
    }

    /**
     * S2: a Key getter without a default of its own falls back to the row's android:defaultValue, not to 0 / false, when its
     * key is not stored (a restored config or module snapshot without it).
     */
    @Test public void keyGettersFallBackToTheXmlDefaults() {
        prefs.edit().clear().commit();
        assertEquals(Integer.parseInt(context.getString(R.string.pref_af_mode_default_value)),PreferenceKeys.getAfMode());
        assertEquals(4,PreferenceKeys.getAfMode()); // CONTROL_AF_MODE_CONTINUOUS_PICTURE, not 0 = AF off
        assertEquals(-1,PreferenceKeys.getCFAValue()); // auto, not 0 = forced RGGB
        assertTrue(PreferenceKeys.isCameraSoundsOn());assertTrue(PreferenceKeys.isRoundEdgeOn());assertTrue(PreferenceKeys.isShowWatermarkOn());
        assertEquals(2,PreferenceKeys.getFocusPeakValue());assertEquals(1,PreferenceKeys.getColorMethodValue());
        assertEquals(-1,PreferenceKeys.getThemeValue());
        assertTrue(PreferenceKeys.isRemosaicSteered());assertTrue(PreferenceKeys.isRemosaicClampDiffs());assertEquals(2,PreferenceKeys.getRemosaicProfile());
        assertTrue(PreferenceKeys.isSharpUsmEnabled());assertTrue(PreferenceKeys.isSharpHaloControl());
        assertEquals(0.5f,PreferenceKeys.getSharpRadius(),0f);assertEquals(75f,PreferenceKeys.getSharpAmount(),0f);
        assertEquals(1800,PreferenceKeys.getSharpEdgesTolerance());assertEquals(0.4f,PreferenceKeys.getSharpDeconvHaloMacro(),1e-6f);
        // Every Key whose row declares a default reads that default (switches as "1" / "0").
        Map<String,String> xml=XmlDefaults.read(context);
        int checked=0;
        for(PreferenceKeys.Key k:PreferenceKeys.Key.values()){
            String d=xml.get(k.mValue);
            if(d==null)continue;
            assertEquals(k.name(),d,manager.getStringDefault(k));
            checked++;
        }
        assertTrue(checked>40);
        assertEquals("1",xml.get("pref_camera_sounds_key"));assertEquals("0",xml.get("pref_horizon"));
        // A stored value still wins.
        prefs.edit().putString("pref_af_mode_key","1").putBoolean("pref_camera_sounds_key",false).commit();
        assertEquals(1,PreferenceKeys.getAfMode());assertFalse(PreferenceKeys.isCameraSoundsOn());
    }

    /** S1 (owner): the shared settings are one list, never per module; processing / tuning / sensor settings stay per module. */
    @Test public void sharedSettingsAreNeverPerModule() {
        String[] global={PreferenceKeys.ROUTE_KEY,"pref_camera_sounds_key","pref_timer_sound_key","pref_show_grid_key","pref_photo_format",
                "pref_jpeg_quality","pref_heic_quality","pref_heic_10bit","pref_webp_quality","pref_webp_lossless","pref_photo_also_jpeg",
                "pref_avif_quality","pref_save_raw_key","pref_ultrahdr_key","pref_show_watermark_key","pref_watermark_line1",
                "pref_watermark_line2","pref_watermark_logo","pref_watermark_size","pref_watermark_opacity","pref_root_enabled",
                "pref_camera_package_spoof_enabled","pref_oplus_spoof_package_key","pref_generic_spoof_package_key",
                "pref_binder_spoof_package_key","pref_face_detect_mode","pref_tracking_af_mode","pref_hide_gallery_icon_key",
                "pref_theme_key","pref_show_gradient_key","pref_antibanding_hz_key"};
        for(String key:global){assertTrue(key,ModuleProfiles.isGlobal(key));assertFalse(key,ModuleProfiles.isLocal(key));}
        for(String key:new String[]{"pref_lmc_hybrid_cdm","pref_vivo_nice_luma","pref_sharp_radius_key","pref_cfa_key","pref_dng_lossless",
                "pref_lmc_tone_curve","pref_raw_stream_format","hexquad_luma"})
            assertTrue(key,ModuleProfiles.isLocal(key));
        // Every listed key is a real row (the RAW save mode is the virtual «Формат» of the top bar and the shade).
        PreferenceScreen screen=inflate();
        for(String key:ModuleProfiles.GLOBAL_KEYS)
            if(!key.equals(ShadeCatalog.FORMAT))assertNotNull(key,screen.findPreference(key));
        // Old per-module copies leave the module profiles and the baseline; the main settings keep their value.
        SharedPreferences[] module=profiles();
        prefs.edit().putString(PreferenceKeys.ROUTE_KEY,"scamhdr").putBoolean("pref_camera_sounds_key",false).commit();
        for(SharedPreferences p:module)p.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putBoolean("pref_camera_sounds_key",true)
                .putString("pref_watermark_line1","OLD").putString("pref_lmc_hybrid_cdm","0.5").commit();
        SettingsMigration.removeObsolete(context,prefs);
        for(SharedPreferences p:module){
            assertFalse(p.contains(PreferenceKeys.ROUTE_KEY));assertFalse(p.contains("pref_camera_sounds_key"));assertFalse(p.contains("pref_watermark_line1"));
            assertEquals("per-module tuning stays","0.5",p.getString("pref_lmc_hybrid_cdm",""));
        }
        assertEquals("scamhdr",prefs.getString(PreferenceKeys.ROUTE_KEY,""));assertFalse(prefs.getBoolean("pref_camera_sounds_key",true));
    }

    /** S1: with per-lens settings on, a lens switch swaps the tuning but keeps the shared settings. */
    @Test public void lensSwitchKeepsSharedSettings() {
        for(int i=0;i<2;i++)prefs.edit().putString("module_auto_back"+i,""+(3+i)).putString("module_label_back"+i,new String[]{"1×","0.6×"}[i])
                .putBoolean("module_visible_back"+i,true).commit();
        prefs.edit().putString("module_active","back0").putString(PreferenceKeys.ROUTE_KEY,"hybrid").putString("pref_lmc_hybrid_cdm","0.5")
                .putString("pref_watermark_line1","ONE").commit();
        String perLens=PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue;
        prefs.edit().putBoolean(perLens,true).commit();PreferenceKeys.profiles().changed(perLens);
        PreferenceKeys.profiles().activate("back1");
        prefs.edit().putString(PreferenceKeys.ROUTE_KEY,"scamhdr").putString("pref_lmc_hybrid_cdm","0.9").putString("pref_watermark_line1","TWO").commit();
        PreferenceKeys.profiles().changed("pref_lmc_hybrid_cdm");PreferenceKeys.profiles().changed(PreferenceKeys.ROUTE_KEY);
        PreferenceKeys.profiles().activate("back0");
        assertEquals("0.5",prefs.getString("pref_lmc_hybrid_cdm",""));
        assertEquals("scamhdr",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        assertEquals("TWO",prefs.getString("pref_watermark_line1",""));
        assertFalse(PreferenceKeys.profiles().snapshot("back1").containsKey(PreferenceKeys.ROUTE_KEY));
        assertEquals("0.9",String.valueOf(PreferenceKeys.profiles().snapshot("back1").get("pref_lmc_hybrid_cdm")));
    }

    /** Owner: the upstream PhotonCamera rows (about page, contributors, Telegram, device list, config download) are gone. */
    @Test public void upstreamRowsAreGone() {
        PreferenceScreen screen=inflate();
        for(String key:new String[]{"pref_about_key","pref_contributors_key","pref_telegram_channel_key","all_devices_names",
                "pref_fetch_configurations_key"})
            assertNull(key,screen.findPreference(key));
        PreferenceScreen system=screen.findPreference("system_settings_screen");
        for(String key:new String[]{"pref_photoncamera","pref_version_key","pref_this_device_key"})
            assertNotNull("the app, version and device rows stay on «Система»: "+key,system.findPreference(key));
    }

    /** Owner: SCAM HDR saves no processing stages and plans with SCAMERA by default; stored former defaults move once. */
    @Test public void scamHdrDefaultsAreDiagnosticsOffAndTheScameraPlanner() {
        Map<String,String> xml=XmlDefaults.read(context);
        assertEquals("0",xml.get("pref_vivo_nice_diagnostics"));assertEquals("scamera",xml.get("pref_vivo_nice_planner"));
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"scamhdr").commit();
        assertFalse(PreferenceKeys.isNiceDiagnosticsEnabled());
        prefs.edit().putBoolean("pref_root_enabled",true).commit();
        assertFalse("no stock planner without the stored choice",PreferenceKeys.useStockBracketPlanner());
        SharedPreferences[] module=profiles();
        prefs.edit().putBoolean("pref_vivo_nice_diagnostics",true).putString("pref_vivo_nice_planner","stock").commit();
        module[0].edit().clear().putString("pref_vivo_nice_diagnostics","1").putString("pref_vivo_nice_planner","stock").commit();
        module[1].edit().clear().putInt(SettingsMigration.SCAM_DEFAULTS_REV,1).putBoolean("pref_vivo_nice_diagnostics",true).commit();
        SettingsMigration.migrateLmcHybrid(context,prefs);
        assertFalse(prefs.getBoolean("pref_vivo_nice_diagnostics",true));assertEquals("scamera",prefs.getString("pref_vivo_nice_planner",""));
        assertFalse(module[0].getBoolean("pref_vivo_nice_diagnostics",true));assertEquals("scamera",module[0].getString("pref_vivo_nice_planner",""));
        assertTrue("a profile that already moved keeps a later choice",module[1].getBoolean("pref_vivo_nice_diagnostics",false));
        prefs.edit().putBoolean("pref_vivo_nice_diagnostics",true).putString("pref_vivo_nice_planner","stock").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertTrue(prefs.getBoolean("pref_vivo_nice_diagnostics",false));assertEquals("stock",prefs.getString("pref_vivo_nice_planner",""));
    }

    /** Without the 8 Elite there is no SCAM HDR: no route tile among the default tiles, no SCAM HDR / neural checks. */
    @Test public void noScamHdrChecksOrRouteTileWithoutThe8Elite() {
        assertTrue(ShadeCatalog.defaultTiles().contains(ShadeCatalog.ROUTE));
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8650");
        assertFalse(ShadeCatalog.defaultTiles().contains(ShadeCatalog.ROUTE));
        assertEquals(ShadeCatalog.DEFAULT_TILES.size()-1,ShadeCatalog.defaultTiles().size());
        assertFalse(ShadeTiles.load(prefs,key->true).contains(ShadeCatalog.ROUTE));
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();
            PreferenceFragmentCompat page=(PreferenceFragmentCompat)controller.get().getSupportFragmentManager().findFragmentById(R.id.settings_container);
            for(String key:new String[]{"vivo_nice_probe","vivo_neural_probe","vivo_diagnostics_screen","vivo_hdr_screen"})
                assertFalse(key,page.findPreference(key).isVisible());
            assertTrue(page.findPreference("pref_root_enabled").isVisible());
        }
    }

    /** The settings screen and the shade see the same device facts (DeviceAvailability). */
    @Test public void deviceFactsReachTheAvailability() {
        Map<String,Object> values=new HashMap<>();values.put(PreferenceKeys.ROUTE_KEY,"scamhdr");values.put("pref_root_enabled",true);
        // Robolectric is no vivo X200 Ultra: the stock planner is explained; no OPPO / vivo matrix replaces the colour method.
        assertNotNull(DeviceAvailability.of(values).reason("pref_vivo_nice_planner"));
        assertNull(DeviceAvailability.of(values).reason("pref_color_method_key"));
        org.robolectric.shadows.ShadowBuild.setManufacturer("vivo");
        assertNotNull(DeviceAvailability.of(values).reason("pref_color_method_key"));
        org.robolectric.shadows.ShadowBuild.setManufacturer("OPPO");org.robolectric.shadows.ShadowBuild.setModel("PHY110");
        assertNotNull(DeviceAvailability.of(values).reason("pref_color_method_key"));
    }

    /** H1: a decimal slider stores as many decimals as its step needs, and the screen seeds the exact XML default. */
    @Test public void slidersKeepTheirPrecision() {
        assertEquals("0.0005",PreferenceNumber.gridText(5/10000.0,PreferenceNumber.gridDecimals(10000,0)));
        assertEquals("0.001",PreferenceNumber.gridText(0.001,PreferenceNumber.gridDecimals(100,0.001f)));
        assertEquals("0.011",PreferenceNumber.gridText(1/100.0+0.001,PreferenceNumber.gridDecimals(100,0.001f)));
        assertEquals("0.50",PreferenceNumber.gridText(0.5,PreferenceNumber.gridDecimals(100,0)));
        assertEquals("0.20",PreferenceNumber.gridText(0.2,PreferenceNumber.gridDecimals(1000,0.01f)));
        inflate();
        assertEquals("0.0005",prefs.getString("pref_lmc_hybrid_bento_trigger",""));
        assertEquals("1.414",prefs.getString("pref_lmc_hybrid_lut_sigma",""));
        assertEquals(0.0005f,PreferenceKeys.hybridValue("bento_trigger",0.0005f),1e-9f);
    }

    /** H1: the "0.00" / "1.41" the old sliders seeded move to 0.0005 / 1.414 once, in every profile; later choices stay. */
    @Test public void roundedSliderDefaultsMoveOnce() {
        SharedPreferences[] module=profiles();
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putString("pref_lmc_hybrid_bento_trigger","0.00")
                .putString("pref_lmc_hybrid_lut_sigma","1.41").commit();
        module[0].edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putString("pref_lmc_hybrid_bento_trigger","0.00").commit();
        module[1].edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putString("pref_lmc_hybrid_bento_trigger","0.01")
                .putString("pref_lmc_hybrid_lut_sigma","1.50").commit();
        module[2].edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putString("pref_lmc_hybrid_lut_sigma","1.41").commit();
        SettingsMigration.migrateLmcHybrid(context,prefs);
        assertEquals("0.0005",prefs.getString("pref_lmc_hybrid_bento_trigger",""));
        assertEquals("1.414",prefs.getString("pref_lmc_hybrid_lut_sigma",""));
        assertEquals("0.0005",module[0].getString("pref_lmc_hybrid_bento_trigger",""));
        assertEquals("a chosen value stays","0.01",module[1].getString("pref_lmc_hybrid_bento_trigger",""));
        assertEquals("1.50",module[1].getString("pref_lmc_hybrid_lut_sigma",""));
        assertEquals("1.414",module[2].getString("pref_lmc_hybrid_lut_sigma",""));
        // A 0.00 chosen after the move is the user's.
        prefs.edit().putString("pref_lmc_hybrid_bento_trigger","0.00").commit();
        assertFalse(SettingsMigration.migrateLmcHybrid(prefs,false));
        assertEquals("0.00",prefs.getString("pref_lmc_hybrid_bento_trigger",""));
    }
}
