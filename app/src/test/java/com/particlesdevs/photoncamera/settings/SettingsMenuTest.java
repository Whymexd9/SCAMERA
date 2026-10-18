package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import androidx.preference.*;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.annotations.Tunable;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, qualifiers="w400dp-h880dp-mdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class SettingsMenuTest {
    private Context context;
    private SettingsManager manager;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;
    @Before public void setUp(){
        // SCAM HDR and its settings exist only on the Snapdragon 8 Elite; these tests cover both routes.
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
    @Test public void oneArkToneForBothRoutes() {
        PreferenceScreen screen=inflate();
        // P10: the SCAMERA tone (AgX / Exposure Fusion / headroom) is gone; ArkCore and its sharpening serve both routes.
        for(String old:new String[]{"agx_screen","scam_hybrid_scamera_tone_screen","pref_scam_hybrid_ark_tone","pref_scamroute_gamma",
                "pref_scamhdr_soft_tone","pref_scamhdr_fusion_enabled","pref_scamold_ae_mid","pref_expocompensation_seekbar_key"})
            assertNull(old,screen.findPreference(old));
        for(String kept:new String[]{"scam_hybrid_ark_tone_screen","pref_scam_hybrid_ark_ae_target","pref_scam_hybrid_ark_sharp_gain"})
            assertNotNull(kept,screen.findPreference(kept));
        // P64: the «Hybrid curves» presets are gone (owner, 2026-10-09)
        for(String old:new String[]{"scam_curves_screen","pref_scam_tone_curve","pref_scam_tone_curve_strength","pref_scam_gamma_curve",
                "pref_scam_gamma_curve_strength"})
            assertNull(old,screen.findPreference(old));
        // P129: SHDR is the only merge: its rows act whatever route was stored
        java.util.Map<String,Object> values=new java.util.HashMap<>();values.put(PreferenceKeys.ROUTE_KEY,"scamhdr");
        SettingsAvailability shdr=new SettingsAvailability(values);
        assertNull(shdr.reason("pref_scam_hybrid_ark_ae_target"));assertNull(shdr.reason("pref_scam_hybrid_ark_sharp_gain"));
        assertNull(shdr.reason("pref_scam_hybrid_cdm"));
        prefs.edit().putString("pref_agx_contrast","20").putString("pref_scamroute_gamma","1.2").putBoolean("pref_scam_hybrid_ark_tone",false)
                .putString("pref_scam_hybrid_ark_ae_target","0.2").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertFalse(prefs.contains("pref_agx_contrast"));assertFalse(prefs.contains("pref_scamroute_gamma"));
        assertFalse(prefs.contains("pref_scam_hybrid_ark_tone"));assertEquals("0.2",prefs.getString("pref_scam_hybrid_ark_ae_target",""));
    }
    @Test public void scamHdrControlsPersistAndEveryRouteIsAScamRoute() {
        PreferenceScreen screen=inflate();
        assertNull("P129: SCAM HDR is gone",screen.findPreference("scam_hdr_screen"));
        // SHDR takes every shot: no state without the scam route
        assertTrue(PreferenceKeys.isScamHdrEnabled());
        manager.set("default_scope",PreferenceKeys.ROUTE_KEY,"scamhdr");
        assertTrue(PreferenceKeys.isScamHdrEnabled());assertTrue(PreferenceKeys.isScamHybridEnabled());
        // P4: the extra RGB luma / chroma denoise and sharpen rows are gone, SCAM HDR keeps its own SCAM controls
        for(String control:new String[]{"luma","chroma","sharpen"}) assertNull(screen.findPreference("pref_scamroute_"+control));
        // the removed legacy switches no longer take a shot off the vivo routes
        manager.set("default_scope","pref_raw_mfsr_enabled_key",true);
        manager.set("default_scope","pref_remosaic_enabled_key",true);
        assertTrue(PreferenceKeys.isScamHdrEnabled());
        manager.set("default_scope",PreferenceKeys.ROUTE_KEY,"hybrid");
        assertTrue(PreferenceKeys.isScamHdrEnabled());assertTrue(PreferenceKeys.isScamHybridEnabled());
    }
    @After public void tearDown(){if(camera!=null)camera.close();}
    private PreferenceScreen inflate(){
        SettingsMigration.prepare(context,prefs);
        PreferenceManager pm=new PreferenceManager(context);
        PreferenceScreen screen=pm.inflateFromResource(context,R.xml.preferences,null);pm.setPreferences(screen);
        for(Class<?> type:TunableRegistry.TUNABLE_CLASSES)TunablePreferenceGenerator.registerTunableClass(type);
        TunablePreferenceGenerator.generatePreferences(context,screen);
        com.particlesdevs.photoncamera.ui.settings.SettingsStyle.apply(screen); // the rows bind with the P6b layouts
        return screen;
    }
    @Test public void legacyCaptureControlsAreGoneAndTheZslRingIsUpgradedOnce() {
        PreferenceScreen root=inflate();
        for(String old:new String[]{"pref_raw_mfsr_enabled_key","pref_mfsr_source_key","pref_remosaic_enabled_key",
                "pref_frame_count_key","pref_zsl_merge_algorithm_key","pref_binning_key","mfsr_settings_screen"})
            assertNull(old,root.findPreference(old));
        assertNotNull(root.findPreference("pref_zsl_buffer_count_key"));
        prefs.edit().clear().putString("pref_mfsr_k_detail_key","0.8").putBoolean("pref_mfsr_calibrate_key",true).commit();
        SettingsMigration.migrateMultiFrame(prefs);
        assertEquals("50",prefs.getString("pref_zsl_buffer_count_key",""));
        prefs.edit().putString("pref_zsl_buffer_count_key","20").commit();
        SettingsMigration.migrateMultiFrame(prefs);
        assertEquals("20",prefs.getString("pref_zsl_buffer_count_key",""));
        SettingsMigration.removeObsolete(prefs);
        assertFalse(prefs.contains("pref_mfsr_k_detail_key"));assertFalse(prefs.contains("pref_mfsr_calibrate_key"));
    }
    private void visit(PreferenceGroup group,Set<String> seen,List<String> pages){
        for(int i=0;i<group.getPreferenceCount();i++){
            Preference p=group.getPreference(i);
            if(p.getKey()!=null)assertTrue("duplicate key "+p.getKey(),seen.add(p.getKey()));
            if(p instanceof PreferenceScreen)pages.add(p.getKey());
            if(p instanceof PreferenceGroup)visit((PreferenceGroup)p,seen,pages);
            else {
                // Binding calls the actual widget and summary code, not just the XML parser.
                android.view.View row=LayoutInflater.from(context).inflate(p.getLayoutResource(),null,false);
                PreferenceViewHolder holder=PreferenceViewHolder.createInstanceForTests(row);
                p.onBindViewHolder(holder);
                com.particlesdevs.photoncamera.ui.settings.SettingsStyle.bind(holder,p);
                p.getSummary();
                if(p instanceof ListPreference && !(p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.RestorePreference)){
                    ListPreference l=(ListPreference)p;
                    assertNotNull(p.getKey(),l.getEntries());assertNotNull(p.getKey(),l.getEntryValues());
                    assertEquals(p.getKey(),l.getEntries().length,l.getEntryValues().length);
                }
            }
        }
    }
    @Test public void allPagesAndRegisteredTunablesAreReachableAndBind(){
        PreferenceScreen screen=inflate();Set<String> seen=new HashSet<>();List<String> pages=new ArrayList<>();visit(screen,seen,pages);
        assertTrue(seen.size()>300);assertTrue(pages.size()>25);
        for(Class<?> c:TunableRegistry.TUNABLE_CLASSES)for(Field f:c.getDeclaredFields())if(f.isAnnotationPresent(Tunable.class)){
            String key="pref_tunable_"+c.getSimpleName().toLowerCase(Locale.ROOT)+"_"+f.getName().toLowerCase(Locale.ROOT);
            assertNotNull("Missing registered control "+key,screen.findPreference(key));
        }
        for(String page:pages){PreferenceScreen root=inflate();PreferenceScreen nested=root.findPreference(page);assertNotNull(page,nested);root.getPreferenceManager().setPreferences(nested);assertEquals(page,nested.getKey());}
    }
    @Test public void mixedTypeMigrationAndRebindPreservePreciseValues(){
        prefs.edit().putInt("pref_scam_hybrid_mosaic_native_clamp",1).putBoolean("pref_tunable_parameters_usedynamicwhitelevel",false)
                .putString("pref_film_lut_strength","37.12345")
                .putInt("pref_scam_hybrid_despeckle",1).putString("pref_scam_hybrid_cdm","0.18973").commit();
        PreferenceScreen screen=inflate();
        assertEquals("1",prefs.getString("pref_scam_hybrid_mosaic_native_clamp",""));assertTrue(prefs.getBoolean("pref_scam_hybrid_despeckle",false));
        visit(screen,new HashSet<>(),new ArrayList<>());
        assertEquals("0.18973",prefs.getString("pref_scam_hybrid_cdm",""));assertEquals("37.12345",prefs.getString("pref_film_lut_strength",""));
        assertFalse(PreferenceNumber.bool(prefs.getAll().get("pref_tunable_parameters_usedynamicwhitelevel"),true));
    }
    /** P193: the old per-camera JSON (written once, at the first camera start) no longer takes over a module without a profile. */
    @Test public void perLensRestoreKeepsTheCurrentSettingsOverTheOldPerCameraJson(){
        prefs.edit().putString(PreferenceKeys.Key.KEY_THEME.mValue,"keep").putBoolean("pref_scam_hybrid_despeckle",false)
                .putFloat("pref_film_lut_strength",12.5f).commit();
        manager.set(PreferenceKeys.Key.PER_LENS_FILE_NAME.mValue,"settings_for_camera_audit",
                "{\"pref_scam_hybrid_despeckle\":true,\"pref_scam_hybrid_mosaic_native_clamp\":1,\"pref_film_lut_strength\":37.125,\"ignored_null\":null,\""+PreferenceKeys.Key.KEY_THEME.mValue+"\":\"replace\"}");
        PreferenceKeys.profiles();
        prefs.edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,true).commit();
        PreferenceKeys.profiles().changed(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue);
        PreferenceKeys.loadSettingsForCamera("audit");
        assertEquals("audit",PreferenceKeys.profiles().editing());
        assertFalse(prefs.getBoolean("pref_scam_hybrid_despeckle",true));
        assertFalse(prefs.contains("pref_scam_hybrid_mosaic_native_clamp"));
        assertEquals(12.5f,prefs.getFloat("pref_film_lut_strength",0),0);
        assertFalse(prefs.contains("ignored_null"));assertEquals("keep",prefs.getString(PreferenceKeys.Key.KEY_THEME.mValue,""));
        manager.openPreferences(PreferenceKeys.Key.PER_LENS_FILE_NAME.mValue).edit().clear().commit();
    }

    @Test public void moduleProfilesKeepTypedValuesAndCopyOnlySelection(){
        prefs.edit().putFloat("pref_film_lut_strength",37.125f).putBoolean("pref_scam_hybrid_despeckle",true)
                .putString("pref_tunable_test","1.234567").putString("pref_sensorconfig_test","hardware").commit();
        ModuleProfiles profiles=PreferenceKeys.profiles();
        prefs.edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,true).commit();
        profiles.changed(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue);
        profiles.activate("back0");
        prefs.edit().putFloat("pref_film_lut_strength",12.25f).putBoolean("pref_scam_hybrid_despeckle",false).commit();
        profiles.activate("back1");
        assertEquals(37.125f,prefs.getFloat("pref_film_lut_strength",0),0);
        prefs.edit().putString("pref_tunable_test","destination").commit();
        profiles.copy("back0",Arrays.asList("back1"),new HashSet<>(Arrays.asList("pref_film_lut_strength","pref_sensorconfig_test")));
        assertEquals(12.25f,prefs.getFloat("pref_film_lut_strength",0),0);
        assertEquals("destination",prefs.getString("pref_tunable_test",""));
        assertTrue(prefs.getBoolean("pref_scam_hybrid_despeckle",false));
        assertEquals("hardware",prefs.getString("pref_sensorconfig_test",""));
        profiles.activate("back0");assertFalse(prefs.getBoolean("pref_scam_hybrid_despeckle",true));
        prefs.edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,false).commit();profiles.changed(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue);
        assertEquals(37.125f,prefs.getFloat("pref_film_lut_strength",0),0);
        prefs.edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,true).commit();profiles.changed(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue);
        profiles.activate("back0");assertEquals(12.25f,prefs.getFloat("pref_film_lut_strength",0),0);
    }
    @Test public void removedScamUpscaleSettingsAreGoneAndTheirStoredValuesCleared() {
        PreferenceScreen screen=inflate();
        for (String key:Arrays.asList("raisr_settings_screen","pref_raisr_enabled_key","pref_scam_upscale_backend_key",
                "pref_scam_downscale_kernel_key","pref_scam_downscale_size_key","softpqe_sr_only_info","scam_downscale_explanation"))
            assertNull(key,screen.findPreference(key));
        android.content.SharedPreferences prefs=androidx.preference.PreferenceManager.getDefaultSharedPreferences(
                org.robolectric.RuntimeEnvironment.getApplication());
        prefs.edit().putBoolean("pref_raisr_enabled_key",true).putString("pref_scam_upscale_backend_key","vsr")
                .putString("pref_scam_downscale_kernel_key","3").putString("pref_scam_hybrid_cdm","0.2").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertFalse(prefs.contains("pref_raisr_enabled_key"));assertFalse(prefs.contains("pref_scam_upscale_backend_key"));
        assertFalse(prefs.contains("pref_scam_downscale_kernel_key"));assertEquals("0.2",prefs.getString("pref_scam_hybrid_cdm",""));
        assertFalse(SettingsMigration.removeObsolete(prefs));
    }
    @Test public void legacyPostProcessingSettingsAreGoneAndTheirStoredValuesCleared() {
        PreferenceScreen screen=inflate();
        for (String key:Arrays.asList("rt_denoise_screen","gcam_finish_screen","aces_group_screen","capture_one_group_screen",
                "scamera_darktable_screen","optical_correction_screen","expert_raw_screen","expert_noise_screen","expert_detail_screen",
                "expert_tone_screen","pref_tunable_postpipeline_tonepipeline","pref_tunable_postpipeline_demosaicingmethod",
                "pref_saturation_seekbar_key","pref_contrast_seekbar_key","pref_sensor_sharpening_enabled","pref_noise_iso_curve_key",
                "sharp_settings_screen")) // P118: the RawTherapee sharpening is gone too (only the ARK sharpening stays)
            assertNull(key,screen.findPreference(key));
        for (String key:Arrays.asList("pref_noise_model_profile_key","pref_dcp_profile_key"))
            assertNotNull(key,screen.findPreference(key));
        android.content.SharedPreferences prefs=androidx.preference.PreferenceManager.getDefaultSharedPreferences(
                org.robolectric.RuntimeEnvironment.getApplication());
        prefs.edit().putString("rt512_luma","10").putBoolean("pref_aces_enabled_key",true).putString("pref_tunable_initial_gammax1","7")
                .putString("pref_noise_iso_manual_key","800").putString("pref_noise_model_profile_key","auto").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        for (String key:Arrays.asList("rt512_luma","pref_aces_enabled_key","pref_tunable_initial_gammax1","pref_noise_iso_manual_key"))
            assertFalse(key,prefs.contains(key));
        assertEquals("auto",prefs.getString("pref_noise_model_profile_key",""));
    }
    @Test public void configScreenKeepsOnlyOutputBackupAndReset() {
        PreferenceScreen screen=inflate();
        androidx.preference.PreferenceGroup config=screen.findPreference("root_photo");assertNotNull(config);
        // RAW mode stays a quick bar setting; DNG crop and the tunable reset page are gone.
        for (String key:Arrays.asList("pref_save_raw_key","expert_output_screen","pref_tunable_submenu","pref_tunable_imagesaversettings_croptype"))
            assertNull(key,screen.findPreference(key));
        // SIMPLE_UI §4: «Фото» (Формат файла, Размер фото, HDR, 16:9, watermark, name); the backup is «Приложение → Профили»
        for (String key:Arrays.asList("pref_jpeg_quality","pref_ultrahdr_key","pref_wide169_key","pref_show_watermark_key","pref_watermark_line1",
                "pref_scam_hybrid_output","pref_scam_hybrid_downsampler","pref_dng_lossless","pref_file_name_pattern"))
            assertNotNull(key,config.findPreference(key));
        PreferenceScreen backup=screen.findPreference("backup_settings_screen");assertNotNull(backup);
        assertNotNull(((androidx.preference.PreferenceGroup)screen.findPreference("root_app")).findPreference("backup_settings_screen"));
        for (String key:Arrays.asList("pref_backup_preferences_key","pref_restore_preferences_key","pref_reset_preferences_key"))
            assertNotNull(key,backup.findPreference(key));
        // The remaining dynamic page is still filled without pref_tunable_submenu. «Кнопки видоискателя» held only the
        // Quad toggle's tunable; the toggle is gone (P25), so are the page and the tunable.
        PreferenceScreen page=screen.findPreference("expert_sensor_screen");assertNotNull(page);assertTrue(page.getPreferenceCount()>0);
        assertNull(screen.findPreference("expert_viewfinder_screen"));
        assertNull(screen.findPreference("pref_tunable_camerauiviewimpl_enablequadres"));
        android.content.SharedPreferences prefs=androidx.preference.PreferenceManager.getDefaultSharedPreferences(
                org.robolectric.RuntimeEnvironment.getApplication());
        prefs.edit().putBoolean("pref_tunable_imagesaversettings_croptype",true).putString("pref_save_raw_key","1").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertFalse(prefs.contains("pref_tunable_imagesaversettings_croptype"));assertEquals("1",prefs.getString("pref_save_raw_key",""));
    }
    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void configXmlKeepsTypesAndAppliesModuleProfilesOnlyOnTheSamePhone() throws Exception {
        configXmlKeepsTypesAndAppliesModuleProfilesOnlyOnTheSamePhone("Загружено: ","Загружены общие настройки");
    }
    /** The same on an English system: the import messages are English (i18n). */
    @Test public void configXmlKeepsTypesAndAppliesModuleProfilesOnlyOnTheSamePhoneInEnglish() throws Exception {
        configXmlKeepsTypesAndAppliesModuleProfilesOnlyOnTheSamePhone("Imported: ","Imported the shared settings");
    }
    private void configXmlKeepsTypesAndAppliesModuleProfilesOnlyOnTheSamePhone(String samePhone,String otherPhone) throws Exception {
        android.content.Context app=org.robolectric.RuntimeEnvironment.getApplication();
        android.content.SharedPreferences main=androidx.preference.PreferenceManager.getDefaultSharedPreferences(app);
        android.content.SharedPreferences meta=app.getSharedPreferences(BackupRestoreUtil.META,0);
        android.content.SharedPreferences main1=app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+"main1",0);
        main.edit().clear().putString("pref_scam_hybrid_cdm","0.2").putBoolean("pref_wide169_key",true).putInt("pref_scam_hybrid_frames_x",7)
                .putLong("scamera_long",1L<<40).putFloat("pref_scam_hybrid_gamma_x",7.18973f)
                .putStringSet("hidden_camera_ids",new HashSet<>(Arrays.asList("2","5"))).putString("pref_watermark_line1","<SHOT & \"ON\">").commit();
        meta.edit().clear().putBoolean("exists_main1",true).putString("active","main1").commit();
        main1.edit().clear().putString("pref_scam_hybrid_cdm","0.3").commit();
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        ConfigXml.write(out,BackupRestoreUtil.header(app),BackupRestoreUtil.collect(app));
        ConfigXml.Config config=ConfigXml.read(new java.io.ByteArrayInputStream(out.toByteArray()));
        assertEquals(BackupRestoreUtil.device(),config.attributes.get("device"));
        assertEquals(new HashMap<>(main.getAll()),new HashMap<>(config.files.get(ConfigXml.MAIN)));
        assertEquals("0.3",config.files.get(BackupRestoreUtil.PROFILE_PREFIX+"main1").get("pref_scam_hybrid_cdm"));
        // same phone: everything comes back, including the module profile
        main.edit().clear().putString("stale","x").commit();main1.edit().clear().commit();
        assertEquals(samePhone,BackupRestoreUtil.apply(app,config));
        assertFalse(main.contains("stale"));assertEquals(1L<<40,main.getLong("scamera_long",0));
        assertEquals(7.18973f,main.getFloat("pref_scam_hybrid_gamma_x",0),0f);assertEquals(7,main.getInt("pref_scam_hybrid_frames_x",0));
        assertEquals(new HashSet<>(Arrays.asList("2","5")),main.getStringSet("hidden_camera_ids",null));
        assertEquals("<SHOT & \"ON\">",main.getString("pref_watermark_line1",""));
        assertEquals("0.3",main1.getString("pref_scam_hybrid_cdm",""));
        // another phone: main settings only, module profiles stay as they are
        config.attributes.put("device","oppo/op627cl1");main1.edit().clear().putString("pref_scam_hybrid_cdm","0.5").commit();
        assertTrue(BackupRestoreUtil.apply(app,config).startsWith(otherPhone));
        assertEquals("0.5",main1.getString("pref_scam_hybrid_cdm",""));assertEquals("0.2",main.getString("pref_scam_hybrid_cdm",""));
        // an old plain shared_prefs XML reads as the main settings
        ConfigXml.Config legacy=ConfigXml.read(new java.io.ByteArrayInputStream(("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                +"<map><boolean name=\"pref_wide169_key\" value=\"false\" /><string name=\"pref_save_raw_key\">1</string></map>").getBytes("UTF-8")));
        assertTrue(legacy.legacyMap);assertEquals(false,legacy.files.get(ConfigXml.MAIN).get("pref_wide169_key"));
    }
    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void configFromAnotherPhoneMapsModuleProfilesOntoThisPhonesLenses() throws Exception {
        configFromAnotherPhoneMapsModuleProfilesOntoThisPhonesLenses(
                "Загружено с oppo/op627cl1. Модули: 1× ← 1×, 3× ← 3×, Фронт ← общие; не перенесены: 0.6×","общие настройки на все модули");
    }
    /** The same on an English system («Фронт» is the module's own label, user data). */
    @Test public void configFromAnotherPhoneMapsModuleProfilesOntoThisPhonesLensesInEnglish() throws Exception {
        configFromAnotherPhoneMapsModuleProfilesOntoThisPhonesLenses(
                "Imported from oppo/op627cl1. Modules: 1× ← 1×, 3× ← 3×, Фронт ← shared; not applied: 0.6×","shared settings for all modules");
    }
    private void configFromAnotherPhoneMapsModuleProfilesOntoThisPhonesLenses(String mapped,String allModules) throws Exception {
        android.content.Context app=org.robolectric.RuntimeEnvironment.getApplication();
        android.content.SharedPreferences main=androidx.preference.PreferenceManager.getDefaultSharedPreferences(app);
        // this phone: 1x and 3x on the back, a front camera (no camera service here: focal = 26 mm x zoom)
        main.edit().clear().putString("module_auto_back0","0").putBoolean("module_visible_back0",true).putString("module_label_back0","1×")
                .putString("module_auto_back1","2").putBoolean("module_visible_back1",true).putString("module_label_back1","3×")
                .putString("module_auto_front0","1").putBoolean("module_visible_front0",true).putString("module_label_front0","Фронт")
                .putString("module_active","back1").putString("pref_scam_hybrid_cdm","0.9").commit();
        ConfigXml.Config config=new ConfigXml.Config();
        config.attributes.put("device","oppo/op627cl1");
        java.util.Map<String,Object> srcMain=new java.util.HashMap<>();
        srcMain.put("pref_scam_hybrid_cdm","0.2");srcMain.put("module_auto_back0","9");srcMain.put("pref_save_per_lens_settings",true);
        config.files.put(ConfigXml.MAIN,srcMain);
        java.util.Map<String,Object> meta=new java.util.HashMap<>();meta.put("active","back1");meta.put("exists_back0",true);
        config.files.put(BackupRestoreUtil.META,meta);
        java.util.Map<String,Object> p0=new java.util.HashMap<>();p0.put("pref_scam_hybrid_cdm","0.5");p0.put("module_auto_back0","7");
        config.files.put(BackupRestoreUtil.PROFILE_PREFIX+"back0",p0);
        java.util.Map<String,Object> common=new java.util.HashMap<>();common.put("pref_scam_hybrid_cdm","0.1");
        config.files.put(BackupRestoreUtil.PROFILE_PREFIX+"common",common);
        java.util.Map<String,Object> lenses=new java.util.LinkedHashMap<>();
        String[][] src={{"back0","14","0.6","0.6×"},{"back1","23","1","1×"},{"back2","70","3","3×"}};
        for(String[] l:src){lenses.put(l[0]+".facing","back");lenses.put(l[0]+".focal",Float.parseFloat(l[1]));
            lenses.put(l[0]+".zoom",Float.parseFloat(l[2]));lenses.put(l[0]+".crop",false);lenses.put(l[0]+".label",l[3]);}
        config.files.put(BackupRestoreUtil.LENSES,lenses);
        String message=BackupRestoreUtil.apply(app,config);
        assertTrue(message,message.startsWith(mapped));
        // 1x <- the source's active 1x (its values are in the source's main settings); 3x <- the source's 3x, which had no
        // saved profile: the baseline; the front has no source lens: the baseline
        assertEquals("0.2",app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+"back0",0).getString("pref_scam_hybrid_cdm",""));
        assertEquals("0.1",app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+"back1",0).getString("pref_scam_hybrid_cdm",""));
        assertEquals("0.1",app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+"front0",0).getString("pref_scam_hybrid_cdm",""));
        assertFalse(app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+"back0",0).contains("module_auto_back0"));
        android.content.SharedPreferences m=app.getSharedPreferences(BackupRestoreUtil.META,0);
        assertTrue(m.getBoolean("exists_back1",false));assertTrue(m.getBoolean("exists_front0",false));assertEquals("back1",m.getString("active",""));
        // this phone's slots stay; the active slot (3x) is in the main settings; the source's global settings came over
        assertEquals("0",main.getString("module_auto_back0",""));assertEquals("0.1",main.getString("pref_scam_hybrid_cdm",""));
        assertTrue(main.getBoolean("pref_save_per_lens_settings",false));
        // the file this phone saves carries its own lens passports
        java.util.Map<String,?> own=BackupRestoreUtil.collect(app).get(BackupRestoreUtil.LENSES);
        assertNotNull(own);assertEquals("front",own.get("front0.facing"));assertEquals(78f,(Float)own.get("back1.focal"),0.01f);
        // per-lens settings off on the source: its main settings go to every module
        srcMain.put("pref_save_per_lens_settings",false);
        message=BackupRestoreUtil.apply(app,config);
        assertTrue(message,message.contains(allModules));
        for(String slot:new String[]{"back0","back1","front0"})
            assertEquals(slot,"0.2",app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+slot,0).getString("pref_scam_hybrid_cdm",""));
        assertEquals("0.2",main.getString("pref_scam_hybrid_cdm",""));
    }
    /** P141: an import on the same phone that changes the active module keeps the config's values in the main settings. */
    @Test public void sameDeviceImportIsNotOverwrittenByTheProfileListener() throws Exception {
        android.content.Context app=org.robolectric.RuntimeEnvironment.getApplication();
        android.content.SharedPreferences main=androidx.preference.PreferenceManager.getDefaultSharedPreferences(app);
        android.content.SharedPreferences meta=app.getSharedPreferences(BackupRestoreUtil.META,0);
        main.edit().clear().putString("module_auto_back0","0").putBoolean("module_visible_back0",true)
                .putString("module_auto_back1","2").putBoolean("module_visible_back1",true).putString("module_active","back0")
                .putString(PreferenceKeys.Key.CAMERA_ID.mValue,"0").putBoolean("pref_save_per_lens_settings",true)
                .putString("pref_scam_hybrid_cdm","0.9").commit();
        meta.edit().clear().putBoolean("baseline",true).putBoolean("exists_back0",true).putBoolean("exists_back1",true).putString("active","back0").commit();
        app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+"back1",0).edit().clear().putString("pref_scam_hybrid_cdm","0.5").commit();
        // the config: saved with the 3x module (back1) active, its value 0.3
        ConfigXml.Config config=new ConfigXml.Config();
        config.attributes.put("device",BackupRestoreUtil.device());
        java.util.Map<String,Object> srcMain=new java.util.HashMap<>(main.getAll());
        srcMain.put("module_active","back1");srcMain.put(PreferenceKeys.Key.CAMERA_ID.mValue,"2");srcMain.put("pref_scam_hybrid_cdm","0.3");
        config.files.put(ConfigXml.MAIN,srcMain);
        java.util.Map<String,Object> srcMeta=new java.util.HashMap<>();srcMeta.put("baseline",true);srcMeta.put("exists_back0",true);
        srcMeta.put("exists_back1",true);srcMeta.put("active","back1");
        config.files.put(BackupRestoreUtil.META,srcMeta);
        java.util.Map<String,Object> p0=new java.util.HashMap<>();p0.put("pref_scam_hybrid_cdm","0.7");
        config.files.put(BackupRestoreUtil.PROFILE_PREFIX+"back0",p0);
        java.util.Map<String,Object> p1=new java.util.HashMap<>();p1.put("pref_scam_hybrid_cdm","0.3");
        config.files.put(BackupRestoreUtil.PROFILE_PREFIX+"back1",p1);
        camera.when(ScameraApp::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        manager.addListener(PreferenceKeys.PROFILE_LISTENER); // as CameraActivity's setDefaults (this test's manager only)
        assertEquals("Imported: ",BackupRestoreUtil.apply(app,config));
        assertEquals("the config's value of its active module, not this phone's old profile of it","0.3",main.getString("pref_scam_hybrid_cdm",""));
        assertEquals("0.7",app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+"back0",0).getString("pref_scam_hybrid_cdm",""));
        assertEquals("0.3",app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+"back1",0).getString("pref_scam_hybrid_cdm",""));
        assertEquals("back1",meta.getString("active",""));
    }
    /**
     * P141: a file without the phone's name is this phone's only when every Camera ID of its module slots exists here;
     * otherwise this phone keeps its own slots. On the same phone the automatic module offers already made stay made.
     */
    @Test public void aConfigWithoutTheDeviceNameIsThisPhonesOnlyWhenItsCamerasExistAndOffersStay() throws Exception {
        android.content.Context app=org.robolectric.RuntimeEnvironment.getApplication();
        android.content.SharedPreferences main=androidx.preference.PreferenceManager.getDefaultSharedPreferences(app);
        main.edit().clear().putString("module_auto_back0","0").putBoolean("module_visible_back0",true).putString("module_label_back0","1×")
                .putBoolean("module_isz_offered_back0",true).putString("pref_scam_hybrid_cdm","0.9").commit();
        ConfigXml.Config config=new ConfigXml.Config();
        java.util.Map<String,Object> srcMain=new java.util.HashMap<>();
        srcMain.put("module_auto_back0","9");srcMain.put("module_visible_back0",true);srcMain.put("pref_scam_hybrid_cdm","0.2");
        config.files.put(ConfigXml.MAIN,srcMain);
        // no camera 9 here: another phone's file, this phone's slot stays
        String message=BackupRestoreUtil.apply(app,config);
        assertTrue(message,message.contains("an unknown phone"));
        assertEquals("0",main.getString("module_auto_back0",""));assertEquals("0.2",main.getString("pref_scam_hybrid_cdm",""));
        // with camera 9 present it is this phone's file: everything as written, the offer already made kept
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.hardware.camera2.CameraManager.class))
                .addCamera("9",org.robolectric.shadows.ShadowCameraCharacteristics.newCameraCharacteristics());
        main.edit().putBoolean("module_isz_offered_back0",true).commit();
        assertEquals("Imported: ",BackupRestoreUtil.apply(app,config));
        assertEquals("9",main.getString("module_auto_back0",""));
        assertTrue("the offer made on this phone stays made",main.getBoolean("module_isz_offered_back0",false));
    }
    /** P141: this phone's hidden modules lose their old profiles on an import from another phone (back to the baseline). */
    @Test public void anImportFromAnotherPhoneResetsTheProfilesOfHiddenModules() throws Exception {
        android.content.Context app=org.robolectric.RuntimeEnvironment.getApplication();
        android.content.SharedPreferences main=androidx.preference.PreferenceManager.getDefaultSharedPreferences(app);
        main.edit().clear().putString("module_auto_back0","0").putBoolean("module_visible_back0",true).putString("module_label_back0","1×")
                .putString("module_auto_back1","2").putBoolean("module_visible_back1",false).putString("module_label_back1","3×")
                .putString("module_active","back0").commit();
        android.content.SharedPreferences meta=app.getSharedPreferences(BackupRestoreUtil.META,0);
        meta.edit().clear().putBoolean("exists_back0",true).putBoolean("exists_back1",true).commit();
        ConfigXml.Config config=new ConfigXml.Config();
        config.attributes.put("device","oppo/op627cl1");
        java.util.Map<String,Object> srcMain=new java.util.HashMap<>();srcMain.put("pref_scam_hybrid_cdm","0.2");
        config.files.put(ConfigXml.MAIN,srcMain);
        java.util.Map<String,Object> lenses=new java.util.LinkedHashMap<>();
        lenses.put("back0.facing","back");lenses.put("back0.focal",23f);lenses.put("back0.zoom",1f);lenses.put("back0.crop",false);lenses.put("back0.label","1×");
        config.files.put(BackupRestoreUtil.LENSES,lenses);
        BackupRestoreUtil.apply(app,config);
        assertTrue(meta.getBoolean("exists_back0",false));
        assertFalse("the hidden 3x keeps no profile from before the import",meta.contains("exists_back1"));
    }
    /** P141 / P148: the passport of a «1.3×» copy of 1x reads 1.3 x the lens's focal length, also with the 1x button hidden. */
    @Test public void thePassportOfACroppedCopyUsesTheLensFocalLength() {
        android.content.Context app=org.robolectric.RuntimeEnvironment.getApplication();
        android.hardware.camera2.CameraCharacteristics c=org.robolectric.shadows.ShadowCameraCharacteristics.newCameraCharacteristics();
        org.robolectric.shadows.ShadowCameraCharacteristics sc=org.robolectric.Shadows.shadowOf(c);
        sc.set(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,new float[]{7.1f});
        sc.set(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,new android.util.SizeF(9.8f,7.35f));
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.hardware.camera2.CameraManager.class)).addCamera("0",c);
        android.content.SharedPreferences main=androidx.preference.PreferenceManager.getDefaultSharedPreferences(app);
        main.edit().clear().putString("module_auto_back0","0").putBoolean("module_visible_back0",false).putString("module_label_back0","1×")
                .putString("module_auto_back1","0").putBoolean("module_visible_back1",true).putString("module_label_back1","1.3×")
                .putString("module_zoom_back1","1.3").putString("module_crop_back1","1.3").commit();
        java.util.Map<String,Object> passports=BackupRestoreUtil.lensPassports(app);
        assertEquals(36f/9.8f*7.1f*1.3f,(Float)passports.get("back1.focal"),0.05f);
        assertFalse(passports.containsKey("back0.focal"));
    }
    @Test public void moduleCopyCatalogContainsDynamicProcessingAndSupportsDrilldown(){
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();
            var copy=new com.particlesdevs.photoncamera.ui.settings.ModuleCopyFragment();
            fm.beginTransaction().replace(R.id.settings_container,copy).commitNow();
            // SIMPLE_UI §4: the root's groups are the copy catalog's groups («Цвет и стиль» holds the colour pages)
            android.view.View processing=copy.requireView().findViewWithTag("group_root_colour");
            assertNotNull(tags(copy.requireView()),processing);assertTrue(processing.performClick());

            // «Цвет» (the RawTherapee sharpening left in the settings audit and is gone since P118, the curves are gone, P64):
            // the DCP profile
            assertNotNull(copy.requireView().findViewWithTag("parameter_pref_dcp_profile_key"));
            activity.getOnBackPressedDispatcher().onBackPressed();
            assertNotNull(copy.requireView().findViewWithTag("group_root_colour"));
        }
    }

    private static String tags(android.view.View view) {
        StringBuilder out=new StringBuilder();
        java.util.ArrayDeque<android.view.View> queue=new java.util.ArrayDeque<>();queue.add(view);
        while(!queue.isEmpty()){android.view.View v=queue.poll();if(v.getTag()!=null)out.append(v.getTag()).append(' ');
            if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++)queue.add(((android.view.ViewGroup)v).getChildAt(i));}
        return out.toString();
    }
    private void renderPage(android.view.View view,String name) throws Exception {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        int exact=android.view.View.MeasureSpec.EXACTLY;
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(400,exact),android.view.View.MeasureSpec.makeMeasureSpec(880,exact));view.layout(0,0,400,880);
        // SIMPLE_UI §4: the concept's header - a 44dp back disc, the title column, (the search disc)
        android.view.ViewGroup header=(android.view.ViewGroup)((android.view.ViewGroup)view).getChildAt(0);
        android.view.ViewGroup row=(android.view.ViewGroup)header.getChildAt(0);
        android.view.ViewGroup titles=(android.view.ViewGroup)row.getChildAt(1);
        assertTrue("Header must hold the 44dp back disc",header.getHeight()>=44);
        assertEquals(android.view.View.VISIBLE,row.getChildAt(0).getVisibility());
        assertEquals(44,row.getChildAt(0).getWidth());
        if(titles.getChildAt(1).getVisibility()==android.view.View.VISIBLE)assertTrue(titles.getChildAt(1).getTop()>=titles.getChildAt(0).getBottom());
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(400,880,android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
        java.io.File dir=new java.io.File("build/reports/module-concept");dir.mkdirs();
        try(var out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
    }
    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void conceptSelectionCopiesOnlyChosenValuesAndAccentSurvivesModuleSwitch() throws Exception {
        conceptSelectionCopiesOnlyChosenValuesAndAccentSurvivesModuleSwitch("Копировать настройки между модулями","Настройки по модулям","частично","");
    }
    /** The same on an English system: the row titles and the partial-selection hint are English. */
    @Test public void conceptSelectionCopiesOnlyChosenValuesAndAccentSurvivesModuleSwitchInEnglish() throws Exception {
        conceptSelectionCopiesOnlyChosenValuesAndAccentSurvivesModuleSwitch("Copy settings between modules","Settings per module","partly selected","-en");
    }
    private void conceptSelectionCopiesOnlyChosenValuesAndAccentSurvivesModuleSwitch(String copyRow,String groupRow,String partly,String png) throws Exception {
        for(int i=0;i<3;i++)prefs.edit().putString("module_auto_back"+i,""+(3+i)).putString("module_label_back"+i,new String[]{"1×","0.4×","2.4×"}[i]).putBoolean("module_visible_back"+i,true).commit();
        prefs.edit().putString("module_active","back0").putBoolean("pref_dng_lossless",true).commit();
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();
            var modules=new com.particlesdevs.photoncamera.ui.settings.ModuleSettingsFragment();fm.beginTransaction().replace(R.id.settings_container,modules).commitNow();renderPage(modules.requireView(),"modules"+png);
            assertEquals(android.view.View.GONE,activity.findViewById(R.id.settings_toolbar).getVisibility());
            // P218: «Камеры и сенсоры» no longer repeats the module group (it is opened from the main settings page only)
            assertNull("no «"+groupRow+"» on «Камеры и сенсоры»",modules.requireView().findViewWithTag(groupRow));
            assertNull("no «"+copyRow+"» on «Камеры и сенсоры»",modules.requireView().findViewWithTag(copyRow));
            var group=new com.particlesdevs.photoncamera.ui.settings.ModuleProfilesFragment();fm.beginTransaction().replace(R.id.settings_container,group).commitNow();
            group.requireView().findViewWithTag(copyRow).performClick();fm.executePendingTransactions();
            var copy=(com.particlesdevs.photoncamera.ui.settings.ModuleCopyFragment)fm.findFragmentById(R.id.settings_container);renderPage(copy.requireView(),"copy"+png);
            copy.requireView().findViewWithTag("clear_selection").performClick();assertFalse(copy.requireView().findViewWithTag("primary_action").isEnabled());
            // one row of «Фото» (SIMPLE_UI §4: the root's groups are the copy catalog's; the DNG compression is under «Фото»)
            copy.requireView().findViewWithTag("group_root_photo").performClick();
            copy.requireView().findViewWithTag("parameter_pref_dng_lossless").performClick();renderPage(copy.requireView(),"noise"+png);
            copy.requireView().findViewWithTag("primary_action").performClick();
            var check=copy.requireView().findViewWithTag("group_check_root_photo");assertTrue(check.getContentDescription().toString(),check.getContentDescription().toString().contains(partly));
            copy.requireView().findViewWithTag("target_back2").performClick();copy.requireView().findViewWithTag("primary_action").performClick();
            assertEquals(Boolean.TRUE,PreferenceKeys.profiles().snapshot("back1").get("pref_dng_lossless"));
            assertFalse(context.getSharedPreferences("module_profiles_meta",0).getBoolean("exists_back2",false));
            var dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestDialog();if(dialog!=null)dialog.dismiss();
            var accent=new com.particlesdevs.photoncamera.ui.settings.AccentSettingsFragment();fm.beginTransaction().replace(R.id.settings_container,accent).commitNow();renderPage(accent.requireView(),"accent"+png);
            accent.requireView().findViewWithTag("accent_blue").performClick();
            assertEquals("blue",prefs.getString(com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette.KEY,""));
            assertFalse(ModuleProfiles.isLocal(com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette.KEY));
            prefs.edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,true).commit();PreferenceKeys.profiles().changed(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue);
            PreferenceKeys.profiles().activate("back1");
            assertEquals(0xFF90C7FF,com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette.camera(context));
        }
    }

    @Test public void openingPagesDoesNotRewriteGalleryOrThemeSwitches(){
        prefs.edit().putString(PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON.mValue,"0").commit();
        inflate(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Map<String,?> before=prefs.getAll();
        java.util.List<String> changed=new ArrayList<>();
        SharedPreferences.OnSharedPreferenceChangeListener observer=(p,k)->changed.add(k);
        prefs.registerOnSharedPreferenceChangeListener(observer);
        for(int i=0;i<5;i++)inflate();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        prefs.unregisterOnSharedPreferenceChangeListener(observer);
        assertFalse(changed.toString(),changed.contains(PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON.mValue));
        assertEquals(before.get(PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON.mValue),prefs.getAll().get(PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON.mValue));
    }

    @Test public void nestedArkTonePageOpensWithoutParentDependency() {
        try(var controller=org.robolectric.Robolectric.buildActivity(
                com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)) {
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();
            fm.executePendingTransactions();
            var root=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            PreferenceScreen tone=root.findPreference("scam_hybrid_ark_tone_screen");
            activity.onPreferenceStartScreen(root,tone);fm.executePendingTransactions();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            var page=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            assertEquals("scam_hybrid_ark_tone_screen",page.getPreferenceScreen().getKey());
            assertTrue(page.findPreference("pref_scam_hybrid_ark_ae_target").isEnabled());
            assertTrue(PreferenceKeys.isScamHdrEnabled());
            camera.verify(()->ScameraApp.restartApp(any(android.content.Context.class)),never());
        }
    }

    /** P140: long press on the launcher icon offers «Настройки»; it opens the settings with the camera's set-up done. */
    @Test public void launcherShortcutOpensTheSettings() throws Exception {
        android.content.pm.ActivityInfo splash=context.getPackageManager().getActivityInfo(
                new android.content.ComponentName(context,com.particlesdevs.photoncamera.ui.SplashActivity.class),
                android.content.pm.PackageManager.GET_META_DATA);
        assertEquals(R.xml.shortcuts,splash.metaData.getInt("android.app.shortcuts"));
        String id=null,target=null,pkg=null,action=null;int shortLabel=0,longLabel=0,icon=0;
        try(android.content.res.XmlResourceParser xml=context.getResources().getXml(R.xml.shortcuts)){
            String ns="http://schemas.android.com/apk/res/android";
            for(int e=xml.next();e!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT;e=xml.next()){
                if(e!=org.xmlpull.v1.XmlPullParser.START_TAG)continue;
                if("shortcut".equals(xml.getName())){id=xml.getAttributeValue(ns,"shortcutId");
                    shortLabel=xml.getAttributeResourceValue(ns,"shortcutShortLabel",0);longLabel=xml.getAttributeResourceValue(ns,"shortcutLongLabel",0);
                    icon=xml.getAttributeResourceValue(ns,"icon",0);}
                // P183: the package is the build's own (a string the launcher resolves, as here), not a fixed name
                if("intent".equals(xml.getName())){target=xml.getAttributeValue(ns,"targetClass");int ref=xml.getAttributeResourceValue(ns,"targetPackage",0);
                    pkg=ref!=0?context.getString(ref):xml.getAttributeValue(ns,"targetPackage");
                    action=xml.getAttributeValue(ns,"action");}
            }
        }
        assertEquals("settings",id);
        assertEquals(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class.getName(),target);
        assertEquals(context.getPackageName(),pkg);
        assertEquals(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.ACTION_SHORTCUT,action);
        assertEquals(R.string.shortcut_settings_short,shortLabel);assertEquals(R.string.shortcut_settings_long,longLabel);
        assertEquals(R.drawable.shortcut_settings,icon);
        assertTrue(context.getString(shortLabel).length()<=10); // the launcher's short-label limit
        // the icon is the camera's own gear (it follows the P136 restyle)
        assertTrue(context.getDrawable(icon) instanceof android.graphics.drawable.AdaptiveIconDrawable);
        // From the shortcut: the camera's defaults and one module-profile listener, however often it is set up
        com.particlesdevs.photoncamera.api.Settings cache=mock(com.particlesdevs.photoncamera.api.Settings.class);
        camera.when(ScameraApp::getSettings).thenReturn(cache);
        android.content.Intent intent=new android.content.Intent(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.ACTION_SHORTCUT)
                .setClass(context,com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class);
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class,intent)){
            controller.setup();
            assertNotNull(controller.get().getSupportFragmentManager().findFragmentById(R.id.settings_container));
        }
        verify(cache,atLeastOnce()).loadCache(); // and again by the profile listener on the defaults it writes
        PreferenceKeys.setDefaults(context);PreferenceKeys.setDefaults(context);
        Field listeners=SettingsManager.class.getDeclaredField("mListeners");listeners.setAccessible(true);
        assertEquals(1,java.util.Collections.frequency((java.util.Collection<?>)listeners.get(manager),PreferenceKeys.PROFILE_LISTENER));
        assertEquals("0",String.valueOf(prefs.getAll().get(PreferenceKeys.Key.KEY_COUNTDOWN_TIMER.mValue)));
    }

    @Test public void settingsBackPopsOnePageWithoutRestart(){
        try(org.robolectric.android.controller.ActivityController<com.particlesdevs.photoncamera.ui.settings.SettingsActivity> controller=
                org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)) {
            controller.setup();
            com.particlesdevs.photoncamera.ui.settings.SettingsActivity activity=controller.get();
            androidx.fragment.app.FragmentManager fm=activity.getSupportFragmentManager();
            fm.executePendingTransactions();
            com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment root=
                    (com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            PreferenceScreen first=root.findPreference("scam_hybrid_screen");
            assertNotNull(first);activity.onPreferenceStartScreen(root,first);fm.executePendingTransactions();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1,fm.getBackStackEntryCount());
            activity.getOnBackPressedDispatcher().onBackPressed();fm.executePendingTransactions();
            assertEquals(0,fm.getBackStackEntryCount());assertFalse(activity.isFinishing());
            assertSame(root,fm.findFragmentById(R.id.settings_container));
            camera.verify(()->ScameraApp.restartApp(any(android.content.Context.class)),never());
        }
    }

    private static <T extends android.view.View> T descendant(android.view.View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                T found=descendant(group.getChildAt(i),type);if(found!=null)return found;
            }
        }
        return null;
    }
    @Test public void searchOpensRealSettingAndRetainsQueryOnBack() {
        try(org.robolectric.android.controller.ActivityController<com.particlesdevs.photoncamera.ui.settings.SettingsActivity> controller=
                org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)) {
            controller.setup();
            var activity=controller.get();var fm=activity.getSupportFragmentManager();fm.executePendingTransactions();
            // P6b: search is the button of the page header (the toolbar stays hidden)
            assertEquals(android.view.View.GONE,activity.findViewById(R.id.settings_toolbar).getVisibility());
            fm.findFragmentById(R.id.settings_container).requireView().findViewWithTag("settings_search").performClick();fm.executePendingTransactions();
            var search=fm.findFragmentById(R.id.settings_container);
            assertTrue(search instanceof com.particlesdevs.photoncamera.ui.settings.SettingsSearchFragment);
            android.widget.EditText input=descendant(search.requireView(),android.widget.EditText.class);
            input.setText("pref_film_lut_strength");
            var list=descendant(search.requireView(),androidx.recyclerview.widget.RecyclerView.class);
            assertEquals(1,list.getAdapter().getItemCount());
            list.measure(android.view.View.MeasureSpec.makeMeasureSpec(400,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(600,1073741824));list.layout(0,0,400,600);
            assertNotNull(list.findViewHolderForAdapterPosition(0));
            list.findViewHolderForAdapterPosition(0).itemView.performClick();fm.executePendingTransactions();
            var page=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            assertNotNull(page.findPreference("pref_film_lut_strength"));
            activity.getOnBackPressedDispatcher().onBackPressed();fm.executePendingTransactions();
            input=descendant(fm.findFragmentById(R.id.settings_container).requireView(),android.widget.EditText.class);
            assertEquals("pref_film_lut_strength",input.getText().toString());
            camera.verify(()->ScameraApp.restartApp(any(android.content.Context.class)),never());
        }
    }


    /** P6a: no Russian «гибрид»; P55: no old names («LMC», «NICE»); P129: the merge is «SHDR». */
    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void noOldNamesOrRussianHybridInTheSettingsTexts(){
        checkNoOldNames();
    }
    /** The same check over the English texts. */
    @Test public void noOldNamesOrRussianHybridInTheSettingsTextsInEnglish(){
        checkNoOldNames();
    }
    private void checkNoOldNames(){
        PreferenceScreen screen=inflate();SensorConfigPreferenceGenerator.generatePreferences(context,screen);
        List<String> found=new ArrayList<>();int checked=0;
        java.util.ArrayDeque<Preference> queue=new java.util.ArrayDeque<>();queue.add(screen);
        while(!queue.isEmpty()){
            Preference p=queue.poll();
            if(p instanceof PreferenceGroup)for(int i=0;i<((PreferenceGroup)p).getPreferenceCount();i++)queue.add(((PreferenceGroup)p).getPreference(i));
            List<CharSequence> texts=new ArrayList<>(Arrays.asList(p.getTitle(),p.getSummary()));
            if(p instanceof ListPreference&&((ListPreference)p).getEntries()!=null)texts.addAll(Arrays.asList(((ListPreference)p).getEntries()));
            if(p instanceof DialogPreference)texts.add(((DialogPreference)p).getDialogTitle());
            for(CharSequence t:texts){
                if(t==null)continue;checked++;
                String s=t.toString();
                if(oldName(s))found.add(p.getKey()+": "+s);
            }
        }
        assertTrue(found.toString(),found.isEmpty());assertTrue(checked>500);
        // P129: and in every string and string array of the APK (the shade, the gallery, dialogs), not only the settings rows
        for(Field f:R.string.class.getFields())try{String s=context.getString(f.getInt(null));if(oldName(s))found.add(f.getName()+": "+s);}catch(Exception ignored){}
        for(Field f:R.array.class.getFields())try{for(String s:context.getResources().getStringArray(f.getInt(null)))if(s!=null&&oldName(s))found.add(f.getName()+": "+s);}catch(Exception ignored){}
        assertTrue(found.toString(),found.isEmpty());
        assertEquals("SHDR",String.valueOf(screen.findPreference("scam_hybrid_screen").getTitle()));
    }

    /**
     * P138 (owner's screenshot: «Кадры теней: EV над N» at its minimum 1.00 with half a thumb): at both ends of the range the
     * slider's thumb is drawn whole - no view between it and the card clips it - in rows with and without an icon.
     */
    @Test public void sliderThumbIsNeverClipped(){
        PreferenceScreen screen=inflate();
        int checked=0;
        for(String key:new String[]{"pref_scam_hybrid_shasta_ev","pref_scam_hybrid_dn_luma_mult","pref_scam_hybrid_dn_sabre_luma_mult"}){
            Preference p=screen.findPreference(key);assertNotNull(key,p);
            android.view.View card=row(p);
            android.widget.SeekBar bar=card.findViewById(R.id.seekbar);
            int w=Math.round(400*context.getResources().getDisplayMetrics().density);
            for(int progress:new int[]{0,bar.getMax()}){
                bar.setProgress(progress);
                card.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED));
                card.layout(0,0,w,card.getMeasuredHeight());
                // AbsSeekBar.drawThumb: translate(paddingLeft - thumbOffset, paddingTop), then the thumb's bounds
                android.graphics.Rect r=new android.graphics.Rect(bar.getThumb().getBounds());
                assertTrue(key+" thumb laid out",r.width()>0);
                r.offset(bar.getPaddingLeft()-bar.getThumbOffset(),bar.getPaddingTop());
                android.view.View child=bar;
                while(child!=card){
                    android.view.ViewGroup parent=(android.view.ViewGroup)child.getParent();
                    if(parent.getClipChildren())assertTrue(key+"@"+progress+": clipped by "+child+" "+r,
                            r.left>=0&&r.top>=0&&r.right<=child.getWidth()&&r.bottom<=child.getHeight());
                    r.offset(child.getLeft(),child.getTop());
                    if(parent.getClipToPadding())assertTrue(key+"@"+progress+": clipped by the padding of "+parent+" "+r,
                            r.left>=parent.getPaddingLeft()&&r.right<=parent.getWidth()-parent.getPaddingRight());
                    child=parent;
                }
                assertTrue(key+"@"+progress+": outside the card "+r,r.left>=0&&r.right<=card.getWidth());
                checked++;
            }
        }
        assertEquals(6,checked);
    }

    /**
     * P137: a slider's exact-value editor is a sheet in the camera's style (no stock dialog): the value typed in place,
     * «Применить»; and «В шторку» on the title line of every value editor pins the setting in the shade and takes it out.
     */
    @Test public void valueEditorsAreSheetsAndPinToTheShade(){
        ShadeCatalog.reset();
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();
            final float[] saved={Float.NaN};
            String key="pref_scam_hybrid_shasta_ev";
            com.google.android.material.bottomsheet.BottomSheetDialog d=com.particlesdevs.photoncamera.ui.controls.PrecisionEditor.show(
                    activity,key,"Кадры теней: EV над N",1,4,1.5,1,true,v->saved[0]=v);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            android.view.View root=d.getWindow().getDecorView();
            assertNull("no stock dialog",org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
            android.widget.TextView value=root.findViewWithTag("precision_value");assertEquals("1.5",value.getText().toString());
            value.performClick();
            android.widget.EditText field=root.findViewWithTag("precision_field");
            assertEquals(android.view.View.VISIBLE,field.getVisibility());
            field.setText("2,5");
            root.findViewWithTag("sheet_save").performClick();
            assertEquals(2.5f,saved[0],1e-6f);assertFalse(d.isShowing());
            // out of range: the sheet stays, nothing is saved
            saved[0]=Float.NaN;
            d=com.particlesdevs.photoncamera.ui.controls.PrecisionEditor.show(activity,key,"x",1,4,1.5,1,true,v->saved[0]=v);
            root=d.getWindow().getDecorView();
            ((android.widget.TextView)root.findViewWithTag("precision_value")).performClick();
            ((android.widget.EditText)root.findViewWithTag("precision_field")).setText("9");
            root.findViewWithTag("sheet_save").performClick();
            assertTrue(Float.isNaN(saved[0]));assertTrue(d.isShowing());
            // «В шторку»: pinned at the end of the shade, then out again
            android.widget.TextView pin=root.findViewWithTag("sheet_pin");assertNotNull(pin);
            assertFalse(ShadeTiles.isPinned(prefs,key));
            pin.performClick();
            assertTrue(ShadeTiles.isPinned(prefs,key));assertEquals(key,ShadeTiles.stored(prefs).get(ShadeTiles.stored(prefs).size()-1));
            assertTrue(pin.isSelected());
            pin.performClick();
            assertFalse(ShadeTiles.isPinned(prefs,key));assertFalse(pin.isSelected());
            d.dismiss();
            // P167: no limit - a shade of 30 tiles takes one more
            java.util.List<String> many=new java.util.ArrayList<>();for(int i=0;i<30;i++)many.add("tile"+i);
            ShadeTiles.save(prefs,many);
            assertTrue(ShadeTiles.toggle(prefs,key));assertEquals(31,ShadeTiles.stored(prefs).size());
            ShadeTiles.save(prefs,new java.util.ArrayList<>(ShadeCatalog.defaultTiles()));
            // the list picker of a setting has the pin too; a key the shade cannot show has none
            ListPreference output=new ListPreference(activity);output.setKey("pref_scam_hybrid_output");output.setTitle("x");
            output.setEntries(new CharSequence[]{"a"});output.setEntryValues(new CharSequence[]{"sensor"});
            assertTrue(com.particlesdevs.photoncamera.ui.settings.SettingsStyle.showDialog(activity,output));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNotNull(latestSheet().findViewWithTag("sheet_pin"));
            assertNull(com.particlesdevs.photoncamera.ui.controls.PrecisionEditor.show(activity,"no_such_key","x",0,1,0,0,false,v->{})
                    .getWindow().getDecorView().findViewWithTag("sheet_pin"));
        }
        ShadeCatalog.reset();
    }

    private static android.view.View latestSheet(){
        android.app.Dialog d=org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertTrue(String.valueOf(d),d instanceof com.google.android.material.bottomsheet.BottomSheetDialog);
        return d.getWindow().getDecorView();
    }

    /** P134: a grading zone's hue acts only with its strength; the AgX saturation only with the «Custom» AgX look. */
    @Test public void colourRowsSayWhenTheyAct(){
        java.util.Map<String,Object> v=new java.util.HashMap<>();
        assertNotNull(new SettingsAvailability(v).reason("pref_scam_hybrid_colour_grade_highlights_hue"));
        v.put("pref_scam_hybrid_colour_grade_highlights_str","30");
        assertNull(new SettingsAvailability(v).reason("pref_scam_hybrid_colour_grade_highlights_hue"));
        assertNotNull(new SettingsAvailability(v).reason("pref_scam_hybrid_colour_grade_shadows_hue"));
        assertNull(new SettingsAvailability(v).reason("pref_scam_hybrid_ark_agx_sat"));
        v.put("pref_scam_hybrid_ark_agx_look","1");
        assertNotNull(new SettingsAvailability(v).reason("pref_scam_hybrid_ark_agx_sat"));
        for(String k:new String[]{"pref_scam_hybrid_colour_warmth","pref_scam_hybrid_colour_mix_red_hue","pref_scam_hybrid_colour_vib_shadows",
                "pref_scam_hybrid_colour_grade_mids_hue","pref_scam_hybrid_colour_grade_blend"})
            assertNotNull(k+" needs numeric bounds",SettingsNumericRules.bounds(k));
    }

    /** P139: in every kind of row the title (medium, larger) and its description (smaller, dimmer) look different. */
    /** The vendor tag editor is a sheet like the other editors, and the key type Session (X300 Ultra forceSensorMode) is a choice. */
    @Test public void vendorTagEditorIsASheetWithTheSessionType(){
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();
            String sensor="t_vendor";
            TunableKeyManager.saveKeys(activity,sensor,new java.util.ArrayList<>());
            final int[] done={0};
            var d=com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableKeyDialog.show(activity,sensor,-1,()->done[0]++);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNotNull(d);assertNull("no stock dialog",org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
            android.view.View root=d.getWindow().getDecorView();
            assertNotNull(root.findViewWithTag("vendor_type_CaptureRequest"));
            android.view.View session=root.findViewWithTag("vendor_type_Session");assertNotNull(session);
            assertTrue("a new key goes into every request",root.findViewWithTag("vendor_type_CaptureRequest").isSelected());
            session.performClick();assertTrue(session.isSelected());
            ((android.widget.EditText)root.findViewWithTag("vendor_name")).setText("vivo.control.forceSensorMode");
            ((android.widget.EditText)root.findViewWithTag("vendor_value")).setText("six");
            root.findViewWithTag("sheet_save").performClick();
            assertTrue("a value that does not fit Integer keeps the sheet",d.isShowing());assertEquals(0,done[0]);
            ((android.widget.EditText)root.findViewWithTag("vendor_value")).setText("6");
            root.findViewWithTag("sheet_save").performClick();
            assertFalse(d.isShowing());assertEquals(1,done[0]);
            java.util.List<com.particlesdevs.photoncamera.api.VendorTagUtils.TunableKey> keys=TunableKeyManager.loadKeys(activity,sensor);
            assertEquals(1,keys.size());
            assertEquals(com.particlesdevs.photoncamera.api.VendorTagUtils.TunableKey.TYPE_SESSION,keys.get(0).type);
            assertEquals("Integer",keys.get(0).valueType);assertEquals("6",keys.get(0).value);
            // editing keeps the type and offers deleting
            d=com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableKeyDialog.show(activity,sensor,0,()->done[0]++);
            root=d.getWindow().getDecorView();
            assertTrue(root.findViewWithTag("vendor_type_Session").isSelected());
            root.findViewWithTag("vendor_delete").performClick();
            assertTrue(TunableKeyManager.loadKeys(activity,sensor).isEmpty());
        }
    }
    /**
     * P144: «Кривая AgX и ACES» - AgX, then ACES, Uchimura and the own curve, each switched on in its own group that holds only
     * its own settings; one operator at a time; AgX's rows say which one works instead.
     */
    @Test public void toneOperatorsInTheirOwnGroups(){
        PreferenceScreen root=inflate();
        androidx.preference.PreferenceGroup screen=root.findPreference("scam_hybrid_ark_curves_screen");
        assertNotNull(screen);
        String[] order={"scam_hybrid_ark_agx_category","scam_hybrid_ark_aces_category","scam_hybrid_ark_uchi_category","scam_hybrid_ark_curve_category"};
        for(int i=0;i<order.length;i++)assertEquals(order[i],screen.getPreference(i).getKey());
        String[][] groups={{"scam_hybrid_ark_aces_category","pref_scam_hybrid_ark_aces_on","pref_scam_hybrid_ark_aces_"},
                {"scam_hybrid_ark_uchi_category","pref_scam_hybrid_ark_uchi_on","pref_scam_hybrid_ark_uchi_"},
                {"scam_hybrid_ark_curve_category","pref_scam_hybrid_ark_curve_on","pref_scam_hybrid_ark_curve_"}};
        for(String[] g:groups){
            androidx.preference.PreferenceGroup cat=root.findPreference(g[0]);
            assertTrue(g[0]+" starts with its switch",cat.getPreference(0) instanceof androidx.preference.TwoStatePreference);
            assertEquals(g[1],cat.getPreference(0).getKey());
            assertFalse("off by default",((androidx.preference.TwoStatePreference)cat.getPreference(0)).isChecked());
            for(int i=0;i<cat.getPreferenceCount();i++)
                assertTrue("only its own settings in "+g[0]+": "+cat.getPreference(i).getKey(),cat.getPreference(i).getKey().startsWith(g[2]));
        }
        assertEquals("pref_scam_hybrid_ark_aces_on",root.findPreference("pref_scam_hybrid_ark_aces_a").getDependency());
        assertEquals("pref_scam_hybrid_ark_uchi_on",root.findPreference("pref_scam_hybrid_ark_uchi_m").getDependency());
        assertTrue(root.findPreference("pref_scam_hybrid_ark_curve_points") instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurvePreference);
        assertEquals("pref_scam_hybrid_ark_curve_on",root.findPreference("pref_scam_hybrid_ark_curve_points").getDependency());
        // AgX's rows tell which operator works instead
        java.util.Map<String,Object> values=new java.util.HashMap<>();
        assertNull(new SettingsAvailability(values).reason("pref_scam_hybrid_ark_agx_look"));
        values.put("pref_scam_hybrid_ark_uchi_on",true);
        String why=new SettingsAvailability(values).reason("pref_scam_hybrid_ark_agx_look");
        assertNotNull(why);assertTrue(why,why.contains("Uchimura"));
    }

    /** P144: one tone operator at a time - switching one on in the settings turns the other two off. */
    @Test public void oneToneOperatorAtATime(){
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();
            SharedPreferences sp=prefs; // the settings' own store (SettingsManager), which the screen listens to
            sp.edit().putBoolean("pref_scam_hybrid_ark_uchi_on",true).commit();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            sp.edit().putBoolean("pref_scam_hybrid_ark_aces_on",true).commit();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(sp.getBoolean("pref_scam_hybrid_ark_aces_on",false));
            assertFalse("Uchimura went off",sp.getBoolean("pref_scam_hybrid_ark_uchi_on",true));
            sp.edit().putBoolean("pref_scam_hybrid_ark_curve_on",true).commit();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse("ACES went off",sp.getBoolean("pref_scam_hybrid_ark_aces_on",true));
            assertTrue(sp.getBoolean("pref_scam_hybrid_ark_curve_on",false));
        }
    }

    /**
     * P144: the own curve's graph - a tap adds a point, a drag moves it, «Прямая» / «S-кривая» reset it; stored as x:y pairs. The
     * touched point stays selected: its «Яркость» slider and its typed «Экспозиция» move it, ‹ › step, «Удалить» removes it.
     */
    @Test public void ownCurveIsEditedOnItsGraph(){
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();
            PreferenceManager pm=new PreferenceManager(activity);PreferenceScreen screen=pm.createPreferenceScreen(activity);
            var pref=new com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurvePreference(activity);
            pref.setKey("pref_scam_hybrid_ark_curve_points");screen.addPreference(pref);
            android.view.View row=android.view.LayoutInflater.from(activity).inflate(R.layout.preference_tone_curve,null);
            androidx.preference.PreferenceViewHolder holder=androidx.preference.PreferenceViewHolder.createInstanceForTests(row);
            pref.onBindViewHolder(holder);
            var graph=(com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurveView)row.findViewWithTag("tone_curve_view");
            assertNotNull(graph);
            int w=600;row.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED));
            row.layout(0,0,w,row.getMeasuredHeight());
            assertEquals("the default S",5,graph.curve().size());
            // a tap on an empty spot of the graph (x 0.6, y 0.3 of the plot) adds a point there, a drag moves it
            float x=graph.getLeft()+graph.getWidth()*0.6f,y=graph.getHeight()*0.62f;
            long t=android.os.SystemClock.uptimeMillis();
            graph.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_DOWN,graph.getWidth()*0.6f,y,0));
            graph.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t+50,android.view.MotionEvent.ACTION_MOVE,graph.getWidth()*0.6f,y-40,0));
            graph.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t+80,android.view.MotionEvent.ACTION_UP,graph.getWidth()*0.6f,y-40,0));
            assertEquals("a sixth point",6,graph.curve().size());
            String stored=pref.points();
            assertEquals(6,stored.split(",").length);
            assertEquals(stored,com.particlesdevs.photoncamera.processing.color.ToneCurve.parse(stored).serialize());
            // the new point is the selected one
            int sel=graph.selected();
            assertTrue("an inner point",sel>0&&sel<graph.curve().size()-1);
            assertEquals("Point "+(sel+1)+" of 6",((android.widget.TextView)row.findViewWithTag("tone_curve_point_label")).getText().toString());
            // the brightness slider dragged to its end: the point goes to 100 %
            android.widget.SeekBar yBar=row.findViewWithTag("tone_curve_y");
            assertTrue(yBar.isEnabled());
            t+=500;
            yBar.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_DOWN,yBar.getWidth()*0.5f,yBar.getHeight()/2f,0));
            yBar.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t+40,android.view.MotionEvent.ACTION_MOVE,yBar.getWidth()-1f,yBar.getHeight()/2f,0));
            yBar.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t+80,android.view.MotionEvent.ACTION_UP,yBar.getWidth()-1f,yBar.getHeight()/2f,0));
            assertEquals(1f,graph.curve().get(sel).y,1e-4f);
            assertEquals("100.0 %",((android.widget.TextView)row.findViewWithTag("tone_curve_y_value")).getText().toString());
            assertEquals("stored when the finger lifts",1f,com.particlesdevs.photoncamera.processing.color.ToneCurve.parse(pref.points()).get(sel).y,1e-4f);
            // the exposure typed in: +1.5 EV (x 0.65), between its neighbours
            row.findViewWithTag("tone_curve_x_value").performClick();
            android.app.Dialog sheet=org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull(sheet);
            android.view.View sv=sheet.getWindow().getDecorView();
            ((android.widget.TextView)sv.findViewWithTag("precision_value")).performClick();
            ((android.widget.EditText)sv.findViewWithTag("precision_field")).setText("1,5");
            sv.findViewWithTag("sheet_save").performClick();
            assertFalse(sheet.isShowing());
            assertEquals(0.65f,graph.curve().get(sel).x,1e-4f);
            assertEquals("+1.50 EV",((android.widget.TextView)row.findViewWithTag("tone_curve_x_value")).getText().toString());
            assertEquals(0.65f,com.particlesdevs.photoncamera.processing.color.ToneCurve.parse(pref.points()).get(sel).x,1e-4f);
            // past a neighbour: refused, the sheet stays
            row.findViewWithTag("tone_curve_x_value").performClick();
            sheet=org.robolectric.shadows.ShadowDialog.getLatestDialog();sv=sheet.getWindow().getDecorView();
            ((android.widget.TextView)sv.findViewWithTag("precision_value")).performClick();
            ((android.widget.EditText)sv.findViewWithTag("precision_field")).setText("4");
            sv.findViewWithTag("sheet_save").performClick();
            assertTrue(sheet.isShowing());sheet.dismiss();
            assertEquals(0.65f,graph.curve().get(sel).x,1e-4f);
            // ‹ steps back; the black end keeps its exposure and cannot go
            row.findViewWithTag("tone_curve_prev").performClick();
            assertEquals(sel-1,graph.selected());
            graph.select(0);
            assertFalse(row.findViewWithTag("tone_curve_x").isEnabled());
            assertFalse(row.findViewWithTag("tone_curve_delete").isEnabled());
            assertFalse(row.findViewWithTag("tone_curve_prev").isEnabled());
            // «Удалить» removes an inner point
            graph.select(sel);
            row.findViewWithTag("tone_curve_delete").performClick();
            assertEquals(5,graph.curve().size());
            assertEquals(5,pref.points().split(",").length);
            row.findViewWithTag("tone_curve_identity").performClick();
            assertEquals("0.0000:0.0000,1.0000:1.0000",pref.points());
            row.findViewWithTag("tone_curve_default").performClick();
            assertEquals(com.particlesdevs.photoncamera.processing.color.ToneCurve.DEFAULT_POINTS,pref.points());
            // P178: ↶ walks back through the shortcuts and the deletion, ↷ forth again, each step stored
            row.findViewWithTag("tone_curve_undo").performClick();
            assertEquals("0.0000:0.0000,1.0000:1.0000",pref.points());
            row.findViewWithTag("tone_curve_undo").performClick();
            assertEquals("the curve before the straight line",5,pref.points().split(",").length);
            row.findViewWithTag("tone_curve_redo").performClick();
            row.findViewWithTag("tone_curve_redo").performClick();
            assertEquals(com.particlesdevs.photoncamera.processing.color.ToneCurve.DEFAULT_POINTS,pref.points());
            assertFalse("nothing further",row.findViewWithTag("tone_curve_redo").isEnabled());
        }
    }

    /**
     * The full reset (owner, 2026-10-16: «кнопка сброса должна полностью все настройки сбрасывать на дефолтные», «Полный сброс
     * не должен удалять конфиги»): every preferences file empty - also the copies Android keeps in memory, so a write while
     * the camera still runs brings nothing old back - the developer switches set aside, the saved configs untouched.
     */
    @Test public void resetClearsEverySettingAndKeepsTheConfigs() throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        SharedPreferences def=androidx.preference.PreferenceManager.getDefaultSharedPreferences(app);
        def.edit().putString("pref_jpeg_quality","90").putBoolean("pref_scam_hybrid_ark_curve_on",true).commit();
        SharedPreferences module=app.getSharedPreferences("module_profile_v2_back0",0);
        module.edit().putFloat("pref_scam_hybrid_ark_aces_a",3f).commit();
        prefs.edit().putBoolean("pref_scam_hybrid_ark_uchi_on",true).commit();
        java.io.File dev=ScamDevFile.file(app);dev.getParentFile().mkdirs();
        java.nio.file.Files.write(dev.toPath(),"hybrid 1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.io.File configs=new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),"SCAMERA/XML");
        configs.mkdirs();java.io.File saved=new java.io.File(configs,"Day.xml");
        java.nio.file.Files.write(saved.toPath(),"<config/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(BackupRestoreUtil.resetPreferences(app));
        assertTrue("default file empty in memory: "+def.getAll(),def.getAll().isEmpty());
        assertTrue("module profile empty: "+module.getAll(),module.getAll().isEmpty());
        assertTrue("the settings' own store empty: "+prefs.getAll(),prefs.getAll().isEmpty());
        // a write right after (the camera runs until the restart) brings nothing old back
        def.edit().putInt("zoom_last",2).commit();
        assertEquals(java.util.Set.of("zoom_last"),app.getSharedPreferences(app.getPackageName()+"_preferences",0).getAll().keySet());
        assertFalse("developer switches set aside",dev.isFile());
        assertTrue(new java.io.File(dev.getParentFile(),"scam_dev.txt.bak").isFile());
        assertTrue("the saved config stays",saved.isFile());
        // the folder grant: Download/SCAMERA (or Download) reaches XML and ToneCurves, other folders do not
        assertTrue(com.particlesdevs.photoncamera.util.ScameraFolder.covers("primary:Download/SCAMERA","primary:Download/SCAMERA/XML"));
        assertTrue(com.particlesdevs.photoncamera.util.ScameraFolder.covers("primary:Download","primary:Download/SCAMERA/ToneCurves"));
        assertTrue(com.particlesdevs.photoncamera.util.ScameraFolder.covers("primary:","primary:Download/SCAMERA/XML"));
        assertTrue(com.particlesdevs.photoncamera.util.ScameraFolder.covers("primary:Download/SCAMERA/XML","primary:Download/SCAMERA/XML"));
        assertFalse(com.particlesdevs.photoncamera.util.ScameraFolder.covers("primary:Download/SCAM","primary:Download/SCAMERA/XML"));
        assertFalse(com.particlesdevs.photoncamera.util.ScameraFolder.covers("primary:DCIM","primary:Download/SCAMERA/XML"));
        assertEquals("primary:Download/SCAMERA/XML",com.particlesdevs.photoncamera.util.ScameraFolder.CONFIGS.documentId());
    }

    /** An in-memory Download/SCAMERA/ToneCurves for the curve tests. */
    static final class MemoryCurves implements com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurvePreference.Files {
        final java.util.TreeMap<String,String> files=new java.util.TreeMap<>();
        @Override public String[] list(){return files.keySet().toArray(new String[0]);}
        @Override public String read(String name)throws java.io.IOException{String t=files.get(name);if(t==null)throw new java.io.IOException(name);return t;}
        @Override public String save(String name,String text){files.put(name,text);return folder()+"/"+name;}
        @Override public boolean delete(String name){return files.remove(name)!=null;}
        @Override public String folder(){return com.particlesdevs.photoncamera.util.ScameraFolder.CURVES.relative;}
    }

    /**
     * P144: own curves saved to and loaded from their own folder (Download/SCAMERA/ToneCurves, apart from the configs' XML):
     * «Сохранить» names the file, «Загрузить» lists them with a preview, a tap loads one, the bin deletes it after a second tap,
     * «Из файла…» hands over to the settings screen's picker.
     */
    @Test public void ownCurvesAreSavedAndLoaded(){
        assertEquals("Download/SCAMERA/ToneCurves",com.particlesdevs.photoncamera.util.ScameraFolder.CURVES.relative);
        assertEquals("Download/SCAMERA/XML",com.particlesdevs.photoncamera.util.ConfigFolder.RELATIVE);
        assertTrue(com.particlesdevs.photoncamera.util.ScameraFolder.CURVES.accepts("Night.txt"));
        assertFalse(com.particlesdevs.photoncamera.util.ScameraFolder.CURVES.accepts("Night.xml"));
        assertEquals("My_film_look",com.particlesdevs.photoncamera.util.ScameraFolder.CURVES.baseName("  My film/look.txt "));
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();
            PreferenceManager pm=new PreferenceManager(activity);PreferenceScreen screen=pm.createPreferenceScreen(activity);
            var pref=new com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurvePreference(activity);
            pref.setKey(com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurvePreference.KEY);screen.addPreference(pref);
            MemoryCurves folder=new MemoryCurves();pref.setFiles(folder);
            final int[] picks={0};pref.setImporter(()->picks[0]++);
            android.view.View row=android.view.LayoutInflater.from(activity).inflate(R.layout.preference_tone_curve,null);
            pref.onBindViewHolder(androidx.preference.PreferenceViewHolder.createInstanceForTests(row));
            var graph=(com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurveView)row.findViewWithTag("tone_curve_view");
            // save the straight line as «Linear»
            row.findViewWithTag("tone_curve_identity").performClick();
            row.findViewWithTag("tone_curve_save").performClick();
            android.app.Dialog sheet=org.robolectric.shadows.ShadowDialog.getLatestDialog();android.view.View sv=sheet.getWindow().getDecorView();
            android.widget.EditText name=sv.findViewWithTag("curve_name");
            assertEquals("a free name offered","Curve_1",name.getText().toString());
            assertTrue(((android.widget.TextView)sv.findViewWithTag("curve_folder")).getText().toString().contains("Download/SCAMERA/ToneCurves"));
            name.setText("Linear");sv.findViewWithTag("sheet_save").performClick();
            assertFalse(sheet.isShowing());
            assertEquals(com.particlesdevs.photoncamera.processing.color.ToneCurve.identity().toFileText(),folder.files.get("Linear.txt"));
            // an empty name is refused
            row.findViewWithTag("tone_curve_save").performClick();
            sheet=org.robolectric.shadows.ShadowDialog.getLatestDialog();sv=sheet.getWindow().getDecorView();
            assertEquals("Curve_1",((android.widget.EditText)sv.findViewWithTag("curve_name")).getText().toString());
            ((android.widget.EditText)sv.findViewWithTag("curve_name")).setText("  ");sv.findViewWithTag("sheet_save").performClick();
            assertTrue(sheet.isShowing());sheet.dismiss();
            assertEquals(1,folder.files.size());
            // another curve in the folder, a file that is no curve (not listed)
            folder.files.put("Soft.txt",com.particlesdevs.photoncamera.processing.color.ToneCurve.parse("0:0.05,0.5:0.5,1:0.95").toFileText());
            folder.files.put("notes.txt","hello");
            row.findViewWithTag("tone_curve_load").performClick();
            sheet=org.robolectric.shadows.ShadowDialog.getLatestDialog();sv=sheet.getWindow().getDecorView();
            assertNotNull(sv.findViewWithTag("curve_row_Linear.txt"));
            assertNotNull(sv.findViewWithTag("curve_row_Soft.txt"));
            assertNull("not a curve",sv.findViewWithTag("curve_row_notes.txt"));
            sv.findViewWithTag("curve_row_Soft.txt").performClick();
            assertFalse(sheet.isShowing());
            assertEquals(3,graph.curve().size());
            assertEquals(0.05f,graph.curve().get(0).y,1e-4f);
            assertEquals("loaded and stored",graph.curve().serialize(),pref.points());
            // the bin: the first tap asks, the second deletes the file and folds the row out
            row.findViewWithTag("tone_curve_load").performClick();
            sheet=org.robolectric.shadows.ShadowDialog.getLatestDialog();sv=sheet.getWindow().getDecorView();
            sv.findViewWithTag("curve_bin_Linear.txt").performClick();
            assertTrue(folder.files.containsKey("Linear.txt"));
            android.view.View confirm=sv.findViewWithTag("curve_delete_Linear.txt");
            assertEquals(android.view.View.VISIBLE,confirm.getVisibility());
            confirm.performClick();
            assertFalse(folder.files.containsKey("Linear.txt"));
            assertNull(sv.findViewWithTag("curve_row_Linear.txt"));
            assertTrue(sheet.isShowing());
            // «Из файла…»: the picker of the settings screen
            sv.findViewWithTag("curve_import").performClick();
            assertEquals(1,picks[0]);assertFalse(sheet.isShowing());
            // a picked file goes through the same reading
            var picked=new java.io.ByteArrayInputStream("0:0,0.3:0.2,0.7:0.8,1:1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            com.particlesdevs.photoncamera.processing.color.ToneCurve read=null;
            try{read=com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurvePreference.readCurve(picked);}catch(java.io.IOException e){fail(e.toString());}
            pref.load(read);
            assertEquals(4,graph.curve().size());assertEquals(read.serialize(),pref.points());
            assertEquals("the middle point selected",2,graph.selected());
            // empty folder: the hint names it
            folder.files.clear();
            row.findViewWithTag("tone_curve_load").performClick();
            sheet=org.robolectric.shadows.ShadowDialog.getLatestDialog();sv=sheet.getWindow().getDecorView();
            assertTrue(((android.widget.TextView)sv.findViewWithTag("curve_empty")).getText().toString().contains("Download/SCAMERA/ToneCurves"));
            sheet.dismiss();
        }
    }

    /**
     * P144: the curves page as the phone shows it (400 x 880 dp, xhdpi), kept in build/reports/tone-curve for a look - the own
     * curve's group, a new point being dragged, an edited curve with more points, then the ACES and Uchimura groups.
     */
    @Test @Config(qualifiers="ru-w400dp-h880dp-xhdpi") public void toneCurvePageRenderedForReview() throws Exception {
        prefs.edit().putBoolean("pref_scam_hybrid_ark_curve_on",true).commit();
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();fm.executePendingTransactions();
            var root=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            activity.onPreferenceStartScreen(root,root.findPreference("scam_hybrid_ark_tone_screen"));fm.executePendingTransactions();
            var tone=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            activity.onPreferenceStartScreen(tone,tone.findPreference("scam_hybrid_ark_curves_screen"));fm.executePendingTransactions();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            var page=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            assertEquals("scam_hybrid_ark_curves_screen",page.getPreferenceScreen().getKey());
            float density=activity.getResources().getDisplayMetrics().density;
            int w=Math.round(400*density),h=Math.round(880*density);
            android.view.View view=page.requireView();
            frame(view,w,h);
            var list=page.getListView();
            int curvePos=((androidx.preference.PreferenceGroup.PreferencePositionCallback)list.getAdapter()).getPreferenceAdapterPosition("scam_hybrid_ark_curve_category");
            ((androidx.recyclerview.widget.LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(curvePos,Math.round(8*density));
            frame(view,w,h);
            var graph=(com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurveView)list.findViewWithTag("tone_curve_view");
            assertNotNull("the graph is on screen",graph);
            assertTrue("on when its switch is on",graph.isEnabled());
            shot(view,w,h,"1-own-curve");
            // a tap on an empty spot adds a point; it shows filled while the finger drags it
            // the plot inside the graph: 14 dp around, 22 dp under it for the EV labels (ToneCurveView.onSizeChanged)
            float pad=14*density,gw=graph.getWidth(),gh=graph.getHeight();
            java.util.function.BiFunction<Float,Float,float[]> at=(x,y)->new float[]{pad+x*(gw-2*pad),gh-22*density-y*(gh-36*density)};
            long t=android.os.SystemClock.uptimeMillis();
            float[] a=at.apply(0.62f,0.66f),b=at.apply(0.62f,0.74f);
            graph.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_DOWN,a[0],a[1],0));
            graph.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t+60,android.view.MotionEvent.ACTION_MOVE,b[0],b[1],0));
            shot(view,w,h,"2-drag-new-point");
            graph.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t+90,android.view.MotionEvent.ACTION_UP,b[0],b[1],0));
            assertEquals(6,graph.curve().size());
            // more points: as many as wanted
            float[][] taps={{0.12f,0.05f},{0.375f,0.30f},{0.88f,0.95f}};
            for(float[] p:taps){
                t+=500;float[] q=at.apply(p[0],p[1]);
                graph.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_DOWN,q[0],q[1],0));
                graph.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t+40,android.view.MotionEvent.ACTION_UP,q[0],q[1],0));
            }
            assertEquals(9,graph.curve().size());
            assertEquals(9,prefs.getString("pref_scam_hybrid_ark_curve_points","").split(",").length);
            shot(view,w,h,"3-more-points");
            // the selected point's sliders: the point at +2.5 EV raised by its brightness slider
            graph.select(6);
            list.scrollBy(0,Math.round(330*density));frame(view,w,h);
            android.widget.SeekBar yBar=list.findViewWithTag("tone_curve_y");
            assertNotNull(yBar);
            float cy=yBar.getHeight()/2f,track=yBar.getWidth()-yBar.getPaddingLeft()-yBar.getPaddingRight();
            float from=yBar.getPaddingLeft()+track*graph.curve().get(6).y,to=yBar.getPaddingLeft()+track*0.90f;
            t+=500;
            yBar.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t,android.view.MotionEvent.ACTION_DOWN,from,cy,0));
            yBar.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t+40,android.view.MotionEvent.ACTION_MOVE,to,cy,0));
            yBar.dispatchTouchEvent(android.view.MotionEvent.obtain(t,t+80,android.view.MotionEvent.ACTION_UP,to,cy,0));
            assertEquals(0.90f,graph.curve().get(6).y,0.01f);
            frame(view,w,h);
            shot(view,w,h,"4-point-sliders");
            // a value typed in: the exposure's sheet
            list.findViewWithTag("tone_curve_x_value").performClick();
            android.app.Dialog sheet=org.robolectric.shadows.ShadowDialog.getLatestDialog();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            android.view.View sv=sheet.getWindow().getDecorView();
            ((android.widget.TextView)sv.findViewWithTag("precision_value")).performClick();
            ((android.widget.EditText)sv.findViewWithTag("precision_field")).setText("2.8");
            frame(sv,w,h);
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);view.draw(canvas);canvas.drawColor(0x99000000);sv.draw(canvas);
            java.io.File dir=new java.io.File("build/reports/tone-curve");dir.mkdirs();
            try(var out=new java.io.FileOutputStream(new java.io.File(dir,"5-type-value.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
            sv.findViewWithTag("sheet_save").performClick();
            assertEquals(0.78f,graph.curve().get(6).x,1e-4f);
            // «Загрузить»: the saved curves of Download/SCAMERA/ToneCurves with their previews
            var curvePref=(com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurvePreference)page.findPreference(
                    com.particlesdevs.photoncamera.ui.settings.custompreferences.ToneCurvePreference.KEY);
            MemoryCurves folder=new MemoryCurves();curvePref.setFiles(folder);
            folder.files.put("Плёнка_мягкая.txt",com.particlesdevs.photoncamera.processing.color.ToneCurve.parse("0:0.04,0.3:0.22,0.5:0.5,0.75:0.8,1:0.96").toFileText());
            folder.files.put("Контраст.txt",com.particlesdevs.photoncamera.processing.color.ToneCurve.parse("0:0,0.25:0.1,0.5:0.5,0.75:0.92,1:1").toFileText());
            folder.files.put("Прямая.txt",com.particlesdevs.photoncamera.processing.color.ToneCurve.identity().toFileText());
            folder.files.put("Ночь_тени.txt",com.particlesdevs.photoncamera.processing.color.ToneCurve.parse("0:0,0.2:0.16,0.45:0.5,0.8:0.86,1:1").toFileText());
            list.scrollBy(0,Math.round(400*density));frame(view,w,h);
            android.view.View loadChip=list.findViewWithTag("tone_curve_load");
            assertNotNull("«Загрузить» on screen",loadChip);
            shot(view,w,h,"6-save-load-buttons");
            loadChip.performClick();
            sheet=org.robolectric.shadows.ShadowDialog.getLatestDialog();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            sv=sheet.getWindow().getDecorView();
            sv.findViewWithTag("curve_bin_Прямая.txt").performClick();
            frame(sv,w,h);
            bitmap=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
            canvas=new android.graphics.Canvas(bitmap);view.draw(canvas);canvas.drawColor(0x99000000);sv.draw(canvas);
            try(var out=new java.io.FileOutputStream(new java.io.File(dir,"7-load-curve.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
            sheet.dismiss();
            // «Сохранить»: the name sheet
            loadChip=list.findViewWithTag("tone_curve_save");
            loadChip.performClick();
            sheet=org.robolectric.shadows.ShadowDialog.getLatestDialog();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            sv=sheet.getWindow().getDecorView();
            ((android.widget.EditText)sv.findViewWithTag("curve_name")).setText("Мой_закат");
            frame(sv,w,h);
            bitmap=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
            canvas=new android.graphics.Canvas(bitmap);view.draw(canvas);canvas.drawColor(0x99000000);sv.draw(canvas);
            try(var out=new java.io.FileOutputStream(new java.io.File(dir,"8-save-curve.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
            sv.findViewWithTag("sheet_save").performClick();
            assertTrue(folder.files.containsKey("Мой_закат.txt"));
            // ACES and Uchimura in their own groups (the jump without the item animator: a finger scroll binds the rows plainly,
            // while a jump in the same pass as the rows the curve's save changed would fade them in at full alpha)
            list.setItemAnimator(null);
            int acesPos=((androidx.preference.PreferenceGroup.PreferencePositionCallback)list.getAdapter()).getPreferenceAdapterPosition("scam_hybrid_ark_aces_category");
            ((androidx.recyclerview.widget.LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(acesPos,Math.round(8*density));
            frame(view,w,h);
            shot(view,w,h,"9-aces-uchimura");
        }
    }
    private void frame(android.view.View view,int w,int h){
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        int exact=android.view.View.MeasureSpec.EXACTLY;
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,exact),android.view.View.MeasureSpec.makeMeasureSpec(h,exact));view.layout(0,0,w,h);
        // the list's item animations (a jump in the same pass as a changed row fades the new rows in) run to their end
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
    }
    private void shot(android.view.View view,int w,int h,String name) throws Exception {
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
        java.io.File dir=new java.io.File("build/reports/tone-curve");dir.mkdirs();
        try(var out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
    }

    /** «Кривая AgX и ACES» is the first row of «Тон ARK» (owner, 2026-10-15). */
    @Test public void curvesOpenTheArkTonePage(){
        PreferenceScreen root=inflate();
        androidx.preference.PreferenceGroup tone=root.findPreference("scam_hybrid_ark_tone_screen");
        assertNotNull(tone);
        assertEquals("scam_hybrid_ark_curves_screen",tone.getPreference(0).getKey());
        assertNotNull(root.findPreference("pref_scam_hybrid_ark_agx_look"));assertNotNull(root.findPreference("pref_scam_hybrid_ark_aces_toe"));
    }
    /** Every page of the settings root has its own icon («Цвет» and «Интерфейс» both showed the palette, 2026-10-15). */
    @Test public void rootPagesHaveTheirOwnIcons() throws Exception{
        String xml;
        try(java.io.InputStream in=new java.io.FileInputStream("src/main/res/xml/preferences.xml")){xml=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        java.util.Map<String,String> byIcon=new java.util.HashMap<>();
        // SIMPLE_UI §4: the pages of the root's groups (one level under the root's categories)
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?m)^    <PreferenceScreen [^>]*>").matcher(xml);
        int pages=0;
        while(m.find()){
            String tag=m.group();
            java.util.regex.Matcher key=java.util.regex.Pattern.compile("android:key=\"([^\"]+)\"").matcher(tag);
            java.util.regex.Matcher icon=java.util.regex.Pattern.compile("android:icon=\"([^\"]+)\"").matcher(tag);
            assertTrue(tag,key.find());assertTrue("root page without an icon: "+key.group(1),icon.find());
            String before=byIcon.put(icon.group(1),key.group(1));
            assertNull(icon.group(1)+" on both "+before+" and "+key.group(1),before);
            pages++;
        }
        assertTrue("root pages found: "+pages,pages>=7);
        assertEquals("@drawable/settings_ic_ui",byIcon.entrySet().stream().filter(e->e.getValue().equals("interface_settings_screen")).findFirst().get().getKey());
    }
    @Test public void titlesStandOutFromTheirDescriptions(){
        PreferenceScreen screen=inflate();
        // a switch, a list, a slider and a page row
        for(String key:new String[]{"pref_scam_hybrid_shasta","pref_scam_hybrid_output","pref_scam_hybrid_shasta_ev","scam_hybrid_screen"}){
            Preference p=screen.findPreference(key);assertNotNull(key,p);
            android.view.View r=row(p);
            android.widget.TextView title=r.findViewById(android.R.id.title),summary=r.findViewById(android.R.id.summary);
            assertNotNull(key,title);assertNotNull(key,summary);
            assertEquals(key,com.particlesdevs.photoncamera.ui.settings.SettingsStyle.SUMMARY,summary.getCurrentTextColor());
            assertNotEquals(key,summary.getCurrentTextColor(),title.getCurrentTextColor());
            assertTrue(key,title.getTextSize()>summary.getTextSize());
            assertNotNull(key+": the title's face",title.getTypeface());
            assertNotEquals(key+": title and description share a face",summary.getTypeface(),title.getTypeface());
        }
    }

    /**
     * P128 (settings audit): one topic in one place - the highlight / shadow frames each in their category of one page, the
     * highlight denoise rows and the dark-colour rows side by side on the noise page, every diagnostics row in «Система ›
     * Диагностика»; and no row title written straight into the XML (each is a translated string).
     */
    @Test public void oneTopicInOnePlaceAndNoLiteralTitles() throws Exception {
        PreferenceScreen screen=inflate();
        for(String k:new String[]{"pref_scam_hybrid_bento","pref_scam_hybrid_bento_weight","pref_scam_hybrid_bento_sigma",
                "pref_scam_hybrid_bento_chroma_sigma","pref_scam_hybrid_bento_scam"})
            assertEquals(k,"scam_hybrid_capture_category",screen.findPreference(k).getParent().getKey());
        for(String k:new String[]{"pref_scam_hybrid_shasta","pref_scam_hybrid_shasta_sharpness","pref_scam_hybrid_shasta_max_ratio",
                "pref_scam_hybrid_lut_sigma"})
            assertEquals(k,"scam_hybrid_shasta_category",screen.findPreference(k).getParent().getKey());
        assertNull(screen.findPreference("scam_hybrid_frames_screen"));assertNull(screen.findPreference("scam_hybrid_diag_category"));
        PreferenceGroup noise=screen.findPreference("scam_hybrid_photo_screen");
        java.util.List<String> order=new java.util.ArrayList<>();
        for(int i=0;i<noise.getPreferenceCount();i++)order.add(noise.getPreference(i).getKey());
        int hl=order.indexOf("pref_scam_hybrid_bento_denoise_max"),dark=order.indexOf("pref_scam_hybrid_dn_dark_fade");
        assertTrue(order.toString(),hl>0&&dark>0);
        assertEquals("pref_scam_hybrid_ark_bracket_denoise",order.get(hl+1));
        assertEquals("pref_scam_hybrid_ark_chroma_denoise",order.get(dark+1));
        for(String k:new String[]{"pref_scam_hybrid_diagnostics","pref_scam_hybrid_profile","pref_scam_hybrid_dev_note",
                "shdr_capture_report","scamera_full_debug"})
            assertEquals(k,"scam_diagnostics_screen",screen.findPreference(k).getParent().getKey());
        String xml;
        try(java.io.InputStream in=new java.io.FileInputStream("src/main/res/xml/preferences.xml")){xml=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("android:title=\"([^\"@][^\"]*)\"").matcher(xml);
        java.util.List<String> literal=new java.util.ArrayList<>();while(m.find())if(!m.group(1).equals("SHDR"))literal.add(m.group(1));
        assertTrue(literal.toString(),literal.isEmpty());
    }

    /** P129: the merge is SHDR; the borrowed code names (LMC, AGC, Sabre, Bento, Shasta, Mochi, GCam, SCAM HDR) are ours now. */
    private static boolean oldName(String s){
        for(String old:new String[]{"LMC","NICE","Nice","Hybrid","Bento","Shasta","Sabre","Mochi","GCam","AGC","SCAM HDR","ArkCore","Sabre 6.1","round 4","раунда 4"})
            if(s.contains(old))return true;
        return s.toLowerCase(Locale.ROOT).contains("гибрид");
    }

    private android.view.View row(Preference p){
        android.widget.FrameLayout parent=new android.widget.FrameLayout(context);
        android.view.View row=LayoutInflater.from(context).inflate(p.getLayoutResource(),parent,false);
        android.view.ViewGroup widget=row.findViewById(android.R.id.widget_frame);
        if(widget!=null&&p.getWidgetLayoutResource()!=0)LayoutInflater.from(context).inflate(p.getWidgetLayoutResource(),widget);
        PreferenceViewHolder holder=PreferenceViewHolder.createInstanceForTests(row);
        p.onBindViewHolder(holder);com.particlesdevs.photoncamera.ui.settings.SettingsStyle.bind(holder,p);
        return row;
    }

    /** P6b: one row of each preference type renders as a card in the user's accent, with the expected widgets. */
    @Test public void everyRowTypeRendersInTheModuleCardStyle(){
        prefs.edit().putString(com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette.KEY,"blue").commit();
        int accent=com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette.color(context);
        PreferenceManager pm=new PreferenceManager(context);PreferenceScreen screen=pm.createPreferenceScreen(context);
        PreferenceScreen page=pm.createPreferenceScreen(context);page.setKey("t_page");page.setTitle("Страница");screen.addPreference(page);
        PreferenceCategory category=new PreferenceCategory(context);category.setKey("t_cat");category.setTitle("Группа");screen.addPreference(category);
        SwitchPreferenceCompat sw=new SwitchPreferenceCompat(context);sw.setKey("t_switch");sw.setTitle("Переключатель");sw.setWidgetLayoutResource(androidx.preference.R.layout.preference_widget_switch_compat); // what the fragment theme gives it
        category.addPreference(sw);sw.setChecked(true);
        ListPreference list=new ListPreference(context);list.setKey("t_list");list.setTitle("Список");list.setSummary("%s. Описание");
        list.setEntries(new CharSequence[]{"Первый","Второй"});list.setEntryValues(new CharSequence[]{"a","b"});category.addPreference(list);list.setValue("b");
        EditTextPreference edit=new EditTextPreference(context);edit.setKey("t_edit");edit.setTitle("Поле");category.addPreference(edit);edit.setText("42");
        com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference seek=
                new com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference(context);
        seek.setKey("t_seek");seek.setTitle("Ползунок");category.addPreference(seek);
        Preference info=new Preference(context);info.setKey("t_info");info.setSelectable(false);info.setSummary("Пояснение");category.addPreference(info);
        Preference action=new Preference(context);action.setKey("t_action");action.setTitle("Действие");category.addPreference(action);
        Preference tile=new Preference(context);tile.setKey("pref_reset_preferences_key");tile.setTitle("Сбросить всё");tile.setIcon(R.drawable.settings_ic_reset);category.addPreference(tile);
        com.particlesdevs.photoncamera.ui.settings.SettingsStyle.apply(screen);
        for(Preference p:new Preference[]{page,sw,list,edit,seek,action,tile}){
            android.view.View r=row(p);
            assertTrue(p.getKey()+" is a card",r.getBackground() instanceof android.graphics.drawable.RippleDrawable);
            android.widget.ImageView icon=r.findViewById(android.R.id.icon);
            assertNotNull(p.getKey(),icon);assertNotNull(p.getKey()+" has an icon",icon.getDrawable());
            // P136: the multi-tone glyphs are drawn as they are; only the reset tile takes the warning colour
            if(p==tile)assertNotNull(p.getKey()+" tinted",icon.getColorFilter());else assertNull(p.getKey()+" tinted",icon.getColorFilter());
        }
        assertNotNull("pages end in a chevron",row(page).findViewById(R.id.settings_chevron));
        android.widget.TextView label=row(category).findViewById(android.R.id.title);assertEquals(accent,label.getCurrentTextColor());
        android.view.View lr=row(list);android.widget.TextView value=lr.findViewById(R.id.settings_value);
        assertEquals("Второй",value.getText().toString());assertEquals("SIMPLE_UI §4: the value on the right, muted",com.particlesdevs.photoncamera.ui.settings.SettingsStyle.MUTED,value.getCurrentTextColor());
        assertEquals("the value is not repeated in the summary","Описание",((android.widget.TextView)lr.findViewById(android.R.id.summary)).getText().toString());
        assertEquals("42",((android.widget.TextView)row(edit).findViewById(R.id.settings_value)).getText().toString());
        android.view.View sr=row(seek);android.widget.SeekBar bar=sr.findViewById(R.id.seekbar);
        assertEquals(accent,bar.getProgressTintList().getDefaultColor());
        assertEquals(accent,((android.widget.TextView)sr.findViewById(R.id.seekbar_value)).getCurrentTextColor());
        android.view.ViewGroup holder=new android.widget.FrameLayout(context);
        android.view.View swr=LayoutInflater.from(context).inflate(sw.getLayoutResource(),holder,false);
        LayoutInflater.from(context).inflate(sw.getWidgetLayoutResource(),(android.view.ViewGroup)swr.findViewById(android.R.id.widget_frame));
        PreferenceViewHolder h=PreferenceViewHolder.createInstanceForTests(swr);sw.onBindViewHolder(h);
        com.particlesdevs.photoncamera.ui.settings.SettingsStyle.bind(h,sw);
        androidx.appcompat.widget.SwitchCompat toggle=swr.findViewById(androidx.preference.R.id.switchWidget);
        assertEquals(accent,toggle.getTrackTintList().getColorForState(new int[]{android.R.attr.state_checked},0));
        assertNotNull("info rows are notes",row(info).findViewById(R.id.settings_info_icon));
        assertNull("notes are not cards",row(info).getBackground());
        assertEquals("SIMPLE_UI §4: the profile actions are rows, as the concept",R.layout.preference_card,tile.getLayoutResource());
        assertEquals(com.particlesdevs.photoncamera.ui.settings.SettingsStyle.WARN,((android.widget.TextView)row(tile).findViewById(android.R.id.title)).getCurrentTextColor());
        assertEquals("actions are written in the accent",accent,((android.widget.TextView)row(action).findViewById(android.R.id.title)).getCurrentTextColor());
        list.setEnabled(false);assertEquals(.45f,row(list).getAlpha(),1e-3);
    }

    /**
     * SIMPLE_UI §4: the root holds the concept's seven groups in order - «Для опытных» first (owner's decision), then Фото,
     * Съёмка, Экран камеры, Цвет и стиль, Приложение, О приложении - without a route row or chip.
     */
    @Test public void rootGroupsForOrdinaryUsers(){
        PreferenceScreen screen=inflate();
        String[] order={"root_expert","root_photo","root_shoot","root_view","root_colour","root_app","root_about"};
        assertEquals(order.length,screen.getPreferenceCount());
        for(int i=0;i<order.length;i++)assertEquals(order[i],screen.getPreference(i).getKey());
        androidx.preference.PreferenceGroup expert=screen.findPreference("root_expert");
        assertEquals(1,expert.getPreferenceCount());
        PreferenceScreen advanced=screen.findPreference("advanced_screen");
        for(String key:new String[]{"scam_hybrid_screen","module_profiles_screen","camera_settings_screen","pref_scam_hybrid_zsl_frames",
                "pref_zsl_buffer_count_key","scam_diagnostics_screen","camera_package_spoof_screen","developer_settings_screen",
                "pref_af_mode_key","pref_live_viewfinder_raw_key","pref_show_afdata_key"})
            assertNotNull(key,advanced.findPreference(key));
        androidx.preference.PreferenceGroup view=screen.findPreference("root_view");
        for(String key:new String[]{"pref_show_grid_key","pref_horizon","pref_face_detect_mode","pref_tracking_af_mode","pref_peak_method_key",
                "pref_split_af_ae","pref_low_light_boost","pref_show_roundedge_key"})assertNotNull(key,view.findPreference(key));
        // P189: «Настройки по модулям» is a row that opens the module group (ModuleProfilesFragment), no page of its own
        assertEquals(0,((PreferenceScreen)screen.findPreference("module_profiles_screen")).getPreferenceCount());
        assertNull(screen.findPreference(PreferenceKeys.ROUTE_KEY));
        androidx.preference.PreferenceGroup shooting=screen.findPreference("root_shoot");
        for(String key:new String[]{"pref_motion_mode","pref_camera_sounds_key","pref_antibanding_hz_key","scam_hybrid_motion_screen"})
            assertNotNull(key,shooting.findPreference(key));
        androidx.preference.PreferenceGroup colour=screen.findPreference("root_colour");
        // P134: every colour row in «Цвет» (white balance, saturation, mixer, grading, style, advanced)
        for(String key:new String[]{"pref_dcp_profile_key","pref_color_method_key","pref_scam_hybrid_colour_warmth","pref_scam_hybrid_colour_tint",
                "pref_scam_hybrid_colour_keep_warm","pref_scam_hybrid_ark_agx_sat","pref_scam_hybrid_ark_vibrance","pref_scam_hybrid_ark_vibrance_sky",
                "pref_scam_hybrid_colour_skin_protect","colour_mixer_screen","pref_scam_hybrid_colour_mix_blue_sat","colour_grading_screen",
                "pref_scam_hybrid_colour_grade_highlights_hue","pref_scam_hybrid_colour_mono","pref_scam_hybrid_colour_gamut",
                "pref_scam_hybrid_colour_hue_lock","pref_scam_hybrid_ark_ccm_sat","pref_scam_hybrid_ark_hl_white"})assertNotNull(key,colour.findPreference(key));
        PreferenceScreen cameras=screen.findPreference("camera_settings_screen");
        assertNotNull(cameras.findPreference("capture_settings_screen"));assertNotNull(cameras.findPreference("pref_sensor_config_submenu"));
        androidx.preference.PreferenceGroup ui=screen.findPreference("root_app");
        for(String key:new String[]{"pref_theme_key","pref_theme_accent_key","pref_hide_gallery_icon_key","pref_shade_transparency",
                "backup_settings_screen"})assertNotNull(key,ui.findPreference(key));
        PreferenceScreen noise=screen.findPreference("noise_model_screen");
        assertNotNull("the SHDR noise source next to the profile",noise.findPreference("pref_scam_hybrid_noise_source"));
    }

    /** P6b: inner pages (sliders, lists, tiles) render at 360 dp; the PNGs are kept for a look (build/reports/module-concept). */
    @Test public void innerPagesRenderAsCards() throws Exception {
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();fm.executePendingTransactions();
            {   // SIMPLE_UI §4: the root as the concept draws it (groups of card rows)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                android.view.View view=fm.findFragmentById(R.id.settings_container).requireView();int exact=android.view.View.MeasureSpec.EXACTLY;
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(360,exact),android.view.View.MeasureSpec.makeMeasureSpec(2400,exact));view.layout(0,0,360,2400);
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(360,2400,android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
                java.io.File dir=new java.io.File("build/reports/module-concept");dir.mkdirs();
                try(var out=new java.io.FileOutputStream(new java.io.File(dir,"page-root.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
            }
            for(String key:new String[]{"scam_hybrid_screen","scam_hybrid_capture_screen","output_settings_screen","scam_hybrid_ark_sharp_screen","advanced_screen","interface_settings_screen","photo_style_screen","shade_transparency_screen","backup_settings_screen","photo_size_screen"}){
                var root=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
                PreferenceScreen target=root.findPreference(key);if(target==null){fm.popBackStackImmediate(null,androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
                    root=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);target=root.findPreference(key);}
                assertNotNull(key,target);activity.onPreferenceStartScreen(root,target);fm.executePendingTransactions();
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                android.view.View view=fm.findFragmentById(R.id.settings_container).requireView();int exact=android.view.View.MeasureSpec.EXACTLY;
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(360,exact),android.view.View.MeasureSpec.makeMeasureSpec(1800,exact));view.layout(0,0,360,1800);
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(360,1800,android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
                java.io.File dir=new java.io.File("build/reports/module-concept");dir.mkdirs();
                try(var out=new java.io.FileOutputStream(new java.io.File(dir,"page-"+key+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
                fm.popBackStackImmediate(null,androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
            }
        }
    }

    /** P6b pickers: a list opens a bottom sheet with radio rows, a text field a sheet with the same validation. */
    @Test public void listAndTextPickersAreBottomSheets(){
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();
            PreferenceManager pm=new PreferenceManager(activity);PreferenceScreen screen=pm.createPreferenceScreen(activity);
            ListPreference list=new ListPreference(activity);list.setKey("t_sheet_list");list.setTitle("Список");
            list.setEntries(new CharSequence[]{"Первый","Второй"});list.setEntryValues(new CharSequence[]{"a","b"});screen.addPreference(list);list.setValue("a");
            assertTrue(com.particlesdevs.photoncamera.ui.settings.SettingsStyle.showDialog(activity,list));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            var sheet=(com.google.android.material.bottomsheet.BottomSheetDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull(sheet);
            // the list and the whole sheet scroll by nested scrolling: a plain ScrollView let the sheet take the drag, and in
            // landscape the fifth option of «Разрешение» could not be reached (X200 Ultra, 2026-10-15)
            assertTrue(sheet.findViewById(android.R.id.content).findViewWithTag("option_list") instanceof androidx.core.widget.NestedScrollView);
            assertTrue(sheet.findViewById(android.R.id.content).findViewWithTag("sheet_scroll") instanceof androidx.core.widget.NestedScrollView);
            assertEquals("portrait: 60 % of the screen",1900,com.particlesdevs.photoncamera.ui.settings.SettingsStyle.listCap(3168,380,1900,3.5f));
            assertEquals("landscape: what the window leaves under the title",1440-380-252,com.particlesdevs.photoncamera.ui.settings.SettingsStyle.listCap(1440,380,1300,3.5f));
            assertEquals("never under two rows",364,com.particlesdevs.photoncamera.ui.settings.SettingsStyle.listCap(600,380,864,3.5f));
            assertEquals("no window yet",864,com.particlesdevs.photoncamera.ui.settings.SettingsStyle.listCap(0,380,864,3.5f));
            sheet.findViewById(android.R.id.content).findViewWithTag("option_b").performClick();
            assertEquals("b",list.getValue());assertFalse(sheet.isShowing());
            EditTextPreference edit=new EditTextPreference(activity);edit.setKey("t_sheet_edit");edit.setTitle("Поле");screen.addPreference(edit);edit.setText("1");
            edit.setOnBindEditTextListener(field->field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER));
            edit.setOnPreferenceChangeListener((p,v)->!v.toString().isEmpty());
            assertTrue(com.particlesdevs.photoncamera.ui.settings.SettingsStyle.showDialog(activity,edit));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            sheet=(com.google.android.material.bottomsheet.BottomSheetDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            android.widget.EditText field=sheet.findViewById(android.R.id.content).findViewWithTag("sheet_field");
            assertEquals(android.text.InputType.TYPE_CLASS_NUMBER,field.getInputType());
            field.setText("");sheet.findViewById(android.R.id.content).findViewWithTag("sheet_save").performClick();
            assertEquals("a rejected value keeps the sheet open","1",edit.getText());assertTrue(sheet.isShowing());
            field.setText("7");sheet.findViewById(android.R.id.content).findViewWithTag("sheet_save").performClick();
            assertEquals("7",edit.getText());assertFalse(sheet.isShowing());
        }
    }

    @Test public void sensorOverridesMigrateToIndependentSlotsAndResetDoesNotRestoreLegacy(){
        prefs.edit().putString("module_auto_back0","3").putString("module_auto_back1","3")
            .putString("module_active","back0").putString("pref_sensorconfig_3_blackleveloverride","64").commit();
        ModuleSensorSettings.ensure("back0");ModuleSensorSettings.ensure("back1");
        assertEquals("64",prefs.getString("pref_sensorconfig_back0_blackleveloverride",""));
        prefs.edit().putString("pref_sensorconfig_back0_blackleveloverride","80").commit();
        assertEquals("64",prefs.getString("pref_sensorconfig_back1_blackleveloverride",""));
        assertEquals("back0",ModuleSensorSettings.runtimeScope("3"));
        assertEquals("4",ModuleSensorSettings.runtimeScope("4"));
        ModuleSensorSettings.reset("back0");ModuleSensorSettings.ensure("back0");
        assertFalse(prefs.contains("pref_sensorconfig_back0_blackleveloverride"));
        assertEquals("64",prefs.getString("pref_sensorconfig_back1_blackleveloverride",""));
    }
    @Test public void sensorCopyMapsSelectedFieldsToTargetModule(){
        prefs.edit().putString("module_auto_back0","3").putString("module_auto_back1","5").commit();
        ModuleSensorSettings.ensure("back0");ModuleSensorSettings.ensure("back1");
        prefs.edit().putString("pref_sensorconfig_back0_blackleveloverride","70")
            .putString("pref_sensorconfig_back1_blackleveloverride","10")
            .putString("pref_sensorconfig_back1_sessiontype","20").commit();
        ModuleSensorSettings.copy("back0",Arrays.asList("back1"),new HashSet<>(Arrays.asList("sensor_copy_blackleveloverride")));
        assertEquals("70",prefs.getString("pref_sensorconfig_back1_blackleveloverride",""));
        assertEquals("20",prefs.getString("pref_sensorconfig_back1_sessiontype",""));
    }
    @Test public void sensorEditorStartsAtActiveModuleAndDoesNotSwitchCamera(){
        prefs.edit().putString("module_auto_back0","3").putString("module_auto_back1","5")
            .putBoolean("module_visible_back0",true).putBoolean("module_visible_back1",true) // shown modules (hidden fillers are not listed)
            .putString("module_active","back1").commit();
        PreferenceScreen screen=inflate();
        SensorConfigPreferenceGenerator.ModuleSelection selection=SensorConfigPreferenceGenerator.generatePreferences(context,screen);
        assertNull("the module is picked by the page chip, not by a row on the page",screen.findPreference("pref_sensor_config_selector"));
        assertEquals("back1",selection.selected());
        assertEquals(Arrays.asList("back0","back1"),selection.slots());
        assertTrue(screen.findPreference("pref_category_sensor_back1").isVisible());
        assertFalse(screen.findPreference("pref_category_sensor_back0").isVisible());
        selection.select("back0");
        assertEquals("back1",ModuleRegistry.active());
        assertTrue(screen.findPreference("pref_category_sensor_back0").isVisible());
        assertFalse(screen.findPreference("pref_category_sensor_back1").isVisible());
    }
    /** The sensor page has no module row: its chip names the module and picks another one; the camera stays. */
    @Test public void sensorPageChipPicksTheModuleWithoutSwitchingTheCamera(){
        prefs.edit().putString("module_auto_back0","3").putString("module_auto_back1","5").putString("module_auto_back2","7")
            .putString("module_label_back0","1×").putString("module_label_back1","3×").putString("module_label_back2","6×")
            .putBoolean("module_visible_back0",true).putBoolean("module_visible_back1",true).putBoolean("module_visible_back2",true)
            .putString("module_active","back2").commit();
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var fm=controller.get().getSupportFragmentManager();
            var page=com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment.sensorPage("back1");
            fm.beginTransaction().replace(R.id.settings_container,page).commitNow();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNull(page.findPreference("pref_sensor_config_selector"));
            assertTrue(page.findPreference("pref_category_sensor_back1").isVisible());
            assertFalse(page.findPreference("pref_category_sensor_back0").isVisible());
            android.widget.TextView chip=page.requireView().findViewWithTag("settings_chip");
            assertNotNull(chip);assertTrue(chip.getText().toString(),chip.getText().toString().startsWith("Editing: 3× · ID 5"));
            chip.performClick();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            var sheet=(com.google.android.material.bottomsheet.BottomSheetDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull(sheet);sheet.findViewById(android.R.id.content).findViewWithTag("option_back0").performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(page.findPreference("pref_category_sensor_back0").isVisible());
            assertFalse(page.findPreference("pref_category_sensor_back1").isVisible());
            assertEquals("the camera keeps its module","back2",ModuleRegistry.active());
            chip=page.requireView().findViewWithTag("settings_chip");
            assertTrue(chip.getText().toString(),chip.getText().toString().startsWith("Editing: 1× · ID 3"));
        }
    }
    /**
     * P156: on a page with per-module values the chip picks the module to edit: its profile comes into the main settings, the
     * camera keeps its module; leaving the settings saves the edit into that module and brings the camera module's values back.
     */
    @Test public void moduleChipEditsAnotherModuleAndLeavingReturnsToTheCameraModule(){
        prefs.edit().putString("module_auto_back0","3").putString("module_auto_back1","5")
            .putString("module_label_back0","1×").putString("module_label_back1","3×")
            .putBoolean("module_visible_back0",true).putBoolean("module_visible_back1",true)
            .putString("module_active","back0").putBoolean("pref_save_per_lens_settings",true)
            .putString("pref_scam_hybrid_cdm","0.9").commit();
        context.getSharedPreferences("module_profiles_meta",0).edit().clear().putBoolean("baseline",true)
            .putBoolean("exists_back0",true).putBoolean("exists_back1",true).putString("active","back0").commit();
        context.getSharedPreferences("module_profile_v2_back1",0).edit().clear().putString("pref_scam_hybrid_cdm","0.5").commit();
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            android.widget.TextView chip=controller.get().findViewById(R.id.settings_container).findViewWithTag("settings_chip");
            assertNotNull(chip);
            assertTrue(chip.getText().toString(),chip.getText().toString().startsWith("Editing: 1× · ID 3")&&chip.getText().toString().endsWith("▾"));
            chip.performClick();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            var sheet=(com.google.android.material.bottomsheet.BottomSheetDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull(sheet);
            android.view.View row=sheet.findViewById(android.R.id.content).findViewWithTag("option_back0");
            assertNotNull(row);
            sheet.findViewById(android.R.id.content).findViewWithTag("option_back1").performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("back1",PreferenceKeys.profiles().editing());
            assertEquals("the 3x values to edit","0.5",prefs.getString("pref_scam_hybrid_cdm",""));
            assertEquals("the camera keeps its module","back0",ModuleRegistry.active());
            prefs.edit().putString("pref_scam_hybrid_cdm","0.4").commit();   // an edit of the 3x
            controller.get().finish();
            controller.pause();
        }
        assertEquals("back0",PreferenceKeys.profiles().editing());
        assertEquals("the camera module's value back","0.9",prefs.getString("pref_scam_hybrid_cdm",""));
        assertEquals("the edit kept in the 3x","0.4",context.getSharedPreferences("module_profile_v2_back1",0).getString("pref_scam_hybrid_cdm",""));
    }
    /** P156: a fresh start of the app opens on the back 1x module; a restored screen keeps its module; once per process. */
    @Test public void aFreshStartOpensOnTheBackOneX(){
        prefs.edit().putString("module_auto_back0","3").putString("module_auto_back1","5").putString("module_auto_front0","1")
            .putString("module_label_back0","1×").putString("module_label_back1","3×").putString("module_label_front0","1×")
            .putBoolean("module_visible_back0",true).putBoolean("module_visible_back1",true).putBoolean("module_visible_front0",true)
            .putString("module_active","back1").commit();
        ModuleRegistry.forgetHomeCheck();
        assertNull("the system restored the screen: the module stays",ModuleRegistry.openHomeOnce(false));
        assertEquals("back1",ModuleRegistry.active());
        assertNull("checked once per process",ModuleRegistry.openHomeOnce(true));
        ModuleRegistry.forgetHomeCheck();
        prefs.edit().putString("module_active","front0").commit();
        assertEquals("back0",ModuleRegistry.openHomeOnce(true));
        assertEquals("back0",ModuleRegistry.active());
        assertEquals("3",PreferenceKeys.getCameraID());
        ModuleRegistry.forgetHomeCheck();
    }
    /** A module page opens its own sensor settings, even for a hidden module; the rows keep one fixed order. */
    @Test public void sensorPageOpensAtTheRequestedModuleInFixedRowOrder(){
        prefs.edit().putString("module_auto_back0","3").putString("module_auto_back1","5")
            .putBoolean("module_visible_back0",true).putBoolean("module_visible_back1",false)
            .putString("module_active","back0").commit();
        PreferenceScreen screen=inflate();
        SensorConfigPreferenceGenerator.ModuleSelection selection=SensorConfigPreferenceGenerator.generatePreferences(context,screen,"back1");
        assertEquals("back1",selection.selected());
        assertEquals("back0",ModuleRegistry.active());
        PreferenceCategory category=screen.findPreference("pref_category_sensor_back1");
        assertTrue(category.isVisible());
        List<String> keys=new ArrayList<>();
        for(int i=0;i<category.getPreferenceCount();i++)if(category.getPreference(i).getKey().startsWith("pref_sensorconfig_back1_"))keys.add(category.getPreference(i).getKey().substring("pref_sensorconfig_back1_".length()));
        // RAW levels, stabilization (only with OIS hardware), session; then the vendor tag button (the exposure limits read by
        // nothing were removed)
        keys.remove("oismode");
        assertEquals(Arrays.asList("blackleveloverride","whiteleveloverride","sessiontype","add_tunablekey"),keys);
    }
    /**
     * P148: the module page groups its rows (basics, copies, advanced) and makes a copy with a lens crop: «1.3×» of the 1× camera
     * crops its frame 1.3x from the lens's uncropped zoom - also with the 1× button hidden -, its page shows and changes the crop;
     * a copy of a sensor-cropped module crops that module's frame.
     */
    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void aModuleCopyWithItsOwnLensCrop() throws Exception {
        for(int i=0;i<3;i++)prefs.edit().putString("module_auto_back"+i,""+(3+i)).putString("module_label_back"+i,new String[]{"1×","0.6×","2×"}[i]).putBoolean("module_visible_back"+i,true).commit();
        for(int i=3;i<8;i++)prefs.edit().putString("module_auto_back"+i,"5").putString("module_label_back"+i,"2×").putBoolean("module_visible_back"+i,false).commit(); // the hidden filler slots
        prefs.edit().putString("module_active","back0").putBoolean("module_sensorcrop_back2",true).commit();
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();
            var modules=new com.particlesdevs.photoncamera.ui.settings.ModuleSettingsFragment();fm.beginTransaction().replace(R.id.settings_container,modules).commitNow();
            assertNotNull("the page's own «add a copy» action",modules.requireView().findViewWithTag("Добавить модуль с кропом"));
            assertFalse("untouched filler slots are a count, not hidden modules",shownText(modules.requireView(),"Скрытые модули"));
            assertTrue(shownText(modules.requireView(),"свободных мест: 5"));
            renderPage(modules.requireView(),"modules-p148");
            var page=com.particlesdevs.photoncamera.ui.settings.ModuleLensFragment.module("back0");fm.beginTransaction().replace(R.id.settings_container,page).commitNow();
            renderPage(page.requireView(),"module-page-p148");
            for(String row:new String[]{"Название","Зум и кроп на сенсоре","Кнопка в видоискателе","Сделать копию с кропом","Camera ID","Сенсор и вендорные ключи"})
                assertNotNull(row,page.requireView().findViewWithTag(row));
            page.requireView().findViewWithTag("Сделать копию с кропом").performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            var sheet=(com.google.android.material.bottomsheet.BottomSheetDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull(sheet);sheet.findViewById(android.R.id.content).findViewWithTag("option_crop_1.3").performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();fm.executePendingTransactions();
            String copy=null;for(String slot:ModuleRegistry.slots())if("back0".equals(ModuleRegistry.duplicateOf(slot)))copy=slot;
            assertNotNull("a copy of the 1× module",copy);
            assertEquals("1.3×",ModuleRegistry.label(copy));
            assertEquals(1.3f,ModuleRegistry.zoom(copy),1e-4f);
            assertEquals(1.3f,ModuleRegistry.crop(copy),1e-4f);
            assertEquals("the lens's uncropped zoom: the camera crops 1.3x",1f,ModuleRegistry.nativeRatio(copy),1e-4f);
            assertTrue(ModuleRegistry.visible(copy));
            var copyPage=fm.findFragmentById(R.id.settings_container);
            assertTrue(copyPage instanceof com.particlesdevs.photoncamera.ui.settings.ModuleLensFragment);
            assertNotNull("the copy's page shows its crop",copyPage.requireView().findViewWithTag("Кроп копии"));
            assertNotNull(copyPage.requireView().findViewWithTag("Удалить копию"));
            renderPage(copyPage.requireView(),"module-copy-p148");
            prefs.edit().putBoolean("module_visible_back0",false).commit();
            assertEquals("the crop stays with the 1× button hidden",1f,ModuleRegistry.nativeRatio(copy),1e-4f);
            // the crop of the copy changed on its page: 1.5x
            copyPage.requireView().findViewWithTag("Кроп копии").performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            sheet=(com.google.android.material.bottomsheet.BottomSheetDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            sheet.findViewById(android.R.id.content).findViewWithTag("option_crop_1.5").performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1.5f,ModuleRegistry.zoom(copy),1e-4f);
            assertEquals(1.5f,ModuleRegistry.crop(copy),1e-4f);
            // a copy of the sensor-cropped 2× module: its frame is cropped 1.5x more (native = the 2× module's frame)
            String isz=ModuleRegistry.duplicate("back2",1.5f);
            assertNotNull(isz);
            assertEquals(3f,ModuleRegistry.zoom(isz),1e-4f);
            assertEquals(2f,ModuleRegistry.nativeRatio(isz),1e-4f);
            assertEquals("the original modules are unchanged",1f,ModuleRegistry.crop("back0"),0f);
            assertEquals(2f,ModuleRegistry.nativeRatio("back2"),1e-4f);
        }
    }
    /**
     * P134 / SIMPLE_UI §4c «Плёночный LUT» is its own page, as the concept: the before / after preview, three samples, the strip
     * («Нет», our film looks, the folder's .cube files, «Импорт .cube»), the strength and «Что меняется»; a card is the choice.
     */
    @Test public void filmLutPageShowsTheStripPreviewAndChanges() throws Exception {
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();fm.executePendingTransactions();
            var root=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            PreferenceScreen page=root.findPreference("film_lut_screen");
            assertNotNull(page);
            com.particlesdevs.photoncamera.ui.settings.custompreferences.FilmLutPreference lut=page.findPreference(com.particlesdevs.photoncamera.processing.color.FilmLut.KEY);
            assertNotNull(lut);
            assertNotNull("the strength on the page",page.findPreference(com.particlesdevs.photoncamera.processing.color.FilmLut.STRENGTH_KEY));
            assertNotNull("«Что меняется» on the page",page.findPreference("film_lut_changes"));
            activity.onPreferenceStartScreen(root,page);fm.executePendingTransactions();
            var shown=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            lut=shown.findPreference(com.particlesdevs.photoncamera.processing.color.FilmLut.KEY);
            lut.setFiles(c->new String[]{"Kodak 2383.cube"});
            boolean[] imported={false};
            lut.setImporter(()->imported[0]=true);
            lut.choose("");
            android.view.View view=layout(shown.requireView(),360,2000);
            for(String tag:new String[]{"lut_compare","lut_sample_0","lut_sample_1","lut_sample_2","lut_card_","lut_card_scam:warm400",
                    "lut_card_scam:mono400","lut_card_Kodak 2383.cube","lut_import","lut_changes"})
                assertNotNull(tag,view.findViewWithTag(tag));
            assertTrue("no LUT: the photo stays as it is",shownText(view.findViewWithTag("lut_changes"),com.particlesdevs.photoncamera.util.Lang.t(activity,"Плёночный образ выключен","The film look is off")));
            view.findViewWithTag("lut_import").performClick();
            assertTrue("«Импорт .cube» opens the picker",imported[0]);
            view.findViewWithTag("lut_card_scam:slide100").performClick();
            assertEquals("scam:slide100",lut.chosen());
            assertEquals("scam:slide100",prefs.getString(com.particlesdevs.photoncamera.processing.color.FilmLut.KEY,""));
            // the analysis runs off the main thread
            android.view.View chips=null;
            for(int i=0;i<100&&chips==null;i++){
                Thread.sleep(30);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                view=layout(fm.findFragmentById(R.id.settings_container).requireView(),360,2000);
                chips=view.findViewWithTag("lut_chips");
            }
            assertNotNull("«Что меняется» shows the chips",chips);
            assertTrue("a slide film is contrastier",shownText(chips,com.particlesdevs.photoncamera.util.Lang.t(activity,"Контраст","Contrast")));
            assertNotNull(view.findViewWithTag("lut_curve"));
            assertNotNull(view.findViewWithTag("lut_swatches"));
            assertTrue(shownText(view,com.particlesdevs.photoncamera.util.Lang.t(activity,"Обращаемая плёнка","Slide film")));
            prefs.edit().putString(com.particlesdevs.photoncamera.processing.color.FilmLut.STRENGTH_KEY,"0").commit();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue("strength 0: nothing changes",shownText(layout(fm.findFragmentById(R.id.settings_container).requireView(),360,2000),com.particlesdevs.photoncamera.util.Lang.t(activity,"Сила 0 %","Strength 0 %")));
            prefs.edit().putString(com.particlesdevs.photoncamera.processing.color.FilmLut.STRENGTH_KEY,"100").commit();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            Thread.sleep(300);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            view=layout(fm.findFragmentById(R.id.settings_container).requireView(),360,2000);
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(360,2000,android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
            java.io.File dir=new java.io.File("build/reports/module-concept");dir.mkdirs();
            try(var out=new java.io.FileOutputStream(new java.io.File(dir,"page-film_lut_screen.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
            view.findViewWithTag("lut_card_").performClick();
            assertEquals("",lut.chosen());
        }
    }
    /** SIMPLE_UI §4 «Прозрачность шторки»: its own page - a preview and radio rows; the choice is the sheet's alpha. */
    @Test public void shadeTransparencyPageStoresThePercentage(){
        PreferenceScreen screen=inflate();
        com.particlesdevs.photoncamera.ui.settings.custompreferences.ShadeTransparencyPreference t=screen.findPreference("pref_shade_transparency");
        assertNotNull(t);
        assertEquals(40,t.value());
        android.view.View row=LayoutInflater.from(context).inflate(t.getLayoutResource(),null,false);
        t.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(row));
        assertNotNull(row.findViewWithTag("shade_transparency_preview"));
        for(int v:new int[]{0,20,30,40,50,60})assertNotNull("option "+v,row.findViewWithTag("shade_transparency_"+v));
        row.findViewWithTag("shade_transparency_20").performClick();
        assertEquals("20",prefs.getString("pref_shade_transparency",""));
        assertEquals(.8f,com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle.sheetAlpha(context),1e-6f);
        assertTrue(row.findViewWithTag("shade_transparency_20").isSelected());
    }
    /** SIMPLE_UI §4 «Профили и резервная копия»: the active profile, the saved ones (newest first) with load / delete in place. */
    @Test public void profilesPageListsAndLoadsConfigs(){
        PreferenceScreen screen=inflate();
        com.particlesdevs.photoncamera.ui.settings.custompreferences.ProfilesPreference profiles=screen.findPreference("profiles_page");
        assertNotNull(profiles);
        java.util.List<String> files=new ArrayList<>(Arrays.asList("Night.xml","Portrait.xml"));
        String[] loaded={null};
        android.view.View row=LayoutInflater.from(context).inflate(profiles.getLayoutResource(),null,false);
        profiles.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(row));
        profiles.setActions(new com.particlesdevs.photoncamera.ui.settings.custompreferences.ProfilesPreference.Actions(){
            @Override public String[] saved(){return files.toArray(new String[0]);}
            @Override public String load(String f){loaded[0]=f;return "ok";}
            @Override public boolean delete(String f){return files.remove(f);}
        });
        assertNotNull(row.findViewWithTag("profile_active"));
        android.view.ViewGroup saved=row.findViewWithTag("profiles_saved");
        assertEquals("newest first","profile_Portrait",String.valueOf(saved.getChildAt(0).getTag()));
        row.findViewWithTag("profile_Night").performClick();
        android.view.View panel=row.findViewWithTag("profile_panel_Night");
        assertEquals(android.view.View.VISIBLE,panel.getVisibility());
        panel.findViewWithTag("profile_load").performClick();
        assertEquals("Night.xml",loaded[0]);
        panel.findViewWithTag("profile_delete").performClick();
        assertEquals(Arrays.asList("Portrait.xml"),files);
        assertNull(row.findViewWithTag("profile_Night"));
    }
    private static android.view.View layout(android.view.View view,int w,int h){
        int exact=android.view.View.MeasureSpec.EXACTLY;
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,exact),android.view.View.MeasureSpec.makeMeasureSpec(h,exact));view.layout(0,0,w,h);
        return view;
    }
    /** A TextView under {@code view} whose text contains {@code part}. */
    private static boolean shownText(android.view.View view,String part){
        if(view instanceof android.widget.TextView&&String.valueOf(((android.widget.TextView)view).getText()).contains(part))return true;
        if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)if(shownText(((android.view.ViewGroup)view).getChildAt(i),part))return true;
        return false;
    }
    /** P149: the RAW level rows say «Авто» for -1, show the module's own levels and run over that module's RAW range. */
    @Test public void rawLevelRowsShowAutoAndTheModulesOwnLevels(){
        prefs.edit().putString("module_auto_back0","3").putString("module_auto_back1","5")
            .putBoolean("module_visible_back0",true).putBoolean("module_visible_back1",true)
            .putString("module_active","back0").putString("pref_sensorconfig_back1_whiteleveloverride","20000").commit();
        java.util.function.BiFunction<android.content.Context,String,int[]> was=SensorConfigPreferenceGenerator.cameraLevels;
        List<String> asked=new ArrayList<>();
        SensorConfigPreferenceGenerator.cameraLevels=(c,id)->{asked.add(id);return "3".equals(id)?new int[]{64,1023,10}:"5".equals(id)?new int[]{256,4095,12}:null;};
        try{
            PreferenceScreen screen=inflate();
            SensorConfigPreferenceGenerator.generatePreferences(context,screen,"back0");
            com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference black=screen.findPreference("pref_sensorconfig_back0_blackleveloverride");
            com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference white=screen.findPreference("pref_sensorconfig_back0_whiteleveloverride");
            assertNotNull(black);assertNotNull(white);
            assertTrue("the module's camera, by its physical id",asked.contains("3")&&asked.contains("5"));
            assertEquals(com.particlesdevs.photoncamera.util.Lang.t("Авто","Auto"),black.valueText(-1f));
            assertEquals(com.particlesdevs.photoncamera.util.Lang.t("Авто","Auto"),white.valueText(-1f));
            assertEquals("64",black.valueText(64f));
            assertEquals("black: a quarter of the 10-bit white",256f,black.maximum(),0f);
            assertEquals("white: four bits above the declared 10",16383f,white.maximum(),0f);
            assertEquals(-1f,black.getFloatValue(),0f);
            String summary=String.valueOf(black.getSummary());
            assertTrue(summary,summary.contains(String.format(java.util.Locale.ROOT,
                com.particlesdevs.photoncamera.util.Lang.t("Авто у этого модуля: чёрный %d, белый %d (%d бит)","Auto for this module: black %d, white %d (%d bit)"),64,1023,10)));
            com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference white1=screen.findPreference("pref_sensorconfig_back1_whiteleveloverride");
            assertEquals("12-bit module: up to 16 bits",65535f,white1.maximum(),0f);
            assertEquals("a stored value beyond the range stays as the shot uses it",20000f,white1.getFloatValue(),0f);
            // an unknown camera keeps the annotation's range, still «Авто»
            SensorConfigPreferenceGenerator.cameraLevels=(c,id)->null;
            PreferenceScreen plain=inflate();
            SensorConfigPreferenceGenerator.generatePreferences(context,plain,"back0");
            com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference fallback=plain.findPreference("pref_sensorconfig_back0_blackleveloverride");
            assertEquals(8192f,fallback.maximum(),0f);
            assertEquals(com.particlesdevs.photoncamera.util.Lang.t("Авто","Auto"),fallback.valueText(-1f));
        }finally{SensorConfigPreferenceGenerator.cameraLevels=was;}
    }
}
