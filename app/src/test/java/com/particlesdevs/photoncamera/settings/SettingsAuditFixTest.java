package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import androidx.preference.*;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
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
    private MockedStatic<ScameraApp> camera;
    @Before public void setUp(){
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Scamera_SettingsActivity);
        manager=new SettingsManager(context); prefs=manager.getDefaultPreferences();prefs.edit().clear().commit();
        camera=mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(()->ScameraApp.getStringStatic(anyInt())).thenAnswer(inv->context.getString(inv.getArgument(0)));
        PreferenceKeys.initialise(manager);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        ScameraApp app=mock(ScameraApp.class,RETURNS_DEEP_STUBS);when(app.getSettingsManager()).thenReturn(manager);
        camera.when(()->ScameraApp.getInstance(any(Context.class))).thenReturn(app);
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

    /**
     * The probe of the old vivo system remosaic path (removed in P4), the SCAM HDR check (P129) and the neural remosaic's check
     * (P175) are gone; the last SHDR capture report stays.
     */
    @Test public void scamRemosaicProbeIsGone() {
        PreferenceScreen screen=inflate();
        assertNull(screen.findPreference("remosaic_scam_probe"));
        assertNull(screen.findPreference("scam_probe"));assertNull(screen.findPreference("scam_neural_probe"));
        assertNotNull(screen.findPreference("shdr_capture_report"));
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
        // Every Key whose row declares a default reads that default (switches as "1" / "0").
        Map<String,String> xml=XmlDefaults.read(context);
        int checked=0;
        for(PreferenceKeys.Key k:PreferenceKeys.Key.values()){
            String d=xml.get(k.mValue);
            if(d==null)continue;
            assertEquals(k.name(),d,manager.getStringDefault(k));
            checked++;
        }
        // 17: 24 since P118 removed the 37 RawTherapee sharpening keys, then P175 the 7 GPU remosaic keys of the neural remosaic
        assertTrue(String.valueOf(checked),checked>=17);
        assertEquals("1",xml.get("pref_camera_sounds_key"));assertEquals("0",xml.get("pref_horizon"));
        // A stored value still wins.
        prefs.edit().putString("pref_af_mode_key","1").putBoolean("pref_camera_sounds_key",false).commit();
        assertEquals(1,PreferenceKeys.getAfMode());assertFalse(PreferenceKeys.isCameraSoundsOn());
    }

    /** P118: «Частота мерцания» is a list; the old default 120 becomes «Авто» once, another number the nearest entry. */
    @Test public void antibandingBecomesAList() {
        assertEquals("-1",SettingsMigration.antibandingEntry(120));assertEquals("100",SettingsMigration.antibandingEntry(50));
        assertEquals("120",SettingsMigration.antibandingEntry(60));assertEquals("100",SettingsMigration.antibandingEntry(100));
        assertEquals("0",SettingsMigration.antibandingEntry(0));assertEquals("120",SettingsMigration.antibandingEntry(130));
        prefs.edit().clear().putString("pref_antibanding_hz_key","120").commit();
        SettingsMigration.migrateAntibanding(prefs);
        assertEquals("-1",prefs.getString("pref_antibanding_hz_key",null));
        prefs.edit().putString("pref_antibanding_hz_key","120").commit();
        SettingsMigration.migrateAntibanding(prefs);
        assertEquals("a later choice of 120 stays","120",prefs.getString("pref_antibanding_hz_key",null));
        assertEquals(120,PreferenceKeys.getAntibandingHz());
        prefs.edit().putString("pref_antibanding_hz_key","0").commit();
        assertEquals(0,PreferenceKeys.getAntibandingHz());
    }

    /**
     * Audit 2026-10-27: the reset of a fresh install (and of the full reset) cleared the one-time markers right after SettingsManager
     * wrote them, so the next start migrated the stored «Авто» (-1) into «Выкл.» (0) and a first-session 60 Hz (120) into «Авто»;
     * the device defaults were missing for the whole first session.
     */
    @Test public void freshInstallKeepsAutoFlickerAndMarkers() {
        assertEquals("-1",SettingsMigration.antibandingEntry(-1));
        for(boolean fullReset:new boolean[]{false,true})
            for(String chosen:new String[]{"-1","120","100","0"}){
                if(fullReset)BackupRestoreUtil.resetPreferences(context);
                else{
                    context.getSharedPreferences(context.getPackageName()+PreferenceKeys.Key.KEY_PREF_VERSION.mValue,Context.MODE_PRIVATE)
                            .edit().clear().commit();
                    prefs.edit().clear().commit();
                }
                boolean readAgain=MigrationManager.readAgain;
                try{MigrationManager.migrate(new SettingsManager(context));}finally{MigrationManager.readAgain=readAgain;}
                for(String marker:new String[]{"settings_antibanding_list_v1","settings_zsl_capacity_v2",SettingsMigration.SIMPLE_UI_TILES,"device_defaults_version"})
                    assertTrue(marker,prefs.contains(marker));
                assertTrue("the device defaults (SM8750) from the first session on",prefs.getBoolean("pref_live_viewfinder_raw_key",false));
                PreferenceManager.setDefaultValues(context,R.xml.preferences,true);
                assertEquals("the XML default is «Авто»","-1",prefs.getString("pref_antibanding_hz_key",null));
                prefs.edit().putString("pref_antibanding_hz_key",chosen).commit(); // the first session's choice
                new SettingsManager(context); // the next start
                assertEquals((fullReset?"after the full reset: ":"fresh install: ")+chosen,chosen,prefs.getString("pref_antibanding_hz_key",null));
            }
    }

    /** The same reset of a fresh install: the Find X7 Ultra's camera package spoof (5 cameras) from the first session on. */
    @Test public void freshInstallHasTheDeviceDefaultsAtOnce() {
        org.robolectric.shadows.ShadowBuild.setManufacturer("OPPO");
        org.robolectric.shadows.ShadowBuild.setModel("PHY110");
        context.getSharedPreferences(context.getPackageName()+PreferenceKeys.Key.KEY_PREF_VERSION.mValue,Context.MODE_PRIVATE).edit().clear().commit();
        prefs.edit().clear().commit();
        boolean readAgain=MigrationManager.readAgain;
        try{MigrationManager.migrate(new SettingsManager(context));}finally{MigrationManager.readAgain=readAgain;}
        assertTrue(prefs.getBoolean("pref_camera_package_spoof_enabled",false));
        assertEquals("raw10",prefs.getString("pref_raw_stream_format",null));
        // a choice of the first session survives the next start
        prefs.edit().putBoolean("pref_camera_package_spoof_enabled",false).putString("pref_zsl_buffer_count_key","30").commit();
        new SettingsManager(context);
        assertFalse(prefs.getBoolean("pref_camera_package_spoof_enabled",true));
        assertEquals("30",prefs.getString("pref_zsl_buffer_count_key",null));
    }

    /** S1 (owner): the shared settings are one list, never per module; processing / tuning / sensor settings stay per module. */
    @Test public void sharedSettingsAreNeverPerModule() {
        String[] global={PreferenceKeys.ROUTE_KEY,"pref_camera_sounds_key","pref_timer_sound_key","pref_show_grid_key","pref_photo_format",
                "pref_jpeg_quality","pref_heic_quality","pref_heic_10bit","pref_webp_quality","pref_webp_lossless","pref_photo_also_jpeg",
                "pref_photo_color_space",
                "pref_avif_quality","pref_save_raw_key","pref_ultrahdr_key","pref_show_watermark_key","pref_watermark_line1",
                "pref_watermark_line2","pref_watermark_logo","pref_watermark_size","pref_watermark_opacity","pref_root_enabled",
                "pref_camera_package_spoof_enabled","pref_oplus_spoof_package_key","pref_generic_spoof_package_key",
                "pref_binder_spoof_package_key","pref_face_detect_mode","pref_tracking_af_mode","pref_hide_gallery_icon_key",
                "pref_theme_key","pref_antibanding_hz_key"};
        for(String key:global){assertTrue(key,ModuleProfiles.isGlobal(key));assertFalse(key,ModuleProfiles.isLocal(key));}
        for(String key:new String[]{"pref_scam_hybrid_cdm","pref_scam_hybrid_why_sharp_gain","pref_cfa_key","pref_dng_lossless",
                "pref_dcp_profile_key","pref_raw_stream_format","pref_film_lut_strength"})
            assertTrue(key,ModuleProfiles.isLocal(key));
        // P175: the neural remosaic's network rows are no module setting any more
        for(String key:new String[]{"hexquad_luma","quad2x2_luma"})assertFalse(key,ModuleProfiles.isLocal(key));
        // Every listed key is a real row (the RAW save mode is the virtual «Формат» of the top bar and the shade; the route is the
        // migrations' marker since P129: SHDR is the only merge, the row is gone).
        PreferenceScreen screen=inflate();
        for(String key:ModuleProfiles.GLOBAL_KEYS)
            if(!key.equals(ShadeCatalog.FORMAT)&&!key.equals(PreferenceKeys.ROUTE_KEY))assertNotNull(key,screen.findPreference(key));
        // Old per-module copies leave the module profiles and the baseline; the main settings keep their value.
        SharedPreferences[] module=profiles();
        prefs.edit().putString(PreferenceKeys.ROUTE_KEY,"scamhdr").putBoolean("pref_camera_sounds_key",false).commit();
        for(SharedPreferences p:module)p.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putBoolean("pref_camera_sounds_key",true)
                .putString("pref_watermark_line1","OLD").putString("pref_scam_hybrid_cdm","0.5").commit();
        SettingsMigration.removeObsolete(context,prefs);
        for(SharedPreferences p:module){
            assertFalse(p.contains(PreferenceKeys.ROUTE_KEY));assertFalse(p.contains("pref_camera_sounds_key"));assertFalse(p.contains("pref_watermark_line1"));
            assertEquals("per-module tuning stays","0.5",p.getString("pref_scam_hybrid_cdm",""));
        }
        assertEquals("scamhdr",prefs.getString(PreferenceKeys.ROUTE_KEY,""));assertFalse(prefs.getBoolean("pref_camera_sounds_key",true));
    }

    /** S1: with per-lens settings on, a lens switch swaps the tuning but keeps the shared settings. */
    @Test public void lensSwitchKeepsSharedSettings() {
        for(int i=0;i<2;i++)prefs.edit().putString("module_auto_back"+i,""+(3+i)).putString("module_label_back"+i,new String[]{"1×","0.6×"}[i])
                .putBoolean("module_visible_back"+i,true).commit();
        prefs.edit().putString("module_active","back0").putString(PreferenceKeys.ROUTE_KEY,"hybrid").putString("pref_scam_hybrid_cdm","0.5")
                .putString("pref_watermark_line1","ONE").commit();
        String perLens=PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue;
        prefs.edit().putBoolean(perLens,true).commit();PreferenceKeys.profiles().changed(perLens);
        PreferenceKeys.profiles().activate("back1");
        prefs.edit().putString(PreferenceKeys.ROUTE_KEY,"scamhdr").putString("pref_scam_hybrid_cdm","0.9").putString("pref_watermark_line1","TWO").commit();
        PreferenceKeys.profiles().changed("pref_scam_hybrid_cdm");PreferenceKeys.profiles().changed(PreferenceKeys.ROUTE_KEY);
        PreferenceKeys.profiles().activate("back0");
        assertEquals("0.5",prefs.getString("pref_scam_hybrid_cdm",""));
        assertEquals("scamhdr",prefs.getString(PreferenceKeys.ROUTE_KEY,""));
        assertEquals("TWO",prefs.getString("pref_watermark_line1",""));
        assertFalse(PreferenceKeys.profiles().snapshot("back1").containsKey(PreferenceKeys.ROUTE_KEY));
        assertEquals("0.9",String.valueOf(PreferenceKeys.profiles().snapshot("back1").get("pref_scam_hybrid_cdm")));
    }

    /**
     * P156 / audit 2026-10-27: while the restart after an import or a reset waits for a shot, a lens switch moves no profile:
     * the written main settings stay, and the old module's file does not take them.
     */
    @Test public void lensSwitchWaitsForTheRestart() {
        for(int i=0;i<2;i++)prefs.edit().putString("module_auto_back"+i,""+(3+i)).putString("module_label_back"+i,new String[]{"1×","0.6×"}[i])
                .putBoolean("module_visible_back"+i,true).commit();
        prefs.edit().putString("module_active","back0").putString("pref_scam_hybrid_cdm","0.5").commit();
        String perLens=PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue;
        prefs.edit().putBoolean(perLens,true).commit();PreferenceKeys.profiles().changed(perLens);
        PreferenceKeys.profiles().activate("back1");
        prefs.edit().putString("pref_scam_hybrid_cdm","0.7").commit();PreferenceKeys.profiles().changed("pref_scam_hybrid_cdm");
        PreferenceKeys.profiles().activate("back0");
        assertEquals("0.5",prefs.getString("pref_scam_hybrid_cdm",""));
        // an imported config wrote the main settings; the restart waits for the shot being processed
        prefs.edit().putString("pref_scam_hybrid_cdm","0.9").commit();
        ScameraApp.restarting=true;
        try{
            PreferenceKeys.profiles().activate("back1");
            assertEquals("back0",PreferenceKeys.profiles().editing());
            assertEquals("0.9",prefs.getString("pref_scam_hybrid_cdm",""));
            assertEquals("the old module's file keeps its values","0.5",
                    context.getSharedPreferences("module_profile_v2_back0",Context.MODE_PRIVATE).getString("pref_scam_hybrid_cdm",""));
            assertEquals("0.7",context.getSharedPreferences("module_profile_v2_back1",Context.MODE_PRIVATE).getString("pref_scam_hybrid_cdm",""));
        }finally{ScameraApp.restarting=false;}
    }

    /** Owner: the upstream rows (about page, contributors, Telegram, device list, config download) are gone. */
    @Test public void upstreamRowsAreGone() {
        PreferenceScreen screen=inflate();
        for(String key:new String[]{"pref_about_key","pref_contributors_key","pref_telegram_channel_key","all_devices_names",
                "pref_fetch_configurations_key"})
            assertNull(key,screen.findPreference(key));
        androidx.preference.PreferenceGroup about=screen.findPreference("root_about");
        for(String key:new String[]{"pref_app_key","pref_version_key","scam_recent_messages"})
            assertNotNull("SIMPLE_UI §4: the app, version (with the device) and messages rows are «О приложении»: "+key,about.findPreference(key));
        assertNull("the device is on the version row",screen.findPreference("pref_this_device_key"));
        // P286: the film data's credit (CC BY-SA 4.0 asks for it in the app)
        assertNotNull(about.findPreference("film_data_credit"));
        assertEquals("app, version, film data credit, messages",4,about.getPreferenceCount());
    }

    /** P129: SCAM HDR is gone: its settings (and the L frame / stock planner keys) are obsolete everywhere, the route row too. */
    @Test public void scamHdrSettingsAreObsolete() {
        for(String key:new String[]{"pref_scamhdr_diagnostics","pref_scamhdr_planner","pref_scamhdr_luma_radius","pref_scamhdr_mosaic",
                "pref_scamold_zsl_long","pref_scamold_fast_capture"})
            assertTrue(key,SettingsMigration.isObsolete(key));
        // P175: the neural remosaic is gone too: SCAM HDR's neural mosaic mode no longer becomes a switch, and its switch, the
        // marker of that P129 migration, the GPU remosaic rows and the HexQuad / Quad 2x2 network rows are obsolete
        for(String key:new String[]{"pref_scam_hybrid_neural_remosaic","pref_scam_hybrid_neural_remosaic_rev","pref_tetra_response_key",
                "pref_remosaic_block_key","pref_remosaic_profile_key","pref_remosaic_steered_key","pref_remosaic_clamp_key",
                "pref_remosaic_flatfield_key","pref_remosaic_phase_x_key","pref_remosaic_phase_y_key","hexquad_luma","hexquad_model",
                "quad2x2_luma","quad2x2_auto_iso"})
            assertTrue(key,SettingsMigration.isObsolete(key));
        assertFalse("the remosaic switch api/Settings reads stays",SettingsMigration.isObsolete("pref_remosaic_key"));
        prefs.edit().clear().putString("pref_scamhdr_mosaic","neural").putString(PreferenceKeys.ROUTE_KEY,"scamhdr").commit();
        SettingsMigration.migrateScamHybrid(prefs,false);
        assertFalse("SCAM HDR's neural mosaic mode becomes no switch",prefs.contains("pref_scam_hybrid_neural_remosaic"));
        prefs.edit().putBoolean("pref_scam_hybrid_neural_remosaic",true).putInt("pref_scam_hybrid_neural_remosaic_rev",1)
                .putString("hexquad_luma","40").putString("pref_remosaic_block_key","2").commit();
        SettingsMigration.removeObsolete(prefs);
        for(String key:new String[]{"pref_scamhdr_mosaic","pref_scam_hybrid_neural_remosaic","pref_scam_hybrid_neural_remosaic_rev",
                "hexquad_luma","pref_remosaic_block_key"})
            assertFalse(key,prefs.contains(key));
    }

    /**
     * P129: no route tile. P175: the neural remosaic page and its check are gone, even on the 8 Elite where they were shown;
     * the diagnostics page stays.
     */
    @Test public void noRouteTileAndNoNeuralRemosaicRows() {
        assertFalse(ShadeCatalog.defaultTiles().contains(ShadeCatalog.ROUTE));
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8650");
        assertFalse(ShadeTiles.load(prefs,key->true).contains(ShadeCatalog.ROUTE));
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();
            PreferenceFragmentCompat page=(PreferenceFragmentCompat)controller.get().getSupportFragmentManager().findFragmentById(R.id.settings_container);
            for(String key:new String[]{"scam_neural_probe","shdr_neural_remosaic_screen","pref_scam_hybrid_neural_remosaic",
                    "hexquad_denoise_screen","quad2x2_denoise_screen","scam_remosaic_gpu_category","pref_tetra_response_key"})
                assertNull(key,page.findPreference(key));
            assertTrue(page.findPreference("shdr_capture_report").isVisible());
            assertTrue(page.findPreference("scam_diagnostics_screen").isVisible());
            assertTrue(page.findPreference("pref_root_enabled").isVisible());
        }
    }

    /** The settings screen and the shade see the same device facts (DeviceAvailability). */
    @Test public void deviceFactsReachTheAvailability() {
        Map<String,Object> values=new HashMap<>();values.put("pref_root_enabled",true);
        // Robolectric is no vivo / OPPO: no tuned or ISP matrix replaces the colour method, the OPPO saturation row is hidden
        assertNull(DeviceAvailability.of(values).reason("pref_color_method_key"));
        assertTrue(DeviceAvailability.of(values).hidden("pref_scam_hybrid_why_ccm_sat"));
        // P370 (review): vivo's 12 MP photos take the ISP matrix, but the choice acts on its mosaic shots: the row stays enabled
        org.robolectric.shadows.ShadowBuild.setManufacturer("vivo");
        assertNull(DeviceAvailability.of(values).reason("pref_color_method_key"));
        org.robolectric.shadows.ShadowBuild.setManufacturer("OPPO");org.robolectric.shadows.ShadowBuild.setModel("PHY110");
        assertNotNull(DeviceAvailability.of(values).reason("pref_color_method_key"));
    }

    /** Texts: the generated RAW-metadata rows speak Russian on a Russian system; the two denoise pairs have distinct names. */
    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void russianTitlesAndDistinctDenoiseNames() {
        PreferenceScreen screen=inflate();
        for(Class<?> type:TunableRegistry.TUNABLE_CLASSES)TunablePreferenceGenerator.registerTunableClass(type);
        TunablePreferenceGenerator.generatePreferences(context,screen);
        assertEquals("Динамический уровень чёрного",String.valueOf(screen.findPreference("pref_tunable_parameters_usedynamicblacklevel").getTitle()));
        assertEquals("Не зеркалить селфи",String.valueOf(screen.findPreference("pref_tunable_parameters_disablemirror").getTitle()));
        assertEquals("SIMPLE_UI §4: a «Съёмка» row","root_shoot",screen.findPreference("pref_tunable_parameters_disablemirror").getParent().getKey());
        assertEquals("Параметры",String.valueOf(screen.findPreference("pref_category_tunable_parameters").getTitle()));
        Set<String> titles=new HashSet<>();
        for(String key:new String[]{"pref_scam_hybrid_post_luma","pref_scam_hybrid_post_chroma","pref_scam_hybrid_dn_luma_mult","pref_scam_hybrid_dn_chroma_mult"})
            assertTrue(key,titles.add(String.valueOf(screen.findPreference(key).getTitle())));
        assertEquals("Шумодав NLM: яркость",String.valueOf(screen.findPreference("pref_scam_hybrid_post_luma").getTitle()));
        assertEquals("Тема галереи",String.valueOf(screen.findPreference("pref_theme_key").getTitle()));
    }

    /**
     * H4: the six enum-like WHY sliders are lists with named entries; a value stored by the slider ("1.00", a float) becomes the
     * list value in every profile and reads the same; the SCAM HDR radii are integer sliders; RawTherapee sits by «Резкость Hybrid».
     */
    @Test public void enumSlidersAreListsAndStoredValuesKeepWorking() {
        String[] lists={"pref_scam_hybrid_why_metering","pref_scam_hybrid_why_agx_look","pref_scam_hybrid_why_sharp_domain",
                "pref_scam_hybrid_why_sharp_rl1_kernel","pref_scam_hybrid_why_sharp_rl2_kernel","pref_scam_hybrid_why_sharp_rl3_kernel"};
        SharedPreferences[] module=profiles();
        prefs.edit().putString("pref_scam_hybrid_why_metering","1.00").putFloat("pref_scam_hybrid_why_agx_look",3f)
                .putString("pref_scam_hybrid_why_sharp_rl1_kernel","7")
                .putString("pref_scam_hybrid_why_sharp_domain","x").commit();
        module[0].edit().clear().putString("pref_scam_hybrid_why_metering","2.0").commit();
        // read as before the migration
        assertEquals(1f,PreferenceKeys.hybridValue("why_metering",0f),0f);
        SettingsMigration.removeObsolete(context,prefs);
        assertEquals("1",prefs.getString("pref_scam_hybrid_why_metering",""));assertEquals("3",prefs.getString("pref_scam_hybrid_why_agx_look",""));
        assertEquals("clamped to the list","2",prefs.getString("pref_scam_hybrid_why_sharp_rl1_kernel",""));
        assertFalse(prefs.contains("pref_scam_hybrid_why_sharp_domain"));
        assertEquals("2",module[0].getString("pref_scam_hybrid_why_metering",""));
        assertFalse("a second run changes nothing",SettingsMigration.removeObsolete(prefs));
        assertEquals(1f,PreferenceKeys.hybridValue("why_metering",0f),0f);assertEquals(3f,PreferenceKeys.hybridValue("why_agx_look",4f),0f);
        PreferenceScreen screen=inflate();
        for(String key:lists){
            Preference p=screen.findPreference(key);
            assertTrue(key,p instanceof ListPreference);
            ListPreference l=(ListPreference)p;
            assertEquals(key,l.getEntries().length,l.getEntryValues().length);
            assertNotNull(key+" shows a named value",l.getEntry());
        }
        assertEquals("1",((ListPreference)screen.findPreference("pref_scam_hybrid_why_metering")).getValue());
        // P118: only the WHY sharpening stays; the RawTherapee page is gone everywhere. 2026-10-27: its strength («Резкость») is
        // a row of «Снимок», the rest of it stays on the sharpening page.
        PreferenceGroup sharp=screen.findPreference("scam_hybrid_sharp_category");
        assertNotNull(sharp.findPreference("pref_scam_hybrid_why_sharp_domain"));
        assertEquals("root_colour",((Preference)screen.findPreference("pref_scam_hybrid_why_sharp_gain")).getParent().getKey());
        assertNull(screen.findPreference("sharp_settings_screen"));
    }

    /** S5: inherited keys without a row that api/Settings still read are dropped; the AF data key they aliased stays. */
    @Test public void readersOfRemovedInheritedKeysAreGone() {
        prefs.edit().putString("pref_hdrx_nr_key","1").putString("pref_enable_system_nr_key","2").putString("pref_sharpness_seekbar_key","0.5")
                .putBoolean("pref_live_viewfinder_look_key",true).putString("pref_show_afdata_key","1").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        for(String key:new String[]{"pref_hdrx_nr_key","pref_enable_system_nr_key","pref_sharpness_seekbar_key","pref_live_viewfinder_look_key"})
            assertFalse(key,prefs.contains(key));
        assertEquals("1",prefs.getString("pref_show_afdata_key",""));
        for(java.lang.reflect.Field f:com.particlesdevs.photoncamera.api.Settings.class.getFields())
            assertFalse(f.getName(),Arrays.asList("noiseReduction","hdrxNR","sharpness","gain","previewFormat").contains(f.getName()));
    }

    /** H1: a decimal slider stores as many decimals as its step needs, and the screen seeds the exact XML default. */
    @Test public void slidersKeepTheirPrecision() {
        assertEquals("0.0005",PreferenceNumber.gridText(5/10000.0,PreferenceNumber.gridDecimals(10000,0)));
        assertEquals("0.001",PreferenceNumber.gridText(0.001,PreferenceNumber.gridDecimals(100,0.001f)));
        assertEquals("0.011",PreferenceNumber.gridText(1/100.0+0.001,PreferenceNumber.gridDecimals(100,0.001f)));
        assertEquals("0.50",PreferenceNumber.gridText(0.5,PreferenceNumber.gridDecimals(100,0)));
        assertEquals("0.20",PreferenceNumber.gridText(0.2,PreferenceNumber.gridDecimals(1000,0.01f)));
        inflate();
        assertEquals("0.0005",prefs.getString("pref_scam_hybrid_bento_trigger",""));
        assertEquals("1.414",prefs.getString("pref_scam_hybrid_lut_sigma",""));
        assertEquals(0.0005f,PreferenceKeys.hybridValue("bento_trigger",0.0005f),1e-9f);
    }

    /** H1: the "0.00" / "1.41" the old sliders seeded move to 0.0005 / 1.414 once, in every profile; later choices stay. */
    @Test public void roundedSliderDefaultsMoveOnce() {
        SharedPreferences[] module=profiles();
        prefs.edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putString("pref_scam_hybrid_bento_trigger","0.00")
                .putString("pref_scam_hybrid_lut_sigma","1.41").commit();
        module[0].edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putString("pref_scam_hybrid_bento_trigger","0.00").commit();
        module[1].edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putString("pref_scam_hybrid_bento_trigger","0.01")
                .putString("pref_scam_hybrid_lut_sigma","1.50").commit();
        module[2].edit().clear().putString(PreferenceKeys.ROUTE_KEY,"hybrid").putString("pref_scam_hybrid_lut_sigma","1.41").commit();
        SettingsMigration.migrateScamHybrid(context,prefs);
        assertEquals("0.0005",prefs.getString("pref_scam_hybrid_bento_trigger",""));
        assertEquals("1.414",prefs.getString("pref_scam_hybrid_lut_sigma",""));
        assertEquals("0.0005",module[0].getString("pref_scam_hybrid_bento_trigger",""));
        assertEquals("a chosen value stays","0.01",module[1].getString("pref_scam_hybrid_bento_trigger",""));
        assertEquals("1.50",module[1].getString("pref_scam_hybrid_lut_sigma",""));
        assertEquals("1.414",module[2].getString("pref_scam_hybrid_lut_sigma",""));
        // A 0.00 chosen after the move is the user's.
        prefs.edit().putString("pref_scam_hybrid_bento_trigger","0.00").commit();
        assertFalse(SettingsMigration.migrateScamHybrid(prefs,false));
        assertEquals("0.00",prefs.getString("pref_scam_hybrid_bento_trigger",""));
    }
}
