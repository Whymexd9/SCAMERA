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
                p.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(row));
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
        for (String key:Arrays.asList("pref_ultrahdr_key","pref_wide169_key","pref_show_watermark_key","pref_watermark_line1",
                "pref_backup_preferences_key","pref_restore_preferences_key","pref_reset_preferences_key"))
            assertNotNull(key,config.findPreference(key));
        // The remaining dynamic pages are still filled without pref_tunable_submenu.
        for (String key:Arrays.asList("expert_viewfinder_screen","expert_sensor_screen")) {
            PreferenceScreen page=screen.findPreference(key);assertNotNull(key,page);assertTrue(key,page.getPreferenceCount()>0);
        }
        android.content.SharedPreferences prefs=androidx.preference.PreferenceManager.getDefaultSharedPreferences(
                org.robolectric.RuntimeEnvironment.getApplication());
        prefs.edit().putBoolean("pref_tunable_imagesaversettings_croptype",true).putString("pref_save_raw_key","1").commit();
        assertTrue(SettingsMigration.removeObsolete(prefs));
        assertFalse(prefs.contains("pref_tunable_imagesaversettings_croptype"));assertEquals("1",prefs.getString("pref_save_raw_key",""));
    }
    @Test public void configXmlKeepsTypesAndAppliesModuleProfilesOnlyOnTheSamePhone() throws Exception {
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
        assertEquals("Загружено: ",BackupRestoreUtil.apply(app,config));
        assertFalse(main.contains("stale"));assertEquals(1L<<40,main.getLong("scamera_long",0));
        assertEquals(7.18973f,main.getFloat("pref_lmc_hybrid_gamma_x",0),0f);assertEquals(7,main.getInt("pref_lmc_hybrid_frames_x",0));
        assertEquals(new HashSet<>(Arrays.asList("2","5")),main.getStringSet("hidden_camera_ids",null));
        assertEquals("<SHOT & \"ON\">",main.getString("pref_watermark_line1",""));
        assertEquals("0.3",main1.getString("pref_lmc_hybrid_cdm",""));
        // another phone: main settings only, module profiles stay as they are
        config.attributes.put("device","oppo/op627cl1");main1.edit().clear().putString("pref_lmc_hybrid_cdm","0.5").commit();
        assertTrue(BackupRestoreUtil.apply(app,config).startsWith("Загружены общие настройки"));
        assertEquals("0.5",main1.getString("pref_lmc_hybrid_cdm",""));assertEquals("0.2",main.getString("pref_lmc_hybrid_cdm",""));
        // an old plain shared_prefs XML reads as the main settings
        ConfigXml.Config legacy=ConfigXml.read(new java.io.ByteArrayInputStream(("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                +"<map><boolean name=\"pref_wide169_key\" value=\"false\" /><string name=\"pref_save_raw_key\">1</string></map>").getBytes("UTF-8")));
        assertTrue(legacy.legacyMap);assertEquals(false,legacy.files.get(ConfigXml.MAIN).get("pref_wide169_key"));
    }
    @Test public void moduleCopyCatalogContainsDynamicProcessingAndSupportsDrilldown(){
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();
            var copy=new com.particlesdevs.photoncamera.ui.settings.ModuleCopyFragment();
            fm.beginTransaction().replace(R.id.settings_container,copy).commitNow();
            android.view.View processing=copy.requireView().findViewWithTag("group_lmc_group_processing");
            assertNotNull(tags(copy.requireView()),processing);assertTrue(processing.performClick());

            assertNotNull(copy.requireView().findViewWithTag("parameter_pref_sharp_amount_key"));
            activity.getOnBackPressedDispatcher().onBackPressed();
            assertNotNull(copy.requireView().findViewWithTag("group_lmc_group_processing"));
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
    @Test public void conceptSelectionCopiesOnlyChosenValuesAndAccentSurvivesModuleSwitch() throws Exception {
        for(int i=0;i<3;i++)prefs.edit().putString("module_auto_back"+i,""+(3+i)).putString("module_label_back"+i,new String[]{"1×","0.4×","2.4×"}[i]).putBoolean("module_visible_back"+i,true).commit();
        prefs.edit().putString("module_active","back0").putFloat("pref_sharp_amount_key",42f).putFloat("pref_sharp_micro_amount_key",14f).commit();
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();
            var modules=new com.particlesdevs.photoncamera.ui.settings.ModuleSettingsFragment();fm.beginTransaction().replace(R.id.settings_container,modules).commitNow();renderPage(modules.requireView(),"modules");
            assertEquals(android.view.View.GONE,activity.findViewById(R.id.settings_toolbar).getVisibility());
            modules.requireView().findViewWithTag("Копировать настройки").performClick();fm.executePendingTransactions();
            var copy=(com.particlesdevs.photoncamera.ui.settings.ModuleCopyFragment)fm.findFragmentById(R.id.settings_container);renderPage(copy.requireView(),"copy");
            copy.requireView().findViewWithTag("clear_selection").performClick();assertFalse(copy.requireView().findViewWithTag("primary_action").isEnabled());
            copy.requireView().findViewWithTag("group_lmc_group_processing").performClick();
            copy.requireView().findViewWithTag("parameter_pref_sharp_amount_key").performClick();renderPage(copy.requireView(),"noise");
            copy.requireView().findViewWithTag("primary_action").performClick();
            var check=copy.requireView().findViewWithTag("group_check_lmc_group_processing");assertTrue(check.getContentDescription().toString().contains("частично"));
            copy.requireView().findViewWithTag("target_back2").performClick();copy.requireView().findViewWithTag("primary_action").performClick();
            assertEquals(42,PreferenceNumber.read(PreferenceKeys.profiles().snapshot("back1").get("pref_sharp_amount_key"),0),0);
            assertFalse(context.getSharedPreferences("module_profiles_meta",0).getBoolean("exists_back2",false));
            var dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestDialog();if(dialog!=null)dialog.dismiss();
            var accent=new com.particlesdevs.photoncamera.ui.settings.AccentSettingsFragment();fm.beginTransaction().replace(R.id.settings_container,accent).commitNow();renderPage(accent.requireView(),"accent");
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
            PreferenceScreen first=root.findPreference("vivo_settings_screen");
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
            androidx.appcompat.widget.Toolbar toolbar=activity.findViewById(R.id.settings_toolbar);
            android.view.MenuItem item=toolbar.getMenu().getItem(0);
            toolbar.getMenu().performIdentifierAction(item.getItemId(),0);fm.executePendingTransactions();
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
            .putString("module_active","back1").commit();
        PreferenceScreen screen=inflate();SensorConfigPreferenceGenerator.generatePreferences(context,screen);
        ListPreference selector=screen.findPreference("pref_sensor_config_selector");
        assertEquals("back1",selector.getValue());
        selector.getOnPreferenceChangeListener().onPreferenceChange(selector,"back0");
        assertEquals("back1",ModuleRegistry.active());
        assertTrue(screen.findPreference("pref_category_sensor_back0").isVisible());
        assertFalse(screen.findPreference("pref_category_sensor_back1").isVisible());
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
