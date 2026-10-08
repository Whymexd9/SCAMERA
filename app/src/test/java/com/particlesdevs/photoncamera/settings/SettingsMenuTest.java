package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import androidx.preference.*;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;
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
    private MockedStatic<PhotonCamera> camera;
    @Before public void setUp(){
        // SCAM HDR and its settings exist only on the Snapdragon 8 Elite; these tests cover both routes.
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
    @Test public void niceInternalTuningKeepsValidatedValues() {
        PreferenceScreen screen=inflate();
        manager.set("default_scope",PreferenceKeys.ROUTE_KEY,"scamhdr"); // SCAM HDR keys (the hybrid reads its own copies)
        assertNotNull(screen.findPreference("vivo_nice_internal_screen"));
        for(String key:new String[]{"norm","noise_scale"}) {
            assertNotNull(screen.findPreference("pref_vivo_nice_"+key));
            assertTrue(ModuleProfiles.isLocal("pref_vivo_nice_"+key));
        }
        manager.set("default_scope","pref_vivo_nice_norm","1,3");
        assertEquals(1.3f,PreferenceKeys.niceInternalValue("norm",1.1f),0f);
        manager.set("default_scope","pref_vivo_nice_noise_scale","0");
        assertEquals(.25f,PreferenceKeys.niceInternalValue("noise_scale",1f),0f);
        manager.set("default_scope","pref_vivo_nice_noise_scale","NaN");
        assertEquals(1f,PreferenceKeys.niceInternalValue("noise_scale",1f),0f);
        manager.set("default_scope","pref_vivo_nice_norm","999");
        assertEquals(2.2f,PreferenceKeys.niceInternalValue("norm",1.1f),0f);
    }
    @Test public void oneArkToneForBothRoutes() {
        PreferenceScreen screen=inflate();
        // P10: the SCAMERA tone (AgX / Exposure Fusion / headroom) is gone; ArkCore and its sharpening serve both routes.
        for(String old:new String[]{"agx_screen","lmc_hybrid_scamera_tone_screen","pref_lmc_hybrid_ark_tone","pref_vivo_hdr_gamma",
                "pref_vivo_nice_soft_tone","pref_vivo_nice_fusion_enabled","pref_nice_ae_mid","pref_expocompensation_seekbar_key"})
            assertNull(old,screen.findPreference(old));
        for(String kept:new String[]{"lmc_hybrid_ark_tone_screen","pref_lmc_hybrid_ark_ae_target","pref_lmc_hybrid_sharp_mode","lmc_curves_screen"})
            assertNotNull(kept,screen.findPreference(kept));
        java.util.Map<String,Object> values=new java.util.HashMap<>();values.put(PreferenceKeys.ROUTE_KEY,"scamhdr");
        SettingsAvailability scam=new SettingsAvailability(values);
        assertNull(scam.reason("pref_lmc_hybrid_ark_ae_target"));assertNull(scam.reason("pref_lmc_hybrid_sharp_mode"));
        assertNotNull(scam.reason("pref_lmc_hybrid_cdm"));
        prefs.edit().putString("pref_agx_contrast","20").putString("pref_vivo_hdr_gamma","1.2").putBoolean("pref_lmc_hybrid_ark_tone",false)
                .putString("pref_lmc_hybrid_ark_ae_target","0.2").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertFalse(prefs.contains("pref_agx_contrast"));assertFalse(prefs.contains("pref_vivo_hdr_gamma"));
        assertFalse(prefs.contains("pref_lmc_hybrid_ark_tone"));assertEquals("0.2",prefs.getString("pref_lmc_hybrid_ark_ae_target",""));
    }
    @Test public void scamHdrControlsPersistAndEveryRouteIsAVivoRoute() {
        PreferenceScreen screen=inflate();
        assertNotNull(screen.findPreference("vivo_hdr_screen"));
        // the hybrid by default: no state without a vivo route any more
        assertTrue(PreferenceKeys.isVivoHdrEnabled());
        manager.set("default_scope",PreferenceKeys.ROUTE_KEY,"scamhdr");
        assertTrue(PreferenceKeys.isVivoHdrEnabled());
        // P4: the extra RGB luma / chroma denoise and sharpen rows are gone, SCAM HDR keeps its own NICE controls
        for(String control:new String[]{"luma","chroma","sharpen"}) assertNull(screen.findPreference("pref_vivo_hdr_"+control));
        // the removed legacy switches no longer take a shot off the vivo routes
        manager.set("default_scope","pref_raw_mfsr_enabled_key",true);
        manager.set("default_scope","pref_remosaic_enabled_key",true);
        assertTrue(PreferenceKeys.isVivoHdrEnabled());
        manager.set("default_scope",PreferenceKeys.ROUTE_KEY,"hybrid");
        assertTrue(PreferenceKeys.isVivoHdrEnabled());assertTrue(PreferenceKeys.isLmcHybridEnabled());
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
        prefs.edit().putInt("pref_remosaic_block_key",4).putBoolean("pref_tunable_parameters_usedynamicwhitelevel",false)
                .putString("hexquad_luma","37.12345")
                .putInt("pref_vivo_nice_post_despeckle",1).putString("pref_lmc_hybrid_cdm","0.18973").commit();
        PreferenceScreen screen=inflate();
        assertEquals("4",prefs.getString("pref_remosaic_block_key",""));assertTrue(prefs.getBoolean("pref_vivo_nice_post_despeckle",false));
        visit(screen,new HashSet<>(),new ArrayList<>());
        assertEquals("0.18973",prefs.getString("pref_lmc_hybrid_cdm",""));assertEquals("37.12345",prefs.getString("hexquad_luma",""));
        assertFalse(PreferenceNumber.bool(prefs.getAll().get("pref_tunable_parameters_usedynamicwhitelevel"),true));
    }
    @Test public void perLensRestorePreservesBooleanTypesAndSharedSettings(){
        prefs.edit().putString(PreferenceKeys.Key.KEY_THEME.mValue,"keep").commit();
        manager.set(PreferenceKeys.Key.PER_LENS_FILE_NAME.mValue,"settings_for_camera_audit",
                "{\"pref_vivo_nice_post_despeckle\":true,\"pref_remosaic_block_key\":4,\"hexquad_luma\":37.125,\"ignored_null\":null,\""+PreferenceKeys.Key.KEY_THEME.mValue+"\":\"replace\"}");
        prefs.edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,true).commit();
        PreferenceKeys.loadSettingsForCamera("audit");
        assertTrue(prefs.getBoolean("pref_vivo_nice_post_despeckle",false));
        assertEquals(4.0,PreferenceNumber.read(manager.getString("default_scope","pref_remosaic_block_key","2"),2),0.0);
        assertEquals(37.125,PreferenceNumber.read(manager.getString("default_scope","hexquad_luma","0"),0),0.0);
        assertFalse(prefs.contains("ignored_null"));assertEquals("keep",prefs.getString(PreferenceKeys.Key.KEY_THEME.mValue,""));
    }

    @Test public void moduleProfilesKeepTypedValuesAndCopyOnlySelection(){
        prefs.edit().putFloat("hexquad_luma",37.125f).putBoolean("pref_vivo_nice_post_despeckle",true)
                .putString("pref_tunable_test","1.234567").putString("pref_sensorconfig_test","hardware").commit();
        ModuleProfiles profiles=PreferenceKeys.profiles();
        prefs.edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,true).commit();
        profiles.changed(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue);
        profiles.activate("back0");
        prefs.edit().putFloat("hexquad_luma",12.25f).putBoolean("pref_vivo_nice_post_despeckle",false).commit();
        profiles.activate("back1");
        assertEquals(37.125f,prefs.getFloat("hexquad_luma",0),0);
        prefs.edit().putString("pref_tunable_test","destination").commit();
        profiles.copy("back0",Arrays.asList("back1"),new HashSet<>(Arrays.asList("hexquad_luma","pref_sensorconfig_test")));
        assertEquals(12.25f,prefs.getFloat("hexquad_luma",0),0);
        assertEquals("destination",prefs.getString("pref_tunable_test",""));
        assertTrue(prefs.getBoolean("pref_vivo_nice_post_despeckle",false));
        assertEquals("hardware",prefs.getString("pref_sensorconfig_test",""));
        profiles.activate("back0");assertFalse(prefs.getBoolean("pref_vivo_nice_post_despeckle",true));
        prefs.edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,false).commit();profiles.changed(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue);
        assertEquals(37.125f,prefs.getFloat("hexquad_luma",0),0);
        prefs.edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,true).commit();profiles.changed(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue);
        profiles.activate("back0");assertEquals(12.25f,prefs.getFloat("hexquad_luma",0),0);
    }
    @Test public void removedVivoUpscaleSettingsAreGoneAndTheirStoredValuesCleared() {
        PreferenceScreen screen=inflate();
        for (String key:Arrays.asList("raisr_settings_screen","pref_raisr_enabled_key","pref_vivo_upscale_backend_key",
                "pref_vivo_downscale_kernel_key","pref_vivo_downscale_size_key","softpqe_sr_only_info","vivo_downscale_explanation"))
            assertNull(key,screen.findPreference(key));
        android.content.SharedPreferences prefs=androidx.preference.PreferenceManager.getDefaultSharedPreferences(
                org.robolectric.RuntimeEnvironment.getApplication());
        prefs.edit().putBoolean("pref_raisr_enabled_key",true).putString("pref_vivo_upscale_backend_key","vsr")
                .putString("pref_vivo_downscale_kernel_key","3").putString("pref_lmc_hybrid_cdm","0.2").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertFalse(prefs.contains("pref_raisr_enabled_key"));assertFalse(prefs.contains("pref_vivo_upscale_backend_key"));
        assertFalse(prefs.contains("pref_vivo_downscale_kernel_key"));assertEquals("0.2",prefs.getString("pref_lmc_hybrid_cdm",""));
        assertFalse(SettingsMigration.removeObsolete(prefs));
    }
    @Test public void legacyPostProcessingSettingsAreGoneAndTheirStoredValuesCleared() {
        PreferenceScreen screen=inflate();
        for (String key:Arrays.asList("rt_denoise_screen","gcam_finish_screen","aces_group_screen","capture_one_group_screen",
                "scamera_darktable_screen","optical_correction_screen","expert_raw_screen","expert_noise_screen","expert_detail_screen",
                "expert_tone_screen","pref_tunable_postpipeline_tonepipeline","pref_tunable_postpipeline_demosaicingmethod",
                "pref_saturation_seekbar_key","pref_contrast_seekbar_key","pref_sensor_sharpening_enabled","pref_noise_iso_curve_key"))
            assertNull(key,screen.findPreference(key));
        for (String key:Arrays.asList("pref_noise_model_profile_key","pref_dcp_profile_key","lmc_curves_screen","sharp_settings_screen"))
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
        PreferenceScreen config=screen.findPreference("output_settings_screen");assertNotNull(config);
        // RAW mode stays a quick bar setting; DNG crop and the tunable reset page are gone.
        for (String key:Arrays.asList("pref_save_raw_key","expert_output_screen","pref_tunable_submenu","pref_tunable_imagesaversettings_croptype"))
            assertNull(key,screen.findPreference(key));
        for (String key:Arrays.asList("pref_jpeg_quality","pref_ultrahdr_key","pref_wide169_key","pref_show_watermark_key","pref_watermark_line1",
                "pref_backup_preferences_key","pref_restore_preferences_key","pref_reset_preferences_key"))
            assertNotNull(key,config.findPreference(key));
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
        main.edit().clear().putString("pref_lmc_hybrid_cdm","0.2").putBoolean("pref_wide169_key",true).putInt("pref_lmc_hybrid_frames_x",7)
                .putLong("scamera_long",1L<<40).putFloat("pref_lmc_hybrid_gamma_x",7.18973f)
                .putStringSet("hidden_camera_ids",new HashSet<>(Arrays.asList("2","5"))).putString("pref_watermark_line1","<SHOT & \"ON\">").commit();
        meta.edit().clear().putBoolean("exists_main1",true).putString("active","main1").commit();
        main1.edit().clear().putString("pref_lmc_hybrid_cdm","0.3").commit();
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        ConfigXml.write(out,BackupRestoreUtil.header(app),BackupRestoreUtil.collect(app));
        ConfigXml.Config config=ConfigXml.read(new java.io.ByteArrayInputStream(out.toByteArray()));
        assertEquals(BackupRestoreUtil.device(),config.attributes.get("device"));
        assertEquals(new HashMap<>(main.getAll()),new HashMap<>(config.files.get(ConfigXml.MAIN)));
        assertEquals("0.3",config.files.get(BackupRestoreUtil.PROFILE_PREFIX+"main1").get("pref_lmc_hybrid_cdm"));
        // same phone: everything comes back, including the module profile
        main.edit().clear().putString("stale","x").commit();main1.edit().clear().commit();
        assertEquals(samePhone,BackupRestoreUtil.apply(app,config));
        assertFalse(main.contains("stale"));assertEquals(1L<<40,main.getLong("scamera_long",0));
        assertEquals(7.18973f,main.getFloat("pref_lmc_hybrid_gamma_x",0),0f);assertEquals(7,main.getInt("pref_lmc_hybrid_frames_x",0));
        assertEquals(new HashSet<>(Arrays.asList("2","5")),main.getStringSet("hidden_camera_ids",null));
        assertEquals("<SHOT & \"ON\">",main.getString("pref_watermark_line1",""));
        assertEquals("0.3",main1.getString("pref_lmc_hybrid_cdm",""));
        // another phone: main settings only, module profiles stay as they are
        config.attributes.put("device","oppo/op627cl1");main1.edit().clear().putString("pref_lmc_hybrid_cdm","0.5").commit();
        assertTrue(BackupRestoreUtil.apply(app,config).startsWith(otherPhone));
        assertEquals("0.5",main1.getString("pref_lmc_hybrid_cdm",""));assertEquals("0.2",main.getString("pref_lmc_hybrid_cdm",""));
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
                .putString("module_active","back1").putString("pref_lmc_hybrid_cdm","0.9").commit();
        ConfigXml.Config config=new ConfigXml.Config();
        config.attributes.put("device","oppo/op627cl1");
        java.util.Map<String,Object> srcMain=new java.util.HashMap<>();
        srcMain.put("pref_lmc_hybrid_cdm","0.2");srcMain.put("module_auto_back0","9");srcMain.put("pref_save_per_lens_settings",true);
        config.files.put(ConfigXml.MAIN,srcMain);
        java.util.Map<String,Object> meta=new java.util.HashMap<>();meta.put("active","back1");meta.put("exists_back0",true);
        config.files.put(BackupRestoreUtil.META,meta);
        java.util.Map<String,Object> p0=new java.util.HashMap<>();p0.put("pref_lmc_hybrid_cdm","0.5");p0.put("module_auto_back0","7");
        config.files.put(BackupRestoreUtil.PROFILE_PREFIX+"back0",p0);
        java.util.Map<String,Object> common=new java.util.HashMap<>();common.put("pref_lmc_hybrid_cdm","0.1");
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
        assertEquals("0.2",app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+"back0",0).getString("pref_lmc_hybrid_cdm",""));
        assertEquals("0.1",app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+"back1",0).getString("pref_lmc_hybrid_cdm",""));
        assertEquals("0.1",app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+"front0",0).getString("pref_lmc_hybrid_cdm",""));
        assertFalse(app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+"back0",0).contains("module_auto_back0"));
        android.content.SharedPreferences m=app.getSharedPreferences(BackupRestoreUtil.META,0);
        assertTrue(m.getBoolean("exists_back1",false));assertTrue(m.getBoolean("exists_front0",false));assertEquals("back1",m.getString("active",""));
        // this phone's slots stay; the active slot (3x) is in the main settings; the source's global settings came over
        assertEquals("0",main.getString("module_auto_back0",""));assertEquals("0.1",main.getString("pref_lmc_hybrid_cdm",""));
        assertTrue(main.getBoolean("pref_save_per_lens_settings",false));
        // the file this phone saves carries its own lens passports
        java.util.Map<String,?> own=BackupRestoreUtil.collect(app).get(BackupRestoreUtil.LENSES);
        assertNotNull(own);assertEquals("front",own.get("front0.facing"));assertEquals(78f,(Float)own.get("back1.focal"),0.01f);
        // per-lens settings off on the source: its main settings go to every module
        srcMain.put("pref_save_per_lens_settings",false);
        message=BackupRestoreUtil.apply(app,config);
        assertTrue(message,message.contains(allModules));
        for(String slot:new String[]{"back0","back1","front0"})
            assertEquals(slot,"0.2",app.getSharedPreferences(BackupRestoreUtil.PROFILE_PREFIX+slot,0).getString("pref_lmc_hybrid_cdm",""));
        assertEquals("0.2",main.getString("pref_lmc_hybrid_cdm",""));
    }
    @Test public void moduleCopyCatalogContainsDynamicProcessingAndSupportsDrilldown(){
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();
            var copy=new com.particlesdevs.photoncamera.ui.settings.ModuleCopyFragment();
            fm.beginTransaction().replace(R.id.settings_container,copy).commitNow();
            // P6: the root pages are the groups (no categories at the root any more)
            android.view.View processing=copy.requireView().findViewWithTag("group_photo_processing_screen");
            assertNotNull(tags(copy.requireView()),processing);assertTrue(processing.performClick());

            assertNotNull(copy.requireView().findViewWithTag("parameter_pref_sharp_amount_key"));
            activity.getOnBackPressedDispatcher().onBackPressed();
            assertNotNull(copy.requireView().findViewWithTag("group_photo_processing_screen"));
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
        android.view.ViewGroup header=(android.view.ViewGroup)((android.view.ViewGroup)view).getChildAt(0);
        android.view.ViewGroup titles=(android.view.ViewGroup)header.getChildAt(0);
        assertTrue("Header must reserve space for branding and title",header.getHeight()>=60);
        assertEquals(android.view.View.VISIBLE,header.getChildAt(1).getVisibility());
        assertTrue(titles.getChildAt(1).getTop()>=titles.getChildAt(0).getBottom());
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(400,880,android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
        java.io.File dir=new java.io.File("build/reports/module-concept");dir.mkdirs();
        try(var out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
    }
    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void conceptSelectionCopiesOnlyChosenValuesAndAccentSurvivesModuleSwitch() throws Exception {
        conceptSelectionCopiesOnlyChosenValuesAndAccentSurvivesModuleSwitch("Копировать настройки между модулями","частично","");
    }
    /** The same on an English system: the row titles and the partial-selection hint are English. */
    @Test public void conceptSelectionCopiesOnlyChosenValuesAndAccentSurvivesModuleSwitchInEnglish() throws Exception {
        conceptSelectionCopiesOnlyChosenValuesAndAccentSurvivesModuleSwitch("Copy settings between modules","partly selected","-en");
    }
    private void conceptSelectionCopiesOnlyChosenValuesAndAccentSurvivesModuleSwitch(String copyRow,String partly,String png) throws Exception {
        for(int i=0;i<3;i++)prefs.edit().putString("module_auto_back"+i,""+(3+i)).putString("module_label_back"+i,new String[]{"1×","0.4×","2.4×"}[i]).putBoolean("module_visible_back"+i,true).commit();
        prefs.edit().putString("module_active","back0").putFloat("pref_sharp_amount_key",42f).putFloat("pref_sharp_micro_amount_key",14f).commit();
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();
            var modules=new com.particlesdevs.photoncamera.ui.settings.ModuleSettingsFragment();fm.beginTransaction().replace(R.id.settings_container,modules).commitNow();renderPage(modules.requireView(),"modules"+png);
            assertEquals(android.view.View.GONE,activity.findViewById(R.id.settings_toolbar).getVisibility());
            modules.requireView().findViewWithTag(copyRow).performClick();fm.executePendingTransactions();
            var copy=(com.particlesdevs.photoncamera.ui.settings.ModuleCopyFragment)fm.findFragmentById(R.id.settings_container);renderPage(copy.requireView(),"copy"+png);
            copy.requireView().findViewWithTag("clear_selection").performClick();assertFalse(copy.requireView().findViewWithTag("primary_action").isEnabled());
            copy.requireView().findViewWithTag("group_photo_processing_screen").performClick();
            copy.requireView().findViewWithTag("parameter_pref_sharp_amount_key").performClick();renderPage(copy.requireView(),"noise"+png);
            copy.requireView().findViewWithTag("primary_action").performClick();
            var check=copy.requireView().findViewWithTag("group_check_photo_processing_screen");assertTrue(check.getContentDescription().toString(),check.getContentDescription().toString().contains(partly));
            copy.requireView().findViewWithTag("target_back2").performClick();copy.requireView().findViewWithTag("primary_action").performClick();
            assertEquals(42,PreferenceNumber.read(PreferenceKeys.profiles().snapshot("back1").get("pref_sharp_amount_key"),0),0);
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
        prefs.edit().putString(PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON.mValue,"0")
                .putString(PreferenceKeys.Key.KEY_SHOW_GRADIENT.mValue,"0").commit();
        inflate(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Map<String,?> before=prefs.getAll();
        java.util.List<String> changed=new ArrayList<>();
        SharedPreferences.OnSharedPreferenceChangeListener observer=(p,k)->changed.add(k);
        prefs.registerOnSharedPreferenceChangeListener(observer);
        for(int i=0;i<5;i++)inflate();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        prefs.unregisterOnSharedPreferenceChangeListener(observer);
        assertFalse(changed.toString(),changed.contains(PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON.mValue));
        assertFalse(changed.toString(),changed.contains(PreferenceKeys.Key.KEY_SHOW_GRADIENT.mValue));
        assertEquals(before.get(PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON.mValue),prefs.getAll().get(PreferenceKeys.Key.KEY_HIDE_GALLERY_ICON.mValue));
    }

    @Test public void nestedArkTonePageOpensWithoutParentDependency() {
        manager.set("default_scope",PreferenceKeys.ROUTE_KEY,"scamhdr");
        try(var controller=org.robolectric.Robolectric.buildActivity(
                com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)) {
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();
            fm.executePendingTransactions();
            var root=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            PreferenceScreen tone=root.findPreference("lmc_hybrid_ark_tone_screen");
            activity.onPreferenceStartScreen(root,tone);fm.executePendingTransactions();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            var page=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            assertEquals("lmc_hybrid_ark_tone_screen",page.getPreferenceScreen().getKey());
            // shared by both routes: active on SCAM HDR too
            assertTrue(page.findPreference("pref_lmc_hybrid_ark_ae_target").isEnabled());
            assertTrue(PreferenceKeys.isVivoHdrEnabled());
            camera.verify(()->PhotonCamera.restartApp(any(android.content.Context.class)),never());
        }
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
            PreferenceScreen first=root.findPreference("vivo_hdr_screen");
            assertNotNull(first);activity.onPreferenceStartScreen(root,first);fm.executePendingTransactions();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1,fm.getBackStackEntryCount());
            activity.getOnBackPressedDispatcher().onBackPressed();fm.executePendingTransactions();
            assertEquals(0,fm.getBackStackEntryCount());assertFalse(activity.isFinishing());
            assertSame(root,fm.findFragmentById(R.id.settings_container));
            camera.verify(()->PhotonCamera.restartApp(any(android.content.Context.class)),never());
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
            input.setText("hexquad_luma");
            var list=descendant(search.requireView(),androidx.recyclerview.widget.RecyclerView.class);
            assertEquals(1,list.getAdapter().getItemCount());
            list.measure(android.view.View.MeasureSpec.makeMeasureSpec(400,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(600,1073741824));list.layout(0,0,400,600);
            assertNotNull(list.findViewHolderForAdapterPosition(0));
            list.findViewHolderForAdapterPosition(0).itemView.performClick();fm.executePendingTransactions();
            var page=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            assertNotNull(page.findPreference("hexquad_luma"));
            activity.getOnBackPressedDispatcher().onBackPressed();fm.executePendingTransactions();
            input=descendant(fm.findFragmentById(R.id.settings_container).requireView(),android.widget.EditText.class);
            assertEquals("hexquad_luma",input.getText().toString());
            camera.verify(()->PhotonCamera.restartApp(any(android.content.Context.class)),never());
        }
    }


    /** P6a: the engine is «Hybrid» in Latin letters everywhere the user can read it; no «LMC», no Russian «гибрид». */
    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void noLmcOrRussianHybridInTheSettingsTexts(){
        noLmcOrRussianHybridInTheSettingsTexts("Выберите склейку «Hybrid».");
    }
    /** The same check over the English texts. */
    @Test public void noLmcOrRussianHybridInTheSettingsTextsInEnglish(){
        noLmcOrRussianHybridInTheSettingsTexts("Select the “Hybrid” merge.");
    }
    private void noLmcOrRussianHybridInTheSettingsTexts(String selectHybrid){
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
                if(s.contains("LMC")||s.toLowerCase(Locale.ROOT).contains("гибрид"))found.add(p.getKey()+": "+s);
            }
        }
        assertTrue(found.toString(),found.isEmpty());assertTrue(checked>500);
        assertEquals("Hybrid",((ListPreference)screen.findPreference(PreferenceKeys.ROUTE_KEY)).getEntries()[0].toString());
        java.util.Map<String,Object> values=new java.util.HashMap<>();values.put(PreferenceKeys.ROUTE_KEY,"scamhdr");
        assertEquals(selectHybrid,new SettingsAvailability(values).reason("pref_lmc_hybrid_cdm"));
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
            assertNotNull(p.getKey(),icon);assertNotNull(p.getKey()+" has an icon",icon.getDrawable());assertNotNull(p.getKey()+" tinted",icon.getColorFilter());
        }
        assertNotNull("pages end in a chevron",row(page).findViewById(R.id.settings_chevron));
        android.widget.TextView label=row(category).findViewById(android.R.id.title);assertEquals(accent,label.getCurrentTextColor());
        android.view.View lr=row(list);android.widget.TextView value=lr.findViewById(R.id.settings_value);
        assertEquals("Второй",value.getText().toString());assertEquals(accent,value.getCurrentTextColor());
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
        assertEquals(R.layout.preference_tile,tile.getLayoutResource());
        assertEquals(com.particlesdevs.photoncamera.ui.settings.SettingsStyle.WARN,((android.widget.TextView)row(tile).findViewById(android.R.id.title)).getCurrentTextColor());
        assertEquals("actions are written in the accent",accent,((android.widget.TextView)row(action).findViewById(android.R.id.title)).getCurrentTextColor());
        list.setEnabled(false);assertEquals(.45f,row(list).getAlpha(),1e-3);
    }

    /** P6b: the route card switches the route with its segments; the root shows the chip and dims the other route's page. */
    @Test @Config(qualifiers="ru-w400dp-h880dp-mdpi") public void routeSegmentsSwitchTheRouteAndDimTheOtherSection(){
        routeSegmentsSwitchTheRouteAndDimTheOtherSection("Активна: ","");
    }
    /** The same on an English system: the chip reads «Active: …». */
    @Test public void routeSegmentsSwitchTheRouteAndDimTheOtherSectionInEnglish(){
        routeSegmentsSwitchTheRouteAndDimTheOtherSection("Active: ","-en");
    }
    private void routeSegmentsSwitchTheRouteAndDimTheOtherSection(String active,String png){
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();fm.executePendingTransactions();
            var root=(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            android.view.View view=root.requireView();
            int exact=android.view.View.MeasureSpec.EXACTLY;
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(360*(int)context.getResources().getDisplayMetrics().density,exact),
                    android.view.View.MeasureSpec.makeMeasureSpec(1600,exact));view.layout(0,0,view.getMeasuredWidth(),1600);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            android.widget.TextView chip=view.findViewWithTag("settings_chip");
            assertNotNull(chip);assertTrue(chip.getText().toString(),chip.getText().toString().startsWith(active+"Hybrid"));
            android.view.View scam=view.findViewWithTag("route_scamhdr");assertNotNull(tags(view),scam);scam.performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("scamhdr",PreferenceKeys.mergeRoute());
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(view.getMeasuredWidth(),exact),android.view.View.MeasureSpec.makeMeasureSpec(1600,exact));
            view.layout(0,0,view.getMeasuredWidth(),1600);
            chip=view.findViewWithTag("settings_chip");assertTrue(chip.getText().toString(),chip.getText().toString().startsWith(active+"SCAM HDR"));
            androidx.recyclerview.widget.RecyclerView list=root.getListView();
            androidx.preference.PreferenceGroupAdapter adapter=(androidx.preference.PreferenceGroupAdapter)list.getAdapter();
            int hybrid=adapter.getPreferenceAdapterPosition("lmc_hybrid_screen"),scamRow=adapter.getPreferenceAdapterPosition("vivo_hdr_screen");
            assertEquals(.45f,list.findViewHolderForAdapterPosition(hybrid).itemView.getAlpha(),1e-3);
            assertEquals(1f,list.findViewHolderForAdapterPosition(scamRow).itemView.getAlpha(),1e-3);
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getMeasuredWidth(),1600,android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
            java.io.File dir=new java.io.File("build/reports/module-concept");dir.mkdirs();
            try(var out=new java.io.FileOutputStream(new java.io.File(dir,"settings-root"+png+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
            catch(java.io.IOException e){throw new RuntimeException(e);}
        }
    }

    /** P6b: inner pages (sliders, lists, tiles) render at 360 dp; the PNGs are kept for a look (build/reports/module-concept). */
    @Test public void innerPagesRenderAsCards() throws Exception {
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();fm.executePendingTransactions();
            for(String key:new String[]{"lmc_hybrid_screen","lmc_hybrid_capture_screen","output_settings_screen","lmc_hybrid_ark_sharp_screen","vivo_hdr_screen"}){
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
            assertNotNull(sheet);sheet.findViewById(android.R.id.content).findViewWithTag("option_b").performClick();
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
    @Test public void sharedPhotoExposureCurveIsFiniteAndRespondsToTarget() throws Exception {
        // The live RAW viewfinder's meter: its fixed target (no settings row since the legacy tone went).
        com.particlesdevs.photoncamera.processing.opengl.postpipeline.AutoExposureCurve model=new com.particlesdevs.photoncamera.processing.opengl.postpipeline.AutoExposureCurve();
        int[][] hist=new int[3][256];for(int c=0;c<3;c++){hist[c][20]=900;hist[c][240]=100;}
        float[] first=model.calculateCurve(hist,new float[]{1,1,1},0,0);
        assertNotNull(first);for(float v:first)assertTrue(Float.isFinite(v)&&v>=0&&v<=1);
        Field target=model.getClass().getDeclaredField("target");target.setAccessible(true);target.setFloat(model,180f);
        float[] brighter=model.calculateCurve(hist,new float[]{1,1,1},0,0);
        assertTrue(brighter[200]>first[200]);
    }
}
