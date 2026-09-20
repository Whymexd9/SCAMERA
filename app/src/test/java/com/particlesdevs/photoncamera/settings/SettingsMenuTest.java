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
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference;
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
    @Test public void researchOptionsAreOptInAndModuleLocal() {
        assertFalse(PreferenceKeys.isZslQualitySelectionEnabled());
        assertFalse(PreferenceKeys.isSaliencyProtectionEnabled());
        assertTrue(ModuleProfiles.isLocal("pref_zsl_quality_selection_key"));
        assertTrue(ModuleProfiles.isLocal("pref_saliency_protection_key"));
        prefs.edit().putBoolean("pref_zsl_quality_selection_key",true)
                .putBoolean("pref_saliency_protection_key",true).commit();
        assertTrue(PreferenceKeys.isZslQualitySelectionEnabled());
        assertTrue(PreferenceKeys.isSaliencyProtectionEnabled());
    }
    @Test public void manualToneControlsKeepRangesAndDefaults() {
        PreferenceScreen screen=inflate();
        assertNotNull(screen.findPreference("vivo_hdr_tone_screen"));
        String[] keys={"exposure","contrast","gamma","saturation","black","white"};
        float[] defaults={0,1,1,1,0,1};
        float[] minimum={-2,.5f,.5f,0,0,.7f};
        float[] maximum={2,2,2,2,.1f,1};
        for(int i=0;i<keys.length;i++) {
            String key="pref_vivo_hdr_"+keys[i];
            com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference p=screen.findPreference(key);
            assertNotNull(p);
            assertTrue(ModuleProfiles.isLocal(key));
            assertEquals(defaults[i],p.defaultNumber(),0f);
            assertEquals(minimum[i],p.minimum(),0f);
            assertEquals(maximum[i],p.maximum(),0f);
            manager.set("default_scope",key,"-999");
            assertEquals(minimum[i],PreferenceKeys.vivoHdrValue(keys[i],defaults[i]),0f);
            manager.set("default_scope",key,"999");
            assertEquals(maximum[i],PreferenceKeys.vivoHdrValue(keys[i],defaults[i]),0f);
        }
        manager.set("default_scope","pref_vivo_hdr_exposure","-1,25");
        assertEquals(-1.25f,PreferenceKeys.vivoHdrValue("exposure",0),0f);
    }
    @Test public void autonomousHdrControlsPersistAndRestorePreviousPipeline() {
        PreferenceScreen screen=inflate();
        assertNotNull(screen.findPreference("vivo_hdr_screen"));
        assertFalse(PreferenceKeys.isVivoHdrEnabled());
        manager.set("default_scope","pref_frame_count_key","1");
        manager.set("default_scope","pref_short_frame_count_key","0");
        manager.set("default_scope","pref_zsl_merge_algorithm_key","hdrplus");
        manager.set("default_scope","pref_vivo_hdr_enabled",true);
        assertTrue(PreferenceKeys.isVivoHdrEnabled());
        assertFalse(PreferenceKeys.isHdrPlusMergeEnabled());
        assertEquals(3,PreferenceKeys.getFrameCountValue());
        assertEquals(1,PreferenceKeys.getShortFrameCountValue());
        for(String control:new String[]{"luma","chroma","tone","shadows","local","sharpen"}) {
            String key="pref_vivo_hdr_"+control;
            assertNotNull(screen.findPreference(key));
            assertTrue(ModuleProfiles.isLocal(key));
            manager.set("default_scope",key,"0");
            assertEquals(0f,PreferenceKeys.vivoHdrValue(control,1f),0f);
            manager.set("default_scope",key,"9");
            assertEquals(2f,PreferenceKeys.vivoHdrValue(control,1f),0f);
            manager.set("default_scope",key,"NaN");
            assertEquals(1f,PreferenceKeys.vivoHdrValue(control,1f),0f);
        }
        manager.set("default_scope","pref_raw_mfsr_enabled_key",true);
        assertFalse(PreferenceKeys.isVivoHdrEnabled());
        manager.set("default_scope","pref_raw_mfsr_enabled_key",false);
        manager.set("default_scope","pref_remosaic_enabled_key",true);
        manager.set("default_scope","pref_remosaic_backend_key","hp9_hexquad");
        assertFalse(PreferenceKeys.isVivoHdrEnabled());
        manager.set("default_scope","pref_remosaic_backend_key","scamera");
        assertTrue(PreferenceKeys.isVivoHdrEnabled());
        manager.set("default_scope","pref_vivo_hdr_enabled",false);
        assertFalse(PreferenceKeys.isVivoHdrEnabled());
        assertTrue(PreferenceKeys.isHdrPlusMergeEnabled());
        assertEquals(1,PreferenceKeys.getFrameCountValue());
        assertEquals(0,PreferenceKeys.getShortFrameCountValue());
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
    @Test public void multiFrameReplacesLegacyControlsAndPersistsSource() {
        PreferenceScreen root=inflate();
        assertNotNull(root.findPreference("pref_raw_mfsr_enabled_key"));
        for(String old:new String[]{"k_detail","k_denoise","k_stretch","k_shrink","dth","dtr","tensor_stride","grad_k"})
            assertNull(root.findPreference("pref_mfsr_"+old+"_key"));
        ListPreference source=root.findPreference("pref_mfsr_source_key");
        assertArrayEquals(new CharSequence[]{"1","2","4"},source.getEntryValues());
        for(String v:new String[]{"1","2","4"}) {
            manager.set("default_scope","pref_mfsr_source_key",v);
            assertEquals(Integer.parseInt(v),PreferenceKeys.getMultiFrameBlock());
        }
        manager.set("default_scope","pref_raw_mfsr_enabled_key",true);
        manager.set("default_scope","pref_remosaic_enabled_key",true);
        manager.set("default_scope","pref_remosaic_backend_key","hp9_hexquad");
        assertFalse(PreferenceKeys.isHexQuadCaptureEnabled());
        manager.set("default_scope","pref_mfsr_calibrate_key",true);
        assertTrue(PreferenceKeys.isMultiFrameCalibration());PreferenceKeys.finishMultiFrameCalibration();
        assertFalse(PreferenceKeys.isMultiFrameCalibration());
    }
    @Test public void multiFrameUpgradePreservesMosaicButNeverCopiesCalibrationAction() {
        prefs.edit().clear().putBoolean("pref_remosaic_enabled_key",true)
                .putString("pref_remosaic_block_key","4").putString("pref_mfsr_k_detail_key","0.8")
                .putBoolean("pref_mfsr_calibrate_key",true).commit();
        SettingsMigration.migrateMultiFrame(prefs);
        assertEquals("4",prefs.getString("pref_mfsr_source_key",""));
        assertFalse(prefs.contains("pref_mfsr_k_detail_key"));
        assertFalse(prefs.contains("pref_mfsr_calibrate_key"));
        assertFalse(ModuleProfiles.isLocal("pref_mfsr_calibrate_key"));
        assertTrue(ModuleProfiles.isLocal("pref_mfsr_source_key"));
        prefs.edit().putString("pref_mfsr_source_key","2").commit();
        SettingsMigration.migrateMultiFrame(prefs);
        assertEquals("2",prefs.getString("pref_mfsr_source_key",""));
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
        prefs.edit().putInt("pref_remosaic_block_key",4).putBoolean("pref_tunable_esd3d2_enable",false)
                .putFloat("pref_tunable_esd3d2_noisetarget",.00390625f).putString("hexquad_luma","37.12345")
                .putInt("scamera_darktable_enabled",1).putString("pref_tunable_initial_gammax1","7.18973").commit();
        PreferenceScreen screen=inflate();
        assertEquals("4",prefs.getString("pref_remosaic_block_key",""));assertTrue(prefs.getBoolean("scamera_darktable_enabled",false));
        TunableSeekBarPreference tiny=screen.findPreference("pref_tunable_esd3d2_noisetarget");
        assertEquals(.00390625f,tiny.getFloatValue(),0f);
        visit(screen,new HashSet<>(),new ArrayList<>());
        assertEquals(.00390625f,prefs.getFloat("pref_tunable_esd3d2_noisetarget",0),0f);
        assertEquals("7.18973",prefs.getString("pref_tunable_initial_gammax1",""));assertEquals("37.12345",prefs.getString("hexquad_luma",""));
        assertFalse(PreferenceNumber.bool(prefs.getAll().get("pref_tunable_esd3d2_enable"),true));
    }
    @Test public void duplicateNoiseToggleMigrationPreservesDisabledState(){
        prefs.edit().putInt("pref_tunable_esd4d_enableadaptivenoise",0).putString("pref_noise_dynamic_enabled_key","1").commit();
        SettingsMigration.prepare(context,prefs);assertFalse(PreferenceNumber.bool(prefs.getAll().get("pref_noise_dynamic_enabled_key"),true));
        prefs.edit().putBoolean("pref_noise_dynamic_enabled_key",true).commit();SettingsMigration.prepare(context,prefs);
        assertTrue(PreferenceNumber.bool(prefs.getAll().get("pref_noise_dynamic_enabled_key"),false));
    }
    @Test public void perLensRestorePreservesBooleanTypesAndSharedSettings(){
        prefs.edit().putString(PreferenceKeys.Key.KEY_THEME.mValue,"keep").commit();
        manager.set(PreferenceKeys.Key.PER_LENS_FILE_NAME.mValue,"settings_for_camera_audit",
                "{\"scamera_darktable_enabled\":true,\"pref_remosaic_block_key\":4,\"hexquad_luma\":37.125,\"ignored_null\":null,\""+PreferenceKeys.Key.KEY_THEME.mValue+"\":\"replace\"}");
        prefs.edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,true).commit();
        PreferenceKeys.loadSettingsForCamera("audit");
        assertTrue(prefs.getBoolean("scamera_darktable_enabled",false));
        assertEquals(4.0,PreferenceNumber.read(manager.getString("default_scope","pref_remosaic_block_key","2"),2),0.0);
        assertEquals(37.125,PreferenceNumber.read(manager.getString("default_scope","hexquad_luma","0"),0),0.0);
        assertFalse(prefs.contains("ignored_null"));assertEquals("keep",prefs.getString(PreferenceKeys.Key.KEY_THEME.mValue,""));
    }

    @Test public void moduleProfilesKeepTypedValuesAndCopyOnlySelection(){
        prefs.edit().putFloat("hexquad_luma",37.125f).putBoolean("scamera_darktable_enabled",true)
                .putString("pref_tunable_test","1.234567").putString("pref_sensorconfig_test","hardware").commit();
        ModuleProfiles profiles=PreferenceKeys.profiles();
        prefs.edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,true).commit();
        profiles.changed(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue);
        profiles.activate("back0");
        prefs.edit().putFloat("hexquad_luma",12.25f).putBoolean("scamera_darktable_enabled",false).commit();
        profiles.activate("back1");
        assertEquals(37.125f,prefs.getFloat("hexquad_luma",0),0);
        prefs.edit().putString("pref_tunable_test","destination").commit();
        profiles.copy("back0",Arrays.asList("back1"),new HashSet<>(Arrays.asList("hexquad_luma","pref_sensorconfig_test")));
        assertEquals(12.25f,prefs.getFloat("hexquad_luma",0),0);
        assertEquals("destination",prefs.getString("pref_tunable_test",""));
        assertTrue(prefs.getBoolean("scamera_darktable_enabled",false));
        assertEquals("hardware",prefs.getString("pref_sensorconfig_test",""));
        profiles.activate("back0");assertFalse(prefs.getBoolean("scamera_darktable_enabled",true));
        prefs.edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,false).commit();profiles.changed(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue);
        assertEquals(37.125f,prefs.getFloat("hexquad_luma",0),0);
        prefs.edit().putBoolean(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue,true).commit();profiles.changed(PreferenceKeys.Key.KEY_SAVE_PER_LENS_SETTINGS.mValue);
        profiles.activate("back0");assertEquals(12.25f,prefs.getFloat("hexquad_luma",0),0);
    }
    @Test public void vivoLanczosChoicesPersistPerModuleAndHaveCorrectSizes() {
        PreferenceScreen screen=inflate();
        ListPreference kernel=screen.findPreference("pref_vivo_downscale_kernel_key");
        ListPreference size=screen.findPreference("pref_vivo_downscale_size_key");
        assertNotNull(kernel);assertNotNull(size);
        assertEquals(0,PreferenceKeys.getVivoDownscaleKernel());
        assertEquals("original",PreferenceKeys.getVivoDownscaleSize());
        assertTrue(ModuleProfiles.isLocal(kernel.getKey()));assertTrue(ModuleProfiles.isLocal(size.getKey()));
        Map<String,Object> values=new HashMap<>();
        assertNotNull(new SettingsAvailability(values).reason(kernel.getKey()));
        values.put("pref_raisr_enabled_key",true);
        assertNull(new SettingsAvailability(values).reason(kernel.getKey()));
        assertNotNull(new SettingsAvailability(values).reason(size.getKey()));
        for (int a=2;a<=5;a++) {
            kernel.setValue(Integer.toString(a));
            assertEquals(a,PreferenceKeys.getVivoDownscaleKernel());
            values.put(kernel.getKey(),Integer.toString(a));
            assertNull(new SettingsAvailability(values).reason(size.getKey()));
        }
        for (String option:new String[]{"original","75","67","50","33","25"}) {
            size.setValue(option);assertEquals(option,PreferenceKeys.getVivoDownscaleSize());
        }
        assertArrayEquals(new int[]{4096,3072},com.particlesdevs.photoncamera.processing.ml.VivoPostDownscale.outputSize(8192,6144,4096,3072,"original"));
        assertArrayEquals(new int[]{4096,3072},com.particlesdevs.photoncamera.processing.ml.VivoPostDownscale.outputSize(8192,6144,4096,3072,"50"));
        assertArrayEquals(new int[]{6144,4608},com.particlesdevs.photoncamera.processing.ml.VivoPostDownscale.outputSize(8192,6144,4096,3072,"75"));
        assertArrayEquals(new int[]{13,10},com.particlesdevs.photoncamera.processing.ml.VivoPostDownscale.outputSize(49,37,49,37,"25"));
    }
    @Test public void softPqeExposesUpscaleWithoutLegacyNoiseAndSharpControls() {
        PreferenceScreen screen=inflate();
        assertNotNull(screen.findPreference("softpqe_sr_only_info"));
        assertNull(screen.findPreference("softpqe_settings_screen"));
        for (String name:Arrays.asList("luma","chroma","sharpen","strength"))
            assertNull(screen.findPreference("pref_softpqe_"+name+"_key"));
        assertNotNull(screen.findPreference("pref_vivo_downscale_kernel_key"));
    }
    @Test public void moduleCopyCatalogContainsDynamicProcessingAndSupportsDrilldown(){
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();
            var copy=new com.particlesdevs.photoncamera.ui.settings.ModuleCopyFragment();
            fm.beginTransaction().replace(R.id.settings_container,copy).commitNow();
            android.view.View noise=copy.requireView().findViewWithTag("group_rt_denoise_screen");
            assertNotNull(noise);assertTrue(noise.performClick());
            assertNotNull(copy.requireView().findViewWithTag("parameter_rt512_luma"));
            activity.getOnBackPressedDispatcher().onBackPressed();
            assertNotNull(copy.requireView().findViewWithTag("group_rt_denoise_screen"));
        }
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
        prefs.edit().putString("module_active","back0").putFloat("rt512_luma",42f).putFloat("rt512_chroma",14f).commit();
        try(var controller=org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)){
            controller.setup();var activity=controller.get();var fm=activity.getSupportFragmentManager();
            var modules=new com.particlesdevs.photoncamera.ui.settings.ModuleSettingsFragment();fm.beginTransaction().replace(R.id.settings_container,modules).commitNow();renderPage(modules.requireView(),"modules");
            assertEquals(android.view.View.GONE,activity.findViewById(R.id.settings_toolbar).getVisibility());
            modules.requireView().findViewWithTag("Копировать настройки").performClick();fm.executePendingTransactions();
            var copy=(com.particlesdevs.photoncamera.ui.settings.ModuleCopyFragment)fm.findFragmentById(R.id.settings_container);renderPage(copy.requireView(),"copy");
            copy.requireView().findViewWithTag("clear_selection").performClick();assertFalse(copy.requireView().findViewWithTag("primary_action").isEnabled());
            copy.requireView().findViewWithTag("group_rt_denoise_screen").performClick();
            copy.requireView().findViewWithTag("parameter_rt512_luma").performClick();renderPage(copy.requireView(),"noise");
            copy.requireView().findViewWithTag("primary_action").performClick();
            var check=copy.requireView().findViewWithTag("group_check_rt_denoise_screen");assertTrue(check.getContentDescription().toString().contains("частично"));
            copy.requireView().findViewWithTag("target_back2").performClick();copy.requireView().findViewWithTag("primary_action").performClick();
            assertEquals(42,PreferenceNumber.read(PreferenceKeys.profiles().snapshot("back1").get("rt512_luma"),0),0);
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

    @Test public void settingsBackPopsOnePageWithoutRestart(){
        try(org.robolectric.android.controller.ActivityController<com.particlesdevs.photoncamera.ui.settings.SettingsActivity> controller=
                org.robolectric.Robolectric.buildActivity(com.particlesdevs.photoncamera.ui.settings.SettingsActivity.class)) {
            controller.setup();
            com.particlesdevs.photoncamera.ui.settings.SettingsActivity activity=controller.get();
            androidx.fragment.app.FragmentManager fm=activity.getSupportFragmentManager();
            fm.executePendingTransactions();
            com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment root=
                    (com.particlesdevs.photoncamera.ui.settings.SettingsActivity.SettingsFragment)fm.findFragmentById(R.id.settings_container);
            PreferenceScreen first=null;
            for(int i=0;i<root.getPreferenceScreen().getPreferenceCount();i++) {
                Preference p=root.getPreferenceScreen().getPreference(i);
                if(p instanceof PreferenceScreen){first=(PreferenceScreen)p;break;}
            }
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
    @Test public void sharedPhotoExposureCurveIsFiniteAndRespondsToTarget(){
        com.particlesdevs.photoncamera.processing.opengl.postpipeline.AutoExposureCurve model=new com.particlesdevs.photoncamera.processing.opengl.postpipeline.AutoExposureCurve();
        int[][] hist=new int[3][256];for(int c=0;c<3;c++){hist[c][20]=900;hist[c][240]=100;}
        TunableInjector.inject(model);
        float[] first=model.calculateCurve(hist,new float[]{1,1,1},0,0);
        assertNotNull(first);for(float v:first)assertTrue(Float.isFinite(v)&&v>=0&&v<=1);
        prefs.edit().putFloat("pref_tunable_autoexposurecurve_target",180).commit();TunableInjector.inject(model);
        float[] brighter=model.calculateCurve(hist,new float[]{1,1,1},0,0);
        assertTrue(brighter[200]>first[200]);
    }
}
